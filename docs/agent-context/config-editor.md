# config-editor（GUI設定エディタ）の恒久知識

`tools/config-editor`（Node.js 製、サーバ運営者が TrinityForge / ArsPaper の yml を GUI で編集するためのツール）を
触るエージェント向けに、今後も踏みうる落とし穴と不変条件をまとめる。作業履歴ではなく恒久的な設計・運用ルールのみ。

## データの真源と保存の仕組み

### yml 本文コメントは保存で消える → **2026-08-16 に解消済み**
かつて `lib/yamlio.js` の保存処理は**先頭ヘッダのコメントしか復元しなかった**ので、本文中の説明コメントは
config-editor 経由で1回保存しただけで消えていた。現在は `lib/yaml-merge.js` が元ファイルを YAML Document の
まま保持し、新データと同じキー/要素のノードを再利用するので**本文コメントも残る**。
- 保証されているのは `test/yaml-comment-preservation.test.js` の「出荷 yml を開いて保存しただけなら
  1バイトも変わらない」（`catalog.yml` 8000行超を含む5本で固定）。**この検査を消さないこと。**
- 消えるのは「構造ごと差し替わった箇所」のコメントだけ（元の値の説明なので残す意味が無い）。
  行末コメント前の空白は1個に正規化される。
- 元ファイルがパースできない/空のときだけ、従来の「全面 stringify + ヘッダ再付与」へ落ちる。
- **歴史的経緯**: この制約があった時期に書かれた「真源は `docs/config-reference/` に置き、yml には
  参照コメントだけ残す」という方針は今も有効（docs のほうが検索しやすい）。ただし
  **「コメントに書くと消えるから書けない」という理由はもう無い**。実際、消える仕様のせいで
  `skilltree/ars_smithing.yml` の「儀式は `recipe:` では無効」という注意書きが失われ、
  同じチャンネル取り違えが再発した（下の「解放ゲート」節）。

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
   `buildCatalogForm`（`initialCategory` を新値にした2本目のインスタンス）を、
   `o.type === "item-stats"`（同ファイル225行〜。item-stats.yml の「スレッド」タブが
   threads.yml も一緒に読み込み・`extraGets` で一緒に保存する2026-08-09の実装）と
   同じ「メイン1本のフォームに、別ファイルの `getData` を `extraGets` として積んで
   一緒に保存する」パターンで合成する（※かつては撤去済みの `thread-bundle` 型を参照していた）。
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

## ⚠️ `getData()` の刈り取りは `working` 配下のコンテナを差し替えてはならない（2026-08-05 修正）

行エディタ（`renderLoreRows` / `buildAddDropMobsBox` / `buildNoSkillExpMobsBox` など）は
**描画時に配列オブジェクトをローカル変数へ掴んでから** `list[idx] = v` で書き込む。
一方 `getData()` の空要素刈り取りを

```js
working[key] = working[key].map(...).filter(...);   // ← 配列を差し替えてしまう
```

と書くと、掴んでいた配列が**孤児**になり、以後その行の編集は `working` に届かない。
`getData()` は **画面を開いた直後**（`app.js` の `syncBaseFromEditor`）と
**`beforeunload` のたび**（`isEditorDirty`）にも呼ばれるので、
「**画面を開いてから最初の1回の編集だけが消える**」という形で出る。

さらに悪いのは、差分が出ないので `isEditorDirty()` が `false` のままになること:

- 画面を移動しても未保存警告が出ない
- 保存ボタンは `save()` 冒頭の `if (!isEditorDirty())` に入り、
  **「自分の変更はありません」と言ってサーバの内容を読み直す** = 編集が黙って捨てられる

刈り取りは必ず **in-place**（後ろから `splice`）で書く。テストは件数ではなく
**配列の同一性**（`assert.equal(working[key], captured)`）を固定する
（`test/prune-must-not-replace-arrays-2026-08-05.test.js`）。
実害が確認された箇所: `mob-forms.js` の `pruneEmptyNoSkillExpMobs` /
`pruneEmptyMobSelections`（レベルテーブル）、`tf-lifestyle-forms.js` の
`pruneEmptyDescriptions`（ロールバフ）。

## ⚠️ `getData()` の刈り取りは `_editor`（画面の UI 状態）も壊す ── 表示タブピン消失（2026-08-06 修正）

上の「コンテナを差し替えるな」と同じ事故クラスだが、被害が **`_editor` メタ**に出る形。

`getData()` の出力は `const out = { ...working, items }` の**浅いコピー**なので、
`out._editor` は**画面が握っている `working._editor` と同一オブジェクト**。
そこで `pruneEditorUiState()`（= `_editor.itemTabs` の孤児掃除）を素直に `delete` すると、
保存用の整形のつもりで**画面の表示タブピンまで消える**。
`getData()` は画面を開いた直後にも呼ばれるので、開いた瞬間に消える。

実際の症状（ユーザー報告「補助の未設定にある内容が消せない。他のカテゴリにあるから要らないのに」）:

