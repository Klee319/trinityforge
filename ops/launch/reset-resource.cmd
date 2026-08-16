@echo off
REM =============================================================================================
REM  Weekly reset of the resource world: announce, stop, delete, re-install the datapacks,
REM  verify, restart, pre-generate.
REM
REM  UNLIKE THE OTHER DESTRUCTIVE SCRIPTS, reset-resource.ps1 defaults to DOING IT. So this
REM  wrapper forces -DryRun unless you pass --apply. Type it out; that is the point.
REM
REM  Arguments after --apply are passed through (up to 9, which is plenty here):
REM    -WarnMinutes 5,1    when to announce (default: 10,5,1)
REM    -SkipPregen         skip the Chunky pre-generation
REM    -PregenRadius <n>   pre-generation radius (default: 2500)
REM
REM  To rebuild the MAIN world too, or to erase player data as well, use RUNBOOK step 18
REM  (reset-world.cmd + purge-player-data.cmd) instead.
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

REM  A goto, not an if-block: cmd.exe expands %1 for the WHOLE block before running shift,
REM  so shifting inside parentheses would still hand --apply to PowerShell.
if /i "%~1"=="--apply" goto :apply

echo.
echo   Dry run. Nothing will be stopped or deleted.
echo   Re-run as:  reset-resource.cmd --apply
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\reset-resource.ps1" -DryRun %*
exit /b %errorlevel%

:apply
shift
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\reset-resource.ps1" %1 %2 %3 %4 %5 %6 %7 %8 %9
exit /b %errorlevel%
