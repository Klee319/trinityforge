<#
.SYNOPSIS
    配備先の ArsPaper 設定で「無限ソース核」を素材アイテムから特殊アイテムへ移す。

.DESCRIPTION
    2026-08-24 の報告「無限ソース核の実装がないのにブロックとして登録されていそう。
    アイテムカタログではネザースターで素材として定義されているはず」への対処。

    【何が起きていたか】
    無限ソース核は Java 側のカスタムブロック(InfinitySourceCore)として実装されている。
    ArsPaper は素材アイテムを登録するとき「その id が既にブロックとして登録済みなら丸ごと
    スキップする」ので、materials.yml に書いてあった base_material(NETHER_STAR) /
    custom_model_data(5445) / display_name / lore は **一度も効いていなかった**
    （効いていたのは recipe だけ）。設定エディタの素材画面で直しても無反応になる。

    実装を Java 側で特別扱いしているアイテムの正典は functional-items.yml
    （設定エディタの「特殊アイテム」画面）なので、そちらへ移す。

    【なぜスクリプトが要るのか】
    ArsPaper は既存の yml を自分では更新しない（saveResource(..., false)）。
    つまり **jar を差し替えても /ars reload しても配備先の yml は書き換わらない**。
    リポジトリ側の編集を届ける経路は ops\launch\deploy-config-head.cmd だけで、
    そちらは全バックエンドを止めてからでないと流せない。

    このスクリプトは「止めずに今すぐ揃えたい」ときの経路。やることは3つ:
      1. materials.yml の infinity_source_core エントリを削除し、
         「ここに書かない」旨の注意コメントへ置き換える（リポジトリ側と同じ文面）
      2. materials.yml の _editor.categories にある - infinity_source_core を削除する
      3. functional-items.yml の items: へ infinity_source_core を追加する
         （source_berry の手前＝リポジトリ側と同じ並び順）

    どれも既に済んでいれば触らない（何度流しても安全）。

.PARAMETER Target
    all（既定）、または ops-config.psd1 の Servers にあるサーバ名。

.PARAMETER DryRun
    書き込まず、変更予定だけを出す。

.EXAMPLE
    .\apply-infinity-core-to-functional-items.ps1 -DryRun

.EXAMPLE
    .\apply-infinity-core-to-functional-items.ps1
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

# materials.yml のエントリを置き換える注意コメント。リポジトリの
# fork-handoff\arspaper\fork\src\main\resources\materials.yml と 1 文字も違えてはいけない
# （違うと配備で drift 扱いになり、どちらが正か分からなくなる）。
$materialsNote = @(
    "  # ⚠ infinity_source_core はここに書かない(2026-08-24 に functional-items.yml へ移設)。",
    "  #   実体は Java のカスタムブロック(InfinitySourceCore)で、ブロック登録済みの id は",
    "  #   素材アイテム登録の側で丸ごとスキップされる。つまりここに書いた base_material /",
    "  #   display_name / lore は一度も効かず、設定エディタで直しても無反応になる。",
    "  #   表示と儀式レシピの正典は functional-items.yml(「特殊アイテム」画面)。"
)

