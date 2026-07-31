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
単位 `m`）。実体は対象の velocity への加算（近接は係数0.35、矢は0.4）なので厳密にはブロック/tick
ですが、両者で同じ単位系なので表記も揃えています。**割合ではないので上限値も生値で書きます**
（`melee-knockback` は同日 `PercentStatNormalize.RATE_KEYS` から外しました。残していると
`2`（=2m のつもり）が `0.02` へ黙って矯正されます）。

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
