package com.example.llama

import java.time.LocalDateTime

/**
 * 多轮补槽位：「设个闹钟」→「几点？」→「7点」。
 * 上一轮因缺槽位而追问时记下意图；3 分钟内、同一会话里，下一句若能补上槽位就直接执行。
 */
object SlotFilling {
    private const val TTL_MS = 3 * 60_000L

    private data class Pending(val route: IntentRouter.Route, val conversationId: String, val at: Long)

    @Volatile
    private var pending: Pending? = null

    fun remember(route: IntentRouter.Route, conversationId: String) {
        pending = if (!route.confident && route.missing != null) Pending(route, conversationId, System.currentTimeMillis()) else null
    }

    fun clear() { pending = null }

    fun resolve(route: IntentRouter.Route, text: String, now: LocalDateTime, conversationId: String): IntentRouter.Route {
        val p = pending ?: return route
        if (route.kind != IntentRouter.Kind.NONE) return route          // 用户换了新指令
        if (p.conversationId != conversationId || System.currentTimeMillis() - p.at > TTL_MS) { pending = null; return route }
        val time = TimeParser.parse(text, now)
        val filled = when (p.route.kind) {
            IntentRouter.Kind.ALARM, IntentRouter.Kind.REMINDER, IntentRouter.Kind.CALENDAR_ADD ->
                if (time != null && (time.hasTime || time.relative || p.route.kind == IntentRouter.Kind.CALENDAR_ADD)) {
                    val extra = IntentRouter.extractContent(text)
                    p.route.copy(confident = true, time = time, missing = null,
                        content = p.route.content.ifBlank { extra })
                } else null
            IntentRouter.Kind.TODO_ADD -> {
                val c = IntentRouter.extractContent(text)
                if (c.isNotBlank()) p.route.copy(confident = true, content = c, time = time ?: p.route.time, missing = null) else null
            }
            else -> null
        }
        pending = null
        return filled ?: route
    }
}