@echo off
REM =============================================================================================
REM  Stop EliteMobs from converting naturally spawned vanilla mobs into "Elite" mobs.
REM
REM  TrinityForge already gives every mob its own level / HP / EXP and draws its own overhead
REM  display, so EliteMobs' natural elite conversion duplicates both the level system and the
REM  overhead text. This flips MobCombatSettings.yml doNaturalEliteMobSpawning to false.
REM
REM  The server only reads this file at startup -- stop the servers first.
REM  Mobs that were already converted stay converted until they die or despawn.
REM
REM  Arguments (passed through to disable-natural-elite-spawning.ps1):
REM    -Apply      actually write; without it nothing changes
REM    -Revert     put it back to true
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\disable-natural-elite-spawning.ps1" %*
exit /b %errorlevel%
