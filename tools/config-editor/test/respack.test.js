"use strict";

const test = require("node:test");
const { after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const zlib = require("zlib");

const Respack = require("../lib/respack");
const CmdRegistry = require("../lib/cmd-registry");
const { buildZip, crc32 } = require("../lib/zipbuild");
const VANILLA_DEFS = require("../lib/vanilla-item-defs-1.21.11.json");
const DEFS = VANILLA_DEFS.defs;

// テスト用に任意サイズのPNG(8bit RGBA, 単色)を自前生成する(依存パッケージなし)。
// respack.jsのIHDR自前パース(readPngDimensions)の検証に、実際にwidth/heightが違うPNGが要る。
function buildTestPng(width, height) {
  function chunk(type, data) {
    const typeBuf = Buffer.from(type, "ascii");
    const lenBuf = Buffer.alloc(4);
    lenBuf.writeUInt32BE(data.length, 0);
    const crcBuf = Buffer.alloc(4);
    crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])) >>> 0, 0);
    return Buffer.concat([lenBuf, typeBuf, data, crcBuf]);
  }
  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdrData = Buffer.alloc(13);
  ihdrData.writeUInt32BE(width, 0);
  ihdrData.writeUInt32BE(height, 4);
  ihdrData[8] = 8; // bit depth
  ihdrData[9] = 6; // color type: RGBA
  ihdrData[10] = 0; // compression
  ihdrData[11] = 0; // filter
  ihdrData[12] = 0; // interlace
  const ihdr = chunk("IHDR", ihdrData);

  const rowBytes = width * 4;
  const raw = Buffer.alloc((rowBytes + 1) * height);
  for (let y = 0; y < height; y++) {
    const rowStart = y * (rowBytes + 1);
    raw[rowStart] = 0; // filter type: none
    for (let x = 0; x < width; x++) {
      const off = rowStart + 1 + x * 4;
      raw[off] = 200; raw[off + 1] = 100; raw[off + 2] = 50; raw[off + 3] = 255;
    }
  }
  const idat = chunk("IDAT", zlib.deflateSync(raw));
  const iend = chunk("IEND", Buffer.alloc(0));
  return Buffer.concat([signature, ihdr, idat, iend]);
}

// L-10: 一時ディレクトリは CWD 内 (test/.tmp/) に作る (os.tmpdir() は使わない、規約)。
const TMP_ROOT = path.join(__dirname, ".tmp", "respack");
fs.mkdirSync(TMP_ROOT, { recursive: true });
function tmpDir() {
  return fs.mkdtempSync(path.join(TMP_ROOT, "case-"));
}
after(() => {
  fs.rmSync(TMP_ROOT, { recursive: true, force: true });
});

// 1x1 の最小PNG (透明ピクセル)
const MIN_PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64"
);

function registryPathOf(dir) {
  return path.join(dir, "cmd-registry.json");
}

test("zipbuild: 同一入力なら2回のビルドでSHA1が一致する", () => {
  const dir = tmpDir();
  const entries = [
    { path: "a/b.txt", data: Buffer.from("hello") },
    { path: "a.txt", data: Buffer.from("world") }
  ];
  const out1 = path.join(dir, "one.zip");
  const out2 = path.join(dir, "two.zip");
  buildZip(entries, out1);
  buildZip(entries.slice().reverse(), out2); // 入力順序を変えても結果は同じ (パスでソートされるため)
  const sha1 = (p) => crypto.createHash("sha1").update(fs.readFileSync(p)).digest("hex");
  assert.equal(sha1(out1), sha1(out2));
});

test("zipbuild: 生成したzipはzlibで展開可能な central directory を持つ", () => {
  const dir = tmpDir();
  const out = path.join(dir, "check.zip");
  buildZip([{ path: "hello.txt", data: Buffer.from("hi there") }], out);
  const buf = fs.readFileSync(out);
  // local file header signature
  assert.equal(buf.readUInt32LE(0), 0x04034b50);
  // EOCD signature は末尾22バイト以内にあるはず (コメント無しなので末尾ちょうど)
  const eocdSig = buf.readUInt32LE(buf.length - 22);
  assert.equal(eocdSig, 0x06054b50);
});

