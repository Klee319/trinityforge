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

### ⚠️ 保存1回の副作用は「コメント消滅」だけではない — 表示タブのピンが外れると別ファイルのエントリが消える
2026-08-04 に実際に踏んだ連鎖。**editor で catalog 系の画面を1回保存しただけ**で次の3つが同時に起きた。

1. `items/catalog.yml` の本文コメント（25行以上）が消える（上の既知の罠）。
2. `_editor.itemTabs` のピンが `catalyst` → `other` に化けた（`inferItemCategory` の推論結果へ落ちた）。
3. **その2件の `stats/item-stats.yml` エントリ（`AMETHYST_SHARD#5760/5761`）が丸ごと消えた。**

3 が起きるのは、item-stats 画面の枠が「catalog 候補のうち `tab` が item-stats のタブ集合に入っているもの」
から作られるためで、**2 でピンが変わると候補が候補から外れ、次の保存でステ定義そのものが落ちる**。
`1.0` → `1` のような数値表記の正規化も同時に走るので、diff が大きくなって 2/3 が埋もれる。

対処:
- **editor で画面を開いたら、保存する前に必ず `git diff` を読む。** 意図した1件以外の差分（コメント削除・
  `itemTabs` の変化・別ファイルのエントリ削除）が混ざっていたら、その保存は捨てる。
- 調査目的で editor を開くときは**保存ボタンを押さない**。
- ピンの正しさは出荷ymlを実読するテストで縛る
  （`test/support-tab-subweapon-only-2026-08-03.test.js` / `test/catalog-key-tab-2026-08-04.test.js`）。
  これがあると、事故った保存を commit する前に赤で止まる。

### 表示プレビューの記法(legacy / MiniMessage)は固定値で渡してはいけない
`buildTooltipPreview().update({nameMode, loreMode})` と `richTextInput(value, mode, …)` に
`"minimessage"` / `"legacy"` を**決め打ちで渡すと、逆の記法で書かれた yml が生文字列で表示される**
（2026-08-04 の報告「`&c焔喰いの炉` のカラーコードがプレビューで見えている」の原因）。

- ゲーム内は fork の `com.arspaper.util.DisplayText#parse` が「レガシー(`&`/`§`)が1つでもあればレガシー、
  無ければ MiniMessage」で解釈する。**editor もこの優先順で判定する**のが唯一の正解
  （`public/js/ars-source-forms.js` の `markupMode` / `markupModeOfLines`。lore は1行でも混ざればレガシー）。
- 現状の記法はファイル単位で分かれている: `sourcelinks.yml` / `sourcejars.yml` / `materials.yml` はレガシー、
  `spellbooks.yml` と TF の `items/catalog.yml` は MiniMessage。**片方に固定してよいのは、そのファイルに
  混在が無いことをテストで縛っている場合だけ**（`test/ars-source-markup-mode-2026-08-04.test.js`）。

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

## レイアウト: `.main` のあふれは「スクロール」ではなく「クリップ」（2026-08-04）

`.main { overflow-x: hidden }` なので、**本文幅を超えた要素はスクロールで見えるようにならず、
切り落とされて永久に到達できない**。「削除ボタンが無い」「入力欄が消えた」系の報告は、
機能の欠落ではなくこれを疑う。`overflow-x` を `auto` に変えるのは筋が悪い
（横スクロールバーが常時出る問題で 2026-07-25 に一度 `hidden` へ寄せた経緯がある）ので、
**あふれる側を直す**か、その要素を自前のスクロール枠に入れる。

2026-08-04 に 375px までのレスポンシブ対応を入れた。閾値は
1280 / 1100 / 900（ドロワー化）/ 700（固定幅の横並びを縦積み）/ 600 / 400 で、
`style.css` 末尾の「レスポンシブ」節に集約してある。触るときの注意:

- **サイドバードロワーの吸着位置に px を決め打ちしない。** `.layout`（トップバーの下の残り全部）を
  `position: relative` の基準にして `absolute` で貼っている。トップバーの実高は日本語フォントで
  変わるので、`calc(100vh - 45px)` 系のマジックナンバーは必ず破綻する(2026-07-25 に踏んだ罠)。
