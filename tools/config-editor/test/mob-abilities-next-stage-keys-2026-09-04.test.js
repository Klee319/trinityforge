"use strict";

// 2026-09-04: 敵の技「予告機構」の次段階(中断機構・空振り硬直・固定領域型・グローバル調停キー・
// per-mob 発動順)を editor 側へ追随させる作業の回帰テスト。Java 側は別担当が同時実装中で、
// このテストの時点では出荷 yml にまだ新キーが出てこない(実データからの動的抽出が使えない)。
// そのため mob-abilities-telegraph-keys-2026-09-04.test.js と同じ「ソースの実アクセサ/レンジ表を
// 機械的に突き合わせる」方式を、固定リストで行う(許可リスト方式そのものが検査を無効化した
// 実例があるので、リストは「今回追加した契約キーの網羅」用途に限定し、キーの存在検証は
// 実アクセサの正規表現一致で行う)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const SCHEMA_PATH = path.join(__dirname, "..", "lib", "schema.js");
const FORM_PATH = path.join(__dirname, "..", "public", "js", "mob-abilities-form.js");
const LABELS_PATH = path.join(__dirname, "..", "public", "js", "labels.js");
const MOB_FORMS_PATH = path.join(__dirname, "..", "public", "js", "mob-forms.js");

const schemaSrc = fs.readFileSync(SCHEMA_PATH, "utf8");
const formSrc = fs.readFileSync(FORM_PATH, "utf8");
const labelsSrc = fs.readFileSync(LABELS_PATH, "utf8");
const mobFormsSrc = fs.readFileSync(MOB_FORMS_PATH, "utf8");

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
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

// トップレベル `const NAME = [ ... ];` 配列リテラルの中身にある "value" 群を返す。
function extractStringArray(source, constName) {
  const re = new RegExp(`const ${constName}\\s*=\\s*\\[([\\s\\S]*?)\\];`);
  const m = re.exec(source);
  assert.ok(m, `${constName} の配列リテラルが見つからない`);
  const body = m[1];
  const out = new Set();
  const re2 = /"([a-zA-Z0-9_]+)"/g;
  let em;
  while ((em = re2.exec(body)) !== null) out.add(em[1]);
  return out;
}

const MOB_ABILITY_RANGES = extractRangeTable(schemaSrc, "MOB_ABILITY_RANGES");
const NUMERIC_BOUNDS = extractRangeTable(formSrc, "NUMERIC_BOUNDS");

// ---- テンプレートの新4キー: interruptible / interrupt-damage-fraction /
//      interrupt-lockout-seconds / whiff-stagger-seconds ----

test("interrupt-damage-fraction / interrupt-lockout-seconds / whiff-stagger-seconds は schema と form の両方に同じ範囲で存在する", () => {
  const keys = ["interrupt-damage-fraction", "interrupt-lockout-seconds", "whiff-stagger-seconds"];
  const expected = {
    "interrupt-damage-fraction": [0.005, 0.5],
    "interrupt-lockout-seconds": [0, 60],
    "whiff-stagger-seconds": [0, 5]
  };
  for (const key of keys) {
    assert.ok(MOB_ABILITY_RANGES.has(key), `MOB_ABILITY_RANGES に ${key} が無い`);
    assert.ok(NUMERIC_BOUNDS.has(key), `NUMERIC_BOUNDS に ${key} が無い`);
    const a = MOB_ABILITY_RANGES.get(key);
    const b = NUMERIC_BOUNDS.get(key);
    assert.deepEqual([a[0], a[1]], expected[key], `MOB_ABILITY_RANGES.${key} の範囲が契約と不一致`);
    assert.deepEqual([b[0], b[1]], expected[key], `NUMERIC_BOUNDS.${key} の範囲が契約と不一致`);
  }
});

test("interruptible は schema で真偽値として検証され、form でチェックボックスとして描画される", () => {
  assert.match(schemaSrc, /entry\.interruptible !== undefined && typeof entry\.interruptible !== "boolean"/,
    "schema.js が interruptible を真偽値として検証していない");
  assert.match(formSrc, /checkboxInput\(entry\.interruptible/,
    "mob-abilities-form.js が interruptible をチェックボックスで描画していない");
});

test("interrupt-* / whiff-stagger-seconds は全型共通の数値欄として描画される(型ごとの FIELDS_BY_TYPE 限定ではない)", () => {
  assert.match(formSrc,
    /for \(const key of \["interrupt-damage-fraction", "interrupt-lockout-seconds", "whiff-stagger-seconds"\]\)/,
    "interrupt-* / whiff-stagger-seconds が全型共通の数値ループに含まれていない");
});

// ---- fixed_zone 型 ----

test("fixed_zone は MOB_ABILITY_TYPES(schema) / TYPES(form) / ENUM_LABELS(labels) の3箇所に揃っている", () => {
  const schemaTypes = extractStringArray(schemaSrc, "MOB_ABILITY_TYPES");
  const formTypes = extractStringArray(formSrc, "TYPES");
  assert.ok(schemaTypes.has("fixed_zone"), "MOB_ABILITY_TYPES に fixed_zone が無い");
  assert.ok(formTypes.has("fixed_zone"), "TYPES(mob-abilities-form.js) に fixed_zone が無い");
  assert.match(labelsSrc, /"fixed_zone"\s*:\s*"[^"]+"/, "ENUM_LABELS[\"mob-ability-type\"] に fixed_zone が無い");
});

test("fixed_zone の FIELDS_BY_TYPE は radius / duration-seconds / vertical-radius を持つ (knockback は使わない)", () => {
  const re = /fixed_zone:\s*\[([^\]]*)\]/;
  const m = re.exec(formSrc);
  assert.ok(m, "FIELDS_BY_TYPE.fixed_zone が見つからない");
  const fields = m[1];
  for (const f of ['"radius"', '"duration-seconds"', '"vertical-radius"']) {
    assert.ok(fields.includes(f), `FIELDS_BY_TYPE.fixed_zone に ${f} が無い`);
  }
  assert.ok(!fields.includes('"knockback"'), "fixed_zone に knockback は含めない契約");
});

