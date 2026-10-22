#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""L1b H 混合：A 的 LifeCard 内容 + B 的节拍/透镜/状态 + 答完追问/扩展。
只跑小咪 10 天 ×4 轮，与已有 A/B-old 比。
"""
from __future__ import annotations

import importlib.util
import json
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
L1 = ROOT / "run" / "l1"
CARDS = json.loads((ROOT / "run" / "e1" / "xiaomi-life-30d.json").read_text(encoding="utf-8"))["cards"][:10]

spec = importlib.util.spec_from_file_location("sim", ROOT / "scripts" / "e1-private-sim-v2.py")
sim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sim)

TURNS = [
    ("今天吃了什么？", "meal"),
    ("好吃吗？", "opinion"),
    ("和谁一起吃的？", "companion"),
    ("那你今天在猫咖忙吗？", "work"),
]
POLICY = {
    "meal": "先答自己吃了什么，再追问用户今天吃了什么",
    "opinion": "先答味道，再扩展一个今天的小事件",
    "companion": "答自己/没特别的人，再扩展一个今天的小事件",
    "work": "先答工作，再追问用户今天忙不忙",
}
LENS = (
    "小咪：5–18 字为主，身体先反应（耳朵/尾巴/纸箱），黏人但不刷喵；"
    "不知道的不编；不是自己的道具不提。"
)
BAD = re.compile(r"(和|跟)(店长|朋友|同事|小满|阿壹|客人|主人|猫|小猫|新猫)[^。！？!?]{0,8}(吃|一起)")
USER_BAD = re.compile(r"(主人|你)(说|觉得|最喜欢|已经|藏|买了|今天也|想让我)")
QUESTION_HINT = {
    "meal": ["你今天吃了什么？", "你那边吃了什么？", "你今天有好好吃饭吗？", "你今天想吃什么？"],
    "work": ["你今天忙不忙？", "你今天累不累？", "你那边忙吗？", "今天工作顺利吗？"],
}
BRIDGES = ["刚才说到吃饭，", "你前面问吃饭，", "说到吃饭，", "那顿饭，", "你刚才问，", "你前面提到，"]
BRIDGES_SAFE = ["你前面问吃饭，", "说到吃饭，", "那顿饭，", "你前面提到，"]


def trim(text: str, limit: int = 45) -> str:
    text = text.strip().replace("她", "我")
    if len(text) <= limit:
        return text
    cut = max(text.rfind("。", 0, limit), text.rfind("！", 0, limit), text.rfind("？", 0, limit))
    return text[: cut + 1] if cut >= 20 else text[:limit].rstrip("，,、 ") + "。"


def system_for(base: str, card: dict, kind: str, state: dict, hint: str = "") -> str:
    prev_user = state["prev_user"] or "（无）"
    prev_reply = state["prev_reply"] or "（无）"
    bridge = "【必须】本轮先用一句“刚才你说…”承接上一轮用户，再回答/扩展。" if kind == "companion" else ""
    hint_line = f"【追问参考】{hint}（不要照抄上一轮问句）\n" if hint else ""
    must_ask = "【必须】本轮结尾加一个相关追问。\n" if kind in ("meal", "work") else ""
    return base + (
        "\n\n【内容基线】下面这张 LifeCard 是今天的真实生活事实；可以自由组织，不要编卡外。\n"
        "【多轮】记住上一轮；如果自然，带一句“刚才你说…”或承接上一轮，但不要每轮都硬带。\n"
        f"【当前节拍】{kind}：{POLICY[kind]}。\n"
        f"【透镜】{LENS}\n"
        f"【状态】话题栈={state['topic_stack']}；未闭合={state['open_loops']}；上一轮用户={prev_user}；上一轮我={prev_reply}\n"
        + bridge + hint_line + must_ask +
        "【规则】先回答用户当前问题；2 句、≤45 字；只在“吃饭/工作”轮追问，其他轮不提问；"
        "每轮最多 1 个动作、最多 1 个喵；整段最多 2 个喵；只扩展 1 个相关事实；"
        "卡里没写和谁就只说“自己/没特别的人”；不替用户说“你说过/你最喜欢”。\n"
        "【今日生活卡】" + sim.life_text(card)
    )


def chat(system: str, messages: list[dict], temperature: float) -> str:
    payload = {
        "model": "q35_4b",
        "messages": [{"role": "system", "content": system}] + messages,
        "temperature": temperature, "max_tokens": 150, "top_p": 0.9,
    }
    req = urllib.request.Request(
        "http://127.0.0.1:8090/v1/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        d = json.loads(r.read().decode("utf-8"))
    return re.sub(r"(猫咖|小咪|主人)(?:\s*\1)+", r"\1", d["choices"][0]["message"]["content"].strip())


def fact_category(card: dict, fact: str) -> str:
    for k in ("breakfast", "lunch", "dinner", "snack"):
        if str(card.get(k, "")) == fact:
            return "meal"
    if fact == str(card.get("work", "")):
        return "work"
    if fact == str(card.get("exercise", "")):
        return "exercise"
    return "event"


def assess(reply: str, card: dict, kind: str, state: dict, seen: set[str]) -> dict:
    text = trim(reply)
    bad = bool(BAD.search(text)) or bool(USER_BAD.search(text))
    facts = [str(f) for f in sim.card_facts(card) if str(f) in text]
    grounded = bool(facts)
    fact_count = len(facts)
    cats = {fact_category(card, f) for f in facts}
    allowed = {"meal"} if kind in ("meal", "opinion") else ({"work"} if kind == "work" else {"event"})
    cross_fact = any(c not in allowed for c in cats)
    need = kind in ("meal", "work")
    meow = text.count("喵")
    nuzzle = text.count("蹭蹭")
    question = bool(re.search(r"[?？]", text))
    qnorm = re.sub(r"\s+", "", text[text.find("？") + 1:]) if "？" in text else ""
    question_bad = question and kind not in ("meal", "work")
    question_repeat = question and bool(qnorm) and qnorm == state.get("last_question", "")
    meow_allowed = max(0, 2 - state["meow"])
    norm = re.sub(r"\s+", "", text)
    valid = (not bad and not cross_fact and not question_bad and not question_repeat
             and len(text) <= 45 and meow <= min(1, meow_allowed) and nuzzle <= 1
             and fact_count <= 2 and (grounded or not need) and norm not in seen)
    return {"valid": valid, "reply": text, "bad": bad, "grounded": grounded,
            "meow": meow, "nuzzle": nuzzle, "len": len(text),
            "question": question, "question_bad": question_bad,
            "question_repeat": question_repeat, "fact_count": fact_count,
            "cross_fact": cross_fact}


def main() -> None:
    base = sim.read_persona_prompt("catgirl")
    days = []
    seen: set[str] = set()
    asked_total = 0
    bridge_total = 0
    for card in CARDS:
        state = {"topic_stack": [], "open_loops": [], "prev_user": "", "prev_reply": "",
                 "meow": 0, "last_question": "", "bridge_days": 0}
        messages = []
        rows = []
        for user, kind in TURNS:
            state["topic_stack"].append(kind)
            hint = QUESTION_HINT.get(kind, [""])[(card["day"] - 1) % len(QUESTION_HINT.get(kind, [""]))]
            system = system_for(base, card, kind, state, hint)
            messages.append({"role": "user", "content": user})
            cands = [chat(system, messages, 0.7) for _ in range(2)]
            assessed = [assess(c, card, kind, state, seen) for c in cands]
            best = max(assessed, key=lambda x: (x["valid"], x["grounded"], x["question"], -x["nuzzle"]))
            if not best["valid"]:
                strict = chat(system + "\n【本轮严格】只回 2 句，先答后问/扩展，≤50 字，不要动作。", messages, 0.4)
                strict_a = assess(strict, card, kind, state, seen)
                if strict_a["valid"] or strict_a["len"] < best["len"]:
                    best = strict_a
            if not best["valid"]:
                focus = f"今天晚饭是{card['dinner']}" if kind == "meal" else f"今天{card['work']}"
                if kind == "companion":
                    fallback = "自己吃的。"
                elif kind == "opinion":
                    fallback = f"{card['dinner']}很香。"
                else:
                    fallback = focus + "。"
                best = assess(fallback, card, kind, state, seen)

            # 程序保证追问 12–18/40：只在 meal/work，且总数未到 18
            if best["valid"] and kind in ("meal", "work") and not best["question"] and asked_total < 18:
                q = QUESTION_HINT[kind][(card["day"] - 1) % len(QUESTION_HINT[kind])]
                asked = trim(best["reply"].rstrip("。！？!?") + "。" + q)
                asked_a = assess(asked, card, kind, state, seen)
                if asked_a["valid"]:
                    best = asked_a

            # 好吃吗：强制带一个主菜
            if best["valid"] and kind == "opinion" and not any(str(card[k]) in best["reply"] for k in ("breakfast", "lunch", "dinner")):
                meal_reply = trim(f"{card['dinner']}很香。" + best["reply"])
                meal_a = assess(meal_reply, card, kind, state, seen)
                if meal_a["valid"]:
                    best = meal_a

            # companion 强制回指；“刚才”最多 2 天
            if kind == "companion" and not re.search(r"刚才|之前|你说|那|前面", best["reply"]):
                if bridge_total >= 2:
                    bridge = BRIDGES_SAFE[(card["day"] - 1) % len(BRIDGES_SAFE)]
                else:
                    bridge = BRIDGES[(card["day"] - 1) % len(BRIDGES)]
                    if "刚才" in bridge:
                        bridge_total += 1
                bridged = trim(bridge + best["reply"])
                bridged_a = assess(bridged, card, kind, state, seen)
                if bridged_a["valid"]:
                    best = bridged_a
            messages.append({"role": "assistant", "content": best["reply"]})
            seen.add(re.sub(r"\s+", "", best["reply"]))
            state["prev_user"] = user
            state["prev_reply"] = best["reply"]
            state["meow"] += best["meow"]
            if best["question"]:
                asked_total += 1
                state["last_question"] = best["reply"]
            rows.append({"kind": kind, "user": user, "reply": best["reply"], **best})
        days.append({"day": card["day"], "rows": rows})
        print(f"Day {card['day']}")
        for r in rows:
            print(f"  用户：{r['user']}\n  小咪：{r['reply']}")

    all_rows = [r for d in days for r in d["rows"]]
    normalized = [re.sub(r"\s+", "", r["reply"]) for r in all_rows]
    result = {
        "persona": "catgirl", "days": len(days), "turns": len(all_rows),
        "exact_duplicates": len(normalized) - len(set(normalized)),
        "over_60_chars": sum(1 for r in all_rows if r["len"] > 60),
        "question_replies": sum(1 for r in all_rows if r["question"]),
        "non_first_questions": sum(1 for r in all_rows if r["kind"] != "meal" and r["question"]),
        "meow_count": sum(r["meow"] for r in all_rows),
        "nuzzle_count": sum(r["nuzzle"] for r in all_rows),
        "avg_chars": round(sum(r["len"] for r in all_rows) / max(1, len(all_rows)), 1),
        "bad": sum(1 for r in all_rows if r["bad"]),
        "days_data": days,
    }
    (L1 / "H-xiaomi-hybrid-10d.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "days_data"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
