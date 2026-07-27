# skills/base/smithing_progression.yml

出荷config `TrinityForge/src/main/resources/skills/base/smithing_progression.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)
### 直後: `experience:`

```
  # Damaging an item (regardless of amount) will increase a tally counter for the item's material type.
  # The next time Smithing exp is gained with said material type, it is multiplied by the number below TIMES the tally counter.
  # The tally counter is then reduced by the max amount of times this effect may be stacked (100 stacks reduced to 0,
  # By default, each stack grants +1% EXP per durability taken, up to 1000 stacks (+1000%) for normal items
  # For armors, +0.5% EXP is granted per stack up to 200 stacks (+100%) because armor is easier to damage than tools
  # In this case, it prevents you from getting more stacks if the counter exceeds this limit.
  # This basically just means you may not get more than 50 stacks per chunk per item type
```
