<#
.SYNOPSIS
    Redis / Garnet と RESP で会話する最小のクライアント。外部ツール (redis-cli) に依存しない。

.DESCRIPTION
    生存確認だけを行う DataStore.ps1 と違い、こちらは【値を読んで消す】ので、
    宣言された長さぶんを正確に読む必要がある。

    ここを「無音になるまで読む」(Read-AvailableBytes) で書くと必ず壊れる:
      - HuskSync のスナップショットは 1 件 10〜30KB あり、1 回の Read では届かない
      - 値の中身は JSON / バイナリで、CRLF がそのまま入っている。
        行区切りで読むと値の途中で切れて、なお「読めたつもり」になる
    そのため bulk string は必ず宣言長ぶんだけ読み、byte[] のまま返す。

    HuskSync が Redis に置くキーの読み方は Get-HuskSyncRedisSettings を参照。
#>

Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot "DataStore.ps1")
. (Join-Path $PSScriptRoot "Yaml.ps1")

function Read-RedisBytes {
    <#
    .SYNOPSIS 指定バイト数ちょうど読む。足りなければ届くまで待つ。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [System.IO.Stream] $Stream,
        [Parameter(Mandatory)] [int] $Count
    )

    $buffer = New-Object byte[] $Count
    $filled = 0
    while ($filled -lt $Count) {
        $read = $Stream.Read($buffer, $filled, $Count - $filled)
        if ($read -le 0) {
            throw "Redis との接続が切れました (期待 $Count バイト中 $filled バイトで途切れた)"
        }
        $filled += $read
    }
    return ,$buffer
}

function Read-RedisLine {
    <#
    .SYNOPSIS CRLF までを 1 行として読む。型行と長さ行にしか使わない。
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)] [System.IO.Stream] $Stream)

    $out = New-Object System.IO.MemoryStream
    while ($true) {
        $b = $Stream.ReadByte()
        if ($b -lt 0) { throw "Redis との接続が切れました (応答の途中)" }
        if ($b -eq 10) { break }
        $out.WriteByte([byte]$b)
    }
    $bytes = $out.ToArray()
    if ($bytes.Length -gt 0 -and $bytes[$bytes.Length - 1] -eq 13) {
        $bytes = $bytes[0..($bytes.Length - 2)]
    }
    return [System.Text.Encoding]::UTF8.GetString($bytes)
}

function Read-RedisReply {
    <#
    .SYNOPSIS
        RESP の応答を 1 つ読む。
    .OUTPUTS
        単純文字列 -> [string] / 整数 -> [int64] / bulk -> [byte[]] (nil は $null) /
        配列 -> [object[]] (nil は $null)。bulk を文字列にするのは呼び出し側の責任
        (バイナリが入りうるので、ここで勝手に UTF8 デコードしない)。
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)] [System.IO.Stream] $Stream)

    $line = Read-RedisLine -Stream $Stream
    if ($line.Length -eq 0) { throw "Redis から空の応答が返りました" }

    $type = $line[0]
    $rest = $line.Substring(1)

    switch ($type) {
        '+' { return $rest }
        '-' { throw "Redis がエラーを返しました: $rest" }
        ':' { return [int64]$rest }
        '$' {
            $length = [int]$rest
            if ($length -lt 0) { return $null }
            $data = Read-RedisBytes -Stream $Stream -Count $length
            [void](Read-RedisBytes -Stream $Stream -Count 2)   # 終端の CRLF
            return ,$data
        }
        '*' {
            $count = [int]$rest
            if ($count -lt 0) { return $null }
            $items = New-Object System.Collections.ArrayList
            for ($i = 0; $i -lt $count; $i++) {
                [void]$items.Add((Read-RedisReply -Stream $Stream))
            }
            return ,$items.ToArray()
        }
        default { throw "未知の RESP 型です: '$type' (行: $line)" }
    }
}

function ConvertFrom-RedisBulk {
    <#
    .SYNOPSIS bulk 応答 (byte[]) を UTF8 文字列にする。nil は $null のまま。
    #>
    [CmdletBinding()]
    param([AllowNull()] $Value)

    if ($null -eq $Value) { return $null }
    if ($Value -is [byte[]]) { return [System.Text.Encoding]::UTF8.GetString($Value) }
    return [string]$Value
}

function Invoke-RedisCommand {
    <#
    .SYNOPSIS コマンドを 1 つ投げて応答を 1 つ読む。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] $Connection,
        [Parameter(Mandatory)] [string[]] $Arguments
    )

    $bytes = ConvertTo-RespCommand -Arguments $Arguments
    $Connection.Stream.Write($bytes, 0, $bytes.Length)
    $Connection.Stream.Flush()
    return Read-RedisReply -Stream $Connection.Stream
}

