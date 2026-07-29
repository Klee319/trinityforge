"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");
const { buildUpdatedData, extractConstants, validateConstants } = require("../lib/constants");

test("出荷combat設定の数値・真偽値はすべて共通変数画面へ出る", () => {
  const root = path.resolve(__dirname, "..", "..", "..");
  const damage = YAML.parse(fs.readFileSync(path.join(root, "TrinityForge/src/main/resources/combat/damage.yml"), "utf8"));
  const combatLevel = YAML.parse(fs.readFileSync(path.join(root, "TrinityForge/src/main/resources/progression/combat-level.yml"), "utf8"));
  const { fields, pillars } = extractConstants(damage, combatLevel);

  assert.deepEqual(Object.fromEntries([
    "physical.base-coefficient", "physical.min-component-damage",
    "melee-charge.enabled", "melee-charge.min-multiplier", "melee-charge.exponent",
    "attack-speed.min-effective", "attack-speed.reconcile-interval-ticks",
    "magical.base-coefficient", "magical.min-component-damage", "magical.scale-with-combat-level",
    "weapon-base-formula.enabled", "weapon-base-formula.a", "weapon-base-formula.b",
    "level-scaling.per-level", "defense.max-mitigation-rate", "defense.max-dodge-chance",
    "defense.max-crit-reduction",
    "defense.min-rate", "defense.max-rate", "defense.min-flat", "defense.max-flat",
    "defense.enchant-protection-scale",
    "vanilla-armor.defense-rate-per-point", "vanilla-armor.defense-rate-max",
    "vanilla-armor.armor-strength-per-point", "bleed.tick-interval-ticks", "bleed.ticks",
    "aoe.hit-players", "curve.scale", "curve.min-level", "curve.max-level", "cache.ttl-seconds"
  ].map((key) => [key, fields[key]])), {
    "physical.base-coefficient": 1, "physical.min-component-damage": 1,
    "melee-charge.enabled": true, "melee-charge.min-multiplier": 0.2, "melee-charge.exponent": 2.0,
    "attack-speed.min-effective": 0.1, "attack-speed.reconcile-interval-ticks": 10,
    "magical.base-coefficient": 1, "magical.min-component-damage": 1,
    "magical.scale-with-combat-level": true,
    "weapon-base-formula.enabled": false, "weapon-base-formula.a": 2, "weapon-base-formula.b": 100,
    "level-scaling.per-level": 0.01, "defense.max-mitigation-rate": 0.9, "defense.max-dodge-chance": 0.9,
    "defense.max-crit-reduction": 1,
    "defense.min-rate": 0, "defense.max-rate": 1, "defense.min-flat": 0, "defense.max-flat": 1000000,
    "defense.enchant-protection-scale": 0.5,
    "vanilla-armor.defense-rate-per-point": 0.015, "vanilla-armor.defense-rate-max": 0.8,
    "vanilla-armor.armor-strength-per-point": 0, "bleed.tick-interval-ticks": 20, "bleed.ticks": 5,
    "aoe.hit-players": false, "curve.scale": 1, "curve.min-level": 0, "curve.max-level": 100,
    "cache.ttl-seconds": 3
  });
  assert.deepEqual(pillars, [
    { top: 1, divisor: 1 }, { top: 2, divisor: 1.5 },
    { top: 3, divisor: 2.1 }, { top: 4, divisor: 2.8 }
  ]);
});

test("2026-07-30 装備耐久ペナルティ: 出荷値が共通変数に出て、編集値がロスレスに保存される", () => {
  const root = path.resolve(__dirname, "..", "..", "..");
  const damage = YAML.parse(fs.readFileSync(path.join(root, "TrinityForge/src/main/resources/combat/damage.yml"), "utf8"));
  const { fields } = extractConstants(damage, {});

  // 出荷既定: ダンジョン限定 / 被弾0.1%(最低1) / 死亡10%
  assert.equal(fields["durability.dungeon-only"], true);
  assert.equal(fields["durability.on-hit.percent-of-max"], 0.001);
  assert.equal(fields["durability.on-hit.min-damage"], 1);
  assert.equal(fields["durability.on-death.percent-of-max"], 0.1);
  assert.equal(fields["durability.on-death.include-hands"], true);

  const payload = { fields: {
    "durability.dungeon-only": false,
    "durability.on-death.percent-of-max": 0.25,
    "durability.on-hit.include-offhand": false
  } };
  assert.deepEqual(validateConstants(payload), []);
  const updated = buildUpdatedData(payload, damage, {});
  assert.equal(updated.damage.durability["dungeon-only"], false);
  assert.equal(updated.damage.durability["on-death"]["percent-of-max"], 0.25);
  assert.equal(updated.damage.durability["on-hit"]["include-offhand"], false);
  // 触っていない同節のキーは温存される。
  assert.equal(updated.damage.durability["on-hit"]["percent-of-max"], 0.001);
  assert.equal(updated.damage.durability["prevent-break"], true);

  // 範囲外は弾く(割合は 0..1、下限量は整数)。
  assert.deepEqual(validateConstants({ fields: { "durability.on-death.percent-of-max": 1.5 } }),
    ["durability.on-death.percent-of-max: 1以下である必要があります"]);
  assert.deepEqual(validateConstants({ fields: { "durability.on-hit.min-damage": 1.5 } }),
    ["durability.on-hit.min-damage: 整数である必要があります"]);
});

