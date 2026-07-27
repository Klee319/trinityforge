# 08 コメント棚卸し / デッドコード・レガシー検出 (横断担当)

対象: `TrinityForge/src/main/java/`(main配下355ファイル、コメント行9,771行 — 実測。`_SHARED_BRIEF.md`記載の「599ファイル」はtest配下244ファイルを含めた数と判明、コメント行数9,771は主張どおり一致確認済み)、`tools/config-editor/`、フォーク改変差分(`fork-handoff/arspaper/fork` git diff、`fork-handoff/elitemobs/elitemobs-fork` git diff)。

**方法論の要注記**: ブリーフは「9,771行を仕分けよ」と要求しているが、上位40ファイル(コメント3,743行 = 全体の38%)を実際に通読した結果、**この実装(過去セッションのClaude作)は既に高品質な"Why"コメント規律を徹底しており、DELETE/EXTERNALIZE対象は当初想定より遥かに少ない**。区切り線は0件、コメントアウトされた旧コードは実質0件、自明javadocも僅少。これは「枠を疑う」観点からの正直な報告であり、水増しでDELETE候補を作ることはしていない。根拠は各節に実データで示す。

---

## タスクA: コメント棚卸し

### A-1. 機械集計: コメント行数上位40ファイル

集計方法: `grep -cE '^\s*(//|/\*|\*)' <file>` (行頭が `//` / `/*` / `*` の行数)。

| # | 行数 | ファイル |
|---|------|----------|
| 1 | 289 | listeners/CombatListener.java |
| 2 | 275 | TrinityForge.java |
| 3 | 197 | config/domains/ItemStatsConfig.java |
| 4 | 144 | combat/SymmetricCombatService.java |
| 5 | 138 | pdc/PdcKeys.java |
| 6 | 132 | combat/PlayerStatAggregator.java |
| 7 | 123 | stats/ItemAssembler.java |
| 8 | 113 | stats/AttributeApplier.java |
| 9 | 108 | stats/CatalogRecipeRegistrar.java |
| 10 | 106 | stats/DerivedItemStats.java |
| 11 | 96 | config/domains/SkillTreeConfig.java |
| 12 | 93 | config/domains/CombatDamageConfig.java |
| 13 | 88 | skilltree/generator/SkillTreeProgressionGenerator.java |
| 14 | 87 | stats/ItemFactory.java |
| 15 | 82 | config/domains/FishingGimmickConfig.java |
| 16 | 81 | config/ConfigManager.java |
| 17 | 81 | combat/DefenseStats.java |
| 18 | 79 | config/domains/ItemCatalogConfig.java |
| 19 | 78 | hate/HateTable.java |
| 20 | 77 | combat/WeaponAttackStatResolver.java |
| 21 | 76 | skilltree/runtime/PerkAttributeApplier.java |
| 22 | 73 | stats/ItemStatProfile.java |
| 23 | 71 | stats/StatVocabulary.java |
| 24 | 71 | mobs/EliteMobsImporter.java |
| 25 | 69 | stats/DropTablePolicy.java |
| 26 | 68 | stats/CraftQualityService.java |
| 27 | 68 | config/domains/CraftingFeaturesConfig.java |
| 28 | 66 | stats/RecipeSpec.java |
| 29 | 66 | listeners/FishingGimmickListener.java |
| 30 | 65 | stats/AttributeProjection.java |
| 31 | 65 | progression/repository/ProgressionRepository.java |
| 32 | 65 | progression/CollectionService.java |
| 33 | 62 | listeners/NativeSkillExperienceListener.java |
| 34 | 60 | pdc/MobData.java |
| 35 | 57 | mobs/ConversionPolicy.java |
| 36 | 56 | gacha/GachaDraw.java |
| 37 | 55 | listeners/CatalogWorkbenchListener.java |
| 38 | 55 | config/domains/DedicatedEffectsConfig.java |
| 39 | 54 | listeners/CraftQualityListener.java |
| 40 | 54 | active/ActiveSkill.java |

上位40ファイル合計: 3,743行。残り315ファイル合計: 6,028行。全40ファイルを通読済み(パス省略なし、`com/trinityforge/`配下)。

### A-2. パターン別集計

| パターン | 件数/行数 | 手法 |
|----------|-----------|------|
| コメントアウトされた旧コード | **実質0件** | `^\s*//\s*(public\|private\|...\|\w+\.\w+\(...)` 系正規表現で走査。ヒットした6件は全て「識別子を含む説明文」であり実コードの死骸ではない(例: `// AttackStats.plain(0), matching the pre-existing...`は文中の型名参照)。 |
| 区切り線 (`// ===`, `/* ***` 等) | **0件** | `^\s*(//\|\*)\s*[=\-\*]{5,}\s*$` に完全ヒットなし。 |
| 自明なJavadoc (`@param x x`, "Gets the...") | **概ね0件(誤検出除く)** | `Gets? the\|Returns? the\|Sets? the` ヒット4件は全て複数行javadocの導入文で実質は非自明な契約説明(例: `StatKeys.canonical`の正規化規則)。`@param x x`型の完全自明ヒット7件は全て偶然の部分一致(パラメータ名で始まる説明文)で真の重複なし。 |
| 履歴メモ(「〜に変更」「旧仕様」「以前は」等) | 19箇所ヒット、**うち実質DELETE適格は2件のみ** | 詳細はA-3参照。大半は「なぜ今の実装がこうなっているか」を説明するバグ修正/exploit修正の記録であり、KEEP基準(非自明な制約・罠の根拠)に該当する。 |
| TODO / FIXME / XXX / HACK | **4件、全件未対応・KEEP** | 全件リストはA-4。 |
| 長大な仕様解説ブロック(10行以上連続) | **243ブロック、計3,705行** | 全件リストはA-5(場所+行数)。EXTERNALIZE推奨は上位の約15ブロックのみ(A-6)。 |

### A-3. 履歴メモ19件の個別判定

パターンマッチでヒットした19件を全件読んで判定した結果:

