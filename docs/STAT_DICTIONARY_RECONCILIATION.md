# Stat Dictionary Reconciliation (DECISION-INPUT)

- **作成日**: 2026-06-29
- **位置付け**: 3つのステータス名前空間（ValhallaMMOパーク統計 / TFロール統計 / TF属性マップ統計）を突き合わせ、**正典stat辞書**（ギャップ I2）の定義と stat ルーティング（C1 / Q5 / Q6）の人間判断に必要な材料を一枚に集約する。**本書は決定しない。** すべての写像案は `CANDIDATE`（人間の承認待ち）。
- **関連**: `OPEN_DECISIONS.md`（I1/I2/C1/Pr1, LD-7）、`SKILL_TREE_TUNING.md`（Q5/Q6）、`COMBAT_SYSTEM_SPEC.md` §2-§5、`VALHALLA_DEFAULT_SKILLS.md`（A の転写）。
- **正典方針リマインド（証拠）**: 「数値の真実 = アドオンのPDC、表示と物理挙動は Vanilla/Valhalla の Attribute に反映」(`COMBAT_SYSTEM_SPEC.md:14`)。「PDC-only combat stats（penetration%, crit, resistance%, flat defense）は attribute-map に載せずパイプラインが消費」(`attribute-map.yml:5-6`)。

---

> **超過通知（2026-07-14）— 一部の見出し的事実が更新済み**: 本書は 2026-06-29 の DECISION-INPUT。以後 LD-8/I2・LD-13 が実装され、次の記述は**もう成立しない**（判断材料としての価値は残るが事実は更新）:
> - §0「B と C は完全に素集合（disjoint）」「現状、ロール統計はどの属性にも反映されない」→ `roll.yml` に `armor-defense-rate/armor-strength/max-health/knockback-resistance/move-speed` を追加し `attribute-map.yml` にcanonical一致済（`attack_power` のみ roll源なしは意図通り）。
> - §0/§2「プレイヤー側 DefenseStats の供給源は未実装（=C1）」→ `PlayerDefenseResolver`＋`DefenseStatBridge` で**item側は実装済**（残=防具スキルbaseline）。
> - 防御 typing は LD-13 で確定（耐性のみ typed・他は共通）。本書§1のtyped前提の一部はそれに合わせて読むこと。

## 0. 3名前空間の実体（証拠付き）

### A. ValhallaMMO パーク統計（`VALHALLA_DEFAULT_SKILLS.md`）
無改変転写。本書では戦闘5職の **combat stat** のみ対象（採取/生産/Power の生産系statは戦闘ルーティング対象外＝LD-7 G3）。出現する主な combat stat:
- 攻撃系: `damagemultiplier`, `attackspeedmultiplier`, `critchance`, `critdamage`, `bleedchance`/`bleeddamage`/`bleedduration`/`bleedoncrit`, `penetrationflat`/`penetrationfraction`, `powerattackdamagemultiplier`/`powerattackfraction`/`powerattackradius`, `stunchance`, `knockbackmultiplier`, `attackreachbonus`, `immunityreductionfraction`, `damagetolightarmormultiplier`, `coating*`(family), 弓系 `bowdamagemultiplier`/`crossbowdamagemultiplier`/`bowcritchance`/`crossbowcritchance`/`inaccuracy`/`distancedamage*`/`infinitydamagemultiplier`/`chargedshot*`/`critonstealth`, `activeelementaldamage*`（Hexblade, `VALHALLA_DEFAULT_SKILLS.md:400`）
- 防御系（防具2職）: `lightarmormultiplier`/`heavyarmormultiplier`, `damageresistanceperpiece`, `setmagicresistance`(`:128`), `setknockbackresistance`(`:158`), `setcritchanceresistance`/`setstunresistance`(`:131`), `setimmunityfractionbonus`(`:161`), `setreflectchance`/`setreflectfraction`(`:157`), `dodgechanceperpiece`/`setdodgechance`(`:127`), `movementspeedperpiece`, `sethealingbonus`/`healingbonusperpiece`, `parrydamagereduction`(`:33`), `oneshotprotectionfraction`(`:180`), `add_immune_effect`(POISON/SLOW/… `:128,158`)
- Parry/Coating/PowerAttack/Charge/Adrenaline/Rage = サブ機構トグル＋duration/cooldown 群（`parry*`, `coating*`, `chargedshot*`, `adrenaline*`, `rage*`）。Q3で採否が決まるまで写像保留。
- Power プロファイル（汎用・非戦闘扱い、参考）: `power_healthbonus`/`power_armorbonus`/`power_attackdamagemultiplier`/`power_healthregenerationbonus`/`power_luckbonus`/`power_cooldownreduction`（`:190-210`）。

