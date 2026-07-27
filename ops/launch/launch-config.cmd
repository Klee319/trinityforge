@echo off
REM =============================================================================================
REM  Shared definitions for the launch folder. Every start-*.cmd calls this first.
REM  CHANGE PATHS HERE ONLY -- do not scatter them across the other scripts.
REM
REM  ASCII ONLY. cmd.exe mis-parses UTF-8 batch files: multi-byte characters make it seek to
REM  the wrong byte offset and it starts executing the middle of a line. Japanese explanations
REM  live in README.md. (testkit\check-ops-scripts.cmd fails if non-ASCII creeps back in.)
REM
REM  find / timeout are spelled %SystemRoot%\System32\...exe on purpose. Git Bash (and anything
REM  else with GNU coreutils ahead on PATH) shadows both, and the GNU versions reject the
REM  Windows syntax -- the checks silently stop working. findstr has no GNU twin, so it is used
REM  in place of find.
REM
REM  The repository copy (ops\launch\) is authoritative; deploy.cmd copies it to
REM  D:\...\Velocity_for_TF\launch. See README.md.
REM =============================================================================================

set "VELOCITY_ROOT=D:\game\minecraft\PaperServer\Velocity_for_TF"
set "GARNET_HOME=D:\game\minecraft\Garnet"

REM Where the ops scripts live (preflight / server-loop / stop-network / ...).
REM Fix this after moving the repository.
set "TF_REPO=C:\Users\T-319\Documents\Program\ClaudeCodeDev\products\minecraft\trinityforge"
set "OPS_SCRIPTS=%TF_REPO%\ops\scripts"

REM jar file names. Update here when you swap a build in.
set "PAPER_JAR=paper-1.21.11-132.jar"
set "VELOCITY_JAR=velocity-4.1.0-SNAPSHOT-9.jar"

REM Heap sizes. Keep the total below physical RAM (64GB box) -- see ops\PERFORMANCE.md.
set "HEAP_MAIN=8G"
set "HEAP_RESOURCE=6G"
set "HEAP_DEV=4G"
set "HEAP_VELOCITY=1G"

REM Seconds to wait for each stage to come up. Raise these as worlds grow.
set "WAIT_AFTER_STORE=5"
set "WAIT_AFTER_MAIN=60"
set "WAIT_AFTER_BACKEND=30"

REM ---- sanity checks --------------------------------------------------------------------------
REM  Finding out mid-startup that a path is wrong is painful to untangle, so fail here.

if not exist "%VELOCITY_ROOT%" (
    echo [ERROR] VELOCITY_ROOT does not exist: %VELOCITY_ROOT%
    exit /b 1
)
if not exist "%OPS_SCRIPTS%\preflight.ps1" (
    echo [ERROR] ops scripts not found: %OPS_SCRIPTS%
    echo         Point TF_REPO in launch-config.cmd at the actual repository.
    exit /b 1
)

exit /b 0
