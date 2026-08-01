# y4 — U13 editor のモブ定義でカスタムアイテムをセレクトできない（修正）

ブランチ: `work/y4-editor-mobitems-u13`（ベース `aa97412`）

## 1. 裏取り（調査済みの根本原因を実コードで確認した結果）

指示にあった `tools/config-editor/lib/registry.js:118` の `mob-types` = `combat/mob-types.yml` で正しい。
この画面でアイテムを指定する欄は **`drops[].material` の 1 箇所だけ**（`mob-profiles` / `mob-import` は
`tf-dungeon-forms.js` に `materialInput` が 1 つも無い＝アイテム欄を持たない）。**「装備」の欄は存在しない**
（mob-types.yml にも Java にも equipment の概念は無い）。指示文の「ドロップ／装備」は drops の 1 欄に集約される。

原因は 3 つあり、**editor だけを直すと今より悪い状態（保存できるのにドロップしない）になる**ことも確認した。

| # | 場所 | 内容 |
|---|---|---|
| 1 | `public/js/mob-forms.js:200`（旧行番号）`buildDropRow` | `window.materialInput(...)` の第4引数 `opts` を渡していない＝`allowCustom:false`。`util.js:118-134` の `if (allowCustom) { for (const k of customCatalog()) ... }` を通らないので **候補に `custom:<ID>` が構造的に 1 件も入らない** |
| 2 | `public/js/util.js:138-150` `normalizeCommitValue` | `allowCustom` が false だと `custom:` 分岐を通らず `raw.toUpperCase().replace(/[^A-Z0-9_]/g,"")` に落ちる。**「＋ 直接入力…」で `custom:tf_scrap` と打っても `CUSTOMTF_SCRAP` へ潰れる**＝手入力の回避路も塞がっていた |
| 3 | `MobTypesConfig.java:408`（旧）/ `MobDropEntry` / `MobTypeDropListener` | Java 側に `custom:` 分岐が無い。`MobDropEntry` は `Material` 必須（`requireNonNull`）、リスナーは `new ItemStack(drop.material(), count)` で `CrossPluginItemResolver` を持っていない。**editor だけ直すと起動ログの `WARNING ... invalid; skipped` にしか出ない無言失敗** |

補足（指示文の前提の訂正）:

- **「editor の候補源は catalog.yml と materials.yml の 2 本だけ」という制約は本件には当たらない。**
  `public/js/catalog-candidates.js:41-45` の `EXTRA_SOURCES` と `app.js:329-339` で functional-items /
  sourcejars / catalysts が既に積まれている（2026-07-30 に解消済み）。現在も構造的に出ないのは
  **threads.yml のスレッド**（`ars-loot-tables` 画面だけ `app.js:295-309` で個別に追加）と
  **spellbooks.yml の魔導書**（yml に base material が無い）の 2 本だけ。今回の対象は catalog + materials +
  extra の共通候補（`window.CUSTOM_ITEM_CANDIDATES`）で足りるので、**制約に触らずに直せた**。
- 兄弟 3 テーブルとの対比: `mob-overrides.yml drops[].item` と `mob-level-table.yml add-drops[].material`
  は既に `custom:` 対応済み。`mob-level-table.yml remove-drops[]` は「バニラドロップを Material で消す」
  機能なので **Material 限定が仕様として正しい**（Java も `Set<Material>` でしか読まない）。

## 2. 判断した設計（方針A: 両側を直す）

「editor だけ直すと保存できるのにドロップしない」を避けるため、**Java 側を先に入れ、そのうえで editor を開けた。**
既存の兄弟実装（`MobOverridesConfig` / `MobOverrideDropEntry` / `MobOverrideDropListener`）の形を
**そのままコピー**し、新しい機構は 1 つも作っていない。

### Java（4 ファイル）

- `TrinityForge/src/main/java/com/trinityforge/mobs/MobDropEntry.java`
  `record MobDropEntry(Material material, String catalogId, double chance, int min, int max, Integer quality)` へ拡張。
  `material`/`catalogId` は**排他必須**、`catalogId` は blank 禁止（`MobOverrideDropEntry` と同一の不変条件）。
  **旧 5 引数コンストラクタを `catalogId=null` 委譲で残した**（既存テストが多数 `new` している）。
  `ofMaterial` / `ofCatalog` / `isCustom()` を追加。
- `TrinityForge/src/main/java/com/trinityforge/config/domains/MobTypesConfig.java`
  `parseDrops` を `MobOverridesConfig#parseDrops:762-789` と**同一の判定順**にした:
  trim → `regionMatches(true, 0, "custom:", 0, 7)` なら catalogId（**空なら warn + skipped++**）→
  それ以外は従来どおり `Material.valueOf(toUpperCase)`。`CUSTOM_PREFIX` はこのクラスの private 定数
  （共有 seam を作らないのが既存の流儀）。`requireDouble`/`requireInt` の第4引数を
  `Material material` → `String itemToken` に変えた（custom 側で `material` が null になり、
  警告文が `null` になってしまうため）。
