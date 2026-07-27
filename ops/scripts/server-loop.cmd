@echo off
setlocal enabledelayedexpansion

REM =============================================================================================
REM  Wrap Paper in a "restart after stop" loop. Same script for main / resource / dev.
REM
REM  ASCII ONLY. cmd.exe mis-parses UTF-8 batch files: multi-byte characters make it seek to the
REM  wrong byte offset, and it starts executing the middle of a line. This file used to carry
REM  Japanese comments and spun in a hot infinite loop that never checked stop.flag and never
REM  started the server. Japanese explanations belong in ops\RUNBOOK.md, not in .cmd files.
REM  (run-selftest.ps1 fails if non-ASCII creeps back into any ops .cmd file.)
REM
REM  Why no restart plugin (ops\PERFORMANCE.md has the details):
REM    HuskSync officially does not support restart plugins. Rebuilding worlds and players while
REM    keeping the process alive breeds data loss and item duplication, so every restart goes
REM    through "clean RCON stop, then start the process again".
REM
REM  Usage:
REM    server-loop.cmd "D:\game\minecraft\PaperServer\Velocity_for_TF\Main_Server"     8G paper-1.21.11-132.jar
REM    server-loop.cmd "D:\game\minecraft\PaperServer\Velocity_for_TF\Resource_Server" 6G paper-1.21.11-132.jar
REM
REM  To leave the loop (maintenance, i.e. do not bring it back up):
REM    put an empty file named stop.flag in the server root, then stop the server.
REM =============================================================================================

set "SERVER_ROOT=%~1"
set "HEAP=%~2"
set "PAPER_JAR=%~3"

if "%SERVER_ROOT%"=="" (
    echo [ERROR] Argument 1 must be the server root.
    exit /b 1
)
if "%HEAP%"==""      set "HEAP=8G"
if "%PAPER_JAR%"=="" set "PAPER_JAR=paper-1.21.11-132.jar"

cd /d "%SERVER_ROOT%" || (
    echo [ERROR] Cannot change into the server root: %SERVER_ROOT%
    exit /b 1
)

if not exist "%PAPER_JAR%" (
    echo [ERROR] Paper jar not found: %SERVER_ROOT%\%PAPER_JAR%
    exit /b 1
)

REM Pause before restarting, in seconds. Keeps a crash loop from pinning the CPU.
set "RESTART_DELAY=10"

:loop
if exist "stop.flag" (
    echo [INFO] stop.flag present, leaving the loop.
    echo        To resume, delete stop.flag and start this script again.
    goto :eof
)

echo.
echo ===============================================================
echo  starting: %SERVER_ROOT%  (heap=%HEAP%)  %DATE% %TIME%
echo ===============================================================

REM Aikar's G1 flags. Kept identical to the current start.bat.
java -Xms%HEAP% -Xmx%HEAP% ^
 -XX:+UseG1GC -XX:+ParallelRefProcEnabled ^
 -XX:MaxGCPauseMillis=200 -XX:+DisableExplicitGC -XX:+AlwaysPreTouch ^
 -jar "%PAPER_JAR%" nogui

set "EXIT_CODE=!errorlevel!"
echo [INFO] Paper exited (exit=!EXIT_CODE!) %DATE% %TIME%

if exist "stop.flag" (
    echo [INFO] stop.flag detected, not restarting.
    goto :eof
)

echo [INFO] Restarting in %RESTART_DELAY%s. Ctrl+C to abort.
REM Fully qualified: GNU coreutils' timeout shadows the Windows one on some PATHs and rejects
REM this syntax, which would turn the delay into a no-op and spin a crash loop at full speed.
%SystemRoot%\System32\timeout.exe /t %RESTART_DELAY% /nobreak >nul
goto loop
