@echo off
REM =============================================================================================
REM  Backend: resource (Resource_Server)
REM
REM  BRING THIS UP AFTER main IS FULLY STARTED. plugins/TrinityForge here is a junction to
REM  main's real directory, so the first-run schema migration must not run concurrently.
REM
REM  This server's worlds are wiped every week (reset-resource.ps1). The guard that keeps the
REM  junction itself from being deleted is Remove-DirectorySafely in Common.ps1.
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

set "SERVER_ROOT=%VELOCITY_ROOT%\Resource_Server"

if not exist "%SERVER_ROOT%\%PAPER_JAR%" (
    echo [ERROR] Paper jar not found: %SERVER_ROOT%\%PAPER_JAR%
    exit /b 1
)

if exist "%SERVER_ROOT%\stop.flag" (
    echo [INFO] Removing stop.flag (clearing the stay-down marker)
    del "%SERVER_ROOT%\stop.flag"
)

start "resource" cmd /c call "%OPS_SCRIPTS%\server-loop.cmd" "%SERVER_ROOT%" %HEAP_RESOURCE% %PAPER_JAR%
exit /b 0
