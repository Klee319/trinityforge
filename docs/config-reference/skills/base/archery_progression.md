# skills/base/archery_progression.yml

出荷config `TrinityForge/src/main/resources/skills/base/archery_progression.yml` の本文コメント(先頭ヘッダを除く)をここに移設したもの。
config-editor で保存すると本文コメントは復元されないため([tools/config-editor/lib/yamlio.js](../../tools/config-editor/lib/yamlio.js) 参照)、運用ドキュメントとしてここに退避している。
**設定値そのもの(キー/値)はymlの方を参照。ここはコメント本文のみを収録し、移設前後でキー・値が完全一致することをスクリプトで検証済み。**

## 本文コメント一覧

現在、移設対象の本文コメントはありません。

## 弓術EXPの権威はこのファイルではない (N5 / 2026-07-31)

**弓術EXPの獲得量は `stats/skill-exp.yml` の `combat.kill-exp` が持つ**(軽武器・重武器と
まったく同じ経路・同じキー。基礎値は `combat.kill-exp.base.ARCHERY`)。
このファイルに残るのは**レベル曲線**(`max_level` / `exp_level_curve` = 次のレベルに必要なEXP量)
だけで、獲得量の係数は1つもない。

ユーザー報告「弓術のスキルだけ経験値が討伐時ベースではなくダメージベースになっている」を受け、
弓術だけが命中1回ごとに与ダメージ比例で即時付与される方式だったのを討伐時ベースへ統一した。
per-hit 方式専用だった以下の係数は**方式ごと削除**した(残すと config-editor から編集できるのに
効かないキーになる)。

| 削除したキー | 旧用途 |
|---|---|
| `bow_exp_base` / `crossbow_exp_base` | 弓/クロスボウ別の1射あたり基礎EXP |
| `damage_exp_bonus` | 与ダメージ1点あたりのEXP加算率 |
| `distance_exp_multiplier_base` / `distance_exp_multiplier` / `distance_limit` | 距離ボーナス |
| `infinity_multiplier` | 無限エンチャント付き弓の減額 |
| `spawner_spawned_multiplier` | スポナー産の敵からの減額 |
| `max_health_limitation` | 過剰ダメージの打ち切り |
| `pvp_multiplier` | 対プレイヤー時の倍率 |
| `entity_exp_multipliers` | 敵種別倍率(`combat.kill-exp.entity-type-multipliers` と同値だったので移植不要) |

このため**距離ボーナス・無限エンチャント減額・スポナー産減額・弓/クロスボウ差は廃止**され、
弓術の稼ぎは射距離に依存しなくなった。
