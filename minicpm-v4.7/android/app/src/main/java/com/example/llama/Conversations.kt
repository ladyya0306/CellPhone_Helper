package com.example.llama

/**
 * 会话管理 —— 一个会话 = 一个人设下的一段连续对话。
 *
 * 规则：
 *   · 换人设 → 自动开新会话（旧会话保留，可在「历史会话」里切回去，切回时人设也随之切回）
 *   · 手动「新会话」→ 开新会话，人设不变
 *   · 给模型的历史只取当前会话、最近 [PROMPT_WINDOW_HOURS] 小时内的对话
 *   · 关于用户的事实（facts）是全局的，不随会话/人设变化
 */
object Conversations {

    const val PROMPT_WINDOW_HOURS = 6L

    fun current(settings: AppSettings, memory: MemoryStore): String {
        val id = settings.currentConversationId
        if (id.isNotBlank() && memory.conversationExists(id) && memory.conversationPersona(id) == settings.personaId) return id
        return startNew(settings, memory)
    }

    fun startNew(settings: AppSettings, memory: MemoryStore): String {
        val n = memory.createConversation(settings.personaId)
        settings.currentConversationId = n
        val p = settings.persona
        memory.saveMessage(
            "assistant", "— ${p.avatar} ${p.name} · 新会话 —", source = "system",
            conversationId = n, kind = MemoryStore.KIND_NOTICE, personaId = p.id,
        )
        return n
    }

    /** 切回某个历史会话；会话属于哪个人设，就切到哪个人设 */
    fun switchTo(settings: AppSettings, memory: MemoryStore, id: String): Boolean {
        if (!memory.conversationExists(id)) return false
        val persona = memory.conversationPersona(id).orEmpty()
        if (persona.isNotBlank() && Personas.ALL.any { it.id == persona }) settings.personaId = persona
        else return false   // 升级前的旧会话没有人设信息，只能查看不能续聊
        settings.currentConversationId = id
        return true
    }
}
