package com.example.llama

import kotlin.math.floor

/**
 * 专注模式的规则引擎（程序判、人设说）。纯 Kotlin，可单测。
 *
 * 引擎只做三件事：吃进"发生了什么"（离开本应用、亮屏看手机、打开分心应用、时间走过去），
 * 算出生气值 / 档位 / 冷战，吐出"反应"（带事实槽位：几秒、第几次、剩多久）。
 * 反应怎么说由 [FocusLines] 按人设选台词、填槽位——数字永远来自这里，台词只负责语气。
 *
 * 规则（2026-10-20 用户拍板：进阶档 + 地狱难度可选，冷战 3 分钟，宽限期 8 秒，均可调）：
 *   离开本应用      宽限期内回来不算；超过宽限期 +15，之后每 10 秒 +1
 *   亮屏看手机      +10（人在本应用里不算；5 秒内连续亮屏只算一次）
 *   打开分心应用    +20（同一个应用在一次离开里只算一次）
 *   回到本应用      −5
 *   连续 5 分钟无干扰 −10
 *   生气值 ≥ 90     爆 → 冷战 [Config.coldWarSec] 秒：期间不再叠加生气值；待在本应用里冷战倒计时按 2 倍速走；结束后生气值回到 50
 *   难度倍率        温和 ×0.7 / 毒舌 ×1.0 / 地狱 ×1.4
 */
object FocusEngine {

    enum class Level { CALM, NAG, SNARK, BLOWN }
    enum class Difficulty { MILD, SPICY, HELL }
    enum class Kind { START, LEAVE, SCREEN_PEEK, DISTRACT, LEVEL_UP, RETURN, RETURN_GRACE, BLOWN, COLD_END, CALM_BONUS, FINISH }

    data class Config(
        val durationMin: Int = 25,
        val graceSec: Int = 8,
        val coldWarSec: Int = 180,
        val difficulty: Difficulty = Difficulty.SPICY,
        /** 不为 0 时优先于 [durationMin]：测试用秒级时长 */
        val durationSec: Int = 0,
        /** 连续这么多秒无干扰 → 回血 10（默认 5 分钟；测试里调小） */
        val calmBonusSec: Int = 300,
    ) {
        val totalMs: Long get() = if (durationSec > 0) durationSec * 1000L else durationMin * 60_000L
    }

    data class State(
        val startMs: Long,
        val endMs: Long,
        val rage: Int = 0,
        val leaves: Int = 0,
        val screenOns: Int = 0,
        val distracts: Int = 0,
        val blowns: Int = 0,
        val awayAtMs: Long? = null,
        val awayPenaltyDone: Boolean = false,
        val awayExtraCharged: Int = 0,
        val distractedApps: Set<String> = emptySet(),
        val lastScreenOnMs: Long = -1_000_000L,
        val calmSinceMs: Long = 0L,
        val coldUntilMs: Long = 0L,
        val lastTickMs: Long = 0L,
        val pausedAtMs: Long? = null,
        val finished: Boolean = false,
    )

    sealed class Event {
        data class Left(val atMs: Long) : Event()
        data class Returned(val atMs: Long) : Event()
        data class ScreenOn(val atMs: Long) : Event()
        data class Distract(val atMs: Long, val label: String) : Event()
        data class Tick(val atMs: Long) : Event()
        data class Pause(val atMs: Long) : Event()
        data class Resume(val atMs: Long) : Event()
    }

    data class Reaction(val kind: Kind, val level: Level, val slots: Map<String, String>)
    data class Result(val state: State, val reactions: List<Reaction>)

    fun levelOf(rage: Int): Level = when {
        rage < 30 -> Level.CALM
        rage < 60 -> Level.NAG
        rage < 90 -> Level.SNARK
        else -> Level.BLOWN
    }

    private fun mult(d: Difficulty) = when (d) { Difficulty.MILD -> 0.7; Difficulty.SPICY -> 1.0; Difficulty.HELL -> 1.4 }

    fun start(nowMs: Long, cfg: Config): State =
        State(startMs = nowMs, endMs = nowMs + cfg.totalMs, calmSinceMs = nowMs, lastTickMs = nowMs)

