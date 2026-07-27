<#
.SYNOPSIS
    資源サーバのワールドを週次でリセットする。手動実行も同じスクリプトを使う。

.DESCRIPTION
    手順:
      1. 予告をブロードキャスト (既定 10 / 5 / 1 分前)
      2. RCON stop -> FallbackRouter が在席者をメインへ自動退避。
         HuskSync が切替時にインベントリを保存するので、持ち物はこの時点で確定する
      3. プロセスが落ちきるまで待つ (タイムアウトしたら中断。強制終了はしない)
      4. 削除 (ops-config.psd1 の ResourceResetTargets)。
         ジャンクションは Remove-DirectorySafely が必ず弾く
      5. sync-configs.ps1 で整合チェック。問題があれば起動せず中断する
      6. server-loop.cmd が自動で起動し直すのを待つ
      7. Chunky のプリジェネレーションを開始し、メインへ完了を通知する

.PARAMETER DryRun
    停止も削除も行わず、削除対象の絶対パスとサイズを含めた実行計画だけを出力する。
    サーバを立てずに手順の正しさを確認できる。

.PARAMETER SkipPregen
    リセット後の Chunky プリジェネレーションを行わない。

.EXAMPLE
    .\reset-resource.ps1 -DryRun

.EXAMPLE
    .\reset-resource.ps1 -WarnMinutes @(5,1)
#>
[CmdletBinding()]
param(
    [string] $ConfigPath,
    [int[]]  $WarnMinutes = @(10, 5, 1),
    [int]    $StopTimeoutSeconds = 180,
    [int]    $StartTimeoutSeconds = 300,
    [int]    $PregenRadius = 2500,
    [switch] $SkipPregen,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")

$config   = Get-OpsConfig -Path $ConfigPath
$resource = $config.Servers.Resource
$main     = $config.Servers.Main
$targets  = $config.ResourceResetTargets

if ($DryRun) {
    Write-OpsLog "=== DRY RUN: 停止も削除も起動も行いません ===" -Level DRYRUN
}

Write-OpsLog "資源サーバのリセットを開始します: $($resource.Root)"

# ---- 0. 安全確認 -------------------------------------------------------------------------------
# 削除対象を組み立てる前に、危険なパスが混ざっていないかを見る。設定ファイルの編集ミスで
# plugins\TrinityForge がリストに載ったら、その瞬間に全プレイヤーの進行データが飛ぶ。

$forbidden = @("plugins\TrinityForge", "plugins", "")
$allTargets = @($targets.Directories) + @($targets.Files)
foreach ($relative in $allTargets) {
    $normalized = $relative.Trim().TrimEnd('\', '/')
    if ($forbidden -contains $normalized) {
        throw "中断: 削除対象に $relative が含まれています。" +
              "plugins\TrinityForge はメインと共有しているジャンクションです。" +
              "ops-config.psd1 の ResourceResetTargets を修正してください。"
    }
    if ([IO.Path]::IsPathRooted($normalized)) {
        throw "中断: 削除対象は資源サーバルートからの相対パスで書いてください: $relative"
    }
    if ($normalized -match '\.\.') {
        throw "中断: 削除対象に .. が含まれています: $relative"
    }
}

Write-OpsLog "削除対象の安全確認 OK ($($allTargets.Count) 件)"

# ---- 1-3. 予告して停止 -------------------------------------------------------------------------

$wasRunning = $false
if ($DryRun) {
    Write-OpsLog "RCON の到達確認をスキップ" -Level DRYRUN
    $wasRunning = $true
} else {
    $wasRunning = Test-RconReachable -HostName $resource.RconHost -Port $resource.RconPort `
        -Password $resource.RconPassword
}

if ($wasRunning) {
    if ($WarnMinutes.Count -gt 0) {
        Send-ShutdownWarnings -Server $resource -Reason "資源ワールドのリセット" `
            -WarnMinutes $WarnMinutes -DryRun:$DryRun
    }

    # 停止直前にもう一押し。HuskSync の保存はサーバ切替/退出のタイミングで走る。
    if (-not $DryRun) {
        $session = New-RconSession -HostName $resource.RconHost -Port $resource.RconPort `
            -Password $resource.RconPassword -Label $resource.Name
        try {
            Invoke-RconCommand -Session $session `
                -Command "say §c[重要] §fこれから資源ワールドをリセットします。メインサーバへ移動します。" | Out-Null
        } finally {
            Close-RconSession -Session $session
        }
    }

    # ループを止めずに再起動させたいので stop.flag は置かない。
    $stopped = Stop-ServerViaRcon -Server $resource -TimeoutSeconds $StopTimeoutSeconds -DryRun:$DryRun
    if (-not $stopped) {
        Write-OpsLog "停止できなかったのでリセットを中断します。ファイルは一切触っていません。" -Level ERROR
        exit 1
    }
} else {
    Write-OpsLog "資源サーバは停止しています。そのまま削除に進みます。"
}

