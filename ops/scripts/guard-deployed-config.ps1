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
    [switch] $NoBackup
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

foreach ($r in $roots) {
    if (-not (Test-Path -LiteralPath $r.Path)) {
        continue
    }
    $current = Read-DeployedHashes $r.Path
    $recorded = Get-ManifestRoot $manifest $r.Label

    if ($null -eq $recorded) {
        # 台帳が無い = 「前回の姿」を知らない。判定できないので全部を退避側に倒す。
        $unknown = $true
        Write-Host "  [UNKN ] $($r.Label): 前回配備の台帳が無いので、サーバ側編集の有無を判定できない"
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
        if (-not $NoBackup) {
            $src = Join-Path $r.Path $rel
            $dst = Join-Path (Join-Path $backupDir $r.Label) $rel
            $dstDir = Split-Path -Parent $dst
            if (-not (Test-Path -LiteralPath $dstDir)) {
                New-Item -ItemType Directory -Path $dstDir -Force | Out-Null
            }
            Copy-Item -LiteralPath $src -Destination $dst -Force
            $backedUp++
        }
    }
}

if ($driftCount -eq 0 -and -not $unknown) {
    Write-Host "  [ OK  ] 配備先の yml は前回配備のまま。サーバ側で書き換えられたものは無い。"
} elseif ($driftCount -gt 0) {
    Write-Host ""
    Write-Host "  配備先で直接編集された yml が $driftCount 件ある。これから HEAD の内容で上書きされる。"
    Write-Host "  編集を残したいなら、リポジトリ側の src\main\resources\<同じパス> に反映してから配り直すこと。"
}
if ($backedUp -gt 0) {
    Write-Host "  退避先: $backupDir  ($backedUp 件)"
} elseif ($NoBackup -and ($driftCount -gt 0 -or $unknown)) {
    Write-Host "  (--dry-run なので退避はしていない)"
}

exit 0
