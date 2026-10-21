# One-click: deploy (backup/build/install/launch) -> run on-phone E2E test hook -> collect results
# Results: run\e2e-result.txt  run\e2e-screen.png  run\e2e-alarms.txt  run\e2e.log
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$run  = Join-Path $root "run"
$log  = Join-Path $run "e2e.log"
function Log($m) { $line = (Get-Date -Format "HH:mm:ss") + "  " + $m; Write-Host $line; Add-Content -Path $log -Value $line -Encoding UTF8 }
Set-Content -Path $log -Value "" -Encoding UTF8
Remove-Item (Join-Path $run "e2e.done") -ErrorAction SilentlyContinue

Log "PART 1: deploy"
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "deploy-apk.ps1")
$d = Get-Content (Join-Path $run "deploy.done") -ErrorAction SilentlyContinue
Log ("  deploy result: " + $d)
if ($d -ne "ok") { Log "FAIL: deploy failed, see run\deploy.log"; "fail:deploy" | Set-Content (Join-Path $run "e2e.done"); exit 1 }

$env:ANDROID_HOME = "D:\MiniProj\CellPhone Helper\android-toolchain\sdk"
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$pkg = "com.minicpm.assistant"

Log "PART 2: trigger E2E hook"
& $adb shell svc power stayon usb | Out-Null
& $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb shell am start -n "$pkg/com.example.llama.MainActivity" | Out-Null
Start-Sleep -Seconds 3
& $adb shell am broadcast -a com.minicpm.assistant.E2E -n "$pkg/com.example.llama.TestHookReceiver" 2>&1 | ForEach-Object { Log ("  " + $_) }

$res = Join-Path $run "e2e-result.txt"
$deadline = (Get-Date).AddMinutes(18)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 15
    cmd /c "`"$adb`" exec-out run-as $pkg cat files/e2e.txt > `"$res`" 2>nul"
    $txt = Get-Content $res -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    $n = 0; if ($txt) { $n = ($txt -split "`n").Count }
    Log ("  progress: " + $n + " lines")
    if ($txt -and $txt.Contains("END")) { break }
}

Log "PART 3: collect"
cmd /c "`"$adb`" shell dumpsys alarm | findstr /i `"minicpm AlertClock BBKClock`" > `"$run\e2e-alarms.txt`""
cmd /c "`"$adb`" exec-out screencap -p > `"$run\e2e-screen.png`""
cmd /c "`"$adb`" exec-out run-as $pkg cat databases/memory.db > `"$run\e2e-memory.db`""
cmd /c "`"$adb`" exec-out run-as $pkg cat files/reminders.json > `"$run\e2e-reminders.json`""
cmd /c "`"$adb`" exec-out run-as $pkg cat files/crash.log > `"$run\e2e-crash.log`" 2>nul"
& $adb shell svc power stayon false | Out-Null
Log "DONE"
"ok" | Set-Content (Join-Path $run "e2e.done")
