package com.example.llama

import kotlin.random.Random

/**
 * 专注模式台词库：预置台词 + 槽位填充。纯 Kotlin，可单测。
 *
 * 为什么是"库"而不是现场让模型写：干扰发生时要立刻弹字；本地模型冷启动约 17 秒、一条约 3.5 秒，而且后台会被系统冻结。
 * 所以台词事先备好，运行时程序按「人设 × 事件 × 档位」抽一条、填上事实（几秒、第几次、哪个应用）。
 * 数字永远来自 [FocusEngine]，台词只负责语气；每一条模板都必须通过 [InsultGuard]（自检里逐条验证）。
 * 后续（F3）模型会在空闲时批量生成更多台词，同样先过 InsultGuard 才能入库。
 *
 * 档位：CALM 平静 → NAG 唠叨 → SNARK 阴阳怪气。难度「温和」取低一档的台词，「地狱」取高一档。
 * 树洞人设永远只用温和台词（树洞是用来倾诉的，不开骂）。
 */
object FocusLines {

    enum class Kind { START, LEAVE, PEEK, DISTRACT, RETURN, BLOWN, COLD_END, FINISH_S, FINISH_A, FINISH_B, FINISH_C }

    private fun FocusKit.byLevel(kind: Kind, idx: Int): List<String>? = when (kind) {
        Kind.LEAVE -> leave.getOrNull(idx) ?: emptyList(); Kind.PEEK -> peek.getOrNull(idx) ?: emptyList(); Kind.DISTRACT -> distract.getOrNull(idx) ?: emptyList(); else -> null
    }
    private fun FocusKit.flat(kind: Kind): List<String>? = when (kind) {
        Kind.START -> start; Kind.RETURN -> ret; Kind.BLOWN -> blown; Kind.COLD_END -> coldEnd
        Kind.FINISH_S -> listOfNotNull(finish.getOrNull(0)); Kind.FINISH_A -> listOfNotNull(finish.getOrNull(1)); Kind.FINISH_B -> listOfNotNull(finish.getOrNull(2)); Kind.FINISH_C -> listOfNotNull(finish.getOrNull(3))
        else -> null
    }

    private fun setOf(personaId: String): FocusKit = PersonaPacks.packOf(personaId).focus
    private fun gentleOnly(personaId: String) = PersonaPacks.of(personaId)?.roast == false

    /** 难度决定取哪一档的台词：温和低一档、地狱高一档（最高到阴阳怪气；爆表按阴阳怪气算） */
    fun effectiveLevel(level: FocusEngine.Level, d: FocusEngine.Difficulty): FocusEngine.Level {
        val base = when (level) { FocusEngine.Level.CALM -> 0; FocusEngine.Level.NAG -> 1; else -> 2 }
        val shifted = (base + when (d) { FocusEngine.Difficulty.MILD -> -1; FocusEngine.Difficulty.SPICY -> 0; FocusEngine.Difficulty.HELL -> 1 }).coerceIn(0, 2)
        return when (shifted) { 0 -> FocusEngine.Level.CALM; 1 -> FocusEngine.Level.NAG; else -> FocusEngine.Level.SNARK }
    }

    fun fill(template: String, slots: Map<String, String>): String {
        var s = template
        for ((k, v) in slots) s = s.replace("{$k}", v)
        return s
    }

    @Volatile private var EXTRA: Map<String, List<String>> = emptyMap()

    fun personaIds(): List<String> = PersonaPacks.ALL.map { it.persona.id }

    /**
     * 载入模型批量生成的台词（F3）。每一条都在这里再过一遍 [FocusLineBank.validate]，不合格的、格子不存在的都丢掉——
     * 所以即使库文件被手改过、或者来自旧版规则，脏的、带数字的也进不来。
     */
    fun setExtra(raw: Map<String, List<String>>) {
        val valid = cellKeys()
        EXTRA = raw.filterKeys { it in valid }.mapValues { (k, lines) ->
            val kind = Kind.valueOf(k.split("/")[1])
            lines.map { it.trim() }.filter { FocusLineBank.validate(it, kind).isEmpty() }.distinct().takeLast(FocusLineBank.MAX_PER_CELL)
        }.filterValues { it.isNotEmpty() }
    }

