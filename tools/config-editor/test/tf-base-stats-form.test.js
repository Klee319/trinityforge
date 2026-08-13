"use strict";

// public/js/tf-base-stats.js (「プレイヤー基礎ステ定義」画面) の3修正の回帰テスト。
//
// 修正1: window.labelForStat という未定義関数を呼んでいたため日本語ラベルが出ず生キーが表示される
//        バグ。正しいAPIは window.LABELS.statLabel (public/js/labels.js)。
// 修正2: base-stats.yml に書いても no-op なキー(glyph-slot-bonus)を画面から除外する。
//        2026-07-31 に heavy-/light-armor-move-speed-per-piece は語彙ごと廃止された。除外は表示のみで、
//        既存データのロスレス往復は壊さないこと。
//        (2026-07-27: 唯一の例外だった charged-shot-unlocked は挙動ゼロの同語反復フラグと判明し、
//         ステ語彙ごと撤去された。この画面にフラグ系のステはもう存在しない。armor-set-buffs全面移行で
//         旧4キー(light/heavy-armor-set-bonus-multiplier, light-armor-set-dodge-chance,
//         heavy-armor-set-knockback-resistance)は armor-set-bonus 1本へ統一された。
//         2026-08-13 訂正: armor-set-bonus はさらに NativeAttributeBridge#armorAttributesFor() が
//         PlayerStatAggregator#nonPerkStatTotal 経由で base-stats.yml 由来分も読むよう配線が変わり、
//         no-op ではなくなったため NO_OP_BASE_STATS_KEYS から外れ、この画面に表示されるようになった。)
// 修正3: バニラ既定値バッジの文言を「絶対値・既定X」→「バニラ:X」へ変更。JS側の
//        VANILLA_ATTRIBUTE_DEFAULTS が Java 側の正典 VanillaAttributeDefaults.java とキー集合・値
//        ともに一致していること(ドリフト検知)。
// 課題1(2026-07-25、ユーザー要望による仕様差し替え): 修正3の「バニラ:X」バッジ(.field-unit の
//        枠付きバッジ、数値入力欄の左)は陳腐化。新仕様は (a) 文言「※バニラの値Xは上書きされません」、
//        (b) 枠なしプレーンテキストの専用クラス .base-stat-note (.field-unit は他画面共有のため
//        流用しない)、(c) 数値入力欄の右に配置、(d) attack-speed-bonus には出さない(既定0の
//        「上書きされません」は無意味なため)。以下のテスト群はこの新仕様を固定する。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

// ============================================================
// 純関数パート: window.h 等の重いDOMスタブ無しで直接 require できる。
// ============================================================

function makeEl(tag, props) {
  const el = {
    tag,
    props: props || {},
    children: [],
    appendChild(c) { this.children.push(c); return c; },
    set textContent(v) { this.props.text = v; },
    get textContent() { return this.props.text || ""; }
  };
  return el;
}

global.window = global.window || {};
global.document = global.document || {};
// tf-base-stats.js は module 読み込み時に `const h = window.h;` を1度だけキャプチャする
// (public/js/tf-rewards-forms.js と同じ dual-mode IIFE パターン)。DOM結合テストのため
// require より前に window.h を固定で用意しておく(window.numberInput/checkboxInput は呼び出し
// のたびに window 経由で読むのでテストごとの差し替えでも問題ない)。
global.window.h = (tag, props, children) => {
  const el = makeEl(tag, props);
  if (Array.isArray(children)) children.forEach((c) => c != null && el.appendChild(c));
  else if (children != null) el.appendChild(children);
  return el;
};
delete require.cache[require.resolve("../public/js/tf-base-stats.js")];
const {
  NO_OP_BASE_STATS_KEYS,
  VANILLA_ATTRIBUTE_DEFAULTS,
  vanillaAttributeDefault,
  shouldShowVanillaNote,
  vanillaNoteText,
  statLabel,
  allStatKeys
} = require("../public/js/tf-base-stats.js");

