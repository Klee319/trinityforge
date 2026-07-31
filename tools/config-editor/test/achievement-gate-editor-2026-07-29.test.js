"use strict";

// アチーブメントの「アイコン / 説明Lore / 前提・分岐」editor 対応 (2026-07-29) の回帰テスト。
//
// 背景: 前提(parent / parents-any)は表示順ではなく「達成そのものを縛る」(ユーザー確定方針)。
// つまり editor で前提を1つ壊すと、その枝は条件を満たしても永久に取れなくなる。
// ここで守るのは主に「壊れた前提を作れないこと」:
//   - 自分自身を前提に選べない
//   - 循環になる選択は拒否される
//   - ID改名/削除で他ノードの参照が置き去りにならない
//   - 開いて保存しただけで空の icon/lore/coords/parent が生えない(往復ロスレス)

const test = require("node:test");
const assert = require("node:assert/strict");

function makeEl(tag, attrs) {
  const el = {
    tag,
    props: attrs || {},
    children: [],
    listeners: {},
    appendChild(c) { if (c != null && c !== false) el.children.push(c); return c; },
    addEventListener(type, fn) { (el.listeners[type] = el.listeners[type] || []).push(fn); },
    querySelector() { return null; },
    querySelectorAll() { return []; }
  };
  Object.defineProperty(el, "innerHTML", {
    get() { return ""; },
    set() { el.children = []; }
  });
  return el;
}

function walk(el, out) {
  if (!el || typeof el !== "object") return out;
  out.push(el);
  for (const child of el.children || []) walk(child, out);
  return out;
}
function findAll(root, pred) {
  return walk(root, []).filter(pred);
}

function setup() {
  global.window = global.window || {};
  global.document = global.document || {};
  global.alert = () => {};

  delete require.cache[require.resolve("../public/js/util.js")];
  require("../public/js/util.js");

  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children != null) {
      (Array.isArray(children) ? children : [children])
        .forEach((c) => c != null && c !== false && el.appendChild(c));
    }
    return el;
  };
  const captured = [];
  global.window.listSelect = (cfg) => {
    const el = makeEl("span", { value: cfg.value });
    el.cfg = cfg;
    captured.push(cfg);
    return el;
  };
  global.window.MATERIALS = ["DIAMOND"];
  global.window.renderLoreRows = (arr) => makeEl("div", { class: "lore-rows", loreLength: arr.length });
  // 2026-07-31: 表示名欄が colors.js の richTextInput(着色パレット付き入力)へ移った。
  // colors.js は document を直接触るのでここでは読み込まない。スタブが無いと
  // buildAchievementsForm が renderDetail の1行目で TypeError を投げ、この下の
  // アサーションが**1つも実行されないまま**テストが赤くなる(=回帰検知が死ぬ)。
  global.window.richTextInput = (value, mode, onInput) => {
    const el = makeEl("span", { class: "rich-host", mode, value });
    el.__onInput = onInput;
    return el;
  };
  global.window.alert = () => {};

  delete require.cache[require.resolve("../public/js/tf-rewards-forms.js")];
  require("../public/js/tf-rewards-forms.js");
  return captured;
}

function achievement(overrides) {
  return Object.assign({
    "display-name": "テスト",
    trigger: { type: "statistic", statistic: "JUMP", threshold: 1 },
    broadcast: false,
    rewards: { special: [], commands: [], items: [], "job-exp": [], "permanent-buffs": {} }
  }, overrides || {});
}

function chainData() {
  return {
    achievements: {
      root: achievement({ "display-name": "起点" }),
      mid: achievement({ "display-name": "中間", parent: "root" }),
      leaf: achievement({ "display-name": "末端", parent: "mid" })
    }
  };
}

function byPlaceholder(captured, placeholder) {
  return captured.filter((c) => c.placeholder === placeholder);
}

test("前提セレクト: 自分自身は候補に出ない", () => {
  const captured = setup();
  const data = chainData();
  window.buildAchievementsForm(data, {});

  const parentSelects = byPlaceholder(captured, "前提を選択…");
  assert.equal(parentSelects.length, 1, "選択中の1件ぶんだけ描画されるはず");
  const values = parentSelects[0].options.map((o) => o.value);
  assert.ok(!values.includes("root"), "選択中(root)自身が候補に出てはいけない");
  assert.ok(values.includes("mid") && values.includes("leaf"));
});

test("前提セレクト: 候補は表示名が主・IDが副で出る", () => {
  const captured = setup();
  window.buildAchievementsForm(chainData(), {});

  const opt = byPlaceholder(captured, "前提を選択…")[0].options.find((o) => o.value === "mid");
  assert.equal(opt.primary, "中間");
  assert.equal(opt.secondary, "mid");
});

