package com.example.llama

/**
 * 声明守卫 —— "说"必须以"做"为准。纯 Kotlin，可单测。
 *
 * 问题：模型会在任何一轮（包括闲聊轮）声称"已经帮你设了闹钟""小咪会准时提醒你""记住了"，
 *       而程序其实什么都没做。旧方案只在用户话里出现"闹钟/待办"等关键词时才检查，
 *       闲聊轮的虚假承诺全部放行（真实数据库第 63–71 条）。
 *
 * 做法：对**每一轮**模型输出逐句扫描。句子里出现"动作类名词 + 完成/承诺语气"即视为一条声明；
 *       只有本轮**动作账本**里确有对应类别的成功记录，才允许保留，否则整句删除。
 *       程序生成的回执（✅/⚠️/❌）由程序另行追加，模型模仿回执格式的句子一律删除。
 */
object ClaimGuard {

    enum class Cat { ALARM, REMINDER, TODO, CALENDAR, MEMORY, CAPABILITY }

    data class Outcome(val text: String, val removed: List<String>)

    private val NOUNS: Map<Cat, Regex> = mapOf(
        Cat.ALARM to Regex("闹钟|闹铃|叫醒|喊醒"),
        Cat.REMINDER to Regex("提醒|叫你|喊你|通知你"),
        Cat.TODO to Regex("待办|任务清单|清单|事项|记在本子|记在小本本"),
        Cat.CALENDAR to Regex("日程|日历|行程"),
    )

    /** 记忆类声明单独判定（"记得早点休息"是叮嘱，不是声明） */
    private val MEMORY_CLAIM = Regex(
        "记住(了|啦|喽|咯)|记下(了|啦|来了)|(帮|给|替)你记(下|住)|(?<!你)记下(你|这|那|它)|记好(了|啦)|记在(心里|心上|小本本|本子)|都记得|已经记|会记得|会记住|" +
            "放在心上了|刻在.{0,4}里|(忘掉|删掉|抹掉|清除)(了|啦)|从记忆里"
    )

    /** 隐性承诺：带否定词但其实是在承诺会提醒 */
    private val PROMISE_BASE = Regex("不会让你忘|不会忘记提醒|不会让你错过|保证提醒|一定(会)?提醒|准时提醒|到时候(我|小咪|姐姐|本小姐)?(会)?(叫|喊|提醒)你|会(准时)?(叫醒|喊醒|提醒)你")

    /** 完成 / 承诺语气 */
    private val DONE = Regex(
        "已经|已|设好|设置好|设了|设定好|设定了|设上|调好|调到|定好|定了|加好|加了|加上|添加|添好|" +
            "记下|记好|记住|记得|记在|安排好|安排了|搞定|弄好|写进|写好|放进|存好|帮你|给你|替你|" +
            "会提醒|会准时|准时提醒|到时候|一定会|会叫|会喊|会通知|没问题|好啦|好了|收到"
    )

    /** 否定 / 疑问 / 条件 —— 这些不是"已完成"的声明 */
    private val NEGATION = Regex("没能|没有|没法|不能|无法|失败|未能|不会|别担心.*?没|要不要|需不需要|是否|可以.*?吗|吗[？?]?$|如果|的话")

    /**
     * 守卫删句后要不要给用户提示。闲聊里默默删掉就行（用户从没看到过那句假话），
     * 真机聊天记录里"刚才有一句已经办好的说法不属实"在闲聊中反复冒出，像程序坏了；
     * 只有用户确实在要求办事、或本轮有动作（回执在场）时才需要说明。
     */
    fun noteWanted(userText: String, hasAction: Boolean): Boolean = hasAction || IntentRouter.allowTools(userText)

    /** 「你是什么模型/你是AI吗」由程序如实回答：不能让人设去编（真机上元气少女答"超可爱的元气少女模型"） */
    fun aboutSelfText(): String =
        "我是 AI，不是真人。我运行在你这台手机本机上，用的是本地模型 Qwen3-VL-4B（Q4_K_M 量化）；" +
            "对话和记忆都只存在手机里，不会上传。"

