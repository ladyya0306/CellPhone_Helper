package com.example.llama

/**
 * 「程序先查记忆」：用户问"你记得我…吗 / 我喜欢什么 / 你了解我多少 / 昨天说了什么 / 今天天气"这类问题时，
 * 由程序直接用真实数据回答，**不交给模型**。纯 Kotlin，可单测。
 *
 * 为什么：2026-10-19 在五个本地模型（2B–9B）上实测，没有任何记忆时问"我喜欢喝什么"，**全部 0/6 编造**（冰美式、特调咖啡……）；
 * 即使在 prompt 里明确写"没有记录，请如实说不记得"，傲娇人设仍说"本小姐当然记得！"并编出细节（带提示后仅 3–5/9）。
 * 模型自己不会"知道自己不知道"，所以这件事必须由程序做：查到 → 说出记录；查不到 → 如实说"你还没告诉过我"。
 * 同时这类问题不再占用一次 20–30 秒的模型推理，秒回。
 */
object MemoryQA {

    enum class Q { NAME, ABOUT_ME, KNOW_ME, PAST, WORLD }

    /** topic：ABOUT_ME 的话题词（喝/吃/生日…，空 = 泛问）；PAST 的时间词（昨天/前天/上次）；WORLD 的话题（天气/新闻/行情） */
    data class Query(val kind: Q, val topic: String = "")

    // ------------------------------------------------------------------ 识别
    /** 句子里要有"在问"的迹象；陈述句（"我喜欢喝冰美式"）不能被拦截 */
    private val QUESTIONISH = Regex("吗|么|呢|什么|啥|哪|谁|几|多少|怎么样|如何|咋样|记得|知道|了解|[?？]\\s*$")

    private val PAST_Q = Regex(
        "(还)?记得我(昨天|前天|上次|之前|以前|刚才).{0,10}(说|讲|提|聊)|" +
            "我(昨天|前天|上次|之前|以前).{0,8}(说|讲|聊|提)过什么|" +
            "上次(我们)?(聊|说|讲)(到|了)(什么|哪)|我们(昨天|上次|之前|以前)聊(了|过)什么"
    )
    private val NAME_Q = Regex("(记得|知道)(我|俺)(的)?(名字|姓名|叫什么)|我叫什么(名字)?|我的名字(是|叫)什么")

    /** 话题词：问到这些的"我…你记得/知道吗"算问具体记录 */
    private val TOPIC_WORDS = listOf("生日", "睡", "吃", "喝", "爱好", "口味", "作息", "周报", "工作", "上班", "猫", "狗", "宠物", "电话", "地址", "年龄")
    private val ABOUT_ME_Q = listOf(
        Regex("我(喜欢|爱|常|习惯|平时|一般|最爱)[^，。！？?]{0,6}(什么|啥|哪)"),
        Regex("(你)?(还)?(记得|知道|了解)我(的)?[^，。！？?]{0,8}"),     // 再看是否含话题词
        Regex("我[^，。！？?]{0,8}(你(知道|记得)吗|你知不知道|你还记得)"),
        Regex("我(的)?(生日)[^，。！？?]{0,4}(哪天|几号|什么时候|是什么|是多少)"),
    )
    private val KNOW_ME_Q = Regex("你(都)?(了解|知道|认识)我(多少|什么|多深)|你对我的了解(有多少|多吗|深吗)|你(都)?知道我些什么|你记得我(什么|多少)")

    private val WORLD_Q = Regex(
        "((今天|明天|后天|现在|最近|这几天|周末).{0,4}(天气|气温|下雨|下雪|降温|升温))|天气(怎么样|如何|好吗|好不好|咋样|预报)|" +
            "(聊聊|说说|看看|讲讲|说一说).{0,3}天气|(新闻|热搜|头条|股市|股价|汇率|油价|比分|彩票)"
    )

    /** 天气的另一类问法：要带伞吗 / 冷不冷 / 会不会下雨 / 今天多少度（自带疑问形式，不依赖问号） */
    private val WEATHER_Q = Regex("要不要带伞|带不带伞|需不需要带伞|(要|该|需要)带伞(吗|么)|带伞(吗|么)|冷不冷|热不热|下不下(雨|雪)|会不会(下雨|下雪)|多少度|几度")

    private val STEP_Q = Regex("走了(多少|几)步|步数|多少步|几步")
    private val BRIEF_Q = Regex("晨报|晚报|早报|简报|(今天|明天|今日|明日)(有)?(什么|啥|哪些)安排")

