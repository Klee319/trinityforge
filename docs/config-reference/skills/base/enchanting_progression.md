# skills/base/enchanting_progression.yml

出荷config `TrinityForge/src/main/resources/skills/base/enchanting_progression.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)
### 直後: `experience:`

```
    # Diminishing returns reduce the amount of enchanting experience you get depending on the amount of a specific
    # mob you have killed. Each kill of a mob in the category "on" increases the player's tally counter by 1,
    # if the tally counter value is more or equal than the amount specified in "amount" the amount of experience is
    # multiplied by the value specified in "multiplier". After enchanting, the tally counter is reduced by the "amount"
    # until the tally counter is below the "amount".
    # Example: a player kills 55 endermen. The next 5 times the player enchants something, their skill experience rewarded
    # is reduced to only 20% (multiplier of 0.2). Now their tally counter is 5 instead of 55, so their experience isn't reduced.
      # entities. In case you want to nerf popular experience farms such as Endermen or Zombie Piglin grinders.
      # By default these multipliers are 1.0 so they don't do anything, they are just there as examples.
```
