@echo off
setlocal
REM =============================================================================================
REM  Build TrinityForge and both forks WHEN THEIR SOURCES CHANGED, then copy the jars onto every
REM  backend listed in TF_BACKENDS.
REM
REM  Usage:
REM    deploy.cmd                 build what changed, then deploy to every backend
REM    deploy.cmd --dry-run       print the plan. Builds nothing, copies nothing, deletes nothing.
REM    deploy.cmd --build-only    build what changed and stop. Touches no server file.
REM    deploy.cmd --config        also copy the repository yml over the deployed config
REM    deploy.cmd --restart       stop the network, deploy, start it again
REM    deploy.cmd --force         deploy even though a backend is running -- READ THE WARNING
REM    deploy.cmd --help
REM
REM  ABORTS WHILE ANY BACKEND IS RUNNING. Overwriting a jar under a live JVM raises
REM  NoClassDefFoundError the moment it needs a class it has not loaded yet; /reload does not fix
REM  it and a full stop -> start is the only way back.
REM
REM  Runs from the repository copy and from the deployed copy alike: every SOURCE path comes from
REM  TF_REPO in launch-config.cmd, never from %~dp0. Sibling scripts (stop-all / start-all) come
REM  from %~dp0 so the deployed copy calls its own neighbours.
REM
REM  This ships the plugin JARS. To ship the launch SCRIPTS themselves, use deploy-launch.cmd.
REM
REM  ASCII ONLY -- see launch-config.cmd. cmd.exe mis-parses UTF-8 batch files and starts executing
REM  the middle of a line. The Japanese explanation lives in ops\RUNBOOK.md, step 13-5.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

REM  Capture our own directory BEFORE parsing options. Plain `shift` renumbers %0 as well, so
REM  %~dp0 stops pointing at this script as soon as one option has been consumed -- it starts
REM  resolving the option text against the current directory instead. SELF keeps a trailing "\".
set "SELF=%~dp0"

set "DRYRUN="
set "FORCE="
set "WITH_CONFIG="
set "RESTART="
set "BUILD_ONLY="
set "BADARG="
set "FAILED="
set "BUILT=0"
set "SKIPPED=0"

REM ---- options ---------------------------------------------------------------------------------
:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--dry-run" (
    set "DRYRUN=1"
    shift /1
    goto parse
)
if /i "%~1"=="--force" (
    set "FORCE=1"
    shift /1
    goto parse
)
if /i "%~1"=="--config" (
    set "WITH_CONFIG=1"
    shift /1
    goto parse
)
if /i "%~1"=="--restart" (
    set "RESTART=1"
    shift /1
    goto parse
)
if /i "%~1"=="--build-only" (
    set "BUILD_ONLY=1"
    shift /1
    goto parse
)
if /i "%~1"=="--help" goto usage
if /i "%~1"=="-h" goto usage
if /i "%~1"=="/?" goto usage
echo [ERROR] Unknown option: %~1
set "BADARG=1"
goto usage

:usage
echo.
echo   deploy.cmd [--dry-run] [--build-only] [--config] [--restart] [--force]
echo.
echo     --dry-run     print the plan only. No build, no copy, no delete.
echo     --build-only  build what changed, then stop. No server file is touched.
echo     --config      also copy the repository yml over the deployed config.
echo     --restart     stop the whole network, deploy, then start it again.
echo     --force       deploy even though a backend is running. Breaks the live JVM.
echo.
if defined BADARG exit /b 1
exit /b 0

:parsed

REM ---- sources and artifacts -------------------------------------------------------------------
REM  Watched trees are src\main only: releaseAssembly does not run tests, so a test-only edit
REM  cannot change the deployable and must not trigger a rebuild. libs\TrinityForge.jar IS watched
REM  for both forks -- that jar is the compileOnly TF ABI, so a fresh one has to force a rebuild.

set "TF_DIR=%TF_REPO%\TrinityForge"
set "TF_ART=%TF_DIR%\build\release\TrinityForge-all.jar"
set "TF_THIN=%TF_DIR%\build\libs\TrinityForge-0.1.0-SNAPSHOT-thin.jar"
set "TF_WATCH=%TF_DIR%\src\main;%TF_DIR%\build.gradle.kts;%TF_DIR%\gradle.properties"

