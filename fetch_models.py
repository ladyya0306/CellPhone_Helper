"""Resumable parallel downloader for the MiniCPM-V 4.7 GGUF files.

Usage: python fetch_models.py <tag> [--only name]
Progress is appended to llm/_dl/download.log and each file can be resumed.
"""
import os, ssl, sys, time, threading, urllib.request

WS = os.path.dirname(os.path.abspath(__file__))
DL = os.path.join(WS, "llm", "models")
os.makedirs(DL, exist_ok=True)
LOG = os.path.join(WS, "llm", "_dl", "download.log")
os.makedirs(os.path.dirname(LOG), exist_ok=True)

REPO = "https://huggingface.co/openbmb/MiniCPM-V-4.7-35B-A3B-gguf/resolve/main"

FILES = {
    "q4km":   f"{REPO}/MiniCPM-V-4_7-35B-A3B-Q4_K_M.gguf",
    "iq4xs":  f"{REPO}/MiniCPM-V-4_7-35B-A3B-IQ4_XS.gguf",
    "iq3m":   f"{REPO}/MiniCPM-V-4_7-35B-A3B-IQ3_M.gguf",
    "mmproj": f"{REPO}/mmproj-model-f16.gguf",
}

_only = None
if "--only" in sys.argv:
    _only = sys.argv[sys.argv.index("--only") + 1]

_loglock = threading.Lock()

def log(msg):
    line = f"[{time.strftime('%H:%M:%S')}] {msg}"
    with _loglock:
        print(line, flush=True)
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(line + "\n")

ctx = ssl.create_default_context()

# Treat direct connection as the default: the local proxy is for the interactive
# user session, and direct HTTPS already works for this Python runtime.
NO_PROXY = "--use-proxy" not in sys.argv
if NO_PROXY:
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    urllib.request.install_opener(opener)

def remote_size(url):
    req = urllib.request.Request(url, headers={"User-Agent": "dsh-fetch"}, method="HEAD")
    with urllib.request.urlopen(req, timeout=60, context=ctx) as r:
        return int(r.headers.get("Content-Length") or 0)

def fsize(path):
    try:
        return os.path.getsize(path)
    except OSError:
        return 0

def fetch(key, url):
    fn = url.rsplit("/", 1)[-1]
    dst = os.path.join(DL, fn)
    try:
        size = remote_size(url)
    except Exception as e:
        log(f"{key}: HEAD failed {e!r}")
        size = 0
    have = fsize(dst)
    if size and have >= size:
        log(f"{key}: already complete ({have/1073741824:.2f} GB)")
        return
    log(f"{key}: start have={have/1048576:.1f}MB remote={size/1048576:.1f}MB -> {fn}")
    t0 = time.time()
    base = have
    attempts = 0
    while True:
        if size and fsize(dst) >= size:
            log(f"{key}: DONE {fsize(dst)/1073741824:.2f} GB in {(time.time()-t0)/60:.1f} min")
            return
        attempts += 1
        if attempts > 40:
            log(f"{key}: giving up after {attempts} attempts")
            return
        have = fsize(dst)
        headers = {"User-Agent": "dsh-fetch"}
        mode = "wb"
        if have > 0:
            headers["Range"] = f"bytes={have}-"
            mode = "ab"
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=60, context=ctx) as r:
                if have > 0 and r.status != 206:
                    log(f"{key}: server ignored Range (status {r.status}); restarting file")
                    mode = "wb"
                    have = 0
                    base = 0
                last = time.time()
                with open(dst, mode) as f:
                    while True:
                        chunk = r.read(1 << 20)
                        if not chunk:
                            break
                        f.write(chunk)
                        if time.time() - last > 20:
                            done = fsize(dst)
                            sp = (done - base) / (time.time() - t0)
                            eta = (size - done) / sp if (size and sp > 0) else 0
                            log(f"{key}: {done/1073741824:5.2f}/{size/1073741824:.2f} GB "
                                f"{sp/1048576:5.2f} MB/s eta {eta/60:5.1f} min")
                            last = time.time()
        except Exception as e:
            log(f"{key}: error {e!r}; retry in 5s (attempt {attempts})")
            time.sleep(5)

jobs = [k for k in FILES if (not _only or k == _only)]
threads = [threading.Thread(target=fetch, args=(k, FILES[k]), daemon=False) for k in jobs]
for t in threads:
    t.start()
for t in threads:
    t.join()
log("all requested downloads finished")
