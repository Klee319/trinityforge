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

// --- 2026-08-16: カタログ側の取りこぼし ---------------------------------------

test("catalog の method: inventory も recipe: 候補に出す", () => {
  // Java 側 RecipeSpec#isBukkitCrafting() = workbench || inventory。どちらも
  // trinityforge:catalog_<id> として登録されるので recipe:<id> でゲートできるのに、
  // 語彙が workbench だけを見ていたため短剣8種などが1件も候補に出ていなかった。
  const catalog = {
    items: {
      iron_dagger: { recipe: { method: "inventory" } },
      core_wood: { recipe: { method: "workbench" } },
      plain: { recipe: {} }, // method 省略 = workbench
      netherite_dagger: { recipe: { method: "netherite" } }
    }
  };
  const vocab = buildGateVocabulary({ catalog });
  assert.ok(vocab.recipes.includes("iron_dagger"), "method: inventory が候補に出ていない");
  assert.ok(vocab.recipes.includes("core_wood"));
  assert.ok(vocab.recipes.includes("plain"));
  // netherite は catalog_<id>_smithing という別キーで、しかもバニラ素材の固定表でしか
  // 解決しない。recipe:<id> ではゲートできないので候補に出さないのが正しい。
  assert.ok(!vocab.recipes.includes("netherite_dagger"), "ゲートできない netherite が候補に出ている");
});

test("catalog の method: ritual は tf_catalog_<id> として ritual: 候補に出す", () => {
  // ArsPaper の CatalogRitualRegistrar が儀式レシピIDを tf_catalog_<カタログID> で登録する
  // (2件目以降は _2)。素のカタログIDではゲートキーに一致しないので、語彙側で組み立てる。
  const catalog = {
    items: {
      thread_angler: {
        "display-name": "<gold>釣り人のスレッド",
        recipe: { method: "ritual" }
      },
      dual: { recipes: [{ method: "ritual" }, { method: "ritual" }] }
    }
  };
  const vocab = buildGateVocabulary({ catalog });
  assert.ok(vocab.rituals.includes("tf_catalog_thread_angler"));
  assert.ok(vocab.rituals.includes("tf_catalog_dual"));
  assert.ok(vocab.rituals.includes("tf_catalog_dual_2"), "2件目のIDが _2 になっていない");
  assert.ok(!vocab.recipes.includes("thread_angler"), "儀式が recipe: 側にも出ている");
  // 機械名のままだとセレクトが読めないので表示ラベルを添える(MiniMessageタグは落とす)。
  assert.equal(vocab.ritualLabels["tf_catalog_thread_angler"], "釣り人のスレッド");
});

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
