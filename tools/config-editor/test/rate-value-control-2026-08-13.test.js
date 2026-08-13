"use strict";

// 割合フィールドの %入力 (window.rateValueControl) の回帰テスト。
//
// 2026-08-13 ユーザー指示「値の互換性は保って割合記法のものはすべて%記法にしてほしい」への対応。
// yml は割合 (0.12) のまま、エディタだけ「12 %」で表示・入力する。**往復が元値へ戻ることが全て**で、
// ここが 1 桁ずれると誰も気づかないまま戦闘バランスが 100 倍/100 分の1 になる。
//
// モブ系の画面(モブ定義/モブインポート/モブオーバーライド)が対象になるのは、そこのキーが
// `physical.resistance` のようなブロック内の短縮キーで、`stats/lore.yml` のステ語彙に存在せず
// `isPercentStat` では割合だと判定できないため。だから語彙ではなく明示リストで持つ ──
// そのリストに「割合でないキー」が混ざると 100 倍表示になるので、除外側も固定する。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

// ---- 最小 DOM スタブ (このリポジトリの他の DOM テストと同じ流儀。jsdom は使わない) ----
function makeEl(tag, props) {
  return {
    tag,
    props: props || {},
    children: [],
    appendChild(c) { this.children.push(c); return c; }
  };
}
global.window = global.window || {};
global.document = global.document || {
  createElement: (tag) => makeEl(tag, {}),
  addEventListener() {}
};
global.window.h = (tag, props, children) => {
  const el = makeEl(tag, props);
  if (Array.isArray(children)) children.forEach((c) => c != null && el.appendChild(c));
  else if (children != null) el.appendChild(children);
  return el;
};

// numberInput は「初期表示値」を覚えて、onChange を外から叩けるだけのスタブにする。
let lastNumberInput = null;
global.window.numberInput = (value, onChange) => {
  lastNumberInput = { value, onChange };
  return makeEl("input", { value });
};

delete require.cache[require.resolve("../public/js/forms.js")];
require("../public/js/forms.js");

const rateValueControl = global.window.rateValueControl;

test("forms.js が rateValueControl を公開している(未公開ならモブ画面が素の数値入力へ黙って戻る)", () => {
  assert.equal(typeof rateValueControl, "function",
    "window.rateValueControl が無い。mob-forms / tf-dungeon-forms は関数が無いと numberInput へ"
    + "フォールバックする設計なので、公開が消えても画面は壊れず『% が付かなくなる』だけになる");
});

/** 表示値を取り出す。 */
function shownFor(value, opts) {
  lastNumberInput = null;
  rateValueControl(value, () => {}, opts);
  return lastNumberInput.value;
}
/** 入力を打ち込んだときに保存される値を取り出す。 */
function savedFor(typed, opts) {
  let saved;
  lastNumberInput = null;
  rateValueControl(0, (v) => { saved = v; }, opts);
  lastNumberInput.onChange(typed);
  return saved;
}

test("表示は値×100 (出荷 yml の実値で確認)", () => {
  // mob-import.yml の resistance、mob-types.yml のレベル係数、damage-modifier の中立値、
  // item-stats.yml の小さいロール値。いずれも出荷値そのもの。
  assert.equal(shownFor(0.12), 12);
  assert.equal(shownFor(0.0025), 0.25);
  assert.equal(shownFor(1), 100);
  assert.equal(shownFor(0.35), 35);
  assert.equal(shownFor(0.007), 0.7);
  assert.equal(shownFor(0), 0);
});

test("保存は入力÷100 で、往復しても浮動小数の誤差が出ない", () => {
  assert.equal(savedFor(12), 0.12);
  assert.equal(savedFor(12.5), 0.125);
  assert.equal(savedFor(0.25), 0.0025);
  assert.equal(savedFor(100), 1);
  assert.equal(savedFor(3), 0.03);
  assert.equal(savedFor(9.4), 0.094);
});

test("空欄は既定で null (モブ系は「空欄=キーを書かず上位スコープを継承」なので 0 に潰してはいけない)", () => {
  assert.equal(shownFor(null), "");
  assert.equal(shownFor(undefined), "");
  assert.equal(savedFor(""), null);
  assert.equal(savedFor(null), null);
});

