@echo off
REM =============================================================================================
REM  Replace the DeathChest plugin (chest1.5.7.jar) with AxGraves on every backend that has it.
REM
REM  Why: DeathChest stores each pending chest under a YAML key that ends with the owner's name.
REM  Bukkit's YAML treats "." as a path separator, and Floodgate prefixes every Bedrock name with
REM  "." here, so such a key silently becomes a nested section. On load the plugin then casts that
REM  section to a List and dies with ClassCastException -- it cannot enable, and the save file is
REM  truncated to 0 bytes. 1.5.7 is the latest upstream release; there is no fixed version.
REM  AxGraves keys its data.json by player UUID, so the failure cannot happen there.
REM
REM  Refuses to run while any backend is up: overwriting a jar in a live JVM ends in
REM  NoClassDefFoundError and only a full stop -> start recovers.
REM
REM  Arguments (passed through to swap-deathchest-to-axgraves.ps1):
REM    -DryRun                  show the plan, write nothing
REM    -Download                fetch the pinned AxGraves jar from Modrinth and verify SHA-512
REM    -SourceJar <path>        use a jar you already downloaded
REM    -DespawnSeconds <n>      grave lifetime; -1 (default) means graves never despawn
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in the .ps1 header and ..\RUNBOOK.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\swap-deathchest-to-axgraves.ps1" %*
exit /b %errorlevel%