- `TrinityForge/src/main/java/com/trinityforge/listeners/MobTypeDropListener.java`
  `CrossPluginItemResolver` を**追加コンストラクタ（7 引数 public）で受ける nullable フィールド**にした。
  custom エントリは `itemResolver.create(catalogId)` → `setAmount(count)`。解決不能はその 1 抽選だけ捨てて
  WARNING（fail-open。兄弟 2 リスナーと同じ契約）。**個数 0 の抽選も捨てる**。
  **品質刻印（`itemFactory.stamp`）は Material のときだけ**（カタログ品は resolver 内の
  `ItemFactory#create` が既に rollSeed/品質を打つので、重ねると上書きになる）。
- `TrinityForge/src/main/resources/combat/mob-types.yml`
  ヘッダの `material: アイテムのMaterial名 (必須)` を `Material名 または custom:<カスタムアイテムID>` に更新
  （日本語のまま。**ヘッダコメントなので editor 保存で消えない領域**）。`quality` が custom には効かない旨も追記。

**なぜ resolver を必須引数にしなかったか**: `TrinityForge.java` は「1 波に 1 人」の choke file で、
本レーンでは触ってはいけない。必須にすると 902 行目がコンパイルエラーになり、レーンが自己完結しない。
代わりに **未配線のまま custom: ドロップを引いたら専用の WARNING を 1 回出す**ようにして、
「無言で出ない」状態を消した（下の §5 に配線 1 行を書いた）。

### editor（2 ファイル。**ミラーは `lib/schema.js`（検証側）↔ `public/js/mob-forms.js`（UI 側）の 2 本**）

- `public/js/mob-forms.js` `buildDropRow`
  `window.materialInput(..., { allowCustom: true })` にした。**候補は新しく取りに行かない** —
  `app.js:668` の共通入口 `ensureCustomItemCandidates()` が既に `window.CUSTOM_ITEM_CANDIDATES` を
  埋めており、`util.js:76-78` がそこをフォールバック候補源にしている（`app.js:741 case "tf-mob-types"` は無変更）。
  ラベル/説明も add-drops 側と揃えて **日本語**にした（`素材(Material/custom:)` / 説明に
  `Material名 または custom:<カタログID>`）。`quality` の説明に「custom: には効かない」を追記。
- `lib/schema.js` `validateMobDrops`
  従来は「非空文字列なら何でも OK」だったので **custom: が Java に届かないドリフトを検証でも検知できなかった**。
  `isValidMobDropItemToken()` を新設し、**Java の受理集合をそのまま写した**:
  `custom:` 始まり（大小無視・trim）なら ID が空でなければ OK / それ以外は `/^[A-Za-z0-9_]+$/`
  （Java は `toUpperCase()` してから `Material.valueOf` なので**小文字も通す** — ここを Java より狭めると
  「Java では動くのにエディタでは必ず検証エラー」という逆向きのドリフトになる）。

`public/js/` 側に検証のミラーは無い（`grep "である必要があります" public/js` は 0 件、
`lib/schema.js` の `module.exports` は `validate` のみ）。`constants.js` は**触っていない**（他レーン所有）。

## 3. テスト結果（実走。before/after を比較）

### editor（`cd tools/config-editor && npm test`）

`node_modules` が worktree に無いため、本体リポジトリの `node_modules` へジャンクションを張って実行した
（`tools/config-editor/.gitignore:1` で ignore 済み＝commit されない）。

| | tests | pass | fail | skipped |
|---|---|---|---|---|
| **before**（`aa97412` そのまま） | 854 | 833 | **20** | 1 |
| **after**（本レーンの変更後） | 864 | 843 | **20** | 1 |

**自分が増やした失敗 0 件。** 失敗テスト名の集合は before/after で `diff` して**完全一致**（`IDENTICAL_FAILURE_SET`）。
既存 fail 20 件（他セッションの WIP と worktree にフォークが無いことに由来）:

