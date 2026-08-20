<#
.SYNOPSIS
    経済プラグイン一式（VaultUnlocked / Jecon / JeconCacheName / PlaceholderAPI）を
    全バックエンドへ配備し、Jecon の config.yml を「3 台で 1 つの MariaDB を共有する」設定で配る。

.DESCRIPTION
    やること（この順番でしかやらない）:
      A. 検査だけする   … 配布元 jar の存在／同名 jar の二重配置／テンプレートの中身／
                          plugins ディレクトリの存在。1 つでも欠ければ【何も書かずに中断】する
      B. 稼働判定       … ops\scripts\check-servers-stopped.ps1 に委譲する。1 台でも動いていたら中断
      C. 退避           … これから上書きする jar と config を plugins\.economy-backups\<日時>\ へ
      D. jar をコピー   … 全バックエンドぶん
      E. config を書く  … D が 1 本でも失敗したらここまで来ない（config と jar のちぐはぐを作らない）
      F. 後始末         … plugins\.paper-remapped\<同名> を消す（Paper の再マップ済みキャッシュ）

    ⚠ 稼働中のサーバへ jar を置くと、JVM が未ロードのクラスを読みに行った瞬間に
      NoClassDefFoundError になる。/reload では直らず、JVM の完全な stop -> start が唯一の復旧手段。
      だから B は飛ばせない（-DryRun でも判定する。「今すぐ配備できるか」を確かめるのが空撃ちの目的で、
      稼働中に空撃ちを通すと、その出力を見て本番を叩くことになる）。

    ⚠ 3 台で共有しているのは【MariaDB の jecon データベース】であって config ファイルではない。
      plugins\Jecon はサーバごとの実体ディレクトリなので、同じ内容を 3 回書く。
      （ジャンクションで実体共有しているのは plugins\TrinityForge だけ。）

    ⚠ このスクリプトは D:\game 配下へ書き込むので、エージェントではなく人が実行する。

.PARAMETER SourceDir
    配布元の jar 置き場。既定は Test_1.21.11 の plugins（ユーザー指定の所在）。

.PARAMETER TemplatePath
    Jecon config.yml の正本。既定は ops\templates\jecon.config.yml。

.PARAMETER PlaceholderApiJar
    PlaceholderAPI の jar を SourceDir 以外から入れるとき。省略時は SourceDir から探し、
    見つからなければ【警告して残り 3 本だけ配る】（PAPI は必須ではない）。

.PARAMETER DryRun
    退避もコピーも書き込みもせず、計画だけ出す。パスワードも尋ねない。

.EXAMPLE
    .\install-economy-plugins.ps1 -DryRun

.EXAMPLE
    .\install-economy-plugins.ps1
