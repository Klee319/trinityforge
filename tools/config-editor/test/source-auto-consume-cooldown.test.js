"use strict";

// 2026-08-14: ソース自動消費のクールタイム(mana.source-auto-consume.cooldown-seconds)を
// editor から設定できるようにした件のガード。
// 実装の要点は「空欄 = キーを書かない = ArsPaper 既定値10秒」と「0 = CT無し」が別物であること。
// ここを取り違えて空欄を0へ丸めると、開いて保存しただけで CT が無効化される(しかも yml 上は
// 見た目が増えるだけなので気づけない)。

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { validate } = require("../lib/schema.js");

const ROOT = path.resolve(__dirname, "../../..");
const CF_SOURCE = fs.readFileSync(
  path.join(ROOT, "tools/config-editor/public/js/tf-crafting-features.js"), "utf8");
const P3_SOURCE = fs.readFileSync(
  path.join(ROOT, "tools/config-editor/public/js/tf-phase3-forms.js"), "utf8");

// tf-phase3-forms.js はブラウザ専用の DOM 部品ファイルなので require できない。
// 行の値(数値 or {mana, cooldown-seconds})を組み立てる純粋関数だけ、ソースから切り出して評価する。
function loadRowHelpers() {
  const start = P3_SOURCE.indexOf("function sacManaOf(");
  const end = P3_SOURCE.indexOf("window.SOURCE_AUTO_CONSUME_ROW");
  assert.ok(start > 0 && end > start, "純粋関数ブロックが見つからない(名前を変えたらこのテストも直す)");
  // eslint-disable-next-line no-new-func
  return new Function(P3_SOURCE.slice(start, end) + "; return { sacManaOf, sacCooldownOf, sacValue };")();
}

test("ars-config スキーマ: cooldown-seconds は0以上の整数だけ通す", () => {
  // validate() はエラー文字列の配列をそのまま返す(オブジェクトではない)。
  assert.deepEqual(validate("ars-config",
    { mana: { "source-auto-consume": { "cooldown-seconds": 10, items: { source_berry: 100 } } } }), []);

  assert.deepEqual(validate("ars-config",
    { mana: { "source-auto-consume": { "cooldown-seconds": 0, items: {} } } }), [],
  "0(CT無し)は正当な設定なので弾いてはいけない");

  assert.ok(validate("ars-config",
    { mana: { "source-auto-consume": { "cooldown-seconds": -1, items: {} } } })
    .some((e) => e.includes("cooldown-seconds")), "負値は弾く");

  assert.ok(validate("ars-config",
    { mana: { "source-auto-consume": { "cooldown-seconds": 1.5, items: {} } } })
    .some((e) => e.includes("cooldown-seconds")), "小数は弾く");
});

test("ars-config スキーマ: cooldown-seconds 未設定は正当(既定値10秒に委ねる)", () => {
  assert.deepEqual(validate("ars-config",
    { mana: { "source-auto-consume": { items: { source_berry: 100 } } } }), [],
  "未設定を必須にすると、CTを持たない既存の config.yml が開けなくなる");
});

test("その他のギミック画面: ソース自動消費タブにCT欄がある", () => {
  assert.ok(CF_SOURCE.includes("自動消費のCT(秒"),
    "「ソース自動消費」タブにCT欄が無い(ユーザー依頼: 自動消費のCTをeditorで設定できるように)");
  assert.ok(CF_SOURCE.includes('sourceAutoConsume["cooldown-seconds"]'),
    "CT欄が mana.source-auto-consume.cooldown-seconds を読み書きしていない");
});

test("その他のギミック画面: CT欄の空欄はキーごと削除する(0へ丸めない)", () => {
  const idx = CF_SOURCE.indexOf("自動消費のCT(秒");
  assert.ok(idx > 0);
  const snippet = CF_SOURCE.slice(idx, idx + 400);
  assert.ok(snippet.includes('delete sourceAutoConsume["cooldown-seconds"]'),
    "空欄でキーを消していない。0へ丸めると『既定10秒』ではなく『CT無し』になり意味が変わる");
});