test("純関数exportが取得できる(モジュール読み込みが壊れていないこと)", () => {
  assert.ok(Array.isArray(NO_OP_BASE_STATS_KEYS));
  assert.equal(typeof vanillaAttributeDefault, "function");
  assert.equal(typeof statLabel, "function");
  assert.equal(typeof allStatKeys, "function");
});

// ---- 修正1: 日本語ラベル解決 ----

test("statLabel: window.LABELS.statLabel を正しく呼び、日本語ラベルを返す(生キーのまま出ない)", () => {
  const prevMeta = global.window.STAT_META;
  const prevLabels = global.window.LABELS;
  try {
    global.window.STAT_META = undefined;
    global.window.LABELS = { statLabel: (k) => (k === "crit-chance" ? "会心率" : k) };
    assert.equal(statLabel("crit-chance"), "会心率");
  } finally {
    global.window.STAT_META = prevMeta;
    global.window.LABELS = prevLabels;
  }
});

test("statLabel: STAT_META にnameがあればそちらを優先する", () => {
  const prevMeta = global.window.STAT_META;
  const prevLabels = global.window.LABELS;
  try {
    global.window.STAT_META = { "attack-power": { name: "攻撃力" } };
    global.window.LABELS = { statLabel: () => "呼ばれてはいけない" };
    assert.equal(statLabel("attack-power"), "攻撃力");
  } finally {
    global.window.STAT_META = prevMeta;
    global.window.LABELS = prevLabels;
  }
});

test("statLabel: ラベル未定義キーはキー名そのままにフォールバックする(挙動温存)", () => {
  const prevMeta = global.window.STAT_META;
  const prevLabels = global.window.LABELS;
  try {
    global.window.STAT_META = undefined;
    global.window.LABELS = { statLabel: (k) => k }; // labels.js 自身の未登録フォールバック
    assert.equal(statLabel("not-a-real-stat"), "not-a-real-stat");
  } finally {
    global.window.STAT_META = prevMeta;
    global.window.LABELS = prevLabels;
  }
});

test("statLabel: window.LABELS 自体が未定義でも例外を投げずキーを返す", () => {
  const prevMeta = global.window.STAT_META;
  const prevLabels = global.window.LABELS;
  try {
    global.window.STAT_META = undefined;
    global.window.LABELS = undefined;
    assert.equal(statLabel("crit-chance"), "crit-chance");
  } finally {
    global.window.STAT_META = prevMeta;
    global.window.LABELS = prevLabels;
  }
});

// 実物の labels.js を読み込んで、実際のラベル解決が機能することも確認する(統合寄りの一本)。
test("statLabel: 実物のlabels.jsをロードしても生キーではなく日本語ラベルが返る", () => {
  const prevMeta = global.window.STAT_META;
  const prevLabels = global.window.LABELS;
  try {
    global.window.STAT_META = undefined;
    delete require.cache[require.resolve("../public/js/labels.js")];
    require("../public/js/labels.js");
    const label = statLabel("attack-power");
    assert.notEqual(label, "attack-power");
    assert.equal(label, global.window.LABELS.STAT_LABELS["attack-power"]);
  } finally {
    global.window.STAT_META = prevMeta;
    global.window.LABELS = prevLabels;
  }
});

// ---- 修正2: no-op 1キー + tool-enchant-efficiency の除外 ----
// (2026-08-13: armor-set-bonus は NativeAttributeBridge#armorAttributesFor() が
//  PlayerStatAggregator#nonPerkStatTotal 経由で base-stats.yml 由来分も読むようになったため
//  no-op ではなくなり、除外対象から外れた。この画面に表示されるべき通常ステとして扱う。)

const EXPECTED_NO_OP_KEYS = [
  "glyph-slot-bonus"
];

