"use strict";

// ---------------------------------------------------------------------------
// 2026-08-16: マナ基礎3キー(default-max / default-regen-rate / regen-interval-ticks)を
// TrinityForge の combat/base-stats.yml から ArsPaper の config.yml (mana.*) へ戻した件の回帰テスト。
//
// 背景: この3キーは 2026-07-25 に TF 側へ移管されたが stats/lore.yml に非登録だったため、
// editor のどの画面にも出ず**手編集でしか変えられなかった**。真源を ArsPaper へ戻し、
// 「ArsPaper 全体設定 (config)」画面のマナカードから編集できるようにする。
//
// このテストが押さえるのは、実際に踏んだ/踏みうる無言の事故4つ:
//  (1) 空欄が 0 として書き込まれる(numField の既定挙動)。default-max: 0 は「最大マナ0で魔法が
//      一切撃てない」、regen-interval-ticks: 0 は「period=0 で毎tick実行」になる。
//  (2) スキーマ検証を validateArsConfig の `source-auto-consume` 早期 return より後ろへ書くと、
//      sac を持たない config.yml では**一度も走らない**(既存 fixture は常に sac を持つので緑のまま)。
//  (3) editor の既定値が ArsPaper(Java)の既定値とずれると「開いて保存しただけで yml の意味が変わる」。
//  (4) 開いて保存しただけで未設定キーが生える(往復差分)。
// ---------------------------------------------------------------------------

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const { validate } = require("../lib/schema.js");

const EDITOR_ROOT = path.resolve(__dirname, "..");
const REPO_ROOT = path.resolve(EDITOR_ROOT, "..", "..");
const FORM_SRC = path.join(EDITOR_ROOT, "public", "js", "tf-phase3-forms.js");
const FORK_CONFIG = path.join(
  REPO_ROOT, "fork-handoff", "arspaper", "fork", "src", "main", "resources", "config.yml");

// 契約値(2026-08-16 のユーザー決定)。移設でバランスは一切変えないので、移設前の
// combat/base-stats.yml の出荷値 mana-max-base:100 / mana-regen-base:5 /
// mana-regen-interval-ticks:20 と同じでなければならない。
const CONTRACT_DEFAULTS = {
  "default-max": 100,
  "default-regen-rate": 5,
  "regen-interval-ticks": 20
};

// ---------------------------------------------------------------------------
// ブラウザ用 IIFE を Node で読むための最小スタブ
// (ars-config-form-tdz.test.js / drop-table-logic.test.js と同じ手法)。
// numberInput の onInput コールバックを拾って、欄の**挙動**まで検査できるようにする。
// ---------------------------------------------------------------------------
function makeEl(tag, props) {
  return {
    tag,
    props: props || {},
    children: [],
    appendChild(c) { this.children.push(c); return c; },
    set innerHTML(_v) { this.children = []; },
    get innerHTML() { return ""; }
  };
}

function loadForm() {
  // field(key, control, opts) は control(=numberInput)を先に評価してから fieldLabelEl を呼ぶ。
  // その順序を利用して「直前の numberInput」と「そのラベル」を突き合わせる。
  const fields = Object.create(null);
  let pending = null;

  global.window = global.window || {};
  global.window.h = (tag, props, children) => {
    const el = makeEl(tag, props);
    if (Array.isArray(children)) children.forEach((c) => c != null && el.appendChild(c));
    else if (children != null) el.appendChild(children);
    return el;
  };
  global.window.numberInput = (value, onInput, opts) => {
    pending = { value, onInput, opts: opts || {} };
    return makeEl("input", { class: "num" });
  };
  global.window.fieldLabelEl = (key, opts) => {
    if (pending) {
      fields[key] = Object.assign({}, pending, {
        label: (opts && opts.label) || "",
        desc: (opts && opts.desc) || ""
      });
      pending = null;
    }
    return makeEl("label", { key, opts });
  };
  global.window.listSelect = () => { pending = null; return makeEl("div", { class: "list-select" }); };
  global.window.checkboxInput = () => { pending = null; return makeEl("input", { class: "checkbox" }); };
  global.window.textInput = () => { pending = null; return makeEl("input", { class: "text" }); };
  global.window.textInputOnCommit = global.window.textInput;

  delete require.cache[require.resolve("../public/js/tf-phase3-forms.js")];
  require("../public/js/tf-phase3-forms.js");
  return { fields, window: global.window };
}

