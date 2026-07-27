# 監査レポート 07: 設定エディタ(Node.js) / 出荷config(yml) / リソースパック

担当領域: `tools/config-editor/` (server.js, lib/, public/, test/, FACADES.md, README.md) / `tools/scripts/` /
`TrinityForge/src/main/resources/**/*.yml` / `resourcepack/`

対象外: TF Java本体のロジック(戦闘計算等)は他担当領域。ここでは「エディタ↔Java語彙の整合」「保存の安全性」
「バリデーション」「セキュリティ」「CMD台帳」「出荷ymlの不備」「JSのdeadcode/redundancy」の観点のみ。

---

## 所見一覧 (サマリ)

| ID | 重要度 | 分類 |
|---|---|---|
| EDT-01 | HIGH | BUG |
| EDT-02 | HIGH | BUG |
| EDT-03 | MEDIUM | BUG / UNIMPLEMENTED |
| EDT-04 | LOW | DEADCODE |
| EDT-05 | MEDIUM | BUG |
| EDT-06 | MEDIUM | REDUNDANCY / DEADCODE |
| EDT-07 | MEDIUM | COMMENT |
| EDT-08 | LOW | COMMENT / BUG(軽微) |
| EDT-09 | LOW | BUG |
| EDT-10 | LOW | UNIMPLEMENTED |

---

### EDT-01 — skill-exp.yml は現在エディタから一切保存できない (validateTfSkillExpが新フィールドに未追随)

- **重要度**: HIGH
- **分類**: BUG
- **場所**: `tools/config-editor/lib/schema.js:663-693`(`validateTfSkillExp`) / `TrinityForge/src/main/resources/stats/skill-exp.yml:13`

**事象**: `validateTfSkillExp` はルート直下の**全キー**を「スキル名→スカラーのマップ」とみなし `isPlainObject` でなければ即エラーにする。しかし実際の `skill-exp.yml` には `dungeon-only-exp: true` という真偽値のトップレベルキーが2026-07-24以降存在する(ダンジョン限定EXPのトグル)。

**根拠**:
```js
// schema.js:666-668
for (const [skill, section] of Object.entries(data)) {
  if (section === undefined || section === null) continue;
  if (!isPlainObject(section)) { errors.push(`${skill}: マップである必要があります`); continue; }
```
```yaml
# stats/skill-exp.yml:13
dungeon-only-exp: true
```
実データに対してこの検証関数を実行すると次のエラーが必ず出る(実測):
```
dungeon-only-exp: マップである必要があります
```

**影響/再現**: フロント側 `tools/config-editor/public/js/tf-forms.js:160-161,205,313-317` の `buildSkillExpForm` は `working = data`(元オブジェクトそのまま)を保持し `dungeon-only-exp` もチェックボックスとして編集対象にしている(`getData: () => working`)。`app.js:1410-1414` の保存フローは `state.editor.getData()` をそのまま `PUT /api/config/skill-exp` の `body.data` に載せる。
そのため、「スキルEXP獲得」画面で**何か1つでも値を変更して保存すると**(dungeon-only-execのトグル含む)、サーバ側の `validate("tf-skill-exp", data)` が必ず失敗し `400` が返る。未編集のまま保存ボタンを押した場合は `isConfigDataChanged` が false になり PUT 自体が発生しないため気づきにくいが、実運用で最も触りたいはずの `dungeon-only-exp` トグル自体が「トグルした瞬間に保存不能になる」状態。
`exp-display` / `level-up` セクション(いずれもオブジェクト)は `isPlainObject` を満たすため問題ないが、`dungeon-only-exp` が存在する限りこの画面は事実上ロックされている。

---

### EDT-02 — tool-config.json が「外部公開・弱いパスワード・全インターフェイス待受」のまま放置されており、ランチャーがそのまま起動する

- **重要度**: HIGH
- **分類**: BUG (運用上のセキュリティ露出)
- **場所**: `tools/config-editor/tool-config.json:4-9,19` / `tools/config-editor/start-config-editor.bat:47` / `tools/config-editor/.gitignore:1-2`

**事象**: 現在コミット/配置されている `tool-config.json` は `external.enabled: true`、`bindHost: "0.0.0.0"`、認証パスワードが `"klee"` という短い辞書的文字列になっている。

