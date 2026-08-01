# Y3 (U9: サトウキビで農業EXPが入らない) 敵対的レビュー

- 対象ブランチ: `work/y3-sugarcane-u9` / コミット `3af1f4f`（親 = `aa97412`。ベース一致を確認済み）
- レビュー実施日: 2026-08-01
- 判定: **ACCEPT_WITH_NOTES**（HIGH 0 件 / MEDIUM 4 件 / LOW 8 件）

---

## 0. 実走で裏を取った事実（報告の丸呑みはしていない）

| 検証項目 | 実行内容 | 結果 |
|---|---|---|
| ブランチ／コミットの存在 | `git log --oneline aa97412..work/y3-sugarcane-u9` | `3af1f4f` 1 本のみ。親は `aa97412` で一致 |
| 巻き込みコミット | `git show --stat 3af1f4f` | **6 ファイルのみ。`catalog.yml` / `item-stats.yml` / `lore.yml` / `resourcepack/` / `ops/reports/` は一切含まれない** |
| 全体テスト | worktree `wf_01789c6c-3ea-3` で `./gradlew test --offline` を自分で実走 | `3006 tests completed, 3 failed, 2 skipped` — **報告と完全一致** |
| 失敗3件の正体 | ログ確認 | `LegacyValhallaRuntimeContentTest` × 1、`SpellBreakMarkerDriftTest` × 2。いずれも `fork-handoff/*/fork` 不在が原因（worktree にフォークが存在しないため当然）。**それ以外の失敗は 0 件** |
| skipped 2 件 | ログ確認 | `OfflineMobImportRunner`（env ゲート）、`prestigeRefundUsesLiveYamlCost_...`（`@Disabled`）。**MockBukkit の未実装API化けではない** |
| 新規テストの実体 | `build/test-results/test/*.xml` を集計 | `CropMaturityTest` tests=24 / fail=0 / **skip=0**、`NativeSkillExperienceListenerCropMaturityTest` tests=4 / fail=0 / **skip=0** |
| RED の再現 | 本番行を旧実装 `instanceof Ageable && age < maximumAge` に**自分で差し戻して**該当2クラスを実走 | `28 tests completed, 2 failed` — 落ちたのは `sugarCaneGrantsFarmingExpAtEveryAge` と `sugarCaneAlsoReachesBreakVanillaExpRelease` の**サトウキビ2件だけ**。ニンジン2件は通過。**報告どおり**（検証後 `git checkout --` で復元、worktree は clean に戻した） |
| 「Ageable は 22 種」の主張 | paper-api **1.21.11** の jar を jshell でリフレクション走査（`Material.data` フィールド、legacy 除外、`Ageable.class.isAssignableFrom`） | **`TOTAL_AGEABLE=22`。javadoc の 11 + 11 の内訳と 1 件のズレもなく一致**（BAMBOO/BEETROOTS/CACTUS/CARROTS/CAVE_VINES/CHORUS_FLOWER/COCOA/FIRE/FROSTED_ICE/KELP/MANGROVE_PROPAGULE/MELON_STEM/NETHER_WART/PITCHER_CROP/POTATOES/PUMPKIN_STEM/SUGAR_CANE/SWEET_BERRY_BUSH/TORCHFLOWER_CROP/TWISTING_VINES/WEEPING_VINES/WHEAT） |
| 「出荷は block_value」の主張 | `TrinityForge/src/main/resources/stats/skill-exp.yml:55` | `exp-mode: block_value`。**主張は正しい**（ドキュメント側が stale） |
| 他経路の取りこぼし | `git grep Ageable` で全 6 箇所を確認 | `FarmingHarvestListener`（4 呼び出し全て `FarmingCropCatalog.isCrop` で事前ゲート済）と `PlantedCropGrowthListener:79`（同上）は罠を踏んでいない — **ドキュメントの記述は正しい**。`GatheringExtraDropListener:59` のみ罠が残存（→ MEDIUM-1） |

**設計指示からの逸脱は無い。** 設定項目の新設なし、`rollSeed` 等の無関係な引き直しなし、yml 変更なし、`TrinityForge.java` の配線変更なし。修正は**厳密に「緩める」方向のみ**（`isImmatureCrop` は旧条件の部分集合）なので、既存作物のガードが黙って外れることは構造的に起きない。

---

## MEDIUM-1: `GatheringExtraDropListener` に同一の罠が残存。逆方向の純粋バグまで一緒に保留されている

