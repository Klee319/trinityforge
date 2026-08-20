# 費用とライセンス — 「全部無料でできるか」の裏取り

**結論: できる。** ただし条件が2つある。

1. **Docker Desktop を使わない**（MariaDB と Garnet を Windows ネイティブで入れる）
2. **HuskSync をソースからビルドする**（公式バイナリは有料配布。ソースは Apache-2.0）

有料が必要になるのは「Bedrock 版まで含めた上流 DDoS 防御」だけで、これは今回の要件に含まれない。

---

## 一覧

| コンポーネント | 費用 | ライセンス | 備考 |
|---|---|---|---|
| Paper | 無料 | GPL-3.0 | 導入済み |
| **Velocity** | 無料 | GPL-3.0 | |
| **HuskSync** | **ソースは無料** | **Apache-2.0** | **公式バイナリとサポートは有料配布。ソースからビルドすれば無料。2026-07-27 にビルド済み**（詳細は下記） |
| LuckPerms | 無料 | MIT | Velocity 版も無料。導入済み |
| Geyser | 無料 | MIT | 導入済み（移設のみ） |
| Floodgate | 無料 | MIT | 導入済み |
| ViaVersion / ViaBackwards | 無料 | GPL-3.0 | 導入済み（移設のみ） |
| Chunky | 無料 | GPL-3.0 | 導入済み |
| **Sonar** | 無料 | GPL-3.0 | Modrinth / Hangar で配布 |
| **FallbackRouter** | 無料 | — | Hangar |
| **OneTimePack** | 無料 | — | Modrinth |
| SetHome / WorldEdit / packetevents / Hurricane / ProtocolLib 等 | 無料 | 各種 OSS | 導入済み |
| **MariaDB** | 無料 | GPL-2.0 | **Windows ネイティブの MSI が公式にある** |
| **Garnet**（Redis 互換） | 無料 | MIT | Microsoft 製。Windows ネイティブ。**zip は自己完結ではなく .NET ランタイムが要る**（この環境は .NET 8.0.21 導入済みなので `net8.0` 版がそのまま動く） |
| ~~Memurai~~ | **実質有料** | 商用 | **使わない。** Developer 版は稼働 10 日上限かつ本番利用禁止 |
| ~~tporadowski/redis~~ | 無料 | BSD-3 | 非推奨。Redis 5.0 相当で更新が止まっている |
| WSL2 + Redis | 無料 | — | ネイティブを使わない場合の代替。Windows に同梱 |
| ~~Docker Desktop~~ | **企業利用は有料** | — | **使わない。** ネイティブで代替する |
| spark | 無料 | GPL-3.0 | **Paper 1.21 に同梱済み** |
| 定期再起動・週次リセット | 無料 | — | PowerShell + タスクスケジューラ + RCON。プラグイン不要 |
| TCPShield | **Free プランあり（Java のみ）** | — | 任意。Bedrock は Premium 限定 |
| ディレクトリジャンクション | 無料 | — | NTFS の機能。`mklink /J` |

---

## HuskSync — 唯一の作業コスト

### ライセンスと配布

ソースコードは **Apache-2.0** で GitHub に公開されている。
一方、公式のビルド済みバイナリとサポートは有料マーケット（Polymart 等）で配布されている。
**Apache-2.0 なので、自分でビルドして自分のサーバで使うことに何の制限もない。**

このプロジェクトは既に 3 つのフォーク（ArsPaper / EliteMobs / TrinityForge）を
Gradle でビルドしている環境なので、ビルド自体は現実的な作業。

### バージョン対応の実態（2026-07-27 時点で調査）

**現行サーバは Paper 1.21.11。ここが問題になる。**

`bukkit/` 配下のディレクトリが、そのまま「ビルドできる Minecraft 版」の一覧になっている。

| HuskSync | `bukkit/` の中身 = 対応 Minecraft | 状況 |
|---|---|---|
| 3.8.7（2025-08-12） | `1.20.1` `1.21.1` `1.21.4` `1.21.5` `1.21.8` | **1.21.11 は含まれない** |
| 3.9.0（2026-06-17） | **`26.1.2` のみ** | **1.21.x のアダプタを全て削除**。Java 25 前提 |
| **master（4.0.0・未リリース）** | **`1.21.11`** `26.1.2` `26.2` | **1.21.11 のアダプタが存在する** |

`bukkit/1.21.11/gradle.properties`（master）:

```properties
minecraft_version_range=>=1.21.11 <=1.21.11
minecraft_version_numeric=12111
paper_api_version=1.21.11-R0.1-SNAPSHOT
java_version=21
```

master は活発に更新されている（直近コミット 2026-07-23）。

### 3.9.0 では 1.21.11 は動かない

**「新しい版に対応したのだから古い版も動くはず」は成り立たない。**
HuskSync は essential の multi-version プリプロセッサを使い、`bukkit/<版>/` ディレクトリ単位で
ソースを切り替えてビルドする。3.9.0 は 26.1.2 対応を*追加*したのではなく、
**26.1.2 へ移行して 1.21.x のバリアントを全て削除**した版である。
`bukkit/` の中身が `26.1.2` ひとつしかないため、1.21.11 向けの jar はそもそも生成されない。

### ビルド済み（2026-07-27）

master（4.0.0・未リリース）をコミット固定してビルドした。**そのまま使える。**

