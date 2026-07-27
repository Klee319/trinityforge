# stats/digging-gimmick.yml

出荷config `TrinityForge/src/main/resources/stats/digging-gimmick.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### 直後: `durability-exp:`

```
2026-07-25: 切削C-1/C-2「消費したシャベルの耐久値の総量に応じてバニラ/職業経験値UP」チューニング。
シャベルはuse-skill(PDC)==DIGGINGで判定する(材質からの推測はしない)。累計耐久消費量は
プレイヤーPDCへログアウト/サーバー再起動を跨いで永続化する(DiggingDurabilityExpListener)。
実際の上限%(バニラ最大50/職業最大25)はスキルツリー側の dedicated-effects value
(feature:digging-durability-vanilla-exp / feature:digging-durability-job-exp)で決まる。
ここは「1%ボーナスに必要な累積耐久消費量」の変換レートのみを持つ。
```

### 直後: `durability-per-percent: 100`

```
例: 100 なら、耐久を100消費するごとに+1%(上限までクランプ)。
```

