# 完了報告 — mob-overrides UI再編 / 日本語表示名 / ダンジョンID選択 / レベルテーブルのモブ指定

> **⚠️ 2026-07-27: この文書は残タスクの一次情報ではありません。**
> 残タスク・既知の問題・作業履歴は `reports/ACTIVE_RECORD.md` に集約しました。
> 本書はその日の作業記録として残していますが、記載されている「残タスク」「保留リスト」は
> 既に解決済みのものを多く含みます（棚卸しで15件が実装済みと判明）。参照しないでください。


作成: 2026-07-26 17:00
対象: ユーザー指示の4件バッチ（モブオーバーライドの折りたたみ+ID固定+日本語名、ダンジョンゲートのID選択、
mob-profiles 要否の判断、レベルテーブルのUI崩れ+対象モブ指定）
前回レビュー: `reports/20260726_1000_StatReclassEditorRestructure.md`（本書の§3はそれ以降の差分のみを対象）

---

## 0. このセッションの終了条件

`/goal` の終了条件は「投げたタスクを完走し、前回レビューからの差分のみをレビューして、
保留リストと残留タスクをまとめた .md を作成する」。
実装・テスト・ブラウザ検証まで完了し、残るのは**配備（権限ゲートで私からは実行不可）**のみ。

---

## 1. 検証結果（自分で実行して確認した数字）

| 対象 | 結果 |
|---|---|
| TrinityForge Java テスト | **2233 tests / failures 0 / errors 0 / skipped 2** |
| config-editor テスト | **543 tests / 541 pass / 2 fail / skipped 0** |
| ブラウザ実機検証 | config-editor を起動して4画面すべて操作確認（§2 の各項目に実測値を記載） |

- Java の skipped 2 は正規のもの（`OfflineMobImportRunner` / `NativeProgressionStabilizationContractsTest`）。
  MockBukkit の `UnimplementedOperationException` が SKIPPED として報告される罠に対し、
  「正規スキップ2件から増えていない＝隠れた失敗なし」という前回同様の判定基準を適用して確認済み。
- Java テスト数は前回報告の 274 クラスから増えているが、これは `MobLevelTableConfigTest` に
  今回 6 件追加したぶんと、`cleanTest` で全クラスを再実行したことによる集計方法の違い。
- **config-editor の失敗2件は今回の変更と無関係の既存stale**（詳細は §4-1）。
  セッション開始時に実行した時点から件数・内容とも不変。

---

## 2. 完了した作業

### 2-1. モブオーバーライドのカード折りたたみ

`buildOverrideScopeCard` / `buildOverrideMobCard` を `window.collapsibleCard` 化した。
396体を一度に全展開する従来の描画では実用にならなかったため、**既定は全て折りたたみ**。
開閉状態は `openOverrideScopes` / `openOverrideMobs`（モジュールスコープの Set）で再描画をまたいで保持する。

ヘッダは折りたたんだまま識別できるよう要約を出す。

```
▶ | 鉱山 | ワールド: | em_id_the_mines | モブ 38体 | スコープごと削除
▶ | 蜘蛛型構造体 | モブID: | the_mines_arachnidian_construct | 削除
```

実測: スコープカード 30枚（default + 29ダンジョン）、初期状態で 30枚とも折りたたみ。

### 2-2. 既定 EliteMobs ダンジョンの ID 固定

台帳 `tools/config-editor/public/data/elitemobs-dungeons.json`（新規）に載っている
**29ダンジョンのワールド名と396モブのidを編集不可**（`<code class="entry-key-fixed">` 表示）にした。
実測: 固定表示 425箇所 = 29 + 396。

固定する理由をUIにも明記した。

- ワールド名は「設計図ワールド名」と一致していないと全インスタンスに当たらない
- モブidは EliteMobs のカスタムボス設定ファイル名と一致していないと無反応になる

自分で追加したスコープ／モブは従来どおり編集可能。
既定ダンジョンには「台帳から選んで追加…」セレクトを付け、未登録モブを手打ちせず選べるようにした。

### 2-3. ダンジョン名・モブ表示名（`display-name`）を日本語で全投入

