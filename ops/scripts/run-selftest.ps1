<#
.SYNOPSIS
    ops スクリプトの自己テスト。サーバも本番ファイルも一切触らない。

.DESCRIPTION
    本構成で最悪の事故は「資源サーバを掃除したつもりで、ジャンクション経由でメイン側の
    実体 (全プレイヤーの進行データと全 config) を消す」ことである。
    その防護が本当に効くのかを、テンポラリ領域に本物のジャンクションを作って実測する。

    あわせて sync-configs.ps1 の重複 jar 検出と、reset-resource.ps1 の削除対象ガードを
    ダミーのディレクトリと設定ファイルで検証する。

.EXAMPLE
    .\run-selftest.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")
. (Join-Path $PSScriptRoot "lib\DataStore.ps1")
. (Join-Path $PSScriptRoot "lib\Yaml.ps1")
. (Join-Path $PSScriptRoot "lib\Redis.ps1")

$script:Passed = 0
$script:Failed = 0

function Test-Case {
    param(
        [Parameter(Mandatory)] [string]      $Name,
        [Parameter(Mandatory)] [scriptblock] $Body
    )
    try {
        & $Body
        Write-Host "  [PASS] $Name" -ForegroundColor Green
        $script:Passed++
    } catch {
        Write-Host "  [FAIL] $Name" -ForegroundColor Red
        Write-Host "         $($_.Exception.Message)"
        $script:Failed++
    }
}

function Assert-True {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) { throw $Message }
}

function New-Junction {
    param([string] $Link, [string] $Target)
    # mklink /J はディレクトリジャンクションなので管理者権限を要しない。
    $result = cmd.exe /c "mklink /J `"$Link`" `"$Target`"" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "ジャンクションを作成できません: $result"
    }
}

$sandbox = Join-Path ([IO.Path]::GetTempPath()) "tf-ops-selftest-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $sandbox -Force | Out-Null

