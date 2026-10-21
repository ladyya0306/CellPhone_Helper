import json, re, urllib.request, urllib.error, urllib.parse

UA = {"User-Agent": "dsh-probe"}

def get(url, timeout=45):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()

print("=" * 70)
print("llama.cpp v0.7.0 windows vulkan assets")
print("=" * 70)
rel = json.loads(get("https://api.github.com/repos/ggml-org/llama.cpp/releases/latest"))
print("tag:", rel["tag_name"], "| published:", rel.get("published_at"))
for a in rel["assets"]:
    n = a["name"]
    if re.search(r"win.*(vulkan|avx2|x64)", n, re.I):
        print(f"  {n:60s} {a['size']/1048576:9.1f} MB")

print()
print("=" * 70)
print("huggingface search: MiniCPM-V 4.7 GGUF")
print("=" * 70)
for q in ["MiniCPM-V-4.7", "MiniCPM-V-4_7", "minicpm-v4.7 gguf"]:
    try:
        d = json.loads(get(
            "https://huggingface.co/api/models?search=" + urllib.parse.quote(q) + "&limit=40&full=false"))
        print(f"--- query {q!r}: {len(d)} hits")
        for m in d:
            print(f"  {m['modelId']:70s} dl={m.get('downloads')}")
    except Exception as e:
        print(f"--- query {q!r} FAILED: {e!r}")
