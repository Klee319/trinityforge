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
    // 2026-08-01 バランス調整(要件1a): 連打減衰を「より滑らかかつ顕著」にするため
    // 出荷既定を 0.2/2.0(バニラ相当) から 0.1/1.6 へ変更した。
    "melee-charge.enabled": true, "melee-charge.min-multiplier": 0.1, "melee-charge.exponent": 1.6,
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

test("2026-08-09 レベル差による足きり: damage.yml に移設され共通変数から編集できる", () => {
  const root = path.resolve(__dirname, "..", "..", "..");
  const damage = YAML.parse(fs.readFileSync(path.join(root, "TrinityForge/src/main/resources/combat/damage.yml"), "utf8"));
  const { fields } = extractConstants(damage, {});

  // over-level(低レベル狩り)の出荷既定は「足きり無し」。閾値 -1 = 無効、倍率 1.0 = 無干渉。
  assert.equal(fields["level-cutoff.over-level.threshold"], -1);
  assert.equal(fields["level-cutoff.over-level.exp-rate"], 1.0);
  assert.equal(fields["level-cutoff.over-level.drop-rate"], 1.0);
  // under-level(高レベルモブ狩り)は 2026-08-18(W-72) から出荷時点で有効。
  assert.equal(fields["level-cutoff.under-level.item-threshold"], 20);

  const payload = { fields: {
    "level-cutoff.over-level.threshold": 10,
    "level-cutoff.over-level.exp-rate": 0.25,
    "level-cutoff.over-level.drop-rate": -1,
    "level-cutoff.under-level.item-threshold": 20
  } };
  assert.deepEqual(validateConstants(payload), []);
  const updated = buildUpdatedData(payload, damage, {});
  assert.equal(updated.damage["level-cutoff"]["over-level"].threshold, 10);
  assert.equal(updated.damage["level-cutoff"]["over-level"]["exp-rate"], 0.25);
  // -1 は「完全に入手不可」を表す特別値なので min:-1 で通す必要がある。
  assert.equal(updated.damage["level-cutoff"]["over-level"]["drop-rate"], -1);
  assert.equal(updated.damage["level-cutoff"]["under-level"]["item-threshold"], 20);

  // 倍率は [-1, 1] の外を弾く(2.0 は「2倍もらえる」ではなく設定ミス)。
  assert.deepEqual(validateConstants({ fields: { "level-cutoff.over-level.exp-rate": 2.0 } }),
    ["level-cutoff.over-level.exp-rate: 1以下である必要があります"]);
  assert.deepEqual(validateConstants({ fields: { "level-cutoff.over-level.threshold": 1.5 } }),
    ["level-cutoff.over-level.threshold: 整数である必要があります"]);
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

test("2026-07-31 D6: magical.attack-power-scale が共通変数に出て、0も保存できる", () => {
  const root = path.resolve(__dirname, "..", "..", "..");
  const damage = YAML.parse(fs.readFileSync(path.join(root, "TrinityForge/src/main/resources/combat/damage.yml"), "utf8"));

  // 出荷既定 = 1(仕様どおり100%加算)。ここが editor 側の def とずれると
  // 「開いて保存しただけで杖の攻撃力が魔法から消える/倍になる」事故になる。
  assert.equal(extractConstants(damage, {}).fields["magical.attack-power-scale"], 1);

  // 0 は「杖の攻撃力を魔法から外す」正当な設定値なので通ること。
  assert.deepEqual(validateConstants({ fields: { "magical.attack-power-scale": 0 } }), []);
  const updated = buildUpdatedData({ fields: { "magical.attack-power-scale": 0 } }, damage, {});
  assert.equal(updated.damage.magical["attack-power-scale"], 0);
  // 同節の他キーは温存される。
  assert.equal(updated.damage.magical["scale-with-combat-level"], true);
  assert.equal(updated.damage.magical["base-coefficient"], 1);

  // Java 側スキーマ [0,10] と同じ範囲で弾く。
  assert.deepEqual(validateConstants({ fields: { "magical.attack-power-scale": 10.1 } }),
    ["magical.attack-power-scale: 10以下である必要があります"]);
  assert.deepEqual(validateConstants({ fields: { "magical.attack-power-scale": -0.1 } }),
    ["magical.attack-power-scale: 0以上の値が必要です"]);
});

test("2026-08-18 W-60: level-cutoff の逓減3キー(exp-decay-per-level/drop-decay-per-level/rate-floor)"
  + " が共通変数に出て、ロスレスに保存される", () => {
  // combat/damage.yml 本体への追加は別レーンの担当のため、ここでは実ファイルに依存せず
  // 未設定(=キー自体が無い旧damage.yml)を模した合成データで確認する。
  const damage = {
    "level-cutoff": {
      "over-level": { threshold: -1, "exp-rate": 1, "drop-rate": 1 },
      "under-level": { "item-threshold": -1 }
    }
  };
  const { fields } = extractConstants(damage, {});
  // 未設定は0(=逓減なし)にフォールバックする。ここが1や-1に化けると
  // 「配備前の旧damage.ymlを開いて保存しただけ」で逓減が勝手に発動する事故になる。
  assert.equal(fields["level-cutoff.over-level.exp-decay-per-level"], 0);
  assert.equal(fields["level-cutoff.over-level.drop-decay-per-level"], 0);
  assert.equal(fields["level-cutoff.over-level.rate-floor"], 0);

  const payload = { fields: {
    "level-cutoff.over-level.exp-decay-per-level": 0.05,
    "level-cutoff.over-level.drop-decay-per-level": 0.1,
    "level-cutoff.over-level.rate-floor": 0.2
  } };
  assert.deepEqual(validateConstants(payload), []);
  const updated = buildUpdatedData(payload, damage, {});
  assert.equal(updated.damage["level-cutoff"]["over-level"]["exp-decay-per-level"], 0.05);
  assert.equal(updated.damage["level-cutoff"]["over-level"]["drop-decay-per-level"], 0.1);
  assert.equal(updated.damage["level-cutoff"]["over-level"]["rate-floor"], 0.2);
  // 同節の既存キー(threshold/exp-rate/drop-rate)は温存される。
  assert.equal(updated.damage["level-cutoff"]["over-level"].threshold, -1);
  assert.equal(updated.damage["level-cutoff"]["over-level"]["exp-rate"], 1);
  assert.equal(updated.damage["level-cutoff"]["under-level"]["item-threshold"], -1);

  // 範囲外は弾く。exp-decay/drop-decay は [0,1]、rate-floor は [-1,1]。
  assert.deepEqual(validateConstants({ fields: { "level-cutoff.over-level.exp-decay-per-level": 1.5 } }),
    ["level-cutoff.over-level.exp-decay-per-level: 1以下である必要があります"]);
  assert.deepEqual(validateConstants({ fields: { "level-cutoff.over-level.exp-decay-per-level": -0.1 } }),
    ["level-cutoff.over-level.exp-decay-per-level: 0以上の値が必要です"]);
  assert.deepEqual(validateConstants({ fields: { "level-cutoff.over-level.drop-decay-per-level": 2 } }),
    ["level-cutoff.over-level.drop-decay-per-level: 1以下である必要があります"]);
  assert.deepEqual(validateConstants({ fields: { "level-cutoff.over-level.rate-floor": 1.1 } }),
    ["level-cutoff.over-level.rate-floor: 1以下である必要があります"]);
  assert.deepEqual(validateConstants({ fields: { "level-cutoff.over-level.rate-floor": -1.1 } }),
    ["level-cutoff.over-level.rate-floor: -1以上の値が必要です"]);
});

test("2026-08-18 W-72: under-level の5キーが共通変数に出て、未設定は旧挙動へフォールバックする", () => {
  // 対称化する前(=under-level が item-threshold 1本しか無かった頃)の damage.yml を模した合成データ。
  // ここでのフォールバック値が Java の SchemaField 既定とずれると、「旧damage.ymlを開いて保存しただけ」で
  // 意味が変わる(exp-rate が 0 に化ければ経験値が消え、drop-rate が 1 に化けば足きりが無効化する)。
  const damage = {
    "level-cutoff": {
      "over-level": { threshold: -1, "exp-rate": 1, "drop-rate": 1 },
      "under-level": { "item-threshold": 20 }
    }
  };
  const { fields } = extractConstants(damage, {});
  assert.equal(fields["level-cutoff.under-level.exp-rate"], 1);      // 経験値に触れない
  assert.equal(fields["level-cutoff.under-level.drop-rate"], -1);    // 発動したら追加ドロップ無し
  assert.equal(fields["level-cutoff.under-level.exp-decay-per-level"], 0);
  assert.equal(fields["level-cutoff.under-level.drop-decay-per-level"], 0);
  assert.equal(fields["level-cutoff.under-level.rate-floor"], 0);

  const payload = { fields: {
    "level-cutoff.under-level.exp-rate": 1,
    "level-cutoff.under-level.drop-rate": -1,
    "level-cutoff.under-level.exp-decay-per-level": 0.1,
    "level-cutoff.under-level.drop-decay-per-level": 0,
    "level-cutoff.under-level.rate-floor": 0
  } };
  assert.deepEqual(validateConstants(payload), []);
  const updated = buildUpdatedData(payload, damage, {});
  assert.equal(updated.damage["level-cutoff"]["under-level"]["exp-decay-per-level"], 0.1);
  assert.equal(updated.damage["level-cutoff"]["under-level"]["drop-rate"], -1);
  // 同節の既存キーは温存される。
  assert.equal(updated.damage["level-cutoff"]["under-level"]["item-threshold"], 20);
  assert.equal(updated.damage["level-cutoff"]["over-level"].threshold, -1);

  // 範囲: rate は [-1,1](-1 は「完全に入手不可」の特別値)、decay/floor は [0,1]。
  assert.deepEqual(validateConstants({ fields: { "level-cutoff.under-level.exp-rate": 2 } }),
    ["level-cutoff.under-level.exp-rate: 1以下である必要があります"]);
  assert.deepEqual(validateConstants({ fields: { "level-cutoff.under-level.drop-rate": -1.1 } }),
    ["level-cutoff.under-level.drop-rate: -1以上の値が必要です"]);
  assert.deepEqual(validateConstants({ fields: { "level-cutoff.under-level.exp-decay-per-level": -0.1 } }),
    ["level-cutoff.under-level.exp-decay-per-level: 0以上の値が必要です"]);
  assert.deepEqual(validateConstants({ fields: { "level-cutoff.under-level.rate-floor": 1.1 } }),
    ["level-cutoff.under-level.rate-floor: 1以下である必要があります"]);
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
