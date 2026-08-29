# TrinityForge 設定リファレンス

出荷config(`TrinityForge/src/main/resources/**/*.yml`、全72本)の**本文コメント**(ファイル先頭のヘッダコメントブロックを除く部分)をここへ移設したもの。

## なぜ移設するのか

`tools/config-editor/lib/yamlio.js` の保存処理は `YAML.stringify` で全文を再シリアライズし、**ファイル先頭のヘッダコメントブロックだけ**を元の生ファイルから抜き出して貼り直す(`extractHeader` 関数)。つまり:

- **ヘッダコメント(ファイル先頭から連続する `#` 行・空行)は config-editor で保存しても消えない。**
- **本文中(実際の設定値の間やその後)にあるコメントは、config-editor で1回でも保存すると復元されず消える。**

出荷configのコメントは合計約1,568行あり、Javaのコメントと違って**運用者(サーバー管理者)が読む設定ドキュメント**である。エディタで保存するたびに説明が失われていくのは運用上のリスクなので、消える可能性のある本文コメントだけをこのディレクトリへ退避し、yml側にはヘッダに1行リンクを追記するに留めた。

## 移設の方針

- **ヘッダコメントは触っていない。** ヘッダはconfig-editor保存後も残るため、移設の必要がない。
- **本文コメント(ヘッダ以降)だけを機械的に抽出**し、元の出現順のままこのディレクトリのMarkdownへ書き出した。コメントアウトされた設定例(`# tiers:` の下に実例がコメントアウトされているもの等)も「設定例」としてそのまま収録している。情報の削除は一切していない。
- **設定値(キー・値)は1文字も変更していない。** 変更したのはコメント行の削除と、ヘッダ末尾へのリンク行1行の追加のみ。
- 検証方法: 移設前後のyml本文をそれぞれ `yaml` パッケージ(config-editorが実際に使っているのと同じ依存)でパースし、結果のオブジェクトを `JSON.stringify` で比較。全20ファイルで一致を確認済み(下記「検証結果」参照)。加えて出荷config全73ファイルを走査し、パースエラーが0件であることも確認済み。

## ディレクトリ構成

`resources/` 配下のディレクトリ構造をそのまま踏襲している(例: `resources/combat/mob-level-table.yml` → `docs/config-reference/combat/mob-level-table.md`)。運用者がyml側のヘッダのリンクから該当ファイルへ1クリックで辿れることを優先した(1ファイルへ集約する案は、ファイル数が多く分割の方が検索性が高いため採用しなかった)。

## 検証結果 (2026-07-25 実施)

- 対象20ファイルすべて: 移設前(バックアップ)と移設後(現行yml)を `YAML.parse` してオブジェクト比較 → **完全一致 (true)**
- 出荷config全73ymlファイルの一括パース → **パースエラー 0件**
- 検証スクリプトはセッションのスクラッチパッドに保存(`migrate_all.js` / `verify_all.js`)。再実行すればいつでも同じ検証ができる。

## 移設済み一覧 (20ファイル)

コメント量(本文のみ、ヘッダ除く)の多い順に着手。

| yml | 本文コメントブロック数 | リファレンス |
|---|---|---|
| `stats/fishing-gimmick.yml` | 16 | [stats/fishing-gimmick.md](stats/fishing-gimmick.md) |
| `stats/lore.yml` | 16 | [stats/lore.md](stats/lore.md) |
| `stats/skill-exp.yml` | 14 | [stats/skill-exp.md](stats/skill-exp.md) |
| `gacha.yml` | 14 | [gacha.md](gacha.md) |
| `combat/mob-import.yml` | 9 | [combat/mob-import.md](combat/mob-import.md) |
| `combat/damage.yml` | 7 | [combat/damage.md](combat/damage.md) |
| `hate/rates.yml` | 7 | [hate/rates.md](hate/rates.md) |
| `combat/mob-defaults.yml` | 6 | [combat/mob-defaults.md](combat/mob-defaults.md) |
| `stats/food-gimmick.yml` | 5 | [stats/food-gimmick.md](stats/food-gimmick.md) |
| `stats/woodcutting-gimmick.yml` | 4 | [stats/woodcutting-gimmick.md](stats/woodcutting-gimmick.md) |
| `stats/mining-gimmick.yml` | 3 | [stats/mining-gimmick.md](stats/mining-gimmick.md) |
| `stats/enchant-luck.yml` | 7 | [stats/enchant-luck.md](stats/enchant-luck.md) |
| `stats/farming-gimmick.yml` | 3 | [stats/farming-gimmick.md](stats/farming-gimmick.md) |
| `dungeon/themes.yml` | 3 | [dungeon/themes.md](dungeon/themes.md) |
| `combat/mob-level-table.yml` | 1 (コメントアウト設定例13行) | [combat/mob-level-table.md](combat/mob-level-table.md) |
| `combat/base-stats.yml` | 1 | [combat/base-stats.md](combat/base-stats.md) |
| `stats/alchemy-quality.yml` | 2 | [stats/alchemy-quality.md](stats/alchemy-quality.md) |
| `stats/quality.yml` | 1 | [stats/quality.md](stats/quality.md) |
| `stats/digging-gimmick.yml` | 2 | [stats/digging-gimmick.md](stats/digging-gimmick.md) |
| `progression/role-buffs.yml` | 1 | [progression/role-buffs.md](progression/role-buffs.md) |