`combat/mob-overrides.yml` に `display-name` キーを新設し、**29ダンジョン + 396モブ = 425件すべてに
日本語名を投入**した。GUI/簡易モードの両方から編集できる。

- 訳表: `tools/scripts/em_ja_names.py`（DUNGEON_JA 61 / MOB_JA 242 / SUFFIX_JA 19 / DISAMBIGUATION_JA 36）
- 生成: `tools/scripts/gen-mob-overrides.py`（冪等。実サーバの `plugins/EliteMobs` を読んで再生成する）
- 同一ダンジョン内で名前が衝突したグループにだけ識別子を付ける
  （例: 「闘技場のゾンビ (第11波)」「スパーキー (第2段階)」）

**表示専用**であり、戦闘計算にもスコープ解決にも一切影響しない。Java 側は
`MobOverridesConfig#dungeonDisplayName` / `#mobDisplayName` として読めるようにしてあるが、
現時点で本体の消費者はいない（§4-2 参照）。

### 2-4. ダンジョンゲートのダンジョンID選択

`dungeon/gates.yml` の行き先ワールド名欄を `listSelect` 化し、61件の既定ダンジョンを
「日本語名 (ワールド名)」で候補表示するようにした。**手動入力も併用可**（`allowCustom`）。
ついでに `content-package` 欄も同じ方式にした（候補61件）。

実測: 候補61件、「深層鉱山」を選択 → キーが `em_id_the_deep_mines` にリネームされることを確認。

### 2-5. mob-profiles.yml の要否 → **残すべき**

調査結論。実サーバの409エントリは全て `dungeon-theme: ''`、うち276体が `level: dynamic`。
`unknown-mobs.synthesize: true`（既定）とフォークが渡す実レベルにより、**削除しても実行時の挙動は
ほぼ変わらない**。ただし、

- 固定レベルの約133体は「import 時に焼いた値」→「現在のランプ値」に意味が変わる（stale が消えるので実質は改善）
- **テーマ層とハンド例外の唯一の受け皿**がここなので、完全削除は非推奨

日常の調整で触る必要はもう無い（mob-overrides で足りる）、という位置づけが正確。

### 2-6. レベルテーブル — UI崩れ修正 と 対象モブ指定

**崩れの真因**: `.mob-drop-row` は `display:flex` の横並び行なのに、その直下に素材グリッド
（`.field-grid`）と対象モブブロック（`.mob-drops-section`）を並べていたため、両者が左右で潰し合っていた。
CSS 側にも `> .field-grid` と `> .btn-small` のルールしか無かった。
縦積み用の `.mob-drop-body` を1枚挟み、削除ボタンだけを行末に残す形へ修正。

実測（1280px幅）: 素材グリッド y=842 / 対象モブ y=940 と上下に分離、両者とも幅842px、
削除ボタンは x=1189 の行末、横スクロールなし。

**モブ指定**: 既存の `mobs:` が EntityType だけだったのが根本原因。
**ダンジョンは1つ丸ごと同じ EntityType**（見た目替えの `ZOMBIE` 等）なので個体を狙えなかった。

新軸 `mob-ids:` を追加し、`MobTargetFilter`（`entityTypes` × `mobIds`、**各軸AND・空軸は無制約**）として
`LevelTierRule`（帯そのもの）と `LevelTierDropEntry`（add-drops 1件）の両方に持たせた。

| 置き場所 | 効果 |
|---|---|
| 帯（tier） | その帯の `remove-drops` / `add-drops` / `vanilla-exp` が丸ごと対象外モブに当たらなくなる |
| `add-drops` の1エントリ | そのドロップ1件だけが絞り込まれる |

id は `MobIdNormalizer` を通すので `.yml` 付き / 裸のどちらの表記でも一致する。
エディタ側は396モブを日本語名で選べるセレクト（手入力可）。実測: 候補396件、
「蜘蛛型構造体 (鉱山 / the_mines_arachnidian_construct)」を選択して反映を確認。

---

## 3. 差分レビュー（前回レビュー 07-26 10:00 以降のぶんのみ）

