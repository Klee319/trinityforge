# 2026-07-23 大改修設計書: stat全面統合 + 動的ゲート機構 + ドロップテーブル + editor大改修

status: DRAFT (ゲート機構/fork機能アイテム/図鑑系の探索完了後に確定)

## 0. 背景と決定事項

- ユーザー決定: 数値系ネイティブ効果は**全面的にstat語彙へ移行**し、consumerを装備+perk合算 (`PlayerStatAggregator.totalOf()`) へ配線。装備アイテムにも同効果を付与可能にする。
- 解放系（習得・永続）は skilltree に残し、**静的カタログID方式 → 動的ID方式**へ改修。専用効果カタログ (dedicated-effects.yml) と editor カタログUIは廃止。
- 4職（採掘/伐採/掘削/釣り）に**重み付きドロップテーブル+カテゴリ+perkゲート**を新設。
- ArsTier 現仕様確認済み: ブック/ワンドの maxGlyphTier への**加算値** (+N)。現状維持。

## 1. 緊急修正（実装ウェーブ0）

1. **catalog移動アイテムの参照修復（ユーザー決定 2026-07-23）**: tf_gacha_ticket*, tf_scrap, tf_crystal_apple, tf_core_*, 圧縮ブロック類は 7/21 に editor「素材タブへ移動」で **ArsPaper materials.yml へ移動済みで、これが正**（editorバグではない。定義場所は Ars materials.yml のまま維持）。壊れている TF 側参照を**クロスプラグイン解決**に改修する:
   - 新設 `stats/CrossPluginItemResolver`（仮称）: id→ItemStack 生成は TF catalog → ArsItemGiveBridge の順（GiveItemCommand:126-173 の既存パターンを共通化）。ItemStack→id 識別は TF PDC `ITEM_CATALOG_ID` → Ars PDC `arspaper:custom_item_id` の dual-read
   - GachaListener の券識別・景品生成、crafting-features scrap-fallback、villager-trades の catalog: 入出力、ドロップテーブル(§4)のitem解決を全てこのリゾルバ経由に
   - 圧縮レシピ (compressed_wood_1x 等) は Ars 側 UnifiedRecipeLoader で登録されるため、ゲートは W2a の `recipe:` 動的ゲート（fork RecipeUnlockGate）が受け持つ
2. ポーションエフェクトID語彙: 1.21系現行名（`JUMP_BOOST`, `SLOWNESS` 等）に全設定・editor語彙を統一。文字列→PotionEffectType解決は Registry ベースに。
3. 訂正: lapis/source-cost-reduction, material-refund-chance, ingredient-no-consume-chance, thread-slot-expansion, source-auto-consume, ocean/ruins-thread は **fork側に consumer 実装済み** (LapisCostReductionListener, SourceAutoConsume, loot/OceanThreadFishingListener, loot/RuinsThreadDropListener, tfEffectiveThreadSlotCap 等、TrinityForgeBridge:1009-1232)。真の未実装は craft-thread-unlock と food-compression 本体のみ。

## 2. stat語彙 全面移行マッピング

### 2.1 新規statキー（native/dedicated → stat）

表記: 旧キー → 新キー (表示名 / format / lore category / consumer変更)

**弓系 (NativeCombatPerkListener → totalOf(shooter, bow)):**
| 旧 | 新 | 表示名 | format | cat | 備考 |
|---|---|---|---|---|---|
| archery_inaccuracy_add | `bow_accuracy` | 弓精度 | PERCENT | attack | **符号反転** (正=高精度)。jitter = max(0, 0.08 − v) |
| archery_ammosavechance_add | `ammo_save_chance` | 矢節約率 | PERCENT (cap 0.9) | attack | |
| archery_distancedamagebonus_add | `distance_damage_bonus` | 距離ダメージ | PERCENT | attack | ×(1+v×min(64,blocks)/16) |
| archery_chargedshotpiercing_add | `arrow_piercing` | 矢貫通 | INTEGER | attack | |
| archery_chargedshotvelocitybonus_add | `arrow_velocity` | 矢速度 | PERCENT | attack | |
| archery_chargedshotcooldown_add | `bow_cooldown_reduction` | 弓CT短縮 | PERCENT | attack | **符号反転** (正=短縮) |
| archery_chargedshotknockback_add | `arrow_knockback` | 矢ノックバック | FLAT | attack | |

