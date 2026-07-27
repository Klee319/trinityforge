"use strict";

// tf-dungeon-forms.js buildDungeonGatesForm の修正2/3 (2026-07-27) の回帰テスト。
//
// 修正2: 必要鍵アイテムをカタログ品/ArsPaper品/バニラMaterialから選べるようにした。
//        yml側のキーは新設の key-item を使い、旧 key-material は書き込み時に削除して
//        二重定義を残さない。旧データ(key-materialのみ)を開いた場合は値を引き継いで表示する。
// 修正3: ゲート編集画面の「EliteMobsパッケージ」入力欄を削除した。content-package は
//        ゲートID確定時に window.EM_DUNGEONS の台帳から自動導出する。台帳に一致が無い
//        場合は既存の content-package を保持したまま触らない。
//
// listSelect 自体のDOM描画(開閉/位置計算)は util.js の既存範囲であり、ここでは
// buildDungeonGatesForm が listSelect/itemRefSelect へ渡す cfg (value/options/onChange/onCommit)
// の中身と、その呼び出し結果として working オブジェクトがどう変化するかを検証する。

const test = require("node:test");
const assert = require("node:assert/strict");

function makeEl(tag, attrs) {
  const el = {
    tag,
    props: attrs || {},
    children: [],
    // このフォームは window.collapsibleCard を通る。実装が el.style.display を直接書くため、
    // style を持たないスタブだと TypeError で落ちる(base-stats のスタブを流用すると踏む)。
    style: {},
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    appendChild(c) { if (c != null && c !== false) el.children.push(c); return c; },
    addEventListener() {},
    querySelector() { return null; },
    querySelectorAll() { return []; }
  };
  Object.defineProperty(el, "innerHTML", {
    get() { return ""; },
    set() { el.children = []; }
  });
  return el;
}

// util.js を実物のまま読み込み、window.h だけ document 不要の軽量版へ差し替え、
// window.listSelect は cfg をそのまま捕捉するスタブへ差し替える。
// (window.itemRefSelect / window.fieldLabelEl / window.numberInput / window.textInput は
//  util.js の実装をそのまま使う — document.* を直接叩かない純粋な h() ラッパーのため。)
function setupDom() {
  global.window = global.window || {};
  global.document = global.document || {};
  global.alert = () => {};

  delete require.cache[require.resolve("../public/js/util.js")];
  require("../public/js/util.js");

  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children != null) {
      (Array.isArray(children) ? children : [children]).forEach((c) => c != null && c !== false && el.appendChild(c));
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

  global.window.MATERIALS = ["DIAMOND", "IRON_INGOT"];
  // ダミー台帳: known_dungeon だけ EliteMobs パッケージを持つ。
  global.window.EM_DUNGEONS = {
    isLoaded: () => true,
    load: () => Promise.resolve(),
    dungeonOptions: () => [
      { value: "known_dungeon", primary: "既知ダンジョン", secondary: "known_dungeon" }
    ],
    packageOptions: () => [],
    get: (world) => (world === "known_dungeon" ? { world: "known_dungeon", package: "known_pkg" } : null)
  };

  delete require.cache[require.resolve("../public/js/tf-dungeon-forms.js")];
  require("../public/js/tf-dungeon-forms.js");

  return captured;
}

const KEY_PLACEHOLDER = "アイテムを選択…（空欄＝鍵なし）";

test("ゲート編集画面に「EliteMobsパッケージ」の入力欄/文言が無い", () => {
  const captured = setupDom();
  const data = { gates: { dungeon_world: { "required-combat-level": 1 } } };
  const result = global.window.buildDungeonGatesForm(data, { catalogCandidates: [] });

  // 旧実装のEliteMobsパッケージ専用セレクト(placeholder固有文言)が描画されていない
  assert.ok(!captured.some((c) => c.placeholder === "選択…（空欄＝紐づけなし）"),
    "旧EliteMobsパッケージ専用セレクトがまだ描画されている");

  // フィールドラベルとしても「EliteMobsパッケージ」の文言が出てこない
  const texts = [];
  (function walk(el) {
    if (!el || !Array.isArray(el.children)) return;
    if (el.props && typeof el.props.text === "string") texts.push(el.props.text);
    el.children.forEach(walk);
  })(result.element);
  assert.ok(!texts.includes("EliteMobsパッケージ"), "「EliteMobsパッケージ」の文言がまだ残っている");
});

