<#
.SYNOPSIS
  並列作業の「見えない地雷」を洗い出す。波を始める前と、レート制限などで中断したあとに必ず走らせる。

.DESCRIPTION
  2026-08-01 に実際に踏んだ3つの事故を機械で検出するために作った。どれも
  「気づくのが遅れると作業ごと捨てることになる」種類のもので、人間の記憶では防げなかった。

    (1) 持ち主のいない未コミット変更
        レート制限で落ちたエージェントが作業ツリーに変更を残すと、誰も引き取らないまま
        出荷 yml のドリフト検知テストが延々と落ち続け、--config 配備も封じられる。
        実際に 17 本の yml が「他セッションの WIP」だと誤認されたまま半日放置された。

    (2) 未マージブランチ同士の担当ファイル衝突
        別々の波が同じ領域を実装してしまうと、後からマージする側が全部書き直しになる。
        実際に「ステ語彙の整理」を2回実装し、片方を捨てた。

    (3) 分岐点が古いブランチ / worktree
        ワークツリーが古いスナップショットから作られていると、その上での grep や
        「該当コードは存在しない」という結論が丸ごと誤りになる。
        実際に「入場ブロッカーは存在しない」という誤報告が出て、訂正に往復した。

    (4) 他 worktree に取り残された未コミット変更
        (1) は主ワークツリーしか見ず、(3) は未マージコミットを持つブランチしか回らない。
        そのため「ブランチはマージ済みなのに worktree には未コミットの WIP が残っている」
        状態が両方の網を抜ける。2026-08-01 の棚卸しで、この状態の worktree が実際に 32 本
        見つかった(合計100ファイル超の実ソース変更)。audit は「問題なし」と言っていた。
        この WIP は commit されていないので worktree を消せば復元手段が無い。

.PARAMETER Base
  比較の基準ブランチ。既定は dev。

.PARAMETER StaleCommits
  分岐点がこの数を超えて基準ブランチから離れていたら「古い」と判定する。既定 40。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\wip-audit.ps1
