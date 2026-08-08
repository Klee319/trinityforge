"use strict";

// U13 (2026-08-01) の回帰テスト。
// 「editor のモブ定義(combat/mob-types.yml)でカスタムアイテムをセレクトできない」への対応を固定する。
//
// 真因は public/js/mob-forms.js の buildDropRow が window.materialInput へ opts を渡していなかったこと
// (= allowCustom:false)。materialInput はセレクト自体は出すが、allowCustom を渡さないと
//   1. 候補に custom:<ID> が1件も入らない
//   2. 「＋ 直接入力…」で custom:foo と打っても normalizeCommitValue が CUSTOMFOO へ潰す
// の2経路が同時に塞がり、カスタムアイテムを入れる方法が1本も無くなる。
//
// ここで固定するのは3点:
//   A. mob-types の drops 行が allowCustom:true でセレクトを組む (振る舞い)
//   B. mob-forms.js の materialInput 呼び出しは「Material限定が仕様の欄」以外すべて allowCustom:true
//      (追加/削除の両方向ドリフト検知 — docs/agent-context/config-editor.md)
//   C. lib/schema.js が custom:<ID> を Java と同じ受理集合で通す (空ID/不正トークンは弾く)
//
// 併せて「セレクトのラベルが日本語で出る」ことも固定する(id がそのまま出るのは既知の再発バグ)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.resolve(__dirname, "..");
const JS = (name) => fs.readFileSync(path.join(ROOT, "public", "js", name), "utf8");

// ============================================================
// A. mob-types の drops 行が allowCustom:true でセレクトを組む
// ============================================================

function makeEl(tag, attrs) {
  const el = {
    tag,
    props: attrs || {},
    children: [],
    style: {},
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    _listeners: {},
    appendChild(c) { if (c != null && c !== false) el.children.push(c); return c; },
    addEventListener(type, fn) { (el._listeners[type] = el._listeners[type] || []).push(fn); },
    querySelector() { return null; },
    querySelectorAll() { return []; }
  };
  if (attrs && "value" in attrs) el.value = attrs.value;
  Object.defineProperty(el, "innerHTML", {
    get() { return ""; },
    set() { el.children = []; }
  });
  return el;
}

/** 要素ツリーを深さ優先で全部拾う。 */
function flatten(el, out) {
  out = out || [];
  if (!el || typeof el !== "object") return out;
  out.push(el);
  for (const c of el.children || []) flatten(c, out);
  return out;
}

/** buildMobTypesForm を描き、materialInput へ渡された opts を記録して返す。 */
function renderMobTypesForm(data) {
  const materialInputCalls = [];
  global.window = {};
  global.document = { getElementById: () => null, body: { appendChild() {} } };
  global.window.VANILLA_MOBS = ["ZOMBIE", "SKELETON"];
  global.window.MOB_LABELS_JA = { ZOMBIE: "ゾンビ", SKELETON: "スケルトン" };
  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children != null) {
      (Array.isArray(children) ? children : [children])
        .forEach((c) => c != null && c !== false && el.appendChild(c));
    }
    return el;
  };
  global.window.fieldLabelEl = (key, opts) => makeEl("label", { text: key, labelOpts: opts || {} });
  global.window.numberInput = (value, onInput, opts) => makeEl("input", { value, numOnInput: onInput, numOpts: opts });
  global.window.textInput = (value, onInput) => makeEl("input", { value, textOnInput: onInput });
  global.window.textInputOnCommit = global.window.textInput;
  global.window.mobTypeSelect = (value, onChange) => makeEl("span", { value, mobOnChange: onChange });
  global.window.listSelect = (opts) => makeEl("span", { listOpts: opts });
  global.window.materialHintEl = () => {
    const el = makeEl("span", { class: "mat-hint" });
    el.update = () => {};
    return el;
  };
  global.window.materialInput = (value, listId, onInput, opts) => {
    materialInputCalls.push({ value, opts: opts || {} });
    return makeEl("span", { class: "material-suggest", matValue: value, matOnInput: onInput });
  };
  global.window.collapsibleCard = (headChildren, bodyChildren) => {
    const card = makeEl("div", { class: "entry-card" });
    card.appendChild(global.window.h("div", { class: "entry-head" }, headChildren));
    card.appendChild(global.window.h("div", { class: "entry-body" }, bodyChildren));
    return card;
  };

  delete require.cache[require.resolve("../public/js/mob-forms.js")];
  require("../public/js/mob-forms.js");
  // buildMobTypesForm は { element, collect } を返す(要素そのものではない)。
  const built = global.window.buildMobTypesForm(data);
  return { form: built.element, materialInputCalls };
}

