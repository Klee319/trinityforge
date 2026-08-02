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

### ⚠️ ガチャの天井とレートアップの確定排出先は「そのプールで weight が最小のエントリ」

`gacha.yml` に「これがジャックポット」と宣言する項目は無い。天井（`pity.threshold`）到達時に必ず出るものと
レートアップ（`gacha_rate_bonus`）の対象は、どちらも**最小 weight のエントリ**として暗黙に決まる。
同値タイがあると天井がその中でランダムに割れる（tier5 で `ANCIENT_DEBRIS` と防具が同 weight 1 だったため
天井の約半分が古代の残骸になっていた）。**各プールのジャックポットは必ず単独最小 weight にすること。**

なお `gacha.yml` の `entries[].item` には **`custom:` を付けない**（`mob-overrides` の drops や
レシピ素材とは逆の規約）。詳細は上記「アイテムID解決」節。

### モブ死亡ドロップに割り込むリスナーは `MONITOR` でないと消える
外部プラグイン（EliteMobs等）のルート処理が `NORMAL` の `EntityDeathEvent` の中で
`getDrops()` を丸ごとクリアすることがある。ドロップを上乗せ/上書きするリスナーは
**`MONITOR`** で登録しないと、それより早いタイミングで消される。

### 属性(Attribute)の付与・削除はスコープを自プラグインの名前空間に限定する
モブスポーン時などに他プラグインが付けた attribute modifier まで一緒に消してしまう
実装ミスが起きやすい。削除対象は必ず自分（TF）が付与した namespace のものだけに絞ること。

## アイテムID解決（TFカタログ ↔ ArsPaper）の罠

### ⚠️ アイテムIDの `custom:` 接頭辞は「共有 seam でも」剥がさないといけない

config-editor は custom アイテムの選択を**必ず `custom:<id>` へ正規化して保存する**
（`public/js/util.js` の `materialInput`）。一方 Java 側は `MobOverridesConfig` /
`MobLevelTableConfig` / `RecipeIngredient` / `FoodGimmickConfig` など **10 個のドメインが
各自ローカルで剥がしている**。この非対称のせいで、共有リゾルバ
（`CrossPluginItemResolver`）だけが剥がし忘れていても他が動くので気づきにくい。

2026-07-31 まで実際に剥がしておらず、**editor から書いたガチャ景品 / アチーブメント報酬アイテム /
ドロップ表が全部解決失敗**していた。`create()` と `exists()` の両方に必要。
`custom:` 明示トークンはバニラ Material へフォールバックさせないこと
（`custom:DIAMOND` が黙ってダイヤになる）。

**症状が「エラー」で出ないので特に危険**: `GachaListener` は「景品が解決できない券は消費しない」
fail-safe を持つため、解決失敗は**「当たるまで無料で引き直せる」**という形で現れる。
無料引き直しを見たら、まず景品IDの解決を疑うこと。

### ⚠️ カスタムアイテムidは Ars と TF の 2 つの PDC キーに分かれている

同じ id 空間を 2 プラグインで分担している。id で照合する箇所は**必ず両方読む**こと。

| 実体 | PDCキー |
|---|---|
| ArsPaper `materials.yml` の品 | `arspaper:custom_item_id` |
| TF `items/catalog.yml` の品 | `trinityforge:catalog_id`（`PdcKeys.ITEM_CATALOG_ID`） |

**どちらにあるかは直感に反する**: `thread_empty` と `mage_*` 防具 28 種は
（Ars の機構で使うのに）**TF カタログ側**にある。逆にガチャ券・コア類・`tf_scrap` は
**Ars の `materials.yml` 側**にある。「Ars の機能で使うから Ars 側だろう」は成り立たない。

Ars 側だけを読んでいたため、**TFカタログ由来の儀式 39 件（`mage_*` 昇格 24 + スレッド 15）が
全て成立しなかった**事故がある（`Pedestal`/`RitualCore` の `saveStoredItem`）。
フォーク側は `PdcHelper.getCrossPluginItemId` に一本化済み。

**キー名を手書きしないこと。** ArsPaper は `paper-plugin.yml` で TF を
`required: true` + `join-classpath: true` のハード依存にしているので `PdcKeys` を直接参照できる。
手書きしていた `SourceAutoConsume` が `trinityforge:item_catalog_id`（実在しない名前）を持っていて
無言で外れていた。

### ⚠️ `CrossPluginItemResolver` の既定解決順は「TFカタログ優先」── Ars 側にしか正しいPDCを刻めないIDは `external-source:` を明示しないと一生ソケット/挿入できない

`items/catalog.yml` に Ars 側実体（`materials.yml`/`threads.yml` 等）と同じ id のエントリを置くと、
`CrossPluginItemResolver#create` は既定で「TFカタログ → Ars → バニラ Material」の順に解決する。
TFがこの経路（ダンジョンドロップ・`gacha.yml`・実績報酬・図鑑報酬など）で配ったアイテムは
**TF自身のPDC（`trinityforge:catalog_id` 等）しか持たず、Ars側が判定に使うPDC
（例 `arspaper:custom_item_id` / `arspaper:thread_item_type`）を一切持たない**。見た目・CMDは
正しいので気づきにくいが、Ars側の機構（例: `ThreadGui#isEffectThread`）がそのPDCで可否判定する物は
**装着不可のまま**になる（2026-08-02、スレッド全種でこの状態だったことが判明。`95cb6b5`で修正）。

