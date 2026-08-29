@echo off
REM =============================================================================================
REM  Build and deploy the patched HuskSync (the one that does NOT throw away a whole inventory
REM  when a single item cannot be rebuilt on this server).
REM
REM  Upstream 4.0.0 (3dc619d) reads the item array in one pass. One unreadable stack -- e.g. an
REM  enchantment registered by a datapack that is installed on another backend but not here --
REM  makes the whole array throw, and the caller then skips the ENTIRE data type. The player
REM  arrives with no inventory at all. The patch falls back to the per-slot reader that upstream
REM  already has, so only the offending slot is dropped, with a WARNING naming it.
REM
REM  Backends MUST be stopped first: swapping a jar under a running JVM always ends in
REM  NoClassDefFoundError and there is no recovery other than a restart.
REM
REM  Arguments (passed through to deploy-husksync.ps1):
REM    -DryRun        show the plan without writing anything
REM    -SkipBuild     deploy the existing artifact instead of rebuilding
REM    -ForkRoot <p>  HuskSync fork root (default: fork-handoff\husksync\fork)
REM    -JavaHome <p>  JDK for Gradle (default: jdk-25.0.4 -- NOT the jdk-21 used by TrinityForge)
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\deploy-husksync.ps1" %*
exit /b %errorlevel%
