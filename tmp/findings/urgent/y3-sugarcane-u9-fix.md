# U9 サトウキビで農業EXPが入らない — 修正記録（レーン y3-sugarcane-u9）

ブランチ: `work/y3-sugarcane-u9`（ベース `aa97412`）

## 1. 実コードでの裏取り（引き継ぎ記述は正しかった。ただし1点だけ違う）

`NativeSkillExperienceListener.java:331-336`（修正前）:

```java
double exp = gatheringExp(SkillId.FARMING, "block_drops", name, drops, mode);
String skill = SkillId.FARMING;
if (exp > 0.0 && block.getBlockData() instanceof Ageable ageable
        && ageable.getAge() < ageable.getMaximumAge()) return false;
```

- `skills/base/farming_progression.yml:40` に `SUGAR_CANE: 24` があるので EXP 値そのものは出ている。**確認済み。**
- `return false` なので `onBlockBreak`(:150-155) の `grantBreakVanillaExp(player)` にも到達しない
  ＝破壊時バニラEXP解放（`feature:break-vanilla-exp`）もサトウキビでは不発。**確認済み。**
- **引き継ぎと違った点**: 出荷 `stats/skill-exp.yml:55` は `exp-mode: block_value` であって `drop_sum` ではない。
  `block_value` ではブロック行の値（サトウキビなら24）がそのまま入るので、
  **成熟ガードだけが唯一の遮断要因**だったことがより明確になる
  （`docs/agent-context/progression-skilltree.md` の「出荷既定の `exp-mode: drop_sum`」という記述は stale。
  他セッションが `stats/skill-exp.yml` を未コミット編集中なので、**この yml とその記述は触っていない**）。

## 2. 同じ罠を持つブロックの全洗い出し（実際に列挙した）

paper-api 1.21.11 の `Material` が持つブロックデータクラスを実走査（`tmp/ageable/AgeableScan.java`、
`Material` の private フィールド `data` をリフレクションで読み `Ageable.isAssignableFrom` で判定）した結果、
`Ageable` を実装する Material は **22種**。

| 分類 | Material | age の意味 |
|---|---|---|
| **成熟する（ガード対象＝ホワイトリスト）** | WHEAT / CARROTS / POTATOES / BEETROOTS / NETHER_WART / COCOA / SWEET_BERRY_BUSH / TORCHFLOWER_CROP / PITCHER_CROP / MELON_STEM / PUMPKIN_STEM | 成熟度。`age == maximumAge` が収穫適期 |
| **age が周回する・成熟概念が無い（ガードしない）** | SUGAR_CANE(15) / KELP(25) / CACTUS(15) / BAMBOO(1) / TWISTING_VINES(25) / WEEPING_VINES(25) / CAVE_VINES(25) / CHORUS_FLOWER(5) / MANGROVE_PROPAGULE(4) / FROSTED_ICE(3) / FIRE(15) | 次の1段を伸ばすまでのカウンタ（最大で0に戻る）／太さ／融解・延焼の進行 |

このうち **`farming_progression.yml` の `block_drops` に載っていて実害が出ていたのは6種**:
**SUGAR_CANE(24) / KELP(10) / BAMBOO(10) / TWISTING_VINES(20) / WEEPING_VINES(20) / CAVE_VINES(80)**。
サトウキビだけでなくこの6種すべてが「農業EXP 0 ＋ 破壊時バニラEXP 0」だった。
CACTUS / CHORUS_FLOWER / MANGROVE_PROPAGULE / FROSTED_ICE / FIRE は現在どのEXP表にも無いので実害は無いが、
表に足した瞬間に同じ事故になるため分類には入れてある。

## 3. 判断した設計

1. **ホワイトリスト方式を採用**（除外集合ではなく）。理由: 除外集合方式は Paper が新しい `Ageable` ブロックを
   追加するたびに「無言でEXP0」が再発する（＝今回とまったく同じ事故）。未知ブロックは
   「成熟概念なし＝常に収穫可能」として素通しする fail-open にした。
   fail-open 側のリスク（未成熟でEXPが入る）は、`placedBlockTracker` が設置ブロックを除外している以上
   「自然生成の未成熟作物を壊す」経路しか無く、EXP増殖には使えない。
2. **判定は Java 側の定数**（設定項目を増やさない）。新クラス
   `TrinityForge/src/main/java/com/trinityforge/farming/CropMaturity.java` に一元化し、
   「なぜ `Ageable` だけでは判定できないか」を javadoc に日本語で残した。
   既存の `FarmingCropCatalog` と同じ `com.trinityforge.farming` パッケージ（Bukkit実行時不要＝単体テスト可能）。
3. **`gatheringExpMode()` を null 安全化**。`TrinityForge.getInstance()` が null（プラグイン未起動＝ユニット
   テスト）だと従来は NPE で破壊経路をテストから叩けなかった。同ファイルの `worldExpRate` /
   `useLevelExpMultiplier` が既に同じ「tf==null なら中立値」パターンを採っているのでそれに揃えた
   （null のときは既定の `DROP_SUM`）。**実サーバ挙動は変わらない。**

### 触っていない同型サイト（意図的に未修正）