test("鍵アイテムをカタログ品から選ぶと key-item に書かれ、旧 key-material は削除される", () => {
  const captured = setupDom();
  const data = { gates: { dungeon_world: { "required-combat-level": 1, "key-material": "DIAMOND" } } };
  global.window.buildDungeonGatesForm(data, {
    catalogCandidates: [{ id: "special_key", label: "特別な鍵" }]
  });

  const keyCfgs = captured.filter((c) => c.placeholder === KEY_PLACEHOLDER);
  assert.equal(keyCfgs.length, 1, "鍵アイテムセレクトが1つ描画されているはず");
  // 初期表示は旧 key-material の値を引き継ぐ
  assert.equal(keyCfgs[0].value, "DIAMOND");
  // カタログ品が候補に出る
  assert.ok(keyCfgs[0].options.some((o) => o.value === "special_key" && o.primary === "特別な鍵"));

  keyCfgs[0].onChange("special_key");

  assert.equal(data.gates.dungeon_world["key-item"], "special_key");
  assert.equal("key-material" in data.gates.dungeon_world, false,
    "key-item へ移行したら旧 key-material は消えているはず");
});

test("旧 key-material のみの既存データを開いても値が表示され、未編集なら値は失われない", () => {
  const captured = setupDom();
  const data = { gates: { legacy_world: { "key-material": "IRON_INGOT" } } };
  const result = global.window.buildDungeonGatesForm(data, { catalogCandidates: [] });

  const keyCfgs = captured.filter((c) => c.placeholder === KEY_PLACEHOLDER);
  assert.equal(keyCfgs[0].value, "IRON_INGOT", "旧key-materialの値が初期表示に引き継がれていない");

  const saved = result.getData();
  assert.equal(saved.gates.legacy_world["key-material"], "IRON_INGOT",
    "未編集のまま保存しても旧データが消えてはいけない(ロスレス往復)");
  assert.equal("key-item" in saved.gates.legacy_world, false);
});

test("鍵アイテムを空にすると key-item / key-material の両方が消える", () => {
  const captured = setupDom();
  const data = { gates: { dungeon_world: { "key-item": "special_key" } } };
  global.window.buildDungeonGatesForm(data, { catalogCandidates: [] });

  const keyCfgs = captured.filter((c) => c.placeholder === KEY_PLACEHOLDER);
  keyCfgs[0].onChange("");

  assert.equal("key-item" in data.gates.dungeon_world, false);
  assert.equal("key-material" in data.gates.dungeon_world, false);
});

test("ゲートIDを台帳にあるダンジョンへ変えると content-package が自動で入る", () => {
  const captured = setupDom();
  const data = { gates: { custom_world: { "required-combat-level": 1 } } };
  global.window.buildDungeonGatesForm(data, { catalogCandidates: [] });

  const headCfg = captured.find((c) => c.className === "entry-key-input" && c.value === "custom_world");
  assert.ok(headCfg, "ヘッダのダンジョン選択セレクトが見つからない");

  const committed = headCfg.onCommit("known_dungeon");
  assert.equal(committed, true);

  assert.ok(!("custom_world" in data.gates), "renameKeyでIDが差し替わっているはず");
  assert.equal(data.gates.known_dungeon["content-package"], "known_pkg",
    "台帳にある既知ダンジョンを選んだのに content-package が自動で入っていない");
});

test("台帳に無いIDへ変えても既存の content-package は消えない(上書きしない)", () => {
  const captured = setupDom();
  const data = { gates: { custom_world: { "content-package": "manual_pkg" } } };
  global.window.buildDungeonGatesForm(data, { catalogCandidates: [] });

  const headCfg = captured.find((c) => c.className === "entry-key-input" && c.value === "custom_world");
  headCfg.onCommit("still_unknown_world");

  assert.equal(data.gates.still_unknown_world["content-package"], "manual_pkg",
    "台帳に無いIDへ変えたら手書きのcontent-packageが消えてしまった");
});

test("台帳にある既知ダンジョンへ変えると、既存の content-package も新しい値へ上書きされる", () => {
  const captured = setupDom();
  const data = { gates: { custom_world: { "content-package": "old_manual_pkg" } } };
  global.window.buildDungeonGatesForm(data, { catalogCandidates: [] });

  const headCfg = captured.find((c) => c.className === "entry-key-input" && c.value === "custom_world");
  headCfg.onCommit("known_dungeon");

  assert.equal(data.gates.known_dungeon["content-package"], "known_pkg",
    "選び直した以上、新しいダンジョンのpackageへ揃うはず");
});

test("別名(aliases)欄は引き続き手編集できる", () => {
  const captured = setupDom();
  const data = { gates: { dungeon_world: { aliases: ["blueprint_a"] } } };
  const result = global.window.buildDungeonGatesForm(data, { catalogCandidates: [] });
  void captured;
  assert.deepEqual(result.getData().gates.dungeon_world.aliases, ["blueprint_a"],
    "aliasesが往復で消えている");
});
