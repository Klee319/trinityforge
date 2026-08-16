@echo off
REM =============================================================================================
REM  Copy the plugin CONFIG of one backend onto the others. Player data is never copied.
REM
REM  Used when adding a backend, or after a plugin generated its defaults on only one server.
REM
REM  Arguments (passed through to seed-backend-configs.ps1):
REM    -From <name>        source backend (default: Dev)
REM    -To <name...>       destinations (default: Main, Resource)
REM    -Overwrite          replace files that already exist at the destination
REM    -DryRun             list the files without copying
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\seed-backend-configs.ps1" %*
exit /b %errorlevel%