### B. TF ロール統計（`stats/roll.yml`）— kebab-case, 5キー
| key | base | roll-min..max | 行 |
|---|---|---|---|
| `crit-chance` | 0.0 | 0.0..0.15 | `roll.yml:7-10` |
| `crit-damage` | 0.5 | 0.0..0.5 | `roll.yml:11-14` |
| `penetration` | 0.0 | 0.0..0.3 | `roll.yml:15-18` |
| `flat-bonus-damage` | 0.0 | 0.0..4.0 | `roll.yml:19-22` |
| `percent-bonus-damage` | 0.0 | 0.0..0.2 | `roll.yml:23-26` |

`lore.yml:23-63` は同5キーを表示整形（`crit-chance`→"Crit Chance" 等）。**B の5キーは全て攻撃側（OFFENSE）。**

### C. TF 属性マップ統計（`stats/attribute-map.yml`）— snake_case, 6キー
| key | → Bukkit Attribute | operation | scale | 行 |
|---|---|---|---|---|
| `attack_power` | `attack_damage` | ADD_NUMBER | 1.0 | `attribute-map.yml:16-19` |
| `knockback_resistance` | `knockback_resistance` | ADD_NUMBER | 1.0 | `:20-23` |
| `armor_defense_rate` | `armor` | ADD_NUMBER | 1.0 | `:24-27` |
| `armor_strength` | `armor_toughness` | ADD_NUMBER | 1.0 | `:28-31` |
| `max_health` | `max_health` | ADD_NUMBER | 1.0 | `:32-35` |
| `move_speed` | `movement_speed` | ADD_SCALAR | 0.01 | `:36-39` |

### 重大事実: B と C は **完全に素集合（disjoint）**
`StatKeys.canonical` は `-`→`_` 折りたたみ＋小文字化のみ（`StatKeys.java:27-30`）。B を canonical 化すると `crit_chance / crit_damage / penetration / flat_bonus_damage / percent_bonus_damage`。C のキー集合 `{attack_power, knockback_resistance, armor_defense_rate, armor_strength, max_health, move_speed}` と**1つも一致しない**。
→ `AttributeProjection.project` はロール値を1つも属性modifierに射影しない（`AttributeProjection.java:89-92` で `entries.get(canonical)` が常に null）。**現状、ロール統計はどの属性にも反映されない。** これは I1 の「キー不一致」が `canonical` で技術的に解消されても、**そもそも写像対象のキー対が存在しない**という I2 の構造欠落であることを示す。

### TF パイプラインが実際に消費する stat フィールド（証拠）
- 攻撃側 `AttackStats`（`AttackStats.java:16-25`）: `defaultDamage, flatBonusDamage, percentBonusDamage, critChance, critDamage, penetration, damageModifier, fixedDamage`。
- 防御側 `DefenseStats`（`DefenseStats.java:13-19`）: `defenseRate, resistance, damageReduction, flatDefense, armorStrength`。
- 8ステップ式が各フィールドをどう使うか: `ComponentDamageCalculator.java:20-55`（penetration は防御率%のみ無視=`:32`、resistance は貫通不可=`:35`、fixedDamage は flat 相殺のみ=`:51`）。
- mob 防御プロファイルは type別 PDC キーから構築（`MobData.java:59-75`、`PdcKeys` `mob_phys_*` / `mob_magic_*` / `mob_armor_strength`）。**プレイヤー側 DefenseStats の供給源は未実装（= C1 そのもの）。**
- B の5キーは `AttackStats` の5フィールドに1:1対応（`crit-chance↔critChance` 等）。`damageModifier` と `fixedDamage` はパイプラインに在るが **roll.yml に源が無い**。

