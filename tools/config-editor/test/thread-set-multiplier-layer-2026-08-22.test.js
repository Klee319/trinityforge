"use strict";

// ---------------------------------------------------------------------------
// スレッドのセット効果の「乗算レイヤ」(2026-08-22 K指摘)
//
// 指摘は「セット効果で乗算をONにした時にレイヤ指定ができない」。
// 原因は UI ではなく、レイヤという概念が editor / fork / TF のどこにも無かったこと。
// 乗算は全部 "addon" という専用レイヤ1本に固定で入っていたので、装備側にも同じステの
// 倍率がある場合(攻撃力%)、TF の合成規則「レイヤ内は Σ(v-1) を足し、レイヤ同士は掛ける」
// によって【掛け算で二重に乗って】いた。
//
// ここで固定するのは:
//   1. スキーマが layer: を受け付け、壊れた指定を弾くこと
//   2. UI が乗算ONのときレイヤ選択を出すこと
//   3. 値やモードを書き換えたときに layer を落とさないこと
//      (落とすと黙って "addon" へ戻り、二重乗算に戻る)
// ---------------------------------------------------------------------------

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const { validate } = require("../lib/schema.js");

const FORMS = path.join(__dirname, "..", "public", "js", "forms.js");

function threadSets(statValue) {
  return { "thread-sets": { swordsman: { thresholds: { "5": { "attack-power": statValue } } } } };
}

test("スキーマ: 乗算ステの layer: を受け付ける", () => {
  assert.deepEqual(
    validate("ars-thread-sets", threadSets({ mode: "multiply", value: 0.1, layer: "layer_1" })),
    [],
    "layer: を書いた乗算が弾かれている");
  assert.deepEqual(
    validate("ars-thread-sets", threadSets({ mode: "multiply", value: 0.1 })),
    [],
    "layer: 省略は従来どおり有効(専用レイヤへ落ちる)");
});

test("スキーマ: 空の layer: と 加算モードの layer: は弾く", () => {
  const blank = validate("ars-thread-sets", threadSets({ mode: "multiply", value: 0.1, layer: "  " }));
  assert.equal(blank.length, 1, "空文字の layer: が通っている: " + JSON.stringify(blank));
  assert.match(blank[0], /layer/);

  const onAdd = validate("ars-thread-sets", threadSets({ mode: "add", value: 0.1, layer: "layer_1" }));
  assert.equal(onAdd.length, 1, "加算モードの layer: が通っている: " + JSON.stringify(onAdd));
  assert.match(onAdd[0], /乗算モード/);
});

// --- UI 側 ---------------------------------------------------------------

/** forms.js の renderThreadSetEffects 関数本体を切り出す。 */
function threadSetEffectsSource() {
  const src = fs.readFileSync(FORMS, "utf8");
  const start = src.indexOf("function renderThreadSetEffects(");
  assert.ok(start >= 0, "renderThreadSetEffects が forms.js に無い(走査が壊れている)");
  // 次のトップレベル定義まで。見つからなければ末尾まで。
  const after = src.indexOf("\n      const threadSetSection =", start);
  return src.slice(start, after < 0 ? src.length : after);
}

test("乗算ONの行にレイヤ選択が出る", () => {
  const body = threadSetEffectsSource();
  assert.match(body, /isMultiply\s*\?\s*setEffectLayerSelect\(/,
    "乗算モードの行にレイヤ選択が差し込まれていない。"
    + "これが無いと『乗算にしたのにレイヤを選べない』(2026-08-22 の指摘そのもの)に戻る。");
  assert.match(body, /className:\s*"mult-layer-select"/,
    "レイヤ選択が item-stats と同じ mult-layer-select で描かれていない(見た目が揃わない)");
});

test("値やモードの書き換えでレイヤを落とさない", () => {
  const body = threadSetEffectsSource();
  // 乗算の値を組み立てるのは multiplyValue 一箇所だけ (layer を必ず含む形)。
  const rawWrites = body.match(/\{\s*mode:\s*"multiply",\s*value:/g) || [];
  assert.deepEqual(rawWrites, [],
    "レイヤを含まない { mode: \"multiply\", value } の直書きが残っている。"
    + "この形で保存すると layer: が消え、黙って専用レイヤ(=装備側と掛け算)へ戻る。"
    + "multiplyValue(delta, layer) を使うこと。");
  assert.ok((body.match(/multiplyValue\(/g) || []).length >= 3,
    "multiplyValue の呼び出しが足りない(モード切替・値変更・レイヤ変更の3経路で使う)");
});

test("既定レイヤの文字列が fork/TF と一致している", () => {
  const src = fs.readFileSync(FORMS, "utf8");
  assert.match(src, /const SET_EFFECT_DEFAULT_LAYER = "addon";/,
    "既定レイヤIDは fork の ThreadSetConfig#DEFAULT_LAYER / TF の "
    + "AddonCombatStats#MULTIPLIER_LAYER_ID と同じ \"addon\" でなければならない。"
    + "ずれると editor が書いた layer 未指定の扱いだけが食い違う。");
});
