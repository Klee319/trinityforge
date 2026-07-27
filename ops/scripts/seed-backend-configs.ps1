<#
.SYNOPSIS
    正本サーバのプラグイン設定を、他のバックエンドへ配る（データは持って行かない）。

.DESCRIPTION
    「dev と main を同じ構成にする」ための初期配布。既に起動して config を生成済みの
    サーバを正本にし、未起動のサーバへ config だけを配る。

    【データは配らない。】プレイヤーデータ・ワールド依存データ・DB ファイルを配ると、
    別サーバの持ち物が本番の初期値として居座る。除外は $DataPaths に列挙する。

    【TrinityForge と HuskSync は扱わない。】
      - TrinityForge は setup-junction.cmd でジャンクション共有する（コピーすると実体が2つになる）
      - HuskSync は apply-husksync-config.ps1 が配る（パスワードを対話で受け取るため）

    プラグインの配置方針は ops/PLUGIN_MATRIX.md。資源サーバへ入れないものは
    $ResourceExcluded に、プロキシへ移設したものは $ProxyOnly に列挙する。

.PARAMETER From
    正本にするサーバ。既定は Dev（唯一 config を生成済みのサーバ）。

.PARAMETER To
    配布先。既定は Main と Resource。

.PARAMETER Overwrite
    配布先に既にあるプラグインディレクトリも上書きする。既定は「無いものだけ」配る
    （運用中に個別調整した値を黙って戻さないため）。

.EXAMPLE
    .\seed-backend-configs.ps1 -DryRun
#>
[CmdletBinding()]
param(
    [string]   $ConfigPath,
    [string]   $From = "Dev",
    [string[]] $To = @("Main", "Resource"),
    [switch]   $Overwrite,
    [switch]   $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Common.ps1")

# 別の仕組みが受け持つので、ここでは触らない。
$script:Managed = @("TrinityForge", "HuskSync")

# プロキシへ移設したもの (PLUGIN_MATRIX.md)。バックエンドには配らない。
$script:ProxyOnly = @("Geyser-Spigot", "GeyserExtra", "ViaVersion", "ViaBackwards",
                      "viaversion", "viabackwards")

# 資源サーバへは入れないもの (PLUGIN_MATRIX.md に理由を明記)。
$script:ResourceExcluded = @("EliteMobs", "DiscordSRV", "Multiverse-Core", "Multiverse-Portals",
                             "WorldGuard", "BlueMap", "bluemap", "Backuper")

# プラグインディレクトリ配下の【データ】。config ではないので配らない。
# キーはプラグインディレクトリ名、値はその配下の相対パス。
$script:DataPaths = @{
    "ArsPaper"          = @("ranking_cache.json", "sourcejars.yml", "sourcelinks.yml")
    "CommandBinderGUI"  = @("playerdata")
    "DiscordSRV"        = @("accounts.aof", "linkedaccounts.json")
    "EliteMobs"         = @("data")
    "LuckPerms"         = @("luckperms-h2-v2.mv.db", "luckperms-h2-v2.trace.db", "libs")
    "Multiverse-Core"   = @("worlds.yml", "anchors.yml")
    "ProtocolLib"       = @("lastupdate")
    "SetHome"           = @("homes.yml")
    "WorldEdit"         = @("sessions")
    "WorldGuard"        = @("worlds", "cache")
    "spark"             = @("tmp")
}

# サーバ固有の識別子が入るので、配ると別サーバと同一視される。
# .paper-remapped は Paper が jar を再マップした結果を貯めるキャッシュ。
# サーバごとに作り直されるものなので配る意味がなく、数百 MB を無駄に増やす。
$script:NeverCopy = @("bStats", ".paper-remapped")

function Test-IsDataPath {
    <# .SYNOPSIS そのファイルが「データ」として除外対象かを判定する。 #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [string] $PluginName,
        [Parameter(Mandatory)] [string] $RelativePath
    )

    if (-not $script:DataPaths.ContainsKey($PluginName)) { return $false }
    foreach ($excluded in $script:DataPaths[$PluginName]) {
        # ディレクトリ指定はその配下すべてを除外する。
        if ($RelativePath -ieq $excluded -or $RelativePath -ilike "$excluded\*") { return $true }
    }
    return $false
}

