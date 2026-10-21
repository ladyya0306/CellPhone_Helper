# MiniCPM-V 4.7 部署工作区

一句话：**模型是真的**（面壁智能 / OpenBMB 2026-10 发布的端侧多模态模型，1B dense + 35B-A3B MoE 两个规格），已经下载校验完毕，手机端与 PC 端都实测跑通了。

---

## 交付物

| 文件 | 内容 |
|---|---|
| **双击入口（推荐）** | [启动 MiniCPM.cmd](启动%20MiniCPM.cmd) · [停止 MiniCPM.cmd](停止%20MiniCPM.cmd) · [状态与自测.cmd](状态与自测.cmd) · [推送模型到手机.cmd](推送模型到手机.cmd) · [全自动部署到手机.cmd](全自动部署到手机.cmd) |
| [scripts/minicpm.ps1](scripts/minicpm.ps1) | **本机标准启动方式**：start / stop / restart / status / logs / test / 开机自启 |
| [scripts/auto-setup-phone.ps1](scripts/auto-setup-phone.ps1) | **手机端全自动部署**：搬模型 → 换源 → 装工具 → 传源码 → 编译 → 建免打字入口（手机上零输入） |
| [scripts/push-to-phone.ps1](scripts/push-to-phone.ps1) | 装 Termux + 推模型 + 推脚本 + 逐个回读校验（已存在的文件自动跳过） |
| [docs/Termux-新手指南.md](docs/Termux-新手指南.md) | **第一次用 Termux 就看这份**：从认识界面、换国内源，到编译、运行、排错 |
| [docs/vivo-X100-Pro-MiniCPM-V-4.7-部署指南.md](docs/vivo-X100-Pro-MiniCPM-V-4.7-部署指南.md) | 手机端技术版说明：路线对比、把 35B 跑上手机为什么还需要等官方 App |
| [docs/PC-35B-A3B-实测报告.md](docs/PC-35B-A3B-实测报告.md) | **本机实测报告**：配置、基准数字、推荐参数、限制说明 |
| [scripts/termux-setup.sh](scripts/termux-setup.sh) | 手机端脚本：`setup` / `build` / `check` / `serve` / `ask` |
| [scripts/download-parallel.ps1](scripts/download-parallel.ps1) | 多连接分块下载器（SHA256 校验、断点续传、失败重试） |
| [scripts/query-server.py](scripts/query-server.py) | 向本地服务发图片问答的测试客户端 |

---

## 模型文件（全部已校验）

### 手机端用（`models/`）

| 文件 | 字节数 | SHA256 |
|---|---|---|
| `MiniCPM-V-4_7-1B-Q4_K_M.gguf` | 672,003,680 | `588c6c94c82f756b86dba35c42ed9ba9ec187d65824eb52f039c347df563e63d` |
| `mmproj-model-f16.gguf` | 1,110,117,984 | `fb9cc047a3cdad5317fd8433a4aeda2d22f00f02ca4bd93b34ad84165b25a900` |
| `dspark-MiniCPM-V-4_7-1B-F16.gguf` | 438,065,792 | `1581ad10c17ff4a239f20a794837015e52e89798deb562073779dc00cfd3e595` |

### PC 端 35B 用（`models/35B-A3B/`）—— **已于 2026-10-16 按你的要求清理，释放 16.2GB**

| 文件 | 字节数 | SHA256 |
|---|---|---|
| ~~`MiniCPM-V-4_7-35B-A3B-mix_q3gud_im.gguf`~~ | ~~15,503,944,896~~ | `51e32201f0ddaf87dbb9689ceef9e3aa1a1543a1366706aa2abbf3aabb34df6b` |
| ~~`mmproj-model-f16.gguf`~~ | ~~1,119,575,840~~ | `bff3b688e14c300eca273092c37c50a0ddfcc2054e079af1a0480edeed5d20f1` |
| ~~`dspark-MiniCPM-V-4_7-35B-A3B-F16.gguf`~~ | ~~789,442,368~~ | `dbeca717b96448ecbb2240864ae00d2f90fe069bc3a8ce08c58084c8b7b96c0f` |

