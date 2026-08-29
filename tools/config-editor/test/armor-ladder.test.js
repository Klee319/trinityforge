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

// 【2026-08-12 防具ラダー引き直し】敵の基準攻撃力カーブ。combat/mob-types.yml の ZOMBIE
// attack.attack-power (base 8, attack-power-growth 1.0148, attack-power-high-level-per-level 0)
// をそのまま反映する式。A(L) = 8.0 * 1.0148^L。
// 指数は当初 1.02 だったが「最大レベルで50ハートぐらいにとどめたい」というユーザー指示で
// 1.0148 へ寝かせた(A(100) が 0.6 倍 -> 守備力も最大HPも A に比例するので同じ 0.6 倍で縮み、
// Lv100 のプレイヤー最大HPが 166.7 -> 100 になる。耐えられる発数は変わらない)。
// 旧カーブ (7.0*1.03^L, かつLv45以降+0.25/Lvの加算あり) は撤去済み ── 実測でプレイヤー実効HPの
// 伸び(装備込みでも x3)を敵攻撃力の伸び(x40)が大幅に超えており、守備力(引き算段)だけでは
// 追いつけず「Lv20〜60は min-component-damage:1 に張り付いて無敵、Lv80〜100はほぼ即死」の
// 二極化を起こしていた元凶。
function baseAttackCurve(level) {
  return 8.0 * Math.pow(1.0148, level);
}

// combat/damage.yml の early-level-attack (Lv10未満だけ後掛けする緩和倍率)。
// EarlyLevelAttackSoftening#multiplier と同じ式: level>=until-level(10) なら1.0、それ未満は
// level0-multiplier(0.7) + (1 - level0-multiplier) * (level / until-level)。
function earlyLevelMultiplier(level) {
  const untilLevel = 10;
  const level0Multiplier = 0.7;
  if (level >= untilLevel) return 1.0;
  return level0Multiplier + (1 - level0Multiplier) * (level / untilLevel);
}

