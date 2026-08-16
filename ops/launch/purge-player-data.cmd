@echo off
REM =============================================================================================
REM  Erase player data and permissions across all three backends.
REM
REM  DEFAULTS TO A DRY RUN. Nothing is deleted until you pass -Apply.
REM
REM  It also prints a SQL file. RUN IT. Inventories live in husksync_user_data and permissions
REM  in luckperms_*; deleting files alone means HuskSync restores everything on next join.
REM
REM  LuckPerms GROUP DEFINITIONS survive by default -- only the memberships go. Pass
REM  -PurgeGroups to drop the groups as well, then rebuild them from RUNBOOK step 14-3.
REM
REM  Arguments (passed through to purge-player-data.ps1):
REM    -Apply                      do it
REM    -KeepOps <name...>          who stays op (default: Klee319)
REM    -KeepProgression            keep skill levels / SP / perks
REM    -PurgeGroups                also delete the LuckPerms group definitions
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\purge-player-data.ps1" %*
exit /b %errorlevel%
