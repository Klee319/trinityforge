@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

REM =============================================================================================
REM  資源サーバの plugins\TrinityForge を、メインサーバの実体へのディレクトリジャンクションにする。
REM
REM  これ1回で「進行データ (player_progression.db) の共有」と「config の完全一致」が同時に成立する。
REM  Java 側の変更は不要。
REM
REM  前提 (RUNBOOK の該当手順を参照):
REM    - 両サーバが停止していること
REM    - 同一マシン・同一ローカルディスクであること (ネットワーク共有上の SQLite は破損する)
REM    - 資源サーバ側の plugins\TrinityForge が空か、退避済みであること
REM
REM  実行は1回だけ。2回目以降は「既にジャンクション」と表示して何もしない。
REM =============================================================================================

set "MAIN_TF=D:\game\minecraft\PaperServer\TrinityForge\plugins\TrinityForge"
set "RES_PLUGINS=D:\game\minecraft\PaperServer\TrinityForge-Res\plugins"
set "RES_TF=%RES_PLUGINS%\TrinityForge"

echo === TrinityForge ディレクトリジャンクション設定 ===
echo   リンク元 (資源) : %RES_TF%
echo   リンク先 (実体) : %MAIN_TF%
echo.

if not exist "%MAIN_TF%\" (
    echo [ERROR] メイン側の実体がありません: %MAIN_TF%
    echo         パスを確認してください。
    exit /b 1
)

if not exist "%RES_PLUGINS%\" (
    echo [ERROR] 資源サーバの plugins ディレクトリがありません: %RES_PLUGINS%
    echo         先に資源サーバを1度起動して plugins を作らせてください。
    exit /b 1
)

REM --- 既にジャンクションなら何もしない (冪等) -------------------------------------------------
if exist "%RES_TF%\" (
    dir /al "%RES_PLUGINS%" 2>nul | findstr /i /c:"[%MAIN_TF%]" >nul
    if !errorlevel! equ 0 (
        echo [SKIP] 既に同じリンク先のジャンクションです。何もしません。
        exit /b 0
    )

    REM --- 実ディレクトリが居座っている場合は退避する ------------------------------------------
    REM   ここで中身を消してはならない。誤って本物の config を捨てる事故になる。
    set "STAMP=%DATE:~0,4%%DATE:~5,2%%DATE:~8,2%-%TIME:~0,2%%TIME:~3,2%%TIME:~6,2%"
    set "STAMP=!STAMP: =0!"
    set "BACKUP=%RES_PLUGINS%\TrinityForge.pre-junction-!STAMP!"
    echo [INFO] 資源側に実ディレクトリがあります。退避します:
    echo        %RES_TF%
    echo        -^> !BACKUP!
    move "%RES_TF%" "!BACKUP!" >nul
    if !errorlevel! neq 0 (
        echo [ERROR] 退避に失敗しました。サーバが起動したままではありませんか?
        exit /b 1
    )
)

REM --- ジャンクション作成 ----------------------------------------------------------------------
mklink /J "%RES_TF%" "%MAIN_TF%"
if %errorlevel% neq 0 (
    echo [ERROR] mklink に失敗しました。管理者権限で実行していますか?
    exit /b 1
)

echo.
echo === 確認 ===
dir /al "%RES_PLUGINS%" | findstr /i "TrinityForge"
echo.
echo [OK] ジャンクションを作成しました。
echo.
echo !! 以後の注意 !!
echo   - 資源サーバを掃除するとき rmdir /s や Remove-Item -Recurse を
echo     %RES_TF% に向けないこと。メイン側の実体 (全プレイヤーの進行データと全 config) が消えます。
echo   - リンクを外すときは rmdir "%RES_TF%" (/s を付けない)。
echo   - 起動順は必ず main -^> resource。初回スキーマ作成の同時実行を避けるためです。
echo   - config を editor で保存したら、両サーバで /tf reload が必要です
echo     (ファイルは共有されますがメモリ上の設定は別々です)。

endlocal
exit /b 0
