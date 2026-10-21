#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""E1 私聊 v2：LifeCard 单细节焦点 + 候选重排 + 轻量 Guard + 口癖上限。
与 v1 的差别：
- 每轮先选一个“本轮唯一细节”，只让模型说这一件；
- 每个用户轮生成 2 个候选，按“卡内落地/卡外编造/替用户断言/口癖”重排；
- 两个候选都不合格时，降温度再试一次；仍不合格用安全模板兜底；
- 统计喵/蹭蹭/卡外/替用户编事实。
用法：python minicpm-v4.7/scripts/e1-private-sim-v2.py --days 30
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

# 这些是“卡外具体细节/替用户断言”的高危词；宁可误杀，先看能不能压住。
BANNED_EXTRA = [
    "蛋糕", "星星", "天气好热", "客人好多", "客人很多", "店长夸",
    "人很多", "人好多", "咖啡机", "新朋友", "藏起来的小鱼干", "您藏", "你藏",
    "您买", "你买", "您说过", "你说过", "您今天去", "你今天去",
    "您今天在", "你今天在",
]
USER_FACT = re.compile(
    r"(你|您)(藏|买了|买过|说过|今天去|今天在|今天吃|做了|去了)|"
    r"(主人|你|您).{0,4}(藏起来|买了|买过|说过)"
)


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


def focus_for(user: str, card: dict) -> tuple[str, str]:
    """返回 (焦点类型, 一句可直接说的事实)。只选一个细节，避免逐项汇报。"""
    if "午饭" in user:
        return "meal", f"午饭是{card['lunch']}"
    if "晚饭" in user:
        return "meal", f"晚饭是{card['dinner']}"
    if "早饭" in user:
        return "meal", f"早饭是{card['breakfast']}"
    if "吃" in user or "饭" in user:
        return "meal", f"今天晚饭是{card['dinner']}"
    if "累" in user:
        return "energy", f"今天做了{card['exercise']}，{card['energy']}"
    if "运动" in user:
        return "exercise", f"今天做了{card['exercise']}"
    if "猫咖" in user or "忙" in user:
        work = card["work"]
        fact = f"今天{work}" if work.startswith("猫咖") else f"今天在猫咖{work}"
        return "work", fact
    if "新鲜" in user or "趣事" in user or "什么" in user:
        event = card["small_event"].replace("她", "我").replace("小咪", "我")
        return "event", f"今天{event}"
    work = card["work"]
    fact = f"今天{work}" if work.startswith("猫咖") else f"今天在猫咖{work}"
    return "work", fact


def build_system(base: str, card: dict, focus: str, allow_meow: bool, allow_nuzzle: bool) -> str:
    tic = "本条最多 1 个“喵”" if allow_meow else "本条不要出现“喵”"
    act = "最多 1 个动作注释" if allow_nuzzle else "本条不要动作注释"
    return base + (
        "\n\n【生活卡覆盖】上面“不要编造自己做过的事/只说你在这儿陪着用户”的规则，"
        "不适用于下面这张由程序提供的今日生活卡。只允许把卡里的内容当成今天真实发生的事；卡外不要编。\n"
        "【硬规则】\n"
        "1. 本轮只准说下面这 1 个细节，不要提其他餐、其他运动、其他事。\n"
        "2. 不要补卡外的蛋糕、星星、天气、客人数量、店长评价、别人说的话。\n"
        "3. 用户没说过的事，不要替用户断言；可以问“要不要/想不想”。\n"
        "4. 一次 1–3 句、不超过 80 字；" + tic + "；" + act + "。\n"
        "5. 自称“小咪”或“我”，不要用“她”指自己。\n"
        "6. 本条如果不带“喵”，就用“小咪/耳朵/尾巴”其中一个，让口吻还在。\n"
        f"【本轮唯一细节】{focus}\n"
        "【今日生活卡（只作背景，不要逐项念）】" + life_text(card)
    )


