#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""针对 360 天里剩余的兜底行做短句重生成，不重跑全量。
输入：run/e1/private-sim-v16-360d.json
输出：run/e1/private-sim-v17-360d.json / .md
"""
from __future__ import annotations

import importlib.util
import json
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "run" / "e1"
SRC = OUT / "private-sim-v16-360d.json"

spec = importlib.util.spec_from_file_location("sim", ROOT / "scripts" / "e1-private-sim-v2.py")
sim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sim)


def is_fallback(row: dict) -> bool:
    r = row["reply"]
    f = row["focus"]
    return r.startswith("（耳朵抖了一下）") or r in (f, f + "。", f + "喵。")


def chat_strict(base: str, card: dict, focus: str, temperature: float) -> str:
    system = base + (
        "\n\n【生活卡】只允许说下面这一件今天发生的事；不要编卡外内容。\n"
        f"【本轮唯一细节】{focus}\n"
        "【格式】只回 1 句，20–45 字，必须包含这个细节；不要“喵”，不要动作注释，不要提问，不要公式化收尾。\n"
        "自称小咪或我；不要用她指自己。"
    )
    payload = {
        "model": "q35_4b",
        "messages": [{"role": "system", "content": system},
                     {"role": "user", "content": "用一句话说说你现在的状态。"}],
        "temperature": temperature,
        "max_tokens": 80,
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


def main() -> None:
    d = json.loads(SRC.read_text(encoding="utf-8"))
    rows = d["rows"]
    cards = {c["day"]: c for c in json.loads((OUT / "xiaomi-life-360d.json").read_text(encoding="utf-8"))["cards"]}
    base = sim.read_persona_prompt("catgirl")
    seen = {re.sub(r"\s+", "", r["reply"]) for r in rows}
    rescued = 0
    for idx, row in enumerate(rows):
        if not is_fallback(row):
            continue
        card, focus = cards[row["day"]], row["focus"]
        allow_meow, allow_nuzzle = row["allow_meow"], row["allow_nuzzle"]
        allow_e, allow_s = row["allow_formula_end"], row["allow_formula_start"]
        candidates = [chat_strict(base, card, focus, 0.6) for _ in range(2)]
        assessed = [(c, sim.assess(c, card, focus, allow_meow, allow_nuzzle,
                                   allow_e, allow_s, seen)) for c in candidates]
        best, best_a = max(assessed, key=lambda x: x[1]["score"])
        if best_a["valid"]:
            seen.discard(re.sub(r"\s+", "", row["reply"]))
            seen.add(re.sub(r"\s+", "", best))
            row["reply"] = best
            row["assess"] = best_a
            rescued += 1
    # 重算指标
    normalized = [re.sub(r"\s+", "", r["reply"]) for r in rows]
    result = {
        "persona": "catgirl", "days": len(rows), "replies": len(rows),
        "exact_duplicates": len(normalized) - len(set(normalized)),
        "over_80_chars": sum(1 for r in rows if r["assess"]["length"] > 80),
        "voice_marker_hits": sum(1 for r in rows if re.search(r"喵|小咪|耳朵|尾巴", r["reply"])),
        "focus_hits": sum(1 for r in rows if r["assess"]["focus_hit"]),
        "grounded_in_lifecard": sum(1 for r in rows if r["assess"]["grounded"]),
        "banned_hits": sum(1 for r in rows if r["assess"]["banned"]),
        "possible_user_fact_invention": sum(1 for r in rows if r["assess"]["user_fact"]),
        "fallbacks": sum(1 for r in rows if is_fallback(r)),
        "rescued": rescued,
        "meow_count": sum(r["reply"].count("喵") for r in rows),
        "nuzzle_count": sum(r["reply"].count("蹭蹭") for r in rows),
        "max_meow_in_one": max(r["reply"].count("喵") for r in rows),
        "max_nuzzle_in_one": max(r["reply"].count("蹭蹭") for r in rows),
        "formula_end_count": sum(1 for r in rows if r["assess"]["formula_end"]),
        "formula_start_count": sum(1 for r in rows if r["assess"]["formula_start"]),
        "distinct_starts": len({r["reply"][:4] for r in rows}),
        "avg_chars": round(sum(len(r["reply"]) for r in rows) / max(1, len(rows)), 1),
        "rows": rows,
    }
    (OUT / "private-sim-v17-360d.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = [
        "# 小咪 360 天：兜底行短句重生成",
        "",
        f"- 剩余兜底：{result['fallbacks']}/{len(rows)}（{result['fallbacks']/len(rows):.1%}）",
        f"- 本轮救回：{rescued}",
        f"- 精确重复：{result['exact_duplicates']}；落地：{result['grounded_in_lifecard']}/{len(rows)}",
        f"- 喵：{result['meow_count']}；蹭蹭：{result['nuzzle_count']}；平均字数：{result['avg_chars']}",
        f"- 公式化收尾/开头：{result['formula_end_count']} / {result['formula_start_count']}",
    ]
    (OUT / "private-sim-v17-360d.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "rows"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
