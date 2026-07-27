# skills/base/mining_progression.yml

出荷config `TrinityForge/src/main/resources/skills/base/mining_progression.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)
### 直後: `experience:`

```
    # if the block was placed beforehand it will not reward any EXP or skill benefits.
    # Only the items in this list will benefit from drop multiplication
    # Items will only reward experience if both the mined block and the associated drop exist in this list (I.E. IRON_ORE and RAW_IRON)
```
