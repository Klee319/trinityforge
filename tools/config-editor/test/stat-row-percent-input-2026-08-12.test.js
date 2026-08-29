"use strict";

// ---------------------------------------------------------------------------
// %ステの入力欄が「0.03」と素の割合で出てしまう問題 (2026-08-12)
//
// エディタでステの数値を出す唯一の正しい部品は forms.js の `statValueControl`。
// これだけが `isPercentStat(key)` を見て「値×100 を表示し、÷100 して保存する」
// %入力(pct-input + pct-suffix "%") を作る。
//
// 素の `window.numberInput` を使うと 2 つが同時に壊れる:
//   1. 割合(0.03)がそのまま画面に出る (本来は 3)
//   2. 単位すら出ない。`statUnitSlot` は %ステに**空スロット**を返す仕様
//      (% は statValueControl の pct-suffix が出す前提)なので、
//      numberInput と組むと「小数なのに % も付かない」表示になる。
//
// 実際に踏んだのは スレッド画面の「セット効果 (thread-sets.yml)」行
// (forms.js renderThreadSetEffects)。回避率3% が `0.03`、会心率3% が `0.03` と
// 出ていた。forms.js の statValueControl 定義の直上には
// 「thread-sets フォーム等でも同じ %入力(割合保存) を再利用する」と既に書いてあり、
// 意図はあったのにこの1箇所だけ変換されていなかった。
//
// 固定するのは文字列ではなく構造:「ステ選択(statSelect)を持つ行が、値の入力に
// numberInput を直接使っていないこと」。倍率行(x1.2)はステの単位を持たない別物なので
// `mult-prefix` を含む行を免除する(免除は現場のコードで判別でき、別ファイルの
// 許可リストのように腐らない)。
// ---------------------------------------------------------------------------

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const JS_DIR = path.join(__dirname, "..", "public", "js");

// statSelect の呼び出し位置から「その1行分」とみなす範囲。
// 行の DOM 組み立ては長くても 900 文字程度に収まっている(次の statSelect が
// 先に来ればそこで打ち切る)。
const ROW_WINDOW = 900;

function statRows() {
  const rows = [];
  for (const file of fs.readdirSync(JS_DIR).filter((f) => f.endsWith(".js"))) {
    const src = fs.readFileSync(path.join(JS_DIR, file), "utf8");
    const re = /\bstatSelect\s*\(/g;
    let m;
    while ((m = re.exec(src))) {
      // 定義そのもの (function statSelect(...) / window.statSelect = statSelect) は行ではない
      const head = src.slice(Math.max(0, m.index - 20), m.index);
      if (/function\s+$/.test(head)) continue;
      const next = src.slice(m.index + 1).search(/\bstatSelect\s*\(/);
      const end = next < 0 ? m.index + ROW_WINDOW : Math.min(m.index + ROW_WINDOW, m.index + 1 + next);
      rows.push({
        file,
        line: src.slice(0, m.index).split("\n").length,
        body: src.slice(m.index, end)
      });
    }
  }
  return rows;
}

// 関数宣言の開始位置から、波括弧の対応を数えて本体だけを切り出す。
// 文字列・コメント内の波括弧は数えない(このファイル群は素の JS で、そこまで凝った
// リテラルは出てこないが、`"{"` のような1文字リテラルは実在するので除外する)。
function functionBody(src, startIdx) {
  const open = src.indexOf("{", startIdx);
  if (open < 0) return src.slice(startIdx);
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    if (c === '"' || c === "'" || c === "`") {
      const quote = c;
      i++;
      while (i < src.length && src[i] !== quote) i += src[i] === "\\" ? 2 : 1;
      continue;
    }
    if (c === "/" && src[i + 1] === "/") { i = src.indexOf("\n", i); if (i < 0) break; continue; }
    if (c === "/" && src[i + 1] === "*") { i = src.indexOf("*/", i) + 1; if (i < 1) break; continue; }
    if (c === "{") depth++;
    else if (c === "}" && --depth === 0) return src.slice(startIdx, i + 1);
  }
  return src.slice(startIdx);
}

test("ステ選択を持つ行の値入力は statValueControl を通している(素の numberInput は %表示を壊す)", () => {
  const offenders = statRows()
    .filter((row) => /\bnumberInput\s*\(/.test(row.body))
    .filter((row) => !/\bstatValueControl\s*\(/.test(row.body))
    // 倍率行 (x1.2) はステの単位も % も持たない別物なので対象外。
    .filter((row) => !row.body.includes("mult-prefix"))
    .map((row) => `${row.file}:${row.line}`);
  assert.deepEqual(offenders, [],
    "ステの値入力に window.numberInput を直接使っている行がある。"
    + "%ステ(dodge-chance 等)が割合のまま(0.03)表示され、statUnitSlot も %ステには"
    + "空スロットを返すので単位すら出ない。window.statValueControl(key, value, setter) を使うこと");
});

test("この構造ガードは空振りしていない(ステ行自体を十分な件数見ている)", () => {
  const rows = statRows();
  assert.ok(rows.length >= 8, `statSelect を使う行を ${rows.length} 件しか見つけられていない(走査が壊れている)`);
  assert.ok(rows.some((r) => /\bstatValueControl\s*\(/.test(r.body)),
    "statValueControl を使う行を1件も検出できていない = 切り出し範囲が短すぎる");
  assert.ok(rows.some((r) => r.body.includes("mult-prefix")),
    "倍率行を1件も検出できていない = 免除条件の読み取りが壊れている");
});

test("セット効果 (thread-sets.yml) の行が %入力になっている", () => {
  const src = fs.readFileSync(path.join(JS_DIR, "forms.js"), "utf8");
  const idx = src.indexOf("function renderThreadSetEffects");
  assert.ok(idx > 0, "renderThreadSetEffects が見つからない(画面構成が変わったらこのテストも直すこと)");
  // 2026-08-21: 固定長(4000文字)で切っていたが、乗算モードの追加で関数が伸びて
  // statValueControl の呼び出しが窓の外へ出た = 実装は正しいのにテストだけ落ちた。
  // 窓は【波括弧の対応を数えて関数の終わりまで】にして、関数が伸びても空振りしない
  // かつ隣の関数を巻き込まないようにする。
  const body = functionBody(src, idx);
  assert.ok(body.includes("window.statValueControl("),
    "セット効果のステ行が statValueControl を通っていない。"
    + "回避率3%が 0.03 と表示された 2026-08-12 のバグに戻っている");
});
