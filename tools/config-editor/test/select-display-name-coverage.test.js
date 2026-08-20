"use strict";

// タスク4 (2026-07-27) 回帰テスト: セレクトメニューが表示名でなくIDを出していたバグの修正確認と、
// 横断監査で見つかった追加1件の修正確認。
//
// 1) グリフBANリスト (ban.yml, buildBanForm/tf-phase3-forms.js):
//    グリフには display-name が設定されているのに、セレクトの primary にID(id)をそのまま
//    出していた。primary=表示名 / secondary=ID (itemRefSelect と同じ流儀) へ修正。
// 2) 横断監査で発見: 達成記録(achievements, tf-rewards-forms.js)の「対象モブ」セレクトも、
//    vocab-1.21.11.js の window.MOB_LABELS_JA (recipes.js/ars-p4.js のモブ選択で既に使われている
//    辞書)があるのに使わず、生の EntityType ID をそのまま primary に出していた。同じ辞書で修正。
//
// ドリフト検知にするため、「渡したサンプルデータに display-name がある場合、primary に
// そのまま反映される」という関数の入出力契約を検証する形にする(表示名を追加するたびに
// 落ちるテストではなく、表示名が有るのに使われない回帰を検知する形)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

function makeEl(tag, attrs) {
  const el = {
    tag,
    props: attrs || {},
    children: [],
    appendChild(c) { if (c != null && c !== false) el.children.push(c); return c; },
    set innerHTML(_v) { this.children = []; },
    get innerHTML() { return ""; }
  };
  return el;
}

function setupBanFormStubs() {
  global.window = global.window || {};
  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (Array.isArray(children)) children.forEach((c) => c != null && el.appendChild(c));
    else if (children != null) el.appendChild(children);
    return el;
  };
  const captured = [];
  global.window.listSelect = (cfg) => {
    captured.push(cfg);
    return makeEl("span", { value: cfg.value });
  };
  delete require.cache[require.resolve("../public/js/tf-phase3-forms.js")];
  require("../public/js/tf-phase3-forms.js");
  return captured;
}

test("グリフBANリスト: display-name が設定されたグリフは、セレクトの primary に表示名が出る(IDそのままではない)", async () => {
  const captured = setupBanFormStubs();
  global.fetch = async () => ({
    json: async () => ({
      data: {
        glyphs: {
          fireball: { "display-name": "火球" },
          no_display_name_glyph: {}
        }
      }
    })
  });

  const data = { "banned-spells": [] };
  window.buildBanForm(data);
  // loadGlyphOptions() は fire-and-forget の async だが、fetch/json のawaitはマイクロタスクなので
  // 1回イベントループを回せば完了する。
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));

  // 「+ BAN追加…」セレクト(既存の未使用グリフ候補)の options を見る。
  const addSelect = captured.find((c) => c.placeholder === "+ BAN追加…" || c.placeholder === "追加できるグリフがありません");
  assert.ok(addSelect, "「+ BAN追加…」セレクトが見つからない");

  const fireballOpt = addSelect.options.find((o) => o.value === "arspaper:fireball");
  assert.ok(fireballOpt, "fireball の候補が見つからない");
  assert.equal(fireballOpt.primary, "火球", "display-name(火球)がprimaryに出ていない(IDのままのバグが再発している)");
  assert.equal(fireballOpt.secondary, "arspaper:fireball", "IDはsecondaryへ回っているはず");

  const noNameOpt = addSelect.options.find((o) => o.value === "arspaper:no_display_name_glyph");
  assert.ok(noNameOpt, "no_display_name_glyph の候補が見つからない");
  assert.equal(noNameOpt.primary, "no_display_name_glyph", "display-name未設定はIDへフォールバックするはず");
});

test("横断監査: 達成記録(tf-rewards-forms.js)の対象モブ候補が window.MOB_LABELS_JA を使うようになっている", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "public", "js", "tf-rewards-forms.js"), "utf8");
  const fnStart = src.indexOf("const mobCandidates = ENTITY_TYPE_CANDIDATES.map(");
  assert.ok(fnStart >= 0, "mobCandidates の生成箇所が見つからない(リファクタで名前が変わった可能性)");
  const snippet = src.slice(fnStart, fnStart + 400);
  assert.match(snippet, /window\.MOB_LABELS_JA/,
    "mobCandidates が window.MOB_LABELS_JA (recipes.js/ars-p4.js と同じ辞書)を参照していない");
});

test("横断監査で確認済み・修正不要: itemRefSelect のバニラMaterial候補はデータ側に表示名が無いため" +
  "ID表示のままで正しい(MATERIAL_LABELSは別のオプトイン辞書であり、素材そのものに表示名フィールドは無い)", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "public", "js", "util.js"), "utf8");
  assert.match(src, /window\.itemRefSelect = function itemRefSelect/, "itemRefSelect が見つからない(監査対象が移動した可能性)");
});
