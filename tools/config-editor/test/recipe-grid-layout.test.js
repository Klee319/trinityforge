"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

test("catalog recipe grids use equal fixed rows and columns, never auto-width columns", () => {
  const forms = fs.readFileSync(path.join(__dirname, "..", "public", "js", "forms.js"), "utf8");
  assert.doesNotMatch(forms, /grid-template-columns:\s*repeat\(\$\{n\}, auto\)/);
  assert.match(forms,
    /grid-template-columns:\s*repeat\(\$\{n\}, 48px\);\s*grid-template-rows:\s*repeat\(\$\{n\}, 48px\)/);
});
