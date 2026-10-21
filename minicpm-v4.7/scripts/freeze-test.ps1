# One-click test: "is the app frozen in the background, and do heartbeat ticks keep coming?"
# Use it right after changing any phone setting (battery / autostart / lock in recents ...) to see at once whether it helped.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\freeze-test.ps1 [-Seconds 150] [-ScreenOff]
#   1. brings the app to the foreground, sets heartbeat interval = 1 min, no same-content limit, no quiet hours
#   2. presses Home (optionally turns the screen off) and waits
#   3. counts heartbeat ticks logged in files/hb_log.txt while in the background, and "Reason=frozen" removals of our
#      HEARTBEAT_TICK alarm in dumpsys alarm during the test
#   4. restores the original settings
# PASS = at least (Seconds/60 - 1) ticks arrived while in the background AND no frozen removal happened.
# Do not touch the phone during the test: opening the app wakes it up and spoils the result.
param([int]$Seconds = 150, [switch]$ScreenOff)
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$env:TMP = Join-Path $root "run\tmp"; $env:TEMP = $env:TMP
$adb = "D:\MiniProj\CellPhone Helper\android-toolchain\sdk\platform-tools\adb.exe"
$pkg = "com.minicpm.assistant"; $rc = "$pkg/com.example.llama.TestHookReceiver"
function Foreground { & $adb shell input keyevent KEYCODE_WAKEUP; & $adb shell am start -n "$pkg/com.example.llama.MainActivity" | Out-Null; Start-Sleep 3 }
function HbLines { (cmd /c "`"$adb`" exec-out run-as $pkg cat files/hb_log.txt") | Where-Object { $_ -match "SPEAK|SILENT" } }

Foreground
& $adb shell am broadcast -a com.minicpm.assistant.E2E_HB -n $rc --ez begin true --ei interval 1 --ei same 0 --es qs 00:00 --es qe 00:00 --ez down false --ez reset true | Out-Null
Start-Sleep 8      # the first tick fires ~3 s after the broadcast, while the app is still in the foreground (not counted)
$startedAt = Get-Date
& $adb shell input keyevent KEYCODE_HOME
if ($ScreenOff) { & $adb shell svc power stayon false; & $adb shell input keyevent KEYCODE_SLEEP }
Write-Host ("waiting {0}s in the background{1} (started {2}) ..." -f $Seconds, $(if ($ScreenOff) { " with screen off" } else { "" }), $startedAt.ToString("HH:mm:ss"))
Start-Sleep -Seconds $Seconds
$endAt = $startedAt.AddSeconds($Seconds)
& $adb shell input keyevent KEYCODE_WAKEUP

# evidence 1: frozen removals of our tick alarm that happened after we went to the background
$dump = (& $adb shell "dumpsys alarm") -join "`n"
$frozen = 0
$pattern = "Reason=frozen[^\n]*rtc=\d{4}-\d\d-\d\d (\d\d:\d\d:\d\d)[^\n]*\n\s*Snapshot:\s*\n\s*type=RTC_WAKEUP tag=\*walarm\*:com\.minicpm\.assistant\.HEARTBEAT_TICK"
foreach ($m in [regex]::Matches($dump, $pattern)) {
    if ($m.Groups[1].Value -ge $startedAt.ToString("HH:mm:ss")) { $frozen++ }
}
# evidence 2: ticks logged between going to the background and the end of the wait
$ticks = 0
foreach ($l in (HbLines)) {
    $t = $l.Substring(0, 8)
    if ($t -gt $startedAt.ToString("HH:mm:ss") -and $t -le $endAt.ToString("HH:mm:ss")) { $ticks++ }
}
$need = [Math]::Max(1, [int]($Seconds / 60) - 1)

Foreground
& $adb shell am broadcast -a com.minicpm.assistant.E2E_HB -n $rc --ez end true | Out-Null
$result = if ($ticks -ge $need -and $frozen -eq 0) { "PASS" } else { "FAIL" }
Write-Host ""
Write-Host "ticks logged while in the background : $ticks (need >= $need)"
Write-Host "frozen removals of our tick alarm    : $frozen"
Write-Host "RESULT: $result"
if ($result -eq "FAIL") { Write-Host "The system froze the app in the background. Change the phone's background / autostart / freeze settings for this app and run this script again." }
$out = Join-Path $root "run\freeze-test.txt"
"{0}  ticks={1} need={2} frozen={3} seconds={4} screenoff={5} => {6}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $ticks, $need, $frozen, $Seconds, [bool]$ScreenOff, $result | Add-Content -Path $out -Encoding ASCII
if ($result -eq "PASS") { exit 0 } else { exit 1 }
