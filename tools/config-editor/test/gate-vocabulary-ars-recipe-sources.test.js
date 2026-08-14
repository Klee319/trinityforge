"use strict";

// 2026-08-14 実サーバ報告「解放ゲートで機能アイテムカテゴリのアイテムを設定できない」。
//
// recipe:/ritual: ゲートの候補が items/catalog.yml のワークベンチレシピだけだったので、
// ArsPaper 側に定義されたレシピ(機能アイテム/中間素材/儀式アイテム/ジャー/リンク/魔導書)は
// 1件もセレクトに出ていなかった。実行時のゲートキーは登録レシピの NamespacedKey のキー部分
// = 各ymlのエントリIDなので、これらは元々ゲート可能で「候補に出ていなかっただけ」。
//
// 一番危険なのはチャンネルの取り違え: ArsPaper の UnlockGate は recipe/ritual で別々のマップを
// 引くため、儀式アイテムを recipe: 側へ出すと【無言で常時解放】になる。method での振り分けを
// ここで固定する。

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const YAML = require("yaml");

const { buildGateVocabulary } = require("../lib/gate-vocabulary.js");

const ROOT = path.resolve(__dirname, "../../..");
const ARS_RESOURCES = path.join(ROOT, "fork-handoff/arspaper/fork/src/main/resources");

function readArsYml(name) {
  const file = path.join(ARS_RESOURCES, name);
  if (!fs.existsSync(file)) return null;
  return YAML.parse(fs.readFileSync(file, "utf8"));
}

test("機能アイテム(functional-items)のワークベンチレシピが recipe: 候補に出る", () => {
  const vocab = buildGateVocabulary({
    functionalItems: {
      items: {
        dominion_wand: { recipe: { method: "workbench" } },
        // method 省略は workbench 扱い(catalog.yml と同じ既定)。
        scribing_table: { recipe: {} }
      }
    }
  });
  assert.deepEqual(vocab.recipes, ["dominion_wand", "scribing_table"]);
  assert.deepEqual(vocab.rituals, []);
});

test("機能アイテムの儀式レシピは ritual: 側へ行く(recipe:へ出すと無言で常時解放になる)", () => {
  const vocab = buildGateVocabulary({
    functionalItems: {
      items: {
        waystone: { recipe: { method: "ritual" } },
        teleport_compass: { recipe: { method: "RITUAL" } }
      }
    }
  });
  assert.deepEqual(vocab.recipes, []);
  assert.deepEqual(vocab.rituals, ["teleport_compass", "waystone"]);
});

test("recipes: 複数形(2件以上の正規形)も拾う", () => {
  const vocab = buildGateVocabulary({
    materials: {
      materials: {
        core_wood: { recipes: [{ method: "workbench" }, { method: "workbench" }] },
        // 作業台と儀式の両方を持つエントリは両チャンネルに出る(どちらでもゲートし得る)。
        dual: { recipes: [{ method: "workbench" }, { method: "ritual" }] }
      }
    }
  });
  assert.deepEqual(vocab.recipes, ["core_wood", "dual"]);
  assert.deepEqual(vocab.rituals, ["dual"]);
});

test("spellbooks は配列形式(spell-books[].id)から拾う", () => {
  const vocab = buildGateVocabulary({
    spellbooks: {
      "spell-books": [
        { id: "spell_book_novice", recipe: { method: "workbench" } },
        { id: "spell_book_adept", recipe: { method: "ritual" } },
        { id: "", recipe: { method: "workbench" } },
        { recipe: { method: "workbench" } }
      ]
    }
  });
  assert.deepEqual(vocab.recipes, ["spell_book_novice"]);
  assert.deepEqual(vocab.rituals, ["spell_book_adept"]);
});

test("ソースが1つも無くても落ちず、空配列を返す", () => {
  const vocab = buildGateVocabulary({});
  assert.deepEqual(vocab.recipes, []);
  assert.deepEqual(vocab.rituals, []);
  const vocab2 = buildGateVocabulary({ functionalItems: null, materials: { materials: "not-a-map" } });
  assert.deepEqual(vocab2.recipes, []);
});

test("儀式エフェクト(items.yml ritual_effects)は従来どおり ritual: に残る", () => {
  const vocab = buildGateVocabulary({
    items: { ritual_effects: { animal_summon: {}, weather_clear: {} } }
  });
  assert.deepEqual(vocab.rituals, ["animal_summon", "weather_clear"]);
});

test("実データ: 出荷 ArsPaper の機能アイテムと儀式アイテムが候補に出る", () => {
  const functionalItems = readArsYml("functional-items.yml");
  const items = readArsYml("items.yml");
  if (!functionalItems || !items) {
    // fork のソースは .gitignore 除外なので、クリーンなクローンには存在しない。
    // 「無いから空」で通してしまうと検査ごと消えるので、その旨を明示して落とさずスキップする。
    console.log("skip: fork-handoff/arspaper のソースがこのワークツリーに無い");
    return;
  }
  const vocab = buildGateVocabulary({ functionalItems, items });

  // 機能アイテム: 作業台側と儀式側の代表を1件ずつ。
  assert.ok(vocab.recipes.includes("dominion_wand"),
    "機能アイテムの作業台レシピが recipe: 候補に出ていない");
  assert.ok(vocab.rituals.includes("waystone"),
    "機能アイテムの儀式レシピが ritual: 候補に出ていない");

  // 儀式アイテム(items.yml items:)。TF の RecipeRitualGateChannelDriftTest が
  // 「ritual: チャンネルでなければ機能しない」と固定している ID 群。
  for (const id of ["enchant_book_mana_regen_1", "enchant_book_mana_boost_1", "enchant_book_soulbound"]) {
    assert.ok(vocab.rituals.includes(id), `${id} が ritual: 候補に出ていない`);
    assert.ok(!vocab.recipes.includes(id),
      `${id} が recipe: 候補に出ている(儀式経路は recipe マップを見ないので無言で常時解放になる)`);
  }
});
