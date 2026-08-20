<#
.SYNOPSIS
    バックエンドが 1 台も動いていないことを確認する。jar を差し替える前の関門。

.DESCRIPTION
    稼働中のサーバの jar を上書きすると、JVM が未ロードのクラスを読みに行った瞬間に
    NoClassDefFoundError が出る。/reload では直らず、JVM の完全な stop -> start だけが
    復旧手段になる。しかも進行データの保存経路で発症すると、その間の保存が丸ごと落ちる。
    だから配備スクリプト（ops\launch\deploy.cmd）は必ずこれを先に通す。

    判定は 2 系統を OR で取る。片方だけでは取りこぼすため:
      1. RCON ポートが LISTEN しているか（server.properties で RCON を有効にしている前提）
      2. world\session.lock が排他ロックされているか
         （RCON を無効にしている・起動途中で RCON がまだ開いていない場合に効く）

    ⚠️ java.exe のコマンドラインでは判定できない。server-loop.cmd は
    `cd /d <server root>` してから `java -jar "paper-....jar" nogui` を叩くので、
    コマンドラインにサーバの Root もディレクトリ名も一切現れない。同じ理屈で
    show-status.ps1 / purge-player-data.ps1 のコマンドライン照合も当たらないため、
    ここでは最初から採用しない（Gradle デーモンも java.exe なので、雑に java.exe の有無で
    判定すると配備が永久にできなくなる、という逆の失敗もある）。

    Velocity は【判定に含めない】。プロキシが上がっていてもバックエンドの jar は掴んでいないので
    差し替え自体は安全であり、ここで止めると無駄に足を引っ張る。参考情報としてだけ表示する。

.PARAMETER Quiet
    見つかったものだけを出す（待ちループから毎回呼ぶとき用）。

.PARAMETER Server
    判定対象を絞る。省略時は全バックエンド（従来の挙動）。
    `deploy.cmd --server` 用。**一部のバックエンドにだけ jar を配る**とき、触らないサーバが
    稼働中であることを理由に配備を止めるのは筋が悪いので、ゲートも同じ範囲へ絞る。

    受け付ける綴りは 3 通り（大小無視）: 設定キー(Main) / `Name`(main) / `Root` の末尾
    ディレクトリ名(Main_Server)。deploy.cmd は TF_BACKENDS のディレクトリ名を渡す。

    **どれにも一致しない名前を渡したら throw する。** ここで黙って 0 件を判定すると
    「全部停止している」と答えてしまい、**稼働中のサーバへ jar を上書きする**という、
    このスクリプトが存在する理由そのものの事故になる。

.OUTPUTS
    終了コード 0 = 対象バックエンドが全て停止 / 1 = 1 台以上稼働中

.EXAMPLE
    .\check-servers-stopped.ps1

.EXAMPLE
    .\check-servers-stopped.ps1 -Server Dev_Server
