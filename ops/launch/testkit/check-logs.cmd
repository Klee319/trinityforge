@echo off
REM =============================================================================================
REM  Scan each server's latest log for the known symptoms that actually hurt this setup.
REM
REM  The one to catch above all: "HuskSync failed to enable but the server started anyway".
REM  Nobody notices until inventories stop syncing, because the startup does not fail.
REM
REM  Arguments pass straight through to check-logs.ps1 (e.g. -All to scan whole logs).
REM
REM  ASCII ONLY -- see ..\launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0..\launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\check-logs.ps1" %*
exit /b %errorlevel%
