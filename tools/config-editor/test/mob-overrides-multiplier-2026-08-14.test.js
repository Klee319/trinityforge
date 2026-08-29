"use strict";

// combat/mob-overrides.yml の「難易度倍率」2キー (stats.max-health-multiplier /
// stats.attack-power-multiplier、2026-08-14 新設) を editor が落とさずに読み書きできることの回帰テスト。
//
// 【このテストが守っているもの】
//  (1) 往復ロスレス — 出荷 yml を開いて【何も操作せず】保存したとき、1バイトも意味が変わらないこと。
//  (2) 値の保存 — 倍率キーを持つ yml を開いて保存すると値が保たれること。
//  (3) **既定値ドリフトの禁止** — 保存前後で倍率キーの集合(パスと値)が完全に同一であること、
//      および倍率キーを持たない yml を保存しても 1.0 が書き込まれないこと。
//      これが本命。editor の normalize 既定値が Java の既定値とずれると「開いて保存しただけ」で
//      yml の意味が変わる事故を過去に踏んでいる。倍率キーの既定は 1.0 ではなく【未設定】。
//      倍率は層をまたいで【掛け合わさる】(default 1.5 × ダンジョン 2.0 × モブ 2.0 = 6.0 倍)ので
//      1.0 は掛け算として完全な no-op であり、上位スコープを打ち消す効果は無い。それでも 1.0 を
//      書き込まないのは、全モブに意味の無い行が生えて yml の差分が読めなくなるから。
//  (4) 倍率が %入力(×100表示)へ流れないこと — 割合ではなく倍率(中立値 1.0)なので。
//
// ブラウザ用 IIFE を Node で読むため、tier-table-editor.test.js と同じ手法で window.* を最小スタブする。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const { validate } = require("../lib/schema");

const SHIPPED_YML = path.join(
  __dirname, "..", "..", "..",
  "TrinityForge", "src", "main", "resources", "combat", "mob-overrides.yml"
);

// ============================================================
// DOM スタブ
// ============================================================

function makeFakeEl(tag, attrs) {
  const el = {
    tag,
    attrs: attrs || {},
    children: [],
    _onclick: attrs && typeof attrs.onclick === "function" ? attrs.onclick : null,
    _onchange: attrs && typeof attrs.onchange === "function" ? attrs.onchange : null,
    value: attrs && attrs.value != null ? attrs.value : "",
    textContent: attrs && attrs.text != null ? attrs.text : "",
    appendChild(child) {
      if (child !== null && child !== undefined && child !== false) el.children.push(child);
      return child;
    },
    querySelector() { return null; },
    querySelectorAll() { return []; },
    addEventListener() {}
  };
  Object.defineProperty(el, "innerHTML", {
    get() { return ""; },
    set() { el.children = []; }
  });
  return el;
}

global.window = global.window || {};
global.window.h = function h(tag, attrs, children) {
  const el = makeFakeEl(tag, attrs);
  if (children !== undefined && children !== null) {
    const arr = Array.isArray(children) ? children : [children];
    arr.forEach((c) => el.appendChild(c));
  }
  return el;
};
// フォーム行を「どの yml キーの行か」で引けるようにするため、キーを attrs に残す。
global.window.fieldLabelEl = (key, opts) => makeFakeEl("span", { class: "form-label", fieldKey: key, opts });
global.window.checkboxInput = () => makeFakeEl("input");
global.window.collapsibleCard = (head, body) => {
  const el = makeFakeEl("div");
  (Array.isArray(head) ? head : [head]).forEach((c) => el.appendChild(c));
  (Array.isArray(body) ? body : [body]).forEach((c) => el.appendChild(c));
  return el;
};
global.window.numberInput = (value, onInput) => {
  const el = makeFakeEl("input", { class: "number-input", value });
  el.trigger = (v) => onInput(v);
  return el;
};
global.window.textInput = (value, onInput) => {
  const el = makeFakeEl("input", { value });
  el.trigger = (v) => onInput(v);
  return el;
};
global.window.textInputOnCommit = global.window.textInput;
global.window.materialInput = (value, listId, onInput) => {
  const el = makeFakeEl("span", { value });
  el.trigger = (v) => onInput(v);
  return el;
};
global.window.listSelect = (cfg) => {
  const el = makeFakeEl("span", { value: cfg && cfg.value });
  el.trigger = (v) => { if (cfg && typeof cfg.onChange === "function") cfg.onChange(v); };
  return el;
};
// 割合フィールド用の %入力。倍率キーがここを通っていないことを見るために、通過キーを記録する。
const rateControlCalls = [];
global.window.rateValueControl = (value, setter) => {
  rateControlCalls.push(value);
  const el = makeFakeEl("span", { class: "pct-input" });
  el.trigger = (v) => setter(v);
  return el;
};