archery_chargedshotunlocked_toggle は**機能解放として native 維持**。
> **2026-07-27 追記 — この判断は撤回済み。** 一度 `charged-shot-unlocked` ステとして stat 化されたが、
> 「解放フラグ」が解放する対象（貫通/初速/弓CT短縮/矢ノックバック）はいずれも**自分自身のステが
> 非0であること**を個別に要求しており、フラグの OR 条件にもその同じステが並んでいた＝完全な同語反復で、
> あってもなくても挙動が一切変わらなかった。TF の語彙・lore・base-stats・archery.yml・editor から
> 全面撤去済み。弓の「チャージ時間」を縮めるバニラのレバーは 1.21.11 に存在しない（Attribute に draw 系が
> 無く、引き絞りはクライアント側予測）。クロスボウの装填時間だけは QUICK_CHARGE で縮められる。

**近接系 (NativeCombatPerkListener → totalOf(attacker, weapon)):**
| 旧 | 新 | 表示名 | format | cat |
|---|---|---|---|---|
| lightweapons_knockbackmultiplier_add | `melee_knockback` | 追撃ノックバック | PERCENT | attack |
| heavyweapons_stunchance_add | `stun_chance` | スタン率 | PERCENT (cap 0.75) | attack |
| heavyweapons_powerattackdamagemultiplier_add | `power_attack_damage` | 空中攻撃ダメージ | PERCENT | attack |
| heavyweapons_powerattackradius_add | `power_attack_radius` | 空中攻撃半径 | FLAT(m) | attack |
| lightweapons_parrydamagereduction_add | `parry_damage_reduction` | パリィ軽減 | PERCENT (cap 0.95) | defense |
| lightweapons_coatingcharges_add + heavyweapons_coatingcharges_add + coating-stack-increase | `coating_charges` | コーティング回数 | INTEGER | other | **3キー統合** |

**生存・汎用系:**
| 旧 | 新 | 表示名 | format | cat | consumer |
|---|---|---|---|---|---|
| power_healthregenerationbonus_add | `health_regen_bonus` | 自然回復量 | PERCENT | defense | NativeSurvivalPerkListener |
| power_hungersavechance_add | `hunger_save_chance` | 空腹節約率 | PERCENT (cap 0.9) | utility | 同上 |
| power_entitydropmultiplier_add | `mob_drop_bonus` | 討伐ドロップ増加 | PERCENT (factor cap ×3) | utility | 同上 |
| power_allskillexpmultiplier_add | `skill_exp_bonus` | スキル経験値増加 | PERCENT | utility | NativeProgressionService注入部 |
| power_cooldownreduction_add | `cooldown_reduction` | 武器CT短縮(2026-07-25訂正。旧表示名「スキルCT短縮」は実態と不一致だった) | PERCENT | attack | CombatListener.startWeaponCooldown |
| (2026-07-25新設、同日CT設計一本化でActiveSkill単位へ分割済み) | `haste_active_mining_cooldown_reduction`(旧 `skill_cooldown_reduction` はグローバル1本で他アクティブスキルへ波及するバグがあり撤去) | 高速破壊CT短縮 | PERCENT | attack | ActivationDispatcher/ActiveCommand → CooldownManager.applyReduction |
| power_luckbonus_add | `loot_luck` | 幸運 | FLAT | utility | PlayerLootLuckSource |
| power_mobdropbonus_add | `mob_drop_quality` | ドロップ品質 | FLAT | utility | PlayerMobDropBonusSource |
| gacha-rate-up (dedicated) | `gacha_rate_bonus` | ガチャ確率UP | PERCENT | utility | GachaListener:106 |
| suspicious-block-respawn (dedicated) | `suspicious_respawn_chance` | 怪しいブロック復活 | PERCENT | gathering | MiningGimmickListener:75 |
| hive-double-harvest (dedicated) | `hive_double_harvest_chance` | 巣二重採取率 | PERCENT | gathering | BeekeepingListener:70 |
| no-food-consume-chance (dedicated) | `food_save_chance` | 食料節約率 | PERCENT | utility | FoodGimmickListener:73 |