// 2026-07-27: tool-enchant-efficiency を追加。ただし理由は上記1キー(no-op = 読み出し経路が無い)
// とは異なり、「プレイヤー総合ステではなくアイテム専用ステ(item-stats.yml側)なので、そもそも
// この画面(base-stats)の対象外」。NO_OP_BASE_STATS_KEYS のコメント参照。
const EXPECTED_NOT_PLAYER_STAT_KEYS = ["tool-enchant-efficiency"];
// 2026-07-29(重複ステ間引き): さらに理由(c)として「同じ画面の別キーと意味が重複」を追加。
// mana-bonus / mana-regen は全員一律値としては mana-max-base / mana-regen-base に足されるだけで、
// プレイヤー基礎ステ画面に2組並べる意味がない(アイテム/パークステとしては存続)。
const EXPECTED_REDUNDANT_KEYS = ["mana-bonus", "mana-regen"];
const ALL_EXCLUDED_KEYS = [
  ...EXPECTED_NO_OP_KEYS, ...EXPECTED_NOT_PLAYER_STAT_KEYS, ...EXPECTED_REDUNDANT_KEYS
];

test("NO_OP_BASE_STATS_KEYS: no-op 1キー + アイテム専用1キー + 重複2キーちょうどを含む", () => {
  assert.deepEqual([...NO_OP_BASE_STATS_KEYS].sort(), [...ALL_EXCLUDED_KEYS].sort());
});

test("allStatKeys: 除外キー(no-op/アイテム専用/重複)が落ち、通常ステ(armor-set-bonus含む)は残る", () => {
  const prevList = global.window.STAT_LIST;
  const prevFallback = global.window.FALLBACK_STATS;
  const prevHidden = global.window.HIDDEN_STATS;
  try {
    global.window.STAT_LIST = [];
    global.window.FALLBACK_STATS = [
      "attack-power", "glyph-slot-bonus",
      "armor-set-bonus", "tool-enchant-efficiency", "mana-bonus", "mana-regen"
    ];
    global.window.HIDDEN_STATS = [];
    const keys = allStatKeys();
    for (const k of ALL_EXCLUDED_KEYS) {
      assert.ok(!keys.includes(k), `除外対象キー "${k}" が画面に残っている`);
    }
    assert.ok(keys.includes("attack-power"), "通常ステまで除外されてしまっている");
    assert.ok(keys.includes("armor-set-bonus"),
      "armor-set-bonus が画面から消えている(2026-08-13 の配線変更で no-op ではなくなったはず)");
  } finally {
    global.window.STAT_LIST = prevList;
    global.window.FALLBACK_STATS = prevFallback;
    global.window.HIDDEN_STATS = prevHidden;
  }
});

// materials.js の FALLBACK_STATS (他画面=item-stats/skilltreeバフでも共有) からは
// no-op キー + tool-enchant-efficiency を削除していないこと(=base-stats画面限定の除外であること)
// を確認する。tool-enchant-efficiency は item-stats では今も有効なステなので、
// FALLBACK_STATS から消してはいけない(brief 前提5)。
test("materials.js の FALLBACK_STATS には除外対象キーが引き続き残っている(他画面では有効なため)", () => {
  delete require.cache[require.resolve("../public/js/materials.js")];
  require("../public/js/materials.js");
  const fallback = global.window.FALLBACK_STATS;
  for (const k of EXPECTED_NO_OP_KEYS) {
    assert.ok(fallback.includes(k), `materials.js FALLBACK_STATS から "${k}" が消えている(他画面に影響)`);
  }
});

// ---- 修正3: バニラ既定値テーブルの Java 側ドリフト検知 ----

const JAVA_DEFAULTS_PATH = path.join(
  __dirname, "..", "..", "..", "TrinityForge", "src", "main", "java",
  "com", "trinityforge", "stats", "VanillaAttributeDefaults.java"
);

function parseJavaDefaults(javaSource) {
  // StatKeys.canonical("key"), value, の行を抽出する。
  const re = /StatKeys\.canonical\("([^"]+)"\),\s*([0-9.]+)/g;
  const out = {};
  let m;
  while ((m = re.exec(javaSource)) !== null) {
    out[m[1]] = Number(m[2]);
  }
  return out;
}