- **900px という閾値は `app.js` の `SIDEBAR_DRAWER_MQ` と二重に持っている。** 片方だけ変えると
  「ドロワーなのに項目選択で閉じない」というズレ方をする。
- **列数の多い表は `.table-scroll` ラッパで包む（閾値で切り替えない）。** あふれるかどうかは
  画面幅ではなく**中身の文字数**で決まるので、`@media` で `display:block` に切り替える方式では
  漏れる（実際に 768px で CMD 台帳=8列が 149px あふれてクリップされた）。
- `auto-fill` グリッドの下限は `minmax(min(Npx, 100%), 1fr)` で書く。直値 `minmax(Npx, 1fr)` は
  トラックがそれを下回る幅で**グリッド自身が親をあふれさせる**（列を潰せないため）。

### UI をブラウザで検証するときの環境の罠

Browser ペインが**非表示**だとページがフレームを作らないため、次の 3 つが起きる。
知らないと「直っていない」と誤診する（実際に一度誤診した）。

1. スクリーンショットが撮れない（`the Browser pane is not displayed` で 5s タイムアウト）。
2. **CSS transition が進まない。** `getComputedStyle` が遷移途中の値を返すので、
   ドロワーが「閉じているのに `visibility: visible` / `translateX(0)`」に見える。
   計測前に `document.querySelectorAll('*').forEach(el => el.getAnimations().forEach(a => a.finish()))`
   で確定させる。
3. **`resize_window` しても `resize` / `matchMedia` の `change` イベントが 1 回も飛ばない**
   （1200→500px で 0 件を実測）。ビューポート自体は変わるのでレイアウトは追随するが、
   JS 側のブレークポイント処理は動かない。ハンドラの正しさは
   `window.dispatchEvent(new Event('resize'))` で直接叩いて確認する。

**ペインが非表示でもスクショは撮れる。** ヘッドレス Chrome を CDP で直接叩けばよく、
Node 24 は global `WebSocket` / `fetch` を持つので **puppeteer 等の依存追加は不要**
（`package.json` を汚さない）。`Runtime.evaluate` を挟めば「ドロワーを開いた状態」のような
インタラクション後の画面も撮れる（`chrome --screenshot` 単発では非同期描画に間に合わず、
操作もできない）。

**実装は `ops/scripts/` にある（2026-08-04 に `tmp/` から昇格。書き直さない）。**
先に editor を起動しておくこと（`cd tools/config-editor && npm start`）。

| スクリプト | 用途 |
|---|---|
| `ops/scripts/lib/cdp.mjs` | CDP クライアント / Chrome 起動 / 描画待ち。**計測前に必ず呼ぶ `flushAnimations()`** を共通化。`chrome.exe` のパスは環境変数 `CHROME` で上書き可 |
| `ops/scripts/editor-screenshot.mjs` | 8 パターン（1440〜375px、ドロワー開閉を含む）を撮って `tmp/shots/` へ出す（＝コミットされない） |
| `ops/scripts/editor-overflow-audit.mjs` | 8 幅 × 14 画面 = 112 通りの横あふれ監査。あふれがあれば exit 2 |

```bash
node ops/scripts/editor-screenshot.mjs
```

```bash
node ops/scripts/editor-overflow-audit.mjs
```

### 横あふれの検査は `scrollWidth` も見ないと取りこぼす

`getBoundingClientRect().right` を親の右端と比べるだけでは不十分。
**ブロックの矩形は親に収まったまま、中のインライン内容だけがあふれる**ケースがあり、
矩形ベースの検査は 0 件と報告する。実際に respack のビルド結果
（`C:\Users\...` の絶対パスと SHA-1 = 折返し候補を持たない 1 トークン）が
355px の枠から 653px 分あふれて切り落とされていたのを、この穴で一度見逃した。

検査は 2 本立てにする:

1. `rect.right > 親の右端` — 矩形のあふれ
2. `el.scrollWidth > el.clientWidth` — 中身のあふれ。
   ただし **`input` / `textarea` / `select` / `button`（欄内スクロールは正常仕様）と
   `text-overflow: ellipsis`（意図的な省略）を除外しないと偽陽性の山**になる
   （除外前 49 件中 38 件がノイズだった）。

