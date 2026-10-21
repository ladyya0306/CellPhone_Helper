"""Download the llama.cpp Windows Vulkan build and extract it into the workspace.

Usage: python fetch_llamacpp.py <tag>
"""
import os, ssl, sys, urllib.request, zipfile, shutil, time

TAG = sys.argv[1] if len(sys.argv) > 1 else "b11696"
WS = os.path.dirname(os.path.abspath(__file__))
DL = os.path.join(WS, "llm", "_dl")
TOOLS = os.path.join(WS, "llm", "tools")
os.makedirs(DL, exist_ok=True)
os.makedirs(TOOLS, exist_ok=True)

name = f"llama-{TAG}-bin-win-vulkan-x64.zip"
url = f"https://github.com/ggml-org/llama.cpp/releases/download/{TAG}/{name}"
dst = os.path.join(DL, name)

print(f"URL : {url}")
print(f"DST : {dst}")

if not (os.path.exists(dst) and os.path.getsize(dst) > 1_000_000):
    ctx = ssl.create_default_context()
    req = urllib.request.Request(url, headers={"User-Agent": "dsh-fetch"})
    t0 = time.time()
    got = 0
    with urllib.request.urlopen(req, timeout=120, context=ctx) as r, open(dst, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            got += len(chunk)
            if total and got % (16 << 20) < (1 << 20):
                print(f"  {got/1048576:7.1f} / {total/1048576:.1f} MB  "
                      f"{got/1048576/max(time.time()-t0,0.01):6.2f} MB/s", flush=True)
    print(f"downloaded {got/1048576:.1f} MB in {time.time()-t0:.1f}s")
else:
    print("already present, size:", os.path.getsize(dst))

outdir = os.path.join(TOOLS, f"llama-{TAG}-vulkan")
with zipfile.ZipFile(dst) as z:
    names = z.namelist()
    z.extractall(outdir)
print("extracted", len(names), "entries ->", outdir)

# show key executables
for root, dirs, files in os.walk(outdir):
    for fn in sorted(files):
        if fn.lower().endswith(".exe"):
            print("  ", os.path.relpath(os.path.join(root, fn), outdir))