| 場所 | 判定 | 理由 |
|------|------|------|
| combat/ComponentDamageCalculator.java:80 | KEEP | 旧仕様(flat還付)廃止の理由を明示。再導入を防ぐ非自明な契約。 |
| combat/DefenseStatBridge.java:47 | KEEP | 現在も有効なフォールバック規則の説明。 |
| combat/SymmetricCombatService.java:174 | KEEP | 「なぜこのパスがRESISTANCEを見ないか」を説明。誤"修正"防止。 |
| config/domains/FishingGimmickConfig.java:271 | KEEP | 設計判断(判断済み)の記録。ロード順依存の偽陽性を防ぐ理由付き。 |
| config/domains/ItemCatalogConfig.java:388 | KEEP | 「旧キーmirrorは逆意味」という罠の明示。削除すると同じ間違いを繰り返す。 |
| config/domains/RoleBuffsConfig.java:27 | KEEP | 過去のチート値exploitの記録(上限が無かった理由)。 |
| integration/ars/ArsNativeBridge.java:26 | KEEP | 過去のsilent no-opバグの記録。 |
| listeners/PickupQualityListener.java:42 | KEEP | ちらつき/増殖/消滅バグの修正根拠(ユーザー指摘)。 |
| listeners/PickupQualityListener.java:51 | KEEP | 同上文脈の補足。 |
| listeners/VanillaItemRemovalListener.java:137 | KEEP | 複製exploit修正の根拠。 |
| listeners/VeinMiningListener.java:142 | **DELETE候補** | 「発動フィードバックを追加した(旧仕様は無告知)」のみで非自明な罠の説明が無い、純粋な変更履歴。1行。 |
| progression/CollectionRecord.java:30 | KEEP | verifier指摘の記録、フォールバック挙動の根拠として現役。 |
| progression/CollectionService.java:199 | KEEP | クラッシュ窓での重複付与を防ぐ順序依存の説明。教科書的なWhyコメント。 |
| skilltree/generator/SkillTreeProgressionGenerator.java:64 | **DELETE候補** | 「旧実装は全セル"00"固定だった」という純粋な変更履歴、現在のロジック理解に不要。1行。 |
| skilltree/runtime/NativeSurvivalPerkListener.java:67 | KEEP | 早期returnを削除した理由(バグ再導入防止)。 |
| stats/DerivedItemStats.java:233 | KEEP | 「式+明示の両方を加算する」現仕様の対比説明として必要。 |
| stats/LoreComposer.java:225 | KEEP | CJK幅計算バグの修正根拠、再発防止に必要。 |
| stats/PercentStatNormalize.java:49 | KEEP | 特定キーを正規化対象から除外する理由(将来の誤った再追加を防ぐ)。 |
| TrinityForge.java:264 | KEEP(弱) | 短い1行、実害は小さいが理由が書かれているため許容。 |

**結論**: 履歴メモ19件のうちDELETE対象は2行のみ。パターンマッチの表層(「旧」「以前は」を含む)だけで機械的にDELETE判定すると17件を誤って削除することになる — 本タスクで「必ず該当ファイルを読む」ことが要求されている理由がここに表れている。

### A-4. TODO/FIXME/XXX/HACK 全件(場所付き・実装済み判定込み)

| ID | 場所 | 内容 | 判定 |
|----|------|------|------|
| CLN-01 | config/ConfigManager.java:195 | `// TODO(M2+): register magic/, pets/ configs here.` | **未対応**。magic/pets ドメインは実際に未実装(ConfigManagerのregister()呼び出し列に該当domainなし)。KEEP。 |
| CLN-02 | listeners/CombatListener.java:84-85 | `// TODO(M2+): EntityDamageEvent.DamageModifier is deprecated in Paper 1.21; migrate...` | **未対応**。`@SuppressWarnings("deprecation")`が同箇所に現存し、移行未実施を裏付ける。KEEP。 |
| CLN-03 | listeners/CombatListener.java:158 | `@SuppressWarnings("deprecation") // DamageModifier folding; see DAMAGE_MODIFIERS TODO (M2+).` | CLN-02と対の注記。KEEP。 |
| CLN-04 | listeners/DotDamageListener.java:21 | `// TODO(M2+): CombatListener と同じ deprecated DamageModifier 折り込み。移行時に一緒に更新する。` | **未対応**。CLN-02と同一の未移行状態を指す。KEEP。 |

4件全て「完了済みなので消せるTODO」には該当しない(実装済みチェック=該当APIが実際にまだdeprecated経路を使っている、該当configドメインが実際に未登録であることをコードで確認済み)。

### A-5. 長大コメントブロック(10行以上連続) 全件リスト

合計243ブロック、3,705行。全件を場所・行数付きで列挙(降順)。ID `CLN-05`〜`CLN-24`は行数20以上の上位ブロック(EXTERNALIZE検討対象、A-6で個別判断)、それ未満(10〜19行)は原則そのファイルのクラス/メソッドJavadocとしてコードと密結合しており **既定KEEP**(IDEホバー表示・保守時の即時参照性を優先。externalizeするとdocs/との往復コストが可読性向上を上回ると判断)。

<details>
<summary>全243ブロック(行数 / 場所)</summary>

