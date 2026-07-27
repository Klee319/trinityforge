# stats/alchemy-quality.yml

出荷config `TrinityForge/src/main/resources/stats/alchemy-quality.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `amplifier-per-quality: 0.5`

```
品質1ポイントあたりの強度(amplifier)加算。切り捨て(Math.floor)で整数キャストしてから適用する。
```

### 直後: `lingering-splash-duration-ticks-per-quality: 10.0`

```
スプラッシュ/残留ポーション限定の追加時間加算(tick、品質1ポイントあたり)。
alchemy.yml B-beta-1「残留時間UP・スプラッシュ強度UP」も potion_quality_bonus と同じ仕組みで
表現するため、この値は通常ポーションへの duration-ticks-per-quality に上乗せする追加分。
```

