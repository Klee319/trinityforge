"use strict";

// lib/spell-form-vocabulary.js の SPELL_FORMS 固定語彙が、Java側の正典である
// fork-handoff/arspaper/fork/src/main/java/com/arspaper/spell/form/*.java の
// `new NamespacedKey(plugin, "<id>")` 群と id 集合でズレていないことを検証する。
// test/gate-vocabulary-java-parity.test.js に倣ったドリフト検知(2026-07-25 config editor T1)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { SPELL_FORMS } = require("../lib/spell-form-vocabulary");

const FORM_DIR = path.join(
  __dirname, "..", "..", "..",
  "fork-handoff", "arspaper", "fork", "src", "main", "java", "com", "arspaper", "spell", "form"
);

const KEY_PATTERN = /new NamespacedKey\(plugin,\s*"([^"]+)"\)/;

function javaFormIds() {
  if (!fs.existsSync(FORM_DIR)) return [];
  const ids = [];
  for (const file of fs.readdirSync(FORM_DIR)) {
    if (!file.endsWith("Form.java")) continue;
    const source = fs.readFileSync(path.join(FORM_DIR, file), "utf8");
    const match = KEY_PATTERN.exec(source);
    if (match) ids.push(match[1]);
  }
  return ids;
}

test("com/arspaper/spell/form ディレクトリが読める(パスが壊れていないこと)", () => {
  assert.ok(fs.existsSync(FORM_DIR), `not found: ${FORM_DIR}`);
});

test("Java の form NamespacedKey と JS SPELL_FORMS の id 集合が完全一致する", () => {
  const javaIds = new Set(javaFormIds());
  assert.ok(javaIds.size > 0, "regex extracted zero form ids; pattern likely stale vs. Java source shape");

  const jsIds = new Set(SPELL_FORMS.map((f) => f.id));

  const missingFromJs = [...javaIds].filter((id) => !jsIds.has(id));
  const missingFromJava = [...jsIds].filter((id) => !javaIds.has(id));

  assert.deepEqual(missingFromJs, [], `form id(s) present in Java but missing from spell-form-vocabulary.js: ${missingFromJs.join(", ")}`);
  assert.deepEqual(missingFromJava, [], `form id(s) present in spell-form-vocabulary.js but missing from Java: ${missingFromJava.join(", ")}`);
});

test("正規表現が現行Javaの並びからサンプル抽出できる(self=self, projectile=projectile)", () => {
  const ids = javaFormIds();
  assert.ok(ids.includes("self"));
  assert.ok(ids.includes("projectile"));
  assert.ok(ids.includes("beam"));
});
