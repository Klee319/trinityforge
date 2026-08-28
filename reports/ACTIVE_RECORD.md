# TrinityForge — 現役記録（残タスク / 既知の問題 / 現在の状態）

**この文書が唯一の現役記録です。** 残タスク・バグ・現在の状態はここだけを見て、ここに追記する。

- **過去の作業記録は `reports/ACTIVE_RECORD_ARCHIVE.md`**（解決根拠と失敗の型を辿る用。2026-08-28 に旧本文 ~3990 行を §Z へ退避）。
- `docs/` 直下の `*_SPEC.md` は仕様、`docs/config-reference/` は config キーの一次情報、
  `docs/agent-context/` は「触る前に知らないと黙って壊す知識」。`docs/archive/` は**失効した設計文書**。
- **新しい日付入りレポートを増やさない。**
- **本文は約 200 行が上限。** 超えたら閉じた項目をアーカイブへ移す（消さない）。

## 運用ルール

- 項目を閉じるときは行を消さず、`~~取り消し線~~ → 解決日と根拠` にする。
- 作業記録は §7 に**上へ**追記する。長くなったら古い順にアーカイブへ移す。
- **着手前に必ず実コードで裏を取る。**
- **「機構は実装済みで config に値が入っていないだけ」は残タスクにしない。**
- **直したと書く前に「修正前に戻すと落ちるテスト」を示す。**

---

## 1. 現在の状態（要約）

| 対象 | 状態 |
|---|---|
| ブランチ | 作業は `dev` へ。`main` はユーザー明示時のみ |
| 実サーバ | **稼働中に jar を差し替えると必ず `NoClassDefFoundError`**。配備は停止後 |
| テスト | MockBukkit 未実装 API は SKIPPED 化する。**skip が増えたら事故と疑う** |
| CMD 台帳 | **現行配線台帳。欠番は再利用してよい**（`reconcileWithUsage`）。既存アイテムの番号振り直しは禁止 |
| combat level | `pillars` の max-of-top-N / divisor。防具も算入 |
| 魔法ダメ | 出荷 `magical.scale-with-combat-level: true` |
| 守備力 flat | typed（`phys-flat-defense` / `magic-flat-defense`） |
| PvP | 出荷は抑制 ON + `damage-multiplier: 0`（対人ダメージ無し）。`enabled: false` は即死PvPになるので触らない |
| ロール | 明示選択が正 |
| ペット | 現状維持（レベル刻印のみ）。連携は後回し |
| ネザライト化 | **実装済み**（`CatalogRecipeRegistrar#registerNetheriteOne`）。K-23 は stale |

---

## 2. 残タスク — 判断待ち

閉じた J 項目はアーカイブ §Z。新規の判断待ちはここに1行で足す。

（なし）

---

## 3. 残タスク — 手を動かせば終わるもの

| # | 内容 | メモ |
|---|---|---|
| **W-65** | PixelRank の `server-name` が Main と Resource で重複 | `D:/game` 配下。**適用はユーザー** |
| **W-67** | デスチェストを AxGraves へ差し替え | 計画は `ops/launch/swap-deathchest.cmd`。**適用はユーザー** |
| **W-69** | 圧縮アイテムにエンチャントオーラ | glint。バニラ本と混同しない／Geyser 確認 |
| **W-70** | スレッドのバニラマテリアルを壺の欠片か鍛冶型へ寄せる | CMD が違えば同じ Material でよい。`catalog.yml` と `item-stats.yml` の `MATERIAL#CMD` を両方動かす |
| ~~**W-68**~~ | ~~ガチャの空スレッドがスタックしない~~ | **2026-08-28**: `EmptyThreadStackNormalizer` が TF 個体 PDC を剥がし、`stampIfEligible` は空スレッドをスキップ。回帰 `EmptyThreadStackNormalizerTest` |
| ~~**W-21**~~ | ~~`generate-item-stats.js` が item-stats を上書き~~ | **2026-08-28**: 旧パスは常に exit 1。本体は `scripts/DEPRECATED/` |

コード修正済み・**配備待ち**の項目（W-64/66/71–80 等）はアーカイブ §Z。jar/config 配備はユーザー。

---

## 4. 既知の問題（開いているものだけ）