**属性系（既存statキーへ統合、新キーなし）:**
- power_healthbonus_add → `max_health`
- power_knockbackresistancebonus_add → `knockback_resistance`
- lightweapons_attackspeedmultiplier_add + heavyweapons_attackspeedmultiplier_add → `attack_speed`
- lightweapons_attackreachbonus_add → `attack_reach`
- 防具セット系4キー (lightarmor/heavyarmor perpiece・set系) は**着用条件付き機構のため native 維持** (NativeAttributeBridge)

**クラフト系 (CraftQualityService → クラフターの totalOf()):**
| 旧 | 新 | 表示名 | format | cat |
|---|---|---|---|---|
| smithing_craftqualitybonus_add + arssmithing_craftqualitybonus_add + enchant-point-bonus | `craft_quality_bonus` | クラフト品質 | FLAT(pt) | craft |
| arssmithing_craftupswingbonus_add (+ smithing-lowroll-reduction) | `craft_upswing_bonus` | 上振れ拡大 | FLAT | craft |
| craftdownswingbonus (smithing-downswing-reduction **配線ギャップ修正**) | `craft_downswing_reduction` | 下振れ抑制 | FLAT | craft |
| craftrollupbonus | `craft_roll_up_bonus` | ロール上振れ | FLAT | craft |
| craftrolldownreduction | `craft_roll_down_reduction` | ロール下振れ抑制 | FLAT | craft |
| craftrollinsetdelta | `craft_roll_inset` | ロール収束 | FLAT | craft |
| arssmithing_threadslots_add | `craft_thread_slot_bonus` | 付与スレッド枠 | INTEGER | craft | ※既存 `thread_slots`(装備自身の枠) とは**別キー維持** |

**Ars系 (perk buffs general → ArsNativeBridge):**
- arsmagic_maxmanabonus_add → `mana_bonus` (既存stat統合。bridgeは**perk由来のみ**返す — 装備分はfork itemStats経路で既に消費、二重計上禁止)
- arsmagic_manaregenbonus_add → `mana_regen` (同上)
- arsmagic_unlockedtier_add / arsmagic_glyphslots_add は解放系のため native 維持

**fork consumer系 (dedicated数値 → stat。fork は新設 TF static API `statTotal(player, key)` 経由で読む):**
| 旧 dedicated id | 新stat | 表示名 | format | cat | fork consumer |
|---|---|---|---|---|---|
| lapis-cost-reduction | `lapis_cost_reduction` | ラピス消費軽減 | PERCENT | craft | LapisCostReductionListener |
| source-cost-reduction | `source_cost_reduction` | ソース消費軽減 | PERCENT | ars | RitualManager:112-118 |
| material-refund-chance | `material_refund_chance` | 素材返還率 | PERCENT | craft | fork Bridge経由 |
| ingredient-no-consume-chance | `ingredient_save_chance` | 材料節約率 | PERCENT | craft | fork Bridge経由 |
| thread-slot-expansion | `thread_slot_cap_bonus` | スレッド枠上限+ | INTEGER | ars | tfEffectiveThreadSlotCap |

- source-auto-consume は flag → 機能解放 (`feature:source-auto-consume`)
- ocean-thread-catch / ruins-thread-drop は **fork の loot リスナーを廃止**し、TF側ドロップテーブル (§4) へ移設

### 2.2 stat化しないもの（確定）

