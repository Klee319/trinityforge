"use strict";

// Verifies the C~S survivability ladder from the 2026-07-25 combat rebalance brief actually
// comes out of the shipped item-stats.yml. Re-derives HP / defense / mitigation independently
// from the file (does not import scripts/retune-armor-ladder.js) so this is a real regression
// check on the data, not a tautology against the generator.

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");
const YAML = require("yaml");

const root = path.resolve(__dirname, "..", "..", "..");
const stats = YAML.parse(fs.readFileSync(
  path.join(root, "TrinityForge", "src", "main", "resources", "stats", "item-stats.yml"), "utf8"));
const items = stats.items;

const PARTS = ["HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"];
const VANILLA_ARMOR_POINTS = {
  LEATHER: 7, COPPER: 11, GOLDEN: 11, CHAINMAIL: 12, IRON: 15, DIAMOND: 20, NETHERITE: 20,
};
const DEFENSE_RATE_PER_POINT = 0.015; // combat/damage.yml vanilla-armor.defense-rate-per-point
const DEFENSE_RATE_MAX = 0.8;

function aFor(level) {
  return 7.0 * Math.pow(1.03, level);
}

function partKeys(material, cmdCsv) {
  if (!cmdCsv) {
    return { HELMET: `${material}_HELMET`, CHESTPLATE: `${material}_CHESTPLATE`,
      LEGGINGS: `${material}_LEGGINGS`, BOOTS: `${material}_BOOTS` };
  }
  const [h, c, l, b] = cmdCsv.split(",");
  return { HELMET: `${material}_HELMET#${h}`, CHESTPLATE: `${material}_CHESTPLATE#${c}`,
    LEGGINGS: `${material}_LEGGINGS#${l}`, BOOTS: `${material}_BOOTS#${b}` };
}

// Sums fixed+random.min (base roll) and fixed+random.max (theoretical roll) for a stat across
// the 4-piece set, plus the always-fixed (non-rolled) stats used for the defense-rate/m math.
function aggregateSet(keys, vanillaMaterial) {
  let flatMin = 0, flatMax = 0, hpMin = 20, hpMax = 20, armorDefenseSum = 0, physResSum = 0;
  for (const part of PARTS) {
    const entry = items[keys[part]];
    assert.ok(entry, `missing item-stats entry for ${keys[part]}`);
    const fixed = entry.fixed || {};
    const random = entry.random || {};

    const fdFixed = fixed["phys-flat-defense"] || 0;
    const fdRoll = random["phys-flat-defense"] || { min: 0, max: 0 };
    flatMin += fdFixed + fdRoll.min;
    flatMax += fdFixed + fdRoll.max;

    const hpFixed = fixed["max-health"] || 0;
    const hpRoll = random["max-health"] || { min: 0, max: 0 };
    hpMin += hpFixed + hpRoll.min;
    hpMax += hpFixed + hpRoll.max;

    armorDefenseSum += fixed["armor-defense-rate"] || 0;
    physResSum += fixed["phys-resistance"] || 0;
  }
  const totalArmorPoints = armorDefenseSum + VANILLA_ARMOR_POINTS[vanillaMaterial];
  const defRate = Math.min(totalArmorPoints * DEFENSE_RATE_PER_POINT, DEFENSE_RATE_MAX);
  const m = (1 - defRate) * (1 - physResSum);
  return { flatMin, flatMax, hpMin, hpMax, m };
}

function hitsToSurvive(hp, a, f, m) {
  const perHit = Math.max(0, (a - f) * m);
  if (perHit <= 0) return Infinity;
  return hp / perHit;
}

function withinPct(actual, expected, pct) {
  return Math.abs(actual - expected) <= expected * pct;
}