// ---- 2026-08-14 追加: アイテムごとのマナ回復量とCT ----
// ユーザー報告「ソースベリー 100 とあるがマナ回復量とCTがそれぞれ設定できるべきでは？」。
// 数値欄にラベルが無く、すぐ上の全体CT欄と区別がつかなかったのも同時に直した。

test("行の値: 旧記法(数値のみ)はマナ回復量として読み、CTは未設定(全体既定)扱い", () => {
  const { sacManaOf, sacCooldownOf } = loadRowHelpers();
  assert.equal(sacManaOf(100), 100);
  assert.equal(sacCooldownOf(100), null,
    "数値だけの行を『CT=その数値』と誤読すると、既存configのマナ量がそのままCTに化ける");
});

test("行の値: 新記法({mana, cooldown-seconds})は両方読む。0は『CT無し』として残す", () => {
  const { sacManaOf, sacCooldownOf } = loadRowHelpers();
  assert.equal(sacManaOf({ mana: 100, "cooldown-seconds": 5 }), 100);
  assert.equal(sacCooldownOf({ mana: 100, "cooldown-seconds": 5 }), 5);
  assert.equal(sacCooldownOf({ mana: 100, "cooldown-seconds": 0 }), 0,
    "0 は『CT無し』という設定であって未設定ではない");
  assert.equal(sacCooldownOf({ mana: 100 }), null, "キーが無ければ全体既定に従う");
});

test("行の保存形: CT未設定なら数値だけの短い形へ戻す(往復で差分を作らない)", () => {
  const { sacValue } = loadRowHelpers();
  assert.equal(sacValue(100, null), 100);
  assert.deepEqual(sacValue(100, 5), { mana: 100, "cooldown-seconds": 5 });
  assert.deepEqual(sacValue(100, 0), { mana: 100, "cooldown-seconds": 0 });
  // マナ回復量は1未満にならない(0だと変換が永久に成立しない)。CTの負値は0へ倒す。
  assert.equal(sacValue(0, null), 1);
  assert.deepEqual(sacValue(100, -3), { mana: 100, "cooldown-seconds": 0 });
});

test("ars-config スキーマ: items の値は旧記法(数値)と新記法(マップ)の両方を通す", () => {
  const sac = (items) => ({ mana: { "source-auto-consume": { items } } });
  assert.deepEqual(validate("ars-config", sac({ source_berry: 100 })), [],
    "旧記法を弾くと既存 config.yml が保存できなくなる");
  assert.deepEqual(validate("ars-config", sac({ source_berry: { mana: 100, "cooldown-seconds": 5 } })), []);
  assert.deepEqual(validate("ars-config", sac({ source_berry: { mana: 100 } })), [],
    "CT省略は全体既定に従うので正当");
  assert.deepEqual(validate("ars-config", sac({ source_berry: { mana: 100, "cooldown-seconds": 0 } })), [],
    "0(CT無し)は正当");

  assert.ok(validate("ars-config", sac({ source_berry: { mana: 0 } }))
    .some((e) => e.includes("mana")), "マナ0は変換が永久に成立しないので弾く");
  assert.ok(validate("ars-config", sac({ source_berry: { mana: 100, "cooldown-seconds": -1 } }))
    .some((e) => e.includes("cooldown-seconds")), "負のCTは弾く");
  assert.ok(validate("ars-config", sac({ source_berry: { mana: 100, cooldown: 5 } }))
    .some((e) => e.includes("未知のキー")), "キー名の打ち間違いは無言で無視されるので弾く");
});

test("アイテム一覧の数値欄にラベルが付いている(全体CT欄と見分けがつく)", () => {
  assert.ok(P3_SOURCE.includes('labeled("マナ回復量"'),
    "数値欄が無ラベルだと、すぐ上の全体CT欄と区別がつかない(ユーザー報告の元)");
  assert.ok(P3_SOURCE.includes('labeled("CT(秒)"'), "アイテムごとのCT欄が無い");
});
