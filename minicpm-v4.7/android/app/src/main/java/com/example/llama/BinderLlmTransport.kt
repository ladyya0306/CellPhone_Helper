package com.example.llama

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * 主进程里的整个推理引擎入口（进程级单例）。
 * 其余代码只认 [InferenceEngine] 接口，不感知 :llm 进程的存在。
 */
object LlmEngine {
    @Volatile private var instance: RemoteInferenceEngine? = null

    fun get(context: Context): InferenceEngine =
        instance ?: synchronized(this) {
            instance ?: RemoteInferenceEngine(BinderLlmTransport(context.applicationContext)).also { instance = it }
        }
}

/**
 * 经 Binder 连接 :llm 进程（[LlmService]）。
 *
 * 死亡处理：被系统杀或原生崩溃时 [ServiceConnection.onServiceDisconnected] 触发 → 立即解绑（否则
 * BIND_AUTO_CREATE 会把一个空的 :llm 进程又拉起来）→ 通知引擎回到"可加载"，并让在途的生成流以
 * [RemoteDiedException] 结束。主动 [disconnect] 不算死亡。
 */
class BinderLlmTransport(private val app: Context) : LlmTransport {

    @Volatile private var service: ILlmService? = null
    @Volatile private var conn: ServiceConnection? = null
    @Volatile private var closing = false
    /** 在途生成流的"以异常结束"入口 */
    private val activeClosers = ConcurrentHashMap.newKeySet<(Throwable) -> Unit>()

    override var onDied: (() -> Unit)? = null

    override val connected: Boolean
        get() = service?.asBinder()?.isBinderAlive == true

    override suspend fun connect() {
        if (connected) return
        closing = false
        val ready = CompletableDeferred<ILlmService>()
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val s = ILlmService.Stub.asInterface(binder)
                service = s
                ready.complete(s)
            }
            override fun onServiceDisconnected(name: ComponentName?) = died(this)
            override fun onBindingDied(name: ComponentName?) = died(this)
            override fun onNullBinding(name: ComponentName?) { ready.completeExceptionally(IllegalStateException("推理进程没有返回接口")) }
        }
        conn = c
        if (!app.bindService(Intent(app, LlmService::class.java), c, Context.BIND_AUTO_CREATE)) {
            conn = null
            throw IllegalStateException("无法绑定推理进程")
        }
        try {
            // 上一个 :llm 进程可能正在退出（刚释放又马上回前台）：系统会在它死后重新拉起并回调，这里多等一会
            withTimeout(CONNECT_TIMEOUT_MS) { ready.await() }
        } catch (e: Exception) {
            disconnect()
            throw IllegalStateException("连接推理进程超时或失败：${e.message}", e)
        }
    }

    private fun died(from: ServiceConnection) {
        if (closing || conn !== from) return
        service = null
        conn = null
        try { app.unbindService(from) } catch (_: Exception) {}
        ModelLifecycle.trace(app, "推理进程异常退出（被系统回收或崩溃）")
        activeClosers.toList().forEach { it(RemoteDiedException()) }
        onDied?.invoke()
    }

    private fun remote(): ILlmService = service ?: throw RemoteDiedException()

    override suspend fun load(path: String) = withContext(Dispatchers.IO) {
        try { remote().load(path) } catch (e: android.os.RemoteException) { throw RemoteDiedException() }
    }

    override suspend fun setSystemPrompt(text: String) = withContext(Dispatchers.IO) {
        try { remote().setSystemPrompt(text) } catch (e: android.os.RemoteException) { throw RemoteDiedException() }
    }

    override fun generate(messages: ByteArray, tools: ByteArray, maxTokens: Int): Flow<String> = callbackFlow {
        var finished = false
        val closer: (Throwable) -> Unit = { err -> finished = true; close(err) }
        activeClosers.add(closer)
        val cb = object : ILlmCallback.Stub() {
            override fun onToken(text: String) { trySend(text) }
            override fun onDone() { finished = true; close() }
            override fun onError(message: String) { finished = true; close(IllegalStateException(message)) }
        }
        try {
            remote().generate(messages, tools, maxTokens, cb)
        } catch (e: Exception) {
            finished = true
            close(RemoteDiedException())
        }
        awaitClose {
            activeClosers.remove(closer)
            // 收集方中途取消（没跑完）：通知远端停止，别让它白白耗电占 CPU
            if (!finished) try { service?.cancel() } catch (_: Exception) {}
        }
    }

    override suspend fun parseToolCalls(text: String): ByteArray = withContext(Dispatchers.IO) {
        try { remote().parseToolCalls(text) } catch (e: android.os.RemoteException) { throw RemoteDiedException() }
    }

    override fun disconnect() {
        closing = true
        val c = conn
        conn = null
        service = null
        if (c != null) try { app.unbindService(c) } catch (_: Exception) {}
        activeClosers.toList().forEach { it(RemoteDiedException()) }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