`TrinityForge/src/main/java/com/trinityforge/listeners/GatheringExtraDropListener.java:59-60`

```java
boolean isMatureCrop = block.getBlockData() instanceof Ageable ageable
        && ageable.getAge() == ageable.getMaximumAge();
```

実装者はこれを「収穫量＝バランスに触るのでユーザー判断待ち」として意図的に未修正にし、ドキュメントにも明記している。判断としては筋が通っているが、**この 1 行には方向の違う 2 つの問題が同居していて、片方はバランスに一切触らない純粋バグ**である。

- **方向A（バランスに触る）**: サトウキビ/コンブ/竹/ねじれツタ/泣きツタ/光ツタは `age == maximumAge` が事実上持続しない（最大値に達した瞬間に成長して 0 に戻る）ため、`harvest_extra_drop_chance` が**ほぼ永久に抽選されない**。直すと収穫量が増える → 保留は妥当。
- **方向B（バランスに触らない・純粋バグ）**: `CHORUS_FLOWER`(age 5) / `CACTUS`(age 15) / `FROSTED_ICE`(age 3) / `MANGROVE_PROPAGULE`(age 4) / `FIRE`(age 15) が「成熟した作物」に化け、**作物でないものに収穫系の追加ドロップが乗る**。

方向B だけなら `CropMaturity.isMaturityGated(block.getType()) && age == maximumAge` にするだけで、**実作物の挙動を一切変えずに**閉じられる（このコミットで `isMaturityGated` は既に public として用意されている）。両者を一括で保留したことで、ノーリスクで閉じられる不具合が判断待ちの箱に入ってしまっている。

---

## MEDIUM-2: 回帰テストが**本番で使われていない EXP モード**を縛っている

`TrinityForge/src/main/java/com/trinityforge/listeners/NativeSkillExperienceListener.java:369-376`（新規 `gatheringExpMode()`）は `tf == null` のとき `DROP_SUM` を返す。テストはプラグイン未起動なので**必ず `DROP_SUM`** で走る。しかし出荷設定は `TrinityForge/src/main/resources/stats/skill-exp.yml:55` の `exp-mode: block_value`。

具体的な食い違いは `NativeSkillExperienceListenerCropMaturityTest.java:105`:

```java
// DROP_SUM(プラグイン未起動時の既定)なのでドロップ品CARROT行の10。
verify(fixture.dispatcher()).grant(fixture.player().getUniqueId(), SkillId.FARMING, 10.0);
```

`gatheringExp` の `BLOCK_VALUE` 分岐（同ファイル `:442`）は `blockExp` を返すので、**実サーバでの同じ操作は 40.0**（`farming_progression.yml:27` の `CARROTS: 40`）。成熟ガードそのものはモード非依存なので今回の修正の妥当性は揺らがないが、**本番モードでの回帰テストが 1 本も無い**。`gatheringExpMode()` はテストを通すために新設された関数なのだから、既定を `BLOCK_VALUE` にする（出荷 yml と揃える）か、モードを注入できるようにして両モードを縛るのが筋。

なお副作用として、新設の null 安全化により `matureCarrotsGrantFarmingExp` の期待値は「出荷 yml と食い違ったまま緑」になり続ける。

---

## MEDIUM-3: 「6 種が直った」は実プレイの主要操作では 4 種が 0 のまま（`_PLANT` 胴体ブロック）

`Ageable` なのは**先端ブロックだけ**で、胴体は別 Material（`KELP_PLANT` / `CAVE_VINES_PLANT` / `TWISTING_VINES_PLANT` / `WEEPING_VINES_PLANT`）。胴体は `Ageable` ではないので元から成熟ガードに掛かっておらず、今回の修正対象外。そして `farming_progression.yml` を確認すると、これら 4 つは **`block_interact` に 0 で載っているだけで `block_drops` の行が無い**（`:13` `:16` `:19`。`KELP_PLANT` に至ってはどのセクションにも無い）。

`gatheringExp` は `blockExp <= 0.0` で即 0 を返す（`NativeSkillExperienceListener.java:418-419`）ので、**胴体ブロックを壊すと農業EXPは 0 のまま**。コンブ/光ツタ/ねじれツタ/泣きツタは、実プレイでは列の途中（胴体）を壊すのが普通なので、**ユーザーから見た症状は 4 種で未解決**。

