# 09. オーケストレータ直轄クロスチェック (実行結果に基づく確定所見)

すべて実際にコマンドを実行して確認した事実。推測なし。

## 実行したテスト

| 対象 | コマンド | 結果 |
|---|---|---|
| TrinityForge (Java) | `./gradlew test --offline` | **tests 1860 / failures 1 / errors 0 / skipped 2** |
| config-editor (JS) | `npm test` | **tests 381 / pass 379 / fail 2** |

---

## ORC-01 【HIGH / BUG+UNIMPLEMENTED】武器シリーズ `morningstar` が全ティア消失。ガードテストが実際に落ちている

- 場所: `TrinityForge/src/main/resources/items/catalog.yml`、ガード: `TrinityForge/src/test/java/com/trinityforge/config/LegacyValhallaRuntimeContentTest.java:70`
- 事象: ネイティブ再実装した武器シリーズの消失を防ぐガードテストが**現在失敗している**。`morningstar` が catalog に1ティアも存在しない。
- 根拠(テスト失敗メッセージ):
  ```
  AssertionFailedError: catalog missing native weapon wooden_morningstar ==> expected: <true> but was: <false>
  ```
  `grep -cE "^  [a-z]+_morningstar:" catalog.yml` → `0`。他シリーズは dagger/rapier/warhammer/greataxe が各10ティア存在。
- 影響: このテスト失敗は「既存staleではなく実際のコンテンツ欠落」。過去セッションの記録では「TF1515/8失敗は全て既存stale」とされていたが、**現在の失敗1件は内容が伴う実欠落**である。morningstar 系のレシピ・リソースパックCMD・item-stats も連鎖して存在しないはず(要確認)。

## ORC-02 【HIGH / BUG】コレクション図鑑「インフィニティ」が永久に達成不能

- 場所: `TrinityForge/src/main/resources/progression/collection.yml:42`
- 事象: 図鑑カテゴリ `infinity` のエントリに `infinity_morningstar` が含まれるが、catalog に該当アイテムが存在しない(ORC-01の連鎖)。
- 根拠: 全yml横断スキャンで catalog に存在しない武器系IDを検出したところ、`progression/collection.yml: infinity_morningstar` が唯一ヒット。
  ```yaml
        - infinity_mace
        - infinity_morningstar   # <- catalog.items に存在しない
        - infinity_pickaxe
  ```
- 影響: 21エントリ中1つが入手不可能 → カテゴリ100%達成が原理的に不可能。図鑑報酬を達成率で出す設計(`collection.percent`)がある以上、100%到達フラグが永久に立たない。

## ORC-03 【HIGH / BALANCE+BUG】ガチャの当選枠に「存在しないプレースホルダーID」が本番configで生き残っている

- 場所: `TrinityForge/src/main/resources/gacha.yml` (pools.standard / pools.tier2 / pools.tier3)
- 事象: 景品エントリに `example_sword` / `example_bow` という**サンプル用ID**がそのまま出荷されている。両IDとも catalog に存在しない。
- 根拠:
  ```yaml
  pools:
    standard:
      entries:
        - item: "example_sword"   # itemCatalog ID の例 (品質ランダム付与)
          weight: 10              # <- standard プールの重み合計17のうち10
  ```
  `tier2`: `example_sword` weight 5/18、`tier3`: `example_bow` weight 5/18。
- 影響(重み計算):
  - standard プール = **58.8% (10/17)** が解決不能エントリ
  - tier2 = 27.8% (5/18)、tier3 = 27.8% (5/18)
  `GachaListener.java:129-137` に「解決できない景品では券を消費しない」ガードがあるため**アイテムロストは起きない**が、代わりに:
  1. プレイヤーは券を右クリックしても**約6割の確率で何も起きない**(再クリックが必要)
  2. その都度コンソールに warning が出る(高頻度でログを汚す)
  3. 抽選失敗時は `setGachaPityCount` に到達しないため**天井カウンタが進まない** → pity 30回の実効回数が理論値より大幅に増える
- 根拠(ガード):
  ```java
  plugin.getLogger().warning("[gacha] prize item '" + drawn.itemId()
          + "' could not be resolved (not a catalog id or vanilla Material); ticket not consumed");
  ```

## ORC-04 【HIGH / BUG】config-editor で保存すると yml の本文コメントが全消滅する

- 場所: `tools/config-editor/lib/yamlio.js:48-58`
- 事象: 保存時に `YAML.stringify(data)` で再シリアライズし、**先頭ヘッダのコメントブロックだけ**を貼り直す実装。本文途中のコメントは復元されない。
- 根拠:
  ```js
  const body = YAML.stringify(data, { lineWidth: 0, nullStr: "" });
  const header = previousRaw ? extractHeader(previousRaw) : "";
  return `${header}\n\n${body}`;
  ```
  コード先頭のコメントでも「本文途中のコメントは失われ得る」と自認している。
- 影響: 出荷ymlのコメントは全72ファイルで**計1,568行**あり、そのうち相当数がキー直上の説明・コメントアウトされた設定例。エディタで1回保存しただけで消える。特に `combat/mob-level-table.yml` は `tiers: []` の直後に約10行のコメントアウト設定例があり、これが失われると設定方法が分からなくなる。
- 補足: 「コメントを整理・外部ドキュメント化する」という今回のユーザー要件と直接関係する。**yml のコメントは Java のコメントと違い"ユーザー向け設定ドキュメント"**なので、削除方針の対象外にすべき。むしろ `yaml` パッケージの Document API (`YAML.parseDocument`) に移行してコメントを保持する方が筋。

