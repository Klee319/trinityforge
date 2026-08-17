<#
.SYNOPSIS
    デスチェストプラグインを DeathChest (chest1.5.7.jar) から AxGraves へ差し替える。

.DESCRIPTION
    ■ なぜ差し替えるのか（1.5.7 が起動できない機構レベルの原因）

      DeathChest は保存キーを
          <world>`<x>`<y>`<z>`<ownerName>`<epochMillis>
      という 1 本の文字列にして deathChests.yml のトップレベルキーにしている。
      ところが **Bukkit の YAML はキー中の `.` をパス区切りとして解釈する**ため、
      Floodgate の `username-prefix: "."`（本構成は proxy/main/resource/dev の 4 つとも "."）
      が付いた統合版プレイヤー名（例: `.natsuking003`）がキーに入った瞬間、
      そのキーは 1 個のキーではなく**入れ子の MemorySection** になる。

      すると load 時に getKeys(false) が `world`567`92`1106`` しか返さず、split の結果が
      6 要素ではなく 4 要素になり、プラグインは
      「Your deathchest save file is not updated!!!!!!!!!!!!」を出したうえで
      **無条件に config.get(key) を List へキャストする**ため ClassCastException で enable に失敗する。

      → 統合版プレイヤーが死んでデスチェストが残ったまま再起動すると必ず再発する。
        上流に修正版は無い（1.5.7 が最新。2026-05-02 公開）。
        しかも失敗したサーバの deathChests.yml は 0 バイトになり、中身は失われる。

    ■ なぜ AxGraves なのか

      永続化が `plugins\AxGraves\data.json` の JSON 配列で、所有者は
      **UUID 文字列**（`obj.addProperty("owner", ...getUniqueId().toString())`）で持つ。
      プレイヤー名をキーにしないので、この不具合は構造的に起こりえない。
      読み込みも try/catch で包まれていて、壊れていても enable は落ちない。

      墓はパケット製（実ブロックではない）なので、現行 config の
      player_breakable=false / explosion_proof=true は「そもそも壊せない」で満たされる。

    ■ やること（この順番でしかやらない）

      A. 検査だけする … 対象サーバの検出／plugins の存在／jar の妥当性／同名 jar の二重配置／
                        config.yml 雛形に置換対象キーがあるか。1 つでも欠ければ【何も書かずに中断】
      B. 稼働判定     … check-servers-stopped.ps1 へ委譲。1 台でも動いていたら中断
      C. 退避         … chest*.jar と plugins\DeathChest\ を plugins\.deathchest-backups\<日時>\ へ
      D. 撤去         … 退避が終わってから chest*.jar と plugins\DeathChest\ を消す
      E. jar をコピー … AxGraves
      F. config を書く… jar 同梱の config.yml を雛形に、現行の挙動へ寄せるキーだけを差し替える

    ⚠ config.yml の雛形は【jar の中の config.yml をそのまま使う】。
      ops 側に全文コピーを置くと、プラグイン更新時に雛形だけ古くなり、新しい項目が
      「設定できないのに既定値で動く」状態になる。ここで書き換えるのは下の 3 つだけ。

        despawn-time-seconds : -1   … 現行の DeathChest は時間で消えない。既定の 180 秒に
                                       すると「死に戻る前に墓が消える」挙動の変更になる
        store-xp             : false … 現行は経験値を預からずバニラどおり地面へ落ちる
        command-aliases      : deathchest / dc を追加（/dc の指の記憶を残す。
                                       Quick-EnderChest は /ec と enderchest/echest だけなので衝突しない）

      それ以外（interact-only-own=false で誰でも開ける、grave-limit 無制限、
      auto-equip-armor など）は既定のままで現行と同じか無害。

    ⚠ このスクリプトは D:\game 配下へ書き込むので、エージェントではなく人が実行する。

.PARAMETER SourceJar
    配備する AxGraves の jar。省略した場合は tmp\AxGraves-*.jar を探す。
    -Download を付けると Modrinth から取得する。

.PARAMETER Download
    Modrinth の固定バージョン（下の PINNED_*）を tmp\ へダウンロードして使う。
    ダウンロード後に SHA-512 を照合し、違えば【何も書かずに中断】する。

.PARAMETER DespawnSeconds
    墓が消えるまでの秒数。-1 で消えない（既定。現行 DeathChest と同じ挙動）。

.PARAMETER DryRun
    退避もコピーも削除も書き込みもせず、計画だけ出す。

.EXAMPLE
    .\swap-deathchest-to-axgraves.ps1 -Download -DryRun

.EXAMPLE
    .\swap-deathchest-to-axgraves.ps1 -Download
#>
[CmdletBinding()]
param(
    [string] $ConfigPath,
    [string] $SourceJar,
    [switch] $Download,
    [int]    $DespawnSeconds = -1,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")

# Modrinth の固定バージョン。上げるときはこの 3 つを同時に直すこと
# （URL だけ直してハッシュを直さないと、照合で必ず止まる＝壊れた配備にはならない）。
$PINNED_VERSION = "1.29.0"
$PINNED_FILE    = "AxGraves-1.29.0.jar"
$PINNED_URL     = "https://cdn.modrinth.com/data/Cz6msz34/versions/WDYTwhVj/AxGraves-1.29.0.jar"
$PINNED_SHA512  = "d95a55d4660d15a95e5f1bd578be619f9ca1ef3bc0995ccb59c612d4648b8fc1b4b507796dfcbf64dca81b7a8c8096392d77f77ede97221cde9bfb63b2ace80b"

# 撤去する側。ファイル名にバージョンが入るのでワイルドカードで拾う。
$OLD_JAR_PATTERN = "chest*.jar"
$OLD_PLUGIN_DIR  = "DeathChest"
$NEW_PLUGIN_NAME = "AxGraves"

# リポジトリ直下の tmp\ を既定の置き場にする（カレントディレクトリの外へは作らない）。
$REPO_ROOT = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$TMP_DIR   = Join-Path $REPO_ROOT "tmp"

function Get-EntryTextFromJar {
    <#
    .SYNOPSIS jar の中の 1 エントリをテキストで取り出す。無ければ $null。
    #>
    param(
        [Parameter(Mandatory)] [string] $JarPath,
        [Parameter(Mandatory)] [string] $EntryName
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq $EntryName } | Select-Object -First 1
        if (-not $entry) { return $null }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try { return $reader.ReadToEnd() } finally { $reader.Close() }
    } finally {
        $zip.Dispose()
    }
}

function Set-TopLevelScalar {
    <#
    .SYNOPSIS トップレベル（インデント無し）の 1 キーだけを置き換える。
    .DESCRIPTION
        YAML パーサを使わないのは、雛形のコメント（全キーに英語の説明が付いている）を
        1 行残らず保つため。パースして書き戻すとコメントが全部消える。

        インデントの無い行だけを対象にするので、入れ子の同名キー
        （save-graves.enabled など）を巻き込まない。
        見つからなければ throw する ＝ プラグイン側の config.yml が変わったことに気づける。
    #>
    param(
        [string[]] $Lines,
        [string]   $Key,
        [string]   $RawValue
    )

    $replaced = $false
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i] -match "^$([regex]::Escape($Key)):\s") {
            $Lines[$i] = "$Key" + ": " + $RawValue
            $replaced = $true
            break
        }
    }
    if (-not $replaced) {
        throw "中断: AxGraves の config.yml にトップレベルキー『$Key』がありません。" +
              "プラグイン側の config.yml が変わった可能性があります（このスクリプトの更新が必要）。"
    }
    return $Lines
}

function Add-ListItems {
    <#
    .SYNOPSIS トップレベルのリストブロックへ項目を追記する（既にあるものは足さない）。
    .DESCRIPTION
        command-aliases のような "key:" の下に "  - \"値\"" が並ぶブロック専用。
        ブロックの最後の項目行の直後へ挿入する。
    #>
    param(
        [string[]] $Lines,
        [string]   $Key,
        [string[]] $Items
    )

    $start = -1
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i] -match "^$([regex]::Escape($Key)):\s*$") { $start = $i; break }
    }
    if ($start -lt 0) {
        throw "中断: AxGraves の config.yml にリスト『$Key』がありません。" +
              "プラグイン側の config.yml が変わった可能性があります（このスクリプトの更新が必要）。"
    }

    $last = $start
    $existing = @()
    for ($i = $start + 1; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i] -match '^\s*-\s*"?([^"]*)"?\s*$') {
            $existing += $Matches[1]
            $last = $i
            continue
        }
        if ($Lines[$i] -match '^\s*$') { continue }
        break
    }
    if ($existing.Count -eq 0) {
        throw "中断: AxGraves の config.yml の『$Key』が空です（少なくとも 1 つ必要）。"
    }

    $toAdd = @($Items | Where-Object { $existing -notcontains $_ })
    if ($toAdd.Count -eq 0) { return $Lines }

    $insert = @($toAdd | ForEach-Object { '  - "' + $_ + '"' })
    $head = @($Lines[0..$last])
    $tail = @()
    if ($last + 1 -lt $Lines.Count) { $tail = @($Lines[($last + 1)..($Lines.Count - 1)]) }
    return @($head + $insert + $tail)
}

function Resolve-AxGravesJar {
    param([string] $Explicit, [switch] $DoDownload, [switch] $PlanOnly)

    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit)) {
            throw "中断: -SourceJar のファイルがありません: $Explicit"
        }
        return Get-Item -LiteralPath $Explicit
    }

    $target = Join-Path $TMP_DIR $PINNED_FILE
    if (Test-Path -LiteralPath $target) {
        return Get-Item -LiteralPath $target
    }

    if (-not $DoDownload) {
        $hits = @()
        if (Test-Path -LiteralPath $TMP_DIR) {
            $hits = @(Get-ChildItem -LiteralPath $TMP_DIR -Filter "AxGraves-*.jar" -File)
        }
        if ($hits.Count -eq 1) { return $hits[0] }
        if ($hits.Count -gt 1) {
            throw "中断: $TMP_DIR に AxGraves-*.jar が $($hits.Count) 件あります。配る 1 本だけを残すか -SourceJar で指定してください:`n  " +
                  (($hits.Name) -join "`n  ")
        }
        throw "中断: AxGraves の jar がありません。`n" +
              "  -Download を付けて Modrinth から取得するか、-SourceJar <path> で指定してください。`n" +
              "  固定バージョン: $PINNED_VERSION ($PINNED_FILE)`n" +
              "  $PINNED_URL"
    }

    if ($PlanOnly) {
        Write-OpsLog "実行時に $PINNED_URL を $target へダウンロードし、SHA-512 を照合する" -Level DRYRUN
        return $null
    }

    if (-not (Test-Path -LiteralPath $TMP_DIR)) {
        New-Item -ItemType Directory -Path $TMP_DIR -Force | Out-Null
    }
    Write-OpsLog "ダウンロード: $PINNED_URL"
    # PowerShell 5.1 の既定は TLS 1.0 で、Modrinth の CDN は受け付けない。
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $PINNED_URL -OutFile $target -UseBasicParsing

    $actual = (Get-FileHash -LiteralPath $target -Algorithm SHA512).Hash.ToLowerInvariant()
    if ($actual -ne $PINNED_SHA512) {
        Remove-Item -LiteralPath $target -Force
        throw "中断: ダウンロードした jar の SHA-512 が一致しません（削除しました）。`n" +
              "  期待: $PINNED_SHA512`n  実際: $actual"
    }
    Write-OpsLog "SHA-512 一致を確認しました ($PINNED_FILE)"
    return Get-Item -LiteralPath $target
}

