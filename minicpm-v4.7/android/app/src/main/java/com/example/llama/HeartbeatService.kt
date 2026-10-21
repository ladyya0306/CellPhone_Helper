package com.example.llama

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 心跳前台服务 —— 这是"App 退到后台仍能准时开口"的唯一可靠方式。
 *
 * 为什么用前台服务：Android 12+ 会杀普通后台进程（幽灵进程杀手），
 * 只有前台服务（带常驻通知）是系统认可的长驻方式。
 *
 * 每 60 秒轮询一次，用 HeartbeatPolicy 判断该不该说。
 * **心跳绝不触发云端调用**（自动 + 云端 = 账单失控）。
 */
class HeartbeatService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settings: AppSettings
    private lateinit var todoStore: TodoStore
    private lateinit var memory: MemoryStore
    private val recentTexts = ArrayDeque<String>()

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        todoStore = TodoStore(this)
        memory = MemoryStore(this)
        createChannels()
        startForeground(NOTIF_PERSISTENT, buildPersistentNotification())
        dbg("service created, interval=" + settings.heartbeatIntervalMin + " enabled=" + settings.heartbeatEnabled)
        // vivo 可能不派发开机广播：服务每次启动都重新登记一次到点提醒（幂等）
        try {
            ReminderScheduler.ensureChannels(this)
            ReminderScheduler.rearmAll(this)
        } catch (e: Exception) {
            dbg("rearm reminders failed: ${e.message}")
        }
        // 不再用进程内的 delay 循环：进程退到后台被冻结后循环就停了（实测 17:06 之后 7 分钟零记录）。
        // 改由 AlarmManager 精确闹钟驱动，系统会把冻结的进程叫醒；这里只负责排第一拍。
        HeartbeatScheduler.arm(this, FIRST_TICK_DELAY_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_TICK && settings.heartbeatEnabled) HeartbeatScheduler.ensureArmed(this)
        if (intent?.action == ACTION_TICK) {
            // 持唤醒锁做完这一拍：CPU 可能正在休眠，闹钟只保证叫醒一瞬间
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "minicpm:heartbeat")
            wl.acquire(30_000L)
            scope.launch {
                try {
                    tick()
                } catch (e: Exception) {
                    dbg("TICK FAILED: " + (e.message ?: e.javaClass.simpleName))
                } finally {
                    if (settings.heartbeatEnabled) HeartbeatScheduler.arm(this@HeartbeatService, HeartbeatScheduler.tickDelayMs(settings))
                    if (wl.isHeld) wl.release()
                }
            }
        }
        // 用户在设置页点了「立即发一条」：绕过所有闸门，用来验证效果
        if (intent?.action == ACTION_FIRE_NOW) {
            scope.launch {
                try {
                    fireNow()
                } catch (e: Exception) {
                    dbg("fireNow failed: ${e.message}")
                }
            }
        }
        return START_STICKY
    }

    /** 立即发一条（不看闸门），仅用于手动测试 */
    private suspend fun fireNow() {
        val persona = settings.persona
        val topic = if (todoStore.all().any { !it.done }) "todo" else "care"
        val text = HeartbeatMessages.forTopic(persona, topic)
        dbg("MANUAL: $text")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            showHeartbeatNotification(persona, text, topic)
        }
        saveHeartbeat(text)
        try {
            sendBroadcast(Intent(ACTION_HEARTBEAT).setPackage(packageName))
        } catch (_: Exception) {
        }
    }

    /** 心跳消息写进**当前会话**，kind=heartbeat：界面会显示，但不会作为 assistant 轮次喂给模型 */
    private fun saveHeartbeat(text: String) {
        try {
            val conv = Conversations.current(settings, memory)
            memory.saveMessage(
                "assistant", text, source = "heartbeat", conversationId = conv,
                kind = MemoryStore.KIND_HEARTBEAT, personaId = settings.personaId,
            )
        } catch (e: Exception) {
            dbg("save heartbeat msg failed: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun dbg(line: String) {
        try {
            val f = java.io.File(filesDir, "hb_log.txt")
            if (f.exists() && f.length() > 64 * 1024) f.delete()
            f.appendText(
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) +
                    "  " + line + "\n"
            )
        } catch (_: Exception) {
        }
    }

    /** 一次判定 + 可能的开口 */
    private fun tick() {
        // 每分钟核对一次提醒：被系统清掉的闹钟重新登记、错过的立即补发（幂等）。
        // 真机上进程被 vivo 清理后，登记过的闹钟一并消失，6:30 的闹钟拖到 07:06 才响。
        try { ReminderScheduler.rearmAll(this) } catch (e: Exception) { dbg("rearm in tick failed: ${e.message}") }
        // 步数：每 10 分钟采一次计步器（没开/没授权就什么都不做）
        try { StepSource.sample(this) } catch (e: Exception) { dbg("step sample failed: ${e.message}") }
        val now = Calendar.getInstance()
        val state = settings.hbState()
        val battery = batteryPercent()
        val today = "%04d-%02d-%02d".format(
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)
        )
        val firedToday = if (state.firedDay == today) state.firedToday else 0

        // 连续未响应自动降频：每次到点未响应 +1；用户说话时清零
        val cfg = settings.hbConfig()
        val decision = HeartbeatPolicy.decide(
            cfg, state, todoStore.all().any { !it.done }, battery, now
        )
        if (decision.verdict != HeartbeatPolicy.Verdict.SPEAK) {
            dbg("SILENT: " + decision.reason)
            return
        }
        // ---- 去噪核心：内容没变就别重复说 ----
        // 这是解决"同一个待办每 10 分钟说一遍"的关键：
        // 待办列表（内容指纹）没变化，[设置·同一件事重复提醒间隔] 内不再开口（默认 240 分钟）；变了就立刻允许。
        val persona = settings.persona
        // 优先说手机里真实存在的事（下一个闹钟、日程、提醒、待办、生日）；一件都没有才退回话术模板。不开模型。
        val composed = try {
            HeartbeatCompose.compose(HeartbeatSources.collect(this, now.timeInMillis), persona, kotlin.random.Random.nextInt())
        } catch (e: Exception) {
            dbg("compose failed: ${e.message}")
            null
        }
        // 智能免打扰：看场合（勿扰、通话、会议中、睡觉中）。放在去重之前，被拦下不消耗"已说过"的次数，场合过去后还能说
        if (cfg.smartQuiet) {
            val sit = try { HeartbeatSources.situation(this, now.timeInMillis) } catch (_: Exception) { null }
            if (sit != null) {
                dbg("situation dnd=${sit.dnd} call=${sit.inCall} meeting=${sit.meetingTitle} sleeping=${sit.sleeping}")
                HeartbeatPolicy.contextSilence(cfg, sit, composed?.topic?.name ?: decision.topic)?.let { why ->
                    dbg("SILENT: 场合不合适：$why")
                    return
                }
            }
        }
        val contentKey = composed?.key ?: buildContentKey(decision.topic)
        val recentKeys = settings.hbRecentKeys
        (HeartbeatPolicy.recentKeysSilence(cfg, contentKey, recentKeys, now.timeInMillis)
            ?: HeartbeatPolicy.sameContentSilence(cfg, contentKey, settings.hbLastContentKey, settings.hbLastContentKeyAt, now.timeInMillis))
            ?.let { why ->
                dbg("SILENT: $why")
                return
            }

        val dayKey = "%04d-%02d-%02d".format(now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH) + 1, now.get(java.util.Calendar.DAY_OF_MONTH))
        if (composed != null) {
            HeartbeatPolicy.dayCapSilence(composed.topic.name, contentKey, settings.hbKeyCounts, dayKey)?.let { why ->
                dbg("SILENT: $why")
                return
            }
        }

        val topic = composed?.topic?.name?.lowercase() ?: decision.topic
        dbg("SPEAK topic=" + topic + " source=" + (if (composed != null) "card" else "template") + " firedToday=" + firedToday + " battery=" + battery)

        var text: String
        if (composed != null) {
            text = composed.text
        } else {
            // 避免与最近 4 条重复（模板池小时随机容易来回轮）
            text = HeartbeatMessages.forTopic(persona, decision.topic)
            if (text in recentTexts) {
                repeat(8) {
                    val t2 = HeartbeatMessages.forTopic(persona, decision.topic)
                    if (t2 !in recentTexts) { text = t2; return@repeat }
                }
            }
        }
        recentTexts.addLast(text)
        while (recentTexts.size > 4) recentTexts.removeFirst()
        settings.hbLastContentKey = contentKey
        settings.hbLastContentKeyAt = now.timeInMillis
        settings.hbRecentKeys = HeartbeatPolicy.pushRecentKey(recentKeys, contentKey, now.timeInMillis)
        settings.hbKeyCounts = HeartbeatPolicy.bumpCount(settings.hbKeyCounts, contentKey, dayKey)
        showHeartbeatNotification(persona, text, topic)

        saveHeartbeat(text)
        // 通知前台界面刷新（如果 App 正开着，用户能立刻看到这条消息）
        try {
            sendBroadcast(Intent(ACTION_HEARTBEAT).setPackage(packageName))
        } catch (_: Exception) {
        }
        dbg("notified: " + text)
        settings.saveHbState(
            now = now.timeInMillis,
            firedToday = firedToday + 1,
            firedDay = today,
            unresponded = state.unrespondedStreak + 1,
        )
    }

    private fun batteryPercent(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return 100
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).let {
            if (it < 0) 100 else it
        }
    }

    // ---------------------------------------------------------------- 通知
    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PERSISTENT, "常驻状态", NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "显示心跳服务正在运行"
                    setShowBadge(false)
                }
            )

            val importance = if (settings.notifySound) {
                NotificationManager.IMPORTANCE_DEFAULT
            } else {
                NotificationManager.IMPORTANCE_LOW
            }
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_HEARTBEAT, "主动消息", importance).apply {
                    description = "人设主动找你说话"
                    enableVibration(settings.notifyVibrate)
                }
            )
        }
    }

    private fun contentIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildPersistentNotification(): Notification {
        val persona = settings.persona
        return NotificationCompat.Builder(this, CHANNEL_PERSISTENT)
            .setContentTitle("${persona.avatar} ${persona.name} 在陪着你")
            .setContentText("心跳已开启 · 每 ${settings.heartbeatIntervalMin} 分钟最多一条")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(contentIntent())
            .build()
    }

    private fun showHeartbeatNotification(persona: Persona, text: String, topic: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_HEARTBEAT)
            .setContentTitle("${persona.avatar} ${persona.name}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent())
            .apply {
                if (!settings.notifySound) setSilent(true)
            }
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_HEARTBEAT_BASE + (topic.hashCode() and 0xFF), n)
    }

    /**
     * 内容指纹：用于判断"这次要说的是不是和上次同一件事"。
     * 待办类用待办列表本身做指纹——待办一变，指纹就变，立刻允许开口。
     */
    private fun buildContentKey(topic: String): String = when (topic) {
        "todo" -> "todo:" + todoStore.all().filter { !it.done }
            .joinToString(",") { "#${it.id}" }
        else -> topic
    }
    companion object {
        private const val CHANNEL_PERSISTENT = "minicpm_persistent"
        private const val CHANNEL_HEARTBEAT = "minicpm_heartbeat"
        private const val NOTIF_PERSISTENT = 1001
        private const val NOTIF_HEARTBEAT_BASE = 2000
        private const val FIRST_TICK_DELAY_MS = 15_000L
        const val ACTION_TICK = "com.minicpm.assistant.HEARTBEAT_TICK"
        const val ACTION_HEARTBEAT = "com.minicpm.assistant.HEARTBEAT"
        const val ACTION_FIRE_NOW = "com.minicpm.assistant.FIRE_NOW"

        fun start(context: Context) {
            val i = Intent(context, HeartbeatService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            HeartbeatScheduler.cancel(context)
            context.stopService(Intent(context, HeartbeatService::class.java))
        }
    }
}