    fun detect(text: String): Query? {
        val t = text.trim()
        if (STEP_Q.containsMatchIn(t)) return Query(Q.WORLD, "步数")
        if (BRIEF_Q.containsMatchIn(t)) return Query(Q.WORLD, "简报")
        if (WEATHER_Q.containsMatchIn(t) && !Regex("我.{0,3}(带了伞|不带伞)").containsMatchIn(t)) return Query(Q.WORLD, "天气")
        if (t.isEmpty()) return null
        if (PAST_Q.containsMatchIn(t)) {
            val w = Regex("昨天|前天").find(t)?.value ?: "上次"
            return Query(Q.PAST, w)
        }
        if (NAME_Q.containsMatchIn(t) && QUESTIONISH.containsMatchIn(t)) return Query(Q.NAME)
        // 具体话题的问记忆
        for ((i, re) in ABOUT_ME_Q.withIndex()) {
            val m = re.find(t) ?: continue
            if (!QUESTIONISH.containsMatchIn(t)) continue
            val topic = TOPIC_WORDS.firstOrNull { t.contains(it) }
            // 只有"我喜欢…什么"这种可以没有话题词（泛问偏好）；其余必须带话题词，
            // 否则"你了解我多少"留给 KNOW_ME，"我明天要面试你知道吗"（在告诉它事情）更不能当提问
            if (i != 0 && topic == null) continue
            if (m.value.isNotBlank()) return Query(Q.ABOUT_ME, topic ?: "")
        }
        if (KNOW_ME_Q.containsMatchIn(t)) return Query(Q.KNOW_ME)
        if (WORLD_Q.containsMatchIn(t) && (QUESTIONISH.containsMatchIn(t) || Regex("聊聊|说说|看看|讲讲").containsMatchIn(t))) {
            val topic = when {
                Regex("新闻|热搜|头条").containsMatchIn(t) -> "新闻"
                Regex("股|汇率|油价|比分|彩票").containsMatchIn(t) -> "行情和比分"
                else -> "天气"
            }
            return Query(Q.WORLD, topic)
        }
        return null
    }

    // ------------------------------------------------------------------ 回答
    private val NAME_KEY = Regex("^(名字|称呼|以后(都|就)?(叫我|称呼我|喊我))")
    private val PREFERENCE = Regex("喜欢|爱|不吃|讨厌|习惯|常|偏好|最")

    /** 实时数据（天气）的回答：给人设加一句话头，数据原样保留 */
    fun lead(persona: Persona, text: String): String = PersonaPacks.packOf(persona.id).weatherLead + text

    /** 回答。facts = 全部记忆；pastUserLines = 用户当时亲口说的话（由调用方按时间查库） */
    fun answer(q: Query, facts: List<MemoryRelevance.Fact>, pastUserLines: List<String>, persona: Persona): String {
        val v = voice(persona)
        return when (q.kind) {
            Q.NAME -> {
                val name = facts.firstOrNull { it.key.startsWith("名字：") }?.key?.removePrefix("名字：")?.trim()
                    ?: facts.firstOrNull { it.key.startsWith("以后叫我") }?.key?.removePrefix("以后叫我")?.trim()
                if (name.isNullOrBlank()) v.unknown("你的名字") else v.recall("你叫「$name」")
            }
            Q.ABOUT_ME -> {
                val cand = facts.filterNot { NAME_KEY.containsMatchIn(it.key) }
                val picked = if (q.topic.isBlank()) cand.filter { PREFERENCE.containsMatchIn(it.key) }
                else {
                    // 记录里直接带这个话题词的优先（问"吃什么"只答吃的）；没有再按相关性找
                    val direct = cand.filter { it.key.contains(q.topic) }
                    direct.ifEmpty { MemoryRelevance.select(cand, q.topic, 3).filterNot { NAME_KEY.containsMatchIn(it.key) } }.take(3)
                }
                if (picked.isEmpty()) v.unknown(topicLabel(q.topic))
                else v.recall("你告诉过我：" + picked.joinToString("；") { readable(it.key) })
            }
            Q.KNOW_ME -> {
                if (facts.isEmpty()) v.stranger()
                else v.recall("我知道的有：" + facts.take(6).joinToString("；") { readable(it.key) } + (if (facts.size > 6) "……（还有 ${facts.size - 6} 条）" else ""))
            }
            Q.PAST -> {
                if (pastUserLines.isEmpty()) v.noRecord(q.topic)
                else v.recall("${q.topic}你说过：" + pastUserLines.takeLast(3).joinToString("；") { "「" + it.take(30) + "」" })
            }
            Q.WORLD -> v.noNet(q.topic)
        }
    }

    /** "生日：3月15日" → "生日是3月15日"，"名字：小明" → "名字是小明"，其余原样 */
    private fun readable(key: String): String = key.trimEnd('。').replaceFirst("：", "是")

    private fun topicLabel(topic: String) = when (topic) {
        "" -> "你的喜好"
        "喝", "吃" -> "你爱${topic}什么"
        "睡" -> "你的作息"
        else -> "你的$topic"
    }

    // ------------------------------------------------------------------ 人设口吻（程序写的短句，不调用模型；话术在各人设的 Pack*.kt 里）
    private class Voice(
        val recall: (String) -> String,
        val unknown: (String) -> String,
        val noNet: (String) -> String,
        val stranger: () -> String,
        val noRecord: (String) -> String,
    )

    private fun voice(p: Persona): Voice {
        val q = PersonaPacks.packOf(p.id).qa
        return Voice({ q.recall.replace("{x}", it) }, { q.unknown.replace("{x}", it) }, { q.noNet.replace("{x}", it) }, { q.stranger }, { q.noRecord.replace("{x}", it) })
    }
}
