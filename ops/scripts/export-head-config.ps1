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
    [string] $StageRoot
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

# ---- 配備されない変更を明示する -------------------------------------------------------------
# 「HEAD を配ります」だけでは、何を配らなかったのかが分からない。
# 未コミットの yml を列挙して、意図的に取り残したことを目に見える形にする。
Write-Host ""
Write-Host "--- 未コミットのため今回は配備されない yml ---"
$dirty = New-Object System.Collections.Generic.List[string]

foreach ($line in (& git -C $RepoRoot status --porcelain -- $tfRel)) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $rel = $line.Substring(3).Trim().Trim('"')
    if ($rel -match '\s->\s') { $rel = ($rel -split '\s->\s')[-1] }
    if ($rel -match '\.yml$') { $dirty.Add("TF   " + $rel) }
}
if (Test-Path (Join-Path $forkDir ".git")) {
    foreach ($line in (& git -C $forkDir status --porcelain -- $arsRel)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $rel = $line.Substring(3).Trim().Trim('"')
        if ($rel -match '\s->\s') { $rel = ($rel -split '\s->\s')[-1] }
        if ($rel -match '\.yml$') { $dirty.Add("ARS  " + $rel) }
    }
}

if ($dirty.Count -eq 0) {
    Write-Host "  (なし。ワーキングツリーの yml は HEAD と一致しています)"
} else {
    foreach ($d in $dirty) { Write-Host ("  " + $d) }
    Write-Host ""
    Write-Host ("  {0} 件。これらは HEAD の内容が配備されます(ワーキングツリー側の編集は届きません)。" -f $dirty.Count)
    Write-Host "  自分の変更がこの一覧にある場合は、先に commit してから配備し直してください。"
}

exit 0
