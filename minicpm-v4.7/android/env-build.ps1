# Android 构建环境（自动生成）
$env:JAVA_HOME = "D:\MiniProj\CellPhone Helper\android-toolchain\jdk"
$env:ANDROID_HOME = "D:\MiniProj\CellPhone Helper\android-toolchain\sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_NDK_HOME = "D:\MiniProj\CellPhone Helper\android-toolchain\sdk\ndk\28.2.13676358"
$env:GRADLE_USER_HOME = "D:\MiniProj\CellPhone Helper\android-toolchain\gradle-home"
$env:TMP = "D:\MiniProj\CellPhone Helper\minicpm-v4.7\run\tmp"
$env:TEMP = $env:TMP
$env:PATH = "$env:JAVA_HOME\bin;D:\MiniProj\CellPhone Helper\android-toolchain\gradle\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
Set-Location "D:\MiniProj\CellPhone Helper\minicpm-v4.7\android"
$global:GRADLE = "D:\MiniProj\CellPhone Helper\android-toolchain\gradle\bin\gradle.bat"
