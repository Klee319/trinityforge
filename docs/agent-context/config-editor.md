# config-editor（GUI設定エディタ）の恒久知識

`tools/config-editor`（Node.js 製、サーバ運営者が TrinityForge / ArsPaper の yml を GUI で編集するためのツール）を
触るエージェント向けに、今後も踏みうる落とし穴と不変条件をまとめる。作業履歴ではなく恒久的な設計・運用ルールのみ。

## データの真源と保存の仕組み

### ⚠️ yml 本文コメントは保存で消える
`lib/yamlio.js` の保存処理は**先頭ヘッダのコメントしか復元しない**。本文中の説明コメント（運用者向けの
設定ドキュメントとして書かれているもの）は、config-editor 経由で1回保存しただけで消える。
- 新しく yml にコメントを足すときは、それが editor 経由で保存され得るファイルなら、
  **コメントの内容を `docs/` 側（例: `docs/config-reference/`）にも移設し、yml 側には1行だけリンクや
  短い参照コメントを残す**方式にする。真源をコメント本文に置くと、次の保存で無言消滅する。
- 同じ理由で、「この yml のキー一覧」を検証するテストの入力を yml 内コメントに依存させない。
  キー一覧の参照先は `docs/` 配下の md にする。

### 生成物ファイルは手編集しない
`public/data/*.json` のようなビルド生成物（例: EliteMobs ダンジョン台帳）は、生成スクリプトの出力であり
手編集の対象ではない。手で直しても次回生成で上書きされ、生成スクリプトとの不整合に気づけなくなる。
config-editor 保存経路（`mirrorToDeploy` 等）を通さない自作の生成・書き込みスクリプトを新設するときは、
**ソース→デプロイ先の同期が本当に行われるか**を必ず確認する。過去に、生成スクリプトがソースにしか書かず
デプロイ先（実サーバ config）を素通りしたため、「値を変えたのに反映されない」を実装バグと誤認した事例がある。

### 語彙・定義ファイルはサーバ側（`lib/`）とブラウザ側（`public/js/`）で分かれている
`lib/gate-vocabulary.js` / `lib/tier-vocabulary.js` のように、検証・API 側（Node, `lib/`）に語彙定義が
存在する一方、UI 描画側（ブラウザ, `public/js/`）にも同名または対応する語彙・定数ファイルが存在する
（例: 両方に `constants.js` があるが、役割は「サーバAPI用の集約定義」と「ブラウザフォーム用のフィールド定義」で
異なる）。**ステータスキー・ゲート種別・語彙を追加/削除するときは、検証側とUI側の両方を確認し、
片方だけ直して終わらせない**こと。過去にキー削除がテストの片方向チェックしか無く、削除漏れを構造的に
見逃していた例がある（ドリフト検知は「追加」と「削除」の両方向でテストすること）。

## ArsPaper の sourcelinks.yml / sourcejars.yml は既に editor 対応済み（「未実装」と誤認しやすい）

`public/js/ars-source-forms.js`（531行、2026-07-27 の「機能アイテム」ナビ新設と同時に配線）が
`window.buildSourceJarsForm` / `window.buildSourceLinksForm` を提供し、`app.js` の
`case "ars-sourcejars"` / `case "ars-sourcelinks"` から呼ばれる。ナビは「機能アイテム」グループ
（`NAV_SECTIONS` の `key: "functional-items"`、`order: ["functional-items", "sourcelinks", "sourcejars"]`）
から到達できる。material（TileState限定）/ display-name / custom-model-data（CMD自動割当対応）/
lore / recipe（`renderCatalogRecipeSection` 経由でワンド/儀式レシピ共通UI）/ capacity（jars）/
type・投入マテリアル表（links の volcanic・mycelial・alchemical）まで一通り揃っている。

「sourcelinks/sourcejars が editor に無い」という報告を受けたら、まずこのファイルが実際に
到達できているか（ビルド済み editor で「機能アイテム」→「ソースリンク」を開けるか）を確認すること
── 実装が無いのではなく、報告者が古い build を見ている／到達経路を知らない可能性がある。

### transfer: チューニングブロックと items.<id>.transfer-multiplier は 2026-08-02 に editor 対応済み
※かつて「`sourcelinks.yml` の `transfer:` 節（`SourceTransferConfig` が消費する調整値）と
`items.<id>.transfer-multiplier` には UI が無い」と記載していたが、`buildSourceLinksForm`
（`ars-source-forms.js`）に `renderTransferCard()` と `extraFields` 経由の `transfer-multiplier` を
追加して解消済み。`buildCatalogLikeCard` の `extraFields` へ渡す関数に足す、という対応方針自体は
合っていた。新しい調整項目を追加するときの参照点として残す。

