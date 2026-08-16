<#
.SYNOPSIS
    3 バックエンドに残っているプレイヤー個人データと権限を全消去し、まっさらな状態から始める。

.DESCRIPTION
    dev サーバでのテストプレイで溜まったデータを、権限も含めて消す。
    main / resource は「dev と同じ構成」で運用するため、同じ対象を同じ手順で消す。

    消すもの (各サーバのルートからの相対パス):
      world*/playerdata, world*/stats, world*/advancements   … インベントリ/実績/統計
      usercache.json                                         … 名前↔UUID キャッシュ
      ops.json                                               … -KeepOps 以外を削除【権限】
      plugins/LuckPerms/luckperms-h2-v2.mv.db (+ .trace.db)  … 旧 H2 の権限データ【権限】
                                                                (現行は MariaDB。下の SQL 側が本体)
      plugins/CommandBinderGUI/playerdata                    … コマンドバインド
      plugins/EliteMobs/data/player_data.db                  … EM のギルドランク/通貨
      plugins/ArsPaper/ranking_cache.json                    … Ars のランキング
      plugins/SetHome/homes.yml                              … 拠点
      plugins/DiscordSRV/accounts.aof, linkedaccounts.json   … Discord 連携

    加えて 1 回だけ:
      Main_Server/plugins/TrinityForge/player_progression.db … スキルLv/SP/パーク
      ※ 3 サーバでジャンクション共有している実体。消すと 3 サーバ全部から消える。
         -KeepProgression を付けると残す。

    MariaDB の luckperms / husksync は資格情報が要るのでこのスクリプトからは消さない。
    実行の最後に、そのまま流せる SQL を出力する。

    ワールドの地形・plugins の config・jar には一切触れない。

.PARAMETER Apply
    既定は下見 (何も消さない)。実際に消すときだけ付ける。

.PARAMETER KeepOps
    ops.json に残す MCID。既定は Klee319 のみ。
    Bedrock のアカウントは先頭のドット込みで指定する (例: ".Klee3192821")。

.PARAMETER KeepProgression
    TrinityForge の進行データ (player_progression.db) を消さずに残す。

.PARAMETER EnableDevWhitelist
    Dev_Server を -KeepOps のメンバーだけに絞る (server.properties の white-list=true と
    whitelist.json の書き換え)。既定で有効。-EnableDevWhitelist:$false で無効。

.PARAMETER PurgeGroups
    LuckPerms の【グループ定義そのもの】(luckperms_groups / luckperms_group_permissions /
    luckperms_tracks) も消す。既定では消さない。

    既定で残す理由: プレイヤーの所属は luckperms_user_permissions の group.<名前> ノードなので、
    そちらを空にすれば「誰も何のグループにも属していない」状態になる。グループ定義まで消すと
    default / member / admin の権限設計を /lp で 1 から作り直すことになり、
    作り直しの過程で権限が緩いまま開幕する事故が起きやすい。

.EXAMPLE
    .\purge-player-data.ps1
    .\purge-player-data.ps1 -Apply
    .\purge-player-data.ps1 -Apply -PurgeGroups
#>
[CmdletBinding()]
param(
    [string]   $ConfigPath,
    [switch]   $Apply,
    [string[]] $KeepOps = @("Klee319"),
    [switch]   $KeepProgression,
    [switch]   $PurgeGroups,
    [bool]     $EnableDevWhitelist = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")

# ファイルしか触らないので RCON パスワードは要求しない。
$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false
$dryRun = -not $Apply
$stamp  = Get-Date -Format "yyyyMMdd_HHmmss"
$backup = Join-Path $config.VelocityRoot "_purge-backup-$stamp"

if ($dryRun) {
    Write-OpsLog "下見モードです。実際に消すには -Apply を付けてください。" -Level DRYRUN
}

# -------------------------------------------------------------------------------------------------
#  安全確認: サーバが動いていたら中断する
# -------------------------------------------------------------------------------------------------
#  起動中に消しても、停止時に Paper がメモリ上の内容を書き戻し、HuskSync が DB から復元するので
#  結局消えない。中途半端に消えた状態が一番たちが悪いので、ここで止める。
$busy = @()
foreach ($name in @($config.Servers.Keys)) {
    $port = $config.Servers[$name].RconPort
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        $busy += "$($config.Servers[$name].Name) (RCON $port)"
    }
}
# RCON を無効にしている場合に備えて java プロセスでも確認する。
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
           "各コンソールで stop と入力するか、RCON パスワード設定済みなら stop-network.ps1 を使う。")
}
Write-OpsLog "バックエンドはすべて停止しています。続行します。"