test("regenerateItemDefinitions: entries は cmd 昇順で並ぶ", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  Respack.writeTexture({ material: "IRON_INGOT", cmd: 20, id: "second", pngBuffer: MIN_PNG }, dir, regPath);
  Respack.writeTexture({ material: "IRON_INGOT", cmd: 10, id: "first", pngBuffer: MIN_PNG }, dir, regPath);
  const defPath = path.join(dir, "trinityforge-items", "assets", "minecraft", "items", "iron_ingot.json");
  const def = JSON.parse(fs.readFileSync(defPath, "utf8"));
  assert.equal(def.model.type, "minecraft:range_dispatch");
  assert.equal(def.model.entries.length, 2);
  assert.equal(def.model.entries[0].threshold, 10);
  assert.equal(def.model.entries[1].threshold, 20);
});

test("regenerateItemDefinitions: 単純materialのfallbackはデータファイルのmodelと完全一致する", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  Respack.writeTexture({ material: "IRON_INGOT", cmd: 1, id: "x", pngBuffer: MIN_PNG }, dir, regPath);
  const defPath = path.join(dir, "trinityforge-items", "assets", "minecraft", "items", "iron_ingot.json");
  const def = JSON.parse(fs.readFileSync(defPath, "utf8"));
  assert.deepEqual(def.model.fallback, DEFS.IRON_INGOT.model);
});

test("regenerateItemDefinitions: LEATHER_HELMET のfallbackはデータファイルのtrim select定義と完全一致する", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  Respack.writeTexture({ material: "LEATHER_HELMET", cmd: 1, id: "x", pngBuffer: MIN_PNG }, dir, regPath);
  const defPath = path.join(dir, "trinityforge-items", "assets", "minecraft", "items", "leather_helmet.json");
  const def = JSON.parse(fs.readFileSync(defPath, "utf8"));
  assert.deepEqual(def.model.fallback, DEFS.LEATHER_HELMET.model);
});

test("regenerateItemDefinitions: BOW のfallbackはデータファイルのusing_item条件分岐と完全一致する", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  Respack.writeTexture({ material: "BOW", cmd: 1, id: "x", pngBuffer: MIN_PNG, parent: "handheld" }, dir, regPath);
  const defPath = path.join(dir, "trinityforge-items", "assets", "minecraft", "items", "bow.json");
  const def = JSON.parse(fs.readFileSync(defPath, "utf8"));
  assert.deepEqual(def.model.fallback, DEFS.BOW.model);
});

test("regenerateItemDefinitions: PLAYER_HEAD のfallbackはデータファイルのspecial定義と完全一致する", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  Respack.writeTexture({ material: "PLAYER_HEAD", cmd: 1, id: "x", pngBuffer: MIN_PNG }, dir, regPath);
  const defPath = path.join(dir, "trinityforge-items", "assets", "minecraft", "items", "player_head.json");
  const def = JSON.parse(fs.readFileSync(defPath, "utf8"));
  assert.deepEqual(def.model.fallback, DEFS.PLAYER_HEAD.model);
});

test("regenerateItemDefinitions: WOODEN_SPEAR は swap_animation_scale がトップレベルに保持される", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  Respack.writeTexture({ material: "WOODEN_SPEAR", cmd: 1, id: "x", pngBuffer: MIN_PNG, parent: "handheld" }, dir, regPath);
  const defPath = path.join(dir, "trinityforge-items", "assets", "minecraft", "items", "wooden_spear.json");
  const def = JSON.parse(fs.readFileSync(defPath, "utf8"));
  assert.equal(def.swap_animation_scale, 1.95);
  assert.deepEqual(def.model.fallback, DEFS.WOODEN_SPEAR.model);
});

test("writeTexture: DECORATED_POT は登録可能である(旧UNSUPPORTED拒否は解除済み)", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const result = Respack.writeTexture({ material: "DECORATED_POT", cmd: 1, id: "x", pngBuffer: MIN_PNG }, dir, regPath);
  assert.ok(result.ok);
  const defPath = path.join(dir, "trinityforge-items", "assets", "minecraft", "items", "decorated_pot.json");
  const def = JSON.parse(fs.readFileSync(defPath, "utf8"));
  assert.deepEqual(def.model.fallback, DEFS.DECORATED_POT.model);
});