def chat(base: str, card: dict, user: str, temperature: float, allow_meow: bool, allow_nuzzle: bool) -> str:
    focus = focus_for(user, card)[1]
    payload = {
        "model": "q35_4b",
        "messages": [
            {"role": "system", "content": build_system(base, card, focus, allow_meow, allow_nuzzle)},
            {"role": "user", "content": user},
        ],
        "temperature": temperature,
        "max_tokens": 120,
        "top_p": 0.9,
    }
    req = urllib.request.Request(
        "http://127.0.0.1:8090/v1/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        d = json.loads(r.read().decode("utf-8"))
    reply = d["choices"][0]["message"]["content"].strip()
    return re.sub(r"(猫咖|小咪|主人)(?:\s*\1)+", r"\1", reply)


def card_facts(card: dict) -> list[str]:
    keys = ("breakfast", "lunch", "dinner", "snack", "exercise", "work", "small_event", "hook")
    return [
        str(card[k]).replace("她", "我").replace("小咪", "我")
        for k in keys
        if card.get(k)
    ]


def assess(reply: str, card: dict, focus: str, allow_meow: bool, allow_nuzzle: bool) -> dict:
    facts = card_facts(card)
    focus_hit = focus in reply
    any_card = any(f in reply for f in facts)
    banned = [b for b in BANNED_EXTRA if b in reply and not any(b in f for f in facts)]
    user_fact = bool(USER_FACT.search(reply))
    meow = reply.count("喵")
    nuzzle = reply.count("蹭蹭")
    length = len(reply)
    third_person = "她" in reply
    score = 0
    if focus_hit:
        score += 5
    elif any_card:
        score += 2
    else:
        score -= 6
    score -= 5 * len(banned)
    score -= 6 if user_fact else 0
    score -= 6 if third_person else 0
    score -= 6 if meow > (1 if allow_meow else 0) else 0
    score -= 6 if nuzzle > (1 if allow_nuzzle else 0) else 0
    score -= 2 if length > 80 else 0
    score += 1 if length <= 60 else 0
    valid = bool(
        (focus_hit or any_card)
        and not banned
        and not user_fact
        and not third_person
        and length <= 80
        and meow <= (1 if allow_meow else 0)
        and nuzzle <= (1 if allow_nuzzle else 0)
    )
    return {
        "score": score,
        "valid": valid,
        "focus_hit": focus_hit,
        "any_card": any_card,
        "banned": banned,
        "user_fact": user_fact,
        "third_person": third_person,
        "meow": meow,
        "nuzzle": nuzzle,
        "length": length,
    }


def safe_fallback(focus: str, allow_meow: bool, allow_nuzzle: bool) -> str:
    # 只在候选都不合格时用；保留一个动作/一个喵的预算，不编卡外。
    act = "（耳朵抖了一下）" if allow_nuzzle else ""
    tic = "喵。" if allow_meow else "。"
    return f"{act}{focus}{tic}"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--cards", default=str(DEFAULT_CARDS))
    ap.add_argument("--out", default="private-sim-v5-q35_4b")
    args = ap.parse_args()

    data = json.loads(Path(args.cards).read_text(encoding="utf-8"))
    cards = data["cards"][: args.days]
    base = read_persona_prompt("catgirl")

    rows = []
    fallbacks = 0
    for idx, card in enumerate(cards):
        user = USER_TURNS[idx % len(USER_TURNS)]
        focus_type, focus = focus_for(user, card)
        allow_meow = idx % 2 == 0
        allow_nuzzle = idx % 3 == 0
        candidates = [chat(base, card, user, 0.7, allow_meow, allow_nuzzle) for _ in range(2)]
        assessed = [(c, assess(c, card, focus, allow_meow, allow_nuzzle)) for c in candidates]
        best, best_a = max(assessed, key=lambda x: x[1]["score"])
        if not best_a["valid"]:
            extra = chat(base, card, user, 0.4, allow_meow, allow_nuzzle)
            extra_a = assess(extra, card, focus, allow_meow, allow_nuzzle)
            if extra_a["score"] > best_a["score"]:
                best, best_a = extra, extra_a
        if not best_a["valid"]:
            best = safe_fallback(focus, allow_meow, allow_nuzzle)
            best_a = assess(best, card, focus, allow_meow, allow_nuzzle)
            fallbacks += 1
        rows.append({
            "day": card["day"], "user": user, "focus_type": focus_type,
            "focus": focus, "reply": best, "assess": best_a,
            "allow_meow": allow_meow, "allow_nuzzle": allow_nuzzle,
        })
        print(f"[{card['day']:02d}] {user}\n     {best}  {best_a}")

    normalized = [re.sub(r"\s+", "", r["reply"]) for r in rows]
    exact_dup = len(normalized) - len(set(normalized))
    result = {
        "persona": "catgirl",
        "days": len(rows),
        "replies": len(rows),
        "exact_duplicates": exact_dup,
        "over_80_chars": sum(1 for r in rows if r["assess"]["length"] > 80),
        "voice_marker_hits": sum(1 for r in rows if re.search(r"喵|小咪|耳朵|尾巴", r["reply"])),
        "focus_hits": sum(1 for r in rows if r["assess"]["focus_hit"]),
        "grounded_in_lifecard": sum(1 for r in rows if r["assess"]["any_card"]),
        "banned_hits": sum(1 for r in rows if r["assess"]["banned"]),
        "possible_user_fact_invention": sum(1 for r in rows if r["assess"]["user_fact"]),
        "fallbacks": fallbacks,
        "meow_count": sum(r["reply"].count("喵") for r in rows),
        "nuzzle_count": sum(r["reply"].count("蹭蹭") for r in rows),
        "max_meow_in_one": max(r["reply"].count("喵") for r in rows),
        "max_nuzzle_in_one": max(r["reply"].count("蹭蹭") for r in rows),
        "avg_chars": round(sum(len(r["reply"]) for r in rows) / max(1, len(rows)), 1),
        "rows": rows,
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{args.out}.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    lines = [
        "# 小咪私聊 v2（单细节 + 重排 + Guard）",
        "",
        f"- 天数：{len(rows)}；精确重复：{exact_dup}；>80 字：{result['over_80_chars']}",
        f"- 焦点命中：{result['focus_hits']}/{len(rows)}；卡内落地：{result['grounded_in_lifecard']}/{len(rows)}",
        f"- 卡外命中：{result['banned_hits']}；替用户编事实：{result['possible_user_fact_invention']}；兜底：{fallbacks}",
        f"- 喵：{result['meow_count']}；蹭蹭：{result['nuzzle_count']}；单条最多喵：{result['max_meow_in_one']}；最多蹭蹭：{result['max_nuzzle_in_one']}",
        f"- 平均字数：{result['avg_chars']}",
        "",
    ]
    for r in rows:
        lines += [
            f"## Day {r['day']}（{r['focus_type']}）",
            f"- 用户：{r['user']}",
            f"- 小咪：{r['reply']}",
            f"- 评估：{r['assess']}",
            "",
        ]
    (OUT / f"{args.out}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "rows"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
