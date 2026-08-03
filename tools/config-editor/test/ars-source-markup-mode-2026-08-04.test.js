"use strict";

// K の報告(2026-08-04): 「&c焔喰いの炉のように新規追加の一部アイテムがカラーコードが反映されず
// editor のプレビューでコードが見えている」。
//
// 原因: ars-source-forms.js のカード(ソースリンク/ソースジャー共通)がプレビューとリッチ入力の
// 記法を **minimessage 固定**で渡していた。出荷 sourcelinks.yml / sourcejars.yml の display-name は
// レガシー(&コード)なので、"&c" が生文字列として見えていた。ゲーム内は
// com.arspaper.util.DisplayText#parse が「レガシーが1つでもあればレガシー、無ければ MiniMessage」で
// 両方を解釈するため、**editor だけ**が食い違っていた。
//
// 固定する不変条件:
//   A. markupMode / markupModeOfLines が DisplayText#parse と同じ優先順で判定する。
//   B. 出荷 yml の display-name が全件 legacy 判定になる(= プレビューで &c が見えない)。
//   C. カードのプレビューとリッチ入力に、固定値ではなく判定結果が渡る。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const ROOT = path.resolve(__dirname, "..");
const FORK = path.resolve(ROOT, "../../fork-handoff/arspaper/fork/src/main/resources");

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
  Object.defineProperty(el, "innerHTML", { get() { return ""; }, set() { el.children = []; } });
  return el;
}

/**
 * ソースリンクカードを描き、richTextInput の (値, モード) と
 * buildTooltipPreview().update() へ渡された引数を記録する。
 */
function renderCard(entry) {
  const richTexts = [];
  const previews = [];
  global.window = {};
  global.document = { getElementById: () => null, body: { appendChild() {} } };
  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children != null) {
      (Array.isArray(children) ? children : [children])
        .forEach((c) => c != null && c !== false && el.appendChild(c));
    }
    return el;
  };
  global.window.fieldLabelEl = (key) => makeEl("label", { text: key });
  global.window.numberInput = (value) => makeEl("input", { value });
  global.window.checkboxInput = (value) => makeEl("input", { type: "checkbox", value });
  global.window.textInput = (value) => makeEl("input", { value });
  global.window.richTextInput = (value, mode) => {
    richTexts.push({ value, mode });
    return makeEl("input", { value });
  };
  global.window.listSelect = (cfg) => makeEl("span", { listCfg: cfg });
  global.window.materialInput = (value) => makeEl("span", { class: "material-suggest", value });
  global.window.materialHintEl = () => { const el = makeEl("span"); el.update = () => {}; return el; };
  global.window.buildTooltipPreview = () => ({
    element: makeEl("div"),
    update: (o) => previews.push(o)
  });
  global.window.collapsibleCard = (headChildren, bodyChildren) => {
    const card = makeEl("div", { class: "entry-card" });
    card.appendChild(global.window.h("div", { class: "entry-head" }, headChildren));
    card.appendChild(global.window.h("div", { class: "entry-body" }, bodyChildren));
    return card;
  };
  delete require.cache[require.resolve("../public/js/ars-source-forms.js")];
  require("../public/js/ars-source-forms.js");
  global.window.buildSourceLinksForm({ items: { volcanic_sourcelink_ii: entry } });
  return { richTexts, previews, logic: global.window.ARS_SOURCE_FORMS_LOGIC };
}

// ============================================================
// A. 判定そのもの
// ============================================================

test("markupMode: レガシー(&/§コード)が1つでもあれば legacy、無ければ minimessage", () => {
  const { logic } = renderCard({ material: "FURNACE" });
  const { markupMode, markupModeOfLines } = logic;

  assert.equal(markupMode("&c焔喰いの炉"), "legacy");
  assert.equal(markupMode("&4&l星焔の煉炉"), "legacy");
  assert.equal(markupMode("§b蒼片の壺"), "legacy", "§(セクション記号)もレガシー扱い");
  assert.equal(markupMode("<red>焔喰いの炉</red>"), "minimessage");
  assert.equal(markupMode(""), "minimessage", "空文字は MiniMessage 既定(判定材料が無い)");
  assert.equal(markupMode(null), "minimessage", "未設定でも例外を投げない");
  assert.equal(markupMode("&&"), "minimessage",
    "&の後ろが色コードでなければレガシーとは見なさない(素の & を含む説明文で誤判定しない)");
  assert.equal(markupMode("<gray>混在 &c あり</gray>"), "legacy",
    "混在時は DisplayText#parse と同じくレガシー優先(ゲーム内の見た目に合わせる)");

  assert.equal(markupModeOfLines(["<gray>普通の行", "&7だけレガシーな行"]), "legacy",
    "lore は1行でもレガシーが混ざればレガシー(プレビューは1モードしか持てない)");
  assert.equal(markupModeOfLines(["<gray>a", "<gray>b"]), "minimessage");
  assert.equal(markupModeOfLines(undefined), "minimessage", "lore 未設定でも例外を投げない");
});