test("H-3: 未登録cmd(テクスチャ無し)の行にもfallbackモデルのentryが生成される(フォールスルー防止)", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  // cmd=10 はテクスチャ登録済み。cmd=20 は台帳にのみ存在しテクスチャ未登録 (allocateのみ相当)。
  Respack.writeTexture({ material: "IRON_INGOT", cmd: 10, id: "textured", pngBuffer: MIN_PNG }, dir, regPath);
  const registry = CmdRegistry.loadRegistry(regPath);
  const nextRegistry = {
    version: registry.version,
    allocations: [...registry.allocations, { material: "IRON_INGOT", cmd: 20, id: "no_texture", source: "catalog", allocatedAt: new Date().toISOString() }]
  };
  CmdRegistry.saveRegistry(regPath, nextRegistry);
  Respack.regenerateItemDefinitions(dir, regPath);

  const defPath = path.join(dir, "trinityforge-items", "assets", "minecraft", "items", "iron_ingot.json");
  const def = JSON.parse(fs.readFileSync(defPath, "utf8"));
  assert.equal(def.model.entries.length, 2, "テクスチャ未登録の行も省略されずentryが生成される");
  const textured = def.model.entries.find((e) => e.threshold === 10);
  const untextured = def.model.entries.find((e) => e.threshold === 20);
  assert.equal(textured.model.model, "trinityforge:item/textured");
  // フォールスルー防止: 未登録cmdのmodelはtrinityforgeアセットではなくバニラfallbackと同一。
  assert.deepEqual(untextured.model, def.model.fallback);
  assert.notEqual(untextured.model.model, "trinityforge:item/textured");
});

test("M-1: assetName衝突は _<cmd> でも足りなければ連番でユニーク化し、既存PNGを上書きしない", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const texDir = path.join(dir, "trinityforge-items", "assets", "trinityforge", "textures", "item");
  fs.mkdirSync(texDir, { recursive: true });

  // "dup" と "dup_5" の2つのassetNameを、別(material,cmd)によって先取りしておく (擬似衝突)。
  const guardMarker = Buffer.from("GUARD-DO-NOT-OVERWRITE");
  fs.writeFileSync(path.join(texDir, "dup.png"), guardMarker);
  fs.writeFileSync(path.join(texDir, "dup_5.png"), guardMarker);
  CmdRegistry.saveRegistry(regPath, {
    version: 1,
    allocations: [
      { material: "GOLD_INGOT", cmd: 99, id: "guard1", source: "test", assetName: "dup", parent: "generated", allocatedAt: new Date().toISOString() },
      { material: "GOLD_INGOT", cmd: 100, id: "guard2", source: "test", assetName: "dup_5", parent: "generated", allocatedAt: new Date().toISOString() }
    ]
  });

  // IRON_INGOT#5 を id="dup" で登録 → base "dup" 衝突 → "dup_5" 衝突 → "dup_5_2" まで採番されるはず。
  const result = Respack.writeTexture({ material: "IRON_INGOT", cmd: 5, id: "dup", pngBuffer: MIN_PNG }, dir, regPath);
  assert.equal(result.assetName, "dup_5_2");

  // 既存の2つのPNGが上書きされていないことを確認する。
  assert.deepEqual(fs.readFileSync(path.join(texDir, "dup.png")), guardMarker);
  assert.deepEqual(fs.readFileSync(path.join(texDir, "dup_5.png")), guardMarker);
});

test("writeTexture: item定義データが無いmaterial(ブロック専用)は「アイテム形態を持たない」エラーで拒否される", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  assert.ok(!DEFS.ACACIA_WALL_SIGN, "前提: ACACIA_WALL_SIGN はdefsに存在しないブロック専用material");
  assert.throws(
    () => Respack.writeTexture({ material: "ACACIA_WALL_SIGN", cmd: 1, id: "x", pngBuffer: MIN_PNG }, dir, regPath),
    /アイテム形態を持たないためテクスチャ登録できません/
  );
});

test("L-2: 実在しないmaterialは拒否される", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  assert.throws(
    () => Respack.writeTexture({ material: "NOT_A_REAL_MATERIAL", cmd: 1, id: "x", pngBuffer: MIN_PNG }, dir, regPath),
    /実在しないMaterial/
  );
});

test("H-2: 破損した台帳に対する writeTexture はエラーを投げ、.bakを上書きしない", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(regPath, "{ this is not valid json");
  fs.writeFileSync(`${regPath}.bak`, JSON.stringify({ version: 1, allocations: [{ material: "OLD", cmd: 1 }] }));
  const bakBefore = fs.readFileSync(`${regPath}.bak`, "utf8");

  assert.throws(
    () => Respack.writeTexture({ material: "IRON_INGOT", cmd: 1, id: "x", pngBuffer: MIN_PNG }, dir, regPath),
    /破損しています/
  );

  const bakAfter = fs.readFileSync(`${regPath}.bak`, "utf8");
  assert.equal(bakAfter, bakBefore, ".bak は書き換えられない (正常な.bakを破壊しない)");
});

test("H-2: status() は破損台帳でも例外を投げず registryCorrupt:true を返す", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(regPath, "not json at all");
  const st = Respack.status(dir, regPath);
  assert.equal(st.registryCorrupt, true);
  assert.deepEqual(st.allocations, []);
});

