# Y4 レビュー: U13「editor のモブ定義でカスタムアイテムをセレクトできない」

- 対象ブランチ: `work/y4-editor-mobitems-u13`
- 対象コミット: `8c0c507`（親 `aa97412` — `git log --format="%H %P"` で確認済み。ブランチもコミットも実在する）
- レビュー実施: 2026-08-01 / 実コードと実走テストで裏取り（実装担当の報告はコピペせず全部自分で回した）

## 判定

**ACCEPT_WITH_NOTES** — 実装の方向・切り分け・テストはいずれも正しく、報告の数値もすべて再現した。
ただし **HIGH 1 件（本番配線が無く実機では機能が 1 件も動かない）** が残っているため、
このブランチ単独では「U13 を直した」とは言えない。配線コミットとセットで初めて完了。

---

## 検証した事実（自分で実走した結果）

### editor (`node --test "test/*.test.js"`)

同一 worktree で、実装後 → 実装前（`git checkout aa97412 -- lib/schema.js public/js/mob-forms.js` +
新規テスト退避）の順に実走し、失敗テスト名を `diff` した。

| | tests | pass | fail | skipped |
|---|---|---|---|---|
| before (`aa97412` 相当) | 854 | 833 | 20 | 1 |
| after (`8c0c507`) | 864 | 843 | 20 | 1 |

**失敗テスト名の集合は before/after で完全一致（`diff` が空 = IDENTICAL）。自分が増やした失敗 0 件。**
残 20 件はすべて他セッション WIP 由来（catalog 表示タブ/ソースジェム防具/槍・斧の item-stats/
player wiki 生成/registry パス/disassembly/mining-gimmick ロスレス/Java-JS 語彙一致 など）で、
今回の変更ファイルとは無関係。

RED も自分で再現した: `buildDropRow` の `{ allowCustom: true }` を外すと、新規テストが
`mob-types の drops 行は allowCustom:true で…` / `…custom: の現在値をそのまま表示に持ち込む` /
`mob-forms.js の materialInput は Material限定が仕様の欄以外すべて allowCustom:true` の
**ちょうど 3 件** 落ちる。報告どおり。テストは空振りしていない。

### TF 本体 (`./gradlew test --offline`)

`2989 tests completed, 3 failed, 2 skipped`。失敗 3 件は
`LegacyValhallaRuntimeContentTest.operationalConfigsContainNoNonGuiValhallaItemsRecipesOrCmds` と
`SpellBreakMarkerDriftTest`（2 件、worktree にフォークが無いため）で、既知・想定内。
skipped 2 件は `OfflineMobImportRunner`（env ゲート）と
`NativeProgressionStabilizationContractsTest.prestigeRefundUsesLiveYamlCost_...` で、
**MockBukkit の未実装 API 由来の「SKIPPED 化け」ではない**（今回の 4 クラスは skipped=0）。

XML から直接読んだ対象クラスの結果:

| クラス | tests | skipped | failures | errors |
|---|---|---|---|---|
| `MobDropEntryTest` | 9 | 0 | 0 | 0 |
| `MobTypeDefinitionTest` | 6 | 0 | 0 | 0 |
| `MobTypesConfigTest` | 28 | 0 | 0 | 0 |
| `MobTypeDropListenerTest` | 7 | 0 | 0 | 0 |

`./gradlew test` が書き換える `ops/reports/*.md` 2 本は、**自分の実走ぶんも `git checkout` で戻した**
（worktree は clean に復帰済み）。コミットには入っていない。

### 巻き込みコミットの有無

`git show --stat 8c0c507` は 11 ファイル。Java 3 + 出荷 yml 1 + Java テスト 3 + editor 2 + editor テスト 1 +
`tmp/findings/urgent/y4-fix.md`。**他セッション WIP（`items/catalog.yml` / `item-stats.yml` / `lore.yml` /
`resourcepack/` / `ops/reports/`）には一切触れていない。`git add -A` 相当の巻き込みは無い。**

### 切り分けの妥当性

- `combat/mob-types.yml` に「装備」欄が無いのは事実（yml にも `MobTypeDefinition` にも該当キーが無い）。
- `mob-profiles` / `mob-import` の editor フォームは `tf-dungeon-forms.js:390,508` にあり、
  `materialInput` / `catalogItemSuggest` の呼び出しは 0 件（アイテム欄を持たない）。**取りこぼしなし。**
