# ops — 資源サーバ分離の作業書と運用スクリプト

Velocity プロキシ + メインサーバ + 資源サーバ の 2 バックエンド構成へ移行するための一式。

**このディレクトリの成果物は「ユーザーが自分で実行する作業書」と「サーバを立てずに前提を検証する仕組み」であって、配備そのものではない。**

---

## どこから読むか

| 目的 | ファイル |
|---|---|
| **移行作業をする** | [RUNBOOK.md](RUNBOOK.md) ← まずここ |
| **日々の起動・停止・検査** | [launch/README.md](launch/README.md) / [launch/testkit/README.md](launch/testkit/README.md) |
| どのプラグインをどこに置くか | [PLUGIN_MATRIX.md](PLUGIN_MATRIX.md) |
| DDoS/DoS 対策とポート方針 | [SECURITY.md](SECURITY.md) |
| 定期再起動と軽量化 | [PERFORMANCE.md](PERFORMANCE.md) |
| 本当に全部無料でできるのか | [COST_AND_LICENSE.md](COST_AND_LICENSE.md) |

---

## 事前検証の結果

サーバを立てずに確認したもの。数字は自動生成なので、テストを再実行すれば更新される。

| レポート | 内容 |
|---|---|
| [reports/resource-server-mob-simulation.md](reports/resource-server-mob-simulation.md) | **EliteMobs が無くてもモブのレベル推移と報酬テーブルが機能する**ことの実証。89 種 × Lv0〜100 |
| [reports/shared-sqlite-concurrency.md](reports/shared-sqlite-concurrency.md) | **2 プロセスが同じ進行 DB を触っても壊れない**ことの実証。ここで本体のバグを 1 件検出して修正した |

対応するテスト:

```bash
gradlew test --tests "com.trinityforge.ops.*"
```

| テスト | 検証すること |
|---|---|
| `ResourceServerMobSimulationTest` | 出荷 yml を実ロードし、EliteMobs 非搭載でレベル推移と報酬が全帯埋まること |
| `SharedSqliteConcurrencyTest` | 子 JVM を 2 つ起動して、プロセス間で進行 DB の二重消費が起きないこと |
| `PlayerPdcPrimitiveTypeAuditTest` | HuskSync の `persistent_data` 同期の前提（PDC が全て primitive 型）が守られていること |

---

## スクリプト

全て `-DryRun` で空撃ちできる。実行前に必ず一度は空撃ちすること。

| スクリプト | 用途 |
|---|---|
| [scripts/run-selftest.ps1](scripts/run-selftest.ps1) | **削除ガードの実測。** 実際にジャンクションを作り、誤削除が止まることを確認する |
| [scripts/preflight.ps1](scripts/preflight.ps1) | **起動前チェック。** MariaDB / Garnet が実際に応答しているか（ポートの開閉ではなくプロトコルで判定）、HuskSync の既定資格情報と同期設定、全バックエンドでの設定一致、forwarding secret の一致 |
| [scripts/apply-velocity-forwarding.ps1](scripts/apply-velocity-forwarding.ps1) | `forwarding.secret` を全バックエンドの `paper-global.yml` へ反映（冪等・退避あり・secret は表示しない） |
| [scripts/apply-husksync-config.ps1](scripts/apply-husksync-config.ps1) | HuskSync の config.yml を全バックエンドで同一内容に揃える（パスワードは実行時に入力） |
| [scripts/setup-junction.cmd](scripts/setup-junction.cmd) | `plugins/TrinityForge` のディレクトリジャンクションを張る（引数=バックエンド名・冪等）。**dev にも張る** |
| [scripts/server-loop.cmd](scripts/server-loop.cmd) | `stop` 後に自動で起動し直すループ。main / resource / dev 共通 |
| [scripts/sync-configs.ps1](scripts/sync-configs.ps1) | ArsPaper config の SHA-256 照合コピー、同名 jar の二重配置検出、ジャンクション有無の確認 |
| [scripts/reset-resource.ps1](scripts/reset-resource.ps1) | 週次リセット（予告 → 停止 → 削除 → 整合チェック → 起動 → 事前生成） |
| [scripts/restart-server.ps1](scripts/restart-server.ps1) | 日次再起動（予告 → `stop`。強制終了はしない） |
| [scripts/stop-network.ps1](scripts/stop-network.ps1) | ネットワーク全体を逆順で停止（`stop.flag` を置いてから `stop`。main は最後） |
| [scripts/show-status.ps1](scripts/show-status.ps1) | 何が上がっているか（プロセス＋3306/6379 の素性＋`stop.flag`） |
| [scripts/check-logs.ps1](scripts/check-logs.ps1) | **起動後に流す。** 既知の症状を拾う（特に「HuskSync が無効なのにサーバは起動している」） |
| [scripts/backup.ps1](scripts/backup.ps1) | 進行 DB と MariaDB の日次バックアップ |

設定は [ops-config.sample.psd1](ops-config.sample.psd1) を `ops-config.psd1` にコピーして編集する。
**RCON パスワードは設定ファイルに書かず、環境変数 `TF_RCON_MAIN_PASSWORD` / `TF_RCON_RESOURCE_PASSWORD` / `TF_RCON_DEV_PASSWORD` で渡す。**

### `.cmd` は ASCII だけで書く

cmd.exe は UTF-8 のバッチファイルを正しく読めない。マルチバイト文字があると
ファイル位置の計算がずれ、**行の途中から実行を始める**。実際に `server-loop.cmd` が
日本語コメント入りだった間、引数検査も `stop.flag` 判定も素通りして
**空回りする無限ループ**になっていた（2026-07-27 に実測）。

説明は `.ps1` と `.md` に置く（PowerShell は UTF-8 で問題ない）。
`run-selftest.ps1` が `ops` 配下の全 `.cmd` を走査して非 ASCII を落とす。

---

## 最重要の注意

`plugins/TrinityForge` は**メイン側の実体を資源サーバから参照しているだけ**で、実体は 1 つしかない。

**`rmdir /s` や `Remove-Item -Recurse` を資源側の `plugins\TrinityForge` に向けてはいけない。**
リンク先（全プレイヤーの進行データと全 config）が消える。

ops スクリプトは全て [scripts/lib/Common.ps1](scripts/lib/Common.ps1) の
`Remove-DirectorySafely` を経由し、ジャンクションとその配下を検出したら必ず中断する。
これが本当に効くことは [run-selftest.ps1](scripts/run-selftest.ps1) で実測できる。
