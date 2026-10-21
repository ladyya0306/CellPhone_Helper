package com.example.llama

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 提醒的重复规则：每天 / 每个工作日 / 每周三 / 每周一三五 / 每个周末 / 每月 15 号。纯 Kotlin，可单测。
 * 只管"哪些日子触发"；几点触发由提醒本身的钟点决定。月底处理：每月 31 号遇到 30 天的月份，取当月最后一天。
 */
data class RepeatRule(val kind: Kind, val days: Set<DayOfWeek> = emptySet(), val dom: Int = 0) {

    enum class Kind { DAILY, WEEKDAYS, WEEKLY, MONTHLY }

    companion object {
        fun daily() = RepeatRule(Kind.DAILY)
        fun weekdays() = RepeatRule(Kind.WEEKDAYS)
        fun weekly(days: Set<DayOfWeek>) = RepeatRule(Kind.WEEKLY, days = days)
        fun monthly(dom: Int) = RepeatRule(Kind.MONTHLY, dom = dom.coerceIn(1, 31))

        private val DOW_CHARS = "一二三四五六日天"
        private fun dow(c: Char): DayOfWeek? = when (c) {
            '一' -> DayOfWeek.MONDAY; '二' -> DayOfWeek.TUESDAY; '三' -> DayOfWeek.WEDNESDAY; '四' -> DayOfWeek.THURSDAY
            '五' -> DayOfWeek.FRIDAY; '六' -> DayOfWeek.SATURDAY; '日', '天' -> DayOfWeek.SUNDAY; else -> null
        }

        private val WEEKDAYS = Regex("每个?工作日|工作日|每个?周一(?:到|至|~|-)周?五|每个?星期一(?:到|至)星期?五")
        private val WEEKEND = Regex("每个?周末|每逢周末|每个?星期?[六日]和[日六]")
        private val WEEKLY = Regex("每(?:个)?(?:周|星期|礼拜)((?:[一二三四五六日天]|[、，,和及与]|周|星期|礼拜)+)")
        private val MONTHLY = Regex("每(?:个)?月(\\d{1,2}|[一二三四五六七八九十]{1,3})\\s*[号日]")
        private val DAILY = Regex("每天|每日|(?<![今明后昨前每])天天(?!气)")

        private fun match(text: String): Pair<RepeatRule, IntRange>? {
            WEEKDAYS.find(text)?.let { return weekdays() to it.range }
            WEEKEND.find(text)?.let { return weekly(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) to it.range }
            WEEKLY.find(text)?.let { m ->
                val ds = m.groupValues[1].filter { it in DOW_CHARS }.mapNotNull { dow(it) }.toSet()
                if (ds.isNotEmpty()) return weekly(ds) to m.range
            }
            MONTHLY.find(text)?.let { m ->
                val n = TimeParser.cnToInt(m.groupValues[1]) ?: return@let
                if (n in 1..31) return monthly(n) to m.range
            }
            DAILY.find(text)?.let { return daily() to it.range }
            return null
        }

        fun parse(text: String): RepeatRule? = match(text)?.first

        /** 去掉重复词，剩下的话照旧交给时间解析和内容提取 */
        fun strip(text: String): String = match(text)?.let { (_, r) -> text.removeRange(r) } ?: text

        private fun RepeatRule.matches(d: LocalDate): Boolean = when (kind) {
            Kind.DAILY -> true
            Kind.WEEKDAYS -> d.dayOfWeek.value <= 5
            Kind.WEEKLY -> d.dayOfWeek in days
            Kind.MONTHLY -> d.dayOfMonth == minOf(dom, d.lengthOfMonth())
        }

        /** 严格晚于 [after] 的下一次触发时间（同一时刻不会重复触发） */
        fun next(rule: RepeatRule, after: LocalDateTime, time: LocalTime): LocalDateTime {
            var d = after.toLocalDate()
            repeat(400) {
                val c = d.atTime(time)
                if (c.isAfter(after) && rule.matches(d)) return c
                d = d.plusDays(1)
            }
            return after.plusDays(1)
        }

        fun serialize(r: RepeatRule): String = when (r.kind) {
            Kind.DAILY -> "daily"
            Kind.WEEKDAYS -> "weekdays"
            Kind.WEEKLY -> "weekly:" + r.days.map { it.value }.sorted().joinToString(",")
            Kind.MONTHLY -> "monthly:${r.dom}"
        }

        fun fromString(s: String): RepeatRule? = try {
            when {
                s == "daily" -> daily()
                s == "weekdays" -> weekdays()
                s.startsWith("weekly:") -> weekly(s.removePrefix("weekly:").split(",").map { DayOfWeek.of(it.trim().toInt()) }.toSet()).takeIf { it.days.isNotEmpty() }
                s.startsWith("monthly:") -> monthly(s.removePrefix("monthly:").trim().toInt())
                else -> null
            }
        } catch (_: Exception) { null }

        /** 给用户看的文字 */
        fun describe(r: RepeatRule): String = when (r.kind) {
            Kind.DAILY -> "每天"
            Kind.WEEKDAYS -> "每个工作日"
            Kind.MONTHLY -> "每月 ${r.dom} 号"
            Kind.WEEKLY -> if (r.days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) "每个周末"
            else "每周" + r.days.sortedBy { it.value }.joinToString("、") { "一二三四五六日"[it.value - 1].toString() }
        }
    }
}
