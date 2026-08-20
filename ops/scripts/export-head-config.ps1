<#
.SYNOPSIS
    コミット済み(HEAD)の設定 yml だけを tmp\deploy-head へ取り出す。

.DESCRIPTION
    このワークツリーは複数セッションで共有している。ワーキングツリーの yml には
    「他セッションが編集途中のもの」が常に混ざっているので、
    resources 配下をそのまま配備する deploy.cmd --config は他人の未完成物を出荷する。

    先に作った deploy-config-skip-wip.cmd は「未コミットのファイルを丸ごと除外」する方式で、
    これは 2 つの意味で外れる:
      1. 自分の変更が入っているファイルに他セッションの未コミット変更が乗っていると、
         そのファイルごと除外されるので【自分の変更も配備されない】。
         2026-08-05 の stats\lore.yml がまさにこれ(職業EXP増加12件の表示定義が落ちる)。
      2. ArsPaper フォークは外側リポジトリから見て .gitignore 除外なので
         git status が何も返さず、【フォークの未コミット変更は検出できない】。
         2026-08-05 時点で materials.yml には他セッションの
         レシピ変更(stone_1x を workbench→ritual 等)が未コミットで乗っていた。

    そこで「未コミットを除外する」のではなく【HEAD の内容そのものを配備する】。
    出荷されるのは常に「コミット済みの状態」= 誰の編集途中も混ざらない、と定義できる。

    ── 2026-08-18 方針変更(W-106): 既定を「HEAD + ワーキングツリーの yml」に変えた ────────
    上の定義には実運用で致命的な副作用があった。設定エディタ(tools\config-editor)の保存先は
    リポジトリの src\main\resources なので、【エディタで直しただけの変更は未コミット】であり、
    次の config 配備で HEAD の内容に無言で上書きされる。ユーザーからは
    「設定が勝手にロールバックした」ようにしか見えない(2026-08-18 W-101 の実例:
    ヴォルカニックソースリンクの解放ゲートを外したのに、配備のたびに復活していた)。

    そこで既定を【HEAD を土台にして、ワーキングツリーの yml を上から重ねる】に変えた。
    エディタでの編集が最優先で残る = ロールバックしない。

    元の懸念(他セッションの編集途中が混ざる)は、次の2点で担保する:
      1. 重ねたファイルを【必ず一覧で表示する】。何が HEAD ではなく現物で出荷されたかが見える。
      2. エージェントが config を直接編集したら【その場で commit する】運用にする。
         こうするとワーキングツリーに残る未コミット yml は
         「人がエディタで編集したもの」だけになり、競合そのものが起きない。

    従来どおり「コミット済みだけ」を配りたいときは -HeadOnly を付ける。

    取り出しは git archive の tar 経由で行う。git show 経由だと PowerShell の文字列化で
    UTF-8 の日本語コメントと改行が壊れるため、バイト列のまま展開する必要がある。

    ArsPaper フォークは自前の git リポジトリ(fork-handoff\arspaper\fork)なので、
    そちらの HEAD から別途取り出す。フォーク不在の環境では単に skip する。

.PARAMETER RepoRoot
    リポジトリのルート。省略時はこのスクリプトの位置から解決する。

.PARAMETER StageRoot
    取り出し先。省略時は <RepoRoot>\tmp\deploy-head。毎回作り直す。

.OUTPUTS
    人が読む進捗と、末尾に "HEAD と差があるファイル" の一覧を出す。
    後者が【今回は配備されないワーキングツリー側の変更】なので、必ず目を通すこと。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\export-head-config.ps1
#>
param(
    [string] $RepoRoot,
    [string] $StageRoot,
    # 付けると従来どおり「コミット済みだけ」を出荷する(ワーキングツリーを重ねない)。
    # 既定はワーキングツリー優先 = エディタでの編集をロールバックさせない(W-106)。
    [switch] $HeadOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $RepoRoot) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}
if (-not $StageRoot) {
    $StageRoot = Join-Path $RepoRoot "tmp\deploy-head"
}

