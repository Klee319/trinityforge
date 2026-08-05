"use strict";

// 2026-07-26: コンパニオン config がマージされたときに applyMergedToEditor が何もせず戻る問題の回帰テスト。
//
// 背景: 1画面が複数ファイルを保存する「コンパニオン」構成(例: プレイヤー基礎ステータス画面の
// combat/stat-caps.yml、醸造ギミック画面の stats/alchemy-quality.yml)では、保存時の楽観ロックが
// **物理パス単位**で効く。したがってコンパニオン側だけが衝突→マージされることがある。
// 従来の applyMergedToEditor には tf-quality(craft-quality) と tf-skill-exp(progression-*) の
// 2スキーマぶんしか再構築分岐が無く、それ以外のコンパニオンでは関数が黙って return していた。
// 結果: サーバ側のリビジョンだけ進み、画面はマージ前のまま。次に保存するとマージ内容が消える。
//
// ここでは app.js のソーステキストに対して構造的に検査する(ブラウザ専用モジュールで、DOM 無しでは
// 実行できないため。同ディレクトリの tf-stat-caps-registry.test.js と同じ方式)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const appJsSrc = fs.readFileSync(path.join(__dirname, "..", "public", "js", "app.js"), "utf8");

function companionOptionKeyMap() {
  const m = appJsSrc.match(/const COMPANION_OPTION_KEYS = \{([\s\S]*?)\};/);
  assert.ok(m, "COMPANION_OPTION_KEYS の定義が見つからない");
  const map = {};
  const re = /"([a-z0-9-]+)"\s*:\s*"([A-Za-z0-9_]+)"/g;
  let hit;
  while ((hit = re.exec(m[1])) !== null) map[hit[1]] = hit[2];
  return map;
}

test("app.js: COMPANION_OPTION_KEYS が定義されている", () => {
  const map = companionOptionKeyMap();
  assert.ok(Object.keys(map).length > 0, "COMPANION_OPTION_KEYS が空");
});

test("app.js: applyMergedToEditor に汎用コンパニオン分岐がある", () => {
  const m = appJsSrc.match(/async function applyMergedToEditor\([\s\S]*?\n  \}\n/);
  assert.ok(m, "applyMergedToEditor の本体を切り出せない(関数の形が変わった?)");
  assert.match(m[0], /COMPANION_OPTION_KEYS\[configId\]/,
    "applyMergedToEditor がコンパニオンidを COMPANION_OPTION_KEYS で引いていない"
    + "(マージ結果が画面に反映されず、次の保存で消える)");
  assert.match(m[0], /buildEditorForLoadedConfig\(/,
    "汎用分岐でエディタを組み立て直していない");
});

test("app.js: 汎用分岐は他コンパニオンの未保存編集を getExtraSaves から引き継ぐ", () => {
  const m = appJsSrc.match(/const optKey = COMPANION_OPTION_KEYS\[configId\];[\s\S]*?\n      \}/);
  assert.ok(m, "汎用分岐のブロックを切り出せない");
  assert.match(m[0], /getExtraSaves/,
    "同一画面の他コンパニオンを getExtraSaves から引き継いでいない"
    + "(再取得すると未保存編集を捨てる)");
});

test("app.js: 専用分岐(tf-skill-exp)はフォールスルーせず return する", () => {
  // 汎用分岐を後ろに足したので、専用分岐が return しないと二重に組み立ててしまう。
  const m = appJsSrc.match(/buildSkillExpForm\(skillExpData, progression\);[\s\S]*?\n      \}/);
  assert.ok(m, "tf-skill-exp コンパニオン分岐を切り出せない");
  assert.match(m[0], /return;/, "tf-skill-exp 分岐が return していない(汎用分岐へフォールスルーする)");
});

test("ドリフト検知: loadConfigCompanion の (id, optKey) が全て COMPANION_OPTION_KEYS に載っている", () => {
  const map = companionOptionKeyMap();
  const re = /loadConfigCompanion\(\s*"([a-z0-9-]+)"\s*,\s*"([A-Za-z0-9_]+)"/g;
  const missing = [];
  const mismatched = [];
  let hit;
  while ((hit = re.exec(appJsSrc)) !== null) {
    const [, id, optKey] = hit;
    if (!(id in map)) missing.push(`${id} -> ${optKey}`);
    else if (map[id] !== optKey) mismatched.push(`${id}: map=${map[id]} call=${optKey}`);
  }
  assert.deepEqual(missing, [],
    "loadConfigCompanion で読んでいるのに COMPANION_OPTION_KEYS に無いコンパニオン"
    + "(このファイルがマージされると画面へ反映されない)");
  assert.deepEqual(mismatched, [], "COMPANION_OPTION_KEYS と呼び出し側で opts キーが食い違っている");
});

test("gathering-efficiency は COMPANION_OPTION_KEYS に載せない(編集経路が無いため)", () => {
  const map = companionOptionKeyMap();
  assert.ok(!("gathering-efficiency" in map),
    "gathering-efficiency は画面から編集する経路が無い(2026-08-05 に stat-caps.yml 側の"
    + "上書き行を撤去し、max-enchant-level は yml 直編集に戻った)。"
    + "載せると存在しない opts キーを渡すことになる");
});