## 移設不要と判定した既存ファイル(本文コメントが元から0件)

以下は調査の結果、**コメントが全てヘッダブロック内に収まっており、config-editorの保存で消えるリスクが元々無い**ことを確認した。何もしていない(意図的にスキップ)。

| yml | ヘッダ行数 |
|---|---|
| `stats/item-stats.yml` | 124 |
| `items/catalog.yml` | 72 |
| `combat/mob-types.yml` | 75 |
| `combat/mob-profiles.yml` | 36 |
| `dungeon/gates.yml` | 41 |
| `progression/special-rewards.yml` | 30 |
| `progression/combat-level.yml` | 21 |
| `progression/collection.yml` | 16 |
| `progression/achievements.yml` | 14 |
| `stats/gathering-efficiency.yml` | 14 |
| `stats/smithing-gimmick.yml` | 14 |
| `items/material-lists.yml` | 11 |
| `progression/use-requirements.yml` | 8 |
| `stats/craft-quality.yml` | 7 |
| `progression/crafting-features.yml` | 6 |
| `stats/glyph-damage-boost.yml` | 6 |
| `economy/villager-trades.yml` | 3 |
| `items/external-items.yml` | 2 |
| `stats/quality-tiers.yml` | 8 |

**注記(重要な訂正)**: `stats/item-stats.yml` と `items/catalog.yml` は当初、機械集計で「本文コメント大量あり」と誤判定された(それぞれ1,112行・42行)。実際に中身を確認したところ、これは `MATERIAL#CMD`(例: `LEATHER_HELMET#200124:`)というキー名や、MiniMessage色タグ・HEXカラー値(`<color:#7df9ff>` 等)に含まれる `#` 文字を、単純な正規表現がコメント開始記号と誤認していた**誤検知**だった。両ファイルとも実際の本文コメントは0件で、ヘッダ(124行・72行)がコメントの全量である。危険なファイルへの機械的なコメント除去を避けるため、この誤検知は移設スクリプトを実行する前に個別確認して除外した。

## 第2バッチ (2026-07-25 追加実施・32ファイル)

初回バッチで「他エージェントが並行編集中のため見送り」としていた32ファイルを移設した。本文コメント計206行を25本のMarkdownへ退避し、**全32ファイルで移設前後の `YAML.parse` 結果が完全一致することを確認済み**(オーケストレータ側でも独立に再検証: 32ファイル・不一致0件)。

| 対象 | ファイル数 | 移設先 |
|---|---|---|
| `skilltree/*.yml` (alchemy/archery/ars_magic/ars_smithing/digging/enchanting/farming/fishing/heavy_armor/heavy_weapons/light_armor/light_weapons/mining/power/smithing/woodcutting) | 16 | `docs/config-reference/skilltree/*.md` |
| `skills/base/*_progression.yml` | 16 | `docs/config-reference/skills/base/*.md` (本文コメントが元から無い7本はMarkdown未作成) |

とくに失うと再導出が必要になる内容として、以下を原文のまま収録している:

- `skills/base/power_progression.md` — ノード取得率70%目標から `9L^2+791L=360,000` → `max_level≈160` を導いた式(PRG-08)と、プレステージ減衰の幾何級数による総供給上限720,000EXPの証明(PRG-09)
- `skilltree/mining.md` — アクティブスキルCTのtier別実値と、グローバル `skill-cooldown-reduction` から専用キー `haste-active-mining-cooldown-reduction` へ移行した経緯
- `skilltree/alchemy.md` — 設計スライドのノードIDと実config値の対応、同名重複ノードの一意化規則

## 第3バッチ (2026-07-26 追加実施・ArsPaper設定ファイル)