# ---- A. 検査 -------------------------------------------------------------------------------------
#  ここを抜けるまで 1 バイトも書かない。

$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false

# 差し替え先は「今 DeathChest が入っているサーバ」だけ。dev には入っていないので触らない。
$targets = @()
foreach ($name in @($config.Servers.Keys)) {
    $backend    = $config.Servers[$name]
    $pluginsDir = Join-Path $backend.Root "plugins"
    if (-not (Test-Path -LiteralPath $pluginsDir)) { continue }

    $oldJars = @(Get-ChildItem -LiteralPath $pluginsDir -Filter $OLD_JAR_PATTERN -File -ErrorAction SilentlyContinue)
    $oldDir  = Join-Path $pluginsDir $OLD_PLUGIN_DIR
    $hasOldDir = Test-Path -LiteralPath $oldDir
    if ($oldJars.Count -eq 0 -and -not $hasOldDir) { continue }

    $targets += @{
        Name       = $backend.Name
        PluginsDir = $pluginsDir
        OldJars    = $oldJars
        OldDir     = $(if ($hasOldDir) { $oldDir } else { $null })
    }
}

if ($targets.Count -eq 0) {
    Write-OpsLog "DeathChest が入っているバックエンドがありません。やることはありません。" -Level WARN
    exit 0
}

