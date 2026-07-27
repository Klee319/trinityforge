"use strict";

// public/js/catalog-candidates.js のテスト。
// 不具合: 食事ギミック等の「カタログID / 表示名で検索」候補が catalog.yml の items: しか
// 見ておらず、ArsPaper materials.yml (別ファイル・別ルートキー・snake_case) が候補に出ない。
// buildCatalogCandidates(catalogData, materialsData) の第2引数で materials.yml も候補化できる
// ことを固定する。

const test = require("node:test");
const assert = require("node:assert/strict");
const { buildCatalogCandidates } = require("../public/js/catalog-candidates.js");

test("catalog.yml の items: だけを渡した従来どおりの挙動が変わっていない (回帰防止)", () => {
  const catalog = {
    items: {
      sword1: { material: "iron_sword", "custom-model-data": 100, "display-name": "&a鉄の剣" },
      no_material: { "display-name": "素材キー欠落" } // material 無しは従来どおり除外
    }
  };
  const out = buildCatalogCandidates(catalog);
  assert.deepEqual(out, [
    { id: "sword1", displayName: "&a鉄の剣", material: "IRON_SWORD", cmd: 100, tab: "other" }
  ]);
});

test("materials.yml (ArsPaper 中間素材) の base_material がカタログ候補に出る", () => {
  const catalog = { items: {} };
  const materials = {
    materials: {
      arcane_gem: { base_material: "amethyst_shard", custom_model_data: 5001, display_name: "&d秘石" }
    }
  };
  const out = buildCatalogCandidates(catalog, materials);
  assert.deepEqual(out, [
    { id: "arcane_gem", displayName: "&d秘石", material: "AMETHYST_SHARD", cmd: 5001, tab: "material" }
  ]);
});

test("materials.yml の snake_case キー(base_material/custom_model_data/display_name)が正しく正規化される", () => {
  const materials = {
    materials: {
      m1: { base_material: "diamond", custom_model_data: 42, display_name: "&b宝石片" }
    }
  };
  const out = buildCatalogCandidates({}, materials);
  assert.equal(out.length, 1);
  assert.equal(out[0].material, "DIAMOND");
  assert.equal(out[0].cmd, 42);
  assert.equal(out[0].displayName, "&b宝石片");
  assert.equal(out[0].tab, "material");
});

test("base_material が無い materials エントリは候補から落ちる (catalog items のガードと同型)", () => {
  const materials = {
    materials: {
      broken: { display_name: "base_material 欠落" },
      ok: { base_material: "iron_ingot", display_name: "OK" }
    }
  };
  const out = buildCatalogCandidates({}, materials);
  assert.deepEqual(out.map((c) => c.id), ["ok"]);
});

test("custom_model_data が無い/不正な materials エントリは cmd: null になる (欠落扱いにしない)", () => {
  const materials = {
    materials: {
      noCmd: { base_material: "stick", display_name: "CMDなし" },
      badCmd: { base_material: "stick", custom_model_data: "abc", display_name: "CMD不正" },
      negCmd: { base_material: "stick", custom_model_data: -1, display_name: "CMD負数" }
    }
  };
  const out = buildCatalogCandidates({}, materials);
  assert.equal(out.length, 3);
  for (const c of out) assert.equal(c.cmd, null);
});

test("ID衝突時は catalog.yml が先勝ちする (util.js setCustomItemCandidates と同じ流儀)", () => {
  const catalog = {
    items: {
      dup: { material: "gold_ingot", "display-name": "カタログ側(先勝ち)" }
    }
  };
  const materials = {
    materials: {
      dup: { base_material: "iron_ingot", display_name: "素材側(後勝ちなら混入)" }
    }
  };
  const out = buildCatalogCandidates(catalog, materials);
  assert.equal(out.length, 1);
  assert.equal(out[0].material, "GOLD_INGOT");
  assert.equal(out[0].displayName, "カタログ側(先勝ち)");
});

test("materialsData が無い/不正でもエラーにならず catalog 分だけ返る", () => {
  const catalog = { items: { a: { material: "stone", "display-name": "石" } } };
  assert.doesNotThrow(() => buildCatalogCandidates(catalog, null));
  assert.doesNotThrow(() => buildCatalogCandidates(catalog, undefined));
  assert.doesNotThrow(() => buildCatalogCandidates(catalog, {}));
  assert.doesNotThrow(() => buildCatalogCandidates(catalog, "not-an-object"));
  const out = buildCatalogCandidates(catalog, {});
  assert.equal(out.length, 1);
  assert.equal(out[0].id, "a");
});

test("catalogData が無くても materials 分だけ返る", () => {
  const materials = {
    materials: { m1: { base_material: "netherite_ingot", display_name: "★" } }
  };
  const out = buildCatalogCandidates(null, materials);
  assert.deepEqual(out.map((c) => c.id), ["m1"]);
});

test("両方から複数件でも順序を保って全部候補化される", () => {
  const catalog = {
    items: {
      c1: { material: "stone", "display-name": "C1" },
      c2: { material: "dirt", "display-name": "C2" }
    }
  };
  const materials = {
    materials: {
      m1: { base_material: "clay", display_name: "M1" },
      m2: { base_material: "sand", display_name: "M2" }
    }
  };
  const out = buildCatalogCandidates(catalog, materials);
  assert.deepEqual(out.map((c) => c.id), ["c1", "c2", "m1", "m2"]);
  assert.deepEqual(out.filter((c) => c.tab === "material").map((c) => c.id), ["m1", "m2"]);
});
