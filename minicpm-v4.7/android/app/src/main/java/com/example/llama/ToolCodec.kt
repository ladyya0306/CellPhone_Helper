package com.example.llama

/**
 * 工具调用的数据编解码 —— 与 ai_chat.cpp 末尾的 JNI 扩展约定一致：
 *   字段用 \u001F 分隔，记录用 \u001E 分隔；一律走 UTF-8 字节，避开 modified-UTF-8 的坑。
 */
object ToolCodec {

    private const val US = '\u001F'   // 字段分隔
    private const val RS = '\u001E'   // 记录分隔

    data class ChatMessage(
        val role: String,
        val content: String,
        val toolName: String = "",
        val toolCallId: String = "",
    )

    data class ToolDef(
        val name: String,
        val description: String,
        val parametersJson: String,   // JSON Schema 字符串
    )

    data class ToolCall(
        val name: String,
        val arguments: String,
        val id: String,
    )

    /** messages -> role␟content[␟toolName␟toolCallId]␞... */
    fun encodeMessages(messages: List<ChatMessage>): ByteArray = buildString {
        messages.forEach { m ->
            append(m.role).append(US).append(m.content)
            if (m.role == "tool") {
                append(US).append(m.toolName).append(US).append(m.toolCallId)
            }
            append(RS)
        }
    }.toByteArray(Charsets.UTF_8)

    /** tools -> name␟description␟parameters␞... */
    fun encodeTools(tools: List<ToolDef>): ByteArray = buildString {
        tools.forEach { t ->
            append(t.name).append(US).append(t.description).append(US)
                .append(t.parametersJson).append(RS)
        }
    }.toByteArray(Charsets.UTF_8)

    /** 解析 name␟args␟id␞... */
    fun decodeToolCalls(bytes: ByteArray): List<ToolCall> {
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isEmpty()) return emptyList()
        return text.split(RS)
            .filter { it.isNotEmpty() }
            .mapNotNull { rec ->
                val f = rec.split(US)
                if (f.size < 2) return@mapNotNull null
                ToolCall(
                    name = f[0],
                    arguments = f.getOrElse(1) { "{}" },
                    id = f.getOrElse(2) { "" },
                )
            }
    }
}
