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
  assert.ok(CF_SOURCE.includes("自動消費のCT(秒)"),
    "「ソース自動消費」タブにCT欄が無い(ユーザー依頼: 自動消費のCTをeditorで設定できるように)");
  assert.ok(CF_SOURCE.includes('sourceAutoConsume["cooldown-seconds"]'),
    "CT欄が mana.source-auto-consume.cooldown-seconds を読み書きしていない");
});

test("その他のギミック画面: CT欄の空欄はキーごと削除する(0へ丸めない)", () => {
  const idx = CF_SOURCE.indexOf("自動消費のCT(秒)");
  assert.ok(idx > 0);
  const snippet = CF_SOURCE.slice(idx, idx + 400);
  assert.ok(snippet.includes('delete sourceAutoConsume["cooldown-seconds"]'),
    "空欄でキーを消していない。0へ丸めると『既定10秒』ではなく『CT無し』になり意味が変わる");
});
