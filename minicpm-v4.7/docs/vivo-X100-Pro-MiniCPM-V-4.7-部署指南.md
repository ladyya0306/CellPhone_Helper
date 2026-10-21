# vivo X100 Pro 部署 MiniCPM-V 4.7 1B 完整指南

> 目标设备：vivo X100 Pro（天玑 9300，4×Cortex-X4 + 4×Cortex-A720 全大核，12/16GB LPDDR5T，UFS 4.0，Android 14 / OriginOS）
> 目标模型：`openbmb/MiniCPM-V-4.7-1B`（多模态，1B 语言主干 + SigLIP 视觉塔，256K 上下文）

---

## 一、先明确：手机上到底能跑什么

| 规格 | 能否在你的手机上跑 | 说明 |
|---|---|---|
| **MiniCPM-V 4.7 1B** | ✅ **可以，本文主路线** | 权重约 1.7GB，常驻内存无压力 |
| MiniCPM-V 4.7 35B-A3B | ⚠️ 目前不行 | 需要官方 App 的"专家权重剥离 + SSD 流式读取"专用运行时，**Android 端尚未支持 4.7**（详见第五节） |
| MiniCPM-V 4.6（上一代） | ✅ 最省事 | 官方 APK v2.5 原生支持，见第四节 |

**为什么不能直接用官方 APK 跑 4.7？** 我们核对了 `OpenBMB/MiniCPM-V-Apps` 仓库 HEAD 的 Android 源码：`MiniCPM-V-demo-Android/.../ModelInfo.kt` 里 `AVAILABLE_MODELS` 只有 MiniCPM-V-4、MiniCPM-V-4.6、MiniCPM5-1B/2B、VoxCPM2，**没有 4.7 条目**；而 iOS 端已有 `MBV47ModelDownloadManager.swift` 与 `MiniCPMV47Const`。也就是说 4.7 目前是 iOS/TestFlight 先行，Android 与 HarmonyOS 还在后面。README 里"三端均支持 4.7"的说法与代码不一致。

---

## 二、已为你准备好的文件

所有文件已下载并**通过官方 SHA256 校验**，位于工作区 `minicpm-v4.7/`：

| 文件 | 大小 | SHA256（官方） |
|---|---|---|
| `models/MiniCPM-V-4_7-1B-Q4_K_M.gguf` | 672,003,680 B（641 MiB） | `588c6c94c82f756b86dba35c42ed9ba9ec187d65824eb52f039c347df563e63d` |
| `models/mmproj-model-f16.gguf` | 1,110,117,984 B（1.03 GiB） | `fb9cc047a3cdad5317fd8433a4aeda2d22f00f02ca4bd93b34ad84165b25a900` |
| `models/dspark-MiniCPM-V-4_7-1B-F16.gguf` | 438,065,792 B（418 MiB） | `1581ad10c17ff4a239f20a794837015e52e89798deb562073779dc00cfd3e595` |
| `tools/platform-tools/adb.exe` | — | Google 官方 platform-tools 37.0.1 |
| `apk/MiniCPM-V-demo-Android-v2.5.apk` | 20.0 MiB | MD5 `b25150612fefe253859324e91716fc81`（与官方发布页一致） |

- `Q4_K_M` 是语言模型本体；`mmproj` 是**视觉投影器 + ViT**，缺了它模型只能当纯文本用；`dspark` 是推测解码草稿模型，**PC 实测可提速约 22%**。
- 手机上一共需要约 **2.1 GB** 存储（三个文件全放）。

---

## 三、主路线：Termux 里自编译 llama.cpp（推荐）

官方 Android App 短时间等不到 4.7，那就在手机上自己跑 llama.cpp。上游 `ggml-org/llama.cpp` 的 PR #29416（Support MiniCPM-V 4.7）**已于 2026-10-10 合入主线**，我在 Windows 预编译版 b11671 上验证了 4.7 的视觉投影类型（`PROJECTOR_TYPE_MINICPMV4_7`）与 `qwen35` 主干都在支持范围内。

### 3.1 安装 Termux

**不要用 Google Play 版**（已废弃），从下面任一渠道装：

- F-Droid：<https://f-droid.org/packages/com.termux/>
- GitHub Releases：<https://github.com/termux/termux-app/releases>

装完打开是命令行界面。以下命令逐条执行（首次会更新软件源，较慢）：

```bash
pkg update -y && pkg upgrade -y
pkg install -y git cmake clang make
```

### 3.2 编译 llama.cpp

```bash
git clone --depth 1 https://github.com/ggml-org/llama.cpp ~/llama.cpp
cd ~/llama.cpp
cmake -B build -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF -DGGML_OPENMP=OFF
cmake --build build -j8 --target llama-mtmd-cli llama-server
```

- 编译在手机上大概 **15～40 分钟**，中途发热正常，建议插着充电器、别锁屏（先 `termux-wake-lock`）。
- 天玑 9300 是 ARMv9，llama.cpp 会自动启用 dotprod/i8mm 等指令集，无需额外参数。
- 只编两个目标（`llama-mtmd-cli`、`llama-server`）能省一半时间；产物在 `~/llama.cpp/build/bin/`。
- 若报 OpenMP 相关错误，`-DGGML_OPENMP=OFF` 已经规避；若报 curl 错误，`-DLLAMA_CURL=OFF` 已经规避。