---

## 1. 再構成テーブル（concept 行 × 名前空間列）

凡例 — classification: **OFF**=攻撃側 / **DEF**=防御側 / **UTIL**=機構・非戦闘。routing は全て `CANDIDATE`。

| # | concept（正典人間名） | A: Valhalla perk stat | B: roll.yml | C: attribute-map → Attribute | class | routing proposal (CANDIDATE) | notes / 曖昧点 |
|---|---|---|---|---|---|---|---|
| 1 | 物理攻撃力（基礎与ダメ） | `damagemultiplier`/`p:*`（武器職）, `power_attackdamagemultiplier` | — | `attack_power` → `attack_damage` | OFF | **TF pipeline の `AttackStats.defaultDamage`**（vanilla attackDamage 由来, `SymmetricCombatService.java:54-58`）。属性 `attack_power` は表示/Bedrock mirror 用。gear非依存方針（`COMBAT_SYSTEM_SPEC.md:13`）と矛盾しないか確認 | Valhalla の `damagemultiplier` は%乗算でレベルスケール済（`:24,56`）。TF combat-level 乗算と二重化注意（G4/C3） |
| 2 | 攻撃速度 | `attackspeedmultiplier` | — | —（候補: `attack_speed`） | OFF/UTIL | vanilla `attack_speed` attribute（速度はバニラ機構）。pipeline外 | C に無い。Valhalla が直接attribute操作する領域。TFがroll対象にするかは別判断（Q3外） |
| 3 | 会心率 | `critchance`, `bowcritchance`, `crossbowcritchance` | `crit-chance` | —（PDC-only） | OFF | **PDC-only → `AttackStats.critChance`**（`ComponentDamageCalculator.java:27`）。属性analogue無し（バニラに会心無し） | Q5中核。Valhalla弓は bow/crossbow 別statだがTFは単一 crit-chance。C9: crit経路は現状デッド |
| 4 | 会心ダメージ | `critdamage` | `crit-damage` | —（PDC-only） | OFF | **PDC-only → `AttackStats.critDamage`** | Q5。roll base 0.5（`roll.yml:13`）はValhalla power starting `critdamage +0.5`(`:180`)と整合 |
| 5 | 貫通（防御率% 無視） | `penetrationflat`(整数), `penetrationfraction`(%) | `penetration`（%） | —（PDC-only） | OFF | **PDC-only → `AttackStats.penetration`**（防御率のみ無視, `ComponentDamageCalculator.java:32`） | Q5。Valhalla は flat と fraction の2系統、TFは fraction のみ。flat貫通の扱い未定 |
| 6 | 固定追加ダメージ | （Valhalla直接対応薄, `bleeddamage`が近い） | `flat-bonus-damage` | —（PDC-only） | OFF | **PDC-only → `AttackStats.flatBonusDamage`** | base算出時加算（`:24`） |
| 7 | 割合追加ダメージ% | `damagemultiplier`の一部, `powerattackdamagemultiplier` | `percent-bonus-damage` | —（PDC-only） | OFF | **PDC-only → `AttackStats.percentBonusDamage`** | `MAGIC_BALANCE_SPEC`でArs増強と層分離済（`COMBAT_SYSTEM_SPEC.md:165`） |
| 8 | ダメージ補正%（攻撃乗算） | — | —（roll欠） | —（PDC-only） | OFF | **PDC-only → `AttackStats.damageModifier`**。roll源を新設すべきか要判断 | パイプラインに在るがroll.ymlに源無し（穴）。被ダメージ軽減%の攻撃側ペア(`COMBAT_SYSTEM_SPEC.md:90`) |
| 9 | 固定ダメージ（flat装甲相殺） | — | —（roll欠） | —（PDC-only） | OFF | **PDC-only → `AttackStats.fixedDamage`**（対重装甲カウンター, `COMBAT_SYSTEM_SPEC.md:58`） | roll源無し（穴）。重装甲特化への対抗手段なので将来必要 |
| 10 | 出血（chance/damage/duration/oncrit） | `bleedchance`,`bleeddamage`,`bleedduration`,`bleedoncrit` | — | — | OFF | **判断要（Q5）**: (a) PDC flavor のみ / (b) `flatBonusDamage` 近似に丸める / (c) DoT機構を新規実装 | 8ステップ式に出血スロット無し。新機構 or 切り捨て |
| 11 | スタン/ノックバック倍率/リーチ等 | `stunchance`,`knockbackmultiplier`,`attackreachbonus`,`damagetolightarmormultiplier` | — | （`knockback`系はC外） | OFF/UTIL | **判断要（Q3/Q5）**: combo perk由来の味付け。多くは PDC flavor or cut 候補 | パイプライン非対応。G5 combo二重breadth点検対象 |
| 12 | Parry/Coating/PowerAttack/Charge | `parry*`,`coating*`,`powerattack*`,`chargedshot*` | — | — | OFF/UTIL | **Q3が先決**。採用ならTF側で機構実装、stat化は後 | サブ機構。stat辞書というより機構フラグ群 |
| 13 | 防御率%（armor由来%軽減, 貫通対象） | `lightarmormultiplier`/`heavyarmormultiplier`, `damageresistanceperpiece` | — | `armor_defense_rate` → `armor` | DEF | **二重経路**: vanilla `armor` mirror（`:24-27`）＋ プレイヤー`DefenseStats.defenseRate`（C1）。**写像規則 = Q6** | C1中核。Valhalla防具multiplierをTF defenseRateにどう係数変換するか未定 |
| 14 | 防具強度（type非依存flat, 固定ダメcap相殺） | （Valhalla直接対応薄） | — | `armor_strength` → `armor_toughness` | DEF | vanilla `armor_toughness` mirror ＋ `DefenseStats.armorStrength`（C1） | `COMBAT_SYSTEM_SPEC.md:127` の写像と一致 |
| 15 | 守備力（typed flat: 物理/魔法別） | — | — | —（PDC-only, mob側のみ実装） | DEF | **PDC-only → `DefenseStats.flatDefense`**。プレイヤー側供給源未実装（C1） | mob側は `mob_phys_flat_defense`/`mob_magic_flat_defense`(`PdcKeys`)。プレイヤー側が穴 |
| 16 | 物理/魔法 耐性%（貫通不可・三すくみ） | `setmagicresistance`(魔法), `radiantresistance` | — | —（PDC-only） | DEF | **PDC-only → `DefenseStats.resistance`**（type別）。Q6で Valhalla魔法耐性を写像 | `setmagicresistance +0.4`(`:128`)が魔法耐性の自然な供給源 |
| 17 | 被ダメージ軽減%（type非依存乗算） | `parrydamagereduction`, `oneshotprotectionfraction`, Adrenaline/Rage の DAMAGE_RESISTANCE | — | —（PDC-only） | DEF | **PDC-only → `DefenseStats.damageReduction`**。Q6で Parry等を写像するか判断 | `COMBAT_SYSTEM_SPEC.md:90` ダメージ補正%の防御ペア |
| 18 | ノックバック耐性 | `setknockbackresistance` | — | `knockback_resistance` → `knockback_resistance` | DEF/UTIL | vanilla attribute mirror。pipeline外（バニラ機構） | C 既存。Valhalla `setknockbackresistance +0.4`(`:158`)が源候補 |
| 19 | 最大体力 | `power_healthbonus`(非戦闘Power) | — | `max_health` → `max_health` | DEF/UTIL | vanilla attribute mirror。pipeline外 | C 既存。プレイヤー実HP。Power由来は戦闘ルーティング外(LD-7) |
| 20 | 移動速度 | `movementspeedperpiece`, Adrenaline SPEED | — | `move_speed` → `movement_speed` | UTIL | vanilla attribute mirror（scale 0.01, `:36-39`） | C 既存。防具職の機動性stat |
| 21 | 回避/反射/被ダメ免疫等 | `dodgechanceperpiece`/`setdodgechance`, `setreflectchance/fraction`, `setcritchanceresistance`, `setstunresistance`, `setimmunityfractionbonus`, `add_immune_effect` | — | — | DEF | **判断要（Q6）**: TF式に該当スロット無し。(a) cut / (b) damageReduction近似 / (c) 新DEF stat | 8ステップ式に回避/反射スロット無し。C1の射程外か新設か |
| 22 | クールダウン短縮/luck/regen等（汎用） | `power_cooldownreduction`,`power_luckbonus`,`power_healthregenerationbonus` | — | （luck/regen系はC外） | UTIL | 戦闘ルーティング外（Power集約, LD-7 G3）。必要なら個別 vanilla attribute | 戦闘力に非算入。参考掲載 |

