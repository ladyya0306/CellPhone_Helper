package com.example.llama

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.CalendarContract
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 端到端自动测试钩子 —— **仅调试包生效**。
 *
 * 触发：adb shell am broadcast -a com.minicpm.assistant.E2E -n com.minicpm.assistant/com.example.llama.TestHookReceiver
 * 保护：清单里要求 android.permission.DUMP（只有 adb shell 拥有，普通应用拿不到）；
 *       且运行时检查 FLAG_DEBUGGABLE，正式包里直接忽略。
 *
 * 做什么：在一个独立的测试会话里跑固定用例（中文写在代码里，避开 adb 不能输入中文的问题），
 *        把每一步的工具调用、回答、回执、耗时写到 files/e2e.txt；结束后清理测试产生的待办、闹钟、日程，
 *        并把当前会话切回测试前的会话。
 */
class TestHookReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        val app = context.applicationContext
        when (intent.action) {
            ACTION_E2E -> AssistantRuntime.scope.launch { runE2E(app) }
            // 发一句话（ASCII 即可）并把结果写到 files/e2e-chat.txt：用来在生成中途杀 :llm 做故障注入
            ACTION_CHAT -> {
                fun dec(x: String) = String(android.util.Base64.decode(x, android.util.Base64.DEFAULT), Charsets.UTF_8)
                val t = intent.getStringExtra("b64")?.let { dec(it) } ?: intent.getStringExtra("text")
                // seed = 逗号分隔的 base64 中文句子：先在同一个临时会话里聊完，再开新会话发 text，看第一轮有没有带上"上次聊天回顾"；结束后删掉两个临时会话
                val seed = intent.getStringExtra("seed")?.split(",")?.filter { it.isNotBlank() }?.map { dec(it) } ?: emptyList()
                if (t != null) AssistantRuntime.scope.launch { runChat(app, t, seed, reuse = intent.getBooleanExtra("reuse", false)) }
            }
            // 改心跳设置并复位判定状态，用来当场验证"设置是否生效"；begin/end 把测试消息隔离到临时会话并还原设置
            ACTION_HB -> hbControl(app, intent)
            // 冻结实验：e1 = 助手提醒（setAlarmClock）N 分钟后能不能在后台触发；e2 = JobScheduler 任务能不能在后台被唤醒
            ACTION_PROBE -> probe(app, intent)
            // 建一个 N 分钟后的系统日历日程（标题 UTF-8 base64，绕开 adb 传中文）；clean=true 删除标题含"自动测试"的日程。用于当场验证心跳是否带上它
            // 真机 UI 测试：把文字打进前台聊天界面并点发送（b64 = UTF-8 base64 中文；new=true 先开新会话）
            ACTION_TYPE -> {
                val inj = AssistantRuntime.uiInject
                if (intent.getBooleanExtra("new", false)) inj?.invoke("new", "")
                intent.getStringExtra("b64")?.let { inj?.invoke("send", String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8)) }
            }
            // 候选记忆的复读间隔：gap=毫秒（测试用 10000 = 10 秒）；不带 gap 则恢复默认 10 分钟
            ACTION_CAND -> {
                val s = AppSettings(app)
                s.candidateGapMs = intent.getLongExtra("gap", 10 * 60_000L)
                if (intent.getBooleanExtra("clear", false)) CandidateStore(app).save(emptyList())
            }
            // 专注模式：start（带秒级参数 durationSec/graceSec/coldWarSec/calmBonusSec/diff/advanced）、ev=left|returned|screenon|distract 模拟事件、stop
            ACTION_FOCUS -> {
                if (intent.getBooleanExtra("statsClear", false)) FocusStatsStore.clear(app)
                if (intent.hasExtra("statsSeed")) FocusStatsStore.seedDays(app, intent.getIntExtra("statsSeed", 0))
                if (intent.hasExtra("gentleMin")) FocusRuntime.distressedUntilMs = System.currentTimeMillis() + intent.getIntExtra("gentleMin", 0) * 60_000L
                when {
                    intent.hasExtra("statsSeed") || intent.getBooleanExtra("statsClear", false) || intent.hasExtra("gentleMin") -> {}
                    intent.getBooleanExtra("stop", false) -> FocusService.stop(app)
                    intent.hasExtra("ev") -> FocusRuntime.debug?.invoke(intent.getStringExtra("ev") ?: "", intent.getStringExtra("label"))
                    else -> FocusService.start(app, intent.extras)
                }
            }
            // 提醒：afterSec=几秒后响（b64=内容，repeat=重复规则，alarm=是否助手闹钟）；snoozeSec=把「再提醒」间隔改成几秒（0 恢复默认）；
            // snooze=立刻把最近响过的提醒推迟几秒；dump=把提醒列表写到 files/e2e-remind.txt；clean=取消内容含「自动测试」的提醒
            ACTION_REMIND -> {
                val out = File(app.filesDir, "e2e-remind.txt")
                if (intent.hasExtra("snoozeSec")) AppSettings(app).snoozeSecOverride = intent.getIntExtra("snoozeSec", 0)
                if (intent.hasExtra("afterSec")) {
                    val text = intent.getStringExtra("b64")?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) } ?: "自动测试提醒"
                    val r = ReminderScheduler.schedule(app, text, System.currentTimeMillis() + intent.getIntExtra("afterSec", 10) * 1000L,
                        intent.getBooleanExtra("alarm", false), intent.getStringExtra("repeat") ?: "")
                    out.appendText("scheduled ok=${r.ok} id=${r.reminder?.id} text=$text repeat=${intent.getStringExtra("repeat")} err=${r.error}\n")
                }
                if (intent.hasExtra("snooze")) {
                    val last = ReminderScheduler.lastFired(app)
                    val r = if (last != null) ReminderScheduler.snooze(app, last.id, intent.getIntExtra("snooze", 10)) else null
                    out.appendText("snooze last=${last?.id} -> ${r?.triggerAt} snoozed=${r?.snoozed}\n")
                }
                if (intent.getBooleanExtra("clean", false)) {
                    val n = ReminderScheduler.all(app).filter { it.text.contains("自动测试") && !it.cancelled && it.firedAt == 0L }.count { ReminderScheduler.cancel(app, it.id) }
                    out.appendText("cleaned $n\n")
                }
                if (intent.getBooleanExtra("dump", false)) {
                    val now = System.currentTimeMillis()
                    out.writeText(ReminderScheduler.all(app).joinToString("\n") { r ->
                        "#${r.id} 「${r.text}」 in=${(r.triggerAt - now) / 1000}s fired=${r.firedAt != 0L} cancelled=${r.cancelled} repeat='${r.repeat}' snoozed=${r.snoozed} alarm=${r.alarm}"
                    } + "\n")
                }
                if (intent.getBooleanExtra("reset", false)) out.writeText("")
            }
            // 探测系统天气应用（vivo 天气）的内容提供者：本应用（普通应用身份）能不能读、有哪些表和列。只写表名/列名/行数/错误，不写城市等位置信息
            ACTION_WPROBE -> AssistantRuntime.scope.launch {
                val out = File(app.filesDir, "e2e-wprobe.txt")
                val sb = StringBuilder()
                val auth = "content://com.vivo.weather.provider/"
                for (t in listOf("localweather", "weather_widget", "currentcity", "weather_index", "hourdata", "weather_News", "weather_config", "alert", "cityAQI", "weathermessage", "usualcity", "rainnotify", "cityorder")) {
                    try {
                        app.contentResolver.query(android.net.Uri.parse(auth + t), null, null, null, null)?.use { c ->
                            sb.append("OK  $t rows=${c.count} cols=${c.columnNames.joinToString(",")}\n")
                        } ?: sb.append("NULL $t\n")
                    } catch (e: Exception) {
                        sb.append("ERR $t ${e.javaClass.simpleName}: ${e.message?.take(160)}\n")
                    }
                }
                out.writeText(sb.toString())
            }
            // 天气：src/net/city/ttlSec 改设置；fake=b64 的 Open-Meteo 响应（免联网测试）；dump=按设置取一次并把三天的回答写到 files/e2e-weather.txt；clear=清联网缓存
            ACTION_WEATHER -> AssistantRuntime.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val st = AppSettings(app)
                val out = File(app.filesDir, "e2e-weather.txt")
                if (intent.hasExtra("sys")) st.weatherUseSystem = intent.getBooleanExtra("sys", true)
                if (intent.hasExtra("net")) st.weatherNet = intent.getBooleanExtra("net", false)
                intent.getStringExtra("city")?.let { st.weatherCity = String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
                if (intent.getBooleanExtra("clearCity", false)) st.weatherCity = ""
                if (intent.hasExtra("ttlSec")) st.weatherTtlOverrideSec = intent.getIntExtra("ttlSec", 0)
                intent.getStringExtra("fake")?.let { WeatherClient.fakeBody = if (it == "-") null else String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }
                if (intent.getBooleanExtra("clear", false)) File(app.filesDir, "weather_net.json").delete()
                if (intent.getBooleanExtra("dump", false)) {
                    val t0 = System.currentTimeMillis()
                    val r = WeatherClient.get(app)
                    val sb = StringBuilder("sys=${st.weatherUseSystem} net=${st.weatherNet} ttl=${st.weatherTtlOverrideSec} 用时=${System.currentTimeMillis() - t0}ms\n")
                    val s = r.snapshot
                    if (s == null) sb.append("无数据：${r.note}\n") else {
                        sb.append("来源=${s.source} 城市=${if (s.source == "手机天气") "(手机天气里的城市)" else s.city} 取数时间=${s.fetchedAtMs} 天数=${s.days.size}\n")
                        (0..2).forEach { sb.append(Weather.answer(s, it, System.currentTimeMillis()).let { a -> if (s.source == "手机天气") a.replace(s.city, "〔城市〕") else a }).append('\n') }
                    }
                    out.writeText(sb.toString())
                }
            }
            // 步数：enable=开关；inject=累计值 + dayShift=相对今天的天数（-1 = 昨天）模拟采样；read=真读一次计步器；dump=写 files/e2e-steps.txt；clear=清空
            ACTION_STEPS -> AssistantRuntime.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val out = File(app.filesDir, "e2e-steps.txt")
                val st = AppSettings(app)
                if (intent.hasExtra("assume")) StepSource.debugAssume = intent.getBooleanExtra("assume", false)
                if (intent.hasExtra("enable")) st.stepsEnabled = intent.getBooleanExtra("enable", false)
                if (intent.getBooleanExtra("clear", false)) StepSource.clear(app)
                if (intent.hasExtra("inject")) StepSource.inject(app, System.currentTimeMillis() + intent.getIntExtra("dayShift", 0) * 86_400_000L + intent.getIntExtra("hourShift", 0) * 3_600_000L, intent.getLongExtra("inject", 0L))
                val sb = StringBuilder()
                if (intent.getBooleanExtra("read", false)) sb.append("权限=${StepSource.hasPermission(app)} 传感器=${StepSource.hasSensor(app)} 读到累计=${StepSource.readTotal(app)}\n")
                if (intent.getBooleanExtra("dump", false) || sb.isNotEmpty()) {
                    sb.append("开关=${st.stepsEnabled} 摘要=${StepSource.summary(app)} 记录天数=${StepSource.load(app).first.days}\n")
                    out.writeText(sb.toString())
                }
            }
            ACTION_EVENT -> AssistantRuntime.scope.launch {
                val out = File(app.filesDir, "e2e-event.txt")
                if (intent.getBooleanExtra("clean", false)) {
                    val n = try {
                        app.contentResolver.delete(CalendarContract.Events.CONTENT_URI, "${CalendarContract.Events.TITLE} LIKE ?", arrayOf("%自动测试%"))
                    } catch (e: Exception) { -1 }
                    out.writeText("cleaned $n events\n")
                } else {
                    val title = intent.getStringExtra("b64")?.let { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) } ?: "自动测试日程"
                    if (intent.getBooleanExtra("ongoing", false)) {
                        // 已经开始 2 分钟、还要 20 分钟的"正在进行的会议"（正常路径不允许写过去的日程，这里直接插库，仅调试用）
                        val calId = Actions.findWritableCalendar(app)
                        val start = System.currentTimeMillis() - 2 * 60_000L
                        val uri = if (calId == null) null else app.contentResolver.insert(CalendarContract.Events.CONTENT_URI, android.content.ContentValues().apply {
                            put(CalendarContract.Events.DTSTART, start); put(CalendarContract.Events.DTEND, start + 22 * 60_000L)
                            put(CalendarContract.Events.TITLE, title); put(CalendarContract.Events.CALENDAR_ID, calId)
                            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                        })
                        out.writeText("ongoing title=$title uri=$uri\n")
                        return@launch
                    }
                    val at = java.time.LocalDateTime.now().plusMinutes(intent.getIntExtra("minutes", 2).toLong()).withSecond(0).withNano(0)
                    val r = Actions.addCalendar(app, title, at, 30, 0)
                    out.writeText("title=$title at=$at status=${r.status}\nreceipt=${r.receipt}\n")
                }
            }
            // N 分钟后的闹钟，走应用里真正的 Actions.setAlarm（含选时钟/核验），回执写 files/e2e-alarm.txt：当场出结果
            ACTION_ALARM -> AssistantRuntime.scope.launch {
                val n = intent.getIntExtra("minutes", 3)
                val at = java.time.LocalDateTime.now().plusMinutes(n.toLong()).withSecond(0).withNano(0)
                val r = Actions.setAlarm(app, at, "自动测试闹钟")
                File(app.filesDir, "e2e-alarm.txt").writeText(
                    "target=$at status=${r.status}\nreceipt=${r.receipt}\n"
                )
            }
        }
    }

    private fun probe(ctx: Context, intent: Intent) {
        val out = File(ctx.filesDir, "e2e-probe.txt")
        val n = intent.getIntExtra("minutes", 2)
        val at = System.currentTimeMillis() + n * 60_000L
        val hhmmss = java.text.SimpleDateFormat("HH:mm:ss")
        when (intent.getStringExtra("kind")) {
            "e1" -> {
                val r = ReminderScheduler.schedule(ctx, "E1实验", at, alarm = false)
                out.appendText("E1 reminder(setAlarmClock) scheduled ok=${r.ok} exact=${r.exact} id=${r.reminder?.id} for ${hhmmss.format(java.util.Date(at))}\n")
            }
            "e2" -> {
                val js = ctx.getSystemService(android.app.job.JobScheduler::class.java)
                val rc = js.schedule(
                    android.app.job.JobInfo.Builder(9001, android.content.ComponentName(ctx, ProbeJobService::class.java))
                        .setMinimumLatency(n * 60_000L)
                        .setOverrideDeadline(n * 60_000L + 30_000L)
                        .build()
                )
                out.appendText("E2 job scheduled rc=$rc (1=ok) for ${hhmmss.format(java.util.Date(at))}\n")
            }
            "clear" -> out.writeText("")
        }
    }

    private fun hbControl(ctx: Context, intent: Intent) {
        val s = AppSettings(ctx)
        val out = File(ctx.filesDir, "e2e-hb.txt")
        fun w(x: String) = out.appendText(x + "\n")
        val bk = ctx.getSharedPreferences("hb_test_backup", Context.MODE_PRIVATE)
        // 已经有一份备份（上一次测试还没 end）时不再覆盖：否则会把"测试值"当成"原设置"备份，还原就还原成测试值了（2026-10-19 真机踩过）
        if (intent.getBooleanExtra("begin", false) && bk.contains("interval")) {
            w("BEGIN 忽略：上一次测试还没 end，沿用已有备份")
        } else if (intent.getBooleanExtra("begin", false)) {
            out.writeText("")
            bk.edit().clear()
                .putInt("interval", s.heartbeatIntervalMin).putInt("same", s.heartbeatSameContentMin)
                .putString("qs", s.quietStart).putString("qe", s.quietEnd).putBoolean("down", s.autoDownshift)
                .putString("conv", s.currentConversationId).apply()
            Conversations.startNew(s, MemoryStore(ctx))
            w("BEGIN 原设置 interval=${s.heartbeatIntervalMin} same=${s.heartbeatSameContentMin} quiet=${s.quietStart}-${s.quietEnd} 降频=${s.autoDownshift}")
        }
        if (intent.hasExtra("interval")) s.heartbeatIntervalMin = intent.getIntExtra("interval", s.heartbeatIntervalMin)
        if (intent.hasExtra("same")) s.heartbeatSameContentMin = intent.getIntExtra("same", s.heartbeatSameContentMin)
        intent.getStringExtra("qs")?.let { s.quietStart = it }
        intent.getStringExtra("qe")?.let { s.quietEnd = it }
        if (intent.hasExtra("down")) s.autoDownshift = intent.getBooleanExtra("down", true)
        if (intent.getBooleanExtra("reset", false)) {
            s.saveHbState(now = 0L, firedToday = 0, firedDay = "", unresponded = 0)
            s.hbLastContentKey = ""; s.hbLastContentKeyAt = 0L; s.hbLastUserMessageAt = 0L; s.hbRecentKeys = emptyMap(); s.hbKeyCounts = HeartbeatPolicy.KeyCounts("", emptyMap())
        }
        if (intent.getBooleanExtra("end", false)) {
            s.heartbeatIntervalMin = bk.getInt("interval", s.heartbeatIntervalMin)
            s.heartbeatSameContentMin = bk.getInt("same", 240)
            s.quietStart = bk.getString("qs", "23:30") ?: "23:30"
            s.quietEnd = bk.getString("qe", "07:30") ?: "07:30"
            s.autoDownshift = bk.getBoolean("down", true)
            bk.getString("conv", null)?.takeIf { it.isNotBlank() }?.let { s.currentConversationId = it }
            w("END 已还原 interval=${s.heartbeatIntervalMin} same=${s.heartbeatSameContentMin} quiet=${s.quietStart}-${s.quietEnd}")
            bk.edit().clear().apply()    // 备份用完即清，下一次 begin 才会重新备份
        }
        w("SET interval=${s.heartbeatIntervalMin} same=${s.heartbeatSameContentMin} quiet=${s.quietStart}-${s.quietEnd} 降频=${s.autoDownshift} at ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}")
        HeartbeatService.start(ctx)
        HeartbeatScheduler.arm(ctx, 3_000L)   // 3 秒后立刻判定一次，不用等下一拍
    }

    private suspend fun runChat(ctx: Context, text: String, seed: List<String> = emptyList(), reuse: Boolean = false) {
        val out = File(ctx.filesDir, "e2e-chat.txt")
        fun w(s: String) = out.appendText(s + "\n")
        out.writeText("")
        val engine = LlmEngine.get(ctx)
        val settings = AppSettings(ctx)
        val memory = MemoryStore(ctx)
        val loop = AgentLoop(engine, ToolRegistry(ctx), { settings.persona }, memory, settings)
        val prev = settings.currentConversationId
        val temps = ArrayList<String>()
        if (seed.isNotEmpty()) {
            val seedConv = Conversations.startNew(settings, memory)
            temps.add(seedConv)
            AssistantRuntime.busy = true
            try {
                for (line in seed) {
                    val r = try { loop.run(ctx, line, seedConv) } catch (e: Exception) { AgentLoop.Result("EXCEPTION ${e.message}", emptyList()) }
                    w("SEED「$line」→ ${r.answer.replace('\n', '/').take(80)}")
                }
            } finally { AssistantRuntime.busy = false }
        }
        // reuse=true：沿用当前会话（测「上一句问、下一句答」这类跨轮流程要用同一个会话）
        val conv = if (reuse) Conversations.current(settings, memory) else Conversations.startNew(settings, memory)
        if (!reuse) temps.add(conv)
        val t0 = System.currentTimeMillis()
        w("CHAT START state=${engine.state.value.javaClass.simpleName} text=$text")
        AssistantRuntime.busy = true
        try {
            val r = try { loop.run(ctx, text, conv) } catch (e: Exception) { AgentLoop.Result("EXCEPTION ${e.javaClass.simpleName}: ${e.message}", emptyList()) }
            w("用时 ${(System.currentTimeMillis() - t0) / 1000}s state=${engine.state.value.javaClass.simpleName}")
            r.steps.forEach { w("  · ${it.tool} [${it.status}] ${it.result.take(120)}") }
            w("回答: " + r.answer.replace("\n", " / ").take(300))
        } finally {
            AssistantRuntime.busy = false
            if (prev.isNotBlank()) settings.currentConversationId = prev
            if (seed.isNotEmpty()) temps.forEach { try { memory.deleteConversation(it) } catch (_: Exception) {} }
            w("END")
        }
    }

    private suspend fun runE2E(ctx: Context) {
        val out = File(ctx.filesDir, "e2e.txt")
        out.writeText("")
        fun w(s: String) = out.appendText(s + "\n")
        val t0 = System.currentTimeMillis()
        w("E2E START " + java.util.Date())

        // 0) 回归语料
        val st = AssistantSelfTest.runAll()
        w("[selftest] " + st.render().trim())

        // 1) 等模型就绪（最多 120 秒）
        val engine = LlmEngine.get(ctx)
        val ready = withTimeoutOrNull(120_000) {
            while (engine.state.value !is InferenceEngine.State.ModelReady) delay(500)
            true
        } == true
        w("[engine] ready=$ready state=${engine.state.value.javaClass.simpleName}")
        if (AssistantRuntime.busy) { w("ABORT: 正在进行一轮对话"); w("END"); return }

        val settings = AppSettings(ctx)
        val memory = MemoryStore(ctx)
        val registry = ToolRegistry(ctx)
        val loop = AgentLoop(engine, registry, { settings.persona }, memory, settings)
        val prevConv = settings.currentConversationId
        val conv = Conversations.startNew(settings, memory)
        val remindersBefore = ReminderScheduler.all(ctx).map { it.id }.toSet()
        val todosBefore = registry.todoStore.all().map { it.id }.toSet()
        val factsBefore = memory.allFacts().map { it.first }.toSet()
        w("[persona] ${settings.persona.id} conv=$conv")

        AssistantRuntime.busy = true
        try {
            val cases = listOf(
                "现在几点了",
                "两分钟后提醒我测试提醒",
                "后天早上6点半的闹钟",
                "设个闹钟",
                "加一条待办：自动测试待办",
                "我有什么待办",
                "明天下午3点的日程：自动测试日程",
                // 一句话多事录入（2026-10-19）：先列清单，回复后才写入；"算了"一件都不写
                "明早9点自动测试开会，下午3点自动测试牙医，周五自动测试交报告",
                "去掉第2条",
                "后天上午10点自动测试面试，大后天下午2点自动测试体检",
                "算了",
                "你好呀",
                "今天上班好累啊",
                "随便聊聊天气",
                "我习惯晚上11点睡",
                // 程序先查记忆（2026-10-19）：这些由程序用真实记录回答，不交给模型，应该秒回
                "你还记得我喜欢喝什么吗",
                "我养了一只猫你记得吗",
                "你了解我多少",
                "你记得我昨天跟你说的事吗",
                "今天天气怎么样",
            )
            for ((i, c) in cases.withIndex()) {
                val s = System.currentTimeMillis()
                val r = try { loop.run(ctx, c, conv) } catch (e: Exception) { AgentLoop.Result("EXCEPTION ${e.javaClass.simpleName}: ${e.message}", emptyList()) }
                w("\n===== [${i + 1}] $c  (${(System.currentTimeMillis() - s) / 1000}s)")
                r.steps.forEach {
                    w("  · ${it.tool} [${it.status}] ${it.result.replace("\n", " / ").take(160)}")
                    // 守卫删掉了哪句话：证据，不能只看"删了 N 句"
                    if (it.tool == "claim_guard") w("      被删的句子: " + it.argsJson.replace("\n", " / "))
                }
                w("  回答: " + r.answer.replace("\n", " / ").take(300))
                if (r.receipts.isNotBlank()) w("  回执: " + r.receipts.replace("\n", " / "))
            }
            // 完成测试待办（顺带测"第N条做完了"）
            registry.todoStore.all().filter { it.id !in todosBefore && !it.done }.forEach { t ->
                val r = loop.run(ctx, "第${t.id}条做完了", conv)
                w("\n===== [完成待办] 第${t.id}条做完了")
                r.steps.forEach { w("  · ${it.tool} [${it.status}] ${it.result.take(160)}") }
                w("  回答: " + r.answer.replace("\n", " / ").take(200))
            }

            // 2) 等"两分钟后的提醒"真的触发
            val testRem = ReminderScheduler.all(ctx).filter { it.id !in remindersBefore && !it.alarm }
            w("\n[reminder] 本次登记的提醒: " + testRem.joinToString { "#${it.id} ${it.text} at ${java.util.Date(it.triggerAt)}" })
            val fired = withTimeoutOrNull(200_000) {
                while (ReminderScheduler.all(ctx).filter { it.id in testRem.map { r -> r.id } }.any { it.firedAt == 0L }) delay(2000)
                true
            } == true
            ReminderScheduler.all(ctx).filter { it.id in testRem.map { r -> r.id } }.forEach {
                w("[reminder] #${it.id} 计划 ${java.util.Date(it.triggerAt)}  实际触发 ${if (it.firedAt > 0) java.util.Date(it.firedAt) else "未触发"}  (偏差 ${if (it.firedAt > 0) (it.firedAt - it.triggerAt) / 1000 else -1}s)")
            }
            w("[reminder] fired=$fired")
        } finally {
            AssistantRuntime.busy = false
            // 3) 清理：取消测试登记的助手闹钟/未触发提醒、删除测试待办与日程、切回原会话
            ReminderScheduler.all(ctx).filter { it.id !in remindersBefore && it.firedAt == 0L }.forEach {
                w("[cleanup] 取消提醒 #${it.id} ${it.text}: ${ReminderScheduler.cancel(ctx, it.id)}")
            }
            registry.todoStore.all().filter { it.id !in todosBefore }.forEach {
                w("[cleanup] 删除待办 #${it.id}: ${registry.todoStore.remove(it.id)}")
            }
            // 测试期间新增的记忆事实不留在真实记忆里（已存在的事实只是被刷新，不动）
            try {
                memory.allFacts().map { it.first }.filter { it !in factsBefore }.forEach {
                    memory.forget(it); w("[cleanup] 删除测试新增的记忆事实「$it」")
                }
            } catch (e: Exception) { w("[cleanup] 清理记忆事实失败 ${e.message}") }
            try {
                val n = ctx.contentResolver.delete(
                    CalendarContract.Events.CONTENT_URI,
                    "${CalendarContract.Events.TITLE} LIKE ?", arrayOf("%自动测试%")
                )
                w("[cleanup] 删除测试日程 $n 条")
            } catch (e: Exception) { w("[cleanup] 删除日程失败 ${e.message}") }
            if (prevConv.isNotBlank()) settings.currentConversationId = prevConv
            w("[cleanup] 已切回原会话 $prevConv（测试会话 $conv 保留在历史会话里，可查看）")
            w("\nE2E 用时 ${(System.currentTimeMillis() - t0) / 1000}s")
            w("END")
            try { ctx.sendBroadcast(Intent(HeartbeatService.ACTION_HEARTBEAT).setPackage(ctx.packageName)) } catch (_: Exception) {}
        }
    }

    companion object {
        const val ACTION_E2E = "com.minicpm.assistant.E2E"
        const val ACTION_CHAT = "com.minicpm.assistant.E2E_CHAT"
        const val ACTION_HB = "com.minicpm.assistant.E2E_HB"
        const val ACTION_ALARM = "com.minicpm.assistant.E2E_ALARM"
        const val ACTION_PROBE = "com.minicpm.assistant.E2E_PROBE"
        const val ACTION_TYPE = "com.minicpm.assistant.E2E_TYPE"
        const val ACTION_CAND = "com.minicpm.assistant.E2E_CAND"
        const val ACTION_FOCUS = "com.minicpm.assistant.E2E_FOCUS"
        const val ACTION_REMIND = "com.minicpm.assistant.E2E_REMIND"
        const val ACTION_WPROBE = "com.minicpm.assistant.E2E_WPROBE"
        const val ACTION_WEATHER = "com.minicpm.assistant.E2E_WEATHER"
        const val ACTION_STEPS = "com.minicpm.assistant.E2E_STEPS"
        const val ACTION_EVENT = "com.minicpm.assistant.E2E_EVENT"
    }
}
