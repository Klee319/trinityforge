# Y1 敵対的レビュー: U4「カスタム装備のエンチャントがはがせない」

- 対象ブランチ: `work/y1-grindstone-u4` / コミット `8cbfb6b`（ベース `aa97412` の直上、1コミット）
- レビュー実施日: 2026-08-01
- 判定: **ACCEPT_WITH_NOTES**（HIGH なし。MEDIUM 3 / LOW 6）

---

## 0. 事実確認（報告の裏取り）

### 0.1 テスト実走（報告のコピペではなく自分で実行）

```
cd .claude/worktrees/wf_01789c6c-3ea-1/TrinityForge
./gradlew test --offline "-Dorg.gradle.java.home=C:\Program Files\Java\jdk-21"
→ 2989 tests completed, 3 failed, 2 skipped
```

- 失敗3件はいずれも **フォーク不在**（worktree に `fork-handoff/*/fork` が存在しない）に起因する既知分のみ:
  - `LegacyValhallaRuntimeContentTest` 1件
  - `SpellBreakMarkerDriftTest` 2件（`forkActuallySetsAndRemovesTheMarkerAroundCallEvent` / `forkAndTrinityForgeAgreeOnTheMarkerKey`）
  - `grep -l "<failure" build/test-results/test/*.xml` の結果もこの2クラスのみ。**それ以外の失敗はゼロ。**
- skipped 2件も本レーン外の既存分（`OfflineMobImportRunner`、`NativeProgressionStabilizationContractsTest.prestigeRefundUsesLiveYamlCost_...`）。
- 対象2クラスの XML を直接確認:
  - `TEST-...GrindstonePreserveListenerTest.xml` → `tests="7" skipped="0" failures="0" errors="0"`
  - `TEST-...CatalogVanillaOperationGuardListenerTest.xml` → `tests="15" skipped="0" failures="0" errors="0"`
- **報告のテスト結果は完全に正確。** MockBukkit の SKIPPED 化け（既知の罠）も発生していない。

### 0.2 巻き込みコミットの有無

`git show --name-only` の7ファイルは全て自分の領域:

```
TrinityForge/src/main/java/com/trinityforge/TrinityForge.java
TrinityForge/src/main/java/com/trinityforge/listeners/CatalogVanillaOperationGuardListener.java
TrinityForge/src/main/java/com/trinityforge/listeners/GrindstonePreserveListener.java
TrinityForge/src/main/java/com/trinityforge/stats/ItemFactory.java
TrinityForge/src/test/java/com/trinityforge/listeners/CatalogVanillaOperationGuardListenerTest.java
TrinityForge/src/test/java/com/trinityforge/listeners/GrindstonePreserveListenerTest.java
tmp/findings/urgent/y1-grindstone-u4-fix.md
```

- 他セッション WIP（`catalog.yml` / `item-stats.yml` / `lore.yml` / `resourcepack/` / `ops/reports/`）は **1つも含まれていない**。
- `reports/ACTIVE_RECORD.md` も未編集（1波1人ルール順守）。
- テストが書き換える `ops/reports/*.md` 2本は worktree に dirty のまま残っており、commit されていない（報告どおり）。
- **`git add -A` 相当の事故は無い。**
- yml 変更ゼロ = **「勝手に設定項目を増やした」も無い。**

### 0.3 反証して落とした「HIGH候補」2件（実装は正しい）

レビュー中に HIGH になりうると疑ったが、実コードを読んで **偽陽性と確定** した。統合担当が同じ疑いを持たないよう記録する。

1. **「`clone + stamp` は `CraftRollMods.NONE` で鍛冶ロールパークの PDC を潰すのでは」→ 潰さない。**
   `GrindstonePreserveListener.java:92` の `itemFactory.stamp(out, seed, quality)` は
   `ItemFactory.java:184` 経由で `CraftRollMods.NONE` を渡すが、
   `ItemData.setCraftRollMods`（`pdc/ItemData.java:187-198`）は
   `if (mods.isZero()) return;` で早期 return するため、clone が持ち込んだ
   `ITEM_CRAFT_ROLL_UP` / `_DOWN_REDUCTION` / `_INSET_DELTA` はそのまま残る。
   その後 `assemble` は `data.craftRollMods()` を読み直すので、クラフト時ロール補正は正しく再現される。
   → 実装担当の「create ではなく clone+stamp」の理由付けはこの点でも正当。

