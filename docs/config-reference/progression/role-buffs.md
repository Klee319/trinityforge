# progression/role-buffs.yml

出荷config `TrinityForge/src/main/resources/progression/role-buffs.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `allow-command: true`

```
非戦闘時のみ /tf role set で変更可。将来はダンジョンドロップアイテム化。
```


### 直後: 各ロールの `icon:` / `description:` (2026-07-28)

```
icon        : /tf role set のGUIでこのロールを表すアイテム(Material名)。
              省略・不正な名前のときは既定アイコン(戦闘職=鉄の剣 / 補助職=本)へフォールバックする。
description : GUIと /tf role のチャット表示に添える1行説明。省略可。
どちらもロールの効果には一切影響しない表示専用の項目。
```