- 対策: `catalog.yml` の該当エントリに `external-source: arspaper`（現状有効値はこの1つ）を足すと、
  **そのIDに限り**解決順が「外部プラグイン(Ars) → カタログ」へ反転する。Ars側が解決できなければ
  カタログへフォールバックする（Ars 非導入構成の後方互換）。
- **全体の解決順は反転しない。** Ars 側 loot-tables 経路（`ItemCostRef#createStack`）は元から
  「Ars → TrinityForgeBridge → PAPER」で正しいため、グローバルに反転すると逆向きの事故を作る。
- 新しい Ars 由来 id を catalog.yml に足すたびに、この宣言漏れが再発しうる。TF給付経路で配る
  Ars由来アイテム（スレッド・source系アイテム等）を追加したら、**Ars側のPDC判定に依存する機構が
  あるかどうか**を確認し、あれば `external-source:` を付け忘れないこと。

### ⚠️ `draft: true`（準備中）は「参照面を絞る」だけでは不十分。解決系メソッド自身にゲートが要る

`ItemCatalogConfig#load` は `template(id)`/`all()` から draft を落とすが、`CrossPluginItemResolver#create`
はカタログ miss を「カタログに無いID」としか解釈せず、Ars フォールバック・バニラ `Material` フォールバック
へそのまま素通りさせていた（2026-08-02 発見）。`items/catalog.yml` の draft かつ
`external-source: arspaper` の品が `combat/mob-level-table.yml` のドロップに実在すると、**Lv帯の敵撃破で
実際にドロップする**。対策は `create()`/`exists()` の先頭で `itemCatalog.isDraft(bare)` を明示チェックする
こと（`CrossPluginItemResolver.java`）。ドロップ表・ガチャ景品欄などの**参照自体は消してはいけない**
（「準備中でも設定はできる」が仕様）── 消すべきは解決（配布）の方だけ。

同じ理由で `ItemCatalogConfig#isDraft` は `custom:` 接頭辞を剥がしてから判定する必要がある
（他のカタログID解決系メソッドと同じ規約。剥がさないと editor が正規化した `custom:<id>` 形式の
draft ID が `GachaListener#withoutDraftPrizes` のフィルタをすり抜け、解決失敗→
「景品ロスト防止で券を消費しない」フェイルセーフに乗って実質無限ガチャになる）。

draft を分母に使う集計（例: 図鑑 `scope: all`/`category` の候補集合）も個別に除外が要る。
`collection.yml` のカテゴリ列挙自体は draft ID を含んだままでよい（消してはいけない）ので、
`CollectionService` 側で `itemResolver.isDraft(id)` を通してから候補へ足す。

### ⚠️ 儀式の台座リング物理上限は 48（16 ではない）── Y±1 の3段を見落とすと過小評価する

`ArsPaper` フォーク `RitualManager#findNearbyPedestals` はコア周囲 X/Z ±2 の外周16マスを
**Y±1 の3段ぶん**走査して同じ ingredient リストへ積む（16×3=48）。`RitualRecipe#matches` は
台座アイテムの multiset 完全一致しか見ず段を区別しないため、19台程度のレシピは Y±1 の段を
使えば普通に成立する。フォーク側の `RitualManager` 冒頭 javadoc（`PEDESTAL_DISTANCE` のコメント）と
`ThreadRitualRecipeConfigTest` のテスト定数は**どちらも「16」のまま**（このタスクでは fork 未修正、
別リポジトリ・別担当）。**「16」という記述をコードで裏取りせずに定数化すると過小評価する**
（2026-08-02 に実際にこの誤診で超過4レシピの素材を不要に軽量化する事故があった）。
TF 側の上限は `ItemCatalogConfig.MAX_RITUAL_PEDESTALS`（48）と
`tools/config-editor/lib/schema.js` の `MAX_PEDESTAL_TOTAL`（48）の2箇所を必ず同時に直すこと。

## config-editor（フロントエンド）の罠

### カテゴリ選択状態は `WeakMap`（キー＝オブジェクト同一性）で管理されている
`activeByHost` のようなホスト単位の状態管理を `WeakMap` で行っている場合、渡すオブジェクトが
浅いクローンだとキーの同一性が崩れ、選択状態が別画面に届かず絞り込みが常に無効になる。
「同じ内容に見えるオブジェクトを渡しているのに動かない」ときはまずオブジェクト同一性を疑うこと。

## yml を「生成」するときの罠

