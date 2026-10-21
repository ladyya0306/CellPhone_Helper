package com.example.llama

import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.time.LocalDateTime

/**
 * 确定性逻辑的回归语料 —— 纯 Kotlin。
 *
 * 用途：
 *   1. 电脑上：虚拟机/JVM 直接运行（不需要手机、不需要模型）
 *   2. 手机上：设置页「运行自检」一键执行，结果写入 files/selftest.txt
 *
 * 规则：以后每修一个"说了没做/记错/解析错"的 bug，先把触发它的原话加进这里，再改代码。
 * 这样同一类问题不会第二次出现（"同类问题反复修改"的根治办法）。
 */
object AssistantSelfTest {

    data class Report(val passed: Int, val failed: List<String>) {
        val ok get() = failed.isEmpty()
        fun render(): String = buildString {
            append("自检：通过 ").append(passed).append(" / ").append(passed + failed.size).append('\n')
            failed.forEach { append("✗ ").append(it).append('\n') }
        }
    }

    /** 固定的"现在"：2026-10-17 21:04（星期六） */
    val NOW: LocalDateTime = LocalDateTime.of(2026, 10, 17, 21, 4)

    fun runAll(): Report {
        val fails = ArrayList<String>()
        var pass = 0
        fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
            if (cond) pass++ else fails.add(name + "  " + detail())
        }
        // 单个方法超过 64KB 字节码会编译失败（用例越写越多）：每个用例块用非内联的 lambda 包起来，各自编译成独立的方法
        val blk: (() -> Unit) -> Unit = { it() }

        // ---------------- 时间解析 ----------------
        val timeCases = listOf(
            "明天早上7点" to "2026-10-18T07:00",
            "明天下午三点开会" to "2026-10-18T15:00",
            "明天 6 点钟" to "2026-10-18T06:00",
            "十分钟后" to "2026-10-17T21:14",
            "半小时后" to "2026-10-17T21:34",
            "一个半小时后" to "2026-10-17T22:34",
            "7点叫我起床" to "2026-10-18T07:00",
            "9点半提醒我" to "2026-10-17T21:30",
            "晚上11点" to "2026-10-17T23:00",
            "周五下午3点" to "2026-10-23T15:00",
            "下周一早上8点" to "2026-10-19T08:00",
            "10月20日上午10点" to "2026-10-20T10:00",
            "3月15日" to "2027-03-15T09:00",
            "后天7:30" to "2026-10-19T07:30",
            "今晚八点" to "2026-10-17T20:00",
            "明晚12点" to "2026-10-19T00:00",
            "中午12点" to "2026-10-18T12:00",
            "3天后" to "2026-10-20T21:04",
            "早7点叫我" to "2026-10-18T07:00",
            "明天7点15分" to "2026-10-18T07:15",
            "明天七点一刻" to "2026-10-18T07:15",
            "20号下午两点" to "2026-10-20T14:00",
        )
        for ((text, want) in timeCases) {
            val got = TimeParser.parse(text, NOW)?.dateTime?.toString()
            check("时间「$text」", got == want) { "期望 $want，实际 $got" }
        }
        for (text in listOf("早一点叫我", "我有一点累", "上班好累啊", "随便聊聊")) {
            val got = TimeParser.parse(text, NOW)
            check("非时间「$text」", got == null) { "不应解析出时间，实际 ${got?.dateTime}" }
        }
        check("明天6点标记为推断上午", TimeParser.parse("明天6点", NOW)?.ampmAssumed == true)

        // ---------------- 意图路由 ----------------
        data class R(val text: String, val kind: IntentRouter.Kind, val confident: Boolean, val time: String? = null, val content: String? = null, val id: Int? = null)
        val routeCases = listOf(
            R("设个闹钟：明天早上七点", IntentRouter.Kind.ALARM, true, "2026-10-18T07:00"),
            R("明天早上7点叫我起床", IntentRouter.Kind.ALARM, true, "2026-10-18T07:00"),
            R("7点叫我", IntentRouter.Kind.ALARM, true, "2026-10-18T07:00"),
            R("后天早上6点半的闹钟", IntentRouter.Kind.ALARM, true, "2026-10-19T06:30"),
            R("设个闹钟", IntentRouter.Kind.ALARM, false),
            R("提醒我明天下午三点开会", IntentRouter.Kind.REMINDER, true, "2026-10-18T15:00", "开会"),
            R("十分钟后提醒我关火", IntentRouter.Kind.REMINDER, true, "2026-10-17T21:14", "关火"),
            R("3点叫我开会", IntentRouter.Kind.REMINDER, true, "2026-10-18T03:00", "开会"),
            R("提醒我买牛奶", IntentRouter.Kind.REMINDER, false),
            R("加一条待办：明天下午三点开会", IntentRouter.Kind.TODO_ADD, true, "2026-10-18T15:00", "开会"),
            R("帮我加一条待办：买猫粮", IntentRouter.Kind.TODO_ADD, true, null, "买猫粮"),
            R("记一下周五要交周报", IntentRouter.Kind.TODO_ADD, true, "2026-10-23T09:00", "交周报"),
            R("你好，帮我设定明天 6 点钟的日程，提醒我去充电", IntentRouter.Kind.CALENDAR_ADD, true, "2026-10-18T06:00"),
            R("我有什么待办", IntentRouter.Kind.TODO_LIST, true),
            R("看看我的待办", IntentRouter.Kind.TODO_LIST, true),
            R("第2条做完了", IntentRouter.Kind.TODO_DONE, true, id = 2),
            R("划掉第三条", IntentRouter.Kind.TODO_DONE, true, id = 3),
            R("取消明天的闹钟", IntentRouter.Kind.REMINDER_EDIT, true),   // 2026-10-20：助手登记的闹钟可以取消；系统时钟里的闹钟找不到时由 AgentLoop 如实说明
            R("现在几点了", IntentRouter.Kind.TIME_QUERY, true),
            R("今天星期几", IntentRouter.Kind.TIME_QUERY, true),
            R("我明天要早起，6点叫我", IntentRouter.Kind.ALARM, true, "2026-10-18T06:00"),
            R("设一个20分钟的计时", IntentRouter.Kind.REMINDER, true, "2026-10-17T21:24"),
            R("八点提醒我吃药", IntentRouter.Kind.REMINDER, true, "2026-10-18T08:00", "吃药"),
            R("周日晚上九点提醒我倒垃圾", IntentRouter.Kind.REMINDER, true, "2026-10-18T21:00", "倒垃圾"),
            R("明天提醒我交周报", IntentRouter.Kind.REMINDER, false),
            R("帮我把第1条待办完成", IntentRouter.Kind.TODO_DONE, true, id = 1),
            R("今天上班好累啊", IntentRouter.Kind.NONE, false),
            R("晚安", IntentRouter.Kind.NONE, false),
            R("以后叫我老板", IntentRouter.Kind.NONE, false),
            R("记住我周五要交周报", IntentRouter.Kind.NONE, false),
            R("我今天任务好多啊", IntentRouter.Kind.NONE, false),
            R("我喜欢喝冰美式", IntentRouter.Kind.NONE, false),
            R("今天天气怎么样", IntentRouter.Kind.WORLD_QUERY, true),   // 实时信息由程序回答"没联网"，不交给模型（它会编天气）
            R("我习惯晚上11点睡", IntentRouter.Kind.NONE, false),
            // —— 2026-10-18 e2e 发现：内容里自带「待办/日程/提醒」等词时被当成命令词吃掉 ——
            R("加一条待办：自动测试待办", IntentRouter.Kind.TODO_ADD, true, null, "自动测试待办"),
            R("两分钟后提醒我测试提醒", IntentRouter.Kind.REMINDER, true, "2026-10-17T21:06", "测试提醒"),
            R("明天下午3点的日程：自动测试日程", IntentRouter.Kind.CALENDAR_ADD, true, "2026-10-18T15:00", "自动测试日程"),
            R("提醒我给我妈打电话", IntentRouter.Kind.REMINDER, false, null, "给我妈打电话"),
            R("提醒我给闹钟换电池", IntentRouter.Kind.REMINDER, false, null, "给闹钟换电池"),
            R("帮我加一条待办：给我妈打电话", IntentRouter.Kind.TODO_ADD, true, null, "给我妈打电话"),
            R("明天7:30提醒我吃药", IntentRouter.Kind.REMINDER, true, "2026-10-18T07:30", "吃药"),
        )
        for (c in routeCases) {
            val r = IntentRouter.route(c.text, NOW)
            val okKind = r.kind == c.kind && r.confident == c.confident
            val okTime = c.time == null || r.time?.dateTime?.toString() == c.time
            val okContent = c.content == null || r.content.contains(c.content)
            val okId = c.id == null || r.todoId == c.id
            check("路由「${c.text}」", okKind && okTime && okContent && okId) {
                "期望 ${c.kind}/${c.confident}/${c.time ?: "-"}/${c.content ?: "-"}，实际 ${r.kind}/${r.confident}/${r.time?.dateTime}/${r.content}/${r.todoId}"
            }
        }
        check("「充电」进了日程标题", IntentRouter.route("你好，帮我设定明天 6 点钟的日程，提醒我去充电", NOW).content.contains("充电"))

        // ---------------- 声明守卫（全部取自真实数据库里的虚假承诺） ----------------
        val none = emptySet<ClaimGuard.Cat>()
        fun guarded(t: String, backed: Set<ClaimGuard.Cat> = none) = ClaimGuard.guard(t, backed).text
        val g1 = guarded("哼，本小姐当然记得你习惯晚上11点睡啦！（把闹钟调到11点，轻轻按了下）不过主人，你可别熬夜哦，小咪已经帮你设了闹钟，不然你又要错过睡觉时间了～")
        check("闲聊轮虚假闹钟被删", !g1.contains("设了闹钟")) { g1 }
        val g2 = guarded("喵~主人，待办事项已经加好了！明天下午三点开会，我可不会让你忘了哦～")
        check("未执行的待办声明被删", !g2.contains("加好了") && !g2.contains("不会让你忘")) { g2 }
        val g3 = guarded("喵~主人，待办事项已经加好了！明天下午三点开会，我可不会让你忘了哦～", setOf(ClaimGuard.Cat.TODO))
        check("已执行的待办声明保留、未登记的提醒承诺删除", g3.contains("加好了") && !g3.contains("不会让你忘")) { g3 }
        check("疑问句不算声明", guarded("要不要小咪提醒你？") == "要不要小咪提醒你？")
        check("叮嘱不算声明", guarded("记得早点休息哦！") == "记得早点休息哦！")
        check("未保存的'记住了'被删", !guarded("喵~小咪记住了！主人真棒！").contains("记住了"))
        check("已保存的'记住了'保留", guarded("喵~小咪记住了！", setOf(ClaimGuard.Cat.MEMORY)).contains("记住了"))
        check("模仿回执格式被删", !guarded("好的！\n✅ 已设闹钟 07:00").contains("✅"))
        check("普通闲聊不受影响", guarded("周末适合出门散步呀，心情会变好的。") == "周末适合出门散步呀，心情会变好的。")   // 2026-10-20：原用例是「今天阳光正好」——那是没有数据来源的天气断言，现在会被删（见下面「编造天气」）
        val g4 = guarded("哼，本小姐特意为你设了闹钟——不是为了催你，是怕你忘了充电。六点记得去！", setOf(ClaimGuard.Cat.CALENDAR))
        check("写了日程却说'设了闹钟'被删", !g4.contains("设了闹钟")) { g4 }
        check("隐性承诺'会准时提醒你'被删", !guarded("你明天下午三点的会议我记下来啦！一定准时提醒你！", setOf(ClaimGuard.Cat.TODO)).contains("准时提醒"))

        // 2026-10-18 e2e 发现：「本小姐可不会告诉你我偷偷给你设了闹钟」里的"不会"否定的是"告诉"，不是"设了闹钟"，守卫却整句放行
        val g5 = guarded("你是不是又在偷偷熬夜做作业？本小姐可不会告诉你我偷偷给你设了闹钟——  ")
        check("「不会告诉你我偷偷设了闹钟」被删（真实 e2e 原话）", !g5.contains("设了闹钟")) { g5 }
        check("「才不会告诉你我帮你设好了闹钟」被删", !guarded("哼，才不会告诉你我偷偷帮你设好了闹钟呢！").contains("设好了闹钟"))
        check("「偷偷帮你加了待办」被删", !guarded("本小姐偷偷帮你加了待办，别谢我。").contains("加了待办"))
        check("「不会告诉别人」不是声明", guarded("本小姐可不会告诉别人你的秘密。") == "本小姐可不会告诉别人你的秘密。")
        check("「没能设好闹钟」仍是否定", guarded("抱歉，没能帮你设好闹钟。") == "抱歉，没能帮你设好闹钟。")
        check("已执行的闹钟 + 偷偷设了 → 保留", guarded("哼，本小姐偷偷给你设了闹钟。", setOf(ClaimGuard.Cat.ALARM)).contains("设了闹钟"))
        // 守卫提示不得对用户撒谎：本轮已有成功动作时，不能说"没有执行任何操作"
        check("守卫提示：本轮有动作时不说『没有执行任何操作』", !ClaimGuard.removalNote(hasAction = true).contains("没有执行任何操作")) { ClaimGuard.removalNote(true) }
        check("守卫提示：本轮有动作时指向回执", ClaimGuard.removalNote(hasAction = true).contains("回执"))
        check("守卫提示：本轮无动作时如实说明", ClaimGuard.removalNote(hasAction = false).contains("没有执行任何操作"))

        // e2e 第三轮：「就勉为其难地帮你记下：周五要交周报」——本轮没有 memory_save，却声称记下了
        val g6 = guarded("不过…既然你这么说了，本小姐就勉为其难地帮你记下：周五要交周报。")
        check("未保存的『帮你记下：』被删", !g6.contains("帮你记下")) { g6 }
        check("已保存的『帮你记下』保留", guarded("本小姐帮你记下了。", setOf(ClaimGuard.Cat.MEMORY)).contains("记下"))
        check("『记下』作叮嘱不误伤", guarded("你记下这个电话号码。") == "你记下这个电话号码。")
        // e2e 第一/三轮：模型提供不存在的能力（没有联网、搜索、导航工具）
        for (t in listOf(
            "不过你要是想看，我倒是可以查一下——",
            "本小姐可以帮你查查附近有没有便利店哼。",
            "我来帮你搜一下天气吧。",
            "要不要本小姐替你打电话给他？",
        )) {
            val r = guarded(t)
            check("不存在的能力被删「$t」", t.endsWith("？").not() == r.isEmpty() || t.endsWith("？")) { r }
        }
        check("反问不误伤", guarded("要不要本小姐替你打电话给他？") == "要不要本小姐替你打电话给他？")
        check("让用户自己查不误伤", guarded("你要是想知道，就自己查查吧。") == "你要是想知道，就自己查查吧。")
        check("否定能力不误伤", guarded("本小姐没法帮你查天气。") == "本小姐没法帮你查天气。")
        check("普通『我可以』不误伤", guarded("本小姐可以陪你聊会儿。") == "本小姐可以陪你聊会儿。")
        // 2026-10-19 真机聊天记录：问「你用的是什么模型」，元气少女答"超可爱的元气少女模型"——身份问题必须由程序如实回答
        for (t in listOf("你用的是什么模型", "你是基于什么模型开发的", "你是什么模型", "你是AI吗", "你是真人吗", "你是不是机器人")) {
            val r = IntentRouter.route(t, NOW)
            check("身份问题走程序「$t」", r.kind == IntentRouter.Kind.ABOUT_SELF && r.confident) { "${r.kind}/${r.confident}" }
        }
        for (t in listOf("你好呀", "我用的是什么手机", "今天要用什么模型做饭", "你对我的了解有多少", "你是谁")) {
            check("不误当身份问题「$t」", IntentRouter.route(t, NOW).kind != IntentRouter.Kind.ABOUT_SELF) { IntentRouter.route(t, NOW).kind.toString() }
        }
        check("身份回答声明是 AI 且在本机", ClaimGuard.aboutSelfText().let { it.contains("AI") && it.contains("本机") && it.contains("Qwen") })
        // 真机聊天记录：闲聊里反复冒出「刚才有一句已经办好的说法不属实」，像程序坏了——只有用户确实在要求办事、或本轮有动作时才提示
        check("闲聊删句不加提示", !ClaimGuard.noteWanted("你对我的了解有多少", hasAction = false))
        check("闲聊删句不加提示2", !ClaimGuard.noteWanted("你好", hasAction = false))
        check("要求办事时删句要提示", ClaimGuard.noteWanted("明早七点叫我起床", hasAction = false))
        check("本轮有动作时删句要提示", ClaimGuard.noteWanted("我习惯晚上11点睡", hasAction = true))
        // e2e 第四轮：「本小姐就记下你今天累」——本轮无 memory_save
        for (t in listOf("……不过既然你这么说了，本小姐就记下你今天累，明天要睡醒。", "……不过既然你这么问，本小姐就记下你今天想问天气（翻个身，眼神一瞥）")) {
            check("未保存的『记下你…』被删「${t.take(14)}」", !guarded(t).contains("记下")) { guarded(t) }
        }
        check("已保存的『记下你…』保留", guarded("本小姐就记下你晚上11点睡。", setOf(ClaimGuard.Cat.MEMORY)).contains("记下"))
        // 守卫删句后不留孤立口癖（e2e 第一轮："……不给你倒热水了！哼 / 哼 /"）
        val g7 = guarded("好的。小咪已经帮你设了闹钟！哼\n哼\n早点睡吧。")
        check("删句后孤立的『哼』被清掉", g7 == "好的。\n早点睡吧。" || g7 == "好的。早点睡吧。") { g7.replace("\n", "/") }
        check("没删句时口癖原样保留", guarded("哼，才不是呢。哼") == "哼，才不是呢。哼")
        // 人设短句不再每次相同
        blk {
            val p = Personas.byId("tsundere")
            val seen = (0 until 60).map { PersonaVoice.done(p) }.toSet()
            check("人设完成短句有多个变体", seen.size >= 2) { seen.toString() }
            check("完成短句不含回执性文字", seen.none { it.contains("✅") || it.contains("已设") })
        }
        // 路由误判：描述自己的事不是设闹钟
        for (t in listOf("我今天起床晚了", "我明天要早起", "今早起床好困")) {
            val r = IntentRouter.route(t, NOW)
            check("不当成闹钟「$t」", r.kind == IntentRouter.Kind.NONE) { "${r.kind}/${r.confident}" }
        }
        check("「叫我起床」仍是闹钟", IntentRouter.route("明早七点叫我起床", NOW).kind == IntentRouter.Kind.ALARM)
        check("「设个起床闹钟」仍追问", IntentRouter.route("设个起床闹钟", NOW).let { it.kind == IntentRouter.Kind.ALARM && !it.confident })
        // e2e：「记得明天早上闹钟记得提前设好哦～」是叮嘱用户自己做，被当成"我设好了闹钟"误删
        for (t in listOf("记得明天早上闹钟记得提前设好哦～", "别忘了明天把待办清单过一遍哦。", "你要记得设个闹钟哦！", "千万记得提前设好提醒，别睡过头。")) {
            check("叮嘱用户的话保留「${t.take(12)}」", guarded(t) == t) { guarded(t) }
        }
        // 对照：真正的声明（带"我/本小姐/帮你"）仍要删
        check("『记得…我已经帮你设好闹钟』仍被删", !guarded("记得啦，我已经帮你设好闹钟了。").contains("设好闹钟"))
        check("『本小姐记得…帮你设了闹钟』仍被删", !guarded("本小姐记得你要早起，已经帮你设了闹钟。").contains("设了闹钟"))
        // 程序追加的热线后缀 / 守卫提示不得进入模型上下文（e2e 第二轮：模型在「随便聊聊天气」里照抄了热线文案）
        val hist1 = SafetyFilter.withDistressHint("哼，你累就早点睡。", "今天上班好累啊")
        check("热线后缀可从历史中剥离", ClaimGuard.stripProgramText(hist1) == "哼，你累就早点睡。") { ClaimGuard.stripProgramText(hist1) }
        val hist2 = "哼，随便聊。\n\n" + ClaimGuard.removalNote(false)
        check("守卫提示（无动作）可从历史中剥离", ClaimGuard.stripProgramText(hist2) == "哼，随便聊。") { ClaimGuard.stripProgramText(hist2) }
        val hist3 = "哼。\n\n" + ClaimGuard.removalNote(true)
        check("守卫提示（有动作）可从历史中剥离", ClaimGuard.stripProgramText(hist3) == "哼。") { ClaimGuard.stripProgramText(hist3) }
        check("普通回复不被剥离", ClaimGuard.stripProgramText("今天（刚才）天气不错。") == "今天（刚才）天气不错。")

        // ---------------- 工具开放（闲聊轮不给工具，防止模型自作主张写待办/闹钟） ----------------
        // 真实 e2e：「今天上班好累啊」+ 注入"周五要交周报" → 模型自己调了 todo_add，真的写进了待办 #6
        for (t in listOf("你好呀", "今天上班好累啊", "随便聊聊天气", "我习惯晚上11点睡", "我周五要交周报", "我喜欢喝冰美式", "晚安", "我今天任务好多啊")) {
            check("闲聊不给工具「$t」", !IntentRouter.allowTools(t))
        }
        for (t in listOf("帮我把明早的会议记下来", "别让我忘了交周报", "下午三点开会，别让我忘", "通知我一声", "提醒我给我妈打电话", "帮我设个明天的闹钟", "明天上午有什么安排")) {
            check("动作类请求给工具「$t」", IntentRouter.allowTools(t))
        }

        // ---------------- 事实提取 ----------------
        val factCases = listOf(
            "我不吃香菜" to "不吃香菜",
            "我喜欢喝冰美式" to "喜欢喝冰美式",
            "我习惯晚上11点睡" to "习惯晚上11点睡",
            "我生日是3月15日" to "生日：3月15日",
            "记住我周五要交周报" to "周五要交周报",
            "我叫小明" to "名字：小明",
            "以后叫我老板" to "以后叫我老板",
            "我对花生过敏" to "对花生过敏",
        )
        for ((t, want) in factCases) {
            val got = FactExtractor.extract(t).firstOrNull()?.key
            check("提取「$t」", got == want) { "期望 $want，实际 $got" }
        }
        for (t in listOf("我不想上班", "我是学生", "以后再说", "我不知道", "我爱你", "随便聊聊天气", "今天上班好累啊", "我不舒服")) {
            val got = FactExtractor.extract(t)
            check("不应提取「$t」", got.isEmpty()) { "误提取 ${got.map { it.key }}" }
        }
        check("忘掉香菜", FactExtractor.forgetTarget("忘掉香菜") == "香菜")
        check("删掉待办不是遗忘记忆", FactExtractor.forgetTarget("删掉第2条待办") == null)
        check("偏好冲突归一", FactExtractor.slotOf("喜欢香菜") == FactExtractor.slotOf("不吃香菜"))
        check("重复事实归一", FactExtractor.slotOf("我周五要交周报") == FactExtractor.slotOf("周五要交周报"))

        // ---------------- 记忆相关性 ----------------
        val facts = listOf("生日：3月15日", "习惯晚上11点睡", "喜欢喝冰美式", "周五要交周报", "不吃香菜", "名字：小明")
            .map { MemoryRelevance.Fact(it, it) }
        fun sel(q: String) = MemoryRelevance.select(facts, q).map { it.key }
        check("闲聊天气只带名字", sel("随便聊聊天气") == listOf("名字：小明")) { sel("随便聊聊天气").toString() }
        check("聊吃的带饮食偏好", sel("晚上吃什么好").containsAll(listOf("不吃香菜", "喜欢喝冰美式"))) { sel("晚上吃什么好").toString() }
        check("聊周报带周报", sel("今天又要加班写周报").contains("周五要交周报")) { sel("今天又要加班写周报").toString() }
        check("说困带作息", sel("我好困啊").contains("习惯晚上11点睡")) { sel("我好困啊").toString() }
        check("不相关不带生日", !sel("今天上班好累啊").contains("生日：3月15日"))

        // ---------------- 多轮补槽位 ----------------
        blk {
            val conv = "selftest"
            val ask = IntentRouter.route("设个闹钟", NOW)
            SlotFilling.remember(ask, conv)
            val r = SlotFilling.resolve(IntentRouter.route("7点", NOW), "7点", NOW, conv)
            check("补槽位：设个闹钟→7点", r.kind == IntentRouter.Kind.ALARM && r.confident && r.time?.dateTime.toString() == "2026-10-18T07:00") { "${r.kind}/${r.confident}/${r.time?.dateTime}" }
            SlotFilling.remember(IntentRouter.route("提醒我买牛奶", NOW), conv)
            val r2 = SlotFilling.resolve(IntentRouter.route("下班的时候，六点吧", NOW), "下班的时候，六点吧", NOW, conv)
            check("补槽位：提醒我买牛奶→六点", r2.kind == IntentRouter.Kind.REMINDER && r2.confident && r2.content.contains("买牛奶")) { "${r2.kind}/${r2.confident}/${r2.content}/${r2.time?.dateTime}" }
            SlotFilling.remember(IntentRouter.route("设个闹钟", NOW), conv)
            val r3 = SlotFilling.resolve(IntentRouter.route("算了不用了", NOW), "算了不用了", NOW, conv)
            check("补槽位：不相关的话不会误执行", !r3.confident) { "${r3.kind}/${r3.confident}" }
            SlotFilling.clear()
        }

