package com.example.llama

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 一句话多事录入：「明早9点开会，下午3点牙医，周五交报告」→ 3 件事，**先列出来让用户确认，再写入**。纯 Kotlin，可单测。
 *
 * 第一版全部由程序完成（不开模型）：拆句 → 每段用 [TimeParser] 解析 → 没写日期的钟点继承上一件的日期 →
 * 过去的、没听出时间的、重复的一律不记。人物、地点保留在标题原文里，不改写、不拆字段（结构化抽取留给后续的模型层）。
 *
 * 为什么先确认：一句话里夹着多件事，任何一件解析错都会悄悄写进日历/待办；列出来让用户看一眼，错了能"去掉第2条"。
 */
object MultiEvent {

    enum class Type { CALENDAR, REMINDER, TODO, ALARM }

    data class Item(val type: Type, val title: String, val at: LocalDateTime, val hasClock: Boolean)
    data class Proposal(val items: List<Item>, val skipped: List<String>)

    private const val MAX_ITEMS = 8

    private val SPLIT = Regex("[，,；;。！!？?\\n]|(?<![0-9])[：:](?![0-9])|然后|接着|还有|另外|以及|再就是|顺便|对了")
    /** 只是"请求框架"的片段（帮我记一下 / 麻烦你），本身不是一件事 */
    private val FRAME_ONLY = Regex("^(请|麻烦|帮我|帮忙|给我|你好|您好|记一下|记下|记个事|记住|提醒我|提醒一下|别忘了|安排一下|安排|我要|我想|我有|待办|日程|行程)+(一下|下|吧|哦|啊)*$")
    /** TimeParser 不认"昨天"这类过去的说法（会当成将来），这里先拦住：讲过去的事不记 */
    private val PAST_MARK = Regex("昨天|昨晚|昨日|昨早|前天|前晚|上周|上个?星期|上个?礼拜|上个月|刚才|刚刚")
    private val ALARM_WORDS = Regex("闹钟|叫醒|喊醒|叫我起床|喊我起床|起床")

    fun propose(text: String, now: LocalDateTime): Proposal? {
        val parts = SPLIT.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null

        val items = ArrayList<Item>()
        val skipped = ArrayList<String>()
        var ctxDate: LocalDate? = null
        var carry = ""
        var ctxPast = false
        for (raw in parts) {
            val frag = carry + raw
            if (PAST_MARK.containsMatchIn(frag)) { ctxPast = true; carry = ""; continue }
            val parsed = TimeParser.parse(frag, now)
            val timed = parsed != null && (parsed.hasTime || parsed.hasDate || parsed.relative)
            val content = IntentRouter.extractContent(frag)
            if (timed && parsed != null && content.isBlank()) {
                // 只有时间没有事由（"明天下午2点，跟李总聊合同"被逗号拆开）：带到下一段一起解析；日期顺便记作上下文
                if (parsed.hasDate) ctxDate = parsed.dateTime.toLocalDate()
                carry = frag
                continue
            }
            carry = ""
            if (!timed || parsed == null) {
                if (!FRAME_ONLY.matches(raw) && raw.length >= 2) skipped.add(raw)
                continue
            }
            if (parsed.hasDate || parsed.relative) ctxPast = false
            if (ctxPast) continue                                  // 接在"昨天…"后面、没写自己日期的钟点，还是在讲过去
            var at = parsed.dateTime
            if (parsed.hasTime && !parsed.hasDate && !parsed.relative && ctxDate != null) at = ctxDate.atTime(at.toLocalTime())
            if (parsed.hasDate) ctxDate = at.toLocalDate()
            val type = when {
                parsed.relative -> Type.REMINDER
                parsed.hasTime && ALARM_WORDS.containsMatchIn(frag) -> Type.ALARM
                parsed.hasTime -> Type.CALENDAR
                else -> Type.TODO
            }
            // 已经过去的不记（多半是在讲过去的事）；待办只看日期，"今天要交"不算过去
            val past = if (type == Type.TODO) at.toLocalDate().isBefore(now.toLocalDate()) else at.isBefore(now)
            if (past) continue
            if (items.any { it.title == content && it.at == at }) continue
            items.add(Item(type, content, at, parsed.hasTime))
            if (items.size >= MAX_ITEMS) break
        }
        if (items.size < 2) return null
        return Proposal(items, skipped)
    }

