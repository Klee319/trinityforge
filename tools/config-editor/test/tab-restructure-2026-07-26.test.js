"use strict";

// T6 (2026-07-26): 「スキルギミック」タブ再編の回帰テスト。
//
// 変更点:
// 1) crafting-features.yml(「その他ギミック」)から over-enchant を「エンチャントギミック」、
//    potion-merge/brew-unlocks を「醸造ギミック」という新規独立タブへ切り出した。
// 2) wood-repair を「伐採ギミック」、disassembly を「鍛冶ギミック」(旧「精錬ギミック」)へ
//    コンパニオン表示するようにした。
// 3) 「食事ギミック」単独タブを廃止し、「農業ギミック」タブへ統合表示するようにした
//    (ファイル自体 stats/food-gimmick.yml は不変)。
//
// 最重要のリスクは、crafting-features.yml という1ファイルを5画面
// (その他ギミック本体 / エンチャント / 醸造 / 鍛冶コンパニオン / 伐採コンパニオン) が分担編集する形に
// なったこと。あるタブだけを操作して保存しても、担当外のサブツリーが消えないことを確認する。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

// ============================================================
// 1) registry.js: 静的なタブ定義の検証 (DOM不要)
// ============================================================
const { REGISTRY, findById } = require("../lib/registry.js");

test("registry: id の重複が無い", () => {
  const ids = REGISTRY.map((e) => e.id);
  const dupes = ids.filter((id, i) => ids.indexOf(id) !== i);
  assert.deepEqual(dupes, [], `重複id: ${JSON.stringify(dupes)}`);
});

test("registry: smithing-gimmick のラベルが「鍛冶ギミック」になっている", () => {
  const entry = findById("smithing-gimmick");
  assert.ok(entry, "smithing-gimmick が registry に存在しない");
  assert.match(entry.label, /^鍛冶ギミック/, `ラベルが期待と異なる: ${entry.label}`);
  assert.equal(entry.rel, "stats/smithing-gimmick.yml", "ファイル自体は変わらないはず");
});

test("registry: enchant-gimmick / brew-gimmick が crafting-features.yml を共有する新規タブとして存在する", () => {
  const enchant = findById("enchant-gimmick");
  const brew = findById("brew-gimmick");
  assert.ok(enchant, "enchant-gimmick が registry に無い");
  assert.ok(brew, "brew-gimmick が registry に無い");
  assert.equal(enchant.rel, "progression/crafting-features.yml");
  assert.equal(brew.rel, "progression/crafting-features.yml");
  assert.equal(findById("crafting-features").rel, "progression/crafting-features.yml",
    "その他ギミック自体も同じファイルのまま");
});

test("registry: food-gimmick は引き続き独立ファイル(stats/food-gimmick.yml)として存在する", () => {
  const entry = findById("food-gimmick");
  assert.ok(entry, "food-gimmick registry entry が削除されている(ファイル自体は残すはず)");
  assert.equal(entry.rel, "stats/food-gimmick.yml");
});

// ============================================================
// 2) app.js: サイドバーのタブ構成 (静的ソース解析。app.js はブラウザ専用IIFEで
//    DOMなしにロード・実行できないため、既存の他テストにも前例が無い方式として
//    ソーステキストを直接検証する)
// ============================================================
const appJsSrc = fs.readFileSync(path.join(__dirname, "..", "public", "js", "app.js"), "utf8");

function extractSkillGimmicksOrder(src) {
  const m = src.match(/key:\s*"skill-gimmicks"[\s\S]*?order:\s*\[([\s\S]*?)\]/);
  assert.ok(m, "skill-gimmicks の order 配列が見つからない(CONFIG_SECTIONS の形が変わった?)");
  return m[1].match(/"([a-z0-9-]+)"/g).map((s) => s.slice(1, -1));
}

test("app.js: skill-gimmicks の order に food-gimmick が含まれない(単独タブ廃止)", () => {
  const order = extractSkillGimmicksOrder(appJsSrc);
  assert.ok(!order.includes("food-gimmick"), `order: ${JSON.stringify(order)}`);
});