- `validateMobDrops` は `lib/schema.js:1682` の 1 箇所からしか呼ばれない。
  受理集合の緩和が他ドメインへ漏れていない。
- `tf-mob-types` は `buildEditorForLoadedConfig` 冒頭の `await ensureCustomItemCandidates()`（`app.js:667-668`）を
  必ず通るので、候補源が空にならない経路も成立している。
- `materialHintEl`（`util.js:919-938`）は既に `custom:` 分岐を持つので、行ヒントも日本語で出る。
- editor の保存経路に mob-types 用 `normalize*` は存在しない（`lib/` 側は schema 検証のみ）。
  「開いて保存しただけで意味が変わる」ドリフトは無い。
- `lib/yamlio.js:19-57` の `extractHeader` がヘッダコメントを保持するので、
  `mob-types.yml` に足した書式説明は **editor 保存では消えない**（実装担当の記載どおり）。

---

## 指摘

### HIGH

#### H1. 本番配線が無いので、この機能は実機で 1 件も動かない

- 根拠: `TrinityForge/src/main/java/com/trinityforge/TrinityForge.java:901-904`
  ```java
  MobTypeDropListener mobTypeDropListener =
          new MobTypeDropListener(configManager.mobTypes(), configManager.craftQuality(),
                  configManager.quality(), itemFactory, mobDropBonusSource,
                  configManager.itemStats());
  ```
  6 引数のままで、`TrinityForge.java:152` 宣言・`:525` 生成済みの `crossPluginItemResolver` を渡していない。
- 影響: editor で `custom:<ID>` を選べて保存でき、`lib/schema.js` も Java の `parseDrops` も通るのに、
  ドロップ時は `MobTypeDropListener.buildCustomStack`（`:183-192`）が `itemResolver == null` で
  必ず `null` を返す。**「設定できるのに永久に出ない」という、今回潰しにいった無言失敗そのものが
  形を変えて残っている。** WARNING はサーバログに 1 回出るだけで、editor 側には何も出ない。
- 実装担当も未対応事項 1 として明記しており、`TrinityForge.java` が choke file である以上この判断自体は妥当。
  ただし**成果物としては未完**なので、配線コミットとセットで初めてマージ可。
- 直し方: 上記呼び出しの末尾に `, crossPluginItemResolver` を足すだけ（7 引数 ctor は
  `MobTypeDropListener.java:190-196` に用意済み）。生成順も問題ない（525 < 901）。

### MEDIUM

#### M1. `itemResolver` を nullable にしたのは兄弟 2 本からの逸脱で、「新機構は作っていない」という報告と食い違う

- 根拠:
  - `MobOverrideDropListener.java:91` … `this.itemResolver = Objects.requireNonNull(itemResolver, "itemResolver");`
  - `MobLevelTableListener.java:98` … 同上
  - 本件 `MobTypeDropListener.java:113` … `this.itemResolver = itemResolver; // nullable`
    ＋ `:161` の `volatile boolean unwiredResolverWarned` ラッチ ＋ `:183-192` の未配線 WARNING
- 影響: 兄弟 2 本は「配線し忘れたらコンパイル/起動で気づく」設計なのに、本件だけ
  **コンパイル時保証をランタイム WARNING に格下げ**した。H1 を回避するための措置なので意図は理解できるが、
  - 配線後はこの分岐が永久にデッドコードとして残り、将来の配線退行を再び黙らせる、
  - 「mob-overrides / mob-level-table の既存形をそのままコピー、新機構は作っていない」という報告文と実物が違う、
  の 2 点で MEDIUM。
- 提案: H1 の配線を入れたら、nullable と WARNING ラッチは撤去して兄弟と同じ `requireNonNull` に揃える。

#### M2. `custom:` ドロップの `quality:` が黙って捨てられる（設定はできる／検証も通る／効かない）

- 根拠: `MobTypeDropListener.java:193` は `itemResolver.create(drop.catalogId())` を呼ぶ。
  この 1 引数版は `CrossPluginItemResolver.java:70-72` で
  `create(id, ThreadLocalRandom.current().nextLong(), 0)` — **quality は常に 0 固定**。
  一方 `MobTypesConfig#parseDrops`（`:443-446`）は `quality` を読んで
  `MobDropEntry.ofCatalog(catalogId, chance, min, max, quality)` に載せており、
  `lib/schema.js` も `custom:` 行の `quality` を弾かない。
