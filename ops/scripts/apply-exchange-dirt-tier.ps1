<#
.SYNOPSIS
    配備先の glyphs.yml で、交換グリフの土系ティアに草ブロック・ポドゾル・菌糸を足す。

.DESCRIPTION
    交換（Exchange）グリフは glyphs.yml の exchange_tiers に「同じ段に並んでいるブロック」の
    間でしか循環しない。第 1 段は長らく

        - [ DIRT, COARSE_DIRT, ROOTED_DIRT, MUD ]

    で、GRASS_BLOCK / PODZOL / MYCELIUM はどの段にも入っていなかった。
    つまり菌糸は交換では一度も作れたことがなく（履歴にも現れない）、キノコ島から
    運んでくる以外の入手経路が無かった。菌糸ソースリンクの強化には計 19 個要る。
    2026-08-22 の指示で、この 3 種を第 1 段へ同居させる。

    【なぜスクリプトが要るのか】
    ArsPaper は glyphs.yml を saveResource(..., false) で書き出す。つまり
    **一度作られたあとは jar を差し替えても /ars reload しても配備先のファイルは永久に
    更新されない**。fork のリソースを直しただけでは実機に絶対に反映されないので、
    配備先の実ファイルをこのスクリプトで直接書き換える。

    触るのは exchange_tiers の中の「DIRT で始まる段」1 行だけ。他の段・他のキーには
    手を出さない。既に菌糸が入っていれば何もしない（何度流しても安全）。

.PARAMETER Target
    all（既定）、または ops-config.psd1 の Servers にあるサーバ名。

.PARAMETER DryRun
    書き込まず、変更予定の行だけを出す。

.EXAMPLE
    .\apply-exchange-dirt-tier.ps1 -DryRun

.EXAMPLE
    .\apply-exchange-dirt-tier.ps1
#>
[CmdletBinding()]
param(
    [string] $Target = "all",
    [string] $ConfigPath,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Common.ps1")

$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false

# 置換後の 1 行。コメントは yml の規約どおり日本語で書く。
$newTier = "  - [ DIRT, COARSE_DIRT, ROOTED_DIRT, MUD, GRASS_BLOCK, PODZOL, MYCELIUM ]"
$comment = @(
    "  # 土系: 草ブロック・ポドゾル・菌糸を同じ段に入れてある（2026-08-22 指示）。",
    "  # 菌糸はキノコ島以外では手に入らず、菌糸ソースリンクの強化に計 19 個要るため、",
    "  # 「土から交換で作れる」経路をここで用意している。バイオーム制限は実質なくなる。"
)

Write-OpsLog "交換グリフの土系ティアへ GRASS_BLOCK / PODZOL / MYCELIUM を追加します"

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
$pending  = 0

foreach ($server in $targets) {
    $file = Join-Path $server.Root "plugins\ArsPaper\glyphs.yml"
    Write-Host ""
    Write-OpsLog "--- $($server.Name) ---"

    if (-not (Test-Path -LiteralPath $file)) {
        Write-OpsLog "ArsPaper の glyphs.yml がありません（このバックエンドには入っていない）: $file"
        continue
    }

    $lines = @(Get-Content -LiteralPath $file)

    # exchange_tiers は最上位キー（インデント 0）。同名の子キーを掴まないよう、
    # ブロックの範囲をインデントで確定してから、その中だけを探す。
    $start = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^exchange_tiers:\s*$') { $start = $i; break }
    }
    if ($start -lt 0) {
        $failures.Add("[$($server.Name)] exchange_tiers が見つかりません: $file")
        continue
    }

    $index = -1
    for ($i = $start + 1; $i -lt $lines.Count; $i++) {
        # インデントが戻ったらブロックの外。
        if ($lines[$i] -match '^\S') { break }
        if ($lines[$i] -match '^\s*-\s*\[\s*DIRT\s*,') { $index = $i; break }
    }
    if ($index -lt 0) {
        $failures.Add("[$($server.Name)] exchange_tiers に DIRT で始まる段がありません: $file")
        continue
    }

    if ($lines[$index] -match 'MYCELIUM') {
        Write-OpsLog "変更なし（既に菌糸が入っています）"
        continue
    }

    Write-OpsLog "  $($index + 1) 行目を書き換えます:" -Level $(if ($DryRun) { "DRYRUN" } else { "INFO" })
    Write-OpsLog "    旧: $($lines[$index].Trim())"
    Write-OpsLog "    新: $($newTier.Trim())"
    if ($DryRun) { $pending++; continue }

    # 書き戻す前に必ず退避する。配備先の編集は手作業が混ざっている可能性があるので、
    # 退避先は元と同じディレクトリに留める（リポジトリへ持ち出さない）。
    $backup = "$file.bak-" + (Get-Date).ToString("yyyyMMdd_HHmmss")
    Copy-Item -LiteralPath $file -Destination $backup
    Write-OpsLog "  退避しました: $(Split-Path $backup -Leaf)"

    $lines[$index] = (($comment + $newTier) -join "`n")

    # ArsPaper が読む yml なので UTF-8 (BOM なし) + LF で書く。
    # PowerShell の > や Set-Content -Encoding utf8 は BOM を付けるので使わない
    # （yml の途中に来た BOM を SnakeYAML は文書境界と誤読する）。
    $content = ($lines -join "`n") + "`n"
    [System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))
    $changed++

    # 書けたことを読み直して確認する。
    $verify = @(Get-Content -LiteralPath $file)
    $ok = $false
    for ($i = 0; $i -lt $verify.Count; $i++) {
        if ($verify[$i] -match '^\s*-\s*\[\s*DIRT\s*,' -and $verify[$i] -match 'MYCELIUM') { $ok = $true; break }
    }
    if ($ok) {
        Write-OpsLog "  反映を確認しました"
    } else {
        $failures.Add("[$($server.Name)] 書き込み後の確認に失敗しました: $file")
    }
}

Write-Host ""
if ($failures.Count -gt 0) {
    foreach ($failure in $failures) { Write-OpsLog $failure -Level ERROR }
    exit 1
}

if ($DryRun) {
    if ($pending -gt 0) {
        Write-OpsLog "DRY RUN: $pending 台に変更が必要です。-DryRun を外すと書き換えます。" -Level DRYRUN
    } else {
        Write-OpsLog "DRY RUN: 全台が既に揃っています。" -Level DRYRUN
    }
    exit 0
}

if ($changed -gt 0) {
    Write-OpsLog "$changed 台を書き換えました。"
    Write-OpsLog "反映は各サーバで /ars reload（再起動は不要。glyphs.yml は reload で読み直される）:" -Level WARN
} else {
    Write-OpsLog "全台が既に揃っています。書き換えはありません。"
}
exit 0
