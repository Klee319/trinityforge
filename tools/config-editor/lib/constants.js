"use strict";

// 共通変数(戦闘定数)の読み書き。複数YAML(combat/damage.yml, progression/combat-level.yml)に
// またがる横断定数を、フィールドごとに (ファイル, キーパス) で対応付けて集約取得/部分更新する。
//
// 部分更新方針: 既存データを deep clone し、指定パスのみ上書きする(入力を破壊しない)。
// これにより editor が扱わないキー(旧 attack-stat-keys / defense-stat-keys 等)は保持される。
// (ヘッダコメントの保持と本文途中コメントの消失は yamlio.serializeConfig の既存仕様に従う。)

// 各定数ファイル: registry と同じ base(=tool-config.json の basePaths キー) + rel で解決する。
const CONSTANT_SOURCES = Object.freeze({
  damage: { base: "trinityforge", rel: "combat/damage.yml" },
  combatLevel: { base: "trinityforge", rel: "progression/combat-level.yml" }
});

// スカラー定数のフィールド定義。
// - id   : クライアントとの受け渡しキー (ドット区切り、UI表示にも使う論理ID)
// - file : "damage" | "combatLevel"
// - path : そのファイル内のキーパス
// - kind : "number" | "int" | "boolean"
// - min/max: 数値の許容範囲 (任意)。minExclusive で下限を厳密不等号にする。
// - def  : ファイルに存在しない場合の既定値 (集約取得のフォールバック)
const FIELD_SPECS = Object.freeze([
  // ---- combat/damage.yml ----
  { id: "physical.base-coefficient", file: "damage", path: ["physical", "base-coefficient"], kind: "number", min: 0, def: 1.0 },
  { id: "physical.min-component-damage", file: "damage", path: ["physical", "min-component-damage"], kind: "number", min: -1000000, max: 1000000, def: 1.0 },
  // B2: バニラのチャージ攻撃(クールダウン中の連打減衰)をTFの近接プレイヤー攻撃に再導入する設定。
  // min-multiplier/exponent は Java 側 (CombatDamageConfig#meleeChargeMinMultiplier/-Exponent) が
  // それぞれ [0,1] / [0.01,100] にクランプする。
  // 【2026-08-01】バランス調整(要件1a)で出荷既定を 0.2/2.0(バニラ相当)から 0.1/1.6 へ変更した
  // (より顕著な連打ペナルティ + よりなだらかなカーブ立ち上がり。damage.yml 側コメント参照)。
  { id: "melee-charge.enabled", file: "damage", path: ["melee-charge", "enabled"], kind: "boolean", def: true },
  { id: "melee-charge.min-multiplier", file: "damage", path: ["melee-charge", "min-multiplier"], kind: "number", min: 0, max: 1, def: 0.1 },
  { id: "melee-charge.exponent", file: "damage", path: ["melee-charge", "exponent"], kind: "number", min: 0.01, max: 100, def: 1.6 },
  // 2026-07-25: attack-speed(絶対値)+attack-speed-bonus(割合)合成後の最終実効速度クランプ関連。
  // min-effective は Java 側 CombatDamageConfig で [0.01,4.0] にクランプ(既定0.1)。
  // reconcile-interval-ticks は PerkAttributeApplier の装備フィンガープリント再照合周期(tick)、[1,1200]。
  { id: "attack-speed.min-effective", file: "damage", path: ["attack-speed", "min-effective"], kind: "number", min: 0.01, max: 4.0, def: 0.1 },
  { id: "attack-speed.reconcile-interval-ticks", file: "damage", path: ["attack-speed", "reconcile-interval-ticks"], kind: "int", min: 1, max: 1200, def: 10 },
  { id: "magical.base-coefficient", file: "damage", path: ["magical", "base-coefficient"], kind: "number", min: 0, def: 1.0 },
  { id: "magical.min-component-damage", file: "damage", path: ["magical", "min-component-damage"], kind: "number", min: -1000000, max: 1000000, def: 1.0 },
  { id: "magical.scale-with-combat-level", file: "damage", path: ["magical", "scale-with-combat-level"], kind: "boolean", def: true },
  // 2026-07-31 D6(魔法ダメージに杖の攻撃力が乗らない): 杖(触媒)の attack-power を魔法の
  // 基礎ダメージへ加算するときの係数。Java 側 CombatDamageConfig#magicalAttackPowerScale が
  // [0,10] にクランプする(既定 1.0 = 仕様どおり100%加算。0 で杖の攻撃力を魔法から外せる)。
  { id: "magical.attack-power-scale", file: "damage", path: ["magical", "attack-power-scale"], kind: "number", min: 0, max: 10, def: 1.0 },
  { id: "weapon-base-formula.enabled", file: "damage", path: ["weapon-base-formula", "enabled"], kind: "boolean", def: true },
  { id: "weapon-base-formula.a", file: "damage", path: ["weapon-base-formula", "a"], kind: "number", min: 0, def: 2.0 },
  { id: "weapon-base-formula.b", file: "damage", path: ["weapon-base-formula", "b"], kind: "number", min: 0, minExclusive: true, def: 100.0 },
  { id: "level-scaling.per-level", file: "damage", path: ["level-scaling", "per-level"], kind: "number", min: 0, def: 0.01 },
  { id: "defense.max-mitigation-rate", file: "damage", path: ["defense", "max-mitigation-rate"], kind: "number", min: 0, max: 1, def: 0.9 },
  { id: "defense.max-dodge-chance", file: "damage", path: ["defense", "max-dodge-chance"], kind: "number", min: 0, max: 1, def: 0.9 },
  { id: "defense.max-crit-reduction", file: "damage", path: ["defense", "max-crit-reduction"], kind: "number", min: 0, max: 1, def: 1.0 },
  // 防御クランプ (旧: 戦闘ダメージタブの「防御クランプ」セクション)。負にすると被ダメ増幅側にも振れる想定なので min は制約しない。
  { id: "defense.min-rate", file: "damage", path: ["defense", "min-rate"], kind: "number", def: 0.0 },
  { id: "defense.max-rate", file: "damage", path: ["defense", "max-rate"], kind: "number", def: 1.0 },
  { id: "defense.min-flat", file: "damage", path: ["defense", "min-flat"], kind: "number", def: 0.0 },
  { id: "defense.max-flat", file: "damage", path: ["defense", "max-flat"], kind: "number", def: 1000000.0 },
  // 防護エンチャント(Protection系)の再導出軽減率に掛ける倍率。既定0.5は意図的な調整値:
  // 1.0(バニラ準拠、防護IVフルセット64%軽減)だと defense.max-mitigation-rate(0.9)の枠を
  // このエンチャント1種だけで71%消費し、TF自前の防具ステが無意味になるため半分に絞っている
  // (根拠: damage.yml本文コメント / DefenseEnchantmentBridge javadoc / CombatDamageConfig L242-250)。
  // Java側スキーマは [0,10] でクランプ(CombatDamageConfig.java L125)しており max-mitigation-rate と
  // 違い 1.0 上限ではない(バニラ超の軽減も許容する設計)ため、editor側もそれに合わせて上限10。
  { id: "defense.enchant-protection-scale", file: "damage", path: ["defense", "enchant-protection-scale"], kind: "number", min: 0, max: 10, def: 0.5 },
  { id: "vanilla-armor.defense-rate-per-point", file: "damage", path: ["vanilla-armor", "defense-rate-per-point"], kind: "number", min: 0, def: 0.04 },
  { id: "vanilla-armor.defense-rate-max", file: "damage", path: ["vanilla-armor", "defense-rate-max"], kind: "number", min: 0, max: 1, def: 0.8 },
  { id: "vanilla-armor.armor-strength-per-point", file: "damage", path: ["vanilla-armor", "armor-strength-per-point"], kind: "number", min: 0, def: 0.0 },
  { id: "bleed.tick-interval-ticks", file: "damage", path: ["bleed", "tick-interval-ticks"], kind: "int", min: 1, def: 20 },
  { id: "bleed.ticks", file: "damage", path: ["bleed", "ticks"], kind: "int", min: 0, def: 5 },
  // 攻撃範囲(AoE) 全体設定。武器個別の aoe-radius/aoe-damage-rate/aoe-max-targets は item-stats 側。
  { id: "aoe.hit-players", file: "damage", path: ["aoe", "hit-players"], kind: "boolean", def: false },
  // 2026-07-27 PvP(player→player)専用の抑制。max-damage-percent-of-max-health が肝で、
  // 倍率と違い攻撃カーブのスケールに依存しないため「調整漏れで即死ゲーに戻る」ことがない。
  { id: "pvp.enabled", file: "damage", path: ["pvp", "enabled"], kind: "boolean", def: true },
  { id: "pvp.damage-multiplier", file: "damage", path: ["pvp", "damage-multiplier"], kind: "number", min: 0, def: 0.5 },
  { id: "pvp.max-damage-percent-of-max-health", file: "damage", path: ["pvp", "max-damage-percent-of-max-health"], kind: "number", min: 0, def: 0.15 },
  // 2026-07-28 日光炎上: バニラの1.0固定ではTFのモブHP(Lv0で400)に対して無意味だったので、
  // 日光で燃えている間の1発だけを最大HP割合へ置き換える。
  // 対象EntityType一覧(sunlight-burn.mobs)はリスト型のためeditorには出していない
  // (未宣言キーは buildUpdatedData の deep clone で温存されるので、保存で消えることはない)。
  { id: "sunlight-burn.enabled", file: "damage", path: ["sunlight-burn", "enabled"], kind: "boolean", def: true },
  { id: "sunlight-burn.damage-percent-of-max-health", file: "damage", path: ["sunlight-burn", "damage-percent-of-max-health"], kind: "number", min: 0, max: 1, def: 0.10 },
  // 2026-07-28 序盤モブ火力の緩和(mob-types の attack-power 指数カーブには触らない後掛け倍率)。
  { id: "early-level-attack.enabled", file: "damage", path: ["early-level-attack", "enabled"], kind: "boolean", def: true },
  { id: "early-level-attack.until-level", file: "damage", path: ["early-level-attack", "until-level"], kind: "int", min: 0, max: 1000, def: 10 },
  { id: "early-level-attack.level-0-multiplier", file: "damage", path: ["early-level-attack", "level-0-multiplier"], kind: "number", min: 0, max: 1, def: 0.7 },
  // 2026-07-30 装備の耐久ペナルティ。EliteMobsのダンジョンは致死ダメージをキャンセルして
  // 「ダウン」へ移すため PlayerDeathEvent が発火せず、死亡ペナルティも致死の一撃分の
  // バニラ防具耐久消費も両方失われていた。min/max/def は CombatDamageConfig の SchemaField と一致。
  { id: "durability.dungeon-only", file: "damage", path: ["durability", "dungeon-only"], kind: "boolean", def: true },
  { id: "durability.respect-unbreaking", file: "damage", path: ["durability", "respect-unbreaking"], kind: "boolean", def: true },
  { id: "durability.prevent-break", file: "damage", path: ["durability", "prevent-break"], kind: "boolean", def: true },
  { id: "durability.on-hit.enabled", file: "damage", path: ["durability", "on-hit", "enabled"], kind: "boolean", def: true },
  { id: "durability.on-hit.percent-of-max", file: "damage", path: ["durability", "on-hit", "percent-of-max"], kind: "number", min: 0, max: 1, def: 0.001 },
  { id: "durability.on-hit.min-damage", file: "damage", path: ["durability", "on-hit", "min-damage"], kind: "int", min: 0, max: 10000, def: 1 },
  { id: "durability.on-hit.include-offhand", file: "damage", path: ["durability", "on-hit", "include-offhand"], kind: "boolean", def: true },
  { id: "durability.on-death.enabled", file: "damage", path: ["durability", "on-death", "enabled"], kind: "boolean", def: true },
  { id: "durability.on-death.percent-of-max", file: "damage", path: ["durability", "on-death", "percent-of-max"], kind: "number", min: 0, max: 1, def: 0.1 },
  { id: "durability.on-death.min-damage", file: "damage", path: ["durability", "on-death", "min-damage"], kind: "int", min: 0, max: 10000, def: 1 },
  { id: "durability.on-death.include-hands", file: "damage", path: ["durability", "on-death", "include-hands"], kind: "boolean", def: true },
  // 2026-08-09 レベル差による足きり。combat/mob-overrides.yml の level-cutoff から移設した
  // (旧実装はEliteMobsが刻印したダンジョンモブにしか効かず、野良モブが素通りしていた)。
  // min/max/def は CombatDamageConfig の SchemaField と一致させること。
  { id: "level-cutoff.over-level.threshold", file: "damage", path: ["level-cutoff", "over-level", "threshold"], kind: "int", min: -1, max: 10000, def: -1 },
  { id: "level-cutoff.over-level.exp-rate", file: "damage", path: ["level-cutoff", "over-level", "exp-rate"], kind: "number", min: -1, max: 1, def: 1 },
  { id: "level-cutoff.over-level.drop-rate", file: "damage", path: ["level-cutoff", "over-level", "drop-rate"], kind: "number", min: -1, max: 1, def: 1 },
  { id: "level-cutoff.under-level.item-threshold", file: "damage", path: ["level-cutoff", "under-level", "item-threshold"], kind: "int", min: -1, max: 10000, def: -1 },
  // attack-stat-keys.* / defense-stat-keys.* は editor から撤去(2026-07-24)し、2026-07-26 に
  // Java 側(CombatDamageConfig schema / damage.yml)からも撤去済み(CMB-31)。キー名は
  // AttackStatKeys / DefenseStatKeys の定数が単一の真実で、config からは改名できない。
  // 配備済みの古い damage.yml に該当セクションが残っていても、buildUpdatedData は deep clone で
  // 温存し、Java 側の ConfigSchema.resolve は宣言済みフィールドしか見ないので無害(警告も出ない)。
  // ---- progression/combat-level.yml ----
  { id: "curve.scale", file: "combatLevel", path: ["curve", "scale"], kind: "number", min: 0, def: 1.0 },
  { id: "curve.min-level", file: "combatLevel", path: ["curve", "min-level"], kind: "int", min: 0, def: 0 },
  { id: "curve.max-level", file: "combatLevel", path: ["curve", "max-level"], kind: "int", min: 0, def: 100 },
  { id: "cache.ttl-seconds", file: "combatLevel", path: ["cache", "ttl-seconds"], kind: "int", min: 0, max: 300, def: 3 }
]);

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function getPath(root, path) {
  let cur = root;
  for (const key of path) {
    if (!isPlainObject(cur) || !(key in cur)) return undefined;
    cur = cur[key];
  }
  return cur;
}

