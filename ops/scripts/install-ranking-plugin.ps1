<#
.SYNOPSIS
    ランキングプラグイン UserRankBoard (PixelRank) を全バックエンドへ配備し、
    3 台が 1 つの MariaDB を共有する設定で config.yml を配る。

.DESCRIPTION
    やること（この順番でしかやらない）:
      A. 検査だけする … jar の存在／同名 jar の二重配置／plugins ディレクトリの存在。
                        1 つでも欠ければ【何も書かずに中断】する
      B. 稼働判定     … check-servers-stopped.ps1 へ委譲。1 台でも動いていたら中断
      C. 退避         … 上書きする jar と config を plugins\.ranking-backups\<日時>\ へ
      D. jar をコピー … 全バックエンドぶん
      E. config を書く… サーバーごとに server-name だけが違う config.yml を書く

    ⚠ config.yml の雛形は【jar の中の config.yml をそのまま使う】。
      ops 側に全文コピーを置くと、プラグインを更新したときに雛形だけ古くなり、
      新しい項目が「設定できないのに既定値で動く」状態になる。ここで書き換えるのは
      storage: セクションの値だけ。

    ⚠ server-name は 3 台で必ず別々でなければならない。同名を付けると同じ
      (player, server, stat) 行を奪い合い、【片方の記録が消える】。
      このスクリプトは ops-config.psd1 の Servers のキー（main/resource/dev）を
      そのまま使うので、人が手で書いて重複させる余地が無い。

    ⚠ 経済プラグイン(Vault/Jecon)は前提ではない。無い間は所持金ランキングだけが
      登録されず、他のランキングは普通に動く（RankingRegistry が
      EconomyHandler.isAvailable で門を作っている）。あとから経済を入れれば、
      このスクリプトを再実行しなくても所持金ランキングが自動で現れる。

    ⚠ このスクリプトは D:\game 配下へ書き込むので、エージェントではなく人が実行する。

.PARAMETER SourceJar
    配布する jar。既定は UserRankBoard のビルド出力
    (products\minecraft\rank\build\libs\PixelRank-*.jar) を自動で探す。

.PARAMETER DryRun
    退避もコピーも書き込みもせず、計画だけ出す。パスワードも尋ねない。

.EXAMPLE
    .\install-ranking-plugin.ps1 -DryRun

.EXAMPLE
    .\install-ranking-plugin.ps1
#>
[CmdletBinding()]
param(
    [string] $ConfigPath,
    [string] $SourceJar,
    [string] $DbHost = "127.0.0.1",
    [int]    $DbPort = 3306,
    [string] $DbName = "pixelrank",
    [string] $DbUser = "pixelrank",
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")

# jar が置かれていない環境でも「どこを見に行ったか」が分かるように既定を明示する。
$DEFAULT_BUILD_DIR = "C:\Users\T-319\Documents\Program\ClaudeCodeDev\products\minecraft\rank\build\libs"

function Resolve-SourceJar {
    param([string] $Explicit)

    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit)) {
            throw "中断: -SourceJar のファイルがありません: $Explicit"
        }
        return Get-Item -LiteralPath $Explicit
    }
    if (-not (Test-Path -LiteralPath $DEFAULT_BUILD_DIR)) {
        throw "中断: ビルド出力がありません: $DEFAULT_BUILD_DIR`n" +
              "先に UserRankBoard を `./gradlew build` でビルドするか、-SourceJar で jar を指定してください。"
    }
    $hits = @(Get-ChildItem -LiteralPath $DEFAULT_BUILD_DIR -Filter "PixelRank-*.jar" -File |
              Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" })
    if ($hits.Count -eq 0) {
        throw "中断: $DEFAULT_BUILD_DIR に PixelRank-*.jar がありません。先にビルドしてください。"
    }
    if ($hits.Count -gt 1) {
        # どれを配るかが暗黙に決まるのを許さない。
        throw "中断: PixelRank-*.jar が $($hits.Count) 件あります。配る 1 本だけを残してください:`n  " +
              (($hits.Name) -join "`n  ")
    }
    return $hits[0]
}

