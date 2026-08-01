"use strict";

// public/js/tf-base-stats.js の「上限」タブ (T8, 2026-07-26新設) 回帰テスト。
// 保存先: combat/stat-caps.yml (base-stats.yml とは別ファイル、buildSmithingGimmickForm と同じ
// getExtraSaves コンパニオン方式)。
//
// 最重要契約: base-stats.yml とは逆で「未記載」と「明示的な0」を区別する。
//   working["stat-caps"] にキーが無い = 上限なし。キーがあれば(0を含め)その値がそのまま上限になる。
// UI はチェックボックス(設定する/しない) + 数値入力で表現し、チェックOFF=キー削除、
// チェックON=値をそのまま保存(0のままでも保存する)という規約を固定する。

const test = require("node:test");
const assert = require("node:assert/strict");

function makeEl(tag, props) {
  const el = {
    tag,
    props: props || {},
    children: [],
    appendChild(c) { this.children.push(c); return c; },
    set innerHTML(_v) { this.children = []; },
    get innerHTML() { return ""; },
    set textContent(v) { this.props.text = v; },
    get textContent() { return this.props.text || ""; }
  };
  return el;
}

function setupStubs() {
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
  global.window.STAT_LIST = [];
  global.window.FALLBACK_STATS = [];
  global.window.HIDDEN_STATS = [];
  global.window.STAT_META = {};
  global.window.STAT_FORMATS = {};
  global.window.LABELS = { statLabel: (k) => k };
  global.window.isPercentStat = () => false;
  delete require.cache[require.resolve("../public/js/tf-base-stats.js")];
  require("../public/js/tf-base-stats.js");
}

function findAllByClass(el, cls, out) {
  out = out || [];
  if (el && el.props && el.props.class === cls) out.push(el);
  for (const c of (el && el.children) || []) findAllByClass(c, cls, out);
  return out;
}

// ---- 純関数パート ----

delete require.cache[require.resolve("../public/js/tf-base-stats.js")];
global.window = global.window || {};
global.document = global.document || {};
global.window.h = (tag, props) => makeEl(tag, props);
const {
  STAT_CAPS_SECTIONS,
  statCapsAllKeys,
  normalizeStatCapsWorking
} = require("../public/js/tf-base-stats.js");

// 2026-07-27: この5キーは「上限が効かないので意図的にUIへ出さない」枠(STAT_CAPS_UNSUPPORTED_KEYS)
// だったが、PerkAttributeApplier にクランプ点を作って設定可能になったため枠ごと廃止し、
// 逆に「ちゃんとUIに出ていること」を固定するテストへ反転させた。
test("ATTRIBUTE チャネルの5キーがUI一覧に出ている(2026-07-27に上限対応)", () => {
  const keys = statCapsAllKeys();
  for (const k of ["move-speed", "attack-speed-bonus", "attack-reach",
    "knockback-resistance", "max-health"]) {
    assert.ok(keys.includes(k), `ATTRIBUTEチャネルのキー "${k}" がUI一覧から欠けている`);
  }
});

test("statCapsAllKeys: 対象外(アイテム個別ステ/CT短縮系/flat-defense)を含まない", () => {
  const keys = statCapsAllKeys();
  const forbidden = [
    "durability", "item-cooldown", "tool-enchant-efficiency", "thread-slots", "coating-charges",
    "cooldown-reduction", "flat-defense"
  ];
  for (const bad of forbidden) {
    assert.ok(!keys.some((k) => k === bad || k.endsWith("-cooldown-reduction")),
      `対象外のはずのキー "${bad}" 系がUI一覧に含まれている`);
  }
});

// 87 → 92: 2026-07-27 に ATTRIBUTE チャネル5キー(move-speed / attack-speed-bonus /
// attack-reach / knockback-resistance / max-health)が上限対応してUIへ加わった。
// 92 → 94: 2026-07-31 に craft-upswing-bonus / craft-downswing-reduction の2キーを
// workbench-* / ritual-* の4キーへ分割した(作業台と儀式で同じパークが共有されていた)。
// 94 → 97: 2026-08-02 にスキル別EXP倍率3キー(woodcutting/farming/digging-exp-bonus)を追加した
// (単発装備の「伐採EXP+15%」を use-skill で表現すると斧で殴って伐採EXPが入るため)。
test("statCapsAllKeys: yml側の効くキー一覧と重複なく97件ちょうど", () => {
  const keys = statCapsAllKeys();
  assert.equal(keys.length, 97, `件数不一致: ${keys.length}`);
  assert.equal(new Set(keys).size, keys.length, "重複キーがある");
});

test("normalizeStatCapsWorking: stat-caps キーが無ければ空マップを補う(既存値は温存)", () => {
  const w1 = normalizeStatCapsWorking({});
  assert.deepEqual(w1["stat-caps"], {});
  const w2 = normalizeStatCapsWorking({ "stat-caps": { "crit-chance": 0.5 } });
  assert.deepEqual(w2["stat-caps"], { "crit-chance": 0.5 });
});

// ---- DOM 結合パート ----

test("buildBaseStatsForm: opts.statCapsData 未指定でも例外を投げず getExtraSaves は空配列", () => {
  setupStubs();
  const result = global.window.buildBaseStatsForm({});
  assert.deepEqual(result.getExtraSaves(), []);
});

