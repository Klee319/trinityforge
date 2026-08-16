<#
.SYNOPSIS
    指定したバックエンドのワールドを作り直す (正式開幕・仕切り直し用)。

.DESCRIPTION
    週次の reset-resource.ps1 と違い、【全サーバを止めた状態】で任意のバックエンドを
    対象にする。予告も自動起動もプリジェネもしない。開幕作業の一部として人が順番に叩く。

    やること (対象サーバごと):
      1. データパックの正本を検査する (資源サーバのみ)。
         world を消したあとで不在に気づくと、その開幕は丸ごとバニラ地形で走ることになる
      2. world / world_nether / world_the_end を退避 (既定) または削除する
      3. 座標に縛られた残骸を消す (Chunky のタスク・Ars のソース網・拠点・BlueMap のタイル)
      4. データパックを正本から world\datapacks へ入れ直す (資源サーバのみ)

    既定は【退避】で、削除ではない。<VelocityRoot>\_world-backup-<日時>\<サーバ>\ へ
    Directory.Move で移すだけなので一瞬で終わり、開幕直後に「やっぱり戻したい」が効く。
    同じボリューム内の移動なので追加の空き容量も要らない。-Delete で即削除にできる。

    プレイヤーの個人データ (インベントリ・権限・進行) はこのスクリプトでは【消えない】。
    world\playerdata などは world ごと退避されるが、そもそも【インベントリの正本は
    world の中に無い】。HuskSync がログイン時に Redis (無ければ MariaDB) から流し込むので、
    ワールドを作り直しても持ち物はそのまま戻る。2026-08-16 に「ワールドをリセットしたのに
    アイテムが消えていない」として実際に報告された。
    LuckPerms の権限と TrinityForge の進行 DB も別の場所にある。
    消すなら purge-player-data.ps1 を併用すること。順番と全体の流れは ops\RUNBOOK.md 手順 18。

.PARAMETER Target
    対象サーバ。ops-config.psd1 の Servers のキー名か Name (main / resource / dev)。
    既定は main と resource。

.PARAMETER Apply
    既定は下見 (何も動かさない)。実際に作り直すときだけ付ける。

.PARAMETER Delete
    退避せず即削除する。戻せなくなるので、容量が足りないときだけ。

.PARAMETER SkipDatapacks
    資源サーバのデータパック再配置を行わない。

.EXAMPLE
    .\reset-world.ps1
    .\reset-world.ps1 -Target main -Apply
    .\reset-world.ps1 -Target main,resource -Apply
#>
[CmdletBinding()]
param(
    [string]   $ConfigPath,
    [string[]] $Target = @("main", "resource"),
    [switch]   $Apply,
    [switch]   $Delete,
    [switch]   $SkipDatapacks
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")

# ファイルしか触らないので RCON パスワードは要求しない。稼働判定は session.lock と
# プロセス一覧で行う (パスワード未設定でも安全確認だけは必ず効かせる)。
$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false
$dryRun = -not $Apply
$stamp  = Get-Date -Format "yyyyMMdd_HHmmss"
$backup = Join-Path $config.VelocityRoot "_world-backup-$stamp"

$WORLDS = @("world", "world_nether", "world_the_end")

# world を消したときに一緒に消さないと「消えた座標を指し続ける」もの。
# ops-config.psd1 に WorldResetTargets があればそちらを優先する。
$DEFAULT_LEFTOVER_DIRS = @(
    "plugins\Chunky\tasks"      # 消えたワールドを指し続けるプリジェネのタスク状態
    "bluemap\web\maps"          # 消えた地形のタイル (core.conf の data + storages\file.conf の root)
)
$DEFAULT_LEFTOVER_FILES = @(
    "plugins\ArsPaper\source-network.yml"  # ソース網のブロック座標 (SourceNetwork#saveSnapshot)
    "plugins\SetHome\homes.yml"            # 消えた地形の拠点
)

if ($dryRun) {
    Write-OpsLog "下見モードです。実際に作り直すには -Apply を付けてください。" -Level DRYRUN
}

# -------------------------------------------------------------------------------------------------
#  対象サーバの解決
# -------------------------------------------------------------------------------------------------
$servers = @()
foreach ($name in $Target) {
    $servers += (Resolve-OpsServer -Config $config -Target $name)
}
if ($servers.Count -eq 0) {
    throw "中断: 対象サーバが 1 台もありません。-Target を確認してください。"
}
Write-OpsLog ("対象: " + (($servers | ForEach-Object { $_.Name }) -join ", "))

$resourceName = $null
if ($config.Servers.ContainsKey("Resource")) {
    $resourceName = $config.Servers.Resource.Name
}

# -------------------------------------------------------------------------------------------------
#  安全確認 1: どのバックエンドも起動していないこと
# -------------------------------------------------------------------------------------------------
#  対象サーバだけでなく【全台】を見る。plugins\TrinityForge は 3 台がジャンクションで
#  共有しているので、1 台でも動いていると掃除の途中で書き戻される。
$busy = @()
foreach ($key in @($config.Servers.Keys)) {
    $entry = $config.Servers[$key]
    if (Get-NetTCPConnection -State Listen -LocalPort $entry.RconPort -ErrorAction SilentlyContinue) {
        $busy += "$($entry.Name) (RCON $($entry.RconPort))"
        continue
    }
    foreach ($world in $WORLDS) {
        $dir = Join-Path $entry.Root $world
        if ((Test-Path -LiteralPath $dir) -and (Test-WorldSessionLocked -WorldDir $dir)) {
            $busy += "$($entry.Name) ($world\session.lock を掴んでいる)"
            break
        }
    }
}
$roots = @($config.Servers.Keys | ForEach-Object { $config.Servers[$_].Root })
$alive = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object {
        $line = $_.CommandLine
        if (-not $line) { return $false }
        foreach ($root in $roots) { if ($line -like "*$root*") { return $true } }
        return $false
    })
