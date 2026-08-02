# launch — 起動・停止・検査の入口

配置先は `D:\game\minecraft\PaperServer\Velocity_for_TF\launch\`。
**正本はリポジトリの `ops/launch/`** で、`deploy-launch.cmd` で配置先へ丸ごと配り直せる。

> **`deploy.cmd` と `deploy-launch.cmd` は別物。**
> `deploy.cmd` = **プラグイン（jar）** をビルドして 3 バックエンドへ配る。
> `deploy-launch.cmd` = **この launch フォルダ自体**（起動スクリプト）を配置先へコピーする。

パスとヒープと jar 名は **[launch-config.cmd](launch-config.cmd) の 1 箇所だけ**に書いてある。
リポジトリを移動したりバージョンを上げたらそこを直す。

---

## 起動と停止

| ファイル | 用途 |
|---|---|
| **[start-all.cmd](start-all.cmd)** | **通常はこれ 1 本。** 正しい順序で全部起動する |
| **[deploy.cmd](deploy.cmd)** | **ソースに変更があればビルドして** 3 バックエンドへ jar を配る（[../RUNBOOK.md](../RUNBOOK.md) 手順 13-5） |
| [stop-all.cmd](stop-all.cmd) | 逆順で安全に停止する（`stop.flag` を置く） |
| [status.cmd](status.cmd) | 何が上がっているか一覧する |
| [start-mariadb.cmd](start-mariadb.cmd) | 依存: MariaDB（サービス。通常は自動で上がっている） |
| [start-garnet.cmd](start-garnet.cmd) | 依存: Garnet（Redis 互換） |
| [start-main.cmd](start-main.cmd) | バックエンド main |
| [start-resource.cmd](start-resource.cmd) | バックエンド resource |
| [start-dev.cmd](start-dev.cmd) | バックエンド dev（検証用） |
| [start-velocity.cmd](start-velocity.cmd) | プロキシ Velocity |

個別のものは、1 台だけ上げ直すときに使う。

### 配備先を 1 台に絞る（`deploy.cmd --server`）

```
deploy.cmd --server dev                    dev だけに配る
deploy.cmd --server main --server dev      複数指定は --server を繰り返す
deploy.cmd --server Dev_Server             ディレクトリ名でも短縮形でも通る（大小無視）
```

`--server` は **jar のコピー・ArsPaper の config コピー・「稼働中か」の判定**の 3 つを同時に絞る。
つまり **dev だけ止めて dev だけに配り、main と resource は動かしたまま**にできる。
対象外のサーバが稼働中であることを理由に配備が止まることはない。

**絞れないものが 1 つある: TrinityForge の yml。** 他のバックエンドの `plugins\TrinityForge` は
config ホスト（`TF_CONFIG_HOST` = Main_Server）へのジャンクションなので、1 回書けば 3 台すべてに
届く。そのため `--config` と併用したときは:

- 対象に config ホストが**含まれる** → コピーするが「全台に届く」と警告を出す
- 対象に config ホストが**含まれない** → **TF の yml はスキップ**する
  （黙って書くと「触らないでと言われたサーバ」を変えてしまうため）

**`--restart` とは併用できない**（エラーで止まる）。`stop-all` / `start-all` はネットワーク全体が
単位で、`stop-network.ps1` に台別の選択が無いため、併用すると**対象外のサーバを止めたまま
放置する**ことになる。1 台だけ差し替えるときは、そのサーバのコンソールで止めてから
`deploy.cmd --server <name>` を実行し、`start-dev.cmd` などで個別に上げ直す。

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

### 起動スクリプト（この launch フォルダ）を配り直す

リポジトリ側を直したら:

```bat
ops\launch\deploy-launch.cmd
```

配置先の `launch\` を上書きする。`launch-config.cmd` も上書きされるので、
配置先だけで書き換えた値は消える。**設定はリポジトリ側で直すこと。**

配置先の `launch\` は**ジャンクションではなく実体のコピー**なので、
リポジトリ側を直しただけでは配置先に反映されない。`deploy.cmd` を配置先から実行したときは、
リポジトリ側の `ops/launch/` が新しければ末尾で `[NOTE]` を出して知らせる。

### プラグイン（jar）をビルドして配る

```bat
D:\game\minecraft\PaperServer\Velocity_for_TF\launch\deploy.cmd
```

TF 本体・ArsPaper・EliteMobs の 3 つを、**前回ビルド以降にソースが変わったものだけ**ビルドして
3 バックエンドへ配る。**サーバが 1 台でも動いていたら何もせず中断する**
（稼働中の jar 差し替えは `NoClassDefFoundError` になり、JVM 再起動以外に復旧手段が無い）。
オプションと更新判定の限界は [../RUNBOOK.md](../RUNBOOK.md) 手順 13-5。

関連: [../RUNBOOK.md](../RUNBOOK.md) 手順 13（起動と再起動）／
[../PERFORMANCE.md](../PERFORMANCE.md)（ヒープの決め方）／
[../SECURITY.md](../SECURITY.md)（ポートとバインド）