- 影響:
  - `quality: 7` と明示的に書いても無警告で無視される（yml 上は正しく見える）。
  - mob-types の売りである**モブレベル駆動の品質決定**（`resolveQuality`）と、
    `power_mobdropbonus_add`（`bonusMode`, `:144-145`）が **custom 品にだけ一切効かない**。
    カタログ装備をモブドロップにすると常に最低品質になる。
  - 実装担当の報告 56-57 行「カタログ品は resolver 内の `ItemFactory#create` が既に rollSeed/品質を打つので
    重ねると上書きになる」は **半分だけ正しい**。打っているのは「品質 0」であって、
    上書きを避けているのではなく品質機能を失っている。
- 兄弟 2 本（mob-overrides / mob-level-table）は `quality` 欄自体を持たないので同じ穴は無い。
  **mob-types だけが「あるのに効かない欄」を持つ。**
- 提案（どちらか）: (a) `create(drop.catalogId(), random.nextLong(), qualityValue)`
  （3 引数版が `CrossPluginItemResolver.java:78` にある）を使って既存の品質決定を通す、
  (b) 通さないと決めるなら `lib/schema.js` と `parseDrops` で
  「`custom:` 行に `quality` があれば警告 or 検証エラー」にして、黙って捨てるのをやめる。

#### M3. 接頭辞をローカルで剥がすため、resolver の「custom: はバニラ Material へ落とさない」ガードが無効化される

- 根拠: `MobTypesConfig.java:446-447` で `catalogId` は接頭辞を剥がした素の ID。
  `MobTypeDropListener.java:193` はそれをそのまま渡す。
  `CrossPluginItemResolver.java:95` は `return isCustomToken(id) ? Optional.empty() : createMaterial(bare);`
  — **`custom:` 付きで渡したときだけ** Material フォールバックを止める設計（同ファイル javadoc 33-35 行に
  「`custom:DIAMOND` cannot silently become a diamond」と明記）。
- 影響: `mob-types.yml` に `custom:DIAMOND` と書くと、WARNING も出さずに**バニラのダイヤ**が出る。
  同様に、catalog から消えた ID がたまたま Material 名と衝突すると
  「解決不能 → WARNING」の契約が破れて別物が静かに出る。今回の目的（無言失敗の撲滅）に対する穴。
- 兄弟 2 本も同じ形なので**回帰ではない**が、3 箇所目を同じ形で増やしている。
- 提案: `itemResolver.create(CUSTOM_PREFIX + drop.catalogId())` にするだけで resolver 側のガードが効く
  （`stripCustomPrefix` は冪等なので副作用なし）。

### LOW

#### L1. 同一メソッド内で count<=0 の扱いが非対称になった

`MobTypeDropListener.java:180-182` は custom だけ `count <= 0` を捨てるが、
バニラ経路（`:161`）は従来どおり `new ItemStack(mat, 0)` を drops に積む。
兄弟 2 本は抽選直後に `if (count <= 0) continue;`（`MobOverrideDropListener.java:144-146`）で
**両方**を落としている。既存挙動なので回帰ではないが、今回「兄弟と同じ契約」と書いた以上は揃えるべき。

#### L2. custom 経路だけ注入乱数を通らない（テストで確定できない）

リスナーは決定性のために `SplittableRandom` を注入されているのに、custom 品のステータスロールは
`CrossPluginItemResolver.create(String)` 内の `ThreadLocalRandom`（`CrossPluginItemResolver.java:71`）から引く。
「rollSeed を引き直した」に当たる構図。兄弟同型なので許容範囲だが、
M2 を (a) で直すなら `random.nextLong()` を渡して同時に解消できる。

#### L3. `remove-drops` が custom 品を Material 一致で黙って消す

`MobLevelTableListener`（HIGH）は `MobTypeDropListener`（NORMAL）の後に走り、
`MobLevelTableListener.java:156-158` が `event.getDrops().removeIf(stack -> removeDrops.contains(stack.getType()))`
を最終リストに掛ける。カタログ品の base material（例: `IRON_SWORD`）が
どこかの帯の `remove-drops` に入っていると、**editor 上からは base material が見えないまま消える**。
既存機構の自然な延長ではあるが、custom ドロップを勧める UI を足した以上、
`mob-types.yml` ヘッダか `docs/agent-context/` に一行書いておくべき落とし穴。