test("VanillaAttributeDefaults.java が読める(パスが壊れていないこと)", () => {
  assert.ok(fs.existsSync(JAVA_DEFAULTS_PATH), `not found: ${JAVA_DEFAULTS_PATH}`);
});

test("JS VANILLA_ATTRIBUTE_DEFAULTS が Java VanillaAttributeDefaults とキー集合・値ともに一致する", () => {
  const javaSource = fs.readFileSync(JAVA_DEFAULTS_PATH, "utf8");
  const javaDefaults = parseJavaDefaults(javaSource);
  assert.ok(Object.keys(javaDefaults).length > 0, "regex抽出が0件; Javaソースの形が変わった可能性");

  const jsKeys = new Set(Object.keys(VANILLA_ATTRIBUTE_DEFAULTS));
  const javaKeys = new Set(Object.keys(javaDefaults));

  const missingFromJs = [...javaKeys].filter((k) => !jsKeys.has(k));
  const missingFromJava = [...jsKeys].filter((k) => !javaKeys.has(k));
  assert.deepEqual(missingFromJs, [], `Javaにあるがtf-base-stats.jsに無いキー: ${missingFromJs.join(", ")}`);
  assert.deepEqual(missingFromJava, [], `tf-base-stats.jsにあるがJavaに無いキー: ${missingFromJava.join(", ")}`);

  for (const k of jsKeys) {
    assert.equal(VANILLA_ATTRIBUTE_DEFAULTS[k], javaDefaults[k], `値の不一致 key=${k}`);
  }
});

// ============================================================
// DOM 結合パート: window.h 等を最小スタブして buildBaseStatsForm を実行する。
// drop-table-logic.test.js / ars-config-form-tdz.test.js と同じ手法。
// ============================================================

function withDomStubs(fn) {
  const saved = {};
  const keys = ["numberInput", "checkboxInput", "STAT_LIST", "FALLBACK_STATS", "HIDDEN_STATS",
    "STAT_META", "STAT_FORMATS", "LABELS", "isPercentStat"];
  for (const k of keys) saved[k] = global.window[k];

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
  global.window.STAT_LIST = ["attack-power", "crit-chance", "max-health", "attack-speed-bonus",
    "arrow-piercing", "glyph-slot-bonus", "armor-set-bonus"];
  global.window.FALLBACK_STATS = [];
  global.window.HIDDEN_STATS = [];
  global.window.STAT_META = {
    "attack-power": { name: "攻撃力", category: "attack", order: 1 },
    "crit-chance": { name: "会心率", category: "attack", order: 2 },
    "max-health": { name: "最大体力", category: "defense", order: 0 },
    "attack-speed-bonus": { name: "攻撃速度加算", category: "attack", order: 4 },
    "arrow-piercing": { name: "矢貫通", category: "attack", order: 3 },
    "glyph-slot-bonus": { name: "グリフ枠", category: "ars", order: 1 },
    "armor-set-bonus": { name: "セット効果増幅", category: "defense", order: 5 }
  };
  global.window.STAT_FORMATS = { "arrow-piercing": "INTEGER" };
  global.window.LABELS = { statLabel: (k) => k };
  global.window.isPercentStat = () => false;

  try {
    return fn();
  } finally {
    for (const k of keys) global.window[k] = saved[k];
  }
}

test("buildBaseStatsForm: no-opキーの行が描画されず、通常ステの行は描画される", () => {
  withDomStubs(() => {
    const result = global.window.buildBaseStatsForm({});
    const labelTexts = [];
    (function walk(el) {
      if (!el || !el.children) return;
      if (el.props && el.props.class === "stat-row-label") labelTexts.push(el.props.text);
      el.children.forEach(walk);
    })(result.element);
    for (const noOp of EXPECTED_NO_OP_KEYS) {
      assert.ok(!labelTexts.includes(noOp), `no-opキー "${noOp}" のラベルが描画された`);
    }
    assert.ok(labelTexts.length > 0, "何も描画されていない(前提が壊れている)");
  });
});

