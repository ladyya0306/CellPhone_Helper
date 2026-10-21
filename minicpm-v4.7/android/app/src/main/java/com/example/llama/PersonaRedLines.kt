package com.example.llama

/**
 * 人设红线（程序侧兜底）。
 *
 * 目前只有一条可机判的红线：**不许对用户下命令**（傅沉这类"强势"人设最容易越界）。
 * 提示词里写"不下命令"只能降低概率，所以输出还要过这一层：命中命令句式整句删除；
 * 如果整段都被删空，用该人设的诚实兜底话术顶上，避免出现空气泡。
 *
 * 数据驱动：哪个人设要过这一层由 [Persona.noCommands] 决定，不在这里写 id。
 */
object PersonaRedLines {

    /** 对用户发号施令的硬句式（只抓最明确的，避免把"先喝口水"这类关心误判成命令） */
    val COMMAND = Regex("(你必须|你不许|不准你|不许你|给我(去|把|老实|听)|按我说的做|听我的|我命令你|你给我)")

    private val SPLIT = Regex("(?<=[。！？!?\\n])")

    /** 删掉命令句；没开 noCommands 的人设原样返回 */
    fun sanitize(p: Persona, text: String): String {
        if (!p.noCommands || text.isBlank()) return text
        val kept = text.split(SPLIT).filterNot { COMMAND.containsMatchIn(it) }.joinToString("").trim()
        return kept.ifBlank { p.fallback }
    }
}
