<#
.SYNOPSIS
  全自动部署 MiniCPM-V 4.7 到手机：手机上完全不用敲任何命令。

.DESCRIPTION
  利用 adb 的 run-as 能力，以 Termux 应用身份直接驱动手机：
    搬模型 → 换国内源 → 装编译工具 → 传源码 → 编译 → 建快捷入口
  长任务（装包、编译）在手机后台运行，本脚本轮询日志显示进度，adb 断开也不影响。

.EXAMPLE
  .\auto-setup-phone.ps1              # 全流程
  .\auto-setup-phone.ps1 -Action status   # 看进度
  .\auto-setup-phone.ps1 -Action build    # 只做装包+编译
#>
[CmdletBinding()]
param(
    [ValidateSet('all', 'transfer', 'build', 'shortcuts', 'status')]
    [string]$Action = 'all',

    [string]$Mirror = 'https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main',
    [int]$Jobs = 8,
    [switch]$NoWait
)

$ErrorActionPreference = 'Stop'

$Root    = Split-Path -Parent $PSScriptRoot
$Adb     = Join-Path $Root 'tools\platform-tools\adb.exe'
$TmpDir  = Join-Path $Root 'run\tmp'
$SrcDir  = '/sdcard/Download/minicpm'
$TH      = '/data/data/com.termux/files'          # Termux 数据根
$THome    = "$TH/home"
$TPrefix  = "$TH/usr"
$ModelDst = "$THome/models/minicpm-v4.7"

# 必须以工作区临时目录为 TMP，否则 adb 写不了自己的日志
New-Item -ItemType Directory -Force -Path $TmpDir | Out-Null
$env:TMP = $TmpDir; $env:TEMP = $TmpDir

function Head($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Ok($t)   { Write-Host "  OK   $t" -ForegroundColor Green }
function Warn2($t){ Write-Host "  !!   $t" -ForegroundColor Yellow }
function Fail($t) { Write-Host "  XX   $t" -ForegroundColor Red }

# Termux 运行所需的环境变量：直接 spawn bash 时 Termux 不会自动带上
$EnvPreamble = @"
export HOME=$THome
export PREFIX=$TPrefix
export PATH=`$PREFIX/bin:`$PREFIX/bin/applets
export LD_LIBRARY_PATH=`$PREFIX/lib
export TMPDIR=`$PREFIX/tmp
export TERM=xterm-256color
export LANG=en_US.UTF-8
export LD_PRELOAD=`$PREFIX/lib/libtermux-exec.so
export DEBIAN_FRONTEND=noninteractive
cd `$HOME
"@

function Assert-Device {
    $raw = & $Adb devices
    $dev = $raw | Select-Object -Skip 1 | Where-Object { $_ -match '\t(device|unauthorized)(\s|$)' }
    if (-not $dev) { throw '没有检测到手机。请确认已开 USB 调试并授权（手机弹窗点「允许」）。' }
    if ($dev -match 'unauthorized') { throw '手机未授权调试，请在手机弹窗点「允许」。' }
    $r = & $Adb shell "run-as com.termux echo RUNAS_OK 2>&1"
    if (($r -join '') -notmatch 'RUNAS_OK') {
        throw '无法以 Termux 身份执行命令（run-as 被系统拒绝）。请先手动打开一次 Termux，再重跑本脚本。'
    }
    return $true
}

# 同步执行一段 Termux 脚本，返回输出
function Invoke-Termux {
    param([string]$Script)
    # 统一成 LF：Windows 的 CRLF 会让 shell 脚本报 "not found" 之类的怪错
    $Script = $Script -replace "`r`n", "`n"
    $full = $EnvPreamble + "`n" + $Script
    return (& $Adb shell "run-as com.termux sh -c '$full'" 2>&1)
}

# 把内容写成 Termux 里的文件。走 base64：既避开引号地狱，又避免 CRLF 污染
function Write-TermuxFile {
    param([string]$RemotePath, [string]$Content)
    $Content = $Content -replace "`r`n", "`n"
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Content))
    & $Adb shell "run-as com.termux sh -c 'echo $b64 | base64 -d > $RemotePath'" | Out-Null
}

