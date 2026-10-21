import json, urllib.request, urllib.parse

UA = {"User-Agent": "dsh-probe"}

def get(url, timeout=60, headers=None):
    h = dict(UA)
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(), dict(r.headers)

# 1) recent llama.cpp releases: are there real binary assets anywhere?
print("=" * 78)
print("recent llama.cpp releases (asset counts)")
print("=" * 78)
d, _ = get("https://api.github.com/repos/ggml-org/llama.cpp/releases?per_page=30")
rels = json.loads(d)
for r in rels:
    names = [a["name"] for a in r["assets"]]
    print(f"  {r['tag_name']:14s} {r.get('published_at','')[:10]}  assets={len(names)}  {names[:3]}")

# 2) is the Q4_K_M a single file or split? check content-length + range support
print()
print("=" * 78)
print("Q4_K_M download probe (Range: bytes=0-3)")
print("=" * 78)
url = "https://huggingface.co/openbmb/MiniCPM-V-4.7-35B-A3B-gguf/resolve/main/MiniCPM-V-4_7-35B-A3B-Q4_K_M.gguf"
try:
    body, hdrs = get(url, timeout=60, headers={"Range": "bytes=0-3"})
    print("  status ok, got", len(body), "bytes:", body.hex())
    print("  content-range:", hdrs.get("Content-Range"))
    print("  content-length:", hdrs.get("Content-Length"))
    print("  x-linked-size:", hdrs.get("x-linked-size"))
    print("  accept-ranges:", hdrs.get("Accept-Ranges"))
    print("  x-linked-etag:", hdrs.get("x-linked-etag"))
except Exception as e:
    print("  probe failed:", repr(e))

# 3) mmproj probe
print()
url2 = "https://huggingface.co/openbmb/MiniCPM-V-4.7-35B-A3B-gguf/resolve/main/mmproj-model-f16.gguf"
try:
    body, hdrs = get(url2, timeout=60, headers={"Range": "bytes=0-3"})
    print("  mmproj ok, x-linked-size:", hdrs.get("x-linked-size"), "| accept-ranges:", hdrs.get("Accept-Ranges"))
except Exception as e:
    print("  mmproj probe failed:", repr(e))
