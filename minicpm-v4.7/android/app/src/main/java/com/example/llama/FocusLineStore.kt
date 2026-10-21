package com.example.llama

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 载入模型批量生成的专注台词库（F3）。优先读 files/focus_lines.json（手机上生成或调试推送的），没有就读随包的 assets/focus_lines.json。
 * 格式：{"人设/事件/档位": ["台词", ...]}。真正的把关在 [FocusLines.setExtra]：每条台词再过一遍 [FocusLineBank.validate]。
 */
object FocusLineStore {
    /** 返回载入后的格子数与台词数（文本，用于日志） */
    fun load(ctx: Context): String {
        return try {
            val f = File(ctx.filesDir, "focus_lines.json")
            val src = if (f.exists()) f.readText(Charsets.UTF_8) else ctx.assets.open("focus_lines.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = JSONObject(src)
            val map = HashMap<String, List<String>>()
            o.keys().forEach { k -> val a = o.getJSONArray(k); map[k] = (0 until a.length()).map { a.getString(it) } }
            val before = map.values.sumOf { it.size }
            FocusLines.setExtra(map)
            "生成台词库：载入 $before 条，通过再校验 ${FocusLines.extraLineCount()} 条，覆盖 ${FocusLines.extraCount()} 个格子（来源 ${if (f.exists()) "files" else "assets"}）"
        } catch (e: java.io.FileNotFoundException) {
            FocusLines.setExtra(emptyMap()); "生成台词库：没有（只用预置台词）"
        } catch (e: Exception) {
            FocusLines.setExtra(emptyMap()); "生成台词库：载入失败 ${e.javaClass.simpleName}: ${e.message}（只用预置台词）"
        }
    }
}
