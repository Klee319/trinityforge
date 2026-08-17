"use strict";

// W-52: セレクトメニューに生アイテムID(または `カスタム: <id>`)が出る問題の再発防止。
//
// 許可リスト方式(特定IDの列挙)は禁止 ── 過去に「リスト自体が誤って検査ごと無効化」した事故が
// あるため使わない。代わりに、候補を作る唯一の入口 window.setCustomItemCandidates
// (util.js) 自身に「ラベル無しで登録されたキー」を集めさせ(window.CUSTOM_ITEM_UNLABELED)、
// 実データ(出荷 yml)を実際にこの入口へ通した結果が空であることを機械的に検査する。
// 新しい候補源を足しても、母集合は「実 yml のキー」から自動的に決まるので特定IDの追加は不要。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const ROOT = path.resolve(__dirname, "..");
const REPO_ROOT = path.resolve(ROOT, "../..");
const JS = (name) => fs.readFileSync(path.join(ROOT, "public", "js", name), "utf8");

/**
 * util.js から window.setCustomItemCandidates だけを切り出して実行する。
 * util.js 本体は DOM 前提(window.h 等)の巨大ファイルだが、この関数自体は DOM に依存しない
 * (window.CUSTOM_ITEM_CANDIDATES / CUSTOM_ITEM_LABELS / CUSTOM_ITEM_UNLABELED への
 * 代入と window.stripDisplayNamePlain の呼び出しだけ)。
 */
function loadSetCustomItemCandidates() {
  const src = JS("util.js");
  const marker = "window.setCustomItemCandidates = function setCustomItemCandidates(entries, opts) {";
  const start = src.indexOf(marker);
  assert.ok(start >= 0, "window.setCustomItemCandidates の定義が見つからない");
  const bodyStart = src.indexOf("{", start);
  let depth = 0;
  let end = -1;
  for (let i = bodyStart; i < src.length; i += 1) {
    if (src[i] === "{") depth += 1;
    else if (src[i] === "}") {
      depth -= 1;
      if (depth === 0) { end = i + 1; break; }
    }
  }
  assert.ok(end > 0, "setCustomItemCandidates の本体を切り出せない(波括弧の対応が取れない)");
  const fnSrc = src.slice(start + "window.setCustomItemCandidates = ".length, end);
  const factory = new Function(`return (${fnSrc});`);
  return factory();
}

function freshWindow() {
  return {
    // 実運用の stripDisplayNamePlain は MiniMessage/legacy コードを剥がすが、この監査では
    // 「ラベルが付いたかどうか」だけを見るので簡易実装で十分(colors.js に依存しない)。
    stripDisplayNamePlain: (raw) => String(raw == null ? "" : raw)
      .replace(/<[^>]+>/g, "")
      .replace(/[&§][0-9a-fk-orA-FK-OR]/gi, "")
      .trim()
  };
}

// ---- 1. 関数自体の契約 ----------------------------------------------------

/** setCustomItemCandidates は内部で `window.X` を直接参照するので、実行前にグローバル window
 * を差し替える。Node の `node --test` はブラウザ由来の `window` を持たないため、
 * `globalThis.window` へ一時的に代入してから呼び、終わったら元に戻す。 */
function withWindow(win, fn) {
  const prev = globalThis.window;
  globalThis.window = win;
  try {
    return fn();
  } finally {
    if (prev === undefined) delete globalThis.window; else globalThis.window = prev;
  }
}

test("setCustomItemCandidates: label 無し登録は CUSTOM_ITEM_UNLABELED に残る(生ID表示になる印)", () => {
  const win = freshWindow();
  const setCustomItemCandidates = loadSetCustomItemCandidates();
  withWindow(win, () => {
    setCustomItemCandidates(["volcanic_sourcelink"], { replace: true }); // 素の文字列 = label無し
  });
  assert.deepEqual(win.CUSTOM_ITEM_CANDIDATES, ["custom:volcanic_sourcelink"]);
  assert.deepEqual(win.CUSTOM_ITEM_UNLABELED, ["custom:volcanic_sourcelink"],
    "label無し登録が CUSTOM_ITEM_UNLABELED から漏れている");
});