# 后台跑一个脚本并留下完成标记，返回后可用 Get-JobLog 轮询
function Start-TermuxJob {
    param([string]$Name, [string]$Script)
    Invoke-Termux "mkdir -p `$HOME/.auto" | Out-Null
    $wrapped = $EnvPreamble + "`n" + $Script + "`necho `$? > `$HOME/.auto/$Name.exit`n"
    Write-TermuxFile -RemotePath "$THome/.auto/$Name.sh" -Content $wrapped
    Invoke-Termux "rm -f `$HOME/.auto/$Name.log `$HOME/.auto/$Name.exit" | Out-Null
    Invoke-Termux "nohup sh `$HOME/.auto/$Name.sh > `$HOME/.auto/$Name.log 2>&1 &" | Out-Null
    Ok "$Name 已在手机后台启动"
}

function Get-TermuxFile {
    param([string]$RemotePath)
    return (& $Adb shell "run-as com.termux cat $RemotePath 2>/dev/null" 2>&1)
}

function Get-RemoteSize {
    param([string]$RemotePath)
    $s = (& $Adb shell "run-as com.termux stat -c %s $RemotePath 2>/dev/null") -join '' -replace '\s', ''
    if ($s -match '^\d+$') { return [int64]$s }
    return -1
}

function Wait-TermuxJob {
    param([string]$Name, [int]$TimeoutSec, [string]$Label)
    $seen = 0
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
        $lines = Get-TermuxFile "$THome/.auto/$Name.log"
        if ($lines.Count -gt $seen) {
            $lines | Select-Object -Skip $seen | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
            $seen = $lines.Count
        }
        $exit = (Get-TermuxFile "$THome/.auto/$Name.exit") -join ''
        if ($exit -match '^\s*\d+\s*$') {
            $code = [int]$exit.Trim()
            $left = Get-TermuxFile "$THome/.auto/$Name.log"
            if ($left.Count -gt $seen) { $left | Select-Object -Skip $seen | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray } }
            if ($code -eq 0) { Ok "$Label 完成（$([int]$sw.Elapsed.TotalMinutes) 分 $($sw.Elapsed.Seconds) 秒）" }
            else { Fail "$Label 失败（退出码 $code），日志见上面" }
            return $code
        }
        Start-Sleep -Seconds 10
    }
    Warn2 "$Label 超过 $([int]($TimeoutSec/60)) 分钟仍未结束，它在手机后台继续跑。随时执行 -Action status 看进度。"
    return -1
}

# ---------------------------------------------------------------- 1) 搬模型
function Step-Transfer {
    Head '把模型搬进 Termux 私有目录（设备内部复制，不走 USB）'
    Invoke-Termux "mkdir -p `$HOME/models/minicpm-v4.7" | Out-Null
    $files = @(
        @{ N = 'MiniCPM-V-4_7-1B-Q4_K_M.gguf'; S = 672003680 },
        @{ N = 'mmproj-model-f16.gguf';         S = 1110117984 },
        @{ N = 'dspark-MiniCPM-V-4_7-1B-F16.gguf'; S = 438065792 }
    )
    foreach ($f in $files) {
        $have = Get-RemoteSize "$ModelDst/$($f.N)"
        if ($have -eq $f.S) { Ok "$($f.N) 已就位，跳过"; continue }
        $src = (& $Adb shell "test -f $SrcDir/$($f.N) && echo yes") -join ''
        if ($src -notmatch 'yes') { Fail "$($f.N) 不在手机 $SrcDir，请先运行 推送模型到手机.cmd"; continue }
        Write-Host ("  复制 {0} ({1:N0} MB) ..." -f $f.N, ($f.S / 1MB))
        # shell 身份读 /sdcard，管道给 app 身份写入私有目录 —— 数据不出手机
        & $Adb shell "cat $SrcDir/$($f.N) | run-as com.termux sh -c 'cat > $ModelDst/$($f.N)'" | Out-Null
        $now = Get-RemoteSize "$ModelDst/$($f.N)"
        if ($now -eq $f.S) { Ok "$($f.N) 字节数一致（$now）" } else { Fail "$($f.N) 大小不符：$now / $($f.S)" }
    }
    # 顺手把手机端脚本也搬过去
    Write-Host '  复制 termux-setup.sh ...'
    & $Adb shell "cat $SrcDir/termux-setup.sh | run-as com.termux sh -c 'cat > $THome/termux-setup.sh'" | Out-Null
    Ok 'termux-setup.sh 已就位'
}

