@echo off
rem Show server status and run a multimodal self-test (image -> answer + speed).
setlocal
set SCRIPT=%~dp0scripts\minicpm.ps1
where pwsh >nul 2>nul
if %errorlevel%==0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" status
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" test
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" status
    powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" test
)
echo.
echo Press any key to close...
pause >nul
endlocal
