# testkit — 壊れていないことを機械的に確かめる

**どのスクリプトも本番ファイルを書き換えない。** 書き換える系（`apply-*` / `reset-resource` /
`restart-server` / `stop-network` / `backup`）は必ず `-DryRun` で呼んでいる。

まとめて流すなら [check-all.cmd](check-all.cmd) 1 本。

---

## 4 つの検査

### [check-ops-scripts.cmd](check-ops-scripts.cmd) — 土台（サーバ稼働：不要）

`run-selftest.ps1` の自己テスト（24 件）と、破壊的スクリプトの空撃ちを流す。
自己テストは**ジャンクション保護と削除対象ガードを実測している**（「ジャンクションを
`Remove-Item -Recurse` するとリンク先の実体まで消える」という Windows の挙動が、
週次リセットで進行データを消し飛ばす一番の危険なので）。

> **`reset-resource.ps1 -DryRun` の出力は必ず目視すること。**
> 削除対象の絶対パスに `plugins\TrinityForge` が含まれていたら実行してはいけない。
> スクリプト側も拒否するが、設定ミスに気付く最後の機会。

RCON パスワードの環境変数（`TF_RCON_MAIN_PASSWORD` など）が未設定だと、
`stop-network` と `restart-server` の空撃ちはそこで止まる。**それは正しい失敗** —
本番で止められない設定だという意味なので、環境変数を設定して流し直す。

### [check-datastores.cmd](check-datastores.cmd) — データストアの素性（不要）

3306 / 6379 に**何がいるか**をプロトコルで確かめる。ポートが開いているかだけでは、
別のプロセスが掴んでいる場合に気付けない。

- MariaDB は pre-auth ハンドシェイクからバージョン文字列を読む
- Garnet は `INFO` の `garnet_version` を見る（Garnet は `redis_version` も返すので、
  先に `garnet_version` を確認しないと Redis と見分けがつかない）

`SpeaksMysql` / `SpeaksResp` が `True` になっていること。`Reachable` が `True` なのに
`Speaks*` が `False` なら、**そのポートは別のプロセスが使っている。**

### [check-config.cmd](check-config.cmd) — 設定のやり残し（不要）

- `preflight.ps1` … 起動前チェック本体。**終了コードはこれに従う**
- `apply-velocity-forwarding.ps1 -DryRun` … 全サーバで「変更なし」なら secret は一致
- `apply-husksync-config.ps1 -DryRun` … 差分が出たら未反映（パスワードは尋ねない）
- `sync-configs.ps1 -DryRun` … config のドリフトと jar の二重配置

### [check-logs.cmd](check-logs.cmd) — 稼働中の症状（**要：起動後**）

**起動したら必ずこれを流す。** 一番拾いたいのは
**「HuskSync の enable が失敗しているのにサーバは起動している」**ケースで、
ログを読まないとインベントリが同期されないまま運用してしまう。

既定は各ログの末尾 800 行（起動 1 回分）。全体を見るには引数をそのまま渡せる:

```bat
testkit\check-logs.cmd -All
```

拾う症状は**この構成で実害が出るものだけ**（汎用のログ解析ではない）。
HuskSync の enable 失敗 / `Connection refused` / `Access denied` /
`Unable to verify player details`（forwarding secret 不一致＝全員ログイン不能）/
`Ambiguous plugin name` / 共有 SQLite のエラー / 属性 modifier の二重付与。

終了コードは ERROR が 1 件でもあれば 1、WARN だけなら 0。

---

## 終了コード

`check-all.cmd` は 4 つのうち 1 つでも失敗すれば 1 を返す（途中で止めずに全部流す）。

親フォルダの [../README.md](../README.md) に、いつどれを流すかをまとめてある。