1. `buildItemStatsForm` はカタログ候補ぶんの**値なしの空枠**を `items` に作り、
   候補の正しいタブ（`catalyst` / `spellbook`）を `_editor.itemTabs` へピン留めする
2. 空枠は `dropEmptyItemProfiles` で**出力の `items` から落ちる**
3. → そのピンが「孤児」と誤判定されて消える
4. → `getItemDisplayTab` が Material 推論へ退化し、`BLAZE_ROD`/`BOOK` が **「補助」タブ**へ落ちる
5. → カードを消しても候補同期が毎回作り直すので**消せない**

対策は2点セット。片方だけでは無効になる:

- `pruneOrphanItemTabs` は **出力オブジェクトの `_editor` だけを差し替える**
  （`{ ...ed, itemTabs: kept }`。`categories` / `orders` は同じ参照で持ち回るので、
  行エディタが掴んだ配列は孤児にならない）。落とすものが無ければ参照ごと据え置く。
- `split-views.js` の `_editor` 受け渡しは `adoptEditorMeta(host, out)` に集約し、
  **画面側が既に持つ `_editor` を保存用出力で上書きしない**。
  旧コードの `data._editor = d._editor` は刈り取り済みクローンを画面へ書き戻すので、
  上の対策を1レンダで無効化する。

テスト: `test/item-tab-pin-must-survive-getdata-2026-08-06.test.js`
（画面側ピンの生存・出力側からの孤児除去・`adoptEditorMeta` の方向性を挙動で固定）。

## ⚠️ 旧キーへフォールバックする表示は「新キーを消す」だけでは消えない（2026-08-05 修正）

`skilltree/*.yml` の説明文は新キー `description`、旧キー `effect-text` の二段構えで、
TF 側（`SkillTreeConfig#description`）は description が**無い/空白のときだけ** effect-text へ
フォールバックする（`nullableString` が空文字を null 扱いする）。

- 出荷 yml の**大半のノードは description を持たない**（`light_weapons` / `heavy_weapons` だけが持つ）。
  そのため editor 側の `delete obj.description` が **no-op** になり、上記と同じ
  「差分ゼロ → 未保存警告なし → 保存で捨てられる」に落ちていた。**スキル依存の再現条件**なので
  「たまたま試したスキルでは再現しない」ことがある。
- `description: ""` を書いても effect-text へフォールバックするので**説明は消えない**。
  空にする意図を表現できる唯一の書き方は**両方のキーを消す**こと（`applySkillNodeDescription`）。

新旧キーのフォールバックがある欄を触るときは、**Java 側がどちらをどう優先するか**を先に読む。

## 未保存判定の全画面掃引スクリプト（2026-08-05）

`tmp/dirty-audit.mjs`（ヘッドレス Chrome + CDP。`ops/scripts/lib/cdp.mjs` を使う）。
`isEditorDirty` は非公開だが、**cancelable な `beforeunload` を撃って `defaultPrevented` を見れば
外から判定できる**。dirty は一度立つと戻せないので**プローブごとに毎回リロード**する。
`TABS=1` で画面内タブ（`.recipe-tab`）も1枚ずつ回る。**保存は一切しない。**

偽陽性を出す入力が多いので、以下は必ず検査対象から外す（外さないと NG が量産される）:

- 表示フィルタ（「表示ステータス (攻撃/守備/…)」など。`.sub-section` の見出しで判別）
- `data-value` が `__custom__` / `__material_free__` のような `__…__` 番兵（自由入力欄を開くだけ）
- `data-value` が空の「(未選択)」、`readonly` の id 欄、`maxlength="1"` のクラフト配置セル
- タブのラベルや `listSelect` の placeholder を「追加ボタン」と誤認するもの
- `alert`/`confirm` で却下された操作（`confirm` は**必ず false を返す**こと。
  `cmdEnsureCatalogItemCmd` のように confirm の先で CMD 採番と catalog.yml の PUT が走る経路がある）

## ⚠️ 「実装はあるがナビ項目が無い分割ビュー」は無言で死ぬ（教訓は現存、実装は2026-08-09に差し戻し）

2026-08-08 に、threads.yml を独立画面「スレッド効果 (Ars)」（`__thread_effects__`、
`split-views.js` の `o.type === "thread-bundle"` で threads.yml + thread-sets.yml を1画面に束ねる方式）
として新設したが、**2026-08-09 にユーザーから差し戻された**（「アイテムステータスの設定でArs効果と
それ以外でスレッド分けないでほしい。もともとのスレッド設定の中で効果をセレクトメニューで追加可能な
方式にしてほしい」）。`__thread_effects__` ナビ項目と `thread-bundle` 型は**撤去済み**。
※かつてこの節は「`thread-bundle` を専用ナビで解消した」と書いていたが、その解決策自体が
ユーザーの意図（Ars効果とそれ以外を画面単位で分けない）に反していたため誤り。

