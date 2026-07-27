# 監査共通ブリーフ (2026-07-25 全体レビュー)

## 対象プロジェクト
Minecraft Paper 1.21.11 サーバー用の独自RPGシステム「TrinityForge」(以下TF)と、その連携のために改変した3つのフォーク、および設定エディタ。

- プロジェクトルート: `C:\Users\T-319\Documents\Program\ClaudeCodeDev\products\minecraft\trinityforge`
- TF本体Java: `TrinityForge/src/main/java/com/trinityforge/` (599ファイル / 約81,000行)
- TF出荷config(yml): `TrinityForge/src/main/resources/{combat,dungeon,economy,hate,items,progression,skills,skilltree,stats,valhalla}/`
- 仕様書: `docs/*.md`, 設計書: `docs/design/`, 過去の作業レポート: `reports/`
- フォーク:
  - ArsPaper (Ars Nouveau のPaper移植): `fork-handoff/arspaper/fork/` — gitあり。ベースライン=`4c78f08`、**我々の改変 = `git diff 4c78f08..HEAD` + 未コミット差分**
  - EliteMobs: `fork-handoff/elitemobs/elitemobs-fork/` — gitあり。**我々の改変 = 未コミットの working tree 差分 (`git diff`, `git status`)**
  - DPSchecker: `fork-handoff/dpschecker/fork/` — gitなし。`src/` 全体が自作に近い
- 設定エディタ (Node.js): `tools/config-editor/` (server.js, lib/, public/, test/)

## このタスクの性質
**監査のみ。コードの修正・編集は一切禁止。** ファイルを書き換えてはならない。
成果は「所見リスト」として指定パスのMDに書き出すこと。

## 探すもの (網羅的に、Low quality の些細なものまで)
1. **BUG** — 実際に誤動作する/しうる箇所。null安全、境界値、非同期・スレッド安全、イベント二重処理、DB整合、リソースリーク、例外握り潰し、単位/符号ミス、順序依存
2. **BALANCE** — ゲーム性の乱れ。数値の破綻(指数爆発/デッドゾーン/上限突破)、無意味な選択肢、支配的戦略、稼ぎ無限ループ(exploit)、進行が詰まる箇所、報酬と労力の不整合
3. **UNIMPLEMENTED** — configやGUIやドキュメントには存在するが実際には何も起きない機能。registryに未登録の効果キー、TODO、空実装、書いたが呼ばれていない設定値
4. **DEADCODE** — 使われていない関数/クラス/フィールド/設定キー、到達不能分岐、旧仕様の残骸(特にValhallaMMO撤廃前の残り物)
5. **REDUNDANCY** — 同じ計算/判定の重複実装、コピペ、責務が重複したクラス、冗長な変換往復
6. **COMMENT** — 後述

## コメント方針 (ユーザー要件)
ソースコードのコメントは総計 約9,771行ある。読み込み速度向上のため以下に仕分けること。
- **KEEP**: AIや後任が作業する上で必要な注釈のみ。すなわち「なぜそうしたか(Why)」「非自明な制約・罠・仕様の根拠」「外部プラグインとの契約」「順序依存やスレッド安全性の理由」「壊れやすい前提」
- **DELETE**: コードを読めば分かる説明(Whatの言い換え)、自明なgetter/setterのJavadoc、区切り線、コメントアウトされた旧コード、TODOのうち完了済みのもの、履歴的な「〜に変更」メモ
- **EXTERNALIZE**: 長い仕様解説・数式の導出・設計背景など、`docs/` に移して該当箇所には1行の参照だけ残すべきもの

## 出力形式 (厳守)
指定されたMDファイルに、以下の表形式＋詳細で書くこと。日本語。

各所見は必ず:
- `ID` : 担当領域プレフィクス + 連番 (例 `CMB-01`)
- `重要度` : HIGH / MEDIUM / LOW
- `分類` : BUG / BALANCE / UNIMPLEMENTED / DEADCODE / REDUNDANCY / COMMENT
- `場所` : プロジェクトルートからの相対パス + 行番号 (例 `TrinityForge/src/main/java/com/trinityforge/combat/DamageCalculator.java:142`)
- `事象` : 1〜2文で何が問題か
- `影響/再現` : どういう条件で何が起きるか。BALANCEなら具体的な数値で示す
- `根拠` : 実際のコード引用(3行以内)。推測ではなく読んだ事実を書く

## 品質ルール
- **推測で書かない。** 必ず該当ファイルを読み、根拠となるコードを引用する。確信が持てないものは所見の末尾に `(要確認)` と明記する。
- 網羅性優先。severityで自主的に間引かない。LOWも全部出す。
- 既に `reports/` の過去レポートで「修正済み」と記録されている項目でも、現在のコードでまだ問題が残っているなら報告する(過去レポートを鵜呑みにしない)。
- 重要度の目安: HIGH=サーバー運用/セーブデータ/ゲーム進行が壊れる or 露骨なexploit / MEDIUM=特定条件で誤動作・体験を損なう / LOW=軽微・可読性・将来の地雷
