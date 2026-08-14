"use strict";

// スキルツリー「解放効果」の動的ゲート語彙を各ソースymlから一括抽出する。
// 設計書 2026-07-23-stat-gate-overhaul.md §3 / §3.2 準拠。
// どのソースが欠損/空でも例外を投げず、対応するキーは空配列を返す。

// 機能解放 (feature:<id>) の固定語彙。プログラム定義（§3.2 FeatureEffectRegistry 相当）。
// 2026-07-25 gather-rework-active-framework §1/§6 Q1: vein-mining/tree-fell/area-harvest/
// haste-active-mining を "scale"(tier表) 化し、small-tree-fell/large-tree-fell を tree-fell へ統合。
// Java側 com.trinityforge.skilltree.effects.FeatureEffectRegistry と id/param が一致していることを
// test/gate-vocabulary-java-parity.test.js が検証する(2026-07-24 の break-vanilla-exp 欠落事故の再発防止)。
const FEATURES = Object.freeze([
  { id: "vein-mining", label: "鉱脈一括破壊", param: "scale" },
  { id: "haste-active-mining", label: "採掘ハステアクティブ", param: "scale" },
  { id: "spawner-silktouch-harvest", label: "スポナーST回収", param: "none" },
  { id: "tree-fell", label: "木一括伐採", param: "scale" },
  { id: "auto-replant", label: "自動再植", param: "none" },
  { id: "area-harvest", label: "範囲収穫", param: "scale" },
  { id: "animal-damage-4x", label: "動物特効", param: "none" },
  { id: "bee-no-aggro", label: "蜂非敵対", param: "none" },
  { id: "junkfood-immunity", label: "ゴミ食免疫", param: "none" },
  // 2026-07-27 農業「ゴミ食」段階化: none -> level化(Java側 FeatureEffectRegistry と同期必須)。
  { id: "junkfood-inversion", label: "ゴミ食反転%", param: "level" },
  { id: "satiety-buff", label: "満腹バフ", param: "none" },
  { id: "junk-to-scrap", label: "釣りゴミ→スクラップ", param: "none" },
  // 2026-07-25 経済連携(vault対応)により再導入。Java側 FeatureEffectRegistry と同期必須。
  { id: "fish-sell-toggle", label: "釣った魚を自動売却", param: "none" },
  // 2026-07-26 tier-expand: NONE -> SCALE化(Java側 FeatureEffectRegistry と同期必須)。
  { id: "xp-bottle-store-unlock", label: "経験値瓶保存", param: "scale" },
  { id: "dismantle-unlock", label: "装備解体", param: "level" },
  { id: "potion-merge", label: "ポーション統合", param: "scale" },
  { id: "wood-repair-unlock", label: "木材修繕", param: "none" },
  { id: "weapon-coating-unlock", label: "武器コーティング解放", param: "none" },
  // 2026-07-28 (数値のギミックyml集約): coating-stack-increase は feature から通常stat
  // (coating_charges_bonus)へ移設したため削除。Java側 FeatureEffectRegistry と同期必須。
  { id: "source-auto-consume", label: "ソース自動消費", param: "none" },
  { id: "break-vanilla-exp", label: "破壊時バニラEXP解放", param: "none" },
  // 2026-07-28 (数値のギミックyml集約): 精錬速度/ボーナスと切削耐久累計2件をlevel(生%直書き) ->
  // scale(tier番号)化。実値は stats/smithing-gimmick.yml / stats/digging-gimmick.yml のtierテーブルへ
  // 移設した。Java側 FeatureEffectRegistry と同期必須。
  { id: "furnace-smelt-speed", label: "精錬速度短縮tier", param: "scale" },
  { id: "furnace-smelt-bonus", label: "精錬ボーナスtier", param: "scale" },
  { id: "junk-food-restore-boost", label: "ゴミ食回復ボーナス%", param: "level" },
  { id: "digging-durability-vanilla-exp", label: "耐久累計→バニラEXP tier", param: "scale" },
  { id: "digging-durability-job-exp", label: "耐久累計→職業EXP tier", param: "scale" }
]);

