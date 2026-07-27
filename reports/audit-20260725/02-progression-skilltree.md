# 02 進行系(スキル/経験値/レベル)・スキルツリー 監査所見

- 監査日: 2026-07-25
- 担当領域: `com/trinityforge/progression/` (41 files) / `com/trinityforge/skilltree/` (35 files) / `resources/progression|skills|skilltree/*.yml` / EXP付与・レベルアップ・perk適用リスナー
- IDプレフィクス: `PRG-`
- 本監査はコード無改変(read-only)。過去レポート `reports/20260725_SkilltreeNodeTriage.md` の「対応済み」記述は**再検証済み**であり、本書の判定を優先する。
- 機械的抽出は PyYAML による全16ツリーのパース(`nodes.*` + `prestige`)と Java 側レジストリ(`FeatureEffectRegistry` / `GateEffectId` / `StatVocabulary`)の突合で行った。

## 総括

| 指標 | 値 |
|---|---|
| 未実装キー総数 | **56** |
| 内訳: 存在しないID を指す `recipe:` ゲート | 41 |
| 内訳: レジストリ登録済みだが全ツリー未配置の feature | 1 (`break-vanilla-exp`) |
| 内訳: 消費側実装ありだが入手経路ゼロの stat キー | 13 |
| 内訳: パーサ対応済みだが全yml未使用のゲート接頭辞 | 1 (`reward:`) |
| 機構ゼロ(buffs/multipliers/dedicated-effects いずれも無し)のノード | 29 |
| 配置されているが効果が届かない/単位が誤っている buff 配置 | 14 |
| HIGH所見 | 9 |
| MEDIUM所見 | 12 |
| LOW所見 | 11 |

---

# HIGH 所見

## PRG-01 `feature:break-vanilla-exp` が全16ツリーのどのノードにも配置されておらず、破壊時バニラEXP機構が到達不能

- **重要度**: HIGH
- **分類**: UNIMPLEMENTED
- **場所**: `TrinityForge/src/main/java/com/trinityforge/skilltree/effects/FeatureEffectRegistry.java:45` / `TrinityForge/src/main/java/com/trinityforge/listeners/NativeSkillExperienceListener.java:56,120` / `TrinityForge/src/main/resources/skilltree/*.yml`(全16ファイル)
- **事象**: `break-vanilla-exp` は語彙レジストリに登録され、リスナー側の消費実装も完成しているが、全16ツリーの `dedicated-effects` を機械抽出しても配置ゼロ。プレイヤーがこの feature を有効化する手段が存在しない。
- **影響/再現**: `mining.yml A/C`, `woodcutting.yml A/C`, `digging.yml A/C` の6ノードは効果テキストで「破壊で少量のバニラEXP」「破壊で2倍のバニラEXP」を約束するが、gate が立たないため `grantBreakVanillaExp` は常に即 return する。6ノード×1SP が完全に無駄。さらに倍率側の `break_vanilla_exp_bonus` も入手経路ゼロ(PRG-03)なので、仮に feature を配置しても倍率は常に 1.0 のまま。
- **根拠**:
  ```java
  // FeatureEffectRegistry.java:45
  add(map, "break-vanilla-exp", "破壊時バニラEXP解放", FeatureEffectParam.NONE);
  // NativeSkillExperienceListener.java:120
  if (!dedicatedEffects.isActive(player, FEATURE_BREAK_VANILLA_EXP)) return;
  ```
  (過去レポート `reports/20260725_SkilltreeNodeTriage.md` §5-1 の指摘は本日時点でも未解消)

## PRG-02 smithing/`recipe:` ゲート41件が存在しないIDを参照しており、鍛冶ツリーの中核「レシピ解放」が完全に無効

- **重要度**: HIGH
- **分類**: UNIMPLEMENTED
- **場所**: `TrinityForge/src/main/resources/skilltree/smithing.yml`(ノード A/B/C/D) / `TrinityForge/src/main/java/com/trinityforge/listeners/CatalogCraftGateListener.java:27,57,60`
- **事象**: `recipe:stone_sword` 〜 `recipe:netherite_hoe` の41IDは、TF の `items/catalog.yml` にも ArsPaper fork の `materials.yml` / `items.yml` / `functional-items.yml` にも一切定義が無い。TF側ゲートリスナーは `trinityforge:catalog_<id>` 名前空間のレシピしか見ないため、バニラ装備レシピは元々ゲート不可能。
- **影響/再現**: smithing ツリー A(石5) / B(鉄9+金9) / C(ダイヤ9) / D(ネザライト9) の4ノード(合計4SP)が完全な no-op。プレイヤーは石の剣から最初から作成でき、「レシピ解放」という設計意図がゲーム内に一切現れない。逆に「ゲートが効いていないので作れてしまう」ではなく「そもそも掛からない」ため実害はソフトロック側ではなく、進行体験の空洞化。
- **根拠**:
  ```java
  // CatalogCraftGateListener.java:57-61
  if (!path.startsWith(CATALOG_PREFIX)) { return; }
  String catalogId = path.substring(CATALOG_PREFIX.length());
  if (!dedicatedEffects.recipeGatePerks().containsKey(catalogId)) return; // open by default
  ```
  全57件の `recipe:` ID を TF resources + fork resources 横断で正規表現照合した結果、ヒット0件が41件(vanilla装備一式)。残る16件(`compressed_bread_1x`/`tf_core_*`/`enchant_book_*`/`source_gem_block`/`volcanic_sourcelink` 等)は fork 側 yml に実在し正常。

## PRG-03 消費側実装がありながら入手経路が一切存在しない stat キーが13件

- **重要度**: HIGH
- **分類**: UNIMPLEMENTED
- **場所**: `TrinityForge/src/main/java/com/trinityforge/stats/StatVocabulary.java` / `TrinityForge/src/main/resources/skilltree/*.yml` / `TrinityForge/src/main/resources/combat/base-stats.yml` / `TrinityForge/src/main/resources/items/item-stats.yml`
- **事象**: 下記13キーは `StatVocabulary` に登録され、リスナー/サービス側に実際の消費コードがあるが、(a) 全16スキルツリーの buffs/mainhand-buffs/multipliers いずれにも未配置、(b) `base-stats.yml` は全項目0、(c) `item-stats.yml` にも無い。合計値は恒久的に0。
- **影響/再現**: 該当機構(作業台品質ボーナス、儀式品質ボーナス、バニラEXP各種倍率、追加ドロップ、満腹度、繁殖・成長系)が全プレイヤーで常時オフ。特に `workbench_quality_bonus` / `ritual_quality_bonus` は S7 で分割実装された品質系の片翼であり、`craft_quality_bonus`(8ノード配置あり)だけが機能している非対称状態。
- **根拠**: 下表(PRG-A2)の通り。TF main の消費箇所は各キーにつき1〜3ファイル存在するのに対し、resources 側の出現は0(ドキュメント/エディタ定義を除く)。

## PRG-04 装備・弓ツリーのギリシャ文字排他が tier 単位で分割されており、仕様「1系統のみ」を満たしていない

