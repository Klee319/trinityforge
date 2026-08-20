@echo off
REM =============================================================================================
REM  Start the whole network IN THE RIGHT ORDER.
REM
REM  Why this order matters (ops\RUNBOOK.md step 13-2):
REM    1. MariaDB and Garnet before the backends. With nothing to connect to, HuskSync fails to
REM       enable BUT THE SERVER STILL STARTS -- you end up running unsynced without noticing.
REM    2. main -> resource -> dev. plugins/TrinityForge is one shared directory, so the
REM       first-run schema migration must not run on two servers at once.
REM    3. Velocity last. Bring it up first and players hit "connects but cannot travel".
REM
REM  If preflight reports even one problem, THIS ABORTS WITHOUT STARTING ANY SERVER.
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

echo.
echo ============================================================
echo  [1/6] MariaDB
echo ============================================================
call "%~dp0start-mariadb.cmd" || exit /b 1

echo.
echo ============================================================
echo  [2/6] Garnet
echo ============================================================
call "%~dp0start-garnet.cmd" || exit /b 1

echo.
echo ============================================================
echo  [3/6] preflight
echo ============================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\preflight.ps1"
if errorlevel 1 (
    echo.
    echo [ERROR] preflight found problems. No server was started.
    echo         Resolve the items above, then run this again.
    exit /b 1
)

echo.
echo ============================================================
echo  [4/6] main  ^(waiting %WAIT_AFTER_MAIN%s for it to finish starting^)
echo ============================================================
call "%~dp0start-main.cmd" || exit /b 1
%SystemRoot%\System32\timeout.exe /t %WAIT_AFTER_MAIN% /nobreak >nul

echo.
echo ============================================================
echo  [5/6] resource and dev
echo ============================================================
call "%~dp0start-resource.cmd" || exit /b 1
%SystemRoot%\System32\timeout.exe /t %WAIT_AFTER_BACKEND% /nobreak >nul
call "%~dp0start-dev.cmd" || exit /b 1
%SystemRoot%\System32\timeout.exe /t %WAIT_AFTER_BACKEND% /nobreak >nul

echo.
echo ============================================================
echo  [6/6] Velocity
echo ============================================================
call "%~dp0start-velocity.cmd" || exit /b 1

echo.
echo All start commands issued.
echo   Now run testkit\check-logs.cmd to confirm HuskSync actually enabled.
exit /b 0
