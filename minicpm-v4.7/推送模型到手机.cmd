@echo off
rem Push MiniCPM-V 4.7 models + Termux to the connected phone.
rem Forces -ExecutionPolicy Bypass so machine script policy cannot block it.
setlocal
set SCRIPT=%~dp0scripts\push-to-phone.ps1
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
