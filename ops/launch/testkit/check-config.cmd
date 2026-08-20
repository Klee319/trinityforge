@echo off
REM =============================================================================================
REM  Detect unfinished configuration mechanically. Run this before starting the servers.
REM  Writes nothing: every apply-* script is invoked with -DryRun.
REM
REM  The exit code follows preflight, which is the gate that matters.
REM
REM  ASCII ONLY -- see ..\launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0..\launch-config.cmd" || exit /b 1

echo.
echo === preflight ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\preflight.ps1"
set "PREFLIGHT=%errorlevel%"

echo.
echo === forwarding secret ("no change" on every server means they match) ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\apply-velocity-forwarding.ps1" -DryRun

echo.
echo === HuskSync config (any diff here means it is not applied yet) ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\apply-husksync-config.ps1" -DryRun

echo.
echo === config drift and duplicate jars ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\sync-configs.ps1" -DryRun

exit /b %PREFLIGHT%
