"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");
const YAML = require("yaml");

// アイテムステータス「補助」にスレッドが並ぶ (2026-08-29)。
// STRING は inferItemCategory が other を返す。ピンが無い／other に退化すると
// スレッドタブへ行けず補助へ落ちる。カタログ ID thread_* と STRING#スレッド帯 CMD は
// 身元だけで thread とみなす。

global.window = global.window || {};
require("../public/js/editor-categories.js");
require("../public/js/materials.js");
require("../public/js/catalog-candidates.js");

const { getItemDisplayTab, setItemDisplayTab, isThreadDisplayIdentity } = global.window;
const buildCatalogCandidates = global.window.buildCatalogCandidates
  || require("../public/js/catalog-candidates.js").buildCatalogCandidates;

test("thread_* カタログIDはピン無しでも thread タブ", () => {
  assert.equal(isThreadDisplayIdentity("thread_speed"), true);
  assert.equal(getItemDisplayTab({ items: {} }, "thread_speed", "STRING"), "thread");
});

test("STRING#スレッド帯 CMD はピン無しでも thread タブ", () => {
  assert.equal(isThreadDisplayIdentity("STRING#300001"), true);
  assert.equal(isThreadDisplayIdentity("STRING#100023"), true);
  assert.equal(isThreadDisplayIdentity("STRING"), false);
  assert.equal(getItemDisplayTab({ items: {} }, "STRING#300001", "STRING"), "thread");
  assert.equal(getItemDisplayTab({ items: {} }, "STRING#100027", "STRING"), "thread");
});

test("STRING#トレジャー帯 CMD はピン無しでも thread タブ", () => {
  assert.equal(isThreadDisplayIdentity("STRING#300070"), true);
  assert.equal(isThreadDisplayIdentity("STRING#300079"), true);
  assert.equal(getItemDisplayTab({ items: {} }, "STRING#300070", "STRING"), "thread");
});

test("other に退化したピンよりスレッド身元を優先する", () => {
  const host = { items: {} };
  setItemDisplayTab(host, "STRING#300004", "other");
  assert.equal(getItemDisplayTab(host, "STRING#300004", "STRING"), "thread");
});

test("武器など別タブのピンはスレッド身元で上書きしない", () => {
  const host = { items: {} };
  setItemDisplayTab(host, "thread_speed", "weapon");
  assert.equal(getItemDisplayTab(host, "thread_speed", "STRING"), "weapon");
});

test("catalog-candidates: thread_* は Node 経路でも tab: thread", () => {
  const out = buildCatalogCandidates({
    items: {
      thread_speed: { material: "STRING", "custom-model-data": 300002, "display-name": "速度" },
      sword1: { material: "IRON_SWORD", "custom-model-data": 100, "display-name": "剣" }
    }
  });
  const thread = out.find((c) => c.id === "thread_speed");
  const sword = out.find((c) => c.id === "sword1");
  assert.equal(thread.tab, "thread");
  assert.equal(sword.tab, "other");
});

const ITEM_STATS_PATH = path.join(__dirname, "..", "..", "..", "TrinityForge", "src",
  "main", "resources", "stats", "item-stats.yml");

test("出荷 item-stats はトレジャー帯 STRING#300070-300079 を thread にピンする", () => {
  const data = YAML.parse(fs.readFileSync(ITEM_STATS_PATH, "utf8"));
  const tabs = (data._editor && data._editor.itemTabs) || {};
  const nest = ((data._editor && data._editor.categories && data._editor.categories.thread) || [])
    .find((c) => c && c.id === "cat_20260802_thread_combat");
  const nestIds = new Set((nest && nest.itemIds) || []);
  for (let cmd = 300070; cmd <= 300079; cmd++) {
    const key = `STRING#${cmd}`;
    assert.equal(tabs[key], "thread", `${key} が itemTabs で thread にピンされていない`);
    assert.equal(nestIds.has(key), true, `${key} がスレッドタブの戦闘系ネストに無い`);
  }
});