### 「設定は本来必須（値が無いと Java が既定値を使う）」タイプの調整ブロックは lazy-touch で作る
`combat/mob-types.yml` の `dimensions:` や `sourcelinks.yml` の `transfer:` のような「複数階層の
子キーを持つ調整ブロックで、省略時は Java 側にちゃんとした既定値がある」種類の UI を追加するとき、
`ensureObject(working, "block")` で丸ごと実体化してから各フィールドを描画すると、**カードを開いて
何も変更せず保存しただけで、その瞬間の Java 既定値が yml へ全部書き込まれる**。今日の既定値と
明日以降 Java 側で変わる既定値が一致する保証はなく、書き込んだ瞬間に「将来の既定値変更が
この yml だけ効かなくなる」という凍結を生む（`normalize*` 系の既定値ドリフト事故と同根、
本ファイル上部の「Java 側との既定値の食い違い」参照）。
- **How**: 各リーフフィールドごとに `readPath`（現在値を読むだけ、何も作らない）と
  `ensurePath`（実際に値を書くときだけ中間オブジェクトを作る）を分離する。値を空へ戻したときは
  そのキーを消し、`pruneEmptyPath`（またはブロック単位の `pruneEmpty<Block>`）で空になった
  中間オブジェクトを根本方向へ辿って刈る。`getData()` 側でも同じ prune 関数をもう一度呼んで
  二重に安全策を掛ける（ライブ編集中の delete だけに頼らない）。
- 真偽値フィールド（例: `transfer.network.path-particles.enabled`、Java既定 `true`）は
  `checkboxInput(raw === undefined ? defaultValue : !!raw, ...)` で表示し、既定値と同じ値が
  選ばれたらキー自体を消す（本コードベース既存の `!== false` 系フィールドと同じ「省略時true」の
  流儀を踏襲）。
- 実装例: `public/js/mob-forms.js` の `renderDimensionsCard`/`pruneEmptyDimensions`、
  `public/js/ars-source-forms.js` の `renderTransferCard`/`ensurePath`/`readPath`/`pruneEmptyPath`/
  `pruneEmptyTransfer`。回帰テストは `test/mob-types-dimensions-2026-08-02.test.js`・
  `test/ars-sourcelinks-transfer-2026-08-02.test.js`（「未編集で保存してもキーが増えない」を
  各パターンで固定している）。

## タブ配置 (`_editor.itemTabs`) の設計と限界

### 「素材」タブは materials.yml 専用で、catalog.yml の itemTabs 拡張だけでは繋がらない
`app.js` の NAV_SECTIONS で「素材」タブだけ `split: { type: "materials", ... }` になっており、
`split-views.js` はこの分岐で `buildMaterialsForm(data, ...)`（`ars-forms.js`、materials.yml
専用）しか呼ばない。catalog.yml 側の `_editor.itemTabs`（`forms.js` の `CATALOG_CATEGORIES` =
weapon/armor/tool/other/catalyst/spellbook/thread の7値）は `idsInCategory` が汎用文字列比較
なので新しい値（例: `"material-ref"`）自体は技術的に追加できるが、それだけでは「素材」タブに
何も表示されない（`buildMaterialsForm` は `data.materials` しか読まないため）。catalog.yml の
アイテムを「素材」タブへ**移動せずに**一覧・編集できるようにするには、最低でも次の3箇所を
連動させる新しいアーキテクチャが要る:
1. `split-views.js` の「素材」分岐で catalog.yml も読み込み、`buildMaterialsForm` と
   `buildCatalogForm`（`initialCategory` を新値にした2本目のインスタンス）を
   `thread-bundle`（同ファイル 184-218行）と同じ「2フォームを1画面に積んで `getExtraSaves` で
   両ファイルへ保存する」パターンで合成する。
2. `forms.js` の per-item「表示タブ」セレクト（`renderItemTabSelect` の `tabOpts`、3077行付近）
   に新値を選択肢として追加しないと、運用者がそのタブへ品目を割り当てる手段が無い。
   **ここに追加する新しい id は既存の `"material"`（`external.material` = 実際に materials.yml
   へデータ移行するボタン、`moveEntryToMaterials`）と絶対に同じ文字列にしないこと。** 同じ文字列
   にすると、既存の「素材へ移動」オプションと衝突し、意図せず catalog.yml → materials.yml への
   実データ移行（`delete working.items[id]` を伴う）を誘発しうる。
