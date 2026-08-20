<#
.SYNOPSIS
    GeyserExtra の custom_items.json から「毎起動パック走査で作り直せる」エントリだけを消す。

.DESCRIPTION
    統合版のカスタムアイテム名は GeyserExtra が生成する Bedrock パックの texts/*.lang から出る。
    その中身は custom_items.json の display_name をそのまま流したものなので、
    **台帳に "Wooden Sword" が残っているかぎり、Java パックへ lang を足しても名前は変わらない。**
    GeyserExtraPaper#prepopulateRegistryFromJavaPack は (baseItem, cmd) が既に台帳に在ると
    その CMD を丸ごと skip し、CustomItemScanner の更新側も表示名を持つエントリは触らないため。

    ただし custom_items.json は生成物ではなく永続台帳で、PDC 経路のエントリは
    「誰かが実物を手に持った」ときにしか作られない。全消しは事実上のデータ削除になる。
    そこでこのスクリプトは **パックから再導出できると証明できるエントリだけ**を落とす。
    判定条件の詳細は隣の prune-geyser-auto-items.py の docstring を参照。

    既定は dry-run。実削除には -Apply が要る。実削除前に「バックエンドが止まっているか」を
    check-servers-stopped.ps1 で確認する（稼働中の Paper は同じファイルを上書き保存するので、
    走らせても消した分がすぐ書き戻る）。バックアップは
    <台帳と同階層>\custom_items.backups\custom_items.<timestamp>.json に取る。

    実削除のあと、反映には次の 4 段が全部要る（1 つでも欠けると何も変わらない）:
      (1) 新しい TrinityForge-Pack.zip を GitHub release へ差し替える
          （生成は `python resourcepack\build_item_pack.py`。**PyYAML が要る**:
            `python -m pip install pyyaml`。lang もこのとき作り直される）
      (2) server.properties の resource-pack と **resource-pack-sha1 の両方**を更新する
          ← sha1 を更新しないと GeyserExtra が旧 zip をキャッシュから使い回す
      (3) Paper を起動する（ここで台帳が lang 由来の名前で作り直される）
      (4) プロキシを再起動する（geyserextra_auto.pending.zip が本番の zip に入れ替わる）

.PARAMETER RegistryPath
    custom_items.json のパス。既定は ops-config.psd1 の VelocityRoot から組み立てる。

.PARAMETER PackPath
    配布中の Java パック（zip か展開済みディレクトリ）。既定はこのリポジトリの
    resourcepack\dist\TrinityForge-Pack.zip。

.PARAMETER BackupDir
    バックアップの置き場。既定は台帳と同階層の custom_items.backups。

.PARAMETER MaxDelete
    この件数を超える削除は中断する。既定 150（2026-08-02 の実測は 94 件）。

.PARAMETER Apply
    実際に書き換える。付けなければ dry-run。

.PARAMETER SkipServerCheck
    停止確認を飛ばす。RCON も session.lock も当てにできない状況でだけ使う。

.PARAMETER Python
    python の実行ファイル。既定は python -> py -3 の順で探す。

.OUTPUTS
    終了コード 0 = 正常（dry-run 含む） / 1 = 安全弁で中断 / 2 = 引数・入出力エラー

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\prune-geyser-auto-items.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\prune-geyser-auto-items.ps1 -Apply
#>
[CmdletBinding()]
param(
    [string] $ConfigPath,
    [string] $RegistryPath,
    [string] $PackPath,
    [string] $BackupDir,
    [int]    $MaxDelete = 150,
    [switch] $Apply,
    [switch] $SkipServerCheck,
    [string] $Python
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ops/scripts -> ops -> リポジトリルート
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent

if (-not $RegistryPath) {
    . (Join-Path $PSScriptRoot "lib\Common.ps1")
    # RCON は使わないのでパスワードは要求しない。
    $config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false
    $RegistryPath = Join-Path $config.VelocityRoot `
        "plugins\Geyser-Velocity\extensions\geyserextra\custom_items.json"
}
if (-not $PackPath) {
    $PackPath = Join-Path $repoRoot "resourcepack\dist\TrinityForge-Pack.zip"
}

if (-not (Test-Path -LiteralPath $RegistryPath)) {
    Write-Error "台帳がありません: $RegistryPath"
    exit 2
}
if (-not (Test-Path -LiteralPath $PackPath)) {
    Write-Error "パックがありません: $PackPath`nresourcepack\build_item_pack.py を先に実行してください（PyYAML が要る）。"
    exit 2
}

# python の在処。ops の他スクリプトと違いここだけ python 依存なので、無いことを明示的に落とす。
if (-not $Python) {
    if (Get-Command python -ErrorAction SilentlyContinue) {
        $Python = "python"
    } elseif (Get-Command py -ErrorAction SilentlyContinue) {
        $Python = "py"
    } else {
        Write-Error "python が見つかりません。-Python <path> で指定してください。"
        exit 2
    }
}
$pythonArgs = @()
if ($Python -eq "py") { $pythonArgs += "-3" }

# 稼働中の Paper は登録のたびに custom_items.json を上書き保存する。消しても即座に
# 書き戻るどころか、こちらの書き込みと衝突して台帳が壊れうるので、実削除前だけ確認する。
if ($Apply -and -not $SkipServerCheck) {
    $checker = Join-Path $PSScriptRoot "check-servers-stopped.ps1"
    $checkArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $checker, "-Quiet")
    if ($ConfigPath) { $checkArgs += @("-ConfigPath", $ConfigPath) }
    & powershell @checkArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Error ("バックエンドが稼働中です。停止してから -Apply してください " +
                     "（稼働中に消しても GeyserExtra が上書き保存で書き戻します）。" +
                     "判定を飛ばすなら -SkipServerCheck。")
        exit 1
    }
}

$script = Join-Path $PSScriptRoot "prune-geyser-auto-items.py"
$arguments = $pythonArgs + @(
    $script,
    "--registry", $RegistryPath,
    "--pack",     $PackPath,
    "--max-delete", $MaxDelete
)
if ($BackupDir) { $arguments += @("--backup-dir", $BackupDir) }
if ($Apply)     { $arguments += "--apply" }

& $Python @arguments
exit $LASTEXITCODE
