package com.example.llama

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 人设头像：用 Canvas 现画的矢量脸（不依赖图片、不联网），造型来自 [AvatarLooks]，表情来自 [Faces.params]。
 * 画在 100×100 的单位坐标里再缩放，所以任何尺寸都清晰。
 *
 * animated = true 时有呼吸、眨眼、思考时省略号跳动、睡觉时 z 飘动；列表里的小头像用 animated = false（静态，不耗电）。
 */
class AvatarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private var look: AvatarLook = AvatarLooks.of(null)
    private var state: FaceState = FaceState.IDLE
    private var animated = false
    private var phase = 0f           // 0..1 循环
    private var animator: ValueAnimator? = null
    private var ringColor = 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val path = Path()
    private val rect = RectF()

    val currentState: FaceState get() = state

    fun setPersona(personaId: String?) { look = AvatarLooks.of(personaId); invalidate() }

    fun setFace(s: FaceState) { if (s != state) { state = s; invalidate() } }

    /** 头像外圈颜色（和当前皮肤的卡片色呼应）；0 = 不画 */
    fun setRing(color: Int) { ringColor = color; invalidate() }

    fun setAnimated(on: Boolean) {
        animated = on
        if (on && isAttachedToWindow) startAnim() else stopAnim()
    }

    private fun startAnim() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4200; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopAnim() { animator?.cancel(); animator = null; phase = 0f; invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (animated) startAnim() }
    override fun onDetachedFromWindow() { stopAnim(); super.onDetachedFromWindow() }

    override fun onMeasure(w: Int, h: Int) {
        val def = (44 * resources.displayMetrics.density).toInt()
        val ws = MeasureSpec.getSize(w); val hs = MeasureSpec.getSize(h)
        val sw = if (MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED) def else ws
        val sh = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) def else hs
        val s = minOf(sw, sh)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) / 100f
        c.save()
        c.scale(s, s)
        // 圆形底
        fill.color = look.bg; c.drawCircle(50f, 50f, 50f, fill)
        c.save()
        path.reset(); path.addCircle(50f, 50f, 50f, Path.Direction.CW); c.clipPath(path)
        // 呼吸：待机/专注时头整体轻轻起伏
        val breathe = if (animated && (state == FaceState.IDLE || state == FaceState.SLEEP || state == FaceState.FOCUS)) 1f + 0.02f * Math.sin(phase * 2 * Math.PI).toFloat() else 1f
        c.translate(50f, 96f); c.scale(breathe, breathe); c.translate(-50f, -96f)
        drawBackHair(c)
        drawBody(c)
        drawHead(c)
        drawFrontHair(c)
        drawFace(c)
        c.restore()
        drawExtras(c)
        if (ringColor != 0) {
            line.color = ringColor; line.strokeWidth = 3f; c.drawCircle(50f, 50f, 48.5f, line)
        }
        c.restore()
    }

    // ------------------------------------------------------------------ 头发/身体/脸
    private fun drawBackHair(c: Canvas) {
        fill.color = look.hair
        when (look.feature) {
            AvatarLook.Feature.LONG_WAVY -> { rect.set(14f, 20f, 86f, 98f); c.drawRoundRect(rect, 30f, 30f, fill) }
            AvatarLook.Feature.STRAIGHT_BANGS -> { rect.set(17f, 22f, 83f, 94f); c.drawRoundRect(rect, 12f, 12f, fill) }
            AvatarLook.Feature.TWIN_TAILS -> {
                c.drawCircle(18f, 66f, 12f, fill); c.drawCircle(82f, 66f, 12f, fill)
                rect.set(12f, 56f, 24f, 90f); c.drawOval(rect, fill); rect.set(76f, 56f, 88f, 90f); c.drawOval(rect, fill)
            }
            AvatarLook.Feature.CAT_EARS -> {
                for (sx in intArrayOf(-1, 1)) {
                    fun x(v: Float) = 50f + sx * (v - 50f)
                    fill.color = look.hair
                    path.reset(); path.moveTo(x(24f), 44f); path.lineTo(x(26f), 8f); path.lineTo(x(50f), 28f); path.close(); c.drawPath(path, fill)
                    fill.color = 0xFFFFB6C1.toInt()
                    path.reset(); path.moveTo(x(29f), 36f); path.lineTo(x(30f), 17f); path.lineTo(x(43f), 29f); path.close(); c.drawPath(path, fill)
                }
            }
            AvatarLook.Feature.DOG_EARS -> {
                for (sx in intArrayOf(-1, 1)) {
                    c.save(); c.rotate(sx * -14f, 50f + sx * 30f, 40f)
                    rect.set(if (sx < 0) 8f else 78f, 30f, if (sx < 0) 24f else 94f, 68f); fill.color = 0xFF6B4423.toInt(); c.drawOval(rect, fill)
                    c.restore()
                }
            }
            AvatarLook.Feature.HOOD_CANDLE -> { rect.set(12f, 8f, 88f, 104f); c.drawRoundRect(rect, 38f, 44f, fill) }
            else -> {}
        }
    }

    private fun drawBody(c: Canvas) {
        // 肩膀：用主题色的衣服，让头像看起来不是悬空的头
        fill.color = look.accent
        rect.set(18f, 84f, 82f, 126f); c.drawRoundRect(rect, 22f, 22f, fill)
        fill.color = look.skin
        rect.set(43f, 78f, 57f, 92f); c.drawRoundRect(rect, 5f, 5f, fill)
    }

    private fun drawHead(c: Canvas) {
        fill.color = look.skin
        rect.set(22f, 24f, 78f, 86f); c.drawOval(rect, fill)
    }

    private fun drawFrontHair(c: Canvas) {
        fill.color = look.hair
        path.reset()
        path.moveTo(20f, 56f)
        path.cubicTo(18f, 22f, 40f, 12f, 50f, 12f)
        path.cubicTo(60f, 12f, 82f, 22f, 80f, 56f)
        when (look.feature) {
            AvatarLook.Feature.STRAIGHT_BANGS -> { path.lineTo(76f, 56f); path.lineTo(74f, 40f); path.lineTo(26f, 40f); path.lineTo(24f, 56f) }
            AvatarLook.Feature.GLASSES_MESSY -> { path.lineTo(76f, 46f); path.lineTo(70f, 36f); path.lineTo(62f, 44f); path.lineTo(54f, 32f); path.lineTo(44f, 44f); path.lineTo(36f, 34f); path.lineTo(30f, 46f); path.lineTo(24f, 50f) }
            else -> {
                path.lineTo(74f, 54f)
                path.quadTo(70f, 36f, 62f, 40f); path.quadTo(56f, 48f, 50f, 36f); path.quadTo(44f, 48f, 38f, 40f); path.quadTo(30f, 36f, 26f, 54f)
            }
        }
        path.close(); c.drawPath(path, fill)
        when (look.feature) {
            AvatarLook.Feature.AHOGE -> {
                line.color = look.hair; line.strokeWidth = 3.4f
                path.reset(); path.moveTo(50f, 14f); path.cubicTo(48f, 4f, 60f, 0f, 62f, 8f); path.cubicTo(63f, 13f, 57f, 14f, 56f, 10f); c.drawPath(path, line)
            }
            AvatarLook.Feature.TWIN_TAILS -> {
                fill.color = look.accent
                c.drawCircle(25f, 44f, 5f, fill); c.drawCircle(75f, 44f, 5f, fill)
            }
            AvatarLook.Feature.HOOD_CANDLE -> {
                // 头顶的烛火：树洞陪你的那一点光
                val flick = if (animated) 1f + 0.12f * Math.sin(phase * 2 * Math.PI * 3).toFloat() else 1f
                fill.color = 0xFFFFD66B.toInt()
                path.reset(); path.moveTo(50f, 1f); path.quadTo(58f, 10f, 50f, 14f * flick); path.quadTo(42f, 10f, 50f, 1f); c.drawPath(path, fill)
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ 表情
    private fun drawFace(c: Canvas) {
        val p = Faces.params(state)
        val blink = animated && phase in 0.93f..0.975f
        val lookDx = if (p.eye == FaceParams.Eye.SIDE) 1.6f + if (animated) 1.4f * Math.sin(phase * 2 * Math.PI * 2).toFloat() else 0f else 0f
        val lookDy = if (p.eye == FaceParams.Eye.SIDE) -1.4f else 0f

        // 腮红
        if (p.blush || (look.feature == AvatarLook.Feature.CAT_EARS && state == FaceState.IDLE)) {
            fill.color = Color.argb(if (p.blush) 120 else 70, 255, 120, 140)
            rect.set(26f, 62f, 38f, 70f); c.drawOval(rect, fill); rect.set(62f, 62f, 74f, 70f); c.drawOval(rect, fill)
        }
        // 眉
        line.color = Color.argb(200, Color.red(look.hair) / 2, Color.green(look.hair) / 2, Color.blue(look.hair) / 2); line.strokeWidth = 2.2f
        fun brow(x1: Float, y1: Float, x2: Float, y2: Float) { c.drawLine(x1, y1, x2, y2, line) }
        when (p.brow) {
            FaceParams.Brow.NEUTRAL -> { brow(33f, 44f, 44f, 44f); brow(56f, 44f, 67f, 44f) }
            FaceParams.Brow.SAD -> { brow(32f, 47f, 44f, 42f); brow(56f, 42f, 68f, 47f) }
            FaceParams.Brow.ANGRY -> { brow(32f, 41f, 44f, 46f); brow(56f, 46f, 68f, 41f) }
            FaceParams.Brow.RAISED -> { brow(33f, 44f, 44f, 44f); brow(56f, 39f, 67f, 41f) }
            FaceParams.Brow.NONE -> {}
        }
        // 眼
        val eye = if (blink && (p.eye == FaceParams.Eye.OPEN || p.eye == FaceParams.Eye.WIDE_SHINE || p.eye == FaceParams.Eye.SIDE)) FaceParams.Eye.CLOSED else p.eye
        for (ex in floatArrayOf(39f, 61f)) drawEye(c, eye, ex + lookDx, 55f + lookDy)
        // 眼镜（摸鱼搭子）
        if (look.feature == AvatarLook.Feature.GLASSES_MESSY) {
            line.color = 0xFF3A3A3A.toInt(); line.strokeWidth = 1.8f
            c.drawCircle(39f, 55f, 8.6f, line); c.drawCircle(61f, 55f, 8.6f, line); c.drawLine(47.6f, 54f, 52.4f, 54f, line)
        }
        // 猫须
        if (look.feature == AvatarLook.Feature.CAT_EARS) {
            line.color = Color.argb(110, 120, 80, 60); line.strokeWidth = 1.1f
            for (dy in floatArrayOf(-2f, 1.5f)) { c.drawLine(22f, 66f + dy, 31f, 67f + dy * 0.5f, line); c.drawLine(78f, 66f + dy, 69f, 67f + dy * 0.5f, line) }
        }
        drawMouth(c, p.mouth)
    }

    private fun drawEye(c: Canvas, e: FaceParams.Eye, x: Float, y: Float) {
        when (e) {
            FaceParams.Eye.OPEN, FaceParams.Eye.SIDE -> {
                fill.color = look.eyeColor; rect.set(x - 4.2f, y - 5.4f, x + 4.2f, y + 5.4f); c.drawOval(rect, fill)
                fill.color = Color.WHITE; c.drawCircle(x + 1.3f, y - 2f, 1.5f, fill)
            }
            FaceParams.Eye.WIDE_SHINE -> {
                fill.color = look.eyeColor; rect.set(x - 5f, y - 6.4f, x + 5f, y + 6.4f); c.drawOval(rect, fill)
                fill.color = Color.WHITE; c.drawCircle(x + 1.6f, y - 2.6f, 2f, fill); c.drawCircle(x - 1.6f, y + 2.4f, 1f, fill)
            }
            FaceParams.Eye.HALF -> {
                fill.color = look.eyeColor; rect.set(x - 4.4f, y - 5f, x + 4.4f, y + 5f); c.drawArc(rect, 0f, 180f, true, fill)
                line.color = look.eyeColor; line.strokeWidth = 2f; c.drawLine(x - 5f, y, x + 5f, y, line)
            }
            FaceParams.Eye.NARROW -> {
                fill.color = look.eyeColor; rect.set(x - 4.6f, y - 2.8f, x + 4.6f, y + 3.4f); c.drawOval(rect, fill)
                fill.color = Color.WHITE; c.drawCircle(x + 1.4f, y - 0.8f, 1f, fill)
            }
            FaceParams.Eye.ARC_UP -> {
                line.color = look.eyeColor; line.strokeWidth = 2.6f; rect.set(x - 5f, y - 3f, x + 5f, y + 6f); c.drawArc(rect, 200f, 140f, false, line)
            }
            FaceParams.Eye.CLOSED -> {
                line.color = look.eyeColor; line.strokeWidth = 2.4f; rect.set(x - 5f, y - 5f, x + 5f, y + 3f); c.drawArc(rect, 20f, 140f, false, line)
            }
        }
    }

    private fun drawMouth(c: Canvas, m: FaceParams.Mouth) {
        val mouth = 0xFFB5535A.toInt()
        line.color = mouth; line.strokeWidth = 2.2f
        when (m) {
            FaceParams.Mouth.SMALL -> { rect.set(45f, 66f, 55f, 72f); c.drawArc(rect, 20f, 140f, false, line) }
            FaceParams.Mouth.FLAT -> c.drawLine(45f, 70f, 55f, 70f, line)
            FaceParams.Mouth.SMILE -> { rect.set(42f, 62f, 58f, 74f); c.drawArc(rect, 20f, 140f, false, line) }
            FaceParams.Mouth.GRIN -> {
                fill.color = 0xFF8E2F3A.toInt()
                path.reset(); path.moveTo(41f, 65f); path.quadTo(50f, 66f, 59f, 65f); path.quadTo(58f, 77f, 50f, 77f); path.quadTo(42f, 77f, 41f, 65f); path.close(); c.drawPath(path, fill)
                fill.color = Color.WHITE; rect.set(44f, 65f, 56f, 68f); c.drawRect(rect, fill)
                if (look.id == "puppy") { fill.color = 0xFFFF8FA0.toInt(); rect.set(46f, 72f, 54f, 80f); c.drawOval(rect, fill) }
            }
            FaceParams.Mouth.FROWN -> { rect.set(43f, 68f, 57f, 78f); c.drawArc(rect, 200f, 140f, false, line) }
            FaceParams.Mouth.OPEN_O -> { fill.color = 0xFF9A4650.toInt(); rect.set(47f, 66f, 53f, 74f + if (animated) 1.2f * Math.sin(phase * 2 * Math.PI).toFloat() else 0f); c.drawOval(rect, fill) }
            FaceParams.Mouth.WAVY -> {
                path.reset(); path.moveTo(42f, 70f); path.quadTo(46f, 66f, 50f, 70f); path.quadTo(54f, 74f, 58f, 70f); c.drawPath(path, line)
            }
        }
    }

    // ------------------------------------------------------------------ 脸旁的小标记
    private fun drawExtras(c: Canvas) {
        when (Faces.params(state).extra) {
            FaceParams.Extra.DOTS -> {
                val active = if (animated) (phase * 3).toInt() % 3 else 2
                fill.color = look.accent
                for (i in 0..2) {
                    fill.alpha = if (i <= active) 255 else 90
                    c.drawCircle(70f + i * 9f, 18f - i * 3f, 3.4f, fill)
                }
                fill.alpha = 255
            }
            FaceParams.Extra.ZZZ -> {
                val rise = if (animated) (phase % 1f) * 8f else 0f
                text.color = look.accent; text.textSize = 15f; c.drawText("z", 72f, 26f - rise * 0.5f, text)
                text.textSize = 11f; c.drawText("z", 82f, 14f - rise * 0.5f, text)
            }
            FaceParams.Extra.VEIN -> {
                line.color = 0xFFE5484D.toInt(); line.strokeWidth = 2.4f
                val bx = 75f; val by = 26f
                path.reset(); path.moveTo(bx - 5, by - 2); path.quadTo(bx - 2, by - 2, bx - 2, by - 5); path.moveTo(bx + 5, by - 2); path.quadTo(bx + 2, by - 2, bx + 2, by - 5)
                path.moveTo(bx - 5, by + 2); path.quadTo(bx - 2, by + 2, bx - 2, by + 5); path.moveTo(bx + 5, by + 2); path.quadTo(bx + 2, by + 2, bx + 2, by + 5)
                c.drawPath(path, line)
            }
            FaceParams.Extra.SPARKLE -> {
                val tw = if (animated) 0.75f + 0.25f * Math.sin(phase * 2 * Math.PI * 2).toFloat() else 1f
                fill.color = 0xFFFFC83D.toInt()
                star(c, 20f, 26f, 7f * tw); star(c, 80f, 20f, 5f * tw)
            }
            FaceParams.Extra.SWEAT -> {
                fill.color = 0xFF7EC8F2.toInt()
                path.reset(); path.moveTo(78f, 24f); path.quadTo(83f, 33f, 78f, 36f); path.quadTo(73f, 33f, 78f, 24f); c.drawPath(path, fill)
            }
            FaceParams.Extra.GLASS_GLINT -> {
                line.color = Color.argb(200, 255, 255, 255); line.strokeWidth = 1.6f
                c.drawLine(33f, 50f, 38f, 46f, line); c.drawLine(55f, 50f, 60f, 46f, line)
            }
            FaceParams.Extra.NONE -> {}
        }
    }

    private fun star(c: Canvas, cx: Float, cy: Float, r: Float) {
        path.reset()
        path.moveTo(cx, cy - r); path.quadTo(cx, cy, cx + r, cy); path.quadTo(cx, cy, cx, cy + r); path.quadTo(cx, cy, cx - r, cy); path.quadTo(cx, cy, cx, cy - r); path.close()
        c.drawPath(path, fill)
    }
}
