<#
.SYNOPSIS
    サーバを起動する前に、外部依存が揃っているかを確認する。

.DESCRIPTION
    HuskSync は MariaDB と Redis に繋がらないと enable に失敗し、
    「プラグインが読み込まれていないのに気付かないままサーバが動く」状態になる。
    起動してログを読んで初めて気付くのは遅いので、先に潰す。

    確認するもの:
      1. MariaDB (3306) と Redis (6379) に到達できるか
      2. HuskSync の config.yml が生成時の既定値 (root / pa55w0rd / DB名 HuskSync) のままでないか
      3. HuskSync の同期設定が全バックエンドで一致しているか、かつ危険な既定値でないか
         (location: false / game_mode: false / persistent_data: true / trinityforge:* 除外)
      4. Velocity の forwarding.secret と各バックエンドの paper-global.yml の secret が一致するか

    ジャンクションの有無と同名 jar の二重配置は sync-configs.ps1 が担当する。

.EXAMPLE
    .\preflight.ps1
#>
[CmdletBinding()]
param([string] $ConfigPath)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Rcon.ps1")
. (Join-Path $PSScriptRoot "lib\Common.ps1")
. (Join-Path $PSScriptRoot "lib\DataStore.ps1")

$config = Get-OpsConfig -Path $ConfigPath -RequireRconPasswords:$false
$issues = New-Object System.Collections.Generic.List[string]
$notes  = New-Object System.Collections.Generic.List[string]

# ---- 1. MariaDB と Redis --------------------------------------------------------------------------
#  ポートが開いているかではなく【何が応答しているか】まで見る。
#  Windows ネイティブ構成では Redis 本体ではなく Garnet が応答するのが正常。

Write-OpsLog "外部データストアを確認します"

$mysql = Test-MysqlEndpoint -HostName "127.0.0.1" -Port 3306
if ($mysql.SpeaksMysql) {
    Write-OpsLog "  OK   MariaDB 127.0.0.1:3306 ($($mysql.Version))"
    if (-not $mysql.IsMariaDb) {
        $notes.Add("3306 で応答しているのは MariaDB ではないようです（$($mysql.Version)）。" +
                   "HuskSync の database.type を実態に合わせてください。")
    }
} elseif ($mysql.Reachable) {
    $issues.Add("3306 は開いていますが MySQL プロトコルで応答しません: $($mysql.Detail)")
} else {
    $issues.Add("MariaDB (127.0.0.1:3306) へ到達できません。" +
                "HuskSync は enable に失敗します（Connection refused）。")
}

$redis = Test-RedisEndpoint -HostName "127.0.0.1" -Port 6379
if ($redis.SpeaksResp) {
    $identity = if ($redis.Version) { "$($redis.Server) $($redis.Version)" } else { $redis.Server }
    Write-OpsLog "  OK   Redis 互換 127.0.0.1:6379 ($identity)"
    if ($redis.RequiresAuth) {
        $notes.Add("6379 はパスワード認証が有効です。HuskSync の redis.credentials.password を" +
                   "設定していないと enable に失敗します。")
    }
} elseif ($redis.Reachable) {
    $issues.Add("6379 は開いていますが RESP で応答しません: $($redis.Detail)")
} else {
    $issues.Add("Redis 互換サーバ (127.0.0.1:6379) へ到達できません。" +
                "HuskSync は enable に失敗します（Connection refused）。")
}