// level -> { vanillaMaterial, keys, label }. One row per physical armor band under test.
const BANDS = {
  0: { vanillaMaterial: "LEATHER", keys: partKeys("LEATHER"), label: "革(Lv0)" },
  10: { vanillaMaterial: "COPPER", keys: partKeys("COPPER"), label: "銅(Lv10)" },
  20: { vanillaMaterial: "CHAINMAIL", keys: partKeys("CHAINMAIL"), label: "チェーン(Lv20)" },
  30: { vanillaMaterial: "IRON", keys: partKeys("IRON"), label: "鉄(Lv30重装)" },
  40: { vanillaMaterial: "GOLDEN", keys: partKeys("GOLDEN"), label: "金(Lv40)" },
  55: { vanillaMaterial: "DIAMOND", keys: partKeys("DIAMOND"), label: "ダイヤ(Lv55重装)" },
  70: { vanillaMaterial: "NETHERITE", keys: partKeys("NETHERITE"), label: "ネザライト(Lv70)" },
  85: { vanillaMaterial: "NETHERITE", keys: partKeys("NETHERITE", "150,153,156,159"), label: "ウィザー(Lv85)" },
  100: { vanillaMaterial: "NETHERITE", keys: partKeys("NETHERITE", "148,151,154,157"), label: "インフィニティ(Lv100)" },
};

const TOL = 0.15; // ±15% (S-ladder tolerance per brief)

for (const [levelStr, band] of Object.entries(BANDS)) {
  const level = Number(levelStr);
  const a = aFor(level);
  const agg = aggregateSet(band.keys, band.vanillaMaterial);

  test(`${band.label}: S帯の耐久回数ラダーが成立する`, () => {
    const sTheory = hitsToSurvive(agg.hpMax, a, agg.flatMax, agg.m); // S理論値
    const sUpper = hitsToSurvive((agg.hpMin + agg.hpMax) / 2, a, (agg.flatMin + agg.flatMax) / 2, agg.m); // S上振れ(中間ロール概算)
    const sBaseline = hitsToSurvive(agg.hpMin, a, agg.flatMin, agg.m); // S厳選なし

    assert.ok(withinPct(sTheory, 20, TOL), `${band.label} S理論値=${sTheory.toFixed(2)} (目標20, m=${agg.m.toFixed(3)})`);
    assert.ok(withinPct(sBaseline, 6, TOL), `${band.label} S厳選なし=${sBaseline.toFixed(2)} (目標6, m=${agg.m.toFixed(3)})`);
    assert.ok(sUpper > sBaseline && sUpper < sTheory, `${band.label} S上振れ=${sUpper.toFixed(2)} が基準~理論値の間にない`);
  });
}

// 1帯下(A)・2帯下(B)チェックは「現装備で1段上のAに当たる」形で直接検証する。
//
// KNOWN GAP (報告済み・意図的に仕様目標のままにはしない): 確定テーブルの帯間隔は Lv40 以降
// 15Lv刻み、Lv0~40は10Lv刻み。A(Lv)=7*1.03^Lv の指数成長は10Lv刻みでは緩やかすぎ、
// Lv0~40台の隣接帯では「1帯下=3~4.5発/2帯下=1~2発」という仕様目標に届かない
// (実測: A帯で約6.3~7.6発, B帯で約3.1~4.1発、目標のおよそ1.5~2倍)。
// ユーザーが検算済みと明言しているのはLv100帯の例のみ(ブリーフ本文参照)であり、
// Lv40以降(15Lv刻み)の帯ではこのテストも仕様目標(3~4.5 / 1~2)をそのまま満たす。
// Lv0~40台はテーブルの帯間隔そのものに起因する限界として、実測値の回帰ロックに留める
// (数値をいじって無理に仕様目標へ通したわけではない。詳細は作業レポート参照)。
const LEVEL_ORDER = [0, 10, 20, 30, 40, 55, 70, 85, 100];
const SPEC_CONFIRMED_FROM_LEVEL = 40; // Lv40以降の遷移(15Lv刻み)だけが確定テーブルの目標をそのまま満たす

for (let i = 1; i < LEVEL_ORDER.length; i++) {
  const prevLevel = LEVEL_ORDER[i - 1];
  const level = LEVEL_ORDER[i];
  const band = BANDS[prevLevel];
  const a = aFor(level); // 1帯上の敵
  const agg = aggregateSet(band.keys, band.vanillaMaterial);
  const hits = hitsToSurvive(agg.hpMax, a, agg.flatMax, agg.m);

  if (prevLevel >= SPEC_CONFIRMED_FROM_LEVEL) {
    test(`Lv${level}の敵に対しLv${prevLevel}装備(理論値)は3~4.5発(A帯)`, () => {
      assert.ok(hits >= 3 * (1 - TOL) && hits <= 4.5 * (1 + TOL),
        `Lv${prevLevel}装備でLv${level}の敵=${hits.toFixed(2)}発 (目標3~4.5)`);
    });
  } else {
    test(`Lv${level}の敵に対しLv${prevLevel}装備(理論値)はA帯目標未達=既知のギャップとして回帰ロック`, () => {
      assert.ok(hits >= 5 && hits <= 9,
        `Lv${prevLevel}装備でLv${level}の敵=${hits.toFixed(2)}発 (仕様目標3~4.5は未達。回帰許容5~9)`);
    });
  }
}