**現在の正しい設計（2026-08-09〜）:** threads.yml の編集導線は「アイテムステータス > スレッド」
（`__stats_thread__`、`item-stats.yml` の thread カテゴリ）の**カード内**に統合されている。
- `app.js` の `selectTool`/`applyMergedToEditor` が `sp.type === "item-stats" && sp.itemCategory === "thread"`
  のときだけ `/api/config/threads` も一緒に GET/PUT する（`threadsData` を `buildSplitConfigView` へ渡す）。
- `split-views.js` の `o.type === "item-stats"` 分岐が `o.threadsData` を `buildItemStatsForm` に渡し、
  `extraGets` に `{ id: "threads", getData: () => threadsData }` を積む。
- `forms.js` の `renderThreadExtraFields` → `renderThreadYmlEffects` が、カタログの `thread_<id>` から
  `<id>` を剥いで threads.yml の該当エントリを解決し、`window.ARS_FORMS.parseThreadEntry`/
  `serializeThreadEntry` と **`window.buildThreadEffectsBox`**（下記）を呼んで編集UIを描く。
- **`window.buildThreadEffectsBox(model, onChange)`（`public/js/ars-forms.js`）が「効果を1つの
  `+ 効果追加` セレクトから追加する」唯一の共通実装。** 数値効果(regen-bonus等)・
  potion-effect+potion-level(1行に統合)・flight(チェックボックス)・slots(数値)を**種類で画面を
  分けず**同じセレクトの選択肢として並べる。`buildThreadsForm`（Ars専用の全件編集画面）と
  `forms.js`（item-stats.yml の「スレッド」タブ）の**両方がこの1つの実装を呼ぶ**。
  「効果ごとに専用の別セクション/別画面を作る」設計は**このユーザー指示で明示的に禁止されている**
  ので、新しい効果キーを追加するときも `buildThreadEffectsBox` の選択肢配列に足すだけにする。
- thread-sets.yml（N個装着のセット効果。効果選択とは別概念）も**同じカード内へ統合されている**
  （`extraGets` に `{ id: "thread-sets", getData: () => threadSetsData }` を積む。
  `forms.js` の `renderThreadSetEffects` が `threadSetsRoot["thread-sets"][threadId].thresholds`
  を読み書きする）。
  ※かつてこの節は「thread-sets.yml は独自の最小ナビ `__thread_sets__`（`o.type === "thread-sets"`）
  を新設して単独画面として残した」と書いていたが、これも**2026-08-09 に再度差し戻された**
  （「スレッドを画面で分けるな」という指示の趣旨に反する、撤去した `thread-bundle` と同じ形の
  分割を作り直しただけだったため）。`__thread_sets__` ナビと `o.type === "thread-sets"` 分岐は
  撤去済み。**「別ファイルの導線を保つ」という理由だけで専用ナビを新設するのは、この指示の下では
  誤った解決策**（正解は常に既存カードへの統合。分割ビューを1つ消したら、束ねていた他ファイルの
  データは別の専用ナビではなく「同じカードに extraGets で足す」方を先に検討する）。
- item-stats.yml 側にあった `entry["set-effects"]`（旧「セット効果」UI、"閾値ごとにステ付与"の
  見た目は同じだが書き込み先がここだった）は、**special-effects と同じ「editor にしか存在しない
  飾り」だった**（TF本体・ArsPaperフォークとも読むコードが無く、出荷 item-stats.yml にも実データ
  0件）。実際にスレッドのN個装備セット効果を読むのは thread-sets.yml（キーは threads.yml と共通の
  スレッドID）。2026-08-09 に `entry["set-effects"]` の読み書き・検証(`lib/schema.js`)・
  ラベル(`labels.js`)を全て撤去し、同じ見た目のUIをそのまま thread-sets.yml 書き込みに差し替えた。
  **「item-stats.yml 側に効果っぽい欄がある」＝「実際にゲームへ効く」ではない例がここでも
  もう1件見つかった**（下の節の special-effects と同じ構造の罠）。
- **教訓（変わらず有効）: 分割ビューは `split-views.js` の分岐・`app.js` の読み書き経路・
  `NAV_SECTIONS`（旧 `NAV_GROUPS`）の項目の3点が揃って初めて到達可能。** 前2つだけ書いても
  機能追加は運営者に届かない。ブラウザ（`http://localhost:8787`、無ければヘッドレスChrome+CDP、
  `ops/scripts/lib/cdp.mjs` 参照）で実際にナビをクリックして確認する。

### ⚠️ ブラウザ実測で「保存」を実際に押すと、動いているエディタが指す実リポジトリの yml に書き込む