#### L4. 「threads.yml は構造的制約」という報告は不正確

`app.js:295-309` に `addThreadCustomCandidates()` が既にあり、
`app.js:751-757`（`ars-loot-tables`）だけがそれを呼んでいる。
つまり mob-types でスレッドが出ないのは **1 行足していないだけ**で、構造的制約ではない。
一方 spellbooks.yml の魔導書のほうは `catalog-candidates.js:38-40` に理由（yml に base material が無い）が
実在するので、そちらの記述は正しい。報告の 199-201 行はこの 2 つを一括りにしている。
（スコープ外という判断自体は妥当。制約の説明だけが誤り。）

#### L5. `lib/schema.js` の受理集合が「緩め」から「厳格」へ変わった副作用

従来は `typeof d.material === "string" && d.material` で**非空文字列なら何でも通っていた**。
`isValidMobDropItemToken`（`lib/schema.js:1495-1503`）導入後は `custom:` 以外に `^[A-Za-z0-9_]+$` を要求する。
Java と揃える意図は正しい（`list:cobblestone` は Java に分岐が無いので通すべきでない）が、
**稼働サーバの `combat/mob-types.yml` に非 Material トークンが 1 つでも入っていると、
mob-types 画面の保存が丸ごと通らなくなる**。
出荷 `combat/mob-types.yml` には `drops:` エントリが 1 件も無いので出荷物としては無害だが、
配備前に実サーバ側の yml を 1 度 grep しておくこと。

#### L6. 恒久知識が `docs/agent-context/` に反映されていない

今回得た非自明知識（「mob 3 ドロップ表のうち mob-types だけ custom: 非対応だった」／
「`materialInput` は opts を渡さないと候補と手入力の 2 経路が同時に塞がる」）は、
サブエージェントに永続メモリが無い以上リポジトリに書くのが約束（`CLAUDE.md` / `docs/agent-context/README.md`）。
`docs/agent-context/config-editor.md` あたりに 2 行追記したい。
`reports/ACTIVE_RECORD.md` は choke file なので触っていないのは正しい。

#### L7. `tmp/` は `.gitignore` 済みなので、報告書のコミットは無視設定を跨いでいる

`.gitignore:25` に `tmp/`。`tmp/findings/urgent/y4-fix.md` は `git add -f` 相当でコミットされている。
本レビュー成果物（`tmp/findings/urgent/Y4-review.md`）も同じ扱いになるため、
ワークフローとして意図的なのか、マージ前に落とすのかを親側で決めること。

---

## 指示からの逸脱チェック（結果: 逸脱なし）

- **勝手な設定項目の追加**: 無し。新しい yml キーは 1 つも増やしていない（既存 `drops[].material` の
  受理書式を 2 種に広げただけ）。
- **`rollSeed` の引き直し**: L2 のとおり custom 経路が `ThreadLocalRandom` を引くが、
  これは兄弟 2 本と同じ既存 seam の挙動で、本件が新たに引き直しているわけではない。
- **ゲームバランスの黙った変更**: M2（custom 装備が常に最低品質・ドロップボーナス無効）が
  唯一それに近いが、出荷 yml に該当エントリが 0 件なので現時点の実バランスは動かない。
- **ミラー 2 本**: `lib/schema.js`（検証）と `public/js/mob-forms.js`（UI）を両方更新済み。
  `mob-forms.js` に `lib/` 側の対は存在せず、`constants.js` にも触れていない（choke file 回避として正しい）。
- **日本語ラベル**: `素材(Material/custom:)` ＋ 実 `util.js` を読み込んだテストで
  `primary` が日本語表示名・`secondary` が ID であることを固定済み。「ID がそのまま出る」再発なし。
- **yml コメントは日本語**: 満たしている。

## マージ条件（推奨）

1. **H1 の配線 1 行**を入れる（別コミット / choke file 担当レーンで可）。これが入るまでは実機で無効。
2. M1〜M3 は同じ 3 行の周辺で全部片付く（`create(CUSTOM_PREFIX + id, random.nextLong(), qualityValue)` に
   変えて `requireNonNull` へ戻す）ので、配線コミットと同時にやるのが安い。
3. L5 のため、配備前に稼働サーバの `combat/mob-types.yml` の `drops[].material` を 1 度確認する。
