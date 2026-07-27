# stats/quality.yml

出荷config `TrinityForge/src/main/resources/stats/quality.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `loot-base-quality: 0`

```
拾得ルート/釣りで得る装備の品質mode基準値。幸運・スキルLv・アイテム個別の品質基準値に加算し、
spread-up/down によるクラフトと同じ上下非対称正規分布で抽選する。
```

