<#
.SYNOPSIS
    guard-deployed-config.ps1 の「配備先の編集をリポジトリへ取り込む」挙動を実測する (W-177)。

.DESCRIPTION
    2026-08-19 (W-110) と 2026-08-20 (W-177) の再発防止の中身は
    「配備先で直接編集された yml を、上書きされる前にリポジトリへ書き戻す」こと。
    ここが no-op に戻ると【設定が勝手にロールバックする】報告がまた出るが、
    実サーバを止めて配備するまで誰も気付けない。だから偽のリポジトリと偽の配備先を
    tmp\ に作って、実際に書き戻しが起きるところまで測る。

    測っているのは 5 つ:
      1. 前回配備の後に書き換えられた yml が、リポジトリ側へ取り込まれる
      2. 触っていない yml は取り込まれない(差分ゼロなら何もしない)
      3. リポジトリに存在しないファイル(プラグインの生成物)は取り込まれない
      4. サーバが実行中に書き換える状態ファイルは取り込まれない
      5. -NoPromote を付けると取り込まない(退避と報告だけ)

.NOTES
    サーバにも本物のリポジトリにも触らない。作業ディレクトリは毎回作り直す。
#>
[CmdletBinding()]
param(
    [string] $WorkRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if (-not $WorkRoot) {
    $WorkRoot = Join-Path $repoRoot "tmp\guard-selftest"
}
$guard = Join-Path $PSScriptRoot "guard-deployed-config.ps1"

$failures = New-Object System.Collections.Generic.List[string]
function Assert-True {
    param([bool] $Condition, [string] $Message)
    if ($Condition) {
        Write-Host "  [ OK  ] $Message"
    } else {
        Write-Host "  [FAIL ] $Message"
        $script:failures.Add($Message)
    }
}

# ---- 偽のリポジトリと偽の配備先を作る ----------------------------------------------------------
if (Test-Path -LiteralPath $WorkRoot) {
    Remove-Item -LiteralPath $WorkRoot -Recurse -Force
}
$fakeRepo   = Join-Path $WorkRoot "repo"
$fakeVeloc  = Join-Path $WorkRoot "velocity"
$tfSource   = Join-Path $fakeRepo "TrinityForge\src\main\resources"
$arsSource  = Join-Path $fakeRepo "fork-handoff\arspaper\fork\src\main\resources"
$tfDeployed = Join-Path $fakeVeloc "Main_Server\plugins\TrinityForge"
$arsDeployed= Join-Path $fakeVeloc "Main_Server\plugins\ArsPaper"
foreach ($d in @($tfSource, $arsSource, $tfDeployed, $arsDeployed)) {
    New-Item -ItemType Directory -Path $d -Force | Out-Null
}
$manifest  = Join-Path $WorkRoot "manifest.json"
$backupDir = Join-Path $WorkRoot "backup"

function Write-Yml {
    param([string] $Path, [string] $Text)
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

# 配備済みの状態を作る(リポジトリと配備先が一致している = 配備直後)。
Write-Yml (Join-Path $tfSource   "network.yml")     "format: old`n"
Write-Yml (Join-Path $tfDeployed "network.yml")     "format: old`n"
Write-Yml (Join-Path $tfSource   "stats\lore.yml")  "lore: same`n"
Write-Yml (Join-Path $tfDeployed "stats\lore.yml")  "lore: same`n"
Write-Yml (Join-Path $arsSource   "materials.yml")  "materials: {}`n"
Write-Yml (Join-Path $arsDeployed "materials.yml")  "materials: {}`n"
# リポジトリに無い = プラグインの生成物。
Write-Yml (Join-Path $arsDeployed "generated-cache.yml") "cache: 1`n"
# サーバが実行中に書き換える状態ファイル。リポジトリ側にも同名を置いて、
# 「実在するのに取り込まれない」ことを測れるようにする。
Write-Yml (Join-Path $arsSource   "world_settings.yml") "worlds: {}`n"
Write-Yml (Join-Path $arsDeployed "world_settings.yml") "worlds: {}`n"

$guardArgs = @(
    "-VelocityRoot", $fakeVeloc,
    "-ConfigHost", "Main_Server",
    "-Backends", "Main_Server",
    "-RepoRoot", $fakeRepo,
    "-ManifestPath", $manifest,
    "-BackupRoot", $backupDir
)

Write-Host "--- 1) 配備直後の台帳を撮る ---"
& powershell -NoProfile -ExecutionPolicy Bypass -File $guard -Mode Record @guardArgs | Out-Null
Assert-True (Test-Path -LiteralPath $manifest) "台帳が作られる"

# ---- 人がサーバ側の yml を直接書き換える -------------------------------------------------------
Write-Yml (Join-Path $tfDeployed  "network.yml")         "format: NEW-FROM-SERVER`n"
Write-Yml (Join-Path $arsDeployed "generated-cache.yml") "cache: 2`n"
Write-Yml (Join-Path $arsDeployed "world_settings.yml")  "worlds: {runtime: 1}`n"

Write-Host ""
Write-Host "--- 2) -NoPromote では取り込まない ---"
& powershell -NoProfile -ExecutionPolicy Bypass -File $guard -Mode Check @guardArgs -NoPromote | Out-Null
Assert-True ((Get-Content -LiteralPath (Join-Path $tfSource "network.yml") -Raw) -notmatch "NEW-FROM-SERVER") `
    "-NoPromote: リポジトリは書き換わらない"

Write-Host ""
Write-Host "--- 3) 既定では取り込む ---"
& powershell -NoProfile -ExecutionPolicy Bypass -File $guard -Mode Check @guardArgs | Out-Null

Assert-True ((Get-Content -LiteralPath (Join-Path $tfSource "network.yml") -Raw) -match "NEW-FROM-SERVER") `
    "サーバ側で直した network.yml がリポジトリへ取り込まれる"
Assert-True ((Get-Content -LiteralPath (Join-Path $tfSource "stats\lore.yml") -Raw) -match "lore: same") `
    "触っていない yml は取り込まれない"
Assert-True (-not (Test-Path -LiteralPath (Join-Path $arsSource "generated-cache.yml"))) `
    "リポジトリに無いファイル(生成物)は取り込まれない"
Assert-True ((Get-Content -LiteralPath (Join-Path $arsSource "world_settings.yml") -Raw) -notmatch "runtime") `
    "実行時の状態ファイルは取り込まれない"
Assert-True ((Get-ChildItem -LiteralPath $backupDir -Recurse -Filter "network.yml" -File).Count -ge 1) `
    "上書き前の配備先ファイルが退避されている"

Write-Host ""
if ($failures.Count -eq 0) {
    Write-Host "guard-deployed-config: 全項目 OK"
    exit 0
}
Write-Host "guard-deployed-config: $($failures.Count) 件 FAIL"
foreach ($f in $failures) { Write-Host "  - $f" }
exit 1
