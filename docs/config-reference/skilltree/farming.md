# skilltree/farming.yml

出荷config `TrinityForge/src/main/resources/skilltree/farming.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `max-times: 1` (2026-07-26 追加。監査knowledge: prestige.max-times が出荷ymlに存在せず全ツリー1周固定だった件)

```
周回上限(NG+)。SkillTreeConfig#load が 'max-times' を section.getInt("max-times", 1) で読む
既存の実装済みロジックに対応する値だが、出荷ymlに一度もキーが存在しなかったため全ツリーが
常にデフォルト値1(1周のみ)に固定されていた(監査知見)。ここで明示化した値も1であり、
挙動は変更していない。2周目以降を解禁したい場合はこの値を2以上に上げる。
```

### 直後: `nodes:`

```
    # 2026-07-25: value=ゴミ食のみに乗る満腹度(食べた見た目満腹度)回復ボーナス%。同じフラグが
    # 「非ゴミ食にはfood_restore_bonusを適用しない(通常に戻す)」動作も兼ねる
    # (FoodBonusListener#onFoodChange、ゴミ食判定は stats/food-gimmick.yml junk-food-materials を再利用)。
```
