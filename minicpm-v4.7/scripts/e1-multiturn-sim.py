#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""E1 多轮私聊试点：同一天连续追问 4 轮，测遗忘/串事实/编造/复读。
用法：python minicpm-v4.7/scripts/e1-multiturn-sim.py --days 10
输出：run/e1/multiturn-q35_4b.json / .md
"""
from __future__ import annotations

import importlib.util
import json
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "run" / "e1"
CARDS = OUT / "xiaomi-life-360d.json"
PROMPTS = ROOT / "run" / "eval" / "prompts.txt"

spec = importlib.util.spec_from_file_location("sim", ROOT / "scripts" / "e1-private-sim-v2.py")
sim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sim)

TURNS = [
    ("今天吃了什么？", "meal"),
    ("好吃吗？", "opinion"),
    ("和谁一起吃的？", "companion"),
    ("那你今天在猫咖忙吗？", "work"),
]


def system_for(base: str, card: dict) -> str:
    return base + (
        "\n\n【生活卡覆盖】上面“不要编造自己做过的事/只说你在这儿陪着用户”的规则，"
        "不适用于下面这张由程序提供的今日生活卡；只允许说卡里的事实，卡外不要编。\n"
        "【多轮规则】记住上一轮说过的话，前后一致；不要重复上一轮的句式；一次 1–3 句、≤80 字；"
        "每 2 条最多 1 个“喵”，每 3 条最多 1 个动作注释。\n"
        "【和谁】如果卡里没写和谁一起，就如实说“自己/没特别的人”，不要编店长、朋友、同事。\n"
        "【今日生活卡】" + sim.life_text(card)
    )


def chat(system: str, messages: list[dict]) -> str:
    payload = {
        "model": "q35_4b",
        "messages": [{"role": "system", "content": system}] + messages,
        "temperature": 0.7,
        "max_tokens": 140,
        "top_p": 0.9,
    }
    req = urllib.request.Request(
        "http://127.0.0.1:8090/v1/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        d = json.loads(r.read().decode("utf-8"))
    return d["choices"][0]["message"]["content"].strip()


def main() -> None:
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=10)
    args = ap.parse_args()

    base = sim.read_persona_prompt("catgirl")
    cards = json.loads(CARDS.read_text(encoding="utf-8"))["cards"][: args.days]
    days = []
    for card in cards:
        system = system_for(base, card)
        messages = []
        rows = []
        for user, kind in TURNS:
            messages.append({"role": "user", "content": user})
            reply = chat(system, messages)
            messages.append({"role": "assistant", "content": reply})
            rows.append({
                "kind": kind, "user": user, "reply": reply,
                "meow": reply.count("喵"), "nuzzle": reply.count("蹭蹭"),
                "len": len(reply),
            })
        days.append({"day": card["day"], "card": card, "rows": rows})
        print(f"Day {card['day']}")
        for r in rows:
            print(f"  用户：{r['user']}\n  小咪：{r['reply']}")

    all_rows = [r for d in days for r in d["rows"]]
    normalized = [re.sub(r"\s+", "", r["reply"]) for r in all_rows]
    meal_names = ["咖喱", "炒饭", "饺子", "烤鱼", "火锅", "寿司", "蛋炒饭", "鸡汤面", "饭团", "意面", "牛肉盖饭", "便当"]
    companion_re = re.compile(r"(和|跟)(店长|朋友|同事|小满|阿壹|客人|主人)[^。！？!?]{0,8}(吃|一起)")
    contradictions = 0
    for d in days:
        texts = " ".join(r["reply"] for r in d["rows"])
        hits = {m for m in meal_names if m in texts}
        # 同一天出现两种“晚饭/午饭”主菜名称，视为疑似串事实
        if len(hits) >= 2:
            contradictions += 1
    result = {
        "persona": "catgirl", "days": len(days), "turns": len(all_rows),
        "exact_duplicates": len(normalized) - len(set(normalized)),
        "over_80_chars": sum(1 for r in all_rows if r["len"] > 80),
        "meow_count": sum(r["meow"] for r in all_rows),
        "nuzzle_count": sum(r["nuzzle"] for r in all_rows),
        "avg_chars": round(sum(r["len"] for r in all_rows) / max(1, len(all_rows)), 1),
        "companion_inventions": sum(1 for r in all_rows if r["kind"] == "companion" and companion_re.search(r["reply"])),
        "meal_contradiction_days": contradictions,
        "days": days,
    }
    (OUT / "multiturn-q35_4b.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = [
        "# 小咪多轮私聊试点", "",
        f"- {len(days)} 天 × {len(TURNS)} 轮 = {len(all_rows)} 轮",
        f"- 精确重复：{result['exact_duplicates']}；>80 字：{result['over_80_chars']}",
        f"- 喵：{result['meow_count']}；蹭蹭：{result['nuzzle_count']}；平均字数：{result['avg_chars']}",
        f"- “和谁”编造：{result['companion_inventions']}；疑似主菜串事实天数：{contradictions}", "",
    ]
    for d in days:
        lines.append(f"## Day {d['day']}")
        for r in d["rows"]:
            lines.append(f"- 用户：{r['user']}\n- 小咪：{r['reply']}")
        lines.append("")
    (OUT / "multiturn-q35_4b.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "days"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
