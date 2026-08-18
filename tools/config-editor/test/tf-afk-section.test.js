"use strict";

// afk.yml (tf-afk) editor 統合の回帰テスト。
//
// 背景: AfkService / AfkConfig / AfkActivityListener / AfkSuppressionListener は実装済みの
// 現役機構だが、config-editor に一切登録されておらず yml 直編集しか調整手段が無かった。
// ユーザー指示により独立タブは作らず「使用制限スイッチ」(use-requirements)画面内へ
// AFK セクションとしてコンパニオン表示する(2026-07-27新設)。
//
// 注意: public/js/tf-crafting-features.js は本テスト作成時点で別セッションの作業途中により
// 808行付近に構文エラーが入ったままになっている(このタスクの指示で「直さない」よう明記されている)。
// そのため buildUseRequirementsForm 側の接続確認は require して実行検証せず、既存テスト
// (tf-stat-caps-registry.test.js 等)と同じ「ソース文字列を正規表現で検査する」流儀に倣う。
// tf-crafting-features.js の構文エラーが解消されれば、この検証はそのまま実行検証にも耐える
// (正規表現が探しているのは実際にそこへ書いたコードそのものなので)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const { findById } = require("../lib/registry.js");
const { validate } = require("../lib/schema.js");

// ---- registry.js ----

test("registry: afk エントリが afk.yml を指して section:quality で存在する", () => {
  const entry = findById("afk");
  assert.ok(entry, "afk が registry に存在しない");
  assert.equal(entry.rel, "afk.yml");
  assert.equal(entry.base, "trinityforge");
  assert.equal(entry.section, "quality");
  assert.equal(entry.schema, "tf-afk");
});

// ---- schema.js: validateTfAfk ----

test("schema.js: tf-afk 正常系は空/未設定を許容する(全キー未記載=既定値のまま)", () => {
  assert.deepEqual(validate("tf-afk", {}), []);
});

test("schema.js: tf-afk 正常系フル指定でエラー無し", () => {
  const ok = {
    enabled: true,
    "idle-seconds": 300,
    "kick-after-seconds": 1800,
    "kick-message": "<yellow>長時間の放置により切断しました。",
    notify: true,
    "tab-suffix": true,
    "tab-suffix-text": " <gray>[AFK]</gray>",
    "exempt-permission": "trinityforge.afk.exempt",
    "check-interval-ticks": 40,
    "warn-before-seconds": 30,
    "warn-title": true,
    suppress: { "skill-exp": true, "vanilla-exp": true, "mob-drops": true, "fishing-sell": true }
  };
  assert.deepEqual(validate("tf-afk", ok), []);
});

// 2026-08-18 ユーザー報告「AFKが現状訪れるので title 等でカウントダウンか通知を表示してほしい」。
// Java側(AfkConfig#load)は idle-seconds 以上の予告を idle-seconds-1 へ黙って引き下げるので、
// editor は保存値と実挙動がずれないよう保存時点で弾く(kick-after-seconds と同じ方針)。
test("schema.js: tf-afk warn-before-seconds が idle-seconds 以上はエラー", () => {
  assert.ok(validate("tf-afk", { "idle-seconds": 300, "warn-before-seconds": 300 }).length > 0,
    "idle-seconds ちょうどなのにエラーが出ない");
  assert.ok(validate("tf-afk", { "idle-seconds": 300, "warn-before-seconds": 600 }).length > 0,
    "idle-seconds 超過なのにエラーが出ない");
});

test("schema.js: tf-afk warn-before-seconds が 0(予告しない)と idle-seconds 未満はOK", () => {
  assert.deepEqual(validate("tf-afk", { "idle-seconds": 300, "warn-before-seconds": 0 }), []);
  assert.deepEqual(validate("tf-afk", { "idle-seconds": 300, "warn-before-seconds": 299 }), []);
});

test("schema.js: tf-afk の exempt-permission 空文字は「免除無効」という意味のある値として許容される", () => {
  assert.deepEqual(validate("tf-afk", { "exempt-permission": "" }), []);
});

