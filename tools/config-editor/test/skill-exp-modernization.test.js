"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const ROOT = path.resolve(__dirname, "..", "..", "..");
const TF_RESOURCES = path.join(ROOT, "TrinityForge", "src", "main", "resources");

function load(relativePath) {
  return YAML.parse(fs.readFileSync(path.join(TF_RESOURCES, relativePath), "utf8"));
}

test("EXP設定から今回廃止したlegacyキーがすべて消えている", () => {
  const skillExp = load(path.join("stats", "skill-exp.yml"));
  assert.equal(Object.hasOwn(skillExp["ars-magic"] || {}, "exp-per-cast"), false);
  assert.equal(Object.hasOwn(skillExp["ars-magic"] || {}, "exp-per-mana"), false);
  for (const key of [
    "exp-per-hit", "mode", "damage-scale", "mob-level-scale",
    "same-target-cooldown-seconds", "by-skill"
  ]) {
    assert.equal(Object.hasOwn(skillExp.combat || {}, key), false, `combat.${key} が残っている`);
  }

  const forbiddenByFile = {
    "archery_progression.yml": ["legacy", "daily_limit", "is_chunk_nerfed"],
    "heavy_armor_progression.yml": ["legacy", "exp_second_piece", "daily_limit"],
    "light_armor_progression.yml": ["legacy", "exp_second_piece", "daily_limit"],
    "heavy_weapons_progression.yml": ["legacy", "exp_per_damage", "exp_enemies_nerfed"],
    "light_weapons_progression.yml": ["legacy", "exp_per_damage", "exp_enemies_nerfed"],
    "mining_progression.yml": ["legacy", "exp_per_break"],
    "smithing_progression.yml": [
      "legacy",
      "durability_tools_exp_multiplier_stack",
      "durability_tools_exp_multiplier_maximum",
      "durability_armors_exp_multiplier_stack",
      "durability_armors_exp_multiplier_maximum"
    ]
  };
  const base = path.join(TF_RESOURCES, "skills", "base");
  for (const file of fs.readdirSync(base).filter((name) => name.endsWith("_progression.yml"))) {
    const exp = YAML.parse(fs.readFileSync(path.join(base, file), "utf8")).experience || {};
    assert.equal(Object.hasOwn(exp, "legacy"), false, `${file}: experience.legacy が残っている`);
    for (const key of forbiddenByFile[file] || []) {
      assert.equal(Object.hasOwn(exp, key), false, `${file}: experience.${key} が残っている`);
    }
  }
});

// 2026-07-31: 50 -> 51。SKELETON_HORSE を Lv45/65/85 帯の mobs: へ追加した
// (骸馬の骨ドロップを配線するため。帯の mobs: がゲートなので、居ないと add-drops が空振りする)。
// このテストは同時に「実は9種が5つの倍率表すべてから欠けていた」ことも掘り出した
// (BEE/GOAT/LLAMA/TRADER_LLAMA/PANDA/WOLF/IRON_GOLEM = no-skill-exp-mobs なのに 0 が書かれておらず、
//  DOLPHIN/POLAR_BEAR は帯に居るのに倍率が無く unlisted-entity-multiplier: 0 で無言の0扱い)。
// 全表を埋めて解消済み。
test("敵別討伐EXP倍率はmob-level-table記載51種を漏れなく持つ", () => {
  const table = load(path.join("combat", "mob-level-table.yml"));
  const expected = new Set(table["no-skill-exp-mobs"] || []);
  for (const tier of table.tiers || []) {
    for (const mob of tier.mobs || []) expected.add(mob);
  }
  assert.equal(expected.size, 51, "正典mob-level-tableの対象数が変わった場合は倍率表も再確認する");

  const skillExp = load(path.join("stats", "skill-exp.yml"));
  for (const section of ["combat", "ars-magic"]) {
    const actual = new Set(Object.keys(skillExp[section]["kill-exp"]["entity-type-multipliers"] || {}));
    assert.deepEqual([...actual].sort(), [...expected].sort(), `${section} の敵倍率表が正典と不一致`);
  }
  for (const file of [
    "archery_progression.yml",
    "heavy_armor_progression.yml",
    "light_armor_progression.yml"
  ]) {
    const exp = load(path.join("skills", "base", file)).experience || {};
    const actual = new Set(Object.keys(exp.entity_exp_multipliers || {}));
    assert.deepEqual([...actual].sort(), [...expected].sort(), `${file} の敵倍率表が正典と不一致`);
  }

  for (const mob of table["no-skill-exp-mobs"] || []) {
    assert.equal(skillExp.combat["kill-exp"]["entity-type-multipliers"][mob], 0,
      `${mob} は戦闘スキルEXP無効なので倍率0である必要がある`);
    assert.equal(skillExp["ars-magic"]["kill-exp"]["entity-type-multipliers"][mob], 0,
      `${mob} はArs魔法EXPも倍率0である必要がある`);
  }
});
