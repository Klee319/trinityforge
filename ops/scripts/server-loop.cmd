@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

REM =============================================================================================
REM  Paper を「stop したら自動で起動し直す」ループで包む。main / resource 共通。
REM
REM  再起動プラグインを使わない理由 (ops/PERFORMANCE.md に詳述):
REM    HuskSync は restart 系プラグインを公式に非対応としている。プロセスを生かしたまま
REM    ワールドとプレイヤーを作り直す方式はデータ消失とアイテム複製の温床になるため、
REM    再起動は必ず「RCON stop でクリーンに落として、プロセスごと起動し直す」で統一する。
REM
REM  使い方:
REM    server-loop.cmd "D:\game\minecraft\PaperServer\TrinityForge"     8G paper-1.21.11-132.jar
REM    server-loop.cmd "D:\game\minecraft\PaperServer\TrinityForge-Res" 6G paper-1.21.11-132.jar
REM
REM  ループを抜けたいとき (メンテナンスなどで上げ直したくないとき):
REM    サーバルートに stop.flag という空ファイルを置いてから stop する。
REM =============================================================================================

set "SERVER_ROOT=%~1"
set "HEAP=%~2"
set "PAPER_JAR=%~3"

if "%SERVER_ROOT%"=="" (
    echo [ERROR] 引数1にサーバルートを指定してください。
    exit /b 1
)
if "%HEAP%"==""      set "HEAP=8G"
if "%PAPER_JAR%"=="" set "PAPER_JAR=paper-1.21.11-132.jar"

cd /d "%SERVER_ROOT%" || (
    echo [ERROR] サーバルートへ移動できません: %SERVER_ROOT%
    exit /b 1
)

if not exist "%PAPER_JAR%" (
    echo [ERROR] Paper の jar がありません: %SERVER_ROOT%\%PAPER_JAR%
    exit /b 1
)

REM 起動し直す前に置く間隔 (秒)。クラッシュループで CPU を焼かないための保険。
set "RESTART_DELAY=10"

:loop
if exist "stop.flag" (
    echo [INFO] stop.flag があるためループを終了します。
    echo        再開するには stop.flag を削除してこのスクリプトを起動し直してください。
    goto :eof
)

echo.
echo ===============================================================
echo  起動: %SERVER_ROOT%  (heap=%HEAP%)  %DATE% %TIME%
echo ===============================================================

REM フラグは Aikar 由来の G1 チューニング。現行 start.bat と同じ構成に揃えている。
java -Xms%HEAP% -Xmx%HEAP% ^
 -XX:+UseG1GC -XX:+ParallelRefProcEnabled ^
 -XX:MaxGCPauseMillis=200 -XX:+DisableExplicitGC -XX:+AlwaysPreTouch ^
 -jar "%PAPER_JAR%" nogui

set "EXIT_CODE=!errorlevel!"
echo [INFO] Paper が終了しました (exit=!EXIT_CODE!) %DATE% %TIME%

if exist "stop.flag" (
    echo [INFO] stop.flag を検出。再起動しません。
    goto :eof
)

echo [INFO] %RESTART_DELAY% 秒後に起動し直します。中止するには Ctrl+C。
timeout /t %RESTART_DELAY% /nobreak >nul
goto loop
