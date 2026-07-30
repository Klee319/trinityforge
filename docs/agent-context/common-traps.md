# Paper/Bukkit API とテストの共通落とし穴

このリポジトリ（TrinityForge本体、ArsPaper/EliteMobsフォーク含む）で繰り返し踏んできた、
「コードを読んだだけでは気づけない」「黙って壊れる」種類の非自明な事実だけを集めた恒久知識。
作業履歴・完了報告は含めない。新しいコードを書く/レビューする前に目を通すこと。

## テストの信頼性

### ⚠️ MockBukkit の未実装APIは「失敗」ではなく「SKIPPED」として素通りする
`org.mockbukkit.mockbukkit.exception.UnimplementedOperationException` は
`TestAbortedException` を継承しているため、JUnit はこれを FAILED ではなく SKIPPED として報告する。
**アサーションが1つも走らないままテストが「緑」に見える。**
実害例: `HumanEntity#damageItemStack` が未実装で、耐久消費テストが到達前に SKIPPED 化した。
`PotionMeta#getAllEffects()` も同様で、`PotionQualityListenerTest` 8件中5件が素通りしていた
（到達しなかったテストだけが緑という最悪の形）。

- 回避策1: 耐久消費は `HumanEntity#damageItemStack` を呼ばず、`Damageable` メタを直接操作して
  UNBREAKING の `1/(L+1)` 判定込みで自前実装する。
- 回避策2: `Mockito.spy()` で該当APIだけ個別にスタブする。
- 恒久対策: `src/test/java/com/trinityforge/testsupport/UnintendedSkipGuardListener.java` を
  `META-INF/services/org.junit.platform.launcher.TestExecutionListener` に登録し、
  例外の型で判別して意図しない SKIPPED をビルド失敗にしている（`@Disabled` や通常の
  `Assumptions` は巻き込まれない）。**skipped 件数が増えたら事故と疑うこと。**

### ⚠️ `Block#breakNaturally` は `BlockBreakEvent` を発火しない
一括伐採/一括採掘/範囲収穫などで連鎖ブロックを `breakNaturally` で割ると、
そのブロック分については「採取EXPが入らない」「道具の耐久も減らない」が**必ずセットで**起きる
（バニラの耐久消費もイベント経路にぶら下がっているため）。
連鎖分の補填は `gathering/ChainBreakSupport` が担っている。
**`BlockBreakEvent` を合成して代用してはいけない** — 採掘運/各種ギミック/ドロップテーブル/
設置ブロック追跡など10以上のリスナーが連鎖分にも反応し、収穫量が跳ね上がる。
（作物は硬度0なのでバニラでも鍬の耐久は減らない＝範囲収穫はEXPのみ補えばよい。）

## クラフト/インベントリ経路の罠

### ⚠️ `CraftItemEvent` 内でカーソルを書き換えると素材が消費されず複製する
CraftBukkit は「イベント発火 → `AbstractContainerMenu.clicked(...)`」の順で処理する。
`CraftItemEvent`(MONITOR等)のリスナー内で `player.setItemOnCursor(...)` すると、バニラは
「カーソルが空だから結果枠を取る」ではなく「同じ品がカーソルにある→マージ」の経路に入り、
装備類（最大スタック1）は上限0で `ResultSlot#onTake` が呼ばれず**素材が一切消費されない**。
手には自分で載せた完成品が残るため無限に増える。
→ **結果枠（`setCurrentItem`/`setResult`）だけを差し替えること。カーソルには絶対に触らない。**
※かつて「ドラッグ操作が原因」と誤診したことがあるが、バイトコード確認の上で訂正済み。
`InventoryDragEvent` 経由の quick-craft はカーソルの中身を配るだけでスロットから取り出さず、
結果枠への配布条件（`mayPlace`）は常に false なので、ドラッグ経路では原理的に複製は起こらない。
「カーソル==結果枠なら落とす」というヒューリスティックは通常操作を巻き込み、
キャンセル＋`updateInventory()`で自分でちらつきを生むだけなので入れてはいけない。

### ⚠️ `CraftItemEvent` は「結果枠をクリックした」だけで発火し、クラフト成立の証明にはならない
カーソルに結果と異なるアイテムを持って結果枠を左クリックすると、Paper は
`InventoryAction.NOTHING` を設定してバニラは何もクラフトしないが、**イベント自体は発火する**。
EXP/品質/統計などを動かす前に、必ず `InventoryAction` で「素材を消費する取り出しか」を判定すること。
- 成立: `PICKUP_*` / `MOVE_TO_OTHER_INVENTORY` / `HOTBAR_SWAP`
- カーソルが空のときだけ成立: `DROP_*_SLOT`
- 不成立: `NOTHING` / `PLACE_*`

