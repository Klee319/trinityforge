<#
.SYNOPSIS
    配備先で直接編集された yml を、上書きされる前に検出して退避する。

.DESCRIPTION
    2026-08-19 (W-110) に追加。

    ── なぜ要るのか ────────────────────────────────────────────────────────────
    config 配備は【リポジトリ → サーバの一方向】で、逆流の経路は無い。
    W-106 で既定を「HEAD ＋ ワーキングツリーの yml を重ねる」に変えたが、
    重ねる元はあくまでリポジトリの src\main\resources であって配備先ではない。

    ところが設定エディタ(tools\config-editor)は【全ての yml に画面を持っているわけではない】。
    画面が無い yml(network.yml など)を直したいとき、人がやれることは
    「配備先の実ファイルを直接編集する」しかない。そしてその編集は
    次の config 配備で【無言で上書きされる】。2026-08-19 に network.yml で実際に起きた。
    上書き前のバックアップも無かったので、書いた内容は復元できなかった。

    ── どう塞ぐか ──────────────────────────────────────────────────────────────
    「配備先の現物」を「前回の配備直後の姿」と突き合わせる。違っていれば
    それは配備後に人か サーバが書いたものなので、上書きする前に退避して名指しで報告する。

    前回の姿は配備の最後に -Mode Record で撮っておく(パス→SHA-256 の一覧)。
    台帳が無い初回や tmp を消した後は「判定できない」と明示して、
    差分の有無に関わらず現物を丸ごと退避する【安全側に倒す】。

    検出しても配備は止めない。退避してあるので失われないし、
    止めると「エディタで直したものを配りたいだけ」の通常運用まで巻き込まれる。
    代わりに一覧を必ず出し、呼び出し側(deploy-config-head.cmd)が最後にもう一度出す。

    ── 2026-08-20 (W-177): 退避だけでは足りないので【リポジトリへ取り込む】────────────
    退避は「消えはしない」だけで、配備先の内容は結局 HEAD で上書きされる。人から見れば
    設定は元に戻ったままなので、同じ報告(「設定したのにロールバックした」)が再発した。
    そこで Check は退避したあと、その内容を【リポジトリの src\main\resources へ書き戻す】。
    書き戻したファイルは W-106 のワーキングツリー重ね掛けで、そのまま同じ配備に乗る。
    配備の向きが repo -> server の一方通行でなくなり、ロールバックが構造的に起きなくなる。

    書き戻す条件は次の全部。1つでも欠けたら退避だけして報告する:
      - リポジトリ側に【同じ相対パスのファイルが実在する】
        (無いものはプラグインが自分で吐いた生成物。取り込むと出荷物が汚れる)
      - $RuntimeStateNames に載っていない
        (サーバが実行中に書き換える状態ファイル。人の編集ではない)
      - 同じ相対パスを別のバックエンドから既に取り込んでいない
        (ArsPaper は配備先が3つ。食い違っていたら後勝ちにせず報告だけする)

    取り込んだ結果はワーキングツリーの未コミット変更になる。CLAUDE.md の
    「config の yml を編集したらその作業のコミットに必ず含める」に従って commit すること。
    取り込みたくないときは -NoPromote。

.PARAMETER Mode
    Check  : 上書き前の検査。ドリフトを検出して退避し、一覧を出す。
    Record : 配備後の台帳更新。現在の配備先の姿を記録する。

.PARAMETER VelocityRoot
    バックエンドの親ディレクトリ(launch-config.cmd の VELOCITY_ROOT)。

.PARAMETER ConfigHost
    TrinityForge の config 実体があるバックエンド名(TF_CONFIG_HOST)。
    他のバックエンドの plugins\TrinityForge はここへのジャンクションなので、1 回だけ見る。

.PARAMETER Backends
    バックエンド名を空白区切りで(TF_BACKENDS)。ArsPaper の config は実体が 3 つあるので全部見る。
    cmd 側から配列を渡すと引用符が壊れるので、意図的に「1 本の文字列」で受ける。

