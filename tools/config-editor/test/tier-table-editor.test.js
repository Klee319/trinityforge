"use strict";

// public/js/tf-lifestyle-forms.js の tierTableEditor (2026-07-26 新設) の回帰テスト。
//
// 背景: 採取系「専用効果」は 2026-07-25 の設計変更でtier化された。Java側(MiningGimmickConfig /
// WoodcuttingGimmickConfig / FarmingGimmickConfig の parse*Tiers/resolve系)は既に対応済みだが、
// editor は section.tiers を一切描画・編集できず、フラット値だけを触っていた
// (= haste-active-mining の tiers:1/3/5 のようにtier表がある機構では、フラット値をいくら
// 変えても解放済みプレイヤーには反映されない無言の死角だった)。
// あわせてタスク3: buildWoodcuttingGimmickForm が書いていた small-max-extra-logs /
// large-max-extra-logs はJava(WoodcuttingGimmickConfig)がもう読まない死んだキーで、
// フォーム構築時に自動で掃除する(lib/cmd-removal.js の孤児掃除の前例に倣う)。
//
// fishing-gimmick-fish-group.test.js と同じ手法: window.h 等を最小スタブしてブラウザ用IIFEを
// Nodeでrequireする。attrs.onclick/onchange は _onclick/_onchange として保持し、
// numberInput/materialInput/textInput は el.trigger(v) で onInput を呼べるようにする。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

function makeFakeEl(tag, attrs) {
  const el = {
    tag,
    attrs: attrs || {},
    children: [],
    _onclick: attrs && typeof attrs.onclick === "function" ? attrs.onclick : null,
    _onchange: attrs && typeof attrs.onchange === "function" ? attrs.onchange : null,
    value: attrs && attrs.value != null ? attrs.value : "",
    textContent: attrs && attrs.text != null ? attrs.text : "",
    appendChild(child) {
      if (child !== null && child !== undefined && child !== false) el.children.push(child);
      return child;
    },
    querySelector() { return null; },
    addEventListener() {}
  };
  Object.defineProperty(el, "innerHTML", {
    get() { return ""; },
    set() { el.children = []; }
  });
  return el;
}

global.window = global.window || {};
global.window.h = function h(tag, attrs, children) {
  const el = makeFakeEl(tag, attrs);
  if (children !== undefined && children !== null) {
    const arr = Array.isArray(children) ? children : [children];
    arr.forEach((c) => el.appendChild(c));
  }
  return el;
};
global.window.fieldLabelEl = () => makeFakeEl("div");
global.window.checkboxInput = () => makeFakeEl("input");
global.window.collapsibleCard = (head, body) => {
  const el = makeFakeEl("div");
  (Array.isArray(head) ? head : [head]).forEach((c) => el.appendChild(c));
  (Array.isArray(body) ? body : [body]).forEach((c) => el.appendChild(c));
  return el;
};
global.window.numberInput = (value, onInput) => {
  const el = makeFakeEl("input", { value });
  el.trigger = (v) => onInput(v);
  return el;
};
global.window.textInput = (value, onInput) => {
  const el = makeFakeEl("input", { value });
  el.trigger = (v) => onInput(v);
  return el;
};
global.window.materialInput = (value, listId, onInput) => {
  const el = makeFakeEl("span", { value });
  el.trigger = (v) => onInput(v);
  return el;
};
global.window.listSelect = (cfg) => {
  const el = makeFakeEl("span", { value: cfg.value });
  el.trigger = (v) => { if (typeof cfg.onChange === "function") cfg.onChange(v); };
  return el;
};

const alerts = [];
global.alert = (msg) => { alerts.push(msg); };

require("../public/js/tf-lifestyle-forms.js");
const { isPositiveIntegerTierKey, normalizeTierRow, pruneEmptyTiers } = global.window.TIER_TABLE_LOGIC;

/** wrap 以下を再帰的に辿り、条件に一致する要素を全て集める。 */
function collectAll(root, pred, out) {
  out = out || [];
  if (!root || typeof root !== "object") return out;
  if (pred(root)) out.push(root);
  for (const c of root.children || []) collectAll(c, pred, out);
  return out;
}
function hasClass(el, cls) {
  const c = el && el.attrs && el.attrs.class;
  return typeof c === "string" && c.split(/\s+/).includes(cls);
}
function findByText(root, text) {
  const hits = collectAll(root, (el) => el.attrs && el.attrs.text === text);
  return hits.length ? hits[0] : null;
}