test("writeTexture: PNGマジックバイト不一致は拒否される", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  assert.throws(
    () => Respack.writeTexture({ material: "IRON_INGOT", cmd: 1, id: "x", pngBuffer: Buffer.from("not a png") }, dir, regPath)
  );
});

test("regenerateItemDefinitions: 配線(モデルJSON)が消えたmaterialの定義ファイルは削除される", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  Respack.writeTexture({ material: "IRON_INGOT", cmd: 1, id: "x", pngBuffer: MIN_PNG }, dir, regPath);
  const defPath = path.join(dir, "trinityforge-items", "assets", "minecraft", "items", "iron_ingot.json");
  assert.ok(fs.existsSync(defPath));

  // 「配線済み」判定はモデルJSON実在で統一されているため、モデルJSONを直接削除してから再生成を呼ぶ
  // (writeTextureを介さない消滅ケースを模擬)。
  const assetName = CmdRegistry.loadRegistry(regPath).allocations[0].assetName;
  fs.unlinkSync(path.join(dir, "trinityforge-items", "assets", "trinityforge", "models", "item", `${assetName}.json`));
  Respack.regenerateItemDefinitions(dir, regPath);
  assert.ok(!fs.existsSync(defPath));
});

test("buildPack: skill-gui と items をマージしてzipを出力する", () => {
  const dir = tmpDir();
  const skillGuiDir = path.join(dir, "trinityforge-skill-gui");
  fs.mkdirSync(skillGuiDir, { recursive: true });
  fs.writeFileSync(path.join(skillGuiDir, "pack.mcmeta"), JSON.stringify({ pack: { pack_format: 75 } }));

  const regPath = registryPathOf(dir);
  Respack.writeTexture({ material: "IRON_INGOT", cmd: 1, id: "x", pngBuffer: MIN_PNG }, dir, regPath);

  const result = Respack.buildPack(dir);
  assert.ok(fs.existsSync(result.path));
  assert.ok(result.fileCount >= 3); // pack.mcmeta + texture + model + item定義
  assert.match(result.sha1, /^[0-9a-f]{40}$/);
});

test("buildPack: 同一相対パスがsourceで重複していたらエラー", () => {
  const dir = tmpDir();
  const skillGuiDir = path.join(dir, "trinityforge-skill-gui");
  fs.mkdirSync(skillGuiDir, { recursive: true });
  fs.writeFileSync(path.join(skillGuiDir, "dup.txt"), "a");

  const itemsDir = path.join(dir, "trinityforge-items");
  fs.mkdirSync(itemsDir, { recursive: true });
  fs.writeFileSync(path.join(itemsDir, "dup.txt"), "b");

  assert.throws(() => Respack.buildPack(dir), /重複/);
});

test("status: テクスチャ実在フラグを返す", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  Respack.writeTexture({ material: "IRON_INGOT", cmd: 1, id: "x", pngBuffer: MIN_PNG }, dir, regPath);
  const st = Respack.status(dir, regPath);
  assert.equal(st.allocations.length, 1);
  assert.equal(st.allocations[0].hasTexture, true);
});

// ---- A: アニメーションテクスチャ対応 ----

function mcmetaPathOf(dir, assetName) {
  return path.join(dir, "trinityforge-items", "assets", "trinityforge", "textures", "item", `${assetName}.png.mcmeta`);
}

test("A: animation指定でアニメーションテクスチャの.png.mcmetaが生成される", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const tallPng = buildTestPng(2, 8); // height(8) = width(2)*4 → 縦長要件を満たす
  const result = Respack.writeTexture(
    { material: "IRON_INGOT", cmd: 1, id: "anim", pngBuffer: tallPng, animation: { frametime: 5, interpolate: true } },
    dir, regPath
  );
  const mcmeta = JSON.parse(fs.readFileSync(mcmetaPathOf(dir, result.assetName), "utf8"));
  assert.deepEqual(mcmeta, { animation: { frametime: 5, interpolate: true } });
});

test("A: interpolate省略時はmcmetaにinterpolateキーを含めない", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const tallPng = buildTestPng(2, 6);
  const result = Respack.writeTexture(
    { material: "IRON_INGOT", cmd: 1, id: "anim2", pngBuffer: tallPng, animation: { frametime: 4 } },
    dir, regPath
  );
  const mcmeta = JSON.parse(fs.readFileSync(mcmetaPathOf(dir, result.assetName), "utf8"));
  assert.deepEqual(mcmeta, { animation: { frametime: 4 } });
});

