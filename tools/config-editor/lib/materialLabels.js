"use strict";

// Material名 -> 日本語表示名 の辞書を提供する (表示専用。YAMLのキー/値には一切影響しない)。
//
// 優先順位 (後勝ち):
//   1) 手書き FALLBACK_LABELS (最低限)
//   2) material-labels-ja-1.21.11.json (1.21.11 公式 ja_jp 由来 + ja_items マージ)
//   3) ArsPaper ja_items.properties (あれば上書き。フォーク固有訳を優先できる)
//
// 候補一覧は materials-1.21.11.json (Paper API 1.21.11 Material enum) を正とする。

const fs = require("fs");
const path = require("path");

const CATALOG_PATH = path.join(__dirname, "materials-1.21.11.json");
const JA_BUNDLE_PATH = path.join(__dirname, "material-labels-ja-1.21.11.json");

// 主要 Material の手書きフォールバック (JSON が読めない場合の最低限)。
const FALLBACK_LABELS = Object.freeze({
  WOODEN_SWORD: "木の剣", STONE_SWORD: "石の剣", COPPER_SWORD: "銅の剣", IRON_SWORD: "鉄の剣",
  GOLDEN_SWORD: "金の剣", DIAMOND_SWORD: "ダイヤモンドの剣", NETHERITE_SWORD: "ネザライトの剣",
  WOODEN_AXE: "木の斧", STONE_AXE: "石の斧", COPPER_AXE: "銅の斧", IRON_AXE: "鉄の斧",
  GOLDEN_AXE: "金の斧", DIAMOND_AXE: "ダイヤモンドの斧", NETHERITE_AXE: "ネザライトの斧",
  WOODEN_PICKAXE: "木のツルハシ", STONE_PICKAXE: "石のツルハシ", COPPER_PICKAXE: "銅のツルハシ",
  IRON_PICKAXE: "鉄のツルハシ", GOLDEN_PICKAXE: "金のツルハシ", DIAMOND_PICKAXE: "ダイヤモンドのツルハシ",
  NETHERITE_PICKAXE: "ネザライトのツルハシ",
  WOODEN_SHOVEL: "木のシャベル", STONE_SHOVEL: "石のシャベル", COPPER_SHOVEL: "銅のシャベル",
  IRON_SHOVEL: "鉄のシャベル", GOLDEN_SHOVEL: "金のシャベル", DIAMOND_SHOVEL: "ダイヤモンドのシャベル",
  NETHERITE_SHOVEL: "ネザライトのシャベル",
  WOODEN_HOE: "木のクワ", STONE_HOE: "石のクワ", COPPER_HOE: "銅のクワ", IRON_HOE: "鉄のクワ",
  GOLDEN_HOE: "金のクワ", DIAMOND_HOE: "ダイヤモンドのクワ", NETHERITE_HOE: "ネザライトのクワ",
  WOODEN_SPEAR: "木の槍", STONE_SPEAR: "石の槍", COPPER_SPEAR: "銅の槍", IRON_SPEAR: "鉄の槍",
  GOLDEN_SPEAR: "金の槍", DIAMOND_SPEAR: "ダイヤモンドの槍", NETHERITE_SPEAR: "ネザライトの槍",
  BOW: "弓", CROSSBOW: "クロスボウ", TRIDENT: "トライデント", MACE: "メイス",
  SHIELD: "盾", FISHING_ROD: "釣竿",
  LEATHER_HELMET: "革の帽子", LEATHER_CHESTPLATE: "革の上着", LEATHER_LEGGINGS: "革のズボン", LEATHER_BOOTS: "革のブーツ",
  CHAINMAIL_HELMET: "チェーンのヘルメット", CHAINMAIL_CHESTPLATE: "チェーンのチェストプレート",
  CHAINMAIL_LEGGINGS: "チェーンのレギンス", CHAINMAIL_BOOTS: "チェーンのブーツ",
  COPPER_HELMET: "銅のヘルメット", COPPER_CHESTPLATE: "銅のチェストプレート",
  COPPER_LEGGINGS: "銅のレギンス", COPPER_BOOTS: "銅のブーツ",
  IRON_HELMET: "鉄のヘルメット", IRON_CHESTPLATE: "鉄のチェストプレート", IRON_LEGGINGS: "鉄のレギンス", IRON_BOOTS: "鉄のブーツ",
  GOLDEN_HELMET: "金のヘルメット", GOLDEN_CHESTPLATE: "金のチェストプレート", GOLDEN_LEGGINGS: "金のレギンス", GOLDEN_BOOTS: "金のブーツ",
  DIAMOND_HELMET: "ダイヤモンドのヘルメット", DIAMOND_CHESTPLATE: "ダイヤモンドのチェストプレート",
  DIAMOND_LEGGINGS: "ダイヤモンドのレギンス", DIAMOND_BOOTS: "ダイヤモンドのブーツ",
  NETHERITE_HELMET: "ネザライトのヘルメット", NETHERITE_CHESTPLATE: "ネザライトのチェストプレート",
  NETHERITE_LEGGINGS: "ネザライトのレギンス", NETHERITE_BOOTS: "ネザライトのブーツ",
  TURTLE_HELMET: "カメの甲羅", ELYTRA: "エリトラ",
  LEATHER: "革", IRON: "鉄", GOLD: "金", CHAINMAIL: "チェーン", DIAMOND: "ダイヤモンド", NETHERITE: "ネザライト"
});

