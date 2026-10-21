package com.example.llama

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings

/**
 * 专注模式「进阶档」的数据来源：系统「使用情况访问」。
 * 权限只能由用户在系统设置里手动打开（我们只负责跳到那一页），读到的数据只在本机用于判断"现在在哪个应用"，不存储、不上传。
 */
object FocusUsage {

    fun hasAccess(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    fun settingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 最近一次切到前台的应用包名；窗口内没有事件返回 null（沿用上一次的结果由调用方决定） */
    fun foregroundPackage(ctx: Context, nowMs: Long = System.currentTimeMillis(), windowMs: Long = 15_000L): String? = try {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val ev = usm.queryEvents(nowMs - windowMs, nowMs)
        val e = UsageEvents.Event()
        var pkg: String? = null
        var at = 0L
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.timeStamp >= at) { pkg = e.packageName; at = e.timeStamp }
        }
        pkg
    } catch (_: Exception) { null }

    data class App(val pkg: String, val label: String)

    /** 手机里能从桌面打开的应用（给用户勾选分心名单）。需要清单里的 <queries> 声明 */
    fun launchableApps(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label }
    }

    fun labelOf(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) { pkg }

    /** 常见的"容易分心"的应用包名片段，用来给勾选名单做默认建议（用户可改） */
    private val SUGGEST = listOf(
        "com.ss.android.ugc", "com.smile.gifmaker", "com.kuaishou", "tv.danmaku.bili", "com.sina.weibo", "com.xingin.xhs",
        "com.tencent.mm", "com.tencent.mobileqq", "com.zhihu", "com.tencent.qqlive", "com.qiyi", "com.youku", "com.netease.cloudmusic",
        "com.tencent.tmgp", "com.miHoYo", "com.taobao", "com.jingdong", "com.xunmeng",
    )

    fun suggested(apps: List<App>): Set<String> = apps.filter { a -> SUGGEST.any { a.pkg.startsWith(it) || a.pkg.contains(it) } }.map { it.pkg }.toSet()
}