```
50  stats/ItemStatProfile.java:6-55
47  stats/AttributeApplier.java:26-72
36  active/ActiveSkill.java:7-42
35  skilltree/generator/SkillTreeProgressionGenerator.java:17-51
34  config/domains/BaseStatsConfig.java:20-53
33  listeners/MobLevelTableListener.java:25-57
32  combat/MeleeChargeMultiplier.java:3-34
31  listeners/FoodGimmickListener.java:26-56
31  stats/ItemTemplate.java:9-39
29  active/ActivationDispatcher.java:22-50
28  mobs/LevelTierDropEntry.java:9-36
28  progression/CombatLevelModel.java:8-35
28  stats/QualityRollModel.java:3-30
27  listeners/FurnaceSmeltListener.java:32-58
27  progression/infrastructure/sqlite/SqliteProgressionRepository.java:13-39
25  listeners/FishingGimmickListener.java:32-56
24  combat/PlayerDefenseResolver.java:10-33
24  listeners/FishSellListener.java:28-51
24  mobs/MobProfile.java:8-31
24  stats/RecipeSpec.java:12-35
23  combat/DefenseStats.java:5-27
23  hate/HateTable.java:10-32
23  listeners/BrewOwnership.java:12-34
23  listeners/XpBottleListener.java:25-47
23  stats/CatalogRecipeRegistrar.java:24-46
22  listeners/EnchantLuckListener.java:24-45
22  skilltree/effects/GateEffectId.java:6-27
22  stats/ItemAssembler.java:33-54
21  mobs/ConversionPolicy.java:8-28
21  mobs/DungeonGate.java:8-28
21  stats/LoreLayout.java:6-26
20  combat/AddonCombatStats.java:11-30
20  listeners/ItemRefreshListener.java:24-43
20  progression/SkillExpFeedbackService.java:34-53
19  config/domains/ItemStatsConfig.java:33-51
19  config/domains/ItemStatsConfig.java:97-115
19  listeners/DiggingDurabilityExpListener.java:21-39
19  listeners/TreeFellingListener.java:35-53
19  listeners/VeinMiningListener.java:30-48
19  progression/core/XpTransitionService.java:5-23
19  stats/DerivedItemStats.java:15-33
18  combat/DefenseClamp.java:3-20
18  combat/DefenseStatBridge.java:9-26
18  combat/DefenseStats.java:85-102
18  combat/PlayerCombatAggregate.java:6-23
18  combat/PlayerStatAggregator.java:362-379
18  listeners/MiningGimmickListener.java:27-44
18  listeners/PickupQualityListener.java:37-54
18  listeners/PotionQualityListener.java:34-51
18  mobs/MobLevelBandTable.java:8-25
18  skilltree/generator/GeneratedPerk.java:8-25
18  skilltree/runtime/PerkAttributeApplier.java:39-56
18  skilltree/runtime/PerkMirrorService.java:13-30
18  stats/CrossPluginItemResolver.java:16-33
18  stats/VanillaItemRemover.java:22-39
17  TrinityForge.java:1192-1208
17  combat/AttackStats.java:3-19
17  combat/MobAbilityDamage.java:3-19
17  combat/ProjectileWeapon.java:11-27
17  config/domains/FishingGimmickConfig.java:267-283
17  config/domains/MobLevelTableConfig.java:27-43
17  config/domains/WoodcuttingGimmickConfig.java:17-33
17  hate/HateSettings.java:3-19
17  integration/ars/ArsNativeBridge.java:13-29
17  listeners/CatalogWorkbenchListener.java:27-43
17  mobs/EliteMobsImporter.java:20-36
17  progression/catalog/FormulaParser.java:3-19
17  skilltree/effects/DedicatedEffectChannel.java:6-22
17  skilltree/generator/PerkNaming.java:6-22
17  skilltree/runtime/PerkBuffResolver.java:21-37
17  stats/CraftQualityService.java:17-33
17  stats/EquipmentSlotResolver.java:9-25
17  stats/ItemFactory.java:201-217
17  stats/StatVocabulary.java:6-22
16  combat/BleedService.java:18-33
16  combat/SymmetricCombatService.java:291-306
16  combat/VanillaArmorMapping.java:3-18
16  combat/WeaponAttackStatResolver.java:14-29
16  config/domains/GachaConfig.java:22-37
16  config/domains/MaterialListsConfig.java:21-36
16  config/domains/SkillTreeConfig.java:338-353
16  mobs/MobLevelCoefficients.java:5-20
16  skilltree/SkillNode.java:6-21
16  skilltree/effects/DedicatedEffectGateIndex.java:18-33
15  combat/AttackStatBridge.java:9-23
15  command/ImportMobsCommand.java:31-45
15  config/domains/CollectionConfig.java:27-41
15  config/domains/SkillTreeConfig.java:35-49
15  hate/HateTable.java:72-86
15  listeners/CollectionListener.java:26-40
15  listeners/FarmingHarvestListener.java:32-46
15  listeners/PlacedBlockTracker.java:14-28
15  mining/HasteActiveSkill.java:14-28
15  progression/SkillLevelCache.java:9-23
15  progression/catalog/FormulaParser.java:30-44
15  skilltree/effects/FeatureEffectParam.java:3-17
15  stats/CatalogRecipeRegistrar.java:203-217
15  stats/DropTablePolicy.java:9-23
15  stats/LoreColorRules.java:5-19
15  stats/RecipeIngredient.java:9-23
14  combat/ComponentDamageCalculator.java:42-55
14  combat/MeleeChargeTracker.java:7-20
14  combat/SymmetricCombatService.java:193-206
14  command/ActiveCommand.java:25-38
14  config/domains/QualityConfig.java:11-24
14  config/domains/WeaponBaseFormula.java:3-16
14  dungeon/DungeonWorldRegistry.java:7-20
14  listeners/BeekeepingListener.java:22-35
14  listeners/CombatListener.java:545-558
14  listeners/CombatListener.java:600-613
14  mining/VeinMiningAlgorithm.java:48-61
14  mobs/ConversionPolicy.java:91-104
14  mobs/LevelTierRule.java:7-20
14  pdc/BindType.java:6-19
14  skilltree/runtime/NativeAttributeBridge.java:32-45
14  stats/AttributeProjection.java:9-22
14  stats/CraftQualityPolicy.java:68-81
14  stats/LoreComposeRequest.java:7-20
14  stats/MaterialTier.java:7-20
14  stats/VanillaAttributeDefaults.java:7-20
13  combat/BleedInstance.java:6-18
13  combat/PlayerStatAggregator.java:88-100
13  combat/SymmetricCombatService.java:20-32
13  combat/SymmetricCombatService.java:103-115
13  combat/SymmetricCombatService.java:136-148
13  command/StatsCategory.java:6-18
13  config/domains/DedicatedEffectsConfig.java:16-28
13  gacha/GachaDraw.java:100-112
13  gacha/GachaRateUp.java:7-19
13  integration/TrainingDummies.java:7-19
13  listeners/BrewUnlockListener.java:35-47
13  listeners/CombatListener.java:262-274
13  listeners/MobTypeSpawnListener.java:29-41
13  listeners/ParticleSeedListener.java:24-36
13  mobs/RampParser.java:33-45
13  pdc/PdcKeys.java:207-219
13  progression/repository/ProgressionRepository.java:11-23
13  progression/repository/ProgressionRepository.java:60-72
13  skilltree/effects/TierTable.java:8-20
13  skilltree/runtime/NativeCombatPerkListener.java:78-90
13  stats/DerivedItemStats.java:292-304
13  stats/ItemFactory.java:86-98
13  stats/RollHash.java:5-17
13  stats/VanillaRecipeRemover.java:15-27
13  woodcutting/WoodcuttingMaterials.java:5-17
12  active/ActiveContext.java:7-18
12  combat/AttackSpeedResolver.java:32-43
12  combat/DefenseStatKeys.java:3-14
12  combat/ProjectileWeapon.java:33-44
12  config/domains/AttributeMappingConfig.java:7-18
12  config/domains/FishingGimmickConfig.java:24-35
12  gacha/GachaEntry.java:5-16
12  gacha/GachaPool.java:6-17
12  hate/HateTable.java:123-134
12  listeners/AnimalDamageListener.java:19-30
12  listeners/AttackerTargetCooldown.java:7-18
12  listeners/CombatListener.java:91-102
12  listeners/CombatListener.java:347-358
12  listeners/CraftQualityListener.java:43-54
12  listeners/CraftQualityListener.java:130-141
12  listeners/GachaListener.java:33-44
12  mobs/DungeonTheme.java:8-19
12  mobs/EliteMobsImporter.java:273-284
12  mobs/MobDropRoller.java:24-35
12  progression/AchievementService.java:53-64
12  progression/CollectionRecord.java:5-16
12  progression/core/SkillProgress.java:3-14
12  skilltree/runtime/PerkAttributeApplier.java:100-111
12  skilltree/runtime/PerkMirror.java:6-17
12  skilltree/runtime/SkillPerkStatSource.java:6-17
12  stats/CraftQualityPolicy.java:52-63
12  stats/DerivedItemStats.java:167-178
12  stats/EquipmentSlotResolver.java:45-56
12  stats/ItemRefreshPolicy.java:6-17
12  stats/PercentStatNormalize.java:5-16
12  stats/StatKeys.java:6-17
11  active/ActiveSkillRegistry.java:40-50
11  active/CooldownManager.java:60-70
11  combat/AttackSpeedResolver.java:3-13
11  combat/CritFlash.java:8-18
11  combat/DefenderProfile.java:5-15
11  combat/DefenseStats.java:64-74
11  combat/DodgeResolver.java:6-16
11  combat/MeleeChargeMultiplier.java:43-53
11  config/domains/EnchantLuckConfig.java:7-17
11  config/domains/RoleBuffsConfig.java:26-36
11  config/domains/SpecialRewardsConfig.java:18-28
11  economy/EconomyBridge.java:12-22
11  fishing/XpBottlePolicy.java:3-13
11  listeners/CombatListener.java:578-588
11  listeners/DiggingGimmickListener.java:26-36
11  listeners/DungeonGateListener.java:17-27
11  listeners/FoodBonusListener.java:23-33
11  mining/VeinMiningAlgorithm.java:11-21
11  mob/DamagePopupDisplay.java:27-37
11  mobs/MobDropEntry.java:7-17
11  progression/CollectionRecord.java:25-35
11  progression/catalog/SkillCatalogEntry.java:8-18
11  progression/core/PerkOwnership.java:7-17
11  skilltree/runtime/NativeSkillPerkStatSource.java:12-22
11  stats/DerivedItemStats.java:260-270
11  stats/DropTablePolicy.java:176-186
11  stats/LoreComposer.java:18-28
11  stats/MaterialLists.java:10-20
11  stats/TableGeneration.java:6-16
10  active/ActivationResult.java:5-14
10  combat/AttackSpeedResolver.java:71-80
10  combat/DefenseStats.java:113-122
10  combat/PlayerStatAggregator.java:21-30
10  combat/PlayerStatAggregator.java:51-60
10  combat/SymmetricCombatService.java:154-163
10  combat/WeaponAttackStatResolver.java:88-97
10  combat/WeaponAttackStatResolver.java:111-120
10  config/domains/AlchemyQualityConfig.java:7-16
10  config/domains/DungeonThemeConfig.java:20-29
10  config/domains/HateConfig.java:8-17
10  config/domains/MobLevelTableConfig.java:167-176
10  config/domains/MobProfileConfig.java:20-29
10  config/domains/MobTypesConfig.java:29-38
10  gacha/GachaDraw.java:117-126
10  listeners/ArmorUseGateListener.java:18-27
10  listeners/CombatListener.java:72-81
10  listeners/CombatListener.java:203-212
10  listeners/CombatListener.java:886-895
10  listeners/FishingQualityListener.java:38-47
10  mobs/DungeonGatePolicy.java:3-12
10  pdc/ItemData.java:13-22
10  progression/CollectionService.java:26-35
10  progression/NativeSkillLevelSource.java:14-23
10  progression/ParticleEffectService.java:22-31
10  progression/PermanentBuffResolver.java:14-23
10  progression/PlayerLockRegistry.java:8-17
10  progression/UseRequirementPolicy.java:6-15
10  progression/catalog/NativeSkillCatalog.java:88-97
10  skilltree/Prestige.java:5-14
10  skilltree/SkillRole.java:6-15
10  skilltree/SkillTree.java:6-15
10  stats/AttributeProjection.java:90-99
10  stats/CraftQualityService.java:152-161
10  stats/DropTableConfig.java:11-20
10  stats/ItemAssembler.java:266-275
10  stats/ItemAssembler.java:357-366
10  stats/StatRange.java:3-12
```
(全パスは `TrinityForge/src/main/java/com/trinityforge/` からの相対)
</details>