2. **「既に組み立て済みの clone に再度 assemble すると attribute modifier / lore が二重計上されるのでは」→ されない。**
   `AttributeApplier.apply`（`stats/AttributeApplier.java:141`）が先頭で `clearAllAttributeModifiers(meta)` を呼ぶ完全置換方式。
   lore も `ItemAssembler.java:250` の `meta.lore(lore)` で置換。
   `applyToolEnchants`（`ItemAssembler.java:317-357`）も PDC 台帳ベースの置換方式。
   → 二重計上は構造的に起こらない。

3. **rollSeed の引き直しは無い。**
   `GrindstonePreserveListener.java:92` は `source.rollSeed().ifPresent(...)` のみで、
   seed が無いアイテムには **新規発行せず stamp 自体をスキップ** する。
   「砥石で厳選ロールをガチャする」exploit は入っていない。

### 0.4 U4 の主症状は実際に直っている

カタログ武器 + プレイヤー付与エンチャント + 片側スロットのみ、という最頻ケースは
`CatalogVanillaOperationGuardListener.java:189-191`（`isEmptySlot(lower)` → return）で
ガードを素通りし、`GrindstonePreserveListener` が復元済み結果を返す。
**死にコード化は解消されている。**

---

## MEDIUM

### M1. 「外せるものが無ければ結果枠を出さない」抑止が、U4 の元の症状（＝何も出ない）を再現しうる

- 根拠: `TrinityForge/src/main/java/com/trinityforge/listeners/GrindstonePreserveListener.java:69-75`
- 設計から追加された新ルール（実装担当も「設計から変えた2点」として自己申告済み）。
  「復元後のエンチャント構成が元と完全一致 → `setResult(null)`」。
- この条件に入るのは **アイテムのエンチャントが TF 由来（`tool-enchant-*` ステ or `enchant-glow`）だけ** のとき。
  プレイヤーから見える結果は「砥石に入れても何も出ない」で、**U4 の元の報告文と区別が付かない**。
- 現時点の出荷 yml では実害なし:
  - `tool-enchant-*` は `stats/item-stats.yml` に **1件も設定されていない**（`grep -c "tool-enchant-"` = 1、しかもコメント行）。
  - `enchant-glow: true` は `items/catalog.yml:86` の `koujien`（`material: BOOK`）1件のみで、
    ここでの抑止はむしろ正しい（glow を剥がされてEXP源になるのを防ぐ）。
- **しかし `tool-enchant-efficiency` は死にキーではない。** `ItemAssembler.java:73` の `TOOL_ENCHANT_PREFIX` は生きており、
  `tools/config-editor/lib/registry.js:52` は「tool-enchant-efficiency を統合し上限を撤廃」と明記している
  （既知の制約により採掘速度は効率強化エンチャントのレベル操作でしか実装できない）。
  つまり **config 担当がツルハシに `tool-enchant-efficiency` を1行足した瞬間に、
  そのツルハシは「砥石に何も出ない」状態へ戻る**。コード側にもテスト側にも警告が無い。
- 併せて: プレイヤーが付けたのが呪い（curse）だけのアイテムも、バニラが呪いを残すため
  「復元後＝元」となり結果枠が消える。バニラは結果を出す（EXP 0）ので微妙に挙動が変わる。
- 推奨: (a) 抑止判定を「TF が復元したエンチャント集合」と「元のエンチャント集合」の**差分**で行い、
  ログか lore で理由を出す／(b) 最低限、`item-stats.yml` の `tool-enchant-*` セクションに
  「設定すると砥石の結果枠が出なくなる場合がある」旨の日本語コメントを置く。

### M2. 無限EXPループは「閉じている」とは言えない（報告の記述が不正確）

- 根拠: `GrindstonePreserveListener.java:69-75` の抑止条件、および
  `CatalogVanillaOperationGuardListener.java:205-217`（金床でエンチャント本の適用を新規許可）
- 抑止が効くのは「外せるものが**ゼロ**のとき」だけ。安いエンチャント本を1つ付ければ抑止は外れ、
  バニラの砥石EXPは **その回に剥がされる全エンチャント**（＝TF が直後に戻す分を含む）で計算される。
  → `安い本を金床で付ける → 砥石で外す` を繰り返すと、TF 付与分のEXPが毎周回払い出される。
- 「金床で本を付ける」レグは **今回の変更で初めてカタログ装備に対して開いた**（従来は `setResult(null)` で全拒否）。
  つまりこのサイクル自体が本 PR の副産物。
