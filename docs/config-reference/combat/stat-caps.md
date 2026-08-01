# combat/stat-caps.yml

出荷config `TrinityForge/src/main/resources/combat/stat-caps.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。**

> このファイルの「効くキー一覧」は `tools/config-editor/test/tf-stat-caps-registry.test.js` が機械的に読み取り、
> エディタUI (`STAT_CAPS_SECTIONS`) と過不足なく一致することを検証している。
> キーを増減するときは **UI・Java・このファイルの3点セット**で揃えること。

---

## 概要

プレイヤーの「最終合算値」— 装備 + スキルツリーパーク + 永続バフ + base-stats + 乗算レイヤを
すべて適用し終えた後の値 — に上限(上側だけ)をかける任意設定。

- 既定は「上限なし」= 何も書かなければ挙動は一切変わらない。
- キーは `stats/lore.yml` 等に出るステータス名 (例: `crit-chance`, `mining-fortune`)。
- **「上限なし」と「上限0」の区別**: キーを未記載のままなら「上限なし」。明示的に数値(0を含む)を
  書いた場合だけ「上限0」として機能する。0を書きたい場合はそのまま `key: 0` と書くこと。
  `base-stats.yml` とは逆の規約なので注意 — あちらは 0 = 未設定。
- 負の値も書ける(「常にこの値以下にしたい」という上限としてそのまま使われる)。
- 編集後は `/trinityforge reload` で即時反映。

## 適用範囲(重要)

クランプの適用点は2種類ある。

- **(A)** `PlayerCombatAggregate#totalOf(canonicalKey)` の戻り値
  (装備+パーク+アドオンを加算し乗算レイヤまで適用した後の総合値)。
- **(B)** 2026-07-26 カバレッジ拡大で追加された、`CombatListener` / `PlayerDefenseResolver` が
  「独自に item/perk/addon を合成した最終値」を組み立てる箇所に置いた
  `PlayerCombatAggregate#clamp` の直接呼び出し(totalOf と同じ意味論・同じ CT短縮系除外/非有限値スルーを共有)。

どちらの経路でも、下の「効くキー一覧」のキーは確実に効く。

### 補足

- `attack-power` は「item側のenchant倍率(Sharpness等)を折り込んだ **後** の最終値」に掛かる。
  一般ATTACKチャネルの他キーよりクランプ対象が広い点に注意。

#### ⚠️ `attack-power` は近接と魔法で「上限の意味」が違う (2026-07-31 F5 指摘6)

同じ `attack-power: N` を書いても、掛かる対象が経路によって違う。**是正せず非対称のまま運用する**
(揃えるには合算の設計変更が必要なので別件)。数値を決めるときは必ずこの差を意識すること。

| 経路 | クランプ対象 | 適用点 |
|---|---|---|
| 近接 (TF `CombatListener`) | **合算総量** — 装備 + オフハンド + スキルツリーパーク + 永続バフ + `base-stats` を全部足した後の値 | `PlayerCombatAggregate#clamp` |
| 魔法 (ArsPaperフォーク → `WeaponAttackStatResolver#attackPowerOf`) | **単品** — 触媒/杖 1本が持つ `attack-power` だけ | `WeaponAttackStatResolver#cappedAttackPower` |

つまり `attack-power: 3000` と書いた場合:

- 近接は「全部合わせて 3000 まで」になる。
- 魔法は「杖1本あたり 3000 まで」になる。装備の他部位やパークが乗る分は上限の外側で足される
  ので、**魔法側の実効上限は 3000 より大きくなりうる**。

また `WeaponAttackStatResolver#forItem` が返す他のステ (`crit-chance` / `penetration` など) は
このクランプを通らない。`attack-power` 1キーだけが上限に服する。

「魔法へも近接と同じ上限を掛けたい」場合は、`attack-power` ではなく
`combat/damage.yml` の `magical.attack-power-scale` を下げる方が意図どおりになる
(ただしそちらは PvE も同時に下がる)。
- `armor-defense-rate` は TF側(perk/addon由来)の合計だけが対象。防具の vanilla armor attribute
  ミラー分はここに含まれない(二重計上防止のため元々別経路 — `combat/damage.yml` の `vanilla-armor.*` が
  別途その分を作る)。
- 防御側は `combat/damage.yml` の `defense.max-mitigation-rate` / `max-crit-reduction` が
  既存の専用上限として別途、この後で効く。

## 効くキー一覧

### ATTACK チャネル (totalOf 経由 — CombatListener が読む)

```
power-attack-damage
power-attack-radius
aoe-radius
aoe-max-targets
aoe-damage-rate
reflect-flat
reflect-percent
melee-knockback
stun-chance
stun-duration-bonus
distance-damage-bonus
arrow-knockback
ammo-save-chance
bow-accuracy
arrow-piercing
arrow-velocity
```

