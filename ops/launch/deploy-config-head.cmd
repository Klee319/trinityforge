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
REM    deploy-config-head.cmd                     copy the committed yml
REM    deploy-config-head.cmd --dry-run           print the plan. Copies nothing.
REM    deploy-config-head.cmd --only <rel\path>   copy ONE TrinityForge yml (skips ArsPaper).
REM
REM  Why --only exists (2026-08-18)
REM    "The committed state" is the right unit only when everything committed is meant to ship.
REM    In practice HEAD also carries OTHER sessions' finished-but-not-yet-deployed work, and a
REM    full copy ships all of it at once. Real case: deploying one level-cutoff key would have
REM    dragged along stats\item-stats.yml (a whole weapon rebalance), stats\skill-exp.yml and
REM    skills\base\farming_progression.yml, none of which the operator had asked for yet.
REM    --only narrows the copy to a single file so an unrelated change cannot ride along.
REM    The path is relative to the TrinityForge config root, e.g.  --only combat\damage.yml
REM
REM  Server-side edits (2026-08-19 W-110, extended 2026-08-20 W-177)
REM    Deployment is one-way repo -> server, and the W-106 overlay protects the REPOSITORY working
REM    tree only. The config editor has no screen for every yml (network.yml, for one), so editing
REM    the deployed file by hand is sometimes the only option -- and that edit used to vanish here
REM    silently, with no backup. Step 2/4 compares the deployed yml against a manifest taken at
REM    the end of the previous deploy, backs up whatever changed since, AND copies it back into
REM    the repository so the very next step ships it instead of reverting it. It never blocks.
REM
REM    W-110 only backed the drift up. The server copy was still overwritten, so from a player's
REM    seat the setting had simply rolled back -- and the same report came in again on 2026-08-20.
REM    Copying it back into the repository is what actually closes the loop, and it is why step
REM    2/4 now runs BEFORE the HEAD export instead of after it.
REM
REM  This deploys CONFIG ONLY. Jars are deploy.cmd's job (run it WITHOUT --config).
REM
REM  ABORTS WHILE ANY BACKEND IS RUNNING. A plugin reads its yml at enable time, so copying under
REM  a live JVM does nothing useful and leaves the on-disk config out of step with what is loaded.
REM  Stop the network first: launch\stop-all.cmd
REM
REM  Exclusions:
REM    paper-plugin.yml   plugin descriptor, not config
REM  Nothing at the destination is ever deleted.
REM
REM  2026-08-08 CORRECTION -- sourcejars.yml and sourcelinks.yml used to be excluded here as
REM  "live state: block coordinates written by the running server". THAT WAS WRONG and it cost
REM  us the whole source ladder: the upper source links (II-V), the upper jars, and the burn
REM  values of the ladder catalysts were added to the fork in 2026-08-02..04 and NEVER reached
REM  any backend, because this script refused to copy exactly those two files.
REM  Proof they are read-only definition files:
REM    SourceJarConfig / SourcelinkConfig only loadConfiguration() them and saveResource(name,false).
REM    The runtime block/link state lives in a DIFFERENT file, source-network.yml, written by
REM    SourceNetwork#saveSnapshot. Block identity itself is in each block's PDC, not in any yml.
REM  source-network.yml is not part of the plugin's resources, so it is never staged and never
REM  copied -- there is nothing to exclude.
REM
REM  Note also that the plugin cannot repair this by itself: ArsPaper#updateResourceFiles only
REM  re-extracts its bundled yml when the plugin VERSION STRING changes, and the fork has been
REM  0.1.0-SNAPSHOT throughout, so that gate has never once opened. This script is the only
REM  path by which an ArsPaper yml change reaches a server.
REM
REM  ASCII ONLY -- cmd.exe mis-parses UTF-8 batch files and starts executing the middle of a line.
REM =============================================================================================
REM  Capture the script directory BEFORE any shift: shift moves %0 too, so %~dp0 stops being
REM  this script's folder as soon as an option is consumed.
set "SELF=%~dp0"
call "%SELF%launch-config.cmd" || exit /b 1

