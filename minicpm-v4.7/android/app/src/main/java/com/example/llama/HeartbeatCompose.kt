package com.example.llama

import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * 心跳内容合成：程序把手机里**真实存在**的东西（系统闹钟、系统日历日程、到点提醒、待办、记忆）拼成"上下文卡片"，
 * 按优先级挑一件，套上人设口吻——**不开模型**。纯 Kotlin，可单测。
 *
 * 为什么不让模型写：心跳在后台触发，模型要加载 3GB、会被系统杀；而且 2026-10-19 评测里所有本地模型在"没有依据时"都会编造。
 * 这里每个数字、每个标题都来自卡片，模板里没有任何"已办好"类的声明。
 *
 * 优先级：生日 > 一小时内要发生的 > 今天还有的 > 明天的 > 待办。都没有就返回 null，调用方退回原来的话术模板。
 */
object HeartbeatCompose {

    enum class Kind { ALARM, REMINDER, CALENDAR }
    data class Item(val kind: Kind, val atMs: Long, val title: String)
    data class Todo(val id: Int, val text: String, val dueMs: Long? = null)
    /** facts：记忆事实原文（如 "生日：3月15日"） */
    data class Card(val nowMs: Long, val items: List<Item>, val todos: List<Todo>, val facts: List<String>, val weather: Weather.Snapshot? = null, val steps: StepLogic.Summary? = null)

    enum class Topic { BIRTHDAY, IMMINENT, BRIEF, WEATHER, TODAY, TOMORROW, TODO }
    /** key：内容指纹（不含"还有几分钟"这类随时间变化的部分），交给"同样内容冷却"去重 */
    data class Composed(val text: String, val key: String, val topic: Topic)

    private const val HORIZON_MS = 36 * 3600_000L
    private const val IMMINENT_MS = 60 * 60_000L

    fun compose(card: Card, persona: Persona, seed: Int): Composed? {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(card.nowMs).atZone(zone)
        val today = now.toLocalDate()
        fun dateOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        fun hhmm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
        val rnd = Random(seed)

        // ---- 生日（今天）----
        val bday = card.facts.firstNotNullOfOrNull { Regex("生日[：:]\\s*(\\d{1,2})月(\\d{1,2})日").find(it) }
        if (bday != null && bday.groupValues[1].toInt() == today.monthValue && bday.groupValues[2].toInt() == today.dayOfMonth) {
            return Composed(wrap(persona, false, "今天是你的生日（${today.monthValue}月${today.dayOfMonth}日）", rnd), "birthday:$today", Topic.BIRTHDAY)
        }

        // ---- 即将发生 / 今天 / 明天（先去重：日历和提醒、系统闹钟和助手闹钟说的是同一件事只留一条）----
        val items = card.items
            .filter { it.atMs > card.nowMs && it.atMs <= card.nowMs + HORIZON_MS }
            .groupBy { it.atMs / 60_000 to it.title }
            // 同一分钟同标题只留一条：日历 > 提醒 > 闹钟
            .map { (_, g) -> g.minByOrNull { when (it.kind) { Kind.CALENDAR -> 0; Kind.REMINDER -> 1; Kind.ALARM -> 2 } }!! }
            .sortedBy { it.atMs }

        fun label(i: Item) = when (i.kind) {
            Kind.CALENDAR -> "日程「${i.title}」"
            Kind.REMINDER -> "提醒「${i.title}」"
            Kind.ALARM -> "闹钟"
        }
        fun fp(list: List<Item>) = list.joinToString("|") { "${it.kind.name.first()}@${hhmm(it.atMs)}#${it.title}" }

        val imminent = items.filter { it.atMs - card.nowMs <= IMMINENT_MS }.take(2)
        if (imminent.isNotEmpty()) {
            val mins = ((imminent[0].atMs - card.nowMs + 59_999) / 60_000).toInt()
            val first = "${hhmm(imminent[0].atMs)} 有${label(imminent[0])}" + (if (mins <= 1) "（马上）" else "（还有 $mins 分钟）")
            val rest = imminent.drop(1).joinToString("") { "；${hhmm(it.atMs)} 有${label(it)}" }
            return Composed(wrap(persona, true, first + rest, rnd), "imminent:" + fp(imminent), Topic.IMMINENT)
        }
        // ---- 晨报 / 晚报：早上 6:30–10:30、晚上 20:00–22:30 各一次（一天一次由 HeartbeatPolicy 按指纹把关）----
        val minOfDay = now.hour * 60 + now.minute
        val briefKind = when { minOfDay in 390..630 -> "morning"; minOfDay in 1200..1350 -> "evening"; else -> null }
        if (briefKind != null) briefSegments(card, briefKind, zone)?.let { return Composed(wrap(persona, false, it, rnd), "brief:$briefKind:$today", Topic.BRIEF) }

        // ---- 天气：白天（6–20 点）今天降水概率 ≥60% → 提醒带伞；数据超过 12 小时就不用；一天一次（见 HeartbeatPolicy.dayCapSilence）----
        card.weather?.let { w ->
            val d0 = w.days.firstOrNull()
            if (d0 != null && Weather.usableWhenOffline(w, card.nowMs) && d0.rainProb >= 60 && now.hour in 6..20) {
                return Composed(wrap(persona, false, "今天有雨（降水概率 ${d0.rainProb}%），出门记得带伞", rnd), "weather:rain:$today", Topic.WEATHER)
            }
        }
        val todayItems = items.filter { dateOf(it.atMs) == today }.take(3)
        if (todayItems.isNotEmpty()) {
            val s = "今天还有：" + todayItems.joinToString("、") { "${hhmm(it.atMs)} ${label(it)}" }
            return Composed(wrap(persona, false, s, rnd), "today:" + fp(todayItems), Topic.TODAY)
        }
        val tomorrowItems = items.filter { dateOf(it.atMs) == today.plusDays(1) }.take(2)
        if (tomorrowItems.isNotEmpty()) {
            val s = "明天：" + tomorrowItems.joinToString("、") { "${hhmm(it.atMs)} ${label(it)}" }
            return Composed(wrap(persona, false, s, rnd), "tomorrow:" + fp(tomorrowItems), Topic.TOMORROW)
        }

        // ---- 待办 ----
        if (card.todos.isNotEmpty()) {
            val first = card.todos.sortedWith(compareBy({ it.dueMs ?: Long.MAX_VALUE }, { it.id })).first()
            val s = "待办还有 ${card.todos.size} 条，先看看「${first.text}」"
            return Composed(wrap(persona, false, s, rnd), "todo:" + card.todos.map { it.id }.sorted().joinToString(","), Topic.TODO)
        }
        return null
    }

