"use strict";

/**
 * Generates the player-facing TrinityForge Wiki from the shipped settings.
 *
 * The generator deliberately uses display names only. Internal identifiers are
 * useful to the server, but are not useful to players and must not leak into
 * the published pages.
 */

const fs = require("fs");
const path = require("path");
const { createRequire } = require("module");

const PROJECT_ROOT = path.resolve(__dirname, "../..");
const editorRequire = createRequire(path.join(PROJECT_ROOT, "tools/config-editor/package.json"));
const YAML = editorRequire("yaml");

/**
 * 手書きの「読み物」ページの出典。
 *
 * ここに書いていない `docs/wiki-source/prose/*.md` は公開されない。
 * 公開しない判断（生成側の事典と内容が重なって腐るもの）を明示的に残すため、
 * 「prose にあるものを全部出す」ではなく一覧を持たせている。
 */
const PROSE_PAGES = [
  { source: "00-はじめかた.md", page: "はじめかた.md", section: "はじめに" },
  { source: "01-このサーバってどんなゲーム.md", page: "このサーバーの遊び方.md", section: "はじめに" },
  { source: "15-バニラとの違い.md", page: "バニラとの違い.md", section: "はじめに" },
  { source: "02-戦闘のしくみ.md", page: "戦闘のしくみ.md", section: "遊び方" },
  { source: "03-ステータスと厳選.md", page: "装備とステータスの見方.md", section: "遊び方" },
  { source: "08-育成と解放.md", page: "育成とスキルツリー.md", section: "遊び方" },
  { source: "05-魔法のしくみ.md", page: "魔法のしくみ.md", section: "遊び方" },
  { source: "07-儀式とソース魔力.md", page: "儀式とソース魔力.md", section: "遊び方" },
  { source: "09-モブとダンジョン.md", page: "ダンジョン攻略の流れ.md", section: "遊び方" },
  { source: "06-呪文グリフ一覧.md", page: "呪文グリフの効果.md", section: "事典（しくみ）" },
  { source: "13-用語集.md", page: "用語集.md", section: "事典（しくみ）" },
  { source: "10-管理者ガイド-導入とコマンド.md", page: "管理者ガイド-導入とコマンド.md", section: "管理者向け" },
  { source: "11-管理者ガイド-設定リファレンス.md", page: "管理者ガイド-設定リファレンス.md", section: "管理者向け" },
  { source: "14-スキルツリー詳細.md", page: "スキルツリー詳細.md", section: "管理者向け" },
  { source: "12-実装状況と注意点.md", page: "実装状況と注意点.md", section: "管理者向け" }
];

/** 設定から生成するページ。順序がそのまま目次・サイドバーの並びになる。 */
const GENERATED_PAGES = [
  { page: "サーバーの概要.md", section: "はじめに" },
  { page: "事典-アイテム索引.md", section: "事典（アイテム）" },
  { page: "事典-軽武器.md", section: "事典（アイテム）" },
  { page: "事典-重武器.md", section: "事典（アイテム）" },
  { page: "事典-遠距離武器と杖.md", section: "事典（アイテム）" },
  { page: "事典-軽装.md", section: "事典（アイテム）" },
  { page: "事典-重装.md", section: "事典（アイテム）" },
  { page: "事典-スレッド.md", section: "事典（アイテム）" },
  { page: "事典-採集道具とその他.md", section: "事典（アイテム）" },
  { page: "事典-ダンジョンの鍵.md", section: "事典（アイテム）" },
  { page: "事典-魔法の素材.md", section: "事典（アイテム）" },
  { page: "事典-魔法の道具と設備.md", section: "事典（アイテム）" },
  { page: "事典-儀式で作る品.md", section: "事典（アイテム）" },
  { page: "事典-呪文グリフ.md", section: "事典（しくみ）" },
  { page: "事典-職業スキルとスキルツリー.md", section: "事典（しくみ）" },
  { page: "事典-モブとダンジョン.md", section: "事典（しくみ）" },
  { page: "事典-ステータス.md", section: "事典（しくみ）" },
  { page: "事典-その他の機能とコマンド.md", section: "事典（しくみ）" }
];

/** サイドバーの見出しと、その並び順。 */
const SECTION_ORDER = ["はじめに", "遊び方", "事典（アイテム）", "事典（しくみ）", "管理者向け"];

const PAGE_NAMES = [
  "Home.md",
  "_Sidebar.md",
  "_Footer.md",
  ...PROSE_PAGES.map((entry) => entry.page),
  ...GENERATED_PAGES.map((entry) => entry.page)
];

/**
 * かつて生成していて、いまは出力しないページ。存在すれば削除する。
 *
 * 2026-08-16 の再編で、1 枚 3000 行超だった「追加アイテム」を種別ごとの
 * 「事典-*」へ割り、旧名のページはすべてここへ移した。
 * 消さずに残すと、GitHub Wiki のページ一覧に古い内容が並び続ける。
 */
const LEGACY_GENERATED_PAGE_NAMES = [
  "README.md",
  "追加アイテム.md",
  "魔法.md",
  "職業スキルとスキルツリー.md",
  "モブとダンジョン.md",
  "プレイヤーステータス.md",
  "その他の追加機能とコマンド.md",
  // 手書き原稿を原稿名のまま置いていた分。正本は docs/wiki-source/prose/ にあり、
  // 公開名は PROSE_PAGES で決まるので、原稿名のページが残っていると同じ内容が二重に並ぶ。
  ...PROSE_PAGES.map((entry) => entry.source),
  "04-アイテム図鑑.md"
];

const CATEGORY_NAMES = {
  attack: "攻撃",
  defense: "防御",
  craft: "ものづくり",
  gathering: "採集",
  utility: "便利機能",
  ars: "魔法",
  other: "その他"
};

const STAT_EXPLANATIONS = {
  "attack-power": "武器で与える基本ダメージを増やします。",
  "attack-speed": "近接武器を振れる速さを変えます。",
  "attack-reach": "近接攻撃が届く距離を伸ばします。",
  "crit-chance": "大きな一撃が出る確率を上げます。",
  "crit-damage": "大きな一撃の威力を上げます。",
  penetration: "相手の防御を一部無視します。",
  "damage-modifier": "与えるダメージ全体に倍率をかけます。",
  "fixed-damage": "攻撃に一定量のダメージを加えます。",
  "bleed-chance": "出血を与える確率を上げます。",
  "bleed-damage": "出血によるダメージを上げます。",
  "bleed-damage-rate": "出血によるダメージを、出血させた一撃のダメージに応じて上げます。",
  "bow-accuracy": "弓矢のばらつきを抑えます。",
  "arrow-velocity": "矢の飛ぶ速さを上げます。",
  "distance-damage-bonus": "遠くの相手へのダメージを増やします。",
  "ammo-save-chance": "矢を消費しない確率を上げます。",
  "power-attack-damage": "空中からの強い攻撃のダメージを増やします。",
  "phys-resistance": "武器など、物理攻撃から受けるダメージを減らします。",
  "magic-resistance": "魔法攻撃から受けるダメージを減らします。",
  "phys-flat-defense": "物理攻撃を受けた時に、ダメージを一定量減らします。",
  "magic-flat-defense": "魔法攻撃を受けた時に、ダメージを一定量減らします。",
  "defense-rate": "受けるダメージを割合で減らします(相手の貫通率で打ち消されます)。",
  "armor-strength": "大きな一撃を受けた時の被害を抑えます。",
  "dodge-chance": "攻撃を回避する確率を上げます。",
  "max-health": "最大体力を増やします。",
  "move-speed": "移動する速さを上げます。",
  durability: "アイテムが壊れるまでの使用回数を増やします。",
  "mana-bonus": "魔法に使うマナの上限を増やします。",
  "mana-regen": "マナが回復する速さを上げます。",
  "thread-slots": "防具に付けられるスレッドの数を増やします。",
  "source-cost-reduction": "儀式で使う魔力を減らします。",
  "mob-drop-bonus": "モンスターから手に入る品の量を増やします。"
};

const PROFESSION_NAMES = {
  WEAPONSMITH: "武器鍛冶",
  ARMORER: "防具鍛冶",
  TOOLSMITH: "道具鍛冶",
  CLERIC: "聖職者",
  LIBRARIAN: "司書"
};

const DUNGEON_TYPE_NAMES = {
  HUB: "拠点・練習エリア",
  OPEN_DUNGEON: "開放型ダンジョン",
  DYNAMIC_DUNGEON: "難度が変化するダンジョン",
  INSTANCED_DUNGEON: "専用ダンジョン"
};

function readYaml(root, relativePath) {
  const absolutePath = path.join(root, relativePath);
  return YAML.parse(fs.readFileSync(absolutePath, "utf8")) || {};
}

function readJson(root, relativePath) {
  return JSON.parse(fs.readFileSync(path.join(root, relativePath), "utf8"));
}

function parseMaterialNames(root) {
  const source = fs.readFileSync(
    path.join(root, "fork-handoff/arspaper/fork/src/main/resources/ja_items.properties"),
    "utf8"
  );
  const labels = new Map();
  for (const line of source.split(/\r?\n/)) {
    if (!line || line.startsWith("#")) continue;
    const separator = line.indexOf("=");
    if (separator <= 0) continue;
    labels.set(line.slice(0, separator).trim(), line.slice(separator + 1).trim());
  }
  return labels;
}

function plainText(value) {
  return String(value ?? "")
    .replace(/<[^>]*>/g, "")
    .replace(/&[0-9a-fk-or]/gi, "")
    .replace(/\s+/g, " ")
    .trim();
}

function normalizeLineEndings(value) {
  return String(value).replace(/\r\n/g, "\n");
}

function markdown(value) {
  return plainText(value).replace(/\|/g, "\\|").replace(/\r?\n/g, "<br>");
}