$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false
$source = Resolve-OpsServer -Config $config -Target $From
$sourcePlugins = Join-Path $source.Root "plugins"

if (-not (Test-Path -LiteralPath $sourcePlugins)) {
    throw "正本の plugins がありません: $sourcePlugins"
}

if ($DryRun) {
    Write-OpsLog "=== DRY RUN: 何も書き込みません ===" -Level DRYRUN
}
Write-OpsLog "正本: $($source.Name) ($sourcePlugins)"

$pluginDirs = @(Get-ChildItem -LiteralPath $sourcePlugins -Directory |
    Where-Object { $script:Managed -notcontains $_.Name -and
                   $script:ProxyOnly -notcontains $_.Name -and
                   $script:NeverCopy -notcontains $_.Name })

Write-OpsLog "配布候補: $($pluginDirs.Count) プラグイン"

$totalCopied  = 0
$totalSkipped = 0

foreach ($targetName in $To) {
    $target = Resolve-OpsServer -Config $config -Target $targetName
    $targetPlugins = Join-Path $target.Root "plugins"

    Write-Host ""
    Write-OpsLog "--- $($target.Name) ---"

    if (-not (Test-Path -LiteralPath $targetPlugins)) {
        if ($DryRun) {
            Write-OpsLog "plugins を作る: $targetPlugins" -Level DRYRUN
        } else {
            New-Item -ItemType Directory -Path $targetPlugins -Force | Out-Null
        }
    }

    # 資源サーバだけ配布対象が狭い。
    $isResource = ($target.Name -ieq "resource")

    foreach ($pluginDir in $pluginDirs) {
        $name = $pluginDir.Name

        if ($isResource -and $script:ResourceExcluded -contains $name) {
            Write-OpsLog "  skip  $name (資源サーバ対象外: PLUGIN_MATRIX.md)"
            continue
        }

        $destination = Join-Path $targetPlugins $name
        if ((Test-Path -LiteralPath $destination) -and -not $Overwrite) {
            Write-OpsLog "  skip  $name (既にあります。上書きするなら -Overwrite)"
            $totalSkipped++
            continue
        }

        # 除外を効かせるため、ファイル単位でコピーする。
        $files = @(Get-ChildItem -LiteralPath $pluginDir.FullName -Recurse -File)
        $copied  = 0
        $skipped = 0

        foreach ($file in $files) {
            $relative = $file.FullName.Substring($pluginDir.FullName.Length + 1)
            if (Test-IsDataPath -PluginName $name -RelativePath $relative) {
                $skipped++
                continue
            }

            $destinationFile = Join-Path $destination $relative
            if (-not $DryRun) {
                $parent = Split-Path $destinationFile -Parent
                if (-not (Test-Path -LiteralPath $parent)) {
                    New-Item -ItemType Directory -Path $parent -Force | Out-Null
                }
                Copy-Item -LiteralPath $file.FullName -Destination $destinationFile -Force
            }
            $copied++
        }

        $suffix = if ($skipped -gt 0) { " / データ $skipped 件を除外" } else { "" }
        Write-OpsLog "  copy  $name ($copied 件$suffix)" -Level $(if ($DryRun) { "DRYRUN" } else { "INFO" })
        $totalCopied++
    }
}

Write-Host ""
Write-OpsLog "配布 $totalCopied / 既存のためスキップ $totalSkipped"
Write-OpsLog "TrinityForge は setup-junction.cmd、HuskSync は apply-husksync-config.ps1 が担当します。"
exit 0
