import json, re, urllib.request, urllib.parse

UA = {"User-Agent": "dsh-probe"}

def get(url, timeout=60):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()

print("=" * 78)
print("ALL llama.cpp v0.7.0 release assets")
print("=" * 78)
rel = json.loads(get("https://api.github.com/repos/ggml-org/llama.cpp/releases/latest"))
print("tag:", rel["tag_name"], "| assets:", len(rel["assets"]))
for a in rel["assets"]:
    print(f"  {a['name']:70s} {a['size']/1048576:8.1f} MB")

for repo in ["openbmb/MiniCPM-V-4.7-35B-A3B-gguf"]:
    print()
    print("=" * 78)
    print("FILES:", repo)
    print("=" * 78)
    d = json.loads(get("https://huggingface.co/api/models/" + repo + "?blobs=true"))
    sibs = d.get("siblings", [])
    rows = []
    for s in sibs:
        rows.append((s.get("rfilename"), s.get("size") or 0))
    rows.sort()
    tot = 0
    for n, sz in rows:
        tot += sz
        print(f"  {n:70s} {sz/1048576:9.1f} MB")
    print(f"  {'TOTAL':70s} {tot/1073741824:9.2f} GB")
