#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""A + 上下文回指层：在旧架构回复上补 12/40 的“刚才你问…”前缀。
不调模型；只验证 A 补回指后指标是否还稳。
"""
from __future__ import annotations
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
L1 = ROOT / "run" / "l1"
SRC = L1 / "A-xiaomi-direct-10d.json"
OUT = L1 / "A-context-10d.json"
STOP = {"今天", "主人", "小咪", "一个", "自己", "什么", "这个", "那个", "有点", "觉得", "已经"}


def bigrams(text: str) -> list[str]:
    zh = re.sub(r"[^\u4e00-\u9fff]", "", text)
    grams = [zh[i:i + 2] for i in range(len(zh) - 1)]
    return [g for g in grams if g not in STOP]


def trim(text: str, limit: int = 40) -> str:
    if len(text) <= limit:
        return text
    cut = max(text.rfind("。", 0, limit), text.rfind("！", 0, limit), text.rfind("？", 0, limit))
    return text[: cut + 1] if cut >= 15 else text[:limit].rstrip("，,、 ") + "。"


def main() -> None:
    d = json.loads(SRC.read_text(encoding="utf-8"))
    prefix = {
        "opinion": [
            "刚才你问好不好吃，", "你前面问味道，", "说到刚才那顿，",
            "你刚问好不好吃，", "刚才聊到味道，", "你前面提的那顿，",
            "刚才那口，", "说到味道，", "你刚问的那顿，", "前面说的那顿，",
        ],
        "companion": ["刚才你问和谁吃，", "前面你问和谁，"],
    }
    pi = {"opinion": 0, "companion": 0}
    added = 0
    for day in d["days"]:
        for r in day["rows"]:
            if r["kind"] == "opinion" or (r["kind"] == "companion" and added < 14):
                bank = prefix.get(r["kind"], [])
                if bank:
                    p = bank[pi[r["kind"]] % len(bank)]
                    pi[r["kind"]] += 1
                else:
                    p = ""
                if p and not re.search(r"刚才|之前|前面|你说", r["reply"]):
                    r["reply"] = trim(p + r["reply"])
                    added += 1
            if added >= 14:
                break
        if added >= 14:
            break
    # 重算基础指标
    rows = [r for day in d["days"] for r in day["rows"]]
    norm = [re.sub(r"\s+", "", r["reply"]) for r in rows]
    grams = [g for r in rows for g in bigrams(r["reply"])]
    d["exact_duplicates"] = len(norm) - len(set(norm))
    d["over_40_chars"] = sum(1 for r in rows if len(r["reply"]) > 40)
    d["question_replies"] = sum(1 for r in rows if re.search(r"[?？]", r["reply"]))
    d["context_refs"] = sum(1 for r in rows if re.search(r"刚才|之前|刚刚|你说|那|上一|前面", r["reply"]))
    d["bigram_ttr"] = round(len(set(grams)) / max(1, len(grams)), 3)
    d["added_context"] = added
    OUT.write_text(json.dumps(d, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in d.items() if k != "days"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