function Get-ConfigYamlFromJar {
    <#
    .SYNOPSIS jar に同梱された config.yml を取り出す（これが雛形の唯一の出所）。
    #>
    param([string] $JarPath)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq "config.yml" } | Select-Object -First 1
        if (-not $entry) {
            throw "中断: jar に config.yml が入っていません: $JarPath"
        }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try { return $reader.ReadToEnd() } finally { $reader.Close() }
    } finally {
        $zip.Dispose()
    }
}

function Set-StorageValue {
    <#
    .SYNOPSIS storage: セクションの 1 キーだけを置き換える。
    .DESCRIPTION
        YAML パーサを使わないのは、雛形のコメント（日本語の説明が全キーに付いている）を
        1 行残らず保つため。パースして書き戻すとコメントが全部消え、
        現場で config を読んだ人が意味を調べられなくなる。

        インデント 2 のキー行だけを対象にし、storage: セクションの範囲外は触らない。
        （同名キー database が leaderboards 等に現れても巻き込まない）
    #>
    param(
        [string[]] $Lines,
        [string]   $Key,
        [string]   $RawValue
    )

    $inStorage = $false
    $replaced  = $false
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        $line = $Lines[$i]
        if ($line -match '^storage:\s*$') { $inStorage = $true; continue }
        if (-not $inStorage) { continue }
        # インデントの無い行が来たらセクションの終わり（空行・コメントは通す）
        if ($line -match '^\S' ) { break }
        if ($line -match "^(\s{2})$([regex]::Escape($Key)):\s") {
            $Lines[$i] = "  $Key" + ": " + $RawValue
            $replaced = $true
            break
        }
    }
    if (-not $replaced) {
        throw "中断: config.yml の storage: に『$Key』が見つかりませんでした。" +
              "プラグイン側の config.yml が変わった可能性があります（このスクリプトの更新が必要）。"
    }
    return $Lines
}

$jar = Resolve-SourceJar -Explicit $SourceJar

$config  = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false
$targets = @(foreach ($name in @($config.Servers.Keys)) { $config.Servers[$name] })

Write-OpsLog "配布する jar: $($jar.FullName)"
Write-OpsLog ("配布先:       " + (($targets | ForEach-Object { $_.Name }) -join ", ") + " の $($targets.Count) 台")
Write-OpsLog "共有DB:       $DbHost`:$DbPort / $DbName (user: $DbUser)"

# ---- A. 検査 -------------------------------------------------------------------------------------
#  ここを抜けるまで 1 バイトも書かない。

# server-name の重複はこのプラグインで唯一の「記録が消える」設定なので、配る前に潰す。
$names = @($targets | ForEach-Object { $_.Name })
$dupes = @($names | Group-Object | Where-Object { $_.Count -gt 1 })
if ($dupes.Count -gt 0) {
    throw "中断: サーバー名が重複しています: " + (($dupes | ForEach-Object { $_.Name }) -join ", ") +
          "`n同名だと rank_stats の同じ行を奪い合い、片方の記録が消えます。"
}
foreach ($n in $names) {
    if ($n.Length -gt 32) {
        throw "中断: サーバー名『$n』が 32 文字を超えています（rank_stats.server は VARCHAR(32)）。"
    }
}

$templateText = Get-ConfigYamlFromJar -JarPath $jar.FullName

