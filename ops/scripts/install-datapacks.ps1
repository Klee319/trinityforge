<#
.SYNOPSIS
    案1 の構造物/地形データパックを資源サーバへ配置する (初回配備・入れ替え用)。

.DESCRIPTION
    やること:
      1. -SourceDir の zip を検査する (案1 に含まれない物・排他の物・重複を弾く)
      2. 資源サーバの正本フォルダ (ops-config.psd1 の ResourceDatapacks.Source) へ複製する
      3. 資源サーバが停止していれば world\datapacks へも即反映する
         (稼働中なら反映しない。次のリセットで reset-resource.ps1 が入れる)

    ⚠ 正本は world の外に置く。world\datapacks へ直接入れると【週次リセットで全部消える】。
      詳細は ops-config.sample.psd1 の ResourceDatapacks のコメント。

    ⚠ このスクリプトは D:\game 配下へ書き込むので、エージェントではなく人が実行する。

.PARAMETER SourceDir
    ダウンロード済み zip の置き場。既定はこのリポジトリの tmp\worldgen\downloads。

.PARAMETER StrongholdVariant
    要塞オーバーホールの版。Full(v2.4.0) か Lite(v1.3) のどちらか一方しか入れられない。
    両方入れると同じ構造物を 2 つのパックが上書きし合い、どちらが勝つかは読み込み順まかせになる。

.PARAMETER DryRun
    コピーせず、配置計画だけを出力する。

.EXAMPLE
    .\install-datapacks.ps1 -DryRun

.EXAMPLE
    .\install-datapacks.ps1 -StrongholdVariant Lite
