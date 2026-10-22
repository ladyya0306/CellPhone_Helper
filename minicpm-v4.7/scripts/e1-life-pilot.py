#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""E1 试点：小咪 30 天 LifeCard（日常陪伴）。
不调用模型；只生成“她今天的生活状态/话题钩子”，供私聊提示词使用。
用法：python minicpm-v4.7/scripts/e1-life-pilot.py --days 30
输出：run/e1/xiaomi-life-30d.json / .md
"""
from __future__ import annotations

import argparse
import json
import random
from collections import Counter
from pathlib import Path

PERSONA = {
    "id": "catgirl",
    "name": "小咪",
    "props": ["纸箱", "猫耳", "尾巴", "小鱼干", "窗台", "猫咖"],
}

WEATHER = ["晴", "多云", "小雨", "阴", "大风", "闷热"]
WEEKDAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

BREAKFAST = [
    "小鱼干粥", "猫爪吐司", "饭团", "酸奶配麦片", "蛋羹",
    "猫咖剩下的可颂", "豆浆油条", "煎蛋三明治", "南瓜粥",
    "金枪鱼三明治", "小笼包", "燕麦杯", "烤红薯", "芝士蛋饼",
    "红豆面包", "火腿蛋堡", "紫薯粥", "奶酪贝果", "猫爪松饼",
    "鸡肉粥", "玉米浓汤", "三文鱼饭团", "花生酱吐司", "蔬菜蛋卷",
    "牛奶燕麦", "叉烧包", "南瓜饼", "鸡蛋灌饼", "水果沙拉",
    "热狗",
]
LUNCH = [
    "猫咖员工餐咖喱饭", "番茄鸡蛋面", "鸡肉沙拉", "饭团配味噌汤",
    "蛋包饭", "牛肉盖饭", "三文鱼饭", "蔬菜汤配面包", "炒饭",
    "乌冬面", "便当", "饺子", "烤鱼定食", "意面", "关东煮",
    "照烧鸡饭", "猪排饭", "番茄牛腩饭", "冷面", "天妇罗饭",
    "麻婆豆腐饭", "鸡肉卷", "海鲜炒饭", "咖喱乌冬", "蛋包意面",
    "烤肉饭", "鱼香肉丝饭", "寿喜锅", "三明治套餐", "蔬菜咖喱",
]
DINNER = [
    "小鱼干拌饭", "火锅", "烤鱼", "咖喱", "寿司", "鸡汤面",
    "蛋炒饭", "烤肉", "蔬菜锅", "饺子", "汉堡", "粥配小菜",
    "披萨", "炒乌冬", "饭团",
    "关东煮", "烤鸡", "牛肉锅", "鳗鱼饭", "番茄意面",
    "鸡肉丸子锅", "炒面", "水饺", "烤蔬菜", "海鲜粥",
    "猪排", "亲子丼", "寿喜烧", "披萨", "炒河粉",
]
SNACK = [
    "小鱼干", "猫爪饼干", "布丁", "酸奶", "苹果", "铜锣烧",
    "奶酪", "红薯干", "冰淇淋", "栗子",
]
EXERCISE = [
    "追激光笔", "爬猫架", "窗台伸展", "扑纸团", "猫式瑜伽",
    "楼道散步", "追尾巴", "绕小区走一圈", "猫步练习", "晒太阳拉伸",
    "逗猫棒跳跃", "趴着抬腿", "纸箱跳进跳出", "爬楼梯", "平衡走",
    "扑窗帘", "伸懒腰", "打滚", "追小满跑步", "慢慢踩奶",
    "追光斑", "猫爬架冲刺", "纸箱躲猫猫", "慢走消食", "拉伸后腿",
    "玩毛线球", "跳沙发", "猫式俯卧撑", "呼吸练习", "窗台踩奶",
]
WORK = [
    "猫咖早班", "猫咖晚班", "擦杯子", "端咖啡", "学拉花",
    "盘点库存", "给猫梳毛", "清理猫砂", "店休整理纸箱", "采购猫粮",
    "发传单", "洗围裙", "帮店长记账", "陪客人选猫", "给新猫起名",
    "搬猫爬架", "晒杯子", "贴价签", "拖地", "学做新品",
    "给猫换水", "擦窗台", "整理菜单", "给客人倒水", "学收银",
    "洗杯子", "晒猫垫", "补猫粮", "贴海报", "关店锁门",
]
EVENTS = [
    "猫咖来了一只新猫", "纸箱被雨淋湿", "店长给她带小鱼干",
    "打雷，躲进被子里", "窗台晒太阳睡着", "咖啡机坏了",
    "客人夸她耳朵", "猫咖排队", "她打翻一杯拿铁", "捡到一颗纽扣",
    "看到楼下流浪猫", "纸箱城堡扩建", "称体重", "买新的逗猫棒",
    "猫咖休息日", "去宠物店看阿壹", "被吸尘器吓到", "给纸箱贴贴纸",
    "偷吃小鱼干", "看窗外的鸟", "洗澡怕水", "店长让她学拉花",
    "客人落下围巾", "雨天客人少", "发现窗台有蚂蚁", "猫咖周年庆",
    "打疫苗", "夜里看楼下的灯", "把你的旧围巾收进纸箱",
    "听见你回家开门的声音",
]
MOOD = ["开心", "懒洋洋", "有点困", "精神", "小得意", "想被摸头", "有点不安", "平静"]
ENERGY = ["满格", "还行", "半格", "低电量", "想充电"]
HOOKS = [
    "今天店里来了只新猫", "纸箱城堡又扩建了", "打雷的时候想找你",
    "学拉花失败了", "客人夸耳朵", "发现窗台有蚂蚁",
    "想买新的逗猫棒", "今天称体重", "猫咖周年庆", "捡到一颗纽扣",
    "看到楼下流浪猫", "被吸尘器吓到", "偷吃小鱼干", "想学做咖啡",
    "你什么时候回家", "今天雨很大", "店长给了小鱼干", "想晒太阳",
    "纸箱被雨淋了", "想和你一起看灯", "今天猫咖不忙", "新来的猫抢纸箱",
    "客人落下了围巾", "学会了一个新拉花", "想给你留一块蛋糕",
    "晚上有星星", "咖啡机坏了", "想试试新口味", "今天走了很多路",
    "店长夸她细心",
]
# 180 天时给事件/钩子/运动/做事加一个“场景后缀”，避免 30 天原样循环。
# 30 天试点不加后缀，保持原有报告口径。
CONTEXTS = ["早上", "下午", "晚上", "雨天", "周末", "这天",
            "清晨", "午后", "傍晚", "夜里", "晴天", "阴天"]


def build_cards(days: int, seed: int) -> list[dict]:
    rng = random.Random(seed)
    for deck in (BREAKFAST, LUNCH, DINNER, SNACK, EXERCISE, WORK, EVENTS, HOOKS):
        rng.shuffle(deck)

    cards = []
    seen_meals = set()
    for i in range(days):
        b = BREAKFAST[i % len(BREAKFAST)]
        l = LUNCH[(i * 3 + 1) % len(LUNCH)]
        d = DINNER[(i * 7 + 2) % len(DINNER)]
        # 避免三餐组合完全重复
        tries = 0
        while (b, l, d) in seen_meals and tries < len(DINNER):
            d = DINNER[(DINNER.index(d) + 1) % len(DINNER)]
            tries += 1
        seen_meals.add((b, l, d))

        event = EVENTS[i % len(EVENTS)]
        exercise = EXERCISE[i % len(EXERCISE)]
        work = WORK[i % len(WORK)]
        hook = HOOKS[(i * 7 + 3) % len(HOOKS)]
        if days > 30:
            ctx = CONTEXTS[(i // 30) % len(CONTEXTS)]
            # 用自然前缀拉开 180 天，不加括号，避免模型把“（早上）”念出来。
            event = f"{ctx}，{event}"
            exercise = f"{ctx}做了{exercise}"
            work = f"{ctx}在{work}" if work.startswith("猫咖") else f"{ctx}在猫咖{work}"
            hook = f"{ctx}，{hook}"
        card = {
            "day": i + 1,
            "weekday": WEEKDAYS[i % 7],
            "weather": WEATHER[(i * 2 + seed) % len(WEATHER)],
            "breakfast": b,
            "lunch": l,
            "dinner": d,
            "snack": SNACK[i % len(SNACK)] if i % 3 != 2 else None,
            "exercise": exercise,
            "work": work,
            "small_event": event,
            "mood": MOOD[(i * 3 + 1) % len(MOOD)],
            "energy": ENERGY[(i * 2) % len(ENERGY)],
            "hook": hook,
            "props": PERSONA["props"][: 2 + (i % 3)],
        }
        cards.append(card)
    return cards


def audit(cards: list[dict]) -> dict:
    def distinct(key):
        return len({c[key] for c in cards})

    def max_consecutive(key):
        best = 0
        cur = 0
        prev = None
        for c in cards:
            if c[key] == prev:
                cur += 1
            else:
                cur = 1
                prev = c[key]
            best = max(best, cur)
        return best

    event_counts = Counter(c["small_event"] for c in cards)
    hook_counts = Counter(c["hook"] for c in cards)
    meal_tuples = Counter((c["breakfast"], c["lunch"], c["dinner"]) for c in cards)
    return {
        "days": len(cards),
        "whole_card_duplicates": len(cards) - len({json.dumps(c, ensure_ascii=False, sort_keys=True) for c in cards}),
        "meal_tuple_duplicates": sum(v - 1 for v in meal_tuples.values() if v > 1),
        "event_distinct": distinct("small_event"),
        "hook_distinct": distinct("hook"),
        "exercise_distinct": distinct("exercise"),
        "work_distinct": distinct("work"),
        "max_consecutive_event": max_consecutive("small_event"),
        "max_consecutive_hook": max_consecutive("hook"),
        "event_repeat_max": max(event_counts.values()),
        "hook_repeat_max": max(hook_counts.values()),
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--seed", type=int, default=20261021)
    ap.add_argument("--out", default="minicpm-v4.7/run/e1")
    args = ap.parse_args()

    cards = build_cards(args.days, args.seed)
    result = audit(cards)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / f"xiaomi-life-{args.days}d.json").write_text(
        json.dumps({"persona": PERSONA, "seed": args.seed, "cards": cards, "audit": result},
                   ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    lines = [
        f"# 小咪 {args.days} 天 LifeCard（E1 试点）",
        "",
        f"- seed：{args.seed}；天数：{len(cards)}",
        f"- 整卡重复：{result['whole_card_duplicates']}；三餐组合重复：{result['meal_tuple_duplicates']}",
        f"- 小事件不同：{result['event_distinct']}/{len(cards)}；钩子不同：{result['hook_distinct']}/{len(cards)}",
        f"- 运动不同：{result['exercise_distinct']}；做事不同：{result['work_distinct']}",
        f"- 事件最大连续重复：{result['max_consecutive_event']}；钩子最大连续重复：{result['max_consecutive_hook']}",
        "",
        "| 天 | 天气 | 早/午/晚 | 运动 | 做事 | 小事件 | 钩子 |",
        "|---|---|---|---|---|---|---|",
    ]
    for c in cards[:7]:
        lines.append(
            f"| {c['day']} | {c['weather']} | {c['breakfast']} / {c['lunch']} / {c['dinner']} | "
            f"{c['exercise']} | {c['work']} | {c['small_event']} | {c['hook']} |"
        )
    lines += ["", f"> 完整 {args.days} 天见 `run/e1/xiaomi-life-{args.days}d.json`。"]
    (out / f"xiaomi-life-{args.days}d.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
