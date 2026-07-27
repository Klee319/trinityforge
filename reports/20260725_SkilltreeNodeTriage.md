# スキルツリー「機構ゼロ」47ノード仕分けレポート (2026-07-25)

> **⚠️ 2026-07-27: この文書は残タスクの一次情報ではありません。**
> 残タスク・既知の問題・作業履歴は `reports/ACTIVE_RECORD.md` に集約しました。
> 本書はその日の作業記録として残していますが、記載されている「残タスク」「保留リスト」は
> 既に解決済みのものを多く含みます（棚卸しで15件が実装済みと判明）。参照しないでください。


対象: `TrinityForge/src/main/resources/skilltree/*.yml` の47ノード（`buffs`/`mainhand-buffs`/`dedicated-effects`のいずれも持たないノード）。
本レポートはコード調査のみ。ymlは一切変更していない。

---

## 1. サマリ表

| 分類 | 件数 |
|---|---|
| 【設定のみ】 | 22 |
| 【機構なし】 | 18 |
| 【要判断】 | 7 |
| **合計** | **47** |

### スキル別内訳

| スキル | 設定のみ | 機構なし | 要判断 | 計 |
|---|---|---|---|---|
| alchemy | 0 | 3 | 2 | 5 |
| ars_magic | 9 | 1 | 0 | 10 |
| digging | 2 | 2 | 0 | 4 |
| enchanting | 0 | 4 | 2 | 6 |
| farming | 4 | 1 | 0 | 5 |
| fishing | 3 | 2 | 2 | 7 |
| mining | 2 | 0 | 0 | 2 |
| smithing | 0 | 6 | 0 | 6 |
| woodcutting | 2 | 0 | 0 | 2 |

---

## 2. 47ノード全件一覧

凡例: 分類 = 【設】設定のみ / 【無】機構なし / 【判】要判断

### alchemy

| ノード | 効果文言 | 分類 | 対応方法 / 不足しているもの |
|---|---|---|---|
| A | 品質+1・醸造速度UP | 【無】 | ポーションの「品質」「醸造速度」を数値化する機構がコード上に存在しない。`CraftQualityService`は装備品(craftItemEvent/儀式)専用で`BrewEvent`は通っていない(`NativeSkillExperienceListener.onBrew`はEXP付与のみ、`TrinityForge/src/main/java/com/trinityforge/listeners/NativeSkillExperienceListener.java:284-312`)。要: 醸造品質/速度という新概念の設計+`BrewEvent`向けconsumer新設。 |
| B | 品質+1・素材消費しない確率UP | 【判】 | 「品質+1」は上と同じ理由で【無】。「素材消費しない確率UP」は`ingredient_save_chance`という stat キーが既に存在する(`StatVocabulary.java:102`)が、実際の消費者はArsPaperフォーク側の儀式/スペル素材コスト削減のみ(`TrinityForge.java:1091`コメント参照)で、**バニラの`BrewEvent`（ブリューイングスタンドでの素材消費）には一切配線されていない**。ユーザー確認事項: この既存stat consumerをバニラ醸造にも拡張してよいか、それとも本ノードは別物として新設するか。 |
| B-alpha-1 | 素材を消費しない確率UP | 【判】 | Bと同じ理由。`ingredient_save_chance`はあるが、バニラ醸造への配線が無い。 |
| B-alpha-2 | 品質+1・醸造速度アップ | 【無】 | Aと同じ理由。 |
| B-beta-1 | 残留時間UP・スプラッシュ強度UP | 【無】 | 生成されるポーションのNBT(持続時間/範囲/強度)を書き換える機構が存在しない。`onBrew`はEXP付与のみで結果アイテムには一切触れていない。要: `BrewEvent#getResults()`を書き換える新規リスナー。 |

### ars_magic

