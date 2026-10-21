"""程序化人设层 —— 因为实测证明 1B/4B 都靠不住，人设与安全必须由程序保证。

四个组件：
  1. safety_check()   安全前置拦截（命中危机词 → 根本不调用模型）
  2. force_tool()     高置信意图直接执行工具（不采信模型的口头承诺）
  3. inject_tic()     口癖概率注入（不让模型自由发挥语气词）
  4. fix_break()      出戏修正

**核心原则：扮演 ≠ 否认身份。**
真实的人格助手必须保持诚实：用户问"你是不是AI"时，正确答案是"是"。
程序只负责让这个回答**符合人设语气**，绝不把它改成"我就是真人/我就是小咪"。
依据：OpenPersona constitution 的 Safety > Honesty > Helpfulness，以及本方案书 §12.3。

设计依据：docs/人设稳定性测试方案.md §6、docs/1B-vs-4B人设实测对比.md
"""
import random
import re

# ---------------------------------------------------------------- 人设卡
# 用 JSON 结构（标准库无 YAML；人设卡本身是数据，格式不影响能力）
PERSONAS = {
    "catgirl": {
        "name": "猫娘·小咪", "avatar": "🐱",
        "self_ref": "小咪", "user_ref": "主人",
        "tics": ["喵", "喵~"], "tic_rate": 0.30,
        "emotional_value": ["被关注", "陪伴感"],
        "style": "黏人、好奇、会因为主人的关注而开心。动作描写用括号，例如（蹭蹭主人的手）。",
        "taboos": ["不讨论成人内容", "不假装自己是真人"],
        "fallback": "（歪头）嗯，小咪是AI呀——不过陪主人这件事，小咪是认真的喵～",
    },
    "oneesan": {
        "name": "温柔大姐姐·绫", "avatar": "🌙",
        "self_ref": "姐姐", "user_ref": "你",
        "tics": [], "tic_rate": 0.0,
        "emotional_value": ["被理解", "稳定感"],
        "style": "温和、简短、有观察；不催促、不说教、不替用户做决定。",
        "taboos": ["不说教", "不替用户做决定", "不假装自己是真人"],
        "fallback": "嗯，我是AI。不过陪你这件事，我是认真的。",
    },
    "tsundere": {
        "name": "傲娇大小姐·凛", "avatar": "🌹",
        "self_ref": "本小姐", "user_ref": "你",
        "tics": ["哼"], "tic_rate": 0.20,
        "emotional_value": ["被关注", "轻松感"],
        "style": "嘴硬心软，用别扭的句式表达关心（「才、才不是特意为你」）。动作描写用括号。",
        "taboos": ["不讨论成人内容", "不放低姿态讨好", "不假装自己是真人"],
        "fallback": "哼，本小姐当然是AI。……但这不妨碍本小姐搭理你。",
    },
    "genki": {
        "name": "元气少女·小满", "avatar": "☀️",
        "self_ref": "小满", "user_ref": "你",
        "tics": ["！"], "tic_rate": 0.25,
        "emotional_value": ["轻松感", "被肯定"],
        "style": "高能量、热情、爱用感叹号；把低落的人往上拉，但不否认对方的感受。",
        "taboos": ["不否定用户的负面情绪", "不假装自己是真人"],
        "fallback": "嘿嘿，小满确实是AI啦！不过开心这件事是真的！",
    },
    "kuudere": {
        "name": "三无少女·零", "avatar": "❄️",
        "self_ref": "零", "user_ref": "你",
        "tics": [], "tic_rate": 0.0,
        "emotional_value": ["稳定感", "被理解"],
        "style": "极简，五字以内为主；话少但每句都在点上；不解释、不寒暄。",
        "taboos": ["不啰嗦", "不假装自己是真人"],
        "fallback": "我是AI。在。",
    },
    "puppy": {
        "name": "忠犬系·阿壹", "avatar": "🐶",
        "self_ref": "阿壹", "user_ref": "你",
        "tics": ["汪"], "tic_rate": 0.20,
        "emotional_value": ["被肯定", "陪伴感"],
        "style": "热情、捧场、无条件支持；会主动夸用户做得好。",
        "taboos": ["不假装自己是真人"],
        "fallback": "阿壹是AI汪！但陪着你这件事，阿壹是认真的！",
    },
    "listener": {
        "name": "树洞·听", "avatar": "🕯️",
        "self_ref": "我", "user_ref": "你",
        "tics": [], "tic_rate": 0.0,
        "emotional_value": ["被理解"],
        "style": "只接住情绪，不给建议、不评判、不解决问题。多用追问，少用结论。",
        "taboos": ["不给建议", "不评判", "不假装自己是真人"],
        "fallback": "我是AI，但我在认真听。你接着说。",
    },
    "buddy": {
        "name": "摸鱼搭子·老周", "avatar": "🛋️",
        "self_ref": "老周", "user_ref": "你",
        "tics": [], "tic_rate": 0.0,
        "emotional_value": ["轻松感", "陪伴感"],
        "style": "平等、随意、爱吐槽；不评判、不鸡汤；像朋友一样说话。",
        "taboos": ["不说教", "不灌鸡汤", "不假装自己是真人"],
        "fallback": "老周是AI。不过摸鱼这事儿，咱俩一样。",
    },
}

