package com.example.llama

import com.arm.aichat.InferenceEngine
import com.arm.aichat.InferenceEngine.State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 推理进程（:llm）的传输层。真实实现走 Binder（[BinderLlmTransport]）；JVM 自检用假实现。
 *
 * 契约：
 *  · [onDied] 只在远端**异常**死亡（被系统杀、原生崩溃）时回调；主动 [disconnect] 不回调
 *  · [generate] 是冷流：收集时才开始；收集方取消时必须通知远端停止；远端死亡时以异常结束
 *  · [disconnect] 结束远端进程，原生内存整体归还系统
 */
interface LlmTransport {
    val connected: Boolean
    var onDied: (() -> Unit)?
    suspend fun connect()
    suspend fun load(path: String)
    suspend fun setSystemPrompt(text: String)
    fun generate(messages: ByteArray, tools: ByteArray, maxTokens: Int): Flow<String>
    suspend fun parseToolCalls(text: String): ByteArray
    fun disconnect()
}

class RemoteDiedException : Exception("推理进程已退出")

/**
 * 主进程里的 [InferenceEngine]：真正的 llama.cpp 跑在 `:llm` 进程。
 *
 * 为什么：模型常驻主进程时，原生内存让 ART 对主线程的每次对象分配强制等 GC（两份 ANR 栈完全一致），
 * 且 vivo 会以 LOW_MEMORY 杀掉整个包（UI、心跳、闹钟一起没）。拆出去之后主进程只有约 100MB。
 *
 * 状态约定：未连接 / 已释放 / 远端死亡 ＝ [State.Initialized]（"可以加载"，不是 Error）。
 * 上层（[AgentLoop]、[MainActivity]）只依赖 [InferenceEngine] 接口，不感知进程边界。
 */
class RemoteInferenceEngine(private val transport: LlmTransport) : InferenceEngine {

    private val _state = MutableStateFlow<State>(State.Initialized)
    override val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Mutex()

    init {
        transport.onDied = { _state.value = State.Initialized }
    }

    override suspend fun loadModel(pathToModel: String) = lock.withLock {
        when (_state.value) {
            is State.ModelReady, is State.Generating, is State.ProcessingSystemPrompt, is State.ProcessingUserPrompt ->
                return@withLock   // 已加载：幂等（并发的第二个调用在这里直接返回）
            else -> {}
        }
        _state.value = State.LoadingModel
        try {
            transport.connect()
            transport.load(pathToModel)
            _state.value = State.ModelReady
        } catch (e: CancellationException) {
            transport.disconnect(); _state.value = State.Initialized
            throw e
        } catch (e: Exception) {
            transport.disconnect(); _state.value = State.Error(e)
            throw e
        }
    }

    override suspend fun setSystemPrompt(systemPrompt: String) {
        check(_state.value is State.ModelReady) { "模型未就绪（${_state.value.javaClass.simpleName}）" }
        _state.value = State.ProcessingSystemPrompt
        try {
            transport.setSystemPrompt(systemPrompt)
            _state.value = State.ModelReady
        } catch (e: Exception) {
            _state.value = if (transport.connected) State.Error(e) else State.Initialized
            throw e
        }
    }

    override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> =
        flow { throw UnsupportedOperationException("本应用只使用 sendToolPrompt") }

    override fun sendToolPrompt(messages: ByteArray, tools: ByteArray, predictLength: Int): Flow<String> = flow {
        check(_state.value is State.ModelReady) { "模型未就绪（${_state.value.javaClass.simpleName}）" }
        _state.value = State.Generating
        try {
            transport.generate(messages, tools, predictLength).collect { emit(it) }
            if (_state.value is State.Generating) _state.value = State.ModelReady
        } catch (e: CancellationException) {
            // 收集方取消：传输层已通知远端停止
            if (_state.value is State.Generating) _state.value = State.ModelReady
            throw e
        } catch (e: Exception) {
            if (!transport.connected) {
                _state.value = State.Initialized
                throw IllegalStateException("推理进程被系统回收，这一轮没有完成", e)
            }
            _state.value = State.ModelReady
            throw e
        }
    }

    override suspend fun parseToolCalls(text: String): ByteArray =
        if (transport.connected) transport.parseToolCalls(text) else ByteArray(0)

    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
        throw UnsupportedOperationException("独立进程模式不提供 bench")

    /** 释放模型 ＝ 结束 :llm 进程（内存整体归还）；Error 状态也由它复位 */
    override fun cleanUp() {
        transport.disconnect()
        _state.value = State.Initialized
    }

    override fun destroy() = cleanUp()
}
