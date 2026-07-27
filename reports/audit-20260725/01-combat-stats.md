# 01 — 戦闘計算・ステータス系 監査レポート

- **担当領域**: `TrinityForge/src/main/java/com/trinityforge/combat/`, `stats/`, 戦闘系 `listeners/`, `resources/combat/`, `resources/stats/`, EliteMobsフォークのダメージ経路, ArsPaperフォークの魔法委譲
- **IDプレフィクス**: `CMB-`
- **監査日**: 2026-07-25
- **方針**: 読み取りのみ。コード変更なし。

---

## サマリ表

| ID | 重要度 | 分類 | 場所 | 一行要約 |
|---|---|---|---|---|
| CMB-01 | HIGH | BUG | listeners/CombatListener.java:217-231 | 不変Mapへ`merge()`し `UnsupportedOperationException` → メイス(Breach)/コーティング武器で被弾処理が全断 |
| CMB-02 | HIGH | BUG | fork TrinityForgeCombatListener.java:43-52 + CombatListener.java:159,259 | player→eliteでeliteの防御/回避/会心が**2回**適用される(二重適用は現存・確定) |
| CMB-03 | HIGH | BUG | skilltree/runtime/PerkAttributeApplier.java:174,219 | 装備変化/耐久減少のたびにMAX_HEALTH modifierをclear→再addし、現在HPが素の上限まで削られる |
| CMB-04 | HIGH | BALANCE | combat/mob-types.yml:91-102 | 3つの乗算軽減枠すべてに0.007/levelを掛けており Lv100で97.3%、Lv143で99.8%軽減 |
| CMB-05 | HIGH | BALANCE | mobs/MobStatScaling.java:42-47 + mob-types.yml:104 | HP指数成長がバニラ`max_health`上限1024で無言飽和(Lv19以降HPが伸びない) |
| CMB-06 | HIGH | BUG | fork PlayerDamagedByEliteMobEvent.java:515-519 + CombatListener.java:815-836 | attack刻印eliteに対して盾ブロックが完全に無効化される |
| CMB-07 | HIGH | BALANCE | stats/item-stats.yml:4292 vs combat/damage.yml:41-42 | 装備で816倍・combatレベルで最大2.0倍。「gear非依存」設計原則が実質崩壊、終盤は相互ワンパン |
| CMB-08 | HIGH | BALANCE | combat/mob-types.yml:148-149 | モブ攻撃力が1.03^Lvで伸びる一方プレイヤーHPは20→48程度。約Lv70(3500ブロック)でフル装備が確定ワンパン |
| CMB-09 | MEDIUM | BUG | combat/ComponentDamageCalculator.java:98-103 | `damage-modifier`が「整数2〜100なら/100」。×2.0を意図した`2`が0.02になる |
| CMB-10 | MEDIUM | BUG | combat/AttackStatBridge.java:40 + CombatListener.java:217-218 | 中立値1.0の`damage-modifier`を全ソース加算合算している(素手+perk 0.1 → Uniform[0.1,1]) |
| CMB-11 | MEDIUM | BUG | listeners/CombatListener.java:275-280 | スイープ攻撃は必ずチャージ倍率0.2。かつスイープ被害者にも武器の全attack-powerが乗る |
| CMB-12 | MEDIUM | BUG | skilltree/runtime/NativeCombatPerkListener.java:112-126 | 同priority(HIGH)で先に走るため`distance-damage-bonus`がTF武器では捨てられる |
| CMB-13 | MEDIUM | BUG | fork TrinityForgeBridge.java:305-314 | 魔法ダメージがバニラ modifier を畳まないため RESISTANCE/PROTECTION が二重軽減 |
| CMB-14 | MEDIUM | BUG | fork TrinityForgeCombatListener.java:157-168 / EliteMobDamagedByPlayerEvent.java:1138-1143 | フォーク側はメインハンドのみ集計。EliteMobsのダメージ帰属(addDamager)もTF最終値と食い違う |
| CMB-15 | MEDIUM | BUG | progression/RoleBuffsConfig.java:161-171 + PermanentBuffResolver.java:64-66 | ロールバフ/永続バフだけ`PercentStatNormalize`未適用。`penetration: 20`が貫通100%になる |
| CMB-16 | MEDIUM | BALANCE | progression/role-buffs.yml:15-18 | tankの`phys/magic-flat-defense: 40`が全身防具(合計~8〜45)を単独で凌駕、支配的選択 |
| CMB-17 | MEDIUM | BUG | stats/AttributeProjection.java:81 vs combat/PlayerDefenseResolver.java:119 | `armor-defense-rate`が経路により「バニラ防具値」と「[0,1]防御率」の二重意味 |
| CMB-18 | MEDIUM | BUG | listeners/CombatListener.java:312 | `setDamage(BASE,…)`はBLOCKING等の他modifierを再スケールしない→盾の軽減量が元のバニラ基準のまま |
| CMB-19 | MEDIUM | BUG | stats/AttributeApplier.java:149-151 | rollSeed無しアイテムは modifier key が全て`.nosd`に衝突し、同ステの複数装備が1個しか効かない(要確認) |
| CMB-20 | MEDIUM | BUG | listeners/MobTypeSpawnListener.java:187-190, 204-218 | MAX_HEALTH/ARMOR/ARMOR_TOUGHNESSの**全**modifierを他プラグイン分ごと削除する |
| CMB-21 | MEDIUM | BALANCE | mobs/MobLevelScaling.java:18-23 | 距離由来レベルに上限が無い。5万ブロックでLv1000、貫通も1.0飽和し防御ステが全部無意味化 |
| CMB-22 | LOW | DEADCODE | combat/SymmetricCombatService.java:227-257 | `hybridFinalDamageResult`/`typelessFinalDamageFlatResult`に本番呼び出し無し |
| CMB-23 | LOW | DEADCODE | combat/mob-types.yml:97,102 | `flat-defense: 0.007/level`はLv100で0.7。ダメージ規模(数百〜数万)に対し実質死にパラメータ |
| CMB-24 | LOW | DEADCODE | combat/damage.yml:62 | `vanilla-armor.armor-strength-per-point: 0` → バニラtoughness→防具強度の経路が常時no-op |
| CMB-25 | LOW | BUG | fork SkillBonusRegistry.java:258-260 + EliteMobDamagedByPlayerEvent.java:1087-1089 | `isCriticalHit`が常にfalse。残したcritフラグ/ポップアップ用途が死んでいる |
| CMB-26 | LOW | BUG | combat/AttackStats.java:43 vs javadoc:34-35 | penetrationを1.0にcapしているのに「1超で増幅に反転する」とjavadocが述べる矛盾 |
| CMB-27 | LOW | BUG | combat/ComponentInput.java:33-35 | `isActive()`が`percentBonusDamage`を見ない(base=0+percentのみの成分は無視される) |
| CMB-28 | LOW | BUG | listeners/MobTypeSpawnListener.java:59-60 | MONITOR優先度でエンティティを変更している(規約違反) |
| CMB-29 | LOW | BUG | combat/MobAbilityDamage.java:37 | staticカウンタ。フォーク側の`finally`漏れ1回でTFのモブ近接経路が恒久停止する |
| CMB-30 | LOW | REDUNDANCY | combat/PlayerStatAggregator.java:118-202 | 1ヒットあたり同一集計が3〜6回走る(CombatListener×2, NativeCombatPerkListener×1〜2, 防御側×1) |
| CMB-31 | LOW | REDUNDANCY | combat/AttackStatKeys.java / DefenseStatKeys.java | 出荷値が全てハードコード既定と同一。config駆動キー名の間接層が使われていない |
| CMB-32 | LOW | BUG | combat/damage.yml:60 vs CombatDamageConfig.java:129 | 出荷yml(0.015)とスキーマ既定(0.04)がドリフト。`weapon-base-formula.enabled`も false vs true |
| CMB-33 | LOW | COMMENT | config/domains/CombatDamageConfig.java:101-103,177-185 | 「既定 false = bypass」というコメントとスキーマ既定`true`・javadoc「Default true」が三者不一致 |
| CMB-34 | LOW | COMMENT | docs/COMBAT_SYSTEM_SPEC.md:47-50 | 仕様書step2の`base = max(base, 0)`が実装に存在しない(意図的に外した)。仕様書が未追随 |
| CMB-35 | LOW | COMMENT | combat/BleedService.java:108-110 | `// dodged this tick` は到達不能(床が`min-component-damage`=1で常に正)。出血に回避判定は無い |
| CMB-36 | LOW | COMMENT | combat/MeleeChargeMultiplier.java:5-40 | Paper PR/issue番号の考古学35行。EXTERNALIZE候補 |
| CMB-37 | LOW | COMMENT | combat/ComponentDamageCalculator.java:42-55 | step2の設計背景14行がインライン。`docs/COMBAT_SYSTEM_SPEC.md §2.1`と重複、EXTERNALIZE候補 |
| CMB-38 | LOW | COMMENT | listeners/CombatListener.java:262-280 | チャージ減衰の経緯説明19行。レビュー履歴メモ(HIGH指摘1/2)を含みDELETE/EXTERNALIZE候補 |
| CMB-39 | LOW | REDUNDANCY | stats/PercentStatNormalize.java:13-15 / StatVocabulary.java:16-21 | %正規化リスト・語彙・StatsCategoryが3つの手動同期セット。自コメントでドリフトを自認 |
| CMB-40 | LOW | BUG | listeners/CombatListener.java:340-342 | パワーアタックAoEの`0.35`がハードコード(configにもloreにも出ない) |