.PARAMETER ManifestPath
    台帳の位置。省略時は <RepoRoot>\tmp\deploy-config-manifest.json。

.PARAMETER BackupRoot
    退避先の親。省略時は <RepoRoot>\tmp\deploy-config-backup。

.PARAMETER NoBackup
    検出と報告だけ行い、退避のコピーはしない(--dry-run 用)。

.OUTPUTS
    人が読む報告。終了コードは常に 0(配備を止めないため)。
    ドリフトを検出したかどうかは、標準出力の "[DRIFT]" 行の有無で分かる。

.EXAMPLE
    .\guard-deployed-config.ps1 -Mode Check -VelocityRoot "D:\...\Velocity_for_TF" -ConfigHost Main_Server -Backends "Main_Server Resource_Server Dev_Server"

.EXAMPLE
    .\guard-deployed-config.ps1 -Mode Record -VelocityRoot "D:\...\Velocity_for_TF" -ConfigHost Main_Server -Backends "Main_Server Resource_Server Dev_Server"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Check", "Record")]
    [string] $Mode,

    [Parameter(Mandatory = $true)]
    [string] $VelocityRoot,

    [Parameter(Mandatory = $true)]
    [string] $ConfigHost,

    [string] $Backends = "",

    [string] $RepoRoot,
    [string] $ManifestPath,
    [string] $BackupRoot,
    [switch] $NoBackup,
    # 付けると退避と報告だけ行い、リポジトリへの書き戻しをしない (W-177)。
    [switch] $NoPromote
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $RepoRoot) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}
if (-not $ManifestPath) {
    $ManifestPath = Join-Path $RepoRoot "tmp\deploy-config-manifest.json"
}
if (-not $BackupRoot) {
    $BackupRoot = Join-Path $RepoRoot "tmp\deploy-config-backup"
}

# plugin descriptor であって config ではない。配備側でも除外しているので突合せからも外す。
$ExcludedNames = @("paper-plugin.yml")

# サーバが実行中に自分で書き換える yml。中身は「人の編集」ではなく実行時の状態なので、
# 差分が出て当たり前だし、リポジトリへ書き戻してはいけない (W-177)。
#   world_settings.yml : ArsPaper WorldSettingsManager がワールド別BANを保存する
#   source-network.yml : ArsPaper SourceNetwork がソースリンクのブロック座標を保存する
$RuntimeStateNames = @("world_settings.yml", "source-network.yml")

# 配備先のラベル -> リポジトリ側の資源ディレクトリ。
# ArsPaper はバックエンドごとに配備先があるが、リポジトリ側は 1 つしか無い。
$TfSourceRoot  = Join-Path $RepoRoot "TrinityForge\src\main\resources"
$ArsSourceRoot = Join-Path $RepoRoot "fork-handoff\arspaper\fork\src\main\resources"

function Get-SourceInfo {
    <#
    .SYNOPSIS
        配備先ラベルから「リポジトリ側の資源ディレクトリ」と「取り込みの重複判定キー」を返す。
    #>
    param([Parameter(Mandatory)] [string] $Label)

    if ($Label -eq "TrinityForge") {
        return [pscustomobject]@{ Root = $TfSourceRoot; Key = "TF" }
    }
    if ($Label -like "ArsPaper@*") {
        return [pscustomobject]@{ Root = $ArsSourceRoot; Key = "ARS" }
    }
    return $null
}

# 見る配備先を組み立てる。TrinityForge は【1 回だけ】(他はジャンクションで同じ実体を指す)、
# ArsPaper はバックエンドごとに実体があるので全部。
function Resolve-Roots {
    $list = New-Object System.Collections.Generic.List[object]
    $list.Add([pscustomobject]@{
        Label = "TrinityForge"
        Path  = Join-Path (Join-Path $VelocityRoot $ConfigHost) "plugins\TrinityForge"
    })
    foreach ($b in ($Backends -split "\s+" | Where-Object { $_ })) {
        $list.Add([pscustomobject]@{
            Label = "ArsPaper@$b"
            Path  = Join-Path (Join-Path $VelocityRoot $b) "plugins\ArsPaper"
        })
    }
    return $list
}