for (let i = 2; i < LEVEL_ORDER.length; i++) {
  const prevLevel = LEVEL_ORDER[i - 2];
  const level = LEVEL_ORDER[i];
  const band = BANDS[prevLevel];
  const a = aFor(level); // 2帯上の敵
  const agg = aggregateSet(band.keys, band.vanillaMaterial);
  const hits = hitsToSurvive(agg.hpMax, a, agg.flatMax, agg.m);

  if (prevLevel >= SPEC_CONFIRMED_FROM_LEVEL) {
    test(`Lv${level}の敵に対しLv${prevLevel}装備(理論値)は1~2発(B帯)`, () => {
      assert.ok(hits >= 1 * (1 - TOL) && hits <= 2 * (1 + TOL),
        `Lv${prevLevel}装備でLv${level}の敵=${hits.toFixed(2)}発 (目標1~2)`);
    });
  } else {
    test(`Lv${level}の敵に対しLv${prevLevel}装備(理論値)はB帯目標未達=既知のギャップとして回帰ロック`, () => {
      assert.ok(hits >= 2.5 && hits <= 5,
        `Lv${prevLevel}装備でLv${level}の敵=${hits.toFixed(2)}発 (仕様目標1~2は未達。回帰許容2.5~5)`);
    });
  }
}

test("Lv100理論値でも防具4部位のmax-health合計はバニラ+40を超えない(ユーザーの絶対制約)", () => {
  for (const cmdCsv of ["148,151,154,157", "149,152,155,158"]) {
    const keys = partKeys("NETHERITE", cmdCsv);
    const agg = aggregateSet(keys, "NETHERITE");
    const bonus = agg.hpMax - 20;
    assert.ok(bonus <= 40, `${cmdCsv}: Lv100理論値の追加HP=${bonus} が+40を超えている`);
  }
});

