<#
.SYNOPSIS
    MariaDB と Redis 互換サーバの生存確認。外部ツールに依存しない。

.DESCRIPTION
    「ポートが開いているか」だけでは足りない。HuskSync が enable に失敗するのは
    接続できないときだけでなく、想定と違うサーバが応答しているときも同じなので、
    プロトコルレベルで【何が応答しているか】まで確認する。

    - Redis 側は RESP を直接喋る。PING と INFO しか使わないので実装は小さい。
      Windows ネイティブ構成では Redis 本体ではなく Garnet が応答するため、
      サーバ名を取り出して記録できるようにしてある。
    - MariaDB 側は接続直後にサーバが送ってくる初期ハンドシェイクを読むだけ。
      認証しないのでパスワードが要らず、バージョン文字列だけが取れる。
#>

Set-StrictMode -Version Latest

function Connect-TcpStream {
    <#
    .SYNOPSIS 接続できたら [pscustomobject]@{Client;Stream}、駄目なら $null。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [string] $HostName,
        [Parameter(Mandatory)] [int]    $Port,
        [int] $TimeoutMs = 3000
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        if (-not $client.ConnectAsync($HostName, $Port).Wait($TimeoutMs) -or -not $client.Connected) {
            $client.Dispose()
            return $null
        }
    } catch {
        $client.Dispose()
        return $null
    }

    $stream = $client.GetStream()
    $stream.ReadTimeout  = $TimeoutMs
    $stream.WriteTimeout = $TimeoutMs
    return [pscustomobject]@{ Client = $client; Stream = $stream }
}

function Read-AvailableBytes {
    <#
    .SYNOPSIS 応答が途切れるまで読む。長さを知らないまま読むのでタイムアウトを終端に使う。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [System.Net.Sockets.NetworkStream] $Stream,
        [int] $MaxBytes = 65536,
        [int] $QuietMs  = 250
    )

    $buffer = New-Object byte[] 4096
    # List[byte].AddRange には byte[] を渡す必要がある。PowerShell の配列スライス
    # ($buffer[0..n]) は Object[] を返すので使えない。MemoryStream なら型で悩まない。
    $out = New-Object System.IO.MemoryStream
    $deadline = (Get-Date).AddMilliseconds($Stream.ReadTimeout)

    while ((Get-Date) -lt $deadline -and $out.Length -lt $MaxBytes) {
        if (-not $Stream.DataAvailable) {
            # 最初の1バイトが来るまでは待つ。来たあとは QuietMs 無音で終端とみなす。
            if ($out.Length -gt 0) {
                Start-Sleep -Milliseconds $QuietMs
                if (-not $Stream.DataAvailable) { break }
            } else {
                Start-Sleep -Milliseconds 25
                continue
            }
        }
        $read = $Stream.Read($buffer, 0, $buffer.Length)
        if ($read -le 0) { break }
        $out.Write($buffer, 0, $read)
    }

    return $out.ToArray()
}

function ConvertTo-RespCommand {
    <#
    .SYNOPSIS 引数配列を RESP の配列形式へ組み立てる。
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)] [string[]] $Arguments)

    $sb = [System.Text.StringBuilder]::new()
    [void]$sb.Append("*$($Arguments.Count)`r`n")
    foreach ($argument in $Arguments) {
        $bytes = [System.Text.Encoding]::UTF8.GetByteCount($argument)
        [void]$sb.Append("`$$bytes`r`n$argument`r`n")
    }
    return [System.Text.Encoding]::UTF8.GetBytes($sb.ToString())
}

