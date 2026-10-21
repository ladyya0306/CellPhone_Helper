package com.example.llama

import android.content.Context
import com.arm.aichat.InferenceEngine
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.UUID

/**
 * 一轮对话的总调度 —— 2026-10-18 重构。
 *
 * 核心原则：**程序说事实，模型说情绪。**
 *   用户看到的"做了什么"只来自动作账本（ToolOutcome.receipt，程序生成）；
 *   模型的话一律经过 ClaimGuard：任何没有账本支撑的"已设好/记住了/会提醒你"整句删除。
 *
 * 流程：
 *   0. 危机词前置拦截（不调用模型）
 *   1. 记忆指令（记住 / 忘掉 / 偏好）——程序执行，生成回执
 *   2. 确定性意图路由：闹钟 / 提醒 / 待办 / 日程 / 查时间
 *        · 槽位齐全 → 程序直接执行 → 人设短句 + 回执（不调用模型，秒回）
 *        · 缺槽位   → 程序追问（不让模型去猜时间）
 *   3. 其余交给模型：第 1 轮带工具 → 程序执行 tool_calls → 第 2 轮只负责语气
 *   4. 落库：用户消息、模型话语（kind=chat）、回执（kind=receipt）、动作账本（actions 表）
 */
class AgentLoop(
    private val engine: InferenceEngine,
    private val registry: ToolRegistry,
    private val personaOf: () -> Persona,
    private val memory: MemoryStore? = null,
    private val settings: AppSettings? = null,
) {

    data class Step(val tool: String, val argsJson: String, val result: String, val status: ActionStatus = ActionStatus.VERIFIED)

    /** answer = 人设话语；receipts = 程序回执（界面单独显示成灰色气泡） */
    data class Result(val answer: String, val steps: List<Step>, val receipts: String = "")

    @Volatile private var lastUserText: String = ""

    private class Turn(val id: String, val conversationId: String, val persona: Persona) {
        val steps = ArrayList<Step>()
        val outcomes = ArrayList<ToolOutcome>()
        /** 真实记录（记忆、回顾、历史、近 7 天用户原话）：模型说"你上次提过…"时，守卫拿它核对 */
        var evidence: List<String> = emptyList()
        fun add(tool: String, args: String, o: ToolOutcome) {
            steps.add(Step(tool, args, o.receipt, o.status)); outcomes.add(o)
        }
        val backed: Set<ClaimGuard.Cat>
            get() = outcomes.filter { it.status == ActionStatus.VERIFIED }.flatMap { it.categories }.toSet()
        val receipts: String
            get() = outcomes.filter { it.status != ActionStatus.INFO }.joinToString("\n") { it.receipt }
        val hasAction get() = outcomes.any { it.status != ActionStatus.INFO }
    }

    suspend fun run(
        context: Context,
        userText: String,
        conversationId: String,
        onStatus: suspend (String) -> Unit = {},
    ): Result {
        // 专注模式的安全阀：用户说了痛苦的话，专注台词接下来 1 小时一律温柔
        FocusRuntime.distressedUntilMs = FocusSafety.updateUntil(userText, System.currentTimeMillis(), FocusRuntime.distressedUntilMs)
        // 人设红线：这一句情绪低落时，阿冷这类人设的系统提示要追加"别讲笑话"
        val lowMood = FocusSafety.isLowMood(userText)
        lastUserText = userText
        val persona = personaOf()
        val turn = Turn("t-" + UUID.randomUUID().toString().take(8), conversationId, persona)
        val now = LocalDateTime.now()

        // 用户可能是在回复刚才的心跳：在落库用户消息之前取
        val heartbeatContext = try {
            memory?.recentMessages(3, conversationId)?.lastOrNull { it.kind != MemoryStore.KIND_NOTICE }
                ?.takeIf { it.kind == MemoryStore.KIND_HEARTBEAT && System.currentTimeMillis() - it.createdAt < 30 * 60_000L }
        } catch (_: Exception) { null }

        // ---------------- 0. 危机前置拦截 ----------------
        SafetyFilter.checkCrisis(userText)?.let { crisis ->
            persist(turn, userText, crisis, kind = MemoryStore.KIND_SAFETY, source = "safety")
            return Result(crisis, emptyList())
        }

        // ---------------- 1. 记忆指令（程序执行） ----------------
        if (settings?.memoryEnabled == true && memory != null) {
            try {
                FactExtractor.forgetTarget(userText)?.let { kw ->
                    val keys = memory.forgetMatching(kw)
                    turn.add(
                        "memory_forget", kw,
                        if (keys.isNotEmpty()) ToolOutcome.ok("已删除记忆：${keys.joinToString("、")}", ClaimGuard.Cat.MEMORY)
                        else ToolOutcome.info("没有找到与「$kw」相关的记忆")
                    )
                }
                FactExtractor.extract(userText).forEach { e ->
                    val replaced = memory.saveFact(e.key, e.value)
                    turn.add("memory_save", e.key, ToolOutcome.ok("已记住：${e.key}" + (if (replaced > 0) "（替换了之前矛盾的说法）" else ""), ClaimGuard.Cat.MEMORY))
                }
                // 身份信息（兴趣、职业、宠物…）：说得明确的才抽；先进"待确认"，隔一阵子再提一次才转正；树洞里说的不记
                if (settings.profileLearning && PersonaPacks.of(persona.id)?.privateSpace != true && FactExtractor.extract(userText).isEmpty()) {
                    val found = ProfileExtractor.extract(userText)
                    if (found.isNotEmpty()) {
                        val store = CandidateStore(context)
                        val obs = Candidates.observe(store.all(), found, System.currentTimeMillis(), promoteAt = settings.candidatePromoteAt, gapMs = settings.candidateGapMs)
                        store.save(obs.list)
                        obs.promoted.forEach { c ->
                            val replaced = memory.saveFact(c.text, c.evidence)
                            turn.add("memory_save", c.text, ToolOutcome.ok("已记住：${c.text}（你提到过 ${c.count} 次）" + (if (replaced > 0) "（替换了之前矛盾的说法）" else ""), ClaimGuard.Cat.MEMORY))
                        }
                    }
                }
            } catch (e: Exception) {
                turn.add("memory", userText, ToolOutcome.fail("记忆写入失败（${e.message}）", ClaimGuard.Cat.MEMORY))
            }
        }

        // ---------------- 1.5 一句话多事：先确认再写入 ----------------
        // 上一轮列过清单、这一轮是回复：好 / 去掉第N条 / 只要第N条 / 算了；换了话题就作废
        MultiEvent.Pending.peek(conversationId)?.let { pend ->
            when (val r = MultiEvent.parseReply(userText, pend.items.size)) {
                is MultiEvent.Reply.Other -> MultiEvent.Pending.clear()
                is MultiEvent.Reply.Reject -> {
                    MultiEvent.Pending.clear()
                    return finish(turn, userText, "好，这几件都没有记。（没有执行任何操作。）", source = "router")
                }
                else -> {
                    MultiEvent.Pending.clear()
                    val keep = if (r is MultiEvent.Reply.Subset) r.keep else pend.items.indices.map { it + 1 }.toSet()
                    pend.items.forEachIndexed { i, item -> if ((i + 1) in keep) writeMultiItem(context, item, turn) }
                    val acts = turn.outcomes.filter { it.status != ActionStatus.INFO }
                    val answer = when {
                        acts.isNotEmpty() && acts.all { it.status == ActionStatus.VERIFIED } -> PersonaVoice.done(persona)
                        acts.none { it.status == ActionStatus.VERIFIED } -> PersonaVoice.fail(persona)
                        else -> PersonaVoice.partial(persona)
                    }
                    return finish(turn, userText, answer, source = "router")
                }
            }
        }
        resolveReminderChoice(context, userText, turn)?.let { answer -> return finish(turn, userText, answer, source = "router") }
        MultiEvent.propose(userText, now)?.let { proposal ->
            MultiEvent.Pending.put(proposal, conversationId)
            return finish(turn, userText, MultiEvent.render(proposal, now), source = "router")
        }

        // ---------------- 2. 确定性路由（含多轮补槽位） ----------------
        val route = SlotFilling.resolve(IntentRouter.route(userText, now), userText, now, conversationId)
        deterministic(context, route, turn)?.let { answer ->
            SlotFilling.remember(route, conversationId)
            return finish(turn, userText, answer, source = "router")
        }
        SlotFilling.clear()

        // ---------------- 3. 模型路径 ----------------
        if (engine.state.value !is InferenceEngine.State.ModelReady) {
            val msg = if (turn.hasAction) PersonaVoice.done(persona)
            else "（模型还在加载或处于异常状态，暂时没法聊天。闹钟、提醒、待办这类指令不需要模型，现在就可以说。）"
            return finish(turn, userText, msg, source = "system")
        }

        // 只有话里确有"要办事"的意思才把工具交给模型；闲聊轮不开放，免得 4B 模型把注入的记忆当成待办自作主张写进去
        val tools = if (IntentRouter.allowTools(userText)) registry.toCodecDefs() else emptyList()
        val systemPrompt = Personas.promptFor(persona, lowMood)
        // 回执只在办事类对话里带进上下文；闲聊轮带着"已设闹钟"的回执，模型会不停地描写调闹钟、编"你昨天说闹钟设错了"
        val history = buildHistory(conversationId, persona.id, withReceipts = tools.isNotEmpty())
        // 会话第一轮（历史窗口是空的：新会话，或隔了 6 小时以上）：把"上次聊天回顾 + 档案卡"当背景给模型，
        // 之后各轮靠历史和相关记忆。纯程序抽取，不让模型总结（它会编）。写进动作账本，事后可查"给模型看了什么"。
        // 用户明确收口（"别再问了""面试完了"）：之前所有会话的回顾都不再带；每条回顾也只展示一次，免得"明天面试"问个没完
        if (settings != null && memory != null && ConversationDigest.closesTopic(userText)) {
            try { settings.recapShown = memory.listConversations(12).fold(settings.recapShown) { acc, c -> ConversationDigest.pushShown(acc, c.id) } } catch (_: Exception) {}
        }
        var shownRecap: ConversationDigest.Digest? = null
        val background = if (history.isEmpty() && settings?.memoryEnabled == true && memory != null) {
            try {
                shownRecap = ConversationDigest.recapFor(memory.previousDigest(conversationId, persona.id, System.currentTimeMillis(), settings.recapShown), userText)
                ConversationDigest.promptBlock(
                    shownRecap,
                    ConversationDigest.profileFor(memory.freshFacts().map { it.first }, userText),
                )
            } catch (_: Exception) { "" }
        } else ""
        val recapId = shownRecap?.convId.orEmpty()
        if (settings != null && recapId.isNotBlank()) settings.recapShown = ConversationDigest.pushShown(settings.recapShown, recapId)
        if (background.isNotBlank()) turn.steps.add(Step("context_recap", "", background, ActionStatus.INFO))
        turn.evidence = try {
            buildList {
                add(userText); add(background)
                history.filter { it.role == "user" }.forEach { add(it.content) }
                memory?.allFacts()?.forEach { add(it.first); add(it.second) }
                memory?.userLinesBetween(System.currentTimeMillis() - 7 * 86_400_000L, System.currentTimeMillis(), 200)?.forEach { add(it) }
            }
        } catch (_: Exception) { listOf(userText, background) }
        val userContent = buildUserContent(userText, now, turn, heartbeatContext, background)

        val firstMessages = buildList {
            add(ToolCodec.ChatMessage("system", systemPrompt))
            addAll(history)
            add(ToolCodec.ChatMessage("user", userContent))
        }
        onStatus("思考中 …")
        val firstRaw = StringBuilder()
        try {
            engine.sendToolPrompt(ToolCodec.encodeMessages(firstMessages), ToolCodec.encodeTools(tools), FIRST_ROUND_TOKENS)
                .collect { firstRaw.append(it) }
        } catch (e: Exception) {
            return finish(turn, userText, "（生成失败：${e.message}）", source = "error")
        }
        val rawText = firstRaw.toString()
        val calls = if (tools.isEmpty()) emptyList() else ToolCodec.decodeToolCalls(engine.parseToolCalls(rawText))

        if (calls.isEmpty()) {
            // 模型没调工具：它的话照常显示，但任何"已经办好"的说法都会被守卫删掉，并如实告诉用户
            val g = ClaimGuard.guard(postProcess(stripToolMarkup(rawText)), turn.backed, turn.evidence)
            var chat = g.text
            if (g.removed.isNotEmpty()) {
                turn.steps.add(Step("claim_guard", g.removed.joinToString(" | ").take(200), "删除了 ${g.removed.size} 句没有执行记录支撑的承诺", ActionStatus.INFO))
                val note = ClaimGuard.removalNote(turn.hasAction)
                chat = if (chat.isBlank()) note
                else if (ClaimGuard.noteWanted(userText, turn.hasAction)) chat + "\n\n" + note
                else chat
            }
            return finish(turn, userText, SafetyFilter.withDistressHint(chat, userText), source = if (g.removed.isEmpty()) "local" else "guarded")
        }

        // ---------------- 执行模型提出的工具调用 ----------------
        calls.forEach { call ->
            onStatus("执行 ${call.name} …")
            val args = try { JSONObject(call.arguments.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
            turn.add(call.name, args.toString(), registry.execute(call.name, args, context))
        }

        // 全部失败 → 不进第 2 轮（否则模型会把失败"圆"成成功）
        if (turn.outcomes.none { it.succeeded }) {
            return finish(turn, userText, PersonaVoice.fail(persona), source = "refusal")
        }

        // ---------------- 第 2 轮：只要语气，不要事实 ----------------
        onStatus("整理结果 …")
        val factsForModel = turn.outcomes.joinToString("\n") { o ->
            when (o.status) {
                ActionStatus.VERIFIED -> "已完成：" + o.forModel.removePrefix("✅ ")
                ActionStatus.UNVERIFIED -> "未能确认：" + o.forModel.removePrefix("⚠️ ")
                ActionStatus.FAILED -> "失败：" + o.forModel.removePrefix("❌ ")
                ActionStatus.INFO -> "查询结果：" + o.forModel
            }
        }
        val secondMessages = listOf(
            ToolCodec.ChatMessage("system", systemPrompt),
            ToolCodec.ChatMessage(
                "user",
                "用户刚才说：「$userText」\n系统的真实执行情况：\n$factsForModel\n\n" +
                    "请用你的人设口吻回应用户一两句话。执行细节系统会单独显示，你不要复述时间和编号；" +
                    "失败或未确认的事不能说成已完成；不要输出工具调用格式。"
            ),
        )
        val secondRaw = StringBuilder()
        try {
            engine.sendToolPrompt(ToolCodec.encodeMessages(secondMessages), ByteArray(0), SECOND_ROUND_TOKENS)
                .collect { secondRaw.append(it) }
        } catch (_: Exception) {
        }
        var voice = guard(postProcess(stripToolMarkup(secondRaw.toString())), turn)
        if (voice.isBlank()) voice = if (turn.outcomes.all { it.succeeded }) PersonaVoice.done(persona) else PersonaVoice.partial(persona)
        return finish(turn, userText, SafetyFilter.withDistressHint(voice, userText), source = "local")
    }

    /** 把确认过的一件事写进对应的系统/助手存储；每一步都进动作账本，回执由程序生成 */
    private fun writeMultiItem(context: Context, item: MultiEvent.Item, turn: Turn) {
        fun off(name: String): Boolean {
            if (registry.isEnabled(name)) return false
            turn.add(name, item.title, ToolOutcome.fail("没能记「${item.title}」：「$name」工具已在设置里关闭"))
            return true
        }
        val parsed = TimeParser.Parsed(item.at, hasDate = true, hasTime = item.hasClock, relative = false, ampmAssumed = false)
        when (item.type) {
            MultiEvent.Type.CALENDAR -> if (!off("calendar_add")) turn.add("calendar_add", item.at.toString() + " " + item.title, Actions.addCalendar(context, item.title, item.at, 30, 0, parsed))
            MultiEvent.Type.REMINDER -> if (!off("reminder_add")) turn.add("reminder_add", item.at.toString() + " " + item.title, Actions.addReminder(context, item.at, item.title, parsed))
            MultiEvent.Type.ALARM -> if (!off("alarm_set")) turn.add("alarm_set", item.at.toString(), Actions.setAlarm(context, item.at, item.title, parsed))
            MultiEvent.Type.TODO -> if (!off("todo_add")) turn.add("todo_add", item.title, Actions.addTodo(context, registry.todoStore, item.title, parsed))
        }
    }


    /** 步数：数字只来自计步器的记录；没开/没授权/没记录都如实说，不报 0 步 */
    private fun stepAnswer(context: Context, p: Persona, turn: Turn): String {
        val st = AppSettings(context)
        if (!st.stepsEnabled || !StepSource.hasPermission(context) || !StepSource.hasSensor(context)) {
            val t = if (!StepSource.hasSensor(context)) "这台手机没有计步器，数不了步。" else StepLogic.unavailableText(StepSource.hasPermission(context) && st.stepsEnabled).let { if (!st.stepsEnabled) "记录步数没有打开：在设置里打开「记录步数」并允许「身体活动」权限后，我才能数。" else it }
            turn.add("step_query", "", ToolOutcome.info(t)); return MemoryQA.lead(p, t)
        }
        try { StepSource.sample(context, force = true) } catch (_: Exception) {}
        val sum = StepSource.summary(context)
        val a = if (sum == null) StepLogic.unavailableText(true) else StepLogic.answer(sum, lastUserText, System.currentTimeMillis())
        turn.add("step_query", "", ToolOutcome.info(a))
        return MemoryQA.lead(p, a)
    }

    /** 晨报/晚报：把日历、提醒、待办、闹钟、天气、步数里真实有的东西拼成一段；什么都没有就如实说 */
    private fun briefAnswer(context: Context, p: Persona, turn: Turn): String {
        val card = try { HeartbeatSources.collect(context) } catch (_: Exception) { null }
        val c = card?.let { HeartbeatCompose.briefNow(it, p, System.nanoTime().toInt()) }
        val a = c?.text ?: "暂时没什么可说的：日历、提醒、待办里都没有安排，天气和步数也没有数据。"
        turn.add("brief_query", "", ToolOutcome.info(a))
        return a
    }

    /** 天气：数据只来自手机自带天气应用或（用户打开后的）联网备用；取不到就如实说，不让模型编 */
    private fun weatherAnswer(context: Context, route: IntentRouter.Route, p: Persona, turn: Turn): String {
        val text = lastUserText
        val res = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { WeatherClient.get(context) }
        val snap = res.snapshot
        if (snap == null) {
            turn.add("weather_query", "", ToolOutcome.info(res.note))
            return MemoryQA.lead(p, "天气我这边没查到。" + res.note)
        }
        val a = Weather.answerFor(snap, text, System.currentTimeMillis())
        turn.add("weather_query", snap.city + "/" + snap.source + "/d${Weather.dayOf(text)}", ToolOutcome.info(a))
        return MemoryQA.lead(p, a)
    }

    // ============================================================ 改 / 删 / 推迟 提醒
    private fun zoned(ms: Long): LocalDateTime = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()

    private fun reminderLabel(r: ReminderScheduler.Reminder, now: LocalDateTime): String {
        val rule = RepeatRule.fromString(r.repeat)
        return TimeParser.describe(zoned(r.triggerAt), now) + (if (rule != null) "（${RepeatRule.describe(rule)}）" else "") + "「${r.text}」"
    }

    private fun verbOf(e: ReminderEdit.Edit) = if (e.kind == ReminderEdit.Kind.CANCEL) "取消" else "改时间"

    /** 对找到的这一条提醒执行取消/改时间；每一步进动作账本，回执由程序生成 */
    private fun applyReminderEdit(context: Context, e: ReminderEdit.Edit, r: ReminderScheduler.Reminder, turn: Turn, now: LocalDateTime) {
        when (e.kind) {
            ReminderEdit.Kind.CANCEL -> {
                val label = reminderLabel(r, now)
                if (ReminderScheduler.cancel(context, r.id)) turn.add("reminder_cancel", "#${r.id}", ToolOutcome.ok("已取消${if (r.alarm) "助手闹钟" else "提醒"}：$label", ClaimGuard.Cat.REMINDER))
                else turn.add("reminder_cancel", "#${r.id}", ToolOutcome.fail("没能取消：$label（系统没有确认）", ClaimGuard.Cat.REMINDER))
            }
            ReminderEdit.Kind.RESCHEDULE -> {
                val nt = e.newTime?.dateTime
                val upd = if (nt != null) ReminderScheduler.reschedule(context, r.id, nt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) else null
                if (upd != null) turn.add("reminder_reschedule", "#${r.id}", ToolOutcome.ok("已把「${r.text}」改到 ${TimeParser.describe(nt!!, now)}", ClaimGuard.Cat.REMINDER))
                else turn.add("reminder_reschedule", "#${r.id}", ToolOutcome.fail("没能改时间：「${r.text}」（新时间已经过去，或系统没有接受）", ClaimGuard.Cat.REMINDER))
            }
            else -> {}
        }
    }

    private fun editVoice(turn: Turn): String {
        val acts = turn.outcomes.filter { it.status != ActionStatus.INFO }
        val p = turn.persona
        return when {
            acts.isNotEmpty() && acts.all { it.status == ActionStatus.VERIFIED } -> PersonaVoice.done(p)
            acts.none { it.status == ActionStatus.VERIFIED } -> PersonaVoice.fail(p)
            else -> PersonaVoice.partial(p)
        }
    }

    private fun handleReminderEdit(context: Context, e: ReminderEdit.Edit?, turn: Turn): String? {
        if (e == null) return null
        val now = LocalDateTime.now()
        val p = turn.persona
        if (e.kind == ReminderEdit.Kind.SNOOZE) {
            val last = ReminderScheduler.lastFired(context)
                ?: return "（最近没有刚响过的提醒，所以没有可推迟的。要新设一个的话，直接说「10分钟后提醒我…」。没有执行任何操作。）"
            val sec = ReminderLogic.defaultSnoozeSec(e.snoozeSec ?: AppSettings(context).snoozeSecOverride.takeIf { it > 0 })
            val label = if (sec >= 60) "${sec / 60} 分钟" else "$sec 秒"
            val r = ReminderScheduler.snooze(context, last.id, sec)
            if (r == null) turn.add("reminder_snooze", "#${last.id}", ToolOutcome.fail("没能推迟「${last.text}」：重新登记定时失败", ClaimGuard.Cat.REMINDER))
            else turn.add("reminder_snooze", "#${r.id}", ToolOutcome.ok("已推迟：「${last.text}」$label 后（${TimeParser.describe(zoned(r.triggerAt), now)}）再提醒你", ClaimGuard.Cat.REMINDER))
            return editVoice(turn)
        }
        val list = ReminderScheduler.upcoming(context)
        return when (val m = ReminderEdit.match(list, e, now)) {
            is ReminderEdit.Match.None ->
                if (e.target == ReminderEdit.Target.ALARM) "（没找到助手登记的闹钟。系统时钟里的闹钟程序改不了——请在「时钟」里直接改；没有执行任何操作。）"
                else "（没找到符合的提醒，没有执行任何操作。）"
            is ReminderEdit.Match.One -> { applyReminderEdit(context, e, m.r, turn, now); editVoice(turn) }
            is ReminderEdit.Match.All -> {
                if (e.kind != ReminderEdit.Kind.CANCEL) return "（一次改好几个提醒的时间我没法一步到位，请说清楚是哪一个。没有执行任何操作。）"
                m.list.forEach { applyReminderEdit(context, e, it, turn, now) }
                editVoice(turn)
            }
            is ReminderEdit.Match.Many -> {
                ReminderEdit.Pending.put(e, m.list.map { it.id }, turn.conversationId)
                PersonaVoice.ask(p, "有 ${m.list.size} 个符合，要${verbOf(e)}哪一个") + "\n" +
                    m.list.mapIndexed { i, r -> "${i + 1}. ${reminderLabel(r, now)}" }.joinToString("\n") +
                    "\n回复序号，或「算了」。"
            }
        }
    }

    /** 上一轮问了"是哪一个"，这一轮是回答 */
    private fun resolveReminderChoice(context: Context, userText: String, turn: Turn): String? {
        val pend = ReminderEdit.Pending.peek(turn.conversationId) ?: return null
        val now = LocalDateTime.now()
        return when (val c = ReminderEdit.parseChoice(userText, pend.ids.size)) {
            is ReminderEdit.Choice.Other -> { ReminderEdit.Pending.clear(); null }
            is ReminderEdit.Choice.Never -> { ReminderEdit.Pending.clear(); "好，没有改动。（没有执行任何操作。）" }
            is ReminderEdit.Choice.Pick -> {
                ReminderEdit.Pending.clear()
                val r = ReminderScheduler.all(context).firstOrNull { it.id == pend.ids[c.n - 1] && !it.cancelled && it.firedAt == 0L }
                if (r == null) "（那个提醒已经不在了，没有执行任何操作。）" else { applyReminderEdit(context, pend.edit, r, turn, now); editVoice(turn) }
            }
            is ReminderEdit.Choice.AllOf -> {
                ReminderEdit.Pending.clear()
                if (pend.edit.kind != ReminderEdit.Kind.CANCEL) return "（一次改好几个提醒的时间我没法一步到位，请说清楚是哪一个。没有执行任何操作。）"
                ReminderScheduler.all(context).filter { it.id in pend.ids && !it.cancelled && it.firedAt == 0L }.forEach { applyReminderEdit(context, pend.edit, it, turn, now) }
                editVoice(turn)
            }
        }
    }

    // ============================================================ 确定性路径
    /** 返回非空 = 本轮已由程序处理完毕（返回的是人设短句） */
    private fun deterministic(context: Context, route: IntentRouter.Route, turn: Turn): String? {
        val p = turn.persona
        val store = registry.todoStore
        fun toolOff(name: String) = !registry.isEnabled(name)

        if (!route.confident) {
            if (route.missing != null && route.kind != IntentRouter.Kind.NONE) {
                val q = when (route.kind) {
                    IntentRouter.Kind.ALARM -> "闹钟要定在几点"
                    IntentRouter.Kind.REMINDER -> if (route.content.isNotBlank()) "什么时候提醒你「${route.content}」" else "什么时候提醒你、提醒什么"
                    IntentRouter.Kind.CALENDAR_ADD -> "这个日程定在哪天几点"
                    IntentRouter.Kind.TODO_ADD -> "要记什么事"
                    else -> "能再说具体一点吗"
                }
                return PersonaVoice.ask(p, q)
            }
            return null
        }
        val t = route.time
        when (route.kind) {
            IntentRouter.Kind.TIME_QUERY -> {
                turn.add("current_time", "{}", ToolOutcome.info(Actions.nowText()))
                return Actions.nowText()
            }
            IntentRouter.Kind.ABOUT_SELF -> return ClaimGuard.aboutSelfText()
            IntentRouter.Kind.MEMORY_QUERY, IntentRouter.Kind.WORLD_QUERY -> {
                val q = route.query ?: return null
                val facts = try {
                    if (settings?.memoryEnabled == true) memory?.allFacts()?.map { MemoryRelevance.Fact(it.first, it.second) } ?: emptyList() else emptyList()
                } catch (_: Exception) { emptyList() }
                val past = if (q.kind == MemoryQA.Q.PAST) pastUserLines(q.topic, LocalDateTime.now()) else emptyList()
                val a = when {
                    q.kind == MemoryQA.Q.WORLD && q.topic == "天气" -> weatherAnswer(context, route, p, turn)
                    q.kind == MemoryQA.Q.WORLD && q.topic == "步数" -> stepAnswer(context, p, turn)
                    q.kind == MemoryQA.Q.WORLD && q.topic == "简报" -> briefAnswer(context, p, turn)
                    else -> MemoryQA.answer(q, facts, past, p)
                }
                // INFO：只是查询结果，不是"动作回执"；用户看到的话全部来自程序查到的记录
                turn.add(if (q.kind == MemoryQA.Q.WORLD) "world_query" else "memory_recall", q.kind.name + ":" + q.topic, ToolOutcome.info(a))
                return a
            }
            IntentRouter.Kind.UNSUPPORTED -> return "（取消或修改闹钟、提醒、日程，我现在还做不到——没有执行任何操作。）\n" +
                "· 系统时钟里的闹钟：请在「时钟」里直接改\n· 助手登记的提醒：设置 → 提醒列表 → 点一下即可取消\n· 日程：请在「日历」里改"
            IntentRouter.Kind.TODO_LIST -> {
                if (toolOff("todo_list")) return null
                val o = Actions.listTodos(store)
                turn.add("todo_list", "{}", o)
                return o.receipt
            }
            IntentRouter.Kind.TODO_DONE -> {
                if (toolOff("todo_done")) return null
                turn.add("todo_done", """{"id":${route.todoId}}""", Actions.doneTodo(store, route.todoId ?: -1))
            }
            IntentRouter.Kind.ALARM -> {
                if (toolOff("alarm_set") || t == null) return null
                turn.add("alarm_set", t.dateTime.toString() + (if (route.repeat.isNotBlank()) " " + route.repeat else ""), Actions.setAlarm(context, t.dateTime, route.content, t, route.repeat))
            }
            IntentRouter.Kind.REMINDER -> {
                if (toolOff("reminder_add") || t == null) return null
                turn.add("reminder_add", t.dateTime.toString() + " " + route.content + (if (route.repeat.isNotBlank()) " " + route.repeat else ""), Actions.addReminder(context, t.dateTime, route.content, t, route.repeat))
            }
            IntentRouter.Kind.TODO_ADD -> {
                if (toolOff("todo_add")) return null
                turn.add("todo_add", route.content, Actions.addTodo(context, store, route.content, t))
            }
            IntentRouter.Kind.CALENDAR_ADD -> {
                if (toolOff("calendar_add") || t == null) return null
                turn.add("calendar_add", t.dateTime.toString() + " " + route.content, Actions.addCalendar(context, route.content, t.dateTime, 30, 0, t))
            }
            IntentRouter.Kind.REMINDER_EDIT -> return handleReminderEdit(context, route.edit, turn)
            IntentRouter.Kind.NONE -> return null
        }
        val acts = turn.outcomes.filter { it.status != ActionStatus.INFO }
        return when {
            acts.all { it.status == ActionStatus.VERIFIED } -> PersonaVoice.done(p)
            acts.none { it.status == ActionStatus.VERIFIED } && acts.any { it.status == ActionStatus.FAILED } -> PersonaVoice.fail(p)
            else -> PersonaVoice.partial(p)
        }
    }

    /** "昨天/前天/上次说过什么"：从数据库取用户当时亲口说的话（"上次"= 30 分钟之前、7 天以内，免得把刚才这段对话当成"上次"） */
    private fun pastUserLines(when_: String, now: LocalDateTime): List<String> {
        val m = memory ?: return emptyList()
        val zone = java.time.ZoneId.systemDefault()
        fun ms(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()
        val today0 = now.toLocalDate().atStartOfDay()
        return try {
            when (when_) {
                "昨天" -> m.userLinesBetween(ms(today0.minusDays(1)), ms(today0))
                "前天" -> m.userLinesBetween(ms(today0.minusDays(2)), ms(today0.minusDays(1)))
                else -> m.userLinesBetween(ms(now.minusDays(7)), ms(now.minusMinutes(30)))
            }
        } catch (_: Exception) { emptyList() }
    }

    // ============================================================ 组装上下文
    private fun buildHistory(conversationId: String, personaId: String, withReceipts: Boolean): List<ToolCodec.ChatMessage> {
        if (settings?.memoryEnabled != true || memory == null) return emptyList()
        val recs = try {
            memory.promptHistory(conversationId, personaId, HISTORY_MESSAGES * 2, Conversations.PROMPT_WINDOW_HOURS * 3600_000L)
        } catch (_: Exception) { emptyList() }
        // 回执并入它所属的那条 assistant 消息，让模型看到"那次是系统真做了"，而不是"嘴上说一句就算做了"
        val out = ArrayList<ToolCodec.ChatMessage>()
        for (r in recs) {
            if (r.content.isBlank()) continue
            when {
                r.role == "user" -> out.add(ToolCodec.ChatMessage("user", r.content))
                r.kind == MemoryStore.KIND_RECEIPT -> if (withReceipts) {
                    val last = out.lastOrNull()
                    val line = "（系统回执：" + r.content.replace("\n", "；") + "）"
                    if (last != null && last.role == "assistant") out[out.size - 1] = last.copy(content = last.content + "\n" + line)
                }
                else -> ClaimGuard.stripProgramText(r.content).takeIf { it.isNotBlank() }
                    ?.let { out.add(ToolCodec.ChatMessage("assistant", it)) }
            }
        }
        // 取最后 N 条，并保证从 user 开始（多数模板要求 user/assistant 交替）
        var tail = out.takeLast(HISTORY_MESSAGES)
        while (tail.isNotEmpty() && tail.first().role != "user") tail = tail.drop(1)
        return tail
    }

    private fun buildUserContent(userText: String, now: LocalDateTime, turn: Turn, hb: MemoryStore.ChatRecord?, background: String = ""): String = buildString {
        val wd = arrayOf("一", "二", "三", "四", "五", "六", "日")[now.dayOfWeek.value - 1]
        append("[现在是 %04d-%02d-%02d %02d:%02d 星期%s]\n".format(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, wd))
        if (background.isNotBlank()) append(background).append('\n')
        if (settings?.memoryEnabled == true && memory != null) {
            val maxFacts = ((settings.memoryTokenBudget) / 60).coerceIn(1, 5)
            val facts = try { memory.relevantFacts(userText, maxFacts) } catch (_: Exception) { emptyList() }
            val block = MemoryRelevance.render(facts)
            if (block.isNotBlank()) append(block).append('\n')
        }
        val memDone = turn.outcomes.filter { it.categories.contains(ClaimGuard.Cat.MEMORY) || it.status == ActionStatus.INFO }
        if (memDone.isNotEmpty()) {
            append("[系统已实际执行] ").append(memDone.joinToString("；") { it.forModel.removePrefix("✅ ").removePrefix("❌ ") })
            append("。只有这些事真的做了，请据此如实回答。\n")
        }
        if (hb != null) append("[你刚才主动对用户说过：「").append(hb.content.take(60)).append("」，用户现在可能是在回应它]\n")
        append('\n').append(userText)
    }

    // ============================================================ 收尾
    private fun guard(text: String, turn: Turn): String {
        val g = ClaimGuard.guard(text, turn.backed, turn.evidence)
        if (g.removed.isNotEmpty()) {
            turn.steps.add(Step("claim_guard", g.removed.joinToString(" | ").take(200), "删除了 ${g.removed.size} 句没有执行记录支撑的承诺", ActionStatus.INFO))
        }
        return g.text
    }

    private fun finish(turn: Turn, userText: String, answer: String, source: String): Result {
        val receipts = turn.receipts
        persist(turn, userText, answer, kind = MemoryStore.KIND_CHAT, source = source, receipts = receipts)
        return Result(answer, turn.steps.toList(), receipts)
    }

    private fun persist(turn: Turn, userText: String, answer: String, kind: String, source: String, receipts: String = "") {
        val m = memory ?: return
        try {
            // 动作账本无论是否开启记忆都要记（它是审计，不是记忆）
            // 守卫拦截也入账本（方案 §4.1：账本记录被拦截的虚假承诺），args 里是被删的原句
            turn.steps.forEach { s ->
                m.logAction(turn.conversationId, turn.id, s.tool, s.argsJson, s.status.name, s.result)
            }
            if (settings?.memoryEnabled != true) return
            m.saveMessage("user", userText, source = "user", conversationId = turn.conversationId,
                kind = if (kind == MemoryStore.KIND_SAFETY) MemoryStore.KIND_SAFETY else MemoryStore.KIND_CHAT,
                personaId = turn.persona.id, turnId = turn.id)
            if (answer.isNotBlank()) {
                m.saveMessage("assistant", answer, source = source, conversationId = turn.conversationId, kind = kind,
                    personaId = turn.persona.id, turnId = turn.id)
            }
            if (receipts.isNotBlank()) {
                m.saveMessage("assistant", receipts, source = "receipt", conversationId = turn.conversationId,
                    kind = MemoryStore.KIND_RECEIPT, personaId = turn.persona.id, turnId = turn.id)
            }
        } catch (_: Exception) {
        }
    }

    /** 人设后处理：出戏修正 → 红线删句 → 口癖频率校准 */
    private fun postProcess(text: String): String {
        var out = ReplyCleaner.clean(text.trim())
        if (out.isEmpty()) return out
        out = PersonaPost.fixBreak(out, personaOf())
        out = PersonaRedLines.sanitize(personaOf(), out)
        out = ToxicityGuard.sanitize(personaOf(), out)
        out = PersonaPost.injectTic(out, personaOf(), settings?.ticStrength ?: 1)
        return out
    }

    /** 模型偶尔把半截 tool_call 标记当正文吐出来 */
    private fun stripToolMarkup(s: String): String =
        s.replace(Regex("<tool_call>[\\s\\S]*?(</tool_call>|$)"), "")
            .replace(Regex("</?tool_call>|</?function[^>]*>|</?parameter[^>]*>"), "")
            .trim()

    companion object {
        private const val FIRST_ROUND_TOKENS = 256
        private const val SECOND_ROUND_TOKENS = 160
        /** 每轮带多少条历史（3 个来回） */
        private const val HISTORY_MESSAGES = 6
    }
}