// ============================================================
// 純関数パート (window.TIER_TABLE_LOGIC)
// ============================================================

test("isPositiveIntegerTierKey: 1以上の整数のみ許可する", () => {
  assert.equal(isPositiveIntegerTierKey("1"), true);
  assert.equal(isPositiveIntegerTierKey("42"), true);
  assert.equal(isPositiveIntegerTierKey("0"), false);
  assert.equal(isPositiveIntegerTierKey("-1"), false);
  assert.equal(isPositiveIntegerTierKey("1.5"), false);
  assert.equal(isPositiveIntegerTierKey("abc"), false);
  assert.equal(isPositiveIntegerTierKey(""), false);
  assert.equal(isPositiveIntegerTierKey(null), false);
  assert.equal(isPositiveIntegerTierKey(undefined), false);
});

test("normalizeTierRow: 列を補い、int列は切り捨てる", () => {
  const columns = [{ key: "amplifier", int: true }, { key: "duration-ticks", int: true }];
  assert.deepEqual(normalizeTierRow({}, columns), { amplifier: 0, "duration-ticks": 0 });
  assert.deepEqual(normalizeTierRow({ amplifier: 3.9, "duration-ticks": 160 }, columns), { amplifier: 3, "duration-ticks": 160 });
  assert.deepEqual(normalizeTierRow(null, columns), { amplifier: 0, "duration-ticks": 0 });
});

test("pruneEmptyTiers: 空の tiers はキーごと削除、非空・未定義は変化なし", () => {
  const a = { tiers: {} };
  pruneEmptyTiers(a);
  assert.equal("tiers" in a, false);

  const b = { tiers: { 1: { radius: 2 } } };
  pruneEmptyTiers(b);
  assert.deepEqual(b.tiers, { 1: { radius: 2 } });

  const c = {};
  pruneEmptyTiers(c);
  assert.equal("tiers" in c, false);
});

// ============================================================
// DOM 結合パート: buildMiningGimmickForm 等を実際に呼び出す
// ============================================================

function baselineMiningData() {
  // stats/mining-gimmick.yml の実データ相当(haste-active-mining.tiers 1/3/5 を含む)。
  return {
    "vein-mining": { "ore-blocks": ["COAL_ORE", "IRON_ORE"], "max-extra-blocks": 32 },
    "haste-active-mining": {
      amplifier: 5,
      "duration-ticks": 200,
      "cooldown-ticks": 600,
      tiers: {
        1: { amplifier: 3, "duration-ticks": 160 },
        3: { amplifier: 4, "duration-ticks": 200 },
        5: { amplifier: 5, "duration-ticks": 240 }
      }
    },
    fortune: { "fortune-per-level": 0.01, "fortune-blocks": ["COAL_ORE"] },
    "drop-tables": { categories: {} }
  };
}

test("tierTableEditor: 既存の tiers 行(haste-active-mining の 1/3/5)を正しく描画する", () => {
  const data = baselineMiningData();
  const form = window.buildMiningGimmickForm(data);
  const tierRows = collectAll(form.element, (el) => hasClass(el, "tier-row"));
  assert.equal(tierRows.length, 3, "vein-mining(0件)+haste(3件)のtier行合計は3のはず");

  const keyValues = tierRows.map((row) => row.children.find((c) => hasClass(c, "tier-key-input")).value);
  assert.deepEqual(keyValues.map(String).sort(), ["1", "3", "5"]);

  // 各行の列入力(numberInput、tier-key-inputの次から2つ)がyml通りの値であること。
  for (const row of tierRows) {
    const keyEl = row.children.find((c) => hasClass(c, "tier-key-input"));
    const numberInputs = row.children.filter((c) => c.tag === "input" && !hasClass(c, "tier-key-input"));
    assert.equal(numberInputs.length, 2, "haste行はamplifier/duration-ticksの2列");
    const tier = Number(keyEl.value);
    const expected = data["haste-active-mining"].tiers[tier];
    assert.equal(numberInputs[0].value, expected.amplifier);
    assert.equal(numberInputs[1].value, expected["duration-ticks"]);
  }
});

