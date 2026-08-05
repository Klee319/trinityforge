"use strict";

// ---------------------------------------------------------------------------
// スキルツリーの説明文を全行削除しても保存されない不具合 (2026-08-05)
//
// 症状: スキル画面(スキル: 弓術 など)でフレーバー説明文の行を「×」で消しても
//   ・別画面へ移動しても未保存警告が出ない
//   ・保存ボタンは「自分の変更はありません」と言ってサーバの内容を読み直す
//       (= 削除が黙って捨てられる)
//
// 真因: 出荷 skilltree/*.yml の大半のノードは新キー `description` を持たず、旧キー
//   `effect-text` だけを持つ (archery/ars_magic/light_armor/… は description が 0 件)。
//   旧実装は空になったとき `delete obj.description` しか行わないので、そのノードでは
//   **delete が no-op** になり working に差分が 1 バイトも出ない。未保存判定
//   (app.js isEditorDirty = deepEqual(getData(), baseSnapshot)) は差分だけを見るため
//   「変更なし」と判断される。light_weapons / heavy_weapons だけが description を
//   持つので、そこでは同じ操作が正しく dirty になる ── **スキル依存**の再現条件だった。
//
// TF 側 (SkillTreeConfig#description) は description が無い/空白のときだけ effect-text へ
// フォールバックするので、`description: ""` を書いても説明は消えない。空にする意図を
// 表現できる唯一の書き方は「両方のキーを消す」こと。
// ---------------------------------------------------------------------------

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

global.window = global.window || {};
require("../public/js/tf-skilltree.js");
const applyDescription = global.window.applySkillNodeDescription;

test("公開されている書き戻しヘルパーがある(DOMハーネス無しで規則を検証するため)", () => {
  assert.equal(typeof applyDescription, "function");
});

test("旧effect-textだけのノードで全行削除すると、working に差分が出る(未保存判定に掛かる)", () => {
  const node = { role: "branch", "effect-text": "&7ダメージ+5%" };
  const before = JSON.parse(JSON.stringify(node));

  applyDescription(node, "");

  assert.notDeepEqual(node, before,
    "差分が出ないと isEditorDirty が false になり、保存で黙って捨てられる");
  assert.equal(Object.prototype.hasOwnProperty.call(node, "effect-text"), false,
    "TF は description が空だと effect-text へフォールバックするので旧キーも消す");
  assert.equal(Object.prototype.hasOwnProperty.call(node, "description"), false);
  assert.equal(node.role, "branch", "他のキーは巻き添えにしない");
});

test("description と effect-text の両方を持つノードでも、全行削除で両方消える", () => {
  const node = { description: "新しい説明", "effect-text": "古い説明" };

  applyDescription(node, "");

  assert.deepEqual(node, {},
    "description だけ消すと effect-text が復活して説明が消えない");
});

test("説明が空でないときは description に書き、effect-text は温存する", () => {
  const node = { role: "branch", "effect-text": "&7ダメージ+5%" };

  applyDescription(node, "&7ダメージ+7%");

  assert.equal(node.description, "&7ダメージ+7%");
  assert.equal(node["effect-text"], "&7ダメージ+5%",
    "非空の編集では旧キーを消さない(description が優先されるので表示は新しい方になる)");
});

test("複数行は \\n 結合で1つの文字列として書く", () => {
  const node = {};

  applyDescription(node, "1行目\n2行目");

  assert.equal(node.description, "1行目\n2行目");
});

test("前提: 出荷 skilltree/*.yml は description を持たないノードが大半である", () => {
  // この前提が崩れる(全ノードが description を持つ)と、上のバグは再現しなくなる。
  // 逆に前提が生きている限り、旧キーを消さない実装へ戻すと必ず再発する。
  const dir = path.join(__dirname, "..", "..", "..", "TrinityForge", "src", "main", "resources", "skilltree");
  const files = fs.readdirSync(dir).filter((f) => f.endsWith(".yml"));
  assert.ok(files.length > 0, "skilltree/*.yml が見つからない");

  const legacyOnly = files.filter((f) => {
    const src = fs.readFileSync(path.join(dir, f), "utf8");
    const hasDescription = /^\s*description:/m.test(src);
    const hasEffectText = /^\s*effect-text:/m.test(src);
    return hasEffectText && !hasDescription;
  });
  assert.ok(legacyOnly.length > 0,
    "effect-text だけを持つ yml が1つも無い ── 再現条件が消えたので本テストの前提を見直す");
});
