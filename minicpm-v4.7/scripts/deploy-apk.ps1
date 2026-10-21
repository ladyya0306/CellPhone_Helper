# One-click: backup phone data -> build debug APK -> install (keep data) -> launch -> screenshot
# Log: run\deploy.log   Gradle output: run\deploy-gradle.log   Screenshot: run\deploy-screen.png
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$run  = Join-Path $root "run"
$log  = Join-Path $run "deploy.log"
function Log($m) { $line = (Get-Date -Format "HH:mm:ss") + "  " + $m; Write-Host $line; Add-Content -Path $log -Value $line -Encoding UTF8 }
Set-Content -Path $log -Value "" -Encoding UTF8
Remove-Item (Join-Path $run "deploy.done") -ErrorAction SilentlyContinue

. (Join-Path $root "android\env-build.ps1")
# Same debug keystore as previous builds -> install -r keeps data and the 2.4GB model
$env:ANDROID_USER_HOME = "D:\MiniProj\CellPhone Helper\android-toolchain\android-user-home"
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$pkg = "com.minicpm.assistant"

Log "STEP 1/6 check device"
$devs = & $adb devices | Select-String -Pattern "\tdevice$"
& $adb devices | ForEach-Object { Log ("  " + $_) }
if (-not $devs) { Log "FAIL: no authorized device. Unlock phone, allow USB debugging, then rerun."; "fail:nodevice" | Set-Content (Join-Path $run "deploy.done"); exit 1 }

Log "STEP 2/6 backup app data"
$bk = Join-Path $run ("backup\" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Force -Path $bk | Out-Null
foreach ($f in @("databases/memory.db", "files/todos.json", "files/reminders.json", "files/hb_log.txt", "files/crash.log")) {
    $out = Join-Path $bk ($f -replace "/", "_")
    cmd /c "`"$adb`" exec-out run-as $pkg cat $f > `"$out`" 2>nul"
    $len = (Get-Item $out -ErrorAction SilentlyContinue).Length
    Log ("  " + $f + " -> " + $len + " bytes")
}
cmd /c "`"$adb`" exec-out run-as $pkg ls -l files/models > `"$bk\models_list.txt`" 2>&1"
Get-Content "$bk\models_list.txt" | ForEach-Object { Log ("  model: " + $_) }

Log "STEP 3/6 gradle assembleDebug (several minutes)"
Set-Location (Join-Path $root "android")
$glog = Join-Path $run "deploy-gradle.log"
& $GRADLE :app:assembleDebug --console=plain *> $glog
$code = $LASTEXITCODE
Get-Content $glog -Tail 15 | ForEach-Object { Log ("  | " + $_) }
if ($code -ne 0) { Log "FAIL: gradle exit $code (see run\deploy-gradle.log)"; "fail:gradle" | Set-Content (Join-Path $run "deploy.done"); exit 1 }
$apk = Join-Path $root "android\app\build\outputs\apk\debug\app-debug.apk"
Log ("  APK: " + (Get-Item $apk).Length + " bytes, " + (Get-Item $apk).LastWriteTime)

Log "STEP 4/6 install -r (if phone asks for confirmation, tap Allow/Install)"
# Wake the screen BEFORE installing: with the screen off, vivo's install prompt is auto-rejected (INSTALL_FAILED_ABORTED: User rejected permissions)
& $adb shell svc power stayon usb | Out-Null
& $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
Start-Sleep -Seconds 2
$inst = & $adb install -r $apk 2>&1
$inst | ForEach-Object { Log ("  " + $_) }
if (-not ($inst -match "Success")) { Log "FAIL: install did not report Success"; "fail:install" | Set-Content (Join-Path $run "deploy.done"); exit 1 }
& $adb shell dumpsys package $pkg | Select-String "versionName|lastUpdateTime" | ForEach-Object { Log ("  " + $_.Line.Trim()) }

Log "STEP 5/6 launch app"
& $adb shell svc power stayon usb | Out-Null
& $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb shell am start -n "$pkg/com.example.llama.MainActivity" 2>&1 | ForEach-Object { Log ("  " + $_) }
Start-Sleep -Seconds 25

Log "STEP 6/6 screenshot + boot/crash logs"
cmd /c "`"$adb`" exec-out screencap -p > `"$run\deploy-screen.png`""
cmd /c "`"$adb`" exec-out run-as $pkg cat files/boot.log > `"$run\deploy-boot.log`" 2>nul"
cmd /c "`"$adb`" exec-out run-as $pkg cat files/crash.log > `"$run\deploy-crash.log`" 2>nul"
Log ("  crash.log bytes: " + (Get-Item "$run\deploy-crash.log").Length)
Log "DONE"
"ok" | Set-Content (Join-Path $run "deploy.done")
