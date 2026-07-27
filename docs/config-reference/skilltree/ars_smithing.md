# skilltree/ars_smithing.yml

出荷config `TrinityForge/src/main/resources/skilltree/ars_smithing.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
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
# 排他ルート(実装は group による相互排他で強制。この行は説明のみ): ギリシャ文字路線(alpha=品質 / beta=スレッド欄)はいずれか1つのみ解放可能(推定)
```

### 直後: `nodes:`

```
  # ---- 主軸 A〜E（主軸に固有名の記載が画像に無いため世界観で命名） ----
  # ---- 派生ノード（上段：A系 ソース関連） ----
  # ---- 派生ノード（上段：D系 Waystone） ----
    # スライド表記は「B-1-3」だが本ファイルにB-1-3は欠番(存在しない)。内容一致するD-1に配置。
    # teleport_compass はWaystoneと対で使う紐づけアイテムのため同ノードでクラフト解放(2026-07-24)。
    # 2026-07-25: waystone/teleport_compassは共に「儀式」クラフト(RitualManager経由)であり、
    # 儀式クラフトの権限チェックはUnlockGate.hasRitualPermission()がRITUAL_GATE
    # (dedicated-effects "ritual:"プレフィックス)のみを見る。以前の"recipe:"プレフィックスは
    # RECIPE_GATE(作業台PrepareItemCraftEvent専用)にルーティングされ、儀式クラフトのパスからは
    # 一切参照されないため、このゲートは実質無効化(fail-open)されていた(要修正確認済み)。
    # 併せてfunctional-items.yml統合でレシピidが "waystone_craft"→"waystone" に変わったため、
    # targetもそれに合わせて更新した。
  # ---- 派生ノード（上段2段目：B系 クラフト解放。※B-1-3欠番） ----
  # B-1-3 は本スライドに存在しない（欠番）。存在しないノードは作成しない。
  # ---- 派生ノード（下段：B系 儀式解放） ----
  # ---- ギリシャ文字路線（最下段：alpha=品質 / beta=スレッド欄・排他） ----
  # ---- 上振れ(幸運鍛冶)雛形ノード ----
  # 品質抽選の「上振れ幅(spread-up)」を広げるパーク。stats/quality.yml の spread-up に加算され、
  # クラフト時のみ効く(敵ドロップは対象外)。native キーは <スキル接頭辞>_<小文字stat名>_add 規則で
  # arssmithing_craftupswingbonus_add(= ArsSmithingProfile.craftUpswingBonus, doubleStat)に対応。
  # 値は小数可(σへの加算。例 0.5 で釣鐘の上側が広がり高品質が出やすくなる)。exclusive group には入れない
  # 独立ブランチとして配置(alpha=品質 / beta=スレッド欄の排他に干渉しない)。数値・Lv・親は暫定。
```