function isPlaceholder(entry) {
  const text = [entry && entry["display-name"], entry && entry.display_name, ...(entry && entry.lore || [])]
    .map(plainText)
    .join(" ");
  return /要調整|プレースホルダ|placeholder|example/i.test(text);
}

function playerFacingMobName(value) {
  const name = plainText(value);
  const withoutPhase = name.replace(/\s*[（(]\s*第?\d+段階\s*[）)]\s*$/, "");
  if (/^[A-Za-z][A-Za-z0-9_-]*$/.test(withoutPhase) && /[0-9_-]/.test(withoutPhase)) return null;
  return name || null;
}

function formatNumber(value, decimals = 2) {
  const number = Number(value);
  if (!Number.isFinite(number)) return String(value);
  return Number.isInteger(number) ? String(number) : number.toFixed(decimals).replace(/\.0+$/, "").replace(/(\.\d*?)0+$/, "$1");
}

function formatStatValue(key, value, lore) {
  const definition = (lore.stats || {})[key] || {};
  const decimals = Number.isFinite(definition.decimals) ? definition.decimals : 2;
  const numeric = Number(value);
  const formatted = definition.format === "PERCENT" && Number.isFinite(numeric)
    ? `${formatNumber(numeric * 100, decimals)}%`
    : formatNumber(value, decimals);
  return `${formatted}${definition.unit ? ` ${definition.unit}` : ""}`;
}

function createNameResolver(data) {
  const names = new Map();
  const register = (id, entry, preferredName) => {
    const name = plainText(preferredName || entry && (entry["display-name"] || entry.display_name));
    if (id && name && !isPlaceholder(entry || {})) names.set(String(id), name);
  };

  for (const [id, entry] of Object.entries(data.catalog.items || {})) register(id, entry);
  for (const [id, entry] of Object.entries(data.materials.materials || {})) register(id, entry);
  for (const [id, entry] of Object.entries(data.functional.items || {})) register(id, entry);
  for (const [id, entry] of Object.entries(data.sourceJars.jars || {})) register(id, entry);
  for (const [id, entry] of Object.entries(data.sourceLinks.items || {})) register(id, entry);
  for (const [id, entry] of Object.entries(data.spellbooks.catalysts || {})) register(id, entry);
  for (const [id, entry] of Object.entries(data.threads.threads || {})) register(`thread_${id}`, entry, entry.display_name);
  for (const [id, entry] of Object.entries(data.arsItems.items || {})) {
    const recipe = entry.recipe || {};
    register(id, entry, recipe.name || (recipe.result && data.materialLabels.get(recipe.result)));
  }

  function baseName(raw) {
    const value = String(raw || "").trim();
    if (!value) return null;
    if (value.startsWith("list:")) {
      const listNames = { planks: "板材のいずれか" };
      return listNames[value.slice(5)] || "素材のいずれか";
    }
    const unprefixed = value.replace(/^custom:/, "");
    if (names.has(unprefixed)) return names.get(unprefixed);
    if (data.materialLabels.has(value)) return data.materialLabels.get(value);
    if (data.materialLabels.has(unprefixed)) return data.materialLabels.get(unprefixed);
    return null;
  }

  function itemName(raw) {
    const source = String(raw || "").trim();
    const quantity = /^(.*?)\s+x(\d+)$/i.exec(source);
    const name = baseName(quantity ? quantity[1] : source);
    if (!name) return null;
    return quantity ? `${name} ×${quantity[2]}` : name;
  }

  return { itemName, baseName, names };
}

function ingredientSummary(recipe, names) {
  if (Array.isArray(recipe.ingredients)) {
    return recipe.ingredients.map(names.itemName).filter(Boolean).join("、");
  }
  if (!recipe.ingredients || typeof recipe.ingredients !== "object") return "";
  if (!Array.isArray(recipe.shape)) {
    return Object.values(recipe.ingredients).map(names.itemName).filter(Boolean).join("、");
  }
  const counts = new Map();
  for (const row of recipe.shape) {
    for (const symbol of String(row)) {
      if (symbol !== " ") counts.set(symbol, (counts.get(symbol) || 0) + 1);
    }
  }
  const result = [];
  for (const [symbol, count] of counts) {
    const name = names.itemName(recipe.ingredients[symbol]);
    if (name) result.push(`${name}${count > 1 ? ` ×${count}` : ""}`);
  }
  return result.join("、");
}

function recipeResultAmount(recipe) {
  const amount = Number(recipe.amount || 1);
  return amount > 1 ? `（できあがり ${amount}個）` : "";
}

function craftingGrid(recipe, names, size) {
  if (!Array.isArray(recipe.shape) || !recipe.ingredients || typeof recipe.ingredients !== "object") return null;
  const symbols = [];
  for (const row of recipe.shape) {
    for (const symbol of String(row)) {
      if (symbol !== " " && !symbols.includes(symbol)) symbols.push(symbol);
    }
  }
  if (!symbols.length) return null;

  const labels = new Map(symbols.map((symbol, index) => [symbol, String.fromCharCode("A".charCodeAt(0) + index)]));
  const rows = Array.from({ length: size }, (_, rowIndex) => {
    const row = String(recipe.shape[rowIndex] || "").padEnd(size, " ").slice(0, size);
    return [...row].map((symbol) => labels.get(symbol) || "・");
  });
  const table = [
    `| ${rows[0].join(" | ")} |`,
    `| ${rows[0].map(() => ":---:").join(" | ")} |`,
    ...rows.slice(1).map((row) => `| ${row.join(" | ")} |`)
  ].join("\n");
  const legend = symbols.map((symbol) => {
    const ingredient = names.itemName(recipe.ingredients[symbol]) || "設定された材料";
    return `${labels.get(symbol)}：${markdown(ingredient)}`;
  }).concat("・：空欄").join("　");
  return `${table}\n\n**材料**　${legend}`;
}

function recipeMarkup(recipe, names) {
  if (!recipe || typeof recipe !== "object") return null;
  const method = recipe.method || "workbench";
  const resultAmount = recipeResultAmount(recipe);
  if (method === "ritual") {
    const center = names.itemName(recipe["core-item"]);
    const pedestals = (recipe["pedestal-items"] || []).map(names.itemName).filter(Boolean).join("、");
    const parts = [
      center && `- 中央に置く: **${markdown(center)}**`,
      pedestals && `- 台座に置く: ${markdown(pedestals)}`,
      recipe.source != null && `- 必要な魔力: ${formatNumber(recipe.source)}`
    ]
      .filter(Boolean)
      .join("\n");
    return `**儀式**${resultAmount}${parts ? `\n\n${parts}` : ""}`;
  }
  if (method === "combine") {
    const first = names.itemName(recipe["source-item"]);
    const second = names.itemName(recipe["addition-item"]);
    const parts = [
      first && `- 合成する品: **${markdown(first)}**`,
      second && `- 加える品: **${markdown(second)}**`
    ].filter(Boolean).join("\n");
    return `**金床**${resultAmount}${parts ? `\n\n${parts}` : ""}`;
  }
  if (method === "netherite") {
    const source = names.itemName(recipe["source-item"]);
    return `**鍛冶台**${resultAmount}${source ? `\n\n- 変化させる品: **${markdown(source)}**` : ""}`;
  }
  const place = method === "inventory" ? "手元のクラフト欄" : "作業台";
  const ingredients = ingredientSummary(recipe, names);
  const grid = craftingGrid(recipe, names, method === "inventory" ? 2 : 3);
  if (grid) return `**${place}**${resultAmount}\n\n${grid}`;
  return `**${place}**${resultAmount}${ingredients ? `\n\n**材料**　${markdown(ingredients)}` : ""}`;
}

function entryRecipes(entry) {
  const recipes = [];
  if (entry && entry.recipe) recipes.push(entry.recipe);
  if (entry && Array.isArray(entry.recipes)) recipes.push(...entry.recipes);
  return recipes.filter((recipe) => recipe && typeof recipe === "object");
}

function addAcquisition(target, rawItem, source, names) {
  const name = names.baseName(rawItem);
  if (!name) return;
  const known = target.get(name) || new Set();
  known.add(source);
  target.set(name, known);
}

function collectConfiguredAcquisitions(data, names) {
  const sources = new Map();
  for (const [ticket, definition] of Object.entries(data.gacha.tickets || {})) {
    const pool = data.gacha.pools && data.gacha.pools[definition.pool];
    const ticketName = names.baseName(ticket);
    if (!pool || !ticketName) continue;
    for (const entry of pool.entries || []) addAcquisition(sources, entry.item, `${ticketName}の景品`, names);
  }

  for (const [kind, config] of Object.entries({
    "採掘の追加ドロップ": data.mining,
    "伐採の追加ドロップ": data.woodcutting
  })) {
    for (const category of Object.values(config["drop-tables"] && config["drop-tables"].categories || {})) {
      for (const entry of category.entries || []) addAcquisition(sources, entry.item, kind, names);
    }
  }
  for (const group of Object.values(data.fishing.fishing && data.fishing.fishing.groups || {})) {
    for (const category of Object.values(group.categories || {})) {
      for (const entry of category.entries || []) addAcquisition(sources, entry.item, "釣り", names);
    }
  }
  for (const [profession, definition] of Object.entries(data.villagerTrades.professions || {})) {
    for (const trade of definition.trades || []) {
      const output = trade.output || {};
      addAcquisition(sources, output.catalog || output.material, `${PROFESSION_NAMES[profession] || "村人"}との取引`, names);
    }
  }
  for (const dungeon of Object.values(data.mobOverrides.overrides || {})) {
    for (const mob of Object.values(dungeon.mobs || {})) {
      for (const drop of mob.drops || []) addAcquisition(sources, drop.item || drop, "ダンジョンの追加ドロップ", names);
    }
  }
  return sources;
}

