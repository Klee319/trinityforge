"use strict";

// 2026-08-24 実サーバ報告「infinity_source_core (functional-items) がリソパ管理で
// 割り当てられているリソパを削除できない」の回帰テスト。
//
// このアイテムは material(BEACON/NETHER_STAR) と CMD(500001) を fork の Java が
// ハードコードしていて、CMDスキャン対象の yml には1行も存在しない。そのため
//   - 登録解除は「参照するconfigが見つかりませんでした」で必ず404になり、
//   - reconcile は usage に無い行として台帳から黙って落とす(=次に誰かが editor で
//     config を1つ保存した瞬間にリソパの配線ごと消える)
// という2つの壊れ方をしていた。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");

const Respack = require("../lib/respack");
const CmdRegistry = require("../lib/cmd-registry");
const { registerCmdRoutes } = require("../lib/cmd-routes");

// 一時ディレクトリは CWD 内 (test/.tmp/) に作る (os.tmpdir() は使わない、規約)。
const TMP_ROOT = path.join(__dirname, ".tmp", "cmd-external-allocation");
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

const EXTERNAL_ROW = {
  material: "BEACON",
  cmd: 500001,
  id: "infinity_source_core",
  source: "functional-items",
  assetName: "infinity_source_core",
  parent: "generated",
  allocatedAt: "2026-08-24T00:00:00.000Z"
};
const CATALOG_ROW = {
  material: "WOODEN_HOE",
  cmd: 58,
  id: "wooden_scythe",
  source: "catalog",
  assetName: "wooden_scythe",
  parent: "handheld",
  allocatedAt: "2026-01-01T00:00:00.000Z"
};
// CATALOG_ROW に対応する catalog.yml の実体 (登録解除後の同期で scanUsage が拾う)。
const CATALOG_FIXTURE = { items: { wooden_scythe: { material: "WOODEN_HOE", "custom-model-data": 58 } } };

// 「fork由来の行1つ + catalog由来の行1つ」が配線済みで台帳に載っている状態を作る。
function setupWiredPack() {
  const root = tmpDir();
  const registryPath = path.join(root, "cmd-registry.json");
  fs.mkdirSync(modelsDir(root), { recursive: true });
  for (const [asset, leaf] of [["infinity_source_core", "minecraft:item/beacon"],
    ["wooden_scythe", "minecraft:item/wooden_hoe"]]) {
    fs.writeFileSync(
      path.join(modelsDir(root), `${asset}.json`),
      JSON.stringify(Respack.generatedLeafModel(leaf, asset), null, 2) + "\n",
      "utf8"
    );
  }
  CmdRegistry.saveRegistry(registryPath, { version: 1, allocations: [EXTERNAL_ROW, CATALOG_ROW] });
  Respack.regenerateItemDefinitions(root, registryPath);
  return { root, registryPath };
}

// cmd-routes.syncCmdRegistryAfterSave と同じ順序 (config保存後の同期)。
function syncAfterSave(root, registryPath, usage) {
  const before = CmdRegistry.loadRegistry(registryPath);
  const { registry, moved } = CmdRegistry.reconcileWithUsageDetailed(usage, before);
  CmdRegistry.saveRegistry(registryPath, registry);
  Respack.rewriteMovedModels(root, moved);
  Respack.regenerateItemDefinitions(root, registryPath);
}

function entryModelName(root, material, cmd) {
  const file = path.join(itemDefsDir(root), `${material.toLowerCase()}.json`);
  if (!fs.existsSync(file)) return null;
  const def = JSON.parse(fs.readFileSync(file, "utf8"));
  const entries = (def.model && def.model.entries) || [];
  const hit = entries.find((e) => e.threshold === cmd);
  return hit && hit.model ? hit.model.model : null;
}

function allocationKeys(registryPath) {
  return CmdRegistry.loadRegistry(registryPath).allocations.map((a) => `${a.material}#${a.cmd}`);
}