**根拠**:
```json
// tool-config.json
"external": {
  "enabled": true,
  "bindHost": "0.0.0.0",
  "auth": { "username": "admin", "password": "klee" }
},
```
```bat
:: start-config-editor.bat:47
start "TrinityForge Config Editor (server)" cmd /k "node server.js"
```
`server.js` の `resolveBinding()` はこの設定を読んで `external.enabled===true` かつパスワードが空でなければそのまま外部公開へ進む(`server.js:50-64`)。ランチャー(`start-config-editor.bat`)は `tool-config.json` を無条件に使って `node server.js` を起動するため、次にこのバッチを実行した瞬間、全ネットワークインターフェイス(`0.0.0.0`)へ、config全体(戦闘バランス・アイテム定義・ダンジョン設定等)の読み書きAPIが、平文HTTP + Basic認証(パスワード`klee`)で公開される。README(`README.md:51`)自身が「平文HTTPの注意: インターネット公開時は必ずTLSリバースプロキシ経由に」と警告している運用を、現在の設定ファイルはまさに満たしていない状態で保存されている。

**影響/再現**: LAN上または(ルータ/ファイアウォール設定次第で)インターネットから到達可能な場合、`klee`(推測可能なパスワード)で全config書き換え・CMD台帳操作・GitHub release公開(`/api/respack/publish`)まで実行できてしまう。加えて `.gitignore` には `tool-config.json` の除外が無い(`node_modules/` と `*.bak-*` のみ)ため、このリポジトリが将来Git管理下に置かれた場合、パスワードと実サーバの絶対パス(`D:/game/minecraft/PaperServer/...`)がそのまま履歴に残る経路がある。

---

### EDT-03 — 14種のconfigスキーマに専用バリデータが無く、genericフォールバック(ルート型チェックのみ)で保存される

- **重要度**: MEDIUM
- **分類**: BUG / UNIMPLEMENTED
- **場所**: `tools/config-editor/lib/schema.js:2009-2121`(`validate`のswitch) と `tools/config-editor/lib/registry.js` の各 `schema:` 定義の差分

**事象**: `registry.js` が宣言している `schema` 値のうち、以下14種は `schema.js` の `validate()` の `switch` に `case` が無く、`default` の `validateGeneric`(ルートがオブジェクトか配列であることのみを確認)にフォールバックする。

**根拠**: registry.js が使用する schema 一覧(重複除去)から `schema.js` の `case` 一覧を差し引くと以下が残る:
```
ars-ban, ars-functional-items, tf-combat-display, tf-digging-gimmick,
tf-dungeon-gates, tf-dungeon-themes, tf-farming-gimmick, tf-hate-rates,
tf-mining-gimmick, tf-mob-import, tf-mob-profiles, tf-role-buffs,
tf-villager-trades, tf-woodcutting-gimmick
```
```js
// schema.js:2117-2120
case "generic":
default:
  validateGeneric(data, errors);
```

**影響/再現**: これらの画面(ダンジョンゲート/ダンジョンテーマ/モブプロファイル/モブインポート/ヘイト倍率/村人取引/ロールバフ/各種ギミック等)はいずれも専用フォームUIを持つ(`tf-dungeon-forms.js` 等)ため通常操作では大きく壊れた値は入りにくいが、サーバ側では「文字列であるべき箇所に数値」「0〜1の範囲外の確率」等の**型・範囲チェックが一切行われない**。フォーム側にバグがあった場合や、`generic.js` の汎用ツリーエディタで直接編集した場合、不正値がノーチェックでYAMLへ書き込まれ、Java側のロード時に例外/デフォルトフォールバック/サイレントな無視につながる経路になる(Java側の実際の挙動は担当領域外のため未検証、`(要確認)`)。

---

### EDT-04 — schema.js に「configから削除済み」と明記されたスキーマ用の検証関数が4つ死んだまま残っている

- **重要度**: LOW
- **分類**: DEADCODE
- **場所**: `tools/config-editor/lib/schema.js:820-839`(`validateTfAttributeMap`) / `:696-718`(`validateTfToolEnchants`) / `:841-852`(`validateTfItemCategories`) / `:1012-1062`(`validateTfCombatDamage`)