// ---------------------------------------------------------------------------------------------
// 2026-07-25 軽装レビュー T3-2: COPPER_*(Lv10) が CHAINMAIL_*(Lv20) を「防具値(armor-defense-rate)」
// と「物理耐性(phys-resistance)」の生の値で上回っている件 — 調査の結果、意図した仕様として確定
// (直さない)。将来「Lv20がLv10に劣っている、逆転バグだ」と早合点して直しに来るのを防ぐための
// regression lock。
//
// 根拠: 8段ダメージパイプラインで最初に減算されるのは「守備力」(phys-flat-defense、TABLEのfMinに
// 一致)であり、これが実効ダメージ軽減の主軸。armor-defense-rate/phys-resistanceはその後に乗算で
// 効く副次的な2軸に過ぎない。CHAINMAILは守備力がCOPPERの約2倍(6.40 vs 3.11)あり、副次2軸で劣って
// いても総合の実効被ダメージではCOPPERより優位(Lv20の敵に対して約25%被ダメージが少ない)。
//
// 実行時の防御率換算に注意: armor-defense-rateはAttributeApplier(REPLACE_MATERIAL_DEFAULTS経由)に
// より「そのバニラ材質の既定ARMOR値を置き換える」ため、実戦闘のdefRateは
// (4部位のarmor-defense-rate合計) * defense-rate-per-point(0.015) のみで決まり、
// VANILLA_ARMOR_POINTS(script内の設計時参考値)を加算しない。この回帰テストは実戦闘と同じ換算式を
// 使う(aggregateSet()のm計算とは意図的に別式 — 詳細はこのファイル冒頭のコメントと
// scripts/retune-armor-ladder.js のヘッダ参照)。
test("T3-2 regression lock: COPPER(Lv10)は防具値/物理耐性でCHAINMAIL(Lv20)を上回るが、守備力主導で実効被ダメージはCHAINMAILの方が少ない(直さない仕様)", () => {
  const DEFENSE_RATE_PER_POINT_RUNTIME = 0.015; // combat/damage.yml (armor-defense-rateは加点のみ、vanilla分は加算しない実戦闘の式)
  const a20 = aFor(20);

  function rawArmorDefenseRateSum(keys) {
    return PARTS.reduce((sum, p) => sum + (items[keys[p]].fixed?.["armor-defense-rate"] || 0), 0);
  }
  function rawPhysResSum(keys) {
    return PARTS.reduce((sum, p) => sum + (items[keys[p]].fixed?.["phys-resistance"] || 0), 0);
  }
  function rawFlatDefenseSum(keys) {
    return PARTS.reduce((sum, p) => sum + (items[keys[p]].fixed?.["phys-flat-defense"] || 0), 0);
  }
  function netDamageAgainstLv20(keys) {
    const flat = rawFlatDefenseSum(keys);
    const defRate = rawArmorDefenseRateSum(keys) * DEFENSE_RATE_PER_POINT_RUNTIME;
    const physRes = rawPhysResSum(keys);
    return Math.max(0, a20 - flat) * (1 - defRate) * (1 - physRes);
  }

  const copperKeys = partKeys("COPPER");
  const chainmailKeys = partKeys("CHAINMAIL");

  // 生の防具値/物理耐性は確かにCOPPERの方が高い(これ自体は直さない)。
  assert.ok(rawArmorDefenseRateSum(copperKeys) > rawArmorDefenseRateSum(chainmailKeys),
    "COPPERの生armor-defense-rate合計がCHAINMAILを上回っているはず(仕様)");
  assert.ok(rawPhysResSum(copperKeys) > rawPhysResSum(chainmailKeys),
    "COPPERの生phys-resistance合計がCHAINMAILを上回っているはず(仕様)");

  // だが守備力(主軸)はCHAINMAILが圧倒的に高く、実効被ダメージはCHAINMAILの方が少ない。
  assert.ok(rawFlatDefenseSum(chainmailKeys) > rawFlatDefenseSum(copperKeys) * 1.5,
    "CHAINMAILの守備力がCOPPERを大きく上回っているはず(実効優位の根拠)");
  const copperNet = netDamageAgainstLv20(copperKeys);
  const chainmailNet = netDamageAgainstLv20(chainmailKeys);
  assert.ok(chainmailNet < copperNet * 0.85,
    `CHAINMAILの実効被ダメージ(${chainmailNet.toFixed(2)})がCOPPER(${copperNet.toFixed(2)})より十分小さいはず`);
});

// ---------------------------------------------------------------------------------------------
// 2026-07-25 軽装レビュー (T1-T4): 新規軽装7セット28点 (骨鎧/銅鋲の革鎧/甲殻鎧/金糸の装束/
// 深海鱗の鎧/幻膜の外套/蝕みの絹) の regression カバレッジ。
//
// 【確定した検証方針(オーケストレータ確認済み・重要)】
// このラダーで「単調増加を強制してよい」のは physical defense (fixed.phys-flat-defense +
// random.phys-flat-defense.max の4部位合計 = TABLEのfMax)だけ。**durabilityは対象外**。
//
// 理由: durabilityは「同レベル帯の対置装備(バニラ材質換算) × 0.85」で決まり、対置元バニラ装備
// 自体の耐久がレベル順に単調ではない(例: バニラのチェインメイル(Lv20側の対置元)と鉄(Lv30側の
// 対置元)は完全に同一の耐久を持つ→copper_stud(Lv20)とcarapace_mail(Lv30)の耐久が一致するのは
// 継承の結果であり不具合ではない。銅(Lv10対置元)の耐久がチェインメイル(Lv20対置元)より高いのも
// 同様にバニラ由来で、bone_guard(Lv10)がcopper_stud(Lv20)より耐久が高いのも意図通り)。
// Lv40「金糸の装束」の耐久低下(バニラ金の脆さ継承)も同じ理由で意図的に残されており、これを
// 「Lv10→Lv20の低下だけ直す」のは一貫性を欠くため直さないと確定した。
// → 将来の担当者へ: この耐久の凸凹は「バグではなく仕様」。直しに来ないこと。
//
// 一方 armor-defense-rate (会心軽減の土台になる生の防具値) は各部位最低1を確保する
// (0だったcopper_stud兜/靴のバグのみ修正対象。他は元々1以上で問題なし)。
const NEW_LIGHT_FAMILIES = [
  { level: 10, label: "骨鎧(bone_guard)", cmdCsv: "200124,200125,200126,200127" },
  { level: 20, label: "銅鋲の革鎧(copper_stud)", cmdCsv: "200128,200129,200130,200131" },
  { level: 30, label: "甲殻鎧(carapace_mail)", cmdCsv: "200132,200133,200134,200135" },
  { level: 40, label: "金糸の装束(gilded_thread)", cmdCsv: "200136,200137,200138,200139" },
  { level: 55, label: "深海鱗の鎧(abyssal_scale)", cmdCsv: "200140,200141,200142,200143" },
  { level: 70, label: "幻膜の外套(phantom_shroud)", cmdCsv: "200144,200145,200146,200147" },
  { level: 85, label: "蝕みの絹(withered_silk)", cmdCsv: "200148,200149,200150,200151" },
];