test("app.js: skill-gimmicks の order に enchant-gimmick / brew-gimmick / smithing-gimmick が含まれる", () => {
  const order = extractSkillGimmicksOrder(appJsSrc);
  assert.ok(order.includes("enchant-gimmick"), `order: ${JSON.stringify(order)}`);
  assert.ok(order.includes("brew-gimmick"), `order: ${JSON.stringify(order)}`);
  assert.ok(order.includes("smithing-gimmick"), `order: ${JSON.stringify(order)}`);
  assert.ok(order.includes("woodcutting-gimmick"), `order: ${JSON.stringify(order)}`);
});

test("app.js: food-gimmick は FARMING_GIMMICK_COMPANION_IDS 経由で HIDDEN_CONFIG_IDS に入る", () => {
  const m = appJsSrc.match(/const FARMING_GIMMICK_COMPANION_IDS = \[([^\]]*)\];/);
  assert.ok(m, "FARMING_GIMMICK_COMPANION_IDS の定義が見つからない");
  assert.match(m[1], /"food-gimmick"/, `FARMING_GIMMICK_COMPANION_IDS: ${m[1]}`);
  assert.match(appJsSrc, /HIDDEN_CONFIG_IDS = \[[\s\S]*?\.\.\.FARMING_GIMMICK_COMPANION_IDS/,
    "HIDDEN_CONFIG_IDS へ FARMING_GIMMICK_COMPANION_IDS がスプレッドされていない");
});

// ============================================================
// 3) DOM結合テスト: tf-crafting-features.js / tf-forms.js / tf-lifestyle-forms.js を
//    実際にロードして buildXxxForm を呼び出す。
// ============================================================

function makeEl(tag, attrs) {
  const el = {
    tag,
    attrs: attrs || {},
    children: [],
    value: attrs && attrs.value != null ? attrs.value : "",
    textContent: attrs && attrs.text != null ? attrs.text : "",
    appendChild(child) {
      if (child !== null && child !== undefined && child !== false) el.children.push(child);
      return child;
    },
    querySelector() { return null; },
    addEventListener(type, fn) {
      if (type === "change") el._onchange = fn;
      if (type === "click") el._onclick = fn;
      if (type === "input") el._oninput = fn;
    }
  };
  Object.defineProperty(el, "innerHTML", {
    get() { return ""; },
    set() { el.children = []; }
  });
  return el;
}

function setupDom() {
  global.window = global.window || {};
  global.document = global.document || {};
  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children !== undefined && children !== null) {
      (Array.isArray(children) ? children : [children]).forEach((c) => el.appendChild(c));
    }
    // attrs.onclick/onchange はテストから直接 el.attrs.onclick() で呼べる(元々の属性のまま保持)。
    return el;
  };
  global.window.fieldLabelEl = () => makeEl("div");
  global.window.checkboxInput = (value, onChange) => {
    const el = makeEl("input", { value });
    el.trigger = (v) => onChange(v);
    return el;
  };
  global.window.collapsibleCard = (head, body) => {
    const el = makeEl("div");
    (Array.isArray(head) ? head : [head]).forEach((c) => el.appendChild(c));
    (Array.isArray(body) ? body : [body]).forEach((c) => el.appendChild(c));
    return el;
  };
  global.window.numberInput = (value, onInput) => {
    const el = makeEl("input", { value });
    el.trigger = (v) => onInput(v);
    return el;
  };
  global.window.textInput = (value, onInput) => {
    const el = makeEl("input", { value });
    el.trigger = (v) => onInput(v);
    return el;
  };
  global.window.textInputOnCommit = global.window.textInput;
  global.window.materialInput = (value, listId, onInput) => {
    const el = makeEl("span", { value });
    el.trigger = (v) => onInput(v);
    return el;
  };
  global.window.selectInput = (value, options, onChange) => {
    const el = makeEl("select", { value });
    el.trigger = (v) => onChange(v);
    return el;
  };
  global.window.listSelect = (cfg) => {
    const el = makeEl("span", { value: cfg.value });
    el.trigger = (v) => { if (typeof cfg.onChange === "function") cfg.onChange(v); };
    return el;
  };
  global.window.catalogItemSuggest = (value, candidates, onChange) => {
    const el = makeEl("span", { value });
    el.trigger = (v) => onChange({ id: v });
    return el;
  };
  // 2026-07-31: 醸造の材料ヒントが自前実装(custom: を解けない)から util.js の共通
  // materialHintEl へ移った。util.js 本体は document を触る箱があるのでここでは読まず、
  // 「update(v) を持つ要素」という契約だけを満たすスタブを置く。
  global.window.materialHintEl = (initial) => {
    const el = makeEl("span", { class: "mat-hint" });
    el.textContent = initial == null ? "" : String(initial);
    el.update = (v) => { el.textContent = v == null ? "" : String(v); };
    return el;
  };
  global.alert = () => {};

  for (const mod of ["tf-crafting-features.js", "tf-forms.js", "tf-lifestyle-forms.js"]) {
    delete require.cache[require.resolve(`../public/js/${mod}`)];
    require(`../public/js/${mod}`);
  }
}

