"use strict";

// 2026-08-21 ユーザー報告: 「エディターでアイテムをセレクトメニューから選択する時に
// 存在しないアイテム custom:エンチャントされた金リンゴ が表示されるので消して。
// こんなアイテムカタログにない」
//
// 正体は候補源に ArsPaper items.yml が混ざっていたこと。あれは<b>レシピ定義だけ</b>の
// ファイルでカスタムアイテムを1件も登録しないので、items: のキーは「レシピの名前」であって
// アイテムIDではない。出荷 items.yml に残る2件はどちらも結果がバニラアイテムのレシピで、
// うち1件のキーが日本語（＝カスタムアイテムの表示名に見える）だったため誤選択を誘っていた。
//
// 【許可リストにしない】このIDだけ弾く形にすると、items.yml にレシピが1本増えるたびに
// 同じ幽霊が復活する。母集合は<b>実 items.yml のキー全部</b>から自動で決まるようにして、
// 「items.yml 由来のIDが1つでも候補に居たら落ちる」を縛る。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const ROOT = path.resolve(__dirname, "..");
const REPO_ROOT = path.resolve(ROOT, "../..");
const ARS_RESOURCES = path.join(REPO_ROOT, "fork-handoff/arspaper/fork/src/main/resources");
const TF_RESOURCES = path.join(REPO_ROOT, "TrinityForge/src/main/resources");

const { buildRecipeCustomCandidates } =
  require(path.join(ROOT, "public/js/recipe-custom-candidates.js"));

function readYml(file) {
  if (!fs.existsSync(file)) return null;
  return YAML.parse(fs.readFileSync(file, "utf8"));
}

function shippedSources() {
  return {
    catalog: readYml(path.join(TF_RESOURCES, "items/catalog.yml")),
    materials: readYml(path.join(ARS_RESOURCES, "materials.yml")),
    threads: readYml(path.join(ARS_RESOURCES, "threads.yml")),
    externalItems: readYml(path.join(TF_RESOURCES, "items/external-items.yml"))
  };
}

test("items.yml のキーは1つも custom: 候補にならない(レシピ定義でアイテム定義ではない)", () => {
  const items = readYml(path.join(ARS_RESOURCES, "items.yml"));
  if (!items) {
    console.log("skip: fork-handoff/arspaper のソースがこのワークツリーに無い");
    return;
  }
  const recipeOnlyIds = Object.keys(items.items || {});
  assert.ok(recipeOnlyIds.length > 0,
    "items.yml の items: が空。母集合が消えるとこの検査は何も見なくなるので、"
    + "空になったなら候補源から外れていることを別の形で確かめること");

  const candidates = buildRecipeCustomCandidates(shippedSources());
  const ids = new Set(candidates.map((c) => c.id));
  const leaked = recipeOnlyIds.filter((id) => ids.has(id));

  assert.deepEqual(leaked, [],
    "items.yml 由来のIDが候補に混ざっている（選ぶと決して解決されない custom:<id> が"
    + "config へ書き込まれる）: " + JSON.stringify(leaked));
});

test("本物のカスタムアイテムは候補に残る(幽霊を消したついでに実在品まで落とさない)", () => {
  const sources = shippedSources();
  if (!sources.materials || !sources.threads) {
    console.log("skip: fork-handoff/arspaper のソースがこのワークツリーに無い");
    return;
  }
  const candidates = buildRecipeCustomCandidates(sources);
  const ids = new Set(candidates.map((c) => c.id));

  assert.ok(candidates.length > 200, `候補が ${candidates.length} 件しかない(源のどれかが落ちている)`);
  assert.ok(ids.has("thread_empty"), "空のスレッドは threads.yml に行が無いので手で足す必要がある");
  for (const id of Object.keys(sources.materials.materials || {})) {
    assert.ok(ids.has(id), `materials.yml の ${id} が候補から落ちている`);
  }
  for (const id of Object.keys((sources.catalog || {}).items || {})) {
    assert.ok(ids.has(id), `catalog.yml の ${id} が候補から落ちている`);
  }
});

test("ラベルは表示名から取り、無ければIDへ落とす(生の &記法/MiniMessage を出さない)", () => {
  const candidates = buildRecipeCustomCandidates({
    catalog: { items: { alpha: { "display-name": "アルファ" }, nameless: {} } },
    materials: { materials: { beta: { display_name: "ベータ" } } },
    threads: { threads: { fire: { display_name: "炎のスレッド" } } },
    externalItems: { items: { ext: {} } }
  });
  const byId = Object.fromEntries(candidates.map((c) => [c.id, c.label]));

  assert.equal(byId.alpha, "アルファ");
  assert.equal(byId.nameless, "nameless", "表示名が無ければID(空ラベルにすると選択肢が空欄になる)");
  assert.equal(byId.beta, "ベータ");
  assert.equal(byId.thread_fire, "炎のスレッド", "スレッドは thread_ 接頭辞付きで登録する");
  assert.equal(byId.ext, "外部: ext");
});

test("recipes.js は items.yml を取りに行かない(候補源から本当に外れていること)", () => {
  const src = fs.readFileSync(path.join(ROOT, "public/js/recipes.js"), "utf8");
  const grabbed = [...src.matchAll(/grab\("([a-z-]+)"\)/g)].map((m) => m[1]);
  assert.ok(grabbed.length > 0, "grab() の呼び出しが読めない(実装の形が変わったなら検査も直す)");
  assert.ok(!grabbed.includes("items"),
    "recipes.js がまだ items.yml を候補源として読んでいる: " + JSON.stringify(grabbed));
});
