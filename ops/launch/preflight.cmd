@echo off
REM =============================================================================================
REM  Check the external dependencies BEFORE starting the servers.
REM
REM  Looks at MariaDB / Garnet reachability, the HuskSync credentials and features, and whether
REM  the Velocity forwarding secret matches on every backend.
REM
REM  WHY THIS MATTERS: when HuskSync cannot reach the database it fails to enable but the server
REM  KEEPS STARTING. Nothing looks broken until inventories stop following players between
REM  servers. Run this and read the exit code; non-zero means do not start yet.
REM
REM  Writes nothing. Takes no arguments.
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\preflight.ps1" %*
exit /b %errorlevel%
