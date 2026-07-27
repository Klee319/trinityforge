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
    game_mode: true
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
        Assert-True ($output -match "game_mode")     "game_mode: true を検出していない"
        Assert-True ($output -match "trinityforge")  "ignored_modifiers の欠落を検出していない"
        # resource 側は config.yml が無い。これは初回起動前の正常な状態なので
        # issue ではなく note として扱われていること。
        Assert-True ($output -match "まだありません") "未生成の config を note にしていない"
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