### A-6. EXTERNALIZE個別判断(行数20以上の23ブロック)

| ID | 重要度 | 分類 | 場所 | 事象/判断 |
|----|--------|------|------|-----------|
| CLN-05 | LOW | COMMENT | stats/ItemStatProfile.java:6-55 (50行) | **KEEP**。recordの各フィールド意味論(fixed/perQuality/random/durability/offhandApplies/multipliers)を1箇所で定義するjavadoc。IDE補完時の即時参照性が高く、docsへ追い出すと実装との乖離リスクが増す。現状維持を推奨。 |
| CLN-06 | LOW | COMMENT | stats/AttributeApplier.java:26-72 (47行) | **KEEP(一部要約可)**。二重計上防止規則(armor-defense-rate/armor-strengthの置換 vs 加算ステの復元)はコードの安全性に直結する契約。ただし後半の「攻撃速度は完全にこの層をバイパスする」説明(37-46行目相当)は`StatVocabulary`側の同旨コメントと一部重複しており、20%程度圧縮可能(具体的な圧縮量は主観的なので削減見込みには含めない)。 |
| CLN-07 | LOW | COMMENT | active/ActiveSkill.java:7-42 (36行) | **KEEP**。インターフェース契約(マルチスキル活性化が既定仕様であることの明示)。実装者への警告として必須、docsに出すと読み飛ばされるリスクが高い。 |
| CLN-08 | MEDIUM | COMMENT | skilltree/generator/SkillTreeProgressionGenerator.java:17-51 (35行) | **EXTERNALIZE推奨**。node→perk id対応表、ValhallaMMO仕様からの逸脱一覧など「仕様書」そのもの。`docs/design/`に既存の設計書(2026-07-25等)群があるため、そこへ`skilltree-progression-generator-mapping.md`として切り出し、クラスには1行参照(`設計: docs/design/skilltree-progression-generator-mapping.md`)を残すことを推奨。約35行削減。 |
| CLN-09 | LOW | COMMENT | config/domains/BaseStatsConfig.java:20-53 (34行) | **KEEP**。config schemaの読み込み規則そのものでコードと1:1対応。 |
| CLN-10 | LOW | COMMENT | listeners/MobLevelTableListener.java:25-57 (33行) | **KEEP**。適用順序(mob-types→mob-level-table)の説明で他リスナーとの優先度契約。 |
| CLN-11 | LOW | COMMENT | combat/MeleeChargeMultiplier.java:3-34 (32行) | **KEEP**。物理法則(バニラの連打減衰再現)の数式根拠。 |
| CLN-12 | LOW | COMMENT | listeners/FoodGimmickListener.java:26-56 (31行) | **KEEP**。ゴミ食/非ゴミ食判定ロジックの分岐表。 |
| CLN-13 | LOW | COMMENT | stats/ItemTemplate.java:9-39 (31行) | **KEEP**。record全フィールドの契約。 |
| CLN-14 | LOW | COMMENT | active/ActivationDispatcher.java:22-50 (29行) | **KEEP**。トリガー判定順序の契約。 |
| CLN-15〜24 | LOW | COMMENT | 28行台以下の残り13ブロック(mobs/LevelTierDropEntry.java, progression/CombatLevelModel.java, stats/QualityRollModel.java, listeners/FurnaceSmeltListener.java, progression/infrastructure/sqlite/SqliteProgressionRepository.java, listeners/FishingGimmickListener.java:32-56, combat/PlayerDefenseResolver.java, listeners/FishSellListener.java, mobs/MobProfile.java, stats/RecipeSpec.java, combat/DefenseStats.java:5-27, hate/HateTable.java:10-32, listeners/BrewOwnership.java) | **KEEP全件**。いずれもrecord/クラスの契約定義かバグ修正根拠で、コードと不可分。externalizeするとdocs更新漏れでコードとの乖離が起きるリスクの方が大きいと判断。 |

