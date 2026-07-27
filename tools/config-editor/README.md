# TrinityForge / ArsPaper Config Editor

TrinityForge および ArsPaper の武器・装備・アイテム config(YAML) を、ブラウザのフォームUIで安全に編集するためのローカルWebツールです。実 config ファイルをその場で読み書きします。Java プラグイン本体からは完全に独立しており、既存ファイルは編集操作をするまで変更しません。

- サーバ: Node.js + Express
- YAML: [eemeli/yaml](https://github.com/eemeli/yaml)（コメント保持のため）
- フロント: 素の HTML / CSS / JavaScript（ビルド不要・フレームワーク不使用）

## セットアップ

前提: Node.js v20 以上（動作確認は v24）。

```
cd tools/config-editor
npm install
npm start
```

起動後、ブラウザで http://localhost:8787 を開きます。**既定では `127.0.0.1` にのみバインドし、外部ネットワークには公開されません。**

ポートは `tool-config.json` の `port` で変更できます。加えて、環境変数 `EDITOR_PORT` を指定するとその値が最優先されます（ローカル検証で待受ポートを一時的に変えたいときに使用）。

```
EDITOR_PORT=8799 node server.js
```

既定では `host` はループバックへ正規化されます（`localhost` / `::1` / `127.0.0.1` はすべて `127.0.0.1` にバインド）。外部公開は下記「外部アクセス（オプトイン）」の手順を踏んだ場合のみ有効になります。

## 外部アクセス（オプトイン・認証必須）

> ⚠ このツールは **configファイルを読み書きできる編集ツール**です。ネットワークに公開すると「到達できる者は誰でもサーバconfigを書き換え可能」になります。公開は必要なときだけにし、下記の注意を必ず守ってください。

外部公開は **「明示オプトイン」かつ「認証パスワード設定」の両方が揃ったときのみ**有効になります（どちらか欠けると自動的に `127.0.0.1` へ強制フォールバックします＝無認証の露出を防ぐ fail-safe）。

1. `tool-config.json` の `external.enabled` を `true` にする。必要なら `external.bindHost` を設定（既定 `0.0.0.0` = 全インターフェイス。LAN限定にしたいなら特定IPを指定）。
2. パスワードを設定する。**環境変数 `CONFIG_EDITOR_PASSWORD` を推奨**（config への平文記載を避けるため）。ユーザ名は `CONFIG_EDITOR_USER`（既定 `admin`）。

```
CONFIG_EDITOR_PASSWORD='強いパスワード' node server.js
```

```json
"external": {
  "enabled": true,
  "bindHost": "0.0.0.0",
  "auth": { "username": "admin", "password": "" }
}
```

- **認証**: HTTP Basic（Node標準 `crypto` の定数時間比較。依存追加なし）。パスワードが設定されていれば、ループバック運用でも全ルートを保護します。
- **平文HTTPの注意**: Basic認証情報は base64 であり暗号化されません。**インターネット公開時は必ず TLS リバースプロキシ（Caddy / nginx / Cloudflare Tunnel）経由**にしてください。あわせて OSファイアウォール／ルータのポート開放を最小限に。
- CSRF: APIは `application/json` のみ受理し CORS を許可しないため、クロスサイトからの書き込みは preflight で遮断されます。

## 画面の構成と使い方（ダイジェスト）

起動直後は「はじめに」ホームが開きます。左メニューは3つのグループに分かれています。

- **はじめに**: ホーム（できること・現在のベースパス・代表操作への導線）と、使い方マニュアル。
- **戦闘ツール**: 共通変数（戦闘定数）エディタと、火力シミュレータ。
- **アイテム系config**: TrinityForge / ArsPaper の各設定ファイル。

アプリ内の「使い方マニュアル」に、起動方法・ベースパス・各configの意味・アイテム追加の流れ・固有/ランダムステの付与・CustomModelData・レシピ追加・共通変数/シミュレータ・バックアップと復元・トラブルシュートまでを、平易な日本語で解説しています。まず迷ったらこのページを開いてください。

### 日本語表示について（表示専用）

画面のラベルは日本語で表示され、元の英字キー（YAMLのキー名）はツールチップや小さな補助テキストとして併記されます。ステータス名（例: `crit-chance` → 会心率）、Material名（例: `DIAMOND_SWORD` → ダイヤモンドの剣）、列挙値（`bind-type` / `applies-to` など）も日本語化されます。

- **保存されるYAMLのキー/値は一切変わりません。** 日本語化は表示層だけの機能です。
- Material名の日本語は `lib/material-labels-ja-1.21.11.json`（公式 ja_jp 由来）を正とし、あれば ArsPaper の `ja_items.properties` で上書きします。候補一覧は Paper API 1.21.11 の Material 全件（`lib/materials-1.21.11.json`、約1600件）です。UI は部分一致サジェストです。
- 表示辞書の場所: フロントは `public/js/labels.js`、Material名はサーバの `GET /api/material-labels`（`lib/materialLabels.js`）。

## ベースパスの設定

編集対象ファイルの探索起点（ベースパス）は `tool-config.json` の `basePaths` で管理します。既定はこのリポジトリ内の resource ディレクトリを指します。

```json
{
  "port": 8787,
  "host": "127.0.0.1",
  "basePaths": {
    "trinityforge": "../../TrinityForge/src/main/resources",
    "arspaper": "../../fork-handoff/arspaper/fork/src/main/resources"
  },
  "deployPaths": {
    "trinityforge": "D:/game/minecraft/PaperServer/TrinityForge/plugins/TrinityForge",
    "arspaper": "D:/game/minecraft/PaperServer/TrinityForge/plugins/ArsPaper"
  }
}
```

- 相対パスは `tools/config-editor/` を起点に解決されます。絶対パスも指定可能です。
- 画面上部のパス欄からも編集・保存できます（「パス保存」ボタンで `tool-config.json` に書き戻します）。
- **保存時**: まず `basePaths`（リポジトリ SoT）へ書き込み、続けて `deployPaths`（稼働サーバの `plugins/<Plugin>`）へ同じ YAML をミラーします。`deployPaths` が空／未設定ならミラーはスキップします。
- ゲーム内への反映には `/trinityforge reload` や `/ars reload` など、各プラグインの再読込が必要な場合があります（ファイルを置いただけではメモリ上の設定は変わりません）。

### 実サーバーの plugins を直接編集する場合

SoT をリポジトリに残したくない／常にサーバ側だけを触る場合は、`basePaths` 自体を plugins フォルダに向けても構いません（このとき `deployPaths` は同じパスだとスキップされます）。

1. 画面上部（または `tool-config.json`）の `trinityforge` を実サーバーの `plugins/TrinityForge` に設定します。
2. `arspaper` を `plugins/ArsPaper` に設定します。
3. 「パス保存」を押すと一覧が再読込され、各 config の「検出 / 未検出」が更新されます。

注意: resource ディレクトリ内のパス構成（`stats/…`, `items/…` 等）を前提にしています。実サーバー側でも同じ相対配置であることを確認してください。

## バックアップ

保存処理は **検証 → バックアップ → 書込** の順で行います。まずスキーマ検証を通過してから、書き込みの直前に同じディレクトリへタイムスタンプ付きのバックアップを作成します。

- 形式: `<元ファイル名>.bak-YYYYMMDD-HHMMSS-mmm`（例: `item-stats.yml.bak-20260716-143012-482`）。ミリ秒まで含め、万一同名が存在する場合は末尾に連番を付けて再採番するため、同一秒の連続保存でも上書き衝突しません。
- 既存ファイルが無い（新規作成）の場合はバックアップを作りません。
- バックアップは削除されません。不要になったら手動で削除してください（`.gitignore` で `*.bak-*` は追跡対象外）。

保存に失敗した場合やスキーマ検証で弾かれた場合は、ファイルには一切書き込みません（検証NG時はバックアップも作成しません）。

## コメント保持の挙動と注意

YAML のコメント、特に日本語の説明コメントを壊さないことを重視しています。

- **ファイル先頭のヘッダコメントブロック（先頭に連続する `#` 行と空行）は必ず保持します。** 保存時に生ファイルからヘッダを退避し、再生成した本文の先頭へ再付与します。
- **本文途中（各エントリの間など）のインラインコメントは失われる可能性があります。** フォームでは構造そのもの（エントリの追加・削除・並び替え）を編集できるため、途中コメントを機械的に正しい位置へ戻すことができないためです。
- 万一コメントが失われても、保存前バックアップから復元できます。重要なインラインコメントを保持したい場合は、保存後に生成された `.bak-*` と見比べて手動で補完してください。

## 同時編集と未保存変更

- 保存対象は、読み込み時点から自分が変更した config のみに限定されます。未編集のまま「保存」を押した場合はPUTせず、サーバの最新内容を再読込します。
- 他の編集者が先に保存していた場合は、3-wayマージ、最新内容の再読込、キャンセルを選択できます。他の編集者の変更を破棄する強制上書きはUIから実行できません。
- 画面移動時の警告は、自分のローカル変更が残っている場合だけ表示されます。
- 折りたたまれたカードは、三角ボタンのほか、ボタンや入力欄を除くヘッダ部分のクリックでも展開できます。

## 対応 config 一覧

**全16 config が専用フォーム化されています**（汎用ツリーエディタは未知 schema のフォールバックとしてのみ残存）。各フォームは既知キーを専用UIで編集し、未知キーは温存・キー順序を維持します（無編集保存で `getData()` は原文と deep-equal）。

### TrinityForge

| id | ファイル | schema | 内容 / 主なUI |
|----|----------|--------|---------------|
| item-stats | `stats/item-stats.yml` | item-stats | Material#CMD キー + fixed / random / **per-quality**（品質1段あたりの増分）ステ + キー絞り込み**検索欄** |
| quality | `stats/quality.yml` | tf-quality | 品質定義（基本）: 最大品質・分布σ等のスカラー小フォーム |
| craft-quality | `stats/craft-quality.yml` | tf-craft-quality | 品質定義（クラフト/ドロップ）: mode / drop / ars-smithing のスカラー群のみ（category-skill / fishing は削除済み機能）。quality.yml と対で「品質定義」グループを構成 |
| quality-tiers | `stats/quality-tiers.yml` | tf-quality-tiers | name + color（**色ピッカー**、gradient は raw 保護） |
| lore | `stats/lore.yml` | tf-lore | layout + stats表示定義 + ステータス別の乗算レイヤ（`{id,name,stat}`）。レイヤはアイテムステ／SkillTreeバフの乗算モードから参照 |
| tool-enchants | `stats/tool-enchants.yml` | tf-tool-enchants | enchant（datalist）/ applies-to（チェック）/ quality-thresholds |
| item-categories | `stats/item-categories.yml` | tf-item-categories | Material picker + カテゴリチェック |
| catalog | `items/catalog.yml` | catalog | display-name=**リッチ着色(MiniMessage)**+**プレビュー** / use-skill=**セレクト** / **lore**（フレーバー説明文、リッチ着色+プレビュー反映） |

### ArsPaper

| id | ファイル | schema | 内容 / 主なUI |
|----|----------|--------|---------------|
| armors | `armors.yml` | armors | name_color/color=**色ピッカー** / lore=**リッチ着色(&)**+部位**プレビュー** |
| items | `items.yml` | ars-recipes | **2エリア**（アイテムレシピ / 儀式エフェクト）+ 作業台**3×3グリッド** + 儀式UI + 検索フィルタ |
| materials | `materials.yml` | ars-materials | base_material / CMD / display_name(**GUI/簡易リッチ入力**) / **lore(リッチ着色)** / 儀式レシピ + プレビュー。折りたたみカードの**ドラッグ並べ替え**、表示タブセレクトで**カタログ⇄素材のファイル跨ぎ移動**(表示名/loreは &コード⇄MiniMessage を自動変換、保存時に両ファイルへ書き込み)に対応 |
| threads | `threads.yml` | ars-threads | display_name / 効果パラメータ / stackable⇔max 連動 / 儀式レシピ |
| glyphs | `glyphs.yml` | ars-glyphs | tier=**セレクト** / mana-cost / params / max-augments / unlock-cost + 検索フィルタ |
| spellbooks | `spellbooks.yml` | ars-spellbooks | spell-books 配列（**順序=ティア段階**）。id / display-name / name-color=**色ピッカー(hex)** / max-slots / max-glyph-tier / custom-model-data / upgrade-from=**セレクト**、上下移動 |

### 汎用エディタ（フォールバック）

未登録 schema 用のフォールバックとして JSON ツリーエディタ（`generic.js`）を残しています。マップ／配列／スカラーの追加・削除・型変更に対応。P5 で **コレクションの開閉**（見出しクリック、深さ2以降は初期折りたたみ）と、常時表示だった**型セレクトの「⋯」メニュー退避**を追加しています。

## 色・プレビュー・スキルセレクト（P0〜P5 の共有基盤）

- **色ユーティリティ / 部品** `public/js/colors.js`（純関数 + UI 部品、依存なし）
  - `colorPickerInput(value, mode, onInput)` — `mode`: `legacy`（&コード16色）/ `hex`（#RRGGBB）/ `legacy-or-hex` / `mm-color`（MiniMessage 色名 or #hex）。16色パレット + 任意色（`<input type=color>`）+ gradient 等の raw 保護。
  - `richTextInput(value, mode, onInput)` — `mode`: `legacy` / `minimessage`。contentEditable で **選択範囲を右クリック→パレット**で部分着色。装飾コード等を含みロスレス不能な値は生テキスト編集へフォールバック。
  - `buildTooltipPreview()` — `{element, update({name, nameMode, loreLines, loreMode, prefix?})}`。ゲーム内 tooltip 近似（MiniMessage gradient は1文字補間）。
  - **記法モードはフィールドごとに固定**（全体を1記法へ正規化しない。プラグインのパーサが異なるため既存記法を壊さない）。
- **`GET /api/skills`** — `skilltree/*.yml` の `skill:` を収集して `{skills:[{id,label?}], source}` を返す（読めない場合は実在15スキルのフォールバック）。catalog の skill セレクトに使用。

## craft-quality.yml の category-skill / fishing について

TF本体側で `stats/craft-quality.yml` から `category-skill:`（カテゴリ→生産スキル対応表）と `fishing:`（釣り品質）が削除されました（category-skillはJava側でハードコード化、fishingはロッド駆動 `stats/gathering.yml` へ移行）。config-editor 側もこれに追従し、craft-quality フォームからは `category-skill` / `fishing` の入力欄を除去し、残る `mode.*` / `drop.*` / `ars-smithing.exp-per-craft` のみを編集します。id `craft-quality` は id `quality` と同じ「TrinityForge」グループ内で隣接配置され、ラベルもそれぞれ「品質定義（基本）」「品質定義（クラフト/ドロップ）」として「品質定義」ファミリーであることが分かるようにしています（ファイル自体は `quality.yml` / `craft-quality.yml` のまま別々です）。

## 補足

- stat 名の候補は `lore.yml` のキーから動的に取得します（未作成の場合は内蔵のフォールバック一覧を使用）。
- SkillTree のノード／プレステージのバフは加算モード（`buffs`）と乗算モード（`multipliers.<layer>.<stat>`）に対応します。乗算レイヤは `lore.yml` で同じ基準ステータスに定義済みである必要があり、未定義・基準ステ不一致は保存時に拒否されます。
- Material 候補は主要な武器／防具／触媒素材を内蔵しています（`public/js/materials.js`）。候補はドロップダウン（datalist）で、一覧に無い値も手入力できます。
- 数値・範囲の検証（`min <= max`、整数フィールド、`bind-type` / `applies-to` の許可値）はサーバ側でも実施し、不正な場合は保存せず 400 とエラー詳細を返します。フロントは 400 の `details` を「見出し＋箇条書き」で読みやすく表示し、エラートーストはクリックするまで消えません。

## 構成（開発者向け）

```
tools/config-editor/
├── server.js               Express サーバ (API + 静的配信)
├── tool-config.json        port / host / basePaths
├── lib/
│   ├── registry.js         編集対象 config の論理定義 (id/label/group/base/rel/schema)
│   ├── schema.js           保存前スキーマ検証 (全16 schema + generic フォールバック)
│   ├── yamlio.js           YAML 読み書き + 先頭ヘッダコメント保持
│   ├── constants.js        共通変数(戦闘定数)の集約取得/部分更新/検証
│   └── materialLabels.js   Material名→日本語辞書 (ja_items.properties + フォールバック)
└── public/
    ├── index.html
    ├── style.css
    └── js/
        ├── util.js         DOM ヘルパー + 入力ウィジェット + ラベル/ヒント補助
        ├── colors.js       色ユーティリティ + colorPickerInput / richTextInput / buildTooltipPreview
        ├── labels.js       表示専用の日本語ラベル辞書 (ステ/フィールド/列挙値) と参照ヘルパー
        ├── materials.js    Material候補・列挙値の定数
        ├── forms.js        専用フォーム (item-stats/catalog/armors)
        ├── recipes.js      ArsPaper items.yml フォーム (2エリア + 3×3グリッド + 儀式UI)
        ├── ars-forms.js    ArsPaper materials/threads フォーム
        ├── tf-forms.js     TF 小型フォーム群 (quality/craft-quality/quality-tiers/attribute-map/tool-enchants/item-categories)
        ├── tf-lore.js      TF lore フォーム (layout + stats テーブル)
        ├── ars-p4.js       ArsPaper glyphs フォーム
        ├── ars-spellbooks.js  ArsPaper spellbooks.yml フォーム (ティア配列、順序=段階)
        ├── generic.js      汎用JSONツリーエディタ (フォールバック。開閉 + 型メニュー)
        ├── constants.js    共通変数エディタ
        ├── simulator.js    火力シミュレータ
        ├── home.js         「はじめに」ホーム画面 (新フォームへの導線カード)
        ├── manual.js       「使い方マニュアル」画面
        └── app.js          画面遷移・API呼び出し・サイドバー・保存前コメント消失警告
```

日本語化は表示層のみで完結します。`labels.js` の辞書と `materialLabels.js` の Material 辞書を参照して各ウィジェットに日本語ラベル/ヒントを付与しますが、フォームの `getData()` が返す値（＝保存されるYAML）には一切影響しません。

### API エンドポイント

| メソッド | パス | 内容 |
|----------|------|------|
| GET | `/api/settings` | ベースパスの現在値（絶対解決・存在有無つき） |
| PUT | `/api/settings` | ベースパスを更新して `tool-config.json` に保存 |
| GET | `/api/material-labels` | Material候補一覧 + 日本語辞書（表示専用）。`materials` / `labels` / `version`(1.21.11)。`source` に参照元 |
| GET | `/api/skills` | スキル系統一覧（`skilltree/*.yml` の `skill:` を収集）。use-skill セレクト用。読めなければ実在15スキルのフォールバック |
| GET | `/api/configs` | 編集対象 config の一覧（存在有無つき） |
| GET | `/api/config/:id` | 単一 config の取得（未作成なら空） |
| PUT | `/api/config/:id` | 検証 → バックアップ → 原子的書込で保存 |
| GET | `/api/constants` | 共通変数（複数YAML横断の戦闘定数）の集約取得 |
| PUT | `/api/constants` | 共通変数の検証 → 各ソースへバックアップ付き保存 |

## 検証（オフライン）

依存追加なし・実ブラウザ無しの制約下で、次の自動検証を実施しています。

- **DOMシムによる全17 config 検証**: 軽量 DOM シム上で各 `build*Form` を実行し、(a) 例外ゼロ (b) `getData()` が元データと deep-equal（ロスレス）を全17件で確認。代表フォーム（recipes/materials/glyphs/catalog/armors）は「追加」ボタン発火後も `getData()` が妥当であることを確認。
- **フルサーバ往復**: 8799 で起動し、全17 config を GET →無編集 PUT →再 GET が deep-equal、`/api/configs`=17、`/api/skills`=15 を確認。
- **`node --check`**: 全 JS（server / lib / public/js）が構文 OK。

### 手動ブラウザ確認チェックリスト

DOM レンダリングと入力操作は依存追加不可のため自動化できません。初回に一度、実ブラウザで次を確認してください（アプリ内「使い方マニュアル」の同名セクションにも掲載）。

- [ ] **色ピッカー**: 防具の「名前の色」ボタンでパレットがポップアップし、色選択で反映。外側クリック / Escape で閉じる。
- [ ] **lore 右クリック着色**: 説明文の一部を選択→右クリックでパレット→選択範囲だけ着色。右端「色」ボタンでも同じ。
- [ ] **tooltip プレビュー反映**: 表示名 / lore を編集するとプレビューの文字・色がライブ更新。防具は部位セレクトで名前が変わる。
- [ ] **レシピグリッド操作**: items のレシピで 3×3 グリッドに素材配置、儀式の台座リスト追加・個数指定。
- [ ] **各セレクトの選択**: use-skill / method / type / effect-type / format / operation / tier などのプルダウン。
- [ ] **保存トースト + コメント消失警告**: 保存で成功トースト表示。items/materials/threads/glyphs の初回保存で確認ダイアログ。