function parseProperties(text) {
  const out = {};
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
    const eq = trimmed.indexOf("=");
    if (eq < 0) continue;
    const key = trimmed.slice(0, eq).trim();
    const value = trimmed.slice(eq + 1).trim();
    if (key) out[key] = value;
  }
  return out;
}

function readJsonSafe(filePath) {
  try {
    if (!fs.existsSync(filePath)) return null;
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
  } catch (_) {
    return null;
  }
}

function loadCatalogMaterials() {
  const data = readJsonSafe(CATALOG_PATH);
  if (data && Array.isArray(data.materials)) {
    return {
      version: data.version || "1.21.11",
      source: data.source || CATALOG_PATH,
      materials: data.materials.filter((m) => typeof m === "string" && m && !m.startsWith("LEGACY_"))
    };
  }
  return { version: "fallback", source: "fallback", materials: Object.keys(FALLBACK_LABELS) };
}

function loadBundledJaLabels() {
  const data = readJsonSafe(JA_BUNDLE_PATH);
  if (data && data.labels && typeof data.labels === "object") {
    return { source: data.source || JA_BUNDLE_PATH, labels: data.labels };
  }
  return { source: "fallback", labels: {} };
}

// candidatePaths のいずれかから ja_items.properties を読み込み、バンドル辞書に重ねて返す。
function buildMaterialLabels(candidatePaths) {
  const catalog = loadCatalogMaterials();
  const bundled = loadBundledJaLabels();
  const labels = { ...FALLBACK_LABELS, ...bundled.labels };
  const sources = [bundled.source];

  for (const p of candidatePaths || []) {
    if (!p) continue;
    try {
      if (!fs.existsSync(p)) continue;
      const parsed = parseProperties(fs.readFileSync(p, "utf8"));
      if (Object.keys(parsed).length === 0) continue;
      Object.assign(labels, parsed);
      sources.push(p);
    } catch (_) {
      // 読めないファイルはスキップ
    }
  }

  // カタログに無いがラベルだけあるキー (LEATHER 等の見た目素材) も残す。
  const materialSet = new Set(catalog.materials);
  for (const k of Object.keys(labels)) materialSet.add(k);
  const materials = [...materialSet].sort();

  return {
    version: catalog.version,
    catalogSource: catalog.source,
    source: sources.filter(Boolean).join(" | ") || "fallback",
    materials,
    labels
  };
}

module.exports = {
  buildMaterialLabels,
  FALLBACK_LABELS,
  parseProperties,
  loadCatalogMaterials,
  JA_ITEMS_FILENAME: "ja_items.properties"
};
