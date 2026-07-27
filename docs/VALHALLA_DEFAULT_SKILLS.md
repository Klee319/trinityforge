# ValhallaMMO バニラ・デフォルト 戦闘スキルツリー転写

ValhallaMMO バニラ・デフォルトのスキルツリー転写（戦闘職5種）。出典: Athlaeos/ValhallaMMO master core/src/main/resources/skills/。本書は無改変の転写であり、バランス調整は別途。

> **⚠️ 2026-07-27 追記 — 本書に出てくる次のキーは TF にはもう存在しません。**
> `daily_limit` / `daily_limit_decay_percent` / `daily_limit_warning` / `pvp_multiplier` /
> `is_chunk_nerfed` / `spawner_spawned_multiplier` / `max_health_limitation` /
> `durability_chunk_limit` / `diminishing_returns` / `mace_exp_multiplier` / `infinity_multiplier`
> の11種は、TF 側で読む実装が一つも無い死にデータだったため 2026-07-26 に
> `skills/base/*_progression.yml` から**物理的に除去**しました（66件/15ファイル）。
> 本書は「ValhallaMMO 上流の無改変転写」という性格上そのまま残していますが、
> **TF の現行挙動の説明として読まないでください**。TF の実挙動は
> `docs/config-reference/` 以下と各 yml のコメントが一次情報です。
> なお PvP のダメージ抑制は、Valhalla の `pvp_multiplier`（EXP倍率）とはまったく別物として
> `combat/damage.yml` の `pvp:` に新設されています（2026-07-27）。

表記メモ:
- 表示名/説明は `languages/en-us.json`（en-us）で解決。Minecraftのカラーコード（`&7` 等）は除去して転写。
- `*_add` は加算的なステータス補正、`*_toggle` は機能のON、`recipes_unlock` はレシピ解放、`add_immune_effect` はポーション効果耐性付与。
- `cost` はスキルポイント消費数、`required_lv` は必要スキルレベル、`requires` は前提パーク（ツリー接続）。
- 全スキル共通: `max_level: 100`、`exp_level_curve: (%level% + 75 * 2^(%level%/7.6)) + 300`、`pvp_multiplier: 0.1`、`is_chunk_nerfed: true`、`daily_limit: -1`（無制限）、`spawner_spawned_multiplier: 0.7`（該当スキル）。
- `starting_perks` = レベル0時点で課されるベースのハンディキャップ（マイナス補正）。`leveling_perks` = レベルごとに自動加算される補正。
- `coords` はツリー画面上の座標（依存構造の把握用に併記）。NG（New Game+）パークは `hidden: true`、`cost: 0`、レベル100到達でスキルをリセットして恒久ボーナスを得る転生パーク。

---

## Light Weapons（軽量武器）

### Overview
- 表示名: `Light Weapons` / アイコン: `IRON_SWORD` (icon_data 3510001)
- 説明: 重装甲には効きにくいが攻撃が速く、熟練すれば攻撃を受け流せる。
- レベルバー: 色 `YELLOW` / スタイル `SEGMENTED_6`
- EXP獲得: 軽量武器による近接ダメージ1ポイントあたり `exp_per_damage: 7`。メイス使用時 `mace_exp_multiplier: 0.1`、スポナー湧き `0.7`、PvP `0.1`。
- 武器コーティング可能アイテム（base yml）: POTION / SPLASH_POTION / LINGERING_POTION / SNOWBALL
- starting_perks（ベース handicap）: damagemultiplier -0.3、knockbackmultiplier -0.3、immunityreductionfraction +0.2
- leveling_perks（毎レベル）: attackspeedmultiplier +0.005、damagemultiplier +0.005

### Perk tree
メイン直列: perk_1 → perk_2 → perk_3 → perk_4 → perk_5 → perk_6。コーティング枝: perk_3 → a1 → a2。combo/ng は独立。

| Perk ID | 表示名 | required_lv | cost | requires | 種別 | 効果（無改変） |
|---|---|---|---|---|---|---|
| lightweapons_perk_1 | Page | 0 | 1 | — | passive | damagemultiplier +0.1、bleedchance +0.1 |
| lightweapons_perk_2 | Squire | 20 | 1 | perk_1 | passive | attackspeedmultiplier +0.1、critchance +0.1 |
| lightweapons_perk_3 | Knight | 40 | 1 | perk_2 | unlock(Parry)+passive | damagemultiplier +0.1。Parry解放: parryeffectiveduration +7、parryvulnerableduration +14、parrycooldown +100、parryenemydebuffduration +30、parryselfdebuffduration +30、parrydamagereduction +0.75、parrycooldownsuccessreduction +0.5 |
| lightweapons_perk_4 | Paladin | 60 | 1 | perk_3 | passive | attackspeedmultiplier +0.2、immunityreductionfraction +0.1、bleedchance +0.1 |
| lightweapons_perk_5 | Templar | 80 | 1 | perk_4 | passive | damagemultiplier +0.1、bleedchance +0.1、immunityreductionfraction +0.2。Parry強化: parryeffectiveduration +7、parryvulnerableduration +1、parrycooldown -60、parryenemydebuffduration +10、parryselfdebuffduration -10、parrydamagereduction +0.25、parrycooldownsuccessreduction +0.5 |
| lightweapons_perk_6 | Warlord | 100 | 1 | perk_5 | passive | critchance +0.1、critdamage +0.3、bleedoncrit(toggle ON)、bleeddamage +2 |
| lightweapons_perk_a1 | Weapon Coating | 50 | 1 | perk_3 | unlock(Coating) | coatingunlocked(toggle ON)、coatingcharges +3、coatingdurationmultiplier -0.8、coatingamplifiermultiplier -0.5 |
| lightweapons_perk_a2 | Bioweapon | 70 | 1 | a1 | passive | coatingcharges +4、coatingdurationmultiplier +0.1、coatingamplifiermultiplier +0.25 |
| lightweapons_perk_combo | Titan | 70 | 1 | （他系統 HEAVY_ARMOR:70） | passive | penetrationflat +10、attackreachbonus +1、knockbackmultiplier +0.3 |
| lightweapons_perk_ng1 | New Game I | 100 | 0 | — (hidden) | 転生 | スキルリセット。p:newgameplus +1、p:damagemultiplier +0.15、p:attackspeedmultiplier +0.2、p:critchance +0.1、p:expmultiplier -0.5（恒久） |
| lightweapons_perk_ng2 | New Game II | 100 | 0 | ng1 (hidden) | 転生 | スキルリセット。p:newgameplus +1、p:damagemultiplier +0.15、p:attackspeedmultiplier +0.2、p:critchance +0.1、p:expmultiplier -0.17（恒久） |

説明テキスト（en-us 要約）: Page=ダメージ増・出血付与 / Squire=攻撃速度・クリ率 / Knight=Parry解放 / Paladin=攻撃速度・無敵時間短縮・出血 / Templar=Parryが大幅に容易かつ強力に / Warlord=クリティカルで出血、出血ダメ増 / Weapon Coating=武器にポーション効果を塗布（強度・持続減、{coatingcharges}ヒット持続） / Bioweapon=コーティング強化 / Titan=貫通・ノックバック・リーチ。

---

## Heavy Weapons（重量武器）

### Overview
- 表示名: `Heavy Weapons` / アイコン: `IRON_AXE` (icon_data 3510001)
- 説明: 攻撃を受け流すのは苦手だが一撃が重く、装甲に強い。
- レベルバー: 色 `RED` / スタイル `SEGMENTED_6`
- EXP獲得: 重量武器による近接ダメージ1ポイントあたり `exp_per_damage: 10`。メイス `0.1`、スポナー `0.7`、PvP `0.1`。
- コーティング可能アイテム: POTION / SPLASH_POTION / LINGERING_POTION / SNOWBALL
- starting_perks: attackspeedmultiplier -0.3、damagemultiplier -0.3
- leveling_perks（毎レベル）: attackspeedmultiplier +0.005、damagemultiplier +0.005

