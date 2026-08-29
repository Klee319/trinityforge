<#
.SYNOPSIS
    mobGriefing を true に戻したまま「壊される側」だけを WorldGuard で止める。

.DESCRIPTION
    クリーパー対策に `gamerule mobGriefing false` を使うと、同じフラグが
    **村人の食料拾い**まで止めるので村人が繁殖しなくなる（2026-08-23 実サーバ報告）。
    mobGriefing は「モブがワールドを書き換えてよいか」の 1 本のスイッチで、
    クリーパーだけを外す粒度を持っていない。

    WorldGuard 7 は同じことをモブごとに分けて持っているので、
    **mobGriefing は true のまま**、壊してほしくないモブだけを止められる。
    キー名は worldguard-bukkit-7.0.15.jar の WorldConfiguration から取った実在のもの。

        block-creeper-block-damage        クリーパーの爆発でブロックが壊れない
                                          （プレイヤー/モブへのダメージは残る）
        disable-enderman-griefing         エンダーマンがブロックを持ち去らない
        block-wither-block-damage         ウィザー本体の破壊
        block-wither-skull-block-damage   ウィザーの頭（弾）の破壊
        block-fireball-block-damage       ガストの火の玉の破壊
        block-zombie-door-destruction     ゾンビがドアを壊さない（難易度ハード）

    ⚠ mobGriefing 側は【このスクリプトでは触らない】。ワールドの gamerule なので、
      サーバ稼働中に `/gamerule mobGriefing true` を実行するのが確実で安全
      （level.dat を直接書くと稼働中のメモリ上の値に上書きされて消える）。

    ⚠ 書き換えても稼働中のサーバには効かない。`/wg reload` か再起動が要る。

    ⚠ 触るのは各サーバの `plugins/WorldGuard/config.yml`（全ワールド共通の既定値）。
      ワールド単位で変えたい場合は `plugins/WorldGuard/worlds/<world>/config.yml` に
      同じ `mobs:` 節を書くと、そちらが優先される。

.PARAMETER Target
    main（既定）、または ops-config.psd1 の Servers にあるサーバ名 / all。
    資源サーバは掘って壊す場所なので既定では触らない。

.PARAMETER Keys
    立てるキーを明示する。省略時は上の 6 つ。

.PARAMETER Revert
    true にするのではなく false へ戻す（WorldGuard の既定に寄せる）。

.PARAMETER DryRun
    書き込まず、変更予定の行だけを出す。

.EXAMPLE
    .\apply-mob-griefing-guard.ps1 -DryRun

.EXAMPLE
    .\apply-mob-griefing-guard.ps1 -Keys block-creeper-block-damage
