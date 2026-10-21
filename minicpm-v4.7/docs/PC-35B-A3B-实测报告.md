# 本机（ONEXPLAYER 2 PRO 掌机）运行 MiniCPM-V 4.7 实测报告

实测日期：2026-10-16 · 全部数字为本机真实运行结果，非官方文档转述

---

## 一、本机配置（自行读取）

| 项目 | 实测值 |
|---|---|
| 机型 | ONEXPLAYER 2 PRO ARP23P（ONE-NETBOOK 掌机） |
| CPU | AMD Ryzen 7 7840U，8 核 16 线程（Zen4），基频 3.3GHz |
| 内存 | 物理 32GB，**系统可见 25.7GB**（约 6GB 被 BIOS 划给核显） |
| 核显 | AMD Radeon 780M（RDNA3，UMA 共享内存，驱动 32.0.12019.1028） |
| 显存 | 注册表记录专用划分 6.0GB；Vulkan 报告可寻址 19,059 MiB（共享） |
| 系统 | Windows 11 家庭版（Build 26200） |
| 独显 | 无（无 NVIDIA/AMD 独显，`nvidia-smi` 不存在） |
| 磁盘 | D: 剩余约 434GB（模型放这里） |

**关键约束：没有独立显卡。** 35B-A3B 只能靠 CPU + 内存，核显的"显存"其实就是同一块内存。

---

## 二、用了什么

- 推理引擎：**llama.cpp 官方预编译版 b11671**（commit `ba3c05f71`，2026-10-16 发布）
  - 已确认该版本支持 MiniCPM-V 4.7：源码 `tools/mtmd/clip.cpp` 在 tag `b11671` 含 `case PROJECTOR_TYPE_MINICPMV4_7`，LLM 主干 `qwen35` / `qwen35moe` 架构也已注册（4.7 支持 PR #29416 于 2026-10-10 合入主线）。
  - 同时准备了 CPU 版与 Vulkan 版两套二进制，便于对比。
- 模型（均通过官方 SHA256 校验）：

| 文件 | 大小 | 说明 |
|---|---|---|
| `MiniCPM-V-4_7-1B-Q4_K_M.gguf` | 641 MiB | 1B 语言主干 |
| `mmproj-model-f16.gguf`（1B 用） | 1.03 GiB | 视觉投影器 + ViT |
| `dspark-MiniCPM-V-4_7-1B-F16.gguf` | 418 MiB | DSpark 推测解码草稿 |
| `MiniCPM-V-4_7-35B-A3B-mix_q3gud_im.gguf` | **14.44 GiB** | 35B-A3B 最小官方量化（专家 Q3） |
| `mmproj-model-f16.gguf`（35B 用） | 1.04 GiB | 注意与 1B 的 mmproj **不是同一个文件** |
| `dspark-MiniCPM-V-4_7-35B-A3B-F16.gguf` | 753 MiB | 35B 的草稿模型 |

---

## 三、基准测试（llama-bench，8 线程，pp=预处理 / tg=生成）

| 模型 | 后端 | 配置 | pp (t/s) | **tg (t/s)** |
|---|---|---|---|---|
| 1B Q4_K_M | CPU | 8 线程 | 378.09 (pp128) | **27.12** (tg64) |
| 1B Q4_K_M | **Vulkan 780M** | `-ngl 99` | 1094.45 (pp128) | **56.26** (tg64) |
| 35B-A3B | CPU | 8 线程 | 48.54 (pp128) | **10.20** (tg64) |
| 35B-A3B | Vulkan 780M | `-ngl 8` | 51.90 (pp64) | 9.43 (tg32) |
| 35B-A3B | Vulkan 780M | `-ngl 99` | — | ❌ `vk::Queue::submit: ErrorOutOfDeviceMemory` |
| 35B-A3B | Vulkan 780M | `-ngl 99 --n-cpu-moe 99` | — | ❌ 同样分配失败 |

**结论 1：1B 走核显能翻倍**（27 → 56 t/s 纯生成）。
**结论 2：35B 部分卸载反而更慢**（9.43 < 10.20），全量卸载直接分配失败——核显名义上能寻址 19GB，但单次大分配被驱动的专用显存上限（6GB）挡住。**35B 就老老实实跑 CPU。**

---

## 四、真实多模态问答（同一张测试图，已关闭 thinking）

测试图内容包含 4 行文字（`MiniCPM-V 4.7 TEST` / `ORDER: X100PRO-2026` / `AMOUNT: 8848 CNY` / `vivo X100 Pro / Dimensity 9300`）与一个黄色圆形。问题：*读出图中所有文字，并说明圆形颜色。*