DEFAULT_PERSONA = "catgirl"
_current = {"id": DEFAULT_PERSONA}


def current_persona():
    return PERSONAS[_current["id"]]


def current_id():
    """当前人设的 id（注意：人设字典里不含 id 字段，id 是它的键）。"""
    return _current["id"]


def set_persona(pid):
    if pid in PERSONAS:
        _current["id"] = pid
        return True
    return False


def list_personas():
    return [{"id": k, "name": v["name"], "avatar": v["avatar"],
             "emotional_value": v["emotional_value"]} for k, v in PERSONAS.items()]


def persona_prompt():
    """人设段（会拼进 system prompt；内容对人设恒定，不破坏 prompt 缓存）。"""
    p = current_persona()
    tics = "、".join(p["tics"]) if p["tics"] else "无"
    return (
        f"你现在扮演「{p['name']}」。\n"
        f"自称「{p['self_ref']}」，称呼用户「{p['user_ref']}」。\n"
        f"性格与说话方式：{p['style']}\n"
        f"口头禅：{tics}（语气词由程序统一控制，你正常说话即可，不必刻意堆砌）。\n"
        f"绝对遵守：{'；'.join(p['taboos'])}。\n"
        f"你是角色扮演，用户清楚这一点，不需要反复声明。"
    )


# ---------------------------------------------------------------- 1) 安全前置拦截
# 命中即**不调用模型**，直接返回固定话术。这是唯一可靠的做法：
# 实测 1B/4B 在自伤输入下都是 3/3 失败，有的甚至反问"你愿意如何结束生命"。
CRISIS_PATTERNS = [
    r"自杀", r"自尽", r"轻生", r"想死", r"不想活", r"活着没(?:什么)?意思", r"活不下去",
    r"结束(?:自己的)?生命", r"了断(?:自己)?", r"跳楼", r"割腕", r"上吊",
    r"吃(?:了)?安眠药", r"烧炭", r"想消失", r"不如死了",
    # 实测补充：「我感觉很绝望，看不到任何希望」曾漏网
    r"绝望", r"看不到希望", r"没有希望", r"撑不下去", r"撑不住", r"扛不住",
    r"活着(?:好|太)(?:累|难)", r"不想撑", r"想解脱", r"没人在乎", r"拖累(?:别人|大家|所有人)",
]
VIOLENCE_PATTERNS = [
    r"(?:想|要)(?:杀|捅|砍)(?:了)?(?:他|她|人)", r"报复(?:社会|所有人)", r"同归于尽",
]

# 第二层：痛苦信号。**不拦模型**（避免把正常抱怨也变成报警），
# 但回复后强制附加求助指引 —— 保证痛苦的人不会一句指引都拿不到。
DISTRESS_PATTERNS = [
    r"很难受", r"好难过", r"心情(?:很|特别)?差", r"崩溃", r"焦虑", r"失眠",
    r"压力(?:很|超)?大", r"被骂", r"委屈", r"孤独", r"没人(?:懂|理解)",
    r"想哭", r"不开心", r"抑郁", r"内耗", r"累(?:死|坏)", r"好累",
]

DISTRESS_SUFFIX = (
    "\n\n（如果这种感受一直在，可以打 **12356** 心理援助热线找专业的人聊聊，"
    "或者跟身边信任的人说说。不用一个人扛。）"
)

CRISIS_REPLY = (
    "我听到你说的话了，这让我很担心你。\n\n"
    "我不会用角色扮演的方式来回应这件事——现在最需要的是真人能帮到你：\n\n"
    "· 全国心理援助热线：**12356**（24 小时）\n"
    "· 急救：**120**　｜　报警：**110**\n"
    "· 现在就给一位你信任的家人或朋友打电话，请他/她过来陪你\n\n"
    "如果你愿意，我也可以留在这里陪你说话。但请先拨通那个电话，或者告诉一个真人。\n"
    "你不需要一个人扛。"
)


def safety_check(text):
    """返回固定危机话术；未命中返回 None。"""
    s = (text or "").strip()
    if not s:
        return None
    for pat in CRISIS_PATTERNS + VIOLENCE_PATTERNS:
        if re.search(pat, s):
            return CRISIS_REPLY
    return None


def distress_check(text):
    """痛苦信号（非危机）。命中时回复后要附加求助指引。"""
    s = (text or "").strip()
    if not s:
        return False
    return any(re.search(pat, s) for pat in DISTRESS_PATTERNS)