### Perk tree
メイン直列: perk_1 → 2 → 3 → 4 → 5 → 6。コーティング枝: perk_3 → a1 → a2。combo/ng は独立。

| Perk ID | 表示名 | required_lv | cost | requires | 種別 | 効果（無改変） |
|---|---|---|---|---|---|---|
| heavyweapons_perk_1 | Savage | 0 | 1 | — | passive | damagemultiplier +0.1 |
| heavyweapons_perk_2 | Brute | 20 | 1 | perk_1 | passive | powerattackdamagemultiplier +0.1、critchance +0.1 |
| heavyweapons_perk_3 | Barbarian | 40 | 1 | perk_2 | passive | damagemultiplier +0.1、powerattackfraction +0.5、powerattackradius +1.5（パワーアタックが範囲スプラッシュ化） |
| heavyweapons_perk_4 | Viking | 60 | 1 | perk_3 | passive | powerattackdamagemultiplier +0.15、penetrationflat +5 |
| heavyweapons_perk_5 | Berserker | 80 | 1 | perk_4 | passive | damagemultiplier +0.1、powerattackfraction +0.25、powerattackradius +0.5、penetrationflat +10 |
| heavyweapons_perk_6 | Deathbringer | 100 | 1 | perk_5 | passive | critchance +0.1、critdamage +0.3、bleedoncrit(toggle ON)、bleeddamage +1 |
| heavyweapons_perk_a1 | Weapon Coating | 50 | 1 | perk_3 | unlock(Coating) | coatingunlocked(toggle ON)、coatingcharges +2、coatingdurationmultiplier -0.8、coatingamplifiermultiplier -0.25 |
| heavyweapons_perk_a2 | Toxic Steel | 70 | 1 | a1 | passive | coatingcharges +2、coatingdurationmultiplier +0.1、coatingamplifiermultiplier +0.25 |
| heavyweapons_perk_combo | Shattering Blows | 70 | 1 | （他系統 WOODCUTTING:70） | passive | stunchance +0.2、penetrationfraction +0.2、damagetolightarmormultiplier +0.05（敵の軽装甲1部位ごとに増ダメ） |
| heavyweapons_perk_ng1 | New Game I | 100 | 0 | — (hidden) | 転生 | スキルリセット。p:newgameplus +1、p:damagemultiplier +0.15、p:powerattackfraction +0.2、p:critchance +0.1、p:expmultiplier -0.5（恒久） |
| heavyweapons_perk_ng2 | New Game II | 100 | 0 | ng1 (hidden) | 転生 | スキルリセット。p:newgameplus +1、p:damagemultiplier +0.15、p:powerattackfraction +0.2、p:critchance +0.1、p:expmultiplier -0.17（恒久）。※ソースの perks_unlocked_remove は `lightarmor_perk_ng2` を参照（原文ママ） |

---

## Archery（弓術）

### Overview
- 表示名: `Archery` / アイコン: `BOW` (icon_data 3510001)
- 説明: 遠隔ダメージ・命中を伸ばし、安全圏から確実にキル。習得は長いが戦闘スキル中最強格。
- レベルバー: 色 `YELLOW` / スタイル `SEGMENTED_6`
- EXP獲得: 弓ヒット基礎 `bow_exp_base: 30`、クロスボウ `crossbow_exp_base: 40`。`damage_exp_bonus: 0.1`（ダメージ1点ごと+10%）、距離倍率 base `1` + `0.75`/10ブロック（上限 `distance_limit: 100`）、Infinity使用時 `infinity_multiplier: 0.7`、スポナー `0.7`、PvP `0.1`。
- ダメージ式（base yml）: 通常 `sqrt(%velocity%) * %basedamage% * (1 + (0.25 * %power%))`、クリティカル `%normaldamage% * 1.3`、貫通減衰 `damage_piercing_reduction: 0.2`、距離ダメ上限 `distance_limit: 50`、収穫逓減 `diminishing_returns_limit: 30` / `multiplier: 0.2`。
- starting_perks: inaccuracy +5、bowdamagemultiplier -0.2、distancedamagebase -0.2、distancedamagebonus +0.1、infinitydamagemultiplier -0.3
- leveling_perks（毎レベル）: bowdamagemultiplier +0.004

### Perk tree
メイン直列: perk_1 → 2 → 3 → 4 → 5 → 6。チャージショット枝: perk_2 → c1 → c2。stealth枝: perk_3（requireperk_one）。ng は独立。

| Perk ID | 表示名 | required_lv | cost | requires | 種別 | 効果（無改変） |
|---|---|---|---|---|---|---|
| archery_perk_1 | Ranger | 0 | 1 | — | passive | inaccuracy -1、bowdamagemultiplier +0.1、crossbowdamagemultiplier +0.1 |
| archery_perk_2 | Resourceful Hunter | 20 | 1 | perk_1 | unlock(recipe)+passive | ammosavechance +0.1、recipes_unlock: craft_stone_arrows |
| archery_perk_3 | Archer | 40 | 1 | perk_2 | unlock(recipe)+passive | inaccuracy -1.5、bowcritchance +0.1、crossbowcritchance +0.1、recipes_unlock: craft_copper_arrows / craft_golden_arrows |
| archery_perk_4 | Long-Ranged Infantry | 60 | 1 | perk_3 | unlock(recipe)+passive | bowdamagemultiplier +0.1、crossbowdamagemultiplier +0.1、ammosavechance +0.2、distancedamagebonus +0.05、recipes_unlock: craft_iron_arrows / craft_teleport_arrows |
| archery_perk_5 | Sniper | 80 | 1 | perk_4 | unlock(recipe)+passive | bowcritchance +0.2、crossbowcritchance +0.2、inaccuracy -2.5、critdamage +0.3、recipes_unlock: craft_diamond_arrows |
| archery_perk_6 | Necrotic Shots | 100 | 1 | perk_5 | unlock(recipe)+passive | distancedamagebonus +0.1、infinitydamagemultiplier +0.3、recipes_unlock: craft_netherite_arrows / craft_removeimmunity_arrows |
| archery_perk_c1 | Charged Shot I | 40 | 1 | perk_2 | unlock(Charged Shot) | chargedshotunlocked(toggle ON)、chargedshotcooldown +600、chargedshotcharges +3、chargedshotpiercing +1、chargedshotknockback +1、chargedshotaccuracy +10、chargedshotvelocitybonus +0.5 |
| archery_perk_c2 | Charged Shot II | 80 | 1 | c1 | active強化 | chargedshotfullvelocity(toggle ON)、chargedshotcrossbowinstantreload(toggle ON)、chargedshotnogravity(toggle ON)、chargedshotdamagemultiplier +0.2、chargedshotcooldown -200、chargedshotcharges +2、chargedshotpiercing +2、chargedshotknockback +1 |
| archery_perk_stealth | Assassin | 50 | 1 | requireperk_one: perk_3 ／ 他系統 LIGHT_ARMOR:50 | mechanic | critonstealth(toggle ON)、bleedoncrit(toggle ON)、critdamage +0.5（戦闘外で敵が背を向けていれば確定クリ） |
| archery_perk_ng1 | New Game I | 100 | 0 | — (hidden) | 転生 | スキルリセット。p:newgameplus +1、p:bowdamagemultiplier +0.15、p:crossbowdamagemultiplier +0.15、p:inaccuracy -3、p:bowcritchance +0.1、p:crossbowcritchance +0.1、p:expmultiplier -0.5（恒久） |
| archery_perk_ng2 | New Game II | 100 | 0 | ng1 (hidden) | 転生 | 同上だが p:expmultiplier -0.17（恒久） |

---

