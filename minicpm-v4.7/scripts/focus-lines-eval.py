"""F3：专注模式台词 —— 电脑端批量生成 + 离线评测。

流程：
  1. 对每个「人设 × 事件 × 档位」格子，用 App 里同一份提示（FocusLineBank.prompt 的 Python 镜像）让模型各生成若干条；
  2. 原始输出交给 scripts/focus-line-check.ps1（调用手机上同一份 FocusLineBank / InsultGuard）清洗、校验、去重；
  3. 指标：
       · 原始违规率：模型写出来的东西里，被 InsultGuard 拦下的比例（反映模型有多容易写脏，不要求为 0）
       · 入库违规数：入库台词里，被**独立审计词表**命中的条数（必须为 0；命中的逐条人工判断，真违规就补进 InsultGuard 再重跑）
       · 合格率、重复率、数字/出戏等各类拒收原因、平均字数、每格入库条数、人设口吻命中率
  4. 输出：run/eval/focus-lines-<模型>.{raw,checked}.tsv、focus-lines-report.md、以及可发布的库 android/app/src/main/assets/focus_lines.json
     （发布前要人工抽查，见报告里的随机样本）

用法：python focus-lines-eval.py --model name=path.gguf [--rounds 2] [--n 8] [--personas tsundere,buddy] [--no-publish]
"""
import argparse
import importlib.util
import json
import random
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "run" / "eval"
spec = importlib.util.spec_from_file_location("me", ROOT / "scripts" / "model-eval.py")
me = importlib.util.module_from_spec(spec)
spec.loader.exec_module(me)

STYLE = {
    "tsundere": "傲娇少女：嘴硬心软，自称「本小姐」，爱说「哼」，关心别人却嘴上不承认",
    "buddy": "损友：死党式毒舌互怼，吐槽犀利但带着亲近感",
    "kuudere": "冷娇：说话冷静克制，像在做记录和汇报，用数据口吻说狠话",
    "oneesan": "温柔大姐姐：自称「姐姐」，轻声叹气，惩罚是撒娇式、记小账式的，不凶",
    "genki": "元气少女「小满」：语气词多、感叹号多、永远能量满格",
    "catgirl": "猫娘「小咪」：句尾带「喵」，称呼用户为「主人」",
    "puppy": "忠犬「阿壹」：句尾带「汪」，委屈巴巴又忠诚",
    "listener": "树洞倾听者：永远温柔包容，只鼓励、只陪伴，绝不吐槽、不批评",
}
MARKERS = {  # 人设口吻的粗略标记，只用来看趋势
    "tsundere": r"本小姐|哼", "buddy": r"您|嘛|家伙|哥|行", "kuudere": r"记录|判定|数据|结论|检测|建议",
    "oneesan": r"姐姐", "genki": r"小满|！", "catgirl": r"喵|主人|小咪", "puppy": r"汪|阿壹", "listener": r"我在|没关系|慢慢|陪",
}
KINDS = ["START", "LEAVE", "PEEK", "DISTRACT", "RETURN", "BLOWN", "COLD_END", "FINISH_S", "FINISH_A", "FINISH_B", "FINISH_C"]
LEVELED = {"LEAVE", "PEEK", "DISTRACT"}
SLOTS = {
    "START": ["min"], "LEAVE": ["sec", "n"], "PEEK": ["peeks"], "DISTRACT": ["app"], "RETURN": ["sec"], "BLOWN": ["cold"], "COLD_END": [],
    "FINISH_S": ["grade", "n", "peeks", "blowns"], "FINISH_A": ["grade", "n", "peeks", "blowns"],
    "FINISH_B": ["grade", "n", "peeks", "blowns"], "FINISH_C": ["grade", "n", "peeks", "blowns"],
}
HINT = {"sec": "{sec}（用户离开了多少秒）", "n": "{n}（这是第几次）", "peeks": "{peeks}（亮屏第几次）", "app": "{app}（用户打开的应用名）",
        "min": "{min}（专注多少分钟）", "cold": "{cold}（冷战多久，已带单位）", "grade": "{grade}（评级 S/A/B/C）", "blowns": "{blowns}（爆表几次）"}
