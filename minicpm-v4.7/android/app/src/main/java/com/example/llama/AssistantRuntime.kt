package com.example.llama

import android.content.Context
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 进程级运行时。
 *
 * 旧版把一轮对话放在 Activity 的 lifecycleScope 里，并在 onStop() 里 cancel ——
 * 用户发完消息切去看别的 App（或闹钟 Intent 把时钟/vivo 跳转确认弹到前台），
 * 这一轮就被取消：工具可能没执行、结果没落库、输入框一直是禁用状态。
 * 现在一轮对话跑在进程级作用域里，Activity 只负责显示；回来时从数据库恢复。
 */
object AssistantRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var busy: Boolean = false
    /** 调试用：由 MainActivity 在前台时登记，TestHook 通过它把文字"打进"真实聊天界面（action: "new" 或 "send"）。 */
    @Volatile var uiInject: ((String, String) -> Unit)? = null
}

/**
 * 模型的内存生命周期。
 *
 * 真机证据（2026-10-19 `dumpsys activity exit-info`）：模型常驻时进程 RSS 4–5.5GB，
 * vivo 在后台把它当首选清理对象——一夜 8 次 LOW_MEMORY，打开 App 加载模型的十几秒里也被杀，
 * 还出现过一次 5 秒 ANR；进程被杀时系统一并清掉它登记的闹钟（6:30 的闹钟 07:06 才响）。
 * 做法：App 退到后台 10 秒、或系统发出后台内存紧张信号时，卸载模型（进程回到约 100MB）；
 * 回到前台再重新加载（约 17 秒，期间闹钟/提醒/待办等确定性指令照常可用）。
 */
object ModelLifecycle {
    /**
     * 退到后台后立即卸载，不能再等：真机上进程退到后台不久就被 vivo 冻结，协程里的定时器不再走
     * （实测排期 10 秒的卸载，要到 39 秒后用户把 App 拉回前台、进程解冻那一刻才执行）。
     */
    const val UNLOAD_AFTER_BACKGROUND_MS = 0L

    /** 加载与卸载互斥 */
    val mutex = Mutex()

    @Volatile
    var visible: Boolean = false
        private set
    private var job: Job? = null

    fun onForeground() {
        visible = true
        job?.cancel(); job = null
    }

    fun onBackground(ctx: Context) {
        visible = false
        job?.cancel()
        val app = ctx.applicationContext
        job = AssistantRuntime.scope.launch {
            if (UNLOAD_AFTER_BACKGROUND_MS > 0) delay(UNLOAD_AFTER_BACKGROUND_MS)
            unload(app, "退到后台")
        }
    }

    suspend fun unload(ctx: Context, why: String) = withContext(Dispatchers.Default) {
        val e = LlmEngine.get(ctx.applicationContext)
        trace(ctx, "unload 检查: visible=$visible busy=${AssistantRuntime.busy} state=${e.state.value.javaClass.simpleName} ($why)")
        if (visible || AssistantRuntime.busy) return@withContext
        mutex.withLock {
            if (visible || AssistantRuntime.busy) return@withLock
            if (e.state.value is InferenceEngine.State.ModelReady) {
                trace(ctx, "unload: $why")
                try { e.cleanUp() } catch (ex: Exception) { trace(ctx, "unload 失败 ${ex.message}") }
            }
        }
    }

    fun trace(ctx: Context, msg: String) {
        try {
            File(ctx.filesDir, "boot.log").appendText(
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + msg + "\n"
            )
        } catch (_: Exception) {
        }
    }
}
