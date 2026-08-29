<#
.SYNOPSIS
    パッチ済み HuskSync（復元できないアイテムでインベントリ丸ごと捨てない版）を配備する。

.DESCRIPTION
    上流 HuskSync 4.0.0 (commit 3dc619d) は、スナップショットのアイテム配列を
    `NBT.itemStackArrayFromNBT` で **一括** に復元する。ここで 1 個でも復元できない
    アイテムがあると配列ごと例外になり、呼び出し側はそれを握って
    **そのデータ型（インベントリ／エンダーチェスト）を丸ごと skip** する。
    つまり **アイテム 1 個のせいで全ロスト**する。実際に踏んだ:

        [HuskSync] Failed to deserialize %s data for snapshot %s; skipping it.
        NbtApiException: Failed to get element nova_structures:spiteful ...

    （資源鯖にだけ Dungeons and Taverns のデータパックが入っていて、そこで拾った
    エンチャント品を Main へ持ち帰った瞬間に発火した。2026-08-21）

    fork-handoff/husksync のパッチは `getItems` の一括読みを try で包み、失敗したら
    上流が既に持っている **スロット単位の読み直し**へ落とす。壊れたスロットだけ AIR に
    なり、何を落としたかは WARNING でログに残る。

    ⚠️ **稼働中に jar を差し替えると必ず NoClassDefFoundError になる。**
    JVM を stop -> start する以外に復旧手段は無いので、check-servers-stopped.ps1 で
    必ずゲートする。

    ⚠️ **配備先のファイル名は「今入っている名前」に合わせる。** 別名で置くと
    HuskSync が 2 本ロードされる。既存を `.jar.bak-<日時>` へ退避してから同名で置く
    （Paper は `.jar` しか読まないので退避ファイルはロードされない）。

.PARAMETER DryRun
    何も書かずに、配る対象と配備先の現状（サイズ・更新時刻）だけを出す。

.PARAMETER SkipBuild
    ビルドを飛ばして既存の成果物を配る。直前に自分でビルドしたときだけ使う。

.PARAMETER ForkRoot
    HuskSync フォークのルート。省略時は `fork-handoff\husksync\fork`。

.PARAMETER JavaHome
    Gradle に渡す JDK。HuskSync は `gradle.properties` で javaVersion=25 を要求するので
    既定は jdk-25.0.4。**TrinityForge 本体（jdk-21）とは違う**ので取り違えないこと。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File ops\scripts\deploy-husksync.ps1 -DryRun
#>
[CmdletBinding()]
param(
    [switch] $DryRun,
    [switch] $SkipBuild,
    [string] $ForkRoot,
    [string] $JavaHome = "C:\Program Files\Java\jdk-25.0.4"
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\Common.ps1")

$config = Get-OpsConfig -RequireRconPasswords:$false

$tfRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $ForkRoot) {
    $ForkRoot = Join-Path $tfRoot "fork-handoff\husksync\fork"
}
if (-not (Test-Path -LiteralPath $ForkRoot)) {
    throw ("HuskSync フォークがありません: $ForkRoot`n" +
           "fork-handoff\husksync\00_HANDOFF.md の手順で clone とパッチ当てをしてください。")
}

# 上流の multi-version ビルド。1.21.11 のモジュールだけを作る。
# shadowJar の出力先は build.gradle で `$rootDir/target` に付け替えられている。
# build\libs\ にも同名の jar が出るが、そちらは **依存を同梱していない thin jar** で
# 起動できない。必ず target\ の方を配ること。
$gradleTask = ":bukkit:1.21.11:build"

if (-not $SkipBuild) {
    Write-Host "[build] $ForkRoot ($gradleTask)" -ForegroundColor Cyan
    if ($DryRun) {
        Write-Host "  (DryRun: gradlew build は実行しない)"
    } else {
        Push-Location $ForkRoot
        try {
            & (Join-Path $ForkRoot "gradlew.bat") $gradleTask "-x" "test" "--no-daemon" "-Dorg.gradle.java.home=$JavaHome"
            if ($LASTEXITCODE -ne 0) {
                throw "gradlew $gradleTask が失敗しました (exit $LASTEXITCODE)。配備は行いません。"
            }
        } finally {
            Pop-Location
        }
    }
}