    private fun mmss(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** 冷战时长的展示文字：整分钟显示「3 分钟」，否则显示「10 秒」 */
    fun coldText(sec: Int): String = if (sec >= 60 && sec % 60 == 0) "${sec / 60} 分钟" else "$sec 秒"

    fun grade(s: State): String = when {
        s.blowns == 0 && s.leaves == 0 && s.screenOns == 0 && s.distracts == 0 -> "S"
        s.blowns == 0 && s.rage < 30 && s.leaves <= 1 -> "A"
        s.blowns == 0 -> "B"
        else -> "C"
    }

    fun reduce(s0: State, e: Event, cfg: Config): Result {
        if (s0.finished) return Result(s0, emptyList())
        if (s0.pausedAtMs != null && e !is Event.Resume) return Result(s0, emptyList())
        var s = s0
        val rs = ArrayList<Reaction>()
        val m = mult(cfg.difficulty)
        fun gain(x: Int) = floor(x * m + 0.5).toInt()
        fun slots(at: Long, extra: Map<String, String> = emptyMap()): Map<String, String> = mapOf(
            "rage" to s.rage.toString(), "n" to s.leaves.toString(), "left" to mmss(s.endMs - at),
            "min" to maxOf(1L, cfg.totalMs / 60_000L).toString(), "cold" to coldText(cfg.coldWarSec),
            "peeks" to s.screenOns.toString(), "blowns" to s.blowns.toString(),
        ) + extra
        fun react(kind: Kind, at: Long, extra: Map<String, String> = emptyMap()) { rs.add(Reaction(kind, levelOf(s.rage), slots(at, extra))) }
        fun cold() = s.coldUntilMs > 0L

        fun addRage(d: Int, at: Long) {
            val before = levelOf(s.rage)
            s = s.copy(rage = (s.rage + d).coerceIn(0, 100))
            val after = levelOf(s.rage)
            if (d > 0 && after.ordinal > before.ordinal && after != Level.BLOWN) react(Kind.LEVEL_UP, at)
            if (s.rage >= 90 && !cold()) {
                s = s.copy(blowns = s.blowns + 1, coldUntilMs = at + cfg.coldWarSec * 1000L)
                react(Kind.BLOWN, at)
            }
        }

        /** 离开期间的扣分：只在超过宽限期后扣一次 +15，之后按每 10 秒 +1 补扣 */
        fun chargeAway(at: Long) {
            val away = s.awayAtMs ?: return
            if (cold()) return
            val awaySec = ((at - away) / 1000).toInt()
            if (awaySec <= cfg.graceSec) return
            if (!s.awayPenaltyDone) {
                s = s.copy(awayPenaltyDone = true, leaves = s.leaves + 1)
                react(Kind.LEAVE, at, mapOf("sec" to awaySec.toString()))   // 先说这件事，再说它引起的升档/爆表
                addRage(gain(15), at)
            }
            val extra = (awaySec - cfg.graceSec) / 10
            if (extra > s.awayExtraCharged) {
                val d = extra - s.awayExtraCharged
                s = s.copy(awayExtraCharged = extra)
                addRage(gain(d), at)
            }
        }

        when (e) {
            is Event.Left -> if (s.awayAtMs == null) s = s.copy(awayAtMs = e.atMs, awayPenaltyDone = false, awayExtraCharged = 0, distractedApps = emptySet())
            is Event.Returned -> {
                val away = s.awayAtMs
                if (away != null) {
                    val awaySec = ((e.atMs - away) / 1000).toInt()
                    if (awaySec <= cfg.graceSec && !s.awayPenaltyDone) {
                        s = s.copy(awayAtMs = null, calmSinceMs = e.atMs)
                        react(Kind.RETURN_GRACE, e.atMs, mapOf("sec" to awaySec.toString()))
                    } else {
                        chargeAway(e.atMs)
                        val wasCold = cold()
                        if (!wasCold) addRage(-5, e.atMs)
                        s = s.copy(awayAtMs = null, calmSinceMs = e.atMs, awayPenaltyDone = false, awayExtraCharged = 0)
                        if (!wasCold) react(Kind.RETURN, e.atMs, mapOf("sec" to awaySec.toString()))
                    }
                }
            }
            is Event.ScreenOn -> if (s.awayAtMs != null && !cold() && e.atMs - s.lastScreenOnMs >= 5_000L) {
                s = s.copy(screenOns = s.screenOns + 1, lastScreenOnMs = e.atMs)
                react(Kind.SCREEN_PEEK, e.atMs)
                addRage(gain(10), e.atMs)
            }
            is Event.Distract -> if (s.awayAtMs != null && !cold() && e.label !in s.distractedApps) {
                s = s.copy(distractedApps = s.distractedApps + e.label, distracts = s.distracts + 1)
                react(Kind.DISTRACT, e.atMs, mapOf("app" to e.label))
                addRage(gain(20), e.atMs)
            }
            is Event.Tick -> {
                val at = e.atMs
                val dt = (at - s.lastTickMs).coerceAtLeast(0L)
                if (cold()) {
                    // 待在本应用里安静待着，冷战按 2 倍速结束
                    if (s.awayAtMs == null) s = s.copy(coldUntilMs = s.coldUntilMs - dt)
                    if (at >= s.coldUntilMs) {
                        s = s.copy(coldUntilMs = 0L, rage = 50, calmSinceMs = at, awayAtMs = null)
                        react(Kind.COLD_END, at)
                    }
                } else {
                    chargeAway(at)
                    if (s.awayAtMs == null && at - s.calmSinceMs >= cfg.calmBonusSec * 1000L) {
                        val had = s.rage
                        s = s.copy(rage = (s.rage - 10).coerceAtLeast(0), calmSinceMs = at)
                        if (had > 0) react(Kind.CALM_BONUS, at)
                    }
                }
                s = s.copy(lastTickMs = at)
                if (at >= s.endMs) {
                    s = s.copy(finished = true)
                    react(Kind.FINISH, at, mapOf("grade" to grade(s)))
                }
            }
            is Event.Pause -> s = s.copy(pausedAtMs = e.atMs)
            is Event.Resume -> {
                val p = s.pausedAtMs
                if (p != null) {
                    val d = (e.atMs - p).coerceAtLeast(0L)
                    s = s.copy(pausedAtMs = null, endMs = s.endMs + d, coldUntilMs = if (s.coldUntilMs > 0) s.coldUntilMs + d else 0L, lastTickMs = e.atMs)
                }
            }
        }
        return Result(s, rs)
    }
}
