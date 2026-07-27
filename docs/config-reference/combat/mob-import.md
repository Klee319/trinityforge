# combat/mob-import.yml

出荷config `TrinityForge/src/main/resources/combat/mob-import.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `source: ELITEMOBS`

```
ELITEMOBS = take the mob's own `level` (numeric); FIXED = force `fixed` for every mob.
```

### 直後: `default: 1`

```
Fallback baked level when source=ELITEMOBS but the EliteMobs level is non-numeric ("dynamic").
dynamic モブは実行時に実レベルで再評価されるので、この値は再評価不能時のフォールバックのみ。
```

### 直後: `unknown-mobs:`

```
未インポートのEliteMobsカスタムボスの扱い(2026-07-26 「無料DL枠ダンジョンの敵を設定できない」修正)
  true (既定): combat/mob-profiles.yml に載っていないモブも、スポーン時にこのファイルのランプを
    実レベルで評価して自動導出する。無料DL枠のダンジョンを新たに落とした直後でも、importmobs を
    回す前から TrinityForge の管理下に入り、combat/mob-overrides.yml でステ/ドロップを指定できる。
    (converterは元のボスファイルの数値を一切読まないので、importmobs が焼く値と同一結果になる)
  false: 旧挙動。mob-profiles.yml に載っているモブだけが TrinityForge 駆動になり、それ以外は
    EliteMobs 側の素の値のまま + mob-overrides.yml も一切効かない。
```

### 直後: `default: ""`

```
Dungeon attribute theme stamped on every converted mob (blank = none). Override per-mob later.
```

### 直後: `physical:`

```
Synthesized 防御率%/耐性%/被ダメ軽減%/守備力 vs physical attacks.
resistance を軽め(0.12)の定数にして、プレイヤーの撃破手数を「標準」レンジに収める。
```

### 直後: `magical:`

```
Same vs magical attacks (魔法職の撃破手数も同等になるよう物理と同値)。
```

### 直後: `armor-strength:     { base: 0.0, per-level: 0.0 }`

```
防具強度 (step2 の会心軽減率), shared across both components. 0 = プレイヤーの会心はそのまま通る。
```

### 直後: `variance:`

```
── 個体ばらつき(厳選ロール) ──────────────────────────────────────────
  同一 level でも個体ごとに HP / 攻撃力を ±fraction の範囲で揺らして「味気なさ」を解消する。
  0.15 = ±15%。0 = ばらつき無し(完全に決定的)。防御(防御率/耐性/軽減/守備力/防具強度)は
  ここでは揺らさない(据え置き)。spawn 時に個体シードを PDC へ焼き、RollHash で決定的に導出するので、
  全回復・フェーズreset・動的レベル再評価をまたいでも同一個体は同じ倍率を保つ。
```

### 直後: `max-health:         { base: 150.0, per-level: 0.0, growth: 1…`

```
TrinityForge-driven max health(指数)。0 = unconfigured なら EliteMobs 側の HP を維持。
base 150 × 1.072^level: L1≈161, L20≈600, L50≈4900, L100≈150000(プレイヤー火力の伸びに追従)。
```

### 直後: `attack:`

```
Attacker-side stats stamped onto each converted mob(完全TF駆動)。
2026-07-25 改訂 v2: attack-power は指数(base 7.0、線形項なし、growth 1.03)。
  L0=7.0, L10≈9.4, L20≈12.6, L30≈17.0, L40≈22.8, L55≈35.6, L70≈55.4, L85≈86.3, L100≈134.5。
  実プレイでワンショット/無敵化の有無を確認し、過強・過弱なら editor / このランプで調整すること。
damage-modifier は乗算端点で中立=1.0(0.0 にすると Uniform[0,1] で威力半減するので触らない)。
```

