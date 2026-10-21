import json, urllib.request

UA = {"User-Agent": "dsh-probe"}

def get(url, timeout=60):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()

d = json.loads(get("https://api.github.com/repos/ggml-org/llama.cpp/releases?per_page=5"))
for rel in d[:3]:
    print("=" * 78)
    print(rel["tag_name"], rel.get("published_at"))
    print("=" * 78)
    for a in rel["assets"]:
        print(f"  {a['name']:75s} {a['size']/1048576:8.1f} MB")