    fun extraCount(): Int = EXTRA.size
    fun extraLineCount(): Int = EXTRA.values.sumOf { it.size }

    private fun cellKeys(): Set<String> = FocusLineBank.cells().map { (p, k, i) -> FocusLineBank.cellKey(p, k, i) }.toSet()

    /** 抽一条「模板」（还没填槽位）。avoid = 最近用过的模板：能避开就避开，全用过了或只有一条时照样能抽 */
    fun pickTemplate(personaId: String, kind: Kind, level: FocusEngine.Level, d: FocusEngine.Difficulty, rnd: Random, avoid: Collection<String> = emptyList()): String {
        val set = setOf(personaId)
        val lv = if (gentleOnly(personaId)) FocusEngine.Level.CALM else effectiveLevel(level, d)
        val idx = lv.ordinal.coerceAtMost(2)
        val base = set.byLevel(kind, idx) ?: set.flat(kind) ?: PersonaPacks.GENERIC.focus.flat(Kind.START)!!
        val leveled = set.byLevel(kind, 0) != null
        val extra = if (PersonaPacks.of(personaId) != null) EXTRA[FocusLineBank.cellKey(personaId, kind, if (leveled) idx else 0)] ?: emptyList() else emptyList()
        val pool = base + extra
        val fresh = pool.filter { it !in avoid }
        val from = if (fresh.isNotEmpty()) fresh else pool
        return from[rnd.nextInt(from.size)]
    }

    fun pick(personaId: String, kind: Kind, level: FocusEngine.Level, d: FocusEngine.Difficulty, slots: Map<String, String>, rnd: Random): String =
        fill(pickTemplate(personaId, kind, level, d, rnd), slots)

    /** 这一格里有几条可抽的台词（用于判断"能不能避开复读"） */
    fun poolSize(personaId: String, kind: Kind, level: FocusEngine.Level, d: FocusEngine.Difficulty): Int {
        val set = setOf(personaId)
        val lv = if (gentleOnly(personaId)) FocusEngine.Level.CALM else effectiveLevel(level, d)
        return (set.byLevel(kind, lv.ordinal.coerceAtMost(2)) ?: set.flat(kind) ?: emptyList()).size
    }

    /** 引擎反应 → 台词种类；没有对应台词的反应（升档提示、回血）返回 null，不出声 */
    fun kindFor(r: FocusEngine.Reaction): Kind? = when (r.kind) {
        FocusEngine.Kind.START -> Kind.START
        FocusEngine.Kind.LEAVE -> Kind.LEAVE
        FocusEngine.Kind.SCREEN_PEEK -> Kind.PEEK
        FocusEngine.Kind.DISTRACT -> Kind.DISTRACT
        FocusEngine.Kind.RETURN -> Kind.RETURN
        FocusEngine.Kind.BLOWN -> Kind.BLOWN
        FocusEngine.Kind.COLD_END -> Kind.COLD_END
        FocusEngine.Kind.FINISH -> when (r.slots["grade"]) { "S" -> Kind.FINISH_S; "A" -> Kind.FINISH_A; "B" -> Kind.FINISH_B; else -> Kind.FINISH_C }
        else -> null
    }

    /** 一个 FocusKit 里的所有模板：(格子编号, 事件, 模板)。人设资源齐全性检查用 */
    fun templatesOf(f: FocusKit): List<Triple<String, Kind, String>> {
        val out = ArrayList<Triple<String, Kind, String>>()
        for (k in Kind.values()) {
            val lists = f.byLevel(k, 0)?.let { _ -> (0..2).map { f.byLevel(k, it)!! } } ?: listOf(f.flat(k)!!)
            lists.forEachIndexed { i, lines -> lines.forEachIndexed { j, t -> out.add(Triple("$k/$i/$j", k, t)) } }
        }
        return out
    }

    /** 所有模板（含兜底）：自检逐条过 InsultGuard 用。返回 (编号, 模板) */
    fun allTemplates(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val all = PersonaPacks.ALL.map { it.persona.id to PersonaPacks.packOf(it.persona.id).focus } + ("generic" to PersonaPacks.GENERIC.focus)
        for ((pid, kit) in all) for ((cell, _, t) in templatesOf(kit)) out.add("$pid/$cell" to t)
        return out
    }
}
