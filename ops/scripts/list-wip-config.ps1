<#
.SYNOPSIS
    他セッションが未コミットで抱えている設定 yml のファイル名を列挙する。

.DESCRIPTION
    このワークツリーは複数セッションで共有している。`deploy.cmd --config` は
    resources 配下の *.yml を丸ごと配備するので、誰かが編集途中の yml もそのまま出荷される。
    2026-08-02 に progression\collection.yml と stats\skill-exp.yml で実際に起きた
    (後者は ARCHERY kill-exp 25 -> 30 という実バランス変更を巻き込んだ)。

    deploy-config-skip-wip.cmd はその対策だが、除外リストを【スクリプト内に手書き】していた。
    2026-08-05 時点で実際の未コミットは 14 件あり、手書きの 2 件とは合っていなかった。
    許可/除外リストを人間が書き写す方式は、リスト自体が腐った瞬間に検査ごと無効化される。
    そこで【リストを git から毎回引き直す】ことにした。これがこのスクリプト。

    出力は robocopy /XF にそのまま渡せるファイル名(basename)。
    basename で除外するので、同名ファイルが別ディレクトリにあると巻き添えで除外される。
    「配備しそこねる」側に倒れる誤りなので、その向きの安全側として許容する。

.PARAMETER RepoRoot
    リポジトリのルート。省略時はこのスクリプトの位置から解決する。

.PARAMETER Relative
    basename ではなくリポジトリ相対パスで出す(人が読む用)。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\list-wip-config.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\list-wip-config.ps1 -Relative
#>
param(
    [string] $RepoRoot,
    [switch] $Relative
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $RepoRoot) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}

# 監視対象。TF 本体と ArsPaper フォークの出荷 yml。
# フォークのソースは .gitignore 除外なので、存在しない環境では git が何も返さない(それでよい)。
$watched = @(
    "TrinityForge/src/main/resources",
    "fork-handoff/arspaper/fork/src/main/resources"
)

$names = New-Object System.Collections.Generic.List[string]

foreach ($path in $watched) {
    $full = Join-Path $RepoRoot ($path -replace '/', '\')
    if (-not (Test-Path $full)) { continue }

    # --porcelain の各行は "XY <path>"。R(rename) は "XY <old> -> <new>" になるので新しい方を採る。
    $lines = & git -C $RepoRoot status --porcelain -- $path 2>$null
    if (-not $lines) { continue }

    foreach ($line in $lines) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $rel = $line.Substring(3).Trim()
        if ($rel -match '\s->\s') { $rel = ($rel -split '\s->\s')[-1] }
        $rel = $rel.Trim('"')
        if ($rel -notmatch '\.yml$') { continue }
        if ($Relative) {
            $names.Add($rel)
        } else {
            $names.Add([System.IO.Path]::GetFileName($rel))
        }
    }
}

# robocopy へ渡すので重複は潰す。順序は安定させておく(差分を読むときに楽)。
$names | Sort-Object -Unique
