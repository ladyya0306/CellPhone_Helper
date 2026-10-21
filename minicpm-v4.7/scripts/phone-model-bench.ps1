# Measure the model that is CURRENTLY configured on the phone: load time, :llm memory, per-turn latency (e2e chat cases).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\phone-model-bench.ps1 -Label vl4b [-ModelFile qwen35-4b.gguf]
#   -ModelFile  : switch the app's model file name first (the file must already be in files/models/); omit to keep the current one.
# Result: run\phone-bench-<Label>.txt   (nothing on the phone is modified except the model_file setting)
# NOTE: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less files as ANSI). Chinese is matched with \u escapes:
#   \u5c31\u7eea = ready, \u5f00\u59cb\u52a0\u8f7d = start loading, \u5931\u8d25 = failed, \u56de\u7b54 = answer
param([Parameter(Mandatory = $true)][string]$Label, [string]$ModelFile = "")
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$env:TMP = Join-Path $root "run\tmp"; $env:TEMP = $env:TMP
$adb = "D:\MiniProj\CellPhone Helper\android-toolchain\sdk\platform-tools\adb.exe"
$pkg = "com.minicpm.assistant"; $rc = "$pkg/com.example.llama.TestHookReceiver"
$out = Join-Path $root ("run\phone-bench-" + $Label + ".txt")
function Run-As([string]$cmd) { (& $adb shell "run-as $pkg $cmd") -join "`n" }

& $adb shell am force-stop $pkg; Start-Sleep 2
if ($ModelFile) {
    $prefs = "shared_prefs/minicpm_settings.xml"
    Run-As "sed -i '/name=.model_file./d' $prefs" | Out-Null
    Run-As "sed -i 's#</map>#    <string name=\`"model_file\`">$ModelFile</string>\n</map>#' $prefs" | Out-Null
}
$cfg = (Run-As "cat shared_prefs/minicpm_settings.xml") -split "`n" | Where-Object { $_ -match "model_file" }
Write-Host ("model setting: " + $(if ($cfg) { ($cfg -join "") } else { "(default model.gguf)" }))

# cold start -> measure load time from boot.log
$t0 = Get-Date
& $adb shell input keyevent KEYCODE_WAKEUP; & $adb shell svc power stayon usb
& $adb shell am start -n "$pkg/com.example.llama.MainActivity" | Out-Null
$ready = $null
for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep 2
    $log = (Run-As "tail -n 8 files/boot.log") -split "`n"
    $last = $log | Where-Object { $_ -match "autoLoad: (\u5c31\u7eea|\u5931\u8d25)" } | Select-Object -Last 1
    $startLine = $log | Where-Object { $_ -match "autoLoad: \u5f00\u59cb\u52a0\u8f7d" } | Select-Object -Last 1
    if ($last -and $startLine -and ($last -gt $startLine)) { $ready = $last; break }
}
$tReady = (Get-Date) - $t0
Write-Host ("cold start to ready: {0:N1} s   ({1})" -f $tReady.TotalSeconds, $ready)
$mem = (& $adb shell "dumpsys meminfo ${pkg}:llm" | Select-String "TOTAL RSS|Native Heap:") -join " | "
Write-Host "llm memory: $mem"

# per-turn latency: the 4 chat-path cases of the e2e run (the rest is deterministic and instant)
& $adb shell "run-as $pkg rm -f files/e2e.txt"
& $adb shell am broadcast -a com.minicpm.assistant.E2E -n $rc | Out-Null
$res = Join-Path $root "run\tmp\bench-e2e.txt"
$txt = ""
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep 10
    cmd /c "`"$adb`" exec-out run-as $pkg cat files/e2e.txt > `"$res`" 2>nul"
    $txt = Get-Content $res -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    if ($txt -and $txt.Contains("END")) { break }
}
$turns = @([regex]::Matches($txt, "===== \[(8|9|10|11)\] .+?  \((\d+)s\)") | ForEach-Object { [int]$_.Groups[2].Value })
$mean = if ($turns.Count) { "{0:N1}" -f (($turns | Measure-Object -Average).Average) } else { "n/a" }
Write-Host ("chat turns (s): " + ($turns -join ", ") + "   mean " + $mean)
$ans = @([regex]::Matches($txt, "===== \[(8|9|10|11)\] .+\r?\n(?:  \S.*\r?\n)*?  \u56de\u7b54: (.+)") | ForEach-Object { $_.Groups[2].Value })
Write-Host "sample replies:"; $ans | ForEach-Object { Write-Host ("  - " + $_.Substring(0, [Math]::Min(110, $_.Length))) }
$lines = @("label=$Label", ("model_setting=" + ($cfg -join "")), ("cold_start_to_ready_s={0:N1}" -f $tReady.TotalSeconds), "llm_memory=$mem", ("chat_turn_seconds=" + ($turns -join ",")), "mean=$mean", "")
$lines + ($ans | ForEach-Object { "reply: " + $_ }) | Set-Content -Path $out -Encoding UTF8
