# combat/mob-defaults.yml

出荷config `TrinityForge/src/main/resources/combat/mob-defaults.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### `defense-rate: 0.0`

vanilla armor-derived %, penetrable (step 3)

### `resistance: 0.0`

physical resistance %, not penetrable (step 4)

### `damage-reduction: 0.0`

被ダメージ軽減 %, x(1-reduction) (step 5)

### `flat-defense: 0.0`

守備力 (physical flat), subtracted (step 6)

### `flat-defense: 0.0`

守備力 (magical flat)

### 直後: `armor-strength: 0.0`

```
防具強度: type-independent flat reduction, shared across both components (step 6).
```

