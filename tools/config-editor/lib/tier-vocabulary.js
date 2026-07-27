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
// mining-gimmick / woodcutting-gimmick / farming-gimmick / fishing-gimmick / crafting-features の
// 各セクションと feature id の対応は Java側 FeatureEffectRegistry + 各config の parse*Tiers/resolve と
// 一致させる (vein-mining→MiningGimmickConfig.veinMiningTiers, haste-active-mining→同haste,
//  tree-fell→WoodcuttingGimmickConfig, area-harvest→FarmingGimmickConfig,
//  xp-bottle-store-unlock→FishingGimmickConfig(xp-bottle-store), potion-merge→CraftingFeaturesConfig)。
// 2026-07-26 tier-expand: xp-bottle-store-unlock/potion-merge をNONE->SCALE化した際に追加。

const SCALE_FEATURE_SECTIONS = Object.freeze({
  "vein-mining": { gimmick: "mining", key: "vein-mining" },
  "haste-active-mining": { gimmick: "mining", key: "haste-active-mining" },
  "tree-fell": { gimmick: "woodcutting", key: "tree-fell" },
  "area-harvest": { gimmick: "farming", key: "area-harvest" },
  "xp-bottle-store-unlock": { gimmick: "fishing", key: "xp-bottle-store" },
  "potion-merge": { gimmick: "craftingFeatures", key: "potion-merge" }
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

/**
 * @param {object} gimmicks { mining, woodcutting, farming } 各ギミックymlの生データ(無ければnull可)
 * @returns {Object<string, number[]>} feature id -> 定義済みtier番号配列(昇順、無ければ空配列)
 */
function buildTierVocabulary(gimmicks) {
  const g = isPlainObject(gimmicks) ? gimmicks : {};
  const out = {};
  for (const featureId of Object.keys(SCALE_FEATURE_SECTIONS)) {
    const loc = SCALE_FEATURE_SECTIONS[featureId];
    const gimmickData = g[loc.gimmick];
    const section = isPlainObject(gimmickData) ? gimmickData[loc.key] : null;
    out[featureId] = extractTierNumbers(section);
  }
  return out;
}

module.exports = { buildTierVocabulary, extractTierNumbers, SCALE_FEATURE_SECTIONS };
