@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title TrinityForge Config Editor Launcher

rem このバッチのあるフォルダへ移動（どこから実行してもOK）
cd /d "%~dp0"

rem Node.js の存在チェック
where node >nul 2>nul
if errorlevel 1 (
  echo [エラー] Node.js が見つかりません。
  echo https://nodejs.org からインストールしてから、もう一度実行してください。
  echo.
  pause
  exit /b 1
)

rem 初回のみ依存パッケージをインストール
if not exist "node_modules\" (
  echo 初回セットアップ中: npm install を実行します...
  call npm install
  if errorlevel 1 (
    echo [エラー] npm install に失敗しました。ネットワーク接続を確認してください。
    echo.
    pause
    exit /b 1
  )
)

rem tool-config.json から port を読み取る（読めなければ 8787）
rem （"port" は他のキー/パスに含まれないため単純一致で一意に取れる）
set "PORT=8787"
for /f "usebackq tokens=2 delims=:," %%a in (`findstr /i "port" tool-config.json`) do (
  set "PORT=%%a"
)
set "PORT=!PORT: =!"
if "!PORT!"=="" set "PORT=8787"

echo.
echo TrinityForge Config Editor を起動します: http://localhost:!PORT!
echo （このウィンドウとは別に、サーバ用のウィンドウが開きます）
echo サーバを止めるには、サーバ用ウィンドウを閉じてください。
echo.

rem サーバを別ウィンドウで起動（ログ表示・閉じれば停止）
start "TrinityForge Config Editor (server)" cmd /k "node server.js"

rem サーバ起動を少し待ってから既定ブラウザで開く
timeout /t 2 /nobreak >nul
start "" "http://localhost:!PORT!"

endlocal
exit /b 0