`stun-duration-bonus` は割合ではなく tick の加算値です。初期値は
`combat/base-stats.yml` で設定し、装備・パークの値を加算します。最終値は
`NativeCombatPerkListener.MAX_STUN_DURATION_TICKS`（既定100tick）で制限されます。

`melee-knockback` / `arrow-knockback` は 2026-07-31 に単位を揃えました（どちらも `FLAT` +
単位 `m`）。**割合ではないので上限値も生値で書きます**
（`melee-knockback` は同日 `PercentStatNormalize.RATE_KEYS` から外しました。残していると
`2`（=2m のつもり）が `0.02` へ黙って矯正されます）。

#### ⚠ ノックバックの「1m」が内部値のいくらか（ここに値を書く前に必読）

実体は対象の velocity への加算です（`NativeCombatPerkListener`：近接
`MELEE_KNOCKBACK_VELOCITY_PER_UNIT = 0.35`、矢 `ARROW_KNOCKBACK_VELOCITY_PER_UNIT = 0.4`。
どちらも「内部値1あたり 係数 blocks/tick の初速」）。空中の水平減衰 0.91/tick を等比級数で積むと
総移動距離は初速の `1/(1-0.91) = 11.11` 倍（`VELOCITY_TO_BLOCKS`）なので、

| キー | 内部値1 が押し出す距離 | **1m 相当の内部値** | lore の `display-scale` |
|---|---|---|---|
| `melee-knockback` | 約 3.9 m | **約 0.26** | 3.9 |
| `arrow-knockback` | 約 4.4 m | **約 0.23** | 4.4 |

**この `stat-caps.yml` に書く上限値・`combat/base-stats.yml` の初期値・`item-stats` /
`skilltree` の値・PDC はすべて内部値です。** 単位 `m` との桁合わせは表示側だけが行います
（`stats/lore.yml` の `display-scale` → `StatDisplaySpec#toDisplayValue`。lore とチャット/GUI で
値が食い違わないよう換算はこの1か所に集約してあります）。

つまり **「2m まで許す」上限は `2` ではなく `0.5` 前後** です（`2` と書くと表示上 7.8m
＝約8ブロック飛ぶ設定になります）。接地中は摩擦が強い（0.6×0.91）ため実距離は表示より短くなる
概算値です。Java の係数を変えたら `display-scale` も直してください
（`KnockbackDisplayScaleDriftTest` が機械照合して落とします）。

### ATTACK チャネル (2026-07-26 拡大 — CombatListener が attackerStats / baseDamage 算出点で直接クランプ)

暴走しがちな近接/弓の本命ステ。詳細は `CombatListener#onEntityDamageByEntity` のコメント参照。

```
attack-power
crit-chance
crit-damage
flat-bonus-damage
percent-bonus-damage
penetration
damage-modifier
fixed-damage
bleed-chance
bleed-damage
```

### DEFENSE チャネル (totalOf 経由 — CombatListener / PerkAttributeApplier が読む)

```
health-regen-bonus
```

### DEFENSE チャネル (2026-07-26 拡大 — PlayerDefenseResolver が DefenseStats#combine 直後にクランプ)

守備力/回避の暴走対策の本命。`DefenderProfile` として返す直前の唯一の出口。

```
phys-resistance
magic-resistance
damage-reduction
armor-defense-rate
dodge-chance
armor-strength
phys-flat-defense
magic-flat-defense
```

### GENERAL チャネル (totalOf 経由 — 採集/経済/クラフト/Ars 等の総合ステ)

```
mining-fortune
fishing-luck
fishing-bonus
gathering-efficiency
fish-sell-price-bonus
disassembly-return-bonus
ocean-fishing-bonus
hunger-save-chance
mob-drop-bonus
skill-exp-bonus
loot-luck
mob-drop-quality
gacha-rate-bonus
suspicious-respawn-chance
hive-harvest-fortune
food-save-chance
workbench-quality-bonus
ritual-quality-bonus
workbench-upswing-bonus
workbench-downswing-reduction
ritual-upswing-bonus
ritual-downswing-reduction
craft-roll-up-bonus
craft-roll-down-reduction
craft-roll-inset
vanilla-exp-bonus
kill-vanilla-exp-bonus
break-vanilla-exp-bonus
breeding-vanilla-exp-bonus
woodcutting-extra-drop-chance
harvest-extra-drop-chance
food-restore-bonus
hidden-saturation-bonus
breeding-extra-child-chance
bred-animal-growth-bonus
planted-crop-growth-bonus
mana-bonus
mana-regen
ars-tier-bonus
glyph-slot-bonus
hit-mana-recovery
damage-mana-recovery
mana-cost-reduction-flat
mana-cost-reduction-percent
lapis-cost-reduction
source-cost-reduction
material-refund-chance
ingredient-save-chance
enchant-luck
enchant-exp-gain-bonus
potion-quality-bonus
brew-speed-bonus
enchant-cost-reduction
glyph-damage-multiplier-bonus
```

