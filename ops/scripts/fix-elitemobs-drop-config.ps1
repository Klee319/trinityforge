<#
.SYNOPSIS
    配備済み EliteMobs の trinityforge.yml に残った古い elite-drop-sources の値を直す。

.DESCRIPTION
    実サーバ報告「EMモブがバニラEMで設定されたアイテムを落とす」の真因はコードではなく
    【配備済み設定ファイルに古い値が残っていること】だった。

    このフォークの設定ローダー(TrinityForgeConfigMigration#appendMissingKeys)は
    「そのファイルに存在しなかったキーだけを jar の既定値で追記し、既存の値は絶対に書き換えない」
    方式なので、jar を新しくしても既に書かれている値は変わらない。
    既定値を false へ変えたコミットが配備済みサーバでは一切効かない、という形の無言故障になる。

    起動ログの 1 行がその証拠になる(遮断されたものだけが列挙される):
      [EliteMobs] TrinityForge: EliteMobs-originated drop sources blocked by elite-drop-sources
      in trinityforge.yml: random-loot ..., special-loot, elite-scroll,
      vanilla-loot-multiplier, treasure-chest-loot, arena-loot
    ここに currency-shower と boss-unique-loot が並んでいなければ、その2経路は生きている。

    このスクリプトは【その2キーの値だけ】を書き換える。ファイル全体を上書きしないのは、
    ほかのキーに運用側の判断が入っている可能性があるため。
    vanilla-loot は意図的に触らない — false にするとモブ本来のバニラドロップまで消え、
    TrinityForge 側 combat/mob-overrides.yml の drops: が前提にしている通常ドロップが無くなる。

    ⚠️ 稼働中に設定を書き換えても反映されない(起動時にしか読まない)。
       サーバを止めてから実行し、そのあと起動すること。

.PARAMETER Apply
    実際に書き換える。指定しない場合は変更予定を表示するだけ(既定=ドライラン)。
    書き換える前に同じ場所へ .bak-<日時> を残す。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\fix-elitemobs-drop-config.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\fix-elitemobs-drop-config.ps1 -Apply
#>
param(
    [switch] $Apply,
    [string] $ConfigPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Common.ps1")

# 設定ファイルを読み書きするだけなので RCON パスワードは要らない。
$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false

$dryRun = -not $Apply

# 「EliteMobs 側で設定されたアイテムを一切落とさない」ために false にすべきキー。
# vanilla-loot は含めない(上の DESCRIPTION の理由)。
$targetKeys = @("currency-shower", "boss-unique-loot")

Write-Host ""
Write-Host ("EliteMobs elite-drop-sources の点検 ({0})" -f $(if ($dryRun) { "ドライラン" } else { "書き換え" }))
Write-Host ""

$changedFiles = 0
$missingFiles = 0

foreach ($key in $config.Servers.Keys) {
    $backend = $config.Servers[$key]
    $ymlPath = Join-Path $backend.Root "plugins\EliteMobs\trinityforge.yml"

    if (-not (Test-Path -LiteralPath $ymlPath)) {
        # EliteMobs が入っていないバックエンド(資源サーバなど)は対象外。
        Write-Host ("  [SKIP] {0}: plugins\EliteMobs\trinityforge.yml がありません" -f $backend.Name)
        $missingFiles++
        continue
    }

    $lines = [System.IO.File]::ReadAllLines($ymlPath)
    $pending = @()

    for ($i = 0; $i -lt $lines.Length; $i++) {
        foreach ($k in $targetKeys) {
            # elite-drop-sources: の子なのでインデント付き。値が true のものだけ拾う
            # (既に false なら触らない = 何度実行しても同じ結果)。
            if ($lines[$i] -match ("^(\s+)" + [regex]::Escape($k) + ":\s*true\s*$")) {
                $pending += [pscustomobject]@{ Index = $i; Key = $k; Indent = $Matches[1] }
            }
        }
    }

    if ($pending.Count -eq 0) {
        Write-Host ("  [OK  ] {0}: 直す対象なし(既に false / キー自体が無い=jar既定の false)" -f $backend.Name)
        continue
    }

    Write-Host ("  [{0}] {1}: {2}" -f $(if ($dryRun) { "DRY " } else { "FIX " }), $backend.Name, $ymlPath)
    foreach ($p in $pending) {
        Write-Host ("          行 {0}: {1}: true  ->  false" -f ($p.Index + 1), $p.Key)
    }

    if ($dryRun) { continue }

    foreach ($p in $pending) {
        $lines[$p.Index] = ("{0}{1}: false" -f $p.Indent, $p.Key)
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
    Write-Host "起動後、ログの 'drop sources blocked by elite-drop-sources' の行に"
    Write-Host "currency-shower と boss-unique-loot が並んでいることを確認してください。"
}
if ($missingFiles -gt 0) {
    Write-Host ("EliteMobs 未導入のバックエンド: {0} 件(対象外)" -f $missingFiles)
}