想再试 35B（实测约 10～11 t/s）时，重新下载即可，脚本已存好，**约 20 分钟**：

```powershell
cd "D:\MiniProj\CellPhone Helper\minicpm-v4.7"
$s = ".\scripts\download-parallel.ps1"; $h = "https://huggingface.co/openbmb/MiniCPM-V-4.7-35B-A3B-gguf/resolve/main"
& $s -Url "$h/MiniCPM-V-4_7-35B-A3B-mix_q3gud_im.gguf" -OutFile ".\models\35B-A3B\MiniCPM-V-4_7-35B-A3B-mix_q3gud_im.gguf" -Size 15503944896 -Sha256 51e32201f0ddaf87dbb9689ceef9e3aa1a1543a1366706aa2abbf3aabb34df6b
& $s -Url "$h/mmproj-model-f16.gguf" -OutFile ".\models\35B-A3B\mmproj-model-f16.gguf" -Size 1119575840 -Sha256 bff3b688e14c300eca273092c37c50a0ddfcc2054e079af1a0480edeed5d20f1
& $s -Url "$h/dspark-MiniCPM-V-4_7-35B-A3B-F16.gguf" -OutFile ".\models\35B-A3B\dspark-MiniCPM-V-4_7-35B-A3B-F16.gguf" -Size 789442368 -Sha256 dbeca717b96448ecbb2240864ae00d2f90fe069bc3a8ce08c58084c8b7b96c0f
```

> 两套 `mmproj-model-f16.gguf` **不是同一个文件**（1,110,117,984 vs 1,119,575,840 字节），不能混用。

### 工具与安装包

| 路径 | 说明 |
|---|---|
| `llamacpp-cpu/` | llama.cpp b11671 Windows CPU 版（跑 35B 用这个） |
| `llamacpp-vulkan/` | llama.cpp b11671 Windows Vulkan 版（跑 1B 用这个，可调用 780M 核显） |
| `tools/platform-tools/adb.exe` | Google 官方 adb（37.0.1），用于把模型推进手机 |
| `apk/termux-app_v0.118.3+github-debug_arm64-v8a.apk` | Termux 稳定版安装包（33.5MB），SHA256 已与官方校验单比对一致 |
| `apk/MiniCPM-V-demo-Android-v2.5.apk` | 官方安卓 App（20.0MB，MD5 与官方发布页一致），**目前只支持到 MiniCPM-V 4.6** |

---

## 本机部署与日常启动

**这台电脑上已经部署好并正在运行**：1B 跑在 Radeon 780M 核显 + CPU 视觉塔，实测约 **45～51 t/s**。
不要手敲那一长串参数，用启动器：

### 最省事：双击

```
启动 MiniCPM.cmd
```

### 或者命令行（功能更全）

```powershell
cd "D:\MiniProj\CellPhone Helper\minicpm-v4.7\scripts"

.\minicpm.ps1 start          # 启动（检查文件 → 挑端口 → 后台运行 → 等就绪 → 打印地址）
.\minicpm.ps1 status         # 看状态：PID、已运行时长、内存、健康检查
.\minicpm.ps1 test           # 发一张测试图，验证视觉链路是否正常（含速度）
.\minicpm.ps1 logs           # 看最近的运行日志
.\minicpm.ps1 stop           # 停止
.\minicpm.ps1 restart        # 重启

.\minicpm.ps1 autostart-install   # 装成开机自启（登录后自动拉起）
.\minicpm.ps1 autostart-remove    # 取消开机自启
```

启动后浏览器打开 **<http://127.0.0.1:8080>**，界面里可以直接传图提问。

### 可选开关

