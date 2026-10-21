package com.example.llama

import android.app.AlarmManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * 工具层 —— 2026-10-18 重构。
 *
 * 核心变化：工具不再返回一句"已成功"的字符串，而是返回结构化的 [ToolOutcome]：
 *   VERIFIED   效果已核实（回读文件 / 查询日历 / 系统时钟确认 / AlarmManager 已接受）
 *   UNVERIFIED 请求已发出，但程序无法确认生效（回执里如实说，并请用户自己看一眼）
 *   FAILED     失败（附原因）
 *   INFO       只读查询
 * 用户看到的"做了什么"一律来自 receipt（程序生成），模型只负责语气。
 *
 * 路由路径（IntentRouter）与模型路径（tool_call）共用 [Actions] 里的同一套实现。
 */
enum class ToolCategory(val label: String) {
    TODO("待办"),
    ALARM("闹钟"),
    TIME("时间"),
    NETWORK("联网"),
    GENERAL("其他"),
}

enum class ActionStatus { VERIFIED, UNVERIFIED, FAILED, INFO }

data class ToolOutcome(
    val status: ActionStatus,
    val receipt: String,
    val categories: Set<ClaimGuard.Cat> = emptySet(),
    val forModel: String = receipt,
) {
    val succeeded get() = status == ActionStatus.VERIFIED || status == ActionStatus.INFO

    companion object {
        fun ok(receipt: String, vararg cats: ClaimGuard.Cat) = ToolOutcome(ActionStatus.VERIFIED, "✅ $receipt", cats.toSet())
        fun unverified(receipt: String, vararg cats: ClaimGuard.Cat) = ToolOutcome(ActionStatus.UNVERIFIED, "⚠️ $receipt", cats.toSet())
        fun fail(receipt: String, vararg cats: ClaimGuard.Cat) = ToolOutcome(ActionStatus.FAILED, "❌ $receipt", cats.toSet())
        fun info(text: String) = ToolOutcome(ActionStatus.INFO, text)
    }
}

interface Tool {
    val name: String
    val description: String
    val parametersJson: String
    val category: ToolCategory get() = ToolCategory.GENERAL
    val requiresNetwork: Boolean get() = false
    val enabledByDefault: Boolean get() = true
    val timeoutMs: Long get() = 15_000L
    fun run(context: Context, args: JSONObject): ToolOutcome
}

/**
 * 在多个声明能处理 SET_ALARM 的应用里挑出"时钟"。纯函数，可单测。
 *
 * 真机证据（2026-10-19）：这台 vivo 上有两个应用都声明了 SET_ALARM——`com.android.BBKClock` 和企业管理应用
 * `com.leagsoft.emm`——系统弹出选择器，应用发出的意图卡在选择器上，回执就写成"系统时钟没有接受"。
 * 直接 `setPackage(com.android.BBKClock)` 后闹钟静默登记，并在 17:28:00.098 准点响起（dumpsys audio 取证）。
 */
object ClockPicker {
    private val KNOWN = listOf(
        "com.android.BBKClock",                 // vivo / iQOO
        "com.google.android.deskclock",         // Google 时钟
        "com.android.deskclock",                // AOSP / 小米等
        "com.sec.android.app.clockpackage",     // 三星
        "com.oplus.alarmclock", "com.coloros.alarmclock",   // OPPO / 一加
        "com.huawei.deskclock", "com.hihonor.deskclock",    // 华为 / 荣耀
    )

    /** @return 应该把意图发给的包名；多个候选又认不出时钟时返回 null（调用方不得盲发，会弹选择器） */
    fun pick(handlers: List<String>): String? {
        val hs = handlers.distinct()
        return KNOWN.firstOrNull { it in hs }
            ?: hs.firstOrNull { it.contains("clock", ignoreCase = true) }
            ?: hs.singleOrNull()
    }
}

// ============================================================================ 动作实现
object Actions {

