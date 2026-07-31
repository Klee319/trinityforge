# progression/role-buffs.yml

出荷config `TrinityForge/src/main/resources/progression/role-buffs.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `allow-command: true`

```
コマンド・GUI からのロール変更を受け付けるか。将来はダンジョンドロップアイテム化。
戦闘中に変更できるかどうかは nearby-enemy-radius 側で決まる(既定では変更できる)。
```

※ 2026-07-31 まで「非戦闘時のみ /tf role set で変更可」と書かれていたが、その「非戦闘時のみ」は
`allow-command` の意味ではなく、`RoleChangeService` 側のハードコードされた近接ガードの説明だった。
ガードは `nearby-enemy-radius` として config 化し、既定を 0（無効）にしたので記述を改めた。


### 直後: `nearby-enemy-radius: 0` (2026-07-31)

```
「交戦中はロールを変更できない」ガードの走査半径(ブロック)。0 でガードそのものを無効化(既定)。上限は64。
0 より大きくすると、その半径内に【自分を狙っている敵】が居る間だけ変更できなくなる。

【なぜ既定を 0 にしたか】2026-07-31 まではこの半径が 16 のハードコードで、敵対判定に Bukkit の
Monster を使っていたため、意図と両方向に外れていた:
  ・ネザーはゾンビピグリン/ピグリンが常時居る(中立だが Monster)ので事実上永久に変更不可。
    壁越し・真下の洞窟のモブや昼のクモでも拒否されるので、拠点や洞窟付近では正常系で常時ブロック。
  ・逆に Slime/Ghast/Phantom/Shulker/EnderDragon/Hoglin は Monster ではない(Enemy ではある)ので、
    エンドラやガストと戦っている最中は自由に変更できていた。
判定を Paper の Enemy＋「自分を狙っているか」へ寄せた上で、既定は解禁側(0)に置いた。
乗せ替え悪用の抑止は cooldown-minutes が担うので、0 にしても clear→即再選択の迂回路は開かない。

・ロール選択GUIを開く操作と /tf role clear はこのガードを通さない(読むだけ・外すだけなので)。
・ターゲットを公開しないボスAI(エンドラ等)はこのガードを跨げる(厳密な戦闘タグ機構は無い)。
・cooldown-minutes: 0 と同時に 0 にすると、被弾直前に tank・与ダメ直前に mage へ無制限に往復できる。
```


### 直後: 各ロールの `icon:` / `description:` (2026-07-28)

```
icon        : /tf role set のGUIでこのロールを表すアイテム(Material名)。
              省略・不正な名前のときは既定アイコン(戦闘職=鉄の剣 / 補助職=本)へフォールバックする。
description : GUIと /tf role のチャット表示に添える1行説明。省略可。
どちらもロールの効果には一切影響しない表示専用の項目。
```