## Light Armor（軽装甲）

### Overview
- 表示名: `Light Armor` / アイコン: `LEATHER_CHESTPLATE` (icon_data 3510001)
- 説明: 回避を高め、機動力を犠牲にせず防御。
- レベルバー: 色 `YELLOW` / スタイル `SEGMENTED_6`
- EXP獲得: 与ダメージ1点ごと `exp_damage_piece: 10`、戦闘中は軽装甲1部位×1秒ごと `exp_second_piece: 5`。`exp_multiplier_point: 0.05`（防具ポイント1点ごとにEXP倍率+5%／40ポイントで+200%）。PvP `0.1`。
- Adrenaline効果（base yml、EFFECT;AMP;DURATION;AMP/Lv;DURATION/Lv）: SPEED;1;200;0.5;50 / JUMP;1;200;0.5;50 / REGENERATION;1;200;0.5;50 / DAMAGE_RESISTANCE;0;200;0.25;50
- starting_perks: lightarmormultiplier -0.3、movementspeedperpiece -0.025、hungersavechanceperpiece -0.0625、healingbonusperpiece -0.0625、dodgechanceperpiece +0.025
- leveling_perks（毎レベル）: lightarmormultiplier +0.005

### Perk tree
基点 perk_1。枝A: 1 → 1a → 2a。枝B: 1 → 1b → 2b。頂点 perk_2 は 2a と 2b の両方が前提。Adrenaline枝: 1 → 1c → 2c。combo は 1 前提＋他系統。

| Perk ID | 表示名 | required_lv | cost | requires | 種別 | 効果（無改変） |
|---|---|---|---|---|---|---|
| lightarmor_perk_1 | Iron Studs | 0 | 1 | — | passive | lightarmormultiplier +0.1 |
| lightarmor_perk_1a | Unburdened | 20 | 1 | perk_1 | passive | movementspeedperpiece +0.01、hungersavechanceperpiece +0.035、healingbonusperpiece +0.035 |
| lightarmor_perk_2a | Resourceful Scout | 60 | 1 | perk_1a | passive | setamount -1（セットボーナス必要数3に）、movementspeedperpiece +0.015、hungersavechanceperpiece +0.0275、healingbonusperpiece +0.0275 |
| lightarmor_perk_1b | Controlled Metabolism | 40 | 1 | perk_1 | passive(set) | sethungersavechance +0.3、lightarmormultiplier +0.1 |
| lightarmor_perk_2b | Swift Reflexes | 60 | 1 | perk_1b | passive(set) | setdodgechance +0.2、lightarmormultiplier +0.1 |
| lightarmor_perk_2 | Dragonscales | 100 | 1 | perk_2a ＋ perk_2b | passive(set) | setmagicresistance +0.4、add_immune_effect: POISON / BLINDNESS / HUNGER |
| lightarmor_perk_1c | Adrenaline | 50 | 1 | perk_1 | unlock(Adrenaline) | adrenalineunlocked(toggle ON)、adrenalinethreshold +0.3、adrenalinelevel +1、adrenalinecooldown +12000 |
| lightarmor_perk_2c | Bolt | 90 | 1 | perk_1c | active強化 | adrenalinelevel +4、adrenalinethreshold +0.2、adrenalinecooldown -6000 |
| lightarmor_perk_combo | Perfect Composure | 50 | 1 | perk_1 ／ 他系統 HEAVY_WEAPONS:50 | passive(set) | setcritchanceresistance +0.5、setstunresistance +1 |
| lightarmor_perk_ng1 | New Game I | 100 | 0 | — (hidden) | 転生 | スキルリセット。p:newgameplus +1、p:damageresistanceperpiece +0.0125、p:movementspeedperpiece +0.02、p:expmultiplier -0.5（恒久） |
| lightarmor_perk_ng2 | New Game II | 100 | 0 | ng1 (hidden) | 転生 | 同上だが p:expmultiplier -0.17（恒久） |

---

## Heavy Armor（重装甲）

### Overview
- 表示名: `Heavy Armor` / アイコン: `IRON_CHESTPLATE` (icon_data 3510001)
- 説明: 被ダメを大幅に減らし不屈の存在になるが、機動力を多少犠牲にする。
- レベルバー: 色 `RED` / スタイル `SEGMENTED_6`
- EXP獲得: 与ダメージ1点ごと `exp_damage_piece: 10`、戦闘中は重装甲1部位×1秒ごと `exp_second_piece: 5`。`exp_multiplier_point: 0.05`（防具ポイント1点ごと+5%）。PvP `0.1`。
- Rage効果（base yml、EFFECT;AMP;DURATION;AMP/Lv;DURATION/Lv）: INCREASE_DAMAGE;1;200;0.5;50 / FAST_DIGGING;1;200;0.5;50 / ABSORPTION;1;200;0.5;50 / DAMAGE_RESISTANCE;0;200;0.25;50
- starting_perks: heavyarmormultiplier -0.3、movementspeedperpiece -0.05、hungersavechanceperpiece -0.125、healingbonusperpiece -0.125、archery_inaccuracy +3（弓の命中にペナルティ）
- leveling_perks（毎レベル）: heavyarmormultiplier +0.005

### Perk tree
基点 perk_1。枝A: 1 → 1a → 2a。枝B: 1 → 1b → 2b。頂点 perk_2 は 2a と 2b の両方が前提。Rage枝: 1 → 1c → 2c。combo は 1 前提＋他系統。

| Perk ID | 表示名 | required_lv | cost | requires | 種別 | 効果（無改変） |
|---|---|---|---|---|---|---|
| heavyarmor_perk_1 | Fortified Fit | 0 | 1 | — | passive | heavyarmormultiplier +0.1 |
| heavyarmor_perk_1a | Never Skip Leg Day | 20 | 1 | perk_1 | passive | movementspeedperpiece +0.01875、hungersavechanceperpiece +0.025、healingbonusperpiece +0.025、archery_inaccuracy -1 |
| heavyarmor_perk_2a | Innovative Infantry | 60 | 1 | perk_1a | passive | setamount -1（セットボーナス必要数3に）、movementspeedperpiece +0.01875、hungersavechanceperpiece +0.05、healingbonusperpiece +0.05、archery_inaccuracy -1 |
| heavyarmor_perk_1b | Vital Steel | 40 | 1 | perk_1 | passive(set) | sethealingbonus +0.4、heavyarmormultiplier +0.1 |
| heavyarmor_perk_2b | Spiked Suit | 80 | 1 | perk_1b | passive(set) | setreflectchance +0.1、setreflectfraction +0.2（反射）、heavyarmormultiplier +0.1 |
| heavyarmor_perk_2 | Juggernaut | 100 | 1 | perk_2a ＋ perk_2b | passive(set) | setknockbackresistance +0.4、add_immune_effect: SLOW / WEAKNESS / LEVITATION |
| heavyarmor_perk_1c | Rage | 50 | 1 | perk_1 | unlock(Rage) | rageunlocked(toggle ON)、ragelevel +1、ragethreshold +0.3、ragecooldown +12000 |
| heavyarmor_perk_2c | Fury | 90 | 1 | perk_1c | active強化 | ragelevel +4、ragethreshold +0.2、ragecooldown -6000 |
| heavyarmor_perk_combo | Know Your Weaknesses | 50 | 1 | perk_1 ／ 他系統 LIGHT_WEAPONS:50 | passive(set) | setcritchanceresistance +0.5、setimmunityfractionbonus +0.25 |
| heavyarmor_perk_ng1 | New Game I | 100 | 0 | — (hidden) | 転生 | スキルリセット。p:newgameplus +1、p:damageresistanceperpiece +0.01875、p:critchanceresistanceperpiece +0.1、p:expmultiplier -0.5（恒久） |
| heavyarmor_perk_ng2 | New Game II | 100 | 0 | ng1 (hidden) | 転生 | 同上だが p:expmultiplier -0.17（恒久） |

