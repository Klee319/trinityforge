"use strict";

// server.js の PUT /api/config/:id は「読み込み時点の revision と現ファイルの内容ハッシュが
// 一致しないと保存できない」楽観的ロックを持つ。サーバはファイルの内容ハッシュ(sha256)しか
// 見ておらず、誰がその内容を書いたかは区別しない。つまりこの仕組みは
// 「config-editor 経由の同時編集」だけでなく「config-editor を経由しない外部プロセス
// (エージェントが yml 本体を直接 Edit/Write するケースを含む) がディスクを書き換えた後の保存」
// にも同様に効くはずだが、それを直接固定するテストがこれまで無かった。
//
// 2026-08-25 に実際に穴を1つ見つけて同時に塞いだ:
//   「エディタが『未作成』(exists:false, revision:null) として読み込んだ config を、
//    開いている間に外部プロセスが新規作成し、そのままエディタで保存する」経路だけ、
//   revision チェックが一律スキップされ、外部プロセスの内容を無言で上書きしていた
//   (server.js が expected===null を「チェックしない」扱いにしていたため。
//    修正は hasOwnProperty(body, "expectedRevision") で判定し、null も比較対象にする)。
//
// このテストは次の3本で、外部プロセスによる直接編集がサイレントロールバックされないことと、
// 楽観ロックに参加しない呼び出し(内部処理)の後方互換を両方固定する。
//   1. 既存ファイルを外部が書き換えた後の保存 -> 409、ディスクは1バイトも変わらない
//   2. 未作成として読み込んだ後に外部がファイルを新規作成した後の保存 -> 409 (今回のfix対象)
//   3. expectedRevision を一度も送らない呼び出しは従来どおり無条件で書き込める (後方互換)

