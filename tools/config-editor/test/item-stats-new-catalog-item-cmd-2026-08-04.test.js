"use strict";

// 回帰: 「カタログで新しいアイテムを追加した後、ステータス設定欄でそのアイテムを追加しようとすると
// 登録していないのに『既にあるアイテムです』と言われて設定できない」(2026-08-04 実機報告)。
//
// 機構: item-stats.yml のキーは `MATERIAL#CMD`。forms.js の statsKeyFromCandidate は
// CMD が空だと素の Material をキーにするフォールバックを持つ。CMD は catalog.yml の
// custom-model-data から来るが、**新規追加したカタログ品はまだ未割当**(個別の「CMD自動割当」
// ボタンは廃止され、リソースパック管理の一括採番かテクスチャ登録時に付く)。
// その結果キーが素の DIAMOND_SWORD 等に退化し、item-stats.yml に元から在るバニラ用の
// 素 Material エントリと衝突して重複チェックに弾かれていた。
//
// 単体のアイテムを指すには CMD が必須(CMD 無しだとバニラの同素材全部にステが効く)なので、
// 選択時にその場で採番して catalog.yml へ保存する `window.cmdEnsureCatalogItemCmd` を入れた。
// このテストはその採番ヘルパの契約を固定する。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const ROOT = path.join(__dirname, "..");
const CMD_TOOLS = path.join(ROOT, "public", "js", "cmd-tools.js");

/**
 * cmd-tools.js を毎回クリーンに読み込み、fetch を差し替えて呼び出しを記録する。
 * @param {object} o
 * @param {object|null} o.catalogEntry  catalog.yml の items["new_sword"] として返す値 (null = 不在)
 * @param {boolean} o.confirmResult     window.confirm の戻り
 * @param {number}  o.allocCmd          /api/cmd/allocate が返す cmd
 * @param {boolean} o.putConflict       PUT を 409 conflict にするか
 */
function loadCmdTools(o) {
  const calls = [];
  const catalogData = { items: {} };
  if (o.catalogEntry) catalogData.items.new_sword = o.catalogEntry;

  global.fetch = async (url, opts) => {
    const method = (opts && opts.method) || "GET";
    const body = opts && opts.body ? JSON.parse(opts.body) : undefined;
    calls.push({ method, url, body });

    if (method === "GET" && url === "/api/config/catalog") {
      return { ok: true, status: 200, json: async () => ({ data: catalogData, revision: "rev-1" }) };
    }
    if (method === "POST" && url === "/api/cmd/allocate") {
      return { ok: true, status: 200, json: async () => ({ cmd: o.allocCmd }) };
    }
    if (method === "PUT" && url === "/api/config/catalog") {
      if (o.putConflict) {
        return { ok: false, status: 409, json: async () => ({ error: "conflict", conflict: true }) };
      }
      return { ok: true, status: 200, json: async () => ({ revision: "rev-2" }) };
    }
    return { ok: false, status: 404, json: async () => ({ error: "unexpected " + method + " " + url }) };
  };

  const notices = [];
  let confirmCount = 0;
  global.window = {
    h: () => ({}), // cmd-tools は IIFE 冒頭で window.h を読むだけ
    confirm: () => { confirmCount += 1; return o.confirmResult; },
    toast: (msg, kind) => notices.push({ msg, kind }),
    alert: (msg) => notices.push({ msg, kind: "alert" })
  };
  global.document = { createElement: () => ({}) };

  delete require.cache[require.resolve(CMD_TOOLS)];
  require(CMD_TOOLS);

  return {
    ensure: global.window.cmdEnsureCatalogItemCmd,
    calls,
    notices,
    catalogData,
    confirmCount: () => confirmCount
  };
}

const urlsOf = (calls) => calls.map((c) => c.method + " " + c.url);