set "ARS_DIR=%TF_REPO%\fork-handoff\arspaper\fork"
set "ARS_ART=%ARS_DIR%\build\libs\ArsPaper-1.0.0.jar"
set "ARS_WATCH=%ARS_DIR%\src\main;%ARS_DIR%\build.gradle.kts;%ARS_DIR%\gradle.properties;%ARS_DIR%\libs\TrinityForge.jar"

set "EM_DIR=%TF_REPO%\fork-handoff\elitemobs\elitemobs-fork"
REM  The deployable is the shadowJar uberjar in testbed\plugins, NOT build\libs\*-min.jar. The min
REM  jar has MagmaCore stripped out and dies at load with
REM  NoClassDefFoundError: com/magmaguy/magmacore/location/DungeonLocator.
set "EM_ART=%EM_DIR%\testbed\plugins\EliteMobs.jar"
set "EM_WATCH=%EM_DIR%\src\main;%EM_DIR%\build.gradle;%EM_DIR%\libs\TrinityForge.jar"

echo.
echo ============================================================
if defined DRYRUN echo  TrinityForge deploy -- DRY RUN, nothing is written
if not defined DRYRUN echo  TrinityForge deploy
echo ============================================================
echo   repo        : %TF_REPO%
echo   backends    : %TF_BACKENDS%
echo   config host : %TF_CONFIG_HOST%
echo   jdk         : %JDK21_HOME%
echo.

if not exist "%JDK21_HOME%\bin\java.exe" (
    echo [ERROR] JDK 21 not found: %JDK21_HOME%\bin\java.exe
    echo         Fix JDK21_HOME in launch-config.cmd. Gradle 8.x cannot run on a newer JDK -- it
    echo         dies printing only the version number.
    exit /b 1
)
if not exist "%TF_DIR%\gradlew.bat" (
    echo [ERROR] Not a TrinityForge checkout: %TF_DIR%\gradlew.bat is missing.
    echo         Fix TF_REPO in launch-config.cmd.
    exit /b 1
)

REM ---- 1. is anything running ------------------------------------------------------------------
echo --- 1/5  are the backends stopped ---
if defined BUILD_ONLY goto s1_buildonly
if defined RESTART goto s1_restart
goto s1_check

:s1_buildonly
echo   [SKIP ] --build-only: no server file will be touched.
goto build

:s1_restart
if defined DRYRUN goto s1_restart_dry
echo   --restart: stopping the whole network first.
call "%SELF%stop-all.cmd"
if errorlevel 1 goto s1_stopfail
set "WAITED=0"
:s1_waitdown
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\check-servers-stopped.ps1" -Quiet
if not errorlevel 1 goto s1_down
if %WAITED% GEQ 300 goto s1_waitfail
%SystemRoot%\System32\timeout.exe /t 10 /nobreak >nul
set /a WAITED+=10
goto s1_waitdown
:s1_down
echo   [ OK  ] every backend is down.
goto build
:s1_stopfail
echo   [ERROR] stop-all.cmd failed. Nothing was built and nothing was copied.
exit /b 1
:s1_waitfail
echo   [ERROR] a backend is still running after %WAITED%s. Nothing was copied.
echo           Stop it from its own console, then run this again.
exit /b 1
:s1_restart_dry
echo   [DRY  ] would run stop-all.cmd, wait for every backend to go down, deploy, then start-all.cmd
goto build

:s1_check
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\check-servers-stopped.ps1"
if not errorlevel 1 goto build
if defined DRYRUN goto s1_dry_note
if not defined FORCE goto s1_abort
echo.
echo   !! --force: continuing while a backend is RUNNING !!
echo   Every jar replaced now raises NoClassDefFoundError inside that live JVM as soon as it
echo   reaches a class it has not loaded yet. /reload does not repair it. If the failure lands in
echo   the progression save path, nothing is persisted until the JVM is restarted.
echo   YOU MUST fully stop and start all backends after this run.
echo.
goto build
:s1_dry_note
echo   [DRY  ] a real run would ABORT here. Continuing so the rest of the plan is visible.
goto build
:s1_abort
echo.
echo [ABORT] Nothing was built and nothing was copied.
echo         Stop the network first, then run this again:
echo           %SELF%stop-all.cmd
echo         Or pass --restart to stop, deploy and start in one go.
exit /b 1

REM ---- 2. build what changed -------------------------------------------------------------------
:build
echo.
echo --- 2/5  build what changed ---