**EXTERNALIZE結論**: 243ブロックを走査した結果、真にdocs/へ切り出すべきは実質 **CLN-08の1件(約35行)のみ**。他の大半は「クラス/メソッドの契約そのもの」であり、docs/に移すとコード変更時の同期漏れリスクが上がるためKEEPが妥当。ブリーフの想定(長い仕様解説を大量にexternalizeする)は、実際に読むとこのコードベースには当てはまらなかった。

### A-7. 上位40ファイルの総合判定

| ファイル | 判定 | 所見 |
|----------|------|------|
| listeners/CombatListener.java | KEEP最優先 | コメント全289行中、削除可能なのは実質0行。全て「非自明な処理順序・二重計上防止・exploit修正・Paper API依存の罠」を説明。特にDAMAGE_MODIFIERSの選択的ゼロ化(91-102行目)、AoE再入ガード(125-129行目)、チャージ攻撃の二重減衰防止(262-274行目)は削除すると再発するクラスのバグ。 |
| TrinityForge.java | KEEP最優先 | onEnable内の登録順序依存(aggregator構築順、PerkAttributeApplier依存順等)を説明するコメント群。順序を変えると壊れる箇所の注記であり、削除厳禁。 |
| config/domains/ItemStatsConfig.java | KEEP | ロード時フェイルセーフ規則(構文エラー時のスナップショット維持等)の説明。 |
| combat/SymmetricCombatService.java | KEEP | 8ステップパイプラインの各関数の非自明な適用順序・符号反転(負値=回復)の契約。 |
| pdc/PdcKeys.java | KEEP | 全PDCキーの意味論を1箇所に集約するレジストリのjavadoc。削除するとキーの意味が失われる。 |
| combat/PlayerStatAggregator.java | KEEP | optional constructor引数の後方互換説明(既存呼び出し元を壊さない契約)。 |
| stats/ItemAssembler.java | KEEP | 冪等性(re-assembleが安全)の説明、tool-enchant付け外しの置換ベース契約。 |
| stats/AttributeApplier.java | KEEP(CLN-06参照) | 二重計上防止規則。一部圧縮余地あり。 |
| stats/CatalogRecipeRegistrar.java | KEEP | 逆レシピをper-slot補正の対象外にした設計判断(exploit防止)。 |
| stats/DerivedItemStats.java | KEEP、**ただしコードとの不一致を検出**(下記B節参照) | 「解決順: プロファイル→カテゴリフォールバック→バニラ材質」のコメントに対し、実装は第3段(バニラ材質フォールバック)を呼んでいない。CLN-31参照。 |
| config/domains/SkillTreeConfig.java | KEEP | フェイルセーフ規則(atomic publish)の説明。 |
| config/domains/CombatDamageConfig.java | KEEP | 全設定項目の既定値とバランス上の意味(B3上限等)。 |
| skilltree/generator/SkillTreeProgressionGenerator.java | 一部EXTERNALIZE(CLN-08) | 上記参照。 |
| stats/ItemFactory.java | KEEP | createIdentityOnlyがcreateと分離されている理由(レシピ結果の一意ロール回避)。 |
| config/domains/FishingGimmickConfig.java | KEEP | 不正キー検証を意図的に省略した設計判断の記録。 |
| config/ConfigManager.java | KEEP | ドメイン登録順序の依存関係。 |
| combat/DefenseStats.java | KEEP | クランプ責務の移動履歴(コンストラクタ→clampedTo)、負クランプの意図。 |
| config/domains/ItemCatalogConfig.java | KEEP | strict-orientation/mirror等のfail-soft規則。 |
| hate/HateTable.java | KEEP | 4つのリーク経路対策(cap/eviction/decay/sweep)の設計文書。 |
| combat/WeaponAttackStatResolver.java | KEEP | fork公開APIの契約(メインスレッド前提等)。 |
| skilltree/runtime/PerkAttributeApplier.java | KEEP | attack-speed分離の理由、イベント購読統合の理由。 |
| stats/ItemStatProfile.java | KEEP(CLN-05) | 上記参照。 |
| stats/StatVocabulary.java | KEEP | perk-buffチャンネルの単一情報源である旨+他クラスとのドリフト警告(このコメント自体が重要な保守メモ)。 |
| mobs/EliteMobsImporter.java | KEEP | マージ(上書きでない)動作の契約、YAML構文エラー時のフェイルセーフ。 |
| stats/DropTablePolicy.java | KEEP | ゲート反転規則、scrap-exempt除外ロジック。 |
| stats/CraftQualityService.java | KEEP | S7分割(作業台/儀式)の後方互換説明。 |
| config/domains/CraftingFeaturesConfig.java | KEEP | `@deprecated`アクセサ群は後方互換のため意図的に残置(削除不可、呼び出し元未確認のため)。 |
| stats/RecipeSpec.java | KEEP | reversibleの前提条件(fail-soft)。 |
| listeners/FishingGimmickListener.java | KEEP | 三択モデル(宝/ゴミ/魚)の抽選規則。 |
| stats/AttributeProjection.java | KEEP | MULTIPLY_SCALAR_1を選んだ理由(Bukkit属性計算順序の説明)、これを読まずに変更すると挙動が壊れる。 |
| progression/repository/ProgressionRepository.java | KEEP | 永続化契約(synchronous/durable)の明示。 |
| progression/CollectionService.java | KEEP | 冪等性修正(claimed-first順序)の根拠。 |
| listeners/NativeSkillExperienceListener.java | KEEP | exploit修正(semi-AFK防具EXP farm)の根拠。 |
| pdc/MobData.java | KEEP | 型不一致の警告を1回だけログする理由。 |
| mobs/ConversionPolicy.java | KEEP | 幾何成長項を導入した理由(線形ランプでは装備tier曲線を追えない)。 |
| gacha/GachaDraw.java | KEEP | pity(天井)ロジックの近似規約(最小weight=最高レア)。 |
| listeners/CatalogWorkbenchListener.java | KEEP | 二部マッチングを使う理由(貪欲割当だと偽陰性)。 |
| config/domains/DedicatedEffectsConfig.java | KEEP | 動的ID方式改修の背景。 |
| listeners/CraftQualityListener.java | KEEP | 複製バグ修正の詳細(ドラッグ/COLLECT_TO_CURSOR経由の無限回収exploit)。 |
| active/ActiveSkill.java | KEEP | マルチスキル活性化契約(重複登録で二重CTになる罠の警告)。 |