test("mob-types の drops 行は allowCustom:true でセレクトを組む (カスタムアイテムが候補に入る)", () => {
  const { materialInputCalls } = renderMobTypesForm({
    "mob-types": {
      ZOMBIE: { level: 1, drops: [{ material: "ROTTEN_FLESH", chance: 0.1, min: 1, max: 1 }] }
    }
  });
  assert.equal(materialInputCalls.length, 1, "drops 1件なら materialInput は1回だけ呼ばれる");
  assert.equal(materialInputCalls[0].opts.allowCustom, true,
    "allowCustom を渡さないと候補に custom:<ID> が構造的に入らない(U13 の真因)");
});

test("mob-types の drops 行は custom: の現在値をそのまま表示に持ち込む", () => {
  const { materialInputCalls } = renderMobTypesForm({
    "mob-types": {
      ZOMBIE: { level: 1, drops: [{ material: "custom:tf_scrap", chance: 0.1, min: 1, max: 1 }] }
    }
  });
  assert.equal(materialInputCalls[0].value, "custom:tf_scrap");
  assert.equal(materialInputCalls[0].opts.allowCustom, true);
});

test("mob-types の drops 行のラベルは日本語で custom: も選べることを明示する", () => {
  const { form } = renderMobTypesForm({
    "mob-types": {
      ZOMBIE: { level: 1, drops: [{ material: "ROTTEN_FLESH", chance: 0.1, min: 1, max: 1 }] }
    }
  });
  const labels = flatten(form)
    .filter((el) => el.tag === "label" && el.props.text === "material")
    .map((el) => el.props.labelOpts || {});
  assert.equal(labels.length, 1);
  assert.match(labels[0].label, /素材/, "ラベルは日本語");
  assert.match(labels[0].label, /custom:/, "custom: も指定できることをラベルで示す");
  assert.match(labels[0].desc, /custom:<カタログID>/);
});

// ============================================================
// A-2. allowCustom:true が実際に「日本語ラベル付きのカスタム候補」を生むこと (util.js 実物)
//      新設した選択肢が id 表記のまま出るのは既知の再発バグなので、ここで縛る。
// ============================================================

/** util.js の実物を読み込み、listSelect へ渡された cfg を捕捉できる状態にする。 */
function setupRealMaterialInput() {
  global.window = {};
  global.document = {};
  delete require.cache[require.resolve("../public/js/labels.js")];
  require("../public/js/labels.js");
  delete require.cache[require.resolve("../public/js/util.js")];
  require("../public/js/util.js");

  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children != null) {
      (Array.isArray(children) ? children : [children])
        .forEach((c) => c != null && c !== false && el.appendChild(c));
    }
    return el;
  };
  const captured = [];
  global.window.listSelect = (cfg) => {
    const el = makeEl("span", { value: cfg.value });
    el.cfg = cfg;
    captured.push(cfg);
    return el;
  };
  global.window.setCustomItemCandidates(
    [{ id: "tf_scrap", label: "鉄くず" }, { id: "source_gem", displayName: "ソースジェム" }],
    { replace: true });
  return captured;
}

test("allowCustom:true のセレクト候補には日本語表示名つきの custom:<ID> が入る", () => {
  const captured = setupRealMaterialInput();
  global.window.materialInput("ROTTEN_FLESH", "material-list", () => {}, { allowCustom: true });
  assert.equal(captured.length, 1);
  const options = captured[0].options();
  const scrap = options.find((o) => o.value === "custom:tf_scrap");
  assert.ok(scrap, "カスタム候補が入っていない");
  assert.equal(scrap.primary, "鉄くず", "主表示は日本語表示名 (ID がそのまま出るのは再発バグ)");
  assert.equal(scrap.secondary, "custom:tf_scrap", "副表示だけが ID");
  assert.equal(options.find((o) => o.value === "custom:source_gem").primary, "ソースジェム",
    "displayName キーの候補も日本語で出る");
});

test("allowCustom:true なら custom: の手入力が大文字化で潰されない", () => {
  const captured = setupRealMaterialInput();
  let saved = null;
  global.window.materialInput("", "material-list", (v) => { saved = v; }, { allowCustom: true });
  assert.equal(captured[0].onCommit("custom:tf_scrap"), "custom:tf_scrap");
  assert.equal(saved, "custom:tf_scrap",
    "allowCustom が無いと normalizeCommitValue が CUSTOMTF_SCRAP へ潰す(U13 の第2経路)");
});

// ============================================================
// B. 両方向ドリフト検知: materialInput の allowCustom 有無をホワイトリストで固定する
// ============================================================

