@echo off
REM =============================================================================================
REM  Install the structure / terrain datapacks into the RESOURCE server.
REM
REM  The packs are copied to datapacks-source, which lives OUTSIDE world. Putting them only in
REM  world\datapacks means the first weekly reset deletes them, and nothing reports it -- the
REM  resource world just quietly comes back as vanilla terrain with none of the added loot pools.
REM  reset-resource.cmd and reset-world.cmd re-install from that source every time.
REM
REM  Arguments (passed through to install-datapacks.ps1):
REM    -DryRun                     show the selection without copying
REM    -SourceDir <path>           where the downloaded zips are
REM    -StrongholdVariant Full|Lite  the two stronghold packs are mutually exclusive
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\install-datapacks.ps1" %*
exit /b %errorlevel%
