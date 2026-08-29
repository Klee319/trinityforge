# スレッド枠拡張の儀式の累計上限(max-slots)を Ⅰ=1 / Ⅱ=2 / Ⅲ=5 へ揃える。
#
# なぜスクリプトが必要か:
#   ArsPaper の yml は「プラグインの version 文字列が変わったときだけ」jar から再抽出される
#   (ArsPaper#updateResourceFiles)。配備先の .version は jar の paper-plugin.yml と一致しているので、
#   jar を差し替えても再起動しても配備先の items.yml は 1 バイトも動かない。
#   ＝リポジトリ側を直しても配備先には届かないので、配備先のファイルを直接直す必要がある。
#
# なぜ直すのか:
#   max-slots は「その装備に刻まれた儀式由来スロットの累計上限」。Ⅱ が 1 のままだと、
#   Ⅰ を一度回した装備では Ⅱ が永久に失敗する(素材もソースも消費せず、ログにも何も出ない)。
#
# 2026-08-25 時点の実測: Main_Server / Resource_Server は既に 1/2/5(正) で、Dev_Server だけ 1/1/3。
# このスクリプトは冪等なので、既に正しいサーバには何も書かない。
#
# 実行後、各サーバで /ars reload を打てば反映される(儀式レシピは items.yml から再登録されるため、
# サーバ再起動は不要)。

param(
    [string]$Root = 'D:\game\minecraft\PaperServer\Velocity_for_TF',
    [string[]]$Servers = @('Main_Server', 'Resource_Server', 'Dev_Server'),
    [switch]$WhatIfOnly
)

$ErrorActionPreference = 'Stop'

# 期待値。キー = 儀式 id、値 = 累計上限。
$expected = [ordered]@{
    'thread_slot_expand_ritual'   = 1
    'thread_slot_expand_ritual_2' = 2
    'thread_slot_expand_ritual_3' = 5
}

# BOM 無し UTF-8。PowerShell の > / Set-Content -Encoding utf8 は BOM を付け、
# yml 途中に来た BOM は SnakeYAML が文書境界と誤読する(過去に踏んでいる)。
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$changed = 0
foreach ($server in $Servers) {
    $path = Join-Path $Root "$server\plugins\ArsPaper\items.yml"
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Host "[skip] $server : items.yml が無い ($path)"
        continue
    }

    $text = [System.IO.File]::ReadAllText($path)
    $updated = $text
    $before = @()
    foreach ($key in $expected.Keys) {
        # 儀式 id の直後に現れる最初の max-slots だけを見る(非貪欲)。
        $pattern = "(?s)($([regex]::Escape($key)):.*?max-slots:\s*)(\d+)"
        $m = [regex]::Match($updated, $pattern)
        if (-not $m.Success) {
            throw "$server : $key の max-slots が見つからない。書式が変わっているので手で確認すること。"
        }
        $before += "$key=$($m.Groups[2].Value)"
        $updated = [regex]::Replace($updated, $pattern, "`${1}$($expected[$key])", 1)
    }

    $now = ($expected.Keys | ForEach-Object { "$_=$($expected[$_])" }) -join ' '
    if ($updated -eq $text) {
        Write-Host "[ok]   $server : 既に正しい ($($before -join ' '))"
        continue
    }

    Write-Host "[fix]  $server : $($before -join ' ')  ->  $now"
    if ($WhatIfOnly) { continue }

    $stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
    Copy-Item -LiteralPath $path -Destination "$path.bak-$stamp"
    [System.IO.File]::WriteAllText($path, $updated, $utf8NoBom)
    Write-Host "       退避: items.yml.bak-$stamp"
    $changed++
}

if ($WhatIfOnly) {
    Write-Host ''
    Write-Host '（-WhatIfOnly なので書き込みはしていない）'
} elseif ($changed -gt 0) {
    Write-Host ''
    Write-Host "$changed 台を更新した。各サーバで /ars reload を打つと反映される。"
} else {
    Write-Host ''
    Write-Host '更新は不要だった。'
}
