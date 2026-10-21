package com.example.llama

import java.time.LocalDateTime

/**
 * 确定性意图路由 —— 纯 Kotlin，可单测。
 *
 * 成熟语音助手（小爱/Siri）的做法：高频、可枚举的指令（闹钟/提醒/待办/日程/查时间）
 * 由 NLU 规则 + 槽位抽取直接执行；只有开放式对话才交给大模型。
 * 原因：4B 模型"该调工具没调""调了但参数编错"是常态，而这几类指令一旦出错就是"说办了没办"。
 *
 * 路由结果：
 *   confident=true  → 程序直接执行，不经过模型第 1 轮（快、可预测）
 *   confident=false → 缺槽位（例如"设个闹钟"没说几点）→ 程序追问；或交给模型兜底
 */
object IntentRouter {

    enum class Kind { ABOUT_SELF, MEMORY_QUERY, WORLD_QUERY, ALARM, REMINDER, REMINDER_EDIT, TODO_ADD, TODO_LIST, TODO_DONE, CALENDAR_ADD, TIME_QUERY, UNSUPPORTED, NONE }

    data class Route(
        val kind: Kind,
        val confident: Boolean,
        val time: TimeParser.Parsed? = null,
        val content: String = "",
        val todoId: Int? = null,
        val missing: String? = null,   // 缺什么槽位（用于追问）
        val reason: String = "",
        val query: MemoryQA.Query? = null,   // MEMORY_QUERY / WORLD_QUERY 的具体问法
        val repeat: String = "",             // 提醒/闹钟的重复规则（RepeatRule.serialize），空 = 只响一次
        val edit: ReminderEdit.Edit? = null, // REMINDER_EDIT：取消 / 改时间 / 再提醒
    )

    private val TIME_QUERY = Regex("(现在|当前)?(几点了|几点钟|什么时间|几点)\\s*[?？了啊呀呢]*$|今天(几号|星期几|周几|礼拜几)|今天是(几号|星期几|周几)|现在是几点")
    private val ABOUT_SELF = Regex(
        "你(用的?是|是基于|基于|是用|用的)?.{0,6}(什么|哪个|哪种|啥).{0,4}(模型|AI|人工智能)|" +
            "你是(不是)?(AI|人工智能|机器人|真人|人类)(吗|么)?"
    )
    private val TODO_LIST = Regex("(我|还)?(有|剩)(什么|哪些|几个|几条|多少)(待办|任务|事情要做|事要做)|(看看|查看|查一下|列出|列一下|显示)(我的)?(全部|所有)?(待办|任务)|待办(列表|清单)|今天要做(什么|啥)")
    private val TODO_DONE = Regex("(?:(?:第|#)\\s*(\\d{1,3}|[一二三四五六七八九十]{1,3})\\s*(?:条|个|项|号)?).{0,6}(完成|做完|搞定|划掉|勾掉|办完)|(完成|做完|搞定|划掉|勾掉|办完)了?\\s*(?:第|#)\\s*(\\d{1,3}|[一二三四五六七八九十]{1,3})")
    private val CANCEL = Regex("(取消|删除|删掉|关掉|关闭|撤销|去掉|改成|改到|推迟|延后).{0,8}(闹钟|提醒|日程|待办)|(闹钟|提醒|日程).{0,4}(取消|删掉|关掉|不要了)")
    private val ALARM_WORDS = Regex("闹钟|叫醒|起床|喊醒|早起")
    private val ALARM_REQUEST = Regex("闹钟|叫醒|喊醒|叫我|喊我|设|定|帮我|提醒")
    private val TIMER_WORDS = Regex("倒计时|计时器|计时|定时")
    private val CALL_ME = Regex("(叫|喊)我")
    private val REMIND_WORDS = Regex("提醒|通知我|到时候告诉我|到点告诉我|记得叫我|记得喊我")
    private val CALENDAR_WORDS = Regex("日程|日历|行程|会议安排|排个会|约(了|个)?会")
    private val TODO_WORDS = Regex("待办|加一条|添一条|记一下|记个事|任务清单|加个任务|添加任务|别忘了")

    /** 粗粒度：这句话是不是在要求"做事"（用于兜底诚实检查） */
    private val ACTION_LIKE = Regex(
        "闹钟|提醒|叫醒|叫我|喊我|起床|日程|日历|待办|任务|定时|计时|记一下|记个事|别忘了|安排|" +
            "帮我(设|加|定|记|订|建|排|写|弄)|设(个|一个|置|定)|定(个|一个)|加(一条|个)"
    )

    fun looksLikeActionRequest(text: String): Boolean = ACTION_LIKE.containsMatchIn(text)

    /**
     * 用户这句话里有没有"要办事"的意思。只有有，才把工具交给模型兜底。
     * 闲聊轮不开放工具：2026-10-18 e2e 里「今天上班好累啊」+ 注入的记忆"周五要交周报"，4B 模型自己调了 todo_add，
     * 真的写进了待办。比 [looksLikeActionRequest] 宽（"帮我…"算请求），但"任务好多啊"这类抱怨不算。
     */
    private val TOOL_REQUEST = Regex(
        "闹钟|提醒|叫醒|叫我|喊我|起床|日程|日历|待办|任务清单|添加任务|加个任务|定时|计时|记一下|记个事|记下来|写下来|" +
            "别忘了|别让我忘|不要让我忘|通知我|安排|帮我|帮忙|设(个|一个|置|定)|定(个|一个)|加(一条|个)"
    )