#>
[CmdletBinding()]
param(
    [string] $ConfigPath,
    [string] $SourceDir = "D:\game\minecraft\PaperServer\Test_1.21.11\plugins",
    [string] $TemplatePath,
    [string] $PlaceholderApiJar,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")

# 配るもの。Match はファイル名の前方一致（版番号が上がっても拾えるように）。
#   ⚠ 導入順序の理由は RUNBOOK 手順 19 に書いてある。ファイルのコピー順は無関係
#     （Bukkit が plugin.yml の depend を見て読み込み順を決める）。
$ECONOMY_PLUGINS = @(
    @{ Match = "VaultUnlocked";  Required = $true;  Note = "経済 API の口だけを提供する（Vault の後継）" }
    @{ Match = "Jecon-";         Required = $true;  Note = "経済本体。残高は MariaDB の jecon DB" }
    @{ Match = "JeconCacheName"; Required = $true;  Note = "Jecon のアドオン（名前解決キャッシュ）" }
    @{ Match = "PlaceholderAPI"; Required = $false; Note = "入れると TF の trinityforge 拡張が登録される" }
)

$PAPI_REPO = "https://repo.extendedclip.com/releases/me/clip/placeholderapi/"

# 置換対象。テンプレートに実パスワードを書かないための唯一の穴。
$PASSWORD_TOKEN = '"__SET_ME__"'

if (-not $TemplatePath) {
    # $PSScriptRoot は ops\scripts。ops まで 1 つ上がる。
    $opsRoot = Split-Path $PSScriptRoot -Parent
    $TemplatePath = Join-Path $opsRoot "templates\jecon.config.yml"
}

function Test-BackendsStopped {
    <#
    .SYNOPSIS バックエンドが 1 台も動いていないかを check-servers-stopped.ps1 に判定させる。
    .DESCRIPTION
        判定を自作しない。cmd から session.lock を開ける定番イディオムは共有モードで開いてしまい
        【稼働中を停止中と誤判定する】ことが実測されている（docs/agent-context/ops-build-deploy.md）。
        誤って「停止中」と答える代償が非対称（NoClassDefFoundError・JVM 再起動以外に復旧なし）なので、
        軽い判定に置き換えてはいけない。
    #>
    param([string] $OpsConfigPath)

    $gate = Join-Path $PSScriptRoot "check-servers-stopped.ps1"
    if (-not (Test-Path -LiteralPath $gate)) {
        throw "中断: 稼働判定スクリプトがありません: $gate"
    }

    $psArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $gate)
    if ($OpsConfigPath) { $psArgs += @("-ConfigPath", $OpsConfigPath) }

    # 子プロセスの stderr をパイプラインへ落とすので、ここだけ Stop を外す。
    # そうしないと「子が正しく稼働中を報告した」こと自体が親の終了エラーになる。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $code = 1
    try {
        & powershell.exe @psArgs 2>&1 | ForEach-Object { Write-Host "  $_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    return ($code -eq 0)
}

$config  = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false
$targets = @(foreach ($name in @($config.Servers.Keys)) { $config.Servers[$name] })

Write-OpsLog "配布元:       $SourceDir"
Write-OpsLog "config 正本:  $TemplatePath"
Write-OpsLog ("配布先:       " + (($targets | ForEach-Object { $_.Name }) -join ", ") + " の $($targets.Count) 台")

# ---- A. 検査 -------------------------------------------------------------------------------------
#  ここを抜けるまで 1 バイトも書かない。

if (-not (Test-Path -LiteralPath $SourceDir)) {
    throw "中断: 配布元がありません: $SourceDir`n-SourceDir で jar の置き場を指定してください。"
}
if (-not (Test-Path -LiteralPath $TemplatePath)) {
    throw "中断: Jecon config の正本がありません: $TemplatePath"
}

$template = [System.IO.File]::ReadAllText($TemplatePath)

# テンプレートが「単体サーバ用」に書き換わっていたら止める。
#  sqlite に戻ると【サーバごとに別の財布】になり、lazyWrite: true だと 3 台で残高がずれる。
#  どちらもエラーは出ず、プレイヤーの申告でしか気づけない壊れ方をする。
foreach ($required in @($PASSWORD_TOKEN, "type: mysql", "lazyWrite: false")) {
    if (-not $template.Contains($required)) {
        throw "中断: 正本に『$required』がありません: $TemplatePath`n" +
              "3 台共有の前提（mysql / lazyWrite 無効 / パスワードは正本に書かない）が崩れています。"
    }
}

$available = @(Get-ChildItem -LiteralPath $SourceDir -Filter "*.jar" -File)
if ($PlaceholderApiJar) {
    if (-not (Test-Path -LiteralPath $PlaceholderApiJar)) {
        throw "中断: -PlaceholderApiJar のファイルがありません: $PlaceholderApiJar"
    }
    $available += @(Get-Item -LiteralPath $PlaceholderApiJar)
}

$selected = @()
$missingRequired = @()
$missingOptional = @()
foreach ($entry in $ECONOMY_PLUGINS) {
    $hits = @($available | Where-Object { $_.Name -like "$($entry.Match)*" })
    if ($hits.Count -eq 0) {
        if ($entry.Required) { $missingRequired += $entry } else { $missingOptional += $entry }
        continue
    }
    if ($hits.Count -gt 1) {
        # どれを配るかが暗黙に決まるのを許さない。古い版を退けてから実行させる。
        throw "中断: 『$($entry.Match)』に一致する jar が $($hits.Count) 件あります。" +
              "配る 1 本だけを残してください:`n  " + (($hits.Name) -join "`n  ")
    }
    $selected += [pscustomobject]@{ File = $hits[0]; Note = $entry.Note }
}

Write-Host ""
Write-OpsLog "--- 配る jar ($($selected.Count) 件) ---"
foreach ($item in $selected) {
    Write-OpsLog ("  {0,-40} {1}" -f $item.File.Name, $item.Note)
}

if ($missingOptional.Count -gt 0) {
    Write-Host ""
    foreach ($entry in $missingOptional) {
        Write-OpsLog "見つかりません（任意）: $($entry.Match)* — $($entry.Note)" -Level WARN
    }
    Write-OpsLog "PlaceholderAPI の入手先: $PAPI_REPO" -Level WARN
    Write-OpsLog ("PAPI が無い間、TF 本体は PlaceholderAPI 拡張の登録だけをスキップして起動する" +
        "（落ちない）。あとで -PlaceholderApiJar <path> を付けて配り直せばよい。") -Level WARN
}
if ($missingRequired.Count -gt 0) {
    $names = ($missingRequired | ForEach-Object { "$($_.Match)*" }) -join ", "
    throw "中断: 必須の jar が $($missingRequired.Count) 件見つかりません: $names`n" +
          "1 本でも欠けたまま配ると、Vault 無しで Jecon だけ、のような中途半端な状態になります。"
}

# plugins ディレクトリの存在と、同名プラグインの二重配置。
#  過去に ArsPaper.jar と ArsPaper-1.0.0.jar が同居して "Ambiguous plugin name" で
#  起動不能になった前科がある。配ってから気づくと 3 台とも上がらない。
$conflicts = @()
foreach ($backend in $targets) {
    $pluginsDir = Join-Path $backend.Root "plugins"
    if (-not (Test-Path -LiteralPath $pluginsDir)) {
        throw "中断: plugins がありません: $pluginsDir`n" +
              "ops-config.psd1 の Servers.$($backend.Name).Root が実環境と食い違っていないか確認してください。"
    }
    $existing = @(Get-ChildItem -LiteralPath $pluginsDir -Filter "*.jar" -File)
    foreach ($item in $selected) {
        $base = Get-PluginBaseName $item.File.Name
        $rivals = @($existing | Where-Object {
            $_.Name -ne $item.File.Name -and (Get-PluginBaseName $_.Name) -eq $base
        })
        foreach ($rival in $rivals) {
            $conflicts += "[$($backend.Name)] $($item.File.Name) と $($rival.Name) が同名プラグイン扱いになります"
        }
    }
}
if ($conflicts.Count -gt 0) {
    throw ("中断: 同名プラグインの jar が二重配置になります（Paper が Ambiguous plugin name で起動しません）:`n  " +
        ($conflicts -join "`n  "))
}

# ---- B. 稼働判定 ---------------------------------------------------------------------------------

Write-Host ""
Write-OpsLog "--- バックエンドが停止しているか ---"
if (-not (Test-BackendsStopped -OpsConfigPath $ConfigPath)) {
    Write-Host ""
    Write-OpsLog ("中断: 稼働中のバックエンドがあります。jar は 1 本も置いていません。" +
        "稼働中に置くと NoClassDefFoundError になり、JVM を落とすまで戻せません。") -Level ERROR
    Write-OpsLog "先に停止してください: launch\stop-all.cmd" -Level ERROR
    exit 1
}

# ---- 配布する config を組み立てる ----------------------------------------------------------------
#  パスワードは正本にもコマンドラインにも置かない。環境変数（自動化・自己テスト用）か、
#  その場の入力（通常運用）から取る。

$content = $null
if (-not $DryRun) {
    $password = [Environment]::GetEnvironmentVariable("TF_JECON_DB_PASSWORD")
    if ([string]::IsNullOrEmpty($password)) {
        Write-Host ""
        Write-OpsLog "MariaDB の 'jecon' ユーザーのパスワードを入力してください（表示されません）"
        $secure = Read-Host -AsSecureString "password"
        $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try {
            $password = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
        } finally {
            [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        }
    }
    if ([string]::IsNullOrEmpty($password) -or $password -eq "__SET_ME__") {
        Write-OpsLog "パスワードが空、またはプレースホルダのままです。中断します。" -Level ERROR
        exit 1
    }

    $content = $template.Replace($PASSWORD_TOKEN, (ConvertTo-YamlSingleQuoted -Value $password))
    $password = $null
    if ($content.Contains("__SET_ME__")) {
        throw "中断: プレースホルダが残ったままの config を配ろうとしました（置換に失敗）。"
    }
}

# ---- C〜F. 退避 -> jar -> config -> 後始末 --------------------------------------------------------

$stamp = (Get-Date).ToString("yyyyMMdd_HHmmss")

if ($DryRun) {
    Write-Host ""
    Write-OpsLog "実行時にやること:" -Level DRYRUN
    foreach ($backend in $targets) {
        $pluginsDir = Join-Path $backend.Root "plugins"
        Write-OpsLog "  [$($backend.Name)] $pluginsDir" -Level DRYRUN
        foreach ($item in $selected) {
            $dest  = Join-Path $pluginsDir $item.File.Name
            $state = if (Test-Path -LiteralPath $dest) { "上書き（退避してから）" } else { "新規" }
            Write-OpsLog "    jar    $($item.File.Name) : $state" -Level DRYRUN
        }
        $jeconFile = Join-Path $pluginsDir "Jecon\config.yml"
        $state = if (Test-Path -LiteralPath $jeconFile) { "上書き（退避してから）" } else { "新規作成" }
        Write-OpsLog "    config Jecon\config.yml : $state" -Level DRYRUN
    }
    Write-Host ""
    Write-OpsLog ("実行時に MariaDB の 'jecon' ユーザーのパスワードを尋ねます" +
        "（環境変数 TF_JECON_DB_PASSWORD があればそれを使う）。") -Level DRYRUN
    Write-OpsLog "先に RUNBOOK 手順 19-2 の SQL で DB とユーザーを作っておくこと。" -Level DRYRUN
    exit 0
}

# C. 退避。ここで失敗したら以降へ進まない（$ErrorActionPreference = Stop）。
Write-Host ""
Write-OpsLog "--- 退避 ---"
foreach ($backend in $targets) {
    $pluginsDir = Join-Path $backend.Root "plugins"
    $backupDir  = Join-Path $pluginsDir ".economy-backups\$stamp"

    $toBackup = @()
    foreach ($item in $selected) {
        $dest = Join-Path $pluginsDir $item.File.Name
        if (Test-Path -LiteralPath $dest) { $toBackup += $dest }
    }
    $jeconFile = Join-Path $pluginsDir "Jecon\config.yml"
    if (Test-Path -LiteralPath $jeconFile) { $toBackup += $jeconFile }

    if ($toBackup.Count -eq 0) {
        Write-OpsLog "  [$($backend.Name)] 退避するものはありません（新規導入）"
        continue
    }
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    foreach ($path in $toBackup) {
        Copy-Item -LiteralPath $path -Destination $backupDir -Force
        Write-OpsLog "  [$($backend.Name)] $(Split-Path $path -Leaf) -> .economy-backups\$stamp"
    }
}

# D. jar。config より先に置く（config と jar がちぐはぐな状態を作らないため）。
Write-Host ""
Write-OpsLog "--- jar ---"
foreach ($backend in $targets) {
    $pluginsDir = Join-Path $backend.Root "plugins"
    foreach ($item in $selected) {
        Copy-Item -LiteralPath $item.File.FullName -Destination (Join-Path $pluginsDir $item.File.Name) -Force
        Write-OpsLog "  [$($backend.Name)] $($item.File.Name)"
    }
}

# E. config。3 台に同じ内容を書く（共有しているのは DB であってファイルではない）。
Write-Host ""
Write-OpsLog "--- Jecon config.yml ---"
foreach ($backend in $targets) {
    $jeconDir  = Join-Path $backend.Root "plugins\Jecon"
    $jeconFile = Join-Path $jeconDir "config.yml"

    if (-not (Test-Path -LiteralPath $jeconDir)) {
        New-Item -ItemType Directory -Path $jeconDir -Force | Out-Null
    } elseif (Test-ReparsePoint -Path $jeconDir) {
        # 想定外。plugins\Jecon はサーバごとの実体のはず。共有されていると 3 回同じ物を書くだけになる。
        Write-OpsLog "  [$($backend.Name)] plugins\Jecon がジャンクションです（想定外。共有先を確認すること）" -Level WARN
    }

    # BOM を付けない。yml 途中の BOM は SnakeYAML が文書境界と誤読し、Bukkit 経由では NPE にしか見えない。
    [System.IO.File]::WriteAllText($jeconFile, $content, [System.Text.UTF8Encoding]::new($false))
    Write-OpsLog "  [$($backend.Name)] 書き込みました（パスワードは表示しません）"
}
$content = $null

# F. Paper の再マップ済みキャッシュ。ファイル名が同じまま中身が変わると古いものが効き続ける。
Write-Host ""
Write-OpsLog "--- .paper-remapped の掃除 ---"
foreach ($backend in $targets) {
    $remapped = Join-Path $backend.Root "plugins\.paper-remapped"
    if (-not (Test-Path -LiteralPath $remapped)) { continue }
    foreach ($item in $selected) {
        $stale = Join-Path $remapped $item.File.Name
        if (Test-Path -LiteralPath $stale) {
            Remove-FileSafely -Path $stale
        }
    }
}

Write-Host ""
Write-OpsLog "配備しました。次にやること:"
Write-OpsLog "  1. RUNBOOK 手順 19-2 の SQL で jecon DB とユーザーを作っておく（未実施なら Jecon が繋げない）"
Write-OpsLog "  2. launch\start-all.cmd で起動する"
Write-OpsLog "  3. 各サーバで /vault-info と /balance を撃ち、3 台で残高が一致することを確認する（手順 19-6）"
exit 0