| ノード | 効果文言 | 分類 | 対応方法 / 不足しているもの |
|---|---|---|---|
| B-1-1 | 焦熱解放 | 【設】 | `dedicated-effects: - id: "glyph:scorch"` — `fork-handoff/arspaper/fork/src/main/resources/glyphs.yml:953`に`scorch`(display-name:"焦熱")が実在。 |
| B-1-2 | 雷撃解放 | 【設】 | `dedicated-effects: - id: "glyph:lightning"` — `glyphs.yml:1266`に`lightning`(display-name:"雷撃")が実在。 |
| B-2-1 | 凍裂解放 | 【設】 | `dedicated-effects: - id: "glyph:cold_snap"` — `glyphs.yml:975`に`cold_snap`(display-name:"凍裂")が実在。 |
| B-2-2 | 拘束解放 | 【設】 | `dedicated-effects: - id: "glyph:snare"` — `glyphs.yml:638`に`snare`(display-name:"拘束")が実在。 |
| B-3 | 害悪が強力に | 【無】 | これは新規グリフ解放ではなく既存「害悪(harm)」グリフの強化。`harm`グリフ自体はどのスキルツリーにも`glyph:harm`として一度も配置されておらず(全16 yml検索で0件)、TF側にグリフ個別の威力ブースト用stat/consumerも存在しない。要: 「特定グリフのダメージ倍率」という新stat語彙+fork側のダメージ計算への新規フック。 |
| B-3-1 | 烈風解放 | 【設】 | `dedicated-effects: - id: "glyph:windshear"` — `glyphs.yml:995`に`windshear`(display-name:"烈風")が実在。 |
| B-3-2 | 引寄解放 | 【設】 | `dedicated-effects: - id: "glyph:pull"` — `glyphs.yml:349`に`pull`(display-name:"引寄")が実在。 |
| C-4-1 | 狼召喚・生命化解放 | 【設】 | `dedicated-effects: - id: "glyph:summon_wolves"` (`glyphs.yml:738`, "狼召喚") + `- id: "glyph:animate"` (`glyphs.yml:1098`, "生命化") |
| C-4-2 | 不死召喚解放 | 【設】 | `dedicated-effects: - id: "glyph:summon_undead"` — `glyphs.yml:1385`(display-name:"不死召喚") |
| C-4-3 | 囮解放・妖精召喚開放 | 【設】 | `dedicated-effects: - id: "glyph:summon_decoy"` (`glyphs.yml:1435`, "囮召喚") + `- id: "glyph:summon_vex"` (`glyphs.yml:1409`, "妖精召喚") |

**日本語名→glyph ID対応表（本タスクで検証した10ノード分）**

| 日本語表記 | glyph ID | glyphs.yml行 | tier |
|---|---|---|---|
| 焦熱 | `scorch` | 953 | 2 |
| 雷撃 | `lightning` | 1266 | 3 |
| 凍裂 | `cold_snap` | 975 | 2 |
| 拘束 | `snare` | 638 | 3 |
| 烈風 | `windshear` | 995 | 2 |
| 引寄 | `pull` | 349 | 2 |
| 狼召喚 | `summon_wolves` | 738 | 2 |
| 生命化 | `animate` | 1098 | 2 |
| 不死召喚 | `summon_undead` | 1385 | 3 |
| 囮召喚 | `summon_decoy` | 1435 | 3 |
| 妖精召喚 | `summon_vex` | 1409 | 3 |
| (参考)害悪 | `harm` | 245 | 1 (既存だがTF側で一度も`glyph:`配置されていない) |

`glyph:`ゲートの配線経路は`DedicatedEffectsConfig.glyphGatePerks()`(`TrinityForge/src/main/java/com/trinityforge/config/domains/DedicatedEffectsConfig.java:44-47`)→fork側`TrinityForgeBridge.java:1062`で参照されており、glyphs.ymlに存在するIDであれば任意のIDを新規に`glyph:<id>`として配置してよい汎用機構であることを確認した。

### digging