| # | 内容 |
|---|---|
| **K-14** | リソースパック配線未了（PNG はあるが items/model json が無い／別アイテム化） |
| **K-15** | ガチャ券 6 種のテクスチャが無い |
| **K-17** | ロール限定コンテンツが乏しい（`use-role` 装備は入ったが drops ゲートは無い） |
| **K-18** | 厳選に「育てる」側が無い（強化レベル・ロック・一括分解） |
| **K-22** | 束縛者目標の parent が `delve_all_seals` のまま（設計判断） |
| **K-25** | 儀式の素材別 EXP が Ars `custom_item_id` を読まない経路の残骸確認 |
| **K-26** | 召喚馬インベントリ保護が無い |
| **K-27** | ArmorStand/Mannequin の EXP 除外が死亡経路に無い |
| **K-28** | レシピブラウザに品質・5分類ソートが無い |
| **K-29** | 束縛者 HP が Spigot 既定 `maxHealth.max`(1024) を超える（新設サーバ注意） |
| **K-30** | GOLD 帯で重武器が弱い（構造的） |
| **K-31** | ドリリングでツルハシモーション消失 = 実機のみ |
| **K-32** | editor「素材タブへ移動」が `external-source` を引き継がない |
| **K-33** | フォーク `ThreadGui#isEffectThread` にテストが無い |
| **K-34** | editor フォーム欄内の横あふれ |
| **K-35** | モブ config は `PercentStatNormalize.coerce` を通らない |
| **K-36** | `ScribingTableGui.getUnlockedGlyphs()` だけ JSON パースが裸 |
| **K-38** | EliteMobs `build.gradle` が廃止済み PlaceholderAPI リポジトリを指す |
| ~~**K-23**~~ | ~~NETHERITE が Bukkit 未登録~~ | **実装済み**（`registerNetheriteOne`）。`isBukkitCrafting()` が netherite で false なのはスミス別経路 |
| ~~**K-39**~~ | ~~カタログ品が弾かれたとき理由が出ない~~ | 作業台は既存。**2026-08-28** 金床/砥石/鍛冶台の Prepare にもアクションバー |
| ~~**K-3**~~ | ~~yml コメントが editor 保存で消える~~ | **2026-08-16** `yaml-merge.js` で解消済み。記録が stale だった |
| ~~**K-12 / K-19 / K-20 / K-37**~~ | 解決済み | アーカイブ §Z |

---

## 5. 機構は実装済み・値だけの項目

ここに上げない。コードと出荷 yml を見ること。

---

## 6. 触るな（並行セッション / choke）

- `tools/rcon_cmd.py` / `tools/scripts/em_ja_names.py` は他人の CRLF 差分。巻き込まない。
- `reports/ACTIVE_RECORD.md` / `TrinityForge.java` の配線 / editor `constants.js` 2本は **1波に1人**。

---

## 7. 作業記録（新しい順）

| 日付 | 内容 |
|---|---|
| 2026-08-28 | **網羅レビュー判断 18 件をコードへ反映。** (1–3,5,18) 仕様をコードに追従（combat level pillars、魔法スケール、typed flat、明示ロール、Valhalla 失効）。(4) PvP は `pvp.enabled` を切らず **倍率 0**（切ると即死PvPになる）。(7) CMD 欠番再利用を規約化。(8) generator 隔離。(9) ネザライト化は既実装 → K-23 閉じ。(10) 金床/砥石/鍛冶台のゲート失敗メッセージ。(11) 空スレッド PDC 剥がし。(12) use-requirements Java 既定 `enforce: true`。(13) 金床 combine の `setItemOnCursor` 削除。回帰 `CatalogAnvilListenerDupeTest`（カーソルに触ると落ちる）。(14) melee-charge SchemaField を出荷 0.1/1.6 へ。(15) 死フォーム `buildThreadSetsForm` / `special-effects` 検証 / `NativePerkRewardResolver` 削除。(16) materialHint 二重表示。(17) 本ファイルを約200行に圧縮＋上限を規約化 |
| （以前） | アーカイブ §Z / §B |

---

## 8. 失敗の型（短く）

1. 記録が腐っていた（「未実装」と書いて実装済み）。**実コードで裏を取る。**
2. 稼働中 jar 差し替え → `NoClassDefFoundError`。停止後に配備。
3. MockBukkit SKIPPED 化。skip 数を見る。
4. `git add -A` で他人の WIP を巻き込む。触ったパスだけ add。