- 永続アンロック型全部（グリフ/レシピ/儀式/取引/醸造/機能解放/ArsTier）→ §3 動的ゲート
- over-enchant → **ユーザー指示によりstat化撤回**。コンフィグ定義ID参照方式 (§3.6)
- 機能解放フラグ (vein-mining, tree-fell, auto-replant, area-harvest, bee-no-aggro, animal-damage-4x, junkfood系, satiety-buff, junk-to-scrap, xp-bottle, potion-merge, wood-repair, weapon-coating, dismantle, haste-active-mining, spawner-silktouch, food-compression) → 機能解放 select リスト（プログラム定義固定語彙）
- ドロップ品系 (apple×3, gacha-ticket-1..5, ancient-debris-drop, ruins-thread-drop, ocean-thread-catch) → §4 ドロップテーブルへ移設
- 進行管理キー (perks_locked_add, p:perks_permanently_unlocked_add) → 対象外

### 2.3 Loreカテゴリ再編

lore.yml `category:` を **attack / defense / craft / gathering / utility / ars / other** の7分類に拡張
(現行: attack/defense/support/ars/other。support は utility へ改名統合し、craft/gathering を新設)。
- 既存の移動: move_speed → utility, mining_fortune/fishing_luck/fishing_bonus → gathering
- `StatCategory` enum / `StatCategoryInference` / `StatsCategory`(コマンド) / editor `STAT_META` を追随
- 釣り運 `fishing_luck` は**意味論変更**: 宝/ゴミ比率シフト% (PERCENT表示に変更)。品質はルート品質基準値系が担当
- 宝釣りエンチャント: 1Lvあたり fishing_luck +20% (EnchantmentStatBridge拡張)。入れ食い=バニラ準拠(変更なし)

### 2.4 配線変更の共通形

- `PerkBuffResolver` の GENERAL_KEYS 拡張ではなく、**新設 `stats/StatVocabulary.java`**（キー→チャネル(attack/defense/attribute/general)の単一ソース）に集約。PerkBuffResolver / StatsCategory / lore検証 / editor API が参照
- 各consumerは `NativePerkRewardResolver.value()` → `PlayerStatAggregator.aggregate(player, contextItem).totalOf(key)` へ切替（乗算レイヤも自動適用）
- skilltree yml の native: 数値キーは buffs: の新statキーへ**機械変換**（値の符号反転2件に注意）
- 旧キーはローダーで警告+無視（後方互換読み替えはしない。ymlは一括変換する）

## 3. 動的ゲート機構（解放系）

方針: dedicated-effects.yml カタログを廃止し、**プレフィックス付き動的ID**へ。既存の held-perks PDCミラー/DedicatedEffectGateIndex アーキテクチャは維持し、ID空間だけ動的化。
「**どの perk からも参照されていない対象は open**、参照された瞬間 locked」の自動反転規則。

| type | ID形式 | 対象語彙ソース | UI |
|---|---|---|---|
| 機能解放 | `feature:<id>` | プログラム定義固定リスト | select (現状維持相当) |
| ArsTier | `ars-tier` (value=+N) | - | 数値入力 (現状維持) |
| 醸造解放 | `brew:<groupId>` | crafting-features の brew-unlocks 定義ID | select・複数追加可 |
| 取引解放 | `trade:<profId>` | villager-trades の職業ID | select・複数追加可 |
| グリフ解放 | `glyph:<glyphKey>` | fork glyphs.yml 全キー (全グリフに暗黙ゲート・デフォルトopen) | select・複数追加可 |
| レシピゲート | `recipe:<itemId>` | catalog/fork items (素材入力UI) | materialInput同等UI |
| 儀式ゲート | `ritual:<itemId>` | 同上 | 同上 |
| ドロップ解放 | `drop:<prof>:<categoryId>` / `drop:<prof>:item:<itemId>` | 各職ドロップテーブル | select+素材UI |
| オーバーエンチャ | `overenchant:<id>` | over-enchant コンフィグ定義ID (任意ID) | select |

### 3.1 実装方式（探索結果反映済み・確定）

