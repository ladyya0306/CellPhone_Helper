package com.example.llama

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/** 把 [Skin] 的颜色套到界面上的几个公共小工具（各个页面都是代码建 View，统一从这里取色） */
object SkinUi {

    /** 页面底色、顶栏、状态栏/导航栏图标明暗 */
    fun applyChrome(a: AppCompatActivity, sk: Skin, toolbar: MaterialToolbar?, bar: View? = null) {
        val root = (a.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        root?.setBackgroundColor(sk.bg)
        bar?.setBackgroundColor(sk.bar)
        toolbar?.apply {
            if (bar == null) setBackgroundColor(sk.bar)
            setTitleTextColor(sk.text); setSubtitleTextColor(sk.textDim)
            navigationIcon?.mutate()?.setTint(sk.text)
            overflowIcon?.mutate()?.setTint(sk.text)
            menu?.let { m -> for (i in 0 until m.size()) m.getItem(i).icon?.mutate()?.setTint(sk.text) }
        }
        a.window.statusBarColor = sk.bar
        a.window.navigationBarColor = sk.bg
        WindowCompat.getInsetsController(a.window, a.window.decorView).apply {
            isAppearanceLightStatusBars = !sk.dark
            isAppearanceLightNavigationBars = !sk.dark
        }
    }

    fun tint(sw: MaterialSwitch, sk: Skin) {
        val on = android.R.attr.state_checked
        sw.thumbTintList = ColorStateList(arrayOf(intArrayOf(on), intArrayOf()), intArrayOf(0xFFFFFFFF.toInt(), Skin.blend(sk.card, sk.textDim, 0.8f)))
        sw.trackTintList = ColorStateList(arrayOf(intArrayOf(on), intArrayOf()), intArrayOf(sk.accent, Skin.blend(sk.card, sk.textDim, 0.25f)))
        sw.setTextColor(sk.text)
    }

    /** filled = 实心主按钮；否则是描边按钮 */
    fun tint(b: MaterialButton, sk: Skin, filled: Boolean) {
        if (filled) {
            b.backgroundTintList = ColorStateList.valueOf(sk.accent)
            b.setTextColor(0xFFFFFFFF.toInt())
        } else {
            b.setTextColor(sk.accent)
            b.strokeColor = ColorStateList.valueOf(Skin.blend(sk.card, sk.accent, 0.5f))
        }
    }

    /** 聊天气泡：助手的左上角小圆角（像带了个小尾巴指向头像），用户的右下角小圆角 */
    fun bubble(sk: Skin, isUser: Boolean, density: Float): GradientDrawable {
        val r = sk.bubbleRadiusDp * density
        val t = 4f * density
        return GradientDrawable().apply {
            setColor(if (isUser) sk.bubbleUser else sk.bubbleBot)
            cornerRadii = if (isUser) floatArrayOf(r, r, r, r, t, t, r, r) else floatArrayOf(t, t, r, r, r, r, r, r)
            val sw = hasAlpha(sk.bubbleStroke)
            if (!isUser && sw) setStroke((1 * density).toInt().coerceAtLeast(1), sk.bubbleStroke)
        }
    }

    private fun hasAlpha(c: Int) = ((c ushr 24) and 0xFF) > 0
}
