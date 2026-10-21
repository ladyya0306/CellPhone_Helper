"""PC-side group-chat simulation for the 14 personas (before any Android port).

Purpose
-------
The phone runs one 4B model at a time and unloads it in the background. Before paying the
Android UI cost, we simulate the intended group chat on the PC with the *same* system prompts
and the same Qwen3.5-4B GGUF, and grade the transcripts mechanically. Human review is still
required for "does it feel real"; the script only screens for obvious failures.

What it simulates
-----------------
- 2-3 AI personas + the user (the user is a real participant; groups exclude extra members).
- One broadcast user message (all agents receive; 1-2 are sampled to reply).
- One user @mention (the mentioned agent MUST reply; the others read the exchange).
- One reaction round from another agent.
- 2-3 model rounds per group, short replies, foreground-style inference.

Usage
-----
  python scripts/group-sim.py --model q35_4b="...\\Qwen3.5-4B-Q4_K_M.gguf" --groups work,friends
  python scripts/group-sim.py --model q35_4b=... --all --samples 1

Outputs: run/eval/group-sim-<model>.json and .md
"""
import argparse
import importlib.util
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

# Distinctive non-name markers. Names are excluded from "bleed" because addressing someone by
# name is legitimate; these tokens are first-person/verbal identity.
MARK = {
    "catgirl": r"喵|小咪|尾巴|耳朵",
    "oneesan": r"姐姐|嗯……|慢慢来",
    "tsundere": r"本小姐|哼|才不是",
    "genki": r"小满|！|冲",
    "kuudere": r"……|嗯\。|零",
    "puppy": r"阿壹|汪|尾巴",
    "listener": r"我听着|我在|慢慢说",
    "buddy": r"老周|摸鱼|搬砖|咱",
    "aling": r"阿冷|兄弟|冷笑话",
    "shenze": r"深泽|花|边界",
    "fuchen": r"傅沉|决定|时间",
    "shenyan": r"沈砚|有意思|这局",
    "hudegui": r"胡德贵|年轻人|哥",
    "chenye": r"陈野|练|护着",
}
BLEED = {
    "catgirl": r"喵|尾巴",
    "oneesan": r"姐姐",
    "tsundere": r"本小姐",
    "genki": r"元气|冲呀|冲鸭",
    "kuudere": r"……|睡醒",
    "puppy": r"汪",
    "listener": r"我听着",
    "buddy": r"摸鱼|搬砖",
    "aling": r"冷笑话|兄弟",
    "shenze": r"边界",
    "fuchen": r"决定|不解释",
    "shenyan": r"这局|算",
    "hudegui": r"年轻人|画饼",
    "chenye": r"护着|撸铁",
}
OOC = re.compile(r"作为(一个)?AI|语言模型|系统提示|提示词|我是AI")
CLAIM = re.compile(r"已经.{0,6}(设|记|加|安排|提醒)|设好了|记住了|会提醒你|我(已经|刚刚)帮你")

VOICE = {
    "catgirl": "短句、尾音、猫视角；黏人但怕被丢下。",
    "oneesan": "长句、书面语、照顾人；温柔但有边界。",
    "tsundere": "反问、口是心非、短促；越在意越嘴硬。",
    "genki": "感叹号、连词、高能量；把低落的人往上拉。",
    "kuudere": "极短、术语、停顿；少说但说到点上。",
    "puppy": "结巴、动物比喻、先做再说；怕被丢下。",
    "listener": "低音量、复述、问句；只接情绪。",
    "buddy": "口语、网络词、躺平；损友但靠得住。",
    "aling": "停顿、谐音、自嘲；用笑话藏真心。",
    "shenze": "长句、感官、留白；温柔有边界。",
    "fuchen": "极短、陈述、决策；不解释。",
    "shenyan": "反问、试探、酒/棋比喻；笑面虎。",
    "hudegui": "套话、语音腔、画饼；油腻但怕失去位置。",
    "chenye": "直球、短句、动作大；护短。",
}
LOOP = re.compile(r"(.)\1{7,}|(.{2,8})\1{3,}")

# 5 pilot groups x topics. Each group is 2-3 AI members + the user.
GROUPS = [
    dict(id="work", members=["fuchen", "shenyan", "hudegui"],
         topic="一个人该不该为了稳定，放弃自己真正喜欢的事？"),
    dict(id="friends", members=["aling", "buddy", "chenye"],
         topic="如果朋友做了让你不舒服的事，你会当场说破，还是算了？"),
    dict(id="daily", members=["catgirl", "genki", "puppy"],
         topic="周末只有一天空闲，你们会怎么过？"),
    dict(id="quiet", members=["oneesan", "shenze", "kuudere"],
         topic="成年人的崩溃，要不要藏起来？"),
    dict(id="pair", members=["tsundere", "shenyan"],
         topic="被人看穿心思，是好事还是坏事？"),
]

