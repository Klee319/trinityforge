"use strict";

// スレッド(threads.yml)の特殊効果(ポーション効果/レベル/飛行/バックパック枠)対応。
//
// 背景: バニラ/Ars のスレッドが持つ効果(暗視・飛行など)を GUI から選べない・レベルを設定できない、
// という実機報告への対応。旧 item-stats.yml の「特殊効果 (special-effects)」欄
// (forms.js の THREAD_SPECIAL_EFFECTS + renderThreadExtraFields 内のUI)は TF/ArsPaper とも
// 読むコードが無い飾りだったため撤去し(下の「1」で固定)、実機が読む threads.yml 側の
// potion-effect/potion-level/flight/slots を public/js/ars-forms.js の buildThreadsForm に
// 追加した(下の「2」「3」で固定)。
//
// lib/schema.js の THREAD_EFFECT_KEYS 取りこぼし(mana-max-percent/regen-percent が
// 型検証されていなかった)も同時に修正した(下の「4」で固定)。

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");
const { validate } = require("../lib/schema");
const ARS_FORMS = require("../public/js/ars-forms.js");

const root = path.resolve(__dirname, "..");
const JS = (name) => fs.readFileSync(path.join(root, "public", "js", name), "utf8");
const RESOURCES = path.resolve(root, "..", "..", "TrinityForge", "src", "main", "resources");

const BENEFICIAL_18 = [
  "speed", "haste", "strength", "jump_boost", "regeneration", "resistance",
  "fire_resistance", "water_breathing", "invisibility", "night_vision",
  "health_boost", "absorption", "saturation", "luck", "slow_falling",
  "conduit_power", "dolphins_grace", "hero_of_the_village"
];

// ---------------------------------------------------------------------------
// 1. 死んでいた「特殊効果 (special-effects)」UI が撤去されていること。
// ---------------------------------------------------------------------------

test("出荷 item-stats.yml に special-effects の実データは1件も無い(削除前提の確認)", () => {
  const yml = fs.readFileSync(path.join(RESOURCES, "stats", "item-stats.yml"), "utf8");
  assert.ok(!yml.includes("special-effects"),
    "special-effects が出荷 yml に存在する。削除前にこのテストで検知される想定。");
});

test("forms.js は THREAD_SPECIAL_EFFECTS も special-effects UI も定義しない", () => {
  const src = JS("forms.js");
  assert.ok(!/const\s+THREAD_SPECIAL_EFFECTS\s*=/.test(src),
    "旧 THREAD_SPECIAL_EFFECTS 定数が復活している");
  // コメント(撤去の経緯説明)には文言が残ってよいので、実際のUIラベル定義(mini-labelのtext)だけを見る。
  assert.ok(!/class:\s*"mini-label",\s*text:\s*"特殊効果 \(special-effects\)"/.test(src),
    "旧「特殊効果 (special-effects)」UI ラベルが復活している");
  assert.ok(!/entry\["special-effects"\]\s*=/.test(src),
    "item-stats.yml の items.<key>.special-effects へ書き込む処理が復活している");
});

// ---------------------------------------------------------------------------
// 2. lib/schema.js: potion-effect / potion-level / flight / mana-max-percent / regen-percent の検証。
// ---------------------------------------------------------------------------

test("ars-threads: potion-effect は有益効果18種の id を許可する", () => {
  for (const id of BENEFICIAL_18) {
    const errs = validate("ars-threads", { threads: { t: { "potion-effect": id } } });
    assert.deepEqual(errs, [], `${id} が弾かれた: ${JSON.stringify(errs)}`);
  }
});

test("ars-threads: potion-effect は \"none\" を許可する(明示的に効果を付けない)", () => {
  const errs = validate("ars-threads", { threads: { t: { "potion-effect": "none" } } });
  assert.deepEqual(errs, []);
});

test("ars-threads: potion-effect の未知 id / 有害効果はエラー", () => {
  for (const bad of ["poison", "wither", "not_a_real_effect", "SPEED"]) {
    const errs = validate("ars-threads", { threads: { t: { "potion-effect": bad } } });
    assert.ok(errs.length >= 1, `${bad} が弾かれなかった`);
  }
});