call :stale "%TF_ART%" "%TF_WATCH%" TF_STALE
if not defined TF_STALE goto b_tf_skip
echo   [BUILD] TrinityForge   trigger: %STALE_WHY%
call :build_gradle "%TF_DIR%" "releaseAssembly"
if errorlevel 1 goto fail
set /a BUILT+=1
goto b_ars
:b_tf_skip
echo   [SKIP ] TrinityForge   %STALE_WHY%
set /a SKIPPED+=1

REM  releaseAssembly copies the TF thin jar into every fork's libs\ as TrinityForge.jar. When TF was
REM  NOT rebuilt that copy can still be stale (someone ran plain `gradlew jar`), so compare content
REM  and refresh BEFORE building a fork -- otherwise the fork compiles against an old TF ABI and
REM  either fails to build or explodes at runtime.
:b_ars
if not exist "%ARS_DIR%\gradlew.bat" goto b_ars_absent
call :sync_libs "%TF_THIN%" "%ARS_DIR%\libs" "ArsPaper "
if errorlevel 1 goto fail
call :stale "%ARS_ART%" "%ARS_WATCH%" ARS_STALE
if not defined ARS_STALE goto b_ars_skip
echo   [BUILD] ArsPaper       trigger: %STALE_WHY%
call :build_gradle "%ARS_DIR%" "jar"
if errorlevel 1 goto fail
set /a BUILT+=1
goto b_em
:b_ars_skip
echo   [SKIP ] ArsPaper       %STALE_WHY%
set /a SKIPPED+=1
goto b_em
:b_ars_absent
echo   [NOTE ] ArsPaper       fork source is absent, skipping build and deploy:
echo                          %ARS_DIR%
echo                          .gitignore excludes it, so clones and git worktrees do not have it.
set "ARS_ART="

:b_em
if not exist "%EM_DIR%\gradlew.bat" goto b_em_absent
call :sync_libs "%TF_THIN%" "%EM_DIR%\libs" "EliteMobs"
if errorlevel 1 goto fail
call :stale "%EM_ART%" "%EM_WATCH%" EM_STALE
if not defined EM_STALE goto b_em_skip
echo   [BUILD] EliteMobs      trigger: %STALE_WHY%
call :build_gradle "%EM_DIR%" "shadowJar"
if errorlevel 1 goto fail
set /a BUILT+=1
goto b_done
:b_em_skip
echo   [SKIP ] EliteMobs      %STALE_WHY%
set /a SKIPPED+=1
goto b_done
:b_em_absent
echo   [NOTE ] EliteMobs      fork source is absent, skipping build and deploy:
echo                          %EM_DIR%
set "EM_ART="

:b_done
echo.
echo   built %BUILT%, skipped %SKIPPED%.

REM ---- 3. deploy the jars ----------------------------------------------------------------------
REM  Jars first, config afterwards. If a single jar fails to copy we stop before touching any yml,
REM  so the servers never come up with config that expects code they do not have.
echo.
echo --- 3/5  deploy jars ---
if defined BUILD_ONLY goto s3_skip
call :deploy_jar "TrinityForge" "%TF_ART%" "TrinityForge*.jar"
if defined FAILED goto fail
call :deploy_jar "ArsPaper" "%ARS_ART%" "ArsPaper*.jar"
if defined FAILED goto fail
call :deploy_jar "EliteMobs" "%EM_ART%" "EliteMobs*.jar"
if defined FAILED goto fail
goto s4
:s3_skip
echo   [SKIP ] --build-only.

REM ---- 4. deploy the config, only when asked ---------------------------------------------------
:s4
echo.
echo --- 4/5  deploy config ---
if defined BUILD_ONLY goto s4_skip_bo
if defined WITH_CONFIG goto s4_run
echo   [SKIP ] pass --config to copy the repository yml as well.
echo           Off by default because plugins\ArsPaper\sourcejars.yml and sourcelinks.yml are LIVE
echo           STATE written by the running server, and because the config editor already mirrors
echo           yml when you save. See ops\RUNBOOK.md step 13-5.
goto s5
:s4_skip_bo
echo   [SKIP ] --build-only.
goto s5
:s4_run
call :deploy_config
if defined FAILED goto fail

