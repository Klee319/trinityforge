"use strict";

// タスク3 (2026-07-27) 回帰テスト: 「解体対象シリーズ」画面のネストが深すぎる問題の修正確認。
//
// 修正前は下記のように、長い説明付きの見出しがそれぞれ1階層とコンポーネントを消費していた:
//   解体対象シリーズ
//     対象 ID（末尾 * でシリーズ指定、* なしはアイテム個別指定）   <- 見出し行(階層1)
//       copper_*                                                  <- 値行(階層2)
//     返却ルール（複数書ける／それぞれ独立に適用される）           <- 見出し行(階層1)
//       ...
//
// 修正後は短いラベル+「?」ツールチップ(window.fieldLabelEl/window.helpIcon、既存の仕組みを流用)へ
// 説明文を移し、見出しと値を同じ form-field 行にまとめてネストを1段減らした。
// 説明文の情報自体は消さず、tooltip の desc として残す。

const test = require("node:test");
const assert = require("node:assert/strict");

function makeEl(tag, attrs) {
  const el = {
    tag,
    props: attrs || {},
    children: [],
    style: {},
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    appendChild(c) { if (c != null && c !== false) el.children.push(c); return c; },
    addEventListener() {},
    querySelector() { return null; },
    querySelectorAll() { return []; }
  };
  Object.defineProperty(el, "innerHTML", {
    get() { return ""; },
    set() { el.children = []; }
  });
  return el;
}

// tf-dungeon-gates-key-item.test.js と同じ手法: util.js は実物のまま読み込み、window.h だけ
// document不要の軽量版へ差し替える。fieldLabelEl/helpIcon/textInput/numberInput は util.js の
// 実装をそのまま使う(document.* を直接叩かない純粋な h() ラッパーのため)。
function setupDom() {
  global.window = global.window || {};
  global.document = global.document || {};
  global.alert = () => {};

  // util.js は自前の window.h(document.createElement ベース)を無条件で上書き定義するため、
  // 先に require してから、テスト用の軽量 window.h へ差し替える(tf-dungeon-gates-key-item.test.js
  // と同じ順序)。util.js の fieldLabelEl/helpIcon/textInput/numberInput は呼び出し時に
  // window.h を動的参照するため、この順序なら以後は常にテスト用スタブが使われる。
  delete require.cache[require.resolve("../public/js/util.js")];
  require("../public/js/util.js");

  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children != null) {
      (Array.isArray(children) ? children : [children]).forEach((c) => c != null && c !== false && el.appendChild(c));
    }
    return el;
  };

  global.window.listSelect = (cfg) => {
    const el = makeEl("span", { value: cfg.value });
    el.cfg = cfg;
    return el;
  };
  global.window.materialInput = () => makeEl("div", { class: "material-input" });
  global.window.checkboxInput = () => makeEl("input", { class: "checkbox" });

  delete require.cache[require.resolve("../public/js/tf-crafting-features.js")];
  require("../public/js/tf-crafting-features.js");
}

function findAllText(el, out) {
  out = out || [];
  if (el && el.props && typeof el.props.text === "string") out.push(el.props.text);
  for (const c of (el && el.children) || []) findAllText(c, out);
  return out;
}

function findByClass(el, cls, out) {
  out = out || [];
  if (el && el.props && el.props.class === cls) out.push(el);
  for (const c of (el && el.children) || []) findByClass(c, cls, out);
  return out;
}

function sampleDis() {
  return { items: { "copper_*": [{ input: "COPPER_INGOT", output: "custom:copper_ingot_scrap", multiplier: 2 }] } };
}

test("長い見出しラベルはもう描画されない(短いラベルへ差し替え済み)", () => {
  setupDom();
  const result = window.buildCraftingFeaturesDisassemblySection(sampleDis());
  const texts = findAllText(result);
  assert.ok(!texts.includes("対象 ID（末尾 * でシリーズ指定、* なしはアイテム個別指定）"),
    "旧・長い見出しラベル(対象ID)がまだ残っている");
  assert.ok(!texts.includes("返却ルール（複数書ける／それぞれ独立に適用される）"),
    "旧・長い見出しラベル(返却ルール)がまだ残っている");
  assert.ok(texts.includes("対象 ID"), `短いラベル「対象 ID」が見つからない。描画テキスト: ${JSON.stringify(texts)}`);
  assert.ok(texts.includes("返却ルール"), `短いラベル「返却ルール」が見つからない。描画テキスト: ${JSON.stringify(texts)}`);
});