// ---------------------------------------------------------------------------
// 1. 既定値: editor が持つ既定値は契約値 100 / 5 / 20 と完全に一致する
// ---------------------------------------------------------------------------

test("既定値: MANA_BASE_DEFAULTS は 100 / 5 / 20 (ArsPaper 側 Java の既定値と一致していること)", () => {
  const { window } = loadForm();
  assert.deepEqual(window.MANA_BASE_DEFAULTS, CONTRACT_DEFAULTS,
    "editor の既定値が ArsPaper の既定値とずれると、開いて保存しただけで yml の意味が変わる");
});

// ---------------------------------------------------------------------------
// 2. フォーム: マナカードに3欄があり、日本語ラベルと ? ホバー説明が付いている
// ---------------------------------------------------------------------------

test("フォーム: マナカードに基礎3欄があり、日本語ラベルが付いている", () => {
  const { fields, window } = loadForm();
  window.buildArsConfigForm({});

  assert.equal(fields["default-max"] && fields["default-max"].label, "最大マナの基礎値");
  assert.equal(fields["default-regen-rate"] && fields["default-regen-rate"].label, "マナ自然回復量");
  assert.equal(fields["regen-interval-ticks"] && fields["regen-interval-ticks"].label, "マナ回復周期(tick)");

  // 既存欄を巻き添えで消していないこと。
  assert.ok(fields["per-glyph-unlock-bonus"], "既存のグリフ解放ボーナス欄が消えている");
  assert.ok(fields["max-percent-cap"], "既存の%上昇キャップ欄が消えている");
});

test("フォーム: 3欄の ? ホバー説明が日本語で、既定値と再起動要否を明記している", () => {
  const { fields, window } = loadForm();
  window.buildArsConfigForm({});

  for (const key of Object.keys(CONTRACT_DEFAULTS)) {
    const desc = fields[key].desc;
    assert.ok(desc && desc.length > 0, `${key}: ? ホバー説明が空(欄の意味が画面から分からない)`);
    assert.ok(desc.includes(String(CONTRACT_DEFAULTS[key])),
      `${key}: 説明に既定値 ${CONTRACT_DEFAULTS[key]} が書かれていない(空欄にした人が何になるか分からない)`);
  }

  const intervalDesc = fields["regen-interval-ticks"].desc;
  assert.ok(intervalDesc.includes("20 = 1秒"),
    "回復周期の説明に「20 = 1秒」が無い(tick を秒に換算できない)");
  assert.ok(intervalDesc.includes("サーバ再起動"),
    "回復周期の説明に「サーバ再起動が必要」が無い。回復タスクは起動時に間隔が焼き込まれ "
    + "/ars reload では張り替わらないため、書かないと『保存したのに効かない』事故になる");
});

// ---------------------------------------------------------------------------
// 3. 空欄の意味: キーごと削除 = ArsPaper 既定値。0 を書き込まない。
//    (numField は clearable を渡さないと空欄を 0 として書き込む = 事故(1))
// ---------------------------------------------------------------------------

test("空欄はキーごと削除する(0 へ丸めない)", () => {
  const { fields, window } = loadForm();
  const data = { mana: { "default-max": 250, "default-regen-rate": 9, "regen-interval-ticks": 10 } };
  const form = window.buildArsConfigForm(data);

  for (const key of Object.keys(CONTRACT_DEFAULTS)) {
    fields[key].onInput(null); // 入力欄を空にした時の経路
  }
  const saved = form.getData();
  for (const key of Object.keys(CONTRACT_DEFAULTS)) {
    assert.equal(Object.prototype.hasOwnProperty.call(saved.mana, key), false,
      `mana.${key} が空欄で 0 として書き込まれている。`
      + "default-max:0 は最大マナ0で魔法が撃てず、regen-interval-ticks:0 は毎tick実行になる");
  }
});

test("入力した値は整数として保存される(小数は切り捨て)", () => {
  const { fields, window } = loadForm();
  const form = window.buildArsConfigForm({});

  fields["default-max"].onInput(250);
  fields["default-regen-rate"].onInput(7.9);
  fields["regen-interval-ticks"].onInput(10);

  const saved = form.getData();
  assert.equal(saved.mana["default-max"], 250);
  assert.equal(saved.mana["default-regen-rate"], 7, "int 指定なのに小数のまま保存されている");
  assert.equal(saved.mana["regen-interval-ticks"], 10);
});

