@echo off
setlocal
REM =============================================================================================
REM  Copy the repository yml onto the deployed config, SKIPPING files that another session still
REM  has uncommitted in the working tree.
REM
REM  Why this exists
REM    deploy.cmd --config copies every *.yml under TrinityForge\src\main\resources. Several
REM    sessions share one working tree, so that sweep also ships whatever another session has
REM    half-finished. On 2026-08-02 that meant progression\collection.yml and stats\skill-exp.yml
REM    (the latter carried a real balance change: ARCHERY kill-exp 25 -> 30).
REM
REM  Usage:
REM    deploy-config-skip-wip.cmd                 copy, skipping the WIP files below
REM    deploy-config-skip-wip.cmd --dry-run       print the plan. Copies nothing.
REM
REM  ABORTS WHILE ANY BACKEND IS RUNNING. A plugin reads its yml at enable time, so copying under
REM  a live JVM does nothing useful and leaves the on-disk config out of step with what is loaded.
REM  Stop the network first: launch\stop-all.cmd
REM
REM  Same exclusions deploy.cmd uses, for the same reasons:
REM    paper-plugin.yml   plugin descriptor, not config
REM    sourcejars.yml     live state: source jar block coordinates written by the running server
REM    sourcelinks.yml    live state: source link block coordinates written by the running server
REM  Overwriting the last two points the live world at blocks that are somewhere else.
REM  Nothing at the destination is ever deleted.
REM
REM  ASCII ONLY -- cmd.exe mis-parses UTF-8 batch files and starts executing the middle of a line.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

REM ---- files another session still owns. Edit this list when the situation changes. ------------
set "SKIP_TF=collection.yml skill-exp.yml"

set "DRYRUN="
if /i "%~1"=="--dry-run" set "DRYRUN=1"
if /i "%~1"=="--help" goto usage

echo ============================================================
echo  deploy config, skipping another session's WIP
echo ============================================================
echo   repo        : %TF_REPO%
echo   config host : %TF_CONFIG_HOST%
echo   backends    : %TF_BACKENDS%
echo   skipped     : %SKIP_TF%
echo.

REM ---- 1/3  refuse to run against a live server ------------------------------------------------
echo --- 1/3  are the backends stopped ---
set "RUNNING="
for %%B in (%TF_BACKENDS%) do call :check_running "%%B"
if not defined RUNNING goto stopped
echo.
echo   One or more backends look alive. A plugin only reads its yml at enable time, so
echo   copying now leaves the file on disk out of step with what the server has loaded.
echo   Stop the network first:  launch\stop-all.cmd
if defined DRYRUN (
    echo   [DRY  ] a real run would ABORT here. Continuing so the rest of the plan is visible.
) else (
    exit /b 1
)
goto after_check
:stopped
echo   [ OK  ] no backend looks alive.
:after_check
echo.

REM ---- 2/3  TrinityForge yml -------------------------------------------------------------------
echo --- 2/3  TrinityForge yml ---
set "TFRES=%TF_REPO%\TrinityForge\src\main\resources"
set "TFDST=%VELOCITY_ROOT%\%TF_CONFIG_HOST%\plugins\TrinityForge"
if not exist "%TFDST%\" (
    echo   [SKIP ] %TFDST% does not exist
    goto ars
)
if defined DRYRUN (
    echo   [DRY  ] would copy *.yml recursively, once
    echo              from %TFRES%
    echo              to   %TFDST%
    echo              excluding paper-plugin.yml %SKIP_TF%
    goto ars
)
robocopy "%TFRES%" "%TFDST%" *.yml /S /XF paper-plugin.yml %SKIP_TF% /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
    echo   [ERROR] TrinityForge config copy failed. robocopy exit=%errorlevel%
    exit /b 1
)
echo   [ OK  ] copied to %TF_CONFIG_HOST% -- plugins\TrinityForge is a junction on the others
echo   [ NOTE] left alone on the server: %SKIP_TF%

REM ---- 3/3  ArsPaper yml, once per backend -----------------------------------------------------
:ars
echo.
echo --- 3/3  ArsPaper yml ---
set "ARSRES=%TF_REPO%\fork-handoff\arspaper\fork\src\main\resources"
if not exist "%ARSRES%\" (
    echo   [SKIP ] fork sources are gitignored and absent here: %ARSRES%
    goto done
)
for %%B in (%TF_BACKENDS%) do call :copy_ars "%%B"

:done
echo.
echo ============================================================
if defined DRYRUN (
    echo  DRY RUN finished. Nothing was copied.
) else (
    echo  Config deployed. Start the network:  launch\start-all.cmd
)
echo ============================================================
exit /b 0

REM ---------------------------------------------------------------------------------------------
:copy_ars
set "ARSDST=%VELOCITY_ROOT%\%~1\plugins\ArsPaper"
if not exist "%ARSDST%\" (
    echo   [SKIP ] %~1 has no plugins\ArsPaper
    goto :eof
)
if defined DRYRUN (
    echo   [DRY  ] %~1: would copy *.yml excluding paper-plugin.yml sourcejars.yml sourcelinks.yml
    goto :eof
)
robocopy "%ARSRES%" "%ARSDST%" *.yml /XF paper-plugin.yml sourcejars.yml sourcelinks.yml /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
    echo   [ERROR] %~1: ArsPaper config copy failed. robocopy exit=%errorlevel%
    exit /b 1
)
echo   [ OK  ] %~1
goto :eof

REM ---------------------------------------------------------------------------------------------
REM  A backend is "alive" when its world\session.lock cannot be opened for writing.
:check_running
set "LOCK=%VELOCITY_ROOT%\%~1\world\session.lock"
if not exist "%LOCK%" goto :eof
2>nul (call ) >>"%LOCK%" || (
    echo   [ALIVE] %~1
    set "RUNNING=1"
)
goto :eof

:usage
echo Usage: deploy-config-skip-wip.cmd [--dry-run^|--help]
exit /b 0