# -------------------------------------------------------------------------------------------------
#  バックアップ (消す前の内容を丸ごと退避しておく)
# -------------------------------------------------------------------------------------------------
function Save-Backup {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Label
    )

    if ($dryRun) { return }
    if (-not (Test-Path -LiteralPath $Path)) { return }

    $dest   = Join-Path $backup $Label
    $parent = Split-Path $dest -Parent
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    Copy-Item -LiteralPath $Path -Destination $dest -Recurse -Force
}

function Write-TextNoBom {
    <#
    .SYNOPSIS BOM 無し UTF-8 で書く。
    .DESCRIPTION
        Windows PowerShell 5.1 の Set-Content -Encoding UTF8 は BOM を付ける。
        ops.json / whitelist.json は Minecraft 側が Gson で読むため、BOM があると
        パースに失敗して「op が全部消えた」ように見える。server.properties も同様に付けない。
    #>
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [AllowEmptyString()] [string] $Text
    )

    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

# -------------------------------------------------------------------------------------------------
#  消す対象の一覧
# -------------------------------------------------------------------------------------------------
$worlds      = @("world", "world_nether", "world_the_end")
$worldSubs   = @("playerdata", "stats", "advancements")
$pluginDirs  = @("plugins\CommandBinderGUI\playerdata")
$pluginFiles = @(
    "plugins\LuckPerms\luckperms-h2-v2.mv.db"
    "plugins\LuckPerms\luckperms-h2-v2.trace.db"
    "plugins\EliteMobs\data\player_data.db"
    "plugins\ArsPaper\ranking_cache.json"
    "plugins\SetHome\homes.yml"
    "plugins\DiscordSRV\accounts.aof"
    "plugins\DiscordSRV\linkedaccounts.json"
)

function Clear-DirectoryContents {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Label
    )

    if (-not (Test-Path -LiteralPath $Path)) { return }
    $items = @(Get-ChildItem -LiteralPath $Path -Force -ErrorAction SilentlyContinue)
    if ($items.Count -eq 0) { return }

    if ($dryRun) {
        Write-OpsLog "中身を消す: $Path ($($items.Count) 件)" -Level DRYRUN
        return
    }
    Save-Backup -Path $Path -Label $Label
    $items | Remove-Item -Recurse -Force
    Write-OpsLog "中身を消しました: $Path ($($items.Count) 件)"
}

function Set-OpsJson {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Label
    )

    if (-not (Test-Path -LiteralPath $Path)) { return }
    # ConvertFrom-Json は JSON 配列を「配列 1 個」としてパイプへ流すので、
    # @(... | ConvertFrom-Json) だと要素数 1 の入れ子になる。一度変数で受けてから @() で均す。
    $parsed  = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    $entries = @($parsed)
    if ($entries.Count -eq 0) { return }

    $kept    = @($entries | Where-Object { $KeepOps -contains $_.name })
    $removed = @($entries | Where-Object { $KeepOps -notcontains $_.name })
    if ($removed.Count -eq 0) {
        Write-OpsLog "$Path : 外す op はいません。"
        return
    }

    $names = ($removed | ForEach-Object { $_.name }) -join ", "
    if ($dryRun) {
        $keptNames = ($kept | ForEach-Object { $_.name }) -join ", "
        Write-OpsLog "op から外す: $names  (残す: $keptNames)" -Level DRYRUN
        return
    }
    Save-Backup -Path $Path -Label $Label
    # ConvertTo-Json は要素 1 件だと配列に畳まないので @() で囲む。0 件なら素直に [] を書く。
    $json = if ($kept.Count -eq 0) { "[]" } else { ConvertTo-Json -InputObject @($kept) -Depth 5 }
    Write-TextNoBom -Path $Path -Text $json
    Write-OpsLog "op から外しました: $names"
}

