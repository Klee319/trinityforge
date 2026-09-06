"use strict";

// 2026-09-04: combat/mob-abilities.yml へ cast-seconds / lethal / vertical-radius の3キーを
// 追加する作業に合わせた editor 側追随の回帰テスト。
//
// このリポジトリでは「Java の丸め表を editor 側が手で複製している」箇所が2本ある
// (lib/schema.js の MOB_ABILITY_RANGES と public/js/mob-abilities-form.js の NUMERIC_BOUNDS)。
// ここがズレると「エディタで開いて保存しただけで yml の意味が変わる」事故になる
// (editor-normalize-default-drift と同種の罠)。
//
// 許可リスト方式(実在しないキーを「実在する」と書いて検査ごと無効化した実例がある)は取らない。
// 代わりに、出荷 mob-abilities.yml に実在する全キー(∪ telegraph 新3キー)を動的に洗い出し、
// schema.js / mob-abilities-form.js の両方が実際に読み書きしている(=ソース中に
// entry["key"] / entry.key というアクセサ、またはレンジ表のキーとして存在する)かを機械的に
// 突き合わせる。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const REPO_ROOT = path.resolve(__dirname, "..", "..", "..");
const YML_PATH = path.join(REPO_ROOT, "TrinityForge", "src", "main", "resources", "combat", "mob-abilities.yml");
const SCHEMA_PATH = path.join(__dirname, "..", "lib", "schema.js");
const FORM_PATH = path.join(__dirname, "..", "public", "js", "mob-abilities-form.js");

const schemaSrc = fs.readFileSync(SCHEMA_PATH, "utf8");
const formSrc = fs.readFileSync(FORM_PATH, "utf8");

// 2026-09-04 に Java 側(別担当が同時実装)へ追加された3キー。出荷 yml の実データにはまだ
// 出てこないので、実キー洗い出しの結果と union して検査対象へ含める。
const NEW_TELEGRAPH_KEYS = ["cast-seconds", "lethal", "vertical-radius"];

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

// entry["key"] / entry.key (ダッシュ無しキーのみ dot 記法も許容) という
// 実際のアクセサ形の出現を機械的に見る。手書きの辞書はここには置かない。
function hasAccessor(source, key) {
  const bracket = new RegExp(`entry\\[["']${escapeRegExp(key)}["']\\]`);
  if (bracket.test(source)) return true;
  if (!/-/.test(key)) {
    const dot = new RegExp(`entry\\.${escapeRegExp(key)}\\b`);
    if (dot.test(source)) return true;
  }
  return false;
}

// トップレベル `const NAME = { ... };` ブロック(閉じ括弧は行頭)を抜き出し、
// "key": [min, max, ...] の形のエントリを Map<key, number[]> として返す。
function extractRangeTable(source, constName) {
  const re = new RegExp(`const ${constName}\\s*=\\s*\\{([\\s\\S]*?)\\n[ \\t]*\\};`);
  const m = re.exec(source);
  assert.ok(m, `${constName} のオブジェクトリテラルが見つからない(ソースの形が変わった可能性)`);
  const body = m[1];
  const map = new Map();
  const entryRe = /"([a-zA-Z0-9-]+)"\s*:\s*\[([^\]]+)\]/g;
  let em;
  while ((em = entryRe.exec(body)) !== null) {
    map.set(em[1], em[2].split(",").map((s) => Number(s.trim())));
  }
  return map;
}

const MOB_ABILITY_RANGES = extractRangeTable(schemaSrc, "MOB_ABILITY_RANGES");
const NUMERIC_BOUNDS = extractRangeTable(formSrc, "NUMERIC_BOUNDS");

function realKeysFromShippedYaml() {
  assert.ok(fs.existsSync(YML_PATH), `not found: ${YML_PATH}`);
  const doc = YAML.parse(fs.readFileSync(YML_PATH, "utf8"));
  assert.ok(doc && doc.abilities && Object.keys(doc.abilities).length > 0,
    "combat/mob-abilities.yml の abilities: が空(テスト前提のドリフト)");
  const keys = new Set();
  for (const entry of Object.values(doc.abilities)) {
    if (!entry || typeof entry !== "object") continue;
    for (const k of Object.keys(entry)) keys.add(k);
  }
  return keys;
}

