"use strict";

// ArsPaper スペル form (config.yml の form-cooldowns のキー) の固定語彙。
// 2026-07-25 config editor T1: form-cooldowns がタイポで無効なキーを作れてしまう自由入力だったため、
// gate-vocabulary.js の FEATURES / test/gate-vocabulary-java-parity.test.js に倣い、Java側正典
// (fork-handoff/arspaper/fork/src/main/java/com/arspaper/spell/form/*.java の
// `new NamespacedKey(plugin, "<id>")`) と id 集合が一致することを
// test/spell-form-vocabulary-java-parity.test.js で検証する。
const SPELL_FORMS = Object.freeze([
  { id: "projectile", label: "投射" },
  { id: "touch", label: "接触" },
  { id: "self", label: "自己" },
  { id: "underfoot", label: "足元" },
  { id: "overhead", label: "頭上" },
  { id: "burst", label: "爆発" },
  { id: "orbit", label: "周回" },
  { id: "wall", label: "壁" },
  { id: "linger", label: "残留" },
  { id: "beam", label: "ビーム" }
]);

module.exports = { SPELL_FORMS };