3. `forms.js` の `buildItemStatsForm` は `candidate.tab === "material"` を厳密一致でスキップして
   item-stats.yml へのゴーストエントリ生成を防いでいる（446行、`item-stats-material-skip.test.js`
   が固定）。**新しい itemTabs 値を追加したら、この判定にも同じ値を足さないと、その値を持つ
   catalog アイテムが item-stats.yml に「タブの無い幽霊エントリ」として量産される**（このガード
   自体は "material" 専用の厳密比較で、新値には自動的に効かない）。
このため「ダンジョンの鍵をカタログのまま素材タブへ出す」のような要望は、上記3点を同時に設計・
実装しないと中途半端な機能（一覧はできるが item-stats が壊れる、等）になる。恒久対応が必要なら
専用タスクとして起票し、ここに挙げた3箇所を最初から通しで設計すること。

## 同時編集・マージ

### ⚠️ 配列の3-way マージは要素単位で行う（`merge.js`）
複数タブ/複数運営者が同時に同じ yml を編集すると、片方が保存したリスト追加（ガチャの排出テーブル等）が
もう片方の保存で丸ごと消えることがある。原因は `threeWayMerge` が配列を「1個の葉」として扱い、双方に
変更があると local 側の配列で丸ごと上書きしていたこと。現在の実装は次の優先順で解決する。
1. 双方が base への末尾追加のみ → base + 自分の追加 + 相手の追加（重複排除して両方残す、衝突なし）
2. 配列内の各要素が識別キー（`id`/`key`/`name`/`stat`/`material`/`item`/`skill` など、その配列内で一意な
   スカラー）を持つ → 要素単位で3-wayマージ（別要素どうしの編集・追加・削除は両立できる。同一要素を
   片方が削除・片方が変更した場合は削除を優先し、衝突として記録する）
3. 識別不能な配列 → 従来通り衝突として記録し local を優先する
マージロジックを変更するときは `test/merge.test.js` を必ず通す。ブラウザは `merge.js` を再読込するために
ハードリロード（Ctrl+F5 / Ctrl+Shift+R）が要る。

### companion（連動オプション）マージの一般化に注意する
複数 yml にまたがる「companion」設定（例: `stat-caps` / `alchemy-quality` / `enchant-luck` /
`crafting-features` / `food-gimmick` / `ars-config` / `glyph-damage-boost`）は、保存時にマージされず
次の保存で消えるバグを踏んだことがある。原因は特定の画面だけに個別分岐が書かれていて、他の画面には
分岐が無かったこと。新しい companion オプションを追加するときは、個別分岐を増やすのではなく
`COMPANION_OPTION_KEYS` のような汎用キーリストに追加する形にする。

## カテゴリ選択・状態管理

### ⚠️ カテゴリ選択状態は `WeakMap`（キー=オブジェクト同一性）に依存する
`split-views.js` のカテゴリ選択状態は `activeByHost` という `WeakMap` で、キーは YAML ルートオブジェクトの
**同一性（reference identity）**。カタログ画面のように特殊アイテムを隠すために `{...data, items: clone}` の
浅いクローンをフォームへ渡している画面では、カテゴリバー側に元の `data` を渡すと**参照が一致せず絞り込みが
常に無効になる**。カテゴリホストとフォームへ渡すオブジェクトの参照は必ず同じものを使うこと
（`const host = categoryHost;` を安易に `data` へ書き換えない）。

## UI 部品の重複表示

