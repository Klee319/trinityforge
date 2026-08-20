<#
.SYNOPSIS
    GeyserExtra（Paper 側プラグイン + Geyser 拡張）をビルドして配備する。

.DESCRIPTION
    GeyserExtra は TrinityForge とは別リポジトリ（既定では
    `..\geyserExtraα`）にあり、`ops\launch\deploy.cmd` の対象外。
    そのため jar 2 本を配る手順がどこにも無く、毎回手で copy していた。

    配る先は 2 種類ある。片方だけ入れ替えると噛み合わないので必ず両方を配る:

      1. Paper 側プラグイン  -> 各バックエンドの `plugins\geyserExtra.jar`
         （バックエンドの数だけ実体をコピーする。config はジャンクション共有だが jar は別）
      2. Geyser 拡張        -> プロキシの
         `plugins\Geyser-Velocity\extensions\geyser_extra.jar`

    ⚠️ **稼働中に jar を差し替えると必ず NoClassDefFoundError になる。**
    JVM を stop -> start する以外に復旧手段は無い。だからバックエンドは
    check-servers-stopped.ps1 で必ずゲートし、プロキシ側は配備先 jar を
    排他オープンできるかで「Velocity が掴んでいないか」を確かめる。

    ⚠️ **配備先のファイル名は「今入っている名前」に合わせる。**
    成果物名（geyserExtra-1.0.0-SNAPSHOT.jar / extension-1.0.0-SNAPSHOT.jar）とは違う。
    名前を変えると同じ拡張が 2 本ロードされる。

.PARAMETER DryRun
    何も書かずに、配る対象と配備先の現状（サイズ・更新時刻）だけを出す。

.PARAMETER SkipBuild
    ビルドを飛ばして既存の成果物を配る。直前に自分でビルドしたときだけ使う。
    **成果物が古いまま配る事故のもとなので、成果物の更新時刻は必ず表示する。**

.PARAMETER RepoRoot
    geyserExtra リポジトリのルート。省略時は TrinityForge リポジトリの
    親ディレクトリの `geyserExtraα`。