// schema.js の validateTfMobAbilities が「知っている」キーか。
// 範囲表のキー、または個別に検証している文字列/真偽値/type/effects キーのいずれか。
function knownBySchema(key) {
  if (MOB_ABILITY_RANGES.has(key)) return true;
  if (key === "type") return schemaSrc.includes("MOB_ABILITY_TYPES.includes(entry.type)");
  if (key === "effects") return schemaSrc.includes("entry.effects");
  if (key === "damage-type" || key === "lethal") return hasAccessor(schemaSrc, key);
  // 残りの文字列系キー(display-name/projectile/summon-type/particle/sound)は
  // `for (const key of [...]) { ... typeof entry[key] !== "string" ... }` という汎用ループで
  // 検証されている(アクセサが entry[key] で、固定文字列としては出てこない)。ループの配列を
  // ハードコードせず、"文字列である必要があります" を吐くループの配列リテラルを実ソースから
  // 動的に見つけて中身を照合する。
  const stringLoopRe = /for \(const key of (\[[^\]]+\])\)\s*\{\s*\n\s*if \(entry\[key\][^\n]*typeof entry\[key\] !== "string"/;
  const slm = stringLoopRe.exec(schemaSrc);
  if (slm && slm[1].includes(`"${key}"`)) return true;
  return false;
}

// mob-abilities-form.js が「知っている」キーか。数値レンジ表にあるか、実際に entry を
// 読み書きしているか(numberField(entry, key) / textField(entry, "key", ...) / entry["key"])。
function knownByForm(key) {
  if (NUMERIC_BOUNDS.has(key)) return true;
  if (hasAccessor(formSrc, key)) return true;
  const fieldCallRe = new RegExp(`(?:numberField|textField)\\(entry, ["']${escapeRegExp(key)}["']`);
  return fieldCallRe.test(formSrc);
}

test("前提: 出荷 combat/mob-abilities.yml が読め、abilities: が空でない", () => {
  const keys = realKeysFromShippedYaml();
  assert.ok(keys.size > 0, "テスト前提が崩れている(実キーが1つも取れない)");
});

test("mob-abilities.yml の実キー + telegraph新3キーは、schema.js と mob-abilities-form.js の両方が把握している", () => {
  const universe = realKeysFromShippedYaml();
  for (const k of NEW_TELEGRAPH_KEYS) universe.add(k);

  const missingFromSchema = [];
  const missingFromForm = [];
  for (const key of universe) {
    if (!knownBySchema(key)) missingFromSchema.push(key);
    if (!knownByForm(key)) missingFromForm.push(key);
  }

  assert.deepEqual(missingFromSchema, [],
    `lib/schema.js の validateTfMobAbilities が把握していないキー: ${missingFromSchema.join(", ")}`);
  assert.deepEqual(missingFromForm, [],
    `public/js/mob-abilities-form.js が描画/把握していないキー: ${missingFromForm.join(", ")}`);
});

test("MOB_ABILITY_RANGES と NUMERIC_BOUNDS の [min,max] は共通キー全てで一致する(2本のズレ検知)", () => {
  const mismatches = [];
  const allKeys = new Set([...MOB_ABILITY_RANGES.keys(), ...NUMERIC_BOUNDS.keys()]);
  for (const key of allKeys) {
    const a = MOB_ABILITY_RANGES.get(key);
    const b = NUMERIC_BOUNDS.get(key);
    if (!a) { mismatches.push(`${key}: MOB_ABILITY_RANGES に無い(NUMERIC_BOUNDSにだけ存在)`); continue; }
    if (!b) { mismatches.push(`${key}: NUMERIC_BOUNDS に無い(MOB_ABILITY_RANGESにだけ存在)`); continue; }
    if (a[0] !== b[0] || a[1] !== b[1]) {
      mismatches.push(`${key}: schema=[${a[0]},${a[1]}] form=[${b[0]},${b[1]}]`);
    }
  }
  assert.deepEqual(mismatches, [],
    `MOB_ABILITY_RANGES と NUMERIC_BOUNDS がズレているキー: ${mismatches.join(" / ")}`);
});

test("cast-seconds は範囲[0, 2.5]、vertical-radius は範囲[0.5, 8]である(Java combat/MobAbility.javaのclampと一致)", () => {
  assert.deepEqual(MOB_ABILITY_RANGES.get("cast-seconds"), [0, 2.5]);
  assert.deepEqual(NUMERIC_BOUNDS.get("cast-seconds"), [0, 2.5, 0.1]);
  assert.deepEqual(MOB_ABILITY_RANGES.get("vertical-radius"), [0.5, 8]);
  assert.deepEqual(NUMERIC_BOUNDS.get("vertical-radius"), [0.5, 8, 0.5]);
});

test("lethal は真偽値としてschemaで検証され、formでチェックボックスとして描画される", () => {
  assert.match(schemaSrc, /entry\.lethal !== undefined && typeof entry\.lethal !== "boolean"/,
    "schema.js が lethal を真偽値として検証していない");
  assert.match(formSrc, /checkboxInput\(entry\.lethal/,
    "mob-abilities-form.js が lethal をチェックボックスで描画していない");
});

test("cast-seconds は全型共通欄として描画される(型ごとの FIELDS_BY_TYPE ではない)", () => {
  assert.match(formSrc,
    /for \(const key of \["damage-percent", "cooldown-seconds", "chance", "range", "cast-seconds"/,
    "cast-seconds が全型共通の数値ループに含まれていない");
});

test("vertical-radius は円判定を使う型(ground_slam/charge/aura/teleport_strike/repulse/vortex_pull/delayed_zone/beam)に出る", () => {
  const circleTypes = ["ground_slam", "charge", "aura", "teleport_strike", "repulse", "vortex_pull",
    "delayed_zone", "beam"];
  const re = /const FIELDS_BY_TYPE\s*=\s*\{([\s\S]*?)\n  \};/;
  const m = re.exec(formSrc);
  assert.ok(m, "FIELDS_BY_TYPE のオブジェクトリテラルが見つからない");
  const body = m[1];
  const missing = [];
  for (const type of circleTypes) {
    const typeRe = new RegExp(`${type}:\\s*\\[([^\\]]*)\\]`);
    const tm = typeRe.exec(body);
    if (!tm || !tm[1].includes('"vertical-radius"')) missing.push(type);
  }
  assert.deepEqual(missing, [], `vertical-radius が FIELDS_BY_TYPE に無い型: ${missing.join(", ")}`);

  // 円判定を使わない型には出さない(意味の無い欄を出さない、というこのファイルの既存方針)。
  for (const type of ["projectile_volley", "summon"]) {
    const typeRe = new RegExp(`${type}:\\s*\\[([^\\]]*)\\]`);
    const tm = typeRe.exec(body);
    assert.ok(tm, `${type} のエントリが見つからない`);
    assert.ok(!tm[1].includes('"vertical-radius"'), `${type} は円判定を使わないのに vertical-radius が出ている`);
  }
});