### ⚠️ `materialInput` の隣に `materialHintEl` を並べると同じ日本語名が二重に出る
`materialInput`（`util.js`）は 2026-07-29 に `listSelect` ベースへ移行済みで、選択後のトリガー表示
自体が既に日本語名（`primary`）になっている。それより前に「Material入力の横に日本語ヒントを出す」
ために作られた `materialHintEl` を今も `input-with-hint` ラッパーで隣に並べている呼び出し（例:
`mob-forms.js` の `buildDropRow` / `buildAddDropRow` / `buildRemoveDropsBox` /
`buildDropItemControl`）は、同じ日本語名が2回連続で描画され行が潰れる（実サーバ報告:
「素材 / material / 幸運のスレッド / 幸運のスレッド / 確率(0〜1) / …」）。
`materialHintEl` は raw な `<input>` 系の Material 欄（`listSelect` を経由しない箇所、例:
`forms.js` の `ingredientMaterialControl` のようにテキスト入力＋互換リストボタンと組み合わせる欄）
では今も意味があるので**一律削除しない**。`materialInput` の直後に付けているものだけが冗長。
新しく Material/カタログ選択欄を作るときは、まず `materialInput` 単体の表示を確認してから
ヒントを足すか判断すること（`.mob-drop-row` 直下に要素を増やすと崩れるトラップは既知だが、
今回のように**既存の子を削るだけ**なら安全）。

### ガチャ景品(`entries[].item`)は「カタログID（接頭辞なし）または バニラMaterial名」
`GachaEntry#itemId` は `custom:` 接頭辞を付けない bare な文字列（`items/catalog.yml` の ID、
または `Material` 名）で、`CrossPluginItemResolver#create` が catalog → ArsPaper 登録 →
バニラ Material の順で解決する（`custom:` 接頭辞を付けた場合はバニラ Material 解決を
スキップする別経路になるが、実際の `gacha.yml` は一貫して bare 表記）。
`window.materialInput({allowCustom:true})` は `custom:<id>` 接頭辞前提の候補
(`window.CUSTOM_ITEM_CANDIDATES`) としか一致しないため、bare なカタログIDをそのまま渡すと
常に「候補外」表示になる。カタログIDを bare のまま扱うセレクトは `catalogCandidates`
（`{id, displayName, material, cmd, tab}` の配列、接頭辞なし）ベースで自作する必要がある
（`p5-forms.js` の `catalogCandidateOptions()` / `prizeItemSelect` 参照）。

## Java 側との既定値の食い違い

### ⚠️ 「開いて保存しただけ」で条件が緩む種類のバグに注意する

`normalize*` 系が入れる既定値が Java 側の既定値と違うと、**エディタで開いて保存し直すだけで
意味が変わった yml が書き戻される**。差分は正常な編集と区別できないので、レビューでも気づけない。

実例（2026-07-31 修正）: `normalizeAchievementTrigger` の `collection.threshold` 既定が常に `1`
だったため、「`targets` を3つ並べて `threshold` を省略＝3種そろったら達成」と書いた yml を
開いて保存すると `threshold: 1` が書き込まれ、**「どれか1つで達成」へ無言で格下げ**されていた。
条件が緩む方向なので、動作を見ても壊れているように見えない。

同時に `lib/schema.js` 側も `collection.targets`（複数形。**Java 側は 2026-07-27 に対応済み**）を
知らず単数 `target` を必須にしていたため、Java では正しく動く定義がエディタでは必ず検証エラーに
なっていた。**Java 側の loader を先に読んで、任意キー・既定値・省略時の挙動を突き合わせること。**
「Java が受け取れる形」と「エディタが書き出す形」は別々に育つので、片方だけ拡張されている前提で疑う。

## テスト実行時の見落とし

### ⚠️ MockBukkit 未実装のメソッドはテストが失敗ではなく SKIPPED になる
`HumanEntity#damageItemStack` のように MockBukkit 側に実装が無い Bukkit API を呼ぶと、テストは失敗ではなく
**SKIPPED として素通り**する。テスト結果を見て「green だから安全」と判断しないこと。SKIPPED の件数・理由を
毎回確認する。耐久消費のような処理は MockBukkit 経由に頼らず、`UNBREAKING` の `1/(L+1)` 判定なども
含めて自前で検証できる形にする。

### ⚠️ `Block#breakNaturally` は `BlockBreakEvent` を発火しない
連鎖伐採・連鎖採掘・範囲収穫などで `breakNaturally` を使うと、`BlockBreakEvent` が飛ばないため
**採取EXPが入らない・道具の耐久も減らない**（バニラの耐久消費もイベント経路にあるため）という2つの欠落が
必ずセットで起きる。連鎖分の補填は `gathering/ChainBreakSupport` が担う。逆に、これを直そうとして
`BlockBreakEvent` を合成してはいけない。採掘運・各種ギミック・ドロップテーブル・設置ブロック追跡など
10以上のリスナーが連鎖分にも反応してしまい、収穫量が跳ね上がる。