// ---- 課題1: 「※バニラの値Xは上書きされません」注記(新仕様) ----

test("純関数: shouldShowVanillaNote / vanillaNoteText がバニラ既定値キーだけに正しい文言を返す", () => {
  assert.equal(shouldShowVanillaNote("max-health"), true);
  assert.equal(vanillaNoteText("max-health"), "※バニラの値20は上書きされません");
  assert.equal(shouldShowVanillaNote("attack-reach"), true);
  assert.equal(vanillaNoteText("attack-reach"), "※バニラの値3は上書きされません");
  // 2026-07-26 (stat-scope 境界引き直し): attack-speed(絶対値)は総合ステータス語彙から外れて
  // アイテム固有ステになり、base-stats では設定できなくなった。バニラ既定値の対象からも外れる。
  assert.equal(shouldShowVanillaNote("attack-speed"), false);
  assert.equal(vanillaNoteText("attack-speed"), null,
    "attack-speed は base-stats では設定できなくなったので注記も出さない");
  // attack-speed-bonus はバニラ既定=0.0の「割合ボーナス加算」キーで、絶対値上書きの意味を持たない
  // ため、バニラ既定値を持つキーであっても注記の対象から除外される。
  assert.equal(shouldShowVanillaNote("attack-speed-bonus"), false);
  assert.equal(vanillaNoteText("attack-speed-bonus"), null,
    "attack-speed-bonus はバニラ既定=0を持つが、絶対値上書きを意味しないキーなので注記文言も出さない");
  // バニラ既定値を持たない通常の加算キーには一切表示しない。
  assert.equal(shouldShowVanillaNote("crit-chance"), false);
  assert.equal(vanillaNoteText("crit-chance"), null);
});

test("buildBaseStatsForm: 注記は「.base-stat-note」クラスのプレーンテキストで描画される(「.field-unit」ではない)", () => {
  withDomStubs(() => {
    const result = global.window.buildBaseStatsForm({});
    const noteTexts = [];
    const fieldUnitTexts = [];
    (function walk(el) {
      if (!el || !el.children) return;
      if (el.props && el.props.class === "base-stat-note") noteTexts.push(el.props.text);
      if (el.props && el.props.class === "field-unit") fieldUnitTexts.push(el.props.text);
      el.children.forEach(walk);
    })(result.element);
    assert.ok(noteTexts.length > 0,
      "base-stat-note が1つも描画されていない(max-healthはバニラ既定値キーなので描画されるはず)");
    assert.equal(fieldUnitTexts.length, 0,
      "この画面専用の新クラスに切り替えたはずが、共有クラス .field-unit がまだ使われている"
      + "(他画面に影響するため禁止)");
  });
});

test("buildBaseStatsForm: 注記の文言が新仕様「※バニラの値Xは上書きされません」に一致する(旧文言が残っていない)", () => {
  withDomStubs(() => {
    const result = global.window.buildBaseStatsForm({});
    const noteTexts = [];
    (function walk(el) {
      if (!el || !el.children) return;
      if (el.props && el.props.class === "base-stat-note") noteTexts.push(el.props.text);
      el.children.forEach(walk);
    })(result.element);
    assert.ok(noteTexts.includes("※バニラの値20は上書きされません"),
      `max-health(バニラ既定20)の注記が見つからない: ${JSON.stringify(noteTexts)}`);
    for (const t of noteTexts) {
      assert.match(t, /^※バニラの値.+は上書きされません$/, `新仕様の文言形式でない: "${t}"`);
      assert.ok(!t.startsWith("バニラ:"), `旧文言(バッジ形式)が残っている: "${t}"`);
      assert.ok(!t.includes("絶対値・既定"), `さらに古い文言が残っている: "${t}"`);
    }
  });
});

