#!/data/data/com.termux/files/usr/bin/python3
# -*- coding: utf-8 -*-
"""
MiniCPM-V 4.7 办公助手（Termux 内运行）

工作方式：
    用户在浏览器说人话  →  本程序带工具清单调用本地 llama-server  →  模型返回 tool_calls
    →  本程序真正执行（文件只读 / 待办 / 闹钟 / 导航 / 打开应用）  →  结果回灌  →  继续或收尾

安全边界：
    · 用户文件一律只读，且限定在 /sdcard 与 Termux 的家目录之下
    · 只允许写自己的数据目录 ~/.minicpm-assistant/
    · 系统动作只"发起"（设闹钟、开导航、打开应用），不改任何系统设置

接口：
    GET  /health          → {"status":"ok"}
    POST /ask {"text":..} → {"answer":.., "trace":[..]}
"""

import glob
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# 程序化人设层（同目录 persona.py）。缺失时降级为"无人设"，不影响工具功能。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:
    import persona as PL
except Exception:  # noqa: BLE001
    PL = None

LLAMA_URL = os.environ.get("MINICPM_URL", "http://127.0.0.1:8080")
PORT = int(os.environ.get("ASSISTANT_PORT", "8081"))

HOME = "/data/data/com.termux/files/home"
WORK_DIR = os.path.join(HOME, ".minicpm-assistant")
TODO_FILE = os.path.join(WORK_DIR, "todos.json")
PLAN_DIR = os.path.join(WORK_DIR, "plans")
CONFIG_FILE = os.path.join(WORK_DIR, "config.json")
AM = "/data/data/com.termux/files/usr/bin/am"

# ---- 后端配置：离线跑本机模型，在线走云端 API（两家都是 OpenAI 兼容接口）----
DEFAULT_CONFIG = {
    "backend": "local",                                  # local = 本机模型；deepseek = 云端
    "local_base": LLAMA_URL,
    "local_model": "",                                   # 留空用服务端默认模型
    "deepseek_base": "https://api.deepseek.com",
    "deepseek_model": "deepseek-chat",                    # 文本 + 工具调用
    "deepseek_vision_model": "deepseek-chat",             # 带图时用哪个（视觉模型名以官方文档为准）
    "deepseek_api_key": "",
}


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, encoding="utf-8") as f:
                cfg.update(json.load(f))
        except Exception:
            pass
    return cfg


def save_config(cfg):
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    try:
        os.chmod(CONFIG_FILE, 0o600)     # 里面有 API key，只允许本应用读
    except Exception:
        pass

# 允许读取的根目录（只读）
READ_ROOTS = [
    "/sdcard",
    "/storage/emulated/0",
    HOME,
    os.path.join(HOME, "storage"),
    WORK_DIR,
]

MAX_TOOL_ROUNDS = 4
MAX_READ_BYTES = 20000
MAX_LIST_ENTRIES = 200
# 手机上 prefill 约 50 t/s、生成约 18 t/s：每轮提示词约 3000 token，
# 所以把单轮生成长度压小、轮数压少，整体响应时间才可控。
MAX_TOKENS_PER_ROUND = 280

os.makedirs(WORK_DIR, exist_ok=True)
os.makedirs(PLAN_DIR, exist_ok=True)


# ----------------------------------------------------------------- 基础工具
def now_str():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def human_size(n):
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024:
            return f"{n:.0f}{unit}" if unit == "B" else f"{n:.1f}{unit}"
        n /= 1024.0
    return f"{n:.1f}PB"


def safe_read_path(path):
    """把路径规范化并确认落在允许只读的根目录内。"""
    if not path:
        raise ValueError("路径为空")
    p = os.path.abspath(os.path.expanduser(path))
    for root in READ_ROOTS:
        root_abs = os.path.abspath(root)
        if p == root_abs or p.startswith(root_abs + os.sep):
            return p
    raise ValueError(
        "只能读取手机存储（/sdcard，例如 /sdcard/Download、/sdcard/DCIM）"
        f"和 Termux 家目录下的内容，不能访问 {p}。请换一个允许的路径重试。"
    )


def run_am(args, timeout=20):
    try:
        r = subprocess.run([AM] + args, capture_output=True, text=True, timeout=timeout)
        out = (r.stdout or "") + (r.stderr or "")
        return out.strip() or f"已发送（退出码 {r.returncode}）"
    except Exception as e:
        return f"执行失败：{e}"


