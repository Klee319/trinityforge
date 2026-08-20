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
REM  The repository copy (ops\launch\) is authoritative; deploy-launch.cmd copies it to
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

REM ---- backends (deploy.cmd walks this list) ---------------------------------------------------
REM  Backend directory names under VELOCITY_ROOT, in start order. Adding a fourth backend here is
REM  enough for deploy.cmd; it never hard-codes a server name.
set "TF_BACKENDS=Main_Server Resource_Server Dev_Server"

REM  The backend that owns the REAL plugins\TrinityForge directory. On the other backends that path
REM  is an NTFS directory junction to this one (ops\scripts\setup-junction.cmd), so TF yml is copied
REM  HERE ONCE and all three see it. jar files are separate real files on every backend, so those
REM  are copied once per backend. plugins\ArsPaper is a real directory everywhere -- three copies.
set "TF_CONFIG_HOST=Main_Server"

REM  JDK that builds TrinityForge and both forks. Java's auto-update makes a newer JDK the default
REM  on PATH, and Gradle 8.x cannot run on it: the build dies printing the version number and
REM  nothing else ("* What went wrong:" / "25.0.4"). A bare version number is that symptom.
set "JDK21_HOME=C:\Program Files\Java\jdk-21"

REM Heap sizes. Keep the total below physical RAM (64GB box) -- see ops\PERFORMANCE.md.
set "HEAP_MAIN=8G"
set "HEAP_RESOURCE=6G"
set "HEAP_DEV=4G"
set "HEAP_VELOCITY=1G"

REM Seconds to wait for each stage to come up. Raise these as worlds grow.
set "WAIT_AFTER_STORE=5"
set "WAIT_AFTER_MAIN=60"
set "WAIT_AFTER_BACKEND=30"

REM ---- console windows -------------------------------------------------------------------------
REM  Four separate console windows is a lot of desktop clutter, so collect the servers as TABS of
REM  one Windows Terminal window. "wt -w <name>" creates that window on first use and reuses it
REM  afterwards, so starting a single server later joins the same window instead of opening a new
REM  one. Each tab keeps its own stdin, which matters: the server console has to stay typeable.
REM  Without Windows Terminal we fall back to the old one-window-per-server behaviour.
set "WT_WINDOW=TrinityForge"
set "USE_WT="
%SystemRoot%\System32\where.exe wt.exe >nul 2>&1 && set "USE_WT=1"

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
