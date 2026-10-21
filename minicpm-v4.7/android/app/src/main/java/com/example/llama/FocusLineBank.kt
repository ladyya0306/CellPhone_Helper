package com.example.llama

/**
 * 模型批量生成台词的「清洗 + 校验 + 去重」。纯 Kotlin，可单测，电脑端评测和手机端载入共用同一份规则。
 *
 * 为什么要这么多关：本地小模型写出来的东西什么都有——脏话、数字对不上事实、出戏（"作为 AI"）、Markdown、复读。
 * 所以模型只负责"出主意"，能不能进库由程序说了算：
 *   1. 清洗（去编号、引号）
 *   2. 校验：InsultGuard（脏字/人身攻击）、不含数字和数量词（数字必须来自事实槽位，免得和事实对不上）、槽位与事件匹配、
 *      不出戏、不带 Markdown、长度合适
 *   3. 去重：与库里已有的、与同批里的，近似重复都不要
 * 过关的才入库；载入运行时（[FocusLines.setExtra]）再过一遍同样的关，所以即使库文件被改过，脏的也进不来。
 */
object FocusLineBank {

    data class Rejected(val line: String, val reasons: List<String>)
    data class Parsed(val accepted: List<String>, val rejected: List<Rejected>)

    private const val MIN_LEN = 6
    private const val MAX_LEN = 40
    const val MAX_PER_CELL = 40

    private val BULLET = Regex("^\\s*(?:[（(]?\\d{1,2}[)）.、:：]?\\s*|[-•·*]+\\s*)")
    private val DIGIT = Regex("[0-9０-９]")
    private val CN_QTY = Regex("[二三四五六七八九十百千两]\\s*(?:次|秒|分钟|分|遍|回|点钟|小时|天|个钟|下)|第\\s*[二三四五六七八九十百千两]")
    private val OOC = Regex("(?i)AI|人工智能|语言模型|大模型|模型|系统提示|提示词|作为一个|助手|程序|ChatGPT|机器人")
    private val MARKDOWN = Regex("[*#`>_~]|^\\s*[-•]")
    private val SLOT = Regex("\\{([^{}]*)\\}")

    private val ALLOWED_SLOTS: Map<FocusLines.Kind, Set<String>> = mapOf(
        FocusLines.Kind.START to setOf("min"),
        FocusLines.Kind.LEAVE to setOf("sec", "n", "rage"),
        FocusLines.Kind.PEEK to setOf("peeks", "rage"),
        FocusLines.Kind.DISTRACT to setOf("app", "rage"),
        FocusLines.Kind.RETURN to setOf("sec", "rage"),
        FocusLines.Kind.BLOWN to setOf("cold", "rage"),
        FocusLines.Kind.COLD_END to emptySet(),
        FocusLines.Kind.FINISH_S to setOf("grade", "n", "peeks", "blowns"),
        FocusLines.Kind.FINISH_A to setOf("grade", "n", "peeks", "blowns"),
        FocusLines.Kind.FINISH_B to setOf("grade", "n", "peeks", "blowns"),
        FocusLines.Kind.FINISH_C to setOf("grade", "n", "peeks", "blowns"),
    )

    private val SLOT_HINT = mapOf(
        "sec" to "{sec}（用户离开了多少秒）", "n" to "{n}（这是第几次）", "peeks" to "{peeks}（亮屏第几次）", "app" to "{app}（用户打开的应用名）",
        "min" to "{min}（专注多少分钟）", "rage" to "{rage}（当前生气值）", "cold" to "{cold}（冷战多久，已带单位）", "grade" to "{grade}（评级 S/A/B/C）", "blowns" to "{blowns}（爆表几次）",
    )

    private val KIND_DESC = mapOf(
        FocusLines.Kind.START to "专注刚开始时的开场白",
        FocusLines.Kind.LEAVE to "用户刚刚离开了专注界面（切走了）",
        FocusLines.Kind.PEEK to "用户离开专注界面后又亮屏看手机",
        FocusLines.Kind.DISTRACT to "用户打开了一个分心的应用",
        FocusLines.Kind.RETURN to "用户回到了专注界面",
        FocusLines.Kind.BLOWN to "角色气到爆表，宣布开始冷战、不理用户一段时间",
        FocusLines.Kind.COLD_END to "冷战结束，角色消气、让用户继续专注",
        FocusLines.Kind.FINISH_S to "专注结束，评级 S（全程零干扰）的总结",
        FocusLines.Kind.FINISH_A to "专注结束，评级 A（只走神一次左右）的总结",
        FocusLines.Kind.FINISH_B to "专注结束，评级 B（有些分心但没爆）的总结",
        FocusLines.Kind.FINISH_C to "专注结束，评级 C（爆过表）的总结",
    )

    private val LEVEL_DESC = listOf("平静克制，点到为止", "开始唠叨，带点无奈", "阴阳怪气，损得有创意")

    fun cleanLine(s: String): String {
        var t = s.trim()
        t = BULLET.replace(t, "")
        t = t.trim().trim('"', '“', '”', '「', '」', '『', '』', '\'', '‘', '’').trim()
        return t
    }

