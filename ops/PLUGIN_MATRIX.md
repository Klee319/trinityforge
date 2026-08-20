# プラグイン配置表 — proxy / main / resource

現行 (2026-07-27 時点) のメインサーバ `D:\game\minecraft\PaperServer\TrinityForge\plugins` の
実配置を読み取ったうえで、3ノードへの割り振りを決めたもの。

**方針**: 資源サーバは原則メインと同じ構成にする。外すものは全て理由を付ける。
最終判断はユーザー。「入れておいても壊れないが無駄」なものと「入れると害がある」ものを分けて書く。

---

## 一覧

| プラグイン | proxy | main | resource | 備考 |
|---|:---:|:---:|:---:|---|
| **TrinityForge** | — | ○ | ○ | `plugins/TrinityForge` はジャンクションで実体共有 |
| **ArsPaper** | — | ○ | ○ | TF を `required: true` で要求するのでセット必須。config はコピー同期 |
| **HuskSync** | — | ○ | ○ | **新規**。インベントリ / EC / 経験値 / PDC の同期 |
| LuckPerms | ○ | ○ | ○ | H2 → MariaDB へ移行。`messaging-service: redis` |
| **VaultUnlocked** | — | ○ | ○ | **新規**（手順 19）。経済 API の口だけ。旧 `Vault.jar` と同居させない |
| **Jecon** | — | ○ | ○ | **新規**（手順 19）。残高は MariaDB の `jecon` DB を 3 台で共有。`lazyWrite: false` 必須 |
| **JeconCacheName** | — | ○ | ○ | **新規**（手順 19）。Jecon のアドオン。単体では意味がない |
| **PlaceholderAPI** | — | ○ | ○ | **新規**（手順 19）。入れると TF の `trinityforge` 拡張が登録される |
| **Geyser-Spigot** | ○ へ移設 | ✕ | ✕ | Geyser-Velocity に置き換え。バックエンドからは撤去 |
| **geyserExtra** | ○ へ移設 | ✕ | ✕ | Geyser の `extensions/` へ |
| floodgate | ○ | ○ | ○ | プロキシに必須。スキンと API のためバックエンドにも残す |
| **ViaVersion / ViaBackwards** | ○ へ移設 | ✕ | ✕ | プロキシで一括変換すればバックエンドは素のままでよい |
| **Sonar** | ○ | — | — | **新規**。antibot |
| **FallbackRouter** | ○ | — | — | **新規**。バックエンド停止時の自動退避 |
| **OneTimePack** | ○ | — | — | **新規**。サーバ移動時のリソパ再送抑止 |
| Chunky | — | ○ | ○ | 資源側では毎週の事前生成に使う |
| WorldEdit | — | ○ | ○ | 運営作業用 |
| packetevents | — | ○ | ○ | 他プラグインの依存 |
| ProtocolLib | — | ○ | ○ | 同上 |
| Hurricane | — | ○ | ○ | パケット最適化 |
| PaperDrawers | — | ○ | ○ | |
| DPSChecker-TF | — | ○ | ○ | |
| CommandBinderGUI | — | ○ | ○ | |
| Quick-EnderChest | — | ○ | ○ | EC は HuskSync が同期するので両方で使える |
| ~~DeathChest~~ → **AxGraves** | — | ○ | ○ | **2026-08-18 差し替え**。DeathChest 1.5.7 は保存キーの末尾がプレイヤー名で、Floodgate の `.` 接頭辞が入ると Bukkit の YAML がキーを入れ子と解釈し、`ClassCastException` で enable できない（上流に修正版なし）。AxGraves は `data.json` を **UUID** で持つので起こりえない。差し替えは [swap-deathchest-to-axgraves.ps1](scripts/swap-deathchest-to-axgraves.ps1) |
| **SetHome** | — | ○ | ○ | home は同期されず**サーバごとに独立**。要件どおり |
| **EliteMobs** | — | ○ | **✕ 推奨** | 下記 |
| **BlueMap** | — | ○ | **✕ 推奨** | 下記 |
| **Backuper** | — | ○ | **✕ 推奨** | 下記 |
| **DiscordSRV** | — | ○ | **✕ 必須** | 下記。入れると実害が出る |
| **Multiverse-Core / Portals** | — | ○ | **✕ 推奨** | 下記 |
| **WorldGuard** | — | ○ | **✕ 推奨** | 下記 |