const test = require("node:test");
const { after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");

const serverModule = require("../server");
const { app, __setToolConfigForTest } = serverModule;

// L-10: 一時ディレクトリは CWD 内 (test/.tmp/) に作る (os.tmpdir() は使わない、規約)。
const TMP_ROOT = path.join(__dirname, ".tmp", "optimistic-lock");
fs.mkdirSync(TMP_ROOT, { recursive: true });
function tmpDir() {
  return fs.mkdtempSync(path.join(TMP_ROOT, "case-"));
}
after(() => {
  fs.rmSync(TMP_ROOT, { recursive: true, force: true });
});

// registry の "gathering-efficiency" (schema: generic, base: trinityforge,
// rel: stats/gathering-efficiency.yml) を借りる。generic schema はオブジェクト/配列であれば
//何でも通るので、item-stats/tf-skilltree のようなクロスファイル検証に依存しない。
const CONFIG_ID = "gathering-efficiency";
const REL = "stats/gathering-efficiency.yml";

// 各テストごとに独立した一時ルートへ basePaths を差し替える。
// ⚠ deployPaths は必ず {} で上書きする。tool-config.json の実値は D:\game\...\Dev_Server の
// 実配備先を指しており、上書きを忘れると mirrorToDeploy がテスト用ファイルを実サーバの
// config フォルダへ書き込んでしまう。
function setupServer() {
  const base = tmpDir();
  __setToolConfigForTest({
    basePaths: { trinityforge: base },
    deployPaths: {},
    backupDir: path.join(base, "_backups"),
    backupKeep: 5
  });
  return { base, absPath: path.join(base, REL) };
}

function startServer() {
  return new Promise((resolve) => {
    const server = app.listen(0, "127.0.0.1", () => resolve(server));
  });
}

async function getConfig(baseUrl, id) {
  const res = await fetch(`${baseUrl}/api/config/${id}`);
  const json = await res.json();
  return { status: res.status, json };
}

async function putConfig(baseUrl, id, body) {
  const res = await fetch(`${baseUrl}/api/config/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  const json = await res.json();
  return { status: res.status, json };
}

test("既存ファイルを外部プロセスが直接編集した後にエディタが保存すると409で拒否され、外部編集は消えない", async () => {
  const { absPath } = setupServer();
  fs.mkdirSync(path.dirname(absPath), { recursive: true });
  fs.writeFileSync(absPath, "value: 1\n", "utf8");

  const server = await startServer();
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  try {
    // エディタが開いた時点の状態を取得する (revision を記憶する想定)。
    const loaded = await getConfig(baseUrl, CONFIG_ID);
    assert.equal(loaded.status, 200);
    assert.equal(loaded.json.data.value, 1);
    const loadedRevision = loaded.json.revision;
    assert.ok(loadedRevision, "既存ファイルの revision は非nullのはず");

    // ここでエージェント(config-editorを経由しない別プロセス)がファイルを直接書き換える。
    fs.writeFileSync(absPath, "value: 999\n# agent が直接編集\n", "utf8");
    const agentContent = fs.readFileSync(absPath, "utf8");

    // エディタは(agentの編集を知らないまま)古い revision で保存しようとする。
    const saved = await putConfig(baseUrl, CONFIG_ID, {
      data: { value: 2 },
      expectedRevision: loadedRevision
    });

    assert.equal(saved.status, 409, "外部編集後の古いrevisionでの保存は409で拒否されるべき");
    assert.equal(saved.json.conflict, true);
    // サーバが返す「最新」はエージェントの内容であること (エディタが持つ古いbaseではない)。
    assert.equal(saved.json.data.value, 999);

    // ディスクの中身がエージェントの編集のまま1バイトも変わっていないこと。
    assert.equal(fs.readFileSync(absPath, "utf8"), agentContent, "サイレントロールバックが発生した");
  } finally {
    server.close();
  }
});

test("『未作成』として読み込んだconfigを外部プロセスが新規作成した後にエディタが保存すると409で拒否される (2026-08-25修正)", async () => {
  const { absPath } = setupServer();
  // わざとファイルを作らない: エディタは「未作成」として読み込む。

  const server = await startServer();
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  try {
    const loaded = await getConfig(baseUrl, CONFIG_ID);
    assert.equal(loaded.status, 200);
    assert.equal(loaded.json.exists, false);
    assert.equal(loaded.json.revision, null, "未作成ファイルの revision は null のはず");

    // エージェントが同じ config を先に新規作成する (config-editorを経由しない直接編集)。
    fs.mkdirSync(path.dirname(absPath), { recursive: true });
    fs.writeFileSync(absPath, "value: 777\n# agent が新規作成\n", "utf8");
    const agentContent = fs.readFileSync(absPath, "utf8");

    // エディタ側は「未作成として読み込んだ」ことを表す expectedRevision: null を明示的に送る
    // (public/js/app.js の putConfig がこの形で送るようになった)。
    const saved = await putConfig(baseUrl, CONFIG_ID, {
      data: { value: 3 },
      expectedRevision: null
    });

    assert.equal(saved.status, 409,
      "外部プロセスが新規作成した後の『未作成』前提の保存は409で拒否されるべき (修正前は無条件で上書きしていた)");
    assert.equal(saved.json.conflict, true);
    assert.equal(saved.json.data.value, 777);

    // ディスクの中身がエージェントの新規作成のまま変わっていないこと (サイレントロールバックが無い)。
    assert.equal(fs.readFileSync(absPath, "utf8"), agentContent, "サイレントロールバックが発生した");
  } finally {
    server.close();
  }
});

test("expectedRevisionを一度も送らない呼び出し(楽観ロックに参加しないレガシー呼び出し)は従来どおり無条件で書き込める", async () => {
  const { absPath } = setupServer();
  fs.mkdirSync(path.dirname(absPath), { recursive: true });
  fs.writeFileSync(absPath, "value: 1\n", "utf8");

  const server = await startServer();
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  try {
    // expectedRevision キー自体を含めない。
    const saved = await putConfig(baseUrl, CONFIG_ID, { data: { value: 42 } });
    assert.equal(saved.status, 200, "expectedRevisionを送らない呼び出しは従来どおり成功するはず");
    assert.equal(fs.readFileSync(absPath, "utf8").includes("42"), true);
  } finally {
    server.close();
  }
});