REM ---- 5. restart -------------------------------------------------------------------------------
:s5
echo.
echo --- 5/5  restart ---
if defined BUILD_ONLY goto s5_skip_bo
if not defined RESTART goto s5_manual
if defined DRYRUN goto s5_dry
call "%SELF%start-all.cmd"
if errorlevel 1 goto s5_startfail
goto done
:s5_dry
echo   [DRY  ] would run start-all.cmd
goto done
:s5_manual
echo   [SKIP ] not restarting. New classes only load on a full JVM restart:
echo             %SELF%start-all.cmd
goto done
:s5_skip_bo
echo   [SKIP ] --build-only.
goto done
:s5_startfail
echo   [ERROR] start-all.cmd failed, but the jars are already in place.
echo           Fix the startup problem and start again -- do not re-run the deploy.
exit /b 1

:done
call :warn_launch_drift
echo.
if defined DRYRUN goto done_dry
echo ============================================================
echo  Done. built %BUILT%, skipped %SKIPPED%.
echo ============================================================
if not defined BUILD_ONLY echo   Then check the logs: %SELF%testkit\check-logs.cmd
exit /b 0
:done_dry
echo ============================================================
echo  DRY RUN finished. Nothing was built, copied or deleted.
echo ============================================================
exit /b 0

:fail
echo.
echo ============================================================
echo  FAILED. Stopping here.
echo ============================================================
echo   Anything already copied stays copied. Do not start the servers on a half-deployed set:
echo   fix the failure, run this again, and start only after it reports Done.
exit /b 1

REM =============================================================================================
REM  subroutines
REM =============================================================================================

REM ---------------------------------------------------------------------------------------------
REM  :stale <artifact> <semicolon-separated watched paths> <out var>
REM    Sets <out var> to 1 when the artifact is missing or older than the newest file under any
REM    watched path, clears it otherwise, and leaves the reason in STALE_WHY.
REM
REM    Timestamps only -- no content hash, no dependency graph. Limits are spelled out in
REM    ops\RUNBOOK.md: restoring an older copy of a file, or a clock that jumps backwards, reads as
REM    "unchanged". Gradle still runs its own up-to-date checks, so a needless BUILD only costs
REM    seconds; a needless SKIP is the harmful one, and only those two cases produce it.
REM ---------------------------------------------------------------------------------------------
:stale
set "%~3=1"
set "STALE_WHY=no jar yet"
if not exist "%~1" goto :eof
set "STALE_OUT="
set "STALE_WHY=could not tell -- powershell failed"
for /f "usebackq tokens=1,* delims=|" %%A in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$a=(Get-Item -LiteralPath '%~1').LastWriteTimeUtc; $p=@('%~2'.Split(';')) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }; if(-not $p){'FRESH|no watched path exists'; exit}; $n=Get-ChildItem -LiteralPath $p -Recurse -File -Force -ErrorAction SilentlyContinue | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1; if(-not $n){'FRESH|no source file found'}elseif($n.LastWriteTimeUtc -gt $a){'STALE|'+$n.FullName}else{'FRESH|nothing newer than the jar'}"`) do (
    set "STALE_OUT=%%A"
    set "STALE_WHY=%%B"
)
if /i "%STALE_OUT%"=="FRESH" set "%~3="
goto :eof

REM ---------------------------------------------------------------------------------------------
REM  :build_gradle <project dir> <task>
REM    JAVA_HOME is set per invocation. -Dorg.gradle.java.home on its own does NOT switch the JDK
REM    for the ArsPaper fork (measured); setting both is harmless for the other two. The
REM    machine-wide JAVA_HOME is deliberately left alone -- other tools depend on it.
REM ---------------------------------------------------------------------------------------------
:build_gradle
if defined DRYRUN (
    echo           would run: gradlew.bat %~2 --offline    in %~1
    goto :eof
)
pushd "%~1" || (
    echo   [ERROR] cannot enter %~1
    exit /b 1
)
setlocal
set "JAVA_HOME=%JDK21_HOME%"
set "PATH=%JDK21_HOME%\bin;%PATH%"
call gradlew.bat %~2 --offline "-Dorg.gradle.java.home=%JDK21_HOME%"
set "RC=%ERRORLEVEL%"
endlocal & set "RC=%RC%"
popd
if not "%RC%"=="0" (
    echo   [ERROR] gradle %~2 failed in %~1   exit=%RC%
    echo           A bare version number as the whole error means the wrong JDK -- check JDK21_HOME.
    exit /b 1
)
goto :eof

