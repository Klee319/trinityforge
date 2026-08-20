<#
.SYNOPSIS
  配備先の server.properties にリソースパックの配布 URL と SHA-1 を書き込む。

.DESCRIPTION
  `resource-pack` と `resource-pack-sha1` は**必ず両方**更新する。
  sha1 を据え置くと GeyserExtra の JavaPackResolver がキャッシュ済み zip を再利用し、
  URL だけ変えても「何も変わらない」状態になる（docs/agent-context/bedrock-geyser.md 参照）。

  server.properties は**起動時にしか読まれない**ので、書き込んだだけでは反映されない。
  反映には Paper の再起動が要る（統合版まで通すならプロキシの再起動も）。

.PARAMETER Url
  リリースの zip の直リンク。

.PARAMETER Sha1
  その zip の SHA-1（40 桁の 16 進）。

.PARAMETER Server
  書き換える配備先。既定は Main_Server（現状パックを配っているのはここだけ）。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\set-resource-pack.ps1 `
    -Url "https://github.com/Klee319/trinityforge-pack/releases/download/pack-XXXX/TrinityForge-Pack.zip" `
    -Sha1 "0123456789abcdef0123456789abcdef01234567"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Url,
    [Parameter(Mandatory = $true)][string]$Sha1,
    [string]$Server = 'Main_Server',
    [string]$VelocityRoot = 'D:\game\minecraft\PaperServer\Velocity_for_TF'
)

$ErrorActionPreference = 'Stop'

if ($Sha1 -notmatch '^[0-9a-fA-F]{40}$') {
    throw "Sha1 が 40 桁の 16 進ではありません: $Sha1"
}
if ($Url -notmatch '^https://') {
    throw "Url が https:// で始まっていません: $Url"
}

$path = Join-Path (Join-Path $VelocityRoot $Server) 'server.properties'
if (-not (Test-Path $path)) {
    throw "server.properties が見つかりません: $path"
}

# server.properties は Java の Properties 形式なので ':' を '\:' へ退避する。
$escapedUrl = $Url -replace ':', '\:'

$lines = Get-Content -LiteralPath $path
$seenUrl = $false
$seenSha = $false
$updated = foreach ($line in $lines) {
    if ($line -match '^resource-pack=') { $seenUrl = $true; "resource-pack=$escapedUrl" }
    elseif ($line -match '^resource-pack-sha1=') { $seenSha = $true; "resource-pack-sha1=$($Sha1.ToLowerInvariant())" }
    else { $line }
}
if (-not $seenUrl -or -not $seenSha) {
    throw "resource-pack / resource-pack-sha1 の行が見つかりません: $path"
}

# 書き換え前の値を退避しておく（戻したくなったときに直近 1 世代だけ残す）。
Copy-Item -LiteralPath $path -Destination "$path.bak" -Force

# Paper は server.properties を Latin-1 で読むので BOM を付けない。
[System.IO.File]::WriteAllLines($path, $updated, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "更新しました: $path"
Write-Host "  resource-pack      = $Url"
Write-Host "  resource-pack-sha1 = $($Sha1.ToLowerInvariant())"
Write-Host "反映には $Server の再起動が必要です（server.properties は起動時にしか読まれない）。"
