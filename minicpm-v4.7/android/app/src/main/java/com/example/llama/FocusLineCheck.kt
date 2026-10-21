package com.example.llama

import java.io.File

/**
 * 电脑端离线评测用的命令行校验器：和手机上同一份 [FocusLineBank] 规则，保证"评测通过"和"手机上会收"是同一个结论。
 * 输入（UTF-8）：每行  人设<TAB>事件<TAB>档位<TAB>原始台词
 * 输出（UTF-8）：每行  OK<TAB>格子<TAB>台词   或   REJ<TAB>格子<TAB>原因1;原因2<TAB>台词
 * 用法见 scripts/focus-line-check.ps1
 */
object FocusLineCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        val inp = File(args[0]).readLines(Charsets.UTF_8)
        val outFile = File(args[1])
        val cellLines = HashMap<String, MutableList<String>>()
        val sb = StringBuilder()
        for (row in inp) {
            val p = row.split("\t")
            if (p.size < 4) continue
            val kind = FocusLines.Kind.valueOf(p[1])
            val cell = FocusLineBank.cellKey(p[0], kind, p[2].toInt())
            val line = FocusLineBank.cleanLine(p[3])
            if (line.isEmpty() || line.endsWith("：") || line.endsWith(":")) continue
            val existing = cellLines.getOrPut(cell) { ArrayList() }
            val reasons = ArrayList(FocusLineBank.validate(line, kind))
            if (existing.any { it == line || FocusLineBank.nearDuplicate(it, line) }) reasons.add("与已有台词重复")
            if (reasons.isEmpty()) { existing.add(line); sb.append("OK\t").append(cell).append('\t').append(line).append('\n') }
            else sb.append("REJ\t").append(cell).append('\t').append(reasons.joinToString(";")).append('\t').append(line).append('\n')
        }
        outFile.writeText(sb.toString(), Charsets.UTF_8)
    }
}
