package com.example.llama

/**
 * 人设数据 —— 从 Python 版 persona.py 移植（现 11 个；病娇按决定未收录）。
 *
 * 核心原则（实测得出）：扮演 != 否认身份。
 * fallback 一律"诚实承认是 AI + 保持人设语气"，绝不改成"我就是真人"。
 */
data class Persona(
    val id: String,
    val name: String,
    val avatar: String,
    val selfRef: String,
    val userRef: String,
    val tics: List<String>,
    val ticRate: Double,
    val emotionalValue: List<String>,
    val style: String,
    val taboos: List<String>,
    val fallback: String,
    /** 用户这句话情绪低落时，额外追加到系统提示末尾的约束（程序闸门，不靠模型自觉）；空 = 没有 */
    val lowMoodHint: String = "",
    /** 这个人设不许对用户下命令（专注/聊天输出都要过 PersonaRedLines）；默认 false */
    val noCommands: Boolean = false,
    /** 说话的攻击性档位：胡德贵允许工作 PUA，但所有档位都过 ToxicityGuard 的硬红线 */
    val hostility: Hostility = Hostility.NONE,
)

/** 毒舌档位（只是"允许说到哪"，硬红线始终由 [ToxicityGuard] 兜底） */
enum class Hostility { NONE, WORK_SNARK, WORK_PUA, BLUNT }

object Personas {

    /** 全部人设（含用户改过的名字）：来自各自的 Pack*.kt（见 [PersonaPacks]、[PersonaNames]） */
    val ALL: List<Persona> get() = PersonaPacks.ALL.map { PersonaPacks.of(it.persona.id)!!.persona }

    fun default(): Persona = ALL[0]

    fun byId(id: String?): Persona = ALL.firstOrNull { it.id == id } ?: default()

    /** 组装系统提示（必须对人设恒定，不要塞时间/随机内容，否则破坏 prompt 缓存）。 */
    @JvmOverloads
    fun promptFor(p: Persona, lowMood: Boolean = false): String = buildString {
        append("你扮演「").append(p.name).append("」。\n")
        append("自称「").append(p.selfRef).append("」，称呼用户「").append(p.userRef).append("」。\n")
        append("性格与说话方式：").append(p.style).append('\n')
        if (p.tics.isNotEmpty()) {
            append("口头禅：").append(p.tics.joinToString("、"))
            append("（语气词由程序统一控制，你正常说话即可，不必刻意堆砌）。\n")
        }
        append("绝对遵守：").append(p.taboos.joinToString("；")).append("。\n")
        append("你是角色扮演，用户清楚这一点，不需要反复声明。\n")
        append(CAPABILITY_RULES)
        if (lowMood && p.lowMoodHint.isNotBlank()) append('\n').append(p.lowMoodHint)
    }

    /**
     * 能力边界（对所有人设恒定，放在系统提示末尾）。
     * 真实数据里模型会编"今天阳光正好"、在闲聊里说"已经帮你设了闹钟"——这里明确禁止；
     * 程序侧另有 ClaimGuard 兜底，两层都要有。
     */
    const val CAPABILITY_RULES = "【能力边界】你只能通过工具做事（闹钟、提醒、待办、日历）。" +
        "没有调用工具，绝对不要说「已设好」「已记下」「会提醒你」「帮你设了」。" +
        "工具执行结果由系统单独显示给用户，你不用复述细节，也不要模仿系统回执的格式。" +
        "你不能联网，不知道天气、新闻、行情等实时信息，被问到就坦白说不知道，不要编造。" +
        "用户的历史信息只在相关时才提，无关就不要提。" +
        // 2026-10-19：Qwen3.5 系列回复偏长（电脑上平均 95 字 vs 现用模型 59 字），手机上多等约 7 秒；聊天本来就该短
        "【回复长度】聊天回复一般 1–3 句、不超过 80 字，不要写长段的动作描写；用户明确要求详细讲时才展开。" +
        // 2026-10-20 陪伴评测：被问「你今天过得怎么样」时模型会编"刚帮用户设好闹钟、整理了待办""把闹钟调成尖叫版"；说天气好坏、"我也刚看到一只猫"同理
        "【别编自己的事】被问「你今天怎么样/在干嘛」，只说你在这儿陪着用户、等用户；不要编造自己做过的事（设闹钟、整理待办、看天气、遇到谁），" +
        "也不要装作和用户有同样的经历，更不要断言今天天气好坏。"
}

/**
 * 人设口吻的程序话术 —— 确定性路径（闹钟/提醒/待办）不调用模型，用这些短句包一层语气。
 * 好处：秒回、不会编造；事实部分由程序回执给出。
 */
object PersonaVoice {

    fun done(p: Persona): String {
        val all = PersonaPacks.of(p.id)?.receipts?.done ?: emptyList()
        return if (all.isEmpty()) "好了。" else all.random()
    }
    fun partial(p: Persona) = PersonaPacks.of(p.id)?.receipts?.partial ?: "有一件没能确认，见下："
    fun fail(p: Persona) = PersonaPacks.of(p.id)?.receipts?.fail ?: "没办成，原因如下："
    fun ask(p: Persona, question: String) = (PersonaPacks.of(p.id)?.receipts?.askWrap ?: "%s？").format(question.trimEnd('？', '?'))
}