test("前提セレクト: 「前提なし」を選ぶと parent キー自体が落ちる", () => {
  const captured = setup();
  // 詳細ペインは最初のキーを選択するので、前提付きの b を先頭に置く。
  const form = window.buildAchievementsForm(
    { achievements: { b: achievement({ parent: "a" }), a: achievement({}) } }, {});

  const parentSelect = byPlaceholder(captured, "前提を選択…")[0];
  assert.equal(parentSelect.value, "a");
  assert.equal(parentSelect.onCommit("__none__"), true);
  assert.ok(!Object.prototype.hasOwnProperty.call(form.getData().achievements.b, "parent"));
});

test("前提セレクト: 循環になる選択は拒否される(false を返し、値も変わらない)", () => {
  const captured = setup();
  // root を選択中。root の親に leaf(root→mid→leaf の子孫) を選ぶと循環。
  const form = window.buildAchievementsForm(chainData(), {});

  const parentSelect = byPlaceholder(captured, "前提を選択…")[0];
  assert.equal(parentSelect.onCommit("leaf"), false, "循環する選択は拒否されるはず");
  assert.ok(!Object.prototype.hasOwnProperty.call(form.getData().achievements.root, "parent"));
});

test("分岐前提: 追加できるが、循環するIDは拒否される", () => {
  const captured = setup();
  const form = window.buildAchievementsForm(chainData(), {});

  const addSelect = byPlaceholder(captured, "＋ 分岐前提を追加…")[0];
  assert.equal(addSelect.onCommit("leaf"), false, "循環する分岐前提も拒否する");
  assert.equal(addSelect.onCommit("mid"), false, "mid も root の子孫なので拒否");
  assert.deepEqual(form.getData().achievements.root["parents-any"], undefined);
});

test("分岐前提: 循環しない相手なら追加され、parents-any に載る", () => {
  const captured = setup();
  const form = window.buildAchievementsForm({
    achievements: {
      target: achievement({ "display-name": "合流点" }),
      left: achievement({ "display-name": "左" }),
      right: achievement({ "display-name": "右" })
    }
  }, {});

  const addSelect = byPlaceholder(captured, "＋ 分岐前提を追加…")[0];
  assert.equal(addSelect.onCommit("left"), true);
  assert.deepEqual(form.getData().achievements.target["parents-any"], ["left"]);
});

test("ID改名: 自分を前提にしている他ノードの参照が追随する", () => {
  setup();
  const data = {
    achievements: {
      root: achievement({}),
      mid: achievement({ parent: "root", "parents-any": ["root"] })
    }
  };
  const form = window.buildAchievementsForm(data, {});

  const input = findAll(form.element, (el) => el.tag === "input" && el.props.value === "root")[0];
  assert.ok(input, "ID入力欄が見つからない");
  input.value = "origin";
  input.listeners.change.forEach((fn) => fn());

  const out = form.getData().achievements;
  assert.ok(out.origin, "改名後のキーになっているはず");
  assert.equal(out.mid.parent, "origin", "parent の参照が古いIDのまま置き去りにされている");
  assert.deepEqual(out.mid["parents-any"], ["origin"]);
});

test("削除: 削除したIDを前提にしている参照は落ちる(達成不能な枝を残さない)", () => {
  setup();
  const form = window.buildAchievementsForm({
    achievements: {
      root: achievement({}),
      mid: achievement({ parent: "root", "parents-any": ["root"] })
    }
  }, {});

  const del = findAll(form.element, (el) => el.tag === "button" && el.props.text === "削除")[0];
  assert.ok(del, "削除ボタンが見つからない");
  del.props.onclick();

  const out = form.getData().achievements;
  assert.ok(!out.root);
  assert.ok(!Object.prototype.hasOwnProperty.call(out.mid, "parent"));
  assert.ok(!Object.prototype.hasOwnProperty.call(out.mid, "parents-any"));
});

test("アイコン: カタログ候補が表示名で並び、保存値はIDのまま", () => {
  const captured = setup();
  const form = window.buildAchievementsForm({ achievements: { a: achievement({}) } }, {
    catalogCandidates: [{ id: "infinity_sword", displayName: "インフィニティの剣" }]
  });

  const iconSelect = byPlaceholder(captured, "アイコンを選択…(空欄=紙)")[0];
  assert.ok(iconSelect, "アイコンのセレクトが描画されていない");
  const opt = iconSelect.options.find((o) => o.value === "infinity_sword");
  assert.equal(opt.primary, "インフィニティの剣");
  iconSelect.onChange("infinity_sword");
  assert.equal(form.getData().achievements.a.icon, "infinity_sword");
});