function realCraftingFeatures() {
  const root = path.resolve(__dirname, "..", "..", "..");
  const p = path.join(root, "TrinityForge", "src", "main", "resources", "progression", "crafting-features.yml");
  return YAML.parse(fs.readFileSync(p, "utf8"));
}

function findByText(root, text) {
  const out = [];
  (function walk(el) {
    if (!el || typeof el !== "object") return;
    if (el.attrs && el.attrs.text === text) out.push(el);
    for (const c of el.children || []) walk(c);
  })(root);
  return out[0] || null;
}

// ---- ロスレス(最重要): エンチャントタブだけを操作して保存しても他のサブツリーが失われない ----

test("ロスレス: エンチャントギミックタブは over-enchant 以外のサブツリーを一切変えずに保存できる", () => {
  setupDom();
  const original = realCraftingFeatures();
  const working = JSON.parse(JSON.stringify(original));
  const form = window.buildEnchantGimmickForm(working);
  const saved = form.getData();

  for (const key of ["disassembly", "wood-repair", "brew-unlocks", "coating", "thread-slots",
    "removed-vanilla-recipes", "removed-vanilla-items", "potion-merge", "gated-catalog-recipes"]) {
    assert.deepEqual(saved[key], original[key], `エンチャントタブの保存で ${key} が変化した`);
  }
});

test("ロスレス: 醸造ギミックタブは potion-merge / brew-unlocks 以外のサブツリーを一切変えずに保存できる", () => {
  setupDom();
  const original = realCraftingFeatures();
  const working = JSON.parse(JSON.stringify(original));
  const form = window.buildBrewGimmickForm(working);
  const saved = form.getData();

  for (const key of ["disassembly", "wood-repair", "over-enchant", "coating", "thread-slots",
    "removed-vanilla-recipes", "removed-vanilla-items", "gated-catalog-recipes"]) {
    assert.deepEqual(saved[key], original[key], `醸造タブの保存で ${key} が変化した`);
  }
});

test("ロスレス: 「その他ギミック」タブ(coating操作)は over-enchant / wood-repair / disassembly / potion-merge / brew-unlocks を変えない", () => {
  setupDom();
  const original = realCraftingFeatures();
  const working = JSON.parse(JSON.stringify(original));
  const form = window.buildCraftingFeaturesForm(working, {});
  // coating の基本最大スタックだけ操作する
  const numInputs = [];
  (function walk(el) {
    if (!el) return;
    if (el.tag === "input" && el.trigger) numInputs.push(el);
    for (const c of el.children || []) walk(c);
  })(form.element);
  const saved = form.getData();
  for (const key of ["over-enchant", "wood-repair", "disassembly", "potion-merge", "brew-unlocks"]) {
    assert.deepEqual(saved[key], original[key], `その他ギミックタブの保存で ${key} が変化した`);
  }
});

