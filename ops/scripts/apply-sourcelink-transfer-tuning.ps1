<#
.SYNOPSIS
    配備先の ArsPaper 設定へ「割合排出」と「経路パーティクルの見やすさ」を反映する。

.DESCRIPTION
    2026-08-24 の報告2件（W-210 / W-211）への対処。

    【W-211 転送速度が生成量に見合っていない】
    ソースリンクのバッファ→隣接ジャーの排出は完全な定額(max-per-transfer ÷ interval-ticks)
    だったので、「1点あたり 0.1 秒」が燃料の価値に関係なく固定されていた。
    階梯の transfer-multiplier と yield-multiplier は同じ倍率で伸びるため、
    上位ソースリンクにしてもこの比率は一切改善しない。結果:
      圧縮薪1個(2,187)      → 約3分40秒
      ソースの欠片1個(4,500) → 7分30秒
      ソース機関1個(3,000万) → 約35日
    → transfer.sourcelink.drain-ratio(既定 0.25)を追加し、
      「定額」と「バッファ×割合」の大きい方を出すようにした(jar 側の変更が必要)。
      注ぎ切れない分はバッファへ戻るので、ジャーの容量が上限として働く設計は変わらない。

    【W-210 経路のパーティクルが見にくい】
    2026-08-01 に負荷を下げるため 1ブロックに1粒子・1秒ごとの描き直しへ落としたが、
    そのせいで線として読めなくなっていた。原因は3つ重なっている:
      (1) 間隔1.0m + 粒の大きさ0.8〜1.2 で「大きい玉の飛び石」になる
      (2) DUST の寿命は約8〜40tick なので、20tick 周期だと描き直しの合間に消えて点滅する
      (3) view-distance 48m × 16経路 + 隣接供給の全描画で周囲一帯が点だらけになる
    → 「細く・詰めて・切れない線を、近くの数本だけ」へ振り直す:
      interval-ticks 20→10 / spacing 1.0→0.5 / dot-size 0.45(新規) /
      view-distance 48→24 / max-paths 16→8
      粒子数は毎秒約528→約1,008(初版は約8,064)。密度を2倍にした代わりに
      範囲と本数を半分にしたので合計は初版の1/8に収まる。

    【なぜスクリプトが要るのか】
    ArsPaper は既存の yml を自分では更新しない（saveResource(..., false)）。
    つまり jar を差し替えても /ars reload しても配備先の yml は書き換わらない。
    リポジトリ側の編集を届ける経路は ops\launch\deploy-config-head.cmd だけで、
    そちらは全バックエンドを止めてからでないと流せない。
    これは「止めずに今すぐ揃えたい」ときの経路。

    【安全のための性質】
      - 何度流しても同じ結果になる（既に新しい値なら触らない）
      - 値が「旧既定でも新既定でもない」= 誰かが手で調整した形跡があれば
        書き換えずに警告する（勝手に上書きしない）
      - 書き込み前に .bak-<日時> へ退避する
      - yml は UTF-8(BOM なし) + LF で書き戻す

    ⚠ dot-size と drain-ratio は jar 側の対応が要る新しいキー。
      古い jar のままこの yml を入れても、知らないキーとして無視されるだけで害はない。

.PARAMETER Target
    all（既定）、または ops-config.psd1 の Servers にあるサーバ名。

.PARAMETER DryRun
    書き込まず、変更予定だけを出す。

.EXAMPLE
    .\apply-sourcelink-transfer-tuning.ps1 -DryRun

.EXAMPLE
    .\apply-sourcelink-transfer-tuning.ps1