/** src 内の `window.materialInput(` 呼び出しを、引数テキストと直前の関数名つきで列挙する。 */
function materialInputCallSites(rawSrc) {
  // 行頭コメントは落とす。説明コメントに `window.materialInput({ allowCustom: true })` と書いて
  // あるだけの行を「呼び出し」として数えると、本物の欠落を隠してしまう。
  const src = rawSrc.replace(/^[ \t]*\/\/.*$/gm, "");
  const sites = [];
  const needle = "window.materialInput(";
  let from = 0;
  for (;;) {
    const at = src.indexOf(needle, from);
    if (at < 0) break;
    // 呼び出しの丸括弧を対応付けて引数テキストを切り出す。
    let depth = 0;
    let end = at + needle.length - 1;
    for (let i = at + needle.length - 1; i < src.length; i += 1) {
      if (src[i] === "(") depth += 1;
      else if (src[i] === ")") {
        depth -= 1;
        if (depth === 0) { end = i; break; }
      }
    }
    const args = src.slice(at + needle.length, end);
    // 囲みブロックの目印は「IIFE 直下(インデント2)の function 宣言」だけを見る。
    // 単に直前の function を拾うと buildRemoveDropsBox の中の入れ子 `function render()` を
    // 掴んでしまい、ホワイトリストが機能しない。
    const before = src.slice(0, at);
    const fnMatches = [...before.matchAll(/^ {2}function ([A-Za-z0-9_$]+)\s*\(/gm)];
    sites.push({
      fn: fnMatches.length ? fnMatches[fnMatches.length - 1][1] : "(unknown)",
      args
    });
    from = end + 1;
  }
  return sites;
}

// Material 限定が「仕様として正しい」欄だけを列挙する。
// remove-drops は「バニラのドロップを Material で消す」機能なので custom: は意味を持たない
// (MobLevelTableConfig 側も Set<Material> でしか読まない)。
const MATERIAL_ONLY_FUNCTIONS = new Set(["buildRemoveDropsBox"]);

test("mob-forms.js の materialInput は Material限定が仕様の欄以外すべて allowCustom:true", () => {
  const src = JS("mob-forms.js");
  const sites = materialInputCallSites(src);
  assert.equal(sites.length, 4,
    "materialInput の呼び出し数が変わった。増えた欄が allowCustom を渡しているか確認して"
    + `この本数を更新すること (現在 ${sites.length}件)`);
  assert.deepEqual([...new Set(sites.map((s) => s.fn))].sort(),
    ["buildAddDropRow", "buildDropItemControl", "buildDropRow", "buildRemoveDropsBox"],
    "アイテム欄を持つ関数の集合が変わった(新設セレクトの見落とし検知)");
  for (const site of sites) {
    const hasAllowCustom = /allowCustom:\s*true/.test(site.args);
    if (MATERIAL_ONLY_FUNCTIONS.has(site.fn)) {
      assert.equal(hasAllowCustom, false,
        `${site.fn} は Material 限定が仕様なのに allowCustom:true が付いている`);
    } else {
      assert.equal(hasAllowCustom, true,
        `${site.fn} の materialInput が allowCustom を渡していない`
        + "(カスタムアイテムが候補にもテキスト入力にも入らなくなる)");
    }
  }
});

test("MATERIAL_ONLY_FUNCTIONS のホワイトリストに死んだ行が無い", () => {
  const src = JS("mob-forms.js");
  for (const fn of MATERIAL_ONLY_FUNCTIONS) {
    assert.match(src, new RegExp(`function\\s+${fn}\\s*\\(`),
      `${fn} が mob-forms.js に無い(ホワイトリストが腐っている)`);
  }
});

// ============================================================
// C. lib/schema.js の受理集合 (Java の MobTypesConfig#parseDrops と一致させる)
// ============================================================

const { validate } = require("../lib/schema.js");

function mobTypesWithDrop(material) {
  return {
    "mob-types": {
      ZOMBIE: { level: 1, drops: [{ material, chance: 0.1, min: 1, max: 1 }] }
    }
  };
}

function dropErrors(material) {
  return validate("tf-mob-types", mobTypesWithDrop(material))
    .filter((e) => e.includes("drops[0].material"));
}

test("schema: drops[].material は custom:<ID> を受け付ける", () => {
  assert.deepEqual(dropErrors("custom:tf_scrap"), []);
  assert.deepEqual(dropErrors("CUSTOM:tf_scrap"), [], "接頭辞は大小無視 (Java 側も regionMatches(true, ...))");
});

test("schema: drops[].material はバニラ Material 名を従来どおり受け付ける", () => {
  assert.deepEqual(dropErrors("ROTTEN_FLESH"), []);
  // Java は toUpperCase してから Material.valueOf するので小文字も通る。
  // ここで弾くと「Java では動くのにエディタでは保存できない」ドリフトになる。
  assert.deepEqual(dropErrors("rotten_flesh"), []);
});

test("schema: ID が空の custom: と不正トークンは弾く", () => {
  assert.equal(dropErrors("custom:").length, 1, "custom: だけの行は Java 側も skip する");
  assert.equal(dropErrors("custom:   ").length, 1);
  assert.equal(dropErrors("").length, 1);
  assert.equal(dropErrors("list:cobblestone").length, 1,
    "素材互換リストは mob ドロップでは解決されない(Java 側に分岐が無い)");
});
