# stats/enchant-luck.yml

出荷config `TrinityForge/src/main/resources/stats/enchant-luck.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `level-boost-max-steps: 2`

```
1回の抽選で格上げを試行できる最大回数。
```

### 直後: `overenchant-bonus-chance-per-luck: 0.02`

```
バニラ上限へ到達した状態から、さらにオーバーエンチャント上限まで格上げを試行する確率
(luck 1.0あたり)。overenchant:<id> を解放しているプレイヤーにのみ適用される。
未解放のプレイヤーはバニラ上限で格上げが止まる(既存 OverEnchantListener が最終クランプする)。
```

### 直後: `extra-enchant-chance-per-luck: 0.005`

```
抽選結果に元々含まれていない、対象アイテムへ付与可能な別のエンチャントを追加で1つ
(レベル1で)付与する確率(luck 1.0あたり)。競合するエンチャント同士は付与しない。
```

