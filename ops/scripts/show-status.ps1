<#
.SYNOPSIS
    何が上がっているかを一覧する。停止/起動の前後で使う。

.DESCRIPTION
    プロセスの有無だけでなく、3306 / 6379 に【何がいるか】をプロトコルで確かめる。
    ポートが開いているかだけでは、別のプロセスが掴んでいる場合に気付けない。

    stop.flag も併せて出す。「起動したのにすぐ落ちる」ときはここが原因のことが多い
    （server-loop.cmd は stop.flag があると上げ直さない）。

.EXAMPLE
    .\show-status.ps1
#>
[CmdletBinding()]
param(
    [string] $ConfigPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Common.ps1")
. (Join-Path $PSScriptRoot "lib\DataStore.ps1")

$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false

# ---- Windows サービス ---------------------------------------------------------------------------

Write-Host ""
Write-Host "=== Windows サービス ==="
$mariadb = Get-Service -Name "MariaDB" -ErrorAction SilentlyContinue
if ($mariadb) {
    Write-Host ("  {0,-12} : {1}" -f "MariaDB", $mariadb.Status)
} else {
    Write-Host ("  {0,-12} : サービスが見つかりません" -f "MariaDB")
}

# ---- プロセス -----------------------------------------------------------------------------------
#  java.exe はどれも同じ名前なので、コマンドラインでどのサーバかを見分ける。

Write-Host ""
Write-Host "=== プロセス ==="

$garnet = @(Get-Process -Name "GarnetServer" -ErrorAction SilentlyContinue)
Write-Host ("  {0,-12} : {1}" -f "Garnet",
    $(if ($garnet.Count -gt 0) { "PID $(($garnet.Id) -join ', ')" } else { "停止" }))

$pids = @{}
foreach ($process in @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue)) {
    $commandLine = $process.CommandLine
    if (-not $commandLine) { continue }
    # velocity の判定を先に置く。バックエンドのディレクトリ名より特徴的なので。
    if ($commandLine -match 'velocity[-\w.]*\.jar') { $pids["velocity"] = $process.ProcessId; continue }
    foreach ($name in @($config.Servers.Keys)) {
        $server = $config.Servers[$name]
        $leaf = Split-Path $server.Root -Leaf
        if ($commandLine -match [regex]::Escape($leaf)) { $pids[$server.Name] = $process.ProcessId }
    }
}

$processOrder = @()
foreach ($name in @("Main", "Resource", "Dev")) {
    if ($config.Servers.ContainsKey($name)) { $processOrder += $config.Servers[$name].Name }
}
$processOrder += "velocity"

foreach ($label in $processOrder) {
    Write-Host ("  {0,-12} : {1}" -f $label,
        $(if ($pids.ContainsKey($label)) { "PID $($pids[$label])" } else { "停止" }))
}

# ---- データストア -------------------------------------------------------------------------------
#  Reachable なのに Speaks* が False なら、そのポートは別のプロセスが使っている。

Write-Host ""
Write-Host "=== データストア（プロトコルで確認） ==="

$mysql = Test-MysqlEndpoint
Write-Host ("  {0,-12} : {1}" -f "MariaDB 3306", $(
    if ($mysql.SpeaksMysql) { $mysql.Version }
    elseif ($mysql.Reachable) { "応答するが MySQL プロトコルではない（別プロセス）" }
    else { "到達できません" }))

$redis = Test-RedisEndpoint
Write-Host ("  {0,-12} : {1}" -f "Redis 6379", $(
    if ($redis.SpeaksResp) { "$($redis.Server) $($redis.Version)" }
    elseif ($redis.Reachable) { "応答するが RESP ではない（別プロセス）" }
    else { "到達できません" }))

# ---- RCON --------------------------------------------------------------------------------------
#  プロセスが生きていても、起動途中は RCON がまだ開いていない。

Write-Host ""
Write-Host "=== stop.flag（あると server-loop が起動し直しません） ==="
foreach ($name in @($config.Servers.Keys)) {
    $server = $config.Servers[$name]
    $flag = Join-Path $server.Root "stop.flag"
    Write-Host ("  {0,-12} : {1}" -f $server.Name,
        $(if (Test-Path -LiteralPath $flag) { "あり（起動し直しません）" } else { "なし" }))
}

Write-Host ""
exit 0
