<#
.SYNOPSIS
    ops スクリプト共通のロード・ログ・削除ガード。

.DESCRIPTION
    このディレクトリのスクリプトは全て「稼働中の本番サーバのファイルを消す」可能性があるため、
    危険な操作は必ずここのヘルパー経由にする。特に Remove-DirectorySafely は
    【ジャンクションを辿らせない】ための唯一の関門であり、直接 Remove-Item を書いてはならない。

    本構成では plugins/TrinityForge がメインサーバの実体へのディレクトリジャンクションになる。
    Remove-Item -Recurse はジャンクションの【中身】を消しに行くため、資源サーバを掃除した
    つもりで全プレイヤーの進行データと全 config が消える。これが本構成で最も重大な事故ポイント。
#>

Set-StrictMode -Version Latest

function Get-OpsConfig {
    <#
    .SYNOPSIS ops-config.psd1 を読み、RCON パスワードを環境変数から埋めて返す。
    .DESCRIPTION
        パスワードは設定ファイルに書かせない。環境変数 TF_RCON_MAIN_PASSWORD /
        TF_RCON_RESOURCE_PASSWORD から読む (RUNBOOK の「タスクスケジューラの登録」を参照)。
    #>
    [CmdletBinding()]
    param([string] $Path)

    if (-not $Path) {
        $Path = Join-Path (Split-Path $PSScriptRoot -Parent) "ops-config.psd1"
    }
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "設定ファイルがありません: $Path`n" +
              "ops/ops-config.sample.psd1 をコピーして ops/ops-config.psd1 を作成してください。"
    }

    $config = Import-PowerShellDataFile -LiteralPath $Path

    foreach ($name in @("Main", "Resource")) {
        if (-not $config.Servers.ContainsKey($name)) {
            throw "$Path に Servers.$name の定義がありません。"
        }
        $envName = "TF_RCON_$($name.ToUpperInvariant())_PASSWORD"
        $password = [Environment]::GetEnvironmentVariable($envName)
        if ([string]::IsNullOrWhiteSpace($password)) {
            throw "環境変数 $envName が未設定です。$name サーバの RCON パスワードを設定してください。"
        }
        $config.Servers[$name].RconPassword = $password
    }

    return $config
}

function Write-OpsLog {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [string] $Message,
        [ValidateSet("INFO", "WARN", "ERROR", "DRYRUN")] [string] $Level = "INFO"
    )

    $stamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
    $line = "[$stamp] [$Level] $Message"
    switch ($Level) {
        "WARN"   { Write-Warning $line }
        # ERROR でも Write-Error は使わない。呼び出し側は $ErrorActionPreference = "Stop" で
        # 走っており、Write-Error がその場で例外化して自前の exit コード制御を奪ってしまう。
        "ERROR"  { Write-Host $line -ForegroundColor Red }
        "DRYRUN" { Write-Host $line -ForegroundColor Cyan }
        default  { Write-Host $line }
    }
}

function Test-ReparsePoint {
    <#
    .SYNOPSIS パスがジャンクション/シンボリックリンクなら $true。
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)] [string] $Path)

    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    $item = Get-Item -LiteralPath $Path -Force
    return [bool]($item.Attributes -band [IO.FileAttributes]::ReparsePoint)
}

function Remove-DirectorySafely {
    <#
    .SYNOPSIS
        ディレクトリを再帰削除する。ただしジャンクション/シンボリックリンクなら【中断】する。
    .DESCRIPTION
        リンク自体を消したい場合は -AllowLinkRemoval を付ける。この場合 .NET の Directory.Delete で
        リンクだけを外し、リンク先には一切触れない。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [string] $Path,
        [switch] $DryRun,
        [switch] $AllowLinkRemoval
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        Write-OpsLog "削除対象なし (存在しない): $Path"
        return
    }

    if (Test-ReparsePoint -Path $Path) {
        if (-not $AllowLinkRemoval) {
            throw "中断: $Path はジャンクション/シンボリックリンクです。" +
                  "再帰削除するとリンク先の実体が消えます。意図的にリンクを外す場合のみ " +
                  "-AllowLinkRemoval を指定してください。"
        }
        if ($DryRun) {
            Write-OpsLog "リンクのみ削除 (リンク先には触れない): $Path" -Level DRYRUN
            return
        }
        [System.IO.Directory]::Delete($Path, $false)
        Write-OpsLog "リンクを削除しました (リンク先は無傷): $Path"
        return
    }

    # 直下にジャンクションが紛れていないかも確認する。plugins/ を丸ごと消すような
    # 誤った呼び出しをここで止める。
    $nestedLinks = @(Get-ChildItem -LiteralPath $Path -Recurse -Force -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint })
    if ($nestedLinks) {
        throw "中断: $Path の配下にジャンクションがあります。再帰削除するとリンク先が消えます。`n" +
              ($nestedLinks.FullName -join "`n")
    }

    if ($DryRun) {
        $size = Get-DirectorySizeMB -Path $Path
        Write-OpsLog "削除する: $Path ($size MB)" -Level DRYRUN
        return
    }

    Remove-Item -LiteralPath $Path -Recurse -Force
    Write-OpsLog "削除しました: $Path"
}

