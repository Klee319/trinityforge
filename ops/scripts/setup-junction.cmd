@echo off
setlocal enabledelayedexpansion

REM =============================================================================================
REM  Turn a backend's plugins\TrinityForge into a directory junction pointing at the main
REM  server's real directory.
REM
REM  ASCII ONLY. cmd.exe mis-parses UTF-8 batch files (multi-byte characters make it seek to the
REM  wrong byte offset and execute the middle of a line), and this script runs move/mklink --
REM  a mis-parse here is destructive. Japanese explanations live in ops\RUNBOOK.md.
REM  (run-selftest.ps1 fails if non-ASCII creeps back into any ops .cmd file.)
REM
REM  One junction gives you both shared progression data (player_progression.db) and identical
REM  config, with no Java-side change.
REM
REM  Usage (backend directory name; repeat for each one):
REM    setup-junction.cmd Resource_Server
REM    setup-junction.cmd Dev_Server
REM
REM  Dev_Server needs it too: it shares main's HuskSync database, so without the junction the
REM  synced PDC data (encyclopedia, titles, prestige) mixes with dev's own SQLite skill levels
REM  and gets written back.
REM
REM  Preconditions (see the matching RUNBOOK step):
REM    - both servers stopped
REM    - same machine, same local disk (SQLite over a network share corrupts)
REM    - the target's plugins\TrinityForge is empty or already moved aside
REM
REM  Idempotent: a second run reports "already a junction" and changes nothing.
REM =============================================================================================

set "VELOCITY_ROOT=D:\game\minecraft\PaperServer\Velocity_for_TF"
set "TARGET_DIR=%~1"

if "%TARGET_DIR%"=="" (
    echo [ERROR] Argument 1 must be the backend directory name, e.g. Resource_Server
    exit /b 1
)

set "MAIN_TF=%VELOCITY_ROOT%\Main_Server\plugins\TrinityForge"
set "TARGET_PLUGINS=%VELOCITY_ROOT%\%TARGET_DIR%\plugins"
set "TARGET_TF=%TARGET_PLUGINS%\TrinityForge"

echo === TrinityForge directory junction ===
echo   link   (%TARGET_DIR%) : %TARGET_TF%
echo   target (Main_Server)  : %MAIN_TF%
echo.

if /i "%TARGET_DIR%"=="Main_Server" (
    echo [ERROR] Main_Server holds the real directory. It must not be a junction.
    exit /b 1
)

if not exist "%MAIN_TF%\" (
    echo [ERROR] The real directory does not exist: %MAIN_TF%
    echo         Start Main_Server once so the plugin creates it, or fix the path.
    exit /b 1
)

if not exist "%TARGET_PLUGINS%\" (
    echo [ERROR] No plugins directory: %TARGET_PLUGINS%
    echo         Start that server once so it creates plugins\.
    exit /b 1
)

REM --- already a junction to the same target: do nothing (idempotent) ---------------------------
if exist "%TARGET_TF%\" (
    dir /al "%TARGET_PLUGINS%" 2>nul | findstr /i /c:"[%MAIN_TF%]" >nul
    if !errorlevel! equ 0 (
        echo [SKIP] Already a junction to the same target. Nothing to do.
        exit /b 0
    )

    REM --- a real directory is in the way: move it aside ----------------------------------------
    REM   Never delete the contents here -- that would throw away a real config directory.
    set "STAMP=%DATE:~0,4%%DATE:~5,2%%DATE:~8,2%-%TIME:~0,2%%TIME:~3,2%%TIME:~6,2%"
    set "STAMP=!STAMP: =0!"
    set "BACKUP=%TARGET_PLUGINS%\TrinityForge.pre-junction-!STAMP!"
    echo [INFO] A real directory exists on this side. Moving it aside:
    echo        %TARGET_TF%
    echo        -^> !BACKUP!
    move "%TARGET_TF%" "!BACKUP!" >nul
    if !errorlevel! neq 0 (
        echo [ERROR] Move failed. Is the server still running?
        exit /b 1
    )
)

REM --- create the junction ---------------------------------------------------------------------
mklink /J "%TARGET_TF%" "%MAIN_TF%"
if %errorlevel% neq 0 (
    echo [ERROR] mklink failed. Are you running this as administrator?
    exit /b 1
)

echo.
echo === verify ===
dir /al "%TARGET_PLUGINS%" | findstr /i "TrinityForge"
echo.
echo [OK] Junction created.
echo.
echo !! FROM NOW ON !!
echo   - Never point rmdir /s or Remove-Item -Recurse at %TARGET_TF%.
echo     That deletes the real directory on the main side: every player's progression
echo     data and every config file.
echo   - To remove the link: rmdir "%TARGET_TF%"  (WITHOUT /s)
echo   - Start order is always main -^> the others, to avoid concurrent first-run schema creation.
echo   - After saving config in the editor, run /tf reload on every server
echo     (the files are shared, the in-memory config is not).

endlocal
exit /b 0
