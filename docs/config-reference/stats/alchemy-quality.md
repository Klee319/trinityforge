# stats/alchemy-quality.yml

出荷config `TrinityForge/src/main/resources/stats/alchemy-quality.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `duration-percent-per-tenth-point: 1.0`

```
品質 0.1 ポイントあたりの持続時間変化(%)。1.0 なら 0.1pt で +1%、1.0pt で +10%。
0 を下回った分は同じ割合で持続時間が短くなる。強度(amplifier)は品質では動かさない。
```
