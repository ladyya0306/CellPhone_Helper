package com.example.llama

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 改 / 删 / 推迟 提醒的说法解析与匹配。纯 Kotlin，可单测。
 * 只管助手自己登记的提醒和助手闹钟（系统时钟里的闹钟、系统日历里的日程，程序改不了，由调用方如实说明）。
 */
object ReminderEdit {

    enum class Kind { CANCEL, RESCHEDULE, SNOOZE }
    enum class Target { REMINDER, ALARM, CALENDAR }

    data class Edit(
        val kind: Kind, val target: Target = Target.REMINDER, val hint: String = "",
        val hintTime: LocalDateTime? = null, val hintDate: LocalDate? = null,
        val all: Boolean = false, val newTime: TimeParser.Parsed? = null, val snoozeSec: Int? = null,
    )

    private val CMD = "取消|删掉|删除|去掉|关掉|撤销|不要了|别再?提醒我?|不用再?提醒我?|不要再?提醒我?"
    private val CANCEL_A = Regex("(?:取消|删掉|删除|去掉|关掉|撤销)(.{0,16}?)(提醒|闹钟|日程|日历|行程)")
    private val CANCEL_B = Regex("(.{0,16}?)(提醒|闹钟|日程|日历|行程)(?:给)?(?:取消|删掉|删除|去掉|关掉)")
    private val CANCEL_C = Regex("^(?:别再?|不用再?|不要再?)提醒我(.+?)了?$")
    private val RESCHED = Regex("^(.*?)(?:改到|改成|改为|推迟到|延后到|提前到|挪到|换到|调到|调整到)(.+)$")
    private val SNOOZE = Regex("(?:推迟|延后|晚点|晚些|稍后|等会|等下|过.{1,4}).{0,4}再?提醒|推迟.{0,6}(?:分钟|小时|钟头|秒)|再等.{1,4}(?:分钟|小时|钟头|秒)|贪睡|再响一次|再响一下")
    private val FILLER = Regex("把|的|那个|这个|一下|所有|全部|都|给|我|吧|了|个|条|帮|请|麻烦|提醒|闹钟|日程|日历|行程|${CMD}")

    private fun hintOf(textWithoutCmd: String): String =
        FILLER.replace(TimeParser.stripTimeWords(textWithoutCmd), "").replace(Regex("[\\s，,。.！!？?、]+"), "").trim()

    private fun targetOf(t: String) = when {
        Regex("日程|日历|行程").containsMatchIn(t) -> Target.CALENDAR
        t.contains("闹钟") -> Target.ALARM
        else -> Target.REMINDER
    }

    fun parse(text0: String, now: LocalDateTime): Edit? {
        val text = text0.trim()
        if (text.isEmpty()) return null
        val time = TimeParser.parse(text, now)
        val hintTime = time?.takeIf { it.hasTime && !it.relative }?.dateTime
        val hintDate = time?.takeIf { it.hasDate && !it.hasTime }?.dateTime?.toLocalDate()

        // 改时间：把开会提醒改到四点
        RESCHED.find(text)?.let { m ->
            val before = m.groupValues[1]
            val nt = TimeParser.parse(m.groupValues[2], now)
            if (nt != null && (nt.hasTime || nt.hasDate) && Regex("提醒|闹钟|日程").containsMatchIn(before + m.groupValues[2] + text)) {
                return Edit(Kind.RESCHEDULE, targetOf(text), hint = hintOf(before), newTime = nt)
            }
        }
        // 取消 / 删掉
        val all = Regex("所有|全部").containsMatchIn(text)
        CANCEL_A.find(text)?.let { m -> return Edit(Kind.CANCEL, targetOf(m.groupValues[2] + text), hintOf(m.groupValues[1]), hintTime, hintDate, all) }
        CANCEL_B.find(text)?.let { m -> return Edit(Kind.CANCEL, targetOf(m.groupValues[2] + text), hintOf(m.groupValues[1]), hintTime, hintDate, all) }
        CANCEL_C.find(text)?.let { m -> return Edit(Kind.CANCEL, Target.REMINDER, hintOf(m.groupValues[1])) }
        // 再提醒 / 推迟（必须带"推迟/晚点/稍后/再等…"这类词；"10 分钟后提醒我喝水"是新建，不是推迟）
        if (SNOOZE.containsMatchIn(text)) {
            return Edit(Kind.SNOOZE, snoozeSec = durationSec(text))
        }
        return null
    }

    private val DURATION = Regex("(\\d+|[零〇一二两三四五六七八九十]{1,3})\\s*(?:个)?\\s*(秒钟|秒|分钟|分|小时|钟头)(半)?")