# ---------------------------------------------------------------- 2) 换源 + 装工具 + 源码
function Step-Build {
    Head '换国内软件源（手机上第一个交互式步骤，这里直接替你写好）'
    $sources = "deb $Mirror stable main"
    Write-TermuxFile -RemotePath "$TPrefix/etc/apt/sources.list" -Content $sources
    Ok "sources.list -> $Mirror"

    Head '准备 llama.cpp 源码'
    # 电脑下载源码包（电脑这边 GitHub 通畅且快），再送进手机，避免依赖手机的网络
    $tarball = Join-Path $TmpDir 'llama.cpp-master.tar.gz'
    if (-not (Test-Path $tarball) -or (Get-Item $tarball).Length -lt 1MB) {
        Write-Host '  电脑下载源码包 ...'
        curl.exe -sS -L --fail --retry 3 -o $tarball 'https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/heads/master'
    }
    Ok ("源码包 {0:N1} MB" -f ((Get-Item $tarball).Length / 1MB))
    & $Adb push $tarball "$SrcDir/llama.cpp.tar.gz" | Out-Null
    & $Adb shell "cat $SrcDir/llama.cpp.tar.gz | run-as com.termux sh -c 'cat > $THome/llama.cpp.tar.gz'" | Out-Null
    Ok '源码包已送进手机'

    Head '在手机后台安装编译工具并编译（预计 20～40 分钟，手机会发热）'
    $script = @'
set -e
echo "== apt 更新软件列表 =="
apt-get update -y
echo "== 同步升级已装包（关键：否则新装的 cmake 会和旧 libc++ 链接不上）=="
apt-get -o Dpkg::Options::=--force-confnew full-upgrade -y
echo "== 安装编译工具 =="
apt-get install -y -o Dpkg::Options::=--force-confnew git cmake clang make
echo "== 自检 cmake 能否运行 =="
cmake --version | head -1
echo "== 解包源码 =="
rm -rf $HOME/llama.cpp
mkdir -p $HOME/llama.cpp
tar -xzf $HOME/llama.cpp.tar.gz -C $HOME/llama.cpp --strip-components=1
echo "== 配置 =="
cd $HOME/llama.cpp
rm -rf build
# 关键：显式指定 ARM 指令集，把 fp16 向量运算带上。
# 用 GGML_NATIVE=ON 时是否启用 fp16 取决于 -mcpu=native 的推断，ggml 只显式探测
# dotprod/i8mm；而视觉编码器（mmproj，fp16）对 FP16_VA 极其敏感 —— 不开会慢几十倍。
cmake -B build -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF -DGGML_OPENMP=OFF \
  -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16+i8mm
echo "== 编译 =="
cmake --build build -j8 --target llama-mtmd-cli llama-server
echo "== 校验产物 =="
ls -l build/bin/llama-mtmd-cli build/bin/llama-server
echo "BUILD_ALL_DONE"
'@
    Start-TermuxJob -Name 'build' -Script $script
    if (-not $NoWait) {
        $rc = Wait-TermuxJob -Name 'build' -TimeoutSec 3600 -Label '编译'
        if ($rc -eq 0) { Step-Verify }
    }
}

