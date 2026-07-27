"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

test("mainhand-buffs uses an independent mainhand-multipliers store", () => {
  const source = fs.readFileSync(
    path.join(__dirname, "..", "public", "js", "tf-skilltree.js"),
    "utf8"
  );
  assert.match(
    source,
    /buffsKey === "mainhand-buffs" \? "mainhand-multipliers" : "multipliers"/
  );
  assert.match(
    source,
    /supportsMultipliers = buffsKey === "buffs" \|\| buffsKey === "mainhand-buffs"/
  );
});
