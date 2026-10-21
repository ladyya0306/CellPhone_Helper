"""Benchmark MiniCPM-V 4.7 35B-A3B on the Radeon 780M.

Usage: python bench_model.py <model-file-name> [--runs N]
Writes incremental JSON + CSV to llm/results/.
"""
import json, os, re, subprocess, sys, time

WS = os.path.dirname(os.path.abspath(__file__))
BIN = os.path.join(WS, "llm", "tools", "llama-b11696-vulkan")
MODELS = os.path.join(WS, "llm", "models")
OUT = os.path.join(WS, "llm", "results")
os.makedirs(OUT, exist_ok=True)

model_name = sys.argv[1]
runs = 2
if "--runs" in sys.argv:
    runs = int(sys.argv[sys.argv.index("--runs") + 1])
stem = os.path.splitext(model_name)[0]
model_path = os.path.join(MODELS, model_name)
assert os.path.exists(model_path), model_path

COMMON = [
    "-m", model_path,
    "-ngl", "999",
    "-fa", "on",
    "-ctk", "q8_0", "-ctv", "q8_0",
    "-b", "1024", "-ub", "512",
    "-r", str(runs),
    "-o", "json",
]

CONFIGS = [
    ("text_only", []),
    ("moe_cache_2g", ["--moe-cache-mib", "2048"]),
    ("moe_cache_4g", ["--moe-cache-mib", "4096"]),
]

results = []
for label, extra in CONFIGS:
    for test in (["-p", "512", "-n", "0"], ["-p", "0", "-n", "128"], ["-p", "2048", "-n", "128"]):
        cmd = [os.path.join(BIN, "llama-bench.exe")] + COMMON + extra + test
        print("RUN:", label, " ".join(extra), " ".join(test), flush=True)
        t0 = time.time()
        try:
            p = subprocess.run(cmd, cwd=BIN, capture_output=True, text=True, timeout=5400)
            dt = time.time() - t0
            raw = p.stdout.strip()
            item = {"config": label, "extra": extra, "test": test, "seconds": round(dt, 1),
                    "returncode": p.returncode, "stdout": raw,
                    "stderr": p.stderr.strip()[-4000:]}
            try:
                item["parsed"] = json.loads(raw)
            except Exception:
                item["parsed"] = None
            results.append(item)
            if item["parsed"]:
                for r in item["parsed"]:
                    print(f"   -> {r.get('n_prompt')}p {r.get('n_gen')}g : "
                          f"{r.get('avg_ts')} t/s  (test={r.get('test')})", flush=True)
            else:
                print("   -> no json; stderr:", item["stderr"][-600:], flush=True)
        except subprocess.TimeoutExpired:
            print("   -> TIMEOUT after", round(time.time() - t0), "s", flush=True)
            results.append({"config": label, "extra": extra, "test": test, "timeout": True})
        with open(os.path.join(OUT, stem + ".json"), "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False, indent=2)
log = os.path.join(OUT, stem + ".json")
print("wrote", log)
