package com.example.llama

import java.io.File

/**
 * 电脑端评测用：把模型的原始回复过一遍「手机上真实的清理流水线」（去复读循环 → 限长 → ClaimGuard）。
 * 输入：每行 人设<TAB>用户原话<TAB>回复（回复里的换行已替换成 \n 两个字符）；输出：每行 清理后的回复<TAB>被删句数<TAB>是否被截/去循环(0/1)
 */
object ReplyCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        val sb = StringBuilder()
        for (row in File(args[0]).readLines(Charsets.UTF_8)) {
            val p = row.split("\t")
            if (p.size < 3) { sb.append("\t0\t0\n"); continue }
            val raw = p[2].replace("\\n", "\n")
            val cleaned = ReplyCleaner.clean(raw)
            val g = ClaimGuard.guard(cleaned, emptySet(), listOf(p[1]))
            sb.append(g.text.replace("\n", "\\n")).append('\t').append(g.removed.size).append('\t').append(if (cleaned != raw.trim()) 1 else 0).append('\n')
        }
        File(args[1]).writeText(sb.toString(), Charsets.UTF_8)
    }
}
