# skilltree/ars_magic.yml

出荷config `TrinityForge/src/main/resources/skilltree/ars_magic.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
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
# 排他ルート(実装は group による相互排他で強制。この行は説明のみ): 本ツリーにギリシャ文字路線(排他)は存在しない
```

### 直後: `nodes:`

```
  # ---- 主軸 A〜E（主軸に固有名の記載が画像に無いため世界観で命名） ----
  # ---- 派生ノード（上段：A系 移動/操作グリフ解放） ----
  # ---- 派生ノード（上段：C系 残留/炸裂グリフ解放） ----
  # ---- 派生ノード（上段：E系 超増強/照射グリフ解放） ----
    # スライド表記は「C-3」だが本ファイルにC-3は存在しない。内容一致するE-1に配置。
    # superamplify-beam-glyph-unlock: 旧カタログ代表target(beam)はbeam + super_*全15種(全てarsmagic_perk_e_1)
    # の束を代表していたため、W2c §3.1展開規則に従い全構成グリフへ展開する。
  # ---- 派生ノード（下段：B系 属性グリフ解放） ----
    # 2026-07-25 害悪グリフ強化: 新規stat glyph_damage_multiplier_bonus(fraction、0.3=+30%)。
    # harmグリフに決め打ちしない汎用stat — フォーク側がどのグリフのダメージ計算に適用するか選ぶ
    # (TF公開API TrinityForge#statTotal(caster, "glyph_damage_multiplier_bonus") 経由で読む想定。
    # フック候補: com.arspaper.spell.effect.HarmEffect#applyToEntity のダメージ算出行)。
  # ---- 派生ノード（最下段：C系 召喚グリフ解放） ----
```