try {
    Write-Host ""
    Write-Host "=== ジャンクション保護 ===" -ForegroundColor Cyan
    Write-Host "  sandbox: $sandbox"

    # 「メイン側の実体」に相当するディレクトリ。中に大切なファイルを1つ置く。
    $real = Join-Path $sandbox "main\plugins\TrinityForge"
    New-Item -ItemType Directory -Path $real -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $real "player_progression.db") -Value "PRECIOUS" -NoNewline

    # 「資源側」からジャンクションを張る。
    $resourcePlugins = Join-Path $sandbox "resource\plugins"
    New-Item -ItemType Directory -Path $resourcePlugins -Force | Out-Null
    $link = Join-Path $resourcePlugins "TrinityForge"
    New-Junction -Link $link -Target $real

    Test-Case "ジャンクションを Test-ReparsePoint が検出する" {
        Assert-True (Test-ReparsePoint -Path $link) "ジャンクションが検出されなかった"
        Assert-True (-not (Test-ReparsePoint -Path $real)) "実体が誤ってリンク扱いされた"
    }

    Test-Case "Remove-DirectorySafely はジャンクションを削除せず中断する" {
        $threw = $false
        try {
            Remove-DirectorySafely -Path $link
        } catch {
            $threw = $true
        }
        Assert-True $threw "中断しなかった (ジャンクションをそのまま消しに行った)"
        Assert-True (Test-Path -LiteralPath (Join-Path $real "player_progression.db")) `
            "リンク先の実体が消えた"
    }

    Test-Case "配下にジャンクションを含むディレクトリの再帰削除も中断する" {
        # plugins\ を丸ごと消すような誤った呼び出しを止められるか。
        $threw = $false
        try {
            Remove-DirectorySafely -Path $resourcePlugins
        } catch {
            $threw = $true
        }
        Assert-True $threw "中断しなかった (plugins ごと消しに行った)"
        Assert-True (Test-Path -LiteralPath (Join-Path $real "player_progression.db")) `
            "リンク先の実体が消えた"
    }

    Test-Case "-AllowLinkRemoval はリンクだけ外し、リンク先には触れない" {
        Remove-DirectorySafely -Path $link -AllowLinkRemoval
        Assert-True (-not (Test-Path -LiteralPath $link)) "リンクが外れていない"
        Assert-True (Test-Path -LiteralPath (Join-Path $real "player_progression.db")) `
            "リンク先の実体が消えた"
    }

    Test-Case "ジャンクションを含まない普通のディレクトリは削除できる" {
        $plain = Join-Path $sandbox "resource\world"
        New-Item -ItemType Directory -Path (Join-Path $plain "region") -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $plain "level.dat") -Value "x" -NoNewline
        Remove-DirectorySafely -Path $plain
        Assert-True (-not (Test-Path -LiteralPath $plain)) "削除されていない"
    }

    Test-Case "DryRun は何も消さない" {
        $plain = Join-Path $sandbox "resource\world_nether"
        New-Item -ItemType Directory -Path $plain -Force | Out-Null
        Remove-DirectorySafely -Path $plain -DryRun
        Assert-True (Test-Path -LiteralPath $plain) "DryRun なのに削除された"
    }

    # ---- sync-configs の規則 -----------------------------------------------------------------

    Write-Host ""
    Write-Host "=== sync-configs の規則 ===" -ForegroundColor Cyan

    # sync-configs.ps1 の内部関数を、スクリプト本体を実行せずに取り出す。
    # ドットソースすると Get-OpsConfig まで走ってしまうため、AST から関数定義だけを拾う。
    $syncPath = Join-Path $PSScriptRoot "sync-configs.ps1"
    $tokens = $null; $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($syncPath, [ref]$tokens, [ref]$errors)
    $functions = $ast.FindAll(
        { param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true)
    foreach ($function in $functions) {
        . ([scriptblock]::Create($function.Extent.Text))
    }

    Test-Case "jar のバージョン表記と重複コピー表記を落として同一視できる" {
        Assert-True ((Get-PluginBaseName "ArsPaper-1.0.0.jar") -eq "ArsPaper") "バージョン付きが落ちない"
        Assert-True ((Get-PluginBaseName "ArsPaper.jar") -eq "ArsPaper") "素の名前が変わった"
        Assert-True ((Get-PluginBaseName "Geyser-Spigot (5).jar") -eq "Geyser-Spigot") "(5) が落ちない"
        Assert-True ((Get-PluginBaseName "TrinityForge-0.1.0-SNAPSHOT-all.jar") -eq "TrinityForge") `
            "SNAPSHOT 付きが落ちない"
    }

    Test-Case "同名プラグインの jar が2つあれば検出する" {
        $plugins = Join-Path $sandbox "dup\plugins"
        New-Item -ItemType Directory -Path $plugins -Force | Out-Null
        foreach ($name in @("ArsPaper.jar", "ArsPaper-1.0.0.jar", "LuckPerms-Bukkit-5.5.17.jar")) {
            Set-Content -LiteralPath (Join-Path $plugins $name) -Value "x" -NoNewline
        }
        $issues = @(Test-DuplicatePluginJars -PluginsDir $plugins -Label "dup")
        Assert-True ($issues.Count -eq 1) "検出件数が想定と違う: $($issues.Count)"
        Assert-True ($issues[0] -match "ArsPaper") "ArsPaper が挙がっていない"
    }

    Test-Case "単一 jar しかなければ何も検出しない" {
        $plugins = Join-Path $sandbox "nodup\plugins"
        New-Item -ItemType Directory -Path $plugins -Force | Out-Null
        foreach ($name in @("ArsPaper-1.0.0.jar", "LuckPerms-Bukkit-5.5.17.jar")) {
            Set-Content -LiteralPath (Join-Path $plugins $name) -Value "x" -NoNewline
        }
        $issues = @(Test-DuplicatePluginJars -PluginsDir $plugins -Label "nodup")
        Assert-True ($issues.Count -eq 0) "誤検出した: $($issues -join '; ')"
    }

    # ---- reset-resource の削除対象ガード -------------------------------------------------------

    Write-Host ""
    Write-Host "=== reset-resource の削除対象ガード ===" -ForegroundColor Cyan

    Test-Case "危険な削除対象を設定に書くと reset-resource が起動時点で拒否する" {
        $badConfig = Join-Path $sandbox "bad-ops-config.psd1"
        @'
@{
    Servers = @{
        Main     = @{ Name = "main";     Root = "C:\nope\main";     RconHost = "127.0.0.1"; RconPort = 25575 }
        Resource = @{ Name = "resource"; Root = "C:\nope\resource"; RconHost = "127.0.0.1"; RconPort = 25576 }
    }
    ResourceResetTargets = @{
        Directories = @("world", "plugins\TrinityForge")
        Files = @()
    }
    ArsPaperSync = @{ ExcludeFiles = @(); ExcludePatterns = @() }
    Backup = @{ Root = "C:\nope\backup"; KeepDays = 1; Databases = @(); WslDistro = "Ubuntu" }
}
'@ | Set-Content -LiteralPath $badConfig -Encoding UTF8

        $resetPath = Join-Path $PSScriptRoot "reset-resource.ps1"
        # 子プロセスの stderr をパイプラインへ落とすので、ここだけ Stop を外す。
        # そうしないと「子が正しく中断した」こと自体が親の終了エラーになってしまう。
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -Command @"
`$env:TF_RCON_MAIN_PASSWORD='x'; `$env:TF_RCON_RESOURCE_PASSWORD='y'
& '$resetPath' -ConfigPath '$badConfig' -DryRun
"@ 2>&1 | Out-String
        } finally {
            $ErrorActionPreference = $previous
        }

        Assert-True ($output -match "TrinityForge") "TrinityForge を名指しで拒否していない"
        Assert-True ($output -match "中断") "中断していない"
    }

    # ---- 週次リセットとデータパックの同居 ------------------------------------------------------
    # world を丸ごと消すリセットと、world\datapacks に置くデータパックは正面衝突する。
    # 衝突しても【エラーは出ず、資源ワールドが黙ってバニラ地形に戻る】だけなので、
    # 「正本が world の外にあること」と「配置が総入れ替えであること」を試験で固定する。

    Write-Host ""
    Write-Host "=== 週次リセットとデータパックの同居 ===" -ForegroundColor Cyan

    $datapackServer = @{ Name = "resource"; Root = (Join-Path $sandbox "dp-resource") }
    $datapackConfig = @{
        ResourceDatapacks = @{
            Source      = "datapacks-source"
            Destination = "world\datapacks"
            Required    = $true
        }
    }

    Test-Case "正本が空なら、ワールドを消す前に Required で止まる" {
        New-Item -ItemType Directory -Path (Join-Path $datapackServer.Root "datapacks-source") -Force | Out-Null
        $failed = $false
        try {
            Get-ResourceDatapackPlan -Config $datapackConfig -ResourceServer $datapackServer | Out-Null
        } catch {
            $failed = $true
            Assert-True ($_.Exception.Message -match "中断") "中断メッセージになっていない: $($_.Exception.Message)"
        }
        Assert-True $failed "正本が空なのに素通りした (この状態で world を消すと1週間バニラ地形になる)"
    }

    Test-Case "正本は world の外に置く (リセットの削除対象と重ならない)" {
        $plan = $datapackConfig.ResourceDatapacks
        # ResourceResetTargets は "world" を消す。Source がその配下だと初回リセットで消える。
        Assert-True (-not ($plan.Source -match '^world[\\/]')) `
            "Source が world 配下にある: $($plan.Source)"
        Assert-True ($plan.Destination -match '^world[\\/]') `
            "Destination は Paper が読む world 配下でなければならない: $($plan.Destination)"
    }

    Test-Case "配置は総入れ替え (正本から外したパックが配置先に残らない)" {
        $root = Join-Path $sandbox "dp-swap"
        $source = Join-Path $root "datapacks-source"
        $dest   = Join-Path $root "world\datapacks"
        New-Item -ItemType Directory -Path $source -Force | Out-Null
        New-Item -ItemType Directory -Path $dest -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $source "keep.zip")  -Value "x" -NoNewline
        # 前の週まで入っていて、正本からは外したパック。差分コピーだと残り続けて効き続ける。
        Set-Content -LiteralPath (Join-Path $dest "dropped.zip") -Value "x" -NoNewline

        $plan = Get-ResourceDatapackPlan `
            -Config @{ ResourceDatapacks = @{ Source = "datapacks-source"; Destination = "world\datapacks" } } `
            -ResourceServer @{ Name = "resource"; Root = $root }
        Install-ResourceDatapacks -Plan $plan

        $names = @(Get-ChildItem -LiteralPath $dest | Select-Object -ExpandProperty Name)
        Assert-True ($names -contains "keep.zip") "正本のパックが配置されていない: $($names -join ', ')"
        Assert-True (-not ($names -contains "dropped.zip")) `
            "正本から外したパックが残っている: $($names -join ', ')"
    }

    Test-Case "session.lock で RCON 無しでも稼働中か判定できる" {
        # RCON パスワードが未設定でも配備できるようにするための判定。
        # ここが誤って「停止中」を返すと、稼働中の world を書き換えてしまう。
        $world = Join-Path $sandbox "lockcheck\world"
        New-Item -ItemType Directory -Path $world -Force | Out-Null

        Assert-True (-not (Test-WorldSessionLocked -WorldDir $world)) `
            "session.lock が無いのに稼働中と判定した"

        $lock = Join-Path $world "session.lock"
        Set-Content -LiteralPath $lock -Value "x" -NoNewline
        Assert-True (-not (Test-WorldSessionLocked -WorldDir $world)) `
            "誰も握っていない session.lock を稼働中と判定した"

        # Minecraft が起動中に握っているのと同じ排他で開く。
        $stream = [System.IO.File]::Open($lock, 'Open', 'ReadWrite', 'None')
        try {
            Assert-True (Test-WorldSessionLocked -WorldDir $world) `
                "握られている session.lock を停止中と判定した"
        } finally {
            $stream.Close()
        }

        Assert-True (-not (Test-WorldSessionLocked -WorldDir $world)) `
            "解放後も稼働中のままになっている"
    }

    # ---- 設定の読み込み ------------------------------------------------------------------------

    Write-Host ""
    Write-Host "=== 設定の読み込み ===" -ForegroundColor Cyan

    # Main / Resource 以外のサーバ (Dev) を足しても扱えることを確認する。
    $threeServerConfig = Join-Path $sandbox "three-ops-config.psd1"
    @'
@{
    VelocityRoot = "C:\nope\velocity"
    Servers = @{
        Main     = @{ Name = "main";     Root = "C:\nope\main";     RconHost = "127.0.0.1"; RconPort = 25586 }
        Resource = @{ Name = "resource"; Root = "C:\nope\resource"; RconHost = "127.0.0.1"; RconPort = 25587 }
        Dev      = @{ Name = "dev";      Root = "C:\nope\dev";      RconHost = "127.0.0.1"; RconPort = 25588 }
    }
    ResourceResetTargets = @{ Directories = @("world"); Files = @() }
    ArsPaperSync = @{ ExcludeFiles = @(); ExcludePatterns = @() }
    Backup = @{ Root = "C:\nope\backup"; KeepDays = 1; Databases = @(); WslDistro = "Ubuntu" }
}
'@ | Set-Content -LiteralPath $threeServerConfig -Encoding UTF8

    Test-Case "Servers のキーごとに RCON パスワードの環境変数を読む" {
        $env:TF_RCON_MAIN_PASSWORD     = "m"
        $env:TF_RCON_RESOURCE_PASSWORD = "r"
        $env:TF_RCON_DEV_PASSWORD      = "d"
        try {
            $loaded = Get-OpsConfig -Path $threeServerConfig
            Assert-True ($loaded.Servers.Main.RconPassword -eq "m")     "main のパスワードが入らない"
            Assert-True ($loaded.Servers.Dev.RconPassword  -eq "d")     "dev のパスワードが入らない"
        } finally {
            Remove-Item Env:\TF_RCON_MAIN_PASSWORD, Env:\TF_RCON_RESOURCE_PASSWORD,
                Env:\TF_RCON_DEV_PASSWORD -ErrorAction SilentlyContinue
        }
    }

    Test-Case "追加サーバのパスワード未設定も既定では失敗させる" {
        $env:TF_RCON_MAIN_PASSWORD     = "m"
        $env:TF_RCON_RESOURCE_PASSWORD = "r"
        try {
            $threw = $false
            try { Get-OpsConfig -Path $threeServerConfig } catch { $threw = $true }
            Assert-True $threw "TF_RCON_DEV_PASSWORD 未設定を見逃した"

            # RCON を使わないスクリプト (preflight) は通す。
            $loaded = Get-OpsConfig -Path $threeServerConfig -RequireRconPasswords:$false
            Assert-True ($null -eq $loaded.Servers.Dev.RconPassword) "未設定が null になっていない"
        } finally {
            Remove-Item Env:\TF_RCON_MAIN_PASSWORD, Env:\TF_RCON_RESOURCE_PASSWORD `
                -ErrorAction SilentlyContinue
        }
    }

    Test-Case "Resolve-OpsServer はキー名と Name のどちらでも引ける" {
        $loaded = Get-OpsConfig -Path $threeServerConfig -RequireRconPasswords:$false
        Assert-True ((Resolve-OpsServer -Config $loaded -Target "dev").RconPort  -eq 25588) "dev が引けない"
        Assert-True ((Resolve-OpsServer -Config $loaded -Target "Main").RconPort -eq 25586) "Main が引けない"

        $threw = $false
        try { Resolve-OpsServer -Config $loaded -Target "nosuch" } catch { $threw = $true }
        Assert-True $threw "存在しないサーバ名を通した"
    }

    # ---- データストアの疎通判定 -------------------------------------------------------------------

    Write-Host ""
    Write-Host "=== データストアの疎通判定 ===" -ForegroundColor Cyan

    # 本物の MariaDB / Garnet を立てずに、決め打ちの応答を返す TCP リスナーで
    # RESP の組み立てとハンドシェイクの解釈だけを検証する。
    function Start-FakeServer {
        param([Parameter(Mandatory)] [scriptblock] $Responder)

        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $listener.Start()
        $port = $listener.LocalEndpoint.Port

        # プローブ側は同期的に読み書きするので、応答役は別スレッドで動かす必要がある。
        $worker = [powershell]::Create()
        [void]$worker.AddScript({
            param($listener, $responder)
            $client = $listener.AcceptTcpClient()
            try {
                $stream = $client.GetStream()
                $stream.ReadTimeout = 3000
                & ([scriptblock]::Create($responder)) $stream
            } finally {
                $client.Close()
                $listener.Stop()
            }
        }).AddArgument($listener).AddArgument($Responder.ToString())

        return [pscustomobject]@{
            Port   = $port
            Worker = $worker
            Handle = $worker.BeginInvoke()
        }
    }

    function Stop-FakeServer {
        param([Parameter(Mandatory)] $Server)
        try { [void]$Server.Worker.EndInvoke($Server.Handle) } catch { }
        $Server.Worker.Dispose()
    }

    Test-Case "RESP で PING/INFO を投げて Garnet を識別できる" {
        $server = Start-FakeServer -Responder {
            param($stream)
            $buffer = New-Object byte[] 1024
            # PING -> +PONG
            [void]$stream.Read($buffer, 0, $buffer.Length)
            $pong = [System.Text.Encoding]::UTF8.GetBytes("+PONG`r`n")
            $stream.Write($pong, 0, $pong.Length); $stream.Flush()
            # INFO server -> バルク文字列
            [void]$stream.Read($buffer, 0, $buffer.Length)
            $payload = "# Server`r`ngarnet_version:2.1.0`r`nredis_version:7.2.5`r`n"
            $info = [System.Text.Encoding]::UTF8.GetBytes(
                "`$$([System.Text.Encoding]::UTF8.GetByteCount($payload))`r`n$payload`r`n")
            $stream.Write($info, 0, $info.Length); $stream.Flush()
            Start-Sleep -Milliseconds 400
        }
        try {
            $probe = Test-RedisEndpoint -HostName "127.0.0.1" -Port $server.Port
            Assert-True $probe.SpeaksResp "RESP と判定できていない"
            # Garnet は互換性のため redis_version も返す。先に garnet_version を見ること。
            Assert-True ($probe.Server -eq "Garnet") "Garnet と識別できていない: $($probe.Server)"
            Assert-True ($probe.Version -eq "2.1.0") "バージョンが取れていない: $($probe.Version)"
        } finally { Stop-FakeServer -Server $server }
    }

    Test-Case "パスワード必須の Redis を「生きているが要認証」と判定する" {
        $server = Start-FakeServer -Responder {
            param($stream)
            $buffer = New-Object byte[] 1024
            [void]$stream.Read($buffer, 0, $buffer.Length)
            $err = [System.Text.Encoding]::UTF8.GetBytes(
                "-NOAUTH Authentication required.`r`n")
            $stream.Write($err, 0, $err.Length); $stream.Flush()
            Start-Sleep -Milliseconds 400
        }
        try {
            $probe = Test-RedisEndpoint -HostName "127.0.0.1" -Port $server.Port
            Assert-True $probe.SpeaksResp   "生存判定できていない"
            Assert-True $probe.RequiresAuth "要認証と判定できていない"
        } finally { Stop-FakeServer -Server $server }
    }

    Test-Case "RESP を喋らない相手を取り違えない" {
        $server = Start-FakeServer -Responder {
            param($stream)
            $buffer = New-Object byte[] 1024
            [void]$stream.Read($buffer, 0, $buffer.Length)
            $junk = [System.Text.Encoding]::UTF8.GetBytes("HTTP/1.1 400 Bad Request`r`n`r`n")
            $stream.Write($junk, 0, $junk.Length); $stream.Flush()
            Start-Sleep -Milliseconds 400
        }
        try {
            $probe = Test-RedisEndpoint -HostName "127.0.0.1" -Port $server.Port
            Assert-True $probe.Reachable        "到達自体はできているはず"
            Assert-True (-not $probe.SpeaksResp) "RESP でないものを RESP と判定した"
        } finally { Stop-FakeServer -Server $server }
    }

    Test-Case "MySQL の初期ハンドシェイクからバージョンを読む" {
        $server = Start-FakeServer -Responder {
            param($stream)
            # [3バイト長(LE)][連番][protocol=10][NUL 終端バージョン][以降は省略]
            $version = [System.Text.Encoding]::UTF8.GetBytes("11.4.5-MariaDB")
            $payload = @(10) + $version + @(0) + @(1, 0, 0, 0)
            $header  = @(($payload.Count -band 0xFF),
                         (($payload.Count -shr 8) -band 0xFF),
                         (($payload.Count -shr 16) -band 0xFF), 0)
            $packet = [byte[]]($header + $payload)
            $stream.Write($packet, 0, $packet.Length); $stream.Flush()
            Start-Sleep -Milliseconds 400
        }
        try {
            $probe = Test-MysqlEndpoint -HostName "127.0.0.1" -Port $server.Port
            Assert-True $probe.SpeaksMysql "MySQL プロトコルと判定できていない"
            Assert-True $probe.IsMariaDb   "MariaDB と判定できていない"
            Assert-True ($probe.Version -eq "11.4.5-MariaDB") "バージョンが違う: $($probe.Version)"
        } finally { Stop-FakeServer -Server $server }
    }

    Test-Case "閉じているポートは Reachable=false になる" {
        # 一度開いて即閉じたポートを使う (誰も掴んでいないことが確実)。
        $probeListener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $probeListener.Start()
        $closedPort = $probeListener.LocalEndpoint.Port
        $probeListener.Stop()

        $redis = Test-RedisEndpoint -HostName "127.0.0.1" -Port $closedPort -TimeoutMs 1000
        $mysql = Test-MysqlEndpoint -HostName "127.0.0.1" -Port $closedPort -TimeoutMs 1000
        Assert-True (-not $redis.Reachable) "閉じたポートに到達したことになっている (redis)"
        Assert-True (-not $mysql.Reachable) "閉じたポートに到達したことになっている (mysql)"
    }

    # ---- yml のキー解決 ---------------------------------------------------------------------------

    Write-Host ""
    Write-Host "=== yml のキー解決 ===" -ForegroundColor Cyan

    # HuskSync の config.yml と同じ形。database と redis の両方に
    # credentials.host / credentials.password があるのが要点。
    $sampleYaml = @(
        "language: en-gb"
        "cluster_id: ''"
        "database:"
        "  type: MYSQL"
        "  credentials:"
        "    host: localhost"
        "    port: 3306"
        "    password: pa55w0rd"
        "  create_tables: true"
        "redis:"
        "  credentials:"
        "    host: redis-host"
        "    port: 6379"
        "    password: ''"
        "synchronization:"
        "  mode: LOCKSTEP"
        "  # コメント行は読み飛ばす"
        ""
        "  features:"
        "    location: false"
        "    game_mode: true"
        "  attributes:"
        "    ignored_modifiers:"
        "    - minecraft:effect.*"
    )

    Test-Case "同名キーを親でたどって取り違えない" {
        $dbHost = Find-YamlLineIndex -Lines $sampleYaml -Path @("database", "credentials", "host")
        $rdHost = Find-YamlLineIndex -Lines $sampleYaml -Path @("redis", "credentials", "host")
        Assert-True ($sampleYaml[$dbHost] -eq "    host: localhost")  "database 側の host が違う"
        Assert-True ($sampleYaml[$rdHost] -eq "    host: redis-host") "redis 側の host が違う"

        $dbPassword = Find-YamlLineIndex -Lines $sampleYaml -Path @("database", "credentials", "password")
        $rdPassword = Find-YamlLineIndex -Lines $sampleYaml -Path @("redis", "credentials", "password")
        Assert-True ($sampleYaml[$dbPassword] -eq "    password: pa55w0rd") "database 側の password が違う"
        Assert-True ($dbPassword -ne $rdPassword) "2 つの password を同じ行と見なした"
    }

    Test-Case "空行とコメントを挟んでもブロックをたどれる" {
        $index = Find-YamlLineIndex -Lines $sampleYaml -Path @("synchronization", "features", "game_mode")
        Assert-True ($sampleYaml[$index] -eq "    game_mode: true") "game_mode に届いていない"
    }

    Test-Case "存在しないキーと、親の外にあるキーは -1 を返す" {
        Assert-True ((Find-YamlLineIndex -Lines $sampleYaml -Path @("database", "nosuch")) -eq -1) `
            "無いキーで -1 を返していない"
        # create_tables は database の直下であって credentials の下ではない。
        Assert-True ((Find-YamlLineIndex -Lines $sampleYaml -Path @("database", "credentials", "create_tables")) -eq -1) `
            "親の外のキーを拾った"
        # mode は synchronization の下。トップレベルにはない。
        Assert-True ((Find-YamlLineIndex -Lines $sampleYaml -Path @("mode")) -eq -1) `
            "入れ子のキーをトップレベルで拾った"
    }

    Test-Case "単一引用符スカラーを往復できる" {
        Assert-True ((ConvertFrom-YamlScalar -Raw "'ab''cd'") -eq "ab'cd")   "エスケープを戻せていない"
        Assert-True ((ConvertFrom-YamlScalar -Raw "  plain  ") -eq "plain")  "裸の値を扱えていない"
        Assert-True ((ConvertFrom-YamlScalar -Raw "''") -eq "")              "空文字を扱えていない"
        $round = ConvertFrom-YamlScalar -Raw (ConvertTo-YamlSingleQuoted -Value "p'w`"d")
        Assert-True ($round -eq "p'w`"d") "往復で壊れた: $round"
    }

    # ---- forwarding secret の反映 ---------------------------------------------------------------

    Write-Host ""
    Write-Host "=== forwarding secret の反映 ===" -ForegroundColor Cyan

    Test-Case "enabled と secret だけを書き換え、他の行と main は触らない" {
        $root = Join-Path $sandbox "fwd"
        $velocity = Join-Path $root "velocity"
        New-Item -ItemType Directory -Path $velocity -Force | Out-Null
        # 単一引用符を含む secret でエスケープも同時に確認する。
        $secret = "ab'cd1234"
        Set-Content -LiteralPath (Join-Path $velocity "forwarding.secret") -Value $secret -NoNewline

        # main は既に正しい / resource は未設定 という実環境と同じ状況を作る。
        $paperGlobal = @'
proxies:
  bungee-cord:
    online-mode: true
  proxy-protocol: false
  velocity:
    enabled: PLACEHOLDER_ENABLED
    online-mode: true
    secret: PLACEHOLDER_SECRET
scoreboards:
  save-empty-scoreboard-teams: true
'@
        $roots = @{}
        foreach ($entry in @(
            @{ Key = "main";     Enabled = "true";  Secret = "'ab''cd1234'" }
            @{ Key = "resource"; Enabled = "false"; Secret = "''" }
        )) {
            $serverRoot = Join-Path $root $entry.Key
            New-Item -ItemType Directory -Path (Join-Path $serverRoot "config") -Force | Out-Null
            ($paperGlobal -replace "PLACEHOLDER_ENABLED", $entry.Enabled `
                          -replace "PLACEHOLDER_SECRET", $entry.Secret) |
                Set-Content -LiteralPath (Join-Path $serverRoot "config\paper-global.yml") -Encoding UTF8
            $roots[$entry.Key] = $serverRoot
        }

        $fwdConfig = Join-Path $sandbox "fwd-ops-config.psd1"
        @"
@{
    VelocityRoot = "$velocity"
    Servers = @{
        Main     = @{ Name = "main";     Root = "$($roots['main'])";     RconHost = "127.0.0.1"; RconPort = 25586 }
        Resource = @{ Name = "resource"; Root = "$($roots['resource'])"; RconHost = "127.0.0.1"; RconPort = 25587 }
    }
    ResourceResetTargets = @{ Directories = @("world"); Files = @() }
    ArsPaperSync = @{ ExcludeFiles = @(); ExcludePatterns = @() }
    Backup = @{ Root = "C:\nope"; KeepDays = 1; Databases = @(); WslDistro = "Ubuntu" }
}
"@ | Set-Content -LiteralPath $fwdConfig -Encoding UTF8

        $mainFile = Join-Path $roots['main'] "config\paper-global.yml"
        $mainBefore = Get-Content -LiteralPath $mainFile -Raw

        $scriptPath = Join-Path $PSScriptRoot "apply-velocity-forwarding.ps1"
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass `
                -Command "& '$scriptPath' -ConfigPath '$fwdConfig'" 2>&1 | Out-String
        } finally {
            $ErrorActionPreference = $previous
        }

        Assert-True ($output -notmatch [regex]::Escape($secret)) "secret を画面に出している"

        # 既に正しい main は 1 バイトも変わっていないこと (退避ファイルも作られない)。
        Assert-True ((Get-Content -LiteralPath $mainFile -Raw) -ceq $mainBefore) "main を書き換えた"
        Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $roots['main'] "config") -Filter "*.bak-*").Count -eq 0) `
            "変更がないのに退避ファイルを作った"

        $resourceLines = @(Get-Content -LiteralPath (Join-Path $roots['resource'] "config\paper-global.yml"))
        Assert-True ($resourceLines[5] -eq "    enabled: true")          "enabled が true になっていない"
        Assert-True ($resourceLines[6] -eq "    online-mode: true")      "online-mode を巻き込んだ"
        Assert-True ($resourceLines[7] -eq "    secret: 'ab''cd1234'")   "secret のエスケープが違う: $($resourceLines[7])"
        Assert-True ($resourceLines[2] -eq "    online-mode: true")      "bungee-cord 側を書き換えた"
        Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $roots['resource'] "config") -Filter "*.bak-*").Count -eq 1) `
            "退避ファイルが作られていない"

        # 冪等性: もう一度流しても「変更なし」で退避が増えない。
        $ErrorActionPreference = "Continue"
        try {
            $again = & powershell.exe -NoProfile -ExecutionPolicy Bypass `
                -Command "& '$scriptPath' -ConfigPath '$fwdConfig'" 2>&1 | Out-String
        } finally {
            $ErrorActionPreference = $previous
        }
        Assert-True ($again -match "変更なし") "2 回目で変更なしと判定していない"
        Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $roots['resource'] "config") -Filter "*.bak-*").Count -eq 1) `
            "冪等でない (退避が増えた)"
    }

    # ---- preflight の HuskSync 検査 -------------------------------------------------------------

    Write-Host ""
    Write-Host "=== preflight の HuskSync 検査 ===" -ForegroundColor Cyan

    Test-Case "既定資格情報と危険な features を preflight が名指しで挙げる" {
        # 生成直後の HuskSync config そのままの状態 (既定パスワード / game_mode: true /
        # trinityforge:* 除外なし) を作り、全部拾えるかを確認する。
        $main = Join-Path $sandbox "pf\main"
        $resource = Join-Path $sandbox "pf\resource"
        foreach ($root in @($main, $resource)) {
            New-Item -ItemType Directory -Path (Join-Path $root "plugins\HuskSync") -Force | Out-Null
        }
        @'
database:
  type: MYSQL
  credentials:
    host: localhost
    port: 3306
    database: HuskSync
    username: root
    password: pa55w0rd
synchronization:
  mode: LOCKSTEP
  features:
    inventory: true
    ender_chest: true
    location: false
    game_mode: false
    flight_status: true
    persistent_data: true
    attributes: true
  attributes:
    ignored_modifiers:
    - minecraft:effect.*
'@ | Set-Content -LiteralPath (Join-Path $main "plugins\HuskSync\config.yml") -Encoding UTF8

        $preflightPath = Join-Path $PSScriptRoot "preflight.ps1"
        $pfConfig = Join-Path $sandbox "pf-ops-config.psd1"
        @"
@{
    Servers = @{
        Main     = @{ Name = "main";     Root = "$main";     RconHost = "127.0.0.1"; RconPort = 25586 }
        Resource = @{ Name = "resource"; Root = "$resource"; RconHost = "127.0.0.1"; RconPort = 25587 }
    }
    ResourceResetTargets = @{ Directories = @("world"); Files = @() }
    ArsPaperSync = @{ ExcludeFiles = @(); ExcludePatterns = @() }
    Backup = @{ Root = "C:\nope\backup"; KeepDays = 1; Databases = @(); WslDistro = "Ubuntu" }
}
"@ | Set-Content -LiteralPath $pfConfig -Encoding UTF8

        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass `
                -Command "& '$preflightPath' -ConfigPath '$pfConfig'" 2>&1 | Out-String
        } finally {
            $ErrorActionPreference = $previous
        }

        Assert-True ($output -match "pa55w0rd")      "既定パスワードを検出していない"
        # game_mode: false なのに flight_status だけ true という組み合わせを拾えること。
        # HuskSync の依存は optional なので、この状態だと「サバイバルに戻ったのに飛べる」になる。
        Assert-True ($output -match "flight_status") "flight_status: true を検出していない"
        Assert-True ($output -match "trinityforge")  "ignored_modifiers の欠落を検出していない"
        # resource 側は config.yml が無い。これは初回起動前の正常な状態なので
        # issue ではなく note として扱われていること。
        Assert-True ($output -match "まだありません") "未生成の config を note にしていない"
    }

    # ---- .cmd の ASCII 制約 ---------------------------------------------------------------------
    # ---- reset-world (正式開幕のワールド作り直し) -----------------------------------------------
    #  ここで守りたいのは 2 つ。
    #   (1) world を「消す」のではなく「移す」ので、中身が本当に無傷で残ること
    #       (退避したつもりで空だった、が一番取り返しがつかない)
    #   (2) plugins\TrinityForge のジャンクションに絶対に手を出さないこと

    Write-Host ""
    Write-Host "=== reset-world のワールド作り直し ===" -ForegroundColor Cyan

    function New-ResetWorldSandbox {
        param([Parameter(Mandatory)] [string] $Name)

        $root     = Join-Path $sandbox $Name
        $mainRoot = Join-Path $root "Main_Server"
        New-Item -ItemType Directory -Path (Join-Path $mainRoot "world\region") -Force | Out-Null
        New-Item -ItemType Directory -Path (Join-Path $mainRoot "world_nether") -Force | Out-Null
        New-Item -ItemType Directory -Path (Join-Path $mainRoot "plugins\SetHome") -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $mainRoot "world\region\r.0.0.mca") -Value "chunk" -NoNewline
        Set-Content -LiteralPath (Join-Path $mainRoot "plugins\SetHome\homes.yml") -Value "x" -NoNewline

        # 使っていないポートを割り当てる。実在ポートを書くと、他のサーバが立っている環境で
        # 「稼働中」と誤判定してテストが落ちる。
        $configPath = Join-Path $root "ops-config.psd1"
        @"
@{
    VelocityRoot = "$root"
    Servers = @{
        Main     = @{ Name = "main";     Root = "$mainRoot";  RconHost = "127.0.0.1"; RconPort = 45586 }
        Resource = @{ Name = "resource"; Root = "$root\nope"; RconHost = "127.0.0.1"; RconPort = 45587 }
    }
    ResourceResetTargets = @{ Directories = @("world"); Files = @() }
    WorldResetTargets = @{ Directories = @(); Files = @("plugins\SetHome\homes.yml") }
    ArsPaperSync = @{ ExcludeFiles = @(); ExcludePatterns = @() }
    Backup = @{ Root = "$root\backup"; KeepDays = 1; Databases = @(); WslDistro = "Ubuntu" }
}
"@ | Set-Content -LiteralPath $configPath -Encoding UTF8

        return @{ Root = $root; MainRoot = $mainRoot; ConfigPath = $configPath }
    }

    Test-Case "ワールドは削除ではなく退避され、中身が無傷で残る" {
        $box = New-ResetWorldSandbox -Name "resetworld-move"
        $script = Join-Path $PSScriptRoot "reset-world.ps1"

        & $script -ConfigPath $box.ConfigPath -Target main -Apply -SkipDatapacks | Out-Null

        Assert-True (-not (Test-Path -LiteralPath (Join-Path $box.MainRoot "world"))) `
            "world が元の場所に残っている"
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $box.MainRoot "plugins\SetHome\homes.yml"))) `
            "座標に縛られた残骸 (homes.yml) が消えていない"

        $backupDirs = @(Get-ChildItem -LiteralPath $box.Root -Directory |
            Where-Object { $_.Name -like "_world-backup-*" })
        Assert-True ($backupDirs.Count -eq 1) "退避先が 1 つ作られていない: $($backupDirs.Count)"

        $moved = Join-Path $backupDirs[0].FullName "main\world\region\r.0.0.mca"
        Assert-True (Test-Path -LiteralPath $moved) "退避先にワールドの中身が無い: $moved"
        Assert-True ((Get-Content -LiteralPath $moved -Raw) -eq "chunk") "退避で中身が壊れた"
    }

    Test-Case "world がジャンクションなら退避せずに中断する" {
        # world をジャンクションにして運用している構成で、リンク先ごと引きずり出さないこと。
        $box  = New-ResetWorldSandbox -Name "resetworld-junction"
        $real = Join-Path $box.Root "real-world"
        New-Item -ItemType Directory -Path $real -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $real "keep.txt") -Value "keep" -NoNewline

        Remove-Item -LiteralPath (Join-Path $box.MainRoot "world") -Recurse -Force
        New-Junction -Link (Join-Path $box.MainRoot "world") -Target $real

        $script = Join-Path $PSScriptRoot "reset-world.ps1"
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -Command @"
& '$script' -ConfigPath '$($box.ConfigPath)' -Target main -Apply -SkipDatapacks
"@ 2>&1 | Out-String
        } finally {
            $ErrorActionPreference = $previous
        }

        Assert-True ($output -match "中断") "中断していない: $output"
        Assert-True (Test-Path -LiteralPath (Join-Path $real "keep.txt")) `
            "リンク先の実体が巻き込まれた"
    }

    Test-Case "WorldResetTargets に plugins\TrinityForge を書くと起動時点で拒否する" {
        $box = New-ResetWorldSandbox -Name "resetworld-guard"
        $bad = Get-Content -LiteralPath $box.ConfigPath -Raw
        $bad = $bad -replace 'WorldResetTargets = @\{ Directories = @\(\);',
                             'WorldResetTargets = @{ Directories = @("plugins\TrinityForge");'
        Set-Content -LiteralPath $box.ConfigPath -Value $bad -Encoding UTF8

        $script = Join-Path $PSScriptRoot "reset-world.ps1"
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -Command @"
& '$script' -ConfigPath '$($box.ConfigPath)' -Target main -Apply -SkipDatapacks
"@ 2>&1 | Out-String
        } finally {
            $ErrorActionPreference = $previous
        }

        Assert-True ($output -match "TrinityForge") "TrinityForge を名指しで拒否していない"
        Assert-True ($output -match "中断") "中断していない"
        Assert-True (Test-Path -LiteralPath (Join-Path $box.MainRoot "world")) `
            "拒否したのに world を触っている"
    }

    # ---- 経済プラグインの配備 --------------------------------------------------------------------
    #  ここで守りたいのは 3 つ。
    #   (1) 稼働中のバックエンドへ jar を置かないこと (NoClassDefFoundError、JVM 再起動以外に復旧なし)
    #   (2) 途中で失敗したときに「jar だけ新しい / config だけ新しい」を作らないこと
    #   (3) Jecon の正本が単体サーバ用 (sqlite / lazyWrite: true) に戻っていないこと
    #       ―― どちらもエラーは出ず、3 台で残高が食い違うという形でしか現れない

    Write-Host ""
    Write-Host "=== 経済プラグインの配備 ===" -ForegroundColor Cyan

    function New-EconomySandbox {
        param([Parameter(Mandatory)] [string] $Name)

        $root  = Join-Path $sandbox $Name
        $roots = @{}
        foreach ($entry in @(
            @{ Key = "Main";     Dir = "Main_Server" }
            @{ Key = "Resource"; Dir = "Resource_Server" }
            @{ Key = "Dev";      Dir = "Dev_Server" }
        )) {
            $serverRoot = Join-Path $root $entry.Dir
            New-Item -ItemType Directory -Path (Join-Path $serverRoot "plugins") -Force | Out-Null
            New-Item -ItemType Directory -Path (Join-Path $serverRoot "world")   -Force | Out-Null
            $roots[$entry.Key] = $serverRoot
        }

        # 配布元。PlaceholderAPI は【置かない】= 任意扱いの経路もここで通る。
        $source = Join-Path $root "source"
        New-Item -ItemType Directory -Path $source -Force | Out-Null
        foreach ($jar in @("VaultUnlocked-2.17.0.jar", "Jecon-2.2.1.jar", "JeconCacheName-1.0.0-SNAPSHOT.jar")) {
            Set-Content -LiteralPath (Join-Path $source $jar) -Value "NEW-$jar" -NoNewline
        }

        # 使っていないポートを割り当てる。実在ポートを書くと、他のサーバが立っている環境で
        # 「稼働中」と誤判定してテストが落ちる。
        $configPath = Join-Path $root "ops-config.psd1"
        @"
@{
    VelocityRoot = "$root"
    Servers = @{
        Main     = @{ Name = "main";     Root = "$($roots['Main'])";     RconHost = "127.0.0.1"; RconPort = 45596 }
        Resource = @{ Name = "resource"; Root = "$($roots['Resource'])"; RconHost = "127.0.0.1"; RconPort = 45597 }
        Dev      = @{ Name = "dev";      Root = "$($roots['Dev'])";      RconHost = "127.0.0.1"; RconPort = 45598 }
    }
    ResourceResetTargets = @{ Directories = @("world"); Files = @() }
    ArsPaperSync = @{ ExcludeFiles = @(); ExcludePatterns = @() }
    Backup = @{ Root = "$root\backup"; KeepDays = 1; Databases = @(); WslDistro = "Ubuntu" }
}
"@ | Set-Content -LiteralPath $configPath -Encoding UTF8

        return @{ Root = $root; Roots = $roots; SourceDir = $source; ConfigPath = $configPath }
    }

    function Invoke-EconomyInstall {
        param(
            [Parameter(Mandatory)] $Box,
            [string] $TemplatePath
        )

        $installer = Join-Path $PSScriptRoot "install-economy-plugins.ps1"
        $line = "& '$installer' -ConfigPath '$($Box.ConfigPath)' -SourceDir '$($Box.SourceDir)'"
        if ($TemplatePath) { $line += " -TemplatePath '$TemplatePath'" }

        # 子プロセスの stderr をパイプラインへ落とすので、ここだけ Stop を外す。
        # そうしないと「子が正しく中断した」こと自体が親の終了エラーになる。
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            return & powershell.exe -NoProfile -ExecutionPolicy Bypass -Command @"
`$env:TF_JECON_DB_PASSWORD='s3cr3t-for-selftest'
$line
"@ 2>&1 | Out-String
        } finally {
            $ErrorActionPreference = $previous
        }
    }

    function Get-EconomyJarNames {
        param([Parameter(Mandatory)] $Box)

        $names = @()
        foreach ($key in @("Main", "Resource", "Dev")) {
            $dir = Join-Path $Box.Roots[$key] "plugins"
            $names += @(Get-ChildItem -LiteralPath $dir -Filter "*.jar" -File |
                ForEach-Object { "$key/$($_.Name)" })
        }
        return $names
    }

    Test-Case "Jecon の正本は 3 台共有の前提を満たし、パスワードの実値を持たない" {
        # このリポジトリは public。正本に実パスワードが入った時点で公開事故になる。
        $master = Join-Path (Split-Path $PSScriptRoot -Parent) "templates\jecon.config.yml"
        Assert-True (Test-Path -LiteralPath $master) "正本がありません: $master"

        $text = [System.IO.File]::ReadAllText($master)
        Assert-True ($text.Contains('password: "__SET_ME__"')) "パスワードがプレースホルダになっていない"
        Assert-True ($text.Contains("type: mysql")) "sqlite に戻っている (サーバごとに別の財布になる)"
        Assert-True ($text.Contains("lazyWrite: false")) "lazyWrite: false が無い"
        Assert-True (-not ($text -match "(?m)^\s*lazyWrite:\s*true")) "lazyWrite: true が残っている"
    }

    Test-Case "停止中なら 3 台へ jar と config が配られ、プレースホルダが残らない" {
        $box = New-EconomySandbox -Name "economy-deploy"
        $output = Invoke-EconomyInstall -Box $box
        Assert-True ($output -notmatch "中断") "中断した: $output"

        $names = @(Get-EconomyJarNames -Box $box)
        Assert-True ($names.Count -eq 9) "3 台 x 3 本にならない: $($names -join ', ')"

        foreach ($key in @("Main", "Resource", "Dev")) {
            $file = Join-Path $box.Roots[$key] "plugins\Jecon\config.yml"
            Assert-True (Test-Path -LiteralPath $file) "$key に config.yml が無い"
            $text = [System.IO.File]::ReadAllText($file)
            Assert-True (-not $text.Contains("__SET_ME__")) "$key にプレースホルダが残っている"
            Assert-True ($text.Contains("password: 's3cr3t-for-selftest'")) "$key にパスワードが入っていない"
            Assert-True ($text.Contains("lazyWrite: false")) "$key の lazyWrite が false でない"
        }

        # BOM を付けない。yml 途中に来た BOM は SnakeYAML が文書境界と誤読し、
        # Bukkit 経由では NPE にしか見えない (過去に回帰 38 件を自作した)。
        $bytes = [System.IO.File]::ReadAllBytes((Join-Path $box.Roots["Main"] "plugins\Jecon\config.yml"))
        Assert-True (-not ($bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)) `
            "config.yml に BOM が付いている"

        # PlaceholderAPI はサンドボックスに置いていない。任意扱いで、警告だけ出して続行すること。
        Assert-True ($output -match "PlaceholderAPI") "PAPI の不足を知らせていない"
    }

    Test-Case "稼働中のバックエンドがあれば jar を 1 本も置かずに中断する" {
        $box  = New-EconomySandbox -Name "economy-running"
        $lock = Join-Path $box.Roots["Main"] "world\session.lock"
        Set-Content -LiteralPath $lock -Value "x" -NoNewline

        # Minecraft が起動中に握っているのと同じ排他で開く。
        $stream = [System.IO.File]::Open($lock, 'Open', 'ReadWrite', 'None')
        try {
            $output = Invoke-EconomyInstall -Box $box
        } finally {
            $stream.Close()
        }

        Assert-True ($output -match "中断") "中断していない: $output"
        $names = @(Get-EconomyJarNames -Box $box)
        Assert-True ($names.Count -eq 0) "稼働中なのに jar を置いた: $($names -join ', ')"
    }

    Test-Case "既存の jar と config は退避してから上書きされる" {
        $box = New-EconomySandbox -Name "economy-backup"
        $mainPlugins = Join-Path $box.Roots["Main"] "plugins"
        Set-Content -LiteralPath (Join-Path $mainPlugins "Jecon-2.2.1.jar") -Value "OLD-JAR" -NoNewline
        New-Item -ItemType Directory -Path (Join-Path $mainPlugins "Jecon") -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $mainPlugins "Jecon\config.yml") -Value "OLD-CONFIG" -NoNewline

        $output = Invoke-EconomyInstall -Box $box
        Assert-True ($output -notmatch "中断") "中断した: $output"

        $backups = @(Get-ChildItem -LiteralPath (Join-Path $mainPlugins ".economy-backups") -Directory)
        Assert-True ($backups.Count -eq 1) "退避先が 1 つ作られていない: $($backups.Count)"
        Assert-True ((Get-Content -LiteralPath (Join-Path $backups[0].FullName "Jecon-2.2.1.jar") -Raw) -eq "OLD-JAR") `
            "旧 jar が退避されていない"
        Assert-True ((Get-Content -LiteralPath (Join-Path $backups[0].FullName "config.yml") -Raw) -eq "OLD-CONFIG") `
            "旧 config が退避されていない"
        Assert-True ((Get-Content -LiteralPath (Join-Path $mainPlugins "Jecon-2.2.1.jar") -Raw) -ne "OLD-JAR") `
            "新しい jar で上書きされていない"
    }

    Test-Case "必須の jar が 1 本でも欠けたら 1 本も配らない" {
        $box = New-EconomySandbox -Name "economy-missing"
        Remove-Item -LiteralPath (Join-Path $box.SourceDir "Jecon-2.2.1.jar") -Force

        $output = Invoke-EconomyInstall -Box $box
        Assert-True ($output -match "中断") "中断していない: $output"
        $names = @(Get-EconomyJarNames -Box $box)
        Assert-True ($names.Count -eq 0) "欠けているのに配った: $($names -join ', ')"
    }

    Test-Case "同名プラグインの jar が二重配置になるなら配らない" {
        # ArsPaper.jar と ArsPaper-1.0.0.jar が同居して Ambiguous plugin name で
        # 起動不能になった前科がある。配ってから気づくと 3 台とも上がらない。
        $box = New-EconomySandbox -Name "economy-ambiguous"
        Set-Content -LiteralPath (Join-Path $box.Roots["Main"] "plugins\Jecon.jar") -Value "x" -NoNewline

        $output = Invoke-EconomyInstall -Box $box
        Assert-True ($output -match "中断") "中断していない: $output"
        $names = @(Get-EconomyJarNames -Box $box)
        Assert-True (-not ($names -match "VaultUnlocked")) "衝突しているのに配った: $($names -join ', ')"
    }

    Test-Case "正本が sqlite / lazyWrite: true に戻っていたら配らない" {
        $box = New-EconomySandbox -Name "economy-badtemplate"
        $bad = Join-Path $box.Root "bad-jecon.yml"
        @'
lazyWrite: true
database:
  type: sqlite
  mysql:
    password: "__SET_ME__"
'@ | Set-Content -LiteralPath $bad -Encoding UTF8

        $output = Invoke-EconomyInstall -Box $box -TemplatePath $bad
        Assert-True ($output -match "中断") "中断していない: $output"
        $names = @(Get-EconomyJarNames -Box $box)
        Assert-True ($names.Count -eq 0) "壊れた正本なのに配った: $($names -join ', ')"
    }

    # ---------------------------------------------------------------------------------------------
    #  HuskSync の Redis キャッシュを消す経路 (lib\Redis.ps1)
    # ---------------------------------------------------------------------------------------------
    #  2026-08-16: ワールドを作り直し、SQL で husksync_user_data も空にしたのに、
    #  Redis に 8 人分の latest_snapshot が残っていて全員のインベントリが復活した。
    #  HuskSync はログイン時に Redis を先に見て、あればそれを適用して DB を読まないため。
    #
    #  ここで固定するのは【値を取りこぼさずに読めること】。RESP の bulk string を
    #  「1 回 Read すれば全部来る」「行区切りで読める」と書くと、
    #  10〜30KB でバイナリの中に CRLF が入るスナップショットで必ず壊れる。
    #  しかも壊れ方が「途中まで読めた」なので、消し漏れに気づけない。

    Write-Host ""
    Write-Host "=== Redis クライアント (RESP) ===" -ForegroundColor Cyan

    Add-Type -TypeDefinition @"