config-editor は検証用サンドボックスを持たない。`EDITOR_PORT` で別ポートに立てても、
参照先は同じ実リポジトリの yml（`TrinityForge/src/main/resources/**`,
`fork-handoff/arspaper/fork/src/main/resources/**`）のまま。ヘッドレスChrome+CDPで
「保存」ボタン(`#save-btn`)を実際にクリックする検証をすると、そのまま出荷 yml が書き換わる
（コメント消滅・意図しないキー追加が実際に混入する）。
- 保存ボタンのクリックまで含めて動作確認したい場合は、`window.fetch` を横取りして
  `method === "PUT"` のリクエストを**実サーバへ転送せず**ペイロードだけを検査する
  （`res.ok`/`res.json()` だけ読む最小のフェイク `Response` を返せば `api()` は正常に完走する）。
  これで「保存すると何が PUT されるか」を実際のUI操作込みで確認できる。
- GET は読み取りのみで安全なので横取り不要。危険なのは PUT/DELETE だけ。
- 検証後は `git status`/`git diff` で対象 yml に意図しない変更が付いていないか必ず確認する。

## スレッドの effects リストは「検証側」と「UI側」で別物 ── 名前が同じでも自動同期しない

`lib/schema.js` の `THREAD_EFFECT_KEYS`（`validateArsThreads` が数値型チェックする既知キー一覧）と
`public/js/ars-forms.js` の `THREAD_EFFECT_KEYS`（CORE、`buildThreadEffectsBox` の追加セレクトに
何を出すか＋ `parseThreadEntry`/`serializeThreadEntry` の分類先）は、他の「lib/ と public/js/ の
ミラー2本」とは違い**役割が異なる独立リスト**（前者=型検証だけ、後者=UI描画とロスレス往復のモデル
分類）。過去に両方が偶然同じ配列リテラルだったため、出荷 threads.yml が使う `mana-max-percent`/
`regen-percent` が**検証側でだけ**取りこぼされていた(2026-08-08 修正、両方に追加した)。
新しいスレッド効果キーを追加するときは、この2つを**それぞれ意図的に**更新すること
（「片方だけ直すと〜」という通常のミラー注意とは逆に、こちらは「同じ配列に見えて実は目的が違う」
ことが罠。UI側だけ・検証側だけの拡張がありうる)。
`potion-effect`/`potion-level`/`flight`/`slots`（バックパック枠）は数値効果とは別の「型」（選択/真偽値/
バックパック整数）を持つため `THREAD_EFFECT_KEYS`（数値効果専用）には入らないが、**画面としては
`buildThreadEffectsBox` の同じ追加セレクトに数値効果と並んで出る**（画面分割はしないが、
モデル上の分類は型ごとに残る）。
※かつてこの節は「`slots` を `potion-effect` 等と同じ独立の『特殊効果』専用セクション
（`renderSpecialEffects`）へ移した」と書いていたが、その専用セクション自体が2026-08-09に
撤去され `buildThreadEffectsBox` へ統合されたため誤り。

## 旧「特殊効果 (special-effects)」「セット効果 (set-effects)」欄(item-stats.yml)は撤去済み ── 実効なきUIの実例2件

`forms.js` の `activeCat === "thread"`（**item-stats.yml の「スレッド」タブ**。threads.yml/
thread-sets.yml とは別ファイル・別データモデル）に、同じ構造の「editor にしか存在しない飾り」
UI が**2件**あった。どちらも TF本体・ArsPaperフォークとも読むコードが無く、出荷 item-stats.yml
にも実データ0件だった(選んでも何も起きない):
- `THREAD_SPECIAL_EFFECTS` 定数 + `entry["special-effects"]` 配列(2026-08-08 撤去)。
  実際にポーション効果を読むのは threads.yml 側の `potion-effect`/`potion-level`/`flight`
  (`window.buildThreadEffectsBox`、「アイテムステータス > スレッド」のカード内に統合済み。
  上の節を参照)。
- `entry["set-effects"]`(2026-08-09 撤去)。実際にN個装備セット効果を読むのは thread-sets.yml
  (上の節を参照)。
このコードベースでは「アイテム個別ステータス画面(item-stats.yml)に効果っぽい欄がある」＝
「実際にゲームへ効く」ではないことが**2回連続で**起きている。新しい欄を追加するときは、
**Java/フォーク側の読み取りコードを先に確認してから**UIを生やす(このケースは逆で、UI が先にあって
読み取りが無かった)。item-stats.yml の「スレッド」タブに何か新しい効果系の欄を足したくなったら、
まず「それは threads.yml か thread-sets.yml のどちらかに実装済みの機構ではないか」を疑う。

## ⚠️ ステの数値入力は必ず `statValueControl` を通す ── 素の `numberInput` は %表示を無言で壊す（2026-08-12 修正）

`isPercentStat(key)` を知っているのは `forms.js` の **`statValueControl(key, value, setter, opts)` だけ**。
これだけが「値×100 を表示し、÷100 して保存する」%入力（`.pct-input` + `.pct-suffix` の `%`）を作る。

ここを `window.numberInput` で書くと**2つ同時に壊れる**:

