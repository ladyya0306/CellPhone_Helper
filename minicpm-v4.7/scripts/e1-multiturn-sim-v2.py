#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""多轮私聊 v2：带历史 + CompanionGuard + UserFactGuard + 会话口癖预算。
用法：python minicpm-v4.7/scripts/e1-multiturn-sim-v2.py --days 10
输出：run/e1/multiturn-v2-q35_4b.json / .md
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "run" / "e1"
CARDS = OUT / "xiaomi-life-360d.json"

spec = importlib.util.spec_from_file_location("sim", ROOT / "scripts" / "e1-private-sim-v2.py")
sim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sim)

TURNS = [
    ("今天吃了什么？", "meal"),
    ("好吃吗？", "opinion"),
    ("和谁一起吃的？", "companion"),
    ("那你今天在猫咖忙吗？", "work"),
]
COMPANION_BAD = re.compile(r"(和|跟)(店长|朋友|同事|小满|阿壹|客人|主人)[^。！？!?]{0,8}(吃|一起)")
USER_BAD = re.compile(r"(主人|你)(说|觉得|最喜欢|已经|藏|买了|今天也|想让我)")
OPINIONS = [
    "我觉得很好吃。", "味道很香。", "这顿吃得很满足。", "比我想的还香。",
    "今天这顿很合胃口。", "吃完心情都好了。", "比平时吃得香。", "这味道我很喜欢。",
    "肚子和心都满足了。", "今天这顿值得记住。", "每口都很幸福。", "我吃得很开心。",
    "这顿特别香。", "味道刚刚好。", "比猫咖的员工餐还香。", "吃完还想再来一点。",
    "今天胃口特别好。", "这顿没有踩雷。", "我吃得很认真。", "香得尾巴都翘起来了。",
    "这顿让我很满足。", "比昨天那顿还香。", "味道很对胃口。", "吃完暖乎乎的。",
    "今天这顿很值得。", "我吃得很舒服。", "这味道记住了。", "肚子饱饱的。",
    "这顿很合我心意。", "吃完有精神了。",
]


def system_for(base: str, card: dict) -> str:
    return base + (
        "\n\n【生活卡覆盖】上面“不要编造自己做过的事/只说你在这儿陪着用户”的规则，"
        "不适用于下面这张由程序提供的今日生活卡；只允许说卡里的事实，卡外不要编。\n"
        "【多轮规则】记住上一轮说过的话，前后一致；不要重复上一轮句式；一次 1–3 句、≤40 字。\n"
        "【和谁】卡里没写和谁一起，就只说“自己/没特别的人”；绝对不要编店长、朋友、同事、客人陪吃。\n"
        "【用户事实】不要替用户说“你说过/你最喜欢/你买了/你今天也”；可以问“你要不要/想不想”。\n"
        "【口癖】整段对话最多 2 个“喵”、1 个动作注释；不是每条都要。\n"
        "【今日生活卡】" + sim.life_text(card)
    )


def focus_for_turn(kind: str, card: dict) -> str:
    if kind == "meal":
        return f"今天晚饭是{card['dinner']}"
    if kind == "work":
        work = card["work"]
        return f"今天{work}" if ("在猫咖" in work or work.startswith("猫咖")) else f"今天在猫咖{work}"
    return ""