```
FunctionalItemConfig.java が読める(パスが壊れていないこと)
Java MATERIAL_OVERRIDE_ALLOWED と JS MATERIAL_EDITABLE_IDS が完全一致する
Java の form NamespacedKey と JS SPELL_FORMS の id 集合が完全一致する
basePaths 配下の全 yml が registry に登録されているか、明示的な許可リストに載っている
buildSkillExpForm: use-level-scaling.per-level の追加スキルも許可リスト無しで編集できる
com/arspaper/spell/form ディレクトリが読める(パスが壊れていないこと)
player wiki generator creates the planned pages from the shipped settings
player wiki generator keeps internal item references out of published text
player wiki generator presents each item with a recipe grid and scannable sections
registry に登録されているエントリは全て実ファイルが存在する(パスtypo/削除の検知)
test\disassembly-defaults.test.js
カタログで表示タブを持つアイテムは同じタブのカテゴリにも所属する
ソースジェム防具と魔法系防具だけが魔法防御を持つ
ロスレス: mining-gimmick.yml 実データを読み込んで何も操作せず保存しても tiers は変形しない
木～ネザライトのツール斧は戦斧と異なるソースジェム式レシピを持つ
木～ネザライトの斧は戦斧として武器へ移し、バニラレシピを無効化する
木～ネザライトの槍は素材別ステータスと槍カテゴリを持つ
正規表現が現行Javaの並びからサンプル抽出できる(self=self, projectile=projectile)
許可リストの各エントリは実際に basePaths 配下に存在する(死んだ許可リスト行の検知)
金ツールは耐久値もランダムロールする
```

skipped 1 件は before から変わらず（本レーンの追加分の skipped は 0）。

新規テスト `tools/config-editor/test/mob-types-custom-drops-2026-08-01.test.js`（10 件、全 pass）:

```
✔ mob-types の drops 行は allowCustom:true でセレクトを組む (カスタムアイテムが候補に入る)
✔ mob-types の drops 行は custom: の現在値をそのまま表示に持ち込む
✔ mob-types の drops 行のラベルは日本語で custom: も選べることを明示する
✔ allowCustom:true のセレクト候補には日本語表示名つきの custom:<ID> が入る
✔ allowCustom:true なら custom: の手入力が大文字化で潰されない
✔ mob-forms.js の materialInput は Material限定が仕様の欄以外すべて allowCustom:true
✔ MATERIAL_ONLY_FUNCTIONS のホワイトリストに死んだ行が無い
✔ schema: drops[].material は custom:<ID> を受け付ける
✔ schema: drops[].material はバニラ Material 名を従来どおり受け付ける
✔ schema: ID が空の custom: と不正トークンは弾く
ℹ tests 10 / pass 10 / fail 0 / skipped 0
```

**RED 確認済み**: `buildDropRow` の `{ allowCustom: true }` を外して実走すると
`pass 5 / fail 3`（allowCustom を渡していない旨で落ちる）。その後ファイルを復元し `pass 10 / fail 0` に戻した。

両方向ドリフト検知の中身: `mob-forms.js` の `window.materialInput(` 呼び出しを丸括弧対応で切り出し、
IIFE 直下の関数名で分類して「Material 限定が仕様の関数（`buildRemoveDropsBox`）以外は必ず
`allowCustom: true`」「ホワイトリストに死んだ行が無い」「呼び出し関数の集合が変わっていない」を固定する。
行頭コメントは除去してから走査する（説明コメントに `materialInput({ allowCustom: true })` と
書いてあるだけの行を呼び出しとして数えると本物の欠落を隠す）。

### TF 本体（`./gradlew test --offline`）

対象クラスだけ:

```
name="com.trinityforge.mobs.MobDropEntryTest"                tests="9"  skipped="0" failures="0" errors="0"
name="com.trinityforge.mobs.MobTypeDefinitionTest"           tests="6"  skipped="0" failures="0" errors="0"
name="com.trinityforge.config.domains.MobTypesConfigTest"    tests="28" skipped="0" failures="0" errors="0"
name="com.trinityforge.listeners.MobTypeDropListenerTest"    tests="7"  skipped="0" failures="0" errors="0"
BUILD SUCCESSFUL
```

**`skipped="0"`（MockBukkit の SKIPPED 化けは無し）。**

全体:

```
2989 tests completed, 3 failed, 2 skipped
LegacyValhallaRuntimeContentTest > operationalConfigsContainNoNonGuiValhallaItemsRecipesOrCmds() FAILED
SpellBreakMarkerDriftTest > forkActuallySetsAndRemovesTheMarkerAroundCallEvent() FAILED
SpellBreakMarkerDriftTest > forkAndTrinityForgeAgreeOnTheMarkerKey() FAILED
OfflineMobImportRunner > offline: convert a live custombosses tree into mob-profiles.yml ... SKIPPED
NativeProgressionStabilizationContractsTest > prestigeRefundUsesLiveYamlCost_... SKIPPED
```