1. 割合がそのまま出る（回避率 3% が `0.03`）。
2. **単位すら出ない。** `statUnitSlot(stat)` は `!isPercentStat(stat)` のときしか単位を返さない
   ＝ %ステには**空スロット**を返す仕様（`%` は `statValueControl` の `pct-suffix` が出す前提）。
   つまり `numberInput` + `statUnitSlot` の組み合わせは「小数なのに `%` も付かない」という、
   一見「lore.yml の宣言ミス」に見える表示になる。

2026-08-12 に実際に踏んだのは `renderThreadSetEffects`（スレッド画面の「セット効果 (thread-sets.yml)」）。
**`statValueControl` の定義の直上には「thread-sets フォーム等でも同じ %入力(割合保存) を再利用する」と
既に書いてあった**のに、この1箇所だけ変換され忘れていた。

診断の教訓: 「%ステの表示がおかしい」を **`stats/lore.yml` の宣言監査だけで結論してはいけない**。
宣言が正しくても**呼び出し側が `statValueControl` を通っていなければ同じ症状になる**。
監査すべきは「どのフォームが `statValueControl` を呼んでいるか」。

回帰は `test/stat-row-percent-input-2026-08-12.test.js` の構造ガードで固定してある
（`statSelect` を持つ行が素の `numberInput` を使っていないこと。倍率行 `x1.2` は
ステの単位も % も持たない別物なので `mult-prefix` を含む行だけ免除）。

### ステ語彙に無い割合は `rateValueControl` を使う（2026-08-13 追加）

上のルールは **`statSelect` を持つ行にしか効かない**。`statValueControl` は
`isPercentStat(key)` ＝ `stats/lore.yml` の宣言で割合かどうかを決めるので、
**語彙に載っていないキーは割合でも FLAT に落ちる**。

該当するのがモブ系とダンジョンテーマの固定キーのフォーム。
`combat/mob-types.yml` などは `physical.resistance` / `attack.crit-chance` のように
**ブロックに属する短縮キー**で書かれていて、`phys-resistance` のような語彙キーとは綴りが違う。
そのため `forms.js` に **`rateValueControl(value, setter, opts)`**（キーを見ず常に % 入力）を用意し、
「これは割合だ」と分かっている呼び出し側が明示リストで指定する:
`mob-forms.js` の `RATE_FIELDS` と `tf-dungeon-forms.js` の `RATE_RAMP_KEYS`（同一内容の9キー）。

**この表に入れてはいけないもの**: `flat-defense` / `flat-bonus-damage` / `fixed-damage` /
`attack-power` / `max-health` / `level`。割合ではなくダメージ量・HP・レベルなので、
入れると画面が 100 倍で表示する。ランプ（`base` / `per-level` / `growth` / …）は
**1 行の中で単位が混ざる** ── `growth` は倍率、`growth-interval` と `high-level-from` はレベル数なので
% にしてはいけない。% にしてよいのは `base` / `per-level` / `high-level-per-level` だけ。

空欄の扱いも分かれる。モブ系は「空欄 = キーを書かず上位スコープを継承」なので
`rateValueControl` の既定は **空欄 → `null`**（呼び出し側が `delete` する）。
既存画面が使う `statValueControl` 経由は従来どおり `null` を `0` として扱う
（`blankWhenEmpty: false`）。ここを取り違えると、レベル係数やオーバーライドの継承が黙って壊れる。

**モブ側は `PercentStatNormalize.coerce` を通らない**（`MobTypesConfig` / `MobOverridesConfig` /
`RampParser` はどれも `getDouble` の生値をそのまま使う）。つまり手書きで `resistance: 25`
（25% のつもり）と書くと **2500%** としてそのまま通る。エディタ経由なら % 入力なので起きないが、
yml を直接編集するときはここが効かないことを忘れないこと。

全画面の掃引には `ops/scripts/editor-percent-audit.mjs`（ヘッドレス Chrome、依存ゼロ）を使う。
**`innerText` で判定しないこと** ── 折りたたみカードの中の行は非表示で `innerText` が空になり、
418 欄ある画面を 18 欄しか見ないまま「問題なし」と報告する。`textContent` を使い、
走査母数（数値入力とステ選択の件数）を必ず一緒に出して空振りを検知する。

## `dropTableEditor`(fishing/mining/woodcutting/digging 共通)は path を渡した瞬間に丸ごと実体化する（2026-08-15）

`tf-lifestyle-forms.js` の `dropTableEditor(working, path, opts)` は内部の
`resolveDropTableContainer` が `path` の各セグメントへ `ensureObj` を無条件で呼ぶ
（`for (const key of path) node = ensureObj(node, key)`）。つまり
**呼び出した時点で `path` の中間オブジェクトと `categories: {}` が丸ごと生成される**。
既存の `groups.treasure`/`groups.junk` はこれを常時呼んでよい設計（常時テーブル）だが、
`groups.fish`(後方互換で既定=未設定)のような**任意キーのドロップテーブル**を新設するときは
`dropTableEditor` を直接呼ばず、`fishGroupEditor`/`unlockGroupEditor`
（同ファイル、`fishing.unlock-groups.<groupId>` の機能解放追加用テーブルで新設）と同じ
「`hasOwnProperty` で存在確認 → 無ければ emptyGuide + 有効化ボタンのみ描画 → ボタンを押した
瞬間に `{ categories: {} }` を代入してから初めて `dropTableEditor` を呼ぶ」二段構えにすること。
これを怠ると、フォームを開いただけで任意キーが yml へ書き戻る（本ファイル上部の
「Java 側との既定値の食い違い」節と同じ事故クラス）。