---

## 2. テーオフすべき具体的決定（証拠付き論点整理）

### C1 / Q6 — 防具職の防御statをプレイヤー DEFENSE プロファイルへ
**現状の穴**: プレイヤーが被弾側のとき `DefenseStats` の供給源が無い。mob側は `MobData.defenseFor`(`MobData.java:59-75`)で type別 PDC から構築されるが、プレイヤー側は同等の供給経路が未実装。対称モデルが攻撃側だけ半完成（OPEN_DECISIONS C1）。
**写像候補（行13-17, 21）**:
- 1:1 attribute で済むもの（vanilla機構に乗る）: ノックバック耐性(行18)、最大体力(行19)、移動速度(行20)、防具強度の表示(行14)、防御率の表示(行13) → 既に C に存在。**追加判断は「これらを満たす roll源を作るか」（I2と接続）。**
- TF `DefenseStats` 入力として要るもの（PDC-only, pipeline消費）: 防御率%(13)・守備力flat(15)・耐性%(16)・被ダメ軽減%(17)。**Valhalla供給源候補** = `lightarmormultiplier`/`heavyarmormultiplier`(防御率), `setmagicresistance`(魔法耐性), `parrydamagereduction`/`oneshotprotectionfraction`(被ダメ軽減)。
- TF式に**スロットが無い**もの: 回避/反射/各種resistance/immune(行21)。→ **cut か新DEF stat 新設の二択を要決定。**
- **決めること**: ① Valhalla防具multiplier → `DefenseStats.defenseRate` の換算規則（1:1か係数か、Q6）。② Parry を `damageReduction` に畳むか独立機構か（Q3と連動）。③ 回避/反射を捨てるか新設するか。