`getAction()` は Mockito の既定値が `null` になるため、このガードを足すと**既存テストが
黙って全部「不成立」側に落ちる**ことがある点に注意（テスト側でも action を明示的にスタブする）。

### ⚠️ `custom:` 素材の `RecipeChoice.MaterialChoice` 登録が同形のバニラレシピを無言で潰す
PDC 付き実物と `ExactChoice` が `isSimilar` 不一致になる問題を避けるため、`custom:` 素材の照合は
材質のみで緩めて実装されている（TF の `CatalogRecipeRegistrar` / ArsPaper フォークの
`RecipeManager#resolveIngredient` 双方）。すると素のバニラ素材だけの盤面にもプラグインレシピが
一致してしまい、**Bukkit は一致レシピを1つしか返さない**ため同形のバニラレシピが選択肢ごと消える。
その後 per-slot 検証が正しく弾いて `setResult(null)` になり、**結果枠が空のまま**になる
（ログは `Level.FINE` にしか出ず、config側にもゲートが無いので原因に辿り着けない）。
実例: ArsPaper の `plank_scrap`（2×2戻しレシピ、base=OAK_PLANKS）が
`minecraft:crafting_table` を潰し「作業台が作れない」になった。
→ 対策として `shadowedVanillaResult`（盤面にカスタム品が無いときだけ自レシピを除外して
`Bukkit.recipeIterator()` を再照合し結果を戻す処理）を両側に実装済み。
**新しい `custom:` 材料を yml に足すたびに新しい衝突が起こり得るので、個別対処ではなく
経路（`shadowedVanillaResult`）で塞ぐのが正しい。**

## Paper プラグイン基盤の罠

### ⚠️ `paper-plugin.yml` は Bukkit形式の `softdepend:` を黙って捨てる
未知キーとして無視されるだけで、エラーも警告も出ない。Paper プラグインはクラスローダが
分離されているため、宣言していない依存プラグインのクラスは見えない。
他プラグインのAPIを触るなら必ず以下の形式で書くこと。
```yaml
dependencies:
  server:
    <PluginName>:
      load: BEFORE
      required: false
      join-classpath: true
```

### `damage_indicator`（被弾時の赤いハート状パーティクル）に対応する Bukkit イベントは無い
`sendParticles` の直接ブロードキャストのため、個数を制御するにはパケット層
（packetevents / ProtocolLib）しか手段がない。導入する場合は **compileOnly のバージョンを
稼働サーバの jar と厳密に一致させること**（gradle キャッシュのバージョンとサーバの
実バージョンが乖離していることがある）。

### パーティクルパケットの個数 `0` は「表示しない」ではない
バニラは count==0 を「offset を速度として1個だけ飛ばす」という別の意味に解釈する。
完全に非表示にしたいならパケットごとキャンセルするしかない。

## 並行処理

### ⚠️ 非同期スレッドから戦闘集計を呼ぶと ConcurrentModificationException になる（原因はスレッド跨ぎ、再入ではない）
経験値付与の一部（`NativeExperienceDispatcher#drain` など）は非同期タスクから
`PlayerStatAggregator#aggregate` のような集計処理を呼ぶことがある。「戦闘集計はメインスレッド専用」
という前提は成り立たない。同期化されていない `HashMap` をメインスレッドと非同期スレッドが
同時に触ると CME になり、ループ処理（例: 全プレイヤーへの属性再適用）がその場で中断する
（＝以降の対象に処理が当たらないまま黙って終わる）。CME は最も軽い症状で、無限ループや
エントリ消失も起こり得る。
**教訓: CME を見たら「再入」より先に「呼び出し元スレッド」を疑うこと**
（スタックトレース上の同名メソッドの連鎖はオーバーロード委譲であって再入とは限らない）。
対策は `Bukkit.isPrimaryThread()` で非同期側にキャッシュを触らせないこと。
非同期スレッドから `player.getInventory()` やPDCを読むこと自体は未解消の弱点として残る点に注意。

## SQLite

