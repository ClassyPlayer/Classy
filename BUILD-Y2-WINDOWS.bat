@echo off
setlocal EnableExtensions EnableDelayedExpansion
title Classy Y2 Firmware Builder

set "ROOT=%~dp0"
rem Drop the trailing backslash: "C:\folder\" would escape the closing quote when passed to wsl.exe
set "ROOT_NS=%ROOT:~0,-1%"
set "LINUX_ROOT="
set "DISTRO="
set "DARG="

echo ==========================================
echo       CLASSY Y2 LOCAL FIRMWARE BUILDER
echo ==========================================
echo.
echo [1/5] Checking WSL Ubuntu installation...
rem "wsl --install -d Ubuntu" registers the distro as "Ubuntu"; try that, then 24.04, then the WSL default.
for %%D in (Ubuntu Ubuntu-24.04) do (
  if not defined DISTRO (
    C:\Windows\System32\wsl.exe --distribution "%%D" --exec /bin/echo WSL_WORKS >nul 2>&1
    if !errorlevel! equ 0 set "DISTRO=%%D"
  )
)
if defined DISTRO (
  set DARG=--distribution !DISTRO!
) else (
  set "DARG="
)
C:\Windows\System32\wsl.exe !DARG! --exec /bin/echo WSL_WORKS
if !errorlevel! neq 0 goto failed
echo Using WSL distro: !DISTRO!

echo.
echo [2/5] Translating project folder path...
for /f "usebackq delims=" %%P in (`C:\Windows\System32\wsl.exe !DARG! --exec wslpath -a "%ROOT_NS%"`) do set "LINUX_ROOT=%%P"
set "LINUX_ROOT=%LINUX_ROOT:"=%"
if not defined LINUX_ROOT goto failed
if not exist "%ROOT%scripts\build-y2-local.sh" goto failed
echo Project path: %LINUX_ROOT%

echo.
echo [3/5] Checking build helper in Ubuntu...
C:\Windows\System32\wsl.exe !DARG! --exec test -f "%LINUX_ROOT%/scripts/build-y2-local.sh"
if !errorlevel! neq 0 goto failed

echo.
echo [4/5] Building Y2 APK and firmware...
echo The first run downloads build tools and firmware components.
echo Keep this window open. Ubuntu may ask for its password.
C:\Windows\System32\wsl.exe !DARG! --cd "%LINUX_ROOT%" --exec bash scripts/build-y2-local.sh
if !errorlevel! neq 0 goto failed

echo.
echo [5/5] Verifying output files...
if not exist "%ROOT%OUTPUT\rom_y2.zip" goto failed
if not exist "%ROOT%OUTPUT\app-y2-debug.apk" goto failed
if not exist "%ROOT%OUTPUT\SHA256SUMS.txt" goto failed

echo.
echo ==========================================
echo BUILD COMPLETED - OUTPUT VERIFIED
echo ==========================================
echo Files are in: %ROOT%OUTPUT
echo.
echo Do not flash until audiobook files and Classy playback data
echo are backed up and a recovery plan is confirmed.
echo.
pause
exit /b 0

:failed
echo.
echo ==========================================
echo BUILD FAILED OR OUTPUT INCOMPLETE
echo ==========================================
echo No firmware is confirmed ready. Copy all error text above.
echo.
pause
exit /b 1