test("A: 一度アニメーション登録後、animation未指定で再アップロードすると.png.mcmetaが削除される(静止画に戻す)", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const tallPng = buildTestPng(2, 6);
  const result = Respack.writeTexture(
    { material: "IRON_INGOT", cmd: 1, id: "anim3", pngBuffer: tallPng, animation: { frametime: 4 } },
    dir, regPath
  );
  assert.ok(fs.existsSync(mcmetaPathOf(dir, result.assetName)));

  Respack.writeTexture({ material: "IRON_INGOT", cmd: 1, id: "anim3", pngBuffer: MIN_PNG }, dir, regPath);
  assert.ok(!fs.existsSync(mcmetaPathOf(dir, result.assetName)));
});

test("A: frametimeが範囲外(0や201)はエラー", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const tallPng = buildTestPng(2, 6);
  assert.throws(
    () => Respack.writeTexture({ material: "IRON_INGOT", cmd: 1, id: "x", pngBuffer: tallPng, animation: { frametime: 0 } }, dir, regPath),
    /frametime/
  );
  assert.throws(
    () => Respack.writeTexture({ material: "IRON_INGOT", cmd: 2, id: "y", pngBuffer: tallPng, animation: { frametime: 201 } }, dir, regPath),
    /frametime/
  );
});

test("A: 縦長でないPNG(高さ<幅の2倍)にanimation指定するとエラー", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const squarePng = buildTestPng(4, 4); // height/width = 1 < 2
  assert.throws(
    () => Respack.writeTexture({ material: "IRON_INGOT", cmd: 1, id: "x", pngBuffer: squarePng, animation: { frametime: 4 } }, dir, regPath),
    /縦にコマを並べたPNG/
  );
});

test("A: IHDRパース単体 — width/heightの整数倍でないPNGは縦長要件を満たさずエラー", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const oddPng = buildTestPng(3, 8); // 8 % 3 !== 0
  assert.throws(
    () => Respack.writeTexture({ material: "IRON_INGOT", cmd: 1, id: "x", pngBuffer: oddPng, animation: { frametime: 4 } }, dir, regPath),
    /縦にコマを並べたPNG/
  );
});

test("A: zipビルドに.png.mcmetaが含まれる", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const tallPng = buildTestPng(2, 6);
  Respack.writeTexture(
    { material: "IRON_INGOT", cmd: 1, id: "anim-zip", pngBuffer: tallPng, animation: { frametime: 4 } },
    dir, regPath
  );
  const result = Respack.buildPack(dir);
  const buf = fs.readFileSync(result.path);
  // zip内エントリ名(パス文字列)がローカルファイルヘッダに含まれるため、単純に文字列検索で存在確認する。
  assert.ok(buf.toString("latin1").includes("anim-zip.png.mcmeta"));
});

// ---- B: カスタムモデルJSON対応 ----

test("B: カスタムモデルJSONの配置に成功し、customModel:trueが台帳に記録される", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const modelJson = {
    parent: "minecraft:item/generated",
    textures: { layer0: "trinityforge:item/custom_tex" }
  };
  const result = Respack.writeModel(
    { material: "IRON_INGOT", cmd: 1, id: "custom1", modelJson, textures: [{ name: "custom_tex", pngBuffer: MIN_PNG }] },
    dir, regPath
  );
  assert.ok(result.ok);
  const modelPath = path.join(dir, "trinityforge-items", "assets", "trinityforge", "models", "item", `${result.assetName}.json`);
  const written = JSON.parse(fs.readFileSync(modelPath, "utf8"));
  assert.deepEqual(written, modelJson);
  const texPath = path.join(dir, "trinityforge-items", "assets", "trinityforge", "textures", "item", "custom_tex.png");
  assert.ok(fs.existsSync(texPath));

  const registry = CmdRegistry.loadRegistry(regPath);
  const row = registry.allocations.find((a) => a.material === "IRON_INGOT" && a.cmd === 1);
  assert.equal(row.customModel, true);

  // range_dispatch生成: 配線済み判定はモデルJSON実在なので、このentryはtrinityforge:item/<assetName>を指す。
  const defPath = path.join(dir, "trinityforge-items", "assets", "minecraft", "items", "iron_ingot.json");
  const def = JSON.parse(fs.readFileSync(defPath, "utf8"));
  const entry = def.model.entries.find((e) => e.threshold === 1);
  assert.equal(entry.model.model, `trinityforge:item/${result.assetName}`);
});