| ノード | 効果文言 | 分類 | 対応方法 / 不足しているもの |
|---|---|---|---|
| A | 破壊で少量のバニラEXP | 【設】 | `dedicated-effects: - id: "feature:break-vanilla-exp"` — `FeatureEffectRegistry.java:46`に登録済み、消費側は`NativeSkillExperienceListener.grantBreakVanillaExp`(`listeners/NativeSkillExperienceListener.java:116-125`)。**ただし現状16スキルツリー全体でこのidを配置しているノードは1つも無い**（§5参照）ため、まずこのノードで基点として配置する必要がある。 |
| C | 破壊で少量の追加バニラEXP | 【設】 | `buffs: { break_vanilla_exp_bonus: 0.3 }`(値は任意) — `break_vanilla_exp_bonus`は`StatVocabulary.java:85`にGENERALキーとして登録済み、`grantBreakVanillaExp`が`totals.totalOf(VANILLA_EXP_BONUS) + totals.totalOf(BREAK_VANILLA_EXP_BONUS)`として連続値で加算消費する(`NativeSkillExperienceListener.java:119-121`)。「少量の追加」なので小さい値(例0.2〜0.3)を推奨。Aのfeatureフラグが同ツリーに配置されている前提。 |
| C-1 | 消費したシャベルの耐久値の総量に応じてバニラ経験値の取得量がアップ 最大50% | 【無】 | 「耐久値消費の累積量」をプレイヤー単位で追跡し上限付きでEXP倍率へ変換する機構が存在しない。似た名前の`durability_tools_exp_multiplier_stack`/`durability_armors_exp_multiplier_stack`(`NativeSkillCatalog.java:193-196`)は**SMITHING**スキル自身のEXP計算専用レート（`PlayerItemDamageEvent`起点、`NativeSkillExperienceListener.onItemDamage`）であり、DIGGING/バニラEXPには一切使われていない別物。要: シャベル耐久消費量トラッカー(PDCまたはインメモリ)+上限50%キャップ付きEXP倍率consumer新設。 |
| C-2 | 消費したシャベルの耐久値の総量に応じて職業経験値の取得量がアップ 最大25% | 【無】 | C-1と同じ理由（対象がスキルEXPに変わるだけで機構自体が丸ごと無い）。 |

### enchanting

| ノード | 効果文言 | 分類 | 対応方法 / 不足しているもの |
|---|---|---|---|
| A | エンチャントポイント+10・エンチャントEXPの減少 | 【無】/【判】 | 「エンチャントポイント」という概念がコード全体に一切存在しない（`エンチャントポイント`/`EnchantPoint`/`enchant_point`等で全文検索し0件）。獲得・消費・上限のいずれの仕組みも無い。**要ユーザー確認**: そもそも何に使う資源か(エンチャ本の所持上限？特定エンチャの解放コスト？)が未定義のため設計要。「エンチャントEXPの減少」も、既存の`enchant.level_cost_multiplier`(`NativeSkillCatalog.java:240`)はグローバル設定値であり、ノード単位でプレイヤーごとに増減させるstat consumerが存在しない。 |
| C | エンチャントEXPの追加減少・エンチャントポイント+30 | 【無】/【判】 | Aと同じ理由。 |
| A-alpha-1 | エンチャントポイント+30・エンチャントEXPの増加 | 【無】/【判】 | Aと同じ理由。 |
| A-alpha-2 | エンチャントポイント+30・エンチャントEXPの増加 | 【無】/【判】 | Aと同じ理由。 |
| A-beta-1 | エンチャントポイント+10・エンチャントEXPの減少 | 【無】/【判】 | Aと同じ理由。 |
| A-beta-2 | エンチャントポイント+10・エンチャントEXPの減少 | 【無】/【判】 | Aと同じ理由。 |

### farming

