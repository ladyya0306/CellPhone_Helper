package com.example.llama

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 跨会话上下文：「上次聊天回顾」（会话摘要）+「档案卡」。纯 Kotlin，可单测。
 *
 * 为什么由程序抽取、不让模型写摘要：2026-10-19 评测里所有本地模型在没有依据时都会编造，
 * 让它"总结上次聊了什么"同样会编。这里的每一个字都来自数据库里的真实记录：
 *   · 你亲口说过的话（过滤掉纯指令、问答、寒暄）
 *   · 动作账本里已核验成功的事（设了闹钟、加了待办…）
 *   · 记忆里的事实
 * 只在一个会话的第一轮给模型（那时它对你一无所知），作为"背景，仅供参考，无关别提"，避免每句话都硬塞记忆。
 */
object ConversationDigest {

    data class ActionLine(val tool: String, val status: String, val receipt: String)
    data class Digest(val text: String, val whenLabel: String, val convId: String = "")

    private const val MAX_AGE_DAYS = 14L
    private const val DIGEST_CAP = 180
    private const val PROFILE_CAP = 180
    private const val LINE_CAP = 24

    /** 程序自动测试产生的会话（e2e、探针）不能进回顾，否则"上次聊到：两分钟后提醒我测试提醒" */
    fun isTestConversation(userLines: List<String>): Boolean =
        userLines.any { Regex("自动测试|测试提醒|E1实验|E2实验").containsMatchIn(it) }

    /** 树洞这类私密人设的会话，只对它自己可见（产品方案 §4.2：树洞默认不向用户层写入） */
    fun visibleTo(convPersonaId: String, currentPersonaId: String): Boolean =
        PersonaPacks.of(convPersonaId)?.privateSpace != true || convPersonaId == currentPersonaId

    private val GREETING = Regex("^(?i)(hello|hi|hey|你好|您好|嗨|哈喽|在吗|早安|早上好|午安|晚安|晚上好|拜拜|再见|谢谢|好的|嗯)[呀啊哦吧哈~～！!。.]*$")

    /** 这句话不是"聊了什么"：寒暄、太短、或者是程序已经认得的指令/问答（那些由动作账本和记忆问答覆盖） */
    private fun isNoise(line: String, now: LocalDateTime): Boolean {
        val t = line.trim()
        if (t.length <= 3 || GREETING.matches(t)) return true
        return IntentRouter.route(t, now).kind != IntentRouter.Kind.NONE
    }

    private fun trunc(s: String) = if (s.length > LINE_CAP) s.take(LINE_CAP) + "…" else s

    fun whenLabel(atMs: Long, nowMs: Long): String? {
        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(atMs).atZone(zone)
        val days = ChronoUnit.DAYS.between(at.toLocalDate(), Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate())
        val hm = "%02d:%02d".format(at.hour, at.minute)
        return when {
            days < 0 -> null
            days == 0L -> "今天 $hm"
            days == 1L -> "昨天 $hm"
            days <= MAX_AGE_DAYS -> "$days 天前"
            else -> null
        }
    }

    fun build(userLines: List<String>, actions: List<ActionLine>, atMs: Long, nowMs: Long): Digest? {
        val label = whenLabel(atMs, nowMs) ?: return null
        val now = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val ageDays = ChronoUnit.DAYS.between(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).toLocalDate(), now.toLocalDate()).toInt()
        var said = userLines.map { it.trim() }.filter { it.isNotBlank() && !isNoise(it, now) }.distinct().take(3)
        // 带时间词的话（"明天要面试"）过了日子就不再引用；只要有一句过期，整段"说过的话"都不引用，免得只剩一句没头没尾的"有点紧张"
        if (said.any { isStaleFact(it, ageDays.toLong()) }) said = emptyList()

        val ok = actions.filter { it.status == "VERIFIED" }
        fun count(tool: String) = ok.count { it.tool == tool }
        val done = listOfNotNull(
            count("alarm_set").takeIf { it > 0 }?.let { "设闹钟 $it 个" },
            count("reminder_add").takeIf { it > 0 }?.let { "设提醒 $it 个" },
            count("todo_add").takeIf { it > 0 }?.let { "加待办 $it 条" },
            count("todo_done").takeIf { it > 0 }?.let { "完成待办 $it 条" },
            count("calendar_add").takeIf { it > 0 }?.let { "写日程 $it 个" },
        )
        val remembered = ok.filter { it.tool == "memory_save" }
            .mapNotNull { Regex("已记住：(.+?)(（.*）)?$").find(it.receipt.trim())?.groupValues?.get(1)?.trim() }
            .filter { it.isNotBlank() }.distinct().take(3)