# 配備先の yml を「相対パス → SHA-256」で拾う。存在しないディレクトリは空で返す。
function Read-DeployedHashes {
    param([string] $Dir)
    $map = @{}
    if (-not (Test-Path -LiteralPath $Dir)) {
        return $map
    }
    $full = (Resolve-Path -LiteralPath $Dir).Path
    foreach ($f in Get-ChildItem -LiteralPath $full -Recurse -Filter *.yml -File -ErrorAction SilentlyContinue) {
        if ($ExcludedNames -contains $f.Name) { continue }
        $rel = $f.FullName.Substring($full.Length).TrimStart("\")
        $map[$rel] = (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash
    }
    return $map
}

function Load-Manifest {
    if (-not (Test-Path -LiteralPath $ManifestPath)) {
        return $null
    }
    try {
        return Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        Write-Host "  [WARN ] 台帳が壊れているので読み飛ばす: $ManifestPath"
        return $null
    }
}

function Get-ManifestRoot {
    param($Manifest, [string] $Label)
    if ($null -eq $Manifest) { return $null }
    if (-not ($Manifest.PSObject.Properties.Name -contains "roots")) { return $null }
    $roots = $Manifest.roots
    if (-not ($roots.PSObject.Properties.Name -contains $Label)) { return $null }
    return $roots.$Label
}

$roots = Resolve-Roots

if ($Mode -eq "Record") {
    $rootsOut = [ordered]@{}
    foreach ($r in $roots) {
        $h = Read-DeployedHashes $r.Path
        $entry = [ordered]@{}
        foreach ($k in ($h.Keys | Sort-Object)) { $entry[$k] = $h[$k] }
        $rootsOut[$r.Label] = $entry
    }
    $doc = [ordered]@{
        generatedAt = (Get-Date).ToString("s")
        note        = "配備直後の配備先 yml の SHA-256。次回配備時に「サーバ側で直接編集されたか」を判定するために使う (W-110)。"
        roots       = $rootsOut
    }
    $dir = Split-Path -Parent $ManifestPath
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    $json = ($doc | ConvertTo-Json -Depth 6)
    [System.IO.File]::WriteAllText($ManifestPath, ($json -replace "`r`n", "`n"), (New-Object System.Text.UTF8Encoding($false)))
    $total = ($rootsOut.Values | ForEach-Object { $_.Count } | Measure-Object -Sum).Sum
    Write-Host "  [ OK  ] 配備後の状態を記録した ($total 件): $ManifestPath"
    exit 0
}

# ---- Mode = Check ----------------------------------------------------------------------------
$manifest = Load-Manifest
$stamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$backupDir = Join-Path $BackupRoot $stamp
$driftCount = 0
$backedUp = 0
$unknown = $false
$promoted = New-Object System.Collections.Generic.List[string]
$promotedKeys = @{}
$notPromoted = New-Object System.Collections.Generic.List[string]

function Copy-Backup {
    <# 上書きされる前の配備先ファイルを退避する。#>
    param([string] $Src, [string] $Label, [string] $Rel)
    $dst = Join-Path (Join-Path $backupDir $Label) $Rel
    $dstDir = Split-Path -Parent $dst
    if (-not (Test-Path -LiteralPath $dstDir)) {
        New-Item -ItemType Directory -Path $dstDir -Force | Out-Null
    }
    Copy-Item -LiteralPath $Src -Destination $dst -Force
}

foreach ($r in $roots) {
    if (-not (Test-Path -LiteralPath $r.Path)) {
        continue
    }
    $current = Read-DeployedHashes $r.Path
    $recorded = Get-ManifestRoot $manifest $r.Label

    if ($null -eq $recorded) {
        # 台帳が無い = 「前回の姿」を知らない。判定できないので全部を退避側に倒す。
        # 取り込み(promote)はしない: 「配備先が新しい」のか「リポジトリが新しい」のかを
        # 区別する材料が無いので、取り込むとリポジトリ側の未配備の変更を巻き戻してしまう。
        $unknown = $true
        Write-Host "  [UNKN ] $($r.Label): 前回配備の台帳が無いので、サーバ側編集の有無を判定できない"

        # 判定できないなりに、目で見る範囲は絞る。リポジトリと中身が違うものだけ名指しする。
        # (どちらが新しいかは分からないが、サーバ側編集は必ずこの一覧の中にある)
        $info = Get-SourceInfo -Label $r.Label
        if ($null -ne $info) {
            foreach ($rel in ($current.Keys | Sort-Object)) {
                $repoFile = Join-Path $info.Root $rel
                if (-not (Test-Path -LiteralPath $repoFile)) { continue }
                $repoHash = (Get-FileHash -LiteralPath $repoFile -Algorithm SHA256).Hash
                if ($repoHash -eq $current[$rel]) { continue }
                Write-Host "  [DIFF ] $($r.Label)\$rel  (リポジトリと中身が違う。どちらが新しいかは台帳が無いので不明)"
            }
        }
        if (-not $NoBackup -and $current.Count -gt 0) {
            foreach ($rel in ($current.Keys | Sort-Object)) {
                $src = Join-Path $r.Path $rel
                $dst = Join-Path (Join-Path $backupDir $r.Label) $rel
                $dstDir = Split-Path -Parent $dst
                if (-not (Test-Path -LiteralPath $dstDir)) {
                    New-Item -ItemType Directory -Path $dstDir -Force | Out-Null
                }
                Copy-Item -LiteralPath $src -Destination $dst -Force
                $backedUp++
            }
            Write-Host "  [ OK  ] $($r.Label): 現物 $($current.Count) 件をまるごと退避した"
        }
        continue
    }

    $recordedNames = $recorded.PSObject.Properties.Name
    foreach ($rel in ($current.Keys | Sort-Object)) {
        $before = $null
        if ($recordedNames -contains $rel) { $before = $recorded.$rel }
        if ($null -eq $before) {
            # 前回配備の後に【配備先で新規に作られた】yml。プラグインが自分で書き出すものも
            # ここに来るので DRIFT ではなく NEW として出す。上書き対象ではないが、
            # 「消えるかも」と誤解されるので必ず見せる。
            Write-Host "  [ NEW ] $($r.Label)\$rel  (前回配備の後に増えた。配備では消えない)"
            continue
        }
        if ($before -eq $current[$rel]) { continue }

        $driftCount++
        Write-Host "  [DRIFT] $($r.Label)\$rel  <- 前回配備の後にサーバ側で書き換えられている"
        $src = Join-Path $r.Path $rel
        if (-not $NoBackup) {
            Copy-Backup -Src $src -Label $r.Label -Rel $rel
            $backedUp++
        }

        # ---- リポジトリへ書き戻す (W-177) ------------------------------------------------
        # ここをやらないと、退避はしたが配備先は HEAD で上書きされる = 人から見れば
        # 設定が元に戻ったまま。同じ報告が 2 回出た原因そのもの。
        if ($NoPromote) {
            $notPromoted.Add("$($r.Label)\$rel  (-NoPromote 指定)")
            continue
        }
        $leaf = Split-Path -Leaf $rel
        if ($RuntimeStateNames -contains $leaf) {
            $notPromoted.Add("$($r.Label)\$rel  (サーバが実行中に書き換える状態ファイル)")
            continue
        }
        $info = Get-SourceInfo -Label $r.Label
        if ($null -eq $info) {
            $notPromoted.Add("$($r.Label)\$rel  (リポジトリ側の対応先が不明)")
            continue
        }
        $repoFile = Join-Path $info.Root $rel
        if (-not (Test-Path -LiteralPath $repoFile)) {
            $notPromoted.Add("$($r.Label)\$rel  (リポジトリに同じパスが無い = プラグインの生成物)")
            continue
        }
        $key = "$($info.Key)|$rel"
        if ($promotedKeys.ContainsKey($key)) {
            $notPromoted.Add("$($r.Label)\$rel  (同じファイルを $($promotedKeys[$key]) から取り込み済み。食い違うなら手で突き合わせること)")
            continue
        }

        # ---- リポジトリ側も動いていないか確かめる ----------------------------------------
        # 配備の最後に Record したとき、配備先の中身は【リポジトリの中身と同一】だった
        # (この配備でコピーした直後だから)。つまり台帳の $before は
        # 「前回配備時点のリポジトリの姿」でもある。これと今のリポジトリを比べれば、
        # リポジトリ側が動いたかどうかが台帳の形式を変えずに判定できる。
        #
        # なぜ要るか: 設定エディタは保存のたび【配備先にもミラー書き込み】する
        # (tool-config.json の deployPaths)。だから「配備先が変わった」は
        # 「サーバ側で直接編集された」とは限らない。エディタ保存の後に
        # エージェントがリポジトリ側をさらに直していると、素朴に取り込むと
        # 【新しいリポジトリの内容を古い配備先の内容で潰す】= 直したかった事故と同じことをする。
        $repoHash = (Get-FileHash -LiteralPath $repoFile -Algorithm SHA256).Hash
        if ($repoHash -eq $current[$rel]) {
            # 中身が既に一致している。エディタのミラー書き込みが typical。取り込む必要が無い。
            continue
        }
        if ($repoHash -ne $before) {
            $notPromoted.Add("$($r.Label)\$rel  (リポジトリ側も前回配備から変わっている。" +
                             "どちらが新しいか判定できないので取り込まない。退避した配備先の現物と突き合わせること)")
            continue
        }
        if ($NoBackup) {
            # --dry-run。書き換えないが、何が取り込まれるかは見せる。
            Write-Host "  [PROMOTE?] $($r.Label)\$rel  ->  $repoFile"
            $promotedKeys[$key] = $r.Label
            continue
        }
        Copy-Item -LiteralPath $src -Destination $repoFile -Force
        $promotedKeys[$key] = $r.Label
        $promoted.Add("$repoFile  <- $($r.Label)\$rel")
        Write-Host "  [PROMOTED] $($r.Label)\$rel  ->  $repoFile"
    }
}

if ($driftCount -eq 0 -and -not $unknown) {
    Write-Host "  [ OK  ] 配備先の yml は前回配備のまま。サーバ側で書き換えられたものは無い。"
} elseif ($driftCount -gt 0) {
    Write-Host ""
    Write-Host "  配備先で直接編集された yml が $driftCount 件ある。"
}
if ($promoted.Count -gt 0) {
    Write-Host ""
    Write-Host "  $($promoted.Count) 件をリポジトリへ取り込んだ。この配備にはこの内容が乗る(=元に戻らない)。"
    foreach ($x in $promoted) { Write-Host "    $x" }
    Write-Host "  ⚠ 取り込んだ分はワーキングツリーの未コミット変更。commit すること (CLAUDE.md)。"
}
if ($notPromoted.Count -gt 0) {
    Write-Host ""
    Write-Host "  取り込まなかったもの ($($notPromoted.Count) 件)。これらは配備先が上書きされる:"
    foreach ($x in $notPromoted) { Write-Host "    $x" }
}
if ($backedUp -gt 0) {
    Write-Host "  退避先: $backupDir  ($backedUp 件)"
} elseif ($NoBackup -and ($driftCount -gt 0 -or $unknown)) {
    Write-Host "  (--dry-run なので退避はしていない)"
}

exit 0