| ノード | 効果文言 | 分類 | 対応方法 / 不足しているもの |
|---|---|---|---|
| C | 収穫量UP・バニラEXPを追加で獲得 | 【設】(※要前提配線) | `buffs: { harvest_extra_drop_chance: 0.2, break_vanilla_exp_bonus: 0.2 }` — 前者は`GatheringExtraDropListener`(`listeners/GatheringExtraDropListener.java:29-63`)が成熟作物ブロック破壊時に消費、後者は`grantBreakVanillaExp`が消費。ただし**farming.ymlのどのノードにも`feature:break-vanilla-exp`が配置されておらず**、EXP側は現状無効。§5参照。 |
| A-1 | 繁殖時低確率で2倍 | 【設】 | `buffs: { breeding_extra_child_chance: 0.1 }` — `BreedingBonusListener.onBreed`(`listeners/BreedingBonusListener.java:56-60`)が確率で同種の子を1体追加スポーンする、まさに「低確率で2倍」の実装。 |
| A-2 | 動物の成長速度UP・繁殖時バニラEXPを獲得 | 【設】 | `buffs: { bred_animal_growth_bonus: 0.2, breeding_vanilla_exp_bonus: 0.3 }` — 前者は`applyGrowthBonus`(同ファイル63-74行, 子の成長タイマー短縮、上限90%)、後者は`onBreed`48-54行のバニラEXP付与に対応。 |
| A-alpha-2 | ゴミの満腹度回復量UP・ゴミ以外の満腹度回復量を戻す | 【無】 | `food_restore_bonus`(`FoodBonusListener.java`)は全食料に一律適用される加算式ボーナスで、ゴミ食/非ゴミ食を区別する分岐が無い。A-alpha-1の`junkfood-inversion`はフラグ(ON/OFF)でグローバル設定値(`food-gimmick.yml`)を使うのみ、本ノードのようにノード単位で「ゴミ食のみさらに強化」を段階的に調整するstatが存在しない。 |
| A-beta-2 | 満腹度回復量UP | 【設】 | `buffs: { food_restore_bonus: 0.15 }` — `FoodBonusListener.onFoodChange`(`listeners/FoodBonusListener.java:32-43`)が食料摂取時の満腹度回復量を割増する、条件分岐なしの汎用consumer。本ノードの文言と完全一致。 |

### fishing

| ノード | 効果文言 | 分類 | 対応方法 / 不足しているもの |
|---|---|---|---|
| A | 同時ヒット確率アップ・得られる経験値量アップ | 【設】 | `buffs: { fishing_bonus: 0.1, skill_exp_bonus: 0.05 }` — `fishing_bonus`は`FishingQualityListener.dropFishingBonus`(`listeners/FishingQualityListener.java:163-179`)が非装備釣果に対し確率で「もう1匹分」を追加ドロップする実装（＝実質的な同時ヒット）。`skill_exp_bonus`は`TrinityForge.java:210-222`で全スキルEXP付与(FISHING含む)に一律乗算される。 |
| C | 同時ヒット確率アップ・得られる経験値量アップ | 【設】 | Aと同じ。`buffs: { fishing_bonus: 0.15, skill_exp_bonus: 0.05 }`程度。 |
| C-1 | 海で釣ると同時ヒット確率アップ | 【判】 | `fishing_bonus`は存在するが、消費側`dropFishingBonus`にバイオーム/水域判定が一切無く、常に一律適用される。「海限定」という条件を再現できない。ユーザー確認: 条件無視で通常のfishing_bonus加算にしてよいか、海限定ロジックを新設するか。 |
| C-2 | 海で釣ると同時ヒット確率アップ | 【判】 | C-1と同じ理由。 |
| B-alpha-1 | 宝が釣れなくなりゴミが釣れるが魚が売れるようになる | 【無】 | 該当する`fish-sell-toggle`は**恒久的にno-op化済み**と明記されている(`FishingGimmickListener.java:191`コメント「fish-sell-toggle stays the permanent no-op...no currency/sell mechanism exists yet」)。魚の売却/通貨系統がTFに一切存在しないため実装不可能。要: 経済システム自体の新設（本タスクの範囲外の規模）。 |
| B-alpha-3 | スクラップの変換効率と魚の売却価格がアップ | 【無】 | 同上。売却価格という概念自体が存在しない。 |
| B-beta-2 | 宝確率アップ・釣り竿の品質に応じてさらに確率アップ | 【設】 | `buffs: { fishing_luck: 0.1 }` — `FishingGimmickListener.luckTotalOf`(`listeners/FishingGimmickListener.java:166-174`)が`fishing_luck`(GENERALキー、`StatVocabulary.java:69`)をロッドの合算値として読む。「釣り竿の品質に応じて」の部分は、ロッドのアイテム品質ロールが既存の`aggregator.aggregate(player, rod)`経由で自動的に折り込まれる既存機構なので追加実装不要。 |