### 3.3 把模型传进手机

**方式一：adb（推荐，可校验）**

手机端：`设置 → 关于手机 → 连点版本号` 打开开发者模式，再到 `设置 → 系统 → 开发者选项` 打开 **USB 调试**，用数据线连电脑。

电脑端（工作区已带 adb）：

```powershell
cd "D:\MiniProj\CellPhone Helper\minicpm-v4.7"
.\tools\platform-tools\adb.exe devices      # 手机上会弹授权，点"允许"
.\tools\platform-tools\adb.exe push .\models\MiniCPM-V-4_7-1B-Q4_K_M.gguf /sdcard/Download/
.\tools\platform-tools\adb.exe push .\models\mmproj-model-f16.gguf       /sdcard/Download/
.\tools\platform-tools\adb.exe push .\models\dspark-MiniCPM-V-4_7-1B-F16.gguf /sdcard/Download/
```

**方式二：USB 数据线选"文件传输"，直接拷到手机 `Download` 目录。**

然后在 Termux 里把它们挪到应用私有目录（避免 Android 11+ 对外部存储的执行/权限限制，速度也更稳）：

```bash
termux-setup-storage          # 弹窗点"允许"
mkdir -p ~/models/minicpm-v4.7
cp ~/storage/downloads/MiniCPM-V-4_7-1B-Q4_K_M.gguf ~/models/minicpm-v4.7/
cp ~/storage/downloads/mmproj-model-f16.gguf         ~/models/minicpm-v4.7/
cp ~/storage/downloads/dspark-MiniCPM-V-4_7-1B-F16.gguf ~/models/minicpm-v4.7/
ls -l ~/models/minicpm-v4.7/   # 三个文件大小应为 672003680 / 1110117984 / 438065792
```

### 3.4 跑起来（两种用法）

**用法 A：命令行单次问答（最直接）**

```bash
~/llama.cpp/build/bin/llama-mtmd-cli \
  -m  ~/models/minicpm-v4.7/MiniCPM-V-4_7-1B-Q4_K_M.gguf \
  --mmproj ~/models/minicpm-v4.7/mmproj-model-f16.gguf \
  --image  ~/storage/shared/DCIM/Camera/你的照片.jpg \
  -p "读出图中所有文字，并说明主要内容" \
  -t 6 -n 256 --temp 0.7 --top-p 0.95 --top-k 100 --repeat-penalty 1.03 --jinja
```

**用法 B：起一个本地服务，用手机浏览器交互（体验最好，推荐）**

```bash
~/llama.cpp/build/bin/llama-server \
  -m  ~/models/minicpm-v4.7/MiniCPM-V-4_7-1B-Q4_K_M.gguf \
  --mmproj ~/models/minicpm-v4.7/mmproj-model-f16.gguf \
  -md ~/models/minicpm-v4.7/dspark-MiniCPM-V-4_7-1B-F16.gguf \
  --host 127.0.0.1 --port 8080 \
  -t 6 -c 4096 --jinja \
  --reasoning off
```

> 上面的命令也可以用脚本一键完成：`bash ~/termux-setup.sh serve`。

然后手机浏览器打开 **<http://127.0.0.1:8080>**，界面里可以直接传图片提问，无需联网（模型全在本地）。

> `-md` 是 DSpark 草稿模型（推测解码）。**注意：`llama-mtmd-cli` 不支持 `-md`，只有 `llama-server` 支持**——这也是推荐用法 B 的原因之一。我在 PC 上实测：带草稿 36.5 t/s，不带 29.9 t/s。

### 3.5 关于 thinking（很重要）

MiniCPM-V 4.7 的对话模板**默认开启 `<think>` 思考块**，回答会先输出一大段推理过程，手机上白等一倍时间。关闭方式：

- **服务端（推荐）**：启动参数加 `--reasoning off`（上面用法 B 已带，`termux-setup.sh` 也已带）。
  这是官方当前的做法；老写法 `--chat-template-kwargs '{"enable_thinking":false}'` **已被官方标记弃用**（启动时会打印弃用警告），不建议再用。API 调用时仍可在请求体里传 `"chat_template_kwargs": {"enable_thinking": false}`。
- **命令行**：`llama-mtmd-cli` 没有 `--reasoning` 参数，需要显式传模板字符串（`--chat-template` 读的是模板内容本身，**不是文件路径**）：

  ```bash
  TPL=$(cat ~/models/minicpm-v4.7/chat-template-no-think.jinja)
  ~/llama.cpp/build/bin/llama-mtmd-cli -m ... --mmproj ... --jinja --chat-template "$TPL" ...
  ```

  该模板文件在工作区 `minicpm-v4.7/research/chat-template-no-think.jinja`，一行 `{%- set enable_thinking = false -%}` 加在模型原始模板前面即可（已在 PC 上验证生效）。

### 3.6 参数建议

