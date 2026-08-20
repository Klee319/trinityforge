@echo off
REM =============================================================================================
REM  Self-test of the ops scripts plus a dry run of every destructive one.
REM  Touches no server and no production file.
REM
REM  ALWAYS EYEBALL THE reset-resource.ps1 OUTPUT. If any absolute path it plans to delete
REM  contains plugins\TrinityForge, do not run the real thing (the script refuses too, but this
REM  is your last chance to notice a bad config).
REM
REM  ASCII ONLY -- see ..\launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0..\launch-config.cmd" || exit /b 1

echo.
echo === self-test (measures the delete guards for real) ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\run-selftest.ps1"
set "SELFTEST=%errorlevel%"

echo.
echo === config rollback guards, self-test (W-177) ===
REM  These two are what stops "my config rolled back" from happening again:
REM  the deploy guard copies server-side edits back into the repository, and
REM  run-against-head restores the working tree it borrowed. Both are silent when broken.
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\test-guard-deployed-config.ps1"
if errorlevel 1 set "SELFTEST=1"
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\test-run-against-head.ps1"
if errorlevel 1 set "SELFTEST=1"

echo.
echo === weekly reset, dry run (READ THE DELETE LIST) ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\reset-resource.ps1" -DryRun

echo.
echo === scheduled restart, dry run ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\restart-server.ps1" -DryRun -Target both

echo.
echo === network stop, dry run ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\stop-network.ps1" -DryRun

echo.
echo === backup, dry run ===
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\backup.ps1" -DryRun

exit /b %SELFTEST%
