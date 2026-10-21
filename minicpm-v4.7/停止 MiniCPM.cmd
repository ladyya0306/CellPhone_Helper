@echo off
rem Stop the local MiniCPM-V 4.7 server.
setlocal
set SCRIPT=%~dp0scripts\minicpm.ps1
where pwsh >nul 2>nul
if %errorlevel%==0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" stop %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" stop %*
)
echo.
echo Press any key to close...
pause >nul
endlocal
