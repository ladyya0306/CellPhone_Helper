package com.example.llama;

/**
 * :llm 进程 → 主进程的流式回调。oneway：同一 Binder 上的调用按序送达，且不会阻塞推理线程。
 */
oneway interface ILlmCallback {
    void onToken(String text);
    void onDone();
    void onError(String message);
}