test("blankWhenEmpty:false では従来どおり 0 として扱う(statValueChangeControl 経由の既存画面の挙動)", () => {
  assert.equal(shownFor(null, { blankWhenEmpty: false }), 0);
  assert.equal(savedFor("", { blankWhenEmpty: false }), 0);
});

test("statValueControl の挙動は変わっていない(%ステは値×100 表示・null は 0)", () => {
  const statValueControl = global.window.statValueControl;
  assert.equal(typeof statValueControl, "function");
  // STAT_FORMATS が無い環境では FLAT 扱いになり素の数値入力へ落ちる。
  // PERCENT 判定を効かせるため最小限の宣言を置く。
  global.window.STAT_FORMATS = { "crit-chance": "PERCENT", "attack-power": "FLAT" };
  lastNumberInput = null;
  statValueControl("crit-chance", 0.03, () => {});
  assert.equal(lastNumberInput.value, 3, "%ステは 0.03 -> 3 と表示する");
  lastNumberInput = null;
  statValueControl("crit-chance", null, () => {});
  assert.equal(lastNumberInput.value, 0, "%ステの null は従来どおり 0 表示(空欄にしない)");
  let saved;
  lastNumberInput = null;
  statValueControl("crit-chance", 0, (v) => { saved = v; });
  lastNumberInput.onChange("");
  assert.equal(saved, 0, "%ステの空欄入力は従来どおり 0 保存");
});

// ---- 割合キーの表: 過不足の両方向を固定する ----

function rateKeysIn(file, constName) {
  const src = fs.readFileSync(path.join(__dirname, "..", "public", "js", file), "utf8");
  const m = new RegExp(`const ${constName} = new Set\\(\\[([\\s\\S]*?)\\]\\)`).exec(src);
  assert.ok(m, `${file} の ${constName} が見つからない(定義の形を変えたらこのテストも直すこと)`);
  return new Set([...m[1].matchAll(/"([a-z0-9-]+)"/g)].map((x) => x[1]));
}

const EXPECTED_RATE_KEYS = [
  "defense-rate", "resistance", "damage-reduction", "armor-strength",
  "percent-bonus-damage", "penetration", "crit-chance", "crit-damage", "damage-modifier"
];
// ここに混ざると 100 倍表示になるキー。DefenseStats / AttackStats の javadoc で
// 「割合ではなくダメージ量そのもの」と定義されているもの。
const MUST_NOT_BE_RATE = [
  "flat-defense", "flat-bonus-damage", "fixed-damage", "attack-power", "max-health", "level"
];

for (const [file, constName] of [["mob-forms.js", "RATE_FIELDS"], ["tf-dungeon-forms.js", "RATE_RAMP_KEYS"]]) {
  test(`${file}: 割合キーの表が期待どおり(過不足なし)`, () => {
    const keys = rateKeysIn(file, constName);
    assert.deepEqual([...keys].sort(), [...EXPECTED_RATE_KEYS].sort());
    for (const k of MUST_NOT_BE_RATE) {
      assert.ok(!keys.has(k),
        `${k} は割合ではなく実数(ダメージ量/HP/レベル)。ここに入れると画面が 100 倍で表示する`);
    }
  });
}

test("mob-forms: 防御/攻撃ブロックの値入力が mobValueInput を通っている", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "public", "js", "mob-forms.js"), "utf8");
  // PHYS_MAGIC_FIELDS / ATTACK_FIELDS を map/flatMap する箇所の直後 400 文字を見て、
  // 素の window.numberInput で値を作っていないことを確認する。
  const blocks = [...src.matchAll(/(PHYS_MAGIC_FIELDS|ATTACK_FIELDS)\.(map|flatMap)\(\(key\) => \{/g)];
  assert.ok(blocks.length >= 5, `検査対象のブロックが少なすぎる (${blocks.length} 件)。走査が空振りしている`);
  const offenders = [];
  for (const b of blocks) {
    const body = src.slice(b.index, b.index + 400);
    if (/window\.numberInput\(/.test(body)) offenders.push(body.split("\n")[0].trim());
  }
  assert.deepEqual(offenders, [],
    "素の numberInput は %変換を知らない。割合キーが 0.12 のまま出て単位も付かなくなる");
});