- ノードyml記述は現行の `dedicated-effects: [{id: ..., value: ...}]` 形式を維持し、**idをプレフィックス付き動的IDに**する: `glyph:blink` / `brew:swiftness-jump` / `trade:WEAPONSMITH` / `recipe:<recipeId>` / `ritual:<ritualId>` / `drop:<prof>:<catId>` / `feature:<featureId>` / `overenchant:<id>`
- `DedicatedEffectsConfig` は dedicated-effects.yml カタログ読込を廃止し、(a) プレフィックス解析による channel 導出 + (b) **Java定義の FeatureEffectRegistry**（機能解放の固定語彙: id/label/param/unique）に置換。`DedicatedEffectGateIndex.build()` の出力形状 (channel→target→Set<perkId>) は**不変**（target = プレフィックス後段）
- **fork側の UsageGate / UnlockGate は無改修**で動く（tfGlyphGatePerks() 等のマップ形状が変わらないため）。fork の数値系 tfEffectValue 読みは新設 TF static API `statTotal(player, key)` へ切替
- 「代表target」方式の廃止: 旧glyph-gate 29件が代表1キーで束ねていたグリフ群（beam→super_*15種、gear-tier→武具5〜18レシピ等）は、**yml変換時に全構成キーへ展開**（旧カタログの note 記載の束を変換表に落とす）。stale ID修正: `volcanic_sourcelink_craft`→`volcanic_sourcelink` 等、現行 fork 定義と突合
- SkillTreeConfig.parseDedicatedEffects の「カタログ未知id警告」は「プレフィックス不正/語彙外警告」に置換。unique検証は unlock系プレフィックス全種に適用（editor側警告と二重防御）

### 3.2 FeatureEffectRegistry 固定語彙（`feature:<id>`、Java定義）

| id | param | 説明 |
|---|---|---|
| vein-mining | none | 鉱脈一括破壊 |
| haste-active-mining | none | 採掘ハステアクティブ |
| spawner-silktouch-harvest | none | スポナーST回収 |
| small-tree-fell / large-tree-fell | none | 小木/大木一括伐採 |
| auto-replant | none | 自動再植 |
| area-harvest | none | 範囲収穫 |
| animal-damage-4x | none | 動物特効 |
| bee-no-aggro | none | 蜂非敵対 |
| junkfood-immunity / junkfood-inversion | none | ゴミ食免疫/反転 |
| satiety-buff | none | 満腹バフ |
| junk-to-scrap | none | 釣りゴミ→スクラップ |
| xp-bottle-store-unlock | none | 経験値瓶保存 |
| dismantle-unlock | level | 装備解体 (value=解体Lv) |
| potion-merge | none | ポーション統合 |
| wood-repair-unlock | none | 木材修繕 |
| weapon-coating-unlock | none | 武器コーティング解放 |
| food-compression | none | 食料圧縮 |
| source-auto-consume | none | ソース自動消費 |
| craft-thread-unlock | none | スレッドクラフト解放 |
| fish-sell-toggle | none | 釣った魚を自動売却(2026-07-25 経済連携で復活。下記参照) |
| furnace-smelt-speed | level | 精錬速度短縮%(鍛冶A-1〜A-3。2026-07-25 追加) |
| furnace-smelt-bonus | level | 精錬ボーナス%(鍛冶B-1〜B-3。2026-07-25 追加) |
| junk-food-restore-boost | level | ゴミ食のみの満腹度回復ボーナス%(農業A-alpha-2。2026-07-25 追加) |
| digging-durability-vanilla-exp | level | 消費シャベル耐久累計→バニラEXPボーナス上限%(切削C-1。2026-07-25 追加) |
| digging-durability-job-exp | level | 消費シャベル耐久累計→職業EXPボーナス上限%(切削C-2。2026-07-25 追加) |

※ 上記5件(2026-07-25 追加)はいずれも param=level。同一featureを複数ノードへ段階配置し、
`DedicatedEffectsConfig#valueMax` が保持ノード中の最大 value を採る方式のため、tierテーブルを持たない
(A-3保持者は A-1/A-2 のperkも保持しているので、そのまま最大%が引ける)。かまど系2件は
「精錬物を入れた本人」のみに適用される(所有者はブロックのPDCへ記録。醸造スタンドの既存方式と同型)。

