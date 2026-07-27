@echo off
REM =============================================================================================
REM  Garnet (Redis 互換サーバ) の起動。HuskSync のキャッシュとして使う。
REM  配置先: D:\game\minecraft\Garnet\garnet.cmd
REM
REM  【net8.0 を使う】
REM  この zip は自己完結ではなく .NET ランタイムを要求する。同梱されているのは
REM    net8.0\GarnetServer.exe   -> .NET 8 ランタイムが必要（この環境には 8.0.21 がある）
REM    net10.0\GarnetServer.exe -> .NET 10 ランタイムが必要（未導入）
REM  なので net8.0 を指す。.NET 10 を入れたら net10.0 へ切り替えてよい。
REM
REM  【--memory を必ず指定する】
REM  既定は 16g。指定しないとメインログ用に 16GB を抱えに行き、
REM  8G + 6G の JVM とメモリを取り合う。HuskSync はスナップショットの
REM  一時キャッシュとしてしか使わない（正本は MariaDB）ので 1g で足りる。
REM
REM  【--bind を必ず指定する】
REM  既定は any。指定しないと外部から到達しうる（SECURITY.md）。
REM
REM  AOF は有効にしない。Redis 側が消えても MariaDB に正本があるため、
REM  ディスク書き込みを増やす意味が薄い。
REM =============================================================================================

set GARNET_HOME=D:\game\minecraft\Garnet

"%GARNET_HOME%\net8.0\GarnetServer.exe" ^
  --bind 127.0.0.1 ^
  --port 6379 ^
  --memory 1g ^
  --index 64m ^
  --checkpointdir "%GARNET_HOME%\data" ^
  --logger-level Warning