### 3-1. レビュー対象

| 種別 | ファイル |
|---|---|
| Java 新規 | `mobs/MobTargetFilter.java`, `mobs/MobIdNormalizer.java` |
| Java 変更 | `mobs/LevelTierRule.java`, `mobs/LevelTierDropEntry.java`, `mobs/MobOverrideEntry.java`, `config/domains/MobLevelTableConfig.java`, `config/domains/MobOverridesConfig.java`, `listeners/MobLevelTableListener.java`, `listeners/MobOverrideExpListener.java`(新規), `TrinityForge.java` |
| config | `combat/mob-overrides.yml`（再生成）, `combat/mob-level-table.yml`（ヘッダ解説） |
| JS 新規 | `public/js/em-dungeons.js` |
| JS 変更 | `public/js/mob-forms.js`, `public/js/tf-dungeon-forms.js`, `public/style.css`, `public/index.html`, `lib/schema.js`, `server.js` |
| ツール | `tools/scripts/em_ja_names.py`(新規), `tools/scripts/gen-mob-overrides.py` |
| ドキュメント | `docs/config-reference/combat/mob-overrides.md`, `.../mob-level-table.md` |
| テスト | `MobLevelTableConfigTest`(+6), `mob-forms-logic.test.js`(+3), `mob-level-table-schema.test.js`(+4), `mob-overrides-schema.test.js`(+3) |

### 3-2. レビューで見つけて直したもの

1. **生成器のパッチ不適用（機能欠落）** — `display-name` 出力と台帳への追記を挿入するパッチが実際には
   当たっておらず、`mob-overrides.yml` に `display-name` が1行も出ず、台帳のモブが0件だった。
   アンカー文字列を修正して再生成し、425件 / 396モブが載ることを実データで検証した。
2. **null モブエントリでフォーム全体が落ちる** — 手書きで `mob_id:`（値なし）と書かれた
   エントリは YAML 上 null になり、`display-name` 欄以降のブロック構築が軒並み null 参照で
   落ちてフォームが描けなくなる。`renderMobs` で `{}` に均すガードを追加した。
   （厳密には従来の `buildOverrideStatsBlock` でも落ちる既存の穴だが、3000行の生成物を人が
   手編集する前提になった以上、現実的なリスクなので塞いだ）
3. **ゲート見出しの日本語名が二重表示** — `listSelect` 自身が「日本語名 (ワールド名)」で描くのに
   追加ラベルも出していた。追加ラベルを削除した。
4. **yml コメントに英単語が混入** — 「一切influenceしない」→「一切影響しない」。
5. **`server.js` が `EDITOR_PORT` しか見ていなかった** — `.claude/launch.json` 経由の
   プレビュー起動ができなかったので `PORT` も見るようにした（既存の優先順位は不変）。

### 3-3. 検証して「問題なし」と確認したもの

- **`display-name` だけのエントリがドロップを消さないか** → 消さない。`dropsFor` は
  「非空の drops リスト」を上書き信号にしているため、`display-name` だけのエントリは
  default へフォールスルーして最終的に無効果になる。セレクトから追加したモブが
  意図せずドロップを全消しする事故は起きない。
- **未タグ付けモブでの NPE** → `MobLevelTableListener` は `mobData.hasProfile()` ゲートを
  通ってから `profileId()`（Optional）を読むので安全。フィールドモブは `profileId = null` となり、
  `mob-ids` 指定のあるフィルタには（idが無いので）決して一致しない＝仕様どおり。
- **`display-name` キーがモブ解析を壊さないか** → 壊さない。スコープ解析は `mobs:` セクションのみを
  走査し、`display-name` は `getString` で個別に読むだけ。未知キー扱いでの skip 計上も起きない。
- **EliteMobs フォークの再ビルド要否** → 不要。API jar（10クラス）に `com.trinityforge.mobs` は
  含まれておらず、フォーク側に `MobOverrideEntry` / `MobTargetFilter` / `LevelTierRule` の
  参照は1件も無い。
