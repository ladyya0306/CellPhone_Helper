package com.example.llama

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
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
import java.io.File
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

/** 专注模式的运行时共享状态（服务写、界面读） */
object FocusRuntime {
    /** 本应用处于"已启动"状态的界面数（>0 = 用户还在本应用里） */
    val started = AtomicInteger(0)
    val inApp: Boolean get() = started.get() > 0
    @Volatile var session: FocusSession? = null
    /** 状态不好的「温柔期」截止时间：聊天里说了痛苦的话、或在专注页点了「温柔点」，期间地狱/毒舌一律按温和处理 */
    @Volatile var distressedUntilMs: Long = 0L
    @Volatile var advanced: Boolean = false
    /** 调试钩子用：由服务登记，同进程内直接调用（避免后台 startService 被系统拦） */
    @Volatile var debug: ((String, String?) -> Unit)? = null
}

/**
 * 专注模式前台服务：每秒一拍，把"用户在不在本应用里 / 屏幕亮了 / 打开了哪个分心应用 / 在不在通话"喂给 [FocusSession]，
 * 把它吐出的台词用通知弹出来（人在本应用里时由界面显示）。所有事件和台词都写 files/focus.log，便于事后核对。
 */
class FocusService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var screenReceiver: BroadcastReceiver? = null
    private var notifSeq = 0
    private val overlay by lazy { FocusOverlay(this) }
    private var overlayWanted = false
    private var lastOverlayAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        when (intent?.action) {
            ACTION_STOP -> { stopAll(clear = true); return START_NOT_STICKY }
            ACTION_EVENT -> { debugEvent(intent); return START_NOT_STICKY }
            else -> {
                startForeground(NOTIF_ONGOING, ongoingNotification("专注中"))
                if (FocusRuntime.session == null || FocusRuntime.session?.state?.finished == true) startSession(intent) else log("已有进行中的一局，忽略重复启动")
            }
        }
        return START_NOT_STICKY
    }

    private fun log(line: String) {
        try {
            File(filesDir, "focus.log").appendText(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + line + "\n")
        } catch (_: Exception) {}
    }

    private fun hourNow() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    private fun startSession(intent: Intent?) {
        val settings = AppSettings(this)
        val persona = settings.persona
        val diff = when (intent?.getIntExtra("diff", settings.focusDifficulty) ?: settings.focusDifficulty) {
            0 -> FocusEngine.Difficulty.MILD; 2 -> FocusEngine.Difficulty.HELL; else -> FocusEngine.Difficulty.SPICY
        }
        val cfg = FocusEngine.Config(
            durationMin = intent?.getIntExtra("durationMin", settings.focusDurationMin) ?: settings.focusDurationMin,
            graceSec = intent?.getIntExtra("graceSec", settings.focusGraceSec) ?: settings.focusGraceSec,
            coldWarSec = intent?.getIntExtra("coldWarSec", settings.focusColdWarSec) ?: settings.focusColdWarSec,
            difficulty = diff,
            durationSec = intent?.getIntExtra("durationSec", 0) ?: 0,
            calmBonusSec = intent?.getIntExtra("calmBonusSec", 300) ?: 300,
        )
        val advancedWanted = intent?.getBooleanExtra("advanced", settings.focusAdvanced) ?: settings.focusAdvanced
        val advanced = advancedWanted && FocusUsage.hasAccess(this)
        // 调试钩子可以直接给分心应用包名（逗号分隔），测试时不用在界面里勾选
        val distract = intent?.getStringExtra("distract")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: settings.focusDistractApps
        FocusRuntime.advanced = advanced
        overlayWanted = intent?.getBooleanExtra("overlay", settings.focusOverlay) ?: settings.focusOverlay
        lastOverlayAt = 0L
        FocusRuntime.debug = { ev, label -> simulate(ev, label) }
        val labels = distract.associateWith { FocusUsage.labelOf(this, it) }
        val bankInfo = FocusLineStore.load(this)
        val now = System.currentTimeMillis()
        val session = FocusSession(persona.id, cfg, now, seed = now.toInt())
        FocusRuntime.session = session
        File(filesDir, "focus.log").writeText("")
        log("START persona=${persona.id} cfg=$cfg advanced=$advanced(wanted=$advancedWanted access=${FocusUsage.hasAccess(this)}) distractApps=${distract.size}")
        log(bankInfo)
        // 刚才 30 分钟里用户说过痛苦的话 → 这一局从温柔开始
        try {
            MemoryStore(this).userLinesBetween(now - 30 * 60_000L, now, 20).forEach { FocusRuntime.distressedUntilMs = FocusSafety.updateUntil(it, now, FocusRuntime.distressedUntilMs) }
        } catch (_: Exception) {}
        log("温柔期=${FocusSafety.active(FocusRuntime.distressedUntilMs, now)}")
        session.begin(now, hourNow(), FocusSafety.active(FocusRuntime.distressedUntilMs, now)).forEach { emit(persona, it) }
        registerScreenReceiver(session)

        scope.launch {
            var paused = false
            var lastPkg: String? = null
            val audio = getSystemService(AudioManager::class.java)
            while (isActive && !session.state.finished) {
                val t = System.currentTimeMillis()
                val h = hourNow()
                try {
                    val inCall = audio?.mode.let { it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION }
                    if (inCall && !paused) { paused = true; log("通话中，暂停"); session.handle(FocusEngine.Event.Pause(t), h) }
                    else if (!inCall && paused) { paused = false; log("通话结束，继续"); session.handle(FocusEngine.Event.Resume(t), h) }
                    if (!paused) {
                        val st = session.state
                        val inApp = FocusRuntime.inApp
                        if (!inApp && st.awayAtMs == null) { log("离开本应用"); handle(persona, session, FocusEngine.Event.Left(t), h) }
                        if (inApp && st.awayAtMs != null) { log("回到本应用"); overlay.remove(); handle(persona, session, FocusEngine.Event.Returned(t), h) }
                        if (advanced && !inApp) {
                            val pkg = FocusUsage.foregroundPackage(this@FocusService, t)
                            if (pkg != null && pkg != packageName && pkg != lastPkg) {
                                lastPkg = pkg
                                if (pkg in distract) { log("分心应用：$pkg"); handle(persona, session, FocusEngine.Event.Distract(t, labels[pkg] ?: pkg), h) }
                            }
                        }
                        handle(persona, session, FocusEngine.Event.Tick(t), h)
                    }
                } catch (e: Exception) { log("tick 异常 ${e.javaClass.simpleName}: ${e.message}") }
                delay(1000)
            }
            val s = session.state
            log("END rage=${s.rage} leaves=${s.leaves} screenOns=${s.screenOns} distracts=${s.distracts} blowns=${s.blowns} grade=${FocusEngine.grade(s)} rejected=${session.rejected}")
            recordStats(session, completed = true)
            unregisterScreenReceiver()
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun handle(persona: Persona, session: FocusSession, e: FocusEngine.Event, hour: Int) {
        val before = session.state
        val lines = session.handle(e, hour, distressed = FocusSafety.active(FocusRuntime.distressedUntilMs, System.currentTimeMillis()))
        val after = session.state
        if (after.rage != before.rage || after.coldUntilMs != before.coldUntilMs || lines.isNotEmpty()) {
            log("rage ${before.rage}→${after.rage} cold=${after.coldUntilMs > 0} leaves=${after.leaves} peeks=${after.screenOns} distracts=${after.distracts} blowns=${after.blowns}")
        }
        lines.forEach { emit(persona, it) }
    }

    private fun emit(persona: Persona, line: FocusSession.Line) {
        log("台词[${line.kind}/${line.level}] 实际尺度=${FocusRuntime.session?.lastDifficulty} ${line.text}")
        // 人在本应用里时界面直接显示；不在时用通知把话送到锁屏/桌面上
        if (FocusRuntime.inApp) return
        // 地狱难度：再用悬浮窗把话盖在别的应用上方（规则见 FocusOverlayPolicy；没授权/没开/不是地狱档都不弹）
        val sess = FocusRuntime.session
        val nowMs = System.currentTimeMillis()
        if (sess != null && FocusOverlayPolicy.allowedFor(persona.id) &&
            FocusOverlayPolicy.shouldShow(line.kind, sess.lastDifficulty, false, overlay.canDraw(), overlayWanted, lastOverlayAt, nowMs, 4_000L)) {
            lastOverlayAt = nowMs
            log("悬浮窗：${line.text}")
            overlay.show(persona.avatar, persona.name, line.text, FocusOverlayPolicy.displayMs(line.text))
        } else if (line.kind == FocusEngine.Kind.LEAVE || line.kind == FocusEngine.Kind.DISTRACT || line.kind == FocusEngine.Kind.SCREEN_PEEK || line.kind == FocusEngine.Kind.BLOWN) {
            log("悬浮窗未弹：difficulty=${sess?.lastDifficulty} 授权=${overlay.canDraw()} 已开=$overlayWanted")
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ROAST)
            .setContentTitle("${persona.avatar} ${persona.name}")
            .setContentText(line.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line.text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openIntent())
            .build()
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ROAST_BASE + (notifSeq++ % 5), n) } catch (_: SecurityException) {}
    }

    private fun registerScreenReceiver(session: FocusSession) {
        unregisterScreenReceiver()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val t = System.currentTimeMillis()
                log("屏幕事件 ${i.action}")
                handle(AppSettings(this@FocusService).persona, session, FocusEngine.Event.ScreenOn(t), hourNow())
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
        screenReceiver = r
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        screenReceiver = null
    }

    /** 调试钩子：模拟事件（ev = left / returned / screenon / distract），用来在没有真人操作时当场验证 */
    private fun debugEvent(intent: Intent) { simulate(intent.getStringExtra("ev") ?: return, intent.getStringExtra("label")) }

    private fun simulate(ev: String, label: String?) {
        val s = FocusRuntime.session ?: return
        val persona = AppSettings(this).persona
        val t = System.currentTimeMillis()
        val e = when (ev) {
            "left" -> FocusEngine.Event.Left(t)
            "returned" -> FocusEngine.Event.Returned(t)
            "screenon" -> FocusEngine.Event.ScreenOn(t)
            "distract" -> FocusEngine.Event.Distract(t, label ?: "某应用")
            else -> return
        }
        log("[模拟] $ev")
        handle(persona, s, e, hourNow())
    }

    /** 一局的结果写进统计。中途结束的只记实际专注时长（太短的不记），不算完成、不扣分 */
    private fun recordStats(session: FocusSession, completed: Boolean) {
        try {
            val st = session.state
            val now = System.currentTimeMillis()
            val sec = ((minOf(now, st.endMs) - st.startMs) / 1000).toInt().coerceAtLeast(0)
            if (!completed && sec < 10) return
            FocusStatsStore.append(this, FocusStats.Rec(st.startMs, sec, completed, FocusEngine.grade(st), st.leaves, st.screenOns, st.distracts, st.blowns))
            log("统计：记录一局 completed=$completed sec=$sec grade=${FocusEngine.grade(st)}")
        } catch (e: Exception) { log("统计写入失败 ${e.message}") }
    }

    private fun stopAll(clear: Boolean) {
        FocusRuntime.session?.let { if (!it.state.finished) recordStats(it, completed = false) }
        scope.cancel()
        unregisterScreenReceiver()
        try { overlay.remove() } catch (_: Exception) {}
        if (clear) FocusRuntime.session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { overlay.remove() } catch (_: Exception) {}
        scope.cancel()
        unregisterScreenReceiver()
        super.onDestroy()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "专注进行中", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ROAST, "专注提醒（吐槽）", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "专注期间你离开本应用时，人设会用通知提醒你"
                enableVibration(true)
            })
        }
    }

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, FocusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ongoingNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setContentTitle("专注模式进行中").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).setContentIntent(openIntent()).build()

    companion object {
        private const val CHANNEL_ONGOING = "minicpm_focus_ongoing"
        private const val CHANNEL_ROAST = "minicpm_focus_roast"
        private const val NOTIF_ONGOING = 3001
        private const val NOTIF_ROAST_BASE = 3100
        const val ACTION_STOP = "com.minicpm.assistant.FOCUS_STOP"
        const val ACTION_EVENT = "com.minicpm.assistant.FOCUS_EVENT"

        fun start(ctx: Context, extras: android.os.Bundle? = null) {
            val i = Intent(ctx, FocusService::class.java)
            if (extras != null) i.putExtras(extras)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, FocusService::class.java).setAction(ACTION_STOP)) }
        fun event(ctx: Context, ev: String, label: String? = null) {
            ctx.startService(Intent(ctx, FocusService::class.java).setAction(ACTION_EVENT).putExtra("ev", ev).apply { if (label != null) putExtra("label", label) })
        }
    }
}
