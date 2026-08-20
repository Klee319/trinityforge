<#
.SYNOPSIS
    ネットワーク全体を安全な順序で停止する。

.DESCRIPTION
    起動順の【逆】に落とす: Velocity -> dev -> resource -> main -> Garnet。
    Velocity を先に落とすと、プレイヤーは切断されてからバックエンドの保存が走る。

    server-loop.cmd は「stop したら自動で起動し直す」ループなので、
    落としたままにするには先に stop.flag を置く必要がある。置き忘れると
    RCON stop の直後に上がってきて「止まらない」ように見える。

    【強制終了はしない。】ジャンクション経由でメインと共有している SQLite を
    フラッシュ途中で殺すと、全プレイヤーの進行データを壊しうる。
    落ちきらない場合は失敗として報告し、人間に判断させる。

.PARAMETER KeepDown
    stop.flag を残す（既定）。-KeepDown:$false にすると stop.flag を置かないので、
    server-loop.cmd が自動で起動し直す（＝再起動になる）。

.PARAMETER IncludeGarnet
    Garnet も停止する。MariaDB は Windows サービスなので触らない。

.EXAMPLE
    .\stop-network.ps1 -DryRun

.EXAMPLE
    .\stop-network.ps1 -WarnMinutes @(5,1)
#>
[CmdletBinding()]
param(
    [string] $ConfigPath,
    [int[]]  $WarnMinutes = @(5, 1),
    [int]    $StopTimeoutSeconds = 180,
    [switch] $KeepDown = $true,
    [switch] $IncludeGarnet,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")

# DryRun では RCON を叩かないので、パスワード未設定でも空撃ちは通す（実行時は throw する）。
$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:(-not $DryRun)

if ($DryRun) {
    Write-OpsLog "=== DRY RUN: 何も停止しません ===" -Level DRYRUN
    Write-MissingRconPasswordWarning -Config $config
}

# ---- 1. Velocity ---------------------------------------------------------------------------------
#  プロキシを先に落として、これ以上プレイヤーが入ってこない状態にする。

Write-OpsLog "--- velocity ---"
$velocityProcesses = @()
if ($config.ContainsKey("VelocityRoot") -and $config.VelocityRoot) {
    # velocity の jar を掴んでいる java だけを選ぶ。バックエンドを巻き込まないため。
    $velocityProcesses = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -match 'velocity[-\w.]*\.jar' })
}
if ($velocityProcesses.Count -eq 0) {
    Write-OpsLog "Velocity は起動していません。"
} elseif ($DryRun) {
    Write-OpsLog "Velocity を停止する (PID $(($velocityProcesses.ProcessId) -join ', '))" -Level DRYRUN
} else {
    # Velocity に RCON は無い。コンソールへ end を送る口が無いので、
    # CloseMainWindow でウィンドウを閉じて正常終了させる。
    foreach ($process in $velocityProcesses) {
        $handle = Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue
        if ($handle) {
            [void]$handle.CloseMainWindow()
            Write-OpsLog "Velocity へ終了要求を送りました (PID $($process.ProcessId))"
        }
    }
    Start-Sleep -Seconds 5
}

# ---- 2. バックエンド（起動の逆順） ----------------------------------------------------------------

# main を最後に落とす。共有 SQLite の実体を持っている側なので、
# 参照している resource / dev より後に閉じる。
$order = @("Dev", "Resource", "Main") | Where-Object { $config.Servers.ContainsKey($_) }
$failures = @()

foreach ($name in $order) {
    $server = $config.Servers[$name]
    Write-Host ""
    Write-OpsLog "--- $($server.Name) ---"

    if (-not $DryRun) {
        $alive = Test-RconReachable -HostName $server.RconHost -Port $server.RconPort `
            -Password $server.RconPassword
        if (-not $alive) {
            Write-OpsLog "$($server.Name) は既に停止しています。スキップします。" -Level WARN
            continue
        }
    }

    # server-loop.cmd が上げ直さないように、stop より【前】に置く。
    $flag = Join-Path $server.Root "stop.flag"
    if ($KeepDown) {
        if ($DryRun) {
            Write-OpsLog "stop.flag を置く: $flag" -Level DRYRUN
        } else {
            Set-Content -LiteralPath $flag -Value "stopped by stop-network.ps1 at $(Get-Date -Format s)"
            Write-OpsLog "stop.flag を置きました（起動し直しません）"
        }
    }

    if ($WarnMinutes.Count -gt 0) {
        Send-ShutdownWarnings -Server $server -Reason "サーバ停止" `
            -WarnMinutes $WarnMinutes -DryRun:$DryRun
    }

    $stopped = Stop-ServerViaRcon -Server $server -TimeoutSeconds $StopTimeoutSeconds -DryRun:$DryRun
    if (-not $stopped) {
        $failures += $server.Name
        Write-OpsLog "$($server.Name) の停止に失敗しました。以降を中断します。" -Level ERROR
        break
    }
}

# ---- 3. Garnet -----------------------------------------------------------------------------------
#  MariaDB は Windows サービスなので触らない（他の用途で使っている可能性がある）。

if ($IncludeGarnet) {
    Write-Host ""
    Write-OpsLog "--- garnet ---"
    $garnet = @(Get-Process GarnetServer -ErrorAction SilentlyContinue)
    if ($garnet.Count -eq 0) {
        Write-OpsLog "Garnet は起動していません。"
    } elseif ($DryRun) {
        Write-OpsLog "Garnet を停止する (PID $(($garnet.Id) -join ', '))" -Level DRYRUN
    } else {
        # ウィンドウ無し (start-garnet.cmd は -WindowStyle Hidden で上げる) だと
        # CloseMainWindow は掴むウィンドウが無く false を返す。その場合だけ Stop-Process へ落とす。
        #
        # Minecraft サーバと違い、ここで強制終了しても壊れるものが無い:
        # Garnet は HuskSync の一時キャッシュで、正本は MariaDB にある。
        # しかもこの時点で全バックエンドは停止済み＝転送中のプレイヤーがいない。
        foreach ($process in $garnet) {
            $closed = $false
            if ($process.MainWindowHandle -ne [IntPtr]::Zero) {
                $closed = $process.CloseMainWindow()
            }
            if (-not $closed) {
                Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
                Write-OpsLog "Garnet (PID $($process.Id)) を終了しました（ウィンドウが無いため直接終了）。"
            }
        }
        Start-Sleep -Seconds 3
        if (@(Get-Process GarnetServer -ErrorAction SilentlyContinue).Count -gt 0) {
            Write-OpsLog "Garnet がまだ残っています。手動で確認してください。" -Level WARN
        } else {
            Write-OpsLog "Garnet を停止しました。"
        }
    }
}

Write-Host ""
if ($failures.Count -gt 0) {
    Write-OpsLog "停止に失敗したサーバ: $($failures -join ', ')" -Level ERROR
    exit 1
}
if ($KeepDown -and -not $DryRun) {
    Write-OpsLog "stop.flag を置いてあります。次に起動するときは launch\start-*.cmd を使うこと"
    Write-OpsLog "（start 側が stop.flag を消してから起動します）。"
}
Write-OpsLog "完了しました。"
exit 0
