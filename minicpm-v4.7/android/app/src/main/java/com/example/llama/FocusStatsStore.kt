package com.example.llama

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 专注记录的存取（files/focus_stats.json）；口径在 [FocusStats]。损坏时先备份再重建。 */
object FocusStatsStore {
    private val LOCK = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "focus_stats.json")

    fun all(ctx: Context): List<FocusStats.Rec> = synchronized(LOCK) {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        try {
            val a = JSONArray(f.readText(Charsets.UTF_8))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                FocusStats.Rec(o.getLong("startMs"), o.getInt("sec"), o.getBoolean("done"), o.optString("grade", "C"),
                    o.optInt("leaves"), o.optInt("peeks"), o.optInt("distracts"), o.optInt("blowns"))
            }
        } catch (e: Exception) {
            f.renameTo(File(ctx.filesDir, "focus_stats.json.corrupt-${System.currentTimeMillis()}"))
            emptyList()
        }
    }

    private fun save(ctx: Context, recs: List<FocusStats.Rec>) = synchronized(LOCK) {
        val a = JSONArray()
        recs.forEach { r ->
            a.put(JSONObject().put("startMs", r.startMs).put("sec", r.elapsedSec).put("done", r.completed).put("grade", r.grade)
                .put("leaves", r.leaves).put("peeks", r.peeks).put("distracts", r.distracts).put("blowns", r.blowns))
        }
        AtomicFile.write(file(ctx), a.toString())
    }

    fun append(ctx: Context, r: FocusStats.Rec) = synchronized(LOCK) { save(ctx, FocusStats.append(all(ctx), r)) }

    fun clear(ctx: Context) = synchronized(LOCK) { save(ctx, emptyList()) }

    /** 调试用：往前补 n 天、每天一局完成的记录（昨天、前天…），用来当场验证连续天数 */
    fun seedDays(ctx: Context, n: Int, nowMs: Long = System.currentTimeMillis()) = synchronized(LOCK) {
        var recs = all(ctx)
        for (i in 1..n) recs = FocusStats.append(recs, FocusStats.Rec(nowMs - i * 86_400_000L, 1500, true, "A", 1, 0, 0, 0))
        save(ctx, recs)
    }
}
