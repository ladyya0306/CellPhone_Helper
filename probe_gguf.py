"""Read GGUF metadata (header key/values) without loading tensors."""
import os, struct, sys

TYPE_SIZES = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}
T_STR, T_ARR = 8, 9

def rd(f, n):
    return f.read(n)

def u32(f):
    return struct.unpack("<I", f.read(4))[0]

def u64(f):
    return struct.unpack("<Q", f.read(8))[0]

def s(f):
    n = u64(f)
    return f.read(n).decode("utf-8", "replace")

def val(f, t):
    if t == T_STR:
        return s(f)
    if t == T_ARR:
        et = u32(f)
        n = u64(f)
        if n > 100000:
            return f"<array of {n} type {et}>"
        return [val(f, et) for _ in range(n)]
    if t == 0: return struct.unpack("<B", rd(f,1))[0]
    if t == 1: return struct.unpack("<b", rd(f,1))[0]
    if t == 2: return struct.unpack("<H", rd(f,2))[0]
    if t == 3: return struct.unpack("<h", rd(f,2))[0]
    if t == 4: return u32(f)
    if t == 5: return struct.unpack("<i", rd(f,4))[0]
    if t == 6: return struct.unpack("<f", rd(f,4))[0]
    if t == 7: return struct.unpack("<?", rd(f,1))[0]
    if t == 10: return u64(f)
    if t == 11: return struct.unpack("<q", rd(f,8))[0]
    if t == 12: return struct.unpack("<d", rd(f,8))[0]
    raise ValueError(f"unknown type {t}")

path = sys.argv[1]
size = os.path.getsize(path)
print(f"file: {os.path.basename(path)}  {size/2**30:.2f} GiB")
with open(path, "rb") as f:
    magic = rd(f, 4)
    ver = u32(f)
    n_tensors = u64(f)
    n_kv = u64(f)
    print(f"magic={magic!r} version={ver} tensors={n_tensors} kv={n_kv}")
    for i in range(n_kv):
        k = s(f)
        t = u32(f)
        v = val(f, t)
        if isinstance(v, list) and len(v) > 12:
            v = f"[{len(v)} items] {v[:12]}"
        if isinstance(v, str) and len(v) > 200:
            v = v[:200] + "..."
        print(f"  {k:34s} = {v}")