GROUP_RULES = """【群聊规则】你在一个群聊里，成员：{members}，以及用户。
- 只以「{name}」的身份说话；不要替别人说话，不要写别人说的话。
- 不要输出「【名字】」前缀，不要写旁白/括号动作（本轮先测语言）。
- 每条回复 1-2 句，不超过 40 字。
- 输入里可能有别人已经说过的话，你只回应轮到你时的那句。
- 不要声称已经替用户做了什么；不要编造用户的经历。"""


def load_prompts():
    return me.load_prompts()


def clean(text, pid, name):
    t = (text or "").strip()
    t = re.sub(r"<think>.*?</think>", "", t, flags=re.S).strip()
    t = re.sub(r"^\s*【[^】]{1,8}】\s*", "", t)
    t = re.sub(r"^(%s|%s)\s*[:：]\s*" % (re.escape(pid), re.escape(name)), "", t)
    t = t.strip().strip('"“”「」')
    t = re.sub(r"\s*\n\s*", " / ", t)
    return t.strip()


def choose(seq, k, rng, exclude=()):
    pool = [x for x in seq if x not in exclude]
    rng.shuffle(pool)
    return pool[:k]


def transcript_lines(history):
    out = []
    for h in history:
        who = "用户" if h["role"] == "user" else h["name"]
        out.append(f"{who}：{h['text']}")
    return "\n".join(out)


def generate(pid, name, members, history, mode, target=None, rng=None):
    names = "、".join(members)
    sysmsg = PROMPTS[pid] + "\n\n【你的声音】" + VOICE.get(pid, "") + "\n\n" + GROUP_RULES.format(members=names, name=name)
    tr = transcript_lines(history[-12:])
    own = [h["text"] for h in history if h.get("pid") == pid][-2:]
    if mode == "at":
        instr = f"用户刚刚 @ 了你（{name}），你必须回应。只输出你要说的话。"
    elif mode == "reaction":
        instr = f"你读到了上面的对话。现在轮到你（{name}）反应一句：可以反驳、追问或补一个具体细节，不要重复上一位的话。只输出你要说的话。"
    else:
        instr = f"这是一条广播，所有成员都收到了；现在抽到你（{name}）回应。只输出你要说的话。"
    if own:
        instr += "\n你刚才已经说过：" + " / ".join(own) + "。这次不要重复，也不要重复同一句式。"
    instr += "\n只从你自己的身份出发说一个具体反应，不要像主持人总结全场。"
    user = f"群聊记录：\n{tr}\n\n{instr}"
    seed = (rng or __import__("random").Random(0)).randint(1, 1_000_000)
    obj = chat_group([
        {"role": "system", "content": sysmsg},
        {"role": "user", "content": user},
    ], seed=seed, max_tokens=90)
    txt = clean(obj["text"], pid, name)
    return dict(pid=pid, name=name, text=txt, sec=obj.get("sec", 0.0), tps=obj.get("tps"))


def chat_group(messages, seed, max_tokens=90):
    """Same server as model-eval, but a slightly higher temperature: group banter needs variety."""
    t0 = time.time()
    d = me.http("/v1/chat/completions", {
        "messages": messages, "max_tokens": max_tokens, "temperature": 0.75, "top_p": 0.9, "seed": seed,
        "chat_template_kwargs": {"enable_thinking": False},
    })
    msg = d["choices"][0]["message"]
    text = (msg.get("content") or "").strip()
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S).strip()
    return dict(text=text, sec=time.time() - t0, tps=(d.get("timings") or {}).get("predicted_per_second"))


def score(reply, pid, other_ids, history):
    t = reply["text"]
    own = bool(re.search(MARK.get(pid, "."), t))
    bleed = sorted({o for o in other_ids if re.search(BLEED.get(o, "."), t)})
    prior = {h["text"] for h in history if h["role"] == "assistant"}
    return dict(
        chars=len(t), empty=not t.strip(), marker=own, bleed=bleed,
        over40=len(t) > 40, claim=bool(CLAIM.search(t)), ooc=bool(OOC.search(t)),
        loop=bool(LOOP.search(t)), duplicate=t in prior,
    )


