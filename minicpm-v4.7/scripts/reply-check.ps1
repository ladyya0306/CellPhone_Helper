# Run raw model replies through the phone's real clean-up pipeline (ReplyCleaner + ClaimGuard). ASCII-only file.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\reply-check.ps1 -In <in.tsv> -Out <out.tsv> [-SkipCompile]
param([string]$In, [string]$Out, [switch]$SkipCompile)
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $root "android\env-build.ps1")
$env:ANDROID_USER_HOME = "D:\MiniProj\CellPhone Helper\android-toolchain\android-user-home"
if (-not $SkipCompile) {
    & $GRADLE :app:compileDebugKotlin --console=plain --offline *> (Join-Path $root "run\reply-check-gradle.log")
    if ($LASTEXITCODE -ne 0) { Write-Host "FAIL: compile"; exit 2 }
}
$classes = Join-Path $root "android\app\build\tmp\kotlin-classes\debug"
$libClasses = Join-Path $root "android\lib\build\tmp\kotlin-classes\debug"
$stdlibRoot = "D:\MiniProj\CellPhone Helper\android-toolchain\gradle-home\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib"
$stdlib = Get-ChildItem -Path $stdlibRoot -Recurse -Filter "kotlin-stdlib-2.*.jar" | Sort-Object Name | Select-Object -Last 1
& java '-Dfile.encoding=UTF-8' -cp "$classes;$libClasses;$($stdlib.FullName)" com.example.llama.ReplyCheck $In $Out
exit $LASTEXITCODE