いずれも**祖先に `overflow-x: auto|scroll` を持つ要素は「横スクロールで到達できる」ので除外**する。
**最も信頼できる単一指標は `.main` の `scrollWidth - clientWidth`**。これが 0 なら
「切り落とされて到達できない内容は無い」と言える。個々の要素の `scrollWidth` 超過は
0 でなくても正常なことが多い。

長い 1 トークン対策は `overflow-wrap: anywhere`（必要なときだけ折る）。
2026-08-04 の実測 A/B（8 幅 × 14 画面 = 112 組合せ）:

| | 変更前 | 変更後 |
|---|---|---|
| 矩形あふれ | 60 件 | **0 件** |
| 実クリップ（`.main` の overflow > 0） | 33 組合せ・最大 864px | **0** |

## カテゴリセレクトの薄字IDは「ラベルの一意性」で判定する（2026-08-04 修正済み）

※かつて「id の見た目（`cat_<Date.now()>` かどうか）で判定しているため、AI が yml へ直接書く
説明的な id (`cat_20260724_source_gem` 等) は常に薄字表示される」と診断し、正規表現を緩めない方が
安全側という結論にしていたが、**正規表現ベースの判定自体を撤去し、ラベルの一意性で判定する方式へ
置き換えた**（同一の不具合報告への対応、id の命名規則には依存しない解決）。

`editor-categories.js#computeCategorySecondaries`（純関数、`renderEditorCategorySelect` の
すぐ上）が判定を担う: 同じセレクト内で **label が非空かつ一意** なら薄字なし、**label が空**
または **他のカテゴリと重複** しているときだけ id を薄字で出す。id の書式は一切見ない。
`renderEditorCategorySelect` はこの関数の戻り値をそのまま `secondary` へ渡すだけになった。
- **How**: 新しい予約 id・GUI 採番 id・AI 直書き id のいずれであっても、ラベルさえ他と
  被っていなければ自動的に薄字が消える。逆にラベルが重複するカテゴリを新設すると
  （id の命名規則に関わらず）両方に id が出るのが正しい挙動。
- 回帰テストは `test/category-select-secondary-2026-08-04.test.js`
  （純関数の4パターン + `renderEditorCategorySelect` 経由の統合テスト2件）。
- `editor-categories.js` に `lib/` 側のミラーは無い（サーバ側 API 定義ではなく純粋な
  ブラウザ側描画ロジックのため）。新しい薄字ロジックを他のセレクト（材料/アイテム参照/
  表示タブ等、`grep -n "secondary:"` で多数ヒットする）へ流用するときは、それらが
  「id の命名規則」ではなく別の目的（材料IDの併記など）で id を出しているので、
  同じ関数を安易に共用しない（それぞれ意味が違う）。

## `<gradient:...>` はツリーパーサ・GUI編集とも対応済み（2026-08-04 修正）

※かつて「`<gradient:...>` は表示プレビューだけ対応・リッチ編集(GUI)は非対応」と診断していたが、
`colors.js` の `classifyOpenTag`/`parseNodesMM`/`flattenMMNodes`/`buildMMNodes`/`canonicalMMTag`
に gradient ノード種別を追加して解消済み。以下はその実装の恒久知識。

- **ノード表現**: 木パーサのノードは `{ kind:"gradient", gradientArgs:[...], openRaw, closeRaw, children }`
  （`gradientArgs` は `<gradient:c1:c2:...>` の `:` 区切り引数を**生文字列のまま**保持する。色として
  解決できない引数（phase 数値等）が混ざっていても解釈しようとせず、往復のためにそのまま残す）。
  `serializeMiniMessage`(302行) は元々ノードの `kind` を見ずに `openRaw` の有無だけで分岐する
  汎用実装だったため、**この部分は無改修でロスレス往復した**（`parse→serialize` の往復は
  classifyOpenTag が `ok:false` を返さなくなるだけで直る。新タグを1つ足すたびに serialize 側の
  改修が要るとは限らない、という教訓）。