    private fun ms(dt: LocalDateTime) = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun ampmNote(p: TimeParser.Parsed?) =
        if (p?.ampmAssumed == true) "（按${if ((p.dateTime.hour) < 12) "上午" else "下午"}理解；不对的话请说清楚上午/下午再说一次）" else ""

    // ---------------------------------------------------------------- 闹钟
    fun setAlarm(ctx: Context, at: LocalDateTime, label0: String, parsed: TimeParser.Parsed? = null, repeat: String = ""): ToolOutcome {
        val now = LocalDateTime.now()
        // 重复闹钟：系统时钟的 SET_ALARM 不好核验重复日，交给助手自己登记（到点响铃，响完自动排下一次）
        RepeatRule.fromString(repeat)?.let { rule ->
            val r = ReminderScheduler.schedule(ctx, label0.ifBlank { "闹钟" }, ms(at), alarm = true, repeat = repeat)
            val hm = "%02d:%02d".format(at.hour, at.minute)
            return if (r.ok) ToolOutcome.unverified(
                "已登记重复闹钟：${RepeatRule.describe(rule)} $hm（${label0.ifBlank { "闹钟" }}），第一次 ${TimeParser.describe(at, now)}。⚠️ 重复闹钟由助手自己定时，需要你已给本应用放行后台限制；响完会自动排下一次" +
                    (if (!r.exact) "；另外未获精确定时权限，可能晚几分钟" else "") + ampmNote(parsed),
            ) else ToolOutcome.fail("没能登记重复闹钟：${r.error}", ClaimGuard.Cat.ALARM)
        }
        val desc = TimeParser.describe(at, now)
        if (!at.isAfter(now)) return ToolOutcome.fail("没能设闹钟：$desc 已经过去了", ClaimGuard.Cat.ALARM)
        val label = label0.ifBlank { "闹钟" }
        val atMs = ms(at)

        // 系统时钟的 SET_ALARM 只能设"下一次出现的 HH:MM"，超过 24 小时的日期它会静默设错
        val todayAt = now.toLocalDate().atTime(at.hour, at.minute)
        val nextOccurrence = if (todayAt.isAfter(now)) todayAt else todayAt.plusDays(1)
        val withinSystemRange = nextOccurrence == at.withSecond(0).withNano(0)
        var reason = "系统时钟只能设 24 小时内的闹钟"

        if (withinSystemRange) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, at.hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, at.minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // 必须指定包名：多个应用声明了 SET_ALARM 时系统会弹选择器，意图就卡在那里
                val handlers = ctx.packageManager
                    .queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    .map { it.activityInfo.packageName }
                val clock = ClockPicker.pick(handlers)
                    ?: throw IllegalStateException(
                        if (handlers.isEmpty()) "没有应用能处理设闹钟" else "有多个应用都能设闹钟（${handlers.joinToString("、")}），认不出哪个是时钟"
                    )
                intent.setPackage(clock)
                ctx.startActivity(intent)
                // 核验：系统时钟收到后会调用 setAlarmClock，下一个闹钟时间应该等于目标
                var matched = false
                repeat(20) {
                    if (matched) return@repeat
                    Thread.sleep(300)
                    val next = am?.nextAlarmClock?.triggerTime
                    if (next != null && abs(next - atMs) < 60_000) matched = true
                }
                if (matched) {
                    return ToolOutcome.ok("已设闹钟：$desc（$label）· 系统时钟已确认${ampmNote(parsed)}", ClaimGuard.Cat.ALARM)
                }
                val next = am?.nextAlarmClock?.triggerTime
                if (next != null && next < atMs - 60_000) {
                    return ToolOutcome.unverified(
                        "已把 $desc 的闹钟发给系统时钟，但你还有更早的闹钟，程序没法自动核对它是否生效——请打开时钟看一眼${ampmNote(parsed)}",
                    )
                }
                reason = "系统时钟没有接受（可能被系统的「应用跳转确认」拦截，或 App 不在前台）"
            } catch (e: Exception) {
                reason = "没能交给系统时钟（${e.message ?: e.javaClass.simpleName}）"
            }
        }

        // 兜底：助手自己的闹钟（AlarmManager 精确定时 + 持续响铃通知）
        val r = ReminderScheduler.schedule(ctx, label, atMs, alarm = true)
        return if (r.ok) {
            // 助手闹钟只是应用自己登记的定时：真机（vivo）会在应用退到后台约 20 秒后冻结它并删掉闹钟（dumpsys alarm: Reason=frozen），
            // 凌晨 6:30 的助手闹钟 07:06 才响。不能承诺"到点会响"，只能如实说"已登记、可能不响"。
            ToolOutcome.unverified(
                "已登记助手闹钟：$desc（$label）· $reason。⚠️ 助手闹钟在这台手机的后台可能被系统冻结而不响；" +
                    "重要的闹钟请说成 24 小时内的时间（会交给系统时钟，准点可靠）" +
                    (if (!r.exact) "；另外未获精确定时权限，可能晚几分钟" else "") + ampmNote(parsed),
            )
        } else {
            ToolOutcome.fail("没能设闹钟：$reason；助手闹钟也登记失败（${r.error}）", ClaimGuard.Cat.ALARM)
        }
    }

    // ---------------------------------------------------------------- 提醒
    fun addReminder(ctx: Context, at: LocalDateTime, text: String, parsed: TimeParser.Parsed? = null, repeat: String = ""): ToolOutcome {
        val now = LocalDateTime.now()
        val desc = TimeParser.describe(at, now)
        if (!at.isAfter(now)) return ToolOutcome.fail("没能登记提醒：$desc 已经过去了", ClaimGuard.Cat.REMINDER)
        val content = text.ifBlank { "提醒" }
        val r = ReminderScheduler.schedule(ctx, content, ms(at), alarm = false, repeat = repeat)
        val rep = RepeatRule.fromString(repeat)?.let { "（${RepeatRule.describe(it)}重复，响完自动排下一次）" } ?: ""
        return if (r.ok) {
            ToolOutcome.ok(
                "已登记提醒：$desc「$content」$rep· 到点会发通知" + (if (!r.exact) "（未获精确定时权限，可能晚几分钟）" else "") + ampmNote(parsed),
                ClaimGuard.Cat.REMINDER,
            )
        } else {
            ToolOutcome.fail("没能登记提醒：${r.error}", ClaimGuard.Cat.REMINDER)
        }
    }

    // ---------------------------------------------------------------- 待办
    fun addTodo(ctx: Context, store: TodoStore, text: String, parsed: TimeParser.Parsed?): ToolOutcome {
        if (text.isBlank()) return ToolOutcome.fail("没能加入待办：没听清要记什么事", ClaimGuard.Cat.TODO)
        val now = LocalDateTime.now()
        val dueText = parsed?.let {
            if (it.hasTime) TimeParser.describe(it.dateTime, now) else TimeParser.describe(it.dateTime, now).substringBefore(" ") + "（未定钟点）"
        }.orEmpty()
        val t = try {
            store.add(text, dueText, parsed?.dateTime?.let { ms(it) } ?: 0L)
        } catch (e: Exception) {
            return ToolOutcome.fail("没能加入待办：写入失败（${e.message}）", ClaimGuard.Cat.TODO)
        }
        if (store.get(t.id) == null) return ToolOutcome.fail("没能加入待办：写入后回读不到", ClaimGuard.Cat.TODO)

        val base = "已加入待办 #${t.id}「${t.text}」" + (if (dueText.isNotBlank()) "（$dueText）" else "")
        // 有具体钟点的待办，同时登记到点提醒——这样"到时候提醒你"才是真的
        if (parsed != null && parsed.hasTime && parsed.dateTime.isAfter(now)) {
            val r = ReminderScheduler.schedule(ctx, "待办：${t.text}", ms(parsed.dateTime), alarm = false)
            if (r.ok && r.reminder != null) {
                store.setReminder(t.id, r.reminder.id)
                return ToolOutcome.ok("$base · 到点会提醒你${ampmNote(parsed)}", ClaimGuard.Cat.TODO, ClaimGuard.Cat.REMINDER)
            }
            return ToolOutcome.ok("$base · 但到点提醒没登记成功（${r.error}），到时候不会自动通知", ClaimGuard.Cat.TODO)
        }
        return ToolOutcome.ok(base, ClaimGuard.Cat.TODO)
    }

    fun listTodos(store: TodoStore): ToolOutcome = ToolOutcome.info(store.renderList())

    fun doneTodo(store: TodoStore, id: Int): ToolOutcome {
        val t = store.get(id) ?: return ToolOutcome.fail("没有找到待办 #$id", ClaimGuard.Cat.TODO)
        if (t.done) return ToolOutcome.info("待办 #$id「${t.text}」本来就是已完成状态。")
        return try {
            store.done(id)
            if (store.get(id)?.done == true) ToolOutcome.ok("已把待办 #$id「${t.text}」标记为完成", ClaimGuard.Cat.TODO)
            else ToolOutcome.fail("标记待办 #$id 失败：回读状态未改变", ClaimGuard.Cat.TODO)
        } catch (e: Exception) {
            ToolOutcome.fail("标记待办 #$id 失败（${e.message}）", ClaimGuard.Cat.TODO)
        }
    }

    // ---------------------------------------------------------------- 日历
    fun addCalendar(
        ctx: Context, title0: String, at: LocalDateTime, durationMin: Int = 30, remindMinutes: Int = 0,
        parsed: TimeParser.Parsed? = null,
    ): ToolOutcome {
        val now = LocalDateTime.now()
        val desc = TimeParser.describe(at, now)
        if (at.isBefore(now.minusMinutes(1))) return ToolOutcome.fail("没能写入日历：$desc 已经过去了", ClaimGuard.Cat.CALENDAR)
        val title = title0.ifBlank { "日程" }
        return try {
            val calId = findWritableCalendar(ctx)
                ?: return ToolOutcome.fail("没能写入日历：手机里没有可写的日历账户，请先在系统「日历」里添加一个", ClaimGuard.Cat.CALENDAR)
            val start = ms(at)
            val end = start + durationMin.coerceIn(5, 600) * 60_000L
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                put(CalendarContract.Events.HAS_ALARM, 1)
            }) ?: return ToolOutcome.fail("没能写入日历：系统返回空（可能没有写权限）", ClaimGuard.Cat.CALENDAR)
            val eventId = ContentUris.parseId(uri)
            // 旧版只设了 HAS_ALARM=1 却没插 Reminders 行 —— 日历其实不会提醒
            val remOk = try {
                ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, remindMinutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }) != null
            } catch (_: Exception) {
                false
            }
            // 回读核验
            val exists = ctx.contentResolver.query(uri, arrayOf(CalendarContract.Events._ID), null, null, null)
                ?.use { it.moveToFirst() } == true
            if (!exists) return ToolOutcome.fail("写入日历后回读不到这条日程", ClaimGuard.Cat.CALENDAR)
            if (remOk) ToolOutcome.ok("已写入系统日历：$desc「$title」· 日历会准时提醒${ampmNote(parsed)}", ClaimGuard.Cat.CALENDAR, ClaimGuard.Cat.REMINDER)
            else ToolOutcome.ok("已写入系统日历：$desc「$title」（日历提醒没设上，到时候不会弹提醒）${ampmNote(parsed)}", ClaimGuard.Cat.CALENDAR)
        } catch (e: SecurityException) {
            ToolOutcome.fail("没能写入日历：没有日历权限，请到系统设置里给本应用开启「日历」权限", ClaimGuard.Cat.CALENDAR)
        } catch (e: Exception) {
            ToolOutcome.fail("没能写入日历（${e.javaClass.simpleName}: ${e.message}）", ClaimGuard.Cat.CALENDAR)
        }
    }

    fun findWritableCalendar(context: Context): Long? {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
        val sel = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, selArgs, null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }

    // ---------------------------------------------------------------- 时间
    fun nowText(): String {
        val now = LocalDateTime.now()
        val wd = arrayOf("一", "二", "三", "四", "五", "六", "日")[now.dayOfWeek.value - 1]
        return "现在是 %04d-%02d-%02d %02d:%02d 星期%s".format(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, wd)
    }
}