test("B: 未解決のtrinityforge:item/参照(タイポ等)はエラーで拒否される", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const modelJson = {
    parent: "minecraft:item/generated",
    textures: { layer0: "trinityforge:item/typo_name" }
  };
  assert.throws(
    () => Respack.writeModel(
      { material: "IRON_INGOT", cmd: 1, id: "custom2", modelJson, textures: [{ name: "custom_tex", pngBuffer: MIN_PNG }] },
      dir, regPath
    ),
    /が見つかりません/
  );
});

test("B: minecraft:以外・trinityforge:以外の不正なnamespace参照はエラー", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const modelJson = {
    parent: "minecraft:item/generated",
    textures: { layer0: "someothermod:item/foo" }
  };
  assert.throws(
    () => Respack.writeModel({ material: "IRON_INGOT", cmd: 1, id: "custom3", modelJson }, dir, regPath),
    /不正なnamespace/
  );
});

test("B(N-2): textures参照値のname部分に ../ が含まれる場合はエラーで拒否する(存在オラクル防止)", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const modelJson = {
    parent: "minecraft:item/generated",
    textures: { layer0: "trinityforge:item/../../../../secret" }
  };
  assert.throws(
    () => Respack.writeModel({ material: "IRON_INGOT", cmd: 1, id: "custom_trav", modelJson }, dir, regPath),
    /name部分は/
  );
});

test("B: パック内に既存のPNGを参照する場合はtextures[]未指定でも成功する", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  // 先に通常テクスチャアップロードで trinityforge:item/anim-zip 相当の実在PNGを作っておく。
  Respack.writeTexture({ material: "GOLD_INGOT", cmd: 1, id: "existing_tex", pngBuffer: MIN_PNG }, dir, regPath);

  const modelJson = {
    parent: "minecraft:item/generated",
    textures: { layer0: "trinityforge:item/existing_tex" }
  };
  const result = Respack.writeModel(
    { material: "IRON_INGOT", cmd: 5, id: "custom4", modelJson },
    dir, regPath
  );
  assert.ok(result.ok);
});

test("B: modelJsonがparent/elements/textures のいずれも持たない場合はエラー", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  assert.throws(
    () => Respack.writeModel({ material: "IRON_INGOT", cmd: 1, id: "custom5", modelJson: { foo: "bar" } }, dir, regPath),
    /parent \/ elements \/ textures/
  );
});

test("B: customModel行への通常テクスチャ再アップロードでフラグが除去される(平面に戻す)", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const modelJson = { parent: "minecraft:item/generated", textures: { layer0: "trinityforge:item/custom_tex6" } };
  Respack.writeModel(
    { material: "IRON_INGOT", cmd: 1, id: "custom6", modelJson, textures: [{ name: "custom_tex6", pngBuffer: MIN_PNG }] },
    dir, regPath
  );
  let registry = CmdRegistry.loadRegistry(regPath);
  assert.equal(registry.allocations.find((a) => a.material === "IRON_INGOT" && a.cmd === 1).customModel, true);

  Respack.writeTexture({ material: "IRON_INGOT", cmd: 1, id: "custom6", pngBuffer: MIN_PNG }, dir, regPath);
  registry = CmdRegistry.loadRegistry(regPath);
  const row = registry.allocations.find((a) => a.material === "IRON_INGOT" && a.cmd === 1);
  assert.equal(row.customModel, undefined);
});

test("B: zipビルドにカスタムモデルJSONが含まれる", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const modelJson = { parent: "minecraft:item/generated", textures: { layer0: "trinityforge:item/zip_tex" } };
  const result = Respack.writeModel(
    { material: "IRON_INGOT", cmd: 1, id: "zip-model", modelJson, textures: [{ name: "zip_tex", pngBuffer: MIN_PNG }] },
    dir, regPath
  );
  const built = Respack.buildPack(dir);
  const buf = fs.readFileSync(built.path);
  assert.ok(buf.toString("latin1").includes(`${result.assetName}.json`));
});

test("previewInfo: 自動生成テクスチャはPNGをbase64で返す", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  Respack.writeTexture({ material: "IRON_INGOT", cmd: 5, id: "prev_tex", pngBuffer: MIN_PNG }, dir, regPath);
  const p = Respack.previewInfo(dir, regPath, "IRON_INGOT", 5);
  assert.equal(p.hasTexture, true);
  assert.equal(p.customModel, false);
  assert.equal(p.textures.length, 1);
  assert.equal(p.textures[0].ref, `trinityforge:item/${p.assetName}`);
  assert.deepEqual(Buffer.from(p.textures[0].pngBase64, "base64"), MIN_PNG);
});

