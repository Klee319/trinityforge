<#
.SYNOPSIS
    Source RCON クライアント (依存ゼロ・PowerShell だけで完結)。

.DESCRIPTION
    再起動もリセットもクリーンシャットダウンで統一するため、稼働中のサーバへ
    「警告を流す」「stop を送る」手段が要る。mcrcon などの外部バイナリを増やさずに済むよう、
    Source RCON プロトコル (Valve 仕様、Minecraft サーバが実装しているもの) を直接喋る。

    パケット構造 (すべてリトルエンディアン):
        int32 size      … これ以降のバイト数
        int32 requestId
        int32 type      … 3=認証, 2=コマンド実行, 0=レスポンス, -1=認証失敗
        byte[] body     … ASCII, NUL 終端
        byte  padding   … NUL

.NOTES
    RCON はパスワードを平文でネットワークに流す。127.0.0.1 以外に開けてはならない。
    詳細は ops/SECURITY.md を参照。
#>

Set-StrictMode -Version Latest

# 認証失敗時に Minecraft サーバが返す requestId。
$script:RconAuthFailureId = -1

class RconSession {
    [System.Net.Sockets.TcpClient] $Client
    [System.Net.Sockets.NetworkStream] $Stream
    [int] $NextRequestId = 1
    [string] $Label
}

function New-RconSession {
    <#
    .SYNOPSIS 接続して認証まで済ませた RCON セッションを返す。失敗すれば throw する。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [string] $HostName,
        [Parameter(Mandatory)] [int]    $Port,
        [Parameter(Mandatory)] [string] $Password,
        [string] $Label = "server",
        [int]    $TimeoutSeconds = 10
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.ConnectAsync($HostName, $Port)
        if (-not $connect.Wait([TimeSpan]::FromSeconds($TimeoutSeconds))) {
            throw "RCON 接続がタイムアウトしました ($Label : ${HostName}:${Port})"
        }
    } catch {
        $client.Dispose()
        throw "RCON へ接続できません ($Label : ${HostName}:${Port}): $($_.Exception.Message)"
    }

    $session = [RconSession]::new()
    $session.Client = $client
    $session.Stream = $client.GetStream()
    $session.Stream.ReadTimeout  = $TimeoutSeconds * 1000
    $session.Stream.WriteTimeout = $TimeoutSeconds * 1000
    $session.Label = $Label

    $authId = $session.NextRequestId++
    Send-RconPacket -Session $session -RequestId $authId -Type 3 -Body $Password
    $response = Receive-RconPacket -Session $session
    if ($response.RequestId -eq $script:RconAuthFailureId) {
        Close-RconSession -Session $session
        throw "RCON 認証に失敗しました ($Label)。server.properties の rcon.password と環境変数を確認してください。"
    }

    return $session
}

function Invoke-RconCommand {
    <#
    .SYNOPSIS コマンドを1つ実行し、サーバの応答文字列を返す。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [RconSession] $Session,
        [Parameter(Mandatory)] [string]      $Command
    )

    $id = $Session.NextRequestId++
    Send-RconPacket -Session $Session -RequestId $id -Type 2 -Body $Command
    return (Receive-RconPacket -Session $Session).Body
}

function Close-RconSession {
    [CmdletBinding()]
    param([RconSession] $Session)

    if ($null -eq $Session) { return }
    if ($null -ne $Session.Stream) { $Session.Stream.Dispose() }
    if ($null -ne $Session.Client) { $Session.Client.Dispose() }
}

function Test-RconReachable {
    <#
    .SYNOPSIS 接続・認証だけ試して真偽を返す。サーバの生死判定に使う (例外を投げない)。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [string] $HostName,
        [Parameter(Mandatory)] [int]    $Port,
        [Parameter(Mandatory)] [string] $Password,
        [int] $TimeoutSeconds = 3
    )

    try {
        $session = New-RconSession -HostName $HostName -Port $Port -Password $Password `
            -TimeoutSeconds $TimeoutSeconds
        Close-RconSession -Session $session
        return $true
    } catch {
        return $false
    }
}

# ---- 内部 -------------------------------------------------------------------------------------

function Send-RconPacket {
    param(
        [Parameter(Mandatory)] [RconSession] $Session,
        [Parameter(Mandatory)] [int]         $RequestId,
        [Parameter(Mandatory)] [int]         $Type,
        [Parameter(Mandatory)] [AllowEmptyString()] [string] $Body
    )

    $bodyBytes = [System.Text.Encoding]::ASCII.GetBytes($Body)
    # size は requestId(4) + type(4) + body + NUL + NUL
    $size = 4 + 4 + $bodyBytes.Length + 2

    $buffer = [System.IO.MemoryStream]::new()
    $writer = [System.IO.BinaryWriter]::new($buffer)
    try {
        $writer.Write([int]$size)
        $writer.Write([int]$RequestId)
        $writer.Write([int]$Type)
        $writer.Write($bodyBytes)
        $writer.Write([byte]0)
        $writer.Write([byte]0)
        $writer.Flush()
        $bytes = $buffer.ToArray()
        $Session.Stream.Write($bytes, 0, $bytes.Length)
        $Session.Stream.Flush()
    } finally {
        $writer.Dispose()
        $buffer.Dispose()
    }
}

function Receive-RconPacket {
    param([Parameter(Mandatory)] [RconSession] $Session)

    $sizeBytes = Read-RconExactly -Session $Session -Count 4
    $size = [BitConverter]::ToInt32($sizeBytes, 0)
    if ($size -lt 10 -or $size -gt 4110) {
        throw "RCON の応答パケット長が不正です ($size バイト)"
    }

    $payload = Read-RconExactly -Session $Session -Count $size
    $requestId = [BitConverter]::ToInt32($payload, 0)
    $type      = [BitConverter]::ToInt32($payload, 4)
    # 末尾 2 バイトは NUL 2つ。
    $bodyLength = $size - 10
    $body = if ($bodyLength -gt 0) {
        [System.Text.Encoding]::UTF8.GetString($payload, 8, $bodyLength)
    } else { "" }

    return [pscustomobject]@{ RequestId = $requestId; Type = $type; Body = $body }
}

function Read-RconExactly {
    <#
    .SYNOPSIS 指定バイト数を読み切る。TCP は分割して届くので単発 Read では足りない。
    #>
    param(
        [Parameter(Mandatory)] [RconSession] $Session,
        [Parameter(Mandatory)] [int]         $Count
    )

    $buffer = [byte[]]::new($Count)
    $offset = 0
    while ($offset -lt $Count) {
        $read = $Session.Stream.Read($buffer, $offset, $Count - $offset)
        if ($read -le 0) {
            throw "RCON 接続が応答の途中で切断されました ($($Session.Label))"
        }
        $offset += $read
    }
    return $buffer
}