| 場所 | 状況 |
|---|---|
| `FarmingHarvestListener#isMature`(:245) | 全呼び出しが `FarmingCropCatalog.isCrop`（WHEAT/CARROTS/POTATOES/BEETROOTS/NETHER_WART の5作物）で先に絞られているので**この罠を踏んでいない**。変更不要 |
| `PlantedCropGrowthListener`(:79) | 同じく `FarmingCropCatalog.isCrop` ゲート済み。変更不要 |
| `GatheringExtraDropListener`(:59) `harvest_extra_drop_chance` | **同じ罠を踏んでいる**（素の `Ageable && age == maximumAge`）。サトウキビ/コンブ/竹/ねじれツタ/泣きツタ/光ツタでは追加ドロップが一切抽選されず、逆にコーラスフラワー/サボテン/凍結氷が「成熟した作物」に化ける。**ただし直すと収穫量が増える＝バランスに触るので未修正**（ユーザー判断が必要）。`docs/agent-context/progression-skilltree.md` に明記した |
| SWEET_BERRY_BUSH の閾値 | ガードは `age >= maximumAge`(=3) のままにした。age2 のベリー（ベリー1個を落とす）は破壊してもEXP0になるが、これは今回の報告とは別の「厳しすぎる閾値」であり既存挙動。右クリック収穫側（`isHarvestableFarmingInteraction`）は `age > 1` なので**両者で基準が違う**点だけ記録しておく |

## 4. テスト結果（実走）

新規2クラス（`--tests` 指定）:

```
> Task :test
BUILD SUCCESSFUL in 3s

TEST-com.trinityforge.farming.CropMaturityTest.xml:
  tests="24" skipped="0" failures="0" errors="0"
TEST-com.trinityforge.listeners.NativeSkillExperienceListenerCropMaturityTest.xml:
  tests="4" skipped="0" failures="0" errors="0"
```

**RED 確認**（テストがちゃんと縛れていることの裏取り）: 該当1行を修正前の `instanceof Ageable` 実装に
戻して実走すると、サトウキビの2件だけが落ち、ニンジンの2件は通る＝意図した挙動だけを検出している。

```
NativeSkillExperienceListenerCropMaturityTest > sugarCaneAlsoReachesBreakVanillaExpRelease() FAILED
NativeSkillExperienceListenerCropMaturityTest > sugarCaneGrantsFarmingExpAtEveryAge() FAILED
4 tests completed, 2 failed
```

縛った内容:

- `CropMaturityTest`: 周回型11種すべてを **age 0〜maximumAge の全段** でガード対象外と確認／
  成熟型11種すべてを **未成熟の全 age で弾き、完熟で通す**と確認／非植物・null・
  `BlockData` が `Ageable` でない場合の防御。
- `NativeSkillExperienceListenerCropMaturityTest`: サトウキビは **age 0〜15 の全段で農業EXP 24 が入る**／
  サトウキビで **`player.giveExp(1)` まで到達する**（`return false` で道連れになっていた
  破壊時バニラEXP解放）／未成熟ニンジン(age3/7)はEXPもバニラEXPも0のまま／完熟ニンジン(age7)は入る。

全体テスト（`./gradlew test`、フォーク不在の既知3件のみ失敗）:

```
LegacyValhallaRuntimeContentTest > operationalConfigsContainNoNonGuiValhallaItemsRecipesOrCmds() FAILED
SpellBreakMarkerDriftTest > forkActuallySetsAndRemovesTheMarkerAroundCallEvent() FAILED
SpellBreakMarkerDriftTest > forkAndTrinityForgeAgreeOnTheMarkerKey() FAILED
3006 tests completed, 3 failed, 2 skipped
```

**skipped=2 の内訳（どちらも既存・意図的で、MockBukkit の未実装API化けではない）:**

- `NativeProgressionStabilizationContractsTest#prestigeRefundUsesLiveYamlCost_...` — ソースに
  `@Disabled("Pre-schema-v2: ...")` が付いている。
- `OfflineMobImportRunner` — 環境変数3本が揃ったときだけ走る `assumeTrue` ゲート。

`./gradlew test` が書き換えた `ops/reports/resource-server-mob-simulation.md` /
`ops/reports/shared-sqlite-concurrency.md` は `git checkout --` で戻し、**commit していない。**

## 5. 統合時に必要な作業

- **`TrinityForge.java` の配線変更は不要**（新クラスは静的ユーティリティ、リスナー登録の変更なし）。
- **yml の変更は無し**＝**jar だけの配備で直る**（config 同時配備は不要）。
- `reports/ACTIVE_RECORD.md` はこのレーンでは触っていない（1波1人の choke file）。統合担当が
  U9 に取り消し線＋「`CropMaturity` へホワイトリスト化。サトウキビ以外に KELP/BAMBOO/TWISTING_VINES/
  WEEPING_VINES/CAVE_VINES の計6種が同じ理由で0だった」と追記すること。
- 未対応として残した `GatheringExtraDropListener` の `harvest_extra_drop_chance`（上の表）は
  バランス判断待ち。ユーザーに「サトウキビ/コンブ/竹の収穫でも追加ドロップを抽選してよいか」を確認する必要がある。
- 参考: 列挙に使った走査スクリプトは `tmp/ageable/AgeableScan.java`（`tmp/` は gitignore なので未コミット）。
  Paper を上げたら同じ手順で 22種の内訳を再確認する。

## 6. 触ったファイル

```
M TrinityForge/src/main/java/com/trinityforge/listeners/NativeSkillExperienceListener.java
M docs/agent-context/progression-skilltree.md
A TrinityForge/src/main/java/com/trinityforge/farming/CropMaturity.java
A TrinityForge/src/test/java/com/trinityforge/farming/CropMaturityTest.java
A TrinityForge/src/test/java/com/trinityforge/listeners/NativeSkillExperienceListenerCropMaturityTest.java
A tmp/findings/urgent/y3-sugarcane-u9-fix.md
```
