# stats/mining-gimmick.yml

出荷config `TrinityForge/src/main/resources/stats/mining-gimmick.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `suspicious-block-respawn:` (2026-07-26 GTH-04 追加)

```
怪しげな砂/砂利をAPI経由(Block#setType)で再湧きさせると、構造生成由来のブロックと違って
ルートテーブルが一切付かず、ブラシで完成させても何もドロップしない不具合(GTH-04)への対処。
MiningGimmickListener#handleSuspiciousRespawn が再湧き直後に Block#getState() を
BrushableBlock へキャストし、ここで指定した org.bukkit.loot.LootTables 定数を setLootTable し、
毎回新しい乱数seedを setSeed する(同一seed使い回しで結果を先読みされないようにするため)。
suspicious-sand と suspicious-gravel は生成元の構造が異なる(砂漠系 vs 遺跡歩道/海底遺跡)ため、
それぞれ別テーブルを設定できるようMaterial別キーにしている。既定値は
DESERT_PYRAMID_ARCHAEOLOGY(砂) / TRAIL_RUINS_ARCHAEOLOGY_COMMON(砂利)。
値は org.bukkit.loot.LootTables の定数名をそのまま文字列で指定する(不正な値は警告を出し既定値へ
フォールバック、読み込みを止めない)。
```

### 直後: `chain-drop-rolls-max: 32`

```
一括破壊1回あたりに「連鎖分」で追加で引く drop-tables の上限回数(2026-08-24、既定は 32)。
連鎖で壊した鉱石は BlockBreakEvent を発火しないため、これが無いと鉱脈を一括で掘っても
起点1ブロック分しか抽選されない(1個ずつ手で掘るより損をする)。
実際に引く回数は「連鎖で壊した自然生成ブロックの数」とこの値の小さいほう。
手で設置した鉱石は数に入れない(起点の抽選が設置ブロックを除外しているのと同じ規約)。
0以下にするとこの経路の抽選を行わない(2026-08-24 以前の挙動)。
```

### 直後: `amplifier: 5`

```
tiers未定義/該当tierなしの場合のフォールバック既定値。
cooldown-ticks は全tier共通の唯一のCT基準値(2026-07-25 CT設計一本化 §1: 段階(tier)はCTに一切影響
させない設計に変更。旧tier1の値(600)をそのまま基準値として採用 — 後方互換の据え置き)。
CTを短くする唯一の手段は ActiveSkillCooldownKeys が解決するCT短縮ステータス
(haste-active-mining-cooldown-reduction、mainhand-buffsで付与)のみ。
```

### 直後: `tiers:`

```
2026-07-25 gather-rework-active-framework §6 Q3(ユーザー承認済み暫定値。バランス調整は後で行う)。
tier(=スキルツリー側 dedicated-effects[].value、mining.yml A-1/A-2/A-3 が解放する段階)ごとの
amplifier/持続時間。cooldown-ticksはここに置かない(2026-07-25 CT設計一本化 §1: tierはCTに影響しない
ため、tier行に書いても読まれない — 上のグローバルscalarが全tier共通で使われる)。
```

### 直後: `fortune-per-level: 0.003`

```
Lv1あたりの追加mining-fortune。実効期待値は fortune + Lv×この値 個/ブロック。
1のままだとMINING Lv100で1ブロックあたり期待+100個となり、vein-mining(最大32ブロック)併用で
経済崩壊するため縮小してある(Lv100で+30%相当)。

【2026-07-28】以前は上式全体に × 0.30 が掛かっていた。この係数があると skilltree が宣言する
「ドロップ増加+15%」が実際には +4.5% にしかならず effect-text と食い違うため撤去し、
レベル項の増加量を従来どおりに保つため 0.01 → 0.003 (= 0.01 × 0.30) へ下げた。
併せて mining-fortune を PercentStatNormalize.RATE_KEYS へ登録した(登録漏れにより
skilltree の mining-fortune: 15 が 0.15 ではなく 15 のまま効き、ドロップが約6倍になっていた)。
```

