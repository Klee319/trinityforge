@echo off
REM =============================================================================================
REM  Confirm that no backend is running. The gate to pass before swapping any jar.
REM
REM  Swapping a jar under a live JVM always ends in NoClassDefFoundError and the only cure is a
REM  restart. deploy.cmd calls this for you; run it directly when you want to check by hand.
REM
REM  Arguments (passed through to check-servers-stopped.ps1):
REM    -Server <name...>   only check these backends
REM    -Quiet              exit code only, no output
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\check-servers-stopped.ps1" %*
exit /b %errorlevel%
