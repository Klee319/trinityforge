<#
.SYNOPSIS
    deploy-full.cmd の第 1 段。配る物と配備先の現状を並べ、実行してよいかを確かめる。

.DESCRIPTION
    何も書き換えない。GeyserExtra（Paper 側 + Geyser 拡張）と TrinityForge の
    「成果物の更新時刻」と「配備先の更新時刻」を突き合わせて表示し、
    続行するかどうかを終了コードで deploy-full.cmd へ返す。

    ⚠️ **GeyserExtra は TrinityForge より先に配る。**
    TF が書き出す `bedrock-recipes.json` は形式バージョン 2 で、旧 GeyserExtra の
    collector は 1 しか受け付けず**表を丸ごと reject する**（鍛冶台だけでなく
    作業台の補正も全部消える）。逆順（GeyserExtra だけ新しい）は 1 も 2 も受理する。
    この順序は deploy-full.cmd 側が固定しており、ここでは表示順にも反映してある。

    ⚠️ **「配備先が新しい」は正常状態。** 配備すると配備先の時刻は「今」になるので、
    次に開くと必ず配備先のほうが新しい。古いかどうかの判定は
    「配備先 < 成果物」で行う。バイト数が同じで時刻だけ違うものは同一物とみなさない
    （ビルドし直すと時刻だけ動くため）。

.PARAMETER Yes
    確認を飛ばす。無人実行用。

.PARAMETER DryRun
    計画を出して必ず終了する（続行しない）。

.PARAMETER RepoRoot
    geyserExtra リポジトリのルート。省略時は TrinityForge リポジトリの
    親ディレクトリの `geyserExtraα`。

.OUTPUTS
    終了コード 0 = 続行してよい / 2 = 続行しない（DryRun または利用者が拒否）/ 1 = エラー。
    deploy-full.cmd は `if errorlevel 2` を先に見るので、この 3 値の順序は変えないこと。
#>
[CmdletBinding()]
param(
    [switch] $Yes,
    [switch] $DryRun,
    [string] $RepoRoot
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Common.ps1")

# RCON パスワードはこの段では使わない（停止は stop-all.cmd の仕事）。
$config = Get-OpsConfig -RequireRconPasswords:$false

$tfRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $RepoRoot) {
    # Windows PowerShell 5.1 に `u{...} エスケープが無いので [char] で組む。
    $RepoRoot = Join-Path (Split-Path $tfRoot -Parent) ("geyserExtra" + [char]0x03B1)
}
if (-not (Test-Path -LiteralPath $RepoRoot)) {
    Write-Host "[ERROR] geyserExtra リポジトリが見つかりません: $RepoRoot" -ForegroundColor Red
    exit 1
}

# --------------------------------------------------------------------------
# 配る物 → 配備先の対応
# --------------------------------------------------------------------------
$plan = @()

$paperJar = Join-Path $RepoRoot "paper\build\libs\geyserExtra-1.0.0-SNAPSHOT.jar"
$extensionJar = Join-Path $RepoRoot "extension\build\libs\extension-1.0.0-SNAPSHOT.jar"
$tfJar = Join-Path $tfRoot "TrinityForge\build\release\TrinityForge-all.jar"

foreach ($name in @($config.Servers.Keys | Sort-Object)) {
    $plugins = Join-Path $config.Servers[$name].Root "plugins"
    if (-not (Test-Path -LiteralPath $plugins)) { continue }
    $plan += [pscustomobject]@{
        Label  = "GeyserExtra / $name"
        Source = $paperJar
        # 配備先のファイル名は「今入っている名前」に合わせる。成果物名で置くと
        # 同一プラグインの jar が 2 つ並び、Paper が Ambiguous plugin name で起動不能になる。
        Target = Join-Path $plugins "geyserExtra.jar"
    }
}
$plan += [pscustomobject]@{
    Label  = "GeyserExtra / proxy"
    Source = $extensionJar
    Target = Join-Path $config.VelocityRoot "plugins\Geyser-Velocity\extensions\geyser_extra.jar"
}
foreach ($name in @($config.Servers.Keys | Sort-Object)) {
    $plugins = Join-Path $config.Servers[$name].Root "plugins"
    if (-not (Test-Path -LiteralPath $plugins)) { continue }
    # 実機の名前は TrinityForge-0.1.0-SNAPSHOT-all.jar。ワイルドカードで拾って
    # 「今入っている名前」をそのまま使う（deploy.cmd と同じ方針）。
    $existing = Get-ChildItem -LiteralPath $plugins -Filter "TrinityForge*-all.jar" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $plan += [pscustomobject]@{
        Label  = "TrinityForge / $name"
        Source = $tfJar
        Target = if ($existing) { $existing.FullName } else { Join-Path $plugins "TrinityForge-all.jar" }
    }
}

