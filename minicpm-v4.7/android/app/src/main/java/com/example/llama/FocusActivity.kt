package com.example.llama

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * 专注模式界面：设置 → 进行中（倒计时、生气值、台词流）→ 结算。
 * 台词全部来自 [FocusSession]（预置台词库，已过 InsultGuard）；本界面只负责显示，不生成任何台词。
 * 任何时候都能点「结束专注」，结束不扣分、不羞辱。
 */
class FocusActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var settings: AppSettings
    private val sk: Skin get() = settings.skin()
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { build(); handler.postDelayed(this, 700) }
    }
    private var advancedStatus: TextView? = null
    private var overlayStatus: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = AppSettings(this)
        content = findViewById(R.id.content)
        findViewById<MaterialToolbar>(R.id.toolbar).apply { title = "专注模式"; setNavigationOnClickListener { finish() }; SkinUi.applyChrome(this@FocusActivity, sk, this) }
    }

    override fun onStart() { super.onStart(); handler.post(refresh) }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }

    private fun build() {
        // 正在输入/弹窗时不重建设置页，免得打断；只有进行中/结算页才每次刷新
        val session = FocusRuntime.session
        if (session == null) { if (content.tag != "setup") { buildSetup(); content.tag = "setup" } else { advancedStatus?.text = accessText(); overlayStatus?.text = overlayText() }; return }
        content.tag = "run"
        buildRun(session)
    }

    // ------------------------------------------------------------------ 设置页
    private fun buildSetup() {
        content.removeAllViews()
        val p = settings.persona
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(16), dp(4), dp(4))
            addView(AvatarView(this@FocusActivity).apply {
                setPersona(p.id); setFace(if (PersonaPacks.packOf(p.id).roast) FaceState.FOCUS else FaceState.IDLE); setAnimated(true); setRing(Skin.blend(sk.bg, sk.accent, 0.55f))
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(12) }
            })
            addView(TextView(this@FocusActivity).apply { text = "${p.name} 来当监督官"; setTextColor(sk.text); textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        })
        hint(statsLine())
        hint("选好时长和尺度，点开始。专注期间离开本应用、亮屏看手机、打开分心应用，她都会开口。随时可以点「结束专注」，不扣分。")

        val card = card()
        card.addView(stepperRow("时长（分钟）", settings.focusDurationMin, 1, 180, 5) { settings.focusDurationMin = it })
        card.addView(divider())
        card.addView(stepperRow("宽限期（秒）：离开不到这么久回来，不算", settings.focusGraceSec, 0, 60, 2) { settings.focusGraceSec = it })
        card.addView(divider())
        card.addView(stepperRow("冷战时长（秒）：生气值爆表后她不理你", settings.focusColdWarSec, 30, 900, 30) { settings.focusColdWarSec = it })
        card.addView(divider())
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(this@FocusActivity).apply { text = "尺度"; setTextColor(sk.text); textSize = 15f })
            val g = MaterialButtonToggleGroup(this@FocusActivity).apply { isSingleSelection = true; isSelectionRequired = true }
            listOf("温和", "毒舌", "地狱").forEachIndexed { i, t ->
                g.addView(MaterialButton(this@FocusActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { id = 9100 + i; text = t; SkinUi.tint(this, sk, false) })
            }
            g.check(9100 + settings.focusDifficulty)
            g.addOnButtonCheckedListener { _, id, checked -> if (checked) settings.focusDifficulty = id - 9100 }
            addView(g)
            addView(TextView(this@FocusActivity).apply {
                text = "夜里 23 点到早上 7 点地狱档会自动降到毒舌；所有台词都过「不带脏字、不攻击人」检查。"
                setTextColor(sk.textDim); textSize = 11f; setPadding(0, dp(6), 0, 0)
            })
        })

        title("进阶：识别你打开了哪个分心应用")
        val adv = card()
        adv.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            val sw = MaterialSwitch(this@FocusActivity).apply {
                SkinUi.tint(this, sk)
                text = "进阶档"; isChecked = settings.focusAdvanced
                setOnCheckedChangeListener { _, on ->
                    settings.focusAdvanced = on
                    if (on && !FocusUsage.hasAccess(this@FocusActivity)) askUsageAccess()
                }
            }
            addView(sw)
            advancedStatus = TextView(this@FocusActivity).apply { text = accessText(); setTextColor(sk.textDim); textSize = 12f; setPadding(0, dp(4), 0, 0) }
            addView(advancedStatus)
        })
        adv.addView(divider())
        adv.addView(button("选择分心应用（已选 ${settings.focusDistractApps.size} 个）") { pickApps() })
        adv.addView(divider())
        adv.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(MaterialSwitch(this@FocusActivity).apply {
                SkinUi.tint(this, sk)
                text = "地狱档悬浮窗（把话盖在别的应用上方）"; isChecked = settings.focusOverlay
                setOnCheckedChangeListener { _, on ->
                    settings.focusOverlay = on
                    if (on && !FocusOverlay(this@FocusActivity).canDraw()) askOverlay()
                }
            })
            overlayStatus = TextView(this@FocusActivity).apply { text = overlayText(); setTextColor(sk.textDim); textSize = 12f; setPadding(0, dp(4), 0, 0) }
            addView(overlayStatus)
        })
        hint("进阶档需要你在系统设置里手动打开「使用情况访问」（我不会替你开）。没开时自动用基础档：只检测离开本应用和亮屏。数据只在本机判断，不存储、不上传。")

        content.addView(MaterialButton(this).apply {
            text = "开始专注"
            SkinUi.tint(this, sk, true)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
            setOnClickListener { FocusService.start(this@FocusActivity); content.tag = null }
        })
    }

    private fun statsLine(): String =
        FocusStats.line(FocusStats.summarize(FocusStatsStore.all(this), System.currentTimeMillis(), java.time.ZoneId.systemDefault()))

    private fun accessText() = if (FocusUsage.hasAccess(this)) "使用情况访问：已授权 ✓" else "使用情况访问：未授权（点开关会带你去系统设置）"

    private fun overlayText() = if (FocusOverlay(this).canDraw()) "悬浮窗权限：已授权 ✓（只在「地狱」尺度、白天、不在本应用时才弹）" else "悬浮窗权限：未授权（点开关会带你去系统设置，由你手动打开）"

    private fun askOverlay() {
        MaterialAlertDialogBuilder(this).setTitle("需要「在其他应用上层显示」")
            .setMessage("地狱档的悬浮窗要盖在别的应用上方才看得见。这个权限只能由你在系统设置里手动打开：找到本应用，打开「允许在其他应用上层显示」，再回来。")
            .setPositiveButton("去设置") { _, _ -> startActivity(FocusOverlay.settingsIntent(this)) }
            .setNegativeButton("先不开", null).show()
    }

    private fun askUsageAccess() {
        MaterialAlertDialogBuilder(this).setTitle("需要「使用情况访问」")
            .setMessage("进阶档要知道你当前在用哪个应用，才能识别分心。这个权限只能由你在系统设置里打开：找到本应用，打开「允许访问使用情况」，再回来。")
            .setPositiveButton("去设置") { _, _ -> startActivity(FocusUsage.settingsIntent(this)) }
            .setNegativeButton("先不开", null).show()
    }

    private fun pickApps() {
        val apps = FocusUsage.launchableApps(this)
        if (apps.isEmpty()) { MaterialAlertDialogBuilder(this).setMessage("没有读到应用列表").setPositiveButton("好", null).show(); return }
        val saved = settings.focusDistractApps
        val checked = (if (saved.isEmpty()) FocusUsage.suggested(apps) else saved).toMutableSet()
        MaterialAlertDialogBuilder(this).setTitle("哪些应用算分心？")
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), BooleanArray(apps.size) { apps[it].pkg in checked }) { _, i, on ->
                if (on) checked.add(apps[i].pkg) else checked.remove(apps[i].pkg)
            }
            .setPositiveButton("保存") { _, _ -> settings.focusDistractApps = checked; content.tag = null; build() }
            .setNegativeButton("取消", null).show()
    }

    // ------------------------------------------------------------------ 进行中 / 结算
    private fun buildRun(session: FocusSession) {
        val (st, lines) = session.snapshot()
        content.removeAllViews()
        val persona = settings.persona
        val now = System.currentTimeMillis()
        val cold = st.coldUntilMs > 0
        val left = (st.endMs - now).coerceAtLeast(0)

        val roast = PersonaPacks.packOf(persona.id).roast
        val face = when {
            st.finished -> if (FocusEngine.grade(st) in listOf("S", "A")) FaceState.HAPPY else FaceState.COMFORT
            cold -> if (roast) FaceState.ANGRY else FaceState.COMFORT
            else -> if (roast) FaceState.FOCUS else FaceState.IDLE
        }
        content.addView(AvatarView(this).apply {
            setPersona(persona.id); setFace(face); setAnimated(true); setRing(Skin.blend(sk.bg, sk.accent, 0.55f))
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) }
        })
        if (st.finished) {
            title("专注结束  评级 ${FocusEngine.grade(st)}")
            hint("离开 ${st.leaves} 次 · 亮屏 ${st.screenOns} 次 · 分心应用 ${st.distracts} 次 · 爆表 ${st.blowns} 次")
            hint(statsLine())
        } else {
            content.addView(TextView(this).apply {
                text = "%d:%02d".format(left / 60000, (left / 1000) % 60)
                textSize = 56f; gravity = Gravity.CENTER; setTextColor(if (cold) 0xFF9CA3AF.toInt() else sk.text)
                setPadding(0, dp(24), 0, dp(8))
            })
            hint(if (cold) "${persona.name}生你的气了，在冷战……（冷战还剩 ${((st.coldUntilMs - now).coerceAtLeast(0) / 1000)} 秒，待在这里能更快和好）"
            else "${persona.name} 盯着你呢")
        }

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = st.rage
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(12); marginStart = dp(16); marginEnd = dp(16) }
        }
        content.addView(bar)
        content.addView(TextView(this).apply {
            text = "生气值 ${st.rage}/100 · ${when (FocusEngine.levelOf(st.rage)) { FocusEngine.Level.CALM -> "平静"; FocusEngine.Level.NAG -> "唠叨"; FocusEngine.Level.SNARK -> "阴阳怪气"; else -> "爆表" }}" +
                "\n离开 ${st.leaves} · 亮屏 ${st.screenOns} · 分心 ${st.distracts} · 爆 ${st.blowns}" + if (FocusRuntime.advanced) " · 进阶档" else " · 基础档"
            setTextColor(sk.textDim); textSize = 12f; setPadding(dp(16), dp(6), dp(16), dp(10))
        })

        val card = card()
        if (lines.isEmpty()) card.addView(textRow("（还没说话）"))
        lines.takeLast(30).reversed().forEachIndexed { i, ln ->
            if (i > 0) card.addView(divider())
            card.addView(textRow(ln.text))
        }

        if (!st.finished) {
            val gentle = FocusSafety.active(FocusRuntime.distressedUntilMs, now)
            content.addView(TextView(this).apply {
                text = if (gentle) "察觉到你状态不太好，她这一阵会温柔一点。" else "今天状态不好？点这里让她温柔点"
                setTextColor(if (gentle) sk.textDim else sk.accent); textSize = 13f; setPadding(dp(16), dp(10), dp(16), dp(4))
                if (!gentle) setOnClickListener { FocusRuntime.distressedUntilMs = System.currentTimeMillis() + 3_600_000L; build() }
            })
        }
        content.addView(MaterialButton(this).apply {
            text = if (st.finished) "再来一局" else "结束专注"
            SkinUi.tint(this, sk, true)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
            setOnClickListener {
                if (st.finished) { FocusRuntime.session = null; content.tag = null; build() }
                else MaterialAlertDialogBuilder(this@FocusActivity).setTitle("结束这一局？").setMessage("结束不扣分，也不会被说。")
                    .setPositiveButton("结束") { _, _ -> FocusService.stop(this@FocusActivity); FocusRuntime.session = null; content.tag = null; build() }
                    .setNegativeButton("继续专注", null).show()
            }
        })
    }

    // ------------------------------------------------------------------ 小部件
    private fun title(t: String) = content.addView(TextView(this).apply {
        text = t; setTextColor(sk.text); textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(dp(12), dp(20), dp(12), dp(4))
    })

    private fun hint(t: String) = content.addView(TextView(this).apply {
        text = t; setTextColor(sk.textDim); textSize = 12f; setPadding(dp(16), dp(6), dp(16), 0)
    })

    private fun card(): LinearLayout {
        val cv = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
            radius = dp(sk.cardRadiusDp).toFloat(); cardElevation = 0f
            setCardBackgroundColor(sk.card); strokeWidth = dp(1); strokeColor = sk.divider
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cv.addView(inner); content.addView(cv)
        return inner
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)); setBackgroundColor(sk.divider)
    }

    private fun textRow(t: String) = TextView(this).apply {
        text = t; setTextColor(sk.text); textSize = 14f; setPadding(dp(16), dp(12), dp(16), dp(12))
    }

    private fun button(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(sk.accent); textSize = 15f; setPadding(dp(16), dp(14), dp(16), dp(14)); setOnClickListener { onClick() }
    }

    private fun stepperRow(label: String, value: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit): View {
        var v = value
        val ll = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(12), dp(10)) }
        ll.addView(TextView(this).apply { text = label; setTextColor(sk.text); textSize = 14f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        val num = TextView(this).apply { text = v.toString(); textSize = 16f; setTextColor(sk.text); gravity = Gravity.CENTER; minWidth = dp(44) }
        fun set(n: Int) { v = n.coerceIn(min, max); num.text = v.toString(); onChange(v) }
        ll.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "−"; minWidth = 0; SkinUi.tint(this, sk, false); setOnClickListener { set(v - step) } })
        ll.addView(num)
        ll.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "+"; minWidth = 0; SkinUi.tint(this, sk, false); setOnClickListener { set(v + step) } })
        return ll
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
