@echo off
cd /d "%~dp0"
echo Auto test: deploy + run tests on phone (about 8-12 minutes). Keep the phone connected.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\e2e-test.ps1"
echo.
echo Finished. You can close this window.
pause