    fun allowTools(text: String): Boolean = TOOL_REQUEST.containsMatchIn(text)

    /** 带重复词（每天/每周三/工作日/每月15号）的提醒或闹钟：去掉重复词按普通说法解析，第一次触发落在规则允许的日子 */
    fun route(text0: String, now: LocalDateTime): Route {
        val rule = RepeatRule.parse(text0)
        if (rule == null) return routeCore(text0, now)
        val r = routeCore(RepeatRule.strip(text0), now)
        if ((r.kind != Kind.REMINDER && r.kind != Kind.ALARM) || !r.confident) return r
        val t = r.time ?: return r
        if (!t.hasTime || t.relative) return r
        val first = RepeatRule.next(rule, now, t.dateTime.toLocalTime())
        return r.copy(time = t.copy(dateTime = first, hasDate = true), repeat = RepeatRule.serialize(rule))
    }

    private fun routeCore(text0: String, now: LocalDateTime): Route {
        val text = text0.trim()
        if (text.isEmpty()) return Route(Kind.NONE, false)

        if (TIME_QUERY.containsMatchIn(text) && !looksLikeActionRequest(text)) {
            return Route(Kind.TIME_QUERY, true, reason = "问时间")
        }
        if (ABOUT_SELF.containsMatchIn(text)) return Route(Kind.ABOUT_SELF, true, reason = "问身份/模型")
        // 问记忆 / 问实时信息：由程序用真实数据回答，不交给模型（模型不会"知道自己不知道"，见 MemoryQA）。办事类请求优先
        // 「今天有什么安排」里带「安排」会被当成办事请求，但它问的是简报（读日程/待办），不是要你去安排；步数同理
        MemoryQA.detect(text)?.let { q ->
            if (!allowTools(text) || (q.kind == MemoryQA.Q.WORLD && (q.topic == "简报" || q.topic == "步数"))) {
                return Route(if (q.kind == MemoryQA.Q.WORLD) Kind.WORLD_QUERY else Kind.MEMORY_QUERY, true, query = q, reason = "问记忆/实时信息")
            }
        }
        if (TODO_LIST.containsMatchIn(text)) return Route(Kind.TODO_LIST, true, reason = "查待办")
        TODO_DONE.find(text)?.let { m ->
            val raw = m.groupValues[1].ifEmpty { m.groupValues[4] }
            val id = TimeParser.cnToInt(raw)
            if (id != null && id > 0) return Route(Kind.TODO_DONE, true, todoId = id, reason = "完成待办")
        }
        // 改 / 删 / 推迟 助手登记的提醒和闹钟（日程在系统日历里，程序改不了，仍走下面的说明）
        ReminderEdit.parse(text, now)?.let { e ->
            return if (e.target == ReminderEdit.Target.CALENDAR) Route(Kind.UNSUPPORTED, true, reason = "取消/修改日程")
            else Route(Kind.REMINDER_EDIT, true, edit = e, reason = "改/删/推迟提醒")
        }
        if (CANCEL.containsMatchIn(text)) {
            return Route(Kind.UNSUPPORTED, true, reason = "取消/修改闹钟、提醒、日程")
        }

        // 计时器："设一个20分钟的计时" → 当作 20 分钟后的提醒
        if (TIMER_WORDS.containsMatchIn(text)) {
            val mins = TimeParser.parseDurationMinutes(text)
            return if (mins != null) Route(
                Kind.REMINDER, true,
                TimeParser.Parsed(now.plusMinutes(mins).withSecond(0).withNano(0), hasDate = false, hasTime = true, relative = true, ampmAssumed = false),
                content = "${mins}分钟计时到了", reason = "计时器",
            ) else Route(Kind.REMINDER, false, missing = "计时多久", reason = "计时缺时长")
        }

        val time = TimeParser.parse(text, now)
        val content = extractContent(text)
        val timed = time != null && (time.hasTime || time.relative)

        // 闹钟：明确说"闹钟/叫醒/起床"，或"X点叫我"且没有别的事由
        // "我今天起床晚了""我明天要早起"是在说自己的事：没有具体钟点、也没有"叫/设/闹钟"这类请求词，就不是设闹钟
        val alarmRequest = timed || ALARM_REQUEST.containsMatchIn(text)
        val isAlarm = (ALARM_WORDS.containsMatchIn(text) && alarmRequest) ||
            (CALL_ME.containsMatchIn(text) && content.isEmpty() && timed)
        if (isAlarm && !REMIND_WORDS.containsMatchIn(text.replace("提醒我起床", "叫我起床"))) {
            return if (timed) Route(Kind.ALARM, true, time, content, reason = "闹钟")
            else Route(Kind.ALARM, false, time, content, missing = "几点", reason = "闹钟缺时间")
        }

        if (CALENDAR_WORDS.containsMatchIn(text)) {
            return if (time != null) Route(Kind.CALENDAR_ADD, true, time, content, reason = "日程")
            else Route(Kind.CALENDAR_ADD, false, null, content, missing = "日期和时间", reason = "日程缺时间")
        }

        // "叫我/喊我"只有带时间时才算提醒（"以后叫我老板"是改称呼，不是提醒）
        val isRemind = REMIND_WORDS.containsMatchIn(text) || (CALL_ME.containsMatchIn(text) && content.isNotEmpty() && timed)
        if (isRemind) {
            return if (timed) Route(Kind.REMINDER, true, time, content, reason = "提醒")
            else if (TODO_WORDS.containsMatchIn(text) && content.isNotEmpty()) Route(Kind.TODO_ADD, true, time, content, reason = "待办（无具体时间的提醒）")
            else Route(Kind.REMINDER, false, time, content, missing = "什么时候", reason = "提醒缺时间")
        }

        if (TODO_WORDS.containsMatchIn(text)) {
            return if (content.isNotEmpty()) Route(Kind.TODO_ADD, true, time, content, reason = "待办")
            else Route(Kind.TODO_ADD, false, time, content, missing = "要记什么事", reason = "待办缺内容")
        }
        return Route(Kind.NONE, false, time, content)
    }

