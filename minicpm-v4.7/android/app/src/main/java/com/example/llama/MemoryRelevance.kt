package com.example.llama

/**
 * 记忆相关性筛选 —— 纯 Kotlin，可单测。
 *
 * 问题：旧实现每轮把**全部**事实注入，模型于是在每句话里硬塞"生日3月15日、周五交周报、冰美式"
 *       （真实数据库第 43–55 条），甚至把"明天6点"解析成"3月15日"。
 *
 * 做法（成熟检索系统的最小可用版本）：
 *   1. "常驻画像"：名字/称呼这类每轮都需要的，始终带上（最多 2 条）
 *   2. 其余事实只有与本轮输入**相关**时才带：关键词字重叠 + 话题触发词
 *   3. 每轮最多注入 [max] 条
 */
object MemoryRelevance {

    data class Fact(val key: String, val value: String)

    private val ALWAYS = Regex("^(名字|称呼|以后(都|就)?(叫我|称呼我|喊我))")

    /** 话题触发：用户这句话属于某个话题时，相应的事实才相关 */
    private val TOPICS: List<Pair<Regex, Regex>> = listOf(
        Regex("吃|喝|饭|餐|菜|咖啡|奶茶|外卖|点单|零食|水果|饮料|饿|口味|做饭|食堂|火锅|早饭|午饭|晚饭|夜宵") to
            Regex("吃|喝|咖啡|美式|拿铁|奶茶|茶|菜|辣|甜|香菜|葱|蒜|饭|面|肉|海鲜|水果"),
        Regex("睡|熬夜|起床|作息|晚安|早安|失眠|困|几点休息") to Regex("睡|起床|作息|熬夜|午休"),
        Regex("生日|过生|礼物|几岁|纪念日|庆祝") to Regex("生日|纪念"),
        Regex("周报|工作|上班|开会|会议|加班|报告|领导|同事|项目|汇报|下班") to Regex("周报|工作|上班|开会|会议|报告|项目|汇报|同事|领导"),
        Regex("运动|跑步|健身|锻炼|散步|爬山|游泳") to Regex("运动|跑步|健身|锻炼|散步|游泳"),
    )

    /** 这些二字组太泛，不算相关证据 */
    private val GENERIC_BIGRAMS = setOf("喜欢", "不喜", "习惯", "以后", "记住", "我的", "一个", "今天", "明天", "什么", "怎么", "可以", "我们", "你们", "一下")

    private fun bigrams(s: String): Set<String> {
        val clean = s.filter { it.isLetterOrDigit() }
        if (clean.length < 2) return emptySet()
        return (0 until clean.length - 1).map { clean.substring(it, it + 2) }.filter { it !in GENERIC_BIGRAMS }.toSet()
    }

    fun select(facts: List<Fact>, query: String, max: Int = 3): List<Fact> {
        if (facts.isEmpty()) return emptyList()
        val always = facts.filter { ALWAYS.containsMatchIn(it.key) }.take(2)
        val q = bigrams(query)
        val scored = facts.filter { it !in always }.map { f ->
            var score = (bigrams(f.key) intersect q).size * 2
            for ((trigger, factPattern) in TOPICS) {
                if (trigger.containsMatchIn(query) && factPattern.containsMatchIn(f.key)) score += 3
            }
            f to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        return (always + scored).take(max.coerceAtLeast(always.size))
    }

    fun render(selected: List<Fact>): String {
        if (selected.isEmpty()) return ""
        return "【与本轮相关的已知信息（仅供参考，无关就不要提）】\n" + selected.joinToString("\n") { "· ${it.key}" }
    }
}
