"use strict";

// combat/mob-level-table.yml editor: no-skill-exp-mobs (2026-07-27 牧場対策) の UI 回帰テスト。
// NOTE: このファイル名は旧仕様名(no-vanilla-exp-mobs)の名残 — Bash が使えずファイルの
// リネーム/削除ができないため、パスはそのままに中身だけ新仕様(no-skill-exp-mobs)へ更新した。
//
// 2026-07-27 設計変更: 当初の「バニラEXPオーブを0にする」案は取り下げられた。バニラEXPオーブは
// 従来どおり落ちてよい(エンチャント等の用途を潰さないため)。止めるのは TrinityForge が独自に
// 付与する「戦闘スキルEXP」(武器=命中/防具=被弾/魔法=詠唱)のみ。ここではその設定キー
// no-skill-exp-mobs の editor UI(buildMobLevelTableForm 内の buildNoSkillExpMobsBox)を検証する。
// 実際のEXP抑止判定(CombatListener/NativeSkillExperienceListener)はJava側でテストする。
//
// buildMobLevelTableForm は DOM (window.h → document.createElement) に依存するブラウザ専用
// 関数だが、他のフォーム同様 window.h を最小スタブへ差し替えれば document 無しで検証できる
// (tf-dungeon-gates-key-item.test.js / tab-restructure-2026-07-26.test.js と同じ手法)。
// tiers: [] のまま検証すれば、renderTierCard 側が使う window.materialInput/numberInput/
// listSelect を一切スタブせずに済む。
//
// 2026-07-29: モブ欄は datalist 付きの生 <input> から共通の window.mobTypeSelect
// (日本語名で引けるセレクト)へ置き換わった。ここでは mobTypeSelect をスタブして
// onChange を直接叩くことで、正規化と行の増減の契約を従来どおり検証する。

const test = require("node:test");
const assert = require("node:assert/strict");

function makeEl(tag, attrs) {
  const el = {
    tag,
    props: attrs || {},
    children: [],
    style: {},
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    _listeners: {},
    appendChild(c) { if (c != null && c !== false) el.children.push(c); return c; },
    addEventListener(type, fn) {
      (el._listeners[type] = el._listeners[type] || []).push(fn);
    },
    dispatch(type, ev) {
      (el._listeners[type] || []).forEach((fn) => fn(ev));
    },
    querySelector() { return null; },
    querySelectorAll() { return []; }
  };
  if (attrs && "value" in attrs) el.value = attrs.value;
  Object.defineProperty(el, "innerHTML", {
    get() { return ""; },
    set() { el.children = []; }
  });
  return el;
}

function setupDom() {
  global.window = global.window || {};
  global.document = { getElementById: () => null, body: { appendChild() {} } };
  global.window.VANILLA_MOBS = ["ZOMBIE", "BEE", "GOAT", "IRON_GOLEM"];
  global.window.RECIPES_UI = undefined;

  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children != null) {
      (Array.isArray(children) ? children : [children]).forEach((c) => c != null && c !== false && el.appendChild(c));
    }
    return el;
  };
  global.window.fieldLabelEl = (key) => makeEl("label", { text: key });
  // 2026-07-29: レベルテーブルのカードが折りたたみ式(util.js の collapsibleCard)になった。
  // util.js はこのテストでは読み込まないので、head/body を素直に積むだけのスタブを置く
  // (findAll が body の中まで辿れれば従来の検証がそのまま通る)。
  global.window.collapsibleCard = (headChildren, bodyChildren) => {
    const card = makeEl("div", { class: "entry-card" });
    card.appendChild(global.window.h("div", { class: "entry-head" }, headChildren));
    card.appendChild(global.window.h("div", { class: "entry-body" }, bodyChildren));
    return card;
  };
  // 帯カード(renderTierCard)まで描く検証のための入力部品スタブ。
  // 既存の検証は tiers: [] でこれらを避けていたが、折りたたみの回帰テストは帯を必要とする。
  global.window.numberInput = (value, onInput) => makeEl("input", { value, numOnInput: onInput });
  global.window.textInput = (value, onInput) => makeEl("input", { value, textOnInput: onInput });
  global.window.textInputOnCommit = global.window.textInput;
  global.window.materialInput = (value, listId, onInput, opts) => makeEl("span", {
    class: "material-suggest", matValue: value, matOnInput: onInput, matOpts: opts || {}
  });
  global.window.listSelect = (cfg) => makeEl("span", { class: "list-select", selCfg: cfg || {} });
  global.window.checkboxInput = (value, onInput) => makeEl("input", { value, checkOnInput: onInput });
  global.window.materialHintEl = () => {
    const el = makeEl("span", { class: "mat-hint" });
    el.update = () => {};
    return el;
  };
  // 共通のモブセレクト。テストからは el.props.mobValue で行を特定し、
  // el.props.mobOnChange(値) で選択確定を再現する。
  global.window.mobTypeSelect = (value, onChange, opts) => makeEl("span", {
    class: "mob-type-select",
    mobValue: value == null ? "" : String(value),
    mobOnChange: onChange,
    mobOpts: opts || {}
  });

  delete require.cache[require.resolve("../public/js/mob-forms.js")];
  require("../public/js/mob-forms.js");
}

/** no-skill-exp-mobs 行のモブセレクトを列挙する。 */
function mobSelects(root) {
  return findAll(root, (el) => el.props && el.props.class === "mob-type-select");
}