// ============================================================================ 给模型用的工具（模型路径）
private const val HONEST = "只有调用本工具才算真的做了；不要在没调用时声称已完成。"

class TodoAddTool(private val store: TodoStore) : Tool {
    override val name = "todo_add"
    override val description = "添加一条待办事项（用户说「待办/任务/要做/记一下」时用）。$HONEST"
    override val category = ToolCategory.TODO
    override val parametersJson = """
        {"type":"object","properties":{
          "text":{"type":"string","description":"待办内容，不含时间"},
          "due":{"type":"string","description":"可选。用户说的时间原话，例如「周五下午三点」"}
        },"required":["text"]}
    """.trimIndent()

    override fun run(context: Context, args: JSONObject): ToolOutcome {
        val due = args.optString("due", "").trim()
        val parsed = if (due.isNotEmpty()) TimeParser.parse(due, LocalDateTime.now()) else null
        return Actions.addTodo(context, store, args.optString("text", "").trim(), parsed)
    }
}

class TodoListTool(private val store: TodoStore) : Tool {
    override val name = "todo_list"
    override val description = "列出当前所有未完成的待办事项。用户问「我有什么待办」「今天要做什么」时用。"
    override val category = ToolCategory.TODO
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, args: JSONObject) = Actions.listTodos(store)
}

