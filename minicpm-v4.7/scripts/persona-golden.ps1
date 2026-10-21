# Write the persona-resources golden snapshot (all persona text across all modules) to the given file.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\persona-golden.ps1 -Out <file>   (needs compiled classes: run scripts\selftest-jvm.ps1 first)
# NOTE: ASCII-only file.
param([string]$Out)
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $root "android\env-build.ps1")
$env:ANDROID_USER_HOME = "D:\MiniProj\CellPhone Helper\android-toolchain\android-user-home"
& $GRADLE :app:compileDebugKotlin --console=plain --offline *> (Join-Path $root "run\persona-golden-gradle.log")
if ($LASTEXITCODE -ne 0) { Write-Host "FAIL: compile"; exit 2 }
$classes = Join-Path $root "android\app\build\tmp\kotlin-classes\debug"
$libClasses = Join-Path $root "android\lib\build\tmp\kotlin-classes\debug"
$stdlibRoot = "D:\MiniProj\CellPhone Helper\android-toolchain\gradle-home\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib"
$stdlib = Get-ChildItem -Path $stdlibRoot -Recurse -Filter "kotlin-stdlib-2.*.jar" | Sort-Object Name | Select-Object -Last 1
& java '-Dfile.encoding=UTF-8' -cp "$classes;$libClasses;$($stdlib.FullName)" com.example.llama.PersonaGolden $Out
exit $LASTEXITCODE
