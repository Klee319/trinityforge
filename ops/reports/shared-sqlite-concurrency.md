# 共有 SQLite 同時アクセス検証レポート

> このファイルは `SharedSqliteConcurrencyTest` が自動生成する。手で編集しない。

## 1. 何を確かめたか

資源サーバ分離では `plugins/TrinityForge/` を NTFS ディレクトリジャンクションで共有し、
**メインと資源の2つの Paper プロセスが同一の `player_progression.db` を読み書きする**。
この方式はコード変更ゼロで進行データ共有と config パリティを同時に得られる代わりに、
「別プロセスからの SQLite 同時アクセスが壊れないこと」に全体重を預けている。
**サーバを立てる前にここを潰すのが本テストの目的**であり、落ちた場合は方式を破棄して
MariaDB 実装へ切り替える判断材料になる。

## 2. 実行環境

| 項目 | 値 |
|---|---|
| Java | 21.0.9 (Oracle Corporation) |
| OS | Windows 11 10.0 / amd64 |
| DB ファイル | C:\Users\T-319\AppData\Local\Temp\junit-9111556715262396859\player_progression.db |
| SQLite PRAGMA | journal_mode=WAL, busy_timeout=5000, synchronous=NORMAL（SqliteProgressionRepository が接続時に適用） |

## 3. 別プロセス競合の実測

2つの子 JVM を起動し、壁時計バリアで足並みを揃えたうえで、
**同一プレイヤーの同一パーク 60 件を両方が全範囲で奪い合う**（一方は昇順、他方は降順）。
配ったポイントはちょうど 60 個（1 パーク 1 ポイント）なので、正しく直列化されていれば
**付与は合計でちょうど 60 件**になる。
これを超えれば二重消費、下回れば取りこぼし（`database is locked` の握り潰し）。

| 項目 | 値 |
|---|---|
| 競合パーク数（＝配ったポイント数） | 60 |
| プロセス数 | 2（親を含めると3） |
| 総試行回数 | 120 |
| プロセス1の付与件数 | 34 |
| プロセス2の付与件数 | 26 |
| **付与合計** | **60** |
| 握り潰された SQL 例外 | 0 |
| 試行間ウェイト | 3 ms |
| 所要時間 | 3346 ms（3000 ms の開始バリアを含む） |

## 4. 判定

- 付与合計 = 配ったポイント数 → **二重消費なし・取りこぼしなし**
- 残ポイント 0 / 消費ポイント 60 / DB のパーク行数 60 の3つが一致
- 握り潰された SQL 例外 0 件 → `busy_timeout=5000` が実効している
- 子プロセスのタイムアウトなし（上限 120 秒）

**結論: ジャンクション方式（プラン F11）は成立する。**
ただし次章の本体修正が入っていることが条件。

## 5. 本テストが検出した欠陥と、その修正

**最初にこのテストを書いた時点では、上の検証は落ちた。** 記録として残す。

### 症状

2コネクション以上から `unlockPerk` を並行実行すると、以下の2種類の例外が出て
解放が取りこぼされた（同一 JVM の2コネクション・別プロセスの双方で再現）。

```
org.sqlite.SQLiteException: [SQLITE_BUSY] The database file is locked
org.sqlite.SQLiteException: [SQLITE_BUSY_SNAPSHOT] Another database connection has already written to the database
```

### 原因

`SqliteProgressionRepository` のトランザクション5箇所（`unlockPerk` / `prestige` /
`saveProgressionTransition` / `saveAdminProgressionEdit` / `resetPlayer`）は全て
**check-then-act**（残高を読む → 差し引く）だが、JDBC の `setAutoCommit(false)` は
既定で `BEGIN DEFERRED` を発行する。この場合トランザクションは読み取りとして始まり、
最初の UPDATE で初めて書き込みロックを取りに行く。その間に別コネクションが
コミットしていると、SQLite はこの昇格を `SQLITE_BUSY_SNAPSHOT` で失敗させる。

**そして `PRAGMA busy_timeout` はこのエラーに対して働かない。** 既に古くなった
スナップショットは待っても解決しないため、SQLite は busy ハンドラを呼ばずに即座に失敗する。
つまり `busy_timeout=5000` があってもこれらのメソッドは保護されていなかった。

さらに悪いことに、`unlockPerk` は `SQLException` を catch して `SEVERE` ログを出したうえで
`false` を返す。呼び出し側から見ると**「ポイント不足で拒否された」と区別がつかない**。
プレイヤーには「解放できません」と表示され、原因はログの奥にしか残らない。

### 修正

接続時に `transaction_mode=IMMEDIATE` を指定し、全トランザクションを
`BEGIN IMMEDIATE` で開くようにした（`SqliteProgressionRepository#immediateTransactionProperties`）。
書き込みロックを最初に取るので昇格が発生せず、競合は `busy_timeout` が吸収できる
通常のロック待ちになる。**単一コネクション運用では挙動が一切変わらない**ため、
現行のシングルサーバに対する影響はない。

### なぜ今まで表面化しなかったか

本番はリポジトリのインスタンスが1つで、全メソッドが `synchronized`、さらに
`ExecutorProgressionRepository` が単一 DB スレッドへ直列化している。
**2つ目のコネクションが同じファイルを触った瞬間に初めて顕在化する**バグであり、
資源サーバ分離はまさにその条件を作り出す。サーバを立てる前に潰せたのが本テストの成果。

## 6. 運用上の前提（RUNBOOK に反映すること）

1. **同一マシン・同一ローカルディスク限定。** SQLite のロックはネットワーク共有上では
   正しく機能せず DB が破損する。サーバを別マシンへ分けるならこの方式は使えない。
2. **起動は main → resource の順。** 本テストは親プロセスが先にスキーマを作ってから
   子を起動している。初回のスキーママイグレーションを同時実行させないこと。
3. **`unlockPerk` は SQL 例外を握り潰して `false` を返す。** 実運用でロック競合が起きても
   例外にはならず「解放できなかった」に化けるため、`SEVERE` ログの監視が必要。
4. **ジャンクションを `rmdir /s` や `Remove-Item -Recurse` で消さない。** リンク先の実体
   （＝全プレイヤーの進行データと全 config）が消える。本構成で最も重大な事故ポイント。

## 7. 同一 JVM 側の検証（参考）

別プロセス版と同じ競合を、同一 JVM 内の2コネクション＋2スレッドでも実行している。
SQLite から見れば同一プロセスなので本番条件ではないが、`unlockPerk` の
check-then-act がコネクションをまたいでも壊れないことの確認になる。

- `alternatingWritesAcrossTwoConnectionsRoundTrip` — 交互 load/save が双方向に見える
- `concurrentUnlocksAcrossTwoConnectionsNeverDoubleSpend` — 2スレッド競合で二重消費なし

## 8. 再実行方法

```
gradlew :TrinityForge:test --tests "com.trinityforge.ops.SharedSqliteConcurrencyTest"
```

別プロセス検証は本テストが `ProcessBuilder` で子 JVM を起動して行うため、
別途スクリプトを実行する必要はない。
