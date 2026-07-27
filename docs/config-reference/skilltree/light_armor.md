# skilltree/light_armor.yml

出荷config `TrinityForge/src/main/resources/skilltree/light_armor.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
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

### 直後: `starting-coords:`

```
# 排他ルート(実装は group による相互排他で強制。この行は説明のみ): ギリシャ文字路線(alpha/beta/gamma)はいずれか1つのみ解放可能
```

### 直後: `prestige:`

```
  # ★画像(スライド15)のプレステージ表記は「ダメージ増加・命中精度増加・節約率増加」だが、
  #   これは弓術テンプレの流用ミス。SPEC §2.4 / §6.1 Q5 でユーザーが守備系への読替を確定済み。
  #   → buffs は守備力/防御率/被ダメージ軽減の永続増加を採用する。
```

### 直後: `nodes:`

```
  # ---- 主軸 A〜E ----
    # 軽装セット効果=回避率(重装のノックバック耐性セットのミラー)。2026-07-27(armor-set-buffs全面移行)
    # 以降は set-buffs スキーマ(装備部位3/4段の条件バフ)で表現し、node B の armor-set-bonus が
    # 採用された段の値全体を増幅する(旧 light-armor-set-bonus-multiplier / light-armor-set-dodge-chance
    # から統一)。他候補(魔法耐性/満腹/クリ耐性/スタン耐性、旧light_armor_progression.yml準拠)は
    # 今回不採用・将来拡張の余地として残す。
  # ---- 派生ノード（上段：魔法耐性/回避率） ----
  # ---- ギリシャ文字路線（各主軸の下・alpha=守備力 / beta=防御率 / gamma=被ダメージ軽減・列ごとに排他） ----
```

### 直後: `nodes.B` の `cost: 1`(セット効果ノード、effect-text 直前)

```
2026-07-27: このノードは armor-set-bonus(セット効果の増幅率)だけを持ち、セットの「成立条件」は
持たない。旧文言の「3部位でセットが成立」は誤りだったので削除した。成立条件は各ノードの
set-buffs の段(3/4)が持つ。
```

### 直後: `nodes.C` の `buffs`(set-buffs 直前)

```
2026-07-27: 4部位帯は3部位帯の約1.5倍。3部位の値は移行前と同一のまま据え置いて、
フル装備の報酬としてだけ上乗せしている(既存バランスを動かさないため)。
```

### 直後: `nodes.D` の `cost: 1`(effect-text 直前)

```
2026-07-27: このノードのバフはどちらも無条件で、set-buffs を持たない。
旧文言の「3部位でセットが成立」は実装と一致していなかったので削除した。
```

### 直後: `nodes.A-alpha-1` の `name`

```
2026-07-26 職業別草案(戦闘)準拠: 旧名称「堅・軽身」を変更。中身が守備力(硬さ)から
回避率(速さ)に変わったため。
```

### 直後: `nodes.A-alpha-1` の `effect-text`(buffs 直前)

```
2026-07-26 職業別草案(戦闘)準拠: α=回避型。守備力(重装備側の役割に一本化)からdodge-chanceへ。
```

### 直後: `nodes.A-gamma-1` の `effect-text`(buffs 直前)

```
2026-07-26 職業別草案(戦闘)準拠: γ=遊撃生存型。move-speedを追加し、重装備γ(会心軽減)との
性格差を作る。
```

### 直後: `nodes.A-gamma-1` ブロック全体(次の `nodes.B-alpha-1` の直前)

```
2026-07-26 路線固定(lane-lock): B〜E のギリシャノードは parent を「同じ路線の1つ前」に向ける。
排他は『同じ親を持つ同group兄弟』にしか効かない仕様(NativePerkService / SkillTreeProgressionGenerator)
なので、親が路線ごとに分かれる B〜E に group を書いても no-op になり、AllSkillTreesLoadTest が
警告で落ちる。路線の固定は parent 連鎖だけで担保し、group は A 段(同じ親A)にのみ置く。
```
