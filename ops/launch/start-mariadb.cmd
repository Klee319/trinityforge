@echo off
REM =============================================================================================
REM  Dependency: MariaDB (system of record for LuckPerms and HuskSync).
REM  It is a Windows service, so it is normally already up; start it if not.
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

sc query MariaDB | %SystemRoot%\System32\findstr.exe /c:"RUNNING" >nul
if not errorlevel 1 (
    echo [OK] MariaDB is running.
    exit /b 0
)

echo [INFO] Starting MariaDB (needs administrator rights)
net start MariaDB
if errorlevel 1 (
    echo [ERROR] Could not start MariaDB. Re-run this as administrator.
    exit /b 1
)
exit /b 0