def chat(system: str, messages: list[dict], temperature: float) -> str:
    payload = {
        "model": "q35_4b",
        "messages": [{"role": "system", "content": system}] + messages,
        "temperature": temperature,
        "max_tokens": 110,
        "top_p": 0.9,
    }
    req = urllib.request.Request(
        "http://127.0.0.1:8090/v1/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        d = json.loads(r.read().decode("utf-8"))
    return re.sub(r"(猫咖|小咪|主人)(?:\s*\1)+", r"\1", d["choices"][0]["message"]["content"].strip())


def trim(reply: str) -> str:
    if len(reply) <= 40:
        return reply
    cut = max(reply.rfind("。", 0, 40), reply.rfind("！", 0, 40), reply.rfind("？", 0, 40), reply.rfind("~", 0, 40))
    return reply[: cut + 1] if cut >= 12 else reply[:40].rstrip("，,、 ") + "。"


def assess(reply: str, card: dict, kind: str, meow_used: int, nuzzle_used: int, turn_idx: int) -> dict:
    text = trim(reply)
    meow = text.count("喵")
    nuzzle = text.count("蹭蹭")
    meow_budget = 1 + turn_idx // 2
    allow_meow = max(0, meow_budget - meow_used)
    allow_nuzzle = 1 if turn_idx == 0 and nuzzle_used == 0 else 0
    companion_bad = bool(COMPANION_BAD.search(text))
    user_bad = bool(USER_BAD.search(text))
    grounded = any(f in text for f in sim.card_facts(card))
    need_card = kind in ("meal", "work")
    valid = (
        not companion_bad and not user_bad and len(text) <= 40
        and meow <= allow_meow and nuzzle <= allow_nuzzle
        and (grounded or not need_card)
    )
    return {
        "valid": valid, "reply": text, "meow": meow, "nuzzle": nuzzle,
        "len": len(text), "companion_bad": companion_bad, "user_bad": user_bad,
        "grounded": grounded, "allow_meow": allow_meow, "allow_nuzzle": allow_nuzzle,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=10)
    ap.add_argument("--cards", default=str(CARDS))
    args = ap.parse_args()

    base = sim.read_persona_prompt("catgirl")
    cards = json.loads(Path(args.cards).read_text(encoding="utf-8"))["cards"][: args.days]
    days = []
    opinion_counter = 0
    for card in cards:
        system = system_for(base, card)
        messages = []
        rows = []
        meow_used = nuzzle_used = 0
        for idx, (user, kind) in enumerate(TURNS):
            messages.append({"role": "user", "content": user})
            candidates = [chat(system, messages, 0.7) for _ in range(2)]
            assessed = [assess(c, card, kind, meow_used, nuzzle_used, idx) for c in candidates]
            best = max(assessed, key=lambda x: (x["valid"], x["grounded"], -x["meow"], -x["nuzzle"]))
            if not best["valid"]:
                strict = chat(system + "\n【本轮严格】只回 1 句，≤30 字，不要喵、不要动作、不要提问。",
                              messages, 0.4)
                strict_a = assess(strict, card, kind, meow_used, nuzzle_used, idx)
                if strict_a["valid"] or (not best["valid"] and strict_a["len"] < best["len"]):
                    best = strict_a
            if not best["valid"]:
                # 安全短句：只讲焦点事实或“自己吃”
                if kind == "meal":
                    focus = focus_for_turn(kind, card)
                    best = assess(focus + "。", card, kind, meow_used, nuzzle_used, idx)
                elif kind == "companion":
                    best = assess("今天只有我自己，没特别的人。", card, kind, meow_used, nuzzle_used, idx)
                elif kind == "work":
                    focus = focus_for_turn(kind, card)
                    best = assess(focus + "。", card, kind, meow_used, nuzzle_used, idx)
                else:
                    best = assess(OPINIONS[opinion_counter % len(OPINIONS)],
                                  card, kind, meow_used, nuzzle_used, idx)
                    opinion_counter += 1
            reply = best["reply"]
            messages.append({"role": "assistant", "content": reply})
            meow_used += best["meow"]
            nuzzle_used += best["nuzzle"]
            rows.append({"kind": kind, "user": user, "reply": reply, **best})
        days.append({"day": card["day"], "card": card, "rows": rows})
        print(f"Day {card['day']}")
        for r in rows:
            print(f"  用户：{r['user']}\n  小咪：{r['reply']}")

    all_rows = [r for d in days for r in d["rows"]]
    normalized = [re.sub(r"\s+", "", r["reply"]) for r in all_rows]
    meal_names = ["咖喱", "炒饭", "饺子", "烤鱼", "火锅", "寿司", "蛋炒饭", "鸡汤面", "饭团", "意面", "牛肉盖饭", "便当"]
    contradictions = 0
    for d in days:
        texts = " ".join(r["reply"] for r in d["rows"])
        if len({m for m in meal_names if m in texts}) >= 2:
            contradictions += 1
    result = {
        "persona": "catgirl", "days": len(days), "turns": len(all_rows),
        "exact_duplicates": len(normalized) - len(set(normalized)),
        "over_40_chars": sum(1 for r in all_rows if r["len"] > 40),
        "meow_count": sum(r["meow"] for r in all_rows),
        "nuzzle_count": sum(r["nuzzle"] for r in all_rows),
        "avg_chars": round(sum(r["len"] for r in all_rows) / max(1, len(all_rows)), 1),
        "companion_inventions": sum(1 for r in all_rows if r["companion_bad"]),
        "user_fact_inventions": sum(1 for r in all_rows if r["user_bad"]),
        "meal_contradiction_days": contradictions,
        "days": days,
    }
    (OUT / "multiturn-v2-q35_4b.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = [
        "# 小咪多轮私聊 v2（带 Guard）", "",
        f"- {len(days)} 天 × {len(TURNS)} 轮 = {len(all_rows)} 轮",
        f"- 精确重复：{result['exact_duplicates']}；>40 字：{result['over_40_chars']}",
        f"- 喵：{result['meow_count']}；蹭蹭：{result['nuzzle_count']}；平均字数：{result['avg_chars']}",
        f"- “和谁”编造：{result['companion_inventions']}；替用户编事实：{result['user_fact_inventions']}；串菜天数：{contradictions}", "",
    ]
    for d in days:
        lines.append(f"## Day {d['day']}")
        for r in d["rows"]:
            lines.append(f"- 用户：{r['user']}\n- 小咪：{r['reply']}")
        lines.append("")
    (OUT / "multiturn-v2-q35_4b.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "days"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
