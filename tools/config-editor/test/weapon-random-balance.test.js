"use strict";

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");
const YAML = require("yaml");

const root = path.resolve(__dirname, "..", "..", "..");
const catalog = YAML.parse(fs.readFileSync(
  path.join(root, "TrinityForge", "src", "main", "resources", "items", "catalog.yml"), "utf8"));
const stats = YAML.parse(fs.readFileSync(
  path.join(root, "TrinityForge", "src", "main", "resources", "stats", "item-stats.yml"), "utf8"));

function statKey(item) {
  return item["custom-model-data"] == null ? item.material : `${item.material}#${item["custom-model-data"]}`;
}

const catalogIdByKey = new Map(Object.entries(catalog.items || {})
  .map(([id, item]) => [statKey(item), id]));

function sourceId(key) {
  return catalogIdByKey.get(key) || key.toLowerCase();
}

function isGold(key) {
  return /golden|golad|gold_/.test(sourceId(key));
}

function isEndgame(key) {
  return /wither|winter_grim|dragon|fnis_peccati|infinity/.test(sourceId(key));
}

// 2026-07-25 修正: stats._editor.orders.weapon にはカタログ実体を持たない「幽霊」登録が混ざっている
// (例: WOODEN_SWORD#16〜NETHERITE_SWORD#132 の剣系CMD帯11件)。これらは catalog.yml に対応アイテムが
// 存在せず、stats.items[key] 自体が定義されていない(fixed/random 以前にエントリそのものが無い)。
// つまり「ステータス枠」がそもそも無い = プレイヤーが装備できる実在武器ではないため、ランダム性や
// ダメージ補正のバランス検査対象にする意味が無い(検査対象が悪い=テスト前提の問題)。
// 枠(stats.items[key])が存在するのに中身が不正なケースは従来通り検出できるよう、
// 「枠が無いものだけ」を対象外にする(一律の除外はしない)。
function hasStatsEntry(key) {
  return stats.items[key] != null;
}

test("金以外の全武器は1～3個のランダムステータスを持つ", () => {
  for (const key of stats._editor.orders.weapon || []) {
    if (isGold(key)) continue;
    if (!hasStatsEntry(key)) continue;
    const randomKeys = Object.keys(stats.items[key]?.random || {});
    assert.ok(randomKeys.length >= 1 && randomKeys.length <= 3,
      `${sourceId(key)} (${key}) のrandom数=${randomKeys.length}`);
  }
});

test("エンドコンテンツ以外の武器は固定ダメージ補正が100%未満", () => {
  for (const key of stats._editor.orders.weapon || []) {
    if (isEndgame(key)) continue;
    if (!hasStatsEntry(key)) continue;
    const modifier = stats.items[key]?.fixed?.["damage-modifier"];
    assert.equal(typeof modifier, "number", `${sourceId(key)} (${key}) のdamage-modifier`);
    assert.ok(modifier < 1, `${sourceId(key)} (${key}) のdamage-modifier=${modifier}`);
  }
});

// 【2026-08-14 反転】旧版は「金は攻撃力も最終ダメージ補正もマイナスまで下振れすること」を
// 要求していた。これは仕様ではなく CRITICAL 不具合をそのまま固定していたもので、実測すると
// 金(Lv35)帯13品は品質0の中央値が実効DPS 14〜25 ―― 木の剣(Lv0) 76 / 鉄の剣(Lv25) 354 に対して
// 【Lv0装備の1/4以下】だった。原因はこの random 幅で、他帯が min/fixed ≈ -0.16 なのに
// 金だけ -1.53(約9.5倍)。ロール下限では attack-power が負になり、
// combat/damage.yml の physical.min-component-damage:1 に張り付いて【1発1ダメージ固定】になる。
// しかも金帯は quality-mode-offset: -4 なので低品質はレアケースではなく通常経路。
//
// 再較正(2026-08-14)は品質15の中央値を1つも動かさず、品質0の中央値だけ他帯と同じ
// 「q15中央の 0.495 倍」へ戻した(fixed を上げ、random を対称の ±R へ狭めた)。
// 金の個性である「他帯より広い振れ幅」は残っている: ±R/fixed ≈ 0.33 に対し、
// 他帯は -0.16/+0.21。damage-modifier に random を持つのも今でも金だけ。
//
// ここで固定するのは【振れ幅が他帯より広いこと】と【ロール下限が負にならないこと】。
// 「負まで下振れすること」を要求へ戻すと上の1ダメージ固定が再発する。
test("金武器は他帯より広く振れるが、ロール下限が負にならない", () => {
  let checked = 0;
  for (const key of stats._editor.orders.weapon || []) {
    if (!isGold(key)) continue;
    if (!hasStatsEntry(key)) continue;
    const entry = stats.items[key];
    const random = entry?.random;
    assert.ok(random, `${sourceId(key)} (${key}) のrandom`);
    if (!random["attack-power"]) continue; // 攻撃力を振らない金の道具(耐久だけ振る品)は対象外
    checked++;
    const apFixed = entry.fixed["attack-power"];
    assert.ok(random["attack-power"].min < 0 && random["attack-power"].max > 0,
      `${sourceId(key)} の攻撃力が上下に振れていない`);
    assert.ok(Math.abs(random["attack-power"].min) / apFixed > 0.25,
      `${sourceId(key)} の攻撃力の振れ幅が他帯(0.16)と大差ない`);
    assert.ok(apFixed + random["attack-power"].min > 0,
      `${sourceId(key)} の攻撃力がロール下限で負になる`
      + `(min-component-damage:1 に張り付いて1発1ダメージ固定になる)`);

    const dmFixed = entry.fixed["damage-modifier"];
    const dmRoll = random["damage-modifier"];
    if (dmRoll) {
      assert.ok(dmFixed + dmRoll.min > 0,
        `${sourceId(key)} の最終ダメージ補正がロール下限で負になる`);
      assert.ok(dmRoll.max > 0, `${sourceId(key)} の補正上振れが無い`);
    }
  }
  assert.ok(checked >= 10, `金武器を ${checked} 本しか見ていない(この検査は空振りしている)`);
});

test("木～ネザライトの槍は素材別ステータスと槍カテゴリを持つ", () => {
  const tiers = [
    ["WOODEN", 0, 0],
    ["STONE", 10, -1],
    ["COPPER", 20, -2],
    ["IRON", 30, -3],
    ["GOLDEN", 40, -4],
    ["DIAMOND", 55, -5],
    ["NETHERITE", 70, -6]
  ];
  const spearCategory = (stats._editor.categories.weapon || [])
    .find((category) => category.label === "槍");
  assert.ok(spearCategory, "槍カテゴリがない");
  for (const [material, level, offset] of tiers) {
    const key = `${material}_SPEAR`;
    const entry = stats.items[key];
    assert.ok(entry, `${key} のステータスがない`);
    assert.equal(entry["use-level-requirement"], level, `${key} の使用レベル`);
    assert.equal(entry["quality-mode-offset"], offset, `${key} の品質基準値`);
    assert.equal(entry["use-skill"], "LIGHT_WEAPONS", `${key} の対象スキル`);
    assert.ok(entry["per-quality"]?.["attack-power"], `${key} の品質別攻撃力`);
    assert.ok(spearCategory.itemIds.includes(key), `${key} が槍カテゴリにない`);
  }
});