function Connect-RedisClient {
    <#
    .SYNOPSIS
        接続して (必要なら) AUTH と SELECT まで済ませる。失敗したら例外。
    .DESCRIPTION
        黙って $null を返さないのが要点。ここを握り潰すと
        「Redis を掃除したつもりで 1 件も消していない」状態が無警告で通る。
    #>
    [CmdletBinding()]
    param(
        [string] $HostName = "127.0.0.1",
        [int]    $Port     = 6379,
        [int]    $Database = 0,
        [AllowEmptyString()] [string] $User     = "",
        [AllowEmptyString()] [string] $Password = "",
        [int]    $TimeoutMs = 5000
    )

    $connection = Connect-TcpStream -HostName $HostName -Port $Port -TimeoutMs $TimeoutMs
    if (-not $connection) {
        throw "Redis へ接続できません ($HostName`:$Port)。サーバが起動しているか確認してください。"
    }
    # 値の読み書きは生存確認より時間がかかる。既定の 3 秒だと大きなキーで切れる。
    $connection.Stream.ReadTimeout  = $TimeoutMs
    $connection.Stream.WriteTimeout = $TimeoutMs

    try {
        if ($Password) {
            if ($User) {
                [void](Invoke-RedisCommand -Connection $connection -Arguments @("AUTH", $User, $Password))
            } else {
                [void](Invoke-RedisCommand -Connection $connection -Arguments @("AUTH", $Password))
            }
        }
        # Garnet は 0 番以外を持たない構成があるので、既定値のときは投げない。
        if ($Database -ne 0) {
            [void](Invoke-RedisCommand -Connection $connection -Arguments @("SELECT", "$Database"))
        }
    } catch {
        $connection.Client.Dispose()
        throw
    }
    return $connection
}

function Disconnect-RedisClient {
    [CmdletBinding()]
    param([Parameter(Mandatory)] $Connection)

    try { $Connection.Client.Dispose() } catch { }
}

function Get-RedisKey {
    <#
    .SYNOPSIS
        パターンに一致するキーを SCAN で集める。
    .DESCRIPTION
        KEYS ではなく SCAN を使う。KEYS はサーバを止めるうえ、
        SCAN は同じキーを 2 回返しうるので重複を落としてから返す。

        【戻り値は「文字列の並び」として流す。`,` で包まないこと。】
        包むと呼び出し側の @(Get-RedisKey ...) が【要素 1 個 (中身は配列) の配列】になり、
        キーが何件あっても 1 件に見える。そのまま消しに行くと配列を文字列化した
        存在しないキーを 1 回消して「消し終わった」ことになる (実際に踏んだ)。
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] $Connection,
        [Parameter(Mandatory)] [string] $Pattern,
        [int] $PageSize = 500
    )

    $keys   = New-Object System.Collections.ArrayList
    $cursor = "0"
    do {
        $reply = Invoke-RedisCommand -Connection $Connection `
            -Arguments @("SCAN", $cursor, "MATCH", $Pattern, "COUNT", "$PageSize")
        if ($null -eq $reply -or $reply.Count -lt 2) {
            throw "SCAN の応答が想定と違います。"
        }
        $cursor = ConvertFrom-RedisBulk -Value $reply[0]
        if ($null -ne $reply[1]) {
            foreach ($item in $reply[1]) {
                [void]$keys.Add((ConvertFrom-RedisBulk -Value $item))
            }
        }
    } while ($cursor -ne "0")

    return @($keys.ToArray() | Sort-Object -Unique)
}

function Get-HuskSyncRedisSettings {
    <#
    .SYNOPSIS
        HuskSync の config.yml から Redis の接続先とキーの接頭辞を読む。
    .DESCRIPTION
        【必ず親をたどって引くこと】。config.yml には database.credentials.host と
        redis.credentials.host のように同じ名前のキーが 2 組あるので、
        行を素朴に検索すると MariaDB 側の資格情報で Redis へ繋ぎに行く。

        キー名は HuskSync 側で "<namespace>:<clusterId>:<種別>:<UUID>" と組まれる
        (RedisKeyType#getKeyPrefix)。cluster_id は既定が空文字なので、
        既定構成のキーは "husksync:::latest_snapshot:<UUID>" になる。
    .OUTPUTS
        @{ HostName; Port; Database; User; Password; ClusterId; KeyPattern }
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)] [string] $ConfigPath)

    if (-not (Test-Path -LiteralPath $ConfigPath)) {
        throw "HuskSync の config.yml が見つかりません: $ConfigPath"
    }
    $lines = @(Get-Content -LiteralPath $ConfigPath -Encoding UTF8)

    function Get-Scalar {
        param([string[]] $Path, [string] $Default)
        $index = Find-YamlLineIndex -Lines $lines -Path $Path
        if ($index -lt 0) { return $Default }
        $line = $lines[$index]
        if ($line -notmatch '^\s*[^:]+:\s*(.*)$') { return $Default }
        $value = $Matches[1].Trim()
        # 引用符は外す。中身に # を含むパスワードがありうるのでコメント除去はしない。
        if ($value.Length -ge 2 -and
            (($value.StartsWith("'") -and $value.EndsWith("'")) -or
             ($value.StartsWith('"') -and $value.EndsWith('"')))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ($value -eq "") { return $Default }
        return $value
    }

    $clusterId = Get-Scalar -Path @('cluster_id') -Default ""

    return @{
        HostName  = Get-Scalar -Path @('redis', 'credentials', 'host')     -Default "127.0.0.1"
        Port      = [int](Get-Scalar -Path @('redis', 'credentials', 'port') -Default "6379")
        Database  = [int](Get-Scalar -Path @('redis', 'credentials', 'database') -Default "0")
        User      = Get-Scalar -Path @('redis', 'credentials', 'user')     -Default ""
        Password  = Get-Scalar -Path @('redis', 'credentials', 'password') -Default ""
        ClusterId = $clusterId
        KeyPattern = "husksync:$($clusterId.ToLowerInvariant()):*"
    }
}