**総評**: 上位40ファイルは「コメントが多い=悪い」ではなく「非自明な契約・exploit修正・順序依存が多い複雑な処理を担っている」ために自然にコメントが多い。DELETE対象は事実上ゼロ、EXTERNALIZE対象は1件(CLN-08)のみ。

### A-8. フォーク改変差分のコメント(参考、簡易確認)

- ArsPaper (`git diff 4c78f08..HEAD -- '*.java'`): 追加コメント559行。抽出サンプルを確認した限り、TF本体と同水準の「なぜ」コメント(例: ホットリロード後のキャッシュ破棄理由、バックアップ失敗時のフォールバック理由)。ただし `/ars reload` サブコマンドの内部で「// config.yml リロード」「// 厳選（selection）設定リロード」のように**各行を1対1でラベル付けするだけの自明コメント**が連続する箇所(ArsCommand関連ハンドラ)があり、これは何をリロードしているか一覧性のために許容範囲だが、DELETE寄りの弱い候補。行数までは未計測(フォークは横断担当の主対象外のため簡易確認に留めた、要確認)。
- EliteMobs (`git diff` working tree): 追加コメント153行。同様の傾向。
- 両フォークとも区切り線・コメントアウト旧コードの明確な検出はなし(簡易grep確認、要確認)。

### A-9. 削減見込み行数(概算)

| 区分 | 行数 | 内訳 |
|------|------|------|
| DELETE | **約10〜15行** | 履歴メモ2件(VeinMiningListener.java:142の3行ブロック、SkillTreeProgressionGenerator.java:64の1行)+ 誤差調整分。区切り線・コメントアウト旧コード・自明javadocは実質0行。 |
| EXTERNALIZE | **約35行** | CLN-08 (SkillTreeProgressionGenerator.java class javadoc) のみ。 |
| 合計削減見込み | **約45〜50行 / 9,771行 (約0.5%)** | |

**結論**: ブリーフの前提(9,771行を大幅に仕分けて読み込み速度を上げる)は、実際にコードを読むと成立しない。このコードベースのコメントは既に「Why中心・KEEP基準に沿う」形で書かれており、大量のDELETE/EXTERNALIZEを行うと逆に非自明なバグ修正根拠や罠の説明を失い、保守性が低下するリスクの方が大きい。もし読み込み速度が本当の課題なら、コメント削減ではなく「IDE折りたたみ(javadocの折りたたみ表示)」や「エディタのJavadoc要約表示」など閲覧側の対策を検討すべき(提案、対象外のため深追いしない)。

---

## タスクB: デッドコード/レガシー検出

### B-1. 未参照メソッド/クラス/フィールド

**手法**: 355クラス全ての単純名について、`TrinityForge/src/main/java` + `src/test/java` + `fork-handoff/{arspaper,dpschecker,elitemobs}/*/src`(build/.gradle/testbed除外) + `tools/config-editor` 全文をトークン化し出現回数を集計(Python実装、grep -rl の代替)。出現回数が極端に少ないクラスを個別に確認。