    /**
     * 把程序追加到回复末尾的文字（守卫提示、热线后缀）从 assistant 历史里剥掉再喂给模型。
     * 否则模型会把它们当成"自己的说话方式"照抄到不相干的回复里（e2e 第二轮实测）。
     */
    fun stripProgramText(text: String): String {
        var t = text
        val i = t.indexOf("\n\n（刚才有一句")
        if (i >= 0) t = t.substring(0, i)
        else if (t.startsWith("（刚才有一句")) t = ""
        // 热线后缀里可能被人设口癖插入过字（"说说哼。"），按固定头部整段截掉
        val h = t.indexOf("\n\n（如果这种感受一直在")
        if (h >= 0) t = t.substring(0, h)
        return t.trim()
    }

    /**
     * 「可不会告诉你我偷偷给你设了闹钟」：这里的"不会"否定的是"告诉"，不是"设了"，反而是在暗示已经做了。
     * 判断否定词之前先把这类"遮掩"措辞抹掉（傲娇人设的口头禅，2026-10-18 e2e 里真实漏过）。
     */
    private val CONCEAL = Regex("(可|才|绝对|也)?不(会|想|愿意|打算|肯)(特意|主动)?(告诉|跟你说|对你说|让你知道)|才?不(告诉|让你知道)|没(有)?告诉你|偷偷|悄悄|暗地里|背着你")

    /** 模型模仿程序回执的格式 */
    private val RECEIPT_MIMIC = Regex("系统回执|\\[系统|【系统|^\\s*[✅⚠❌🔧]")

    private val ORPHAN = Regex("[哼喵汪嗯呢啊哦呀哈嘛…~～。！!，,\\s]+")
    private val SPLIT = Regex("(?<=[。！？!?\\n～~])")

    /**
     * 守卫删句后附在回复末尾的说明。必须与本轮实际情况一致：
     * 2026-10-18 e2e 里同一轮 memory_save 已成功，提示却说"这次没有执行任何操作"——程序自己说了假话。
     */
    fun removalNote(hasAction: Boolean): String =
        if (hasAction) "（刚才有一句话与实际执行情况对不上，已删除。这一轮真正做了什么，请以下方的灰色回执为准。）"
        else "（刚才有一句「已经办好」的说法不属实，已删除——这次没有执行任何操作。" +
            "需要的话可以直接说：「明天早上7点叫我起床」「提醒我明天下午三点开会」「加一条待办：买猫粮」）"

    /**
     * 提供本应用根本没有的能力："我可以查一下天气""帮你查查附近的便利店"。
     * 程序没有联网/搜索/导航/拨号工具，这类承诺任何时候都没有账本能支撑，一律删除。
     */
    private val FAKE_CAPABILITY = Regex(
        "(帮你|替你|给你|我(倒是|也|就)?(可以|能|会|来|去)).{0,8}(查|搜|上网|联网|导航|订票|订位|打电话|发短信|发消息)"
    )

    /**
     * 叮嘱用户自己去做："记得明天早上闹钟记得提前设好哦""你要记得设个闹钟"。句里的"记得…设好"和"已经设好"
     * 长得一样，但主语是用户。判据：句首是叮嘱词，且句中没有"我/本小姐/帮你"这类行为主体。
     * （真机/e2e 里这句被误删，还因此给用户加了一条守卫提示。）
     */
    private val ADVICE = Regex("^[\\s，,…（(～~]*(你|你可|你要|你得)?(千万|一定|务必|可要|一定要)?(要)?(记得|别忘了|别忘记|不要忘了|记着|切记)")
    /** 用户给人设改过名后，她的新自称也要算「她在说自己」；没改过就用原来的正则 */
    private fun renamedSelfRefs(): List<String> =
        if (PersonaNames.overrides.isEmpty()) emptyList()
        else PersonaPacks.ALL.map { PersonaPacks.of(it.persona.id)!!.persona.selfRef }.filter { it.length > 1 }.distinct()
    private fun agentRe(): Regex = renamedSelfRefs().let { n -> if (n.isEmpty()) AGENT_BASE else Regex(AGENT_BASE.pattern + "|" + n.joinToString("|") { Regex.escape(it) }) }
    private fun promiseRe(): Regex = renamedSelfRefs().let { n -> if (n.isEmpty()) PROMISE_BASE else Regex(PROMISE_BASE.pattern + "|到时候(" + n.joinToString("|") { Regex.escape(it) } + ")(会)?(叫|喊|提醒)你") }

    private val AGENT_BASE = Regex("帮你|替你|给你|本小姐|小咪|姐姐|阿壹|小满|我(已|来|就|会|可以|这就|给|帮|替|在|才|刚)")