- 現状は `tool-enchant-*` 未設定なので実害ゼロ（M1 と同じ潜在条件）。
  ただし報告の「ループは閉じている」は **条件付きでしか正しくない**ので、
  `ACTIVE_RECORD.md` へのクローズ追記でそのまま書くと後続を誤導する。文言を直すこと。

### M3. 金床のエンチャント本許可が「改名」も同時に通し、復元も再組み立ても無い

- 根拠: `CatalogVanillaOperationGuardListener.java:219-227`（`appliesEnchantmentBook`）
- 判定が「第2スロットが `EnchantmentStorageMeta` か」だけなので、
  バニラ金床の1操作で可能な **「本の適用 + 改名」** がまとめて通る。
  従来は `setResult(null)` で全拒否だったため、**カタログ装備の改名は今回初めて可能になった**。
  `ItemAssembler.assemble` は `displayName` を一切書かないので、この改名は
  `ItemRefreshListener` を通しても**元に戻らない（不可逆）**。
  タスク指示は「エンチャント本の適用」だけを許可と書いており、改名解禁は暗黙の仕様変更。
- 併せて、金床側には `GrindstonePreserveListener` に相当する復元/再組み立てが無い:
  - 新しく乗ったエンチャントは TF の lore に反映されない（見た目とステ表示の食い違い）。
  - 結果の PDC / CustomModelData はバニラ金床が入力をコピーする前提に**依存**している。
    実装担当も「金床側の見た目復元は範囲外」と自己申告済みだが、
    **拒否 → 許可へ倒した以上、実サーバでの目視確認は砥石と同じく必須**
    （カタログ品 + エンチャント本を金床に通して、CMD・表示名・lore・PDC が残るか1回見る）。
- 推奨（最小）: `appliesEnchantmentBook` に `inventory.getRenameText()` が
  元の表示名と異なる場合は許可しない、を足す。もしくは改名解禁を明示的に仕様として承認する。

---

## LOW

### L1. `appliesEnchantmentBook` が「カタログ品が第1スロットにあること」を確認していない

- 根拠: `CatalogVanillaOperationGuardListener.java:219-227`
- `containsCatalogItem(inventory)` が真になる理由が **第2スロット側のカタログ品** でもこの分岐は通る。
  `catalog.yml` に `ENCHANTED_BOOK` 素材のアイテムは現在存在しない（`grep ENCHANTED_BOOK` = 0件）ので潜在だが、
  このクラスの契約は「カタログ品を素材として食う操作を拒否」であり、この枝だけ契約が抜けている。
- 1行修正で済む: `return isCatalog(first) && !isCatalog(second) && second.getItemMeta() instanceof EnchantmentStorageMeta;`

### L2. `restoreEnchantGlow` の付与順が `ItemFactory.create` と逆

- 根拠: `GrindstonePreserveListener.java:92-93`（stamp → glow）vs `ItemFactory.buildIdentity`（glow → assemble）
- `ItemMeta.addEnchant(ench, 1, true)` は既存レベルを **上書き** するため、
  `enchant-glow: true` と `tool-enchant-unbreaking` を両方持つアイテムができた場合、
  砥石を通すたびに unbreaking が 1 へ黙って格下げされる（`create()` なら assemble 側が勝つ）。
- 現状 `tool-enchant-unbreaking` は resources に0件なので潜在。
  修正するなら glow の復元を stamp の**前**に移すだけでよい。

### L3. `pureRemovalReusesTheOriginalRollSeedAndQuality` が実質何も縛っていない

- 根拠: `TrinityForge/src/test/java/com/trinityforge/listeners/GrindstonePreserveListenerTest.java:133-147`
- 2つの assert が両方とも `primary.clone()` だけで満たされる:
  - `data.rollSeed()` / `data.quality()` は入力 `tfBlade()` が既に持っており、clone で引き継がれる。
  - `verify(assembler, never()).assemble(..., longThat(seed -> seed != ORIGINAL), ...)` は
    `assemble` が**一度も呼ばれなくても通る**（never() の空振り）。
- つまり `GrindstonePreserveListener.java:92` の `itemFactory.stamp(...)` 行を丸ごと削除しても
  **このテストは緑のまま**。名前が主張している「再組み立て経路が元の seed を使う」ことは縛れていない。
- 救いはある: 同クラスの `pureRemovalKeepsNameLoreAndTfEnchantWhileDroppingThePlayerEnchant` が
  モック assembler の書く `RESTORED_LORE` を assert しているので、stamp 経路自体はそちらで通っている。