// clone 済みオブジェクトに対して破壊的に設定する (呼び出し側が deep clone を渡す前提)。
function setPath(root, path, value) {
  let cur = root;
  for (let i = 0; i < path.length - 1; i++) {
    const key = path[i];
    if (!isPlainObject(cur[key])) cur[key] = {};
    cur = cur[key];
  }
  cur[path[path.length - 1]] = value;
}

function deepClone(value) {
  return value === undefined || value === null ? {} : JSON.parse(JSON.stringify(value));
}

// 2ファイルのパース済みデータから、対象定数をまとめて抽出する。
function extractConstants(damageData, combatLevelData) {
  const data = { damage: damageData || {}, combatLevel: combatLevelData || {} };
  const fields = {};
  for (const spec of FIELD_SPECS) {
    const raw = getPath(data[spec.file], spec.path);
    fields[spec.id] = raw === undefined ? spec.def : raw;
  }

  const rawPillars = getPath(data.combatLevel, ["pillars"]);
  const pillars = Array.isArray(rawPillars)
    ? rawPillars.map((p) => ({ top: Number(p && p.top), divisor: Number(p && p.divisor) }))
    : [];

  const rawSkills = getPath(data.combatLevel, ["skills"]);
  const skills = isPlainObject(rawSkills) ? { ...rawSkills } : {};

  return { fields, pillars, skills };
}