### `catalog-combat-content` の `wooden_halberd` 未割当 fail は既知の stale
`item-stat-coverage.test.js` / `catalog-combat-content` テストで恒常的に落ちている既知failがある
（データ側の欠落であり、直近の変更由来ではない）。新しい変更の副作用と誤認しないよう、変更前後で
fail数・fail内容が変わっていないかを比較すること。テスト失敗を「全部既存stale」と決めつけるのも危険で、
過去に本物のコンテンツ欠落（武器シリーズの全ティア消失）が「stale」として見過ごされていたことがある。
**毎回中身（差分・スタックトレース）を見て判断する**。

## ファイル衛生

### バイナリ判定・改行コード
JS/JSON ファイルに生の NUL バイトなどの制御文字を書くと、JS としては合法でテストも通るが、
**ripgrep/git がそのファイルをバイナリ判定し、以降の grep 検索が一切ヒットしなくなる**。折りたたみ状態の
区切り等に制御文字を使わず、通常のエスケープ文字列（`" "` 等）を使う。`test/source-hygiene.test.js` が
js/json/css/html の生制御文字を機械的に禁止しているので、これを削除・弱体化しない。
`.gitattributes` の `text eol=lf` 対象拡張子（`*.js` 等）を外さない。対象外の拡張子（`*.py` を追加した
実例がある）が Windows の改行コード書き戻しで大きな偽差分を生むことがある。

### バニラレシピの上書き優先順位
配備先 Paper のレシピ解決（`RecipeManager.getRecipeFor`）は「最後にマッチしたレシピが勝つ」実装になっている
ため、TF のカタログレシピがバニラと同型（同じ shape・素材配置）で登録されると、事実上バニラレシピを
上書きする。`custom:` 素材を含む TF レシピが同型衝突すると、素材料だけのグリッドではクラフトゲート側が
結果を空にしてしまい、バニラ品も作れなくなることがある。新しいカタログレシピを追加するときは、
既存バニラレシピと形が完全一致していないか確認する。

## 「準備中」カテゴリ (draft: true) と表示タブ移動の相互作用 (2026-08-02)

### ⚠️ 表示タブの移動でカテゴリの内部的な付け替えが起きると draft: true が無言で消える
`editor-categories.js` の `moveItemDisplayTab` は、旧タブのネストカテゴリから外し新タブの
カテゴリへ入れ直す内部処理で `removeEditorCategoryItem`/`ensureItemEditorCategory` を経由し、
これらは最終的に `moveItemEditorCategory` を呼ぶ。この関数は「準備中」カテゴリへの
出入りをそのまま `items[id].draft` へ同期する (`syncDraftFlag`)。**カテゴリの内部的な
付け替え(表示タブ移動に伴う移動元/移動先の再配置)と、利用者が「準備中カテゴリを選んだ」
という明示操作を区別しないと、準備中(draft: true)の品を別の表示タブへピン留めし直した
瞬間に draft が外れ、次の保存でゲームに出る**(レシピ登録・ガチャ抽選対象化。ダンジョンの
鍵のような「先に仕込んで後で解禁する」運用が事故る)。
- **How**: `moveItemEditorCategory`/`removeEditorCategoryItem`/`ensureItemEditorCategory` に
  `opts.skipDraftSync` を追加し、`moveItemDisplayTab` からの内部呼び出しだけ `true` を渡す。
  利用者がカード上のカテゴリセレクト(`renderEditorCategorySelect` の onChange)・複製・
  新規追加の絞り込み割当を通る経路は従来どおり同期する。
- 回帰テストは `test/draft-category-2026-08-02.test.js` の
  「【CRITICAL】表示タブを移しても draft: true は残る」。

### ⚠️ 「準備中」カテゴリは catalog.yml の画面にだけ出す(他ファイルでは意味が無い/害がある)
`draft: true` は `ItemCatalogConfig#load` が読む catalog.yml 専用のフラグ。materials.yml /
threads.yml / spellbooks.yml の画面で「準備中」へ入れても Java 側は無反応、**items を持つ
item-stats.yml では実際に `draft: true` が書き込まれて誰も読まないゴーストキーになる**
(実測: `{"NETHERITE_SWORD#300010":{"fixed":{...},"draft":true}}`)。
`renderEditorCategoryBar(host, tabKey, onFilterChange, onStructureChange, opts)` の第5引数
`opts.includeDraftCategory` が `true` のときだけ `ensureDraftCategory` を呼ぶようにし、
`split-views.js` の `withCategoryBar` は `o.type === "catalog"` のときだけ `true` を渡す。
新しい split type を追加するときは、draft が実効を持つファイルかどうかをまず確認してから
この値を決めること(既定は false = 出さない)。
回帰テストは `test/split-view-draft-scope-2026-08-02.test.js`。

