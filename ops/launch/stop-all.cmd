@echo off
REM =============================================================================================
REM  Stop the whole network in the safe order (Velocity -> dev -> resource -> main -> Garnet).
REM
REM  server-loop.cmd is a "restart after stop" loop, so stop-network.ps1 writes stop.flag BEFORE
REM  sending the RCON stop. Forget the flag and the server comes right back up, looking like it
REM  refuses to stop.
REM
REM  NO FORCE KILL. Killing a server mid-flush can corrupt the shared SQLite progression data.
REM
REM  RCON passwords come from the environment:
REM    TF_RCON_MAIN_PASSWORD / TF_RCON_RESOURCE_PASSWORD / TF_RCON_DEV_PASSWORD
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\stop-network.ps1" ^
  -IncludeGarnet %*
exit /b %errorlevel%