test("ロスレス: mining-gimmick.yml 実データを読み込んで何も操作せず保存しても tiers は変形しない", () => {
  const ymlPath = path.join(__dirname, "..", "..", "..", "TrinityForge", "src", "main", "resources", "stats", "mining-gimmick.yml");
  assert.ok(fs.existsSync(ymlPath), `not found: ${ymlPath}`);
  const original = YAML.parse(fs.readFileSync(ymlPath, "utf8"));
  assert.deepEqual(original["haste-active-mining"].tiers, {
    1: { amplifier: 3, "duration-ticks": 160 },
    3: { amplifier: 4, "duration-ticks": 200 },
    5: { amplifier: 5, "duration-ticks": 240 }
  }, "前提となる出荷ymlの形が変わっている(テストの前提を見直すこと)");

  const working = JSON.parse(JSON.stringify(original));
  const form = window.buildMiningGimmickForm(working);
  const saved = form.getData();

  assert.deepEqual(saved["haste-active-mining"].tiers, original["haste-active-mining"].tiers,
    "何も操作していないのに haste-active-mining.tiers が変形/消失した");
  assert.equal(saved["vein-mining"]["max-extra-blocks"], original["vein-mining"]["max-extra-blocks"]);
  assert.equal("tiers" in saved["vein-mining"], false, "vein-mining は出荷ymlにtiersが無い(空生成禁止)");
});

test("tierTableEditor: 「+ tier追加」は既存最大tier+1を初期値にする", () => {
  const data = baselineMiningData();
  data["vein-mining"].tiers = { 2: { "max-extra-blocks": 10 }, 5: { "max-extra-blocks": 20 } };
  const form = window.buildMiningGimmickForm(data);
  const addBtns = collectAll(form.element, (el) => el.attrs && el.attrs.text === "+ tier追加");
  assert.ok(addBtns.length >= 1);
  addBtns[0]._onclick(); // vein-mining用(最初のtierTableEditor)
  const saved = form.getData();
  assert.ok(Object.prototype.hasOwnProperty.call(saved["vein-mining"].tiers, "6"),
    `既存最大5の次=6が追加されるはず: ${JSON.stringify(Object.keys(saved["vein-mining"].tiers))}`);
});

test("tierTableEditor: tier番号 0/負数/非整数への変更は拒否され、元の値のまま", () => {
  const data = baselineMiningData();
  const form = window.buildMiningGimmickForm(data);
  const tierRows = collectAll(form.element, (el) => hasClass(el, "tier-row"));
  const row1 = tierRows.find((r) => r.children.find((c) => hasClass(c, "tier-key-input")).value === "1");
  const keyEl = row1.children.find((c) => hasClass(c, "tier-key-input"));

  for (const bad of ["0", "-1", "1.5", "abc"]) {
    alerts.length = 0;
    keyEl._onchange({ target: { value: bad } });
    assert.equal(alerts.length, 1, `"${bad}" は弾かれてalertが出るはず`);
    assert.deepEqual(Object.keys(form.getData()["haste-active-mining"].tiers).sort(), ["1", "3", "5"],
      `"${bad}" への変更で tiers のキー集合が壊れてはいけない`);
  }
});

test("tierTableEditor: tier番号を既存の他行と重複させる変更は拒否される", () => {
  const data = baselineMiningData();
  const form = window.buildMiningGimmickForm(data);
  const tierRows = collectAll(form.element, (el) => hasClass(el, "tier-row"));
  const row1 = tierRows.find((r) => r.children.find((c) => hasClass(c, "tier-key-input")).value === "1");
  const keyEl = row1.children.find((c) => hasClass(c, "tier-key-input"));

  alerts.length = 0;
  keyEl._onchange({ target: { value: "3" } }); // 既に tier 3 が存在する
  assert.equal(alerts.length, 1, "重複するtier番号への変更はalertで拒否されるはず");
  assert.deepEqual(Object.keys(form.getData()["haste-active-mining"].tiers).sort(), ["1", "3", "5"]);
});

test("tierTableEditor: 妥当な tier番号への変更は反映される", () => {
  const data = baselineMiningData();
  const form = window.buildMiningGimmickForm(data);
  const tierRows = collectAll(form.element, (el) => hasClass(el, "tier-row"));
  const row1 = tierRows.find((r) => r.children.find((c) => hasClass(c, "tier-key-input")).value === "1");
  const keyEl = row1.children.find((c) => hasClass(c, "tier-key-input"));

  keyEl._onchange({ target: { value: "2" } });
  const saved = form.getData();
  assert.deepEqual(Object.keys(saved["haste-active-mining"].tiers).sort(), ["2", "3", "5"]);
  assert.deepEqual(saved["haste-active-mining"].tiers["2"], { amplifier: 3, "duration-ticks": 160 },
    "改名しても行の中身(amplifier/duration-ticks)は保持されるはず");
});

