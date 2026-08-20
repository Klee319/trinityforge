# combat/mob-level-table.yml

出荷config `TrinityForge/src/main/resources/combat/mob-level-table.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧(元のyml内での出現順)

### (ファイル末尾の補足)

```
設定例(コメントアウト): Lv0〜19は骨だけ・低EXP、Lv20以降は腐肉を消して追加ドロップ+高EXP。
tiers:
  - min-level: 0
    add-drops:
      - { material: BONE, chance: 0.3, min: 1, max: 2 }
    vanilla-exp: 3
  - min-level: 20
    remove-drops: [ROTTEN_FLESH]
    add-drops:
      - { material: IRON_INGOT, chance: 0.05, min: 1, max: 1 }
      # モブ別ドロップ指定の例: ZOMBIEのキルだけ、カスタムカタログアイテムを追加ドロップ。
      - { material: "custom:some_catalog_id", chance: 0.02, min: 1, max: 1, mobs: [ZOMBIE] }
    vanilla-exp: 12
```

## 対象モブの絞り込み (`mobs` / `mob-ids`) — 2026-07-26 追加

`mobs`(バニラの EntityType)と `mob-ids`(EliteMobs カスタムボスのモブid)の2軸で、
「この帯／このドロップを、どのモブに当てるか」を絞り込める。
どちらも **`add-drops` の各エントリ** と **帯そのもの(tier)** の両方に書ける。

| 軸 | 書式 | 何を指せるか |
| --- | --- | --- |
| `mobs` | `[ZOMBIE, SKELETON]` | バニラ EntityType。`combat/mob-types.yml` のフィールドモブを指す唯一の手段 |
| `mob-ids` | `[the_mines_boss]` | EliteMobs のモブid(`combat/mob-profiles.yml` / `mob-overrides.yml` と同じキー)。拡張子 `.yml` は付けても付けなくても同じ |

- どちらも省略/空なら **全モブに適用**(この機能を足す前と同じ挙動)。
- 両方書くと **AND**(EntityType も一致し、かつモブidも一致するモブだけ)。「A か B」をやりたいときは片方だけ使う。
- **帯そのもの**に書くと、その帯の `remove-drops` / `add-drops` / `vanilla-exp` が丸ごと対象外のモブに当たらなくなる。
  **`add-drops` エントリ**に書くと、その1エントリだけが絞り込まれる。

`mob-ids` が必要な理由: ダンジョンは1つ丸ごと同じ EntityType(見た目替えの `ZOMBIE` 等)であることが多く、
EntityType だけでは個体を区別できない。特定のダンジョンボスを狙い撃ちできるのは `mob-ids` だけ。

## 職業での絞り込み (`roles`) — 2026-08-02 追加

`add-drops` の各エントリだけが `roles` を持てる。**キルしたプレイヤーの職業**でそのエントリを絞る
(`mobs` / `mob-ids` が「倒された側」を絞るのに対し、`roles` は「倒した側」を絞る)。

- 省略/空なら **職業を問わない**(この機能を足す前と同じ挙動)。**既存の add-drops は一切変わらない。**
- 戦闘職・補助職の **どちらの枠で一致しても通る**。職業未選択のプレイヤーには落ちない。
- IDは `progression/role-buffs.yml` のキー。大文字小文字と前後空白は無視する。
- **帯そのもの(tier)には書けない。** 帯ごと職業で切ると、その帯の `remove-drops` や `vanilla-exp` まで
  職業依存になってしまう。

```yaml
add-drops:
  # 坑夫か掘削者が倒したときだけ落ちる。
  - { material: "custom:pit_token", chance: 0.05, min: 1, max: 1, roles: [miner, digger] }
```

```yaml
tiers:
  # Lv50以上の「鉱山のボス」だけ、バニラEXPを厚くしてボス専用素材を落とす。
  - min-level: 50
    mob-ids: [the_mines_boss_phase_3]
    vanilla-exp: 400
    add-drops:
      - { material: "custom:mine_core", chance: 1.0, min: 1, max: 1 }
```

