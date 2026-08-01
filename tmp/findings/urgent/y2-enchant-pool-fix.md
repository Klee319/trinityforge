# U17 修正記録 — エンチャントテーブルでバニラに出ないエンチャント(修繕等)が出る

レーン: `y2-enchant-pool` / ブランチ `work/y2-enchant-pool-u17`（ベース `aa97412`）

## 1. 根本原因（実コードで裏取り済み・引き継ぎ記述どおり）

`TrinityForge/src/main/java/com/trinityforge/listeners/EnchantLuckListener.java` の
`pickCompatibleEnchant` が **`Registry.ENCHANTMENT` を全走査**していて、フィルタが

- `already.containsKey(candidate)`（重複）
- `candidate.canEnchantItem(item)`（付与可能か）
- `conflictsWith`（排他）

の3つだけだった。**「エンチャントテーブルの抽選対象か」を一切見ていない。**
そのため treasure 系が候補に混ざる。呼び出しは `onEnchant`（`EnchantItemEvent`, `EventPriority.LOW`）で、
`stats/enchant-luck.yml` の `extra-enchant-chance-per-luck` × `enchant_luck` の確率で発動する。

実測した treasure 系（MockBukkit の registry 43件を `isTreasure()` で走査、**7件**）:

```
minecraft:mending          修繕
minecraft:frost_walker     氷結歩行
minecraft:soul_speed       魂の速さ
minecraft:swift_sneak      忍び歩き
minecraft:binding_curse    束縛の呪い
minecraft:vanishing_curse  消滅の呪い
minecraft:wind_burst       風の衝撃   ← 引き継ぎメモに挙がっていなかったが同じ経路で出る
```

引き継ぎメモは6件挙げていたが、実測では **`wind_burst`（風の衝撃）も混ざっていた**ので7件が正しい。
残り36件がテーブルで出得るもの。

## 2. 判断した設計

### 第一経路: タグを引く（指示どおり実現できた）

Paper 1.21.11 の paper-api を `javap` で直接確認し、以下が**すべて存在すること**を裏取りした:

- `io.papermc.paper.registry.keys.tags.EnchantmentTagKeys.IN_ENCHANTING_TABLE`（`TagKey<Enchantment>`）
- `org.bukkit.Registry#hasTag(TagKey)` / `#getTagValues(TagKey)`

したがって **ハードコードのブラックリストは採らず、`#minecraft:in_enchanting_table` タグを引く**。
バニラ側でエンチャントが増減しても自動追随する。

### 第二経路（フォールバック）: `Enchantment#isTreasure()`

**MockBukkit 4.110.0 は `Registry#hasTag` / `#getTagValues` をどちらも未実装**で、
`UnimplementedOperationException` を投げる（実測。probe テストで確認）。
= テスト環境ではタグが引けない。そこで `isTreasure()` で篩う経路へ落とす。

- `isTreasure()` は 1.21 で `@Deprecated`（"enchantment types are now managed by tags"）だが、
  契約は「looting / 交易 / 釣りでしか手に入らない = テーブルでは出ない」なので母集団としては同義。
  **MockBukkit はこれを正しいバニラデータで実装している**（上の7件を返す）。
- **`isDiscoverable()` は使ってはいけない。** javadoc は「エンチャントテーブルで見つかるか」と
  書いてあるが、実測では **修繕が `true`** を返す（実体は `#minecraft:on_random_loot` 相当）。
  javadoc を信じて使うと修繕が素通りする。
- タグが引けなかったときに**全走査へ fail-open してはいけない**（それが元のバグそのもの）ので、
  catch 節は必ず `isTreasure()` 経路へ落ちる形にした。

### 設定項目は増やしていない

指示どおり「バニラで出ないものが出る」バグの修正として扱い、緩めるための config キーは追加していない。

### テスト用 seam

