<#
.SYNOPSIS
  MiniCPM-V 4.7 本地服务启动器（Windows）。

.DESCRIPTION
  把本机跑 MiniCPM-V 4.7 这件事固定成一条标准命令，而不是每次手敲一长串参数。
  负责：检查模型文件 → 挑可用端口 → 后台拉起 llama-server → 等待就绪 → 打印访问地址；
  并提供停止、重启、看状态、看日志、发图自测、开机自启的开关。

.EXAMPLE
  .\minicpm.ps1 start                  # 默认：1B 走核显，端口 8080
  .\minicpm.ps1 start -Model 35B       # 用 35B（需先下载权重）
  .\minicpm.ps1 start -Backend cpu     # 强制纯 CPU（核显异常时用）
  .\minicpm.ps1 start -Lan             # 允许手机/局域网访问
  .\minicpm.ps1 status / stop / restart / logs / test
  .\minicpm.ps1 autostart-install      # 开机自动启动
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'restart', 'status', 'logs', 'test', 'autostart-install', 'autostart-remove', 'help')]
    [string]$Action = 'start',

    [ValidateSet('1B', '35B')]
    [string]$Model = '1B',

    [ValidateSet('gpu', 'cpu')]
    [string]$Backend = 'gpu',

    [int]$Port = 8080,
    [int]$Threads = 8,
    [int]$Ctx = 4096,

    [switch]$Lan,
    [switch]$Verify,
    [switch]$NoDraft
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- 路径与常量
$Root      = Split-Path -Parent $PSScriptRoot
$RunDir    = Join-Path $Root 'run'
$LogDir    = Join-Path $RunDir 'logs'
$StateFile = Join-Path $RunDir 'server.json'
$BinGpu    = Join-Path $Root 'llamacpp-vulkan'
$BinCpu    = Join-Path $Root 'llamacpp-cpu'

$ModelSets = @{
    '1B' = @{
        Name    = 'MiniCPM-V 4.7 1B'
        Dir     = Join-Path $Root 'models'
        Gguf    = 'MiniCPM-V-4_7-1B-Q4_K_M.gguf'
        GgufSz  = 672003680
        GgufSha = '588c6c94c82f756b86dba35c42ed9ba9ec187d65824eb52f039c347df563e63d'
        Mmproj  = 'mmproj-model-f16.gguf'
        MmSz    = 1110117984
        MmSha   = 'fb9cc047a3cdad5317fd8433a4aeda2d22f00f02ca4bd93b34ad84165b25a900'
        Draft   = 'dspark-MiniCPM-V-4_7-1B-F16.gguf'
        DraftSz = 438065792
    }
    '35B' = @{
        Name    = 'MiniCPM-V 4.7 35B-A3B'
        Dir     = Join-Path $Root 'models\35B-A3B'
        Gguf    = 'MiniCPM-V-4_7-35B-A3B-mix_q3gud_im.gguf'
        GgufSz  = 15503944896
        GgufSha = '51e32201f0ddaf87dbb9689ceef9e3aa1a1543a1366706aa2abbf3aabb34df6b'
        Mmproj  = 'mmproj-model-f16.gguf'
        MmSz    = 1119575840
        MmSha   = 'bff3b688e14c300eca273092c37c50a0ddfcc2054e079af1a0480edeed5d20f1'
        Draft   = 'dspark-MiniCPM-V-4_7-35B-A3B-F16.gguf'
        DraftSz = 789442368
    }
}

# 1B 在核显上不要用草稿模型（实测更慢）；35B 纯 CPU 时加草稿能提速约 11%
function Use-Draft {
    param($Backend, $ModelName, [bool]$Suppressed)
    if ($Suppressed) { return $false }
    if ($ModelName -eq '35B' -and $Backend -eq 'cpu') { return $true }
    return $false
}

