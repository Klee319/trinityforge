# U4 カスタム装備のエンチャントを砥石で外せるようにする — 実装記録

- ブランチ: `work/y1-grindstone-u4`（ベース `aa97412`）
- 対象: 砥石(`PrepareGrindstoneEvent`)と金床(`PrepareAnvilEvent`)のカタログ品ガード、および除去後の復元

## 1. 実コードでの裏取り（記述の検証結果）

| 引き継ぎの記述 | 検証結果 |
|---|---|
| `CatalogVanillaOperationGuardListener:168-173` がカタログ品を1つでも含めば無条件で `setResult(null)` | **正しい**。しかも判定は `getContents()` 全走査なので、**結果スロット(index 2)にカタログ品が残っているだけでも拒否**していた |
| `GrindstonePreserveListener` は MONITOR なので死にコード | **正しい**。HIGHEST で null にされた後に `result == null` ガードで必ず即 return する |
| javadoc がガードと矛盾 | **正しい**（「エンチャント除去は許可」と書いてあるのに許可されていない） |
| 金床も `matchesDeclaredCombine` 以外全拒否でエンチャ本が適用できない | **正しい**。`OverEnchantListener#onAnvil`(HIGH) の結果も後から潰されていた |
| 砥石は表示名・lore・attribute を剥がす | **リポジトリ内では確定できない**（paper-api しか無く `GrindstoneMenu` のソースが無い）。そのため復元は「バニラが何を残したかに依存しない」形で実装した（下記 3-b） |

## 2. やったこと

### (a) 砥石ガード = identity を消費する組み合わせだけ拒否
`CatalogVanillaOperationGuardListener#onPrepareGrindstone` を書き換え、判定を**スロット 0/1 だけ**に限定した。

- 許可: 片側だけ埋まっている（純粋なエンチャント除去）／両側が同一 `catalogId`（同種修理）
- 拒否: 両側が別 identity（片方だけカタログ品を素材として食う修理マージ）

判定は `CatalogVanillaOperationPolicy.catalogIdOf` の一致比較のみ（指示どおり）。

### (b) 金床ガード = エンチャ本の適用と同一 identity の修理を許可
`matchesDeclaredCombine` に加えて
`appliesEnchantmentBook`（第2スロットが `EnchantmentStorageMeta`）と
`sameCatalogIdentity`（両スロット同一 `catalogId`）を許可条件に追加。それ以外は従来どおり拒否。

### (c) 砥石通過後の見た目/ステータス復元（`GrindstonePreserveListener` 全面書き換え）
`(ItemCatalogConfig, ItemFactory)` を受け取り、MONITOR（= ガードの HIGHEST の後）で

1. 元アイテムを `clone()`
2. **バニラ結果が残したエンチャント**に構成を揃える（= エンチャントだけ落とす。呪い等が残るなら残る）
3. **耐久はバニラ結果の値を引き継ぐ**（単体投入なら元と同値、同一 identity マージなら修理後の値）
4. `ItemFactory.stamp(out, 元の rollSeed, 元の quality)` で再組み立て（lore・attribute modifier・
   TF 付与 tool-enchant が戻る）
5. `enchant-glow` 由来の隠しエンチャントだけは再組み立てで戻らないので個別に戻す

**rollSeed は必ず元の値。品質・厳選ロール・耐久はリセットしない。**

### (d) 追加した安全弁: 外せるエンチャントが無いときは結果枠を出さない
片側だけの投入で「復元後のエンチャント構成が元と完全に同じ」＝プレイヤーが外せるエンチャントが
1つも無い（TF 自身が付与した tool-enchant / enchant-glow だけ）場合は `setResult(null)` にする。

**理由（設計への追加。これが無いと新規の複製級バグになる）**: 砥石の EXP は「入力から除去された
エンチャント」から計算されて取り出し時に付与される。TF が刻印を戻す実装だけ入れると
「取り出す→TF が刻印を戻す→また取り出す」で**砥石が無限 EXP 源**になる。
この安全弁で 1 回の実removal 以降はループが閉じる（マージ修理は素材を消費するのでこの判定から除外）。

