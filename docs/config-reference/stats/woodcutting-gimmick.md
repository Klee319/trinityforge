# stats/woodcutting-gimmick.yml

出荷config `TrinityForge/src/main/resources/stats/woodcutting-gimmick.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `max-extra-logs: 8`

```
tiers未定義/該当tierなしの場合のフォールバック既定値(トリガーになった原木1本は含まない)。
```

### 直後: `cooldown-ticks: 200`

```
プレイヤー毎クールダウン(tick)。20tick=1秒。既定10秒(要調整)。
```

### 直後: `tiers:`

```
tier(=スキルツリー側 dedicated-effects[].value)ごとの一括伐採上限本数。
旧small-tree-fell(8本)/large-tree-fell(64本)の配置(woodcutting.yml B/D)を移行した値そのもの
(2026-07-25 §6 Q1: 挙動は完全に据え置き)。
```

### 直後: `drop-tables:`

```
伐採ドロップテーブル(2026-07-23 stat-gate-overhaul §4): 葉(LEAVES系)破壊時、または原木破壊時
(一括伐採のトリガーになった1本のみ、連鎖破壊分は対象外)に、各カテゴリ独立でtrigger-chance-percent判定→
重み付き抽選。旧 apple-drop/golden-apple-drop/crystal-apple-drop の個別consumerを置換したもの
(確率は旧値を移植、要調整)。perkゲートは drop:woodcutting:<catId> / drop:woodcutting:item:<itemId>
で参照済みカテゴリ/アイテムのみ解放制御される。未参照は誰でも解放。
```