# -------------------------------------------------------------------------------------------------
#  サーバごとの消去
# -------------------------------------------------------------------------------------------------
foreach ($name in @($config.Servers.Keys | Sort-Object)) {
    $server = $config.Servers[$name]
    $root   = $server.Root
    Write-OpsLog "----- $($server.Name) -----"

    if (-not (Test-Path -LiteralPath $root)) {
        Write-OpsLog "ルートがありません。飛ばします: $root" -Level WARN
        continue
    }

    foreach ($world in $worlds) {
        foreach ($sub in $worldSubs) {
            Clear-DirectoryContents -Path (Join-Path $root "$world\$sub") -Label "$($server.Name)\$world\$sub"
        }
    }

    foreach ($rel in $pluginDirs) {
        Clear-DirectoryContents -Path (Join-Path $root $rel) -Label "$($server.Name)\$rel"
    }

    foreach ($rel in $pluginFiles) {
        $path = Join-Path $root $rel
        if (-not (Test-Path -LiteralPath $path)) { continue }
        Save-Backup -Path $path -Label "$($server.Name)\$rel"
        Remove-FileSafely -Path $path -DryRun:$dryRun
    }

    $usercache = Join-Path $root "usercache.json"
    if (Test-Path -LiteralPath $usercache) {
        if ($dryRun) {
            Write-OpsLog "空にする: $usercache" -Level DRYRUN
        } else {
            Save-Backup -Path $usercache -Label "$($server.Name)\usercache.json"
            Write-TextNoBom -Path $usercache -Text "[]"
            Write-OpsLog "空にしました: $usercache"
        }
    }

    Set-OpsJson -Path (Join-Path $root "ops.json") -Label "$($server.Name)\ops.json"
}

# -------------------------------------------------------------------------------------------------
#  TrinityForge の進行データ (ジャンクションの実体なので 1 回だけ)
# -------------------------------------------------------------------------------------------------
if ($KeepProgression) {
    Write-OpsLog "-KeepProgression 指定のため TrinityForge の進行データは残します。"
} else {
    $tfRoot = Join-Path $config.Servers.Main.Root "plugins\TrinityForge"
    if (Test-Path -LiteralPath $tfRoot) {
        # 本体と -wal / -shm、過去の手動バックアップ (.dev-*) をまとめて。
        $tfFiles = @(Get-ChildItem -LiteralPath $tfRoot -Force -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like "player_progression.db*" })
        foreach ($file in $tfFiles) {
            Save-Backup -Path $file.FullName -Label "TrinityForge\$($file.Name)"
            Remove-FileSafely -Path $file.FullName -DryRun:$dryRun
        }
        if ($tfFiles.Count -gt 0 -and -not $dryRun) {
            Write-OpsLog "TrinityForge の進行データを消しました (3 サーバ共有の実体)。次回起動時に空で作り直されます。"
        }
    }
}

# -------------------------------------------------------------------------------------------------
#  dev のホワイトリスト (管理者専用にする)
# -------------------------------------------------------------------------------------------------
if ($EnableDevWhitelist -and $config.Servers.ContainsKey("Dev")) {
    $devRoot   = $config.Servers.Dev.Root
    $propsPath = Join-Path $devRoot "server.properties"
    $wlPath    = Join-Path $devRoot "whitelist.json"

    # UUID は消す前の ops.json から採る。既に消した後なのでバックアップ側を先に見る。
    $source = Join-Path $backup "dev\ops.json"
    if (-not (Test-Path -LiteralPath $source)) { $source = Join-Path $devRoot "ops.json" }

    $allow = @()
    if (Test-Path -LiteralPath $source) {
        # 上と同じ理由で、ConvertFrom-Json の結果を一度変数に受けてから @() で均す。
        $parsed = Get-Content -LiteralPath $source -Raw -Encoding UTF8 | ConvertFrom-Json
        $allow  = @(@($parsed) |
            Where-Object { $KeepOps -contains $_.name } |
            ForEach-Object { [pscustomobject]@{ uuid = $_.uuid; name = $_.name } })
    }

    if ($allow.Count -eq 0) {
        Write-OpsLog "dev のホワイトリストに載せる相手が見つかりません ($($KeepOps -join ', '))。" -Level WARN
        Write-OpsLog "誰も入れなくなるので white-list は有効にしません。" -Level WARN
    } elseif ($dryRun) {
        $names = ($allow | ForEach-Object { $_.name }) -join ", "
        Write-OpsLog "dev を white-list=true にし、$names だけ許可する" -Level DRYRUN
    } else {
        Write-TextNoBom -Path $wlPath -Text (ConvertTo-Json -InputObject @($allow) -Depth 5)
        $props = Get-Content -LiteralPath $propsPath -Encoding UTF8
        if ($props -match "^white-list=") {
            $props = $props -replace "^white-list=.*$", "white-list=true"
        } else {
            $props += "white-list=true"
        }
        Write-TextNoBom -Path $propsPath -Text (($props -join "`r`n") + "`r`n")
        $names = ($allow | ForEach-Object { $_.name }) -join ", "
        Write-OpsLog "dev を white-list=true にしました。許可: $names"
    }
}

