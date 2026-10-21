#!/data/data/com.termux/files/usr/bin/bash
# vivo X100 Pro 上部署 MiniCPM-V 4.7 1B 的辅助脚本（Termux 内运行）
#
# 第一次用：先在 Termux 里执行
#     cp ~/storage/downloads/minicpm/termux-setup.sh ~/
#     bash ~/termux-setup.sh setup     # 搬模型 + 校验
#     bash ~/termux-setup.sh build     # 编译 llama.cpp（15~40 分钟）
#     bash ~/termux-setup.sh serve     # 起服务，浏览器开 http://127.0.0.1:8080
#
# 其它：
#     bash ~/termux-setup.sh ask 图片路径 "问题"    # 命令行单次问答
#     bash ~/termux-setup.sh check                  # 只检查文件和二进制
set -euo pipefail

MODEL_DIR="$HOME/models/minicpm-v4.7"
LLAMA_DIR="$HOME/llama.cpp"
BIN="$LLAMA_DIR/build/bin"

GGUF="MiniCPM-V-4_7-1B-Q4_K_M.gguf"
MMPROJ="mmproj-model-f16.gguf"
DSPARK="dspark-MiniCPM-V-4_7-1B-F16.gguf"

# 官方 SHA256，用于校验传输完整性
SHA_GGUF="588c6c94c82f756b86dba35c42ed9ba9ec187d65824eb52f039c347df563e63d"
SHA_MMPROJ="fb9cc047a3cdad5317fd8433a4aeda2d22f00f02ca4bd93b34ad84165b25a900"
SHA_DSPARK="1581ad10c17ff4a239f20a794837015e52e89798deb562073779dc00cfd3e595"

THREADS=6      # 天玑 9300 共 8 核，留 2 核给系统
CTX=4096

# 电脑推过来的文件可能在这两个位置
SRC_CANDIDATES=(
  "$HOME/storage/downloads/minicpm"
  "$HOME/storage/downloads"
)

cmd="${1:-}"

find_src() {
  for d in "${SRC_CANDIDATES[@]}"; do
    if [ -f "$d/$GGUF" ]; then echo "$d"; return 0; fi
  done
  return 1
}

do_setup() {
  command -v termux-setup-storage >/dev/null 2>&1 && termux-setup-storage || true
  sleep 1
  local src
  if ! src="$(find_src)"; then
    echo "❌ 没找到模型文件。请确认电脑已推送，且 Termux 已授权存储（termux-setup-storage）。"
    echo "   找过这些位置：${SRC_CANDIDATES[*]}"
    exit 1
  fi
  echo "📦 来源目录：$src"
  mkdir -p "$MODEL_DIR"
  for f in "$GGUF" "$MMPROJ" "$DSPARK"; do
    if [ -f "$MODEL_DIR/$f" ]; then
      echo "   已存在，跳过：$f"
    else
      echo "   复制 $f ..."
      cp "$src/$f" "$MODEL_DIR/$f"
    fi
  done
  echo "🔐 校验 SHA256 ..."
  ( cd "$MODEL_DIR" && \
    echo "$SHA_GGUF  $GGUF"     | sha256sum -c - && \
    echo "$SHA_MMPROJ  $MMPROJ" | sha256sum -c - && \
    echo "$SHA_DSPARK  $DSPARK" | sha256sum -c - )
  echo "✅ 模型就绪：$MODEL_DIR"
  df -h "$HOME" | tail -1 | awk '{print "   剩余存储：" $4}'
}

