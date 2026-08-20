"use strict";

// stats/item-stats.yml の【弓／クロスボウ】専用リチューン (2026-08-19 / W-118 + W-119)。
//
// ■ なぜ要るか（実測で確かめた事実）
//  1) 出荷データの弓は「剣の attack-power × 1.3143」、クロスボウは「剣 × 0.9198」で全段が生成
//     されている。ただし copper_bow(Lv15) と golden_bow(Lv35) の 2 本だけがこの比から外れており
//     (それぞれ 0.94 / 0.936)、【copper_bow(Lv15, 112.8) が stone_bow(Lv5, 138) より弱い】という
//     階梯の逆転が実在した。過去の再校正が 2 本を取りこぼしている。W-118「弓の火力が渋い」の
//     低〜中レベル帯の実体はこれ。
//  2) 弓の1発は attack-power × 引き絞り量(0〜1) で決まる (CombatListener の PROJECTILE 分岐)。
//     クロスボウは Paper 1.21.11 で常に 1.0。1.21.11 に弓の引き絞り時間を動かす手段は無いので、
//     「時間あたりの火力」は attack-power でしか調整できない。
//  3) 住み分けが存在しなかった: 弓は攻撃力・会心率・命中・矢節約・移動速度・距離ボーナスの
//     すべてでクロスボウを上回り、クロスボウが勝つのは貫通と会心ダメージだけだった。
//     = クロスボウを選ぶ理由が無い。
//
// ■ 何をするか（ユーザー確定方針「弓＝手数と機動 / クロスボウ＝一撃と貫通」）
//  A) 階梯の逆転を直す: copper_bow / golden_bow を「剣 × 1.3143」へ戻す。
//  B) 火力の底上げ(W-118): 弓の attack-power を一律 ×1.35。剣の実効DPS
//     (attack-power × damage-modifier期待値 × 手数係数) と概ね並ぶ水準。
//  C) 住み分け(W-119): クロスボウの attack-power を ×2.22 にして【1発の重さを弓の約1.2倍】にし、
//     副次ステを弓＝手数/機動、クロスボウ＝一撃/貫通へ振り分ける。
//     発射間隔は弓 20tick / クロスボウ 25tick なので、1発を 1.2 倍にしても持続DPSは概ね同等
//     (会心込みの期待値で弓 : 弩 ≒ 1.00 : 1.00)。どちらかが上位互換にならないことが目的。
//
// ■ 副次ステは「既定値の差分シフト」で書き換える（固定代入ではない）
//  abyss_bow / abyss_crossbow のように既定より貫通が高い一点物があるため、
//  new = old + (新既定 - 旧既定) にして個体の味付けを保存する。
//
// 実行:  node tools/config-editor/scripts/retune-archery-ladder.js
// ⚠ 冪等ではない（倍率を掛ける処理なので二度流すと二重に掛かる）。再実行するなら git で戻してから。

const fs = require("node:fs");
const path = require("node:path");

const targetPath = path.resolve(__dirname, "..", "..", "..",
  "TrinityForge", "src", "main", "resources", "stats", "item-stats.yml");

/** 弓の attack-power 一律倍率(W-118 火力底上げ)。 */
const BOW_AP_FACTOR = 1.35;
/**
 * クロスボウの attack-power 一律倍率(W-119 一撃化)。
 * 旧比 クロスボウ/弓 = 0.9198/1.3143 = 0.6999 を、新比 1.15 へ持ち上げる:
 *   0.6999 × (2.22/1.35) = 1.1509  → 1発の期待ダメージ比は damage-modifier 差
 *   (弓 0.65→期待0.825 / 弩 0.72→期待0.86) を掛けて約 1.20 倍。
 */
const CROSSBOW_AP_FACTOR = 2.22;

/**
 * 階梯から外れていた2本の「剣 × 1.3143」正解値(倍率適用前)。
 * COPPER_SWORD の attack-power 120 / GOLDEN_SWORD の 595.7 から引いた。
 */
const LADDER_FIX = {
  "BOW#1093": 157.72, // copper_bow  現行 112.8 (stone_bow 138 より弱いという逆転)
  "BOW#1095": 782.93, // golden_bow  現行 557.76
};

/** 階梯補正の分母(補正前の fixed.attack-power)。スクリプト実行前の出荷値。 */
const FIXED_BASE = {
  "BOW#1093": 112.8,
  "BOW#1095": 557.76,
};

/** fixed セクションの副次ステ: 旧既定 → 新既定。差分で全エントリへシフトする。 */
const FIXED_SHIFTS = {
  BOW: {
    // 弓＝手数と機動: 当てる回数で稼ぐ。会心は「よく出るが軽い」、矢が減りにくく、動きながら撃てる。
    "crit-chance": [0.18, 0.3],
    "crit-damage": [0.72, 0.6],
    "penetration": [0.1, 0.05],
    "bow-accuracy": [0.07, 0.16],
    "distance-damage-bonus": [0.15, 0.06],
    "ammo-save-chance": [0.08, 0.22],
    "move-speed": [0.015, 0.035],
  },
  CROSSBOW: {
    // クロスボウ＝一撃と貫通: 1発が重く、硬い相手を抜き、遠距離ほど伸びる。動きと矢効率は捨てる。
    "crit-chance": [0.11, 0.08],
    "crit-damage": [0.85, 1.3],
    "penetration": [0.13, 0.3],
    "bow-accuracy": [0.04, 0.03],
    "arrow-velocity": [0.12, 0.26],
    "distance-damage-bonus": [0.08, 0.22],
    "ammo-save-chance": [0.04, 0.02],
    "move-speed": [0.008, 0.003],
  },
};