# -------------------------------------------------------------------------------------------------
#  MariaDB (資格情報が要るので手で流してもらう)
# -------------------------------------------------------------------------------------------------
#  ここを飛ばすと、ファイルを消しても HuskSync がインベントリを DB から復元し、
#  LuckPerms が権限を読み直すので「消したのに戻る」ことになる。必ず流すこと。
$sqlPath = Join-Path $config.VelocityRoot "purge-player-data-$stamp.sql"

# プレイヤー側だけを空にする分。所属グループは user_permissions の group.<名前> ノードなので、
# これを消せばグループ定義を残したまま「誰も属していない」状態にできる。
$sqlPlayers = @'
-- LuckPerms のプレイヤー権限と、HuskSync のインベントリを空にする。
-- テーブル定義は残す (作り直させると型が変わる余地があるため)。
-- 外部キーがあると TRUNCATE が弾かれるので一時的に外す。
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE luckperms.luckperms_user_permissions;
TRUNCATE TABLE luckperms.luckperms_players;
TRUNCATE TABLE luckperms.luckperms_actions;
'@

# グループ定義まで消す分 (-PurgeGroups のときだけ)。
$sqlGroups = @'
-- グループ定義も消す (-PurgeGroups)。開幕前に /lp で作り直すこと。
DELETE FROM luckperms.luckperms_group_permissions;
DELETE FROM luckperms.luckperms_groups;
DELETE FROM luckperms.luckperms_tracks;
'@

$sqlTail = @'
TRUNCATE TABLE husksync.husksync_user_data;
TRUNCATE TABLE husksync.husksync_map_data;
TRUNCATE TABLE husksync.husksync_map_ids;
DELETE FROM husksync.husksync_users;
SET FOREIGN_KEY_CHECKS = 1;
'@

if ($PurgeGroups) {
    $sql = $sqlPlayers + "`r`n" + $sqlGroups + "`r`n" + $sqlTail + "`r`n"
    Write-OpsLog "-PurgeGroups 指定: LuckPerms のグループ定義も消す SQL を出します。" -Level WARN
} else {
    $sql = $sqlPlayers + "`r`n" +
           "-- グループ定義 (luckperms_groups / group_permissions / tracks) は残す。" + "`r`n" +
           "-- 消す場合は purge-player-data.ps1 -PurgeGroups を使うこと。" + "`r`n" +
           $sqlTail + "`r`n"
    Write-OpsLog "LuckPerms のグループ定義は残します (-PurgeGroups で消せます)。"
}

if ($dryRun) {
    Write-OpsLog "SQL を書き出す: $sqlPath" -Level DRYRUN
} else {
    Write-TextNoBom -Path $sqlPath -Text $sql
    Write-OpsLog "MariaDB 用の SQL を書き出しました: $sqlPath"
    Write-OpsLog "次のコマンドで流してください (パスワードは対話入力):"
    # PowerShell では < が使えない (「演算子 '<' は将来の使用のために予約されています」)。
    # パイプで渡すと標準入力がパスワード入力と食い合うので、クライアント組み込みの source を使う。
    Write-OpsLog "  & 'C:\Program Files\MariaDB 12.3\bin\mariadb.exe' -u root -p -e `"source $($sqlPath -replace '\\', '/')`""
    Write-OpsLog "消す前の内容は $backup に残してあります。確認後に削除してください。"
}
