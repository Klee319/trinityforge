"use strict";

// T8 (2026-07-26): 「ステータス上限」タブ新設 + 「最終効率の上限 (gathering-efficiency)」独立カテゴリの
// 畳み込みに関する registry.js / app.js / schema.js の回帰テスト。
//
// 1) registry.js に stat-caps エントリが追加されている(rel: combat/stat-caps.yml)。
// 2) app.js: stat-caps / gathering-efficiency の両方が HIDDEN_CONFIG_IDS 経由でサイドバーから隠れる。
// 3) schema.js: tf-stat-caps バリデータが登録されている。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const { REGISTRY, findById } = require("../lib/registry.js");
const { validate } = require("../lib/schema.js");

test("registry: stat-caps エントリが combat/stat-caps.yml を指して存在する", () => {
  const entry = findById("stat-caps");
  assert.ok(entry, "stat-caps が registry に存在しない");
  assert.equal(entry.rel, "combat/stat-caps.yml");
  assert.equal(entry.base, "trinityforge");
});

test("registry: gathering-efficiency は引き続き独立ファイル(stats/gathering-efficiency.yml)として存在する(後方互換のため削除しない)", () => {
  const entry = findById("gathering-efficiency");
  assert.ok(entry, "gathering-efficiency registry entry が削除されている(ファイル自体は残すはず)");
  assert.equal(entry.rel, "stats/gathering-efficiency.yml");
});

test("registry: id の重複が無い(stat-caps 追加後も)", () => {
  const ids = REGISTRY.map((e) => e.id);
  const dupes = ids.filter((id, i) => ids.indexOf(id) !== i);
  assert.deepEqual(dupes, [], `重複id: ${JSON.stringify(dupes)}`);
});

const appJsSrc = fs.readFileSync(path.join(__dirname, "..", "public", "js", "app.js"), "utf8");

test("app.js: BASE_STATS_COMPANION_IDS に stat-caps と gathering-efficiency の両方が含まれる", () => {
  const m = appJsSrc.match(/const BASE_STATS_COMPANION_IDS = \[([^\]]*)\];/);
  assert.ok(m, "BASE_STATS_COMPANION_IDS の定義が見つからない");
  assert.match(m[1], /"stat-caps"/, `BASE_STATS_COMPANION_IDS: ${m[1]}`);
  assert.match(m[1], /"gathering-efficiency"/, `BASE_STATS_COMPANION_IDS: ${m[1]}`);
});

