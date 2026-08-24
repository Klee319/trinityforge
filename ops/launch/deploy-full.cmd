@echo off
setlocal
REM =============================================================================================
REM  Build, deploy and restart everything a Bedrock-facing change needs, in one command.
REM
REM  Usage:
REM    deploy-full.cmd              show the plan, ask for confirmation, then do it
REM    deploy-full.cmd --dry-run    show the plan and stop. Builds nothing, copies nothing.
REM    deploy-full.cmd --yes        skip the confirmation prompt (unattended)
REM
REM  What it does, in this order:
REM    1. BUILD    GeyserExtra + TrinityForge  -- WHILE THE SERVERS ARE STILL UP.
REM    2. STOP     stop-all.cmd
REM    3. DEPLOY   GeyserExtra (3 backends + proxy extension), then TrinityForge (3 backends)
REM    4. START    start-all.cmd
REM
REM  Building BEFORE the stop is the point of this script. deploy.cmd builds after its own
REM  running-check, so a compile error there costs a stopped network; here it costs nothing.
REM
REM  ORDER IS NOT ARBITRARY: GeyserExtra goes before TrinityForge. TF writes
REM  bedrock-recipes.json at format version 2 and an older GeyserExtra collector accepts only 1
REM  -- it rejects the WHOLE table, so every corrected recipe disappears, not just the new ones.
REM  The reverse skew (GeyserExtra newer) accepts both, so this direction is the safe one.
REM
REM  NOT DONE HERE: config (yml). Use deploy-config-head.cmd for that -- mixing the two would
REM  ship whatever half-edited yml other sessions have in the working tree.
REM  NOT DONE HERE: the forks (ArsPaper / EliteMobs). Use deploy.cmd without --tf-only.
REM
REM  On any failure the network is left STOPPED on purpose. A half-deployed set of jars wants
REM  eyes on it, and the recovery is one command that this script prints.
REM
REM  ASCII ONLY -- see launch-config.cmd. cmd.exe mis-parses UTF-8 batch files and starts
REM  executing the middle of a line. The Japanese explanation lives in ops\scripts\deploy-full-plan.ps1.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

REM  Grab our own directory BEFORE parsing options. Plain `shift` renumbers %0 too, so %~dp0
REM  stops meaning "this script" as soon as one option has been consumed -- it starts resolving
REM  the option text against the current directory. `shift /1` leaves %0 alone; SELF is the
REM  belt to that braces and keeps a trailing "\".
set "SELF=%~dp0"

set "GEYSER_DEPLOY=%OPS_SCRIPTS%\deploy-geyserextra.ps1"
if not exist "%GEYSER_DEPLOY%" (
    echo [ERROR] not found: %GEYSER_DEPLOY%
    exit /b 1
)

REM ---- options ---------------------------------------------------------------------------------
REM  Translated rather than forwarded: the plan script is PowerShell, which binds -DryRun and
REM  would reject the --dry-run spelling this folder's scripts use.
set "PLAN_ARGS="
:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--dry-run" (
    set "PLAN_ARGS=%PLAN_ARGS% -DryRun"
    shift /1
    goto parse
)
if /i "%~1"=="--yes" (
    set "PLAN_ARGS=%PLAN_ARGS% -Yes"
    shift /1
    goto parse
)
if /i "%~1"=="--help" (
    set "USAGE_RC=0"
    goto usage
)
if /i "%~1"=="-h" (
    set "USAGE_RC=0"
    goto usage
)
echo [ERROR] unknown option: %~1
set "USAGE_RC=1"
goto usage
:parsed

REM ---- 1. plan + confirm -----------------------------------------------------------------------
REM  `if errorlevel N` means "N or higher", so the 2 test has to come before the 1 test.
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\deploy-full-plan.ps1"%PLAN_ARGS%
if errorlevel 2 exit /b 0
if errorlevel 1 exit /b 1