- **表示色**: `gradientEndpoints(rawArgs)`(新設) が引数配列から色解決できるものだけを filter し、
  **最初と最後だけ**を線形補間の両端に使う（中間ストップは無視）。これは旧 `buildTooltipPreview` の
  正規表現特別扱いが元々やっていたのと同じ簡略化で、新しい制約ではない。`buildTooltipPreview` の
  `renderInto` は独自の regex 特別扱いをやめ、`flattenMMNodes`+`gradientEndpoints`+`gradientSpans`
  という GUI 側と共通の経路に統一した（結果、gradient が文字列全体でなく部分文字列を包む形
  `"前置き<gradient:..>中<gradient>後"` でも着色できるようになった。旧実装は全体一致 regex
  だったためこの形は非対応だった）。
- **GUI編集ボックスの内部表現**: `richTextInput` の文字ごとモデル(`colors`/`tokens`/`decos`)に
  並列で `gradients[]`（各文字が属する gradient の生引数配列、非所属は `null`）を追加した。
  DOM 上は `span.dataset.gradient = JSON.stringify(rawArgs)` で保持し、`readChars()` で
  `JSON.parse` して復元する。**gradient 所属の文字は per-char で `<color:#hex>` へ分解せず**、
  `serialize()` が `gradients[]` の連続区間をそのまま `<gradient:元の引数>text</gradient>` へ
  復元する（`buildMMNodes` の `r.gradient` 分岐）。これにより「GUIを開いただけ・無編集で
  簡易モードへトグルしても値が変わらない」という既存の安全性を維持している（実測済み）。
- **明示的な色操作は gradient 所属を解除する**: `applyColor`（選択範囲へ色を適用/クリア）は
  対象文字の `gradients[k]` も同時に `null` へ落とす。「範囲全体の自動彩色」と「部分的な手動着色」は
  両立しない、という設計判断。
- **解除(dissolve)操作**: 個々の色ストップの編集は非対応（YAGNI、実データに需要なし）。
  代わりに、gradient を含む編集ボックスを右クリックすると出るパレットに
  「グラデーション解除」ボタンを追加した(`hasAnyGradient()`/`dissolveGradient()`)。
  **選択範囲を問わず編集ボックス全体が対象**（既存の「色を消す」ボタンは選択必須で
  gradient 全体の一括解除には使いにくいため、別ボタンにした）。
- **legacy(&コード) への変換は gradient を含む文字列を `null` にする**: `miniMessageToLegacy` は
  `flattenMMNodes` の各ランに `r.gradient` があれば即 `null` を返す（legacy に gradient 相当の
  記法が無いため、変換せず呼び出し側にフォールバックさせる。**この分岐が無いと「色を黙って
  落とさない」という既存不変条件が gradient だけ破れる**ので、新しいタグ種別を足すたびに
  この変換関数の分岐漏れが無いか確認すること）。
## 差し込みタグ（`<icon>`/`<name>`/…）と GUI モード（2026-08-05 修正）

「GUI/簡易が切り替えられない」報告は **`parseMiniMessage().ok === false`** の一点に収束する。
`ok:false` は `richTextInput` の `remount()` が GUI ボタンを `disabled` にする条件そのもの。
原因は 2 系統あり、**症状が同じなので切り分けを間違えやすい**。

1. **色でも装飾でもないタグ**（差し込みタグ）。`classifyOpenTag` が `null` を返し未知タグ扱いになる。
   フィールド側が持つ差し込みタグ名を `richTextInput(value, mode, onInput, { placeholders: [...] })`
   で宣言すると、`isPlaceholderTag` がそれだけを「閉じタグを持たない text ノード」に落として通す。
   **allowlist 方式にしているのは意図的**で、GUI で表現できない本物の MiniMessage タグ
   （`<click:…>`/`<hover:…>`/`<font:…>`）を「ただの文字」に化けさせないため。
   宣言済み: `line-template`=icon/name/value、`score-line-template`=tier/tier-name/score、
   `owner-line`=owner、`use-requirement-line`=level/skill。**新しい差し込みタグを持つ
   フィールドを増やしたら、その呼び出しにも `placeholders` を渡すこと**（渡し忘れると
   そのフィールドだけ GUI が無効になる）。