test("app.js: HIDDEN_CONFIG_IDS へ BASE_STATS_COMPANION_IDS がスプレッドされている(サイドバー非表示)", () => {
  assert.match(appJsSrc, /HIDDEN_CONFIG_IDS = \[[\s\S]*?\.\.\.BASE_STATS_COMPANION_IDS/,
    "HIDDEN_CONFIG_IDS へ BASE_STATS_COMPANION_IDS がスプレッドされていない");
});

test("app.js: tf-base-stats ケースが stat-caps コンパニオンを読み込んで buildBaseStatsForm へ渡す", () => {
  assert.match(appJsSrc, /loadConfigCompanion\("stat-caps",\s*"statCapsData",\s*options\)/,
    "tf-base-stats ケースで stat-caps のコンパニオン読み込みが見当たらない");
});

test("schema.js: tf-stat-caps バリデータが登録され、正しい形はエラー無し", () => {
  const ok = { "stat-caps": { "crit-chance": 0.3, "mining-fortune": 0 }, "gathering-efficiency-max-enchant-level": 5 };
  assert.deepEqual(validate("tf-stat-caps", ok), []);
});

test("schema.js: tf-stat-caps は stat-caps 内の非数値でエラーを返す", () => {
  const bad = { "stat-caps": { "crit-chance": "not-a-number" } };
  const errors = validate("tf-stat-caps", bad);
  assert.ok(errors.length > 0, "非数値の上限値でエラーが出るはず");
});

test("schema.js: tf-stat-caps は gathering-efficiency-max-enchant-level の非整数でエラーを返す", () => {
  const bad = { "gathering-efficiency-max-enchant-level": 1.5 };
  const errors = validate("tf-stat-caps", bad);
  assert.ok(errors.length > 0, "非整数でエラーが出るはず");
});

test("schema.js: tf-stat-caps は空/未設定を許容する(全キー未記載=上限なし)", () => {
  assert.deepEqual(validate("tf-stat-caps", {}), []);
  assert.deepEqual(validate("tf-stat-caps", { "stat-caps": {} }), []);
});

// ---- 設定リファレンスとの整合性: UI 一覧が「効くキー」だけを含むことの実データ照合 ----
//
// 2026-07-26: 参照先を combat/stat-caps.yml の本文コメントから docs/config-reference/combat/stat-caps.md
// へ移した。理由は「エディタでこの画面を一度保存すると yml 本文コメントが復元されず丸ごと消える」
// (tools/config-editor/lib/yamlio.js)ため — 真源をコメントに置いたままでは、運用中に何の前触れも無く
// このテストの入力データごと消滅する。他の出荷config と同じく docs/config-reference/ を真源にした。
const REPO_ROOT = path.resolve(__dirname, "..", "..", "..");
const STAT_CAPS_DOC = path.join(REPO_ROOT, "docs", "config-reference", "combat", "stat-caps.md");
const STAT_CAPS_YML = path.join(REPO_ROOT, "TrinityForge", "src", "main", "resources", "combat", "stat-caps.yml");

test("combat/stat-caps.yml が読める(パスが壊れていないこと)", () => {
  assert.ok(fs.existsSync(STAT_CAPS_YML), `not found: ${STAT_CAPS_YML}`);
});

test("combat/stat-caps.yml は docs/config-reference/combat/stat-caps.md を指している", () => {
  const src = fs.readFileSync(STAT_CAPS_YML, "utf8");
  assert.match(src, /docs\/config-reference\/combat\/stat-caps\.md/,
    "キー一覧の移設先(md)への案内が yml ヘッダから消えている");
});

test("UI一覧(STAT_CAPS_SECTIONS)は 設定リファレンス md の「効くキー一覧」の全キーをちょうどカバーする", () => {
  assert.ok(fs.existsSync(STAT_CAPS_DOC), `not found: ${STAT_CAPS_DOC}`);
  const src = fs.readFileSync(STAT_CAPS_DOC, "utf8");
  // 「## 効くキー一覧」以降・「## まだ効かないキー」より前にある ``` フェンス内の行を全て集める。
  const lines = src.split("\n");
  const startIdx = lines.findIndex((l) => l.trim() === "## 効くキー一覧");
  // 終端は「次の h2 見出し」で取る。2026-07-27 に後続の h2 が「## まだ効かないキー」から
  // 「## ATTRIBUTE チャネル — …」へ変わった際、終端見出しを文字列で決め打ちしていたせいで
  // このテストが落ちた。見出し名ではなくレベルで区切れば、章の並べ替えに巻き込まれない。
  const endIdx = lines.findIndex((l, i) => i > startIdx && /^##\s/.test(l.trim()));
  assert.ok(startIdx >= 0 && endIdx > startIdx,
    "md の「## 効くキー一覧」セクション(次のh2見出しまで)の範囲特定に失敗");
  const keys = [];
  let inFence = false;
  for (const l of lines.slice(startIdx + 1, endIdx)) {
    if (l.trim().startsWith("```")) { inFence = !inFence; continue; }
    if (!inFence) continue;
    const key = l.trim();
    if (key) {
      assert.match(key, /^[a-z][a-z0-9-]*$/, `キー一覧のフェンス内に想定外の行: ${JSON.stringify(l)}`);
      keys.push(key);
    }
  }
  assert.ok(keys.length > 0, "md から抽出できたキーが0件(フェンスの書式が想定と合っていない)");
  const dupes = keys.filter((k, i) => keys.indexOf(k) !== i);
  assert.deepEqual(dupes, [], `md のキー一覧に重複: ${dupes.join(", ")}`);

  global.window = global.window || {};
  global.document = global.document || {};
  global.window.h = (tag, props) => ({ tag, props, children: [], appendChild() {} });
  delete require.cache[require.resolve("../public/js/tf-base-stats.js")];
  const { statCapsAllKeys } = require("../public/js/tf-base-stats.js");
  const uiKeys = statCapsAllKeys();

  const missingFromUi = keys.filter((k) => !uiKeys.includes(k));
  const extraInUi = uiKeys.filter((k) => !keys.includes(k));
  assert.deepEqual(missingFromUi, [], `mdにあるがUIに無いキー: ${missingFromUi.join(", ")}`);
  assert.deepEqual(extraInUi, [], `UIにあるがmdに無いキー: ${extraInUi.join(", ")}`);
});