#>
[CmdletBinding()]
param(
    [string] $ConfigPath,
    [string] $SourceDir,
    [ValidateSet("Full", "Lite")] [string] $StrongholdVariant = "Full",
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")

# 案1 に入れるパック。ファイル名の一部で照合する (版番号が上がっても拾えるように前方一致)。
# ここに無い zip はコピーしない ―― downloads には minecraft_server.jar のような
# データパックでない物も混ざっており、丸ごとコピーすると Paper が起動時に警告を吐き続ける。
$PLAN_ONE = @(
    @{ Match = "Dungeons and Taverns";              Note = "DnT 本体 (nova_structures)" }
    @{ Match = "DnT Ancient City Overhaul";         Note = "古代都市" }
    @{ Match = "DnT Desert Temple Overhaul";        Note = "砂漠の寺院" }
    @{ Match = "DnT Jungle Temple Overhaul";        Note = "ジャングルの寺院" }
    @{ Match = "DnT Mineshaft Overhaul";            Note = "廃坑" }
    @{ Match = "DnT Nether Fortress Overhaul";      Note = "ネザー要塞" }
    @{ Match = "DnT Ocean Monument Overhaul";       Note = "海底神殿" }
    @{ Match = "DnT Pillager Outpost Overhaul";     Note = "ピリジャー前哨基地" }
    @{ Match = "DnT Swamp Hut Overhaul";            Note = "沼の小屋" }
    @{ Match = "DnT Woodland Mansion Overhual";     Note = "森の洋館 (配布名の綴りが Overhual)" }
    @{ Match = "Structory_v";                       Note = "Structory (structory)" }
    @{ Match = "Structory_Towers";                  Note = "Structory Towers (structory_towers)" }
    @{ Match = "t_and_t-datapack";                  Note = "Towns and Towers (戦利品は kaisyn 名前空間)" }
    @{ Match = "Terralith";                         Note = "地形 (terralith)" }
    @{ Match = "Incendium";                         Note = "ネザー地形 (incendium)" }
    @{ Match = "Nullscape";                         Note = "エンド地形 (nullscape)" }
)

# 要塞オーバーホールは排他。両方入れてはいけない。
$STRONGHOLD = @{
    Full = @{ Match = "DnT Stronghold Overhaul v";    Note = "要塞 (フル版)" }
    Lite = @{ Match = "DnT Stronghold Overhaul LITE"; Note = "要塞 (軽量版)" }
}

if (-not $SourceDir) {
    # $PSScriptRoot は ops\scripts。ops -> リポジトリルート と 2 つ上がる。
    $repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
    $SourceDir = Join-Path $repoRoot "tmp\worldgen\downloads"
}

if (-not (Test-Path -LiteralPath $SourceDir)) {
    throw "データパックの置き場がありません: $SourceDir`n" +
          "-SourceDir で指定するか、tmp\worldgen\downloads に zip を置いてください。"
}

$config   = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false
$resource = $config.Servers.Resource
# これから正本を作るので、空でも止めない。
$plan     = Get-ResourceDatapackPlan -Config $config -ResourceServer $resource -SkipRequiredCheck
if (-not $plan) {
    throw "ops-config.psd1 に ResourceDatapacks がありません。" +
          "ops/ops-config.sample.psd1 の該当ブロックを写してください。"
}

Write-OpsLog "データパックの置き場: $SourceDir"
Write-OpsLog "正本の配置先:        $($plan.Source)"
Write-OpsLog "ワールドの配置先:    $($plan.Destination)"
Write-OpsLog "要塞オーバーホール:  $StrongholdVariant"

# ---- 1. 照合 -----------------------------------------------------------------------------------

$available = @(Get-ChildItem -LiteralPath $SourceDir -Filter "*.zip" -File)
$wanted    = @($PLAN_ONE) + @($STRONGHOLD[$StrongholdVariant])

$selected = @()
$missing  = @()
foreach ($entry in $wanted) {
    $hits = @($available | Where-Object { $_.Name -like "$($entry.Match)*" })
    if ($hits.Count -eq 0) {
        $missing += $entry
        continue
    }
    if ($hits.Count -gt 1) {
        # 同じパックの複数版が並んでいると、どれが効くか読み込み順まかせになる。ここで止める。
        throw "中断: 『$($entry.Match)』に一致する zip が $($hits.Count) 件あります。" +
              "古い版を $SourceDir から退けてから実行してください:`n  " +
              (($hits.Name) -join "`n  ")
    }
    $selected += [pscustomobject]@{ File = $hits[0]; Note = $entry.Note }
}

$other = @($STRONGHOLD.Keys | Where-Object { $_ -ne $StrongholdVariant })[0]
$otherHits = @($available | Where-Object { $_.Name -like "$($STRONGHOLD[$other].Match)*" })
if ($otherHits) {
    Write-OpsLog ("要塞オーバーホールの $other 版は配置しません (排他): " +
        (($otherHits.Name) -join ", ")) -Level WARN
}

$skipped = @($available | Where-Object { $f = $_; -not ($selected | Where-Object { $_.File.FullName -eq $f.FullName }) })

Write-Host ""
Write-OpsLog "--- 配置する ($($selected.Count) 件) ---"
foreach ($item in $selected) {
    Write-OpsLog ("  {0,-42} {1}" -f $item.File.Name, $item.Note)
}
if ($skipped) {
    Write-Host ""
    Write-OpsLog "--- 配置しない ($($skipped.Count) 件) ---"
    foreach ($file in $skipped) { Write-OpsLog "  $($file.Name)" }
}
if ($missing) {
    Write-Host ""
    foreach ($entry in $missing) {
        Write-OpsLog "見つかりません: $($entry.Match)* ($($entry.Note))" -Level WARN
    }
    Write-OpsLog ("$($missing.Count) 件が不足しています。" +
        "そのパックの構造物は生成されず、戦利品プールも当たりません。") -Level WARN
}
if ($selected.Count -eq 0) {
    throw "中断: 配置できる zip が 1 件もありません。"
}

# ---- 2. 正本へ複製 -----------------------------------------------------------------------------

Write-Host ""
Write-OpsLog "--- 正本へ複製 ---"
if ($DryRun) {
    Write-OpsLog "$($plan.Source) を作り直して $($selected.Count) 件をコピーする" -Level DRYRUN
} else {
    if (Test-Path -LiteralPath $plan.Source) {
        Remove-DirectorySafely -Path $plan.Source
    }
    New-Item -ItemType Directory -Path $plan.Source -Force | Out-Null
    foreach ($item in $selected) {
        Copy-Item -LiteralPath $item.File.FullName -Destination $plan.Source -Force
    }
    Write-OpsLog "正本を更新しました: $($selected.Count) 件 -> $($plan.Source)"
}

# ---- 3. 稼働中でなければワールドへも反映 --------------------------------------------------------

Write-Host ""
# RCON パスワードが無いと稼働中かどうかを確かめられない。稼働中の world を触るのは危険なので、
# 判定できない場合は【触らない】側に倒す。
if ([string]::IsNullOrWhiteSpace($resource.RconPassword)) {
    Write-OpsLog ("RCON パスワード (TF_RCON_RESOURCE_PASSWORD) が未設定なので、" +
        "資源サーバが稼働中か判定できません。world\datapacks へは反映しません。" +
        "次の週次リセットで入ります。") -Level WARN
    exit 0
}
$running = Test-RconReachable -HostName $resource.RconHost -Port $resource.RconPort `
    -Password $resource.RconPassword

if ($running) {
    Write-OpsLog ("資源サーバが稼働中なので world\datapacks へは反映しません。" +
        "次の週次リセット (reset-resource.ps1) で入ります。" +
        "すぐ反映したい場合はサーバを停止してからもう一度実行してください。") -Level WARN
    exit 0
}

# 停止中なら今の world へも入れる。ただし既存ワールドのチャンクは生成済みなので、
# 新しい構造物が出るのは【まだ生成していない範囲だけ】。継ぎ目が気になるなら
# reset-resource.ps1 でワールドごと作り直すこと。
$freshPlan = Get-ResourceDatapackPlan -Config $config -ResourceServer $resource
Install-ResourceDatapacks -Plan $freshPlan -DryRun:$DryRun

Write-Host ""
Write-OpsLog ("既存ワールドに入れた場合、新しい構造物は未生成のチャンクにしか出ません。" +
    "全域へ行き渡らせるには reset-resource.ps1 でワールドを作り直してください。") -Level WARN
Write-OpsLog "完了しました。"
exit 0