#>
[CmdletBinding()]
param(
    [string] $Target = "main",
    [string] $ConfigPath,
    [string[]] $Keys,
    [switch] $Revert,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Common.ps1")

$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false

# 既定の対象。mobGriefing: true へ戻したときに復活する「壊す側」を一通り塞ぐ。
$defaultKeys = @(
    "block-creeper-block-damage",
    "disable-enderman-griefing",
    "block-wither-block-damage",
    "block-wither-skull-block-damage",
    "block-fireball-block-damage",
    "block-zombie-door-destruction"
)
if (-not $Keys -or $Keys.Count -eq 0) { $Keys = $defaultKeys }

# ⚠ WorldGuard に実在するキーだけを許す。綴りを間違えると YAML としては通るのに
#    一生効かない（無言の no-op）ので、ここで弾く。
$knownKeys = @(
    "block-creeper-explosions", "block-creeper-block-damage",
    "block-wither-explosions", "block-wither-block-damage",
    "block-wither-skull-explosions", "block-wither-skull-block-damage",
    "block-enderdragon-block-damage", "block-enderdragon-portal-creation",
    "block-fireball-explosions", "block-fireball-block-damage",
    "block-windcharge-explosions", "disable-enderman-griefing",
    "disable-snowman-trails", "block-painting-destroy", "block-item-frame-destroy",
    "block-armor-stand-destroy", "block-above-ground-slimes",
    "block-other-explosions", "block-zombie-door-destruction"
)
foreach ($k in $Keys) {
    if ($knownKeys -notcontains $k) {
        throw ("WorldGuard に存在しないキーです: $k`n" +
               "使えるのは:`n  " + ($knownKeys -join "`n  "))
    }
}

$desired = if ($Revert) { "false" } else { "true" }

$targets = @()
if ($Target -eq "all") {
    $targets = @($config.Servers.Keys | Sort-Object)
} elseif ($config.Servers.ContainsKey($Target)) {
    $targets = @($Target)
} else {
    throw ("サーバ名が不明です: $Target`n使えるのは: all, " +
           (($config.Servers.Keys | Sort-Object) -join ", "))
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$changedAny = $false

foreach ($name in $targets) {
    $file = Join-Path $config.Servers[$name].Root "plugins\WorldGuard\config.yml"
    if (-not (Test-Path -LiteralPath $file)) {
        Write-Host "[SKIP] $name : WorldGuard の config.yml がありません ($file)" -ForegroundColor Yellow
        continue
    }

    $raw = [System.IO.File]::ReadAllText($file)
    $newline = if ($raw -match "`r`n") { "`r`n" } else { "`n" }
    $lines = $raw -split "`r`n|`n"

    # mobs: 節の範囲を決める。同名キーは他の節に無いが、範囲を切っておけば
    # 将来 WorldGuard がキーを増やしても他所を巻き込まない。
    $start = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^mobs:\s*$') { $start = $i; break }
    }
    if ($start -lt 0) {
        throw "$name : config.yml に mobs: 節がありません（WorldGuard が書き直した？）: $file"
    }
    $stop = $lines.Count
    for ($i = $start + 1; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\S') { $stop = $i; break }
    }

    $changed = @()
    foreach ($k in $Keys) {
        $found = $false
        for ($i = $start + 1; $i -lt $stop; $i++) {
            if ($lines[$i] -match ('^(\s+)' + [regex]::Escape($k) + ':\s*(\S+)\s*$')) {
                $found = $true
                $indent = $Matches[1]
                $current = $Matches[2]
                if ($current -ne $desired) {
                    $lines[$i] = "$indent${k}: $desired"
                    $changed += ("{0}: {1} -> {2}" -f $k, $current, $desired)
                }
                break
            }
        }
        if (-not $found) {
            # WorldGuard は既定値を必ず書き出すので、通常はここへ来ない。
            # 来たときは節の末尾へ足す（インデントは節内の既存行に合わせる）。
            $indent = "    "
            for ($i = $start + 1; $i -lt $stop; $i++) {
                if ($lines[$i] -match '^(\s+)\S') { $indent = $Matches[1]; break }
            }
            $lines = $lines[0..($stop - 1)] + @("$indent${k}: $desired") + $lines[$stop..($lines.Count - 1)]
            $stop++
            $changed += ("{0}: (無し) -> {1}" -f $k, $desired)
        }
    }

    Write-Host ("[{0}] {1}" -f $name, $file)
    if ($changed.Count -eq 0) {
        Write-Host "       変更なし（すでに $desired）" -ForegroundColor DarkGray
        continue
    }
    foreach ($c in $changed) { Write-Host "       $c" -ForegroundColor Cyan }
    $changedAny = $true

    if ($DryRun) { continue }

    Copy-Item -LiteralPath $file -Destination "$file.bak-$stamp" -Force
    # ⚠ BOM を付けない。yml の途中に BOM が来ると SnakeYAML が文書境界と誤読する。
    [System.IO.File]::WriteAllText($file, ($lines -join $newline),
        (New-Object System.Text.UTF8Encoding $false))
    Write-Host ("       書き込み OK（退避: {0}）" -f (Split-Path "$file.bak-$stamp" -Leaf)) -ForegroundColor Green
}

Write-Host ""
if ($DryRun) {
    Write-Host "DryRun のためここで終了します。何も書いていません。" -ForegroundColor Green
    exit 0
}
if (-not $changedAny) {
    Write-Host "すべて既に希望どおりでした。" -ForegroundColor Green
    exit 0
}
Write-Host "次にやること:" -ForegroundColor Yellow
Write-Host "  1. サーバ上で /wg reload （または再起動）— 書き換えただけでは効かない"
Write-Host "  2. サーバ上で /gamerule mobGriefing true — 村人の食料拾い（＝繁殖）を戻す"
Write-Host "     ⚠ これはワールドごと。オーバーワールドで実行すること。"
Write-Host "  3. クリーパーを1体爆発させて、地形が抜けないこと・ダメージは入ることを確認する"