※ fish-sell-toggle は #5 exploit fix で恒久no-op化され一時語彙から除外されていたが、2026-07-25 の
Vault経済連携(EconomyBridge)実装により復活した: 保持者が釣った魚を釣った瞬間にVault通貨へ自動換金する
（無限売却を防ぐ1分あたり売却上限つき）。gacha-ticket/apple/debris/thread ドロップ系は §4 ドロップテーブルへ、
数値系は §2 stat へ移行済み。
- 解放ゲートコンフィグ (unlock-gate hub) と crafting-features の解放レシピ欄 (gated-catalog-recipes) は **editor・実装とも削除**（recipe: ゲートに置換）
- 儀式エフェクトIDは editor でグレーアウト（変更不可）
- editor検証: 一回性効果 (feature/brew/trade/glyph/recipe/ritual/drop/overenchant) が複数ノードから参照されたら警告

## 4. ドロップテーブル機構（4職）

### 4.1 スキーマ（各ギミックyml内 `drop-tables:` セクション）

```yaml
# 採掘/伐採/掘削 (ブロック破壊型): 追加ドロップ
drop-tables:
  categories:
    tier1:
      display-name: "Tier1"
      trigger-chance-percent: 5.0   # 対象ブロック破壊ごとの発動率
      entries:
        - item: tf_gacha_ticket_1   # catalogID または Material名 (gacha.yml と同規約)
          weight: 10
          amount: 1
```

```yaml
# 釣り (獲得物置換型): junk/treasure 2大グループ + 比率
fishing:
  group-ratio:
    treasure-percent: 15.0   # デフォルト宝率。fishing_luck% で ×(1+luck) シフト
  groups:
    treasure:
      categories: { tier1: { display-name:..., entries: [...] } }
    junk:
      categories: { ... }
```

- 掘削は**新設リスナー**（シャベル適正ブロック破壊トリガー。digging_progression.yml の digging_break 表を対象判定に流用）
- ガチャ券1..3(採掘)/4..5(釣り)/リンゴ3種(伐採)/ancient-debris(採掘)/遺跡スレッド(掘削)/海洋スレッド(釣り) は全てテーブルエントリへ移行。専用UI・専用confファイルは削除
- 遺跡/海洋スレッド: 専用アイテム未定義 → catalog.yml に新規アイテム定義を追加（thread系儀式素材として）
- perkゲート: `drop:<prof>:<cat>` 参照で locked。未参照カテゴリ/エントリは open
- 重み抽選は `gacha/GachaDraw.java` の累積weight方式を共通化して再利用
- 採集タブ(gathering.yml)は廃止し、fortune-per-level 等を各ギミックymlへ統合

## 5. editor改修サマリ

1. 専用効果カタログUI削除 (tf-phase3-forms.js:156-365, constants.js:12-129, registry/app配線)
2. skilltreeノードUI: dedicated-effectsセクション → 新「解放効果」セクション（§3のtype別select/素材UI）。語彙は新API `/api/gate-vocabulary` で一元供給（DEDICATED_EFFECTSの静的ミラー廃止）
3. buffs セクション: 新stat語彙 (lore.yml動的) — 既存機構がそのまま拾う
4. ギミックタブ: ドロップテーブルUI新設（materialInput+weight+amount行、カテゴリCRUD、釣りはgroup比率）。ガチャ券専用UI・リンゴ%UI削除
5. gathering タブ削除（釣り欄→釣りギミック、採掘欄→採掘ギミックへ統合）
6. 村人取引: unlock-effect をselect化・アイテム指定を materialInput 統一・職業名日本語化・重なりUI修正
7. crafting-features: 解放レシピ欄削除、over-enchant を任意ID定義方式に
8. 儀式エフェクトID グレーアウト
9. 一回性効果の複数参照警告
10. ロールバフ: スキル/ポーションエフェクトをselect化 + エフェクトID現行語彙修正
11. アイテムステータス: 品質基準値を使用スキルの右隣へ移動 + モブドロップ/ルート系適用（プラグイン側含む）
12. リソースパック管理タブ空表示バグ修正
13. Arsグリフ: 表示名編集+カテゴリ分類(意味なし・UI用)+折りたたみ時表示名
14. 魔法カテゴリに機能アイテムレシピタブ（fork探索結果待ち）

