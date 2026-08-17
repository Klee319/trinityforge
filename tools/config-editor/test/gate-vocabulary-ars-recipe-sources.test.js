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

  // 2026-08-18 (W-52): 「候補に出るか」だけでなく「ラベルが生IDではないこと」も固定する。
  // ここが無かったため、以前は recipe:pedestal / ritual:waystone のセレクトが
  // `カスタム: pedestal` のような生ID表示のままだった。
  assert.equal(vocab.recipeLabels.dominion_wand, "ドミニオンワンド");
  assert.equal(vocab.ritualLabels.waystone, "ウェイストーン");

  // 儀式アイテム(items.yml items:)。TF の RecipeRitualGateChannelDriftTest が
  // 「ritual: チャンネルでなければ機能しない」と固定している ID 群。
  for (const id of ["enchant_book_mana_regen_1", "enchant_book_mana_boost_1", "enchant_book_soulbound"]) {
    assert.ok(vocab.rituals.includes(id), `${id} が ritual: 候補に出ていない`);
    assert.ok(!vocab.recipes.includes(id),
      `${id} が recipe: 候補に出ている(儀式経路は recipe マップを見ないので無言で常時解放になる)`);
    assert.ok(vocab.ritualLabels[id] && vocab.ritualLabels[id] !== id,
      `${id} のラベルが付いていない/生IDのまま`);
  }
});

// ============================================================
// 2026-08-18 (W-52): 実サーバ報告「解放ゲートのセレクトに生アイテムIDが出る」の再発防止。
//
// 機構A(このファイル): recipe:/ritual: ゲートのラベル辞書(recipeLabels/ritualLabels)。
// 「候補にIDが出るか」に加えて「そのラベルが生ID・`カスタム: <id>`のままではないこと」を
// 機械的に固定する。特定IDを列挙する許可リスト方式は使わない ── 出荷 yml を実読みし、
// display-name(または name / display_name)を持つ全キーを母集合として検査する。
// ============================================================

test("解放ゲート機構A: 儀式エフェクト(items.yml ritual_effects)の name: がラベルになる", () => {
  const vocab = buildGateVocabulary({
    items: {
      ritual_effects: {
        weather_clear: { name: "晴天の儀式" },
        no_name: {}
      }
    }
  });
  assert.equal(vocab.ritualLabels.weather_clear, "晴天の儀式");
  assert.ok(!vocab.ritualLabels.no_name, "name: を持たないエントリにラベルを捏造してはいけない");
});

test("解放ゲート機構A: materials.yml は snake_case (display_name) でもラベルが付く", () => {
  const vocab = buildGateVocabulary({
    materials: {
      materials: {
        source_gem: { display_name: "&bソースジェム", recipe: { method: "ritual" } }
      }
    }
  });
  assert.ok(vocab.rituals.includes("source_gem"));
  assert.equal(vocab.ritualLabels.source_gem, "ソースジェム",
    "materials.yml の display_name(snake_case)からラベルが引けていない");
});

test("解放ゲート機構A: sourcelinks.yml のレシピにもラベルが付く(recipe/ritual 両方へマージされる)", () => {
  const vocab = buildGateVocabulary({
    sourcelinks: {
      items: {
        volcanic_sourcelink: { "display-name": "ヴォルカニックソースリンク", recipe: { method: "ritual" } }
      }
    }
  });
  assert.ok(vocab.rituals.includes("volcanic_sourcelink"));
  assert.equal(vocab.ritualLabels.volcanic_sourcelink, "ヴォルカニックソースリンク");
  // recipeLabels 側にも同じラベルが merge されている(1つのIDが workbench/ritual 両方の
  // レシピを持つケースがあるため、method に関わらずラベル自体は両方へ載せる設計)。
  assert.equal(vocab.recipeLabels.volcanic_sourcelink, "ヴォルカニックソースリンク");
});

test("実データ監査: display-name/display_name/name を持つ全ArsPaperキーに生ID以外のラベルが付く", () => {
  const functionalItems = readArsYml("functional-items.yml");
  const materials = readArsYml("materials.yml");
  const sourcejars = readArsYml("sourcejars.yml");
  const sourcelinks = readArsYml("sourcelinks.yml");
  const items = readArsYml("items.yml");
  const spellbooks = readArsYml("spellbooks.yml");
  if (!functionalItems || !materials || !sourcejars || !sourcelinks || !items || !spellbooks) {
    console.log("skip: fork-handoff/arspaper のソースがこのワークツリーに無い");
    return;
  }
  const vocab = buildGateVocabulary({ functionalItems, materials, sourcejars, sourcelinks, items, spellbooks });

  function assertLabeled(id, source) {
    const inRecipes = vocab.recipes.includes(id);
    const inRituals = vocab.rituals.includes(id);
    if (!inRecipes && !inRituals) return; // ゲート候補にすら出ないキーは対象外
    const label = inRituals ? vocab.ritualLabels[id] : vocab.recipeLabels[id];
    assert.ok(label, `[${source}] ${id} にラベルが付いていない(display-name/nameを持つのに生ID表示になる)`);
    assert.notEqual(label, id, `[${source}] ${id} のラベルが生IDのまま`);
  }

  let audited = 0;
  const sections = [
    [functionalItems.items, "display-name", "functional-items.items"],
    [materials.materials, "display_name", "materials.materials"],
    [sourcejars.jars, "display-name", "sourcejars.jars"],
    [sourcelinks.items, "display-name", "sourcelinks.items"]
  ];
  for (const [section, nameKey, label] of sections) {
    if (!section) continue;
    for (const [id, entry] of Object.entries(section)) {
      if (!entry || typeof entry !== "object" || entry[nameKey] == null || entry[nameKey] === "") continue;
      audited += 1;
      assertLabeled(id, label);
    }
  }
  const effects = items.ritual_effects;
  if (effects) {
    for (const [id, entry] of Object.entries(effects)) {
      if (!entry || !entry.name) continue;
      audited += 1;
      assertLabeled(id, "items.ritual_effects");
    }
  }
  const books = spellbooks["spell-books"];
  if (Array.isArray(books)) {
    for (const book of books) {
      if (!book || !book.id || !book["display-name"]) continue;
      audited += 1;
      assertLabeled(String(book.id), "spellbooks.spell-books");
    }
  }
  assert.ok(audited >= 40, `母集合が想定より少ない(${audited}件)。yml のパースが壊れていないか確認する`);
});
