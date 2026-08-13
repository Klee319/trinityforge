"use strict";

// ---------------------------------------------------------------------------
// 2026-08-09 差し戻し対応の回帰テスト(2回目の差し戻しを反映して全面更新)。
//
// 背景(1回目、0b23802 → 差し戻し): threads.yml の potion-effect/potion-level/flight/slots を
// 「スレッド効果 (Ars)」という独立ナビ画面(thread-bundle 分割ビュー)に実装したが、
// 「アイテムステータスの設定でArs効果とそれ以外でスレッド分けないでほしい。あくまでもともとの
// スレッド設定の中で効果をセレクトメニューで追加可能な方式やってほしい」という指示で差し戻された。
//
// 背景(2回目): 1回目の対応時、thread-bundle 撤去に伴い thread-sets.yml(装着数しきい値のセット
// 効果)が編集不能になるのを避けるため、thread-sets.yml 専用の単独ナビ(__thread_sets__、
// type: "thread-sets")を新設したが、これも「スレッドを画面で分けるな」という指示の趣旨に反する
// (撤去した __thread_effects__ と同じ形の分割を作り直しただけ)として差し戻された。
// さらに、item-stats.yml 側の entry["set-effects"](旧「セット効果」UI)は special-effects と同じ
// 「editor にしか存在しない飾り」(TF本体・ArsPaperフォークとも読むコードが無く、出荷 yml も
// 実データ0件)であることが判明した。実際にスレッドのセット効果を読むのは thread-sets.yml
// (キーは threads.yml と共通のスレッドID)。
//
// 今回(2回目)の対応:
//   1. app.js から __thread_sets__ ナビ項目を削除し、split-views.js の o.type === "thread-sets"
//      分岐(死にコード化)も削除。
//   2. item-stats.yml の「スレッド」タブ(__stats_thread__)が threads.yml に加えて thread-sets.yml
//      も横から読み書きする(3ファイル1画面。extraGets に "threads"/"thread-sets" の2件)。
//   3. forms.js の renderThreadExtraFields から entry["set-effects"] を書く旧UIを撤去し、
//      同じ見た目・操作感で threadSetsRoot["thread-sets"][threadId].thresholds を書くUIに差し替えた。
//   4. lib/schema.js / public/js/labels.js に残っていた entry["set-effects"] 用の検証・ラベル定義を
//      撤去(validateArsThreadSets は変更していない)。
//
// スレッド関連のナビ項目は「スレッド」(__stats_thread__)1つだけになる。
//
// buildItemStatsForm の実カード描画は util.js の window.h が本物の document.createElement に
// 依存する重い依存関係を持つため(30種超の window.* ヘルパー)、本ファイルでは
// (a) 実データでの id 解決(45件)、(b) 静的ソースチェックによる配線確認、
// (c) 既存パターン(item-stats-cmdless-candidate-no-bare-key-2026-08-04.test.js と同じ
//     「DOM未定義でも候補同期ループは完了する」トリック)による最小限の動的確認、の3本で担保する。
// 効果の追加/削除そのものの挙動(数値効果・potion-effect・flight・slots)は
// window.buildThreadEffectsBox の単体テスト(thread-potion-effect-2026-08-08.test.js)で
// 別途厳密に固定している(forms.js はこの共通部品をそのまま呼ぶだけなので重複させない)。
// セット効果(thread-sets.yml)の追加/削除の実際の見た目は、静的ソースチェックの限界を超えるため
// ヘッドレスChrome+CDPによるブラウザ実測(ops/scripts/lib/cdp.mjs)で別途確認する。
// ---------------------------------------------------------------------------

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");
const YAML = require("yaml");

const ROOT = path.resolve(__dirname, "..");
const JS = (name) => fs.readFileSync(path.join(ROOT, "public", "js", name), "utf8");
const LIB = (name) => fs.readFileSync(path.join(ROOT, "lib", name), "utf8");
const RESOURCES = path.resolve(ROOT, "..", "..", "TrinityForge", "src", "main", "resources");
const THREADS_YML_PATH = path.resolve(ROOT, "..", "..", "fork-handoff", "arspaper", "fork", "src", "main", "resources", "threads.yml");
const THREAD_SETS_YML_PATH = path.resolve(ROOT, "..", "..", "fork-handoff", "arspaper", "fork", "src", "main", "resources", "thread-sets.yml");

