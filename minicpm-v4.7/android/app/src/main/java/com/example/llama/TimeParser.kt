package com.example.llama

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 中文时间表达解析 —— 纯 Kotlin，不依赖 Android，可在 JVM 上直接单测（见 AssistantSelfTest）。
 *
 * 为什么由程序解析而不是交给模型：
 *   实测 4B 模型会把"明天6点"解析成 2024-03-15（拿错记忆、编年份）；
 *   时间是"动作是否真的落地"的关键参数，必须可预测、可测试。
 *
 * 支持：
 *   相对：十分钟后 / 半小时后 / 一个半小时后 / 2小时后 / 3天后
 *   日期：今天 明天 后天 大后天 / 今早 今晚 明早 明晚 / 周五 下周一 下下周三 / 10月20日 / 2026年10月20日 / 20号
 *   钟点：7点 七点半 7点15 7点一刻 7:30 / 时段词：凌晨 早上 上午 中午 下午 傍晚 晚上
 *   无时段的 1–12 点：没给日期时取"最近的未来时刻"（上午或下午）；给了日期时按字面理解并标记 ampmAssumed
 */
object TimeParser {

    data class Parsed(
        val dateTime: LocalDateTime,
        val hasDate: Boolean,       // 用户明确说了日期
        val hasTime: Boolean,       // 用户明确说了钟点（或相对时间）
        val relative: Boolean,      // "X分钟后"这类
        val ampmAssumed: Boolean,   // 上午/下午是程序推断的（回执里要明示）
    )

    private const val NUM = "(\\d{1,2}|[零〇一二两三四五六七八九十]{1,3})"

    private val RELATIVE = Regex("(半|$NUM)\\s*个?\\s*(半)?\\s*(分钟|小时|钟头|天)\\s*(?:以后|之后|后)")
    private val DAY_WORD = Regex("(大后天|后天|明天|明早|明晚|明日|明儿|今天|今早|今晚|今日|今儿)")
    private val WEEKDAY = Regex("(下下|下|这|本)?个?(?:周|星期|礼拜)([一二三四五六日天1-7])")
    private val MONTH_DAY = Regex("(?:(\\d{4})\\s*年\\s*)?$NUM\\s*月\\s*$NUM\\s*(?:日|号)?")
    private val DAY_ONLY = Regex("(?<![月\\d零〇一二两三四五六七八九十])(\\d{1,2}|[一二三四五六七八九十]{1,3})\\s*号(?!楼|线|门|床|机)")
    private val COLON = Regex("(?<!\\d)(\\d{1,2})\\s*[:：]\\s*(\\d{2})(?!\\d)")
    private val HOUR = Regex("$NUM\\s*(?:点|时)(?!点)\\s*钟?\\s*(半|一刻|三刻|过?$NUM\\s*分?)?")

    /** "早一点/快一点/有一点"里的"一点"不是钟点 */
    private val NOT_CLOCK_BEFORE = "早晚快慢多少大小好差有点再"

    private fun findHour(t: String): MatchResult? = HOUR.findAll(t).firstOrNull { m ->
        val prev = if (m.range.first > 0) t[m.range.first - 1] else ' '
        !(m.groupValues[1] == "一" && NOT_CLOCK_BEFORE.contains(prev))
    }
    private val PERIOD = Regex("(凌晨|半夜|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|夜晚|今晚|明晚|今早|明早|早)")
    /** 清除时间词时不能把"早起""早点"里的"早"删掉 */
    private val PERIOD_STRIP = Regex("(凌晨|半夜|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|夜晚|今晚|明晚|今早|明早|早(?=\\s*$NUM\\s*点))")
    private val DURATION = Regex("$NUM\\s*个?\\s*(半)?\\s*(分钟|小时|钟头|秒)")

    /** 中文数字（0–99）或阿拉伯数字 → Int */
    fun cnToInt(s0: String): Int? {
        val s = s0.trim()
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }
        val d = mapOf(
            '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )
        if (s == "十") return 10
        val i = s.indexOf('十')
        if (i < 0) return if (s.length == 1) d[s[0]] else null
        if (s.length > i + 2) return null
        val tens = if (i == 0) 1 else (d[s[0]] ?: return null)
        val ones = if (i == s.length - 1) 0 else (d[s[i + 1]] ?: return null)
        return tens * 10 + ones
    }

