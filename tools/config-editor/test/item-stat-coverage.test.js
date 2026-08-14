"use strict";

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");
const YAML = require("yaml");

const root = path.resolve(__dirname, "..", "..", "..");
const catalog = YAML.parse(fs.readFileSync(path.join(root, "TrinityForge", "src", "main", "resources", "items", "catalog.yml"), "utf8"));
const stats = YAML.parse(fs.readFileSync(path.join(root, "TrinityForge", "src", "main", "resources", "stats", "item-stats.yml"), "utf8"));
const validMaterials = new Set(JSON.parse(fs.readFileSync(path.join(root, "tools", "config-editor", "lib", "materials-1.21.11.json"), "utf8")).materials);

function statKey(item) {
  return item["custom-model-data"] == null ? item.material : `${item.material}#${item["custom-model-data"]}`;
}

test("カタログの戦闘・触媒・工具・防具・補助品はすべて個別ステータス枠を持つ", () => {
  for (const tab of ["weapon", "catalyst", "tool", "armor", "other"]) {
    for (const category of catalog._editor.categories[tab] || []) {
      for (const id of category.itemIds || []) {
        const item = catalog.items[id];
        assert.ok(item, `${tab}/${category.label}/${id} がカタログにない`);
        assert.ok(stats.items[statKey(item)], `${tab}/${category.label}/${id} のステータスがない`);
      }
    }
  }
});

test("アイテムステータスのカテゴリは実際の Material#CMD キーを使う", () => {
  for (const tab of ["weapon", "catalyst", "tool", "armor", "other"]) {
    for (const catalogCategory of catalog._editor.categories[tab] || []) {
      const statsCategory = (stats._editor.categories[tab] || [])
        .find((entry) => entry.id === catalogCategory.id);
      assert.ok(statsCategory, `${tab}/${catalogCategory.label} のカテゴリがない`);
      for (const id of catalogCategory.itemIds || []) {
        const expected = statKey(catalog.items[id]);
        assert.ok(statsCategory.itemIds.includes(expected), `${tab}/${catalogCategory.label}/${id} (${expected}) の分類`);
        assert.equal(stats._editor.itemTabs[expected], tab, `${expected} の表示タブ`);
      }
    }
  }
});

test("全ステータスキーは実在Materialを使い、Editorカテゴリにも所属する", () => {
  for (const key of Object.keys(stats.items)) {
    const material = key.split("#", 1)[0];
    assert.ok(validMaterials.has(material), `${key} のMaterialが実在しない`);
    assert.ok(stats._editor.itemTabs[key], `${key} がEditorカテゴリ未割当`);
  }
});

test("バニラ防具と通常工具は素材別の品質・使用条件を持つ", () => {
  for (const material of ["LEATHER", "CHAINMAIL", "COPPER", "IRON", "GOLDEN", "DIAMOND", "NETHERITE"]) {
    for (const part of ["HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"]) {
      const entry = stats.items[`${material}_${part}`];
      assert.ok(entry?.fixed?.["armor-defense-rate"] > 0, `${material}_${part} の防具値`);
      assert.ok(entry?.fixed?.["phys-resistance"] > 0, `${material}_${part} の物理耐性`);
      assert.equal(typeof entry["quality-mode-offset"], "number", `${material}_${part} の品質基準値`);
    }
  }
  for (const material of ["WOODEN", "STONE", "COPPER", "IRON", "GOLDEN", "DIAMOND", "NETHERITE"]) {
    for (const tool of ["PICKAXE", "SHOVEL", "HOE"]) {
      const entry = stats.items[`${material}_${tool}`];
      assert.ok(entry?.fixed?.durability > 0, `${material}_${tool} の耐久`);
      assert.ok(entry?.["per-quality"]?.durability > 0, `${material}_${tool} の品質耐久`);
    }
  }
});

test("金ツールは耐久値もランダムロールする", () => {
  for (const key of ["GOLDEN_PICKAXE", "GOLDEN_SHOVEL", "GOLDEN_HOE", statKey(catalog.items.golden_axe_tool)]) {
    const entry = stats.items[key];
    assert.ok(entry?.random?.durability, `${key} のランダム耐久`);
    assert.ok(entry.random.durability.min < 0, `${key} の下振れ`);
    assert.ok(entry.random.durability.max > 0, `${key} の上振れ`);
  }
});