---

## 詳細

### CMB-01 — HIGH / BUG — 不変Mapへの`merge()`でダメージ処理が例外落ちする

**場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/CombatListener.java:217-231`, `536-543`, `combat/PlayerCombatAggregate.java:32-37`

**事象**: `attackerStats` はパーク攻撃バフとアドオンステが両方空のとき `agg.item()`(= `Map.copyOf` による**不変マップ**)そのものになる。その状態で `merge()` を呼ぶため `UnsupportedOperationException` を投げる。

**影響/再現**: スキルツリーの攻撃系パークを1つも解放しておらず、ArsPaperのスレッドステも書かれていないプレイヤー(＝新規プレイヤーの標準状態)が、
1. **Breachエンチャント付きメイス**で殴る (`enchantBonuses.penetrationBonus() > 0`)、または
2. **コーティング済み武器**で殴る (`coatingBonus > 0`)
とき、`CombatListener.onEntityDamageByEntity` が例外で中断する。以降の `event.setDamage(BASE, …)`・出血・EXP付与・AoEがすべて実行されず、バニラの生ダメージがそのまま通る(TFのダメージ計算が丸ごと消える)。コンソールに毎ヒット例外が出る。

**根拠**:
```java
// PlayerCombatAggregate.java:33
item = item == null ? Map.of() : Map.copyOf(item);      // ← 不変
// CombatListener.java:537-539  (mergeStats)
if (addend.isEmpty()) { return base; }                   // ← 不変マップをそのまま返す
// CombatListener.java:230
attackerStats.merge(StatKeys.canonical("penetration"), enchantBonuses.penetrationBonus(), Double::sum);
```

---

### CMB-02 — HIGH / BUG — player→elite で防御・回避・会心が二重適用される(現存・確定)

**場所**:
- `fork-handoff/elitemobs/elitemobs-fork/src/main/java/com/magmaguy/elitemobs/trinityforge/TrinityForgeCombatListener.java:42-52`
- `fork-handoff/elitemobs/elitemobs-fork/src/main/java/com/magmaguy/elitemobs/api/EliteMobDamagedByPlayerEvent.java:978-979, 1104, 1130, 1156`
- `TrinityForge/src/main/java/com/trinityforge/listeners/CombatListener.java:159-160, 198, 259-260`
- `fork-handoff/elitemobs/.../trinityforge/TrinityForgeSpawnListener.java:75-92`(eliteに`MOB_LEVEL`を刻印＝`MobData.hasProfile()`が真)

**事象**: 1回の `EntityDamageByEntityEvent` に対し、TFの対称パイプラインが**2回**走る。

**コードパス(イベント1回あたりの実行回数を明示)**:

| 順序 | 実行主体 | priority | 何が起きるか |
|---|---|---|---|
| ① | `EliteMobDamagedByPlayerEvent.EliteMobDamagedByPlayerEventFilter#onEliteMobAttacked` | **NORMAL**(既定) | EliteMobs式でbaseを算出 → `EliteMobDamagedByPlayerEvent` を同期発火 |
| ② | フォーク `TrinityForgeCombatListener#onEliteDamagedByPlayer` | HIGHEST(サブイベント) | `physicalFinalDamageFlat(elite, base, playerAttackStats)` → **eliteのdefenseRate/resistance/damageReduction/flatDefenseを適用、dodge 1回、crit 1回** |
| ③ | ① に戻り `event.setDamage(BASE, damage)` (line 1156) | — | ②の結果を元イベントBASEへ書き戻す |
| ④ | TF `CombatListener#onEntityDamageByEntity` | **HIGH** | `vanillaBaseDamage = event.getDamage()`(=③の値) → `physicalFinalDamageResult` → **同じeliteの防御を再度適用、dodge 2回目、crit 2回目** |

**影響/再現**:
- **`attack-power` を持たない武器**(素手、item-stats未登録のバニラ武器、CMD無し斧など)では `tfBaseReplaces == false` なので ④ が ③ の値をベースにする → **eliteの防御(守備力/防御率/耐性/被ダメ軽減)が文字通り2回掛かる**。守備力50/防御率0.5/耐性0.3/軽減0.2のeliteに対し素の100ダメージは `((100-50)*0.28-50)*0.28 = -10.0` → step7の床で1に張り付く。
- **`attack-power` を持つTF武器**では ④ が ③ を捨てるので数値の二重乗算は起きないが、②の**dodge/critロールは無駄撃ち**になり、②で回避成立(damage=0)しても④がフルダメージを書き戻すため**eliteの回避率が効かない**。逆に②のcritはEliteMobsのDamageBreakdown/ヘイト集計にだけ残る。
- combatレベル倍率は④のみなので**1回**(過去レポートの「combatレベルが2回」は現行コードでは解消済み)。防御/crit/dodgeの二重は**未解消**。

**根拠**:
```java
// EliteMobDamagedByPlayerEvent.java:978-979 — 既定priority(NORMAL) = TFのHIGHより先
@EventHandler(ignoreCancelled = true)
public void onEliteMobAttacked(EntityDamageByEntityEvent event) {
// TrinityForgeCombatListener.java:51
applyPhysical(event::setDamage, victim, base, playerAttackStats(player));   // ← 1回目(elite防御+dodge+crit)
// CombatListener.java:259-260
CombatHitResult hit = combatService.physicalFinalDamageResult(
        attacker.getUniqueId(), victim, baseDamage, weaponStats);           // ← 2回目(同じelite防御+dodge+crit)
```
`trinityforge.yml` の `combat-delegation: true`(既定)で常時有効。

---

### CMB-03 — HIGH / BUG — 装備変化のたびにプレイヤーの現在HPが素の上限へ切り詰められる

**場所**: `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/PerkAttributeApplier.java:170-225`(特に `clearOwnModifiers` 呼び出し:174 と再add:219), `TrinityForge/src/main/resources/skilltree/power.yml:31,41,51`

**事象**: `apply()` は「自分のmodifierを全削除 → 再計算 → 再追加」の順で走る。`max_health` は `ADD_NUMBER` で `Attribute.MAX_HEALTH` に載るため、削除した瞬間に最大HPが下がり、バニラ側(`LivingEntity#onAttributeUpdated`)が現在HPを新しい上限へクランプする。直後に再追加しても**現在HPは戻らない**。

**影響/再現**: `skilltree/power.yml` の活力ノード(+2/+3/+5、合計+10)を解放したプレイヤーは、最大HP30/現在HP30の状態で
- 防具や手持ちを変える
- **武器/防具の耐久が減る**(`EntityEquipmentChangedEvent` は耐久変化でも発火する。クラス javadoc:48-51 に「高頻度(耐久変化)」と明記)
- リスポーンする / 10tick周期の再照合でフィンガープリントが変化する

たびに `30 → (clear) 20 にクランプ → (re-add) 20/30` となる。つまり**戦闘中に殴るたびにパーク由来のHPが剥がれ続け、実効HPが素の20に固定される**。防具のmax-health(item-stats、合計最大+18相当)は item 側 modifier なのでこの経路では影響を受けないが、パーク/永続バフ/base-stats由来分はすべて該当する。

**根拠**:
```java
// PerkAttributeApplier.java:174
clearOwnModifiers(player);
// …:219
instance.addModifier(new AttributeModifier(key, spec.amount(), op, EquipmentSlotGroup.ANY));
// AttributeProjection.java:82
entries.put("max_health", new AttributeMapEntry("max_health", AttributeOperation.ADD_NUMBER, 1.0));
```
(バニラ側のMAX_HEALTH減少時クランプは 1.20.5+ の `LivingEntity#onAttributeUpdated` 実装に依る挙動。実サーバでの目視確認を推奨 — **要確認**)

