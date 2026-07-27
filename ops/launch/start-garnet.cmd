@echo off
REM =============================================================================================
REM  Dependency: Garnet (Redis-compatible; HuskSync's snapshot cache).
REM
REM  --memory IS MANDATORY. It defaults to 16g: without it Garnet reserves 16GB for its main
REM  log and fights the 8G + 6G JVMs for RAM. HuskSync only uses it as a short-lived snapshot
REM  cache (MariaDB is the system of record), so 1g is plenty.
REM
REM  --bind IS MANDATORY TOO. It defaults to "any", i.e. reachable from outside (ops\SECURITY.md).
REM
REM  Use net8.0: this zip is NOT self-contained, it needs a .NET runtime, and this box has
REM  .NET 8.0.21 installed (net8.0\ works, net10.0\ would need .NET 10).
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

tasklist /fi "imagename eq GarnetServer.exe" | %SystemRoot%\System32\findstr.exe /i /c:"GarnetServer.exe" >nul
if not errorlevel 1 (
    echo [OK] Garnet is already running.
    exit /b 0
)

if not exist "%GARNET_HOME%\net8.0\GarnetServer.exe" (
    echo [ERROR] GarnetServer.exe not found: %GARNET_HOME%\net8.0
    exit /b 1
)

REM No console window: Garnet is a background dependency and nobody types into it.
REM Its output goes to a log file instead, so it is still readable after the fact.
REM (At --logger-level Warning this file stays tiny.)
if not exist "%GARNET_HOME%\logs" mkdir "%GARNET_HOME%\logs"

echo [INFO] Starting Garnet (background, no window)
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Start-Process -FilePath '%GARNET_HOME%\net8.0\GarnetServer.exe'" ^
  " -ArgumentList '--bind','127.0.0.1','--port','6379','--memory','1g','--index','64m','--checkpointdir','%GARNET_HOME%\data','--logger-level','Warning'" ^
  " -WindowStyle Hidden" ^
  " -RedirectStandardOutput '%GARNET_HOME%\logs\garnet.log'" ^
  " -RedirectStandardError '%GARNET_HOME%\logs\garnet.err'"
if errorlevel 1 (
    echo [ERROR] Could not start Garnet. See %GARNET_HOME%\logs\garnet.err
    exit /b 1
)

%SystemRoot%\System32\timeout.exe /t %WAIT_AFTER_STORE% /nobreak >nul

REM Confirm it actually answers, rather than trusting that the process launched.
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  ". '%OPS_SCRIPTS%\lib\DataStore.ps1'; if ((Test-RedisEndpoint).SpeaksResp) { exit 0 } else { exit 1 }"
if errorlevel 1 (
    echo [ERROR] Garnet is not answering on 6379. See %GARNET_HOME%\logs\garnet.err
    exit /b 1
)
echo [OK] Garnet is answering on 127.0.0.1:6379
exit /b 0