function findAll(el, pred, out) {
  out = out || [];
  if (!el) return out;
  if (pred(el)) out.push(el);
  (el.children || []).forEach((c) => findAll(c, pred, out));
  return out;
}

test("no-skill-exp-mobs: 未指定なら案内文言だけでキーを実体化しない", () => {
  setupDom();
  const data = { tiers: [] };
  const result = global.window.buildMobLevelTableForm(data);

  const hints = findAll(result.element, (el) => el.tag === "div"
    && el.props && el.props.text === "未指定(戦闘スキルEXP無効化の対象なし)。");
  assert.equal(hints.length, 1, "未指定時の案内文言が出ていない");
  assert.equal("no-skill-exp-mobs" in result.getData(), false);
});

test("no-skill-exp-mobs: 「+ 対象モブを追加」で既定値 BEE が working に積まれる", () => {
  setupDom();
  const data = { tiers: [] };
  const result = global.window.buildMobLevelTableForm(data);

  const addButtons = findAll(result.element, (el) => el.tag === "button"
    && el.props && el.props.text === "+ 対象モブを追加");
  assert.equal(addButtons.length, 1, "追加ボタンが見つからない");
  addButtons[0].props.onclick();

  assert.deepEqual(data["no-skill-exp-mobs"], ["BEE"]);
});

test("no-skill-exp-mobs: 入力変更で大文字化・非英数字除去の正規化がかかる", () => {
  setupDom();
  const data = { "no-skill-exp-mobs": ["BEE"] };
  const result = global.window.buildMobLevelTableForm(data);

  const selects = mobSelects(result.element);
  assert.equal(selects.length, 1, "モブセレクトが1件のはず");
  assert.equal(selects[0].props.mobValue, "BEE");
  selects[0].props.mobOnChange(" goat! ");

  assert.deepEqual(data["no-skill-exp-mobs"], ["GOAT"]);
});

test("no-skill-exp-mobs: 削除ボタンで空になったらキー自体を working から消す", () => {
  setupDom();
  const data = { "no-skill-exp-mobs": ["BEE"] };
  const result = global.window.buildMobLevelTableForm(data);

  const deleteButtons = findAll(result.element, (el) => el.tag === "button"
    && el.props && el.props.text === "削除" && el.props.class === "btn-small danger");
  // tiers: [] を渡していないため帯側の削除ボタンは無い(min-levelを含まないので帯カードは0件)。
  assert.equal(deleteButtons.length, 1, "no-skill-exp-mobs の削除ボタンが1件のはず");
  deleteButtons[0].props.onclick();

  assert.equal("no-skill-exp-mobs" in data, false);
});

test("getData: 保存直前に空文字の未選択行を刈り取り、結果が空ならキーを消す", () => {
  setupDom();
  const data = { "no-skill-exp-mobs": ["BEE", "", "  ", "GOAT"] };
  const result = global.window.buildMobLevelTableForm(data);

  const saved = result.getData();
  assert.deepEqual(saved["no-skill-exp-mobs"], ["BEE", "GOAT"]);
});

test("getData: 全行が空文字なら no-skill-exp-mobs キーごと消える", () => {
  setupDom();
  const data = { "no-skill-exp-mobs": ["", "   "] };
  const result = global.window.buildMobLevelTableForm(data);

  const saved = result.getData();
  assert.equal("no-skill-exp-mobs" in saved, false);
});

// 2026-07-29 ユーザー要望「レベルテーブル設定のカードを折りたためるようにして」の回帰テスト。
// 帯が増えると縦に延々と積まれて全体像が掴めなくなるため、全カードを collapsibleCard で作る。
test("レベルテーブルのカードは全て折りたたみ式で作られる", () => {
  setupDom();
  const calls = [];
  const realCollapsible = global.window.collapsibleCard;
  global.window.collapsibleCard = (headChildren, bodyChildren, opts) => {
    calls.push({ headChildren, opts });
    return realCollapsible(headChildren, bodyChildren, opts);
  };

  const data = {
    "dungeon-only": false,
    "no-skill-exp-mobs": ["BEE"],
    tiers: [{ "min-level": 0, "vanilla-exp": 5 }, { "min-level": 20 }]
  };
  global.window.buildMobLevelTableForm(data);

  // 適用範囲 / 戦闘スキルEXP無効化 / 帯 ×2 = 4 カード。
  assert.equal(calls.length, 4, "全カードが collapsibleCard で作られること");
});

test("折りたたみ中のヘッダに帯の要約が出る (畳んだままでも中身が分かる)", () => {
  setupDom();
  const data = {
    tiers: [{
      "min-level": 30,
      "vanilla-exp": 12,
      "remove-drops": ["ROTTEN_FLESH"],
      "add-drops": [{ material: "BONE", chance: 0.1, min: 1, max: 1 }]
    }]
  };
  const result = global.window.buildMobLevelTableForm(data);

  const summaries = findAll(result.element,
    (el) => el.props && el.props.class === "entry-summary").map((el) => el.props.text);
  assert.ok(summaries.some((t) => t.includes("EXP 12") && t.includes("削除 1") && t.includes("追加 1")),
    `帯の要約が出ること: ${JSON.stringify(summaries)}`);

  const keyLabels = findAll(result.element,
    (el) => el.props && el.props.class === "entry-key-label").map((el) => el.props.text);
  assert.ok(keyLabels.some((t) => t.includes("Lv30")), `帯の見出しに下限レベルが出ること: ${JSON.stringify(keyLabels)}`);
});