test("buildBaseStatsForm: 注記は数値入力欄より後ろ(右側)に配置される(左側のバッジではない)", () => {
  withDomStubs(() => {
    const result = global.window.buildBaseStatsForm({});
    let targetRow = null;
    (function walk(el) {
      if (!el || !el.children || targetRow) return;
      if (el.props && el.props.class === "stat-row") {
        const hasNote = el.children.some((c) => c.props && c.props.class === "base-stat-note");
        if (hasNote) targetRow = el;
      }
      if (!targetRow) el.children.forEach(walk);
    })(result.element);
    assert.ok(targetRow, "base-stat-note を含む stat-row が見つからない");
    const classesInOrder = targetRow.children.map((c) => c.props && c.props.class);
    const valueIdx = classesInOrder.indexOf("num");
    const noteIdx = classesInOrder.indexOf("base-stat-note");
    assert.ok(valueIdx >= 0, `数値入力欄(num)が行内に見つからない: ${JSON.stringify(classesInOrder)}`);
    assert.ok(noteIdx >= 0, `base-stat-note が行内に見つからない: ${JSON.stringify(classesInOrder)}`);
    assert.ok(noteIdx > valueIdx,
      `注記が数値入力欄より左に配置されている(右配置になっていない): ${JSON.stringify(classesInOrder)}`);
  });
});

test("buildBaseStatsForm: attack-speed-bonus の行には注記が出ず、他の属性ステ(max-health)には出る", () => {
  withDomStubs(() => {
    const result = global.window.buildBaseStatsForm({});
    let attackSpeedBonusRow = null;
    let maxHealthRow = null;
    (function walk(el) {
      if (!el || !el.children) return;
      if (el.props && el.props.class === "stat-row") {
        const label = el.children.find((c) => c.props && c.props.class === "stat-row-label");
        if (label && label.props.title && label.props.title.startsWith("attack-speed-bonus")) attackSpeedBonusRow = el;
        if (label && label.props.title && label.props.title.startsWith("max-health")) maxHealthRow = el;
      }
      el.children.forEach(walk);
    })(result.element);
    assert.ok(attackSpeedBonusRow, "attack-speed-bonus の行が見つからない");
    assert.ok(maxHealthRow, "max-health の行が見つからない");
    const hasNote = (row) => row.children.some((c) => c.props && c.props.class === "base-stat-note");
    assert.equal(hasNote(attackSpeedBonusRow), false,
      "attack-speed-bonus の行に注記が出てはいけない(既定0で「上書きされません」は無意味)");
    assert.equal(hasNote(maxHealthRow), true, "max-health の行には注記が出るべき");
  });
});

// ---- ロスレス往復: 除外キーに既存値があっても保存で消えないこと ----

test("ロスレス: no-op除外キーに既存値がある場合、buildBaseStatsForm(表示除外)しても値は消えない", () => {
  withDomStubs(() => {
    const existing = {
      "glyph-slot-bonus": 3,
      "armor-set-bonus": 0.02,
      "attack-power": 10
    };
    const data = { "base-stats": { ...existing } };
    const result = global.window.buildBaseStatsForm(data);
    const saved = result.getData();
    assert.deepEqual(saved["base-stats"], existing,
      "buildBaseStatsForm の描画だけで no-op キーの既存値が変化してはいけない(ロスレス違反)");
  });
});

// 2026-07-27: 元は「charged-shot-unlocked の値が保持されること」を見ていたテスト。当のキーは
// 撤去したが、稼働中サーバの base-stats.yml にはまだ `charged-shot-unlocked: 1` が残っている。
// **語彙から消えたキーを画面が黙って捨てないこと**の確認としてそのまま価値があるので、
// 意図を「撤去済みキーのロスレス往復」に読み替えて残す。
test("ロスレス: 語彙から撤去されたキーが残っていても画面を開くだけで消えない", () => {
  withDomStubs(() => {
    const data = { "base-stats": { "charged-shot-unlocked": 1 } };
    const result = global.window.buildBaseStatsForm(data);
    assert.equal(result.getData()["base-stats"]["charged-shot-unlocked"], 1);
  });
});