set "DRYRUN="
set "ONLY_REL="
set "HEADONLY="
:parse_args
if "%~1"=="" goto parsed_args
if /i "%~1"=="--dry-run" (set "DRYRUN=1" & shift /1 & goto parse_args)
if /i "%~1"=="--head-only" (set "HEADONLY=1" & shift /1 & goto parse_args)
if /i "%~1"=="--help" goto usage
if /i "%~1"=="-h" goto usage
if /i "%~1"=="--only" (
    if "%~2"=="" (
        echo   [ERROR] --only needs a path relative to the TrinityForge config root, e.g. combat\damage.yml
        exit /b 1
    )
    set "ONLY_REL=%~2"
    shift /1
    shift /1
    goto parse_args
)
echo   [ERROR] unknown option: %~1
goto usage
:parsed_args

set "STAGE=%TF_REPO%\tmp\deploy-head"
set "TFRES=%STAGE%\tf\TrinityForge\src\main\resources"
set "ARSRES=%STAGE%\ars\src\main\resources"
set "TFDST=%VELOCITY_ROOT%\%TF_CONFIG_HOST%\plugins\TrinityForge"

echo ============================================================
if defined HEADONLY (
    echo  deploy config from HEAD ^(committed state only^)
) else (
    echo  deploy config: HEAD + working tree yml ^(editor edits win^)
)
echo ============================================================
echo   repo        : %TF_REPO%
echo   config host : %TF_CONFIG_HOST%
echo   backends    : %TF_BACKENDS%
if defined HEADONLY echo   source      : HEAD only -- uncommitted editor edits will NOT ship
if not defined HEADONLY echo   source      : HEAD, then the working tree yml layered on top
if defined ONLY_REL echo   scope       : ONLY %ONLY_REL% (TrinityForge; ArsPaper skipped)
echo.

REM ---- 1/4  are the backends stopped -----------------------------------------------------------
REM  Uses the same detector as deploy.cmd. Probing world\session.lock from cmd REPORTS A LIVE
REM  SERVER AS STOPPED: cmd opens the append handle with enough sharing that Paper's lock does not
REM  block it. check-servers-stopped.ps1 opens with FileShare.None and also looks at the RCON port.
echo --- 1/4  are the backends stopped ---
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

REM ---- 2/4  did anyone edit the DEPLOYED yml -----------------------------------------------------
REM  Deployment is one-way repo -> server. The W-106 overlay protects edits made in the REPOSITORY
REM  working tree, not edits made to the deployed file itself -- and the config editor has no
REM  screen for every yml, so "edit it on the server" is sometimes the only way. Those edits used
REM  to be overwritten silently and with no backup (2026-08-19, network.yml, W-110).
REM  guard-deployed-config.ps1 compares the deployed yml against a manifest taken at the end of the
REM  previous deploy, backs up anything that changed since, AND copies it back into the repository
REM  (W-177). It never blocks the deploy.
REM
REM  THIS RUNS BEFORE THE HEAD EXPORT ON PURPOSE. The export overlays the working tree on top of
REM  HEAD, so a file promoted here is picked up by the very next step and ships in THIS deploy.
REM  While it ran after the export (2026-08-19 to 2026-08-20) the edit was backed up but the
REM  server copy was still reverted, which is exactly what "my config rolled back" looks like.
echo --- 2/4  check the deployed config for server-side edits ---
set "GUARD_ARGS=-VelocityRoot "%VELOCITY_ROOT%" -ConfigHost "%TF_CONFIG_HOST%" -Backends "%TF_BACKENDS%""
if defined DRYRUN (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\guard-deployed-config.ps1" -Mode Check %GUARD_ARGS% -NoBackup
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\guard-deployed-config.ps1" -Mode Check %GUARD_ARGS%
)
echo.

REM ---- 3/4  export HEAD -------------------------------------------------------------------------
REM  Runs even under --dry-run: the export writes only inside tmp\ and its report of
REM  "uncommitted, therefore not deployed" is the main thing a dry run is for.
echo --- 3/4  export HEAD into tmp\deploy-head ---
if defined HEADONLY (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\export-head-config.ps1" -HeadOnly
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\export-head-config.ps1"
)
if errorlevel 1 (
    echo   [ERROR] HEAD export failed. Nothing was copied.
    exit /b 1
)
if not exist "%TFRES%\" (
    echo   [ERROR] expected staged config not found: %TFRES%
    exit /b 1
)
echo.

REM ---- 4/4  copy ------------------------------------------------------------------------------
REM  TF yml goes to TF_CONFIG_HOST ONCE: plugins\TrinityForge is an NTFS junction to it on the
REM  other backends (ops\scripts\setup-junction.cmd), so one write is seen by all three.
REM  plugins\ArsPaper is a real directory everywhere -- three copies.
echo --- 4/4  copy onto the deployed config ---
if not exist "%TFDST%\" (
    echo   [SKIP ] TrinityForge: %TFDST% does not exist
    goto ars
)
if defined ONLY_REL goto copy_one
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
goto ars

