"use strict";

// public/js/tf-phase3-forms.js の buildArsConfigForm 回帰テスト。
//
// 背景(D修正, 2026-07-25): 以前は `const formOptions = [];` の宣言が renderCd()/loadFormOptions()
// の呼び出しより後にあり、const の TDZ (Temporal Dead Zone) により buildArsConfigForm 実行時に
// 必ず ReferenceError が発生していた。結果、ArsPaper全体設定ページ(form-cooldowns / mana / geyser /
// enchantments / mob-drops / loot の全カード)が1件も描画されなかった。
//
// 1) 静的チェック: ソース中で formOptions の宣言(1回目の出現)が、renderCd()/loadFormOptions() の
//    呼び出し(いずれの出現よりも前)にあることを検証する。
// 2) 実行チェック: window.h 等を最小スタブし、buildArsConfigForm(空データ)を実際に呼び出して
//    ReferenceError なく完走し、4カード(form-cooldowns/mana/enchantments/mob-drops)
//    がすべて描画されることを確認する(drop-table-logic.test.js / mob-forms-logic.test.js と同じ
//    「window.h を最小スタブしてブラウザ用IIFEをNodeでrequireする」手法)。
//    geyser カードは 2026-07-27 に撤去した(CustomModelDataを常時付与へ固定し、設定という
//    逃げ道自体を無くしたため。既定値 false = 付与する、なので実挙動は変わらない)。
//    loot カードは 2026-07-31 に撤去した(K-21。下の「旧 loot.* キー」テスト参照)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const SRC_PATH = path.join(__dirname, "..", "public", "js", "tf-phase3-forms.js");

test("静的チェック: formOptions の宣言は renderCd/loadFormOptions の呼び出しより前にある", () => {
  const src = fs.readFileSync(SRC_PATH, "utf8");
  const fnStart = src.indexOf("window.buildArsConfigForm = function buildArsConfigForm");
  assert.ok(fnStart >= 0, "buildArsConfigForm が見つかりません");
  const fnEnd = src.indexOf("\n  window.buildBanForm", fnStart);
  assert.ok(fnEnd > fnStart, "buildArsConfigForm の終端(次の関数)が見つかりません");
  const fnBody = src.slice(fnStart, fnEnd);

  const declIdx = fnBody.indexOf("const formOptions = []");
  assert.ok(declIdx >= 0, "const formOptions = []; の宣言が見つかりません");

  // renderCd()/loadFormOptions() の「呼び出し」(`function renderCd() {` 等の定義行は除く)全ての
  // 出現位置を集め、宣言より前に来るものが無いことを検証する。
  const callRe = /\b(renderCd|loadFormOptions)\(\)/g;
  let m;
  let sawAnyCall = false;
  while ((m = callRe.exec(fnBody))) {
    const precedingText = fnBody.slice(Math.max(0, m.index - 10), m.index);
    if (/function\s+$/.test(precedingText)) continue; // 関数定義行はスキップ(呼び出しではない)
    sawAnyCall = true;
    assert.ok(
      declIdx < m.index,
      `formOptions の宣言(index=${declIdx})が呼び出し "${m[0]}"(index=${m.index})より後ろにあります`
    );
  }
  assert.ok(sawAnyCall, "renderCd()/loadFormOptions() の呼び出しが見つかりません(テスト前提が崩れています)");
});