---

# 非戦闘（採取・生産）スキル

以下は非戦闘デフォルトスキル9種（power, mining, digging, woodcutting, farming, fishing, smithing, enchanting, alchemy）の転写。出典は同一（master core/src/main/resources/skills/）。共通仕様（max_level 100 / 同一 exp_level_curve 等）は冒頭の表記メモを参照。**power のみ exp 仕様が特殊**（後述）。`other_levels_required` は他スキルの必要レベル（クロススキル combo パーク）、`*_set` は値の直接セット、`*_toggle`/`_set: true` は機能ON、`recipes_unlock`/`recipes_lock` はレシピ解放/封印、`block_conversions_unlock` はブロック変換解放。NG パークは戦闘職と同形式（`reset_skill_<skill>`、`p:` 接頭辞 = persistent 恒久ボーナス、`hidden:true`/`cost:0`）。

---

## Power（パワー／集約メタスキル）

### Overview
- 表示名: `Power` / アイコン: `ARMOR_STAND` (icon_data 3510001)
- 説明（en-us）: 「他スキルをレベルアップすると Power の EXP を得る。レベルアップでスキルポイントを得て、それを任意の他スキルのパークに使える」
- レベルバー: 色 `YELLOW` / スタイル `SEGMENTED_6`
- **EXP仕様（特殊・重要）**: `max_level: 256`、`exp_level_curve: '(%level%/100) * 1800 + 800'`、`exp_gain: 100`。Power は自前のブロック破壊等で EXP を得ない。ソース `PowerSkill.java` の `onPlayerLevelUp` が、**自分以外の任意スキルがレベルアップしたとき**に `exp_gain(100) × 上昇レベル数` を Power に付与する。つまり Power は他スキルの「レベルアップ回数」を数えるのであり、**他スキルの累積EXPを二重計上しない**（生EXPの合算ではない）。
- starting_perks（ベース）: power_spendableskillpoints_add +3、critdamage +0.5、bleeddamage +2、bleedduration +80、radiantresistance +2、oneshotprotectionfraction +0.1、oneshotprotectioncooldown +6000、recipes_unlock: 木材系基礎レシピ多数（planks/sticks/stairs/fences/fence_gates/doors/trap_doors/signs 各木材 ×80件）
- leveling_perks（毎レベル）: power_spendableskillpoints_add +1（＝1レベルごとに振り分け可能なスキルポイント1点。これが全スキル共通の「ポイント源」）
- special_perks（レベル到達時自動付与）: Lv10/20/30/40/50/60/70/80 各到達で power_pvpresistance_add +0.1（合計 +0.8）
- `navigable: false`（ツリー上を自由移動しない、固定グリッド表示）。starting_coordinates `3,1`

### Perk tree
Power は**自前の独立パークツリーを持つ**（他スキルの派生ではない）。7系統（a〜g）×各3ティア＝21パーク。各系統はティアI（Lv0）→II（Lv30）→III（Lv60）の直列。全パーク cost 1。II・III は `hidden:true`（前ティア取得で出現）。

| Perk ID | 表示名 | required_lv | cost | requires | 種別 | 効果（無改変） |
|---|---|---|---|---|---|---|
| power_perk_1a | Vitality I | 0 | 1 | — | passive | power_healthbonus +2（最大体力） |
| power_perk_2a | Vitality II | 30 | 1 | 1a | passive | power_healthbonus +3 |
| power_perk_3a | Vitality III | 60 | 1 | 2a | passive | power_healthbonus +5 |
| power_perk_1b | Iron Skin I | 0 | 1 | — | passive | power_armorbonus +2（自然防御） |
| power_perk_2b | Iron Skin II | 30 | 1 | 1b | passive | power_armorbonus +3 |
| power_perk_3b | Iron Skin III | 60 | 1 | 2b | passive | power_armorbonus +5 |
| power_perk_1c | Divine Favor I | 0 | 1 | — | passive | power_luckbonus +0.2 |
| power_perk_2c | Divine Favor II | 30 | 1 | 1c | passive | power_luckbonus +0.3 |
| power_perk_3c | Divine Favor III | 60 | 1 | 2c | passive | power_luckbonus +0.5 |
| power_perk_1d | Restoration I | 0 | 1 | — | passive | power_healthregenerationbonus +0.2 |
| power_perk_2d | Restoration II | 30 | 1 | 1d | passive | power_healthregenerationbonus +0.3 |
| power_perk_3d | Restoration III | 60 | 1 | 2d | passive | power_healthregenerationbonus +0.5 |
| power_perk_1e | Physique I | 0 | 1 | — | passive | power_attackdamagemultiplier +0.05（与ダメージ） |
| power_perk_2e | Physique II | 30 | 1 | 1e | passive | power_attackdamagemultiplier +0.075 |
| power_perk_3e | Physique III | 60 | 1 | 2e | passive | power_attackdamagemultiplier +0.125 |
| power_perk_1f | Looter I | 0 | 1 | — | passive | power_entitydropmultiplier +0.2（モブドロップ） |
| power_perk_2f | Looter II | 30 | 1 | 1f | passive | power_entitydropmultiplier +0.3 |
| power_perk_3f | Looter III | 60 | 1 | 2f | passive | power_entitydropmultiplier +0.5 |
| power_perk_1g | Ambition I | 0 | 1 | — | passive | power_cooldownreduction +0.1（アビリティCD減） |
| power_perk_2g | Ambition II | 30 | 1 | 1g | passive | power_cooldownreduction +0.15 |
| power_perk_3g | Ambition III | 60 | 1 | 2g | passive | power_cooldownreduction +0.25 |

※ Power には combo パーク・NG パークは無い。Power は「ポイントの蓄積元＋汎用ステータス7系統」であり、各スキルパークが参照する `power_*` ステータス（cookingspeedbonus, foodbonus*, hungersavechance 等）の格納先プロファイルでもある（他スキルの combo パークが `power_*_add` を加算しているのはこのため）。

---

## Mining（採掘）

### Overview
- 表示名: `Mining` / アイコン: `IRON_PICKAXE` / レベルバー色 `RED` / `SEGMENTED_6`
- EXP獲得: `mining_break` リストのブロック破壊時（設置ブロックは無効）。`exp_multiplier_mine: 1`、`exp_multiplier_blast: 1.5`（爆破採掘1.5倍）、`exp_per_break: false`（ドロップ数基準）、`daily_limit: -1`。主要値（抜粋）: 石/丸石 8、深層岩 12、石炭鉱石40・深層60、鉄鉱石80・深層120、銅32・深層84、金160・深層240、ラピス320・深層240、レッドストーン80・深層120、ダイヤ鉱石400・深層600、エメラルド鉱石400・深層600、古代の残骸1600、ネザライトの欠片800、黒曜石40。
- その他設定（base yml）: `vein_mining_instant: false`、`break_limit_vein_mining: 64`、ドリルアビリティ関連音、`forgiving_multipliers: true`、`remove_tnt_chaining: true`。
- starting_perks: mining_miningdrops_add -0.3
- leveling_perks（毎レベル）: mining_miningdrops_add +0.01、mining_blastingdrops_add +0.01

### Perk tree
直列: perk_1 → perk_2 →（分岐 a枝/b枝）→ perk_3 → perk_4。a枝=TNT爆破、b枝=ドリル/ベインマイニング。perk_3 は a2 **または** b2（requireperk_one）。a3 配下に silktouch/fortune の二者択一サブパーク。