### ⚠️ 予約カテゴリ(未分類/準備中)は「+ カテゴリ」「救済採番」以外の操作からも守る必要がある
`RESERVED_CATEGORY_IDS` は `addBtn`(新規作成時の id 衝突回避)と `backfillCategoryIds`(id
欠落救済)では最初から効いていたが、`deleteBtn`/`renameBtn` にはガードが無く、予約カテゴリを
削除・改名できてしまっていた。削除すると `draft: true` の付いたメンバーが孤児化し
(受け皿が消えても items 側のフラグは残るので「なぜゲームに出ないか」の手掛かりが UI から
消える)、改名すると id は `cat_auto_draft` のままラベルだけ変わり(例:「強化予定」)、
以後そのタブが意味不明なまま draft を刻み続ける。**予約 id を扱う操作(追加/削除/改名/救済)を
増やすたびに、その操作にも `RESERVED_CATEGORY_IDS` チェックを個別に入れる必要がある**
(1箇所に定義しても自動的に全操作へ効くわけではない)。

### 手書きの「準備中」カテゴリを予約 id へ昇格させるとき、既存メンバーにも draft を同期する
`ensureDraftCategory` はラベル一致 (`"準備中"`) の既存カテゴリを見つけると `id` だけを
`cat_auto_draft` へ書き換えていたが、そのカテゴリに既に入っていたメンバーの `items[id].draft`
を付け忘れていた。結果、「タブは準備中と表示されるのに中身は普通に配線されたまま(ゲームに
出続ける)」という食い違いが起きる。昇格時は `handmade.itemIds` を回って `syncDraftFlag(host,
id, true)` を呼ぶこと。

## `forms.js` は同じ形の `const defaultMaterial = {...}[activeCat] || "..."` ブロックを2箇所持つ
`buildCatalogForm`(カタログ画面の「+ アイテム追加」)と別の箇所(item-stats 系)に、ほぼ同じ
書き方の `defaultMaterial` マップ構築コードがそれぞれ独立して存在する。**片方だけを対象にした
緩い正規表現(`/const defaultMaterial = \{[\s\S]{0,400}?\}\[activeCat\]/` のような)で静的
ソーステストを書くと `exec()` は最初にヒットした側(無関係な方)を返し、意図した方の変更を
検証できないまま緑になる。** 対象を一意に絞るには、編集した側にしかない固有の文字列
(例: 追加したキー名)を正規表現の中に含めて絞り込むこと。

## ガチャ景品セレクトはカタログ候補とバニラ Material 候補を両方1本のセレクトに積む
`p5-forms.js` の `prizeItemSelect`(gacha.yml の `pool.entries[].item`)は、`custom:` 接頭辞
前提の `window.materialInput({allowCustom:true})` から `catalogCandidateOptions()` ベースへ
置き換えた際、バニラ Material 候補の生成ロジックごと削ってしまっていた(=正確な enum 名を
自由入力するしかなくなる、`tf-rewards-forms.js` で一度踏んだのと同じ罠の再発)。
`window.MATERIALS`(vanilla Material 全件配列)と `window.LABELS.materialLabel` から
`{value, primary, secondary}` を組み、`catalogCandidateOptions()` の結果と重複排除しつつ
連結する。`GachaEntry#itemId` はどちらも `custom:` 無しの bare な文字列で受けるので、
値の形式(セレクトの `value`)は変えない。

### 同じ「materialInput 直後に materialHintEl を並べる」二重表示バグは ars-forms.js にも残っている
`mob-forms.js`(2026-08-02修正済み)/`forms.js`・`ars-spellbooks.js`・`functional-items.js`
(2026-08-02 CRITICAL指摘で修正)以外に、**`ars-forms.js`(materials.yml 本体の編集フォーム、
440-444行付近)にも同じパターン `[matInput, matHint]` が残っている**。今回のタスクでは
明示的な対象範囲外だったため未修正。次にこの症状(素材欄で同じ日本語名が2回出て行が潰れる)を
materials.yml の画面で踏んだら、まずここを疑うこと。