- **後方互換** → `mobs:` / `mob-ids:` とも省略時は `MobTargetFilter.EMPTY` で「全モブに適用」。
  既存の `LevelTierRule` / `LevelTierDropEntry` には3引数・`Set<EntityType>` 版のコンストラクタを
  残してあり、既存テスト23件はそのまま緑。

### 3-4. 残る弱点（直さなかったもの・軽微）

- `pruneEmptyAddDropsMobs` は帯レベルの `mobs` / `mob-ids` も刈るようになったが、
  関数名が `AddDrops` のままで実態と合っていない。テストと呼び出し側から参照されているため
  改名は見送り、コメントで補った。
- スコープをリネームすると `openOverrideScopes` に旧名が残る（次回描画で無視されるだけの
  見た目上の問題。折りたたみ状態が1回リセットされる）。
- `em-dungeons.js` の fetch URL は相対パス。エディタをサブパス配下で配信し始めたら壊れる。
- `MobOverridesConfig#dungeonDisplayName` / `#mobDisplayName` に本体の消費者がいない（§4-2）。

---

## 4. 保留リスト（ユーザー判断待ち・着手前に確認が要るもの）

### 4-1. config-editor の失敗テスト2件（今回の変更とは無関係）

`test/item-stat-coverage.test.js`。

1. `weapon/特殊武器/wooden_halberd (WOODEN_SWORD#51) の分類` — `catalog.yml` 側にはあるのに
   `item-stats.yml` の `_editor.categories` / `_editor.itemTabs` に無い。
2. `GLOWSTONE#84 がEditorカテゴリ未割当` — `item-stats.yml` の items にあるのに
   `_editor.itemTabs` に登録されておらず、**エディタのアイテムステータス画面に一切表示されない**。

どちらに分類すべきかはゲームデザインの判断なので独断で決めない。作業タスクとしてチップを1件出してある。

### 4-2. `display-name` の Java 側消費者をどうするか

現状は editor 専用メタデータで、Java からは読めるが誰も使っていない。
「ダメージ表示・ログ・ボスバーの名前をこの `display-name` に寄せる」なら実装が要る。
不要なら `MobOverridesConfig` の2アクセサと `MobOverrideEntry.displayName` は将来の
デッドコードになる（現時点では yml の未知キー化を避ける意味でも解析自体は残す価値がある）。

### 4-3. 前回報告書から未解決のまま持ち越しているもの

`reports/20260726_1000_StatReclassEditorRestructure.md` §5 の6件は**いずれも未着手のまま**。

| # | 内容 |
|---|---|
| 5-1 | スキルツリー草案16ツリーの yml 適用可否（承認待ち） |
| 5-2 | 生産草案の未決4件（伐採C-2の `mining-fortune: 45`、鍛冶「品質+1」未配線、buffs空の穴埋め、エンチャント「消費経験値-10%」の読み替え） |
| 5-3 | 重量武器D帯「スタン時間」— `stun-duration` 相当のステキーが無い |
| 5-4 | `skills/base/*_progression.yml` 16ファイルに残る ValhallaMMO 時代の死んだデータ |
| 5-5 | 消費者ゼロのステキー `tree_fell_cooldown_reduction` の削除可否 |
| 5-6 | ~~`lore.yml` に表示定義が無いステ30件~~ → **解消済み**（§7-3） |

### 4-4. さらに以前から持ち越している未決事項

| 出典 | 内容 |
|---|---|
| 07-26 監査クローズ | モブHP上限1024 / 触媒のオフハンド / `ingredient_save_chance` のバニラ醸造への拡張 |
| 07-24 モブ個体差 | ~~**player→elite の二重ダメージ**~~ → **既に修正済み**（§7-2）。この行自体が stale だった |
| 07-24 モブ個体差 | 次期方針4点（ダンジョン作成→TF化手順 / overworld 豊穣化 / editor UX / レベル報酬の討伐EXP通貨一本化）が未決定 |
| 07-25 スキルツリー47ノード | 経済系2件の実装保留 |

---

## 5. 残留タスク（判断不要・手を動かせば終わるもの）

