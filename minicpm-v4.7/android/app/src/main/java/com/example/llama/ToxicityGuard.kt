package com.example.llama

/**
 * 毒舌/刻薄角色的**硬红线**（通讯录 2.0 · A1）。
 *
 * 用户选择了「恶心老板可以毒舌贬低工作、用 PUA 话术，但不能脏话/人身攻击」。
 * 所以这里不是放开骂人，而是：
 *   · 允许：工作否定、画饼、抢功、装熟、饭局式施压（这些由人设提示词负责）；
 *   · 禁止：脏话、地域/性别/外貌/家人/智力攻击、威胁、性骚扰、否定自我价值（由 InsultGuard + 本类兜底）。
 * 命中整句删除；删空用该人设的 fallback 顶上，避免空气泡。
 *
 * 数据驱动：谁需要过这一层由 [Persona.hostility] 决定，不在代码里写 id。
 */
object ToxicityGuard {

    private val SELF_WORTH = Regex(
        "你(这|真|就|这辈子|永远|根本).{0,6}(没用|废物|不行|没出息|废了|就这样|什么都不是)|" +
            "你(活着|存在).{0,6}(没|毫无).{0,4}(意义|价值)|" +
            "除了我(没人|谁还)会要你|离开(这里|我).{0,6}你(什么都不是|活不下去)"
    )

    /** 明确越界的性暗示/骚扰（宁可漏，不可放过明显的） */
    private val SEXUAL = Regex("陪睡|睡一觉|脱(了|掉)|身体(换|交易)|潜规则|开房")

    private val STOP = Regex("别(这样|这么说|骂|说)了|我不舒服|闭嘴|你别说了|够了|别烦我")

    private val SPLIT = Regex("(?<=[。！？!?\\n])")

    fun enabled(p: Persona): Boolean = p.hostility == Hostility.WORK_SNARK || p.hostility == Hostility.WORK_PUA

    /** 用户明确叫停：交给 A3 的静音状态使用 */
    fun stopRequest(text: String): Boolean = STOP.containsMatchIn(text.trim())

    fun sanitize(p: Persona, text: String): String {
        if (!enabled(p) || text.isBlank()) return text
        val kept = text.split(SPLIT).filterNot { sent ->
            val hard = InsultGuard.check(sent).filter { it != "太短" && it != "太长" }
            hard.isNotEmpty() || SELF_WORTH.containsMatchIn(sent) || SEXUAL.containsMatchIn(sent)
        }.joinToString("").trim()
        return kept.ifBlank { p.fallback }
    }
}
