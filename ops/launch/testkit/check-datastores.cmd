@echo off
REM =============================================================================================
REM  Confirm WHAT is listening on 3306 and 6379, not just that the ports are open -- an open
REM  port tells you nothing when a different process happens to hold it.
REM
REM  Reachable=True with Speaks*=False means exactly that: something else owns the port.
REM
REM  ASCII ONLY -- see ..\launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0..\launch-config.cmd" || exit /b 1

REM Cast to pscustomobject so Format-List prints one block per endpoint instead of one block
REM per hashtable key.
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  ". '%OPS_SCRIPTS%\lib\DataStore.ps1';" ^
  "'=== MariaDB 3306 ==='; [pscustomobject](Test-MysqlEndpoint) | Format-List;" ^
  "'=== Redis 6379 ==='; [pscustomobject](Test-RedisEndpoint) | Format-List"
exit /b 0