    // ------------------------------------------------------------ 内容提取
    private val TRIGGERS = listOf(
        "你好", "您好", "麻烦你", "麻烦", "请你", "请", "帮我", "帮忙", "给我", "替我",
        "加到日历里", "加到日历", "加进日历", "写进日历", "加到", "加进", "写进", "放进", "放到", "设定一个", "设定", "设置一个", "设置", "设一个", "设个", "定一个", "定个", "新建", "建一个", "添加", "加一条", "添一条", "加个", "记一下", "记个事",
        "到时候告诉我", "到点告诉我", "记得叫我", "记得喊我", "提醒我", "提醒一下", "提醒", "通知我",
        "叫醒我", "喊醒我", "叫我", "喊我", "闹钟", "待办事项", "待办", "日程", "日历", "行程", "任务", "别忘了",
        "倒计时", "计时器", "计时", "定时", "一下", "好吗", "好不好", "可以吗", "行吗", "吧",
    )
    private val FILLER_SEGMENT = Regex("^(的|了|吧|呢|啊|呀|哦|喔|嗯|个|一个|一条|要|在|把|一下|下|钟|点|我|你)*$")

    /** 句尾框架：它后面跟的就是事由原文（长词在前，同位置取最长） */
    private val TERMINAL_FRAMES = listOf(
        "到时候告诉我", "到点告诉我", "记得叫我", "记得喊我", "提醒一下我", "提醒我", "提醒一下", "通知我", "叫醒我", "喊醒我", "叫我", "喊我",
    )
    /** 中文冒号 / 英文冒号，但不是 7:30 里的那个 */
    private val COLON = Regex("(?<![0-9])[：:](?![0-9])")
    private val SEP = Regex("[，,。.！!？?：:；;\\s、~～]+")
    private val PAYLOAD_TAIL = Regex("(好吗|好不好|可以吗|行吗|一下|吧|呢|啊|哦|呀|了|的)+$")

    /**
     * 事由原文已被用户明确框出来时，直接取原文，不再按命令词去抠：
     *   「加一条待办：自动测试待办」→「自动测试待办」；「两分钟后提醒我测试提醒」→「测试提醒」；
     *   「提醒我给我妈打电话」→「给我妈打电话」（旧做法会抠掉"给我"变成「妈打电话」）
     * 取不到（框架后面没有事由）返回 null，由 [extractContent] 走旧路径。
     */
    private fun explicitPayload(text: String): String? {
        COLON.find(text)?.let { m ->
            val head = text.substring(0, m.range.first)
            if (TRIGGERS.any { head.contains(it) }) {
                cleanPayload(text.substring(m.range.last + 1)).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        var at = -1
        var frame = ""
        for (f in TERMINAL_FRAMES) {
            val i = text.indexOf(f)
            if (i >= 0 && (at < 0 || i < at)) { at = i; frame = f }
        }
        if (at >= 0) cleanPayload(text.substring(at + frame.length)).takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    private fun cleanPayload(raw: String): String =
        TimeParser.stripTimeWords(raw).split(SEP)
            .map { it.trim().trimStart('的').replace(PAYLOAD_TAIL, "") }
            .filter { it.isNotEmpty() && !FILLER_SEGMENT.matches(it) }
            .joinToString("，").take(40)

    fun extractContent(text: String): String {
        explicitPayload(text)?.let { return it }
        var s = TimeParser.stripTimeWords(text)
        for (w in TRIGGERS) s = s.replace(w, " ")
        val segs = s.split(Regex("[，,。.！!？?：:；;\\s、~～]+"))
            .map { it.trim().trimStart('的', '要', '在', '把', '我').trimEnd('的', '了', '吧', '呢', '啊', '哦', '呀') }
            .filter { it.isNotEmpty() && !FILLER_SEGMENT.matches(it) }
        return segs.joinToString("，").take(40)
    }
}

