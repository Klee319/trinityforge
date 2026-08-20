"use strict";

// 2026-07-27: registry.js の「登録漏れ」検知テスト。
//
// 発端: afk.yml (AFK判定機構・12キー、実装済みの現役機構) が registry.js に一切登録されておらず、
// editor から永久に触れない設定になっていた。registry は「編集対象config の論理定義」であり、
// tool-config.json の basePaths 配下に新しい yml を足しても、ここへ登録し忘れると
// サーバは常に registry の rel のみを解決する設計(パストラバーサル対策)のため、
// 無言で「404 になる/一覧に出ない」状態が発生する。誰にも気付かれずに放置され続ける。
//
// このテストは:
//   1. basePaths 配下の実在する *.yml を全列挙する
//   2. registry.js の { base, rel } でカバーされているか照合する
//   3. カバーされていないものは「明示的な許可リスト」に無ければ失敗させる
//   4. 逆方向: registry にあるのに実ファイルが無いエントリも失敗させる(typo/削除の検知)
//
// 許可リストは「本当に config-editor で編集すべきでない」ものだけを載せる想定であり、
// 新規 config を足すたびに広げるものではない(広げて良いのは「これは編集対象ではない」と
// 明確に説明できる場合のみ)。減る一方であるべきで、増える方向のPRはレビューで疑うこと。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");
const toolConfig = JSON.parse(fs.readFileSync(path.join(ROOT, "tool-config.json"), "utf8"));
const { REGISTRY } = require("../lib/registry");
// lib/constants.js は combat/damage.yml と progression/combat-level.yml を registry.js とは別枠の
// CONSTANT_SOURCES(base+rel)で編集対象として登録している(「共通変数(戦闘定数)」ビュー)。
// この2ファイルは registry.js には出てこないが editor から普通に編集できるため、
// 「本当に編集不可能」を検知したいこのテストでは正当なカバレッジ源として扱う。
const { CONSTANT_SOURCES } = require("../lib/constants");

// 許可リスト: basePaths 配下に実在するが registry 未登録で構わない yml。
// 各エントリは "<baseキー>:<rel (POSIX区切り)>" 形式。
// 減る一方であるべき(新規config追加時にここへ足すのは原則NG。editorで編集すべきなのに
// 単に登録し忘れただけ、というケースを隠してしまうため)。
const ALLOWLIST = new Set([
  // プラグイン記述子であって config ではない(両リポジトリ)。
  "trinityforge:paper-plugin.yml",
  "arspaper:paper-plugin.yml",
  // ファイル冒頭に DEPRECATED と明記済み。正は combat/mob-types.yml の defaults: であり、
  // 二重管理を避けるため意図的に editor 非対応のまま残している。
  "trinityforge:combat/mob-defaults.yml",
  // 「空を維持」が前提の緊急上書き用ファイル。正本は TF スキルツリー yml のノード内
  // dedicated-effects: フィールドであり、通常運用でこのファイルを editor から触ることは想定していない。
  "arspaper:usage-gate.yml",
  "arspaper:unlock-gate.yml",
]);

function listYmlFiles(dir) {
  const out = [];
  const stack = [dir];
  while (stack.length > 0) {
    const current = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(current, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) {
        stack.push(full);
      } else if (entry.isFile() && entry.name.endsWith(".yml")) {
        out.push(full);
      }
    }
  }
  return out;
}

function toPosixRel(baseDir, absPath) {
  return path.relative(baseDir, absPath).split(path.sep).join("/");
}

test("basePaths 配下の全 yml が registry に登録されているか、明示的な許可リストに載っている", () => {
  const registeredKeys = new Set(REGISTRY.map((entry) => `${entry.base}:${entry.rel}`));
  for (const source of Object.values(CONSTANT_SOURCES)) {
    registeredKeys.add(`${source.base}:${source.rel}`);
  }
  const missing = [];

  for (const [baseKey, relBaseDir] of Object.entries(toolConfig.basePaths)) {
    const baseDir = path.resolve(ROOT, relBaseDir);
    assert.ok(fs.existsSync(baseDir), `basePaths.${baseKey} が存在しない: ${baseDir}`);

    for (const absPath of listYmlFiles(baseDir)) {
      const rel = toPosixRel(baseDir, absPath);
      const key = `${baseKey}:${rel}`;
      if (registeredKeys.has(key)) continue;
      if (ALLOWLIST.has(key)) continue;
      missing.push(key);
    }
  }

  assert.deepEqual(missing, [],
    "registry.js に未登録かつ許可リストにも無い yml が見つかった(editor から永久に触れない設定になっている " +
    "可能性がある。登録するかどうかは設計判断のため、勝手に registry へ追加せず報告すること): " +
    missing.join(", "));
});

test("許可リストの各エントリは実際に basePaths 配下に存在する(死んだ許可リスト行の検知)", () => {
  const stale = [];
  for (const key of ALLOWLIST) {
    const [baseKey, ...relParts] = key.split(":");
    const rel = relParts.join(":");
    const baseDir = path.resolve(ROOT, toolConfig.basePaths[baseKey]);
    const absPath = path.join(baseDir, ...rel.split("/"));
    if (!fs.existsSync(absPath)) stale.push(key);
  }
  assert.deepEqual(stale, [],
    "許可リストに載っているが実ファイルが無いエントリ(ファイル移動/削除で不要になった行を掃除すること): " +
    stale.join(", "));
});

test("registry に登録されているエントリは全て実ファイルが存在する(パスtypo/削除の検知)", () => {
  const missingFiles = [];
  for (const entry of REGISTRY) {
    const baseDir = toolConfig.basePaths[entry.base];
    assert.ok(baseDir, `registry entry "${entry.id}" の base "${entry.base}" が tool-config.json に無い`);
    const absPath = path.resolve(ROOT, baseDir, ...entry.rel.split("/"));
    if (!fs.existsSync(absPath)) {
      missingFiles.push(`${entry.id} (${entry.base}:${entry.rel})`);
    }
  }
  assert.deepEqual(missingFiles, [],
    "registry に登録されているのに実ファイルが存在しないエントリがある(editor が 404 を返す): " +
    missingFiles.join(", "));
});
