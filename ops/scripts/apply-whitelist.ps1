<#
.SYNOPSIS
    「実際にログインしたことがある人」だけを許可するホワイトリストを作り、バックエンドへ適用する。

.DESCRIPTION
    2026-08-27 追加。

    ── なぜ要るのか ────────────────────────────────────────────────────────────
    Velocity のログには、心当たりのないアカウントが海外のデータセンター IP
    (Scaleway 等) から接続してくる記録が残っている。いずれもワールドへ入る前に
    切れているので実害は出ていないが、【サーバは既にスキャンされている】。
    25 人規模ではホワイトリストが無料で最も効く対策になる (ops/SECURITY.md §2)。

    ── 名簿をどこから作るか ────────────────────────────────────────────────────
    手で書くと必ず取りこぼす。かつ【このリポジトリは public なので、プレイヤー名と
    UUID の一覧を版管理へ入れてはならない】。そこでこのスクリプトは名簿を持たず、
    実行のたびに稼働サーバ自身のデータから組み立てる。

    採用する根拠は「本当にゲームへ入った」ことが分かるものだけ:
      1. usercache.json          … ログイン時にサーバがプロファイルを記録する
      2. <world>\playerdata\*.dat … 一度でも入れば必ずできる
      3. ログの "<名前> joined the game"

    ログの "UUID of player <名前> is <UUID>" 行【だけ】しか無いアカウントは採用しない。
    これは認証は通ったがワールドへ入る前に切れた者で、スキャナーがここに落ちる。
    名前と UUID の対応はこの行から引くので、読むこと自体はする。

    統合版 (Floodgate) のプレイヤーは名前の先頭にドットが付き、UUID は
    00000000-0000-0000-0009-xxxxxxxxxxxx 形式になる。Paper のホワイトリスト照合は
    UUID で行われるため、この形式のまま whitelist.json へ書けば正しく効く。
    逆に `whitelist add <名前>` を打つと Mojang に無いアカウントとして失敗するので、
    【統合版を含む名簿はコマンドではなくファイルで入れる】必要がある。

    ── 適用の順番 (稼働中でも安全に効かせるため) ────────────────────────────────
      1. whitelist.json と server.properties を .bak-<日時> へ退避
      2. whitelist.json を書く (UTF-8 BOM なし)
      3. RCON で `whitelist reload` → `whitelist on` → `whitelist list` で確認
         Paper の `whitelist on` は server.properties の white-list も書き換えるので、
         再起動後もホワイトリストは有効なまま残る
      4. そのあとで server.properties へ enforce-whitelist=true を足す
         (3 より先に足すと、Paper が properties を書き直したときに消える)
         enforce-whitelist は【次回再起動から】効く。名簿から外した人をその場で
         切断させたいときだけ必要な設定で、今回は全員が名簿に載るので即時の影響は無い。

.PARAMETER Target
    書き込む先。既定は main,resource。dev は検証用サーバで、既に white-list=true かつ
    許可は Klee319 だけなので【既定では触らない】。含めたいときだけ明示する。

.PARAMETER ScanTarget
    履歴を読む先。既定は all (ops-config.psd1 の Servers 全部)。
    停止中のサーバもファイルとログは読めるので、読む対象は書く対象より広くてよい。

.PARAMETER LegacyRoot
    分離前の旧サーバのルート。ここの usercache.json も名簿に加える。
    現ネットワークへ来る前にしか遊んでいない人を拾うために使う。

.PARAMETER ExcludeName
    名簿から外す名前 (統合版はドット込み)。大文字小文字は区別しない。

.PARAMETER ExtraEntry
    名簿へ手で足す "名前=UUID" 形式の文字列。まだ一度も来ていない人を先に通すとき用。

.PARAMETER BedrockGamertag
    統合版 (Bedrock) プレイヤーのゲーマータグ。【一度もサーバへ来ていない人でも先に通せる】。
    GeyserMC の公開 API (api.geysermc.org) へゲーマータグを送って XUID を取得し、
    Floodgate と同じ規則で UUID と Java 側の名前を組み立てて名簿へ入れる。
    -DryRun でも問い合わせは行う (先に引けているか確認するため)。