function Write-Head($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Write-Ok($t)   { Write-Host "  OK   $t" -ForegroundColor Green }
function Write-Warn2($t) { Write-Host "  !!   $t" -ForegroundColor Yellow }
function Write-Err2($t)  { Write-Host "  XX   $t" -ForegroundColor Red }

function Initialize-Dirs {
    foreach ($d in @($RunDir, $LogDir)) {
        if (-not (Test-Path $d)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
    }
}

function Get-State {
    if (Test-Path $StateFile) {
        try { return (Get-Content $StateFile -Raw | ConvertFrom-Json) } catch { return $null }
    }
    return $null
}

function Save-State($obj) {
    $obj | ConvertTo-Json -Depth 6 | Set-Content -Path $StateFile -Encoding UTF8
}

function Test-Running {
    param($State)
    if (-not $State) { return $false }
    $p = Get-Process -Id $State.Pid -ErrorAction SilentlyContinue
    if ($null -eq $p) { return $false }
    # 防止 PID 被系统复用：名字必须还是 llama-server
    return ($p.ProcessName -eq 'llama-server')
}

function Test-Serving {
    param($State)
    if (-not (Test-Running $State)) { return $false }
    try {
        $h = Invoke-RestMethod "http://127.0.0.1:$($State.Port)/health" -TimeoutSec 3
        return ($h.status -eq 'ok')
    }
    catch { return $false }
}

function Get-FreePort {
    param([int]$Start)
    for ($p = $Start; $p -lt ($Start + 20); $p++) {
        $busy = $false
        try { $busy = (Test-NetConnection -ComputerName '127.0.0.1' -Port $p -InformationLevel Quiet -WarningAction SilentlyContinue) } catch { $busy = $false }
        if (-not $busy) { return $p }
    }
    throw "从 $Start 起连续 20 个端口都被占用"
}

function Assert-ModelFiles {
    param($Set, [bool]$DoVerify)
    Write-Head '检查模型文件'
    $missing = @()
    $items = @(
        @{ F = $Set.Gguf;  Sz = $Set.GgufSz;  Sha = $Set.GgufSha;  Label = '语言模型' },
        @{ F = $Set.Mmproj; Sz = $Set.MmSz;   Sha = $Set.MmSha;    Label = '视觉投影器' }
    )
    if (Use-Draft -Backend $Backend -ModelName $Model -Suppressed:$NoDraft) {
        $items += @{ F = $Set.Draft; Sz = $Set.DraftSz; Sha = $null; Label = '草稿模型' }
    }

    foreach ($it in $items) {
        $path = Join-Path $Set.Dir $it.F
        if (-not (Test-Path $path)) { Write-Err2 "$($it.Label) 缺失：$it.F"; $missing += $it.F; continue }
        $len = (Get-Item $path).Length
        if ($len -ne $it.Sz) { Write-Err2 "$($it.Label) 大小不对：期望 $($it.Sz)，实际 $len"; $missing += $it.F; continue }
        if ($DoVerify -and $it.Sha) {
            $h = (Get-FileHash $path -Algorithm SHA256).Hash.ToLower()
            if ($h -ne $it.Sha) { Write-Err2 "$($it.Label) SHA256 校验失败：$it.F"; $missing += $it.F; continue }
            Write-Ok "$($it.Label) $($it.F)（大小 + SHA256 通过）"
        }
        else {
            Write-Ok ("{0} {1}（{2:N0} MB）" -f $it.Label, $it.F, ($len / 1MB))
        }
    }

    if ($missing.Count -gt 0) {
        Write-Host ''
        Write-Warn2 '缺失的权重可以这样重新下载（工作区里已存好下载器）：'
        Write-Host "  cd `"$Root`""
        Write-Host "  `$s = `".\scripts\download-parallel.ps1`""
        if ($Model -eq '1B') {
            $h = 'https://huggingface.co/openbmb/MiniCPM-V-4.7-1B-gguf/resolve/main'
            Write-Host "  & `$s -Url `"$h/MiniCPM-V-4_7-1B-Q4_K_M.gguf`" -OutFile `".\models\MiniCPM-V-4_7-1B-Q4_K_M.gguf`" -Size 672003680 -Sha256 $($Set.GgufSha)"
            Write-Host "  & `$s -Url `"$h/mmproj-model-f16.gguf`" -OutFile `".\models\mmproj-model-f16.gguf`" -Size 1110117984 -Sha256 $($Set.MmSha)"
        }
        else {
            Write-Host '  （35B 三个文件的完整下载命令见 README.md 对应小节）'
        }
        throw '模型文件不完整，已中止'
    }
}

function Get-ServerArgs {
    param($Set, [int]$UsePort, [string]$ServerLog)
    $exe = if ($Backend -eq 'gpu') { Join-Path $BinGpu 'llama-server.exe' } else { Join-Path $BinCpu 'llama-server.exe' }
    if (-not (Test-Path $exe)) { throw "找不到 llama-server：$exe" }

    # 注意：Start-Process 不会自动给含空格的参数加引号（本工作区路径含空格），
    # 所以这里手工拼带引号的参数串。
    $q = { param($s) '"' + $s + '"' }
    $parts = @()
    $parts += '-m';            $parts += (& $q (Join-Path $Set.Dir $Set.Gguf))
    $parts += '--mmproj';      $parts += (& $q (Join-Path $Set.Dir $Set.Mmproj))
    $parts += '--host';        $parts += $(if ($Lan) { '0.0.0.0' } else { '127.0.0.1' })
    $parts += '--port';        $parts += "$UsePort"
    $parts += '-c';            $parts += "$Ctx"
    $parts += '-t';            $parts += "$Threads"
    $parts += '--jinja'
    # 关闭 thinking：用新版参数（--chat-template-kwargs 传 enable_thinking 已被官方标记弃用）
    $parts += '--reasoning';   $parts += 'off'
    # 让服务自己写日志文件（重定向 stdout 会被块缓冲，看不到内容）
    $parts += '--log-file';    $parts += (& $q $ServerLog)
    $parts += '--log-colors';  $parts += 'off'
    if ($Backend -eq 'gpu') {
        # 实测：LLM 放核显提速明显；视觉塔放核显会分配失败，必须留在 CPU
        $parts += '-ngl'; $parts += '99'
        $parts += '--no-mmproj-offload'
    }
    if (Use-Draft -Backend $Backend -ModelName $Model -Suppressed:$NoDraft) {
        $parts += '-md'; $parts += (& $q (Join-Path $Set.Dir $Set.Draft))
    }
    return @{ Exe = $exe; ArgString = ($parts -join ' ') }
}

function Start-Server {
    Initialize-Dirs
    $state = Get-State
    if (Test-Running $state) {
        if (Test-Serving $state) {
            Write-Warn2 "已经在运行（PID $($state.Pid)，端口 $($state.Port)）"
            Write-Host "  地址：http://127.0.0.1:$($state.Port)"
            Write-Host '  想重启用：.\minicpm.ps1 restart'
            return
        }
        Write-Err2 "检测到残留的 llama-server 进程（PID $($state.Pid)）但端口 $($state.Port) 无响应"
        Write-Host '  先清理：.\minicpm.ps1 stop   然后再 start'
        throw '存在残留进程'
    }

    $set = $ModelSets[$Model]
    Write-Head "启动 $($set.Name)（后端：$(if ($Backend -eq 'gpu') { 'Radeon 780M 核显 + CPU 视觉塔' } else { '纯 CPU' })）"
    Assert-ModelFiles -Set $set -DoVerify:$Verify

    Write-Head '准备端口'
    $usePort = Get-FreePort -Start $Port
    if ($usePort -ne $Port) { Write-Warn2 "端口 $Port 被占用，改用 $usePort" } else { Write-Ok "端口 $usePort 可用" }

    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $srvLog = Join-Path $LogDir "server-$stamp.log"
    $outLog = Join-Path $LogDir "server-$stamp.out.log"
    $errLog = Join-Path $LogDir "server-$stamp.err.log"
    $conf = Get-ServerArgs -Set $set -UsePort $usePort -ServerLog $srvLog

    Write-Head '拉起服务'
    $proc = Start-Process -FilePath $conf.Exe -ArgumentList $conf.ArgString -PassThru -WindowStyle Hidden `
        -WorkingDirectory (Split-Path -Parent $conf.Exe) `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    Write-Ok "进程已启动（PID $($proc.Id)）"

    Write-Head '等待就绪'
    $ready = $false
    for ($i = 0; $i -lt 180; $i++) {
        if ($proc.HasExited) { break }
        try {
            $h = Invoke-RestMethod "http://127.0.0.1:$usePort/health" -TimeoutSec 3
            if ($h.status -eq 'ok') { $ready = $true; break }
        }
        catch { }
        Start-Sleep -Seconds 1
    }

    if (-not $ready) {
        Write-Err2 '服务没能正常就绪'
        foreach ($lf in @($srvLog, $errLog, $outLog)) {
            if ((Test-Path $lf) -and (Get-Item $lf).Length -gt 0) {
                Write-Host "    --- $(Split-Path -Leaf $lf) ---" -ForegroundColor DarkGray
                Get-Content $lf -Tail 15 | ForEach-Object { Write-Host "    $_" }
            }
        }
        if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
        throw '启动失败'
    }

    $bind = if ($Lan) { '0.0.0.0（局域网可访问）' } else { '127.0.0.1（仅本机）' }
    Save-State @{
        Pid       = $proc.Id
        Port      = $usePort
        Model     = $Model
        Backend   = $Backend
        Threads   = $Threads
        Ctx       = $Ctx
        Lan       = [bool]$Lan
        StartedAt = (Get-Date).ToString('s')
        LogFile   = $srvLog
        OutLog    = $outLog
        ErrLog    = $errLog
        Exe       = $conf.Exe
    }

    Write-Ok "就绪（用了 $i 秒）"
    Write-Host ''
    Write-Host "  本机浏览器打开：  http://127.0.0.1:$usePort" -ForegroundColor Green
    if ($Lan) {
        $ips = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
                Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' }).IPAddress
        if ($ips) { Write-Host "  手机上打开：      http://$($ips[0]):$usePort" -ForegroundColor Green }
        Write-Warn2 '局域网模式：服务没有 API key，同一 WiFi 下的设备都能访问，注意别在公共网络使用'
    }
    Write-Host "  绑定：$bind   上下文：$Ctx  线程：$Threads"
    Write-Host "  停止：.\minicpm.ps1 stop      状态：.\minicpm.ps1 status"
    Write-Host "  日志：$srvLog"
}

function Stop-Server {
    Initialize-Dirs
    $state = Get-State
    if (-not (Test-Running $state)) {
        Write-Warn2 '没有正在运行的服务'
        Remove-Item $StateFile -Force -ErrorAction SilentlyContinue
        return
    }
    Write-Head '停止服务'
    Stop-Process -Id $state.Pid -Force -ErrorAction SilentlyContinue
    for ($i = 0; $i -lt 20; $i++) {
        if (-not (Get-Process -Id $state.Pid -ErrorAction SilentlyContinue)) { break }
        Start-Sleep -Milliseconds 500
    }
    if (Get-Process -Id $state.Pid -ErrorAction SilentlyContinue) { Write-Err2 "PID $($state.Pid) 还没退出" }
    else { Write-Ok "已停止（PID $($state.Pid)）" }
    Remove-Item $StateFile -Force -ErrorAction SilentlyContinue
}

function Show-Status {
    $state = Get-State
    if (-not (Test-Running $state)) { Write-Warn2 '服务未运行'; return }
    $p = Get-Process -Id $state.Pid
    $up = (Get-Date) - $p.StartTime
    Write-Head "运行中：$($state.Model) / 后端 $($state.Backend)"
    Write-Host "  PID       : $($state.Pid)"
    Write-Host ("  已运行    : {0:N0} 分 {1:N0} 秒" -f $up.TotalMinutes, $up.Seconds)
    Write-Host ("  内存占用  : {0:N2} GB" -f ($p.WorkingSet64 / 1GB))
    Write-Host "  端口      : $($state.Port)"
    Write-Host "  地址      : http://127.0.0.1:$($state.Port)"
    try {
        $h = Invoke-RestMethod "http://127.0.0.1:$($state.Port)/health" -TimeoutSec 3
        Write-Ok "健康检查：$($h.status)"
    }
    catch { Write-Err2 '健康检查失败（进程在但端口无响应）' }
}

function Show-Logs {
    $state = Get-State
    if (-not $state) { Write-Warn2 '没有日志记录'; return }
    $shown = $false
    # --log-file 写的日志最完整，优先看它
    if ($state.LogFile -and (Test-Path $state.LogFile) -and (Get-Item $state.LogFile).Length -gt 0) {
        Write-Head "运行日志：$($state.LogFile)"
        Get-Content $state.LogFile -Tail 30
        $shown = $true
    }
    foreach ($item in @(@{ P = $state.ErrLog; T = '错误输出' }, @{ P = $state.OutLog; T = '标准输出' })) {
        if ($item.P -and (Test-Path $item.P) -and (Get-Item $item.P).Length -gt 0) {
            Write-Head "$($item.T)：$($item.P)"
            Get-Content $item.P -Tail 15
            $shown = $true
        }
    }
    if (-not $shown) { Write-Warn2 '日志还是空的（服务可能刚启动，或输出尚未刷新）' }
}

function Test-Server {
    $state = Get-State
    if (-not (Test-Running $state)) { throw '服务未运行，先执行 .\minicpm.ps1 start' }
    $img = Join-Path $Root 'test\demo-image.png'
    if (-not (Test-Path $img)) { throw "找不到测试图：$img" }

    Write-Head '发一张测试图给模型'
    $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($img))
    $body = @{
        messages = @(@{
            role    = 'user'
            content = @(
                @{ type = 'text'; text = '读出图中所有文字，并说明圆形颜色。' },
                @{ type = 'image_url'; image_url = @{ url = "data:image/png;base64,$b64" } }
            )
        })
        max_tokens  = 200
        temperature = 0.7
        seed        = 42
    } | ConvertTo-Json -Depth 8

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-RestMethod "http://127.0.0.1:$($state.Port)/v1/chat/completions" -Method Post `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 600
    $sw.Stop()

    Write-Host ''
    Write-Host $r.choices[0].message.content.Trim()
    Write-Host ''
    if ($r.timings) {
        Write-Ok ("生成速度 {0:N2} t/s，共 {1} token，耗时 {2:N1} 秒" -f `
            $r.timings.predicted_per_second, $r.timings.predicted_n, $sw.Elapsed.TotalSeconds)
    }
}