**事象**: `registry.js` 自身のコメントが「属性マップ(attribute-map)/アイテム分類(item-categories)は Java側でハードコード化したため config から削除」「tool-enchants: 廃止」「combat-damage (旧: 戦闘ダメージタブ) は削除」と明言しているにもかかわらず、対応する検証関数 `validateTfAttributeMap` / `validateTfItemCategories` / `validateTfToolEnchants` / `validateTfCombatDamage` が `schema.js` に残存し、`switch` にも `case "tf-attribute-map"` 等が生きている(呼び出し元の `registry.js` にはこれらschemaを使うエントリが存在しない)。

**根拠**:
```js
// registry.js:53-55
// tool-enchants: 廃止。ツールエンチャントは item-stats の補助ステ...へ統合。属性マップ(attribute-map)/
//   アイテム分類(item-categories)は Java側でハードコード化したため config から削除。
```
```js
// registry.js:91-92
// combat-damage (旧: 戦闘ダメージタブ) は削除。combat/damage.yml の各フィールドは
// 「共通変数（戦闘定数）」ビュー (__constants__, /api/constants) から編集する。
```
```js
// schema.js:2060-2065, 2042-2044, 2054-2056 (switch内、対応するregistryエントリなし)
case "tf-attribute-map": validateTfAttributeMap(data, errors); break;
...
case "tf-tool-enchants": validateTfToolEnchants(data, errors); break;
...
case "tf-combat-damage": validateTfCombatDamage(data, errors); break;
```

**影響/再現**: 実害は無い(到達不能コード)。ただし約150行がメンテナンス対象として残り続け、将来「combat-damage を復活させたのか?」等の誤読を招く。README(`README.md:206`)の「tf-forms.js: TF 小型フォーム群 (quality/craft-quality/quality-tiers/attribute-map/tool-enchants/item-categories)」という記述も同じく古い実装を指しており、EDT-07と根が同じ。

---

### EDT-05 — CMD台帳スキャン対象(CMD_SCAN_IDS)が ArsPaper `config.yml` の custom-model-data を含んでおらず、RESERVED_CMDS という手書き配列で代替している

- **重要度**: MEDIUM
- **分類**: BUG (保守性リスク・ドリフト検知不能)
- **場所**: `tools/config-editor/lib/cmd-registry.js:27-33`(`RESERVED_CMDS`) / `:125-134`(`SCANNERS`/`CMD_SCAN_IDS`) / `fork-handoff/arspaper/fork/src/main/resources/config.yml:120,139,159,177,195`

**事象**: `config.yml`(registryの id `"ars-config"`、エディタで開閉・保存可能な現役config)には `custom-model-data: 200003`〜`200007` が実在する(メイジ防具セット等)。しかし `CmdRegistry.SCANNERS` は `catalog / item-stats / materials / spellbooks / external-items / sourcejars / sourcelinks` の7つしか対象にしておらず、`ars-config` はスキャン対象に含まれていない。

**根拠**:
```js
// cmd-registry.js:125-134
const SCANNERS = {
  catalog: scanCatalog,
  "item-stats": scanItemStats,
  materials: scanMaterials,
  spellbooks: scanSpellbooks,
  "external-items": scanExternalItems,
  sourcejars: scanSourceJars,
  sourcelinks: scanSourceLinks
};
```
```yaml
# fork-handoff/arspaper/fork/src/main/resources/config.yml:120,139,159,177,195
    custom-model-data: 200003
    custom-model-data: 200004
    custom-model-data: 200005
    custom-model-data: 200006
    custom-model-data: 200007
```
これを補うために `RESERVED_CMDS`(`cmd-registry.js:27-33`)へ `200002〜200007` 等が**手書きで**列挙されているが、コメント自身が「Java 側でハードコードされ、かつ『エディタが走査する TF config に現れない』CMD だけを予約する」と書いており、`config.yml` はまさに「エディタが編集する現役config」であるにもかかわらず対象外にされている実態と矛盾している。

**影響/再現**: 現状は `RESERVED_CMDS` が正しく同期されているため実害は無いが、将来 `config.yml` 側のメイジ防具CMDが変更されても `GET /api/cmd/usage` の使用状況一覧・クロスファイル衝突検査(`computeCmdWarnings`)はその変更を検知できず、`RESERVED_CMDS` を手動で追随させる以外に整合を保つ手段が無い(テストによる同期保証も無い、`gate-vocabulary-java-parity.test.js` のような parity テストが存在しない)。