| 开关 | 作用 |
|---|---|
| `-Lan` | 绑定 `0.0.0.0`，**手机在同一 WiFi 下可以连电脑的模型**（会打印手机可用的地址） |
| `-Backend cpu` | 强制纯 CPU（核显驱动异常时的退路） |
| `-Model 35B` | 换 35B-A3B（权重已清理，需先按上一节重新下载） |
| `-Verify` | 启动时做完整 SHA256 校验（默认只查文件大小，快） |
| `-NoDraft` | 不使用草稿模型（默认只有 35B+CPU 才启用） |
| `-Port 9000` | 换端口（被占用时启动器也会自动往后找） |

启动器会自动做的事：校验模型文件大小 → 找可用端口 → 加 `--reasoning off` 关掉 thinking → 用 `--log-file` 让服务自己写日志 → 后台拉起并等待健康检查 → 把 PID、端口、配置写进 `run\server.json`。

> 关于关掉 thinking：官方已弃用 `--chat-template-kwargs '{"enable_thinking":false}'` 这种写法（会打印弃用警告），**新参数是 `--reasoning off`**，启动器已经用上了。
>
> 已开启**开机自启**：登录 Windows 后会自动在后台拉起（已在运行则不会重复启动）。取消用 `.\minicpm.ps1 autostart-remove`。

**手机上跑 1B —— 全自动，手机上零输入**

> 手机上的「16+16G 动态扩展运行内存」是**闪存当虚拟内存**，对跑模型没有帮助反而拖慢（带宽差一两个数量级），还会占存储。跑之前建议关掉，16GB 物理内存跑 1B 完全够用。

手机开好 USB 调试、插上数据线，电脑上**双击 `全自动部署到手机.cmd`**（或跑 `.\scripts\auto-setup-phone.ps1`），它会做完这些：

| 步骤 | 怎么做到的 |
|---|---|
| 1. 把 2.1GB 模型搬进 Termux | 用 adb 的 `run-as` 以 Termux 身份执行，**手机内部复制**（实测 233 MB/s，约 10 秒，不走 USB、不经过电脑） |
| 2. 换国内软件源 | 直接写 `$PREFIX/etc/apt/sources.list`（清华源），省掉 `termux-change-repo` 那套交互菜单 |
| 3. 装 git / cmake / clang / make | 手机上后台跑，电脑这边实时显示日志 |
| 4. 传 llama.cpp 源码 | **电脑下载源码包再送进手机**，不依赖手机的网络能不能连上 GitHub |
| 5. 编译 | 手机上后台跑（20～40 分钟），adb 断开也不影响；随时用 `-Action status` 看进度 |
| 6. 建免打字启动入口 | `~/.shortcuts/启动MiniCPM.sh`（配合 Termux:Widget）+ `~/.termux/boot/start-minicpm.sh`（配合 Termux:Boot） |
| 7. 自动装好 Termux:Widget 与 Termux:Boot | 并写好 `allow-external-apps=true`，两个 App 已用 adb 安装并激活 |

之后日常启动**只需点一下 Termux 图标**，服务与浏览器都会自动就绪：

- **打开 Termux 就自动启动服务，并自动弹出浏览器。** 已写入 `~/.bashrc` / `~/.profile` / `~/.bash_profile`（用绝对路径，不依赖 `$HOME`）：检测到服务未运行就后台拉起 → 轮询 `/health` 直到就绪 → `termux-open-url` 自动打开 `http://127.0.0.1:8080`。**实测：模拟点开 Termux，5 秒内服务已起且浏览器已在前台。**
  - 不想要自动弹浏览器：`touch ~/.auto/no-auto-open`