    /** "十分钟 / 5 分钟 / 半小时 / 一个半小时"→ 秒；没说时长返回 null（用默认） */
    fun durationSec(text: String): Int? {
        if (Regex("半个?小时|半个?钟头").containsMatchIn(text) && !DURATION.containsMatchIn(text)) return 1800
        val m = DURATION.find(text) ?: return null
        val n = TimeParser.cnToInt(m.groupValues[1]) ?: return null
        val unit = when (m.groupValues[2]) { "秒钟", "秒" -> 1; "分钟", "分" -> 60; else -> 3600 }
        return n * unit + if (m.groupValues[3].isNotEmpty() && unit == 3600) 1800 else 0
    }

    sealed class Match {
        object None : Match()
        data class One(val r: ReminderScheduler.Reminder) : Match()
        data class Many(val list: List<ReminderScheduler.Reminder>) : Match()
        data class All(val list: List<ReminderScheduler.Reminder>) : Match()
    }

    private fun localOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()

    fun match(list: List<ReminderScheduler.Reminder>, e: Edit, now: LocalDateTime): Match {
        var c = list.filter { !it.cancelled && it.firedAt == 0L }
        c = when (e.target) {
            Target.ALARM -> c.filter { it.alarm }
            Target.REMINDER -> c.filter { !it.alarm }
            Target.CALENDAR -> emptyList()
        }
        if (c.isEmpty()) return Match.None
        if (e.all) return Match.All(c)
        var f = c
        if (e.hint.isNotEmpty()) f = f.filter { it.text.contains(e.hint) || e.hint.contains(it.text) }
        e.hintTime?.let { t -> f = f.filter { val l = localOf(it.triggerAt); l.toLocalDate() == t.toLocalDate() && l.hour == t.hour && l.minute == t.minute } }
        e.hintDate?.let { d -> f = f.filter { localOf(it.triggerAt).toLocalDate() == d } }
        return when (f.size) { 0 -> Match.None; 1 -> Match.One(f[0]); else -> Match.Many(f.sortedBy { it.triggerAt }) }
    }

    /** 等用户选「是哪一条」：同一会话、3 分钟内有效，取走即清 */
    object Pending {
        private const val TTL_MS = 3 * 60_000L
        data class P(val edit: Edit, val ids: List<Int>, val conv: String, val at: Long)
        @Volatile private var cur: P? = null
        fun put(e: Edit, ids: List<Int>, conv: String, nowMs: Long = System.currentTimeMillis()) { cur = P(e, ids, conv, nowMs) }
        fun clear() { cur = null }
        fun peek(conv: String, nowMs: Long = System.currentTimeMillis()): P? = cur?.takeIf { it.conv == conv && nowMs - it.at <= TTL_MS }
        fun take(conv: String, nowMs: Long = System.currentTimeMillis()): P? { val p = peek(conv, nowMs); cur = null; return p }
    }

    sealed class Choice {
        data class Pick(val n: Int) : Choice()
        object AllOf : Choice()
        object Never : Choice()
        object Other : Choice()
    }

    fun parseChoice(text: String, n: Int): Choice {
        val t = text.trim().trimEnd('。', '！', '!', '，', ',')
        if (Regex("^(算了|不用了|不取消了|不删了|不改了|取消吧|别动)$").matches(t)) return Choice.Never
        if (Regex("^(都取消|全部取消|都删|全部删掉|都删掉|所有的?都取消|全都取消)$").matches(t)) return Choice.AllOf
        val m = Regex("^(?:第\\s*)?([0-9]{1,2}|[一二三四五六七八九十]{1,3})\\s*(?:个|条|项)?$").find(t) ?: return Choice.Other
        val k = TimeParser.cnToInt(m.groupValues[1]) ?: return Choice.Other
        return if (k in 1..n) Choice.Pick(k) else Choice.Other
    }
}

/** 提醒触发后的状态变化（纯函数）：再提醒、重复提醒自动排下一次 */
object ReminderLogic {
    const val DEFAULT_SNOOZE_SEC = 600

    fun defaultSnoozeSec(override: Int?): Int = if (override != null && override > 0) override else DEFAULT_SNOOZE_SEC

    fun snooze(r: ReminderScheduler.Reminder, nowMs: Long, sec: Int): ReminderScheduler.Reminder =
        r.copy(triggerAt = nowMs + sec * 1000L, firedAt = 0L, snoozed = r.snoozed + 1)

    fun afterFire(r: ReminderScheduler.Reminder, nowMs: Long): ReminderScheduler.Reminder {
        if (r.cancelled) return r
        val rule = RepeatRule.fromString(r.repeat) ?: return r.copy(firedAt = nowMs)
        val zone = ZoneId.systemDefault()
        val ref = maxOf(r.triggerAt, nowMs)
        val time = Instant.ofEpochMilli(r.triggerAt).atZone(zone).toLocalTime()
        val next = RepeatRule.next(rule, Instant.ofEpochMilli(ref).atZone(zone).toLocalDateTime(), time)
        return r.copy(triggerAt = next.atZone(zone).toInstant().toEpochMilli(), firedAt = 0L, snoozed = 0)
    }
}