$targetDir = Join-Path $ForkRoot "target"
$built = @(Get-ChildItem -LiteralPath $targetDir -Filter "HuskSync-Bukkit-*+mc.1.21.11.jar" -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" })
if ($built.Count -ne 1) {
    throw ("配る jar を一意に決められません（$($built.Count) 個）: $targetDir`n" +
           "target\ を消してからビルドし直してください。")
}
$sourceJar = $built[0]
Write-Host ("[artifact] {0}  {1:N0} bytes  {2:yyyy-MM-dd HH:mm:ss}" -f `
    $sourceJar.Name, $sourceJar.Length, $sourceJar.LastWriteTime)

# --------------------------------------------------------------------------
# パッチが入っているかを成果物そのもので確かめる
#
# 「パッチを当てたつもりで上流のままの jar を配る」を防ぐ。ソースを見ても意味が無い
# （配るのは jar）ので、jar の中のクラスに埋まった文字列を直接探す。
# --------------------------------------------------------------------------
$marker = "falling back to a per-slot read"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($sourceJar.FullName)
try {
    $entry = $zip.Entries | Where-Object {
        $_.FullName -eq "net/william278/husksync/data/BukkitSerializer`$ItemDeserializer.class"
    }
    if (-not $entry) {
        throw "jar に BukkitSerializer`$ItemDeserializer.class がありません: $($sourceJar.FullName)"
    }
    $stream = $entry.Open()
    try {
        $mem = New-Object System.IO.MemoryStream
        $stream.CopyTo($mem)
        $bytes = $mem.ToArray()
    } finally {
        $stream.Close()
    }
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    if ($text.IndexOf($marker) -lt 0) {
        throw ("この jar にはパッチが入っていません（目印 '$marker' が見つからない）。`n" +
               "fork-handoff\husksync\patches\ のパッチを当ててからビルドし直してください。")
    }
} finally {
    $zip.Dispose()
}
Write-Host "[verify] パッチ済みの jar であることを確認しました" -ForegroundColor Green

# --------------------------------------------------------------------------
# 配備先の決定（今入っている jar の名前をそのまま使う）
# --------------------------------------------------------------------------
$targets = @()
foreach ($name in @($config.Servers.Keys | Sort-Object)) {
    $pluginsDir = Join-Path $config.Servers[$name].Root "plugins"
    if (-not (Test-Path -LiteralPath $pluginsDir)) {
        Write-Host "[SKIP] $name : plugins フォルダがありません ($pluginsDir)" -ForegroundColor Yellow
        continue
    }
    $existing = @(Get-ChildItem -LiteralPath $pluginsDir -Filter "HuskSync-*.jar" -ErrorAction SilentlyContinue)
    if ($existing.Count -gt 1) {
        throw ("$name の plugins に HuskSync の jar が $($existing.Count) 本あります。" +
               "2 本ロードされている状態なので、まず手で 1 本に減らしてください:`n  " +
               (($existing | ForEach-Object { $_.Name }) -join "`n  "))
    }
    if ($existing.Count -eq 1) {
        $targetPath = $existing[0].FullName
    } else {
        Write-Host "[SKIP] $name : HuskSync が入っていません" -ForegroundColor Yellow
        continue
    }
    $targets += [pscustomobject]@{
        Label  = $name
        Target = $targetPath
    }
}
if ($targets.Count -eq 0) {
    throw "配備先が 1 つも見つかりませんでした。"
}

Write-Host ""
foreach ($t in $targets) {
    $item = Get-Item -LiteralPath $t.Target
    Write-Host ("[plan] {0,-16} -> {1}" -f $t.Label, $t.Target)
    Write-Host ("       現在: {0:N0} bytes  {1:yyyy-MM-dd HH:mm:ss}" -f $item.Length, $item.LastWriteTime)
}

if ($DryRun) {
    Write-Host ""
    Write-Host "DryRun のためここで終了します。何も書いていません。" -ForegroundColor Green
    exit 0
}

# --------------------------------------------------------------------------
# 稼働チェック（子プロセスで呼ぶ。あのスクリプトは exit で終了コードを返す）
# --------------------------------------------------------------------------
Write-Host ""
Write-Host "[gate] バックエンドが停止していることを確認します" -ForegroundColor Cyan
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "check-servers-stopped.ps1")
if ($LASTEXITCODE -ne 0) {
    throw ("稼働中のバックエンドがあります。停止してからやり直してください。" +
           "（稼働中の jar 差し替えは必ず NoClassDefFoundError になり、再起動以外に復旧手段がありません）")
}

# --------------------------------------------------------------------------
# 退避してからコピー
# --------------------------------------------------------------------------
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
Write-Host ""
foreach ($t in $targets) {
    $backup = "$($t.Target).bak-$stamp"
    Copy-Item -LiteralPath $t.Target -Destination $backup -Force
    Copy-Item -LiteralPath $sourceJar.FullName -Destination $t.Target -Force
    Write-Host ("[copy] {0,-16} OK  (退避: {1})" -f $t.Label, (Split-Path $backup -Leaf)) -ForegroundColor Green
}

Write-Host ""
Write-Host "配備しました。効いているかは、次に復元不能なアイテムが来たときのログで分かります:" -ForegroundColor Green
Write-Host "  直った場合: [HuskSync] Could not read an item array in one pass (...); falling back to a per-slot read ..."
Write-Host "              [HuskSync] Dropping an item this server cannot rebuild (slot N): {...}"
Write-Host "  未配備なら: [HuskSync] Failed to deserialize %s data for snapshot %s; skipping it."
Write-Host ""
Write-Host "後者が出続けるなら jar が差し替わっていない。前者ならインベントリは残っている。"
