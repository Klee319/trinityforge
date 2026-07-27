@echo off
chcp 65001 >nul

REM =============================================================================================
REM  ネットワーク全体を【正しい順序で】起動する。
REM  配置先: D:\game\minecraft\PaperServer\Velocity_for_TF\start-all.cmd
REM
REM  この順序を守る理由 (RUNBOOK 手順 13-2):
REM    1. Garnet と MariaDB がバックエンドより先。繋ぐ先が無いと HuskSync は enable に
REM       失敗するが【サーバの起動は止まらない】ため、同期されないまま運用する事故になる。
REM    2. main -> resource の順。plugins/TrinityForge は実体を共有しているので、
REM       初回スキーママイグレーションを同時に走らせない。
REM    3. Velocity は最後。先に上げるとプレイヤーが「繋がるが飛べない」状態を踏む。
REM
REM  各バックエンドは server-loop.cmd で包む。stop 後に自動で起動し直す口が無いと、
REM  定期再起動 (restart-server.ps1) が「落ちたまま」になる。
REM =============================================================================================

set "VELOCITY_ROOT=D:\game\minecraft\PaperServer\Velocity_for_TF"
set "OPS_SCRIPTS=C:\Users\T-319\Documents\Program\ClaudeCodeDev\products\minecraft\trinityforge\ops\scripts"
set "PAPER_JAR=paper-1.21.11-132.jar"

REM 起動しきるまでの待ち時間 (秒)。ワールドが大きいほど伸ばす。
set "WAIT_AFTER_STORE=5"
set "WAIT_AFTER_MAIN=60"
set "WAIT_AFTER_BACKEND=30"

echo [1/6] MariaDB を確認します
sc query MariaDB | find "RUNNING" >nul
if errorlevel 1 (
    echo        起動していないので開始します
    net start MariaDB
)

echo [2/6] Garnet を起動します
tasklist /fi "imagename eq GarnetServer.exe" | find /i "GarnetServer.exe" >nul
if errorlevel 1 (
    start "Garnet" /min cmd /c "D:\game\minecraft\Garnet\garnet.cmd"
    timeout /t %WAIT_AFTER_STORE% /nobreak >nul
) else (
    echo        既に起動しています
)

echo [3/6] 起動前チェック
powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\preflight.ps1"
if errorlevel 1 (
    echo.
    echo [ERROR] preflight が問題を検出しました。サーバは起動しません。
    echo         上の指摘を解消してから、もう一度実行してください。
    exit /b 1
)

echo [4/6] main を起動します
start "main" cmd /c "\"%OPS_SCRIPTS%\server-loop.cmd\" \"%VELOCITY_ROOT%\Main_Server\" 8G %PAPER_JAR%"
timeout /t %WAIT_AFTER_MAIN% /nobreak >nul

echo [5/6] resource と dev を起動します
start "resource" cmd /c "\"%OPS_SCRIPTS%\server-loop.cmd\" \"%VELOCITY_ROOT%\Resource_Server\" 6G %PAPER_JAR%"
timeout /t %WAIT_AFTER_BACKEND% /nobreak >nul
start "dev" cmd /c "\"%OPS_SCRIPTS%\server-loop.cmd\" \"%VELOCITY_ROOT%\Dev_Server\" 4G %PAPER_JAR%"
timeout /t %WAIT_AFTER_BACKEND% /nobreak >nul

echo [6/6] Velocity を起動します
start "velocity" cmd /c "cd /d \"%VELOCITY_ROOT%\" && start.bat"

echo.
echo 起動を投げ終えました。各ウィンドウのログを確認してください。
echo   HuskSync が enable に失敗していないか (Connection refused が出ていないか) を必ず見ること。
