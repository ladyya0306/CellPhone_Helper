"""Concurrency probe: do 3 parallel streams aggregate faster than 1?"""
import ssl, time, threading, urllib.request

URL = "https://huggingface.co/openbmb/MiniCPM-V-4.7-35B-A3B-gguf/resolve/main/MiniCPM-V-4_7-35B-A3B-Q4_K_M.gguf"
CHUNK = 48 << 20
ctx = ssl.create_default_context()
res = {}

def worker(tag, start, proxy):
    handler = urllib.request.ProxyHandler({} if proxy is None else {"http": proxy, "https": proxy})
    op = urllib.request.build_opener(handler)
    req = urllib.request.Request(URL, headers={
        "User-Agent": "dsh", "Range": f"bytes={start}-{start+CHUNK-1}"})
    t0 = time.time()
    got = 0
    try:
        with op.open(req, timeout=60) as r:
            while got < CHUNK:
                b = r.read(1 << 20)
                if not b:
                    break
                got += len(b)
        dt = time.time() - t0
        res[tag] = (got, dt)
        print(f"  {tag:22s} {got/1048576:6.1f} MB in {dt:5.2f}s = {got/1048576/dt:6.2f} MB/s", flush=True)
    except Exception as e:
        res[tag] = (got, time.time() - t0)
        print(f"  {tag:22s} FAILED after {got/1048576:.1f} MB: {e!r}", flush=True)

print("=== 3 parallel via proxy ===")
t0 = time.time()
ths = [threading.Thread(target=worker, args=(f"proxy#{i}", i * CHUNK, "http://127.0.0.1:7897")) for i in range(3)]
for t in ths: t.start()
for t in ths: t.join()
tot = sum(v[0] for v in res.values())
print(f"  AGGREGATE proxy: {tot/1048576:.1f} MB in {time.time()-t0:.2f}s = {tot/1048576/(time.time()-t0):.2f} MB/s")

res.clear()
print("=== 3 parallel direct ===")
t0 = time.time()
ths = [threading.Thread(target=worker, args=(f"direct#{i}", 10 * CHUNK + i * CHUNK, None)) for i in range(3)]
for t in ths: t.start()
for t in ths: t.join()
tot = sum(v[0] for v in res.values())
print(f"  AGGREGATE direct: {tot/1048576:.1f} MB in {time.time()-t0:.2f}s = {tot/1048576/(time.time()-t0):.2f} MB/s")