    private fun typeLabel(t: Type) = when (t) { Type.CALENDAR -> "日程"; Type.REMINDER -> "提醒"; Type.TODO -> "待办"; Type.ALARM -> "闹钟" }

    private fun dateLabel(d: LocalDate, now: LocalDateTime): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), d)
        val wd = arrayOf("一", "二", "三", "四", "五", "六", "日")[d.dayOfWeek.value - 1]
        return when (days) { 0L -> "今天"; 1L -> "明天"; 2L -> "后天"; else -> "${d.monthValue}月${d.dayOfMonth}日（周$wd）" }
    }

    /** 给用户看的确认清单：每一行都来自解析结果，没有任何模型生成的字 */
    fun render(p: Proposal, now: LocalDateTime): String = buildString {
        append("我听到 ${p.items.size} 件事：\n")
        p.items.forEachIndexed { i, it ->
            val whenText = if (it.hasClock) TimeParser.describe(it.at, now) else dateLabel(it.at.toLocalDate(), now)
            append("${i + 1}. ${typeLabel(it.type)} $whenText ${it.title}\n")
        }
        if (p.skipped.isNotEmpty()) append("（没记：${p.skipped.joinToString("、") { "「$it」" }}——没听出时间，要的话单独说）\n")
        append("要都记下吗？回复「好」，或「去掉第2条」「只要第1条」。")
    }

    // ------------------------------------------------------------ 用户的回复
    sealed class Reply {
        object All : Reply()
        data class Subset(val keep: Set<Int>) : Reply()
        object Reject : Reply()
        object Other : Reply()
    }

    private val TRAIL = Regex("[！!。.~～，,\\s]+$")
    private val REJECT = Regex("^(不用了?|算了|不要了?|取消|都不要|全部不要|全都不要|不记了|别记了|不需要)[吧啊呀哦]*$")
    private val ACCEPT = Regex("^(好|好的|好吧|好哒|行|可以|嗯|嗯嗯|对|是的|确认|ok|OK|没问题|记吧|都记|全部记|全记)[，,]?(都|全部)?(记|记下|记上|记吧)?[吧啊呀哦~]*$")
    private val NUMS = Regex("第\\s*((?:[一二三四五六七八九十\\d]+\\s*[、，,和及与]\\s*)*[一二三四五六七八九十\\d]+)\\s*(?:条|个|项|件)?")
    private val EXCLUDE = Regex("去掉|不要|删掉|删除|别记|不记|取消|划掉|排除|不用")
    private val ONLY = Regex("只要|只记|只留|只保留|就记|留下|只")

    fun parseReply(text: String, n: Int): Reply {
        val t = text.trim().replace(TRAIL, "")
        if (t.isEmpty()) return Reply.Other
        if (REJECT.matches(t)) return Reply.Reject
        if (ACCEPT.matches(t)) return Reply.All
        val nums = NUMS.findAll(t).flatMap { m -> m.groupValues[1].split(Regex("[、，,和及与]")).mapNotNull { TimeParser.cnToInt(it.trim()) } }.toSet()
        if (nums.isEmpty() || nums.any { it !in 1..n }) return Reply.Other
        val all = (1..n).toSet()
        val keep = when {
            ONLY.containsMatchIn(t) && !EXCLUDE.containsMatchIn(t.replace("只要", "")) -> nums
            EXCLUDE.containsMatchIn(t) -> all - nums
            else -> return Reply.Other
        }
        return if (keep.isEmpty()) Reply.Reject else Reply.Subset(keep)
    }

    /** 待确认的清单：同一会话、5 分钟内有效，取走即清 */
    object Pending {
        private const val TTL_MS = 5 * 60_000L
        private data class Entry(val proposal: Proposal, val conv: String, val at: Long)
        @Volatile private var cur: Entry? = null

        fun put(p: Proposal, conv: String, nowMs: Long = System.currentTimeMillis()) { cur = Entry(p, conv, nowMs) }
        fun clear() { cur = null }
        fun peek(conv: String, nowMs: Long = System.currentTimeMillis()): Proposal? =
            cur?.takeIf { it.conv == conv && nowMs - it.at <= TTL_MS }?.proposal
        fun take(conv: String, nowMs: Long = System.currentTimeMillis()): Proposal? {
            val e = cur ?: return null
            cur = null
            return if (e.conv == conv && nowMs - e.at <= TTL_MS) e.proposal else null
        }
    }

    @Suppress("unused")
    private val UNUSED = DayOfWeek.MONDAY
}