test("往復ロスレス: 開いて保存しただけでは空の icon/lore/coords/parent が生えない", () => {
  setup();
  const form = window.buildAchievementsForm({ achievements: { a: achievement({}) } }, {});

  const saved = form.getData().achievements.a;
  for (const key of ["icon", "lore", "coords", "parent", "parents-any"]) {
    assert.ok(!Object.prototype.hasOwnProperty.call(saved, key), key + " が勝手に生えている");
  }
});

test("説明Lore: アイテムカタログと同じ共通lore行エディタを使う", () => {
  setup();
  const form = window.buildAchievementsForm({
    achievements: { a: achievement({ lore: ["<gray>ひとこと"] }) }
  }, {});

  const loreBox = findAll(form.element, (el) => el.props && el.props.class === "lore-rows")[0];
  assert.ok(loreBox, "共通lore行エディタ(window.renderLoreRows)が呼ばれていない");
  assert.equal(loreBox.props.loreLength, 1);
  assert.deepEqual(form.getData().achievements.a.lore, ["<gray>ひとこと"]);
});

test("肥大化対策: 詳細ペインは節タブ(基本/達成条件/前提・分岐/報酬)に割れている", () => {
  setup();
  const form = window.buildAchievementsForm({ achievements: { a: achievement({}) } }, {});

  const tabBar = findAll(form.element,
    (el) => el.props && String(el.props.class || "").includes("achievement-section-tabs"))[0];
  assert.ok(tabBar, "詳細ペインの節タブが無い");
  const labels = findAll(tabBar, (el) => el.tag === "span" && el.props.text).map((el) => el.props.text);
  assert.deepEqual(labels, ["基本", "達成条件", "前提・分岐", "報酬"]);
});

// ---- lib/schema.js -----------------------------------------------------------
const { validate } = require("../lib/schema.js");

function validateAchievements(achievements) {
  return validate("tf-achievements", { achievements });
}

test("schema: 存在しない前提IDはエラーにする(永久に達成不能な定義を通さない)", () => {
  const errors = validateAchievements({
    a: { trigger: { type: "statistic", statistic: "JUMP", threshold: 1 }, parent: "missing" }
  });
  assert.ok(errors.some((e) => e.includes("parent") && e.includes("missing")), errors.join(" / "));
});

test("schema: 自分自身を前提にはできない", () => {
  const errors = validateAchievements({
    a: { trigger: { type: "statistic", statistic: "JUMP", threshold: 1 }, parent: "a" },
    b: { trigger: { type: "statistic", statistic: "JUMP", threshold: 1 }, "parents-any": ["b"] }
  });
  assert.ok(errors.some((e) => e.startsWith("achievements.a.parent")));
  assert.ok(errors.some((e) => e.startsWith("achievements.b.parents-any[0]")));
});

test("schema: coords は \"x,y\" 形式だけ通す", () => {
  const base = { trigger: { type: "statistic", statistic: "JUMP", threshold: 1 } };
  assert.equal(validateAchievements({ a: Object.assign({ coords: "4,2" }, base) }).length, 0);
  assert.equal(validateAchievements({ a: Object.assign({ coords: "-3, 10" }, base) }).length, 0);
  assert.equal(validateAchievements({ a: Object.assign({ coords: "" }, base) }).length, 0);
  assert.ok(validateAchievements({ a: Object.assign({ coords: "4" }, base) }).length > 0);
  assert.ok(validateAchievements({ a: Object.assign({ coords: "x,y" }, base) }).length > 0);
});

test("schema: icon は文字列、lore は文字列配列", () => {
  const base = { trigger: { type: "statistic", statistic: "JUMP", threshold: 1 } };
  assert.equal(validateAchievements({
    a: Object.assign({ icon: "custom:foo", lore: ["a", "b"] }, base)
  }).length, 0);
  assert.ok(validateAchievements({ a: Object.assign({ icon: 5 }, base) }).length > 0);
  assert.ok(validateAchievements({ a: Object.assign({ lore: [1] }, base) }).length > 0);
});

test("schema: 正しい前提の連鎖はエラーにならない", () => {
  const base = { trigger: { type: "statistic", statistic: "JUMP", threshold: 1 } };
  const errors = validateAchievements({
    root: Object.assign({}, base),
    mid: Object.assign({ parent: "root" }, base),
    join: Object.assign({ "parents-any": ["root", "mid"] }, base)
  });
  assert.deepEqual(errors, []);
});