.PARAMETER BedrockXuid
    "ゲーマータグ=XUID" 形式。XUID が既に分かっているときに使う。外部への問い合わせをしない。
    -BedrockGamertag が API のレート制限 (503) で引けないときの逃げ道でもある。

.PARAMETER NoRcon
    RCON を使わず、ファイルの書き換えだけを行う。サーバ停止中に使う。
    この場合 white-list=true も server.properties へ直接書く。

.PARAMETER SkipEnforce
    enforce-whitelist=true を書かない。

.PARAMETER DryRun
    何も書き込まず、名簿と変更予定だけを出す。**必ず先にこれで確認すること。**

.EXAMPLE
    .\apply-whitelist.ps1 -DryRun

.EXAMPLE
    # 統合版の友人 2 人を、本人がアクセスする前に通す
    .\apply-whitelist.ps1 -BedrockGamertag "Steve Alex","BedrockFriend01" -DryRun

.EXAMPLE
    .\apply-whitelist.ps1 -LegacyRoot "D:\game\minecraft\PaperServer\TrinityForge_test" -DryRun

.EXAMPLE
    .\apply-whitelist.ps1
#>
[CmdletBinding()]
param(
    [string[]] $Target = @("Main", "Resource"),
    [string]   $ScanTarget = "all",
    [string]   $LegacyRoot,
    [string[]] $ExcludeName = @(),
    [string[]] $ExtraEntry = @(),
    [string[]] $BedrockGamertag = @(),
    [string[]] $BedrockXuid = @(),
    [string]   $ConfigPath,
    [switch]   $NoRcon,
    [switch]   $SkipEnforce,
    [switch]   $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")

# ---- 名簿の組み立て ----------------------------------------------------------------------------

$reUuidLine = [regex] 'UUID of player (\S+) is ([0-9a-fA-F-]{36})'
$reJoined   = [regex] '\[Server thread/INFO\]: (\S+) joined the game'

function ConvertFrom-JsonArray {
    <#
    .SYNOPSIS JSON 配列を「必ず要素数の分かる配列」として返す。
    .DESCRIPTION
        ⚠ Windows PowerShell 5.1 の ConvertFrom-Json は配列を【1 個のオブジェクトとして】
        出力する。@(...) で囲っても件数は常に 1 になり、
        「書き込み後に件数を数えて検算する」たぐいの検査が全部素通りする。
        パイプへ流し直して初めて要素へばらける。
    #>
    param([string] $Raw)

    # ⚠ 空を返すとき return @() は【何も出力しない】に化けるので、呼び出し側は必ず @() で受ける。
    if ([string]::IsNullOrWhiteSpace($Raw)) { return @() }
    $parsed = ConvertFrom-Json $Raw
    if ($null -eq $parsed) { return @() }
    return @($parsed | ForEach-Object { $_ })
}

function Read-LogLines {
    <#
    .SYNOPSIS ログ 1 本を行単位で返す。.gz も latest.log (サーバが開いたまま) も読める。
    #>
    param([Parameter(Mandatory)] [string] $Path)

    # latest.log はサーバが書き込み中なので ReadWrite で共有しないと開けない。
    $fs = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        $stream = if ($Path.EndsWith(".gz", [StringComparison]::OrdinalIgnoreCase)) {
            [System.IO.Compression.GZipStream]::new($fs, [System.IO.Compression.CompressionMode]::Decompress)
        } else { $fs }
        $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
        try {
            while ($null -ne ($line = $reader.ReadLine())) { $line }
        } finally { $reader.Dispose() }
    } finally { $fs.Dispose() }
}

class Roster {
    # UUID(小文字) -> @{ Uuid; Name; Strong(bool); Sources(List[string]) }
    [hashtable] $ByUuid = @{}
    # 名前(小文字) -> UUID(小文字)。ログの名前から UUID を引くため。
    [hashtable] $UuidByName = @{}
}

