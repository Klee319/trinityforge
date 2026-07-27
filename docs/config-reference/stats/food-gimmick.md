# stats/food-gimmick.yml

出荷config `TrinityForge/src/main/resources/stats/food-gimmick.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `cancelled-debuff-effects:`

```
ゴミ食を食べた直後にバニラが付与するデバフ系ポーション効果のうち、免疫時に打ち消す種類
(EntityPotionEffectEvent の Cause.FOOD のみが対象。金リンゴ等の食事由来バフはここに含めない
ことで誤って打ち消さないようにする)。
```

### 直後: `junk-saturation-bonus: 2.0`

```
ゴミ食を食べた際、隠し満腹度(saturation)に追加加算する量。
```

### 直後: `non-junk-saturation-penalty: 1.0`

```
非ゴミ食(通常食)を食べた際、隠し満腹度(saturation)から減算する量(0未満にはならない)。
```

### 直後: `saturation-bonus: 4.0`

```
食事時に追加で加算する隠し満腹度(saturation)。
```

### 直後: `custom-foods:`

```
カスタム食料: 指定アイテム(カタログID/Ars素材ID)を食べた時の満腹度・隠し満腹度を上書き設定する。
food-level=回復する満腹度(0-20), saturation=回復する隠し満腹度。ベースMaterialのバニラ栄養値は無視され、
ここで指定した値ちょうどに置き換わる。解放自体はクラフトゲート(recipe:)側で管理する。
```

