@echo off
rem ============================================================
rem  MiniCPM-V 4.7 本地服务 —— 双击启动
rem  停止 / 状态 / 自测请用命令行：
rem      scripts\minicpm.ps1 stop | status | test
rem ============================================================
setlocal
set SCRIPT=%~dp0scripts\minicpm.ps1
where pwsh >nul 2>nul
if %errorlevel%==0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" start %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" start %*
)
if errorlevel 1 (
    echo.
    echo [启动失败] 按任意键关闭窗口...
    pause >nul
)
endlocal