class TodoDoneTool(private val store: TodoStore) : Tool {
    override val name = "todo_done"
    override val description = "把某条待办标记为已完成。用户说「做完了」「划掉第 N 条」时用。$HONEST"
    override val category = ToolCategory.TODO
    override val parametersJson = """
        {"type":"object","properties":{"id":{"type":"integer","description":"待办编号"}},"required":["id"]}
    """.trimIndent()
    override fun run(context: Context, args: JSONObject): ToolOutcome {
        val id = args.optInt("id", -1)
        if (id <= 0) return ToolOutcome.fail("缺少合法的待办编号", ClaimGuard.Cat.TODO)
        return Actions.doneTodo(store, id)
    }
}

class AlarmSetTool : Tool {
    override val name = "alarm_set"
    override val description = "设置闹钟（到点响铃）。只在用户要「闹钟/叫醒我/叫我起床」时使用。$HONEST"
    override val category = ToolCategory.ALARM
    override val parametersJson = """
        {"type":"object","properties":{
          "hour":{"type":"integer","description":"小时 0-23"},
          "minute":{"type":"integer","description":"分钟 0-59"},
          "date":{"type":"string","description":"可选，YYYY-MM-DD；不填表示最近的一次"},
          "label":{"type":"string","description":"闹钟备注"}
        },"required":["hour","minute"]}
    """.trimIndent()

