package com.example.llama

/**
 * 给人设改名（只改显示名与自称）。纯 Kotlin，可单测。
 *
 * 显示名的格式是「类型·名字」，例如「猫娘·小咪」。用户能改的只有后半截的名字（alias）：
 *   · [Persona.name] 变成「类型·新名字」；
 *   · 如果这个人设本来就自称自己的名字（小咪、小满、零、阿壹、老周），[Persona.selfRef] 跟着改，
 *     并把话术包里所有出现旧名字的地方（回执、心跳、专注台词、提示词……）换成新名字；
 *   · 自称是一个身份称呼的人设（姐姐、本小姐、我）自称不变，只改显示名；
 *   · 「称呼你」（userRef）永远不改。
 *
 * 改名后的话术包必须仍然通过 [PersonaPacks.validate]（专注台词不能带数字/脏话等），过不了就不让改——
 * 所以名字怎么起都不会破坏已有的合规检查。
 *
 * 保存：覆盖表 [overrides] 存在内存里，持久化由 AppSettings 负责（App 启动时读入）。没有覆盖时，
 * [PersonaPacks.of] 返回原包本身（同一个对象），行为与改名功能上线前逐字相同。
 */
object PersonaNames {

    const val MAX_LEN = 8

    /** 人设编号 → 用户起的名字。只在主线程/设置页写，读的地方多，用并发表避免心跳线程读到半截 */
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()

    val overrides: Map<String, String> get() = map

    fun set(id: String, alias: String?) { if (alias.isNullOrBlank()) map.remove(id) else map[id] = alias }
    fun replaceAll(all: Map<String, String>) { map.clear(); map.putAll(all) }
    fun clear() = map.clear()

    /** 「类型·名字」里的类型（猫娘、温柔大姐姐……） */
    fun typeOf(name: String): String = if (name.contains('·')) name.substringBefore('·') else name

    /** 「类型·名字」里的名字 */
    fun aliasOf(name: String): String = if (name.contains('·')) name.substringAfter('·') else name

    /** 这个人设是不是自称自己的名字（决定改名要不要同步自称和话术） */
    fun selfIsName(p: Persona): Boolean = p.selfRef == aliasOf(p.name)

    sealed class Verdict {
        data class Ok(val alias: String) : Verdict()
        /** 空输入 = 恢复默认名字 */
        object Reset : Verdict()
        data class Bad(val why: String) : Verdict()
    }

    private val PRONOUNS = setOf("你", "我", "他", "她", "它", "咱", "您", "主人", "本人", "自己", "助手", "AI", "ai")

    /**
     * 校验用户输入的名字。规则：
     *   1. 去掉首尾空白；空 = 恢复默认
     *   2. 1–8 个字；只允许汉字、假名、字母（不要数字、标点、空格、表情符号、控制字符）
     *   3. 不是代词/泛称（你、我、主人、助手……）
     *   4. 过 [InsultGuard]（脏话、人身攻击等）
     *   5. 不能和别的人设现在的名字重名（可以和自己原来的名字一样）
     *   6. 改名后的话术包必须仍通过 [PersonaPacks.validate]
     */
    fun check(raw: String?, personaId: String): Verdict {
        val base = PersonaPacks.ALL.firstOrNull { it.persona.id == personaId } ?: return Verdict.Bad("没有这个人设")
        val t = (raw ?: "").trim()
        if (t.isEmpty()) return Verdict.Reset
        if (t.codePointCount(0, t.length) > MAX_LEN) return Verdict.Bad("太长了，最多 $MAX_LEN 个字")
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            i += Character.charCount(cp)
            val script = try { Character.UnicodeScript.of(cp) } catch (_: Exception) { Character.UnicodeScript.UNKNOWN }
            val okScript = script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
                script == Character.UnicodeScript.KATAKANA || (Character.isLetter(cp) && script == Character.UnicodeScript.LATIN)
            if (!okScript) return Verdict.Bad("只能用汉字、假名或字母，不能有数字、空格、标点和表情符号")
        }
        if (t in PRONOUNS) return Verdict.Bad("这个太像称呼了，换个像名字的")
        val baseAlias = aliasOf(base.persona.name)
        if (t == baseAlias) return Verdict.Reset
        // InsultGuard 对太短的句子会报「太短」，名字本来就短，只看其他原因
        val dirty = InsultGuard.check(t + "。").filter { !it.startsWith("太短") }
        if (dirty.isNotEmpty()) return Verdict.Bad("这个名字不太合适，换一个吧")
        val clash = PersonaPacks.ALL.filter { it.persona.id != personaId }.any {
            val other = effectiveAlias(it.persona.id)
            other.equals(t, ignoreCase = true) || aliasOf(it.persona.name).equals(t, ignoreCase = true)
        }
        if (clash) return Verdict.Bad("已经有别的人设叫这个名字了")
        val problems = PersonaPacks.validate(listOf(withAlias(base, t)))
        if (problems.isNotEmpty()) return Verdict.Bad("这个名字会让她的台词出问题，换一个吧")
        return Verdict.Ok(t)
    }

    /** 当前生效的名字（没改过就是原名） */
    fun effectiveAlias(personaId: String): String {
        val base = PersonaPacks.ALL.firstOrNull { it.persona.id == personaId } ?: return ""
        return map[personaId] ?: aliasOf(base.persona.name)
    }

    /** 把话术包里的名字换成 [alias]；不自称名字的人设只改 persona.name */
    fun withAlias(pack: PersonaPack, alias: String): PersonaPack {
        val p = pack.persona
        val old = aliasOf(p.name)
        if (alias == old) return pack
        val newName = typeOf(p.name) + "·" + alias
        if (!selfIsName(p)) return pack.copy(persona = p.copy(name = newName))
        val f = { s: String -> s.replace(old, alias) }
        val fl = { l: List<String> -> l.map(f) }
        val fll = { l: List<List<String>> -> l.map(fl) }
        return pack.copy(
            persona = p.copy(
                name = newName, selfRef = alias, tics = fl(p.tics), emotionalValue = fl(p.emotionalValue),
                style = f(p.style), taboos = fl(p.taboos), fallback = f(p.fallback),
            ),
            signature = fl(pack.signature),
            receipts = pack.receipts.copy(done = fl(pack.receipts.done), partial = f(pack.receipts.partial), fail = f(pack.receipts.fail), askWrap = f(pack.receipts.askWrap)),
            qa = QaVoice(f(pack.qa.recall), f(pack.qa.unknown), f(pack.qa.noNet), f(pack.qa.stranger), f(pack.qa.noRecord)),
            weatherLead = f(pack.weatherLead),
            heartbeat = HeartbeatVoice(fl(pack.heartbeat.imminent), fl(pack.heartbeat.normal)),
            hbMessages = pack.hbMessages.mapValues { fl(it.value) },
            focus = pack.focus.copy(
                start = fl(pack.focus.start), leave = fll(pack.focus.leave), peek = fll(pack.focus.peek), distract = fll(pack.focus.distract),
                ret = fl(pack.focus.ret), blown = fl(pack.focus.blown), coldEnd = fl(pack.focus.coldEnd), finish = fl(pack.focus.finish),
            ),
        )
    }
}
