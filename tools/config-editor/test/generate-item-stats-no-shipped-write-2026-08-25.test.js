"use strict";

// generate-item-stats.js は 2026-08-25 に「出荷 item-stats.yml を丸ごと上書きする」経路を撤去した。
// (背景: docs/agent-context/config-editor.md「editor を絶対優先」。--force ゲートは
//  確認を怠った瞬間に事故るため、上書き経路そのものを消した。)
// この回帰を守るのは次の2本立て:
//   1. 実際にスクリプトを子プロセスで実行し、出荷 yml のバイト列が1バイトも変わらないことを固定する
//      (動的テスト。旧仕様へ戻すと出荷 yml が書き換わるので確実に落ちる)。
//   2. スクリプトのソースに shippedPath (出荷パス) を writeFileSync の対象として含まないことを
//      静的に固定する (defense in depth。子プロセス実行がCI環境差で不安定になった場合の保険)。
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");
const { execFileSync } = require("node:child_process");
const YAML = require("yaml");

const repoRoot = path.resolve(__dirname, "..", "..", "..");
const scriptPath = path.join(__dirname, "..", "scripts", "generate-item-stats.js");
const shippedPath = path.join(repoRoot, "TrinityForge", "src", "main", "resources", "stats", "item-stats.yml");
const previewPath = path.join(repoRoot, "tmp", "generated", "item-stats.generated-preview.yml");

test("generate-item-stats.js のソースは出荷 item-stats.yml への writeFileSync を持たない (静的ガード)", () => {
  const src = fs.readFileSync(scriptPath, "utf8");
  // shippedPath (出荷パス変数) が writeFileSync の第一引数として使われていないことを固定する。
  assert.ok(
    !/writeFileSync\(\s*shippedPath/.test(src),
    "shippedPath への writeFileSync が復活している (出荷ymlの巻き戻し経路が再発した疑い)"
  );
  // 書き込み先は tmp/generated/ 配下の preview ファイルだけであること。
  assert.match(src, /writeFileSync\(\s*previewPath/);
});

test("generate-item-stats.js を実行しても出荷 item-stats.yml は1バイトも変わらない", () => {
  // 実行前のバイト列を退避する (このテストは repo の実ファイルに対して実行するが、
  // 期待される挙動は「一切触らない」なので、そもそも書き換わらないはず。念のため比較する)。
  const before = fs.readFileSync(shippedPath);

  let stdout;
  try {
    stdout = execFileSync(process.execPath, [scriptPath], {
      cwd: path.join(__dirname, ".."),
      encoding: "utf8",
      timeout: 30000
    });
  } catch (err) {
    assert.fail(`generate-item-stats.js の実行に失敗しました: ${err.stderr || err.message}`);
  }

  const after = fs.readFileSync(shippedPath);
  assert.ok(before.equals(after), "出荷 item-stats.yml のバイト列が変化した (巻き戻り経路が復活している)");

  // 出力メッセージが「書いていない」ことを明言していること (旧仕様の "wrote N item-stat profiles" の
  // ような「出荷 yml へ書いた」と読める文言に戻っていないこと)。
  assert.match(stdout, /出荷 item-stats\.yml には一切書き込んでいません/);

  // プレビュー先には生成結果が書かれていること (機能自体は生きていることを確認する)。
  assert.ok(fs.existsSync(previewPath), "tmp/generated/item-stats.generated-preview.yml が生成されていない");
  const previewRaw = fs.readFileSync(previewPath, "utf8");
  const previewData = YAML.parse(previewRaw);
  assert.ok(previewData && typeof previewData.items === "object", "プレビューに items が無い");
  assert.ok(Object.keys(previewData.items).length > 0, "プレビューの items が空");
});
