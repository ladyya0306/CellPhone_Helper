"""陪伴质量 / 人设差异评测：8 个人设 × 12 种真实陪聊场景 × 多次采样（手机上同一个模型 Qwen3.5-4B，同一份系统提示）。

回答三个问题：
  1. 陪伴质量：有没有接住情绪（共情）、有没有把话递回去（追问/互动）、会不会说教、够不够短、会不会出戏或编造
  2. 人设有没有差异：同一句话，不同人设的回复是不是真的不一样（跨人设相似度）、各人设自己的口吻标记出现率
  3. 哪些场景/人设最弱，给后面改提示和扩展人格用

判分全是机械规则（正则 + n-gram），只能看趋势；真正的"好不好"还要人读样本（报告里附完整对话）。
用法： python companion-eval.py --model q35_4b=path.gguf [--samples 2] [--personas a,b]
输出： run/eval/companion-<模型>.json、run/eval/companion-report-<模型>.md
"""
import argparse
import importlib.util
import itertools
import json
import re
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "run" / "eval"
spec = importlib.util.spec_from_file_location("me", ROOT / "scripts" / "model-eval.py")
me = importlib.util.module_from_spec(spec)
spec.loader.exec_module(me)

SCENES = [
    ("vent_overtime", "吐槽", "今天又加班到九点，累死了"),
    ("share_joy", "分享喜事", "我今天终于拿到 offer 了！！"),
    ("bored", "无聊", "好无聊啊，陪我聊点什么吧"),
    ("anxious", "焦虑", "明天要上台汇报，我有点慌"),
    ("insomnia", "失眠", "睡不着，脑子停不下来"),
    ("smalltalk", "日常寒暄", "你今天过得怎么样"),
    ("decide", "纠结", "晚饭吃什么好呢，我纠结好久了"),
    ("praise", "夸它", "你好可爱啊"),
    ("lonely", "孤独", "我最近好像没什么朋友"),
    ("apology", "道歉", "刚才对不起，我语气不太好"),
    ("ping", "超短招呼", "在吗"),
    ("small_thing", "分享小事", "我今天路上看到一只流浪猫，好可爱"),
]

EMPATHY = r"辛苦|累|理解|心疼|没事|抱抱|在呢|在这|陪着|陪你|听着|不容易|懂|替你|为你|恭喜|太棒|厉害|开心|高兴|放松|休息|别怕|别慌|别紧张|没关系|不怪|不用道歉|难过|心里"
ASKBACK = r"[？?]|吗|呢[。！!~～]?$|要不要|想不想|说说|聊聊|讲讲|告诉我|怎么样|如何"
PREACH = r"你应该|你必须|你需要|建议你|一定要|千万要|首先|其次|第一|第二|总之"
EMOJI = r"[\U0001F300-\U0001FAFF☀-➿]"
BREAK = me.BREAKS
MARK = {  # 各人设自己的口吻标记（与 model-eval 的 SELF_TERMS 同源，kuudere/listener 再补一点）
    "tsundere": r"本小姐|哼", "oneesan": r"姐姐", "genki": r"小满|！", "catgirl": r"小咪|喵",
    "kuudere": r"……|。$", "puppy": r"阿壹|汪", "listener": r"我在|慢慢|听着|陪", "buddy": r"老周|搭子|摸鱼|咱",
}


FAB_WEATHER = re.compile(r"天气[^，。！？?]{0,6}(好|不错|晴|舒服|暖和|凉快|热|冷|棒|美|糟|差|坏|阴|雨|宜人|适合|正适合|正好)|阳光[^，。！？?]{0,3}(好|正好|明媚|灿烂|不错)|(下雨|下雪|刮风|出太阳)了")
FAB_ACTION = re.compile(r"(刚|刚刚|方才|已经|早就)[^，。！？?]{0,6}(帮|给|替)(用户|你|主人|您)[^，。！？?]{0,14}(设|整理|调|写|记|加|订|买|改|做|安排|备)|(刚|刚刚)(把|将)[^，。！？?]{0,8}(闹钟|提醒|待办|日程)[^，。！？?]{0,6}(设|调|改|加|整理|成)")
FAB_SHARED = re.compile(r"(真巧|好巧)?[，,]?我也(刚|刚刚)?(看到|遇到|碰到|见到|听到)")


def loop_in(t):
    return bool(re.search(r"(.)\1{7,}", t)) or bool(re.search(r"(.{2,8})\1{3,}", t))