require("../public/js/mob-forms.js");

// ============================================================
// ヘルパー
// ============================================================

function collectAll(root, pred, out) {
  out = out || [];
  if (!root || typeof root !== "object") return out;
  if (pred(root)) out.push(root);
  for (const c of root.children || []) collectAll(c, pred, out);
  return out;
}

/** fieldRow(key, control) が作った行のうち、ラベルの yml キーが key のものの control を全部返す。 */
function controlsForKey(root, key) {
  return collectAll(root, (el) => {
    const label = el.children && el.children[0];
    return !!(label && label.attrs && label.attrs.fieldKey === key);
  }).map((row) => row.children[1]);
}

function deepClone(v) { return JSON.parse(JSON.stringify(v)); }

function buildForm(data) {
  return window.buildMobOverridesForm(data, { catalogCandidates: [] });
}

// ============================================================
// (1) 往復ロスレス: 出荷 yml を開いて何も操作せず保存する
// ============================================================

test("ロスレス: 出荷 mob-overrides.yml を読み込んで何も操作せず保存しても差分ゼロ", () => {
  assert.ok(fs.existsSync(SHIPPED_YML), `not found: ${SHIPPED_YML}`);
  const original = YAML.parse(fs.readFileSync(SHIPPED_YML, "utf8"));
  // 前提: 検査が空振りしていないこと(スコープもモブも実データがある)。
  const scopeNames = Object.keys(original.overrides);
  assert.ok(scopeNames.length >= 10, `スコープが少なすぎる (${scopeNames.length})。前提を見直すこと`);

  const working = deepClone(original);
  const form = buildForm(working);
  const saved = form.getData();

  assert.deepEqual(saved, original,
    "何も操作していないのに mob-overrides.yml の内容が変形した(倍率キーの追加でロスレスが壊れた)");
});

/** 表示用にセグメント列を `overrides.em_x.stats.max-health-multiplier` の形へ整形する。 */
function formatTrail(trail) {
  return trail
    .map((seg) => (typeof seg === "number" ? `[${seg}]` : `.${seg}`))
    .join("")
    .replace(/^\./, "");
}

/**
 * データ構造を再帰的に歩き、キー名に "multiplier" を含む全エントリを Map で返す。
 * 配列添字もセグメントに含めるので、リストの中に倍率が生えても検出できる。
 *
 * Map のキーは【セグメント配列を JSON 化した文字列】であって、表示用のドット連結パスではない。
 * ドット連結だと `{"a.b": {...}}` と `{"a": {"b": ...}}` が同じ文字列になり、
 * 片方が Map から静かに落ちて検査をすり抜ける。値は表示用パスと一緒に持たせる。
 */
function collectMultiplierEntries(node, trail, out) {
  out = out || new Map();
  trail = trail || [];
  if (node === null || typeof node !== "object") return out;
  if (Array.isArray(node)) {
    node.forEach((v, i) => collectMultiplierEntries(v, trail.concat([i]), out));
    return out;
  }
  for (const [key, value] of Object.entries(node)) {
    const next = trail.concat([key]);
    if (key.includes("multiplier")) {
      out.set(JSON.stringify(next), { path: formatTrail(next), value });
    }
    collectMultiplierEntries(value, next, out);
  }
  return out;
}

/**
 * 値の同一性。倍率キーの値は数値のはずだが、将来 `xxx-multipliers:` のような
 * マップ/リストが増えたときに【参照が違うだけで毎回「値が変わった」と誤報する】のを避けるため、
 * オブジェクトは構造で比較する。
 */
function sameMultiplierValue(a, b) {
  if (a !== null && typeof a === "object") return JSON.stringify(a) === JSON.stringify(b);
  return Object.is(a, b);
}