#>
[CmdletBinding()]
param(
    [string]   $ConfigPath,
    [switch]   $Quiet,
    [string[]] $Server
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Common.ps1")

# ファイルもポートも見るだけで RCON は叩かないので、パスワードは要求しない。
$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false

# world\session.lock は Minecraft がワールドを掴んでいる間だけ排他ロックされる。
# 開けたら止まっている、開けなければ動いている。RCON を切っていても効く唯一の手がかり。
function Test-FileLocked {
    param([Parameter(Mandatory)] [string] $Path)
    try {
        $stream = [System.IO.File]::Open($Path, 'Open', 'ReadWrite', 'None')
        $stream.Close()
        return $false
    } catch [System.IO.IOException] {
        return $true
    } catch {
        # 権限不足など。ロックの有無が分からないので「掴まれている」側に倒す。
        return $true
    }
}

$busy = New-Object System.Collections.Generic.List[string]

# -Server の解決。設定キー / Name / Root の末尾ディレクトリ名 のどれでも当たるようにする。
# 一致ゼロは throw（上の .PARAMETER Server 参照 — 黙って「全部停止」と答えると事故になる）。
$targetKeys = @($config.Servers.Keys)
if ($Server) {
    $resolved = New-Object System.Collections.Generic.List[string]
    foreach ($wanted in $Server) {
        if (-not $wanted) { continue }
        $hit = @($config.Servers.Keys) | Where-Object {
            $s = $config.Servers[$_]
            ($_ -eq $wanted) -or ($s.Name -eq $wanted) -or ((Split-Path $s.Root -Leaf) -eq $wanted)
        }
        if (-not $hit) {
            $known = (@($config.Servers.Keys) | ForEach-Object {
                "$_ / $($config.Servers[$_].Name) / $(Split-Path $config.Servers[$_].Root -Leaf)"
            }) -join " | "
            throw "-Server '$wanted' はどのバックエンドにも一致しません。指定できるのは: $known"
        }
        foreach ($h in $hit) { if (-not $resolved.Contains($h)) { $resolved.Add($h) } }
    }
    $targetKeys = $resolved.ToArray()
}

foreach ($name in $targetKeys) {
    # 変数名は $server にしないこと。PowerShell の変数名は大文字小文字を区別しないので
    # param の [string[]] $Server と同一変数になり、ハッシュテーブルを代入した瞬間に
    # 型制約で string[] へ黙って変換される（$backend.RconPort が「存在しない」で落ちる）。
    $backend = $config.Servers[$name]
    $reasons = @()

    if (Get-NetTCPConnection -State Listen -LocalPort $backend.RconPort -ErrorAction SilentlyContinue) {
        $reasons += "RCON $($backend.RconPort) が LISTEN"
    }

    $lock = Join-Path $backend.Root "world\session.lock"
    if ((Test-Path -LiteralPath $lock) -and (Test-FileLocked -Path $lock)) {
        $reasons += "world\session.lock がロック済み"
    }

    if ($reasons.Count -gt 0) {
        $busy.Add("$($backend.Name): " + ($reasons -join " / "))
    }
}

$javaProcesses = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue)

# Velocity は参考情報。バックエンドの jar を掴まないのでブロックしない。
$velocity = @($javaProcesses | Where-Object {
    $line = $_.CommandLine
    if (-not $line) { return $false }
    return ($line -match 'velocity[-\w.]*\.jar')
})

if ($busy.Count -eq 0) {
    if (-not $Quiet) {
        if ($Server) {
            Write-OpsLog ("対象バックエンドは停止しています: " +
                (($targetKeys | ForEach-Object { Split-Path $config.Servers[$_].Root -Leaf }) -join ", ") +
                "。ここに jar を差し替えても安全です（対象外のサーバは判定していません）。")
        } else {
            Write-OpsLog "バックエンドはすべて停止しています。jar を差し替えても安全です。"
        }
        if ($velocity.Count -gt 0) {
            Write-OpsLog ("参考: Velocity が稼働中です PID " +
                (($velocity | ForEach-Object { $_.ProcessId }) -join ", ") +
                "。バックエンドの jar は掴まないので配備は続行できますが、" +
                "jar を替えたらネットワーク全体を停止 -> 起動し直すこと。") -Level WARN
        }
    }
    exit 0
}

if ($Quiet) {
    # 待ちループから毎回呼ばれる想定。1 行だけ出す。
    Write-Host ("  まだ稼働中: " + (($busy | ForEach-Object { ($_ -split ":")[0] }) -join ", "))
    exit 1
}

Write-OpsLog "$($busy.Count) 件、稼働中のバックエンドを検出しました。" -Level WARN
foreach ($item in $busy) {
    Write-Host "  - $item"
}
Write-Host ""
Write-Host "稼働中に jar を上書きすると NoClassDefFoundError になり、JVM を落とすまで戻せません。"
Write-Host "先に停止してください: launch\stop-all.cmd  （または各コンソールで stop）"
exit 1