$conflicts = @()
foreach ($backend in $targets) {
    $pluginsDir = Join-Path $backend.Root "plugins"
    if (-not (Test-Path -LiteralPath $pluginsDir)) {
        throw "中断: plugins がありません: $pluginsDir`n" +
              "ops-config.psd1 の Servers.$($backend.Name).Root が実環境と食い違っていないか確認してください。"
    }
    $existing = @(Get-ChildItem -LiteralPath $pluginsDir -Filter "*.jar" -File)
    $base = Get-PluginBaseName $jar.Name
    $rivals = @($existing | Where-Object {
        $_.Name -ne $jar.Name -and (Get-PluginBaseName $_.Name) -eq $base
    })
    foreach ($rival in $rivals) {
        $conflicts += "[$($backend.Name)] $($jar.Name) と $($rival.Name) が同名プラグイン扱いになります"
    }
}
if ($conflicts.Count -gt 0) {
    throw ("中断: 同名プラグインの jar が二重配置になります（Paper が Ambiguous plugin name で起動しません）:`n  " +
        ($conflicts -join "`n  "))
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
    Write-OpsLog ("中断: 稼働中のバックエンドがあります。jar は 1 本も置いていません。" +
        "稼働中に置くと NoClassDefFoundError になり、JVM を落とすまで戻せません。") -Level ERROR
    Write-OpsLog "先に停止してください: launch\stop-all.cmd" -Level ERROR
    exit 1
}

# ---- パスワードを受け取る ------------------------------------------------------------------------

$password = $null
if (-not $DryRun) {
    $password = [Environment]::GetEnvironmentVariable("TF_PIXELRANK_DB_PASSWORD")
    if ([string]::IsNullOrEmpty($password)) {
        Write-Host ""
        Write-OpsLog "MariaDB の '$DbUser' ユーザーのパスワードを入力してください（表示されません）"
        $secure = Read-Host -AsSecureString "password"
        $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try {
            $password = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
        } finally {
            [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        }
    }
    if ([string]::IsNullOrEmpty($password)) {
        Write-OpsLog "パスワードが空です。中断します。" -Level ERROR
        exit 1
    }
}

# ---- 計画表示 / 実行 -----------------------------------------------------------------------------

$stamp = (Get-Date).ToString("yyyyMMdd_HHmmss")

if ($DryRun) {
    Write-Host ""
    Write-OpsLog "実行時にやること:" -Level DRYRUN
    foreach ($backend in $targets) {
        $pluginsDir = Join-Path $backend.Root "plugins"
        $dest  = Join-Path $pluginsDir $jar.Name
        $state = if (Test-Path -LiteralPath $dest) { "上書き（退避してから）" } else { "新規" }
        Write-OpsLog "  [$($backend.Name)] $pluginsDir" -Level DRYRUN
        Write-OpsLog "    jar    $($jar.Name) : $state" -Level DRYRUN
        $cfg = Join-Path $pluginsDir "PixelRank\config.yml"
        $cstate = if (Test-Path -LiteralPath $cfg) { "上書き（退避してから）" } else { "新規作成" }
        Write-OpsLog "    config PixelRank\config.yml : $cstate (server-name: $($backend.Name))" -Level DRYRUN
    }
    Write-Host ""
    Write-OpsLog ("実行時に MariaDB の '$DbUser' ユーザーのパスワードを尋ねます" +
        "（環境変数 TF_PIXELRANK_DB_PASSWORD があればそれを使う）。") -Level DRYRUN
    Write-OpsLog "先に RUNBOOK 手順 20 の SQL で $DbName DB とユーザーを作っておくこと。" -Level DRYRUN
    exit 0
}

# C. 退避
Write-Host ""
Write-OpsLog "--- 退避 ---"
foreach ($backend in $targets) {
    $pluginsDir = Join-Path $backend.Root "plugins"
    $backupDir  = Join-Path $pluginsDir ".ranking-backups\$stamp"

    $toBackup = @()
    $dest = Join-Path $pluginsDir $jar.Name
    if (Test-Path -LiteralPath $dest) { $toBackup += $dest }
    $cfg = Join-Path $pluginsDir "PixelRank\config.yml"
    if (Test-Path -LiteralPath $cfg) { $toBackup += $cfg }

    if ($toBackup.Count -eq 0) {
        Write-OpsLog "  [$($backend.Name)] 退避するものはありません（新規導入）"
        continue
    }
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    foreach ($path in $toBackup) {
        Copy-Item -LiteralPath $path -Destination $backupDir -Force
        Write-OpsLog "  [$($backend.Name)] $(Split-Path $path -Leaf) -> .ranking-backups\$stamp"
    }
}

# D. jar
Write-Host ""
Write-OpsLog "--- jar ---"
foreach ($backend in $targets) {
    $pluginsDir = Join-Path $backend.Root "plugins"
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $pluginsDir $jar.Name) -Force
    Write-OpsLog "  [$($backend.Name)] $($jar.Name)"
}

