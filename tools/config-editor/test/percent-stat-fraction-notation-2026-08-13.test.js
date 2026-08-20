"use strict";

// ---------------------------------------------------------------------------
// %ステの出荷値は「割合記法(0.15)」で書く ── パーセントポイント記法(15)を残さない (2026-08-13)
//
// 実サーバ報告「幸運ステータスが 2000% になっている」の回帰ロック。
//
// 【なぜ実害が「表示だけ」で済んでいたのか】
// `PercentStatNormalize.coerce` は率キーに対して **|v| が 1 より大きい 100 以下の整数**を
// /100 へ矯正する。つまり `mining-fortune: 20` は実行時に 0.2 になり、ゲーム内の効果は正しい。
// 壊れるのは設定エディタ側だけで、こちらは `stats/lore.yml` の `format: PERCENT` に従って
// **yml の生値を x100 して表示する**ので `20` が「2000%」と出る。
//
// 【なぜテストで縛るのか】
// 矯正が効いているぶん、間違った記法を書いても**サーバログにも lore にも何も出ない**。
// 気づけるのはエディタを開いた人間だけで、しかも「エディタのバグ」に見える。
// さらに矯正の条件は「整数」なので、`mining-fortune: 20` を後から `20.5` に変えると
// **その瞬間だけ黙って 2050% の実効値になる**(coerce が素通りする)。記法を割合へ統一しておけば
// この地雷ごと消える。
//
// 【対象を率キーに限る理由】
// `crit-damage` のように「1 を超えるのが正当な PERCENT ステ」がある。これらは
// PercentStatNormalize が意図的に矯正対象から外しているので、ここでも対象外にする。
// 検査対象は「lore.yml で PERCENT かつ PercentStatNormalize の率キー」の交差だけ。
// ---------------------------------------------------------------------------

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const REPO = path.resolve(__dirname, "..", "..", "..");
const RES = path.join(REPO, "TrinityForge", "src", "main", "resources");
const LORE = path.join(RES, "stats", "lore.yml");
const PERCENT_NORMALIZE_JAVA = path.join(
  REPO, "TrinityForge", "src", "main", "java", "com", "trinityforge", "stats", "PercentStatNormalize.java");

function loadStatFormats() {
  const lore = YAML.parse(fs.readFileSync(LORE, "utf8"));
  const out = {};
  (function walk(node) {
    if (!node || typeof node !== "object") return;
    for (const [key, value] of Object.entries(node)) {
      if (!value || typeof value !== "object") continue;
      if (value.format || value.decimals != null) out[key] = String(value.format || "FLAT").toUpperCase();
      walk(value);
    }
  })(lore);
  return out;
}

function javaRateKeys() {
  const src = fs.readFileSync(PERCENT_NORMALIZE_JAVA, "utf8");
  return new Set([...src.matchAll(/StatKeys\.canonical\("([^"]+)"\)/g)].map((m) => m[1]));
}

function* ymlFiles(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* ymlFiles(p);
    else if (entry.name.endsWith(".yml")) yield p;
  }
}

// `key: 12.5` / `- key: 12` 形式のスカラー行だけを見る(構造を組まずに全ファイルを掃く)。
const SCALAR_LINE = /^\s*(?:-\s*)?([a-z0-9_-]+):\s*(-?\d+(?:\.\d+)?)\s*(?:#.*)?$/;

// 乗算レイヤ(multipliers: / mainhand-multipliers:)の中では 1.2 は「x1.2」であって 120% ではない。
// 2026-08-15: 率キー(bleed-damage-rate)を乗算レイヤに載せた瞬間にこの検査が誤検知したため、
// ブロック配下を丸ごと除外する。インデントだけで判定する(構造を組まない方針は維持)。
const MULTIPLIER_BLOCK = /^(\s*)(?:mainhand-)?multipliers:\s*(?:#.*)?$/;

function indentOf(line) {
  return line.length - line.replace(/^\s*/, "").length;
}

function scan() {
  const formats = loadStatFormats();
  const rateKeys = javaRateKeys();
  const targets = new Set(
    Object.keys(formats).filter((k) => formats[k] === "PERCENT" && rateKeys.has(k)));
  const offenders = [];
  let scannedValues = 0;
  for (const file of ymlFiles(RES)) {
    if (path.resolve(file) === path.resolve(LORE)) continue;
    const rel = path.relative(REPO, file).replace(/\\/g, "/");
    let multiplierIndent = -1;
    fs.readFileSync(file, "utf8").split(/\r?\n/).forEach((line, i) => {
      if (line.trim() !== "") {
        if (multiplierIndent >= 0 && indentOf(line) <= multiplierIndent) multiplierIndent = -1;
        const block = MULTIPLIER_BLOCK.exec(line);
        if (block) multiplierIndent = block[1].length;
      }
      if (multiplierIndent >= 0) return;
      const m = SCALAR_LINE.exec(line);
      if (!m || !targets.has(m[1])) return;
      scannedValues += 1;
      const v = Number(m[2]);
      // 1 ちょうどは「+100%」として正当なので通す(浮動小数の誤差ぶんだけ緩める)。
      if (Math.abs(v) <= 1.0001) return;
      offenders.push(`${rel}:${i + 1} ${m[1]}: ${v} (エディタ表示 ${v * 100}%)`);
    });
  }
  return { offenders, scannedValues, targetCount: targets.size };
}

test("PERCENT かつ率キーのステは、出荷 yml でも割合記法(|v|<=1)で書かれている", () => {
  const { offenders } = scan();
  assert.deepEqual(offenders, [],
    "パーセントポイント記法(15 = 15%)が残っている。PercentStatNormalize が実行時に /100 するので"
    + "ゲーム内の効果は正しいが、設定エディタは yml の生値を x100 して表示するので 1500% と出る。"
    + "さらに矯正条件は『整数』なので、あとで 15 を 15.5 に変えた瞬間に黙って 1550% の実効値になる。"
    + "割合記法(0.15)へ揃えること");
});

test("この検査は空振りしていない(対象キーと値を十分な件数見ている)", () => {
  const { scannedValues, targetCount } = scan();
  assert.ok(targetCount >= 30,
    `検査対象キーが ${targetCount} 件しかない。lore.yml か PercentStatNormalize.java の読み取りが壊れている`);
  assert.ok(scannedValues >= 100,
    `出荷 yml の該当値を ${scannedValues} 件しか見ていない。走査が壊れている`);
});
