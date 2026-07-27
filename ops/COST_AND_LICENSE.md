# 費用とライセンス — 「全部無料でできるか」の裏取り

**結論: できる。** ただし条件が2つある。

1. **Docker Desktop を使わない**（WSL2 に MariaDB / Redis を直接入れる）
2. **HuskSync をソースからビルドする**（公式バイナリは有料配布。ソースは Apache-2.0）

有料が必要になるのは「Bedrock 版まで含めた上流 DDoS 防御」だけで、これは今回の要件に含まれない。

---

## 一覧

| コンポーネント | 費用 | ライセンス | 備考 |
|---|---|---|---|
| Paper | 無料 | GPL-3.0 | 導入済み |
| **Velocity** | 無料 | GPL-3.0 | |
| **HuskSync** | **ソースは無料** | **Apache-2.0** | **公式バイナリとサポートは有料配布。ソースからビルドすれば無料。** 詳細は下記 |
| LuckPerms | 無料 | MIT | Velocity 版も無料。導入済み |
| Geyser | 無料 | MIT | 導入済み（移設のみ） |
| Floodgate | 無料 | MIT | 導入済み |
| ViaVersion / ViaBackwards | 無料 | GPL-3.0 | 導入済み（移設のみ） |
| Chunky | 無料 | GPL-3.0 | 導入済み |
| **Sonar** | 無料 | GPL-3.0 | Modrinth / Hangar で配布 |
| **FallbackRouter** | 無料 | — | Hangar |
| **OneTimePack** | 無料 | — | Modrinth |
| SetHome / WorldEdit / packetevents / Hurricane / ProtocolLib 等 | 無料 | 各種 OSS | 導入済み |
| **MariaDB** | 無料 | GPL-2.0 | |
| **Redis** | 無料 | 自己ホストなら無料 | |
| **WSL2** | 無料 | — | Windows に同梱 |
| ~~Docker Desktop~~ | **企業利用は有料** | — | **使わない。** WSL2 直インストールで代替する |
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

| HuskSync | 対応 Minecraft | 状況 |
|---|---|---|
| 3.8.7（2025-08-12） | 1.20.1 / 1.21.1 / 1.21.4 / 1.21.5 / **1.21.8** | **1.21.11 は含まれない** |
| 3.9.0（2026-06-17） | **26.1.2 のみ** | 1.21.x のアダプタを全て削除。Java 25 前提 |
| **master（4.0.0・未リリース）** | **1.21.11 / 26.1.2 / 26.2** | **1.21.11 のアダプタが存在する** |

`bukkit/1.21.11/gradle.properties`（master）:

```properties
minecraft_version_range=>=1.21.11 <=1.21.11
minecraft_version_numeric=12111
paper_api_version=1.21.11-R0.1-SNAPSHOT
java_version=21
```

master は活発に更新されている（直近コミット 2026-07-23）。

### したがって取れる手

**推奨: master（4.0.0-dev）を特定コミットに固定してビルドする。**

- 1.21.11 のアダプタが公式に存在するので、動作は最も確実
- ルートの `gradle.properties` は `javaVersion=25` だが、
  **1.21.11 アダプタ自身は `java_version=21`** を宣言している。
  ビルドには JDK 25 が要る可能性があるが、実行は Java 21 でよい
- 未リリースブランチなので、**必ずコミットハッシュを固定**して、
  ビルドした jar と対応コミットを記録に残すこと

**代替案（先に試す価値あり・コストゼロ）: 3.8.7 の配布 jar をそのまま入れてみる。**

HuskSync は `compileOnly paper-api` のみで、**paperweight / NMS を使っていない**
（`bukkit/build.gradle` を確認済み）。難読化マッピングに依存しないため、
1.21.8 向けビルドが 1.21.11 でそのまま動く可能性は十分ある。
`api-version` も `1.21` なので Paper には受け入れられる。

ただし **公式サポート範囲外**であり、アイテムのシリアライズ形式が変わっていた場合に
インベントリが壊れる形で失敗しうる。**本番データで試さないこと。**
試すならテスト用の空サーバで、捨ててよいアカウントを使う。

**最後の手段: MC 26.1.2 へ全体を上げて 3.9.0 を使う。**
TrinityForge / ArsPaper / EliteMobs の 3 フォーク、Geyser、リソースパックが
すべて追随する必要があるため、今回のスコープでは非現実的。

---

## Docker Desktop を避ける理由

Docker Desktop は**一定規模以上の企業利用が有料**。個人利用なら無料だが、
そもそも今回必要なのは MariaDB と Redis を 1 つずつ動かすことだけで、
コンテナランタイムを 1 層挟む理由がない。

**WSL2 に直接インストールする。** Windows に同梱されており追加費用ゼロ。

```bash
# Ubuntu (WSL2) 上で
sudo apt update
sudo apt install -y mariadb-server redis-server
```

`.wslconfig` でメモリ上限を切っておく（[PERFORMANCE.md](PERFORMANCE.md) 参照）。
MariaDB は `bind-address=127.0.0.1`、Redis は `bind 127.0.0.1` にする
（[SECURITY.md](SECURITY.md)）。

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
無料: MariaDB / Redis （WSL2 に直インストール）
無料: 定期再起動・週次リセット・バックアップ （PowerShell + タスクスケジューラ + RCON）
無料: 進行データ共有 （NTFS ディレクトリジャンクション）

作業コストのみ: HuskSync をソースからビルド（Apache-2.0）

任意・無料: TCPShield Free （Java 版のみ上流吸収）
不要:      Docker Desktop
```

**金銭的な支出はゼロで組める。**