if ($alive.Count -gt 0) {
    $busy += "java PID " + (($alive | ForEach-Object { $_.ProcessId }) -join ", ")
}

if ($busy.Count -gt 0) {
    throw ("中断: バックエンドが起動しています。先に全サーバを停止してください。`n" +
           ($busy -join "`n") + "`n" +
           "ops\scripts\stop-network.ps1 を使うか、各コンソールで stop と入力する。")
}
Write-OpsLog "バックエンドはすべて停止しています。続行します。"

# -------------------------------------------------------------------------------------------------
#  安全確認 2: 消す相対パスに危険なものが混ざっていないこと
# -------------------------------------------------------------------------------------------------
function Assert-SafeRelativePath {
    param([Parameter(Mandatory)] [string] $Relative)

    $normalized = $Relative.Trim().TrimEnd('\', '/')
    # plugins\TrinityForge はメインの実体を resource / dev がジャンクションで指している。
    # 消した瞬間に全プレイヤーの進行データと全 config が 3 台分まとめて飛ぶ。
    if (@("plugins\TrinityForge", "plugins", "") -contains $normalized) {
        throw "中断: 削除対象に $Relative が含まれています。ops-config.psd1 を修正してください。"
    }
    if ([IO.Path]::IsPathRooted($normalized)) {
        throw "中断: 削除対象はサーバルートからの相対パスで書いてください: $Relative"
    }
    if ($normalized -match '\.\.') {
        throw "中断: 削除対象に .. が含まれています: $Relative"
    }
}

$leftoverDirs  = $DEFAULT_LEFTOVER_DIRS
$leftoverFiles = $DEFAULT_LEFTOVER_FILES
if ($config.ContainsKey("WorldResetTargets") -and $config.WorldResetTargets) {
    $spec = $config.WorldResetTargets
    if ($spec.ContainsKey("Directories")) { $leftoverDirs  = @($spec.Directories) }
    if ($spec.ContainsKey("Files"))       { $leftoverFiles = @($spec.Files) }
    Write-OpsLog "残骸の消去対象は ops-config.psd1 の WorldResetTargets を使います。"
}

foreach ($relative in (@($WORLDS) + @($leftoverDirs) + @($leftoverFiles))) {
    Assert-SafeRelativePath -Relative $relative
}
Write-OpsLog "削除対象の安全確認 OK"

# -------------------------------------------------------------------------------------------------
#  安全確認 3: データパックの正本 (資源サーバのみ)
# -------------------------------------------------------------------------------------------------
#  world を消したあとで「正本が空だった」と分かっても手遅れ。先に検査して、
#  足りなければ 1 バイトも触らずに止まる。
$datapackPlans = @{}
if (-not $SkipDatapacks) {
    foreach ($server in $servers) {
        if ($server.Name -ne $resourceName) { continue }
        $plan = Get-ResourceDatapackPlan -Config $config -ResourceServer $server
        if ($plan) {
            $datapackPlans[$server.Name] = $plan
            Write-OpsLog "データパックの正本を確認: $($plan.Packs.Count) 件 ($($plan.Source))"
        }
    }
} else {
    Write-OpsLog "-SkipDatapacks 指定のためデータパックは扱いません。" -Level WARN
}

# -------------------------------------------------------------------------------------------------
#  ワールドの退避 / 削除
# -------------------------------------------------------------------------------------------------
function Move-WorldToBackup {
    <#
    .SYNOPSIS
        ワールドを退避先へ移す。ジャンクションなら中断する。
    .DESCRIPTION
        Directory.Move は同一ボリュームなら一瞬で終わり、追加の空き容量も要らない。
        ボリュームをまたぐと IOException になるので、そのときは -Delete を促す。
    #>
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Destination
    )

    if (Test-ReparsePoint -Path $Path) {
        throw "中断: $Path はジャンクション/シンボリックリンクです。移すとリンク先が巻き込まれます。"
    }

    $parent = Split-Path $Destination -Parent
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    try {
        [System.IO.Directory]::Move($Path, $Destination)
    } catch [System.IO.IOException] {
        throw ("中断: 退避に失敗しました ($Path -> $Destination): $($_.Exception.Message)`n" +
               "ボリュームをまたぐ移動はできません。退避先を同じドライブにするか -Delete を使ってください。")
    }
}

