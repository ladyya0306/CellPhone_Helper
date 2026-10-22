#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 A 原版 vs A+回指层的匿名盲评包（15 对 = 30 条）。"""
from __future__ import annotations
import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
L1 = ROOT / "run" / "l1"
A = json.loads((L1 / "A-xiaomi-direct-10d.json").read_text(encoding="utf-8"))
C = json.loads((L1 / "A-context-10d.json").read_text(encoding="utf-8"))

a_rows = [(d["day"], r["kind"], r["user"], r["reply"]) for d in A["days"] for r in d["rows"]]
c_rows = [(d["day"], r["kind"], r["user"], r["reply"]) for d in C["days"] for r in d["rows"]]
assert len(a_rows) == len(c_rows) == 40

# 只选 A 与 A+回指确实不同的 14 对，再补 1 对对照
diffs = [i for i in range(40) if a_rows[i][3] != c_rows[i][3]]
same = [i for i in range(40) if a_rows[i][3] == c_rows[i][3]]
selected = diffs[:14] + same[:1]
rng = random.Random(20261022)
rng.shuffle(selected)

items = []
for i in selected:
    day, kind, user, reply = a_rows[i]
    items.append({"arm": "A", "day": day, "kind": kind, "user": user, "reply": reply})
    items.append({"arm": "A+context", "day": day, "kind": kind, "user": user, "reply": c_rows[i][3]})
rng.shuffle(items)
lines = [
    "# A vs A+回指：30 条匿名盲评",
    "",
    "规则：只读“用户”和“小咪”，不知道哪条来自哪个版本。每条打 1–5 分：",
    "1=机械/答非所问；3=能读；5=自然、像她、接得住。",
    "重点看：是否自然、是否真的承接上一轮、有没有硬贴“刚才”。",
    "",
    "| ID | 用户 | 小咪 | 自然 1–5 | 备注 |",
    "|---|---|---|---|---|",
]
key = {}
for n, it in enumerate(items, 1):
    sid = f"S{n:02d}"
    key[sid] = {"arm": it["arm"], "day": it["day"], "kind": it["kind"]}
    lines.append(f"| {sid} | {it['user']} | {it['reply']} |  |  |")
(L1 / "blind-review.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
(L1 / "blind-key.json").write_text(json.dumps(key, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"wrote {len(items)} items to run/l1/blind-review.md")
