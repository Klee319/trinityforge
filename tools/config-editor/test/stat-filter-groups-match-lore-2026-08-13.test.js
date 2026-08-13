"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

// ---------------------------------------------------------------------------
// 2026-08-13 実サーバ報告
// 「ロア表示で設定・登録されているステータスで選べないものがある。
//   例としてロール収束ステがスレッド設定のセット効果でしか選べない。」
//
// 真因: item-stats 画面の「+追加」候補の絞り込みグループ (forms.js の STAT_FILTER_GROUPS) が
// 旧5分類 (attack/defense/support/ars/other) のままだった。stats/lore.yml は 2026-07-23 の
// 再編で7分類 (attack/defense/craft/gathering/utility/ars/other) になっており、
// craft/gathering/utility は「未知のカテゴリ」として許可リストから外れ、キー名ヒューリスティック
// (inferStatCategory) へ落ちて全部 support 扱いになっていた。
// = lore.yml に category: craft と宣言したステ (craft-roll-inset「ロール収束」など) は、
//   どのタブの既定ONにも入らない「補助」を手で ON にしない限り候補に出てこない。
//
// 守る不変条件は 2 つ:
//   (1) lore.yml が実際に使っている category 値は、全部そのまま絞り込みグループとして通ること
//       (= statFilterCategory が宣言値を返し、ヒューリスティックへ落ちないこと)
//   (2) クラフト系/採集系のステが、どこかのタブの既定ONで候補に出ること
// 実装文字列ではなく挙動で固定する (同じ不変条件を別の実装で満たせなくならないように)。
// ---------------------------------------------------------------------------

const ROOT = path.resolve(__dirname, "..");
const LORE_YML = path.resolve(ROOT, "../../TrinityForge/src/main/resources/stats/lore.yml");

global.window = global.window || {};
require("../public/js/editor-categories.js");
require("../public/js/forms.js");
const win = global.window;

function loreStats() {
  const doc = YAML.parse(fs.readFileSync(LORE_YML, "utf8"));
  assert.ok(doc && doc.stats && typeof doc.stats === "object", "出荷 lore.yml の stats を読めていない");
  return doc.stats;
}

/** STAT_META を差し替えて fn を走らせ、必ず元へ戻す (他テストへ漏らさない)。 */
function withStatMeta(meta, fn) {
  const saved = win.STAT_META;
  win.STAT_META = meta;
  try {
    return fn();
  } finally {
    win.STAT_META = saved;
  }
}

test("出荷 lore.yml の category 値は全部そのまま絞り込みグループとして通る", () => {
  const stats = loreStats();
  const meta = {};
  const declared = new Set();
  for (const [key, spec] of Object.entries(stats)) {
    if (!spec || typeof spec !== "object" || !spec.category) continue;
    meta[key] = { category: spec.category };
    declared.add(String(spec.category));
  }
  assert.ok(declared.size >= 5, `lore.yml の category が読めていない (${declared.size}種)`);

  withStatMeta(meta, () => {
    const fellThrough = [];
    for (const [key, spec] of Object.entries(meta)) {
      // 旧名 support は utility へ正規化してから比較する (lore.yml 側の後方互換)。
      const expected = win.normalizeStatCategory(spec.category);
      if (win.statFilterCategory(key) !== expected) fellThrough.push(`${key}(${spec.category})`);
    }
    assert.deepEqual(fellThrough, [],
      "lore.yml の宣言カテゴリが絞り込みグループに無く、キー名ヒューリスティックへ落ちている"
      + " (そのステは対応するグループを手で ON にしない限り候補に出ない)");
  });
});

test("ロール収束 (craft-roll-inset) は lore.yml の宣言どおり「クラフト」へ入る", () => {
  const stats = loreStats();
  assert.ok(stats["craft-roll-inset"], "出荷 lore.yml に craft-roll-inset が無い (この照合は空振り)");
  assert.equal(stats["craft-roll-inset"].category, "craft",
    "lore.yml 側の宣言が変わっている (テストの前提が古い)");

  withStatMeta({ "craft-roll-inset": { category: "craft" } }, () => {
    assert.equal(win.statFilterCategory("craft-roll-inset"), "craft",
      "「補助」へ落ちている (2026-08-13 報告の再発)");
  });
});

test("STAT_META が無いキーでも、クラフト系/採集系は「補助」ひとまとめにしない", () => {
  // lore.yml に category を書き忘れたキーだけがヒューリスティックへ来る。
  withStatMeta({}, () => {
    assert.equal(win.inferStatCategory("craft-roll-inset"), "craft");
    assert.equal(win.inferStatCategory("workbench-quality-luck"), "craft");
    assert.equal(win.inferStatCategory("ritual-quality-luck"), "craft");
    assert.equal(win.inferStatCategory("block-drop-fortune"), "gathering");
    assert.equal(win.inferStatCategory("fishing-luck"), "gathering");
    // 既存の分類は変えない (ここが崩れると別のステが行方不明になる)。
    assert.equal(win.inferStatCategory("attack-power"), "attack");
    assert.equal(win.inferStatCategory("armor-defense-rate"), "defense");
    assert.equal(win.inferStatCategory("mana-max"), "ars");
    assert.equal(win.inferStatCategory("item-cooldown"), "other");
    assert.equal(win.inferStatCategory("durability"), "utility");
  });
});

test("旧語彙 support は utility へ寄せる (lore.yml に残っていても未知扱いにしない)", () => {
  assert.equal(win.normalizeStatCategory("support"), "utility");
  assert.equal(win.normalizeStatCategory("utility"), "utility");
  assert.equal(win.normalizeStatCategory(""), "");
  withStatMeta({ legacy_stat: { category: "support" } }, () => {
    assert.equal(win.statFilterCategory("legacy_stat"), "utility");
  });
});

test("7分類は全部どこかのタブの既定ONに入っている (どのタブからも出せないグループを作らない)", () => {
  const forms = fs.readFileSync(path.join(ROOT, "public", "js", "forms.js"), "utf8");
  // ITEM_STATS_CAT_DEFAULTS はモジュール内定数なので、ソースから抜き出して評価する。
  const m = forms.match(/const ITEM_STATS_CAT_DEFAULTS = (\{[\s\S]*?\n  \});/);
  assert.ok(m, "ITEM_STATS_CAT_DEFAULTS を読み出せない (定義の形が変わった)");
  const defaults = new Function("return " + m[1])();

  const groups = ["attack", "defense", "craft", "gathering", "utility", "ars", "other"];
  const orphans = groups.filter((g) => !Object.values(defaults).some((tab) => tab[g] === true));
  assert.deepEqual(orphans, [],
    "どのタブでも既定OFFのグループがある (そこへ分類されたステは手で ON にしない限り出ない)");

  // 全タブが7分類そろっていること (キーが欠けると undefined = OFF に化ける)。
  for (const [tab, flags] of Object.entries(defaults)) {
    assert.deepEqual(Object.keys(flags).sort(), [...groups].sort(), `${tab} タブの分類が欠けている`);
  }
});
