package com.example.llama

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.View
import android.view.MenuItem
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.InferenceEngine
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 会话窗口 —— 2026-10-18 重构。
 *
 * 修掉的生命周期问题：
 *   · 一轮对话不再绑在 Activity 上（见 AssistantRuntime），切走 App 不会被取消
 *   · 不再在 onDestroy 里 destroy() 进程级单例引擎——旧版按返回键再打开（心跳服务让进程一直活着），
 *     拿到的是已销毁的引擎，必然"模型加载失败"
 *   · 换人设不再重载 2.4GB 模型：processRawPrompt 每轮都会整段重算 prompt，系统提示本来就随每轮传入，
 *     重载既慢（30–40 秒）又毫无必要。现在换人设 = 开新会话，瞬间完成
 *   · 模型没加载好时也能发消息：闹钟/提醒/待办走确定性路径，不需要模型
 */
class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var sendFab: FloatingActionButton
    private lateinit var avatar: AvatarView

    private lateinit var settings: AppSettings
    private lateinit var memory: MemoryStore
    private var engine: InferenceEngine? = null
    private var registry: ToolRegistry? = null
    private var agentLoop: AgentLoop? = null

    private var shownPersonaId: String? = null

    // 头像表情：由程序已知的事实决定（见 Faces.pick）；刚聊到的情绪保持一会儿再回到待机
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var mood: FaceState? = null
    private var moodUntil = 0L
    private val faceTick = object : Runnable { override fun run() { refreshFace(); ui.postDelayed(this, 3000) } }
    private var lastRestoredSignature = ""
    private val messages = mutableListOf<Message>()
    private val adapter = MessageAdapter(messages)

    /** 心跳 / 到点提醒落库后会发广播，App 开着时立刻刷新 */
    private val hbReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
            if (!AssistantRuntime.busy) restoreHistory(force = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = AppSettings(this)
        memory = MemoryStore(applicationContext)

        toolbar = findViewById(R.id.toolbar)
        statusTv = findViewById(R.id.gguf)
        messagesRv = findViewById(R.id.messages)
        userInputEt = findViewById(R.id.user_input)
        sendFab = findViewById(R.id.fab)
        avatar = findViewById(R.id.avatar)
        avatar.setAnimated(true)

        setSupportActionBar(toolbar)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = adapter
        sendFab.setOnClickListener { sendMessage() }

        // 不等模型：确定性指令现在就能用
        setBusy(AssistantRuntime.busy)
        shownPersonaId = settings.personaId
        applySkin()
        updateToolbarTitle()
        restoreHistory(force = true)

        lifecycleScope.launch(Dispatchers.Default) {
            val e = LlmEngine.get(applicationContext)
            engine = e
            registry = ToolRegistry(applicationContext)
            agentLoop = AgentLoop(e, registry!!, { settings.persona }, memory, settings)
            // 状态栏跟随引擎状态：推理进程被释放/被系统回收后不能还显示"就绪"
            lifecycleScope.launch(Dispatchers.Main) {
                e.state.collect { st ->
                    when (st) {
                        is InferenceEngine.State.Initialized ->
                            statusTv.text = "模型未加载（发消息会自动加载；闹钟、提醒、待办可直接用）"
                        is InferenceEngine.State.LoadingModel ->
                            statusTv.text = "模型加载中…（闹钟、提醒、待办已可直接使用）"
                        is InferenceEngine.State.ModelReady ->
                            statusTv.text = "就绪 · ${settings.modelFileName}"
                        else -> {}
                    }
                }
            }
            ensureModelLoaded(e)
        }
    }

    /** 引擎是进程级单例：已经加载好就直接用，正在加载就等，否则才加载 */
    private suspend fun ensureModelLoaded(e: InferenceEngine) = ModelLifecycle.mutex.withLock { ensureModelLoadedLocked(e) }

    private suspend fun ensureModelLoadedLocked(e: InferenceEngine) {
        when (e.state.value) {
            is InferenceEngine.State.ModelReady,
            is InferenceEngine.State.Generating,
            is InferenceEngine.State.ProcessingUserPrompt,
            is InferenceEngine.State.ProcessingSystemPrompt -> { onModelReady(); return }
            is InferenceEngine.State.LoadingModel -> {
                setStatus("模型加载中…（闹钟、提醒、待办已可直接使用）")
                e.state.first { it is InferenceEngine.State.ModelReady }
                onModelReady()
                return
            }
            else -> {}
        }
        val modelsDir = File(filesDir, DIRECTORY_MODELS)
        // 配置的模型不存在（例如新装机还没放文件）就退回最初的 model.gguf，免得"模型消失"
        val file = File(modelsDir, settings.modelFileName).takeIf { it.exists() && it.length() > 0 }
            ?: File(modelsDir, AppSettings.FALLBACK_MODEL_FILE)
        if (!settings.autoLoadModel) { setStatus("自动加载已关闭（闹钟、提醒、待办仍可使用）"); return }
        if (!file.exists() || file.length() == 0L) { setStatus("未找到模型文件：${file.name}（闹钟、提醒、待办仍可使用）"); return }
        val mb = file.length() / 1024 / 1024
        trace("autoLoad: 开始加载 ${file.name} ($mb MB)")
        setStatus("正在加载 ${file.name}（$mb MB）…闹钟、提醒、待办已可直接使用")
        try {
            if (e.state.value is InferenceEngine.State.Error) e.cleanUp()
            e.loadModel(file.path)
            // 引擎要求加载后立刻设一次系统提示；实际对话时系统提示随每轮传入（AgentLoop）
            e.setSystemPrompt(Personas.promptFor(settings.persona))
            trace("autoLoad: 就绪")
            onModelReady()
            if (RUN_TOOL_SMOKE_TEST) toolSmokeTest()
        } catch (ex: Exception) {
            trace("autoLoad: 失败 " + (ex.message ?: ex.javaClass.simpleName))
            Log.e(TAG, "model load failed", ex)
            setStatus("模型加载失败：${ex.message}（闹钟、提醒、待办仍可使用）")
        }
    }

    private suspend fun onModelReady() = withContext(Dispatchers.Main) {
        statusTv.text = "就绪 · ${settings.modelFileName}"
        userInputEt.hint = "和 ${settings.persona.selfRef} 说点什么…"
    }

    private suspend fun setStatus(s: String) = withContext(Dispatchers.Main) { statusTv.text = s }

    private fun updateToolbarTitle() {
        val p = settings.persona
        supportActionBar?.title = p.name
        avatar.setPersona(p.id)
        refreshFace()
        userInputEt.hint = "和 ${p.selfRef} 说点什么…"
    }

    /** 套用当前皮肤：页面底色、顶栏、输入栏、发送按钮、气泡与头像外圈 */
    private fun applySkin() {
        val sk = settings.skin()
        SkinUi.applyChrome(this, sk, toolbar, findViewById(R.id.top_bar))
        statusTv.setBackgroundColor(sk.bar); statusTv.setTextColor(sk.textDim)
        findViewById<View>(R.id.input_bar).setBackgroundColor(sk.bar)
        findViewById<View>(R.id.div_top).setBackgroundColor(sk.divider)
        findViewById<View>(R.id.div_bottom).setBackgroundColor(sk.divider)
        userInputEt.setTextColor(sk.text); userInputEt.setHintTextColor(sk.textDim)
        sendFab.backgroundTintList = android.content.res.ColorStateList.valueOf(sk.accent)
        sendFab.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        avatar.setRing(Skin.blend(sk.bar, sk.accent, 0.55f))
        adapter.skin = sk
        adapter.personaId = settings.personaId
        adapter.notifyDataSetChanged()
    }

    /** 头像表情 + 副标题。程序已知的事实说了算：模型是否在忙、专注模式是否进行/冷战、是否到了免打扰时段、刚聊到的情绪 */
    private fun refreshFace() {
        if (!::avatar.isInitialized) return
        val p = settings.persona
        val pack = PersonaPacks.packOf(p.id)
        val now = System.currentTimeMillis()
        if (mood != null && now > moodUntil) mood = null
        val fs = try { FocusRuntime.session?.snapshot()?.first } catch (_: Exception) { null }
        val focusRunning = fs != null && !fs.finished
        val cold = focusRunning && fs!!.coldUntilMs > now
        val loading = engine?.state?.value is InferenceEngine.State.LoadingModel
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val quiet = Faces.isQuiet(nowMin, Faces.hhmmToMin(settings.quietStart, 23 * 60 + 30), Faces.hhmmToMin(settings.quietEnd, 7 * 60 + 30))
        val face = Faces.pick(pack.roast, cold, focusRunning, AssistantRuntime.busy || loading, mood, quiet)
        avatar.setFace(face)
        supportActionBar?.subtitle = Faces.caption(face, p.selfRef, p.emotionalValue.joinToString(" · "))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_NEW, 0, "新会话").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_HISTORY, 1, "历史会话").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_FOCUS, 2, "专注模式").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_SETTINGS, 3, "设置")
            .setIcon(android.R.drawable.ic_menu_preferences)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_SETTINGS -> { startActivity(Intent(this, SettingsActivity::class.java)); return true }
            MENU_NEW -> {
                if (AssistantRuntime.busy) { Toast.makeText(this, "等这一轮说完再开新会话", Toast.LENGTH_SHORT).show(); return true }
                Conversations.startNew(settings, memory)
                restoreHistory(force = true)
                return true
            }
            MENU_HISTORY -> { showHistory(); return true }
            MENU_FOCUS -> { startActivity(Intent(this, FocusActivity::class.java)); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showHistory() {
        val list = memory.listConversations(30)
        if (list.isEmpty()) { Toast.makeText(this, "还没有历史会话", Toast.LENGTH_SHORT).show(); return }
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
        val current = settings.currentConversationId
        val sk = settings.skin()
        val d = resources.displayMetrics.density
        val adapterH = object : android.widget.ArrayAdapter<MemoryStore.Conversation>(this, 0, list) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val c = getItem(position)!!
                val row = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
                }
                row.addView(AvatarView(context).apply {
                    setPersona(c.personaId); setFace(if (c.id == current) FaceState.HAPPY else FaceState.IDLE)
                    layoutParams = android.widget.LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply { marginEnd = (12 * d).toInt() }
                })
                val col = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }
                col.addView(TextView(context).apply { text = (if (c.id == current) "▶ " else "") + c.title.ifBlank { "（空会话）" }; textSize = 15f; setTextColor(android.graphics.Color.TRANSPARENT.let { com.google.android.material.color.MaterialColors.getColor(this@MainActivity, android.R.attr.textColorPrimary, sk.text) }); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                col.addView(TextView(context).apply { text = fmt.format(java.util.Date(c.updatedAt)) + " · ${c.messageCount} 条"; textSize = 12f; setTextColor(com.google.android.material.color.MaterialColors.getColor(this@MainActivity, android.R.attr.textColorSecondary, sk.textDim)) })
                row.addView(col)
                return row
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("历史会话（点击切换，人设随会话切换）")
            .setAdapter(adapterH) { _, which ->
                val c = list[which]
                if (AssistantRuntime.busy) { Toast.makeText(this, "等这一轮说完再切换", Toast.LENGTH_SHORT).show(); return@setAdapter }
                if (Conversations.switchTo(settings, memory, c.id)) {
                    shownPersonaId = settings.personaId
                    applySkin()
                    updateToolbarTitle()
                    restoreHistory(force = true)
                } else {
                    // 升级前的旧会话没有人设信息：只读查看
                    val text = memory.recentMessages(60, c.id).joinToString("\n") { (if (it.role == "user") "我：" else "它：") + it.content.take(80) }
                    MaterialAlertDialogBuilder(this).setTitle(c.title.ifBlank { "旧会话" }).setMessage(text.ifBlank { "（空）" })
                        .setPositiveButton("关闭", null).show()
                }
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    override fun onResume() {
        AssistantRuntime.uiInject = { action, text ->
            runOnUiThread {
                if (action == "new") { if (!AssistantRuntime.busy) { Conversations.startNew(settings, memory); restoreHistory(force = true) } }
                else { userInputEt.setText(text); sendMessage() }
            }
        }
        super.onResume()
        // 系统会在 onCreate 之后用清单里的应用名覆盖标题，所以每次回到前台都重设一次
        applySkin()   // 设置里可能换了皮肤、人设（主题色跟人设走）或改了名
        updateToolbarTitle()
        ui.removeCallbacks(faceTick); ui.post(faceTick)
        ensureHeartbeat()
        settings.hbUnresponded = 0   // 回到 App 就算"看到了"

        // 换了人设：开新会话即可，不重载模型
        if (shownPersonaId != settings.personaId) {
            shownPersonaId = settings.personaId
            updateToolbarTitle()
            if (!AssistantRuntime.busy) {
                Conversations.current(settings, memory)   // 人设不匹配会自动建新会话
                Toast.makeText(this, "已切换到 ${settings.persona.name}（新会话）", Toast.LENGTH_SHORT).show()
            }
        }
        setBusy(AssistantRuntime.busy)
        if (!AssistantRuntime.busy) restoreHistory()
    }

    private fun sendMessage() {
        if (AssistantRuntime.busy) return
        val loop = agentLoop
        if (loop == null) { Toast.makeText(this, "还在初始化，稍等一秒", Toast.LENGTH_SHORT).show(); return }
        // 推理进程被系统回收（或刚从后台回来还没加载）：发消息时顺手拉起，不用用户自己想办法
        engine?.let { e -> if (e.state.value is InferenceEngine.State.Initialized) lifecycleScope.launch(Dispatchers.Default) { ensureModelLoaded(e) } }
        val text = userInputEt.text.toString().trim()
        if (text.isEmpty()) { Toast.makeText(this, "先说点什么吧", Toast.LENGTH_SHORT).show(); return }
        userInputEt.setText("")
        setBusy(true)
        AssistantRuntime.busy = true

        settings.hbLastUserMessageAt = System.currentTimeMillis()
        settings.hbUnresponded = 0
        // 这句话的情绪决定她答完之后的表情（喜事开心、难过安慰），保持一会儿
        mood = Faces.moodOf(text); moodUntil = System.currentTimeMillis() + 80_000L
        val conv = Conversations.current(settings, memory)

        messages.add(Message(UUID.randomUUID().toString(), text, true))
        messages.add(Message(UUID.randomUUID().toString(), "…", false))
        adapter.notifyItemRangeInserted(messages.size - 2, 2)
        messagesRv.scrollToPosition(messages.size - 1)

        val appCtx = applicationContext
        AssistantRuntime.scope.launch {
            val result = try {
                loop.run(appCtx, text, conv) { status ->
                    withContext(Dispatchers.Main) { if (!isDestroyed) statusTv.text = status }
                }
            } catch (e: Exception) {
                AgentLoop.Result("（这一轮出错了：${e.javaClass.simpleName}: ${e.message}。没有执行任何操作。）", emptyList())
            } finally {
                AssistantRuntime.busy = false
            }
            // 这一轮是在后台跑完的：现在可以把模型交还给系统了（见 ModelLifecycle）
            if (!ModelLifecycle.visible) ModelLifecycle.unload(appCtx, "后台一轮结束")
            withContext(Dispatchers.Main) {
                if (isDestroyed) return@withContext
                setBusy(false)
                statusTv.text = if (engine?.state?.value is InferenceEngine.State.ModelReady) "就绪 · ${settings.modelFileName}" else statusTv.text
                if (settings.memoryEnabled) {
                    restoreHistory(force = true)
                } else {
                    val last = messages.size - 1
                    if (last >= 0) messages[last] = messages[last].copy(content = result.answer)
                    if (result.receipts.isNotBlank()) messages.add(Message(UUID.randomUUID().toString(), result.receipts, false, MemoryStore.KIND_RECEIPT))
                    adapter.notifyDataSetChanged()
                }
                // 透明度：如果守卫删掉了模型的虚假承诺，告诉用户
                // 闲聊里默默删掉即可，只有办事类对话才提示（真机聊天记录里闲聊中反复冒出提示，像程序坏了）
                result.steps.firstOrNull { it.tool == "claim_guard" }?.takeIf { ClaimGuard.noteWanted(text, result.receipts.isNotBlank()) }?.let { g ->
                    messages.add(Message(UUID.randomUUID().toString(), "🛡️ " + g.result, false, MemoryStore.KIND_NOTICE))
                    adapter.notifyItemInserted(messages.size - 1)
                }
                messagesRv.scrollToPosition(messages.size - 1)
            }
        }
    }

    /** 从数据库恢复当前会话（数据库是唯一事实来源） */
    private fun restoreHistory(force: Boolean = false) {
        try {
            val conv = Conversations.current(settings, memory)
            val recent = memory.recentMessages(40, conv)
            val sig = conv + ":" + (recent.lastOrNull()?.id ?: 0) + ":" + recent.size
            if (!force && sig == lastRestoredSignature) return
            lastRestoredSignature = sig
            messages.clear()
            recent.forEach { r ->
                val text = if (r.kind == MemoryStore.KIND_HEARTBEAT) "💭 ${r.content}" else r.content
                messages.add(Message("db-${r.id}", text, r.role == "user", r.kind))
            }
            adapter.notifyDataSetChanged()
            if (messages.isNotEmpty()) messagesRv.scrollToPosition(messages.size - 1)
        } catch (e: Exception) {
            trace("restoreHistory 失败：${e.message}")
        }
    }

    private fun ensureHeartbeat() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        if (settings.heartbeatEnabled) {
            try {
                HeartbeatService.start(this)
            } catch (e: Exception) {
                statusTv.text = "心跳启动失败：${e.message}"
            }
        } else {
            HeartbeatService.stop(this)
            // 心跳关了也要保证到点提醒已登记
            try { ReminderScheduler.rearmAll(applicationContext) } catch (_: Exception) {}
        }
    }

    private fun setBusy(busy: Boolean) {
        userInputEt.isEnabled = !busy
        sendFab.isEnabled = !busy
        refreshFace()
    }

    override fun onStart() {
        super.onStart()
        ModelLifecycle.onForeground()
        // 在后台被卸载过（见 ModelLifecycle）：回到前台重新加载。必须先拿锁再看状态——
        // 卸载可能正在进行，先看状态再拿锁会漏掉重载（真机实测：回到前台后模型没了也没重载）
        engine?.let { e -> lifecycleScope.launch(Dispatchers.Default) { ensureModelLoaded(e) } }
        try {
            ContextCompat.registerReceiver(
                this, hbReceiver, android.content.IntentFilter(HeartbeatService.ACTION_HEARTBEAT),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {
        }
    }

    override fun onStop() {
        ui.removeCallbacks(faceTick)
        AssistantRuntime.uiInject = null
        try { unregisterReceiver(hbReceiver) } catch (_: Exception) {}
        // 注意：不再取消正在进行的一轮（见 AssistantRuntime）
        ModelLifecycle.onBackground(this)
        super.onStop()
    }

    // 注意：不再在 onDestroy 里 engine.destroy() —— 引擎是进程级单例

    private fun trace(msg: String) {
        try {
            File(filesDir, "boot.log").appendText(
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + msg + "\n"
            )
        } catch (_: Exception) {
        }
    }

    /** 端到端冒烟（需要模型）：结果写 files/tooltest.txt */
    private suspend fun toolSmokeTest() {
        val loop = agentLoop ?: return
        val log = File(filesDir, "tooltest.txt")
        try {
            log.writeText("")
            val conv = Conversations.startNew(settings, memory)
            listOf(
                "你好，帮我设定明天 6 点钟的日程，提醒我去充电",
                "加一条待办：明天下午三点开会",
                "设个闹钟：明天早上七点",
                "我喜欢喝冰美式",
                "今天天气怎么样",
            ).forEachIndexed { i, p ->
                val r = loop.run(applicationContext, p, conv)
                log.appendText("===== [${i + 1}] $p\n")
                r.steps.forEach { s -> log.appendText("  · ${s.tool} [${s.status}] ${s.result.take(120)}\n") }
                log.appendText("  回答: ${r.answer.take(160)}\n  回执: ${r.receipts.take(200)}\n\n")
            }
        } catch (e: Exception) {
            log.appendText("FAILED: ${e.message}\n")
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val DIRECTORY_MODELS = "models"
        private const val MENU_SETTINGS = 1001
        private const val MENU_NEW = 1002
        private const val MENU_HISTORY = 1003
        private const val MENU_FOCUS = 1004
        private const val REQ_NOTIF = 3001
        /** 打开后会跑一次端到端冒烟并写 files/tooltest.txt */
        private const val RUN_TOOL_SMOKE_TEST = false
    }
}