/**
 * 心跳节拍器：用 AlarmManager 精确闹钟（setExactAndAllowWhileIdle）驱动 [HeartbeatService] 的每一拍。
 * 节拍间隔 = 设置里的心跳间隔限制在 1–5 分钟内（间隔再长也每 5 分钟看一眼，免得"用户刚说完话"之类的闸门错过时机）；
 * 是否开口仍由 [HeartbeatPolicy] 按设置判定。
 */
object HeartbeatScheduler {
    private const val REQ_TICK = 7001

    fun tickDelayMs(settings: AppSettings): Long = settings.heartbeatIntervalMin.coerceIn(1, 5) * 60_000L

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getForegroundService(
        ctx, REQ_TICK,
        Intent(ctx, HeartbeatService::class.java).setAction(HeartbeatService.ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private const val PREFS = "minicpm_settings"
    private const val KEY_NEXT = "hb_next_tick_at"

    /** 下一拍已经过期却没有触发（闹钟被系统/厂商清掉了）：补排。App 打开、服务被拉起时调用 */
    fun ensureArmed(ctx: Context) {
        val next = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_NEXT, 0L)
        if (next < System.currentTimeMillis() - 30_000L) arm(ctx, 15_000L)
    }

    fun arm(ctx: Context, delayMs: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val at = System.currentTimeMillis() + delayMs
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_NEXT, at).apply()
        try {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pending(ctx))
        } catch (e: SecurityException) {
            // 没有精确闹钟权限：退而求其次（可能被系统推迟，但不至于完全不响）
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pending(ctx))
        }
    }

    fun cancel(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).cancel(pending(ctx))
    }
}