    override fun run(context: Context, args: JSONObject): ToolOutcome {
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolOutcome.fail("时间不合法（hour=$hour, minute=$minute）", ClaimGuard.Cat.ALARM)
        }
        val now = LocalDateTime.now()
        val date = args.optString("date", "").trim().let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val at = if (date != null) date.atTime(hour, minute) else {
            val today = now.toLocalDate().atTime(hour, minute)
            if (today.isAfter(now)) today else today.plusDays(1)
        }
        return Actions.setAlarm(context, at, args.optString("label", ""))
    }
}

class ReminderAddTool : Tool {
    override val name = "reminder_add"
    override val description = "登记一个到点提醒（到时间会发通知）。用户说「提醒我/到时候叫我/记得告诉我」并给了时间时用。$HONEST"
    override val category = ToolCategory.ALARM
    override val parametersJson = """
        {"type":"object","properties":{
          "text":{"type":"string","description":"要提醒的事"},
          "when":{"type":"string","description":"用户说的时间原话，例如「明天下午三点」「十分钟后」"}
        },"required":["text","when"]}
    """.trimIndent()

    override fun run(context: Context, args: JSONObject): ToolOutcome {
        val whenText = args.optString("when", "")
        val parsed = TimeParser.parse(whenText, LocalDateTime.now())
            ?: return ToolOutcome.fail("没听懂提醒时间「$whenText」", ClaimGuard.Cat.REMINDER)
        return Actions.addReminder(context, parsed.dateTime, args.optString("text", ""), parsed)
    }
}

