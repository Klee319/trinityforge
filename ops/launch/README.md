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

## 運用スクリプト

`ops\scripts\*.ps1` の入口。**中身は PowerShell 側にあり、`.cmd` は引数をそのまま渡すだけ**
（`reset-resource.cmd` だけ例外。後述）。だから引数の意味を知りたいときは
`.ps1` の先頭のコメントを読めばよい。

> **`.cmd` が ASCII なのは趣味ではない。** cmd.exe は UTF-8 のバッチを読むとバイト位置を
> 取り違えて**行の途中から実行を始める**。日本語の説明はこの README にしか書かない。
> `testkit\check-ops-scripts.cmd` が非 ASCII の混入を検出する。

> **`.ps1` はリポジトリ側を直接実行する**（`launch-config.cmd` の `OPS_SCRIPTS`）。
> `deploy-launch.cmd` で配り直すのは `.cmd` だけで、`.ps1` のコピーは配置先に存在しない。
> つまり **PowerShell 側を直したら配り直しは要らない。**

### 見るだけ（何も書かない）

| ファイル | 用途 | 主な引数 |
|---|---|---|
| [preflight.cmd](preflight.cmd) | **起動前の関門。** MariaDB / Garnet の疎通、HuskSync の資格情報と features、forwarding secret の一致 | なし |
| [check-stopped.cmd](check-stopped.cmd) | バックエンドが 1 台も動いていないことの確認。jar を差し替える前に | `-Server <名前...>` / `-Quiet` |
| [status.cmd](status.cmd) | 何が上がっているか（プロセス＋3306/6379 の素性＋`stop.flag`） | なし |
| [testkit/check-logs.cmd](testkit/check-logs.cmd) | **起動後に流す。** ログから既知の症状だけを拾う | なし |

**`preflight.cmd` を飛ばさないこと。** HuskSync は DB へ繋げないと enable に失敗するが、
**サーバの起動は止まらない**。同期されないまま運用する事故になる。

### 日常運用

| ファイル | 用途 | 主な引数 |
|---|---|---|
| [backup.cmd](backup.cmd) | 進行 DB（SQLite）と MariaDB の `husksync` / `luckperms` を退避 | `-DryRun` |
| [restart.cmd](restart.cmd) | 1 台を予告付きでクリーン再起動 | `-Target main\|resource\|dev\|both` / `-WarnMinutes 5,1` / `-DryRun` |
| [sync-configs.cmd](sync-configs.cmd) | ArsPaper config をメイン→資源へ同期し整合チェック | `-DryRun` / `-SkipArsPaper` |

**`backup.cmd` はワールドを対象にしない**（メインは Backuper、資源は毎週消える前提）。
サーバ稼働中でも安全に走る（SQLite は `.db` / `-wal` / `-shm` の 3 点セットで取る）。

**`restart.cmd` は強制終了しない。** 共有 SQLite をフラッシュ途中で殺すと全プレイヤーの
進行データを壊しうる。落ちきらない場合は失敗として報告される。

### 設定を書き込む

| ファイル | 用途 | 主な引数 |
|---|---|---|
| [apply-husksync-config.cmd](apply-husksync-config.cmd) | HuskSync の `config.yml` を全バックエンドで同一内容に揃える | `-BaseFrom <名前>` / `-DryRun` |
| [apply-velocity-forwarding.cmd](apply-velocity-forwarding.cmd) | forwarding secret を各 `paper-global.yml` へ反映 | `-Target <名前>\|all` / `-DryRun` |
| [apply-block-break-exploits.cmd](apply-block-break-exploits.cmd) | 岩盤剥がしの可否（`unsupported-settings`）を各 `paper-global.yml` で揃える | `-Target <名前>\|all` / `-Disable` / `-IncludePistonDuplication` / `-DryRun` |
| [seed-backend-configs.cmd](seed-backend-configs.cmd) | あるバックエンドの**設定だけ**を他へ配る（データは持って行かない） | `-From` / `-To` / `-Overwrite` / `-DryRun` |
| [set-resource-pack.cmd](set-resource-pack.cmd) | `server.properties` にリソースパックの URL と SHA-1 を書く | `-Url` / `-Sha1`（**必須**）/ `-Server` |
| [prune-geyser-items.cmd](prune-geyser-items.cmd) | GeyserExtra の `custom_items.json` から**再生成できる分だけ**を消す | `-Apply` / `-MaxDelete <n>` |
| [install-economy-plugins.cmd](install-economy-plugins.cmd) | 経済一式（VaultUnlocked / Jecon / JeconCacheName / PlaceholderAPI）の jar と Jecon の `config.yml` を全バックエンドへ | `-DryRun` / `-SourceDir` / `-PlaceholderApiJar` |

**`install-economy-plugins.cmd` は jar を置くので、1 台でも稼働中なら中断する**
（[../RUNBOOK.md](../RUNBOOK.md) 手順 19）。3 台が共有するのは **MariaDB の `jecon` DB** であって
config ファイルではない。`plugins\Jecon` はサーバごとの実体なので、同じ `config.yml` を 3 回書く。
**パスワードは引数に取らない**（`TF_JECON_DB_PASSWORD`、無ければその場で入力）。