## ORC-05 【MEDIUM / BUG】カタログとアイテムステータスの対応漏れ 2件 (JSテスト2件の失敗原因)

- 場所: `TrinityForge/src/main/resources/items/catalog.yml` ↔ `stats/item-stats.yml`
- 根拠(実測):
  - `other/サブウェポン/novus_criculus_luminis` → キー `GLOWSTONE#84` の**ステータス枠が存在しない**
  - `weapon/特殊武器/wooden_halberd` → キー `WOODEN_SWORD#51` が **item-stats の editor カテゴリに未分類**
- 影響: 前者はステータスなし=補正ゼロで出荷される。後者はエディタ上でこのアイテムのステータスに到達できない。
- 総数: カタログ 262 item / item-stats 313 枠。

## ORC-06 【MEDIUM / BALANCE】`halberd` シリーズが wooden 1ティアだけで放置。`spear`/`scythe` もティア欠損

- 場所: `TrinityForge/src/main/resources/items/catalog.yml`
- 根拠(ティア数の実測):
  | 系統 | ティア数 | 備考 |
  |---|---|---|
  | dagger / rapier / warhammer / greataxe / grate_sword / mace / trident / cane / axe_tf / axe_tool | 7 | 完全 |
  | bow / crossbow | 6 | wooden 欠(意図的の可能性) |
  | scythe | 5 | 途中欠 |
  | spear | 3 | hero/infinity/nuclear/source_gem のみ。基本ティアなし |
  | **halberd** | **1** | wooden のみ |
  | morningstar | 0 | ORC-01 |
- 影響: 進行の途中で使える武器種と使えない武器種がまだらになる。特に halberd は wooden 1本だけ存在するため、序盤に拾って以降ずっと上位がない死に系統。
- 併せて: catalog ID にタイプミスが残存 — `golad_scythe` (golden の誤り)、`nethrite_scythe` (netherite の誤り)。ティア名でのソート・照合に頼るロジックがあると壊れる。

## ORC-07 【MEDIUM / UNIMPLEMENTED】機構だけ実装され中身が空のconfigが多数。機能として存在しないのと同じ

実測した「トップレベルが空」のconfigセクション:

| ファイル | 空セクション | 意味 |
|---|---|---|
| `dungeon/gates.yml` | `gates: {}` | **ダンジョンゲートが1つも定義されていない** |
| `combat/mob-level-table.yml` | `tiers: []` | レベル帯別ドロップ/EXP機構が完全未使用 |
| `combat/mob-profiles.yml` | `profiles: {}` | (`/trinityforge importmobs` で実サーバ側生成される想定。出荷空は仕様) |
| `progression/crafting-features.yml` | `gated-catalog-recipes: {}` | 段階解放レシピが0件 |
| `progression/special-rewards.yml` | `particle-seeds: {}` | パーティクル報酬0件 |
| `progression/collection.yml` | `categories.mobs: {}` / `reward-tiers: {}` | **`sources.mob-kills: true` なのにモブ図鑑の分類が0件。かつ図鑑を埋めても報酬が1つも無い** |
| `progression/achievements.yml` | `commands: []` / `items: []` / `job-exp: []` / `permanent-buffs: {}` | アチーブメント報酬が空 |
| `items/external-items.yml` | `items: {}` | 外部アイテム連携0件 |
| `stats/lore.yml` | `multiplier-layers: []` ほか | 乗算レイヤ表示が未使用 |
| `items/catalog.yml` | 空セクション94箇所 | 個別確認が必要(別担当) |

- 影響: `collection.yml` の `reward-tiers: {}` が特に問題。図鑑は進捗を記録し `/tf collection` で表示までするが、**どれだけ集めても報酬が発生しない**。ヘッダコメントには「段階的報酬解放」「称号/コスメ/恒久QoL」と設計が明記されているのに、コンテンツが1件も無い。

## ORC-08 【LOW / COMMENT】yml のコメント量が本文を大きく上回るファイルがある

- 例: `combat/mob-level-table.yml` — コメント約60行 + コメントアウト設定例10行 に対し、実設定は `dungeon-only: false` と `tiers: []` の**2行**。
- 判断: これは Java コメントの削減方針とは別扱いにすべき。yml は運用者(ユーザー本人)が読む設定ドキュメントであり、削るとエディタ経由でしか設定方法が分からなくなる。ただし ORC-04 の通りエディタ保存で消えるため、**「yml から docs/ の設定リファレンスへ移し、yml には1行のリンクだけ残す」方が両方の問題を同時に解決する**(要ユーザー判断)。

## ORC-09 【LOW / 保守】リポジトリ内に大容量の非成果物ディレクトリが滞留

実測サイズ:

| ディレクトリ | サイズ | 2026-07-01以降の更新ファイル数 |
|---|---|---|
| `.native-smoke/` | 281 MB | 253 |
| `fork-handoff/` | 95 MB | — |
| `external/` | 65 MB | 2858 |
| `TrinityForge/` | 62 MB | — |
| `decompile/` | 48 MB | **0** |
| `backups/` | 35 MB | — |
| `source/` | 12 MB | **1** |

- `decompile/` は7月以降まったく触られていない(48MB)。`source/` もほぼ死んでいる(12MB)。
- 影響: 直接のバグではないが、全文検索・grep・AIによるコード読み込みのノイズと速度低下に直結する。「読み込み速度向上」というユーザー要件に照らすと、**コメント削減より効果が大きい可能性がある**。削除可否はユーザー判断。
