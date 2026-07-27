"use strict";

// タスク2 (2026-07-27) 回帰テスト: 鍛冶/伐採/エンチャント/醸造ギミック画面でカード同士が
// くっついて余白が無かったバグの修正確認。
//
// 根本原因: tf-crafting-features.js のセクション関数(disassembly/brew/over-enchant/wood-repair)は
// 複数の entry-card を「クラス無しの生 div」へ直接 appendChild しており、この div に gap を
// 作る CSS ルールが一つも無かった。同じ役割の他画面は class="card-list" (style.css で
// display:flex; gap:12px) を使っていたため、余白の有無がクラスの有無で分かれていた。
//
// 修正: 各セクション関数の root に既存の "card-list-body" クラス(コードベース内8箇所で
// 既に使われている慣習的なクラス名)を付け、style.css 側で .card-list と同じ gap を当てる
// ルールへ統合した(新しい余白値は発明せず、既存の12pxへ揃えた)。
//
// ここではブラウザ実描画までは行わず、(1) CSSソースに .card-list-body の gap ルールが実在する、
// (2) 対象4関数の root が card-list-body クラスを持つ、をソースレベルで検証する
// (実画面での見た目確認は preview_start + Browser pane で別途実施)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const CSS_PATH = path.join(__dirname, "..", "public", "style.css");
const JS_PATH = path.join(__dirname, "..", "public", "js", "tf-crafting-features.js");
const css = fs.readFileSync(CSS_PATH, "utf8");
const js = fs.readFileSync(JS_PATH, "utf8");

test(".card-list-body に .card-list と同じ間隔(display:flex/flex-direction:column/gap)を作るCSSルールがある", () => {
  const ruleMatch = css.match(/\.card-list,\s*\.nodes-container,\s*\.card-list-body\s*\{([^}]*)\}/);
  assert.ok(ruleMatch, ".card-list, .nodes-container, .card-list-body の統合ルールが見つからない");
  const body = ruleMatch[1];
  assert.match(body, /display:\s*flex/, "display:flex が無い");
  assert.match(body, /flex-direction:\s*column/, "flex-direction:column が無い");
  assert.match(body, /gap:\s*12px/, "gap:12px が無い(既存の .card-list / .dedicated-form と同じ値であること)");
});

test(".card-list-body は他の画面(村人取引・ダンジョン系)でも使われている既存の慣習的クラス名である" +
  "(新しい値/新しいクラスを発明していないことの確認)", () => {
  const lifestyle = fs.readFileSync(
    path.join(__dirname, "..", "public", "js", "tf-lifestyle-forms.js"), "utf8");
  const matches = lifestyle.match(/class:\s*"card-list-body"/g) || [];
  assert.ok(matches.length >= 3,
    `tf-lifestyle-forms.js 内で card-list-body の使用が想定より少ない(${matches.length}件)`);
});

const SECTIONS = [
  { fn: "buildDisassemblySection", label: "鍛冶ギミック(解体)" },
  { fn: "buildBrewSection", label: "醸造ギミック(醸造解放)" },
  { fn: "buildOverEnchantSection", label: "エンチャントギミック(オーバーエンチャ)" },
  { fn: "buildWoodRepairSection", label: "伐採ギミック(木材修繕)" }
];

for (const { fn, label } of SECTIONS) {
  test(`${label}: ${fn} の root が card-list-body クラスを持つ(複数カードを直接並べるため余白が必要)`, () => {
    const fnStart = js.indexOf(`function ${fn}(`);
    assert.ok(fnStart >= 0, `${fn} が tf-crafting-features.js に見つからない`);
    const fnBody = js.slice(fnStart, fnStart + 900);
    assert.match(fnBody, /const root = h\("div",\s*\{\s*class:\s*"card-list-body"\s*\}\)/,
      `${fn} の root にクラスが付与されていない(このバグの根本原因のパターンそのもの)`);
  });
}

test("ポーション統合(buildPotionMergeSection)は単一カードのみで root に兄弟が無いため対象外のまま", () => {
  // 単一カードしか appendChild しないセクションは gap 不要(比較対象として、意図的に触らなかった
  // ことを明示しておく)。誤って壊していないかの回帰確認。
  const fnStart = js.indexOf("function buildPotionMergeSection(");
  assert.ok(fnStart >= 0);
  const fnBody = js.slice(fnStart, fnStart + 400);
  assert.match(fnBody, /const root = h\("div",\s*\{\}\)/);
});
