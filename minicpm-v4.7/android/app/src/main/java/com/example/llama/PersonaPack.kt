package com.example.llama

/**
 * 一个人设的**全部资源**，放在一个对象里：新增人格 = 复制一个 `Pack<Xxx>.kt`、改成自己的话术、把它加进 [PersonaPacks.ALL]，仅此而已。
 *
 * 以前的做法是每个模块各存一张「人设 → 话术」的表（回执、记忆问答、心跳话术、心跳模板、专注台词……），
 * 新增一个人设要改 6 个文件，漏改一处就悄悄退回通用口吻。现在：
 *   · 所有话术都在这一个类里（按"人设"而不是按"功能"组织）；
 *   · [PersonaPacks.validate] 检查每个人设的资源是否齐全、占位符是否正确，自检里每次都跑——漏写会直接报错，而不是悄悄退回通用口吻；
 *   · 行为开关（树洞的私密、不开骂）也是人设自己的属性，不再到处写 `id == "listener"`。
 *
 * 占位符：QA 模板里的 {x} = 程序查到的内容；心跳话术里的 {s} = 程序合成的那句事实；回执里的 %s = 追问的内容；专注台词的 {sec}{n}{app}… 见 [FocusLineBank]。
 */
data class PersonaPack(
    val persona: Persona,
    /** 私密空间（树洞）：说的话不进用户层记忆回顾、不被别的人设看到，也不做兴趣身份学习 */
    val privateSpace: Boolean = false,
    /** 专注模式里会不会吐槽/开骂；false = 永远只用温和台词、不弹悬浮窗 */
    val roast: Boolean = true,
    /** 口吻标记：这个人设的话里一定会出现的词（自检用来确认话术真的是这个人设的，评测脚本也用它判断口吻命中率） */
    val signature: List<String> = emptyList(),
    val receipts: Receipts,
    val qa: QaVoice,
    /** 天气等实时数据回答前的一句话头，数据原样跟在后面；可以为空 */
    val weatherLead: String,
    val heartbeat: HeartbeatVoice,
    /** 心跳话术池（没有真实数据可说时用）：topic → 多句；必须有 morning/noon/evening/night/todo/else */
    val hbMessages: Map<String, List<String>>,
    val focus: FocusKit,
)

/** 确定性路径（闹钟/提醒/待办）的口吻短句：done 可有多种说法 */
data class Receipts(val done: List<String>, val partial: String, val fail: String, val askWrap: String)

/** 程序查记忆/实时信息后的口吻模板，{x} = 程序查到的内容（recall/unknown/noNet/noRecord 必须含 {x}，stranger 不含） */
data class QaVoice(val recall: String, val unknown: String, val noNet: String, val stranger: String, val noRecord: String)

/** 心跳里把一句事实包成人设口吻：imminent = 马上要发生的，normal = 平常的；都含 {s} */
data class HeartbeatVoice(val imminent: List<String>, val normal: List<String>)

/**
 * 专注模式台词（预置）。档位 = [平静, 唠叨, 阴阳怪气]；slots 见 [FocusLineBank]。
 * finish = [S, A, B, C] 四个评级各一句。
 */
data class FocusKit(
    val start: List<String>,
    val leave: List<List<String>>,
    val peek: List<List<String>>,
    val distract: List<List<String>>,
    val ret: List<String>,
    val blown: List<String>,
    val coldEnd: List<String>,
    val finish: List<String>,
)

object PersonaPacks {

    /** 所有人设的**原包**（未改名）。新增人设：在这里加一行；要取生效版本用 [of] */
    val ALL: List<PersonaPack> = listOf(
        PACK_CATGIRL, PACK_ONEESAN, PACK_TSUNDERE, PACK_GENKI, PACK_KUUDERE, PACK_PUPPY, PACK_LISTENER, PACK_BUDDY,
        PACK_ALING, PACK_SHENZE, PACK_FUCHEN, PACK_SHENYAN, PACK_HUDEGUI, PACK_CHENYE,
    )

    private val BY_ID: Map<String, PersonaPack> = ALL.associateBy { it.persona.id }

    /** 生效的人设包：用户给她改过名就是改名后的版本（见 [PersonaNames]），没改过就是原包本身 */
    fun of(id: String?): PersonaPack? {
        val base = BY_ID[id] ?: return null
        val alias = PersonaNames.overrides[base.persona.id] ?: return base
        val key = base.persona.id + "|" + alias
        return renamedCache.getOrPut(key) { PersonaNames.withAlias(base, alias) }
    }

    private val renamedCache = java.util.concurrent.ConcurrentHashMap<String, PersonaPack>()

