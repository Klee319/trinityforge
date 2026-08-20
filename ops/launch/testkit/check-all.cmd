@echo off
REM =============================================================================================
REM  Run every testkit check, ordered foundation -> configuration -> running symptoms.
REM  Does not stop at the first failure; exits 1 if any check failed.
REM
REM  ASCII ONLY -- see ..\launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0..\launch-config.cmd" || exit /b 1
set "FAILED=0"

echo.
echo ##############################################################
echo #  1. ops script self-test and dry runs
echo ##############################################################
call "%~dp0check-ops-scripts.cmd" || set "FAILED=1"

echo.
echo ##############################################################
echo #  2. what the data stores actually are
echo ##############################################################
call "%~dp0check-datastores.cmd" || set "FAILED=1"

echo.
echo ##############################################################
echo #  3. unfinished configuration
echo ##############################################################
call "%~dp0check-config.cmd" || set "FAILED=1"

echo.
echo ##############################################################
echo #  4. symptoms in the running servers' logs
echo ##############################################################
call "%~dp0check-logs.cmd" || set "FAILED=1"

echo.
if "%FAILED%"=="1" (
    echo ============================================================
    echo  Something failed or is still unresolved. Read the output above.
    echo ============================================================
    exit /b 1
)
echo ============================================================
echo  All checks passed.
echo ============================================================
exit /b 0
