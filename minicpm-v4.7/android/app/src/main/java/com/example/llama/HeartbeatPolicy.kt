package com.example.llama

import java.util.Calendar

/**
 * 心跳决策 —— 「此刻该不该开口」。纯 Kotlin，可在电脑上单测（见 AssistantSelfTest）。
 *
 * 设计依据（docs/离线私人智能助手-方案书.md §5）：
 *   Hermes 的心跳判据是"任务有没有变化"（客观）；陪伴型是"此刻开口有没有价值"（主观）。
 *   所以用**多因子打分 + 硬闸门**，不让模型自由发挥。
 *
 * 硬闸门（任一不满足直接跳过，不消耗预算）：
 *   启用 / 间隔 / 免打扰 / 每日上限 / 电量 / 连续未响应 / 用户刚说完话 / 同样内容冷却
 *
 * 2026-10-19 改动：策略不再直接依赖 AppSettings（需要 Context），改吃 [Config]，设置是否生效可以用用例逐项验证。
 */
object HeartbeatPolicy {

    /** 设置项的快照（来自 AppSettings.hbConfig()） */
    data class Config(
        val enabled: Boolean = true,
        val intervalMin: Int = 10,
        val quietStart: String = "23:30",
        val quietEnd: String = "07:30",
        val dailyCap: Int = 100,
        val batteryFloor: Int = 15,
        val autoDownshift: Boolean = true,
        /** 同一件事（内容指纹没变）至少隔多久才再说一次；0 = 不限制 */
        val sameContentMin: Int = 240,
        /** 智能免打扰：看场合（勿扰、通话、会议中、睡觉中）而不只是看时间 */
        val smartQuiet: Boolean = true,
    )

    /** 运行时状态（持久化在 AppSettings 的 sp 里） */
    data class State(
        val lastFiredAt: Long = 0L,        // 上次开口时间
        val firedToday: Int = 0,           // 今天已开口次数
        val firedDay: String = "",         // firedToday 属于哪一天（yyyy-MM-dd）
        val unrespondedStreak: Int = 0,    // 连续未响应次数
        val lastUserMessageAt: Long = 0L,  // 用户上次说话时间
    )

    enum class Verdict { SPEAK, SILENT }

    data class Decision(
        val verdict: Verdict,
        val reason: String,
        val topic: String = "",
    )

