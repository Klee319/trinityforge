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

.OUTPUTS
    終了コード 0 = 全バックエンド停止 / 1 = 1 台以上稼働中

.EXAMPLE
    .\check-servers-stopped.ps1
#>
[CmdletBinding()]
param(
    [string] $ConfigPath,
    [switch] $Quiet
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

foreach ($name in @($config.Servers.Keys)) {
    $server = $config.Servers[$name]
    $reasons = @()

    if (Get-NetTCPConnection -State Listen -LocalPort $server.RconPort -ErrorAction SilentlyContinue) {
        $reasons += "RCON $($server.RconPort) が LISTEN"
    }

    $lock = Join-Path $server.Root "world\session.lock"
    if ((Test-Path -LiteralPath $lock) -and (Test-FileLocked -Path $lock)) {
        $reasons += "world\session.lock がロック済み"
    }

    if ($reasons.Count -gt 0) {
        $busy.Add("$($server.Name): " + ($reasons -join " / "))
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
        Write-OpsLog "バックエンドはすべて停止しています。jar を差し替えても安全です。"
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
