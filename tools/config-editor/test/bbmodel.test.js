"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const { isBlockbenchProject, convertBlockbenchProject, normalizeTextureName } = require("../lib/bbmodel");

const packRoot = path.resolve(__dirname, "..", "..", "..", "resourcepack");
const modelsDir = path.join(packRoot, "trinityforge-items", "assets", "trinityforge", "models", "item");

const texturePathOf = (name) => `trinityforge:item/${name}`;

// 1x1 の最小PNG (base64)。埋め込みテクスチャの取り出し検証用。
const PNG_1X1 =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

function projectFixture(overrides) {
  return Object.assign({
    meta: { format_version: "5.0", model_format: "java_block" },
    resolution: { width: 32, height: 32 },
    textures: [{ id: "0", name: "blade.png", source: `data:image/png;base64,${PNG_1X1}`, particle: false }],
    elements: [{
      name: "cube", type: "cube", from: [0, 0, 0], to: [4, 4, 4], origin: [0, 0, 0],
      faces: { north: { uv: [0, 0, 8, 8], texture: 0 } }
    }]
  }, overrides || {});
}

test("Blockbenchプロジェクトを検出する / エクスポート済みモデルは検出しない", () => {
  assert.equal(isBlockbenchProject(projectFixture()), true);
  assert.equal(isBlockbenchProject({ meta: { format_version: "4.5" } }), true);
  assert.equal(isBlockbenchProject({ outliner: [] }), true);
  // Java版エクスポート結果 (textures はオブジェクト)
  assert.equal(isBlockbenchProject({
    credit: "Made with Blockbench",
    textures: { 2: "trinityforge:item/wooden_dagger" },
    elements: []
  }), false);
  assert.equal(isBlockbenchProject(null), false);
  assert.equal(isBlockbenchProject([]), false);
});

test("埋め込みテクスチャをPNGとして取り出し、facesの参照を #id へ書き換える", () => {
  const { modelJson, textures, warnings } = convertBlockbenchProject(projectFixture(), texturePathOf);

  assert.deepEqual(modelJson.textures, {
    0: "trinityforge:item/blade",
    particle: "trinityforge:item/blade"
  });
  assert.equal(modelJson.elements[0].faces.north.texture, "#0");
  assert.deepEqual(modelJson.elements[0].faces.north.uv, [0, 0, 8, 8], "UVは0..16へ正規化せず生値のまま残す");
  assert.deepEqual(modelJson.texture_size, [32, 32], "UV空間が16でないので texture_size を添える");
  assert.equal(textures.length, 1);
  assert.equal(textures[0].name, "blade");
  assert.ok(Buffer.isBuffer(textures[0].pngBuffer));
  assert.deepEqual(warnings, []);
});

test("UV空間が16x16なら texture_size を出さない", () => {
  const { modelJson } = convertBlockbenchProject(
    projectFixture({ resolution: { width: 16, height: 16 } }), texturePathOf);
  assert.equal("texture_size" in modelJson, false);
});

test("front_gui_light は gui_light: front へ写す(インベントリの陰影がプレビューと一致する)", () => {
  const on = convertBlockbenchProject(projectFixture({ front_gui_light: true }), texturePathOf);
  assert.equal(on.modelJson.gui_light, "front");

  const off = convertBlockbenchProject(projectFixture({ front_gui_light: false }), texturePathOf);
  assert.equal("gui_light" in off.modelJson, false);
});

test("Blockbench固有のrootフィールドを落とす", () => {
  const { modelJson } = convertBlockbenchProject(projectFixture({
    name: "testst", java_block_version: "1.21.11", visible_box: [4, 3.5, 1.25],
    variable_placeholders: "", unhandled_root_fields: {}, outliner: [], groups: [], parent: ""
  }), texturePathOf);

  for (const dropped of ["meta", "name", "java_block_version", "visible_box",
    "variable_placeholders", "unhandled_root_fields", "outliner", "resolution", "parent"]) {
    assert.equal(dropped in modelJson, false, `${dropped} は出力してはいけない`);
  }
  assert.deepEqual(Object.keys(modelJson).sort(), ["credit", "elements", "texture_size", "textures"]);
});

function rotatedFixture(rotation, origin) {
  return projectFixture({
    elements: [{
      type: "cube", from: [0, 0, 0], to: [4, 4, 4], origin: origin || [2, 2, 2], rotation,
      faces: { north: { uv: [0, 0, 8, 8], texture: 0 } }
    }]
  });
}

test("単軸の回転は旧記法 {origin, axis, angle} で書き出す(現在も有効・互換性が高い)", () => {
  const { modelJson } = convertBlockbenchProject(rotatedFixture([0, 0, 22.5]), texturePathOf);
  assert.deepEqual(modelJson.elements[0].rotation, { origin: [2, 2, 2], axis: "z", angle: 22.5 });
});

// 1.21.6 で「22.5の倍数」制限が撤廃された。丸めたり弾いたりしてはいけない。
test("22.5の倍数でない角度もそのまま通す (1.21.6以降)", () => {
  const { modelJson } = convertBlockbenchProject(rotatedFixture([0, 0, 41]), texturePathOf);
  assert.deepEqual(modelJson.elements[0].rotation, { origin: [2, 2, 2], axis: "z", angle: 41 });
});