REM  --only <rel\path>: copy exactly one file. Sources come from the same HEAD export, so the
REM  "committed state only" guarantee is unchanged -- this just narrows what ships.
:copy_one
set "SRCFILE=%TFRES%\%ONLY_REL%"
set "DSTFILE=%TFDST%\%ONLY_REL%"
if not exist "%SRCFILE%" (
    echo   [ERROR] not present in HEAD: %ONLY_REL%
    echo              looked for %SRCFILE%
    exit /b 1
)
REM  A trailing backslash would escape the closing quote, so pass the directories as "<dir>\.".
for %%F in ("%SRCFILE%") do set "ONE_SRCDIR=%%~dpF"
for %%F in ("%SRCFILE%") do set "ONE_NAME=%%~nxF"
for %%F in ("%DSTFILE%") do set "ONE_DSTDIR=%%~dpF"
if defined DRYRUN (
    echo   [DRY  ] TrinityForge: would copy ONE file
    echo              from %SRCFILE%
    echo              to   %DSTFILE%
    goto ars
)
if not exist "%ONE_DSTDIR%" (
    echo   [ERROR] destination folder missing: %ONE_DSTDIR%
    exit /b 1
)
robocopy "%ONE_SRCDIR%." "%ONE_DSTDIR%." "%ONE_NAME%" /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
    echo   [ERROR] TrinityForge single-file copy failed. robocopy exit=%errorlevel%
    exit /b 1
)
echo   [ OK  ] TrinityForge: copied %ONLY_REL% to %TF_CONFIG_HOST% -- the junction carries it to the rest

:ars
echo.
if defined ONLY_REL (
    echo   [SKIP ] ArsPaper: --only names a TrinityForge path
    goto done
)
if not exist "%ARSRES%\" (
    echo   [SKIP ] ArsPaper: fork sources are gitignored and absent here
    goto done
)
for %%B in (%TF_BACKENDS%) do call :copy_ars "%%B"

:done
echo.
REM  Record what the deployed config looks like NOW, so the next run can tell "someone edited the
REM  server copy" apart from "the repository changed". Skipped under --dry-run, which copied
REM  nothing and must not move the baseline.
if not defined DRYRUN (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\guard-deployed-config.ps1" -Mode Record %GUARD_ARGS%
)
echo.
echo ============================================================
if defined DRYRUN (
    echo  DRY RUN finished. Nothing was copied.
) else (
    echo  Config deployed from HEAD. Start the network:  launch\start-all.cmd
)
if not defined DRYRUN (
    echo  Re-read step 2/4 above. Every [DRIFT] line was an edit made on the SERVER; each one that
    echo  also shows [PROMOTED] has been copied back into the repository and IS in this deploy --
    echo  commit it. Anything listed as not promoted was only backed up, under
    echo  tmp\deploy-config-backup, and the server copy has been overwritten.
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
    echo              excluding paper-plugin.yml
    exit /b 0
)
robocopy "%ARSRES%" "%ARSDST%" *.yml /XF paper-plugin.yml /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
    echo   [ERROR] ArsPaper config copy failed for %~1. robocopy exit=%errorlevel%
    exit /b 1
)
echo   [ OK  ] ArsPaper: copied to %~1
exit /b 0

REM ---------------------------------------------------------------------------------------------
:usage
echo Usage: deploy-config-head.cmd [--dry-run] [--head-only] [--only ^<rel\path^>]
echo.
echo   --head-only        ship the COMMITTED state only. Without it (the default since
echo                      2026-08-18, W-106) the working tree yml is layered on top of HEAD so
echo                      that edits made in the config editor are NOT rolled back. Every file
echo                      taken from the working tree is listed; check it for other sessions'
echo                      half-finished work before continuing.
echo.
echo   Copies the yml onto the deployed config, for TrinityForge and ArsPaper.
echo   Config only -- run deploy.cmd (without --config) for the jars.
echo   Aborts while any backend is running.
echo.
echo   --only ^<rel\path^>  copy ONE TrinityForge yml instead of the whole tree, e.g.
echo                        deploy-config-head.cmd --only combat\damage.yml
echo                      Use it when HEAD also carries other sessions' finished-but-not-yet
echo                      -wanted work that must not ride along. ArsPaper is skipped.
exit /b 0
