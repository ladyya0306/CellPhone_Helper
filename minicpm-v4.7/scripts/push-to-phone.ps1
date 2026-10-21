<#
.SYNOPSIS
  把 Termux、模型文件和手机端脚本一次性推到 vivo 手机上。

.DESCRIPTION
  在电脑上运行。会依次：检查 adb 与设备连接 → 安装 Termux（可选）→ 建立
  /sdcard/Download/minicpm 目录 → 推送 3 个模型文件与 termux-setup.sh →
  逐个回读字节数确认传输完整。

.EXAMPLE
  .\push-to-phone.ps1              # 全流程
  .\push-to-phone.ps1 -SkipInstall # 已经装好 Termux，只推文件
#>
param(
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $root 'tools\platform-tools\adb.exe'
$apkDir = Join-Path $root 'apk'
$modelDir = Join-Path $root 'models'
$remoteDir = '/sdcard/Download/minicpm'

function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Ok($msg) { Write-Host "  OK  $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "  !!  $msg" -ForegroundColor Yellow }

if (-not (Test-Path $adb)) { throw "找不到 adb：$adb" }

Step '检查手机连接'
$devicesRaw = & $adb devices
$devicesRaw | ForEach-Object { Write-Host "  $_" }
$devices = $devicesRaw | Select-Object -Skip 1 | Where-Object { $_ -match '\t(device|unauthorized|offline)(\s|$)' }
if ($devices.Count -eq 0) {
    throw @"
没有检测到设备。请依次确认：
  1. 手机已用数据线连接电脑，USB 模式选"传输文件 / MTP"
  2. 手机：设置 → 关于手机 → 连点"版本号"7 次，开启开发者模式
  3. 手机：设置 → 系统 → 开发者选项 → 打开"USB 调试"
  4. 手机上会弹出"允许 USB 调试吗？"，勾选"一律允许"后点确定
然后重新运行本脚本。
"@
}
if ($devices -match 'unauthorized') { throw '手机还没授权调试。请在手机弹窗中点「允许」。若没弹窗，拔插数据线重试。' }
if ($devices.Count -gt 1) { Warn "检测到多台设备，将使用第一台" }

Step '安装 Termux（已装则跳过）'
$termuxApk = Get-ChildItem $apkDir -Filter 'termux-app*.apk' | Select-Object -First 1
$installed = ((& $adb shell "pm list packages com.termux") -join '') -match 'package:com\.termux'
if ($installed) {
    Ok '手机上已经有 Termux，跳过安装'
}
elseif (-not $termuxApk) { Warn "apk 目录下没有 Termux 安装包，跳过" }
elseif ($SkipInstall) { Warn "已指定 -SkipInstall，跳过" }
else {
    Write-Host "  安装 $($termuxApk.Name) ..."
    Write-Host '  （如果手机弹出"是否安装"，请在手机上点允许）'
    $out = & $adb install -r $termuxApk.FullName 2>&1
    $out | ForEach-Object { Write-Host "  $_" }
    if ($out -match 'Success') { Ok 'Termux 安装成功' }
    elseif ($out -match 'ALREADY_EXISTS') { Warn '已安装过 Termux，跳过（签名不同的话需先卸载旧版）' }
    else { Warn "安装未完成：$out  —— 可在手机上手动点击确认后重跑本脚本" }
}

Step '创建手机目录'
& $adb shell "mkdir -p $remoteDir" | Out-Null
Ok $remoteDir

Step '推送模型文件（约 2.1GB，视数据线速度需几分钟）'
$files = @(
    'MiniCPM-V-4_7-1B-Q4_K_M.gguf',
    'mmproj-model-f16.gguf',
    'dspark-MiniCPM-V-4_7-1B-F16.gguf'
)
foreach ($f in $files) {
    $local = Join-Path $modelDir $f
    if (-not (Test-Path $local)) { Warn "本地缺少 $f，跳过"; continue }
    $localSize = (Get-Item $local).Length

    # 手机上已有且大小一致就跳过，重跑这个脚本不会重复传 2GB
    $remoteSize = (& $adb shell "stat -c %s '$remoteDir/$f' 2>/dev/null") -join '' -replace '\s', ''
    if ($remoteSize -eq "$localSize") { Ok "$f 手机上已存在且大小一致，跳过"; continue }

    Write-Host ("  推送 {0} ({1:N1} MB) ..." -f $f, ($localSize / 1MB))
    & $adb push $local "$remoteDir/$f" | ForEach-Object { Write-Host "    $_" }
    $remoteSize = (& $adb shell "stat -c %s '$remoteDir/$f'") -join '' -replace '\s', ''
    if ($remoteSize -eq "$localSize") { Ok "$f 字节数一致（$remoteSize）" }
    else { Warn "$f 大小不一致！本地 $localSize / 手机 $remoteSize —— 请重新推送" }
}

Step '推送手机端脚本'
$sh = Join-Path $root 'scripts\termux-setup.sh'
& $adb push $sh "$remoteDir/termux-setup.sh" | ForEach-Object { Write-Host "    $_" }
Ok 'termux-setup.sh 已推送'

Step '完成，接下来在手机上操作'
@"
  1. 打开 Termux（首次启动要等它初始化几秒）
  2. 长按屏幕 → Paste，粘贴这一行后回车，允许存储权限：
         termux-setup-storage
  3. 粘贴并依次执行：
         cp ~/storage/downloads/minicpm/termux-setup.sh ~/
         bash ~/termux-setup.sh setup
         bash ~/termux-setup.sh build
         bash ~/termux-setup.sh serve
  4. 手机浏览器打开  http://127.0.0.1:8080

  详细说明见 docs/Termux-新手指南.md
"@ | Write-Host