// 1.21.11 で複数軸回転に対応し、[-45,45] の角度制限も撤廃された。
test("複数軸の回転は 1.21.11 の新記法 {origin, x, y, z} で書き出す", () => {
  const { modelJson } = convertBlockbenchProject(rotatedFixture([180, 0, 139]), texturePathOf);
  assert.deepEqual(modelJson.elements[0].rotation, { origin: [2, 2, 2], x: 180, y: 0, z: 139 });
  // 旧記法と新記法の混在は禁止(同居すると旧記法が優先されてしまう)。
  assert.equal("axis" in modelJson.elements[0].rotation, false);
  assert.equal("angle" in modelJson.elements[0].rotation, false);
});

test("45度を超える角度も通す (1.21.11で[-45,45]制限が撤廃されたため)", () => {
  const { modelJson } = convertBlockbenchProject(rotatedFixture([0, 180, 0]), texturePathOf);
  assert.deepEqual(modelJson.elements[0].rotation, { origin: [2, 2, 2], axis: "y", angle: 180 });
});

test("回転ゼロの要素には rotation を出さない", () => {
  const { modelJson } = convertBlockbenchProject(rotatedFixture([0, 0, 0]), texturePathOf);
  assert.equal("rotation" in modelJson.elements[0], false);
});

test("Java版の座標範囲(-16..32)を超える要素はエラーにする", () => {
  assert.throws(() => convertBlockbenchProject(projectFixture({
    elements: [{
      type: "cube", from: [0, 0, 0], to: [40, 4, 4], origin: [0, 0, 0],
      faces: { north: { uv: [0, 0, 8, 8], texture: 0 } }
    }]
  }), texturePathOf), (err) => {
    assert.match(err.conversionErrors[0], /許容範囲/);
    return true;
  });
});

test("テクスチャ未割当の面は落とし、面が全滅した要素は出力しない", () => {
  assert.throws(() => convertBlockbenchProject(projectFixture({
    elements: [{
      type: "cube", from: [0, 0, 0], to: [4, 4, 4], origin: [0, 0, 0],
      faces: { north: { uv: [0, 0, 8, 8], texture: null } }
    }]
  }), texturePathOf), (err) => {
    assert.match(err.message, /出力できる立方体要素がありません/);
    return true;
  });
});

test("埋め込みでないテクスチャは警告を出し、PNGは返さない", () => {
  const { textures, warnings } = convertBlockbenchProject(projectFixture({
    textures: [{ id: "0", name: "blade.png", source: "" }]
  }), texturePathOf);
  assert.equal(textures.length, 0);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /blade\.png/);
});

test("テクスチャ名を assetName 規約へ正規化する", () => {
  assert.equal(normalizeTextureName("test.png", 0), "test");
  assert.equal(normalizeTextureName("My Blade.PNG", 0), "my_blade");
  assert.equal(normalizeTextureName("", 3), "texture_3");
  assert.equal(normalizeTextureName("...", 1), "texture_1");
});

// 変換器の出力形式が、Blockbench 自身の Java エクスポート結果と一致することを固定する。
// 参照実装は wood_test.json (Format Version 1.21.11+ / UV Size 64x64 の剣を Blockbench が
// エクスポートしたもの)。Blockbench は多軸回転を 1.21.11 の新記法 {x,y,z,origin} で、
// 単軸回転を旧記法 {angle,axis,origin} で書き分けるので、変換器も同じ書き分けにする。
test("実データ: Blockbenchのエクスポート結果と同じ記法で回転を書き分ける", (t) => {
  const p = path.join(modelsDir, "wood_test.json");
  if (!fs.existsSync(p)) return t.skip("wood_test.json が存在しないためスキップ");
  const exported = JSON.parse(fs.readFileSync(p, "utf8"));

  assert.equal(isBlockbenchProject(exported), false, "エクスポート済みはプロジェクトと誤判定しないこと");

  const rotations = exported.elements.map((e) => e.rotation).filter(Boolean);
  const multi = rotations.filter((r) => "x" in r && !("axis" in r));
  const legacy = rotations.filter((r) => "axis" in r);
  assert.ok(multi.length > 0, "Blockbenchが多軸回転を新記法で出していること");
  assert.ok(legacy.length > 0, "Blockbenchが単軸回転を旧記法で出していること");

  // 同じ入力を我々の変換器へ通すと、Blockbench と同じ記法になること。
  const multiSample = multi[0];
  const converted = convertBlockbenchProject(projectFixture({
    elements: [{
      type: "cube", from: [0, 0, 0], to: [4, 4, 4], origin: multiSample.origin,
      rotation: [multiSample.x, multiSample.y, multiSample.z],
      faces: { north: { uv: [0, 0, 8, 8], texture: 0 } }
    }]
  }), texturePathOf);
  assert.deepEqual(converted.modelJson.elements[0].rotation, {
    origin: multiSample.origin, x: multiSample.x, y: multiSample.y, z: multiSample.z
  });
});

// 実データによる回帰: 正常にエクスポートされたモデルはプロジェクト形式と誤判定されないこと。
test("実データ: エクスポート済みモデル28件はプロジェクト形式と誤判定しない", (t) => {
  if (!fs.existsSync(modelsDir)) return t.skip("resourcepack が存在しないためスキップ");
  const exported = fs.readdirSync(modelsDir)
    .filter((f) => f.endsWith(".json") && f !== "wood_test.json")
    .map((f) => JSON.parse(fs.readFileSync(path.join(modelsDir, f), "utf8")));
  assert.ok(exported.length > 0);
  for (const model of exported) {
    assert.equal(isBlockbenchProject(model), false);
  }
});