test("実行チェック: buildArsConfigForm が ReferenceError なく完走し全カードを描画する", () => {
  // drop-table-logic.test.js / mob-forms-logic.test.js と同じ最小スタブ手法。
  function makeEl(tag, props) {
    const el = {
      tag,
      props: props || {},
      children: [],
      appendChild(c) { this.children.push(c); return c; },
      set innerHTML(_v) { this.children = []; },
      get innerHTML() { return ""; }
    };
    return el;
  }

  global.window = global.window || {};
  global.window.h = (tag, props, children) => {
    const el = makeEl(tag, props);
    if (Array.isArray(children)) children.forEach((c) => c != null && el.appendChild(c));
    else if (children != null) el.appendChild(children);
    return el;
  };
  global.window.fieldLabelEl = (key, opts) => makeEl("label", { key, opts });
  global.window.listSelect = () => makeEl("div", { class: "list-select" });
  global.window.numberInput = () => makeEl("input", { class: "num" });
  global.window.checkboxInput = () => makeEl("input", { class: "checkbox" });
  global.window.textInput = () => makeEl("input", { class: "text" });
  global.window.textInputOnCommit = global.window.textInput;
  // fetch 未定義でも loadFormOptions() 内の try/catch が握りつぶすため無害(D修正の前提通り)。

  delete require.cache[require.resolve("../public/js/tf-phase3-forms.js")];
  require("../public/js/tf-phase3-forms.js");

  let result;
  assert.doesNotThrow(() => {
    result = global.window.buildArsConfigForm({});
  }, "buildArsConfigForm が例外(ReferenceError等)を投げずに完走すること");

  assert.ok(result && result.element, "戻り値に element が含まれること");
  // banner + 4カード(form-cooldowns / mana / enchantments / mob-drops) = 5要素以上。
  // (geyser カードは 2026-07-27 に撤去。CMD常時付与へ固定したため設定という逃げ道が不要になった。)
  assert.ok(result.element.children.length >= 5,
    `カードが描画されていません(children=${result.element.children.length})`);
});

// ============================================================
// K-21 (2026-07-31): 旧 `loot.*` キーが editor に残っていた件の回帰テスト。
//
// ルートチェストの追加抽選は loot-tables.yml へ移っており、fork の config.yml は
// `loot:` ブロックをもう読まない。にもかかわらず「ArsPaper 全体設定」画面に旧3キー
// (enabled / enchant-book-chance / enchanted-golden-apple-chance)が残っていたため、
//   - 「ルートチェストON」を off にしても追加抽選は止まらない
//   - 出現率を変えても何も変わらない
//   - `ensureObj(working, "loot")` のせいで **ars-config を保存するだけで**
//     config.yml に読まれない `loot:` ブロックが復活する
// という3つの症状が出ていた。撤去したうえで、往復ロスレス(既存の loot: を勝手に消さない)
// も同時に固定する。
// ============================================================
test("旧 loot.* キー: 画面から撤去され、開いて保存しただけでは loot: が生えない", () => {
  // コメントには経緯として旧キー名が残るので、コードの構文で判定する。
  const src = fs.readFileSync(SRC_PATH, "utf8");
  assert.ok(!/ensureObj\(working,\s*"loot"\)/.test(src),
    "ensureObj(working, \"loot\") が復活している(保存するだけで死にブロックが書き戻される)");
  assert.ok(!/\bconst\s+loot\s*=/.test(src), "loot ホストの取得が復活している");
  assert.ok(!/(bool|num)Field\(loot\s*,/.test(src), "loot.* を編集する欄が復活している");

  function makeEl(tag, props) {
    const el = {
      tag, props: props || {}, children: [],
      appendChild(c) { this.children.push(c); return c; },
      set innerHTML(_v) { this.children = []; },
      get innerHTML() { return ""; }
    };
    return el;
  }
  global.window = global.window || {};
  global.window.h = (tag, props, children) => {
    const el = makeEl(tag, props);
    if (Array.isArray(children)) children.forEach((c) => c != null && el.appendChild(c));
    else if (children != null) el.appendChild(children);
    return el;
  };
  global.window.fieldLabelEl = (key, opts) => makeEl("label", { key, opts });
  global.window.listSelect = () => makeEl("div", { class: "list-select" });
  global.window.numberInput = () => makeEl("input", { class: "num" });
  global.window.checkboxInput = () => makeEl("input", { class: "checkbox" });
  global.window.textInput = () => makeEl("input", { class: "text" });
  global.window.textInputOnCommit = global.window.textInput;

  delete require.cache[require.resolve("../public/js/tf-phase3-forms.js")];
  require("../public/js/tf-phase3-forms.js");

  // 空の config を開いて保存しても loot: は生えない。
  const fresh = global.window.buildArsConfigForm({}).getData();
  assert.ok(!Object.prototype.hasOwnProperty.call(fresh, "loot"),
    "開いて保存しただけで loot: ブロックが生えている");

  // 既存ファイルに残っている loot: は勝手に消さない(往復ロスレス)。
  const existing = { loot: { enabled: true, "enchant-book-chance": 0.25 } };
  const kept = global.window.buildArsConfigForm(existing).getData();
  assert.deepEqual(kept.loot, { enabled: true, "enchant-book-chance": 0.25 },
    "既存の loot: を勝手に書き換え/削除している");
});