| # | 内容 | 状態 |
|---|---|---|
| R-1 | **本セッション成果物の配備**（§6） | §6 の2件+jar は**配備済み**。§7 の追加ぶんは jar 再配備が必要 |
| R-2 | 採取系見直し（gather-rework）の実装 W1 基盤から | **W1 は既に実装・配線・テスト済みだった**（§7-4）。残るは W2/W3 |
| R-3 | 07-24 レビューの残 MEDIUM 4件（複数スキルバー上書き / StatsCategory / fork 短絡 / fork テスト） | **完了**（§7-1） |
| R-4 | `item-stat-coverage` の2件（§4-1）— 分類先が決まれば作業自体は数分 | **完了**（§7-1） |

---

## 6. 配備（ユーザー操作が必要）

配備先への書き込みは権限ゲートで私からは実行できない（試行して拒否された）。

**本セッションの成果物**

| 成果物 | 時刻 |
|---|---|
| `TrinityForge/build/libs/TrinityForge-0.1.0-SNAPSHOT-all.jar` | 07-26 16:51 |
| `combat/mob-overrides.yml`（display-name 425件入り） | 07-26 16:44 |
| `combat/mob-level-table.yml`（`mob-ids` 解説入り） | 07-26 16:22 |

jar には `MobTargetFilter` / `MobIdNormalizer` のクラスと、`display-name` 425件入りの
`mob-overrides.yml`・`mob-ids` 解説入りの `mob-level-table.yml` が同梱されていることを
zip の中身で確認済み。

```bash
cp TrinityForge/build/libs/TrinityForge-0.1.0-SNAPSHOT-all.jar "D:/game/minecraft/PaperServer/TrinityForge/plugins/" && cp TrinityForge/src/main/resources/combat/mob-overrides.yml TrinityForge/src/main/resources/combat/mob-level-table.yml "D:/game/minecraft/PaperServer/TrinityForge/plugins/TrinityForge/combat/"
```

**安全性の確認**: 稼働中の `mob-overrides.yml` と新ファイルは、コメントと `display-name` 行を
除いたデータ行 **2427行が完全一致**（差分ゼロ）。上書きしても調整値は失われない。

**注意**

- `combat/mob-profiles.yml` は**絶対に上書きしないこと**（`importmobs` 生成物）。
- jar が変わっているので reload では足りず、**フルの再起動**が必要。
- config-editor 側の変更（`em-dungeons.js` 追加・`index.html`・`schema.js`・`server.js`）は
  Node プロセスの再起動で読み込まれる。jar 配備とは独立。

**本セッション外の未配備差分**

実サーバと内容が違う config は現在 26件あり、うち今回のぶんは上記2件のみ。
残り24件は前回報告書の積み残しと、本セッションと並行して行われた別作業によるもの
（`combat/stat-caps.yml` は実サーバに存在しない新規ファイル）。
**それらの配備可否は私の側では判断していない**ので、配備するファイルは明示的に選んでいただきたい。

```
combat/base-stats.yml            combat/mob-profiles.yml(※上書き禁止)
combat/stat-caps.yml(新規)       progression/crafting-features.yml
skills/base/*_progression.yml (16ファイル)
skilltree/heavy_weapons.yml      stats/digging-gimmick.yml
stats/fishing-gimmick.yml        stats/lore.yml
stats/skill-exp.yml
```

---

## 7. 継続バッチ（17:00–17:35）— 残タスクの消化

「判断が必要なものは聞いていいので残る進めて」を受けて、確認した4問の回答に沿って残タスクを消化した。
**この節は §4 / §5 の一部を上書きする**（該当行には取り消し線を入れてある）。

### 7-1. 07-24 レビューの残 MEDIUM 4件（R-3）＋ item-stat-coverage（R-4）