Write-OpsLog ("対象: " + (($targets | ForEach-Object { $_.Name }) -join ", ") + " の $($targets.Count) 台")

$jar = Resolve-AxGravesJar -Explicit $SourceJar -DoDownload:$Download -PlanOnly:$DryRun

$templateText = $null
if ($jar) {
    $pluginYml = Get-EntryTextFromJar -JarPath $jar.FullName -EntryName "plugin.yml"
    if (-not $pluginYml -or $pluginYml -notmatch "(?m)^name:\s*$NEW_PLUGIN_NAME\s*$") {
        throw "中断: $($jar.Name) は AxGraves の jar ではありません（plugin.yml の name が違う）。"
    }
    $templateText = Get-EntryTextFromJar -JarPath $jar.FullName -EntryName "config.yml"
    if (-not $templateText) {
        throw "中断: jar に config.yml が入っていません: $($jar.FullName)"
    }

    # 置換対象キーが雛形に在ることを、1 台も触る前に確かめる。
    $probe = $templateText -split "`r?`n"
    $probe = Set-TopLevelScalar -Lines $probe -Key "despawn-time-seconds" -RawValue "$DespawnSeconds"
    $probe = Set-TopLevelScalar -Lines $probe -Key "store-xp"             -RawValue "false"
    $probe = Add-ListItems      -Lines $probe -Key "command-aliases"      -Items @("deathchest", "dc")

    $conflicts = @()
    foreach ($t in $targets) {
        $base     = Get-PluginBaseName $jar.Name
        $existing = @(Get-ChildItem -LiteralPath $t.PluginsDir -Filter "*.jar" -File)
        foreach ($rival in $existing) {
            if ($rival.Name -eq $jar.Name) { continue }
            if ((Get-PluginBaseName $rival.Name) -eq $base) {
                $conflicts += "[$($t.Name)] $($jar.Name) と $($rival.Name) が同名プラグイン扱いになります"
            }
        }
        $dir = Join-Path $t.PluginsDir $NEW_PLUGIN_NAME
        if ((Test-Path -LiteralPath $dir) -and (Test-ReparsePoint -Path $dir)) {
            # 共有すると 3 台が同じ data.json を読み、他サーバのワールドの墓を持ち込む。
            throw "中断: [$($t.Name)] plugins\$NEW_PLUGIN_NAME がジャンクションです。実体ディレクトリにしてください。"
        }
    }
    if ($conflicts.Count -gt 0) {
        throw ("中断: 同名プラグインの jar が二重配置になります（Paper が Ambiguous plugin name で起動しません）:`n  " +
            ($conflicts -join "`n  "))
    }
}

