# skilltree/smithing.yml

出荷config `TrinityForge/src/main/resources/skilltree/smithing.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
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

### 直後: `starting-coords:`

```
# 排他ルート(実装は group による相互排他で強制。この行は説明のみ): ギリシャ文字路線(alpha=下振れ軽減 / beta=品質上限)はいずれか1つのみ解放可能
```

### 直後: `nodes:`

```
  # ---- 主軸 A〜E（主軸に固有名の記載が画像に無いため世界観で命名） ----
    # gear-tier-craft-stone: 旧カタログ代表target(stone_sword)は stone_sword/stone_pickaxe/stone_axe/
    # stone_shovel/stone_hoe (全てsmithing_perk_a) の束を代表していたため、W2c §3.1展開規則に従い
    # 全構成レシピへ展開する。
    # gear-tier-craft-ironsgold: 旧カタログ代表target(iron_sword)は iron_*/golden_* 道具+防具 計18レシピ
    # (全てsmithing_perk_b)の束を代表していたため、W2c §3.1展開規則に従い全構成レシピへ展開する。
    # gear-tier-craft-diamond: 旧カタログ代表target(diamond_sword)は diamond_* 道具+防具 計9レシピ
    # (全てsmithing_perk_c)の束を代表していたため、W2c §3.1展開規則に従い全構成レシピへ展開する。
    # gear-tier-craft-netherite: 旧カタログ代表target(netherite_sword)は netherite_* 道具+防具 計9レシピ
    # (全てsmithing_perk_d、鍛冶台経由)の束を代表していたため、W2c §3.1展開規則に従い全構成レシピへ展開する。
    # 判断メモ(W2c・要orchestrator確認): 旧blacksmith-unlockは単一の汎用flagで「鍛冶師」を代表していたが、
    # villager-trades.ymlには鍛冶系職業がWEAPONSMITH/ARMORER/TOOLSMITHの3つ独立して存在する。設計書に
    # 代表target展開の明記が無いため、村人取引を機能させる目的で3職業すべてを暫定的に割り当てる
    # (単一に絞る場合はユーザー確認要)。
  # ---- 派生ノード（下段：精錬速度 A系） ----
    # 2026-07-25 かまど精錬速度: 精錬物を入れた本人のみに乗る(所有者=FurnaceSmeltListenerがPDCで
    # 追跡)。ホッパー自動投入時は stats/smithing-gimmick.yml auto-mode-multiplier で減衰。
    # スライド表記は「B-4」だが本ファイルには存在しない。内容一致する精錬速度系のA-4に配置。
  # ---- 派生ノード（下段：精錬ボーナス B系） ----
    # 2026-07-25 かまど精錬ボーナス: 精錬完了時に確率で追加ドロップ(GatheringExtraDropListenerと同型)。
    # 所有者=精錬物を入れた本人のみ。ホッパー自動投入時は auto-mode-multiplier で減衰。
  # ---- ギリシャ文字路線（上段：alpha=下振れ軽減 / beta=品質上限・排他） ----
    # スライド表記は「C-1」だが本ファイルにC-1は存在しない。内容一致する下振れ軽減系B-alpha-1に配置。
  # 上段ギリシャ路線末端の共通ノード（原表記: B-3 解体解放）。alpha/beta路線の先に配置される
  # 共通末端と読めるため、排他groupには含めず、alpha/betaどちらの終端からも接続可能。
    # スライド表記は「C-2」だが本ファイルにC-2は存在しない。内容一致する解体解放ノードB-3-upperに配置。
```
