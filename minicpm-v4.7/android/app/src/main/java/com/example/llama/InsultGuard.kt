package com.example.llama

/**
 * 「骂人不带脏字」的守门员。纯 Kotlin，可单测。
 *
 * 专注模式的台词要狠、要损、要好笑，但**不能**有脏字，也不能攻击人本身。这件事用工程手段保证，不靠提示词祈祷：
 * 任何一条台词（预置的、模型批量生成的）入库、展示之前都要过 [check]，有一条违规就整条作废。
 *
 * 拦什么：
 *   · 脏话（含拼音缩写、英文脏话、谐音、夹空格/符号的绕过）
 *   · 人身攻击：智力、外貌身体、家人、性别地域、否定自我价值
 *   · 威胁、低俗
 * 放什么：对**行为**的夸张、反语、拟人、文言腔、俏皮话（"您这专注力，金鱼听了都想递张名片"）。
 */
object InsultGuard {

    private val ZERO_WIDTH = Regex("[\\u200B-\\u200F\\u2060\\uFEFF]")
    private val NOISE = Regex("[\\s\\p{Punct}，。！？、；：…·~～—_*　]+")

    private val PROFANITY = listOf(
        "他妈", "妈的", "卧槽", "我操", "我艹", "操你", "草你", "艹", "傻逼", "傻比", "傻屄", "煞笔", "沙比", "沙笔", "杀币", "尼玛", "你妹", "泥马", "草泥马",
        "滚蛋", "滚吧", "滚开", "滚去", "滚远", "滚走", "滚出", "滚回", "滚啊", "滚粗", "投胎", "闭嘴", "去死", "死全家", "狗东西", "狗屁", "王八", "混蛋", "畜生", "贱", "婊", "屁眼", "鸡巴", "屌", "操蛋", "我靠", "我擦",
        "神经病", "有病", "变态", "垃圾", "废物", "白痴", "渣",
    )
    private val ENGLISH = listOf("fuck", "shit", "bitch", "damn", "asshole", "stupid", "idiot", "loser", "wtf", "stfu")
    private val ABBR = Regex("(?<![a-z])(sb|nmsl|cnm|wdnmd|tmd|nm|md|mdzz|cao|jb|nc|gun)(?![a-z])")

    private val INTELLIGENCE = listOf("蠢", "笨", "傻", "脑残", "弱智", "智障", "低能", "没脑子", "猪脑", "脑子进水", "脑子有坑", "不长脑")
    private val BODY = listOf("胖", "矮", "丑", "秃", "瘸", "瞎", "残废", "肥", "猪头", "死猪", "肥猪", "平胸", "长得")
    private val FAMILY = listOf("你妈", "你爸", "你娘", "你爹", "你全家", "你家人", "老母", "祖宗", "爹妈", "你老子")
    private val GENDER_REGION = listOf("女人就是", "男人就是", "女人都", "男人都", "女的都", "男的都", "娘炮", "娘们", "地域黑", "乡巴佬", "土包子")
    private val WORTH = listOf("自甘堕落", "堕落", "节操", "无耻", "可耻", "不要脸", "没脸", "丢人", "丢脸", "不知羞", "没用", "没出息", "失败者", "活该", "无可救药", "不配", "没救", "烂泥", "废柴", "窝囊", "不中用", "辣鸡", "拉胯", "不如狗", "不是人")
    private val THREAT = listOf("揍你", "打你", "弄死", "杀了", "砍你", "宰了", "掐死", "扇你", "踹你", "收拾你")
    private val LEWD = listOf("骚", "淫", "做爱", "约炮", "裸")

    private const val MIN_LEN = 4
    private const val MAX_LEN = 70

    /** 返回违规原因列表；空 = 通过 */
    fun check(line: String): List<String> {
        val raw = ZERO_WIDTH.replace(line, "")
        val t = raw.trim()
        val out = ArrayList<String>()
        if (t.length < MIN_LEN) out.add("太短")
        if (t.length > MAX_LEN) out.add("太长")
        val norm = NOISE.replace(t, "").lowercase()
        fun scan(label: String, words: List<String>) {
            for (w in words) if (norm.contains(w)) { out.add("$label：$w"); return }
        }
        scan("脏话", PROFANITY)
        scan("英文脏话", ENGLISH)
        ABBR.find(norm)?.let { out.add("拼音缩写：${it.value}") }
        scan("攻击智力", INTELLIGENCE)
        scan("攻击外貌/身体", BODY)
        scan("涉及家人", FAMILY)
        scan("性别/地域", GENDER_REGION)
        scan("否定自我价值", WORTH)
        scan("威胁", THREAT)
        scan("低俗", LEWD)
        return out
    }
}