test("isExternalSource: スキャン対象の由来だけを「外部でない」と判定する", () => {
  assert.equal(CmdRegistry.isExternalSource("catalog"), false);
  assert.equal(CmdRegistry.isExternalSource("materials"), false);
  assert.equal(CmdRegistry.isExternalSource("spellbooks:catalysts"), false, "base(':'より前)で判定する");
  assert.equal(CmdRegistry.isExternalSource(null), false, "由来なしは usage 起点の行なので従来どおり");
  assert.equal(CmdRegistry.isExternalSource(""), false);
  assert.equal(CmdRegistry.isExternalSource("functional-items"), true);
});

test("fork由来(functional-items)の行は、他configの保存で台帳から消えない", () => {
  const { root, registryPath } = setupWiredPack();
  // catalog 側だけが usage に現れる (functional-items は永遠に scanUsage に出てこない)。
  syncAfterSave(root, registryPath, [
    { material: "WOODEN_HOE", cmd: 58, sources: [{ file: "catalog", id: "wooden_scythe" }] }
  ]);

  assert.deepEqual(allocationKeys(registryPath).sort(), ["BEACON#500001", "WOODEN_HOE#58"]);
  assert.equal(
    entryModelName(root, "BEACON", 500001), "trinityforge:item/infinity_source_core",
    "リソパ定義の配線も残る"
  );
});

test("スキャン対象由来の行は、usageから消えたら従来どおり台帳から外れる", () => {
  const { root, registryPath } = setupWiredPack();
  syncAfterSave(root, registryPath, []); // catalog からも消えた

  assert.deepEqual(allocationKeys(registryPath), ["BEACON#500001"]);
  assert.equal(entryModelName(root, "WOODEN_HOE", 58), null, "catalog由来の行は配線ごと外れる");
});

// registerCmdRoutes に渡す最小の express もどき (ハンドラを捕まえて直接呼ぶ)。
function captureRoutes(ctx) {
  const routes = {};
  const app = {
    get(p, handler) { routes[`GET ${p}`] = handler; },
    post(p, handler) { routes[`POST ${p}`] = handler; }
  };
  registerCmdRoutes(app, ctx);
  return routes;
}

function fakeRes() {
  const captured = { status: 200, body: null };
  return {
    captured,
    status(code) { captured.status = code; return this; },
    json(body) { captured.body = body; return this; }
  };
}

test("登録解除: どのconfigにも無いfork由来の行は台帳とリソパ定義から外れる", () => {
  const { root, registryPath } = setupWiredPack();
  const routes = captureRoutes({
    // BEACON#500001 はどの config にも実体が無い。catalog由来の行は実体があるので
    // 解除後の同期 (reconcile) で巻き添えにならないことも同時に見る。
    readEntryById: (id) => (id === "catalog" ? CATALOG_FIXTURE : null),
    writeEntry: () => { throw new Error("config は書き換えられないはず"); },
    cmdRegistryPath: () => registryPath,
    resourcePackRoot: () => root
  });

  const res = fakeRes();
  routes["POST /api/respack/unregister"]({ body: { material: "BEACON", cmd: 500001 } }, res);

  assert.equal(res.captured.status, 200, "404ではなく成功する");
  assert.deepEqual(res.captured.body.removed, [{ file: "functional-items", id: "infinity_source_core" }]);
  assert.deepEqual(allocationKeys(registryPath), ["WOODEN_HOE#58"], "台帳から消える");
  assert.equal(entryModelName(root, "BEACON", 500001), null, "リソパ定義からも外れる");
});

test("登録解除: 台帳にもconfigにも無い(material,cmd)は従来どおり404", () => {
  const { root, registryPath } = setupWiredPack();
  const routes = captureRoutes({
    readEntryById: (id) => (id === "catalog" ? CATALOG_FIXTURE : null),
    writeEntry: () => {},
    cmdRegistryPath: () => registryPath,
    resourcePackRoot: () => root
  });

  const res = fakeRes();
  routes["POST /api/respack/unregister"]({ body: { material: "BEACON", cmd: 400002 } }, res);

  assert.equal(res.captured.status, 404);
  assert.deepEqual(allocationKeys(registryPath).sort(), ["BEACON#500001", "WOODEN_HOE#58"]);
});