#>
param(
    [string]$Base = 'dev',
    [int]$StaleCommits = 40
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $repo
try {
    $problems = 0

    Write-Host ''
    Write-Host '============================================================'
    Write-Host "  WIP audit  (base = $Base)"
    Write-Host '============================================================'

    # ---- (1) 持ち主のいない未コミット変更 -----------------------------------------------------
    # 「どのブランチにも入っていない作業ツリーの変更」は、中断したエージェントの置き土産である
    # ことが多い。捨てる前に必ず git stash push -- <paths> で退避すること(復元できる)。
    Write-Host ''
    Write-Host '--- 1/4  持ち主のいない未コミット変更 ---'
    $dirty = @(git status --porcelain | Where-Object { $_ -notmatch '^\?\?' })
    if ($dirty.Count -eq 0) {
        Write-Host '  [ OK  ] 作業ツリーは clean'
    } else {
        Write-Host "  [WARN ] 未コミットが $($dirty.Count) 件ある。誰の作業か特定できないなら退避する:"
        Write-Host '          git stash push -m "orphan-<日付>: <経緯>" -- <paths>'
        $dirty | Select-Object -First 25 | ForEach-Object { Write-Host "          $_" }
        if ($dirty.Count -gt 25) { Write-Host "          ... 他 $($dirty.Count - 25) 件" }
        $problems++
    }

    # ---- (2) 未マージブランチ同士の担当ファイル衝突 --------------------------------------------
    Write-Host ''
    Write-Host '--- 2/4  未マージブランチの担当ファイル衝突 ---'
    $branches = @(git branch --format='%(refname:short)' |
                  Where-Object { $_ -and $_ -ne $Base -and $_ -notmatch '^\(' })
    $owners = @{}     # file -> [branch,...]
    $touched = @{}    # branch -> [file,...]
    foreach ($b in $branches) {
        $ahead = (git rev-list --count "$Base..$b" 2>$null)
        if (-not $ahead -or [int]$ahead -eq 0) { continue }   # 既にマージ済みは無視
        $files = @(git diff --name-only "$Base...$b" 2>$null)
        if ($files.Count -eq 0) { continue }
        $touched[$b] = $files
        foreach ($f in $files) {
            if (-not $owners.ContainsKey($f)) { $owners[$f] = @() }
            $owners[$f] += $b
        }
    }
    if ($touched.Count -eq 0) {
        Write-Host "  [ OK  ] $Base に未マージのブランチは無い"
    } else {
        Write-Host "  未マージ $($touched.Count) 本:"
        foreach ($b in ($touched.Keys | Sort-Object)) {
            Write-Host ("    {0,-42} {1} files" -f $b, $touched[$b].Count)
        }
        $clashes = @($owners.GetEnumerator() | Where-Object { $_.Value.Count -gt 1 })
        if ($clashes.Count -eq 0) {
            Write-Host '  [ OK  ] 担当ファイルの重なりは無い'
        } else {
            Write-Host "  [WARN ] $($clashes.Count) ファイルが複数ブランチから触られている(マージ時に手作業になる):"
            $clashes | Sort-Object { -$_.Value.Count } | Select-Object -First 20 | ForEach-Object {
                Write-Host ("          {0}" -f $_.Key)
                Write-Host ("            <- {0}" -f ($_.Value -join ', '))
            }
            if ($clashes.Count -gt 20) { Write-Host "          ... 他 $($clashes.Count - 20) ファイル" }
            $problems++
        }
    }

    # ---- (3) 分岐点が古いブランチ / worktree ---------------------------------------------------
    # ここが一番静かに壊れる。古いスナップショット上で grep しても「無い」としか出ないので、
    # 調査結果そのものが誤りになる。エージェントに調査させる前に必ず潰す。
    Write-Host ''
    Write-Host '--- 3/4  分岐点が古いブランチ・worktree ---'
    $stale = 0
    foreach ($b in ($touched.Keys | Sort-Object)) {
        $mb = (git merge-base $Base $b 2>$null)
        if (-not $mb) { continue }
        $behind = [int](git rev-list --count "$mb..$Base" 2>$null)
        if ($behind -gt $StaleCommits) {
            Write-Host ("  [WARN ] {0} の分岐点は {1} 基準で {2} コミット前 (= その上の調査結果は信用できない)" -f $b, $Base, $behind)
            $stale++
        }
    }
    if ($stale -eq 0) {
        Write-Host '  [ OK  ] すべてのブランチが十分新しい分岐点にある'
    } else {
        $problems++
    }

    # ---- (4) 他 worktree に取り残された未コミット変更 ------------------------------------------
    # (1) は主ワークツリー、(3) は未マージブランチしか見ないので、両方の網を抜ける穴がある。
    # 「ブランチはマージ済み(= rev-list で 0 件)なのに worktree には未コミットの WIP がある」
    # がそれで、worktree を消した瞬間に復元不能になる。ここだけが検出できる。
    Write-Host ''
    Write-Host '--- 4/4  他 worktree の未コミット変更 ---'
    $others = @(git worktree list --porcelain |
                Where-Object { $_ -like 'worktree *' } |
                ForEach-Object { $_.Substring(9) } |
                Where-Object { (Resolve-Path $_).Path -ne (Resolve-Path $repo).Path })
    $dirtyTrees = 0
    $dirtyFiles = 0
    foreach ($wt in $others) {
        $st = @(git -C $wt status --porcelain 2>$null | Where-Object { $_ -notmatch '^\?\?' })
        if ($st.Count -eq 0) { continue }
        $br = (git -C $wt rev-parse --abbrev-ref HEAD 2>$null)
        $ahead = (git rev-list --count "$Base..$br" 2>$null)
        if (-not $ahead) { $ahead = '?' }
        Write-Host ("  [WARN ] {0,-40} 未コミット {1,3} 件 / {2} への未マージ {3} 件" -f $br, $st.Count, $Base, $ahead)
        $dirtyTrees++
        $dirtyFiles += $st.Count
    }
    if ($dirtyTrees -eq 0) {
        Write-Host '  [ OK  ] 他 worktree に未コミット変更は無い'
    } else {
        Write-Host ''
        Write-Host "  合計 $dirtyTrees 本の worktree に $dirtyFiles ファイルの未コミット変更がある。"
        Write-Host '  未マージ 0 件なら「commit されていない = 消したら復元できない」ので、'
        Write-Host '  worktree を消す前に必ず中身を見て、要るものは stash か commit で拾うこと:'
        Write-Host '          git -C <worktree> stash push -m "orphan-<日付>: <経緯>"'
        $problems++
    }

    Write-Host ''
    Write-Host '============================================================'
    if ($problems -eq 0) {
        Write-Host '  問題なし。並列作業を始めてよい。'
    } else {
        Write-Host "  $problems 種類の問題がある。上の指示に従って片付けてから波を始めること。"
    }
    Write-Host '============================================================'
    Write-Host ''
    exit $problems
}
finally {
    Pop-Location
}
