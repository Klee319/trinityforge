"use strict";

// 保存(serializeConfig)で yml 本文のコメントが消えないこと (2026-08-16)。
//
// 旧実装は「YAML.stringify し直してファイル先頭のヘッダだけ貼り直す」だったので、
// 本文中のコメントが保存のたびに全部消えていた。実害として skilltree/ars_smithing.yml の
// 「enchant_book_* は method: ritual なので recipe: では無効」という注意書き4行が消え、
// 同じチャンネル取り違え(無言で常時解放)が再発した。

const test = require("node:test");
const assert = require("node:assert/strict");
const YAML = require("yaml");

const { serializeConfig } = require("../lib/yamlio.js");

const SAMPLE = [
  "# ヘッダ: このファイルの説明",
  "# 2行目",
  "",
  "skill: ARS_SMITHING",
  "nodes:",
  "  A:",
  "    name: \"基礎\"",
  // 行末コメント前の空白は1個に正規化される(値の前に2個以上空けても1個になる)。
  // 出荷ymlは1個で書かれているので実ファイルの往復は完全一致する。
  "    cost: 1 # 取得コスト",
  "    # このノードは儀式チャンネルでないと効かない",
  "    dedicated-effects:",
  "      - id: ritual:waystone",
  "      - id: ritual:teleport_compass",
  "  B:",
  "    name: \"応用\"",
  "    cost: 2",
  ""
].join("\n");

test("無編集の往復では1文字も変わらない", () => {
  const data = YAML.parse(SAMPLE);
  assert.equal(serializeConfig(data, SAMPLE), SAMPLE);
});

test("本文中のコメントが保存後も残る", () => {
  const data = YAML.parse(SAMPLE);
  data.nodes.A.cost = 3;
  const out = serializeConfig(data, SAMPLE);

  assert.ok(out.includes("# このノードは儀式チャンネルでないと効かない"),
    "キーの前に置いたコメントが消えている");
  assert.ok(out.includes("# 取得コスト"), "行末コメントが消えている");
  assert.ok(out.includes("# ヘッダ: このファイルの説明"), "ヘッダコメントが消えている");
  assert.equal(YAML.parse(out).nodes.A.cost, 3, "編集内容が反映されていない");
});

test("キーの追加・削除がコメントを壊さない", () => {
  const data = YAML.parse(SAMPLE);
  delete data.nodes.B;
  data.nodes.A.icon = "AMETHYST_SHARD";
  const out = serializeConfig(data, SAMPLE);
  const parsed = YAML.parse(out);

  assert.equal(parsed.nodes.B, undefined, "削除したノードが残っている");
  assert.equal(parsed.nodes.A.icon, "AMETHYST_SHARD");
  assert.ok(out.includes("# このノードは儀式チャンネルでないと効かない"));
});

test("リストの要素を差し替えても他要素のコメントは残る", () => {
  const withListComment = SAMPLE.replace(
    "      - id: ritual:waystone",
    "      # ウェイストーンは儀式\n      - id: ritual:waystone");
  const data = YAML.parse(withListComment);
  data.nodes.A["dedicated-effects"][1] = { id: "ritual:enchant_book_share" };
  const out = serializeConfig(data, withListComment);

  assert.ok(out.includes("# ウェイストーンは儀式"), "残した要素のコメントが消えている");
  assert.deepEqual(YAML.parse(out).nodes.A["dedicated-effects"], [
    { id: "ritual:waystone" }, { id: "ritual:enchant_book_share" }
  ]);
});

test("元ファイルが壊れている/空のときは従来どおり全面直列化にフォールバックする", () => {
  const broken = "key: [unclosed\n";
  const out = serializeConfig({ key: "value" }, broken);
  assert.equal(YAML.parse(out).key, "value");

  const fromNothing = serializeConfig({ a: 1 }, "");
  assert.equal(YAML.parse(fromNothing).a, 1);
});

test("出荷ymlを開いて保存しただけなら1バイトも変わらない", () => {
  const fs = require("node:fs");
  const path = require("node:path");
  const { readConfig } = require("../lib/yamlio.js");
  const root = path.resolve(__dirname, "../../../TrinityForge/src/main/resources");
  // コメントの置き方が違う代表を選ぶ(ヘッダのみ / 本文コメントあり / 巨大ファイル)。
  const targets = [
    "skilltree/ars_smithing.yml",
    "skilltree/mining.yml",
    "progression/achievements.yml",
    "items/catalog.yml",
    "stats/lore.yml"
  ];
  let checked = 0;
  for (const rel of targets) {
    const abs = path.join(root, rel);
    if (!fs.existsSync(abs)) continue; // 他セッションの整理でファイルが動いても落とさない
    const { data, raw } = readConfig(abs);
    assert.equal(serializeConfig(data, raw), raw,
      `${rel}: 開いて保存しただけで内容が変わっている(差分が読めなくなる)`);
    checked++;
  }
  assert.ok(checked >= 3, `検査できた出荷ymlが ${checked} 件しかない(パスがずれている)`);
});

test("値の型が変わってもデータは正しく書き出される", () => {
  const data = YAML.parse(SAMPLE);
  data.nodes.A.cost = { min: 1, max: 2 }; // スカラー → マップ
  const out = serializeConfig(data, SAMPLE);
  assert.deepEqual(YAML.parse(out).nodes.A.cost, { min: 1, max: 2 });
});
