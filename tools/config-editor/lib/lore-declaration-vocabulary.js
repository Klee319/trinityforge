"use strict";

// JS mirror of the closed vocabulary for stats/lore.yml stats.<key>.trigger / limits (段階1/2).
// Java source of truth:
//   TrinityForge/src/main/java/com/trinityforge/stats/StatTriggerWhen.java
//   TrinityForge/src/main/java/com/trinityforge/stats/StatSourceScope.java
//   TrinityForge/src/main/java/com/trinityforge/stats/StatAppliesTo.java
//   TrinityForge/src/main/java/com/trinityforge/stats/StatStacking.java
// A drift test (test/lore-declaration-vocabulary-java-parity.test.js) asserts these arrays stay in
// sync with the Java enums by reading the Java source as text (same technique as
// gate-vocabulary-java-parity.test.js). Do not edit one side without the other.

const TRIGGER_WHEN = [
  "ON_MELEE_HIT",
  "ON_PROJECTILE_HIT",
  "ON_ANY_HIT",
  "ON_DAMAGE_TAKEN",
  "ON_KILL",
  "PASSIVE_ATTRIBUTE",
  "PASSIVE",
  "ON_BLOCK_BREAK",
  "ON_CRAFT",
  "ON_BREW",
  "ON_ENCHANT",
  "ON_FISH",
  "ON_SMELT",
  "ON_DISASSEMBLE",
  "ON_CONSUME",
  "ON_SPELL_CAST",
];

const SOURCE_SCOPE = ["ALL", "MAINHAND_ONLY", "WORN_ARMOR_ONLY", "OFFHAND_OPT_IN", "NON_ITEM_ONLY"];

const APPLIES_TO = ["PLAYER", "MOB"];

const STACKING = ["ADDITIVE", "MULTIPLICATIVE", "MAX_ONLY"];

module.exports = { TRIGGER_WHEN, SOURCE_SCOPE, APPLIES_TO, STACKING };