// 受信 payload と現在データから、更新後の各ファイルデータ(新オブジェクト)を作る。
function buildUpdatedData(payload, damageData, combatLevelData) {
  const updated = { damage: deepClone(damageData), combatLevel: deepClone(combatLevelData) };
  const fields = (payload && payload.fields) || {};
  for (const spec of FIELD_SPECS) {
    if (!(spec.id in fields)) continue;
    const value = coerce(spec, fields[spec.id]);
    setPath(updated[spec.file], spec.path, value);
  }

  if (Array.isArray(payload && payload.pillars)) {
    updated.combatLevel.pillars = payload.pillars.map((p) => ({
      top: Math.trunc(Number(p.top)),
      divisor: Number(p.divisor)
    }));
  }
  if (isPlainObject(payload && payload.skills)) {
    const skills = {};
    for (const [name, weight] of Object.entries(payload.skills)) skills[name] = Number(weight);
    updated.combatLevel.skills = skills;
  }
  return updated;
}

function coerce(spec, value) {
  if (spec.kind === "boolean") return Boolean(value);
  if (spec.kind === "string") return String(value).trim();
  const n = Number(value);
  return spec.kind === "int" ? Math.trunc(n) : n;
}

// 保存前検証。エラーは配列で返す (空=OK)。
function validateConstants(payload) {
  const errors = [];
  if (!isPlainObject(payload)) {
    errors.push("constants はオブジェクトである必要があります");
    return errors;
  }
  const fields = payload.fields;
  if (fields !== undefined && !isPlainObject(fields)) {
    errors.push("constants.fields はオブジェクトである必要があります");
  } else if (isPlainObject(fields)) {
    for (const spec of FIELD_SPECS) {
      if (!(spec.id in fields)) continue;
      validateField(spec, fields[spec.id], errors);
    }
    const minLevel = Number(fields["curve.min-level"]);
    const maxLevel = Number(fields["curve.max-level"]);
    if (Number.isFinite(minLevel) && Number.isFinite(maxLevel) && minLevel > maxLevel) {
      errors.push(`curve.min-level(${minLevel}) <= curve.max-level(${maxLevel}) が必要です`);
    }
  }

  validatePillars(payload.pillars, errors);
  validateSkills(payload.skills, errors);
  return errors;
}