### Q5 — Valhalla風オフェンス stat（bleed/crit/penetration）の表現
- crit(行3-4)・penetration(行5): **既にパイプラインにスロット有り** → PDC-only で `AttackStats` に流す（attribute analogue不要、`attribute-map.yml:5`の方針通り）。roll.yml に既存（B）。
- bleed(行10)・stun/reach等(行11): **8ステップ式にスロット無し**。(a) PDC flavor 表示のみ / (b) `flatBonusDamage`/`percentBonusDamage` に近似吸収 / (c) DoT等の新機構実装 ── の3択を要決定。
- penetration flat vs fraction(行5): Valhalla は2系統、TFは fraction のみ。flat貫通を捨てるか fraction 換算するか。

### I2 — roll.yml に attribute-map エントリを追加すべき最小集合（snake_case名）
**核心の発見**: 現 roll.yml の5キーは**全て PDC-only が正しい**（crit/penetration/bonus-damage に vanilla attribute analogue は無く、`attribute-map.yml:5-6` の方針通りパイプラインが消費）。**つまり現5キーに属性エントリは不要。**
逆向きの穴こそ本体: **attribute-map は6つの属性ターゲットを宣言しているが、それを生む roll キーが1つも無い**（B∩C=∅）。アイテムが実際に attack_power/armor/HP 等を付与するには、**roll.yml 側に以下の新キーが要る**（canonical 折りたたみで既存 attribute-map に一致する snake/kebab）:

