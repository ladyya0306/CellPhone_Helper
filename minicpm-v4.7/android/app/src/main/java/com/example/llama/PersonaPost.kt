package com.example.llama

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 人设后处理 —— 移植自 Python 版 persona.py。
 *
 * 核心原则：**扮演 ≠ 否认身份**。出戏修正只做两件事：
 *   1) 去掉"作为AI助手…"这类破坏人设的句子
 *   2) 补一句**诚实承认是 AI + 保持人设语气**的兜底话术
 * 绝不允许把"我是AI"改写成"我就是真人"。
 */
object PersonaPost {

    private val BREAK_WORDS = listOf(
        "作为一个ai", "作为ai", "我是ai", "我是个ai", "我是一个人工智能",
        "人工智能助手", "语言模型", "作为智能助手", "我是智能助手",
        "作为一个人工智能", "ai助手", "我是由", "大语言模型",
    )

    private val SENTENCE_DELIMS = charArrayOf('。', '！', '？', '!', '?', '\n')

    fun detectBreak(text: String): List<String> {
        val low = text.lowercase()
        return BREAK_WORDS.filter { low.contains(it) }
    }

    /** 按句末标点切句，保留分隔符（便于原样拼回） */
    private fun splitSentences(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (c in text) {
            sb.append(c)
            if (SENTENCE_DELIMS.contains(c)) {
                out.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** 去掉出戏句；整段崩了就换成符合人设的兜底 */
    fun fixBreak(text: String, persona: Persona): String {
        if (text.isBlank()) return text
        if (detectBreak(text).isEmpty()) return text
        val kept = splitSentences(text).filter { detectBreak(it).isEmpty() }
        val cleaned = kept.joinToString("").trim()
        return if (cleaned.length < 8 || detectBreak(cleaned).isNotEmpty()) {
            persona.fallback
        } else {
            cleaned + "\n" + persona.fallback
        }
    }

    /**
     * 口癖频率校准：目标区间 15%–45%。
     * 模型实测要么每句都加（过载），要么几轮后忘光，所以不靠它自觉。
     */
    fun injectTic(text: String, persona: Persona, strength: Int = 1, seed: Int = Random.nextInt()): String {
        if (persona.tics.isEmpty() || persona.ticRate <= 0.0 || text.isBlank()) return text
        val sents = splitSentences(text).toMutableList()
        if (sents.size < 2) return text

        val tics = persona.tics
        val has = BooleanArray(sents.size) { i -> tics.any { sents[i].contains(it) } }
        val cur = has.count { it }.toDouble() / sents.size
        if (cur >= 0.15 && cur <= 0.45) return text   // 已在目标区间

        val rng = Random(seed)
        // 口癖强度：0 弱(×0.5) / 1 中(×1) / 2 强(×1.6)
        val mul = when (strength) { 0 -> 0.5; 2 -> 1.6; else -> 1.0 }
        val want = maxOf(1, (persona.ticRate * mul * sents.size).roundToInt())

        if (cur > 0.45) {
            // 过载：从后往前删多余的
            var excess = has.count { it } - want
            for (i in sents.indices.reversed()) {
                if (excess <= 0) break
                if (!has[i]) continue
                for (t in tics) {
                    if (sents[i].contains(t)) {
                        sents[i] = sents[i].replaceFirst(t, "")
                        excess--
                        has[i] = false
                        break
                    }
                }
            }
        } else {
            // 遗忘：随机补到目标数量
            var need = want - has.count { it }
            val idx = (0 until sents.size).shuffled(rng)
            for (i in idx) {
                if (need <= 0) break
                if (has[i]) continue
                val s = sents[i]
                val last = s.lastOrNull()
                val punct = if (last != null && SENTENCE_DELIMS.contains(last)) last else null
                val body = if (punct != null) s.dropLast(1) else s
                sents[i] = body + tics[rng.nextInt(tics.size)] + (punct ?: '。')
                has[i] = true
                need--
            }
        }
        return sents.joinToString("")
    }
}
