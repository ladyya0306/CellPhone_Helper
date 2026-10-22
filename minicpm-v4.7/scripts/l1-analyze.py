#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 L1 A/B 记录补算：话题丰富度、主动发问、上下文回指。"""
from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
L1 = ROOT / "run" / "l1"
CARDS = json.loads((ROOT / "run" / "e1" / "xiaomi-life-30d.json").read_text(encoding="utf-8"))["cards"]
CARD_BY_DAY = {c["day"]: c for c in CARDS}

TURN_KIND = ["meal", "opinion", "companion", "work"]
MEALS = ["咖喱", "炒饭", "饺子", "烤鱼", "火锅", "寿司", "蛋炒饭", "鸡汤面", "饭团", "意面", "牛肉盖饭", "便当", "关东煮", "冷面", "三明治", "披萨", "粥", "面包"]
STOP = {"今天", "主人", "小咪", "一个", "自己", "什么", "这个", "那个", "有点", "觉得", "已经"}


def rows_a(path: Path) -> list[dict]:
    d = json.loads(path.read_text(encoding="utf-8"))
    out = []
    for day in d["days"]:
        for r in day["rows"]:
            out.append({"day": day["day"], "kind": r["kind"], "reply": r["reply"]})
    return out


def rows_b(path: Path) -> list[dict]:
    d = json.loads(path.read_text(encoding="utf-8"))
    out = []
    for day in d["days_data"]:
        for r in day["rows"]:
            out.append({"day": day["day"], "kind": r["kind"], "reply": r["reply"]})
    return out


def bigrams(text: str) -> list[str]:
    zh = re.sub(r"[^\u4e00-\u9fff]", "", text)
    grams = [zh[i:i + 2] for i in range(len(zh) - 1)]
    return [g for g in grams if g not in STOP]


def analyze(rows: list[dict]) -> dict:
    questions = sum(1 for r in rows if re.search(r"[?？]", r["reply"]))
    q_after_first = sum(1 for r in rows if r["kind"] != "meal" and re.search(r"[?？]", r["reply"]))
    context_refs = sum(1 for r in rows if r["kind"] != "meal" and re.search(r"刚才|之前|刚刚|你说|那|上一|前面", r["reply"]))
    follow_meal = sum(1 for r in rows if r["kind"] == "opinion" and any(m in r["reply"] for m in MEALS))
    # 新话题：在非 meal 轮提到 meal，或在 meal 轮提到工作/事件
    new_topic = 0
    for r in rows:
        card = CARD_BY_DAY.get(r["day"], {})
        meal_fact = any(str(card.get(k, "")) and str(card.get(k, "")) in r["reply"] for k in ("breakfast", "lunch", "dinner", "snack"))
        work_fact = str(card.get("work", "")) in r["reply"]
        event_fact = str(card.get("small_event", "")) in r["reply"]
        if r["kind"] != "meal" and meal_fact:
            new_topic += 1
        if r["kind"] == "meal" and (work_fact or event_fact):
            new_topic += 1
    all_grams = [g for r in rows for g in bigrams(r["reply"])]
    ttr = len(set(all_grams)) / max(1, len(all_grams))
    fact_set = set()
    for r in rows:
        card = CARD_BY_DAY.get(r["day"], {})
        for k in ("breakfast", "lunch", "dinner", "snack", "work", "exercise", "small_event", "hook"):
            v = str(card.get(k, ""))
            if v and v in r["reply"]:
                fact_set.add(v)
    return {
        "turns": len(rows),
        "question_replies": questions,
        "question_rate": round(questions / max(1, len(rows)), 3),
        "questions_after_first": q_after_first,
        "context_refs_after_first": context_refs,
        "opinion_with_meal": follow_meal,
        "new_topic_replies": new_topic,
        "distinct_card_facts": len(fact_set),
        "bigram_ttr": round(ttr, 3),
        "avg_chars": round(sum(len(r["reply"]) for r in rows) / max(1, len(rows)), 1),
        "top_bigrams": Counter(all_grams).most_common(8),
    }


def main() -> None:
    a = analyze(rows_a(L1 / "A-xiaomi-direct-10d.json"))
    b = analyze(rows_b(L1 / "B-xiaomi-beats-10d.json"))
    lines = [
        "# L1 A/B：话题丰富度、主动发问、上下文回指", "",
        "| 指标 | A 旧架构 | B 新架构 |",
        "|---|---|---|",
        f"| 主动发问（含问号） | {a['question_replies']}/40（{a['question_rate']:.0%}） | {b['question_replies']}/40（{b['question_rate']:.0%}） |",
        f"| 非首轮主动发问 | {a['questions_after_first']} | {b['questions_after_first']} |",
        f"| 上下文回指（刚才/之前/那…） | {a['context_refs_after_first']} | {b['context_refs_after_first']} |",
        f"| “好吃吗”回应含主菜 | {a['opinion_with_meal']}/10 | {b['opinion_with_meal']}/10 |",
        f"| 非当前话题的新事实 | {a['new_topic_replies']} | {b['new_topic_replies']} |",
        f"| 不同 LifeCard 事实数 | {a['distinct_card_facts']} | {b['distinct_card_facts']} |",
        f"| 二元组 TTR（越高越多样） | {a['bigram_ttr']} | {b['bigram_ttr']} |",
        f"| 平均字数 | {a['avg_chars']} | {b['avg_chars']} |",
        "",
        f"- A 高频二元组：{a['top_bigrams']}",
        f"- B 高频二元组：{b['top_bigrams']}",
    ]
    (L1 / "analysis.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
