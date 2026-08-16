@echo off
REM =============================================================================================
REM  Make plugins\HuskSync\config.yml identical on every backend, using one server as the base.
REM
REM  A single mismatched feature flag shows up as 'that item vanished when I changed servers',
REM  which is very hard to trace back to a config diff. preflight.cmd detects the mismatch;
REM  this fixes it.
REM
REM  Arguments (passed through to apply-husksync-config.ps1):
REM    -BaseFrom <name>    server to copy from (default: dev)
REM    -DryRun             show the diff without writing
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\apply-husksync-config.ps1" %*
exit /b %errorlevel%
