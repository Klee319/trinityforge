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
