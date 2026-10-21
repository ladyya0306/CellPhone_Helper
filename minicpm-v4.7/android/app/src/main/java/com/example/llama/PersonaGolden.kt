package com.example.llama

/**
 * 人设资源的"金标准快照"：把每个人设在所有模块里的话术（回执、问答、天气话头、心跳话术、专注台词、系统提示、人设本身）
 * 按固定输入展开成排序后的文本。重构前后各生成一份，逐字相同才算"没改坏"。开发工具，不参与运行。
 */
object PersonaGolden {
    fun snapshot(): String {
        val sb = StringBuilder()
        fun add(p: String, sec: String, t: String) { sb.append(p).append('|').append(sec).append('|').append(t.replace("\n", "\n")).append('\n') }
        val facts = listOf(MemoryRelevance.Fact("名字：测试君", "我叫测试君"), MemoryRelevance.Fact("喜欢喝冰美式", "我喜欢喝冰美式"), MemoryRelevance.Fact("习惯晚上11点睡", "我习惯晚上11点睡"))
        val card1 = HeartbeatCompose.Card(1_000_000_000_000L, listOf(HeartbeatCompose.Item(HeartbeatCompose.Kind.CALENDAR, 1_000_000_000_000L + 20 * 60_000L, "开会")), emptyList(), emptyList())
        val card2 = HeartbeatCompose.Card(1_000_000_000_000L, listOf(HeartbeatCompose.Item(HeartbeatCompose.Kind.CALENDAR, 1_000_000_000_000L + 5 * 3_600_000L, "牙医")), emptyList(), emptyList())
        for (p in Personas.ALL) {
            val id = p.id
            add(id, "persona", "${p.id}|${p.name}|${p.avatar}|${p.selfRef}|${p.userRef}|${p.tics}|${p.ticRate}|${p.emotionalValue}|${p.style}|${p.taboos}|${p.fallback}")
            add(id, "prompt", Personas.promptFor(p))
            (1..400).map { PersonaVoice.done(p) }.toSortedSet().forEach { add(id, "done", it) }
            add(id, "partial", PersonaVoice.partial(p)); add(id, "fail", PersonaVoice.fail(p)); add(id, "ask", PersonaVoice.ask(p, "几点"))
            fun qa(label: String, q: MemoryQA.Query, f: List<MemoryRelevance.Fact>, past: List<String>) = add(id, "qa-$label", MemoryQA.answer(q, f, past, p))
            qa("name", MemoryQA.Query(MemoryQA.Q.NAME, ""), facts, emptyList()); qa("name0", MemoryQA.Query(MemoryQA.Q.NAME, ""), emptyList(), emptyList())
            qa("about", MemoryQA.Query(MemoryQA.Q.ABOUT_ME, "喝"), facts, emptyList()); qa("about0", MemoryQA.Query(MemoryQA.Q.ABOUT_ME, "喝"), emptyList(), emptyList())
            qa("know", MemoryQA.Query(MemoryQA.Q.KNOW_ME, ""), facts, emptyList()); qa("know0", MemoryQA.Query(MemoryQA.Q.KNOW_ME, ""), emptyList(), emptyList())
            qa("past", MemoryQA.Query(MemoryQA.Q.PAST, "昨天"), emptyList(), listOf("我喜欢猫")); qa("past0", MemoryQA.Query(MemoryQA.Q.PAST, "昨天"), emptyList(), emptyList())
            qa("world", MemoryQA.Query(MemoryQA.Q.WORLD, "新闻"), emptyList(), emptyList())
            add(id, "lead", MemoryQA.lead(p, "X"))
            val hb = HashSet<String>()
            for (seed in 0..40) { HeartbeatCompose.compose(card1, p, seed)?.let { hb.add("I:" + it.text) }; HeartbeatCompose.compose(card2, p, seed)?.let { hb.add("N:" + it.text) } }
            hb.toSortedSet().forEach { add(id, "hb", it) }
            for (topic in listOf("morning", "noon", "evening", "night", "todo", "care", "xyz")) (1..600).map { HeartbeatMessages.forTopic(p, topic) }.toSortedSet().forEach { add(id, "hbmsg-$topic", it) }
        }
        FocusLines.allTemplates().forEach { (k, t) -> add(k.substringBefore('/'), "focus-" + k.substringAfter('/'), t) }
        add("_", "gen", "${PersonaVoice.done(Persona("zzz", "Z", "z", "z", "u", emptyList(), 0.0, emptyList(), "", emptyList(), ""))}|${PersonaVoice.partial(Persona("zzz", "Z", "z", "z", "u", emptyList(), 0.0, emptyList(), "", emptyList(), ""))}")
        return sb.toString().lines().filter { it.isNotEmpty() }.sorted().joinToString("\n") + "\n"
    }

    @JvmStatic fun main(args: Array<String>) { java.io.File(args[0]).writeText(snapshot(), Charsets.UTF_8) }
}