    fun validate(line: String, kind: FocusLines.Kind): List<String> {
        val out = ArrayList<String>()
        val t = line.trim()
        if (t.length < MIN_LEN) out.add("太短")
        if (t.length > MAX_LEN) out.add("太长")
        if (t.endsWith("：") || t.endsWith(":")) out.add("像前言不像台词")
        InsultGuard.check(t).filter { it != "太短" && it != "太长" }.forEach { out.add("InsultGuard：$it") }
        if (DIGIT.containsMatchIn(t)) out.add("含数字（数字要用槽位）")
        if (CN_QTY.containsMatchIn(t)) out.add("含数量词（数字要用槽位）")
        if (OOC.containsMatchIn(t)) out.add("出戏")
        if (MARKDOWN.containsMatchIn(t)) out.add("含 Markdown 符号")
        val allowed = ALLOWED_SLOTS[kind] ?: emptySet()
        val slots = SLOT.findAll(t).map { it.groupValues[1] }.toList()
        slots.filter { it !in allowed }.forEach { out.add("槽位不匹配：{$it}") }
        if (SLOT.replace(t, "").let { it.contains('{') || it.contains('}') }) out.add("槽位括号不成对")
        return out.distinct()
    }

    private fun grams(s: String): Set<String> {
        val t = s.filter { it.code > 0x2E7F || it.isLetterOrDigit() }.filter { !it.isWhitespace() }
        return if (t.length < 2) setOf(t) else (0 until t.length - 1).map { t.substring(it, it + 2) }.toSet()
    }

    /** 字二元组的 Jaccard ≥ 0.7 算近似重复 */
    fun nearDuplicate(a: String, b: String): Boolean {
        val x = grams(a); val y = grams(b)
        if (x.isEmpty() || y.isEmpty()) return false
        val inter = x.count { it in y }
        return inter.toDouble() / (x.size + y.size - inter) >= 0.7
    }

    /** 解析模型一次的输出：一行一条，清洗、校验、去重 */
    fun parse(raw: String, kind: FocusLines.Kind, existing: List<String> = emptyList()): Parsed {
        val accepted = ArrayList<String>()
        val rejected = ArrayList<Rejected>()
        for (l in raw.lines()) {
            val line = cleanLine(l)
            if (line.isEmpty()) continue
            if (line.endsWith("：") || line.endsWith(":")) continue          // "以下是台词：" 之类的前言，不算台词也不计入被拒
            val reasons = ArrayList(validate(line, kind))
            if (existing.any { it == line || nearDuplicate(it, line) } || accepted.any { it == line || nearDuplicate(it, line) }) reasons.add("与已有台词重复")
            if (reasons.isEmpty()) accepted.add(line) else rejected.add(Rejected(line, reasons))
        }
        return Parsed(accepted, rejected)
    }

    fun merge(existing: List<String>, new: List<String>, max: Int = MAX_PER_CELL): List<String> =
        (existing + new).distinct().takeLast(max)

    fun cellKey(personaId: String, kind: FocusLines.Kind, levelIdx: Int) = "$personaId/${kind.name}/$levelIdx"

    private val LEVELED = setOf(FocusLines.Kind.LEAVE, FocusLines.Kind.PEEK, FocusLines.Kind.DISTRACT)

    /** 全部「人设 × 事件 × 档位」格子 */
    fun cells(): List<Triple<String, FocusLines.Kind, Int>> =
        FocusLines.personaIds().flatMap { pid ->
            FocusLines.Kind.values().flatMap { k -> (if (k in LEVELED) 0..2 else 0..0).map { Triple(pid, k, it) } }
        }

    /** 给模型的生成提示。修辞菜单和禁止项写死在这里，生成后仍要过 [parse] 的校验——提示只能降低违规率，不能保证 */
    fun prompt(personaId: String, personaStyle: String, kind: FocusLines.Kind, levelIdx: Int, d: FocusEngine.Difficulty, n: Int): String {
        val slots = (ALLOWED_SLOTS[kind] ?: emptySet()).mapNotNull { SLOT_HINT[it] }
        val slotText = if (slots.isEmpty()) "这一类台词不需要任何数字，也不要用占位符。" else "需要数字或名称时只能用这些占位符原样写出：" + slots.joinToString("、") + "。"
        val dText = when (d) { FocusEngine.Difficulty.MILD -> "温和"; FocusEngine.Difficulty.SPICY -> "毒舌"; FocusEngine.Difficulty.HELL -> "地狱（最狠，但狠在创意不在脏）" }
        return buildString {
            append("你在为「专注监督」功能写台词。角色：").append(personaStyle).append("（人设编号 ").append(personaId).append("）。\n")
            append("场景：").append(KIND_DESC[kind] ?: "").append("。语气：").append(LEVEL_DESC[levelIdx.coerceIn(0, 2)]).append("。尺度：").append(dText).append("。\n")
            append("要求：\n")
            append("1. 写 ").append(n).append(" 条，每条只有一句话，不超过 36 个字，一行一条，不要编号、不要解释、不要引号。\n")
            append("2. 修辞只用这几种：反语、夸张、拟人、文言腔、歇后语/俏皮话、自嘲式连坐。要有画面感，别写成干巴巴的批评。\n")
            append("3. 绝对不能有脏字（包括谐音、拼音缩写）；不能做人身攻击——不涉及智力、外貌身材、家人、性别地域、疾病，不否定一个人的价值（不说「没用」「没出息」），不威胁；只吐槽「这一次的行为」。\n")
            append("4. 不要写任何阿拉伯数字或「三次」「两分钟」这类中文数量词。").append(slotText).append("\n")
            append("5. 不要提到 AI、模型、系统、程序；始终保持角色口吻。\n")
            append("现在直接输出，一行一条。")
        }
    }
}