- **⚠️ 关键坑：vivo 的「应用跳转确认」**。Termux 拉起浏览器时，vivo 会插入 `com.vivo.appfilter/.activity.AppJumpPromptActivity` 弹窗：「"Termux"想要打开"Edge"」，选项是 **始终打开 / 仅打开一次 / 取消**——这就是"自启动、关联启动、悬浮窗、后台弹出界面全给了却还是没反应"的真凶：它和那些权限**不是同一个机制**，是独立的应用跳转管控。**点一次「始终打开」后 vivo 就记住了**，之后不再弹窗（已实测确认第二次直接拉起浏览器、不再弹窗）。
- **桌面小组件（Termux:Widget）**：点一下会列出三个脚本（启动MiniCPM / 查看状态 / 停止MiniCPM）。它走的也是"启动 Activity"这条路，所以**大概率被同一个跳转管控拦**——如果它弹窗，同样点「始终打开」即可。不过「打开 Termux」已经全自动，小组件属可选项。
- **开机自启（Termux:Boot）**：实测**在你的手机上不生效**——`BootReceiver` 注册正常、`RECEIVE_BOOT_COMPLETED` 权限也在，但 vivo 系统根本没有派发开机广播（自启脚本日志为空）。需要在 `设置 → 应用与权限 → 权限管理 → 自启动` 里给 **Termux:Boot** 单独放行（是 Termux:Boot，不是 Termux）。

### 🔀 离线 / 在线 双后端切换

助手支持两种后端，网页上「🧑💼 助手」模式顶部可一键切换：

| | 离线 · 本机 | 在线 · 云端（DeepSeek） |
|---|---|---|
| 模型 | 手机上的 MiniCPM-V 4.7 1B | DeepSeek API（OpenAI 兼容） |
| 联网 | **完全不需要**，数据不出手机 | 需要网络，内容会上传到云端 |
| 速度 | 简单任务 20～30 秒 | 通常几秒到十几秒 |
| 工具选择准确度 | 一般（1B 模型） | 明显更好 |
| 成本 | 0 | 按 token 计费 |
| 传图 | 支持（视觉编码约 1～3 分钟） | 支持（走 DeepSeek 视觉模型，很快） |

配置方式：助手模式顶部点「在线 · 云端」→ 填入 DeepSeek API Key → 保存。
Key 存在手机本地 `~/.minicpm-assistant/config.json`（权限 600，只有本应用能读），接口只回显末四位。
模型名、API 地址都可在该配置文件里改（`deepseek_model` / `deepseek_vision_model` / `deepseek_base`），
以便跟随官方文档调整——**带图时用 `deepseek_vision_model`，纯文本时用 `deepseek_model`**。