function Add-RosterEntry {
    param(
        [Parameter(Mandatory)] [Roster] $Roster,
        [Parameter(Mandatory)] [string] $Uuid,
        [string] $Name,
        [Parameter(Mandatory)] [string] $Source,
        [switch] $Strong
    )

    $key = $Uuid.ToLowerInvariant()
    if (-not $Roster.ByUuid.ContainsKey($key)) {
        $Roster.ByUuid[$key] = @{
            Uuid    = $key
            Name    = $null
            Strong  = $false
            Sources = New-Object System.Collections.Generic.List[string]
        }
    }
    $entry = $Roster.ByUuid[$key]
    # 最初に入った名前を正とする。呼び出し側が新しい順 (usercache → ログ) で回す。
    if (-not $entry.Name -and $Name) { $entry.Name = $Name }
    if ($Strong) { $entry.Strong = $true }
    if (-not $entry.Sources.Contains($Source)) { $entry.Sources.Add($Source) }

    if ($Name) {
        $nameKey = $Name.ToLowerInvariant()
        if (-not $Roster.UuidByName.ContainsKey($nameKey)) { $Roster.UuidByName[$nameKey] = $key }
    }
}

function Import-ServerHistory {
    <#
    .SYNOPSIS サーバのルート 1 つを走査して名簿へ足す。
    #>
    param(
        [Parameter(Mandatory)] [Roster] $Roster,
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $Label,
        [switch] $ScanLogs
    )

    if (-not (Test-Path -LiteralPath $Root)) {
        Write-OpsLog "  [$Label] ルートがありません: $Root" -Level WARN
        return
    }

    # 1) usercache.json … ログインしたときにだけ書かれる。名前も最新。
    $userCache = Join-Path $Root "usercache.json"
    if (Test-Path -LiteralPath $userCache) {
        $raw = Get-Content -LiteralPath $userCache -Raw -Encoding UTF8
        if (-not [string]::IsNullOrWhiteSpace($raw)) {
            foreach ($item in (ConvertFrom-Json $raw)) {
                Add-RosterEntry -Roster $Roster -Uuid $item.uuid -Name $item.name `
                    -Source "$Label/usercache" -Strong
            }
        }
    }

    # 2) playerdata … 一度でも入れば必ずできる。名前は持っていない。
    foreach ($dir in @(Get-ChildItem -LiteralPath $Root -Directory -ErrorAction SilentlyContinue)) {
        $playerData = Join-Path $dir.FullName "playerdata"
        if (-not (Test-Path -LiteralPath $playerData)) { continue }
        foreach ($dat in @(Get-ChildItem -LiteralPath $playerData -Filter "*.dat" -File -ErrorAction SilentlyContinue)) {
            $uuid = [System.IO.Path]::GetFileNameWithoutExtension($dat.Name)
            if ($uuid -notmatch '^[0-9a-fA-F-]{36}$') { continue }
            Add-RosterEntry -Roster $Roster -Uuid $uuid -Source "$Label/playerdata" -Strong
        }
    }

    if (-not $ScanLogs) { return }

    # 3) ログ … 名前と UUID の対応 (弱い根拠) と、joined the game (強い根拠)。
    $logDir = Join-Path $Root "logs"
    if (-not (Test-Path -LiteralPath $logDir)) { return }
    $logs = @(Get-ChildItem -LiteralPath $logDir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*.log.gz" -or $_.Name -eq "latest.log" } |
        Sort-Object Name)
    if ($logs.Count -eq 0) { return }

    $joinedNames = New-Object System.Collections.Generic.HashSet[string]
    foreach ($log in $logs) {
        foreach ($line in (Read-LogLines -Path $log.FullName)) {
            $m = $reUuidLine.Match($line)
            if ($m.Success) {
                # ここでは Strong にしない。認証だけ通って入れなかった者が混ざる。
                Add-RosterEntry -Roster $Roster -Uuid $m.Groups[2].Value -Name $m.Groups[1].Value `
                    -Source "$Label/log-auth"
                continue
            }
            $m = $reJoined.Match($line)
            if ($m.Success) { [void] $joinedNames.Add($m.Groups[1].Value.ToLowerInvariant()) }
        }
    }

    foreach ($name in $joinedNames) {
        if (-not $Roster.UuidByName.ContainsKey($name)) { continue }
        $key = $Roster.UuidByName[$name]
        $Roster.ByUuid[$key].Strong = $true
        if (-not $Roster.ByUuid[$key].Sources.Contains("$Label/joined")) {
            $Roster.ByUuid[$key].Sources.Add("$Label/joined")
        }
    }

    Write-OpsLog "  [$Label] ログ $($logs.Count) 本を読みました"
}

