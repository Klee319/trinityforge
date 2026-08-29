@echo off
REM =============================================================================================
REM  Start the whole network IN THE RIGHT ORDER.
REM
REM  Why this order matters (ops\RUNBOOK.md step 13-2):
REM    1. MariaDB and Garnet before the backends. With nothing to connect to, HuskSync fails to
REM       enable BUT THE SERVER STILL STARTS -- you end up running unsynced without noticing.
REM    2. main -> resource (-> dev, if you start it later). plugins/TrinityForge is one shared
REM       directory, so the first-run schema migration must not run on two servers at once.
REM    3. Velocity last. Bring it up first and players hit "connects but cannot travel".
REM
REM  NOT STARTED HERE (2026-08-24, matching what was actually running on the box):
REM    - preflight. It used to run as a gate that aborted the whole start. Run it yourself when
REM      you want the check:  preflight.cmd
REM    - the DEV backend. Start it on demand with start-dev.cmd.
REM  If you put either back, put it back HERE and redeploy -- editing the deployed copy under
REM  Velocity_for_TF\launch is pointless, deploy-launch.cmd overwrites it.
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

echo.
echo ============================================================
echo  [1/4] MariaDB
echo ============================================================
call "%~dp0start-mariadb.cmd" || exit /b 1

echo.
echo ============================================================
echo  [2/4] Garnet
echo ============================================================
call "%~dp0start-garnet.cmd" || exit /b 1

echo.
echo ============================================================
echo  [3/4] main  ^(waiting %WAIT_AFTER_MAIN%s for it to finish starting^)
echo ============================================================
call "%~dp0start-main.cmd" || exit /b 1
%SystemRoot%\System32\timeout.exe /t %WAIT_AFTER_MAIN% /nobreak >nul

echo.
echo ============================================================
echo  [4/4] resource, then Velocity
echo ============================================================
call "%~dp0start-resource.cmd" || exit /b 1
%SystemRoot%\System32\timeout.exe /t %WAIT_AFTER_BACKEND% /nobreak >nul
call "%~dp0start-velocity.cmd" || exit /b 1

echo.
echo All start commands issued.
echo   Now run testkit\check-logs.cmd to confirm HuskSync actually enabled.
exit /b 0