> 依据：DeepSeek 的 API 是 OpenAI 兼容接口（支持 function calling / Responses API），
> 并于 2026-08-21 上线多模态视觉模型 [V4-Flash-Vision-Exp](https://api-docs.deepseek.com/zh-cn/news/news260821/)，
> 视觉调用格式见官方 [图像理解文档](https://api-docs.deepseek.com/zh-cn/guides/vision/)。
> ⚠️ DeepSeek **不接受音频输入**（有公开 issue 记录其拒绝 OpenAI 的 `input_audio` 内容块），
> 所以"录音转文字"需要另配 ASR（本机 whisper.cpp 或其它云服务）。

### 🧑💼 办公助手（能用对话操作手机）

网页界面右上角有 **💬 对话 / 🧑💼 助手** 切换。助手模式下，你说人话，它调用工具真正操作手机：

| 能做 | 工具 |
|---|---|
| 待办 | `todo_add` / `todo_list` / `todo_done` / `todo_remove`（存在 `~/.minicpm-assistant/todos.json`） |
| 闹钟 / 计时 | `set_alarm_at`（直接说"明天早上7点"）/ `set_timer` |
| 导航 | `navigate`（交给高德/百度地图） |
| 应用与网页 | `find_app` / `open_app` / `open_url` |
| 文件（**只读**） | `fs_list` / `fs_search` / `fs_read` / `fs_stat` / `disk_usage`，限 `/sdcard` 与 Termux 家目录 |
| 计划与笔记 | `save_plan` / `list_plans` / `read_plan`（存在 `~/.minicpm-assistant/plans/`） |
| 时间 | `current_time` / `parse_when`（中文时间换算，"周五下午三点"→ 具体日期） |

实现：`assistant/assistant.py`（纯标准库 Python，Termux 内运行，监听 8081；调用本地 llama-server 8080 的 function call）。
启动：打开 Termux 时自动拉起（与模型服务一起），无需手动。

**安全设计**：用户文件只读；只允许写助手自己的目录；系统动作只"发起"不改设置；模型在没调工具却声称"已完成"时，回答里会追加**明确警告**，不会让你被误导。

**⚠️ 关于 1B 模型的可靠性（实测）**：

| 场景 | 结果 |
|---|---|
| "帮我加一条待办：周五下午三点开组会" | ✅ 正确调用 `todo_add`，日期算对 |
| "明天早上 7 点叫我起床" | ✅ 正确设置系统闹钟 |
| "你好"（闲聊） | ✅ 不调用任何工具 |
| "导航到北京南站" | ❌ 没调用 `navigate`，反而去找叫"导航"的 App 并设了个闹钟 |

**结论**：说法明确、单一意图的任务成功率不错；含糊或需要多步推理的任务容易选错工具。界面里会显示**完整的工具调用轨迹**（调了哪个工具、什么参数、返回什么），所以你能立刻看出它到底做没做、做得对不对。想更准可以把它指向电脑上的 35B 模型（局域网），工具选择会明显改善。

> 底层依据：模型对话模板内置完整的 `<tool_call><function=…><parameter=…>` 协议（模板里 21 处 `tool_call`、17 处 `function`），配合 `--jinja` 由 llama-server 解析成标准 OpenAI `tool_calls`。实测带 `tools` 请求会返回 `finish_reason: "tool_calls"` 和结构化 `tool_calls`，所以模型端能驱动 agent 循环。但**浏览器本身不能操作手机**——网页没有文件权限也没有执行能力，必须有 Termux 里的执行器（就是上面这个助手）。

> ⚠️ 现实提醒：1B 模型的工具调用**参数容易出错**（实测把 `/sdcard/Download` 说成了 `/Download`）。做文件操作必须加路径校验 + 危险操作二次确认，建议先「试运行」列清单给你确认再执行。

### ⚠️ 关于根路径 404（已修复）

Termux 里**从源码编译的 llama-server 不含官方 WebUI**——官方发行版是 CI 单独构建前端再嵌进去的，源码树里根本没有 `tools/server/webui` 目录，所以访问 `http://127.0.0.1:8080` 会返回 **404**（但 `/health` 和 API 都是正常的）。

解决办法是用 llama-server 的 `--path` 参数挂一个自制页面：工作区里 [webui/index.html](webui/index.html) 是一个单文件聊天界面（无需任何外部依赖），已部署到手机 `~/webui/`，启动命令带上了 `--path $HOME/webui`。现在访问根路径就是正常界面了，支持打字聊天、传图片、流式输出、显示速度。

> 想看进度或重跑：`.\scripts\auto-setup-phone.ps1 -Action status`（看进度）/ `-Action build`（只重编译）/ `-Action transfer`（只搬模型）。

手动版本（想理解每一步到底干了什么时看）：[Termux 新手指南](docs/Termux-新手指南.md)。

### 手机端实测数据（vivo X100 Pro，已部署验证）

| 项目 | 实测 | 说明 |
|---|---|---|
| 纯文本生成 | **18.07 t/s** | 预处理 62.20 t/s，很流畅 |
| 图片问答（含视觉编码） | **约 3 分钟** | ⚠️ 瓶颈在视觉编码器，见下 |
| 编译参数 | `-march=armv8.2-a+dotprod+fp16+i8mm` | 已在设备上核对；`+fp16` 必须显式加，靠 `GGML_NATIVE=ON` 推断会漏掉 |
| 结果正确性 | ✅ | 文字识别全对、圆形颜色正确 |

**关于图片慢（约 3 分钟）**：手机端日志显示视觉编码单独耗时 **174～184 秒**，而同一张图在电脑上只要 **4.7 秒**（37 倍差距）。已排除的原因：不是热降频（大核在负载中恒定 1200MHz，说明根本没跑满）、不是内存不足、加 `+fp16` 也没改善（但文本速度因此从 8.7 提升到 18 t/s）。加线程有效（1 线程 → 6 线程快 4.6 倍），说明瓶颈在 ARM 上这条 ViT 计算路径本身的效率。

**实用结论**：手机上**文字对话很好用**；**图片问答要有心理准备等 3 分钟**，赶时间就用电脑（55 t/s，一张图约 6 秒）。

---

## 常见问题

### 报「未对文件…进行数字签名，无法在当前系统上运行该脚本」

这是 Windows 的 PowerShell 执行策略拦住了 `.ps1`，和模型无关。**根目录下所有 `.cmd` 文件内部都强制带了 `-ExecutionPolicy Bypass`，双击即可，不会再撞这个墙。**

如果你就是想用命令行跑 `.ps1`，三种解法任选：

```powershell
# 1) 单次绕过（最省事，不改任何系统设置）
powershell -ExecutionPolicy Bypass -File .\scripts\push-to-phone.ps1

# 2) 在交互式窗口里临时放开
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\push-to-phone.ps1

# 3) 如果文件是从网上拷来的，先解除锁定
Unblock-File .\scripts\push-to-phone.ps1
```

> 本机实测的机器策略是 `RemoteSigned`，而脚本文件并没有"来自网络"标记，按理不该被拦——所以那次报错很可能来自当时那个终端会话（或安全软件）。上面三种办法在任何策略下都有效。

### 手机推送相关

| 现象 | 处理 |
|---|---|
| `adb: no devices/emulators found` | 开发者选项里打开 USB 调试；数据线插好后在手机弹窗点「允许」；USB 模式选「传输文件/MTP」 |
| 安装 Termux 时手机弹窗被误拒 | 脚本会提示；手机上手动点允许后重跑脚本即可（已安装则会自动跳过） |
| 重复运行推送脚本 | 已存在且大小一致的文件会**自动跳过**，不会重复传 2GB |

---

## 官方来源

| 用途 | 地址 |
|---|---|
| 主仓库 | <https://github.com/OpenBMB/MiniCPM-V> |
| 1B 权重 | <https://huggingface.co/openbmb/MiniCPM-V-4.7-1B> |
| 1B GGUF | <https://huggingface.co/openbmb/MiniCPM-V-4.7-1B-gguf> |
| 35B-A3B 权重 | <https://huggingface.co/openbmb/MiniCPM-V-4.7-35B-A3B> |
| 35B-A3B GGUF | <https://huggingface.co/openbmb/MiniCPM-V-4.7-35B-A3B-gguf> |
| 端侧 App（iOS/安卓/鸿蒙） | <https://github.com/OpenBMB/MiniCPM-V-Apps> |
| 部署手册 | <https://github.com/OpenSQZ/MiniCPM-V-Cookbook> |
| LES 加速原理 | <https://github.com/OpenBMB/llama.cpp-omni/blob/main/docs/les/lookahead-expert-scheduling.md> |
| Ollama 版 | <https://ollama.com/openbmb/minicpm-v4.7> |

---

## 两个重要结论（省得你踩坑）

1. **官方安卓 App 还不能跑 4.7。** 我们核对了仓库 HEAD 的 Android 源码，模型清单里没有 4.7 条目（只有 V4 / V4.6 / MiniCPM5 / VoxCPM2），iOS 端才有 `MBV47Const`。官方 README 写"三端支持 4.7"与代码不符。所以手机端走 Termux 自编译。
2. **35B-A3B 在手机上需要专用运行时。** 它靠 LES 把专家权重剥离到 SSD 流式读取，普通 GGUF 不行（官方桌面版量化专家是内联的，手机会被系统杀掉）。等 Android App 更新。
