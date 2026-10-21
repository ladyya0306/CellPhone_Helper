package com.example.llama

/**
 * 候选记忆的积累与转正。纯 Kotlin，可单测；存取由 [CandidateStore] 负责。
 *
 * 规则：
 *   · 第一次听到只进"待确认"，不写进记忆；
 *   · 隔了 10 分钟以上又说一次（复读不算）→ 自动转正；用户在记忆页点"记住"也转正，点"忽略"则永不再提；
 *   · 30 天没再提的待确认自动清掉；待确认最多 50 条，忽略名单最多 100 条。
 */
object Candidates {

    data class Cand(
        val id: Int, val category: String, val text: String, val evidence: String,
        val count: Int, val firstAt: Long, val lastAt: Long, val status: String,   // pending / confirmed / rejected
    )

    data class Observed(val list: List<Cand>, val promoted: List<Cand>)

    private const val REPEAT_GAP_MS = 10 * 60_000L
    private const val EXPIRE_MS = 30 * 86_400_000L
    private const val MAX_PENDING = 50
    private const val MAX_REJECTED = 100

    private fun key(t: String) = t.filter { !it.isWhitespace() }

    fun pending(list: List<Cand>) = list.filter { it.status == "pending" }

    fun prune(list: List<Cand>, nowMs: Long): List<Cand> =
        list.filter { it.status != "pending" || nowMs - it.lastAt < EXPIRE_MS }

    private fun cap(list: List<Cand>): List<Cand> {
        var l = list
        val p = l.filter { it.status == "pending" }.sortedByDescending { it.lastAt }
        if (p.size > MAX_PENDING) { val drop = p.drop(MAX_PENDING).map { it.id }.toSet(); l = l.filter { it.id !in drop } }
        val r = l.filter { it.status == "rejected" }.sortedByDescending { it.lastAt }
        if (r.size > MAX_REJECTED) { val drop = r.drop(MAX_REJECTED).map { it.id }.toSet(); l = l.filter { it.id !in drop } }
        return l
    }

    fun observe(list0: List<Cand>, found: List<ProfileExtractor.Candidate>, nowMs: Long, promoteAt: Int = 2, gapMs: Long = REPEAT_GAP_MS): Observed {
        var list = prune(list0, nowMs)
        val promoted = ArrayList<Cand>()
        for (f in found) {
            val k = key(f.text)
            val ex = list.firstOrNull { key(it.text) == k }
            if (ex == null) {
                val id = (list.maxOfOrNull { it.id } ?: 0) + 1
                val c = Cand(id, f.category, f.text, f.evidence, 1, nowMs, nowMs, "pending")
                if (promoteAt <= 1) { list = list + c.copy(status = "confirmed"); promoted.add(c.copy(status = "confirmed")) } else list = list + c
            } else if (ex.status == "pending" && nowMs - ex.lastAt >= gapMs) {
                val n = ex.count + 1
                val upd = if (n >= promoteAt) ex.copy(count = n, lastAt = nowMs, status = "confirmed") else ex.copy(count = n, lastAt = nowMs)
                list = list.map { if (it.id == ex.id) upd else it }
                if (upd.status == "confirmed") promoted.add(upd)
            }
        }
        return Observed(cap(list), promoted)
    }

    fun confirm(list: List<Cand>, id: Int): Observed {
        val c = list.firstOrNull { it.id == id && it.status == "pending" } ?: return Observed(list, emptyList())
        val upd = c.copy(status = "confirmed")
        return Observed(list.map { if (it.id == id) upd else it }, listOf(upd))
    }

    fun reject(list: List<Cand>, id: Int): Observed =
        Observed(list.map { if (it.id == id) it.copy(status = "rejected") else it }, emptyList())
}