### mining

| ノード | 効果文言 | 分類 | 対応方法 / 不足しているもの |
|---|---|---|---|
| A | 破壊で少量のバニラEXP | 【設】 | `dedicated-effects: - id: "feature:break-vanilla-exp"` — digging Aと同一機構。現状mining.ymlにも配置ノードが無い（§5）。 |
| C | 破壊で2倍のバニラEXPを獲得 | 【設】 | `buffs: { break_vanilla_exp_bonus: 1.0 }` — `amount = BASE_BREAK_EXP * (1.0 + bonus)`(`NativeSkillExperienceListener.java:121`)なので`bonus=1.0`で正確に「2倍」を表現できる。連続値パラメータのため段階(I/II/III)表現も可能（§4-5参照）。 |

### smithing

| ノード | 効果文言 | 分類 | 対応方法 / 不足しているもの |
|---|---|---|---|
| A-1 | 精錬速度+10% | 【無】 | かまど(`Furnace`/`BlastFurnace`/`Smoker`)関連のイベント(`FurnaceBurnEvent`/`FurnaceSmeltEvent`等)を扱うリスナーがコード全体に1つも存在しない（`listeners/`配下全文検索で"furnace"/"smelt"/"cook"系イベント0件、`CraftQualityService`等でヒットした"smelt"はars_magicのグリフ名"精錬"魔法のみで無関係）。要: 精錬速度スタット新設+`FurnaceBurnEvent`(cookTimeTotal操作)向け新規リスナー。 |
| A-2 | 精錬速度+20% | 【無】 | 同上。 |
| A-3 | 精錬速度+30% | 【無】 | 同上。 |
| B-1 | 鉱石の精錬ボーナス+10% | 【無】 | 精錬結果の産出量/追加ドロップを操作する機構が存在しない（`FurnaceSmeltEvent`のリスナー自体が無い）。要: 新規stat+`FurnaceExtractEvent`等向けリスナー。 |
| B-2 | 鉱石の精錬ボーナス+20% | 【無】 | 同上。 |
| B-3 | 鉱石の精錬ボーナス+30% | 【無】 | 同上。 |

### woodcutting

| ノード | 効果文言 | 分類 | 対応方法 / 不足しているもの |
|---|---|---|---|
| A | 破壊で少量のバニラEXP | 【設】 | `dedicated-effects: - id: "feature:break-vanilla-exp"` — mining/digging Aと同一機構。現状woodcutting.ymlにも配置ノードが無い（§5）。 |
| C | 破壊で2倍のバニラEXPを獲得 | 【設】 | `buffs: { break_vanilla_exp_bonus: 1.0 }` — mining Cと同一ロジック。 |

---

## 3. 【機構なし】18件の実装ボリューム見積もり

