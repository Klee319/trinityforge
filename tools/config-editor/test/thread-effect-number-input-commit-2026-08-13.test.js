"use strict";

// ---------------------------------------------------------------------------
// 2026-08-13 実サーバ報告の回帰テスト:
//   「スレッドのステータス設定で効果の引数に数値を入力しても保存時に数値が null(空) になっている」
//
// 真因: window.buildThreadEffectsBox の数値入力欄(数値効果6種 / ポーション効果のLv /
// バックパック枠)は model を書き換えるだけで、呼び出し元へ「値が変わった」ことを一切通知して
// いなかった。
//
//   - ars-forms.js の「スレッド」画面 (buildThreadsForm) では models 配列が model を
//     そのまま保持しているので、model を書き換えるだけで保存まで残る = 症状が出ない。
//   - forms.js の item-stats.yml「スレッド」タブ (renderThreadYmlEffects) は
//     **毎回の再描画で threads.yml から model を作り直す**ため、通知の無い書き換えは
//     次の再描画で捨てられる。保存対象は threadsRoot 側なので、入力した数値は
//     どこにも書かれないまま消える(potion-level のように「追加時に既定値を書かない」キーは
//     まるごと欠落するので、ユーザーには null/空 として見える)。
//
// 同種の罠は 2026-08-11 の statValueControl(値を持つ部品を、実際に永続化する経路へ
// 配線せずに置いた)と同じ形。UI部品側に「値だけが変わったときのコールバック」を持たせ、
// forms.js 側が再描画なしの書き戻しを渡すことで直す
// (再描画を伴う onChange をここで呼ぶと、打鍵のたびに入力欄が作り直されてフォーカスを失い、
//  ×ボタンへのクリックが blur→再描画に食われて1回目が効かなくなる)。
// ---------------------------------------------------------------------------

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

const ROOT = path.resolve(__dirname, "..");
const JS = (name) => fs.readFileSync(path.join(ROOT, "public", "js", name), "utf8");

// h() のスタブは本物(util.js)と同じ規約(on* な関数値は addEventListener 経由で配線する)。
function stubBrowser() {
  const created = [];
  const selects = [];
  const numbers = []; // { value, onInput, opts } を描画順に記録する
  global.window = global.window || {};
  global.document = global.document || {};
  global.window.h = (tag, attrsIn, children) => {
    const attrs = attrsIn || {};
    const el = {
      tag, attrs, children: [], _listeners: {},
      appendChild(c) { if (c != null && c !== false) this.children.push(c); return c; },
      set innerHTML(_v) { this.children = []; },
      get innerHTML() { return ""; },
      querySelectorAll() { return []; },
      addEventListener(ev, fn) { this._listeners[ev] = fn; },
      fire(ev, evt) { if (this._listeners[ev]) this._listeners[ev](evt); }
    };
    for (const [k, v] of Object.entries(attrs)) {
      if (v != null && k.startsWith("on") && typeof v === "function") el.addEventListener(k.slice(2).toLowerCase(), v);
    }
    if (Array.isArray(children)) children.forEach((c) => c != null && c !== false && el.appendChild(c));
    else if (children != null && children !== false) el.appendChild(children);
    created.push(el);
    if (tag === "select") selects.push(el);
    return el;
  };
  global.window.numberInput = (value, onInput, opts) => {
    const el = { tag: "input", value, opts, type: null, fire(v) { onInput(v); } };
    numbers.push(el);
    return el;
  };
  global.window.listSelect = (cfg) => ({ tag: "listSelect", cfg, commit: (v) => cfg.onCommit(v) });
  delete require.cache[require.resolve("../public/js/ars-forms.js")];
  delete require.cache[require.resolve("../public/js/recipes.js")];
  global.window.RECIPES = require("../public/js/recipes.js");
  require("../public/js/ars-forms.js");
  return { created, selects, numbers };
}

// ---------------------------------------------------------------------------
// 1. 部品単体: 数値入力は「値だけが変わった」ことを onValueCommit で必ず通知する
// ---------------------------------------------------------------------------

test("buildThreadEffectsBox: 数値効果の入力は onValueCommit を呼ぶ(model の書き換えだけで終わらせない)", () => {
  const { numbers } = stubBrowser();
  const model = window.ARS_FORMS.parseThreadEntry("t", { "regen-bonus": 0 });
  let commits = 0;
  window.buildThreadEffectsBox(model, () => { throw new Error("値の変更で再描画用の onChange を呼んではいけない"); },
    { onValueCommit: () => { commits++; } });

  assert.equal(numbers.length, 1, "数値効果の入力欄が1つ描画されるはず");
  numbers[0].fire(2.5);
  assert.equal(model.effects["regen-bonus"], 2.5);
  assert.equal(commits, 1, "数値を入力しても onValueCommit が呼ばれない(入力値が保存経路に届かない)");
});

test("buildThreadEffectsBox: ポーション効果のLv入力は onValueCommit を呼ぶ", () => {
  const { numbers } = stubBrowser();
  const model = window.ARS_FORMS.parseThreadEntry("t", { "potion-effect": "speed" });
  let commits = 0;
  window.buildThreadEffectsBox(model, () => {}, { onValueCommit: () => { commits++; } });

  assert.equal(numbers.length, 1, "ポーションLvの入力欄が1つ描画されるはず");
  numbers[0].fire(3);
  assert.equal(model.hasPotionLevel, true);
  assert.equal(model.potionLevel, 3);
  assert.equal(commits, 1, "Lv を入力しても onValueCommit が呼ばれない(potion-level が空のまま保存される)");
});