function validateField(spec, value, errors) {
  if (spec.kind === "boolean") {
    if (typeof value !== "boolean") errors.push(`${spec.id}: 真偽値である必要があります`);
    return;
  }
  if (spec.kind === "string") {
    if (typeof value !== "string" || !value.trim()) errors.push(`${spec.id}: 空でない文字列が必要です`);
    return;
  }
  const n = Number(value);
  if (!Number.isFinite(n)) {
    errors.push(`${spec.id}: 有限の数値である必要があります`);
    return;
  }
  if (spec.kind === "int" && !Number.isInteger(n)) {
    errors.push(`${spec.id}: 整数である必要があります`);
  }
  if (spec.min !== undefined) {
    if (spec.minExclusive ? n <= spec.min : n < spec.min) {
      errors.push(`${spec.id}: ${spec.min}${spec.minExclusive ? "より大きい" : "以上の"}値が必要です`);
    }
  }
  if (spec.max !== undefined && n > spec.max) {
    errors.push(`${spec.id}: ${spec.max}以下である必要があります`);
  }
}

function validatePillars(pillars, errors) {
  if (pillars === undefined) return;
  if (!Array.isArray(pillars)) {
    errors.push("pillars は配列である必要があります");
    return;
  }
  pillars.forEach((p, i) => {
    if (!isPlainObject(p)) {
      errors.push(`pillars[${i}]: {top, divisor} が必要です`);
      return;
    }
    const top = Number(p.top);
    const divisor = Number(p.divisor);
    if (!Number.isInteger(top) || top < 1) errors.push(`pillars[${i}].top: 1以上の整数が必要です`);
    if (!Number.isFinite(divisor) || divisor <= 0) errors.push(`pillars[${i}].divisor: 0より大きい数値が必要です`);
  });
}

function validateSkills(skills, errors) {
  if (skills === undefined) return;
  if (!isPlainObject(skills)) {
    errors.push("skills はマップである必要があります");
    return;
  }
  for (const [name, weight] of Object.entries(skills)) {
    if (!name || typeof name !== "string") errors.push("skills: スキル名は空でない文字列である必要があります");
    if (!Number.isFinite(Number(weight))) errors.push(`skills.${name}: 数値である必要があります`);
  }
}

module.exports = {
  CONSTANT_SOURCES,
  FIELD_SPECS,
  extractConstants,
  buildUpdatedData,
  validateConstants
};