| Perk ID | 表示名 | required_lv | cost | requires | 種別 | 効果（無改変） |
|---|---|---|---|---|---|---|
| mining_perk_1 | Brittle Rock | 0 | 1 | — | passive | miningspeedbonus +0.1、blockexperiencerate +0.1 |
| mining_perk_2 | Spelunking | 20 | 1 | perk_1 | passive | miningdrops +0.1、miningluck +1、power_cookingspeedbonus +0.25 |
| mining_perk_a1 | Demolist | 40 | 1 | perk_2 | passive | tntblastradius +0.5、tntdamagereduction +0.5、blastingdrops +0.2 |
| mining_perk_a2 | Earthshaker | 60 | 1 | a1 | passive+unlock | tntblastradius +0.5、tntdamagereduction +0.5、blastingdrops +0.3、recipes_unlock: tnt_triple |
| mining_perk_a3 | Enchanted Explosives | 70 | 1 | a2 ／ other: ENCHANTING:70 | unlock(選択) | TNTにSilk Touch/Fortune IIを選択付与可能に（下記2サブパークが出現） |
| mining_perk_a3silktouch | …: Silk Touch | — | 0 | a3 (hidden) | toggle(選択) | mining_blastfortunelevel_set -1、perks_unlocked_remove: a3fortune（fortuneと排他） |
| mining_perk_a3fortune | …: Fortune | — | 0 | a3 (hidden) | toggle(選択) | mining_blastfortunelevel_set 2、perks_unlocked_remove: a3silktouch（silktouchと排他） |
| mining_perk_b1 | Dwarven Aptitude | 40 | 1 | perk_2 | passive+unlock | miningspeedbonus +0.2、blockexperiencerate +0.2、drillingunlocked(toggle)、drillingspeedbonus +2、drillingcooldown +1200、drillingduration +200 |
| mining_perk_b2 | Vein Extraction | 60 | 1 | b1 | passive+unlock | miningdrops +0.2、miningluck +3、veinminingunlocked(toggle)、power_cookingspeedbonus +0.25、veinminerblocks_add: 全鉱石（石炭〜エメラルド＋深層各種＋ネザー水晶/金/古代の残骸） |
| mining_perk_3 | Earthsplitter | 80 | 1 | a2 **or** b2 | passive | drillingspeedbonus +3、drillingcooldown -600、blockexperiencemultiplier +1、power_cookingspeedbonus +0.25 |
| mining_perk_4 | Aspect of Kali | 100 | 1 | perk_3 | passive+unlock | veinmininginstantpickup(toggle)、power_cookingspeedbonus +0.25、veinminerblocks_add: 花崗岩/安山岩/閃緑岩/方解石/凝灰岩/黒石/玄武岩/アメジストブロック |
| mining_perk_ng1 | New Game I | 100 | 0 | — (hidden) | 転生 | reset_skill_mining。p:newgameplus +1、p:miningspeedbonus +0.15、p:blastingdrops +0.25、p:miningdrops +0.25、p:miningexpmultiplier -0.5（恒久） |
| mining_perk_ng2 | New Game II | 100 | 0 | ng1 (hidden) | 転生 | 同上だが p:miningexpmultiplier -0.17 |

---

## Digging（掘削）

### Overview
- 表示名: `Digging` / アイコン: `WOODEN_SHOVEL`系 / レベルバー色 `GREEN`（&a/&2）/ `SEGMENTED_6`
- EXP獲得: `digging_break`（土系ブロック破壊、設置物無効）と `archaeology_brush`（考古ブラシ使用時）。digging_break 主要値: 土/砂/赤砂 8、粗い土16、雪ブロック8、ソウルサンド/ソウルソイル16、草ブロック/土の道/菌糸/砂利/ポドゾル16、粘土/泥24、骨25、生鉄/生金/生銅100、エンダーパール200、アメジストの欠片200、ダイヤ/エメラルド500、残響の欠片2000、エンチャント本1000、ネザライトの欠片1000。archaeology_brush: 怪しいシチュー50、各種土器の破片50、エメラルド100、ダイヤ150、スニッファーの卵100 等。
- starting_perks: なし
- leveling_perks（毎レベル）: digging_diggingdrops_add +0.01、digging_diggingspeedbonus_add +0.005

### Perk tree
直列: perk_1 → perk_2 → perk_3 →（a枝=考古/b枝=速度）→ perk_4（a2 **or** b2）。combo は perk_2 前提＋MINING:50。a枝は `version_at_least: MINECRAFT_1_20`。

| Perk ID | 表示名 | required_lv | cost | requires | 種別 | 効果（無改変） |
|---|---|---|---|---|---|---|
| digging_perk_1 | Digger | 0 | 1 | — | passive | diggingspeedbonus +0.3、blockexperiencerate +0.1 |
| digging_perk_2 | Filterer | 20 | 1 | perk_1 | passive | diggingluck +3、blockexperiencerate +0.2 |
| digging_perk_3 | Ground Replication | 40 | 1 | perk_2 | unlock(recipe) | recipes_unlock: ポドゾル/菌糸/火打石/粘土/泥/草/ソウルサンド/ソウルソイル＋コンクリート全16色 |
| digging_perk_a1 | Archaeologist | 60 | 1 | perk_3 (1.20+) | passive | archaeologyrepeatchance +0.2、archaeologyluck +3 |
| digging_perk_a2 | Ancient History | 80 | 1 | a1 (1.20+) | passive | archaeologyrepeatchance +0.3、怪しい砂/砂利の自然生成チャンス +0.0001（構造物付近 +0.001）×砂・砂利 |
| digging_perk_b1 | Excavator | 60 | 1 | perk_3 | passive | diggingspeedbonus +0.5 |
| digging_perk_b2 | Panning | 80 | 1 | b1 | passive | diggingluck +3 |
| digging_perk_4 | Hidden Treasure | 100 | 1 | a2 **or** b2 | passive | diggingluck +3 |
| digging_perk_combo | Flexible Builder | 50 | 1 | perk_2 ／ other: MINING:50 | unlock(変換) | block_conversions_unlock: 苔化(丸石/石/レンガ)、草育成、レンガ/各種磨きブロックのひび割れ化、ネザーラック/砂/生鉱石/水晶/粘土の精錬変換 等 |
| digging_perk_ng1 | New Game I | 100 | 0 | — (hidden) | 転生 | reset_skill_digging。p:newgameplus +1、p:diggingdrops +0.3、p:diggingluck +3、p:diggingspeedbonus +0.25、p:diggingexpmultiplier -0.5（恒久） |
| digging_perk_ng2 | New Game II | 100 | 0 | ng1 (hidden) | 転生 | 同上だが p:diggingexpmultiplier -0.17 |

---

## Woodcutting（伐採）

### Overview
- 表示名: `Woodcutting` / アイコン: `WOODEN_AXE`系 / レベルバー色 `GREEN` / `SEGMENTED_6`
- EXP獲得: `woodcutting_break`（原木/木材破壊。原木40、ネザー幹/木材60、ハイフィ80）と `woodcutting_strip`（斧での樹皮剥ぎ。原木20、木材30、ハイフィ40）。設置物無効。
- starting_perks: なし
- leveling_perks（毎レベル）: woodcutting_woodcuttingspeedbonus_add +0.005、woodcutting_woodcuttingdrops_add +0.01

### Perk tree
直列: perk_1 → perk_2 → perk_3 → perk_4 → perk_5。perk_3 から carpentry枝(建材レシピ)と capitator枝(伐採強化)が分岐。combo は perk_2 前提＋DIGGING:50。