---

## 資源サーバから外すもの — 理由

### 入れると実害が出る (外すことを強く推奨)

**DiscordSRV**
同じ Discord チャンネルに2インスタンスがぶら下がると、**チャットが二重投稿**される。
さらにサーバ移動のたびに join/leave が両方から流れ、チャンネルが埋まる。
仮に入れるなら別チャンネルに分けるか、chat 連携を片方で無効にする必要がある。手間に見合わない。

**Multiverse-Core / Multiverse-Portals**
`world` / `world_nether` / `world_the_end` の3つだけならバニラのポータルで足りる。
問題は**週次リセットで `worlds.yml` が存在しないワールドを指し続ける**こと。
起動のたびにエラーを吐き、リセット手順の障害物になる。
（`worlds.yml` もリセット対象に加えれば回避できるが、そもそも要らない。）

### 無駄なだけ (害はないが CPU とディスクを食う)

**BlueMap**
**毎週消えるワールドを毎回フルレンダーする**。今回の目的である「メインの負荷隔離」に真っ向から反する。
同一マシンで CPU を共有する構成なので、これは実質メインへの負荷になる。

**EliteMobs**
プレイヤーデータが SQLite でサーバ別のため、資源サーバでは通貨も武器スキルも常に 0 に見える。
`content_packages` と `world_blueprints` 一式のロードも無駄。
加えて資源ワールドにエリートモブとダンジョン戦利品が湧いてしまい、資源採取の場という位置づけが崩れる。

> **重要**: EliteMobs を外しても**モブのレベル推移と報酬テーブルは機能する**。
> TF のモブ系は2系統に分かれていて、`mob-types.yml` + `mob-level-table.yml` は
> バニラ／フィールドモブ用で EliteMobs に依存しない。
> `mob-overrides.yml` だけが EliteMobs 由来の刻印を持つ個体にしか反応しない。
> これはサーバを立てずに検証済み → [ops/reports/resource-server-mob-simulation.md](reports/resource-server-mob-simulation.md)

**Backuper**
消す前提のワールドをバックアップする意味がない。
資源サーバで守るべきデータ（進行 DB・インベントリ）はメイン側と MariaDB にあり、
[backup.ps1](scripts/backup.ps1) が担当する。

**WorldGuard**
リージョン定義は残るのに地形は毎週別物になる。幽霊リージョンが溜まっていく。
スポーン保護は `server.properties` の `spawn-protection=16` で足りる。

---

## プロキシへ移設するもの — 注意点

**Geyser / geyserExtra**
Geyser-Spigot を撤去して Geyser-Velocity を入れる。移設時に引き継ぐもの:

- `key.pem`（Floodgate のリンクキー。これを引き継がないと**既存の Bedrock プレイヤーが全員別人扱いになる**）
- Floodgate のリンク済みアカウント DB
- `geyserExtra` は Geyser の `extensions/` ディレクトリへ

Floodgate は**バックエンドにも残す**。スキン表示と、Bedrock 判定 API を使うプラグインのため。

> Bedrock の締め出しは影響が大きいので、RUNBOOK では Geyser 移設を独立した手順に切り、
> その場で Bedrock 接続確認を挟むようにしてある。

**ViaVersion / ViaBackwards**
プロキシで一括変換すればバックエンドは素の 1.21.11 だけを相手にすればよい。
バックエンドにも残すと二重変換になり、原因の切り分けが難しい不具合を生む。

---

## jar の二重配置に注意

過去に `ArsPaper.jar` と `ArsPaper-1.0.0.jar` が同居し、
`Ambiguous plugin name 'ArsPaper'` で起動できなくなった前科がある。
2026-07-27 時点では解消済み（`ArsPaper-1.0.0.jar` のみ）だが、
資源サーバへコピーするときに再発しやすい。

[sync-configs.ps1](scripts/sync-configs.ps1) が両サーバの `plugins/*.jar` を走査して
同名プラグインの重複を検出し、見つかれば **exit 1 で起動をブロック**する。
週次リセットからも自動で呼ばれる。
