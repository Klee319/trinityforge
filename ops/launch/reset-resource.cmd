@echo off
REM =============================================================================================
REM  Weekly reset of the resource world: announce, stop, delete, re-install the datapacks,
REM  verify, restart, pre-generate.
REM
REM  UNLIKE THE OTHER DESTRUCTIVE SCRIPTS, reset-resource.ps1 defaults to DOING IT.
REM
REM  Double-clicking this file runs a DRY RUN first, prints everything that would be deleted,
REM  and then asks you to type RESET to go through with it. Typing it out is the point.
REM  (Before 2026-08-23 the no-argument form stopped after the dry run, which read as
REM   "I pressed it and nothing happened".)
REM
REM  Pass --apply to skip the prompt entirely (for scheduled / unattended runs).
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
echo   Step 1 of 2: DRY RUN. Nothing has been stopped or deleted yet.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\reset-resource.ps1" -DryRun %*
if errorlevel 1 (
  echo.
  echo   The dry run failed. Nothing was changed. Fix the error above first.
  pause
  exit /b %errorlevel%
)

echo.
echo   ------------------------------------------------------------------
echo   Step 2 of 2: CONFIRM.
echo   Everything listed above will be DELETED. The resource world is then
echo   regenerated from a NEW random seed (server.properties has no
echo   level-seed, so the terrain will be different every time).
echo   Player inventories and the MAIN world are NOT touched.
echo   ------------------------------------------------------------------
echo.
set "TF_RESET_CONFIRM="
set /p "TF_RESET_CONFIRM=Type RESET in capitals to do it for real, or press Enter to cancel: "
if not "%TF_RESET_CONFIRM%"=="RESET" (
  echo.
  echo   Cancelled. Nothing was changed.
  pause
  exit /b 0
)

echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\reset-resource.ps1" %*
set TF_RESET_RC=%errorlevel%
echo.
pause
exit /b %TF_RESET_RC%

:apply
shift
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\reset-resource.ps1" %1 %2 %3 %4 %5 %6 %7 %8 %9
exit /b %errorlevel%