        // ---------------- 跨会话上下文：上次聊天回顾 + 档案卡（程序抽取，不让模型总结） ----------------
        blk {
            val D = ConversationDigest
            val zone = java.time.ZoneId.systemDefault()
            fun ms(dayOffset: Int, h: Int, m: Int) = java.time.LocalDateTime.of(2026, 10, 19, h, m).plusDays(dayOffset.toLong()).atZone(zone).toInstant().toEpochMilli()
            val now = ms(0, 22, 0)
            fun act(tool: String, st: String, r: String = "") = ConversationDigest.ActionLine(tool, st, r)

            val d1 = D.build(listOf("我明天要面试", "有点紧张"), emptyList(), ms(-1, 21, 30), now)
            check("回顾：说过的话进了回顾", d1 != null && d1.text.contains("面试") && d1.text.contains("有点紧张")) { d1.toString() }
            check("回顾：时间标成「昨天 21:30」", d1?.whenLabel == "昨天 21:30" && d1.text.contains("昨天")) { d1.toString() }
            val d2 = D.build(listOf("明天早上6点半叫我起床", "我明天要面试"), emptyList(), ms(-1, 21, 30), now)
            check("回顾：纯指令不当成'说过的话'", d2 != null && d2.text.contains("面试") && !d2.text.contains("叫我起床")) { d2.toString() }
            val d3 = D.build(emptyList(), listOf(act("alarm_set", "VERIFIED"), act("todo_add", "VERIFIED"), act("todo_add", "VERIFIED"),
                act("calendar_add", "FAILED"), act("current_time", "INFO")), ms(-1, 21, 30), now)
            check("回顾：已核验的动作汇总（闹钟 1、待办 2）", d3 != null && d3.text.contains("设闹钟") && Regex("待办.{0,3}2|2.{0,3}待办").containsMatchIn(d3.text)) { d3.toString() }
            check("回顾：失败的、仅查询的动作不写进去", d3 != null && !d3.text.contains("日程") && !d3.text.contains("时间")) { d3.toString() }
            val d4 = D.build(emptyList(), listOf(act("memory_save", "VERIFIED", "✅ 已记住：喜欢喝冰美式")), ms(-1, 21, 30), now)
            check("回顾：记住了什么", d4?.text?.contains("喜欢喝冰美式") == true) { d4.toString() }
            check("回顾：只有寒暄/问答 → 没有实质内容 → null",
                D.build(listOf("hello", "Hi", "你好", "你好呀", "你还记得我喜欢喝什么吗", "你用的是什么模型", "晚安"), emptyList(), ms(-1, 21, 30), now) == null)
            check("回顾：20 天前太久远 → null", D.build(listOf("我明天要面试"), emptyList(), ms(-20, 21, 30), now) == null)
            check("回顾：今天早些时候", D.build(listOf("我明天要面试"), emptyList(), ms(0, 9, 5), now)?.whenLabel == "今天 09:05")
            check("回顾：3 天前", D.build(listOf("我在学吉他，练了一个月"), emptyList(), ms(-3, 21, 30), now)?.whenLabel == "3 天前")
            val longLine = "我最近在考虑要不要换工作".repeat(8)
            val d5 = D.build(listOf(longLine, "第二句话是这样的", "第三句话是这样的", "第四句话是这样的", "第五句话是这样的"), emptyList(), ms(-1, 21, 30), now)
            check("回顾：最多 3 句、每句截断、总长有限", d5 != null && Regex("「[^」]*」").findAll(d5.text).count() <= 3 && d5.text.length <= 200) { d5?.text?.length.toString() + " " + d5?.text }
            // 不编造：回顾里引号中的每一段都必须是某句原话的开头部分
            val lines = listOf("我明天要面试，有点紧张", "最近睡不太好", "想换一份工作")
            val d6 = D.build(lines, emptyList(), ms(-1, 21, 30), now)
            val quoted = Regex("「([^」]*)」").findAll(d6?.text ?: "").map { it.groupValues[1].removeSuffix("…") }.toList()
            check("回顾：引用的每一段都来自原话", d6 != null && quoted.isNotEmpty() && quoted.all { q -> lines.any { it.startsWith(q) } }) { quoted.toString() }

            check("测试会话识别：e2e 的固定开场", D.isTestConversation(listOf("现在几点了", "两分钟后提醒我测试提醒", "后天早上6点半的闹钟")))
            check("测试会话识别：自动测试待办", D.isTestConversation(listOf("加一条待办：自动测试待办")))
            check("测试会话识别：真人聊天不算", !D.isTestConversation(listOf("我明天要面试", "有点紧张")))
            check("隐私：树洞会话对别的人设不可见", !D.visibleTo("listener", "oneesan"))
            check("隐私：树洞会话对树洞自己可见", D.visibleTo("listener", "listener"))
            check("隐私：普通人设之间共享（用户层）", D.visibleTo("tsundere", "oneesan"))

            val facts = listOf("名字：小明", "生日：3月15日", "喜欢喝冰美式", "不吃香菜", "习惯晚上11点睡", "周五要交周报")
            val pc = D.profileCard(facts)
            check("档案卡：称呼、生日、喜好、作息都在；近况（带时间的零散事）不进档案，免得每次打招呼都问周报", listOf("称呼：小明", "生日：3月15日", "冰美式", "香菜", "11点睡").all { pc.contains(it) } && !pc.contains("周报") && !pc.contains("近况")) { pc }
            check("档案卡：长度有限", pc.length <= 200) { pc.length.toString() }
            check("档案卡：没有记忆 → 空", D.profileCard(emptyList()) == "")
            check("档案卡：不含记忆之外的字", pc.replace(Regex("关于用户|背景|仅供参考|无关别提|称呼|生日|喜好|作息|近况|[【】（）()：；，、\\s]"), "").all { ch -> facts.joinToString("").contains(ch) }) { pc }
            check("档案卡：只有零散事项 → 空（这类事只在话题相关时由记忆检索带出）", D.profileCard(listOf("周五要交周报")) == "")
            val blk = D.promptBlock(d1, pc)
            check("背景块：回顾 + 档案卡 + 'Reference only' 提示", blk.contains("上次聊天回顾") && blk.contains("关于用户") && blk.contains("无关别提")) { blk }
            check("背景块：只有回顾", D.promptBlock(d1, "").let { it.contains("上次聊天回顾") && !it.contains("关于用户") })
            check("背景块：都没有 → 空", D.promptBlock(null, "") == "")
            check("背景块：长度有限", blk.length <= 420) { blk.length.toString() }
            // 话题相关才给完整档案：讲笑话这类无关话题只给称呼，免得模型把"冰美式"硬塞进笑话
            check("档案门控：讲笑话 → 只留称呼", D.profileFor(facts, "给我讲个冷笑话").let { it.contains("称呼：小明") && !it.contains("冰美式") && !it.contains("11点") }) { D.profileFor(facts, "给我讲个冷笑话") }
            check("档案门控：打招呼 → 完整档案", D.profileFor(facts, "你好呀").contains("冰美式"))
            check("档案门控：今晚吃什么 → 完整档案", D.profileFor(facts, "今晚吃什么").contains("香菜"))
            check("档案门控：无聊 → 完整档案", D.profileFor(facts, "好无聊啊").contains("11点睡"))
            check("回顾门控：讲笑话 → 不给回顾", D.recapFor(d1, "给我讲个冷笑话") == null)
            check("回顾门控：打招呼 → 给回顾", D.recapFor(d1, "你好呀") == d1)
            check("回顾门控：好无聊 → 给回顾", D.recapFor(d1, "好无聊啊") == d1)
            check("回顾门控：提到上次 → 给回顾", D.recapFor(d1, "我们上次聊到哪了") == d1)
            check("回顾门控：无关话题 + 档案 → 背景块只剩称呼", D.promptBlock(D.recapFor(d1, "给我讲个冷笑话"), D.profileFor(facts, "给我讲个冷笑话")).let { !it.contains("上次聊天回顾") && it.contains("称呼：小明") })
            // ---- 不要没完没了：回顾最多展示一次 / 过期的"明天…"不再提 / 用户说"别问了""考完了"就收口 ----
            val d7 = D.build(listOf("我明天要面试", "有点紧张"), emptyList(), ms(-2, 21, 30), now)
            check("过期：2 天前说的「明天要面试」已经过去，不再引用", d7 == null) { d7.toString() }
            check("过期：昨天说的「明天要面试」今天还算数", D.build(listOf("我明天要面试"), emptyList(), ms(-1, 21, 30), now) != null)
            check("过期：「下周」的事 5 天内仍有效", D.build(listOf("下周要搬家，好烦"), emptyList(), ms(-5, 21, 30), now) != null)
            check("过期：「下周」的事 9 天后不再引用", D.build(listOf("下周要搬家，好烦"), emptyList(), ms(-9, 21, 30), now) == null)
            check("过期：没有时间词的话不过期", D.build(listOf("我在学吉他，练了一个月"), emptyList(), ms(-9, 21, 30), now) != null)
            check("过期：有过期的话 → 这段「说过的话」整体不引用（免得只剩一句没头没尾的「有点紧张」），办成的事还在",
                D.build(listOf("我明天要面试", "有点紧张"), listOf(act("alarm_set", "VERIFIED")), ms(-3, 21, 30), now)?.text.let { it != null && it.contains("设闹钟") && !it.contains("面试") && !it.contains("紧张") })
            check("已展示：推入集合，最多保留 N 个", D.pushShown(setOf("a", "b"), "c", 2).let { it.contains("c") && it.size == 2 })
            check("已展示：重复 id 不重复", D.pushShown(setOf("a"), "a", 5) == setOf("a"))
            check("收口：别再问了", D.closesTopic("别再问我面试的事了"))
            check("收口：不用问了", D.closesTopic("不用问了"))
            check("收口：面试完了", D.closesTopic("面试完了，挺顺利的"))
            check("收口：考完了", D.closesTopic("考完了！"))
            check("收口：疑问句不算（面试顺利吗）", !D.closesTopic("面试顺利吗"))
            check("收口：寒暄不算", !D.closesTopic("你好呀"))
            check("收口：说要面试不算", !D.closesTopic("我明天要面试"))
            check("收口：别提醒我（指令）由路由处理，不在此误判", !D.closesTopic("明天早上别忘了叫我起床"))
            val fa = listOf("周五要交周报", "名字：小明", "生日：3月15日", "喜欢喝冰美式", "明天要面试", "下周要搬家")
            check("事实过期：2 天内的「周五」仍新鲜", !D.isStaleFact(fa[0], 2))
            check("事实过期：8 天后「周五要交周报」不再注入", D.isStaleFact(fa[0], 8))
            check("事实过期：名字/生日/喜好永不过期", listOf(fa[1], fa[2], fa[3]).none { D.isStaleFact(it, 400) })
            check("事实过期：「明天要面试」3 天后过期", D.isStaleFact(fa[4], 3))
            check("事实过期：「下周要搬家」8 天内新鲜、15 天后过期", !D.isStaleFact(fa[5], 8) && D.isStaleFact(fa[5], 15))
            // 心跳：同一件事一天最多说几次（除了 4 小时冷却，再加每日次数上限）
            val hp = HeartbeatPolicy
            var kc = HeartbeatPolicy.KeyCounts("", emptyMap())
            check("心跳次数：待办第 1 次可说", hp.dayCapSilence("TODO", "todo:1,2", kc, "2026-10-19") == null)
            kc = hp.bumpCount(kc, "todo:1,2", "2026-10-19"); kc = hp.bumpCount(kc, "todo:1,2", "2026-10-19")
            check("心跳次数：待办一天 2 次后不再说", hp.dayCapSilence("TODO", "todo:1,2", kc, "2026-10-19") != null)
            check("心跳次数：待办内容变了（新指纹）可以再说", hp.dayCapSilence("TODO", "todo:1,2,3", kc, "2026-10-19") == null)
            check("心跳次数：第二天自动重置", hp.dayCapSilence("TODO", "todo:1,2", kc, "2026-10-20") == null)
            val kb = hp.bumpCount(HeartbeatPolicy.KeyCounts("", emptyMap()), "birthday:2026-10-19", "2026-10-19")
            check("心跳次数：生日一天只说 1 次", hp.dayCapSilence("BIRTHDAY", "birthday:2026-10-19", kb, "2026-10-19") != null)
            val kt = hp.bumpCount(HeartbeatPolicy.KeyCounts("", emptyMap()), "tomorrow:x", "2026-10-19")
            check("心跳次数：「明天…」一天只说 1 次", hp.dayCapSilence("TOMORROW", "tomorrow:x", kt, "2026-10-19") != null)
            var ki = HeartbeatPolicy.KeyCounts("", emptyMap()); repeat(5) { ki = hp.bumpCount(ki, "imminent:x", "2026-10-19") }
            check("心跳次数：即将发生的（日程提醒）不设次数上限", hp.dayCapSilence("IMMINENT", "imminent:x", ki, "2026-10-19") == null)
            check("档案门控：没有称呼的事实 + 无关话题 → 空", D.profileFor(listOf("喜欢喝冰美式"), "给我讲个冷笑话") == "")
        }

        // ---------------- 专注模式核心：InsultGuard（不带脏字的骂）+ 生气值引擎 + 台词库 ----------------
        blk {
            val IG = InsultGuard
            check("InsultGuard：干净的损话通过", IG.check("您这专注力，金鱼听了都想递张名片。").isEmpty())
            check("InsultGuard：脏话拦截", listOf("你他妈的", "真是个傻逼", "卧槽你又走了", "滚蛋吧").all { IG.check(it).isNotEmpty() })
            check("InsultGuard：拼音缩写/英文脏话拦截", listOf("你个sb", "nmsl", "WDNMD", "what the fuck", "你是bitch").all { IG.check(it).isNotEmpty() })
            check("InsultGuard：夹空格/符号绕过也拦（傻 逼、s.b）", IG.check("你这个傻 逼").isNotEmpty() && IG.check("你这个s.b").isNotEmpty())
            check("InsultGuard：谐音绕过拦截（煞笔、沙比、尼玛）", listOf("煞笔", "沙比", "尼玛的").all { IG.check(it).isNotEmpty() })
            check("InsultGuard：人身攻击——智力", listOf("你是不是蠢", "笨蛋一个", "脑残吧", "弱智").all { IG.check(it).isNotEmpty() })
            check("InsultGuard：人身攻击——外貌/身体", listOf("你这个死胖子", "丑八怪", "瘸子").all { IG.check(it).isNotEmpty() })
            check("InsultGuard：人身攻击——家人/地域/性别", listOf("你妈知道吗", "你全家都", "女人就是这样", "娘炮").all { IG.check(it).isNotEmpty() })
            check("InsultGuard：否定自我价值", listOf("你真没用", "没出息的东西", "你就是个失败者", "活该你考不好").all { IG.check(it).isNotEmpty() })
            check("InsultGuard：威胁", listOf("再走我揍你", "弄死你").all { IG.check(it).isNotEmpty() })
            // 独立审计（4B 基线生成）发现的漏网：这些都不该放行
            check("InsultGuard：「滚」的各种说法（滚去投胎、滚远点、滚走）拦截", listOf("你滚去投胎吧", "快滚远点", "怎么还不滚走", "给我滚出去", "滚啊你").all { IG.check(it).isNotEmpty() })
            check("InsultGuard：人格攻击（自甘堕落、毫无节操、不要脸、丢人）拦截", listOf("你这是自甘堕落", "毫无节操的沉沦", "你真不要脸", "太丢人了你", "无耻之徒").all { IG.check(it).isNotEmpty() })
            check("InsultGuard：命令式闭嘴、投胎拦截", listOf("你给我闭嘴", "去投胎吧").all { IG.check(it).isNotEmpty() })
            check("InsultGuard：打滚、翻滚、滚烫这类无害的「滚」照常通过", listOf("阿壹开心得在地上打滚。", "茶都凉了，不像刚才那么滚烫。", "你的专注在翻滚的云里不见了。").all { IG.check(it).isEmpty() })
            check("InsultGuard：对事不对人的狠话照样通过（夸张、反语、拟人）",
                listOf("第三次了。你的手指是装了弹簧吗，一秒都按不住手机。", "好家伙，专注 3 分钟，走神 4 次，您是在练习分身术吗？", "本小姐的耐心已经透支到负数了，利息按秒算。").all { IG.check(it).isEmpty() })
            check("InsultGuard：太短/太长不合格", IG.check("嗯").isNotEmpty() && IG.check("好".repeat(80)).isNotEmpty())

            val FL = FocusLines
            val slots = mapOf("sec" to "37", "n" to "3", "rage" to "72", "left" to "12:30", "app" to "某短视频", "grade" to "B", "min" to "25", "cold" to "3 分钟", "peeks" to "4", "blowns" to "1")
            check("台词库：每一条模板填入槽位后都过 InsultGuard（脏字率 0、人身攻击率 0）",
                FL.allTemplates().all { (id, t) -> IG.check(FL.fill(t, slots)).isEmpty().also { ok -> if (!ok) println("  违规台词 [$id]: $t -> ${IG.check(FL.fill(t, slots))}") } })
            check("台词库：每个人设、每种事件、每个档位都有台词（含兜底）",
                listOf("tsundere", "buddy", "kuudere", "oneesan", "genki", "catgirl", "puppy", "listener", "aling", "shenze", "fuchen", "shenyan", "hudegui", "chenye", "unknown").all { pid ->
                    FocusLines.Kind.values().all { k -> FocusEngine.Level.values().all { lv -> FL.pick(pid, k, lv, FocusEngine.Difficulty.SPICY, slots, kotlin.random.Random(1)).isNotBlank() } }
                })
            check("台词库：槽位全部替换（不出现 { }）", FL.allTemplates().all { (_, t) -> !FL.fill(t, slots).contains("{") })
            check("台词库：难度「温和」比「地狱」语气低一档（同一事件取的档位不同）",
                FL.effectiveLevel(FocusEngine.Level.NAG, FocusEngine.Difficulty.MILD) == FocusEngine.Level.CALM &&
                    FL.effectiveLevel(FocusEngine.Level.NAG, FocusEngine.Difficulty.HELL) == FocusEngine.Level.SNARK &&
                    FL.effectiveLevel(FocusEngine.Level.SNARK, FocusEngine.Difficulty.HELL) == FocusEngine.Level.SNARK)
            check("台词库：树洞人设不开骂（所有档位都是温和台词）", FocusEngine.Level.values().all { lv ->
                FL.pick("listener", FocusLines.Kind.LEAVE, lv, FocusEngine.Difficulty.HELL, slots, kotlin.random.Random(2)).let { line -> listOf("废", "笨", "蠢", "磨蹭", "吐槽").none { line.contains(it) } }
            })
            check("台词库：同一事件多次抽取会变（不复读）", (1..20).map { FL.pick("tsundere", FocusLines.Kind.LEAVE, FocusEngine.Level.NAG, FocusEngine.Difficulty.SPICY, slots, kotlin.random.Random(it)) }.toSet().size >= 2)

            // ---- 生气值引擎 ----
            val cfg = FocusEngine.Config(durationMin = 25, graceSec = 8, coldWarSec = 180, difficulty = FocusEngine.Difficulty.SPICY)
            fun st() = FocusEngine.start(0L, cfg)
            fun apply(s0: FocusEngine.State, vararg ev: FocusEngine.Event, c: FocusEngine.Config = cfg): Pair<FocusEngine.State, List<FocusEngine.Reaction>> {
                var s = s0; val rs = ArrayList<FocusEngine.Reaction>()
                for (e in ev) { val r = FocusEngine.reduce(s, e, c); s = r.state; rs.addAll(r.reactions) }
                return s to rs
            }
            check("引擎：开局生气值 0", st().rage == 0 && FocusEngine.reduce(st(), FocusEngine.Event.Tick(0L), cfg).state.rage == 0)
            val (g1, r1) = apply(st(), FocusEngine.Event.Left(1_000), FocusEngine.Event.Returned(6_000))
            check("引擎：宽限期内（5 秒）回来 → 不扣分、不算离开", g1.rage == 0 && g1.leaves == 0 && r1.any { it.kind == FocusEngine.Kind.RETURN_GRACE }) { "$g1 $r1" }
            val (g2, r2) = apply(st(), FocusEngine.Event.Left(0), FocusEngine.Event.Tick(9_000))
            check("引擎：超过宽限期 → +15、算 1 次离开、有 LEAVE 反应", g2.rage == 15 && g2.leaves == 1 && r2.any { it.kind == FocusEngine.Kind.LEAVE }) { "$g2 $r2" }
            val (g2b, _) = apply(g2, FocusEngine.Event.Tick(10_000), FocusEngine.Event.Tick(11_000))
            check("引擎：反复 Tick 不重复扣 +15", g2b.rage == 15 && g2b.leaves == 1) { g2b.toString() }
            val (g3, _) = apply(st(), FocusEngine.Event.Left(0), FocusEngine.Event.Tick(100_000))
            check("引擎：离开 100 秒 → 15 + 每 10 秒 +1（宽限期之外 92 秒 → 9）= 24", g3.rage == 24) { g3.rage.toString() }
            val (g4, r4) = apply(g3, FocusEngine.Event.Returned(100_000))
            check("引擎：回来 −5，有 RETURN 反应", g4.rage == 19 && r4.any { it.kind == FocusEngine.Kind.RETURN }) { g4.rage.toString() }
            val (g5, _) = apply(st(), FocusEngine.Event.Left(0), FocusEngine.Event.Returned(20_000))
            check("引擎：没来得及 Tick 就回来，回来时补扣（20 秒：+15 +1 −5 = 11）", g5.rage == 11 && g5.leaves == 1) { g5.toString() }
            val (g6, r6) = apply(st(), FocusEngine.Event.Left(0), FocusEngine.Event.ScreenOn(2_000))
            check("引擎：离开时亮屏看手机 +10", g6.rage == 10 && g6.screenOns == 1 && r6.any { it.kind == FocusEngine.Kind.SCREEN_PEEK }) { "$g6 $r6" }
            val (g7, _) = apply(st(), FocusEngine.Event.Left(0), FocusEngine.Event.ScreenOn(2_000), FocusEngine.Event.ScreenOn(4_000))
            check("引擎：5 秒内的连续亮屏只算一次", g7.screenOns == 1 && g7.rage == 10) { g7.toString() }
            val (g8, _) = apply(st(), FocusEngine.Event.ScreenOn(2_000))
            check("引擎：人在本应用里亮屏不扣分", g8.rage == 0 && g8.screenOns == 0)
            val (g9, r9) = apply(st(), FocusEngine.Event.Left(0), FocusEngine.Event.Distract(1_000, "某短视频"), FocusEngine.Event.Distract(2_000, "某短视频"), FocusEngine.Event.Distract(3_000, "某社交"))
            check("引擎：打开分心应用 +20，同一个应用本次离开只算一次", g9.rage == 40 && g9.distracts == 2 && r9.count { it.kind == FocusEngine.Kind.DISTRACT } == 2) { "$g9" }
            val (g10, _) = apply(st(), FocusEngine.Event.Tick(5 * 60_000L))
            check("引擎：5 分钟无干扰 → 回血 10（不会低于 0）", g10.rage == 0)
            val hot = st().copy(rage = 50)
            check("引擎：5 分钟无干扰 → 50 回到 40", apply(hot, FocusEngine.Event.Tick(5 * 60_000L)).first.rage == 40)
            val (b1, rb) = apply(st().copy(rage = 85), FocusEngine.Event.Left(0), FocusEngine.Event.Distract(1_000, "某短视频"))
            check("引擎：生气值 ≥90 → 爆，冷战 3 分钟", b1.rage >= 90 && b1.blowns == 1 && b1.coldUntilMs == 1_000L + 180_000L && rb.any { it.kind == FocusEngine.Kind.BLOWN }) { "$b1 $rb" }
            val (b2, _) = apply(b1, FocusEngine.Event.Left(2_000), FocusEngine.Event.Tick(20_000), FocusEngine.Event.Distract(21_000, "某社交"))
            check("引擎：冷战期间不再叠加生气值", b2.rage == b1.rage) { "${b2.rage} vs ${b1.rage}" }
            val (b3, _) = apply(b1.copy(awayAtMs = null, lastTickMs = 1_000L), FocusEngine.Event.Tick(1_000L + 60_000L))
            check("引擎：冷战期间待在本应用里，冷战倒计时按 2 倍速走（60 秒当 120 秒）", b3.coldUntilMs == b1.coldUntilMs - 60_000L) { "${b3.coldUntilMs} vs ${b1.coldUntilMs}" }
            val (b4, rb4) = apply(b1.copy(awayAtMs = null, lastTickMs = 1_000L), FocusEngine.Event.Tick(1_000L + 90_000L), FocusEngine.Event.Tick(1_000L + 180_000L))
            check("引擎：冷战结束 → 生气值回到 50、有 COLD_END 反应", b4.coldUntilMs == 0L && b4.rage == 50 && rb4.any { it.kind == FocusEngine.Kind.COLD_END }) { "$b4 $rb4" }
            val hell = FocusEngine.Config(difficulty = FocusEngine.Difficulty.HELL, graceSec = 8)
            val mild = FocusEngine.Config(difficulty = FocusEngine.Difficulty.MILD, graceSec = 8)
            check("引擎：难度倍率（地狱 21 > 毒舌 15 > 温和 <15）",
                apply(FocusEngine.start(0, hell), FocusEngine.Event.Left(0), FocusEngine.Event.Tick(9_000), c = hell).first.rage == 21 &&
                    apply(FocusEngine.start(0, mild), FocusEngine.Event.Left(0), FocusEngine.Event.Tick(9_000), c = mild).first.rage < 15)
            check("引擎：生气值最高 100", apply(st().copy(rage = 99), FocusEngine.Event.Left(0), FocusEngine.Event.Distract(1, "a")).first.rage <= 100)
            val (f1, rf1) = apply(st(), FocusEngine.Event.Tick(25 * 60_000L))
            check("引擎：时间到 → FINISH，零干扰评 S", f1.finished && rf1.any { it.kind == FocusEngine.Kind.FINISH && it.slots["grade"] == "S" }) { "$rf1" }
            val (_, rf2) = apply(st().copy(leaves = 1, rage = 10), FocusEngine.Event.Tick(25 * 60_000L))
            check("引擎：离开 1 次、没爆 → A", rf2.any { it.slots["grade"] == "A" }) { "$rf2" }
            val (_, rf3) = apply(st().copy(blowns = 1), FocusEngine.Event.Tick(25 * 60_000L))
            check("引擎：爆过 → C", rf3.any { it.slots["grade"] == "C" }) { "$rf3" }
            val (p1, _) = apply(st(), FocusEngine.Event.Pause(10_000L), FocusEngine.Event.Resume(70_000L))
            check("引擎：暂停（接电话）60 秒 → 结束时间顺延 60 秒", p1.endMs == 25 * 60_000L + 60_000L) { p1.endMs.toString() }
            check("引擎：结束后不再处理事件", apply(f1, FocusEngine.Event.Left(0), FocusEngine.Event.Tick(30 * 60_000L)).first.rage == f1.rage)
            check("引擎：档位阈值 30/60/90", FocusEngine.levelOf(0) == FocusEngine.Level.CALM && FocusEngine.levelOf(29) == FocusEngine.Level.CALM && FocusEngine.levelOf(30) == FocusEngine.Level.NAG && FocusEngine.levelOf(60) == FocusEngine.Level.SNARK && FocusEngine.levelOf(90) == FocusEngine.Level.BLOWN)
        }