// ---------------------------------------------------------------------------
// 4. 往復ロスレス: 開いて保存しただけでキーが生えない / 既存キーが消えない
// ---------------------------------------------------------------------------

test("往復ロスレス: 空の config を開いて保存しても mana.default-* は生えない", () => {
  const { window } = loadForm();
  const saved = window.buildArsConfigForm({}).getData();
  for (const key of Object.keys(CONTRACT_DEFAULTS)) {
    assert.equal(Object.prototype.hasOwnProperty.call(saved.mana || {}, key), false,
      `開いて保存しただけで mana.${key} が生えている(往復差分)`);
  }
});

test("往復ロスレス: 既存の mana セクションの他キーが消えない", () => {
  const { window } = loadForm();
  const existing = {
    mana: {
      "per-glyph-unlock-bonus": 5,
      "max-percent-cap": 100,
      "source-auto-consume": {
        "cooldown-seconds": 10,
        items: { "custom:source_berry": { mana: 100, "cooldown-seconds": 10 } }
      },
      "default-max": 120
    }
  };
  const saved = window.buildArsConfigForm(existing).getData();

  assert.equal(saved.mana["per-glyph-unlock-bonus"], 5);
  assert.equal(saved.mana["max-percent-cap"], 100);
  assert.deepEqual(saved.mana["source-auto-consume"], {
    "cooldown-seconds": 10,
    items: { "custom:source_berry": { mana: 100, "cooldown-seconds": 10 } }
  }, "この画面では触らない source-auto-consume が書き換わっている");
  assert.equal(saved.mana["default-max"], 120, "既存の基礎値が失われている");
});

// ---------------------------------------------------------------------------
// 5. スキーマ: 早期 return の穴(事故(2))と値域
// ---------------------------------------------------------------------------

test("スキーマ: source-auto-consume が無い config でも基礎3キーの検証が走る(早期returnの穴)", () => {
  // ここが validateArsConfig の `if (sac == null) return;` より後ろに書かれていると、
  // このケースだけ検証が丸ごと空振りする。既存 fixture は常に sac を持つので気づけない。
  for (const key of Object.keys(CONTRACT_DEFAULTS)) {
    const errors = validate("ars-config", { mana: { [key]: "abc" } });
    assert.ok(errors.some((e) => e.includes(`mana.${key}`)),
      `mana.${key} の検証が source-auto-consume 無しで走っていない: ${JSON.stringify(errors)}`);
  }
});

test("スキーマ: 契約どおりの既定値と妥当な値は通る", () => {
  assert.deepEqual(validate("ars-config", { mana: Object.assign({}, CONTRACT_DEFAULTS) }), []);
  assert.deepEqual(validate("ars-config", {
    mana: { "default-max": 250, "default-regen-rate": 0, "regen-interval-ticks": 10 }
  }), [], "自然回復量 0 は「自然回復しない」という意味のある設定なので弾いてはいけない");
});

test("スキーマ: 未設定は正当(ArsPaper の既定値に委ねる)", () => {
  assert.deepEqual(validate("ars-config", { mana: {} }), [],
    "必須にすると、まだ3キーを持たない既存の config.yml が開けなくなる");
  assert.deepEqual(validate("ars-config", {}), []);
});

test("スキーマ: サーバが壊れる値(最大マナ0 / 回復周期0)と小数・負値は弾く", () => {
  const err = (data) => validate("ars-config", data);

  assert.ok(err({ mana: { "default-max": 0 } }).some((e) => e.includes("default-max")),
    "最大マナ0は魔法が一切撃てなくなるので editor から保存させない");
  assert.ok(err({ mana: { "regen-interval-ticks": 0 } }).some((e) => e.includes("regen-interval-ticks")),
    "回復周期0tickは period=0 で毎tick実行(実質暴走)になるので弾く");

  assert.ok(err({ mana: { "default-max": -1 } }).some((e) => e.includes("default-max")));
  assert.ok(err({ mana: { "default-regen-rate": -1 } }).some((e) => e.includes("default-regen-rate")));
  assert.ok(err({ mana: { "default-max": 100.5 } }).some((e) => e.includes("default-max")),
    "Java 側は getInt で読むので小数は無言で切り捨てられる。editor では弾く");
  assert.ok(err({ mana: { "regen-interval-ticks": 20.5 } }).some((e) => e.includes("regen-interval-ticks")));
});