### (e) `ItemFactory.applyEnchantGlow(ItemMeta)` を public static 化
`enchant-glow` の隠しエンチャントキー（`minecraft:unbreaking` + `HIDE_ENCHANTS`）を
呼び出し側で複製しないための抽出。`buildIdentity` からも同じメソッドを呼ぶ。

## 3. 判断した設計（指示から変えた点と理由）

### (a) 「TF付与エンチャントだけのときは結果枠を出さない」を追加した
上記 2-(d)。指示に無い追加だが、入れないと**無限 EXP 源**になる。ポリシー自体は指示の
「純粋なエンチャント除去は許可」と矛盾しない（外すものが無いので操作自体が no-op）。

### (b) 復元の手段を `ItemFactory.create(template, rollSeed, quality)` ではなく
### 「元アイテムの clone + `ItemFactory.stamp(元の rollSeed, 元の quality)`」にした
指示は `create` で作り直す形だったが、`create` はテンプレートから作り直すので**元アイテムにしか無い
PDC を再現しない**。具体的に落ちるもの:

| 落ちる PDC | 実害 |
|---|---|
| `ITEM_OWNER` | **SOULBOUND の所有者が消える = 砥石が魂縛外しになる** |
| `ITEM_RITUAL_THREAD_SLOT_BONUS` | 儀式で支払ったスレッド枠加算が消える（`assemble` の derivation 入力なので事後コピーでは間に合わない） |
| `ITEM_CRAFT_ROLL_*` | クラフト時ロール補正が消えてステータスが変わる（同じく derivation 入力） |
| `ITEM_COATING_*` / `ITEM_PARTICLE_SEED` | コーティング・パーティクルが消える |

`clone + stamp` は上記を構造的に全部維持し、**バニラが名前/lore を剥がすか否かのどちらでも正しい**
（剥がすなら clone で名前が戻り stamp で lore/attribute が戻る、剥がさないなら stamp が冪等）。
要求（見た目とステータスの維持・rollSeed 再利用・品質/ロール/耐久の維持）は満たしている。
テスト `pureRemovalKeepsDurabilityAndPaidPdc` で owner と儀式加算の維持を縛った。

### (c) 復元の対象は「カタログ品」ではなく「TF 刻印を持つアイテム」
`hasTfItemIdentity`（従来と同じ基準）を維持。クラフト/釣り由来でテンプレートを持たない TF 装備も
同じ経路で復元される（`rollSeed` が無いアイテムは派生ステを持たないので再組み立てしない
= ここで seed を発行しない）。

## 4. テスト結果（実走）

対象クラス:

```
> Task :test
BUILD SUCCESSFUL in 13s
TEST-com.trinityforge.listeners.GrindstonePreserveListenerTest.xml:      tests="7"  skipped="0" failures="0" errors="0"
TEST-com.trinityforge.listeners.CatalogVanillaOperationGuardListenerTest.xml: tests="15" skipped="0" failures="0" errors="0"
```

全体:

```
2989 tests completed, 3 failed, 2 skipped
LegacyValhallaRuntimeContentTest > operationalConfigsContainNoNonGuiValhallaItemsRecipesOrCmds() FAILED
SpellBreakMarkerDriftTest > forkActuallySetsAndRemovesTheMarkerAroundCallEvent() FAILED
SpellBreakMarkerDriftTest > forkAndTrinityForgeAgreeOnTheMarkerKey() FAILED
```

失敗3件は**worktree にフォークが存在しないことによる既知の失敗**（指示で明示されたもの）。
skipped 2件も自分の領域外の既存分:
`OfflineMobImportRunner`（オフライン専用ランナー）と
`NativeProgressionStabilizationContractsTest.prestigeRefundUsesLiveYamlCost_...`（`@Disabled` 系）。
**自分の追加テストの skipped は 0**（MockBukkit の SKIPPED 化け無し）。

