package com.example.llama

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 推理进程（清单里 `android:process=":llm"`）。只做一件事：把 [InferenceEngine]（llama.cpp）暴露成 Binder。
 *
 * 这个进程里不碰数据库、待办、提醒；主进程不再加载 libai-chat.so。
 * 所有客户端解绑后系统销毁本服务，[onDestroy] 直接结束进程——原生内存整体还给系统
 * （实测 `cleanUp()` 之后原生堆仍剩约 120MB 并被换出）。
 */
class LlmService : Service() {

    private val engine: InferenceEngine by lazy { AiChat.getInferenceEngine(applicationContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var genJob: Job? = null

    private fun trace(msg: String) = ModelLifecycle.trace(this, "[llm] $msg")

    /** 跨进程只能带少数异常类型，统一成 IllegalStateException */
    private inline fun <T> remote(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw IllegalStateException(e.message ?: e.javaClass.simpleName)
    }

    private val binder = object : ILlmService.Stub() {
        override fun load(path: String) = remote {
            runBlocking {
                // 原生库在引擎构造时异步加载：等它就绪（或上一次失败后复位）
                engine.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error }
                if (engine.state.value is InferenceEngine.State.Error) engine.cleanUp()
                trace("开始加载 $path")
                engine.loadModel(path)
                trace("加载完成")
            }
        }

        override fun setSystemPrompt(text: String) = remote { runBlocking { engine.setSystemPrompt(text) } }

        override fun generate(messages: ByteArray, tools: ByteArray, maxTokens: Int, cb: ILlmCallback) {
            genJob?.cancel()
            genJob = scope.launch {
                try {
                    engine.sendToolPrompt(messages, tools, maxTokens).collect { cb.onToken(it) }
                    cb.onDone()
                } catch (e: CancellationException) {
                    try { cb.onDone() } catch (_: Exception) {}
                    throw e
                } catch (e: Throwable) {
                    try { cb.onError(e.message ?: e.javaClass.simpleName) } catch (_: Exception) {}
                }
            }
        }

        override fun cancel() { genJob?.cancel() }

        override fun parseToolCalls(text: String): ByteArray = remote { runBlocking { engine.parseToolCalls(text) } }
    }

    override fun onCreate() {
        super.onCreate()
        trace("进程启动 pid=${Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        trace("退出（客户端已解绑），结束进程")
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }
}