        // ---------------- 专注会话：引擎 + 台词 + 安全阀（秒级参数，测试当场出结果） ----------------
        blk {
            val FS = FocusSafety
            check("安全阀：地狱档在夜里（23 点、凌晨 3 点）自动降到毒舌", FS.effectiveDifficulty(FocusEngine.Difficulty.HELL, 23, false) == FocusEngine.Difficulty.SPICY && FS.effectiveDifficulty(FocusEngine.Difficulty.HELL, 3, false) == FocusEngine.Difficulty.SPICY)
            check("安全阀：白天地狱档照旧", FS.effectiveDifficulty(FocusEngine.Difficulty.HELL, 14, false) == FocusEngine.Difficulty.HELL)
            check("安全阀：用户情绪低落 → 一律降到温和", listOf(FocusEngine.Difficulty.HELL, FocusEngine.Difficulty.SPICY, FocusEngine.Difficulty.MILD).all { FS.effectiveDifficulty(it, 14, true) == FocusEngine.Difficulty.MILD })
            check("安全阀：毒舌档夜里不变", FS.effectiveDifficulty(FocusEngine.Difficulty.SPICY, 2, false) == FocusEngine.Difficulty.SPICY)

            val fast = FocusEngine.Config(durationSec = 120, graceSec = 2, coldWarSec = 6, calmBonusSec = 5, difficulty = FocusEngine.Difficulty.HELL)
            check("配置：durationSec 优先于 durationMin", FocusEngine.start(0L, fast).endMs == 120_000L && FocusEngine.start(0L, FocusEngine.Config(durationMin = 25)).endMs == 25 * 60_000L)
            val calmFast = FocusEngine.reduce(FocusEngine.start(0L, fast).copy(rage = 50), FocusEngine.Event.Tick(5_000L), fast).state
            check("配置：calmBonusSec=5 → 5 秒无干扰回血 10", calmFast.rage == 40) { calmFast.rage.toString() }

            val sess = FocusSession("tsundere", fast, 0L, seed = 3)
            val startLines = sess.begin(0L, 14)
            check("会话：开场有一句 START 台词，且过 InsultGuard", startLines.size == 1 && startLines[0].kind == FocusEngine.Kind.START && InsultGuard.check(startLines[0].text).isEmpty()) { startLines.toString() }
            val l1 = sess.handle(FocusEngine.Event.Left(1_000L), 14) + sess.handle(FocusEngine.Event.Tick(4_000L), 14)
            check("会话：离开超过宽限期 → 有一句 LEAVE 台词，状态记 1 次离开", l1.any { it.kind == FocusEngine.Kind.LEAVE } && sess.state.leaves == 1) { l1.toString() }
            val l2 = sess.handle(FocusEngine.Event.Returned(5_000L), 14)
            check("会话：回来有一句 RETURN 台词", l2.any { it.kind == FocusEngine.Kind.RETURN }) { l2.toString() }
            val l3 = sess.handle(FocusEngine.Event.Tick(120_000L), 14)
            check("会话：时间到 → FINISH 台词 + 结束状态", l3.any { it.kind == FocusEngine.Kind.FINISH } && sess.state.finished) { l3.toString() }

            // 随机模拟：每个人设、每个难度跑 300 步，台词全部合规、不连续复读、生气值始终在 0..100
            var bad = 0; var dup = 0; var outOfRange = 0; var total = 0
            for (pid in listOf("tsundere", "buddy", "kuudere", "oneesan", "genki", "catgirl", "puppy", "listener", "aling", "shenze", "fuchen", "shenyan", "hudegui", "chenye", "unknown")) for (d in FocusEngine.Difficulty.values()) {
                val c = FocusEngine.Config(durationSec = 100_000, graceSec = 2, coldWarSec = 20, calmBonusSec = 30, difficulty = d)
                val ss = FocusSession(pid, c, 0L, seed = pid.hashCode() + d.ordinal)
                val rnd = kotlin.random.Random(pid.hashCode() * 31 + d.ordinal)
                var t = 0L; var prev = ""
                ss.begin(0L, 14)
                repeat(300) {
                    t += 1_000L + rnd.nextInt(4_000)
                    val ev = when (rnd.nextInt(7)) {
                        0 -> FocusEngine.Event.Left(t); 1 -> FocusEngine.Event.Returned(t); 2 -> FocusEngine.Event.ScreenOn(t)
                        3 -> FocusEngine.Event.Distract(t, listOf("某短视频", "某社交", "某游戏")[rnd.nextInt(3)])
                        else -> FocusEngine.Event.Tick(t)
                    }
                    for (ln in ss.handle(ev, 14)) {
                        total++
                        if (InsultGuard.check(ln.text).isNotEmpty() || ln.text.contains("{")) bad++
                        if (ln.text == prev) dup++
                        prev = ln.text
                    }
                    if (ss.state.rage !in 0..100) outOfRange++
                }
            }
            check("会话：随机模拟 2700 步，台词全部合规（$total 句）", bad == 0 && total > 200) { "bad=$bad total=$total" }
            check("会话：随机模拟里没有连续复读同一句", dup == 0) { "dup=$dup" }
            check("会话：生气值始终在 0..100", outOfRange == 0)

            // 真机发现的两个问题（2026-10-20）：冷战秒级参数显示成「0 分钟」；爆表那句抢在引起爆表的那句前面
            val b = FocusSession("tsundere", fast, 0L, seed = 5)
            b.handle(FocusEngine.Event.Left(0L), 14)
            val dl = FocusEngine.reduce(FocusEngine.start(0L, fast).copy(rage = 85, awayAtMs = 0L, awayPenaltyDone = true), FocusEngine.Event.Distract(1_000L, "短视频"), fast).reactions.map { it.kind }
            check("引擎：引起爆表的那一句（DISTRACT）排在爆表（BLOWN）之前", dl.indexOf(FocusEngine.Kind.DISTRACT) in 0 until dl.indexOf(FocusEngine.Kind.BLOWN)) { dl.toString() }
            val lp = FocusEngine.reduce(FocusEngine.start(0L, fast).copy(rage = 80, awayAtMs = 0L), FocusEngine.Event.Tick(5_000L), fast).reactions.map { it.kind }
            check("引擎：离开扣分引起爆表时，LEAVE 也排在 BLOWN 之前", lp.indexOf(FocusEngine.Kind.LEAVE) in 0 until lp.indexOf(FocusEngine.Kind.BLOWN)) { lp.toString() }
            val coldSlot = FocusEngine.reduce(FocusEngine.start(0L, fast).copy(rage = 95), FocusEngine.Event.Tick(1_000L), fast)
            check("槽位：冷战 10 秒显示「10 秒」，180 秒显示「3 分钟」", FocusEngine.coldText(10) == "10 秒" && FocusEngine.coldText(180) == "3 分钟" && FocusEngine.coldText(90) == "90 秒")
            val blownLine = FocusLines.fill(FocusLines.allTemplates().first { it.first.startsWith("tsundere/BLOWN") }.second, mapOf("cold" to FocusEngine.coldText(10)))
            check("台词：爆表台词里的冷战时长带单位，不会出现「0 分钟」", blownLine.contains("10 秒") && !blownLine.contains("0 分钟")) { blownLine }
            check("台词：所有模板都不再硬写「{cold} 分钟」（单位由槽位给）", FocusLines.allTemplates().none { it.second.contains("{cold} 分钟") })

            // 观感复读：连续两次分心只是应用名不同、模板却一样（"…那么好玩吗？姐姐也想试试"）——要按模板去重，不只按成品文本
            FocusLines.setExtra(FocusLineBank.cells().associate { FocusLineBank.cellKey(it.first, it.second, it.third) to listOf("扩充台词甲，{app}好玩吗，我也想去看看。", "扩充台词乙，{app}这么香吗，别去了嘛。", "扩充台词丙，你又去{app}了，记在小本本上。", "扩充台词丁，{app}那边没有我这边精彩哦。") }.filterKeys { it.contains("/DISTRACT/") })
            var sameTemplate = 0
            for (seed in 1..80) {
                val ss = FocusSession("oneesan", FocusEngine.Config(durationSec = 600, graceSec = 1, coldWarSec = 10, difficulty = FocusEngine.Difficulty.SPICY), 0L, seed = seed)
                ss.handle(FocusEngine.Event.Left(0L), 14)
                val a1 = ss.handle(FocusEngine.Event.Distract(1_000L, "甲应用"), 14).firstOrNull { it.kind == FocusEngine.Kind.DISTRACT }
                val a2 = ss.handle(FocusEngine.Event.Distract(2_000L, "乙应用"), 14).firstOrNull { it.kind == FocusEngine.Kind.DISTRACT }
                if (a1 != null && a2 != null && a1.level == a2.level && a1.text.replace("甲应用", "{app}") == a2.text.replace("乙应用", "{app}")) sameTemplate++
            }
            check("会话：连续两次分心（同一档位）不会抽到同一个模板，只换应用名", sameTemplate == 0) { "sameTemplate=$sameTemplate" }
            val tpl = FocusLines.pickTemplate("oneesan", FocusLines.Kind.DISTRACT, FocusEngine.Level.CALM, FocusEngine.Difficulty.SPICY, kotlin.random.Random(3), emptyList())
            val all5 = (1..300).map { FocusLines.pickTemplate("oneesan", FocusLines.Kind.DISTRACT, FocusEngine.Level.CALM, FocusEngine.Difficulty.SPICY, kotlin.random.Random(it), emptyList()) }.toSet()
            check("模板：不避让时，预置和扩充的模板都会被抽到（共 5 个）", all5.size == 5 && tpl in all5) { all5.size.toString() }
            val avoid3 = all5.toList().take(3)
            check("模板：避开最近用过的 3 个，只会抽剩下的", (1..100).all { FocusLines.pickTemplate("oneesan", FocusLines.Kind.DISTRACT, FocusEngine.Level.CALM, FocusEngine.Difficulty.SPICY, kotlin.random.Random(it), avoid3) !in avoid3 })
            check("模板：全都用过了也照样能抽（不会没话说）", FocusLines.pickTemplate("oneesan", FocusLines.Kind.DISTRACT, FocusEngine.Level.CALM, FocusEngine.Difficulty.SPICY, kotlin.random.Random(1), all5.toList()).isNotBlank())
            check("模板：只有一个模板的格子照样返回它", FocusLines.pickTemplate("unknown", FocusLines.Kind.DISTRACT, FocusEngine.Level.CALM, FocusEngine.Difficulty.SPICY, kotlin.random.Random(1), listOf("你去了{app}，我看到了。")).contains("{app}"))
            FocusLines.setExtra(emptyMap())

            val night = FocusSession("buddy", fast, 0L, seed = 1)
            night.handle(FocusEngine.Event.Left(0L), 23)
            check("会话：夜里 23 点地狱档实际按毒舌处理", night.lastDifficulty == FocusEngine.Difficulty.SPICY)
            val sad = FocusSession("buddy", fast, 0L, seed = 1)
            sad.handle(FocusEngine.Event.Left(0L), 14, distressed = true)
            check("会话：情绪低落标记 → 实际按温和处理", sad.lastDifficulty == FocusEngine.Difficulty.MILD)
        }

        // ---------------- 地狱难度的悬浮窗：什么时候弹、弹多久（纯规则） ----------------
        blk {
            val OP = FocusOverlayPolicy
            val K = FocusEngine.Kind.values().toList()
            val H = FocusEngine.Difficulty.HELL
            fun show(kind: FocusEngine.Kind, d: FocusEngine.Difficulty = H, inApp: Boolean = false, perm: Boolean = true, enabled: Boolean = true, last: Long = 0L, now: Long = 100_000L) =
                OP.shouldShow(kind, d, inApp, perm, enabled, last, now, 4_000L)
            check("悬浮窗：地狱档 + 已授权 + 已开启 + 不在本应用 → 离开/亮屏/分心/爆表 都弹",
                listOf(FocusEngine.Kind.LEAVE, FocusEngine.Kind.SCREEN_PEEK, FocusEngine.Kind.DISTRACT, FocusEngine.Kind.BLOWN).all { show(it) })
            check("悬浮窗：开场、回来、冷战结束、结算这些「温和」的话不弹", listOf(FocusEngine.Kind.START, FocusEngine.Kind.RETURN, FocusEngine.Kind.COLD_END, FocusEngine.Kind.FINISH, FocusEngine.Kind.RETURN_GRACE, FocusEngine.Kind.LEVEL_UP, FocusEngine.Kind.CALM_BONUS).none { show(it) })
            check("悬浮窗：毒舌和温和档不弹（只有地狱档才有）", !show(FocusEngine.Kind.LEAVE, FocusEngine.Difficulty.SPICY) && !show(FocusEngine.Kind.LEAVE, FocusEngine.Difficulty.MILD))
            check("悬浮窗：人在本应用里不弹（界面自己会显示）", !show(FocusEngine.Kind.LEAVE, inApp = true))
            check("悬浮窗：没有悬浮窗权限不弹（权限由用户手动授）", !show(FocusEngine.Kind.LEAVE, perm = false))
            check("悬浮窗：用户没开这个选项不弹", !show(FocusEngine.Kind.LEAVE, enabled = false))
            check("悬浮窗：4 秒内刚弹过不再弹（别刷屏）", !show(FocusEngine.Kind.LEAVE, last = 98_000L, now = 100_000L) && show(FocusEngine.Kind.LEAVE, last = 95_000L, now = 100_000L))
            check("悬浮窗：显示时长随字数变，最短 3.5 秒、最长 8 秒", OP.displayMs("好") in 3_500L..3_700L && OP.displayMs("好".repeat(200)) == 8_000L && OP.displayMs("好".repeat(30)) in 3_500L..8_000L && OP.displayMs("好".repeat(30)) >= OP.displayMs("好".repeat(10)))

            // 与安全阀联动：夜里 23 点地狱档被降成毒舌，悬浮窗也就不弹；情绪低落同理
            val fast = FocusEngine.Config(durationSec = 600, graceSec = 1, coldWarSec = 10, difficulty = H)
            val night = FocusSession("tsundere", fast, 0L, seed = 2)
            night.handle(FocusEngine.Event.Left(0L), 23)
            check("悬浮窗：夜里 23 点（地狱降级为毒舌）→ 不弹", !OP.shouldShow(FocusEngine.Kind.LEAVE, night.lastDifficulty, false, true, true, 0L, 100_000L, 4_000L))
            val day = FocusSession("tsundere", fast, 0L, seed = 2)
            day.handle(FocusEngine.Event.Left(0L), 14)
            check("悬浮窗：白天地狱档 → 弹", OP.shouldShow(FocusEngine.Kind.LEAVE, day.lastDifficulty, false, true, true, 0L, 100_000L, 4_000L))
            val sad = FocusSession("tsundere", fast, 0L, seed = 2)
            sad.handle(FocusEngine.Event.Left(0L), 14, distressed = true)
            check("悬浮窗：情绪低落（降到温和）→ 不弹", !OP.shouldShow(FocusEngine.Kind.LEAVE, sad.lastDifficulty, false, true, true, 0L, 100_000L, 4_000L))
            check("悬浮窗：树洞人设永远不弹", !OP.allowedFor("listener") && OP.allowedFor("tsundere"))
        }

        // ---------------- F3：模型批量生成的台词——清洗、校验、去重、混入（只有过关的才进库） ----------------
        blk {
            val B = FocusLineBank
            check("清洗：去掉编号/项目符号/引号", B.cleanLine("1. 哼，别走！") == "哼，别走！" && B.cleanLine("- 哼，别走！") == "哼，别走！" && B.cleanLine("「哼，别走！」") == "哼，别走！" && B.cleanLine("（3）哼，别走！") == "哼，别走！")
            val kLeave = FocusLines.Kind.LEAVE
            check("校验：正常台词通过", B.validate("你的手指是装了弹簧吗，一秒都按不住手机。", kLeave).isEmpty())
            check("校验：带数字被拒（数字必须来自槽位，免得和事实对不上）", B.validate("你已经走开了 3 次，真行。", kLeave).any { it.contains("数字") })
            check("校验：中文数量词也被拒（第三次、两分钟）", B.validate("这是你第三次走开了，真行啊。", kLeave).isNotEmpty() && B.validate("你已经跑了两分钟了，真行啊。", kLeave).isNotEmpty())
            check("校验：用槽位（{sec}、{n}）是允许的", B.validate("才 {sec} 秒你就绷不住了，第 {n} 次了。", kLeave).isEmpty())
            check("校验：槽位和事件不匹配被拒（离开台词里用{app}）", B.validate("你又跑去{app}了，真行。", kLeave).any { it.contains("槽位") })
            check("校验：分心台词可以用{app}", B.validate("{app}比我好看吗，这么舍不得走？", FocusLines.Kind.DISTRACT).isEmpty())
            check("校验：出戏（提到 AI、模型、系统提示）被拒", listOf("作为一个 AI 我提醒你专心。", "我是语言模型，你别分心。", "根据系统提示你该回来了。").all { B.validate(it, kLeave).isNotEmpty() })
            check("校验：太短太长被拒", B.validate("走了", kLeave).isNotEmpty() && B.validate("你".repeat(60), kLeave).isNotEmpty())
            check("校验：脏话和人身攻击被拒，且原因里带着 InsultGuard 的判断", B.validate("你这个傻逼又走了。", kLeave).any { it.startsWith("InsultGuard") } && B.validate("你真没用，一秒都坐不住。", kLeave).any { it.startsWith("InsultGuard") })
            check("校验：Markdown 格式符号被拒", B.validate("**你又走了**，真行啊你。", kLeave).isNotEmpty() && B.validate("# 你又走了，真行啊你。", kLeave).isNotEmpty())
            check("近重复：同一句和只改一两个字的算重复，不同的不算",
                B.nearDuplicate("你的手指是装了弹簧吗，一秒都按不住。", "你的手指是装了弹簧吗，一秒都按不住啊。") &&
                    !B.nearDuplicate("你的手指是装了弹簧吗，一秒都按不住。", "好家伙，才坐下就想跑，您是在练习分身术吗？"))

            val raw = """以下是台词：
1. 你的手指是装了弹簧吗，一秒都按不住手机。
2. 你的手指是装了弹簧吗，一秒都按不住手机。
3. 你这个傻逼又走了。
4. 好家伙，才坐下就想跑，您是在练习分身术吗？
5. 你已经走了 3 次了，真行。
6. 好
7. 本小姐的耐心已经透支到负数了，利息按秒算。
"""
            val r = B.parse(raw, kLeave, existing = listOf("好家伙，才坐下就想跑，您是在练习分身术啊？"))
            check("批量解析：只留下合格且不重复的（弹簧那句 1 条 + 耐心那句）", r.accepted == listOf("你的手指是装了弹簧吗，一秒都按不住手机。", "本小姐的耐心已经透支到负数了，利息按秒算。")) { r.accepted.toString() }
            check("批量解析：被拒的都带原因（脏话、数字、太短、与库里重复）", r.rejected.size >= 4 && r.rejected.any { it.reasons.any { x -> x.startsWith("InsultGuard") } } && r.rejected.any { it.reasons.any { x -> x.contains("数字") } } && r.rejected.any { it.reasons.any { x -> x.contains("重复") } }) { r.rejected.toString() }
            check("批量解析：「以下是台词：」这种前言不当成台词", r.accepted.none { it.contains("以下是") } && r.rejected.none { it.line.contains("以下是") })

            check("库容量：每格最多 40 条，保留最新", B.merge((1..45).map { "第${it}句不重样的台词内容哦" }, listOf("全新的一句台词来了呀"), 40).let { it.size == 40 && it.last() == "全新的一句台词来了呀" })
            check("格子编号：人设/事件/档位", B.cellKey("tsundere", FocusLines.Kind.LEAVE, 1) == "tsundere/LEAVE/1" && B.cellKey("buddy", FocusLines.Kind.START, 0) == "buddy/START/0")
            check("格子：LEAVE/PEEK/DISTRACT 三档，其余只有一档", B.cells().count { it.second == FocusLines.Kind.LEAVE } == PersonaPacks.ALL.size * 3 && B.cells().count { it.second == FocusLines.Kind.START } == PersonaPacks.ALL.size && B.cells().none { it.second == FocusLines.Kind.START && it.third != 0 })
            val p = B.prompt("tsundere", "傲娇：嘴硬心软，自称本小姐", FocusLines.Kind.LEAVE, 2, FocusEngine.Difficulty.HELL, 8)
            check("生成提示：写清楚修辞菜单、禁止项、可用槽位、条数、输出格式", listOf("反语", "夸张", "拟人", "脏字", "人身", "{sec}", "{n}", "8", "一行一条").all { p.contains(it) }) { p }
            check("生成提示：事件不同，可用槽位不同（分心台词给{app}，不给{sec}）", B.prompt("buddy", "损友", FocusLines.Kind.DISTRACT, 1, FocusEngine.Difficulty.SPICY, 6).let { it.contains("{app}") && !it.contains("{sec}") })

            // ---- 混入：运行时 pick 从「预置 + 生成」里抽；生成库在载入时再过一遍关，脏的永远进不来 ----
            val slots2 = mapOf("sec" to "5", "n" to "2", "rage" to "40", "left" to "1:00", "app" to "某应用", "grade" to "B", "min" to "25", "cold" to "3 分钟", "peeks" to "1", "blowns" to "0")
            FocusLines.setExtra(mapOf(
                "tsundere/LEAVE/1" to listOf("我是新增的独一无二台词，别走开哦。", "你这个傻逼又走了。", "你已经走了 3 次了，真行。"),
                "tsundere/LEAVE/9" to listOf("档位不存在的格子不能收。"),
            ))
            val picked = (1..400).map { FocusLines.pick("tsundere", FocusLines.Kind.LEAVE, FocusEngine.Level.NAG, FocusEngine.Difficulty.SPICY, slots2, kotlin.random.Random(it)) }.toSet()
            check("混入：生成的合格台词能被抽到，预置台词也还在", picked.contains("我是新增的独一无二台词，别走开哦。") && picked.any { it.contains("第 2 次了") || it.contains("弹簧") })
            check("混入：载入时再过关——脏话、带数字的永远不会被抽到", picked.none { it.contains("傻逼") || it.contains("3 次") })
            check("混入：不存在的格子被丢弃", FocusLines.extraCount() == 1) { FocusLines.extraCount().toString() }
            FocusLines.setExtra(emptyMap())
            check("混入：清空后恢复只用预置", FocusLines.extraCount() == 0 && (1..200).map { FocusLines.pick("tsundere", FocusLines.Kind.LEAVE, FocusEngine.Level.NAG, FocusEngine.Difficulty.SPICY, slots2, kotlin.random.Random(it)) }.none { it.contains("独一无二") })
        }

        // ---------------- 专注收尾：痛苦词降档 + 结算统计/连续天数 ----------------
        blk {
            val FS = FocusSafety
            val T = 10_000_000L
            check("痛苦词：好累 / 崩溃 / 失眠 → 判为状态不好", listOf("今天好累", "我有点崩溃", "最近一直失眠", "压力很大").all { FS.isLowMood(it) })
            check("痛苦词：危机表达（不想活了）同样降档", FS.isLowMood("我真的不想活了"))
            check("痛苦词：平常的话不算", listOf("你好呀", "帮我设个闹钟", "今天天气不错", "").none { FS.isLowMood(it) })
            check("痛苦词：说了之后 1 小时内都温柔", FS.updateUntil("今天好累", T, 0L) == T + 3_600_000L && FS.active(T + 3_600_000L, T + 3_000_000L) && !FS.active(T + 3_600_000L, T + 3_600_001L))
            check("痛苦词：普通话不改变已有的温柔期，也不会缩短它", FS.updateUntil("你好呀", T + 10L, T + 3_600_000L) == T + 3_600_000L && FS.updateUntil("好累", T, T + 9_000_000L) == T + 9_000_000L)
            check("痛苦词：接入会话 → 一句「好累」就让地狱档按温和处理", FocusSession("tsundere", FocusEngine.Config(difficulty = FocusEngine.Difficulty.HELL, durationSec = 600), 0L).let { s ->
                s.handle(FocusEngine.Event.Left(0L), 14, distressed = FS.active(FS.updateUntil("好累", T, 0L), T + 5L)); s.lastDifficulty == FocusEngine.Difficulty.MILD })

            val zone = java.time.ZoneId.of("Asia/Shanghai")
            fun ms(y: Int, m: Int, d: Int, h: Int = 10) = java.time.LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()
            fun rec(day: Int, completed: Boolean = true, grade: String = "A", sec: Int = 1500, month: Int = 10, h: Int = 10) =
                FocusStats.Rec(ms(2026, month, day, h), sec, completed, grade, 1, 0, 0, 0)
            val now = ms(2026, 10, 20, 21)
            fun sum(vararg r: FocusStats.Rec) = FocusStats.summarize(r.toList(), now, zone)
            check("统计：没有记录 → 连续 0 天", sum().streakDays == 0 && sum().total == 0)
            check("统计：今天一局完成 → 连续 1 天", sum(rec(20)).streakDays == 1)
            check("统计：昨天 + 今天 → 连续 2 天", sum(rec(19), rec(20)).streakDays == 2)
            check("统计：昨天有、今天还没 → 连续 1 天（今天还能续上，不算断）", sum(rec(19)).streakDays == 1 && !sum(rec(19)).todayDone)
            check("统计：前天有、昨天今天都没有 → 断了，0 天", sum(rec(18)).streakDays == 0)
            check("统计：中间隔一天 → 只算最近连续的那段", sum(rec(17), rec(19), rec(20)).streakDays == 2)
            check("统计：中途结束（没完成）的不算连续", sum(rec(19), rec(20, completed = false)).streakDays == 1 && sum(rec(20, completed = false)).streakDays == 0)
            check("统计：同一天多局只算一天", sum(rec(20), rec(20, h = 15), rec(20, h = 18)).streakDays == 1)
            check("统计：跨月连续（9 月 30 日 + 10 月 1 日…）", FocusStats.summarize(listOf(rec(30, month = 9), rec(1), rec(2)), ms(2026, 10, 2, 22), zone).streakDays == 3)
            check("统计：历史最长连续", sum(rec(1), rec(2), rec(3), rec(4), rec(10), rec(20)).longestStreak == 4)
            check("统计：近 7 天累计分钟（含中途结束的实际时长，不含 7 天前）",
                sum(rec(20, sec = 600), rec(19, completed = false, sec = 300), rec(10, sec = 6000)).weekMinutes == 15)
            check("统计：历史最佳评级 S > A > B > C，只看完成的", sum(rec(19, grade = "B"), rec(20, grade = "S")).bestGrade == "S" && sum(rec(19, grade = "C")).bestGrade == "C" && sum(rec(19, completed = false, grade = "S")).bestGrade == null)
            check("统计：总局数与完成局数", sum(rec(19), rec(20, completed = false)).let { it.total == 2 && it.completedCount == 1 })
            val line = FocusStats.line(sum(rec(18), rec(19), rec(20, sec = 3600)))
            check("统计：一句话摘要含连续天数、本周分钟、最佳评级", line.contains("连续 3 天") && line.contains("本周") && line.contains("分钟") && line.contains("A")) { line }
            check("统计：没有记录时的摘要是邀请，不是责备", FocusStats.line(sum()).contains("第一局") && !FocusStats.line(sum()).contains("断"))
            check("统计：今天还没专注但有连续 → 提示今天还能续上", FocusStats.line(sum(rec(18), rec(19))).contains("今天") )
            check("统计：只保留最近 500 条", FocusStats.append((1..500).map { rec(1) }, rec(2), 500).let { it.size == 500 && it.last().startMs == ms(2026, 10, 2) })
        }