# 失われる保留中デスチェストを、消す前に数えて見せる。
Write-Host ""
Write-OpsLog "--- 残っている DeathChest の保留分 ---"
foreach ($t in $targets) {
    if (-not $t.OldDir) { Write-OpsLog "  [$($t.Name)] plugins\$OLD_PLUGIN_DIR なし"; continue }
    $save = Join-Path $t.OldDir "deathChests.yml"
    if (-not (Test-Path -LiteralPath $save)) {
        Write-OpsLog "  [$($t.Name)] deathChests.yml なし"
        continue
    }
    $len = (Get-Item -LiteralPath $save).Length
    if ($len -eq 0) {
        Write-OpsLog "  [$($t.Name)] deathChests.yml が 0 バイト（起動失敗時に中身が失われている）" -Level WARN
        continue
    }
    $keys = @(Get-Content -LiteralPath $save -Encoding UTF8 | Where-Object { $_ -match '^[^\s#-].*:\s*$' })
    Write-OpsLog "  [$($t.Name)] 保留中の墓 $($keys.Count) 件（退避はするが AxGraves へは引き継げない）"
    foreach ($k in $keys) { Write-OpsLog "      $($k.TrimEnd(':'))" }
}

# ---- B. 稼働判定 ---------------------------------------------------------------------------------
#  稼働中に jar を置くと NoClassDefFoundError になり、JVM の停止以外に復旧手段が無い。

