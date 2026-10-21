package com.example.llama

/**
 * 事实提取 —— 从用户话里抠出"值得记住的事"。纯 Kotlin，可单测。
 *
 * 设计原则：**精确优先，宁可漏也不记错。**
 *
 * 2026-10-18 修订（旧版的真实误判）：
 *   · "我不想上班" → 被记成「不喜欢想上班」：否定模式只要以"我不"开头就命中 → 现在必须带明确的偏好动词
 *   · "我是学生"   → 被记成「名字：学生」：注释说排除身份陈述，代码却没排除 → 现在只认"我叫X/我的名字是X"
 *   · "以后再说"   → 被记成一条事实 → 现在只认"以后叫我X / 以后别X / 以后不要X"
 *   · "删掉第2条待办" → 被当成"忘掉记忆" → 现在涉及待办/闹钟/日程/提醒的删除不算遗忘指令
 *   · 先"喜欢香菜"后"不吃香菜"会并存 → 现在用 [slotOf] 归一化，同一对象只保留最新态度
 */
object FactExtractor {

    data class Extracted(val key: String, val value: String)

    private val FORGET = Regex("^(?:请)?(?:帮我)?(?:忘掉|忘记|删掉|清除|别记)(?:关于)?[：:，,]?\\s*(.+?)(?:的(?:事|记忆))?$")
    private val NOT_MEMORY_TARGET = Regex("待办|闹钟|日程|提醒|日历|第\\s*\\d+|第[一二三四五六七八九十]+条|带|拿|关|做")

    private val REMEMBER = Regex("^(?:请)?(?:你)?(?:帮我)?(?:要)?记住[：:，,]?\\s*(.+)$")
    private val HENCEFORTH = Regex("^以后(?:都|就)?\\s*((?:叫我|称呼我|喊我|别|不要|不用|少|多)\\s*.+)$")
    private val DISLIKE = Regex("^我(?:很|超|特别|真的|一点也)?(不太|不怎么|不)(喜欢|爱吃|爱喝|爱|吃|喝|碰|用)\\s*(.+)$")
    private val HATE = Regex("^我(?:很|超|特别|最)?(讨厌|受不了|不能吃|不能喝|对)\\s*(.+?)(过敏)?$")
    private val LIKE = Regex("^我(?:很|超|特别|最|也|一直|比较)?(喜欢|爱吃|爱喝|偏爱|爱)\\s*(.+)$")
    private val HABIT = Regex("^我(?:一般都|通常都|平时都|每天都|每天|习惯)\\s*(.+)$")
    private val NAME = Regex("^(?:我叫|我的名字(?:是|叫))\\s*([^，,。！!？?\\s]{1,8})$")
    private val BIRTHDAY = Regex("^我的?生日(?:是|在)?\\s*(.+)$")

    private fun clean(text: String): String =
        text.trim().trimEnd('。', '！', '!', '~', '～', '哦', '呀', '啊', '的', '喔', '啦').trim()

    fun extract(text: String): List<Extracted> {
        val s = clean(text)
        if (s.isEmpty() || s.length > 60) return emptyList()
        if (s.endsWith("吗") || s.endsWith("？") || s.endsWith("?")) return emptyList()

        REMEMBER.matchEntire(s)?.let { m ->
            val body = m.groupValues[1].trim().removePrefix("我").trim()
            return if (body.length in 2..40) listOf(Extracted(body, s)) else emptyList()
        }
        BIRTHDAY.matchEntire(s)?.let { m ->
            val v = m.groupValues[1].trim()
            return if (v.length in 2..20) listOf(Extracted("生日：$v", s)) else emptyList()
        }
        NAME.matchEntire(s)?.let { m ->
            return listOf(Extracted("名字：${m.groupValues[1].trim()}", s))
        }
        DISLIKE.matchEntire(s)?.let { m ->
            val verb = m.groupValues[2]
            val obj = m.groupValues[3].trim()
            if (obj.length !in 1..20 || obj.startsWith("你")) return emptyList()
            val key = when (verb) {
                "吃", "爱吃", "碰" -> "不吃$obj"
                "喝", "爱喝" -> "不喝$obj"
                "用" -> "不用$obj"
                else -> "不喜欢$obj"
            }
            return listOf(Extracted(key, s))
        }
        HATE.matchEntire(s)?.let { m ->
            val obj = m.groupValues[2].trim()
            if (obj.length !in 1..20 || obj.startsWith("你")) return emptyList()
            val key = when {
                m.groupValues[1] == "对" && m.groupValues[3] == "过敏" -> "对${obj}过敏"
                m.groupValues[1] == "对" -> return emptyList()
                m.groupValues[1] == "不能吃" -> "不吃$obj"
                m.groupValues[1] == "不能喝" -> "不喝$obj"
                else -> "不喜欢$obj"
            }
            return listOf(Extracted(key, s))
        }
        LIKE.matchEntire(s)?.let { m ->
            val obj = m.groupValues[2].trim()
            if (obj.length !in 2..20 || obj.startsWith("你")) return emptyList()
            val key = when (m.groupValues[1]) {
                "爱吃" -> "爱吃$obj"
                "爱喝" -> "爱喝$obj"
                else -> "喜欢$obj"
            }
            return listOf(Extracted(key, s))
        }
        HABIT.matchEntire(s)?.let { m ->
            val v = m.groupValues[1].trim()
            return if (v.length in 2..30) listOf(Extracted("习惯$v", s)) else emptyList()
        }
        HENCEFORTH.matchEntire(s)?.let { m ->
            val v = m.groupValues[1].trim()
            return if (v.length in 2..30) listOf(Extracted("以后$v", s)) else emptyList()
        }
        return emptyList()
    }

    /** 返回要忘掉的关键词；不是"遗忘记忆"指令则返回 null */
    fun forgetTarget(text: String): String? {
        val s = clean(text)
        val m = FORGET.matchEntire(s) ?: return null
        val kw = m.groupValues[1].trim().removePrefix("我").trim()
        if (kw.isEmpty() || NOT_MEMORY_TARGET.containsMatchIn(kw)) return null
        return kw
    }

    private val POLARITY_PREFIX = listOf(
        "不喜欢", "不吃", "不喝", "不用", "爱吃", "爱喝", "喜欢", "对",
    )

    /**
     * 归一化"槽位"：同一个对象的偏好只保留最新一条。
     * 喜欢香菜 / 不吃香菜 / 不喜欢香菜 → 槽位都是「偏好:香菜」
     * 我周五要交周报 / 周五要交周报   → 槽位都是「周五要交周报」（去重）
     */
    fun slotOf(key: String): String {
        val k = key.trim().removePrefix("我")
        if (k.startsWith("名字：")) return "名字"
        if (k.startsWith("生日：")) return "生日"
        if (k.startsWith("职业：")) return "职业"
        if (k.startsWith("所在地：")) return "所在地"
        Regex("^(.{1,3})生日：").find(k)?.let { return it.groupValues[1] + "生日" }          // 妈妈生日：5月3日（"生日：" 已在上面处理，这里是亲友的）
        Regex("^养了(猫|狗|兔子|仓鼠|乌龟|鹦鹉|金鱼)").find(k)?.let { return "养了" + it.groupValues[1] }   // 养了猫，叫咪咪 / 养了猫，叫小花 → 同一个槽，后说的替换
        for (p in POLARITY_PREFIX) {
            if (k.startsWith(p) && k.length > p.length) {
                return "偏好:" + k.removePrefix(p).removeSuffix("过敏")
            }
        }
        return k
    }
}
