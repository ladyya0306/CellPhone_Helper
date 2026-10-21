package com.example.llama

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 候选记忆的存取（files/candidates.json）；规则在 [Candidates]，这里只管读写。损坏时先备份再重建，写失败抛异常。 */
class CandidateStore(context: Context) {
    private val dir = context.filesDir
    private val file = File(dir, "candidates.json")

    fun all(): List<Candidates.Cand> = synchronized(LOCK) {
        if (!file.exists()) return emptyList()
        try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    Candidates.Cand(o.optInt("id"), o.optString("category"), o.optString("text"), o.optString("evidence"),
                        o.optInt("count", 1), o.optLong("firstAt"), o.optLong("lastAt"), o.optString("status", "pending"))
                }
            }
        } catch (e: Exception) {
            file.renameTo(File(dir, "candidates.json.corrupt-${System.currentTimeMillis()}"))
            emptyList()
        }
    }

    fun save(list: List<Candidates.Cand>) = synchronized(LOCK) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().put("id", c.id).put("category", c.category).put("text", c.text).put("evidence", c.evidence)
                .put("count", c.count).put("firstAt", c.firstAt).put("lastAt", c.lastAt).put("status", c.status))
        }
        AtomicFile.write(file, arr.toString())
    }

    companion object { private val LOCK = Any() }
}