# ------------------------------------------------------------------ 开机自启
$StartupDir = [Environment]::GetFolderPath('Startup')
$AutoCmd    = Join-Path $StartupDir 'minicpm-server.cmd'

function Install-Autostart {
    # 注释保持纯 ASCII：.cmd 由 cmd.exe 按 OEM 代码页解析，写中文容易乱码
    $lines = @(
        '@echo off',
        'rem MiniCPM-V 4.7 local server - auto start at logon (no-op if already running)',
        'rem To remove: scripts\minicpm.ps1 autostart-remove',
        "powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSScriptRoot\minicpm.ps1`" start -Model $Model -Backend $Backend -Port $Port"
    )
    Set-Content -Path $AutoCmd -Value $lines -Encoding OEM
    Write-Ok "已安装开机自启：$AutoCmd"
    Write-Host "  当前配置：-Model $Model -Backend $Backend -Port $Port"
    Write-Host "  取消：.\minicpm.ps1 autostart-remove"
}

function Remove-Autostart {
    if (Test-Path $AutoCmd) { Remove-Item $AutoCmd -Force; Write-Ok "已取消开机自启：$AutoCmd" }
    else { Write-Warn2 '本来就没有设置开机自启' }
}

function Show-Help {
    @"
MiniCPM-V 4.7 本地服务启动器

  .\minicpm.ps1 start [-Model 1B|35B] [-Backend gpu|cpu] [-Port 8080]
                      [-Threads 8] [-Ctx 4096] [-Lan] [-Verify] [-NoDraft]
  .\minicpm.ps1 stop | restart | status | logs | test
  .\minicpm.ps1 autostart-install | autostart-remove

说明
  start    检查模型文件 → 挑端口 → 后台启动 → 等就绪 → 打印访问地址
  test     给正在运行的服务发一张测试图，验证视觉链路是否正常
  -Lan     绑定 0.0.0.0，手机/局域网可访问（默认只绑本机）
  -Verify  启动时做完整 SHA256 校验（默认只查文件大小）
  -NoDraft 不使用草稿模型（默认 35B+CPU 才启用草稿）

默认配置（实测最优）
  1B  ：llama.cpp Vulkan 版，LLM 放 Radeon 780M 核显，视觉塔留 CPU → 约 45 t/s
  35B ：llama.cpp CPU 版，8 线程 + DSpark 草稿 → 约 11 t/s
  两种都默认关闭 thinking
"@ | Write-Host
}

switch ($Action) {
    'start'            { Start-Server }
    'stop'             { Stop-Server }
    'restart'          { Stop-Server; Start-Sleep -Seconds 1; Start-Server }
    'status'           { Show-Status }
    'logs'             { Show-Logs }
    'test'             { Test-Server }
    'autostart-install' { Install-Autostart }
    'autostart-remove' { Remove-Autostart }
    'help'             { Show-Help }
}