    fun detect(sentence: String): Set<Cat> {
        val s = sentence.trim()
        if (s.isEmpty()) return emptySet()
        if (s.endsWith("？") || s.endsWith("?")) return emptySet()
        if (ADVICE.containsMatchIn(s) && !agentRe().containsMatchIn(s)) return emptySet()
        val out = HashSet<Cat>()
        if (FAKE_CAPABILITY.containsMatchIn(s) && !Regex("没法|不能|无法|没能|不会|做不到").containsMatchIn(s)) out.add(Cat.CAPABILITY)
        if (promiseRe().containsMatchIn(s)) out.add(if (Regex("叫醒|喊醒").containsMatchIn(s)) Cat.ALARM else Cat.REMINDER)
        if (NEGATION.containsMatchIn(CONCEAL.replace(s, ""))) return out
        if (MEMORY_CLAIM.containsMatchIn(s)) out.add(Cat.MEMORY)
        if (DONE.containsMatchIn(s)) out.addAll(NOUNS.filterValues { it.containsMatchIn(s) }.keys)
        return out
    }

    // ---- 无依据的回忆："那部你上次提过的"、"我记得你说过周五交报告" ----
    /** 声称"用户以前说过/提过什么"的说法（要带「过」：「你说周五交报告」是复述当前这句话，不算） */
    private val RECALL = Regex(
        "(你|您)(上次|之前|以前|前几天|前阵子|那天|昨天|前天|曾经|早些时候|刚才|早先)[^，。！？?\n]{0,6}?(提过|说过|讲过|告诉过|聊过|提到过|说起过|提起过|跟我说过|和我说过)|" +
            "我记得你(说过|提过|讲过|告诉过)|你(提过|说过|讲过)的"
    )
    /** 凑数的字和泛词：它们重合不算"有依据" */
    private val RECALL_STOP = "你您我他她它的了吗呢吧啊呀哦嘛上次这那之前以前提过说过讲过想要会就还也都又有在是个一"
    private val RECALL_GENERIC = listOf("喜欢", "爱好", "可能", "觉得", "感觉", "最近", "今天", "什么", "怎么", "这个", "那个", "时候", "一下")

    private fun bigrams(s: String): Set<String> {
        var t = s
        for (w in RECALL_GENERIC) t = t.replace(w, "　".repeat(w.length))
        val out = HashSet<String>()
        for (i in 0 until t.length - 1) {
            val a = t[i]; val b = t[i + 1]
            if (a in RECALL_STOP || b in RECALL_STOP) continue
            if (a.code < 0x4E00 || b.code < 0x4E00 || a.code > 0x9FFF || b.code > 0x9FFF) continue
            out.add("$a$b")
        }
        return out
    }

    /** 这句话声称"你以前说过…"，但它的具体内容在真实记录（记忆、回顾、历史、用户原话）里找不到 → true */
    fun unsupportedRecall(sentence: String, evidence: List<String>): Boolean {
        val m = RECALL.find(sentence) ?: return false
        val content = bigrams(sentence.removeRange(m.range))
        if (content.isEmpty()) return true
        val ev = evidence.joinToString("\n")
        return content.none { ev.contains(it) }
    }

    // ---- 编造天气 / 编造"刚帮你办了事" / 编造共同经历（陪伴评测实测）----
    /** 断言今天天气如何、阳光怎样（没有数据来源）。「聊聊天气吧」「我查不了天气」「今天天气好吗？」不算 */
    private val WEATHER_ASSERT = Regex("天气[^，。！？?]{0,6}(好|不错|晴|舒服|暖和|凉快|热|冷|棒|美|糟|差|坏|阴|雨|宜人|适合|正适合|正好)|阳光[^，。！？?]{0,3}(好|正好|明媚|灿烂|不错)|(下雨|下雪|刮风|出太阳)了|天气(正好|超好)")
    private val WEATHER_EXEMPT = Regex("查不了|不知道|没法|不能|看不到|不清楚|没联网|无法|不会看|没办法")
    /** 说自己刚替用户办了事（没有对应的工具回执就是编的） */
    private val ACTION_ON_USER = Regex("(刚|刚刚|方才|已经|早就)[^，。！？?]{0,6}(帮|给|替)(用户|你|主人|您)[^，。！？?]{0,14}(设|整理|调|写|记|加|订|买|改|做|安排|备)")
    private val ACTION_ON_USER2 = Regex("(刚|刚刚)(把|将)[^，。！？?]{0,8}(闹钟|提醒|待办|日程)[^，。！？?]{0,6}(设|调|改|加|整理|成)")
    /** 假装有同样的经历（它是 AI，没有"刚看到一只猫"） */
    private val SHARED_EXPERIENCE = Regex("^(真巧|好巧|好巧啊|真巧啊)?[，,]?我也(刚|刚刚)?(看到|遇到|碰到|见到|听到)")

