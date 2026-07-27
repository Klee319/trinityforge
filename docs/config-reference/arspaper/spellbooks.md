# arspaper/spellbooks.yml

出荷config `fork-handoff/arspaper/fork/src/main/resources/spellbooks.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

```
  # ----------------------------------------------------------
  # ティア1: 見習いの魔法書
  # ----------------------------------------------------------
```

```
  # ----------------------------------------------------------
  # ティア2: 魔術師の魔術書（見習いの魔法書からアップグレード）
  # ----------------------------------------------------------
```

```
  # ----------------------------------------------------------
  # ティア3: 大魔導士の魔導書（魔術師の魔術書からアップグレード）
  # ----------------------------------------------------------
```

```
# ============================================================
# catalysts: 触媒アイテム定義
# ============================================================
# 触媒＝「item-catalog相当のアイテム定義＋item-stat相当のステ」を持つアイテム。
# バインドされた魔法を発動すると、その触媒のステータスが反映された魔法が飛ぶ
# （反射経路: SpellBindListener → SpellCaster.cast → TrinityForgeBridge.magicalFinalDamage）。
#
# 【重要】stats(fixed/per-quality/random)の解決はArsPaper側で行わず、TrinityForgeの
# 動的item-stats登録API(registerDynamic)へそのまま渡す。品質(quality)・ランダムロール
# (rollSeed)を反映した実値の算出・lore自動生成・戦闘連携(magicalFinalDamage反射/
# マナ集約)は全てTrinityForgeエンジン側に一本化される（フォークでステ解決ロジックを
# 重複実装しない）。
#
# 各触媒のフィールド:
#   material:            ベースとなるバニラマテリアル
#   display-name:         表示名（MiniMessage可。単色表示でも可）
#   name-color:           表示名の色（Hex "#RRGGBB"、任意）
#   custom-model-data:    CustomModelData値。他のカスタムアイテムと重複しない値にすること
#                         （TrinityForge側はmaterial+custom-model-dataの組でステを解決するため）
#   color:                革防具素材の場合のみ有効な染色色（Hex "#RRGGBB"、任意）
#   lore:                 フレーバーLore（任意、文字列配列）
#   bind-type:            TrinityForge BindType名（SOULBOUND / TRADEABLE / OWNER_BOUND）。
#                         未指定ならbind-type刻印は行わない
#   max-bind-tier:        この触媒にバインド可能なスペルの最大グリフtier。
#                         超過するスペルのバインドは拒否される（未設定時は3=事実上無制限）
#   mana-cost-reduction:
#     flat:               発動時のマナ消費から実数で減算する値（未設定=0）
#     percent:            発動時のマナ消費を割合(%)で減少させる値。ManaManagerの装備由来
#                         削減%に加算合成される（未設定=0）
#   cooldown:             発動CT(秒)。0または未設定=触媒由来の追加CTゲートなし
#                         （form別/連射CTとは別キー空間で判定される）
#   stats:                item-stat相当のステ定義。canonicalキー(ハイフン区切り、例: attack-power,
#                         mana-bonus)で指定する。int系ステ(将来的にthread-slots相当を持たせる場合等)
#                         はTrinityForge側の解決結果を丸めて適用するため、ここでの入力値自体は
#                         そのままdouble精度で登録する。
#     fixed:              固定値ステ
#     per-quality:        品質1あたりの加算値ステ
#     random:              ランダムロール範囲ステ（min/max）。rollSeedにより個体ごとに解決される
# ============================================================
```