test("スキーマ: 既存の source-auto-consume 検証を壊していない", () => {
  // 基礎3キーの検証を早期 return の前へ差し込んだことで、後段が動かなくなっていないこと。
  assert.deepEqual(validate("ars-config", {
    mana: { "default-max": 100, "source-auto-consume": { "cooldown-seconds": 10, items: { source_berry: 100 } } }
  }), []);
  assert.ok(validate("ars-config", {
    mana: { "default-max": 100, "source-auto-consume": { "cooldown-seconds": -1, items: {} } }
  }).some((e) => e.includes("cooldown-seconds")), "後段の検証が動かなくなっている");
});

// ---------------------------------------------------------------------------
// 6. ミラー: lib/ と public/js/ で二重管理になっていないこと
//
// この機能の実体は「フォーム = public/js/tf-phase3-forms.js」「検証 = lib/schema.js」の
// 片側1本ずつで、同名ミラーは存在しない。**将来コピーが生えたら必ず片方だけ直されて無言で壊れる**
// ので、コピーの不在自体を固定する。あわせて、フォームが編集するキー集合とスキーマが検証する
// キー集合が一致していること(ファイルをまたいだ綴りのズレ)を挙動で突き合わせる。
// ---------------------------------------------------------------------------

test("ミラー: tf-phase3-forms.js / schema.js に同名の二重管理コピーが無い", () => {
  const stale = [
    path.join(EDITOR_ROOT, "lib", "tf-phase3-forms.js"),
    path.join(EDITOR_ROOT, "public", "js", "schema.js")
  ];
  for (const p of stale) {
    assert.equal(fs.existsSync(p), false,
      `ミラーが増えている: ${p}。片方だけ直されて無言で壊れるので、増やすなら両方を同期する`
      + "テストをここへ追加すること");
  }
});

test("ミラー: フォームが編集するキーとスキーマが検証するキーが一致している", () => {
  const { fields, window } = loadForm();
  window.buildArsConfigForm({});

  for (const key of Object.keys(window.MANA_BASE_DEFAULTS)) {
    assert.ok(fields[key], `フォームに ${key} の欄が無い(定数だけ増えて画面に出ていない)`);
    const errors = validate("ars-config", { mana: { [key]: "abc" } });
    assert.ok(errors.some((e) => e.includes(`mana.${key}`)),
      `lib/schema.js が mana.${key} を検証していない(フォームとスキーマでキーがズレている)`);
  }
});

// ---------------------------------------------------------------------------
// 7. 実データ: ArsPaper fork の出荷 config.yml と既定値が一致していること。
//    fork は親の .gitignore で除外されているため、クリーンクローンには存在しない → スキップ。
//    fork レーンが3キーをまだ書いていない間もスキップする(別レーンの未着地であって
//    このレーンの回帰ではない)。統合後は値の一致を実データで固定する。
// ---------------------------------------------------------------------------

test("実データ: 出荷 config.yml の mana.* が editor の既定値と一致する", (t) => {
  if (!fs.existsSync(FORK_CONFIG)) {
    t.skip("ArsPaper fork 未取得(.gitignore除外)の環境ではスキップ");
    return;
  }
  const data = YAML.parse(fs.readFileSync(FORK_CONFIG, "utf8"));
  const mana = (data && data.mana) || {};
  const declared = Object.keys(CONTRACT_DEFAULTS).filter(
    (k) => Object.prototype.hasOwnProperty.call(mana, k));
  if (declared.length === 0) {
    t.skip("出荷 config.yml にマナ基礎3キーがまだ無い(ArsPaper fork レーンが未着地)");
    return;
  }
  const { window } = loadForm();
  for (const key of Object.keys(CONTRACT_DEFAULTS)) {
    assert.equal(mana[key], window.MANA_BASE_DEFAULTS[key],
      `出荷 config.yml の mana.${key} と editor の既定値がずれている`);
  }
});