### ATTRIBUTE チャネル (PerkAttributeApplier が直接クランプ — 2026-07-27 実装)

意味が他のチャネルと違う(「TFの寄与分」の上限であって「実効値」の上限ではない)。
必ず次節「ATTRIBUTE チャネル — 効くが「意味」が他と違う」を読んでから設定すること。

```
move-speed
attack-speed-bonus
attack-reach
knockback-resistance
max-health
```

## ATTRIBUTE チャネル — 効くが「意味」が他と違う (2026-07-27 実装)

**対象キー**: `move-speed` / `attack-speed-bonus` / `attack-reach` /
`knockback-resistance` / `max-health`

2026-07-26 まで「まだ効かないキー」として保留していたが、2026-07-27 に上限を実装した。
適用点は `PerkAttributeApplier#apply` の**合算完了直後・バニラ Attribute への書き込み直前**の1点
(`attack-speed-bonus` だけは専用合算があるので `collectAttackSpeedBonus` の出口)。

**⚠ 他のキーと意味が違う点 — これは「TF が要求する量」の上限であって、「その属性の実効値」の上限ではない。**

`PerkAttributeApplier` は TF自身の寄与分を Bukkit の `AttributeModifier`
(`ADD_NUMBER` / `ADD_SCALAR` / `MULTIPLY_SCALAR_1`) としてバニラ `AttributeInstance` へ加えるだけで、
最終的にその属性が持つ実効値は、バニラ本体が「TFの寄与 + バニラ基礎値 + 他プラグイン/
ポーション効果(例: Haste)由来の別modifier」を合成した結果になる。したがって:

- ✅ **効く**: TF の perk / native armor-set / 永続バフ / base-stats がスタックして
  `max-health` が青天井に伸びる、といった**TF内部の暴走**は止まる。
- ❌ **効かない**: Haste や他プラグインが同じ属性へ足した分は含まれない。「実効値が必ず◯以下」を
  保証するものではない。

この線引きは意図的で、`PerkAttributeApplier` の「Haste との相殺事故を避けるため**ライブ属性値を
一切読まない**」という設計原則(クラス javadoc 参照)を崩さないために選んでいる。実効値を読み戻す
方式にすると、その原則ごと壊れて Haste 相殺の回帰を招く。

`max-health` は CMB-03 の health restore ロジックと絡むが、クランプ位置が
`clearOwnModifiers` → 再適用 → `restoreHealthAfterReapply` の**途中ではなく合算段階**なので、
restore は「クランプ後の最終 MAX_HEALTH」を見ることになり整合する。

上限は `stat-caps.yml` に**書いたキーだけ**に掛かる(既定は空 = 従来どおり無制限)。

## 対象外(そもそも書いても効かない、上とは別理由)

- **アイテム個別ステ** (`durability` / `item-cooldown` / `tool-enchant-*` / `thread-slots` /
  `coating-charges`) — `StatVocabulary` 未登録のアイテム単位ステ。
- **`*-cooldown-reduction` 系**(`cooldown-reduction` 含む) — `CooldownManager.applyReduction` が
  既にクランプ済み。ここに書くと警告ログが出て無視される(二重管理防止)。
- **`flat-defense`**(legacy、typed 版が無い旧アイテム向けフォールバックキー) —
  `PlayerDefenseResolver` はここで解決した値を必ず `phys-flat-defense` / `magic-flat-defense` の
  いずれかへ正規化してからクランプする。つまり legacy 値も最終的には typed キーの上限に服するが、
  「flat-defense」というキー名自体で上限を書いても参照されない(typed キーで書くこと)。

## `gathering-efficiency-max-enchant-level`

採集効率(`gathering-efficiency`、旧称:最終効率)の効率強化エンチャント上限。ルート直下のキー(`stat-caps:` マップの外側)。

旧来は `stats/gathering-efficiency.yml` の `max-enchant-level` だけがこの値を決めていた。
ここに数値を書くと、そちらより優先してこの値が使われる(`stats/gathering-efficiency.yml` は
後方互換のため引き続き読み込まれ続けるが、このキーが設定されていれば無視される)。
未記載なら、これまで通り `stats/gathering-efficiency.yml` 側の値が使われる。
0以下は「無制限」(`gathering-efficiency.yml` と同じ意味論)。内部ハード上限 255
(`GatheringEfficiencyMath`)は、この設定に関わらず絶対に超えない。

## 出荷初期値 (2026-08-01) — 攻撃側8キーの導出根拠

