"""向手机上的办公助手发一条指令，打印工具调用轨迹与回答。

用法（电脑上，需先 adb forward tcp:9091 tcp:8081）：
    python ask-assistant.py "帮我加一条待办：周五下午三点开组会"
"""
import json
import sys
import urllib.error
import urllib.request

URL = "http://127.0.0.1:9091/ask"


def ask(text, timeout=900):
    req = urllib.request.Request(
        URL,
        data=json.dumps({"text": text}).encode(),
        headers={"Content-Type": "application/json"},
    )
    try:
        r = json.loads(urllib.request.urlopen(req, timeout=timeout).read().decode())
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        try:
            detail = json.loads(detail).get("error", detail)
        except Exception:
            pass
        print("问：" + text)
        print(f"   助手返回 {e.code}：{detail}")
        print("-" * 64)
        return
    print("问：" + text)
    trace = r.get("trace", [])
    if not trace:
        print("   [没有调用任何工具]")
    for t in trace:
        args = json.dumps(t["args"], ensure_ascii=False)
        print(f"   [工具] {t['tool']}({args})")
        for line in (t["result"] or "").strip().splitlines()[:3]:
            print(f"          {line[:120]}")
    print("答：" + (r.get("answer") or "").strip()[:600])
    print(f"   （耗时 {r.get('elapsed')} 秒，调用工具 {len(trace)} 次）")
    print("-" * 64)


if __name__ == "__main__":
    for q in sys.argv[1:]:
        try:
            ask(q)
        except Exception as e:
            print("问：" + q)
            print("   出错：" + str(e))
            print("-" * 64)