| # | 指摘 | 対応 |
|---|---|---|
| M-1 | 複数スキル同時取得時のボスバー上書き | **対応不要**。`SkillExpFeedbackService` は既に per-skill の `PlayerBossBars`（`LinkedHashMap`）＋ `maxConcurrentBossBars()` 上限＋ `evictOldestIfAtCapacity` で解決済みだった（指摘のほうが stale） |
| M-2 | `StatsCategory` の未分類キー | `OTHER` に落ちていた**24キー**を ATTACK/ARMOR/CRAFT/GATHERING/UTILITY/ARS へ回収。加えて `StatsCategoryCoverageTest`（2件）を新設し、`StatVocabulary` との乖離が出た瞬間に落ちるようにした |
| M-3 | fork `gridHasCustomItem` の短絡 | custom-only な `list:` 素材（Material 実体を1つも持たない互換リスト）を強制ガード対象に含めるよう `RecipeManager.requiresBareCustomIngredient` / `listRequiresForcedGuard` を追加 |
| M-4 | fork 新ガードに単体テストが皆無 | `CustomIngredientCraftGuardShapeTest`（6件）新設＋`RecipeManagerReversibleTest` に4件追加（11→15件）。fork 側テストは 23→**33件**へ |
| §4-1 | `item-stat-coverage` の失敗2件 | `item-stats.yml` に `WOODEN_SWORD#51`（特殊武器カテゴリ）と `GLOWSTONE#84`（`other` タブ）を追加。JS **543/543 緑**に復帰 |

### 7-2. player→elite の二重ダメージ（§4-4）

「修正する」との回答を受けて着手したところ、**調査の結果すでに修正済み・配備済み**だった。
2026-07-25 に CMB-02 として対処されており、fork が `LOWEST` で `EliteCombatDelegation.mark()`、
`MONITOR` で `clear()` し、TF 側 `CombatListener.java:358` がマーク中は `event.getDamage()` を
そのまま採用する（＝TF側で再計算しない）形になっている。テスト6件が緑で、稼働中の
`EliteMobs.jar`（07-26 07:32）にもガード込みで入っていることをクラスのバイト列で確認した。

**私の引き継ぎメモのほうが stale だった**。§4-4 の当該行は取り消してある。

### 7-3. `display-name` の Java 側配線（§4-2）＋ lore.yml 30件（§5-6）

**display-name**: 「ダメージ表示とログに反映する」との回答どおり実装した。

- `com.trinityforge.mob.MobDisplayNames` を新設（`display-name` → `customName` → EntityType 翻訳名の順に解決）
- `FocusHpDisplay` の頭上ラベル、`MobOverrideDropListener` / `MobLevelTableListener` のドロップ生成ログが
  これを経由するようになった。ログ表記は `蜘蛛型構造体 (the_mines_arachnidian_construct)` 形式
- `MobDisplayNamesTest`（5件）新設、`FocusHpDisplayTest` はコンストラクタ変更に追随

**lore.yml**: 調査したところ **欠落は0件**だった。`StatVocabulary` の全114キーが lore.yml に
定義済みで、30件は本日 15:46 の更新で既に埋まっていた（`StatVocabulary` 側の
「lore.yml へは意図的に未登録」というコメントも同様に stale）。
実データが揃っているので追加作業は無く、代わりに再発防止として
`LoreVocabularyCoverageTest`（2件）を新設した。

> 逆方向（lore.yml にあるが語彙に無い5件 = `durability` / `item_cooldown` / `attack_speed` /
> `coating_charges` / `thread_slots`）は、アイテム固有でパークから供給しない表示専用ステなので
> 正当。テストでも意図的に検査していない。

### 7-4. 採取系見直し W1 基盤（R-2）

**既に実装・配線・テスト済みだった**ため、新規作業は無し。

- `com.trinityforge.active` に `ActiveSkillRegistry` / `CooldownManager` / `ActivationDispatcher` /
  `FeedbackLayer` / `ActiveSkill` / `ActiveSkillCooldownKeys` が揃っている
- `TrinityForge.java` で登録済み（`HasteActiveSkill` 登録、`TreeFellingListener` も同じ
  `CooldownManager` を共有）
- テストは `active/` 配下に4クラス
- 設計 Q2 の結論どおり **GUI は作られておらず**、正式トリガーはスニーク+右クリック。
  `/tf active <id>` はデバッグ専用でヘルプ／タブ補完に出さない