- 完全に直っているのは **`SUGAR_CANE` と `BAMBOO` の 2 種**（全段が同一 Material なので先端概念が無い）。報告された U9 本体はここなので**主目的は達成されている**。
- コミットメッセージ・javadoc・`progression-skilltree.md` はいずれも「6 種が該当」とだけ書いており、胴体側が残る旨の注記が無い。**次に「コンブでEXPが入らない」と再報告される導線が残っている**（タスク指示の「同じ報告が再来すると困る」に直接該当）。

これは yml 行の追加＝バランス変更なので今回直さない判断は妥当だが、**ドキュメントに明記されていない**点が問題。

---

## MEDIUM-4: 自分が編集したドキュメントの中に、自分で stale と認定した記述を残している

`docs/agent-context/progression-skilltree.md:96`

> 出荷既定の`exp-mode: drop_sum`では、「ブロック名の行」はゲート判定（値>0か）専用であり、…

実際の出荷値は `stats/skill-exp.yml:55` の `block_value`。実装者は報告書で「ドキュメントの記述が stale」と正しく指摘しておきながら、**その stale な段落の直後（`:98`）に新セクションを追記しただけで、`:96` 自体は直していない**。「yml は他セッションが編集中なので触らない」は yml を触らない理由にはなるが、**ドキュメントを直さない理由にはならない**（同じファイルを既に編集している）。

`docs/agent-context/` は「触る前に知らないと黙って壊す知識」の恒久ベースなので、EXP 計算方式の記述が逆になっているのは実害が大きい。1 行の修正で閉じられる。

---

## LOW-1: javadoc の分類理由が 5 種で事実と異なる（判断は正しいが根拠が誤り）

`TrinityForge/src/main/java/com/trinityforge/farming/CropMaturity.java:36-39` の見出し

> **age が周回する・成熟の概念が無い（ガードしない）:** SUGAR_CANE / KELP / CACTUS / BAMBOO / TWISTING_VINES / WEEPING_VINES / CAVE_VINES / **CHORUS_FLOWER / MANGROVE_PROPAGULE / FROSTED_ICE / FIRE**

後半 4 種（＋ BAMBOO）の `age` は**周回しない**。`CHORUS_FLOWER` は 0→5 で成長を止める単調増加、`MANGROVE_PROPAGULE` は 0→4 の正真正銘の成熟度、`FROSTED_ICE` は 0→3 で融解、`FIRE` は 0→15 で消火、`BAMBOO` は太さ 0/1（javadoc 本文では竹だけ正しく補足しているが、箇条書きの見出しには同居している）。

**ガードしないという結論は妥当**（いずれも EXP 表に無い／作物ではない）だが、`docs/agent-context/` と javadoc は「後任が根拠ごと信じる」前提の資産であり、テストの enum 名まで `CyclingPlant`（`CropMaturityTest.java:27`）になっているので誤解が固定化される。見出しを「成熟ガードを掛ける意味が無い」等に直し、周回するもの（SUGAR_CANE / KELP / CACTUS / TWISTING_VINES / WEEPING_VINES / CAVE_VINES）と単調増加だが作物でないもの（CHORUS_FLOWER / MANGROVE_PROPAGULE / FROSTED_ICE / FIRE / BAMBOO）を分けるべき。

## LOW-2: 「Ageable は 22 種」に drift ガードが無い

javadoc と `progression-skilltree.md` は「Paper を上げたら再確認すること」と書いているが、**再確認を強制する仕組みが無い**。`CropMaturityTest` の `CyclingPlant` enum と `@CsvSource` は 22 種のハードコピーであり、paper-api の実体とは照合していない。

fail-open 設計なので、Paper が新しい**成熟作物**を追加した場合の事故は「未成熟でもEXPが入る」（＝静かなバランス漏れ）方向で、旧実装の「無言でEXP0」よりは軽い。ただし本レビューで実際にやったとおり `Material` を走査して「22 種の内訳＝ホワイトリスト ∪ 非ガード集合」を突き合わせるテストは書ける（`Material.data` へのリフレクションは脆いので、`createBlockData` が使える環境なら素直にそちら）。設計判断としては許容範囲だが、ガードが無いことは記録しておく。

## LOW-3: `gatheringExpMode()` の null 安全化は fail-fast を「静かに違う値」に変える

`NativeSkillExperienceListener.java:374-376`。`worldExpRate`（`:946-955`）に同じパターンが実在するので**先例の主張は正しく、一貫性もある**。実サーバで `getInstance()` が null になる経路は通常無いため「実サーバ挙動は不変」も妥当。

