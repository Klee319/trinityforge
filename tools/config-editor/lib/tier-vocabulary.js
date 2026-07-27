"use strict";

// タスク2 (2026-07-26): スキルツリー「機能解放」(feature:<id>, param="scale") のtier番号を、
// 自由入力ではなく実際に定義済みのtierからセレクトで選べるようにするための語彙供給。
//
// 対象は tf-lifestyle-forms.js の tierTableEditor で section.tiers を編集できる機構
// (= lib/gate-vocabulary.js の FEATURES で param:"scale" の各件と一致。他のfeatureは
// tiers非対応なので対象外)。各 section.tiers に実在するtier番号を返すだけの読み取り専用ロジックで、
// 新しい語彙(意味)は増やさない。gate-vocabulary.js の FEATURES 自体はここでは変更しない
// (歩調を合わせるだけ)。
//
// mining-gimmick / woodcutting-gimmick / farming-gimmick / fishing-gimmick / crafting-features /
// smithing-gimmick / digging-gimmick の各セクションと feature id の対応は Java側 FeatureEffectRegistry
// + 各config の parse*Tiers/resolve と一致させる
// (vein-mining→MiningGimmickConfig.veinMiningTiers, haste-active-mining→同haste,
//  tree-fell→WoodcuttingGimmickConfig, area-harvest→FarmingGimmickConfig,
//  xp-bottle-store-unlock→FishingGimmickConfig(xp-bottle-store), potion-merge→CraftingFeaturesConfig,
//  furnace-smelt-*→SmithingGimmickConfig, digging-durability-*→DiggingGimmickConfig)。
// 2026-07-26 tier-expand: xp-bottle-store-unlock/potion-merge をNONE->SCALE化した際に追加。
// 2026-07-28 (数値のギミックyml集約): furnace-smelt-speed/bonus と digging-durability-vanilla-exp/job-exp
// をLEVEL->SCALE化したのに伴い smithing / digging バケットを追加。この4件は tiers が
// ymlのトップレベル直下ではなく1段ネストした位置(furnace-smelt.speed 等)にあるため、key を
// ドット区切りのパスとして解決するようにした(既存6件のkeyにドットは含まれないので後方互換)。

const SCALE_FEATURE_SECTIONS = Object.freeze({
  "vein-mining": { gimmick: "mining", key: "vein-mining" },
  "haste-active-mining": { gimmick: "mining", key: "haste-active-mining" },
  "tree-fell": { gimmick: "woodcutting", key: "tree-fell" },
  "area-harvest": { gimmick: "farming", key: "area-harvest" },
  "xp-bottle-store-unlock": { gimmick: "fishing", key: "xp-bottle-store" },
  "potion-merge": { gimmick: "craftingFeatures", key: "potion-merge" },
  "furnace-smelt-speed": { gimmick: "smithing", key: "furnace-smelt.speed" },
  "furnace-smelt-bonus": { gimmick: "smithing", key: "furnace-smelt.bonus" },
  "digging-durability-vanilla-exp": { gimmick: "digging", key: "durability-exp.vanilla-exp" },
  "digging-durability-job-exp": { gimmick: "digging", key: "durability-exp.job-exp" }
});

function isPlainObject(v) {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

// section.tiers のキー群から、正の整数のtier番号だけを昇順で返す(不正キーは無視)。
function extractTierNumbers(section) {
  const tiers = section && section.tiers;
  if (!isPlainObject(tiers)) return [];
  return Object.keys(tiers)
    .map((k) => Number(k))
    .filter((n) => Number.isInteger(n) && n > 0)
    .sort((a, b) => a - b);
}

// ドット区切りの key ("furnace-smelt.speed") を1段ずつ辿る。途中がオブジェクトでなければ null。
function resolveSection(root, dottedKey) {
  let node = root;
  for (const part of String(dottedKey).split(".")) {
    if (!isPlainObject(node)) return null;
    node = node[part];
  }
  return isPlainObject(node) ? node : null;
}

/**
 * @param {object} gimmicks { mining, woodcutting, farming, fishing, craftingFeatures, smithing, digging }
 *                          各ギミックymlの生データ(無ければnull可)
 * @returns {Object<string, number[]>} feature id -> 定義済みtier番号配列(昇順、無ければ空配列)
 */
function buildTierVocabulary(gimmicks) {
  const g = isPlainObject(gimmicks) ? gimmicks : {};
  const out = {};
  for (const featureId of Object.keys(SCALE_FEATURE_SECTIONS)) {
    const loc = SCALE_FEATURE_SECTIONS[featureId];
    out[featureId] = extractTierNumbers(resolveSection(g[loc.gimmick], loc.key));
  }
  return out;
}

module.exports = { buildTierVocabulary, extractTierNumbers, SCALE_FEATURE_SECTIONS };