---

### EDT-06 — リソースパックに孤児モデルJSONが23件(約2.1MB)残存し、配布zipに無条件で混入する

- **重要度**: MEDIUM
- **分類**: REDUNDANCY / DEADCODE
- **場所**: `resourcepack/cmd-registry.json`(allocations一覧) / `resourcepack/trinityforge-items/assets/trinityforge/models/item/*.json` / `tools/config-editor/lib/respack.js:457-498`(`buildPack`)

**事象**: `resourcepack/trinityforge-items/assets/trinityforge/models/item/` 配下に存在するモデルJSONのうち23件が、`cmd-registry.json` の `allocations[].assetName` からどれも参照されていない(孤児)。該当ファイル: `copper_long_spear.json` / `copper_morningstar.json` / `copper_spear_tf.json` / `diamond_long_spear.json` / `diamond_morningstar.json` / `diamond_spear_tf.json` / `golden_long_spear.json` / `golden_morningstar.json` / `golden_spear_tf.json` / `iron_long_spear.json` / `iron_morningstar.json` / `iron_spear_tf.json` / `netherite_long_spear.json` / `netherite_morningstar.json` / `netherite_spear_tf.json` / `stone_long_spear.json` / `stone_morningstar.json` / `stone_spear_tf.json` / `wooden_long_spear.json` / `wooden_morningstar.json` / `wooden_spear_tf.json` / `koujien.json` / `fnis_peccati_profundi.json`。実測サイズ合計は約2,118,254バイト(約2.07MB)。

**根拠**: `cmd-registry.json` には `koujien` と `fnis_peccati_profundi` の現行エントリが存在するが、`assetName` は改名後の `koujien_100004` / `fnis_peccati_profundi_68` になっている(`resourcepack/cmd-registry.json:582-585,647-650`)。つまり `koujien.json` / `fnis_peccati_profundi.json` はリネーム前(衝突回避で `resolveAssetName` が `_${cmd}` を付与する前)の旧ファイルの残骸。同様に `*_long_spear.json` / `*_morningstar.json` / `*_spear_tf.json` の21件(7素材 × 3武器種)は、対応する `allocations` エントリが1件も無く、design上の「槍/モーニングスター/spear_tf」という別武器バリエーションが後に統合・破棄された痕跡と見られる。
```js
// respack.js:479-498 (buildPack): trinityforge-items 配下を丸ごとcollectFilesし、そのままzip化する
const itemFiles = collectFiles(itemsSourceDir, itemsSourceDir);
...
const allEntries = [...skillGuiFiles, ...itemFiles];
const buf = buildZip(allEntries, outPath);
```
`buildPack()` は台帳を参照せず「ディレクトリに存在する全ファイル」を無条件でzipへ含める。

**影響/再現**: これらのファイルはどの `assets/minecraft/items/*.json` (range_dispatch) からも参照されないため機能的には完全に無意味だが、`GET /api/respack/build` → `/api/respack/publish` で毎回配布zipに同梱され続け、プレイヤー全員のダウンロードサイズを不必要に増やす。`cmd-routes.js:310-326` の `syncCmdRegistryAfterSave`(コメント: 「共有テクスチャを誤削除しない」ためPNG等の生アセットは安全側であえて削除しない設計)はconfig参照の孤児(台帳の孤児行)は掃除するが、**ファイルシステム上の孤児モデル/テクスチャ**を検出・報告する仕組みが無いため、今回のような蓄積が今後も気づかれずに増え続ける。

---

### EDT-07 — README.md / FACADES.md が現行実装から大きく乖離しており、実態と異なる情報を記載している

- **重要度**: MEDIUM
- **分類**: COMMENT (ドキュメント不整合)
- **場所**: `tools/config-editor/README.md:131-216` / `tools/config-editor/FACADES.md:1-56`