`pickCompatibleEnchant` を `static` にし、**母集団を `Iterable<Enchantment>` で受ける**形にした
（`enchantingTablePool()` も package-private static）。
タグ解決を通さずに母集団を渡せるので、テストが `UnimplementedOperationException` →
SKIPPED に化けて素通りすることがない。

## 3. テスト結果（実走）

### 対象クラス

```
> Task :test
BUILD SUCCESSFUL in 9s
```

`build/test-results/test/TEST-com.trinityforge.listeners.EnchantLuckListenerTest.xml`:

```
tests="15" skipped="0" failures="0" errors="0"
```

`build/test-results/unintended-skips.txt` は**生成されていない**（＝MockBukkit 未実装 API による
意図しない SKIPPED は無し）。

追加したテスト9件:

| テスト | 縛っている内容 |
|---|---|
| `enchantingTablePoolExcludesTreasureEnchants` | treasure 7件が母集団に入らない |
| `enchantingTablePoolStillContainsOrdinaryEnchants` | 効率強化/ダメージ増加/耐久力/ダメージ軽減/ドロップ増加は残る |
| `treasureEnchantWouldHaveBeenApplicableUnderOldFilters` | 修繕は剣に付与可能＝旧 `canEnchantItem` では弾けていなかったことの反バキューム証明 |
| `extraEnchantNeverGrantsTreasureEnchantAcrossManySeeds` | seed 300通りの end-to-end で treasure が1件も付与されない（かつ「1件も追加されていない」空振りを弾く） |
| `pickCompatibleEnchantReturnsCandidateFromGivenPool` | 母集団から選べる |
| `pickCompatibleEnchantOnlyDrawsFromThePool` | 母集団外は選ばれない |
| `pickCompatibleEnchantSkipsAlreadyPresentEnchant` | 既存フィルタ（重複）の回帰 |
| `pickCompatibleEnchantSkipsInapplicableEnchant` | 既存フィルタ（`canEnchantItem`）の回帰 |
| `pickCompatibleEnchantSkipsConflictingEnchant` | 既存フィルタ（`conflictsWith`）の回帰 |

### ミューテーション確認（テストが本当にバグを捕まえるかの検証）

`enchantingTablePool()` のフィルタを一時的に旧挙動（全件通す）へ戻して実走:

```
EnchantLuckListenerTest > enchantingTablePoolExcludesTreasureEnchants() FAILED
EnchantLuckListenerTest > extraEnchantNeverGrantsTreasureEnchantAcrossManySeeds() FAILED
15 tests completed, 2 failed
```

→ 回帰テストが実際に U17 を捕まえることを確認した上で修正へ戻した。

### 全体テスト

```
2988 tests completed, 3 failed, 2 skipped
```

失敗3件は**既知・想定どおり**（worktree にフォークが存在しないため）:

- `LegacyValhallaRuntimeContentTest > operationalConfigsContainNoNonGuiValhallaItemsRecipesOrCmds`
- `SpellBreakMarkerDriftTest > forkActuallySetsAndRemovesTheMarkerAroundCallEvent`
- `SpellBreakMarkerDriftTest > forkAndTrinityForgeAgreeOnTheMarkerKey`

**skipped 2件は私の変更とは無関係で、どちらも意図的なスキップ**
（`unintended-skips.txt` は生成されていない = MockBukkit 由来ではない）:

- `OfflineMobImportRunner`「offline: convert a live custombosses tree into mob-profiles.yml」
  — 環境変数3本が揃ったときだけ走る `assumeTrue` ゲート
- `NativeProgressionStabilizationContractsTest#prestigeRefundUsesLiveYamlCost_characterizesMutableConfigRefund`
  — `@Disabled("Pre-schema-v2: ...")`

## 4. 踏んだ落とし穴（**恒久知識に値する。次に同じ場所を触る人が必ず踏む**）

### ⚠️ `Enchantment` 定数をテストの static フィールドに置くと、比較が常に false になり検証が消える

