"use strict";

// public/js/colors.js の「差し込みタグ (<icon>/<name>/<value> 等)」対応 (2026-08-05) の回帰テスト。
//
// 背景: 実サーバ報告「editor内でGUI/簡易が選択できるテキスト入力欄で、こちらが自動設定した個所が
// 切り替え不可(例: ロア表示)」。ツリーパーサ (classifyOpenTag/parseNodesMM) は色でも装飾でもない
// タグを未知タグとして parseMiniMessage().ok=false にしていた。ok=false は richTextInput の
// remount() が GUI トグルを disabled にする条件そのものなので、出荷 stats/lore.yml の
// line-template「<gray><icon><name>：<value></gray>」のように差し込みタグを含む値は
// 常に簡易編集しかできなかった。2026-08-04 の <gradient:...> と同型の壊れ方。
//
// 直し方: フィールド側が持つ差し込みタグ名を宣言(richTextInput の opts.placeholders)し、
// 宣言されたタグだけを「閉じタグを持たないただの文字」として text ノードに落とす。
// 宣言しないフィールドの挙動は従来どおり(未知タグ=非対応)で、GUI で表現できない本物の
// MiniMessage タグ(<click:...>/<hover:...>/<font:...>)を取り違えない。
//
// このテストは (1) 出荷 lore.yml の5フィールドが宣言付きで ok:true かつロスレス往復すること、
// (2) 宣言しなければ従来どおり ok:false のままであること、(3) 宣言していない未知タグは
// 宣言リストがあっても非対応のままであること を固定する。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

global.window = global.window || {};
require("../public/js/colors.js");
const COLORS = global.window.COLORS;

const ROOT = path.resolve(__dirname, "..");
const LORE_YML = path.resolve(ROOT, "../../TrinityForge/src/main/resources/stats/lore.yml");

// tf-lore.js の richTextInput 呼び出しと同じ宣言。増やしたらこちらも増やす。
const FIELDS = [
  { path: ["layout", "line-template"], placeholders: ["icon", "name", "value"] },
  { path: ["layout", "score-line-template"], placeholders: ["tier", "tier-name", "score"] },
  { path: ["bind", "owner-line"], placeholders: ["owner"] },
  { path: ["bind", "use-requirement-line"], placeholders: ["level", "skill"] }
];

function readShipped() {
  const data = YAML.parse(fs.readFileSync(LORE_YML, "utf8"));
  const out = [];
  for (const f of FIELDS) {
    let node = data;
    for (const key of f.path) node = node && typeof node === "object" ? node[key] : undefined;
    if (typeof node === "string" && node !== "") {
      out.push({ label: f.path.join("."), value: node, placeholders: f.placeholders });
    }
  }
  return out;
}

test("出荷 lore.yml の差し込みタグ付きテンプレートが GUI モードで編集できる(ok:true かつロスレス)", () => {
  const shipped = readShipped();
  // 空振り防止: 少なくとも line-template は出荷値を持っているはず。
  assert.ok(shipped.some((s) => s.label === "layout.line-template"),
    "出荷 lore.yml から layout.line-template を読めていない(この照合は空振りしている)");
  assert.ok(shipped.some((s) => s.value.includes("<")),
    "差し込みタグを含む出荷値が1件も無い(この照合は空振りしている)");

  for (const s of shipped) {
    const parsed = COLORS.parseMiniMessage(s.value, s.placeholders);
    assert.equal(parsed.ok, true, `${s.label}: GUI 編集が無効になる (${s.value})`);
    assert.equal(COLORS.serializeMiniMessage(parsed.segments), s.value,
      `${s.label}: ロスレス往復しない`);
  }
});

test("宣言しなければ従来どおり非対応(この対応がフィールド単位であることの確認)", () => {
  const template = "<gray><icon><name>：<value></gray>";
  assert.equal(COLORS.parseMiniMessage(template).ok, false);
  assert.equal(COLORS.parseMiniMessage(template, []).ok, false);
  assert.equal(COLORS.parseMiniMessage(template, ["icon", "name", "value"]).ok, true);
});

test("宣言していないタグは宣言リストがあっても非対応のまま(本物のMMタグを取り違えない)", () => {
  // <click:...> は GUI で表現できないので簡易編集に留めなければならない。
  assert.equal(COLORS.parseMiniMessage("<click:run_command:/x>a</click>", ["icon"]).ok, false);
  // 宣言外の差し込みタグも同じ扱い。
  assert.equal(COLORS.parseMiniMessage("<gray><unknown_ph></gray>", ["icon"]).ok, false);
});

test("差し込みタグは色/装飾の入れ子の中でも文字として扱われる", () => {
  const v = "<gray><b><icon></b><name></gray>";
  const parsed = COLORS.parseMiniMessage(v, ["icon", "name"]);
  assert.equal(parsed.ok, true);
  assert.equal(COLORS.serializeMiniMessage(parsed.segments), v);
});