$tfRel   = "TrinityForge/src/main/resources"
$forkDir = Join-Path $RepoRoot "fork-handoff\arspaper\fork"
$arsRel  = "src/main/resources"

# tar.exe は Windows 10 1803 以降に同梱。無い環境では黙って壊れるので先に落とす。
$tar = Join-Path $env:SystemRoot "System32\tar.exe"
if (-not (Test-Path $tar)) {
    throw "tar.exe が見つかりません: $tar (git archive の展開に必要)"
}

# ---- 取り出し先を作り直す -------------------------------------------------------------------
# 前回の残骸が混ざると「消したはずの yml が配備される」ので、毎回消してから作る。
if (Test-Path $StageRoot) {
    Remove-Item -LiteralPath $StageRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $StageRoot -Force | Out-Null

function Export-HeadPath {
    param(
        [Parameter(Mandatory = $true)][string] $GitDir,   # git -C に渡すディレクトリ
        [Parameter(Mandatory = $true)][string] $PathSpec, # HEAD から取り出すパス(スラッシュ区切り)
        [Parameter(Mandatory = $true)][string] $Dest,     # 展開先
        [Parameter(Mandatory = $true)][string] $Label
    )

    $tarPath = Join-Path $StageRoot ("_" + $Label + ".tar")
    & git -C $GitDir archive --format=tar -o $tarPath HEAD -- $PathSpec
    if ($LASTEXITCODE -ne 0) {
        throw "$Label : git archive が失敗しました (exit=$LASTEXITCODE)"
    }

    New-Item -ItemType Directory -Path $Dest -Force | Out-Null
    & $tar -xf $tarPath -C $Dest
    if ($LASTEXITCODE -ne 0) {
        throw "$Label : tar 展開が失敗しました (exit=$LASTEXITCODE)"
    }
    Remove-Item -LiteralPath $tarPath -Force

    $ymls = @(Get-ChildItem -LiteralPath $Dest -Recurse -Filter *.yml -File)
    Write-Host ("  [ OK  ] {0}: {1} 件の yml を HEAD から取り出しました" -f $Label, $ymls.Count)
    if ($ymls.Count -eq 0) {
        throw "$Label : yml が 0 件です(パス指定 '$PathSpec' が HEAD に無い可能性)"
    }
    return $ymls.Count
}

Write-Host "--- HEAD から設定 yml を取り出す ---"
Write-Host ("  stage : {0}" -f $StageRoot)

$tfDest = Join-Path $StageRoot "tf"
Export-HeadPath -GitDir $RepoRoot -PathSpec $tfRel -Dest $tfDest -Label "TrinityForge" | Out-Null

$arsDest = Join-Path $StageRoot "ars"
if (Test-Path (Join-Path $forkDir ".git")) {
    Export-HeadPath -GitDir $forkDir -PathSpec $arsRel -Dest $arsDest -Label "ArsPaper" | Out-Null
} else {
    Write-Host "  [SKIP ] ArsPaper: フォークのソースがここには無い(.gitignore 除外)"
}

# ---- ワーキングツリーと HEAD の差分を拾う ----------------------------------------------------
# git status --porcelain は変更(M)も未追跡(??)も返す。エディタが新規 yml を作ることもあるので
# 未追跡も対象に含める。削除(D)だけは重ねようがないので別扱いにする。
function Get-DirtyYml {
    param(
        [Parameter(Mandatory = $true)][string] $GitDir,
        [Parameter(Mandatory = $true)][string] $PathSpec,
        [Parameter(Mandatory = $true)][string] $Label
    )

    $result = New-Object System.Collections.Generic.List[psobject]
    foreach ($line in (& git -C $GitDir status --porcelain -- $PathSpec)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $rel = $line.Substring(3).Trim().Trim('"')
        # リネームは "old -> new"。重ねたいのは新しい方。
        if ($rel -match '\s->\s') { $rel = ($rel -split '\s->\s')[-1] }
        if ($rel -notmatch '\.yml$') { continue }
        $result.Add([pscustomobject]@{
            Label   = $Label
            Rel     = $rel
            GitDir  = $GitDir
            Deleted = -not (Test-Path -LiteralPath (Join-Path $GitDir $rel))
        })
    }
    return $result
}

$dirty = New-Object System.Collections.Generic.List[psobject]
foreach ($d in (Get-DirtyYml -GitDir $RepoRoot -PathSpec $tfRel -Label "TF ")) { $dirty.Add($d) }
if (Test-Path (Join-Path $forkDir ".git")) {
    foreach ($d in (Get-DirtyYml -GitDir $forkDir -PathSpec $arsRel -Label "ARS")) { $dirty.Add($d) }
}

# TF は git archive のパススペックがリポジトリ相対なので、展開先も同じ相対パスで揃う。
# フォークも同様(フォーク自身のリポジトリ相対)。したがって重ねる先は <展開ルート>\<相対パス>。
$stageRootOf = @{}
$stageRootOf[$RepoRoot] = $tfDest
$stageRootOf[$forkDir]  = $arsDest

Write-Host ""
if ($HeadOnly) {
    Write-Host "--- -HeadOnly: 未コミットのため今回は配備されない yml ---"
    if ($dirty.Count -eq 0) {
        Write-Host "  (なし。ワーキングツリーの yml は HEAD と一致しています)"
    } else {
        foreach ($d in $dirty) { Write-Host ("  {0}  {1}" -f $d.Label, $d.Rel) }
        Write-Host ""
        Write-Host ("  {0} 件。これらは HEAD の内容が配備されます(ワーキングツリー側の編集は届きません)。" -f $dirty.Count)
        Write-Host "  エディタでの編集をそのまま配備したい場合は -HeadOnly を外してください。"
    }
    exit 0
}

# ---- ワーキングツリーを HEAD の上に重ねる(既定) ----------------------------------------------
# ここで重ねた分が「HEAD ではなく現物が出荷されたファイル」。ロールバック事故の逆で、
# 今度は【他人の編集途中を出荷してしまう】のが唯一のリスクなので、必ず全件を表示する。
Write-Host "--- ワーキングツリーの yml を HEAD の上に重ねる(エディタでの編集を優先) ---"
if ($dirty.Count -eq 0) {
    Write-Host "  (重ねるものなし。ワーキングツリーの yml は HEAD と一致しています)"
    exit 0
}

$overlaid = 0
$skipped  = New-Object System.Collections.Generic.List[string]
foreach ($d in $dirty) {
    if ($d.Deleted) {
        # ワーキングツリーで消えている = 重ねる中身が無い。HEAD の内容をそのまま配る。
        # (配備先のファイルを消す動作はこのスクリプトには無い。消したいなら commit して別途対応)
        $skipped.Add(("{0}  {1}  (ワーキングツリーで削除済み。HEAD の内容を配備)" -f $d.Label, $d.Rel))
        continue
    }
    $destRoot = $stageRootOf[$d.GitDir]
    if (-not $destRoot) {
        $skipped.Add(("{0}  {1}  (展開先が不明)" -f $d.Label, $d.Rel))
        continue
    }
    $src  = Join-Path $d.GitDir $d.Rel
    $dest = Join-Path $destRoot $d.Rel
    $destDir = Split-Path -Parent $dest
    if (-not (Test-Path -LiteralPath $destDir)) {
        New-Item -ItemType Directory -Path $destDir -Force | Out-Null
    }
    Copy-Item -LiteralPath $src -Destination $dest -Force
    Write-Host ("  [重ねた] {0}  {1}" -f $d.Label, $d.Rel)
    $overlaid++
}

foreach ($s in $skipped) { Write-Host ("  [スキップ] " + $s) }

Write-Host ""
Write-Host ("  {0} 件をワーキングツリーの内容で配備します。" -f $overlaid)
Write-Host "  ⚠ この一覧に【身に覚えのないファイル】があれば、他セッションの編集途中が混ざっています。"
Write-Host "     その場合は配備を中止するか、-HeadOnly でコミット済みだけを配備してください。"

exit 0
