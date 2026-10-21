"""Probe workspace drive space, admin/pagefile state, and the Vulkan device properties."""
import ctypes, os, shutil, subprocess, sys, json

WS = os.path.dirname(os.path.abspath(__file__))
print("workspace:", WS)
for drive in ("C:\\", "D:\\", "E:\\"):
    try:
        t, u, f = shutil.disk_usage(drive)
        print(f"  {drive} total={t/2**30:7.1f} GB free={f/2**30:7.1f} GB")
    except Exception as e:
        print(f"  {drive} {e!r}")

try:
    print("is_admin:", bool(ctypes.windll.shell32.IsUserAnAdmin()))
except Exception as e:
    print("is_admin check failed:", e)

# pagefile config
ps = os.path.join(WS, "llm", "tools", "llama-b11696-vulkan", "llama-server.exe")
print("\n--- llama-server --list-devices (full) ---")
try:
    out = subprocess.run([ps, "--list-devices"], capture_output=True, text=True, timeout=120)
    print(out.stdout.strip())
    if out.stderr.strip():
        print("stderr:", out.stderr.strip()[:2000])
except Exception as e:
    print("failed:", e)