# E. config。server-name だけがサーバーごとに違う。
Write-Host ""
Write-OpsLog "--- PixelRank config.yml ---"
$quotedPassword = ConvertTo-YamlSingleQuoted -Value $password
$password = $null
foreach ($backend in $targets) {
    $lines = $templateText -split "`r?`n"
    $lines = Set-StorageValue -Lines $lines -Key "type"          -RawValue '"mysql"'
    $lines = Set-StorageValue -Lines $lines -Key "host"          -RawValue ('"' + $DbHost + '"')
    $lines = Set-StorageValue -Lines $lines -Key "port"          -RawValue "$DbPort"
    $lines = Set-StorageValue -Lines $lines -Key "database-name" -RawValue ('"' + $DbName + '"')
    $lines = Set-StorageValue -Lines $lines -Key "username"      -RawValue ('"' + $DbUser + '"')
    $lines = Set-StorageValue -Lines $lines -Key "password"      -RawValue $quotedPassword
    # ここが本題。自動判定に任せず、ops-config のキーをそのまま焼き込む。
    $lines = Set-StorageValue -Lines $lines -Key "server-name"   -RawValue ('"' + $backend.Name + '"')
    # 旧 SQLite は存在しないので取り込みは走らせない（走っても無害だが、
    # 将来 database.db を手で置いたときに黙って自サーバーの記録として取り込むのを防ぐ）。
    $lines = Set-StorageValue -Lines $lines -Key "migrate-from-sqlite" -RawValue "false"

    $content = ($lines -join "`r`n")
    if ($content.Contains($quotedPassword) -eq $false) {
        throw "中断: パスワードの書き込みに失敗しました（[$($backend.Name)]）。"
    }

    $dir  = Join-Path $backend.Root "plugins\PixelRank"
    $file = Join-Path $dir "config.yml"
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    } elseif (Test-ReparsePoint -Path $dir) {
        # plugins\PixelRank をジャンクション共有すると 3 台が同じ config を見る＝
        # server-name が同じになり、記録を奪い合う。
        throw "中断: [$($backend.Name)] plugins\PixelRank がジャンクションです。" +
              "共有すると server-name が同じになり、3 台で記録を奪い合います。実体ディレクトリにしてください。"
    }
    # BOM を付けない。yml 途中の BOM は SnakeYAML が文書境界と誤読する。
    [System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))
    Write-OpsLog "  [$($backend.Name)] 書き込みました（server-name: $($backend.Name) / パスワードは表示しません）"
}
$quotedPassword = $null

# F. Paper の再マップ済みキャッシュ
Write-Host ""
Write-OpsLog "--- .paper-remapped の掃除 ---"
foreach ($backend in $targets) {
    $remapped = Join-Path $backend.Root "plugins\.paper-remapped"
    if (-not (Test-Path -LiteralPath $remapped)) { continue }
    $stale = Join-Path $remapped $jar.Name
    if (Test-Path -LiteralPath $stale) {
        Remove-FileSafely -Path $stale
    }
}

Write-Host ""
Write-OpsLog "配備しました。次にやること:"
Write-OpsLog "  1. RUNBOOK 手順 20 の SQL で $DbName DB とユーザーを作っておく（未実施なら接続できない）"
Write-OpsLog "  2. launch\start-all.cmd で起動する"
Write-OpsLog "  3. /pixelrank rank で一覧が出ること、3 台で同じ順位が出ることを確認する"
Write-OpsLog "  4. SELECT DISTINCT server FROM $DbName.rank_stats; が 3 行（main/resource/dev）になること"
exit 0
