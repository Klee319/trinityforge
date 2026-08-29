@echo off
REM =============================================================================================
REM  Keep mobGriefing enabled and block only the destructive mobs, via WorldGuard.
REM
REM  Using "gamerule mobGriefing false" to stop creeper terrain damage also stops villagers from
REM  picking up food, so villagers no longer breed. mobGriefing is a single switch and cannot be
REM  narrowed to creepers. WorldGuard 7 splits the same behaviour per mob, so mobGriefing can stay
REM  true while creepers (and endermen / wither / ghast / zombie doors) stop breaking blocks.
REM
REM  Arguments (passed through to apply-mob-griefing-guard.ps1):
REM    -Target <name>   one backend, or all (default: main)
REM    -Keys <list>     only these WorldGuard mobs.* keys (default: the six listed in the ps1)
REM    -Revert          set the keys back to false
REM    -DryRun          show what would change without writing
REM
REM  A running server does NOT pick this up: run /wg reload (or restart) afterwards, and run
REM  /gamerule mobGriefing true in the overworld to bring villager breeding back.
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\apply-mob-griefing-guard.ps1" %*
exit /b %errorlevel%