class CalendarAddTool : Tool {
    override val name = "calendar_add"
    override val description = "把日程写进系统日历（用户能在日历应用里看到，并会准时提醒）。用户说「日程/安排/会议/约」时用。$HONEST"
    override val category = ToolCategory.GENERAL
    override val parametersJson = """
        {"type":"object","properties":{
          "title":{"type":"string","description":"日程标题"},
          "when":{"type":"string","description":"用户说的时间原话，例如「明天下午三点」"},
          "durationMin":{"type":"integer","description":"持续分钟数，默认 30"}
        },"required":["title","when"]}
    """.trimIndent()

    override fun run(context: Context, args: JSONObject): ToolOutcome {
        val whenText = args.optString("when", "")
        val parsed = TimeParser.parse(whenText, LocalDateTime.now())
            ?: return ToolOutcome.fail("没听懂日程时间「$whenText」", ClaimGuard.Cat.CALENDAR)
        return Actions.addCalendar(context, args.optString("title", ""), parsed.dateTime, args.optInt("durationMin", 30), 0, parsed)
    }
}

class CurrentTimeTool : Tool {
    override val name = "current_time"
    override val description = "获取当前日期和时间。"
    override val category = ToolCategory.TIME
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, args: JSONObject) = ToolOutcome.info(Actions.nowText())
}

/**
 * 工具注册表 —— 按设置过滤，只把启用的工具交给模型。
 */
class ToolRegistry(context: Context) {

    private val appContext = context.applicationContext
    private val settings = AppSettings(appContext)
    val todoStore = TodoStore(appContext)

    val allTools: List<Tool> = listOf(
        TodoAddTool(todoStore),
        TodoListTool(todoStore),
        TodoDoneTool(todoStore),
        AlarmSetTool(),
        ReminderAddTool(),
        CurrentTimeTool(),
        CalendarAddTool(),
    )

    fun enabledTools(): List<Tool> {
        val netOk = settings.allowNetwork
        return allTools.filter { t ->
            settings.isToolEnabled(t.name, t.enabledByDefault) && (!t.requiresNetwork || netOk)
        }
    }

    fun isEnabled(name: String) = enabledTools().any { it.name == name }

    fun toCodecDefs(): List<ToolCodec.ToolDef> =
        enabledTools().map { ToolCodec.ToolDef(it.name, it.description, it.parametersJson) }

    fun execute(name: String, args: JSONObject, context: Context): ToolOutcome {
        val tool = enabledTools().firstOrNull { it.name == name }
            ?: return if (allTools.any { it.name == name }) ToolOutcome.fail("工具 $name 当前未启用（可在设置 → 工具管理里打开）")
            else ToolOutcome.fail("没有名为 $name 的工具")
        return try {
            tool.run(context, args)
        } catch (e: Exception) {
            ToolOutcome.fail("工具 $name 执行出错：${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