| Perk ID | 表示名 | required_lv | cost | requires | 種別 | 効果（無改変） |
|---|---|---|---|---|---|---|
| woodcutting_perk_1 | Novice Woodcutting | 0 | 1 | — | passive | woodcuttingspeedbonus +0.1、blockexperiencerate +0.1 |
| woodcutting_perk_2 | Golden Leaves | 20 | 1 | perk_1 | passive | blockexperiencerate +0.1、woodcuttingluck +10（葉から金リンゴ稀ドロップ） |
| woodcutting_perk_3 | Deforestation I | 40 | 1 | perk_2 | unlock | treecapitatorunlocked(set true)、treecapitatorcooldown +200、treecapitatorlimit +16、treecapitatorblocks_add_all（小木の一括伐採解放） |
| woodcutting_perk_4 | Arborist | 80 | 1 | perk_3 | passive | blockexperiencerate +0.3、woodcuttingspeedbonus +0.1、instantgrowthrate +2（苗木成長2倍） |
| woodcutting_perk_5 | Crystal Leaves | 100 | 1 | perk_4 | passive | woodcuttingluck +10（葉から宝石リンゴ、金リンゴ頻度増） |
| woodcutting_perk_carpentry_a | （Carpentry） | 60 | 1 | perk_3 | unlock(recipe) | recipes_unlock: 建材の高効率版（planks_*_6/sticks_*_8/stairs_*_8/fences_*_8/doors_*_6 等）＋ recipes_lock: 低効率版（power 初期解放の _4/_3/_1 版を封印・上位で置換） |
| woodcutting_perk_capitator_a | （Capitator A） | 60 | 1 | perk_3 | passive | treecapitatorcooldown -60、treecapitatorlimit +32 |
| woodcutting_perk_capitator_b | （Capitator B） | 80 | 1 | capitator_a | passive | treecapitatorcooldown -80、treecapitatorlimit +80 |
| woodcutting_perk_combo | Active Lifestyle | 50 | 1 | perk_2 ／ other: DIGGING:50 | passive | power_foodbonus（vegetable/seafood/magical/grain/fruit/nuts/dairy/meat）各 +0.3、power_healthregenerationbonus +0.3、power_hungersavechance +0.3 |
| woodcutting_perk_ng1 | New Game I | 100 | 0 | — (hidden) | 転生 | reset_skill_woodcutting。p:newgameplus +1、p:woodcuttingdrops +0.3、p:woodcuttingluck +4.5、p:woodcuttingspeedbonus +0.25、p:woodcuttingexpmultiplier -0.5（恒久） |
| woodcutting_perk_ng2 | New Game II | 100 | 0 | ng1 (hidden) | 転生 | 同上だが p:woodcuttingexpmultiplier -0.17 |

---

## Farming（農業）

### Overview
- 表示名: `Farming` / アイコン: `WOODEN_HOE`系 / レベルバー色 `GREEN` / `SEGMENTED_6`
- EXP獲得: 4経路。`block_drops`（作物収穫: 小麦48、ジャガイモ/ニンジン40、ビートルート48、カカオ80、メロン/カボチャ80、トーチフラワー/ピッチャー100、各種花40、胞子の花120 等）、`block_interact`（蜂の巣/養蜂箱400、甘いベリー茂み40、洞窟つる48/80、蜂蜜瓶400）、`entity_breed`（繁殖: ニワトリ80〜ホグリン200）、`entity_drops`（屠殺ドロップ: 牛/豚/羊肉60、ウサギ100 等）、`entity_shear`（毛刈り: 羊200、ムーシュルーム1000）。
- starting_perks: なし
- leveling_perks（毎レベル）: farming_farmingdrops_add +0.01

### Perk tree
中央直列: perk_1 → perk_2 → perk_3 → perk_4 → perk_5。畜産枝(a): perk_1 → 1a → 2a。養蜂(b): perk_1 → 1b。食事(c): perk_1 から 1c **xor** 2c（相互ロック `perks_locked_add`）。combo パーク無し。

| Perk ID | 表示名 | required_lv | cost | requires | 種別 | 効果（無改変） |
|---|---|---|---|---|---|---|
| farming_perk_1 | Gentle Touch | 0 | 1 | — | unlock | farming_instantharvesting(toggle)（右クリックで収穫＋再植え） |
| farming_perk_2 | Magical Soil | 20 | 1 | perk_1 | passive | farmingexperiencerate +0.3、instantgrowthrate +1（作物成長加速＋EXP） |
| farming_perk_3 | Blessed Harvest | 40 | 1 | perk_2 | passive | farmingexperiencerate +0.3、farmingluck +3、farmingdrops +0.2 |
| farming_perk_4 | Divine Harvest | 80 | 1 | perk_3 | passive | farmingexperiencerate +0.4、farmingluck +3、farmingdrops +0.3 |
| farming_perk_5 | Automagical Agriculture | 100 | 1 | perk_4 | unlock | fieldharvestunlocked(toggle)、fieldharvestinstantpickup(toggle)、fieldharvestcooldown +100（広範囲一括収穫アビリティ） |
| farming_perk_1a | Animal Husbandry | 40 | 1 | perk_1 | passive | growuptimemultiplier +0.4、butcherydrops +0.2、breedingexperiencemultiplier +0.5 |
| farming_perk_2a | Butchery | 60 | 1 | 1a | passive | growuptimemultiplier +0.6、butcherydrops +0.3、breedingexperiencemultiplier +0.5、butcherydamagemultiplier +3（動物に4倍ダメージ） |
| farming_perk_1b | Beekeeper | 50 | 1 | perk_1 | passive | beeaggroimmunity(toggle)（蜂が敵対しない）、hivehoneysavechance +0.5 |
| farming_perk_1c | Trash Diet | 30 | 1 | perk_1 | passive(排他) | power_badfoodimmune(toggle)、腐敗/生食 foodbonus+4・saturation+2（3倍効果）、その他全食料 foodbonus -0.5、perks_locked_add: 2c（2cと排他） |
| farming_perk_2c | Balanced Diet | 50 | 1 | perk_1 | passive(排他) | 腐敗/生食以外の全食料 foodbonus +0.2、perks_locked_add: 1c（1cと排他） |
| farming_perk_ng1 | New Game I | 100 | 0 | — (hidden) | 転生 | reset_skill_farming。p:newgameplus +1、p:farmingdrops +0.3、p:butcherydrops +0.3、p:farmingexpmultiplier -0.5（恒久） |
| farming_perk_ng2 | New Game II | 100 | 0 | ng1 (hidden) | 転生 | 同上だが p:farmingexpmultiplier -0.17 |

---

## Fishing（釣り）

### Overview
- 表示名: `Fishing` / アイコン: `STRING`/魚系 / レベルバー色 `BLUE`（&b/&9）/ `SEGMENTED_6`
- EXP獲得: `fishing_catch`（釣果ごと）。タラ200、サケ250、フグ500、熱帯魚800、弓/エンチャント本/釣竿600、名札/オウムガイの殻/鞍500、革/睡蓮/ボウル/革ブーツ150、腐肉/棒/糸/骨/イカスミ/トリップワイヤーフック/水入り瓶100、竹150。`is_chunk_nerfed: true`。
- starting_perks: なし
- leveling_perks（毎レベル）: fishing_fishingspeedbonus_add +0.02、fishing_fishingdrops_add +0.02

### Perk tree
単一直列: perk_1 → 2 → 3 → 4 → 5 → 6。combo は perk_1 前提＋SMITHING:70。

