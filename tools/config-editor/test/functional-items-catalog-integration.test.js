"use strict";

// 2026-07-27: サイドバー「機能アイテム」カテゴリ新設 + TF特殊アイテム2件(skill_node_lock/
// skill_tree_reset, catalog.yml)を「特殊アイテム」(旧名: 機能アイテム, schema:
// ars-functional-items)画面へ ID ロック付きで統合する変更の回帰テスト。
//
// 対象:
//   1. lib/registry.js: functional-items/sourcelinks/sourcejars の section 移動
//   2. public/js/app.js: NAV_SECTIONS の「機能アイテム」グループ配置 + COMPANION_OPTION_KEYS
//      への catalog 追加 + ars-functional-items ケースでの catalog コンパニオン読み込み配線
//   3. public/js/functional-items.js: TF_SPECIAL_ITEM_IDS / ensureTfSpecialItems (純関数)
//   4. public/js/split-views.js: カタログ画面から TF 特殊アイテム2件を隠し、保存時はロスレスに
//      無編集のまま catalog.yml へ戻す

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const { REGISTRY, findById } = require("../lib/registry.js");

// ---- 1. registry.js ----

test("registry: functional-items / sourcelinks / sourcejars は section: functional-items へ移動している", () => {
  for (const id of ["functional-items", "sourcelinks", "sourcejars"]) {
    const entry = findById(id);
    assert.ok(entry, `${id} が registry に存在しない`);
    assert.equal(entry.section, "functional-items", `${id} の section が移動していない`);
  }
});

test("registry: functional-items の label は括弧を落とすと「特殊アイテム」になる", () => {
  const entry = findById("functional-items");
  const displayed = String(entry.label).replace(/\s*\([^)]*\)/g, "");
  assert.equal(displayed, "特殊アイテム");
});

test("registry: id/rel/base/schema はリネーム前と変わらない(既存の保存経路・パスを壊さない)", () => {
  const entry = findById("functional-items");
  assert.equal(entry.id, "functional-items");
  assert.equal(entry.rel, "functional-items.yml");
  assert.equal(entry.base, "arspaper");
  assert.equal(entry.schema, "ars-functional-items");
});

test("registry: functional-items セクション以外の既存 recipes-magic 系エントリは section が変わっていない", () => {
  for (const id of ["items", "glyphs", "ars-config", "ban"]) {
    const entry = findById(id);
    assert.equal(entry.section, "recipes-magic", `${id} の section が意図せず動いた`);
  }
});

// ---- 2. app.js ----

const appJsSrc = fs.readFileSync(path.join(__dirname, "..", "public", "js", "app.js"), "utf8");

test("app.js: NAV_SECTIONS に「機能アイテム」グループが configSectionKey 付きで存在する", () => {
  assert.match(appJsSrc, /title:\s*"機能アイテム",\s*\n\s*configSectionKey:\s*"functional-items"/,
    "NAV_SECTIONS の「機能アイテム」エントリが見当たらない");
});

test("app.js: 「機能アイテム」グループは NAV_SECTIONS 内で「アイテムステータス」の直後・「戦闘ツール」の直前に置かれている", () => {
  const statsIdx = appJsSrc.indexOf('title: "アイテムステータス"');
  const funcIdx = appJsSrc.indexOf('title: "機能アイテム"');
  const combatIdx = appJsSrc.indexOf('title: "戦闘ツール"');
  assert.ok(statsIdx >= 0 && funcIdx >= 0 && combatIdx >= 0, "見出し文字列のどれかが見つからない");
  assert.ok(statsIdx < funcIdx, "「機能アイテム」が「アイテムステータス」より前にある");
  assert.ok(funcIdx < combatIdx, "「機能アイテム」が「戦闘ツール」より後にある");
});

test("app.js: ALL_TOOL_VIEWS の reduce は views 無しの NAV_SECTIONS エントリでも例外にならない(s.views || [])", () => {
  assert.match(appJsSrc, /NAV_SECTIONS\.reduce\(\(acc, s\) => acc\.concat\(s\.views \|\| \[\]\), \[\]\)/,
    "ALL_TOOL_VIEWS の reduce が s.views || [] でガードされていない"
    + "(configSectionKey のみのエントリで s.views が undefined になり TypeError になる)");
});

test("app.js: CONFIG_SECTIONS の recipes-magic order から functional-items/sourcelinks/sourcejars が外れている", () => {
  const m = appJsSrc.match(/key: "recipes-magic"[\s\S]*?order: \[([^\]]*)\]/);
  assert.ok(m, "recipes-magic セクション定義が見つからない");
  for (const id of ["functional-items", "sourcelinks", "sourcejars"]) {
    assert.ok(!m[1].includes(`"${id}"`), `recipes-magic.order にまだ ${id} が残っている`);
  }
});

