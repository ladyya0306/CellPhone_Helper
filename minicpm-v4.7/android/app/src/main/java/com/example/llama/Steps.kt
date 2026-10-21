package com.example.llama

import java.time.Instant
import java.time.ZoneId

/**
 * 步数：计步器给的是「开机以来的累计步数」，这里把它折算成「每天多少步」。纯 Kotlin，可单测；读传感器在 [StepSource]。
 *
 * 老实的地方：
 *   · 第一次采样只建立基线——之前走了多少我们不知道，不会凭空报一个数；
 *   · 昨天的步数只有在昨天也采样过才有，没有就说没有，不编；
 *   · 开机后计数器清零（总数变小）→ 把新的总数当作这一段的增量；
 *   · 跨天的那一段增量算到新的一天（两次采样之间的步数没法再细分）。
 */
object StepLogic {

    data class State(
        val lastTotal: Long? = null,
        val firstDay: String = "",
        val days: Map<String, Int> = emptyMap(),
    )

    data class Summary(val today: Int?, val yesterday: Int?, val sinceDay: String?)

    private const val KEEP_DAYS = 60

    private fun dayOf(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun apply(s: State, atMs: Long, total: Long, zone: ZoneId): State {
        if (total < 0) return s
        val day = dayOf(atMs, zone).toString()
        val last = s.lastTotal ?: return s.copy(lastTotal = total, firstDay = s.firstDay.ifBlank { day })
        val delta = if (total >= last) total - last else total
        var days = s.days
        if (delta > 0) days = days + (day to ((days[day] ?: 0) + delta.coerceAtMost(200_000L).toInt()))
        if (days.size > KEEP_DAYS) days = days.toSortedMap().entries.toList().takeLast(KEEP_DAYS).associate { it.key to it.value }
        return s.copy(lastTotal = total, firstDay = s.firstDay.ifBlank { day }, days = days)
    }

    fun today(s: State, nowMs: Long, zone: ZoneId): Int = s.days[dayOf(nowMs, zone).toString()] ?: 0
    fun yesterday(s: State, nowMs: Long, zone: ZoneId): Int? = s.days[dayOf(nowMs, zone).minusDays(1).toString()]
    fun week(s: State, nowMs: Long, zone: ZoneId): Int {
        val t = dayOf(nowMs, zone)
        return (0..6).sumOf { s.days[t.minusDays(it.toLong()).toString()] ?: 0 }
    }

    fun summary(s: State, nowMs: Long, zone: ZoneId): Summary =
        Summary(today(s, nowMs, zone), yesterday(s, nowMs, zone), s.firstDay.ifBlank { null })

    fun shouldSample(lastSampleAtMs: Long, nowMs: Long, minGapMs: Long): Boolean = lastSampleAtMs == 0L || nowMs - lastSampleAtMs >= minGapMs

    private fun short(day: String) = if (day.length >= 10) day.substring(5) else day

    fun unavailableText(hasPermission: Boolean): String =
        if (!hasPermission) "数步需要「身体活动」权限，我这边还没有；可以在设置里打开「记录步数」并允许。"
        else "我还没有步数记录——刚开始数，过一阵子再问我。"

    fun answer(s: Summary, question: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val since = s.sinceDay?.let { "（我是从 ${short(it)} 开始数的）" } ?: ""
        return if (question.contains("昨天") || question.contains("昨日")) {
            if (s.yesterday == null) "昨天的步数我没有记录$since。每天我会自己累计，明天起就能告诉你昨天的了。"
            else "昨天走了 ${s.yesterday} 步。"
        } else {
            val t = s.today ?: return unavailableText(true)
            val firstDay = s.sinceDay == dayOf(nowMs, zone).toString()
            "今天走了 $t 步$since。" + if (firstDay) "今天是第一天，开始统计之前走的步数我没法补上。" else ""
        }
    }
}