.PARAMETER JavaHome
    Gradle に渡す JDK。省略時は `C:\Program Files\Java\jdk-21`。
    （渡さないと Gradle が別バージョンを掴んで `25.0.4` だけ吐いて落ちる）

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\deploy-geyserextra.ps1 -DryRun

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\deploy-geyserextra.ps1
#>
[CmdletBinding()]
param(
    [switch] $DryRun,
    [switch] $SkipBuild,
    [string] $RepoRoot,
    [string] $JavaHome = "C:\Program Files\Java\jdk-21"
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Common.ps1")

# RCON パスワードは使わないので必須にしない（未設定でも配備はできる）。
$config = Get-OpsConfig -RequireRconPasswords:$false

if (-not $RepoRoot) {
    $tfRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
    # Windows PowerShell 5.1 に `u{...} エスケープは無いので [char] で組む。
    $RepoRoot = Join-Path (Split-Path $tfRoot -Parent) ("geyserExtra" + [char]0x03B1)
}
if (-not (Test-Path -LiteralPath $RepoRoot)) {
    throw "geyserExtra リポジトリが見つかりません: $RepoRoot`n-RepoRoot で明示してください。"
}

$paperJar     = Join-Path $RepoRoot "paper\build\libs\geyserExtra-1.0.0-SNAPSHOT.jar"
$extensionJar = Join-Path $RepoRoot "extension\build\libs\extension-1.0.0-SNAPSHOT.jar"

# --------------------------------------------------------------------------
# ビルド
# --------------------------------------------------------------------------
if (-not $SkipBuild) {
    Write-Host "[build] $RepoRoot" -ForegroundColor Cyan
    if ($DryRun) {
        Write-Host "  (DryRun: gradlew build は実行しない)"
    } else {
        Push-Location $RepoRoot
        try {
            & (Join-Path $RepoRoot "gradlew.bat") build "-Dorg.gradle.java.home=$JavaHome"
            if ($LASTEXITCODE -ne 0) {
                throw "gradlew build が失敗しました (exit $LASTEXITCODE)。配備は行いません。"
            }
        } finally {
            Pop-Location
        }
    }
}

foreach ($jar in @($paperJar, $extensionJar)) {
    if (-not (Test-Path -LiteralPath $jar)) {
        throw "成果物がありません: $jar`n-SkipBuild を外すか、先にビルドしてください。"
    }
    $item = Get-Item -LiteralPath $jar
    Write-Host ("[artifact] {0}  {1:N0} bytes  {2:yyyy-MM-dd HH:mm:ss}" -f `
        $item.Name, $item.Length, $item.LastWriteTime)
}

# --------------------------------------------------------------------------
# 配備先の決定
# --------------------------------------------------------------------------
$targets = @()
foreach ($name in @($config.Servers.Keys | Sort-Object)) {
    $root = $config.Servers[$name].Root
    $pluginsDir = Join-Path $root "plugins"
    if (-not (Test-Path -LiteralPath $pluginsDir)) {
        Write-Host "[SKIP] $name : plugins フォルダがありません ($pluginsDir)" -ForegroundColor Yellow
        continue
    }
    $targets += [pscustomobject]@{
        Label  = "$name (Paper)"
        Source = $paperJar
        Target = Join-Path $pluginsDir "geyserExtra.jar"
    }
}

$extensionsDir = Join-Path $config.VelocityRoot "plugins\Geyser-Velocity\extensions"
if (-not (Test-Path -LiteralPath $extensionsDir)) {
    throw "Geyser 拡張フォルダがありません: $extensionsDir"
}
$targets += [pscustomobject]@{
    Label  = "proxy (Geyser extension)"
    Source = $extensionJar
    Target = Join-Path $extensionsDir "geyser_extra.jar"
}

Write-Host ""
foreach ($t in $targets) {
    $state = if (Test-Path -LiteralPath $t.Target) {
        $existing = Get-Item -LiteralPath $t.Target
        "{0:N0} bytes  {1:yyyy-MM-dd HH:mm:ss}" -f $existing.Length, $existing.LastWriteTime
    } else {
        "(未配備)"
    }
    Write-Host ("[plan] {0,-26} -> {1}" -f $t.Label, $t.Target)
    Write-Host ("       現在: {0}" -f $state)
}

if ($DryRun) {
    Write-Host ""
    Write-Host "DryRun のためここで終了します。何も書いていません。" -ForegroundColor Green
    exit 0
}

# --------------------------------------------------------------------------
# 稼働チェック
# --------------------------------------------------------------------------
Write-Host ""
Write-Host "[gate] バックエンドが停止していることを確認します" -ForegroundColor Cyan
# 子プロセスで呼ぶ。あのスクリプトは exit で終了コードを返すので、ドットソースや & で
# 同じセッションから呼ぶとこちらまで落ちる（deploy.cmd も子プロセスで呼んでいる）。
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "check-servers-stopped.ps1")
if ($LASTEXITCODE -ne 0) {
    throw "稼働中のバックエンドがあります。停止してからやり直してください。" +
          "（稼働中の jar 差し替えは必ず NoClassDefFoundError になり、再起動以外に復旧手段がありません）"
}

# Velocity は check-servers-stopped.ps1 の対象外。プロキシの拡張 jar を差し替えるので
# ここだけは別途確かめる。ファイルを排他オープンできれば誰も掴んでいない。
$proxyTarget = ($targets | Where-Object { $_.Label -like "proxy*" }).Target
if (Test-Path -LiteralPath $proxyTarget) {
    try {
        $stream = [System.IO.File]::Open(
            $proxyTarget, [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
        $stream.Close()
    } catch {
        throw "Velocity が拡張 jar を掴んでいます ($proxyTarget)。プロキシを停止してからやり直してください。"
    }
}

# --------------------------------------------------------------------------
# コピー
# --------------------------------------------------------------------------
Write-Host ""
foreach ($t in $targets) {
    Copy-Item -LiteralPath $t.Source -Destination $t.Target -Force
    Write-Host ("[copy] {0,-26} OK" -f $t.Label) -ForegroundColor Green
}

Write-Host ""
Write-Host "配備しました。次に起動して、以下のログが出ることを確認してください:" -ForegroundColor Green
Write-Host "  バックエンド: [bedrock-recipes] <backend>: N recipes from 2 plugin(s) [ArsPaper=..][TrinityForge=..]"
Write-Host "  プロキシ    : [bedrock-recipes] N corrected recipes loaded; M custom Bedrock items are addressable"
Write-Host "  プロキシ    : [bedrock-recipes] corrected recipes will be sent after Geyser's own"
Write-Host ""
Write-Host "バックエンド側のログは、TrinityForge / ArsPaper が enable した後（最大 1 分）に出ます。"