| Perk ID | 表示名 | required_lv | cost | requires | 種別 | 効果（無改変） |
|---|---|---|---|---|---|---|
| fishing_perk_1 | Lucky Fisherman | 0 | 1 | — | passive | fishingluck +1.5 |
| fishing_perk_2 | Passion for the Seas | 20 | 1 | perk_1 | passive | fishingessencemultiplier +0.5（EXP増） |
| fishing_perk_3 | Deep Senses | 40 | 1 | perk_2 | passive | fishingspeedbonus +0.4、fishingluck +2 |
| fishing_perk_4 | Dual Hooks | 60 | 1 | perk_3 | passive | fishingdrops +0.25（ダブルキャッチ確率） |
| fishing_perk_5 | Golden Bait | 80 | 1 | perk_4 | passive | fishingspeedbonus +0.6、fishingluck +2.5 |
| fishing_perk_6 | Mystic Bait | 100 | 1 | perk_5 | passive | fishingdrops +0.25、fishingessencemultiplier +1 |
| fishing_perk_combo | Deep Sea Scavenging | 50 | 1 | perk_1 ／ other: SMITHING:70 | unlock(recipe) | recipes_unlock: 防具・道具の解体（salvage）レシピ多数（革/チェーン/金/鉄/ダイヤ/銅 各防具＋全木〜ダイヤ/銅の剣/ツルハシ/斧/シャベル/クワ/ダガー/メイス/レイピア/大斧/ウォーハンマー/槍） |
| fishing_perk_ng1 | New Game I | 100 | 0 | — (hidden) | 転生 | reset_skill_fishing。p:newgameplus +1、p:fishingdrops +0.5、p:fishingluck +2.5、p:fishingspeedbonus +0.25、p:fishingexpmultiplier -0.5（恒久） |
| fishing_perk_ng2 | New Game II | 100 | 0 | ng1 (hidden) | 転生 | 同上だが p:fishingexpmultiplier -0.17 |

---

## Smithing（鍛冶）

### Overview
- 表示名: `Smithing` / アイコン: `OAK_PLANKS`系 / レベルバー色（&e）/ `SEGMENTED_6`
- EXP獲得: アイテムに耐久ダメージが入るたびに素材種ごとの tally カウンタが増え、次回その素材で Smithing EXP を得る際に倍率がかかる。`durability_tools_exp_multiplier_stack: 0.01`（道具: 1スタックで+1%、最大1000スタック=+1000%）、`durability_armors_exp_multiplier_stack: 0.005`（防具: +0.5%、最大200スタック=+100%）、`durability_chunk_limit: 50`（同一チャンク・同一素材で最大50スタックまで）。
- starting_perks（素材別EXP倍率ハンディ）: stone/chain/copper -0.5、gold/iron -0.75、diamond -0.90、netherite -1（ネザライトは初期0%）。
- special_perks（レベル進行で素材別EXP倍率が段階シフト＝低Tier素材は減衰、高Tier素材が解放）: **Lv20**で wood/leather -0.75・stone系 +0.5・gold/iron +0.25・diamond +0.15・netherite +0.1、**Lv40**で wood/leather -0.2・stone系 -0.75・gold/iron +0.50・diamond +0.25・netherite +0.15、**Lv60**で wood/leather -0.05・stone系 -0.2・gold/iron -0.75・diamond +0.5・netherite +0.25、**Lv80**で stone系 -0.05・gold/iron -0.2・netherite +0.5。
- leveling_perks（毎レベル）: smithing_genericcraftingskill_add +1.5

### Perk tree
直列: perk_1 → 2 → 3 → 4 → 5 → 6。perk_3 から prismarine枝、perk_4 から enderic枝。bows はクロススキル単独パーク（前提パーク無し、ARCHERY:50）。各 craftingskill は対応素材の品質スキル。

| Perk ID | 表示名 | required_lv | cost | requires | 種別 | 効果（無改変） |
|---|---|---|---|---|---|---|
| smithing_perk_1 | Craftsman | 0 | 1 | — | passive | woodcraftingskill +50、leathercraftingskill +50 |
| smithing_perk_2 | Apprentice Blacksmith | 20 | 1 | perk_1 | passive | stone/chain/coppercraftingskill 各 +50 |
| smithing_perk_3 | Blacksmith | 40 | 1 | perk_2 | passive+unlock | iron/goldcraftingskill 各 +50、recipes_unlock: ダイヤ防具・全ダイヤ武器/道具（剣/斧/ツルハシ/シャベル/クワ/ウォーハンマー/大斧/槍/ダガー/レイピア/メイス/realspear） |
| smithing_perk_4 | Crystalsmith | 60 | 1 | perk_3 | passive+unlock | diamondcraftingskill +50、power_inventoryrepairingkeepenchanting(toggle)（修理でエンチャント保持） |
| smithing_perk_5 | Hellforge | 80 | 1 | perk_4 | passive | netheritecraftingskill +50 |
| smithing_perk_6 | Durin's Blessing | 100 | 1 | perk_5 | passive | genericcraftingskill +50 |
| smithing_perk_prismarine | Marine Smithing | 70 | 1 | perk_3 | passive+unlock | prismarinecraftingskill +50、recipes_unlock: craft_trident |
| smithing_perk_enderic | Exotic Smithing | 90 | 1 | perk_4 | passive+unlock | endericcraftingskill +50、recipes_unlock: craft_elytra / craft_choral_leather |
| smithing_perk_bows | Advanced Fletching | 50 | 1 | （前提パーク無し）／ other: ARCHERY:50 | passive | bowcraftingskill +50、crossbowcraftingskill +50 |
| smithing_perk_ng1 | New Game I | 100 | 0 | — (hidden) | 転生 | reset_skill_smithing。p:genericcraftingskill +50、p:newgameplus +1、p:genericexpmultiplier -0.5（恒久） |
| smithing_perk_ng2 | New Game II | 100 | 0 | ng1 (hidden) | 転生 | 同上だが p:genericexpmultiplier -0.17 |

※ smithing.yml（base）にはアップグレード/品質システムの設定（強化スロット拡張等）あり。perk ツリー本体は上記。

---

## Enchanting（エンチャント）

### Overview
- 表示名: `Enchanting` / アイコン: `PAPER`/本系 / レベルバー色 `BLUE`（&b/&9）/ `SEGMENTED_6`
- EXP獲得: `exp_gain`。エンチャント実施時、消費した経験値の `experience_spent_conversion: 0.5`（50%）がスキルEXPに変換＋エンチャント種別ごとの `enchantment_base`（例: power/efficiency/sharpness/protection 180〜200、fortune/looting/lure 450、mending/flame 750、silk_touch/infinity/channeling 1000 等）×`enchantment_level_multiplier`（I=1.0、II=2.2、III=3.4…X=11.8）。`enchantment_type/item_multiplier` は全1.0。`diminishing_returns`（同一モブ20体killごとに以降のエンチャEXPを×0.1へ減衰）。`is_chunk_nerfed: true`。
- starting_perks: enchanting_essencemultiplier -0.3、essencerefundchance +1、enchantmentamplificationchance +1
- leveling_perks（毎レベル）: enchanting_enchantingskill_add +1、enchanting_anvilskill_add +1

### Perk tree
基点 perk_1 から3方向: a枝(essence/lapis節約)、b枝(essence倍率＋Hexblade属性剣)、中央(perk_2 → 3 → 4)。中央の各メイン下に隠し選択サブパーク（hidden, cost 1, required_lv 無し＝前提取得で出現）が排他選択で並ぶ。b2 配下に Hexblade属性 radiant/necrotic の二択。combo は perk_2 前提＋FARMING:50。