test("previewInfo: カスタムモデルは参照PNGを重複なく返し、minecraft:参照はpngBase64:null", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const modelJson = {
    parent: "minecraft:item/handheld",
    textures: {
      "0": "trinityforge:item/prev_cm",
      particle: "trinityforge:item/prev_cm",
      extra: "minecraft:item/stick"
    }
  };
  Respack.writeModel(
    { material: "IRON_INGOT", cmd: 6, id: "prev_cm_item", modelJson, textures: [{ name: "prev_cm", pngBuffer: MIN_PNG }] },
    dir, regPath
  );
  const p = Respack.previewInfo(dir, regPath, "IRON_INGOT", 6);
  assert.equal(p.hasTexture, true);
  assert.equal(p.customModel, true);
  // "0" と particle は同一参照なので1件に重複排除される。
  const tfRefs = p.textures.filter((t) => t.ref.startsWith("trinityforge:"));
  assert.equal(tfRefs.length, 1);
  assert.ok(tfRefs[0].pngBase64);
  const mcRefs = p.textures.filter((t) => t.ref === "minecraft:item/stick");
  assert.equal(mcRefs.length, 1);
  assert.equal(mcRefs[0].pngBase64, null);
});

test("previewInfo: 未配線(台帳行なし/モデル欠落)は hasTexture:false", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  // 台帳行そのものが無い
  assert.equal(Respack.previewInfo(dir, regPath, "IRON_INGOT", 99).hasTexture, false);
  // 割当のみ(assetName無し)の行
  CmdRegistry.allocate(regPath, { material: "IRON_INGOT", id: "alloc_only", source: "test" }, () => null);
  const reg = CmdRegistry.loadRegistry(regPath);
  const cmd = reg.allocations.find((a) => a.id === "alloc_only").cmd;
  assert.equal(Respack.previewInfo(dir, regPath, "IRON_INGOT", cmd).hasTexture, false);
});

// ---------------------------------------------------------------------------
// バニラの描画構造の保持 (2026-08-03)
//
// 退行の内容: CMDエントリを常に素の minecraft:model 1個へ潰していたため、CMD付きの
// カスタム武器だけがトライデントの専用レンダラ・弓の引き絞り・槍の手持ちモデルを失い、
// 3人称で板ポリ(アイテム持ち)になっていた。fallback(CMDなし)側は正しかったので、
// バニラ品を見ても気づけない種類の壊れ方だった。
// ---------------------------------------------------------------------------

// entries から指定 cmd のモデルノードを取り出す。
function entryModelOf(def, cmd) {
  const hit = def.model.entries.find((e) => e.threshold === cmd);
  assert.ok(hit, `cmd ${cmd} のentryが無い`);
  return hit.model;
}

test("構造保持: TRIDENT のカスタムCMDは手持ち/投擲の minecraft:special レンダラを残す", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  Respack.writeTexture({ material: "TRIDENT", cmd: 7, id: "iron_trident", pngBuffer: MIN_PNG }, dir, regPath);
  const def = JSON.parse(fs.readFileSync(
    path.join(dir, "trinityforge-items", "assets", "minecraft", "items", "trident.json"), "utf8"));
  const model = entryModelOf(def, 7);

  // GUI/地面/額縁 は独自テクスチャ、手持ち/投擲はバニラの専用レンダラ。
  assert.equal(model.type, "minecraft:select");
  assert.equal(model.property, "minecraft:display_context");
  assert.equal(model.cases[0].model.model, "trinityforge:item/iron_trident");
  assert.equal(model.fallback.on_false.type, "minecraft:special");
  assert.equal(model.fallback.on_false.model.type, "minecraft:trident");
  assert.equal(model.fallback.on_true.base, "minecraft:item/trident_throwing");
});

test("構造保持: BOW のカスタムCMDは引き絞り3段階の切替を残し、全段が独自モデルを指す", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  Respack.writeTexture({ material: "BOW", cmd: 8, id: "iron_bow", pngBuffer: MIN_PNG }, dir, regPath);
  const def = JSON.parse(fs.readFileSync(
    path.join(dir, "trinityforge-items", "assets", "minecraft", "items", "bow.json"), "utf8"));
  const model = entryModelOf(def, 8);

  assert.equal(model.type, "minecraft:condition");
  assert.equal(model.property, "minecraft:using_item");
  assert.equal(model.on_false.model, "trinityforge:item/iron_bow");
  assert.equal(model.on_true.property, "minecraft:use_duration");
  // 引き絞り中にバニラの弓へ化けないこと(ここが素の minecraft:model だと化けていた)。
  assert.equal(model.on_true.fallback.model, "trinityforge:item/iron_bow__pulling_0");
  assert.equal(model.on_true.entries[0].model.model, "trinityforge:item/iron_bow__pulling_1");
  assert.equal(model.on_true.entries[1].model.model, "trinityforge:item/iron_bow__pulling_2");

  // 引き絞り用モデルはバニラの同段を parent にし、テクスチャだけ独自に差し替える。
  const pulling = JSON.parse(fs.readFileSync(
    path.join(dir, "trinityforge-items", "assets", "trinityforge", "models", "item", "iron_bow__pulling_1.json"),
    "utf8"));
  assert.equal(pulling.parent, "minecraft:item/bow_pulling_1");
  assert.equal(pulling.textures.layer0, "trinityforge:item/iron_bow");
});