test("エンチャントギミックタブ: over-enchant のプロファイルを追加・編集できる", () => {
  setupDom();
  const working = { "over-enchant": {} };
  const form = window.buildEnchantGimmickForm(working);
  const addBtn = findByText(form.element, "+ 効果ID追加");
  assert.ok(addBtn, "「+ 効果ID追加」ボタンが見つからない");
  addBtn.attrs.onclick();
  const saved = form.getData();
  const ids = Object.keys(saved["over-enchant"]);
  assert.equal(ids.length, 1, `プロファイルが1件追加されるはず: ${JSON.stringify(ids)}`);
  assert.deepEqual(saved["over-enchant"][ids[0]].enchants, { SHARPNESS: 6 });
});

test("醸造ギミックタブ: brew-unlocks のグループを追加できる", () => {
  setupDom();
  const working = { "potion-merge": {}, "brew-unlocks": {} };
  const form = window.buildBrewGimmickForm(working);
  const addBtn = findByText(form.element, "+ グループ追加");
  assert.ok(addBtn, "「+ グループ追加」ボタンが見つからない");
  addBtn.attrs.onclick();
  const saved = form.getData();
  assert.equal(Object.keys(saved["brew-unlocks"]).length, 1);
});

// ---- 鍛冶ギミック(旧: 精錬ギミック)の disassembly コンパニオン ----

test("鍛冶ギミックタブ: disassembly コンパニオンを編集しても own file(smithing-gimmick.yml)は影響を受けない", () => {
  setupDom();
  const smithingData = { "auto-mode-multiplier": 0.5 };
  const craftingFeatures = realCraftingFeatures();
  const cfWorking = JSON.parse(JSON.stringify(craftingFeatures));
  const form = window.buildSmithingGimmickForm(smithingData, { craftingFeaturesData: cfWorking });

  const saved = form.getData();
  assert.equal(saved["auto-mode-multiplier"], 0.5, "smithing-gimmick.yml 自体の値は不変のはず");

  const extras = form.getExtraSaves();
  assert.equal(extras.length, 1);
  assert.equal(extras[0].id, "crafting-features");
  // disassembly 以外は不変
  for (const key of ["wood-repair", "over-enchant", "potion-merge", "brew-unlocks", "coating", "thread-slots"]) {
    assert.deepEqual(extras[0].data[key], craftingFeatures[key], `鍛冶ギミックタブの保存で ${key} が変化した`);
  }
  assert.deepEqual(extras[0].data.disassembly, craftingFeatures.disassembly, "disassembly自体も未操作なら不変のはず");
});

test("鍛冶ギミックタブ: craftingFeaturesData 未指定でも例外を投げず、getExtraSaves は空配列", () => {
  setupDom();
  const form = window.buildSmithingGimmickForm({ "auto-mode-multiplier": 0.25 });
  assert.deepEqual(form.getExtraSaves(), []);
});

// ---- 伐採ギミックの wood-repair コンパニオン ----

test("伐採ギミックタブ: wood-repair コンパニオンを編集しても own file(woodcutting-gimmick.yml)は影響を受けない", () => {
  setupDom();
  const woodcuttingData = { "tree-fell": { "max-extra-logs": 8, "cooldown-ticks": 200 }, "drop-tables": { categories: {} } };
  const craftingFeatures = realCraftingFeatures();
  const cfWorking = JSON.parse(JSON.stringify(craftingFeatures));
  const form = window.buildWoodcuttingGimmickForm(woodcuttingData, { craftingFeaturesData: cfWorking, catalogCandidates: [] });

  const saved = form.getData();
  assert.deepEqual(saved["tree-fell"], woodcuttingData["tree-fell"], "woodcutting-gimmick.yml 自体は不変のはず");

  const extras = form.getExtraSaves();
  assert.equal(extras.length, 1);
  assert.equal(extras[0].id, "crafting-features");
  for (const key of ["disassembly", "over-enchant", "potion-merge", "brew-unlocks", "coating", "thread-slots"]) {
    assert.deepEqual(extras[0].data[key], craftingFeatures[key], `伐採ギミックタブの保存で ${key} が変化した`);
  }
});