2. **開き／閉じタグの数が合っていない値**。木パーサは未終端タグを `null` にする一方、
   MiniMessage 自体は未終端を許すので**表示は正常なまま GUI だけ死ぬ**。実例: 出荷
   `use-requirement-line` が `<gray>…<white>…</white> <gray>…</gray>` で `<gray>` 1 個ぶん
   閉じておらず、このフィールドだけ常に簡易編集だった（2026-08-05 に yml 側を均衡させて解消。
   `</white>` の直後は外側 `<gray>` へ戻るので内側の `<gray>` は元から no-op）。
   **パーサを緩める修正は入れていない**（ロスレス往復の保証が崩れるため）。同種の報告が来たら
   まず値のタグ均衡を数える。

**行のドラッグ並べ替えと入力欄の取り合い**（`window.guardRowDragFromInputs`, util.js）: 行に
`draggable=true` が付いていると、中の `<input>` での範囲選択ドラッグが行の並べ替えとして始まり、
逆に入力欄で選択した状態から掴むと選択テキストのドラッグが優先されて並べ替えが成立しない。
=「入力中でも行が動く」と「行を移動できないときがある」は**同じ原因の裏表**。対策は入力系要素の
`mousedown` 中だけ `draggable` を切ること。復帰は `document` の `mouseup` に一度だけ張る
（行の内側に張ると「選択したまま行の外で離す」経路で `false` のまま固まる）。

- **回帰テスト**: `test/colors-placeholder-tags-2026-08-05.test.js`（差し込みタグ）／
  `test/colors-gradient-2026-08-04.test.js`。後者は `items/catalog.yml` の
  `binder_*`/`key_binder` 系 gradient 付き display-name 18件全ての
  `serialize(parse(v))===v` 往復、legacy `&r`（リセット、既存の唯一の非対応ケース）と
  未知タグが引き続き `ok:false` のままであること（gradient 以外まで緩めていないこと）を固定する。

## skill-exp.yml に新しいスカラーキーを足すときは、ラベル登録先が2箇所ある（2026-08-04）

`stats/skill-exp.yml` の各セクション（`ars-smithing` / `smithing` / `combat` 等）は `tf-forms.js`
の `scalarSectionBody` が汎用描画するため、キー単位のラベル/説明は次の**独立した2箇所**に登録する
必要がある。片方だけだと「画面には出るが、別のドリフト検知テストだけ落ちる」状態になる。

1. **画面固有の説明文**: `buildSkillExpForm` の `SECTION_FIELD_OVERRIDES`（セクション名→キー→
   `{label, desc}`）。同じキー名でもセクションごとに意味が違う場合（`exp-per-craft` が鍛冶とAr鍛冶で
   別物、等）はここで上書きする。**これだけ足しても** `test/skill-exp-label-coverage.test.js` の
   「skill-exp.yml の汎用描画対象キーは全て labels.js で日本語ラベルへ解決される」は直らない。
2. **`public/js/labels.js` の `FIELD_LABELS`**: このテストは `SECTION_FIELD_OVERRIDES` を一切見ず、
   `window.LABELS.fieldLabel(key)`（labels.js 単体）だけを実 yml の `GENERIC_SECTION_KEYS`
   （`gathering`/`ars-smithing`/`smithing`/`ars-magic`/`combat`/`spot-diminishing`/
   `level-diminishing`）全キーに対して突き合わせる、独立したドリフト検知。新キーがこのいずれかの
   セクションに入るなら、`labels.js` にも中立な説明（画面別の詳細は要らない、1行で十分）を
   足さないと、**自分の変更とは無関係に見えるテストだけが赤くなる**（今回は `ars-smithing.exp-per-source`
   で実際に踏んだ。`power` セクションは `GENERIC_SECTION_KEYS` に含まれないため
   `levels-per-skill-point` はこのテストの対象外だが、他の画面が生ID表示防止に labels.js を
   参照することがあるので合わせて登録しておくこと）。
- `lib/` 側に `labels.js` のミラーは無い（ブラウザ専用の表示ロジックで、サーバ側検証は
  `lib/schema.js` の型チェックだけが責務のため）。