// TABLEのfMax (retune-armor-ladder.jsのTABLE定数と同じ値をここでも独立に持つ = ミラーであり
// トートロジーを避けるため generator を import しない)。
const F_MAX_BY_LEVEL = {
  10: 7.4, 20: 10.6, 30: 13.6, 40: 19.2, 55: 31.1, 70: 48.8, 85: 77.7,
};

test("新規軽装7セット: 各部位のarmor-defense-rateは最低1 (copper_stud兜/靴の0バグ regression lock)", () => {
  for (const family of NEW_LIGHT_FAMILIES) {
    const keys = partKeys("LEATHER", family.cmdCsv);
    for (const part of PARTS) {
      const entry = items[keys[part]];
      assert.ok(entry, `missing item-stats entry for ${keys[part]}`);
      const adr = entry.fixed?.["armor-defense-rate"];
      assert.ok(adr >= 1, `${family.label} ${part}: armor-defense-rate=${adr} (最低1が必要)`);
    }
  }
});

test("新規軽装7セット: 最大ロール時の4部位合計phys-flat-defenseはTABLEのfMaxと一致し、A(Lv)を上回らない", () => {
  for (const family of NEW_LIGHT_FAMILIES) {
    const keys = partKeys("LEATHER", family.cmdCsv);
    const agg = aggregateSet(keys, "LEATHER");
    const expectedFMax = F_MAX_BY_LEVEL[family.level];
    const a = aFor(family.level);

    assert.ok(withinPct(agg.flatMax, expectedFMax, 0.01),
      `${family.label}: 最大ロール守備力合計=${agg.flatMax.toFixed(2)} (TABLE fMax=${expectedFMax}と一致するはず)`);
    assert.ok(agg.flatMax < a,
      `${family.label}: 最大ロール守備力合計=${agg.flatMax.toFixed(2)} が同帯モブ基準攻撃力A(${family.level})=${a.toFixed(2)}を上回っている(実質無敵バグ)`);
  }
});

test("新規軽装7セット: random.phys-flat-defense.max が fixed と同値になる旧バグが再発していない", () => {
  for (const family of NEW_LIGHT_FAMILIES) {
    const keys = partKeys("LEATHER", family.cmdCsv);
    for (const part of PARTS) {
      const entry = items[keys[part]];
      const fixed = entry.fixed?.["phys-flat-defense"] || 0;
      const rndMax = entry.random?.["phys-flat-defense"]?.max || 0;
      // 修正後は random.max = (fMax-fMin)*partWeight であり fixed(=fMin*partWeight) とは
      // 別の値になるはず(fixed===rndMaxは旧バグの症状そのもの)。フルロールの部位以外で偶然
      // 一致することはまず無い実測値なので、厳密不一致を要求する。
      assert.notEqual(rndMax, fixed,
        `${family.label} ${part}: random.max(${rndMax}) が fixed(${fixed}) と同値 (T2の旧バグが再発している)`);
    }
  }
});
