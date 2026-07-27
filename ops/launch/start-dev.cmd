@echo off
REM =============================================================================================
REM  Backend: dev (Dev_Server) -- for testing
REM
REM  Excluded from the scheduled restarts and the weekly reset. Also where config-editor writes.
REM  It shares main's HuskSync database and cluster_id, which means the plugins/TrinityForge
REM  JUNCTION MUST POINT AT main TOO. Without it, synced PDC data (encyclopedia, titles,
REM  prestige) mixes with dev's own SQLite skill levels and gets written back.
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

set "SERVER_ROOT=%VELOCITY_ROOT%\Dev_Server"

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
    wt.exe -w %WT_WINDOW% new-tab --title dev cmd /c call "%OPS_SCRIPTS%\server-loop.cmd" "%SERVER_ROOT%" %HEAP_DEV% %PAPER_JAR%
) else (
    start "dev" cmd /c call "%OPS_SCRIPTS%\server-loop.cmd" "%SERVER_ROOT%" %HEAP_DEV% %PAPER_JAR%
)
exit /b 0