test("schema.js: tf-afk check-interval-ticks が20未満はエラー", () => {
  const errors = validate("tf-afk", { "check-interval-ticks": 10 });
  assert.ok(errors.length > 0, "20未満なのにエラーが出ない");
});

test("schema.js: tf-afk check-interval-ticks が20ちょうどはOK", () => {
  assert.deepEqual(validate("tf-afk", { "check-interval-ticks": 20 }), []);
});

test("schema.js: tf-afk kick-after-seconds が idle-seconds 未満(0以外)はエラー", () => {
  const errors = validate("tf-afk", { "idle-seconds": 300, "kick-after-seconds": 100 });
  assert.ok(errors.length > 0, "kick-after-seconds < idle-seconds なのにエラーが出ない");
});

test("schema.js: tf-afk kick-after-seconds が0(キックしない)なら idle-seconds 未満でもOK", () => {
  assert.deepEqual(validate("tf-afk", { "idle-seconds": 300, "kick-after-seconds": 0 }), []);
});

test("schema.js: tf-afk kick-after-seconds が idle-seconds 以上ならOK", () => {
  assert.deepEqual(validate("tf-afk", { "idle-seconds": 300, "kick-after-seconds": 300 }), []);
});

test("schema.js: tf-afk 型違い/範囲違いはそれぞれエラー", () => {
  assert.ok(validate("tf-afk", { enabled: "yes" }).length > 0, "enabled");
  assert.ok(validate("tf-afk", { "idle-seconds": "300" }).length > 0, "idle-seconds 型");
  assert.ok(validate("tf-afk", { "idle-seconds": 0 }).length > 0, "idle-seconds は1以上");
  assert.ok(validate("tf-afk", { "kick-after-seconds": -1 }).length > 0, "kick-after-seconds は0以上");
  assert.ok(validate("tf-afk", { "kick-message": 123 }).length > 0, "kick-message");
  assert.ok(validate("tf-afk", { notify: "true" }).length > 0, "notify");
  assert.ok(validate("tf-afk", { "tab-suffix": 1 }).length > 0, "tab-suffix");
  assert.ok(validate("tf-afk", { "tab-suffix-text": 1 }).length > 0, "tab-suffix-text");
  assert.ok(validate("tf-afk", { "exempt-permission": 1 }).length > 0, "exempt-permission 型");
  assert.ok(validate("tf-afk", { "warn-before-seconds": -1 }).length > 0, "warn-before-seconds は0以上");
  assert.ok(validate("tf-afk", { "warn-before-seconds": "30" }).length > 0, "warn-before-seconds 型");
  assert.ok(validate("tf-afk", { "warn-title": 1 }).length > 0, "warn-title 型");
  assert.ok(validate("tf-afk", { suppress: "x" }).length > 0, "suppress 型");
  assert.ok(validate("tf-afk", { suppress: { "skill-exp": "yes" } }).length > 0, "suppress.skill-exp 型");
});

test("schema.js: tf-afk ルートが非マップはエラー", () => {
  assert.ok(validate("tf-afk", []).length > 0);
  assert.ok(validate("tf-afk", "x").length > 0);
});

// ---- app.js: サイドバー非表示接続 / コンパニオン読み込み配線 ----

const appJsSrc = fs.readFileSync(path.join(__dirname, "..", "public", "js", "app.js"), "utf8");

test("app.js: USE_REQUIREMENTS_COMPANION_IDS に afk が含まれる", () => {
  const m = appJsSrc.match(/const USE_REQUIREMENTS_COMPANION_IDS = \[([^\]]*)\];/);
  assert.ok(m, "USE_REQUIREMENTS_COMPANION_IDS の定義が見つからない");
  assert.match(m[1], /"afk"/, `USE_REQUIREMENTS_COMPANION_IDS: ${m[1]}`);
});