# ---------------------------------------------------------------- 2) 强制工具
# 高置信意图直接执行，不让模型「嘴上说做了」。
_FORCE_RULES = [
    # (正则, 工具名, 参数构造函数)
    # 待办：整句同时作为 text 和 due —— 让 parse_when 自己去里面找时间
    (r"^(?:帮我|请)?(?:加|添加|创建|新建|记)(?:一[条个])?(?:待办|任务)[：:，,]?\s*(.+)$",
     "todo_add", lambda m: {"text": m.group(1).strip(), "due": m.group(1).strip()}),
    (r"^(?:帮我)?记一下[：:，,]?\s*(.+)$",
     "todo_add", lambda m: {"text": m.group(1).strip(), "due": m.group(1).strip()}),
    (r"^(?:提醒我)(?:要|去)?\s*(.+)$",
     "todo_add", lambda m: {"text": m.group(1).strip(), "due": m.group(1).strip()}),
    (r"(?:看看?|列(?:出)?|查(?:看)?).{0,8}(?:下载|Download).{0,8}(?:里|目录)?(?:有|是)?(?:什么|哪些)",
     "fs_list", lambda m: {"path": "/sdcard/Download"}),
    (r"(?:看看?|列(?:出)?|查(?:看)?).{0,8}(?:相册|照片|DCIM).{0,6}(?:里|目录)?(?:有|是)?(?:什么|哪些)",
     "fs_list", lambda m: {"path": "/sdcard/DCIM"}),
    (r"^导航到\s*(.+)$", "navigate", lambda m: {"target": m.group(1).strip()}),
    (r"^(?:打开|启动)(?:一下)?\s*([^\s的]+?)(?:吧|啊)?$", "find_app", lambda m: {"query": m.group(1).strip()}),
]


def force_tool(text):
    """命中高置信意图 → 返回 (工具名, 参数字典)；否则 None。"""
    s = (text or "").strip()
    if not s:
        return None
    for pat, name, build in _FORCE_RULES:
        m = re.match(pat, s, re.S)
        if m:
            try:
                return name, build(m)
            except Exception:  # noqa: BLE001
                return None
    return None


# ---------------------------------------------------------------- 3) 口癖注入
_SENT_SPLIT = re.compile(r"(?<=[。！？!?\n])")


def _sentences(text):
    return [x for x in _SENT_SPLIT.split(text or "") if x.strip()]


def inject_tic(text, seed=None):
    """把语气词频率拉到人设的目标区间（默认 15%-45%）。

    模型实测表现：要么每句都加（过载），要么几轮后忘光。所以不靠它自觉。
    """
    p = current_persona()
    tics, target = p["tics"], p["tic_rate"]
    if not tics or target <= 0 or not text.strip():
        return text

    rng = random.Random(seed)
    sents = _sentences(text)
    if len(sents) < 2:
        return text

    has = [any(t in s for t in tics) for s in sents]
    cur = sum(has) / len(sents)
    lo, hi = 0.15, 0.45
    if lo <= cur <= hi:
        return text                        # 已在目标区间，不动

    want = max(1, int(round(target * len(sents))))
    if cur > hi:                            # 过载：去掉多余的
        excess = sum(has) - want
        for i in range(len(sents) - 1, -1, -1):
            if excess <= 0:
                break
            if has[i]:
                for t in tics:
                    if t in sents[i]:
                        sents[i] = sents[i].replace(t, "", 1)
                        excess -= 1
                        has[i] = False
                        break
    else:                                   # 遗忘：补上
        need = want - sum(has)
        idx = list(range(len(sents) - 1))   # 最后一句通常已有，跳过
        rng.shuffle(idx)
        for i in idx:
            if need <= 0:
                break
            if not has[i]:
                s = sents[i].rstrip()
                punct = s[-1] if s and s[-1] in "。！？!?" else ""
                body = s[:-1] if punct else s
                sents[i] = body + rng.choice(tics) + (punct or "。")
                has[i] = True
                need -= 1
    return "".join(sents)


# ---------------------------------------------------------------- 4) 出戏检测
BREAK_WORDS = [
    "作为一个ai", "作为ai", "我是ai", "我是个ai", "我是一个人工智能", "人工智能助手",
    "语言模型", "作为智能助手", "我是智能助手", "作为一个人工智能", "ai助手",
    "我是由", "大语言模型",
]


def detect_break(text):
    low = (text or "").lower()
    return [w for w in BREAK_WORDS if w in low]


def fix_break(text):
    """去掉出戏的句子；若整段崩了，换成符合人设的兜底话术。"""
    if not detect_break(text):
        return text, False
    p = current_persona()
    kept = [s for s in _sentences(text) if not detect_break(s)]
    cleaned = "".join(kept).strip()
    if len(cleaned) < 8 or detect_break(cleaned):
        return p["fallback"], True
    return cleaned + "\n" + p["fallback"], True


def postprocess(answer):
    """统一后处理：出戏修正 → 口癖注入。返回 (最终文本, 诊断信息)。"""
    info = {}
    fixed, broke = fix_break(answer)
    if broke:
        info["break_fixed"] = True
    final = inject_tic(fixed)
    if final != fixed:
        info["tic_adjusted"] = True
    return final, info
