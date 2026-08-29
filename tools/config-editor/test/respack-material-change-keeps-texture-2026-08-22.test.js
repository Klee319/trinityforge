"use strict";

// 2026-08-22 実サーバ報告「editorで鎌のmaterialを剣に変えてリソパビルドしたら
// テクスチャ割り当てが外れた」の回帰テスト。
//
// 原因: 台帳の突合せキーが (material,cmd) だけだったため、material を差し替えた行は
// 「新規の未配線行」として扱われ assetName/parent が無言で落ちていた。
// 台帳が assetName を失うと regenerateItemDefinitions が未配線と判断して
// バニラモデルの entry を書くので、アセットは1枚も消えていないのに割り当てだけが外れる。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");

const Respack = require("../lib/respack");
const CmdRegistry = require("../lib/cmd-registry");

// 一時ディレクトリは CWD 内 (test/.tmp/) に作る (os.tmpdir() は使わない、規約)。
const TMP_ROOT = path.join(__dirname, ".tmp", "respack-material-change");
fs.mkdirSync(TMP_ROOT, { recursive: true });
function tmpDir() {
  return fs.mkdtempSync(path.join(TMP_ROOT, "case-"));
}

function modelsDir(root) {
  return path.join(root, "trinityforge-items", "assets", "trinityforge", "models", "item");
}
function itemDefsDir(root) {
  return path.join(root, "trinityforge-items", "assets", "minecraft", "items");
}

// 「WOODEN_HOE#58 に鎌のテクスチャが割り当たっている」状態を作る。
// PNG は配線判定に使われない(モデルJSONの実在だけを見る)ので用意しない。
function setupWiredHoe() {
  const root = tmpDir();
  const registryPath = path.join(root, "cmd-registry.json");
  fs.mkdirSync(modelsDir(root), { recursive: true });
  fs.writeFileSync(
    path.join(modelsDir(root), "wooden_scythe.json"),
    JSON.stringify(Respack.generatedLeafModel("minecraft:item/wooden_hoe", "wooden_scythe"), null, 2) + "\n",
    "utf8"
  );
  CmdRegistry.saveRegistry(registryPath, {
    version: 1,
    allocations: [{
      material: "WOODEN_HOE",
      cmd: 58,
      id: "wooden_scythe",
      source: "catalog",
      assetName: "wooden_scythe",
      parent: "handheld",
      allocatedAt: "2026-01-01T00:00:00.000Z"
    }]
  });
  Respack.regenerateItemDefinitions(root, registryPath);
  return { root, registryPath };
}

// editor で catalog を保存した後の同期 (cmd-routes.syncCmdRegistryAfterSave と同じ順序)。
function syncAfterSave(root, registryPath, usage) {
  const before = CmdRegistry.loadRegistry(registryPath);
  const { registry, moved } = CmdRegistry.reconcileWithUsageDetailed(usage, before);
  CmdRegistry.saveRegistry(registryPath, registry);
  Respack.rewriteMovedModels(root, moved);
  Respack.regenerateItemDefinitions(root, registryPath);
  return moved;
}

function entryModelName(root, material, cmd) {
  const file = path.join(itemDefsDir(root), `${material.toLowerCase()}.json`);
  if (!fs.existsSync(file)) return null;
  const def = JSON.parse(fs.readFileSync(file, "utf8"));
  const entries = (def.model && def.model.entries) || [];
  const hit = entries.find((e) => e.threshold === cmd);
  return hit && hit.model ? hit.model.model : null;
}

test("editorでmaterialを差し替えても、その行のテクスチャ割り当ては引き継がれる", () => {
  const { root, registryPath } = setupWiredHoe();
  assert.equal(entryModelName(root, "WOODEN_HOE", 58), "trinityforge:item/wooden_scythe",
    "前提: 差し替え前は鍬側に割り当たっている");

  syncAfterSave(root, registryPath, [
    { material: "WOODEN_SWORD", cmd: 58, sources: [{ file: "catalog", id: "wooden_scythe" }] }
  ]);

  const row = CmdRegistry.loadRegistry(registryPath).allocations
    .find((a) => a.id === "wooden_scythe");
  assert.equal(row.material, "WOODEN_SWORD");
  assert.equal(row.assetName, "wooden_scythe", "assetName が落ちるとテクスチャ割り当てが外れる");
  assert.equal(row.parent, "handheld");
  assert.equal(row.allocatedAt, "2026-01-01T00:00:00.000Z", "払い出し日時も引き継ぐ");

  assert.equal(entryModelName(root, "WOODEN_SWORD", 58), "trinityforge:item/wooden_scythe",
    "剣側の CMD58 が鎌のモデルを指していること");
  assert.equal(fs.existsSync(path.join(itemDefsDir(root), "wooden_hoe.json")), false,
    "配線の無くなった鍬の定義ファイルは消える");
});

test("materialを差し替えたら、自動生成モデルのparentも新しいmaterialへ貼り直す", () => {
  const { root, registryPath } = setupWiredHoe();
  syncAfterSave(root, registryPath, [
    { material: "WOODEN_SWORD", cmd: 58, sources: [{ file: "catalog", id: "wooden_scythe" }] }
  ]);

  const model = JSON.parse(fs.readFileSync(path.join(modelsDir(root), "wooden_scythe.json"), "utf8"));
  assert.equal(model.parent, "minecraft:item/wooden_sword",
    "parent が旧 material のままだと構え方・大きさが元のアイテムのまま残る");
  assert.equal(model.textures.layer0, "trinityforge:item/wooden_scythe");
});

