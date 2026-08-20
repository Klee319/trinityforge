<#
.SYNOPSIS
    run-against-head.ps1 が「必ず元へ戻す」ことを実測する (W-177)。

.DESCRIPTION
    このスクリプトは `git checkout -- <path>` の代わりとして使う前提なので、
    復元が壊れていると【checkout より悪い】(消えたことにすら気付けない)。
    使い捨ての git リポジトリを tmp\ に作り、実際に差し替え -> 実行 -> 復元まで走らせる。

    測っているのは 4 つ:
      1. コマンド実行中はファイルの中身が HEAD になっている
      2. 実行後はワーキングツリーの内容へ必ず戻る
      3. コマンドが失敗しても戻るし、終了コードはそのまま返る
      4. 中身がバイト単位で戻る(BOM が付いたり改行が化けたりしない)
#>
[CmdletBinding()]
param([string] $WorkRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if (-not $WorkRoot) { $WorkRoot = Join-Path $repoRoot "tmp\run-against-head-selftest" }
$script = Join-Path $PSScriptRoot "run-against-head.ps1"

$failures = New-Object System.Collections.Generic.List[string]
function Assert-True {
    param([bool] $Condition, [string] $Message)
    if ($Condition) { Write-Host "  [ OK  ] $Message" }
    else { Write-Host "  [FAIL ] $Message"; $script:failures.Add($Message) }
}

if (Test-Path -LiteralPath $WorkRoot) { Remove-Item -LiteralPath $WorkRoot -Recurse -Force }
$repo = Join-Path $WorkRoot "repo"
New-Item -ItemType Directory -Path $repo -Force | Out-Null

# ---- 使い捨てリポジトリ --------------------------------------------------------------------------
# 改行と日本語をわざと混ぜる。PowerShell の > で書き出すと BOM が付いて壊れる経路の検出用。
$headText = "id: head`r`n# 日本語コメント`r`nvalue: 1`r`n"
$wipText  = "id: WIP-EDIT`r`n# 日本語コメント`r`nvalue: 2`r`n"
$target = Join-Path $repo "conf.yml"
[System.IO.File]::WriteAllText($target, $headText, (New-Object System.Text.UTF8Encoding($false)))

Push-Location $repo
try {
    & git init -q 2>&1 | Out-Null
    & git config user.email "test@example.com"
    & git config user.name "selftest"
    & git add conf.yml
    & git commit -q -m "head" 2>&1 | Out-Null
} finally {
    Pop-Location
}

# ワーキングツリーだけ書き換える(= ユーザーが設定エディタで保存した状態)。
[System.IO.File]::WriteAllText($target, $wipText, (New-Object System.Text.UTF8Encoding($false)))
$wipBytes = [System.IO.File]::ReadAllBytes($target)

# ---- 1) 実行中は HEAD、終わったら WIP -----------------------------------------------------------
Write-Host "--- 1) 成功するコマンド ---"
$probe = Join-Path $WorkRoot "seen.txt"
$cmd = "type `"$target`" > `"$probe`""
& powershell -NoProfile -ExecutionPolicy Bypass -File $script -GitDir $repo -Paths "conf.yml" `
    -StashRoot (Join-Path $WorkRoot "stash") -Command $cmd | Out-Null
$exit1 = $LASTEXITCODE

Assert-True ((Get-Content -LiteralPath $probe -Raw) -match "id: head") `
    "コマンド実行中は HEAD の内容になっている"
Assert-True ((Get-Content -LiteralPath $target -Raw) -match "WIP-EDIT") `
    "実行後はワーキングツリーの内容へ戻る"
Assert-True ($exit1 -eq 0) "成功したコマンドの終了コードは 0"

# ---- 2) バイト単位で戻る -------------------------------------------------------------------------
$after = [System.IO.File]::ReadAllBytes($target)
$same = ($after.Length -eq $wipBytes.Length)
if ($same) {
    for ($i = 0; $i -lt $after.Length; $i++) {
        if ($after[$i] -ne $wipBytes[$i]) { $same = $false; break }
    }
}
Assert-True $same "復元はバイト単位で一致する(BOM が付かない・改行が化けない)"

# ---- 3) 失敗するコマンドでも戻る -----------------------------------------------------------------
Write-Host ""
Write-Host "--- 2) 失敗するコマンド ---"
& powershell -NoProfile -ExecutionPolicy Bypass -File $script -GitDir $repo -Paths "conf.yml" `
    -StashRoot (Join-Path $WorkRoot "stash") -Command "exit 7" | Out-Null
$exit2 = $LASTEXITCODE
Assert-True ((Get-Content -LiteralPath $target -Raw) -match "WIP-EDIT") `
    "コマンドが失敗しても元の内容へ戻る"
Assert-True ($exit2 -eq 7) "コマンドの終了コードをそのまま返す (実際: $exit2)"

Write-Host ""
if ($failures.Count -eq 0) {
    Write-Host "run-against-head: 全項目 OK"
    exit 0
}
Write-Host "run-against-head: $($failures.Count) 件 FAIL"
foreach ($f in $failures) { Write-Host "  - $f" }
exit 1