失敗 3 件は**指示に明記された既知分そのまま**（worktree にフォークが存在しないため）。
skipped 2 件はどちらも**明示的な `@Disabled` / 環境変数ゲート**（`OfflineMobImportRunner` は
env 3 本が揃ったときだけ走る手動ランナー、もう 1 件は `@Disabled("Pre-schema-v2: ...")`）で、
MockBukkit の未実装 API 由来ではない。**自分が触ったクラスの skipped は 0。**

追加した Java テスト:

- `MobDropEntryTest`: `ofCatalog`/`ofMaterial`/`isCustom`、material と catalogId の**排他必須**、blank ID 拒否
- `MobTypesConfigTest`: `custom:tf_scrap` が `catalogId` に入り `material` は null／
  接頭辞の大小無視・trim（`"  CUSTOM: tf_scrap  "`）／`custom:` だけの行は skipped++ で他の行は生きる
- `MobTypeDropListenerTest`: resolver が抽選個数で組む／解決不能は 1 件だけ捨てて他は残る／
  **resolver 未配線でも Material のドロップは従来どおり出る**／custom 品に `stamp` を重ねない

`./gradlew test` が書き換えた `ops/reports/*.md` 2 本は **`git checkout` で戻した（commit していない）**。

## 4. 残した未対応と理由

- **`TrinityForge.java` の配線 1 行は入れていない**（choke file。指示で禁止）。→ §5。
  **この 1 行が入るまで、editor で custom: を保存しても実機ではドロップしない**（ただし起動ではなく
  「該当モブを倒した瞬間」に WARNING が 1 回出る）。
- **稼働サーバの `combat/mob-types.yml` には yml コメントの更新が届かない。**
  `ConfigDomain` は `plugin.saveResource(PATH, false)` なので既存ファイルを上書きしない。
  運用へ届けるには editor 経由の保存（`server.js` の `mirrorToDeploy`）か手動コピーが必要。
  なお**機能自体は jar だけで有効**（yml の変更はコメントのみ）。
- **threads.yml のスレッドと spellbooks.yml の魔導書は今回も候補に出ない。**
  `catalog-candidates.js:38-40` に理由が明記されている構造的制約（yml に base material が無い）で、
  制約ごと直すのは本件のスコープ外＝**指示どおり報告に書いて止めた**。
  mob ドロップでスレッドを出したい要望が来たら別レーンで（`addThreadCustomCandidates()` の
  呼び出しを共通入口へ移すのが最小手）。
- **`mob-level-table.yml remove-drops[]` は Material 限定のまま**（仕様として正しい）。
  新規テストのホワイトリストにその根拠を書いて固定した。
- `items/catalog.yml` / `stats/item-stats.yml` / `stats/lore.yml` / `resourcepack/**` / `ops/reports/*.md` /
  `fork-handoff/` は**一切触っていない**。

## 5. 統合時に必要な作業

1. **`TrinityForge.java:901-904` の配線に引数 1 つ追加（これだけ）**:

   ```java
   MobTypeDropListener mobTypeDropListener =
           new MobTypeDropListener(configManager.mobTypes(), configManager.craftQuality(),
                   configManager.quality(), itemFactory, mobDropBonusSource,
                   configManager.itemStats(), crossPluginItemResolver);   // ← 末尾に追加
   ```

   `crossPluginItemResolver` は同ファイル 525-526 行で既に生成済み（`MobLevelTableListener` /
   `MobOverrideDropListener` が同じインスタンスを受け取っている）ので、宣言の追加は不要。
2. マージ順の注意は無し。**本レーンは `TrinityForge.java` / `constants.js` / 他レーン所有ファイルに
   触っていない**ので、どの順でマージしても衝突しない（触ったのは下記 8 ファイル）。
3. 配備は jar のみで機能する（yml はコメント更新だけ）。`--config` を付けるなら
   `combat/mob-types.yml` を配ればヘッダの説明も更新される。

### 触ったファイル

```
TrinityForge/src/main/java/com/trinityforge/mobs/MobDropEntry.java
TrinityForge/src/main/java/com/trinityforge/config/domains/MobTypesConfig.java
TrinityForge/src/main/java/com/trinityforge/listeners/MobTypeDropListener.java
TrinityForge/src/main/resources/combat/mob-types.yml
TrinityForge/src/test/java/com/trinityforge/mobs/MobDropEntryTest.java
TrinityForge/src/test/java/com/trinityforge/config/domains/MobTypesConfigTest.java
TrinityForge/src/test/java/com/trinityforge/listeners/MobTypeDropListenerTest.java
tools/config-editor/public/js/mob-forms.js
tools/config-editor/lib/schema.js
tools/config-editor/test/mob-types-custom-drops-2026-08-01.test.js   (新規)
tmp/findings/urgent/y4-fix.md                                        (新規・このファイル)
```