function Test-RedisEndpoint {
    <#
    .SYNOPSIS
        RESP を喋って PING と INFO を投げ、応答したサーバの素性を返す。
    .OUTPUTS
        @{ Reachable; SpeaksResp; RequiresAuth; Server; Version; Detail }
        Server は "Redis" / "Garnet" / "unknown"。
    #>
    [CmdletBinding()]
    param(
        [string] $HostName = "127.0.0.1",
        [int]    $Port     = 6379,
        [int]    $TimeoutMs = 3000
    )

    $result = @{
        Reachable = $false; SpeaksResp = $false; RequiresAuth = $false
        Server = "unknown"; Version = $null; Detail = $null
    }

    $connection = Connect-TcpStream -HostName $HostName -Port $Port -TimeoutMs $TimeoutMs
    if (-not $connection) {
        $result.Detail = "接続できません (Connection refused)"
        return $result
    }
    $result.Reachable = $true

    try {
        $ping = ConvertTo-RespCommand -Arguments @("PING")
        $connection.Stream.Write($ping, 0, $ping.Length)
        $connection.Stream.Flush()
        $reply = [System.Text.Encoding]::UTF8.GetString((Read-AvailableBytes -Stream $connection.Stream))

        if ($reply -match "^\+PONG") {
            $result.SpeaksResp = $true
        } elseif ($reply -match "^-NOAUTH|^-ERR.*auth") {
            # パスワードが設定されている。RESP は喋れているので生存確認としては十分。
            $result.SpeaksResp = $true
            $result.RequiresAuth = $true
            $result.Detail = "パスワード認証が有効"
            return $result
        } else {
            $result.Detail = "RESP を喋っていません (応答: " +
                ($reply -replace "`r|`n", " ").Trim() + ")"
            return $result
        }

        $info = ConvertTo-RespCommand -Arguments @("INFO", "server")
        $connection.Stream.Write($info, 0, $info.Length)
        $connection.Stream.Flush()
        $infoReply = [System.Text.Encoding]::UTF8.GetString((Read-AvailableBytes -Stream $connection.Stream))

        # Garnet は garnet_version、Redis / Valkey は redis_version を返す。
        if ($infoReply -match "garnet_version:\s*(\S+)") {
            $result.Server = "Garnet"; $result.Version = $Matches[1]
        } elseif ($infoReply -match "valkey_version:\s*(\S+)") {
            $result.Server = "Valkey"; $result.Version = $Matches[1]
        } elseif ($infoReply -match "redis_version:\s*(\S+)") {
            $result.Server = "Redis"; $result.Version = $Matches[1]
        }
    } catch {
        $result.Detail = "通信に失敗しました: $($_.Exception.Message)"
    } finally {
        $connection.Client.Dispose()
    }

    return $result
}

function Test-MysqlEndpoint {
    <#
    .SYNOPSIS
        接続直後のハンドシェイクからサーバのバージョン文字列を読む。認証はしない。
    .DESCRIPTION
        MySQL プロトコルの初期パケットは
          [3 バイト長 (LE)][1 バイト連番][1 バイト プロトコル版][NUL 終端のバージョン文字列]...
        という並びで、認証前に平文で届く。ここまで読めれば「MariaDB が生きている」と言い切れる。
    .OUTPUTS
        @{ Reachable; SpeaksMysql; IsMariaDb; Version; Detail }
    #>
    [CmdletBinding()]
    param(
        [string] $HostName = "127.0.0.1",
        [int]    $Port     = 3306,
        [int]    $TimeoutMs = 3000
    )

    $result = @{
        Reachable = $false; SpeaksMysql = $false; IsMariaDb = $false
        Version = $null; Detail = $null
    }

    $connection = Connect-TcpStream -HostName $HostName -Port $Port -TimeoutMs $TimeoutMs
    if (-not $connection) {
        $result.Detail = "接続できません (Connection refused)"
        return $result
    }
    $result.Reachable = $true

    try {
        $bytes = Read-AvailableBytes -Stream $connection.Stream -MaxBytes 1024
        if ($bytes.Length -lt 6) {
            $result.Detail = "ハンドシェイクが読めません (受信 $($bytes.Length) バイト)"
            return $result
        }

        # ホスト名を解決できない等でサーバから拒否されると ERR パケットが返る。
        if ($bytes[4] -eq 0xFF) {
            $result.Detail = "サーバがエラーパケットを返しました " +
                "(ホストが許可されていない可能性): " +
                ([System.Text.Encoding]::UTF8.GetString($bytes[7..($bytes.Length - 1)]))
            return $result
        }
        if ($bytes[4] -ne 10) {
            $result.Detail = "MySQL プロトコルではありません (protocol version=$($bytes[4]))"
            return $result
        }

        $terminator = 5
        while ($terminator -lt $bytes.Length -and $bytes[$terminator] -ne 0) { $terminator++ }
        $result.SpeaksMysql = $true
        $result.Version = [System.Text.Encoding]::UTF8.GetString($bytes[5..($terminator - 1)])
        $result.IsMariaDb = $result.Version -match "MariaDB"
    } catch {
        $result.Detail = "通信に失敗しました: $($_.Exception.Message)"
    } finally {
        $connection.Client.Dispose()
    }

    return $result
}
