"use strict";

// public/js/tf-crafting-features.js の buildBrewGimmickForm / buildEnchantGimmickForm 回帰テスト。
//
// 背景:
// - T5/T7: ポーション品質換算・エンチャント運を「その他ギミック」から各専用タブへ移設した。
// - 2026-08-29: ポーション品質換算の GUI を外した(係数は本体が alchemy-quality.yml を直接読む。
//   gathering-efficiency と同じ yml 直編集)。エンチャント運の GUI はエンチャントギミックに残る。
//
// 1) 醸造ギミックはポーション品質換算カードを描かない / getExtraSaves は空。
// 2) エンチャントギミックは enchant-luck を id 不変で getExtraSaves する。
// 3) コンパニオン未指定でも例外を投げず getExtraSaves が空配列を返す。
// 4) buildCraftingFeaturesForm(「その他ギミック」)側にはもうこの2タブが存在しない。

const test = require("node:test");
const assert = require("node:assert/strict");

function makeEl(tag, props) {
  const el = {
    tag,
    props: props || {},
    children: [],
    appendChild(c) { this.children.push(c); return c; },
    set innerHTML(_v) { this.children = []; },
    get innerHTML() { return ""; }
  };
  return el;
}

function setupStubs() {
  global.window = global.window || {};
  global.document = global.document || {};
  global.window.h = (tag, props, children) => {
    const el = makeEl(tag, props);
    if (Array.isArray(children)) children.forEach((c) => c != null && el.appendChild(c));
    else if (children != null) el.appendChild(children);
    return el;
  };
  global.window.numberInput = (value) => makeEl("input", { class: "num", value });
  global.window.checkboxInput = () => makeEl("input", { class: "checkbox" });
  global.window.textInput = (value) => makeEl("input", { class: "text", value });
  global.window.textInputOnCommit = global.window.textInput;
  global.window.selectInput = () => makeEl("select");
  global.window.materialInput = () => makeEl("div", { class: "material-input" });
  delete require.cache[require.resolve("../public/js/tf-crafting-features.js")];
  require("../public/js/tf-crafting-features.js");
}

// 再帰的にツリーを走査して text を持つ要素を探す(タブ切り替えを伴わない静的描画の確認用)。
function findAllText(el, out) {
  out = out || [];
  if (el && el.props && typeof el.props.text === "string") out.push(el.props.text);
  for (const c of (el && el.children) || []) findAllText(c, out);
  return out;
}

test("その他ギミック(SECTIONS): ポーション品質換算 / エンチャント運 タブはもう存在しない", () => {
  setupStubs();
  const result = window.buildCraftingFeaturesForm({}, {});
  const tabsEl = result.element.children[0];
  const labels = tabsEl.children.map((b) => b.children.map((s) => s.props && s.props.text).find(Boolean));
  assert.ok(!labels.includes("ポーション品質換算"), `タブ一覧: ${JSON.stringify(labels)}`);
  assert.ok(!labels.includes("エンチャント運"), `タブ一覧: ${JSON.stringify(labels)}`);
});

test("その他ギミック: getExtraSaves はもう ars-config のみを返す(alchemy-quality / enchant-luck は含まない)", () => {
  setupStubs();
  const result = window.buildCraftingFeaturesForm({}, { arsConfigData: { mana: {} } });
  const ids = result.getExtraSaves().map((e) => e.id);
  assert.deepEqual(ids, ["ars-config"]);
});

test("醸造ギミックタブ: ポーション品質換算カードは描かない", () => {
  setupStubs();
  const result = window.buildBrewGimmickForm({ "potion-merge": {}, "brew-unlocks": {} });
  const texts = findAllText(result.element);
  assert.ok(!texts.includes("ポーション品質換算 (stat: potion_quality_bonus)"),
    `描画テキスト: ${JSON.stringify(texts)}`);
});

test("エンチャントギミックタブ: enchantLuckData 指定時、エンチャント運のフィールドを描画する", () => {
  setupStubs();
  const enchantLuckData = { "level-boost-chance-per-luck": 0.01 };
  const result = window.buildEnchantGimmickForm({ "over-enchant": {} }, { enchantLuckData });
  const texts = findAllText(result.element);
  assert.ok(texts.includes("エンチャント運 (stat: enchant_luck)"),
    `描画テキスト: ${JSON.stringify(texts)}`);
});

test("醸造ギミックタブ getExtraSaves: alchemy-quality はもう返さない", () => {
  setupStubs();
  const extras = window.buildBrewGimmickForm({ "potion-merge": {}, "brew-unlocks": {} }).getExtraSaves();
  assert.deepEqual(extras, []);
});

test("エンチャントギミックタブ getExtraSaves: over-enchant 本体には無関係に enchant-luck を id 不変で返す(キーパス維持)", () => {
  setupStubs();
  const enchantLuckData = { "level-boost-chance-per-luck": 0.42 };
  const result = window.buildEnchantGimmickForm({ "over-enchant": {} }, { enchantLuckData });
  const extras = result.getExtraSaves();
  assert.equal(extras.length, 1);
  assert.equal(extras[0].id, "enchant-luck");
  assert.equal(extras[0].data["level-boost-chance-per-luck"], 0.42, "既存キーの値が保持されていません(往復ロス)");
});

test("醸造ギミックタブ getExtraSaves: 未指定でも例外を投げず空配列を返す", () => {
  setupStubs();
  const result = window.buildBrewGimmickForm({ "potion-merge": {}, "brew-unlocks": {} });
  assert.deepEqual(result.getExtraSaves(), []);
});

test("エンチャントギミックタブ getExtraSaves: enchantLuckData 未指定でも例外を投げず、空配列を返す", () => {
  setupStubs();
  const result = window.buildEnchantGimmickForm({ "over-enchant": {} });
  assert.deepEqual(result.getExtraSaves(), []);
});

test("エンチャントギミックタブ: enchantLuckData 指定時、既定値を補って enchant-luck を返す", () => {
  setupStubs();
  const result = window.buildEnchantGimmickForm({ "over-enchant": {} }, { enchantLuckData: {} });
  const el = result.getExtraSaves().find((e) => e.id === "enchant-luck").data;
  assert.equal(el["level-boost-chance-per-luck"], 0.01);
  assert.equal(el["level-boost-max-steps"], 2);
  assert.equal(el["overenchant-bonus-chance-per-luck"], 0.02);
  assert.equal(el["extra-enchant-chance-per-luck"], 0.005);
  assert.equal(el["vanilla-parity-luck"], 10);
  assert.equal(el["level-nerf-chance-at-zero"], 0.5);
  assert.equal(el["level-nerf-max-steps"], 2);
});