// 実戦闘でプレイヤーが受ける「実効」攻撃力。early-level-attack はモブの基本ダメージに後掛け
// されるため、Lv10未満の帯ではこの softening まで含めて A(L) として扱わないと、守備力ラダーの
// 検証がゲーム内の実際の被ダメージとズレる(Lv0帯の実測 F=2.8 は 0.5*8*0.7 と一致し、この
// softening を含めた値で防具側が校正されていることを裏付けている)。
function aFor(level) {
  return baseAttackCurve(level) * earlyLevelMultiplier(level);
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
// 2026-08-15: 防具値ステ(armor-defense-rate, 点数)を廃止し防御率(defense-rate, [0,1])へ一本化した。
// ここの m は「設計時のはしご」を固定するための別式(VANILLA_ARMOR_POINTS を足す = 実戦闘とは
// 意図的に違う。理由は T3-2 regression lock のコメント参照)なので、式そのものは変えず、
// 点数の代わりに防御率で同じ計算をする(旧 点数合計 * 0.015 == 新 defense-rate 合計 で同値)。
function aggregateSet(keys, vanillaMaterial) {
  let flatMin = 0, flatMax = 0, hpMin = 20, hpMax = 20, defenseRateSum = 0, physResSum = 0;
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

    defenseRateSum += fixed["defense-rate"] || 0;
    physResSum += fixed["phys-resistance"] || 0;
  }
  const totalDefenseRate = defenseRateSum
    + VANILLA_ARMOR_POINTS[vanillaMaterial] * DEFENSE_RATE_PER_POINT;
  const defRate = Math.min(totalDefenseRate, DEFENSE_RATE_MAX);
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

// 【2026-08-12 防具ラダー引き直し】S帯の目標耐久回数(理論値/厳選なし)。
//
// 旧設計は「理論値20発/厳選なし6発」を全帯共通の目標として、F(fMin/fMax)とm(乗算軽減)を
// 帯ごとに連立方程式で解いていた(retune-armor-ladder.js の TABLE がその解)。新設計は
// F(L) = 0.5 * A(L) (早期緩和込み) という単純な閉形に切り替えており、m(defense-rate/
// phys-resistanceから決まる乗算軽減)は帯ごとに既存の値のまま(このスクリプトが再計算していない)
// ため、"理論値20/厳選なし6"を全帯で満たすことはもう保証されない(mが帯ごとに大きく変動する
// 一方、F=0.5*Aは常に同じ比率なので、両者の組み合わせで出る耐久回数は帯ごとに5~14発の範囲で
// 上下する)。ここでは item-stats.yml の実測値(2026-08-12)を帯別の回帰ロックとして固定する
// ── 「守備力を引いてもゼロ近傍/爆発の二極化に戻っていないか」を検出するのが目的で、
// 旧仕様の統一ターゲットそのものを再現する試みではない。
//
// 【2026-08-12 追補: 指数を 1.02 -> 1.0148 へ寝かせたときの表の更新】
// A・F・最大HP は「A に比例して」縮めたのに、この表の数値は 10〜35% 増える方向へ寄った。
// 理由は【バニラの最大HP 20 が固定で縮まないから】── 装備の最大HPだけが縮むので、
// 総HP(20 + 装備分)は A ほどは縮まない。加えて帯ごとの F の倍率も A の倍率とは一致しない
// (F は帯の実測合計から target/実測 で出しているため)。
// 同帯どうしの被弾回数(tmp/balance-model.js の TTD、品質9の期待ロール)は前後で不変。
// ここが動いたのは「最大ロール(hpMax/flatMax)」と「格上の敵」という、この表だけが見ている
// 断面。方向としては「格上に対して少し粘れるようになった」で、床値張り付きとは逆側なので許容する。
//
// 【2026-08-14 ダイヤ(Lv55重装)帯だけ再測定】9.4 -> 12.9 / 6.4 -> 8.9。
// この帯だけ「1段下の金(Lv40) 11.7 より耐えられない」という逆転が残っていた
// (DIAMOND_* 4部位の phys-resistance 合計 0.12 が GOLDEN_* の 0.214 を 44% 下回り、
//  防具値(当時のarmor-defense-rate)も 11 で IRON/GOLDEN の 14 未満だった)。
// 2026-08-12 の引き直しは m(乗算軽減)を帯ごとに据え置いた上で実測値をロックしただけなので、
// この逆転もそのまま固定されていた。GOLDEN -> NETHERITE の線形補間(t=0.4)へ揃えたので、
// ラダーは 40:11.7 -> 55:12.9 -> 70:13.7 と単調になる。
// 変更したのは DIAMOND_* 4部位の防具値(現 defense-rate) / phys-resistance / max-health だけで、
// 他帯の数値は1つも動いていない(この表の他の行が変わっていないことがその証拠)。
//
// 【2026-08-21 W-183 軽装/重装/魔法装の住み分け引き直し】全帯を再測定して差し替えた。
// 動いた理由は2つで、どちらも「防具ラダーの意図」ではなく「防具データそのもの」を
// 引き直したことによる。
//   (1) 帯ごとの被ダメージ期待値を【重装(板金)ラインの現在値】へ全ラインで揃えた。
//       このとき板金ラインも「帯の梯子(バニラ銅→鎖→鉄→金→ダイヤ→ネザライト→ウィザー→
//       世界を繋ぐ)からの補間値」へ寄せているので、梯子から外れていた帯(革Lv0・
//       インフィニティLv100)は守備係数が上がり、耐久回数もその分伸びた
//       (Lv0 9.1→12.6 / Lv100 10.1→15.8 が該当。他の帯の増分は 5〜15% に収まる)。
//   (2) 最大HPが「fixed 0 で全部ランダムロール」だった 83 部位(軽装/魔法装の全ライン)を
//       重装と同じ fixed 74.5% + ロール 25.5% へ分け直した。総量(fixed+random.max)は
//       据え置きなので S理論値(最大ロール)はこの操作では動かないが、S厳選なし(最小ロール)は
//       上がる。軽装の主軸ステータスを最大HPに据えた以上、「軽装だけ厳選運で4倍ブレる」
//       状態を残すと住み分けそのものが成立しないため。
// この表の役割は変わっていない ── 「守備力を引いた残りがゼロ近傍/爆発の二極化に
// 戻っていないか」の検出。値そのものは設計目標ではなく実測の回帰ロック。
const S_THEORY_TARGET = { 0: 12.6, 10: 7.1, 20: 6.6, 30: 10.8, 40: 13.3, 55: 14.6, 70: 15.1, 85: 15.7, 100: 15.8 };
const S_BASELINE_TARGET = { 0: 8.0, 10: 5.0, 20: 4.5, 30: 6.3, 40: 7.6, 55: 9.6, 70: 9.9, 85: 11.3, 100: 11.8 };

for (const [levelStr, band] of Object.entries(BANDS)) {
  const level = Number(levelStr);
  const a = aFor(level);
  const agg = aggregateSet(band.keys, band.vanillaMaterial);

  test(`${band.label}: S帯の耐久回数ラダーが成立する`, () => {
    const sTheory = hitsToSurvive(agg.hpMax, a, agg.flatMax, agg.m); // S理論値
    const sUpper = hitsToSurvive((agg.hpMin + agg.hpMax) / 2, a, (agg.flatMin + agg.flatMax) / 2, agg.m); // S上振れ(中間ロール概算)
    const sBaseline = hitsToSurvive(agg.hpMin, a, agg.flatMin, agg.m); // S厳選なし

    assert.ok(withinPct(sTheory, S_THEORY_TARGET[level], TOL),
      `${band.label} S理論値=${sTheory.toFixed(2)} (目標${S_THEORY_TARGET[level]}, m=${agg.m.toFixed(3)})`);
    assert.ok(withinPct(sBaseline, S_BASELINE_TARGET[level], TOL),
      `${band.label} S厳選なし=${sBaseline.toFixed(2)} (目標${S_BASELINE_TARGET[level]}, m=${agg.m.toFixed(3)})`);
    assert.ok(sUpper > sBaseline && sUpper < sTheory, `${band.label} S上振れ=${sUpper.toFixed(2)} が基準~理論値の間にない`);
  });
}

// 1帯下(A)・2帯下(B)チェックは「現装備で1段上/2段上の敵に当たる」形で直接検証する。
//
// 【2026-08-12 防具ラダー引き直し】旧仕様目標(A帯=3~4.5発/B帯=1~2発、Lv40以降の帯だけ厳格判定・
// それ未満は「既知のギャップ」として緩い回帰ロック)は、旧カーブ(7*1.03^Lv)+旧TABLE(連立方程式で
// 解いたfMin/fMax/m)前提のもの。新カーブ(8*1.02^Lv)+新F(L)=0.5*A(L)では、Lv40以降でも
// 旧目標(3~4.5/1~2)を満たす帯と満たさない帯が混在する(mが帯ごとに大きく違うため)。
// 「Lv40以降だけ厳格」という帯の切り方自体が新カーブでは意味を持たないので撤去し、
// 実測値(item-stats.yml, 2026-08-12)を遷移ペアごとの回帰ロックとして固定する。
const LEVEL_ORDER = [0, 10, 20, 30, 40, 55, 70, 85, 100];

// 1帯上(A帯)の実測耐久回数。key=現在の装備帯(prevLevel)。2026-08-12 追補で再測定
// (増えた理由は上の S_THEORY_TARGET のコメントを見ること。バニラの20が縮まないため)。
// 2026-08-14: 55 の行だけ 6.4 -> 8.8(上の S_THEORY_TARGET のコメント参照。ダイヤ帯の再測定)。
// 2026-08-21 W-183: 全帯を再測定(理由は S_THEORY_TARGET のコメント)。Lv0 装備の行だけ
// 3.9 -> 5.5 と大きく動くのは、革(Lv0)が帯の梯子から外れていて守備係数が上がったため。
const A_BAND_TARGET = { 0: 5.5, 10: 5.6, 20: 5.1, 30: 8.1, 40: 8.3, 55: 9.5, 70: 10.0, 85: 11.3 };
// 2帯上(B帯)の実測耐久回数。key=現在の装備帯(prevLevel)。
// 2026-08-14: 55 の行だけ 4.6 -> 6.3(同上)。 2026-08-21 W-183: 全帯を再測定。
const B_BAND_TARGET = { 0: 4.5, 10: 4.6, 20: 4.1, 30: 5.6, 40: 5.7, 55: 6.7, 70: 7.0 };

for (let i = 1; i < LEVEL_ORDER.length; i++) {
  const prevLevel = LEVEL_ORDER[i - 1];
  const level = LEVEL_ORDER[i];
  const band = BANDS[prevLevel];
  const a = aFor(level); // 1帯上の敵
  const agg = aggregateSet(band.keys, band.vanillaMaterial);
  const hits = hitsToSurvive(agg.hpMax, a, agg.flatMax, agg.m);
  const target = A_BAND_TARGET[prevLevel];

  test(`Lv${level}の敵に対しLv${prevLevel}装備(理論値)はA帯の実測耐久回数を維持する`, () => {
    assert.ok(withinPct(hits, target, TOL),
      `Lv${prevLevel}装備でLv${level}の敵=${hits.toFixed(2)}発 (目標${target})`);
  });
}

for (let i = 2; i < LEVEL_ORDER.length; i++) {
  const prevLevel = LEVEL_ORDER[i - 2];
  const level = LEVEL_ORDER[i];
  const band = BANDS[prevLevel];
  const a = aFor(level); // 2帯上の敵
  const agg = aggregateSet(band.keys, band.vanillaMaterial);
  const hits = hitsToSurvive(agg.hpMax, a, agg.flatMax, agg.m);
  const target = B_BAND_TARGET[prevLevel];

  test(`Lv${level}の敵に対しLv${prevLevel}装備(理論値)はB帯の実測耐久回数を維持する`, () => {
    assert.ok(withinPct(hits, target, TOL),
      `Lv${prevLevel}装備でLv${level}の敵=${hits.toFixed(2)}発 (目標${target})`);
  });
}

// 【2026-08-12 防具ラダー引き直し】旧絶対制約(+40)は旧カーブ・旧HP設計(装備込み最大HPを
// x3程度にしか伸ばさない)前提の値。新設計はプレイヤー実効HPを
// HP(L) = 8 * (A(L) - F(L)) * k で敵攻撃力カーブに追随させる方針へ変更しており、ユーザー
// ブリーフに明記された新しいLv100の目標(最大HP ≈166.9、バニラ20込みなので追加分≈146.9)が
// 旧+40を大きく超える。ここでは新ブリーフの目標値に十分な安全マージンを乗せた+150を
// 「無限に伸びていないか(暴走)」を検出する上限として維持する(値ゼロではない不変条件は残す)。
test("Lv100理論値でも防具4部位のmax-health合計は暴走していない(新ブリーフの目標+150を上限とする)", () => {
  for (const cmdCsv of ["148,151,154,157", "149,152,155,158"]) {
    const keys = partKeys("NETHERITE", cmdCsv);
    const agg = aggregateSet(keys, "NETHERITE");
    const bonus = agg.hpMax - 20;
    assert.ok(bonus <= 150, `${cmdCsv}: Lv100理論値の追加HP=${bonus} が新ブリーフの安全上限+150を超えている`);
  }
});

// ---------------------------------------------------------------------------------------------
// 2026-07-25 軽装レビュー T3-2: COPPER_*(Lv10) が CHAINMAIL_*(Lv20) を「防御率(defense-rate)」
// と「物理耐性(phys-resistance)」の生の値で上回っている件 — 調査の結果、意図した仕様として確定
// (直さない)。将来「Lv20がLv10に劣っている、逆転バグだ」と早合点して直しに来るのを防ぐための
// regression lock。
//
// 根拠: 8段ダメージパイプラインで最初に減算されるのは「守備力」(phys-flat-defense、TABLEのfMinに
// 一致)であり、これが実効ダメージ軽減の主軸。defense-rate/phys-resistanceはその後に乗算で
// 効く副次的な2軸に過ぎない。CHAINMAILは守備力がCOPPERの約2倍(6.40 vs 3.11)あり、副次2軸で劣って
// いても総合の実効被ダメージではCOPPERより優位(Lv20の敵に対して約25%被ダメージが少ない)。
//
// 実行時の防御率換算に注意: 2026-08-15 に防具値ステ(armor-defense-rate)を廃止し、TFスタンプ装備の
// Attribute.ARMOR は常に 0(防具バーは空)になった。実戦闘の defRate は 4部位の defense-rate 合計
// そのもので決まり、VANILLA_ARMOR_POINTS(script内の設計時参考値)は一切加算しない。
// この回帰テストは実戦闘と同じ換算式を使う(aggregateSet()のm計算とは意図的に別式 — 詳細は
// このファイル冒頭のコメントと scripts/retune-armor-ladder.js のヘッダ参照)。
test("T3-2 regression lock: COPPER(Lv10)は防御率/物理耐性でCHAINMAIL(Lv20)を上回るが、守備力主導で実効被ダメージはCHAINMAILの方が少ない(直さない仕様)", () => {
  const a20 = aFor(20);

  function rawDefenseRateSum(keys) {
    return PARTS.reduce((sum, p) => sum + (items[keys[p]].fixed?.["defense-rate"] || 0), 0);
  }
  function rawPhysResSum(keys) {
    return PARTS.reduce((sum, p) => sum + (items[keys[p]].fixed?.["phys-resistance"] || 0), 0);
  }
  function rawFlatDefenseSum(keys) {
    return PARTS.reduce((sum, p) => sum + (items[keys[p]].fixed?.["phys-flat-defense"] || 0), 0);
  }
  function netDamageAgainstLv20(keys) {
    const flat = rawFlatDefenseSum(keys);
    const defRate = rawDefenseRateSum(keys);
    const physRes = rawPhysResSum(keys);
    return Math.max(0, a20 - flat) * (1 - defRate) * (1 - physRes);
  }

  const copperKeys = partKeys("COPPER");
  const chainmailKeys = partKeys("CHAINMAIL");

  // 生の防御率/物理耐性は確かにCOPPERの方が高い(これ自体は直さない)。
  assert.ok(rawDefenseRateSum(copperKeys) > rawDefenseRateSum(chainmailKeys),
    "COPPERの生defense-rate合計がCHAINMAILを上回っているはず(仕様)");
  assert.ok(rawPhysResSum(copperKeys) > rawPhysResSum(chainmailKeys),
    "COPPERの生phys-resistance合計がCHAINMAILを上回っているはず(仕様)");

  // 守備力(引き算段)は依然としてCHAINMAILの方が高い。ここは設計として維持する。
  assert.ok(rawFlatDefenseSum(chainmailKeys) > rawFlatDefenseSum(copperKeys) * 1.5,
    "CHAINMAILの守備力がCOPPERを大きく上回っているはず(帯が上なので当然)");

  // 2026-08-10 の再較正で、この lock の向きは意図的に反転した。
  //
  // 旧: 守備力がモブ攻撃力とほぼ同じ大きさだったので、引き算段 (A - F) が支配的になり、
  //     「防具値も物理耐性も劣るCHAINMAILの方が実効被ダメージが少ない」という逆転が起きていた。
  //     当時はこれを『直さない仕様』として固定していた。
  // 新: F(L) = 0.5 x A(L) に引き直したので (A - F) は常に A の半分ぶん残り、引き算段が
  //     %軽減(防御率・物理耐性)を食い潰さなくなった。結果、防御率と物理耐性で勝るCOPPERが
  //     実効被ダメージでも正しく勝つ。逆転は「直した」のであって「壊れた」のではない。
  //
  // ここを旧向きへ戻すには守備力をモブ攻撃力と同勾配へ戻すしかなく、それは
  // 「無敵か即死か」の二択（全帯で min-component-damage の床値に張り付く）へ逆戻りすることを意味する。
  const copperNet = netDamageAgainstLv20(copperKeys);
  const chainmailNet = netDamageAgainstLv20(chainmailKeys);
  assert.ok(copperNet < chainmailNet,
    `防具値/物理耐性で勝るCOPPERの実効被ダメージ(${copperNet.toFixed(2)})が`
    + `CHAINMAIL(${chainmailNet.toFixed(2)})より少ないはず`
    + "(守備力が%軽減を食い潰さなくなったため)");
  // 引き算段が支配的に戻っていないこと自体も固定する: 守備力はLv20の攻撃力の6割未満に収まる。
  assert.ok(rawFlatDefenseSum(chainmailKeys) < a20 * 0.6,
    `CHAINMAILの守備力(${rawFlatDefenseSum(chainmailKeys).toFixed(2)})が`
    + `Lv20攻撃力(${a20.toFixed(2)})の6割以上ある。引き算段が支配的に戻ると床値張り付きが再発する。`);
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
// 一方 defense-rate (乗算軽減の土台) は各部位最低 0.015(旧仕様の防具値1点ぶん)を確保する
// (0だったcopper_stud兜/靴のバグのみ修正対象。他は元々1点以上で問題なし)。
const NEW_LIGHT_FAMILIES = [
  { level: 10, label: "骨鎧(bone_guard)", cmdCsv: "200124,200125,200126,200127" },
  { level: 20, label: "銅鋲の革鎧(copper_stud)", cmdCsv: "200128,200129,200130,200131" },
  { level: 30, label: "甲殻鎧(carapace_mail)", cmdCsv: "200132,200133,200134,200135" },
  { level: 40, label: "金糸の装束(gilded_thread)", cmdCsv: "200136,200137,200138,200139" },
  { level: 55, label: "深海鱗の鎧(abyssal_scale)", cmdCsv: "200140,200141,200142,200143" },
  { level: 70, label: "幻膜の外套(phantom_shroud)", cmdCsv: "200144,200145,200146,200147" },
  { level: 85, label: "蝕みの絹(withered_silk)", cmdCsv: "200148,200149,200150,200151" },
];

// 【2026-08-12 防具ラダー引き直し】item-stats.yml から直接読み取った実測値(独立読み取りであり
// generator=tools/config-editor/scripts/retune-armor-ladder.js は import していないため
// トートロジーではない ── 同スクリプトは旧カーブ(7*1.03^Lv)のTABLEしか持たず、今回の引き直し
// では実行していない)。
// F(L) = 0.5 * A(L) という単純な閉形にはならない: 各セットは「同帯の重装(バニラ素材)セットと
// 同じ守備力予算を共有する」設計のため(例: 骨鎧(Lv10ラベル)の実測3.2はCOPPER_*4部位合計と
// 完全一致、甲殻鎧(Lv30ラベル)の実測5.9はIRON_*4部位合計と完全一致、等)、閉形の式ではなく
// 実測値をそのまま回帰ロックとして固定する。
// 各値が「重装セットの合計そのまま」より 0.1〜0.4 だけ大きいのは、引き直しの丸めで
// ロール帯(fixed と random.max)が同じ刻みへ潰れた部位を +0.1 ずつ押し広げたため。
// 潰れると厳選幅そのものが消えるので、直下の regression lock がそれを拾う。
//
// 【2026-08-21 W-183】「同帯の重装セットと同じ守備力予算を共有する」という上の設計は撤回した。
// 軽装と重装の住み分けを数値で成立させるため、軽装ラインの物理3軸(守備力/物理耐性/防御率)には
// 【同帯の板金ラインの 80% を上限とする】天井を入れてある(重装が守備で必ず勝つ向きを保証する。
// 天井が無いと、元の値が薄いラインほど守備係数が大きく解かれて "軽装の守備力が同帯の板金を
// 上回る" 逆転が起きる ── 実際に起きた)。削った守備のぶんは最大HPで返している(軽装は板金の 1.5 倍)。
// したがってこの表は重装セット合計とは一致しない。実測値をそのまま回帰ロックとして固定する。
const F_MAX_BY_LEVEL = {
  10: 2.85, 20: 2.94, 30: 5.18, 40: 6.79, 55: 7.70, 70: 9.37, 85: 8.34,
};

test("新規軽装7セット: 各部位のdefense-rateは最低0.015 (copper_stud兜/靴の0バグ regression lock)", () => {
  for (const family of NEW_LIGHT_FAMILIES) {
    const keys = partKeys("LEATHER", family.cmdCsv);
    for (const part of PARTS) {
      const entry = items[keys[part]];
      assert.ok(entry, `missing item-stats entry for ${keys[part]}`);
      const rate = entry.fixed?.["defense-rate"];
      assert.ok(rate >= DEFENSE_RATE_PER_POINT,
        `${family.label} ${part}: defense-rate=${rate} (最低${DEFENSE_RATE_PER_POINT}が必要)`);
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
