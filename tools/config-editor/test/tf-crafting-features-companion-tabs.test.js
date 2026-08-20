"use strict";

// public/js/tf-crafting-features.js の buildBrewGimmickForm / buildEnchantGimmickForm 回帰テスト。
//
// 背景:
// - T5 (2026-07-25): ポーション品質換算(alchemy-quality.yml)/エンチャント運(enchant-luck.yml)は
//   「ステータス定義」画面ではなく「スキルギミック」>「その他のギミック」タブに表示すべき、という
//   ユーザー要望に基づき表示移設した(このファイルは元々その回帰テストだった)。
// - T7 (2026-07-26): 「その他ギミックから、エンチャント関連・醸造関連はそれぞれのタブへ切り出す」という
//   追加のユーザー指示により、ポーション品質換算は「醸造ギミック」(buildBrewGimmickForm)、
//   エンチャント運は「エンチャントギミック」(buildEnchantGimmickForm)へさらに表示移設した。
//   保存先configのYAMLキーパスは不変(alchemy-quality / enchant-luck のまま)で、鍛冶/伐採ギミックの
//   crafting-features コンパニオン(craftingFeaturesData)と同じ getExtraSaves 方式で編集する。
//
// 1) buildBrewGimmickForm / buildEnchantGimmickForm がそれぞれのコンパニオンフィールドを描画すること。
// 2) getExtraSaves() が主保存先(alchemy-quality / enchant-luck)を id 不変で返し、
//    渡したデータオブジェクトをそのまま(キー追加のみで、既存キー破壊なく)保持していること
//    (= 保存先キーパスが変わっていないことの確認)。
// 3) コンパニオン未指定でも例外を投げず getExtraSaves が空配列を返すこと(鍛冶/伐採ギミックと同じ契約)。
// 4) buildCraftingFeaturesForm(「その他ギミック」)側にはもうこの2タブが存在しないこと。

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

test("醸造ギミックタブ: alchemyQualityData 指定時、ポーション品質換算のフィールドを描画する", () => {
  setupStubs();
  const alchemyQualityData = { "duration-ticks-per-quality": 20, "amplifier-per-quality": 0.5 };
  const result = window.buildBrewGimmickForm(
    { "potion-merge": {}, "brew-unlocks": {} }, { alchemyQualityData }
  );
  const texts = findAllText(result.element);
  assert.ok(texts.includes("ポーション品質換算 (stat: potion_quality_bonus)"),
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

test("醸造ギミックタブ getExtraSaves: potion-merge/brew-unlocks 本体には無関係に alchemy-quality を id 不変で返す(キーパス維持)", () => {
  setupStubs();
  const alchemyQualityData = { "duration-ticks-per-quality": 99 };
  const result = window.buildBrewGimmickForm(
    { "potion-merge": {}, "brew-unlocks": {} }, { alchemyQualityData }
  );
  const extras = result.getExtraSaves();
  assert.equal(extras.length, 1);
  assert.equal(extras[0].id, "alchemy-quality");
  assert.equal(extras[0].data["duration-ticks-per-quality"], 99, "既存キーの値が保持されていません(往復ロス)");
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

test("醸造ギミックタブ getExtraSaves: alchemyQualityData 未指定でも例外を投げず、空オブジェクトから既定値を補って返す", () => {
  setupStubs();
  const result = window.buildBrewGimmickForm({ "potion-merge": {}, "brew-unlocks": {} });
  assert.deepEqual(result.getExtraSaves(), []);
});

test("エンチャントギミックタブ getExtraSaves: enchantLuckData 未指定でも例外を投げず、空配列を返す", () => {
  setupStubs();
  const result = window.buildEnchantGimmickForm({ "over-enchant": {} });
  assert.deepEqual(result.getExtraSaves(), []);
});

test("醸造ギミックタブ: alchemyQualityData 指定時、既定値を補って alchemy-quality を返す", () => {
  setupStubs();
  const result = window.buildBrewGimmickForm(
    { "potion-merge": {}, "brew-unlocks": {} }, { alchemyQualityData: {} }
  );
  const aq = result.getExtraSaves().find((e) => e.id === "alchemy-quality").data;
  assert.equal(aq["duration-ticks-per-quality"], 20.0);
  assert.equal(aq["amplifier-per-quality"], 0.5);
  assert.equal(aq["lingering-splash-duration-ticks-per-quality"], 10.0);
});

test("エンチャントギミックタブ: enchantLuckData 指定時、既定値を補って enchant-luck を返す", () => {
  setupStubs();
  const result = window.buildEnchantGimmickForm({ "over-enchant": {} }, { enchantLuckData: {} });
  const el = result.getExtraSaves().find((e) => e.id === "enchant-luck").data;
  assert.equal(el["level-boost-chance-per-luck"], 0.01);
  assert.equal(el["level-boost-max-steps"], 2);
  assert.equal(el["overenchant-bonus-chance-per-luck"], 0.02);
  assert.equal(el["extra-enchant-chance-per-luck"], 0.005);
});
