package com.example.llama

/**
 * 界面皮肤与每个人设的头像造型。纯 Kotlin（颜色用 ARGB 整数），可单测。
 *
 * 三套皮肤：
 *   · cream   奶油手帐（默认）：暖米白底，每个人设用自己的主题色，大圆角卡片，气泡带小尾巴
 *   · night   深夜陪伴：近黑蓝底，主题色提亮，气泡带淡淡的描边光
 *   · classic 经典：改版前的灰白 + 蓝
 */
data class Skin(
    val id: String, val label: String, val desc: String,
    val bg: Int, val card: Int, val bar: Int, val text: Int, val textDim: Int, val divider: Int,
    val accent: Int, val accentSoft: Int,
    val bubbleBot: Int, val bubbleBotText: Int, val bubbleUser: Int, val bubbleUserText: Int, val bubbleStroke: Int,
    val cardRadiusDp: Int, val bubbleRadiusDp: Int, val dark: Boolean,
) {
    companion object {
        const val CREAM = "cream"
        const val NIGHT = "night"
        const val CLASSIC = "classic"
        val IDS = listOf(CREAM, NIGHT, CLASSIC)
        val LABELS = mapOf(CREAM to "奶油手帐", NIGHT to "深夜陪伴", CLASSIC to "经典")

        fun normalize(id: String?): String = if (id in IDS) id!! else CREAM

        /** 按皮肤编号 + 当前人设取调色板。奶油/深夜的主题色跟着人设走（人设不是皮肤，是关系模式——所以颜色也跟着关系走） */
        fun of(id: String?, personaId: String?): Skin {
            val look = AvatarLooks.of(personaId)
            return when (normalize(id)) {
                NIGHT -> Skin(
                    NIGHT, "深夜陪伴", "近黑蓝底，主题色提亮，晚上用不刺眼",
                    bg = 0xFF12151D.toInt(), card = 0xFF1C212C.toInt(), bar = 0xFF171B24.toInt(), text = 0xFFE8EAF0.toInt(), textDim = 0xFF9AA1B2.toInt(), divider = 0xFF2A3040.toInt(),
                    accent = lighten(look.accent, 0.35f), accentSoft = blend(0xFF1C212C.toInt(), look.accent, 0.22f),
                    bubbleBot = 0xFF222836.toInt(), bubbleBotText = 0xFFE8EAF0.toInt(), bubbleUser = blend(0xFF1C212C.toInt(), look.accent, 0.55f), bubbleUserText = 0xFFFFFFFF.toInt(),
                    bubbleStroke = blend(0xFF222836.toInt(), look.accent, 0.5f), cardRadiusDp = 18, bubbleRadiusDp = 18, dark = true,
                )
                CLASSIC -> Skin(
                    CLASSIC, "经典", "改版前的灰白底和蓝色，最朴素",
                    bg = 0xFFF5F6F8.toInt(), card = 0xFFFFFFFF.toInt(), bar = 0xFFFFFFFF.toInt(), text = 0xFF1F2328.toInt(), textDim = 0xFF6B7280.toInt(), divider = 0xFFE3E6EA.toInt(),
                    accent = 0xFF2F6FED.toInt(), accentSoft = 0x142F6FED,
                    bubbleBot = 0xFFE5E5EA.toInt(), bubbleBotText = 0xFF000000.toInt(), bubbleUser = 0xFF4285F4.toInt(), bubbleUserText = 0xFFFFFFFF.toInt(),
                    bubbleStroke = 0x00000000, cardRadiusDp = 14, bubbleRadiusDp = 16, dark = false,
                )
                else -> Skin(
                    CREAM, "奶油手帐", "暖米白底，每个人设一个主题色，圆润柔和",
                    bg = 0xFFFBF6EE.toInt(), card = 0xFFFFFFFF.toInt(), bar = 0xFFFFFBF4.toInt(), text = 0xFF3B2F2A.toInt(), textDim = 0xFF8C7B70.toInt(), divider = 0xFFF0E6D8.toInt(),
                    accent = look.accent, accentSoft = blend(0xFFFFFFFF.toInt(), look.accent, 0.14f),
                    bubbleBot = 0xFFFFFFFF.toInt(), bubbleBotText = 0xFF3B2F2A.toInt(), bubbleUser = look.accent, bubbleUserText = 0xFFFFFFFF.toInt(),
                    bubbleStroke = blend(0xFFFFFFFF.toInt(), look.accent, 0.28f), cardRadiusDp = 20, bubbleRadiusDp = 18, dark = false,
                )
            }
        }

        /** 把 a 向 b 混合 t（0..1），忽略 alpha，结果不透明 */
        fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        fun lighten(c: Int, t: Float): Int = blend(c, 0xFFFFFFFF.toInt(), t)

        /** 相对亮度（0..1），用来自检文字与底色的对比度 */
        fun luminance(c: Int): Double {
            fun f(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
            return 0.2126 * f((c shr 16) and 0xFF) + 0.7152 * f((c shr 8) and 0xFF) + 0.0722 * f(c and 0xFF)
        }

        fun contrast(a: Int, b: Int): Double {
            val la = luminance(a); val lb = luminance(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }
    }
}

/** 一个人设的头像造型：全部是数据，画图的人（[AvatarView]）只读它 */
data class AvatarLook(
    val id: String,
    val accent: Int,      // 主题色（也是气泡/按钮的颜色）
    val bg: Int,          // 头像底色
    val hair: Int,
    val skin: Int = 0xFFFFE4D0.toInt(),
    val eyeColor: Int,
    val feature: Feature, // 辨识特征
) {
    enum class Feature { CAT_EARS, LONG_WAVY, TWIN_TAILS, AHOGE, STRAIGHT_BANGS, DOG_EARS, HOOD_CANDLE, GLASSES_MESSY }
}

object AvatarLooks {
    val ALL: List<AvatarLook> = listOf(
        AvatarLook("catgirl", 0xFFD9683E.toInt(), 0xFFFFE3D3.toInt(), 0xFFF2A07B.toInt(), eyeColor = 0xFF8A4B2D.toInt(), feature = AvatarLook.Feature.CAT_EARS),
        AvatarLook("oneesan", 0xFF5B7FC4.toInt(), 0xFFDDE6F7.toInt(), 0xFF3B4A7A.toInt(), eyeColor = 0xFF2E3B66.toInt(), feature = AvatarLook.Feature.LONG_WAVY),
        AvatarLook("tsundere", 0xFFC8415B.toInt(), 0xFFF9D9DE.toInt(), 0xFFB5303F.toInt(), eyeColor = 0xFF6E1B2B.toInt(), feature = AvatarLook.Feature.TWIN_TAILS),
        AvatarLook("genki", 0xFFB07A00.toInt(), 0xFFFFF1B8.toInt(), 0xFFF2B521.toInt(), eyeColor = 0xFF8A5A00.toInt(), feature = AvatarLook.Feature.AHOGE),
        AvatarLook("kuudere", 0xFF4F9CB8.toInt(), 0xFFE3F1F8.toInt(), 0xFFB9D3E3.toInt(), eyeColor = 0xFF3C6E86.toInt(), feature = AvatarLook.Feature.STRAIGHT_BANGS),
        AvatarLook("puppy", 0xFFB5733A.toInt(), 0xFFF2E3CF.toInt(), 0xFF8B5A2B.toInt(), eyeColor = 0xFF3E2A18.toInt(), feature = AvatarLook.Feature.DOG_EARS),
        AvatarLook("listener", 0xFFB8892B.toInt(), 0xFFF6E9C8.toInt(), 0xFF4A3B2E.toInt(), eyeColor = 0xFF3A2D22.toInt(), feature = AvatarLook.Feature.HOOD_CANDLE),
        AvatarLook("buddy", 0xFF5E8A6B.toInt(), 0xFFDCE8DE.toInt(), 0xFF7C8A84.toInt(), eyeColor = 0xFF2F3A34.toInt(), feature = AvatarLook.Feature.GLASSES_MESSY),
    )
    private val DEFAULT = AvatarLook("generic", 0xFF2F6FED.toInt(), 0xFFE5E9F5.toInt(), 0xFF555555.toInt(), eyeColor = 0xFF222222.toInt(), feature = AvatarLook.Feature.STRAIGHT_BANGS)
    fun of(id: String?): AvatarLook = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
