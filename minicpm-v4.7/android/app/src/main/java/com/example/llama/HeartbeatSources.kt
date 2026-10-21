package com.example.llama

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

/**
 * 心跳的数据采集：只读手机里真实存在的东西，交给 [HeartbeatCompose] 拼成一句话。
 *
 * 能读到的：下一个系统闹钟（Android 只开放"最早的一个"）、系统日历未来 36 小时的日程、
 *          助手登记的提醒与助手闹钟、未完成待办、记忆事实。
 * 读不到的（也不会去读）：其它应用的数据、系统时钟里的完整闹钟列表、通知内容。
 * 任何一项读取失败都只是少一项，不影响其它项，更不会让心跳停摆。
 */
object HeartbeatSources {

    private const val HORIZON_MS = 36 * 3600_000L

    fun collect(ctx: Context, nowMs: Long = System.currentTimeMillis()): HeartbeatCompose.Card {
        val items = ArrayList<HeartbeatCompose.Item>()

        // 1) 下一个系统闹钟（用户在系统时钟里设的，包括本应用交给系统时钟的）
        try {
            ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
                ?.takeIf { it > nowMs && it <= nowMs + HORIZON_MS }
                ?.let { items.add(HeartbeatCompose.Item(HeartbeatCompose.Kind.ALARM, it, "闹钟")) }
        } catch (_: Exception) {
        }

        // 2) 助手登记的提醒 / 助手闹钟（还没响、没取消的）
        try {
            ReminderScheduler.all(ctx)
                .filter { it.firedAt == 0L && !it.cancelled && it.triggerAt > nowMs && it.triggerAt <= nowMs + HORIZON_MS }
                .forEach {
                    items.add(
                        if (it.alarm) HeartbeatCompose.Item(HeartbeatCompose.Kind.ALARM, it.triggerAt, "闹钟")
                        else HeartbeatCompose.Item(HeartbeatCompose.Kind.REMINDER, it.triggerAt, it.text)
                    )
                }
        } catch (_: Exception) {
        }

        // 3) 系统日历：未来 36 小时内的日程（全天事件时间不确定，不读）
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                val proj = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY)
                CalendarContract.Instances.query(ctx.contentResolver, proj, nowMs, nowMs + HORIZON_MS)?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getInt(2) == 1) continue
                        val begin = c.getLong(1)
                        if (begin <= nowMs) continue
                        items.add(HeartbeatCompose.Item(HeartbeatCompose.Kind.CALENDAR, begin, c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } ?: "日程"))
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 4) 未完成待办
        val todos = try {
            TodoStore(ctx).all().filter { !it.done }.map { HeartbeatCompose.Todo(it.id, it.text, it.dueAt.takeIf { d -> d > 0 }) }
        } catch (_: Exception) { emptyList() }

        // 5) 记忆事实（目前只用来认出"今天生日"）
        val facts = try { MemoryStore(ctx).allFacts().map { it.first } } catch (_: Exception) { emptyList() }

        // 6) 天气：只读本机数据和还新鲜的缓存，心跳绝不主动联网
        val weather = try { if (AppSettings(ctx).let { it.weatherUseSystem || it.weatherNet }) WeatherClient.get(ctx, allowNetwork = false, nowMs = nowMs).snapshot else null } catch (_: Exception) { null }

        // 7) 步数：只读本机已存的记录（采样由心跳每 10 分钟做一次）
        val steps = try { StepSource.summary(ctx, nowMs)?.takeIf { AppSettings(ctx).stepsEnabled } } catch (_: Exception) { null }

        return HeartbeatCompose.Card(nowMs, items, todos, facts, weather, steps)
    }

    /** 此刻的场合：勿扰、通话、正在开会、睡觉中。任何一项读不到都按"没有"处理（宁可多说一句，也不因为读取失败就永远沉默） */
    fun situation(ctx: Context, nowMs: Long = System.currentTimeMillis()): HeartbeatPolicy.Situation {
        val dnd = try {
            val f = ctx.getSystemService(android.app.NotificationManager::class.java)?.currentInterruptionFilter
            f != null && f != android.app.NotificationManager.INTERRUPTION_FILTER_ALL && f != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        } catch (_: Exception) { false }
        val inCall = try {
            val m = ctx.getSystemService(android.media.AudioManager::class.java)?.mode
            m == android.media.AudioManager.MODE_IN_CALL || m == android.media.AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) { false }
        var meeting: String? = null
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                val proj = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.AVAILABILITY)
                val events = ArrayList<HeartbeatPolicy.CalEvent>()
                CalendarContract.Instances.query(ctx.contentResolver, proj, nowMs, nowMs + 1)?.use { c ->
                    while (c.moveToNext()) {
                        events.add(HeartbeatPolicy.CalEvent(c.getString(0)?.trim().orEmpty(), c.getLong(1), c.getLong(2), c.getInt(3) == 1,
                            c.getInt(4) == CalendarContract.Events.AVAILABILITY_FREE))
                    }
                }
                meeting = HeartbeatPolicy.currentMeeting(events, nowMs)
            }
        } catch (_: Exception) {
        }
        val sleeping = try {
            val bm = ctx.getSystemService(android.os.BatteryManager::class.java)
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            val hour = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }.get(java.util.Calendar.HOUR_OF_DAY)
            HeartbeatPolicy.isSleeping(bm?.isCharging == true, pm?.isInteractive != false, hour)
        } catch (_: Exception) { false }
        return HeartbeatPolicy.Situation(dnd, inCall, meeting, sleeping)
    }
}