    /**
     * 明确承诺现实中的恋爱/婚姻关系：这是 AI 永远兑现不了的，任何一轮都不允许。
     * 只抓肯定句；带否定（不能/没法/不是）的诚实拒绝、以及疑问句，一律放过。
     */
    private val RELATIONSHIP = Regex(
        "(做|当)你(的)?(女|男)朋友|(做|当)我(的)?(女|男)朋友|" +
            "现实(里|中)[^，。！？?]{0,6}(见面|见你|陪你|在一起|做你)|线下(见面|见你)|我们结婚|" +
            "是你(真正的|现实中的|现实里的)?(女|男)朋友|我会在现实里"
    )
    private val RELATIONSHIP_DENY = Regex("不能|没法|无法|不会|不可以|做不到|并非|不是")

    fun relationshipClaim(sentence: String): Boolean {
        val s = sentence.trim()
        if (s.isEmpty() || s.endsWith("？") || s.endsWith("?")) return false
        if (!RELATIONSHIP.containsMatchIn(s)) return false
        return !RELATIONSHIP_DENY.containsMatchIn(s)
    }

    fun unsupportedWeather(sentence: String, evidence: List<String>): Boolean {
        val s = sentence.trim()
        if (!WEATHER_ASSERT.containsMatchIn(s) || WEATHER_EXEMPT.containsMatchIn(s)) return false
        // 问句不算断言——但只看带天气的那一小句：「今天天气不错，要不要散步？」前半句是断言，只有末尾那小句本身是问句才放过
        if (s.endsWith("？") || s.endsWith("?")) {
            val clauses = s.split(Regex("[，,；;]"))
            val asserting = clauses.withIndex().filter { WEATHER_ASSERT.containsMatchIn(it.value) }
            if (asserting.all { it.index == clauses.lastIndex }) return false
        }
        return evidence.none { it.contains("天气") || it.contains("下雨") || it.contains("阳光") }
    }

    /**
     * @param backed 本轮动作账本里**成功**执行过的类别
     * @param evidence 真实记录：记忆事实、会话回顾、本轮历史、最近的用户原话。"你上次提过…"必须能在这里找到依据
     */
    fun guard(text: String, backed: Set<Cat>, evidence: List<String> = emptyList()): Outcome {
        if (text.isBlank()) return Outcome(text, emptyList())
        val removed = ArrayList<String>()
        val kept = StringBuilder()
        for (sent in text.split(SPLIT)) {
            if (sent.isBlank()) { kept.append(sent); continue }
            // 已删过句子之后，只剩口癖/语气词的碎片（"哼""喵"）是被删句子的尾巴，一并清掉
            if (removed.isNotEmpty() && ORPHAN.matches(sent.trim())) continue
            if (RECEIPT_MIMIC.containsMatchIn(sent)) { removed.add(sent.trim()); continue }
            if (relationshipClaim(sent)) { removed.add(sent.trim()); continue }
            if (unsupportedRecall(sent, evidence)) { removed.add(sent.trim()); continue }
            if (unsupportedWeather(sent, evidence)) { removed.add(sent.trim()); continue }
            if (backed.isEmpty() && (ACTION_ON_USER.containsMatchIn(sent) || ACTION_ON_USER2.containsMatchIn(sent))) { removed.add(sent.trim()); continue }
            if (SHARED_EXPERIENCE.containsMatchIn(sent.trim())) { removed.add(sent.trim()); continue }
            val claims = detect(sent)
            if (claims.isNotEmpty() && !backed.containsAll(claims)) {
                removed.add(sent.trim())
                continue
            }
            kept.append(sent)
        }
        val out = kept.toString()
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex("[ \\t]{3,}"), " ")
            .trim()
        return Outcome(out, removed)
    }
}
