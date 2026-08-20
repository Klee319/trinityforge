"use strict";

// public/js/colors.js の <gradient:...> 対応 (2026-08-04) の回帰テスト。
//
// 背景: colors.js のツリーパーサ (classifyOpenTag/parseNodesMM) は <gradient:...> を未知タグとして
// 扱い parseMiniMessage().ok=false を返していた。ok=false は richTextInput の remount() が
// GUI トグルボタンを disabled にする条件そのものなので、「AIがyml直書きした gradient 付き
// display-name (items/catalog.yml の binder_*/key_binder 系18件) だけ GUI 編集ボタンが
// 使えない」という報告と一致していた(詳細は docs/agent-context/config-editor.md の
// 「<gradient:...> は表示プレビューだけ対応・リッチ編集(GUI)は非対応」の項)。
//
// 直し方: classifyOpenTag に gradient ノード種別を足し、serializeMiniMessage は
// (既存の openRaw 保持パスがノード種別に依存しない汎用実装のため) 無改修でロスレス往復する。
// このテストは (1) 実際に出荷される18件の gradient 付き display-name が全て
// serialize(parse(v))===v で往復すること、(2) ok:true になり GUI が有効化される条件を
// 満たすこと、(3) 既存パターン(単色hex/名前色/装飾/legacy&/空文字/null/未知タグ)が
// 従来どおりであること(回帰防止) を固定する。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

global.window = global.window || {};
require("../public/js/colors.js");
const COLORS = global.window.COLORS;

const ROOT = path.resolve(__dirname, "..");
const CATALOG = path.resolve(ROOT, "../../TrinityForge/src/main/resources/items/catalog.yml");

function loadGradientDisplayNames() {
  const data = YAML.parse(fs.readFileSync(CATALOG, "utf8"));
  const found = [];
  for (const [id, item] of Object.entries(data.items || {})) {
    const dn = item && item["display-name"];
    if (typeof dn === "string" && dn.includes("<gradient:")) found.push({ id, dn });
  }
  return found;
}

test("items/catalog.yml の gradient 付き display-name は実際に18件存在する(前提確認)", () => {
  const found = loadGradientDisplayNames();
  assert.equal(found.length, 18, `想定は18件。実際: ${found.length}`);
});

test("gradient 付き display-name 18件すべてが ok:true かつ完全往復する(serialize(parse(v))===v)", () => {
  const found = loadGradientDisplayNames();
  assert.ok(found.length > 0, "テスト対象データが読めていない");
  for (const { id, dn } of found) {
    const parsed = COLORS.parseMiniMessage(dn);
    assert.equal(parsed.ok, true, `${id}: ok=falseになっている(GUIボタンが無効化される) raw=${dn}`);
    const back = COLORS.serializeMiniMessage(parsed.segments);
    assert.equal(back, dn, `${id}: 往復失敗`);
  }
});

test("gradient ノードは flattenMMNodes で gradient(生の引数配列) を持つ葉ランへ展開される", () => {
  const raw = "<gradient:#1b1b3a:#b388ff:#1b1b3a>世界を繋ぐ剣</gradient>";
  const parsed = COLORS.parseMiniMessage(raw);
  assert.equal(parsed.ok, true);
  const runs = COLORS.flattenMMNodes(parsed.segments);
  assert.equal(runs.length, 1);
  assert.deepEqual(runs[0].gradient, ["#1b1b3a", "#b388ff", "#1b1b3a"]);
  assert.equal(runs[0].text, "世界を繋ぐ剣");
});

test("gradientEndpoints は両端の色だけを解決する(中間ストップはプレビュー/GUIとも無視)", () => {
  const ep = COLORS.gradientEndpoints(["#1b1b3a", "#b388ff", "#1b1b3a"]);
  assert.deepEqual(ep, { from: "#1B1B3A", to: "#1B1B3A" });
  const ep2 = COLORS.gradientEndpoints(["red", "blue"]);
  assert.deepEqual(ep2, { from: "#FF5555", to: "#5555FF" });
});

test("gradientEndpoints は色として解決できるトークンが無ければ null(フォールバック用)", () => {
  assert.equal(COLORS.gradientEndpoints(["foo", "bar"]), null);
  assert.equal(COLORS.gradientEndpoints([]), null);
});

test("buildMMNodes は gradient ランを <gradient:args> ノードとして再構築し、往復する", () => {
  const runs = [{ text: "abc", gradient: ["#111111", "#eeeeee"], decos: [] }];
  const nodes = COLORS.buildMMNodes(runs);
  const out = COLORS.serializeMiniMessage(nodes);
  assert.equal(out, "<gradient:#111111:#eeeeee>abc</gradient>");
});

