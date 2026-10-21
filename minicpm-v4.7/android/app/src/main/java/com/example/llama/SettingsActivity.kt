package com.example.llama

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * 设置页 —— 按用户要求：所有可调项都在这里，包括人设选择。
 * 布局用代码构建，避免大量 XML。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var content: LinearLayout
    private val sk: Skin get() = settings.skin()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = AppSettings(this)
        content = findViewById(R.id.content)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationOnClickListener { finish() }
        }
        buildUi()
    }

    private fun buildUi() {
        content.removeAllViews()
        SkinUi.applyChrome(this, sk, findViewById(R.id.toolbar))
        buildPersonaSection()
        buildSkinSection()
        buildModelSection()
        buildHeartbeatSection()
        buildMemorySection()
        buildWeatherSection()
        buildStepsSection()
        buildAuditSection()
        buildOnlineSection()
        buildToolsSection()
        buildNotifySection()
        buildSafetySection()
        buildAboutSection()
    }

    // ---------------------------------------------------------------- 人设
    private fun buildPersonaSection() {
        section("人设", "选一个人设 = 选一种相处方式；切换后自动开一个新会话（旧会话在主界面「历史会话」里），无需重新加载模型。名字不好听？点右边的「改名」")
        val card = card()
        Personas.ALL.forEachIndexed { idx, p ->
            val current = p.id == settings.personaId
            val h = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(4), dp(8), dp(4))
                if (current) setBackgroundColor(sk.accentSoft)
            }
            h.addView(AvatarView(this).apply {
                setPersona(p.id); setFace(if (current) FaceState.HAPPY else FaceState.IDLE)
                if (current) setRing(sk.accent)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) }
            })
            val r = row(p.name, p.emotionalValue.joinToString(" · ") + "  ｜  自称「" + p.selfRef + "」").apply {
                setPadding(0, dp(10), 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            if (current) r.findViewById<TextView>(R.id.row_title).setTextColor(sk.accent)
            h.addView(r)
            h.addView(TextView(this).apply {
                text = "改名"; textSize = 13f; setTextColor(sk.accent); gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setStroke(dp(1), Skin.blend(sk.card, sk.accent, 0.5f)) }
                contentDescription = "给${p.name}改名"
                setOnClickListener { showRenameDialog(p) }
            })
            val pick = View.OnClickListener {
                settings.personaId = p.id
                buildUi()          // 重绘以更新选中态（主题色也跟着人设变）
            }
            r.setOnClickListener(pick)
            h.getChildAt(0).setOnClickListener(pick)
            card.addView(h)
            if (idx < Personas.ALL.size - 1) card.addView(divider())
        }
        hint("当前人设的兜底话术：${settings.persona.fallback}")

        // 口癖强度（只对带口癖的人设有效）
        val ticCard = card()
        if (settings.persona.tics.isEmpty()) {
            ticCard.addView(row("口癖强度", "当前人设没有口癖，此项无效"))
        } else {
            val levels = arrayOf("弱", "中", "强")
            ticCard.addView(numberRow(
                "口癖强度（${levels[settings.ticStrength.coerceIn(0, 2)]}）",
                "控制「${settings.persona.tics.joinToString("、")}」的出现频率",
                settings.ticStrength, 0, 2, 1
            ) { settings.ticStrength = it; buildUi() })
        }
    }


    // ---------------------------------------------------------------- 改名
    /** 给她改名：只改显示名和自称（「类型·名字」的后半截），称呼你不变；校验见 [PersonaNames.check] */
    private fun showRenameDialog(p: Persona) {
        val base = PersonaPacks.ALL.first { it.persona.id == p.id }.persona
        val baseAlias = PersonaNames.aliasOf(base.name)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val et = EditText(this).apply {
            setText(PersonaNames.aliasOf(p.name)); setSelection(text.length)
            hint = "新名字（1–${PersonaNames.MAX_LEN} 个字）"; isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(24))

            contentDescription = "新名字输入框"
        }
        val preview = TextView(this).apply { textSize = 12f; setPadding(0, dp(8), 0, 0) }
        box.addView(et); box.addView(preview)
        fun refresh() {
            when (val v = PersonaNames.check(et.text.toString(), p.id)) {
                is PersonaNames.Verdict.Ok -> {
                    val shown = PersonaNames.typeOf(base.name) + "·" + v.alias
                    preview.setTextColor(et.hintTextColors)
                    preview.text = "显示名：$shown" + if (PersonaNames.selfIsName(base)) "　自称：${v.alias}" else "　自称：${base.selfRef}（不变）"
                }
                is PersonaNames.Verdict.Reset -> { preview.setTextColor(et.hintTextColors); preview.text = "将恢复默认名字：${base.name}" }
                is PersonaNames.Verdict.Bad -> { preview.setTextColor(0xFFEF5350.toInt()); preview.text = v.why }
            }
        }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        refresh()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("给「${p.name}」改名")
            .setMessage("只改她的显示名和自称，不改她怎么称呼你。改完立刻生效，之前的聊天记录不变。")
            .setView(box)
            .setPositiveButton("保存", null)
            .setNeutralButton("恢复默认", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                when (val v = PersonaNames.check(et.text.toString(), p.id)) {
                    is PersonaNames.Verdict.Ok -> {
                        settings.setPersonaAlias(p.id, v.alias); dialog.dismiss(); buildUi()
                        android.widget.Toast.makeText(this, "好，以后叫「${Personas.byId(p.id).name}」", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    is PersonaNames.Verdict.Reset -> {
                        settings.setPersonaAlias(p.id, null); dialog.dismiss(); buildUi()
                        android.widget.Toast.makeText(this, "已恢复默认名字", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    is PersonaNames.Verdict.Bad -> android.widget.Toast.makeText(this, v.why, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                settings.setPersonaAlias(p.id, null); dialog.dismiss(); buildUi()
                android.widget.Toast.makeText(this, "已恢复默认名字", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    // ---------------------------------------------------------------- 界面皮肤
    private fun buildSkinSection() {
        section("界面皮肤", "三套配色：奶油手帐的主题色会跟着当前人设走；切换立即生效（主界面、设置页、专注页）")
        val card = card()
        Skin.IDS.forEachIndexed { idx, id ->
            val s = Skin.of(id, settings.personaId)
            val selected = id == settings.skinId
            val h = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                if (selected) setBackgroundColor(sk.accentSoft)
                contentDescription = "皮肤：${s.label}"
            }
            // 色板：底色圆里套一个主题色小圆，一眼看出长什么样
            h.addView(android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(s.bg); setStroke(dp(1), s.divider) }
                addView(View(context).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(s.accent) }
                })
            })
            val r = row((if (selected) "✓ " else "") + s.label, s.desc).apply {
                setPadding(0, dp(4), 0, dp(4)); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            if (selected) r.findViewById<TextView>(R.id.row_title).setTextColor(sk.accent)
            h.addView(r)
            h.setOnClickListener { settings.skinId = id; buildUi() }
            r.setOnClickListener { settings.skinId = id; buildUi() }
            card.addView(h)
            if (idx < Skin.IDS.size - 1) card.addView(divider())
        }
    }

    // ---------------------------------------------------------------- 模型
    private fun buildModelSection() {
        section("模型")
        val card = card()
        card.addView(switchRow("自动加载模型", "启动时加载 files/models/ 下的模型",
            settings.autoLoadModel) { settings.autoLoadModel = it })
        card.addView(divider())
        card.addView(textRow("模型文件名", "放在 app 私有 files/models/ 下",
            settings.modelFileName) { settings.modelFileName = it })
    }

    // ---------------------------------------------------------------- 心跳
    private fun buildHeartbeatSection() {
        section("心跳（主动消息）")
        val card = card()
        card.addView(switchRow("启用主动消息", null, settings.heartbeatEnabled) { settings.heartbeatEnabled = it })
        card.addView(divider())
        card.addView(numberRow("间隔（分钟）", "两条主动消息之间至少隔多久", settings.heartbeatIntervalMin, 1, 720, 5) { settings.heartbeatIntervalMin = it })
        card.addView(divider())
        card.addView(numberRow("同一件事重复提醒间隔（分钟）", "待办没有变化时，至少隔这么久才再说一次；0 = 不限制。想每个间隔都收到，把它调到 ≤ 间隔",
            settings.heartbeatSameContentMin, 0, 1440, 5) { settings.heartbeatSameContentMin = it })
        card.addView(divider())
        card.addView(numberRow("每日上限（条）", null, settings.heartbeatDailyCap, 0, 500, 10) { settings.heartbeatDailyCap = it })
        card.addView(divider())
        card.addView(timeRow("免打扰开始", settings.quietStart) {
            settings.quietStart = it; buildUi()
        })
        card.addView(divider())
        card.addView(timeRow("免打扰结束", settings.quietEnd) {
            settings.quietEnd = it; buildUi()
        })
        card.addView(divider())
        card.addView(switchRow("「该不该说」用模型判断", "（待接线）当前固定用规则打分；用模型判断尚未实现",
            settings.heartbeatUseModel) { settings.heartbeatUseModel = it })
        card.addView(divider())
        card.addView(switchRow("智能免打扰", "勿扰模式、通话中、日历里正在开会、充电熄屏的夜里（像在睡觉）时不主动开口；马上要发生的日程会议中仍会提醒",
            settings.smartQuiet) { settings.smartQuiet = it })
        card.addView(divider())
        card.addView(switchRow("连续未响应自动降频", "3 次不理 → 间隔×2，6 次×4，9 次×8，12 次才当天暂停；回到 App 即重置",
            settings.autoDownshift) { settings.autoDownshift = it })
        card.addView(divider())
        card.addView(numberRow("电量下限（%）", "低于则暂停主动消息", settings.batteryFloor, 0, 50, 5) { settings.batteryFloor = it })
        // 手动触发一条（绕过闸门，用于验证效果）
        val testCard = card()
        testCard.addView(row("▶ 立即发一条试试", "跳过间隔与去重，直接模拟一次主动消息").apply {
            setOnClickListener {
                try {
                    val i = android.content.Intent(this@SettingsActivity, HeartbeatService::class.java)
                    i.action = HeartbeatService.ACTION_FIRE_NOW
                    androidx.core.content.ContextCompat.startForegroundService(
                        this@SettingsActivity, i
                    )
                    android.widget.Toast.makeText(this@SettingsActivity, "已发送，看看通知栏或会话", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@SettingsActivity, "失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        })
        hint("心跳由系统闹钟每 1–5 分钟叫醒一次判定，修改后下一拍生效。")
        // 在这台 vivo 上，应用退到后台约 20 秒就被系统冻结，登记的闹钟（含心跳）一并被删——这里直接告诉用户
        val nextTick = getSharedPreferences("minicpm_settings", MODE_PRIVATE).getLong("hb_next_tick_at", 0L)
        if (settings.heartbeatEnabled && nextTick > 0 && nextTick < System.currentTimeMillis() - 2 * 60_000L) {
            hint("⚠️ 心跳已停摆 ${(System.currentTimeMillis() - nextTick) / 60_000L} 分钟：应用在后台被系统冻结，后台的主动消息不会按设置的间隔到来。" +
                "需要在手机系统里给本应用放行（后台高耗电 / 自启动 / 最近任务加锁 / 后台冻结相关项）。打开应用时会自动恢复。")
        }
        // 最近几次判定及原因（来自 hb_log.txt）：为什么没来消息，一眼可见
        val recent = try {
            java.io.File(filesDir, "hb_log.txt").readLines().takeLast(4).joinToString("\n")
        } catch (_: Exception) { "" }
        if (recent.isNotBlank()) hint("最近判定：\n$recent")
    }

    // ---------------------------------------------------------------- 记忆
    private fun buildMemorySection() {
        section("记忆")
        val card = card()
        card.addView(switchRow("启用长期记忆", "记住事实、偏好、会话摘要", settings.memoryEnabled) { settings.memoryEnabled = it })
        card.addView(divider())
        card.addView(numberRow("每轮注入上限（token）", "手机上下文紧张，建议 ≤500",
            settings.memoryTokenBudget, 100, 2000, 100) { settings.memoryTokenBudget = it })

        val mem = MemoryStore(this)
        card.addView(divider())
        val pendingN = Candidates.pending(CandidateStore(this).all()).size
        val factsRow = row("记忆管理：已记住 ${mem.factCount()} 件事", if (pendingN > 0) "另有 $pendingN 条待你确认 · 点开查看、修改、删除" else "点开查看、修改、删除")
        factsRow.setOnClickListener { startActivity(android.content.Intent(this, MemoryActivity::class.java)) }
        card.addView(factsRow)
        card.addView(divider())
        card.addView(switchRow("自动发现兴趣和身份信息", "聊天里提到喜欢什么、做什么工作、养了什么时，先记到「待确认」；同一件事隔一阵子再提一次或你点「记住」才写进记忆；健康、收入、证件等不记",
            settings.profileLearning) { settings.profileLearning = it })
        card.addView(divider())
        card.addView(numberRow("同一件事提到几次才自动记住", "1 = 说一次就记（不再确认）；默认 2 次，隔一阵子再提才算一次；也可以在记忆管理里手动点「记住」", settings.candidatePromoteAt, 1, 5, 1) { settings.candidatePromoteAt = it })

        card.addView(divider())
        card.addView(row("会话历史 ${mem.messageCount()} 条", "长按或点开可清空").apply {
            setOnClickListener {
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle("清空会话历史？")
                    .setMessage("会删除所有聊天记录（含心跳消息），但保留记住的事。")
                    .setPositiveButton("清空") { _, _ ->
                        mem.clearMessages()
                        buildUi()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
        hint("对话里说「记住…」「我不吃…」「我习惯…」会自动记下来；说「忘掉…」会删掉相关的。")
    }

    // ---------------------------------------------------------------- 步数
    private fun buildStepsSection() {
        section("步数")
        val card = card()
        val perm = StepSource.hasPermission(this)
        card.addView(switchRow("记录步数", "用系统计步器统计每天走了多少步，晨报/晚报和「今天走了多少步」会用到。数据只存在本机。需要允许「身体活动」权限（只能由你在弹窗里点允许）",
            settings.stepsEnabled) { on ->
            settings.stepsEnabled = on
            if (on && !StepSource.hasPermission(this)) requestPermissions(arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION), 7101)
        })
        hint(if (!StepSource.hasSensor(this)) "这台手机没有计步器。" else if (perm) "身体活动权限：已允许 ✓。从打开的那一刻起开始累计，之前走的步数无法补上；昨天的步数要等记录满一天后才有。" else "身体活动权限：还没有允许（打开开关会弹出系统的权限窗口）。")
    }

    // ---------------------------------------------------------------- 天气
    private fun buildWeatherSection() {
        section("天气")
        val card = card()
        card.addView(switchRow("使用手机自带天气的数据", "直接读手机天气应用已取好的数据：不联网、不发任何位置，和桌面上看到的一致", settings.weatherUseSystem) { settings.weatherUseSystem = it })
        card.addView(divider())
        card.addView(switchRow("联网查天气（备用，默认关）", "手机里读不到天气数据时才用。会向第三方气象服务（Open-Meteo）发送你选的城市的大致坐标（取整到约 10 公里），不带任何账号或设备标识",
            settings.weatherNet) { settings.weatherNet = it; buildUi() })
        card.addView(divider())
        val cityRow = row("城市", if (settings.weatherCity.isBlank()) "未选择（联网查天气需要；手机天气默认用天气应用里排第一的城市）" else settings.weatherCity)
        cityRow.setOnClickListener {
            val names = WeatherCities.ALL.map { it.name }
            MaterialAlertDialogBuilder(this).setTitle("选择城市")
                .setItems((listOf("（清空）") + names).toTypedArray()) { _, i -> settings.weatherCity = if (i == 0) "" else names[i - 1]; buildUi() }
                .setNegativeButton("取消", null).show()
        }
        card.addView(cityRow)
        hint("问「今天天气怎么样 / 明天会下雨吗 / 要带伞吗」时，答案的每个数字都来自上面的数据，由程序给出；取不到就如实说取不到，不会让模型编。白天降水概率高时心跳还会提醒带伞（一天一次）。")
    }

    // ---------------------------------------------------------------- 动作与提醒（可审计）
    private fun buildAuditSection() {
        section("动作与提醒", "它说做了的事，都能在这里核对")
        val card = card()
        val now = java.time.LocalDateTime.now()
        fun fmt(ms: Long): String {
            val dt = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault())
            return TimeParser.describe(dt, now)
        }
        val upcoming = ReminderScheduler.upcoming(this)
        card.addView(row("待触发的提醒 ${upcoming.size} 条", "点开查看；点某一条可以取消").apply {
            setOnClickListener {
                if (upcoming.isEmpty()) {
                    android.widget.Toast.makeText(this@SettingsActivity, "没有待触发的提醒", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val items = upcoming.map { (if (it.alarm) "⏰ " else "🔔 ") + fmt(it.triggerAt) + "  " + it.text }.toTypedArray()
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle("待触发的提醒（点一条取消）")
                    .setItems(items) { _, which ->
                        val r = upcoming[which]
                        MaterialAlertDialogBuilder(this@SettingsActivity)
                            .setMessage("取消「${r.text}」（${fmt(r.triggerAt)}）？")
                            .setPositiveButton("取消这条") { _, _ ->
                                val ok = ReminderScheduler.cancel(this@SettingsActivity, r.id)
                                android.widget.Toast.makeText(this@SettingsActivity, if (ok) "已取消" else "取消失败", android.widget.Toast.LENGTH_SHORT).show()
                                buildUi()
                            }
                            .setNegativeButton("保留", null)
                            .show()
                    }
                    .setPositiveButton("关闭", null)
                    .show()
            }
        })
        card.addView(divider())
        val fired = ReminderScheduler.all(this).filter { it.firedAt > 0 }.sortedByDescending { it.firedAt }.take(20)
        card.addView(row("已触发的提醒 ${fired.size} 条", "证明它真的到点提醒过").apply {
            setOnClickListener {
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle("已触发的提醒（最近 20 条）")
                    .setMessage(if (fired.isEmpty()) "还没有触发过的提醒。" else fired.joinToString("\n") { "· 计划 " + fmt(it.triggerAt) + " → 实际 " + fmt(it.firedAt) + "  " + it.text })
                    .setPositiveButton("关闭", null)
                    .show()
            }
        })
        card.addView(divider())
        card.addView(row("最近动作记录", "每一次执行、核验结果、被拦下的虚假承诺").apply {
            setOnClickListener {
                val acts = MemoryStore(this@SettingsActivity).recentActions(30)
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle("最近 30 条动作")
                    .setMessage(if (acts.isEmpty()) "还没有动作记录。" else acts.joinToString("\n\n") { a ->
                        fmt(a.createdAt) + "  [" + a.status + "] " + a.tool + "\n" + a.receipt
                    })
                    .setPositiveButton("关闭", null)
                    .show()
            }
        })
        card.addView(divider())
        card.addView(row("▶ 运行自检", "时间解析 / 意图路由 / 虚假承诺拦截 / 记忆提取的回归语料（不需要模型）").apply {
            setOnClickListener {
                val rep = try { AssistantSelfTest.runAll().render() } catch (e: Exception) { "自检崩溃：${e.message}" }
                try { java.io.File(filesDir, "selftest.txt").writeText(rep) } catch (_: Exception) {}
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle("自检结果")
                    .setMessage(rep)
                    .setPositiveButton("关闭", null)
                    .show()
            }
        })
        hint("闹钟：24 小时内的交给系统时钟并自动核对；更远的或系统时钟拒收的，改用助手闹钟（到点持续响铃）。")
    }

    // ---------------------------------------------------------------- 在线
    private fun buildOnlineSection() {
        section("在线升级")
        val card = card()
        card.addView(switchRow("使用云端后端", "（待接线 M10）关闭 = 全离线",
            settings.backend == "deepseek") { on ->
            settings.backend = if (on) "deepseek" else "local"
        })
        card.addView(divider())
        card.addView(textRow("DeepSeek API Key", "存在本机 SharedPreferences",
            if (settings.deepseekKey.isEmpty()) "" else "••••" + settings.deepseekKey.takeLast(4),
            masked = true) { v ->
            if (v.isNotEmpty() && !v.startsWith("••")) settings.deepseekKey = v
        })
        card.addView(divider())
        card.addView(textRow("云端模型名", null, settings.deepseekModel) { settings.deepseekModel = it })
        card.addView(divider())
        card.addView(numberRow("每日 token 上限", null, settings.dailyTokenCap, 1000, 1000000, 5000) { settings.dailyTokenCap = it })
        hint("升级策略固定为「每次确认」，不提供「总是同意」选项。云端调用尚未接线。")
    }

    // ---------------------------------------------------------------- 工具
    private fun buildToolsSection() {
        section("工具", "只把启用的工具交给模型；工具越多，每次响应越慢（实测 20 个工具 ≈ 多 60 秒）")
        val card = card()
        card.addView(switchRow(
            "允许联网",
            "关闭后，所有标记「需联网」的工具都不会启用；打开会让数据离开手机",
            settings.allowNetwork
        ) { on ->
            settings.allowNetwork = on
            buildUi()
        })
        val registry = ToolRegistry(this)
        registry.allTools.forEach { t ->
            card.addView(divider())
            val sub = (if (t.requiresNetwork) "【需联网】" else "") +
                t.description.take(46) + "…"
            card.addView(switchRow(
                "${t.category.label} · ${t.name}", sub,
                settings.isToolEnabled(t.name, t.enabledByDefault)
            ) { on -> settings.setToolEnabled(t.name, on) })
        }
    }
    // ---------------------------------------------------------------- 通知
    private fun buildNotifySection() {
        section("通知")
        val card = card()
        card.addView(switchRow("通知声音", null, settings.notifySound) { settings.notifySound = it })
        card.addView(divider())
        card.addView(switchRow("震动", null, settings.notifyVibrate) { settings.notifyVibrate = it })
        card.addView(divider())
        card.addView(switchRow("通知栏直接回复", "（待接线）不回 App 也能回一句，需 RemoteInput", settings.notificationReply) { settings.notificationReply = it })
    }

    // ---------------------------------------------------------------- 安全
    private fun buildSafetySection() {
        section("安全")
        val card = card()
        val lock = row("危机词拦截", "命中自伤/自杀/暴力表达 → 不调用模型，直接给求助资源")
        lock.findViewById<TextView>(R.id.row_sub).setTextColor(0xFFB91C1C.toInt())
        card.addView(lock)
        card.addView(divider())
        val road = row("这个开关不可关闭", "求助资源：12356 / 120 / 110")
        road.findViewById<TextView>(R.id.row_title).setTextColor(sk.textDim)
        card.addView(road)
    }

    private fun buildAboutSection() {
        section("关于")
        val card = card()
        card.addView(row("版本", AppInfo.VERSION_LABEL))
        card.addView(divider())
        card.addView(row("引擎", "llama.cpp（NDK 原生编译）"))
        card.addView(divider())
        card.addView(row("当前模型", settings.modelFileName))
    }

    // ---------------------------------------------------------------- 组件工厂
    private fun section(title: String, sub: String? = null): TextView {
        val tv = TextView(this).apply {
            text = title
            setTextColor(sk.text)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(20), dp(12), dp(4))
        }
        content.addView(tv)
        sub?.let {
            content.addView(TextView(this).apply {
                text = it
                setTextColor(sk.textDim)
                textSize = 11f
                setPadding(dp(12), 0, dp(12), dp(8))
            })
        }
        return tv
    }

    private fun hint(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            setTextColor(sk.textDim)
            textSize = 11f
            setPadding(dp(16), dp(8), dp(16), 0)
        })
    }

    /** 建一张卡片，返回可直接追加行的内层容器 */
    private fun card(): LinearLayout {
        val cv = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            radius = dp(sk.cardRadiusDp).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(sk.card)
            strokeWidth = dp(1)
            strokeColor = sk.divider
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cv.addView(inner)
        content.addView(cv)
        return inner
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(sk.divider)
    }

    private fun row(title: String, sub: String?): LinearLayout {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        ll.addView(TextView(this).apply {
            id = R.id.row_title
            text = title
            setTextColor(sk.text)
            textSize = 15f
        })
        if (!sub.isNullOrBlank()) {
            ll.addView(TextView(this).apply {
                id = R.id.row_sub
                text = sub
                setTextColor(sk.textDim)
                textSize = 12f
                setPadding(0, dp(3), 0, 0)
            })
        }
        return ll
    }

    private fun switchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply { text = title; setTextColor(sk.text); textSize = 15f })
        if (!sub.isNullOrBlank()) {
            texts.addView(TextView(this).apply {
                text = sub; setTextColor(sk.textDim); textSize = 12f; setPadding(0, dp(3), 0, 0)
            })
        }
        ll.addView(texts)
        ll.addView(MaterialSwitch(this).apply {
            SkinUi.tint(this, sk)
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
        return ll
    }

    private fun numberRow(title: String, sub: String?, value: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit): LinearLayout {
        var v = value
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply { text = title; setTextColor(sk.text); textSize = 15f })
        if (!sub.isNullOrBlank()) {
            texts.addView(TextView(this).apply {
                text = sub; setTextColor(sk.textDim); textSize = 12f; setPadding(0, dp(3), 0, 0)
            })
        }
        ll.addView(texts)
        val valueTv = TextView(this).apply {
            text = v.toString(); textSize = 16f; setTextColor(sk.accent)
            gravity = Gravity.CENTER; minWidth = dp(56)
        }
        ll.addView(TextView(this).apply {
            text = "−"; textSize = 22f; setTextColor(sk.accent); gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                if (v - step >= min) { v -= step; valueTv.text = v.toString(); onChange(v) }
            }
        })
        ll.addView(valueTv)
        ll.addView(TextView(this).apply {
            text = "+"; textSize = 20f; setTextColor(sk.accent); gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                if (v + step <= max) { v += step; valueTv.text = v.toString(); onChange(v) }
            }
        })
        return ll
    }

    private fun textRow(title: String, sub: String?, value: String, masked: Boolean = false, onChange: (String) -> Unit): LinearLayout {
        val ll = row(title, sub)
        val et = EditText(this).apply {
            setText(value)
            textSize = 14f
            setTextColor(sk.accent)
            setPadding(0, dp(6), 0, 0)
            isSingleLine = true
            if (masked) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        et.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onChange(et.text.toString().trim()) }
        ll.addView(et)
        return ll
    }

    /** 时间选择行：点一下弹出系统时间选择器（比手打 HH:MM 靠谱得多） */
    private fun timeRow(title: String, current: String, onChange: (String) -> Unit): LinearLayout {
        val parts = current.split(":", "：")
        val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 23
        val mi = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 30

        val ll = row(title, null)
        val valueTv = TextView(this).apply {
            text = "%02d:%02d".format(h, mi)
            textSize = 18f
            setTextColor(sk.accent)
            setPadding(0, dp(6), 0, 0)
        }
        ll.addView(valueTv)
        ll.setOnClickListener {
            android.app.TimePickerDialog(
                this, { _, hh, mm ->
                    val formatted = "%02d:%02d".format(hh, mm)
                    valueTv.text = formatted
                    onChange(formatted)
                }, h, mi, true
            ).show()
        }
        return ll
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