    /** 主动要简报（任何时间都行）：中午前按晨报口径，之后按晚报口径；真的什么都没有返回 null */
    fun briefNow(card: Card, persona: Persona, seed: Int): Composed? {
        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(card.nowMs).atZone(zone)
        val kind = if (now.hour < 12) "morning" else "evening"
        val text = briefSegments(card, kind, zone) ?: return null
        return Composed(wrap(persona, false, text, Random(seed)), "brief:$kind:${now.toLocalDate()}", Topic.BRIEF)
    }

    /**
     * 简报的每一段都来自真实数据：天气（手机天气/联网备用）、系统日历与提醒、待办、步数。没有的那一段直接不写，不说"今天没有日程"这种读不到就不能断言的话。
     * 晨报：今天的天气与日程、待办、今天还会响的闹钟、昨天走了多少步。晚报：今天走了多少步、明天的天气与第一项日程、明早的闹钟、没做完的待办。
     */
    private fun briefSegments(card: Card, kind: String, zone: ZoneId): String? {
        val today = Instant.ofEpochMilli(card.nowMs).atZone(zone).toLocalDate()
        fun dateOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        fun hhmm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
        val future = card.items.filter { it.atMs > card.nowMs }.sortedBy { it.atMs }
        val segs = ArrayList<String>()
        fun weatherSeg(idx: Int, label: String) {
            val w = card.weather ?: return
            if (!Weather.usableWhenOffline(w, card.nowMs)) return
            val d = w.days.getOrNull(idx) ?: return
            val name = if (d.text.isNotBlank()) d.text else Weather.describeCode(d.code)
            var t = "$label$name ${Math.round(d.min)}–${Math.round(d.max)}℃"
            if (d.rainProb >= 30) t += "，降水概率 ${d.rainProb}%"
            if (Weather.advice(d, 0.0).any { it.contains("带伞") && !it.contains("折叠") }) t += "，记得带伞"
            segs.add(t)
        }
        if (kind == "morning") {
            weatherSeg(0, "今天")
            val ev = future.filter { it.kind != Kind.ALARM && dateOf(it.atMs) == today }.take(3)
            if (ev.isNotEmpty()) segs.add("今天 ${ev.size} 项：" + ev.joinToString("、") { "${hhmm(it.atMs)} ${it.title}" })
            if (card.todos.isNotEmpty()) segs.add("待办还有 ${card.todos.size} 条")
            future.firstOrNull { it.kind == Kind.ALARM && dateOf(it.atMs) == today }?.let { segs.add("今天还有闹钟 ${hhmm(it.atMs)}") }
            card.steps?.yesterday?.let { segs.add("昨天走了 $it 步") }
        } else {
            card.steps?.today?.takeIf { it > 0 }?.let { segs.add("今天走了 $it 步") }
            weatherSeg(1, "明天")
            future.firstOrNull { it.kind != Kind.ALARM && dateOf(it.atMs) == today.plusDays(1) }?.let { segs.add("明天第一项：${hhmm(it.atMs)} ${it.title}") }
            future.firstOrNull { it.kind == Kind.ALARM && dateOf(it.atMs) == today.plusDays(1) }?.let { segs.add("明早闹钟 ${hhmm(it.atMs)}") }
            if (card.todos.isNotEmpty()) segs.add("还有 ${card.todos.size} 条待办没做")
        }
        return if (segs.isEmpty()) null else segs.joinToString("；")
    }

    // ------------------------------------------------------------------ 人设口吻（程序写的句子，不含"已办好"类声明）
    private fun wrap(p: Persona, imminent: Boolean, s: String, rnd: Random): String {
        val v = PersonaPacks.packOf(p.id).heartbeat
        val list = if (imminent) v.imminent else v.normal
        return list[rnd.nextInt(list.size)].replace("{s}", s)
    }
}
