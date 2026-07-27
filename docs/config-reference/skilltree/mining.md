# skilltree/mining.yml

出荷config `TrinityForge/src/main/resources/skilltree/mining.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
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
    # このノードの description/effect-text が約束する「破壊で少量のバニラEXP」の実体。
    # 2026-07-25 CT設計一本化: tierはCTに影響しない。実挙動(tier1、CT短縮なし)は
    # duration-ticks=160(8秒)・cooldown-ticks=600(30秒、全tier共通の基準値)。旧表記「10s/60CD」は
    # どちらも実挙動と食い違っていたため実値に訂正(バランス数値は変更していない)。
      # 2026-07-25 tier常時1固定バグ修正: stats/mining-gimmick.yml haste-active-mining.tiers の
      # tier1行に対応する明示value(SCALE既定値と同じ1だが、A-2/A-3と並べて意図を明示するため明記)。
    # 2026-07-25 CT設計一本化: tier3(amplifier4/duration-ticks200=10秒)+CT短縮0.25を適用した実値。
    # CT = 全tier共通の基準値30秒 ×(1-0.25) = 22.5秒。旧「45CD」表記は実挙動と食い違っていたため訂正
    # (バランス数値=0.25は変更していない)。
    # 2026-07-25 CT設計一本化 §3: 旧 skill-cooldown-reduction(全アクティブスキル共有のグローバルキー、
    # 他の無関係なアクティブスキルにも波及していた)を、haste-active-mining専用の
    # haste-active-mining-cooldown-reduction(ActiveSkillCooldownKeys.forSkill("haste-active-mining"))へ
    # 移行。数値0.25は変更していない。
    # 2026-07-25 tier常時1固定バグ修正: これまでこのノードは feature:haste-active-mining を
    # 再配置しておらず、A-1由来のtier1のまま止まっていた。stats/mining-gimmick.yml
    # haste-active-mining.tiers の tier3行に対応する value:3 を明示配置する。
    # 2026-07-25 CT設計一本化: tier5(amplifier5/duration-ticks240=12秒)+ 親A-2の0.25と自身の0.25が
    # 合算されたCT短縮0.5(perkは祖先ノード分も保持し続けるため)を適用した実値。
    # CT = 全tier共通の基準値30秒 ×(1-0.5) = 15秒。旧「10s/30CD」表記はどちらも実挙動と食い違っていた
    # ため訂正(バランス数値=0.25は変更していない)。
    # 2026-07-25 CT設計一本化 §3: A-2と同じ理由で移行(数値0.25は変更していない、詳細はA-2参照)。
    # 2026-07-25 tier常時1固定バグ修正: stats/mining-gimmick.yml haste-active-mining.tiers の
    # tier5行に対応する value:5 を明示配置する。
```
