"use strict";

// レガシーステータスがステ選択(セレクトメニュー)の項目として残っていた件の回帰テスト。
//
// 実サーバ報告: 「クラフト:効率増幅↑」(tool-enchant-efficiency)がセレクトに出続けている。
//
// 原因: ステ候補は statList() が「STAT_LIST(lore.yml 由来) ∪ FALLBACK_STATS」の和集合で作る。
// tool-enchant-efficiency は 2026-07-26 の「効率」ステ統合で lore.yml の語彙からは消えていたが、
// materials.js の FALLBACK_STATS に残っていたため、和集合の側から復活していた。
//
// なぜ「ただの表示の余り」で済まないか: StatKeys.LEGACY_KEY_ALIASES が canonical 化の時点で
// tool_enchant_efficiency -> gathering_efficiency へ読み替えるので、この2項目は
// 「別の項目に見えて実体は同じキー」。同じアイテムに両方設定すると後勝ちで片方が黙って消える。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const PUBLIC_JS = path.join(__dirname, "..", "public", "js");

function loadMaterials() {
  global.window = global.window || {};
  delete require.cache[require.resolve("../public/js/materials.js")];
  require("../public/js/materials.js");
  return {
    fallback: global.window.FALLBACK_STATS,
    hidden: global.window.HIDDEN_STATS,
    formats: global.window.FALLBACK_STAT_FORMATS
  };
}

// lore.yml の語彙(STAT_LIST 相当)と FALLBACK_STATS の和集合から HIDDEN_STATS を引く、
// forms.js statList() と同じ計算。ここでは DOM を要さない純粋計算として再現する。
function selectableKeys(statList, fallback, hidden) {
  const hide = new Set(hidden);
  const seen = new Set();
  const out = [];
  for (const k of statList.concat(fallback)) {
    if (!k || hide.has(k) || seen.has(k)) continue;
    seen.add(k);
    out.push(k);
  }
  return out;
}

test("FALLBACK_STATS に統合済みの旧キー tool-enchant-efficiency が残っていない", () => {
  const { fallback } = loadMaterials();
  assert.ok(Array.isArray(fallback) && fallback.length > 10,
    "FALLBACK_STATS が読めていない(テストの空振り検知)");
  assert.ok(!fallback.includes("tool-enchant-efficiency"),
    "tool-enchant-efficiency が FALLBACK_STATS に戻っている。"
    + "gathering-efficiency へ統合済み(StatKeys のエイリアスで読み替えられる)なので、"
    + "セレクトに出すと同じキーが2項目に見えて後勝ちで消える");
});

test("HIDDEN_STATS が定義され、廃止/統合済みキーを両方止めている", () => {
  const { hidden } = loadMaterials();
  assert.ok(Array.isArray(hidden), "window.HIDDEN_STATS が未定義。"
    + "各画面が個別に [\"flat-defense\"] をハードコードする状態へ戻っている");
  // flat-defense = phys/magic へ分離済み、tool-enchant-efficiency = gathering-efficiency へ統合済み。
  assert.ok(hidden.includes("flat-defense"), "flat-defense が HIDDEN_STATS から抜けている");
  assert.ok(hidden.includes("tool-enchant-efficiency"),
    "tool-enchant-efficiency が HIDDEN_STATS から抜けている");
});

test("配備先の古い lore.yml が旧キーを持っていても、セレクトには出ない", () => {
  const { fallback, hidden } = loadMaterials();
  // STAT_LIST 側(=lore.yml 由来)に旧キーが混ざっている状況を再現する。
  // FALLBACK_STATS から消すだけでは、この経路で復活してしまう。
  const stale = ["attack-power", "tool-enchant-efficiency", "flat-defense", "gathering-efficiency"];
  const keys = selectableKeys(stale, fallback, hidden);
  assert.ok(!keys.includes("tool-enchant-efficiency"),
    "lore.yml 側に旧キーが残っているとセレクトへ復活する(HIDDEN_STATS が効いていない)");
  assert.ok(!keys.includes("flat-defense"), "廃止済みの flat-defense がセレクトへ復活する");
  assert.ok(keys.includes("gathering-efficiency"),
    "統合後の正しいキーまで消えている(隠しすぎ)");
  assert.ok(keys.includes("attack-power"), "通常ステまで消えている(隠しすぎ)");
});

test("ロスレス表示用のフォーマット定義は残す(旧キーが書かれた yml を開いても壊さない)", () => {
  const { formats } = loadMaterials();
  assert.equal(formats["tool-enchant-efficiency"], "INTEGER",
    "旧キーのフォーマット定義まで消すと、旧綴りが残る yml を開いたときの"
    + "statSelect のロスレス表示がフォーマット不明になる");
});

// ステ選択を組み立てる3ファイルは、どれも HIDDEN_STATS を参照していなければならない。
// ars-spellbooks.js は「forms.js の statList() と同一ロジック」と書きながら除外だけ持っておらず、
// 廃止キーがこの画面のセレクトにだけ出ていた。複製を増やすときに同じ抜けを再発させないための固定。
for (const file of ["forms.js", "ars-spellbooks.js", "tf-base-stats.js"]) {
  test(`${file} のステ候補生成が HIDDEN_STATS を参照している`, () => {
    const src = fs.readFileSync(path.join(PUBLIC_JS, file), "utf8");
    assert.ok(src.includes("FALLBACK_STATS"),
      `${file} が FALLBACK_STATS を使っていない(テストの前提が崩れている=空振り)`);
    assert.ok(src.includes("HIDDEN_STATS"),
      `${file} のステ候補生成に HIDDEN_STATS の除外が入っていない。`
      + "廃止・統合済みのキーがこの画面のセレクトにだけ出る");
  });
}

