<#
.SYNOPSIS
    配備済み EliteMobs の MobCombatSettings.yml で「バニラモブのエリート化」を止める。

.DESCRIPTION
    実サーバ報告(2026-08-18)「バニラのエリートモブの仕様でエリート化されたモブが湧いてしまっている。
    テキストディスプレイが重複している」への対処。

    EliteMobs は自然湧きしたバニラモブを一定確率で「エリート ○○」へ変換し、
    レベル入りの名前(Lv 「N」エリート クリーパー)を持たせる。TrinityForge 側は
    すべてのモブに独自のレベル/HP/EXP を持たせて FocusHp 表示(Lv.N + 名前 + HP)を出すので、
    この変換は【表示が二重になる】うえに【レベル体系も二重になる】。
    combat/mob-level-table.yml と combat/mob-overrides.yml がバニラモブを既に管理しているため、
    EliteMobs 側の自然エリート化は TrinityForge 構成では役割が無い。

    このスクリプトは MobCombatSettings.yml の

        doNaturalEliteMobSpawning: true  ->  false

    だけを書き換える。ファイル全体は上書きしない(他のキーに運用判断が入っている可能性があるため)。
    カスタムボス・ダンジョンのエリート・スポナー由来のイベントモブは別のキーで制御されるので
    このスクリプトの影響を受けない。

    ⚠️ 稼働中に書き換えても反映されない(起動時にしか読まない)。サーバを止めてから実行すること。
    ⚠️ 既に湧いてしまったエリートは残る。倒す/デスポーンするまで表示は二重のまま。

.PARAMETER Apply
    実際に書き換える。指定しない場合は変更予定を表示するだけ(既定=ドライラン)。
    書き換える前に同じ場所へ .bak-<日時> を残す。

.PARAMETER Revert
    逆向き(false -> true)に戻す。エリート化を復活させたくなったとき用。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\disable-natural-elite-spawning.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\disable-natural-elite-spawning.ps1 -Apply
#>
param(
    [switch] $Apply,
    [switch] $Revert,
    [string] $ConfigPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Common.ps1")

# 設定ファイルを読み書きするだけなので RCON パスワードは要らない。
$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false

$dryRun = -not $Apply
$fromValue = if ($Revert) { "false" } else { "true" }
$toValue = if ($Revert) { "true" } else { "false" }
$key = "doNaturalEliteMobSpawning"

Write-Host ""
Write-Host ("EliteMobs {0}: {1} -> {2} ({3})" -f $key, $fromValue, $toValue,
    $(if ($dryRun) { "ドライラン" } else { "書き換え" }))
Write-Host ""

$changedFiles = 0
$missingFiles = 0

foreach ($serverKey in $config.Servers.Keys) {
    $backend = $config.Servers[$serverKey]
    $ymlPath = Join-Path $backend.Root "plugins\EliteMobs\MobCombatSettings.yml"

    if (-not (Test-Path -LiteralPath $ymlPath)) {
        # EliteMobs が入っていないバックエンド(資源サーバなど)は対象外。
        Write-Host ("  [SKIP] {0}: plugins\EliteMobs\MobCombatSettings.yml がありません" -f $backend.Name)
        $missingFiles++
        continue
    }

    $lines = [System.IO.File]::ReadAllLines($ymlPath)
    $pending = @()

    for ($i = 0; $i -lt $lines.Length; $i++) {
        # トップレベルのキーなのでインデント無し。目的の値のときだけ拾う
        # (既に目的の状態なら触らない = 何度実行しても同じ結果)。
        if ($lines[$i] -match ("^" + [regex]::Escape($key) + ":\s*" + $fromValue + "\s*$")) {
            $pending += $i
        }
    }

    if ($pending.Count -eq 0) {
        Write-Host ("  [OK  ] {0}: 直す対象なし(既に {1} / キーが無い)" -f $backend.Name, $toValue)
        continue
    }

    Write-Host ("  [{0}] {1}: {2}" -f $(if ($dryRun) { "DRY " } else { "FIX " }), $backend.Name, $ymlPath)
    foreach ($index in $pending) {
        Write-Host ("          行 {0}: {1}: {2}  ->  {3}" -f ($index + 1), $key, $fromValue, $toValue)
    }

    if ($dryRun) { continue }

    foreach ($index in $pending) {
        $lines[$index] = ("{0}: {1}" -f $key, $toValue)
    }

    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    Copy-Item -LiteralPath $ymlPath -Destination ("{0}.bak-{1}" -f $ymlPath, $stamp) -Force
    # 元ファイルは BOM なし UTF-8。BOM を付けると Bukkit の YAML パーサが先頭キーを取り違える。
    [System.IO.File]::WriteAllLines($ymlPath, $lines, (New-Object System.Text.UTF8Encoding($false)))
    $changedFiles++
}

Write-Host ""
if ($dryRun) {
    Write-Host "ドライランでした。実際に書き換えるには -Apply を付けて再実行してください。"
} else {
    Write-Host ("書き換えたファイル: {0} 件(.bak-<日時> を同じ場所に残しました)" -f $changedFiles)
    Write-Host "起動後、バニラモブが『エリート ○○』へ変換されないことを確認してください。"
    Write-Host "既に湧いているエリートは倒す/デスポーンするまで残ります。"
}
if ($missingFiles -gt 0) {
    Write-Host ("EliteMobs 未導入のバックエンド: {0} 件(対象外)" -f $missingFiles)
}