## 6. 新機能（バッチ2、探索結果反映済み）

### 6.1 特殊報酬レジストリ (`progression/special-rewards.yml`)

```yaml
titles:
  <id>: { display: "<gradient:...>竜殺し</gradient>", }   # MiniMessage可
particles:
  <id>: { particle: FLAME, count: 8, radius: 0.6, interval-ticks: 10, shape: circle|aura }
particle-seeds:
  <id>: { seed-item: <material or custom:id>, particle: CRIT, count: 4 }   # ツール合成用
```
- 称号表示: プレイヤー頭上 TextDisplay（`mob/FocusHpDisplay.java` のパターンを流用: Billboard.CENTER・PDCタグで孤児掃除・死亡/退出時cleanup。EM CombatLevelDisplay の「死亡位置に浮遊残留」バグを踏まないこと）
- パーティクルシード: シードアイテム（editor指定の任意アイテム）を作業台でツール/武器と組合せ → ツールPDCにパーティクルID焼込 → BlockBreak/攻撃時に発生
- 保有・装備状態: プレイヤーPDC（unlocked set + equipped title/particle）
- **参照方法の統一**: skilltree（`reward:<id>` 動的ID）/ 図鑑報酬ティア / アチーブメント報酬 の3箇所から同一IDを参照
- プレイヤー設定GUI (`/tf settings`): 称号選択/パーティクル選択/「他人の称号・パーティクル非表示」トグル（軽量化用、PDCフラグ+表示側でフィルタ）

### 6.2 アチーブメント (`progression/achievements.yml`)

```yaml
achievements:
  <id>:
    display-name: "ジャンプ王"
    trigger:
      type: statistic          # statistic | advancement
      statistic: JUMP          # Bukkit Statistic名 (JUMP / WALK_ONE_CM / PLAY_ONE_MINUTE 等)
      threshold: 10000
    # type: advancement の場合: advancement: minecraft:story/mine_diamond
    broadcast: true            # 達成時サーバ通知
    rewards:
      special: [<special-reward-id>...]
      commands: ["give %player% ..."]
```
- トラッキング: バニラ統計は周期タスクポーリング（1分毎、達成済みPDCセットと突合）+ PlayerAdvancementDoneEvent。ジャンプ/移動距離/ログイン時間はバニラ Statistic をそのまま使う（独自カウンタ不要）
- editor: アチーブ定義タブ + 報酬設定（アチーブをselectメニューで選び、通知有無・報酬を設定）

### 6.3 図鑑改善

- 現状: PDC文字列Set (`item:<catalogId>` / `mob:<ENTITY_TYPE>`)、日時・品質なし、GUIなし (CollectionCommand/CollectionService/CollectionListener)
- データ拡張: PDCエントリを `id|epochMillis|maxQualityPt` 形式に拡張（旧形式は読み替え互換）。記録時に品質pt（CatalogIdentity+品質PDC）を更新
- `progression/collection.yml` 拡張: カテゴリ定義（items/mobsの分類、表示順）+ 報酬ティア（既存 reward-tiers に special: 参照を追加）
- GUI: `/tf collection` をページ付きインベントリGUI化（カテゴリタブ、未発見はグレー、ホバーで記録日時・最大品質pt表示）
- editor: 図鑑タブ（分類設定・報酬設定）

### 6.7 新規yml確定スキーマ（TF実装・editorタブ共通の契約。逸脱禁止）

