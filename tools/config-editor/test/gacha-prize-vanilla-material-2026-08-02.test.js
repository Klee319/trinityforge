"use strict";

// 【2026-08-02 指摘7】ガチャ景品セレクト (pool.entries[].item) からバニラ Material の候補が
// 消えていた回帰テスト。
//
// 背景: prizeItemSelect は 2026-08-02 に window.materialInput({allowCustom:true}) から
// catalogCandidateOptions() ベースへ置き換えられ、バーレのカタログID候補と一致するようになった
// (custom: 接頭辞前提の候補としか一致しなかった旧実装のバグを修正)。だがその置き換えで
// バニラ Material 一覧そのものを候補源から落としてしまい、"COAL"/"DIAMOND" のような
// 正確な enum 名を自由入力するしかなくなっていた。
//
// 修正: catalogCandidateOptions() と window.MATERIALS 由来の候補を同じ1本のセレクトへ
// 両方積む。GachaEntry#itemId はどちらも bare な文字列(custom: 接頭辞なし)で受けるため
// 値の形式は変えない。

const test = require("node:test");
const assert = require("node:assert/strict");

function makeEl(tag, attrs) {
  const el = {
    tag,
    props: attrs || {},
    children: [],
    appendChild(c) { if (c != null && c !== false) el.children.push(c); return c; },
    addEventListener() {}
  };
  Object.defineProperty(el, "innerHTML", {
    get() { return ""; },
    set() { el.children = []; }
  });
  return el;
}

function setupDom() {
  global.window = global.window || {};
  global.document = global.document || {};
  global.alert = () => {};

  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children != null) {
      (Array.isArray(children) ? children : [children]).forEach((c) => c != null && c !== false && el.appendChild(c));
    }
    return el;
  };

  global.window.MATERIALS = ["COAL", "DIAMOND", "IRON_INGOT"];
  global.window.LABELS = {
    materialLabel: (k) => ({ COAL: "石炭", DIAMOND: "ダイヤモンド", IRON_INGOT: "鉄インゴット" }[k] || "")
  };
  global.window.numberInput = (value) => makeEl("input", { value, class: "num" });
  global.window.checkboxInput = (value) => makeEl("input", { type: "checkbox", checked: !!value });

  const captured = [];
  global.window.listSelect = (cfg) => {
    const el = makeEl("span", { value: cfg.value });
    el.cfg = cfg;
    captured.push(cfg);
    return el;
  };

  delete require.cache[require.resolve("../public/js/p5-forms.js")];
  require("../public/js/p5-forms.js");

  return captured;
}

function makeGachaData(prizeItem) {
  return {
    tickets: {},
    pools: {
      standard: {
        entries: [{ item: prizeItem, weight: 1, amount: 1 }]
      }
    }
  };
}

test("景品セレクト: バニラ Material (COAL/DIAMOND) が候補に入る", () => {
  const captured = setupDom();
  window.buildGachaForm(makeGachaData("COAL"), {
    catalogCandidates: [{ id: "tf_scrap", label: "TFスクラップ" }]
  });

  // 景品行のセレクトは value === 現在値("COAL") で捕捉する。
  const cfg = captured.find((c) => c.value === "COAL");
  assert.ok(cfg, "景品セレクトが描画されていない");

  const coal = cfg.options.find((o) => o.value === "COAL");
  assert.ok(coal, "バニラ Material COAL が候補に入っていない(自由入力しか手段が無い状態のまま)");
  assert.equal(coal.primary, "石炭", "バニラ Material が日本語表示になっていない");

  const diamond = cfg.options.find((o) => o.value === "DIAMOND");
  assert.ok(diamond, "候補になっていない他のバニラ Material (DIAMOND) も出るべき");
});

test("景品セレクト: カタログ候補とバニラ Material の両方が同じセレクトに共存する", () => {
  const captured = setupDom();
  window.buildGachaForm(makeGachaData("tf_scrap"), {
    catalogCandidates: [{ id: "tf_scrap", label: "TFスクラップ" }]
  });

  const cfg = captured.find((c) => c.value === "tf_scrap");
  assert.ok(cfg, "景品セレクトが描画されていない");

  const catalogOpt = cfg.options.find((o) => o.value === "tf_scrap");
  assert.ok(catalogOpt, "カタログ候補が消えている(既存の修正を退行させている)");
  assert.equal(catalogOpt.primary, "TFスクラップ");

  assert.ok(cfg.options.some((o) => o.value === "DIAMOND"), "バニラ Material 候補が共存していない");
});

test("景品セレクト: 選択後の保存値は bare な文字列のまま(custom: 接頭辞を付けない)", () => {
  const captured = setupDom();
  const data = makeGachaData("COAL");
  window.buildGachaForm(data, { catalogCandidates: [] });

  const cfg = captured.find((c) => c.value === "COAL");
  const applied = cfg.onCommit("DIAMOND");
  assert.notEqual(applied, false, "onCommit が値を拒否している");
  assert.equal(data.pools.standard.entries[0].item, "DIAMOND",
    "保存値が custom: 接頭辞付きなど別形式に変換されている"
    + "(GachaEntry#itemId は bare な文字列で受ける)");
});
