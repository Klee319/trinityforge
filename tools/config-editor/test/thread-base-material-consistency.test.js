"use strict";

// スレッドのベース材質が 4 つのファイルで一致していることを守る (2026-08-18 / W-103)。
//
// なぜ必要か
// ----------
// 同じ「材質」がプロジェクト内の 5 箇所に別々に書かれている。
//
//   1. ArsPaper `ThreadType.java` の baseMaterial  … 実際に配るアイテムの材質
//   2. TF `items/catalog.yml` の material          … 図鑑・レシピ・エディタが見る材質
//   3. TF `stats/item-stats.yml` の `<材質>#<CMD>` … ステータスの引き当てキー
//   4. `resourcepack/cmd-registry.json` の material … CMD の永続台帳
//   5. `assets/minecraft/items/<材質>.json`         … CMD → モデルの宣言
//
// このうち 3 がずれると **ステータスが丸ごと 0 になるのに警告が 1 行も出ない**。
// 5 が無いと CMD がそもそも参照されず、見た目を分けられない (W-95 と同じ穴)。
// 1 はフォークが .gitignore 除外でクローンに存在しないためここでは検査できない
// (フォーク側は `ThreadsYamlEnumParityTest` が別途縛っている)。
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");
const YAML = require("yaml");

const root = path.resolve(__dirname, "..", "..", "..");
const catalog = YAML.parse(
  fs.readFileSync(path.join(root, "TrinityForge", "src", "main", "resources", "items", "catalog.yml"), "utf8"));
const itemStats = YAML.parse(
  fs.readFileSync(path.join(root, "TrinityForge", "src", "main", "resources", "stats", "item-stats.yml"), "utf8"));
const registry = JSON.parse(
  fs.readFileSync(path.join(root, "resourcepack", "cmd-registry.json"), "utf8"));
const ITEMS_DIR = path.join(root, "resourcepack", "trinityforge-items", "assets", "minecraft", "items");

/** catalog.yml の `thread_*` エントリを (id, material, cmd) で拾う。 */
function threads() {
  return Object.entries(catalog.items || {})
    .filter(([id, entry]) => id.startsWith("thread_") && entry && typeof entry === "object")
    .map(([id, entry]) => ({ id, material: entry.material, cmd: entry["custom-model-data"] }));
}

test("前提: catalog.yml のスレッドを十分な件数見ている(空振りしていない)", () => {
  assert.ok(threads().length >= 45, `スレッドを ${threads().length} 件しか拾えていない`);
});

// 2026-08-21: ベース材質を鍛冶型 → STRING(糸)へ一律変更した(ユーザー指示)。
// 理由は「鍛冶型はバニラの説明文(装備できる部位・素材の一覧)が lore に出て、
// スレッド自身の効果表示を圧迫していた」から。糸はバニラ説明を持たない。
// 検査の向きは変えていない ——【材質は全スレッドで1種類に揃っている】ことを縛る。
// 混在すると item-stats のキー(`<材質>#<CMD>`)と items 宣言が材質ごとに分裂し、
// ステが無言で 0 になる穴(W-103)が戻る。
const THREAD_BASE_MATERIAL = "STRING";

test("スレッドのベース材質はすべて糸である(W-103)", () => {
  const wrong = threads()
    .filter((t) => String(t.material) !== THREAD_BASE_MATERIAL)
    .map((t) => `${t.id}=${t.material}`);
  assert.deepEqual(wrong, [], "鍛冶型・壺の欠片・旗の模様などが混ざっている");
});

test("CMD台帳の材質は catalog.yml と一致する", () => {
  const byId = new Map(registry.allocations.map((a) => [a.id, a]));
  const wrong = [];
  for (const t of threads()) {
    const row = byId.get(t.id);
    if (!row) { wrong.push(`${t.id}: 台帳に行が無い`); continue; }
    if (row.material !== t.material || row.cmd !== t.cmd) {
      wrong.push(`${t.id}: 台帳=${row.material}#${row.cmd} catalog=${t.material}#${t.cmd}`);
    }
  }
  assert.deepEqual(wrong, []);
});

test("item-stats.yml のスレッドのキーは catalog.yml の材質を使う(ずれるとステが無言で0になる)", () => {
  // CMD → catalog の材質。item-stats 側の `<材質>#<CMD>` を CMD で引き当てて突き合わせる。
  const byCmd = new Map(threads().map((t) => [t.cmd, t]));
  const wrong = [];
  for (const key of Object.keys(itemStats.items || {})) {
    const hash = key.indexOf("#");
    if (hash < 0) continue;
    const material = key.slice(0, hash);
    const cmd = Number(key.slice(hash + 1));
    const t = byCmd.get(cmd);
    if (!t) continue;
    if (material !== t.material) wrong.push(`${t.id}(${cmd}): item-stats=${material} catalog=${t.material}`);
  }
  assert.deepEqual(wrong, []);
});

test("スレッドの材質にはリソースパックの items 宣言があり、CMD が並んでいる", () => {
  // 宣言ファイルが無いと CMD は一度も参照されない。テクスチャがまだ無くても
  // fallback だけの宣言を置いておかないと、後からモデルを足せない。
  const missing = [];
  for (const t of threads()) {
    const file = path.join(ITEMS_DIR, `${String(t.material).toLowerCase()}.json`);
    if (!fs.existsSync(file)) { missing.push(`${t.id}: ${path.basename(file)} が無い`); continue; }
    const def = JSON.parse(fs.readFileSync(file, "utf8"));
    const thresholds = (def.model?.entries || []).map((e) => e.threshold);
    if (!def.model?.fallback) missing.push(`${path.basename(file)}: fallback が無い`);
    if (!thresholds.includes(t.cmd)) missing.push(`${t.id}: ${path.basename(file)} に ${t.cmd} が無い`);
  }
  assert.deepEqual(missing, []);
});
