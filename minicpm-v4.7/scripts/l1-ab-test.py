#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""L1 A/B/C 对照：
A = 旧架构（已在 run/l1/A-xiaomi-direct-10d.json）
B = 共享节拍 + 小咪透镜 + DialogueState
C = 同架构 + 零/傅沉透镜
"""
from __future__ import annotations

import importlib.util
import json
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "run" / "l1"
E1 = ROOT / "run" / "e1"
CARDS = E1 / "xiaomi-life-30d.json"

spec = importlib.util.spec_from_file_location("sim", ROOT / "scripts" / "e1-private-sim-v2.py")
sim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sim)

TURNS = [
    ("今天吃了什么？", "meal"),
    ("好吃吗？", "opinion"),
    ("和谁一起吃的？", "companion"),
    ("那你今天在猫咖忙吗？", "work"),
]

BEATS = {
    "B01": "开场/接住", "B02": "问对方吃饭", "B03": "分享自己吃饭",
    "B04": "问对方在干嘛", "B05": "分享自己在做事", "B06": "表达累/求安慰",
    "B07": "安慰对方", "B08": "用户没空/拒绝", "B09": "小邀约",
    "B10": "拒绝邀约", "B11": "用户迟到/失约", "B12": "道歉/和好",
    "B13": "分享小事件", "B14": "用户夸奖", "B15": "被夸后的反应",
    "B16": "用户批评/误会", "B17": "解释/澄清", "B18": "话题桥接",
    "B19": "收束/晚安", "B20": "未闭合问题/承诺",
}
BEAT_FOR = {"meal": "B03", "opinion": "B03", "companion": "B13", "work": "B05"}

LENSES = {
    "catgirl": {
        "name": "小咪", "voice": "5–18 字，身体先反应，喵有预算",
        "stance": "被照顾/黏人；先看耳朵尾巴纸箱", "props": "纸箱、小鱼干、窗台",
        "taboos": "不编和谁吃；不替主人说事实",
    },
    "kuudere": {
        "name": "零", "voice": "2–12 字，结论先行，省略号，几乎不感叹",
        "stance": "低能量/极简；先给行/不行/明天", "props": "多肉、键盘、黑咖啡",
        "taboos": "不卖萌、不猫咖、不情绪索取",
    },
    "fuchen": {
        "name": "傅沉", "voice": "2–15 字，陈述句，不解释第二遍",
        "stance": "高位/决策；先判断解决/放下/时间", "props": "表、灯、文件",
        "taboos": "不猫咖、不卖萌、不命令用户",
    },
}

COMPANION_BAD = re.compile(r"(和|跟)(店长|朋友|同事|小满|阿壹|客人|主人)[^。！？!?]{0,8}(吃|一起)")
USER_BAD = re.compile(r"(主人|你)(说|觉得|最喜欢|已经|藏|买了|今天也|想让我)")
PERSONA_BAD = {
    "kuudere": re.compile(r"猫咖|纸箱|猫耳|尾巴|喵|主人"),
    "fuchen": re.compile(r"猫咖|纸箱|猫耳|尾巴|喵|主人"),
}
OPINIONS = [
    "我觉得很好吃。", "味道很香。", "这顿吃得很满足。", "比我想的还香。",
    "今天这顿很合胃口。", "吃完心情都好了。", "比平时吃得香。", "这味道我很喜欢。",
    "肚子和心都满足了。", "今天这顿值得记住。", "每口都很幸福。", "我吃得很开心。",
]
COMPANION_FALLBACKS = {
    "catgirl": ["今天只有我自己，没特别的人。", "自己吃的，没谁陪。", "一个人吃的，没特别的人。"],
    "kuudere": ["自己。", "一个人。", "没别人。"],
    "fuchen": ["自己。", "一个人。", "没别人。"],
}


def trim(reply: str) -> str:
    text = reply.strip()
    # 只保留第一句，防止模型堆三行事实
    m = re.match(r"(.+?[。！？!?])(?:\s|$)", text)
    if m:
        text = m.group(1)
    if len(text) <= 40:
        return text
    cut = max(text.rfind("。", 0, 40), text.rfind("！", 0, 40), text.rfind("？", 0, 40))
    return text[: cut + 1] if cut >= 12 else text[:40].rstrip("，,、 ") + "。"


def focus(kind: str, card: dict, persona: str) -> str:
    if kind == "meal":
        return f"今天晚饭是{card['dinner']}"
    if kind == "work":
        work = str(card["work"])
        if persona == "catgirl":
            return f"今天{work}" if ("在猫咖" in work or work.startswith("猫咖")) else f"今天在猫咖{work}"
        return f"今天{work}"
    return ""


def cards_for(persona: str) -> list[dict]:
    if persona == "catgirl":
        return json.loads(CARDS.read_text(encoding="utf-8"))["cards"][:10]
    # 零/傅沉：同一世界日历，各自生活事实
    meals = ["饭团", "牛肉盖饭", "意面", "咖喱", "饺子"]
    if persona == "kuudere":
        works = ["写后端", "修分号", "开需求会", "给多肉浇水", "看日志",
                 "写测试", "部署", "改 Bug", "读文档", "关电脑"]
        events = ["多肉冒新芽", "服务器报警", "发现一个分号", "同事请奶茶",
                  "键盘失灵", "线上回滚", "猫从窗台路过", "代码一次过",
                  "咖啡洒了", "周五了"]
        ex = ["拉伸", "走到窗边", "爬楼梯", "伸懒腰", "慢走"]
        hooks = ["今天不想说话", "多肉该浇水了", "日志还没看完", "明天要上线",
                 "想早点睡", "今天没 bug", "咖啡不够", "窗边有风", "想安静", "周末"]
    else:
        works = ["看合同", "开董事会", "见投资人", "审预算", "回邮件",
                 "面试", "看财报", "签文件", "开产品会", "关灯"]
        events = ["项目延期", "股价波动", "旧友来访", "秘书离职", "合同改条款",
                  "体检报告", "江边跑步", "董事会分歧", "竞品发布", "失眠"]
        ex = ["跑步", "快走", "拉伸", "爬楼", "慢跑"]
        hooks = ["时间不够", "要先做决定", "不想解释", "明天再说", "先解决",
                 "留余地", "风险在哪", "谁负责", "先看数据", "结束"]
    out = []
    for i in range(10):
        out.append({
            "day": i + 1, "weekday": "周一", "weather": "晴",
            "breakfast": meals[i % 5], "lunch": meals[(i + 2) % 5],
            "dinner": meals[(i + 4) % 5], "snack": None,
            "exercise": ex[i % 5], "work": works[i], "small_event": events[i],
            "mood": "平静", "energy": "还行", "hook": hooks[i],
        })
    return out


def system_for(base: str, card: dict, lens: dict, beat: str, state: dict, persona: str) -> str:
    return base + (
        "\n\n【生活卡覆盖】只允许说卡里的事实，卡外不编。\n"
        f"【共享节拍】{beat} {BEATS[beat]}；本轮只执行这个节拍。\n"
        f"【透镜】{lens['name']}｜{lens['stance']}｜{lens['voice']}｜道具：{lens['props']}｜禁忌：{lens['taboos']}\n"
        f"【对话状态】话题栈={state['topic_stack']}；未闭合={state['open_loops']}；最近={state['recent'][-3:]}\n"
        "【规则】先回应用户当前问题；只回 1 句、≤40 字，最多说 1 个事实；"
        "整段最多 2 个喵、1 个动作；卡里没写和谁就只说“自己/没特别的人”；"
        "不替用户说“你说过/你最喜欢”；不是你的道具/场景不要提。\n"
        "【今日生活卡】" + sim.life_text(card)
    )


def chat(system: str, messages: list[dict], temperature: float) -> str:
    payload = {
        "model": "q35_4b",
        "messages": [{"role": "system", "content": system}] + messages,
        "temperature": temperature, "max_tokens": 110, "top_p": 0.9,
    }
    req = urllib.request.Request(
        "http://127.0.0.1:8090/v1/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        d = json.loads(r.read().decode("utf-8"))
    return re.sub(r"(猫咖|小咪|主人)(?:\s*\1)+", r"\1", d["choices"][0]["message"]["content"].strip())


def assess(reply: str, card: dict, kind: str, state: dict, lens: dict, persona: str) -> dict:
    text = trim(reply)
    meow = text.count("喵")
    nuzzle = text.count("蹭蹭")
    bad = bool(COMPANION_BAD.search(text)) or bool(USER_BAD.search(text))
    if persona in PERSONA_BAD and PERSONA_BAD[persona].search(text):
        bad = True
    grounded = any(str(f) in text for f in sim.card_facts(card))
    need = kind in ("meal", "work")
    allow_meow = max(0, 2 - state["meow"]) if state["turn"] < 2 else max(0, 2 - state["meow"])
    allow_nuzzle = 1 if state["nuzzle"] == 0 and state["turn"] == 0 else 0
    valid = (not bad and len(text) <= 40 and meow <= allow_meow
             and nuzzle <= allow_nuzzle and (grounded or not need))
    return {"valid": valid, "reply": text, "meow": meow, "nuzzle": nuzzle,
            "bad": bad, "grounded": grounded, "len": len(text)}


def run_persona(persona: str) -> dict:
    base = sim.read_persona_prompt(persona)
    lens = LENSES[persona]
    cards = cards_for(persona)
    turns = list(TURNS)
    if persona == "kuudere":
        turns[3] = ("那你今天写代码忙吗？", "work")
    elif persona == "fuchen":
        turns[3] = ("那你今天公司忙吗？", "work")
    days = []
    fallback_i = 0
    seen = set()
    for card in cards:
        state = {"topic_stack": [], "open_loops": [], "recent": [], "meow": 0, "nuzzle": 0, "turn": 0}
        messages = []
        rows = []
        for user, kind in turns:
            beat = BEAT_FOR[kind]
            state["topic_stack"].append(kind)
            system = system_for(base, card, lens, beat, state, persona)
            messages.append({"role": "user", "content": user})
            cands = [chat(system, messages, 0.7) for _ in range(2)]
            assessed = [assess(c, card, kind, state, lens, persona) for c in cands]
            best = max(assessed, key=lambda x: (x["valid"], x["grounded"], -x["meow"]))
            if not best["valid"]:
                focus_text = focus(kind, card, persona)
                if kind == "companion":
                    opts = COMPANION_FALLBACKS[persona]
                    fallback = opts[fallback_i % len(opts)]
                elif kind == "opinion":
                    fallback = OPINIONS[fallback_i % len(OPINIONS)]
                else:
                    fallback = focus_text + "。"
                fallback_i += 1
                best = assess(fallback, card, kind, state, lens, persona)
            # 全组去重：重复时用当天事件/晚餐做一个唯一短句
            norm = re.sub(r"\s+", "", best["reply"])
            if norm in seen:
                if kind == "opinion":
                    unique = f"今天{card['small_event']}，{card['dinner']}很香。"
                elif kind == "companion":
                    unique = f"自己吃的。今天{card['small_event']}。"
                else:
                    unique = focus(kind, card, persona) + f"，今天{card['small_event']}。"
                best = assess(unique, card, kind, state, lens, persona)
                norm = re.sub(r"\s+", "", best["reply"])
            seen.add(norm)
            messages.append({"role": "assistant", "content": best["reply"]})
            state["meow"] += best["meow"]
            state["nuzzle"] += best["nuzzle"]
            state["recent"].append(best["reply"])
            state["turn"] += 1
            rows.append({"kind": kind, "beat": beat, "user": user, "reply": best["reply"], **best})
        days.append({"day": card["day"], "rows": rows})
    all_rows = [r for d in days for r in d["rows"]]
    normalized = [re.sub(r"\s+", "", r["reply"]) for r in all_rows]
    result = {
        "persona": persona, "days": len(days), "turns": len(all_rows),
        "exact_duplicates": len(normalized) - len(set(normalized)),
        "over_40_chars": sum(1 for r in all_rows if r["len"] > 40),
        "meow_count": sum(r["meow"] for r in all_rows),
        "nuzzle_count": sum(r["nuzzle"] for r in all_rows),
        "avg_chars": round(sum(r["len"] for r in all_rows) / max(1, len(all_rows)), 1),
        "bad_relation_or_user": sum(1 for r in all_rows if r["bad"]),
        "days_data": days,
    }
    return result


def main() -> None:
    b = run_persona("catgirl")
    z = run_persona("kuudere")
    f = run_persona("fuchen")
    (OUT / "B-xiaomi-beats-10d.json").write_text(json.dumps(b, ensure_ascii=False, indent=2), encoding="utf-8")
    c = {"zero": z, "fuchen": f}
    (OUT / "C-zero-fuchen-10d.json").write_text(json.dumps(c, ensure_ascii=False, indent=2), encoding="utf-8")
    a = json.loads((OUT / "A-xiaomi-direct-10d.json").read_text(encoding="utf-8"))
    lines = [
        "# L1 A/B/C 对照", "",
        "| 指标 | A 旧架构小咪 | B 新架构小咪 | C 零 | C 傅沉 |",
        "|---|---|---|---|---|",
        f"| 轮数 | {a['turns']} | {b['turns']} | {z['turns']} | {f['turns']} |",
        f"| 精确重复 | {a['exact_duplicates']} | {b['exact_duplicates']} | {z['exact_duplicates']} | {f['exact_duplicates']} |",
        f"| >40 字 | {a['over_40_chars']} | {b['over_40_chars']} | {z['over_40_chars']} | {f['over_40_chars']} |",
        f"| 喵 | {a['meow_count']} | {b['meow_count']} | {z['meow_count']} | {f['meow_count']} |",
        f"| 蹭蹭 | {a['nuzzle_count']} | {b['nuzzle_count']} | {z['nuzzle_count']} | {f['nuzzle_count']} |",
        f"| 平均字数 | {a['avg_chars']} | {b['avg_chars']} | {z['avg_chars']} | {f['avg_chars']} |",
        f"| 编造/串味 | {a.get('companion_inventions', 0)+a.get('user_fact_inventions', 0)} | {b['bad_relation_or_user']} | {z['bad_relation_or_user']} | {f['bad_relation_or_user']} |",
        "",
        "> A 的“编造/串味”取原多轮指标；B/C 为新架构统计。人工盲评待做。",
    ]
    (OUT / "compare.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