test("app.js: FUNCTIONAL_ITEMS_NAV_SECTION が3件を正しい順で order に持つ", () => {
  const m = appJsSrc.match(/FUNCTIONAL_ITEMS_NAV_SECTION = \{[\s\S]*?order: \[([^\]]*)\]/);
  assert.ok(m, "FUNCTIONAL_ITEMS_NAV_SECTION の定義が見つからない");
  const ids = m[1].split(",").map((s) => s.trim().replace(/"/g, "")).filter(Boolean);
  assert.deepEqual(ids, ["functional-items", "sourcelinks", "sourcejars"]);
});

test("app.js: COMPANION_OPTION_KEYS に catalog -> catalogData が登録されている", () => {
  assert.match(appJsSrc, /"catalog":\s*"catalogData"/,
    "COMPANION_OPTION_KEYS に catalog エントリが無い(catalog がマージされた際に画面が再構築されず消える)");
});

test("app.js: ars-functional-items ケースが catalog コンパニオンを loadConfigCompanion で読み込んで渡す", () => {
  assert.match(appJsSrc, /loadConfigCompanion\("catalog",\s*"catalogData",\s*options\)/,
    "ars-functional-items ケースで catalog のコンパニオン読み込みが見当たらない");
  assert.match(appJsSrc, /buildFunctionalItemsForm\(data,\s*\{\s*catalogData\s*\}\)/,
    "buildFunctionalItemsForm へ catalogData が渡されていない");
});

// ---- ドリフト検知: loadConfigCompanion(id, optKey) は全て COMPANION_OPTION_KEYS に載っているはず
// (companion-merge-apply.test.js と同じチェックだが、catalog 固有の見落としを個別に確認する) ----
test("app.js: loadConfigCompanion(\"catalog\", ...) の optKey は COMPANION_OPTION_KEYS[\"catalog\"] と一致する", () => {
  const callMatch = appJsSrc.match(/loadConfigCompanion\(\s*"catalog"\s*,\s*"([A-Za-z0-9_]+)"/);
  const mapMatch = appJsSrc.match(/"catalog":\s*"([A-Za-z0-9_]+)"/);
  assert.ok(callMatch && mapMatch, "呼び出し側/マップ側のどちらかが見つからない");
  assert.equal(callMatch[1], mapMatch[1], "loadConfigCompanion の optKey と COMPANION_OPTION_KEYS の値が食い違っている");
});

// ---- 3. functional-items.js (純関数) ----

const {
  TF_SPECIAL_ITEM_IDS,
  TF_SPECIAL_ITEM_LABELS,
  ensureTfSpecialItems,
  FUNCTIONAL_ITEM_IDS
} = require("../public/js/functional-items.js");

test("TF_SPECIAL_ITEM_IDS: 既存2件 + 券3件 + 2026-08-24追加の良薬3件のちょうど8件", () => {
  assert.deepEqual(TF_SPECIAL_ITEM_IDS.slice().sort(), [
    "exp_cleanse_tonic_greater", "exp_cleanse_tonic_lesser", "exp_cleanse_tonic_supreme",
    "quality_upgrade_ticket", "role_reselect_ticket", "skill_node_lock",
    "skill_tree_reset", "stat_reroll_ticket"
  ]);
});

test("TF_SPECIAL_ITEM_IDS は Ars の FUNCTIONAL_ITEM_IDS(8件)と重複しない", () => {
  for (const id of TF_SPECIAL_ITEM_IDS) {
    assert.ok(!FUNCTIONAL_ITEM_IDS.includes(id), `${id} が Ars 8件と重複している`);
  }
});

test("TF_SPECIAL_ITEM_LABELS は TF_SPECIAL_ITEM_IDS の全キーに日本語ラベルを持つ", () => {
  for (const id of TF_SPECIAL_ITEM_IDS) {
    assert.ok(TF_SPECIAL_ITEM_LABELS[id], `${id} のラベルが無い`);
  }
});

test("ensureTfSpecialItems: items が無ければ作り、2件を空オブジェクトで補完する", () => {
  const out = ensureTfSpecialItems({});
  assert.ok(out.items && typeof out.items === "object");
  for (const id of TF_SPECIAL_ITEM_IDS) {
    assert.deepEqual(out.items[id], {});
  }
});

test("ensureTfSpecialItems: 既存の catalog.yml エントリはそのまま保持する(内容を変更しない)", () => {
  const catalogData = {
    items: {
      skill_node_lock: { material: "AMETHYST_SHARD", "display-name": "スキルノードの楔", "enchant-glow": true },
      other_item: { material: "STONE" }
    }
  };
  const out = ensureTfSpecialItems(catalogData);
  assert.deepEqual(out.items.skill_node_lock, {
    material: "AMETHYST_SHARD", "display-name": "スキルノードの楔", "enchant-glow": true
  });
  assert.deepEqual(out.items.other_item, { material: "STONE" }, "無関係なアイテムを壊してはいけない");
});

test("ensureTfSpecialItems: 破壊的(clone しない) — 呼び出し側の catalogData 実体をそのまま返す", () => {
  const catalogData = { items: {} };
  const out = ensureTfSpecialItems(catalogData);
  assert.equal(out, catalogData, "clone すると catalog.yml のコンパニオン保存(getExtraSaves)が別実体を書き戻してしまう");
});

// ---- 4. split-views.js: カタログ画面から TF 特殊アイテム2件を隠す + ロスレス往復 ----

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

function setupSplitViewStubs(catalogFormFactory) {
  global.window = global.window || {};
  global.document = global.document || {};
  global.window.h = (tag, props, children) => {
    const el = makeEl(tag, props);
    if (Array.isArray(children)) children.forEach((c) => c != null && el.appendChild(c));
    else if (children != null) el.appendChild(children);
    return el;
  };
  global.window.FUNCTIONAL_ITEMS_CORE = { TF_SPECIAL_ITEM_IDS: ["skill_node_lock", "skill_tree_reset"] };
  global.window.buildCatalogForm = catalogFormFactory;
  global.window.renderEditorCategoryBar = () => makeEl("div");
  global.window.pruneEditorUiState = (d) => d;
  delete require.cache[require.resolve("../public/js/split-views.js")];
  require("../public/js/split-views.js");
}

test("split-views catalog: buildCatalogForm へ渡す items から TF 特殊アイテム2件が除かれる", () => {
  let receivedItemIds = null;
  setupSplitViewStubs((viewData) => {
    receivedItemIds = Object.keys(viewData.items).sort();
    return { element: makeEl("div"), getData: () => ({ ...viewData, items: { ...viewData.items } }) };
  });
  const catalogData = {
    items: {
      novus_criculus_luminis: { material: "NETHER_STAR" },
      skill_node_lock: { material: "AMETHYST_SHARD" },
      skill_tree_reset: { material: "ECHO_SHARD" }
    }
  };
  window.buildSplitConfigView({ type: "catalog", configId: "catalog", categoryKey: "other", data: catalogData });
  assert.deepEqual(receivedItemIds, ["novus_criculus_luminis"]);
});

test("split-views catalog: getData は隠した2件を無編集のまま catalog.yml へ書き戻す(ロスレス)", () => {
  setupSplitViewStubs((viewData) => ({
    element: makeEl("div"),
    getData: () => ({ ...viewData, items: { ...viewData.items } })
  }));
  const catalogData = {
    items: {
      novus_criculus_luminis: { material: "NETHER_STAR" },
      skill_node_lock: { material: "AMETHYST_SHARD", "display-name": "楔", lore: ["行1"] },
      skill_tree_reset: { material: "ECHO_SHARD", "display-name": "書" }
    },
    _editor: {
      categories: { other: [{ id: "cat1", label: "サブウェポン", itemIds: ["novus_criculus_luminis"] }] }
    }
  };
  const original = JSON.parse(JSON.stringify(catalogData));
  const view = window.buildSplitConfigView({ type: "catalog", configId: "catalog", categoryKey: "other", data: catalogData });
  const out = view.getData();
  assert.deepEqual(out.items.skill_node_lock, original.items.skill_node_lock, "skill_node_lock がロスレスに戻っていない");
  assert.deepEqual(out.items.skill_tree_reset, original.items.skill_tree_reset, "skill_tree_reset がロスレスに戻っていない");
  assert.deepEqual(out.items.novus_criculus_luminis, original.items.novus_criculus_luminis, "他のアイテムが変化した");
});

test("split-views catalog: フィルタ用クローンは浅いコピーのため、元の data.items は隠した2件を保持したまま", () => {
  setupSplitViewStubs((viewData) => ({ element: makeEl("div"), getData: () => viewData }));
  const catalogData = {
    items: {
      skill_node_lock: { material: "AMETHYST_SHARD" },
      skill_tree_reset: { material: "ECHO_SHARD" }
    }
  };
  window.buildSplitConfigView({ type: "catalog", configId: "catalog", categoryKey: "other", data: catalogData });
  assert.ok(Object.prototype.hasOwnProperty.call(catalogData.items, "skill_node_lock"));
  assert.ok(Object.prototype.hasOwnProperty.call(catalogData.items, "skill_tree_reset"));
});

test("split-views catalog: TF特殊アイテムが存在しない catalog.yml でも例外にならない(未検出時は素通し)", () => {
  let received = null;
  setupSplitViewStubs((viewData) => {
    received = viewData;
    return { element: makeEl("div"), getData: () => viewData };
  });
  const catalogData = { items: { some_item: { material: "STONE" } } };
  assert.doesNotThrow(() => {
    window.buildSplitConfigView({ type: "catalog", configId: "catalog", categoryKey: "other", data: catalogData });
  });
  assert.deepEqual(Object.keys(received.items), ["some_item"]);
});

// ---- 5. catalog.yml 実データ: 該当2件が実在し、_editor.categories(other) に紛れ込んでいない ----

test("実データ: catalog.yml の skill_node_lock / skill_tree_reset は _editor.categories のどのネストカテゴリにも属していない", () => {
  const { readConfig } = require("../lib/yamlio.js");
  const catalogPath = path.join(
    __dirname, "..", "..", "..", "TrinityForge", "src", "main", "resources", "items", "catalog.yml"
  );
  const { data } = readConfig(catalogPath);
  assert.ok(data.items.skill_node_lock, "skill_node_lock が catalog.yml に無い");
  assert.ok(data.items.skill_tree_reset, "skill_tree_reset が catalog.yml に無い");
  const categories = (data._editor && data._editor.categories) || {};
  for (const [tabKey, cats] of Object.entries(categories)) {
    for (const cat of cats) {
      const ids = Array.isArray(cat.itemIds) ? cat.itemIds : [];
      assert.ok(!ids.includes("skill_node_lock"), `_editor.categories.${tabKey} に skill_node_lock が孤児として残っている`);
      assert.ok(!ids.includes("skill_tree_reset"), `_editor.categories.${tabKey} に skill_tree_reset が孤児として残っている`);
    }
  }
});

// ---- 6. catalog.yml 実データ: 2026-08-04追加の券3件(role_reselect_ticket/stat_reroll_ticket/
// quality_upgrade_ticket)が実在し、同じく _editor.categories に孤児として残っていないこと ----

test("実データ: catalog.yml に券3件(role_reselect_ticket/stat_reroll_ticket/quality_upgrade_ticket)が" +
    "存在し、CMD は台帳に登録済みで、_editor.categories のどのネストカテゴリにも属していない", () => {
  const { readConfig } = require("../lib/yamlio.js");
  const catalogPath = path.join(
    __dirname, "..", "..", "..", "TrinityForge", "src", "main", "resources", "items", "catalog.yml"
  );
  const { data } = readConfig(catalogPath);
  // 2026-08-04: 当初は「CMD 未割当であること」を固定していたが、CMD 割当は台帳
  // (resourcepack/cmd-registry.json) 側の作業と対で進むため、割当済み・未割当のどちらでも
  // 成立する不変条件へ移した。本当に危険なのは「catalog.yml に CMD を書いたのに台帳へ
  // 登録されていない」状態で、これは番号の二重払い出しを招く。
  const registry = JSON.parse(fs.readFileSync(
    path.join(__dirname, "..", "..", "..", "resourcepack", "cmd-registry.json"), "utf8"
  ));
  const allocated = new Set(
    (registry.allocations || []).map((a) => `${String(a.material).toUpperCase()}#${a.cmd}`)
  );
  const ticketIds = ["role_reselect_ticket", "stat_reroll_ticket", "quality_upgrade_ticket"];
  for (const id of ticketIds) {
    assert.ok(data.items[id], `${id} が catalog.yml に無い`);
    const cmd = data.items[id]["custom-model-data"];
    if (cmd !== undefined) {
      assert.ok(Number.isInteger(cmd) && cmd > 0, `${id} の custom-model-data が正の整数でない: ${cmd}`);
      const key = `${String(data.items[id].material).toUpperCase()}#${cmd}`;
      assert.ok(allocated.has(key),
        `${id} の CMD が台帳に無い (${key})。cmd-registry.json へ払い出さずに手で書くと番号が二重払い出しされる`);
    }
    assert.equal(data.items[id].recipe, undefined, `${id} にレシピを付けてはいけない`);
  }
  const categories = (data._editor && data._editor.categories) || {};
  for (const [tabKey, cats] of Object.entries(categories)) {
    for (const cat of cats) {
      const ids = Array.isArray(cat.itemIds) ? cat.itemIds : [];
      for (const ticketId of ticketIds) {
        assert.ok(!ids.includes(ticketId), `_editor.categories.${tabKey} に ${ticketId} が孤児として残っている`);
      }
    }
  }
});