test("tierTableEditor: 行を全部消すと tiers キー自体が消える(空オブジェクトを残さない)", () => {
  const data = baselineMiningData();
  data["vein-mining"].tiers = { 1: { "max-extra-blocks": 8 }, 3: { "max-extra-blocks": 64 } };
  const form = window.buildMiningGimmickForm(data);
  const tierRows = collectAll(form.element, (el) => hasClass(el, "tier-row"))
    .filter((r) => {
      const numInputs = r.children.filter((c) => c.tag === "input" && !hasClass(c, "tier-key-input"));
      return numInputs.length === 1; // vein-mining は1列のみ(haste=2列と区別)
    });
  assert.equal(tierRows.length, 2, "vein-mining のtier行が2件見つかるはず");

  for (const row of tierRows) {
    const delBtn = row.children.find((c) => c.attrs && c.attrs.text === "×");
    assert.ok(delBtn, "削除ボタンが見つかること");
    delBtn._onclick();
  }
  const saved = form.getData();
  assert.equal("tiers" in saved["vein-mining"], false, "全行削除後は vein-mining.tiers キー自体が消えるはず");
});

// ---- タスク3: 一括伐採の死んだキー(small/large-max-extra-logs)の掃除 ----

test("buildWoodcuttingGimmickForm: small/large-max-extra-logs は保存後に残らず、max-extra-logsに統合される", () => {
  const data = {
    "tree-fell": {
      "small-max-extra-logs": 8,
      "large-max-extra-logs": 64,
      "cooldown-ticks": 200
    },
    "drop-tables": { categories: {} }
  };
  const form = window.buildWoodcuttingGimmickForm(data);
  const saved = form.getData();
  assert.equal("small-max-extra-logs" in saved["tree-fell"], false, "small-max-extra-logsが残っている(ゴミキー掃除が効いていない)");
  assert.equal("large-max-extra-logs" in saved["tree-fell"], false, "large-max-extra-logsが残っている(ゴミキー掃除が効いていない)");
});

test("buildWoodcuttingGimmickForm: 出荷yml相当(既にmax-extra-logs+tiers)を読み込んでも変形しない", () => {
  const data = {
    "tree-fell": {
      "max-extra-logs": 8,
      "cooldown-ticks": 200,
      tiers: { 1: { "max-extra-logs": 8 }, 3: { "max-extra-logs": 64 } }
    },
    "drop-tables": {
      categories: {
        apple: { "display-name": "リンゴ", "trigger-chance-percent": 5.0, entries: [{ item: "APPLE", weight: 1, amount: 1 }] }
      }
    }
  };
  const original = JSON.parse(JSON.stringify(data));
  const form = window.buildWoodcuttingGimmickForm(data);
  const saved = form.getData();
  assert.deepEqual(saved["tree-fell"], original["tree-fell"]);
});

// ---- buildFarmingGimmickForm: area-harvest の tier表(実yml側は既定でtiers無し) ----

test("buildFarmingGimmickForm: area-harvest に tiers が無い状態で開いて保存しても tiers キーは作られない", () => {
  const data = { "area-harvest": { radius: 1 }, "animal-damage-4x": { multiplier: 4.0 }, "bee-no-aggro": { "calm-radius": 8.0 } };
  const form = window.buildFarmingGimmickForm(data);
  const saved = form.getData();
  assert.equal("tiers" in saved["area-harvest"], false);
});

test("buildFarmingGimmickForm: area-harvest に「+ tier追加」すると tiers.1 が radius 0 で作られる", () => {
  const data = { "area-harvest": { radius: 2 }, "animal-damage-4x": { multiplier: 4.0 }, "bee-no-aggro": { "calm-radius": 8.0 } };
  const form = window.buildFarmingGimmickForm(data);
  const addBtn = findByText(form.element, "+ tier追加");
  assert.ok(addBtn);
  addBtn._onclick();
  const saved = form.getData();
  assert.deepEqual(saved["area-harvest"].tiers, { 1: { radius: 0 } });
});
