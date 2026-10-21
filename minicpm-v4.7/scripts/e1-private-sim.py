#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""E1 私聊试点：用 30 天 LifeCard 驱动小咪的 1:1 对话。
只读 run/e1/xiaomi-life-30d.json；调本机 llama-server（默认 8090）。
用法：python minicpm-v4.7/scripts/e1-private-sim.py --days 7
输出：run/e1/private-sim-q35_4b.json / .md
"""
from __future__ import annotations

import argparse
import json
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROMPTS = ROOT / "run" / "eval" / "prompts.txt"
DEFAULT_CARDS = ROOT / "run" / "e1" / "xiaomi-life-30d.json"
OUT = ROOT / "run" / "e1"

USER_TURNS = [
    "今天吃了什么？",
    "在干嘛呢？",
    "今天累不累？",
    "午饭吃的啥？",
    "今天猫咖忙吗？",
    "晚上吃什么？",
    "今天有什么新鲜事？",
    "你今天运动了吗？",
]


def read_persona_prompt(persona_id: str) -> str:
    text = PROMPTS.read_text(encoding="utf-8")
    m = re.search(rf"@@PERSONA {re.escape(persona_id)}[^\n]*\n(.*?)\n@@END", text, re.S)
    if not m:
        raise SystemExit(f"prompt not found: {persona_id}")
    return m.group(1).strip()


def life_text(card: dict) -> str:
    snack = f"，加餐是{card['snack']}" if card.get("snack") else ""
    return (
        f"今天是第{card['day']}天，{card['weekday']}，{card['weather']}。"
        f"早饭吃的是{card['breakfast']}，午饭是{card['lunch']}，晚饭是{card['dinner']}{snack}。"
        f"今天做了{card['exercise']}，还在猫咖做了{card['work']}。"
        f"今天发生的小事：{card['small_event']}。心情{card['mood']}，精力{card['energy']}。"
        f"今天想聊的钩子：{card['hook']}。"
    )


def build_system(base: str, card: dict) -> str:
    return base + (
        "\n\n【生活卡覆盖】上面“不要编造自己做过的事/只说你在这儿陪着用户”的规则，"
        "不适用于下面这张由程序提供的今日生活卡。你可以把卡里的内容当成今天真实发生的事；"
        "但卡外仍然不要编造。\n"
        "【硬规则】\n"
        "1. 只允许说卡里列出的内容；不要补卡外的天气、星星、客人数量、店长评价、别人说的话。\n"
        "2. 用户没说过的事，不要替用户编（不要写“你藏起来的小鱼干”“你买的…”）。\n"
        "3. 被问到生活，最多说 1–2 个卡内细节，不要逐项汇报。\n"
        "4. 一次 1–3 句、不超过 80 字；动作注释最多 1 个，不要每句都“蹭蹭/喵”。\n"
        "【今日生活卡】" + life_text(card) + "\n"
        "【最近聊过】最近几天已经聊过吃饭/运动/猫咖；这次换一个具体细节，不要重复上一轮句式。"
    )


def chat(base: str, card: dict, user: str) -> str:
    payload = {
        "model": "q35_4b",
        "messages": [
            {"role": "system", "content": build_system(base, card)},
            {"role": "user", "content": user},
        ],
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
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=7)
    ap.add_argument("--cards", default=str(DEFAULT_CARDS))
    args = ap.parse_args()

    data = json.loads(Path(args.cards).read_text(encoding="utf-8"))
    cards = data["cards"][: args.days]
    base = read_persona_prompt("catgirl")

    rows = []
    for idx, card in enumerate(cards):
        user = USER_TURNS[idx % len(USER_TURNS)]
        reply = chat(base, card, user)
        rows.append({"day": card["day"], "user": user, "reply": reply, "card": card})
        print(f"[{card['day']:02d}] 用户：{user}\n     小咪：{reply}\n")

    normalized = [re.sub(r"\s+", "", r["reply"]) for r in rows]
    exact_dup = len(normalized) - len(set(normalized))
    long = sum(1 for r in rows if len(r["reply"]) > 80)
    mark = sum(1 for r in rows if re.search(r"喵|小咪|耳朵|尾巴", r["reply"]))
    grounded = 0
    invented_user = 0
    for r in rows:
        c = r["card"]
        allowed = [c[k] for k in ("breakfast", "lunch", "dinner", "exercise", "work", "small_event", "hook") if c.get(k)]
        if c.get("snack"):
            allowed.append(c["snack"])
        if any(tok in r["reply"] for tok in allowed):
            grounded += 1
        if re.search(r"(你|您).{0,6}(藏|买|说|做|去|在|吃)|主人.{0,6}(藏|买|说|做|去|在|吃)", r["reply"]):
            invented_user += 1
    result = {
        "persona": "catgirl",
        "days": len(rows),
        "replies": len(rows),
        "exact_duplicates": exact_dup,
        "over_80_chars": long,
        "voice_marker_hits": mark,
        "grounded_in_lifecard": grounded,
        "possible_user_fact_invention": invented_user,
        "meow_count": sum(r["reply"].count("喵") for r in rows),
        "nuzzle_count": sum(r["reply"].count("蹭蹭") for r in rows),
        "avg_chars": round(sum(len(r["reply"]) for r in rows) / max(1, len(rows)), 1),
        "rows": rows,
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "private-sim-q35_4b.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    lines = [
        "# 小咪私聊试点（LifeCard 驱动）",
        "",
        f"- 天数：{len(rows)}；回复：{len(rows)}",
        f"- 精确重复：{exact_dup}；>80 字：{long}；口吻标记命中：{mark}",
        f"- 至少命中 1 个 LifeCard 事实：{grounded}/{len(rows)}；疑似编造用户事实：{invented_user}",
        f"- 平均字数：{result['avg_chars']}；喵：{result['meow_count']}；蹭蹭：{result['nuzzle_count']}",
        "",
    ]
    for r in rows:
        lines += [
            f"## Day {r['day']}",
            f"- 生活卡：{life_text(r['card'])}",
            f"- 用户：{r['user']}",
            f"- 小咪：{r['reply']}",
            "",
        ]
    (OUT / "private-sim-q35_4b.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "rows"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
