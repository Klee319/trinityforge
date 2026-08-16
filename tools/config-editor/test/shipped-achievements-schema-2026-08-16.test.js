"use strict";

// 出荷 progression/achievements.yml と collection.yml が、エディタのスキーマ検証を
// そのまま通ることを固定する(2026-08-16)。
//
// なぜ要るか: エディタの検証はサーバ起動とは独立した2本目の実装なので、Java 側だけを
// 拡張して lib/schema.js を放置すると「サーバでは正常に動く出荷 yml を、エディタで開くと
// 検証エラーになって保存できない」という片肺状態になる。しかもこの状態は
// **エディタを開くまで誰も気づかない**(テストも yml も静かなまま)。
// 実際 2026-07-31 の複数対象 targets 拡張でこれが起きていた。
//
// ここは「出荷 yml を実読して validate() に通す」だけにしてある。件数や個々のIDは
// 一切写さない ── 写すとツリーを組み替えるたびに【正しく直した側】が落ちる。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const { validate } = require("../lib/schema.js");

const ROOT = path.resolve(__dirname, "..");
const RES = path.resolve(ROOT, "../../TrinityForge/src/main/resources");
const load = (rel) => YAML.parse(fs.readFileSync(path.join(RES, rel), "utf8"));

test("出荷 achievements.yml はエディタのスキーマ検証を通る", () => {
  assert.deepStrictEqual(validate("tf-achievements", load("progression/achievements.yml")), []);
});

test("出荷 collection.yml はエディタのスキーマ検証を通る", () => {
  assert.deepStrictEqual(validate("tf-collection", load("progression/collection.yml")), []);
});

test("出荷 special-rewards.yml はエディタのスキーマ検証を通る", () => {
  assert.deepStrictEqual(validate("tf-special-rewards", load("progression/special-rewards.yml")), []);
});

// 2026-08-16 追加のトリガー種別が、実際に出荷 yml で使われていること。
// 使われていなければ上の検証は「新しい分岐を一度も通っていない」ので、
// スキーマを壊しても緑のままになる(検証が検証になっていない状態)。
test("出荷 achievements.yml は gear-use / skill-level / hidden を実際に使っている", () => {
  const data = load("progression/achievements.yml");
  const entries = Object.values(data.achievements || {});
  const types = new Set(entries.map((e) => e && e.trigger && e.trigger.type));
  assert.ok(types.has("gear-use"), "gear-use を使うアチーブメントが無い");
  assert.ok(types.has("skill-level"), "skill-level を使うアチーブメントが無い");
  assert.ok(entries.some((e) => e && e.hidden === true), "hidden(裏アチーブメント)が1件も無い");
});