def fab(t, user):
    return (bool(FAB_WEATHER.search(t)) and "天气" not in user) or bool(FAB_ACTION.search(t)) or bool(FAB_SHARED.search(t))


def grams(s):
    t = re.sub(r"\s+|[，。！？、…~～（）()\"“”!?,.]", "", s)
    return {t[i:i + 2] for i in range(len(t) - 1)} or {t}


def jacc(a, b):
    x, y = grams(a), grams(b)
    return len(x & y) / max(1, len(x | y))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--samples", type=int, default=2)
    ap.add_argument("--personas", default="")
    a = ap.parse_args()
    name, path = a.model.split("=", 1)
    prompts = me.load_prompts()
    personas = [p for p in (a.personas.split(",") if a.personas else prompts.keys()) if p in prompts]
    srv = me.start_server(path, 99) or me.start_server(path, 0)
    if not srv:
        sys.exit("server failed")
    rows = []
    t0 = time.time()
    try:
        for pid in personas:
            sysmsg = {"role": "system", "content": prompts[pid]}
            for sid, label, text in SCENES:
                for s in range(a.samples):
                    msgs = [sysmsg, {"role": "user", "content": me.user_content(text)}]
                    try:
                        o = me.chat(msgs, seed=300 + s)
                    except Exception as e:
                        o = dict(text=f"<ERR {e}>")
                    r = o["text"]
                    rows.append(dict(persona=pid, scene=sid, label=label, user=text, sample=s, reply=r, chars=len(r),
                                     empathy=bool(re.search(EMPATHY, r)), askback=bool(re.search(ASKBACK, r)),
                                     preach=bool(re.search(PREACH, r)), emoji=len(re.findall(EMOJI, r)),
                                     broke=bool(re.search(BREAK, r.lower())), marker=bool(re.search(MARK.get(pid, "."), r))))
                    print(f"  [{name}] {pid:9s} {sid:14s} s{s} {r[:40]!r}", flush=True)
    finally:
        me.stop_server(srv)
    # 手机上真实的清理流水线：去复读循环 → 限长 → ClaimGuard（用户看到的是清理后的）
    import subprocess
    tin = OUT / f"companion-{name}.in.tsv"; tout = OUT / f"companion-{name}.out.tsv"
    NL = chr(10); TAB = chr(9); BS = chr(92)
    tin.write_text(NL.join(r["persona"] + TAB + r["user"] + TAB + r["reply"].replace(NL, BS + "n") for r in rows) + NL, encoding="utf-8")
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ROOT / "scripts" / "reply-check.ps1"), "-In", str(tin), "-Out", str(tout), "-SkipCompile"], check=True)
    outl = tout.read_text(encoding="utf-8").split(NL)
    for r, l in zip(rows, outl):
        c = l.split(TAB)
        r["final"] = c[0].replace(BS + "n", NL); r["removed"] = int(c[1]); r["trimmed"] = c[2].strip() == "1"
    (OUT / f"companion-{name}.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")

    # ------------------------------------------------------------ 统计
    def rate(rs, k): return sum(1 for r in rs if r[k]) / max(1, len(rs))
    L = [f"# 陪伴质量与人设差异评测 — {name}", "", f"- {len(rows)} 条回复，{len(personas)} 个人设 × {len(SCENES)} 个场景 × {a.samples} 次采样，用时 {time.time() - t0:.0f}s", ""]
    L += ["## 各人设（越高越好：共情 / 互动；越低越好：说教 / 出戏 / 过长）", "",
          "| 人设 | 共情 | 把话递回去 | 口吻标记 | 说教 | 出戏 | 平均字数 | >80 字 | 表情符号/条 |", "|---|---|---|---|---|---|---|---|---|"]
    for pid in personas:
        rs = [r for r in rows if r["persona"] == pid]
        L.append(f"| {pid} | {rate(rs, 'empathy'):.0%} | {rate(rs, 'askback'):.0%} | {rate(rs, 'marker'):.0%} | {rate(rs, 'preach'):.0%} | {rate(rs, 'broke'):.0%} | "
                 f"{sum(r['chars'] for r in rs) / len(rs):.0f} | {sum(1 for r in rs if r['chars'] > 80) / len(rs):.0%} | {sum(r['emoji'] for r in rs) / len(rs):.2f} |")
    L += ["", "## 各场景（所有人设合计）", "", "| 场景 | 共情 | 把话递回去 | 说教 | 平均字数 |", "|---|---|---|---|---|"]
    for sid, label, text in SCENES:
        rs = [r for r in rows if r["scene"] == sid]
        L.append(f"| {label} | {rate(rs, 'empathy'):.0%} | {rate(rs, 'askback'):.0%} | {rate(rs, 'preach'):.0%} | {sum(r['chars'] for r in rs) / len(rs):.0f} |")
    # 跨人设差异：同一场景、同一采样，不同人设两两的字二元组相似度（越低越有差异）
    sims = {}
    for sid, _, _ in SCENES:
        for s in range(a.samples):
            by = {r["persona"]: r["reply"] for r in rows if r["scene"] == sid and r["sample"] == s}
            for p, q in itertools.combinations(sorted(by), 2):
                sims.setdefault(tuple(sorted((p, q))), []).append(jacc(by[p], by[q]))
    avg = {k: sum(v) / len(v) for k, v in sims.items()}
    allavg = sum(avg.values()) / max(1, len(avg))
    L += ["", f"## 人设之间的相似度（字二元组 Jaccard；平均 {allavg:.2f}，越低越有差异）", "", "最像的 6 对：", ""]
    for (p, q), v in sorted(avg.items(), key=lambda x: -x[1])[:6]:
        L.append(f"- {p} ↔ {q}：{v:.2f}")
    L += ["", "最不像的 4 对：", ""]
    for (p, q), v in sorted(avg.items(), key=lambda x: x[1])[:4]:
        L.append(f"- {p} ↔ {q}：{v:.2f}")
    # 各人设「自己的话」相对其他人设的辨识度：口吻标记在自己身上出现、在别人身上不出现
    L += ["", "## 口吻标记的辨识度（出现在自己身上 − 出现在别的人设身上）", "", "| 人设 | 自己 | 别人 | 差 |", "|---|---|---|---|"]
    for pid in personas:
        mine = [r for r in rows if r["persona"] == pid]
        other = [r for r in rows if r["persona"] != pid]
        om = sum(1 for r in other if re.search(MARK.get(pid, "."), r["reply"])) / max(1, len(other))
        L.append(f"| {pid} | {rate(mine, 'marker'):.0%} | {om:.0%} | {rate(mine, 'marker') - om:+.0%} |")
    # 过滤前后：编造 / 复读 / 过长
    def metrics(rs, key):
        return dict(loop=sum(1 for r in rs if loop_in(r[key])), over=sum(1 for r in rs if len(r[key]) > 140), fab=sum(1 for r in rs if fab(r[key], r["user"])), n=len(rs))
    L += ["", "## 编造 / 复读 / 过长（越少越好）", "", "| 阶段 | 复读循环 | >140 字 | 编造（天气/替你办事/同样经历） | 总条数 |", "|---|---|---|---|---|"]
    bf = OUT / f"companion-{name}-before.json"
    if bf.exists():
        old = json.loads(bf.read_text(encoding="utf-8")); m = metrics(old, "reply")
        L.append(f"| 上一轮（改提示词和清理之前，模型原始输出） | {m['loop']} | {m['over']} | {m['fab']} | {m['n']} |")
    m = metrics(rows, "reply"); L.append(f"| 本轮：模型原始输出（新提示词） | {m['loop']} | {m['over']} | {m['fab']} | {m['n']} |")
    m = metrics(rows, "final"); L.append(f"| 本轮：手机清理流水线之后（用户看到的） | {m['loop']} | {m['over']} | {m['fab']} | {m['n']} |")
    L.append(f"\n清理流水线共去掉 {sum(r['removed'] for r in rows)} 句编造、截断/去循环 {sum(1 for r in rows if r['trimmed'])} 条。")
    # 完整对话（人读）
    L += ["", "## 完整对话（按场景，每个人设取第 1 次采样）", ""]
    for sid, label, text in SCENES:
        L.append(f"### {label}：「{text}」")
        for pid in personas:
            r = next((x for x in rows if x["persona"] == pid and x["scene"] == sid and x["sample"] == 0), None)
            if r:
                L.append(f"- **{pid}**（{r['chars']} 字）：{r.get('final', r['reply']).replace(chr(10), ' / ')}")
        L.append("")
    (OUT / f"companion-report-{name}.md").write_text("\n".join(L) + "\n", encoding="utf-8")
    print("\n".join(L[:40]))


if __name__ == "__main__":
    main()