// ---- グローバルキー: telegraph-lethal-atomic / telegraph-bar-style ----

test("telegraph-lethal-atomic は真偽値、telegraph-bar-style は block/ascii のみを許すよう schema が検証する", () => {
  assert.match(schemaSrc,
    /data\["telegraph-lethal-atomic"\] !== undefined && typeof data\["telegraph-lethal-atomic"\] !== "boolean"/,
    "schema.js が telegraph-lethal-atomic を真偽値として検証していない");
  assert.match(schemaSrc, /\["block", "ascii"\]\.includes\(String\(data\["telegraph-bar-style"\]\)\)/,
    "schema.js が telegraph-bar-style を block/ascii の列挙として検証していない");
});

test("mob-abilities-form.js のグローバル欄(全体設定)が telegraph-lethal-atomic と telegraph-bar-style を描画する", () => {
  assert.match(formSrc, /working\["telegraph-lethal-atomic"\]/,
    "全体設定カードが telegraph-lethal-atomic を読み書きしていない");
  assert.match(formSrc, /working\["telegraph-bar-style"\]/,
    "全体設定カードが telegraph-bar-style を読み書きしていない");
});

// ---- mob-overrides の per-mob 新キー: ability-sequence / ability-interval-seconds ----
// schema.js は個別バリデータを export していないので、公開APIの validate(schemaType, data) 経由で叩く
// (validate はエラーメッセージの配列をそのまま返す。schema.js 末尾の `return errors;` を参照)。

const { validate } = require("../lib/schema.js");

test("validate() の戻り値の形を確認する(エラー配列であること)", () => {
  const result = validate("tf-mob-overrides", { overrides: {} });
  assert.ok(Array.isArray(result), `validate() がエラー配列を返していない: ${JSON.stringify(result)}`);
});

test("mob-overrides: ability-sequence は文字列配列としてIDの形を検証し、同じIDの重複はエラーにしない", () => {
  const data = {
    overrides: {
      world_dungeon: {
        mobs: {
          boss_a: {
            "ability-sequence": ["ground_slam", "ground_slam", "beam_x"]
          }
        }
      }
    }
  };
  const errors = validate("tf-mob-overrides", data);
  assert.deepEqual(errors, [], `重複IDだけで妥当な形式なのにエラーが出た: ${JSON.stringify(errors)}`);
});

test("mob-overrides: ability-sequence の不正な要素(空文字/記号)はエラーになる", () => {
  const data = {
    overrides: {
      w: { mobs: { boss_a: { "ability-sequence": ["OK_id", "bad id!"] } } }
    }
  };
  const errors = validate("tf-mob-overrides", data);
  assert.ok(errors.length > 0, "不正なability-sequence要素がエラーにならなかった");
  assert.ok(errors.some((e) => e.includes("ability-sequence")),
    `エラー文言に ability-sequence が含まれない: ${JSON.stringify(errors)}`);
});

test("mob-overrides: ability-interval-seconds は 0.5〜120 の数値のみ許す", () => {
  const ok = validate("tf-mob-overrides", { overrides: { w: { mobs: { m: { "ability-interval-seconds": 30 } } } } });
  assert.deepEqual(ok, [], `妥当な値なのにエラーが出た: ${JSON.stringify(ok)}`);

  const tooLow = validate("tf-mob-overrides", { overrides: { w: { mobs: { m: { "ability-interval-seconds": 0.1 } } } } });
  assert.ok(tooLow.length > 0, "範囲外(下限未満)の ability-interval-seconds がエラーにならなかった");

  const tooHigh = validate("tf-mob-overrides", { overrides: { w: { mobs: { m: { "ability-interval-seconds": 999 } } } } });
  assert.ok(tooHigh.length > 0, "範囲外(上限超過)の ability-interval-seconds がエラーにならなかった");
});

test("mob-forms.js が per-mob 欄で ability-sequence と ability-interval-seconds を描画する", () => {
  assert.match(mobFormsSrc, /host\["ability-sequence"\]/,
    "mob-forms.js が ability-sequence を読み書きしていない");
  assert.match(mobFormsSrc, /host\["ability-interval-seconds"\]/,
    "mob-forms.js が ability-interval-seconds を読み書きしていない");
});

// ---- telegraph-bar-style の不正値検出(schema の実挙動) ----

test("telegraph-bar-style に block/ascii 以外を書くと検証エラーになる(schema.jsの実挙動)", () => {
  const bad = validate("tf-mob-abilities", { "telegraph-bar-style": "foo" });
  assert.ok(bad.some((e) => e.includes("telegraph-bar-style")),
    `telegraph-bar-style: "foo" がエラーにならなかった: ${JSON.stringify(bad)}`);

  const ok = validate("tf-mob-abilities", { "telegraph-bar-style": "ascii" });
  assert.deepEqual(ok, [], `妥当な telegraph-bar-style なのにエラーが出た: ${JSON.stringify(ok)}`);
});
