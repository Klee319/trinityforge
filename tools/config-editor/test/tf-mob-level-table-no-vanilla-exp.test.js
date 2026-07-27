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
// listSelect を一切スタブせずに済む(no-skill-exp-mobs ボックスは生の <input> のみを使う)。

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

  delete require.cache[require.resolve("../public/js/mob-forms.js")];
  require("../public/js/mob-forms.js");
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

  const inputs = findAll(result.element, (el) => el.tag === "input"
    && el.props && el.props.list === "entity-type-list");
  assert.equal(inputs.length, 1, "モブ入力欄が1件のはず");
  inputs[0].dispatch("change", { target: { value: " goat! " } });

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