    private fun normalize(text: String): String = buildString {
        for (c in text) {
            append(
                when (c) {
                    in '０'..'９' -> '0' + (c - '０')
                    '：' -> ':'
                    else -> c
                }
            )
        }
    }

    fun parse(text: String, now: LocalDateTime): Parsed? {
        val t = normalize(text)

        // ---------- 1) 相对时间 ----------
        RELATIVE.find(t)?.let { m ->
            val base = if (m.groupValues[1] == "半") 0.5 else (cnToInt(m.groupValues[2]) ?: return@let).toDouble()
            val extraHalf = if (m.groupValues[3] == "半") 0.5 else 0.0
            val amount = base + extraHalf
            val unit = m.groupValues[4]
            val minutes = when (unit) {
                "分钟" -> amount
                "小时", "钟头" -> amount * 60
                else -> amount * 24 * 60
            }.toLong()
            if (minutes <= 0) return@let
            return Parsed(
                now.plusMinutes(minutes).truncatedTo(ChronoUnit.MINUTES),
                hasDate = false, hasTime = true, relative = true, ampmAssumed = false,
            )
        }

        // ---------- 2) 日期 ----------
        val today = now.toLocalDate()
        var date: LocalDate? = null
        var bareWeekday = false
        var periodFromDay: String? = null

        DAY_WORD.find(t)?.let { m ->
            val w = m.groupValues[1]
            date = when (w) {
                "大后天" -> today.plusDays(3)
                "后天" -> today.plusDays(2)
                "明天", "明早", "明晚", "明日", "明儿" -> today.plusDays(1)
                else -> today
            }
            if (w == "今晚" || w == "明晚") periodFromDay = "晚上"
            if (w == "今早" || w == "明早") periodFromDay = "早上"
        }
        if (date == null) {
            WEEKDAY.find(t)?.let { m ->
                val prefix = m.groupValues[1]
                val wd = when (val c = m.groupValues[2]) {
                    "日", "天" -> 7
                    else -> cnToInt(c) ?: c.toIntOrNull() ?: return@let
                }.coerceIn(1, 7)
                val dow = today.dayOfWeek.value
                date = when (prefix) {
                    "下" -> today.minusDays((dow - 1).toLong()).plusDays(7L + wd - 1)
                    "下下" -> today.minusDays((dow - 1).toLong()).plusDays(14L + wd - 1)
                    else -> {
                        bareWeekday = prefix.isEmpty()
                        today.plusDays(((wd - dow + 7) % 7).toLong())
                    }
                }
            }
        }
        if (date == null) {
            MONTH_DAY.find(t)?.let { m ->
                val mo = cnToInt(m.groupValues[2]) ?: return@let
                val d = cnToInt(m.groupValues[3]) ?: return@let
                val yGiven = m.groupValues[1].toIntOrNull()
                val y = yGiven ?: today.year
                var cand = runCatching { LocalDate.of(y, mo, d) }.getOrNull() ?: return@let
                if (yGiven == null && cand.isBefore(today)) cand = cand.plusYears(1)
                date = cand
            }
        }
        if (date == null) {
            DAY_ONLY.find(t)?.let { m ->
                val d = cnToInt(m.groupValues[1]) ?: return@let
                var cand = runCatching { today.withDayOfMonth(d) }.getOrNull() ?: return@let
                if (cand.isBefore(today)) cand = runCatching { today.plusMonths(1).withDayOfMonth(d) }.getOrNull() ?: return@let
                date = cand
            }
        }

        // ---------- 3) 钟点 ----------
        var hour: Int? = null
        var minute = 0
        COLON.find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            val mi = m.groupValues[2].toInt()
            if (h in 0..23 && mi in 0..59) { hour = h; minute = mi }
        }
        if (hour == null) {
            findHour(t)?.let { m ->
                val h = cnToInt(m.groupValues[1]) ?: return@let
                if (h !in 0..24) return@let
                val tail = m.groupValues[2]
                minute = when {
                    tail.isEmpty() -> 0
                    tail == "半" -> 30
                    tail == "一刻" -> 15
                    tail == "三刻" -> 45
                    else -> cnToInt(m.groupValues[3])?.takeIf { it in 0..59 } ?: 0
                }
                hour = if (h == 24) 0 else h
            }
        }

