# Dump the REAL system prompts of every persona (Personas.promptFor) to run\eval\prompts.txt, so that PC-side model
# evaluation uses exactly what the app sends. Uses the classes compiled by selftest-jvm.ps1 (run that first if sources changed).
# NOTE: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less files as ANSI).
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $root "android\env-build.ps1")
$classes = Join-Path $root "android\app\build\tmp\kotlin-classes\debug"
$libClasses = Join-Path $root "android\lib\build\tmp\kotlin-classes\debug"
$stdlibRoot = "D:\MiniProj\CellPhone Helper\android-toolchain\gradle-home\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib"
$stdlib = Get-ChildItem -Path $stdlibRoot -Recurse -Filter "kotlin-stdlib-2.3.0.jar" | Select-Object -First 1
$cp = "$classes;$libClasses;$($stdlib.FullName)"
$tmp = Join-Path $root "run\tmp\dump-runner"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$java = @'
import com.example.llama.Persona;
import com.example.llama.Personas;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
public class DumpPrompts {
    public static void main(String[] a) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Persona p : Personas.INSTANCE.getALL()) {
            sb.append("@@PERSONA ").append(p.getId()).append(" ").append(p.getName()).append("\n");
            sb.append(Personas.INSTANCE.promptFor(p)).append("\n");
            sb.append("@@END\n");
        }
        Files.write(Paths.get(a[0]), sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
'@
[System.IO.File]::WriteAllText((Join-Path $tmp "DumpPrompts.java"), $java, (New-Object System.Text.UTF8Encoding($false)))
& javac -encoding UTF-8 -cp $cp -d $tmp (Join-Path $tmp "DumpPrompts.java")
if ($LASTEXITCODE -ne 0) { Write-Host "FAIL: javac"; exit 3 }
$outDir = Join-Path $root "run\eval"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$dest = Join-Path $outDir "prompts.txt"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
& java '-Dfile.encoding=UTF-8' -cp "$tmp;$cp" DumpPrompts $dest
Write-Host ("wrote {0}" -f $dest)