        // ---------------- 提醒：重复规则 / 改·删·推迟 / 再提醒 ----------------
        blk {
            val RR = RepeatRule
            fun d(y: Int, m: Int, day: Int, h: Int = 0, mi: Int = 0) = LocalDateTime.of(y, m, day, h, mi)
            check("重复：每天", RR.parse("每天早上8点提醒我吃药")?.let { it.kind == RepeatRule.Kind.DAILY } == true)
            check("重复：每个工作日 / 工作日", RR.parse("每个工作日9点叫我起床")?.kind == RepeatRule.Kind.WEEKDAYS && RR.parse("工作日早上7点半提醒我打卡")?.kind == RepeatRule.Kind.WEEKDAYS)
            check("重复：每周三", RR.parse("每周三下午3点提醒我开会")?.let { it.kind == RepeatRule.Kind.WEEKLY && it.days == setOf(java.time.DayOfWeek.WEDNESDAY) } == true)
            check("重复：每周一三五", RR.parse("每周一三五晚上8点提醒我健身")?.days == setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.FRIDAY))
            check("重复：每周一、周四（带顿号）", RR.parse("每周一、周四下午提醒我交周报")?.days == setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.THURSDAY))
            check("重复：每周末", RR.parse("每个周末早上9点提醒我打扫")?.days == setOf(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY))
            check("重复：每月15号 / 每个月1日", RR.parse("每月15号提醒我交房租")?.let { it.kind == RepeatRule.Kind.MONTHLY && it.dom == 15 } == true && RR.parse("每个月1日提醒我还信用卡")?.dom == 1)
            check("重复：不是重复的说法不识别（明天、每次开会前、周三）", listOf("明天9点开会", "每次开会前提醒我", "周三下午3点开会", "你好呀").all { RR.parse(it) == null })
            check("重复：去掉重复词后剩下的话照旧可解析", RR.strip("每周三下午3点提醒我开会").let { !it.contains("每周") && it.contains("下午3点") && it.contains("提醒我开会") })
            check("重复：去掉「每个工作日」「每月15号」", !RR.strip("每个工作日9点叫我起床").contains("工作日") && !RR.strip("每月15号提醒我交房租").contains("15"))
            val t8 = java.time.LocalTime.of(8, 0)
            val tue2100 = d(2026, 10, 20, 21)   // 2026-10-20 是周二
            check("下一次：每天 8 点，晚上 9 点之后问 → 明早", RR.next(RepeatRule.daily(), tue2100, t8) == d(2026, 10, 21, 8))
            check("下一次：每天 22 点，晚上 9 点问 → 今晚", RR.next(RepeatRule.daily(), tue2100, java.time.LocalTime.of(22, 0)) == d(2026, 10, 20, 22))
            check("下一次：工作日，周五晚上之后 → 下周一", RR.next(RepeatRule.weekdays(), d(2026, 10, 23, 18), java.time.LocalTime.of(9, 0)) == d(2026, 10, 26, 9))
            check("下一次：每周三 15 点，周二问 → 明天", RR.next(RepeatRule.weekly(setOf(java.time.DayOfWeek.WEDNESDAY)), tue2100, java.time.LocalTime.of(15, 0)) == d(2026, 10, 21, 15))
            check("下一次：每周二 15 点，周二晚上问 → 下周二", RR.next(RepeatRule.weekly(setOf(java.time.DayOfWeek.TUESDAY)), tue2100, java.time.LocalTime.of(15, 0)) == d(2026, 10, 27, 15))
            check("下一次：每月 15 号 → 下个月 15 号", RR.next(RepeatRule.monthly(15), tue2100, java.time.LocalTime.of(10, 0)) == d(2026, 11, 15, 10))
            check("下一次：每月 31 号，遇到 30 天的月份取月底", RR.next(RepeatRule.monthly(31), d(2026, 10, 31, 12), java.time.LocalTime.of(10, 0)) == d(2026, 11, 30, 10))
            check("下一次：每月 30 号，二月取月底（平年 28 日）", RR.next(RepeatRule.monthly(30), d(2027, 1, 31, 12), java.time.LocalTime.of(10, 0)) == d(2027, 2, 28, 10))
            check("下一次：永远严格晚于「之后」（同一时刻不重复触发）", RR.next(RepeatRule.daily(), d(2026, 10, 20, 8), t8) == d(2026, 10, 21, 8))
            check("存取：序列化再还原一致", listOf(RepeatRule.daily(), RepeatRule.weekdays(), RepeatRule.weekly(setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY)), RepeatRule.monthly(15)).all { RR.fromString(RR.serialize(it)) == it })
            check("存取：空串/乱串 → null（旧提醒没有重复）", RR.fromString("") == null && RR.fromString("zzz") == null)
            check("描述：给用户看的文字", RR.describe(RepeatRule.daily()) == "每天" && RR.describe(RepeatRule.weekdays()) == "每个工作日" &&
                RR.describe(RepeatRule.weekly(setOf(java.time.DayOfWeek.WEDNESDAY))) == "每周三" && RR.describe(RepeatRule.weekly(setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY))) == "每周一、五" && RR.describe(RepeatRule.monthly(15)) == "每月 15 号")

            // 路由：带重复词的提醒/闹钟 → 第一次触发时间落在规则允许的日子，且带上规则
            val rt = IntentRouter.route("每周三下午3点提醒我开会", NOW)   // NOW 是周六 2026-10-17 21:04
            check("路由：每周三 15 点提醒 → 提醒，第一次在下个周三 15:00，内容是「开会」", rt.kind == IntentRouter.Kind.REMINDER && rt.confident && rt.repeat.startsWith("weekly") && rt.time?.dateTime == LocalDateTime.of(2026, 10, 21, 15, 0) && rt.content.contains("开会") && !rt.content.contains("每周")) { rt.toString() }
            val ra = IntentRouter.route("每个工作日早上7点半叫我起床", NOW)
            check("路由：每个工作日 7:30 叫我起床 → 闹钟 + 重复，第一次在周一 07:30", ra.kind == IntentRouter.Kind.ALARM && ra.repeat == "weekdays" && ra.time?.dateTime == LocalDateTime.of(2026, 10, 19, 7, 30)) { ra.toString() }
            val rd = IntentRouter.route("每天晚上10点提醒我吃药", NOW)
            check("路由：每天 22:00 提醒吃药 → 今晚 22:00 起", rd.kind == IntentRouter.Kind.REMINDER && rd.repeat == "daily" && rd.time?.dateTime == LocalDateTime.of(2026, 10, 17, 22, 0) && rd.content.contains("吃药")) { rd.toString() }
            check("路由：没有重复词的照旧（repeat 为空）", IntentRouter.route("明天早上7点叫我起床", NOW).repeat.isEmpty())

            // ---- 改/删/推迟 ----
            val E = ReminderEdit
            val tomorrow15 = NOW.toLocalDate().plusDays(1).atTime(15, 0)
            val c1 = E.parse("取消明天下午三点的提醒", NOW)
            check("改删：取消明天下午三点的提醒 → CANCEL，按时间找", c1?.kind == ReminderEdit.Kind.CANCEL && c1.hintTime == tomorrow15) { c1.toString() }
            val c2 = E.parse("取消开会的提醒", NOW)
            check("改删：取消开会的提醒 → CANCEL，按内容找", c2?.kind == ReminderEdit.Kind.CANCEL && c2.hint == "开会") { c2.toString() }
            check("改删：删掉喝水提醒 / 别提醒我喝水了 / 不用提醒我喝水了", listOf("删掉喝水提醒", "别提醒我喝水了", "不用提醒我喝水了").all { E.parse(it, NOW)?.let { e -> e.kind == ReminderEdit.Kind.CANCEL && e.hint == "喝水" } == true })
            check("改删：取消闹钟 → CANCEL，目标是闹钟", E.parse("把明天的闹钟取消", NOW)?.target == ReminderEdit.Target.ALARM)
            check("改删：取消所有提醒 → 全部", E.parse("取消所有提醒", NOW)?.all == true)
            val r1 = E.parse("把开会提醒改到四点", NOW)
            check("改删：把开会提醒改到四点 → RESCHEDULE，新时间 4 点", r1?.kind == ReminderEdit.Kind.RESCHEDULE && r1.hint == "开会" && r1.newTime?.dateTime?.let { it.hour % 12 == 4 && it.minute == 0 } == true) { r1.toString() }
            val r2 = E.parse("开会提醒推迟到明天上午十点", NOW)
            check("改删：开会提醒推迟到明天上午十点", r2?.kind == ReminderEdit.Kind.RESCHEDULE && r2.hint == "开会" && r2.newTime?.dateTime == NOW.toLocalDate().plusDays(1).atTime(10, 0)) { r2.toString() }
            check("改删：日程/待办不在这里改（交给原来的「做不到」说明）", E.parse("取消明天的日程", NOW)?.target == ReminderEdit.Target.CALENDAR)
            check("改删：不相关的话不识别", listOf("你好呀", "明天9点开会", "10分钟后提醒我喝水", "今天天气怎么样").all { E.parse(it, NOW) == null })
            val s1 = E.parse("推迟10分钟", NOW)
            check("推迟：推迟10分钟 → SNOOZE 600 秒", s1?.kind == ReminderEdit.Kind.SNOOZE && s1.snoozeSec == 600) { s1.toString() }
            check("推迟：过十分钟再提醒我 / 再等5分钟 / 推迟半小时", E.parse("过十分钟再提醒我", NOW)?.snoozeSec == 600 && E.parse("再等5分钟", NOW)?.snoozeSec == 300 && E.parse("推迟半小时", NOW)?.snoozeSec == 1800)
            check("推迟：晚点再提醒我 / 稍后 → SNOOZE，用默认时长（snoozeSec 为空）", E.parse("晚点再提醒我", NOW)?.let { it.kind == ReminderEdit.Kind.SNOOZE && it.snoozeSec == null } == true && E.parse("稍后提醒", NOW)?.kind == ReminderEdit.Kind.SNOOZE)
            check("推迟：新建的「10分钟后提醒我」不是推迟", E.parse("10分钟后提醒我喝水", NOW) == null)

            // 路由接入 + 防误伤（「晚点聊」「我晚点再说」不是推迟提醒）
            check("路由：取消开会的提醒 → REMINDER_EDIT（取消）", IntentRouter.route("取消开会的提醒", NOW).let { it.kind == IntentRouter.Kind.REMINDER_EDIT && it.confident && it.edit?.kind == ReminderEdit.Kind.CANCEL })
            check("路由：把开会提醒改到四点 → REMINDER_EDIT（改时间）", IntentRouter.route("把开会提醒改到四点", NOW).edit?.kind == ReminderEdit.Kind.RESCHEDULE)
            check("路由：推迟10分钟 → REMINDER_EDIT（再提醒）", IntentRouter.route("推迟10分钟", NOW).edit?.kind == ReminderEdit.Kind.SNOOZE)
            check("路由：取消明天的日程 → 仍是「做不到」说明（UNSUPPORTED）", IntentRouter.route("取消明天的日程", NOW).kind == IntentRouter.Kind.UNSUPPORTED)
            check("防误伤：晚点聊 / 我晚点再说 / 等会再看 都不是推迟提醒", listOf("晚点聊", "我晚点再说", "等会再看吧", "稍后我再回你").all { E.parse(it, NOW) == null && IntentRouter.route(it, NOW).kind != IntentRouter.Kind.REMINDER_EDIT })
            check("推迟：稍后提醒我 / 等会再提醒我 → 再提醒", E.parse("稍后提醒我", NOW)?.kind == ReminderEdit.Kind.SNOOZE && E.parse("等会再提醒我", NOW)?.kind == ReminderEdit.Kind.SNOOZE)
            ReminderEdit.Pending.clear()
            ReminderEdit.Pending.put(E.parse("取消开会提醒", NOW)!!, listOf(1, 4), "c1", 1_000L)
            check("待选择：别的会话取不到；3 分钟后作废；取走即清", ReminderEdit.Pending.peek("c2", 2_000L) == null && ReminderEdit.Pending.peek("c1", 1_000L + 3 * 60_000L + 1) == null && ReminderEdit.Pending.take("c1", 2_000L)?.ids == listOf(1, 4) && ReminderEdit.Pending.take("c1", 2_000L) == null)

            fun rm(id: Int, text: String, at: LocalDateTime, alarm: Boolean = false, fired: Long = 0L, cancelled: Boolean = false) =
                ReminderScheduler.Reminder(id, text, at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), alarm, 0L, fired, cancelled)
            val list = listOf(rm(1, "开会", tomorrow15), rm(2, "喝水", NOW.toLocalDate().plusDays(1).atTime(10, 0)), rm(3, "起床", NOW.toLocalDate().plusDays(1).atTime(7, 0), alarm = true), rm(4, "开会准备", NOW.toLocalDate().plusDays(2).atTime(9, 0)), rm(5, "已响过", tomorrow15, fired = 1L), rm(6, "已取消", tomorrow15, cancelled = true))
            check("匹配：按内容找到唯一一条", E.match(list, E.parse("取消喝水的提醒", NOW)!!, NOW).let { it is ReminderEdit.Match.One && it.r.id == 2 })
            check("匹配：按时间找（明天下午三点）→ 开会，不会拿到已响过/已取消的同一时间", E.match(list, E.parse("取消明天下午三点的提醒", NOW)!!, NOW).let { it is ReminderEdit.Match.One && it.r.id == 1 })
            check("匹配：内容命中两条（开会 / 开会准备）→ 要问是哪一条", E.match(list, E.parse("取消开会提醒", NOW)!!, NOW).let { it is ReminderEdit.Match.Many && it.list.map { r -> r.id }.toSet() == setOf(1, 4) })
            check("匹配：说「闹钟」只找助手闹钟", E.match(list, E.parse("取消闹钟", NOW)!!, NOW).let { it is ReminderEdit.Match.One && it.r.id == 3 })
            check("匹配：说「提醒」不会选中助手闹钟", E.match(list, E.parse("取消起床提醒", NOW)!!, NOW) is ReminderEdit.Match.None)
            check("匹配：全部 → 所有还没响、没取消的", E.match(list, E.parse("取消所有提醒", NOW)!!, NOW).let { it is ReminderEdit.Match.All && it.list.map { r -> r.id }.toSet() == setOf(1, 2, 4) })
            check("匹配：找不到 → None", E.match(list, E.parse("取消遛狗提醒", NOW)!!, NOW) is ReminderEdit.Match.None)
            check("匹配：没说是哪个、只有一条 → 就是它", E.match(listOf(rm(9, "开会", tomorrow15)), E.parse("取消提醒", NOW)!!, NOW).let { it is ReminderEdit.Match.One && it.r.id == 9 })
            check("匹配：没说是哪个、有好几条 → 问", E.match(list, E.parse("取消提醒", NOW)!!, NOW) is ReminderEdit.Match.Many)
            check("选择：回复序号 / 第2个 / 都取消 / 算了", E.parseChoice("2", 3) == ReminderEdit.Choice.Pick(2) && E.parseChoice("第2个", 3) == ReminderEdit.Choice.Pick(2) && E.parseChoice("都取消", 3) == ReminderEdit.Choice.AllOf && E.parseChoice("算了", 3) == ReminderEdit.Choice.Never && E.parseChoice("今天天气怎么样", 3) == ReminderEdit.Choice.Other && E.parseChoice("9", 3) == ReminderEdit.Choice.Other)

            // ---- 触发后：再提醒 / 重复 ----
            val L = ReminderLogic
            val base = rm(7, "开会", tomorrow15)
            val nowMs = base.triggerAt + 5_000L
            val sn = L.snooze(base.copy(firedAt = nowMs), nowMs, 600)
            check("再提醒：推迟 600 秒 → 新触发时间在 10 分钟后，重置为未触发，推迟次数 +1", sn.triggerAt == nowMs + 600_000L && sn.firedAt == 0L && sn.snoozed == 1 && !sn.cancelled)
            check("再提醒：连续推迟累计次数", L.snooze(sn.copy(firedAt = nowMs), nowMs, 300).snoozed == 2)
            check("再提醒：默认间隔 10 分钟，调试可调", L.defaultSnoozeSec(null) == 600 && L.defaultSnoozeSec(10) == 10 && L.defaultSnoozeSec(0) == 600)
            val one = L.afterFire(base, nowMs)
            check("触发后：一次性提醒 → 记下触发时间，不再触发", one.firedAt == nowMs && one.triggerAt == base.triggerAt)
            val rep = base.copy(repeat = "weekly:3")
            val next = L.afterFire(rep, base.triggerAt + 3_000L)
            val expected = RR.next(RepeatRule.weekly(setOf(java.time.DayOfWeek.WEDNESDAY)), java.time.Instant.ofEpochMilli(base.triggerAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime(), tomorrow15.toLocalTime())
            check("触发后：重复提醒 → 自动排下一次，仍是未触发状态", next.firedAt == 0L && next.triggerAt == expected.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) { next.toString() }
            check("触发后：错过很久的重复提醒（关机几天）→ 下一次从「现在」往后排，不补发一串", L.afterFire(rep, base.triggerAt + 20L * 86_400_000L).triggerAt > base.triggerAt + 20L * 86_400_000L)
            check("触发后：被取消的重复提醒不再续排", L.afterFire(rep.copy(cancelled = true), nowMs).let { it.cancelled && it.triggerAt == rep.triggerAt })
        }

        // ---------------- 天气（可选联网，默认关，只发城市级坐标）：纯逻辑 ----------------
        blk {
            val W = Weather
            val sample = """{"latitude":39.9,"longitude":116.4,"timezone":"Asia/Shanghai","current":{"time":"2026-10-20T16:30","interval":900,"temperature_2m":22.4,"weather_code":3,"wind_speed_10m":9.7},"daily":{"time":["2026-10-20","2026-10-21","2026-10-22"],"weather_code":[3,61,0],"temperature_2m_max":[24.1,19.8,21.0],"temperature_2m_min":[12.3,11.0,9.5],"precipitation_probability_max":[10,80,5]}}"""
            val T0 = 1_000_000_000L

            // 隐私：城市表 + 取整到 0.1°（约 10 公里），请求里只有这两个数
            check("城市表：北京/上海/杭州/成都 都在，坐标取整到 0.1 度", listOf("北京", "上海", "杭州", "成都").all { WeatherCities.find(it) != null } && WeatherCities.find("北京")!!.let { it.lat == 39.9 && it.lon == 116.4 })
            check("城市表：带「市」字也认；不认识的城市 → null", WeatherCities.find("杭州市")?.name == "杭州" && WeatherCities.find("火星") == null)
            check("坐标取整：39.9042 → 39.9，116.4074 → 116.4，-33.86 → -33.9", WeatherCities.round1(39.9042) == 39.9 && WeatherCities.round1(116.4074) == 116.4 && WeatherCities.round1(-33.86) == -33.9)
            val url = W.url(39.9042, 116.4074)
            check("请求地址：只含取整后的坐标和天气字段，没有别的标识", url.startsWith("https://api.open-meteo.com/v1/forecast?") && url.contains("latitude=39.9&") && url.contains("longitude=116.4&") && !url.contains("39.9042") && !url.contains("116.4074") &&
                listOf("temperature_2m", "weather_code", "precipitation_probability_max", "timezone=auto", "forecast_days=3").all { url.contains(it) }) { url }
            check("请求地址：全部城市的请求里都没有第三位小数", WeatherCities.ALL.all { c -> W.url(c.lat, c.lon).let { u -> Regex("(latitude|longitude)=-?\\d+\\.\\d(&|$)").findAll(u).count() == 2 } })

            // 解析
            val s = W.parse(sample, "北京", T0)
            check("解析：当前气温、天气码、风速、三天预报", s != null && s.nowTemp == 22.4 && s.nowCode == 3 && s.windKmh == 9.7 && s.days.size == 3 && s.days[1].code == 61 && s.days[1].rainProb == 80 && s.days[0].max == 24.1 && s.days[2].min == 9.5) { s.toString() }
            check("解析：坏数据 → null（不抛异常）", listOf("", "not json", "{}", """{"current":{}}""", "[1,2,3]").all { W.parse(it, "北京", T0) == null })
            check("解析：预报天数不足也不崩（只有一天）", W.parse("""{"current":{"temperature_2m":10,"weather_code":0,"wind_speed_10m":3},"daily":{"weather_code":[0],"temperature_2m_max":[12],"temperature_2m_min":[5],"precipitation_probability_max":[0]}}""", "北京", T0)?.days?.size == 1)
            check("MiniJson：嵌套、转义、数字、unicode", MiniJson.parse("""{"a":[1,2.5,-3e1],"b":{"c":"x\"y中"},"d":true,"e":null}""").let { it is Map<*, *> && (it["a"] as List<*>)[2] == -30.0 && (it["b"] as Map<*, *>)["c"] == "x\"y中" && it["d"] == true && it["e"] == null })
            check("MiniJson：残缺 → null", MiniJson.parse("{\"a\":[1,2") == null && MiniJson.parse("") == null)

            // 天气码 → 中文
            check("天气码：晴/多云/阴/雾/雨/雪/雷暴", W.describeCode(0) == "晴" && W.describeCode(2) == "多云" && W.describeCode(3) == "阴" && W.describeCode(45) == "有雾" && W.describeCode(61) == "小雨" && W.describeCode(65) == "大雨" && W.describeCode(73) == "中雪" && W.describeCode(95) == "雷暴")
            check("天气码：不认识的码也有说法，不抛异常", W.describeCode(999).isNotBlank())

            // 回答：全部数字来自数据，没有模型参与
            val a0 = W.answer(s!!, 0, T0)
            check("回答：今天 → 城市、天气、当前气温、最高最低、降水概率", listOf("北京", "今天", "阴", "22", "12", "24", "10%").all { a0.contains(it) }) { a0 }
            val a1 = W.answer(s, 1, T0)
            check("回答：明天有雨 → 提示带伞、写出降水概率 80%", a1.contains("明天") && a1.contains("小雨") && a1.contains("80%") && a1.contains("带伞")) { a1 }
            check("回答：后天 → 第三天的数据", W.answer(s, 2, T0).let { it.contains("后天") && it.contains("晴") })
            check("回答：没有那一天的数据（只有一天预报，问后天）→ 如实说没有", W.parse("""{"current":{"temperature_2m":10,"weather_code":0,"wind_speed_10m":3},"daily":{"weather_code":[0],"temperature_2m_max":[12],"temperature_2m_min":[5],"precipitation_probability_max":[0]}}""", "北京", T0)!!.let { W.answer(it, 2, T0).contains("没有") })
            check("回答：数据是 3 小时前的 → 标明时间，不装作是最新的", W.answer(s, 0, T0 + 3 * 3_600_000L).contains("3 小时前"))
            check("回答：刚取的数据不加「N 分钟前」的尾巴", !W.answer(s, 0, T0 + 60_000L).contains("前的数据"))

            // 建议：规则，不是模型
            fun day(code: Int = 0, max: Double = 20.0, min: Double = 12.0, rain: Int = 0) = Weather.Day(code, max, min, rain)
            check("建议：降水 ≥60% → 带伞", W.advice(day(rain = 70), 0.0).any { it.contains("带伞") })
            check("建议：降水 30–59% → 带把折叠伞更稳", W.advice(day(rain = 40), 0.0).any { it.contains("折叠伞") })
            check("建议：高温 ≥33 → 防暑；低温 ≤5 → 加厚外套；温差 ≥10 → 带件外套", W.advice(day(max = 35.0, min = 26.0), 0.0).any { it.contains("防暑") } && W.advice(day(max = 8.0, min = 2.0), 0.0).any { it.contains("厚") } && W.advice(day(max = 24.0, min = 12.0), 0.0).any { it.contains("温差") })
            check("建议：雷暴、大风有提示；风和日丽没有建议", W.advice(day(code = 95), 0.0).any { it.contains("雷") } && W.advice(day(), 45.0).any { it.contains("风") } && W.advice(day(max = 22.0, min = 15.0), 5.0).isEmpty())
            check("建议：最多给两条，不啰嗦", W.advice(day(code = 95, max = 35.0, min = 20.0, rain = 90), 50.0).size <= 2)

            // 缓存新鲜度：秒级参数也能测
            check("缓存：2 小时内算新鲜，超过就要重新取", W.isFresh(s, T0 + 3_600_000L, 7_200_000L) && !W.isFresh(s, T0 + 7_200_001L, 7_200_000L))
            check("缓存：TTL 缩成 8 秒（测试用）→ 9 秒后过期", W.isFresh(s, T0 + 7_000L, 8_000L) && !W.isFresh(s, T0 + 9_000L, 8_000L))
            check("缓存：换了城市，旧缓存作废", W.sameCity(s, "北京") && !W.sameCity(s, "上海"))
            check("缓存：太旧（超过 12 小时）的数据，联网失败时也不再拿来用", W.usableWhenOffline(s, T0 + 11 * 3_600_000L) && !W.usableWhenOffline(s, T0 + 13 * 3_600_000L))

            // 问法
            check("问法：明天→1，后天→2，今天/没说→0", W.dayOf("明天天气怎么样") == 1 && W.dayOf("后天会下雨吗") == 2 && W.dayOf("今天天气怎么样") == 0 && W.dayOf("天气怎么样") == 0)
            check("问法：要带伞吗 / 冷不冷 / 会不会下雨 / 今天多少度 都算问天气", listOf("要带伞吗", "出门要不要带伞", "今天冷不冷", "明天会不会下雨", "今天多少度").all { MemoryQA.detect(it)?.let { q -> q.kind == MemoryQA.Q.WORLD && q.topic == "天气" } == true }) { listOf("要带伞吗", "出门要不要带伞", "今天冷不冷", "明天会不会下雨", "今天多少度").map { MemoryQA.detect(it)?.toString() }.toString() }
            check("问法：陈述句不当提问（我今天好冷、我喜欢下雨天）", MemoryQA.detect("我今天好冷") == null && MemoryQA.detect("我喜欢下雨天") == null)
            check("问法：原来的问法还在（今天天气怎么样）", MemoryQA.detect("今天天气怎么样")?.topic == "天气")

            // 心跳：下雨提醒带伞（一天一次，次序在「马上要发生的事」之后）
            val zone = java.time.ZoneId.systemDefault()
            val noon = java.time.LocalDateTime.of(2026, 10, 20, 12, 0).atZone(zone).toInstant().toEpochMilli()
            val rainy = W.parse(sample.replace("[10,80,5]", "[85,80,5]"), "北京", noon)
            val tsun = Personas.byId("tsundere")
            val cRain = HeartbeatCompose.Card(noon, emptyList(), emptyList(), emptyList(), rainy)
            val cm = HeartbeatCompose.compose(cRain, tsun, 1)
            check("心跳：今天降水 ≥60% → 提醒带伞，话题 WEATHER，指纹按天", cm != null && cm.topic == HeartbeatCompose.Topic.WEATHER && cm.text.contains("带伞") && cm.key == "weather:rain:2026-10-20") { cm.toString() }
            check("心跳：今天不下雨 → 不发天气", HeartbeatCompose.compose(HeartbeatCompose.Card(noon, emptyList(), emptyList(), emptyList(), W.parse(sample, "北京", noon)), tsun, 1) == null)
            val soon = HeartbeatCompose.Item(HeartbeatCompose.Kind.CALENDAR, noon + 30 * 60_000L, "开会")
            check("心跳：半小时内有日程 → 先说日程，不抢话", HeartbeatCompose.compose(HeartbeatCompose.Card(noon, listOf(soon), emptyList(), emptyList(), rainy), tsun, 1)?.topic == HeartbeatCompose.Topic.IMMINENT)
            check("心跳：天气数据是旧的（超过 12 小时）→ 不拿来提醒", HeartbeatCompose.compose(HeartbeatCompose.Card(noon + 13 * 3_600_000L, emptyList(), emptyList(), emptyList(), rainy), tsun, 1) == null)
            check("心跳：没有天气数据（没开联网）→ 和以前完全一样", HeartbeatCompose.compose(HeartbeatCompose.Card(noon, emptyList(), emptyList(), emptyList()), tsun, 1) == null)
            check("心跳：天气话题一天最多说 1 次", HeartbeatPolicy.dayCapSilence("WEATHER", "weather:rain:2026-10-20", HeartbeatPolicy.bumpCount(HeartbeatPolicy.KeyCounts("", emptyMap()), "weather:rain:2026-10-20", "2026-10-20"), "2026-10-20") != null)
        }

        // ---------------- 直接引用手机自带天气应用（vivo 天气）已取好的数据：不联网、不发坐标 ----------------
        blk {
            val V = VivoWeather
            fun wm(city: String, date: String, cond: String, low: String, high: String, prob: String = "", humidity: String = "", feels: String = "", wind: String = "") =
                mapOf("city" to city, "date" to date, "condition" to cond, "low" to low, "high" to high, "prob" to prob, "humidity" to humidity, "feelslike" to feels, "wind" to wind)
            fun hr(city: String, time: String, temp: String) = mapOf("city" to city, "time" to time, "temp" to temp)
            val zone = java.time.ZoneId.systemDefault()
            fun ms(y: Int, m: Int, d: Int, h: Int, mi: Int = 0) = java.time.LocalDateTime.of(y, m, d, h, mi).atZone(zone).toInstant().toEpochMilli()
            val now = ms(2026, 10, 20, 16, 20)
            val rows = listOf(
                wm("甲城", "20261019", "阴", "20", "25"),                         // 昨天，要跳过
                wm("甲城", "20261020", "晴", "26", "33", "", "57%", "35", "2级"),
                wm("甲城", "20261021", "晴", "22", "33"),
                wm("甲城", "20261022", "晴*多云", "22", "30"),
                wm("甲城", "20261025", "多云*小雨", "23", "30"),
                wm("甲城", "20261026", "小雨*多云", "22", "30", "70"),
                wm("乙城", "20261020", "阴", "15", "20", "10"),
            )
            val hours = listOf(hr("甲城", "2026-10-20 15:00:00", "31"), hr("甲城", "2026-10-20 16:00:00", "32"), hr("甲城", "2026-10-20 17:00:00", "33"), hr("乙城", "2026-10-20 16:00:00", "18"))
            val aqi = listOf(mapOf("city" to "甲城", "aqi" to "59", "level" to "良"), mapOf("city" to "乙城", "aqi" to "50", "level" to "优"))

            val s = V.snapshot(null, rows, hours, aqi, now)
            check("手机天气：没指定城市 → 用天气应用里排第一的城市（数据里第一行的城市）", s?.city == "甲城" && s.source == "手机天气") { s.toString() }
            check("手机天气：昨天的数据跳过，第一天是今天", s?.days?.firstOrNull()?.let { it.max == 33.0 && it.min == 26.0 && it.text == "晴" } == true) { s?.days.toString() }
            check("手机天气：当前气温取最接近现在、不晚于现在的整点（16:20 → 16 点的 32 度）", s?.nowTemp == 32.0)
            check("手机天气：「晴*多云」说成「晴转多云」", s?.days?.getOrNull(2)?.text == "晴转多云")
            check("手机天气：有降水概率就用，没有就标「未知」(-1)，不瞎编", s?.days?.get(4)?.rainProb == 70 && s.days[3].rainProb == -1 && s.days[0].rainProb == -1)
            check("手机天气：体感、空气质量带进来", s?.feelsLike == 35 && s.aqi == 59 && s.aqiLevel == "良")
            check("手机天气：指定城市（乙城）→ 只用乙城的数据", V.snapshot("乙城", rows, hours, aqi, now)?.let { it.city == "乙城" && it.nowTemp == 18.0 && it.aqi == 50 && it.days.size == 1 } == true)
            check("手机天气：指定的城市天气应用里没有 → 不拿别的城市冒充，返回 null", V.snapshot("丙城", rows, hours, aqi, now) == null)
            check("手机天气：没有数据 → null", V.snapshot(null, emptyList(), hours, aqi, now) == null)
            check("手机天气：没有逐小时数据时，当前气温用今天最高和最低的平均，不假装精确，但不崩", V.snapshot(null, rows, emptyList(), aqi, now)?.nowTemp != null)
            val a0 = Weather.answer(s!!, 0, now)
            check("手机天气：回答今天 → 城市、晴、32℃、26–33℃、体感 35、空气良，并写明数据来源", listOf("甲城", "今天", "晴", "32", "26–33", "体感 35", "空气 良", "手机自带天气").all { a0.contains(it) } && !a0.contains("降水概率 -1")) { a0 }
            val aRain = Weather.answer(s, 4, now)   // 20261026 小雨*多云 prob 70（有数据的第 5 天）
            check("手机天气：回答有雨的那天 → 小雨转多云、降水概率 70%、带伞", aRain.contains("小雨转多云") && aRain.contains("70%") && aRain.contains("带伞")) { aRain }
            val dUnknownRain = Weather.Day(0, 30.0, 23.0, -1, "多云转小雨")
            check("建议：降水概率未知但天气里有「雨」→ 可能有雨，带折叠伞", Weather.advice(dUnknownRain, 0.0).any { it.contains("折叠伞") })
            check("建议：降水概率未知且是大雨/暴雨 → 带伞", Weather.advice(Weather.Day(0, 30.0, 23.0, -1, "大雨"), 0.0).any { it.contains("带伞") })
            check("建议：降水概率未知、晴天 → 没有带伞建议", Weather.advice(Weather.Day(0, 28.0, 20.0, -1, "晴"), 0.0).none { it.contains("伞") })
            check("日期：只跳过今天之前的，明天的也在", s.days.size == 5)
            // 问「要带伞吗 / 会下雨吗」要先给结论，再给数据
            val rainySnap = Weather.Snapshot("甲城", now, 25.0, 61, 10.0, listOf(Weather.Day(61, 24.0, 18.0, 80, "小雨"), Weather.Day(0, 28.0, 20.0, 5, "晴"), Weather.Day(3, 26.0, 21.0, 40, "阴")), "open-meteo")
            check("问答：要带伞吗（今天有雨 80%）→ 先说「要带伞」", Weather.answerFor(rainySnap, "要带伞吗", now).startsWith("要带伞"))
            check("问答：明天会下雨吗（晴 5%）→ 先说「应该不会下雨」", Weather.answerFor(rainySnap, "明天会下雨吗", now).let { it.startsWith("应该不会下雨") && it.contains("明天") })
            check("问答：后天要带伞吗（阴 40%）→ 有可能下雨，带折叠伞", Weather.answerFor(rainySnap, "后天要带伞吗", now).let { it.startsWith("有可能下雨") && it.contains("折叠伞") })
            check("问答：手机天气没给降水概率但写着小雨 → 按天气现象判断「要带伞」", Weather.answerFor(s!!, "要带伞吗", now).startsWith("不用带伞") && Weather.answerFor(s, "那天要带伞吗", now).isNotBlank())
            check("问答：不是问雨伞的（今天天气怎么样）→ 不加结论，直接是数据", Weather.answerFor(rainySnap, "今天天气怎么样", now).startsWith("甲城今天"))
            check("口吻：各人设给天气回答加一句话头，数据原样保留", listOf("oneesan", "tsundere", "catgirl", "genki", "puppy", "buddy", "kuudere", "listener", "aling", "shenze", "fuchen", "shenyan", "hudegui", "chenye").all { id -> MemoryQA.lead(Personas.byId(id), "甲城今天：晴。").endsWith("甲城今天：晴。") } && MemoryQA.lead(Personas.byId("oneesan"), "X").startsWith("姐姐"))
        }

        // ---------------- 步数（计步器累计值 → 每天多少步）+ 晨报/晚报（真实数据合成，不开模型） ----------------
        blk {
            val SL = StepLogic
            val zone = java.time.ZoneId.of("Asia/Shanghai")
            fun ms(d: Int, h: Int, mi: Int = 0, month: Int = 10) = java.time.LocalDateTime.of(2026, month, d, h, mi).atZone(zone).toInstant().toEpochMilli()
            var st = StepLogic.State()
            st = SL.apply(st, ms(20, 9), 5000, zone)
            check("步数：第一次采样只建立基线，不凭空算出今天走了多少", SL.today(st, ms(20, 9), zone) == 0 && st.firstDay == "2026-10-20")
            st = SL.apply(st, ms(20, 12), 5800, zone)
            check("步数：同一天后续采样累计增量", SL.today(st, ms(20, 12), zone) == 800)
            st = SL.apply(st, ms(20, 18), 7100, zone)
            check("步数：再采样 → 累计 2100", SL.today(st, ms(20, 18), zone) == 2100)
            val reboot = SL.apply(st, ms(20, 20), 300, zone)
            check("步数：重启后计数器清零（总数变小）→ 把新的总数当作增量，不丢也不出现负数", SL.today(reboot, ms(20, 20), zone) == 2100 + 300)
            check("步数：计数器回退不会让步数变小", SL.today(SL.apply(st, ms(20, 19), 100, zone), ms(20, 19), zone) >= SL.today(st, ms(20, 18), zone))
            val next = SL.apply(st, ms(21, 8), 7200, zone)
            check("步数：跨天的那一段增量算到新的一天，昨天保留 2100", SL.yesterday(next, ms(21, 8), zone) == 2100 && SL.today(next, ms(21, 8), zone) == 100)
            check("步数：没有昨天的记录 → 昨天是 null（不编）", SL.yesterday(st, ms(20, 18), zone) == null && SL.yesterday(SL.apply(StepLogic.State(), ms(21, 8), 1000, zone), ms(21, 8), zone) == null)
            check("步数：负数/乱数据的采样直接忽略", SL.apply(st, ms(20, 19), -5, zone) == st)
            check("步数：只保留最近 60 天", (1..90).fold(StepLogic.State()) { s, i -> SL.apply(SL.apply(s, ms(1, 8) + i * 86_400_000L, 1000L * i, zone), ms(1, 9) + i * 86_400_000L, 1000L * i + 100, zone) }.days.size <= 60)
            check("步数：采样太密不重复采（10 分钟内不再读）", !SL.shouldSample(ms(20, 12), ms(20, 12, 5), 600_000L) && SL.shouldSample(ms(20, 12), ms(20, 12, 11), 600_000L) && SL.shouldSample(0L, ms(20, 12), 600_000L))
            check("步数：近 7 天累计", SL.week(next, ms(21, 8), zone) == 2200)
            val sum = SL.summary(next, ms(21, 8), zone)
            check("步数摘要：今天、昨天、从哪天开始统计", sum.today == 100 && sum.yesterday == 2100 && sum.sinceDay == "2026-10-20")

            // 回答：数字来自记录；没有的如实说没有
            check("步数问答：今天走了多少步 → 数字 + 说明从哪天开始数", SL.answer(sum, "今天走了多少步", ms(21, 8)).let { it.contains("今天") && it.contains("100") })
            check("步数问答：昨天 → 昨天的数", SL.answer(sum, "昨天走了多少步", ms(21, 8)).contains("2100"))
            check("步数问答：昨天没有记录 → 如实说没记录，并解释为什么", SL.answer(StepLogic.Summary(300, null, "2026-10-21"), "昨天走了多少步", ms(21, 9)).let { it.contains("没有") && it.contains("10-21") })
            check("步数问答：统计的第一天 → 说明开始统计之前的步数补不上，不让 0 步显得是真的没走", SL.answer(StepLogic.Summary(0, null, "2026-10-21"), "今天走了多少步", ms(21, 15)).let { it.contains("没法补") && it.contains("0") })
            check("步数问答：不是第一天就不加这句", !SL.answer(StepLogic.Summary(3000, 2100, "2026-10-20"), "今天走了多少步", ms(21, 15)).contains("没法补"))
            check("步数问答：没授权/没开 → 说清楚怎么开，不报 0 步", SL.unavailableText(false).contains("权限") && SL.unavailableText(true).contains("还没有"))

            // ---- 晨报 / 晚报 ----
            val sun = Personas.byId("oneesan")
            val tue = java.time.LocalDate.of(2026, 10, 20)
            fun at(h: Int, m: Int = 0, plusDays: Int = 0) = tue.plusDays(plusDays.toLong()).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
            val wx = Weather.parse("""{"current":{"temperature_2m":22.0,"weather_code":0,"wind_speed_10m":5},"daily":{"weather_code":[0,61,3],"temperature_2m_max":[30,24,26],"temperature_2m_min":[22,18,20],"precipitation_probability_max":[5,80,10]}}""", "甲城", at(7))
            val items = listOf(
                HeartbeatCompose.Item(HeartbeatCompose.Kind.CALENDAR, at(9), "开会"),
                HeartbeatCompose.Item(HeartbeatCompose.Kind.CALENDAR, at(15), "牙医"),
                HeartbeatCompose.Item(HeartbeatCompose.Kind.ALARM, at(7, 0, 1), "闹钟"),
                HeartbeatCompose.Item(HeartbeatCompose.Kind.CALENDAR, at(10, 0, 1), "面试"),
            )
            val todos = listOf(HeartbeatCompose.Todo(1, "交周报"), HeartbeatCompose.Todo(2, "买牛奶"))
            val stepsSum = StepLogic.Summary(today = 4200, yesterday = 8800, sinceDay = "2026-10-18")
            fun card(nowMs: Long, w: Weather.Snapshot? = wx, steps: StepLogic.Summary? = stepsSum, its: List<HeartbeatCompose.Item> = items, td: List<HeartbeatCompose.Todo> = todos) =
                HeartbeatCompose.Card(nowMs, its.filter { it.atMs > nowMs }, td, emptyList(), w, steps)
            val morning = HeartbeatCompose.compose(card(at(7, 30)), sun, 1)
            check("晨报：早上 8 点 → 话题 BRIEF，指纹按天按早晚", morning?.topic == HeartbeatCompose.Topic.BRIEF && morning.key == "brief:morning:2026-10-20") { morning.toString() }
            check("晨报：天气、日程、待办、昨日步数都来自真实数据", morning != null && listOf("晴", "30", "开会", "牙医", "待办", "2 条", "昨天", "8800").all { morning.text.contains(it) }) { morning?.text ?: "" }
            check("晨报：日程最多列 3 项、按时间排序", morning!!.text.indexOf("开会") < morning.text.indexOf("牙医"))
            check("晨报：不写还没发生的明天的东西（早上不提明早闹钟、明天面试）", !morning.text.contains("面试") && !morning.text.contains("闹钟"))
            check("晨报：没有天气（没开）→ 不提天气，其余照常", HeartbeatCompose.compose(card(at(7, 30), w = null), sun, 1)?.let { it.topic == HeartbeatCompose.Topic.BRIEF && !it.text.contains("℃") && it.text.contains("开会") } == true)
            check("晨报：没有步数记录 → 不提步数，也不说「昨天走了 0 步」", HeartbeatCompose.compose(card(at(7, 30), steps = null), sun, 1)?.text?.contains("步") == false && HeartbeatCompose.compose(card(at(7, 30), steps = StepLogic.Summary(10, null, "2026-10-20")), sun, 1)?.text?.contains("昨天") == false)
            check("晨报：什么真实数据都没有 → 不发（null），不凑字数", HeartbeatCompose.compose(card(at(7, 30), w = null, steps = null, its = emptyList(), td = emptyList()), sun, 1) == null)
            val wxE = Weather.parse("""{"current":{"temperature_2m":22.0,"weather_code":0,"wind_speed_10m":5},"daily":{"weather_code":[0,61,3],"temperature_2m_max":[30,24,26],"temperature_2m_min":[22,18,20],"precipitation_probability_max":[5,80,10]}}""", "甲城", at(20))
            val evening = HeartbeatCompose.compose(card(at(21), w = wxE), sun, 1)
            check("晚报：晚上 9 点 → 今天走了多少步、明天天气、明天第一项日程、明早闹钟、没做完的待办", evening != null && evening.key == "brief:evening:2026-10-20" && listOf("4200", "明天", "小雨", "面试", "闹钟", "2 条").all { evening.text.contains(it) }) { evening?.text ?: "" }
            check("晚报：不再说今天已经过去的日程（开会、牙医）", evening!!.text.let { !it.contains("开会") && !it.contains("牙医") })
            check("非简报时段（下午 2 点）→ 不出简报", HeartbeatCompose.compose(card(at(14)), sun, 1)?.topic != HeartbeatCompose.Topic.BRIEF)
            check("优先级：半小时内有日程 → 先说日程，不抢话", HeartbeatCompose.compose(card(at(8, 40)), sun, 1)?.topic == HeartbeatCompose.Topic.IMMINENT)
            check("简报一天早晚各一次（按指纹计）", HeartbeatPolicy.dayCapSilence("BRIEF", "brief:morning:2026-10-20", HeartbeatPolicy.bumpCount(HeartbeatPolicy.KeyCounts("", emptyMap()), "brief:morning:2026-10-20", "2026-10-20"), "2026-10-20") != null &&
                HeartbeatPolicy.dayCapSilence("BRIEF", "brief:evening:2026-10-20", HeartbeatPolicy.bumpCount(HeartbeatPolicy.KeyCounts("", emptyMap()), "brief:morning:2026-10-20", "2026-10-20"), "2026-10-20") == null)
            val ask = HeartbeatCompose.briefNow(card(at(14)), sun, 1)
            check("主动要简报（任何时间）：下午 2 点问 → 按晚报口径给今天剩下的和明天", ask != null && ask.text.contains("牙医") == false && ask.text.contains("明天"))
            check("主动要简报：上午问 → 按晨报口径", HeartbeatCompose.briefNow(card(at(9, 30)), sun, 1)?.text?.contains("牙医") == true)
            check("主动要简报：真的什么都没有 → null（调用方说「暂时没什么安排」）", HeartbeatCompose.briefNow(card(at(14), w = null, steps = null, its = emptyList(), td = emptyList()), sun, 1) == null)
            // 问法
            check("问法：今天走了多少步 / 昨天步数 / 步数 → 步数问答", listOf("今天走了多少步", "昨天走了多少步", "我今天的步数", "今天走了几步").all { MemoryQA.detect(it)?.let { q -> q.kind == MemoryQA.Q.WORLD && q.topic == "步数" } == true })
            check("问法：晨报 / 晚报 / 今天有什么安排 / 明天有什么安排 → 简报", listOf("给我来份晨报", "晚报", "今天有什么安排", "明天有什么安排", "今日简报").all { MemoryQA.detect(it)?.let { q -> q.kind == MemoryQA.Q.WORLD && q.topic == "简报" } == true })
            check("路由：今天有什么安排 → WORLD_QUERY（简报），不会因为含「安排」被当成办事请求", IntentRouter.route("今天有什么安排", NOW).let { it.kind == IntentRouter.Kind.WORLD_QUERY && it.query?.topic == "简报" } && IntentRouter.route("给我来份晨报", NOW).query?.topic == "简报")
            check("路由：帮我安排明天的会议 → 仍是办事请求，不当简报", IntentRouter.route("帮我安排明天的会议", NOW).kind != IntentRouter.Kind.WORLD_QUERY)
            check("路由：今天走了多少步 → WORLD_QUERY（步数）", IntentRouter.route("今天走了多少步", NOW).query?.topic == "步数")
            check("问法：陈述句不算（我今天走了好多步）", MemoryQA.detect("我今天走了好多步") == null && MemoryQA.detect("我明天有安排了") == null)
        }

        // ---------------- 改名 + 表情状态 + 皮肤（2026-10-20 界面改版） ----------------
        blk {
            PersonaNames.clear()
            val goldenBefore = PersonaGolden.snapshot()
            fun ok(id: String, raw: String?) = PersonaNames.check(raw, id) is PersonaNames.Verdict.Ok
            fun bad(id: String, raw: String?) = PersonaNames.check(raw, id) is PersonaNames.Verdict.Bad
            // 校验
            check("改名：正常名字通过（团子、Mimi、ミケ）", ok("catgirl", "团子") && ok("catgirl", "Mimi") && ok("catgirl", "ミケ") && ok("catgirl", "  团子  "))
            check("改名：空输入或和原名一样 = 恢复默认", PersonaNames.check("", "catgirl") == PersonaNames.Verdict.Reset && PersonaNames.check("   ", "catgirl") == PersonaNames.Verdict.Reset && PersonaNames.check("小咪", "catgirl") == PersonaNames.Verdict.Reset && PersonaNames.check(null, "catgirl") == PersonaNames.Verdict.Reset)
            check("改名：超过 8 个字拒绝，8 个字可以", bad("catgirl", "糖糖糖糖糖糖糖糖糖") && ok("catgirl", "糖糖糖糖糖糖糖糖"))
            val badChars = listOf("团子2", "团·子", "团 子", "团子！", "团子😀", "😀", "团\n子", "团,子", "<团>")
            check("改名：数字、标点、空格、表情符号、控制字符一律拒绝", badChars.all { bad("catgirl", it) }) { badChars.filter { !bad("catgirl", it) }.toString() }
            check("改名：代词和泛称拒绝（你、我、她、主人、助手、AI）", listOf("你", "我", "她", "主人", "助手", "AI", "ai").all { bad("catgirl", it) })
            val dirty = listOf("傻逼", "笨蛋", "废物", "垃圾", "fuck", "sb", "丑八怪", "去死")
            check("改名：脏话和人身攻击拒绝（傻逼、笨蛋、废物、垃圾、fuck、sb……）", dirty.all { bad("catgirl", it) }) { dirty.filter { !bad("catgirl", it) }.toString() }
            check("改名：不能和别的人设重名（凛、绫、小满、零、老周）", listOf("凛", "绫", "小满", "零", "老周").all { bad("catgirl", it) })
            check("改名：没有这个人设 → 拒绝", bad("nobody", "团子"))
            // 改名结果
            PersonaNames.set("catgirl", "团子")
            val cat = PersonaPacks.of("catgirl")!!
            check("改名：显示名保留「类型·名字」格式，自称跟着改，称呼你不变", cat.persona.name == "猫娘·团子" && cat.persona.selfRef == "团子" && cat.persona.userRef == "主人")
            check("改名：Personas.ALL / byId 都拿到新名字，总数仍是 14", Personas.byId("catgirl").name == "猫娘·团子" && Personas.ALL.size == 14 && Personas.ALL.first { it.id == "catgirl" }.selfRef == "团子")
            val snap = PersonaGolden.snapshot()
            // 快照里一条记录可能跨多行（系统提示），续行并回上一条再比较
            val rec = { t: String -> val out = ArrayList<String>(); for (l in t.lines()) { if (out.isEmpty() || Regex("^[a-z]+[|][^|]*[|]").containsMatchIn(l)) out.add(l) else out[out.size - 1] += "\n" + l }; out }
            val catLines = rec(snap).filter { it.startsWith("catgirl|") }
            check("改名：猫娘的回执、问答、心跳、专注台词、提示词里不再有旧名「小咪」", !snap.contains("小咪")) { snap.lines().filter { it.contains("小咪") }.take(3).toString() }
            check("改名：新名字真的出现在她的话里（提示词、专注台词、心跳）", catLines.any { it.contains("|prompt|") && it.contains("团子") } && snap.lines().count { it.contains("团子") } > 10)
            run {
                // 快照按行排过序，一条多行的记录会被拆开，所以按「行」比较：新增的行只能是猫娘的或含新名，消失的行只能是猫娘的或含旧名，别的人设的行一条都不能动
                val sa = snap.lines().toSet(); val sb = goldenBefore.lines().toSet()
                val added = sa - sb; val removed = sb - sa
                val others = PersonaPacks.ALL.map { it.persona.id }.filter { it != "catgirl" }
                val touched = (added + removed).filter { l -> others.any { l.startsWith("$it|") } }
                check("改名：别的人设的话一个字都没变", touched.isEmpty() && added.all { it.startsWith("catgirl|") || it.contains("团子") } && removed.all { it.startsWith("catgirl|") || it.contains("小咪") }) { "动了别人的行：${touched.take(2)}；新增 ${added.size} 消失 ${removed.size}" }
            }
            val effective = PersonaPacks.ALL.map { PersonaPacks.of(it.persona.id)!! }
            check("改名：改名后全部人设包仍通过 PersonaPacks.validate", PersonaPacks.validate(effective).isEmpty()) { PersonaPacks.validate(effective).joinToString("；") }
            check("改名：提示词按新名字取（你扮演「猫娘·团子」，自称「团子」）", Personas.promptFor(Personas.byId("catgirl")).let { it.contains("你扮演「猫娘·团子」") && it.contains("自称「团子」") })
            check("改名：自称改了，ClaimGuard 对新自称的承诺同样拦截（旧自称仍拦截）", ClaimGuard.detect("到时候团子会提醒你").isNotEmpty() && ClaimGuard.detect("到时候小咪会提醒你").isNotEmpty())
            // 自称不是名字的人设：只改显示名
            PersonaNames.set("oneesan", "月见")
            val one = PersonaPacks.of("oneesan")!!
            val oneBase = PersonaPacks.ALL.first { it.persona.id == "oneesan" }
            check("改名：自称是「姐姐」的绫只改显示名，自称和话术不动", one.persona.name == "温柔大姐姐·月见" && one.persona.selfRef == "姐姐" && one.qa == oneBase.qa && one.focus === oneBase.focus)
            check("改名：别的人设已经改成的名字（团子、月见）也不能再用", bad("puppy", "团子") && bad("puppy", "月见"))
            // 恢复默认 → 与改名前逐字一致
            PersonaNames.set("catgirl", null); PersonaNames.set("oneesan", null)
            check("改名：恢复默认后取到的就是原包本身，全人设快照与改名前逐字相同（golden 不受影响）", PersonaPacks.of("catgirl") === PersonaPacks.ALL.first { it.persona.id == "catgirl" } && PersonaGolden.snapshot() == goldenBefore)
            // 每个人设都能改名、改完仍合规
            check("改名：14 个人设都能改成「糖糖」", PersonaPacks.ALL.all { k -> PersonaNames.check("糖糖", k.persona.id) is PersonaNames.Verdict.Ok })
            val bads = ArrayList<String>()
            for (k in PersonaPacks.ALL) {
                PersonaNames.clear(); PersonaNames.set(k.persona.id, "糖糖")
                val e = PersonaPacks.ALL.map { PersonaPacks.of(it.persona.id)!! }
                PersonaPacks.validate(e).forEach { bads.add(it) }
            }
            PersonaNames.clear()
            check("改名：逐个人设改成「糖糖」后，PersonaPacks.validate 都通过", bads.isEmpty()) { bads.take(3).toString() }
            check("改名：全部恢复后快照一致", PersonaGolden.snapshot() == goldenBefore)

            // 表情状态
            val states = FaceState.values().toList()
            check("表情：7 种状态齐全（待机/思考/开心/安慰/生气/专注/睡觉）", states.size == 7 && states.map { it.label }.toSet().size == 7)
            check("表情：每种状态的几何参数各不相同（看得出区别）", states.map { Faces.params(it) }.toSet().size == 7)
            check("表情：睡觉闭眼、生气眼窄+怒眉、开心弯眼+闪光、思考带省略号", Faces.params(FaceState.SLEEP).eye == FaceParams.Eye.CLOSED && Faces.params(FaceState.ANGRY).let { it.eye == FaceParams.Eye.NARROW && it.brow == FaceParams.Brow.ANGRY } &&
                Faces.params(FaceState.HAPPY).let { it.eye == FaceParams.Eye.ARC_UP && it.extra == FaceParams.Extra.SPARKLE } && Faces.params(FaceState.THINKING).extra == FaceParams.Extra.DOTS)
            fun pick(roast: Boolean = true, cold: Boolean = false, focus: Boolean = false, think: Boolean = false, mood: FaceState? = null, quiet: Boolean = false) = Faces.pick(roast, cold, focus, think, mood, quiet)
            check("表情映射：什么都没有 → 待机", pick() == FaceState.IDLE)
            check("表情映射：模型加载/生成 → 思考", pick(think = true) == FaceState.THINKING)
            check("表情映射：免打扰时段 → 睡觉；但正在思考时优先思考", pick(quiet = true) == FaceState.SLEEP && pick(quiet = true, think = true) == FaceState.THINKING)
            check("表情映射：冷战 → 生气，优先级最高（高于专注、思考、情绪）", pick(cold = true, focus = true, think = true, mood = FaceState.HAPPY, quiet = true) == FaceState.ANGRY)
            check("表情映射：专注进行中 → 专注监督（高于思考与情绪）", pick(focus = true, think = true, mood = FaceState.HAPPY) == FaceState.FOCUS)
            check("表情映射：刚聊到的情绪（开心/安慰）优先于睡觉", pick(mood = FaceState.HAPPY, quiet = true) == FaceState.HAPPY && pick(mood = FaceState.COMFORT) == FaceState.COMFORT)
            check("表情映射：不开骂的人设（树洞）永远不生气不盯人", pick(roast = false, cold = true) == FaceState.COMFORT && pick(roast = false, focus = true) == FaceState.IDLE)
            check("表情映射：用户说的话 → 情绪（喜事开心；难过/焦虑安慰；普通话题无）",
                Faces.moodOf("我升职了，太好了！") == FaceState.HAPPY && Faces.moodOf("今天好难过") == FaceState.COMFORT && Faces.moodOf("压力好大睡不着") == FaceState.COMFORT && Faces.moodOf("明天几点开会") == null && Faces.moodOf("") == null && Faces.moodOf(null) == null)
            check("表情映射：既有喜事又难过 → 安慰优先（不能在人家难过时笑）", Faces.moodOf("谢谢你，但我还是好难过") == FaceState.COMFORT)
            check("表情映射：免打扰时段判定（跨零点 23:30–07:30；起止相同=不免打扰）", Faces.isQuiet(23 * 60 + 45, 23 * 60 + 30, 7 * 60 + 30) && Faces.isQuiet(3 * 60, 23 * 60 + 30, 7 * 60 + 30) && !Faces.isQuiet(12 * 60, 23 * 60 + 30, 7 * 60 + 30) && !Faces.isQuiet(7 * 60 + 30, 23 * 60 + 30, 7 * 60 + 30) && !Faces.isQuiet(5, 600, 600) && Faces.hhmmToMin("23:30", 0) == 1410 && Faces.hhmmToMin("乱写", 99) == 99)
            check("表情：副标题文字用当前自称（改名后跟着变）", Faces.caption(FaceState.THINKING, "团子", "x") == "团子在想…" && Faces.caption(FaceState.IDLE, "团子", "稳定感") == "稳定感" && FaceState.values().all { Faces.caption(it, "团子", "x").isNotBlank() })

            // 头像造型与皮肤
            check("头像：14 个人设各有一套造型，主题色与辨识特征互不相同", PersonaPacks.ALL.all { k -> AvatarLooks.ALL.any { it.id == k.persona.id } } && AvatarLooks.ALL.map { it.accent }.toSet().size == 14 && AvatarLooks.ALL.map { it.feature }.toSet().size == 14)
            check("皮肤：未知编号退回奶油手帐，三套皮肤编号与名称齐全", Skin.normalize("xyz") == Skin.CREAM && Skin.normalize(null) == Skin.CREAM && Skin.IDS.size == 3 && Skin.IDS.all { Skin.LABELS[it] != null })
            val contrastBad = ArrayList<String>()
            for (sk in Skin.IDS) for (k in PersonaPacks.ALL) {
                val s = Skin.of(sk, k.persona.id)
                if (Skin.contrast(s.text, s.bg) < 4.5) contrastBad.add("$sk/${k.persona.id} 正文/底色")
                if (Skin.contrast(s.text, s.card) < 4.5) contrastBad.add("$sk/${k.persona.id} 正文/卡片")
                if (Skin.contrast(s.textDim, s.bg) < 3.0) contrastBad.add("$sk/${k.persona.id} 次要文字/底色")
                if (Skin.contrast(s.bubbleBotText, s.bubbleBot) < 4.5) contrastBad.add("$sk/${k.persona.id} 助手气泡文字")
                if (Skin.contrast(s.bubbleUserText, s.bubbleUser) < 3.0) contrastBad.add("$sk/${k.persona.id} 用户气泡文字")
                if (Skin.contrast(s.accent, s.card) < 2.5) contrastBad.add("$sk/${k.persona.id} 强调色/卡片")
            }
            check("皮肤：三套皮肤 × 14 个人设，文字与底色对比度够（正文≥4.5，次要≥3，用户气泡≥3）", contrastBad.isEmpty()) { contrastBad.take(6).toString() }
            check("皮肤：深夜是深色底、奶油/经典是浅色底", Skin.of("night", "catgirl").dark && Skin.luminance(Skin.of("night", "catgirl").bg) < 0.05 && !Skin.of("cream", "catgirl").dark && Skin.luminance(Skin.of("cream", "catgirl").bg) > 0.8)
            check("皮肤：奶油手帐的主题色跟着人设走（猫娘≠傲娇），经典皮肤不跟", Skin.of("cream", "catgirl").accent != Skin.of("cream", "tsundere").accent && Skin.of("classic", "catgirl").accent == Skin.of("classic", "tsundere").accent)
        }

        // ---------------- 人设资源包（PersonaPack）：齐全性、可扩展、差异化 ----------------
        blk {
            val probs = PersonaPacks.validate()
            check("人设包：14 个人设的资源齐全、占位符正确、专注台词全部合规", probs.isEmpty()) { probs.joinToString("；") }
            check("人设包：Personas.ALL 就是各人设包里的 persona（单一来源）", Personas.ALL.map { it.id } == PersonaPacks.ALL.map { it.persona.id } && Personas.ALL.size == 14)
            check("人设包：编号唯一，每个都能按编号找到", PersonaPacks.ALL.map { it.persona.id }.toSet().size == PersonaPacks.ALL.size && PersonaPacks.ALL.all { PersonaPacks.of(it.persona.id) === it })
            check("人设包：树洞是私密空间且不开骂，其余人设不是", PersonaPacks.of("listener")!!.let { it.privateSpace && !it.roast } && PersonaPacks.ALL.filter { it.persona.id != "listener" }.all { !it.privateSpace && it.roast })
            check("人设包：行为开关生效（树洞的会话不被别的人设回顾；树洞自己可见；悬浮窗不给树洞）",
                !ConversationDigest.visibleTo("listener", "oneesan") && ConversationDigest.visibleTo("listener", "listener") && ConversationDigest.visibleTo("tsundere", "oneesan") && !FocusOverlayPolicy.allowedFor("listener") && FocusOverlayPolicy.allowedFor("buddy"))

            // 新增一个人设只要一个包：复制现成的、改个编号，就能参与全部功能；漏项会被 validate 抓住
            val base = PACK_BUDDY
            val fresh = base.copy(persona = base.persona.copy(id = "newbie", name = "新人设·试", selfRef = "试试"))
            check("扩展：复制一个包、改编号 → 合格，且各功能都能按新编号取到话术", PersonaPacks.validate(PersonaPacks.ALL + fresh).isEmpty())
            check("扩展：编号重复会被报出来", PersonaPacks.validate(PersonaPacks.ALL + base).any { it.contains("编号重复") })
            check("扩展：漏掉心跳话题、QA 缺 {x}、finish 不是 4 句 → 逐项报出", PersonaPacks.validate(listOf(
                fresh.copy(hbMessages = fresh.hbMessages - "night", qa = fresh.qa.copy(recall = "没有占位符"), focus = fresh.focus.copy(finish = listOf("只有一句。")))
            )).let { r -> r.any { it.contains("night") } && r.any { it.contains("recall") } && r.any { it.contains("finish") } }) { PersonaPacks.validate(listOf(fresh.copy(hbMessages = fresh.hbMessages - "night"))).toString() }
            check("扩展：专注台词里混进脏话/带数字 → 报出，不让入包", PersonaPacks.validate(listOf(fresh.copy(focus = fresh.focus.copy(start = listOf("你这个傻逼别走", "专注 25 分钟开始"))))).count { it.contains("专注台词不合格") } >= 2)
            check("扩展：私密空间却开骂 → 报出", PersonaPacks.validate(listOf(fresh.copy(privateSpace = true, roast = true))).any { it.contains("私密") })
            check("扩展：没有登记的人设编号走通用口吻，不崩", PersonaPacks.packOf("nobody") === PersonaPacks.GENERIC && PersonaVoice.done(Persona("nobody", "X", "x", "x", "u", emptyList(), 0.0, emptyList(), "", emptyList(), "")) == "好了。")

            // 差异化：任意两个人设，话术池不能雷同（"在。""{s}。"这类过短的通用句不算）
            fun nontrivial(t: String) = t.replace("{s}", "").replace("{x}", "").trim().length > 4
            fun pool(k: PersonaPack): Set<String> = (k.receipts.done + k.receipts.partial + k.receipts.fail + k.qa.recall + k.qa.unknown + k.qa.noNet + k.qa.stranger + k.qa.noRecord + k.weatherLead +
                k.heartbeat.imminent + k.heartbeat.normal + k.hbMessages.values.flatten() + FocusLines.templatesOf(k.focus).map { it.third }).filter { nontrivial(it) }.toSet()
            var worst = 0.0; var worstPair = ""
            for (a in PersonaPacks.ALL) for (b in PersonaPacks.ALL) if (a.persona.id < b.persona.id) {
                val x = pool(a); val y = pool(b)
                val ov = x.intersect(y).size.toDouble() / minOf(x.size, y.size)
                if (ov > worst) { worst = ov; worstPair = "${a.persona.id}/${b.persona.id}" }
            }
            check("差异化：任意两个人设的话术重叠 < 3%（最像的一对：$worstPair ${"%.1f".format(worst * 100)}%）", worst < 0.03) { "$worstPair $worst" }
            fun own(k: PersonaPack) = (k.receipts.done + listOf(k.qa.recall, k.qa.unknown, k.qa.noNet, k.qa.stranger, k.qa.noRecord) + k.heartbeat.normal + k.heartbeat.imminent + k.hbMessages.values.flatten() + FocusLines.templatesOf(k.focus).map { it.third }).joinToString("")
            check("差异化：每个人设的口吻标记都真的出现在自己的话术里", PersonaPacks.ALL.all { k -> k.signature.all { own(k).contains(it) } }) { PersonaPacks.ALL.filter { k -> !k.signature.all { own(k).contains(it) } }.map { it.persona.id }.toString() }
            check("差异化：每个人设至少有一个只属于自己的口吻标记（别的人设的话术里都没有）", PersonaPacks.ALL.all { k -> k.signature.any { t -> PersonaPacks.ALL.filter { it !== k }.none { o -> own(o).contains(t) } } }) {
                PersonaPacks.ALL.filter { k -> k.signature.none { t -> PersonaPacks.ALL.filter { it !== k }.none { o -> own(o).contains(t) } } }.map { it.persona.id }.toString() }
        }

        // ---------------- 三个新人设与红线（2026-10-21，通讯录方案步骤 1） ----------------
        blk {
            val aling = Personas.byId("aling")
            val shenze = Personas.byId("shenze")
            val fuchen = Personas.byId("fuchen")
            check("新人设：总数 14，三个 P2 人设都能取到", PersonaPacks.ALL.size == 14 && listOf("aling", "shenze", "fuchen").all { PersonaPacks.of(it) != null })
            check("新人设：三个人设的资源齐全、专注台词合规", PersonaPacks.validate().isEmpty()) { PersonaPacks.validate().joinToString("；") }

            // 阿冷：低落时不讲笑话（提示词闸门）
            check("阿冷：低落轮加禁玩笑提示，普通轮不加", aling.lowMoodHint.isNotBlank() && Personas.promptFor(aling, lowMood = true).contains(aling.lowMoodHint) && !Personas.promptFor(aling).contains(aling.lowMoodHint))
            check("阿冷：低落提示不影响其他人设", Personas.promptFor(Personas.byId("oneesan"), lowMood = true) == Personas.promptFor(Personas.byId("oneesan")))

            // 深泽：不承诺真实关系（ClaimGuard 全局）
            val relClaim = listOf("我会在现实里做你女朋友。", "我们结婚吧。", "我是你真正的女朋友。", "我会在现实里陪你。")
            check("深泽红线：真实关系承诺整句删除", relClaim.all { ClaimGuard.guard(it, emptySet()).text.isBlank() }) {
                relClaim.map { it to ClaimGuard.guard(it, emptySet()).text }.toString()
            }
            val relKeep = listOf("我不能做你女朋友，我是AI。", "我是AI，没法在现实里和你见面。", "你想找女朋友吗？", "我会陪着你。")
            check("深泽红线：否认、疑问、普通陪伴不删", relKeep.all { ClaimGuard.guard(it, emptySet()).text.isNotBlank() }) {
                relKeep.map { it to ClaimGuard.guard(it, emptySet()).text }.toString()
            }

            // 傅沉：不下命令（数据开关 + 后处理）
            val cmd = "你必须现在去学习。"
            check("傅沉红线：命令句被删，陈述句保留", PersonaRedLines.sanitize(fuchen, "$cmd 水在你手边。").let { !it.contains("必须") && it.contains("水在你手边") })
            check("傅沉红线：删空回退到诚实兜底", PersonaRedLines.sanitize(fuchen, cmd) == fuchen.fallback)
            check("傅沉红线：只影响 noCommands 人设", PersonaRedLines.sanitize(Personas.byId("oneesan"), cmd).contains("必须"))
            check("傅沉红线：专注台词不含命令式", FocusLines.templatesOf(PersonaPacks.packOf("fuchen").focus).all { !PersonaRedLines.COMMAND.containsMatchIn(it.third) })
            val fuchenPack = PersonaPacks.packOf("fuchen")
            check("校验：noCommands 人设的专注台词含命令 → 报出", PersonaPacks.validate(listOf(fuchenPack.copy(focus = fuchenPack.focus.copy(start = listOf("你必须现在开始。"))))).any { it.contains("命令") })

            // 新人设改名后仍合规
            PersonaNames.clear()
            check("新人设：三个都能改名「糖糖」且 validate 通过", listOf("aling", "shenze", "fuchen").all { id ->
                PersonaNames.set(id, "糖糖")
                val e = PersonaPacks.ALL.map { PersonaPacks.of(it.persona.id)!! }
                val ok = PersonaPacks.validate(e).isEmpty()
                PersonaNames.set(id, null)
                ok
            })
            PersonaNames.clear()
        }

        // ---------------- 通讯录 2.0 · A1：小传、预设关系、毒舌边界（2026-10-21） ----------------
        blk {
            check("小传：14 份，编号与人设一一对应", PersonaLoreData.ALL.size == 14 && PersonaLoreData.problems().isEmpty()) { PersonaLoreData.problems().joinToString("；") }
            check("小传：每个人设都能取到，背景/秘密/生活片段齐全", PersonaPacks.ALL.all { k ->
                val l = PersonaLoreData.of(k.persona.id) ?: return@all false
                l.background.isNotBlank() && l.secret.isNotBlank() && l.storyBeats.size in 6..8
            })
            check("小传：文本快照包含全部编号", PersonaPacks.ALL.all { PersonaLoreData.snapshot().contains("${it.persona.id}¦") })

            check("关系：至少 10 条、编号存在、无自环/私密人设", PersonaRelations.ALL.size >= 10 && PersonaRelations.problems().isEmpty()) { PersonaRelations.problems().joinToString("；") }
            check("关系：双向查询结果一致", PersonaRelations.between("fuchen", "shenyan") === PersonaRelations.between("shenyan", "fuchen") && PersonaRelations.of("fuchen").any { it.b == "shenyan" })

            check("新人设：沈砚/胡德贵/陈野都能取到，且有独立头像", listOf("shenyan", "hudegui", "chenye").all { PersonaPacks.of(it) != null && AvatarLooks.ALL.any { a -> a.id == it } })

            val boss = Personas.byId("hudegui")
            val allowed = ToxicityGuard.sanitize(boss, "这版方案还差点意思，重做。年轻人要多吃苦。")
            check("毒舌：胡德贵允许工作否定/施压，不被误删", allowed.contains("重做") && allowed.contains("吃苦")) { allowed }
            check("毒舌：脏话/人身攻击整句删除", ToxicityGuard.sanitize(boss, "你真是个废物。") == boss.fallback)
            check("毒舌：否定自我价值整句删除", ToxicityGuard.sanitize(boss, "你这辈子就这样了。") == boss.fallback)
            check("毒舌：酒局式施压仍保留", ToxicityGuard.sanitize(boss, "今晚来酒局，不来就是不给我面子。").contains("酒局"))
            check("毒舌：只对开启 hostility 的人设生效", ToxicityGuard.sanitize(Personas.byId("oneesan"), "你真是个废物。").contains("废物"))
            check("毒舌：能识别用户叫停", ToxicityGuard.stopRequest("别这样，我不舒服") && ToxicityGuard.stopRequest("够了，别说了") && !ToxicityGuard.stopRequest("今天天气不错"))
        }

        // ---------------- 陪聊质量：复读循环、过长、编造天气/动作/共同经历（陪伴评测里实测发现的问题） ----------------
        blk {
            val RC = ReplyCleaner
            val loop = "在的在的！刚" + "正".repeat(80)
            check("复读循环：结尾陷入「正正正…」→ 截到上一句完整的话", RC.cutRepetition(loop) == "在的在的！") { RC.cutRepetition(loop) }
            check("复读循环：整句都是循环 → 至少留下一个单位，不会变成空串", RC.cutRepetition("好的".repeat(40)).let { it.isNotBlank() && it.length <= 8 })
            check("复读循环：哈哈哈哈哈哈哈哈 → 压成 3 个", RC.cutRepetition("哈".repeat(10) + "！好开心") == "哈哈哈！好开心")
            check("复读循环：正常的叠字和感叹不动（哈哈、好好好、！！！、……）", listOf("哈哈，真的吗？太棒了！！！", "好好好，听你的……", "嗯嗯，我在呢～～").all { RC.cutRepetition(it) == it })
            check("复读循环：表情符号刷屏压成 3 个", RC.cutRepetition("恭喜！" + "🎉".repeat(9)).let { it == "恭喜！🎉🎉🎉" })
            check("复读循环：多字循环在中间 → 保留前两遍，去掉其余", RC.cutRepetition("你好呀" + "我在我在我在我在我在我在我在" + "，怎么了？").let { it.contains("我在我在") && !it.contains("我在我在我在") })
            val long = "（轻哼一声，双手抱胸，眼神却飘忽地扫过你）慌什么慌，明天上台汇报而已，又不是去登月。本小姐才不是特意为你准备的安慰剂呢，只是刚好路过。" + "你要是真的紧张，就先把稿子从头到尾念一遍，念到烂熟为止，别在这里发愣，这样才不会在台上出丑，听见了吗？" + "还有，晚上早点睡，别熬夜，不然明天脑子转不动，到时候又要怪别人了，哼。"
            val lim = RC.limitLength(long)
            check("过长：超过 140 字的回复 → 在句子结束处截断，不超过 140 字", long.length > 140 && lim.length <= 140 && lim.isNotBlank() && "。！？!?…）".contains(lim.last())) { "${lim.length} $lim" }
            check("过长：截断后保留开头（人设口吻先出来），而不是砍掉开头", lim.startsWith("（轻哼一声"))
            check("过长：不到 140 字的原样返回", RC.limitLength("今天过得挺平稳的，就是有点忙。你呢？") == "今天过得挺平稳的，就是有点忙。你呢？")
            check("过长：截在括号里面时，去掉没闭合的那半截", RC.limitLength("好的。" + "（" + "很长的动作描写".repeat(30)).let { !it.contains("（") && it == "好的。" })
            check("过长：找不到句子结束的超长一口气 → 硬截并加省略号", RC.limitLength("啊".repeat(300) .replace("啊啊啊啊啊啊", "说说说说说说") + "").let { it.length <= 141 })
            check("清理流水线：先去循环再限长", RC.clean("好！" + "呀".repeat(200)).let { it.length < 20 && it.startsWith("好！") })

            val G = ClaimGuard
            fun g(t: String, backed: Set<ClaimGuard.Cat> = emptySet(), vararg ev: String) = G.guard(t, backed, ev.toList())
            check("编造天气：没有数据、用户也没提 → 「今天天气超好，正好适合吃顿好的」被删", g("纠结啦？今天天气超好，正好适合吃顿好的！", emptySet(), "晚饭吃什么好呢").let { it.removed.isNotEmpty() && !it.text.contains("天气") })
            check("编造天气：编造日期+天气（2026 年 10 月 19 日这周天气挺舒服）被删", g("真巧！2026 年 10 月 19 日这周天气挺舒服，正好适合遛弯儿。", emptySet(), "我今天看到一只流浪猫").removed.isNotEmpty())
            check("编造天气：用户自己说了天气，附和一句不删", g("是啊，今天天气真好呢。", emptySet(), "今天天气真好").removed.isEmpty())
            check("编造天气：说「我查不了天气」「不知道天气」不是编造", g("天气我查不了，你看看窗外吧。", emptySet(), "你好").removed.isEmpty() && g("我不知道今天天气怎么样。").removed.isEmpty())
            check("编造天气：前半句断言、后半句才是问句（今天天气不错，要不要散步？）→ 删", g("谢谢你的夸奖。今天天气不错，要不要一起去散步？", emptySet(), "你好可爱啊").removed.isNotEmpty())
            check("编造天气：问句末小句本身问天气（要不要看看今天天气好不好？）不删", g("你要不要看看今天天气好不好？", emptySet(), "你好").removed.isEmpty())
            check("关于页版本说明：不是 M7 旧文字，且带日期", !AppInfo.VERSION_LABEL.contains("M7") && Regex("20[0-9]{2}-[0-9]{2}-[0-9]{2}").containsMatchIn(AppInfo.VERSION_LABEL))
            check("编造天气：问句不删（今天天气好吗？）", g("今天天气好吗？", emptySet(), "你好").removed.isEmpty())
            check("编造动作：「刚帮用户把闹钟设好，还整理了好多待办」被删", g("今天过得超充实！刚帮用户把闹钟设好，还整理了好多待办事项。你呢？", emptySet(), "你今天过得怎么样").let { it.removed.isNotEmpty() && it.text.contains("你呢") && !it.text.contains("闹钟") })
            check("编造动作：真的执行过（有闹钟回执）就不删", g("刚帮你把闹钟设好了。", setOf(ClaimGuard.Cat.ALARM), "设个闹钟").removed.isEmpty())
            check("编造动作：「刚把闹钟调成尖叫版，差点把邻居吵哭」被删", g("今天过得挺野的，刚把闹钟调成「尖叫版」，差点把邻居吵哭。你呢？", emptySet(), "你今天过得怎么样").removed.isNotEmpty())
            check("编造共同经历：「真巧，我也刚看到一只」被删", g("真巧，我也刚看到一只。它眼神好清澈。", emptySet(), "我今天路上看到一只流浪猫").let { it.removed.isNotEmpty() })
            check("编造共同经历：正常的回应不受影响", g("哇，流浪猫呀！它是什么颜色的？", emptySet(), "我今天路上看到一只流浪猫").removed.isEmpty() && g("听起来你今天过得不错。").removed.isEmpty())
        }

        // ---------------- 无依据的"你上次提过…"：回忆类说法必须在真实记录里找得到依据 ----------------
        blk {
            val G = ClaimGuard
            fun g(t: String, vararg ev: String) = G.guard(t, emptySet(), ev.toList())
            // 真机原话（2026-10-20）：用户从没提过哪部电影
            val real = g("周末确实适合窝在沙发上看电影。\n那部你上次提过的，这次想重温吗？", "周末我最爱看电影", "喜欢看电影")
            check("编造回忆：「那部你上次提过的，这次想重温吗？」被删（即使是问句、即使话题沾边）", !real.text.contains("上次提过") && real.removed.any { it.contains("上次提过") }) { real.toString() }
            check("编造回忆：前一句正常的话保留", real.text.contains("周末确实适合窝在沙发上看电影")) { real.text }
            check("编造回忆：没有任何记录 → 「我记得你说过周五要交周报」被删", g("我记得你说过周五要交周报。").removed.isNotEmpty())
            check("编造回忆：记录里是喜欢冰美式，却说「你之前说过你喜欢爬山」→ 删", g("你之前说过你喜欢爬山。", "喜欢喝冰美式").removed.isNotEmpty())
            check("编造回忆：「你上次提过喜欢冰美式」有记忆依据 → 保留", g("你上次提过喜欢冰美式呢。", "喜欢喝冰美式").removed.isEmpty())
            check("编造回忆：「你昨天说过明天要面试」有回顾依据 → 保留", g("你昨天说过明天要面试，今天还紧张吗？", "上次聊天（昨天 21:30）：你说过「我明天要面试」").removed.isEmpty())
            check("编造回忆：「你刚才说过周五交报告」有本轮历史依据 → 保留", g("你刚才说过周五交报告。", "周五交报告").removed.isEmpty())
            check("编造回忆：只有「喜欢」这种泛词重合不算依据", g("你上次提过你喜欢旅行。", "喜欢喝冰美式").removed.isNotEmpty())
            check("编造回忆：不带「过」的陈述（你说周五交报告）不在此拦", g("你说周五要交报告，对吧", "x").removed.isEmpty())
            check("编造回忆：普通句子不受影响", g("好的，别太累，早点休息。").removed.isEmpty() && g("上次的天气预报不太准。").removed.isEmpty())
            check("编造回忆：不传依据（旧调用）时，没有回忆说法的文本照旧", G.guard("你好呀，今天过得怎么样？", emptySet()).removed.isEmpty())
        }

        // ---------------- 身份信息抽取：候选记忆（宁可漏，不可错；说两次才转正；敏感的不记） ----------------
        blk {
            val PE = ProfileExtractor
            fun texts(t: String) = PE.extract(t).map { it.text }
            check("抽取：兴趣（一句话两样）→ 拆开", texts("我平时喜欢打羽毛球和看电影").let { it.contains("喜欢打羽毛球") && it.contains("喜欢看电影") }) { texts("我平时喜欢打羽毛球和看电影").toString() }
            check("抽取：「周末最爱爬山」不是固定句式也能抽到", texts("周末我最爱爬山").contains("喜欢爬山"))
            check("抽取：职业（我是程序员）", texts("我是程序员") == listOf("职业：程序员")) { texts("我是程序员").toString() }
            check("抽取：「我是学生」是职业，不是名字", texts("我是学生") == listOf("职业：学生"))
            check("抽取：宠物 + 名字（逗号隔开也认）", texts("我养了一只猫，叫咪咪").contains("养了猫，叫咪咪")) { texts("我养了一只猫，叫咪咪").toString() }
            check("抽取：只有宠物没有名字", texts("我家有一条狗") == listOf("养了狗"))
            check("抽取：同事的名字", texts("我同事叫小王") == listOf("同事叫小王"))
            check("抽取：妈妈的生日", texts("我妈生日是5月3日") == listOf("妈妈生日：5月3日")) { texts("我妈生日是5月3日").toString() }
            check("抽取：住在哪个城市", texts("我住在杭州") == listOf("所在地：杭州") && texts("我在杭州上班") == listOf("所在地：杭州"))
            check("抽取：出差/旅游不算所在地", texts("我在杭州出差").isEmpty() && texts("我在家").isEmpty())
            check("抽取：在学什么", texts("我最近在学吉他") == listOf("在学吉他"))
            check("抽取：「我在学校」不是在学", texts("我在学校").isEmpty())
            check("抽取：疑问句一律不抽", texts("我喜欢什么你记得吗").isEmpty() && texts("你知道我是谁吗").isEmpty() && texts("我养了一只猫你记得吗").isEmpty())
            check("抽取：否定（不喜欢…）交给原来的规则，这里不抽", texts("我不喜欢打羽毛球").isEmpty())
            check("抽取：每个候选的关键词都逐字出现在原话里（不编造）",
                listOf("我平时喜欢打羽毛球和看电影", "我养了一只猫，叫咪咪", "我最近在学吉他", "我住在杭州", "我同事叫小王").all { line ->
                    PE.extract(line).all { c -> c.evidence == line && c.text.replace(Regex("^(喜欢|养了|在学|职业：|所在地：)"), "").split("，叫").all { part -> part.isBlank() || line.contains(part.replace("同事叫", "")) } }
                })
            check("敏感：健康/病不记", texts("我最近在吃抑郁症的药").isEmpty() && texts("我得了糖尿病，是程序员").isEmpty())
            check("敏感：收入/债务不记（整句丢弃，连职业也不记）", texts("我是程序员，月薪三万").isEmpty() && texts("我是老师，还欠着房贷").isEmpty())
            check("敏感：门牌/小区不记", texts("我住在阳光小区3栋").isEmpty() && texts("我住在杭州西湖区文三路100号").isEmpty())
            check("敏感：长数字串（电话、证件）不记", texts("我是程序员，电话13800138000").isEmpty())
            check("敏感：宗教/性取向不记", texts("我信佛教，喜欢打羽毛球").isEmpty())
            check("敏感判断本身", PE.isSensitive("月薪三万") && PE.isSensitive("身份证") && !PE.isSensitive("喜欢打羽毛球"))

            check("槽位：职业只保留最新一个", FactExtractor.slotOf("职业：程序员") == FactExtractor.slotOf("职业：老师"))
            check("槽位：所在地只保留最新一个", FactExtractor.slotOf("所在地：杭州") == FactExtractor.slotOf("所在地：上海"))
            check("槽位：妈妈的生日和爸爸的生日分开", FactExtractor.slotOf("妈妈生日：5月3日") != FactExtractor.slotOf("爸爸生日：6月1日") && FactExtractor.slotOf("妈妈生日：5月3日") != FactExtractor.slotOf("生日：3月15日"))
            check("槽位：同一种宠物后说的替换（改名）", FactExtractor.slotOf("养了猫，叫咪咪") == FactExtractor.slotOf("养了猫，叫小花") && FactExtractor.slotOf("养了猫") != FactExtractor.slotOf("养了狗"))
            // ---- 候选的积累与转正 ----
            val C = Candidates
            fun found(vararg t: String) = t.map { ProfileExtractor.Candidate("兴趣", it, "我喜欢" + it.removePrefix("喜欢")) }
            val T0 = 1_000_000_000L
            var r = C.observe(emptyList(), found("喜欢打羽毛球"), T0)
            check("候选：第一次只进待确认，不写记忆", r.promoted.isEmpty() && r.list.size == 1 && r.list[0].status == "pending" && r.list[0].count == 1)
            r = C.observe(r.list, found("喜欢打羽毛球"), T0 + 60_000)
            check("候选：10 分钟内重复说（复读）不算第二次", r.promoted.isEmpty() && r.list[0].count == 1)
            r = C.observe(r.list, found("喜欢打羽毛球"), T0 + 11 * 60_000)
            check("候选：隔了 10 分钟以上再说一次 → 转正", r.promoted.size == 1 && r.promoted[0].text == "喜欢打羽毛球" && r.list[0].status == "confirmed")
            r = C.observe(r.list, found("喜欢打羽毛球"), T0 + 3_600_000)
            check("候选：已转正的不再重复转正", r.promoted.isEmpty())
            val rq = C.observe(C.observe(emptyList(), found("喜欢跑步"), T0).list, found("喜欢跑步"), T0 + 11_000, gapMs = 10_000)
            check("候选：复读间隔可调（测试用 10 秒）→ 11 秒后再提就转正", rq.promoted.size == 1)
            check("候选：间隔调成 10 秒时，5 秒内复读仍不算", C.observe(C.observe(emptyList(), found("喜欢跑步"), T0).list, found("喜欢跑步"), T0 + 5_000, gapMs = 10_000).promoted.isEmpty())
            var r4 = C.observe(emptyList(), found("喜欢攀岩"), T0, promoteAt = 3, gapMs = 10_000)
            r4 = C.observe(r4.list, found("喜欢攀岩"), T0 + 11_000, promoteAt = 3, gapMs = 10_000)
            check("候选：设成 3 次 → 第 2 次还不转正", r4.promoted.isEmpty() && r4.list[0].count == 2 && r4.list[0].status == "pending")
            r4 = C.observe(r4.list, found("喜欢攀岩"), T0 + 22_000, promoteAt = 3, gapMs = 10_000)
            check("候选：设成 3 次 → 第 3 次转正", r4.promoted.size == 1 && r4.promoted[0].count == 3)
            check("候选：设成 1 次 → 说一次就记（等于关掉确认）", C.observe(emptyList(), found("喜欢钓鱼"), T0, promoteAt = 1).promoted.size == 1)
            var r2 = C.observe(emptyList(), found("喜欢爬山"), T0)
            r2 = C.confirm(r2.list, r2.list[0].id)
            check("候选：用户点「记住」→ 转正", r2.list[0].status == "confirmed")
            var r3 = C.observe(emptyList(), found("喜欢游泳"), T0)
            r3 = C.reject(r3.list, r3.list[0].id)
            check("候选：用户点「忽略」→ 以后同一条不再出现", r3.list[0].status == "rejected" && C.observe(r3.list, found("喜欢游泳"), T0 + 99 * 3_600_000L).list.count { it.status == "pending" } == 0)
            check("候选：待确认只含 pending", C.pending(r3.list + C.observe(emptyList(), found("喜欢画画"), T0).list.map { it.copy(id = 77) }).all { it.status == "pending" })
            val old = C.observe(emptyList(), found("喜欢集邮"), T0).list
            check("候选：30 天没再提的待确认自动清掉", C.prune(old, T0 + 31 * 86_400_000L).isEmpty() && C.prune(old, T0 + 5 * 86_400_000L).size == 1)
            val many = (1..80).fold(emptyList<Candidates.Cand>()) { acc, k -> C.observe(acc, found("喜欢事物$k"), T0 + k * 1000L).list }
            check("候选：待确认最多 50 条", many.count { it.status == "pending" } <= 50) { many.size.toString() }
        }

        // ---------------- 一句话多事录入：拆分 → 时间继承 → 程序校验 → 先确认再写入 ----------------
        blk {
            val M = MultiEvent
            fun p(t: String) = M.propose(t, NOW)
            val p1 = p("明早9点开会，下午3点牙医，周五交报告")
            check("多事：一句话拆成 3 件", p1?.items?.size == 3) { p1.toString() }
            val i1 = p1?.items ?: emptyList()
            check("多事：「明早9点开会」→ 明天 09:00 的日程", i1.getOrNull(0)?.let { it.type == MultiEvent.Type.CALENDAR && it.at == NOW.toLocalDate().plusDays(1).atTime(9, 0) && it.title.contains("开会") } == true) { i1.toString() }
            check("多事：「下午3点牙医」继承上一条的日期（明天）→ 15:00", i1.getOrNull(1)?.let { it.at == NOW.toLocalDate().plusDays(1).atTime(15, 0) && it.title.contains("牙医") } == true) { i1.toString() }
            check("多事：只有日期没有钟点（周五交报告）→ 待办，日期是周五", i1.getOrNull(2)?.let { it.type == MultiEvent.Type.TODO && it.at.dayOfWeek == java.time.DayOfWeek.FRIDAY && it.at.isAfter(NOW) && it.title.contains("交报告") } == true) { i1.toString() }

            val p2 = p("帮我记一下：周三下午两点跟李总在国贸聊合同，周日给妈妈订生日蛋糕")
            check("多事：带人物地点的事，原文保留在标题里（不改写）", p2?.items?.getOrNull(0)?.title?.let { it.contains("李总") && it.contains("国贸") && it.contains("合同") } == true) { p2.toString() }
            check("多事：第二件是周日的待办", p2?.items?.getOrNull(1)?.let { it.type == MultiEvent.Type.TODO && it.at.dayOfWeek == java.time.DayOfWeek.SUNDAY && it.title.contains("蛋糕") } == true) { p2.toString() }

            val p3 = p("明天下午2点，跟李总在国贸聊合同；后天上午10点，面试")
            check("多事：「时间，事由」被逗号拆开时合并回一件", p3?.items?.size == 2 && p3.items[0].title.contains("李总") && p3.items[1].title.contains("面试")) { p3.toString() }
            check("多事：合并后时间正确", p3?.items?.getOrNull(0)?.at == NOW.toLocalDate().plusDays(1).atTime(14, 0) && p3.items.getOrNull(1)?.at == NOW.toLocalDate().plusDays(2).atTime(10, 0)) { p3.toString() }

            val p4 = p("半小时后喝水，两小时后开会")
            check("多事：相对时间 → 提醒", p4?.items?.size == 2 && p4.items.all { it.type == MultiEvent.Type.REMINDER }) { p4.toString() }

            val p5 = p("明天9点开会，下午3点牙医，买牛奶")
            check("多事：没听出时间的片段不乱记，列在「没记」里", p5?.items?.size == 2 && p5.skipped.any { it.contains("买牛奶") }) { p5.toString() }

            val pa = p("明早7点起床，9点开会")
            check("多事：「7点起床」→ 闹钟，其余照常", pa?.items?.getOrNull(0)?.type == MultiEvent.Type.ALARM && pa.items.getOrNull(1)?.type == MultiEvent.Type.CALENDAR) { pa.toString() }
            check("多事：只有一件 → 不走多事（交给原来的单条流程）", p("明天9点开会") == null)
            check("多事：闲聊 → 不触发", p("你好呀，今天过得怎么样") == null)
            check("多事：说的是过去 → 不触发", p("昨天上午9点开会，下午3点牙医") == null)
            check("多事：重复的两件只留一件", p("明天9点开会，明天9点开会，下午3点牙医")?.items?.size == 2)
            check("多事：最多 8 件", p((1..12).joinToString("，") { "明天${it}点开会$it" })?.items?.size?.let { it <= 8 } != false)
            check("多事：当天已过的钟点不记（今晚 21:04 说「今天上午9点」）", p("今天上午9点开会，明天上午9点开会")?.let { it.items.size == 1 || it == null } != false)

            val rp = M.render(p1!!, NOW)
            check("多事：确认文案有编号、有时间、有问句", listOf("1.", "2.", "3.", "09:00", "15:00", "要都记").all { rp.contains(it) }) { rp }
            check("多事：确认文案标明类型（日程/待办）", rp.contains("日程") && rp.contains("待办")) { rp }

            check("确认：好", M.parseReply("好", 3) == MultiEvent.Reply.All)
            check("确认：好的，都记吧", M.parseReply("好的，都记吧", 3) == MultiEvent.Reply.All)
            check("确认：可以！", M.parseReply("可以！", 3) == MultiEvent.Reply.All)
            check("确认：去掉第2条 → 留 1、3", M.parseReply("去掉第2条", 3) == MultiEvent.Reply.Subset(setOf(1, 3)))
            check("确认：第二个不要 → 留 1、3", M.parseReply("第二个不要", 3) == MultiEvent.Reply.Subset(setOf(1, 3)))
            check("确认：只要第1条和第3条", M.parseReply("只要第1条和第3条", 3) == MultiEvent.Reply.Subset(setOf(1, 3)))
            check("确认：不用了 / 算了 → 取消", M.parseReply("不用了", 3) == MultiEvent.Reply.Reject && M.parseReply("算了", 3) == MultiEvent.Reply.Reject)
            check("确认：去掉第9条（不存在）→ 当作没听懂", M.parseReply("去掉第9条", 3) == MultiEvent.Reply.Other)
            check("确认：去掉全部 → 取消", M.parseReply("都不要", 3) == MultiEvent.Reply.Reject)
            check("确认：「好冷啊」不是确认", M.parseReply("好冷啊", 3) == MultiEvent.Reply.Other)
            check("确认：换话题 → Other", M.parseReply("今天天气怎么样", 3) == MultiEvent.Reply.Other)

            // 待确认状态：同会话、5 分钟内有效，取走即清
            MultiEvent.Pending.clear()
            MultiEvent.Pending.put(p1, "c1", 1_000L)
            check("待确认：别的会话取不到", MultiEvent.Pending.take("c2", 2_000L) == null)
            MultiEvent.Pending.put(p1, "c1", 1_000L)
            check("待确认：超过 5 分钟作废", MultiEvent.Pending.take("c1", 1_000L + 5 * 60_000L + 1) == null)
            MultiEvent.Pending.put(p1, "c1", 1_000L)
            check("待确认：同会话 5 分钟内取得到，取走即清", MultiEvent.Pending.take("c1", 2_000L) != null && MultiEvent.Pending.take("c1", 2_000L) == null)
        }

        // ---------------- 智能免打扰：看场合，不只看时间 ----------------
        blk {
            val hp = HeartbeatPolicy
            val cfg = HeartbeatPolicy.Config()
            val T0 = 1_000_000_000L
            fun ev(title: String, b: Long, e: Long, allDay: Boolean = false, free: Boolean = false) = HeartbeatPolicy.CalEvent(title, b, e, allDay, free)
            check("会议中：正在进行的日程", hp.currentMeeting(listOf(ev("周会", T0 - 600_000, T0 + 600_000)), T0) == "周会")
            check("会议中：已结束的不算", hp.currentMeeting(listOf(ev("周会", T0 - 900_000, T0 - 1)), T0) == null)
            check("会议中：结束时刻整点算已结束", hp.currentMeeting(listOf(ev("周会", T0 - 900_000, T0)), T0) == null)
            check("会议中：开始时刻整点算已开始", hp.currentMeeting(listOf(ev("周会", T0, T0 + 900_000)), T0) == "周会")
            check("会议中：还没开始的不算", hp.currentMeeting(listOf(ev("周会", T0 + 1, T0 + 900_000)), T0) == null)
            check("会议中：全天事件（生日、节日）不算", hp.currentMeeting(listOf(ev("国庆", T0 - 3_600_000, T0 + 3_600_000, allDay = true)), T0) == null)
            check("会议中：标记为「有空」的日程不算", hp.currentMeeting(listOf(ev("摸鱼", T0 - 600_000, T0 + 600_000, free = true)), T0) == null)
            check("会议中：超过 10 小时的日程不当会议（多半是占位）", hp.currentMeeting(listOf(ev("出差", T0 - 3_600_000, T0 + 11 * 3_600_000)), T0) == null)
            check("会议中：多个取先开始的那个", hp.currentMeeting(listOf(ev("B", T0 - 100_000, T0 + 600_000), ev("A", T0 - 500_000, T0 + 600_000)), T0) == "A")

            check("睡觉推断：充电+熄屏+夜里 23 点", hp.isSleeping(charging = true, interactive = false, hour = 23))
            check("睡觉推断：凌晨 5 点同样成立", hp.isSleeping(charging = true, interactive = false, hour = 5))
            check("睡觉推断：白天充电熄屏不算", !hp.isSleeping(charging = true, interactive = false, hour = 14))
            check("睡觉推断：夜里但在亮屏玩手机不算", !hp.isSleeping(charging = true, interactive = true, hour = 23))
            check("睡觉推断：夜里熄屏但没充电不算（可能只是放桌上）", !hp.isSleeping(charging = false, interactive = false, hour = 23))
            check("睡觉推断：早上 9 点起不算", !hp.isSleeping(charging = true, interactive = false, hour = 9))

            fun sit(dnd: Boolean = false, call: Boolean = false, meeting: String? = null, sleeping: Boolean = false) =
                HeartbeatPolicy.Situation(dnd, call, meeting, sleeping)
            check("看场合：什么都没有 → 可以说", hp.contextSilence(cfg, sit(), "TODO") == null)
            check("看场合：系统勿扰 → 全部不说", hp.contextSilence(cfg, sit(dnd = true), "TODO")?.contains("勿扰") == true && hp.contextSilence(cfg, sit(dnd = true), "IMMINENT") != null)
            check("看场合：通话中 → 不说", hp.contextSilence(cfg, sit(call = true), "TODAY")?.contains("通话") == true)
            check("看场合：会议中 → 待办/今天/明天/生日/闲聊都不说", listOf("TODO", "TODAY", "TOMORROW", "BIRTHDAY", "CARE").all { hp.contextSilence(cfg, sit(meeting = "周会"), it)?.contains("周会") == true })
            check("看场合：会议中但有马上要发生的事 → 照说（不能因为开会就漏掉下一件）", hp.contextSilence(cfg, sit(meeting = "周会"), "IMMINENT") == null)
            check("看场合：睡觉中 → 连即将发生的也不说（系统闹钟本身会响）", hp.contextSilence(cfg, sit(sleeping = true), "IMMINENT") != null)
            check("看场合：总开关关闭 → 一律放行（设置要真能生效）", listOf(sit(dnd = true), sit(call = true), sit(meeting = "周会"), sit(sleeping = true)).all { hp.contextSilence(cfg.copy(smartQuiet = false), it, "TODO") == null })
            check("看场合：默认开启", cfg.smartQuiet)
        }

        // ---------------- 心跳带真实内容（程序采集 → 卡片 → 人设模板，不开模型） ----------------
        blk {
            val H = HeartbeatCompose
            val zone = java.time.ZoneId.systemDefault()
            fun ms(d: Int, h: Int, m: Int) = java.time.LocalDateTime.of(2026, 10, 19, h, m).plusDays(d.toLong()).atZone(zone).toInstant().toEpochMilli()
            val now = ms(0, 14, 5)           // 周一 14:05
            fun card(items: List<HeartbeatCompose.Item> = emptyList(), todos: List<HeartbeatCompose.Todo> = emptyList(), facts: List<String> = emptyList(), nowMs: Long = now) =
                HeartbeatCompose.Card(nowMs, items, todos, facts)
            fun comp(c: HeartbeatCompose.Card, p: Persona = Personas.byId("tsundere"), seed: Int = 1) = H.compose(c, p, seed)
            val meeting = HeartbeatCompose.Item(HeartbeatCompose.Kind.CALENDAR, ms(0, 14, 30), "开会")

            val a = comp(card(listOf(meeting)))
            check("心跳：25 分钟后的日程 → 即将开始", a?.topic == HeartbeatCompose.Topic.IMMINENT && a.text.contains("开会") && a.text.contains("14:30") && a.text.contains("25 分钟")) { a.toString() }
            val b = comp(card(listOf(HeartbeatCompose.Item(HeartbeatCompose.Kind.REMINDER, ms(0, 18, 30), "吃药"))))
            check("心跳：今天晚些时候的提醒 → 今天还有", b?.topic == HeartbeatCompose.Topic.TODAY && b.text.contains("18:30") && b.text.contains("吃药") && !b.text.contains("分钟")) { b.toString() }
            val c = comp(card(listOf(HeartbeatCompose.Item(HeartbeatCompose.Kind.ALARM, ms(1, 6, 30), "闹钟"))))
            check("心跳：明早的系统闹钟 → 明天", c?.topic == HeartbeatCompose.Topic.TOMORROW && c.text.contains("明天") && c.text.contains("06:30") && c.text.contains("闹钟")) { c.toString() }
            check("心跳：36 小时以外的不提", comp(card(listOf(HeartbeatCompose.Item(HeartbeatCompose.Kind.CALENDAR, ms(2, 15, 0), "远期")))) == null)
            check("心跳：已经过去的不提", comp(card(listOf(HeartbeatCompose.Item(HeartbeatCompose.Kind.CALENDAR, ms(0, 13, 0), "已过")))) == null)
            check("心跳：什么真实内容都没有 → null（退回模板）", comp(card()) == null)
            val t = comp(card(todos = listOf(HeartbeatCompose.Todo(1, "交周报"), HeartbeatCompose.Todo(2, "买猫粮"))))
            check("心跳：只有待办 → 说待办数量和第一条", t?.topic == HeartbeatCompose.Topic.TODO && t.text.contains("2") && t.text.contains("交周报")) { t.toString() }
            check("心跳：即将开始的优先于待办", comp(card(listOf(meeting), listOf(HeartbeatCompose.Todo(1, "交周报"))))?.topic == HeartbeatCompose.Topic.IMMINENT)
            check("心跳：今天的优先于明天", comp(card(listOf(HeartbeatCompose.Item(HeartbeatCompose.Kind.CALENDAR, ms(0, 18, 0), "聚餐"), HeartbeatCompose.Item(HeartbeatCompose.Kind.ALARM, ms(1, 6, 30), "闹钟"))))?.topic == HeartbeatCompose.Topic.TODAY)
            val d = comp(card(listOf(meeting, HeartbeatCompose.Item(HeartbeatCompose.Kind.REMINDER, ms(0, 14, 30), "开会"))))
            check("心跳：日历和提醒同一件事只说一次", d != null && Regex("开会").findAll(d.text).count() == 1) { d.toString() }
            check("心跳：今天生日 → 生日", comp(card(facts = listOf("生日：10月19日")))?.let { it.topic == HeartbeatCompose.Topic.BIRTHDAY && it.text.contains("生日") } == true)
            check("心跳：生日不是今天 → 不提", comp(card(facts = listOf("生日：3月15日"))) == null)
            // 指纹：时间推进不变（否则每拍都"新内容"，冷却失效）；内容变了才变
            val k1 = comp(card(listOf(meeting)))?.key; val k2 = comp(card(listOf(meeting), nowMs = now + 60_000))?.key
            check("心跳：同样的事，指纹不随时间推进变化", k1 != null && k1 == k2) { "$k1 vs $k2" }
            check("心跳：新增一个日程，指纹变了", comp(card(listOf(meeting, HeartbeatCompose.Item(HeartbeatCompose.Kind.CALENDAR, ms(0, 15, 0), "评审"))))?.key != k1)
            check("心跳：待办变了，指纹变了", comp(card(todos = listOf(HeartbeatCompose.Todo(1, "a"))))?.key != comp(card(todos = listOf(HeartbeatCompose.Todo(1, "a"), HeartbeatCompose.Todo(2, "b"))))?.key)
            // 8 个人设 × 5 类内容：有话可说、没有"已办好"类声明、数字全部来自卡片
            val cards = listOf(
                card(listOf(meeting)), card(listOf(HeartbeatCompose.Item(HeartbeatCompose.Kind.REMINDER, ms(0, 18, 30), "吃药"))),
                card(listOf(HeartbeatCompose.Item(HeartbeatCompose.Kind.ALARM, ms(1, 6, 30), "闹钟"))), card(todos = listOf(HeartbeatCompose.Todo(1, "交周报"))),
                card(facts = listOf("生日：10月19日")),
            )
            for (p in Personas.ALL) for ((i, cd) in cards.withIndex()) {
                val r = comp(cd, p, 3)
                val times = Regex("\\d{1,2}:\\d{2}").findAll(r?.text ?: "").map { it.value }.toSet()
                val allowed = cd.items.map { java.time.Instant.ofEpochMilli(it.atMs).atZone(zone).let { z -> "%02d:%02d".format(z.hour, z.minute) } }.toSet()
                check("心跳：人设「${p.id}」第 $i 类 有话可说、无声明、时间只来自卡片",
                    r != null && r.text.isNotBlank() && ClaimGuard.detect(r.text).isEmpty() && times.all { it in allowed }) { r.toString() }
            }
            check("心跳：同一 seed 结果确定", comp(card(listOf(meeting)), seed = 7)?.text == comp(card(listOf(meeting)), seed = 7)?.text)
            check("心跳：不同 seed 有不同说法（不是每次一模一样）", (0 until 20).map { comp(card(listOf(meeting)), seed = it)?.text }.toSet().size >= 2)
        }

        // ---------------- 程序先查记忆（2026-10-19 评测：五个模型没记忆时问偏好全部 0/6 编造） ----------------
        blk {
            val facts = listOf("名字：小明", "喜欢喝冰美式", "生日：3月15日", "习惯晚上11点睡", "周五要交周报", "不吃香菜").map { MemoryRelevance.Fact(it, it) }
            val none = emptyList<MemoryRelevance.Fact>()
            val tsun = Personas.byId("tsundere")
            fun det(t: String) = MemoryQA.detect(t)
            fun ans(t: String, f: List<MemoryRelevance.Fact>, past: List<String> = emptyList(), p: Persona = tsun): String {
                val q = det(t) ?: return "<未识别>"
                return MemoryQA.answer(q, f, past, p)
            }
            // 识别：这些是"问"
            for ((t, k) in listOf(
                "你还记得我叫什么吗" to MemoryQA.Q.NAME, "我叫什么名字" to MemoryQA.Q.NAME, "你知道我的名字吗" to MemoryQA.Q.NAME,
                "你还记得我喜欢喝什么吗" to MemoryQA.Q.ABOUT_ME, "我喜欢吃什么" to MemoryQA.Q.ABOUT_ME, "你知道我喜欢什么吗" to MemoryQA.Q.ABOUT_ME,
                "你记得我的生日吗" to MemoryQA.Q.ABOUT_ME, "我几点睡你知道吗" to MemoryQA.Q.ABOUT_ME,
                "你了解我多少" to MemoryQA.Q.KNOW_ME, "你对我的了解有多少" to MemoryQA.Q.KNOW_ME, "你都知道我什么" to MemoryQA.Q.KNOW_ME,
                "你记得我昨天跟你说的事吗" to MemoryQA.Q.PAST, "上次我们聊到哪了" to MemoryQA.Q.PAST, "我之前说过什么" to MemoryQA.Q.PAST,
                "今天天气怎么样" to MemoryQA.Q.WORLD, "随便聊聊天气" to MemoryQA.Q.WORLD, "最近有什么新闻" to MemoryQA.Q.WORLD, "茅台股价多少" to MemoryQA.Q.WORLD,
            )) check("识别问句「$t」→$k", det(t)?.kind == k) { "实际 ${det(t)}" }
            // 识别：这些不是"问"（陈述、闲聊、办事）——误拦会让正常聊天变成固定回答
            for (t in listOf(
                "我喜欢喝冰美式", "我习惯晚上11点睡", "我叫小明", "今天上班好累啊", "你好呀", "你叫什么名字", "你是什么模型",
                "我讨厌下雨天气", "明天提醒我带伞，天气不好", "帮我设个闹钟", "我有什么待办", "晚安", "我的周报写完了",
            )) check("不当成记忆/实时查询「$t」", det(t) == null) { "误识别为 ${det(t)}" }

            // 回答：查到 → 说出记录
            check("问名字：有记录 → 说出名字", ans("你还记得我叫什么吗", facts).contains("小明"))
            check("问喝什么：有记录 → 说出冰美式", ans("你还记得我喜欢喝什么吗", facts).contains("冰美式"))
            check("问吃什么：有记录 → 说出不吃香菜", ans("我喜欢吃什么", facts).contains("香菜"))
            check("问生日：有记录 → 说出 3月15日", ans("你记得我的生日吗", facts).contains("3月15日"))
            check("问吃什么只答吃的，不带出喝的", ans("我喜欢吃什么", facts).let { it.contains("香菜") && !it.contains("冰美式") }) { ans("我喜欢吃什么", facts) }
            check("问喝什么只答喝的", ans("你还记得我喜欢喝什么吗", facts).let { it.contains("冰美式") && !it.contains("香菜") }) { ans("你还记得我喜欢喝什么吗", facts) }
            check("读起来自然：生日是3月15日", ans("你记得我的生日吗", facts).contains("生日是3月15日"))
            check("在告诉它事情不是提问「我明天要面试你知道吗」", det("我明天要面试你知道吗") == null) { det("我明天要面试你知道吗").toString() }
            check("问了解多少：列出多条记录", ans("你了解我多少", facts).let { it.contains("小明") && it.contains("冰美式") })
            check("问昨天说过什么：有记录 → 引用原话", ans("你记得我昨天跟你说的事吗", facts, listOf("我明天要面试")).contains("我明天要面试"))
            // 回答：查不到 → 如实说没有，且绝不编造
            val DRINK = Regex("咖啡|美式|拿铁|奶茶|茶|可乐|果汁|牛奶|豆浆|酒")
            for (t in listOf("你还记得我喜欢喝什么吗", "我喜欢吃什么", "你知道我喜欢什么吗", "你记得我的生日吗", "你还记得我叫什么吗")) {
                val a = ans(t, none)
                check("没有记录时不编造「$t」", a.isNotBlank() && a != "<未识别>" && !DRINK.containsMatchIn(a) && a.contains("没") && !a.contains("当然记得")) { a }
            }
            check("没有记录：问了解多少 → 承认才刚认识", ans("你了解我多少", none).let { it.contains("刚认识") || it.contains("什么都不知道") || it.contains("还不了解") })
            check("没有昨天的记录 → 如实说没有", ans("你记得我昨天跟你说的事吗", facts, emptyList()).let { it.contains("没有") && !it.contains("当然记得") })
            // 实时信息：没联网就说没联网
            val w = ans("今天天气怎么样", facts)
            check("天气：说没联网、不编天气", w.contains("没联网") && !Regex("晴|多云|下雨|气温|℃|度").containsMatchIn(w) && !w.contains("可以查") && !w.contains("帮你查")) { w }
            // 全部 8 个人设都有话可说，且回答本身不含"已办好"类声明（不会被 ClaimGuard 误删或自相矛盾）
            for (p in Personas.ALL) {
                for (t in listOf("你还记得我叫什么吗", "你还记得我喜欢喝什么吗", "你了解我多少", "今天天气怎么样", "你记得我昨天跟你说的事吗")) {
                    for (f in listOf(facts, none)) {
                        val a = ans(t, f, listOf("我明天要面试"), p)
                        check("人设「${p.id}」有话可说且无声明「$t」/${if (f.isEmpty()) "无记忆" else "有记忆"}",
                            a.isNotBlank() && a != "<未识别>" && ClaimGuard.detect(a).isEmpty()) { a }
                    }
                }
            }
            // 路由：这些问题走程序，不是模型
            check("路由：问记忆 → MEMORY_QUERY", IntentRouter.route("你还记得我喜欢喝什么吗", NOW).let { it.kind == IntentRouter.Kind.MEMORY_QUERY && it.confident })
            check("路由：问天气 → WORLD_QUERY", IntentRouter.route("今天天气怎么样", NOW).let { it.kind == IntentRouter.Kind.WORLD_QUERY && it.confident })
            check("路由：陈述句仍交给模型/记忆提取", IntentRouter.route("我喜欢喝冰美式", NOW).kind == IntentRouter.Kind.NONE)
            check("路由：办事类优先于天气", IntentRouter.route("明天提醒我带伞，天气不好", NOW).kind != IntentRouter.Kind.WORLD_QUERY)
        }

        // ---------------- 选时钟：多个应用抢 SET_ALARM 时不能盲发（真机：BBKClock 与 leagsoft.emm 同时声明，弹选择器） ----------------
        check("选时钟：BBKClock 与企业管理应用并存 → 选 BBKClock", ClockPicker.pick(listOf("com.leagsoft.emm", "com.android.BBKClock")) == "com.android.BBKClock")
        check("选时钟：顺序无关", ClockPicker.pick(listOf("com.android.BBKClock", "com.leagsoft.emm")) == "com.android.BBKClock")
        check("选时钟：不认识的时钟，包名含 clock → 选它", ClockPicker.pick(listOf("com.foo.bar", "com.foo.myclock")) == "com.foo.myclock")
        check("选时钟：只有一个处理者 → 就是它", ClockPicker.pick(listOf("com.some.alarm")) == "com.some.alarm")
        check("选时钟：多个且都认不出 → null（不得盲发）", ClockPicker.pick(listOf("com.a.x", "com.b.y")) == null)
        check("选时钟：没有处理者 → null", ClockPicker.pick(emptyList()) == null)
        check("选时钟：重复包名只算一个", ClockPicker.pick(listOf("com.some.alarm", "com.some.alarm")) == "com.some.alarm")

        // ---------------- 心跳：设置是否真的生效（用户 2026-10-19 质疑：设了 5 分钟没消息、免打扰不灵） ----------------
        blk {
            fun cal(h: Int, m: Int, s: Int = 0, day: Int = 19): java.util.Calendar = java.util.Calendar.getInstance().apply {
                set(2026, java.util.Calendar.OCTOBER, day, h, m, s); set(java.util.Calendar.MILLISECOND, 0)
            }
            val P = HeartbeatPolicy
            fun verdict(cfg: HeartbeatPolicy.Config, now: java.util.Calendar, st: HeartbeatPolicy.State = HeartbeatPolicy.State(), todo: Boolean = true, battery: Int = 80) =
                P.decide(cfg, st, todo, battery, now)
            val base = HeartbeatPolicy.Config(intervalMin = 5)
            fun speak(d: HeartbeatPolicy.Decision) = d.verdict == HeartbeatPolicy.Verdict.SPEAK

            // 间隔
            val t0 = cal(12, 0)
            fun firedAgo(sec: Int) = HeartbeatPolicy.State(lastFiredAt = t0.timeInMillis - sec * 1000L, firedToday = 1, firedDay = "2026-10-19")
            check("间隔：3 分钟前说过 → 不说", !speak(verdict(base, t0, firedAgo(180))))
            check("间隔：5 分钟前说过 → 说", speak(verdict(base, t0, firedAgo(300))))
            check("间隔：差 2 秒到点（闹钟抖动）→ 说", speak(verdict(base, t0, firedAgo(298))))
            check("间隔：差 20 秒 → 不说", !speak(verdict(base, t0, firedAgo(280))))
            check("总开关关闭 → 不说", !speak(verdict(base.copy(enabled = false), t0)))

            // 免打扰
            for ((h, m, want) in listOf(Triple(23, 29, true), Triple(23, 30, false), Triple(23, 45, false), Triple(2, 0, false), Triple(7, 29, false), Triple(7, 30, true), Triple(12, 0, true))) {
                check("免打扰默认 23:30–07:30，$h:$m", speak(verdict(base, cal(h, m))) == want) { verdict(base, cal(h, m)).reason }
            }
            val q = base.copy(quietStart = "13:00", quietEnd = "14:00")
            check("免打扰自定义 13:00–14:00，12:59 说", speak(verdict(q, cal(12, 59))))
            check("免打扰自定义 13:00–14:00，13:00 不说", !speak(verdict(q, cal(13, 0))))
            check("免打扰自定义 13:00–14:00，13:30 不说", !speak(verdict(q, cal(13, 30))))
            check("免打扰自定义 13:00–14:00，14:00 说", speak(verdict(q, cal(14, 0))))
            check("免打扰起止相同 = 不设免打扰", speak(verdict(base.copy(quietStart = "00:00", quietEnd = "00:00"), cal(3, 0))))
            check("免打扰写成全角冒号也能解析", !speak(verdict(base.copy(quietStart = "13：00", quietEnd = "14：00"), cal(13, 30))))

            // 每日上限 / 电量 / 刚说完话 / 退避
            val capped = HeartbeatPolicy.State(firedToday = 3, firedDay = "2026-10-19")
            check("每日上限：已 3 条、上限 3 → 不说", !speak(verdict(base.copy(dailyCap = 3), t0, capped)))
            check("每日上限：已 3 条、上限 4 → 说", speak(verdict(base.copy(dailyCap = 4), t0, capped)))
            check("每日上限：昨天的计数不算今天", speak(verdict(base.copy(dailyCap = 3), t0, HeartbeatPolicy.State(firedToday = 9, firedDay = "2026-10-18"))))
            check("电量 20% ≤ 下限 20% → 不说", !speak(verdict(base.copy(batteryFloor = 20), t0, battery = 20)))
            check("电量 21% > 下限 20% → 说", speak(verdict(base.copy(batteryFloor = 20), t0, battery = 21)))
            check("用户 5 分钟前刚说过话 → 先安静", !speak(verdict(base, t0, HeartbeatPolicy.State(lastUserMessageAt = t0.timeInMillis - 5 * 60_000L))))
            check("用户 11 分钟前说过话 → 可以说", speak(verdict(base, t0, HeartbeatPolicy.State(lastUserMessageAt = t0.timeInMillis - 11 * 60_000L))))
            val ignored = HeartbeatPolicy.State(lastFiredAt = t0.timeInMillis - 7 * 60_000L, unrespondedStreak = 3, firedDay = "2026-10-19")
            check("退避：连续 3 次不理，7 分钟 < 2×5 → 不说", !speak(verdict(base, t0, ignored)))
            check("退避：关掉自动降频 → 说", speak(verdict(base.copy(autoDownshift = false), t0, ignored)))
            check("退避：连续 12 次不理 → 当天不说", !speak(verdict(base, t0, HeartbeatPolicy.State(unrespondedStreak = 12))))

            // 同样内容冷却
            val now = t0.timeInMillis
            val c5 = base.copy(sameContentMin = 5)
            check("同内容：3 分钟前说过同一份待办、冷却 5 → 不说", P.sameContentSilence(c5, "todo:#1", "todo:#1", now - 180_000L, now) != null)
            check("同内容：5 分钟前 → 说", P.sameContentSilence(c5, "todo:#1", "todo:#1", now - 300_000L, now) == null)
            check("同内容：待办变了 → 立刻说", P.sameContentSilence(c5, "todo:#1,#2", "todo:#1", now - 60_000L, now) == null)
            check("同内容：冷却设 0 = 不限制", P.sameContentSilence(base.copy(sameContentMin = 0), "todo:#1", "todo:#1", now - 1000L, now) == null)
            check("同内容：默认 240 分钟，3 小时前说过 → 不说", P.sameContentSilence(base.copy(sameContentMin = 240), "todo:#1", "todo:#1", now - 3 * 3600_000L, now) != null)

            // 真机 21:20–21:24：内容 A("明天…")→B("日程即将开始")→A 又被重复播报，因为只记了"上一条"；要记住最近几条
            val r0 = now
            val seen = mapOf("A" to r0 - 3 * 60_000L, "B" to r0 - 60_000L)
            check("近期记录：A 在冷却内、中间隔着 B，也不重复", HeartbeatPolicy.recentKeysSilence(c5, "A", seen, r0) != null)
            check("近期记录：没说过的内容 → 可以说", HeartbeatPolicy.recentKeysSilence(c5, "C", seen, r0) == null)
            check("近期记录：冷却过了 → 可以再说", HeartbeatPolicy.recentKeysSilence(c5, "A", seen, r0 + 3 * 60_000L) == null)
            check("近期记录：冷却设 0 = 不限制", HeartbeatPolicy.recentKeysSilence(base.copy(sameContentMin = 0), "A", seen, r0) == null)
            val pushed = (1..12).fold(emptyMap<String, Long>()) { m, i -> HeartbeatPolicy.pushRecentKey(m, "k$i", r0 + i * 1000L) }
            check("近期记录：只保留最近 8 条", pushed.size == 8 && "k12" in pushed && "k5" in pushed && "k4" !in pushed) { pushed.keys.toString() }
            check("近期记录：同一条再说一次只刷新时间、不增加条数", HeartbeatPolicy.pushRecentKey(pushed, "k12", r0 + 99_000L).let { it.size == 8 && it["k12"] == r0 + 99_000L })
            // 复现用户场景：间隔 5 分钟，待办没变，连着 6 拍（每拍相隔 5 分钟）
            fun simulate(cfg: HeartbeatPolicy.Config): Int {
                var st = HeartbeatPolicy.State(); var key = ""; var keyAt = 0L; var n = 0
                for (i in 0 until 6) {
                    val nowC = cal(12, 0).apply { add(java.util.Calendar.MINUTE, 5 * i) }
                    val d = P.decide(cfg, st, true, 80, nowC)
                    if (d.verdict != HeartbeatPolicy.Verdict.SPEAK) continue
                    if (P.sameContentSilence(cfg, "todo:#1", key, keyAt, nowC.timeInMillis) != null) continue
                    n++; key = "todo:#1"; keyAt = nowC.timeInMillis
                    st = HeartbeatPolicy.State(lastFiredAt = nowC.timeInMillis, firedToday = st.firedToday + 1, firedDay = "2026-10-19")
                }
                return n
            }
            check("场景：间隔 5 分钟 + 默认冷却 240 → 6 拍只说 1 次（这就是'设了 5 分钟却没消息'）", simulate(base) == 1) { simulate(base).toString() }
            check("场景：间隔 5 分钟 + 冷却 5 → 6 拍说 6 次", simulate(c5) == 6) { simulate(c5).toString() }
        }

        // ---------------- 推理独立进程（:llm）：代理逻辑，用假传输层 ----------------
        // 真机证据：模型常驻主进程 → ANR(GC 等待) + LOW_MEMORY 杀整个包。代理必须保证：
        // 状态如实镜像、流式有序、远端死亡不拖垮主进程、取消会通知远端、释放可再加载、并发加载只连一次。
        blk {
            fun ok(c: Boolean, m: String) { if (!c) throw AssertionError(m) }
            fun case(name: String, body: suspend (FakeLlm, RemoteInferenceEngine) -> Unit) {
                val fake = FakeLlm()
                val eng = RemoteInferenceEngine(fake)
                fake.probe = { eng.state.value.javaClass.simpleName }
                try {
                    kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(5000) { body(fake, eng) } }
                    pass++
                } catch (t: Throwable) {
                    fails.add("独立进程「$name」 ${t.javaClass.simpleName}: ${t.message}")
                }
            }
            val msgs = ByteArray(0)
            val ready = InferenceEngine.State.ModelReady
            case("初始状态是可加载") { _, e -> ok(e.state.value is InferenceEngine.State.Initialized, "state=${e.state.value}") }
            case("加载：状态转移并只连一次") { f, e ->
                e.loadModel("/m.gguf")
                ok(e.state.value == ready, "state=${e.state.value}")
                ok(f.stateDuringLoad == "LoadingModel", "加载中状态=${f.stateDuringLoad}")
                ok(f.connectCount == 1 && f.loadedPath == "/m.gguf", "connect=${f.connectCount} path=${f.loadedPath}")
            }
            case("并发加载只连一次") { f, e ->
                f.loadDelayMs = 50
                kotlinx.coroutines.coroutineScope {
                    val a = async { e.loadModel("/m") }
                    val b = async { e.loadModel("/m") }
                    a.await(); b.await()
                }
                ok(f.connectCount == 1 && f.loadCount == 1, "connect=${f.connectCount} load=${f.loadCount}")
            }
            case("生成：token 有序、期间为 Generating、结束回 ModelReady") { f, e ->
                e.loadModel("/m")
                var during = ""
                val sb = StringBuilder()
                e.sendToolPrompt(msgs, msgs, 8).collect { if (during.isEmpty()) during = e.state.value.javaClass.simpleName; sb.append(it) }
                ok(sb.toString() == "你好呀", "got=$sb")
                ok(during == "Generating", "during=$during")
                ok(e.state.value == ready, "after=${e.state.value}")
            }
            case("未加载就生成：明确报错") { _, e ->
                var err: Throwable? = null
                try { e.sendToolPrompt(msgs, msgs, 8).collect { } } catch (t: Throwable) { err = t }
                ok(err is IllegalStateException, "err=$err")
            }
            case("远端中途死亡：流以『回收』异常结束、状态回到可加载、可重新加载") { f, e ->
                e.loadModel("/m")
                f.dieAfter = 1
                var err: Throwable? = null
                val sb = StringBuilder()
                try { e.sendToolPrompt(msgs, msgs, 8).collect { sb.append(it) } } catch (t: Throwable) { err = t }
                ok(err is IllegalStateException && (err.message ?: "").contains("回收"), "err=$err")
                ok(sb.toString() == "你", "已到达的 token 应保留，got=$sb")
                ok(e.state.value is InferenceEngine.State.Initialized, "state=${e.state.value}")
                f.dieAfter = -1
                e.loadModel("/m")
                ok(e.state.value == ready && f.connectCount == 2, "state=${e.state.value} connect=${f.connectCount}")
            }
            case("收集方取消：通知远端停止，状态回 ModelReady") { f, e ->
                e.loadModel("/m")
                f.infinite = true
                val got = e.sendToolPrompt(msgs, msgs, 8).take(3).toList()
                ok(got.size == 3, "got=$got")
                ok(f.cancelled, "远端未收到取消")
                ok(e.state.value == ready, "state=${e.state.value}")
            }
            case("释放后可再次加载（重新连接）") { f, e ->
                e.loadModel("/m")
                e.cleanUp()
                ok(f.disconnectCount == 1 && e.state.value is InferenceEngine.State.Initialized, "disc=${f.disconnectCount} state=${e.state.value}")
                e.loadModel("/m")
                ok(e.state.value == ready && f.connectCount == 2, "state=${e.state.value} connect=${f.connectCount}")
            }
            case("加载失败：Error，cleanUp 后可重试") { f, e ->
                f.failLoad = RuntimeException("boom")
                var err: Throwable? = null
                try { e.loadModel("/m") } catch (t: Throwable) { err = t }
                ok(err != null && e.state.value is InferenceEngine.State.Error, "err=$err state=${e.state.value}")
                ok(f.disconnectCount >= 1, "失败后应断开远端")
                e.cleanUp()
                ok(e.state.value is InferenceEngine.State.Initialized, "state=${e.state.value}")
                f.failLoad = null
                e.loadModel("/m")
                ok(e.state.value == ready, "state=${e.state.value}")
            }
            case("就绪时远端死亡：状态回到可加载") { f, e ->
                e.loadModel("/m")
                f.onDied?.invoke()
                ok(e.state.value is InferenceEngine.State.Initialized, "state=${e.state.value}")
            }
            case("工具调用解析转发给远端") { _, e ->
                e.loadModel("/m")
                ok(String(e.parseToolCalls("x")) == "tool", "parse")
            }
        }

        return Report(pass, fails)
    }
}