        val period = periodFromDay ?: PERIOD.findAll(t).map { it.groupValues[1] }
            .firstOrNull { it != "早" || t.contains(Regex("早\\s*$NUM\\s*点")) }

        if (date == null && hour == null) return null

        val hasTime = hour != null
        var h = hour ?: when (period) {
            "凌晨", "半夜" -> 6
            "清晨", "早上", "早晨", "早", "今早", "明早", "上午" -> 9
            "中午" -> 12
            "下午" -> 15
            "傍晚" -> 18
            "晚上", "夜里", "夜晚", "今晚", "明晚" -> 20
            else -> 9
        }
        var dayShift = 0L
        var ampmAssumed = false
        if (hasTime && period != null) {
            when (period) {
                "凌晨", "半夜" -> if (h == 12) h = 0
                "中午" -> if (h in 1..3) h += 12
                "下午", "傍晚" -> if (h in 1..11) h += 12
                "晚上", "夜里", "夜晚", "今晚", "明晚" -> when (h) {
                    in 1..11 -> h += 12
                    12 -> { h = 0; dayShift = 1 }
                }
            }
        }

        // 只说了钟点、没说日期 → 取最近的未来时刻
        if (date == null) {
            val candidates = ArrayList<LocalDateTime>()
            val base = today.atTime(h % 24, minute)
            candidates.add(base)
            candidates.add(base.plusDays(1))
            if (period == null && h in 1..11) {
                candidates.add(today.atTime(h + 12, minute))
                ampmAssumed = true
            }
            val pick = candidates.filter { it.isAfter(now) }.minOrNull() ?: base.plusDays(1)
            return Parsed(pick.plusDays(dayShift), hasDate = false, hasTime = hasTime, relative = false, ampmAssumed = ampmAssumed)
        }

        if (hasTime && period == null && h in 1..6) ampmAssumed = true
        var dt = date!!.atTime(h % 24, minute).plusDays(dayShift)
        // "周五三点"在周五晚上说 → 指下周五
        if (bareWeekday && dt.isBefore(now)) dt = dt.plusDays(7)
        return Parsed(dt, hasDate = true, hasTime = hasTime, relative = false, ampmAssumed = ampmAssumed)
    }

    /** 计时器时长："20分钟的计时" → 20（分钟）；没有则 null */
    fun parseDurationMinutes(text: String): Long? {
        val m = DURATION.find(normalize(text)) ?: return null
        val n = cnToInt(m.groupValues[1]) ?: return null
        val half = if (m.groupValues[2] == "半") 0.5 else 0.0
        val v = n + half
        return when (m.groupValues[3]) {
            "秒" -> maxOf(1L, (v / 60.0).toLong())
            "小时", "钟头" -> (v * 60).toLong()
            else -> v.toLong()
        }.takeIf { it > 0 }
    }

    /** 去掉文本里的时间表达，便于提取"要做的事" */
    fun stripTimeWords(text: String): String {
        var s = normalize(text)
        for (r in listOf(RELATIVE, MONTH_DAY, WEEKDAY, DAY_WORD, DAY_ONLY, COLON, HOUR, PERIOD_STRIP, DURATION)) {
            s = r.replace(s, " ")
        }
        return s
    }

    /** 给用户看的时间描述：今天 15:00 / 明天 07:00 / 10月21日（周三）07:00 */
    fun describe(dt: LocalDateTime, now: LocalDateTime): String {
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), dt.toLocalDate())
        val hm = "%02d:%02d".format(dt.hour, dt.minute)
        val wd = arrayOf("一", "二", "三", "四", "五", "六", "日")[dt.dayOfWeek.value - 1]
        return when (days) {
            0L -> "今天 $hm"
            1L -> "明天 $hm"
            2L -> "后天 $hm"
            else -> "${dt.monthValue}月${dt.dayOfMonth}日（周$wd）$hm"
        }
    }

    @Suppress("unused")
    private fun DayOfWeek.cn(): String = arrayOf("一", "二", "三", "四", "五", "六", "日")[value - 1]
}
