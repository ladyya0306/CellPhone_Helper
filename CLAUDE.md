# CellPhone Helper — 项目说明（供本地 Claude Code 会话读取）

## 项目是什么
离线私人智能助手 APK："共同成长的小秘书 + 可以互动的玩伴"。Kotlin + llama.cpp(JNI)，默认模型 Qwen3.5-4B Q4_K_M（`files/models/qwen35-4b.gguf`，2.61GB；旧 Qwen3-VL-4B `model.gguf` 仍在手机，文件缺失时才回退），目标机 vivo X100 Pro。
主工程：`minicpm-v4.7\android`（app 模块 `com.example.llama` 命名空间，applicationId `com.minicpm.assistant`）。

## 必读文档（按顺序）
0. `minicpm-v4.7\docs\项目状态-2026-10-21.md` —— **当前事实、证据、技术债（先读这份）**
1. `minicpm-v4.7\docs\实施计划-2026-10-21.md` —— 阶段、门禁、下一步；动工前先看范围
2. `minicpm-v4.7\docs\会话交接-2026-10-18.md` —— 历史追加日志（§1–§28），查细节与踩坑
3. `minicpm-v4.7\docs\通讯录与群聊朋友圈方案-2026-10-21.md` —— 通讯录方向的原始方案
4. `minicpm-v4.7\docs\通讯录2.0-计划-2026-10-21.md` —— 已批准的新计划（14 人设 / 小传关系 / 群聊 / 朋友圈 / 编排层，A–D 阶段）
5. `minicpm-v4.7\docs\群聊-PC拟真测试-2026-10-21.md` —— 群聊 PC 测试报告（机制通过、拟真未过）
6. `minicpm-v4.7\docs\产品方案-v1.0.md` —— 产品定位、缺陷总表、路线（阶段 A–F）
7. `minicpm-v4.7\docs\架构诊断与重构方案-2026-10-18.md` —— 根因、架构不变量、验收清单 §3.4
8. `minicpm-v4.7\docs\APK工程方案.md` —— M1–M7 里程碑记录

## 构建 / 部署 / 测试
- 环境：`. minicpm-v4.7\android\env-build.ps1`；另设 `$env:ANDROID_USER_HOME = "D:\MiniProj\CellPhone Helper\android-toolchain\android-user-home"`（沿用同一个 debug 密钥，`adb install -r` 才能覆盖安装）
- 一键部署：`minicpm-v4.7\scripts\deploy-apk.ps1`（备份手机数据 → assembleDebug → install -r → 启动 → 截图），日志 `run\deploy.log`
- 一键端到端测试：`minicpm-v4.7\scripts\e2e-test.ps1`（部署后触发调试包内的 `TestHookReceiver`，结果 `run\e2e-result.txt`）
- 回归语料：`AssistantSelfTest.kt`（纯 Kotlin，**1088 条**）；电脑端 `scripts\selftest-jvm.ps1`，手机上「设置 → 动作与提醒 → 运行自检」
- 人设文本快照：`scripts\persona-golden.ps1`（旧人设文本不得变化，只允许新增人设带来的新增行）
- PC 群聊拟真：`python scripts\group-sim.py --all`（先 `dump-prompts.ps1` 导出 14 份真实系统提示；结果 `run\eval\group-sim-*.md`；未过拟真门禁不上手机）
- adb：`android-toolchain\sdk\platform-tools\adb.exe`
- 版本控制：2026-10-21 起仓库根目录有 git 基线；改文件前先看 `git status`，每阶段一个提交

## 硬性规则
1. **绝不卸载应用**：模型在应用私有目录 `files/models/`（`qwen35-4b.gguf` 2.61GB + 旧 `model.gguf` 2.4GB），卸载即丢失；一律 `install -r`
2. 改代码前后都跑回归语料；每个新 bug 先把触发原话加进 `AssistantSelfTest`，再修
3. "完成"必须附证据（编译输出、自检结果、e2e 记录、截图），不能只凭自述
4. 架构不变量："程序说事实，模型说情绪"——用户看到的"做了什么"只来自动作账本回执；模型输出必须经过 `ClaimGuard`
5. vivo ROM 会吞掉 Java 的 `Log.*`：诊断信息一律写文件（`files/boot.log`、`hb_log.txt`、`crash.log`），用 `adb exec-out run-as com.minicpm.assistant cat <path>` 读取
6. adb `input text` 不支持中文：中文测试用例写进 `TestHook.kt`
7. 文档用简体中文书面语、Markdown 格式；方案成熟前不做 Word
8. 动工前先读《实施计划》的「进入条件 / 验证 / 不偏离清单」；不在当前阶段范围内的事先记录、不顺手做