## `stats/*-gimmick.yml` の drop-tables 系(`groups`/`drop-tables.categories`)は長らく schema 未検証だった

`lib/schema.js` の `validateTfMiningGimmick`（コメントに「最小限・許容的」と明記）は
`suspicious-block-respawn.loot-tables` の型しか見ておらず、mining/woodcutting/digging の
`drop-tables.categories` と fishing の `groups.*` は 2026-08-15 まで**一切検証されていなかった**
（不正な `weight`/`amount` を書いても validate() はエラーを返さない）。fishing の
`groups`/`unlock-groups` にだけ `validateFishingDropGroupsMap`（同ファイル、entries[].weight/amount
が 1 以上の整数であること等を検査）を新設したが、**mining/woodcutting/digging 側の
`drop-tables.categories` は今も未検証のまま**。同種の検証を追加するときは、この関数を
そのまま流用できる形（`{groupId→{categories:{catId→{display-name,entries[],
trigger-chance-percent?}}}}` 相当の形へ正規化してから渡す）にしてある。

## カタログの「スレッド」タブは id が `thread_<threads.ymlのid>` でないと GUI もゲーム内も無言で死ぬ（2026-08-15）

`items/catalog.yml` の `_editor.itemTabs.<id>: "thread"` に分類された id は、ArsPaper
`UnifiedRecipeLoader.java:212` の `recipeKey("thread_" + threads.ymlのid), ...)` という命名規約に
必ず合わせる必要がある。実サーバで新規スレッド追加時に `thread_translate` を `thred_translate`
とタイプミスした事故があり、症状は「エディタでスレッド固有の設定欄を開くと見出し（`subTitleEl`
「スレッド固有」）だけ残って入力欄が1つも出ない」だった。

- **機構**: `forms.js#resolveThreadId` は `catId.startsWith("thread_")` が false なら `null` を
  返し、`renderThreadYmlEffects`/`renderThreadSetEffects`（同ファイル、`renderThreadExtraFields`
  内）は冒頭 `if (!threadsRoot || !tid) return null;` で即 return する。見出し自体は無条件描画な
  ので、**理由がどこにも出ないまま空欄だけが残る**。id の綴りミスは「エディタのバグ」に見えるが
  実際は catalog.yml 側のデータ不正で、`thread_` で始まらない id は threads.yml /
  thread-sets.yml のどのエントリにも対応しないため**ゲーム内でもスレッドとして機能しない**
  （表示バグではなく実害あり）。
- **対処（実装済み）**: `forms.js` に純関数 `window.threadCatalogIdProblem(catId)` を新設し、
  `renderThreadExtraFields` は見出し直後にこれを判定して問題があれば `warn-banner` を出して
  return する（原因と直し方をGUI上に明示）。`lib/schema.js` の `validateCatalog` にも
  `validateCatalogThreadTabIds` を追加し、`_editor.itemTabs` で `thread` 分類の id が
  `thread_` プレフィックスを持つことを保存時に検査する。回帰テストは
  `test/thread-catalog-id-prefix-2026-08-15.test.js`。
- **教訓**: `resolveThreadId`/`renderThread*Effects` のように「対応関係が崩れると黙って
  `null` を返し、呼び出し元も無条件で見出しだけ描く」構造は、原因不明の空欄バグを生みやすい。
  新しいタブ種別・id 命名規約を導入するときは、対応が取れない場合に**理由を画面に出す**
  ガード関数を最初から用意すること（`threadCatalogIdProblem` を他のタブ種別へ流用する場合、
  戻り値の形 `{reason, title, hint}` をそのまま踏襲すると一貫性が保てる）。

## `assets/minecraft/items/*.json` は生成物 ── 手書きすると必ず巻き戻る（2026-08-16）

`tools/config-editor/lib/respack.js` の `regenerateItemDefinitions(packRoot, registryPath)` は、
`cmd-registry.json` を唯一の入力として **その material の items json を丸ごと書き直す**。
ユーザーがエディタのリソースパック管理画面を開くだけで走るので、
`resourcepack/trinityforge-items/assets/minecraft/items/*.json` を**手で編集しても黙って消える**
（実際に本セッションで 2 回巻き戻り、「他セッションが revert している」と誤診した）。

配線済みかどうかの判定は 1 行しかない:

```js
hasModel(a) = a.assetName && exists(models/item/<a.assetName>.json)
```

ここから 2 つの無言の失敗が出る。

