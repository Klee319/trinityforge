"use strict";

// E-1 (バイオームのセレクト/サジェスト化) + E-2 (モブ候補の重複解消) の回帰テスト。
//
// E-1: vocab-1.21.11.js の window.VANILLA_BIOMES / BIOME_LABELS_JA が
//   stats/fishing-gimmick.yml の既定 ocean-biomes 9件を全て候補として持つこと。
//   また、tf-lifestyle-forms.js のバイオーム行が listSelect(allowCustom:true) で組まれている
//   (=一覧に無い値も自由入力できる、完全なセレクトにしていない)ことをソース上で検証する。
//
// E-2: 以前 mob-forms.js と tf-rewards-forms.js に別々に重複定義されていた
//   ENTITY_TYPE_CANDIDATES(37種、tf-rewards-forms.js側はさらに ENDER_DRAGON/WITHER_BOSS を追加)
//   が、vocab-1.21.11.js の window.VANILLA_MOBS へ集約され、集約後の候補が集約前の和集合を
//   下回らないことを検証する(WITHER_BOSSは paper-api 1.21.11 に存在しない値のため意図的に除外。
//   ars-config-form-tdz.test.js と同様、ブラウザ用IIFEをNodeで最小スタブしてrequireする手法)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const VOCAB_PATH = path.join(__dirname, "..", "public", "js", "vocab-1.21.11.js");
const MOB_FORMS_PATH = path.join(__dirname, "..", "public", "js", "mob-forms.js");
const LIFESTYLE_PATH = path.join(__dirname, "..", "public", "js", "tf-lifestyle-forms.js");
const REWARDS_PATH = path.join(__dirname, "..", "public", "js", "tf-rewards-forms.js");

// stats/fishing-gimmick.yml の fishing.ocean-biomes 既定値(9件)。
const DEFAULT_OCEAN_BIOMES = [
  "ocean", "deep_ocean", "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean",
  "cold_ocean", "deep_cold_ocean", "frozen_ocean", "deep_frozen_ocean"
];

// 集約前に mob-forms.js / tf-rewards-forms.js にあった重複配列(和集合)。WITHER_BOSS は
// paper-api 1.21.11 の EntityType に存在しない無効値と確認済みのため、和集合からは除外して
// 「有効な候補は減っていないか」を検証する(無効値の消失は許容される)。
const PRE_CONSOLIDATION_UNION = [
  "ZOMBIE", "SKELETON", "CREEPER", "SPIDER", "CAVE_SPIDER", "ENDERMAN", "WITCH",
  "ZOMBIE_VILLAGER", "HUSK", "DROWNED", "STRAY", "PHANTOM", "PILLAGER", "VINDICATOR",
  "EVOKER", "RAVAGER", "BLAZE", "WITHER_SKELETON", "PIGLIN", "PIGLIN_BRUTE", "HOGLIN",
  "ZOGLIN", "GHAST", "SLIME", "MAGMA_CUBE", "GUARDIAN", "ELDER_GUARDIAN", "SHULKER",
  "VEX", "WARDEN", "BREEZE", "BOGGED", "CREAKING", "ENDERMITE", "SILVERFISH",
  "ILLUSIONER", "WITHER", "ENDER_DRAGON"
];

function loadVocabInto(win) {
  global.window = win;
  delete require.cache[require.resolve(VOCAB_PATH)];
  require(VOCAB_PATH);
}

test("E-1: VANILLA_BIOMES が ocean-biomes の既定9件を全て含む", () => {
  loadVocabInto({});
  const biomes = new Set(global.window.VANILLA_BIOMES);
  for (const b of DEFAULT_OCEAN_BIOMES) {
    assert.ok(biomes.has(b), `候補に ${b} が含まれていません`);
  }
});

test("E-1: BIOME_LABELS_JA は VANILLA_BIOMES の全キーに日本語ラベルを持つ", () => {
  loadVocabInto({});
  for (const id of global.window.VANILLA_BIOMES) {
    assert.ok(global.window.BIOME_LABELS_JA[id], `${id} のラベルがありません`);
  }
});

test("E-1: tf-lifestyle-forms.js のバイオーム選択は listSelect(allowCustom:true) で組まれ、自由入力を殺していない", () => {
  const src = fs.readFileSync(LIFESTYLE_PATH, "utf8");
  const fnMatch = src.match(/function biomeSelect\([\s\S]*?\n  \}/);
  assert.ok(fnMatch, "biomeSelect 関数が見つかりません");
  const fnBody = fnMatch[0];
  assert.match(fnBody, /window\.listSelect\(/, "listSelect を使っていません");
  assert.match(fnBody, /allowCustom:\s*true/, "allowCustom:true が指定されていません(自由入力が塞がれています)");
  assert.match(fnBody, /VANILLA_BIOMES/, "VANILLA_BIOMES を候補として使っていません");
});

test("E-2: mob-forms.js の ENTITY_TYPE_CANDIDATES は window.VANILLA_MOBS を参照する(重複定義の解消)", () => {
  const win = {};
  loadVocabInto(win);
  global.window.h = () => ({});
  delete require.cache[require.resolve(MOB_FORMS_PATH)];
  require(MOB_FORMS_PATH);

  const src = fs.readFileSync(MOB_FORMS_PATH, "utf8");
  assert.doesNotMatch(src, /const ENTITY_TYPE_CANDIDATES = \[\s*"ZOMBIE"/,
    "配列がハードコードされたままです(VANILLA_MOBS への集約がされていません)");
  assert.match(src, /ENTITY_TYPE_CANDIDATES = .*VANILLA_MOBS/);
});

test("E-2: tf-rewards-forms.js の ENTITY_TYPE_CANDIDATES は window.VANILLA_MOBS を参照する(重複定義の解消)", () => {
  const src = fs.readFileSync(REWARDS_PATH, "utf8");
  assert.doesNotMatch(src, /const ENTITY_TYPE_CANDIDATES = \[\s*"ZOMBIE"/,
    "配列がハードコードされたままです(VANILLA_MOBS への集約がされていません)");
  assert.match(src, /ENTITY_TYPE_CANDIDATES = .*VANILLA_MOBS/);
});

test("E-2: 集約後の VANILLA_MOBS は集約前の和集合(WITHER_BOSSを除く)を下回らない", () => {
  loadVocabInto({});
  const after = new Set(global.window.VANILLA_MOBS);
  const missing = PRE_CONSOLIDATION_UNION.filter((id) => !after.has(id));
  assert.deepEqual(missing, [], `集約後に失われた候補があります: ${missing.join(", ")}`);
  assert.ok(after.size >= PRE_CONSOLIDATION_UNION.length,
    "集約後の候補数が集約前の和集合を下回っています");
});

test("E-2: WITHER_BOSS は paper-api 1.21.11 に存在しない値として意図的に含まれない(参考記録)", () => {
  loadVocabInto({});
  assert.equal(global.window.VANILLA_MOBS.includes("WITHER_BOSS"), false);
  assert.equal(global.window.VANILLA_MOBS.includes("WITHER"), true);
});
