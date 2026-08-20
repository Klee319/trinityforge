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
バニラ防具値1点あたりの防御率換算。
0.04だと防具値20点＝Lv10相当で上限0.8に飽和し、以降の防具更新が%軽減に反映されない。
0.015なら革13点で0.195、ネザライト34点で0.51となり、全帯を通して防具強化が%軽減に伸び続ける。
```

2026-08-15 に防具値ステ(`armor-defense-rate`)を廃止したので、この係数が効くのは
**TF未スタンプの素のバニラ防具だけ**になった(TFスタンプ装備は `AttributeApplier` が
`Attribute.ARMOR` を材質既定ごと抑止するため防具バーが常に空＝ミラー寄与0)。
同時に、出荷 `item-stats.yml` の防具値をこの 0.015 で `defense-rate` へ一括換算しているので、
**この値は「過去に換算に使ったレート」でもある**。動かすと素のバニラ防具の強さだけが変わり、
TF装備は変わらない(TF装備を変えるには `item-stats.yml` の `defense-rate` を直す)。


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

### 直後: `level-cutoff:` (2026-08-09 移設 / 2026-08-18 線形傾斜追加 W-60 / 2026-08-18 under-level 対称化 W-72)

```
レベル差による経験値・ドロップの足きり(低レベル狩りの抑制 / 高レベルモブのドロップ制限)。
⚠ over/under はモブではなく【プレイヤー】が主語。over-level はプレイヤーのほうが高レベルなときに
  効く側で、「格上狩り」と読むと向きが逆になるのでその言い方はしない。
over-level: 自分(戦闘レベル)が threshold 以上モブより高いと発動。exp-rate/drop-rate は発動直後の初期倍率
(-1で完全遮断、1で無干渉)。exp-decay-per-level/drop-decay-per-level は閾値超過1レベルごとに
その初期倍率からさらに引く量(既定0=減衰なし=従来どおり固定レートのまま)。rate-floor は減衰後の下限。
under-level: モブが自分より item-threshold 以上高いと発動。2026-08-18 に over-level と完全対称にした
(以前は「TF追加ドロップを一切付けない」の全か無かだけで、経験値には一切効かなかった)。狙いは
低レベルのままハメ殺しやデスルーラーで高レベルのモブを狩る行為の抑制。撃破EXPはモブのレベルで伸びるので、
経験値に効かないことが一番大きな抜け穴だった。
⚠ 閾値のキー名は item-threshold のまま据え置き(リネームすると配備済み config の値が無言で
  既定値に化けるため)。実際にはアイテムと経験値の両方の発動条件を兼ねる。
⚠ パーティでの同行は区別しない。レベル差だけで判定するので、高レベルの人にダンジョンへ連れて行って
  もらった低レベルも同じだけ削られる(免除を入れると低レベルを連れて行くだけで抑制を回避できるため)。
```

出荷値は `item-threshold: 20 / exp-rate: 1 / drop-rate: -1 / exp-decay-per-level: 0.1 /
drop-decay-per-level: 0 / rate-floor: 0`。**over-level は出荷時 `threshold: -1`(無効)のままで、
実際に有効なのは under-level 側だけ**。20レベル差から TF追加ドロップが止まり、経験値は20差では等倍、
超過1レベルごとに0.1ずつ落ちて30差以上で0になる。

`CombatDamageConfig#levelCutoff()` → `MobLevelCutoff`(`com.trinityforge.mobs.MobLevelCutoff`)が実体。
適用点は `KillRewardAdjuster` 一点(EXP側の `LevelCutoffExpListener`/`CombatListener#onCombatKill`、
ドロップ側の `MobOverrideDropListener`/`MobTypeDropListener`/`MobLevelTableListener` が全てここを経由)。

**over-level(プレイヤーのほうが高レベル＝低レベル狩り)の計算式**: `diff = プレイヤー戦闘Lv − モブLv` が `threshold` 以上で発動。
発動時のレートは

```
excess = max(0, diff - threshold)
rate   = clamp(rate-floor, 1.0, 基準rate(exp-rate/drop-rate) − excess × decay-per-level)
```

`exp-rate`/`drop-rate` が `-1` の場合はこの計算を経由せず常に完全遮断(exp=0 / dropは`blocksItems`扱い)を返す
── 「rateを-1にして締め出す」と「decayで徐々に絞る」は排他で、-1が常に優先される。
`excess == 0`(閾値ちょうど)では減衰が一切乗らない(基準rateがそのまま使われる)。
`exp-decay-per-level`/`drop-decay-per-level`/`rate-floor` の既定は全て `0`。この場合
`excess` がいくつであっても減衰量は常に0になるため、2026-08-09時点の「閾値到達で固定レートへジャンプする
だけのステップ関数」と完全に一致する(既存の出荷設定・回帰テストは無変更)。

**under-level(モブのほうが高レベル)の計算式は over-level と完全に同型**(2026-08-18 W-72)。
`diff = モブLv − プレイヤー戦闘Lv` が `item-threshold` 以上で発動し、
`excess = max(0, diff - item-threshold)` に対して同じ `clamp(rate-floor, 1.0, 基準rate − excess × decay)`
を適用する。`-1` が完全遮断で減衰計算より優先されるのも同じ。対称性そのものは
`MobLevelCutoffTest#underLevelIsExactlySymmetricWithOverLevel`(同じ数値を両向きに置き、レベルを
鏡写しにして0〜30超過まで突き合わせる)が固定している。

**両方が同時に発動した場合は厳しいほう(小さいほう)を採る**。ただし両方が同時に発動するのは
閾値が両方 `0` かつプレイヤーとモブが同レベルのときだけ。

**新キーの既定値は「対称化する前の挙動」に合わせてある**: `under-level.exp-rate: 1.0`(経験値に触れない)、
`under-level.drop-rate: -1`(発動したらTF追加ドロップを一切付けない)、decay/floor は `0`。
`under-level` のキーを1つも書いていない配備済み config は意味が変わらない。
逆に言うと**出荷 yml を配備し直さない限り新しい足きりは効かない**(配備済み `damage.yml` の
`item-threshold` は明示的に `-1` = 無効のままなので、editor で書き換えるか config を配備し直す必要がある)。
出荷値そのものは `ShippedLevelCutoffTest` が固定していて、`item-threshold` を `-1` に戻すと落ちる。



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