test("ars-threads: potion-level は1以上の整数のみ許可", () => {
  assert.deepEqual(validate("ars-threads", { threads: { t: { "potion-level": 1 } } }), []);
  assert.deepEqual(validate("ars-threads", { threads: { t: { "potion-level": 3 } } }), []);
  assert.ok(validate("ars-threads", { threads: { t: { "potion-level": 0 } } }).length >= 1);
  assert.ok(validate("ars-threads", { threads: { t: { "potion-level": -1 } } }).length >= 1);
  assert.ok(validate("ars-threads", { threads: { t: { "potion-level": 1.5 } } }).length >= 1);
  assert.ok(validate("ars-threads", { threads: { t: { "potion-level": "1" } } }).length >= 1);
});

test("ars-threads: flight は真偽値のみ許可", () => {
  assert.deepEqual(validate("ars-threads", { threads: { t: { flight: true } } }), []);
  assert.deepEqual(validate("ars-threads", { threads: { t: { flight: false } } }), []);
  assert.ok(validate("ars-threads", { threads: { t: { flight: "true" } } }).length >= 1);
});

test("ars-threads: mana-max-percent / regen-percent は取りこぼされず数値検証される", () => {
  assert.deepEqual(validate("ars-threads", { threads: { t: { "mana-max-percent": 3 } } }), []);
  assert.deepEqual(validate("ars-threads", { threads: { t: { "regen-percent": 4 } } }), []);
  assert.ok(validate("ars-threads", { threads: { t: { "mana-max-percent": "3" } } }).length >= 1,
    "mana-max-percent が数値以外でも通ってしまう(取りこぼしの再発)");
  assert.ok(validate("ars-threads", { threads: { t: { "regen-percent": "4" } } }).length >= 1,
    "regen-percent が数値以外でも通ってしまう(取りこぼしの再発)");
});

// ---------------------------------------------------------------------------
// 3. public/js/ars-forms.js (CORE): parse/serialize の純関数コア。ロスレス往復 + 削除挙動。
// ---------------------------------------------------------------------------

test("CORE.THREAD_POTION_EFFECTS は lib/schema.js の有益効果18種と同じ集合(検証側/UI側のミラー)", () => {
  const ids = ARS_FORMS.THREAD_POTION_EFFECTS.map(([id]) => id);
  assert.deepEqual(ids.slice().sort(), BENEFICIAL_18.slice().sort());
});

test("parseThreadEntry/serializeThreadEntry: potion-effect/potion-level/flight/slots のロスレス往復", () => {
  const src = {
    display_name: "テスト",
    "potion-effect": "speed",
    "potion-level": 2,
    flight: true,
    slots: 27
  };
  const model = ARS_FORMS.parseThreadEntry("t", src);
  assert.equal(model.hasPotionEffect, true);
  assert.equal(model.potionEffect, "speed");
  assert.equal(model.hasPotionLevel, true);
  assert.equal(model.potionLevel, 2);
  assert.equal(model.hasFlight, true);
  assert.equal(model.flight, true);
  assert.equal(model.hasSlots, true);
  assert.equal(model.slots, 27);
  assert.deepEqual(ARS_FORMS.serializeThreadEntry(model), src);
});

test("parseThreadEntry: 何も無いエントリは特殊効果系のキーを持たない(既定値へ勝手に触れない)", () => {
  const model = ARS_FORMS.parseThreadEntry("t", { display_name: "空" });
  assert.equal(model.hasPotionEffect, false);
  assert.equal(model.hasPotionLevel, false);
  assert.equal(model.hasFlight, false);
  assert.equal(model.hasSlots, false);
  assert.deepEqual(ARS_FORMS.serializeThreadEntry(model), { display_name: "空" });
});