```yaml
# progression/special-rewards.yml
titles:
  <id>:
    display: "<MiniMessage文字列>"      # 頭上称号表示
particles:
  <id>:
    particle: FLAME                     # Bukkit Particle名
    count: 8
    radius: 0.6
    interval-ticks: 10
    shape: circle                       # circle | aura
particle-seeds:
  <id>:
    seed-item: "custom:xxx"             # custom:<id> または Material名
    particle: CRIT
    count: 4

# progression/achievements.yml
achievements:
  <id>:
    display-name: "ジャンプ王"
    trigger:
      type: statistic                   # statistic | advancement
      statistic: JUMP                   # type=statistic時: Bukkit Statistic名
      threshold: 10000
      advancement: ""                   # type=advancement時: "minecraft:story/mine_diamond"
    broadcast: true
    rewards:
      special: []                       # special-rewards の id 配列
      commands: []                      # %player% 置換

# progression/collection.yml（既存 reward-tiers を拡張）
categories:
  items:
    <catId>: { display-name: "武器", order: 1, entries: [<catalogId>...] }
  mobs:
    <catId>: { display-name: "ボス", order: 1, entries: [<ENTITY_TYPE|mobTypeId>...] }
reward-tiers:
  <tierId>:
    threshold: 10
    title: "..."
    broadcast: true
    commands: []
    special: []                         # special-rewards の id 配列（追加フィールド）
```
- 未分類エントリはGUIで自動「その他」カテゴリ表示
- editor登録: special-rewards / achievements / collection の3タブ（スキル&進行グループ）

### 6.4 機能アイテムレシピタブ（editor・魔法カテゴリ）

- fork側: `functional-items.yml`（表示名上書き、W2d-1で実装）+ 既存レシピ定義（items.yml / TF catalog.yml）
- editor: 機能アイテム一覧（dominion_wand/teleport_compass/pedestal/ritual_core/scribing_table/waystone/source_berry）。CMD/material/item id は**グレーアウト表示のみ**、表示名とレシピ（items.yml or catalog.yml側の該当recipe）を編集可能
- リロールアイテムは**未実装のため対象外**（存在しないことを確認済み。将来実装時に追加）

### 6.5 村人取引の仕様確認結果（ユーザーへ報告済み事項）

- オリジナル取引作成: **可能**（現行仕様どおり）
- 現行コードの実挙動: `unlock-effect` **空欄 = 常時解放**（ユーザー認識の「空欄は永久不可」と逆）。block-vanilla-trades=true時: 解放済→カスタムに全置換/未解放→対話cancel は認識どおり
- 新方式: unlock-effect フィールド廃止 → `trade:<PROFESSION>` 動的ID。skilltreeから未参照なら解放（自動反転規則）。「未参照=封印」にしたい場合は職業ごとの `locked-until-referenced` フラグを追加検討（ユーザー確認待ち）

### 6.6 品質基準値 (quality-mode-offset) の適用拡大

- 適用済: クラフト/Ars儀式クラフトのみ
- 追加適用: MobTypeDropListener (L101-108のmode算出に加算) / EM fork TrinityForgeLootListener / PickupQualityListener / FishingQualityListener / GachaListener(quality-random を一様乱数→mode分布+offset に変更するかは保留、まずは非適用のまま)
- editor: 品質基準値フィールドを「使用スキル」の右隣へ移動

## 7. 実装ウェーブ計画

- W0: 緊急修正（catalog復旧+editor保存バグ、ポーションID）
- W1: TF stat移行（StatVocabulary新設、consumer配線、yml一括変換、lore.yml再編、テスト）
- W2: 動的ゲート機構（TF+fork）+ ドロップテーブル（TF、掘削リスナー新設）
- W3: editor大改修（カタログ削除、解放UI、ドロップテーブルUI、各タブ修正）
- W4: 新機能（特殊報酬/アチーブメント/図鑑/機能アイテム）
- W5: verifier + ビルド + 配備

### W5 配備時の必須手順（レビューで判明）
- **稼働サーバの ArsPaper データフォルダの glyphs.yml には display-name が現れない**（updateResourceFiles はバージョン変化時のみ再抽出、tier/params のユーザー編集を保護するため /ars reload reset は不可）→ 配備時に display-name 120件を稼働側 glyphs.yml へマージするか、editor の deployPaths ミラー経由で反映すること。functional-items.yml はファイル欠損時 saveResource で自己修復するため対応不要
- fork リポジトリに W2d-1 以前の未コミット変更（アーマー系削除等）が混在している点に留意（verifier B 報告）
