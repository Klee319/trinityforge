@echo off
REM =============================================================================================
REM  Clean restart of one backend with in-game warnings first. No force kill, ever: killing a
REM  server mid-flush can corrupt the shared SQLite progression data.
REM
REM  server-loop.cmd brings the server back up by itself, so this only has to send the stop.
REM
REM  Arguments (passed through to restart-server.ps1):
REM    -Target <name>      main / resource / dev, or both (default: both)
REM    -WarnMinutes 5,1    when to announce (default: 10,5,1)
REM    -DryRun             announce nothing and stop nothing
REM
REM  RCON passwords come from TF_RCON_MAIN_PASSWORD / _RESOURCE_ / _DEV_.
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\restart-server.ps1" %*
exit /b %errorlevel%
