<#
.SYNOPSIS
    日次バックアップ。進行 DB (SQLite) と MariaDB の 2 DB を退避する。

.DESCRIPTION
    2サーバ構成で失うと痛いものは3つ:
      1. plugins\TrinityForge\player_progression.db  … スキルLv / ポイント / パーク
      2. MariaDB の husksync   … インベントリ / エンダーチェスト / PDC
      3. MariaDB の luckperms  … 権限

    ワールドは対象にしない。メインは Backuper が担当し、資源は毎週消える前提のため。

    【ジャンクションを辿らせないこと】
    plugins\ を再帰コピーすると資源サーバ側のジャンクション経由でメインの実体を二重に舐める。
    このスクリプトは SQLite ファイルを1本だけ名指しでコピーする。

    SQLite は WAL で開かれたまま (サーバ稼働中) でもコピーできるよう、
    .db / .db-wal / .db-shm の3点セットを取る。復元時は3点まとめて戻す。

.PARAMETER DryRun
    何もコピーせず、対象と出力先だけを表示する。

.EXAMPLE
    .\backup.ps1 -DryRun
#>
[CmdletBinding()]
param(
    [string] $ConfigPath,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")

$config = Get-OpsConfig -Path $ConfigPath
$main   = $config.Servers.Main
$backup = $config.Backup

$stamp     = (Get-Date).ToString("yyyyMMdd_HHmmss")
$outputDir = Join-Path $backup.Root $stamp

Write-OpsLog "バックアップ先: $outputDir"
if ($DryRun) {
    Write-OpsLog "=== DRY RUN: 何も書き込みません ===" -Level DRYRUN
} else {
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
}

$failures = New-Object System.Collections.Generic.List[string]

# ---- 1. 進行 DB (SQLite) -----------------------------------------------------------------------
# ジャンクションではなく、メイン側の実体を直接指す。

$dbDir = Join-Path $main.Root "plugins\TrinityForge"
if (Test-ReparsePoint -Path $dbDir) {
    # メイン側がジャンクションになっているのは設定ミス。実体を二重に取っても意味がないので止める。
    $failures.Add("メイン側の plugins\TrinityForge がジャンクションです。実体のパスを設定してください。")
} else {
    $sqliteParts = @("player_progression.db", "player_progression.db-wal", "player_progression.db-shm")
    $copiedAny = $false
    foreach ($part in $sqliteParts) {
        $source = Join-Path $dbDir $part
        if (-not (Test-Path -LiteralPath $source)) {
            # -wal / -shm は稼働状況によって存在しない。欠けていても異常ではない。
            continue
        }
        if ($DryRun) {
            Write-OpsLog "コピーする: $source" -Level DRYRUN
        } else {
            Copy-Item -LiteralPath $source -Destination (Join-Path $outputDir $part) -Force
        }
        $copiedAny = $true
    }
    if (-not $copiedAny) {
        $failures.Add("進行 DB が見つかりません: $dbDir\player_progression.db")
    } else {
        Write-OpsLog "進行 DB を退避しました (.db / -wal / -shm のうち存在するもの)"
    }
}

# ---- 2. MariaDB ---------------------------------------------------------------------------------
# ネイティブ構成 (手順2 の既定) なら MariaDB 同梱の mariadb-dump.exe を直接叩く。
# WSL 構成なら wsl 経由で mysqldump を叩く。どちらでも
# 【パスワードはコマンドラインに書かない】(プロセス一覧から見えるため)。
#   ネイティブ = --defaults-file で指定した my.cnf の [mariadb-dump] セクション
#   WSL        = WSL 側の ~/.my.cnf

$nativeDumpPath = if ($backup.ContainsKey("MysqldumpPath")) { $backup.MysqldumpPath } else { $null }
$useNative = $false
$canDump = $true

if ($nativeDumpPath) {
    # 設定されているのに見つからない場合、黙って WSL へ落ちない。
    # 「設定したのと違う経路でバックアップされていた」ほうが事故として重い。
    if (Test-Path -LiteralPath $nativeDumpPath) {
        $useNative = $true
    } else {
        $failures.Add("MysqldumpPath が存在しません: $nativeDumpPath`n" +
                      "    MariaDB のインストール先と版番号を確認してください。" +
                      "WSL 構成なら MysqldumpPath ごと削除してください。")
        $canDump = $false
    }
} elseif (-not $backup.ContainsKey("WslDistro")) {
    $failures.Add("MysqldumpPath も WslDistro も設定されていません。MariaDB を dump できません。")
    $canDump = $false
}

foreach ($database in $(if ($canDump) { $backup.Databases } else { @() })) {
    $dumpPath = Join-Path $outputDir "$database.sql"
    $dumpArgs = @("--single-transaction", "--quick", "--default-character-set=utf8mb4", $database)

    if ($useNative) {
        $defaultsFile = if ($backup.ContainsKey("MysqlDefaultsFile")) { $backup.MysqlDefaultsFile } else { $null }
        if (-not $defaultsFile -or -not (Test-Path -LiteralPath $defaultsFile)) {
            $failures.Add("MysqlDefaultsFile が見つかりません: $defaultsFile" +
                          "（[mariadb-dump] セクションに user / password を書いたファイルが要る）")
            continue
        }
        # --defaults-file は他のオプションより先に置く必要がある。
        $exePath = $nativeDumpPath
        $exeArgs = @("--defaults-file=$defaultsFile") + $dumpArgs
        $shown   = "$exePath $($exeArgs -join ' ')"
    } else {
        $exePath = "wsl.exe"
        $exeArgs = @("-d", $backup.WslDistro, "--", "bash", "-lc",
                     "`"mysqldump $($dumpArgs -join ' ')`"")
        $shown   = "wsl -d $($backup.WslDistro) -- mysqldump $($dumpArgs -join ' ')"
    }

    if ($DryRun) {
        Write-OpsLog "実行する: $shown > $dumpPath" -Level DRYRUN
        continue
    }

    try {
        # 標準出力をそのままファイルへ。UTF-8 のまま落としたいので Out-File は使わない。
        $process = Start-Process -FilePath $exePath -ArgumentList $exeArgs `
            -RedirectStandardOutput $dumpPath -NoNewWindow -Wait -PassThru

        if ($process.ExitCode -ne 0) {
            $failures.Add("dump が失敗しました ($database, exit=$($process.ExitCode))")
        } elseif ((Get-Item -LiteralPath $dumpPath).Length -eq 0) {
            $failures.Add("dump の出力が空です ($database)")
        } else {
            $sizeMb = [math]::Round((Get-Item -LiteralPath $dumpPath).Length / 1MB, 2)
            Write-OpsLog "$database を dump しました ($sizeMb MB)"
        }
    } catch {
        $failures.Add("dump を実行できません ($database): $($_.Exception.Message)")
    }
}

# ---- 3. 世代管理 ---------------------------------------------------------------------------------

$cutoff = (Get-Date).AddDays(-$backup.KeepDays)
if (Test-Path -LiteralPath $backup.Root) {
    $stale = @(Get-ChildItem -LiteralPath $backup.Root -Directory |
        Where-Object { $_.CreationTime -lt $cutoff })
    foreach ($dir in $stale) {
        # バックアップ配下にジャンクションが出来る想定はないが、削除は必ずガード経由にする。
        Remove-DirectorySafely -Path $dir.FullName -DryRun:$DryRun
    }
    if (-not $stale) {
        Write-OpsLog "$($backup.KeepDays) 日より古いバックアップはありません。"
    }
}

# ---- 結果 -----------------------------------------------------------------------------------------

Write-Host ""
if ($failures.Count -gt 0) {
    Write-OpsLog "$($failures.Count) 件の失敗がありました。" -Level ERROR
    foreach ($failure in $failures) { Write-Host "  - $failure" }
    exit 1
}
Write-OpsLog "バックアップが完了しました: $outputDir"
exit 0
