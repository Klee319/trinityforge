"use strict";

// 2026-07-26: ソースに「生の制御文字」が混入していないことのガード。
//
// 発端: mob-forms.js の折りたたみ状態キーの区切りとして、**生の NUL バイト(U+0000)**が
// ソースへ直接書かれていた(`${scopeName}<NUL>${mobId}`)。JS としては合法で、テストもブラウザも
// 素通りするため誰も気付かなかったが、ripgrep/git/多くのエディタはファイルを **binary 判定**にして
// 中身を一切走査しなくなる。実際にこのファイルだけ grep が永久に空振りし、調査が丸ごと止まった。
//
// 挙動を変えずに直せる(`"\u0000"` のエスケープ表記にするだけ)にもかかわらず、症状が
// 「検索できない」という形でしか出ないため、機械的に弾く以外に再発を防ぐ手立てが無い。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");
const SCAN_DIRS = ["public/js", "public", "lib", "test"];

function collectSourceFiles() {
  const out = [];
  for (const rel of SCAN_DIRS) {
    const dir = path.join(ROOT, rel);
    if (!fs.existsSync(dir)) continue;
    for (const name of fs.readdirSync(dir)) {
      if (!/\.(js|json|css|html)$/.test(name)) continue;
      const p = path.join(dir, name);
      if (!fs.statSync(p).isFile()) continue;
      out.push(p);
    }
  }
  return out;
}

test("config-editor のソースに生の NUL バイトが無い(ripgrep/git が binary 判定してしまう)", () => {
  const offenders = [];
  for (const p of collectSourceFiles()) {
    const buf = fs.readFileSync(p);
    const at = buf.indexOf(0);
    if (at >= 0) {
      const line = buf.slice(0, at).toString("utf8").split("\n").length;
      offenders.push(`${path.relative(ROOT, p)}:${line}`);
    }
  }
  assert.deepEqual(offenders, [],
    "生の NUL バイトを含むソース(エスケープ表記 \\u0000 に置き換えること): " + offenders.join(", "));
});

test("config-editor のソースに想定外の制御文字が無い(TAB/LF/CR は許可)", () => {
  const offenders = [];
  for (const p of collectSourceFiles()) {
    const buf = fs.readFileSync(p);
    for (let i = 0; i < buf.length; i++) {
      const b = buf[i];
      // 0x09 TAB / 0x0A LF / 0x0D CR は正常。それ以外の C0 制御文字と DEL(0x7F) を弾く。
      if ((b < 0x20 && b !== 0x09 && b !== 0x0a && b !== 0x0d) || b === 0x7f) {
        const line = buf.slice(0, i).toString("utf8").split("\n").length;
        offenders.push(`${path.relative(ROOT, p)}:${line} (0x${b.toString(16).padStart(2, "0")})`);
        break; // 1ファイル1件まで報告すれば十分
      }
    }
  }
  assert.deepEqual(offenders, [], "想定外の制御文字を含むソース: " + offenders.join(", "));
});

// 2026-07-26: エディタが取りに行くURLは全て「ルート絶対」で書く規約のガード。
//
// 発端: em-dungeons.js だけが文書相対の "data/elitemobs-dungeons.json" を使っていた。相対パスは
// 「現在の文書のパス」を基準に解決されるため、パスセグメントを持つ画面から読むと
// /<現在の階層>/data/... を取りに行って静かに404になる(台帳ローダは失敗しても機能を落とさない
// 設計なので、候補が空になるだけで**エラーとして表面化しない**)。他の fetch は全て "/api/..." だった。
test("エディタの fetch 先URLはルート絶対で書く(文書相対だと現在のパス次第で静かに404になる)", () => {
  const offenders = [];
  const dir = path.join(ROOT, "public/js");
  for (const name of fs.readdirSync(dir)) {
    if (!name.endsWith(".js")) continue;
    const src = fs.readFileSync(path.join(dir, name), "utf8");
    // fetch("...") / fetch(`...`) に直接書かれたリテラル
    for (const m of src.matchAll(/fetch\(\s*["'`]([^"'`]*)["'`]/g)) {
      const url = m[1];
      if (!url.startsWith("/") && !/^https?:/.test(url)) offenders.push(`${name}: fetch("${url}")`);
    }
    // fetch に渡す目的で定数化されたURL(em-dungeons.js の const URL = "..." パターン)
    for (const m of src.matchAll(/const URL = ["']([^"']*)["']/g)) {
      const url = m[1];
      if (!url.startsWith("/") && !/^https?:/.test(url)) offenders.push(`${name}: const URL = "${url}"`);
    }
  }
  assert.deepEqual(offenders, [],
    "文書相対のURLが残っている(先頭に / を付けること): " + offenders.join(", "));
});

test("mob-forms.js: 折りたたみキーの区切りはエスケープ表記の定数として定義されている", () => {
  const src = fs.readFileSync(path.join(ROOT, "public/js/mob-forms.js"), "utf8");
  assert.match(src, /const OVERRIDE_MOB_KEY_SEP = "\\u0000";/,
    "区切り文字が定数 OVERRIDE_MOB_KEY_SEP としてエスケープ表記で定義されていない");
  assert.match(src, /function overrideMobKey\(scopeName, mobId\)/,
    "キー組み立てが overrideMobKey ヘルパーに一本化されていない");
});

test("mob-forms.js: スコープのリネーム/削除で折りたたみ状態のキーを移し替える", () => {
  const src = fs.readFileSync(path.join(ROOT, "public/js/mob-forms.js"), "utf8");
  assert.match(src, /function renameScopeUiState\(oldName, newName\)/,
    "renameScopeUiState が無い(リネーム後に旧キーが残り折りたたみ状態がリセットされる)");
  assert.match(src, /function forgetScopeUiState\(scopeName\)/,
    "forgetScopeUiState が無い(削除したスコープのキーが Set に残り続ける)");
  assert.match(src, /renameKey\(overrides, oldName, newName\);[\s\S]{0,300}renameScopeUiState\(oldName, newName\)/,
    "リネーム時に renameScopeUiState が呼ばれていない");
  assert.match(src, /delete overrides\[scopeName\]; forgetScopeUiState\(scopeName\)/,
    "スコープ削除時に forgetScopeUiState が呼ばれていない");
});