REM ---------------------------------------------------------------------------------------------
REM  :sync_libs <TF thin jar> <fork libs dir> <label>
REM    Refresh the fork's compileOnly TF jar when its CONTENT differs. Compared by hash, not by
REM    timestamp: releaseAssembly rewrites that file on every run, so a timestamp compare would
REM    copy every single time and the answer would carry no information.
REM ---------------------------------------------------------------------------------------------
:sync_libs
set "SL_DST=%~2\TrinityForge.jar"
if not exist "%~1" goto sl_nothin
if not exist "%~2\" goto sl_nodir
set "SL_CMP="
for /f "usebackq delims=" %%A in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$d='%SL_DST%'; if(-not (Test-Path -LiteralPath $d)){'DIFF'; exit}; if((Get-FileHash -LiteralPath '%~1' -Algorithm SHA256).Hash -eq (Get-FileHash -LiteralPath $d -Algorithm SHA256).Hash){'SAME'}else{'DIFF'}"`) do set "SL_CMP=%%A"
if /i "%SL_CMP%"=="SAME" goto :eof
if not defined SL_CMP goto sl_hashfail
if defined DRYRUN goto sl_dry
copy /y "%~1" "%SL_DST%" >nul
if errorlevel 1 goto sl_copyfail
echo   [SYNC ] %~3      libs\TrinityForge.jar refreshed from the TF thin jar
goto :eof
:sl_dry
echo   [DRY  ] %~3      would refresh libs\TrinityForge.jar from the TF thin jar
goto :eof
:sl_nothin
echo   [NOTE ] %~3      no TF thin jar built yet, leaving libs\TrinityForge.jar alone
goto :eof
:sl_nodir
echo   [NOTE ] %~3      no libs directory, skipping the TF jar refresh
goto :eof
:sl_hashfail
echo   [ERROR] %~3      could not hash libs\TrinityForge.jar
exit /b 1
:sl_copyfail
echo   [ERROR] %~3      could not refresh libs\TrinityForge.jar
exit /b 1

REM ---------------------------------------------------------------------------------------------
REM  :deploy_jar <label> <source jar> <destination glob>
REM    Walks TF_BACKENDS. The destination file NAME comes from what is already installed, never
REM    from the source: the staged artifact is TrinityForge-all.jar while the installed file is
REM    TrinityForge-0.1.0-SNAPSHOT-all.jar, and copying under the source name would leave two jars
REM    of the same plugin in plugins\ -- Paper then refuses to start with "Ambiguous plugin name"
REM    (that has happened here before, with ArsPaper).
REM
REM    A backend with no such jar is SKIPPED, never given a new one. EliteMobs is deliberately
REM    absent from Resource_Server (ops\PLUGIN_MATRIX.md); installing it there would be a silent
REM    change of what that server runs.
REM ---------------------------------------------------------------------------------------------
:deploy_jar
if "%~2"=="" (
    echo   %~1: no artifact, skipping.
    goto :eof
)
if not exist "%~2" (
    echo   [ERROR] %~1: the artifact is missing after the build step: %~2
    set "FAILED=1"
    goto :eof
)
echo   %~1  from %~2
for %%B in (%TF_BACKENDS%) do call :deploy_jar_one "%~1" "%~2" "%~3" "%%B"
goto :eof

:deploy_jar_one
if defined FAILED goto :eof
set "PLUGDIR=%VELOCITY_ROOT%\%~4\plugins"
if not exist "%PLUGDIR%\" (
    echo     [SKIP ] %~4: no plugins directory
    goto :eof
)
set "HITS=0"
set "TARGET="
for /f "delims=" %%F in ('dir /b /a-d "%PLUGDIR%\%~3" 2^>nul') do (
    set /a HITS+=1
    set "TARGET=%%F"
)
if "%HITS%"=="0" (
    echo     [SKIP ] %~4: %~1 is not installed here
    goto :eof
)
if %HITS% GTR 1 (
    echo     [ERROR] %~4: %HITS% files match %~3 -- Paper would say "Ambiguous plugin name".
    echo             Remove the stale ones by hand, then run this again.
    set "FAILED=1"
    goto :eof
)
if defined DRYRUN (
    echo     [DRY  ] %~4: would overwrite %TARGET%
    goto :eof
)
copy /y "%~2" "%PLUGDIR%\%TARGET%" >nul
if errorlevel 1 (
    echo     [ERROR] %~4: copy failed. Is that server still running and holding the jar?
    set "FAILED=1"
    goto :eof
)
echo     [ OK  ] %~4: %TARGET%
REM  Paper caches a remapped copy of legacy plugin.yml plugins. Drop it so the new jar gets remapped
REM  instead of the old cache being reused. Absent for paper-plugin.yml plugins, which is fine.
if exist "%PLUGDIR%\.paper-remapped\%TARGET%" (
    del /q "%PLUGDIR%\.paper-remapped\%TARGET%" >nul 2>&1
    echo              cleared .paper-remapped\%TARGET%
)
goto :eof

