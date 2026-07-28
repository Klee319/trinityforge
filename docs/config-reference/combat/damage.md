# combat/damage.yml

出荷config `TrinityForge/src/main/resources/combat/damage.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `melee-charge:`

```
バグ報告B2: バニラの「クールダウン中に攻撃すると威力が落ちる」補正(近接専用)。
TFの独自ダメージパイプラインには元々この補正が存在せず、連打が最適解になっていた。
t = Player#getAttackCooldown() (0.0=直後の連打 〜 1.0=フルチャージ) として
multiplier = min-multiplier + t^exponent × (1 - min-multiplier) を最終物理ダメージ全体
(fixed-damage含む、CombatListenerの8段階パイプライン適用後の合計)へ乗算する。
近接(素手/剣/斧等のENTITY_ATTACK/ENTITY_SWEEP_ATTACK)のみに適用され、弓/クロスボウ/トライデントの
遠隔攻撃・魔法ダメージ・モブの攻撃には一切適用されない。
```

### 直後: `min-multiplier: 0.2`

```
フルチャージ0%時の下限倍率。既定0.2はバニラの 0.2 + t^2*0.8 と同じ。
```

### 直後: `exponent: 2.0`

```
tに掛かる指数。既定2.0はバニラ相当。
```

### 直後: `attack-speed:`

```
2026-07-25: attack-speed(絶対値・メインハンド専用)+attack-speed-bonus(割合・全ソース横断)分離仕様。
両方ともプレイヤー単位でPerkAttributeApplierが一元計算・適用する(item-level=AttributeApplierは
ATTACK_SPEED属性へ一切触れない)。
```

### 直後: `min-effective: 0.1`

```
絶対値+割合ボーナスを合成した後の最終実効速度がこの値を割らないようにするクランプ下限。
デバフの重ね掛けでも0/負値にはならない(バニラのAttribute演算に0以下を渡すと壊れるため)。
```

### 直後: `reconcile-interval-ticks: 10`

```
装備フィンガープリント再照合(安全網)の周期。イベント取りこぼし対策で、変更を検知した時だけ再適用する。
```

### 直後: `defense-rate-per-point: 0.015`

```
バニラ防具値(+TF加算分)1点あたりの防御率換算。
0.04だとバニラ+TF合算で防具値20点＝Lv10相当で上限0.8に飽和し、以降の防具更新が%軽減に反映されない。
0.015なら革13点で0.195、ネザライト34点で0.51となり、全帯を通して防具強化が%軽減に伸び続ける。
```


### 直後: `sunlight-burn:` (2026-07-28)

```
バニラの炎上ダメージは1発1.0固定。一方 TF のモブ最大HPは Lv0 のゾンビでも 400 あるため、
「朝になっても敵が炎上で死なない」(400秒以上燃え続ける)状態だった。日光で燃えている間の
1発だけを「被弾モブの最大HP × damage-percent-of-max-health」へ置き換える。

enabled: false で完全にバニラ挙動へ戻る。
damage-percent-of-max-health: 既定0.10 = 最大HPの10%。バニラの炎上は1秒に1回なので約10秒で焼き切れる。
  算出値がバニラのダメージより小さい場合はバニラ値のまま(下げる方向には決して働かない)。
mobs: 置換の対象とする EntityType 名。既定はバニラで日光焼却される種別のみ。空にすると全モブが
  対象になるが、その場合「野外・昼間に火属性エンチャントで着火しただけ」のボスまで毎秒10%ずつ
  溶けるため、意図的に種別を絞ってある。未知の EntityType 名はスキップされる。
適用条件(すべて満たしたときだけ置換): 通常世界 / 昼 / 天候が晴れ / 立っているブロックの空からの
  明るさが15 / DamageCause が FIRE_TICK。夜間・屋内・ネザー/エンドではバニラのまま。
```

### 直後: `early-level-attack:` (2026-07-28)

```
モブの attack-power は combat/mob-types.yml で指数カーブ(base 5.5〜6.6 × 1.033^Lv)として校正されて
いる。この節はそのカーブ自体を触らずに、レベルの低いモブだけ火力を落とすための後掛け倍率。
base を直接下げると全レベル帯が下がって中盤以降の校正がやり直しになるため、「序盤だけ」を独立した
つまみとして分離してある。

  multiplier(Lv) = Lv >= until-level ? 1.0
                 : level-0-multiplier + (1 - level-0-multiplier) × Lv / until-level

既定では Lv0 で0.7倍、Lv5 で0.85倍、Lv10以上で等倍(=従来どおり)。
適用範囲: モブ→プレイヤーの物理/魔法の基本ダメージ(防御計算より前)。プレイヤー→モブ、および
  EliteMobs が最終ダメージを直接渡してくる経路(physical/magicalFinalDamageFlat)には掛からない。
```