test("CMD未割当のカタログ品を選ぶと、採番して catalog.yml へ保存し CMD を返す", async () => {
  const ctx = loadCmdTools({
    catalogEntry: { material: "DIAMOND_SWORD" }, // custom-model-data なし = 新規追加直後
    confirmResult: true,
    allocCmd: 1234
  });

  const cmd = await ctx.ensure({ id: "new_sword", material: "DIAMOND_SWORD" });

  assert.equal(cmd, 1234, "採番した CMD を返す");
  assert.equal(ctx.confirmCount(), 1, "台帳を消費するので確認を1回取る");
  assert.deepEqual(urlsOf(ctx.calls), [
    "GET /api/config/catalog",   // 既存CMDの確認
    "GET /api/config/catalog",   // 確認ダイアログ後に revision を取り直す
    "POST /api/cmd/allocate",
    "PUT /api/config/catalog"
  ]);
  // 修正の核心: catalog.yml 側にも CMD が書かれること。
  // ここを書かないと item-stats のキーだけ #1234 になり、実物のアイテムに一生マッチしない。
  assert.equal(ctx.catalogData.items.new_sword["custom-model-data"], 1234);
  const put = ctx.calls.find((c) => c.method === "PUT");
  assert.equal(put.body.expectedRevision, "rev-1", "楽観ロックのrevisionを送る");
});

test("既にCMDが割当済みなら、確認も採番もせずその値を返す", async () => {
  const ctx = loadCmdTools({
    catalogEntry: { material: "DIAMOND_SWORD", "custom-model-data": 77 },
    confirmResult: true,
    allocCmd: 999
  });

  const cmd = await ctx.ensure({ id: "new_sword", material: "DIAMOND_SWORD" });

  assert.equal(cmd, 77);
  assert.equal(ctx.confirmCount(), 0, "割当済みなら黙って使う(確認は出さない)");
  assert.deepEqual(urlsOf(ctx.calls), ["GET /api/config/catalog"]);
});

test("確認をキャンセルしたら台帳の番号を消費しない", async () => {
  const ctx = loadCmdTools({
    catalogEntry: { material: "DIAMOND_SWORD" },
    confirmResult: false,
    allocCmd: 1234
  });

  const cmd = await ctx.ensure({ id: "new_sword", material: "DIAMOND_SWORD" });

  assert.equal(cmd, null);
  assert.ok(!urlsOf(ctx.calls).includes("POST /api/cmd/allocate"), "allocate を呼ばない");
  assert.ok(!urlsOf(ctx.calls).includes("PUT /api/config/catalog"), "保存もしない");
});

test("catalog.yml の保存が競合したら null を返し、キーを変えさせない", async () => {
  const ctx = loadCmdTools({
    catalogEntry: { material: "DIAMOND_SWORD" },
    confirmResult: true,
    allocCmd: 1234,
    putConflict: true
  });

  const cmd = await ctx.ensure({ id: "new_sword", material: "DIAMOND_SWORD" });

  assert.equal(cmd, null, "保存できていないのに成功扱いにしない");
  assert.ok(
    ctx.notices.some((n) => n.kind === "error" && /再読込/.test(n.msg)),
    "再読込を促すエラーを出す"
  );
});

test("catalog.yml に無い出自(機能アイテム等)は採番せずエラーにする", async () => {
  const ctx = loadCmdTools({ catalogEntry: null, confirmResult: true, allocCmd: 1234 });

  const cmd = await ctx.ensure({ id: "new_sword", material: "DIAMOND_SWORD" });

  assert.equal(cmd, null);
  assert.equal(ctx.confirmCount(), 0);
  assert.ok(!urlsOf(ctx.calls).includes("POST /api/cmd/allocate"));
});

test("material や id が空なら採番しない", async () => {
  const ctx = loadCmdTools({ catalogEntry: { material: "DIAMOND_SWORD" }, confirmResult: true, allocCmd: 1 });

  assert.equal(await ctx.ensure({ id: "", material: "DIAMOND_SWORD" }), null);
  assert.equal(await ctx.ensure({ id: "new_sword", material: "" }), null);
  assert.deepEqual(ctx.calls, [], "APIを一度も叩かない");
});

// 前提の固定: 出荷 item-stats.yml に素 Material キーが在る限り、CMD 未割当の新規カタログ品は
// 必ずキー衝突を起こす。ここが 0 になったら上の採番フォールバックの前提が変わったことになる。
test("出荷 item-stats.yml は素Materialキーを持つ(衝突の前提)", () => {
  const p = path.join(ROOT, "..", "..", "TrinityForge", "src", "main", "resources", "stats", "item-stats.yml");
  const items = (YAML.parse(fs.readFileSync(p, "utf8")) || {}).items || {};
  const bare = Object.keys(items).filter((k) => !k.includes("#"));
  assert.ok(bare.length > 0, "素Materialキーが1件も無ければ衝突は起きないので前提の再確認が必要");
});