/** per-quality セクションの副次ステ: 品質で伸びる方向も住み分けに合わせて固定代入する。 */
const PER_QUALITY_SET = {
  BOW: { "crit-chance": 0.005, "crit-damage": 0.016, "penetration": 0.002 },
  CROSSBOW: { "crit-chance": 0.0015, "crit-damage": 0.032, "penetration": 0.01 },
};

const round = (x, digits) => {
  const f = 10 ** digits;
  return Math.round(x * f) / f;
};

/** 元の表記桁を壊さずに数値を書き戻す(整数は整数のまま、小数は最大4桁)。 */
function formatNumber(value) {
  const rounded = round(value, 4);
  return Number.isInteger(rounded) ? String(rounded) : String(rounded);
}

const lines = fs.readFileSync(targetPath, "utf8").split(/\r?\n/);

let currentKey = null;
let family = null; // "BOW" | "CROSSBOW" | null
let section = null; // "fixed" | "per-quality" | "random" | null
let randomStat = null; // random 配下の対象ステ名
let apFactor = 1;
const report = [];

for (let i = 0; i < lines.length; i++) {
  const line = lines[i];

  const itemMatch = /^ {2}([A-Z_]+(?:#\d+)?):\s*$/.exec(line);
  if (itemMatch) {
    currentKey = itemMatch[1];
    section = null;
    randomStat = null;
    const base = currentKey.split("#")[0];
    family = base === "BOW" || base === "CROSSBOW" ? base : null;
    if (family) {
      const familyFactor = family === "BOW" ? BOW_AP_FACTOR : CROSSBOW_AP_FACTOR;
      // 階梯補正は倍率の前段に掛ける(per-quality と random も同じ比率で連れて行く)。
      apFactor = familyFactor;
      report.push({ key: currentKey, family });
    }
    continue;
  }
  if (!family) continue;

  const sectionMatch = /^ {4}(fixed|per-quality|random):\s*$/.exec(line);
  if (sectionMatch) {
    section = sectionMatch[1];
    randomStat = null;
    continue;
  }
  if (/^ {4}\S/.test(line)) {
    // use-level-requirement など、セクション外のキーに入った
    section = null;
    randomStat = null;
    continue;
  }

  const statMatch = /^( {6})([a-z-]+):\s*(-?[\d.]+)\s*$/.exec(line);
  const randomHeadMatch = /^ {6}([a-z-]+):\s*$/.exec(line);
  const randomBoundMatch = /^( {8})(min|max):\s*(-?[\d.]+)\s*$/.exec(line);

  if (section === "random" && randomHeadMatch) {
    randomStat = randomHeadMatch[1];
    continue;
  }

  if (section === "random" && randomBoundMatch && randomStat === "attack-power") {
    const [, indent, bound, raw] = randomBoundMatch;
    const scaled = scaleAttackPower(currentKey, Number(raw), apFactor);
    lines[i] = `${indent}${bound}: ${formatNumber(scaled)}`;
    continue;
  }

  if (!statMatch) continue;
  const [, indent, stat, raw] = statMatch;
  const value = Number(raw);

  if (stat === "attack-power" && (section === "fixed" || section === "per-quality")) {
    const scaled = scaleAttackPower(currentKey, value, apFactor);
    lines[i] = `${indent}${stat}: ${formatNumber(scaled)}`;
    continue;
  }

  if (section === "fixed") {
    const shift = FIXED_SHIFTS[family][stat];
    if (shift) {
      const [oldDefault, newDefault] = shift;
      const shifted = Math.max(0, value + (newDefault - oldDefault));
      lines[i] = `${indent}${stat}: ${formatNumber(shifted)}`;
    }
    continue;
  }

  if (section === "per-quality") {
    const preset = PER_QUALITY_SET[family][stat];
    if (preset !== undefined) {
      lines[i] = `${indent}${stat}: ${formatNumber(preset)}`;
    }
  }
}

/**
 * attack-power の倍率適用。階梯から外れていた 2 本は、まず「剣 × 1.3143」の正解値へ
 * 引き戻す比率を掛けてから一律倍率を掛ける(per-quality / random も同じ比率で追随させる)。
 */
function scaleAttackPower(key, value, factor) {
  const fix = LADDER_FIX[key];
  if (fix === undefined) return value * factor;
  const current = FIXED_BASE[key];
  const ladderRatio = fix / current;
  return value * ladderRatio * factor;
}

fs.writeFileSync(targetPath, lines.join("\n"), "utf8");
console.log(`retuned ${report.length} archery entries in ${targetPath}`);
for (const r of report) console.log(`  ${r.family.padEnd(8)} ${r.key}`);
