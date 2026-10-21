<#
.SYNOPSIS
  多连接分块下载器，用于从 Hugging Face / ModelScope 拉取大模型权重。

.DESCRIPTION
  按固定块大小把文件切成多个区间，用多个 curl 进程并行下载各区间到 .parts 目录，
  全部完成后按顺序拼接，最后校验文件大小与 SHA256。支持断点续传：已完整下载的区间会跳过。

.EXAMPLE
  ./download-parallel.ps1 -Url "https://huggingface.co/..." -OutFile "models/x.gguf" -Size 672003680 -Sha256 "588c..." -Concurrency 8
#>
param(
    [Parameter(Mandatory = $true)][string]$Url,
    [Parameter(Mandatory = $true)][string]$OutFile,
    [Parameter(Mandatory = $true)][int64]$Size,
    [string]$Sha256 = '',
    [int]$Concurrency = 8,
    [int]$ChunkMB = 256,
    [int]$MaxAttempts = 4
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Write-Log([string]$msg) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $msg)
}

$outFull = [System.IO.Path]::GetFullPath($OutFile)
$outDir = Split-Path -Parent $outFull
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }

# 已完成且校验通过则直接跳过
if (Test-Path $outFull) {
    $existing = (Get-Item $outFull).Length
    if ($existing -eq $Size) {
        if ([string]::IsNullOrEmpty($Sha256)) {
            Write-Log "SKIP (size ok): $outFull"
            return
        }
        $h = (Get-FileHash $outFull -Algorithm SHA256).Hash.ToLower()
        if ($h -eq $Sha256.ToLower()) { Write-Log "SKIP (sha256 ok): $outFull"; return }
        Write-Log "existing file sha256 mismatch, re-downloading: $outFull"
    }
    else {
        Write-Log ("existing partial file {0} bytes != {1}, re-downloading" -f $existing, $Size)
    }
    Remove-Item $outFull -Force
}

$partDir = "$outFull.parts"
if (-not (Test-Path $partDir)) { New-Item -ItemType Directory -Force -Path $partDir | Out-Null }

$chunkBytes = [int64]$ChunkMB * 1MB
$chunkCount = [int][Math]::Ceiling($Size / [double]$chunkBytes)

# 收集待下载区间
$pending = New-Object System.Collections.ArrayList
for ($i = 0; $i -lt $chunkCount; $i++) {
    $start = [int64]$i * $chunkBytes
    $end = [Math]::Min($start + $chunkBytes - 1, $Size - 1)
    $want = $end - $start + 1
    $part = Join-Path $partDir ("chunk_{0:D5}.bin" -f $i)
    $have = 0
    if (Test-Path $part) { $have = (Get-Item $part).Length }
    if ($have -eq $want) { continue }
    if ($have -ne 0) { Remove-Item $part -Force }
    [void]$pending.Add([pscustomobject]@{ Index = $i; Start = $start; End = $end; Want = $want; Part = $part })
}

$totalChunks = $chunkCount
Write-Log ("target {0} ({1:N2} GB), chunks={2}, pending={3}, concurrency={4}" -f (Split-Path -Leaf $outFull), ($Size / 1GB), $totalChunks, $pending.Count, $Concurrency)

$attempt = 0
while ($pending.Count -gt 0 -and $attempt -lt $MaxAttempts) {
    $attempt++
    Write-Log ("pass {0}: {1} chunk(s) remaining" -f $attempt, $pending.Count)
    $queue = @($pending)
    $pending = New-Object System.Collections.ArrayList

    for ($b = 0; $b -lt $queue.Count; $b += $Concurrency) {
        $batch = $queue[$b..([Math]::Min($b + $Concurrency - 1, $queue.Count - 1))]
        $procs = @()
        foreach ($c in $batch) {
            # 注意：Start-Process 不会自动给含空格的参数加引号，必须显式加双引号
            $errLog = "$($c.Part).err"
            $argStr = '-sS -L --fail --retry 2 --retry-delay 2 --max-time 3600 -r "{0}-{1}" -o "{2}" "{3}"' -f `
                $c.Start, $c.End, $c.Part, $Url
            $procs += Start-Process -FilePath 'curl.exe' -ArgumentList $argStr -PassThru `
                -WindowStyle Hidden -RedirectStandardError $errLog
        }
        $procs | ForEach-Object { $_.WaitForExit() }

        foreach ($c in $batch) {
            $ok = $false
            if (Test-Path $c.Part) {
                if ((Get-Item $c.Part).Length -eq $c.Want) { $ok = $true }
                else { Remove-Item $c.Part -Force -ErrorAction SilentlyContinue }
            }
            if (-not $ok) {
                $e = ''
                if (Test-Path "$($c.Part).err") { $e = (Get-Content "$($c.Part).err" -Raw).Trim() }
                if ($e) { Write-Log ("  chunk {0} failed: {1}" -f $c.Index, $e) }
                [void]$pending.Add($c)
            }
        }
        $doneCount = $totalChunks - $pending.Count
        Write-Log ("  progress {0}/{1} chunks" -f $doneCount, $totalChunks)
    }
}

if ($pending.Count -gt 0) {
    throw ("download incomplete after {0} passes: {1} chunk(s) still missing" -f $MaxAttempts, $pending.Count)
}

Write-Log "all chunks complete, concatenating..."
$fs = [System.IO.File]::Open($outFull, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write)
try {
    $buf = New-Object byte[] (1MB)
    for ($i = 0; $i -lt $chunkCount; $i++) {
        $part = Join-Path $partDir ("chunk_{0:D5}.bin" -f $i)
        $in = [System.IO.File]::OpenRead($part)
        try {
            while (($n = $in.Read($buf, 0, $buf.Length)) -gt 0) { $fs.Write($buf, 0, $n) }
        }
        finally { $in.Dispose() }
    }
}
finally { $fs.Dispose() }

$finalSize = (Get-Item $outFull).Length
if ($finalSize -ne $Size) { throw ("size mismatch: got {0}, expected {1}" -f $finalSize, $Size) }

if (-not [string]::IsNullOrEmpty($Sha256)) {
    Write-Log "verifying sha256..."
    $h = (Get-FileHash $outFull -Algorithm SHA256).Hash.ToLower()
    if ($h -ne $Sha256.ToLower()) { throw ("sha256 mismatch: got {0}, expected {1}" -f $h, $Sha256) }
    Write-Log "sha256 OK"
}

Remove-Item $partDir -Recurse -Force -ErrorAction SilentlyContinue
Write-Log ("DONE {0} ({1:N2} GB)" -f $outFull, ($finalSize / 1GB))