// ============================================================
// B. 出荷 yml
// ============================================================

for (const [file, section] of [["sourcelinks.yml", "items"], ["sourcejars.yml", "jars"]]) {
  test(`出荷 ${file} の色付き display-name は legacy 判定になる(プレビューで &c が生で見えない)`, () => {
    const { logic } = renderCard({ material: "FURNACE" });
    const data = YAML.parse(fs.readFileSync(path.join(FORK, file), "utf8"));
    const entries = Object.entries(data[section] || {})
      .filter(([, e]) => e && typeof e === "object" && typeof e["display-name"] === "string");
    assert.ok(entries.length > 0, `${file} の ${section}: が読めていない`);

    // 色記号を含む名前(= 階梯で追加した固有名。無印5種は色無しの素のテキストなので対象外)。
    const colored = entries.filter(([, e]) => e["display-name"].includes("&"));
    assert.ok(colored.length > 0, `${file} に色付きの display-name が1件も無い(テストの前提が変わった)`);
    const wrong = colored.filter(([, e]) => logic.markupMode(e["display-name"]) !== "legacy").map(([id]) => id);
    assert.deepEqual(wrong, [],
      `${file} の色付き display-name がレガシーとして判定されていない`
      + "(= プレビューに &c がそのまま出る。K が報告した実バグ)");

    // MiniMessage 記法は混ぜない。プレビューは1モードしか持てないので、混ぜるとどちらかが必ず生で出る。
    const mm = entries.filter(([, e]) => /<\/?[a-z_]+(:[^>]*)?>/.test(e["display-name"])).map(([id]) => id);
    assert.deepEqual(mm, [],
      `${file} に MiniMessage 記法の display-name が混ざっている`
      + "(fork の DisplayText はレガシーを優先するので、混在すると片方が生タグで表示される)");
  });
}

// ============================================================
// C. カードへの受け渡し
// ============================================================

test("カード: レガシー名のときプレビューとリッチ入力に legacy が渡る", () => {
  const { richTexts, previews } = renderCard({
    material: "FURNACE", "display-name": "&c焔喰いの炉", lore: ["&7燃料を燃やす"]
  });
  const nameInput = richTexts.find((r) => r.value === "&c焔喰いの炉");
  assert.ok(nameInput, "display-name のリッチ入力が描かれていない");
  assert.equal(nameInput.mode, "legacy",
    "display-name の入力欄が minimessage 固定のまま(&c が生文字列として見える)");
  const loreInput = richTexts.find((r) => r.value === "&7燃料を燃やす");
  assert.ok(loreInput, "lore 行のリッチ入力が描かれていない");
  assert.equal(loreInput.mode, "legacy", "lore 行が minimessage 固定のまま");

  assert.ok(previews.length > 0, "プレビューが更新されていない");
  const p = previews[previews.length - 1];
  assert.equal(p.nameMode, "legacy", "プレビューの名前が minimessage 固定のまま(報告された実バグ)");
  assert.equal(p.loreMode, "legacy", "プレビューの lore が minimessage 固定のまま");
});

test("カード: MiniMessage 名のときは minimessage が渡る(レガシー固定へ倒していない)", () => {
  const { richTexts, previews } = renderCard({
    material: "FURNACE", "display-name": "<red>焔喰いの炉</red>", lore: ["<gray>燃料を燃やす</gray>"]
  });
  const nameInput = richTexts.find((r) => r.value === "<red>焔喰いの炉</red>");
  assert.ok(nameInput);
  assert.equal(nameInput.mode, "minimessage",
    "レガシー固定へ倒すと、MiniMessage で書いた名前が <red> の生タグとして見える(逆向きの同じバグ)");
  const p = previews[previews.length - 1];
  assert.equal(p.nameMode, "minimessage");
  assert.equal(p.loreMode, "minimessage");
});
