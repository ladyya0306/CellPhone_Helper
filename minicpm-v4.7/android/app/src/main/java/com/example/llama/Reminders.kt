package com.example.llama

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 到点提醒 —— 让"我会提醒你"第一次成为**程序能兑现**的承诺。
 *
 * 旧实现的问题：待办只存了一段文字截止时间（"明天下午三点"），程序里没有任何到点触发机制；
 *   模型说"一定准时提醒你"在结构上就不可能兑现。
 *
 * 机制：
 *   · AlarmManager.setAlarmClock（精确、可穿透 Doze；需要 USE_EXACT_ALARM，Android 13+ 安装即授予）
 *     拿不到精确权限时退回 setAndAllowWhileIdle，并在回执里如实说明"可能晚几分钟"
 *   · 提醒持久化在 files/reminders.json；开机 / 应用更新 / 心跳服务启动时自动重新登记
 *   · 触发时：发通知 + 写入当前会话（用户在 App 里也能看到"它真的提醒了"）+ 记录 firedAt（可审计）
 *   · alarm=true 的提醒用闹钟铃声并持续响铃，直到用户划掉通知（用于系统时钟设不了的闹钟）
 */
object ReminderScheduler {

    data class Reminder(
        val id: Int,
        val text: String,
        val triggerAt: Long,
        val alarm: Boolean,
        val createdAt: Long,
        val firedAt: Long = 0L,
        val cancelled: Boolean = false,
        /** 重复规则（见 RepeatRule.serialize），空 = 只响一次 */
        val repeat: String = "",
        /** 被推迟（再提醒）了几次 */
        val snoozed: Int = 0,
    )

    data class ScheduleResult(val ok: Boolean, val exact: Boolean, val reminder: Reminder?, val error: String = "")

