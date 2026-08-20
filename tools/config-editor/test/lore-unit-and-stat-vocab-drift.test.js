"use strict";

// 2026-07-31 (L7) のステ語彙・単位まわりのドリフト検知。
//
// 1) 単位のデフォルト辞書 (materials.js FALLBACK_STAT_UNITS) が stats/lore.yml の unit と一致すること。
//    tf-lore.js の defaultUnitFor はこの辞書しか見ないため、lore.yml に unit があってここに無いと
//    「Lore表示設定の単位欄がカスタム扱い(チェックON)で表示される」= tick が既定として扱われない。
//    実際 stun-duration-bonus を含む14キーが辞書から漏れていた。片方向だけ検査すると
//    「辞書に残った死にキー」を見逃すので双方向で見る。
// 2) 廃止したステキー(弓CT短縮 / 軽装・重装部位速度 / 旧クラフト上振れ・下振れ)が
//    エディタ側の辞書に残っていないこと、分割後の4キーが揃っていること。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const REPO_ROOT = path.resolve(__dirname, "..", "..", "..");
const JS_DIR = path.join(__dirname, "..", "public", "js");

/** 2026-07-31 に廃止したキー。 */
const RETIRED_KEYS = [
  "bow-cooldown-reduction",
  "light-armor-move-speed-per-piece",
  "heavy-armor-move-speed-per-piece",
  "craft-upswing-bonus",
  "craft-downswing-reduction"
];

/** 作業台/儀式へ分割した後のキー。 */
const SPLIT_KEYS = [
  "workbench-upswing-bonus", "workbench-downswing-reduction",
  "ritual-upswing-bonus", "ritual-downswing-reduction"
];

function loadLoreStats() {
  const yml = fs.readFileSync(
    path.join(REPO_ROOT, "TrinityForge/src/main/resources/stats/lore.yml"), "utf8");
  const parsed = YAML.parse(yml);
  assert.ok(parsed && parsed.stats, "stats/lore.yml に stats: が無い");
  return parsed.stats;
}

function loadEditorGlobals() {
  global.window = {};
  delete require.cache[require.resolve("../public/js/labels.js")];
  delete require.cache[require.resolve("../public/js/materials.js")];
  require("../public/js/labels.js");
  require("../public/js/materials.js");
  return global.window;
}

test("FALLBACK_STAT_UNITS は stats/lore.yml の unit と過不足なく一致する", () => {
  const stats = loadLoreStats();
  const win = loadEditorGlobals();
  const dict = win.FALLBACK_STAT_UNITS;
  assert.ok(dict && typeof dict === "object", "FALLBACK_STAT_UNITS が読めない");

  // PERCENT ステは defaultUnitFor が自動で "%" を返すので辞書の対象外。
  const expected = {};
  for (const [key, entry] of Object.entries(stats)) {
    if (!entry || typeof entry !== "object") continue;
    if (typeof entry.unit !== "string" || entry.unit === "") continue;
    if (String(entry.format || "FLAT").toUpperCase() === "PERCENT") continue;
    expected[key] = entry.unit;
  }
  assert.ok(Object.keys(expected).length > 5, "lore.yml から unit を抽出できていない(空振り)");

  const missing = Object.keys(expected).filter((k) => dict[k] !== expected[k])
    .map((k) => `${k}: lore="${expected[k]}" dict="${dict[k] ?? ""}"`);
  const stale = Object.keys(dict).filter((k) => !(k in expected))
    .map((k) => `${k}="${dict[k]}"`);

  assert.deepEqual(missing, [],
    "lore.yml の unit と FALLBACK_STAT_UNITS が食い違う(単位欄がカスタム扱いで表示される): "
      + missing.join(", "));
  assert.deepEqual(stale, [],
    "FALLBACK_STAT_UNITS に lore.yml 側の裏付けが無いキーが残っている: " + stale.join(", "));
});

test("スタン時間のデフォルト単位は tick で、ラベルには単位を埋め込まない", () => {
  const win = loadEditorGlobals();
  assert.equal(win.defaultStatUnit("stun-duration-bonus"), "tick");
  const label = win.LABELS.STAT_LABELS["stun-duration-bonus"];
  assert.ok(label, "stun-duration-bonus のラベルが無い");
  assert.ok(!label.includes("tick"),
    `ラベルに単位を埋め込むと forms.js が付ける「（tick）」と二重になる: "${label}"`);
});

test("廃止キーはエディタの辞書(ラベル/説明/フォールバック一覧)から消えている", () => {
  const win = loadEditorGlobals();
  const labels = win.LABELS.STAT_LABELS;
  const fallbackStats = win.FALLBACK_STATS || [];
  const leftovers = [];
  for (const key of RETIRED_KEYS) {
    if (key in labels) leftovers.push(`STAT_LABELS[${key}]`);
    if (fallbackStats.includes(key)) leftovers.push(`FALLBACK_STATS[${key}]`);
    const desc = win.LABELS.statDescription(key);
    if (desc && !desc.includes("未登録")) leftovers.push(`STAT_DESCRIPTIONS[${key}]`);
  }
  assert.deepEqual(leftovers, [], "廃止キーがエディタ辞書に残っている: " + leftovers.join(", "));
});

test("分割後の4キーはラベルと説明の両方を持つ", () => {
  const win = loadEditorGlobals();
  for (const key of SPLIT_KEYS) {
    assert.ok(win.LABELS.STAT_LABELS[key], `${key} のラベルが無い`);
    const desc = win.LABELS.statDescription(key);
    assert.ok(desc && !desc.includes("未登録"), `${key} の説明が無い`);
  }
});

test("tf-base-stats.js: 廃止キーは no-op 除外リストと上限UI一覧から消え、分割後の4キーが上限UIに居る", () => {
  global.window = global.window || {};
  global.window.h = (tag, props) => ({ tag, props, children: [], appendChild() {} });
  delete require.cache[require.resolve("../public/js/tf-base-stats.js")];
  const { NO_OP_BASE_STATS_KEYS, statCapsAllKeys } = require("../public/js/tf-base-stats.js");
  // NO_OP_BASE_STATS_KEYS は内部では Set だが module.exports では Array.from されている。
  const noOp = [...NO_OP_BASE_STATS_KEYS];
  const caps = statCapsAllKeys();
  for (const key of RETIRED_KEYS) {
    assert.ok(!noOp.includes(key), `${key} が NO_OP_BASE_STATS_KEYS に残っている`);
    assert.ok(!caps.includes(key), `${key} が STAT_CAPS_SECTIONS に残っている`);
  }
  for (const key of SPLIT_KEYS) {
    assert.ok(caps.includes(key), `${key} が STAT_CAPS_SECTIONS に無い`);
  }
});

test("tf-skilltree.js: 旧nativeの移行先に廃止キーを指すエントリが残っていない", () => {
  // 移行先が消えたキーへ横流しすると、開いて保存した瞬間に値が channel NONE で無言ドロップされる。
  const src = fs.readFileSync(path.join(JS_DIR, "tf-skilltree.js"), "utf8");
  const m = src.match(/const LEGACY_NATIVE_TO_BUFF = \{([\s\S]*?)\n {2}\};/);
  assert.ok(m, "LEGACY_NATIVE_TO_BUFF の定義が見つからない");
  const body = m[1];
  for (const key of RETIRED_KEYS) {
    assert.ok(!body.includes(`"${key}"`),
      `LEGACY_NATIVE_TO_BUFF が廃止キー ${key} へ移行しようとしている`);
  }
});
