package com.example.llama

/**
 * 身份信息抽取：从一段话里抠出兴趣、职业、宠物、人际、所在城市、在学什么。纯 Kotlin，可单测。
 *
 * 原则（沿用 [FactExtractor]）：**宁可漏，不可错**。
 *   · 只认明确的句式；疑问句一律不抽；否定（"不喜欢…"）交给 FactExtractor。
 *   · 每个候选的关键词都逐字来自原话（程序抠出来的，不是模型改写的）。
 *   · 敏感内容（健康、收入债务、证件电话、门牌、宗教、性取向）整句丢弃，连同句子里的无害部分也不记。
 * 抽出来的只是"候选"，要说两次或用户点"记住"才会转正（见 [Candidates]）。
 */
object ProfileExtractor {

    data class Candidate(val category: String, val text: String, val evidence: String)

    private val SENSITIVE = Regex(
        "病|癌|症|药|手术|怀孕|抑郁|焦虑|自杀|心理|住院|" +
            "欠|债|贷|工资|月薪|年薪|收入|存款|股票|基金|信用卡|银行卡|密码|身份证|护照|驾照|" +
            "佛教|基督|伊斯兰|穆斯林|信教|信佛|宗教|同性|性取向|出轨|离婚|" +
            "小区|栋|单元|楼|路\\d|街\\d|号楼|\\d{7,}"
    )

    fun isSensitive(text: String): Boolean = SENSITIVE.containsMatchIn(text)

    private val CITY = "北京|上海|广州|深圳|杭州|成都|重庆|武汉|西安|南京|天津|苏州|长沙|郑州|青岛|厦门|合肥|宁波|福州|济南|昆明|大连|沈阳|哈尔滨|无锡|佛山|东莞|珠海|南昌|贵阳|南宁|太原|石家庄|兰州|乌鲁木齐|海口|三亚|拉萨|呼和浩特|银川|西宁"
    private val JOBS = "程序员|学生|老师|教师|医生|护士|设计师|工程师|律师|会计|销售|产品经理|公务员|研究生|大学生|高中生|初中生|自由职业者|博主|摄影师|厨师|司机|运营|博士|硕士|程序媛|插画师|作家|演员|记者|教授"
    private val REL = "同事|同学|室友|闺蜜|朋友|老板|领导|妈妈|爸爸|妈|爸|老婆|老公|女朋友|男朋友|女儿|儿子|姐姐|哥哥|弟弟|妹妹"

    private val HOBBY = Regex("(?<![不没])(喜欢|爱好是|爱好|热爱|最爱|迷上了?|爱(?=[玩打看听吃喝]))([^，,。！!；;？?\\n]{2,16})")
    private val JOB = Regex("我是(?:一名|一个|一位|个)?($JOBS)(?=[，,。！!\\s的]|$)")
    private val PET = Regex("我(?:家里?)?(?:养了|养着|有|家有)(?:一只|一条|两只|两条|一个|只|条)?(小?猫咪?|小?狗狗?|兔子|仓鼠|乌龟|鹦鹉|金鱼)(?:[，,]\\s*)?(?:(?:名字叫|名叫|叫)\\s*([一-龥A-Za-z]{1,5})(?=[，,。！!\\s]|$))?")
    private val PERSON = Regex("我($REL)(?:名字)?(?:名叫|叫)([一-龥]{2,4})(?=[，,。！!\\s]|$)")
    private val REL_BIRTHDAY = Regex("我(妈妈|妈|爸爸|爸|老婆|老公|女朋友|男朋友|女儿|儿子)的?生日(?:是|在)?\\s*(\\d{1,2})月(\\d{1,2})[日号]?")
    private val PLACE_LIVE = Regex("我(?:现在|目前|一直)?(?:住在|住|家在|定居在|定居)($CITY)")
    private val PLACE_WORK = Regex("我(?:现在|目前)?在($CITY)(?:市)?的?(?:上班|工作|读书|上学|生活)")
    private val SKILL = Regex("我(?:最近|现在|正在)?(?:在|开始)(?:学习|学|练习|练)(?!校|习|生)([一-龥A-Za-z0-9]{2,10})")

    private val BAD_OBJ = Regex("^(你|这个|那个|什么|啥|谁|哪)|什么|啥|谁|哪")
    private val BAD_NAME_START = Regex("^[我你他她去来做帮给一别不]")

    private fun norm(rel: String) = when (rel) { "妈" -> "妈妈"; "爸" -> "爸爸"; else -> rel }
    private fun animal(a: String) = when (a) { "小猫", "猫咪", "小猫咪" -> "猫"; "小狗", "狗狗", "小狗狗" -> "狗"; else -> a }

    fun extract(text: String): List<Candidate> {
        val s = text.trim()
        if (s.isEmpty() || s.length > 120) return emptyList()
        if (s.contains("吗") || s.contains("？") || s.contains("?")) return emptyList()
        if (isSensitive(s)) return emptyList()
        val out = ArrayList<Candidate>()
        fun add(cat: String, t: String) { if (out.none { it.text == t }) out.add(Candidate(cat, t, s)) }

        for (m in HOBBY.findAll(s)) {
            val raw = m.groupValues[2].trim().trimEnd('的', '啦', '呀', '哦', '吧', '啊')
            for (obj in raw.split(Regex("和|、|跟|及|与|还有|以及"))) {
                val o = obj.trim()
                if (o.length in 2..10 && !BAD_OBJ.containsMatchIn(o)) add("兴趣", "喜欢$o")
            }
        }
        JOB.find(s)?.let { add("职业", "职业：${it.groupValues[1]}") }
        PET.find(s)?.let { m ->
            val a = animal(m.groupValues[1]); val n = m.groupValues[2]
            add("宠物", "养了$a" + if (n.isNotBlank()) "，叫$n" else "")
        }
        PERSON.find(s)?.let { m ->
            val name = m.groupValues[2]
            if (!BAD_NAME_START.containsMatchIn(name)) add("人物", "${norm(m.groupValues[1])}叫$name")
        }
        REL_BIRTHDAY.find(s)?.let { add("人物", "${norm(it.groupValues[1])}生日：${it.groupValues[2]}月${it.groupValues[3]}日") }
        (PLACE_LIVE.find(s) ?: PLACE_WORK.find(s))?.let { add("所在地", "所在地：${it.groupValues[1]}") }
        SKILL.find(s)?.let { add("在学", "在学${it.groupValues[1]}") }
        return out
    }
}