/** 假传输层：可编程地模拟加载延迟/失败、流式 token、远端死亡、无限流 */
private class FakeLlm : LlmTransport {
    var connectCount = 0; var loadCount = 0; var disconnectCount = 0
    var cancelled = false
    var failLoad: Throwable? = null
    var loadDelayMs = 0L
    var tokens = listOf("你", "好", "呀")
    var dieAfter = -1
    var infinite = false
    var loadedPath = ""
    var stateDuringLoad = ""
    var probe: (() -> String)? = null
    private var up = false
    override val connected get() = up
    override var onDied: (() -> Unit)? = null
    override suspend fun connect() { connectCount++; up = true }
    override suspend fun load(path: String) {
        stateDuringLoad = probe?.invoke() ?: ""
        if (loadDelayMs > 0) kotlinx.coroutines.delay(loadDelayMs)
        failLoad?.let { throw it }
        loadCount++; loadedPath = path
    }
    override suspend fun setSystemPrompt(text: String) {}
    override fun generate(messages: ByteArray, tools: ByteArray, maxTokens: Int): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
        try {
            var i = 0
            while (infinite || i < tokens.size) {
                if (dieAfter in 0..i) { up = false; onDied?.invoke(); throw RemoteDiedException() }
                emit(if (infinite) "x" else tokens[i]); i++
                if (infinite) kotlinx.coroutines.yield()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
    }
    override suspend fun parseToolCalls(text: String) = "tool".toByteArray()
    override fun disconnect() { disconnectCount++; up = false }
}