function matchingStats(entry, itemStats) {
  if (!entry || !entry.material) return {};
  const withModel = entry["custom-model-data"] != null ? `${entry.material}#${entry["custom-model-data"]}` : null;
  return (withModel && itemStats.items && itemStats.items[withModel]) || (itemStats.items && itemStats.items[entry.material]) || {};
}

function statRows(profile, section, lore) {
  return Object.entries(profile[section] || {})
    .map(([key, raw]) => ({ key, raw, definition: (lore.stats || {})[key] }))
    .filter(({ definition }) => definition && definition.name)
    .sort((left, right) => {
      const leftOrder = Number.isFinite(Number(left.definition.order)) ? Number(left.definition.order) : Number.MAX_SAFE_INTEGER;
      const rightOrder = Number.isFinite(Number(right.definition.order)) ? Number(right.definition.order) : Number.MAX_SAFE_INTEGER;
      return leftOrder - rightOrder || plainText(left.definition.name).localeCompare(plainText(right.definition.name), "ja");
    });
}

function signedStatValue(key, value, lore) {
  const numeric = Number(value);
  const formatted = formatStatValue(key, value, lore);
  return Number.isFinite(numeric) && numeric > 0 ? `+${formatted}` : formatted;
}

function statTable(headers, rows) {
  return `| ${headers.join(" | ")} |\n| ${headers.map(() => "---").join(" | ")} |\n${rows.map((row) => `| ${row.join(" | ")} |`).join("\n")}`;
}

function statMarkup(profile, lore) {
  const fixed = statRows(profile, "fixed", lore).map(({ key, raw, definition }) => [
    markdown(definition.name),
    markdown(formatStatValue(key, raw, lore))
  ]);
  const perQuality = statRows(profile, "per-quality", lore).map(({ key, raw, definition }) => [
    markdown(definition.name),
    `品質が1段上がるごとに ${markdown(signedStatValue(key, raw, lore))}`
  ]);
  const random = statRows(profile, "random", lore).map(({ key, raw, definition }) => {
    const isRange = raw && typeof raw === "object";
    const value = isRange
      ? `${markdown(formatStatValue(key, raw.min, lore))} 〜 ${markdown(formatStatValue(key, raw.max, lore))}`
      : markdown(formatStatValue(key, raw, lore));
    return [markdown(definition.name), value];
  });
  const sections = [];
  if (fixed.length) sections.push(`### 性能\n\n${statTable(["性能", "数値"], fixed)}`);
  if (perQuality.length || random.length) sections.push(`### 品質による変化\n\n${statTable(["性能", "変化"], [...perQuality, ...random])}`);
  return sections.join("\n\n");
}

function requirementSummary(entry, profile, skillNames) {
  const level = Number(profile["use-level-requirement"] ?? entry["use-level-requirement"] ?? 0);
  const skill = profile["use-skill"] || entry["use-skill"];
  if (!level || !skill) return "なし";
  return `${skillNames.get(skill) || "対応する職業"} Lv.${level}`;
}

/** ページ名（拡張子つき）から、本文中に置くリンク記法を作る。 */
function pageLink(fileName) {
  return `[${fileName.replace(/\.md$/, "")}](${fileName})`;
}

/** 全ページの先頭に置く 1 行。迷子になったときの戻り先だけを示す。 */
function navigation() {
  return `${pageLink("Home.md")} ｜ ${pageLink("はじめかた.md")} ｜ ${pageLink("バニラとの違い.md")}`;
}

function generatedNotice() {
  return "> このページの一覧と数値は、現在のサーバー設定から作られています。設定を変更した後は、Wiki を再生成すると内容も更新されます。";
}

function page(title, body) {
  return `# ${title}\n\n${navigation()}\n\n${generatedNotice()}\n\n${body.trim()}\n`;
}

/** セクション見出し → そのセクションに属するページ名の一覧。 */
function pagesBySection() {
  const sections = new Map(SECTION_ORDER.map((name) => [name, []]));
  for (const entry of [...PROSE_PAGES, ...GENERATED_PAGES]) {
    const bucket = sections.get(entry.section);
    if (!bucket) throw new Error(`未知のセクション名です: ${entry.section}`);
    bucket.push(entry.page);
  }
  return sections;
}

/**
 * GitHub Wiki のサイドバー。
 *
 * `_Sidebar.md` を置かないと、GitHub は全ページを 1 列のアルファベット順で並べる。
 * 「何から読めばいいか分からない」の直接の原因なので、読む順に並べたものを生成する。
 */
function buildSidebar() {
  const sections = [...pagesBySection()].map(([section, fileNames]) => {
    const items = fileNames.map((fileName) => `- ${pageLink(fileName)}`).join("\n");
    return `### ${section}\n\n${items}`;
  });
  return `### 入口\n\n- ${pageLink("Home.md")}\n\n${sections.join("\n\n")}\n`;
}

function buildFooter() {
  return "事典のページ（`事典-` で始まるもの）は、サーバーの設定ファイルから自動で作られています。遊び方の説明は手書きです。\n";
}

function loadData(root) {
  const data = {
    catalog: readYaml(root, "TrinityForge/src/main/resources/items/catalog.yml"),
    itemStats: readYaml(root, "TrinityForge/src/main/resources/stats/item-stats.yml"),
    lore: readYaml(root, "TrinityForge/src/main/resources/stats/lore.yml"),
    gates: readYaml(root, "TrinityForge/src/main/resources/dungeon/gates.yml"),
    mobOverrides: readYaml(root, "TrinityForge/src/main/resources/combat/mob-overrides.yml"),
    mobTypes: readYaml(root, "TrinityForge/src/main/resources/combat/mob-types.yml"),
    quality: readYaml(root, "TrinityForge/src/main/resources/stats/quality-tiers.yml"),
    crafting: readYaml(root, "TrinityForge/src/main/resources/progression/crafting-features.yml"),
    mining: readYaml(root, "TrinityForge/src/main/resources/stats/mining-gimmick.yml"),
    woodcutting: readYaml(root, "TrinityForge/src/main/resources/stats/woodcutting-gimmick.yml"),
    farming: readYaml(root, "TrinityForge/src/main/resources/stats/farming-gimmick.yml"),
    fishing: readYaml(root, "TrinityForge/src/main/resources/stats/fishing-gimmick.yml"),
    gacha: readYaml(root, "TrinityForge/src/main/resources/gacha.yml"),
    villagerTrades: readYaml(root, "TrinityForge/src/main/resources/economy/villager-trades.yml"),
    roleBuffs: readYaml(root, "TrinityForge/src/main/resources/progression/role-buffs.yml"),
    collection: readYaml(root, "TrinityForge/src/main/resources/progression/collection.yml"),
    achievements: readYaml(root, "TrinityForge/src/main/resources/progression/achievements.yml"),
    materials: readYaml(root, "fork-handoff/arspaper/fork/src/main/resources/materials.yml"),
    functional: readYaml(root, "fork-handoff/arspaper/fork/src/main/resources/functional-items.yml"),
    threads: readYaml(root, "fork-handoff/arspaper/fork/src/main/resources/threads.yml"),
    glyphs: readYaml(root, "fork-handoff/arspaper/fork/src/main/resources/glyphs.yml"),
    spellbooks: readYaml(root, "fork-handoff/arspaper/fork/src/main/resources/spellbooks.yml"),
    arsItems: readYaml(root, "fork-handoff/arspaper/fork/src/main/resources/items.yml"),
    sourceJars: readYaml(root, "fork-handoff/arspaper/fork/src/main/resources/sourcejars.yml"),
    sourceLinks: readYaml(root, "fork-handoff/arspaper/fork/src/main/resources/sourcelinks.yml"),
    dungeonManifest: readJson(root, "tools/config-editor/public/data/elitemobs-dungeons.json"),
    materialLabels: parseMaterialNames(root),
    skillTrees: [],
    skillProgression: new Map()
  };
  const skillDirectory = path.join(root, "TrinityForge/src/main/resources/skilltree");
  for (const file of fs.readdirSync(skillDirectory).filter((name) => name.endsWith(".yml")).sort()) {
    const skillTree = readYaml(root, path.join("TrinityForge/src/main/resources/skilltree", file));
    data.skillTrees.push({ file, ...skillTree });
    const progressionPath = path.join("TrinityForge/src/main/resources/skills/base", `${path.basename(file, ".yml")}_progression.yml`);
    if (fs.existsSync(path.join(root, progressionPath))) {
      data.skillProgression.set(skillTree.skill, readYaml(root, progressionPath));
    }
  }
  return data;
}

