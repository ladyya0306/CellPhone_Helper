package com.example.llama

import kotlin.random.Random

/**
 * 专注模式的安全阀。纯 Kotlin，可单测。
 *
 * 「做到天花板」不等于没有刹车：
 *   · 夜里（23 点到次日 7 点）不开地狱档，自动按毒舌处理；
 *   · 用户情绪低落（痛苦词、明显沮丧）→ 一律按温和处理；
 *   · 任何时候都能一键结束（界面上的「结束」按钮），结束不扣分、不羞辱。
 */
object FocusSafety {
    private const val GENTLE_MS = 3_600_000L

    /** 用户这句话流露出状态不好（沿用安全模块的痛苦词表与危机词表）→ 专注台词一律温柔 */
    fun isLowMood(text: String): Boolean = SafetyFilter.isDistress(text) || SafetyFilter.checkCrisis(text) != null

    /** 返回新的「温柔期」截止时间：说了痛苦的话就顺延到 1 小时后；平常的话不改变、也不缩短已有的温柔期 */
    fun updateUntil(text: String, nowMs: Long, currentUntilMs: Long): Long =
        if (isLowMood(text)) maxOf(currentUntilMs, nowMs + GENTLE_MS) else currentUntilMs

    fun active(untilMs: Long, nowMs: Long): Boolean = nowMs < untilMs

    fun effectiveDifficulty(d: FocusEngine.Difficulty, hour: Int, distressed: Boolean): FocusEngine.Difficulty = when {
        distressed -> FocusEngine.Difficulty.MILD
        d == FocusEngine.Difficulty.HELL && (hour >= 23 || hour < 7) -> FocusEngine.Difficulty.SPICY
        else -> d
    }
}

/**
 * 地狱难度的悬浮窗规则：台词用悬浮窗盖在别的应用上方弹出几秒。纯 Kotlin，可单测。
 * 只在「地狱」档弹（毒舌/温和档只发通知）；夜里和情绪低落时地狱档已被 [FocusSafety] 降级，悬浮窗随之不弹；
 * 人在本应用里不弹（界面自己会显示）；悬浮窗权限只能由用户在系统设置里手动授，没授权就不弹；树洞永远不弹。
 */
object FocusOverlayPolicy {
    private val KINDS = setOf(FocusEngine.Kind.LEAVE, FocusEngine.Kind.SCREEN_PEEK, FocusEngine.Kind.DISTRACT, FocusEngine.Kind.BLOWN)

    fun allowedFor(personaId: String) = PersonaPacks.of(personaId)?.roast != false

    fun shouldShow(
        kind: FocusEngine.Kind, effectiveDifficulty: FocusEngine.Difficulty, inApp: Boolean, hasPermission: Boolean,
        enabled: Boolean, lastShownAtMs: Long, nowMs: Long, minGapMs: Long,
    ): Boolean = enabled && hasPermission && !inApp && effectiveDifficulty == FocusEngine.Difficulty.HELL &&
        kind in KINDS && nowMs - lastShownAtMs >= minGapMs

    /** 悬浮窗停留多久：3.5 秒起，每个字多 0.12 秒，最长 8 秒 */
    fun displayMs(text: String): Long = (3_500L + text.length * 120L).coerceIn(3_500L, 8_000L)
}

/**
 * 一局专注：引擎状态 + 人设 + 台词抽取。服务和界面都只和它打交道；纯逻辑，不碰 Android。
 * 每条要展示的台词都先过 [InsultGuard]，不过关就丢掉（并计数），也不会连续两次说同一句。
 */
class FocusSession(val personaId: String, val cfg: FocusEngine.Config, nowMs: Long, seed: Int = 7) {

    data class Line(val atMs: Long, val kind: FocusEngine.Kind, val level: FocusEngine.Level, val text: String)

    var state: FocusEngine.State = FocusEngine.start(nowMs, cfg)
        private set
    val lines = ArrayList<Line>()
    var rejected = 0
        private set
    var lastDifficulty: FocusEngine.Difficulty = cfg.difficulty
        private set

    private val rnd = Random(seed)
    private var lastText = ""

    /** 每种事件最近用过的 3 个模板：避免"只换了应用名、模板一模一样"的观感复读 */
    private val recentTemplates = HashMap<FocusLines.Kind, ArrayDeque<String>>()

    private fun say(at: Long, kind: FocusEngine.Kind, level: FocusEngine.Level, k: FocusLines.Kind, d: FocusEngine.Difficulty, slots: Map<String, String>): Line? {
        val recent = recentTemplates.getOrPut(k) { ArrayDeque() }
        repeat(4) {
            val tpl = FocusLines.pickTemplate(personaId, k, level, d, rnd, recent)
            val text = FocusLines.fill(tpl, slots)
            if (InsultGuard.check(text).isNotEmpty() || text.contains("{")) { rejected++; return@repeat }
            if (text == lastText) return@repeat      // 刚说过的同一句：换一条；这一格只有一条可选就宁可不出声，也不连着复读
            lastText = text
            recent.addLast(tpl)
            while (recent.size > 3) recent.removeFirst()
            return Line(at, kind, level, text).also { lines.add(it) }
        }
        return null
    }

    /** 开场白（START） */
    @Synchronized
    fun begin(atMs: Long, hour: Int, distressed: Boolean = false): List<Line> {
        val d = FocusSafety.effectiveDifficulty(cfg.difficulty, hour, distressed)
        lastDifficulty = d
        val slots = mapOf("min" to maxOf(1L, cfg.totalMs / 60_000L).toString(), "cold" to (cfg.coldWarSec / 60).toString())
        return listOfNotNull(say(atMs, FocusEngine.Kind.START, FocusEngine.Level.CALM, FocusLines.Kind.START, d, slots))
    }

    /** 喂一个事件，返回这次要展示的台词（可能为空）。 */
    @Synchronized
    fun handle(e: FocusEngine.Event, hour: Int, distressed: Boolean = false): List<Line> {
        val d = FocusSafety.effectiveDifficulty(cfg.difficulty, hour, distressed)
        lastDifficulty = d
        val r = FocusEngine.reduce(state, e, cfg)
        state = r.state
        val at = when (e) {
            is FocusEngine.Event.Left -> e.atMs; is FocusEngine.Event.Returned -> e.atMs; is FocusEngine.Event.ScreenOn -> e.atMs
            is FocusEngine.Event.Distract -> e.atMs; is FocusEngine.Event.Tick -> e.atMs; is FocusEngine.Event.Pause -> e.atMs; is FocusEngine.Event.Resume -> e.atMs
        }
        val out = ArrayList<Line>()
        for (re in r.reactions) {
            val k = FocusLines.kindFor(re) ?: continue
            say(at, re.kind, re.level, k, d, re.slots)?.let { out.add(it) }
        }
        return out
    }

    /** 界面轮询用：当前状态和最近的台词（拷贝，线程安全） */
    @Synchronized
    fun snapshot(): Pair<FocusEngine.State, List<Line>> = state to lines.toList()
}
