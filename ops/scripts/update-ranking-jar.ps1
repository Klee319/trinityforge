<#
.SYNOPSIS
    ランキングプラグイン UserRankBoard (PixelRank) の jar だけを 3 バックエンドへ差し替える。

.DESCRIPTION
    install-ranking-plugin.ps1 との違いは【config.yml を書かない】ことだけ。

    ⚠ install-ranking-plugin.ps1 は config.yml を jar 同梱の雛形から書き直すので、
      稼働中サーバで手を入れた設定 (例: death_rank: false) が既定へ戻り、
      DB パスワードも聞かれる。W-137 の修正 jar は起動時に ranks.migrate-tf-ranks で
      自分を移行するので【config は配り直さなくてよい】。そのための jar 専用スクリプト。

    やること (この順番でしかやらない):
      A. 検査だけする … source jar の存在 / 各 plugins ディレクトリ / 同名 jar の二重配置。
                        1 つでも欠ければ【何も書かずに中断】する
      B. 稼働判定     … check-servers-stopped.ps1 へ委譲。1 台でも動いていたら中断
      C. 退避         … 現行 jar を plugins\.ranking-backups\<日時>\ へ
      D. jar をコピー … 全バックエンドぶん。コピー後に SHA-256 を突き合わせる

    ⚠ このスクリプトは D:\game 配下へ書き込むので、エージェントではなく人が実行する。
    ⚠ 稼働中のサーバの jar を差し替えると必ず NoClassDefFoundError になる。
      JVM 再起動以外に復旧手段は無いので、B の稼働判定は絶対に飛ばさないこと。

.PARAMETER SourceJar
    配布する jar。既定は UserRankBoard のビルド出力
    (products\minecraft\rank\build\libs\PixelRank-*.jar) を自動で探す。

.PARAMETER DryRun
    退避もコピーもせず、計画だけ出す。

.EXAMPLE
    .\update-ranking-jar.ps1 -DryRun

.EXAMPLE
    .\update-ranking-jar.ps1
#>
[CmdletBinding()]
param(
    [string] $SourceJar,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$DEFAULT_BUILD_DIR = "C:\Users\T-319\Documents\Program\ClaudeCodeDev\products\minecraft\rank\build\libs"
$BACKENDS = @(
    "D:\game\minecraft\PaperServer\Velocity_for_TF\Main_Server",
    "D:\game\minecraft\PaperServer\Velocity_for_TF\Resource_Server",
    "D:\game\minecraft\PaperServer\Velocity_for_TF\Dev_Server"
)

function Resolve-SourceJar {
    param([string] $Explicit)

    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit)) {
            throw "中断: -SourceJar のファイルがありません: $Explicit"
        }
        return (Resolve-Path -LiteralPath $Explicit).Path
    }
    if (-not (Test-Path -LiteralPath $DEFAULT_BUILD_DIR)) {
        throw "中断: ビルド出力ディレクトリがありません: $DEFAULT_BUILD_DIR"
    }
    $found = Get-ChildItem -LiteralPath $DEFAULT_BUILD_DIR -Filter "PixelRank-*.jar" |
        Sort-Object LastWriteTime -Descending
    if (-not $found) {
        throw "中断: $DEFAULT_BUILD_DIR に PixelRank-*.jar がありません (先に UserRankBoard をビルドする)"
    }
    return $found[0].FullName
}

# ---- A. 検査 ---------------------------------------------------------------
$jar = Resolve-SourceJar -Explicit $SourceJar
$jarName = Split-Path -Leaf $jar
$jarHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
$jarInfo = Get-Item -LiteralPath $jar
Write-Host "source: $jar"
Write-Host "        $($jarInfo.Length) bytes / $($jarInfo.LastWriteTime) / SHA256 $($jarHash.Substring(0,16))..."

$targets = @()
foreach ($backend in $BACKENDS) {
    $plugins = Join-Path $backend "plugins"
    if (-not (Test-Path -LiteralPath $plugins)) {
        throw "中断: plugins ディレクトリがありません: $plugins"
    }
    # 同名プラグインの jar が2つあると "Ambiguous plugin name" で起動不能になる前科がある。
    $existing = @(Get-ChildItem -LiteralPath $plugins -Filter "PixelRank*.jar" -File)
    if ($existing.Count -gt 1) {
        throw "中断: $plugins に PixelRank の jar が $($existing.Count) 個あります: $($existing.Name -join ', ')"
    }
    $targets += [pscustomobject]@{
        Plugins  = $plugins
        Existing = if ($existing.Count -eq 1) { $existing[0] } else { $null }
        Dest     = Join-Path $plugins $jarName
    }
}

foreach ($t in $targets) {
    if ($null -eq $t.Existing) {
        Write-Host "  [NEW ] $($t.Dest)"
    } elseif ($t.Existing.Name -ne $jarName) {
        Write-Host "  [SWAP] $($t.Existing.Name) -> $jarName  ($($t.Plugins))"
    } else {
        Write-Host "  [OVER] $($t.Dest)  ($($t.Existing.Length) bytes / $($t.Existing.LastWriteTime))"
    }
}

# ---- B. 稼働判定 -----------------------------------------------------------
$stopCheck = Join-Path $PSScriptRoot "check-servers-stopped.ps1"
if (Test-Path -LiteralPath $stopCheck) {
    & $stopCheck
    if ($LASTEXITCODE -ne 0) {
        throw "中断: サーバが稼働中です。停止してから実行すること (稼働中の jar 差し替えは NoClassDefFoundError で必ず壊れる)"
    }
} else {
    throw "中断: 稼働判定スクリプトが見つかりません: $stopCheck"
}

if ($DryRun) {
    Write-Host "`n-DryRun のため、ここまでで終了 (何も書いていない)"
    return
}

# ---- C. 退避 / D. コピー ---------------------------------------------------
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
foreach ($t in $targets) {
    if ($null -ne $t.Existing) {
        $backupDir = Join-Path $t.Plugins ".ranking-backups\$stamp"
        New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
        Copy-Item -LiteralPath $t.Existing.FullName -Destination $backupDir -Force
        Write-Host "  退避: $($t.Existing.Name) -> $backupDir"
        if ($t.Existing.Name -ne $jarName) {
            Remove-Item -LiteralPath $t.Existing.FullName -Force
            Write-Host "  削除: $($t.Existing.Name) (名前が変わるので古い方を残さない)"
        }
    }
    Copy-Item -LiteralPath $jar -Destination $t.Dest -Force
    $copied = (Get-FileHash -LiteralPath $t.Dest -Algorithm SHA256).Hash
    if ($copied -ne $jarHash) {
        throw "中断: コピー後の SHA-256 が一致しません: $($t.Dest)"
    }
    Write-Host "  配備: $($t.Dest)  (SHA-256 一致)"
}

Write-Host "`n完了。config.yml は触っていない。"
Write-Host "次回の起動で ranks.migrate-tf-ranks が 1 回だけ走り、全 TF ランキングが有効へ戻る。"