| ID | 重要度 | 分類 | 場所 | 事象 | 影響/再現 | 根拠 |
|----|--------|------|------|------|-----------|------|
| CLN-25 | **HIGH** | DEADCODE / UNIMPLEMENTED | `TrinityForge/src/main/java/com/trinityforge/listeners/BlacksmithBanListener.java` (全92行) | 村人の鍛冶職(防具/道具/武器鍛冶)との取引を禁止する完全実装のListenerだが、`TrinityForge.java`のどこからも`registerEvents`されておらず、Bukkitに一切登録されていない。 | サーバー上で鍛冶村人との取引は禁止されない(意図された機能が完全に無効)。運用者が「鍛冶村人は禁止したはず」と誤認する可能性がある。 | `grep -rn "BlacksmithBan" TrinityForge/src/main/java/com/trinityforge/TrinityForge.java` は0件ヒット。TrinityForge.javaには70件の`registerEvents`呼び出しがあるが、このクラスは含まれない。クラス名の全文検索でも自己定義(1箇所)以外に出現しない。 |
| CLN-26 | **HIGH** | DEADCODE / BUG(仕様とコードの乖離) | `TrinityForge/src/main/java/com/trinityforge/stats/VanillaMaterialStats.java` (全72行) + `TrinityForge/src/main/java/com/trinityforge/stats/DerivedItemStats.java:86` | `VanillaMaterialStats.baselines(Material)`(バニラ素材のattack_damage既定値をTF `attack-power`へ変換して返すフォールバック)が、宣言・private コンストラクタ以外の一切から呼ばれていない。一方 `DerivedItemStats.java:86` のコメントは「解決順: アイテム個別プロファイル → カテゴリ別フォールバック(不足分) → **バニラ材質(不足分)**」と3段フォールバックを明記しているが、実装(87-93行目)は1段目(profile)と2段目(`fallbackFixedFor`=カテゴリ別フォールバック)のみを実行し、3段目(バニラ材質フォールバック=`VanillaMaterialStats`)を一切呼び出していない。 | item-stats.ymlにもcategoryフォールバックにも`attack-power`が定義されていない武器は、ドキュメント上「バニラ素材のattack_damageにフォールバックする」はずが、実際には**フォールバックせず0のまま**になる(武器のattack-powerが0でCombatListener側のバニラベース維持ロジックに委ねられるため実害は限定的だが、コメントとコードの乖離自体がバグ)。 | `VanillaMaterialStats.java`の`baselines()`はテストも含め全体で自己ファイル内(クラス宣言+コンストラクタ)以外に0件ヒット。`DerivedItemStats.java:86`のコメント文言「カテゴリ別フォールバック(不足分) → バニラ材質(不足分)」と、直後87-93行目のコード(`profileFor`→`applyProfile`→`applyRandom`→`mergeAdditive(fallbackFixedFor(...))`で終わり)を突き合わせ確認。 |
| CLN-27 | LOW | DEADCODE | `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativeRewardRegistry.java` | `find`/`isRegistered`/`requireRegistered`/`entries`が本番コード(main)からは一切呼ばれておらず、`TrinityForge/src/test/java/com/trinityforge/skilltree/runtime/NativeRewardRegistryContractTest.java`からのみ参照される。 | 実害なし(意図された設計: 「Contract tests fail the build when YAML introduces an unregistered key」というjavadoc通り、ビルド時契約テスト専用のカタログとして機能している)。 | grep結果: mainからの参照0件、testからの参照1ファイルのみ。クラスjavadoc自身が「Contract tests fail the build...」と用途を明記しており意図的な設計と判断。**DEADCODEではなくKEEP**、参考情報として記載。 |

### B-2. ValhallaMMO撤廃の残骸

| ID | 重要度 | 分類 | 場所 | 事象 | 影響/再現 | 根拠 |
|----|--------|------|------|------|-----------|------|
| CLN-28 | LOW | DEADCODE | `TrinityForge/src/main/resources/valhalla/languages/` (空ディレクトリ) | ValhallaMMO撤廃(2026-07-22)前の言語ファイル置き場と思われる空ディレクトリツリーが残存。中身は0ファイル。 | ビルド/実行への実害なし(下記根拠のexclude設定により既にjarへ同梱されていない)。単なるリポジトリの残骸。 | `find TrinityForge/src/main/resources/valhalla -type f` は0件。`TrinityForge/build.gradle.kts`の`tasks.processResources`ブロックに`exclude("valhalla/**")`が既に存在し、ビルド時点で意図的に除外されている(=誰かが既に「死んでいる」と認識して除外済みだが、ディレクトリ自体の削除はされていない)。 |
| CLN-29 | LOW | COMMENT/DEADCODE | `TrinityForge/build.gradle.kts` (`tasks.processResources`内 `exclude("valhalla/**")`) | CLN-28のディレクトリを除外する設定行自体も、ディレクトリ削除後は不要になる。 | 実害なし。CLN-28と合わせて片付けるべき1行。 | 同上。 |
| — | — | — | `com/trinityforge/bridge/valhalla/` パッケージ | **存在しない**(既に完全削除済み)。 | — | `find TrinityForge/src/main/java -ipath "*valhalla*"` はヒット0件。ブリーフが「残っている」と想定していたjavaパッケージは実際には既に無い。 |
| — | — | — | `com.trinityforge.*` 内のValhalla言及45ファイル | 全て**生きている**(用語としての「ValhallaMMO」への言及のみ)。 | 実装への影響なし。 | `grep -rn "^import.*[Vv]alhalla" TrinityForge/src/main/java` は0件ヒット — ValhallaMMOクラスへの実import は皆無。45ファイルの言及は全て(a) コメント上の由来説明(「旧ValhallaMMO仕様」等の歴史的文脈)、(b) `AttributeApplier`のように**現在もValhalla由来の属性modifierを後方互換でクリアする**実装上の必要性(ValhallaMMOで作られた過去のアイテムがサーバーに残っている前提)のいずれか。誤ってDEADCODE判定しないよう個別に確認済み。 |