# functional-items.yml へ挿入するエントリ。こちらもリポジトリ側と完全一致させる。
$functionalEntry = @(
    "  # 到達証明「無限ソース核」。実体は Java のカスタムブロック(InfinitySourceCore)。",
    "  # ⚠ 2026-08-24 に materials.yml から移設した。あちらにも同じ id の定義が残っていたが、",
    "  #   ブロック登録済みの id は素材アイテム登録の側で丸ごとスキップされる仕様なので、",
    "  #   base_material(NETHER_STAR)も display_name も lore も一度も効いていなかった",
    "  #   (効いていたのは recipe だけ)。実装を Java 側で特別扱いしているアイテムは",
    "  #   materials.yml ではなくこの「特殊アイテム」へ置くこと。",
    "  # material / custom-model-data はブロック系なので編集不可(書いても無視され警告が出る)。",
    "  infinity_source_core:",
    "    display-name: <gold><bold>無限ソース核</bold></gold>",
    "    enchant-glow: true",
    "    lore:",
    "      - <dark_purple>ここまでに築いた貯蔵設備そのものを焚べた結果。</dark_purple>",
    "      - <dark_purple>1億のソースが1点に折り畳まれている。</dark_purple>",
    '      - ""',
    "      - <yellow>第2の目標「1億ソース」の到達証明</yellow>",
    "      - <gray>設置すると周囲のソースリンクを強化する</gray>",
    "      - <dark_gray>右クリックで回収可能(消費されない)</dark_gray>",
    "    recipe:",
    "      method: ritual",
    "      core-item: custom:source_singularity_jar",
    "      pedestal-items:",
    "        - custom:source_singularity_jar x3",
    "        - custom:source_engine x8",
    "      source: 45000000",
    "      type: shapeless",
    "      ingredients: []"
)

# yml を UTF-8(BOM なし) + LF で書き戻す。PowerShell の > や Set-Content -Encoding utf8 は
# BOM を付けるので使わない（yml の途中に来た BOM を SnakeYAML は文書境界と誤読する）。
function Write-YamlLines {
    param([string] $Path, [string[]] $Lines)
    $content = ($Lines -join "`n") + "`n"
    [System.IO.File]::WriteAllText($Path, $content, [System.Text.UTF8Encoding]::new($false))
}

function Backup-File {
    param([string] $Path)
    $backup = "$Path.bak-" + (Get-Date).ToString("yyyyMMdd_HHmmss")
    Copy-Item -LiteralPath $Path -Destination $backup
    Write-OpsLog "  退避しました: $(Split-Path $backup -Leaf)"
}