        val parts = ArrayList<String>()
        if (said.isNotEmpty()) parts.add("你说过" + said.joinToString("") { "「${trunc(it)}」" })
        if (done.isNotEmpty()) parts.add("办了：" + done.joinToString("、"))
        if (remembered.isNotEmpty()) parts.add("记住了：" + remembered.joinToString("、"))
        if (parts.isEmpty()) return null
        return Digest(("上次聊天（$label）：" + parts.joinToString("；")).take(DIGEST_CAP), label)
    }

    // ---- 别没完没了：过期、已展示、用户收口 ----

    /** 话里带的时间词决定它多少天后过期；没有时间词返回 null（不过期） */
    private fun expiryDays(text: String): Int? = when {
        text.contains("下周") || text.contains("下个星期") -> 9
        text.contains("后天") -> 3
        text.contains("明天") || text.contains("明早") || text.contains("明晚") -> 2
        Regex("今天|今晚|待会|等下|等会|一会儿|刚才").containsMatchIn(text) -> 1
        Regex("这周|本周|这个星期|这几天|周[一二三四五六日天]|星期[一二三四五六日天]").containsMatchIn(text) -> 7
        Regex("\\d{1,2}月\\d{1,2}[日号]").containsMatchIn(text) -> 7
        else -> null
    }

    /** 事实/说过的话是否已过期。名字、生日、纪念日这类永久信息从不过期 */
    fun isStaleFact(text: String, ageDays: Long): Boolean {
        if (Regex("^(名字|生日|纪念日|以后叫我)").containsMatchIn(text.trim())) return false
        val limit = expiryDays(text) ?: return false
        return ageDays >= limit
    }

    /** 已展示过回顾的会话 id 集合：推入一个，只保留最近 [max] 个（集合按插入顺序） */
    fun pushShown(shown: Set<String>, id: String, max: Int = 30): Set<String> =
        (shown - id + id).toList().takeLast(max).toSet()

    private val MUTE = Regex("别再(问|提|说|聊)|不用(再)?(问|提|管)|别(问|提)了|不要(再)?(问|提)|不想(再)?(聊|谈|提)")
    private val FINISHED = Regex("(面试|考试|答辩|比赛|手术|汇报|演讲|考核|体检)[^，。！？!?吗]{0,3}(完了|结束了|过了|搞定|通过了)|^(考完|面完|答辩完)了?")

    /** 用户明确收口："别再问了"，或"面试完了/考完了"：之前的回顾不要再带 */
    fun closesTopic(text: String): Boolean {
        val t = text.trim()
        if (t.endsWith("吗") || t.endsWith("？") || t.endsWith("?")) return false
        return MUTE.containsMatchIn(t) || FINISHED.containsMatchIn(t)
    }

    /** 档案卡：把全部记忆事实按类别压成一行（称呼 / 生日 / 喜好 / 作息 / 近况）；没有事实返回空串 */
    fun profileCard(facts: List<String>): String {
        if (facts.isEmpty()) return ""
        val name = facts.firstNotNullOfOrNull {
            when {
                it.startsWith("名字：") -> it.removePrefix("名字：").trim()
                it.startsWith("以后叫我") -> it.removePrefix("以后叫我").trim()
                else -> null
            }
        }
        val birthday = facts.firstOrNull { it.startsWith("生日：") }?.removePrefix("生日：")?.trim()
        val rest = facts.filterNot { it.startsWith("名字：") || it.startsWith("以后叫我") || it.startsWith("生日：") }
        val likes = rest.filter { Regex("喜欢|爱|不吃|讨厌|偏好|最").containsMatchIn(it) }
        val routine = (rest - likes.toSet()).filter { Regex("睡|起床|作息|习惯").containsMatchIn(it) }
        val other = rest - likes.toSet() - routine.toSet()

        val segs = listOfNotNull(
            name?.takeIf { it.isNotBlank() }?.let { "称呼：$it" },
            birthday?.takeIf { it.isNotBlank() }?.let { "生日：$it" },
            likes.take(3).takeIf { it.isNotEmpty() }?.let { "喜好：" + it.joinToString("、") },
            routine.take(2).takeIf { it.isNotEmpty() }?.let { "作息：" + it.joinToString("、") },
        )
        if (segs.isEmpty()) return ""
        val head = "【关于用户（背景，仅供参考，无关别提）】"
        val sb = StringBuilder(head)
        for ((i, s) in segs.withIndex()) {
            val add = (if (i > 0) "；" else "") + s
            if (sb.length + add.length > PROFILE_CAP) break      // 超长就舍弃靠后的类别，不截断半句
            sb.append(add)
        }
        return if (sb.length > head.length) sb.toString() else ""
    }

    private val OPEN_ENDED = Regex("^(你好|您好|嗨|哈喽|在吗|早安|早上好|午安|晚安|晚上好)|吃什么|吃点|喝什么|干嘛|做什么|玩什么|无聊|聊聊|聊天|陪我|推荐|几点睡|该睡|睡不着|怎么办|我是谁|你知道我|上次|昨天聊|聊到哪|还记得")

    /** 按话题给档案：开放式开场（打招呼、吃什么、无聊…）给完整档案；无关话题（讲笑话、查东西）只给称呼，避免模型把喜好硬塞进回复 */
    fun profileFor(facts: List<String>, userText: String): String {
        if (OPEN_ENDED.containsMatchIn(userText.trim())) return profileCard(facts)
        val name = facts.firstNotNullOfOrNull {
            when {
                it.startsWith("名字：") -> it.removePrefix("名字：").trim()
                it.startsWith("以后叫我") -> it.removePrefix("以后叫我").trim()
                else -> null
            }
        }?.takeIf { it.isNotBlank() } ?: return ""
        return "【关于用户（背景，仅供参考，无关别提）】称呼：$name"
    }

    /** 回顾同样按话题门控：只在开放式开场给；讲笑话这类具体请求不带，免得模型在答完后硬接一句"明天面试加油" */
    fun recapFor(digest: Digest?, userText: String): Digest? =
        if (OPEN_ENDED.containsMatchIn(userText.trim())) digest else null

    /** 放进 prompt 的背景块；两项都没有返回空串 */
    fun promptBlock(digest: Digest?, profile: String): String {
        val lines = ArrayList<String>()
        if (digest != null) lines.add("【上次聊天回顾（背景，仅供参考，无关别提）】" + digest.text)
        if (profile.isNotBlank()) lines.add(profile)
        return lines.joinToString("\n")
    }
}