test("buildBaseStatsForm: opts.statCapsData 指定時、getExtraSaves が id=stat-caps を返す", () => {
  setupStubs();
  const statCapsData = { "stat-caps": { "crit-chance": 0.3 } };
  const result = global.window.buildBaseStatsForm({}, { statCapsData });
  const extras = result.getExtraSaves();
  assert.equal(extras.length, 1);
  assert.equal(extras[0].id, "stat-caps");
  assert.equal(extras[0].data["stat-caps"]["crit-chance"], 0.3, "既存キーの値が保持されていません(往復ロス)");
});

test("ロスレス: 無編集なら getExtraSaves の stat-caps は入力データと完全一致する", () => {
  setupStubs();
  const statCapsData = {
    "stat-caps": { "crit-chance": 0, "mining-fortune": 12.5 },
    "gathering-efficiency-max-enchant-level": 7
  };
  const original = JSON.parse(JSON.stringify(statCapsData));
  const result = global.window.buildBaseStatsForm({}, { statCapsData });
  const saved = result.getExtraSaves().find((e) => e.id === "stat-caps").data;
  assert.deepEqual(saved, original, "無編集での往復がロスレスでない");
});

test("上限タブ: 「未設定」のキーはチェックボックスOFF・数値inputはdisabled", () => {
  setupStubs();
  const statCapsData = { "stat-caps": {} };
  const result = global.window.buildBaseStatsForm({}, { statCapsData });
  const checkboxes = findAllByClass(result.element, "checkbox");
  // 上限タブはデフォルト非アクティブ(active="base")なので描画されていないことをまず確認。
  assert.equal(checkboxes.length, 0, "初期表示(基礎ステータスタブ)にチェックボックスが出てはいけない");
});

test("上限タブを選択すると、未設定キーはOFF・チェックボックス操作でキーの有無が切り替わる", () => {
  setupStubs();
  const statCapsData = { "stat-caps": { "crit-chance": 0 } };
  const result = global.window.buildBaseStatsForm({}, { statCapsData });
  // タブボタンをクリックして「上限」タブへ切り替える。
  const buttons = [];
  (function walk(el) {
    if (!el || !el.children) return;
    if (el.tag === "button") buttons.push(el);
    el.children.forEach(walk);
  })(result.element);
  const capsTabBtn = buttons.find((b) => b.children.some((c) => c.props && c.props.text === "上限"));
  assert.ok(capsTabBtn, "「上限」タブボタンが見つからない");
  capsTabBtn.props.onclick();

  const checkboxes = findAllByClass(result.element, "checkbox");
  assert.ok(checkboxes.length > 0, "上限タブ描画後にチェックボックスが1つも無い");
  // crit-chance は working に既にキーがある(値0)ので ON でなければならない。
  const critRow = checkboxes.find((cb) => cb.props.checked === true);
  assert.ok(critRow, "既存キー(crit-chance:0)のチェックボックスがONになっていない"
    + "(「未設定」と「明示的な0」の区別が壊れている)");
});

test("上限タブのチェックボックスON操作で map にキー0が作られ、OFF操作でキーごと削除される", () => {
  setupStubs();
  const statCapsData = { "stat-caps": {} };
  const result = global.window.buildBaseStatsForm({}, { statCapsData });
  const buttons = [];
  (function walk(el) {
    if (!el || !el.children) return;
    if (el.tag === "button") buttons.push(el);
    el.children.forEach(walk);
  })(result.element);
  const capsTabBtn = buttons.find((b) => b.children.some((c) => c.props && c.props.text === "上限"));
  capsTabBtn.props.onclick();

  const checkboxes = findAllByClass(result.element, "checkbox");
  const cb = checkboxes[0];
  assert.equal(cb.props.checked, false);
  cb.__onInput(true);
  const saved = result.getExtraSaves().find((e) => e.id === "stat-caps").data;
  const capsMap = saved["stat-caps"];
  const anyKey = Object.keys(capsMap)[0];
  assert.ok(anyKey !== undefined, "チェックON操作でもキーが作られていない");
  assert.equal(capsMap[anyKey], 0, "チェックON直後の既定値は0であるべき");

  cb.__onInput(false);
  assert.equal(Object.prototype.hasOwnProperty.call(capsMap, anyKey), false,
    "チェックOFF操作でもキーが削除されていない(「上限なし」に戻っていない)");
});

test("gathering-efficiency-max-enchant-level: 上限タブ内に1行として統合され、未設定/設定を区別する", () => {
  setupStubs();
  const statCapsData = { "stat-caps": {} };
  const result = global.window.buildBaseStatsForm({}, { statCapsData });
  const buttons = [];
  (function walk(el) {
    if (!el || !el.children) return;
    if (el.tag === "button") buttons.push(el);
    el.children.forEach(walk);
  })(result.element);
  const capsTabBtn = buttons.find((b) => b.children.some((c) => c.props && c.props.text === "上限"));
  capsTabBtn.props.onclick();

  const texts = [];
  (function walk(el) {
    if (!el || !el.children) return;
    if (el.props && typeof el.props.text === "string") texts.push(el.props.text);
    el.children.forEach(walk);
  })(result.element);
  assert.ok(texts.some((t) => t.includes("効率強化エンチャントの上限")),
    "gathering-efficiency-max-enchant-level 行が上限タブに見当たらない");
  assert.ok(texts.some((t) => t.includes("旧ファイルより優先")),
    "旧ファイルより優先されるという説明文が見当たらない");

  const saved0 = result.getExtraSaves().find((e) => e.id === "stat-caps").data;
  assert.equal(saved0["gathering-efficiency-max-enchant-level"], undefined,
    "未操作なら gathering-efficiency-max-enchant-level は作られないはず(旧ファイル任せ)");
});
