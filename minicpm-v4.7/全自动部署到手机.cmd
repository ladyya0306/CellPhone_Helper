@echo off
rem Fully automatic MiniCPM-V 4.7 deployment to the connected phone.
rem No commands need to be typed on the phone.
setlocal
set SCRIPT=%~dp0scripts\auto-setup-phone.ps1
where pwsh >nul 2>nul
if %errorlevel%==0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
)
if errorlevel 1 (
    echo.
    echo [FAILED] Press any key to close...
    pause >nul
)
endlocal
