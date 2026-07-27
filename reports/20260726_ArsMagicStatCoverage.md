# Ars魔法に「乗るステ / 乗らないステ」洗い出し (2026-07-26)

ユーザー要望「アイテムドロップボーナス系のステータスなどArsの魔法に乗るとまずいステータス、
逆に現状乗りそうなのにならないステータスの洗い出し」への回答。

## 前提: 魔法ダメージが読むステは7キーだけ

ArsPaperフォークの `TrinityForgeBridge#resolveMagicAttackStats` は、詠唱者の**全**集計ステを
`WeaponAttackStatResolver#bridgeStats` に渡すが、そこで `AttackStats` に写るのは
`combat/AttackStatKeys.java` が定める**7キーのみ**。

| 魔法ダメージに乗る7キー |
|---|
| `flat-bonus-damage` / `percent-bonus-damage` / `crit-chance` / `crit-damage` / `penetration` / `damage-modifier` / `fixed-damage` |

集計元は「メインハンドを除く装備(防具4部位＋オフハンド適用時)＋パーク＋アドオン ＋ 触媒自身の解決済みステ」。
メインハンド武器のステを意図的に外しているのは、触媒使用時は触媒のステで代替するため。

## 1. 「乗るとまずい」もの

### 1-A. 実害あり → **修正済み (2026-07-26)**

**`melee-knockback` / `stun-chance` が Ars魔法に乗っていた。**

`skilltree/runtime/NativeCombatPerkListener#onMelee` が
「ダメージ元が `Player` か」だけで通しており、`DamageCause` を見ていなかった。
フォークの `applyMagicDamage` は `DamageSource.builder(DamageType.MAGIC).withCausingEntity(caster)` で
`victim.damage(...)` を呼ぶため、**詠唱者=Playerの `EntityDamageByEntityEvent` として成立**し、
近接専用のノックバックとスタンが魔法にも乗っていた。反射ダメージ等、
プレイヤー起因で他プラグインが発火する任意のダメージでも同様に乗る状態だった。

修正: `CombatListener` と同じ `MELEE_CAUSES`(`ENTITY_ATTACK` / `ENTITY_SWEEP_ATTACK`)でゲート。
集合の中身を固定する回帰テストを追加済み。

### 1-B. 懸念されたが実害なし

**ドロップ系・EXP系ステータスは魔法ダメージ計算に一切入らない。**
`mob-drop-bonus` / `gacha-rate-bonus` / `skill-exp-bonus` / `mining-fortune` / 各種 extra-drop は
上記7キーに含まれないため、`bridgeStats` を通らない。

ただし**キル時**のドロップ/EXPボーナスは魔法キルでも発動する
(`NativeSurvivalPerkListener#onDeathDrops` は `EntityDeathEvent` + `getKiller()` で判定し、
`DamageCause` を見ていない)。これは**望ましい挙動**と判断した — 魔法で倒したときだけ
ドロップボーナスが無効になる方が理不尽なため。変更していない。

## 2. 「乗りそうだが乗らない」もの

| ステ | 状態 | 判定 |
|---|---|---|
| `attack-power` | 乗らない | **設計どおり**。触媒の `attack-power` は「グリフ基礎ダメージへの加算値」として別経路で合成される(`catalystAttackPowerAddend`)。二重計上を避けるための意図的な除外 |
| `bleed-chance` / `bleed-damage` | 乗らない | **意図的**。`CombatListener.java:395` に `melee only` と明記。魔法で出血を付けるかは設計判断が必要 → **要ユーザー判断(下記)** |
| 弓系7キー(`bow-accuracy`/`ammo-save-chance`/`distance-damage-bonus`/`arrow-piercing`/`arrow-velocity`/`bow-cooldown-reduction`/`arrow-knockback`) | 乗らない | **正しい**。射撃固有の挙動 |
| `melee-knockback`/`stun-chance`/`power-attack-damage`/`power-attack-radius` | 乗らない(修正後) | **正しい**。`power-attack-*` は元から `CombatListener` の `MELEE_CAUSES` ゲート内side にあり漏れていなかった |
| `cooldown-reduction`(武器CT) | 乗らない | **正しい**。武器CTゲージ専用 |

## 3. 要ユーザー判断

**魔法に出血(`bleed-chance` / `bleed-damage`)を乗せるか。**
現状は近接専用。魔導士系ビルドが出血ステを装備で拾っても完全に死にステになる。
- 乗せる → 魔法ビルドで出血装備が生きる。ただし魔法のDPSが上がる
- 現状維持 → 「出血は近接の個性」として明確。装備選択の指針にもなる

私の推奨は**現状維持**。魔法側には会心・貫通・固定ダメージが既に通っており、
出血まで乗せると近接の差別化要素が消えるため。

## 4. CMB-13(魔法の二重軽減)の再調査 — **監査の指摘は誤り。ただし別の論点が1つ残る**

### 4-A. 防具のProtection(`MAGIC` modifier)の二重軽減は **起きていない**(監査の誤り)

`SymmetricCombatService#componentResult` の `extraDefense` 引数のjavadocに設計意図が明記されている:

> used by the **physical** entry points to fold in `vanillaProtectionDefense` without polluting
> `resolveDefender`, which is also shared by the **magical**/DoT/hybrid paths
> **where TF never zeroes the vanilla `MAGIC` modifier and so must NOT double-apply this re-derivation.**

つまり:
- **物理経路**: TFがバニラの `MAGIC` modifier を0化する → TFが自分でProtectionを再導出して適用
- **魔法経路**: TFはバニラのmodifierを0化しない → TFはProtectionを**適用しない**。バニラが1回だけ適用

役割分担が成立しており、Protectionの二重適用は無い。**CMB-13は取り下げ。**

### 4-B. ただしポーションの `RESISTANCE` は二重の可能性がある(**未確定・要検証**)

`resolveDefender` は `potionResistanceReduction`(Lv×10%)を**全ダメージタイプ共通**で加算している
(コメント「resolveDefender runs once per component type, so the same addend lands on both types」)。
一方 4-A のとおり魔法経路ではバニラのmodifierが畳まれないため、
バニラ側の `RESISTANCE` modifier も生きているはず = **同じポーションが2回効く**。

**これは静的な読みだけでは確定できない**。確定には次が必要:
1. `DamageType.MAGIC` の `victim.damage(...)` でバニラの `RESISTANCE` modifier が実際に非ゼロか(実機確認)
2. モブ→プレイヤーの魔法経路(`magicalFinalDamageFromMob`)が、modifierを畳むイベント経由かどうか

安易に `potionResistance` を魔法経路から外すと、畳んでいる経路では**耐性が丸ごと消える**逆バグになる。
リスクが高いため**実装せず、要ユーザー判断として残す**。
