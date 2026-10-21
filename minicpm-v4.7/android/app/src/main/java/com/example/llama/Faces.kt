package com.example.llama

/**
 * 头像的表情状态与映射。纯 Kotlin，可单测；画图在 [AvatarView]。
 *
 * 状态由"程序知道的事实"决定（模型在加载/生成、专注模式在冷战/进行、到了免打扰时段、用户这句话的情绪），
 * 不是模型自己说了算——和"程序说事实，模型说情绪"的原则一致。
 */
enum class FaceState(val label: String) {
    IDLE("待机"), THINKING("思考"), HAPPY("开心"), COMFORT("安慰"), ANGRY("生气"), FOCUS("专注监督"), SLEEP("睡觉")
}

/** 一个表情的几何参数：画图时只读它，所以"每种状态长什么样"可以直接用数据校验 */
data class FaceParams(
    val eye: Eye, val mouth: Mouth, val brow: Brow,
    val blush: Boolean = false, val extra: Extra = Extra.NONE,
) {
    enum class Eye { OPEN, HALF, ARC_UP, CLOSED, NARROW, WIDE_SHINE, SIDE }
    enum class Mouth { SMALL, FLAT, SMILE, GRIN, FROWN, OPEN_O, WAVY }
    enum class Brow { NONE, NEUTRAL, SAD, ANGRY, RAISED }
    /** 脸旁边的小标记：思考的省略号、睡觉的 z、生气的青筋、开心的闪光、安慰的汗滴 */
    enum class Extra { NONE, DOTS, ZZZ, VEIN, SPARKLE, SWEAT, GLASS_GLINT }
}

object Faces {

    fun params(s: FaceState): FaceParams = when (s) {
        FaceState.IDLE -> FaceParams(FaceParams.Eye.OPEN, FaceParams.Mouth.SMALL, FaceParams.Brow.NEUTRAL)
        FaceState.THINKING -> FaceParams(FaceParams.Eye.SIDE, FaceParams.Mouth.FLAT, FaceParams.Brow.RAISED, extra = FaceParams.Extra.DOTS)
        FaceState.HAPPY -> FaceParams(FaceParams.Eye.ARC_UP, FaceParams.Mouth.GRIN, FaceParams.Brow.NONE, blush = true, extra = FaceParams.Extra.SPARKLE)
        FaceState.COMFORT -> FaceParams(FaceParams.Eye.WIDE_SHINE, FaceParams.Mouth.WAVY, FaceParams.Brow.SAD, blush = true, extra = FaceParams.Extra.SWEAT)
        FaceState.ANGRY -> FaceParams(FaceParams.Eye.NARROW, FaceParams.Mouth.FROWN, FaceParams.Brow.ANGRY, extra = FaceParams.Extra.VEIN)
        FaceState.FOCUS -> FaceParams(FaceParams.Eye.HALF, FaceParams.Mouth.FLAT, FaceParams.Brow.ANGRY, extra = FaceParams.Extra.GLASS_GLINT)
        FaceState.SLEEP -> FaceParams(FaceParams.Eye.CLOSED, FaceParams.Mouth.OPEN_O, FaceParams.Brow.NONE, extra = FaceParams.Extra.ZZZ)
    }

    /** 状态栏下的一句话（顶栏副标题），{name} 用当前自称 */
    fun caption(s: FaceState, selfRef: String, idle: String): String = when (s) {
        FaceState.IDLE -> idle
        FaceState.THINKING -> "${selfRef}在想…"
        FaceState.HAPPY -> "${selfRef}很开心"
        FaceState.COMFORT -> "${selfRef}在陪着你"
        FaceState.ANGRY -> "${selfRef}生气了"
        FaceState.FOCUS -> "${selfRef}盯着你呢"
        FaceState.SLEEP -> "${selfRef}睡着了（免打扰）"
    }

    private val HAPPY_WORDS = Regex("哈哈|开心|高兴|太好了|好消息|通过了|成功|升职|加薪|过了|拿到|谢谢|感谢|爱你|棒|赢了|终于")
    private val COMFORT_WORDS = Regex("难过|伤心|想哭|哭了|累死|好累|崩溃|焦虑|压力|烦死|烦躁|失眠|睡不着|失败|被骂|难受|郁闷|不开心|孤独|委屈|害怕")

    /** 用户这句话的情绪倾向（关键词，保守）：命中安慰类优先于开心类；都没命中返回 null */
    fun moodOf(text: String?): FaceState? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (SafetyFilter.isDistress(t) || COMFORT_WORDS.containsMatchIn(t)) return FaceState.COMFORT
        if (HAPPY_WORDS.containsMatchIn(t)) return FaceState.HAPPY
        return null
    }

    /** 当前是否在免打扰时段（支持跨零点，如 23:30–07:30；起止相同 = 不免打扰） */
    fun isQuiet(nowMin: Int, startMin: Int, endMin: Int): Boolean {
        if (startMin == endMin) return false
        return if (startMin < endMin) nowMin in startMin until endMin else nowMin >= startMin || nowMin < endMin
    }

    fun hhmmToMin(s: String, fallback: Int): Int {
        val m = Regex("""(\d{1,2})\s*[:：]\s*(\d{1,2})""").find(s.trim()) ?: return fallback
        return (m.groupValues[1].toInt().coerceIn(0, 23)) * 60 + m.groupValues[2].toInt().coerceIn(0, 59)
    }

    /**
     * 综合判定。优先级：冷战（生气）> 专注进行中 > 正在思考（加载模型/生成）> 刚聊到的情绪（moodUntil 之内）> 免打扰时段（睡觉）> 待机。
     * 不开骂的人设（树洞）永远不会生气：冷战/专注都落回 COMFORT/IDLE，保持它"只听你说"的性格。
     */
    fun pick(
        roast: Boolean, coldWar: Boolean, focusRunning: Boolean, thinking: Boolean,
        mood: FaceState?, quiet: Boolean,
    ): FaceState {
        if (coldWar) return if (roast) FaceState.ANGRY else FaceState.COMFORT
        if (focusRunning) return if (roast) FaceState.FOCUS else FaceState.IDLE
        if (thinking) return FaceState.THINKING
        if (mood != null) return mood
        if (quiet) return FaceState.SLEEP
        return FaceState.IDLE
    }
}
