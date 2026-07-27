"use strict";

// writeModel に Blockbench プロジェクトを渡したときの一連の挙動 (変換 → PNG書き出し →
// モデル書き出し → 台帳更新 → item定義再生成) を通しで固定する。
//
// 背景: 旧実装はプロジェクトファイルを検証なしで受理し、台帳へ customModel:true を付けて
// 「登録成功」を返していた。ゲーム内では描画されず、プレビューも空という無言の失敗だった。

const test = require("node:test");
const { after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");

const Respack = require("../lib/respack");
const CmdRegistry = require("../lib/cmd-registry");

// 一時ディレクトリは CWD 内 (test/.tmp/) に作る (os.tmpdir() は使わない、規約)。
const TMP_ROOT = path.join(__dirname, ".tmp", "respack-bbmodel");
fs.mkdirSync(TMP_ROOT, { recursive: true });
function tmpDir() {
  return fs.mkdtempSync(path.join(TMP_ROOT, "case-"));
}
after(() => {
  fs.rmSync(TMP_ROOT, { recursive: true, force: true });
});

const MIN_PNG_B64 =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
const MIN_PNG = Buffer.from(MIN_PNG_B64, "base64");

function bbProject(overrides) {
  return Object.assign({
    meta: { format_version: "5.0", model_format: "java_block" },
    resolution: { width: 32, height: 32 },
    outliner: [],
    parent: "",
    textures: [{ id: "0", name: "blade.png", source: `data:image/png;base64,${MIN_PNG_B64}` }],
    elements: [{
      name: "cube", type: "cube", from: [0, 0, 0], to: [4, 4, 4], origin: [0, 0, 0],
      faces: { north: { uv: [0, 0, 8, 8], texture: 0 } }
    }]
  }, overrides || {});
}

function paths(dir) {
  const base = path.join(dir, "trinityforge-items", "assets", "trinityforge");
  return {
    models: path.join(base, "models", "item"),
    textures: path.join(base, "textures", "item"),
    registry: path.join(dir, "cmd-registry.json")
  };
}

test("Blockbenchプロジェクトを渡すと変換され、埋め込みPNGが書き出される", () => {
  const dir = tmpDir();
  const p = paths(dir);

  const result = Respack.writeModel(
    { material: "IRON_INGOT", cmd: 1, id: "bb_item", modelJson: bbProject(), textures: [] },
    dir, p.registry
  );

  assert.equal(result.ok, true);
  assert.equal(result.converted, true, "変換したことを呼び出し元へ伝えること");
  assert.deepEqual(result.warnings, []);

  const written = JSON.parse(fs.readFileSync(path.join(p.models, `${result.assetName}.json`), "utf8"));
  assert.equal(Array.isArray(written.textures), false, "textures はオブジェクトへ変換されていること");
  assert.equal(written.textures["0"], "trinityforge:item/blade");
  assert.equal(written.elements[0].faces.north.texture, "#0");
  assert.deepEqual(written.texture_size, [32, 32]);
  assert.equal("meta" in written, false, "Blockbench固有フィールドが残っていないこと");

  assert.ok(fs.existsSync(path.join(p.textures, "blade.png")), "埋め込み画像がPNGとして取り出されること");

  const row = CmdRegistry.loadRegistry(p.registry).allocations
    .find((a) => a.material === "IRON_INGOT" && a.cmd === 1);
  assert.equal(row.customModel, true);
});

test("変換後のモデルはプレビューでテクスチャを解決できる (broken にならない)", () => {
  const dir = tmpDir();
  const p = paths(dir);
  Respack.writeModel(
    { material: "IRON_INGOT", cmd: 1, id: "bb_item", modelJson: bbProject(), textures: [] },
    dir, p.registry
  );

  const preview = Respack.previewInfo(dir, p.registry, "IRON_INGOT", 1);
  assert.equal(preview.hasTexture, true);
  assert.equal(preview.customModel, true);
  assert.equal(preview.broken, false);
  assert.equal(preview.blockbenchProject, false);
  assert.ok(preview.textures.some((t) => t.name === "blade" && t.pngBase64));
});

test("同名テクスチャは手動アップロード側を優先する(埋め込み画像で上書きしない)", () => {
  const dir = tmpDir();
  const p = paths(dir);
  const replacement = Buffer.concat([MIN_PNG, Buffer.alloc(0)]);

  const result = Respack.writeModel(
    {
      material: "IRON_INGOT", cmd: 1, id: "bb_item", modelJson: bbProject(),
      textures: [{ name: "blade", pngBuffer: replacement }]
    },
    dir, p.registry
  );

  assert.deepEqual(result.writtenTextures, ["blade"], "blade が二重に書かれないこと");
  assert.deepEqual(fs.readFileSync(path.join(p.textures, "blade.png")), replacement);
});

// 1.21.11 で回転制約は撤廃されたので、残る「Java版で表現できない」ケースは
// Java Block/Item の 3x3x3 サイズ制限 (座標 -16..32) を超えるモデル。
test("Java版で表現できないプロジェクトは登録せず、理由を全件返す", () => {
  const dir = tmpDir();
  const p = paths(dir);
  const project = bbProject({
    elements: [{
      name: "bad", type: "cube", from: [0, 0, 0], to: [64, 4, 4], origin: [0, 0, 0],
      faces: { north: { uv: [0, 0, 8, 8], texture: 0 } }
    }]
  });

  assert.throws(
    () => Respack.writeModel(
      { material: "IRON_INGOT", cmd: 1, id: "bb_bad", modelJson: project, textures: [] },
      dir, p.registry
    ),
    (err) => {
      assert.ok(Array.isArray(err.conversionErrors) && err.conversionErrors.length > 0);
      return true;
    }
  );

  // 失敗時は副作用を残さない: 台帳にも customModel 行を作らない。
  const registry = fs.existsSync(p.registry) ? CmdRegistry.loadRegistry(p.registry) : { allocations: [] };
  assert.equal(registry.allocations.some((a) => a.material === "IRON_INGOT" && a.cmd === 1), false);
  assert.equal(fs.existsSync(path.join(p.models, "bb_bad.json")), false);
});

test("textures が配列のJSONは必ず拒否される(旧実装ではここが素通りしていた)", () => {
  const dir = tmpDir();
  const p = paths(dir);
  // textures が配列の時点で isBlockbenchProject が真になるため、まず変換器が受け取り、
  // テクスチャ0枚として弾かれる。旧実装ではこの入力が「参照0件=検証成功」で通っていた。
  const malformed = { textures: [], elements: [{ from: [0, 0, 0], to: [1, 1, 1], faces: {} }] };
  assert.throws(
    () => Respack.writeModel(
      { material: "IRON_INGOT", cmd: 1, id: "bad", modelJson: malformed, textures: [] },
      dir, p.registry
    ),
    /テクスチャが1枚も含まれていません/
  );
  assert.equal(fs.existsSync(path.join(p.models, "bad.json")), false);
});

// 2026-07-25 実例: Blockbench の Java エクスポートは textures を {"0": "test"} のように
// 名前空間なしで書き出す。Minecraft は minecraft: として解決するため、そのままでは
// trinityforge 名前空間に置いた我々のPNGへ到達できず missing texture になる。
test("名前空間なしのテクスチャ参照を trinityforge:item/<name> へ書き換える", () => {
  const dir = tmpDir();
  const p = paths(dir);
  const exported = {
    format_version: "1.21.11",
    credit: "Made with Blockbench",
    texture_size: [64, 64],
    textures: { 0: "test" },
    elements: [{ from: [0, 0, 0], to: [4, 4, 4], faces: { north: { uv: [0, 0, 8, 8], texture: "#0" } } }]
  };

  const result = Respack.writeModel(
    {
      material: "IRON_INGOT", cmd: 1, id: "exported", modelJson: exported,
      textures: [{ name: "test", pngBuffer: MIN_PNG }]
    },
    dir, p.registry
  );

  assert.equal(result.converted, false, "エクスポート済みなので変換は走らない");
  assert.deepEqual(result.rewrittenTextureRefs, ["test → trinityforge:item/test"]);

  const written = JSON.parse(fs.readFileSync(path.join(p.models, `${result.assetName}.json`), "utf8"));
  assert.equal(written.textures["0"], "trinityforge:item/test");

  const preview = Respack.previewInfo(dir, p.registry, "IRON_INGOT", 1);
  assert.equal(preview.broken, false, "書き換え後はプレビューでPNGに解決できること");
});

test("パック内に実体が無い名前空間なし参照は書き換えない(バニラ資産の流用を壊さない)", () => {
  const dir = tmpDir();
  const p = paths(dir);
  const exported = {
    textures: { 0: "item/diamond_sword" },
    elements: [{ from: [0, 0, 0], to: [4, 4, 4], faces: { north: { uv: [0, 0, 8, 8], texture: "#0" } } }]
  };

  const result = Respack.writeModel(
    { material: "IRON_INGOT", cmd: 1, id: "vanilla_ref", modelJson: exported, textures: [] },
    dir, p.registry
  );

  assert.deepEqual(result.rewrittenTextureRefs, []);
  const written = JSON.parse(fs.readFileSync(path.join(p.models, `${result.assetName}.json`), "utf8"));
  assert.equal(written.textures["0"], "item/diamond_sword");
});

test("parent も elements も無いモデルは拒否する(texturesだけでは描画できない)", () => {
  const dir = tmpDir();
  const p = paths(dir);
  assert.throws(
    () => Respack.writeModel(
      {
        material: "IRON_INGOT", cmd: 1, id: "bad",
        modelJson: { textures: { 0: "trinityforge:item/blade" } }, textures: []
      },
      dir, p.registry
    ),
    /parent か elements/
  );
});
