# skills/base/light_weapons_progression.yml

出荷config `TrinityForge/src/main/resources/skills/base/light_weapons_progression.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

> **2026-07-26 注記**: `starting_perks:` / `leveling_perks:` は ValhallaMMO 時代の死んだデータとして
> yml 側から削除済み。**現在の yml にこのキーは存在しない**ため、以下は「なぜその層が無いのか」を
> 説明する歴史記録として読むこと(設定箇所を探しても見つからない)。

### 直後: `starting_perks:` — **削除済みキー(歴史記録)**

```
  # LD-9: no Valhalla damagemultiplier layer (TF SymmetricCombat owns damage).
```

### 直後: `leveling_perks:` — **削除済みキー(歴史記録)**

```
  # damagemultiplier removed (LD-9)
```
