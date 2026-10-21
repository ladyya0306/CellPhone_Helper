package com.example.llama

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 专注统计：连续天数、本周累计、历史最佳。纯 Kotlin，可单测；存取由 [FocusStatsStore] 负责。
 *
 * 口径（克制，不制造压力）：
 *   · 「有效的一天」= 当天至少完成了一局（中途点「结束专注」的不算完成，但实际专注的时间会计入本周累计）；
 *   · 连续天数：从今天往前数连续有效的天；今天还没专注、但昨天有 → 连续天数照算（今天还能续上，不算断）；
 *   · 不排名、不分享、不和别人比。
 */
object FocusStats {

    data class Rec(
        val startMs: Long, val elapsedSec: Int, val completed: Boolean, val grade: String,
        val leaves: Int, val peeks: Int, val distracts: Int, val blowns: Int,
    )

    data class Summary(
        val streakDays: Int, val longestStreak: Int, val todayDone: Boolean, val weekMinutes: Int,
        val total: Int, val completedCount: Int, val bestGrade: String?,
    )

    private fun dayOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun summarize(recs: List<Rec>, nowMs: Long, zone: ZoneId): Summary {
        val today = dayOf(nowMs, zone)
        val days = recs.filter { it.completed }.map { dayOf(it.startMs, zone) }.toSet()
        val todayDone = today in days
        // 当前连续：从今天（没有就从昨天）往前数
        var cur = if (todayDone) today else today.minusDays(1)
        var streak = 0
        while (cur in days) { streak++; cur = cur.minusDays(1) }
        // 历史最长
        var longest = 0
        for (d in days) if (d.minusDays(1) !in days) {
            var n = 0; var c = d
            while (c in days) { n++; c = c.plusDays(1) }
            longest = maxOf(longest, n)
        }
        val weekSec = recs.filter { nowMs - it.startMs in 0..7 * 86_400_000L }.sumOf { it.elapsedSec }
        val order = "SABC"
        val best = recs.filter { it.completed }.map { it.grade }.minByOrNull { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
        return Summary(streak, longest, todayDone, weekSec / 60, recs.size, recs.count { it.completed }, best)
    }

    fun line(s: Summary): String {
        if (s.total == 0) return "还没有专注记录，开始第一局吧"
        val parts = ArrayList<String>()
        if (s.streakDays > 0) parts.add("连续 ${s.streakDays} 天" + if (!s.todayDone) "（今天再来一局就能续上）" else "")
        else parts.add("今天来一局，重新开始计连续天数")
        parts.add("本周累计 ${s.weekMinutes} 分钟")
        s.bestGrade?.let { parts.add("历史最佳评级 $it") }
        return parts.joinToString(" · ")
    }

    fun append(recs: List<Rec>, r: Rec, max: Int = 500): List<Rec> = (recs + r).takeLast(max)
}