## 汎用セクションが曲線カードへ合流するスキルで「専用カードだけに出す」を作るときの除外機構

`power` セクション（`skill-exp.yml`）のように、**曲線ファイル（`skills/base/*_progression.yml`）が
`experience:` を持つスキル**は `buildSkillExpForm` 内の `curveBySkill` 判定で「レベル曲線・獲得レート」
の統合カードへ自動的に合流する。そのスキルの特定のキーだけをページ上部などの専用カードで描画したい
場合、汎用ループ（`scalarSectionBody`）側でも同じキーが二重に描画される。
- **How**: `SECTION_FIELD_OVERRIDES` と対になる `SECTION_EXCLUDED_KEYS`（セクション名→
  除外キーの `Set`）を作り、`scalarSectionBody` の第4引数 `excludedKeys` へ渡す。呼び出し箇所は
  **2つ**（`sectionsWithoutCurve` ループと、曲線へ合流した後のループの両方）あるので、片方だけ
  直すと「曲線を持たない他スキルでは直るが、曲線を持つこのスキルだけ二重表示が残る」という
  中途半端な修正になる。
- **合流するかどうかは推測せず、そのスキルの `*_progression.yml` に `experience:` があるかを
  実ファイルで確認する。** 無ければ合流しないので除外機構自体が不要（曲線を持たないスキルの
  専用カードは単に「そのセクションの一部フィールドだけ上に出し、残りは下の通常カードに任せる」
  設計にできる余地がある）。
- 回帰テストは `test/skill-exp-power-and-source-2026-08-04.test.js`（DOM harness で
  `.form-label-ja` の出現回数を数えて1箇所だけであることを固定）。

## ブラウザ検証で「同じフィールドが2回出ている」と誤診する CSS セレクタの罠（2026-08-04）

`fieldLabelEl`（`util.js`）が作る DOM は `span.form-label.with-ja > span.form-label-ja`（実際の
日本語テキストはここ）という**親子とも class 名に `label` を含む**入れ子構造。ヘッドレス Chrome 等で
`document.querySelectorAll('.form-label, .field-label, [class*=label]')` のような**広い部分一致
セレクタ**で「このラベル文言が何箇所に出ているか」を数えると、**同じフィールド1個なのに親要素と
子要素の2件としてカウントされ、二重描画と誤診する**（実際に本セッションで1回誤診し、Node製
DOM モック上のユニットテストの結果と食い違って再調査した）。
フィールドの出現回数を数えるときは、**実際にテキストを保持する末端の class（`form-label-ja`）だけ**
を対象にする、または `closest('.form-field')` で祖先の重複を先に潰してから数える。

## item-stats のキーは CMD が無いと素の Material に退化する ── 新規カタログ品が必ず踏む罠（2026-08-04）

`stats/item-stats.yml` のキーは `MATERIAL#CMD`。`forms.js` の `statsKeyFromCandidate` は
**CMD が空だと素の `MATERIAL` をキーにする**フォールバックを持つ。CMD は `catalog.yml` の
`custom-model-data` から来るが、**カタログへ新規追加した直後の品はまだ未割当**（個別の
「CMD自動割当」ボタンは廃止済みで、番号はリソースパック管理画面の一括採番かテクスチャ登録で付く）。
その結果 2 つが同時に起きる。

- **狙ったアイテムを指せない**: キーが `DIAMOND_SWORD` になるので、バニラのダイヤの剣**全部**に
  ステが効く。
- **追加そのものができない**: 出荷 `item-stats.yml` はバニラ用の素 Material キーを **77 件**持つ
  （総キー 449）。新品の material がそのどれかと一致すると `commitKey` の重複チェックに当たって
  「同じキーが既に存在します（重複）」で弾かれる。**これが「登録していないのに既にあると言われる」の
  正体**（2026-08-04 実機報告）。`material: BOOK` のように素キーが無い材質なら通ってしまうため、
  **再現するかどうかが材質次第**で、設定ミスに見える。