---

### CMB-04 — HIGH / BALANCE — 3つの乗算軽減枠すべてが同率で伸び、高レベル帯で実質無敵化

**場所**: `TrinityForge/src/main/resources/combat/mob-types.yml:91-102`(defaults) と各EntityType(`137-146` ZOMBIE ほか13種すべて同値), `combat/ComponentDamageCalculator.java:67-74`

**事象**: `defense-rate` / `resistance` / `damage-reduction` の3つはパイプライン上**独立に乗算**される。3枠すべてに `0.007/level` を与えているため、軽減は `(1-0.007L)^3` で立ち上がる。

**影響/再現**(貫通0.08のプレイヤー想定):

| effectiveLevel | 距離(0.02/block) | defenseRate | resistance | damageReduction | 合成通過率 | 実質軽減 |
|---|---|---|---|---|---|---|
| 0 | 0 | 0.00 | 0.00 | 0.00 | 1.000 | 0% |
| 30 | 1,500 | 0.21 | 0.21 | 0.21 | 0.508 | 49% |
| 60 | 3,000 | 0.42 | 0.42 | 0.42 | 0.207 | 79% |
| 100 | 5,000 | 0.70 | 0.70 | 0.70 | 0.032 | 96.8% |
| 143+ | 7,150+ | 0.90(cap) | 0.90(cap) | 0.90(cap) | 0.00172 | **99.83%** |

`defense.max-mitigation-rate: 0.9` は各枠に個別に効くだけで、3枠の積には効かない(`DefenseStats#cappedMitigation` は field-wise)。Lv143以上ではどんな装備でも `min-component-damage: 1` に張り付く。`armor-strength`(会心軽減率)も同率なのでLv143で会心の上乗せが完全消滅する。

**根拠**:
```yaml
# mob-types.yml:93-97
    physical:
      defense-rate: 0.007
      resistance: 0.007
      damage-reduction: 0.007
```
```java
// ComponentDamageCalculator.java:67-74
base *= (1 - defense.defenseRate() * (1 - attack.penetration()));
base *= (1 - defense.resistance());
base *= rollDamageModifierMultiplier(attack.damageModifier(), unitRandom);
base *= (1 - defense.damageReduction());
```

---

### CMB-05 — HIGH / BALANCE — モブHPの指数成長がバニラ`max_health`上限で無言飽和する

**場所**: `TrinityForge/src/main/java/com/trinityforge/mobs/MobStatScaling.java:42-47`, `listeners/MobTypeSpawnListener.java:181-198`, `resources/combat/mob-types.yml:104`(`max-health-growth: 1.055`, base 380), `resources/combat/mob-import.yml`(elite: base 150.0 / growth 1.072)

**事象**: `effective = (base + coeff*L) * growth^(L/interval)` を `Attribute.MAX_HEALTH` の `setBaseValue` に書くが、バニラの `max_health` 属性はレンジ `[1, 1024]` にサニタイズされる。TF/フォークのどこにもこの上限を意識したコードは無い(`grep 1024` でヒット無し)。

**影響/再現**:
- vanilla mob-types: `380 * 1.055^L` は **L=19 で 1043 > 1024** に到達。以降どれだけ遠方でもHPは1024で頭打ち。設計上の成長カーブ(L=100で80,270)は実現しない。
- EliteMobs輸入プロファイル: `150 * 1.072^L` は **L=32 で 1392 > 1024**。同様に飽和。
- 一方 `MobTypeSpawnListener.applyMaxHealth:193-197` は `setHealth(80270)` が `IllegalArgumentException` を投げるのを捕まえて `min(value, attr.getValue())` にフォールバックするだけなので、**エラーもログも出ずに静かに縮む**。
- 結果として、防御%と攻撃力だけが伸び続けHPが伸びないカーブになり、CMB-04/CMB-07 と合わさって「相互ワンパン」状態になる。

**根拠**:
```java
// MobStatScaling.java:45-46
ConversionPolicy.Ramp ramp = new ConversionPolicy.Ramp(baseMaxHealth, maxHealthCoeff, growth, growthInterval);
return Math.max(1.0, ramp.at(level));
// MobTypeSpawnListener.java:191-197
attr.setBaseValue(value);
try { entity.setHealth(value); }
catch (IllegalArgumentException ex) { entity.setHealth(Math.max(1.0, Math.min(value, attr.getValue()))); }
```
(バニラ `max_health` の上限1024はMinecraft 1.20.5+の`RangedAttribute`定義。実測での確認を推奨 — **要確認**)

---

### CMB-06 — HIGH / BUG — attack刻印eliteに対して盾ブロックが完全に無効

**場所**: `fork-handoff/elitemobs/.../api/PlayerDamagedByEliteMobEvent.java:513-519`, `TrinityForge/src/main/java/com/trinityforge/listeners/CombatListener.java:815-836`

**事象**: EliteMobsは NORMAL priority で
1. 盾ブロック時の80%軽減を `newDamage` に**畳み込み**、
2. `ABSORPTION` 以外の**全バニラmodifier(BLOCKING含む)を0化**する。

その後TFの `handleMobToPlayerDamage` が HIGH priority で走り、`attack-power` 刻印があるeliteでは `physicalFinalDamageFromMobResult` が `vanillaBaseDamage`(=EliteMobsの`newDamage`)を**捨てて**刻印attack-powerを基底に採る(`SymmetricCombatService.java:97-99`)。

**影響/再現**: `mob-profiles.yml` に `attack:` を持つeliteの近接攻撃に対し、プレイヤーが盾で受けても
- BLOCKING modifier は既に0化済み(TFは再注入しない)
- 80%軽減を含む `newDamage` はTFが破棄

の二重で消え、**ブロックしてもしなくても被ダメージが同一**になる。`attack:` を持たないeliteでは(フォークの flat 委譲が効くので)ブロックは正しく効くため、mob設定次第で挙動が割れる。

**根拠**:
```java
// PlayerDamagedByEliteMobEvent.java:513-519
if (blocking) newDamage = newDamage - newDamage * MobCombatSettingsConfig.getBlockingDamageReduction();
for (EntityDamageEvent.DamageModifier modifier : EntityDamageByEntityEvent.DamageModifier.values())
    if (event.isApplicable(modifier) && modifier != EntityDamageEvent.DamageModifier.ABSORPTION)
        event.setDamage(modifier, 0);
// SymmetricCombatService.java:97-99
double itemAttackPower = attack.defaultDamage();
double baseDamage = itemAttackPower != 0 ? itemAttackPower : base;   // ← newDamageを捨てる
```

---

### CMB-07 — HIGH / BALANCE — 装備が816倍、combatレベルが最大2.0倍。「gear非依存」原則が崩壊

