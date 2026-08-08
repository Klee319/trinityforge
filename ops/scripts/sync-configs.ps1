<#
.SYNOPSIS
    ArsPaper の config をメインから資源サーバへ同期し、起動前の整合チェックを行う。

.DESCRIPTION
    TrinityForge の config はディレクトリジャンクションで共有されるので同期不要。
    一方 ArsPaper は ranking_cache.json / source-network.yml というサーバ固有の状態ファイルを
    持つため共有できず、config だけをコピーする。

    ⚠ 2026-08-08 訂正: ここは長らく sourcejars.yml / sourcelinks.yml を「サーバ固有の状態」
    としていたが、その2つは読み取り専用の定義ファイル(容量・階梯・燃料点数)で、全サーバで
    同じであるべきもの。ブロック座標を書いているのは source-network.yml の方。
    取り違えのせいで、上位ソースリンク/上位ジャー/階梯触媒の点数が一度も配備されていなかった。

    さらに、過去に実際に起きた事故を検出する:
      - plugins に同名プラグインの jar が2つある (ArsPaper.jar と ArsPaper-1.0.0.jar が同居して
        "Ambiguous plugin name" で起動不能になった前科がある)
      - コピー後に SHA-256 が一致しない (コピー失敗やファイルロックの見逃し)

    リセット直後の起動前に reset-resource.ps1 から呼ばれる。単体でも実行できる。

.PARAMETER DryRun
    コピーも削除も行わず、実行予定の内容だけを出力する。

.PARAMETER SkipArsPaper
    ArsPaper の同期を飛ばし、整合チェックだけ行う。

.EXAMPLE
    .\sync-configs.ps1 -DryRun