| 対象 | 規模 | 理由 |
|---|---|---|
| alchemy A/B-alpha-2 (品質+1・醸造速度UP、計2ノード分の概念) | 中 | 「ポーション品質」という新概念のゲームデザイン+`BrewEvent`結果への数値反映(効果時間/強度の再計算)+速度は`BrewingStand`の`brewingTime`をリフレクション/NMSなしで操作する手段の調査が必要。既存の装備品質システム(`CraftQualityService`)とは全く別軸。 |
| alchemy B-beta-1 (残留時間UP・スプラッシュ強度UP) | 小〜中 | `BrewEvent#getResults()`のPotionMeta(base potion type / custom effects)を書き換える新規リスナー1本。ゲームバランス設計は別途必要。 |
| ars_magic B-3 (害悪強化) | 小 | 「特定グリフの威力を割増する」stat語彙1件+fork側`HarmEffect.java`のダメージ計算箇所へのTF stat参照フック追加。他グリフへの汎用化を視野に入れるなら中規模。 |
| digging C-1/C-2 (シャベル耐久消費→EXP倍率、上限50%/25%) | 中 | プレイヤー単位の累積耐久消費量トラッカー新設(PDCまたはDB永続化要検討、ログアウトを跨ぐか等の仕様確定が必要)+上限キャップ付き倍率適用consumer。既存の`onItemDamage`(SMITHING専用)とは別経路が必要。 |
| enchanting A/C/A-alpha-1/2/A-beta-1/2 (エンチャントポイント6ノード分) | 大 | 「エンチャントポイント」というプレイヤー資源そのものが未定義。何に使うか(エンチャ本所持上限？特定エンチャの解放？)のゲームデザインから必要なため、コード量以前に要件確定が最優先。「エンチャントEXPの増減」もノード単位でプレイヤーごとに変動する新機構が必要。 |
| farming A-alpha-2 (ゴミ食の食事量差別化) | 小 | `food_restore_bonus`のゴミ食限定変種、または既存`food-gimmick.yml`の`junkfood-inversion`値をノードから段階的に上乗せする仕組みの新設。 |
| fishing B-alpha-1/B-alpha-3 (魚売却・スクラップ経済) | 大 | 通貨/経済システム自体が存在しない。Vault連携や独自通貨の新設を伴う規模で、本タスクの他の欠落とは一線を画す。 |
| smithing A-1〜A-3/B-1〜B-3 (精錬速度・精錬ボーナス、計6ノード) | 中 | かまど系イベント(`FurnaceBurnEvent`/`FurnaceSmeltEvent`/`FurnaceExtractEvent`)を扱うリスナーが1つも無い状態からの新規実装。速度短縮とドロップ増加で2種のconsumerが必要だが、既存の`GatheringExtraDropListener`等と同型のパターンで実装できるため個々の技術難度は低い。 |

---

## 4. 論点1〜5への回答

**論点1(ars_magicのglyph突き合わせ)**: 10ノードのうち9ノード(B-1-1/B-1-2/B-2-1/B-2-2/B-3-1/B-3-2/C-4-1/C-4-2/C-4-3)は、対応する日本語名のグリフがすべて`glyphs.yml`に実在した（対応表は§2参照）。**唯一B-3「害悪強化」だけは性質が異なり**、これは新規グリフ解放ではなく既存グリフ(`harm`)の威力ブーストを指すため、glyph突き合わせの対象外＝【機構なし】と判定した。

**論点2(smithing精錬6ノード)**: かまど関連のリスナーはコード全体に1つも存在しない（`furnace`/`smelt`/`cook`イベントで全文検索し、ヒットしたのは無関係なグリフ名"精錬"1件のみ）。6ノード全て【機構なし】。

**論点3(enchanting 6ノード)**: 「エンチャントポイント」はコード上のどこにも定義がない（全文検索0件）。唯一「消費経験値レベルに対する倍率」を意味する`enchant.level_cost_multiplier`(`NativeSkillCatalog.java:240`)はカタログyml側のグローバル設定値であり、プレイヤーごと/ノードごとに増減させる仕組みではない。`OverEnchantListener`(`listeners/OverEnchantListener.java`)は`overenchant:<id>`プロファイルによるエンチャレベル上限突破機構であり、「エンチャントポイント」とは無関係の別機構だった。6ノード全て【機構なし/要判断】(資源そのものの設計が先に必要)。

**論点4(fishing「スクラップ経済」「同時ヒット確率」)**: `FishingGimmickConfig`/`fishing-gimmick.yml`には`junk-to-scrap`変換とtreasure/junk比率の設定は存在するが、**売却価格やスクラップ変換効率という経済パラメータは存在しない**（`fish-sell-toggle`は恒久的no-op化済みとコード内コメントで明記、`listeners/FishingGimmickListener.java:191`）。一方「同時ヒット確率」は`fishing_bonus`という既存stat(＝非装備釣果への追加ドロップ)で近似的に表現可能であることを確認した。