REM ---- 2. build, servers still up --------------------------------------------------------------
echo.
echo ============================================================
echo  [1/4] build  ^(servers still running^)
echo ============================================================
call :find_geyser_repo || exit /b 1
pushd "%GEYSER_REPO%" || exit /b 1
call gradlew.bat build --offline "-Dorg.gradle.java.home=%JDK21_HOME%"
set "RC=%errorlevel%"
popd
if not "%RC%"=="0" (
    echo.
    echo [ERROR] GeyserExtra build failed. Nothing was stopped and nothing was copied.
    exit /b 1
)

call "%SELF%deploy.cmd" --build-only --tf-only || (
    echo.
    echo [ERROR] TrinityForge build failed. Nothing was stopped and nothing was copied.
    exit /b 1
)

REM ---- 3. stop ---------------------------------------------------------------------------------
echo.
echo ============================================================
echo  [2/4] stop
echo ============================================================
call "%SELF%stop-all.cmd" || (
    echo.
    echo [ERROR] stop-all failed. Nothing was copied.
    exit /b 1
)

REM ---- 4. deploy: GeyserExtra FIRST, then TrinityForge ------------------------------------------
echo.
echo ============================================================
echo  [3/4] deploy  GeyserExtra
echo ============================================================
REM  -SkipBuild: step 1 already built it, and letting it rebuild here would run gradle without
REM  --offline. The script prints the artifact timestamps, so a stale artifact stays visible.
powershell -NoProfile -ExecutionPolicy Bypass -File "%GEYSER_DEPLOY%" -SkipBuild
if errorlevel 1 (
    echo.
    echo [ERROR] GeyserExtra deploy failed. TrinityForge was NOT touched.
    echo         The network is stopped. Start it with:  "%SELF%start-all.cmd"
    exit /b 1
)

echo.
echo ============================================================
echo  [3/4] deploy  TrinityForge
echo ============================================================
call "%SELF%deploy.cmd" --tf-only || (
    echo.
    echo [ERROR] TrinityForge deploy failed. GeyserExtra IS already updated, which is the safe
    echo         skew -- an older TF with a newer GeyserExtra loads fine.
    echo         The network is stopped. Start it with:  "%SELF%start-all.cmd"
    exit /b 1
)

REM ---- 5. start --------------------------------------------------------------------------------
echo.
echo ============================================================
echo  [4/4] start
echo ============================================================
call "%SELF%start-all.cmd" || exit /b 1

echo.
echo ============================================================
echo  done
echo ============================================================
echo  Confirm these lines appear before trusting the deploy:
echo    backend : [bedrock-recipes] ^<backend^>: N recipes from 2 plugin^(s^)
echo    proxy   : [bedrock-recipes] N corrected recipes loaded
echo  The backend line appears after TrinityForge and ArsPaper enable ^(up to a minute^).
echo.
echo  Bedrock clients pick the rebuilt pack up on their next connection; the pack patch
echo  version is a monotonic counter, so no cache clearing is needed.
exit /b 0

REM ---- helpers ---------------------------------------------------------------------------------
:usage
echo.
echo   deploy-full.cmd              show the plan, confirm, then build / stop / deploy / start
echo   deploy-full.cmd --dry-run    show the plan and stop
echo   deploy-full.cmd --yes        skip the confirmation prompt
echo.
echo   Deploys GeyserExtra ^(then^) TrinityForge and restarts the network.
echo   config ^(yml^) : deploy-config-head.cmd
echo   the forks     : deploy.cmd
exit /b %USAGE_RC%

REM  GeyserExtra lives in a SIBLING repository whose folder name ends in a Greek alpha. This file
REM  must stay ASCII, so the name is never written here -- the directory is found by pattern
REM  instead. Sibling of TF_REPO, starting with "geyserExtra".
:find_geyser_repo
set "GEYSER_REPO="
for /d %%D in ("%TF_REPO%\..\geyserExtra*") do set "GEYSER_REPO=%%~fD"
if not defined GEYSER_REPO (
    echo [ERROR] geyserExtra repository not found next to %TF_REPO%
    exit /b 1
)
if not exist "%GEYSER_REPO%\gradlew.bat" (
    echo [ERROR] not a gradle project: %GEYSER_REPO%
    exit /b 1
)
exit /b 0
