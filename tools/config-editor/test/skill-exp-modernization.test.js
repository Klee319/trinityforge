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
    // N5(2026-07-31): 弓術EXPを討伐時ベース(skill-exp.yml の combat.kill-exp)へ統一したため、
    // per-hit 式専用だった係数は出荷ymlから消えている。効かないキーが再び生えるのを禁止する。
    "archery_progression.yml": [
      "legacy", "daily_limit", "is_chunk_nerfed",
      "bow_exp_base", "crossbow_exp_base", "damage_exp_bonus",
      "distance_exp_multiplier_base", "distance_exp_multiplier", "distance_limit",
      "infinity_multiplier", "spawner_spawned_multiplier", "max_health_limitation",
      "pvp_multiplier", "entity_exp_multipliers"
    ],
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

// 正典は combat/mob-level-table.yml の「no-skill-exp-mobs ∪ 全帯の mobs:」= 51種。
// 2026-07-31: 50 -> 51。SKELETON_HORSE を Lv45/65/85 帯の mobs: へ追加した
// (骸馬の骨ドロップを配線するため。帯の mobs: がゲートなので、居ないと add-drops が空振りする)。
//
// 2026-08-03 に契約を変更した。旧契約は「51種すべてを倍率表に持ち、no-skill-exp-mobs は 0 と書く」
// だったが、非敵対モブ(Paper の Enemy を実装しないモブ)は倍率表に載せない方針になったため、
// 「倍率表のキー集合 == 正典 − no-skill-exp-mobs」へ改めた。
// 検出力は落ちていない — 帯に居るのに no-skill-exp-mobs にも倍率表にも無いモブは、
// どちらの集合にも入らないので今も deepEqual で落ちる(unlisted-entity-multiplier: 0 による
// 「無言の0扱い」を見逃さない、というこのテストの元々の目的はそのまま)。
test("敵別討伐EXP倍率は正典51種からno-skill-exp-mobsを除いた集合と完全一致する", () => {
  const table = load(path.join("combat", "mob-level-table.yml"));
  const noSkillExp = new Set(table["no-skill-exp-mobs"] || []);
  const canon = new Set(noSkillExp);
  for (const tier of table.tiers || []) {
    for (const mob of tier.mobs || []) canon.add(mob);
  }
  assert.equal(canon.size, 51, "正典mob-level-tableの対象数が変わった場合は倍率表も再確認する");

  const expected = [...canon].filter((mob) => !noSkillExp.has(mob));
  assert.equal(expected.length, 41, "no-skill-exp-mobs の増減時は倍率表も同時に直すこと");

  const tables = [];
  const skillExp = load(path.join("stats", "skill-exp.yml"));
  for (const section of ["combat", "ars-magic"]) {
    tables.push([section, skillExp[section]["kill-exp"]["entity-type-multipliers"] || {}]);
  }
  // N5(2026-07-31): archery_progression.yml は entity_exp_multipliers ごと削除された
  // (弓術の敵種倍率は combat.kill-exp.entity-type-multipliers を近接と共有する)。
  // 独自の敵倍率表を持つのは防具の2ファイルだけ。
  for (const file of [
    "heavy_armor_progression.yml",
    "light_armor_progression.yml"
  ]) {
    tables.push([file, (load(path.join("skills", "base", file)).experience || {}).entity_exp_multipliers || {}]);
  }

  for (const [label, multipliers] of tables) {
    assert.deepEqual(Object.keys(multipliers).sort(), [...expected].sort(), `${label} の敵倍率表が正典と不一致`);
    for (const mob of noSkillExp) {
      assert.equal(Object.hasOwn(multipliers, mob), false,
        `${label}: ${mob} は非敵対/EXP無効モブなので倍率行ごと消すこと(0で書き残さない)`);
    }
  }
});