# --------------------------------------------------------------------------
# 表示
# --------------------------------------------------------------------------
Write-Host ""
Write-Host "============================================================"
Write-Host " 配備計画  (GeyserExtra -> TrinityForge の順に配る)"
Write-Host "============================================================"

$missing = @()
$stale = 0
# 組み立てた順そのままで出す。Windows PowerShell 5.1 の Sort-Object は安定ソートではないので、
# 並べ替えを挟むと「GeyserExtra を先に配る」という肝心の順序が表示上だけ崩れる。
foreach ($step in $plan) {
    $srcText = "(成果物なし)"
    $src = $null
    if (Test-Path -LiteralPath $step.Source) {
        $src = Get-Item -LiteralPath $step.Source
        $srcText = "{0,11:N0} bytes  {1:yyyy-MM-dd HH:mm:ss}" -f $src.Length, $src.LastWriteTime
    } else {
        $missing += $step.Source
    }

    $dstText = "(未配備)"
    $mark = "NEW "
    if (Test-Path -LiteralPath $step.Target) {
        $dst = Get-Item -LiteralPath $step.Target
        $dstText = "{0,11:N0} bytes  {1:yyyy-MM-dd HH:mm:ss}" -f $dst.Length, $dst.LastWriteTime
        if ($src -and $dst.Length -eq $src.Length -and $dst.LastWriteTime -ge $src.LastWriteTime) {
            $mark = "同一"
        } else {
            $mark = "更新"
        }
    }
    if ($mark -ne "同一") { $stale++ }

    Write-Host ("  [{0}] {1}" -f $mark, $step.Label)
    Write-Host ("         成果物: {0}" -f $srcText)
    Write-Host ("         配備先: {0}" -f $dstText)
}

if ($missing.Count -gt 0) {
    # ここで止めない。ビルドは deploy-full.cmd の次の段でこの後に走るので、
    # 「まだ無い」は正常な初回状態でもある。存在しないまま配る段まで来たら
    # そちらが失敗するので、無言で進めるより名指ししておく。
    Write-Host ""
    Write-Host "[NOTE] 現時点で未ビルドの成果物があります（この後のビルド段で作られます）:" -ForegroundColor Yellow
    foreach ($m in ($missing | Sort-Object -Unique)) { Write-Host "         $m" }
}

Write-Host ""
Write-Host "この後の流れ:"
Write-Host "  1. ビルド        GeyserExtra と TrinityForge  ← サーバは動いたまま"
Write-Host "  2. 停止          stop-all.cmd"
Write-Host "  3. 配備          GeyserExtra -> TrinityForge"
Write-Host "  4. 起動          start-all.cmd"
Write-Host ""
Write-Host "  config(yml) は配りません。必要なら別途 deploy-config-head.cmd を使ってください。"

if ($stale -eq 0) {
    Write-Host ""
    Write-Host "配備先はすべて成果物と同一です。再配備しても内容は変わりません。" -ForegroundColor Yellow
}

if ($DryRun) {
    Write-Host ""
    Write-Host "--dry-run のためここで終了します。何も書いていません。" -ForegroundColor Green
    exit 2
}

if ($Yes) {
    exit 0
}

Write-Host ""
Write-Host "サーバを停止して配備します。よろしければ DEPLOY と入力してください。" -ForegroundColor Cyan
$answer = Read-Host "  入力"
if ($answer -ne "DEPLOY") {
    Write-Host "中止しました。何も書いていません。" -ForegroundColor Yellow
    exit 2
}
exit 0