    private fun todayKey(now: Calendar): String =
        "%04d-%02d-%02d".format(
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)
        )

    private fun minutesOf(now: Calendar): Int =
        now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

    private fun parseHhmm(s: String, fallback: Int): Int {
        val m = Regex("""(\d{1,2})\s*[:：]\s*(\d{1,2})""").find(s.trim()) ?: return fallback
        val h = m.groupValues[1].toIntOrNull() ?: return fallback
        val mi = m.groupValues[2].toIntOrNull() ?: return fallback
        return (h.coerceIn(0, 23)) * 60 + mi.coerceIn(0, 59)
    }

    /** 是否处于免打扰时段（支持跨零点，如 23:30–07:30） */
    private fun inQuietHours(nowMin: Int, startMin: Int, endMin: Int): Boolean {
        if (startMin == endMin) return false
        return if (startMin < endMin) {
            nowMin in startMin until endMin
        } else {
            // 跨零点
            nowMin >= startMin || nowMin < endMin
        }
    }

    /** 按时间挑一个话题，让人设的话术有的放矢 */
    private fun pickTopic(nowMin: Int, hasPendingTodo: Boolean): String = when {
        hasPendingTodo && nowMin >= 8 * 60 && nowMin < 22 * 60 -> "todo"
        nowMin >= 6 * 60 + 30 && nowMin < 9 * 60 -> "morning"
        nowMin >= 11 * 60 + 30 && nowMin < 13 * 60 -> "noon"
        nowMin >= 17 * 60 + 30 && nowMin < 19 * 60 + 30 -> "evening"
        nowMin >= 21 * 60 -> "night"
        else -> "care"
    }

    fun decide(
        cfg: Config,
        state: State,
        hasPendingTodo: Boolean,
        batteryPercent: Int,
        now: Calendar = Calendar.getInstance(),
    ): Decision {
        if (!cfg.enabled) {
            return Decision(Verdict.SILENT, "未启用主动消息")
        }

        val nowMs = now.timeInMillis
        val intervalMs = cfg.intervalMin * 60_000L
        // 定时闹钟与上次开口恰好相隔一个间隔，容忍几秒的抖动，免得整整漏掉一拍
        if (state.lastFiredAt > 0 && nowMs - state.lastFiredAt < intervalMs - TICK_JITTER_MS) {
            val waitMin = (intervalMs - (nowMs - state.lastFiredAt)) / 60_000L
            return Decision(Verdict.SILENT, "距上次才 ${(nowMs - state.lastFiredAt) / 60_000L} 分钟，还需 $waitMin 分钟")
        }

        val nowMin = minutesOf(now)
        val qStart = parseHhmm(cfg.quietStart, 23 * 60 + 30)
        val qEnd = parseHhmm(cfg.quietEnd, 7 * 60 + 30)
        if (inQuietHours(nowMin, qStart, qEnd)) {
            return Decision(Verdict.SILENT, "免打扰时段（${cfg.quietStart}–${cfg.quietEnd}）")
        }

        // 用户刚说完话就别抢话
        if (state.lastUserMessageAt > 0 && nowMs - state.lastUserMessageAt < MIN_GAP_AFTER_USER_MS) {
            return Decision(Verdict.SILENT, "用户刚说完话，先安静")
        }

        // 每日上限（跨天自动重置）
        val today = todayKey(now)
        val firedToday = if (state.firedDay == today) state.firedToday else 0
        if (firedToday >= cfg.dailyCap) {
            return Decision(Verdict.SILENT, "已达每日上限 ${cfg.dailyCap} 条")
        }

        if (batteryPercent in 0..cfg.batteryFloor) {
            return Decision(Verdict.SILENT, "电量 ${batteryPercent}% 低于下限 ${cfg.batteryFloor}%")
        }

        // 连续未响应 → 逐级"退避"（而不是当天暂停）
        // v1 的设计是达到 6 次就当天闭嘴，导致用户再也收不到——已修正。
        // 现在：3 次 → 间隔 ×2；6 次 → ×4；9 次 → ×8；12 次才暂时当天停。
        val backoff = if (cfg.autoDownshift) {
            when {
                state.unrespondedStreak >= HARD_STOP_STREAK -> -1
                state.unrespondedStreak >= 9 -> 8
                state.unrespondedStreak >= 6 -> 4
                state.unrespondedStreak >= 3 -> 2
                else -> 1
            }
        } else 1

        if (backoff < 0) {
            return Decision(Verdict.SILENT, "连续 ${state.unrespondedStreak} 次未响应，今天先不打扰了")
        }
        if (backoff > 1) {
            val effectiveMs = intervalMs * backoff
            if (state.lastFiredAt > 0 && nowMs - state.lastFiredAt < effectiveMs) {
                val waitMin = (effectiveMs - (nowMs - state.lastFiredAt)) / 60_000L
                return Decision(
                    Verdict.SILENT,
                    "连续 ${state.unrespondedStreak} 次未响应，间隔已放宽 ${backoff} 倍（还需 $waitMin 分钟）"
                )
            }
        }

        return Decision(Verdict.SPEAK, "可以开口", pickTopic(nowMin, hasPendingTodo))
    }

    /**
     * 去噪：内容指纹没变（同一份待办、同一个话题），[Config.sameContentMin] 分钟内不重复说。
     * 返回 null = 可以说；否则是不说的原因。
     */
    fun sameContentSilence(cfg: Config, contentKey: String, lastKey: String, lastKeyAt: Long, nowMs: Long): String? {
        if (cfg.sameContentMin <= 0 || contentKey != lastKey || lastKeyAt <= 0) return null
        val cooldownMs = cfg.sameContentMin * 60_000L
        if (nowMs - lastKeyAt >= cooldownMs - TICK_JITTER_MS) return null
        val remainMin = (cooldownMs - (nowMs - lastKeyAt)) / 60_000L
        return "同样内容（$contentKey）已说过，还需 $remainMin 分钟（设置里「同一件事重复提醒间隔」= ${cfg.sameContentMin} 分钟）"
    }

    /**
     * 同上，但对照"最近说过的若干条内容"而不是只看上一条：
     * 真机上内容在 A（明天…）→ B（日程即将开始）→ A 之间来回时，只记上一条会把 A 又播报一遍。
     */
    fun recentKeysSilence(cfg: Config, contentKey: String, recent: Map<String, Long>, nowMs: Long): String? {
        val at = recent[contentKey] ?: return null
        return sameContentSilence(cfg, contentKey, contentKey, at, nowMs)
    }

    /** 记下刚说过的内容指纹，只保留最近 [max] 条（按时间） */
    fun pushRecentKey(recent: Map<String, Long>, key: String, nowMs: Long, max: Int = 8): Map<String, Long> =
        (recent + (key to nowMs)).entries.sortedByDescending { it.value }.take(max).associate { it.key to it.value }

    // ---------------- 智能免打扰：不只看钟点，还看"此刻在干什么" ----------------

    data class CalEvent(val title: String, val beginMs: Long, val endMs: Long, val allDay: Boolean, val free: Boolean)

    /** 此刻的场合（由 HeartbeatSources 采集） */
    data class Situation(val dnd: Boolean, val inCall: Boolean, val meetingTitle: String?, val sleeping: Boolean)

    /** 正在进行的"会议"：非全天、非"有空"、不超过 10 小时（更长的多半是占位）；取先开始的那个 */
    fun currentMeeting(events: List<CalEvent>, nowMs: Long): String? =
        events.filter { !it.allDay && !it.free && it.beginMs <= nowMs && nowMs < it.endMs && it.endMs - it.beginMs <= 10 * 3_600_000L }
            .minByOrNull { it.beginMs }?.title?.ifBlank { "日程" }

    /** 睡觉推断：充电 + 熄屏 + 夜里（22 点到 9 点）。只是推断，用来别在这时候打扰 */
    fun isSleeping(charging: Boolean, interactive: Boolean, hour: Int): Boolean =
        charging && !interactive && (hour >= 22 || hour < 9)

    /**
     * 场合闸门：返回 null = 可以说，否则是不说的原因。
     * 即将发生的事（IMMINENT）在会议中仍然放行——不能因为开会就漏掉下一件；勿扰、通话、睡觉中则一律不说。
     */
    fun contextSilence(cfg: Config, sit: Situation, topic: String): String? {
        if (!cfg.smartQuiet) return null
        if (sit.dnd) return "系统勿扰模式已开启"
        if (sit.inCall) return "正在通话中"
        if (sit.sleeping) return "看起来在睡觉（充电 + 熄屏 + 夜里）"
        if (sit.meetingTitle != null && !topic.equals("IMMINENT", ignoreCase = true)) return "正在开会（${sit.meetingTitle}）"
        return null
    }

    /** 某件事今天已经说过几次（跨天重置） */
    data class KeyCounts(val day: String, val counts: Map<String, Int>)

    /**
     * 每日次数上限：4 小时冷却之外，同一件"不会自己消失"的事（待办、明天的日程、生日）一天最多说几次。
     * 即将发生的日程/提醒不设上限（指纹里带着时间，本来就只会说一次）。返回 null = 可以说。
     */
    fun dayCapSilence(topic: String, key: String, kc: KeyCounts, day: String): String? {
        val cap = when (topic.uppercase()) { "BIRTHDAY" -> 1; "WEATHER" -> 1; "BRIEF" -> 1; "TOMORROW" -> 1; "TODAY" -> 2; "TODO" -> 2; else -> return null }
        val n = if (kc.day == day) kc.counts[key] ?: 0 else 0
        return if (n >= cap) "这件事今天已经说过 $n 次（上限 $cap），明天再说" else null
    }

    fun bumpCount(kc: KeyCounts, key: String, day: String): KeyCounts {
        val base = if (kc.day == day) kc.counts else emptyMap()
        return KeyCounts(day, base + (key to ((base[key] ?: 0) + 1)))
    }

    /** 连续这么多次未响应才当天暂停（在此之前只是放宽间隔） */
    const val HARD_STOP_STREAK = 12
    const val MIN_GAP_AFTER_USER_MS = 10 * 60_000L
    const val TICK_JITTER_MS = 5_000L
}