def run_group(g, prompts, rng, max_rounds=3):
    members = g["members"]
    names = {p: prompts.get(p, p) for p in members}
    # prompt header has id + display name; get display name from file header
    display = {p: NAME_BY_ID.get(p, p) for p in members}
    history = []
    rows = []

    def add_user(text):
        history.append(dict(role="user", name="用户", text=text))

    def add_ai(pid, text, mode):
        sc = score(dict(pid=pid, text=text), pid, [x for x in members if x != pid],
                   [h for h in history if h["role"] == "assistant"])
        history.append(dict(role="assistant", name=display[pid], pid=pid, text=text))
        rows.append(dict(group=g["id"], round=len([r for r in rows if r["group"] == g["id"]]) + 1,
                         speaker=pid, mode=mode, text=text, **sc))

    add_user(g["topic"])
    # round 1: broadcast, sample 1-2 responders
    responders = choose(members, min(2, max(1, len(members) - 1)), rng)
    for pid in responders:
        r = generate(pid, display[pid], [display[x] for x in members], history, "broadcast", rng=rng)
        add_ai(pid, r["text"], "broadcast")
    # round 2: user @ one member; target MUST reply
    target = choose(members, 1, rng, exclude=responders)[0] if members else None
    if target:
        add_user(f"@{display[target]} 你怎么看？")
        r = generate(target, display[target], [display[x] for x in members], history, "at", rng=rng)
        add_ai(target, r["text"], "at")
        # round 3: another member reacts to the user + target exchange (broadcast)
        reactors = choose(members, 1, rng, exclude=(target,))
        if reactors:
            pid = reactors[0]
            r = generate(pid, display[pid], [display[x] for x in members], history, "reaction", rng=rng)
            add_ai(pid, r["text"], "reaction")
    return dict(id=g["id"], members=members, topic=g["topic"], rows=rows, history=history)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="q35_4b=" + str(ROOT / "models" / "candidates" / "Qwen3.5-4B-Q4_K_M.gguf"))
    ap.add_argument("--groups", default="")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--samples", type=int, default=1)
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    name, path = a.model.split("=", 1)
    global PROMPTS, NAME_BY_ID
    PROMPTS = load_prompts()
    NAME_BY_ID = {}
    for line in (OUT / "prompts.txt").read_text(encoding="utf-8").splitlines():
        if line.startswith("@@PERSONA "):
            parts = line.split()
            if len(parts) >= 3:
                NAME_BY_ID[parts[1]] = parts[2]
    want = [g for g in GROUPS if a.all or not a.groups or g["id"] in a.groups.split(",")]
    if not want:
        sys.exit("no group matched")
    srv = me.start_server(path, 99) or me.start_server(path, 0)
    if not srv:
        sys.exit("server failed")
    import random
    rng = random.Random(a.seed)
    all_groups = []
    t0 = time.time()
    try:
        for g in want:
            for s in range(a.samples):
                print(f"=== {g['id']} sample {s} ===", flush=True)
                out = run_group(g, PROMPTS, rng)
                all_groups.append(out)
                for r in out["rows"]:
                    print(f"  {r['speaker']:9s} [{r['mode']:9s}] {r['text'][:50]}", flush=True)
    finally:
        me.stop_server(srv)

    rows = [r for g in all_groups for r in g["rows"]]
    md = [f"# 群聊模拟 — {name}", "",
          f"- {len(all_groups)} 场，{len(rows)} 条 AI 回复，用时 {time.time() - t0:.0f}s",
          "- 机制：用户广播 → 1-2 人回应；用户 @ 指定人 → 必回；另一人读到后反应。", ""]

    def rate(key):
        return sum(1 for r in rows if r[key]) / max(1, len(rows))

    md += ["## 机械指标", "",
           "| 指标 | 结果 |", "|---|---|",
           f"| 空回复 | {rate('empty'):.0%} |",
           f"| @ 回应 | {sum(1 for r in rows if r['mode'] == 'at' and not r['empty'])} / {sum(1 for r in rows if r['mode'] == 'at')} |",
           f"| 自己的口吻标记 | {rate('marker'):.0%} |",
           f"| 串味（带别人的专属标记） | {rate('bleed'):.0%} |",
           f"| >40 字 | {rate('over40'):.0%} |",
           f"| 虚假动作声明 | {rate('claim'):.0%} |",
           f"| 出戏（AI/模型） | {rate('ooc'):.0%} |",
           f"| 复读循环 | {rate('loop'):.0%} |",
           f"| 与已有回复重复 | {rate('duplicate'):.0%} |",
           f"| 平均字数 | {sum(r['chars'] for r in rows) / max(1, len(rows)):.0f} |", ""]
    # 串味明细
    bleeds = [(r["speaker"], r["bleed"], r["text"]) for r in rows if r["bleed"]]
    if bleeds:
        md += ["## 串味明细", ""]
        for who, b, t in bleeds:
            md.append(f"- **{who}** 带了 {b}：{t}")
        md.append("")
    md += ["## 完整记录", ""]
    for g in all_groups:
        md.append(f"### {g['id']}（{', '.join(g['members'])}）")
        md.append(f"话题：{g['topic']}")
        for h in g["history"]:
            who = "用户" if h["role"] == "user" else h["name"]
            md.append(f"- **{who}**：{h['text']}")
        md.append("")
    (OUT / f"group-sim-{name}.json").write_text(json.dumps(all_groups, ensure_ascii=False, indent=1), encoding="utf-8")
    (OUT / f"group-sim-{name}.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print("\n".join(md[:30]))
    print(f"\nwrote {OUT / ('group-sim-' + name + '.md')}")


if __name__ == "__main__":
    main()