test("app.js: HIDDEN_CONFIG_IDS へ USE_REQUIREMENTS_COMPANION_IDS がスプレッドされている(サイドバー非表示)", () => {
  assert.match(appJsSrc, /HIDDEN_CONFIG_IDS = \[[\s\S]*?\.\.\.USE_REQUIREMENTS_COMPANION_IDS/,
    "HIDDEN_CONFIG_IDS へ USE_REQUIREMENTS_COMPANION_IDS がスプレッドされていない");
});

test("app.js: tf-use-requirements ケースが afk コンパニオンを loadConfigCompanion で読み込んで渡す(farming-gimmickと同じ経路)", () => {
  assert.match(appJsSrc, /loadConfigCompanion\("afk",\s*"afkData",\s*options\)/,
    "tf-use-requirements ケースで afk のコンパニオン読み込みが見当たらない");
  assert.match(appJsSrc, /buildUseRequirementsForm\(data,\s*\{\s*afkData\s*\}\)/,
    "buildUseRequirementsForm へ afkData が渡されていない");
});

test("app.js: COMPANION_OPTION_KEYS に afk→afkData が登録されている(マージ後の画面再構築に必須)", () => {
  assert.match(appJsSrc, /"afk":\s*"afkData"/,
    "COMPANION_OPTION_KEYS に afk エントリが無い(マージ後に画面が再構築されず消える不具合の原因になる)");
});

// ---- public/js/tf-crafting-features.js: buildUseRequirementsForm 側の afk 配線 ----
// (構文エラーが入ったままなので require せず、ソース文字列で確認する)

const craftingFeaturesSrc = fs.readFileSync(
  path.join(__dirname, "..", "public", "js", "tf-crafting-features.js"), "utf8"
);

test("tf-crafting-features.js: buildUseRequirementsForm が opts を受け取り buildAfkSection を呼ぶ", () => {
  assert.match(craftingFeaturesSrc,
    /function buildUseRequirementsForm\(data,\s*opts\)/,
    "buildUseRequirementsForm が opts を受け取っていない");
  assert.match(craftingFeaturesSrc, /window\.buildAfkSection\(afkData\)/,
    "buildAfkSection の呼び出しが見当たらない");
});

test("tf-crafting-features.js: buildUseRequirementsForm の getExtraSaves が afk を返す", () => {
  assert.match(craftingFeaturesSrc,
    /getExtraSaves:\s*\(\)\s*=>\s*hasAfk\s*\?\s*\[\{\s*id:\s*"afk",\s*data:\s*afkSubform\.getData\(\)\s*\}\]\s*:\s*\[\]/,
    "getExtraSaves が id:\"afk\" を返す形になっていない");
});

// ---- tf-afk-form.js: buildAfkSection (実行検証) ----

function makeEl(tag, props) {
  const el = {
    tag,
    props: props || {},
    children: [],
    appendChild(c) { this.children.push(c); return c; },
    set innerHTML(_v) { this.children = []; },
    get innerHTML() { return ""; }
  };
  return el;
}

function setupAfkFormStubs() {
  global.window = global.window || {};
  global.document = global.document || {};
  global.window.h = (tag, props, children) => {
    const el = makeEl(tag, props);
    if (Array.isArray(children)) children.forEach((c) => c != null && el.appendChild(c));
    else if (children != null) el.appendChild(children);
    return el;
  };
  global.window.numberInput = (value, onInput) => {
    const el = makeEl("input", { class: "num", value });
    el.__onInput = onInput;
    return el;
  };
  global.window.checkboxInput = (value, onInput) => {
    const el = makeEl("input", { class: "checkbox", checked: Boolean(value) });
    el.__onInput = onInput;
    return el;
  };
  global.window.textInput = (value, onInput) => {
    const el = makeEl("input", { class: "text", value });
    el.__onInput = onInput;
    return el;
  };
  global.window.textInputOnCommit = global.window.textInput;
  // 2026-07-29: MiniMessage を書く欄(kick-message / tab-suffix-text)を Lore/表示名と同じ
  // 着色パレット付き入力(colors.js の richTextInput)へ寄せた。colors.js はここでは
  // 読み込まないので、値と onInput を保持するだけのスタブを置く。
  global.window.richTextInput = (value, mode, onInput) => {
    const el = makeEl("span", { class: "rich-host", mode, value });
    el.__onInput = onInput;
    return el;
  };
  global.window.fieldLabelEl = (key, opts) => makeEl("label", { text: (opts && opts.label) || key });
  delete require.cache[require.resolve("../public/js/tf-afk-form.js")];
  require("../public/js/tf-afk-form.js");
}

test("tf-afk-form.js: buildAfkSection が window へ公開されている", () => {
  setupAfkFormStubs();
  assert.equal(typeof window.buildAfkSection, "function");
});

test("buildAfkSection: 全キーが未設定でも例外を投げずレンダリングできる", () => {
  setupAfkFormStubs();
  const result = window.buildAfkSection({});
  assert.ok(result.element, "element が返っていない");
  assert.equal(typeof result.getData, "function", "getData が関数でない");
});

test("buildAfkSection: getData は往復ロスレス(既存値をそのまま保持する)", () => {
  setupAfkFormStubs();
  const afkData = {
    enabled: false,
    "idle-seconds": 120,
    "kick-after-seconds": 0,
    "kick-message": "custom message",
    notify: false,
    "tab-suffix": false,
    "tab-suffix-text": "",
    "exempt-permission": "",
    "check-interval-ticks": 100,
    "warn-before-seconds": 15,
    "warn-title": false,
    suppress: { "skill-exp": false, "vanilla-exp": true, "mob-drops": false, "fishing-sell": true }
  };
  const original = JSON.parse(JSON.stringify(afkData));
  const result = window.buildAfkSection(afkData);
  assert.deepEqual(result.getData(), original, "無編集での往復がロスレスでない");
});

test("buildAfkSection: suppress が未指定でも空マップを補って壊れない(working.suppress = {})", () => {
  setupAfkFormStubs();
  const result = window.buildAfkSection({ enabled: true });
  const data = result.getData();
  assert.deepEqual(data.suppress, {});
});

test("buildAfkSection: チェックボックス操作で enabled が反映される", () => {
  setupAfkFormStubs();
  const result = window.buildAfkSection({ enabled: false });
  function findCheckbox(el) {
    if (!el || !el.children) return null;
    if (el.props && el.props.class === "checkbox") return el;
    for (const c of el.children) {
      const found = findCheckbox(c);
      if (found) return found;
    }
    return null;
  }
  const cb = findCheckbox(result.element);
  assert.ok(cb, "checkbox が描画されていない");
  cb.__onInput(true);
  assert.equal(result.getData().enabled, true, "checkbox操作が working へ反映されていない");
});

// 2026-07-29 ユーザー要望「使用制限スイッチの AFK メッセージ設定が Lore 設定のような GUI でない」の回帰テスト。
// メッセージ欄は素の <input> ではなく、着色パレット付きの共通 richTextInput を使う。
// exempt-permission は権限ノードで色を付けられないので従来どおり textInput のまま。
test("buildAfkSection: メッセージ欄は richTextInput(minimessage) を使う", () => {
  setupAfkFormStubs();
  const result = window.buildAfkSection({
    "kick-message": "<yellow>bye",
    "tab-suffix-text": " <gray>[AFK]"
  });

  const riches = [];
  (function walk(el) {
    if (!el) return;
    if (el.props && el.props.class === "rich-host") riches.push(el);
    (el.children || []).forEach(walk);
  })(result.element);

  assert.equal(riches.length, 2, "kick-message / tab-suffix-text の2件がリッチ入力でない");
  for (const r of riches) assert.equal(r.props.mode, "minimessage");
  assert.deepEqual(riches.map((r) => r.props.value), ["<yellow>bye", " <gray>[AFK]"]);

  // 編集が working へ戻ることまで確かめる(差し替えで保存経路を壊していない)。
  riches[0].__onInput("<red>changed");
  assert.equal(result.getData()["kick-message"], "<red>changed");
});