Write-Host ""
Write-OpsLog "--- バックエンドが停止しているか ---"
$gate = Join-Path $PSScriptRoot "check-servers-stopped.ps1"
if (-not (Test-Path -LiteralPath $gate)) {
    throw "中断: 稼働判定スクリプトがありません: $gate"
}
$psArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $gate)
if ($ConfigPath) { $psArgs += @("-ConfigPath", $ConfigPath) }
$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$gateCode = 1
try {
    & powershell.exe @psArgs 2>&1 | ForEach-Object { Write-Host "  $_" }
    $gateCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previous
}
if ($gateCode -ne 0) {
    Write-Host ""
    Write-OpsLog ("中断: 稼働中のバックエンドがあります。1 バイトも書いていません。" +
        "稼働中に jar を差し替えると NoClassDefFoundError になり、JVM を落とすまで戻せません。") -Level ERROR
    Write-OpsLog "先に停止してください: launch\stop-all.cmd" -Level ERROR
    exit 1
}

$stamp = (Get-Date).ToString("yyyyMMdd_HHmmss")

if ($DryRun) {
    Write-Host ""
    Write-OpsLog "実行時にやること:" -Level DRYRUN
    foreach ($t in $targets) {
        Write-OpsLog "  [$($t.Name)] $($t.PluginsDir)" -Level DRYRUN
        foreach ($o in $t.OldJars) {
            Write-OpsLog "    退避+削除 $($o.Name)" -Level DRYRUN
        }
        if ($t.OldDir) { Write-OpsLog "    退避+削除 $OLD_PLUGIN_DIR\ (ディレクトリごと)" -Level DRYRUN }
        Write-OpsLog "    配置     $(if ($jar) { $jar.Name } else { $PINNED_FILE })" -Level DRYRUN
        Write-OpsLog "    書込     $NEW_PLUGIN_NAME\config.yml (despawn-time-seconds: $DespawnSeconds / store-xp: false / alias に deathchest,dc を追加)" -Level DRYRUN
    }
    Write-OpsLog "  退避先: plugins\.deathchest-backups\$stamp\" -Level DRYRUN
    exit 0
}

# ---- C. 退避 -------------------------------------------------------------------------------------

