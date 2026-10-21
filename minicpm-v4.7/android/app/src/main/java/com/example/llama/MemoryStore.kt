package com.example.llama

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * 记忆与会话持久化 —— v2（2026-10-18）。
 *
 * 三类数据，三种作用域（成熟助手的标准划分）：
 *   ┌──────────────┬────────────────────────────┬───────────────────────────┐
 *   │ 数据          │ 作用域                      │ 进 prompt 的方式            │
 *   ├──────────────┼────────────────────────────┼───────────────────────────┤
 *   │ messages     │ 会话（conversation）内       │ 只取本会话、本人设、kind=chat │
 *   │ facts        │ 全局（关于用户，跨人设共享）   │ 按相关性挑 ≤3 条            │
 *   │ actions      │ 审计（动作账本）              │ 不进 prompt，只给用户看      │
 *   └──────────────┴────────────────────────────┴───────────────────────────┘
 *
 * v1 的问题：只有一个 'default' 会话，换人设后旧人设的历史照样喂给新人设
 *   （真实数据：傲娇大小姐自称"小咪"、喊"主人"、说"喵"）；心跳模板消息也作为 assistant 轮次进了上下文。
 *
 * 升级策略：v1 的消息全部保留，归入一个名为「升级前的会话」的旧会话；新对话从新会话开始。
 */
class MemoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class ChatRecord(
        val id: Long,
        val role: String,
        val content: String,
        val source: String,     // user / local / cloud / heartbeat / safety / refusal / reminder / router
        val createdAt: Long,
        val kind: String = KIND_CHAT,
        val personaId: String = "",
    )

    data class Conversation(
        val id: String,
        val personaId: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messageCount: Int,
    )

    data class ActionRow(
        val id: Long, val conversationId: String, val tool: String, val args: String,
        val status: String, val receipt: String, val createdAt: Long,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages (
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 conversation_id TEXT NOT NULL DEFAULT 'default',
                 role TEXT NOT NULL,
                 content TEXT NOT NULL,
                 source TEXT NOT NULL DEFAULT 'local',
                 created_at INTEGER NOT NULL,
                 kind TEXT NOT NULL DEFAULT 'chat',
                 persona_id TEXT NOT NULL DEFAULT '',
                 turn_id TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL("CREATE INDEX idx_messages_created ON messages(created_at)")
        db.execSQL("CREATE INDEX idx_messages_conv ON messages(conversation_id, created_at)")
        db.execSQL(
            """CREATE TABLE facts (
                 key TEXT PRIMARY KEY,
                 value TEXT NOT NULL,
                 updated_at INTEGER NOT NULL,
                 slot TEXT NOT NULL DEFAULT '',
                 last_used_at INTEGER NOT NULL DEFAULT 0)"""
        )
        createV2Tables(db)
    }

    private fun createV2Tables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS conversations (
                 id TEXT PRIMARY KEY,
                 persona_id TEXT NOT NULL DEFAULT '',
                 title TEXT NOT NULL DEFAULT '',
                 created_at INTEGER NOT NULL,
                 updated_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS actions (
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 conversation_id TEXT NOT NULL,
                 turn_id TEXT NOT NULL,
                 tool TEXT NOT NULL,
                 args TEXT NOT NULL,
                 status TEXT NOT NULL,
                 receipt TEXT NOT NULL,
                 created_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_actions_created ON actions(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'chat'")
            db.execSQL("ALTER TABLE messages ADD COLUMN persona_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN turn_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE messages SET kind='heartbeat' WHERE source='heartbeat'")
            db.execSQL("UPDATE messages SET kind='safety' WHERE source='safety'")
            db.execSQL("ALTER TABLE facts ADD COLUMN slot TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE facts ADD COLUMN last_used_at INTEGER NOT NULL DEFAULT 0")
            createV2Tables(db)
            // 旧消息归入一个旧会话
            val now = System.currentTimeMillis()
            db.execSQL(
                "INSERT OR IGNORE INTO conversations(id, persona_id, title, created_at, updated_at) VALUES('default', '', '升级前的会话', ?, ?)",
                arrayOf<Any>(now, now)
            )
            // 旧事实补槽位并去重（"我周五要交周报"与"周五要交周报"并存的问题）
            val rows = ArrayList<Triple<String, String, Long>>()
            db.query("facts", arrayOf("key", "value", "updated_at"), null, null, null, null, "updated_at ASC").use { c ->
                while (c.moveToNext()) rows.add(Triple(c.getString(0), c.getString(1), c.getLong(2)))
            }
            db.delete("facts", null, null)
            val bySlot = LinkedHashMap<String, Triple<String, String, Long>>()
            rows.forEach { bySlot[FactExtractor.slotOf(it.first)] = it }   // 同槽位后写的覆盖先写的
            bySlot.forEach { (slot, r) ->
                db.insert("facts", null, ContentValues().apply {
                    put("key", r.first.removePrefix("我")); put("value", r.second); put("updated_at", r.third); put("slot", slot)
                })
            }
        }
    }

    // ---------------------------------------------------------------- 会话
    fun createConversation(personaId: String): String {
        val id = "c-" + UUID.randomUUID().toString().take(8)
        val now = System.currentTimeMillis()
        writableDatabase.insert("conversations", null, ContentValues().apply {
            put("id", id); put("persona_id", personaId); put("title", ""); put("created_at", now); put("updated_at", now)
        })
        return id
    }

    fun conversationExists(id: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM conversations WHERE id=?", arrayOf(id)).use { it.moveToFirst() }

    fun conversationPersona(id: String): String? =
        readableDatabase.rawQuery("SELECT persona_id FROM conversations WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun listConversations(limit: Int = 30): List<Conversation> {
        val out = ArrayList<Conversation>()
        readableDatabase.rawQuery(
            """SELECT c.id, c.persona_id, c.title, c.created_at, c.updated_at,
                      (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id)
               FROM conversations c ORDER BY c.updated_at DESC LIMIT ?""",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Conversation(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getInt(5)))
            }
        }
        return out
    }

    fun deleteConversation(id: String) {
        writableDatabase.delete("messages", "conversation_id=?", arrayOf(id))
        writableDatabase.delete("conversations", "id=?", arrayOf(id))
    }

    private fun touchConversation(id: String, firstUserText: String?) {
        val db = writableDatabase
        db.execSQL("UPDATE conversations SET updated_at=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(), id))
        if (!firstUserText.isNullOrBlank()) {
            db.execSQL(
                "UPDATE conversations SET title=? WHERE id=? AND title=''",
                arrayOf<Any>(firstUserText.replace("\n", " ").take(20), id)
            )
        }
    }

    // ---------------------------------------------------------------- 消息
    fun saveMessage(
        role: String,
        content: String,
        source: String = "local",
        conversationId: String = "default",
        kind: String = KIND_CHAT,
        personaId: String = "",
        turnId: String = "",
    ): Long {
        val cv = ContentValues().apply {
            put("conversation_id", conversationId)
            put("role", role)
            put("content", content)
            put("source", source)
            put("created_at", System.currentTimeMillis())
            put("kind", kind)
            put("persona_id", personaId)
            put("turn_id", turnId)
        }
        val id = writableDatabase.insert("messages", null, cv)
        try {
            touchConversation(conversationId, if (role == "user" && kind == KIND_CHAT) content else null)
        } catch (_: Exception) {
        }
        return id
    }

    private fun readRecords(sql: String, args: Array<String>): List<ChatRecord> {
        val out = ArrayList<ChatRecord>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(ChatRecord(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getString(5), c.getString(6)))
            }
        }
        return out
    }

    /** 给界面看：本会话全部类型的消息（聊天 / 心跳 / 回执 / 提醒 / 系统提示） */
    fun recentMessages(limit: Int = 30, conversationId: String = "default"): List<ChatRecord> =
        readRecords(
            "SELECT id, role, content, source, created_at, kind, persona_id FROM messages WHERE conversation_id=? ORDER BY id DESC LIMIT ?",
            arrayOf(conversationId, limit.toString())
        ).reversed()

    /**
     * 给模型看：只取**本会话、本人设、真正的对话**（kind=chat），并限制时间窗口。
     * 心跳模板、回执、危机话术、诚实拒绝都不作为 assistant 轮次喂给模型——
     * 否则模型会照着这些模板说话（真实数据里回复越来越像复读机）。
     */
    fun promptHistory(conversationId: String, personaId: String, limit: Int, maxAgeMs: Long): List<ChatRecord> {
        val since = System.currentTimeMillis() - maxAgeMs
        return readRecords(
            """SELECT id, role, content, source, created_at, kind, persona_id FROM messages
               WHERE conversation_id=? AND kind IN ('chat','receipt') AND (persona_id=? OR persona_id='') AND created_at>=?
               ORDER BY id DESC LIMIT ?""",
            arrayOf(conversationId, personaId, since.toString(), limit.toString())
        ).reversed()
    }

    /** 最近一条心跳（用户可能正在回复它） */
    fun lastHeartbeat(conversationId: String, withinMs: Long): ChatRecord? {
        val since = System.currentTimeMillis() - withinMs
        return readRecords(
            "SELECT id, role, content, source, created_at, kind, persona_id FROM messages WHERE conversation_id=? AND kind='heartbeat' AND created_at>=? ORDER BY id DESC LIMIT 1",
            arrayOf(conversationId, since.toString())
        ).firstOrNull()
    }

    /**
     * 上一次（当前会话之外、最近的）真人聊天的回顾，供新会话第一轮给模型当背景。
     * 跳过：没有真人发言的会话、程序自动测试的会话、对当前人设不可见的会话（树洞）。纯抽取，见 [ConversationDigest]。
     */
    fun previousDigest(currentConvId: String, currentPersonaId: String, nowMs: Long, skipIds: Set<String> = emptySet()): ConversationDigest.Digest? {
        for (c in listConversations(12)) {
            if (c.id == currentConvId || c.id in skipIds || !ConversationDigest.visibleTo(c.personaId, currentPersonaId)) continue
            val lines = readRecords(
                """SELECT id, role, content, source, created_at, kind, persona_id FROM messages
                   WHERE conversation_id=? AND role='user' AND kind='chat' AND source='user' ORDER BY id LIMIT 12""",
                arrayOf(c.id)
            ).map { it.content }
            if (lines.isEmpty() || ConversationDigest.isTestConversation(lines)) continue
            val actions = actionsOf(c.id)
            ConversationDigest.build(lines, actions, c.updatedAt, nowMs)?.let { return it.copy(convId = c.id) }
        }
        return null
    }

    private fun actionsOf(conversationId: String): List<ConversationDigest.ActionLine> {
        val out = ArrayList<ConversationDigest.ActionLine>()
        readableDatabase.rawQuery("SELECT tool, status, receipt FROM actions WHERE conversation_id=? ORDER BY id", arrayOf(conversationId))
            .use { c -> while (c.moveToNext()) out.add(ConversationDigest.ActionLine(c.getString(0), c.getString(1), c.getString(2))) }
        return out
    }

    /**
     * 用户在 [fromMs, toMs) 之间亲口说的话（kind=chat，且不是调试/测试会话里程序造的）：
     * 回答"你记得我昨天说的事吗"只能用这个，不能让模型凭印象编。
     */
    fun userLinesBetween(fromMs: Long, toMs: Long, limit: Int = 5): List<String> =
        readRecords(
            """SELECT id, role, content, source, created_at, kind, persona_id FROM messages
               WHERE role='user' AND kind='chat' AND source='user' AND created_at>=? AND created_at<? ORDER BY id DESC LIMIT ?""",
            arrayOf(fromMs.toString(), toMs.toString(), limit.toString())
        ).map { it.content.trim() }.filter { it.isNotBlank() }.reversed()

    fun searchMessages(keyword: String, limit: Int = 20): List<ChatRecord> {
        if (keyword.isBlank()) return emptyList()
        return readRecords(
            "SELECT id, role, content, source, created_at, kind, persona_id FROM messages WHERE content LIKE ? ORDER BY id DESC LIMIT ?",
            arrayOf("%$keyword%", limit.toString())
        )
    }

    fun messageCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    // ---------------------------------------------------------------- 动作账本
    fun logAction(conversationId: String, turnId: String, tool: String, args: String, status: String, receipt: String) {
        writableDatabase.insert("actions", null, ContentValues().apply {
            put("conversation_id", conversationId); put("turn_id", turnId); put("tool", tool); put("args", args)
            put("status", status); put("receipt", receipt); put("created_at", System.currentTimeMillis())
        })
    }

    fun recentActions(limit: Int = 30): List<ActionRow> {
        val out = ArrayList<ActionRow>()
        readableDatabase.rawQuery(
            "SELECT id, conversation_id, tool, args, status, receipt, created_at FROM actions ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(ActionRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getLong(6)))
        }
        return out
    }

    // ---------------------------------------------------------------- 事实（全局）
    /** 同槽位只保留最新一条：先"喜欢香菜"后"不吃香菜"，只留"不吃香菜" */
    fun saveFact(key: String, value: String): Int {
        val slot = FactExtractor.slotOf(key)
        val db = writableDatabase
        val replaced = db.delete("facts", "slot=? AND key<>?", arrayOf(slot, key))
        db.insertWithOnConflict("facts", null, ContentValues().apply {
            put("key", key); put("value", value); put("updated_at", System.currentTimeMillis()); put("slot", slot)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return replaced
    }

    fun getFact(key: String): String? =
        readableDatabase.query("facts", arrayOf("value"), "key = ?", arrayOf(key), null, null, null)
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun allFacts(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        readableDatabase.query("facts", arrayOf("key", "value"), null, null, null, null, "updated_at DESC").use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
        }
        return out
    }

    /** 去掉已过期的带时间词的事实（"周五要交周报"一周后不再往对话里塞）；数据库里仍保留，问"你记得什么"还是能列出来 */
    fun freshFacts(nowMs: Long = System.currentTimeMillis()): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        readableDatabase.query("facts", arrayOf("key", "value", "updated_at"), null, null, null, null, "updated_at DESC").use { c ->
            while (c.moveToNext()) {
                val ageDays = (nowMs - c.getLong(2)).coerceAtLeast(0) / 86_400_000L
                if (!ConversationDigest.isStaleFact(c.getString(0), ageDays)) out.add(c.getString(0) to c.getString(1))
            }
        }
        return out
    }

    /** 按相关性挑选本轮要注入的事实（见 MemoryRelevance） */
    fun relevantFacts(query: String, max: Int): List<MemoryRelevance.Fact> {
        val picked = MemoryRelevance.select(freshFacts().map { MemoryRelevance.Fact(it.first, it.second) }, query, max)
        if (picked.isNotEmpty()) {
            try {
                val now = System.currentTimeMillis()
                picked.forEach { writableDatabase.execSQL("UPDATE facts SET last_used_at=? WHERE key=?", arrayOf<Any>(now, it.key)) }
            } catch (_: Exception) {
            }
        }
        return picked
    }

    fun forget(key: String) {
        writableDatabase.delete("facts", "key = ?", arrayOf(key))
    }

    /** 按关键词删除事实（key 或 value 命中即删），返回删掉的 key 列表 */
    fun forgetMatching(keyword: String): List<String> {
        if (keyword.isBlank()) return emptyList()
        val like = "%$keyword%"
        val keys = ArrayList<String>()
        readableDatabase.query("facts", arrayOf("key"), "key LIKE ? OR value LIKE ?", arrayOf(like, like), null, null, null).use { c ->
            while (c.moveToNext()) keys.add(c.getString(0))
        }
        if (keys.isNotEmpty()) writableDatabase.delete("facts", "key LIKE ? OR value LIKE ?", arrayOf(like, like))
        return keys
    }

    fun factCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM facts", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun renderFactsForUi(): String {
        val all = allFacts()
        if (all.isEmpty()) return "还没有记住任何事。\n试试对它说：「记住我周五要交周报」「我不吃香菜」"
        return all.joinToString("\n") { (k, _) -> "· $k" }
    }

    // ---------------------------------------------------------------- 清理
    fun clearMessages() {
        writableDatabase.delete("messages", null, null)
        writableDatabase.delete("conversations", null, null)
    }

    fun clearFacts() {
        writableDatabase.delete("facts", null, null)
    }

    fun clearAll() {
        clearMessages()
        clearFacts()
        writableDatabase.delete("actions", null, null)
    }

    companion object {
        private const val DB_NAME = "memory.db"
        private const val DB_VERSION = 2

        const val KIND_CHAT = "chat"
        const val KIND_HEARTBEAT = "heartbeat"
        const val KIND_RECEIPT = "receipt"
        const val KIND_NOTICE = "notice"
        const val KIND_SAFETY = "safety"
    }
}