**forwarding secret がずれると全員が `Unable to verify player details` で入れなくなる。**
他はどこも壊れて見えないので、`-DryRun` が全台「変更なし」になることを先に確かめる。

**`custom_items.json` は永続台帳であってキャッシュではない。** 再観測できないエントリは
ファイルごと消すと戻らない。だから `prune-geyser-items.cmd` は選択的にしか消さない。

### ワールドとデータを消す（取り返しがつかない）

| ファイル | 用途 | 既定 |
|---|---|---|
| [install-datapacks.cmd](install-datapacks.cmd) | 構造物/地形データパックを資源サーバへ配置 | 配置する（`-DryRun` で下見） |
| [reset-world.cmd](reset-world.cmd) | 指定バックエンドのワールドを作り直す（**正式開幕用**） | **下見**（`-Apply` で実行） |
| [purge-player-data.cmd](purge-player-data.cmd) | プレイヤー個人データと権限の全消去 | **下見**（`-Apply` で実行） |
| [reset-resource.cmd](reset-resource.cmd) | 資源ワールドの週次リセット（予告→停止→削除→再配置→起動→プリジェネ） | **下見**（`--apply` で実行） |

```bat
reset-world.cmd                        下見（main と resource）
reset-world.cmd -Apply
reset-world.cmd -Target dev -Apply     dev だけ
reset-world.cmd -Apply -Delete         退避せず即削除

purge-player-data.cmd                  下見
purge-player-data.cmd -Apply
purge-player-data.cmd -Apply -PurgeGroups   LuckPerms のグループ定義も消す

reset-resource.cmd                     下見（強制的に -DryRun が付く）
reset-resource.cmd --apply
reset-resource.cmd --apply -SkipPregen
```

> **`reset-resource.cmd` の `--apply` だけ書式が違う。**
> 他の 3 つは PowerShell 側が既定で下見だが、**`reset-resource.ps1` は既定で本当に消す。**
> 引数なしで叩いた瞬間に資源ワールドが飛ぶのを避けるため、この `.cmd` は
> `--apply` を書かないかぎり `-DryRun` を強制する。

押さえておくこと:

- **`reset-world.cmd` はワールドを削除ではなく退避する。** `_world-backup-<日時>\` へ
  `Directory.Move` で移すだけなので一瞬で終わり、追加の空き容量も要らず、巻き戻せる
- **`purge-player-data.cmd` は SQL を書き出すだけで DB は触らない。** 出力された SQL を
  流さないと、HuskSync がインベントリを DB から復元して**元に戻る**
- **LuckPerms のグループ定義は既定で残る。** 消えるのは所属（`user_permissions` の
  `group.<名前>` ノード）だけ。設計ごと作り直すなら `-PurgeGroups`
- **データパックの正本は `world` の外**（`datapacks-source`）。`world\datapacks` にだけ置くと
  最初のリセットで全部消え、**資源ワールドが黙ってバニラ地形に戻る**（無警告）
- **どれもサーバが 1 台でも動いていたら中断する。** 起動中に消しても停止時に書き戻される

全体の順番（正式に開き直すとき）は [../RUNBOOK.md](../RUNBOOK.md) 手順 18。
週次リセットは手順 13、データパックは手順 17。

### ここに入口が無いもの

**リポジトリ側の作業用**で、稼働中のサーバを触らないもの:
`wip-audit.ps1` / `list-wip-config.ps1` / `export-head-config.ps1` / `run-selftest.ps1` /
`editor-*.mjs`。前 2 つは `deploy-config-*.cmd` が内部で呼び、`run-selftest.ps1` は
`testkit\check-ops-scripts.cmd` から走る。

**一度きりの修復**: `fix-elitemobs-drop-config.ps1`（配備済み EliteMobs に残った古い
`elite-drop-sources` を直す）。常設の入口を作ると「毎回叩くもの」に見えるので置いていない。
必要なときに `ops\scripts\` から直接叩く。

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
バックエンドへ配る。EliteMobs はビルドをスキップしても、成果物 uberjar が実機と違えば
main / dev へコピーする。**サーバが 1 台でも動いていたら何もせず中断する**
（稼働中の jar 差し替えは `NoClassDefFoundError` になり、JVM 再起動以外に復旧手段が無い）。
オプションと更新判定の限界は [../RUNBOOK.md](../RUNBOOK.md) 手順 13-5。

> **`--config` を付けないと yml は配られない。** 手順 4/5 で `[CHECK]` として
> 「リポジトリと稼働中で食い違っている yml」を一覧するので、**1 行でも出たらそのファイルの設定は
> サーバに届いていない**。2026-08-03 に、稼働中の `items/catalog.yml` が前日のままで
> `draft: true` が 1 件も入っておらず、**準備中アイテムがゲーム内で全部入手できる**状態になっていた
> （コードは正しかったので原因究明に何時間もかかった）。config に住む修正は、jar を何度ビルドし直しても
> 「直っていない」ようにしか見えない。

関連: [../RUNBOOK.md](../RUNBOOK.md) 手順 13（起動と再起動）／
[../PERFORMANCE.md](../PERFORMANCE.md)（ヒープの決め方）／
[../SECURITY.md](../SECURITY.md)（ポートとバインド）
