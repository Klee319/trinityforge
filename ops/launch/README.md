# launch — 起動・停止・検査の入口

配置先は `D:\game\minecraft\PaperServer\Velocity_for_TF\launch\`。
**正本はリポジトリの `ops/launch/`** で、`deploy.cmd` で配置先へ丸ごと配り直せる。

パスとヒープと jar 名は **[launch-config.cmd](launch-config.cmd) の 1 箇所だけ**に書いてある。
リポジトリを移動したりバージョンを上げたらそこを直す。

---

## 起動と停止

| ファイル | 用途 |
|---|---|
| **[start-all.cmd](start-all.cmd)** | **通常はこれ 1 本。** 正しい順序で全部起動する |
| [stop-all.cmd](stop-all.cmd) | 逆順で安全に停止する（`stop.flag` を置く） |
| [status.cmd](status.cmd) | 何が上がっているか一覧する |
| [start-mariadb.cmd](start-mariadb.cmd) | 依存: MariaDB（サービス。通常は自動で上がっている） |
| [start-garnet.cmd](start-garnet.cmd) | 依存: Garnet（Redis 互換） |
| [start-main.cmd](start-main.cmd) | バックエンド main |
| [start-resource.cmd](start-resource.cmd) | バックエンド resource |
| [start-dev.cmd](start-dev.cmd) | バックエンド dev（検証用） |
| [start-velocity.cmd](start-velocity.cmd) | プロキシ Velocity |

個別のものは、1 台だけ上げ直すときに使う。

### ウィンドウの出かた

**サーバ 4 台（velocity / main / resource / dev）は Windows Terminal の 1 ウィンドウに
タブでまとまる。** ウィンドウ名は `TrinityForge`（[launch-config.cmd](launch-config.cmd) の
`WT_WINDOW`）。あとから `start-dev.cmd` を単体で叩いても**同じウィンドウにタブが増える**だけで、
新しいウィンドウは開かない。

タブではなく画面分割にしたい場合は、各 `start-*.cmd` の `new-tab` を
`split-pane` に変えるだけでよい。

Windows Terminal が無い環境では、従来どおり 1 台 1 ウィンドウで開く（自動判定）。

> タブを 1 つに**統合**することはできない。各サーバのコンソールはそれぞれ独立した stdin を
> 持つ必要があり（コンソールにコマンドを打てなくなるため）、1 つの画面に流し込むと
> どのサーバに入力しているのか区別できなくなる。

**Garnet はウィンドウを出さない。** バックグラウンドで動き、出力は
`D:\game\minecraft\Garnet\logs\garnet.log` / `garnet.err` に落ちる
（`--logger-level Warning` なのでほとんど増えない）。
起動しているかどうかは `status.cmd` で見る。
MariaDB は元から Windows サービスなのでウィンドウを持たない。

### 起動順（`start-all.cmd` がこの順に呼ぶ）

```
MariaDB → Garnet → preflight → main → resource → dev → Velocity
```

- **MariaDB と Garnet はバックエンドより先。** 繋ぐ先が無いと HuskSync は enable に失敗し、
  **それでもサーバの起動は止まらない**ので、同期されないまま運用する事故になる
- **preflight で 0 件になってから先へ進む。** `start-all.cmd` は 1 件でも検出したら
  サーバを起動せずに中断する
- **main → resource → dev。** `plugins/TrinityForge` は実体を共有しているので、
  初回スキーママイグレーションを同時に走らせない
- **Velocity は最後。** 先に上げるとプレイヤーが「繋がるが飛べない」状態を踏む

停止は逆順（`stop-all.cmd` がやる）。Velocity を先に落とせば、
プレイヤーが切断されてからバックエンドの保存が走る。

### `stop.flag` について

各バックエンドは `server-loop.cmd` で包まれていて、**`stop` すると自動で起動し直す**
（定期再起動をこれで実現している）。落としたままにするには `stop.flag` が必要:

- `stop-all.cmd` は RCON `stop` の**前**に `stop.flag` を置く
- `start-*.cmd` は起動時に `stop.flag` を消す

`status.cmd` が `stop.flag` の有無を表示する。「起動したのにすぐ落ちる」ときはここを見る。

> **強制終了はしない。** ジャンクション経由で共有している SQLite をフラッシュ途中で
> 殺すと、全プレイヤーの進行データを壊しうる。落ちきらない場合は失敗として報告する。

---

## testkit — 壊れていないことを確かめる

| ファイル | 何を見るか | サーバ稼働 |
|---|---|:---:|
| **[testkit/check-all.cmd](testkit/check-all.cmd)** | **下の 4 つを全部** | どちらでも |
| [testkit/check-ops-scripts.cmd](testkit/check-ops-scripts.cmd) | 自己テスト（削除ガードの実測）＋破壊的スクリプトの空撃ち | 不要 |
| [testkit/check-datastores.cmd](testkit/check-datastores.cmd) | MariaDB / Garnet が**何であるか**（プロトコルで判定） | 不要 |
| [testkit/check-config.cmd](testkit/check-config.cmd) | 設定のやり残し（preflight ＋ 各 apply の `-DryRun`） | 不要 |
| [testkit/check-logs.cmd](testkit/check-logs.cmd) | ログから既知の症状を拾う | **要**（起動後） |

**いつ流すか**

- サーバを上げる前 → `check-config.cmd`（`start-all.cmd` が preflight を内蔵しているので省略可）
- サーバを上げた後 → `check-logs.cmd`。**ここを見ないと HuskSync が無効のまま気付かない**
- 何か変えたあと / 定期的に → `check-all.cmd`

`check-logs.cmd` が拾うのは**この構成で実害が出る症状だけ**（汎用のログ解析ではない）:
HuskSync の enable 失敗、`Connection refused`、`Access denied`、
`Unable to verify player details`（forwarding secret の不一致）、`Ambiguous plugin name`、
共有 SQLite のエラー、属性 modifier の二重付与。

> `check-ops-scripts.cmd` の中で `reset-resource.ps1 -DryRun` が走る。
> **削除対象の絶対パスは必ず目視すること。** `plugins\TrinityForge` が含まれていたら
> 実行してはいけない（スクリプト側も拒否するが、設定ミスに気付く最後の機会）。

---

## 配置し直す

リポジトリ側を直したら:

```bat
ops\launch\deploy.cmd
```

配置先の `launch\` を上書きする。`launch-config.cmd` も上書きされるので、
配置先だけで書き換えた値は消える。**設定はリポジトリ側で直すこと。**

関連: [../RUNBOOK.md](../RUNBOOK.md) 手順 13（起動と再起動）／
[../PERFORMANCE.md](../PERFORMANCE.md)（ヒープの決め方）／
[../SECURITY.md](../SECURITY.md)（ポートとバインド）