2026-08-01 まで出荷 yml は `stat-caps: {}` で、攻撃側は完全に青天井だった(K-19)。
そこへ初期値を入れたときの計算過程を、後から数値だけを見ても再現できるように残す。

### ⚠ 最重要 — ここは「厳選の寄与分」ではなく「最終合算値」の上限

追加コンテンツ詳細プラン §3 A-4 の表は
**「19枠フル厳選の理論最大」に対して cap を提案していた**(例 `attack-power: 16000`)。
だが実際の適用点(上記「適用範囲」)は **装備 + パーク + 永続バフ + base-stats を全部足した後の総量**なので、
**武器そのものの `attack-power` も同じ上限に服する**。

プランの値をそのまま書くと、出荷 `stats/item-stats.yml` の最上位装備が上限で潰れる:

| キー | プランの提案 cap | 出荷 item-stats の単品最大 | そのまま書いた場合 |
|---|---|---|---|
| `attack-power` | 16,000 | **96,279.8**(`NETHERITE_SWORD#5611`、品質9 + ロール最大) | 最上位剣が **1/6** に潰れ、装備の階段が丸ごと消える |
| `bleed-damage` | 1,500 | **3,240**(`TRIDENT#121`) | トライデントの出血が **54% 減** |
| `crit-damage` | 2.50 | 1.73(`CROSSBOW#164`) + base-stats 0.5 = **2.23** | 余地 0.27 しか残らず厳選が実質死ぬ |
| `penetration` | 0.60 | 0.501(`GOLDEN_SPEAR`) | 余地 0.099。槍1本でほぼ上限 |

**装備の単品最大の求め方**(item-stats.yml の意味論): `fixed + per-quality × 9 + random.max`。
`9` は `stats/quality.yml` の `max-quality`。防具4部位は攻撃側8キーを1つも持たないので、
攻撃側の装備寄与は「手に持つ1本」だけで決まる(`offhand-stats-apply: true` の出荷品は0件)。

### 出荷した値

| キー | 装備の単品最大 | base-stats | 厳選19枠の理論最大 | **出荷 cap** | 上限が許す装備外の上積み |
|---|---|---|---|---|---|
| `attack-power` | 96,279.8 | 0 | 20,520 | **110,000** | +13,720 |
| `crit-chance` | 0.478 | 0.05 | 1.71 | **0.85** | +0.32 |
| `crit-damage` | 1.73 | 0.5 | 3.42 | **3.20** | +0.97 |
| `penetration` | 0.501 | 0 | 1.71 | **0.85** | +0.35 |
| `percent-bonus-damage` | 0 | 0 | 1.71 | **1.00** | +1.00 |
| `flat-bonus-damage` | 0(アイテム側は廃止キー) | 0 | 102.6 | **60** | +60 |
| `bleed-chance` | 0.27 | 0 | 2.05 | **0.60** | +0.33 |
| `bleed-damage` | 3,240 | 0 | 2,736 | **4,500** | +1,260 |

「厳選19枠の理論最大」= `thread-rolls.yml` の主ステ `max` × レア度 legend の `multiplier` 1.8 × 19枠。
主ステが全枠同一キーになる最悪ケースなので、サブステは同じキーを引かない(主ステと重複しない仕様)。

`percent-bonus-damage` / `flat-bonus-damage` / `bleed-chance` はプランの値をそのまま採用した
(装備側の baseline が 0〜0.27 でプラン値の下にあり、潰す装備が無いため)。

### 個別の理由

- **`penetration: 0.85`** — `AttackStats` が既に 1.0 でハードクランプしており、1.0 は
  「守備力レート項 `1 - defenseRate × (1 - penetration)` が丸ごと消える」= 守備力機構の無効化。
  0.85 なら守備力は必ず 15% 残る。Breach エンチャント(+0.1/level)を積む特化ビルドは上限に当たるが、
  それは「特化は cap に届く」という意図どおり。
- **`crit-chance: 0.85`** — 会心100%(確定会心)を作らせないための上限。
  最高クロスボウ 0.478 + base-stats 0.05 = 0.528 なので、厳選/パークで 0.32 ぶん伸ばせる。
- **`attack-power: 110000`** — 最上位剣(96,279.8)を一切削らず、厳選19枠の理論最大 20,520 のうち
  67% までしか上積みできないようにした値。中位以下の武器には事実上効かない
  (= 上限が効くのは「最強武器 + フル厳選」の一点だけ)。

**すべて初期値。**実機で「特化ビルドが cap に当たって伸びない」と感じたら上げる方向で調整する。
値を上げ下げするときは `ShippedStatCapsDriftTest` が
「出荷 item-stats.yml の単品最大を下回る cap」を落とすので、装備を潰す事故は再発しない。
