@echo off
REM =============================================================================================
REM  Backend: main (Main_Server)
REM
REM  START THIS BEFORE resource AND dev. plugins/TrinityForge is one shared directory, so the
REM  first-run schema migration must not run on two servers at once.
REM
REM  Wrapped in server-loop.cmd, so it comes back up after an RCON stop. Without that wrapper
REM  the scheduled restarts (restart-server.ps1) would leave the server down.
REM
REM  To keep it down, use stop-network.ps1 -- it writes stop.flag. This script deletes stop.flag.
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

set "SERVER_ROOT=%VELOCITY_ROOT%\Main_Server"

if not exist "%SERVER_ROOT%\%PAPER_JAR%" (
    echo [ERROR] Paper jar not found: %SERVER_ROOT%\%PAPER_JAR%
    exit /b 1
)

if exist "%SERVER_ROOT%\stop.flag" (
    echo [INFO] Removing stop.flag (clearing the stay-down marker)
    del "%SERVER_ROOT%\stop.flag"
)

REM One Windows Terminal window, one tab per server (see launch-config.cmd).
if defined USE_WT (
    wt.exe -w %WT_WINDOW% new-tab --title main cmd /c call "%OPS_SCRIPTS%\server-loop.cmd" "%SERVER_ROOT%" %HEAP_MAIN% %PAPER_JAR%
) else (
    start "main" cmd /c call "%OPS_SCRIPTS%\server-loop.cmd" "%SERVER_ROOT%" %HEAP_MAIN% %PAPER_JAR%
)
exit /b 0