**事象**:
1. README は「**全16 config**が専用フォーム化」「**全17 config**」(`README.md:133,239`)と明記するが、`registry.js` の実際のエントリ数は60超(TrinityForge本体・スキルツリー15本・ArsPaper・進行度曲線companion等を含む)。
2. README の対応config一覧表(`README.md:135-157`)は ArsPaper `armors.yml`(schema `armors`)を現役として掲載しているが、現行 `registry.js` にはそのようなエントリが存在しない(ArsPaperセクションは `thread-sets/items/functional-items/materials/threads/glyphs/glyph-damage-boost/spellbooks/ars-config/ban/sourcelinks/sourcejars` の12件)。同様に `tool-enchants` / `item-categories` を「対応config」として掲載しているが、これらはEDT-04で確認した通り既にconfigから削除済み。
3. API一覧表(`README.md:220-233`)には `GET/PUT /api/settings` `/api/material-labels` `/api/skills` `/api/configs` `/api/config/:id` `/api/constants` の6系統しか無いが、実際の `server.js`/`cmd-routes.js` には `/api/spell-forms` `/api/gate-vocabulary` `/api/tilestate-materials` `/api/cmd/usage` `/api/cmd/allocate` `/api/cmd/allocate-bulk` `/api/respack/texture` `/api/respack/model` `/api/respack/build` `/api/respack/publish` `/api/respack/unregister` `/api/respack/prune-orphans` `/api/respack/status` `/api/respack/preview` が存在し、大半が未文書化。
4. FACADES.md の「Still incomplete / deferred」節(`FACADES.md:39-44`)は2026-07-21時点の記述で止まっており、2026-07-23以降の大改修(stat-gate-overhaul、動的ゲート方式への移行等、`registry.js` のコメントに多数登場)を反映していない。

**根拠**: 上記は `registry.js` / `server.js` / `cmd-routes.js` の実コードとREADME/FACADES.mdの記述を突き合わせた事実。

**影響/再現**: 直接の実行時バグではないが、ブリーフの「後任が作業する上で必要な注釈」の観点で、このREADMEを信じて設計判断をする(例: 「schemaは17種類しかない」という前提で新規schema追加時に一覧を見落とす)と誤った変更につながる。EDT-04と合わせ、ドキュメントの陳腐化が継続的に蓄積している。

---

### EDT-08 — CMD予約(RESERVED_CMDS)の「グローバル一意」という設計前提が実データと矛盾している

- **重要度**: LOW
- **分類**: COMMENT / BUG(軽微)
- **場所**: `tools/config-editor/lib/cmd-registry.js:252-288`(`nextCmd`) / `resourcepack/cmd-registry.json`

**事象**: `nextCmd()` のコメントは「衝突判定は安全側に『全 material の使用値(UNKNOWNを含む)』+ 予約値を対象にする」とあり、CMD値をMaterialをまたいでグローバルに一意なリソースとして扱っている。しかし実際の `cmd-registry.json` には、同じCMD番号(例: `200002`, `200003`)が `LEATHER_CHESTPLATE` / `DECORATED_POT` / `FURNACE` のように**複数の異なるMaterialで既に共有**されている(Minecraftの `range_dispatch` はMaterialごとに独立した名前空間を持つため実害は無い)。

**根拠**:
```
resourcepack/cmd-registry.json より抜粋(実データ):
  LEATHER_CHESTPLATE#200002 (mage_guardian_novice_chestplate)
  DECORATED_POT#200002      (source_jar)
  LEATHER_LEGGINGS#200003 / FURNACE#200003(volcanic_sourcelink) / DECORATED_POT#200003(creative_source_jar)
```

**影響/再現**: 実際の割当ロジック(`nextCmd`)は新規採番のときだけグローバル一意を強制するため、既存データが上記のように複数Materialで同一番号を共有していること自体は動作に影響しない。しかし「CMDはグローバルに一意であるべき」という設計コメント・実装方針そのものが、既存の意図的な共有(予約値の使い回し)と食い違っており、将来この設計意図を読んだ担当者が「なぜ同じ番号が複数Materialにあるのか」で混乱しうる。また、この保守的な一意性チェックにより、本来Material内で自由に使える番号域が他Materialの都合で「予約済み」として使用不可になるケースがある(番号空間は最大9,999,999あるため実害はほぼ無い)。

---

### EDT-09 — .gitignore が backups/ ディレクトリと server.log を除外していない

- **重要度**: LOW
- **分類**: BUG (運用上の情報露出リスク)
- **場所**: `tools/config-editor/.gitignore:1-2` / `tools/config-editor/server.log`