Write-Host ""
Write-OpsLog "--- 退避 ---"
foreach ($t in $targets) {
    $backupDir = Join-Path $t.PluginsDir ".deathchest-backups\$stamp"
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    foreach ($o in $t.OldJars) {
        Copy-Item -LiteralPath $o.FullName -Destination $backupDir -Force
        Write-OpsLog "  [$($t.Name)] $($o.Name) -> .deathchest-backups\$stamp"
    }
    if ($t.OldDir) {
        Copy-Item -LiteralPath $t.OldDir -Destination (Join-Path $backupDir $OLD_PLUGIN_DIR) -Recurse -Force
        Write-OpsLog "  [$($t.Name)] $OLD_PLUGIN_DIR\ -> .deathchest-backups\$stamp"
    }
}

# ---- D. 撤去 -------------------------------------------------------------------------------------

Write-Host ""
Write-OpsLog "--- 撤去 ---"
foreach ($t in $targets) {
    foreach ($o in $t.OldJars) {
        Remove-FileSafely -Path $o.FullName
        $stale = Join-Path $t.PluginsDir ".paper-remapped\$($o.Name)"
        if (Test-Path -LiteralPath $stale) { Remove-FileSafely -Path $stale }
    }
    if ($t.OldDir) {
        Remove-DirectorySafely -Path $t.OldDir
    }
}

# ---- E. jar --------------------------------------------------------------------------------------

Write-Host ""
Write-OpsLog "--- jar ---"
foreach ($t in $targets) {
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $t.PluginsDir $jar.Name) -Force
    Write-OpsLog "  [$($t.Name)] $($jar.Name)"
    $stale = Join-Path $t.PluginsDir ".paper-remapped\$($jar.Name)"
    if (Test-Path -LiteralPath $stale) { Remove-FileSafely -Path $stale }
}

# ---- F. config -----------------------------------------------------------------------------------

Write-Host ""
Write-OpsLog "--- $NEW_PLUGIN_NAME config.yml ---"
foreach ($t in $targets) {
    $lines = $templateText -split "`r?`n"
    $lines = Set-TopLevelScalar -Lines $lines -Key "despawn-time-seconds" -RawValue "$DespawnSeconds"
    $lines = Set-TopLevelScalar -Lines $lines -Key "store-xp"             -RawValue "false"
    $lines = Add-ListItems      -Lines $lines -Key "command-aliases"      -Items @("deathchest", "dc")

    $dir  = Join-Path $t.PluginsDir $NEW_PLUGIN_NAME
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    $file = Join-Path $dir "config.yml"
    if (Test-Path -LiteralPath $file) {
        Copy-Item -LiteralPath $file -Destination "$file.predeploy-$stamp" -Force
        Write-OpsLog "  [$($t.Name)] 既存 config.yml を config.yml.predeploy-$stamp へ退避"
    }
    # BOM を付けない。yml 途中の BOM は SnakeYAML が文書境界と誤読する。
    [System.IO.File]::WriteAllText($file, (($lines -join "`r`n")), [System.Text.UTF8Encoding]::new($false))
    Write-OpsLog "  [$($t.Name)] 書き込みました (despawn-time-seconds: $DespawnSeconds / store-xp: false)"
}

Write-Host ""
Write-OpsLog "差し替えました。次にやること:"
Write-OpsLog "  1. launch\start-all.cmd で起動する"
Write-OpsLog "  2. コンソールに AxGraves $PINNED_VERSION が enable されたログが出ること"
Write-OpsLog "  3. 死んで墓が出ること、/grave list（旧 /dc に相当）が動くこと"
Write-OpsLog "  4. 統合版アカウントで死んで墓を残したまま再起動し、enable が落ちないこと（今回の本題）"
Write-OpsLog "  5. 墓は時間で消えない設定なので、溜まりすぎるようなら despawn-time-seconds を有限にする"
exit 0
