# arspaper/unlock-gate.yml

出荷config `fork-handoff/arspaper/fork/src/main/resources/unlock-gate.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

```
# 儀式レシピID → 必要 perk ID。
# 儀式レシピID は RitualRecipe.id()（items.yml の ritual_effects: キー）。
# 未定義 = ゲート無し。空マップ = yml 側ゲート無し。
```

```
# 修繕儀式（Ars 鍛冶「修復の儀式」）の設定。
# バニラ修繕は別 fork で廃止されるが、Ars の修繕儀式は許可する。
# 無限修繕の抜け道を防ぐため、追加 Source コストで重くできる。
```

```
  # false にすると修繕儀式自体を無効化する。
```

```
  # 修繕儀式の既存 Source コストへの上乗せ量（0 = 据え置き）。
  # 儀式レシピ側の source: でも基礎コストは調整可能。これはさらに上乗せする値。
```