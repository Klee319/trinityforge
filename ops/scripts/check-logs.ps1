<#
.SYNOPSIS
    各サーバの最新ログから、この構成で実害が出る既知の症状だけを拾う。

.DESCRIPTION
    起動ログは長いので目視だと見落とす。特に厄介なのが
    「HuskSync の enable が失敗しているのにサーバは起動している」ケースで、
    ログを読まないと同期されないまま運用してしまう。

    ここで探すのは【この構成で実際に起こる症状】に限る。汎用のログ解析ではない。

.PARAMETER Lines
    各ログの末尾から何行を見るか。既定 800（起動 1 回分に相当）。

.PARAMETER All
    ログ全体を見る。

.EXAMPLE
    .\check-logs.ps1

.EXAMPLE
    .\check-logs.ps1 -All
#>
[CmdletBinding()]
param(
    [string] $ConfigPath,
    [int]    $Lines = 800,
    [switch] $All
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")

# 症状ごとに「何が起きているか」と「どこを見るか」を持たせる。
# Severity: ERROR = 運用に実害あり / WARN = 放置すると効いてくる
$script:Symptoms = @(
    @{
        Pattern  = 'Error occurred while enabling HuskSync'
        Severity = "ERROR"
        Meaning  = "HuskSync が無効のまま動いている。インベントリが同期されない"
        Action   = "MariaDB / Garnet の稼働を確認 (preflight.ps1)。RUNBOOK 手順2"
    }
    @{
        Pattern  = 'Connection refused'
        Severity = "ERROR"
        Meaning  = "DB か Redis 互換サーバへ繋がっていない"
        Action   = "preflight.ps1 で 3306 / 6379 を確認する"
    }
    @{
        Pattern  = 'Access denied for user'
        Severity = "ERROR"
        Meaning  = "DB の資格情報が違う"
        Action   = "apply-husksync-config.ps1 を流し直す。ユーザーのホストは 127.0.0.1"
    }
    @{
        Pattern  = 'Unable to verify player details'
        Severity = "ERROR"
        Meaning  = "forwarding secret の不一致。全員ログインできない"
        Action   = "apply-velocity-forwarding.ps1 を実行して再起動する"
    }
    @{
        Pattern  = 'Ambiguous plugin name'
        Severity = "ERROR"
        Meaning  = "同名プラグインの jar が二重に置かれている"
        Action   = "sync-configs.ps1 が検出する。古い方を退避する"
    }
    @{
        # SqliteProgressionRepository は SQLException を握り潰して false を返すので、
        # プレイヤーには「ポイント不足」と区別がつかない形でパーク解放が失われる。
        Pattern  = 'SqliteProgressionRepository'
        Severity = "ERROR"
        Meaning  = "共有 SQLite でエラー。パーク解放が黙って失われている可能性"
        Action   = "reports/shared-sqlite-concurrency.md の §5"
    }
    @{
        Pattern  = 'database is locked'
        Severity = "ERROR"
        Meaning  = "共有 SQLite のロック競合"
        Action   = "起動順が main -> resource になっているか確認する"
    }
    @{
        Pattern  = 'Could not pass event'
        Severity = "WARN"
        Meaning  = "リスナーが例外を投げている"
        Action   = "直後のスタックトレースを読む"
    }
    @{
        Pattern  = 'Plugin .* has failed to register'
        Severity = "WARN"
        Meaning  = "プラグインの登録に失敗している"
        Action   = "対象プラグインの依存を確認する"
    }
    @{
        Pattern  = 'Attribute modifier .* already'
        Severity = "WARN"
        Meaning  = "属性 modifier の二重付与。HuskSync の attributes 同期が疑わしい"
        Action   = "ignored_modifiers に trinityforge:* があるか確認する"
    }
)

function Get-LogFindings {
    <#
    .SYNOPSIS ログ行の配列から、既知の症状に当たる行を拾う。
    .OUTPUTS 症状ごとに @{ Severity; Pattern; Meaning; Action; Count; Sample }
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [AllowEmptyCollection()] [AllowEmptyString()] [string[]] $LogLines,
        [array] $Symptoms = $script:Symptoms
    )

    $findings = @()
    foreach ($symptom in $Symptoms) {
        $hits = @($LogLines | Where-Object { $_ -match $symptom.Pattern })
        if ($hits.Count -eq 0) { continue }
        $findings += @{
            Severity = $symptom.Severity
            Pattern  = $symptom.Pattern
            Meaning  = $symptom.Meaning
            Action   = $symptom.Action
            Count    = $hits.Count
            # 最後の 1 件を出す。同じ症状が繰り返されるので先頭より末尾が有用。
            Sample   = $hits[-1].Trim()
        }
    }
    # 実害の大きい順に並べる。
    return @($findings | Sort-Object { if ($_.Severity -eq "ERROR") { 0 } else { 1 } })
}

# スクリプトとして直接実行されたときだけ走らせる（テストからはドットソースで関数だけ使う）。
if ($MyInvocation.InvocationName -eq ".") { return }

$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false

$logTargets = @()
foreach ($name in @($config.Servers.Keys)) {
    $server = $config.Servers[$name]
    $logTargets += @{ Label = $server.Name; Path = Join-Path $server.Root "logs\latest.log" }
}
if ($config.ContainsKey("VelocityRoot") -and $config.VelocityRoot) {
    $logTargets += @{ Label = "velocity"; Path = Join-Path $config.VelocityRoot "logs\latest.log" }
}

$totalErrors = 0
$totalWarns  = 0

foreach ($target in $logTargets) {
    Write-Host ""
    Write-OpsLog "--- $($target.Label) ---"

    if (-not (Test-Path -LiteralPath $target.Path)) {
        Write-OpsLog "ログがありません（まだ起動していない）: $($target.Path)" -Level WARN
        continue
    }

    $logLines = if ($All) {
        @(Get-Content -LiteralPath $target.Path)
    } else {
        @(Get-Content -LiteralPath $target.Path -Tail $Lines)
    }

    $findings = @(Get-LogFindings -LogLines $logLines)
    if ($findings.Count -eq 0) {
        Write-OpsLog "既知の症状は見つかりませんでした（$($logLines.Count) 行を確認）"
        continue
    }

    foreach ($finding in $findings) {
        if ($finding.Severity -eq "ERROR") { $totalErrors++ } else { $totalWarns++ }
        # Write-OpsLog が [ERROR] / [WARN] を前置するので、ここでは重ねない。
        Write-OpsLog "$($finding.Meaning)（$($finding.Count) 件）" `
            -Level $(if ($finding.Severity -eq "ERROR") { "ERROR" } else { "WARN" })
        Write-Host "         対処: $($finding.Action)"
        Write-Host "         例  : $($finding.Sample)"
    }
}

Write-Host ""
if ($totalErrors -gt 0) {
    Write-OpsLog "実害のある症状 $totalErrors 件 / 注意 $totalWarns 件" -Level ERROR
    exit 1
}
if ($totalWarns -gt 0) {
    Write-OpsLog "注意 $totalWarns 件（実害のある症状はなし）" -Level WARN
    exit 0
}
Write-OpsLog "既知の症状はどのログにもありません。"
exit 0
