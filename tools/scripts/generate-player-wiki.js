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

const PAGE_NAMES = [
  "Home.md",
  "サーバーの概要.md",
  "職業スキルとスキルツリー.md",
  "モブとダンジョン.md",
  "プレイヤーステータス.md",
  "魔法.md",
  "追加アイテム.md",
  "その他の追加機能とコマンド.md"
];

const LEGACY_GENERATED_PAGE_NAMES = ["README.md"];

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
  "bow-accuracy": "弓矢のばらつきを抑えます。",
  "arrow-velocity": "矢の飛ぶ速さを上げます。",
  "distance-damage-bonus": "遠くの相手へのダメージを増やします。",
  "ammo-save-chance": "矢を消費しない確率を上げます。",
  "power-attack-damage": "空中からの強い攻撃のダメージを増やします。",
  "phys-resistance": "武器など、物理攻撃から受けるダメージを減らします。",
  "magic-resistance": "魔法攻撃から受けるダメージを減らします。",
  "phys-flat-defense": "物理攻撃を受けた時に、ダメージを一定量減らします。",
  "magic-flat-defense": "魔法攻撃を受けた時に、ダメージを一定量減らします。",
  "armor-defense-rate": "防具値を増やします。",
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

function recipeDescription(recipe, names) {
  if (!recipe || typeof recipe !== "object") return null;
  const method = recipe.method || "workbench";
  const amount = Number(recipe.amount || 1);
  const resultAmount = amount > 1 ? `（できあがり ${amount}個）` : "";
  if (method === "ritual") {
    const center = names.itemName(recipe["core-item"]);
    const pedestals = (recipe["pedestal-items"] || []).map(names.itemName).filter(Boolean).join("、");
    const parts = [center && `中央: ${center}`, pedestals && `台座: ${pedestals}`, recipe.source != null && `必要な魔力: ${formatNumber(recipe.source)}`]
      .filter(Boolean)
      .join("／");
    return `儀式で作成${resultAmount}${parts ? `（${parts}）` : ""}`;
  }
  if (method === "combine") {
    const first = names.itemName(recipe["source-item"]);
    const second = names.itemName(recipe["addition-item"]);
    return first && second ? `金床で ${first} と ${second} を合成${resultAmount}` : "金床で合成";
  }
  if (method === "netherite") {
    const source = names.itemName(recipe["source-item"]);
    return source ? `鍛冶台で ${source} をネザライト化${resultAmount}` : "鍛冶台でネザライト化";
  }
  const place = method === "inventory" ? "手元のクラフト欄" : "作業台";
  const ingredients = ingredientSummary(recipe, names);
  return `${place}で作成${resultAmount}${ingredients ? `（材料: ${ingredients}）` : ""}`;
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

function statSummary(profile, lore) {
  const sections = [
    ["fixed", ""],
    ["per-quality", "品質が1段上がるごとに "],
    ["random", "品質によって変わる範囲: "]
  ];
  const values = [];
  for (const [section, prefix] of sections) {
    for (const [key, raw] of Object.entries(profile[section] || {})) {
      const definition = (lore.stats || {})[key];
      if (!definition || !definition.name) continue;
      const value = section === "random" && raw && typeof raw === "object"
        ? `${formatStatValue(key, raw.min, lore)} 〜 ${formatStatValue(key, raw.max, lore)}`
        : formatStatValue(key, raw, lore);
      values.push(`${prefix}${plainText(definition.name)} ${value}`);
    }
  }
  return values.join("、");
}

function requirementSummary(entry, profile, skillNames) {
  const level = Number(profile["use-level-requirement"] ?? entry["use-level-requirement"] ?? 0);
  const skill = profile["use-skill"] || entry["use-skill"];
  if (!level || !skill) return "なし";
  return `${skillNames.get(skill) || "対応する職業"} Lv.${level}`;
}

function navigation() {
  return [
    "[サーバーの概要](サーバーの概要.md)",
    "[職業スキルとスキルツリー](職業スキルとスキルツリー.md)",
    "[モブとダンジョン](モブとダンジョン.md)",
    "[プレイヤーステータス](プレイヤーステータス.md)",
    "[魔法](魔法.md)",
    "[追加アイテム](追加アイテム.md)",
    "[その他の追加機能とコマンド](その他の追加機能とコマンド.md)"
  ].join(" ｜ ");
}

function generatedNotice() {
  return "> このページの一覧と数値は、現在のサーバー設定から作られています。設定を変更した後は、Wiki を再生成すると内容も更新されます。";
}

function page(title, body) {
  return `# ${title}\n\n${navigation()}\n\n${generatedNotice()}\n\n${body.trim()}\n`;
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
  const body = `## TrinityForge へようこそ

TrinityForge は、戦闘・採集・ものづくり・魔法を自分の好みに合わせて伸ばし、仲間と強敵へ挑む協力型のサーバーです。現在は **${data.skillTrees.length}種類の職業**（${skills}）を選んで育てられます。

## 最初にすること

1. \`skills\` または \`/tf skills\` で職業の画面を開きます。
2. 好きな行動を一つ選び、その行動に関係する職業を育てます。
3. 職業の画面で新しい効果や作り方を解放します。
4. 装備・魔法・回復手段を整え、より強いモンスターやダンジョンへ挑みます。

## 目標

強さは武器だけでは決まりません。職業を育て、装備に付いた補正を選び、物理攻撃と魔法を使い分けることで、各地のダンジョンを攻略していきます。

## 読む順番

- 初めてなら [サーバーの概要](サーバーの概要.md)
- 育て方を決めるなら [職業スキルとスキルツリー](職業スキルとスキルツリー.md)
- 装備を選ぶなら [プレイヤーステータス](プレイヤーステータス.md) と [追加アイテム](追加アイテム.md)
- 魔法を使うなら [魔法](魔法.md)
- 挑戦先を決めるなら [モブとダンジョン](モブとダンジョン.md)
`;
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
3. ダンジョンの敵に物理攻撃と魔法のどちらが通りやすいか、[モブとダンジョン](モブとダンジョン.md)で確認する。
4. 入場条件がある場合は、必要な戦闘レベルと入場用の品を準備する。

詳しい数値の見方は [プレイヤーステータス](プレイヤーステータス.md)、作れる物は [追加アイテム](追加アイテム.md) を参照してください。`;
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
  return page("職業スキルとスキルツリー", body);
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
  return page("モブとダンジョン", body);
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

一部の武器・道具には、使うために必要な職業レベルがあります。条件は装備ごとに異なるため、[追加アイテム](追加アイテム.md)の「使うための条件」も確認してください。`;
  return page("プレイヤーステータス", body);
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

魔法は魔法書や杖を使い、選んだ魔法を組み合わせて発動します。職業の「Ars魔法」を育てると、最大マナ、マナ回復、使える魔法の段階、魔法を置ける枠が増えます。魔法の内容を広げたい時は [職業スキルとスキルツリー](職業スキルとスキルツリー.md) の Ars魔法を確認してください。

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

魔法の素材・道具・儀式で作るものは [追加アイテム](追加アイテム.md) にまとめています。`;
  return page("魔法", body);
}

function itemGroups(data) {
  return [
    ["追加装備・道具", Object.entries(data.catalog.items || {}).map(([id, entry]) => ({ id, entry }))],
    ["魔法の素材", Object.entries(data.materials.materials || {}).map(([id, entry]) => ({ id, entry }))],
    ["魔法の機能道具", Object.entries(data.functional.items || {}).map(([id, entry]) => ({ id, entry }))],
    ["魔法の建物と貯蔵道具", [
      ...Object.entries(data.sourceJars.jars || {}).map(([id, entry]) => ({ id, entry })),
      ...Object.entries(data.sourceLinks.items || {}).map(([id, entry]) => ({ id, entry }))
    ]],
    ["儀式で作る効果付きの品", Object.entries(data.arsItems.items || {}).map(([id, entry]) => ({ id, entry, preferredName: entry.recipe && entry.recipe.name }))]
  ];
}

function buildItemsPage(data, names) {
  const skillNames = new Map(data.skillTrees.map((tree) => [tree.skill, plainText(tree["display-name"])]));
  const acquisitions = collectConfiguredAcquisitions(data, names);
  const groups = itemGroups(data).map(([title, entries]) => {
    const visible = entries.filter(({ entry }) => !isPlaceholder(entry));
    const details = visible.map(({ id, entry, preferredName }) => {
      const name = plainText(preferredName || entry["display-name"] || entry.display_name || names.baseName(id));
      if (!name) return null;
      const recipes = entryRecipes(entry).map((recipe) => recipeDescription(recipe, names)).filter(Boolean);
      const profile = matchingStats(entry, data.itemStats);
      const stats = statSummary(profile, data.lore);
      const requirement = requirementSummary(entry, profile, skillNames);
      const acquisition = acquisitions.get(name);
      const lore = (entry.lore || []).map(plainText).filter(Boolean).join(" ");
      const lines = [
        lore && `- 説明: ${markdown(lore)}`,
        recipes.length ? `- 作り方: ${recipes.map(markdown).join("<br>")}` : null,
        !recipes.length && acquisition && `- 入手先: ${[...acquisition].map(markdown).join("、")}`,
        !recipes.length && !acquisition && "- 入手先: 個別の入手方法は設定されていません",
        requirement !== "なし" && `- 使うための条件: ${markdown(requirement)}`,
        stats && `- 主な補正: ${markdown(stats)}`
      ].filter(Boolean).join("\n");
      return `<details>\n<summary>${markdown(name)}</summary>\n\n${lines}\n\n</details>`;
    }).filter(Boolean).join("\n\n");
    return `## ${title}（${visible.length}種類）\n\n${details || "現在、表示できる品はありません。"}`;
  }).join("\n\n");

  const body = `## 入手方法の見方

追加の品には、作業台で作るもの、儀式で作るもの、金床で合成するもの、鍛冶台で強化するものがあります。作り方が設定されていない品は、ガチャ、採集、釣り、村人との取引、モンスターの追加ドロップなどで入手する場合があります。設定された入手方法だけを各項目に表示しています。

## 追加アイテム一覧

${groups}`;
  return page("追加アイテム", body);
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
  const roleCommandEnabled = Boolean(data.roleBuffs["role-change"] && data.roleBuffs["role-change"]["allow-command"]);
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