test("defense.max-dodge-chance is retained and persisted", () => {
  const updated = buildUpdatedData(
    { fields: { "defense.max-dodge-chance": 0.25 } },
    { defense: { "max-dodge-chance": 0.9 } },
    {}
  );
  assert.equal(updated.damage.defense["max-dodge-chance"], 0.25);
  assert.equal(extractConstants(updated.damage, {}).fields["defense.max-dodge-chance"], 0.25);
  assert.deepEqual(validateConstants({ fields: { "defense.max-dodge-chance": 1.1 } }),
    ["defense.max-dodge-chance: 1以下である必要があります"]);
});

test("物理・魔法の下限クランプは負値を許容して保存する", () => {
  const payload = { fields: {
    "physical.min-component-damage": -50000,
    "magical.min-component-damage": -75000
  } };
  assert.deepEqual(validateConstants(payload), []);
  const updated = buildUpdatedData(payload, {}, {});
  assert.equal(updated.damage.physical["min-component-damage"], -50000);
  assert.equal(updated.damage.magical["min-component-damage"], -75000);
});

test("melee-charge/attack-speed/enchant-protection-scale を保存しても他キーはロスレスに温存される", () => {
  const damage = {
    pvp: { enabled: true, "damage-multiplier": 0.5, "max-damage-percent-of-max-health": 0.15 },
    "vanilla-armor": { "defense-rate-per-point": 0.015, "defense-rate-max": 0.8, "armor-strength-per-point": 0 },
    bleed: { "tick-interval-ticks": 20, ticks: 5 },
    "melee-charge": { enabled: true, "min-multiplier": 0.2, exponent: 2.0 },
    "attack-speed": { "min-effective": 0.1, "reconcile-interval-ticks": 10 },
    defense: { "enchant-protection-scale": 0.5 }
  };
  const payload = { fields: {
    "melee-charge.enabled": false,
    "melee-charge.min-multiplier": 0.3,
    "melee-charge.exponent": 3.0,
    "attack-speed.min-effective": 0.2,
    "attack-speed.reconcile-interval-ticks": 20,
    "defense.enchant-protection-scale": 0.75
  } };
  assert.deepEqual(validateConstants(payload), []);
  const updated = buildUpdatedData(payload, damage, {});

  assert.equal(updated.damage["melee-charge"].enabled, false);
  assert.equal(updated.damage["melee-charge"]["min-multiplier"], 0.3);
  assert.equal(updated.damage["melee-charge"].exponent, 3.0);
  assert.equal(updated.damage["attack-speed"]["min-effective"], 0.2);
  assert.equal(updated.damage["attack-speed"]["reconcile-interval-ticks"], 20);
  assert.equal(updated.damage.defense["enchant-protection-scale"], 0.75);

  // 他キー(pvp/vanilla-armor/bleed)はこのフォームが触れていないので温存される。
  assert.deepEqual(updated.damage.pvp, damage.pvp);
  assert.deepEqual(updated.damage["vanilla-armor"], damage["vanilla-armor"]);
  assert.deepEqual(updated.damage.bleed, damage.bleed);
});

test("新設6キーのバリデーション: int小数と範囲外は弾く", () => {
  assert.deepEqual(
    validateConstants({ fields: { "attack-speed.reconcile-interval-ticks": 10.5 } }),
    ["attack-speed.reconcile-interval-ticks: 整数である必要があります"]
  );
  assert.deepEqual(
    validateConstants({ fields: { "melee-charge.min-multiplier": 1.5 } }),
    ["melee-charge.min-multiplier: 1以下である必要があります"]
  );
  assert.deepEqual(
    validateConstants({ fields: { "melee-charge.exponent": 0 } }),
    ["melee-charge.exponent: 0.01以上の値が必要です"]
  );
  assert.deepEqual(
    validateConstants({ fields: { "attack-speed.min-effective": 5 } }),
    ["attack-speed.min-effective: 4以下である必要があります"]
  );
  assert.deepEqual(
    validateConstants({ fields: { "attack-speed.reconcile-interval-ticks": 0 } }),
    ["attack-speed.reconcile-interval-ticks: 1以上の値が必要です"]
  );
  // enchant-protection-scale は Java 側が[0,10]でクランプするだけで上限1.0ではない(バニラ超も許容)。
  assert.deepEqual(validateConstants({ fields: { "defense.enchant-protection-scale": 2.5 } }), []);
  assert.deepEqual(
    validateConstants({ fields: { "defense.enchant-protection-scale": 10.1 } }),
    ["defense.enchant-protection-scale: 10以下である必要があります"]
  );
  assert.deepEqual(
    validateConstants({ fields: { "defense.enchant-protection-scale": -0.1 } }),
    ["defense.enchant-protection-scale: 0以上の値が必要です"]
  );
});

test("撤去した attack/defense stat-key はもう共通変数に現れない", () => {
  // 恒等マップのためハードコード化(2026-07-24)。editor の FIELD_SPECS から除外済みで、
  // extractConstants は該当キーを surface せず、既存 damage.yml の値は保存時に温存される。
  const damage = {
    "attack-stat-keys": { "crit-chance": "crit-chance" },
    "defense-stat-keys": { "phys-flat-defense": "phys-flat-defense" }
  };
  const { fields } = extractConstants(damage, {});
  assert.equal("attack-stat-keys.crit-chance" in fields, false);
  assert.equal("defense-stat-keys.phys-flat-defense" in fields, false);
  // 他の定数を保存しても damage.yml の stat-key セクションは deep clone で温存される。
  const updated = buildUpdatedData({ fields: { "defense.max-dodge-chance": 0.5 } }, damage, {});
  assert.equal(updated.damage["attack-stat-keys"]["crit-chance"], "crit-chance");
  assert.equal(updated.damage["defense-stat-keys"]["phys-flat-defense"], "phys-flat-defense");
});