なお、メモにあった設計書 `docs/design/2026-07-25-gather-rework-active-framework.md` は
**実在しない**（`docs/design/` にあるのは stat-gate-overhaul と stat-scope-ux の2本のみ）。
W2（パラメータ化）・W3（UX）に進む場合は設計を書き起こすところからになる。

### 7-5. テストとビルド

| 対象 | 結果 |
|---|---|
| TrinityForge (Java) | **2259 tests / failures 0 / skipped 2**（基準 2233・skipped 2 から増分のみ、回帰なし） |
| ArsPaper fork (Java) | **33 tests / failures 0 / skipped 0** |
| config-editor (JS) | **543 / 543 緑** |

| 成果物 | 時刻 |
|---|---|
| `TrinityForge/build/libs/TrinityForge-0.1.0-SNAPSHOT-all.jar` | 07-26 **17:32** |
| `fork-handoff/arspaper/fork/build/libs/ArsPaper-1.0.0.jar` | 07-26 **17:33** |

### 7-6. 追加配備（ユーザー操作）

§6 で配備した jar は 16:51 版なので、§7 の変更（StatsCategory / MobDisplayNames /
item-stats.yml / fork ガード）を反映するには**両 jar の再配備とフル再起動**が要る。

```bash
cp TrinityForge/build/libs/TrinityForge-0.1.0-SNAPSHOT-all.jar "D:/game/minecraft/PaperServer/TrinityForge/plugins/" && cp fork-handoff/arspaper/fork/build/libs/ArsPaper-1.0.0.jar "D:/game/minecraft/PaperServer/TrinityForge/plugins/"
```

`item-stats.yml` はエディタ用メタデータ（`_editor`）の追加だけなので jar 同梱ぶんで足りるが、
実サーバ側の `stats/item-stats.yml` を個別に上書きしている運用なら、そちらも合わせて更新が要る。

### 7-7. この節を終えて残っているもの

判断待ち（私の側では決められないもの）:

- §5-1〜5-5（スキルツリー草案16本の適用可否ほか5件）
- §4-4 の残り（モブHP上限1024 / 触媒のオフハンド / `ingredient_save_chance` のバニラ醸造拡張 /
  次期方針4点 / 経済系2件）

手を動かせば終わるもの:

- 採取系見直しの **W2（パラメータ化）/ W3（UX）** — ただし設計書が実在しないので設計から
- 本セッション外の未配備 config 24件の取捨（§6 末尾）

---

## 8. 追加バッチ（22:30–23:10）— オーバーワールドEXP / TT対策 / 全モブ定義

ユーザー指示3件。着手前に確認した4問の回答（EXPは両系統・低レート / TT対策は同一地点の逓減 /
動物は「1+3」＝敵対と反撃中立はフル設計・友好はバニラEXPのみ）に沿って実装した。

### 8-1. オーバーワールドEXP開放

`outside-dungeon-exp-rate: 0.25` を新設し `dungeon-only-exp` を `false` へ。
合成規則は `SkillExpConfig.worldExpRate(boolean)` の1箇所に閉じた。
呼び出し側で「フラグを見てから倍率も見る」と書くと、片方を忘れた経路が静かに全額付与になり
エラーも出ないため、意図的に分岐を持たせていない。

**レビューで見つけた既存バグ**: ymlは当初から「ARS_MAGIC もゲート対象」と書いていたのに、
実装(`ArsProgressionBridge.grantMagicExp`)はワールドを見ておらず魔法だけ素通りしていた。
オーバーワールドを0.25倍に絞った以上、放置すると魔法だけ4倍速で伸びるため実装側を修正。

### 8-2. TT・放置対策（同一地点の逓減）

`LocationExpDiminishing` 新設。半径24ブロック/5分窓/しきい値30体/1体-5%/下限10%/ダンジョン除外。