function buildHome(data) {
  const skills = data.skillTrees.map((tree) => plainText(tree["display-name"])).join("、");
  const sections = pagesBySection();
  const catalogue = [...sections]
    .filter(([section]) => section.startsWith("事典"))
    .map(([section, fileNames]) => `**${section.replace(/^事典/, "").replace(/[（）]/g, "") || "事典"}**\n\n${fileNames.map((fileName) => `- ${pageLink(fileName)}`).join("\n")}`)
    .join("\n\n");
  const admin = (sections.get("管理者向け") || [])
    .map((fileName) => `- ${pageLink(fileName)}`)
    .join("\n");
  const body = `TrinityForge は、戦闘・採集・ものづくり・魔法を自分の好みに合わせて伸ばし、仲間と強敵へ挑む協力型のサーバーです。バニラのマインクラフトを遊んだことがあれば、そのまま始められます。ただし **戦闘・エンチャント・アイテムの入手には、バニラと違うしくみが入っています**。

## はじめての人へ（この順に読んでください）

| 読むもの | 分かること |
| --- | --- |
| 1. ${pageLink("はじめかた.md")} | ログインしてから最初の 30 分で何をすればいいか |
| 2. ${pageLink("バニラとの違い.md")} | いつもの感覚のままだと損する所・危ない所 |
| 3. ${pageLink("このサーバーの遊び方.md")} | このサーバー全体で何ができるのか |

そのあとは、やりたいことから選んでください。

## やりたいことから探す

| やりたいこと | 読むもの |
| --- | --- |
| 敵と戦って勝ちたい | ${pageLink("戦闘のしくみ.md")} |
| 強い装備を選びたい・厳選したい | ${pageLink("装備とステータスの見方.md")} |
| キャラクターを育てたい | ${pageLink("育成とスキルツリー.md")} |
| 魔法を使いたい | ${pageLink("魔法のしくみ.md")} → ${pageLink("儀式とソース魔力.md")} |
| ダンジョンへ挑みたい | ${pageLink("ダンジョン攻略の流れ.md")} |
| 知らない言葉が出てきた | ${pageLink("用語集.md")} |

## 事典（サーバー設定から自動生成）

数値や一覧を引くためのページです。サーバーの設定を変えると、ここも一緒に更新されます。現在は **${data.skillTrees.length}種類の職業**（${skills}）が用意されています。

${catalogue}

## 管理者・上級者向け

${admin}`;
  return page("TrinityForge Wiki", body);
}

function buildOverview(data) {
  const qualityTiers = Object.values(data.quality.tiers || data.quality.qualities || {})
    .map((tier) => plainText(tier["display-name"] || tier.name))
    .filter(Boolean);
  const body = `## このサーバーで強くなる方法

行動に対応した職業を育てると、能力が上がるだけでなく、特殊な採集・追加の作り方・村人との取引・魔法などが使えるようになります。装備には品質とさまざまな補正があり、同じ種類の装備でも役割が変わります。

## 遊び方の基本

- **戦う**: 軽い武器、重い武器、弓、魔法のうち得意な方法を選びます。
- **集める**: 採掘、切削、伐採、農業、釣りで素材や追加の景品を集めます。
- **作る**: 作業台、儀式、金床、鍛冶台を使い、より良い装備や魔法の道具を作ります。
- **育てる**: 職業の画面で、必要なレベルに達した効果を選んで解放します。
- **挑む**: 敵が得意とする防御に合わせ、物理攻撃と魔法を使い分けます。

## 装備を使う前に

一部の武器・道具には、対応する職業の必要レベルがあります。装備の説明に条件が書かれている時は、先にその職業を育ててください。現在設定されている品質の段階は ${qualityTiers.length ? qualityTiers.join("、") : "装備ごとの品質"} です。

## ダンジョンへ行く前の確認

1. 使いたい武器の必要レベルを満たしているか確認する。
2. 食料、回復手段、予備の装備を用意する。
3. ダンジョンの敵に物理攻撃と魔法のどちらが通りやすいか、[事典-モブとダンジョン](事典-モブとダンジョン.md)で確認する。
4. 入場条件がある場合は、必要な戦闘レベルと入場用の品を準備する。

詳しい数値の見方は [事典-ステータス](事典-ステータス.md)、作れる物は [事典-アイテム索引](事典-アイテム索引.md) を参照してください。`;
  return page("サーバーの概要", body);
}

function buildSkillPage(data) {
  const skillNames = new Map(data.skillTrees.map((tree) => [tree.skill, plainText(tree["display-name"])]));
  const rows = data.skillTrees.map((tree) => {
    const progression = data.skillProgression.get(tree.skill) || {};
    const maximum = progression.experience && progression.experience.max_level;
    const prestige = tree.prestige && tree.prestige.enabled
      ? `Lv.${tree.prestige["at-level"]}から追加周回あり`
      : "なし";
    return `| ${markdown(tree["display-name"])} | ${maximum != null ? `Lv.${maximum}` : "設定なし"} | ${markdown(prestige)} |`;
  }).join("\n");

  const details = data.skillTrees.map((tree) => {
    const progression = data.skillProgression.get(tree.skill) || {};
    const nodeRows = Object.values(tree.nodes || {}).map((node) => {
      const text = node["effect-text"] || node.description || "効果の説明は設定されていません";
      return `| Lv.${node.level} | ${markdown(node.name)} | ${markdown(text)} | ${node.cost ?? 0} |`;
    }).join("\n");
    const prestige = tree.prestige && tree.prestige.enabled
      ? `\n\n**育成を最後まで進めた後**: Lv.${tree.prestige["at-level"]} から「${markdown(tree.prestige.name)}」を ${tree.prestige["max-times"] || 1}回まで行えます。効果: ${markdown(tree.prestige["effect-text"] || "設定された永続効果")}`
      : "";
    const maxLevel = progression.experience && progression.experience.max_level;
    return `<details>\n<summary>${markdown(tree["display-name"])}${maxLevel != null ? `（最大 Lv.${maxLevel}）` : ""}</summary>\n\n| 解放レベル | 効果の名前 | 内容 | 必要ポイント |\n| --- | --- | --- | --- |\n${nodeRows}${prestige}\n\n</details>`;
  }).join("\n\n");

  const body = `## 職業の育て方

職業は、対応する行動をすると経験値を得ます。レベルが上がるとポイントを使って効果を選べます。効果には、いつでも働くものと、新しい行動や作り方を使えるようにするものがあります。分かれ道では、自分が使いたい武器・魔法・採集方法に合う方を選んでください。

| 職業 | 最大レベル | 追加周回 |
| --- | --- | --- |
${rows}

## 全職業の効果

下の職業名を開くと、現在設定されている解放条件と効果を確認できます。数値や効果は、サーバーの設定変更に合わせて更新されます。

${details}

## 特殊な行動

現在、採掘には「高速破壊」の特殊行動があります。解放後、採掘用の道具を持って **しゃがみながら右クリック** すると発動します。持続時間と次に使えるまでの時間は、採掘の解放段階によって変わります。`;
  return page("事典-職業スキルとスキルツリー", body);
}

function findDungeonGate(gates, world, contentPackage) {
  for (const [destination, gate] of Object.entries(gates || {})) {
    if (!gate) continue;
    if (destination === world
      || gate["content-package"] === contentPackage
      || (gate.aliases || []).includes(contentPackage)) {
      return gate;
    }
  }
  return null;
}

function configuredDungeonRows(data, names) {
  const manifests = new Map((data.dungeonManifest.dungeons || []).map((entry) => [entry.world, entry]));
  const rows = [];
  for (const [world, dungeon] of Object.entries(data.mobOverrides.overrides || {})) {
    if (world === "default" || !dungeon["display-name"]) continue;
    const manifest = manifests.get(world) || {};
    const mobs = Object.entries(dungeon.mobs || {});
    const levels = mobs.flatMap(([id]) => [...String(id).matchAll(/(?:tier|level|lv)[_-]?(\d+)/ig)].map((match) => Number(match[1])));
    const physical = mobs.map(([, mob]) => Number(mob.stats && mob.stats.physical && mob.stats.physical["defense-rate"])).filter(Number.isFinite);
    const magical = mobs.map(([, mob]) => Number(mob.stats && mob.stats.magical && mob.stats.magical["defense-rate"])).filter(Number.isFinite);
    const average = (values) => values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null;
    const physicalDefense = average(physical);
    const magicalDefense = average(magical);
    const approach = physicalDefense != null && magicalDefense != null
      ? physicalDefense > magicalDefense + 0.04 ? "魔法が通りやすい" : magicalDefense > physicalDefense + 0.04 ? "物理攻撃が通りやすい" : "物理攻撃と魔法をどちらも用意"
      : "現地の敵の防御を確認";
    const gate = findDungeonGate(data.gates.gates, world, manifest.package);
    const entrance = gate
      ? `${gate["required-combat-level"] ? `戦闘レベル Lv.${gate["required-combat-level"]}` : "戦闘レベルの条件なし"}${gate["key-material"] ? `、入場用の品: ${names.itemName(gate["key-material"]) || "設定された入場用の品"}${gate["key-amount"] > 1 ? ` ×${gate["key-amount"]}` : ""}` : ""}`
      : "個別の入場条件なし";
    const drops = [...new Set(mobs.flatMap(([, mob]) => (mob.drops || []).map((drop) => names.itemName(drop.item || drop)).filter(Boolean)))];
    const enemyNames = mobs.map(([, mob]) => playerFacingMobName(mob["display-name"])).filter(Boolean).slice(0, 4);
    const levelText = levels.length
      ? `敵の目安 Lv.${Math.min(...levels)}〜${Math.max(...levels)}`
      : "敵の目安は現地表示を確認";
    rows.push({
      name: plainText(dungeon["display-name"]),
      type: DUNGEON_TYPE_NAMES[manifest.contentType] || "ダンジョン",
      levelText,
      approach,
      entrance,
      drops: drops.length ? drops.join("、") : "個別の追加ドロップは設定されていません",
      enemies: enemyNames.length ? `${enemyNames.join("、")}${mobs.length > enemyNames.length ? ` ほか${mobs.length - enemyNames.length}種類` : ""}` : "敵の一覧は設定されていません"
    });
  }
  return rows.sort((a, b) => a.name.localeCompare(b.name, "ja"));
}

