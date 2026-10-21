package com.example.llama;

import com.example.llama.ILlmCallback;

/**
 * 主进程 → :llm 进程。load / setSystemPrompt / parseToolCalls 是同步调用（load 约 13 秒，在调用方的 IO 线程上等）；
 * generate / cancel 是 oneway 且必须立即返回：同一 Binder 上的 oneway 调用串行派发，
 * 若 generate 一直占着线程，后面的 cancel 就永远到不了。
 * 失败一律以 IllegalStateException(message) 过 Binder（只有少数异常类型能跨进程）。
 */
interface ILlmService {
    void load(String path);
    void setSystemPrompt(String text);
    oneway void generate(in byte[] messages, in byte[] tools, int maxTokens, ILlmCallback cb);
    oneway void cancel();
    byte[] parseToolCalls(String text);
}