#>
[CmdletBinding()]
param(
    [string] $ConfigPath,
    [switch] $DryRun,
    [switch] $SkipArsPaper
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")

# ファイルを見るだけで RCON は使わないので、パスワードは要求しない。
$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false
$main     = $config.Servers.Main
$resource = $config.Servers.Resource

$issues = New-Object System.Collections.Generic.List[string]

# ---- 1. 同名プラグイン jar の二重配置を検出 ---------------------------------------------------

function Get-PluginBaseName {
    <#
    .SYNOPSIS
        jar のファイル名からバージョン部と重複コピー部を落とし、プラグイン名の当たりを付ける。
    .DESCRIPTION
        "ArsPaper-1.0.0.jar" と "ArsPaper.jar" を同一視したい。厳密には jar 内の
        plugin.yml を読むべきだが、起動前チェックとしてはファイル名で十分に検出できる。
        "Geyser-Spigot (5).jar" のような Windows の重複コピー表記も落とす。
    #>
    param([Parameter(Mandatory)] [string] $FileName)

    $name = [IO.Path]::GetFileNameWithoutExtension($FileName)
    $name = $name -replace '\s*\(\d+\)\s*$', ''          # " (5)"
    $name = $name -replace '[-_]v?\d+(\.\d+)*.*$', ''     # "-1.0.0-SNAPSHOT-all"
    return $name.Trim()
}

function Test-DuplicatePluginJars {
    param(
        [Parameter(Mandatory)] [string] $PluginsDir,
        [Parameter(Mandatory)] [string] $Label
    )

    if (-not (Test-Path -LiteralPath $PluginsDir)) {
        Write-OpsLog "$Label の plugins がありません: $PluginsDir" -Level WARN
        return @()
    }

    $found = @()
    Get-ChildItem -LiteralPath $PluginsDir -Filter "*.jar" -File |
        Group-Object { Get-PluginBaseName $_.Name } |
        Where-Object { $_.Count -gt 1 } |
        ForEach-Object {
            $names = ($_.Group.Name | Sort-Object) -join " / "
            $found += "[$Label] 同名プラグインの jar が $($_.Count) 個あります: $names"
        }
    return $found
}

Write-OpsLog "同名プラグイン jar の二重配置を確認します"
foreach ($server in @($main, $resource)) {
    $pluginsDir = Join-Path $server.Root "plugins"
    foreach ($issue in (Test-DuplicatePluginJars -PluginsDir $pluginsDir -Label $server.Name)) {
        $issues.Add($issue)
    }
}

# ---- 2. TrinityForge がジャンクションであることの確認 -----------------------------------------

$resourceTf = Join-Path $resource.Root "plugins\TrinityForge"
if (Test-Path -LiteralPath $resourceTf) {
    if (Test-ReparsePoint -Path $resourceTf) {
        $target = (Get-Item -LiteralPath $resourceTf -Force).Target
        Write-OpsLog "資源側 plugins\TrinityForge はジャンクションです -> $target"
    } else {
        $issues.Add("[resource] plugins\TrinityForge がジャンクションではなく実ディレクトリです。" +
                    "進行データが共有されず、資源サーバのスキルが別勘定になります。" +
                    "setup-junction.cmd を実行してください。")
    }
} else {
    $issues.Add("[resource] plugins\TrinityForge がありません。setup-junction.cmd を実行してください。")
}

# ---- 3. ArsPaper の config を同期 -------------------------------------------------------------

function Get-FileHashHex {
    param([Parameter(Mandatory)] [string] $Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

if (-not $SkipArsPaper) {
    $sourceDir = Join-Path $main.Root     "plugins\ArsPaper"
    $targetDir = Join-Path $resource.Root "plugins\ArsPaper"

    if (-not (Test-Path -LiteralPath $sourceDir)) {
        $issues.Add("[main] plugins\ArsPaper がありません: $sourceDir")
    } else {
        if (-not (Test-Path -LiteralPath $targetDir)) {
            if ($DryRun) {
                Write-OpsLog "作成する: $targetDir" -Level DRYRUN
            } else {
                New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
            }
        }

        $exclude  = $config.ArsPaperSync.ExcludeFiles
        $patterns = $config.ArsPaperSync.ExcludePatterns

        $files = @(Get-ChildItem -LiteralPath $sourceDir -File | Where-Object {
            $name = $_.Name
            if ($exclude -contains $name) { return $false }
            foreach ($pattern in $patterns) {
                if ($name -like $pattern) { return $false }
            }
            return $true
        })

        Write-OpsLog "ArsPaper config を同期します ($($files.Count) 件)"
        $copied = 0
        $skipped = 0
        foreach ($file in $files) {
            $dest = Join-Path $targetDir $file.Name
            $sourceHash = Get-FileHashHex -Path $file.FullName

            if ((Test-Path -LiteralPath $dest) -and (Get-FileHashHex -Path $dest) -eq $sourceHash) {
                $skipped++
                continue
            }

            if ($DryRun) {
                Write-OpsLog "コピーする: $($file.Name)" -Level DRYRUN
                $copied++
                continue
            }

            Copy-Item -LiteralPath $file.FullName -Destination $dest -Force

            # コピー直後に照合する。ロック中のファイルを黙って取りこぼすと、
            # 片方のサーバだけ Ars アイテムが機能しない状態で起動してしまう。
            $destHash = Get-FileHashHex -Path $dest
            if ($destHash -ne $sourceHash) {
                $issues.Add("[ArsPaper] コピー後のハッシュが一致しません: $($file.Name)`n" +
                            "  source=$sourceHash`n  dest  =$destHash")
            }
            $copied++
        }
        Write-OpsLog "同期完了: コピー $copied 件 / 一致済みスキップ $skipped 件"
    }
}

# ---- 4. 結果 ----------------------------------------------------------------------------------

Write-Host ""
if ($issues.Count -eq 0) {
    Write-OpsLog "整合チェックに問題はありません。"
    exit 0
}

Write-OpsLog "$($issues.Count) 件の問題を検出しました。起動前に解消してください。" -Level WARN
foreach ($issue in $issues) {
    Write-Host "  - $issue"
}
# reset-resource.ps1 はこの終了コードを見て起動を中断する。
exit 1