function buildDungeonPage(data, names) {
  const dungeons = configuredDungeonRows(data, names);
  const rows = dungeons.map((dungeon) => `| ${markdown(dungeon.name)} | ${markdown(dungeon.type)} | ${markdown(dungeon.levelText)} | ${markdown(dungeon.approach)} | ${markdown(dungeon.entrance)} | ${markdown(dungeon.drops)} |`).join("\n");
  const details = dungeons.map((dungeon) => `<details>\n<summary>${markdown(dungeon.name)}</summary>\n\n- 主な敵: ${markdown(dungeon.enemies)}\n- 装備の選び方: ${markdown(dungeon.approach)}\n- 入場: ${markdown(dungeon.entrance)}\n- 目玉の追加ドロップ: ${markdown(dungeon.drops)}\n\n</details>`).join("\n\n");
  const fieldMobCount = Object.keys(data.mobTypes["mob-types"] || {}).length;
  const body = `## フィールドのモンスター

通常のモンスターは、出現する場所が遠いほど強くなります。使う武器の必要レベルと敵のレベルが近い場所から始めると、安全に戦えます。現在は ${fieldMobCount || "複数"} 種類の生き物に個別の強さが設定されています。

## ダンジョン攻略の考え方

ダンジョンの敵には、物理攻撃に強いものと、魔法に強いものがいます。下の「攻め方」を目安にし、片方の攻撃だけで苦戦する時は武器や魔法を切り替えてください。入場条件がある場所では、戦闘レベルと入場用の品が必要です。

| 場所 | 種類 | 敵の目安 | 攻め方 | 入場条件 | 目玉の追加ドロップ |
| --- | --- | --- | --- | --- | --- |
${rows || "| 現在、個別に設定されたダンジョンはありません | - | - | - | - | - |"}

## 場所ごとの詳細

${details || "現在、表示できるダンジョンの詳細はありません。"}`;
  return page("事典-モブとダンジョン", body);
}

function buildStatsPage(data) {
  const usedKeys = new Set();
  for (const profile of Object.values(data.itemStats.items || {})) {
    for (const section of ["fixed", "per-quality", "random"]) {
      for (const key of Object.keys(profile[section] || {})) usedKeys.add(key);
    }
  }
  const groups = new Map();
  for (const key of usedKeys) {
    const definition = (data.lore.stats || {})[key];
    if (!definition || !definition.name) continue;
    const group = CATEGORY_NAMES[definition.category] || "その他";
    const entries = groups.get(group) || [];
    entries.push({ key, definition });
    groups.set(group, entries);
  }
  const sections = [...groups.entries()].sort(([a], [b]) => a.localeCompare(b, "ja")).map(([group, entries]) => {
    const rows = entries.sort((a, b) => Number(a.definition.order || 0) - Number(b.definition.order || 0)).map(({ key, definition }) => {
      const explanation = STAT_EXPLANATIONS[key] || "装備に付けると、名前に書かれた能力が変わります。";
      const unit = definition.format === "PERCENT" ? "割合" : definition.unit || "数値";
      return `| ${markdown(definition.name)} | ${markdown(explanation)} | ${markdown(unit)} |`;
    }).join("\n");
    return `## ${group}\n\n| ステータス | 効果 | 表示 |\n| --- | --- | --- |\n${rows}`;
  }).join("\n\n");
  const body = `## ステータスとは

武器、防具、道具、魔法の道具には、基本の性能に加えてさまざまな補正が付きます。品質が高いほど強くなる補正や、一定の範囲で変わる補正もあります。装備を比べる時は、数字だけでなく自分の遊び方に合う効果かどうかを見てください。

## 現在の装備に設定されている補正

下の一覧は、現在の装備に実際に設定されている補正です。新しい装備や補正が設定された場合は、再生成時に一覧へ反映されます。

${sections}

## 装備の条件

一部の武器・道具には、使うために必要な職業レベルがあります。条件は装備ごとに異なるため、[事典-アイテム索引](事典-アイテム索引.md)の「使うための条件」も確認してください。`;
  return page("事典-ステータス", body);
}

function buildMagicPage(data, names) {
  const books = (data.spellbooks["spell-books"] || []).filter((book) => !isPlaceholder(book));
  const bookRows = books.map((book) => `| ${markdown(book["display-name"])} | ${book["max-slots"]}個 | ${book["max-glyph-tier"]}段階まで |`).join("\n");
  const glyphGroups = new Map();
  for (const glyph of Object.values(data.glyphs.glyphs || {})) {
    if (isPlaceholder(glyph) || !glyph["display-name"]) continue;
    const tier = Number(glyph.tier || 1);
    const group = glyphGroups.get(tier) || [];
    group.push(glyph);
    glyphGroups.set(tier, group);
  }
  const glyphSections = [...glyphGroups.entries()].sort(([a], [b]) => a - b).map(([tier, glyphs]) => {
    const rows = glyphs.sort((a, b) => plainText(a["display-name"]).localeCompare(plainText(b["display-name"]), "ja")).map((glyph) => {
      const cost = glyph["unlock-cost"] || {};
      const materials = Object.entries(cost.materials || {}).map(([material, amount]) => {
        const label = names.itemName(material);
        return label ? `${label} ×${amount}` : null;
      }).filter(Boolean).join("、");
      return `| ${markdown(glyph["display-name"])} | ${glyph["mana-cost"] ?? 0} | ${cost.level != null ? `経験値レベル ${cost.level}` : "条件なし"} | ${markdown(materials || "素材の指定なし")} |`;
    }).join("\n");
    return `<details>\n<summary>第${tier}段階の魔法（${glyphs.length}種類）</summary>\n\n| 魔法 | マナ消費 | 解放条件 | 解放に使う素材 |\n| --- | --- | --- | --- |\n${rows}\n\n</details>`;
  }).join("\n\n");
  const threadRows = Object.values(data.threads.threads || {}).filter((thread) => thread.display_name).map((thread) => {
    const effects = [
      thread["regen-bonus"] != null && `マナ回復 +${thread["regen-bonus"]}`,
      thread["mana-bonus"] != null && `最大マナ +${thread["mana-bonus"]}`,
      thread.recovery != null && `マナ回復 +${thread.recovery}`,
      thread["cost-reduction"] != null && `魔法の消費軽減 ${thread["cost-reduction"]}%`,
      thread.slots != null && `追加の持ち物枠 +${thread.slots}`
    ].filter(Boolean).join("、") || "特殊な効果";
    const count = thread.stackable ? `重ねて付けられる（最大 ${thread.max || "制限なし"}個）` : "同じ種類は1個まで";
    return `| ${markdown(thread.display_name)} | ${markdown(effects)} | ${markdown(count)} |`;
  }).join("\n");
  const body = `## 魔法の基本

魔法は魔法書や杖を使い、選んだ魔法を組み合わせて発動します。職業の「Ars魔法」を育てると、最大マナ、マナ回復、使える魔法の段階、魔法を置ける枠が増えます。魔法の内容を広げたい時は [事典-職業スキルとスキルツリー](事典-職業スキルとスキルツリー.md) の Ars魔法を確認してください。

## 魔法書

| 魔法書 | 魔法を置ける数 | 使える魔法の段階 |
| --- | --- | --- |
${bookRows || "| 現在、魔法書は設定されていません | - | - |"}

## 解放できる魔法

魔法は、経験値レベルと素材を使って解放します。魔法書が対応していない段階の魔法は使えません。

${glyphSections || "現在、解放できる魔法は設定されていません。"}

## 防具に付けるスレッド

スレッドは防具に付けて、マナや移動などを補助するものです。必要な数だけ重ねられるものもあります。

| スレッド | 効果 | 重ね方 |
| --- | --- | --- |
${threadRows || "| 現在、スレッドは設定されていません | - | - |"}

魔法の素材は ${pageLink("事典-魔法の素材.md")}、道具と設備は ${pageLink("事典-魔法の道具と設備.md")}、儀式で作るものは ${pageLink("事典-儀式で作る品.md")} にまとめています。**各グリフが実際に何をするかの説明**は ${pageLink("呪文グリフの効果.md")} にあります（この表はマナと解放条件だけを、設定から自動で作っています）。`;
  return page("事典-呪文グリフ", body);
}

/** 作り方の「場所」だけを 1 語で言い表す。一覧表の「入手」列に入れる。 */
const RECIPE_METHOD_LABELS = {
  ritual: "儀式",
  combine: "金床",
  netherite: "鍛冶台",
  inventory: "手元のクラフト欄",
  workbench: "作業台"
};

/** 一覧表に載せる「主な効果」の最大件数。多いと表が読めなくなる。 */
const SUMMARY_EFFECT_LIMIT = 3;

/**
 * カタログの品を、プレイヤーから見た種別へ割り振る表。
 *
 * 判定の軸は `item-stats.yml` の `use-skill`。これはスキルツリーの区分そのものなので、
 * 「軽武器を伸ばしているから軽武器のページを見る」という探し方がそのまま通る。
 * 素材名（NETHERITE_SWORD 等）で切ると、同じ見た目で用途の違う品が混ざる。
 *
 * 上から順に判定し、最初に当たったものを採用する（`skills` を持たない行は無条件一致）。
 */
const CATALOG_SECTIONS = [
  { page: "事典-スレッド.md", title: "スレッド", idPrefix: "thread_" },
  { page: "事典-ダンジョンの鍵.md", title: "ダンジョンの鍵", material: "TRIAL_KEY" },
  { page: "事典-軽武器.md", title: "軽武器", skills: ["LIGHT_WEAPONS"] },
  { page: "事典-重武器.md", title: "重武器", skills: ["HEAVY_WEAPONS"] },
  { page: "事典-遠距離武器と杖.md", title: "弓・クロスボウ", skills: ["ARCHERY"] },
  { page: "事典-遠距離武器と杖.md", title: "魔法の杖", skills: ["ARS_MAGIC"] },
  { page: "事典-軽装.md", title: "軽装", skills: ["LIGHT_ARMOR"] },
  { page: "事典-重装.md", title: "重装", skills: ["HEAVY_ARMOR"] },
  {
    page: "事典-採集道具とその他.md",
    title: "採集道具",
    skills: ["MINING", "DIGGING", "WOODCUTTING", "FARMING", "FISHING", "SMITHING", "ALCHEMY", "ENCHANTING", "ARS_SMITHING"]
  },
  { page: "事典-採集道具とその他.md", title: "その他の品" }
];