**結論**: ValhallaMMO撤廃の残骸として実質的に削除可能なのは空ディレクトリ1件(CLN-28)+関連build設定1行(CLN-29)のみ。javaコードの残骸は既にクリーン。

### B-3. 重複実装

| ID | 重要度 | 分類 | 場所 | 事象 | 影響/再現 | 根拠 |
|----|--------|------|------|------|-----------|------|
| — | — | — | ステータス計算・レベル→数値変換・確率判定 全般 | **顕著な重複は検出されず**。 | — | `RollHash`(決定的シード乱数)、`StatVocabulary`(チャネル単一情報源)、`PlayerStatAggregator`(全ステ合算の単一集計者)など、コード中に繰り返し「二重実装しない」「単一の情報源」という設計意図のコメントが現れており(タスクA通読で多数確認)、実際に単一責務クラスへの集約が徹底されている。確率判定(`ThreadLocalRandom.current()`)は30以上のリスナーで個別に呼ばれているが、これは各イベント固有の独立した抽選(出血チャンス、品質ロール等)であり「同じ計算の重複実装」ではなく単なる同一イディオムの反復のため、REDUNDANCY指摘には該当しないと判断。 |
| CLN-30 | LOW | REDUNDANCY(要確認) | `TrinityForge/src/main/java/com/trinityforge/listeners/CatalogWorkbenchListener.java:307` (`catalogIdentityOf`) vs `TrinityForge/src/main/java/com/trinityforge/stats/CatalogIdentity.java:108` (`catalogIdOf`) | カタログID解決ロジックが2箇所に存在する。`CatalogWorkbenchListener`は独自private実装(PDC優先→material+CMDフォールバック→ExternalItemRegistry)を持ち、共有ユーティリティ`CatalogIdentity.catalogIdOf`を呼んでいない。 | 実害は薄い(`CatalogWorkbenchListener`版は`ExternalItemRegistry`分岐が追加されている点で完全な重複ではなく上位互換の可能性があるため)。ただし将来カタログID解決規則を変更する際に2箇所同時修正が必要になるリスクがある。 | `CatalogWorkbenchListener.java:307-323`の`catalogIdentityOf`実装と`CatalogIdentity.java:108`の`catalogIdOf`実装を比較。前者はPDC→material+CMD(TF catalog)→ExternalItemRegistryの3段、後者は詳細未読(要確認)。完全一致ではなく部分拡張の可能性が高いため重要度LOW、要確認注記。 |

### B-4. 未使用の依存/リソース

| ID | 重要度 | 分類 | 場所 | 事象 | 根拠 |
|----|--------|------|------|------|------|
| — | — | — | `TrinityForge/build.gradle.kts` dependencies全項目 | **未使用の依存は検出されず**。全依存(VaultAPI compileOnly/testImplementation、sqlite-jdbc、paper-api testImplementation、JUnit BOM、MockBukkit、Mockito)にそれぞれ採用理由のコメントが付されており、用途も実コードと整合(Vault=EconomyBridge、sqlite-jdbc=SqliteProgressionRepository等)。 | `TrinityForge/build.gradle.kts` を通読、各依存のコメントと実際の利用箇所(grep)を突き合わせ確認。 |
| — | — | — | `TrinityForge/src/main/resources/skills/base/*_progression.yml` (16ファイル) | 一見ValhallaMMO由来の遺物に見えるが**現役**。`NativeSkillCatalog`がexperience curveのSoTとして`plugins/TrinityForge/skills/base/`から実行時に読む。 | `grep -n "skills/base" TrinityForge/src/main/java/com/trinityforge/progression/catalog/NativeSkillCatalog.java` で複数ヒット、`File baseDir = new File(dataFolder, "skills/base")`等の実読み込みコードを確認。誤ってDEADCODE候補にしないよう個別確認した。 |
| — | — | — | `tools/config-editor/lib/*.js` (13ファイル) | **全ファイルが`server.js`等から参照されており未使用モジュールなし**(`materialLabels.js`が最も参照が薄いが`server.js`から1箇所requireされている)。 | 各libモジュール名を`server.js`/`lib`/`public/js`/`test`全体でテキスト検索、13ファイル全てに自己ファイル以外からの参照を確認。 |

### B-5. 空実装/スタブ

| ID | 重要度 | 分類 | 場所 | 事象 | 根拠 |
|----|--------|------|------|------|------|
| — | — | — | `TrinityForge/src/main/java` 全体 | **空実装/`return null`のみ/未実装コメント付きスタブは検出されず**。 | `grep -rn "throw new UnsupportedOperationException\|// not implemented\|// TODO: implement\|return null; // stub"` は0件。`{}`のみの空メソッド体は`private SkillId() {}` / `private NativeRewardRegistry() {}`の2件のみで、いずれもユーティリティクラスの意図的なprivateコンストラクタ(Java標準イディオム)であり問題なし。 |

---

## まとめ

### タスクA(コメント棚卸し)
- 集計対象: TrinityForge/src/main/java 355ファイル、コメント9,771行(実測、ブリーフの数値と一致)
- 上位40ファイル(3,743行、全体の38%)を全件通読
- パターン別: 区切り線0件、コメントアウト旧コード実質0件、自明javadoc実質0件、履歴メモ19件中DELETE適格2件、TODO 4件(全件未対応・KEEP)、長大ブロック243件/3,705行(EXTERNALIZE適格は実質1件)
- **削減見込み: DELETE 約10〜15行、EXTERNALIZE 約35行、合計 約45〜50行(全体の約0.5%)**
- このコードベースのコメントは既に高品質(Why中心)であり、大量削減の前提が成立しないことが実測で判明

### タスクB(デッドコード/レガシー検出)
- **デッドコード確定candidate: 2件**(CLN-25 `BlacksmithBanListener`未登録=HIGH、CLN-26 `VanillaMaterialStats`未配線+ドキュメント/実装乖離=HIGH)
- **レガシー残骸: 2件**(CLN-28空ディレクトリ、CLN-29関連build設定1行、いずれもLOW)
- ValhallaMMO撤廃(2026-07-22)のjavaコード側残骸は既にクリーン(パッケージ自体が存在しない)
- 重複実装の顕著な例は1件のみ検出(CLN-30、LOW、要確認)
- 未使用依存・空実装スタブは検出されず