test("リーフ構成が違うmaterialへ移しても、必要なリーフモデルが全部書かれる", () => {
  // BOW は引き絞りリーフを持つ。貼り直さないと entryModelFor が
  // <assetName>__pulling_0.json 等を参照するのにファイルが無く、描画が丸ごと落ちる。
  const { root, registryPath } = setupWiredHoe();
  syncAfterSave(root, registryPath, [
    { material: "BOW", cmd: 58, sources: [{ file: "catalog", id: "wooden_scythe" }] }
  ]);

  for (const leaf of Respack.vanillaLeafModels("BOW")) {
    const file = path.join(modelsDir(root), `${Respack.leafAssetName("wooden_scythe", leaf.suffix)}.json`);
    assert.equal(fs.existsSync(file), true, `リーフモデルが無い: ${file}`);
  }
});

test("customModel の行は自動生成モデルで上書きしない", () => {
  const root = tmpDir();
  const registryPath = path.join(root, "cmd-registry.json");
  const handwritten = { parent: "minecraft:item/generated", elements: [{ from: [0, 0, 0], to: [1, 1, 1] }] };
  fs.mkdirSync(modelsDir(root), { recursive: true });
  fs.writeFileSync(path.join(modelsDir(root), "fancy_blade.json"),
    JSON.stringify(handwritten, null, 2) + "\n", "utf8");
  CmdRegistry.saveRegistry(registryPath, {
    version: 1,
    allocations: [{
      material: "WOODEN_HOE", cmd: 70, id: "fancy_blade", source: "catalog",
      assetName: "fancy_blade", parent: "handheld", customModel: true,
      allocatedAt: "2026-01-01T00:00:00.000Z"
    }]
  });
  Respack.regenerateItemDefinitions(root, registryPath);

  syncAfterSave(root, registryPath, [
    { material: "WOODEN_SWORD", cmd: 70, sources: [{ file: "catalog", id: "fancy_blade" }] }
  ]);

  const row = CmdRegistry.loadRegistry(registryPath).allocations.find((a) => a.id === "fancy_blade");
  assert.equal(row.assetName, "fancy_blade");
  assert.equal(row.customModel, true, "customModel フラグも引き継ぐ");
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(modelsDir(root), "fancy_blade.json"), "utf8")),
    handwritten, "作者が書いたモデルJSONを自動生成で潰さない");
});

test("元の(material,cmd)が残っている行は移動ではないので引き継がない", () => {
  // 同じ id を名乗る別アイテムが増えただけのケース。元の行はそのまま生きているので、
  // 新しい行へ assetName を持って行くと元の行が理由不明で未配線になる。
  const registry = {
    version: 1,
    allocations: [
      { material: "WOODEN_HOE", cmd: 58, id: "wooden_scythe", assetName: "wooden_scythe", parent: "handheld" }
    ]
  };
  const usage = [
    { material: "WOODEN_HOE", cmd: 58, sources: [{ file: "catalog", id: "wooden_scythe" }] },
    { material: "WOODEN_SWORD", cmd: 58, sources: [{ file: "catalog", id: "wooden_scythe" }] }
  ];
  const { registry: next, moved } = CmdRegistry.reconcileWithUsageDetailed(usage, registry);
  assert.deepEqual(moved, []);
  assert.equal(next.allocations.find((a) => a.material === "WOODEN_HOE").assetName, "wooden_scythe");
  assert.equal(next.allocations.find((a) => a.material === "WOODEN_SWORD").assetName, undefined);
});

test("同じidの引き継ぎ候補が複数あるときは、どこへも引き継がない", () => {
  const registry = {
    version: 1,
    allocations: [
      { material: "WOODEN_HOE", cmd: 58, id: "dup", assetName: "dup_a", parent: "handheld" },
      { material: "STONE_HOE", cmd: 58, id: "dup", assetName: "dup_b", parent: "handheld" }
    ]
  };
  const usage = [{ material: "WOODEN_SWORD", cmd: 58, sources: [{ file: "catalog", id: "dup" }] }];
  const { registry: next, moved } = CmdRegistry.reconcileWithUsageDetailed(usage, registry);
  assert.deepEqual(moved, []);
  assert.equal(next.allocations[0].assetName, undefined,
    "どちらを引き継ぐか決められない以上、黙って片方を選ばない");
});

test("引き継ぎ先の assetName が他行で使われている場合は引き継がない", () => {
  const registry = {
    version: 1,
    allocations: [
      { material: "WOODEN_HOE", cmd: 58, id: "moving", assetName: "shared", parent: "handheld" },
      { material: "WOODEN_SWORD", cmd: 99, id: "staying", assetName: "shared", parent: "handheld" }
    ]
  };
  const usage = [
    { material: "WOODEN_SWORD", cmd: 58, sources: [{ file: "catalog", id: "moving" }] },
    { material: "WOODEN_SWORD", cmd: 99, sources: [{ file: "catalog", id: "staying" }] }
  ];
  const { registry: next, moved } = CmdRegistry.reconcileWithUsageDetailed(usage, registry);
  assert.deepEqual(moved, []);
  assert.equal(next.allocations.find((a) => a.cmd === 58).assetName, undefined);
  assert.equal(next.allocations.find((a) => a.cmd === 99).assetName, "shared");
});