最初に書いたテストは treasure 判定を `Set<Enchantment>.contains(Enchantment.MENDING)` で見ていた。
**これは除外が効いていなくても常に通る**（＝何も検証しない緑）。原因:

- テストクラスの **static 初期化は `MockBukkit.mock()` より前に走る**ため、
  `Enchantment.MENDING` などの定数は mock 前の registry のインスタンスに束縛される。
- `Registry.ENCHANTMENT` がテスト中に返すのは**別インスタンス**（実測 id `824900551` vs `1250848393`）。
- `Enchantment` は `equals`/`hashCode` を上書きしていないので**同一性比較**になり、`contains` は常に false。

この壊れ方に気づけたのは「普通のエンチャントは母集団に残る」という**逆向きのアサーションを入れていたから**
だけで、除外テスト単体では永久に緑だった。

→ **対策: エンチャントの比較は必ず `getKey().toString()`（`"minecraft:mending"` 等の文字列）で行う。**
`Enchantment` を static フィールドに保持しない。テスト内に注意書きのコメントを残してある。
なお定数を**テストメソッド内でだけ**使う分には整合するが、
「static 定数 ↔ registry 反復インスタンス」を混ぜた瞬間に実行順依存で壊れる。

### MockBukkit 4.110.0 の未実装 API（実測）

| API | 状態 |
|---|---|
| `Registry#hasTag` / `#getTagValues` | **未実装**（`UnimplementedOperationException`） |
| `Enchantment#getWeight` | **未実装** |
| `Enchantment#isTreasure` / `#isCursed` / `#isDiscoverable` | 実装あり（バニラ相当のデータ） |

## 5. 残した未対応と理由

- **`docs/agent-context/common-traps.md` への追記をしていない。**
  上記4節の2件（Enchantment 定数の同一性罠 / MockBukkit の未実装タグ API）は恒久知識として
  書く価値があるが、`common-traps.md` は**他レーンと衝突しやすい共有ファイル**なので、
  並列作業中の独断追記を避けた。**統合時に一括で追記してほしい**（文面はこの節を流用できる）。
- **`reports/ACTIVE_RECORD.md` に追記していない。** 「1波に1人しか触れない」choke file のため。
  統合担当が U17 を閉じる際に、本ファイルを根拠として取り消し線＋解決根拠を入れてほしい。
- **フォーク側のカスタムエンチャントは検証範囲外。** worktree にフォークが存在しないため。
  なお設計上、タグ経路では EliteMobs 等が独自登録したエンチャントは
  `#minecraft:in_enchanting_table` に入っていない限り**候補から外れる**（＝テーブルで出なくなる）。
  これは「EM の独自入手経路で得るもの」なので正しい挙動だと判断した。
  ただし `isTreasure()` フォールバック経路では独自エンチャントの `isTreasure()` が false を返すと
  候補に入り得る。**実サーバではタグ経路が使われるので実害は無い**が、差異として記録しておく。
- **実機での目視確認はしていない**（配備はユーザー実行のため）。

## 6. 統合時に必要な作業

- **`TrinityForge.java` の配線変更は不要。** リスナーのコンストラクタ・登録の形は変えていない
  （`pickCompatibleEnchant` を private → package-private static にしただけで、公開 API 面は不変）。
- **yml の変更は無し。** 新しい config キーは追加していないので、**jar だけ配備すれば直る**。
- `ops/reports/*.md` は `./gradlew test` が書き換えるので、**commit していない**
  （このブランチでは `git checkout --` で戻してある）。
- 触ったファイルは2本だけ:
  - `TrinityForge/src/main/java/com/trinityforge/listeners/EnchantLuckListener.java`
  - `TrinityForge/src/test/java/com/trinityforge/listeners/EnchantLuckListenerTest.java`
  - （+ この `tmp/findings/urgent/y2-enchant-pool-fix.md`）