- **`assetName` が無い割当は、モデルもテクスチャもパックに実在していてもバニラの
  fallback モデルへ落とされる。** テクスチャを追加した本人からは「入れたのに反映されない」
  にしか見えない（実例: `IRON_SWORD:68` fnis_peccati_profundi、ソース系 5 種）。
- **その material の配線済み行が 0 件だと、items json ごと生成されない/削除される。**
  `amethyst_shard` / `prismarine_crystals` / `heart_of_the_sea` / `conduit` / `nether_star` が
  そもそも存在しなかったのはこれ。

したがって直す場所は **`cmd-registry.json` の `assetName`（と `parent`。手持ち武器なら
`handheld`、それ以外は `generated`）** であって items json ではない。
反映は**エディタ自身の generator を呼ぶ**（`require("tools/config-editor/lib/respack.js")` →
`regenerateItemDefinitions(...)`）。手書きの JSON はエディタが出す形と必ずどこかがズレる。

`cmd-registry.json` は永続台帳で **CMD 番号を再利用しない**。既存の割当に `assetName` を
後から足すのは正しい操作だが、番号を振り直すのは禁止。

### 「アートはあるのに描かれない」を検出する仕組み

`resourcepack/build_item_pack.py` の `validate()` は元々**前向き参照**（items json → model →
texture が存在するか）しか見ておらず、上記のどれも検出できなかった。逆向きの検査を 2 本追加してある。

- **D-1**: items json を起点に推移的に辿り、どこからも参照されない model/texture を落とす
  （＝カタログから消えたアイテムのアートが残り続けるのを止める）
- **D-2**: `cmd-registry.json` の割当のうち、**パックに実物があるのに** threshold へ配線されて
  いないものを落とす。割当の大半は意図的にバニラ見た目のままなので、
  「実物がある」で絞らないと検査そのものが無意味になる

「アイテム定義より先にアートだけ入れる」場合の逃げ道が `resourcepack/unreferenced-assets.json`
（`{"assets":[{"path": ..., "reason": ...}]}`）。ただし**宣言が腐る方向も両方エラー**にしてある
（宣言したのに実は配線済み / 実はもう存在しない / `reason` が空）。
理由の書いていない宣言は許可リストと同じで書いた本人以外に検証できないため、`reason` は必須。
該当 0 件のときはファイルを置かない（存在しなければ宣言 0 件として扱う）。

## 防具の着用時テクスチャはエディタの管轄外（2026-08-16）

Minecraft 1.21.4+ では**着用時の見た目に CMD は一切効かない**。必要なのは
`minecraft:equippable{asset_id}` と、パック側の 3 点セット:

```
assets/trinityforge/equipment/<セット名>.json
assets/trinityforge/textures/entity/equipment/humanoid/<セット名>.png          (64x32)
assets/trinityforge/textures/entity/equipment/humanoid_leggings/<セット名>.png (64x32)
```

エディタには装備レイヤーという概念が無いので、**エディタで防具をどう設定しても着用時の
見た目は変わらない**。生成は `resourcepack/build_armor_layers_from_items.py`
（inv テクスチャの配色から決定的にレイヤーを起こし、`equipment-registry.json` と
`TrinityForge/src/main/resources/items/equipment-assets.yml` まで配線する）。
手描きの PNG が上記パスにあればそちらが優先される。

## `_editor.categories[*].itemIds`/`itemTabs`/`orders` の宙ぶらりん検出は「自ファイルの集合」だけでは誤検知する（2026-08-16）

`_editor` メタは実データ(`items`/`materials`)を消した後もエディタ経由の削除
(`window.removeEditorCategoryItem`)を通さない限り古い id を残し続ける
（手編集・改名・別ツール削除は検出不能。実測: ArsPaper `materials.yml` に 19 件）。
検出関数 `danglingEditorMetaIds(data, itemIdSet)`
(`lib/editor-meta-integrity.js` / `public/js/editor-meta-integrity.js`、完全ミラー)は
`itemIdSet` を突き合わせるだけの純関数だが、**`itemIdSet` を「そのファイル自身のキー」だけで
組み立てると意図的な他ファイル参照が誤検知になる**。

- 実例: ArsPaper `materials.yml` の `_editor.categories.material` にある
  `cat_dungeon_keys`(「ダンジョンの鍵」カテゴリ、17件)は **TrinityForge本体の
  `items/catalog.yml` の id を指すのが正しい設計**（鍵の実体を catalog.yml に置いたままにして
  いるのは `dungeon/gates.yml` の `key-item` 判定・レシピ・CMD台帳が catalog.yml 前提のため。
  materials.yml へ実移動するとダンジョン入場が壊れる）。