| 項目 | 値 |
|---|---|
| 成果物 | `tmp/husksync-dist/HuskSync-Bukkit-4.0.0-3dc619d+mc.1.21.11.jar`（3,247,291 バイト） |
| SHA-256 | `4E047339EFD25DD1DC776CF3E8D9F8AA007C54E35B77534367C8479274E82466` |
| 元コミット | `3dc619d5f641ee909004925dbbda2d507b7127c2`（master、2026-07-23） |
| コンパイル対象 | `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` |
| ビルド JDK | **21 で通った** |

**ルートの `javaVersion=25` に惑わされないこと。** それは 26.x バリアントと Fabric 向けで、
`bukkit/1.21.11/gradle.properties` は `java_version=21` を宣言している。
`:bukkit:1.21.11:shadowJar` だけを指定すれば JDK 21 でビルドできる
（`./gradlew build` だと全バリアントを巻き込んで JDK 25 が要る）。

手順は [RUNBOOK.md](RUNBOOK.md) の手順 9-1。

### 参考: 3.8.7 の配布 jar を使う案（採らなかった）

HuskSync は `compileOnly paper-api` のみで **paperweight / NMS を使っていない**ため、
1.21.8 向けビルドが 1.21.11 でそのまま動く可能性はあった。
ただし公式サポート範囲外で、アイテムのシリアライズ形式が変わっていればインベントリが壊れる。
master に正式な 1.21.11 バリアントがあり、それが JDK 21 で問題なくビルドできた以上、
賭ける理由がない。

### 参考: MC 26.1.2 へ全体を上げる案（採らなかった）

TrinityForge / ArsPaper / EliteMobs の 3 フォーク、Geyser、リソースパックが
すべて追随する必要があるため、今回のスコープでは非現実的。

---

## Docker Desktop を避ける理由

Docker Desktop は**一定規模以上の企業利用が有料**。個人利用なら無料だが、
そもそも今回必要なのは MariaDB と Redis を 1 つずつ動かすことだけで、
コンテナランタイムを 1 層挟む理由がない。

**Windows ネイティブで入れる**（手順は [RUNBOOK.md](RUNBOOK.md) 手順2）。追加費用ゼロ。

---

## Redis を Windows でどうするか

**Redis 本体に公式の Windows 版は無い。** ここが構成上いちばん選択肢が割れる点なので、
判断の根拠を残す。

| 候補 | 費用 | 判定 |
|---|---|---|
| **Garnet**（Microsoft・MIT） | **無料** | **採用。** ネイティブ Windows / 活発に開発中（v2.1.0 = 2026-07-24）。.NET ランタイムが要るが既に入っている |
| Memurai | Developer 版は無料だが**稼働 10 日上限・本番利用禁止**。本番は有料 | **不可。** 24/7 のゲームサーバでは要件を満たさない |
| tporadowski/redis | 無料 | 非推奨。Redis 5.0 相当で更新が止まっている |
| WSL2 + redis-server | 無料 | 可。ネイティブに拘らないならこれ |

**Garnet で足りる根拠**: HuskSync が使う Redis コマンドは
`PING` / `SET` / `SETEX` / `GET` / `DEL` / `KEYS` / `PUBLISH` / `SUBSCRIBE` / `INFO` の
**9 つだけ**（`common/.../redis/RedisManager.java` を実読）。
すべて Garnet の API 互換表で対応済みで、pub/sub も既定で有効。

**ただし Garnet は Redis の再実装であって Redis そのものではない。**
Dev_Server で先に確認してから Main / Resource へ広げること。
問題が出たら WSL2 + Redis に切り替えればよく、**HuskSync 側の設定は 1 文字も変わらない**。

MariaDB は `bind-address=127.0.0.1`、Garnet は `--bind 127.0.0.1` にする
（[SECURITY.md](SECURITY.md)）。WSL2 を使う場合は `.wslconfig` でメモリ上限を切っておく
（[PERFORMANCE.md](PERFORMANCE.md) 参照）。**切らないと 2 つの JVM とメモリを取り合う。**

---

## TCPShield — 無料の範囲

| | 内容 |
|---|---|
| Free プラン | Java Edition のみ。上流で L3/L4 攻撃を吸収する |
| Bedrock（UDP） | **Premium 限定。無料の手段はない** |

今回の要件（資源サーバ分離）には含まれないので任意。
入れる場合は Java 側だけ守れると理解したうえで使うこと。
Bedrock が攻撃された場合の運用手順は [SECURITY.md](SECURITY.md) の §3 に書いてある。

---

## 費用ゼロで組んだ場合の全体像

```
無料: Paper / Velocity / LuckPerms / Geyser / Floodgate / Via* / Chunky /
      Sonar / FallbackRouter / OneTimePack / SetHome / WorldEdit / spark
無料: MariaDB / Garnet （Windows ネイティブ。Redis 互換サーバとして Garnet を使う）
無料: 定期再起動・週次リセット・バックアップ （PowerShell + タスクスケジューラ + RCON）
無料: 進行データ共有 （NTFS ディレクトリジャンクション）

作業コストのみ: HuskSync をソースからビルド（Apache-2.0）

任意・無料: TCPShield Free （Java 版のみ上流吸収）
不要:      Docker Desktop / Memurai（Developer 版は本番不可）
```

**金銭的な支出はゼロで組める。**
