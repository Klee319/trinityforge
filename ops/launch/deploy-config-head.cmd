@echo off
setlocal
REM =============================================================================================
REM  Copy the COMMITTED (HEAD) yml onto the deployed config. Never the working tree.
REM
REM  Why this exists
REM    Several sessions share one working tree, so resources\*.yml almost always carries someone
REM    else's half-finished edit. Two earlier attempts both got it wrong:
REM
REM      deploy.cmd --config           copies the working tree -> ships other sessions' WIP.
REM      deploy-config-skip-wip.cmd    skips every uncommitted file -> when MY change and someone
REM                                    else's WIP share a file, MY change is skipped too. And it
REM                                    cannot see the ArsPaper fork at all: the fork sources are
REM                                    .gitignore'd in the outer repo, so git status returns
REM                                    nothing for them and the fork's WIP ships silently.
REM
REM    This script sidesteps both by defining what gets deployed as "the committed state":
REM    export-head-config.ps1 materialises HEAD (of the repo, and of the fork's own repo) into
REM    tmp\deploy-head, and we copy from there. Nobody's in-progress edit can leak in, and no
REM    committed change can be lost because it happens to share a file with one.
REM
REM    It also prints which yml differ from HEAD, so "what did NOT get deployed" is visible.
REM
REM  Usage:
REM    deploy-config-head.cmd                copy the committed yml
REM    deploy-config-head.cmd --dry-run      print the plan. Copies nothing.
REM
REM  This deploys CONFIG ONLY. Jars are deploy.cmd's job (run it WITHOUT --config).
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

set "DRYRUN="
if /i "%~1"=="--dry-run" set "DRYRUN=1"
if /i "%~1"=="--help" goto usage
if /i "%~1"=="-h" goto usage

set "STAGE=%TF_REPO%\tmp\deploy-head"
set "TFRES=%STAGE%\tf\TrinityForge\src\main\resources"
set "ARSRES=%STAGE%\ars\src\main\resources"
set "TFDST=%VELOCITY_ROOT%\%TF_CONFIG_HOST%\plugins\TrinityForge"

echo ============================================================
echo  deploy config from HEAD (committed state only)
echo ============================================================
echo   repo        : %TF_REPO%
echo   config host : %TF_CONFIG_HOST%
echo   backends    : %TF_BACKENDS%
echo.

REM ---- 1/3  are the backends stopped -----------------------------------------------------------
REM  Uses the same detector as deploy.cmd. Probing world\session.lock from cmd REPORTS A LIVE
REM  SERVER AS STOPPED: cmd opens the append handle with enough sharing that Paper's lock does not
REM  block it. check-servers-stopped.ps1 opens with FileShare.None and also looks at the RCON port.
echo --- 1/3  are the backends stopped ---
set "RUNNING="
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\check-servers-stopped.ps1"
if errorlevel 1 set "RUNNING=1"
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

REM ---- 2/3  export HEAD -------------------------------------------------------------------------
REM  Runs even under --dry-run: the export writes only inside tmp\ and its report of
REM  "uncommitted, therefore not deployed" is the main thing a dry run is for.
echo --- 2/3  export HEAD into tmp\deploy-head ---
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\export-head-config.ps1"
if errorlevel 1 (
    echo   [ERROR] HEAD export failed. Nothing was copied.
    exit /b 1
)
if not exist "%TFRES%\" (
    echo   [ERROR] expected staged config not found: %TFRES%
    exit /b 1
)
echo.

REM ---- 3/3  copy ------------------------------------------------------------------------------
REM  TF yml goes to TF_CONFIG_HOST ONCE: plugins\TrinityForge is an NTFS junction to it on the
REM  other backends (ops\scripts\setup-junction.cmd), so one write is seen by all three.
REM  plugins\ArsPaper is a real directory everywhere -- three copies.
echo --- 3/3  copy onto the deployed config ---
if not exist "%TFDST%\" (
    echo   [SKIP ] TrinityForge: %TFDST% does not exist
    goto ars
)
if defined DRYRUN (
    echo   [DRY  ] TrinityForge: would copy *.yml recursively, once
    echo              from %TFRES%
    echo              to   %TFDST%
    echo              excluding paper-plugin.yml
    goto ars
)
robocopy "%TFRES%" "%TFDST%" *.yml /S /XF paper-plugin.yml /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
    echo   [ERROR] TrinityForge config copy failed. robocopy exit=%errorlevel%
    exit /b 1
)
echo   [ OK  ] TrinityForge: copied to %TF_CONFIG_HOST% -- the junction carries it to the rest

:ars
echo.
if not exist "%ARSRES%\" (
    echo   [SKIP ] ArsPaper: fork sources are gitignored and absent here
    goto done
)
for %%B in (%TF_BACKENDS%) do call :copy_ars "%%B"

:done
echo.
echo ============================================================
if defined DRYRUN (
    echo  DRY RUN finished. Nothing was copied.
) else (
    echo  Config deployed from HEAD. Start the network:  launch\start-all.cmd
)
echo ============================================================
exit /b 0

REM ---------------------------------------------------------------------------------------------
:copy_ars
set "ARSDST=%VELOCITY_ROOT%\%~1\plugins\ArsPaper"
if not exist "%ARSDST%\" (
    echo   [SKIP ] ArsPaper: %~1 has no plugins\ArsPaper
    exit /b 0
)
if defined DRYRUN (
    echo   [DRY  ] ArsPaper: would copy *.yml to %ARSDST%
    echo              excluding paper-plugin.yml sourcejars.yml sourcelinks.yml
    exit /b 0
)
robocopy "%ARSRES%" "%ARSDST%" *.yml /XF paper-plugin.yml sourcejars.yml sourcelinks.yml /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
    echo   [ERROR] ArsPaper config copy failed for %~1. robocopy exit=%errorlevel%
    exit /b 1
)
echo   [ OK  ] ArsPaper: copied to %~1
exit /b 0

REM ---------------------------------------------------------------------------------------------
:usage
echo Usage: deploy-config-head.cmd [--dry-run]
echo.
echo   Copies the COMMITTED (HEAD) yml onto the deployed config, for TrinityForge and ArsPaper.
echo   Config only -- run deploy.cmd (without --config) for the jars.
echo   Aborts while any backend is running.
exit /b 0
