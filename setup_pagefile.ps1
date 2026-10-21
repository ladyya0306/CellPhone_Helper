# 需要管理员权限运行：为 D: 盘增加页文件，缓解 35B 模型运行时的内存压力。
# 用法：以管理员身份打开 PowerShell，然后执行：
#   powershell -ExecutionPolicy Bypass -File "D:\MiniProj\CellPhone Helper\setup_pagefile.ps1"
#
# 本脚本只做三件事：显示当前页文件配置 -> 设置 D: 盘页文件 -> 提示重启生效。
# 不会删除 C: 盘页文件，也不会改动其他系统设置。

$ErrorActionPreference = 'Stop'

$isAdmin = ([Security.Principal.WindowsPrincipal] `
    [Security.Principal.WindowsIdentity]::GetCurrent()
).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host "当前不是管理员权限。请右键 PowerShell -> 以管理员身份运行，再执行本脚本。" -ForegroundColor Red
    exit 1
}

Write-Host "=== 修改前 ===" -ForegroundColor Cyan
Get-CimInstance Win32_PageFileSetting | Select-Object Name, InitialSize, MaximumSize | Format-Table -AutoSize
$cs = Get-CimInstance Win32_ComputerSystem
Write-Host ("AutomaticManagedPagefile : {0}" -f $cs.AutomaticManagedPagefile)
Get-CimInstance Win32_PageFileUsage | Select-Object Name, AllocatedBaseSize, CurrentUsage, PeakUsage | Format-Table -AutoSize

# 关闭“自动管理所有驱动器的分页文件大小”，否则无法固定 D: 的页文件
if ($cs.AutomaticManagedPagefile) {
    Write-Host "关闭 AutomaticManagedPagefile ..." -ForegroundColor Yellow
    Set-CimInstance -InputObject $cs -Property @{ AutomaticManagedPagefile = $false }
}

$d = Get-CimInstance Win32_PageFileSetting -Filter "Name='D:\\pagefile.sys'" -ErrorAction SilentlyContinue
if ($d) {
    Write-Host "已存在 D: 页文件设置，更新大小 ..." -ForegroundColor Yellow
    Set-CimInstance -InputObject $d -Property @{ InitialSize = 32768; MaximumSize = 65536 }
} else {
    Write-Host "新建 D: 页文件设置 (初始 32 GB / 最大 64 GB) ..." -ForegroundColor Yellow
    New-CimInstance -ClassName Win32_PageFileSetting -Property @{
        Name        = 'D:\pagefile.sys'
        InitialSize = 32768
        MaximumSize = 65536
    } | Out-Null
}

Write-Host ""
Write-Host "=== 修改后 ===" -ForegroundColor Cyan
Get-CimInstance Win32_PageFileSetting | Select-Object Name, InitialSize, MaximumSize | Format-Table -AutoSize
Write-Host "请重启电脑使页文件真正生效（重启前 D:\pagefile.sys 不会立即扩大）。" -ForegroundColor Green