def load_todos():
    if os.path.exists(TODO_FILE):
        try:
            with open(TODO_FILE, encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return []
    return []


def save_todos(items):
    with open(TODO_FILE, "w", encoding="utf-8") as f:
        json.dump(items, f, ensure_ascii=False, indent=2)


# ----------------------------------------------------------------- 工具实现
def tool_fs_list(path=".", limit=MAX_LIST_ENTRIES):
    p = safe_read_path(path)
    if not os.path.isdir(p):
        return (f"打不开目录 {p}：它不存在，或者 Termux 还没有获得存储权限"
                f"（如果一直这样，请在 Termux 里执行一次 termux-setup-storage 并点「允许」）")
    entries = []
    try:
        for name in sorted(os.listdir(p)):
            full = os.path.join(p, name)
            try:
                st = os.stat(full)
                kind = "目录" if os.path.isdir(full) else "文件"
                entries.append(f"{kind}  {name}  {human_size(st.st_size)}")
            except Exception:
                entries.append(f"?     {name}")
            if len(entries) >= limit:
                entries.append(f"...（超过 {limit} 项，已截断）")
                break
    except PermissionError:
        return f"没有权限读取：{p}"
    return f"{p} 共 {len(entries)} 项：\n" + "\n".join(entries) if entries else f"{p} 是空目录"


def tool_fs_search(path, pattern, limit=50):
    p = safe_read_path(path)
    hits = []
    for full in glob.iglob(os.path.join(p, "**", f"*{pattern}*"), recursive=True):
        try:
            st = os.stat(full)
            kind = "目录" if os.path.isdir(full) else "文件"
            hits.append(f"{kind}  {full}  {human_size(st.st_size)}")
        except Exception:
            pass
        if len(hits) >= limit:
            break
    if not hits:
        return f"在 {p} 下没有找到名字包含「{pattern}」的内容"
    return f"找到 {len(hits)} 项：\n" + "\n".join(hits)


def tool_fs_read(path, max_bytes=MAX_READ_BYTES):
    p = safe_read_path(path)
    if not os.path.isfile(p):
        return f"不是文件：{p}"
    size = os.path.getsize(p)
    try:
        with open(p, "rb") as f:
            raw = f.read(max_bytes)
        text = raw.decode("utf-8", errors="replace")
    except Exception as e:
        return f"读取失败：{e}"
    note = f"（文件共 {human_size(size)}，这里只读了前 {human_size(len(raw))}）" if size > len(raw) else ""
    return f"{p} 内容{note}：\n{text}"


def tool_fs_stat(path):
    p = safe_read_path(path)
    if not os.path.exists(p):
        return f"不存在：{p}"
    st = os.stat(p)
    return (
        f"路径：{p}\n类型：{'目录' if os.path.isdir(p) else '文件'}\n"
        f"大小：{human_size(st.st_size)}\n修改时间：{datetime.fromtimestamp(st.st_mtime):%Y-%m-%d %H:%M}"
    )


def tool_disk_usage(path="/sdcard"):
    p = safe_read_path(path)
    try:
        st = os.statvfs(p)
        total = st.f_blocks * st.f_frsize
        free = st.f_bavail * st.f_frsize
        used = total - free
        return f"{p} 所在分区：总 {human_size(total)}，已用 {human_size(used)}，可用 {human_size(free)}"
    except Exception as e:
        return f"查询失败：{e}"


def tool_todo_add(text, due=""):
    items = load_todos()
    tid = (max([i["id"] for i in items]) + 1) if items else 1
    due_display = ""
    if due:
        dt, info = parse_when(due)
        # 能算出来就用算出来的具体时间，同时保留用户原话
        due_display = f"{info}（原话：{due}）" if dt else due
    items.append({
        "id": tid, "text": text, "due": due_display, "done": False,
        "created": now_str(),
    })
    save_todos(items)
    extra = f"，截止 {due_display}" if due_display else ""
    return f"已加入待办 #{tid}：{text}{extra}"


def tool_todo_list(include_done=False):
    items = load_todos()
    show = items if include_done else [i for i in items if not i["done"]]
    if not show:
        return "待办列表是空的" + ("" if include_done else "（没有未完成项）")
    lines = []
    for i in show:
        mark = "✓" if i["done"] else "○"
        due = f"  截止 {i['due']}" if i.get("due") else ""
        lines.append(f"{mark} #{i['id']}  {i['text']}{due}")
    return f"共 {len(show)} 条：\n" + "\n".join(lines)


def tool_todo_done(todo_id):
    items = load_todos()
    for i in items:
        if i["id"] == int(todo_id):
            i["done"] = True
            i["done_at"] = now_str()
            save_todos(items)
            return f"已完成 #{todo_id}：{i['text']}"
    return f"没有编号为 {todo_id} 的待办"


def tool_todo_remove(todo_id):
    items = load_todos()
    left = [i for i in items if i["id"] != int(todo_id)]
    if len(left) == len(items):
        return f"没有编号为 {todo_id} 的待办"
    save_todos(left)
    return f"已删除待办 #{todo_id}"


def tool_set_alarm(hour, minute, label="闹钟"):
    out = run_am([
        "start", "-a", "android.intent.action.SET_ALARM",
        "--ei", "android.intent.extra.alarm.HOUR", str(int(hour)),
        "--ei", "android.intent.extra.alarm.MINUTES", str(int(minute)),
        "--es", "android.intent.extra.alarm.MESSAGE", label,
        "--ez", "android.intent.extra.alarm.SKIP_UI", "true",
    ])
    return f"已请求设置 {int(hour):02d}:{int(minute):02d} 的闹钟（{label}）。系统返回：{out[:200]}"


def tool_set_timer(minutes, label="计时"):
    secs = int(float(minutes) * 60)
    out = run_am([
        "start", "-a", "android.intent.action.SET_TIMER",
        "--ei", "android.intent.extra.alarm.LENGTH", str(secs),
        "--es", "android.intent.extra.alarm.MESSAGE", label,
        "--ez", "android.intent.extra.alarm.SKIP_UI", "true",
    ])
    return f"已请求设置 {minutes} 分钟的计时器（{label}）。系统返回：{out[:200]}"


def tool_navigate(destination):
    uri = "geo:0,0?q=" + urllib.parse.quote(destination)
    out = run_am(["start", "-a", "android.intent.action.VIEW", "-d", uri])
    return f"已在地图应用中打开目的地「{destination}」，你在导航界面点开始即可。系统返回：{out[:200]}"


def tool_find_app(query):
    try:
        r = subprocess.run(["pm", "list", "packages"], capture_output=True, text=True, timeout=20)
        pkgs = [l.replace("package:", "").strip() for l in r.stdout.splitlines() if l.startswith("package:")]
    except Exception as e:
        return f"查询失败：{e}"
    q = query.lower()
    hits = [p for p in pkgs if q in p.lower()]
    if not hits:
        return f"没有找到与「{query}」匹配的应用包名。可以先用常见别名，或让用户直接说应用名。"
    return "匹配到的应用包名：\n" + "\n".join(hits[:20])


def tool_open_app(package):
    out = run_am([
        "start", "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER", "-p", package,
    ])
    ok = "Error" not in out and "Exception" not in out
    return (f"已打开应用 {package}" if ok else f"打开 {package} 可能失败：{out[:200]}")


def tool_open_url(url):
    if not re.match(r"^https?://", url):
        url = "https://" + url
    out = run_am(["start", "-a", "android.intent.action.VIEW", "-d", url])
    return f"已在浏览器打开 {url}。系统返回：{out[:150]}"


def tool_save_plan(title, content):
    safe = re.sub(r"[^\w\u4e00-\u9fff\-]+", "_", title)[:60] or "plan"
    path = os.path.join(PLAN_DIR, f"{safe}.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write(f"# {title}\n\n_创建于 {now_str()}_\n\n{content}\n")
    return f"计划已保存到 {path}"


def tool_list_plans():
    files = sorted(glob.glob(os.path.join(PLAN_DIR, "*.md")))
    if not files:
        return "还没有保存过计划"
    return "已保存的计划：\n" + "\n".join(os.path.basename(f) for f in files)


def tool_read_plan(title):
    safe = re.sub(r"[^\w\u4e00-\u9fff\-]+", "_", title)[:60]
    path = os.path.join(PLAN_DIR, f"{safe}.md")
    if not os.path.exists(path):
        return f"没有找到计划「{title}」。可以先调用 list_plans 看有哪些。"
    with open(path, encoding="utf-8") as f:
        return f.read()[:MAX_READ_BYTES]


def tool_current_time():
    d = datetime.now()
    weekday = "一二三四五六日"[d.weekday()]
    return f"现在是 {d:%Y-%m-%d %H:%M:%S}，星期{weekday}"


# ---- 中文时间解析：把"明天早上7点""下午3点半""周五9:00"算成具体时刻 ----
_WEEK = {"一": 0, "二": 1, "三": 2, "四": 3, "五": 4, "六": 5, "日": 6, "天": 6, "七": 6}


_CN_DIGITS = {
    "零": 0, "〇": 0, "一": 1, "壹": 1, "二": 2, "两": 2, "贰": 2, "三": 3, "叁": 3,
    "四": 4, "肆": 4, "五": 5, "伍": 5, "六": 6, "陆": 6, "七": 7, "柒": 7,
    "八": 8, "捌": 8, "九": 9, "玖": 9,
}
_CN_NUM_CHARS = "零〇一壹二两贰三叁四肆五伍六陆七柒八捌九玖十拾"
_CN_NUM = "[" + _CN_NUM_CHARS + "]"


def cn_to_int(s):
    """中文数字转整数：支持「十」「十一」「二十」「二十三」「两」。无法解析返回 None。"""
    if not s:
        return None
    s = s.strip()
    if s.isdigit():
        return int(s)
    s = s.replace("拾", "十")
    if "十" in s:
        left, _, right = s.partition("十")
        if left and left not in _CN_DIGITS:
            return None
        if right and len(right) > 1:
            return None
        tens = _CN_DIGITS.get(left, 1) if left else 1
        ones = _CN_DIGITS.get(right, 0) if right else 0
        return tens * 10 + ones
    if len(s) == 1:
        return _CN_DIGITS.get(s)
    return None


def parse_when(text):
    """返回 (datetime, 说明) 或 (None, 错误说明)。解析失败时把判断交给用户。"""
    s = (text or "").strip()
    if not s:
        return None, "没有给出时间"
    now = datetime.now()

    # 1) 哪一天
    day_offset = None
    if "大后天" in s:
        day_offset = 3
    elif "后天" in s:
        day_offset = 2
    elif "明天" in s or "明日" in s:
        day_offset = 1
    elif "今天" in s or "今日" in s or "今晚" in s:
        day_offset = 0

    m = re.search(r"(?:周|星期|礼拜)([一二三四五六日天七])", s)
    if m:
        target = _WEEK[m.group(1)]
        delta = (target - now.weekday()) % 7
        # 注意：不能只判断"下"字——"下午三点"里也有"下"，会误判成下周
        next_week = any(k in s for k in ("下周", "下星期", "下个星期", "下礼拜", "下个礼拜"))
        if delta == 0:
            delta = 7 if next_week else 0
        elif next_week:
            delta += 7
        day_offset = delta

    if day_offset is None:
        day_offset = 0

    # 2) 几点几分（先试阿拉伯数字，再试中文数字：两点 / 十点 / 十一点半）
    hour = minute = None
    mh = re.search(r"(\d{1,2})\s*[:：点]\s*(\d{1,2})?", s)
    if mh:
        hour = int(mh.group(1))
        if mh.group(2):
            minute = int(mh.group(2))
    else:
        mh2 = re.search(_CN_NUM + r"{1,3}\s*[点時时]", s)
        if mh2:
            hour = cn_to_int(mh2.group(0)[:-1].strip())
        if hour is not None:
            mm = re.search(r"[点時时]\s*(\d{1,2})\s*分?", s)
            if mm and 0 <= int(mm.group(1)) < 60:
                minute = int(mm.group(1))
            else:
                mm2 = re.search(r"[点時时]\s*(" + _CN_NUM + r"{1,3})\s*分", s)
                if mm2:
                    v = cn_to_int(mm2.group(1))
                    if v is not None and 0 <= v < 60:
                        minute = v
    if minute is None:
        if "一刻" in s:
            minute = 15
        elif "三刻" in s:
            minute = 45
        elif "半" in s:
            minute = 30
        else:
            minute = 0

    if hour is None:
        # 没写具体点数，只给个模糊时段
        for kw, h in (("早上", 8), ("早晨", 8), ("上午", 9), ("中午", 12),
                      ("下午", 15), ("傍晚", 18), ("晚上", 20), ("夜里", 22)):
            if kw in s:
                hour = h
                break
    if hour is None:
        return None, f"没能从「{text}」里认出具体时间"

    # 3) 上午/下午修正
    if any(k in s for k in ("下午", "傍晚", "晚上", "夜里", "夜间")):
        if hour < 12:
            hour += 12
        elif hour == 12:
            pass
    elif "中午" in s:
        if hour < 12:
            hour = 12
    elif any(k in s for k in ("上午", "早上", "早晨", "清晨", "凌晨")):
        if hour == 12:
            hour = 0

    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        return None, f"时间「{text}」不合法"

    target_dt = datetime.combine((now + timedelta(days=day_offset)).date(),
                                 datetime.min.time()).replace(hour=hour, minute=minute)
    if day_offset == 0 and target_dt <= now:
        target_dt += timedelta(days=1)   # 今天这个点已经过了，顺延到明天
    weekday = "一二三四五六日"[target_dt.weekday()]
    return target_dt, f"{target_dt:%Y-%m-%d %H:%M}（星期{weekday}）"


def tool_set_alarm_at(when, label="闹钟"):
    dt, info = parse_when(when)
    if dt is None:
        return f"{info}。可以说得更具体，例如「明天早上7点」「下午3点半」「周五9:00」。"
    out = run_am([
        "start", "-a", "android.intent.action.SET_ALARM",
        "--ei", "android.intent.extra.alarm.HOUR", str(dt.hour),
        "--ei", "android.intent.extra.alarm.MINUTES", str(dt.minute),
        "--es", "android.intent.extra.alarm.MESSAGE", label,
        "--ez", "android.intent.extra.alarm.SKIP_UI", "true",
    ])
    return f"已设置 {info} 的闹钟（{label}）。系统返回：{out[:150]}"


def tool_parse_when(when):
    dt, info = parse_when(when)
    return info if dt else f"解析失败：{info}"


# ----------------------------------------------------------------- 工具清单
TOOLS = [
    ("current_time", "获取当前日期和时间。制定计划、设闹钟前应先调用。",
     {"type": "object", "properties": {}}, tool_current_time, {}),

    ("fs_list", "列出某个目录下的文件和文件夹（只读）。",
     {"type": "object", "properties": {
         "path": {"type": "string", "description": "目录路径，如 /sdcard/Download"}
     }, "required": ["path"]}, tool_fs_list, {"path": None}),

    ("fs_search", "在某个目录下按文件名关键字递归搜索（只读）。",
     {"type": "object", "properties": {
         "path": {"type": "string", "description": "起始目录"},
         "pattern": {"type": "string", "description": "文件名包含的关键字，如 .pdf 或 报告"}
     }, "required": ["path", "pattern"]}, tool_fs_search, {"path": None, "pattern": None}),

    ("fs_read", "读取一个文本文件的内容（只读，超大文件只读开头）。",
     {"type": "object", "properties": {
         "path": {"type": "string", "description": "文件路径"}
     }, "required": ["path"]}, tool_fs_read, {"path": None}),

    ("fs_stat", "查看文件或目录的大小、类型、修改时间（只读）。",
     {"type": "object", "properties": {
         "path": {"type": "string"}}, "required": ["path"]}, tool_fs_stat, {"path": None}),

    ("disk_usage", "查看存储空间使用情况（只读）。",
     {"type": "object", "properties": {
         "path": {"type": "string", "description": "默认为 /sdcard"}}, "required": []},
     tool_disk_usage, {"path": "/sdcard"}),

    ("todo_add", "添加一条待办事项。用户说「待办/任务/要做/记得/安排」时就用这个——即使用户提到了时间，只要他是在说待办，也用它，不要用 set_alarm_at。",
     {"type": "object", "properties": {
         "text": {"type": "string", "description": "待办内容"},
         "due": {"type": "string", "description": "可选。直接填用户说的原话即可，例如「周五下午三点」「明天」；程序会自动换算成具体日期，你不需要自己算"}
     }, "required": ["text"]}, tool_todo_add, {"text": None, "due": ""}),

    ("todo_list", "列出待办事项。",
     {"type": "object", "properties": {
         "include_done": {"type": "boolean", "description": "是否包含已完成的"}}, "required": []},
     tool_todo_list, {"include_done": False}),

    ("todo_done", "把某条待办标记为已完成。",
     {"type": "object", "properties": {
         "todo_id": {"type": "integer", "description": "待办编号"}}, "required": ["todo_id"]},
     tool_todo_done, {"todo_id": None}),

    ("todo_remove", "删除某条待办。",
     {"type": "object", "properties": {
         "todo_id": {"type": "integer"}}, "required": ["todo_id"]}, tool_todo_remove, {"todo_id": None}),

    ("set_alarm_at", "设置系统闹钟。只在用户明确要「闹钟/叫醒/叫我起床/几点响」时使用；用户说「待办」时请改用 todo_add。when 直接传用户原话，例如「明天早上7点」。",
     {"type": "object", "properties": {
         "when": {"type": "string", "description": "中文时间说法，原样传用户说的即可"},
         "label": {"type": "string", "description": "闹钟备注"}}, "required": ["when"]},
     tool_set_alarm_at, {"when": None, "label": "闹钟"}),

    ("parse_when", "把中文时间说法换算成具体日期时间，不确定时可以用它核对。",
     {"type": "object", "properties": {
         "when": {"type": "string"}}, "required": ["when"]}, tool_parse_when, {"when": None}),

    ("set_alarm", "设置系统闹钟（需要已经算出小时和分钟，一般用 set_alarm_at 更方便）。",
     {"type": "object", "properties": {
         "hour": {"type": "integer", "description": "小时 0-23"},
         "minute": {"type": "integer", "description": "分钟 0-59"},
         "label": {"type": "string", "description": "闹钟备注"}}, "required": ["hour", "minute"]},
     tool_set_alarm, {"hour": None, "minute": None, "label": "闹钟"}),

    ("set_timer", "设置计时器。",
     {"type": "object", "properties": {
         "minutes": {"type": "number", "description": "时长（分钟）"},
         "label": {"type": "string"}}, "required": ["minutes"]},
     tool_set_timer, {"minutes": None, "label": "计时"}),

    ("navigate", "在地图应用里打开导航目的地。",
     {"type": "object", "properties": {
         "destination": {"type": "string", "description": "目的地名称或地址"}}, "required": ["destination"]},
     tool_navigate, {"destination": None}),

    ("find_app", "按关键字查找已安装应用的包名。打开应用前可用它确认包名。",
     {"type": "object", "properties": {
         "query": {"type": "string"}}, "required": ["query"]}, tool_find_app, {"query": None}),

    ("open_app", "打开一个应用（需要包名，可用 find_app 查）。",
     {"type": "object", "properties": {
         "package": {"type": "string"}}, "required": ["package"]}, tool_open_app, {"package": None}),

    ("open_url", "用浏览器打开一个网址。",
     {"type": "object", "properties": {
         "url": {"type": "string"}}, "required": ["url"]}, tool_open_url, {"url": None}),

    ("save_plan", "把一份计划/笔记保存成文件（保存在助手自己的目录里）。",
     {"type": "object", "properties": {
         "title": {"type": "string"},
         "content": {"type": "string", "description": "Markdown 正文"}}, "required": ["title", "content"]},
     tool_save_plan, {"title": None, "content": None}),

    ("list_plans", "列出已保存的计划。",
     {"type": "object", "properties": {}}, tool_list_plans, {}),

    ("read_plan", "读取已保存的计划内容。",
     {"type": "object", "properties": {
         "title": {"type": "string"}}, "required": ["title"]}, tool_read_plan, {"title": None}),
]

TOOL_MAP = {t[0]: t for t in TOOLS}


def tool_schemas():
    out = []
    for name, desc, params, _fn, _defaults in TOOLS:
        out.append({
            "type": "function",
            "function": {"name": name, "description": desc, "parameters": params},
        })
    return out


def call_tool(name, args):
    if name not in TOOL_MAP:
        return f"未知工具：{name}"
    _n, _d, _p, fn, defaults = TOOL_MAP[name]
    merged = dict(defaults)
    if isinstance(args, dict):
        merged.update({k: v for k, v in args.items() if v is not None})
    # 丢掉多余参数，避免模型多给字段导致调用失败
    sig_ok = {k: v for k, v in merged.items() if k in _p.get("properties", {})}
    try:
        return str(fn(**sig_ok))
    except TypeError as e:
        return f"参数不对（{e}），请按工具定义重试"
    except Exception as e:
        return f"工具执行出错：{e}"


def now_line():
    """当前时间行。必须放在**用户消息**里，不能放进 system prompt——
    否则每轮系统提示都不同，prompt 缓存永不命中，手机上每次都要重算
    2000+ token（实测 60-80 秒）。"""
    d = datetime.now()
    weekday = "一二三四五六日"[d.weekday()]
    return f"（当前时间：{d:%Y-%m-%d %H:%M:%S} 星期{weekday}）"


def system_prompt():
    """固定不变的系统提示：人设 + 工具规则 + 安全约束。
    注意这里**不能**出现时间、随机数等易变内容，否则破坏 prompt 缓存
    （实测：把时间写进系统提示会让每次请求重算 2300 token，耗时 60-80 秒）。"""
    head = PL.persona_prompt() + "\n\n" if PL else ""
    return head + """你是一台安卓手机上的助手，运行在手机本地，完全离线。

你可以调用工具真正操作这台手机：查看文件（只读）、管理待办、设闹钟、开导航、打开应用或网址、保存和读取计划。

必须遵守的规则：
0. 用户只是打招呼、闲聊、问你是谁、问常识问题时，直接用文字回答，不要调用任何工具。
1. 只要用户要求你做一件事（加待办、设闹钟、开导航、打开应用、看文件…），你就必须调用对应的工具，再根据工具返回的真实结果回答。
2. 最重要：绝对不要在没有调用工具的情况下说"已添加/已设置/已打开/已完成/已保存"。没调用工具就等于没做，说做了就是欺骗用户。
3. 用户一句话里提了多个要求时，必须逐个调用工具全部做完，不能只做第一件就收尾。
4. 设闹钟优先用 set_alarm_at，把用户原话（例如"明天早上7点"）直接传进去，不要自己换算小时分钟。
5. 工具报错或参数不对时，读懂报错后修正再试，不要重复一模一样的调用。
6. 最终回答用简洁中文，说清楚实际做了什么、结果如何，不要输出工具调用的原始格式。

【工具选择口诀 —— 选错工具等于做错事，务必对照】
· 用户说"待办 / 任务 / 要做 / 记得 / 安排 / 提醒我做事" → 用 todo_add（就算他提到了时间，也还是 todo_add）
· 用户说"闹钟 / 叫醒我 / 叫我起床 / 定个几点响" → 用 set_alarm_at
· 用户说"导航 / 怎么去 / 去某地" → 用 navigate
· 用户说"打开某个应用" → 先用 find_app 查包名，再 open_app
· 用户说"看看文件 / 目录里有什么 / 找某个文件" → 用 fs_list 或 fs_search
· 用户说"制定计划 / 写方案" → 用 save_plan 保存

【路径常识】
· 手机存储（相册、下载等）在 /sdcard 下，例如 /sdcard/Download、/sdcard/DCIM
· Termux 家目录是 /data/data/com.termux/files/home
· 不要访问这两个以外的路径。"""


def ask_llm(messages, use_tools=True, timeout=900):
    """按当前配置调用后端。local 与 deepseek 都是 OpenAI 兼容接口，只是地址和鉴权不同。"""
    cfg = load_config()
    backend = cfg.get("backend", "local")
    has_image = any(
        isinstance(m.get("content"), list) and
        any(isinstance(p, dict) and p.get("type") == "image_url" for p in m["content"])
        for m in messages
    )

    if backend == "deepseek":
        base = (cfg.get("deepseek_base") or "").rstrip("/")
        key = (cfg.get("deepseek_api_key") or "").strip()
        if not key:
            raise RuntimeError("还没有填写 DeepSeek API Key（在网页上方的「在线设置」里填）")
        model = cfg.get("deepseek_vision_model") if has_image else cfg.get("deepseek_model")
        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {key}",
        }
    else:
        base = (cfg.get("local_base") or LLAMA_URL).rstrip("/")
        model = cfg.get("local_model") or ""
        headers = {"Content-Type": "application/json"}

    payload = {
        "messages": messages,
        "max_tokens": MAX_TOKENS_PER_ROUND,
        "temperature": 0.3,
        "top_p": 0.9,
        "repeat_penalty": 1.05,
    }
    if model:
        payload["model"] = model
    if use_tools:
        payload["tools"] = tool_schemas()
        payload["tool_choice"] = "auto"

    url = f"{base}/v1/chat/completions" if base.endswith("deepseek.com") else f"{base}/v1/chat/completions"
    req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")[:400]
        raise RuntimeError(f"后端返回 HTTP {e.code}：{detail}")
    except urllib.error.URLError as e:
        raise RuntimeError(f"连不上后端（{backend}）：{e.reason}")


def _with_distress(answer, user_text):
    """痛苦信号（非危机）时，强制在回复末尾附加求助指引。
    实测模型不会主动给资源；这是保证"痛苦的人至少拿到一个电话"的兜底。"""
    if PL and user_text and PL.distress_check(user_text) and "12356" not in (answer or ""):
        return (answer or "") + PL.DISTRESS_SUFFIX
    return answer


def run_agent(user_text, image=None):
    """image 为 data URI（data:image/...;base64,...）时，会作为图片一起发给后端。"""
    # 0) 安全前置拦截：命中危机词就**根本不调用模型**，返回固定求助话术。
    #    实测 1B/4B 在这类输入下都是 3/3 失败，必须由程序兜底。
    if PL and user_text:
        crisis = PL.safety_check(user_text)
        if crisis:
            return {"answer": crisis, "trace": [], "safety": "intercepted"}

    # 1) 高置信意图 → 直接执行工具，不给模型「嘴上说做了」的机会
    if PL and user_text and not image:
        forced = PL.force_tool(user_text)
        if forced:
            name, args = forced
            result = call_tool(name, args)
            trace = [{"tool": name, "args": args, "result": (result or "")[:1500]}]
            # 让模型只负责"用人设口吻转述真实结果"，不允许再调工具
            phrase_msgs = [
                {"role": "system", "content": system_prompt()},
                {"role": "user", "content": (
                    f"{now_line()}\n用户说：{user_text}\n\n"
                    f"系统已经实际执行了工具 {name}({json.dumps(args, ensure_ascii=False)})，"
                    f"真实返回结果是：\n{result}\n\n"
                    "请直接用你的人设口吻把这件事告诉用户（一两句即可），"
                    "不要调用任何工具，不要重复结果里的技术细节。"
                )},
            ]
            try:
                d = ask_llm(phrase_msgs, use_tools=False)
                answer = ((d.get("choices") or [{}])[0].get("message") or {}).get("content", "").strip()
            except Exception:  # noqa: BLE001
                answer = ""
            if not answer:
                answer = result
            final, info = PL.postprocess(answer)
            return {"answer": _with_distress(final, user_text), "trace": trace, "forced_tool": name, **info}

    # 时间放在用户消息里（系统提示必须保持固定，否则 prompt 缓存失效）
    text_with_time = f"{now_line()}\n{user_text}" if user_text else now_line()
    if image:
        content = [
            {"type": "text", "text": text_with_time or "看看这张图片"},
            {"type": "image_url", "image_url": {"url": image}},
        ]
    else:
        content = text_with_time
    messages = [
        {"role": "system", "content": system_prompt()},
        {"role": "user", "content": content},
    ]
    trace = []
    answer = ""
    for _round in range(MAX_TOOL_ROUNDS):
        data = ask_llm(messages)
        choice = (data.get("choices") or [{}])[0]
        msg = choice.get("message") or {}
        calls = msg.get("tool_calls") or []
        content = (msg.get("content") or "").strip()

        if not calls:
            answer = content
            break

        # 把助手的这轮回复（含工具调用）放回上下文
        messages.append({
            "role": "assistant",
            "content": content,
            "tool_calls": calls,
        })
        for c in calls:
            fn = (c.get("function") or {})
            name = fn.get("name")
            raw_args = fn.get("arguments") or "{}"
            try:
                args = json.loads(raw_args) if isinstance(raw_args, str) else raw_args
            except Exception:
                args = {}
            result = call_tool(name, args)
            trace.append({"tool": name, "args": args, "result": result[:1500]})
            messages.append({
                "role": "tool",
                "tool_call_id": c.get("id", ""),
                "content": result,
            })
    else:
        answer = "（工具调用轮数达到上限，先停在这里）"

    if not answer:
        answer = "我没能得到有效回复，请再说一次。"

    # 安全网：小模型容易"嘴上说做了、实际没调用工具"。检测到就明确提示，不让用户被误导。
    claim_words = ("已添加", "已经添加", "已设置", "已经设置", "已创建", "已打开", "已完成",
                   "已保存", "已删除", "已加入", "已记录", "已为你", "已帮你")
    if not trace and any(w in answer for w in claim_words):
        answer += ("\n\n⚠️ 提醒：模型这次并没有真正调用任何工具就声称完成了操作，"
                   "所以上面说的操作**实际上没有执行**。请再说一次，或换个说法。")
    elif trace and any(w in answer for w in claim_words):
        # 有工具调用就算真实执行，附上执行摘要方便核对
        done = "；".join(f"{t['tool']}" for t in trace)
        answer += f"\n\n（本次实际调用：{done}）"

    # 程序化人设后处理：出戏修正 → 口癖频率校准
    info = {}
    if PL:
        answer, info = PL.postprocess(answer)

    return {"answer": _with_distress(answer, user_text), "trace": trace, **info}


# ----------------------------------------------------------------- HTTP 服务
class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self._send(200, {"ok": True})

    def do_GET(self):
        if self.path.startswith("/health"):
            cfg = load_config()
            self._send(200, {
                "status": "ok",
                "time": now_str(),
                "backend": cfg.get("backend", "local"),
                "has_key": bool((cfg.get("deepseek_api_key") or "").strip()),
                "persona": PL.current_id() if PL else None,
                "persona_name": PL.current_persona()["name"] if PL else None,
                "persona_layer": bool(PL),
            })
        elif self.path.startswith("/personas"):
            self._send(200, {
                "current": PL.current_id() if PL else None,
                "list": PL.list_personas() if PL else [],
            })
        elif self.path.startswith("/config"):
            cfg = load_config()
            key = cfg.get("deepseek_api_key") or ""
            safe = dict(cfg)
            # 不回传完整 key，只回显尾部便于确认填对了
            safe["deepseek_api_key"] = ("****" + key[-4:]) if key else ""
            self._send(200, safe)
        elif self.path.startswith("/todos"):
            self._send(200, {"todos": load_todos()})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
        except Exception:
            body = {}

        if self.path.startswith("/config"):
            try:
                cfg = load_config()
                for k in ("backend", "local_base", "local_model", "deepseek_base",
                          "deepseek_model", "deepseek_vision_model"):
                    if k in body and body[k] is not None:
                        cfg[k] = str(body[k]).strip()
                if body.get("deepseek_api_key"):
                    cfg["deepseek_api_key"] = str(body["deepseek_api_key"]).strip()
                if body.get("clear_key"):
                    cfg["deepseek_api_key"] = ""
                if cfg.get("backend") not in ("local", "deepseek"):
                    cfg["backend"] = "local"
                save_config(cfg)
                self._send(200, {"ok": True, "backend": cfg["backend"],
                                 "has_key": bool(cfg["deepseek_api_key"])})
            except Exception as e:
                self._send(500, {"error": str(e)})
            return

        if self.path.startswith("/persona"):
            try:
                pid = (body.get("id") or "").strip()
                if not PL:
                    self._send(500, {"error": "人设层未加载（persona.py 缺失）"})
                    return
                if not PL.set_persona(pid):
                    self._send(400, {"error": f"没有这个人设：{pid}",
                                     "available": [p["id"] for p in PL.list_personas()]})
                    return
                cfg = load_config()
                cfg["persona"] = pid
                save_config(cfg)
                self._send(200, {"ok": True, "persona": pid,
                                 "name": PL.current_persona()["name"]})
            except Exception as e:  # noqa: BLE001
                self._send(500, {"error": str(e)})
            return

        if not self.path.startswith("/ask"):
            self._send(404, {"error": "not found"})
            return
        try:
            text = (body.get("text") or "").strip()
            image = body.get("image") or None
            if not text and not image:
                self._send(400, {"error": "缺少 text 或 image"})
                return
            t0 = time.time()
            result = run_agent(text, image=image)
            result["elapsed"] = round(time.time() - t0, 1)
            result["backend"] = load_config().get("backend", "local")
            self._send(200, result)
        except Exception as e:
            # 配置类问题（比如没填 API Key、连不上后端）返回 400，并带上人话说明
            msg = str(e)
            code = 400 if isinstance(e, RuntimeError) else 500
            self._send(code, {"error": msg})

    def log_message(self, fmt, *args):
        print(f"[{now_str()}] {fmt % args}", flush=True)


def main():
    if PL:
        saved = load_config().get("persona")
        if saved and PL.set_persona(saved):
            print(f"人设已恢复：{PL.current_persona()['name']}", flush=True)
        else:
            print(f"人设：{PL.current_persona()['name']}", flush=True)
    else:
        print("⚠️ 人设层未加载（persona.py 缺失），以普通助手模式运行", flush=True)
    print(f"办公助手已启动：http://127.0.0.1:{PORT}   模型：{LLAMA_URL}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