**自己レビューで自分の設計バグを1件発見・修正**: 当初 `multiplierAt` が呼ばれるたびに1件記録する
実装だったため、被弾トリガの防具EXPと命中トリガの武器EXPでもカウンタが増えていた。
5体を相手に各6発もらえば同一地点30件に達し、**正常なプレイでEXPが減り始める**誤爆になる。
カウンタを増やすのは撃破のみ(`recordKill`)、hit系は読むだけ、に分離した。
同一死亡イベントに HIGH と MONITOR の2リスナーが反応するための二重記録は、直前Spotの
victim UUID 照合で防いでいる。

倍率自体は武器/防具/バニラEXPの3経路すべてが読む（経路ごとに別勘定にすると混ぜるだけで回避できる）。

**既知の穴**: カウンタはバニラEXP書き込み経路で回るため、レベル帯の対象外である友好モブ(C群)の
養殖場は数えない。TTの本命ではないので現状は許容している。

### 8-3. 1.21.11 全モブ定義（13体 → 89体）

分類は **Paper の `Enemy` マーカー**を一次情報にした。Bukkit の `Monster`/`Animals`
インターフェースは使えない（HOGLIN は `Animals` 判定なのに実際は敵対）。

| 群 | 数 | 扱い |
|---|---|---|
| A 敵対 | 41 | HP/攻撃/防御/係数までフル設計 |
| B 反撃中立 | 9 | 同上（鉄ゴーレム/狼/蜂/シロクマ/パンダ/ラマ×2/ヤギ/イルカ） |
| C 友好・受動 | 39 | レベル刻印のみ。HPと与ダメージはバニラ据え置き |

`MANNEQUIN` は除外。1.21.11 の生存EntityTypeで唯一 `Mob` を実装しない
（`ArmorStand` と同じ置物側）ため、戦闘モブ表に入れるとレベル刻印が飾りに乗る。

`mob-level-table.yml` が `tiers: []` で空だったので、6帯（Lv0→8EXP … Lv85+→140EXP）を
**A/B群50種限定のフィルタ付き**で追加した。C群を含めないのが肝で、含めると牛がゾンビと
同じEXPを落として「友好モブはバニラEXPのみ」が壊れる。
EXPの伸びをHP(growth 1.055)より緩くしてあるのは、同率にすると所要時間が一定なのに報酬だけ
跳ね上がって進行が壊れるため。

生成器: `tools/scripts/gen-mob-types.py`（mob-types と level-table を同時生成・冪等）。

### 8-4. エディタ: mob-types のカード折りたたみ

`collapsibleCard` へ移行し、要約バッジ（HP / 攻撃 / ドロップ件数、友好モブは「HP: バニラ据え置き」）を
ヘッダに出した。実測（1280×900）: 折りたたみ時50px/枚・全体6874px、展開時1226px。
折りたたまなければ約11万pxになる計算で、90体では実質使えなかった。

### 8-5. 死にデータ除去（前回 §5-4）

`skills/base/*_progression.yml` から ValhallaMMO 由来の未使用キー **66件 / 15ファイル**を除去。
`SkillCatalogEntry.rate()` の呼び出しは全て文字列リテラル（変数キー0件）なので、
literal grep 0件 = 到達不能と言い切れることを確認した上で削除している。
`prestige_decay_rate` は consumers=2 で生きているため対象外（名前が似ているので注意）。
スクリプト: `tools/scripts/strip-dead-progression-keys.py`（冪等）。

### 8-6. 検証

| 対象 | 結果 |
|---|---|
| TrinityForge (Java) | **2292 tests / failures 0 / skipped 2** |
| config-editor (JS) | **584 / 584 緑** |
| エディタ実機 | 89枚の折りたたみ・開閉・要約表示を DOM 実測で確認（横スクロールなし） |

成果物: `TrinityForge-0.1.0-SNAPSHOT-all.jar` **23:07**（89体の mob-types と新キー2種が
同梱されていることを zip 内で確認済み）。

### 8-7. 注意

本セッションと**並行して別のセッションが同じリポジトリを編集していた**（`mob-forms.js`、
`*_progression.yml`、`SpellBreakGuard` ほか）。git 管理外のため復旧手段が無く、
競合ファイルは相手の編集が止まるまで待ってから着手している。
上記のテスト数には相手の変更も含まれる。
