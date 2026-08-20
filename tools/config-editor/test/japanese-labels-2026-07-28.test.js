"use strict";

// 2026-07-28 実サーバ報告バッチの回帰テスト。
// 「セレクトが生ID／英語のまま」「カスタムアイテムが候補に出ない」の再発を防ぐ。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.resolve(__dirname, "..");
const JS = (name) => fs.readFileSync(path.join(ROOT, "public", "js", name), "utf8");

/** labels.js / vocab-1.21.11.js を素の window に読み込んで実際の関数を得る。 */
function loadLabels() {
  global.window = {};
  // vocab が先だと ENCHANT_LABELS_JA が無いまま labels.js が動くので、実際の index.html と
  // 同じ順(labels.js → vocab-1.21.11.js)で読む。
  new Function("window", JS("labels.js"))(global.window);
  new Function("window", JS("vocab-1.21.11.js"))(global.window);
  return global.window;
}

test("PotionType の日本語辞書は labels.js に一本化されている", () => {
  const win = loadLabels();
  assert.equal(typeof win.LABELS.potionTypeLabel, "function");
  assert.equal(win.LABELS.potionTypeLabel("AWKWARD"), "奇妙なポーション");
  assert.equal(win.LABELS.potionTypeLabel("SWIFTNESS"), "俊敏");
  // 醸造ギミックのベースセレクトは自前の辞書を持たず labels.js を引くこと。
  assert.match(
    JS("tf-crafting-features.js"),
    /const BREW_BASE_LABELS = \(window\.LABELS && window\.LABELS\.POTION_TYPE_LABELS_JA\)/
  );
});

test("LONG_/STRONG_ 付きの PotionType も日本語で出る", () => {
  const win = loadLabels();
  assert.equal(win.LABELS.potionTypeLabel("LONG_SWIFTNESS"), "俊敏(延長)");
  assert.equal(win.LABELS.potionTypeLabel("STRONG_HEALING"), "治癒(強化)");
  // 辞書に無いものは空(呼び出し側で生IDへフォールバックする契約)。
  assert.equal(win.LABELS.potionTypeLabel("NOT_A_POTION"), "");
});

test("1.21.2 で改名された sweeping も日本語で出る", () => {
  const win = loadLabels();
  assert.equal(win.LABELS.enchantLabel("sweeping_edge"), "範囲ダメージ増加");
  // 出荷 yml(enchanting_progression.yml)には旧IDの sweeping 行も残っている。
  assert.equal(win.LABELS.enchantLabel("sweeping"), "範囲ダメージ増加");
});

test("旧 EntityType 名 MUSHROOM_COW は表示辞書にだけ別名を持つ", () => {
  const win = loadLabels();
  assert.equal(win.MOB_LABELS_JA.MUSHROOM_COW, "ムーシュルーム");
  // 候補リストに並べると新旧が二重に出るので、そちらには足さない。
  assert.ok(!win.VANILLA_MOBS.includes("MUSHROOM_COW"));
});

test("醸造結果EXP表の行見出しは PotionType の日本語で引く", () => {
  const src = JS("tf-forms.js");
  assert.match(src, /sectionKey === "brew_result"/);
  assert.match(src, /window\.LABELS\.potionTypeLabel/);
});

test("ポーション効果の候補は現行レジストリ名で、レガシー名を重複させない", () => {
  const src = JS("tf-crafting-features.js");
  const block = /const POTION_TYPES = \[([\s\S]*?)\];/.exec(src);
  assert.ok(block, "POTION_TYPES が見つからない");
  const ids = block[1].match(/"[A-Z_]+"/g).map((s) => s.slice(1, -1));
  assert.ok(ids.includes("JUMP_BOOST"), "JUMP ではなく現行名の JUMP_BOOST を候補にする");
  assert.ok(!ids.includes("JUMP"));
  assert.ok(!ids.includes("FAST_DIGGING"));
  assert.equal(new Set(ids).size, ids.length, "候補に重複がある");
});

test("カスタムアイテム候補はエディタ構築の共通入口で必ず読み込む", () => {
  const src = JS("app.js");
  // 画面種別ごとに呼び出しを足すと必ず漏れる(醸造ギミックで漏れていた)。
  assert.match(src, /function ensureCustomItemCandidates\(\)/);
  assert.match(
    src,
    /async function buildEditorForLoadedConfig\([\s\S]{0,300}?await ensureCustomItemCandidates\(\);/
  );
  // 取得したカタログ候補は custom: 候補としても共有登録する。
  assert.match(src, /window\.setCustomItemCandidates\(candidates, \{ replace: false \}\)/);
});

test("バニラレシピ削除は製法サフィックス付きキーも日本語で出す", () => {
  const src = JS("tf-crafting-features.js");
  assert.match(src, /function vanillaRecipeKeyLabel\(key\)/);
  assert.match(src, /\["_smithing", "鍛冶台"\]/);
});
