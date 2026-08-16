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
  assert.equal(pages.has("追加アイテム.md"), false);
  assert.match(pages.get("Home.md"), /はじめての人へ/);
  assert.match(pages.get("事典-職業スキルとスキルツリー.md"), /軽量武器/);
  assert.match(pages.get("事典-軽装.md"), /ソースジェム/);
  assert.match(pages.get("事典-呪文グリフ.md"), /見習いの魔法書/);
  assert.match(pages.get("事典-モブとダンジョン.md"), /現在は 89 種類/);
  // 段の数も秒数も config 側で動く。特定の値で固定すると、config を触るたびここが赤くなり、
  // 「テストのほうを実値に書き換える」運用になって検査の意味が消える。書式だけを固定する。
  assert.match(pages.get("事典-その他の機能とコマンド.md"), /段階\d+: 持続 [\d.]+秒、強さ \d+/);
  assert.match(pages.get("事典-その他の機能とコマンド.md"), /段階\d+: 追加で最大 \d+本分/);
  assert.match(pages.get("事典-その他の機能とコマンド.md"), /`\/tf role`/);
});

test("player wiki generator publishes the hand written pages with rewritten links", () => {
  const pages = generator.generatePages(ROOT);

  // 手書き原稿は原稿名で相互リンクしている。公開名へ差し替えないと全部 404 になる。
  const combat = pages.get("戦闘のしくみ.md");
  assert.match(combat, /^# 戦闘のしくみ\n\n\[Home\]\(Home\.md\)/);
  assert.doesNotMatch([...pages.values()].join("\n"), /\]\(\d\d-[^)]*\.md/);
  assert.match(pages.get("このサーバーの遊び方.md"), /\[戦闘のしくみ\]\(戦闘のしくみ\.md\)/);
});

test("player wiki generator gives every item page a summary table before the folded details", () => {
  const pages = generator.generatePages(ROOT);

  // 一覧表を先に出さないと、4000 行の折りたたみを上から順に開くしか探す手段が無くなる。
  for (const fileName of ["事典-軽武器.md", "事典-重装.md", "事典-スレッド.md", "事典-ダンジョンの鍵.md"]) {
    const content = pages.get(fileName);
    const table = content.indexOf("| 名前 | 入手 | 使用条件 | 主な効果 |");
    const details = content.indexOf("<details>");
    assert.notEqual(table, -1, `${fileName} に一覧表がありません`);
    assert.notEqual(details, -1, `${fileName} に詳細がありません`);
    assert.ok(table < details, `${fileName} は一覧表が詳細より後ろにあります`);
  }
});

test("player wiki generator sorts catalogue items into the page matching their skill", () => {
  const pages = generator.generatePages(ROOT);

  // 分類の軸は item-stats.yml の use-skill。軽装の品が重装のページへ紛れ込むと探せない。
  assert.match(pages.get("事典-軽装.md"), /ソースジェムのヘルメット/);
  assert.doesNotMatch(pages.get("事典-重装.md"), /ソースジェムのヘルメット/);
  assert.match(pages.get("事典-スレッド.md"), /スレッド/);
  assert.doesNotMatch(pages.get("事典-軽武器.md"), /のスレッド<\/strong>/);
});

test("player wiki generator leaves unreleased catalogue items out of the published pages", () => {
  const YAML = require("yaml");
  const catalog = YAML.parse(
    fs.readFileSync(path.join(ROOT, "TrinityForge/src/main/resources/items/catalog.yml"), "utf8")
  );
  // draft: true は ItemCatalogConfig が template()/all() から除いている＝ゲームに存在しない。
  // 載せると「Wiki にあるのに手に入らない品」になる。
  const drafts = Object.entries(catalog.items || {})
    .filter(([, entry]) => entry && entry.draft === true)
    .map(([, entry]) => String(entry["display-name"] || "").replace(/<[^>]*>/g, "").trim())
    .filter((name) => name.length >= 4);

  assert.ok(drafts.length >= 20, `draft の品を ${drafts.length} 件しか拾えていません`);

  const output = [...generator.generatePages(ROOT).values()].join("\n");
  const leaked = drafts.filter((name) => output.includes(`<strong>${name}</strong>`));
  assert.deepEqual(leaked, [], `未出荷の品が Wiki に載っています: ${leaked.join("、")}`);
});

test("player wiki generator presents each item with a recipe grid and scannable sections", () => {
  const items = generator.generatePages(ROOT).get("事典-軽装.md");
  const start = items.indexOf("<summary><strong>ソースジェムのヘルメット</strong></summary>");
  const helmet = items.slice(start, items.indexOf("</details>", start));

  assert.notEqual(start, -1);
  assert.match(helmet, /### 作り方\n\n\*\*作業台\*\*\n\n\| A \| A \| A \|\n\| :---: \| :---: \| :---: \|\n\| A \| ・ \| A \|\n\| ・ \| ・ \| ・ \|\n\n\*\*材料\*\*　A：ソースジェム　・：空欄/);
  // 必要レベルの実値は item-stats.yml 側で動くので、スキル名と書式だけを固定する。
  assert.match(helmet, /### 使用条件\n\n\*\*軽装備 Lv\.\d+\*\*/);
  assert.match(helmet, /### 性能\n\n\| 性能 \| 数値 \|/);
  assert.match(helmet, /### 品質による変化/);
  assert.match(helmet, /### 説明\n\n> 魔力を帯びた外殻/);
  assert.doesNotMatch(helmet, /主な補正:|作り方:|使うための条件:/);
});

test("player wiki generator builds a sidebar that links only to pages it actually writes", () => {
  const pages = generator.generatePages(ROOT);
  const sidebar = pages.get("_Sidebar.md");
  const linked = [...sidebar.matchAll(/\]\(([^)]+)\)/g)].map((match) => match[1]);

  assert.ok(linked.length >= 20, `サイドバーのリンクが ${linked.length} 件しかありません`);
  for (const target of linked) {
    assert.ok(pages.has(target), `サイドバーの ${target} は生成されていません`);
  }
  // 見出しが無いとページ名のべた並びになり、いまの「見にくい」状態へ戻る。
  assert.match(sidebar, /### 事典（アイテム）/);
  assert.match(sidebar, /### 管理者向け/);
});

test("player wiki generator keeps internal item references out of published text", () => {
  // 検査対象は「設定から生成したページ」だけ。手書きページを混ぜると、
  // 設定キーを列挙するのが仕事の管理者ガイドが毎回引っかかり、検査ごと外すことになる。
  const pages = generator.generatePages(ROOT);
  const generated = new Set(generator.GENERATED_PAGES.map((entry) => entry.page));
  const output = [...pages].filter(([fileName]) => generated.has(fileName)).map(([, content]) => content).join("\n");

  assert.ok(generated.size >= 10, `検査対象が ${generated.size} ページしかありません`);
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