**場所**: `TrinityForge/src/main/resources/stats/item-stats.yml:118`(WOODEN_SWORD `attack-power: 63`)、`:4292`(NETHERITE_SWORD#142 `attack-power: 51408`)、`resources/combat/damage.yml:41-42`(`level-scaling.per-level: 0.01`)、`resources/progression/combat-level.yml`(`max-level: 100`)

**事象**: `DefaultDamageResolver#scaled` の `base * (1 + perLevel * level)` は combatレベル100でも**×2.0**にしかならない。一方 `attack-power` は 63 → 51,408 と**816倍**。仕様書冒頭の「gear非依存ダメージ: 物理の生与ダメージは『バニラ基準 × mob levelスケール』のみ。装備tierによる与ダメ倍率は排除」(`docs/COMBAT_SYSTEM_SPEC.md §1`)と実装が正面から矛盾している。

**影響/再現**:
- 最終盤の想定: attack-power 51,408 × combatレベル倍率2.0 × damage-modifier期待値0.95 ≒ **97,600**。
- 対する敵はCMB-05によりHP **1024** 上限、CMB-04の軽減0.032を掛けても被ダメ **3,123** → **確定1発**。
- 逆に序盤 WOODEN_SWORD 63 × 0.825 ≒ 52 に対し ZOMBIE の HP 380 → 8発。帯によって必要ヒット数が 8 → 1 に落ちる。
- combatレベルは「難易度ノブ」として設計されているが、実効寄与は最大 +100% で装備差(+81,500%)に埋没している。

**根拠**:
```java
// DefaultDamageResolver.java:52-55
private double scaled(double base, int level, double coefficient) {
    int safeLevel = Math.max(0, level);
    return base * (1 + perLevel * safeLevel) * coefficient;   // perLevel=0.01, max level=100 → 最大 x2.0
}
```

---

### CMB-08 — HIGH / BALANCE — モブ攻撃力の指数成長にプレイヤーHPが追随せず、中盤以降が確定ワンパン

**場所**: `TrinityForge/src/main/resources/combat/mob-types.yml:148-149`(`attack-power-growth: 1.03`), `:123`(`attack-power: 5.5`), `resources/stats/item-stats.yml`(防具 `max-health` 最大4.5/部位), `resources/skilltree/power.yml:31,41,51`(+2/+3/+5)

**事象**: モブ攻撃力は `5.5 × 1.03^L`(19倍/100レベル)で伸びるが、プレイヤー最大HPは 20(バニラ) + 防具最大約18 + パーク10 = **最大48**(2.4倍)にしかならない。守備力(`phys-flat-defense`)は最大でも約45で、これは定数減算なので指数成長に追いつけない。

**影響/再現**(フル最終盤装備: flat-defense 45 / defenseRate 0.36 / resistance 0.36 / HP 48、モブ貫通0.85想定):

| モブLv | 距離 | モブ攻撃力 | 被ダメージ | 必要ヒット数 |
|---|---|---|---|---|
| 30 | 1,500 | 13.3 | 1(床) | 48+ |
| 50 | 2,500 | 24.1 | 1(床) | 48+ |
| 70 | 3,500 | 43.6 | ≈ -0.9 → 1(床) | 48+ |
| 100 | 5,000 | 105.7 | ≈ 36.7 | 2 |
| 120 | 6,000 | 190.9 | ≈ 88 | **1** |

中盤装備(chainmail級: flat-defense 6.4 / HP 36)だと Lv70 で被ダメ約35 → **1〜2発**、Lv100 で **確定ワンパン**。
守備力が定数減算のため「帯適正装備なら床値1に張り付く／帯を外すと即死」という二値状態が復活しており、これは `docs/COMBAT_SYSTEM_SPEC.md §2.1` の2026-07-25改訂が解消したはずの症状そのもの。

**根拠**:
```yaml
# mob-types.yml:122-123, 147-149
    attack:
      attack-power: 5.5
      attack:
        attack-power: 0
        attack-power-growth: 1.03
```

---

### CMB-09 — MEDIUM / BUG — `damage-modifier` の「整数2〜100は/100」ルールが×2.0を0.02にする

**場所**: `TrinityForge/src/main/java/com/trinityforge/combat/ComponentDamageCalculator.java:98-103`, `stats/PercentStatNormalize.java:96-101`

**事象**: `damage-modifier` は「100%に対するendpoint」で 1.0 が中立。しかし正規化が「絶対値が1超100以下の整数なら/100」なので、`2`(=×2倍のつもり)は `0.02` に化ける。`1.5` は非整数なので1.5のまま残る。

**影響/再現**: `mob-types.yml` の `level-coefficients.attack.damage-modifier` に非ゼロを入れると `MobStatScaling#scaleAttack:68` が `base.damageModifier() + coeff * level` で線形加算するため、レベルが上がるにつれ 1.0→2.0→3.0… と整数を跨ぐ。その瞬間だけ 0.02/0.03 に落ち、モブダメージが2%に激減する**レベル依存の不連続**が生じる。item-stats側でも `damage-modifier: 2` と書いた運営者は無言で1/100にされる。

**根拠**:
```java
// ComponentDamageCalculator.java:99-101
if (value > 1.0 && value <= 100.0 && value == Math.floor(value)) {
    return value / 100.0;
}
```

---

### CMB-10 — MEDIUM / BUG — 中立値1.0の`damage-modifier`を全ソース加算合算している

**場所**: `TrinityForge/src/main/java/com/trinityforge/combat/AttackStatBridge.java:40`, `listeners/CombatListener.java:217-218`

**事象**: `AttackStatBridge` は「キーが無ければ1.0」というフォールバックを持つが、`CombatListener` は item(防具+武器+オフハンド+ロール+永続+base-stats) / perkAttack / addon を **加算** で合算した後の1枚のマップを渡す。中立値が1.0のステを加算合算するのは意味論的に誤り。

**影響/再現**:
- 素手 + パークが `damage_modifier: 0.1`(「+10%」のつもり)を持つ場合 → endpoint 0.1 → `Uniform[0.1, 1.0]` → **平均55%減**。
- 武器 `0.65` + オフハンド(`offhand-stats-apply: true`) `0.65` → 1.30 → `Uniform[1.0,1.30]` → **平均15%増**。武器を持ち替えず盾を持つだけで火力が上がる。
- `damage_modifier` は `StatVocabulary.ATTACK_KEYS`(:41)に登録済みなのでパークから付与可能。現状の出荷skilltreeには無いが、editorから設定可能な穴。

**根拠**:
```java
// AttackStatBridge.java:40
valueFor(canonical, keys.damageModifier(), 1.0));   // 「キー不在なら1.0」だが合算後は必ず存在する
// CombatListener.java:217-218
Map<String, Double> itemAndPerk = mergeStats(agg.item(), agg.perkAttack());
Map<String, Double> attackerStats = mergeStats(itemAndPerk, agg.addon());
```

---

### CMB-11 — MEDIUM / BUG — スイープ攻撃が常にチャージ倍率0.2、かつ全attack-powerが乗る

**場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/CombatListener.java:275-280`, `combat/MeleeChargeTracker.java:47-57`

**事象**: `MELEE_CAUSES` に `ENTITY_SWEEP_ATTACK` が含まれる。バニラは同一tick内で「主命中(ENTITY_ATTACK)→スイープ(ENTITY_SWEEP_ATTACK)」の順にイベントを発火するが、主命中の処理末尾で `meleeChargeTracker.recordAttack(uuid, currentTick)` を実行してしまうため、直後のスイープでは `elapsedTicks == 0` → `chargeFraction == 0` → 倍率が `min-multiplier`(0.2)固定になる。

**影響/再現**:
- TF武器(`tfBaseReplaces == true`)でのスイープダメージは**常に20%**。フルチャージ攻撃でも変わらない。
- 同時に、スイープ被害者にもTFの `attack-power` が丸ごと基底として適用される(バニラのスイープダメージ `1 + …` は捨てられる)。NETHERITE_SWORD#142 なら **周囲の敵全員に 51,408 × 0.2 ≒ 10,282 相当**が入る。「20%固定の巨大AoE」という設計されていない挙動。
- `aoe-radius`/`aoe-damage-rate` の正規AoE機構(`maybeApplyAreaDamage`)とは別系統で二重に存在する。

**根拠**:
```java
// CombatListener.java:275-280
if (MELEE_CAUSES.contains(event.getCause())) {          // ENTITY_ATTACK と ENTITY_SWEEP_ATTACK の両方
    if (tfBaseReplaces) { total *= meleeChargeMultiplier(attacker); }
    meleeChargeTracker.recordAttack(attacker.getUniqueId(), Bukkit.getCurrentTick());
}
```

---

### CMB-12 — MEDIUM / BUG — 同priorityの登録順依存で `distance-damage-bonus` が消える

**場所**: `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativeCombatPerkListener.java:112-126`, `TrinityForge.java:343-345`(NativeCombatPerkListener登録) と `:382-387`(CombatListener登録)

**事象**: 両者とも `EntityDamageByEntityEvent` の `EventPriority.HIGH`。Bukkitは同priority内を登録順で実行するので **NativeCombatPerkListener が先**。`onProjectileDamage` は `event.setDamage(event.getDamage() * (1 + …))` で生ダメージを増やすが、直後の `CombatListener` は `tfBaseReplaces == true`(TF弓/クロスボウ)の場合 `event.getDamage()` を使わず `attack-power` を基底に採るため、**距離ボーナスは無言で破棄**される。

**影響/再現**: `distance-damage-bonus` を持つパーク/装備は、`attack-power` を持たないバニラ弓でのみ効き、TFの弓では一切効かない。`stats/lore.yml` にはステとして表示されるので「効いているように見えて効かない」。

**根拠**:
```java
// NativeCombatPerkListener.java:112-125
@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
public void onProjectileDamage(EntityDamageByEntityEvent event) {
    …
    event.setDamage(event.getDamage() * (1.0 + distanceBonus * Math.min(64.0, blocks) / 16.0));
// CombatListener.java:253-258
if (tfBaseReplaces) { baseDamage = adjustedWeapon + perkAttackPower + addonAttackPower; }
else { baseDamage = vanillaBaseDamage + perkAttackPower + addonAttackPower; }
```

---

### CMB-13 — MEDIUM / BUG — 魔法ダメージでバニラ RESISTANCE / PROTECTION が二重軽減される

**場所**: `fork-handoff/arspaper/fork/src/main/java/com/arspaper/integration/TrinityForgeBridge.java:305-314`, `TrinityForge/src/main/java/com/trinityforge/combat/SymmetricCombatService.java:318-326`

**事象**: `applyMagicDamage` は `victim.damage(finalDamage, DamageSource(DamageType.MAGIC))` で適用するだけで、`EntityDamageEvent.DamageModifier` を一切畳まない。一方TF側の `magicalFinalDamageResult` は既に
- `potionResistanceReduction`(RESISTANCE ポーション、Lv×10%)を耐性%へ加算 (`SymmetricCombatService.java:318-326`)
- プレイヤー防御側では `vanillaArmorDefense` も MAGICAL 成分へ適用 (`:336`)

を済ませている。TFの `CombatListener` は cause が `MAGIC` なので発火しない(`resolveAttacker` は MELEE/PROJECTILE のみ)。

**影響/再現**: Resistance ポーションを飲んだプレイヤーが魔法を受けると、TFの耐性%(Lv×10%)と、エンジンの RESISTANCE modifier(Lv×20%)が**両方**掛かる。Resistance II なら `(1-0.20)×(1-0.40) = 0.48` で52%軽減(意図は20%軽減)。Protection エンチャントも同様にTFの計算後に上乗せされる。

**根拠**:
```java
// TrinityForgeBridge.java:310-314
DamageSource source = DamageSource.builder(DamageType.MAGIC).withCausingEntity(caster)…build();
victim.damage(finalDamage, source);       // modifier を一切 0 化しない
// SymmetricCombatService.java:322-326
double potionResistance = potionResistanceReduction(living);
DefenseStats withPotion = base.stats().combine(new DefenseStats(0, potionResistance, 0, 0, 0));
```

---

### CMB-14 — MEDIUM / BUG — フォーク側の攻撃ステ集計がTFと非対称、ダメージ帰属もズレる

**場所**: `fork-handoff/elitemobs/.../trinityforge/TrinityForgeCombatListener.java:157-168`, `.../api/EliteMobDamagedByPlayerEvent.java:1138-1143`

**事象**:
1. フォークは `WeaponAttackStatResolver#forItem(mainhand)` だけを使う。TF側(`PlayerStatAggregator`)は防具4部位+オフハンド+パーク+アドオン+ロール+永続+base-stats を合算する。同じ「プレイヤーの攻撃ステ」に2種類の定義が併存している。
2. EliteMobs は `eliteEntity.addDamager(player, damage)` に**フォーク経由の値**を渡す(:1142)。その後TFがBASEを上書きするため、EliteMobsのダメージランキング(=戦利品/報酬の分配根拠)は実際に与えたダメージと一致しない。

**影響/再現**: 防具やパークにcrit/貫通を積んでもelite戦のフォーク経路では反映されない(CMB-02の①②区間)。また複数人でボスを殴った場合、`attack-power` の有無や `damage-modifier` の差で addDamager の値が実ダメージから大きく乖離し、MVP判定/ドロップ配分が歪む。

**根拠**:
```java
// TrinityForgeCombatListener.java:163
AttackStats stats = resolver.forItem(player.getInventory().getItemInMainHand());
// EliteMobDamagedByPlayerEvent.java:1138-1142
damage = eliteMobDamagedByPlayerEvent.getDamage();
if (validPlayer) { eliteEntity.addDamager(player, damage); }
```

---

### CMB-15 — MEDIUM / BUG — ロールバフ / 永続バフだけ `PercentStatNormalize` が掛からない

**場所**: `TrinityForge/src/main/java/com/trinityforge/config/domains/RoleBuffsConfig.java:161-171`, `progression/PermanentBuffResolver.java:64-66`

**事象**: `item-stats.yml`(`ItemStatsConfig.java:756,786,836`)と skilltree(`SkillTreeConfig.java:403`)と base-stats(`BaseStatsConfig.java:120`)は `PercentStatNormalize.coerce` を通すが、`role-buffs.yml` と achievements/collection の `permanent-buffs` は通さない。RoleBuffsConfig は名前ベースのクランプだけを行い、しかもそのクランプが `_chance`/`_reduction`/`_rate`/`percent` を含まないキーを **FLAT扱い(上限400)** にする。

**影響/再現**: `role-buffs.yml` の `attack-buffs` に
- `penetration: 20`(20%のつもり) → coerce無し・FLAT扱いで通過 → `AttackStats` の `capAtOne` で **貫通100%**
- `phys-resistance: 30`(30%のつもり) → 同様に **耐性100%** → `max-mitigation-rate` の0.9でcap
- `crit-chance: 5` → `_chance` なので percent 扱い上限1.0 → **会心率100%**

と書けてしまう。同じ数字を `item-stats.yml` に書けば正しく0.20/0.30/0.05になるため、config編集者が同じ値の書き方を場所ごとに変えねばならない。

**根拠**:
```java
// RoleBuffsConfig.java:167-168
String canonicalKey = key.replace('-', '_');
map.put(canonicalKey, clampBuffValue(canonicalKey, sec.getDouble(key)));   // coerce 呼び出し無し
// PermanentBuffResolver.java:65
source.forEach((key, value) -> target.merge(StatKeys.canonical(key), value, Double::sum));  // 同上
```

---

### CMB-16 — MEDIUM / BALANCE — tankロールの守備力40が装備全体を単独で上回る

**場所**: `TrinityForge/src/main/resources/progression/role-buffs.yml:13-19`

**事象**: `tank.defense-buffs` が `phys-flat-defense: 40.0` / `magic-flat-defense: 40.0` / `damage-reduction: 0.05` を与える。守備力はパイプライン step2 の**定数減算**で、%軽減より前に効く。

**影響/再現**:
- 中盤装備(chainmail一式)の `phys-flat-defense` 合計は約 6.4。tankロール単体が **6倍以上**。
- 最終盤装備(1部位16.68×4≒67)でようやく同等になるので、序盤〜中盤は「tank一択」。
- CMB-08の表の通り、tankは Lv70 のモブ攻撃(43.6)ですら `43.6 - 40 - 6.4 < 0` で **床値1** に落ち、実質無敵になる。ロール選択が難易度スイッチと化す。

**根拠**:
```yaml
# role-buffs.yml:13-19
  tank:
    label: タンク
    defense-buffs:
      phys-flat-defense: 40.0
      magic-flat-defense: 40.0
      damage-reduction: 0.05
```

---

### CMB-17 — MEDIUM / BUG — `armor-defense-rate` が経路により2つの異なる単位を持つ

**場所**: `TrinityForge/src/main/java/com/trinityforge/stats/AttributeProjection.java:81`, `combat/DefenseStatBridge.java:56`, `combat/PlayerDefenseResolver.java:119`, `combat/PlayerStatAggregator.java:169-199`

**事象**: 同じキーが3つの意味で使われる。

| 経路 | 意味 | 実装 |
|---|---|---|
| item-stats の装備ステ | **バニラ `Attribute.ARMOR` のポイント数**(例 2 = +2防具値) | `AttributeProjection.java:81` ADD_NUMBER → `VanillaArmorMapping` が `×0.015` で防御率へ |
| item map(DefenseStatBridge) | **0固定**(二重計上ガード) | `DefenseStatBridge.java:56` |
| perk / permanent-buffs / base-stats | **[0,1] の防御率そのもの** | `PlayerDefenseResolver.java:119`, `PlayerStatAggregator.java:173-198` |

**影響/再現**: `combat/base-stats.yml` の `armor-defense-rate` に「防具値8相当」のつもりで `8` と入れると、`DefenseClamp.maxRate=1` で 1.0(=防御率100%)にクランプされ `max-mitigation-rate` の0.9まで通ってしまう。逆に `0.1` と入れると item-stats 感覚では「防具値0.1」に見えるが実際は防御率10%。editorやloreは同一名で表示するため区別できない。

**根拠**:
```java
// AttributeProjection.java:81  (item経路 = バニラ防具値)
entries.put("armor_defense_rate", new AttributeMapEntry("armor", AttributeOperation.ADD_NUMBER, 1.0));
// PlayerDefenseResolver.java:118-119  (perk経路 = [0,1]防御率)
return new DefenseStats(
        stats.getOrDefault(StatKeys.canonical("armor-defense-rate"), 0.0), // 防御率%
```

---

### CMB-18 — MEDIUM / BUG — `setDamage(BASE, …)` が他modifierを再スケールしないため盾の軽減量が不整合

**場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/CombatListener.java:301-313, 826-836`

**事象**: TFは `ARMOR`/`RESISTANCE` のみ0化し、`BLOCKING`/`ABSORPTION` は「エンジンにそのまま適用させる」方針(:297-300のコメント)。しかしエンジンが算出した BLOCKING の値は**変更前のバニラ基底ダメージ**を元にした絶対値であり、`setDamage(DamageModifier.BASE, x)` は他modifierを比例再計算しない。

**影響/再現**: バニラ基底5.0のモブ攻撃に対し BLOCKING が -5.0 と算出済みの状態で、TFがBASEを 105(attack-power刻印モブ)に書き換えると最終ダメージは 100。盾は「5だけ」軽減する。逆にTFがBASEを下げた場合は BLOCKING が過剰に効き、最終が負になり得る。プレイヤー側の防御体験がTFの数値スケールに追随しない。

**根拠**:
```java
// CombatListener.java:297-305 (ARMOR/RESISTANCE のみ0化、BLOCKINGは温存)
for (EntityDamageEvent.DamageModifier modifier : DAMAGE_MODIFIERS) {
    if (FOLDED_MODIFIERS.contains(modifier) && event.isApplicable(modifier)) { event.setDamage(modifier, 0.0); }
}
// :312
event.setDamage(EntityDamageEvent.DamageModifier.BASE, Math.max(0.0, total));
```
(Bukkitの`setDamage(DamageModifier,double)`が他modifierを再計算しないことは`setDamage(double)`との差異。実測確認を推奨 — **要確認**)

---

### CMB-19 — MEDIUM / BUG — rollSeed無しアイテムの attribute modifier キーが衝突する

**場所**: `TrinityForge/src/main/java/com/trinityforge/stats/AttributeApplier.java:146-152, 307-310`

**事象**: modifier の `NamespacedKey` は `statmod.<statKey>.<rollSeed16進>` で、rollSeed が無いアイテムは一律 `.nosd` になる。バニラの `AttributeMap` は同一 `ResourceLocation` の modifier を「置換」扱いにするため、同じ属性・同じキーの modifier を複数装備が持つと**1つしか効かない**。

**影響/再現**: `item-stats.yml` にバニラ素材(CMD無し)エントリとして登録された防具を、TFの鍛冶を通さず素のまま装備した場合(rollSeed無し)、4部位すべてが `statmod.armor_defense_rate.nosd` / `statmod.max_health.nosd` を持つため防具値も最大HPも**1部位分しか反映されない**。コード自身がこの罠を認識(:147-148)しているが、`.nosd` フォールバックが穴として残っている。

**根拠**:
```java
// AttributeApplier.java:146-151
// Using only statKey causes different equipped items to share the same UUID and therefore
// overwrite each other instead of stacking. rollSeed is stable per TF-stamped item.
String rollSeedSuffix = ItemData.of(meta).rollSeed()
        .map(seed -> "." + Long.toHexString(seed))
        .orElse(".nosd");
```
(素のバニラ防具に `AttributeApplier` が走るか＝`ItemRefreshListener`の適用範囲によるため **要確認**)

---

### CMB-20 — MEDIUM / BUG — モブスポーン時に他プラグインのattribute modifierまで削除する

**場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/MobTypeSpawnListener.java:187-190, 204-218`

**事象**: `applyMaxHealth` は `MAX_HEALTH` の**全 modifier** を、`syncVanillaArmorIcons` は `ARMOR`/`ARMOR_TOUGHNESS` の**全 modifier** を無条件に除去してから `setBaseValue` する。しかも `scheduleHealthReassert` で1tick後にもう一度実行する。

**影響/再現**: 同じモブに attribute modifier を付ける他プラグイン(MythicMobs, レイド強化, ポーション由来の一部)や、モブが**装備している防具由来のARMOR modifier**が消える。装備防具の見た目は残るのに防御値が0になる。`ARMOR_TOUGHNESS` は問答無用で `setBaseValue(0.0)`。

**根拠**:
```java
// MobTypeSpawnListener.java:188-191
for (AttributeModifier modifier : attr.getModifiers()) { attr.removeModifier(modifier); }
attr.setBaseValue(value);
// :212-217
for (AttributeModifier modifier : toughness.getModifiers()) { toughness.removeModifier(modifier); }
toughness.setBaseValue(0.0);
```

---

### CMB-21 — MEDIUM / BALANCE — 距離由来モブレベルに上限が無い

**場所**: `TrinityForge/src/main/java/com/trinityforge/mobs/MobLevelScaling.java:18-23`, `resources/combat/mob-types.yml:110`(`coordinate-coefficient: 0.02`)

**事象**: `effectiveLevel = base + floor(distance * 0.02)` を `Integer.MAX_VALUE` までしか丸めない。上限config が存在しない(`combat-level.yml` の `max-level: 100` はプレイヤー側だけ)。

**影響/再現**:
- 50,000ブロック地点で **Lv1000**。`attack-power = 5.5 × 1.03^1000 ≒ 3.9e14`、`penetration = 0.05 + 0.008×1000 = 8.05` → `AttackStats` の cap で1.0(=防御率完全無視)。
- Lv119(5,950ブロック)で既に貫通1.0に飽和し、以降プレイヤーの防御率(バニラ防具由来)は**完全に無効**。
- ネザーは1ブロック=8ブロック相当の移動なので、ネザーゲート往復だけで容易に到達する座標帯。
- `mob-level-table.yml` は `tiers: []` で空なので、レベル帯ごとの補正もかからない。

**根拠**:
```java
// MobLevelScaling.java:20-22
long scaled = (long) Math.floor(safeDistance * coefficient);
long result = base + scaled;
return (int) Math.max(0, Math.min(Integer.MAX_VALUE, result));   // 上限クランプ無し
```

---

### CMB-22 — LOW / DEADCODE — 本番呼び出しの無い公開APIメソッド

**場所**: `TrinityForge/src/main/java/com/trinityforge/combat/SymmetricCombatService.java:227-250`(`hybridFinalDamageResult`), `:253-257`(`typelessFinalDamageFlatResult`), `:116-134`(`magicalFinalDamageFromMob*`), `:63-66`(`physicalFinalDamage`)

**事象**: プロジェクト全体(TF本体 + 3フォーク)を検索しても、本番コードからの呼び出しがテストのみ、または皆無。

**影響/再現**: `hybridFinalDamageResult` / `typelessFinalDamageFlatResult` は呼び出し元ゼロ(テストも無い)。`magicalFinalDamageFromMob`/`physicalFinalDamage` はテストからのみ。`DamageType.TYPELESS` 分岐(`SymmetricCombatService.java:309-311`, `DefenseStatBridge.java:40-42`, `PlayerDefenseResolver.java:56-64,107-109`, `MobData.java:107`)も到達不能。総計で約80行が死んでいる。

**根拠**: `grep -rn "hybridFinalDamageResult\|typelessFinalDamageFlatResult" TrinityForge/src fork-handoff/*/` → `SymmetricCombatService.java` の定義のみ。

---

### CMB-23 — LOW / DEADCODE — `flat-defense` のレベル係数が実質無意味

**場所**: `TrinityForge/src/main/resources/combat/mob-types.yml:97, 102`(および各EntityType)

**事象**: `flat-defense: 0.007/level`、base 0。Lv100で **0.7**。同レベル帯のプレイヤー火力は数百〜数万なので、この減算は結果に一切影響しない(浮動小数の丸め以下)。

**影響/再現**: 「モブの守備力」というノブがconfigに存在しeditorにも出るのに、出荷値では常に無効。運営者が「守備力を上げた」つもりでも何も起きない。プレイヤー側の守備力(最大約45+ロール40)とは3桁違う。

**根拠**:
```yaml
# mob-types.yml:96-97
      damage-reduction: 0.007
      flat-defense: 0.007
```

---

### CMB-24 — LOW / DEADCODE — バニラtoughness→防具強度の経路が常時no-op

**場所**: `TrinityForge/src/main/resources/combat/damage.yml:62`(`armor-strength-per-point: 0`), `combat/VanillaArmorMapping.java:44-48`

**事象**: `armorStrength = toughness * 0` なので `VanillaArmorMapping` が返す `armorStrength` は常に0。`ARMOR_TOUGHNESS` 属性を読む `SymmetricCombatService#vanillaArmorDefense:361` も無意味。

**影響/再現**: `ARMOR_TOUGHNESS` の `attributeValue()` 呼び出しと `VanillaArmorMapping` の第2引数が完全な死にコード。ダイヤ/ネザライト防具のtoughnessは戦闘に一切影響しない(仕様通りだが、コード側にその事実を示す実行時ガードが無く毎ヒット読み続けている)。

**根拠**:
```yaml
# damage.yml:62
  armor-strength-per-point: 0
```

---

### CMB-25 — LOW / BUG — EliteMobs側のcrit判定が常にfalse

**場所**: `fork-handoff/elitemobs/.../skills/bonuses/SkillBonusRegistry.java:256-261`, `.../api/EliteMobDamagedByPlayerEvent.java:1087-1089`

**事象**: フォークで `getPlayerSkillLevel` が常に0を返すよう改変された。`isCriticalHit(player)` はスキルレベル由来のcrit率を使うため常に false になる。にもかかわらず `criticalHit` 変数と `EliteMobDamagedByPlayerEvent` へのcritフラグ受け渡し、DebugMessage の `| CRIT` 表示が残っている。

**影響/再現**: 「VorpalStrike等のスキル発動/ダメージポップアップ用に残す」というコメント(:1083-1086)の意図が達成されていない。crit依存のEliteMobsスキルは永久に発動しない。

**根拠**:
```java
// SkillBonusRegistry.java:258-260
// Phase 6(2026-07-18): 武器スキルレベリングは無力化。…常に0を返し発動強度の源を断つ。
return 0;
```

---

### CMB-26 — LOW / BUG — `penetration` のcapとjavadocの記述が矛盾

**場所**: `TrinityForge/src/main/java/com/trinityforge/combat/AttackStats.java:32-35, 43`

**事象**: javadoc は「Penetration above 1 would flip the step-4 defense-rate term into an amplifier」と警告するが、直下のコンパクトコンストラクタが `capAtOne` で1.0に切っているため、その状況は発生し得ない。

**影響/再現**: 読み手を誤誘導する。また、CMB-21のようにモブの貫通が加算で1.0に飽和した際、警告に反して安全に振る舞う(=貫通1.0で防御率完全無効、増幅はしない)。

**根拠**:
```java
// AttackStats.java:43
penetration = capAtOne(finiteOrZero(penetration));
```

---

### CMB-27 — LOW / BUG — `ComponentInput.isActive()` が `percentBonusDamage` を判定に含めない

**場所**: `TrinityForge/src/main/java/com/trinityforge/combat/ComponentInput.java:33-35`

**事象**: `defaultDamage != 0 || flatBonusDamage != 0 || fixedDamage != 0` のみ。`percentBonusDamage` は `defaultDamage` の割合なので単独では意味を持たないが、コメント(:7-11)は「非ゼロの defaultDamage / flatBonusDamage / fixedDamage を持つ成分のみ処理」と述べつつ仕様書 §2.1 の「デフォルトダメージ > 0 の成分のみ実行」とも食い違う(fixedDamageのみでも実行する例外を後付けした結果)。

**影響/再現**: 実害は限定的だが、`min-component-damage` を負(=回復)運用している場合、全ステ0の成分がスキップされるため「回復するはずが何も起きない」という帯が生まれる。

**根拠**:
```java
// ComponentInput.java:34
return attack.defaultDamage() != 0 || attack.flatBonusDamage() != 0 || attack.fixedDamage() != 0;
```

---

### CMB-28 — LOW / BUG — MONITOR優先度でエンティティを変更している

**場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/MobTypeSpawnListener.java:59-60`

**事象**: `@EventHandler(priority = EventPriority.MONITOR)` でありながら PDC 書き込み・属性変更・HP設定を行う。Bukkit規約では MONITOR は観測専用。

**影響/再現**: 他プラグインが MONITOR で「最終状態」を読む前提の場合、実行順によって観測値が食い違う。クラス javadoc(:55-57)は「他プラグインの後に適用するため MONITOR」と意図を説明しているが、規約違反であることは変わらない(HIGHEST + 1tick後の再適用が本来の形)。

**根拠**:
```java
// MobTypeSpawnListener.java:59-60
@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
public void onSpawn(CreatureSpawnEvent event) {
```

---

### CMB-29 — LOW / BUG — `MobAbilityDamage` の深度カウンタが漏れると恒久的にモブ近接が止まる

**場所**: `TrinityForge/src/main/java/com/trinityforge/combat/MobAbilityDamage.java:37, 45-49`, `listeners/CombatListener.java:809-811`

**事象**: `private static int depth` を `mark()`/`clear()` で増減するだけ。フォーク側の `finally` が1回でも実行されない(例: JVMレベルの `Error`、非メインスレッドからの呼び出し、フォーク再ロード)と `depth > 0` が残り続け、`CombatListener.handleMobToPlayerDamage` が**以後すべてのモブ→プレイヤー近接で即return**する。

**影響/再現**: TF刻印モブの攻撃力設定がサイレントに無効化され、EliteMobs/バニラの生ダメージだけが通る。リセット手段は再起動のみ(公開されたリセットAPIが無い)。フォーク側 `TrinityForgeAbilityDamage` は2つのカウンタ(自前 + TFミラー)を持つので、片方だけ漏れる分岐も存在する。

**根拠**:
```java
// MobAbilityDamage.java:37
private static int depth = 0;
// CombatListener.java:809-811
if (com.trinityforge.combat.MobAbilityDamage.isActive()) { return; }
```

---

### CMB-30 — LOW / REDUNDANCY — 1ヒットあたり同一のフル集計が3〜6回走る

**場所**: `TrinityForge/src/main/java/com/trinityforge/combat/PlayerStatAggregator.java:118-202`

**事象**: `aggregate()` は毎回 防具4部位 + メインハンド + オフハンドの `DerivedItemStats.resolve`(YAMLプロファイル照合 + 品質計算 + `RollHash` の正規乱数)+ `PerkBuffResolver` + `RoleBuffResolver` + `PermanentBuffResolver`(アチーブメント/図鑑の全走査)+ `BaseStatsConfig` を再実行する。キャッシュ無し。

**影響/再現**: プレイヤーがモブを1回殴るだけで
1. `CombatListener:214` (攻撃集計)
2. `CombatListener:515` (`startWeaponCooldown` 内)
3. `NativeCombatPerkListener.onMelee:59`
の3回。逆にモブに殴られると `PlayerDefenseResolver.resolve:55` + `NativeCombatPerkListener.onParryDefense:95` の2回。さらに `PerkAttributeApplier` の10tick周期照合とデバウンスapplyでも走る。マルチプレイの乱戦で無視できないTPS圧になる可能性。

**根拠**:
```java
// PlayerStatAggregator.java:141-201  (毎回フル再構築、キャッシュなし)
Map<String, Double> item = armorAndOffhandStats(player, contributorIsOffhand);
…
return new PlayerCombatAggregate(item, mainhand, perkBuffs.attack(), perkDefense, addon, multipliers);
```

---

### CMB-31 — LOW / REDUNDANCY — config駆動のステキー名が実際には固定値

**場所**: `TrinityForge/src/main/java/com/trinityforge/combat/AttackStatKeys.java`, `DefenseStatKeys.java`, `resources/combat/damage.yml:63-78`, `config/domains/CombatDamageConfig.java:137-153`

**事象**: 14個のキー名がすべて「スキーマ既定値 == 出荷yml値 == ハードコード名」で一致。`AttackStatBridge`/`DefenseStatBridge` は `StatKeys.canonical` を両側に掛けるので、リネームしても他所(`PercentStatNormalize`, `StatVocabulary`, `StatCategoryInference`, editor)が追随せず実際には変更できない。

**影響/再現**: 「config駆動なのでリネーム可能」というjavadocの約束(`AttackStatKeys.java:5-6`)が守れない。間接層の分だけ読解コストが増える。

**根拠**:
```yaml
# damage.yml:63-70 — 左右が完全一致
attack-stat-keys:
  flat-bonus-damage: flat-bonus-damage
  percent-bonus-damage: percent-bonus-damage
```

---

### CMB-32 — LOW / BUG — スキーマ既定値と出荷ymlのドリフト

**場所**: `TrinityForge/src/main/resources/combat/damage.yml:38, 60` vs `config/domains/CombatDamageConfig.java:107, 129`

**事象**:
| キー | 出荷yml | スキーマ既定 |
|---|---|---|
| `weapon-base-formula.enabled` | `false` | `true` |
| `vanilla-armor.defense-rate-per-point` | `0.015` | `0.04` |

**影響/再現**: config を消す/壊すとスキーマ既定に戻り、`weapon-base-formula` が有効化されて全武器の attack-power が `1 + useLevel^2/100` 分だけ加算される(Lv100武器で +101)。防具の防御率換算も0.015→0.04(2.7倍)に跳ねる。ymlのコメント(`damage.yml:57-59`)は0.015を前提に説明しており、既定へのフォールバックはその説明と矛盾する。

**根拠**:
```java
// CombatDamageConfig.java:107
.field(SchemaField.of(WEAPON_BASE_FORMULA_ENABLED, SchemaField.Type.BOOLEAN, true))
```
```yaml
# damage.yml:37-38
weapon-base-formula:
  enabled: false
```

---

### CMB-33 — LOW / COMMENT — `magical.scale-with-combat-level` の説明が三者不一致

**場所**: `TrinityForge/src/main/java/com/trinityforge/config/domains/CombatDamageConfig.java:101-103, 177-185`, `combat/SymmetricCombatService.java:194-200`, `resources/combat/damage.yml:36`

**事象**:
- スキーマ直上のコメント: 「既定 false = bypass」
- スキーマ実値: `true`
- アクセサ javadoc: 「Default true」
- `SymmetricCombatService` javadoc: 「By default the magical base damage BYPASSES the combat-level curve (C2)」

4か所のうち2か所が誤り。**DELETE/修正対象**。

**根拠**:
```java
// CombatDamageConfig.java:101-103
// C2 (魔法はcombatレベルbypass): false のとき魔法の基本ダメージにcombatレベル倍率を掛けない。
// 既定 false = bypass。true で物理と同じレベル倍率を適用する(旧挙動)。
.field(SchemaField.of(MAGICAL_SCALE_WITH_COMBAT_LEVEL, SchemaField.Type.BOOLEAN, true));
```

---

### CMB-34 — LOW / COMMENT — 仕様書 §2.1 step2 の0クランプが実装に無い

**場所**: `docs/COMBAT_SYSTEM_SPEC.md:47-50` vs `TrinityForge/src/main/java/com/trinityforge/combat/ComponentDamageCalculator.java:56-57`

**事象**: 仕様書は step2 に `base = max(base, 0)` を明記しているが、実装は意図的にクランプしない(理由は `ComponentDamageCalculator.java:48-52` のコメントに詳述)。仕様書側が未追随。

**影響/再現**: 仕様書を根拠にした実装/レビューが誤る。`min-component-damage` を負値運用する構成の説明が仕様書と実装で食い違う。**仕様書を実装に合わせるべき**(EXTERNALIZE先が仕様書側なので、コメントを削って仕様書を直すのが正しい方向)。

**根拠**:
```markdown
# COMBAT_SYSTEM_SPEC.md:47-50
2. 守備力 : flat = 守備力[該当type]（防具強度は含まない）
            base -= flat
            base = max(base, 0)                       ← ここでの0クランプは…
```

---

### CMB-35 — LOW / COMMENT — 到達不能なコメントと存在しない挙動の記述

**場所**: `TrinityForge/src/main/java/com/trinityforge/combat/BleedService.java:107-110`

**事象**: `bleedFinalDamageFlat` は `Math.max(finalDamage, minComponentDamage)`(既定1.0)を返すので `<= 0` にはならない。`// dodged this tick, or fully mitigated` は二重に誤り(回避判定自体が `bleedFinalDamageFlat` に存在しない)。**DELETE対象**。

**根拠**:
```java
// BleedService.java:107-110
double finalDamage = combatService.bleedFinalDamageFlat(victim, bleed.damagePerTick());
if (finalDamage <= 0) {
    return; // dodged this tick, or fully mitigated
}
```

---

### CMB-36 — LOW / COMMENT — Paper PR考古学35行(EXTERNALIZE候補)

**場所**: `TrinityForge/src/main/java/com/trinityforge/combat/MeleeChargeMultiplier.java:5-40`

**事象**: クラスjavadocが PaperMC PR #13856 / issue #13838 / #13884 / #11552 のマージ日と引用を含む35行。「なぜ `getAttackCooldown()` に依存しないか」という Why は必要だが、詳細な経緯は `docs/` へ移し1行の参照に置き換えるべき分量。

**根拠**: `MeleeChargeMultiplier.java:12-27` に外部issue番号4件と英文引用を含む段落。

---

### CMB-37 — LOW / COMMENT — step2設計背景14行のインライン記述(EXTERNALIZE候補)

**場所**: `TrinityForge/src/main/java/com/trinityforge/combat/ComponentDamageCalculator.java:42-55`

**事象**: 守備力を前倒しした理由・負クランプ運用・逆運用の説明が14行。同内容は `docs/COMBAT_SYSTEM_SPEC.md §2.1` の改訂注記に既にある。**EXTERNALIZE**(1行の参照に圧縮)。

**根拠**: `ComponentDamageCalculator.java:42-55` の連続する日本語コメントブロック(14行)。

---

### CMB-38 — LOW / COMMENT — レビュー履歴メモがコード中に残存

**場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/CombatListener.java:262-280`, `348-358`, `377`

**事象**: 「レビュー修正(HIGH指摘2, 二重減衰)」「B2 レビュー修正(HIGH指摘1)」「一次情報確認済み(セッションレポート参照)」といった作業履歴メモ。指摘番号やセッションレポート参照は後任には解決不能。挙動の理由(バニラ側で既に減衰済みなので二重にしない)だけ残し、履歴は **DELETE**。

**根拠**:
```java
// CombatListener.java:267-271
// レビュー修正(HIGH指摘2, 二重減衰): tfBaseReplaces=false のとき baseDamage は
// vanillaBaseDamage(=event.getDamage())そのもの——…
```

---

### CMB-39 — LOW / REDUNDANCY — 3つの手動同期ステキーセット

**場所**: `TrinityForge/src/main/java/com/trinityforge/stats/PercentStatNormalize.java:13-15`, `stats/StatVocabulary.java:16-21`, `command/StatsCategory`, `stats/StatCategoryInference.java`

**事象**: `RATE_KEYS`(%正規化)、`StatVocabulary` の4チャネル、`StatsCategory`、`StatCategoryInference` の部分一致ルールが**互いに独立**した手動管理。両クラスのjavadocが自らドリフトリスクを明記している。`VanillaAttributeDefaults` だけが static イニシャライザで整合チェックを持つ(良い前例)。

**影響/再現**: 新ステキー追加時に4か所の更新漏れが起きる。実際 CMB-15 の「role-buffs だけ正規化されない」はこの構造の帰結。

**根拠**:
```java
// PercentStatNormalize.java:13-15
// <b>キー追加時の注意</b>: {@link #RATE_KEYS} は {@link StatVocabulary} や {@code stats/lore.yml} とは
// 独立して手動管理されており、自動同期しない。
```

---

### CMB-40 — LOW / BUG — パワーアタックAoEの倍率がハードコード

**場所**: `TrinityForge/src/main/java/com/trinityforge/listeners/CombatListener.java:339-343`

**事象**: `power-attack-radius` が正のとき `AOE_DAMAGE_RATE_KEY: 0.35` を直接埋め込んで `maybeApplyAreaDamage` を呼ぶ。config にも lore にも `0.35` は現れない。

**影響/再現**: 運営がパワーアタックのAoE威力を調整できない。`aoe-damage-rate` という同名のステが別に存在するため、editorで設定しても効かない(=UNIMPLEMENTEDに見える)。

**根拠**:
```java
// CombatListener.java:340-342
maybeApplyAreaDamage(attacker, victim, Map.of(
        AOE_RADIUS_KEY, powerAttackRadius,
        AOE_DAMAGE_RATE_KEY, 0.35), total);
```

---

## 補足: 二重適用の最終判定(論点2への回答)

**結論: 二重適用は現在も存在する。ただし報告されていた5要素のうち残存は4つ。**

| 要素 | 現状 | 根拠 |
|---|---|---|
| 防御(守備力/防御率/耐性/被ダメ軽減) | **2回**(`attack-power` 未設定武器のみ数値に反映。設定済み武器では2回計算されるが1回目が破棄) | CMB-02 表①②④ |
| crit | **2回ロール**(TF側が最終値を支配、フォーク側はEliteMobsの帰属値に残留) | 同上 |
| dodge | **2回ロール**(`attack-power` 設定済み武器では1回目の回避成立が無効化される) | 同上 |
| 貫通 | **2回**(防御と同じ経路) | 同上 |
| combatレベルスケール | **1回のみ**(フォークが `physicalFinalDamageFlat` を使うため。過去の報告時点から解消済み) | `TrinityForgeCombatListener.java:142` |

構造的な原因は「EliteMobs の `EntityDamageByEntityEvent` ハンドラが NORMAL、TF の `CombatListener` が HIGH」であり、TF側に `MobData.of(victim).hasProfile()` による stand-down ガード(elite→player 方向には `hasTrinityForgeAttackStamp` として実装済み)が player→elite 方向に**存在しない**こと。