#>
[CmdletBinding()]
param(
    [string] $Target = "all",
    [string] $ConfigPath,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Common.ps1")

$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false

# 書き換える単純なスカラー値。
#   Indent … このキーがぶら下がっている階層（path-particles 配下は 6、sourcelink/network 直下は 4）
#   Old    … 旧既定。これと一致する行だけ書き換える
#   New    … 新既定
$scalarEdits = @(
    @{ Key = "interval-ticks"; Indent = 6; Old = "20";  New = "10"  },
    @{ Key = "spacing";        Indent = 6; Old = "1.0"; New = "0.5" },
    @{ Key = "view-distance";  Indent = 6; Old = "48";  New = "24"  },
    @{ Key = "max-paths";      Indent = 6; Old = "16";  New = "8"   }
)

# 新規に足すキー。Anchor 行の直後へ入れる。
$insertions = @(
    @{
        Key    = "dot-size"
        Indent = 6
        Anchor = "spacing"
        Lines  = @(
            "      # 粒の大きさ。1.0 がバニラのレッドストーン粒と同じで、線に使うと太すぎて",
            "      # 「大きい玉の飛び石」になり線として読めない(2026-08-24 に設定へ出した)。",
            "      # 終端マーカーと隣接供給の大きさもこの値からの相対値で決まる。",
            "      dot-size: 0.45"
        )
    },
    @{
        Key    = "drain-ratio"
        Indent = 4
        Anchor = "buffer-cap"
        Lines  = @(
            "",
            "    # 1周期に「バッファの何割まで」出せるか(0.0〜1.0)。実際の排出量は",
            "    #   max(max-per-transfer x 階梯倍率 x コア倍率, バッファ x drain-ratio)",
            "    # の大きい方。0 にすると従来どおりの完全定額になる。",
            "    #",
            "    # ■ 2026-08-24 追加の理由(生成量に対して転送が遅すぎた)",
            "    #   定額だけだと「1点あたり 100tick ÷ 50 = 2tick = 0.1秒」が燃料の価値に",
            "    #   関係なく固定になる。階梯の transfer-multiplier と yield-multiplier は",
            "    #   同じ倍率で伸びるので、上位ソースリンクにしてもこの比率は改善しない。",
            "    #",
            "    # ■ 既定 0.25 の根拠(定額50・周期100tick。バッファを空にするまでの秒数)",
            "    #     投入量               定額のみ    0.10     0.25",
            "    #     100 (溶岩バケツ)           10       10       10  ← 小口は定額が勝つ",
            "    #     2,187 (圧縮薪 3x)         220      120       65",
            "    #     4,500 (ソースの欠片)       450      155       75",
            "    #     140,000 (圧縮薪1スタック) 14,000     320      135",
            "    #     30,000,000 (ソース機関) 3,000,000    575      230",
            "    #",
            "    # ⚠ 割合の項に階梯倍率は掛けていない。掛けると生成量倍率と再び同率で伸びて",
            "    #   「階梯を上げても比率が変わらない」元の構造に戻る。",
            "    # ⚠ ジャーの空きより多く出しても損失にはならない。注ぎ切れなかった分のうち",
            "    #   バッファ由来はバッファへ戻る(受動生成分だけは従来どおり捨てられる)。",
            "    drain-ratio: 0.25"
        )
    }
)

# yml を UTF-8(BOM なし) + LF で書き戻す。PowerShell の > や Set-Content -Encoding utf8 は
# BOM を付けるので使わない（yml の途中に来た BOM を SnakeYAML は文書境界と誤読する）。
function Write-YamlLines {
    param([string] $Path, [string[]] $Lines)
    $content = ($Lines -join "`n") + "`n"
    [System.IO.File]::WriteAllText($Path, $content, [System.Text.UTF8Encoding]::new($false))
}

function Backup-File {
    param([string] $Path)
    $backup = "$Path.bak-" + (Get-Date).ToString("yyyyMMdd_HHmmss")
    Copy-Item -LiteralPath $Path -Destination $backup
    Write-OpsLog "  退避しました: $(Split-Path $backup -Leaf)"
}

# 「indent 個の空白 + key:」の行だけに当てる正規表現。
# interval-ticks は sourcelink(4) / network(4) / path-particles(6) の3箇所に出るので、
# インデントで区別しないと関係ないところを書き換えてしまう。
function Get-KeyPattern {
    param([string] $Key, [int] $Indent)
    return "^ {$Indent}$([regex]::Escape($Key)):"
}

Write-OpsLog "配備先の ArsPaper 設定へ割合排出と経路パーティクルの調整を反映します"

$targets = @(
    if ($Target -ieq "all") {
        foreach ($name in @($config.Servers.Keys)) { $config.Servers[$name] }
    } else {
        Resolve-OpsServer -Config $config -Target $Target
    }
)

if ($DryRun) {
    Write-OpsLog "=== DRY RUN: 何も書き込みません ===" -Level DRYRUN
}

$failures = New-Object System.Collections.Generic.List[string]
$changed  = 0
$pending  = 0

foreach ($server in $targets) {
    Write-Host ""
    Write-OpsLog "--- $($server.Name) ---"

    $file = Join-Path $server.Root "plugins\ArsPaper\sourcelinks.yml"
    if (-not (Test-Path -LiteralPath $file)) {
        Write-OpsLog "ArsPaper が入っていないバックエンドです（sourcelinks.yml なし）: $file"
        continue
    }

    $lines = @([System.IO.File]::ReadAllLines($file, [System.Text.UTF8Encoding]::new($false)))
    $edits = New-Object System.Collections.Generic.List[string]
    $skips = New-Object System.Collections.Generic.List[string]

    # ---- 1) 既存のスカラー値を書き換える ----
    foreach ($edit in $scalarEdits) {
        $pattern = Get-KeyPattern -Key $edit.Key -Indent $edit.Indent
        $hits = @(0..($lines.Count - 1) | Where-Object { $lines[$_] -match $pattern })
        if ($hits.Count -eq 0) {
            $skips.Add("$($edit.Key): 行が見つかりません（構造が変わっている可能性）")
            continue
        }
        foreach ($index in $hits) {
            $value = ($lines[$index] -replace $pattern, "").Split("#")[0].Trim()
            if ($value -eq $edit.New) { continue }
            if ($value -ne $edit.Old) {
                # 旧既定でも新既定でもない ＝ 誰かが手で調整している。勝手に上書きしない。
                $skips.Add("$($edit.Key): 現在値 $value は旧既定 $($edit.Old) と違うので触りません")
                continue
            }
            $lines[$index] = (" " * $edit.Indent) + $edit.Key + ": " + $edit.New
            $edits.Add("$($edit.Key): $($edit.Old) -> $($edit.New)")
        }
    }

    # ---- 2) 新しいキーを足す ----
    foreach ($insertion in $insertions) {
        $selfPattern = Get-KeyPattern -Key $insertion.Key -Indent $insertion.Indent
        if (@($lines | Where-Object { $_ -match $selfPattern }).Count -gt 0) { continue }

        $anchorPattern = Get-KeyPattern -Key $insertion.Anchor -Indent $insertion.Indent
        $anchor = -1
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match $anchorPattern) { $anchor = $i; break }
        }
        if ($anchor -lt 0) {
            $skips.Add("$($insertion.Key): 挿入位置の $($insertion.Anchor) が見つかりません")
            continue
        }

        $merged = @()
        $merged += $lines[0..$anchor]
        $merged += $insertion.Lines
        if ($anchor + 1 -le $lines.Count - 1) {
            $merged += $lines[($anchor + 1)..($lines.Count - 1)]
        }
        $lines = $merged
        $edits.Add("$($insertion.Key): 新規追加（$($insertion.Anchor) の直後）")
    }

    foreach ($skip in $skips) { Write-OpsLog "  スキップ: $skip" -Level WARN }

    if ($edits.Count -eq 0) {
        Write-OpsLog "  変更なし（既に反映済み）"
        continue
    }

    foreach ($edit in $edits) {
        Write-OpsLog "  $edit" -Level $(if ($DryRun) { "DRYRUN" } else { "INFO" })
    }

    if ($DryRun) { $pending++; continue }

    Backup-File -Path $file
    Write-YamlLines -Path $file -Lines $lines

    # 書けたことを読み直して確認する。
    $verify = @([System.IO.File]::ReadAllLines($file, [System.Text.UTF8Encoding]::new($false)))
    $ok = $true
    foreach ($edit in $scalarEdits) {
        $pattern = Get-KeyPattern -Key $edit.Key -Indent $edit.Indent
        foreach ($line in @($verify | Where-Object { $_ -match $pattern })) {
            $value = ($line -replace $pattern, "").Split("#")[0].Trim()
            if ($value -eq $edit.Old) { $ok = $false }
        }
    }
    foreach ($insertion in $insertions) {
        $pattern = Get-KeyPattern -Key $insertion.Key -Indent $insertion.Indent
        if (@($verify | Where-Object { $_ -match $pattern }).Count -eq 0) { $ok = $false }
    }
    if ($ok) {
        Write-OpsLog "  反映を確認しました"
        $changed++
    } else {
        $failures.Add("[$($server.Name)] 書き込み後の確認に失敗しました（sourcelinks.yml を目視してください）")
    }
}

Write-Host ""
if ($failures.Count -gt 0) {
    foreach ($failure in $failures) { Write-OpsLog $failure -Level ERROR }
    exit 1
}

if ($DryRun) {
    if ($pending -gt 0) {
        Write-OpsLog "DRY RUN: $pending 台に変更が必要です。-DryRun を外すと書き換えます。" -Level DRYRUN
    } else {
        Write-OpsLog "DRY RUN: 全台が既に反映済みです。" -Level DRYRUN
    }
    exit 0
}

if ($changed -gt 0) {
    Write-OpsLog "$changed 台へ反映しました。"
    Write-OpsLog "反映は各サーバで /ars reload（パーティクルのタイマーも張り直されます）。" -Level WARN
    Write-OpsLog "drain-ratio と dot-size は新しい ArsPaper jar が入っていないと無視されます。" -Level WARN
} else {
    Write-OpsLog "全台が既に反映済みです。変更はありません。"
}
exit 0
