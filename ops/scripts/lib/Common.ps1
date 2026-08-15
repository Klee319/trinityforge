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
        パスワードは設定ファイルに書かせない。Servers に書かれたキーごとに
        環境変数 TF_RCON_<キー名>_PASSWORD から読む (Main -> TF_RCON_MAIN_PASSWORD)。
        キーを固定しないので、ops-config.psd1 に Dev を足せばそれだけで扱える。
    .PARAMETER RequireRconPasswords
        既定で必須。RCON を使わないスクリプト (preflight など) は
        -RequireRconPasswords:$false を渡して、パスワード未設定でも読めるようにする。
    #>
    [CmdletBinding()]
    param(
        [string] $Path,
        [switch] $RequireRconPasswords = $true
    )

    if (-not $Path) {
        # $PSScriptRoot はこのファイルがある ops/scripts/lib を指す。ops/ まで2つ上がる。
        $opsRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
        $Path = Join-Path $opsRoot "ops-config.psd1"
    }
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "設定ファイルがありません: $Path`n" +
              "ops/ops-config.sample.psd1 をコピーして ops/ops-config.psd1 を作成してください。"
    }

    $config = Import-PowerShellDataFile -LiteralPath $Path

    if (-not $config.ContainsKey("Servers") -or $config.Servers.Count -eq 0) {
        throw "$Path に Servers の定義がありません。"
    }
    # main と resource はこの構成の前提。名前を間違えたまま気付かない事故を防ぐ。
    foreach ($required in @("Main", "Resource")) {
        if (-not $config.Servers.ContainsKey($required)) {
            throw "$Path に Servers.$required の定義がありません。"
        }
    }

    foreach ($name in @($config.Servers.Keys)) {
        $server = $config.Servers[$name]
        foreach ($key in @("Name", "Root", "RconHost", "RconPort")) {
            if (-not $server.ContainsKey($key)) {
                throw "$Path の Servers.$name に $key がありません。"
            }
        }

        $envName = "TF_RCON_$($name.ToUpperInvariant())_PASSWORD"
        $password = [Environment]::GetEnvironmentVariable($envName)
        if ([string]::IsNullOrWhiteSpace($password)) {
            if ($RequireRconPasswords) {
                throw "環境変数 $envName が未設定です。$name サーバの RCON パスワードを設定してください。"
            }
            $password = $null
        }
        $server.RconPassword = $password
    }

    return $config
}

function Write-MissingRconPasswordWarning {
    <#
    .SYNOPSIS DryRun 時に未設定の RCON パスワードを警告する。
    .DESCRIPTION
        DryRun では RCON を一切叩かないので、パスワード未設定でも空撃ちは通す。
        ここで throw すると【空撃ちの本題（reset-resource の削除対象一覧など）が
        一切表示されないまま落ちる】ため、警告に留めて続行させる。
        実行時は Get-OpsConfig が throw するので、取り違えは起こらない。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [hashtable] $Config
    )

    $missing = @()
    foreach ($name in @($Config.Servers.Keys)) {
        if (-not $Config.Servers[$name].RconPassword) {
            $missing += "TF_RCON_$($name.ToUpperInvariant())_PASSWORD"
        }
    }
    if ($missing.Count -eq 0) { return }

    Write-OpsLog "RCON パスワードが未設定です: $($missing -join ', ')" -Level WARN
    Write-OpsLog "DryRun なので続行します。実際に停止するには setx で設定してください。" -Level WARN
}

function Resolve-OpsServer {
    <#
    .SYNOPSIS -Target で渡された名前から Servers のエントリを引く。
    .DESCRIPTION
        大文字小文字とキー名/Name のどちらでも引けるようにする
        (ops-config.psd1 のキーは Main だが、利用者が打つのは main)。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [hashtable] $Config,
        [Parameter(Mandatory)] [string]    $Target
    )

    foreach ($name in @($Config.Servers.Keys)) {
        $server = $Config.Servers[$name]
        if ($name -ieq $Target -or $server.Name -ieq $Target) { return $server }
    }

    $known = (@($Config.Servers.Keys) | Sort-Object) -join ", "
    throw "サーバ '$Target' が ops-config.psd1 にありません。定義済み: $known"
}

function ConvertFrom-YamlScalar {
    <#
    .SYNOPSIS yml の 1 行スカラー値を素の文字列へ戻す。
    .DESCRIPTION
        `secret: 'ab''cd'` のような単一引用符文字列を素直に扱うために要る。
        正規表現 "'?([^']*)'?" で済ませると【引用符を含む値でマッチに失敗し】、
        「値が読めない」を「値が違う」と誤判定する（実際に踏んだ）。
        必要なのは単一引用符・二重引用符・裸の 3 形だけなので、YAML パーサは持ち込まない。
    #>
    [CmdletBinding()]
    param([string] $Raw)

    if ($null -eq $Raw) { return $null }
    $value = $Raw.Trim()
    if ($value.Length -ge 2 -and $value.StartsWith("'") -and $value.EndsWith("'")) {
        # 単一引用符内では '' が ' のエスケープ。他のエスケープは無い。
        return $value.Substring(1, $value.Length - 2) -replace "''", "'"
    }
    if ($value.Length -ge 2 -and $value.StartsWith('"') -and $value.EndsWith('"')) {
        return $value.Substring(1, $value.Length - 2) -replace '\\"', '"'
    }
    return $value
}

