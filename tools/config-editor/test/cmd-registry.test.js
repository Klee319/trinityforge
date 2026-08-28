"use strict";

const test = require("node:test");
const { after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");

const CmdRegistry = require("../lib/cmd-registry");

// L-10: 一時ディレクトリは CWD 内 (test/.tmp/) に作る (os.tmpdir() は使わない、規約)。
// テスト完了後に一括削除する。
const TMP_ROOT = path.join(__dirname, ".tmp", "cmd-registry");
fs.mkdirSync(TMP_ROOT, { recursive: true });
function tmpDir() {
  return fs.mkdtempSync(path.join(TMP_ROOT, "case-"));
}
after(() => {
  fs.rmSync(TMP_ROOT, { recursive: true, force: true });
});

function emptyReader() {
  return () => null;
}

test("nextCmd: material に使用値が無ければ 1 から", () => {
  const cmd = CmdRegistry.nextCmd("IRON_INGOT", [], { allocations: [] });
  assert.equal(cmd, 1);
});

test("nextCmd: max+1 から始まる", () => {
  const usage = [{ material: "BLAZE_ROD", cmd: 5 }, { material: "BLAZE_ROD", cmd: 7 }];
  const cmd = CmdRegistry.nextCmd("BLAZE_ROD", usage, { allocations: [] });
  assert.equal(cmd, 8);
});

test("nextCmd: 予約値をスキップする", () => {
  // 300001-300016 は連続した fork 専用の予約値。使用値の max+1 がその範囲に当たるケースを作る。
  const usage = [{ material: "ENCHANTING_TABLE", cmd: 300000 }];
  const cmd = CmdRegistry.nextCmd("ENCHANTING_TABLE", usage, { allocations: [] });
  assert.equal(cmd, 300017); // 300001..300016 は予約なのですべてスキップされる
});

test("nextCmd: config化された旧予約値(魔導書等)はもう予約でない", () => {
  // 100001-100003(spell_book_*)・100010(source_berry)・100020(teleport_compass)は catalog.yml へ
  // 定義されたため RESERVED から除外済み。usage/台帳に無ければ払い出せる。
  const usage = [{ material: "BOOK", cmd: 100000 }];
  const cmd = CmdRegistry.nextCmd("BOOK", usage, { allocations: [] });
  assert.equal(cmd, 100001);
});

test("nextCmd: material 毎に独立して開始する (他material未使用なら1から)", () => {
  const usage = [{ material: "BLAZE_ROD", cmd: 500 }];
  const cmd = CmdRegistry.nextCmd("IRON_INGOT", usage, { allocations: [] });
  assert.equal(cmd, 1);
});

test("nextCmd: reconcile 前の台帳残りは衝突として飛ばす", () => {
  const registry = { allocations: [{ material: "IRON_INGOT", cmd: 3 }] };
  const cmd = CmdRegistry.nextCmd("IRON_INGOT", [], registry);
  assert.equal(cmd, 4);
});

test("nextCmd: 全material使用値との衝突を安全側でスキップする", () => {
  // BLAZE_ROD が cmd=9 を使用中。IRON_INGOT の新規払い出しは 1 から始まるが、
  // 9 は他材質で使用済みのためスキップされる (仕様: 全material使用値との衝突を回避)。
  const usage = [{ material: "BLAZE_ROD", cmd: 9 }];
  const cmd = CmdRegistry.nextCmd("IRON_INGOT", usage, { allocations: [] });
  assert.equal(cmd, 1); // 1 は誰も使っていないのでそのまま採用される
});

test("nextCmd: 開始点そのものが他materialで使用済みならスキップする", () => {
  const usage = [
    { material: "IRON_INGOT", cmd: 1 },
    { material: "BLAZE_ROD", cmd: 2 }
  ];
  // IRON_INGOT の開始点は max(1)+1=2 だが、2 は BLAZE_ROD が使用中のため 3 へスキップ。
  const cmd = CmdRegistry.nextCmd("IRON_INGOT", usage, { allocations: [] });
  assert.equal(cmd, 3);
});

test("nextCmd: 7桁上限を超えるとエラー", () => {
  const usage = [{ material: "IRON_INGOT", cmd: CmdRegistry.MAX_CMD }];
  assert.throws(() => CmdRegistry.nextCmd("IRON_INGOT", usage, { allocations: [] }));
});

test("scanUsage: catalog/item-stats/materials/spellbooks/sourcejars/sourcelinks/external-items を集計する", () => {
  const fixtures = {
    catalog: { items: { foo: { material: "IRON_INGOT", "custom-model-data": 10 } } },
    "item-stats": { items: { "IRON_INGOT#10": { fixed: { "attack-power": 1 } }, GOLD_INGOT: {} } },
    materials: { materials: { bar: { base_material: "STRING", custom_model_data: 20 } } },
    spellbooks: {
      "spell-books": [{ id: "b1", "custom-model-data": 30 }],
      catalysts: { c1: { material: "BLAZE_ROD", "custom-model-data": 40 } }
    },
    sourcejars: { jars: { j1: { material: "DECORATED_POT", "custom-model-data": 50 } } },
    sourcelinks: { items: { s1: { material: "FURNACE", "custom-model-data": 60 } } },
    "external-items": { items: { ext: { material: "PAPER", "custom-model-data": 70 } } }
  };
  const usage = CmdRegistry.scanUsage((id) => fixtures[id] || null);
  const has = (material, cmd) => usage.some((u) => u.material === material && u.cmd === cmd);
  assert.ok(has("IRON_INGOT", 10), "catalog");
  assert.ok(has("IRON_INGOT", 10), "item-stats (同じキーに集約)");
  assert.ok(has("STRING", 20), "materials");
  assert.ok(has("BOOK", 30), "spellbooks (books は BOOK 固定)");
  assert.ok(has("BLAZE_ROD", 40), "spellbooks catalysts");
  assert.ok(has("DECORATED_POT", 50), "sourcejars");
  assert.ok(has("FURNACE", 60), "sourcelinks");
  assert.ok(has("PAPER", 70), "external-items");
  // GOLD_INGOT (CMD無しキー) は集計されない
  assert.ok(!usage.some((u) => u.material === "GOLD_INGOT"));
});

test("allocate: 台帳へ追記して保存し、cmd を返す", () => {
  const dir = tmpDir();
  const registryPath = path.join(dir, "cmd-registry.json");
  const { cmd } = CmdRegistry.allocate(registryPath, { material: "IRON_INGOT", id: "test_item", source: "catalog" }, emptyReader());
  assert.equal(cmd, 1);
  const saved = CmdRegistry.loadRegistry(registryPath);
  assert.equal(saved.allocations.length, 1);
  assert.equal(saved.allocations[0].material, "IRON_INGOT");
  assert.equal(saved.allocations[0].cmd, 1);
  assert.equal(saved.allocations[0].id, "test_item");

  const second = CmdRegistry.allocate(registryPath, { material: "IRON_INGOT", id: "test_item2" }, emptyReader());
  assert.equal(second.cmd, 2);
});

test("allocateBulk: 複数件へ直列採番し、台帳保存は1回で全件記録される", () => {
  const dir = tmpDir();
  const registryPath = path.join(dir, "cmd-registry.json");
  const { results } = CmdRegistry.allocateBulk(registryPath, [
    { material: "IRON_INGOT", id: "a", source: "catalog" },
    { material: "IRON_INGOT", id: "b", source: "catalog" },
    { material: "BLAZE_ROD", id: "c", source: "catalog" }
  ], emptyReader());
  assert.deepEqual(results.map((r) => [r.id, r.material, r.cmd]),
    [["a", "IRON_INGOT", 1], ["b", "IRON_INGOT", 2], ["c", "BLAZE_ROD", 3]]);
  // BLAZE_ROD は同material初回だが、1,2 は全material衝突回避で 3 になる
  const saved = CmdRegistry.loadRegistry(registryPath);
  assert.equal(saved.allocations.length, 3);
});

test("allocateBulk: 不正materialが1件でもあれば全件失敗し台帳は書かれない", () => {
  const dir = tmpDir();
  const registryPath = path.join(dir, "cmd-registry.json");
  assert.throws(() => CmdRegistry.allocateBulk(registryPath, [
    { material: "IRON_INGOT", id: "ok" },
    { material: "NOT_A_MATERIAL", id: "bad" }
  ], emptyReader()), /実在しない/);
  assert.equal(fs.existsSync(registryPath), false);
});

test("allocateBulk: 空配列は台帳を作らず空の結果を返す", () => {
  const dir = tmpDir();
  const registryPath = path.join(dir, "cmd-registry.json");
  const { results } = CmdRegistry.allocateBulk(registryPath, [], emptyReader());
  assert.deepEqual(results, []);
  assert.equal(fs.existsSync(registryPath), false);
});

test("nextCmd: MAX_CMD超えの不正値は連番起点に使われない (衝突回避には使われる)", () => {
  const usage = [
    { material: "WOODEN_SWORD", cmd: 37101001 }, // 上限超の既存不正値
    { material: "WOODEN_SWORD", cmd: 10 }
  ];
  const cmd = CmdRegistry.nextCmd("WOODEN_SWORD", usage, { allocations: [] });
  assert.equal(cmd, 11);
});

test("loadRegistry: ファイルが無ければ空台帳を返す", () => {
  const dir = tmpDir();
  const registry = CmdRegistry.loadRegistry(path.join(dir, "missing.json"));
  assert.deepEqual(registry, { version: 1, allocations: [] });
});

test("H-2: loadRegistry は壊れたJSONに対して RegistryCorruptError を投げる", () => {
  const dir = tmpDir();
  const regPath = path.join(dir, "cmd-registry.json");
  fs.writeFileSync(regPath, "{ not valid json !!");
  assert.throws(() => CmdRegistry.loadRegistry(regPath), CmdRegistry.RegistryCorruptError);
  assert.throws(() => CmdRegistry.loadRegistry(regPath), /破損しています/);
});

test("H-2: loadRegistry は想定外シェイプ(allocationsが配列でない)にも RegistryCorruptError を投げる", () => {
  const dir = tmpDir();
  const regPath = path.join(dir, "cmd-registry.json");
  fs.writeFileSync(regPath, JSON.stringify({ version: 1, allocations: "not-an-array" }));
  assert.throws(() => CmdRegistry.loadRegistry(regPath), CmdRegistry.RegistryCorruptError);
});

test("H-2: loadRegistrySafe は破損台帳でも例外を投げず corrupt:true を返す", () => {
  const dir = tmpDir();
  const regPath = path.join(dir, "cmd-registry.json");
  fs.writeFileSync(regPath, "{ not valid json !!");
  const { registry, corrupt } = CmdRegistry.loadRegistrySafe(regPath);
  assert.equal(corrupt, true);
  assert.deepEqual(registry, { version: 1, allocations: [] });
});

test("H-2: allocate は破損台帳に対してエラーを投げ、番号を払い出さず既存.bakも破壊しない", () => {
  const dir = tmpDir();
  const regPath = path.join(dir, "cmd-registry.json");
  fs.writeFileSync(regPath, "{ not valid json !!");
  const validBak = JSON.stringify({ version: 1, allocations: [{ material: "IRON_INGOT", cmd: 1 }] });
  fs.writeFileSync(`${regPath}.bak`, validBak);

  assert.throws(
    () => CmdRegistry.allocate(regPath, { material: "IRON_INGOT", id: "x" }, emptyReader()),
    /破損しています/
  );
  // 破損した本体ファイルはそのまま (再度読んでも同じ壊れた内容)
  assert.equal(fs.readFileSync(regPath, "utf8"), "{ not valid json !!");
  // 正常な .bak は上書きされていない
  assert.equal(fs.readFileSync(`${regPath}.bak`, "utf8"), validBak);
});

test("H-2: saveRegistry は既存ファイルが破損している場合 .bak を上書きせずエラーを投げる", () => {
  const dir = tmpDir();
  const regPath = path.join(dir, "cmd-registry.json");
  fs.writeFileSync(regPath, "{ not valid json !!");
  const validBak = JSON.stringify({ version: 1, allocations: [] });
  fs.writeFileSync(`${regPath}.bak`, validBak);

  assert.throws(
    () => CmdRegistry.saveRegistry(regPath, { version: 1, allocations: [] }),
    CmdRegistry.RegistryCorruptError
  );
  assert.equal(fs.readFileSync(`${regPath}.bak`, "utf8"), validBak);
});

test("L-2: allocate は実在しないmaterialを拒否する", () => {
  const dir = tmpDir();
  const regPath = path.join(dir, "cmd-registry.json");
  assert.throws(
    () => CmdRegistry.allocate(regPath, { material: "NOT_A_REAL_MATERIAL", id: "x" }, emptyReader()),
    /実在しないMaterial/
  );
});

test("isValidMaterial: 実在するMaterialのみtrueを返す", () => {
  assert.equal(CmdRegistry.isValidMaterial("IRON_INGOT"), true);
  assert.equal(CmdRegistry.isValidMaterial("BOOK"), true);
  assert.equal(CmdRegistry.isValidMaterial("NOT_A_REAL_MATERIAL"), false);
});

test("adoptFromConfigs: 新規採用と既存行のid更新", () => {
  const registry = { version: 1, allocations: [{ material: "IRON_INGOT", cmd: 1, id: "old_id", source: "catalog" }] };
  const usage = [
    { material: "IRON_INGOT", cmd: 1, sources: [{ file: "catalog", id: "new_id" }] }, // 既存行 → id維持(既存優先)
    { material: "STRING", cmd: 2, sources: [{ file: "materials", id: "bar" }] } // 新規採用
  ];
  const next = CmdRegistry.adoptFromConfigs(usage, registry);
  assert.equal(next.allocations.length, 2);
  const ironRow = next.allocations.find((a) => a.material === "IRON_INGOT");
  assert.equal(ironRow.id, "old_id"); // 既存行のidは維持 (上書きしない)
  const stringRow = next.allocations.find((a) => a.material === "STRING");
  assert.equal(stringRow.id, "bar");
});

test("adoptFromConfigs: 既存行に id が無ければ検出元 id を埋める", () => {
  const registry = { version: 1, allocations: [{ material: "IRON_INGOT", cmd: 1, id: null, source: null }] };
  const usage = [{ material: "IRON_INGOT", cmd: 1, sources: [{ file: "catalog", id: "detected_id" }] }];
  const next = CmdRegistry.adoptFromConfigs(usage, registry);
  assert.equal(next.allocations[0].id, "detected_id");
});

test("reconcileWithUsage: 削除済みconfigの台帳行を残さず、現存由来へ正規化する", () => {
  const registry = { version: 1, allocations: [
    { material: "IRON_INGOT", cmd: 10, id: "deleted", source: "materials", assetName: "old_asset" },
    { material: "BOOK", cmd: 20, id: "stale_book", source: "spellbooks" }
  ] };
  const usage = [{ material: "IRON_INGOT", cmd: 10, sources: [{ file: "catalog", id: "current" }] }];
  const next = CmdRegistry.reconcileWithUsage(usage, registry);
  assert.equal(next.allocations.length, 1);
  assert.deepEqual({ ...next.allocations[0], allocatedAt: undefined }, {
    material: "IRON_INGOT", cmd: 10, id: "current", source: "catalog", assetName: "old_asset", allocatedAt: undefined
  });
});