function Step-Verify {
    Head '验证手机端就绪状态'
    $out = Invoke-Termux @'
for f in MiniCPM-V-4_7-1B-Q4_K_M.gguf mmproj-model-f16.gguf dspark-MiniCPM-V-4_7-1B-F16.gguf; do
  if [ -f "$HOME/models/minicpm-v4.7/$f" ]; then echo "  OK   $f  $(stat -c %s $HOME/models/minicpm-v4.7/$f)"; else echo "  缺失 $f"; fi
done
for b in llama-mtmd-cli llama-server; do
  if [ -x "$HOME/llama.cpp/build/bin/$b" ]; then echo "  OK   $b"; else echo "  缺失 $b"; fi
done
'@
    $out | ForEach-Object { Write-Host $_ }
}

# ---------------------------------------------------------------- 3) 免打字启动入口
function Step-Shortcuts {
    Head '部署自制网页界面（源码编译的 llama-server 不含官方 WebUI，根路径会 404）'
    $webui = Join-Path $Root 'webui\index.html'
    if (Test-Path $webui) {
        & $Adb push $webui "$SrcDir/index.html" | Out-Null
        Invoke-Termux "mkdir -p `$HOME/webui" | Out-Null
        & $Adb shell "cat $SrcDir/index.html | run-as com.termux sh -c 'cat > $THome/webui/index.html'" | Out-Null
        $sz = Get-RemoteSize "$THome/webui/index.html"
        if ($sz -gt 1000) { Ok "webui/index.html 已部署（$sz 字节）" } else { Warn2 'webui 部署异常' }
    } else { Warn2 "找不到 $webui，跳过" }

    Head '建立免打字的启动入口'
    Invoke-Termux "mkdir -p `$HOME/.shortcuts" | Out-Null

    $widgets = @{
        '启动MiniCPM.sh' = @'
#!/data/data/com.termux/files/usr/bin/bash
echo "=== 启动 MiniCPM-V 4.7 ==="
if pgrep -f llama-server > /dev/null; then
  echo "  服务已经在运行，无需重复启动"
  echo "  浏览器打开： http://127.0.0.1:8080"
  exit 0
fi
exec bash $HOME/termux-setup.sh serve
'@
        '查看状态.sh' = @'
#!/data/data/com.termux/files/usr/bin/bash
echo "=== MiniCPM-V 4.7 状态 ==="
if pgrep -f llama-server > /dev/null; then
  PID=$(pgrep -f llama-server | head -1)
  echo "  服务：正在运行 (PID $PID)"
  MEM=$(awk '/VmRSS/{print int($2/1024)}' /proc/$PID/status 2>/dev/null)
  echo "  内存：${MEM} MB"
  if curl -s -m 3 http://127.0.0.1:8080/health | grep -q '"ok"'; then
    echo "  接口：正常"
    echo
    echo "  在浏览器打开： http://127.0.0.1:8080"
  else
    echo "  接口：无响应（可能还在加载模型，稍等再试）"
  fi
else
  echo "  服务：未运行"
  echo
  echo "  点桌面的「启动MiniCPM」即可启动"
fi
echo
echo "（本窗口可以直接关掉）"
'@
        '停止MiniCPM.sh' = @'
#!/data/data/com.termux/files/usr/bin/bash
echo "=== 停止 MiniCPM-V 4.7 服务 ==="
if pgrep -f llama-server > /dev/null; then
  pkill -f llama-server
  sleep 2
  if pgrep -f llama-server > /dev/null; then echo "  停止失败，请重试"; else echo "  已停止"; fi
else
  echo "  服务本来就没有在运行"
fi
echo
echo "（本窗口可以直接关掉）"
'@
    }
    foreach ($name in $widgets.Keys) {
        Write-TermuxFile -RemotePath "$THome/.shortcuts/$name" -Content $widgets[$name]
        Invoke-Termux "chmod +x `$HOME/.shortcuts/`"$name`"" | Out-Null
    }
    Ok '已建立桌面入口：启动MiniCPM / 查看状态 / 停止MiniCPM（配合 Termux:Widget）'

    # 关键：vivo 会拦小组件与开机自启，所以把「打开 Termux 就自动启动」写进 shell 启动文件。
    # 用绝对路径，因为非登录 shell / run-as 环境下 $HOME 可能不是 Termux 家目录。
    $autoStart = @'
# >>> MiniCPM-V 4.7 auto-start >>>
MC_HOME=/data/data/com.termux/files/home
if [ -x "$MC_HOME/llama.cpp/build/bin/llama-server" ] && ! pgrep -x llama-server > /dev/null 2>&1; then
  mkdir -p "$MC_HOME/.auto"
  ( nohup bash "$MC_HOME/termux-setup.sh" serve > "$MC_HOME/.auto/serve.log" 2>&1 & )
  echo ""
  echo "  MiniCPM-V 4.7 正在后台启动，约 10 秒后浏览器打开 http://127.0.0.1:8080"
  echo ""
fi
# <<< MiniCPM-V 4.7 auto-start <<<
'@
    foreach ($rc in @('.bashrc', '.profile', '.bash_profile')) {
        $cur = (Get-TermuxFile "$THome/$rc") -join "`n"
        $cur = $cur -replace '(?s)# >>> MiniCPM-V 4\.7 .*?# <<< MiniCPM-V 4\.7 .*?<<<', ''
        Write-TermuxFile -RemotePath "$THome/$rc" -Content ($cur.TrimEnd() + "`n`n" + $autoStart)
    }
    Ok '已写入「打开 Termux 自动启动服务」逻辑（.bashrc / .profile / .bash_profile）'

    # Termux:Boot 用的开机自启脚本
    $boot = @'
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
sleep 20
exec bash $HOME/termux-setup.sh serve
'@
    Invoke-Termux "mkdir -p `$HOME/.termux/boot" | Out-Null
    Write-TermuxFile -RemotePath "$THome/.termux/boot/start-minicpm.sh" -Content $boot
    Invoke-Termux "chmod +x `$HOME/.termux/boot/start-minicpm.sh" | Out-Null
    Ok '已建立 ~/.termux/boot/start-minicpm.sh（配合 Termux:Boot 可开机自动起服务）'
}

