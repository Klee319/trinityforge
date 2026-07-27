<#
.SYNOPSIS
    HuskSync の config.yml を全バックエンドで同一内容に揃える。

.DESCRIPTION
    HuskSync の同期設定は【全バックエンドで完全に一致】していないといけない。
    片方だけ同期対象が違うと、サーバを跨いだ瞬間にその項目が消えたように見える。
    3 台ぶんを手で編集すると必ずどこかがずれるので、1 つの正本から配る。

    正本は -BaseFrom で指定したサーバの【生成済み】config.yml。
    HuskSync のバージョンが変わるとキーが増減するため、テンプレートを丸写しせず
    実際に生成されたものを土台にする。

    【パスワードは引数で渡さない】。実行時に入力を求める。
    コマンド履歴にもプロセス一覧にも残らないようにするため。

.PARAMETER BaseFrom
    正本にするサーバ名（既定 dev）。そのサーバで一度 HuskSync を起動して
    config.yml を生成させておくこと。

.PARAMETER DryRun
    書き込まず、変更内容だけを出す（パスワードの入力も求めない）。

.EXAMPLE
    .\apply-husksync-config.ps1 -DryRun

.EXAMPLE
    .\apply-husksync-config.ps1 -BaseFrom dev
#>
[CmdletBinding()]
param(
    [string] $BaseFrom = "dev",
    [string] $ConfigPath,
    [string] $DatabaseName = "husksync",
    [string] $DatabaseUser = "husksync",
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")
. (Join-Path $PSScriptRoot "lib\Yaml.ps1")

$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false

$baseServer = Resolve-OpsServer -Config $config -Target $BaseFrom
$baseFile = Join-Path $baseServer.Root "plugins\HuskSync\config.yml"
if (-not (Test-Path -LiteralPath $baseFile)) {
    Write-OpsLog "正本にする config.yml がありません: $baseFile" -Level ERROR
    Write-OpsLog "$($baseServer.Name) で一度サーバを起動して生成させてください。"
    exit 1
}

Write-OpsLog "正本: $($baseServer.Name) の config.yml"

# ---- 正本へ加える変更 ------------------------------------------------------------------------
#  値の意味と根拠は ops/templates/husksync.config.yml に書いてある。

$edits = @(
    # MariaDB 側。既定は MYSQL / localhost / HuskSync / root / pa55w0rd で、
    # そのままでは必ず Connection refused か Access denied になる。
    @{ Path = @("database", "type");                          Value = "MARIADB" }
    @{ Path = @("database", "credentials", "host");           Value = "127.0.0.1" }
    @{ Path = @("database", "credentials", "database");       Value = $DatabaseName }
    @{ Path = @("database", "credentials", "username");       Value = $DatabaseUser }

    # Redis 互換サーバ (Garnet)。localhost だと IPv6 で引いて外すことがある。
    @{ Path = @("redis", "credentials", "host");              Value = "127.0.0.1" }

    # 【生成時の既定が危険なもの】
    #  game_mode: メインの world は creative。同期すると資源サーバでもクリエイティブになる。
    @{ Path = @("synchronization", "features", "game_mode");  Value = "false" }
    #  flight_status: game_mode と必ず【同じ値】にする。
    #  HuskSync 側では flight_status -> game_mode は Dependency.optional なので、
    #  game_mode だけ false にしても flight_status は同期され続ける
    #  (Identifier.java: FLIGHT_STATUS = huskSync("flight_status", true, Dependency.optional("game_mode")))。
    #  その結果「ゲームモードはサバイバルに戻るのに飛行状態だけ引き継ぐ」= サバイバルで飛べる状態になる。
    @{ Path = @("synchronization", "features", "flight_status"); Value = "false" }
    #  location: true だと資源サーバで岩盤に埋まる。既定 false だが明示しておく。
    @{ Path = @("synchronization", "features", "location");   Value = "false" }
    #  TF の図鑑・称号・Ars のマナはここに乗る。落とすと進行が飛ぶ。
    @{ Path = @("synchronization", "features", "persistent_data"); Value = "true" }
    @{ Path = @("synchronization", "features", "inventory");  Value = "true" }
    @{ Path = @("synchronization", "features", "ender_chest"); Value = "true" }
)

# TF / Ars が付け直す AttributeModifier は同期させない。理由は
# ops/templates/husksync.config.yml の「属性同期」節。
$requiredIgnoredModifiers = @("trinityforge:*", "arspaper:*")

# ---- 正本を組み立てる ------------------------------------------------------------------------

$lines = @(Get-Content -LiteralPath $baseFile)
$changes = New-Object System.Collections.Generic.List[string]

foreach ($edit in $edits) {
    $index = Find-YamlLineIndex -Lines $lines -Path $edit.Path
    if ($index -lt 0) {
        Write-OpsLog "キーが見つかりません: $($edit.Path -join '.')（HuskSync の版が変わった可能性）" -Level ERROR
        exit 1
    }
    $current = ConvertFrom-YamlScalar -Raw ($lines[$index] -replace '^[^:]*:\s*', '')
    if ($current -cne $edit.Value) {
        $indent = " " * (Get-YamlIndent -Line $lines[$index])
        $lines[$index] = "$indent$($edit.Path[-1]): $($edit.Value)"
        $changes.Add("$($edit.Path -join '.'): $current -> $($edit.Value)")
    }
}

# ignored_modifiers は「消さずに足す」。既定の 2 行を落とすと effect 由来の
# modifier まで同期しに行く。
$ignoredIndex = Find-YamlLineIndex -Lines $lines `
    -Path @("synchronization", "attributes", "ignored_modifiers")
if ($ignoredIndex -lt 0) {
    Write-OpsLog "synchronization.attributes.ignored_modifiers が見つかりません" -Level ERROR
    exit 1
}

$listEnd = $ignoredIndex
$existing = @()
for ($i = $ignoredIndex + 1; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match '^\s*-\s*(\S+)\s*$') {
        $existing += $Matches[1]
        $listEnd = $i
    } elseif ($lines[$i] -match '^\s*$') {
        continue
    } else {
        break
    }
}

$missing = @($requiredIgnoredModifiers | Where-Object { $_ -notin $existing })
if ($missing.Count -gt 0) {
    $listIndent = " " * (Get-YamlIndent -Line $lines[$ignoredIndex])
    $inserted = @($missing | ForEach-Object { "$listIndent- $_" })
    $lines = @($lines[0..$listEnd]) + $inserted + @($lines[($listEnd + 1)..($lines.Count - 1)])
    $changes.Add("ignored_modifiers に追加: $($missing -join ', ')")
}

# ---- 出力 -------------------------------------------------------------------------------------

$targets = @(foreach ($name in @($config.Servers.Keys)) { $config.Servers[$name] })

Write-Host ""
if ($changes.Count -eq 0) {
    Write-OpsLog "正本に対する変更はありません（既に全て意図した値です）"
} else {
    foreach ($change in $changes) {
        Write-OpsLog "  $change" -Level $(if ($DryRun) { "DRYRUN" } else { "INFO" })
    }
}

if ($DryRun) {
    Write-Host ""
    Write-OpsLog "配布先（実行時はここに同一内容を書きます）:" -Level DRYRUN
    foreach ($server in $targets) {
        $file = Join-Path $server.Root "plugins\HuskSync\config.yml"
        $state = if (Test-Path -LiteralPath $file) { "上書き" } else { "新規作成" }
        Write-OpsLog "  [$($server.Name)] $state : $file" -Level DRYRUN
    }
    Write-Host ""
    Write-OpsLog "実行時に MariaDB の '$DatabaseUser' ユーザーのパスワードを尋ねます。" -Level DRYRUN
    exit 0
}

# ---- パスワード ---------------------------------------------------------------------------------
#  引数でも環境変数でも受け取らない。履歴に残さないため、その場で入力させる。

Write-Host ""
Write-OpsLog "MariaDB の '$DatabaseUser' ユーザーのパスワードを入力してください（表示されません）"
$secure = Read-Host -AsSecureString "password"
$bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $plain = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
} finally {
    [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}

if ([string]::IsNullOrEmpty($plain)) {
    Write-OpsLog "パスワードが空です。中断します。" -Level ERROR
    exit 1
}

$passwordIndex = Find-YamlLineIndex -Lines $lines -Path @("database", "credentials", "password")
if ($passwordIndex -lt 0) {
    Write-OpsLog "database.credentials.password が見つかりません" -Level ERROR
    exit 1
}
$passwordIndent = " " * (Get-YamlIndent -Line $lines[$passwordIndex])
$lines[$passwordIndex] = "$passwordIndent" + "password: " + (ConvertTo-YamlSingleQuoted -Value $plain)

$content = ($lines -join "`n") + "`n"
$plain = $null

# ---- 配布 ---------------------------------------------------------------------------------------

$failures = New-Object System.Collections.Generic.List[string]

foreach ($server in $targets) {
    $dir  = Join-Path $server.Root "plugins\HuskSync"
    $file = Join-Path $dir "config.yml"
    Write-Host ""
    Write-OpsLog "--- $($server.Name) ---"

    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
        Write-OpsLog "  ディレクトリを作成しました"
    }

    if (Test-Path -LiteralPath $file) {
        # パスワードを含むので、退避は元と同じディレクトリに留める。
        $backup = "$file.bak-" + (Get-Date).ToString("yyyyMMdd_HHmmss")
        Copy-Item -LiteralPath $file -Destination $backup
        Write-OpsLog "  退避しました: $(Split-Path $backup -Leaf)"
    }

    [System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))

    # 読み直して、パスワード以外が意図どおりかを確認する（値そのものは出さない）。
    $verify = @(Get-Content -LiteralPath $file)
    $verified = $true
    foreach ($key in @("game_mode", "flight_status")) {
        $index = Find-YamlLineIndex -Lines $verify -Path @("synchronization", "features", $key)
        if ($index -lt 0 -or $verify[$index] -notmatch "$key`:\s*false") {
            $failures.Add("[$($server.Name)] 書き込み後の確認に失敗しました ($key): $file")
            $verified = $false
        }
    }
    if ($verified) {
        Write-OpsLog "  書き込みました"
    }
}

Write-Host ""
if ($failures.Count -gt 0) {
    Write-OpsLog "$($failures.Count) 件の失敗がありました。" -Level ERROR
    foreach ($failure in $failures) { Write-Host "  - $failure" }
    exit 1
}

Write-OpsLog "全バックエンドへ同一の config.yml を配布しました。"
Write-OpsLog "先に MariaDB 側で DB とユーザーを作っておくこと（RUNBOOK 手順 2-1）。"
Write-OpsLog "確認: preflight.ps1 で HuskSync 関連の指摘が消えていること。"
exit 0