**論点5(「破壊で〜バニラEXP」系の倍率パラメータ)**: `feature:break-vanilla-exp`は`FeatureEffectRegistry`に実装済みで、消費側`grantBreakVanillaExp`は`vanilla_exp_bonus`+`break_vanilla_exp_bonus`という**連続値(double)のstat**を`amount = BASE * (1.0 + bonus)`という式で加算する(`NativeSkillExperienceListener.java:116-125`)。ON/OFFのみではなく、`bonus=1.0`で正確に「2倍」、小さい値で「少量」を表現できるため、**I/II/IIIの段階差も表現可能**（ON/OFF固定ではない）。ただし後述§5の通り、**この機能フラグ自体が現在どのスキルツリーにも1つも配置されていない**ため、mining/woodcutting/digging/farmingの「破壊/収穫でバニラEXP」系ノードは全て現状無効。

---

## 5. 47ノード以外で見つかった配線漏れ・矛盾

1. **`feature:break-vanilla-exp`が全16スキルツリーのどこにも配置されていない（重大）**。`FeatureEffectRegistry.java:46`に登録され、`NativeSkillExperienceListener.grantBreakVanillaExp`(116-125行)が実装済みの消費側を持つにもかかわらず、`grep -r "break-vanilla-exp" skilltree/*.yml`は0件。このため、mining/woodcutting/digging/farmingの4ツリーに存在する「破壊(収穫)で少量のバニラEXPを獲得」という基点ノード（mining A, woodcutting A, digging A, farming A）は、テキスト上は書かれていても現状バニラEXPを一切付与していない。今回の47件のうちmining A/C・woodcutting A/C・digging A/Cが「設定のみ」と判定できるのは、Aノードに`feature:break-vanilla-exp`を新規に書き足すことで初めて成立する前提付きである。

2. **farming Aの`dedicated-effects`が`feature:auto-replant`のみで、`feature:break-vanilla-exp`が無い**。farming Aのeffect-textは「植え直しと収穫が同時に可能・**バニラEXPを収穫時に獲得**」と明記しているが、後半のEXP部分に対応する機能フラグ配置が欠落している。farming C（本タスク対象）の「バニラEXPを追加で獲得」もこの土台が無い限り機能しない。

3. **fishing B-beta-1のコメントに「fish-sell-toggleは廃止済み」との記載があるが、B-alpha-1のeffect-textは「魚が売れるようになる」という同種の売却機構への言及を含んだまま残っている**。設計判断としてfish-sell-toggleがno-op化された経緯（`FishingGimmickListener.java:191`のコメント）を踏まえると、B-alpha-1/B-alpha-3の「売却」文言自体がスライド設計時点の古い構想の名残である可能性が高く、テキスト自体の見直し（売却以外の代替効果への差し替え）をユーザーに確認した方がよい。

4. **`digging C-1/C-2`と`smithing`の`durability_tools_exp_multiplier_stack`/`durability_armors_exp_multiplier_stack`は名前が非常に似ているが完全に別物**。前者はDIGGING/バニラEXP向けの構想（未実装）、後者はSMITHINGスキル自身のEXPをitem damage量から計算する既存の実装済み機構（`NativeSkillExperienceListener.onItemDamage`, 314-325行）。実装時に混同しないよう注意が必要。

5. **`ingredient_save_chance`はGENERALキーとして`StatVocabulary`に登録されているが、実際の消費者はArsPaperフォーク側の儀式/スペル素材コストのみで、TrinityForge本体のバニラ`BrewEvent`には一切配線されていない**（`TrinityForge.java:1091`のコメントで「fork consumer系」と明記）。alchemy Dノード（本タスク対象外・既存配線）が同キーを使っているが、これも実質フォーク側専用と考えられ、alchemy B/B-alpha-1（本タスク対象）に同キーを使う場合は「フォークの儀式クラフトにのみ効く」という限定的な効果になる可能性がある点をユーザーに確認すべき。
