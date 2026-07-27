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
