# stats/lore.yml

出荷config `TrinityForge/src/main/resources/stats/lore.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `name: 採集効率`

```
2026-07-25 採掘効率エンチャント連動方式(統合版/Geyser対応、属性ベースを取り下げ再設計):
装備/防具/装飾品/パーク/base-stats等から通常通り合算される。メインハンドの道具が農業/採掘/伐採/
切削(use-skill)のいずれかのときだけ、その道具へ「効率強化」エンチャントのレベルとして反映される
(floor、上限は stats/gathering-efficiency.yml)。クラフト時にツールへ刻まれる tool-enchant-efficiency
とは別物(そちらはアイテムのエンチャントとして永続的に残り続ける)。
```

### 直後: `name: 釣り運`

```
2026-07-23 意味論変更: 宝/ゴミ比率シフト%(品質はルート品質基準値系が担当)。
```

### 直後: `name: 範囲ダメージ`

```
2026-07-31 order 9 → 10: aoe-max-targets と order が重複していた。同 order のタイブレークは
キー名の辞書順(LoreComposer)なので 'aoe-damage-rate' < 'aoe-max-targets' となり、
yml の記述順(範囲上限 → 範囲ダメージ)と画面の並びが逆になっていた。
```

### 直後: `name: 矢ノックバック`

```
2026-07-31 display-scale: 4.4 を追加。内部値は距離ではなく velocity への加算
(NativeCombatPerkListener: 矢は ARROW_KNOCKBACK_VELOCITY_PER_UNIT=0.4 × 値 blocks/tick)なので、
そのまま単位 m として出すと桁が合わない(内部値2を「2m」と読んで書くと実際は約9ブロック飛ぶ)。
空中の水平減衰 0.91/tick を等比級数で積むと総移動距離は初速の 1/(1-0.91)=11.11 倍
(NativeCombatPerkListener.VELOCITY_TO_BLOCKS)なので 0.4×11.11≒4.4 を表示係数にした。
1m 相当の内部値 ≒ 0.23。base-stats / item-stats / skilltree / stats/stat-caps.yml に書く値と PDC は
内部値のままで、換算は表示側(StatDisplaySpec.toDisplayValue)だけが行う。
接地中は摩擦が強い(0.6×0.91)ため実距離は表示より短い概算値である。
Java の係数を変えたら display-scale も直すこと(KnockbackDisplayScaleDriftTest が機械照合する)。
```

### 直後: `name: 追撃ノックバック`

```
2026-07-31 display-scale: 3.9 を追加(近接は MELEE_KNOCKBACK_VELOCITY_PER_UNIT=0.35 なので
0.35×11.11≒3.9)。1m 相当の内部値 ≒ 0.26。算出根拠は `name: 矢ノックバック` の項を参照。
order 107 → 106: stun 系の order 重複を解消するため矢/近接ノックバックを 105/106 へ繰り上げた
(矢→近接→スタンの相対順は不変)。
```

### 直後: `name: スタン率`

```
2026-07-31 order 108 → 107: stun-duration-bonus と order が重複していた(相対順は不変)。
```

### 直後: `name: 儀式上振れ拡大`

```
2026-07-31 order 201 → 203 / 儀式下振れ抑制 202 → 204: 作業台側(workbench-*)と order が
重複していた(旧 craft-* ブロックの本体をそのまま流用したため)。同 order のタイブレークは
キー名の辞書順なので 'ritual-*' が 'workbench-*' より上に出て、yml の記述順
(作業台2本 → 儀式2本)と画面の並びが食い違っていた。
```

### 直後: `name: ロール上振れ`

```
2026-07-31 order 203 → 205(ロール下振れ抑制 204→206 / ロール収束 205→207): 儀式側を 203/204 に
入れた分の繰り下げ。ロール3キーの相対順は不変。
```

### 直後: `bow-accuracy:`

```
--- 2026-07-23 stat-gate-overhaul §2.1: 新規statキー(弓系/近接系) ---
```

### 直後: `name: 弓精度`

```
符号反転: 正=高精度 (consumer側 jitter = max(0, 0.08 − v))
```

### 直後: `name: アイテムCT短縮`

```
2026-07-25 CT短縮ステータス分離 §1-A: 表示名を実態(アイテムCTだけを短縮する)に訂正。
アクティブスキルのCTには一切効かない(それは各ActiveSkill単位の "<id>-cooldown-reduction" が担う。
例: haste-active-mining-cooldown-reduction)。
符号反転: 正=短縮。
(2026-07-31: 旧 `name: 弓CT短縮` (bow-cooldown-reduction) の項に付いていた「符号反転: 正=短縮」を
ここへ引き取った。同キーは同日廃止 — アイテムCT短縮と同じ Player#setCooldown を二重に掛ける設計で、
item-stats.yml の BOW/CROSSBOW に item-cooldown が無いため残CTが常に0の完全な no-op だった。
弓のCT短縮はこのアイテムCT短縮へ一本化済み。)
```

### 直後: `name: 高速破壊CT短縮`

```
2026-07-25 CT設計一本化 §2: 旧グローバル skill-cooldown-reduction をActiveSkill単位へ分割した
haste-active-mining(高速破壊)用キー。ActivationDispatcher/ActiveCommand がこのアクティブスキルの
CTにだけ適用する(アイテムCTにも他のアクティブスキルにも一切影響しない)。符号反転(正=短縮)は
cooldown-reduction と同じ。新しいActiveSkillを追加したら "<id>-cooldown-reduction" を同じ形式で追加
すること(ActiveSkillCooldownKeys参照)。
```

### 直後: `name: 一括伐採CT短縮`

```
2026-07-25 PRG-07: woodcutting.yml B-1/B-2/B-3 が誤ってアイテムCT短縮キー(cooldown-reduction)を
使っており一括伐採CTに一切効いていなかったバグの修正。tree-fellはActiveSkillではなくパッシブな
ブロック破壊ギミックのためActiveSkillRegistryには載らないが、CT短縮キーの命名規約は
ActiveSkillCooldownKeys.forSkill("tree-fell")と揃える(TreeFellingListenerが
CooldownManager.applyReductionで消費)。符号反転(正=短縮)はcooldown-reductionと同じ。
```

### 直後: `name: コーティング回数`

```
3キー統合 (lightweapons/heavyweapons coating-charges + coating-stack-increase)
```

### 直後: `hunger-save-chance:`

```
--- 生存・汎用系 ---
```

### 直後: `kill-vanilla-exp-bonus:`

```
--- 2026-07-24 新規: バニラEXP/追加ドロップ/満腹度/繁殖・成長 ---
```

### 直後: `name: 破壊時バニラEXP`

```
前提: 機能解放『破壊時バニラEXP入手』が必要。
```

### 直後: `ritual-quality-bonus:`

```
--- クラフト系 (CraftQualityService → クラフターの totalOf()) ---
```

### 直後: `name: ラピス消費軽減`

```
2026-07-23 仕様確定: %ではなく「軽減する個数」(FLATな整数)。PercentStatNormalizeのRATE_KEYS対象外。
2026-07-31 order 207 → 212: 儀式上振れ/下振れを 203/204 に入れた分ロール3キーが 207 まで伸びたため、
空き番の 212 へ移した。移動先はエンチャント系(213 エンチャントコスト軽減 / 214 エンチャント運)の
直前なので配置としても素直。
```

### 直後: `source-cost-reduction:`

```
--- fork consumer系 (Ars) ---
```

### 直後: `name: 材料節約率` (2026-07-26 追加。監査knowledge: バニラ醸造台への配線が無かった件の修正)

```
ingredient-save-chance は元々ArsPaperのAlchemicalSourcelink(独自クラフトの素材投入)専用の
consumerしか無く、skilltree/alchemy.yml D「素材を消費しない確率UP」を取得してもバニラの
醸造台では何も起きなかった(2026-07-25 監査 reports/20260725_SkilltreeNodeTriage.md B-alpha-1)。
2026-07-26、TrinityForge本体に BrewIngredientSaveListener を追加し、バニラBrewEventでも同じstatを
ロールするようにした。これにより ingredient-save-chance は「バニラ醸造台の材料投入」と
「ArsPaperアルケミカルソースリンクへの素材投入」の両方に等しく適用される(片方だけを強化する
専用キーではない)。自動(ホッパー式)醸造には一切適用しない(複製防止、品質/速度のような
alchemy.auto_mult減衰すら無い完全スキップ)。
```

