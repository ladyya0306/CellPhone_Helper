package com.example.llama

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 全局崩溃捕获 —— 因为 vivo ROM 会吞掉第三方 App 的 Log.* 输出，
 * 出问题只能靠"写文件"排查（这个坑已经踩过两次）。
 *
 * 任何未捕获异常都会把堆栈写到 files/crash.log，可以直接 run-as cat 出来看。
 * 保留最近 20 次崩溃，超过就滚动覆盖。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        // 用户给人设起的名字：每个进程启动时读入，之后话术包、提示词、专注台词都按新名字取
        try { PersonaNames.replaceAll(AppSettings(this).personaAliases()) } catch (_: Throwable) {}
        // 专注模式要知道"用户是不是还在本应用里"：本应用任一界面可见就算在（切到别的应用、锁屏、回桌面才算离开）
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) { FocusRuntime.started.incrementAndGet() }
            override fun onActivityStopped(activity: android.app.Activity) { FocusRuntime.started.updateAndGet { maxOf(0, it - 1) } }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }

    /** 系统在后台内存紧张时先交出模型（它占 4–5GB，是首选被杀对象），见 [ModelLifecycle] */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // :llm 进程里没有引擎代理，只在主进程处理
        if (level >= TRIM_MEMORY_BACKGROUND && !getProcessName().endsWith(":llm")) {
            AssistantRuntime.scope.launch { ModelLifecycle.unload(this@App, "系统内存紧张 level=$level") }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(thread, throwable)
            } catch (_: Throwable) {
                // 崩溃日志本身失败就放弃，别影响原流程
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = buildString {
            append("===== ").append(stamp).append("  线程: ").append(thread.name).append(" =====\n")
            append(throwable.javaClass.name).append(": ").append(throwable.message ?: "").append('\n')
            append(sw.toString()).append('\n')
        }
        val f = File(filesDir, "crash.log")
        // 滚动：超过 128KB 就只保留新内容
        val old = if (f.exists() && f.length() < 128 * 1024) f.readText() else ""
        f.writeText(entry + old)
    }
}
