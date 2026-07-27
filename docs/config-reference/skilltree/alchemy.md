# skilltree/alchemy.yml

出荷config `TrinityForge/src/main/resources/skilltree/alchemy.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
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

### 直後: `ingredient_save_chance: 0.15` (Dノード, 2026-07-26 追加)

```
醸造台+アルケミカル素材投入の両方に適用(2026-07-26、BrewIngredientSaveListener新設で
バニラ醸造台への配線が完成。詳細は docs/config-reference/stats/lore.md の「材料節約率」項参照)。
既定値(要調整)(旧 dedicated-effects ingredient-no-consume-chance, stat化)。
```

### 直後: `starting-coords:`

```
# 排他ルート(実装は group による相互排他で強制。この行は説明のみ): ギリシャ文字路線(alpha=上段 / beta=下段)はいずれか1つのみ解放可能
```

### 直後: `nodes:`

```
  # ---- 主軸 A〜E（主軸に固有名の記載が画像に無いため世界観で命名） ----
    # 品質+1=potion_quality_bonus(醸造ポーションの効果時間/強度への換算ポイント、
    # PotionQualityListener/stats/alchemy-quality.yml消費)。醸造速度UP=brew_speed_bonus
    # (醸造時間の短縮率、同リスナー消費)。
    # 武器コーティング解放（Valhalla の light/heavy weapons コーティング機構をここで開放）。
    # コーティング解放は dedicated-effects、回数は buffs に統合する。
    # 武器コーティングのスタック上限を増強（Lv90 主軸。Valhalla alchemy_perk_combo の +10 に準拠）。
    # 交換・色変換グリフの解放は ArsPaper 側 usage-gate（perk alchemy_perk_e）で扱う。
    # 注意: スタック増強はノードCと同じくnativeクラスキーのみで表現する。dedicated
    # `coating-stack-increase` を併記するとlistenerが両方を加算して設計値+10の2倍になるため、
    # 本ノードにdedicated値は置かない(2026-07-22 verifier F2)。
  # ---- 派生ノード（上段：魔法/グリフ系。原表記 C-1 / C-2） ----
    # スライド表記は「C-3」だが本ファイルにC-3は存在しない。内容一致するE-1-1に配置。
  # ---- 派生ノード（下段：グリフ開放系。原表記 C-1 / C-2） ----
    # スライド表記は「B(B-2)」だが本ファイルのBには該当記述が無く、内容一致するC-1-lowerに配置。
    # スライド表記は「B-3」だが本ファイルには存在しない。内容一致するC-2-lowerに配置。
    # スライド表記は「B-4」だが本ファイルには存在しない。デバフ系グリフ路線の終端であるE-2-1に
    # 最も内容が近いと判断し配置(multi-debuff-glyph-unlockのカタログ側game配線はtarget: hexで確定済み)。
  # ---- ギリシャ文字路線（alpha=上段 / beta=下段・排他） ----
    # B案(ユーザー確定): 「残留時間UP・スプラッシュ強度UP」も品質(potion_quality_bonus)と同じ
    # 仕組みで表現する。SPLASH/LINGERINGボトル限定の追加時間加算(lingering-splash-duration-ticks-
    # per-quality)は PotionQualityListener が全ての potion_quality_bonus 保持者へ均等に適用するため、
    # この経路専用の値ではなくポイントの蓄積として表現する。
    # 武器コーティングのスタック増強（beta 分岐。主軸 E より控えめの +5）。
```