// 【2026-08-14 値の更新】加算幅を {-1.6, +1.65} から {-0.16, +0.2} へ。
// 旧値は「固定補正 0.95〜0.98 に対し下限が -1.6」で、品質0の中央値が 0.00〜0.03、
// ロール下限は -0.62〜-0.65 と負だった ―― つまり金装備は低品質だと最終ダメージ補正が
// ほぼゼロ〜負になり、min-component-damage:1 に張り付いて1発1ダメージ固定になっていた。
// (詳細と再較正の式は tools/config-editor/test/weapon-random-balance.test.js のコメント)。
// 新値は他帯の attack-power と同じ比率(-0.16/+0.20)。金だけが damage-modifier にも
// random を持つ、という「金はピーキー」の個性そのものは維持している。
test("金武器のダメージ補正は100%未満の固定値へ上下振れを加算し、下限で負にならない", () => {
  const goldIds = (catalog._editor.categories.weapon || [])
    .flatMap((category) => category.itemIds || [])
    .filter((id) => /golden|golad|gold_/.test(id));
  assert.ok(goldIds.length > 0, "金武器がない");
  const goldEntries = [
    ["GOLDEN_SWORD", stats.items.GOLDEN_SWORD],
    ...goldIds.map((id) => [id, stats.items[statKey(catalog.items[id])]])
  ];
  for (const [id, entry] of goldEntries) {
    const roll = entry.random["damage-modifier"];
    assert.ok(entry.fixed["damage-modifier"] < 1, `${id} の固定補正`);
    assert.equal(roll.min, -0.16, `${id} の加算下限`);
    assert.equal(roll.max, 0.2, `${id} の加算上限`);
    assert.ok(entry.fixed["damage-modifier"] + roll.min > 0,
      `${id} の最終ダメージ補正がロール下限で負になる`);
  }
});

test("ソースジェム防具と魔法系防具だけが魔法防御を持つ", () => {
  for (const id of ["source_gem_helmet", "source_gem_chestplate", "source_gem_leggings", "source_gem_boots"]) {
    const entry = stats.items[statKey(catalog.items[id])];
    assert.equal(entry["use-level-requirement"], 30, `${id} の必要Lv`);
    assert.equal(entry["quality-mode-offset"], -3, `${id} の品質基準値`);
    assert.ok(entry.fixed["thread-slots"] > 0, `${id} のスレッド枠`);
  }
  // 魔法系防具の判定は CMD番号帯(旧: /^LEATHER_.*#2000\d{2}$/)を決め打ちしない。CMD自動採番が
  // その帯域に無関係なアイテムを割り当てると誤判定で壊れるため(2026-07-25 T2)、代わりに
  // catalog.yml のカタログID命名規約(魔法系防具シリーズは "mage_" 接頭辞: mage_guardian_*/
  // mage_arcane_*/mage_weaver_* の3シリーズ×3段階×4部位=36)という、CMD番号に依存しない
  // 実データから磁法系防具の統計キー集合を導出する。
  const magicArmorKeys = new Set(
    Object.keys(catalog.items)
      .filter((id) => id.startsWith("mage_"))
      .map((id) => statKey(catalog.items[id]))
  );
  assert.equal(magicArmorKeys.size, 36, "魔法系防具の部位数");
  for (const key of magicArmorKeys) {
    const entry = stats.items[key];
    assert.ok(entry, `${key} のステータスがない`);
    assert.equal(entry.fixed["phys-resistance"], undefined, `${key} の物理耐性`);
    assert.equal(entry.fixed["phys-flat-defense"], undefined, `${key} の物理守備`);
    assert.ok(entry.fixed["magic-resistance"] > 0, `${key} の魔法耐性`);
    assert.ok(entry.fixed["magic-flat-defense"] > 0, `${key} の魔法守備`);
    assert.ok(entry.fixed["mana-bonus"] > 0, `${key} の最大マナ`);
    assert.equal(entry.fixed["max-health"], undefined, `${key} に最大体力が誤配線されている`);
  }
  for (const [key, entry] of Object.entries(stats.items)) {
    if (!entry.fixed?.["magic-resistance"] && !entry.fixed?.["magic-flat-defense"]) continue;
    assert.ok(magicArmorKeys.has(key), `${key} は魔法防御を持たない系統(mage_* カタログ以外)`);
  }
});