TrinityForge統合アドオン ArsPaper の設定ファイル(`fork-handoff/arspaper/fork/src/main/resources/*.yml`)のうち、13本を移設した。本文コメント計448行を8本のMarkdownへ退避し、**全13ファイルで移設前後の `YAML.parse` 結果が完全一致することを確認済み**。

| 対象 | ファイル数 | コメント行数 | 移設先 |
|---|---|---|---|
| ArsPaper config files | 13 | 448 | `docs/config-reference/arspaper/*.md` |

移設対象の内訳:

| yml | ステータス | コメント行数 | リファレンス |
|---|---|---|---|
| `glyphs.yml` (優先度:高、78KB) | SUCCESS | 334 | [arspaper/glyphs.md](arspaper/glyphs.md) |
| `spellbooks.yml` | SUCCESS | 48 | [arspaper/spellbooks.md](arspaper/spellbooks.md) |
| `sourcelinks.yml` | SUCCESS | 38 | [arspaper/sourcelinks.md](arspaper/sourcelinks.md) |
| `unlock-gate.yml` | SUCCESS | 9 | [arspaper/unlock-gate.md](arspaper/unlock-gate.md) |
| `threads.yml` | SUCCESS | 12 | [arspaper/threads.md](arspaper/threads.md) |
| `thread-sets.yml` | SUCCESS | 4 | [arspaper/thread-sets.md](arspaper/thread-sets.md) |
| `sourcejars.yml` | SUCCESS | 2 | [arspaper/sourcejars.md](arspaper/sourcejars.md) |
| `functional-items.yml` | SUCCESS | 1 | [arspaper/functional-items.md](arspaper/functional-items.md) |
| `ban.yml` | NO_COMMENTS | 0 | — |
| `config.yml` | NO_COMMENTS | 0 | — |
| `items.yml` | NO_COMMENTS | 0 | — |
| `materials.yml` | NO_COMMENTS | 0 | — |
| `usage-gate.yml` | NO_COMMENTS | 0 | — |

とくに失うと再導出が困難になる内容として、以下を原文のまま収録している:

- `arspaper/glyphs.yml` — グリフ(効果・形態)の詳細な仕様説明(334行)。増幅/減衰/延長/短縮等の修飾子がそれぞれの効果で何を意味するのか(例: 破壊効果では「増幅=ツールティア上昇」、炎上効果では「増幅=火炎ダメージ↑」)、射程・範囲・条件などの運用上重要なルール
- `arspaper/spellbooks.yml` — 魔導書ティアの段階定義と、段階変更時の互換性に関する警告(「既存要素の並び替えは既存プレイヤーのtier値の意味をずらしてしまうため、行う場合は移行手順やお知らせを別途用意すること」)
- `arspaper/sourcelinks.yml` — 各ソースリンク種別の材料と変換値の参考コメント(「Reference: coal (1600 burn ticks) = 5 source points」など)
- `arspaper/unlock-gate.yml` — 修繕儀式の設定方針(バニラ修繕は廃止される一方、Ars の修繕儀式は許可する、ただしソースコスト上乗せで抑制可能)と未実装ゲートの一覧

## 未移設 (対象外)

| 対象 | 理由 |
|---|---|
| `fork-handoff/arspaper/fork/src/main/resources/paper-plugin.yml` (ArsPaper) | Bukkit標準の `plugin.yml` 相当のメタ情報ファイル。本文コメント2行のため移設対象外。 |
| `TrinityForge/src/main/resources/paper-plugin.yml` | Bukkit標準の `plugin.yml` 相当のメタ情報ファイルで、運用者が読む「設定ドキュメント」という性質のものではないため対象外とした(本文コメントは2行のみで実害も小さい)。 |

## 移設スクリプトについて

移設は手作業ではなく、以下の機械的な手順で実施した(判断が必要な箇所は個別に人手で確認してから対象に含めている):

1. ヘッダ(先頭から連続する `#` 行・空行)の終端をconfig-editorの `extractHeader` と同一ロジックで検出。
2. ヘッダ以降を走査し、行頭が `#` の行(連続するものは1ブロックとして結合)と、値の後ろに続く行末コメント(`key: value  # 説明` の `# 説明` 部分。クォート文字列内の `#` は除外する簡易パーサで誤検知を回避)を抽出。
3. 抽出したテキストは元の出現順のまま Markdown 化。
4. 元ファイルからは該当コメントのみを除去し、ヘッダ末尾に `# 設定リファレンス(本文コメント移設先): docs/config-reference/<path>.md` を1行追記。
5. 移設前後をそれぞれYAMLパースし、結果のオブジェクトが完全一致することを確認できたファイルのみ書き込み(不一致なら書き込みを中止する安全装置つき)。