# ---- 統合版 (Floodgate) の事前登録 --------------------------------------------------------------
#
#   Paper のホワイトリスト照合は【UUID だけ】で行われる (whitelist.json の name は表示用)。
#   そして Floodgate が統合版プレイヤーへ配る UUID は XUID から一意に決まる:
#
#       UUID = 00000000-0000-0000-<XUID を 16 桁の 16 進にしたもの>
#       例) XUID 2535400000000001 -> 00000000-0000-0000-0009-01eed05d1001
#
#   つまり XUID さえ分かれば、本人が一度もアクセスしなくても正しい行を書ける。
#   (2026-09-01 に配備先 usercache.json の統合版 16 人全員でこの式が成り立つことを確認済み)
#
#   XUID は Xbox 側が発番するのでこちらでは計算できない。ゲーマータグからの変換は
#   GeyserMC が公開している API を使う。キャッシュに無いタグは Xbox Live へ問い合わせに行くので、
#   一度も Geyser サーバへ来ていない人でも引ける。ただしレート制限に当たると 503 を返す。

function Get-FloodgateNameRule {
    <#
    .SYNOPSIS 配備先の Floodgate 設定から、Java 側の名前の作り方を読む。
    .DESCRIPTION
        読めなければ Floodgate の既定値 (prefix "." / 空白は _ に置換) を使う。
        名前は表示用でしかないので、ここが多少ずれても許可・不許可の判定には影響しない。
    #>
    param([string] $VelocityRoot)

    $rule = @{ Prefix = "."; ReplaceSpaces = $true; Source = "既定値" }
    if (-not $VelocityRoot) { return $rule }

    $path = Join-Path $VelocityRoot "plugins\floodgate\config.yml"
    if (-not (Test-Path -LiteralPath $path)) { return $rule }

    $text = Get-Content -LiteralPath $path -Raw -Encoding UTF8
    if ($text -match '(?m)^\s*username-prefix:\s*"?([^"\r\n]*?)"?\s*$') {
        $rule.Prefix = $Matches[1]
    }
    if ($text -match '(?m)^\s*replace-spaces:\s*(true|false)\s*$') {
        $rule.ReplaceSpaces = ($Matches[1] -ieq "true")
    }
    $rule.Source = $path
    return $rule
}

function ConvertTo-FloodgateUuid {
    param([Parameter(Mandatory)] [long] $Xuid)

    if ($Xuid -le 0) { throw "XUID が正の数ではありません: $Xuid" }
    $hex = $Xuid.ToString("x16")
    return "00000000-0000-0000-{0}-{1}" -f $hex.Substring(0, 4), $hex.Substring(4)
}

function ConvertTo-FloodgateName {
    param(
        [Parameter(Mandatory)] [string] $Gamertag,
        [Parameter(Mandatory)] [hashtable] $Rule
    )

    $tag = $Gamertag
    if ($Rule.ReplaceSpaces) { $tag = $tag -replace ' ', '_' }
    $name = $Rule.Prefix + $tag
    # Java の名前は 16 文字までなので Floodgate も同じところで切る。
    if ($name.Length -gt 16) { $name = $name.Substring(0, 16) }
    return $name
}