REM ---------------------------------------------------------------------------------------------
REM  :deploy_config
REM    TF yml goes to TF_CONFIG_HOST ONCE: plugins\TrinityForge is an NTFS junction to it on the
REM    other backends. plugins\ArsPaper is a real directory on every backend, so that one needs a
REM    copy per backend.
REM
REM    Left out on purpose:
REM      paper-plugin.yml   plugin descriptor, not config
REM      sourcejars.yml     live state: source jar block coordinates written by the running server
REM      sourcelinks.yml    live state: source link block coordinates written by the running server
REM    Overwriting the last two points the live world at blocks that are somewhere else.
REM    Nothing at the destination is ever deleted, so yml the plugin generates itself survives.
REM ---------------------------------------------------------------------------------------------
:deploy_config
set "TFRES=%TF_DIR%\src\main\resources"
set "TFDST=%VELOCITY_ROOT%\%TF_CONFIG_HOST%\plugins\TrinityForge"
if not exist "%TFDST%\" goto cfg_tf_absent
if defined DRYRUN goto cfg_tf_dry
robocopy "%TFRES%" "%TFDST%" *.yml /S /XF paper-plugin.yml /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 goto cfg_tf_fail
echo   [ OK  ] TrinityForge: yml copied to %TF_CONFIG_HOST% -- the junction carries it to the rest
goto cfg_ars
:cfg_tf_dry
echo   [DRY  ] TrinityForge: would copy *.yml recursively, once
echo              from %TFRES%
echo              to   %TFDST%
goto cfg_ars
:cfg_tf_fail
echo   [ERROR] TrinityForge config copy failed. robocopy exit=%errorlevel%
set "FAILED=1"
goto :eof
:cfg_tf_absent
echo   [SKIP ] TrinityForge: %TFDST% does not exist

:cfg_ars
if "%ARS_ART%"=="" goto :eof
for %%B in (%TF_BACKENDS%) do call :deploy_config_ars "%%B"
goto :eof

:deploy_config_ars
if defined FAILED goto :eof
set "ARSDST=%VELOCITY_ROOT%\%~1\plugins\ArsPaper"
if not exist "%ARSDST%\" (
    echo   [SKIP ] ArsPaper: %~1 has no plugins\ArsPaper
    goto :eof
)
if defined DRYRUN (
    echo   [DRY  ] ArsPaper: would copy *.yml to %ARSDST%
    goto :eof
)
robocopy "%ARS_DIR%\src\main\resources" "%ARSDST%" *.yml /XF paper-plugin.yml sourcejars.yml sourcelinks.yml /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 goto cfg_ars_fail
echo   [ OK  ] ArsPaper: yml copied to %~1
goto :eof
:cfg_ars_fail
echo   [ERROR] ArsPaper config copy failed for %~1. robocopy exit=%errorlevel%
set "FAILED=1"
goto :eof

REM ---------------------------------------------------------------------------------------------
REM  :warn_launch_drift
REM    When run from the deployed copy, say so if the repository's launch scripts are newer.
REM    Editing the deployed copy is pointless -- deploy-launch.cmd overwrites it -- and the drift
REM    is otherwise silent.
REM ---------------------------------------------------------------------------------------------
:warn_launch_drift
set "HERE=%SELF%"
if "%HERE:~-1%"=="\" set "HERE=%HERE:~0,-1%"
if /i "%HERE%"=="%TF_REPO%\ops\launch" goto :eof
call :stale "%HERE%\deploy.cmd" "%TF_REPO%\ops\launch" LAUNCH_DRIFT
if not defined LAUNCH_DRIFT goto :eof
echo.
echo   [NOTE ] The repository's launch scripts are newer than this deployed copy.
echo           Refresh them with: %TF_REPO%\ops\launch\deploy-launch.cmd
goto :eof