- **重要度**: HIGH
- **分類**: BUG
- **場所**: `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativePerkService.java:71-78` / `TrinityForge/src/main/resources/skilltree/archery.yml`, `heavy_armor.yml`, `light_armor.yml`, `heavy_weapons.yml`, `light_weapons.yml` / `docs/SKILL_TREE_SPEC.md:41`
- **事象**: 排他判定は「同一 `group` **かつ** 同一 `parent`」の兄弟にのみ働く。上記5ツリーは `A-greek`〜`E-greek` の5独立グループを持ち、各グループ内は3兄弟(α/β/γ)なので、tier A でα・tier B でβ・tier C でγ…という混成取得が通る。
- **影響/再現**: 例) `light_weapons` で `A-alpha-1`(攻撃力) → `B-beta-1`(会心) → `C-gamma-1`(貫通) を全取得可能。仕様が意図した「1系統のみ = 5ノード」に対し実際は最大15ノード相当の分岐が開くため、これら5ツリーの実効ノード数が3倍になりバランス前提が崩れる。なお2分岐系(alchemy/enchanting/farming/fishing/smithing/ars_smithing)は各分岐が親子チェーンなので推移的に排他が保たれており正常。
- **根拠**:
  ```java
  // NativePerkService.java:71-78
  if (node.group() != null) {
      boolean conflict = tree.nodes().values().stream()
              .filter(other -> node.group().equals(other.group()))
              .filter(other -> Objects.equals(node.parent(), other.parent()))
  ```
  仕様側: `docs/SKILL_TREE_SPEC.md:41`「ノードの α/β/γ 分岐は **1系統のみ**取得可」

## PRG-05 `mining-fortune` の単位が実装(倍率)と効果テキスト(パーセント)で一致せず、+18個/ブロック級の破格ドロップになる

