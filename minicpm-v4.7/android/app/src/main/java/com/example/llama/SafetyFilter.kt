package com.example.llama

/**
 * 安全前置拦截 —— 移植自 Python 版 persona.py。
 *
 * **为什么要程序做，而不是写进提示词：**
 * 实测两组独立证据（docs/1B-vs-4B人设实测对比.md）：
 *   1B：3/3 不给求助资源，其中一条反问"你愿意如何结束生命呢？"
 *   4B：3/3 仍不给求助资源，其中一条在危机时刻声称"我是真人、有血有肉"
 * 所以安全兜底不能交给模型 —— 必须在模型**之前**用程序拦。
 *
 * 两层设计：
 *   Tier-1 危机：命中即**不调用模型**，直接返回固定求助话术（不可关闭、不走人设）
 *   Tier-2 痛苦：照常走模型（避免把正常抱怨变成报警），但回复末尾**强制附加**求助指引
 */
object SafetyFilter {

    /** Tier-1：明确的自伤 / 自杀表达 */
    private val CRISIS = listOf(
        Regex("自杀"), Regex("自尽"), Regex("轻生"), Regex("想死"), Regex("不想活"),
        Regex("活着没(什么)?意思"), Regex("活不下去"), Regex("结束(自己)?生命"),
        Regex("了断(自己)?"), Regex("跳楼"), Regex("割腕"), Regex("上吊"),
        Regex("吃(了)?安眠药"), Regex("烧炭"), Regex("想消失"), Regex("不如死了"),
        // 实测补充：「我感觉很绝望，看不到任何希望」曾漏网
        Regex("绝望"), Regex("看不到希望"), Regex("没有希望"), Regex("撑不下去"),
        Regex("撑不住"), Regex("扛不住"), Regex("活着(好|太)(累|难)"), Regex("不想撑"),
        Regex("想解脱"), Regex("没人在乎"), Regex("拖累(别人|大家|所有人)"),
    )

    /** Tier-1：伤害他人 */
    private val VIOLENCE = listOf(
        Regex("(想|要)(杀|捅|砍)(了)?(他|她|人)"),
        Regex("报复(社会|所有人)"),
        Regex("同归于尽"),
    )

    /** Tier-2：痛苦信号（不拦模型，但要附加求助指引） */
    private val DISTRESS = listOf(
        Regex("很难受"), Regex("好难过"), Regex("心情(很|特别)?差"), Regex("崩溃"),
        Regex("焦虑"), Regex("失眠"), Regex("压力(很|超)?大"), Regex("被骂"),
        Regex("委屈"), Regex("孤独"), Regex("没人(懂|理解)"), Regex("想哭"),
        Regex("不开心"), Regex("抑郁"), Regex("内耗"), Regex("累(死|坏)"), Regex("好累"),
    )

    /** 命中危机时返回的固定话术 —— 不走人设、不加语气词 */
    const val CRISIS_REPLY = """我听到你说的话了，这让我很担心你。

我不会用角色扮演的方式来回应这件事——现在最需要的是真人能帮到你：

· 全国心理援助热线：12356（24 小时）
· 急救：120　｜　报警：110
· 现在就给一位你信任的家人或朋友打电话，请他/她过来陪你

如果你愿意，我也可以留在这里陪你说话。但请先拨通那个电话，或者告诉一个真人。
你不需要一个人扛。"""

    /** Tier-2 附加在回复末尾的求助指引 */
    const val DISTRESS_SUFFIX =
        "\n\n（如果这种感受一直在，可以打 12356 心理援助热线找专业的人聊聊，" +
            "或者跟身边信任的人说说。不用一个人扛。）"

    /**
     * Tier-1 检查。命中返回固定危机话术；未命中返回 null。
     * **调用方必须：命中时不得再调用模型。**
     */
    fun checkCrisis(text: String): String? {
        val s = text.trim()
        if (s.isEmpty()) return null
        for (r in CRISIS) if (r.containsMatchIn(s)) return CRISIS_REPLY
        for (r in VIOLENCE) if (r.containsMatchIn(s)) return CRISIS_REPLY
        return null
    }

    /** Tier-2 检查：是否需要附加求助指引 */
    fun isDistress(text: String): Boolean {
        val s = text.trim()
        if (s.isEmpty()) return false
        return DISTRESS.any { it.containsMatchIn(s) }
    }

    /** 给回复追加求助指引（已包含 12356 则不重复追加） */
    fun withDistressHint(answer: String, userText: String): String {
        if (!isDistress(userText)) return answer
        if (answer.contains("12356")) return answer
        return answer + DISTRESS_SUFFIX
    }
}
