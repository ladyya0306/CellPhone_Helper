@echo off
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\deploy-apk.ps1"
echo.
echo Finished. You can close this window.
pause