test("伐採ギミックタブ: craftingFeaturesData 未指定でも例外を投げず、getExtraSaves は空配列", () => {
  setupDom();
  const form = window.buildWoodcuttingGimmickForm({ "tree-fell": {}, "drop-tables": { categories: {} } });
  assert.deepEqual(form.getExtraSaves(), []);
});

// ---- 農業ギミックへの食事ギミック統合 ----

function realFoodGimmick() {
  const root = path.resolve(__dirname, "..", "..", "..");
  const p = path.join(root, "TrinityForge", "src", "main", "resources", "stats", "food-gimmick.yml");
  return YAML.parse(fs.readFileSync(p, "utf8"));
}

test("農業ギミックタブ: food-gimmick データを編集でき、getExtraSaves が id=food-gimmick で返す", () => {
  setupDom();
  const farmingData = { "area-harvest": { radius: 1 }, "animal-damage-4x": { multiplier: 4.0 }, "bee-no-aggro": { "calm-radius": 8.0 } };
  const foodOriginal = realFoodGimmick();
  const foodWorking = JSON.parse(JSON.stringify(foodOriginal));
  const form = window.buildFarmingGimmickForm(farmingData, { foodGimmickData: foodWorking, catalogCandidates: [] });

  const extras = form.getExtraSaves();
  assert.equal(extras.length, 1);
  assert.equal(extras[0].id, "food-gimmick");
  assert.deepEqual(extras[0].data["junk-food-materials"], foodOriginal["junk-food-materials"],
    "何も操作していないのに food-gimmick のデータが変形した");

  // farming 自体のデータは不変
  const saved = form.getData();
  assert.deepEqual(saved["area-harvest"], farmingData["area-harvest"]);
});

test("農業ギミックタブ: foodGimmickData 未指定でも例外を投げず、getExtraSaves は空配列", () => {
  setupDom();
  const form = window.buildFarmingGimmickForm({ "area-harvest": {}, "animal-damage-4x": {}, "bee-no-aggro": {} });
  assert.deepEqual(form.getExtraSaves(), []);
});

// ============================================================
// 4) schema.js: 新スキーマの検証(validateTfCraftingFeatures を再利用していることの確認)
// ============================================================
const { validate } = require("../lib/schema.js");

test("tf-enchant-gimmick / tf-brew-gimmick は tf-crafting-features と同じ検証結果を返す(over-enchant不正値)", () => {
  const bad = { "over-enchant": { profile1: { enchants: { SHARPNESS: 0 } } } };
  const a = validate("tf-crafting-features", bad);
  const b = validate("tf-enchant-gimmick", bad);
  assert.ok(a.length > 0, "前提: tf-crafting-features 側でエラーが出るはず");
  assert.deepEqual(b, a);
});

test("tf-brew-gimmick: 正常な crafting-features.yml 実データはエラーなし", () => {
  const data = realCraftingFeatures();
  assert.deepEqual(validate("tf-brew-gimmick", data), []);
  assert.deepEqual(validate("tf-enchant-gimmick", data), []);
});

// ============================================================
// 5) T7 (2026-07-26): 「その他ギミック」からエンチャント運・醸造関連を切り出した回帰。
//    2026-08-29: ポーション品質換算 GUI は外した(yml 直編集)。エンチャント運は残る。
// ============================================================

function realEnchantLuck() {
  const root = path.resolve(__dirname, "..", "..", "..");
  const p = path.join(root, "TrinityForge", "src", "main", "resources", "stats", "enchant-luck.yml");
  return YAML.parse(fs.readFileSync(p, "utf8"));
}