ただし `worldExpRate` の fallback が `1.0`（＝何もしない）なのに対し、こちらの fallback は `DROP_SUM`（＝**出荷設定と違う値でEXPを配る**）。仮に shutdown/reload の隙間で null を踏んだ場合、旧実装は NPE でイベントハンドラが落ちて EXP 0 だったのが、新実装は**間違ったモードで EXP を配って黙って通る**。方向としては劣化。既定を `BLOCK_VALUE`（出荷値）にすれば MEDIUM-2 と同時に解消する。

## LOW-4: gitignore 対象の `tmp/` に強制 add している

`.gitignore:25` に `tmp/` があるにもかかわらず `tmp/findings/urgent/y3-sugarcane-u9-fix.md` が tracked（`git ls-tree` で確認）。`dev` には tracked な `tmp/` 配下ファイルは 1 件も無いので、**このブランチで `git add -f` 相当が行われた**。CLAUDE.md の「新しいレポートを増やさない」にも抵触する。

※ ただし本レビューファイル自身も同じ場所への commit を上位から指示されているため、ワークフローの運用意図である可能性が高い。統合時にワークフロー側の意図を確認し、意図的でなければ 2 ファイルとも落とすこと。

## LOW-5: `reports/ACTIVE_RECORD.md` の U9 が閉じられていない

`reports/ACTIVE_RECORD.md:2653` に U9 の項目が残ったまま（記載されている原因行 `NativeSkillExperienceListener.java:334-336` も修正でズレた）。実装者は「追記内容は tmp の報告書 第5節にある」としている。

並列作業のルール（`ACTIVE_RECORD.md` は 1 波に 1 人しか触らない）に従った結果なので**手順違反ではない**が、統合担当が拾わないとタスクが開いたまま残る。統合時に取り消し線＋解決根拠で閉じること。

## LOW-6: `com.trinityforge.farming` に作物 Material 集合が 2 本並ぶ

`FarmingCropCatalog.CROPS`（5 種・植え直し用の種対応表）と `CropMaturity.MATURITY_GATED`（11 種・成熟ガード）。**概念が違うので統合すべきではない**が、同一パッケージに相互参照無しで並んでおり、作物を足す後任が片方だけ更新する事故が起こりうる。互いの javadoc に「用途が違う」旨の相互リンクを 1 行入れるだけで防げる。

## LOW-7: `CACTUS` を非ガードにした効果は 0（EXP 表に行が無い）

タスク指示が名指しした `CACTUS` は正しく非ガードへ回されたが、`farming_progression.yml` の `block_drops` に行が無いため `gatheringExp` が `blockExp <= 0` で即 0 を返し、**サボテンの農業EXPは 0 のまま**。実装者の「実害が出ていたのは 6 種」は正確（サボテンを含めていないのは正しい）だが、指示で名指しされた Material の結果が変わらない点は明示しておくべき。EXP を付ける／付けないは yml の判断事項。

## LOW-8: `SWEET_BERRY_BUSH` の成熟基準が破壊経路と右クリック経路で食い違う

破壊経路は `CropMaturity` により `age < 3` で弾く。右クリック経路 `isHarvestableFarmingInteraction`（`NativeSkillExperienceListener.java:582-584`）は `age > 1`（＝age 2 でも収穫扱い）。実装者が既知・別件として申告済みで、**今回の変更で新たに生じた食い違いではない**（旧実装でも同じ）。ただし `CropMaturity` が「成熟判定の一元化」を名乗る以上、この 2 本目の基準がクラス外に残っていることは javadoc に注記しておくのが望ましい。

---

## 統合時の推奨

1. **そのままマージしてよい**（HIGH 無し、テスト実走で裏取り済み、巻き込み無し、jar 差し替えのみで反映）。
2. マージ前に 5 分で閉じられるもの: **MEDIUM-4**（doc の `drop_sum` → `block_value`）、**MEDIUM-3**（`_PLANT` 胴体が 0 のままである旨を doc に追記）、**LOW-1**（javadoc の分類見出し）。
3. ユーザー判断が要るもの: **MEDIUM-1 方向A**（`harvest_extra_drop_chance` を周回植物に効かせるか）、**MEDIUM-3**（`KELP_PLANT` 等に `block_drops` 行を足すか）、**LOW-7**（`CACTUS` に行を足すか）。
4. ノーリスクで別途閉じられるもの: **MEDIUM-1 方向B**（非作物への収穫追加ドロップ）、**MEDIUM-2**（本番モードでの回帰テスト）。
5. **LOW-5**（`ACTIVE_RECORD.md` の U9 クローズ）は統合担当が実施すること。
