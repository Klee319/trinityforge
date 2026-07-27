"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const generator = require("../../scripts/generate-player-wiki.js");

const ROOT = path.resolve(__dirname, "../../..");

test("player wiki generator creates the planned pages from the shipped settings", () => {
  const pages = generator.generatePages(ROOT);

  assert.deepEqual([...pages.keys()], generator.PAGE_NAMES);
  assert.equal(pages.has("README.md"), false);
  assert.match(pages.get("Home.md"), /TrinityForge へようこそ/);
  assert.match(pages.get("職業スキルとスキルツリー.md"), /軽量武器/);
  assert.match(pages.get("追加アイテム.md"), /ソースジェム/);
  assert.match(pages.get("魔法.md"), /見習いの魔法書/);
  assert.match(pages.get("モブとダンジョン.md"), /現在は 89 種類/);
  assert.match(pages.get("その他の追加機能とコマンド.md"), /段階5: 持続 12秒/);
  assert.match(pages.get("その他の追加機能とコマンド.md"), /段階3: 追加で最大 64本分/);
  assert.match(pages.get("その他の追加機能とコマンド.md"), /`\/tf role`/);
});

test("player wiki generator presents each item with a recipe grid and scannable sections", () => {
  const items = generator.generatePages(ROOT).get("追加アイテム.md");
  const start = items.indexOf("<summary><strong>ソースジェムのヘルメット</strong></summary>");
  const helmet = items.slice(start, items.indexOf("</details>", start));

  assert.notEqual(start, -1);
  assert.match(helmet, /### 作り方\n\n\*\*作業台\*\*\n\n\| A \| A \| A \|\n\| :---: \| :---: \| :---: \|\n\| A \| ・ \| A \|\n\| ・ \| ・ \| ・ \|\n\n\*\*材料\*\*　A：ソースジェム　・：空欄/);
  assert.match(helmet, /### 使用条件\n\n\*\*軽装備 Lv\.30\*\*/);
  assert.match(helmet, /### 性能\n\n\| 性能 \| 数値 \|/);
  assert.match(helmet, /### 品質による変化/);
  assert.match(helmet, /### 説明\n\n> 魔力を帯びた外殻/);
  assert.doesNotMatch(helmet, /主な補正:|作り方:|使うための条件:/);
});

test("player wiki generator keeps internal item references out of published text", () => {
  const output = [...generator.generatePages(ROOT).values()].join("\n");

  assert.doesNotMatch(output, /custom:/i);
  assert.doesNotMatch(output, /source_gem/i);
  assert.doesNotMatch(output, /items\/catalog\.yml/i);
  assert.doesNotMatch(output, /\b(?:minecraft|custom):|\btf_[a-z0-9_]+|\bthe_[a-z0-9_]+/i);
  assert.doesNotMatch(output, /CLK-WRx702/i);
  assert.match(output, /グナスラの電気頭/);
  assert.doesNotMatch(output, /今後|未実装|予定/);
});

test("player wiki generator accepts a check-only output directory", () => {
  const options = generator.parseArguments(["--check", "--wiki-dir", "wiki-preview"]);

  assert.equal(options.checkOnly, true);
  assert.equal(options.wikiDirectory, path.resolve("wiki-preview"));
});

test("player wiki generator recognizes a dungeon entrance keyed by its destination", () => {
  const gate = generator.findDungeonGate({
    crypt_depths: {
      "required-combat-level": 25,
      "key-material": "minecraft:echo_shard"
    }
  }, "crypt_depths", "crypt-package");

  assert.equal(gate["required-combat-level"], 25);
});

test("player wiki generator excludes opaque enemy labels while keeping ordinary names", () => {
  assert.equal(generator.playerFacingMobName("CLK-WRx702 (第1段階)"), null);
  assert.equal(generator.playerFacingMobName("グナスラの電気頭"), "グナスラの電気頭");
});

test("player wiki generator renders tiered gathering limits when they are configured", () => {
  const summary = generator.tieredMaximumSummary({
    "max-extra-blocks": 12,
    tiers: { 2: { "max-extra-blocks": 24 } }
  }, "max-extra-blocks", "ブロック");

  assert.equal(summary, "段階2: 追加で最大 24ブロック");
});

test("player wiki generator removes the legacy README entry and detects it in check mode", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "trinityforge-wiki-test-"));
  const pages = new Map([["Home.md", "# Home\n"]]);
  try {
    fs.writeFileSync(path.join(directory, "Home.md"), "# Home\n", "utf8");
    fs.writeFileSync(path.join(directory, "README.md"), "# Home\n", "utf8");

    assert.deepEqual(generator.writePages(pages, directory, true), ["README.md"]);
    assert.deepEqual(generator.writePages(pages, directory, false), ["README.md"]);
    assert.equal(fs.existsSync(path.join(directory, "README.md")), false);
    assert.deepEqual(generator.writePages(pages, directory, true), []);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test("player wiki generator ignores line-ending differences in check mode", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "trinityforge-wiki-test-"));
  const pages = new Map([["Home.md", "# Home\n\n本文\n"]]);
  try {
    fs.writeFileSync(path.join(directory, "Home.md"), "# Home\r\n\r\n本文\r\n", "utf8");

    assert.deepEqual(generator.writePages(pages, directory, true), []);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