function ConvertTo-YamlSingleQuoted {
    <#
    .SYNOPSIS 文字列を yml の単一引用符スカラーへ包む。
    #>
    [CmdletBinding()]
    param([string] $Value)

    return "'" + ($Value -replace "'", "''") + "'"
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

function Get-ResourceDatapackPlan {
    <#
    .SYNOPSIS
        資源サーバのデータパック配置計画 (正本 -> 配置先) を組み立てて検証する。
    .DESCRIPTION
        設定が無ければ $null を返す (データパックを使わない構成)。
        Required なのに正本が空/不在なら【この時点で throw する】。
        ワールドを消したあとで気づくと、その週は丸ごとバニラ地形で走ることになる。
    .PARAMETER SkipRequiredCheck
        これから正本を作る側 (install-datapacks.ps1) 用。空でも throw しない。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [hashtable] $Config,
        [Parameter(Mandatory)] [hashtable] $ResourceServer,
        [switch] $SkipRequiredCheck
    )

    if (-not $Config.ContainsKey("ResourceDatapacks") -or -not $Config.ResourceDatapacks) {
        return $null
    }
    $spec = $Config.ResourceDatapacks
    foreach ($key in @("Source", "Destination")) {
        if (-not $spec.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($spec[$key])) {
            throw "ops-config.psd1 の ResourceDatapacks に $key がありません。"
        }
        if ([IO.Path]::IsPathRooted($spec[$key])) {
            throw "ResourceDatapacks.$key は資源サーバルートからの相対パスで書いてください: $($spec[$key])"
        }
    }
    $source = Join-Path $ResourceServer.Root $spec.Source
    $destination = Join-Path $ResourceServer.Root $spec.Destination
    $required = $spec.ContainsKey("Required") -and $spec.Required

    $packs = @()
    if (Test-Path -LiteralPath $source) {
        # データパックは「zip 1本」か「pack.mcmeta を持つフォルダ」のどちらか。両方受ける。
        $packs = @(Get-ChildItem -LiteralPath $source -Force |
            Where-Object { $_.Extension -eq ".zip" -or
                           ($_.PSIsContainer -and (Test-Path -LiteralPath (Join-Path $_.FullName "pack.mcmeta"))) })
    }

    if ($required -and -not $SkipRequiredCheck -and $packs.Count -eq 0) {
        throw "中断: データパックの正本が空です: $source`n" +
              "ResourceDatapacks.Required = `$true のため、ワールドを削除せずにここで止めます。`n" +
              "ops\scripts\install-datapacks.ps1 で配置するか、Required を `$false にしてください。"
    }

    return @{
        Source      = $source
        Destination = $destination
        Packs       = $packs
        Required    = $required
    }
}

function Test-WorldSessionLocked {
    <#
    .SYNOPSIS
        ワールドの session.lock を握られているか (= そのサーバが起動中か) を返す。
    .DESCRIPTION
        RCON パスワードが要らない稼働判定。Minecraft は起動中ずっと
        <world>\session.lock を排他で開いたままにする ("The world is locked by another
        instance of Minecraft" の実体)。開ければ停止中、開けなければ起動中。

        RCON より弱い判定だが、パスワード未設定でも使えるのが利点。
        RCON が使えるならそちらを優先し、これは代替として使うこと。

        session.lock が無い場合は $false (まだワールドが無い = 起動中ではない)。
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)] [string] $WorldDir)

    $lock = Join-Path $WorldDir "session.lock"
    if (-not (Test-Path -LiteralPath $lock)) {
        return $false
    }
    try {
        $stream = [System.IO.File]::Open($lock, 'Open', 'ReadWrite', 'None')
        $stream.Close()
        return $false
    } catch [System.IO.IOException] {
        return $true
    } catch {
        # 権限不足など、握られている以外の理由でも開けないことがある。
        # 判定できない場合は「起動中」に倒す (稼働中の world を触るほうが危険)。
        return $true
    }
}

function Install-ResourceDatapacks {
    <#
    .SYNOPSIS
        正本のデータパックを配置先へ複製する。配置先は毎回作り直す (差分ではなく総入れ替え)。
    .DESCRIPTION
        差分コピーにすると、正本から外したパックが配置先に残り続けて
        「外したはずのパックが効いている」状態になる。原因が config を見ても分からないので、
        毎回まっさらにしてから入れ直す。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [hashtable] $Plan,
        [switch] $DryRun
    )

    if ($Plan.Packs.Count -eq 0) {
        Write-OpsLog "データパックの正本が空なので配置をスキップします: $($Plan.Source)" -Level WARN
        return
    }

    if ($DryRun) {
        Write-OpsLog "$($Plan.Destination) を作り直して $($Plan.Packs.Count) 件を複製する" -Level DRYRUN
        foreach ($pack in $Plan.Packs) {
            Write-OpsLog "  $($pack.Name)" -Level DRYRUN
        }
        return
    }

    if (Test-Path -LiteralPath $Plan.Destination) {
        Remove-DirectorySafely -Path $Plan.Destination
    }
    New-Item -ItemType Directory -Path $Plan.Destination -Force | Out-Null
    foreach ($pack in $Plan.Packs) {
        Copy-Item -LiteralPath $pack.FullName -Destination $Plan.Destination -Recurse -Force
    }
    Write-OpsLog "データパックを配置しました: $($Plan.Packs.Count) 件 -> $($Plan.Destination)"
    foreach ($pack in $Plan.Packs) {
        Write-OpsLog "  $($pack.Name)"
    }
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