test("setCustomItemCandidates: {id, label} 登録は CUSTOM_ITEM_UNLABELED から外れる", () => {
  const win = freshWindow();
  const setCustomItemCandidates = loadSetCustomItemCandidates();
  withWindow(win, () => {
    setCustomItemCandidates([{ id: "volcanic_sourcelink", label: "ヴォルカニックソースリンク" }], { replace: true });
  });
  assert.deepEqual(win.CUSTOM_ITEM_UNLABELED, []);
  assert.equal(win.CUSTOM_ITEM_LABELS["custom:volcanic_sourcelink"], "ヴォルカニックソースリンク");
});

test("setCustomItemCandidates: 後続呼び出し(replace:false)で label が付くと unlabeled から消える", () => {
  const win = freshWindow();
  const setCustomItemCandidates = loadSetCustomItemCandidates();
  withWindow(win, () => {
    setCustomItemCandidates(["pedestal"], { replace: true });
  });
  assert.deepEqual(win.CUSTOM_ITEM_UNLABELED, ["custom:pedestal"]);
  withWindow(win, () => {
    setCustomItemCandidates([{ id: "pedestal", label: "台座" }], { replace: false });
  });
  assert.deepEqual(win.CUSTOM_ITEM_UNLABELED, [],
    "後から届いたラベルが unlabeled 集合へ反映されていない");
});

// ---- 2. 実データ監査: 出荷 yml を実読みし、display-name/display_name/name を持つ全キーに
//         ラベルが付くことを確認する。特定IDを列挙しない(=許可リスト方式ではない)。 -------

const ARS_RESOURCES = path.join(REPO_ROOT, "fork-handoff/arspaper/fork/src/main/resources");
const TF_CATALOG = path.join(REPO_ROOT, "TrinityForge/src/main/resources/items/catalog.yml");

function readYml(file) {
  if (!fs.existsSync(file)) return null;
  return YAML.parse(fs.readFileSync(file, "utf8"));
}

/** {section, nameKey} の各キーを {id, label} へ正規化して集める。 */
function collectLabeledEntries(sectionObj, nameKey) {
  const out = [];
  if (!sectionObj || typeof sectionObj !== "object") return out;
  for (const [id, entry] of Object.entries(sectionObj)) {
    if (!entry || typeof entry !== "object") continue;
    const raw = entry[nameKey];
    if (raw == null || raw === "") continue;
    out.push({ id, label: String(raw) });
  }
  return out;
}

test("実データ監査: functional-items.yml / sourcelinks.yml / sourcejars.yml / materials.yml の" +
  " 表示名付きエントリは setCustomItemCandidates を通しても生ID表示にならない", () => {
  const functionalItems = readYml(path.join(ARS_RESOURCES, "functional-items.yml"));
  const sourcelinks = readYml(path.join(ARS_RESOURCES, "sourcelinks.yml"));
  const sourcejars = readYml(path.join(ARS_RESOURCES, "sourcejars.yml"));
  const materials = readYml(path.join(ARS_RESOURCES, "materials.yml"));
  if (!functionalItems || !sourcelinks || !sourcejars || !materials) {
    console.log("skip: fork-handoff/arspaper のソースがこのワークツリーに無い");
    return;
  }

  const entries = []
    .concat(collectLabeledEntries(functionalItems.items, "display-name"))
    .concat(collectLabeledEntries(sourcelinks.items, "display-name"))
    .concat(collectLabeledEntries(sourcejars.jars, "display-name"))
    .concat(collectLabeledEntries(materials.materials, "display_name"));

  assert.ok(entries.length >= 40,
    `母集合が想定より少ない(${entries.length}件)。yml のパース自体が壊れていないか確認する`);

  const win = freshWindow();
  const setCustomItemCandidates = loadSetCustomItemCandidates();
  withWindow(win, () => {
    setCustomItemCandidates(entries, { replace: true });
  });

  assert.deepEqual(win.CUSTOM_ITEM_UNLABELED, [],
    "表示名を持つのにラベルが付かなかったキーがある(生ID/`カスタム: <id>`表示になる): "
    + JSON.stringify(win.CUSTOM_ITEM_UNLABELED));

  // 具体例(機構B/機構Cで報告された品)がちゃんと母集合に含まれ、正しいラベルが付くことも確認する。
  assert.equal(win.CUSTOM_ITEM_LABELS["custom:pedestal"], "台座");
  assert.equal(win.CUSTOM_ITEM_LABELS["custom:volcanic_sourcelink"], "ヴォルカニックソースリンク");
});

