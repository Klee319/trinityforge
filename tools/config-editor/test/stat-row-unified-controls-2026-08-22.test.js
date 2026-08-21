"use strict";

// ---------------------------------------------------------------------------
// ステ行の「加算/乗算の出し方」と「列の縦線」を固定する (2026-08-22 K指摘)
//
// 指摘は2つ:
//   1. セット効果 (thread-sets) の加算/乗算だけ**プルダウン**で、item-stats と
//      スキルツリーは**チェックボックス**。同じ意味のものが3画面で3通りに出ていた。
//      → `modeToggleButton` (押すと 加算 ⇄ 乗算 が入れ替わるボタン) に統一した。
//   2. ランダムロールステ等の縦のラインが行ごとにずれる。
//      真因は「値入力セルの幅が中身で変わる」こと:
//        %ステ      … pct-suffix("%") のぶん広い
//        単位なしステ … 何も付かないので狭い
//        単位ありステ … "ダメ"/"MP"/"/秒" のぶんさらに広い
//      その差がそのまま後ろへ伝わり、実測で「max」が 13px・「乗算」「×」が 26px ずれていた。
//      → 値と単位を `valueCell` (CSS で総幅固定) に包み、単位が何であれ後続の列を動かさない。
//
// ここで固定するのは**機構**であって見た目の文字列ではない:
//   - 値入力が valueCell を通っていること (通っていなければ幅は中身任せに戻る)
//   - .value-cell と中の入力欄の幅が CSS で px 固定であること (auto に戻すと再発する)
//   - 加算/乗算がボタン以外の部品で出ていないこと
// ---------------------------------------------------------------------------

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const JS_DIR = path.join(__dirname, "..", "public", "js");
const CSS = path.join(__dirname, "..", "public", "style.css");

const jsFiles = () => fs.readdirSync(JS_DIR).filter((f) => f.endsWith(".js"));
const readJs = (f) => fs.readFileSync(path.join(JS_DIR, f), "utf8");

// statSelect の呼び出し位置から「その1行分」とみなす範囲。
// (stat-row-percent-input-2026-08-12.test.js と同じ切り出し方。次の statSelect が
//  先に来ればそこで打ち切る。)
const ROW_WINDOW = 3000;

function statRows() {
  const rows = [];
  for (const file of jsFiles()) {
    const src = readJs(file);
    const re = /\bstatSelect\s*\(/g;
    let m;
    while ((m = re.exec(src))) {
      if (/function\s+$/.test(src.slice(Math.max(0, m.index - 20), m.index))) continue;
      const next = src.slice(m.index + 1).search(/\bstatSelect\s*\(/);
      const end = next < 0 ? m.index + ROW_WINDOW : Math.min(m.index + ROW_WINDOW, m.index + 1 + next);
      rows.push({ file, line: src.slice(0, m.index).split("\n").length, body: src.slice(m.index, end) });
    }
  }
  return rows;
}

test("加算/乗算の切替はボタンだけ (チェックボックス/プルダウンが混ざっていない)", () => {
  const strays = [];
  for (const file of jsFiles()) {
    const src = readJs(file);
    src.split("\n").forEach((line, i) => {
      // 旧UI その1: セット効果のプルダウン
      if (line.includes('"mode-select"')) strays.push(`${file}:${i + 1} プルダウン(mode-select)`);
    });
    // 旧UI その2: 「乗算」ラベル付きのインラインチェックボックス。
    // (inline-check 自体は他の用途で使われているので、乗算ラベルとの組み合わせだけを見る)
    const re = /class:\s*"inline-check[^"]*"[\s\S]{0,400}?text:\s*"乗算"/g;
    let m;
    while ((m = re.exec(src))) {
      strays.push(`${file}:${src.slice(0, m.index).split("\n").length} チェックボックス(inline-check + 乗算)`);
    }
  }
  assert.deepEqual(strays, [],
    "加算/乗算の切替がボタン以外で出ている: " + strays.join(" / ")
    + "。2026-08-22 の K指摘は『同じ意味のものが画面ごとに違う部品で出ている』。"
    + "window.modeToggleButton(isMultiply, onToggle, title) に統一すること。");
});

test("modeToggleButton が実際に使われている (定義だけ残って画面から消えていない)", () => {
  // 呼び出し箇所を数える (定義と window への公開は除く)。
  // 想定は item-stats の 加算/乗算 共通トグル 1 + セット効果 1 + スキルツリー 2 = 4。
  let calls = 0;
  for (const f of jsFiles()) {
    const src = readJs(f)
      .replace(/function\s+modeToggleButton\s*\(/g, "")
      .replace(/window\.modeToggleButton\s*=\s*modeToggleButton;/g, "");
    calls += (src.match(/\bmodeToggleButton\s*\(/g) || []).length;
  }
  assert.ok(calls >= 4,
    `modeToggleButton の呼び出しが ${calls} 箇所しかない `
    + "(item-stats 1 / セット効果 1 / スキルツリー 2 の計4を想定)。"
    + "減っているなら、その画面だけ別の部品へ戻っている。");
});

test("ステ選択を持つ行の値入力は valueCell に包まれている (列の縦線が揃う条件)", () => {
  const offenders = statRows()
    // 値を描かない行 (ステ名の付け替えだけ等) は対象外。
    .filter((row) => /\b(statValueControl|numberInput)\s*\(/.test(row.body))
    .filter((row) => !/\bvalueCell\s*\(/.test(row.body))
    .map((row) => `${row.file}:${row.line}`);
  assert.deepEqual(offenders, [],
    "ステ行の値入力が valueCell を通っていない: " + offenders.join(" / ")
    + "。valueCell を外すとセルの幅が中身(%サフィックス・単位の文字数)任せに戻り、"
    + "同じ表の中で『max』『乗算』『×』の縦線が行ごとにずれる(2026-08-22 の指摘そのもの)。");
});

test("この構造ガードは空振りしていない", () => {
  const rows = statRows().filter((row) => /\b(statValueControl|numberInput)\s*\(/.test(row.body));
  assert.ok(rows.length >= 8, `値を描くステ行を ${rows.length} 件しか見つけられていない (走査が壊れている)`);
});

test("値セルと入力欄の幅が CSS で px 固定 (auto に戻すと列がずれる)", () => {
  const css = fs.readFileSync(CSS, "utf8");

  function ruleBody(selector) {
    const i = css.indexOf(selector);
    assert.ok(i >= 0, `${selector} のルールが style.css に無い`);
    const open = css.indexOf("{", i);
    const close = css.indexOf("}", open);
    return css.slice(open + 1, close);
  }

  const cell = ruleBody(".stat-row > .value-cell");
  assert.match(cell, /flex:\s*0\s+0\s+\d+px/,
    ".stat-row > .value-cell の flex-basis が px 固定でない。"
    + "auto/内容依存に戻すと、単位の文字数のぶんだけ後続の列が動く = 縦線がずれる。");

  const input = ruleBody(".value-cell .field-input.num");
  assert.match(input, /width:\s*\d+px/,
    ".value-cell .field-input.num の width が px 固定でない。"
    + "入力枠そのものの幅が行ごとに変わると、枠の左右の線が揃わない。");
});
