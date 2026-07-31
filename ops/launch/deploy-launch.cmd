@echo off
setlocal
REM =============================================================================================
REM  Copy the repository's ops\launch\ over the deployed copy (%VELOCITY_ROOT%\launch).
REM
REM  This only ships the launch SCRIPTS. To build and ship the PLUGIN JARS, use deploy.cmd.
REM
REM  THE REPOSITORY COPY IS AUTHORITATIVE. Editing the deployed copy is pointless: the next
REM  run of this script overwrites it. Change launch-config.cmd values in the repository.
REM
REM  Overwrites existing files but does not delete files that only exist at the destination
REM  (deliberately not using /MIR).
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

set "SRC=%~dp0"
set "DST=%VELOCITY_ROOT%\launch"

REM Running this from the destination would copy the tree onto itself. Compare and refuse.
REM (%~dp0 keeps a trailing backslash, so strip it before comparing.)
if "%SRC:~-1%"=="\" set "SRC=%SRC:~0,-1%"
if /i "%SRC%"=="%DST%" (
    echo [ERROR] You are running the deployed copy: %SRC%
    echo         Run the repository's ops\launch\deploy-launch.cmd instead.
    exit /b 1
)

echo.
echo   from: %SRC%
echo   to  : %DST%
echo.

robocopy "%SRC%" "%DST%" /E /NFL /NDL /NJH /NJS /NP
REM Parentheses inside an echo would close the if-block early, so keep them out of the message.
if errorlevel 8 (
    echo.
    echo [ERROR] Copy failed. robocopy exit code: %errorlevel%
    exit /b 1
)

echo.
echo Deployed the launch scripts. From now on you can run them there:
echo   %DST%\deploy.cmd            build changed sources and ship the plugin jars
echo   %DST%\start-all.cmd
echo   %DST%\testkit\check-all.cmd
exit /b 0
