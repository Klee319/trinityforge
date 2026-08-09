"use strict";

// 2026-08-09 ユーザー指示「ステータスの小数点が細かすぎる。長くて小数点一桁、ゲームバランスが
// 壊れないものは整数値に収めるように」「エディタ上も小数点2桁以下は消してほしい」の回帰ロック。
//
// 【何を固定するのか】
// item-stats.yml の値を「エディタが画面に出す単位」へ直したとき、小数第2位以下が出ないこと。
// 表示単位は stats/lore.yml の format で決まる:
//   - format: PERCENT … エディタは 値×100 を % として入出力する (forms.js statValueControl)
//   - それ以外       … 値をそのまま表示する
// ゲーム内 lore 側の decimals は全ステで 0 か 1 なので、ここが2桁以上あっても画面には出ず、
// 「エディタと yml にだけ存在する細かさ」になる。数値そのものは戦闘計算に効くので、
// 見えない桁を残すと「表示は同じなのに強さが違う」状態を作る。
//
// 【例外の扱い】
// 例外は2種類だけ。どちらも「リストが腐ったら検査ごと無効になる」のを避けるため、
// 例外そのものが今も成立していることを毎回検証する。
//   (1) MISDECLARED_STATS … lore.yml の format 宣言が実態(0〜1の割合)と食い違っているステ。
//       値は正しいので触らない。lore.yml を直したら【この例外は自動で落ちる】ので、
//       直したときに必ずここへ戻ってくることになる。
//   (2) PER_QUALITY_EXEMPT … per-quality(品質1あたりの上昇量)を1桁へ丸めると 17〜67% の
//       増減になる箇所。値を変えたらテストが落ちるので、変更時に再検討が強制される。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const REPO = path.resolve(__dirname, "..", "..", "..");
const RES = path.join(REPO, "TrinityForge", "src", "main", "resources");
const ITEM_STATS = path.join(RES, "stats", "item-stats.yml");
const LORE = path.join(RES, "stats", "lore.yml");

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

// lore.yml が「0〜1の割合」を FLAT と宣言しているステ。PercentStatNormalize が率として扱うのに
// 表示が FLAT decimals:0 なので、実機の lore には "+0%" としか出ない(既知の表示バグ)。
// 値(0.018 等)は割合として正しいので丸めない。
const MISDECLARED_STATS = new Set(["mana-cost-reduction-percent"]);

// per-quality を1桁へ丸めると設計が壊れる箇所。キーは item-stats.yml のエントリ名。
const PER_QUALITY_EXEMPT = new Map([
  ["SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE#300007|magic-resistance", 0.0015],
  ["ARMS_UP_POTTERY_SHERD#300020|workbench-quality-bonus", 0.12],
  ["BREWER_POTTERY_SHERD#300021|ritual-quality-bonus", 0.12],
  ["EXPLORER_POTTERY_SHERD#300040|loot-luck", 0.15],
  ["FLOWER_BANNER_PATTERN#300041|potion-quality-bonus", 0.12],
  ["PRIZE_POTTERY_SHERD#300044|mob-drop-quality", 0.06],
  ["NETHERITE_UPGRADE_SMITHING_TEMPLATE#300045|gathering-efficiency", 0.06],
]);

function shownDecimals(format, value) {
  const shown = format === "PERCENT" ? Math.round(value * 100 * 1e6) / 1e6 : value;
  const text = String(shown);
  const dot = text.indexOf(".");
  return dot < 0 ? 0 : text.length - dot - 1;
}

function collectViolations() {
  const formats = loadStatFormats();
  const items = YAML.parse(fs.readFileSync(ITEM_STATS, "utf8")).items || {};
  const out = [];
  for (const [statKey, entry] of Object.entries(items)) {
    if (!entry || typeof entry !== "object") continue;
    const check = (stat, section, value) => {
      if (typeof value !== "number") return;
      if (shownDecimals(formats[stat] || "FLAT", value) <= 1) return;
      out.push({ statKey, stat, section, value });
    };
    for (const section of ["fixed", "per-quality"]) {
      const map = entry[section];
      if (!map || typeof map !== "object") continue;
      for (const [stat, value] of Object.entries(map)) check(stat, section, value);
    }
    if (entry.random && typeof entry.random === "object") {
      for (const [stat, range] of Object.entries(entry.random)) {
        if (!range || typeof range !== "object") continue;
        check(stat, "random.min", range.min);
        check(stat, "random.max", range.max);
      }
    }
  }
  return out;
}

test("item-stats.yml の値は、エディタの表示単位で小数第1位までに収まっている", () => {
  const leftovers = collectViolations().filter((v) => {
    if (MISDECLARED_STATS.has(v.stat)) return false;
    if (v.section === "per-quality" && PER_QUALITY_EXEMPT.has(`${v.statKey}|${v.stat}`)) return false;
    return true;
  });
  assert.deepEqual(leftovers.map((v) => `${v.statKey} ${v.section}.${v.stat}=${v.value}`), [],
    "エディタの表示単位で小数第2位以下が残っている(見えない桁は戦闘計算だけを揺らす)");
});

test("MISDECLARED_STATS の例外は、lore.yml が今も PERCENT でないことに支えられている", () => {
  const formats = loadStatFormats();
  for (const stat of MISDECLARED_STATS) {
    assert.ok(formats[stat], `${stat} が lore.yml に無い。例外の前提が消えている`);
    assert.notEqual(formats[stat], "PERCENT",
      `${stat} の lore.yml が PERCENT に直っている。表示バグが解消したので、この例外は撤去し`
        + "値(0.018 等)が %表示で小数第1位に収まることを確認すること");
  }
});

test("PER_QUALITY_EXEMPT の例外は、対象アイテムと値が今も実在する", () => {
  const items = YAML.parse(fs.readFileSync(ITEM_STATS, "utf8")).items || {};
  const stale = [];
  for (const [key, expected] of PER_QUALITY_EXEMPT) {
    const [statKey, stat] = key.split("|");
    const entry = items[statKey];
    if (!entry) { stale.push(`${statKey} が item-stats.yml に無い`); continue; }
    const actual = (entry["per-quality"] || {})[stat];
    if (actual !== expected) stale.push(`${statKey} per-quality.${stat}=${actual} (例外の記録は ${expected})`);
  }
  assert.deepEqual(stale, [],
    "例外リストが実データとズレている。リストが腐ると検査ごと無効になるので、"
      + "値を変えたときはここも更新して『1桁へ丸められないか』を再検討すること");
});

test("この検査は空振りしていない(値を十分な件数見ている)", () => {
  const formats = loadStatFormats();
  const items = YAML.parse(fs.readFileSync(ITEM_STATS, "utf8")).items || {};
  let seen = 0;
  for (const entry of Object.values(items)) {
    if (!entry || typeof entry !== "object") continue;
    for (const section of ["fixed", "per-quality"]) {
      for (const v of Object.values(entry[section] || {})) if (typeof v === "number") seen++;
    }
  }
  assert.ok(Object.keys(formats).length > 100, `lore.yml から format を ${Object.keys(formats).length} 件しか読めていない`);
  assert.ok(seen > 2000, `item-stats.yml の数値を ${seen} 件しか見ていない(読み取りが壊れている疑い)`);
});