## `select-japanese-labels-2026-07-29.test.js` はファイル全体に対する文字列一致で「禁止関数」を検査している
`test/select-japanese-labels-2026-07-29.test.js`(245行目付近)の「醸造ギミックの材料ヒントは custom: を
解ける共通ヘルパーを使う」は `assert.ok(!/materialLabelWithFallback\(/.test(src))` という**ファイル全体
(tf-crafting-features.js)への正規表現マッチ**で「custom: を解けない自前実装への先祖返り」を検知している。
対象は醸造ギミック(brew-unlocks)の material ヒント1箇所のはずだが、正規表現はスコープを絞っていないため、
**同じファイル内の全く無関係な箇所(例: 解体シリーズの表示名解決)で `materialLabelWithFallback(` を
呼んでも、あるいはその関数名をコメントに書いただけでも**同じテストが落ちる。
`custom:` プレフィックスと無関係な単純Material表示名を解決したいときは、この関数を呼ばずに
`window.MATERIAL_LABELS[key] || key` を直接書く(`materialLabelWithFallback` の中身そのものと同じだが、
このファイルでは名前を出さない)。新しいテストを書くときも、対象関数呼び出しの正規表現は
`assert.match(src, /function xxx\(\)[\s\S]*?\n  \}/)` のように**対象範囲を先に切り出してから**
判定する(`forms.js` の `defaultMaterial` 二重定義の教訓と同根)。

## 生ID表示バグの多くは既に前セッションで修正済み — 修正前に現在のファイルを必ず読み直す
2026-08-02 の表示名統一タスクで、事前に洗い出された「候補」の一部(`tf-lifestyle-forms.js` の村人職業カード、
`tf-rewards-forms.js` の特殊報酬/前提アチーブメント/一覧の各行、`mob-abilities-form.js`・`mob-forms.js` の
モブオーバーライドカード見出し)は、**候補リストが作られた時点では生ID表示だったが、実際に着手した時点では
既に別セッションが `entry["display-name"] || id` / `professionLabel(id)` 等で修正済みだった**。
「候補に載っている = 現在も再現する」と決め打ちせず、着手前に該当箇所を読み直して現況を確認すること。
逆に、`mob-abilities-form.js` の `idSelect`(汎用セレクト、EntityType/Particle/PotionEffectType 用)や
`mob-forms.js` の `abilityIdSelect`(mob-abilities.yml テンプレートID選択)のように、**個別の見出しは
直っていても、汎用ヘルパー内部が生ID決め打ちのままのケース**は見落としやすい
(修正は `window.MOB_LABELS_JA` / `window.PARTICLE_LABELS_JA` / `window.POTION_EFFECT_LABELS_JA`
(tf-lifestyle-forms.js の `POTION_EFFECT_OPTIONS` を window 経由で共有、新規辞書は作らない) /
`window.MOB_ABILITY_LABELS_JA`(app.js の `fetchMobAbilityIds` が `abilities[id]["display-name"]` から
同時に構築)を optional 引数として通す形にした)。

## 運営者が任意に決める「id」はそもそも表示名を持たない(生IDのままで正しい)
`loot-tables-form.js` の pool id、`p5-forms.js` の gacha pool id のように、**運営者がその場で自由に
命名する識別子**(Java側に対応する `display-name` フィールドが存在しない)は、id自体が既に人間可読な
ラベルとして機能している。`p5-forms.js` には「プールIDは運用側の任意名なので和訳できない。代わりに
景品件数を副表示に出す」という既存コメントがあり、これが設計判断として正しい。表示名統一タスクで
このパターンに当たったら「表示名が引けない」のではなく「表示名という概念がそもそも無い」ケースなので、
無理に他のフィールド(先頭素材のitem等)を代用ラベルにしない。

