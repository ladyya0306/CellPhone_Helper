# Run the regression corpus (AssistantSelfTest, pure Kotlin) on the PC: no phone, no network, no JUnit needed.
# Usage:  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\selftest-jvm.ps1
# Result: run\selftest-jvm.txt (also printed). Exit code 0 = all passed, 1 = some failed, 2/3 = build problem.
# How:    gradle :app:compileDebugKotlin (offline) -> javac/java from the JDK load the compiled classes -> AssistantSelfTest.runAll()
# NOTE:   keep this file ASCII-only: Windows PowerShell 5.1 reads BOM-less files as ANSI and can swallow line breaks after CJK text.
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$run  = Join-Path $root "run"
. (Join-Path $root "android\env-build.ps1")
$env:ANDROID_USER_HOME = "D:\MiniProj\CellPhone Helper\android-toolchain\android-user-home"

$glog = Join-Path $run "selftest-jvm-gradle.log"
& $GRADLE :app:compileDebugKotlin --console=plain --offline *> $glog
if ($LASTEXITCODE -ne 0) {
    Get-Content $glog -Tail 25
    Write-Host "FAIL: compile failed (see $glog)"; exit 2
}

$classes = Join-Path $root "android\app\build\tmp\kotlin-classes\debug"
$stdlibRoot = "D:\MiniProj\CellPhone Helper\android-toolchain\gradle-home\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib"
$stdlib = Get-ChildItem -Path $stdlibRoot -Recurse -Filter "kotlin-stdlib-2.3.0.jar" | Select-Object -First 1
if (-not $stdlib) { $stdlib = Get-ChildItem -Path $stdlibRoot -Recurse -Filter "kotlin-stdlib-2.*.jar" | Sort-Object Name | Select-Object -Last 1 }
# coroutines (for runBlocking/Flow in the remote-engine cases) and the :lib module classes (InferenceEngine interface)
$coRoot = "D:\MiniProj\CellPhone Helper\android-toolchain\gradle-home\caches\modules-2\files-2.1\org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm"
$co = Get-ChildItem -Path $coRoot -Recurse -Filter "kotlinx-coroutines-core-jvm-*.jar" | Where-Object { $_.Name -notmatch "sources" } | Sort-Object Name | Select-Object -Last 1
$libClasses = Join-Path $root "android\lib\build\tmp\kotlin-classes\debug"
$cp = "$classes;$libClasses;$($stdlib.FullName);$($co.FullName)"

$tmp = Join-Path $run "tmp\selftest-runner"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$java = @'
import java.io.PrintStream;
public class RunSelfTest {
    public static void main(String[] a) throws Exception {
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        com.example.llama.AssistantSelfTest.Report r = com.example.llama.AssistantSelfTest.INSTANCE.runAll();
        out.print(r.render());
        System.exit(r.getOk() ? 0 : 1);
    }
}
'@
# Write without BOM (javac rejects a BOM)
[System.IO.File]::WriteAllText((Join-Path $tmp "RunSelfTest.java"), $java, (New-Object System.Text.UTF8Encoding($false)))

& javac -encoding UTF-8 -cp $cp -d $tmp (Join-Path $tmp "RunSelfTest.java")
if ($LASTEXITCODE -ne 0) { Write-Host "FAIL: javac runner"; exit 3 }

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$out = & java -cp "$tmp;$cp" RunSelfTest 2>&1
$code = $LASTEXITCODE
$out | Set-Content -Path (Join-Path $run "selftest-jvm.txt") -Encoding UTF8
$out | ForEach-Object { Write-Host $_ }
exit $code