### ⚠️ Python の `yaml.safe_load` は重複キーを黙って通すが、editor の JS `yaml` は投げる
`yaml.safe_load` は同一マッピング内に同じキーが2回あっても**警告なしで後勝ち**にする。
一方 config-editor が使う JS の `yaml` は `YAMLParseError: Map keys must be unique` を投げるので、
**Python 側の検証を全部通ったファイルが editor 側のテストをファイルごと落とす**。
生成器で既存ブロックを複製・加筆するときは、加筆するキーが**継承元に既に無いか**必ず確認する
（`infinity_*` を種にすると `bleed-chance` などが既にある個体だけ重複する、という形で当たる）。

### 生成器は「表を書き写す」のでなく「出荷 yml の既存ブロックを読んで意図したキーだけ上書きする」
仕様書の数値を転記すると、転記ミスと**仕様書自体の誤り**が区別できなくなる。
既存ブロックを読んで差分だけ当てる方式にすると、
「そのキーが実在しない」「その帯が存在しない」「その値は既存最大を超えている」が
生成時に例外として出る。実際にこの方式で仕様書側の誤りが7件出た（2026-08-02）。

### 率のフォーマットは値のフォーマットと分ける
`0.12` を小数第1位で丸めると `0.1`、`0.015` は `0.01` になる。桁が落ちても yml としては妥当なので
テストも通ってしまう。率専用に小数第3位まで残す書式を用意すること。

## リソースパック / CMD の罠

### CMD は「素材ごと」の割り当て。`range_dispatch` は登録漏れを無言で前のモデルに落とす
`NETHERITE_SWORD#5700` と `MACE#5700` は**別のアイテム**。`resourcepack/cmd-registry.json` は
再生成できない永続台帳で、番号の再利用は禁止。

`assets/minecraft/items/<material>.json` の `range_dispatch` は**「値以下で最大の threshold」**を選ぶので、
threshold を足し忘れた CMD は**エラーにならず、直前のシリーズのモデルで描画される**。

- threshold の中身は**そのファイル自身の `fallback` を丸ごとディープコピー**する。
  `fallback` の形は素材ごとに違い（`minecraft:model` / `minecraft:select` / `minecraft:condition` /
  入れ子の `range_dispatch`）、`{"model": ...}` 決め打ちで書くと `KeyError` になるか、
  もっと悪いことに**形の違う素材だけ描画が壊れる**。
- **その素材の json 自体が存在しない場合は何も足さなくてよい**（バニラが描画する）。
  2026-08-02 時点で該当するのは CROSSBOW / NETHERITE_SHOVEL / AMETHYST_SHARD /
  LEATHER_CHESTPLATE / LEATHER_HELMET。
- `resourcepack/build_item_pack.py` は「json にある threshold が台帳に無い」を落とすが、
  **逆（台帳にあって json に無い）は通す**。つまりビルドが通っても描画が正しいとは限らない。

## アイテム設計の罠

### インベントリに入れただけのアイテムのステータスは1つも効かない
`PlayerStatAggregator` が読むのは**防具4部位・メインハンド・オフハンドだけ**。
「アクセサリ」枠は存在しないので、装飾品を作るなら `offhand-stats-apply: true` を立てて
**オフハンド装備**にするか、機構から作る必要がある。放置すると完全な死にアイテムになるが、
**テストも lint も何も言わない**。

### `use-skill` は使用要件であって分類マーカーではない
斧に `use-skill: WOODCUTTING` と書くと「伐採スキルが要る斧」になるだけで、
**その斧でモブを殴ったときに伐採EXPが入る**（採取ツールにも付いているため）。
スキル別の効果が欲しいときは専用のステータスキー（`<skill>_exp_bonus` など）を足す。

### 儀式レシピは `(core-item, pedestal-items)` が同一だと先勝ちで後発が無言死する
`RitualRecipeRegistry#findMatch` は `findFirst`。同じ核と同じ台座構成のレシピを2件以上登録すると、
2件目以降は**レシピ帳に出るのに永久に作れない**。共通素材で揃えたいときは、
アイテムごとの識別素材を台座に1つ入れて回避する。

### 語彙キーを1本足すと登録先が6箇所ある
`StatVocabulary` / `stats/lore.yml`（カテゴリ内で `order` が一意）/ `combat/base-stats.yml` /
`command/StatsCategory.java` / `docs/config-reference/combat/stat-caps.md` /
editor の `labels.js`・`tf-base-stats.js`・`tf-lore.js` ＋ `test/tf-stat-caps-tab.test.js` の件数。
1つでも漏らすと drift テストが落ちる（＝落ちたら「テストが古い」ではなく登録漏れを疑う）。

### 使用要件のゲートは `UseRequirementsConfig.enforce` の【コード上の既定が false】
`true` にしているのは出荷 `progression/use-requirements.yml` のほう。
テストが自前の一時ディレクトリに yml を書く場合、`enforce: true` を書かないと
**ゲートが素通りして、そのテストが何も検証しないまま緑になる**。

## 関連
- [./forks-and-mobs.md](./forks-and-mobs.md)
- [./ops-build-deploy.md](./ops-build-deploy.md)
- [./config-editor.md](./config-editor.md)