test("構造保持: *_SPEAR のカスタムCMDは手持ち専用モデルを残し、槍の構えを継承する", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  Respack.writeTexture({ material: "NETHERITE_SPEAR", cmd: 9, id: "hero_spear", pngBuffer: MIN_PNG }, dir, regPath);
  const def = JSON.parse(fs.readFileSync(
    path.join(dir, "trinityforge-items", "assets", "minecraft", "items", "netherite_spear.json"), "utf8"));
  const model = entryModelOf(def, 9);

  assert.equal(model.property, "minecraft:display_context");
  assert.equal(model.cases[0].model.model, "trinityforge:item/hero_spear");
  assert.equal(model.fallback.model, "trinityforge:item/hero_spear__in_hand");

  const inHand = JSON.parse(fs.readFileSync(
    path.join(dir, "trinityforge-items", "assets", "trinityforge", "models", "item", "hero_spear__in_hand.json"),
    "utf8"));
  assert.equal(inHand.parent, "minecraft:item/netherite_spear_in_hand");
});

test("自動生成モデルの parent は handheld/generated の2択でなくマテリアル自身のモデルになる", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const modelDir = path.join(dir, "trinityforge-items", "assets", "trinityforge", "models", "item");
  // 呼び出し側が旧語彙の parent:"handheld" を渡しても、描画は常にマテリアル由来に倒す。
  Respack.writeTexture({ material: "MACE", cmd: 1, id: "iron_mace", pngBuffer: MIN_PNG, parent: "handheld" }, dir, regPath);
  Respack.writeTexture({ material: "BOW", cmd: 2, id: "iron_bow", pngBuffer: MIN_PNG, parent: "handheld" }, dir, regPath);
  Respack.writeTexture({ material: "IRON_SWORD", cmd: 3, id: "s", pngBuffer: MIN_PNG }, dir, regPath);

  // メイスは handheld_mace、弓は bow 固有の display を継承する(どちらも剣の構えとは別物)。
  assert.equal(JSON.parse(fs.readFileSync(path.join(modelDir, "iron_mace.json"), "utf8")).parent,
    "minecraft:item/mace");
  assert.equal(JSON.parse(fs.readFileSync(path.join(modelDir, "iron_bow.json"), "utf8")).parent,
    "minecraft:item/bow");
  assert.equal(JSON.parse(fs.readFileSync(path.join(modelDir, "s.json"), "utf8")).parent,
    "minecraft:item/iron_sword");
});

test("構造保持の対象外: 防具の鍛冶型(trim_material)は分岐を畳んで1モデルのままにする", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  Respack.writeTexture({ material: "NETHERITE_HELMET", cmd: 4, id: "infinity_helmet", pngBuffer: MIN_PNG }, dir, regPath);
  const def = JSON.parse(fs.readFileSync(
    path.join(dir, "trinityforge-items", "assets", "minecraft", "items", "netherite_helmet.json"), "utf8"));
  const model = entryModelOf(def, 4);

  // テクスチャは1枚しか無く、鍛冶型16種ぶんのリーフを作っても全部同じ絵になるだけなので畳む。
  assert.equal(model.type, "minecraft:model");
  assert.equal(model.model, "trinityforge:item/infinity_helmet");
  // fallback(CMDなし=バニラの防具)側の鍛冶型分岐は従来どおり無傷であること。
  assert.deepEqual(def.model.fallback, DEFS.NETHERITE_HELMET.model);
});

test("構造保持の対象外: 自作の立体モデル(customModel)は全コンテキストでそれを出す", () => {
  const dir = tmpDir();
  const regPath = registryPathOf(dir);
  const entry = Respack.entryModelFor("TRIDENT", { assetName: "bb_trident", customModel: true });
  assert.deepEqual(entry, { type: "minecraft:model", model: "trinityforge:item/bb_trident" });
});
