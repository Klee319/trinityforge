# skilltree/power.yml

出荷config `TrinityForge/src/main/resources/skilltree/power.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
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
  # ---- 主軸: 活力 (max-health) ----
  # ---- 派生: 足元強化 (旧 Iron Skin → knockback resist) ----
  # ---- 派生: 天恵 (luck) ----
  # ---- 派生: 持久 (旧 Physique → hunger save) ----
  # ---- 派生: 回収 (looter) ----
  # ---- 派生: 俊敏 (cooldown) ----
```
