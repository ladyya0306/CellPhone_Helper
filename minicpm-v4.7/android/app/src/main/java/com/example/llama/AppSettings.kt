package com.example.llama

import android.content.Context

/**
 * 全部设置项 —— 按用户要求"开放设置，不由开发者拍板"。
 * 默认值只是起点，每一项都能在设置页里改。
 * 尚未接通的项会在设置页标注「待接线」。
 */
class AppSettings(context: Context) {

    companion object {
        const val DEFAULT_MODEL_FILE = "qwen35-4b.gguf"
        /** 配置的模型文件不存在时回退到它（最初部署的文件，用于回滚） */
        const val FALLBACK_MODEL_FILE = "model.gguf"
    }

    private val sp = context.applicationContext
        .getSharedPreferences("minicpm_settings", Context.MODE_PRIVATE)

    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun i(key: String, def: Int) = sp.getInt(key, def)
    private fun s(key: String, def: String) = sp.getString(key, def) ?: def
    private fun put(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun put(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    private fun put(key: String, v: String) = sp.edit().putString(key, v).apply()

    // ---------------- 人设 ----------------
    var personaId: String
        get() = s("persona_id", Personas.default().id)
        set(v) = put("persona_id", v)

    val persona: Persona get() = Personas.byId(personaId)

    /** 用户给人设起的名字（只改显示名与自称，见 [PersonaNames]）；没改过的人设没有这一项 */
    fun personaAliases(): Map<String, String> = PersonaPacks.ALL.mapNotNull { k ->
        val id = k.persona.id
        sp.getString("persona_alias_$id", null)?.takeIf { it.isNotBlank() }?.let { id to it }
    }.toMap()

    /** 保存（或恢复默认）一个人设的名字，并立刻让全进程生效 */
    fun setPersonaAlias(id: String, alias: String?) {
        if (alias.isNullOrBlank()) sp.edit().remove("persona_alias_$id").apply() else sp.edit().putString("persona_alias_$id", alias).apply()
        PersonaNames.set(id, alias)
    }

    /** 界面皮肤：cream 奶油手帐（默认）/ night 深夜陪伴 / classic 经典 */
    var skinId: String
        get() = Skin.normalize(s("skin_id", Skin.CREAM))
        set(v) = put("skin_id", Skin.normalize(v))

    fun skin(): Skin = Skin.of(skinId, personaId)

    var ticStrength: Int          // 0 弱 / 1 中 / 2 强，对应口癖注入概率倍率
        get() = i("tic_strength", 1)
        set(v) = put("tic_strength", v)

    // ---------------- 模型 ----------------
    var modelFileName: String
        // 默认 Qwen3.5-4B（2026-10-19 评测：复读 0.78→0.07、:llm 内存少 0.67GB，代价每轮慢约 7 秒）；文件不存在时回退 model.gguf
        get() = s("model_file", DEFAULT_MODEL_FILE)
        set(v) = put("model_file", v)

    var autoLoadModel: Boolean
        get() = b("auto_load_model", true)
        set(v) = put("auto_load_model", v)

    var contextSize: Int
        get() = i("context_size", 2048)
        set(v) = put("context_size", v)

    var threads: Int
        get() = i("threads", 6)
        set(v) = put("threads", v)

    var keepModelResident: Boolean
        get() = b("keep_model_resident", true)
        set(v) = put("keep_model_resident", v)

    // ---------------- 心跳 ----------------
    var heartbeatEnabled: Boolean
        get() = b("heartbeat_enabled", true)
        set(v) = put("heartbeat_enabled", v)

    var heartbeatIntervalMin: Int
        get() = i("heartbeat_interval_min", 10)
        set(v) = put("heartbeat_interval_min", v)

    var heartbeatDailyCap: Int
        get() = i("heartbeat_daily_cap", 100)
        set(v) = put("heartbeat_daily_cap", v)

    /** 同一件事（待办列表没变）至少隔多久才再说一次，分钟；0 = 不限制。原先写死 4 小时，设置页没有这一项 */
    var heartbeatSameContentMin: Int
        get() = i("heartbeat_same_content_min", 240)
        set(v) = put("heartbeat_same_content_min", v)

    /** 心跳设置的快照，交给纯函数 [HeartbeatPolicy] 判定 */
    fun hbConfig() = HeartbeatPolicy.Config(
        enabled = heartbeatEnabled,
        intervalMin = heartbeatIntervalMin,
        quietStart = quietStart,
        quietEnd = quietEnd,
        dailyCap = heartbeatDailyCap,
        batteryFloor = batteryFloor,
        autoDownshift = autoDownshift,
        sameContentMin = heartbeatSameContentMin,
        smartQuiet = smartQuiet,
    )

    var quietStart: String
        get() = s("quiet_start", "23:30")
        set(v) = put("quiet_start", v)

    var quietEnd: String
        get() = s("quiet_end", "07:30")
        set(v) = put("quiet_end", v)

    var heartbeatUseModel: Boolean   // 「该不该说」是否用模型判断
        get() = b("heartbeat_use_model", true)
        set(v) = put("heartbeat_use_model", v)

    /** 智能免打扰：勿扰/通话/会议中/睡觉中不主动开口 */
    var smartQuiet: Boolean
        get() = b("smart_quiet", true)
        set(v) = put("smart_quiet", v)

    /** 自动发现兴趣、职业、宠物等身份信息：先进待确认，说两次或你点"记住"才写进记忆 */
    var profileLearning: Boolean
        get() = b("profile_learning", true)
        set(v) = put("profile_learning", v)

    /** 候选记忆"再提一次才算第二次"的最短间隔（毫秒）。默认 10 分钟；调试钩子 E2E_CAND 可改成 10 秒，测试当场出结果 */
    var candidateGapMs: Long
        get() = sp.getLong("candidate_gap_ms", 10 * 60_000L)
        set(v) = sp.edit().putLong("candidate_gap_ms", v).apply()

    /** 候选记忆几次提到才自动写进记忆（1 = 说一次就记，等于不确认；默认 2） */
    var candidatePromoteAt: Int
        get() = i("candidate_promote_at", 2).coerceIn(1, 5)
        set(v) = put("candidate_promote_at", v.coerceIn(1, 5))

    // ---------------- 专注模式 ----------------
    var focusDurationMin: Int
        get() = i("focus_duration_min", 25).coerceIn(1, 180)
        set(v) = put("focus_duration_min", v.coerceIn(1, 180))
    var focusGraceSec: Int
        get() = i("focus_grace_sec", 8).coerceIn(0, 60)
        set(v) = put("focus_grace_sec", v.coerceIn(0, 60))
    var focusColdWarSec: Int
        get() = i("focus_cold_war_sec", 180).coerceIn(30, 900)
        set(v) = put("focus_cold_war_sec", v.coerceIn(30, 900))
    /** 0 温和 / 1 毒舌 / 2 地狱 */
    var focusDifficulty: Int
        get() = i("focus_difficulty", 1).coerceIn(0, 2)
        set(v) = put("focus_difficulty", v.coerceIn(0, 2))
    /** 进阶档：用「使用情况访问」识别打开了哪个分心应用（需要用户在系统设置里手动授权） */
    var focusAdvanced: Boolean
        get() = b("focus_advanced", false)
        set(v) = put("focus_advanced", v)
    /** 地狱档悬浮窗：台词盖在别的应用上方弹出（需要用户在系统设置里手动授「在其他应用上层显示」） */
    var focusOverlay: Boolean
        get() = b("focus_overlay", false)
        set(v) = put("focus_overlay", v)
    var focusDistractApps: Set<String>
        get() = sp.getStringSet("focus_distract_apps", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("focus_distract_apps", v).apply()

    /** 「再提醒」的间隔（秒），0 = 默认 10 分钟；调试钩子可设成 10 秒，测试当场出结果 */
    var snoozeSecOverride: Int
        get() = i("snooze_sec_override", 0)
        set(v) = put("snooze_sec_override", v.coerceAtLeast(0))

    // ---------------- 天气 ----------------
    /** 直接用手机自带天气应用已取好的数据（本机读取，不联网）；默认开 */
    var weatherUseSystem: Boolean
        get() = b("weather_use_system", true)
        set(v) = put("weather_use_system", v)
    /** 联网查天气（备用）：默认关。只发取整到 0.1° 的城市坐标 */
    var weatherNet: Boolean
        get() = b("weather_net", false)
        set(v) = put("weather_net", v)
    /** 联网时用的城市；留空 = 手机天气里排第一的城市（只对手机天气生效） */
    var weatherCity: String
        get() = s("weather_city", "")
        set(v) = put("weather_city", v)
    /** 联网缓存有效期（秒），0 = 默认 2 小时；调试钩子可设成几秒 */
    var weatherTtlOverrideSec: Int
        get() = i("weather_ttl_override_sec", 0)
        set(v) = put("weather_ttl_override_sec", v.coerceAtLeast(0))

    /** 记录步数（系统计步器；需要「身体活动」权限）：默认关 */
    var stepsEnabled: Boolean
        get() = b("steps_enabled", false)
        set(v) = put("steps_enabled", v)

    var autoDownshift: Boolean
        get() = b("auto_downshift", true)
        set(v) = put("auto_downshift", v)

    var batteryFloor: Int
        get() = i("battery_floor", 20)
        set(v) = put("battery_floor", v)

    // ---------------- 记忆 ----------------
    var memoryEnabled: Boolean
        get() = b("memory_enabled", true)
        set(v) = put("memory_enabled", v)

    var memoryTokenBudget: Int
        get() = i("memory_token_budget", 500)
        set(v) = put("memory_token_budget", v)

    // ---------------- 在线升级 ----------------
    var backend: String              // "local" / "deepseek"
        get() = s("backend", "local")
        set(v) = put("backend", v)

    var deepseekKey: String
        get() = s("deepseek_key", "")
        set(v) = put("deepseek_key", v)

    var deepseekModel: String
        get() = s("deepseek_model", "deepseek-chat")
        set(v) = put("deepseek_model", v)

    var dailyTokenCap: Int
        get() = i("daily_token_cap", 50000)
        set(v) = put("daily_token_cap", v)

    // ---------------- 通知 ----------------
    var notifySound: Boolean
        get() = b("notify_sound", true)
        set(v) = put("notify_sound", v)

    var notifyVibrate: Boolean
        get() = b("notify_vibrate", true)
        set(v) = put("notify_vibrate", v)

    var notificationReply: Boolean   // 通知栏内直接回复
        get() = b("notification_reply", true)
        set(v) = put("notification_reply", v)

    // ---------------- 心跳运行时状态 ----------------
    var hbLastFiredAt: Long
        get() = sp.getLong("hb_last_fired_at", 0L)
        set(v) = sp.edit().putLong("hb_last_fired_at", v).apply()

    var hbFiredToday: Int
        get() = sp.getInt("hb_fired_today", 0)
        set(v) = sp.edit().putInt("hb_fired_today", v).apply()

    var hbFiredDay: String
        get() = s("hb_fired_day", "")
        set(v) = put("hb_fired_day", v)

    var hbUnresponded: Int
        get() = sp.getInt("hb_unresponded", 0)
        set(v) = sp.edit().putInt("hb_unresponded", v).apply()

    var hbLastUserMessageAt: Long
        get() = sp.getLong("hb_last_user_msg_at", 0L)
        set(v) = sp.edit().putLong("hb_last_user_msg_at", v).apply()

    fun hbState(): HeartbeatPolicy.State = HeartbeatPolicy.State(
        lastFiredAt = hbLastFiredAt,
        firedToday = hbFiredToday,
        firedDay = hbFiredDay,
        unrespondedStreak = hbUnresponded,
        lastUserMessageAt = hbLastUserMessageAt,
    )

    fun saveHbState(now: Long, firedToday: Int, firedDay: String, unresponded: Int) {
        sp.edit()
            .putLong("hb_last_fired_at", now)
            .putInt("hb_fired_today", firedToday)
            .putString("hb_fired_day", firedDay)
            .putInt("hb_unresponded", unresponded)
            .apply()
    }

    // ---------------- 工具与联网 ----------------
    /** 联网总开关（默认关）。关掉时所有 requiresNetwork 的工具都不会交给模型 */
    var allowNetwork: Boolean
        get() = b("allow_network", false)
        set(v) = put("allow_network", v)

    /** 某个工具是否启用（未设置时用工具的默认值） */
    fun isToolEnabled(name: String, default: Boolean): Boolean =
        sp.getBoolean("tool_enabled_$name", default)

    fun setToolEnabled(name: String, enabled: Boolean) =
        sp.edit().putBoolean("tool_enabled_$name", enabled).apply()

    // ---------------- 心跳去噪状态 ----------------
    /** 上次开口的"内容指纹"（如 todo:#1,#2 / care / morning） */
    var hbLastContentKey: String
        get() = s("hb_last_content_key", "")
        set(v) = put("hb_last_content_key", v)

    /** 上次开口的时间（用于同内容冷却） */
    /** 最近说过的内容指纹 → 时间（最多 8 条，JSON），用于"同样内容冷却" */
    var hbRecentKeys: Map<String, Long>
        get() = try {
            val o = org.json.JSONObject(s("hb_recent_keys", "{}"))
            o.keys().asSequence().associateWith { o.getLong(it) }
        } catch (_: Exception) { emptyMap() }
        set(v) = put("hb_recent_keys", org.json.JSONObject(v as Map<*, *>).toString())

    /** 今天各件事已说过几次（JSON：{"day":"yyyy-MM-dd","counts":{key:n}}），用于每日次数上限 */
    var hbKeyCounts: HeartbeatPolicy.KeyCounts
        get() = try {
            val o = org.json.JSONObject(s("hb_key_counts", "{}"))
            val c = o.optJSONObject("counts")
            HeartbeatPolicy.KeyCounts(o.optString("day", ""), c?.keys()?.asSequence()?.associateWith { c.getInt(it) } ?: emptyMap())
        } catch (_: Exception) { HeartbeatPolicy.KeyCounts("", emptyMap()) }
        set(v) = put("hb_key_counts", org.json.JSONObject().put("day", v.day).put("counts", org.json.JSONObject(v.counts as Map<*, *>)).toString())

    /** 已经给过"上次聊天回顾"的会话 id（按先后顺序，最多 30 个）：每条回顾只展示一次 */
    var recapShown: Set<String>
        get() = try {
            val a = org.json.JSONArray(s("recap_shown", "[]"))
            (0 until a.length()).map { a.getString(it) }.toCollection(LinkedHashSet())
        } catch (_: Exception) { emptySet() }
        set(v) = put("recap_shown", org.json.JSONArray(v.toList()).toString())

    var hbLastContentKeyAt: Long
        get() = sp.getLong("hb_last_content_key_at", 0L)
        set(v) = sp.edit().putLong("hb_last_content_key_at", v).apply()

    // ---------------- 会话 ----------------
    /** 当前会话 id（空表示还没有；由 Conversations.current() 负责创建） */
    var currentConversationId: String
        get() = s("current_conversation_id", "")
        set(v) = put("current_conversation_id", v)
}