Write-OpsLog "配備先の ArsPaper 設定で infinity_source_core を特殊アイテムへ移します"

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
    Write-Host ""
    Write-OpsLog "--- $($server.Name) ---"

    $materialsFile  = Join-Path $server.Root "plugins\ArsPaper\materials.yml"
    $functionalFile = Join-Path $server.Root "plugins\ArsPaper\functional-items.yml"

    if (-not (Test-Path -LiteralPath $materialsFile)) {
        Write-OpsLog "ArsPaper が入っていないバックエンドです（materials.yml なし）: $materialsFile"
        continue
    }
    if (-not (Test-Path -LiteralPath $functionalFile)) {
        $failures.Add("[$($server.Name)] functional-items.yml がありません: $functionalFile")
        continue
    }

    $touched = $false

    # ---- 1) materials.yml: エントリ削除 + 注意コメントへ置き換え / カテゴリ行の削除 ----
    $lines = @(Get-Content -LiteralPath $materialsFile)
    $out = New-Object System.Collections.Generic.List[string]
    $removedEntry     = $false
    $removedCategory  = 0
    $noteAlreadyThere = [bool]($lines | Where-Object { $_ -match '^\s*#\s*⚠ infinity_source_core はここに書かない' })

    $i = 0
    while ($i -lt $lines.Count) {
        $line = $lines[$i]

        # materials: 直下の infinity_source_core: ブロック（次の兄弟キーの手前まで）を落とす
        if ($line -match '^\s{2}infinity_source_core:\s*$') {
            $i++
            while ($i -lt $lines.Count -and $lines[$i] -notmatch '^\s{0,2}\S') { $i++ }
            $removedEntry = $true
            if (-not $noteAlreadyThere) {
                foreach ($noteLine in $materialsNote) { $out.Add($noteLine) }
                $noteAlreadyThere = $true
            }
            continue
        }

        # _editor.categories の - infinity_source_core を落とす
        if ($line -match '^\s*-\s*infinity_source_core\s*$') {
            $removedCategory++
            $i++
            continue
        }

        $out.Add($line)
        $i++
    }

    if ($removedEntry -or $removedCategory -gt 0) {
        $what = @()
        if ($removedEntry) { $what += "エントリ" }
        if ($removedCategory -gt 0) { $what += "カテゴリ行 $removedCategory 件" }
        Write-OpsLog "  materials.yml: $($what -join ' / ') を削除します" -Level $(if ($DryRun) { "DRYRUN" } else { "INFO" })
        if (-not $DryRun) {
            Backup-File -Path $materialsFile
            Write-YamlLines -Path $materialsFile -Lines $out
        }
        $touched = $true
    } else {
        Write-OpsLog "  materials.yml: 変更なし（既に移設済み）"
    }

    # ---- 2) functional-items.yml: items: 配下へエントリを追加 ----
    $fLines = @(Get-Content -LiteralPath $functionalFile)
    if ($fLines | Where-Object { $_ -match '^\s{2}infinity_source_core:\s*$' }) {
        Write-OpsLog "  functional-items.yml: 変更なし（既に infinity_source_core があります）"
    } else {
        $start = -1
        for ($j = 0; $j -lt $fLines.Count; $j++) {
            if ($fLines[$j] -match '^items:\s*$') { $start = $j; break }
        }
        if ($start -lt 0) {
            $failures.Add("[$($server.Name)] items: が見つかりません: $functionalFile")
            continue
        }

        # source_berry の手前へ入れる ＝ リポジトリ側と同じ並び順になる。
        $index = -1
        for ($j = $start + 1; $j -lt $fLines.Count; $j++) {
            if ($fLines[$j] -match '^\S') { break }   # インデントが戻ったらブロックの外
            if ($fLines[$j] -match '^\s{2}source_berry:\s*$') { $index = $j; break }
        }
        if ($index -lt 0) {
            $failures.Add("[$($server.Name)] items: の中に source_berry がありません（挿入位置を決められない）: $functionalFile")
            continue
        }

        Write-OpsLog "  functional-items.yml: $($index + 1) 行目(source_berry)の直前へ $($functionalEntry.Count) 行を挿入します" -Level $(if ($DryRun) { "DRYRUN" } else { "INFO" })
        if (-not $DryRun) {
            Backup-File -Path $functionalFile
            $merged = @()
            $merged += $fLines[0..($index - 1)]
            $merged += $functionalEntry
            $merged += $fLines[$index..($fLines.Count - 1)]
            Write-YamlLines -Path $functionalFile -Lines $merged
        }
        $touched = $true
    }

    if (-not $touched) { continue }
    if ($DryRun) { $pending++; continue }

    # 書けたことを読み直して確認する。
    $verifyMaterials  = @(Get-Content -LiteralPath $materialsFile)
    $verifyFunctional = @(Get-Content -LiteralPath $functionalFile)
    $ok = $true
    if ($verifyMaterials | Where-Object { $_ -match '^\s{2}infinity_source_core:\s*$' }) { $ok = $false }
    if ($verifyMaterials | Where-Object { $_ -match '^\s*-\s*infinity_source_core\s*$' }) { $ok = $false }
    if (-not ($verifyFunctional | Where-Object { $_ -match '^\s{2}infinity_source_core:\s*$' })) { $ok = $false }
    if ($ok) {
        Write-OpsLog "  反映を確認しました"
        $changed++
    } else {
        $failures.Add("[$($server.Name)] 書き込み後の確認に失敗しました（materials.yml / functional-items.yml を目視してください）")
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
        Write-OpsLog "DRY RUN: 全台が既に移設済みです。" -Level DRYRUN
    }
    exit 0
}

if ($changed -gt 0) {
    Write-OpsLog "$changed 台へ反映しました。"
    Write-OpsLog "反映は各サーバで /ars reload。表示名と lore は特殊アイテム側から出るようになります。" -Level WARN
} else {
    Write-OpsLog "全台が既に移設済みです。変更はありません。"
}
exit 0