**事象**: `.gitignore` は `node_modules/` と `*.bak-*` のみを除外しており、`tools/config-editor/server.log`(起動ログ)や `backupDir`(既定 `../../backups/`、`server.js:86` 参照)配下の世代バックアップ自体は対象外になっている。

**根拠**:
```
tools/config-editor/.gitignore:
node_modules/
*.bak-*
```
`server.js:86-88` のコメント通り `backupDir` の既定値は `../../backups`(リポジトリ直下)であり、この直下に `.gitignore` が存在するかは今回の担当範囲(`tools/config-editor/`)外のため未確認(`(要確認)`)。少なくとも `tools/config-editor/server.log` はこのディレクトリの `.gitignore` の対象外。

**影響/再現**: `server.log` は現状「起動ログのみ標準出力へ」(`server.js:765`)なのでファイル自体に機密情報が書かれる設計ではないが、将来ログ出力が増えた場合に無警戒でコミットされる経路が空いている。

---

### EDT-10 — CMD自動生成モデルの `parent` 判定(`inferParent`)がヒント文字列の部分一致のみで、誤爆しうる

- **重要度**: LOW
- **分類**: UNIMPLEMENTED (判定ロジックの粗さ)
- **場所**: `tools/config-editor/lib/respack.js:29-30,83-86`

**事象**: `inferParent(material)` は `HANDHELD_HINTS = ["SWORD", "_AXE", "HOE", "PICKAXE", "SHOVEL", "ROD", "BOW", "SPEAR", "HALBERD"]` のいずれかを**部分文字列として含むか**だけで `"handheld"` か `"generated"` かを決めている。

**根拠**:
```js
// respack.js:30
const HANDHELD_HINTS = ["SWORD", "_AXE", "HOE", "PICKAXE", "SHOVEL", "ROD", "BOW", "SPEAR", "HALBERD"];
...
// respack.js:83-86
function inferParent(material) {
  const mat = String(material || "").toUpperCase();
  return HANDHELD_HINTS.some((hint) => mat.includes(hint)) ? "handheld" : "generated";
}
```
`"ROD"` は部分一致なので、将来 `Material` 名に `ROD` を含むが手持ちアニメーション対象ではないアイテム(例: 架空の `PERIOD_...` や `RODEO_...` のような名称、または `IRON_ROD` 系の非武器アイテム)が追加された場合、意図せず `handheld` 判定になる。現状の実在Material一覧では該当なしと見られるが、将来のカスタムアイテム追加(`external-items.yml` 等は任意のMaterialを指定可能)で誤爆しうる設計。

**影響/再現**: `parent` は明示指定(`opts.parent`)で上書き可能なため実運用への影響は小さいが、無指定でテクスチャ登録した場合にのみこの粗い推定が使われる。

---

## 検証方法メモ (再現性のため記録)

- 全86ファイルの `TrinityForge/src/main/resources/**/*.yml` + `fork-handoff/arspaper/fork/src/main/resources/**/*.yml` を `yaml` パッケージ(`uniqueKeys:true`)で走査し、重複キー・パースエラーは**0件**だった(YAML構文/重複キー起因の指摘は本レポートに含まれない)。
- `registry.js` の全エントリに対し実ファイルを読み込み `schema.js` の `validate()` を実行したところ、EDT-01 の `skill-exp` のみが実際にエラーを返した(他の格納済みconfigは現行スキーマ検証を通過する)。
- `resourcepack/cmd-registry.json` の全 `allocations`(360件)に対し、モデルJSON実在・孤児モデル・孤児テクスチャ・アイテム定義ファイルの整合を機械チェックした(EDT-06の根拠)。上記のいずれのスクリプトも監査用の一時ファイルであり、実行後に削除済み(リポジトリへの変更は残していない)。

## 未検証・スコープ外の断り書き

- `TrinityForge/src/main/resources/items/catalog.yml`(5,122行)と `stats/item-stats.yml`(8,875行)は分量が大きく、全行の手動読解までは実施していない(構造検証・スキーマ検証・CMDスキャンは実施済み)。個別エントリの値の妥当性(バランス面)は他担当領域(BALANCE)でカバーされる想定。
- Java側が EDT-03 の未検証schema群に対して実際にどこまで頑健か(不正値で例外を投げるか、黙って既定値にフォールバックするか)は本担当領域(エディタ/yml)の外であり `(要確認)` としている。