/** 倍率エントリの Map 同士を突き合わせ、増えた/消えた/値が変わったキーを列挙する。 */
function diffMultiplierEntries(before, after) {
  const added = [];
  const removed = [];
  const changed = [];
  for (const [id, entry] of after) {
    if (!before.has(id)) {
      added.push(`${entry.path} = ${JSON.stringify(entry.value)}`);
    } else if (!sameMultiplierValue(before.get(id).value, entry.value)) {
      changed.push(`${entry.path}: ${JSON.stringify(before.get(id).value)} -> ${JSON.stringify(entry.value)}`);
    }
  }
  for (const [id, entry] of before) {
    if (!after.has(id)) removed.push(`${entry.path} = ${JSON.stringify(entry.value)}`);
  }
  return { added, removed, changed };
}

function formatMultiplierDiff(label, list) {
  return `  ${label}: ${list.length ? "\n    - " + list.join("\n    - ") : "なし"}`;
}

// 【2026-08-14 書き直し】以前ここは「保存後の YAML 文字列に multiplier という語が
// 1つも出ないこと」を見ていた。当時は出荷 yml に倍率キーが無かったのでガードとして
// 成立していたが、難易度再設計で出荷 yml へ倍率キーを多数書いた時点で
// /multiplier/.test(text) は【出荷値そのもの】を拾うようになり、
// 「editor が 1.0 を勝手に生やしたか」を一切見なくなった(＝ガードが無効化された)。
// しかも失敗メッセージが「出荷 yml には倍率キーが無いのに書き込まれた」という嘘の診断を出し、
// 読んだ人を存在しないバグへ誘導する。
// 守りたい不変条件は「開いて保存しただけでは yml の意味が変わらない」なので、
// 出荷値をハードコードせず【保存前後で倍率キーの集合が完全に同一】であることを見る形へ変えた。
test("既定値ドリフト禁止: 出荷 mob-overrides.yml を開いて保存しても倍率キーの集合が1件も動かない", () => {
  const original = YAML.parse(fs.readFileSync(SHIPPED_YML, "utf8"));
  const before = collectMultiplierEntries(original);
  // 前提: 抽出そのものが空振りしていないこと(件数は出荷 yml 次第なので下限だけ見る)。
  assert.ok(before.size > 0,
    "出荷 mob-overrides.yml から倍率キーを1件も抽出できなかった。"
    + " 出荷 yml から倍率が消えたか、collectMultiplierEntries が壊れている");

  const saved = buildForm(deepClone(original)).getData();
  const after = collectMultiplierEntries(saved);

  const { added, removed, changed } = diffMultiplierEntries(before, after);
  assert.deepEqual(
    { added, removed, changed },
    { added: [], removed: [], changed: [] },
    "editor で開いて何も操作せず保存しただけで、倍率キーの集合が変わった"
    + "(＝既定値ドリフト。yml の意味が黙って変わる)。\n"
    + formatMultiplierDiff("増えたキー(既定値を勝手に書き込んだ)", added) + "\n"
    + formatMultiplierDiff("消えたキー(読み落として保存で落とした)", removed) + "\n"
    + formatMultiplierDiff("値が変わったキー", changed));
});

// ============================================================
// (2) 倍率キーを持つ yml は値が保たれる
// ============================================================

function fixtureWithMultipliers() {
  return {
    overrides: {
      default: { mobs: {} },
      em_id_the_mines: {
        "display-name": "鉱山",
        stats: {
          attack: { "magic-ratio": 0.1 },
          "max-health-multiplier": 2.5,
          "attack-power-multiplier": 1.8
        },
        mobs: {
          the_mines_boss: {
            "display-name": "鉱山の主",
            stats: {
              "max-health": 1200,
              "max-health-multiplier": 3.25,
              "attack-power-multiplier": 0.5
            }
          }
        }
      }
    }
  };
}

test("倍率キーを持つ yml を読んで保存すると、スコープ/モブ両方の値が保たれる", () => {
  const original = fixtureWithMultipliers();
  const working = deepClone(original);
  const saved = buildForm(working).getData();
  assert.deepEqual(saved, original, "倍率キーが変形/消失した");
});

