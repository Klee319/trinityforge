@echo off
REM =============================================================================================
REM  Write the Velocity forwarding.secret into each backend's config\paper-global.yml.
REM
REM  If the secret does not match, EVERY player is rejected with 'Unable to verify player
REM  details'. Nothing else about the server looks wrong.
REM
REM  Arguments (passed through to apply-velocity-forwarding.ps1):
REM    -Target <name>      one backend, or all (default: all)
REM    -DryRun             show what would change; 'no change' everywhere means they match
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\apply-velocity-forwarding.ps1" %*
exit /b %errorlevel%