| 追加すべき roll キー（kebab） | canonical | 既存 attribute-map エントリに一致 | 用途 |
|---|---|---|---|
| `attack-power` | `attack_power` | ✅ `attack_power`→attack_damage | 武器の基礎攻撃力をrollで味付け（※gear非依存方針と要整合, 行1注記） |
| `armor-defense-rate` | `armor_defense_rate` | ✅ →armor | 防具の防御率%（C1供給, 行13） |
| `armor-strength` | `armor_strength` | ✅ →armor_toughness | 防具強度（行14） |
| `max-health` | `max_health` | ✅ →max_health | 防具のHP（行19） |
| `knockback-resistance` | `knockback_resistance` | ✅ →knockback_resistance | 行18 |
| `move-speed` | `move_speed` | ✅ →movement_speed | 行20 |

→ **I2 の最小集合 = 上記6キーのうち「アイテムに実際に roll させたい防御/属性 stat」を取捨選択**したもの。攻撃側5キー（現B）は据え置きで PDC-only のまま。**判断: gear非依存方針（`COMBAT_SYSTEM_SPEC.md:13`）が `attack-power` の roll化を禁ずるなら、attribute-map の `attack_power` エントリは mob/表示専用に限定し、防御系5キーのみ roll新設する**、が有力な CANDIDATE。

---

## 3. 人間が下すべき正確な決定リスト

1. **(Q6/C1-a)** Valhalla 防具 multiplier（`lightarmormultiplier`/`heavyarmormultiplier`/`damageresistanceperpiece`）→ TF `DefenseStats.defenseRate` の換算規則は 1:1 か係数変換か。
2. **(Q6/C1-b)** `setmagicresistance` → `DefenseStats.resistance(MAGICAL)`、`parrydamagereduction`/`oneshotprotectionfraction` → `DefenseStats.damageReduction` を採用するか。
3. **(Q6/C1-c)** TF式にスロットが無い防御stat（回避 `dodgechance`、反射 `setreflect*`、`setcritchanceresistance`/`setstunresistance`/`setimmunityfractionbonus`、`add_immune_effect`）を **cut するか新DEF statを新設するか**。
4. **(Q5-a)** `bleed*`・`stunchance`・`attackreachbonus`・`damagetolightarmormultiplier` を PDC flavor 表示のみ / 既存ダメージstat吸収 / 新機構 のどれにするか。
5. **(Q5-b)** penetration の flat 系統（`penetrationflat`）を捨てるか fraction 換算するか。
6. **(I2-a)** gear非依存方針の下で `attack-power` を roll 対象にするか（attribute-map の `attack_power` を mob/表示専用に限定するか）。
7. **(I2-b)** 防御/属性 roll キー（`armor-defense-rate`/`armor-strength`/`max-health`/`knockback-resistance`/`move-speed`）のうち**どれを roll.yml に新設**してアイテム供給対象にするか（= B∩C の素集合を埋める最小セットの確定）。
8. **(穴)** パイプラインに在るが roll 源の無い `damageModifier`(行8)・`fixedDamage`(行9) に roll キーを新設するか、PDC直書き/他経路供給にするか。
9. **(Q3連動)** Parry/Coating/PowerAttack/Charge/Adrenaline/Rage サブ機構を採用するか（採否がstat化の前提）。