test("倍率キーの現在値がフォームの入力欄に出る(読めていないと空欄で上書きされる)", () => {
  const form = buildForm(fixtureWithMultipliers());
  const hpInputs = controlsForKey(form.element, "max-health-multiplier");
  const atkInputs = controlsForKey(form.element, "attack-power-multiplier");
  // default スコープ / em_id_the_mines スコープ / the_mines_boss の3箇所ぶん出る。
  assert.equal(hpInputs.length, 3, `max-health-multiplier の入力欄が想定数と違う: ${hpInputs.length}`);
  assert.equal(atkInputs.length, 3, `attack-power-multiplier の入力欄が想定数と違う: ${atkInputs.length}`);
  const hpValues = hpInputs.map((el) => el.value);
  assert.ok(hpValues.includes(2.5), `スコープの 2.5 が入力欄に出ていない: ${JSON.stringify(hpValues)}`);
  assert.ok(hpValues.includes(3.25), `モブの 3.25 が入力欄に出ていない: ${JSON.stringify(hpValues)}`);
  assert.ok(hpValues.includes(""), "未設定(default スコープ)は空欄で出るべき");
  const atkValues = atkInputs.map((el) => el.value);
  assert.ok(atkValues.includes(1.8) && atkValues.includes(0.5),
    `attack-power-multiplier の値が入力欄に出ていない: ${JSON.stringify(atkValues)}`);
});

// ============================================================
// (3) 既定値ドリフトの回帰テスト: 倍率キーが無いときは書き込まない
// ============================================================

function fixtureWithoutMultipliers() {
  return {
    overrides: {
      default: { mobs: {} },
      em_fireworks: {
        "display-name": "花火工房",
        stats: { attack: { "magic-ratio": 0.1 } },
        mobs: {
          fireworks_level_50_boss_phase_1: {
            "display-name": "スパーキー",
            stats: { physical: { "defense-rate": 0.374 } },
            "vanilla-exp": { base: 56.0, "per-level": 1.68 },
            drops: []
          }
        }
      }
    }
  };
}

test("既定値ドリフト禁止: 倍率キーを持たない yml を保存しても 1.0 が書き込まれない", () => {
  const original = fixtureWithoutMultipliers();
  const working = deepClone(original);
  const saved = buildForm(working).getData();

  const scopeStats = saved.overrides.em_fireworks.stats;
  const mobStats = saved.overrides.em_fireworks.mobs.fireworks_level_50_boss_phase_1.stats;
  for (const [where, host] of [["スコープ", scopeStats], ["モブ", mobStats]]) {
    for (const key of ["max-health-multiplier", "attack-power-multiplier"]) {
      assert.equal(Object.prototype.hasOwnProperty.call(host, key), false,
        `${where}の ${key} が勝手に生えた(1.0 は掛け算として no-op だが、全モブに意味の無い行が`
        + ` 生えて yml の差分が読めなくなる)`);
    }
  }
  assert.deepEqual(saved, original, "倍率キー以外にも変形が起きている");
});

test("既定値ドリフト禁止: stats: を持たないモブに空の stats: すら生やさない", () => {
  const original = {
    overrides: { default: { mobs: { ravager: { abilities: ["bull_rush"] } } } }
  };
  const working = deepClone(original);
  const saved = buildForm(working).getData();
  assert.equal("stats" in saved.overrides.default.mobs.ravager, false,
    "倍率欄を描いただけで stats: {} が生えた(空マップが yml に残る)");
  assert.deepEqual(saved, original);
});

test("倍率を入力すると stats へ書かれ、空欄に戻すとキーごと消える(空になった stats も消える)", () => {
  const data = { overrides: { default: { mobs: { ravager: {} } } } };
  const form = buildForm(data);
  const hpInputs = controlsForKey(form.element, "max-health-multiplier");
  // default スコープぶん + ravager ぶんの2つ。後ろがモブ(スコープ行は mobsBox の前に積まれる)。
  const mobInput = hpInputs[hpInputs.length - 1];

  mobInput.trigger(2.0);
  assert.equal(form.getData().overrides.default.mobs.ravager.stats["max-health-multiplier"], 2.0);

  mobInput.trigger("");
  const after = form.getData().overrides.default.mobs.ravager;
  assert.equal("stats" in after, false,
    "空欄へ戻したのに stats: {} が残った(次の保存で yml に空マップが書かれる)");
});