| 配置 | 生成速度 | 墙钟 | 结果 |
|---|---|---|---|
| 1B · CPU · 无草稿 | 29.87 t/s | 7.0s | ✅ 全部正确 |
| 1B · CPU · **+DSpark 草稿** | **36.52 t/s** | 6.7s | ✅ 全部正确（CPU 上草稿提速 +22%） |
| 1B · **核显(LLM)+CPU(视觉)** · 无草稿 | **44.65 t/s** | **5.5s** | ✅ 全部正确（**最快**） |
| 1B · 核显 + 草稿 | 33.57 t/s | 10.3s | ⚠️ 草稿在 GPU 上反而拖慢 |
| **35B-A3B · CPU · +草稿** | **11.36 t/s** | 23.1s | ⚠️ 文字基本正确，但把 `8848` 读成 `88.48`（最小量化的专家位宽偏低所致） |

补充观测：
- 视觉编码（ViT）在 CPU 上约需 4～5 秒，是每次带图提问的固定开销；把 mmproj 放 GPU 会直接让服务端崩溃（`ErrorOutOfDeviceMemory`），所以带图时**视觉塔必须留在 CPU**。
- 35B 服务端工作集实测 **14.08 GB**（私有 4.96GB + 文件映射），加载完成后常驻。
- 服务端冷启动（首次从磁盘读 14.44GB）明显慢于热启动；热启动 35B 约 11 秒、1B 约 4 秒。

---

## 五、给你的推荐配置

> 日常不用手敲这些命令：本目录已提供启动器 `scripts\minicpm.ps1`（或双击 `启动 MiniCPM.cmd`）。
> 下面保留原始命令，方便你理解启动器到底做了什么、或手动排查。

**1B（日常用这个，速度最好）**

```powershell
# 用 vulkan 版二进制；LLM 放核显，视觉塔留 CPU；不要加草稿模型
.\llamacpp-vulkan\llama-server.exe `
  -m  .\models\MiniCPM-V-4_7-1B-Q4_K_M.gguf `
  --mmproj .\models\mmproj-model-f16.gguf `
  --host 127.0.0.1 --port 8080 `
  -ngl 99 --no-mmproj-offload `
  -t 8 -c 4096 --jinja `
  --reasoning off `
  --log-file .\run\logs\manual.log --log-colors off
```
浏览器打开 <http://127.0.0.1:8080> 即可传图对话。**约 45～52 t/s，很流畅。**

**35B-A3B（想要更强能力时）** —— 权重已清理，需先从 README 重新下载

```powershell
# 必须用 cpu 版；加草稿模型能提速约 11%
.\llamacpp-cpu\llama-server.exe `
  -m  .\models\35B-A3B\MiniCPM-V-4_7-35B-A3B-mix_q3gud_im.gguf `
  --mmproj .\models\35B-A3B\mmproj-model-f16.gguf `
  -md .\models\35B-A3B\dspark-MiniCPM-V-4_7-35B-A3B-F16.gguf `
  --host 127.0.0.1 --port 8080 `
  -t 8 -c 4096 --jinja `
  --reasoning off
```
**约 10～11 t/s**，可用但偏慢；跑之前建议关掉浏览器/ChatGPT 等占内存的程序，给模型留出 15GB 以上可用内存。

**务必关掉 thinking**：这套模板默认输出 `<think>` 推理块，不关的话生成量翻倍，体感速度直接砍半（1B 实测：不关时 160 token 才说完一句话，关掉后 66 token 就说完了）。关闭方式用 `--reasoning off`；老写法 `--chat-template-kwargs '{"enable_thinking":false}'` 已被官方标记弃用（会打印警告），不建议再用。

---

## 六、几个需要你知道的实话

1. **`mix_q3gud_im` 是这个机器唯一实用的 35B 档位。** 官方 Q4_K_M 是 21.2GB，加上 mmproj 与运行时开销约 23GB，在 25.7GB 可见内存上会疯狂换页；最小量化 14.44GB 才跑得舒服。代价是精度——OCR 小字号数字会出错（实测把 8848 读成 88.48）。
2. **35B 的"3GB 内存上手机"是专用运行时的成果，不是通用推理栈能达到的。** 那是 LES（专家权重剥离到 SSD 流式加载）+ 细粒度分层量化 + DSpark 的组合，目前只在官方 iOS/Android App 里集成。桌面 llama.cpp 直接加载，专家权重必须常驻，实测就是 14GB。
3. **草稿模型不是万能的。** CPU 上提速 22%，核显上反而慢 22%——它和 GPU 批处理抢同一份带宽。所以 1B 用 GPU 时不要加 `-md`。
4. **长上下文要克制。** 模型支持 256K，但本机内存有限，`-c` 建议 4096；上下文越长 KV cache 越大，35B 会先撑爆内存。