- 推奨: `verify(assembler).assemble(any(), any(), eq(ORIGINAL_ROLL_SEED), eq(ORIGINAL_QUALITY));`（肯定形）へ差し替える。

### L4. 砥石ガードのテストが「砥石に入らないマテリアル」でしか組まれていない

- 根拠: `CatalogVanillaOperationGuardListenerTest.java:64-66`（`halo` = `Material.GLOWSTONE` / CMD 84）、
  同 `:180-186` / `:189-199` / `:202-209`
- 分岐（同一 identity / 別 identity / 片側空）はいずれも正しく通っており、
  「同一マテリアル・別 CMD」という現実的な消費マージの形にもなっている。
  ただし GLOWSTONE は実際には砥石に入らないため、
  **エンチャント可能なマテリアル（剣・ツルハシ）での組み合わせを1本も踏んでいない**。
  `GrindstonePreserveListener` 側と合わせて読めば実害は小さいが、
  ガード側だけを読むと「本当に砥石で起きうる組み合わせか」が検証できない。

### L5. 金床側の「従来どおり拒否」を縛るテストが薄い

- 根拠: `CatalogVanillaOperationGuardListenerTest.java:154-176`, `:212-240`
- 追加された肯定テスト（本の適用 / 同一 identity 修理）はあるが、
  **「改名のみ」「カタログ品 + 素材アイテムでの修理」が今も拒否されること** を明示的に縛るテストが無い。
  既存の `clearsDefaultAnvilAndSmithingResults` は `getFirstItem()/getSecondItem()` を stub していないため
  暗黙に null が返って拒否側へ落ちているだけで、意図した拒否ケースを表現していない。
- M3（改名解禁）を仕様として塞ぐなら、その回帰テストもここに要る。

### L6. MONITOR 優先度で結果を書き換える／恒久知識の置き場

- 根拠: `GrindstonePreserveListener.java:50-51`（`@EventHandler(priority = MONITOR, ignoreCancelled = true)`）
- 元からの設計だが、今回 **MONITOR でポリシー判断（`setResult(null)`）を新たに追加** したため、
  砥石の可否判断が HIGHEST と MONITOR の2クラスに割れた。Bukkit 規約上 MONITOR は観測専用。
  また `PrepareGrindstoneEvent` は Cancellable ではないので `ignoreCancelled = true` は無意味（既存踏襲）。
  ArsPaper `CustomItemListener` も同イベントを見るので、フォーク側が MONITOR で走る場合は登録順依存になる。
- 併せて運用面: 今回得られた非自明な知見2件 —
  (a) **HIGHEST のガードが MONITOR のリスナーを無言で死にコード化する**、
  (b) **`ItemFactory.create()` は既存アイテムの「作り直し」に使えない**（owner=魂縛 / 儀式のスレッド枠加算 /
  クラフト時ロール補正 / コーティングを再現できない）—
  は `tmp/findings/urgent/y1-grindstone-u4-fix.md` とコミットメッセージにしか無い。
  リポジトリ規約（`CLAUDE.md` / `docs/agent-context/README.md`）では恒久知識は `docs/agent-context/` が正。
  統合時に `docs/agent-context/`（`common-traps.md` あたり）へ移すこと。
- 参考（実害なし）: `tmp/` は `.gitignore:25` の対象で、
  `tmp/findings/urgent/y1-grindstone-u4-fix.md` は force-add されている（tracked なので通常の
  `git check-ignore` では出ないが `--no-index` で一致する）。指示由来なので問題視しないが、
  dev へマージすると public リポジトリに載る点だけ統合担当が意識すること。

---

## 統合時の確認事項（実装担当の申告を追認）

- `TrinityForge.java:711-713` の1行変更はコンパイル上不可避。他レーンが同ブロックを触る場合はここだけ手動解決。
- `ItemFactory.applyEnchantGlow(ItemMeta)` の `public static` 化は妥当（glow キーの二重管理回避）。直接テストは無いが `enchantGlowIsRestored...` で間接的に踏んでいる。
- `reports/ACTIVE_RECORD.md` の U4 クローズ追記は統合担当へ。**M2 の「ループは閉じている」という文言はそのまま転記しないこと。**
- 実サーバでの目視は2点: (1) 砥石でカタログ武器のエンチャントを外した後、表示名・lore・ステ・耐久・魂縛が残るか、(2) 金床でカタログ装備にエンチャント本を適用した後、CMD・表示名・PDC が残るか。