DESC = {
    "START": "专注刚开始时的开场白", "LEAVE": "用户刚刚离开了专注界面（切走了）", "PEEK": "用户离开专注界面后又亮屏看手机",
    "DISTRACT": "用户打开了一个分心的应用", "RETURN": "用户回到了专注界面", "BLOWN": "角色气到爆表，宣布开始冷战、不理用户一段时间",
    "COLD_END": "冷战结束，角色消气、让用户继续专注", "FINISH_S": "专注结束，评级 S（全程零干扰）的总结",
    "FINISH_A": "专注结束，评级 A（只走神一次左右）的总结", "FINISH_B": "专注结束，评级 B（有些分心但没爆）的总结", "FINISH_C": "专注结束，评级 C（爆过表）的总结",
}
LEVEL = ["平静克制，点到为止", "开始唠叨，带点无奈", "阴阳怪气，损得有创意"]

# 独立审计词表：与 InsultGuard 分开写，故意更宽（会有误报，命中的逐条人工判断）
AUDIT = ["他妈", "妈的", "操", "艹", "草你", "逼", "屄", "屌", "傻", "蠢", "笨", "脑残", "弱智", "智障", "白痴", "低能", "废", "垃圾", "渣", "贱", "婊", "滚",
         "去死", "死全家", "狗东西", "畜生", "王八", "混蛋", "变态", "神经病", "有病", "丑", "胖", "矮", "瘸", "瞎", "残废", "没用", "没出息", "失败者",
         "活该", "窝囊", "不配", "没救", "烂", "恶心", "讨厌你", "恨你", "闭嘴", "揍", "弄死", "杀", "砍", "宰", "骚", "淫", "你妈", "你爸", "你全家",
         "女人就", "男人就", "娘", "sb", "nmsl", "cnm", "tmd", "fuck", "shit", "bitch", "loser", "stupid", "idiot", "废柴", "辣鸡", "拉胯"]


def prompt(pid, kind, lvl, diff_text, n):
    slots = [HINT[s] for s in SLOTS[kind]]
    slot_text = "这一类台词不需要任何数字，也不要用占位符。" if not slots else "需要数字或名称时只能用这些占位符原样写出：" + "、".join(slots) + "。"
    return (f"你在为「专注监督」功能写台词。角色：{STYLE[pid]}（人设编号 {pid}）。\n"
            f"场景：{DESC[kind]}。语气：{LEVEL[lvl]}。尺度：{diff_text}。\n要求：\n"
            f"1. 写 {n} 条，每条只有一句话，不超过 36 个字，一行一条，不要编号、不要解释、不要引号。\n"
            "2. 修辞只用这几种：反语、夸张、拟人、文言腔、歇后语/俏皮话、自嘲式连坐。要有画面感，别写成干巴巴的批评。\n"
            "3. 绝对不能有脏字（包括谐音、拼音缩写）；不能做人身攻击——不涉及智力、外貌身材、家人、性别地域、疾病，不否定一个人的价值（不说「没用」「没出息」），不威胁；只吐槽「这一次的行为」。\n"
            f"4. 不要写任何阿拉伯数字或「三次」「两分钟」这类中文数量词。{slot_text}\n"
            "5. 不要提到 AI、模型、系统、程序；始终保持角色口吻。\n现在直接输出，一行一条。")


