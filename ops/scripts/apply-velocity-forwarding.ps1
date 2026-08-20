<#
.SYNOPSIS
    Velocity の forwarding.secret を各バックエンドの paper-global.yml へ反映する。

.DESCRIPTION
    modern forwarding は「プロキシから来た」という署名で成立するため、
    secret が 1 文字でも違うと全員が 'Unable to verify player details' で入れなくなる。
    3 台ぶんを手で貼ると必ずどこかで事故るので、機械的に揃える。

    触るのは proxies.velocity ブロックの enabled と secret の 2 行だけ。
    online-mode やその他の設定には手を出さない。

    【secret は画面に出さない】。一致したかどうかだけを報告する。

.PARAMETER Target
    all（既定）、または ops-config.psd1 の Servers にあるサーバ名。

.PARAMETER DryRun
    書き込まず、変更予定の行だけを出す。

.EXAMPLE
    .\apply-velocity-forwarding.ps1 -DryRun

.EXAMPLE
    .\apply-velocity-forwarding.ps1
#>
[CmdletBinding()]
param(
    [string] $Target = "all",
    [string] $ConfigPath,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")

$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false

if (-not $config.ContainsKey("VelocityRoot") -or -not $config.VelocityRoot) {
    Write-OpsLog "ops-config.psd1 に VelocityRoot がありません。" -Level ERROR
    exit 1
}

$secretFile = Join-Path $config.VelocityRoot "forwarding.secret"
if (-not (Test-Path -LiteralPath $secretFile)) {
    Write-OpsLog "forwarding.secret がありません: $secretFile" -Level ERROR
    Write-OpsLog "Velocity を一度起動すると生成されます。"
    exit 1
}

# 末尾改行の有無で一致判定が揺れないよう trim する。
$secret = (Get-Content -LiteralPath $secretFile -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($secret)) {
    Write-OpsLog "forwarding.secret が空です: $secretFile" -Level ERROR
    exit 1
}

$quotedSecret = ConvertTo-YamlSingleQuoted -Value $secret
Write-OpsLog "forwarding.secret を読みました（$($secret.Length) 文字。内容は表示しません）"

$targets = @(
    if ($Target -ieq "all") {
        foreach ($name in @($config.Servers.Keys)) { $config.Servers[$name] }
    } else {
        Resolve-OpsServer -Config $config -Target $Target
    }
)

if ($DryRun) {
    Write-OpsLog "=== DRY RUN: 何も書き込みません ===" -Level DRYRUN
}

$failures = New-Object System.Collections.Generic.List[string]
$changed  = 0

foreach ($server in $targets) {
    $file = Join-Path $server.Root "config\paper-global.yml"
    Write-Host ""
    Write-OpsLog "--- $($server.Name) ---"

    if (-not (Test-Path -LiteralPath $file)) {
        $failures.Add("[$($server.Name)] paper-global.yml がありません: $file")
        continue
    }

    $lines = @(Get-Content -LiteralPath $file)

    # proxies.velocity ブロックの範囲を特定する。インデントで判定するので、
    # 他の場所にある同名キー（bungee-cord の online-mode 等）を掴まない。
    $start = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s{2}velocity:\s*$') { $start = $i; break }
    }
    if ($start -lt 0) {
        $failures.Add("[$($server.Name)] proxies.velocity ブロックが見つかりません: $file")
        continue
    }

    $enabledIndex = -1
    $secretIndex  = -1
    for ($i = $start + 1; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s{0,3}\S') { break }
        if ($lines[$i] -match '^\s{4}enabled:')  { $enabledIndex = $i }
        if ($lines[$i] -match '^\s{4}secret:')   { $secretIndex  = $i }
    }
    if ($enabledIndex -lt 0 -or $secretIndex -lt 0) {
        $failures.Add("[$($server.Name)] velocity ブロックに enabled / secret がありません: $file")
        continue
    }

    $updates = @()
    if ($lines[$enabledIndex] -notmatch '^\s{4}enabled:\s*true\s*$') {
        $updates += @{ Index = $enabledIndex; New = "    enabled: true"; Label = "enabled -> true" }
    }
    # 既に一致しているかを比較する。引用符の有無や前後の空白は無視する。
    $currentSecret = if ($lines[$secretIndex] -match '^\s{4}secret:\s*(.*)$') {
        ConvertFrom-YamlScalar -Raw $Matches[1]
    } else { $null }
    if ($currentSecret -cne $secret) {
        $updates += @{ Index = $secretIndex; New = "    secret: $quotedSecret"; Label = "secret -> 一致させる" }
    }

    if ($updates.Count -eq 0) {
        Write-OpsLog "変更なし（既に enabled: true で secret も一致しています）"
        continue
    }

    foreach ($update in $updates) {
        Write-OpsLog "  $($update.Label)（$($update.Index + 1) 行目）" -Level $(if ($DryRun) { "DRYRUN" } else { "INFO" })
    }
    if ($DryRun) { continue }

    # 書き戻す前に必ず退避する。secret を含むので、バックアップの置き場所は元と同じ
    # ディレクトリに留める（リポジトリや共有先へ持ち出さない）。
    $backup = "$file.bak-" + (Get-Date).ToString("yyyyMMdd_HHmmss")
    Copy-Item -LiteralPath $file -Destination $backup
    Write-OpsLog "  退避しました: $(Split-Path $backup -Leaf)"

    foreach ($update in $updates) { $lines[$update.Index] = $update.New }

    # Paper が読む yml なので UTF-8 (BOM なし) + LF で書く。
    $content = ($lines -join "`n") + "`n"
    [System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))
    $changed++

    # 書けたことを読み直して確認する。
    $verify = @(Get-Content -LiteralPath $file)
    if ($verify[$enabledIndex] -notmatch 'enabled:\s*true' -or
        $verify[$secretIndex] -notmatch [regex]::Escape($secret)) {
        $failures.Add("[$($server.Name)] 書き込み後の確認に失敗しました: $file")
    } else {
        Write-OpsLog "  反映を確認しました"
    }
}

Write-Host ""
if ($failures.Count -gt 0) {
    Write-OpsLog "$($failures.Count) 件の失敗がありました。" -Level ERROR
    foreach ($failure in $failures) { Write-Host "  - $failure" }
    exit 1
}

if ($DryRun) {
    Write-OpsLog "DRY RUN 完了。"
} else {
    Write-OpsLog "$changed 件のファイルを更新しました。**反映には各バックエンドの再起動が必要**です。"
    Write-OpsLog "確認: preflight.ps1 を実行して secret 関連の指摘が消えていること。"
}
exit 0