test("スコープ側の倍率も同じ規則で書き込み/削除される", () => {
  const data = { overrides: { default: { mobs: {} }, em_x: { mobs: {} } } };
  const form = buildForm(data);
  const atkInputs = controlsForKey(form.element, "attack-power-multiplier");
  assert.equal(atkInputs.length, 2, "default と em_x の2スコープぶん出るはず");
  atkInputs[1].trigger(1.35);
  assert.equal(form.getData().overrides.em_x.stats["attack-power-multiplier"], 1.35);
  atkInputs[1].trigger(null);
  assert.equal("stats" in form.getData().overrides.em_x, false);
});

// ============================================================
// (4) 倍率は割合ではない: %入力(×100表示)へ流さない
// ============================================================

test("倍率キーは RATE_FIELDS に入っていない(入ると画面が 100 倍で表示する)", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "public", "js", "mob-forms.js"), "utf8");
  const m = /const RATE_FIELDS = new Set\(\[([\s\S]*?)\]\)/.exec(src);
  assert.ok(m, "mob-forms.js の RATE_FIELDS が見つからない(定義の形を変えたらこのテストも直すこと)");
  const keys = new Set([...m[1].matchAll(/"([a-z0-9-]+)"/g)].map((x) => x[1]));
  for (const key of ["max-health-multiplier", "attack-power-multiplier"]) {
    assert.equal(keys.has(key), false,
      `${key} は割合(0..1)ではなく倍率(中立値 1.0)。RATE_FIELDS に入れると 1.0 が「100%」と表示される`);
  }
});

test("倍率の入力欄は素の数値入力で、%入力(rateValueControl)を通らない", () => {
  rateControlCalls.length = 0;
  const form = buildForm(fixtureWithMultipliers());
  const hpInputs = controlsForKey(form.element, "max-health-multiplier");
  for (const el of hpInputs) {
    assert.equal(el.attrs.class, "number-input",
      "倍率欄が numberInput ではない(%入力に流れると 2.5 が 250% と表示される)");
  }
  // 倍率の値(2.5 / 3.25 / 1.8 / 0.5)が %入力へ渡っていないこと。
  for (const v of [2.5, 3.25, 1.8, 0.5]) {
    assert.equal(rateControlCalls.includes(v), false, `倍率 ${v} が %入力へ渡っている`);
  }
});

// ============================================================
// schema (lib/schema.js) 側
// ============================================================

function statsEntry(stats) {
  return { overrides: { default: { mobs: { goblin_chief: { stats } } } } };
}
function scopeStatsEntry(stats) {
  return { overrides: { em_id_the_mines: { stats, mobs: {} } } };
}

test("tf-mob-overrides: 倍率キーはモブ/スコープどちらの stats でも妥当", () => {
  assert.deepEqual(validate("tf-mob-overrides", statsEntry({
    "max-health-multiplier": 2.5, "attack-power-multiplier": 1.8
  })), []);
  assert.deepEqual(validate("tf-mob-overrides", scopeStatsEntry({
    "max-health-multiplier": 0.5, "attack-power-multiplier": 10
  })), []);
});

test("tf-mob-overrides: 倍率キー省略は妥当(未設定が既定であって 1.0 ではない)", () => {
  assert.deepEqual(validate("tf-mob-overrides", statsEntry({ "max-health": 1200 })), []);
});

test("tf-mob-overrides: 倍率キーが非数値はエラー", () => {
  assert.ok(validate("tf-mob-overrides", statsEntry({ "max-health-multiplier": "2x" }))
    .some((e) => e.includes("stats.max-health-multiplier")));
  assert.ok(validate("tf-mob-overrides", statsEntry({ "attack-power-multiplier": true }))
    .some((e) => e.includes("stats.attack-power-multiplier")));
});