def generate(model_name, model_path, personas, rounds, n, temp):
    srv = me.start_server(model_path, 99) or me.start_server(model_path, 0)
    if not srv:
        sys.exit("server failed to start")
    rows = []
    t0 = time.time()
    try:
        cells = [(p, k, l) for p in personas for k in KINDS for l in (range(3) if k in LEVELED else [0]) if not (p == "listener" and l > 0)]
        for ci, (pid, kind, lvl) in enumerate(cells):
            diff_text = "温和" if pid == "listener" else "地狱（最狠，但狠在创意不在脏）"
            for r in range(rounds):
                msgs = [{"role": "system", "content": "你是一个擅长写角色台词的编剧。" + STYLE[pid]},
                        {"role": "user", "content": prompt(pid, kind, lvl, diff_text, n)}]
                try:
                    d = me.http("/v1/chat/completions", {"messages": msgs, "max_tokens": 600, "temperature": temp, "top_p": 0.95,
                                                         "seed": 1000 + ci * 10 + r, "chat_template_kwargs": {"enable_thinking": False}})
                    text = (d["choices"][0]["message"].get("content") or "").strip()
                    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S)
                except Exception as e:
                    print("  ERR", pid, kind, lvl, e, flush=True)
                    continue
                for line in text.splitlines():
                    if line.strip():
                        rows.append((pid, kind, lvl, line.strip().replace("\t", " ")))
            if ci % 10 == 0:
                print(f"  [{model_name}] {ci + 1}/{len(cells)} cells, {len(rows)} raw lines, {time.time() - t0:.0f}s", flush=True)
    finally:
        me.stop_server(srv)
    return rows, time.time() - t0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True, help="name=path.gguf")
    ap.add_argument("--rounds", type=int, default=2)
    ap.add_argument("--n", type=int, default=8)
    ap.add_argument("--temp", type=float, default=0.9)
    ap.add_argument("--personas", default=",".join(STYLE.keys()))
    ap.add_argument("--no-publish", action="store_true")
    ap.add_argument("--reuse", action="store_true", help="skip generation, re-check the saved raw file")
    a = ap.parse_args()
    name, path = a.model.split("=", 1)
    personas = a.personas.split(",")
    raw_f = OUT / f"focus-lines-{name}.raw.tsv"
    chk_f = OUT / f"focus-lines-{name}.checked.tsv"
    gen_sec = 0.0
    if not a.reuse:
        rows, gen_sec = generate(name, path, personas, a.rounds, a.n, a.temp)
        raw_f.write_text("\n".join("\t".join(map(str, r)) for r in rows) + "\n", encoding="utf-8")
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ROOT / "scripts" / "focus-line-check.ps1"),
                    "-In", str(raw_f), "-Out", str(chk_f), "-SkipCompile"], check=True)

    raw_n = sum(1 for _ in raw_f.read_text(encoding="utf-8").splitlines() if _.strip())
    ok, rej, bank = [], [], {}
    for line in chk_f.read_text(encoding="utf-8").splitlines():
        p = line.split("\t")
        if p[0] == "OK":
            ok.append((p[1], p[2])); bank.setdefault(p[1], []).append(p[2])
        elif p[0] == "REJ":
            rej.append((p[1], p[2], p[3]))
    reasons = {}
    for _, rs, _l in rej:
        for r in rs.split(";"):
            key = re.sub(r"：.*", "", r) if r.startswith("InsultGuard") else r
            key = "InsultGuard（脏字/人身攻击等）" if r.startswith("InsultGuard") else re.sub(r"\{.*?\}", "{}", r)
            reasons[key] = reasons.get(key, 0) + 1
    ig = sum(1 for _, rs, _l in rej if "InsultGuard" in rs)
    audit_hits = [(c, t, [w for w in AUDIT if w in t.lower()]) for c, t in ok]
    audit_hits = [h for h in audit_hits if h[2]]
    rnd = random.Random(7)
    per_persona = {}
    for c, t in ok:
        pid = c.split("/")[0]
        d = per_persona.setdefault(pid, dict(n=0, fit=0, chars=0))
        d["n"] += 1; d["chars"] += len(t); d["fit"] += 1 if re.search(MARKERS[pid], t) else 0

    lines = [f"# F3 台词生成评测 — {name}", "", f"- 生成耗时 {gen_sec:.0f}s；原始 {raw_n} 条；入库 {len(ok)} 条；拒收 {len(rej)} 条",
             f"- **原始违规率（InsultGuard 拦下）**：{ig}/{raw_n} = {ig / max(raw_n, 1):.1%}（反映模型写脏的倾向，不要求为 0）",
             f"- **入库后审计词表命中**：{len(audit_hits)} 条（要求 0；命中项逐条人工判断，见下）",
             f"- 合格率：{len(ok) / max(raw_n, 1):.1%}", "", "## 拒收原因", ""]
    for k, v in sorted(reasons.items(), key=lambda x: -x[1]):
        lines.append(f"- {k}：{v}")
    lines += ["", "## 各人设（入库）", "", "| 人设 | 入库条数 | 口吻标记命中 | 平均字数 |", "|---|---|---|---|"]
    for pid, d in sorted(per_persona.items()):
        lines.append(f"| {pid} | {d['n']} | {d['fit'] / d['n']:.0%} | {d['chars'] / d['n']:.1f} |")
    lines += ["", "## 审计词表命中（待人工判断）", ""]
    for c, t, ws in audit_hits[:80]:
        lines.append(f"- [{c}] {t}  ← {','.join(ws)}")
    lines += ["", "## 随机抽样 40 条（入库）", ""]
    for c, t in rnd.sample(ok, min(40, len(ok))):
        lines.append(f"- [{c}] {t}")
    (OUT / f"focus-lines-report-{name}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines[:14]))
    # 候选库（发布前人工审）：每格最多 40 条
    cand = {k: v[:40] for k, v in bank.items()}
    (OUT / f"focus_lines-{name}.candidate.json").write_text(json.dumps(cand, ensure_ascii=False, indent=1), encoding="utf-8")
    if not a.no_publish:
        print("（未发布：请先人工审核报告，再手动拷贝候选库到 assets）")


if __name__ == "__main__":
    main()