if (-not $mysql.SpeaksMysql -or -not $redis.SpeaksResp) {
    # 構成の取り違えを切り分けられるようヒントを出す。
    # ネイティブ構成ならサービスが、WSL 構成ならディストロが見えるはず。
    # 部分一致にすると無関係なサービスを拾う (GameInputRedistService が 'redis' に当たった)。
    # サービス名の先頭でのみ照合する。
    $services = @(Get-Service -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^(mariadb|mysql|garnet|memurai|redis|valkey)' })
    if ($services.Count -gt 0) {
        $notes.Add("Windows サービスは見つかっています: " +
                   (($services | ForEach-Object { "$($_.Name)=$($_.Status)" }) -join ", ") +
                   "`n    停止しているものがあれば Start-Service で起動してください。")
    } else {
        $notes.Add("MariaDB / Garnet 相当の Windows サービスが見つかりません。" +
                   "`n    RUNBOOK 手順2（Windows ネイティブ）が未実施の可能性があります。")
        try {
            $distros = @(wsl.exe --list --quiet 2>$null |
                ForEach-Object { ($_ -replace "`0", "").Trim() } |
                Where-Object { $_ })
            $linux = @($distros | Where-Object { $_ -notmatch '^docker-desktop' })
            if ($linux.Count -gt 0) {
                $notes.Add("WSL のディストロはあります（$($linux -join ', ')）。WSL 構成で進めるなら" +
                           "`n    ディストロ内で `"sudo systemctl status mariadb redis-server`" を確認してください。")
            }
        } catch {
            # wsl.exe が無い環境は珍しくない。ネイティブ構成では不要なので黙って進む。
        }
    }
}

# ---- 2 / 3. HuskSync の config -------------------------------------------------------------------

Write-OpsLog "HuskSync の config を確認します"

# 生成時の既定値。これが残っていると必ず接続に失敗する。
$defaultCredentials = @{
    "username" = "root"
    "password" = "pa55w0rd"
    "database" = "HuskSync"
}

# キー名は 4.0.0 の生成物に合わせている。値は「あるべき状態」。
$requiredFeatures = @{
    "location"        = "false"
    "game_mode"       = "false"
    "persistent_data" = "true"
    "inventory"       = "true"
    "ender_chest"     = "true"
}

$featureSnapshots = @{}

foreach ($name in $config.Servers.Keys) {
    $server = $config.Servers[$name]
    $configFile = Join-Path $server.Root "plugins\HuskSync\config.yml"

    if (-not (Test-Path -LiteralPath $configFile)) {
        $notes.Add("[$($server.Name)] HuskSync/config.yml がまだありません " +
                   "(初回起動で生成されます): $configFile")
        continue
    }

    $lines = Get-Content -LiteralPath $configFile

    # --- 既定の資格情報が残っていないか ---
    foreach ($key in $defaultCredentials.Keys) {
        $pattern = "^\s{4}$key`:\s*$([regex]::Escape($defaultCredentials[$key]))\s*$"
        if ($lines | Where-Object { $_ -match $pattern }) {
            $issues.Add("[$($server.Name)] HuskSync の $key が生成時の既定値 " +
                        "'$($defaultCredentials[$key])' のままです。RUNBOOK 手順2で作った値に変更してください。")
        }
    }

    # --- features の値 ---
    # features ブロックの範囲だけを見る（同名キーが他所にあっても拾わないため）。
    $featureStart = ($lines | Select-String -Pattern '^\s{2}features:\s*$' | Select-Object -First 1)
    if (-not $featureStart) {
        $issues.Add("[$($server.Name)] HuskSync の synchronization.features ブロックが見つかりません。")
        continue
    }

    $snapshot = @{}
    for ($i = $featureStart.LineNumber; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        # インデントが浅くなったら features ブロックの終わり。
        if ($line -match '^\s{0,3}\S') { break }
        if ($line -match '^\s{4}([a-z_]+):\s*(\S+)\s*$') {
            $snapshot[$Matches[1]] = $Matches[2]
        }
    }
    $featureSnapshots[$server.Name] = $snapshot

    foreach ($key in $requiredFeatures.Keys) {
        if (-not $snapshot.ContainsKey($key)) {
            $issues.Add("[$($server.Name)] HuskSync の features.$key がありません。")
        } elseif ($snapshot[$key] -ne $requiredFeatures[$key]) {
            $issues.Add("[$($server.Name)] HuskSync の features.$key が " +
                        "'$($snapshot[$key])' です。'$($requiredFeatures[$key])' にしてください。" +
                        "（理由は ops/templates/husksync.config.yml）")
        }
    }

    # --- TF の attribute modifier を除外しているか ---
    if ($snapshot.ContainsKey("attributes") -and $snapshot["attributes"] -eq "true") {
        if (-not ($lines | Where-Object { $_ -match '^\s*-\s*trinityforge:\*\s*$' })) {
            $issues.Add("[$($server.Name)] attributes 同期が有効なのに ignored_modifiers に " +
                        "'trinityforge:*' がありません。TF が付け直す modifier を同期すると、" +
                        "再計算前の一瞬だけ別サーバの値が乗ります。")
        }
    }
}

# --- 全バックエンドで features が一致しているか ---
if ($featureSnapshots.Count -ge 2) {
    $names = @($featureSnapshots.Keys)
    $baseName = $names[0]
    $base = $featureSnapshots[$baseName]
    foreach ($other in $names[1..($names.Count - 1)]) {
        foreach ($key in $base.Keys) {
            $otherValue = if ($featureSnapshots[$other].ContainsKey($key)) {
                $featureSnapshots[$other][$key]
            } else { "(なし)" }
            if ($otherValue -ne $base[$key]) {
                $issues.Add("HuskSync の features.$key がサーバ間で違います: " +
                            "$baseName=$($base[$key]) / $other=$otherValue。" +
                            "サーバを跨いだ瞬間にその項目が消えたように見えます。")
            }
        }
    }
}

# ---- 4. forwarding secret ------------------------------------------------------------------------

if ($config.ContainsKey("VelocityRoot") -and $config.VelocityRoot) {
    Write-OpsLog "forwarding secret の一致を確認します"

    $secretFile = Join-Path $config.VelocityRoot "forwarding.secret"
    if (-not (Test-Path -LiteralPath $secretFile)) {
        $issues.Add("Velocity の forwarding.secret がありません: $secretFile")
    } else {
        # 末尾改行の有無で一致判定が揺れないよう trim して比べる。
        $secret = (Get-Content -LiteralPath $secretFile -Raw).Trim()
        if (-not $secret) {
            $issues.Add("forwarding.secret が空です: $secretFile")
        }

        foreach ($name in $config.Servers.Keys) {
            $server = $config.Servers[$name]
            $globalFile = Join-Path $server.Root "config\paper-global.yml"
            if (-not (Test-Path -LiteralPath $globalFile)) {
                $notes.Add("[$($server.Name)] paper-global.yml がまだありません: $globalFile")
                continue
            }

            $globalLines = Get-Content -LiteralPath $globalFile
            $velocityStart = ($globalLines | Select-String -Pattern '^\s{2}velocity:\s*$' |
                Select-Object -First 1)
            if (-not $velocityStart) {
                $issues.Add("[$($server.Name)] paper-global.yml に proxies.velocity がありません。")
                continue
            }

            $enabled = $null
            $configuredSecret = $null
            for ($i = $velocityStart.LineNumber; $i -lt $globalLines.Count; $i++) {
                $line = $globalLines[$i]
                if ($line -match '^\s{0,3}\S') { break }
                if ($line -match '^\s{4}enabled:\s*(\S+)') { $enabled = $Matches[1] }
                # 引用符を含む値でも壊れないよう、正規表現で剥がさず専用のヘルパーへ渡す。
                if ($line -match '^\s{4}secret:\s*(.*)$') {
                    $configuredSecret = ConvertFrom-YamlScalar -Raw $Matches[1]
                }
            }

            if ($enabled -ne "true") {
                $issues.Add("[$($server.Name)] paper-global.yml の proxies.velocity.enabled が " +
                            "'$enabled' です。true にしないとプロキシ経由でログインできません。")
            }
            if ([string]::IsNullOrWhiteSpace($configuredSecret)) {
                $issues.Add("[$($server.Name)] paper-global.yml の proxies.velocity.secret が空です。")
            } elseif ($configuredSecret -cne $secret) {
                $issues.Add("[$($server.Name)] forwarding secret が Velocity 側と一致しません。" +
                            "全員が 'Unable to verify player details' で入れなくなります。")
            }
        }
    }
} else {
    $notes.Add("ops-config.psd1 に VelocityRoot が無いので forwarding secret の照合をスキップしました。")
}

# ---- 結果 ------------------------------------------------------------------------------------------

Write-Host ""
foreach ($note in $notes) {
    Write-OpsLog $note -Level WARN
}

if ($issues.Count -eq 0) {
    Write-OpsLog "起動前チェックに問題はありません。"
    exit 0
}

Write-OpsLog "$($issues.Count) 件の問題を検出しました。起動前に解消してください。" -Level ERROR
foreach ($issue in $issues) {
    Write-Host "  - $issue"
}
exit 1
