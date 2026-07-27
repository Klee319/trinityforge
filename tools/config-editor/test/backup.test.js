"use strict";

// createBackup / pruneBackups (server.js) の単体テスト。
// server.js は require.main === module でない限り app.listen しないため、require するだけで安全。
// 実行: node --test tools/config-editor/test/backup.test.js

const test = require("node:test");
const { after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");

const serverModule = require("../server");
const { createBackup, pruneBackups, backupDirFor, timestamp, __setToolConfigForTest } = serverModule;

// L-10: 一時ディレクトリは CWD 内 (test/.tmp/) に作る (os.tmpdir() は使わない、規約)。
const TMP_ROOT = path.join(__dirname, ".tmp", "backup");
fs.mkdirSync(TMP_ROOT, { recursive: true });
function tmpDir() {
  return fs.mkdtempSync(path.join(TMP_ROOT, "case-"));
}
after(() => {
  fs.rmSync(TMP_ROOT, { recursive: true, force: true });
});

// 各テストごとに独立した一時ルートを作り、toolConfig.backupDir/backupKeep をそこへ差し替える。
// 実リポジトリの backups/ や basePaths は一切参照しない。
function setup(overrides) {
  const dir = tmpDir();
  const backupDir = path.join(dir, "backups");
  __setToolConfigForTest({ backupDir, backupKeep: 50, ...overrides });
  return { dir, backupDir };
}

test("バックアップは backupDir 配下の base/rel 階層に作られ、内容は元ファイルと一致する", () => {
  const { dir, backupDir } = setup();
  const srcDir = path.join(dir, "source", "stats");
  fs.mkdirSync(srcDir, { recursive: true });
  const abs = path.join(srcDir, "item-stats.yml");
  fs.writeFileSync(abs, "a: 1\n", "utf8");

  const entry = { base: "trinityforge", rel: "stats/item-stats.yml" };
  const backupPath = createBackup(abs, entry);

  assert.ok(fs.existsSync(backupPath));
  assert.equal(fs.readFileSync(backupPath, "utf8"), "a: 1\n");

  const expectedDir = path.join(backupDir, "trinityforge", "stats", "item-stats.yml");
  assert.equal(path.dirname(backupPath), expectedDir);
  assert.equal(backupDirFor(entry), expectedDir);
  assert.match(path.basename(backupPath), /^item-stats\.yml\.bak-\d{8}-\d{6}-\d{3}$/);
});

test("元ファイルと同じフォルダには .bak- が作られない", () => {
  const { dir } = setup();
  const srcDir = path.join(dir, "source", "stats");
  fs.mkdirSync(srcDir, { recursive: true });
  const abs = path.join(srcDir, "quality.yml");
  fs.writeFileSync(abs, "q: 1\n", "utf8");

  const entry = { base: "trinityforge", rel: "stats/quality.yml" };
  createBackup(abs, entry);

  const siblings = fs.readdirSync(srcDir);
  const stray = siblings.filter((f) => f.includes(".bak-"));
  assert.deepEqual(stray, []);
});

test("backupKeep を超えたら古い世代から削除され、新しい世代 (直近3件) は残る", () => {
  const { backupDir } = setup({ backupKeep: 3 });
  const dirRoot = tmpDir();
  const srcDir = path.join(dirRoot, "source");
  fs.mkdirSync(srcDir, { recursive: true });
  const abs = path.join(srcDir, "recipes.yml");
  const entry = { base: "trinityforge", rel: "recipes.yml" };

  const created = [];
  for (let i = 0; i < 5; i++) {
    fs.writeFileSync(abs, `v: ${i}\n`, "utf8");
    created.push(createBackup(abs, entry));
  }

  const expectedDir = path.join(backupDir, "trinityforge", "recipes.yml");
  const remaining = fs.readdirSync(expectedDir).sort();
  assert.equal(remaining.length, 3);

  const remainingFull = new Set(remaining.map((f) => path.join(expectedDir, f)));
  for (const p of created.slice(-3)) {
    assert.ok(remainingFull.has(p), `直近世代が残っているはず: ${p}`);
  }
  for (const p of created.slice(0, 2)) {
    assert.ok(!remainingFull.has(p), `古い世代は削除されているはず: ${p}`);
  }
});

test("backupKeep が 0 なら世代を削除しない (無制限)", () => {
  const { backupDir } = setup({ backupKeep: 0 });
  const dirRoot = tmpDir();
  const srcDir = path.join(dirRoot, "source");
  fs.mkdirSync(srcDir, { recursive: true });
  const abs = path.join(srcDir, "gimmick.yml");
  const entry = { base: "trinityforge", rel: "gimmick.yml" };

  for (let i = 0; i < 6; i++) {
    fs.writeFileSync(abs, `v: ${i}\n`, "utf8");
    createBackup(abs, entry);
  }

  const expectedDir = path.join(backupDir, "trinityforge", "gimmick.yml");
  const remaining = fs.readdirSync(expectedDir);
  assert.equal(remaining.length, 6);
});

test("backupKeep が負値でも世代を削除しない (無制限扱い)", () => {
  const { backupDir } = setup({ backupKeep: -1 });
  const dirRoot = tmpDir();
  const srcDir = path.join(dirRoot, "source");
  fs.mkdirSync(srcDir, { recursive: true });
  const abs = path.join(srcDir, "threads.yml");
  const entry = { base: "trinityforge", rel: "threads.yml" };

  for (let i = 0; i < 4; i++) {
    fs.writeFileSync(abs, `v: ${i}\n`, "utf8");
    createBackup(abs, entry);
  }

  const expectedDir = path.join(backupDir, "trinityforge", "threads.yml");
  assert.equal(fs.readdirSync(expectedDir).length, 4);
});

test("同一タイムスタンプ衝突時は連番 (-1, -2, ...) でリトライし、既存バックアップを上書きしない", () => {
  const { dir } = setup();
  const srcDir = path.join(dir, "source");
  fs.mkdirSync(srcDir, { recursive: true });
  const abs = path.join(srcDir, "gate.yml");
  fs.writeFileSync(abs, "v: 1\n", "utf8");
  const entry = { base: "trinityforge", rel: "gate.yml" };

  // fs.copyFileSync を一時的に差し替え、最初の1回だけ EEXIST を強制発生させて
  // createBackup の連番リトライ経路を決定的に検証する (実タイムスタンプの偶発衝突に依存しない)。
  const original = fs.copyFileSync;
  let calls = 0;
  fs.copyFileSync = function patched(src, dest, flags) {
    calls += 1;
    if (calls === 1) {
      const err = new Error("EEXIST (simulated)");
      err.code = "EEXIST";
      throw err;
    }
    return original.call(fs, src, dest, flags);
  };

  let backupPath;
  try {
    backupPath = createBackup(abs, entry);
  } finally {
    fs.copyFileSync = original;
  }

  assert.equal(calls, 2, "1回目のEEXIST後、2回目で成功しているはず");
  assert.match(path.basename(backupPath), /-1$/, "連番 -1 が付与されているはず");
  assert.ok(fs.existsSync(backupPath));
  assert.equal(fs.readFileSync(backupPath, "utf8"), "v: 1\n");
});

test("pruneBackups: -<連番> 付きの変種も含めてタイムスタンプ順に正しく並べて剪定する", () => {
  const { backupDir } = setup({ backupKeep: 2 });
  const expectedDir = path.join(backupDir, "trinityforge", "manual.yml");
  fs.mkdirSync(expectedDir, { recursive: true });

  // 同一タイムスタンプの連番違い (-1, -2 は数値として比較する必要がある: "-10" が "-2" より
  // 文字列的に若く見える罠を踏まないことを確認する)。
  const names = [
    "manual.yml.bak-20260725-100000-000",
    "manual.yml.bak-20260725-100000-000-1",
    "manual.yml.bak-20260725-100000-000-2",
    "manual.yml.bak-20260725-100000-000-10",
    "manual.yml.bak-20260725-110000-000"
  ];
  for (const n of names) {
    fs.writeFileSync(path.join(expectedDir, n), "x", "utf8");
  }

  pruneBackups(expectedDir, "manual.yml");

  const remaining = fs.readdirSync(expectedDir).sort();
  // 最新2件 = 連番-10 (同一タイムスタンプ内で最大の連番) と 11:00:00 (より新しいタイムスタンプ)
  assert.deepEqual(remaining.sort(), [
    "manual.yml.bak-20260725-100000-000-10",
    "manual.yml.bak-20260725-110000-000"
  ].sort());
});

test("timestamp() は bak-YYYYMMDD-HHMMSS-mmm 形式を返す", () => {
  const stamp = timestamp();
  assert.match(stamp, /^\d{8}-\d{6}-\d{3}$/);
});