### テストの変更点
- `CatalogVanillaOperationGuardListenerTest.clearsDefaultAnvilSmithingAndGrindstoneResults` を分割:
  - `clearsDefaultAnvilAndSmithingResults`（金床/鍛冶の従来分はそのまま）
  - `clearsGrindstoneResultOnlyWhenTheMergeWouldConsumeACatalogIdentity`（消費マージ → null）
  - `keepsGrindstoneResultForPureEnchantRemoval`（上下どちらか片側 → 保持）
  - `keepsGrindstoneResultWhenBothSlotsShareTheSameCatalogIdentity`（同種修理 → 保持）
  - `keepsAnvilResultForEnchantedBookApplicationAndSameIdentityRepair`（金床の新許可2件）
- `GrindstonePreserveListenerTest` を**新規作成**（7件）。表示名/lore/TF付与エンチャントの復元、
  プレイヤーエンチャントの除去、rollSeed/quality の不変、耐久と支払い済み PDC の維持、
  「外せるものが無いときは結果枠を出さない」、非TF品の非干渉、enchant-glow の復元。

## 5. 残した未対応と理由

1. **バニラの砥石が実際に表示名/lore/attribute を剥がすのかは未確定**。
   リポジトリに server 実装が無く、`GrindstoneMenu` を読めない。実装はどちらでも正しくなるように
   組んであるが、**実サーバで 1 回は目視確認したい**（「名前が消える」なら復元が効いている証拠、
   「元から消えない」なら復元は安全網として空振りするだけ）。
2. **砥石の EXP は「実際に外した 1 回分」については TF 付与エンチャントの分まで入る**。
   `PrepareGrindstoneEvent` に EXP のフックが無く、EXP は取り出し時に入力から計算されるため
   構造的に分離できない。ループは 3-(a) の安全弁で閉じているので farm にはならない。
3. **金床側の見た目復元は入れていない**（指示の範囲外）。バニラの金床結果は入力のコンポーネントを
   引き継ぐ設計なので通常は不要だが、エンチャ本適用後に lore が古いままになる可能性は残る。
   必要なら砥石と同じ `clone + stamp` を `PrepareAnvilEvent` にも足せる。
4. **砥石で「修理コストのリセットだけ」はできない**（エンチャントが無い TF 品は結果枠が空になる）。
   3-(a) の安全弁の副作用。実害は小さいと判断。
5. **`WoodRepairListener#onPrepareAnvil`（圧縮材による修理）は、その素材が TF `catalog.yml` の品なら
   今も金床ガードに潰される**。現状 圧縮系は ArsPaper `materials.yml` 側にしか無い（U8 の調査結果）
   ので現時点では衝突しないが、**U8 で圧縮系を TF catalog へ移すなら、ガードの許可条件に
   `features.woodRepairMaterial(catalogId) != null` を足す必要がある**（`features` はガードが既に保持済み）。
   今回は「それ以外は現状どおり拒否」の指示に従い触っていない。
6. **ArsPaper `CustomItemListener` の砥石処理との優先度関係は未検証**（フォークが worktree に無い）。
   TF 側は MONITOR なので、フォークが MONITOR で後から結果を空にする実装ならそちらが勝つ。

## 6. 統合時に必要な作業

- **`TrinityForge.java:711-713` の配線を 1 行変更している**（コンパイルが通らないため不可避）:
  `new GrindstonePreserveListener()` → `new GrindstonePreserveListener(configManager.itemCatalog(), itemFactory)`。
  他レーンがこの登録ブロックを触っている場合はここだけ手で解決すること。
- `ItemFactory.java` に `public static void applyEnchantGlow(ItemMeta)` を追加し、
  `buildIdentity` の glow 適用をそこへ寄せている（他レーンが `ItemFactory` を触るなら軽微な衝突あり）。
- `reports/ACTIVE_RECORD.md` は触っていない（1波1人ルール）。U4 のクローズ追記は統合担当が行うこと。
  追記内容の要点: **原因は PDC ではなくガードの無条件 `setResult(null)`。修正後は
  「片側だけ/同一IDのみ許可」「除去後は元 rollSeed で再組み立てして見た目とステを復元」
  「外せるエンチャントが無いときは結果枠を出さない(無限EXP対策)」**。
- `ops/reports/*.md` は `./gradlew test` が書き換えるが **commit していない**。