| 参数 | 建议 | 原因 |
|---|---|---|
| `-t` | 6 | 天玑 9300 共 8 核，留 2 核给系统，否则会掉帧/发热 |
| `-c` | 4096 | 上下文越长 KV cache 越吃内存；手机上够用即可 |
| `-n` | 256 | 单次最多生成 token 数 |
| 采样 | `--temp 0.7 --top-p 0.95 --top-k 100 --repeat-penalty 1.03` | 官方模型卡推荐的 instruct 模式采样参数 |

性能预期：PC 上 Ryzen 7 7840U（8 线程）实测生成 27 t/s；天玑 9300 的 X4 大核单核更强，**预计手机端在 15～30 t/s 区间**（视散热与后台而定）。图像编码（ViT）在 CPU 上约需 4～5 秒，这部分是固定开销。

---

## 四、备选路线（如果你想立刻看到东西）

**官方 App 跑 4.6（零折腾）**

1. 安装工作区里的 `apk/MiniCPM-V-demo-Android-v2.5.apk`（设置里允许"安装未知应用"）。
2. 打开 App → **模型管理** → 选 MiniCPM-V-4.6 → 下载（约 1.6GB，App 会自动拉取并校验）。
3. 直接对话，支持拍照识图。

要求：Android 8.0+、arm64-v8a、内存 ≥6GB——你的机器全部满足。这条路今天就能用，只是模型是 4.6 而非 4.7。

**LiteRT 路线（性能潜力最好，但步骤多）**

社区已把 4.7 1B 转成 Google LiteRT-LM 格式：[litert-community/MiniCPM-V-4.7-1B](https://huggingface.co/litert-community/MiniCPM-V-4.7-1B)，其中 `MiniCPM-V-4.7-1B-VL_int8vis.litertlm`（1.70GB）是专门为 **Mali GPU 的 8GB 安卓机**准备的版本（fp16 视觉编码器曾在部分机型上把手机跑重启）。天玑 9300 正是 Immortalis-G720（Mali 系），所以应选 `_int8vis`。

需要 LiteRT-LM 的 Android 运行时（`litert_lm_advanced_main`，adb push 到 `/data/local/tmp` 后运行），或用支持导入 `.litertlm` 的 Google AI Edge Gallery。这条路线我没在真机上验证过，属于"可以试"的选项。

---

## 五、35B-A3B 在手机上的现状

35B-A3B 能跑在手机上，靠的是三件事：**LES（前瞻专家调度）** 把路由专家权重从主文件里剥离、按需从 SSD 流式读取；逐层混合精度量化；以及 DSpark 推测采样。官方数据是 iPad Pro M4（16GB）17.39 t/s、iPhone 17 Pro Max 13+ t/s、约 3GB 内存。

但这条路**依赖官方 App 的专用运行时**：

- 需要拆分版权重（`tc-mb/MiniCPM-V-Apps-gguf` 里的 `*-les.gguf` + `experts/` 40 个分片 + manifest），普通 GGUF 不行——官方明确说桌面版 `mix_q3gud_im.gguf` 的专家是内联的，手机上要么整体 mmap、要么全部常驻，都会被系统杀掉。
- 需要能识别这套拆分格式的 `llama.cpp-omni` 运行时——目前只在 iOS/Android/HarmonyOS 三个 App 里集成，**而 Android App 里没有 4.7 条目**。

所以结论：**等官方 Android 端更新**。你的 X100 Pro 是 16GB 内存 + UFS 4.0，一旦 App 支持，它会是一台合格的 35B-A3B 端侧设备（官方要求 ≥12GB 内存、约 17GB 空闲存储）。

---

## 六、常见问题排查

| 现象 | 原因与处理 |
|---|---|
| `Failed to load vision model` / `vulkan ... OutOfDeviceMemory` | 别用 Vulkan 后端跑 mmproj，改纯 CPU（不加 `-ngl`，或加 `--no-mmproj-offload`） |
| 模型加载后秒退、报 mmproj 不匹配 | `mmproj` 必须与主模型**同批次**：1B 的 mmproj 是 1,110,117,984 字节，35B 的是 1,119,575,840 字节，别混用 |
| 回答里全是 `<think>` | 见 3.5，关闭 thinking |
| `Number of media markers (0) does not match...` | `--chat-template` 传成了文件路径；要传模板**内容**（`--chat-template "$TPL"`），且模板里必须保留 `<image>` 标记 |
| 手机发烫、速度掉一半 | 正常，长时间满载必降频；`-t 6`、插电、摘壳、别在充电时跑大图 |
| Termux 被杀后台 | 运行前 `termux-wake-lock`，并在 OriginOS 的电池设置里把 Termux 设为"允许后台高耗电" |
| 编译报内存不足 | 去掉 `-j8` 改 `-j4`，或先关掉其他 App |

---

## 七、一句结论

- **手机**：今天就能用上 MiniCPM-V 4.7 1B，走 Termux + llama.cpp，把已校验好的三个文件推进去即可；35B-A3B 要等官方 Android App 更新。
- **官方 APK 现在能给你的最好体验是 4.6**，如果你只想点几下就用，走第四节。