# ---- 4. 削除 -----------------------------------------------------------------------------------

Write-Host ""
Write-OpsLog "--- 削除 ---"

foreach ($relative in $targets.Directories) {
    $path = Join-Path $resource.Root $relative
    Remove-DirectorySafely -Path $path -DryRun:$DryRun
}
foreach ($relative in $targets.Files) {
    $path = Join-Path $resource.Root $relative
    Remove-FileSafely -Path $path -DryRun:$DryRun
}

# ---- 5. 整合チェック ---------------------------------------------------------------------------

Write-Host ""
Write-OpsLog "--- 起動前の整合チェック ---"

$syncScript = Join-Path $PSScriptRoot "sync-configs.ps1"
$syncArgs = @("-File", $syncScript)
if ($ConfigPath) { $syncArgs += @("-ConfigPath", $ConfigPath) }
if ($DryRun)     { $syncArgs += "-DryRun" }

& powershell.exe -NoProfile -ExecutionPolicy Bypass @syncArgs
$syncExit = $LASTEXITCODE

if ($syncExit -ne 0) {
    if ($DryRun) {
        # ドライランでは残りの手順も見せたいので続行する。実行時はここで止まる。
        Write-OpsLog ("整合チェックが失敗しました (exit=$syncExit)。" +
            "実行時はここで中断し、資源サーバを起動しません。ドライランなので手順の表示を続けます。") -Level WARN
    } else {
        Write-OpsLog ("整合チェックに失敗しました (exit=$syncExit)。" +
            "資源サーバは起動しません。上の指摘を解消してから server-loop.cmd を起動してください。") -Level ERROR
        exit 1
    }
}

# ---- 6. 起動待ち -------------------------------------------------------------------------------

Write-Host ""
if ($DryRun) {
    Write-OpsLog "server-loop.cmd による自動起動を最大 $StartTimeoutSeconds 秒待つ" -Level DRYRUN
} else {
    Write-OpsLog "server-loop.cmd の自動起動を待ちます (最大 $StartTimeoutSeconds 秒)"
    $deadline = (Get-Date).AddSeconds($StartTimeoutSeconds)
    $up = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        if (Test-RconReachable -HostName $resource.RconHost -Port $resource.RconPort `
                -Password $resource.RconPassword) {
            $up = $true
            break
        }
    }
    if (-not $up) {
        Write-OpsLog ("$StartTimeoutSeconds 秒待っても資源サーバが起動しませんでした。" +
            "server-loop.cmd が動いているか確認してください。") -Level ERROR
        exit 1
    }
    Write-OpsLog "資源サーバが起動しました。"
}

# ---- 7. プリジェネレーションと通知 -------------------------------------------------------------

Write-Host ""
if ($SkipPregen) {
    Write-OpsLog "プリジェネレーションはスキップします (-SkipPregen)"
} else {
    $pregenCommands = @(
        "chunky world world"
        "chunky center 0 0"
        "chunky radius $PregenRadius"
        "chunky start"
    )
    if ($DryRun) {
        foreach ($command in $pregenCommands) {
            Write-OpsLog "RCON へ送信する (resource): $command" -Level DRYRUN
        }
    } else {
        $session = New-RconSession -HostName $resource.RconHost -Port $resource.RconPort `
            -Password $resource.RconPassword -Label $resource.Name
        try {
            foreach ($command in $pregenCommands) {
                $result = Invoke-RconCommand -Session $session -Command $command
                Write-OpsLog "chunky: $command -> $result"
            }
        } finally {
            Close-RconSession -Session $session
        }
    }
}

$notice = "say §a[お知らせ] §f資源ワールドをリセットしました。/server resource で新しいワールドへ行けます。"
if ($DryRun) {
    Write-OpsLog "RCON へ送信する (main): $notice" -Level DRYRUN
} else {
    try {
        $session = New-RconSession -HostName $main.RconHost -Port $main.RconPort `
            -Password $main.RconPassword -Label $main.Name
        try {
            Invoke-RconCommand -Session $session -Command $notice | Out-Null
        } finally {
            Close-RconSession -Session $session
        }
    } catch {
        # メインが落ちていてもリセット自体は成功している。警告に留める。
        Write-OpsLog "メインサーバへの通知に失敗しました: $($_.Exception.Message)" -Level WARN
    }
}

Write-Host ""
Write-OpsLog "資源サーバのリセットが完了しました。"
exit 0