using System;
using System.IO;

// 1 回の Read で ChunkSize バイトしか返さないストリーム。
// ネットワークが値を分割して届ける状況を、待ち時間なしで再現する。
public class TfChunkedStream : Stream {
    private readonly byte[] _data;
    private readonly int _chunk;
    private int _position;

    public TfChunkedStream(byte[] data, int chunkSize) { _data = data; _chunk = chunkSize; }

    public override int Read(byte[] buffer, int offset, int count) {
        int remaining = _data.Length - _position;
        if (remaining <= 0) { return 0; }
        int n = Math.Min(Math.Min(count, _chunk), remaining);
        Array.Copy(_data, _position, buffer, offset, n);
        _position += n;
        return n;
    }

    // 書き込みは捨てる。偽の接続として Invoke-RedisCommand にも使えるようにするため。
    public override void Write(byte[] buffer, int offset, int count) { }

    public override bool CanRead { get { return true; } }
    public override bool CanSeek { get { return false; } }
    public override bool CanWrite { get { return true; } }
    public override long Length { get { return _data.Length; } }
    public override long Position {
        get { return _position; }
        set { throw new NotSupportedException(); }
    }
    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) { throw new NotSupportedException(); }
    public override void SetLength(long value) { throw new NotSupportedException(); }
}
"@ -ErrorAction SilentlyContinue

    function New-RespBulk {
        <#
        .SYNOPSIS 生バイト列を RESP の bulk string にする (長さは実バイト数)。
        #>
        param([Parameter(Mandatory)] [byte[]] $Payload)

        $out = New-Object System.IO.MemoryStream
        $head = [System.Text.Encoding]::ASCII.GetBytes("`$$($Payload.Length)`r`n")
        $out.Write($head, 0, $head.Length)
        $out.Write($Payload, 0, $Payload.Length)
        $tail = [System.Text.Encoding]::ASCII.GetBytes("`r`n")
        $out.Write($tail, 0, $tail.Length)
        return $out.ToArray()
    }

    Test-Case "bulk string の中に CRLF があっても宣言長ぶんを正確に読む" {
        # HuskSync のスナップショットは JSON/バイナリで、CRLF がそのまま入りうる。
        # 行区切りで読む実装はここで値を途中で切る。
        $payload = [System.Text.Encoding]::UTF8.GetBytes("{`"a`":1}`r`n{`"b`":2}`r`ntail")
        $stream  = New-Object System.IO.MemoryStream (,(New-RespBulk -Payload $payload))
        $value   = Read-RedisReply -Stream $stream

        Assert-True ($value -is [byte[]]) "bulk が byte[] で返っていない"
        Assert-True ($value.Length -eq $payload.Length) `
            "長さが違う: 期待 $($payload.Length) / 実際 $($value.Length)"
        Assert-True ((ConvertFrom-RedisBulk -Value $value) -eq
                     [System.Text.Encoding]::UTF8.GetString($payload)) "中身が一致しない"
    }

    Test-Case "1 回の Read で全部届かなくても取りこぼさない" {
        # 実測のスナップショットは 10〜30KB。1 回の Read では絶対に届かない。
        $payload = New-Object byte[] 40000
        for ($i = 0; $i -lt $payload.Length; $i++) { $payload[$i] = [byte]($i % 251) }
        $bytes  = New-RespBulk -Payload $payload
        $stream = New-Object -TypeName TfChunkedStream -ArgumentList $bytes, 7
        $value  = Read-RedisReply -Stream $stream

        Assert-True ($value.Length -eq $payload.Length) `
            "分割されると短く読む: 期待 $($payload.Length) / 実際 $($value.Length)"
        Assert-True ($value[39999] -eq $payload[39999]) "末尾のバイトが一致しない"
    }

    Test-Case "SCAN の応答 (カーソル + キー配列) を読める" {
        # 宣言長は実バイト数から組み立てる。手で数えて書くと、ずれた瞬間に
        # 「読めてはいるが 1 つ後ろへ食い込む」壊れ方をしてテスト自体が嘘になる。
        $keys = @("husksync:::latest_snapshot:aaaa", "husksync:::x:b")
        $text = "*2`r`n" + "`$1`r`n0`r`n" + "*$($keys.Count)`r`n"
        foreach ($key in $keys) {
            $text += "`$$([System.Text.Encoding]::UTF8.GetByteCount($key))`r`n$key`r`n"
        }
        $stream = New-Object System.IO.MemoryStream (,[System.Text.Encoding]::UTF8.GetBytes($text))
        $reply  = Read-RedisReply -Stream $stream

        Assert-True ($reply.Count -eq 2) "配列の要素数が 2 でない"
        Assert-True ((ConvertFrom-RedisBulk -Value $reply[0]) -eq "0") "カーソルが読めていない"
        Assert-True ($reply[1].Count -eq 2) "キーの件数が違う"
        Assert-True ((ConvertFrom-RedisBulk -Value $reply[1][0]) -eq "husksync:::latest_snapshot:aaaa") `
            "キーが読めていない"
    }

    Test-Case "エラー応答は握り潰さず例外にする" {
        $stream = New-Object System.IO.MemoryStream (,[System.Text.Encoding]::UTF8.GetBytes("-NOAUTH required`r`n"))
        $threw = $false
        try { [void](Read-RedisReply -Stream $stream) } catch { $threw = $true }
        Assert-True $threw "エラー応答が例外になっていない (掃除できていないのに成功扱いになる)"
    }

    Test-Case "Get-RedisKey の戻り値は @() で包んでも件数が変わらない" {
        # 実際に踏んだ回帰: 戻り値を `,` で包んでいたので @(Get-RedisKey ...) が
        # 【要素 1 個 (中身は 8 件の配列) の配列】になり、8 件あるキーが 1 件に見えた。
        # そのまま消しに行くと、配列を文字列化した存在しないキーを 1 回消すだけで
        # 「掃除できた」ことになる。消し漏れは無警告なので、ここで件数を固定する。
        $keys = @("husksync:::latest_snapshot:aaaa",
                  "husksync:::latest_snapshot:bbbb",
                  "husksync:::server_switch:cccc")
        $text = "*2`r`n`$1`r`n0`r`n*$($keys.Count)`r`n"
        foreach ($key in $keys) {
            $text += "`$$([System.Text.Encoding]::UTF8.GetByteCount($key))`r`n$key`r`n"
        }
        $fake = [pscustomobject]@{
            Stream = New-Object -TypeName TfChunkedStream `
                -ArgumentList ([System.Text.Encoding]::UTF8.GetBytes($text)), 4096
        }

        $wrapped = @(Get-RedisKey -Connection $fake -Pattern "husksync::*")
        Assert-True ($wrapped.Count -eq $keys.Count) `
            "@() で包むと件数が変わる: 期待 $($keys.Count) / 実際 $($wrapped.Count)"
        Assert-True ($wrapped[0] -is [string]) `
            "要素が文字列でない (配列が 1 つ入っている): $($wrapped[0].GetType().Name)"
        Assert-True ($wrapped -contains "husksync:::server_switch:cccc") "キーが欠けている"
    }

    Test-Case "HuskSync の config から redis 側の資格情報を読む (database 側と取り違えない)" {
        # config.yml には database.credentials.host と redis.credentials.host が両方ある。
        # 行を素朴に検索すると MariaDB の資格情報で Redis へ繋ぎに行く。
        $file = Join-Path $sandbox "husksync-config.yml"
        @'
cluster_id: ''
database:
  type: MARIADB
  credentials:
    host: db.example
    port: 3306
    database: husksync
    username: husksync
    password: 'DB-PASSWORD'
redis:
  credentials:
    host: redis.example
    port: 6380
    database: 3
    user: 'ruser'
    password: 'REDIS-PASSWORD'
'@ | Set-Content -LiteralPath $file -Encoding UTF8

        $settings = Get-HuskSyncRedisSettings -ConfigPath $file
        Assert-True ($settings.HostName -eq "redis.example") "host が redis 側でない: $($settings.HostName)"
        Assert-True ($settings.Port -eq 6380)                "port が redis 側でない: $($settings.Port)"
        Assert-True ($settings.Database -eq 3)               "database が redis 側でない: $($settings.Database)"
        Assert-True ($settings.User -eq "ruser")             "user が redis 側でない: $($settings.User)"
        Assert-True ($settings.Password -eq "REDIS-PASSWORD") "password が MariaDB 側になっている"
    }

    Test-Case "cluster_id からキーの照合パターンを組み、実キーの形と一致する" {
        $file = Join-Path $sandbox "husksync-config-empty-cluster.yml"
        @'
cluster_id: ''
redis:
  credentials:
    host: 127.0.0.1
    port: 6379
    database: 0
    user: ''
    password: ''
'@ | Set-Content -LiteralPath $file -Encoding UTF8

        $settings = Get-HuskSyncRedisSettings -ConfigPath $file
        Assert-True ($settings.KeyPattern -eq "husksync::*") "既定 (cluster_id 空) のパターンが違う: $($settings.KeyPattern)"

        # 実サーバで観測したキーの形。パターンの固定部分が接頭辞になっていること。
        $actual = "husksync:::latest_snapshot:34607fc2-f3ce-43f6-83ed-ea179e0fbb3a"
        $prefix = $settings.KeyPattern.TrimEnd('*')
        Assert-True ($actual.StartsWith($prefix)) "実キー $actual を拾えないパターン: $($settings.KeyPattern)"

        $named = Join-Path $sandbox "husksync-config-named-cluster.yml"
        @'
cluster_id: 'prod'
redis:
  credentials:
    host: 127.0.0.1
    port: 6379
'@ | Set-Content -LiteralPath $named -Encoding UTF8
        Assert-True ((Get-HuskSyncRedisSettings -ConfigPath $named).KeyPattern -eq "husksync:prod:*") `
            "cluster_id 付きのパターンが違う"
    }

    Test-Case "purge-player-data は Redis を消す経路を持ち、既定で有効" {
        # SQL だけ流す運用に戻ると、また「消したのに戻る」が起きる。
        $script = Join-Path $PSScriptRoot "purge-player-data.ps1"
        $text   = [System.IO.File]::ReadAllText($script)
        Assert-True ($text -match "Get-HuskSyncRedisSettings") "Redis を消す経路が無い"
        Assert-True ($text -match "\[switch\]\s+\`$SkipRedis") "-SkipRedis が無い (既定で消す形になっていない)"
        Assert-True ($text -notmatch "\`$SkipRedis\s*=\s*\`$true") "-SkipRedis が既定で有効になっている"
    }

    #  cmd.exe は UTF-8 のバッチファイルを正しく読めない。マルチバイト文字があると
    #  ファイル位置の計算がずれ、行の途中から実行を始める。実際に server-loop.cmd が
    #  日本語コメント入りだった間、stop.flag を見ずに空回りする無限ループになっていた
    #  (2026-07-27 に実測)。PowerShell は UTF-8 で問題ないので、説明は .ps1 と .md に置く。

    Write-Host ""
    Write-Host "=== .cmd が ASCII だけであること ===" -ForegroundColor Cyan

    Test-Case "ops 配下の .cmd に非 ASCII が混ざっていない" {
        $opsRoot = Split-Path $PSScriptRoot -Parent
        $offenders = @()
        foreach ($file in @(Get-ChildItem -LiteralPath $opsRoot -Recurse -Filter "*.cmd" -File)) {
            $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
            $count = @($bytes | Where-Object { $_ -gt 0x7f }).Count
            if ($count -gt 0) {
                $offenders += "$($file.FullName.Substring($opsRoot.Length + 1)) ($count バイト)"
            }
        }
        Assert-True ($offenders.Count -eq 0) `
            "非 ASCII を含む .cmd がある (cmd.exe が行の途中から実行する): $($offenders -join ', ')"
    }

} finally {
    # サンドボックス自体にジャンクションが残っている可能性があるため、先にリンクを外してから消す。
    Get-ChildItem -LiteralPath $sandbox -Recurse -Force -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint } |
        ForEach-Object { [System.IO.Directory]::Delete($_.FullName, $false) }
    Remove-Item -LiteralPath $sandbox -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "=== 結果: 成功 $script:Passed / 失敗 $script:Failed ===" `
    -ForegroundColor $(if ($script:Failed -eq 0) { "Green" } else { "Red" })
exit $(if ($script:Failed -eq 0) { 0 } else { 1 })
