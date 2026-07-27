<#
.SYNOPSIS
    サーバを1台、予告付きでクリーン再起動する (日次再起動の実装)。

.DESCRIPTION
    RCON で段階的に予告を流し、stop を送り、落ちきるまで待つだけ。起動し直すのは
    server-loop.cmd 側の仕事なので、このスクリプトは起動処理を持たない。
    分業させておくと「上げ直したくないメンテナンス」でも同じスクリプトが使える。

    Velocity 側に FallbackRouter を入れてあるため、片方を落としても在席者は
    もう一方のサーバへ自動退避する。ネットワーク全体は無停止のまま片肺で回る。

    タイムアウトしても【強制終了しない】。ジャンクション経由でメインと共有している
    SQLite をフラッシュ途中で殺すと、全プレイヤーの進行データを壊しうる。

.PARAMETER Target
    both、または ops-config.psd1 の Servers にあるサーバ名 (main / resource / dev)。
    both のときは resource -> main の順に、間隔を空けて実行する
    (同時に落として両方から追い出さないため)。dev は both に含めない
    (検証用サーバなので定期再起動の対象外)。

.PARAMETER DryRun
    RCON へ何も送らず、実行予定の手順だけを出力する。

.PARAMETER WarnMinutes
    予告を出す残り分数。既定は 10 / 5 / 1 分前。-WarnMinutes @() で予告なし即時。

.EXAMPLE
    .\restart-server.ps1 -Target both -DryRun

.EXAMPLE
    .\restart-server.ps1 -Target resource -WarnMinutes @(1)

.EXAMPLE
    .\restart-server.ps1 -Target dev -WarnMinutes @()
#>
[CmdletBinding()]
param(
    # ValidateSet は使わない。ops-config.psd1 にサーバを足したら
    # スクリプトを触らずに -Target で指せるようにする (名前の検証は Resolve-OpsServer 側)。
    [string] $Target = "both",
    [string] $ConfigPath,
    [int[]]  $WarnMinutes = @(10, 5, 1),
    [int]    $StopTimeoutSeconds = 180,
    [int]    $BetweenServersSeconds = 90,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")

$config = Get-OpsConfig -Path $ConfigPath

# 資源 -> メイン の順。資源を先に落とせば、その在席者はメインへ退避できる。
# @() で包む: 1台だけのとき switch がスカラーを返し、StrictMode 下で .Count が落ちる。
$order = @(
    if ($Target -ieq "both") {
        $config.Servers.Resource
        $config.Servers.Main
    } else {
        Resolve-OpsServer -Config $config -Target $Target
    }
)

if ($DryRun) {
    Write-OpsLog "=== DRY RUN: 何も停止しません ===" -Level DRYRUN
}

Write-OpsLog "再起動対象: $(($order | ForEach-Object { $_.Name }) -join ' -> ')"

$failed = @()

for ($i = 0; $i -lt $order.Count; $i++) {
    $server = $order[$i]
    Write-Host ""
    Write-OpsLog "--- $($server.Name) ---"

    if (-not $DryRun) {
        $alive = Test-RconReachable -HostName $server.RconHost -Port $server.RconPort `
            -Password $server.RconPassword
        if (-not $alive) {
            Write-OpsLog "$($server.Name) は既に停止しています。スキップします。" -Level WARN
            continue
        }
    }

    if ($WarnMinutes.Count -gt 0) {
        Send-ShutdownWarnings -Server $server -Reason "定期再起動" `
            -WarnMinutes $WarnMinutes -DryRun:$DryRun
    }

    $stopped = Stop-ServerViaRcon -Server $server -TimeoutSeconds $StopTimeoutSeconds -DryRun:$DryRun
    if (-not $stopped) {
        $failed += $server.Name
        Write-OpsLog "$($server.Name) の停止に失敗しました。以降の処理を中断します。" -Level ERROR
        break
    }

    Write-OpsLog "$($server.Name) は server-loop.cmd が自動で起動し直します。"

    if ($i + 1 -lt $order.Count) {
        if ($DryRun) {
            Write-OpsLog "$BetweenServersSeconds 秒待ってから次のサーバへ" -Level DRYRUN
        } else {
            Write-OpsLog "$BetweenServersSeconds 秒待って次のサーバへ (起動完了を待つ)"
            Start-Sleep -Seconds $BetweenServersSeconds
        }
    }
}

Write-Host ""
if ($failed.Count -gt 0) {
    Write-OpsLog "停止に失敗したサーバ: $($failed -join ', ')" -Level ERROR
    exit 1
}
Write-OpsLog "完了しました。"
exit 0
