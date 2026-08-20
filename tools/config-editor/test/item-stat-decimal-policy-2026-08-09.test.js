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
// 2026-08-19 W-125「editor の数値が小数点以下細かすぎる(76.323902 等)」で許容桁を section 別にした。
// それまでは全 section 一律で「小数第1位まで」+ PER_QUALITY_EXEMPT という7件の許可リストだったが、
// 出荷値を実際に丸めてみると、例外7件はどれも【per-quality を1桁へ丸めると 17〜67% 動く】という
// 同じ理由で並んでいた。理由が同じものは列挙ではなく規則にする方が腐らないので、
// 許可リストは廃止し、per-quality だけ1桁ぶん緩める規則に置き換えた:
//
//   fixed / random.min / random.max … 表示単位で小数第1位まで(合計に直接効く値)
//   per-quality                     … 表示単位で小数第2位まで(品質1段あたりの上昇量。桁が2つ小さい)
//
// per-quality を1桁に縛ると 0.06 -> 0.1 / 0.0015 -> 0.002 のような丸めが起き、
// 「短剣=会心率」「斧=会心倍率」のような武器種の尖り(W-126)や装備間の差がまとめて潰れる。
// 逆に fixed 側を2桁まで許すと、今回の報告そのもの(attack-power 76.32...)が通ってしまう。
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

// section ごとの許容桁(表示単位)。per-quality だけ1桁ぶん緩い理由は冒頭のコメント。
const MAX_DECIMALS = { fixed: 1, "per-quality": 2, "random.min": 1, "random.max": 1 };

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
      if (shownDecimals(formats[stat] || "FLAT", value) <= MAX_DECIMALS[section]) return;
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

test("item-stats.yml の値は、エディタの表示単位で section ごとの許容桁に収まっている", () => {
  const leftovers = collectViolations();
  assert.deepEqual(leftovers.map((v) => `${v.statKey} ${v.section}.${v.stat}=${v.value}`), [],
    "エディタの表示単位で許容桁を超える小数が残っている(見えない桁は戦闘計算だけを揺らす)。"
      + `許容は ${JSON.stringify(MAX_DECIMALS)} 桁`);
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

// per-quality だけ2桁を許しているのは「1桁だと壊れる値があるから」であって、
// 「per-quality は雑でよい」ではない。緩和が言い訳に使われていないことを、
// 許可リストではなく【今の出荷値そのもの】に対して毎回検証する。
// 2桁を使ってよいのは、1桁へ丸めると 5% 以上動く値だけ。
test("per-quality で小数第2位を使っているのは、1桁へ丸めると5%以上動く値だけ", () => {
  const formats = loadStatFormats();
  const items = YAML.parse(fs.readFileSync(ITEM_STATS, "utf8")).items || {};
  const lazy = [];
  let twoDigit = 0;
  for (const [statKey, entry] of Object.entries(items)) {
    if (!entry || typeof entry !== "object") continue;
    for (const [stat, value] of Object.entries(entry["per-quality"] || {})) {
      if (typeof value !== "number" || value === 0) continue;
      const pct = (formats[stat] || "FLAT") === "PERCENT";
      const shown = pct ? Math.round(value * 100 * 1e6) / 1e6 : value;
      if (shownDecimals(formats[stat] || "FLAT", value) <= 1) continue;
      twoDigit += 1;
      const oneDigit = Math.round(shown * 10) / 10;
      const drift = Math.abs((oneDigit - shown) / shown) * 100;
      if (oneDigit !== 0 && drift < 5) {
        lazy.push(`${statKey} per-quality.${stat}=${value} (1桁にしても ${drift.toFixed(1)}% しか動かない)`);
      }
    }
  }
  assert.ok(twoDigit > 50, `2桁の per-quality を ${twoDigit} 件しか見ていない(読み取りが壊れている疑い)`);
  assert.deepEqual(lazy, [],
    "1桁へ落としても実質変わらない per-quality が2桁のまま残っている。"
      + "緩和は『1桁だと設計が壊れる値』のためのものなので、動かない値は1桁へ寄せること");
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
