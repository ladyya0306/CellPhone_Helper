# Validate generated focus lines with the SAME rules the phone uses (FocusLineBank / InsultGuard).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\focus-line-check.ps1 -In <raw.tsv> -Out <checked.tsv>
# Needs the app classes compiled (run scripts\selftest-jvm.ps1 once, or let this script compile them).
# NOTE: keep this file ASCII-only (Windows PowerShell 5.1).
param([string]$In, [string]$Out, [switch]$SkipCompile)
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $root "android\env-build.ps1")
$env:ANDROID_USER_HOME = "D:\MiniProj\CellPhone Helper\android-toolchain\android-user-home"
if (-not $SkipCompile) {
    & $GRADLE :app:compileDebugKotlin --console=plain --offline *> (Join-Path $root "run\focus-line-check-gradle.log")
    if ($LASTEXITCODE -ne 0) { Write-Host "FAIL: compile"; exit 2 }
}
$classes = Join-Path $root "android\app\build\tmp\kotlin-classes\debug"
$stdlibRoot = "D:\MiniProj\CellPhone Helper\android-toolchain\gradle-home\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib"
$stdlib = Get-ChildItem -Path $stdlibRoot -Recurse -Filter "kotlin-stdlib-2.*.jar" | Sort-Object Name | Select-Object -Last 1
$libClasses = Join-Path $root "android\lib\build\tmp\kotlin-classes\debug"
$cp = "$classes;$libClasses;$($stdlib.FullName)"
& java '-Dfile.encoding=UTF-8' -cp $cp com.example.llama.FocusLineCheck $In $Out
exit $LASTEXITCODE