| Perk ID | 表示名 | required_lv | cost | requires | 種別 | 効果（無改変） |
|---|---|---|---|---|---|---|
| enchanting_perk_1 | Runic Focus | 0 | 1 | — | passive | enchantingskill +20、anvilskill +20 |
| enchanting_perk_a1 | Stable Conduit | 20 | 1 | perk_1 | passive | essencerefundfraction +0.1、lapissavechance +0.2 |
| enchanting_perk_a2 | Pure Conduit | 40 | 1 | a1 | passive | essencerefundfraction +0.1、lapissavechance +0.3、enchantingskill +20、anvilskill +20 |
| enchanting_perk_b1 | Mental Focus | 20 | 1 | perk_1 | passive | essencemultiplier +0.3、**power_allskillexpmultiplier +0.05**（全スキルEXP+5%） |
| enchanting_perk_b2 | Hexblade | 60 | 1 | b1 | passive+unlock | essencemultiplier +0.5、**power_allskillexpmultiplier +0.15**、activeelementaldamageconversion +0.4、activeelementaldamagemultiplier +0.5、essencecostperhit +3 |
| enchanting_perk_radiant | Hexblade: Holy | — | 1 | b2 (hidden) | toggle(選択) | set_elemental_type RADIANT、perks_unlocked_remove: necrotic（排他） |
| enchanting_perk_necrotic | Hexblade: Unholy | — | 1 | b2 (hidden) | toggle(選択) | set_elemental_type NECROTIC、perks_unlocked_remove: radiant（排他） |
| enchanting_perk_2 | Arcane Fortitude | 40 | 1 | perk_1 | passive | enchantingskill +30、anvilskill +30、essencerefundfraction +0.1 |
| enchanting_perk_sharpnesses | Arcane Fortitude: Damage | — | 1 | perk_2 (hidden) | 選択 | levelbonus sharpness/smite/boa/impaling/power 各 +1、perks_locked_add: protections（排他） |
| enchanting_perk_protections | Arcane Fortitude: Protection | — | 1 | perk_2 (hidden) | 選択 | levelbonus blastprotection/featherfalling/fireprotection/projectileprotection/protection 各 +1、perks_locked_add: sharpnesses（排他） |
| enchanting_perk_3 | Archmage | 80 | 1 | perk_2 | passive | enchantingskill +30、anvilskill +30 |
| enchanting_perk_looting | Archmage: Looting | — | 1 | perk_3 (hidden) | 選択(3択) | levelbonuslooting +2、perks_locked_add: fortune/unbreaking |
| enchanting_perk_fortune | Archmage: Fortune | — | 1 | perk_3 (hidden) | 選択(3択) | levelbonusfortune +1、perks_locked_add: looting/unbreaking |
| enchanting_perk_unbreaking | Archmage: Unbreaking | — | 1 | perk_3 (hidden) | 選択(3択) | levelbonusunbreaking +2、perks_locked_add: looting/fortune |
| enchanting_perk_4 | Divine Nexus | 100 | 1 | perk_3 | passive | enchantingskill +50、anvilskill +50 |
| enchanting_perk_offensive | Divine Nexus: Evocation | — | 1 | perk_4 (hidden) | 選択(3択) | levelbonusgenericoffensive +1、perks_locked_add: defensive/utility |
| enchanting_perk_defensive | Divine Nexus: Abjuration | — | 1 | perk_4 (hidden) | 選択(3択) | levelbonusgenericdefensive +1、perks_locked_add: offensive/utility |
| enchanting_perk_utility | Divine Nexus: Divination | — | 1 | perk_4 (hidden) | 選択(3択) | levelbonusgenericutility +1、perks_locked_add: offensive/defensive |
| enchanting_perk_combo | Halfling Luck | 70 | 1 | perk_2 ／ other: FARMING:50 | passive | power_luckbonus +1 |
| enchanting_perk_ng1 | New Game I | 100 | 0 | — (hidden) | 転生 | reset_skill_enchanting。p:newgameplus +1、p:enchantingskill +50、p:anvilskill +50、p:essencemultiplier +0.5、p:enchantingexpmultiplier -0.5（恒久） |
| enchanting_perk_ng2 | New Game II | 100 | 0 | ng1 (hidden) | 転生 | 同上だが p:enchantingexpmultiplier -0.17 |

※ enchanting の b枝（Mental Focus/Hexblade）は `power_allskillexpmultiplier` を加算し、全スキルのEXP獲得を底上げする（＝間接的に Power のレベルアップ頻度にも寄与する）。選択サブパーク群（hidden, required_lv 表記なし）は親メイン取得後に出現し、`perks_locked_add` による相互排他で1つだけ取得可能。

---

## Alchemy（錬金）

### Overview
- 表示名: `Alchemy` / アイコン: `BLAZE_POWDER`系 / レベルバー色 `PURPLE`（&d/&5）/ `SEGMENTED_6`
- EXP獲得: 醸造したポーションの品質に応じる。`exp_multiplier_quality: 0.00334`（品質1点ごとEXP倍率+0.334%）、`multiplier_automated: 0.25`（自動醸造は0.25倍）、`multiplier_manual: 2`（手動醸造2倍）。
- starting_perks: なし
- leveling_perks（毎レベル）: alchemy_genericbrewingskill_add +1.5
- 別ファイル `alchemy_transmutations.yml` 存在（ブロック/アイテムの transmutation 変換レシピ定義。本書では perk ツリーに集中し、変換レシピ表は割愛。perk_4 の `alchemy_transmutations_unlock_all` で全解放）。

### Perk tree
直列: perk_1 → perk_2 →（a枝=デバフ/スプラッシュ、b枝=バフ/触媒）→ perk_3（a2 **or** b2）→ perk_4。combo は perk_2 前提＋（軽武器 or 重武器の perk_3）。

| Perk ID | 表示名 | required_lv | cost | requires | 種別 | 効果（無改変） |
|---|---|---|---|---|---|---|
| alchemy_perk_1 | Stable Burn | 0 | 1 | — | passive | brewingtimereduction +0.5、genericbrewingskill +20 |
| alchemy_perk_2 | Potent Ingredients | 20 | 1 | perk_1 | passive | brewingingredientsavechance +0.2、genericbrewingskill +30 |
| alchemy_perk_a1 | Volatile Poisons | 40 | 1 | perk_2 | passive | splashintensityminimum +0.3、debuffbrewingskill +20 |
| alchemy_perk_a2 | Chemical Artillery | 60 | 1 | a1 | passive | debuffbrewingskill +30、splashintensityminimum +0.3、lingeringdurationmultiplier +0.5、lingeringradiusmultiplier +0.5、throwvelocity +0.5 |
| alchemy_perk_b1 | Effective Catalysts | 40 | 1 | perk_2 | passive | buffbrewingskill +20、brewingtimereduction +0.75 |
| alchemy_perk_b2 | Exotic Ingredients | 60 | 1 | b1 | passive+unlock | buffbrewingskill +30、brewingingredientsavechance +0.3、recipes_unlock: brew_haste_potion / brew_health_boost_potion |
| alchemy_perk_3 | Mighty Mixtures | 80 | 1 | a2 **or** b2 | passive+unlock | brewingtimereduction +1.25、potioncombiningunlocked(toggle)、potioncombiningmaxcombinations +3、potioncombiningamplifiermultiplier +0、potioncombiningdurationmultiplier -0.2 |
| alchemy_perk_4 | Prima Materia | 100 | 1 | perk_3 | passive+unlock | genericbrewingskill +50、transmutationradius +2、transmutations_unlock_all、recipes_unlock: brew_transmutation_potion |
| alchemy_perk_combo | Etching Venom | 50 | 1 | perk_2 ＋（LIGHT_WEAPONS perk_3 **or** HEAVY_WEAPONS perk_3） | passive+unlock | lightweapons_coatingcharges +10、heavyweapons_coatingcharges +10、recipes_unlock: craft_vial / vial_poison / vial_holy / vial_antiheal / vial_hurt |
| alchemy_perk_ng1 | New Game I | 100 | 0 | — (hidden) | 転生 | reset_skill_alchemy。p:newgameplus +1、p:genericbrewingskill +50、p:brewingtimereduction +0.75、p:alchemyexpmultiplier -0.5（恒久） |
| alchemy_perk_ng2 | New Game II | 100 | 0 | ng1 (hidden) | 転生 | 同上だが p:brewingtimereduction +1.0、p:alchemyexpmultiplier -0.17 |

※ alchemy_perk_combo は他スキルのパーク（武器スキルの perk_3）を前提とする珍しいクロススキル combo（`requireperk_one` で武器パーク、`requireperk_all` で alchemy_perk_2）。武器コーティングのチャージ数を増やす。