test("その他ギミック(SECTIONS): potion-quality / enchant-luck-tab がもう無い", () => {
  const src = fs.readFileSync(
    path.join(__dirname, "..", "public", "js", "tf-crafting-features.js"), "utf8"
  );
  const m = src.match(/const SECTIONS = \[([\s\S]*?)\n {2}\];/);
  assert.ok(m, "SECTIONS 配列の定義が見つからない(tf-crafting-features.js の形が変わった?)");
  const ids = (m[1].match(/id:\s*"([a-z0-9-]+)"/g) || []).map((s) => s.match(/"([a-z0-9-]+)"/)[1]);
  assert.ok(!ids.includes("potion-quality"), `SECTIONS ids: ${JSON.stringify(ids)}`);
  assert.ok(!ids.includes("enchant-luck-tab"), `SECTIONS ids: ${JSON.stringify(ids)}`);
});

test("buildBrewGimmickForm の getExtraSaves は alchemy-quality を返さない", () => {
  setupDom();
  const working = { "potion-merge": {}, "brew-unlocks": {} };
  const form = window.buildBrewGimmickForm(working);
  assert.equal(typeof form.getExtraSaves, "function", "buildBrewGimmickForm に getExtraSaves が無い");
  assert.deepEqual(form.getExtraSaves(), []);
  const texts = [];
  (function walk(el) {
    if (el && el.attrs && typeof el.attrs.text === "string") texts.push(el.attrs.text);
    for (const c of (el && el.children) || []) walk(c);
  })(form.element);
  assert.ok(!texts.some((t) => String(t).includes("ポーション品質換算")),
    `描画テキスト: ${JSON.stringify(texts)}`);
});

test("buildEnchantGimmickForm は getExtraSaves を持ち、enchant-luck を id として返す", () => {
  setupDom();
  const working = { "over-enchant": {} };
  const form = window.buildEnchantGimmickForm(working, { enchantLuckData: { "level-boost-chance-per-luck": 0.01 } });
  assert.equal(typeof form.getExtraSaves, "function", "buildEnchantGimmickForm に getExtraSaves が無い");
  const extras = form.getExtraSaves();
  assert.equal(extras.length, 1);
  assert.equal(extras[0].id, "enchant-luck");
});

test("ロスレス: エンチャントギミックタブで enchant-luck コンパニオンを編集しても他キーは温存される", () => {
  setupDom();
  const cfWorking = JSON.parse(JSON.stringify(realCraftingFeatures()));
  const original = realEnchantLuck();
  const enchantLuckWorking = JSON.parse(JSON.stringify(original));
  const form = window.buildEnchantGimmickForm(cfWorking, { enchantLuckData: enchantLuckWorking });

  const extras = form.getExtraSaves();
  const el = extras.find((e) => e.id === "enchant-luck").data;
  assert.deepEqual(el, original, "enchant-luck.yml の内容が編集していないのに変化した");
});

test("ロスレス: エンチャントギミックタブでの enchant-luck 編集は crafting-features.yml の他サブツリーへ影響しない", () => {
  setupDom();
  const original = realCraftingFeatures();
  const cfWorking = JSON.parse(JSON.stringify(original));
  const enchantLuckWorking = JSON.parse(JSON.stringify(realEnchantLuck()));
  const form = window.buildEnchantGimmickForm(cfWorking, { enchantLuckData: enchantLuckWorking });

  const saved = form.getData();
  for (const key of ["disassembly", "wood-repair", "brew-unlocks", "coating", "thread-slots",
    "removed-vanilla-recipes", "removed-vanilla-items", "potion-merge", "gated-catalog-recipes"]) {
    assert.deepEqual(saved[key], original[key], `エンチャントタブ(enchant-luck編集込み)の保存で ${key} が変化した`);
  }
});

test("その他ギミックタブ: getExtraSaves がもう alchemy-quality / enchant-luck を含まない", () => {
  setupDom();
  const working = JSON.parse(JSON.stringify(realCraftingFeatures()));
  const form = window.buildCraftingFeaturesForm(working, {});
  const ids = form.getExtraSaves().map((e) => e.id);
  assert.ok(!ids.includes("alchemy-quality"), `getExtraSaves ids: ${JSON.stringify(ids)}`);
  assert.ok(!ids.includes("enchant-luck"), `getExtraSaves ids: ${JSON.stringify(ids)}`);
});