test("tf-mob-overrides: クォートされた数値文字列も倍率キーではエラー(Java 側と受理範囲が一致)", () => {
  // 【非対称の禁止】isNumber は typeof === "number" なので "2.5" を弾く。Java 側
  // (MobOverridesConfig#nullablePositiveMultiplier) も 2026-08-14 に数値のみへ寄せてある。
  // Java だけが広いと「手書きで "2.5" と書いた yml を editor で開くとファイルごと保存できない」、
  // editor だけが広いと「editor で保存できたのにゲーム内では無視される」になる。
  assert.ok(validate("tf-mob-overrides", statsEntry({ "max-health-multiplier": "2.5" }))
    .some((e) => e.includes("stats.max-health-multiplier")));
  assert.ok(validate("tf-mob-overrides", scopeStatsEntry({ "attack-power-multiplier": "1.5" }))
    .some((e) => e.includes("overrides.em_id_the_mines.stats.attack-power-multiplier")));
});

test("tf-mob-overrides: 倍率キーが0以下はエラー(HP/攻撃力が消える or 符号が反転する)", () => {
  assert.ok(validate("tf-mob-overrides", statsEntry({ "max-health-multiplier": 0 }))
    .some((e) => e.includes("stats.max-health-multiplier")));
  assert.ok(validate("tf-mob-overrides", statsEntry({ "attack-power-multiplier": -1 }))
    .some((e) => e.includes("stats.attack-power-multiplier")));
  assert.ok(validate("tf-mob-overrides", scopeStatsEntry({ "max-health-multiplier": -0.1 }))
    .some((e) => e.includes("overrides.em_id_the_mines.stats.max-health-multiplier")));
});

function scopeWithAbilityScale(scale) {
  return { overrides: { em_id_enchantment_challenge_2: { "ability-damage-scale": scale, mobs: {} } } };
}

test("tf-mob-overrides: ability-damage-scale 0.70 は妥当、省略も妥当", () => {
  assert.deepEqual(validate("tf-mob-overrides", scopeWithAbilityScale(0.70)), []);
  assert.deepEqual(validate("tf-mob-overrides", { overrides: { em_x: { mobs: {} } } }), []);
});

test("tf-mob-overrides: ability-damage-scale が非数値・0以下・2超・クォート文字列はエラー", () => {
  assert.ok(validate("tf-mob-overrides", scopeWithAbilityScale("0.70"))
    .some((e) => e.includes("ability-damage-scale")));
  assert.ok(validate("tf-mob-overrides", scopeWithAbilityScale(0))
    .some((e) => e.includes("ability-damage-scale")));
  assert.ok(validate("tf-mob-overrides", scopeWithAbilityScale(2.1))
    .some((e) => e.includes("ability-damage-scale")));
});

test("ability-damage-scale は RATE_FIELDS に入っていない(入ると 0.70 が 70% と表示される)", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "public", "js", "mob-forms.js"), "utf8");
  const m = /const RATE_FIELDS = new Set\(\[([\s\S]*?)\]\)/.exec(src);
  assert.ok(m, "mob-forms.js の RATE_FIELDS が見つからない");
  const keys = new Set([...m[1].matchAll(/"([a-z0-9-]+)"/g)].map((x) => x[1]));
  assert.equal(keys.has("ability-damage-scale"), false);
});

test("ability-damage-scale を持つ yml を開いて保存しても値が保たれ、無い yml に 1.0 は生えない", () => {
  const withScale = {
    overrides: {
      default: { mobs: {} },
      em_id_enchantment_challenge_2: {
        "display-name": "エンチャント試練 2",
        "ability-damage-scale": 0.7,
        mobs: {}
      }
    }
  };
  assert.deepEqual(buildForm(deepClone(withScale)).getData(), withScale);

  const withoutScale = fixtureWithoutMultipliers();
  const saved = buildForm(deepClone(withoutScale)).getData();
  assert.equal(Object.prototype.hasOwnProperty.call(saved.overrides.em_fireworks, "ability-damage-scale"), false);
  assert.deepEqual(saved, withoutScale);
});

test("ability-damage-scale を空欄に戻すとキーごと消える", () => {
  const data = {
    overrides: {
      em_x: { "display-name": "x", "ability-damage-scale": 0.7, mobs: {} }
    }
  };
  const form = buildForm(data);
  const inputs = controlsForKey(form.element, "ability-damage-scale");
  assert.equal(inputs.length, 1, "非 default スコープに技倍率欄が1つ出る");
  inputs[0].trigger(null);
  assert.equal("ability-damage-scale" in form.getData().overrides.em_x, false);
});
