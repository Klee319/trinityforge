<#
.SYNOPSIS
    指定ファイルだけを一時的に HEAD の内容へ差し替えてコマンドを実行し、必ず元へ戻す。

.DESCRIPTION
    2026-08-20 (W-177) に追加。

    ── なぜ要るのか ────────────────────────────────────────────────────────────
    「テストの失敗が自分の変更のせいか確かめたい」ときに、エージェントは
    `git checkout -- <path>` でファイルを HEAD へ戻していた。これはワーキングツリーの
    未コミット変更を無条件で捨てる操作で、この worktree では次の2つを巻き込む:

      1. 他セッションの作業中の変更
      2. 【ユーザーが設定エディタで保存した yml】
         config 配備は 2026-08-18 (W-106) から「HEAD にワーキングツリーの yml を重ねる」
         方式なので、未コミットの yml が残っているのは異常ではなく【正規の運用】。

    2026-08-19 14:51 に実際に起きた: あるセッションが catalog.yml を HEAD へ戻して
    テストを走らせ、その 33 秒前にユーザーが設定エディタで保存した「触媒(杖3種)の id と
    レシピ」を消したまま、自分の変更だけを commit した。ユーザーからは
    「設定が勝手にロールバックした」としか見えない。

    そこで .claude\hooks\pre-guard.py が checkout/restore/reset --hard/clean/stash を
    deny するようにし、【代わりの正規手段】としてこのスクリプトを置く。
    退避 -> HEAD を置く -> コマンド実行 -> 戻す、を必ずセットで行う。
    コマンドが落ちても Ctrl+C で止めても finally で戻す。

    ── 使い方 ──────────────────────────────────────────────────────────────────
    パスはリポジトリ(または -GitDir)からの相対。複数指定できる。

.PARAMETER Paths
    HEAD の内容に差し替えるファイル(リポジトリ相対)。

.PARAMETER Command
    差し替えている間に実行するコマンド。cmd.exe 経由で走る。省略すると
    「差し替えて、すぐ戻す」だけになる(このスクリプト自体の動作確認用)。

.PARAMETER GitDir
    git リポジトリのルート。省略時はこのスクリプトから解決したリポジトリルート。
    ArsPaper フォークを対象にするときは fork-handoff\arspaper\fork を渡す。

.PARAMETER StashRoot
    退避先の親。省略時は <RepoRoot>\tmp\wip-restore。実行後も消さない
    (戻し損ねたときの最後の砦なので、自動削除しない)。

.OUTPUTS
    実行したコマンドの終了コードをそのまま返す。差し替えや復元に失敗したときは 2。

.EXAMPLE
    .\run-against-head.ps1 -Paths TrinityForge\src\main\resources\items\catalog.yml `
        -Command "cd TrinityForge && gradlew.bat test --offline"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string[]] $Paths,

    [string] $Command,
    [string] $GitDir,
    [string] $StashRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if (-not $GitDir) { $GitDir = $repoRoot }
if (-not $StashRoot) { $StashRoot = Join-Path $repoRoot "tmp\wip-restore" }

$stamp = (Get-Date).ToString("yyyyMMdd-HHmmss")
$stashDir = Join-Path $StashRoot $stamp

function Write-HeadBlobTo {
    <#
    .SYNOPSIS
        HEAD:<rel> の中身を【バイト列のまま】ファイルへ書き出す。
    .DESCRIPTION
        `git show HEAD:x > y` を PowerShell の > で書くと BOM が付き、改行も CRLF に化ける。
        yml の途中に来た BOM は SnakeYAML が文書境界と誤読するので、
        「戻したはずなのに読めない」という別の事故になる。標準出力を生のまま流す。
    #>
    param([string] $Rel, [string] $Dest)

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "git"
    $psi.Arguments = "-C `"$GitDir`" show `"HEAD:$($Rel -replace '\\', '/')`""
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $proc = [System.Diagnostics.Process]::Start($psi)
    $fs = [System.IO.File]::Create($Dest)
    try {
        $proc.StandardOutput.BaseStream.CopyTo($fs)
    } finally {
        $fs.Dispose()
    }
    $err = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()
    if ($proc.ExitCode -ne 0) {
        throw "git show HEAD:$Rel が失敗しました: $err"
    }
}

# ---- 1. 対象を確かめる --------------------------------------------------------------------------
$targets = New-Object System.Collections.Generic.List[psobject]
foreach ($rel in $Paths) {
    $clean = $rel.Trim().Trim('"')
    $abs = Join-Path $GitDir $clean
    if (-not (Test-Path -LiteralPath $abs -PathType Leaf)) {
        Write-Host "  [ERROR] ワーキングツリーに存在しません: $clean"
        exit 2
    }
    $targets.Add([pscustomobject]@{ Rel = $clean; Abs = (Resolve-Path -LiteralPath $abs).Path })
}

Write-Host "--- HEAD の内容で実行する ---"
Write-Host ("  repo  : {0}" -f $GitDir)
Write-Host ("  退避先: {0}" -f $stashDir)
New-Item -ItemType Directory -Path $stashDir -Force | Out-Null

# ---- 2. 退避してから差し替える -------------------------------------------------------------------
# 「退避できたものだけ」を戻し対象にする。途中で失敗しても、差し替えたものは必ず戻る。
$swapped = New-Object System.Collections.Generic.List[psobject]
$exitCode = 2
try {
    foreach ($t in $targets) {
        $saved = Join-Path $stashDir ($t.Rel -replace '[\\/]', '__')
        Copy-Item -LiteralPath $t.Abs -Destination $saved -Force
        $swapped.Add([pscustomobject]@{ Abs = $t.Abs; Saved = $saved; Rel = $t.Rel })
        Write-HeadBlobTo -Rel $t.Rel -Dest $t.Abs
        Write-Host ("  [差替え] {0}" -f $t.Rel)
    }

    if (-not $Command) {
        Write-Host "  (-Command 未指定。差し替えだけ行いました)"
        $exitCode = 0
    } else {
        Write-Host ""
        Write-Host ("--- 実行: {0}" -f $Command)
        & cmd.exe /c $Command
        $exitCode = $LASTEXITCODE
        Write-Host ("--- 終了コード: {0}" -f $exitCode)
    }
} finally {
    # ---- 3. 何があっても戻す -------------------------------------------------------------------
    Write-Host ""
    Write-Host "--- 元の内容へ戻す ---"
    $restoreFailed = $false
    foreach ($s in $swapped) {
        try {
            Copy-Item -LiteralPath $s.Saved -Destination $s.Abs -Force
            Write-Host ("  [戻した] {0}" -f $s.Rel)
        } catch {
            $restoreFailed = $true
            Write-Host ("  [ERROR] 戻せませんでした: {0}" -f $s.Rel)
            Write-Host ("          退避してある現物: {0}" -f $s.Saved)
        }
    }
    if ($restoreFailed) {
        Write-Host "  ⚠ 戻せなかったファイルがある。上の退避パスから手でコピーすること。"
        $exitCode = 2
    } else {
        Write-Host ("  退避は消していない: {0}" -f $stashDir)
    }
}

exit $exitCode