function Resolve-BedrockXuid {
    <#
    .SYNOPSIS ゲーマータグを GeyserMC の公開 API へ投げて XUID を得る。
    .DESCRIPTION
        ⚠ ここだけは外部 (api.geysermc.org) へ通信する。送るのはゲーマータグだけ。
        Windows PowerShell 5.1 は既定で TLS1.2 を使わないことがあるので明示的に足す。
    #>
    param([Parameter(Mandatory)] [string] $Gamertag)

    try {
        [Net.ServicePointManager]::SecurityProtocol =
            [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    } catch { }

    $uri = "https://api.geysermc.org/v2/xbox/xuid/" + [uri]::EscapeDataString($Gamertag)
    try {
        $res = Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec 20
    } catch {
        $code = $null
        try { $code = [int] $_.Exception.Response.StatusCode } catch { }
        if ($code -eq 400 -or $code -eq 404) {
            throw "ゲーマータグ '$Gamertag' は見つかりませんでした（綴り違い、または存在しないアカウント）。"
        }
        if ($code -eq 503) {
            throw ("GeyserMC の API がゲーマータグ '$Gamertag' を引けませんでした (503)。" +
                   "Xbox Live 側のレート制限です。しばらく待つか -BedrockXuid で直接渡してください。")
        }
        throw "GeyserMC の API 呼び出しに失敗しました ($Gamertag): $($_.Exception.Message)"
    }

    if (-not $res -or -not ($res.PSObject.Properties.Name -contains "xuid")) {
        throw "ゲーマータグ '$Gamertag' の XUID が返りませんでした（綴り違い、または存在しないアカウント）。"
    }
    return [long] $res.xuid
}

function Add-BedrockEntry {
    param(
        [Parameter(Mandatory)] [Roster] $Roster,
        [Parameter(Mandatory)] [string] $Gamertag,
        [Parameter(Mandatory)] [long]   $Xuid,
        [Parameter(Mandatory)] [hashtable] $Rule,
        [Parameter(Mandatory)] [string] $Source
    )

    $uuid = ConvertTo-FloodgateUuid -Xuid $Xuid
    $name = ConvertTo-FloodgateName -Gamertag $Gamertag -Rule $Rule
    # 現行の XUID は先頭が 0009 になる。違う形が出たら式の前提が変わった合図なので黙って通さない。
    if ($uuid -notlike "00000000-0000-0000-0009-*") {
        Write-OpsLog "XUID $Xuid から出た UUID の形が想定と違います: $uuid（続行しますが要確認）" -Level WARN
    }
    Write-OpsLog "  統合版: $Gamertag -> $name / $uuid（XUID $Xuid）"
    Add-RosterEntry -Roster $Roster -Uuid $uuid -Name $name -Source $Source -Strong
}

# ---- 設定の読み込み ----------------------------------------------------------------------------

# ---- 設定の読み込み ----------------------------------------------------------------------------

$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:(-not $NoRcon -and -not $DryRun)

$writeTargets = @(foreach ($name in $Target) { Resolve-OpsServer -Config $config -Target $name })
$scanTargets  = @(
    if ($ScanTarget -ieq "all") {
        foreach ($name in @($config.Servers.Keys)) { $config.Servers[$name] }
    } else {
        Resolve-OpsServer -Config $config -Target $ScanTarget
    }
)

if ($DryRun) { Write-OpsLog "=== DRY RUN: 何も書き込みません ===" -Level DRYRUN }

Write-OpsLog "履歴を走査します（$($scanTargets.Count) サーバ）"
$roster = [Roster]::new()
foreach ($server in $scanTargets) {
    Import-ServerHistory -Roster $roster -Root $server.Root -Label $server.Name -ScanLogs
}
if ($LegacyRoot) {
    Write-OpsLog "旧サーバも走査します: $LegacyRoot"
    Import-ServerHistory -Roster $roster -Root $LegacyRoot -Label "legacy" -ScanLogs
}

foreach ($extra in $ExtraEntry) {
    if ($extra -notmatch '^\s*(?<name>[^=]+?)\s*=\s*(?<uuid>[0-9a-fA-F-]{36})\s*$') {
        Write-OpsLog "-ExtraEntry の書式が違います（`"名前=UUID`" で渡す）: $extra" -Level ERROR
        exit 1
    }
    Add-RosterEntry -Roster $roster -Uuid $Matches.uuid -Name $Matches.name -Source "manual" -Strong
}

if ($BedrockGamertag.Count -gt 0 -or $BedrockXuid.Count -gt 0) {
    $velocityRoot = if ($config.ContainsKey("VelocityRoot")) { $config.VelocityRoot } else { $null }
    $nameRule = Get-FloodgateNameRule -VelocityRoot $velocityRoot
    Write-OpsLog ("統合版の名前規則: 接頭辞 '{0}' / 空白の置換 {1}（{2}）" -f `
        $nameRule.Prefix, $nameRule.ReplaceSpaces, $nameRule.Source)

    foreach ($pair in $BedrockXuid) {
        if ($pair -notmatch '^\s*(?<tag>[^=]+?)\s*=\s*(?<xuid>\d{1,19})\s*$') {
            Write-OpsLog "-BedrockXuid の書式が違います（`"ゲーマータグ=XUID`" で渡す）: $pair" -Level ERROR
            exit 1
        }
        Add-BedrockEntry -Roster $roster -Gamertag $Matches.tag -Xuid ([long] $Matches.xuid) `
            -Rule $nameRule -Source "bedrock-manual"
    }

    foreach ($tag in $BedrockGamertag) {
        $tag = $tag.Trim()
        if (-not $tag) { continue }
        Write-OpsLog "GeyserMC の API へ問い合わせます: $tag"
        Add-BedrockEntry -Roster $roster -Gamertag $tag -Xuid (Resolve-BedrockXuid -Gamertag $tag) `
            -Rule $nameRule -Source "bedrock-lookup"
    }
}

# ---- 採否の確定 --------------------------------------------------------------------------------

$excluded = @{}
foreach ($name in $ExcludeName) { $excluded[$name.ToLowerInvariant()] = $true }

$accepted = New-Object System.Collections.Generic.List[object]
$rejected = New-Object System.Collections.Generic.List[object]

foreach ($key in @($roster.ByUuid.Keys | Sort-Object)) {
    $entry = $roster.ByUuid[$key]
    if (-not $entry.Strong) {
        $rejected.Add([pscustomobject]@{ Name = $entry.Name; Uuid = $entry.Uuid; Reason = "ワールドへ入った記録が無い"; Sources = ($entry.Sources -join ",") })
        continue
    }
    if (-not $entry.Name) {
        # playerdata しか無く名前が分からない。UUID だけでも照合は効くが、
        # 誰なのか説明できないものを黙って通さない。
        $rejected.Add([pscustomobject]@{ Name = "(不明)"; Uuid = $entry.Uuid; Reason = "名前を特定できない"; Sources = ($entry.Sources -join ",") })
        continue
    }
    if ($excluded.ContainsKey($entry.Name.ToLowerInvariant())) {
        $rejected.Add([pscustomobject]@{ Name = $entry.Name; Uuid = $entry.Uuid; Reason = "-ExcludeName で除外"; Sources = ($entry.Sources -join ",") })
        continue
    }
    $accepted.Add([pscustomobject]@{ Name = $entry.Name; Uuid = $entry.Uuid; Sources = ($entry.Sources -join ",") })
}

$accepted = @($accepted | Sort-Object { $_.Name.TrimStart(".").ToLowerInvariant() })

Write-Host ""
Write-OpsLog "許可する $($accepted.Count) アカウント:"
foreach ($a in $accepted) { Write-Host ("  {0,-22} {1}" -f $a.Name, $a.Uuid) }

if ($rejected.Count -gt 0) {
    Write-Host ""
    Write-OpsLog "名簿に載せない $($rejected.Count) 件:" -Level WARN
    foreach ($r in $rejected) { Write-Host ("  {0,-22} {1}  … {2}" -f $r.Name, $r.Uuid, $r.Reason) }
}

if ($accepted.Count -eq 0) {
    Write-OpsLog "名簿が空です。この状態でホワイトリストを ON にすると全員締め出されます。中止します。" -Level ERROR
    exit 1
}

# 名簿はプレイヤー名と UUID の一覧なので、public リポジトリへ持ち出さない。
# tmp\ は .gitignore 対象なのでここへだけ残す。
$repoRoot   = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$reportDir  = Join-Path $repoRoot "tmp\whitelist"
$stamp      = (Get-Date).ToString("yyyyMMdd_HHmmss")
if (-not $DryRun) {
    if (-not (Test-Path -LiteralPath $reportDir)) { New-Item -ItemType Directory -Path $reportDir -Force | Out-Null }
    $reportPath = Join-Path $reportDir "roster-$stamp.json"
    [System.IO.File]::WriteAllText($reportPath,
        (@{ accepted = $accepted; rejected = $rejected } | ConvertTo-Json -Depth 4),
        [System.Text.UTF8Encoding]::new($false))
    Write-OpsLog "名簿の控えを残しました: tmp\whitelist\roster-$stamp.json（.gitignore 対象）"
}

# Paper が読む形。照合は uuid で行われ、name は表示用。
$whitelistJson = (ConvertTo-Json -InputObject @($accepted | ForEach-Object {
    [ordered]@{ uuid = $_.Uuid; name = $_.Name }
}) -Depth 3)

# ---- 適用 --------------------------------------------------------------------------------------

$failures = New-Object System.Collections.Generic.List[string]

foreach ($server in $writeTargets) {
    Write-Host ""
    Write-OpsLog "=== [$($server.Name)] $($server.Root)"

    $whitelistPath  = Join-Path $server.Root "whitelist.json"
    $propertiesPath = Join-Path $server.Root "server.properties"
    if (-not (Test-Path -LiteralPath $propertiesPath)) {
        $failures.Add("[$($server.Name)] server.properties がありません: $propertiesPath")
        continue
    }

    $before = @()
    if (Test-Path -LiteralPath $whitelistPath) {
        $before = @(ConvertFrom-JsonArray (Get-Content -LiteralPath $whitelistPath -Raw -Encoding UTF8))
    }
    Write-OpsLog "  現在の whitelist.json: $($before.Count) 件 → $($accepted.Count) 件"

    if ($DryRun) {
        Write-OpsLog "  whitelist.json を書き、RCON で whitelist reload / on を実行します" -Level DRYRUN
        if (-not $SkipEnforce) { Write-OpsLog "  server.properties へ enforce-whitelist=true を書きます" -Level DRYRUN }
        continue
    }

    # 1) 退避
    if (Test-Path -LiteralPath $whitelistPath) {
        Copy-Item -LiteralPath $whitelistPath -Destination "$whitelistPath.bak-$stamp"
    }
    Copy-Item -LiteralPath $propertiesPath -Destination "$propertiesPath.bak-$stamp"
    Write-OpsLog "  退避しました（.bak-$stamp）"

    # 2) whitelist.json を書く（BOM を付けない）
    [System.IO.File]::WriteAllText($whitelistPath, $whitelistJson, [System.Text.UTF8Encoding]::new($false))

    $verify = @(ConvertFrom-JsonArray (Get-Content -LiteralPath $whitelistPath -Raw -Encoding UTF8))
    if ($verify.Count -ne $accepted.Count) {
        $failures.Add("[$($server.Name)] 書き込み後の件数が合いません（$($verify.Count) / $($accepted.Count)）")
        continue
    }
    Write-OpsLog "  whitelist.json を書きました（$($verify.Count) 件）"

    # 3) 稼働中なら RCON で読み直させて ON にする
    $whitelistTurnedOn = $false
    if (-not $NoRcon) {
        if ([string]::IsNullOrWhiteSpace($server.RconPassword)) {
            $failures.Add("[$($server.Name)] RCON パスワード（環境変数）が未設定です")
            continue
        }
        if (-not (Test-RconReachable -HostName $server.RconHost -Port $server.RconPort -Password $server.RconPassword)) {
            Write-OpsLog "  RCON へ届きません（停止中？）。ファイルだけ書き換えます。" -Level WARN
        } else {
            $session = New-RconSession -HostName $server.RconHost -Port $server.RconPort `
                -Password $server.RconPassword -Label $server.Name
            try {
                Write-OpsLog "  reload: $(Invoke-RconCommand -Session $session -Command 'whitelist reload')"
                Write-OpsLog "  on    : $(Invoke-RconCommand -Session $session -Command 'whitelist on')"
                $listed = Invoke-RconCommand -Session $session -Command 'whitelist list'
                Write-OpsLog "  list  : $listed"
                if ($listed -match 'are (\d+) whitelisted') {
                    if ([int]$Matches[1] -ne $accepted.Count) {
                        $failures.Add("[$($server.Name)] サーバが読んだ件数が合いません（$($Matches[1]) / $($accepted.Count)）")
                    } else {
                        $whitelistTurnedOn = $true
                    }
                } else {
                    $failures.Add("[$($server.Name)] whitelist list の応答を解釈できません: $listed")
                }
            } finally { Close-RconSession -Session $session }
        }
    }

    # 4) server.properties。RCON で ON にできた場合、white-list は Paper が既に書いている。
    #    書き換えるのはそのあと（先に書くと Paper の書き直しで消える）。
    $desired = [ordered]@{}
    if (-not $whitelistTurnedOn) { $desired["white-list"] = "true" }
    if (-not $SkipEnforce)       { $desired["enforce-whitelist"] = "true" }

    if ($desired.Count -gt 0) {
        $newline = if ((Get-Content -LiteralPath $propertiesPath -Raw) -match "`r`n") { "`r`n" } else { "`n" }
        $lines = [System.Collections.Generic.List[string]] @(Get-Content -LiteralPath $propertiesPath)
        $changedKeys = @()
        foreach ($key in $desired.Keys) {
            $value = $desired[$key]
            $index = -1
            for ($i = 0; $i -lt $lines.Count; $i++) {
                if ($lines[$i] -match "^\s*$([regex]::Escape($key))\s*=") { $index = $i; break }
            }
            if ($index -ge 0) {
                if ($lines[$index] -ne "$key=$value") { $lines[$index] = "$key=$value"; $changedKeys += $key }
            } else {
                $lines.Add("$key=$value"); $changedKeys += $key
            }
        }
        if ($changedKeys.Count -gt 0) {
            [System.IO.File]::WriteAllText($propertiesPath, (($lines -join $newline) + $newline),
                [System.Text.UTF8Encoding]::new($false))
            Write-OpsLog "  server.properties: $($changedKeys -join ', ') を設定しました"
        } else {
            Write-OpsLog "  server.properties: 変更なし"
        }
    }
}

Write-Host ""
if ($failures.Count -gt 0) {
    Write-OpsLog "$($failures.Count) 件の失敗がありました。" -Level ERROR
    foreach ($failure in $failures) { Write-Host "  - $failure" }
    Write-OpsLog "退避した .bak-$stamp を戻せば元へ戻せます。" -Level ERROR
    exit 1
}

if ($DryRun) {
    Write-OpsLog "DRY RUN 完了。実行するときは -DryRun を外す。"
} else {
    Write-OpsLog "完了。$($writeTargets.Count) サーバへ $($accepted.Count) アカウントを適用しました。"
    if (-not $SkipEnforce) {
        Write-OpsLog "enforce-whitelist は【次回再起動から】効きます（名簿から外した人をその場で切断させる設定）。"
    }
    Write-OpsLog "あとから足すとき: Java 版なら各サーバのコンソールで `whitelist add <名前>`。"
    Write-OpsLog "                  統合版（先頭ドット）は Mojang に無いので、このスクリプトを再実行する。"
}
exit 0