- **How**: `editorMetaItemIdSet(ctx, configId, data)`(`lib/editor-meta-integrity.js`、
  サーバ専用・`ctx.readEntryById` でクロスファイル読取)が configId ごとに集合を組む。
  `"materials"` は `自身のmaterialsキー ∪ catalog.ymlのitemsキー`。`"catalog"` は自己完結。
  `"item-stats"` は **`CmdRegistry.scanUsage` で定義ファイル全部を走査した `MATERIAL#CMD` 集合
  ∪ 自身の items キー**（後者は CMD 無しの素の `MATERIAL` キーを拾うため）。新しい config 種別へ広げるときは、その `_editor` が
  意図的に他ファイルの id を指す設計になっていないか（= 専用カテゴリの説明コメントが
  yml 側にあるか）を先に確認してから itemIdSet を決めること。決め打ちで「自ファイルだけ」
  にすると、常時ノイズを出す警告になり 1 週間で誰も読まなくなる。
- 検出は**保存をブロックしない**(`lib/schema.js` の `errors` には入れない。
  `server.js` の PUT `/api/config/:id` が `cmdWarnings` と同じ非ブロッキング応答フィールド
  `editorMetaWarnings` として返し、`public/js/app.js` がトースト表示する。加えて
  `public/js/split-views.js` の `withCategoryBar` が画面内にも `.form-banner` を出すが、
  **警告が無いときは今までどおり `root.children = [nestBar, body]` の2要素のまま**にしてある
  （`children[1]` のようなインデックス参照テストが複数あるため、警告があるときだけ先頭に
  banner を差し込む設計。無条件で3要素にすると `thread-dedicated-ui-removed-2026-08-02.test.js`
  等が壊れる）。
- `_editor.itemTabs` は `pruneOrphanItemTabs`(`editor-categories.js`)が **`items` を持つ
  ファイルの保存時にだけ**自動的に孤児を刈るが、`_editor.categories`/`_editor.orders` は
  同関数のコメントに明記の通り「触らない」ため一生残り得る。実データでの検証
  (`test/editor-meta-integrity-2026-08-16.test.js`)は事前に実測・裏取り済みの
  `categories[*].itemIds` だけを厳密比較し、`itemTabs`/`orders` は fixture テストのみに留めている
  （出荷データ全体の実測が無い状態で 0 件を断定すると、実装のバグと積年の孤児データの区別が
  つかなくなるため）。

### ⚠️⚠️ `item-stats.yml` の `items:` は「有効なアイテムの一覧」ではない（2026-08-16、上の検査を実際に壊していた）

導入翌日にアイテムステータス画面が **15 件**の宙ぶらりんを出したが、**10 件は今も実在する
アイテム**を指す誤検知だった。誤検知は「煩い」で済まない ―― 本物の取り残し 4 件が山に埋もれ、
実際に見過ごされていた。原因は 2 つあり、どちらも「有効 id の母集合」の取り違え。

1. **`item-stats.yml` は参照専用ファイル**（`lib/cmd-removal.js` 冒頭が明記）。
   `items:` に載るのは「TF ステータスを設定済みのものだけ」で、実在アイテム集合の**部分集合**。
   ステータス未設定の実在アイテム（catalog.yml の深罪の終幕 `IRON_SWORD#68`・魔法書3種
   `BOOK#100001-100003`、ArsPaper `spellbooks.yml` の触媒3種 `BLAZE_ROD#400024-400026`）が
   軒並み「もう存在しません」になっていた。→ サーバ側は `CmdRegistry.scanUsage` の集合を使う。
2. **ブラウザ側は母集合を作れない**。アイテムステータス画面の `host` にはカタログ候補の
   「空枠」行と、その `_editor.itemTabs` ピンが載っている（`buildItemStatsForm` が作る。
   `test/item-tab-pin-must-survive-getdata-2026-08-06.test.js` 参照）。一方 `split-views.js` が
   渡していた集合は `data.items`（読み込んだ yml そのもの）で、候補が必ず全部あぶれた。
   **候補ピンは `pruneEditorUiState` が保存時に落とすので yml には一度も書かれない** ――
   つまり画面にしか出ない、直しようのない警告だった。→ この画面では検査自体を行わない
   （`itemIdSet = null`）。保存時のサーバ警告に一本化する。

`categories`/`orders` は `pruneEditorUiState` が触らないので**本当に残る**。実際 `b969faa` が
`items:` エントリだけ消して 4 件（`IRON_CHAIN#68` / `CROSSBOW#161` / `GOLDEN_SWORD#59` /
`GLOWSTONE#84`）を取り残していた。`IRON_CHAIN`・`GLOWSTONE` は CMD 台帳にも Bukkit の
Material にも無い名前で、**同じ CMD の実体は別素材**（68 = `IRON_SWORD`、84 = `GLOWSTONE_DUST`）
という「素材名だけ古い」型の取り残しがある点に注意。回帰は
`test/editor-meta-item-stats-universe-2026-08-16.test.js`（母集合の非空回り検査つき）。

## 関連
- [./ops-build-deploy.md](./ops-build-deploy.md)
- [./combat.md](./combat.md)
- [./progression-skilltree.md](./progression-skilltree.md)