function Step-Status {
    Head '手机端进度'
    $out = Invoke-Termux @'
echo "--- 模型 ---"
ls -l $HOME/models/minicpm-v4.7/ 2>/dev/null | tail -4
echo "--- 编译产物 ---"
ls -l $HOME/llama.cpp/build/bin/llama-mtmd-cli $HOME/llama.cpp/build/bin/llama-server 2>/dev/null || echo "  还没有编译产物"
echo "--- 后台任务 ---"
if [ -f $HOME/.auto/build.exit ]; then echo "  build 退出码: $(cat $HOME/.auto/build.exit)"; else echo "  build 仍在运行或未启动"; fi
echo "--- 日志尾部 ---"
tail -6 $HOME/.auto/build.log 2>/dev/null || echo "  暂无日志"
'@
    $out | ForEach-Object { Write-Host $_ }
}

# ---------------------------------------------------------------- 主流程
Assert-Device | Out-Null
$serial = ((& $Adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice' } | Select-Object -First 1)
if ($serial) { $serial = ($serial -split '\s+')[0] }
Ok "已连接手机：$serial"

switch ($Action) {
    'all'       { Step-Transfer; Step-Shortcuts; Step-Build }
    'transfer'  { Step-Transfer }
    'build'     { Step-Build; if ($NoWait) { Step-Status } }
    'shortcuts' { Step-Shortcuts }
    'status'    { Step-Status }
}
