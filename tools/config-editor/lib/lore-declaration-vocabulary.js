"use strict";

// JS mirror of the closed vocabulary for stats/lore.yml stats.<key>.trigger / limits (段階1/2/3).
// Java source of truth:
//   TrinityForge/src/main/java/com/trinityforge/stats/StatTriggerWhen.java
//   TrinityForge/src/main/java/com/trinityforge/stats/StatSourceScope.java
//   TrinityForge/src/main/java/com/trinityforge/stats/StatAppliesTo.java
//   TrinityForge/src/main/java/com/trinityforge/stats/StatStacking.java
// A drift test (test/lore-declaration-vocabulary-java-parity.test.js) asserts these arrays stay in
// sync with the Java enums by reading the Java source as text (same technique as
// gate-vocabulary-java-parity.test.js). Do not edit one side without the other.
//
// 段階3(/tf stats detail): 各 enum の日本語ラベルは Java の label() が正本。この *_LABELS は
// そのミラーで、値そのものを勝手に変えないこと(パリティテストがラベル文字列まで突き合わせる)。

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

const TRIGGER_WHEN_LABELS = {
  ON_MELEE_HIT: "近接攻撃時",
  ON_PROJECTILE_HIT: "飛び道具命中時",
  ON_ANY_HIT: "攻撃命中時(近接/飛び道具問わず)",
  ON_DAMAGE_TAKEN: "被弾時",
  ON_KILL: "撃破時",
  PASSIVE_ATTRIBUTE: "常時(バニラ属性へ直接反映)",
  PASSIVE: "常時(独自ロジックで常時適用)",
  ON_BLOCK_BREAK: "ブロック破壊時",
  ON_CRAFT: "クラフト時",
  ON_BREW: "醸造時",
  ON_ENCHANT: "エンチャント時",
  ON_FISH: "釣り時",
  ON_SMELT: "精錬時",
  ON_DISASSEMBLE: "解体時",
  ON_CONSUME: "飲食時",
  ON_SPELL_CAST: "魔法詠唱時",
};

const SOURCE_SCOPE = ["ALL", "MAINHAND_ONLY", "WORN_ARMOR_ONLY", "OFFHAND_OPT_IN", "NON_ITEM_ONLY"];

const SOURCE_SCOPE_LABELS = {
  ALL: "装備・パーク・アドオン等すべての合算元が対象",
  MAINHAND_ONLY: "メインハンドの装備のみが対象",
  WORN_ARMOR_ONLY: "装着中の防具のみが対象",
  OFFHAND_OPT_IN: "オフハンドも対象に含められる(任意)",
  NON_ITEM_ONLY: "アイテム以外の合算元のみが対象(パーク/アドオン等)",
};

const APPLIES_TO = ["PLAYER", "MOB"];

const APPLIES_TO_LABELS = {
  PLAYER: "プレイヤー",
  MOB: "モブ",
};

const STACKING = ["ADDITIVE", "MULTIPLICATIVE", "MAX_ONLY"];

const STACKING_LABELS = {
  ADDITIVE: "加算(複数ソースの値を合計する)",
  MULTIPLICATIVE: "乗算(複数ソースの値を掛け合わせる)",
  MAX_ONLY: "最大値のみ採用(複数ソースがあっても最大の1つだけ適用される)",
};

module.exports = {
  TRIGGER_WHEN,
  TRIGGER_WHEN_LABELS,
  SOURCE_SCOPE,
  SOURCE_SCOPE_LABELS,
  APPLIES_TO,
  APPLIES_TO_LABELS,
  STACKING,
  STACKING_LABELS,
};