修正（`691ff03`）は `cmd-tools.js` の `window.cmdEnsureCatalogItemCmd({id, material})`。
CMD 未割当の候補を選んだときだけ、確認ダイアログ 1 回 → `/api/cmd/allocate` → `catalog.yml` へ
`custom-model-data` を書いて即保存 → 採番した CMD を返し、`forms.js` の `commitKeyFromCatalog` が
それで `MATERIAL#CMD` を確定する。押さえるべき点:

- **`catalog.yml` 側にも必ず書く。** item-stats のキーだけ `#123` にすると、そのステータスは
  実物のアイテムに**一生マッチしない**半端な状態になる。
- **確認ダイアログの後に revision を取り直してから採番する。** 先に採番して 409 を食うと
  台帳の番号だけ捨てることになる（番号は再利用しない方針なので欠番が増える）。
- **`.then()` の中で `candidate.cmd = assigned` も更新する。** 画面が持つ候補リストは同一オブジェクト
  参照なので、ここを忘れると同じ品を選び直したときに再び未割当と判定して確認が二重に出る。
- 回帰テストは `test/item-stats-new-catalog-item-cmd-2026-08-04.test.js`（採番ヘルパの契約 6 件＋
  「出荷 item-stats.yml が素 Material キーを持つ」＝衝突の前提を固定する 1 件）。

同じ根本原因（CMD 未割当の候補をアイテム1件の識別子として扱っていた）で、**候補同期ループと
キー解決にも同型のバグがあった**。`8703a1a` で以下の不変条件に揃えて解消済み。

> **CMD を持たないカタログ候補は item-stats のキーを持たない。素 Material キーは常にバニラのもの。**

- `statsKeyFromCandidate`: CMD が無い候補は空文字を返す（以前は素 Material を返していた）
- 候補同期ループ（`buildItemStatsForm` 冒頭）: 素キーの空枠を作らない。素キーが**まだ無い**材質
  （例 `BOOK`）では衝突検知に引っかからないため、**「バニラの本すべてに効く枠」が静かに生えていた**
  ＝材質依存で再現する潜在バグだった。`cmd` が空文字のとき `MATERIAL#` という Java 側が
  解決できないキーを作っていた不整合（`candidate.cmd == null` しか見ていなかった）も同時に消えた
- `candidatesForStatsKey`: 素キーを CMD 未割当の候補へ解決させない。以前は
  **バニラ用の素キーのカードが「その新品を選択済み」として表示され**（`findCatalogForStatsKey` が
  素キー→その候補に解決していた）、そのカードを編集すると新品を設定したつもりでバニラ全部に効いた
- `skill_node_lock` / `skill_tree_reset` を名前で個別除外していたのは同じ害の場当たり対応。
  根本は CMD の有無なので、名前リストは**新規追加品では毎回すり抜ける**
- 回帰テストは `test/item-stats-cmdless-candidate-no-bare-key-2026-08-04.test.js`。
  なお既存 2 テスト（`item-stats-material-skip` / `catalog-key-tab-2026-08-04`）の
  「巻き添え確認」の対照が CMD 未割当だったため、**旧挙動を固定していたテスト側も直した**
  （対照は CMD 付きにする）。実機では item-stats 武器画面のカード 206 枚が維持され、
  素キー 18 枚すべてが「カタログID空 ＋ バニラ表示」になることを確認済み

## ブラウザで `listSelect` の候補を選ぶ検証は `MAX_RENDERED = 200` に注意（2026-08-04）

`util.js` の `listSelect` はドロップダウンに **先頭 200 件だけ**描画する（`MAX_RENDERED = 200`）。
カタログ候補は約 400 件あるので、**新しく足した品は初期表示のリストに出てこない**。
「候補に入っていないから壊れている」と誤診しやすい（本セッションで 1 回誤診した）。
検証時は `li.list-select-filter-row` 内の `input.list-select-filter` に値を入れて `input` イベントを
発火させ、絞り込んでから `li` をクリックする。ドロップダウンは**独立したポップアップではなく
同じ `span.list-select` 内の `ul.material-suggest-list`**なので、`document` 直下を探しても無い。

## 関連
- [./ops-build-deploy.md](./ops-build-deploy.md)
- [./combat.md](./combat.md)
- [./progression-skilltree.md](./progression-skilltree.md)