    const val ACTION_FIRE = "com.minicpm.assistant.REMINDER_FIRE"
    const val EXTRA_ID = "reminder_id"
    const val ACTION_SNOOZE = "com.minicpm.assistant.REMINDER_SNOOZE"
    const val EXTRA_SEC = "snooze_sec"
    const val CHANNEL_REMINDER = "minicpm_reminder"
    const val CHANNEL_ALARM = "minicpm_alarm"
    private const val NOTIF_BASE = 5000

    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, "reminders.json")

    fun all(ctx: Context): List<Reminder> = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val arr = try {
            JSONArray(f.readText())
        } catch (e: Exception) {
            f.renameTo(File(ctx.filesDir, "reminders.json.corrupt-${System.currentTimeMillis()}"))
            JSONArray()
        }
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Reminder(
                id = o.optInt("id"), text = o.optString("text"), triggerAt = o.optLong("triggerAt"),
                alarm = o.optBoolean("alarm"), createdAt = o.optLong("createdAt"),
                firedAt = o.optLong("firedAt"), cancelled = o.optBoolean("cancelled"),
                repeat = o.optString("repeat", ""), snoozed = o.optInt("snoozed", 0),
            )
        }
    }

    private fun saveAll(ctx: Context, list: List<Reminder>) = synchronized(lock) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("text", r.text); put("triggerAt", r.triggerAt); put("alarm", r.alarm)
                put("createdAt", r.createdAt); put("firedAt", r.firedAt); put("cancelled", r.cancelled)
                put("repeat", r.repeat); put("snoozed", r.snoozed)
            })
        }
        AtomicFile.write(file(ctx), arr.toString())
    }

    private fun pendingIntent(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(ctx, 10_000 + id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun snoozeIntent(ctx: Context, id: Int, sec: Int): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_SNOOZE).putExtra(EXTRA_ID, id).putExtra(EXTRA_SEC, sec)
        return PendingIntent.getBroadcast(ctx, 20_000 + id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun showIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, 9_001, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 登记到系统。返回 true 表示系统 API 已接受（未抛异常） */
    private fun arm(ctx: Context, r: Reminder): Pair<Boolean, Boolean> {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false to false
        val pi = pendingIntent(ctx, r.id)
        return try {
            if (am.canScheduleExactAlarms()) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(r.triggerAt, showIntent(ctx)), pi)
                true to true
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pi)
                true to false
            }
        } catch (e: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pi)
                true to false
            } catch (e2: Exception) {
                false to false
            }
        }
    }

    fun schedule(ctx: Context, text: String, triggerAt: Long, alarm: Boolean, repeat: String = ""): ScheduleResult {
        if (triggerAt <= System.currentTimeMillis()) {
            return ScheduleResult(false, false, null, "这个时间已经过去了")
        }
        return try {
            val list = all(ctx).toMutableList()
            val id = (list.maxOfOrNull { it.id } ?: 0) + 1
            val r = Reminder(id, text.ifBlank { if (alarm) "闹钟" else "提醒" }, triggerAt, alarm, System.currentTimeMillis(), repeat = repeat)
            list.add(r)
            saveAll(ctx, list)
            // 回读核验：确实落盘了
            if (all(ctx).none { it.id == id }) return ScheduleResult(false, false, null, "写入提醒列表失败")
            val (ok, exact) = arm(ctx, r)
            if (!ok) ScheduleResult(false, false, r, "系统拒绝登记定时（AlarmManager 异常）") else ScheduleResult(true, exact, r)
        } catch (e: Exception) {
            ScheduleResult(false, false, null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun cancel(ctx: Context, id: Int): Boolean {
        return try {
            ctx.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(ctx, id))
            saveAll(ctx, all(ctx).map { if (it.id == id) it.copy(cancelled = true) else it })
            all(ctx).firstOrNull { it.id == id }?.cancelled == true
        } catch (_: Exception) {
            false
        }
    }

    private fun statePrefs(ctx: Context) = ctx.getSharedPreferences("reminder_state", Context.MODE_PRIVATE)

    /** 最近一次响过的提醒（用来回答"推迟10分钟"这类话） */
    fun lastFired(ctx: Context, withinMs: Long = 30 * 60_000L): Reminder? {
        val p = statePrefs(ctx)
        val id = p.getInt("last_id", -1)
        val at = p.getLong("last_at", 0L)
        if (id <= 0 || System.currentTimeMillis() - at > withinMs) return null
        return all(ctx).firstOrNull { it.id == id && !it.cancelled }
    }

    /**
     * 再提醒：一次性提醒原地改成"N 秒后再响"（推迟次数 +1）；重复提醒不能改（会丢掉原来的排期），另建一条一次性的。
     * 返回实际生效的那条提醒；失败返回 null。
     */
    fun snooze(ctx: Context, id: Int, sec: Int): Reminder? {
        val r = all(ctx).firstOrNull { it.id == id } ?: return null
        try { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_BASE + id) } catch (_: Exception) {}
        val now = System.currentTimeMillis()
        if (r.repeat.isNotBlank()) {
            val s = schedule(ctx, r.text, now + sec * 1000L, r.alarm)
            return s.reminder?.takeIf { s.ok }
        }
        val upd = ReminderLogic.snooze(r, now, sec)
        saveAll(ctx, all(ctx).map { if (it.id == id) upd else it })
        return if (arm(ctx, upd).first) upd else null
    }

    /** 改时间（提醒还没响时）：原地改触发时间并重新登记 */
    fun reschedule(ctx: Context, id: Int, newTriggerAt: Long): Reminder? {
        if (newTriggerAt <= System.currentTimeMillis()) return null
        val r = all(ctx).firstOrNull { it.id == id && !it.cancelled } ?: return null
        val upd = r.copy(triggerAt = newTriggerAt, firedAt = 0L)
        saveAll(ctx, all(ctx).map { if (it.id == id) upd else it })
        return if (arm(ctx, upd).first) upd else null
    }

    fun markFired(ctx: Context, id: Int) {
        val list = all(ctx).map { if (it.id == id) it.copy(firedAt = System.currentTimeMillis()) else it }
        saveAll(ctx, list)
    }

    fun upcoming(ctx: Context): List<Reminder> =
        all(ctx).filter { it.firedAt == 0L && !it.cancelled }.sortedBy { it.triggerAt }

    /** 开机 / 更新 / 服务启动时调用：重新登记未触发的提醒；错过的立即补发 */
    fun rearmAll(ctx: Context) {
        val now = System.currentTimeMillis()
        upcoming(ctx).forEach { r ->
            if (r.triggerAt > now) arm(ctx, r) else fire(ctx, r.id, missed = true)
        }
    }

    // ------------------------------------------------------------ 触发
    fun fire(ctx: Context, id: Int, missed: Boolean = false) {
        val r = all(ctx).firstOrNull { it.id == id } ?: return
        if (r.firedAt != 0L || r.cancelled) return
        ensureChannels(ctx)
        val title = if (r.alarm) "⏰ 闹钟" else "🔔 提醒"
        val rule = RepeatRule.fromString(r.repeat)
        val body = (if (missed) "（错过的提醒）" else "") + r.text + (if (rule != null) "（${RepeatRule.describe(rule)}）" else "")
        val snoozeSec = ReminderLogic.defaultSnoozeSec(AppSettings(ctx).snoozeSecOverride.takeIf { it > 0 })
        val snoozeLabel = if (snoozeSec >= 60) "${snoozeSec / 60} 分钟" else "$snoozeSec 秒"
        val n = NotificationCompat.Builder(ctx, if (r.alarm) CHANNEL_ALARM else CHANNEL_REMINDER)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(if (r.alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(showIntent(ctx))
            .addAction(android.R.drawable.ic_popup_reminder, "$snoozeLabel 后再提醒", snoozeIntent(ctx, id, snoozeSec))
            .build()
        if (r.alarm) n.flags = n.flags or Notification.FLAG_INSISTENT   // 持续响铃直到划掉
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_BASE + id, n)
        } catch (_: SecurityException) {
        }
        // 一次性提醒记下触发时间；重复提醒自动排下一次
        val after = ReminderLogic.afterFire(r, System.currentTimeMillis())
        saveAll(ctx, all(ctx).map { if (it.id == id) after else it })
        if (after.repeat.isNotBlank() && after.firedAt == 0L) arm(ctx, after)
        statePrefs(ctx).edit().putInt("last_id", id).putLong("last_at", System.currentTimeMillis()).apply()
        // 写进会话：让"它真的提醒了"在聊天记录里可见、可审计
        try {
            val settings = AppSettings(ctx)
            MemoryStore(ctx).saveMessage(
                "assistant", "$title：$body", source = "reminder",
                conversationId = settings.currentConversationId, kind = MemoryStore.KIND_NOTICE,
                personaId = settings.personaId,
            )
            ctx.sendBroadcast(Intent(HeartbeatService.ACTION_HEARTBEAT).setPackage(ctx.packageName))
        } catch (_: Exception) {
        }
    }

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_REMINDER) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_REMINDER, "到点提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "你让助手提醒的事，到点通知"
                    enableVibration(true)
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_ALARM) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALARM, "助手闹钟", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "系统时钟设不了的闹钟（如两天后），由助手到点响铃"
                    enableVibration(true)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    )
                }
            )
        }
    }
}

