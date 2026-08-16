@echo off
REM =============================================================================================
REM  Rebuild the worlds of the chosen backends. For a formal re-open, not for the weekly cycle.
REM
REM  DEFAULTS TO A DRY RUN. Nothing moves until you pass -Apply.
REM
REM  Worlds are PARKED, not deleted: they move to _world-backup-<stamp> on the same volume, so
REM  it finishes instantly, needs no extra space, and can be undone. -Delete removes them.
REM
REM  Refuses to run while any backend is up, refuses junctions, and refuses a delete list that
REM  contains plugins\TrinityForge (one junction shared by all three backends -- deleting it
REM  takes every player's progression and all of the config with it).
REM
REM  Arguments (passed through to reset-world.ps1):
REM    -Apply                      do it
REM    -Target main,resource       which backends (default: main and resource)
REM    -Delete                     delete instead of parking
REM    -SkipDatapacks              do not re-install the resource datapacks
REM
REM  Full procedure, including the player data and the database: RUNBOOK step 18.
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\reset-world.ps1" %*
exit /b %errorlevel%
