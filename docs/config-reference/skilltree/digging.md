# skilltree/digging.yml

出荷config `TrinityForge/src/main/resources/skilltree/digging.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
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

### 直後: `nodes:`

```
    # PRG-01: feature:break-vanilla-exp が全16ツリー未配置で機構が到達不能だったため配置。
    # 2026-07-25 CT設計一本化: value未指定でtier1固定(amplifier3/duration-ticks160=8秒)、CT短縮なし
    # なのでCT=全tier共通の基準値30秒のまま。旧「10s」表記は実挙動(8秒)と食い違っていたため訂正
    # (バランス数値は変更していない、CD側の「30sCD」はもともと実値と一致していた)。
    # 2026-07-25 CT設計一本化 §3: 旧 skill-cooldown-reduction(全アクティブスキル共有のグローバルキー)を
    # haste-active-mining-cooldown-reduction へ移行。数値0.2は変更していない。
    # 2026-07-25 段階IV到達修正: mining.yml A-2 と同じ流儀で feature:haste-active-mining の value:3 を
    # 再配置(tiersテーブルは stats/mining-gimmick.yml haste-active-mining.tiers を共用、tier3=
    # amplifier4/duration-ticks200=10秒)。これでA-1由来のtier1固定から解放され「段階IV」に到達する。
    # CD = 全tier共通の基準値30秒 ×(1-0.2) = 24秒(CTはtierに影響されないためA-1と同じ24秒のまま)。
    # 効果テキストを実挙動(採掘速度上昇IV・10秒・CD24秒)に合わせて訂正(バランス数値0.2は変更していない)。
    # 2026-07-25: valueは上限%(=50)。実際の付与率は累計耐久消費量に応じて0〜valueで線形に増える
    # (DiggingDurabilityExpListener、stats/digging-gimmick.yml durability-exp.durability-per-percent参照)。
    # シャベル判定はuse-skill(PDC)のみ(材質推測はしない)。
```
