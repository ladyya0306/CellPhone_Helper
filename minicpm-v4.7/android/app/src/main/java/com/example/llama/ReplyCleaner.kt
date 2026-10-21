package com.example.llama

/**
 * 模型回复的清理：去掉复读循环、限制长度。纯 Kotlin，可单测。
 *
 * 来历（陪伴质量评测，2026-10-20）：小模型在「在吗」这种超短输入上会陷入「正正正正…」循环（一条 160 字全是同一个字）；
 * 傲娇人设在「明天要汇报我有点慌」上写了 223 字的动作描写。提示词里写了"不超过 80 字"，但管不住，所以程序兜底。
 * 顺序：先去循环，再限长；之后才是人设后处理和 ClaimGuard。
 */
object ReplyCleaner {

    private const val HARD = 140
    private const val SENTENCE_END = "。！？!?…\n"

    fun clean(text: String): String = limitLength(cutRepetition(text))

    private fun isLetterLike(cp: Int) = Character.isLetterOrDigit(cp)

    /** 去掉复读循环：连续重复的字/词压成不超过 3 遍（单字）或 2 遍（词）；一直循环到结尾的退化尾巴整段删掉，并退回到上一句完整的话。 */
    fun cutRepetition(text: String): String {
        val cps = text.codePoints().toArray()
        val n = cps.size
        val out = ArrayList<Int>()
        var tailCut = false
        var i = 0
        fun restIsBlank(from: Int): Boolean { var k = from; while (k < n) { if (isLetterLike(cps[k])) return false; k++ }; return true }
        while (i < n) {
            // 多字循环：单位 2..8 个字，连续重复 ≥4 遍
            var handled = false
            for (len in 2..8) {
                if (i + len * 4 > n) break
                if ((1 until len).all { cps[i + it] == cps[i] }) continue      // 同一个字重复，交给下面的单字规则（压成 3 个）
                var k = 1
                while (i + (k + 1) * len <= n && (0 until len).all { cps[i + it] == cps[i + k * len + it] }) k++
                if (k >= 4) {
                    val end = i + k * len
                    if (restIsBlank(end)) { tailCut = true; if (out.isEmpty() || true) { if (out.isEmpty()) for (r in 0 until 2) for (c in 0 until len) out.add(cps[i + c]) } ; i = n }
                    else { for (r in 0 until 2) for (c in 0 until len) out.add(cps[i + c]); i = end }
                    handled = true
                    break
                }
            }
            if (handled) continue
            // 单字重复
            var run = 1
            while (i + run < n && cps[i + run] == cps[i]) run++
            if (run > 3) {
                if (isLetterLike(cps[i]) && run >= 8 && restIsBlank(i + run)) {
                    tailCut = true
                    if (out.isEmpty()) repeat(3) { out.add(cps[i]) }
                    i = n
                } else { repeat(3) { out.add(cps[i]) }; i += run }
            } else { for (r in 0 until run) out.add(cps[i]); i += run }
        }
        var s = String(out.toIntArray(), 0, out.size)
        if (tailCut) {
            // 退回到上一句完整的话（没有句子结束符就保留已有的部分）
            val idx = s.indexOfLast { it in SENTENCE_END }
            if (idx >= 0) s = s.substring(0, idx + 1)
        }
        return s.trim()
    }

    /** 超过 [HARD] 字就在句子结束处截断；一口气没有句号就硬截 120 字加省略号；截断后去掉没闭合的括号动作描写 */
    fun limitLength(text: String): String {
        val t = text.trim()
        if (t.length <= HARD) return t
        val window = t.substring(0, HARD)
        val idx = window.indexOfLast { it in SENTENCE_END || it == '）' || it == ')' }
        var cut = if (idx >= 0) window.substring(0, idx + 1) else window.substring(0, 120) + "……"
        // 括号没闭合：丢掉最后一个没闭合的括号起的半截
        fun unclosed(s: String): Int {
            var depth = 0; var lastOpen = -1
            s.forEachIndexed { k, c -> when (c) { '（', '(' -> { if (depth == 0) lastOpen = k; depth++ }; '）', ')' -> if (depth > 0) depth-- } }
            return if (depth > 0) lastOpen else -1
        }
        val u = unclosed(cut)
        if (u >= 0) cut = cut.substring(0, u).trimEnd()
        return cut
    }
}
