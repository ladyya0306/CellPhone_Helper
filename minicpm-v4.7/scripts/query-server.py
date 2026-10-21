"""向本地 llama-server 发送一张图片 + 问题，打印回答与耗时。"""
import base64
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

SERVER = os.environ.get("MINICPM_SERVER", "http://127.0.0.1:8080")
image_path = Path(sys.argv[1])
question = sys.argv[2] if len(sys.argv) > 2 else "描述这张图片"

b64 = base64.b64encode(image_path.read_bytes()).decode()
payload = {
    "messages": [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": question},
                {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b64}"}},
            ],
        }
    ],
    "max_tokens": 160,
    "temperature": 0.7,
    "top_p": 0.95,
    "top_k": 100,
    "repeat_penalty": 1.03,
    "seed": 42,
}

# NO_THINK=1 时通过 chat_template_kwargs 关闭思考过程（模型模板支持 enable_thinking）
if os.environ.get("NO_THINK") == "1":
    payload["chat_template_kwargs"] = {"enable_thinking": False}

req = urllib.request.Request(
    f"{SERVER}/v1/chat/completions",
    data=json.dumps(payload).encode(),
    headers={"Content-Type": "application/json"},
)
t0 = time.time()
with urllib.request.urlopen(req, timeout=600) as resp:
    body = json.loads(resp.read().decode())
elapsed = time.time() - t0

print("=" * 60)
print("ANSWER:")
print(body["choices"][0]["message"]["content"].strip())
print("=" * 60)
usage = body.get("usage", {})
print(f"prompt_tokens={usage.get('prompt_tokens')} completion_tokens={usage.get('completion_tokens')}")
print(f"wall_clock={elapsed:.1f}s")
timings = body.get("timings", {})
for k in ("prompt_per_second", "predicted_per_second", "predicted_n", "prompt_n"):
    if k in timings:
        v = timings[k]
        print(f"{k}={v:.2f}" if isinstance(v, float) else f"{k}={v}")
