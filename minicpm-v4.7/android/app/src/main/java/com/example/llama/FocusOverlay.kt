package com.example.llama

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 地狱难度的悬浮窗：把台词用一张卡片盖在别的应用上方几秒（点一下回到专注界面）。
 * 规则在 [FocusOverlayPolicy]；「在其他应用上层显示」权限只能由用户在系统设置里手动打开，这里只负责带用户去那一页。
 */
class FocusOverlay(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(ctx)

    fun show(avatar: String, name: String, text: String, durationMs: Long) {
        handler.post {
            remove()
            val d = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(16))
                background = GradientDrawable().apply { setColor(Color.parseColor("#E61F2328")); cornerRadius = dp(18).toFloat() }
                addView(TextView(ctx).apply { this.text = "$avatar $name"; setTextColor(Color.parseColor("#FFB4A9")); textSize = 12f })
                addView(TextView(ctx).apply { this.text = text; setTextColor(Color.WHITE); textSize = 17f; setPadding(0, dp(6), 0, 0) })
                setOnClickListener {
                    remove()
                    ctx.startActivity(Intent(ctx, FocusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(96); x = 0 }
            card.layoutParams = LinearLayout.LayoutParams(-1, -2)
            try {
                wm.addView(card, lp)
                view = card
                handler.postDelayed({ remove() }, durationMs)
            } catch (_: Exception) { view = null }
        }
    }

    fun remove() {
        handler.removeCallbacksAndMessages(null)
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }

    companion object {
        fun settingsIntent(ctx: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
