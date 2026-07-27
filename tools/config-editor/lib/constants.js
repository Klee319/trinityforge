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
  { id: "magical.base-coefficient", file: "damage", path: ["magical", "base-coefficient"], kind: "number", min: 0, def: 1.0 },
  { id: "magical.min-component-damage", file: "damage", path: ["magical", "min-component-damage"], kind: "number", min: -1000000, max: 1000000, def: 1.0 },
  { id: "magical.scale-with-combat-level", file: "damage", path: ["magical", "scale-with-combat-level"], kind: "boolean", def: true },
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
      errors.push(`${spec.id}: ${spec.min}${spec.minExclusive ? "より大きい" : "以上"}値が必要です`);
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