foreach ($server in $servers) {
    Write-Host ""
    Write-OpsLog "----- $($server.Name) -----"

    if (-not (Test-Path -LiteralPath $server.Root)) {
        Write-OpsLog "ルートがありません。飛ばします: $($server.Root)" -Level WARN
        continue
    }

    foreach ($world in $WORLDS) {
        $path = Join-Path $server.Root $world
        if (-not (Test-Path -LiteralPath $path)) {
            Write-OpsLog "対象なし (存在しない): $path"
            continue
        }

        if ($Delete) {
            Remove-DirectorySafely -Path $path -DryRun:$dryRun
            continue
        }

        $dest = Join-Path (Join-Path $backup $server.Name) $world
        if ($dryRun) {
            $size = Get-DirectorySizeMB -Path $path
            Write-OpsLog "退避する: $path ($size MB) -> $dest" -Level DRYRUN
            continue
        }
        Move-WorldToBackup -Path $path -Destination $dest
        Write-OpsLog "退避しました: $path -> $dest"
    }

    # ---- 座標に縛られた残骸 --------------------------------------------------------------------
    # 消し忘れると「存在しない座標を指すデータ」が残る。エラーにはならず、
    # プリジェネが空振りしたり拠点へ飛べなかったりするだけなので気づきにくい。
    foreach ($relative in $leftoverDirs) {
        Remove-DirectorySafely -Path (Join-Path $server.Root $relative) -DryRun:$dryRun
    }
    foreach ($relative in $leftoverFiles) {
        Remove-FileSafely -Path (Join-Path $server.Root $relative) -DryRun:$dryRun
    }

    # ---- データパックの再配置 ------------------------------------------------------------------
    # world ごと消えているので入れ直す。飛ばすと資源ワールドは【バニラ地形で生成され】、
    # 追加した戦利品プールも namespace ごと当たらなくなる。無警告。
    if ($datapackPlans.ContainsKey($server.Name)) {
        Install-ResourceDatapacks -Plan $datapackPlans[$server.Name] -DryRun:$dryRun
    }
}

Write-Host ""
if ($dryRun) {
    Write-OpsLog "下見だけ行いました。実行するには -Apply を付けてください。" -Level DRYRUN
} elseif ($Delete) {
    Write-OpsLog "ワールドを削除しました。次回起動時に新しい地形が生成されます。"
} else {
    Write-OpsLog "ワールドを退避しました: $backup"
    Write-OpsLog "次回起動時に新しい地形が生成されます。問題なければ退避先を削除してください。"
}
Write-OpsLog "プレイヤーの権限・進行・インベントリは別途 purge-player-data.ps1 で消します (RUNBOOK 手順 18)。"
Write-OpsLog "  ※ インベントリの正本は world の中ではなく HuskSync の Redis / MariaDB です。" -Level WARN
Write-OpsLog "     このスクリプトだけでは、次のログインで持ち物がそのまま戻ります。" -Level WARN
exit 0
