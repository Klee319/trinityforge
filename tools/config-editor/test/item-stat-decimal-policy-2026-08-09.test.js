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
// 例外は1種類だけ。「リストが腐ったら検査ごと無効になる」のを避けるため、
// 例外そのものが今も成立していることを毎回検証する。
//   PER_QUALITY_EXEMPT … per-quality(品質1あたりの上昇量)を1桁へ丸めると 17〜67% の
//   増減になる箇所。値を変えたらテストが落ちるので、変更時に再検討が強制される。
//
// 2026-08-12: もう1種類あった MISDECLARED_STATS(mana-cost-reduction-percent)は撤去した。
// lore.yml の宣言が FLAT + unit "%" のまま実態(0〜1の割合)と食い違っていたのを PERCENT へ
// 訂正したため、例外なしで %表示の小数第1位(0.004 -> 0.4% / 0.094 -> 9.4%)に収まる。

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

// PercentStatNormalize が [0,1] の割合として扱うキーは、lore.yml でも PERCENT でなければ
// ならない。FLAT のままだと (1) 実機の lore が 0.004 を "+0%" としか出さず (2) 設定エディタも
// %入力にならず小数のまま出る、という 2026-08-12 まで実在したバグに戻る。
// PercentStatNormalize.java の StatKeys.canonical("...") 列挙から機械的に引く。
const PERCENT_NORMALIZE_JAVA = path.join(
  REPO, "TrinityForge", "src", "main", "java", "com", "trinityforge", "stats", "PercentStatNormalize.java");
// crit-damage 等「率だが 1 を超えるのが正当」なキーは Java 側で意図的に除外されているので、
// この列挙に入っていない = ここで検査されないのが正しい。
function javaRateKeys() {
  const src = fs.readFileSync(PERCENT_NORMALIZE_JAVA, "utf8");
  return [...src.matchAll(/StatKeys\.canonical\("([^"]+)"\)/g)].map((m) => m[1]);
}

// per-quality を1桁へ丸めると設計が壊れる箇所。キーは item-stats.yml のエントリ名。
const PER_QUALITY_EXEMPT = new Map([
  ["SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE#300007|magic-resistance", 0.0015],
  // 2026-08-18(W-103) スレッドのベース材質を鍛冶型へ統一したのでキー名が変わった。
  // ここは item-stats.yml のエントリ名そのままなので、材質を変えたら必ず追随させる
  // (追随を忘れると除外が外れ、意図した小数がまとめて違反として出る)。
  ["TIDE_ARMOR_TRIM_SMITHING_TEMPLATE#300020|workbench-quality-bonus", 0.12],
  ["WARD_ARMOR_TRIM_SMITHING_TEMPLATE#300021|ritual-quality-bonus", 0.12],
  ["RAISER_ARMOR_TRIM_SMITHING_TEMPLATE#300040|loot-luck", 0.15],
  ["SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE#300041|potion-quality-bonus", 0.12],
  ["COAST_ARMOR_TRIM_SMITHING_TEMPLATE#300044|mob-drop-quality", 0.06],
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
    if (v.section === "per-quality" && PER_QUALITY_EXEMPT.has(`${v.statKey}|${v.stat}`)) return false;
    return true;
  });
  assert.deepEqual(leftovers.map((v) => `${v.statKey} ${v.section}.${v.stat}=${v.value}`), [],
    "エディタの表示単位で小数第2位以下が残っている(見えない桁は戦闘計算だけを揺らす)");
});

test("Java が割合として扱うステは lore.yml でも PERCENT である(2026-08-12)", () => {
  const formats = loadStatFormats();
  const misdeclared = [];
  let checked = 0;
  for (const stat of javaRateKeys()) {
    const format = formats[stat];
    if (!format) continue; // lore.yml に出ないキー(base-stats 専用など)は表示の話が無い
    checked += 1;
    if (format !== "PERCENT") misdeclared.push(`${stat} は lore.yml で ${format}`);
  }
  assert.ok(checked >= 30, `検査対象が ${checked} 件しかない。Java 側の列挙の読み取りが壊れている`);
  assert.deepEqual(misdeclared, [],
    "PercentStatNormalize が [0,1] の割合として扱うのに lore.yml が PERCENT でないステがある。"
      + "この食い違いは (1) 実機の lore が 0.004 を \"+0%\" としか出さない (2) 設定エディタが "
      + "%入力にならず小数のまま出す、の2つを同時に起こす"
      + "(2026-08-12 に mana-cost-reduction-percent で実際に発生)");
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
