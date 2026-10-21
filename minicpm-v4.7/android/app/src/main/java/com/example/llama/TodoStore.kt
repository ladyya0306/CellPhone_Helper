package com.example.llama

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 待办存储 —— JSON 文件（files/todos.json）。
 *
 * 2026-10-18 修订：
 *   · 写入改为原子写，失败**抛异常**（旧版吞掉异常，写失败也会回答"已加入待办"）
 *   · 文件损坏时先备份再重建（旧版直接当空列表，下次保存会把所有待办覆盖掉）
 *   · 新增 dueAt（毫秒时间戳，0 表示没有具体时间）与 reminderId（关联的到点提醒）
 */
class TodoStore(context: Context) {

    private val dir = context.filesDir
    private val file = File(dir, "todos.json")

    data class Todo(
        val id: Int,
        val text: String,
        val due: String,
        val done: Boolean,
        val created: Long,
        val dueAt: Long = 0L,
        val reminderId: Int = 0,
    )

    private fun loadRaw(): JSONArray = synchronized(LOCK) {
        if (!file.exists()) return JSONArray()
        return try {
            JSONArray(file.readText())
        } catch (e: Exception) {
            file.renameTo(File(dir, "todos.json.corrupt-${System.currentTimeMillis()}"))
            JSONArray()
        }
    }

    private fun saveRaw(arr: JSONArray) = synchronized(LOCK) { AtomicFile.write(file, arr.toString()) }

    private fun JSONObject.toTodo(i: Int) = Todo(
        id = optInt("id", i + 1),
        text = optString("text", ""),
        due = optString("due", ""),
        done = optBoolean("done", false),
        created = optLong("created", 0L),
        dueAt = optLong("dueAt", 0L),
        reminderId = optInt("reminderId", 0),
    )

    fun all(): List<Todo> {
        val arr = loadRaw()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toTodo(i) }
    }

    fun get(id: Int): Todo? = all().firstOrNull { it.id == id }

    /** @throws java.io.IOException 写入失败时 */
    fun add(text: String, due: String, dueAt: Long = 0L, reminderId: Int = 0): Todo {
        val arr = loadRaw()
        var maxId = 0
        for (i in 0 until arr.length()) maxId = maxOf(maxId, arr.optJSONObject(i)?.optInt("id", 0) ?: 0)
        val id = maxId + 1
        val now = System.currentTimeMillis()
        arr.put(JSONObject().apply {
            put("id", id); put("text", text); put("due", due); put("done", false); put("created", now)
            put("dueAt", dueAt); put("reminderId", reminderId)
        })
        saveRaw(arr)
        return Todo(id, text, due, false, now, dueAt, reminderId)
    }

    fun setReminder(id: Int, reminderId: Int) {
        val arr = loadRaw()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optInt("id", -1) == id) { o.put("reminderId", reminderId); saveRaw(arr); return }
        }
    }

    fun done(id: Int): Boolean {
        val arr = loadRaw()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optInt("id", -1) == id) {
                o.put("done", true)
                saveRaw(arr)
                return true
            }
        }
        return false
    }

    fun remove(id: Int): Boolean {
        val arr = loadRaw()
        val keep = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optInt("id", -1) == id) removed = true else keep.put(o)
        }
        if (removed) saveRaw(keep)
        return removed
    }

    /** 给模型看 / 给用户看的紧凑文本 */
    fun renderList(): String {
        val items = all().filter { !it.done }
        if (items.isEmpty()) return "当前没有未完成的待办。"
        return buildString {
            append("未完成待办共 ").append(items.size).append(" 条：\n")
            items.forEach { t ->
                append("#").append(t.id).append(" ").append(t.text)
                if (t.due.isNotBlank()) append("（").append(t.due).append("）")
                if (t.reminderId > 0) append(" 🔔")
                append("\n")
            }
            val doneCount = all().count { it.done }
            if (doneCount > 0) append("另有 ").append(doneCount).append(" 条已完成。")
        }.trim()
    }

    companion object {
        private val LOCK = Any()
    }
}
