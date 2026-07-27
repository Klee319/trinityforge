<#
.SYNOPSIS
    yml を「行単位で正確に」触るための最小限のヘルパー。

.DESCRIPTION
    HuskSync の config.yml には同じ名前のキーが複数ある
    （database.credentials.host と redis.credentials.host、
      database.credentials.password と redis.credentials.password）。
    単純な行の置換では【別のブロックのキーを書き換える】ため、
    親をたどってから探す必要がある。

    ここでは YAML パーサを持ち込まない。生成物のコメントと並び順を保ったまま
    書き戻したいので、必要な機能（インデントでブロックを絞ってキーを引く）だけを持つ。
    対応するのは「2 スペース刻みのマッピング」だけで、フロー形式や複数行文字列は扱わない。
#>

Set-StrictMode -Version Latest

function Get-YamlIndent {
    <#
    .SYNOPSIS 行頭の空白の数を返す。
    #>
    [CmdletBinding()]
    param([Parameter(Mandatory)] [AllowEmptyString()] [string] $Line)

    if ($Line -match '^(\s*)') { return $Matches[1].Length }
    return 0
}

function Find-YamlLineIndex {
    <#
    .SYNOPSIS
        ドットパス相当のキー列を親からたどり、最後のキーがある行の添字を返す。
    .DESCRIPTION
        見つからなければ -1。探索範囲を親ブロックの内側へ絞っていくので、
        同名キーが他のブロックにあっても取り違えない。
    .EXAMPLE
        Find-YamlLineIndex -Lines $lines -Path @('database','credentials','host')
    #>
    [CmdletBinding()]
    param(
        # 空行を含む配列が来るので AllowEmptyString が要る（既定だと要素ごとに弾かれる）。
        [Parameter(Mandatory)] [AllowEmptyString()] [string[]] $Lines,
        [Parameter(Mandatory)] [string[]] $Path
    )

    $searchStart = 0
    $searchEnd   = $Lines.Count
    $result      = -1

    for ($depth = 0; $depth -lt $Path.Count; $depth++) {
        $key = $Path[$depth]
        $expectedIndent = $depth * 2
        $found = -1

        for ($i = $searchStart; $i -lt $searchEnd; $i++) {
            $line = $Lines[$i]
            if ($line -match '^\s*$' -or $line -match '^\s*#') { continue }

            $indent = Get-YamlIndent -Line $line
            # 親より浅い行が出たら、その親のブロックは終わっている。
            if ($indent -lt $expectedIndent) { break }
            if ($indent -ne $expectedIndent) { continue }

            if ($line -match "^\s{$expectedIndent}$([regex]::Escape($key))\s*:") {
                $found = $i
                break
            }
        }

        if ($found -lt 0) { return -1 }

        # 次の階層はこのキーのブロック内だけを見る。
        $blockEnd = $searchEnd
        for ($j = $found + 1; $j -lt $searchEnd; $j++) {
            $line = $Lines[$j]
            if ($line -match '^\s*$' -or $line -match '^\s*#') { continue }
            if ((Get-YamlIndent -Line $line) -le $expectedIndent) { $blockEnd = $j; break }
        }

        $searchStart = $found + 1
        $searchEnd   = $blockEnd
        $result      = $found
    }

    return $result
}
