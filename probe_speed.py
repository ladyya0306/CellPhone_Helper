"""A/B throughput probe: direct vs 127.0.0.1:7897 proxy, single 64 MB range read each."""
import ssl, time, urllib.request

URL = "https://huggingface.co/openbmb/MiniCPM-V-4.7-35B-A3B-gguf/resolve/main/MiniCPM-V-4_7-35B-A3B-Q4_K_M.gguf"
CHUNK = 64 << 20
ctx = ssl.create_default_context()

def run(label, proxy):
    handler = urllib.request.ProxyHandler({} if proxy is None else {"http": proxy, "https": proxy})
    op = urllib.request.build_opener(handler)
    req = urllib.request.Request(URL, headers={"User-Agent": "dsh", "Range": f"bytes=0-{CHUNK-1}"})
    t0 = time.time()
    got = 0
    try:
        with op.open(req, timeout=60) as r:
            status = r.status
            while True:
                b = r.read(1 << 20)
                if not b:
                    break
                got += len(b)
                if got >= CHUNK:
                    break
        dt = time.time() - t0
        print(f"{label:26s} status={status} {got/1048576:6.1f} MB in {dt:5.2f}s = {got/1048576/dt:6.2f} MB/s")
    except Exception as e:
        print(f"{label:26s} FAILED {e!r}")

run("direct", None)
run("proxy 127.0.0.1:7897", "http://127.0.0.1:7897")
run("direct (2nd)", None)