test("buildThreadEffectsBox: バックパック枠の入力は onValueCommit を呼ぶ", () => {
  const { numbers } = stubBrowser();
  const model = window.ARS_FORMS.parseThreadEntry("t", { slots: 0 });
  let commits = 0;
  window.buildThreadEffectsBox(model, () => {}, { onValueCommit: () => { commits++; } });

  assert.equal(numbers.length, 1, "バックパック枠の入力欄が1つ描画されるはず");
  numbers[0].fire(9);
  assert.equal(model.slots, 9);
  assert.equal(commits, 1, "枠数を入力しても onValueCommit が呼ばれない(0 のまま保存される)");
});

test("buildThreadEffectsBox: onValueCommit を渡さない呼び出し(スレッド画面)でも従来どおり動く", () => {
  const { numbers } = stubBrowser();
  const model = window.ARS_FORMS.parseThreadEntry("t", { "mana-bonus": 1, slots: 27 });
  assert.doesNotThrow(() => window.buildThreadEffectsBox(model, () => {}));
  numbers.forEach((el) => el.fire(4));
  assert.equal(model.effects["mana-bonus"], 4);
  assert.equal(model.slots, 4);
});

// ---------------------------------------------------------------------------
// 2. 入力した値が threads.yml 側のデータへ実際に届くこと。
//    forms.js の renderThreadYmlEffects と同じ条件(model を毎回作り直し、保存対象は
//    threadsMap 側)を組み立てて、書き戻しが起きることを確認する。
// ---------------------------------------------------------------------------

test("model を毎回作り直す呼び出し方(item-stats のスレッドタブと同じ条件)でも入力値が threads.yml 側へ届く", () => {
  const { numbers } = stubBrowser();
  const threadsMap = { silk: { display_name: "絹のスレッド", "potion-effect": "speed" } };
  const tid = "silk";
  const existed = true;

  // forms.js の renderThreadYmlEffects と同じ組み立て。
  const model = window.ARS_FORMS.parseThreadEntry(tid, threadsMap[tid]);
  function writeBack() {
    const out = window.ARS_FORMS.serializeThreadEntry(model);
    if (!existed && Object.keys(out).length === 0) delete threadsMap[tid];
    else threadsMap[tid] = out;
  }
  window.buildThreadEffectsBox(model, () => { writeBack(); }, { onValueCommit: writeBack });

  numbers[0].fire(2); // ポーションLvに 2 を入力
  assert.equal(threadsMap[tid]["potion-level"], 2,
    "入力した Lv が threads.yml 側へ書き戻されていない(保存すると空になる)");
});

// ---------------------------------------------------------------------------
// 3. 配線: forms.js は再描画なしの書き戻し(writeBack)を onValueCommit として渡すこと。
//    ここで render() を伴う commit を渡すと、打鍵ごとに入力欄が作り直されて使えなくなる。
// ---------------------------------------------------------------------------

test("forms.js: renderThreadYmlEffects は再描画を伴わない writeBack を onValueCommit として渡す", () => {
  const src = JS("forms.js");
  assert.match(src, /function writeBack\(\)/);
  assert.match(src, /window\.buildThreadEffectsBox\(model, commit, \{ onValueCommit: writeBack \}\)/);
  // commit は writeBack + 再描画。writeBack 自体が render() を呼んでいないこと
  // (writeBack の定義から、その直後に来る commit の定義までを本体とみなす)。
  const start = src.indexOf("function writeBack()");
  const end = src.indexOf("function commit()", start);
  assert.ok(start > 0 && end > start, "writeBack の直後に commit が定義されていない(前提のドリフト)");
  assert.ok(!/render\(\)/.test(src.slice(start, end)),
    "writeBack が render() を呼んでいる(打鍵ごとに再描画されてしまう)");
  assert.match(src.slice(end), /^function commit\(\) \{\s*\n\s*writeBack\(\);\s*\n\s*render\(\);/,
    "commit が writeBack + render() の形になっていない");
});

test("ars-forms.js: 数値入力の3箇所すべてが commitValue を呼ぶ(通知漏れの再発防止)", () => {
  const src = JS("ars-forms.js");
  const boxStart = src.indexOf("window.buildThreadEffectsBox = function");
  assert.ok(boxStart > 0, "buildThreadEffectsBox が見つからない");
  const boxSrc = src.slice(boxStart, src.indexOf("\n  // ============", boxStart));
  const numberInputCalls = boxSrc.match(/window\.numberInput\(/g) || [];
  const commitCalls = boxSrc.match(/commitValue\(\)/g) || [];
  assert.equal(numberInputCalls.length, 3, "数値入力欄の数が3から変わっている(テスト前提のドリフト)");
  assert.equal(commitCalls.length, 3,
    `数値入力欄 ${numberInputCalls.length} 箇所に対して commitValue() が ${commitCalls.length} 箇所しかない`);
});