test("実データ監査: catalog.yml (TF) の display-name 付きエントリも同様", () => {
  const catalog = readYml(TF_CATALOG);
  assert.ok(catalog && catalog.items, "TrinityForge/items/catalog.yml が読めない");
  const entries = collectLabeledEntries(catalog.items, "display-name");
  assert.ok(entries.length > 100, "catalog.yml の母集合が想定より少ない");

  const win = freshWindow();
  const setCustomItemCandidates = loadSetCustomItemCandidates();
  withWindow(win, () => {
    setCustomItemCandidates(entries, { replace: true });
  });
  assert.deepEqual(win.CUSTOM_ITEM_UNLABELED, []);
});

// ---- 3. functional-items.js / ars-source-forms.js がこの入口を実際に呼んでいること
//         (呼ばなければ上の監査が「他画面でたまたま埋まっていただけ」で終わってしまう) ------

test("functional-items.js: 3つのカードセクションすべてが setCustomItemCandidates を呼ぶ", () => {
  const src = JS("functional-items.js");
  const calls = src.match(/setCustomItemCandidates\(/g) || [];
  assert.ok(calls.length >= 3,
    `setCustomItemCandidates の呼び出しが${calls.length}件(機能アイテム/エンチャント本/TF特殊アイテムの3セクション分に満たない)`);
});

test("ars-source-forms.js: sourcejars/sourcelinks の両フォームが setCustomItemCandidates を呼ぶ", () => {
  const src = JS("ars-source-forms.js");
  const calls = src.match(/setCustomItemCandidates\(/g) || [];
  assert.ok(calls.length >= 2,
    `setCustomItemCandidates の呼び出しが${calls.length}件(sourcejars/sourcelinksの2フォーム分に満たない)`);
});

// ---- 4. app.js / catalog-candidates.js の候補源リストに sourcelinks が入っていること ------

test("app.js: EXTRA_CONFIGS に sourcelinks が登録されている", () => {
  const src = JS("app.js");
  const m = src.match(/const EXTRA_CONFIGS = \[([\s\S]*?)\];/);
  assert.ok(m, "EXTRA_CONFIGS の定義が見つからない");
  assert.match(m[1], /\["sourcelinks",\s*"sourcelinks"\]/,
    "EXTRA_CONFIGS に sourcelinks が無い(material/display-name を持つのに候補生成が一度も読まない)");
});

test("catalog-candidates.js: EXTRA_SOURCES に sourcelinks があり、functionalItems は materialless", () => {
  const src = JS("catalog-candidates.js");
  const m = src.match(/const EXTRA_SOURCES = Object\.freeze\(\[([\s\S]*?)\]\);/);
  assert.ok(m, "EXTRA_SOURCES の定義が見つからない");
  assert.match(m[1], /key:\s*"sourcelinks"/, "EXTRA_SOURCES に sourcelinks が無い");
  assert.match(m[1], /key:\s*"functionalItems"[\s\S]*?materialless:\s*true/,
    "functionalItems に materialless: true が無い(pedestal等12件が候補から除外されたまま)");
});