test("miniMessageToLegacy は gradient を含む文字列を null にする(色を黙って落とさない)", () => {
  const raw = "<gradient:#1b1b3a:#b388ff:#1b1b3a>世界を繋ぐ剣</gradient>";
  assert.equal(COLORS.miniMessageToLegacy(raw), null);
});

test("gradient を含む lore(配列要素の1行)でも同じ経路で ok:true になる", () => {
  const line = "<gradient:red:blue>グラデ行</gradient>";
  const parsed = COLORS.parseMiniMessage(line);
  assert.equal(parsed.ok, true);
  assert.equal(COLORS.serializeMiniMessage(parsed.segments), line);
});

test("gradient が文字列の一部だけを包む形(前後にプレーンテキストがある)でも往復する", () => {
  const raw = "接頭辞<gradient:#111111:#eeeeee>中身</gradient>接尾辞";
  const parsed = COLORS.parseMiniMessage(raw);
  assert.equal(parsed.ok, true);
  assert.equal(COLORS.serializeMiniMessage(parsed.segments), raw);
});

test("引数無しの <gradient> や末尾に phase 数値が付く形も ok:true で往復する(引数は解釈せず保持)", () => {
  for (const raw of ["<gradient>無地</gradient>", "<gradient:#111111:#eeeeee:0.5>位相付き</gradient>"]) {
    const parsed = COLORS.parseMiniMessage(raw);
    assert.equal(parsed.ok, true, raw);
    assert.equal(COLORS.serializeMiniMessage(parsed.segments), raw);
  }
});

// ---- 回帰防止: gradient 対応の追加が既存パターンを壊していないこと ----

test("回帰: 単色hex(#RRGGBB)は従来どおり ok:true", () => {
  const raw = "<color:#7df9ff>単色</color>";
  const parsed = COLORS.parseMiniMessage(raw);
  assert.equal(parsed.ok, true);
  assert.equal(COLORS.serializeMiniMessage(parsed.segments), raw);
});

test("回帰: 名前付き色(<green>等)は従来どおり ok:true", () => {
  const raw = "<green>緑</green>";
  const parsed = COLORS.parseMiniMessage(raw);
  assert.equal(parsed.ok, true);
  assert.equal(COLORS.serializeMiniMessage(parsed.segments), raw);
});

test("回帰: 文字ごとの色(hexchain)は従来どおり ok:true", () => {
  const raw = "<#ff0000>あ</#ff0000><#00ff00>い</#00ff00>";
  const parsed = COLORS.parseMiniMessage(raw);
  assert.equal(parsed.ok, true);
  assert.equal(COLORS.serializeMiniMessage(parsed.segments), raw);
});

test("回帰: 装飾タグ(<b>等)は従来どおり ok:true", () => {
  const raw = "<b>太字</b>と<i>斜体</i>";
  const parsed = COLORS.parseMiniMessage(raw);
  assert.equal(parsed.ok, true);
  assert.equal(COLORS.serializeMiniMessage(parsed.segments), raw);
});

test("回帰: legacy(&コード)は parseLegacy で従来どおり ok:true(gradient変更の影響を受けない)", () => {
  const raw = "&c焔喰いの炉&r";
  // &r(リセット)は既存仕様で未対応 → ok:false のままであるべき(今回の変更で変わらないこと)
  const parsed = COLORS.parseLegacy(raw);
  assert.equal(parsed.ok, false);
  const raw2 = "&c焔喰いの炉";
  const parsed2 = COLORS.parseLegacy(raw2);
  assert.equal(parsed2.ok, true);
  assert.equal(COLORS.serializeLegacy(parsed2.segments), raw2);
});

test("回帰: 空文字列/nullは従来どおり ok:true", () => {
  for (const v of ["", null, undefined]) {
    const parsed = COLORS.parseMiniMessage(v);
    assert.equal(parsed.ok, true, String(v));
    assert.equal(COLORS.serializeMiniMessage(parsed.segments), v == null ? "" : v);
  }
});

test("回帰: 本当に未対応な未知タグは今も ok:false のまま(gradient以外まで緩めていないこと)", () => {
  const raw = "<foobar>不明タグ</foobar>";
  const parsed = COLORS.parseMiniMessage(raw);
  assert.equal(parsed.ok, false, "未知タグまでok:trueにしてしまっている");
});

test("回帰: 迷子の閉じタグ/未終端タグは今も ok:false のまま", () => {
  assert.equal(COLORS.parseMiniMessage("</color>abc").ok, false);
  assert.equal(COLORS.parseMiniMessage("<color:red>abc").ok, false);
});