## プレイヤー向けコマンド

| 入力 | できること |
| --- | --- |
| \`skills\` または \`/tf skills\` | 職業とスキルツリーの画面を開く。 |
| \`/tf stats\` | 自分に働いている主な能力を確認する。 |
| \`/tf collection\` | 図鑑の進み具合を確認する。 |
| \`/tf settings\` | 称号や演出、他人の演出表示、鉱脈掘り・一括伐採・植え直しと収穫同時・範囲収穫のオン／オフを選ぶ。 |
${roleCommandEnabled ? "| `/tf role` | 現在の役割を確認する。変更時は `/tf role set` の後で Tab キーを押して候補から選び、解除は `/tf role clear` を使う。 |" : ""}

サーバー管理者向けの操作は、権限を持つ人だけが使えます。設定を反映する操作、装備の配布、職業レベルの調整などは、プレイヤー向けのコマンドとは分けて管理されています。`;
  return page("その他の追加機能とコマンド", body);
}

function generatePages(root = PROJECT_ROOT) {
  const data = loadData(root);
  const names = createNameResolver(data);
  const home = buildHome(data);
  const pages = new Map();
  pages.set("Home.md", home);
  pages.set("サーバーの概要.md", buildOverview(data));
  pages.set("職業スキルとスキルツリー.md", buildSkillPage(data));
  pages.set("モブとダンジョン.md", buildDungeonPage(data, names));
  pages.set("プレイヤーステータス.md", buildStatsPage(data));
  pages.set("魔法.md", buildMagicPage(data, names));
  pages.set("追加アイテム.md", buildItemsPage(data, names));
  pages.set("その他の追加機能とコマンド.md", buildOtherPage(data, names));
  return pages;
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

module.exports = { PAGE_NAMES, generatePages, writePages, parseArguments, findDungeonGate, playerFacingMobName, tieredMaximumSummary, main };
