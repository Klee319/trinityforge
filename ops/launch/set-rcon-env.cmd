@echo off
REM =============================================================================================
REM  Fill in the TF_RCON_<KEY>_PASSWORD environment variables from each backend's
REM  server.properties. Run this ONCE per machine (and again if you change an RCON password).
REM
REM  Without these variables every ops script that is NOT a dry run throws on its first line,
REM  complaining that TF_RCON_MAIN_PASSWORD is unset.
REM  That is what "I ran the batch and nothing happened" looks like from the outside.
REM
REM  The passwords are never printed. They are written straight into the user environment.
REM
REM  IMPORTANT: variables only reach NEW windows. Close this one and open a fresh one before
REM  running reset-resource.cmd / restart.cmd / stop-all.cmd.
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\set-rcon-env.ps1" %*
set TF_RCON_ENV_RC=%errorlevel%
echo.
pause
exit /b %TF_RCON_ENV_RC%
