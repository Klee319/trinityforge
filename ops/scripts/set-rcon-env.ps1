<#
.SYNOPSIS
    各バックエンドの server.properties から RCON パスワードを読み、
    ops スクリプトが期待する環境変数 (TF_RCON_<キー>_PASSWORD) をユーザー環境へ設定する。

.DESCRIPTION
    ops-config.psd1 にはパスワードを書かない規約なので、Get-OpsConfig は
    環境変数から読む。未設定だと【DryRun 以外のすべての ops スクリプトが
    1行目で例外になる】——「バッチを叩いたのに赤い文字が出て何も起きない」の正体。

    値はここでも画面にも出さない。setx ではなく .NET の SetEnvironmentVariable を使うのは、
    コマンドラインに平文が乗るとタスクマネージャや履歴から読めてしまうため。

    ⚠ 設定は【新しく開いたウィンドウ】から有効になる。今開いている cmd / PowerShell には
      反映されないので、実行後は窓を開き直すこと。

.PARAMETER Scope
    User (既定) か Machine。Machine は管理者権限が要る。

.PARAMETER WhatIf
    どのサーバのどの変数を設定するかだけ出して、書き込まない。

.EXAMPLE
    .\set-rcon-env.ps1 -WhatIf

.EXAMPLE
    .\set-rcon-env.ps1
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [string] $ConfigPath,
    [ValidateSet("User", "Machine")]
    [string] $Scope = "User"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Common.ps1")

# パスワードを読む前なので RequireRconPasswords は当然 false。
# ここで必須にすると「設定するためのスクリプトが未設定で落ちる」堂々巡りになる。
$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false

$set     = 0
$skipped = 0

foreach ($name in @($config.Servers.Keys | Sort-Object)) {
    $server  = $config.Servers[$name]
    $envName = "TF_RCON_$($name.ToUpperInvariant())_PASSWORD"
    $propsPath = Join-Path $server.Root "server.properties"

    if (-not (Test-Path -LiteralPath $propsPath)) {
        Write-OpsLog "$name : server.properties が見つかりません: $propsPath" -Level WARN
        $skipped++
        continue
    }

    # プロパティは「最初の = までがキー」。パスワードに = が入っていても壊れないよう分割は1回だけ。
    $line = Get-Content -LiteralPath $propsPath -Encoding UTF8 |
        Where-Object { $_ -match '^\s*rcon\.password\s*=' } |
        Select-Object -Last 1

    if (-not $line) {
        Write-OpsLog "$name : rcon.password の行がありません: $propsPath" -Level WARN
        $skipped++
        continue
    }

    $password = ($line -split '=', 2)[1]
    if ([string]::IsNullOrWhiteSpace($password)) {
        Write-OpsLog "$name : rcon.password が空です。server.properties 側を先に設定してください。" -Level WARN
        $skipped++
        continue
    }

    # enable-rcon が false だと、変数を入れても接続だけが延々失敗する。先に気づかせる。
    $enable = Get-Content -LiteralPath $propsPath -Encoding UTF8 |
        Where-Object { $_ -match '^\s*enable-rcon\s*=' } |
        Select-Object -Last 1
    if ($enable -and (($enable -split '=', 2)[1]).Trim() -ne "true") {
        Write-OpsLog "$name : enable-rcon が true ではありません。変数は設定しますが接続はできません。" -Level WARN
    }

    if ($PSCmdlet.ShouldProcess("$envName ($Scope)", "設定する")) {
        [Environment]::SetEnvironmentVariable($envName, $password, $Scope)
        # 同じセッションで続けて ops スクリプトを走らせられるように、プロセス側にも入れておく。
        [Environment]::SetEnvironmentVariable($envName, $password, "Process")
        Write-OpsLog "$envName を設定しました (値は表示しません / $($server.Name) : ポート $($server.RconPort))"
        $set++
    }
}

Write-Host ""
Write-OpsLog "設定 $set 件 / スキップ $skipped 件"
if ($set -gt 0) {
    Write-OpsLog "⚠ 既に開いているウィンドウには反映されません。新しいウィンドウから実行し直してください。"
}
if ($skipped -gt 0) {
    exit 1
}
exit 0