/**
 * まだプレイヤーへ配っていない品かどうか。
 *
 * `draft: true` はエディタの「準備中」で、`ItemCatalogConfig` が `template()` / `all()` から
 * 除いているのでゲーム側には存在しない。Wiki に載せると「あるはずの品が手に入らない」になる。
 */
function isUnreleased(entry) {
  return Boolean(entry && entry.draft === true);
}

/** カタログの 1 件が属する種別。CATALOG_SECTIONS の並び順で最初に当たったもの。 */
function catalogSectionOf(id, entry, itemStats) {
  const material = String(entry.material || "");
  const profile = matchingStats(entry, itemStats);
  const skill = profile["use-skill"] || entry["use-skill"];
  for (const section of CATALOG_SECTIONS) {
    if (section.idPrefix && !id.startsWith(section.idPrefix)) continue;
    if (section.material && material !== section.material) continue;
    if (section.skills && !section.skills.includes(skill)) continue;
    return section;
  }
  throw new Error(`種別を決められない品があります: ${id}（CATALOG_SECTIONS の最後は無条件一致にしてください）`);
}

/**
 * 品の一覧。`page` が同じものは 1 ページにまとめて出る。
 *
 * 2026-08-16 まではカタログ 420 件を 1 ページへ縦に並べていて 1 万行を超えていた。
 * 目次から辿り着いても目的の品まで延々スクロールすることになるので、種別でページを割る。
 */
function itemGroups(data) {
  const catalogEntries = new Map(CATALOG_SECTIONS.map((section) => [section, []]));
  for (const [id, entry] of Object.entries(data.catalog.items || {})) {
    catalogEntries.get(catalogSectionOf(id, entry, data.itemStats)).push({ id, entry });
  }
  return [
    ...CATALOG_SECTIONS
      .map((section) => ({ page: section.page, title: section.title, entries: catalogEntries.get(section) }))
      .filter((group) => group.entries.length),
    {
      page: "事典-魔法の素材.md",
      title: "魔法の素材",
      entries: Object.entries(data.materials.materials || {}).map(([id, entry]) => ({ id, entry }))
    },
    {
      page: "事典-魔法の道具と設備.md",
      title: "魔法の機能道具",
      entries: Object.entries(data.functional.items || {}).map(([id, entry]) => ({ id, entry }))
    },
    {
      page: "事典-魔法の道具と設備.md",
      title: "魔法の建物と貯蔵道具",
      entries: [
        ...Object.entries(data.sourceJars.jars || {}).map(([id, entry]) => ({ id, entry })),
        ...Object.entries(data.sourceLinks.items || {}).map(([id, entry]) => ({ id, entry }))
      ]
    },
    {
      page: "事典-儀式で作る品.md",
      title: "儀式で作る効果付きの品",
      entries: Object.entries(data.arsItems.items || {}).map(([id, entry]) => ({ id, entry, preferredName: entry.recipe && entry.recipe.name }))
    }
  ];
}

/** 一覧表の「入手」列。作り方が設定されていればその場所、無ければ設定済みの入手経路。 */
function acquisitionSummary(recipes, acquisition) {
  if (recipes.length) {
    const places = [...new Set(recipes.map((recipe) => RECIPE_METHOD_LABELS[recipe.method || "workbench"] || "作業台"))];
    return places.join("・");
  }
  if (acquisition && acquisition.size) return [...acquisition].join("、");
  return "設定なし";
}

/** この品に付く補正の名前を、詳細と同じ並びで重複なく並べたもの。 */
function effectNames(profile, lore) {
  const names = [];
  for (const section of ["fixed", "per-quality", "random"]) {
    for (const { definition } of statRows(profile, section, lore)) {
      const name = plainText(definition.name);
      if (name && !names.includes(name)) names.push(name);
    }
  }
  return names;
}

/**
 * 一覧表の「主な効果」列。
 *
 * lore の並び順どおりに先頭 3 件を出すと、防具はどの行も「移動速度、体力増強、防御率」になって
 * 表として何も区別できなくなる。そこで<b>同じページの中で珍しい補正を先に</b>出す。
 * こうすると「この品にしか付いていないもの」が列に残り、選ぶ手がかりになる。
 */
function effectSummary(profile, lore, frequency) {
  const names = effectNames(profile, lore);
  if (!names.length) return "補正なし";
  const ranked = names
    .map((name, index) => ({ name, index, count: (frequency && frequency.get(name)) || 0 }))
    .sort((left, right) => left.count - right.count || left.index - right.index)
    .map(({ name }) => name);
  const shown = ranked.slice(0, SUMMARY_EFFECT_LIMIT).join("、");
  return ranked.length > SUMMARY_EFFECT_LIMIT ? `${shown} ほか${ranked.length - SUMMARY_EFFECT_LIMIT}件` : shown;
}

/** 事典の品ページ。1 群 = 「見渡すための一覧表」＋「畳んだ詳細」の 2 段構え。 */
const ITEM_PAGE_LEADS = {
  "事典-軽武器.md": `**軽武器**スキルで扱う、素早く振れる近接武器の一覧です。重い武器は ${"${事典-重武器}"} にあります。`,
  "事典-重武器.md": `**重武器**スキルで扱う、一撃の重い近接武器の一覧です。軽い武器は ${"${事典-軽武器}"} にあります。`,
  "事典-遠距離武器と杖.md": `離れて戦う武器の一覧です。弓・クロスボウは**弓術**、杖は**Ars魔法**のスキルで扱います。`,
  "事典-軽装.md": `**軽装**スキルで扱う防具の一覧です。回避や移動を得意とします。重い防具は ${"${事典-重装}"} にあります。`,
  "事典-重装.md": `**重装**スキルで扱う防具の一覧です。攻撃に耐えることを得意とします。軽い防具は ${"${事典-軽装}"} にあります。`,
  "事典-スレッド.md": `防具のスレッド枠に挿して、効果を足すための品です。魔法の遊び方は ${"${魔法のしくみ}"} を参照してください。`,
  "事典-採集道具とその他.md": `採集に使う道具と、上のどの分類にも入らない品の一覧です。`,
  "事典-ダンジョンの鍵.md": `ダンジョンへ入るための鍵の一覧です。どこで使うかは ${"${ダンジョン攻略の流れ}"} を参照してください。`,
  "事典-魔法の素材.md": `魔法（Ars）で使う素材の一覧です。

魔法そのものの遊び方は ${"${魔法のしくみ}"}、儀式のやり方は ${"${儀式とソース魔力}"} を先に読んでください。`,
  "事典-魔法の道具と設備.md": `魔法（Ars）の機能道具と、魔力を貯める設備の一覧です。

魔法そのものの遊び方は ${"${魔法のしくみ}"}、儀式のやり方は ${"${儀式とソース魔力}"} を先に読んでください。`,
  "事典-儀式で作る品.md": `儀式（ぎしき）でしか作れない、効果の付いた品の一覧です。

儀式のやり方そのものは ${"${儀式とソース魔力}"} にあります。`
};

function itemPageLead(fileName) {
  const template = ITEM_PAGE_LEADS[fileName] || "";
  return template.replace(/\$\{([^}]+)\}/g, (_, name) => pageLink(`${name}.md`));
}

/** 事典（アイテム）の入口。どのページに何が載っているかだけを示す。 */
function buildItemIndexPage(data) {
  const counts = new Map();
  for (const { page: fileName, title, entries } of itemGroups(data)) {
    const visible = entries.filter(({ entry }) => !isPlaceholder(entry) && !isUnreleased(entry)).length;
    const current = counts.get(fileName) || { titles: [], total: 0 };
    current.titles.push(title);
    current.total += visible;
    counts.set(fileName, current);
  }
  const rows = [...counts].map(([fileName, { titles, total }]) =>
    `| ${pageLink(fileName)} | ${total}種類 | ${markdown(titles.join("、"))} |`).join("\n");
  const body = `追加されている品は、探しやすいように種別ごとのページへ分けてあります。**どのページにも、まず一覧表があります。** 目当ての品を表で見つけてから、その下の折りたたみを開いて数値を確認してください。

| ページ | 収録数 | 載っているもの |
| --- | --- | --- |
${rows}

## 探し方のこつ

- **武器や防具を選びたい** … 自分が育てているスキル（軽武器・重武器・弓術・軽装・重装）のページを見てください。装備の分類はスキルツリーの区分とそろえてあります。
- **数値の意味が分からない** … ${pageLink("事典-ステータス.md")} に、どの補正が何をするかをまとめています。
- **どうやって手に入るか知りたい** … 各ページの一覧表に「入手」の列があります。作り方が設定されている品は、そこに作る場所（作業台・儀式・金床・鍛冶台）が出ます。
- **同じ名前なのに性能が違う** … 品質とランダムな補正のためです。詳しくは ${pageLink("装備とステータスの見方.md")} を読んでください。`;
  return page("事典-アイテム索引", body);
}

