<#
.SYNOPSIS
    配備先の materials.yml へ「圧縮焼きウサギ肉(cooked_rabbit_1x)」を追加する。

.DESCRIPTION
    2026-08-23 の要望「圧縮したじゃがいもや生肉、生魚を焼けるようにしたい」に伴い、
    圧縮焼き物で唯一欠番だったウサギ肉を新設した。TF 側は
    progression/crafting-features.yml の compressed-smelting が rabbit_1x → cooked_rabbit_1x を
    指しているので、**このアイテムが配備先に無いと「焼こうとしても結果が組めない」**。

    【なぜスクリプトが要るのか】
    ArsPaper は既存の yml を自分では更新しない（saveResource(..., false)）。
    つまり **jar を差し替えても /ars reload しても配備先の materials.yml に新しいキーは増えない**。
    リポジトリ側の編集を届ける経路は ops\launch\deploy-config-head.cmd だけで、
    そちらは全バックエンドを止めてからでないと流せない。

    このスクリプトは「止めずに今すぐ入れたい」ときの経路。配備先の実ファイルへ
    materials: 直下のエントリを 1 件だけ挿入し、各サーバで /ars reload すれば反映される。
    ここで入れた編集は次回の config 配備で guard-deployed-config.ps1 が drift として拾い、
    リポジトリへ書き戻す（W-177）ので、黙って巻き戻されることはない。

    既に cooked_rabbit_1x があれば何もしない（何度流しても安全）。

.PARAMETER Target
    all（既定）、または ops-config.psd1 の Servers にあるサーバ名。

.PARAMETER DryRun
    書き込まず、挿入予定の位置だけを出す。

.EXAMPLE
    .\apply-compressed-cooked-rabbit.ps1 -DryRun

.EXAMPLE
    .\apply-compressed-cooked-rabbit.ps1
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

# 挿入するエントリ。リポジトリの
# fork-handoff\arspaper\fork\src\main\resources\materials.yml と 1 文字も違えてはいけない
# （違うと配備で drift 扱いになり、どちらが正か分からなくなる）。
$entry = @(
    "  # 2026-08-23: 圧縮焼き物で唯一欠番だったウサギ肉を追加した。",
    "  # 圧縮生肉をかまどで焼けるようにした(TF 側 crafting-features.yml の compressed-smelting)ので、",
    "  # rabbit_1x だけ焼き先が無い状態になっていた。",
    "  cooked_rabbit_1x:",
    "    base_material: COOKED_RABBIT",
    "    edible: true",
    "    custom_model_data: 260",
    "    display_name: 圧縮焼きウサギ肉",
    "    enchant_glow: true",
    "    lore:",
    '      - "&7&o9個分の焼きウサギ肉を1個に凝縮した保存食。"',
    "    recipe:",
    "      method: workbench",
    "      reversible: true",
    "      type: shaped",
    "      ingredients:",
    "        i: COOKED_RABBIT",
    "      shape:",
    "        - iii",
    "        - iii",
    "        - iii"
)

Write-OpsLog "配備先の materials.yml へ cooked_rabbit_1x を追加します"

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
    $file = Join-Path $server.Root "plugins\ArsPaper\materials.yml"
    Write-Host ""
    Write-OpsLog "--- $($server.Name) ---"

    if (-not (Test-Path -LiteralPath $file)) {
        Write-OpsLog "ArsPaper の materials.yml がありません（このバックエンドには入っていない）: $file"
        continue
    }

    $lines = @(Get-Content -LiteralPath $file)

    if ($lines | Where-Object { $_ -match '^\s{2}cooked_rabbit_1x:\s*$' }) {
        Write-OpsLog "変更なし（既に cooked_rabbit_1x があります）"
        continue
    }

    # materials: は最上位キー。その直下（インデント 2）の cookie_1x の手前へ入れる
    # ＝ リポジトリ側と同じ並び順になる。_editor: など他の最上位ブロックは触らない。
    $start = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^materials:\s*$') { $start = $i; break }
    }
    if ($start -lt 0) {
        $failures.Add("[$($server.Name)] materials: が見つかりません: $file")
        continue
    }

    $index = -1
    for ($i = $start + 1; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\S') { break }   # インデントが戻ったらブロックの外
        if ($lines[$i] -match '^\s{2}cookie_1x:\s*$') { $index = $i; break }
    }
    if ($index -lt 0) {
        $failures.Add("[$($server.Name)] materials: の中に cookie_1x がありません（挿入位置を決められない）: $file")
        continue
    }

    Write-OpsLog "  $($index + 1) 行目(cookie_1x)の直前へ $($entry.Count) 行を挿入します" -Level $(if ($DryRun) { "DRYRUN" } else { "INFO" })
    if ($DryRun) { $pending++; continue }

    # 書き戻す前に必ず退避する（配備先には手作業が混ざっている可能性がある）。
    $backup = "$file.bak-" + (Get-Date).ToString("yyyyMMdd_HHmmss")
    Copy-Item -LiteralPath $file -Destination $backup
    Write-OpsLog "  退避しました: $(Split-Path $backup -Leaf)"

    $merged = @()
    $merged += $lines[0..($index - 1)]
    $merged += $entry
    $merged += $lines[$index..($lines.Count - 1)]

    # ArsPaper が読む yml なので UTF-8 (BOM なし) + LF で書く。
    # PowerShell の > や Set-Content -Encoding utf8 は BOM を付けるので使わない
    # （yml の途中に来た BOM を SnakeYAML は文書境界と誤読する）。
    $content = ($merged -join "`n") + "`n"
    [System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))
    $changed++

    # 書けたことを読み直して確認する。
    $verify = @(Get-Content -LiteralPath $file)
    if ($verify | Where-Object { $_ -match '^\s{2}cooked_rabbit_1x:\s*$' }) {
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
        Write-OpsLog "DRY RUN: $pending 台に追加が必要です。-DryRun を外すと書き換えます。" -Level DRYRUN
    } else {
        Write-OpsLog "DRY RUN: 全台が既に揃っています。" -Level DRYRUN
    }
    exit 0
}

if ($changed -gt 0) {
    Write-OpsLog "$changed 台へ追加しました。"
    Write-OpsLog "反映は各サーバで /ars reload。TF 側は圧縮精錬レシピを登録し直すため /trinityforge reload も必要です。" -Level WARN
} else {
    Write-OpsLog "全台が既に揃っています。追加はありません。"
}
exit 0