test("説明文の情報(末尾*の意味/複数書ける)は消えておらず、?ツールチップのdescへ残っている", () => {
  setupDom();
  const captured = [];
  const realFieldLabelEl = global.window.fieldLabelEl;
  global.window.fieldLabelEl = (key, opts) => { captured.push({ key, opts }); return realFieldLabelEl(key, opts); };

  window.buildCraftingFeaturesDisassemblySection(sampleDis());

  const targetIdCall = captured.find((c) => c.key === "disassembly-target-id");
  const returnRulesCall = captured.find((c) => c.key === "disassembly-return-rules");
  assert.ok(targetIdCall, "disassembly-target-id 用の fieldLabelEl 呼び出しが見つからない");
  assert.match(targetIdCall.opts.desc, /末尾 ?\*/, "「末尾*でシリーズ指定、*なしはアイテム個別指定」の説明が消えている");
  assert.match(targetIdCall.opts.desc, /個別指定/, "「アイテム個別指定」の説明が消えている");
  assert.ok(returnRulesCall, "disassembly-return-rules 用の fieldLabelEl 呼び出しが見つからない");
  assert.match(returnRulesCall.opts.desc, /複数書ける/, "「複数書ける」の説明が消えている");
  assert.match(returnRulesCall.opts.desc, /独立に適用/, "「それぞれ独立に適用される」の説明が消えている");
});

// 2026-07-29: 対象シリーズカードを <details> の折りたたみへ変えたので、クラスは
// "cf-mat-card cf-mat-card-collapsible" になる(返却ルールカードは従来どおり "cf-mat-card" 単体)。
// ネストが1段減っていること(見出しと値入力がカード直下の form-field に並ぶ)は引き続き検証する。
test("ネストが1段減っている: 見出しと値入力が itemCard 直下の同じ form-field 行にまとまっている", () => {
  setupDom();
  const result = window.buildCraftingFeaturesDisassemblySection(sampleDis());
  const itemCards = findByClass(result, "cf-mat-card cf-mat-card-collapsible");
  assert.equal(itemCards.length, 1,
    `対象シリーズ本体のカードが1件見つかるはず。実際: ${itemCards.length}`);
  assert.equal(itemCards[0].tag, "details", "対象シリーズカードは折りたためる <details> であること");
  // 返却ルール1件ごとのエディタは従来どおり素の cf-mat-card。
  assert.equal(findByClass(result, "cf-mat-card").length, 1, "返却ルールカードは1件(サンプルのルール数)");

  const directFormFields = itemCards[0].children.filter((c) => c && c.props && c.props.class === "form-field");
  assert.equal(directFormFields.length, 2,
    `itemCard の直接の子に form-field(対象ID行・返却ルール行)が2つあるはず。` +
    `実際の直接の子クラス: ${itemCards[0].children.map((c) => c && c.props && c.props.class).join(", ")}`);
});

// 閉じたまま一覧として読めること(対象 ID と返却ルール件数が見出しに出る)の回帰テスト。
// 旧実装は見出しが全件「解体対象シリーズ」という同じ固定文字列で、展開しないと区別できなかった。
test("折りたたみ見出しに対象 ID と返却ルール件数が出る", () => {
  setupDom();
  const result = window.buildCraftingFeaturesDisassemblySection(sampleDis());
  const card = findByClass(result, "cf-mat-card cf-mat-card-collapsible")[0];
  const summary = card.children.find((c) => c && c.tag === "summary");
  assert.ok(summary, "summary(クリックで開閉する見出し)が無い");
  const texts = summary.children.map((c) => c && c.props && c.props.text);
  assert.ok(texts.includes("copper_*"), `見出しに対象 ID が出ていない: ${JSON.stringify(texts)}`);
  assert.ok(texts.some((t) => typeof t === "string" && t.includes("1 件")),
    `見出しに返却ルール件数が出ていない: ${JSON.stringify(texts)}`);
});