test("【挙動】特殊効果を空にしたらキーごと削除される(0/\"\"を書き残さない)", () => {
  // 一度全部設定してから、GUI の onCommit/onchange が実際にやる操作
  // (hasXxx=false; xxx=undefined) を模して1つずつ解除し、そのたびに出力からキーが消えることを見る。
  const src = {
    "potion-effect": "night_vision", "potion-level": 3, flight: true, slots: 27
  };
  const model = ARS_FORMS.parseThreadEntry("t", src);

  model.hasFlight = false; model.flight = undefined;
  let out = ARS_FORMS.serializeThreadEntry(model);
  assert.ok(!("flight" in out), "flight を解除してもキーが残っている");
  assert.equal(out["potion-effect"], "night_vision");

  model.hasSlots = false; model.slots = undefined;
  out = ARS_FORMS.serializeThreadEntry(model);
  assert.ok(!("slots" in out), "slots を解除してもキーが残っている");

  // 効果を「未設定」に戻す(× ボタン相当) = potion-effect と potion-level を両方削除。
  model.hasPotionEffect = false; model.potionEffect = undefined;
  model.hasPotionLevel = false; model.potionLevel = undefined;
  out = ARS_FORMS.serializeThreadEntry(model);
  assert.deepEqual(out, {}, `キーが残っている: ${JSON.stringify(out)}`);
});

test("parseThreadEntry: 旧来の slots(バックパック)は効果パラメータ(effects)からは外れ、専用フィールドに乗る", () => {
  const model = ARS_FORMS.parseThreadEntry("backpack", { display_name: "バックパックのスレッド", slots: 27 });
  assert.equal(model.hasSlots, true);
  assert.equal(model.slots, 27);
  assert.ok(!Object.prototype.hasOwnProperty.call(model.effects, "slots"),
    "slots が汎用 effects ループに残っている(特殊効果セクションと二重編集になる)");
  assert.ok(!ARS_FORMS.THREAD_EFFECT_KEYS.includes("slots"),
    "THREAD_EFFECT_KEYS に slots が残っている(特殊効果セクションと二重表示になる)");
});

test("parseThreadEntry/serializeThreadEntry: mana-max-percent / regen-percent は引き続き effects 経由でロスレス", () => {
  const src = { display_name: "マナ増幅のスレッド", "mana-max-percent": 3, stackable: true, max: 3 };
  const model = ARS_FORMS.parseThreadEntry("mana_amplify", src);
  assert.equal(model.effects["mana-max-percent"], 3);
  assert.deepEqual(ARS_FORMS.serializeThreadEntry(model), src);

  const src2 = { display_name: "循環のスレッド", "regen-percent": 4, stackable: true, max: 3 };
  const model2 = ARS_FORMS.parseThreadEntry("mana_circulate", src2);
  assert.equal(model2.effects["regen-percent"], 4);
  assert.deepEqual(ARS_FORMS.serializeThreadEntry(model2), src2);
});

// ---------------------------------------------------------------------------
// 4. 出荷 threads.yml 全件のロスレス往復(extract -> build 相当)。
// ---------------------------------------------------------------------------

test("出荷 fork-handoff threads.yml が存在する場合、全スレッドが parse/serialize でロスレス往復する", (t) => {
  const shippedPath = path.resolve(root, "..", "..", "fork-handoff", "arspaper", "fork", "src", "main", "resources", "threads.yml");
  if (!fs.existsSync(shippedPath)) { t.skip("fork-handoff は他セッションのWIPで存在しないことがある"); return; }
  const YAML = require("yaml");
  const doc = YAML.parse(fs.readFileSync(shippedPath, "utf8"));
  const threads = doc && doc.threads;
  assert.ok(threads && typeof threads === "object");
  for (const [id, entry] of Object.entries(threads)) {
    const model = ARS_FORMS.parseThreadEntry(id, entry);
    assert.deepEqual(ARS_FORMS.serializeThreadEntry(model), entry, `${id} がロスレス往復しない`);
  }
});

// ---------------------------------------------------------------------------
// 5. buildThreadsForm が新セクションを描画すること(静的ソースチェック)。
// ---------------------------------------------------------------------------

test("ars-forms.js の buildThreadsForm は特殊効果セクション(potion-effect/potion-level/flight/slots)を描画する", () => {
  const src = JS("ars-forms.js");
  assert.ok(/renderSpecialEffects/.test(src), "renderSpecialEffects が無い");
  assert.ok(/text:\s*"特殊効果"/.test(src), "「特殊効果」見出しが無い");
  assert.ok(/hasPotionEffect/.test(src) && /hasPotionLevel/.test(src) && /hasFlight/.test(src) && /hasSlots/.test(src),
    "特殊効果系のモデルフィールドが無い");
});