/** 定时到了 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(ReminderScheduler.EXTRA_ID, -1)
        if (id <= 0) return
        when (intent.action) {
            ReminderScheduler.ACTION_FIRE -> ReminderScheduler.fire(context, id)
            ReminderScheduler.ACTION_SNOOZE -> {
                val sec = ReminderLogic.defaultSnoozeSec(intent.getIntExtra(ReminderScheduler.EXTRA_SEC, 0))
                val r = ReminderScheduler.snooze(context, id, sec)
                try {
                    val settings = AppSettings(context)
                    val label = if (sec >= 60) "${sec / 60} 分钟" else "$sec 秒"
                    MemoryStore(context).saveMessage(
                        "assistant", if (r != null) "⏰ 已推迟：「${r.text}」$label 后再提醒你" else "⏰ 推迟失败：没能重新登记定时",
                        source = "reminder", conversationId = settings.currentConversationId, kind = MemoryStore.KIND_NOTICE, personaId = settings.personaId,
                    )
                    context.sendBroadcast(Intent(HeartbeatService.ACTION_HEARTBEAT).setPackage(context.packageName))
                } catch (_: Exception) {}
            }
        }
    }
}

/** 开机 / 应用更新后重新登记（vivo 可能不派发开机广播，所以心跳服务启动时也会再登记一次） */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_LOCKED_BOOT_COMPLETED ->
                try { ReminderScheduler.rearmAll(context) } catch (_: Exception) {}
        }
    }
}

/** 原子写：先写临时文件再改名；失败会抛异常（不再静默吞掉——旧版待办写失败也会回答"已加入"） */
object AtomicFile {
    fun write(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("无法写入 ${target.name}")
        }
    }
}