## 「専用GUIを作るな、既存の汎用フォームを使え」指示への違反は item-stats.yml 系でよく起きる (2026-08-02)
スレッド厳選(主ステ1つ+サブステ0〜4つの抽選)を ArsPaper 独自 `thread-rolls.yml` から TF の
`stats/item-stats.yml` へ移設した際、ユーザーの明示指示(「専用のGUIと仕様を作るな。武器と同じ
アイテムステータス設定の仕様とやり方で、スレッドも個別にステータス定義しろ」)に反し、
`random-roll-pools:` という**第4の専用データ層**と、editor に `p5-forms.js` の
`buildRandomRollPoolsForm`/`buildRandomRollPoolEditor`(rarities/main-stats/sub-stats/sub-count/
quality-spread 専用フォーム)+ `split-views.js` の「スレッド」タブだけ専用セクションを上に合成する
分岐(`o.itemCategory === "thread"`)を新設してしまっていた(同日中に撤去)。
- **なぜ間違いか**: `items.<key>` は既に `fixed`/`per-quality`/`random`(min/max)/
  `advanced.randomize-grants`+`grant-chances` という汎用ロール機構を持っており(`forms.js` の
  `buildItemStatsForm`、`lib/schema.js` の `validateItemStats`)、スレッドも武器/防具と同じ
  `items.<Material>` エントリとして表現できる。個体差(スレッド固有のランダム性)は既存 `random:`
  レイヤーの守備範囲内で、新しい抽選機構やレア度概念を作る必要は無かった
  (`no-new-dedicated-spec-reuse-item-stats` の教訓と同種の再発)。
- **検知の仕方**: `grep -rn "random-roll-pool\|randomRollPool"` は撤去後も**過去の経緯を説明する
  コメントには残る**(このリポジトリの規約として、廃止した機構は消した理由をコメントで残す)ため、
  この grep 単体では「ゼロ=完全撤去」の判定にならない。実体があるかどうかは
  `window\.buildXxxForm\s*=`(関数定義そのもの)や、その関数を呼ぶ側の分岐条件
  (`o.itemCategory === "thread"` のような専用 if)を正規表現で狙って判定すること。
  回帰テストは `test/thread-dedicated-ui-removed-2026-08-02.test.js`
  (静的ソースチェック3件 + `buildSplitConfigView` 経由で「スレッド」タブが他カテゴリと
  同じ `itemStatsForm` をそのまま使い、`random-roll-pools` ルートキーを書き換えず素通しすることを
  固定する動的チェック2件)。
- **ロスレス側の裏付け**: `buildItemStatsForm` の `working = data`(同一参照、浅いクローンではない)
  なので、`items` 以外のルートキー(`random-roll-pools` 等、Java 側の削除待ちで yml にまだ残る間)は
  editor がスキーマ検証も専用フォームも持たないまま黙って素通しする。専用UIを外しても
  「開いて保存しただけで消える」事故にはならない。

## 「もう1つのカードでも同じ実体を編集させたい」ときは新セクションを作らず参照を渡す (2026-08-03)

`skill-exp.yml` の Ars鍛冶(`ars-smithing`)カードは長らく `exp-per-craft` の定額表示のままだったが、
Java側 (`ArsProgressionBridge#grantSmithingCraftExp`, 2026-08-01以降) は儀式経路でも
`smithing.exp-per-material`(鍛冶カードの素材表)を読むよう変わっていた ── 消費素材が**全部**表に
載っているときだけ合計値を使い、**1つでも表に無ければ** `ars-smithing.exp-per-craft` の定額に戻る。
この種の「片方のカードの実装が先に進み、もう片方のカード表示が古いまま」を直すとき、
`ars-smithing.exp-per-material` のような**新しいキーを作ってはいけない**(Javaが読まない死に設定になる)。
正しい直し方は、Ars鍛冶カードの描画関数の中で `working.smithing["exp-per-material"]` への
**直接参照**を `expMapEditor` に渡すこと。同一オブジェクト参照なので、どちらのカードで編集しても
もう片方に即時反映される(浅いクローンを挟むとこの反映が壊れる、既知のWeakMap選択状態の罠と同根)。
`working.smithing` や `exp-per-material` が存在しない場合は `ensureObj` で強制生成せず(lazy-touch)、
編集不可の案内だけを出す ── ここで強制生成すると「Ars鍛冶カードを開いて保存しただけで
`smithing: {}` が yml に生える」正規化ドリフト事故になる。実装は `public/js/tf-forms.js` の
`buildSharedSmithingMaterialSection`。`lib/schema.js` 側は `exp-per-craft`/`exp-per-material` を
セクション名に関係なく汎用検証しているため、この種の「表示だけ2箇所に出す」変更は `lib/` 側の
ミラー更新が不要な数少ないケース(新しいYAMLキーを増やしていないため)。回帰テストは
`test/ars-smithing-shared-material-2026-08-03.test.js`。

## 関連
- [./ops-build-deploy.md](./ops-build-deploy.md)
- [./combat.md](./combat.md)
- [./progression-skilltree.md](./progression-skilltree.md)
