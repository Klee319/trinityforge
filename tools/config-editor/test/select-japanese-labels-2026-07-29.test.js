"use strict";

// 2026-07-29 ユーザー報告の回帰テスト。
// 「アチブの設定でセレクトメニューの名称が全てID形式」「editor内のセレクトでID/英語表記のものを直す」
// 「手動入力になっているものをセレクトに変える」への対応が戻らないよう固定する。
//
// 特に itemRefSelect は、候補生成器 (catalog-candidates.js) が displayName を返すのに
// c.label しか見ておらず、報酬アイテム・ダンジョンの必要鍵アイテムのセレクトが
// カタログIDの羅列になっていた。両方のキーを見ることを契約として固定する。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.resolve(__dirname, "..");
const JS = (name) => fs.readFileSync(path.join(ROOT, "public", "js", name), "utf8");

function makeEl(tag, attrs) {
  const el = {
    tag,
    props: attrs || {},
    children: [],
    classList: { add() {}, remove() {}, toggle() {} },
    appendChild(c) { if (c != null && c !== false) el.children.push(c); return c; },
    addEventListener() {},
    set innerHTML(_v) { this.children = []; },
    get innerHTML() { return ""; }
  };
  return el;
}

/** util.js を素の window へ読み込み、listSelect の呼び出し設定を捕まえられるようにする。 */
function loadUtilWithCapturedListSelect(extraWindow) {
  const win = Object.assign({}, extraWindow || {});
  win.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (Array.isArray(children)) children.forEach((c) => c != null && el.appendChild(c));
    else if (children != null) el.appendChild(children);
    return el;
  };
  global.window = win;
  global.document = { getElementById: () => null, body: makeEl("body"), createElement: (t) => makeEl(t) };
  new Function("window", "document", JS("labels.js"))(win, global.document);
  new Function("window", "document", JS("util.js"))(win, global.document);
  const captured = [];
  const realListSelect = win.listSelect;
  win.listSelect = (cfg) => { captured.push(cfg); return makeEl("span", {}); };
  return { win, captured, realListSelect };
}

test("itemRefSelect: 候補の displayName が主表示になる (カタログIDの羅列に戻らない)", () => {
  const { win, captured } = loadUtilWithCapturedListSelect();
  win.MATERIALS = [];
  win.itemRefSelect({
    value: "",
    catalogCandidates: [
      { id: "tf_core_wood", displayName: "<gold>木の核</gold>", material: "STICK" },
      { id: "no_name_item", displayName: "", material: "STONE" }
    ],
    onChange: () => {}
  });
  assert.equal(captured.length, 1);
  const opts = captured[0].options;
  const wood = opts.find((o) => o.value === "tf_core_wood");
  // MiniMessage タグは落として素の日本語を出す。IDは副表示へ。
  assert.equal(wood.primary, "木の核");
  assert.equal(wood.secondary, "tf_core_wood");
  // display-name が空のものだけIDへフォールバックする。
  const noName = opts.find((o) => o.value === "no_name_item");
  assert.equal(noName.primary, "no_name_item");
});

test("itemRefSelect: バニラ Material も日本語名で並ぶ", () => {
  const { win, captured } = loadUtilWithCapturedListSelect();
  win.MATERIALS = ["DIAMOND"];
  win.MATERIAL_LABELS = { DIAMOND: "ダイヤモンド" };
  win.itemRefSelect({ value: "", catalogCandidates: [], onChange: () => {} });
  const diamond = captured[0].options.find((o) => o.value === "DIAMOND");
  assert.equal(diamond.primary, "ダイヤモンド");
  assert.equal(diamond.secondary, "DIAMOND");
});

