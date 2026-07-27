@echo off
REM =============================================================================================
REM  Start Garnet (Redis-compatible server), used as HuskSync's cache.
REM  Deploy to: D:\game\minecraft\Garnet\garnet.cmd
REM
REM  ASCII ONLY. cmd.exe mis-parses UTF-8 batch files: multi-byte characters make it seek to the
REM  wrong byte offset and it starts executing the middle of a line. Japanese explanations live
REM  in ops\RUNBOOK.md step 2-2. (run-selftest.ps1 fails if non-ASCII creeps back in.)
REM
REM  USE net8.0
REM  This zip is not self-contained, it needs a .NET runtime. It ships:
REM    net8.0\GarnetServer.exe  -> needs .NET 8  (this box has 8.0.21)
REM    net10.0\GarnetServer.exe -> needs .NET 10 (not installed)
REM  Hence net8.0. Switch to net10.0 only after installing .NET 10.
REM
REM  --memory IS MANDATORY
REM  It defaults to 16g: without it Garnet reserves 16GB for its main log and fights the
REM  8G + 6G JVMs for RAM. HuskSync only uses it as a short-lived snapshot cache
REM  (MariaDB is the system of record), so 1g is plenty.
REM
REM  --bind IS MANDATORY
REM  It defaults to "any", i.e. reachable from outside the machine (ops\SECURITY.md).
REM
REM  AOF stays off: MariaDB holds the system of record, so extra disk writes buy little.
REM =============================================================================================

set GARNET_HOME=D:\game\minecraft\Garnet

"%GARNET_HOME%\net8.0\GarnetServer.exe" ^
  --bind 127.0.0.1 ^
  --port 6379 ^
  --memory 1g ^
  --index 64m ^
  --checkpointdir "%GARNET_HOME%\data" ^
  --logger-level Warning
