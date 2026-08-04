# gacha.yml

出荷config `TrinityForge/src/main/resources/gacha.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### `gacha_ticket_0:`

券アイテムのitemCatalog ID

### `pool: standard`

参照する景品プールID

### 直後: `gacha_ticket_1:`

```
--- 要件⑥: ガチャ券 tier1〜5 (ArsPaper materials.yml の gacha_ticket_1..5。
    2026-08-04 訂正: 券の実体は items/catalog.yml ではなく ArsPaper 側にある)。
    tier1=切削等で入手しやすい/易しい、tier5=釣り等で希少。景品プールは下の pools.tier1..tier5 を参照。
    券ごとに専用プールが1つずつ存在すれば足りるため(GachaConfig/GachaListenerは
    catalogId -> ticket -> pool の1:1参照のみで動作する)、Java側の変更は不要。 ---
```

### 直後: `pools:`

```
pools: 景品プールの定義。
  キー = プールID (上の tickets.*.pool から参照される)
  pity.threshold: 天井(pity)回数。省略時0=無効(ITEM_ECONOMY_SPEC CR-9安全弁②)。
    このプールで連続してpity.threshold回、"最高レア枠"(entriesの中でweightが最小のもの。
    タイがあれば全て対象)を引けなかった場合、次回抽選は最高レア枠から強制確定になる。
    最高レア枠を引くと(天井発火/自然的中を問わず)カウンタは0にリセットされる。
    カウンタはプレイヤー毎・プール毎にPDCで永続化される。
  entries = 重み付き抽選テーブル。各エントリのweightの比率で当選する
    item          : itemCatalog ID (items/catalog.yml のキー) または バニラMaterial名 (例: DIAMOND)
    weight        : 抽選の重み。1以上の整数(この値の比率で当たりやすくなる)
    amount        : 当選時に付与する個数。1以上の整数
    quality-random: true の場合、TrinityForgeのitemCatalogアイテムに限り
                    0〜現在の最大品質のランダムな品質を付与する(バニラMaterialでは無視される)
```

### `threshold: 30`

最高レア枠(core_wood, weight2/17≈12%)の天井

### `- item: "example_sword"`

itemCatalog ID の例 (品質ランダム付与)

### `- item: "DIAMOND"`

バニラMaterial名の例

### `- item: "core_wood"`

TFコア(グリフ解放アイテム)も景品にできる

### 直後: `tier1:`

```
--- 要件⑥: ガチャ券tier1〜5用プール。中身(weight/品質)は暫定・要調整。tier1が最も渋く、
    tier5に近づくほど豪華・希少になるよう重み付けしている。 ---
```

### `threshold: 20`

最高レア枠(DIAMOND, weight8/33≈24%)の天井

### `threshold: 25`

最高レア枠(core_wood, weight3/18≈17%)の天井

### `threshold: 25`

最高レア枠(core_vegetable, weight3/18≈17%)の天井

### `threshold: 20`

最高レア枠(mage_guardian_novice_helmet, weight2/10=20%)の天井

### `threshold: 15`

最高レア枠(ANCIENT_DEBRIS/archmage_chestplate, 各weight1/7≈14%)の天井