test("window.selectInput は廃止されている (使うだけで生ID表示になるため)", () => {
  const util = JS("util.js");
  assert.ok(!/window\.selectInput\s*=/.test(util), "util.js に selectInput の定義が残っている");
  for (const name of fs.readdirSync(path.join(ROOT, "public", "js"))) {
    if (!name.endsWith(".js")) continue;
    const src = JS(name);
    const uses = src.match(/(?<!Labeled)\bselectInput\s*\(/g) || [];
    assert.deepEqual(uses, [], `${name} がまだ selectInput を呼んでいる`);
  }
});

test("村人職業の日本語辞書は labels.js に一本化されている", () => {
  const win = {};
  new Function("window", JS("labels.js"))(win);
  assert.equal(win.LABELS.professionLabel("WEAPONSMITH"), "武器鍛冶");
  assert.equal(win.LABELS.professionLabel("LIBRARIAN"), "司書");
  // 未知キーは生IDへフォールバック (呼び出し側の契約)。
  assert.equal(win.LABELS.professionLabel("UNKNOWN_JOB"), "UNKNOWN_JOB");
  // 各フォームは自前の辞書を持たず labels.js を引くこと。
  assert.ok(!/VILLAGER_PROFESSION_LABELS\s*=\s*\{/.test(JS("tf-lifestyle-forms.js")),
    "tf-lifestyle-forms.js が自前の職業辞書を持ち直している");
  assert.match(JS("tf-skilltree.js"), /LABELS\.professionLabel/);
});

test("バニラ進捗キーはセレクト候補になっている (自由入力だけではない)", () => {
  const win = {};
  new Function("window", JS("labels.js"))(win);
  new Function("window", JS("vocab-1.21.11.js"))(win);
  assert.ok(Array.isArray(win.VANILLA_ADVANCEMENTS));
  assert.ok(win.VANILLA_ADVANCEMENTS.length > 50);
  assert.equal(win.ADVANCEMENT_LABELS_JA["minecraft:story/mine_diamond"], "ダイヤモンドを手に入れる");
  // 候補は必ず namespace:path 形式 (前方一致の namespace 抜けを弾く)。
  for (const key of win.VANILLA_ADVANCEMENTS) {
    assert.match(key, /^minecraft:[a-z_]+\/[a-z_]+$/, `不正な進捗キー: ${key}`);
  }
  // アチーブメントフォームは textInput ではなく専用セレクトを使う。
  const rewards = JS("tf-rewards-forms.js");
  assert.match(rewards, /function advancementSelect\(/);
  assert.match(rewards, /field\("進捗キー \(trigger\.advancement\)", advancementSelect\(/);
});

test("アチーブメントの特殊報酬セレクトは種別+表示名の日本語ラベルを出す", () => {
  const forms = require("../public/js/tf-rewards-forms.js"); // 純関数のみ (isBrowser=false)
  assert.equal(typeof forms.normalizeAchievementRewards, "function");
  const src = JS("tf-rewards-forms.js");
  assert.match(src, /function specialRewardLabelOf\(labels, id\)/);
  // 割り当て済み行も追加セレクトも、生IDを range-label へ直書きしない。
  assert.match(src, /specialRewardAssignedRow\(specialRewardLabels, id/);
  assert.match(src, /specialRewardAddSelect\(available, specialRewardLabels/);
  // app.js がラベル辞書を作って渡していること。
  const app = JS("app.js");
  assert.match(app, /async function loadSpecialRewards\(\)/);
  assert.match(app, /specialRewardLabels: specialRewards\.labels/);
});

test("gate-vocabulary は特殊報酬の日本語ラベルも返す", () => {
  const { buildGateVocabulary } = require("../lib/gate-vocabulary.js");
  const vocab = buildGateVocabulary({
    specialRewards: {
      titles: { veteran: { display: "<gold>歴戦の</gold>" }, plain: {} },
      particles: { sparkle: { particle: "CRIT" } }
    }
  });
  assert.deepEqual(vocab.specialRewards, ["plain", "sparkle", "veteran"]);
  assert.equal(vocab.specialRewardLabels.veteran, "称号: 歴戦の");
  assert.equal(vocab.specialRewardLabels.sparkle, "パーティクル: CRIT");
  // 表示名が無いものは種別+IDで区別できるようにする。
  assert.equal(vocab.specialRewardLabels.plain, "称号: plain");
});

test("スキルツリーの親ノード/代替親/排他グループはセレクトになっている", () => {
  const src = JS("tf-skilltree.js");
  // 親ノードはノード名を主表示にした listSelect。
  assert.match(src, /primary: "起点 \(親なし\)"/);
  assert.match(src, /const nodeLabelOf = \(nid\) =>/);
  // 代替親はカンマ区切りの自由入力ではなく行リスト。
  assert.match(src, /function renderAnyParents\(\)/);
  assert.ok(!/textInput\(anyParents,/.test(src), "parents-any がカンマ区切りの自由入力に戻っている");
  // 排他グループは既存グループ名から選べる (新規は自由入力)。
  assert.match(src, /primary: "\(排他なし\)"/);
});

test("EntityType 欄は datalist 付き自由入力ではなく共通の日本語セレクト", () => {
  // 共通ヘルパーが日本語名を主表示にすること。
  const { win, captured } = loadUtilWithCapturedListSelect();
  win.VANILLA_MOBS = ["ZOMBIE", "BEE"];
  win.MOB_LABELS_JA = { ZOMBIE: "ゾンビ", BEE: "ハチ" };
  win.mobTypeSelect("ZOMBIE", () => {});
  const opts = captured[0].options;
  assert.equal(opts.find((o) => o.value === "ZOMBIE").primary, "ゾンビ");
  assert.equal(opts.find((o) => o.value === "BEE").primary, "ハチ");
  // 候補外の現在値も注記つきで残る (ロスレス)。
  captured.length = 0;
  win.mobTypeSelect("CUSTOM_BOSS", () => {}, { unknownNote: "mob-types.yml" });
  assert.equal(captured[0].options[0].value, "CUSTOM_BOSS");
  assert.equal(captured[0].options[0].secondary, "mob-types.yml");

  // 各フォームは自前の datalist 入力を持たず、この共通ヘルパーを使うこと。
  assert.ok(!/collection-entity-type-list/.test(JS("tf-rewards-forms.js")));
  assert.match(JS("tf-rewards-forms.js"), /window\.mobTypeSelect\(value, onChange/);
  for (const name of ["mob-forms.js", "ars-p4.js"]) {
    assert.match(JS(name), /window\.mobTypeSelect\(/, `${name} が共通セレクトを使っていない`);
    assert.ok(!/list: entityList/.test(JS(name)), `${name} に datalist 入力が残っている`);
  }
});

test("汎用エディタの型セレクトとレベルバーの色/形状は日本語", () => {
  const generic = JS("generic.js");
  assert.match(generic, /const TYPE_LABELS = \{/);
  assert.match(generic, /string: "文字列"/);
  const phase3 = JS("tf-phase3-forms.js");
  assert.match(phase3, /\["BLUE", "青"\]/);
  assert.match(phase3, /\["SEGMENTED_6", "6分割"\]/);
});

test("ガチャの券IDはカタログ品セレクト、プールは景品件数つき", () => {
  const src = JS("p5-forms.js");
  assert.match(src, /function ticketIdSelect\(value, onCommit\)/);
  assert.match(src, /secondary: `景品\$\{n\}件`/);
  assert.match(JS("app.js"), /window\.buildGachaForm\(data, \{ catalogCandidates \}\)/);
});

// ============================================================
// 2026-07-31: 「セレクトメニューが id 表記のまま日本語にならない」の再発防止。
// yml 側は無罪で、editor の**候補源とラベル解決**が3か所で壊れていた。
// 個別の表示は tf-rewards-forms-item-select.test.js が振る舞いで固定しているので、
// ここでは「画面ごとに流儀が食い違う」形へ戻らないことを契約として固定する。
// ============================================================

test("アイテム候補源は画面ごとにバラバラにしない (achievements も共通の候補源を使う)", () => {
  const app = JS("app.js");
  const achievements = /case "tf-achievements": \{[\s\S]*?\n      \}/.exec(app);
  assert.ok(achievements, "case \"tf-achievements\" が見つからない");
  const body = achievements[0];
  // catalog.yml 単体から候補を作ると materials.yml / functional-items / sourcejars /
  // catalysts / threads 由来の品が構造的に候補へ入らない(=生ID表示に戻る)。
  assert.ok(!/buildCatalogCandidates\(/.test(body),
    "achievements が候補を自前で組み直している(共通の候補源を使うこと)");
  assert.match(body, /ensureCustomItemCandidates\(\)|fetchCatalogCandidatesWithMaterials\(\)/,
    "achievements が共通の候補源を通っていない");
  // 兄弟の図鑑画面も同じ共通ヘルパーであること。
  assert.match(/case "tf-collection": \{[\s\S]*?\n      \}/.exec(app)[0],
    /fetchCatalogCandidatesWithMaterials\(\)|ensureCustomItemCandidates\(\)/);
});

test("itemRefSelect は custom: 付きの現在値を候補と照合できる (保存値は verbatim)", () => {
  const util = JS("util.js");
  // 照合の緩和は custom: 接頭辞が付いているときだけ。素のIDやバニラ Material の挙動は不変。
  assert.match(util, /\/\^custom:\/i\.test\(cur\)/);
  // 保存値(value)は現在値そのまま。剥がして書き戻すと「開いて保存しただけ」で差分が出る。
  assert.match(util, /options\.unshift\(hit[\s\S]{0,200}?\{ value: cur, primary: hit\.primary, secondary: cur \}/);
});

test("図鑑のアイテム欄はバニラ Material も候補に持つ (アチーブメント画面と同じ流儀)", () => {
  const rewards = JS("tf-rewards-forms.js");
  const control = /function catalogEntryControl\(value, onChange\) \{[\s\S]*?\n    \}/.exec(rewards);
  assert.ok(control, "catalogEntryControl が見つからない");
  assert.match(control[0], /window\.itemRefSelect\(/,
    "catalogItemSuggest 単体はバニラ Material を候補に持たないので「候補外」表示に戻る");
});

test("醸造ギミックの材料ヒントは custom: を解ける共通ヘルパーを使う", () => {
  const src = JS("tf-crafting-features.js");
  assert.match(src, /const ingredientHint = window\.materialHintEl\(pot\.ingredient\)/);
  // materialLabelWithFallback は MATERIAL_LABELS[key] || key なので custom:<id> を生返しする。
  // (コメントには経緯として名前が残るので、呼び出しの構文で判定する。)
  assert.ok(!/materialLabelWithFallback\(/.test(src),
    "custom: を解けない自前ヒント実装に戻っている");

  // 共通ヘルパー側が実際に custom: を日本語へ解けること (辞書は CUSTOM_ITEM_LABELS)。
  const { win } = loadUtilWithCapturedListSelect();
  win.CUSTOM_ITEM_LABELS = { "custom:witch_elixir": "魔女の霊薬" };
  const hint = win.materialHintEl("custom:witch_elixir");
  assert.equal(hint.textContent, "魔女の霊薬");
  // 辞書に無い custom: でも生トークンをそのまま出さない。
  const unknown = win.materialHintEl("custom:no_such");
  assert.equal(unknown.textContent, "カスタム:no_such");
});

// ============================================================
// 2026-07-31 (追補): アチーブメント画面の候補が**セッション中ずっと更新されない**問題。
//
// 上の「共通の候補源を使う」修正で achievements を ensureCustomItemCandidates() に
// 差し替えたが、これは module スコープの promise を**一度だけ**解決して保持する実装で
// 無効化経路が無く、毎回同一の配列インスタンスを返す。揃えた相手の tf-collection は
// fetchCatalogCandidatesWithMaterials() を毎回呼び直すので、鮮度の流儀が食い違ったまま
// だった。症状: カタログ画面で新規アイテムを追加して保存 → アチーブメント画面のセレクトに
// 出てこない(ハードリロードするまで)。
// ============================================================

/**
 * app.js から「候補源の2関数」をそのまま切り出して実行する。
 * app.js 本体は DOM 前提の巨大 IIFE なので丸ごとは動かせないが、この2関数は
 * api / rememberRevision / rememberBase / window だけに依存するので隔離して実走できる。
 * (fetchCatalogCandidatesWithMaterials → let customItemCandidatePromise →
 *  ensureCustomItemCandidates は app.js 内で連続しているので、その範囲を1スライスで取る。)
 */
function loadCandidateSources(api) {
  const app = JS("app.js");
  const start = app.indexOf("async function fetchCatalogCandidatesWithMaterials()");
  assert.ok(start >= 0, "fetchCatalogCandidatesWithMaterials が見つからない");
  const endStart = app.indexOf("function ensureCustomItemCandidates()", start);
  assert.ok(endStart > start, "ensureCustomItemCandidates が見つからない");
  // ensureCustomItemCandidates の本体の閉じ括弧まで、波括弧の対応を数えて取る。
  let depth = 0;
  let end = -1;
  for (let i = app.indexOf("{", endStart); i < app.length; i += 1) {
    if (app[i] === "{") depth += 1;
    else if (app[i] === "}") {
      depth -= 1;
      if (depth === 0) { end = i + 1; break; }
    }
  }
  assert.ok(end > 0, "ensureCustomItemCandidates の本体を切り出せない");
  const slice = app.slice(start, end);
  assert.match(slice, /let customItemCandidatePromise/,
    "2関数の間にあるメモ化用の変数宣言がスライスに入っていない");
  const win = {
    buildCatalogCandidates: (catalogData) => Object.keys((catalogData && catalogData.items) || {})
      .map((id) => ({ id, label: id })),
    setCustomItemCandidates: () => {}
  };
  const factory = new Function("api", "rememberRevision", "rememberBase", "window",
    `${slice}\nreturn { fetchCatalogCandidatesWithMaterials, ensureCustomItemCandidates };`);
  return factory(api, () => {}, () => {}, win);
}

/** catalog の GET 回数を数えつつ、items を後から差し替えられる api スタブ。 */
function catalogApiStub(items) {
  const state = { catalogGets: 0 };
  state.api = async (_method, url) => {
    if (url === "/api/config/catalog") {
      state.catalogGets += 1;
      return { revision: state.catalogGets, data: { items: { ...items } } };
    }
    return { revision: 1, data: {} };
  };
  return state;
}

test("共通の候補源は呼ぶたびに読み直す (保存直後の新規アイテムがセレクトに出る)", async () => {
  const items = { old_item: {} };
  const stub = catalogApiStub(items);
  const sources = loadCandidateSources(stub.api);

  const first = await sources.fetchCatalogCandidatesWithMaterials();
  assert.deepEqual(first.map((c) => c.id), ["old_item"]);

  // カタログ画面で新規アイテムを追加して保存した状態。
  items.new_item = {};
  const second = await sources.fetchCatalogCandidatesWithMaterials();

  assert.ok(second.map((c) => c.id).includes("new_item"),
    "保存後に候補が更新されない(セレクトに新規アイテムが出ない)");
  assert.equal(stub.catalogGets, 2, "毎回 catalog を読み直していない");
});

test("ensureCustomItemCandidates は一度しか解決しない (画面の候補源には使えない)", async () => {
  const items = { old_item: {} };
  const stub = catalogApiStub(items);
  const sources = loadCandidateSources(stub.api);

  const first = await sources.ensureCustomItemCandidates();
  items.new_item = {};
  const second = await sources.ensureCustomItemCandidates();

  // メモ化そのものは仕様(materialInput のカスタム候補を共通入口で1度だけ温める役)。
  // だからこそ「画面ごとの catalogCandidates」には使えない、という契約をここで固定する。
  assert.equal(second, first, "メモ化が外れている(共通入口の追加GETが毎画面で走る)");
  assert.equal(stub.catalogGets, 1);
  assert.ok(!second.map((c) => c.id).includes("new_item"),
    "メモ化 promise は保存後の新規アイテムを見られない");
});

test("アチーブメント画面の候補は毎回読み直す側へ揃える (図鑑画面と鮮度をそろえる)", () => {
  const app = JS("app.js");
  const achievements = /case "tf-achievements": \{[\s\S]*?\n      \}/.exec(app);
  assert.ok(achievements, "case \"tf-achievements\" が見つからない");
  // 「なぜ使わないか」はコメントで残すので、判定はコメントを外したコードだけで行う。
  const body = achievements[0].replace(/^[ \t]*\/\/.*$/gm, "");
  assert.match(body, /fetchCatalogCandidatesWithMaterials\(\)/,
    "achievements が毎回読み直す候補源を使っていない");
  assert.ok(!/ensureCustomItemCandidates\(\)/.test(body),
    "メモ化 promise を画面の候補源に使っている(保存しても候補が更新されない)");
  // 兄弟の図鑑画面と同じ関数であること = 鮮度の流儀を食い違わせない。
  assert.match(/case "tf-collection": \{[\s\S]*?\n      \}/.exec(app)[0],
    /fetchCatalogCandidatesWithMaterials\(\)/);
});

// ============================================================
// 2026-08-01 実サーバ報告「アイテムのセレクトメニューの表示が ID 表記で日本語でない」の
// 再発防止ラチェット。
//
// 2026-07-29 に全面日本語化したのに、その後に足された `selectLabeledInput` の呼び出しが
// 2つの形で生ID表示へ戻っていた。どちらも**例外もログも出ない**ので気づけない:
//   (a) 第3引数(語彙グループ)の渡し忘れ。コールバックが enumGroup の位置に入るので
//       ラベル解決に失敗して生ID表示になり、さらに onInput が undefined になって
//       **選んでも保存されない**。 (p5-forms.js のレア度カラー)
//   (b) 語彙グループ名を書いたのに labels.js 側にそのグループが無い。
//       フォーム内のフォールバック <select> だけが日本語辞書を持っていて、
//       実際に描画される listSelect は生ID。 (mob-abilities-form.js の型/ダメージ種別)
//
// どちらもソース走査で機械的に検出できるので、呼び出し規約そのものを固定する。
// ============================================================

/**
 * コメントを空白へ潰す。コメント内の「例示としての呼び出し」を実コードと誤検出しないため。
 * (文字列リテラル内の `//` は残す。)
 */
function stripJsComments(src) {
  const NL = String.fromCharCode(10);
  let out = "";
  let inStr = null;
  for (let i = 0; i < src.length; i += 1) {
    const ch = src[i];
    const next = src[i + 1];
    if (inStr) {
      if (ch === "\\") { out += ch + (next || ""); i += 1; continue; }
      if (ch === inStr) inStr = null;
      out += ch;
      continue;
    }
    if (ch === '"' || ch === "'" || ch === "`") { inStr = ch; out += ch; continue; }
    if (ch === "/" && next === "/") {
      while (i < src.length && src[i] !== NL) i += 1;
      out += NL;
      continue;
    }
    if (ch === "/" && next === "*") {
      i += 2;
      while (i < src.length && !(src[i] === "*" && src[i + 1] === "/")) {
        if (src[i] === NL) out += NL;
        i += 1;
      }
      i += 1;
      continue;
    }
    out += ch;
  }
  return out;
}

/** `selectLabeledInput(` の呼び出しごとに、丸括弧の対応を数えて引数を切り出す。 */
function parseSelectLabeledInputCalls(rawSrc) {
  const src = stripJsComments(rawSrc);
  const calls = [];
  const needle = "selectLabeledInput(";
  let from = 0;
  for (;;) {
    const at = src.indexOf(needle, from);
    if (at < 0) break;
    from = at + needle.length;
    // 定義側 (`window.selectLabeledInput = function selectLabeledInput(`) は呼び出しではない。
    const before = src.slice(Math.max(0, at - 30), at);
    if (/function\s+$/.test(before)) continue;

    let depth = 1;
    let i = from;
    let inStr = null;
    const args = [];
    let cur = "";
    for (; i < src.length && depth > 0; i += 1) {
      const ch = src[i];
      if (inStr) {
        if (ch === "\\") { cur += ch + src[i + 1]; i += 1; continue; }
        if (ch === inStr) inStr = null;
        cur += ch;
        continue;
      }
      if (ch === '"' || ch === "'" || ch === "`") { inStr = ch; cur += ch; continue; }
      if (ch === "(" || ch === "[" || ch === "{") depth += 1;
      else if (ch === ")" || ch === "]" || ch === "}") {
        depth -= 1;
        if (depth === 0) break;
      }
      if (ch === "," && depth === 1) { args.push(cur.trim()); cur = ""; continue; }
      cur += ch;
    }
    args.push(cur.trim());
    calls.push({ index: at, args });
  }
  return calls;
}

test("selectLabeledInput の第3引数は必ず語彙グループの文字列リテラル", () => {
  const offenders = [];
  for (const name of fs.readdirSync(path.join(ROOT, "public", "js"))) {
    if (!name.endsWith(".js")) continue;
    for (const call of parseSelectLabeledInputCalls(JS(name))) {
      const group = call.args[2];
      if (group === undefined || !/^"[a-z0-9-]+"$/.test(group)) {
        offenders.push(`${name}: 第3引数が語彙グループでない -> ${String(group).slice(0, 40)}`);
      }
    }
  }
  // 渡し忘れるとラベルが解決できず生ID表示になり、同時に onInput がずれて保存も効かなくなる。
  assert.deepEqual(offenders, [], offenders.join(" / "));
});

test("selectLabeledInput が使う語彙グループは labels.js に実在し、全値が日本語", () => {
  const win = {};
  new Function("window", JS("labels.js"))(win);
  const ENUM = win.LABELS.ENUM_LABELS;
  const hasJa = (s) => /[぀-ヿ一-鿿]/.test(String(s));

  const missingGroups = [];
  const missingValues = [];
  for (const name of fs.readdirSync(path.join(ROOT, "public", "js"))) {
    if (!name.endsWith(".js")) continue;
    const src = JS(name);
    for (const call of parseSelectLabeledInputCalls(src)) {
      const raw = call.args[2];
      if (!raw || !/^"[a-z0-9-]+"$/.test(raw)) continue; // 上のテストが担当
      const group = raw.slice(1, -1);
      if (!ENUM[group]) { missingGroups.push(`${name}: "${group}"`); continue; }

      // 第2引数が文字列リテラルの配列 (インライン or 同ファイルの const) なら値も網羅を見る。
      let listSrc = call.args[1] || "";
      if (/^[A-Z_][A-Z0-9_]*$/.test(listSrc)) {
        // テンプレートリテラルなので正規表現のバックスラッシュは二重に書く。
        const m = new RegExp(`const ${listSrc}\\s*=\\s*\\[([\\s\\S]*?)\\];`).exec(src);
        listSrc = m ? m[1] : "";
      }
      if (!/^\s*\[?\s*"/.test(listSrc)) continue; // 動的生成は対象外
      const values = (listSrc.match(/"([^"]+)"/g) || []).map((s) => s.slice(1, -1));
      for (const v of values) {
        const ja = ENUM[group][v];
        if (!ja || !hasJa(ja)) missingValues.push(`${name}: "${group}"."${v}"`);
      }
    }
  }
  assert.deepEqual(missingGroups, [],
    `labels.js に無い語彙グループを指している(セレクトが生ID表示になる): ${missingGroups.join(", ")}`);
  assert.deepEqual(missingValues, [],
    `語彙グループに日本語ラベルが無い値がある(その値だけ生ID表示になる): ${missingValues.join(", ")}`);
});

test("モブ技の型ラベルは labels.js へ一本化されている (フォーム側に辞書を持ち直さない)", () => {
  const src = JS("mob-abilities-form.js");
  // 自前の辞書リテラルを持つと、listSelect が引く labels.js 側とだけズレて生ID表示に戻る。
  assert.ok(!/const TYPE_LABELS = \{\s*\n?\s*ground_slam:/.test(src),
    "mob-abilities-form.js が型の日本語辞書を持ち直している");
  assert.match(src, /ENUM_LABELS\["mob-ability-type"\]/);

  const win = {};
  new Function("window", JS("labels.js"))(win);
  assert.equal(win.LABELS.enumLabel("mob-ability-type", "charge"), "突進 (charge)");
  assert.equal(win.LABELS.enumLabel("mob-ability-damage-type", "physical"), "物理");
});

// 「スレッド厳選のレア度カラー」テストは 2026-08-02、p5-forms.js の random-roll-pools 専用UI
// (buildRandomRollPoolsForm/buildRandomRollPoolEditor)ごと撤去した。スレッドは他アイテムと
// 同じ item-stats.yml フォームで編集するため、レア度カラーという概念自体が editor に無い。