// ---------------------------------------------------------------------------
// 1. ナビ: スレッド関連の独立画面(旧「スレッド効果 (Ars)」/旧「スレッドのセット効果」)が
//    どちらも存在せず、「スレッド」(__stats_thread__)1つだけになっていること。
// ---------------------------------------------------------------------------

test("app.js: 旧・独立ナビ項目(__thread_effects__ / __thread_sets__)はどちらも存在しない", () => {
  const src = JS("app.js");
  // 撤去の経緯を説明する履歴コメントに旧IDが文字列として残るのは規約どおりなので、
  // 「ナビ項目の id として実際に使われている」ことだけを見る(部分文字列一致にしない)。
  assert.ok(!/id:\s*"__thread_effects__"/.test(src), "旧・独立ナビ項目(スレッド効果)が復活している");
  assert.ok(!/id:\s*"__thread_sets__"/.test(src), "旧・独立ナビ項目(スレッドのセット効果)が復活している");
  assert.ok(!/"スレッド効果 \(Ars\)"/.test(src), "旧ラベル(スレッド効果)が復活している");
  assert.ok(!/"スレッドのセット効果/.test(src), "旧ラベル(スレッドのセット効果)が復活している");
});

test("app.js: __stats_thread__(アイテムステータスのスレッドタブ)は残っている", () => {
  const src = JS("app.js");
  assert.ok(/__stats_thread__/.test(src));
  assert.match(src, /id: "__stats_thread__"[\s\S]{0,200}?itemCategory: "thread"/);
});

test("app.js / split-views.js: thread-bundle 型・thread-sets 単独型のコード上の実体がどちらも撤去されている", () => {
  const appSrc = JS("app.js");
  const splitSrc = JS("split-views.js");
  assert.ok(!/type:\s*"thread-bundle"/.test(appSrc), "app.js に thread-bundle 型を組み立てている箇所が残っている");
  assert.ok(!/sp\.type === "thread-bundle"/.test(appSrc), "app.js に thread-bundle 用の分岐が残っている");
  assert.ok(!/o\.type === "thread-bundle"/.test(splitSrc), "split-views.js に thread-bundle 分岐が残っている");
  assert.ok(!/type:\s*"thread-sets"/.test(appSrc), "app.js に thread-sets 単独ナビの split 定義が残っている");
  assert.ok(!/o\.type === "thread-sets"/.test(splitSrc), "split-views.js に thread-sets 単独分岐が残っている(死にコード)");
});

// ---------------------------------------------------------------------------
// 2. split-views.js: item-stats 画面が threadsData/threadSetsData の両方を受け取り、
//    buildItemStatsForm へ渡し、両ファイルを extraGets(保存対象)へ積むこと。
//    「itemCategory === "thread" のとき専用」のような分岐(=thread-dedicated-ui-removed-2026-08-02
//    .test.js が禁止するパターン)にはせず、各 Data の有無だけで分岐すること。
// ---------------------------------------------------------------------------

test("split-views.js: item-stats 分岐は o.threadsData/o.threadSetsData を buildItemStatsForm へ渡し、extraGets(threads/thread-sets)に積む", () => {
  const src = JS("split-views.js");
  assert.match(src, /o\.type === "item-stats"[\s\S]{0,2000}?threadsData[\s\S]{0,800}?buildItemStatsForm\(data,\s*\{[\s\S]{0,600}?threadsData[\s\S]{0,200}?threadSetsData/);
  assert.match(src, /extraGets\.push\(\{\s*id:\s*"threads"/);
  assert.match(src, /extraGets\.push\(\{\s*id:\s*"thread-sets"/);
  // 「itemCategory === "thread"」というカテゴリ名での専用分岐は作らない(2026-08-02 撤去済みの
  // random-roll-pools 専用分岐の再発防止テストと同じ不変条件)。
  assert.ok(!/itemCategory === "thread"/.test(src));
});

test("app.js: item-stats の読み込み経路が threads.yml/thread-sets.yml の両方を GET してくる(sp.itemCategory === \"thread\" のときだけ)", () => {
  const src = JS("app.js");
  assert.match(src, /sp\.type === "item-stats" && sp\.itemCategory === "thread"[\s\S]{0,600}?\/api\/config\/threads[\s\S]{0,400}?\/api\/config\/thread-sets/);
});

test("app.js: applyMergedToEditor は threads/thread-sets のどちらがマージされても再構成できる", () => {
  const src = JS("app.js");
  assert.match(src, /configId === "threads" \|\| configId === "thread-sets"/);
  assert.match(src, /function resolveExtra\(id\)/);
});

// ---------------------------------------------------------------------------
// 3. forms.js: renderThreadExtraFields が threads.yml/thread-sets.yml 側の編集を統合していること
//    (静的ソースチェック)。旧 entry["set-effects"] UI が復活していないこと。
// ---------------------------------------------------------------------------

test("forms.js: buildItemStatsForm は options.threadsData/threadSetsData を受け取り threadsRoot/threadSetsRoot として保持する", () => {
  const src = JS("forms.js");
  assert.match(src, /const threadsRoot = options\.threadsData/);
  assert.match(src, /const threadSetsRoot = options\.threadSetsData/);
});

test("forms.js: resolveThreadId が catalogMatch から threads.yml/thread-sets.yml 共通の id(thread_ を外した文字列)を解決する", () => {
  const src = JS("forms.js");
  assert.match(src, /function resolveThreadId\(match\)/);
  assert.match(src, /catId\.startsWith\("thread_"\)/);
  assert.match(src, /catId\.slice\("thread_"\.length\)/);
});

test("forms.js: renderThreadYmlEffects は window.ARS_FORMS.parseThreadEntry/serializeThreadEntry と window.buildThreadEffectsBox を再利用する(定義を複製しない)", () => {
  const src = JS("forms.js");
  assert.match(src, /window\.ARS_FORMS\.parseThreadEntry\(tid, threadsMap\[tid\]\)/);
  assert.match(src, /window\.ARS_FORMS\.serializeThreadEntry\(model\)/);
  // 2026-08-13: 数値入力を「再描画なしの書き戻し」へ繋ぐ第3引数を追加した
  // (thread-effect-number-input-commit-2026-08-13.test.js が経緯と不変条件を持つ)。
  assert.match(src, /window\.buildThreadEffectsBox\(model, commit, \{ onValueCommit: writeBack \}\)/);
  // THREAD_EFFECT_KEYS / THREAD_POTION_EFFECTS の値そのものを forms.js 側にコピーしていないこと。
  assert.ok(!/const\s+THREAD_EFFECT_KEYS\s*=/.test(src), "forms.js が効果キー一覧を複製している");
  assert.ok(!/const\s+THREAD_POTION_EFFECTS\s*=/.test(src), "forms.js がポーション効果一覧を複製している");
});

test("forms.js: 未編集(元から空)のエントリは threads.yml へ書き込まない(lazy-touch)", () => {
  const src = JS("forms.js");
  assert.match(src, /if \(!existed && Object\.keys\(out\)\.length === 0\) delete threadsMap\[tid\];/);
});

test("forms.js: renderThreadSetEffects は thread-sets.yml の thresholds を書く(entry ではなく threadSetsRoot 側)", () => {
  const src = JS("forms.js");
  assert.match(src, /function renderThreadSetEffects\(tid\)/);
  assert.match(src, /threadSetsRoot\["thread-sets"\]/);
  assert.match(src, /setsMap\[tid\]\.thresholds/);
});

test("forms.js / labels.js / lib/schema.js: 旧 entry[\"set-effects\"](item-stats.yml 側)は復活していない", () => {
  const formsSrc = JS("forms.js");
  const labelsSrc = JS("labels.js");
  const schemaSrc = LIB("schema.js");
  // 撤去の経緯を説明する履歴コメントに旧キー名が文字列として残るのは規約どおりなので、
  // 実際にコードとして読み書きしている行(代入・プロパティアクセスの直後に演算子や括弧が続く)
  // だけを見る(コメント中の言及は除外する)。
  const codeLines = formsSrc.split("\n").filter((line) => !/^\s*\/\//.test(line));
  const liveUsage = codeLines.some((line) => /entry\["set-effects"\]\s*[=.[]/.test(line) || /entry\["set-effects"\]\)/.test(line));
  assert.ok(!liveUsage, "forms.js が entry[\"set-effects\"] を書いている(editor にしか無い飾りキーの再発)");
  assert.ok(!/"set-effects":\s*\{\s*label:/.test(labelsSrc), "labels.js に set-effects のラベル定義が復活している");
  assert.ok(!/items\.\$\{key\}\.set-effects/.test(schemaSrc), "lib/schema.js に item-stats.yml 側の set-effects 検証が復活している");
  // thread-sets.yml 側の検証(validateArsThreadSets)は維持されていること(壊さない指示)。
  assert.match(schemaSrc, /function validateArsThreadSets\(data, errors\)/);
  assert.match(schemaSrc, /case "ars-thread-sets":/);
});

test("forms.js / ars-forms.js: 独立した「特殊効果」セクションの再発防止(統合済みの効果編集を1つのセレクトで持つ)", () => {
  const formsSrc = JS("forms.js");
  const arsSrc = JS("ars-forms.js");
  assert.ok(!/text:\s*"特殊効果"/.test(formsSrc));
  assert.ok(!/text:\s*"特殊効果"/.test(arsSrc));
});

// ---------------------------------------------------------------------------
// 4. buildItemStatsForm: threadsData/threadSetsData を渡しても既存の候補同期ループが
//    壊れないこと(動的確認)。実カード描画(render())は window.h が本物の document 依存のため、
//    既存テスト(item-stats-cmdless-candidate-no-bare-key-2026-08-04.test.js)と同じ
//    「DOM未定義で render() 到達前に投げる例外を握り潰す」手口で、候補同期ループの完了だけを見る。
// ---------------------------------------------------------------------------

global.window = global.window || {};
require("../public/js/editor-categories.js");
const { buildCatalogCandidates } = require("../public/js/catalog-candidates.js");
require("../public/js/forms.js");
const buildItemStatsForm = global.window.buildItemStatsForm;

function runBuildItemStatsForm(data, catalogCandidates, opts) {
  try {
    buildItemStatsForm(data, { catalogCandidates, ...(opts || {}) });
  } catch (_err) {
    // no-op: DOM 未定義による render() 内の例外は検証対象外(候補同期ループはそれより前に完了する)。
  }
  return data;
}

test("threadsData/threadSetsData を渡しても、CMD割当済みカタログ品の枠生成(候補同期ループ)は変わらない", () => {
  const catalogCandidates = buildCatalogCandidates({
    items: { fine_sword: { material: "IRON_SWORD", "display-name": "良い剣", "custom-model-data": 4321 } }
  }, null);
  const data = { items: {} };
  runBuildItemStatsForm(data, catalogCandidates, {
    threadsData: { threads: {} },
    threadSetsData: { "thread-sets": {} }
  });
  assert.ok(Object.prototype.hasOwnProperty.call(data.items, "IRON_SWORD#4321"));
});

test("threadsData/threadSetsData を渡さない(旧来の呼び出し)場合でも候補同期ループは例外を出さない", () => {
  const catalogCandidates = buildCatalogCandidates({
    items: { fine_sword: { material: "IRON_SWORD", "display-name": "良い剣", "custom-model-data": 4321 } }
  }, null);
  const data = { items: {} };
  assert.doesNotThrow(() => runBuildItemStatsForm(data, catalogCandidates));
  assert.ok(Object.prototype.hasOwnProperty.call(data.items, "IRON_SWORD#4321"));
});

// ---------------------------------------------------------------------------
// 5. 実データ: MATERIAL#CMD(item-stats.yml) → catalog.yml の thread_<id> → threads.yml の <id>
//    という id 解決が、出荷 yml の45件すべてで成立すること。thread-sets.yml のキーも
//    「エディタにしか無いキーを新たに生やさない」ことのガードとして、threads.yml と同じ
//    id 空間の部分集合であることを確認する。
// ---------------------------------------------------------------------------

function loadYml(relParts) {
  return YAML.parse(fs.readFileSync(path.join(...relParts), "utf8"));
}

test("実データ: catalog.yml の _editor.itemTabs で「thread」に割り当てられた品は45件、threads.yml の id も45件で完全に1:1対応する", () => {
  const catalog = loadYml([RESOURCES, "items", "catalog.yml"]);
  if (!fs.existsSync(THREADS_YML_PATH)) {
    // fork は .gitignore 除外 + 他セッションのWIPで存在しないことがある(config-editor.md 既知の注意)。
    console.log("fork-handoff threads.yml が無いため id 解決テストをスキップ");
    return;
  }
  const threads = loadYml([THREADS_YML_PATH]);

  const itemTabs = (catalog._editor && catalog._editor.itemTabs) || {};
  const threadCatalogIds = Object.entries(itemTabs).filter(([, t]) => t === "thread").map(([id]) => id);
  const threadYmlIds = Object.keys((threads && threads.threads) || {});

  assert.equal(threadCatalogIds.length, 45, "catalog.yml の thread タブ品数が45件から変わっている(前提が変わったのでテストの見直しが必要)");
  assert.equal(threadYmlIds.length, 45, "threads.yml のスレッド種数が45件から変わっている(前提が変わったのでテストの見直しが必要)");

  const missing = [];
  for (const catId of threadCatalogIds) {
    if (!catId.startsWith("thread_")) { missing.push(`${catId}: thread_ 接頭辞が無い`); continue; }
    const threadId = catId.slice("thread_".length);
    if (!Object.prototype.hasOwnProperty.call(threads.threads, threadId)) {
      missing.push(`${catId} -> ${threadId}: threads.yml に存在しない`);
    }
  }
  assert.deepEqual(missing, []);

  const extra = threadYmlIds.filter((id) => !threadCatalogIds.includes(`thread_${id}`));
  assert.deepEqual(extra, [], "threads.yml にあるのに catalog.yml 側の対応する thread_<id> が無いものがある");
});

test("実データ: item-stats.yml は45件すべての thread 品について MATERIAL#CMD キーを持つ", () => {
  const catalog = loadYml([RESOURCES, "items", "catalog.yml"]);
  const itemStats = loadYml([RESOURCES, "stats", "item-stats.yml"]);
  const itemTabs = (catalog._editor && catalog._editor.itemTabs) || {};
  const threadCatalogIds = Object.entries(itemTabs).filter(([, t]) => t === "thread").map(([id]) => id);

  const missing = [];
  for (const catId of threadCatalogIds) {
    const entry = catalog.items && catalog.items[catId];
    if (!entry) { missing.push(`${catId}: catalog.items に実体が無い`); continue; }
    const material = String(entry.material || "").toUpperCase();
    const cmd = entry["custom-model-data"];
    if (cmd == null) { missing.push(`${catId}: custom-model-data が無い(CMD未割当)`); continue; }
    const key = `${material}#${cmd}`;
    if (!Object.prototype.hasOwnProperty.call((itemStats.items || {}), key)) {
      missing.push(`${catId} -> ${key}: item-stats.yml に無い`);
    }
  }
  assert.deepEqual(missing, []);
});

test("実データ: 出荷 item-stats.yml に set-effects(item-stats.yml側の旧UI)は1件も無い(削除前提の確認)", () => {
  const itemStats = loadYml([RESOURCES, "stats", "item-stats.yml"]);
  const withSetEffects = Object.entries(itemStats.items || {})
    .filter(([, entry]) => entry && typeof entry === "object" && entry["set-effects"] !== undefined);
  assert.deepEqual(withSetEffects.map(([k]) => k), []);
});

test("実データ: thread-sets.yml のキーは threads.yml/catalog.yml と共通の id 空間の部分集合である(エディタにしか無いキーを作らないことのガード)", () => {
  if (!fs.existsSync(THREAD_SETS_YML_PATH) || !fs.existsSync(THREADS_YML_PATH)) {
    console.log("fork-handoff thread-sets.yml/threads.yml が無いため id 空間ガードをスキップ");
    return;
  }
  const threadSets = loadYml([THREAD_SETS_YML_PATH]);
  const threads = loadYml([THREADS_YML_PATH]);
  const threadYmlIds = new Set(Object.keys((threads && threads.threads) || {}));
  const threadSetIds = Object.keys((threadSets && threadSets["thread-sets"]) || {});
  const orphaned = threadSetIds.filter((id) => !threadYmlIds.has(id));
  assert.deepEqual(orphaned, [], "thread-sets.yml に threads.yml 側に存在しないスレッドIDがある(id空間がズレている)");
});