function isPlainObject(v) {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

// glyphs.yml: glyphs.<key> → { key, displayName, category }
function extractGlyphs(glyphsData) {
  const glyphs = glyphsData && glyphsData.glyphs;
  if (!isPlainObject(glyphs)) return [];
  return Object.keys(glyphs).map((key) => {
    const e = isPlainObject(glyphs[key]) ? glyphs[key] : {};
    return {
      key,
      displayName: e["display-name"] != null ? String(e["display-name"]) : key,
      category: e.category != null ? String(e.category) : ""
    };
  });
}

// progression/crafting-features.yml: brew-unlocks キー一覧
function extractBrews(craftingFeatures) {
  const brewUnlocks = craftingFeatures && craftingFeatures["brew-unlocks"];
  if (!isPlainObject(brewUnlocks)) return [];
  return Object.keys(brewUnlocks);
}

// progression/crafting-features.yml: over-enchant キー一覧
function extractOverenchants(craftingFeatures) {
  const overEnchant = craftingFeatures && craftingFeatures["over-enchant"];
  if (!isPlainObject(overEnchant)) return [];
  return Object.keys(overEnchant);
}

// economy/villager-trades.yml: professions キー一覧
function extractTrades(villagerTrades) {
  const professions = villagerTrades && villagerTrades.professions;
  if (!isPlainObject(professions)) return [];
  return Object.keys(professions);
}

// fishing.groups.<treasure|junk|fish>.categories / fishing.unlock-groups.<同groupId>.categories の
// 1グループ分を out へ積む共通処理(groups と unlock-groups は完全に同一の Category スキーマ)。
function collectFishingGroupCategories(profession, groups, out) {
  if (!isPlainObject(groups)) return;
  for (const groupId of Object.keys(groups)) {
    const group = groups[groupId];
    const categories = group && group.categories;
    if (!isPlainObject(categories)) continue;
    for (const catId of Object.keys(categories)) {
      const cat = categories[catId];
      out.push({
        profession,
        categoryId: `${groupId}:${catId}`,
        displayName: (isPlainObject(cat) && cat["display-name"]) || catId
      });
    }
  }
}

// 各ギミックyml drop-tables.categories → { profession, categoryId, displayName }[]
// 釣りだけ fishing.groups.<treasure|junk|fish>.categories 形式。
// 2026-08-15: 機能解放追加用テーブル fishing.unlock-groups.<同groupId>.categories も同じ
// categoryId (`${groupId}:${catId}`) で語彙へ追加する ── ノード側の解放IDは
// drop:fishing:<groupId>:<catId> で groups/unlock-groups どちらのカテゴリも共通形式のため。
function extractDropCategories(profession, gimmickData) {
  const out = [];
  if (!isPlainObject(gimmickData)) return out;

  if (profession === "fishing") {
    const fishing = gimmickData.fishing;
    if (!isPlainObject(fishing)) return out;
    collectFishingGroupCategories(profession, fishing.groups, out);
    collectFishingGroupCategories(profession, fishing["unlock-groups"], out);
    return out;
  }

  const dropTables = gimmickData["drop-tables"];
  const categories = dropTables && dropTables.categories;
  if (!isPlainObject(categories)) return out;
  for (const catId of Object.keys(categories)) {
    const cat = categories[catId];
    out.push({
      profession,
      categoryId: catId,
      displayName: (isPlainObject(cat) && cat["display-name"]) || catId
    });
  }
  return out;
}

function extractDrops(gimmicks) {
  const g = gimmicks || {};
  return []
    .concat(extractDropCategories("mining", g.mining))
    .concat(extractDropCategories("woodcutting", g.woodcutting))
    .concat(extractDropCategories("digging", g.digging))
    .concat(extractDropCategories("fishing", g.fishing));
}

// progression/special-rewards.yml: titles/particles/particle-seeds キー(和集合)
const SPECIAL_REWARD_GROUPS = [
  ["titles", "称号"],
  ["particles", "パーティクル"],
  ["particle-seeds", "パーティクルシード"]
];
function extractSpecialRewards(specialRewards) {
  if (!isPlainObject(specialRewards)) return [];
  const ids = new Set();
  for (const [group] of SPECIAL_REWARD_GROUPS) {
    const map = specialRewards[group];
    if (isPlainObject(map)) {
      for (const k of Object.keys(map)) ids.add(k);
    }
  }
  return [...ids].sort();
}

// 2026-07-29: 特殊報酬IDは new_title / new_particle のような機械名なので、スキルツリーの
// reward: ゲートのセレクトが読めない ID の羅列になっていた。表示用の日本語ラベルを別キーで
// 添える (specialRewards の配列そのものは互換のためID配列のまま)。
// 称号の display は MiniMessage なのでタグを落としたプレーン文字にする。
function stripMiniMessage(raw) {
  return String(raw == null ? "" : raw)
    .replace(/<[^>]+>/g, "")
    .replace(/[§&][0-9a-fk-or]/gi, "")
    .replace(/\s+/g, " ")
    .trim();
}
function extractSpecialRewardLabels(specialRewards) {
  const out = {};
  if (!isPlainObject(specialRewards)) return out;
  for (const [group, kind] of SPECIAL_REWARD_GROUPS) {
    const map = specialRewards[group];
    if (!isPlainObject(map)) continue;
    for (const [id, raw] of Object.entries(map)) {
      if (Object.prototype.hasOwnProperty.call(out, id)) continue;
      const entry = isPlainObject(raw) ? raw : {};
      const name = group === "titles"
        ? stripMiniMessage(entry.display)
        : String(entry.particle == null ? "" : entry.particle);
      out[id] = name ? `${kind}: ${name}` : `${kind}: ${id}`;
    }
  }
  return out;
}

// items/catalog.yml: items.<id>.recipe.method から、実際にゲートできる出力IDを抽出する。
// method省略は catalog.yml の既定どおり workbench。combine/netherite はこのゲート方式の対象外。
function extractCatalogGateTargets(catalog) {
  const items = catalog && catalog.items;
  const recipes = [];
  if (!isPlainObject(items)) return { recipes };
  for (const id of Object.keys(items)) {
    const item = items[id];
    const recipe = item && item.recipe;
    if (!isPlainObject(recipe)) continue;
    const method = recipe.method == null ? "workbench" : String(recipe.method).trim().toLowerCase();
    if (method === "workbench") recipes.push(id);
  }
  return { recipes: recipes.sort() };
}

// ArsPaper items.yml: ritual_effects.<id> は完成品を作るレシピではなく、
// ワールドへ作用する「儀式エフェクト」。ritual: ゲートはこのIDを対象にする。
function extractRitualEffects(itemsData) {
  const effects = itemsData && itemsData.ritual_effects;
  return isPlainObject(effects) ? Object.keys(effects).sort() : [];
}

// 2026-08-14 (実サーバ報告「解放ゲートで機能アイテムカテゴリのアイテムを設定できない」):
// recipe:/ritual: ゲートの候補が items/catalog.yml のワークベンチレシピだけだったので、
// ArsPaper 側に定義されたレシピ(機能アイテム/中間素材/儀式アイテム/ジャー/リンク/魔導書)は
// 1件もセレクトに出ていなかった。
//
// 実行時のゲートキーは「登録された Bukkit レシピの NamespacedKey のキー部分」で、それは
// ArsPaper の UnifiedRecipeLoader が各ymlのエントリIDをそのまま使う(2件目以降だけ `_r2` で
// 一意化し、RecipeUnlockGate#gateKey が基底IDへ寄せる)。つまり ArsPaper のどのymlに書かれた
// レシピでも「エントリID」で正しくゲートできる ── 候補に出ていなかっただけ。
//
// チャンネルの振り分けは method で決まる。ArsPaper の UnlockGate は
// hasRecipePermission / hasRitualPermission が【別々のマップ】を引くので、儀式アイテムを
// recipe: 側に置くと儀式経路はそのマップを一切見ず【無言で常時解放】になる
// (TrinityForge の RecipeRitualGateChannelDriftTest が固定している事故)。
// よって method: ritual は rituals へ、それ以外(既定 workbench)は recipes へ入れる。
const ARS_RECIPE_SOURCES = [
  // [sources のキー, そのymlでエントリを並べているセクション名]
  ["functionalItems", "items"],
  ["items", "items"],
  ["materials", "materials"],
  ["sourcejars", "jars"],
  ["sourcelinks", "items"]
];

// 1件=recipe: / 2件以上=recipes: の正規形(全config共通)。どちらの綴りでも拾う。
function recipeEntriesOf(entry) {
  if (!isPlainObject(entry)) return [];
  const out = [];
  if (isPlainObject(entry.recipe)) out.push(entry.recipe);
  if (Array.isArray(entry.recipes)) {
    for (const r of entry.recipes) {
      if (isPlainObject(r)) out.push(r);
    }
  }
  return out;
}

function methodOf(recipe) {
  return recipe.method == null ? "workbench" : String(recipe.method).trim().toLowerCase();
}

// レシピを持つ ArsPaper のエントリIDを method ごとに振り分ける。
function collectArsGateTargets(sources, recipes, rituals) {
  for (const [sourceKey, sectionKey] of ARS_RECIPE_SOURCES) {
    const section = sources[sourceKey] && sources[sourceKey][sectionKey];
    if (!isPlainObject(section)) continue;
    for (const id of Object.keys(section)) {
      for (const recipe of recipeEntriesOf(section[id])) {
        (methodOf(recipe) === "ritual" ? rituals : recipes).add(id);
      }
    }
  }
  // spellbooks.yml だけ配列形式 (spell-books[].id)。
  const books = sources.spellbooks && sources.spellbooks["spell-books"];
  if (Array.isArray(books)) {
    for (const book of books) {
      if (!isPlainObject(book) || !book.id) continue;
      for (const recipe of recipeEntriesOf(book)) {
        (methodOf(recipe) === "ritual" ? rituals : recipes).add(String(book.id));
      }
    }
  }
}

/**
 * @param {object} sources
 * @param {object} [sources.glyphs] glyphs.yml の生データ
 * @param {object} [sources.craftingFeatures] progression/crafting-features.yml の生データ
 * @param {object} [sources.villagerTrades] economy/villager-trades.yml の生データ
 * @param {object} [sources.gimmicks] { mining, woodcutting, digging, fishing } 各ギミックymlの生データ
 * @param {object} [sources.specialRewards] progression/special-rewards.yml の生データ (未実装なら未指定でよい)
 */
function buildGateVocabulary(sources) {
  const s = sources || {};
  const catalogTargets = extractCatalogGateTargets(s.catalog);
  const recipes = new Set(catalogTargets.recipes);
  const rituals = new Set(extractRitualEffects(s.items));
  collectArsGateTargets(s, recipes, rituals);
  return {
    glyphs: extractGlyphs(s.glyphs),
    brews: extractBrews(s.craftingFeatures),
    trades: extractTrades(s.villagerTrades),
    features: FEATURES.map((f) => ({ ...f })),
    overenchants: extractOverenchants(s.craftingFeatures),
    drops: extractDrops(s.gimmicks),
    specialRewards: extractSpecialRewards(s.specialRewards),
    specialRewardLabels: extractSpecialRewardLabels(s.specialRewards),
    recipes: [...recipes].sort(),
    rituals: [...rituals].sort()
  };
}

module.exports = { buildGateVocabulary, FEATURES };