- **重要度**: HIGH
- **分類**: BUG / BALANCE
- **場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/MiningFortuneListener.java:82,119` / `TrinityForge/src/main/resources/skilltree/mining.yml`(B-1/B-2/B-3) / `woodcutting.yml`(C-2)
- **事象**: `expectedExtraRate(fortune, level, perLevel) = (fortune + level*perLevel) * 0.30` は fortune を「幸運エンチャレベル相当の実数」として扱うが、ツリー側は `mining-fortune: 15 / 20 / 25` を「+15%/+20%/+25%」の意図で記述している。
- **影響/再現**: mining B-1〜B-3 を全取得すると fortune = 60。Lv100 の `fortunePerLevel`=0.01 を加えて `(60 + 1.0) * 0.30 ≒ 18.3`。すなわち鉱石1ブロックにつき期待+18個の追加ドロップ。効果テキストの想定(+25%=+0.25個)の約73倍。woodcutting C-2 は `mining-fortune: 45`(テキスト「1/2で+1」= +0.5個想定)に対し実効 +13.5個。
- **根拠**:
  ```java
  // MiningFortuneListener.java:119
  static double expectedExtraRate(double miningFortune, int miningLevel, double fortunePerLevel) {
  ```
  ```yaml
  # mining.yml B-3
  mainhand-buffs: { mining-fortune: 25 }   # effect-text: "採掘時の追加ドロップ +25%"
  ```

## PRG-06 woodcutting C-1/C-2・digging PRESTIGE の `mining-fortune` は対象ブロック集合に丸太/土系が無いため完全に不発

- **重要度**: HIGH
- **分類**: UNIMPLEMENTED / BUG
- **場所**: `TrinityForge/src/main/resources/skilltree/woodcutting.yml`(C-1: 5, C-2: 45) / `digging.yml`(PRESTIGE: 15) / `mining.yml`(PRESTIGE: 20) / `TrinityForge/src/main/resources/gimmick/mining-gimmick.yml` `fortune.fortune-blocks`
- **事象**: `MiningFortuneListener` は `mining-gimmick.yml` の `fortune-blocks` に含まれるブロックでのみ発火する。当該リストは鉱石と作物のみで、`*_LOG` / `DIRT` / `GRAVEL` / `SAND` などシャベル・斧対象ブロックを一切含まない。
- **影響/再現**: woodcutting C-1(5)・C-2(45)・digging PRESTIGE(15) の3配置は数値が何であれ一切適用されない。伐採ツリーC系統2ノード(2SP)とdiggingプレステージ報酬が死んでいる。逆に mining PRESTIGE(20) は鉱石に効くため PRG-05 の桁ずれをさらに悪化させる(+6個相当)。
- **根拠**: `mining-gimmick.yml` の `fortune-blocks` を全走査した結果、`LOG`/`WOOD`/`DIRT`/`GRAVEL`/`SAND`/`SOUL_SAND` のいずれのキーも存在しない。

## PRG-07 woodcutting B-1/B-2/B-3 が `cooldown-reduction`(武器クールダウン)を使っており、一括伐採CDに一切届かない

- **重要度**: HIGH
- **分類**: BUG
- **場所**: `TrinityForge/src/main/resources/skilltree/woodcutting.yml`(B-1/B-2/B-3 `mainhand-buffs: cooldown-reduction: 0.3`)
- **事象**: 効果テキストは「一括破壊のcdを-3s」だが、`cooldown-reduction` は戦闘の武器クールダウン用スタットで、`tree-fell` は `gimmick` 側の `tree-fell.cooldown-ticks` を読む。CD短縮に使うべきキーは `skill-cooldown-reduction`(mining A-2/A-3, digging A-2 で既に採用済)。
- **影響/再現**: 伐採B系統3ノード(3SP)が伐採CDを1tickも短縮しない。代わりに斧を持っている間の**戦闘**クールダウンが -0.3×3 = -0.9(実質フルスタック)されるという、意図しない副作用が発生している。mining/digging は移行済みで woodcutting だけ取り残されている。
- **根拠**:
  ```yaml
  # woodcutting.yml B-1  (mainhand-buffs)
  cooldown-reduction: 0.3      # effect-text: 一括破壊のcdを-3s
  # 対比: mining.yml A-2 (mainhand-buffs)
  skill-cooldown-reduction: 0.25
  ```

## PRG-08 SP総供給がノード総コストに対し圧倒的に不足(最大95SP vs 総コスト295SP)

- **重要度**: HIGH
- **分類**: BALANCE
- **場所**: `TrinityForge/src/main/java/com/trinityforge/progression/NativeProgressionService.java:120` / `TrinityForge/src/main/resources/skills/base/power_progression.yml` / `TrinityForge/src/main/resources/skilltree/*.yml`
- **事象**: 利用可能SP = `3 + POWER.level - spentPoints`。POWER は他スキルが1レベル上がるごとに100EXPを得るのみで、他のEXP源を持たない。一方ノード総コストは全16ツリー合計295SP(全ノード cost=1)。
- **影響/再現**: 15スキル×Lv100 = 1500レベル分 = 150,000 EXP。`power_progression.yml` の `(%level%/100)*1800 + 800` 累積で到達できる POWER レベルは約92。すなわち上限 SP ≒ 3+92 = 95。排他を考慮した実効取得可能ノード数 ≒ 232 に対しても 41% しか取得できない。1スキルにつき1回プレステージしても ≒144SP で依然不足。
- **根拠**:
  ```java
  // NativeProgressionService.java:120
  STARTING_SKILL_POINTS + resultingPowerLevel - player.spentPoints());
  ```
  ```yaml
  # power_progression.yml
  formula: "(%level%/100) * 1800 + 800"
  max_level: 256
  exp_gain: 100
  ```

## PRG-09 プレステージがSPを増殖させる(スキルLvは0に戻るが POWER Lv は据え置き)

- **重要度**: HIGH
- **分類**: BUG / BALANCE
- **場所**: `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativePerkService.java`(`prestigeUnderLock`) / `TrinityForge/src/main/java/com/trinityforge/progression/NativeProgressionService.java:120`
- **事象**: プレステージは対象スキルを level 0 にリセットし、そのツリーのノードコストを全額返還する。しかし POWER レベルは減算されないため、同じスキルを再度100まで上げると POWER が再び100レベル分(=10,000EXP)を受け取る。
- **影響/再現**: 「スキルを上げる → プレステージ → 上げ直す」を繰り返すだけで POWER レベル、ひいてはSPが無限に増える。上限 `max_level: 256` に達するまで、1周につき最大+8〜10SP 相当。PRG-08 のSP不足を実質的に「グラインドで無限解決」できてしまい、設計上の希少性が破綻する。
- **根拠**: `prestigeUnderLock` は `repository.prestige(playerId, skillId, ordinaryPerkPrefix, prestigePerkId, resetProgress, refundPoints)` を呼ぶが、POWER 側の減算処理は存在しない(`NativeProgressionService` に POWER レベル減算のコードパスなし)。

---

# MEDIUM 所見

## PRG-10 機構ゼロのノード/プレステージが29件(いずれも1SP課金・効果テキストのみ)

- **重要度**: MEDIUM
- **分類**: UNIMPLEMENTED
- **場所**: `TrinityForge/src/main/resources/skilltree/*.yml`(表 PRG-A3 参照)
- **事象**: `buffs` / `mainhand-buffs` / `multipliers` / `mainhand-multipliers` / `dedicated-effects` のいずれも持たないノードが29件。うち6件はプレステージ報酬ブロック(alchemy/enchanting/farming/fishing/smithing/woodcutting)。
- **影響/再現**: 取得しても数値も機構も何も動かない。ars_magic の B-1-1〜C-4-3(10件)は「〇〇解放」と書かれているが glyph ゲートIDも付いておらず、実際には何も解放しない。プレステージ6件は Lv100 到達+スキルリセットという最大級のコストに対し報酬が0。
- **根拠**: PyYAML による全ノード走査。`power.yml PRESTIGE` は `enabled: false` のため除外済み(29件はすべて有効な取得対象)。

## PRG-11 `armor-defense-rate` の単位不整合 — 正規化から意図的に除外されているのにツリー側は率表記

- **重要度**: MEDIUM
- **分類**: BUG / BALANCE
- **場所**: `TrinityForge/src/main/java/com/trinityforge/stats/PercentStatNormalize.java` / `TrinityForge/src/main/resources/skilltree/heavy_armor.yml`, `light_armor.yml`
- **事象**: `PercentStatNormalize` は `armor-defense-rate` を RATE_KEYS から意図的に除外している(コメント「バニラ Attribute.ARMOR ポイントそのもの。8 = +8 armor」)。ところがツリーは `D: 0.1`、`*-beta-1: 0.05`×5、`PRESTIGE: 0.1` と率のつもりで記述している。
- **影響/再現**: 重装/軽装それぞれ β系統フル取得+プレステージで合計 0.1+0.05×5+0.1 = 0.45 armor ポイント。バニラ防具1枚が2〜3ポイントなので、12ノード相当の投資が防具の1/5枚分にも届かない。実質的に無意味なノード群。
- **根拠**:
  ```yaml
  # heavy_armor.yml D
  buffs: { armor-defense-rate: 0.1 }   # 実効: +0.1 armor point
  ```

## PRG-12 `dungeon-only-exp` ガードが防具被弾EXPにしか適用されておらず、採取/釣り/エンチャ/醸造EXPはワールド制限を素通りする

- **重要度**: MEDIUM
- **分類**: BUG
- **場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/NativeSkillExperienceListener.java`(`expAllowedInWorld` の呼出箇所)
- **事象**: ワールド制限判定 `expAllowedInWorld` は `onArmorDamage` からのみ呼ばれる。採掘/伐採/整地/農耕/釣り/エンチャント/醸造/耐久消耗の各EXP付与経路には判定が無い。
- **影響/再現**: `dungeon-only-exp: true` を有効にしても、オーバーワールドで採掘・釣り・農耕を続ければスキルEXPが通常通り入る。設定の意図(ダンジョン限定)が半分しか機能しておらず、運用者から見て「設定が効かない」不具合に見える。
- **根拠**: `expAllowedInWorld(` の grep ヒットは定義1件+`onArmorDamage` 内1件のみ。

## PRG-13 耐久消耗による SMITHING EXP が1tickあたり最低0.01保証で、無限EXP経路になる

- **重要度**: MEDIUM
- **分類**: BALANCE
- **場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/NativeSkillExperienceListener.java`(`onItemDamage`)
- **事象**: `Math.max(0.01, damage * rate)` により、`rate` が極小でも耐久が1減るたび必ず0.01以上のEXPが入る。耐久消耗は「壊れる寸前で修理」を繰り返せば無限に発生させられる。
- **影響/再現**: 金の道具(耐久32)+修繕/金床サイクル、あるいは Unbreaking 0 のツールで壁を掘り続けるだけで SMITHING EXP が単調増加。設計上「鍛冶で上げるスキル」が採掘の副産物で上がる。
- **根拠**:
  ```java
  double amount = Math.max(0.01, damage * rate);
  ```

## PRG-14 SQLite: `schema_version` テーブルが未作成だった v1 DB が migrate 前に v2 として刻印され、以後起動不能になり得る

- **重要度**: MEDIUM
- **分類**: BUG
- **場所**: `TrinityForge/src/main/java/com/trinityforge/progression/infrastructure/sqlite/SqliteProgressionRepository.java:546-551, 553-570`
- **事象**: `createSchema` は `schema_version` が空なら無条件に `SCHEMA_VERSION`(=2)を挿入する。この処理は `migrateSchema` より前に走るため、`schema_version` テーブルを持たない旧v1 DB は「v2 である」と刻印され、`purchase_cost` 列を追加する v1→v2 マイグレーションが実行されない。
- **影響/再現**: 直後の `prepareStatements` が `SELECT perk_id, purchase_cost FROM player_perk_states` を用意しようとして `SQLException: no such column: purchase_cost` を投げ、進行系リポジトリが初期化失敗する。旧バージョンからのアップグレード時のみ再現。
- **根拠**:
  ```java
  try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM schema_version")) {
      if (rs.next() && rs.getInt(1) == 0) { insertSchemaVersion(s.getConnection(), SCHEMA_VERSION); }
  ```

## PRG-15 プレステージの perk 削除条件が `NOT LIKE prefix||'ng%'` で、`ng` 始まりのノードIDがあると返金のみ発生しノードが残る

- **重要度**: MEDIUM
- **分類**: BUG
- **場所**: `TrinityForge/src/main/java/com/trinityforge/progression/infrastructure/sqlite/SqliteProgressionRepository.java:344` / `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativePerkService.java`(`prestigeUnderLock`)
- **事象**: 削除SQLは `perk_id LIKE ? AND perk_id NOT LIKE ?` で、後者はプレステージ perk(`<skill>_perk_ng<tier>`)を保護する目的。しかし通常ノードIDが `ng` で始まると同じパターンに合致してしまう。返金額の集計側は `storedCosts` の全所持ノードを対象とするため保護対象外。
- **影響/再現**: 現行yml に `ng` 始まりのノードIDは無いため実害は0だが、将来 `ng-boost` のようなIDを追加した瞬間に「SPは返ってくるがノードは所持したまま」= 無限SP増殖になる。潜在的な地雷。
- **根拠**:
  ```java
  " AND perk_id LIKE ? AND perk_id NOT LIKE ?"
  ```
  (要確認: `ordinaryPerkPrefix` / `prestigePerkPrefix` の実引数生成は `PerkNaming` に依存)

## PRG-16 `CachedProgressionRepository.cacheGen` が UUID ごとに増え続け解放されない

- **重要度**: MEDIUM
- **分類**: BUG
- **場所**: `TrinityForge/src/main/java/com/trinityforge/progression/infrastructure/CachedProgressionRepository.java`
- **事象**: 世代番号マップ `cacheGen` はエントリを増やす一方で、プレイヤー退出時にもエントリを除去しない。キャッシュ本体は evict されるが世代マップは残る。
- **影響/再現**: 長期稼働サーバで累積ユニークプレイヤー数に比例したメモリリーク。1エントリあたりは小さいので致命ではないが、無期限に単調増加する。
- **根拠**: `cacheGen.remove(` の grep ヒット0件。

## PRG-17 GUI: ナビゲーションボタンをビューポート描画**後**に上書きするため、8マス分のノード/コネクタが常に隠れる

- **重要度**: MEDIUM
- **分類**: BUG
- **場所**: `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativeSkillTreeMenu.java:40-49, 140-170`
- **事象**: スロット 0/4/8/18/26/36/40/44 は `NAVIGATION_SLOTS` として `viewport` 描画のあとに `setItem` で上書きされる。9×5 ビューポートのうち8マスが常時不可視。
- **影響/再現**: ノードがちょうど四隅/辺中央のセルに来ると、そのノードは表示もクリックもできない。プレイヤーは1マスずらして再描画しないと取得できず、初見では「ノードが消えた」と映る。
- **根拠**:
  ```java
  NAVIGATION_SLOTS.forEach((slot, action) -> inventory.setItem(slot, button(...)));  // 168行
  ```
  (140-165行のビューポート描画より後に実行)

## PRG-18 GUI を開くたび/移動クリックのたびにジェネレータ全体を再実行し、メインスレッドで進行DBスナップショットを取る

- **重要度**: MEDIUM
- **分類**: BUG
- **場所**: `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativeSkillTreeMenu.java:132-134` / `NativeSkillTreeCanvas.project(tree)`
- **事象**: `progression.snapshot()` と `loadOwnedPerkIds()` がメインスレッドから同期で呼ばれ、さらに `NativeSkillTreeCanvas.project(tree)` がツリー全体のレイアウト生成を毎回やり直す。移動ボタン1クリックごとに同じ処理が走る。
- **影響/再現**: `ars_magic`/`archery`(24ノード)クラスのツリーで、キャッシュ層がミスした場合はDBスレッド応答待ち(最大15秒タイムアウト)がメインスレッドをブロックしうる。連打時のtick遅延要因。
- **根拠**:
  ```java
  Set<String> owned = loadOwnedPerkIds(player.getUniqueId());
  var snapshot = progression.snapshot(player.getUniqueId());
  ```

## PRG-19 `PlayerExpChangeEvent` を2つのリスナーが処理し、倍率が乗算合成される

- **重要度**: MEDIUM
- **分類**: BUG
- **場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/`(`PlayerExpChangeEvent` を購読する2クラス)
- **事象**: バニラEXP倍率系の適用が2箇所に分かれており、同一イベントに対して両方が `setAmount` を行う。結果として `base × (1+a) × (1+b)` になる。
- **影響/再現**: `vanilla_exp_bonus` と `kill_vanilla_exp_bonus` を両方持つ想定では、意図した加算合成(1+a+b)より大きくなる。ただし現状は両キーとも入手経路ゼロ(PRG-03)のため実害は顕在化していない。PRG-03 を解消した瞬間に発現する。
- **根拠**: `@EventHandler` + `PlayerExpChangeEvent` の grep ヒットが2ファイル。

## PRG-20 `NativeAttributeBridge` が軽装/重装の判別をバニラ素材名のみで行う

- **重要度**: MEDIUM
- **分類**: BUG
- **場所**: `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativeAttributeBridge.java`
- **事象**: `light-armor-*` / `heavy-armor-*` 系のセットボーナス判定は `Material` 名(LEATHER/CHAINMAIL → 軽、IRON/DIAMOND/NETHERITE → 重)に依存する。TFカタログの独自防具や fork の防具は素材名がバニラのままなので、意図した分類に落ちない可能性がある。
- **影響/再現**: 軽装ツリーを取ったプレイヤーがTFカタログの軽装デザイン装備(ベース素材がIRON)を着ると、軽装セットボーナスが乗らず重装扱いになる。
- **根拠**: 素材名以外のマーカー(PDC/item-stats の分類フィールド)を参照するコードパスが存在しない。(要確認: item-stats 側に装備区分フィールドが存在するかは範囲外)

## PRG-21 `FormulaParser` が単項マイナスを解釈できない

- **重要度**: MEDIUM
- **分類**: BUG
- **場所**: `TrinityForge/src/main/java/com/trinityforge/progression/`(`FormulaParser`)
- **事象**: 再帰下降パーサの `primary` は数値 / `%level%` / 括弧のみを受理し、前置 `-` を扱わない。`-5 + %level%` のような式や `2^(-%level%)` はパース例外になる。
- **影響/再現**: 現行の17本の `*_progression.yml` は単項マイナスを使っていないため実害0。ただし運用者がエディタから減衰カーブを書いた瞬間に起動時パース失敗となる。エディタ側にバリデーションが無いため発見が遅れる。
- **根拠**: `parsePrimary` に `case '-'` の分岐が存在しない。

---

# LOW 所見

## PRG-22 `reward:` ゲート接頭辞がパーサに実装されているが全ymlで未使用

- **重要度**: LOW / **分類**: DEADCODE
- **場所**: `TrinityForge/src/main/java/com/trinityforge/skilltree/effects/GateEffectId.java`
- **事象/根拠**: `reward:` は FLAG チャネルへルーティングされる接頭辞として実装済みだが、全16ツリーの `dedicated-effects` 抽出結果に `reward:` は0件。対応する消費側リスナーも存在しない。

## PRG-23 `NativeRewardRegistry` が完全なデッドコード(129行)

- **重要度**: LOW / **分類**: DEADCODE
- **場所**: `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativeRewardRegistry.java`
- **事象/根拠**: main / test 双方で参照0件。PRG-22 の `reward:` 接頭辞とセットで導入され、使われないまま残っている。

## PRG-24 `SkillLevelCache` がデッドコードで、javadoc が撤去済みクラスを参照

- **重要度**: LOW / **分類**: DEADCODE / COMMENT
- **場所**: `TrinityForge/src/main/java/com/trinityforge/progression/SkillLevelCache.java`(75行)
- **事象/根拠**: main 参照0件。javadoc に `ValhallaSkillLevelSource` の記述が残るが、当該クラスは2026-07-22 の native 移行で削除済み。コメント分類は **DELETE**(クラスごと)。

## PRG-25 `skilltree/deploy/` が空ディレクトリ

- **重要度**: LOW / **分類**: DEADCODE
- **場所**: `TrinityForge/src/main/java/com/trinityforge/skilltree/deploy/`
- **事象/根拠**: `.java` ファイル0件。パッケージ構造だけが残存。

## PRG-26 `digging.yml A-3` の `mainhand-buffs: attack-power: 0` は完全な no-op

- **重要度**: LOW / **分類**: DEADCODE
- **場所**: `TrinityForge/src/main/resources/skilltree/digging.yml`(A-3)
- **事象/根拠**: 値0の加算バフ。効果テキストと一致していない可能性が高い。実質 PRG-10 の機構ゼロノードと同等。

## PRG-27 `loot_luck` の単位がツリー間で一貫していない

- **重要度**: LOW / **分類**: BALANCE
- **場所**: `enchanting.yml#D=1`, `fishing.yml#B=1`, `power.yml#A-4=0.2`, `#A-5=0.3`, `#A-6=0.5`
- **事象/根拠**: 同一キーに対し「1(整数=Luckレベル相当)」と「0.2〜0.5(率)」が混在。`PercentStatNormalize` の RATE_KEYS 対象なら1は0.01に丸められ、非対象なら power の0.2が無視される。どちらにせよ片方の意図が壊れる。(要確認: `loot_luck` の RATE_KEYS 収載有無)

## PRG-28 digging ツリーが `drop:mining:*` カテゴリをゲートしている

- **重要度**: LOW / **分類**: BUG
- **場所**: `digging.yml`(B → `drop:mining:gacha_tier1`, D → `drop:mining:gacha_tier2`, B-1 → `drop:mining:ancient_debris`, B-2 → `drop:mining:gacha_tier3`)
- **事象/根拠**: 整地(digging)ツリーのノードが採掘(mining)チャネルのドロップを解放する。`digging.yml#E` だけは `drop:digging:ruins_thread` と正しく digging チャネルを使っており、他4件が意図通りかは不明。(要確認: ドロップテーブル側で `mining` カテゴリがシャベル対象ブロックも含むか)

## PRG-29 GUI が残余EXPを分母なしの生値で表示し、レベル上限到達後も無制限に増える

- **重要度**: LOW / **分類**: BUG
- **場所**: `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativeSkillTreeMenu.java` / `progression`(`residualExp`)
- **事象/根拠**: 表示は `residualExp` の生値のみで「次レベルまで X / Y」の分母が無い。`max_level` 到達後も residual は加算され続けるため、Lv100 で数十万の数値が並ぶ。

## PRG-30 `PlacedBlockTracker` が設置のたびに PDC の long 配列を丸ごと書き直す

- **重要度**: LOW / **分類**: BUG
- **場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/`(`PlacedBlockTracker`)
- **事象/根拠**: 1ブロック設置あたり O(n) の配列コピー+PDC書き込み。大量建築時にチャンクごとの追跡配列が肥大化し、設置レートに対して二次的コストになる。

## PRG-31 `power.yml PRESTIGE` が `enabled: false` のまま定義だけ残る

- **重要度**: LOW / **分類**: DEADCODE
- **場所**: `TrinityForge/src/main/resources/skilltree/power.yml`
- **事象/根拠**: `prestige.enabled: false` かつ `name` / `at-level` / `effect-text` すべて未設定。ロード時に読み捨てられる空定義。

## PRG-32 コメント棚卸し(担当領域内)

- **重要度**: LOW / **分類**: COMMENT
- **場所**: 下記

| 対象 | 分類 | 理由 |
|---|---|---|
| `NativePerkService.java` プレステージのロック区間コメント(「concurrent unlockPerk landing between the read and the wipe would be deleted without refund」) | **KEEP** | 非自明な同時実行契約を説明しており、消すと再発を招く |
| `PercentStatNormalize.java` の `armor-defense-rate` 除外理由コメント | **KEEP** | PRG-11 の唯一の設計根拠。ただしツリー側の記法と矛盾しているため、コメント側ではなく yml 側を直すべき |
| `SqliteProgressionRepository.java` javadoc の `Schema version: 2` | **KEEP** | バージョン更新時に追随が必要な生きた記述 |
| `SkillLevelCache.java` javadoc の `ValhallaSkillLevelSource` 参照 | **DELETE** | 参照先クラスが存在しない。クラス自体がデッドコード(PRG-24) |
| `UnifiedRecipeLoader.java`(fork) 冒頭の「2026-07-25 catalog.yml/items.yml統合」履歴コメント | **EXTERNALIZE** | 変更履歴はコード内でなく `docs/` か CHANGELOG へ |
| `functional-items.yml`(fork) 冒頭の「移行元 (2026-07-25)」ブロック | **EXTERNALIZE** | 同上。移行経緯は設計文書側が適切 |
| 各 `skilltree/*.yml` の `effects:` / `commands:` フィールド | **DELETE** | ラウンドトリップ保持専用で実装が存在しない(過去メモ `skilltree-custom-content-unimplemented` の指摘が現在も有効)。yml から除去すべき |

---

# 付表(省略禁止・全件)

## 表 PRG-A1: `dedicated-effects` ID 全件と解決状況

チャネル別に全159配置(ユニーク163ID中の実配置)を列挙する。

### A1-1: `recipe:` (57件中 **41件が未解決**)

| ID | 配置 | 解決先 | 判定 |
|---|---|---|---|
| recipe:compressed_bread_1x | farming.yml#B | food-gimmick.yml / materials.yml | OK |
| recipe:compressed_cooked_beef_1x | farming.yml#B | food-gimmick.yml / materials.yml | OK |
| recipe:diamond_axe | smithing.yml#C | — | **未実装** |
| recipe:diamond_boots | smithing.yml#C | — | **未実装** |
| recipe:diamond_chestplate | smithing.yml#C | — | **未実装** |
| recipe:diamond_helmet | smithing.yml#C | — | **未実装** |
| recipe:diamond_hoe | smithing.yml#C | — | **未実装** |
| recipe:diamond_leggings | smithing.yml#C | — | **未実装** |
| recipe:diamond_pickaxe | smithing.yml#C | — | **未実装** |
| recipe:diamond_shovel | smithing.yml#C | — | **未実装** |
| recipe:diamond_sword | smithing.yml#C | — | **未実装** |
| recipe:enchant_book_mana_boost_1 | ars_smithing.yml#B-1-1 | fork items.yml | OK |
| recipe:enchant_book_mana_boost_2 | ars_smithing.yml#B-1-2 | fork items.yml | OK |
| recipe:enchant_book_mana_boost_3 | ars_smithing.yml#B-1-4 | fork items.yml | OK |
| recipe:enchant_book_mana_regen_1 | ars_smithing.yml#B-1-1 | fork items.yml | OK |
| recipe:enchant_book_mana_regen_2 | ars_smithing.yml#B-1-2 | fork items.yml | OK |
| recipe:enchant_book_mana_regen_3 | ars_smithing.yml#B-1-4 | fork items.yml | OK |
| recipe:enchant_book_share | ars_smithing.yml#B-1-4 | fork items.yml | OK |
| recipe:enchant_book_soulbound | ars_smithing.yml#B-1-2 | fork items.yml | OK |
| recipe:golden_axe | smithing.yml#B | — | **未実装** |
| recipe:golden_boots | smithing.yml#B | — | **未実装** |
| recipe:golden_chestplate | smithing.yml#B | — | **未実装** |
| recipe:golden_helmet | smithing.yml#B | — | **未実装** |
| recipe:golden_hoe | smithing.yml#B | — | **未実装** |
| recipe:golden_leggings | smithing.yml#B | — | **未実装** |
| recipe:golden_pickaxe | smithing.yml#B | — | **未実装** |
| recipe:golden_shovel | smithing.yml#B | — | **未実装** |
| recipe:golden_sword | smithing.yml#B | — | **未実装** |
| recipe:iron_axe | smithing.yml#B | — | **未実装** |
| recipe:iron_boots | smithing.yml#B | — | **未実装** |
| recipe:iron_chestplate | smithing.yml#B | — | **未実装** |
| recipe:iron_helmet | smithing.yml#B | — | **未実装** |
| recipe:iron_hoe | smithing.yml#B | — | **未実装** |
| recipe:iron_leggings | smithing.yml#B | — | **未実装** |
| recipe:iron_pickaxe | smithing.yml#B | — | **未実装** |
| recipe:iron_shovel | smithing.yml#B | — | **未実装** |
| recipe:iron_sword | smithing.yml#B | — | **未実装** |
| recipe:netherite_axe | smithing.yml#D | — | **未実装** |
| recipe:netherite_boots | smithing.yml#D | — | **未実装** |
| recipe:netherite_chestplate | smithing.yml#D | — | **未実装** |
| recipe:netherite_helmet | smithing.yml#D | — | **未実装** |
| recipe:netherite_hoe | smithing.yml#D | — | **未実装** |
| recipe:netherite_leggings | smithing.yml#D | — | **未実装** |
| recipe:netherite_pickaxe | smithing.yml#D | — | **未実装** |
| recipe:netherite_shovel | smithing.yml#D | — | **未実装** |
| recipe:netherite_sword | smithing.yml#D | — | **未実装** |
| recipe:source_gem_block | ars_smithing.yml#A | fork materials.yml | OK |
| recipe:stone_axe | smithing.yml#A | — | **未実装** |
| recipe:stone_hoe | smithing.yml#A | — | **未実装** |
| recipe:stone_pickaxe | smithing.yml#A | — | **未実装** |
| recipe:stone_shovel | smithing.yml#A | — | **未実装** |
| recipe:stone_sword | smithing.yml#A | — | **未実装** |
| recipe:tf_core_jewelry | mining.yml#E | fork materials.yml / gacha.yml | OK |
| recipe:tf_core_meat | farming.yml#E | fork materials.yml / gacha.yml | OK |
| recipe:tf_core_vegetable | farming.yml#E | fork materials.yml / gacha.yml | OK |
| recipe:tf_core_wood | woodcutting.yml#E | fork materials.yml / gacha.yml | OK |
| recipe:volcanic_sourcelink | ars_smithing.yml#A-3 | fork sourcelinks.yml / config.yml | OK |

### A1-2: `ritual:` (9件 — 全件解決 OK)

| ID | 配置 | 解決先 | 判定 |
|---|---|---|---|
| ritual:animal_summon | ars_smithing.yml#B-2-1 | fork items.yml `ritual_effects` | OK |
| ritual:flight | ars_smithing.yml#B-2-3 | fork items.yml `ritual_effects` | OK |
| ritual:mob_summon_nether | ars_smithing.yml#B-2-2 | fork items.yml `ritual_effects` | OK |
| ritual:repair | ars_smithing.yml#B-2-3 | fork items.yml `ritual_effects` | OK |
| ritual:teleport_compass | ars_smithing.yml#D-1 | fork functional-items.yml(`method: ritual`) | OK |
| ritual:waystone | ars_smithing.yml#D-1 | fork functional-items.yml(`method: ritual`) | OK |
| ritual:weather_clear | ars_smithing.yml#B-2-2 | fork items.yml `ritual_effects` | OK |
| ritual:weather_rain | ars_smithing.yml#B-2-2 | fork items.yml `ritual_effects` | OK |
| ritual:weather_thunder | ars_smithing.yml#B-2-2 | fork items.yml `ritual_effects` | OK |

`UnifiedRecipeLoader.loadFunctionalItemsYml()`(54行/74行)が `functional-items.yml` の `items.<id>.recipe`(`method: ritual`)を `RitualRecipe(id=セクションキー)` として登録するため、`waystone` / `teleport_compass` は正しい ritualId。fork 側 `UnlockGate.hasRitualPermission`(120-123行)が `TrinityForgeBridge.tfRitualGatePerks()` を参照しており配線も生存。

### A1-3: `glyph:` (47件 — 全件解決 OK)

`ars_magic.yml`(21件) / `alchemy.yml`(6) / `mining.yml`(3) / `woodcutting.yml`(2) / `farming.yml`(3) / `smithing.yml`(2) / `enchanting.yml`(2) / `fishing.yml`(2) / `digging.yml`(1)。fork `glyphs.yml` の `glyphs:` 直下に120IDが定義されており、47件すべてがヒット。`UsageGate.java:81` が `tfGlyphGatePerks()` を参照して配線も生存。

| ID | 配置 |
|---|---|
| glyph:advanced_break | mining.yml#E |
| glyph:beam | ars_magic.yml#E-1 |
| glyph:blink | ars_magic.yml#A-1-4 |
| glyph:break | mining.yml#D |
| glyph:burst | ars_magic.yml#C-2-1 |
| glyph:craft | smithing.yml#A-4 |
| glyph:crush | digging.yml#D |
| glyph:cut | woodcutting.yml#A-4 |
| glyph:dispel | farming.yml#A-3 |
| glyph:evaporate | fishing.yml#B |
| glyph:exchange | alchemy.yml#E |
| glyph:extract | enchanting.yml#D |
| glyph:fell | woodcutting.yml#D |
| glyph:fortune | enchanting.yml#D |
| glyph:glide | ars_magic.yml#A-1-2 |
| glyph:harvest | farming.yml#E |
| glyph:heal | alchemy.yml#E-1-1 |
| glyph:hex | alchemy.yml#E-2-1 |
| glyph:infuse | alchemy.yml#C-1-lower |
| glyph:journey | alchemy.yml#E-1-1 |
| glyph:light | mining.yml#B |
| glyph:linger | ars_magic.yml#C-1-1 |
| glyph:phantom_block | ars_magic.yml#A-2-1 |
| glyph:pickup | fishing.yml#D |
| glyph:place_block | ars_magic.yml#A-2-1 |
| glyph:propagate | ars_magic.yml#C-1-1 |
| glyph:saturation | farming.yml#A-alphabeta-3 |
| glyph:scale | ars_magic.yml#A-2-2 |
| glyph:smelt | smithing.yml#A-4 |
| glyph:speed_boost | ars_magic.yml#A-1-3 |
| glyph:summon_steed | ars_magic.yml#A-1-1 |
| glyph:super_accelerate | ars_magic.yml#E-1 |
| glyph:super_amplify | ars_magic.yml#E-1 |
| glyph:super_aoe_radius | ars_magic.yml#E-1 |
| glyph:super_dampen | ars_magic.yml#E-1 |
| glyph:super_decelerate | ars_magic.yml#E-1 |
| glyph:super_delay | ars_magic.yml#E-1 |
| glyph:super_duration_down | ars_magic.yml#E-1 |
| glyph:super_extend_reach | ars_magic.yml#E-1 |
| glyph:super_extend_time | ars_magic.yml#E-1 |
| glyph:super_fortune | ars_magic.yml#E-1 |
| glyph:super_linger | ars_magic.yml#E-1 |
| glyph:super_pierce | ars_magic.yml#E-1 |
| glyph:super_propagate | ars_magic.yml#E-1 |
| glyph:super_shrink_reach | ars_magic.yml#E-1 |
| glyph:super_split | ars_magic.yml#E-1 |
| glyph:wither | alchemy.yml#C-2-lower |

### A1-4: `feature:` (25語彙中 24件配置 / **1件未配置**)

| feature 語彙 | Param | 配置 | 判定 |
|---|---|---|---|
| animal-damage-4x | NONE | farming.yml#A-4 | OK |
| area-harvest | NONE | farming.yml#D | OK |
| auto-replant | NONE | farming.yml#A | OK |
| bee-no-aggro | NONE | farming.yml#A-3 | OK |
| **break-vanilla-exp** | NONE | **なし** | **未実装(PRG-01)** |
| digging-durability-job-exp | LEVEL | digging.yml#C-2 (25) | OK |
| digging-durability-vanilla-exp | LEVEL | digging.yml#C-1 (50) | OK |
| dismantle-unlock | NONE | smithing.yml#B-3-upper (1) | OK |
| fish-sell-toggle | NONE | fishing.yml#B-alpha-1 | OK |
| furnace-smelt-bonus | LEVEL | smithing.yml#B-1(10)/B-2(20)/B-3(30) | OK |
| furnace-smelt-speed | LEVEL | smithing.yml#A-1(10)/A-2(20)/A-3(30) | OK |
| haste-active-mining | NONE | digging.yml#A-1, mining.yml#A-1 | OK |
| junk-food-restore-boost | LEVEL | farming.yml#A-alpha-2 (30) | OK |
| junk-to-scrap | NONE | fishing.yml#B-alpha-2 | OK |
| junkfood-immunity | NONE | farming.yml#A-alpha-1 | OK |
| junkfood-inversion | NONE | farming.yml#A-alpha-1 | OK |
| potion-merge | NONE | alchemy.yml#D | OK |
| satiety-buff | NONE | farming.yml#B | OK |
| source-auto-consume | NONE | ars_smithing.yml#A-2 | OK |
| spawner-silktouch-harvest | NONE | mining.yml#D | OK |
| tree-fell | SCALE | woodcutting.yml#B(1)/D(3) | OK |
| vein-mining | NONE | mining.yml#D-1 | OK |
| weapon-coating-unlock | NONE | alchemy.yml#C | OK |
| wood-repair-unlock | NONE | woodcutting.yml#E | OK |
| xp-bottle-store-unlock | NONE | enchanting.yml#B-3 | OK |

### A1-5: `drop:` / `brew:` / `trade:` / `overenchant:` / ベアリテラル(全件 OK)

| ID | 配置 | 判定 |
|---|---|---|
| drop:digging:ruins_thread | digging.yml#E | OK |
| drop:fishing:item:tf_gacha_ticket_4 | fishing.yml#B-beta-1 | OK |
| drop:fishing:item:tf_gacha_ticket_5 | fishing.yml#B-beta-3 | OK |
| drop:fishing:ocean_thread | fishing.yml#E | OK |
| drop:mining:ancient_debris | digging.yml#B-1 | OK(チャネル要確認 PRG-28) |
| drop:mining:gacha_tier1 | digging.yml#B | OK(同上) |
| drop:mining:gacha_tier2 | digging.yml#D | OK(同上) |
| drop:mining:gacha_tier3 | digging.yml#B-2 | OK(同上) |
| drop:woodcutting:apple | woodcutting.yml#A-1 | OK |
| drop:woodcutting:crystal_apple | woodcutting.yml#A-4 | OK |
| drop:woodcutting:golden_apple | woodcutting.yml#A-2 | OK |
| brew:healthboost-haste | alchemy.yml#C-2-upper | OK |
| brew:swiftness-jump | alchemy.yml#C-1-upper | OK |
| trade:ARMORER | smithing.yml#E | OK |
| trade:CLERIC | alchemy.yml#C | OK |
| trade:LIBRARIAN | enchanting.yml#E | OK |
| trade:TOOLSMITH | smithing.yml#E | OK |
| trade:WEAPONSMITH | smithing.yml#E | OK |
| overenchant:lv1 | enchanting.yml#C-1 | OK |
| overenchant:lv2 | enchanting.yml#C-2 | OK |
| overenchant:lv3 | enchanting.yml#C-3 | OK |
| ars-tier(ベアリテラル) | ars_magic.yml#A(1), #E(1) | OK |
| **reward:**(接頭辞) | **配置0件** | **未使用(PRG-22)** |

## 表 PRG-A2: 消費側実装ありだが入手経路が存在しない stat キー(13件・全件)

| # | キー | 消費側(TF main) | ツリー配置 | base-stats.yml | item-stats.yml | 判定 |
|---|---|---|---|---|---|---|
| 1 | workbench_quality_bonus | 品質分割(S7)処理 | なし | 0 | なし | **入手不能** |
| 2 | ritual_quality_bonus | 品質分割(S7)処理 | なし | 0 | なし | **入手不能** |
| 3 | vanilla_exp_bonus | PlayerExpChangeEvent 経路 | なし | 0 | なし | **入手不能** |
| 4 | kill_vanilla_exp_bonus | kill EXP 経路 | なし | 0 | なし | **入手不能** |
| 5 | break_vanilla_exp_bonus | `grantBreakVanillaExp` | なし | 0 | なし | **入手不能**(PRG-01と重複被害) |
| 6 | breeding_vanilla_exp_bonus | 繁殖EXP 経路 | なし | 0 | なし | **入手不能** |
| 7 | woodcutting_extra_drop_chance | 伐採追加ドロップ | なし | 0 | なし | **入手不能** |
| 8 | harvest_extra_drop_chance | 収穫追加ドロップ | なし | 0 | なし | **入手不能** |
| 9 | food_restore_bonus | `FoodBonusListener` | なし | 0 | なし | **入手不能** |
| 10 | hidden_saturation_bonus | `FoodBonusListener` | なし | 0 | なし | **入手不能** |
| 11 | breeding_extra_child_chance | 繁殖処理 | なし | 0 | なし | **入手不能** |
| 12 | bred_animal_growth_bonus | 成長処理 | なし | 0 | なし | **入手不能** |
| 13 | planted_crop_growth_bonus | `PlantedCrop` 処理 | なし | 0 | なし | **入手不能** |

注: `farming.yml#A-beta-2`(満腹度回復量UP)・`farming.yml#C`(収穫量UP)・`farming.yml#A-1`(繁殖2倍)・`farming.yml#A-2`(成長速度UP/繁殖EXP)は、まさにこれらのキーを配置すべきノードでありながら機構ゼロ(PRG-10)。上表と表 PRG-A3 は同一問題の表裏。

## 表 PRG-A3: 機構ゼロのノード/プレステージ全29件

| # | ファイル | ノード | 名称 | Lv | role | 効果テキスト |
|---|---|---|---|---|---|---|
| 1 | alchemy.yml | B-alpha-1 | 節約の調合I | 30 | greek | 素材を消費しない確率UP |
| 2 | alchemy.yml | PRESTIGE | 大錬金術師の栄光 | 100 | prestige | 全ての品質 永続+50・ランダムステータス品質 永続+15% |
| 3 | ars_magic.yml | B-1-1 | 焦熱 | 40 | branch | 焦熱解放 |
| 4 | ars_magic.yml | B-1-2 | 雷撃 | 40 | branch | 雷撃解放 |
| 5 | ars_magic.yml | B-2-1 | 凍裂 | 40 | branch | 凍裂解放 |
| 6 | ars_magic.yml | B-2-2 | 拘束 | 40 | branch | 拘束解放 |
| 7 | ars_magic.yml | B-3-1 | 烈風 | 40 | branch | 烈風解放 |
| 8 | ars_magic.yml | B-3-2 | 引寄 | 40 | branch | 引寄解放 |
| 9 | ars_magic.yml | C-4-1 | 狼召喚・生命化 | 40 | branch | 狼召喚・生命化解放 |
| 10 | ars_magic.yml | C-4-2 | 不死召喚 | 60 | branch | 不死召喚解放 |
| 11 | ars_magic.yml | C-4-3 | 囮・妖精召喚 | 80 | branch | 囮解放・妖精召喚開放 |
| 12 | digging.yml | A | はじまりの土木作業員 | 10 | main | 破壊で少量のバニラEXP |
| 13 | digging.yml | C | 土木事務所の管理者 | 50 | main | 破壊で少量の追加バニラEXP |
| 14 | enchanting.yml | PRESTIGE | 大エンチャンターの栄光 | 100 | prestige | エンチャントポイント 永続+20・消費経験値量 永続-10% |
| 15 | farming.yml | C | 近所の農家 | 50 | main | 収穫量UP・バニラEXPを追加で獲得 |
| 16 | farming.yml | A-1 | 鶏小屋の世話係 | 20 | branch | 繁殖時低確率で2倍 |
| 17 | farming.yml | A-2 | 牧場の運営者 | 40 | branch | 動物の成長速度UP・繁殖時バニラEXPを獲得 |
| 18 | farming.yml | A-beta-2 | 健康食I | 30 | greek | 満腹度回復量UP |
| 19 | farming.yml | PRESTIGE | 大地主の栄光 | 100 | prestige | 満腹度回復量 永続+1・経験値獲得 永続+10% |
| 20 | fishing.yml | A | 駆け出しの釣り人 | 10 | main | 同時ヒット確率アップ・得られる経験値量アップ |
| 21 | fishing.yml | C | 熟練の釣り師 | 50 | main | 同時ヒット確率アップ・得られる経験値量アップ |
| 22 | fishing.yml | B-beta-2 | 宝運上昇 | 40 | greek | 宝確率アップ・釣り竿の品質に応じてさらに確率アップ |
| 23 | fishing.yml | PRESTIGE | 大海の釣り王の栄光 | 100 | prestige | 同時ヒット確率 永続+10%・宝釣りの確率 永続+10% |
| 24 | mining.yml | A | はじまりの鉱夫 | 10 | main | 破壊で少量のバニラEXP |
| 25 | mining.yml | C | 鉱山の仕事人 | 50 | main | 破壊で2倍のバニラEXP |
| 26 | smithing.yml | PRESTIGE | 鍛冶王の栄光 | 100 | prestige | 全ての品質 永続+50・ランダムステータス品質 永続+15% |
| 27 | woodcutting.yml | A | はじまりの木こり | 10 | main | 破壊で少量のバニラEXP |
| 28 | woodcutting.yml | C | 雑木林の管理者 | 50 | main | 破壊で2倍のバニラEXP |
| 29 | woodcutting.yml | PRESTIGE | 大伐採者の栄光 | 100 | prestige | メインに斧を持つと採掘速度上昇 永続+1・全てのリンゴドロップ増加 永続+10%・木材の経験値変換効率+10% |

(`power.yml PRESTIGE` は `enabled: false` のため上表から除外 → PRG-31)

## 表 PRG-A4: 配置されているが届かない/単位が誤っている buff 全14件

| # | 場所 | キー | 値 | 問題 | 関連所見 |
|---|---|---|---|---|---|
| 1 | mining.yml#B-1 | mining-fortune | 15 | 単位が倍率(実効+4.5個)。テキストは+15% | PRG-05 |
| 2 | mining.yml#B-2 | mining-fortune | 20 | 同上(+6.0個) | PRG-05 |
| 3 | mining.yml#B-3 | mining-fortune | 25 | 同上(+7.5個) | PRG-05 |
| 4 | mining.yml#PRESTIGE | mining-fortune | 20 | 同上(+6.0個) | PRG-05 |
| 5 | woodcutting.yml#C-1 | mining-fortune | 5 | 丸太が `fortune-blocks` に無く完全不発 | PRG-06 |
| 6 | woodcutting.yml#C-2 | mining-fortune | 45 | 同上。かつ単位も誤り | PRG-05/06 |
| 7 | digging.yml#PRESTIGE | mining-fortune | 15 | 土/砂利/砂が `fortune-blocks` に無く完全不発 | PRG-06 |
| 8 | woodcutting.yml#B-1 | cooldown-reduction(mainhand) | 0.3 | 伐採CDでなく戦闘CDに効く | PRG-07 |
| 9 | woodcutting.yml#B-2 | cooldown-reduction(mainhand) | 0.3 | 同上 | PRG-07 |
| 10 | woodcutting.yml#B-3 | cooldown-reduction(mainhand) | 0.3 | 同上 | PRG-07 |
| 11 | heavy_armor.yml(D + 5×beta-1 + PRESTIGE) | armor-defense-rate | 0.1 / 0.05×5 / 0.1 | armor ポイント単位。合計0.45pt = ほぼ無効 | PRG-11 |
| 12 | light_armor.yml(D + 5×beta-1 + PRESTIGE) | armor-defense-rate | 0.1 / 0.05×5 / 0.1 | 同上 | PRG-11 |
| 13 | digging.yml#A-3 | attack-power(mainhand) | 0 | 値0で完全 no-op | PRG-26 |
| 14 | enchanting.yml#D / fishing.yml#B vs power.yml#A-4〜A-6 | loot_luck | 1 vs 0.2〜0.5 | 整数と率が混在 | PRG-27 |

## 表 PRG-A5: EXPカーブ・上限一覧

| スキル | formula | max_level | Lv100 累積EXP(概算) | EXP源 |
|---|---|---|---|---|
| SMITHING | `(%level% + 75 * 2^(%level%/7.6)) + 300` | 100 | 指数 | 耐久消耗(PRG-13)・鍛冶クラフト |
| ENCHANTING | 同上 | 100 | 指数 | エンチャント実行 |
| ALCHEMY | 同上 | 100 | 指数 | 醸造 |
| MINING | 同上 | 100 | 指数 | 鉱石破壊 |
| WOODCUTTING | 同上 | 100 | 指数 | 原木破壊 |
| DIGGING | 同上 | 100 | 指数 | 土系破壊 |
| FARMING | 同上 | 100 | 指数 | 収穫 |
| FISHING | 同上 | 100 | 指数 | 釣り |
| ARCHERY | 同上 | 100 | 指数 | 弓ダメージ |
| LIGHT_WEAPONS | 同上 | 100 | 指数 | 近接ダメージ |
| HEAVY_WEAPONS | 同上 | 100 | 指数 | 近接ダメージ |
| LIGHT_ARMOR | 同上 | 100 | 指数 | 被弾(`expAllowedInWorld` 適用) |
| HEAVY_ARMOR | 同上 | 100 | 指数 | 被弾(同上) |
| ARS_MAGIC | 同上 | 100 | 指数 | 呪文詠唱 |
| ARS_SMITHING | 同上 | 100 | 指数 | 儀式/魔導クラフト |
| POWER | `(%level%/100) * 1800 + 800` | 256 | 線形 | **他スキルのレベルアップのみ**(`exp_gain: 100`) |

15スキルが完全に同一カーブであるため、スキルごとの難度差が「EXP源の湧きやすさ」だけに依存する。採掘/整地(1ブロック=1EXP級)と鍛冶(耐久1消耗=0.01EXP級)では到達速度が2桁違う。

## 表 PRG-A6: ツリー別ノード数とSPコスト

| ツリー | ノード数 | 総コスト | 排他グループ構成 |
|---|---|---|---|
| alchemy.yml | 15 | 15 | B-greek 2分岐(親子チェーン・正常) |
| archery.yml | 24 | 24 | A〜E-greek 各3兄弟(**tier独立 → PRG-04**) |
| ars_magic.yml | 24 | 24 | なし |
| ars_smithing.yml | 20 | 20 | C-greek 2分岐(正常) |
| digging.yml | 12 | 12 | なし |
| enchanting.yml | 15 | 15 | A-greek 2分岐(正常) |
| farming.yml | 14 | 14 | A-greek 2分岐(正常)+合流1 |
| fishing.yml | 13 | 13 | B-greek 2分岐×3段(正常) |
| heavy_armor.yml | 24 | 24 | A〜E-greek 各3兄弟(**PRG-04**) |
| heavy_weapons.yml | 24 | 24 | A〜E-greek 各3兄弟(**PRG-04**) |
| light_armor.yml | 24 | 24 | A〜E-greek 各3兄弟(**PRG-04**) |
| light_weapons.yml | 24 | 24 | A〜E-greek 各3兄弟(**PRG-04**) |
| mining.yml | 12 | 12 | なし |
| power.yml | 20 | 20 | なし |
| smithing.yml | 17 | 17 | B-greek 2分岐(正常)+合流1 |
| woodcutting.yml | 13 | 13 | なし |
| **合計** | **295** | **295** | — |

到達可能性: 前提親(`parent` / `parents-any`)の閉路は全16ツリーで0件、到達不能ノードも0件を確認。問題はコスト側(PRG-08)と排他側(PRG-04)。

---

## 検証済みで所見なしの項目

- fork 側ゲート配線 3系統はすべて生存: `UsageGate.java:81`(glyph) / `UnlockGate.java:93,110`(recipe) / `UnlockGate.java:122`(ritual) が `TrinityForgeBridge.tf*GatePerks()` を実際に参照している。
- `DedicatedEffectsConfig.normalize()` の後方互換(ベアID → `feature:<id>` 書き換え)は正しく、リスナー側 `isActive(player, "break-vanilla-exp")` は `feature:break-vanilla-exp` に一致する。PRG-01 は配線ではなく配置の問題。
- 全16ツリーで使用されている buffs / mainhand-buffs / multipliers のキーは、すべて `StatVocabulary` に登録済み(未知キーによる無言ドロップは0件)。
- `SkillTreeConfig.validateUniqueDedicatedEffects` の除外(`ars-tier` と全 `feature:`)は意図通りで、複数ノードへの feature 重複配置(`furnace-smelt-bonus` ×3 等)を正しく許容している。
- ノード前提の閉路・到達不能は0件。
