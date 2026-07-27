# skilltree/woodcutting.yml

出荷config `TrinityForge/src/main/resources/skilltree/woodcutting.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
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
    # PRG-01: feature:break-vanilla-exp が全16ツリー未配置で機構が到達不能だったため配置。
      # 2026-07-25 gather-rework-active-framework §6 Q1: 旧 feature:small-tree-fell を
      # feature:tree-fell tier1 へ移行(挙動据え置き。stats/woodcutting-gimmick.yml tree-fell.tiers参照)。
      # 2026-07-25 gather-rework-active-framework §6 Q1: 旧 feature:large-tree-fell を
      # feature:tree-fell tier3 へ移行(挙動据え置き。stats/woodcutting-gimmick.yml tree-fell.tiers参照)。
    # PRG-07修正: 旧cooldown-reduction(アイテムCT専用キー)は一括伐採CTに一切効いていなかった。
    # tree-fell専用のtree-fell-cooldown-reductionへ移行(数値0.3は変更していない)。
    # PRG-07修正: 同上。
    # PRG-07修正: 同上。
```