function buildItemPages(data, names) {
  const skillNames = new Map(data.skillTrees.map((tree) => [tree.skill, plainText(tree["display-name"])]));
  const acquisitions = collectConfiguredAcquisitions(data, names);
  const byPage = new Map();
  for (const { page: fileName, title, entries } of itemGroups(data)) {
    const visible = entries.filter(({ entry }) => !isPlaceholder(entry) && !isUnreleased(entry));
    // この群の中で、どの補正が何件に付いているか。一覧表では珍しいものを先に出す。
    const frequency = new Map();
    for (const { entry } of visible) {
      for (const name of effectNames(matchingStats(entry, data.itemStats), data.lore)) {
        frequency.set(name, (frequency.get(name) || 0) + 1);
      }
    }
    const rows = [];
    const details = visible.map(({ id, entry, preferredName }) => {
      const name = plainText(preferredName || entry["display-name"] || entry.display_name || names.baseName(id));
      if (!name) return null;
      const recipes = entryRecipes(entry).map((recipe) => recipeMarkup(recipe, names)).filter(Boolean);
      const profile = matchingStats(entry, data.itemStats);
      const stats = statMarkup(profile, data.lore);
      const requirement = requirementSummary(entry, profile, skillNames);
      const acquisition = acquisitions.get(name);
      const lore = (entry.lore || []).map(plainText).filter(Boolean);
      rows.push(`| ${markdown(name)} | ${markdown(acquisitionSummary(entryRecipes(entry), acquisition))} | ${markdown(requirement)} | ${markdown(effectSummary(profile, data.lore, frequency))} |`);
      const sections = [
        recipes.length && `### 作り方\n\n${recipes.join("\n\n---\n\n")}`,
        // 入手方法が無い品にまで「設定されていません」を書くと、420 件ぶんの空行が積み上がる。
        // 一覧表の「入手」列に同じことが出ているので、詳細側では黙って省く。
        !recipes.length && acquisition && `### 入手方法\n\n${[...acquisition].map(markdown).join("、")}`,
        requirement !== "なし" && `### 使用条件\n\n**${markdown(requirement)}**`,
        stats,
        lore.length && `### 説明\n\n> ${lore.map(markdown).join("<br>\n> ")}`
      ].filter(Boolean).join("\n\n");
      return `<details>\n<summary><strong>${markdown(name)}</strong></summary>\n\n${sections}\n\n</details>`;
    }).filter(Boolean).join("\n\n");
    const summary = rows.length
      ? `| 名前 | 入手 | 使用条件 | 主な効果 |\n| --- | --- | --- | --- |\n${rows.join("\n")}`
      : "現在、表示できる品はありません。";
    const section = `## ${title}（${rows.length}種類）\n\n${summary}\n\n### ${title}の詳細\n\n${details || "現在、表示できる品はありません。"}`;
    const bucket = byPage.get(fileName) || [];
    bucket.push(section);
    byPage.set(fileName, bucket);
  }

  const pages = new Map();
  for (const [fileName, sections] of byPage) {
    const title = fileName.replace(/\.md$/, "");
    const body = `${itemPageLead(fileName)}

## 入手の見方

作り方が設定されている品は「作業台・手元のクラフト欄・儀式・金床・鍛冶台」のどれかで作れます。作り方が設定されていない品は、ガチャ、採集、釣り、村人との取引、モンスターの追加ドロップなどで手に入ります。ここには、設定されている入手方法だけを載せています。

${sections.join("\n\n")}`;
    pages.set(fileName, page(title, body));
  }
  return pages;
}

function configuredDropRows(data, names) {
  const rows = [];
  for (const [activity, config] of Object.entries({ 採掘: data.mining, 伐採: data.woodcutting })) {
    for (const category of Object.values(config["drop-tables"] && config["drop-tables"].categories || {})) {
      const items = (category.entries || []).map((entry) => names.itemName(entry.item)).filter(Boolean).join("、");
      if (items) rows.push(`| ${activity} | ${markdown(category["display-name"] || "追加ドロップ")} | ${markdown(items)} | ${category["trigger-chance-percent"] ?? "設定なし"}% |`);
    }
  }
  for (const [group, definition] of Object.entries(data.fishing.fishing && data.fishing.fishing.groups || {})) {
    for (const category of Object.values(definition.categories || {})) {
      const items = (category.entries || []).map((entry) => names.itemName(entry.item)).filter(Boolean).join("、");
      if (items) rows.push(`| 釣り（${group === "treasure" ? "宝" : "その他"}） | ${markdown(category["display-name"] || "追加ドロップ")} | ${markdown(items)} | 設定された抽選 |`);
    }
  }
  return rows.join("\n");
}

function secondsFromTicks(value) {
  return `${formatNumber(Number(value || 0) / 20, 1)}秒`;
}

function tieredMiningSummary(mining) {
  const tiers = Object.entries(mining.tiers || {})
    .sort(([first], [second]) => Number(first) - Number(second))
    .map(([tier, definition]) => `段階${tier}: 持続 ${secondsFromTicks(definition["duration-ticks"] ?? mining["duration-ticks"])}、強さ ${definition.amplifier ?? mining.amplifier}`);
  return tiers.join("／") || `持続 ${secondsFromTicks(mining["duration-ticks"])}、強さ ${mining.amplifier ?? "設定なし"}`;
}

function tieredMaximumSummary(feature, key, unit) {
  const tiers = Object.entries(feature.tiers || {})
    .sort(([first], [second]) => Number(first) - Number(second))
    .map(([tier, definition]) => `段階${tier}: 追加で最大 ${definition[key] ?? feature[key] ?? 0}${unit}`);
  return tiers.join("／") || `追加で最大 ${feature[key] || 0}${unit}`;
}

function buildOtherPage(data, names) {
  const mining = data.mining["haste-active-mining"] || {};
  const treeFell = data.woodcutting["tree-fell"] || {};
  // 2026-08-05 (W-28): 変更の動線は /tf status のロールアイコンだけになり、キーは
  // allow-command → allow-change へ改名。false は「変更不可」ではなく
  // 「初回の就職以外は転職の証が必要」なので、コマンド表そのものは常に出す。
  const roleChange = data.roleBuffs["role-change"] || {};
  const roleChangeAllowed = roleChange["allow-change"] === undefined
    ? (roleChange["allow-command"] === undefined ? true : Boolean(roleChange["allow-command"]))
    : Boolean(roleChange["allow-change"]);
  const roleNames = [
    ...Object.values(data.roleBuffs["combat-roles"] || {}).map((role) => role.label),
    ...Object.values(data.roleBuffs["support-roles"] || {}).map((role) => role.label)
  ].filter(Boolean).map(markdown).join("、");
  const gachaRows = Object.entries(data.gacha.tickets || {}).map(([ticket, definition]) => {
    const ticketName = names.baseName(ticket);
    const pool = data.gacha.pools && data.gacha.pools[definition.pool];
    if (!ticketName || !pool) return null;
    const prizes = (pool.entries || []).map((entry) => names.itemName(entry.item)).filter(Boolean).join("、");
    return prizes ? `| ${markdown(ticketName)} | ${markdown(prizes)} | ${pool.pity && pool.pity.threshold ? `${pool.pity.threshold}回で救済あり` : "設定なし"} |` : null;
  }).filter(Boolean).join("\n");
  const tradeRows = Object.entries(data.villagerTrades.professions || {}).flatMap(([profession, definition]) => (definition.trades || []).map((trade) => {
    const input = trade.input || {};
    const output = trade.output || {};
    const from = names.itemName(input.material) || "設定された素材";
    const to = names.itemName(output.catalog || output.material) || "設定された品";
    return `| ${PROFESSION_NAMES[profession] || "村人"} | ${markdown(`${from} ×${input.amount || 1}`)} | ${markdown(`${to} ×${output.amount || 1}`)} |`;
  })).join("\n");
  const body = `## 採集と生活の追加機能

| 機能 | 現在の設定 |
| --- | --- |
| 採掘の高速破壊 | しゃがみながら右クリックで発動。解放段階ごとの持続時間と強さ: ${tieredMiningSummary(mining)}。次に使えるまで ${secondsFromTicks(mining["cooldown-ticks"])}。 |
| 鉱脈掘り | 解放段階ごとの効果: ${tieredMaximumSummary(data.mining["vein-mining"] || {}, "max-extra-blocks", "ブロック")}を掘ります。 |
| 一括伐採 | 解放段階ごとの効果: ${tieredMaximumSummary(treeFell, "max-extra-logs", "本分")}の原木を壊します。次に使えるまで ${secondsFromTicks(treeFell["cooldown-ticks"])}。 |
| 釣り | 宝・その他の品が設定された比率で釣れます。魚は自動で売れる場合があります。 |
| 解体 | 金床を使って、一部の不要な品を素材に戻せます。 |
| 武器コーティング | 素材を使って、武器に追加ダメージを与えられます。 |

## 採集・釣りで手に入る追加の品

| 行動 | 種類 | 手に入る品 | 確率 |
| --- | --- | --- | --- |
${configuredDropRows(data, names) || "| 現在、追加の景品は設定されていません | - | - | - |"}

## ガチャ

ガチャ券を右クリックすると、対応する景品の中から1つ受け取れます。

| ガチャ券 | 主な景品 | 救済 |
| --- | --- | --- |
${gachaRows || "| 現在、ガチャ券は設定されていません | - | - |"}

## 村人との追加取引

これらの取引は、対応する職業の効果を解放した後に使えます。

| 村人 | 渡す品 | 受け取る品 |
| --- | --- | --- |
${tradeRows || "| 現在、追加取引は設定されていません | - | - |"}

## 役割

現在選べる役割は ${roleNames || "設定されていません"} です。役割は戦い方や得意な作業に合わせて選べます。

役割は \`/tf status\` の「職業(ロール)」のアイコンから確認・変更できます。${roleChangeAllowed
    ? "一度選ぶと、次に変えられるようになるまで待ち時間があります。"
    : "最初の就職だけ自由に選べます。そのあと変えるには「転職の証」を手に持って右クリックしてください。"}

## プレイヤー向けコマンド

| 入力 | できること |
| --- | --- |
| \`skills\` または \`/tf skills\` | 職業とスキルツリーの画面を開く。 |
| \`/tf stats\` | 自分に働いている主な能力を確認する。 |
| \`/tf collection\` | 図鑑の進み具合を確認する。 |
| \`/tf settings\` | 称号や演出、他人の演出表示、鉱脈掘り・一括伐採・植え直しと収穫同時・範囲収穫のオン／オフを選ぶ。 |
| \`/tf status\` | 自分の能力と役割を画面で確認する。役割のアイコンから変更もできる。 |
| \`/tf role\` | 現在の役割と、その効果を文字で確認する。${roleChangeAllowed ? "解除は `/tf role clear`。" : ""} |

サーバー管理者向けの操作は、権限を持つ人だけが使えます。設定を反映する操作、装備の配布、職業レベルの調整などは、プレイヤー向けのコマンドとは分けて管理されています。`;
  return page("事典-その他の機能とコマンド", body);
}