### ⚠️ check-then-act トランザクションは `BEGIN IMMEDIATE` が必須（`busy_timeout` では防げない）
JDBC の `setAutoCommit(false)` は既定で `BEGIN DEFERRED` を発行する。トランザクションは
読み取りとして始まり、最初の UPDATE で書き込みロックへ昇格しようとするが、その間に
**別コネクション**がコミットしていると SQLite はこの昇格を `SQLITE_BUSY_SNAPSHOT` で失敗させる。
**`PRAGMA busy_timeout` はこのエラーには効かない**（古くなったスナップショットは待っても
解決しないため、busy ハンドラを呼ばずに即座に失敗する）。「WAL + busy_timeout があるから
複数コネクションでも安全」は誤り。
- 対策: 接続時に `transaction_mode=IMMEDIATE` を指定する（sqlite-jdbc なら
  `SQLiteConfig#setTransactionMode` → `DriverManager.getConnection(url, props)`）。
  書き込みロックを最初に取れば昇格が起きず、競合は busy_timeout が吸収できる通常のロック待ちになる。
  単一コネクション運用では挙動が変わらないため入れておいて損はない。
- 発見しにくい理由: (1) 本番が単一コネクションだと絶対に再現しない。2つ目のコネクションが
  同じファイルを触った瞬間に初めて顕在化する。 (2) `SQLException` を catch して `false` を
  返す実装（例: ポイント不足での拒否と同じ戻り値）だと、拒否なのか例外なのか見分けがつかない。
  テストでは戻り値だけでなく**ログを傍受して握り潰しを検出する**こと。
- 検証時の注意: 同一JVM内の2コネクションでは検証しきれない。プロセス間のファイルロックと
  `-shm` を通すには `ProcessBuilder` で本当に子JVMを起動する必要がある。また2プロセスを
  同方向に競合させるとロックステップで片側が全勝し証拠にならない。**互いに逆方向から
  攻めさせる**と中央で衝突して結果が分散する。

## PlayerDropItemEvent / 経路依存

### ⚠️ `PlayerDropItemEvent` 発火時にスタックが既に引かれているかは経路依存
バニラ経路とGeyserが翻訳した統合版のドロップとで、イベント発火時点でインベントリから
アイテムが引かれているかの順序が違う。そのため「イベント時に `getItemInMainHand()` を
記憶し、次tickで記憶値と一致するかで他プラグインの介入を検出する」という設計は、
**正常系で毎回不一致になり機能全体が沈黙する**（実例: GeyserExtraのオフハンド切替が
「ただ捨てるだけ」に退行した）。
- 正しい形: イベント時に確定させてよいのは**ホットバーのスロット番号だけ**。中身の判定は
  次tickに実際の状態を見て行う。`occupant` が空（全スタックドロップ）か、ドロップしたアイテムと
  同種（部分ドロップの残り）なら受理し、それ以外は中止して返却する。これなら引き算が
  前でも後でも正しく動く。
- 部分ドロップの残りは再構成後のスタックに含まれるため、オフハンド等へ移す際に元スロットを
  **上書きしないと複製する**。「上書きしてよいか」の判定自体は必須で、判定をやめる方向の
  修正は複製バグに戻るので注意。

## 権利・レシピの設計原理

### 消費キャンセル型の「+1で返す」リスナーは、全キャンセラより後の優先度でないと複製になる
素材消費をキャンセルしてから `+1` して返すタイプのリスナー（醸造素材の保存など）で、
別のキャンセラが**同じ優先度**で後から登録されると、先に走った側の `+1` だけが生き残り
無限複製になる。一般則として、こうした「consume-cancel の `+1` ミラー」は
**全キャンセラより後の優先度**（例: `HIGHEST`）で登録すること。

### モブ死亡ドロップに割り込むリスナーは `MONITOR` でないと消える
外部プラグイン（EliteMobs等）のルート処理が `NORMAL` の `EntityDeathEvent` の中で
`getDrops()` を丸ごとクリアすることがある。ドロップを上乗せ/上書きするリスナーは
**`MONITOR`** で登録しないと、それより早いタイミングで消される。

### 属性(Attribute)の付与・削除はスコープを自プラグインの名前空間に限定する
モブスポーン時などに他プラグインが付けた attribute modifier まで一緒に消してしまう
実装ミスが起きやすい。削除対象は必ず自分（TF）が付与した namespace のものだけに絞ること。

## config-editor（フロントエンド）の罠

### カテゴリ選択状態は `WeakMap`（キー＝オブジェクト同一性）で管理されている
`activeByHost` のようなホスト単位の状態管理を `WeakMap` で行っている場合、渡すオブジェクトが
浅いクローンだとキーの同一性が崩れ、選択状態が別画面に届かず絞り込みが常に無効になる。
「同じ内容に見えるオブジェクトを渡しているのに動かない」ときはまずオブジェクト同一性を疑うこと。

## 関連
- [./forks-and-mobs.md](./forks-and-mobs.md)
- [./ops-build-deploy.md](./ops-build-deploy.md)
- [./config-editor.md](./config-editor.md)