    /** 没有对应人设时的通用口吻（旧的备用数据人设 / 数据异常时才会用到；validate 保证正式人设不会走到这里） */
    val GENERIC: PersonaPack = PersonaPack(
        persona = Persona("generic", "通用", "💬", "我", "你", emptyList(), 0.0, emptyList(), "", emptyList(), ""),
        receipts = Receipts(listOf("好了。"), "有一件没能确认，见下：", "没办成，原因如下：", "%s？"),
        qa = QaVoice("{x}", "{x}：你还没告诉过我。", "我没联网，不知道今天的{x}。", "我们才刚认识，我还不了解你。", "没有找到{x}的对话记录。"),
        weatherLead = "",
        heartbeat = HeartbeatVoice(listOf("{s}。"), listOf("{s}。")),
        hbMessages = mapOf("else" to listOf("在。")),
        focus = FocusKit(
            start = listOf("专注 {min} 分钟，开始。我盯着你呢。"),
            leave = listOf(listOf("你离开 {sec} 秒了，快回来。"), listOf("第 {n} 次离开了，认真一点。"), listOf("离开 {n} 次，您这是在练习分身术吗？")),
            peek = listOf(listOf("偷看手机被我发现了。"), listOf("又亮屏了，我都看见了。"), listOf("亮屏第 {peeks} 回了，消息不会长腿跑掉的。")),
            distract = listOf(listOf("你去了{app}，我看到了。"), listOf("又去{app}，它给你发工资吗？"), listOf("{app}一开，我这边就成背景音乐了。")),
            ret = listOf("回来了，继续吧。"), blown = listOf("我生气了，{cold}不理你。"), coldEnd = listOf("好了，不气了，继续。"),
            finish = listOf("全程零走神，满分。", "只走神一次，很不错。", "评级 {grade}，下次更稳一点。", "评级 {grade}，这次有点难，下次再来。"),
        ),
    )

    fun packOf(id: String?): PersonaPack = of(id) ?: GENERIC

    private val HB_TOPICS = listOf("morning", "noon", "evening", "night", "todo", "else")

    /** 资源齐全性检查：返回问题列表（空 = 合格）。自检每次都跑，新增人设漏写任何一项都会在这里报出来 */
    fun validate(packs: List<PersonaPack> = ALL): List<String> {
        val out = ArrayList<String>()
        val ids = packs.map { it.persona.id }
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { out.add("人设编号重复：$it") }
        for (k in packs) {
            val id = k.persona.id
            fun bad(msg: String) { out.add("[$id] $msg") }
            val p = k.persona
            if (p.name.isBlank() || p.avatar.isBlank() || p.selfRef.isBlank() || p.style.isBlank() || p.fallback.isBlank()) bad("基本信息有空项（name/avatar/selfRef/style/fallback）")
            if (p.tics.isNotEmpty() && p.ticRate <= 0.0) bad("有口头禅但 ticRate≤0")
            val r = k.receipts
            if (r.done.isEmpty() || r.done.any { it.isBlank() }) bad("回执 done 为空")
            if (r.partial.isBlank() || r.fail.isBlank()) bad("回执 partial/fail 为空")
            if (!r.askWrap.contains("%s")) bad("askWrap 缺少 %s")
            val q = k.qa
            listOf("recall" to q.recall, "unknown" to q.unknown, "noNet" to q.noNet, "noRecord" to q.noRecord).forEach { (n, t) -> if (!t.contains("{x}")) bad("qa.$n 缺少 {x}") }
            if (q.stranger.isBlank() || q.stranger.contains("{x}")) bad("qa.stranger 为空或不该含 {x}")
            val h = k.heartbeat
            if (h.imminent.isEmpty() || h.normal.isEmpty() || (h.imminent + h.normal).any { !it.contains("{s}") }) bad("heartbeat 为空或缺少 {s}")
            HB_TOPICS.filter { k.hbMessages[it].isNullOrEmpty() }.forEach { bad("hbMessages 缺少话题 $it") }
            val f = k.focus
            if (f.start.isEmpty() || f.ret.isEmpty() || f.blown.isEmpty() || f.coldEnd.isEmpty()) bad("focus 的 start/ret/blown/coldEnd 有空项")
            if (f.finish.size != 4) bad("focus.finish 必须是 4 句（S/A/B/C）")
            listOf("leave" to f.leave, "peek" to f.peek, "distract" to f.distract).forEach { (n, l) -> if (l.size != 3 || l.any { it.isEmpty() }) bad("focus.$n 必须有 3 档且每档非空") }
            if (k.signature.isEmpty()) bad("没有写口吻标记 signature")
            if (k.privateSpace && k.roast) bad("私密空间的人设不应该开骂（privateSpace=true 要配 roast=false）")
            // 每条专注台词必须过 InsultGuard 与 FocusLineBank 的校验，否则不能入包
            FocusLines.templatesOf(f).forEach { (cell, kind, t) ->
                val why = FocusLineBank.validate(t, kind).filter { !it.startsWith("太短") }
                if (why.isNotEmpty()) bad("专注台词不合格 [$cell] $t → $why")
            }
            // 红线：noCommands 人设的专注台词也不能出现命令式
            if (p.noCommands) FocusLines.templatesOf(f).forEach { (cell, _, t) ->
                if (PersonaRedLines.COMMAND.containsMatchIn(t)) bad("专注台词含命令式 [$cell] $t")
            }
        }
        return out
    }
}