const PROSE_SOURCE_DIRECTORY = "docs/wiki-source/prose";

/**
 * 手書き原稿どうしの相互リンクは原稿名（`02-戦闘のしくみ.md`）で書かれている。
 * Wiki では別名で公開するので、そのまま複製すると全部 404 になる。
 *
 * 公開しない原稿（`04-アイテム図鑑.md`）は、内容を引き継いだ生成ページへ向ける。
 */
function proseLinkTargets() {
  const targets = new Map(PROSE_PAGES.map(({ source, page: fileName }) => [source, fileName]));
  targets.set("README.md", "Home.md");
  // 手書きの「アイテム図鑑」は公開しない（生成側と重複してすぐ腐る）。
  // 代わりに、種別ごとのページへ振り分ける索引を行き先にする。
  targets.set("04-アイテム図鑑.md", "事典-アイテム索引.md");
  return targets;
}

/** 原稿内の `](<原稿名>.md)` と `](<原稿名>.md#見出し)` を公開ページ名へ差し替える。 */
function rewriteProseLinks(markdownText, targets, source) {
  return markdownText.replace(/\]\(([^)#]+\.md)(#[^)]*)?\)/g, (whole, target, anchor) => {
    const replacement = targets.get(target);
    if (replacement) return `](${replacement}${anchor || ""})`;
    // 原稿名の形をしているのに行き先が無いものは、公開後に 404 になる。
    if (/^\d\d-.+\.md$/.test(target) || target === "README.md") {
      throw new Error(`${source} のリンク先 "${target}" は公開されないページです。PROSE_PAGES か差し替え表を見直してください。`);
    }
    return whole;
  });
}

/**
 * 手書きページを Wiki の体裁へ整えて取り込む。
 *
 * 手書き原稿はリポジトリ内（`docs/wiki-source/prose/`）が正本で、Wiki 側は複製。
 * ここで戻り先の 1 行を差し込むのは、GitHub Wiki には「1 つ上へ戻る」導線が無く、
 * 深いページに直接飛んできた読者が迷子になるため。
 */
function buildProsePages(root) {
  const pages = new Map();
  const missing = [];
  const targets = proseLinkTargets();
  for (const { source, page: fileName } of PROSE_PAGES) {
    const absolutePath = path.join(root, PROSE_SOURCE_DIRECTORY, source);
    if (!fs.existsSync(absolutePath)) {
      missing.push(source);
      continue;
    }
    const original = rewriteProseLinks(
      normalizeLineEndings(fs.readFileSync(absolutePath, "utf8")).trimEnd(),
      targets,
      source
    );
    const lines = original.split("\n");
    // 原稿の 1 行目は必ず `# 見出し`。その直後へ戻り先を差し込む。
    const heading = lines[0].startsWith("# ") ? lines[0] : `# ${fileName.replace(/\.md$/, "")}`;
    const rest = lines[0].startsWith("# ") ? lines.slice(1).join("\n").replace(/^\n+/, "") : original;
    pages.set(fileName, `${heading}\n\n${navigation()}\n\n${rest}\n`);
  }
  if (missing.length) {
    throw new Error(
      `手書きページの原稿が見つかりません: ${missing.join(", ")}\n`
        + `${PROSE_SOURCE_DIRECTORY}/ に置くか、PROSE_PAGES から外してください。`
    );
  }
  return pages;
}

function stripPageExtensionFromLinks(markdownText) {
  return markdownText.replace(/\]\(([^)#\s]+)\.md(#[^)]*)?\)/g, (whole, target, anchor) => `](${target}${anchor || ""})`);
}

function generatePages(root = PROJECT_ROOT) {
  const data = loadData(root);
  const names = createNameResolver(data);
  const pages = new Map();
  pages.set("Home.md", buildHome(data));
  pages.set("_Sidebar.md", buildSidebar());
  pages.set("_Footer.md", buildFooter());
  pages.set("サーバーの概要.md", buildOverview(data));
  pages.set("事典-職業スキルとスキルツリー.md", buildSkillPage(data));
  pages.set("事典-モブとダンジョン.md", buildDungeonPage(data, names));
  pages.set("事典-ステータス.md", buildStatsPage(data));
  pages.set("事典-呪文グリフ.md", buildMagicPage(data, names));
  pages.set("事典-その他の機能とコマンド.md", buildOtherPage(data, names));
  pages.set("事典-アイテム索引.md", buildItemIndexPage(data));
  for (const [fileName, content] of buildItemPages(data, names)) pages.set(fileName, content);
  for (const [fileName, content] of buildProsePages(root)) pages.set(fileName, content);

  // 出力一覧と PAGE_NAMES がずれると、サイドバーにだけ載っていて実体の無いリンクが出る。
  const expected = new Set(PAGE_NAMES);
  const unexpected = [...pages.keys()].filter((fileName) => !expected.has(fileName));
  const notBuilt = [...expected].filter((fileName) => !pages.has(fileName));
  if (unexpected.length || notBuilt.length) {
    throw new Error(
      `PAGE_NAMES と生成結果が一致しません。`
        + `${unexpected.length ? ` 余分: ${unexpected.join(", ")}` : ""}`
        + `${notBuilt.length ? ` 不足: ${notBuilt.join(", ")}` : ""}`
    );
  }
  // 生成物どうしのリンク切れは、公開して踏むまで気づけない。ここで全部たどって落とす。
  const dangling = [];
  for (const [fileName, content] of pages) {
    for (const match of content.matchAll(/\]\(([^)#\s]+\.md)(#[^)]*)?\)/g)) {
      if (!pages.has(match[1])) dangling.push(`${fileName} → ${match[1]}`);
    }
  }
  if (dangling.length) {
    throw new Error(`存在しないページへのリンクがあります:\n  ${dangling.join("\n  ")}`);
  }

  // ここまでは `.md` 付きで組み立てて検査する（原稿もページ名も `.md` 付きなので突き合わせが素直）。
  // 公開直前に拡張子を落とす。GitHub Wiki のページ URL は `/wiki/<ページ名>` で、`.md` を付けると
  // ページとして解決されない ── ASCII 名は raw.githubusercontent.com へ 302（生の md が落ちてくる）、
  // 非 ASCII 名は `/wiki/` へ 302（Wiki トップへ飛ばされる）。どちらも「リンクが全部死ぬ」に見える。
  for (const [fileName, content] of pages) {
    pages.set(fileName, stripPageExtensionFromLinks(content));
  }

  // 並びは PAGE_NAMES（= サイドバーの並び）に合わせて返す。組み立てた順は関係ない。
  return new Map(PAGE_NAMES.map((fileName) => [fileName, pages.get(fileName)]));
}

function writePages(pages, wikiDirectory, checkOnly) {
  const changed = [];
  for (const [fileName, content] of pages) {
    const target = path.join(wikiDirectory, fileName);
    const current = fs.existsSync(target) ? fs.readFileSync(target, "utf8") : null;
    if (current != null && normalizeLineEndings(current) === normalizeLineEndings(content)) continue;
    changed.push(fileName);
    if (!checkOnly) fs.writeFileSync(target, content, "utf8");
  }
  for (const fileName of LEGACY_GENERATED_PAGE_NAMES) {
    if (pages.has(fileName)) continue;
    const target = path.join(wikiDirectory, fileName);
    if (!fs.existsSync(target)) continue;
    changed.push(fileName);
    if (!checkOnly) fs.unlinkSync(target);
  }
  return changed;
}

function parseArguments(argv) {
  const result = { wikiDirectory: path.join(PROJECT_ROOT, "wiki"), checkOnly: false };
  for (let index = 0; index < argv.length; index += 1) {
    if (argv[index] === "--check") result.checkOnly = true;
    else if (argv[index] === "--wiki-dir") {
      const value = argv[index + 1];
      if (!value) throw new Error("--wiki-dir には出力先を指定してください。");
      result.wikiDirectory = path.resolve(value);
      index += 1;
    } else if (argv[index] === "--help") {
      result.help = true;
    } else {
      throw new Error(`不明な指定: ${argv[index]}`);
    }
  }
  return result;
}

function main(argv = process.argv.slice(2)) {
  const options = parseArguments(argv);
  if (options.help) {
    process.stdout.write("使い方: node tools/scripts/generate-player-wiki.js [--check] [--wiki-dir <出力先>]\n");
    return 0;
  }
  const pages = generatePages(PROJECT_ROOT);
  const changed = writePages(pages, options.wikiDirectory, options.checkOnly);
  if (options.checkOnly) {
    if (changed.length) {
      process.stderr.write(`Wiki を再生成する必要があります: ${changed.join(", ")}\n`);
      return 1;
    }
    process.stdout.write("Wiki は現在の設定と一致しています。\n");
    return 0;
  }
  process.stdout.write(changed.length ? `Wiki を更新しました: ${changed.join(", ")}\n` : "Wiki はすでに最新です。\n");
  return 0;
}

if (require.main === module) {
  try {
    process.exitCode = main();
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}

module.exports = { PAGE_NAMES, PROSE_PAGES, GENERATED_PAGES, generatePages, writePages, parseArguments, findDungeonGate, playerFacingMobName, tieredMaximumSummary, main };