do_build() {
  termux-wake-lock 2>/dev/null || true
  echo "🔧 安装编译依赖（较慢，请保持 Termux 在前台、屏幕常亮、插着电）..."
  pkg update -y && pkg upgrade -y
  pkg install -y git cmake clang make
  if [ ! -d "$LLAMA_DIR" ]; then
    echo "📥 克隆 llama.cpp ..."
    git clone --depth 1 https://github.com/ggml-org/llama.cpp "$LLAMA_DIR"
  else
    echo "   已存在 $LLAMA_DIR，跳过克隆（如需更新：cd ~/llama.cpp && git pull）"
  fi
  cd "$LLAMA_DIR"
  echo "⚙️  配置 ..."
  # 显式指定 ARM 指令集：必须带 +fp16，否则视觉编码器（mmproj，fp16）会慢几十倍。
  # 仅靠 GGML_NATIVE=ON 时 fp16 取决于 -mcpu=native 的推断，ggml 只显式探测 dotprod/i8mm。
  cmake -B build -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF -DGGML_OPENMP=OFF \
    -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16+i8mm
  echo "🏗️  编译（这一步最久，15~40 分钟，中途发热正常）..."
  cmake --build build -j8 --target llama-mtmd-cli llama-server
  termux-wake-lock -u 2>/dev/null || true
  echo "✅ 编译完成，产物："
  ls -lh "$BIN/llama-mtmd-cli" "$BIN/llama-server"
}

do_check() {
  echo "--- 模型文件 ---"
  for f in "$GGUF" "$MMPROJ" "$DSPARK"; do
    if [ -f "$MODEL_DIR/$f" ]; then
      printf '  OK   %-42s %s 字节\n' "$f" "$(stat -c %s "$MODEL_DIR/$f")"
    else
      printf '  缺失 %s\n' "$f"
    fi
  done
  echo "--- 二进制 ---"
  for b in llama-mtmd-cli llama-server; do
    [ -x "$BIN/$b" ] && printf '  OK   %s\n' "$b" || printf '  缺失 %s\n' "$b"
  done
}

do_serve() {
  do_check
  echo "🚀 启动本地服务：浏览器打开 http://127.0.0.1:8080"
  echo "   （停止：按 音量减 + C，即 Ctrl+C）"
  termux-wake-lock 2>/dev/null || true
  # --path 指向自制网页界面：Termux 源码编译出的 llama-server 不含官方 WebUI（官方是 CI 单独构建嵌入的），
  # 不指定 --path 时访问根路径会返回 404，只能走 API。
  WEBUI_ARG=""
  [ -f "$HOME/webui/index.html" ] && WEBUI_ARG="--path $HOME/webui"
  exec "$BIN/llama-server" \
    -m  "$MODEL_DIR/$GGUF" \
    --mmproj "$MODEL_DIR/$MMPROJ" \
    -md "$MODEL_DIR/$DSPARK" \
    --host 127.0.0.1 --port 8080 \
    -t "$THREADS" -c "$CTX" --jinja \
    --reasoning off $WEBUI_ARG
}

do_ask() {
  img="${2:?用法: bash ~/termux-setup.sh ask <图片路径> \"问题\"}"
  q="${3:-描述这张图片}"
  exec "$BIN/llama-mtmd-cli" \
    -m  "$MODEL_DIR/$GGUF" \
    --mmproj "$MODEL_DIR/$MMPROJ" \
    --image "$img" -p "$q" \
    -t "$THREADS" -n 256 --jinja \
    --temp 0.7 --top-p 0.95 --top-k 100 --repeat-penalty 1.03
}

case "$cmd" in
  setup) do_setup ;;
  build) do_build ;;
  check) do_check ;;
  serve) do_serve ;;
  ask)   do_ask "$@" ;;
  *)
    cat <<'EOF'
用法：
  bash ~/termux-setup.sh setup                # 从 Download 搬模型并校验
  bash ~/termux-setup.sh build                # 编译 llama.cpp
  bash ~/termux-setup.sh check                # 检查模型与程序是否齐全
  bash ~/termux-setup.sh serve                # 起本地服务（浏览器访问）
  bash ~/termux-setup.sh ask <图片> "问题"     # 命令行单次问答
EOF
    exit 1
    ;;
esac
