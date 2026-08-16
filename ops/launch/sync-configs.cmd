@echo off
REM =============================================================================================
REM  Copy the ArsPaper config from main to resource and run the pre-start consistency checks
REM  (SHA-256 comparison, duplicate jar detection, junction presence).
REM
REM  Run it after editing ArsPaper yml on main, and any time resource behaves differently from
REM  main for no obvious reason.
REM
REM  Arguments (passed through to sync-configs.ps1):
REM    -DryRun             report differences without copying
REM    -SkipArsPaper       only run the checks
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\sync-configs.ps1" %*
exit /b %errorlevel%