function Remove-FileSafely {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [string] $Path,
        [switch] $DryRun
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        Write-OpsLog "削除対象なし (存在しない): $Path"
        return
    }
    if ($DryRun) {
        Write-OpsLog "削除する: $Path" -Level DRYRUN
        return
    }
    Remove-Item -LiteralPath $Path -Force
    Write-OpsLog "削除しました: $Path"
}

function Get-DirectorySizeMB {
    [CmdletBinding()]
    param([Parameter(Mandatory)] [string] $Path)

    # 空ディレクトリだと Measure-Object が何も返さず、StrictMode 下で .Sum の参照が落ちる。
    $measured = Get-ChildItem -LiteralPath $Path -Recurse -Force -File -ErrorAction SilentlyContinue |
        Measure-Object -Property Length -Sum
    if (-not $measured -or -not $measured.Sum) { return 0 }
    return [math]::Round($measured.Sum / 1MB, 1)
}

function Wait-ForServerStop {
    <#
    .SYNOPSIS
        RCON に応答しなくなるまで待つ。タイムアウトしても【強制終了はしない】。
    .DESCRIPTION
        kill するとフラッシュ途中の SQLite (= ジャンクション経由でメインと共有している実体) を
        壊しうる。落ちきらない場合は失敗として返し、人間に判断させる。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [hashtable] $Server,
        [int] $TimeoutSeconds = 180,
        [int] $PollSeconds = 3
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds $PollSeconds
        $alive = Test-RconReachable -HostName $Server.RconHost -Port $Server.RconPort `
            -Password $Server.RconPassword
        if (-not $alive) {
            Write-OpsLog "サーバが停止しました: $($Server.Name)"
            return $true
        }
    }

    Write-OpsLog ("$($Server.Name) が $TimeoutSeconds 秒で停止しませんでした。" +
        "強制終了はしません (共有 SQLite 破損を避けるため)。手動で確認してください。") -Level ERROR
    return $false
}

function Send-ShutdownWarnings {
    <#
    .SYNOPSIS 停止予告を段階的にブロードキャストする。
    .PARAMETER WarnMinutes 予告を出す残り分数 (降順)。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [hashtable] $Server,
        [Parameter(Mandatory)] [string]    $Reason,
        [int[]] $WarnMinutes = @(10, 5, 1),
        [switch] $DryRun
    )

    # @() で包む: 要素が1つだとパイプラインがスカラーを返し、StrictMode 下で .Count が落ちる。
    $sorted = @($WarnMinutes | Sort-Object -Descending)
    for ($i = 0; $i -lt $sorted.Count; $i++) {
        $minutes = $sorted[$i]
        $message = "say §e[お知らせ] §fあと §c$minutes 分§f で $Reason します。"
        if ($DryRun) {
            Write-OpsLog "RCON へ送信する ($($Server.Name)): $message" -Level DRYRUN
        } else {
            $session = New-RconSession -HostName $Server.RconHost -Port $Server.RconPort `
                -Password $Server.RconPassword -Label $Server.Name
            try {
                Invoke-RconCommand -Session $session -Command $message | Out-Null
            } finally {
                Close-RconSession -Session $session
            }
        }

        $next = if ($i + 1 -lt $sorted.Count) { $sorted[$i + 1] } else { 0 }
        $waitSeconds = ($minutes - $next) * 60
        if ($DryRun) {
            Write-OpsLog "$waitSeconds 秒待機する" -Level DRYRUN
        } elseif ($waitSeconds -gt 0) {
            Start-Sleep -Seconds $waitSeconds
        }
    }
}

function Stop-ServerViaRcon {
    <#
    .SYNOPSIS RCON で stop を送り、落ちきるまで待つ。落ちなければ $false。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [hashtable] $Server,
        [int] $TimeoutSeconds = 180,
        [switch] $DryRun
    )

    if ($DryRun) {
        Write-OpsLog "RCON へ 'stop' を送り、最大 $TimeoutSeconds 秒待つ ($($Server.Name))" -Level DRYRUN
        return $true
    }

    $session = New-RconSession -HostName $Server.RconHost -Port $Server.RconPort `
        -Password $Server.RconPassword -Label $Server.Name
    try {
        Invoke-RconCommand -Session $session -Command "stop" | Out-Null
    } finally {
        Close-RconSession -Session $session
    }
    Write-OpsLog "stop を送信しました: $($Server.Name)"
    return (Wait-ForServerStop -Server $Server -TimeoutSeconds $TimeoutSeconds)
}
