# stats/farming-gimmick.yml

出荷config `TrinityForge/src/main/resources/stats/farming-gimmick.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `radius: 1`

```
範囲収穫の半径(ブロック)。水平X/Zのみ(作物は同じYの耕地上)。1 = 起点を中心とした3x3(8マス追加)。
```

### 直後: `multiplier: 4.0`

```
動物(Animals実装、敵対mob除く)への与ダメージ倍率。
```

### 直後: `calm-radius: 8.0`

```
蜂の巣/養蜂箱の採取時、周囲何ブロック以内のハチの怒りをリセットするか(ベストエフォート・要調整)。
```

