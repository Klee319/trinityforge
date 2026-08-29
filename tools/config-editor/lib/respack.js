"use strict";

// リソースパック生成モジュール。
// パックソース: <resourcePackPath>/trinityforge-items/ (新規、CMD配線パック)
//   + <resourcePackPath>/trinityforge-skill-gui/ (既存、skill GUI用。触らない)
// 出力: <resourcePackPath>/dist/TrinityForge-Pack.zip
//
// テクスチャ登録 → CMD台帳に assetName/parent を記録 → item定義(range_dispatch)を全再生成、
// という一方向フローで、常に「台帳 + 実在テクスチャ」から item 定義を機械的に導出する
// (item定義ファイルを手編集しない前提)。

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const { loadRegistry, loadRegistrySafe, saveRegistry, isValidMaterial, RESERVED_CMDS } = require("./cmd-registry");
const { buildZip } = require("./zipbuild");
const { isBlockbenchProject, convertBlockbenchProject } = require("./bbmodel");

// 1.21.11 クライアントjarから抽出した全1505 item定義の逐語コピー ({defs: {MATERIAL: <items/*.jsonの中身>}})。
// 手書き・合成ロジックは使わず、常にこのファイルの該当defを deep clone して使う。
// このファイル自体は編集禁止 (データ抽出元の生データ)。
const VANILLA_DEFS = require("./vanilla-item-defs-1.21.11.json");
const DEFS = VANILLA_DEFS.defs || {};

const MATERIAL_RE = /^[A-Z0-9_]+$/;
const ASSET_NAME_RE = /^[a-z0-9_.-]+$/;
const MAX_PNG_BYTES = 2 * 1024 * 1024;
const PNG_MAGIC = Buffer.from([0x89, 0x50, 0x4e, 0x47]);
// バニラの挙動を壊すため自動配線を拒否する基底アイテム (Java側の武器種別ヒントとは無関係)。
const HANDHELD_HINTS = ["SWORD", "_AXE", "HOE", "PICKAXE", "SHOVEL", "ROD", "BOW", "SPEAR", "HALBERD"];

function deepClone(value) {
  return JSON.parse(JSON.stringify(value));
}

const MIN_FRAMETIME = 1;
const MAX_FRAMETIME = 200;

// PNGのIHDRチャンク(バイト16-23: width/heightがビッグエンディアン32bit)を自前パースする。
// zlib等の画像デコーダには依存しない(このモジュールは他に依存パッケージを持たない方針のため)。
function readPngDimensions(pngBuffer) {
  if (!Buffer.isBuffer(pngBuffer) || pngBuffer.length < 24) {
    throw new Error("PNGのIHDRチャンクを読み取れません(データが短すぎます)");
  }
  const width = pngBuffer.readUInt32BE(16);
  const height = pngBuffer.readUInt32BE(20);
  if (!(width > 0) || !(height > 0)) {
    throw new Error("PNGのIHDRチャンクを読み取れません(width/heightが不正です)");
  }
  return { width, height };
}

// アニメーションテクスチャとして妥当か(縦にコマを並べたPNG=高さが幅の整数倍かつ2倍以上)を検証する。
function validateAnimationInput(pngBuffer, animation) {
  if (!animation) return null;
  const frametime = Number(animation.frametime);
  if (!Number.isInteger(frametime) || frametime < MIN_FRAMETIME || frametime > MAX_FRAMETIME) {
    throw new Error(`frametimeは${MIN_FRAMETIME}〜${MAX_FRAMETIME}の整数である必要があります`);
  }
  const { width, height } = readPngDimensions(pngBuffer);
  if (height % width !== 0 || height / width < 2) {
    throw new Error("アニメーションには縦にコマを並べたPNG(高さ=幅の整数倍)が必要です");
  }
  const interpolate = animation.interpolate === true;
  const mcmeta = { animation: { frametime } };
  if (interpolate) mcmeta.animation.interpolate = true;
  return mcmeta;
}

function itemsDir(packRoot) { return path.join(packRoot, "trinityforge-items"); }
function texturesDir(packRoot) { return path.join(itemsDir(packRoot), "assets", "trinityforge", "textures", "item"); }
function modelsDir(packRoot) { return path.join(itemsDir(packRoot), "assets", "trinityforge", "models", "item"); }
function itemDefsDir(packRoot) { return path.join(itemsDir(packRoot), "assets", "minecraft", "items"); }

function normalizeAssetName(id) {
  const lower = String(id || "item").toLowerCase();
  let normalized = lower.replace(/[^a-z0-9_.-]/g, "_");
  // L-1: 先頭のドットはアンダースコアへ (".." → "__" 等。隠しファイル化やパス誤解釈を避ける)。
  normalized = normalized.replace(/^\.+/, (m) => "_".repeat(m.length));
  return normalized || "item";
}

// 【後方互換のみ】material 名の部分一致で handheld / generated の2択を返す旧推定。
// 2026-08-03 に自動生成モデルの親決定からは外した(下記 vanillaLeafModels を参照)。
// 「BOW を含むから handheld」のような2択では、バニラが弓・メイス・槍・トライデントごとに
// 別々の display 変換を持っていることを表現できず、3人称の構え方が全部剣と同じになっていた。
// 台帳(cmd-registry.json)の parent 列と、それを表示する editor UI がまだこの語彙を使うため
// 関数自体は残す。生成される描画には影響しない。
function inferParent(material) {
  const mat = String(material || "").toUpperCase();
  return HANDHELD_HINTS.some((hint) => mat.includes(hint)) ? "handheld" : "generated";
}

// writeTexture/writeModel共通の material/cmd/id 検証 (テクスチャ/カスタムモデルの両ルートで同じ制約)。
function validateMaterialCmdId(material, cmd, id) {
  if (!material || !MATERIAL_RE.test(material)) {
    throw new Error("material は大文字英数字・アンダースコアのみである必要があります");
  }
  // L-2: Paper 1.21.11 に実在しないMaterialは拒否する。
  if (!isValidMaterial(material)) {
    throw new Error(`material "${material}" は Paper 1.21.11 に実在しないMaterialです`);
  }
  // 実データ(vanilla-item-defs-1.21.11.json)に item 定義が存在しない = アイテム形態を持たないMaterial
  // (壁看板/作物ブロック/AIR等)。逐語コピー元が無いため配線不可能。
  if (!DEFS[String(material).toUpperCase()]) {
    throw new Error("このMaterialはアイテム形態を持たないためテクスチャ登録できません");
  }
  if (!Number.isInteger(cmd) || cmd <= 0) {
    throw new Error("cmd は正の整数である必要があります");
  }
  if (!id || typeof id !== "string") {
    throw new Error("id は必須です");
  }
}

function validatePngBuffer(pngBuffer, label) {
  const prefix = label ? `${label}: ` : "";
  if (!Buffer.isBuffer(pngBuffer) || pngBuffer.length === 0) {
    throw new Error(`${prefix}pngBuffer が空です`);
  }
  if (pngBuffer.length > MAX_PNG_BYTES) {
    throw new Error(`${prefix}PNGサイズが上限(${MAX_PNG_BYTES}バイト)を超えています`);
  }
  if (!pngBuffer.subarray(0, 4).equals(PNG_MAGIC)) {
    throw new Error(`${prefix}PNG形式ではありません(マジックバイト不一致)`);
  }
}

function validateTextureInput({ material, cmd, id, pngBuffer }) {
  validateMaterialCmdId(material, cmd, id);
  validatePngBuffer(pngBuffer);
}

// カスタムモデルJSONの最低限の形状検証: 3キーのいずれかを持つこと(空オブジェクト等の事故防止)。
function validateModelJsonShape(modelJson) {
  if (!modelJson || typeof modelJson !== "object" || Array.isArray(modelJson)) {
    throw new Error("modelJson はオブジェクトである必要があります");
  }
  if (!("parent" in modelJson) && !("elements" in modelJson) && !("textures" in modelJson)) {
    throw new Error("modelJson には parent / elements / textures のいずれかが必要です");
  }
  // 2026-07-25: Blockbench プロジェクトファイルの誤投入対策。Java版モデルの textures は
  // 常にオブジェクト(マップ)で、配列なのは Blockbench プロジェクト形式のみ。旧実装はこれを
  // 素通りさせ、参照0件=検証成功として「登録できたのに描画されない」状態を作っていた。
  // 通常の経路は writeModel が事前に変換するため、ここへ到達するのは変換をすり抜けた場合だけ。
  if (Array.isArray(modelJson.textures)) {
    throw new Error(
      "modelJson の textures が配列です（Blockbench のプロジェクトファイル形式）。"
      + "Java版モデルでは textures はオブジェクトである必要があります"
    );
  }
  if (!("elements" in modelJson) && !("parent" in modelJson)) {
    throw new Error("modelJson には parent か elements のいずれかが必要です（textures だけでは描画できません）");
  }
}

// textures[].name は assetName と同じ正規化ルールに従うこと(ここでは自動補正せず、
// 規約外の名前はエラーで突き返す。modelJson内の参照と食い違う「サイレント別名化」を避けるため)。
function validateTextureAssetName(name) {
  if (typeof name !== "string" || !name || name.startsWith(".") || !ASSET_NAME_RE.test(name)) {
    throw new Error(`textures[].name が不正です: "${name}" (小文字英数字・_.-のみ、先頭ドット不可)`);
  }
}

// modelJson木を再帰的に走査し、"textures" キーを持つオブジェクトの値(文字列)を全て集める。
// 1.21.x のモデル形式は select/condition/range_dispatch 等でネストしうるため、トップレベルの
// "textures" だけでなく木全体を対象にする。
function collectTextureRefs(node, refs) {
  if (!node || typeof node !== "object") return;
  if (Array.isArray(node)) {
    for (const item of node) collectTextureRefs(item, refs);
    return;
  }
  for (const [key, value] of Object.entries(node)) {
    if (key === "textures" && value && typeof value === "object" && !Array.isArray(value)) {
      for (const v of Object.values(value)) {
        if (typeof v === "string") refs.push(v);
      }
    } else {
      collectTextureRefs(value, refs);
    }
  }
}

// Blockbench の Java エクスポートは、パック内テクスチャの参照を名前空間なしの素の名前
// (例 "test" や "item/test") で書き出す。Minecraft はこれを minecraft: 名前空間として解決するため、
// そのままでは trinityforge 名前空間に置いた我々のPNGに解決できず、ゲーム内で missing texture に
// なる (2026-07-25 実例: wood_test が textures {"0": "test"} を持ち、パック内の
// trinityforge/textures/item/test.png に到達できなかった)。
// ここで「名前空間が無く、かつ同名PNGが今回アップロード分かパック内に実在する」参照だけを
// trinityforge:item/<name> へ書き換える。実在しない参照はバニラ資産の意図的な流用とみなして触らない。
function normalizeTextureRefs(modelJson, uploadedNames, texDir) {
  const rewritten = [];
  const cloned = deepClone(modelJson);

  const resolveLocal = (ref) => {
    if (typeof ref !== "string" || ref.includes(":")) return null;
    const name = ref.slice(ref.lastIndexOf("/") + 1);
    if (name.startsWith(".") || !ASSET_NAME_RE.test(name)) return null;
    const available = uploadedNames.has(name) || fs.existsSync(path.join(texDir, `${name}.png`));
    return available ? name : null;
  };

  (function walk(node) {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) {
      for (const item of node) walk(item);
      return;
    }
    for (const [key, value] of Object.entries(node)) {
      if (key === "textures" && value && typeof value === "object" && !Array.isArray(value)) {
        for (const [slot, ref] of Object.entries(value)) {
          const name = resolveLocal(ref);
          if (name) {
            value[slot] = `trinityforge:item/${name}`;
            rewritten.push(`${ref} → trinityforge:item/${name}`);
          }
        }
      } else {
        walk(value);
      }
    }
  })(cloned);

  return { modelJson: cloned, rewritten };
}

// modelJsonが参照するテクスチャの整合性を検証する (タイポ事故防止)。
// - "minecraft:" 参照はバニラアセットとしてそのまま許可。
// - "trinityforge:item/<name>" 参照は、今回アップロードのtextures[].name か
//   パック内に既存のPNGのいずれかに解決できなければエラー。
// - それ以外のnamespaceは常にエラー。
function validateModelTextureRefs(modelJson, uploadedNames, texDir) {
  const refs = [];
  collectTextureRefs(modelJson, refs);
  for (const ref of refs) {
    const namespace = ref.includes(":") ? ref.split(":")[0] : "minecraft";
    if (namespace === "minecraft") continue;
    if (namespace !== "trinityforge" || !ref.startsWith("trinityforge:item/")) {
      throw new Error(`modelJsonのtextures参照に不正なnamespaceが含まれています: "${ref}" (minecraft: か trinityforge:item/<name> のみ許可)`);
    }
    const name = ref.slice("trinityforge:item/".length);
    // N-2: 参照値の name 部分にもパストラバーサル対策を適用する。"../" 等が含まれると
    // fs.existsSync が texDir 外のパスを叩き、存在有無がエラーメッセージから漏れる(存在オラクル)。
    if (name.startsWith(".") || !ASSET_NAME_RE.test(name)) {
      throw new Error(`modelJsonのtextures参照が不正です: "${ref}" (name部分は小文字英数字・_.-のみ、先頭ドット・スラッシュ不可)`);
    }
    const existingOnDisk = fs.existsSync(path.join(texDir, `${name}.png`));
    if (!uploadedNames.has(name) && !existingOnDisk) {
      throw new Error(`modelJsonが参照するテクスチャ "${ref}" が見つかりません(今回アップロード分にもパック内既存PNGにも該当しません)`);
    }
  }
}

// 台帳内で (material,cmd) 以外に同じ assetName を使っている行があれば重複を避けて別名にする。
// M-1: `${base}_${cmd}` すら衝突する場合は `_2`, `_3`... と一意になるまで採番する
// (既存の別 (material,cmd) の PNG/モデルを絶対に上書きしない)。
function resolveAssetName(registry, material, cmd, id) {
  const base = normalizeAssetName(id);
  const conflict = (name) => registry.allocations.some((a) =>
    a.assetName === name && !(a.material === material && a.cmd === cmd));
  if (!conflict(base)) return base;
  const withCmd = `${base}_${cmd}`;
  if (!conflict(withCmd)) return withCmd;
  let n = 2;
  while (conflict(`${withCmd}_${n}`)) n += 1;
  return `${withCmd}_${n}`;
}

// PNGテクスチャ1件を登録し、モデルJSONを書き、台帳の assetName/parent を更新した後、
// item定義群を全再生成する。
// opts.animation = {frametime, interpolate} を指定すると <assetName>.png.mcmeta を生成する。
// 未指定での再アップロードは、既存の .png.mcmeta があれば削除する(静止画に戻す操作として扱う)。
// このルートは常に「自動生成モデル」を書くため、台帳行に customModel:true が付いていた場合は
// 平面テクスチャに戻す操作として扱い、フラグを除去する(B機能との整合)。
function writeTexture(opts, packRoot, registryPath) {
  const material = String(opts.material || "").toUpperCase();
  const cmd = opts.cmd;
  const id = opts.id;
  const pngBuffer = opts.pngBuffer;
  const parent = opts.parent === "handheld" || opts.parent === "generated" ? opts.parent : inferParent(material);

  validateTextureInput({ material, cmd, id, pngBuffer });
  const mcmeta = validateAnimationInput(pngBuffer, opts.animation);

  const registry = loadRegistry(registryPath);
  const assetName = resolveAssetName(registry, material, cmd, id);

  fs.mkdirSync(texturesDir(packRoot), { recursive: true });
  fs.mkdirSync(modelsDir(packRoot), { recursive: true });
  const pngPath = path.join(texturesDir(packRoot), `${assetName}.png`);
  fs.writeFileSync(pngPath, pngBuffer);
  const mcmetaPath = `${pngPath}.mcmeta`;
  if (mcmeta) {
    fs.writeFileSync(mcmetaPath, JSON.stringify(mcmeta, null, 2) + "\n", "utf8");
  } else if (fs.existsSync(mcmetaPath)) {
    fs.unlinkSync(mcmetaPath);
  }
  // バニラ定義のリーフごとにモデルを書く。単純なマテリアル(剣・防具・素材など)は
  // リーフ1つなので従来どおり <assetName>.json だけが出る。BOW / *_SPEAR のように
  // 引き絞りや手持ち専用モデルを持つマテリアルでは、その分だけ
  // <assetName>__pulling_0.json / <assetName>__in_hand.json …が追加で出る。
  // テクスチャは1枚しか無いので全リーフが同じ layer0 を指す(差は parent の display 変換)。
  // 専用の引き絞り絵などを後から足したくなったときは、このファイルを差し替えるだけでよい。
  for (const leaf of vanillaLeafModels(material)) {
    const leafAsset = leafAssetName(assetName, leaf.suffix);
    fs.writeFileSync(
      path.join(modelsDir(packRoot), `${leafAsset}.json`),
      JSON.stringify(generatedLeafModel(leaf.vanillaId, assetName), null, 2) + "\n",
      "utf8"
    );
  }

  const idx = registry.allocations.findIndex((a) => a.material === material && a.cmd === cmd);
  const nextAllocations = [...registry.allocations];
  if (idx >= 0) {
    const prev = { ...nextAllocations[idx] };
    delete prev.customModel;
    nextAllocations[idx] = { ...prev, assetName, parent, id: nextAllocations[idx].id || id };
  } else {
    nextAllocations.push({ material, cmd, id, source: "respack", assetName, parent, allocatedAt: new Date().toISOString() });
  }
  const nextRegistry = { version: registry.version || 1, allocations: nextAllocations };
  saveRegistry(registryPath, nextRegistry);

  regenerateItemDefinitions(packRoot, registryPath);
  return { ok: true, assetName };
}

// カスタムモデルJSON(上級者向け)を登録する。自動生成の layer0 モデルの代わりに
// modelJson をそのまま models/item/<assetName>.json へ書き、台帳行に customModel:true を付ける。
// v1制約: このルートで登録したテクスチャにアニメーション指定はできない(manual.jsに明記)。
// opts.textures: [{name, pngBuffer}] (0件可。既存PNGのみを再利用するmodelJsonのケースを許容する)。
function writeModel(opts, packRoot, registryPath) {
  const material = String(opts.material || "").toUpperCase();
  const cmd = opts.cmd;
  const id = opts.id;
  const uploadedTextures = Array.isArray(opts.textures) ? opts.textures : [];

  validateMaterialCmdId(material, cmd, id);

  // 2026-07-25: Blockbench のプロジェクトファイル(.bbmodel相当)が投入されたら、Java版アイテム
  // モデルへ変換してから通常フローに合流する。変換できない構造(多軸回転・非対応角度など)は
  // 理由を列挙して例外にする — 旧実装のように「成功表示だが描画されない」状態を作らない。
  let modelJson = opts.modelJson;
  let textures = uploadedTextures;
  let converted = false;
  let conversionWarnings = [];
  if (isBlockbenchProject(modelJson)) {
    const result = convertBlockbenchProject(modelJson, (name) => `trinityforge:item/${name}`);
    modelJson = result.modelJson;
    conversionWarnings = result.warnings;
    converted = true;
    // 同名は手動アップロード側を優先する(利用者が差し替えたPNGを埋め込み画像で上書きしない)。
    const uploadedNames = new Set(uploadedTextures.map((t) => t.name));
    textures = [...uploadedTextures, ...result.textures.filter((t) => !uploadedNames.has(t.name))];
  }

  validateModelJsonShape(modelJson);
  for (const t of textures) {
    validateTextureAssetName(t.name);
    validatePngBuffer(t.pngBuffer, `textures[].pngBuffer(${t.name})`);
  }

  const texDir = texturesDir(packRoot);
  const uploadedNames = new Set(textures.map((t) => t.name));
  const normalized = normalizeTextureRefs(modelJson, uploadedNames, texDir);
  modelJson = normalized.modelJson;
  validateModelTextureRefs(modelJson, uploadedNames, texDir);

  const registry = loadRegistry(registryPath);
  const assetName = resolveAssetName(registry, material, cmd, id);
  const parent = opts.parent === "handheld" || opts.parent === "generated" ? opts.parent : inferParent(material);

  fs.mkdirSync(texDir, { recursive: true });
  fs.mkdirSync(modelsDir(packRoot), { recursive: true });
  for (const t of textures) {
    fs.writeFileSync(path.join(texDir, `${t.name}.png`), t.pngBuffer);
  }
  fs.writeFileSync(
    path.join(modelsDir(packRoot), `${assetName}.json`),
    JSON.stringify(modelJson, null, 2) + "\n",
    "utf8"
  );

  const idx = registry.allocations.findIndex((a) => a.material === material && a.cmd === cmd);
  const nextAllocations = [...registry.allocations];
  if (idx >= 0) {
    nextAllocations[idx] = {
      ...nextAllocations[idx], assetName, parent,
      id: nextAllocations[idx].id || id, customModel: true
    };
  } else {
    nextAllocations.push({
      material, cmd, id, source: "respack", assetName, parent,
      customModel: true, allocatedAt: new Date().toISOString()
    });
  }
  const nextRegistry = { version: registry.version || 1, allocations: nextAllocations };
  saveRegistry(registryPath, nextRegistry);

  regenerateItemDefinitions(packRoot, registryPath);
  return {
    ok: true,
    assetName,
    converted,
    warnings: conversionWarnings,
    rewrittenTextureRefs: normalized.rewritten,
    writtenTextures: textures.map((t) => t.name)
  };
}

// defs[material].model サブツリーの逐語コピー(deep clone)を返す。
// 合成ロジック(item/<小文字>のパス組み立て)は一切行わない。
// 呼び出し元は事前に defs[material] の存在を保証していること(validateTextureInputのL-2/no-item-form検査)。
function fallbackForMaterial(material) {
  const mat = String(material || "").toUpperCase();
  const def = DEFS[mat];
  if (!def) {
    throw new Error(`material "${material}" の item 定義データが見つかりません`);
  }
  return deepClone(def.model);
}

// ---------------------------------------------------------------------------
// バニラのモデル構造を保ったまま、テクスチャだけカスタムへ差し替えるための仕組み
// (2026-08-03)。
//
// 以前は CMD エントリを常に素の {type:"minecraft:model"} 1個へ潰していたため、
// バニラ側がマテリアルごとに持っている描画の作りが丸ごと失われていた:
//   - TRIDENT      : minecraft:special の専用レンダラ(立体モデル+投擲アニメーション)
//   - BOW          : using_item + use_duration による引き絞り3段階の切替
//   - *_SPEAR      : display_context による GUI アイコンと手持ちモデルの出し分け
// fallback(CMDなし) だけは正しくバニラ定義を deep clone していたので、
// 「素の鉄の剣は正常なのに、CMD 付きのカスタム武器だけ板ポリになる」状態だった。
//
// 方針: バニラ定義のツリーをそのまま複製し、その中の minecraft:model リーフだけを
// カスタムモデルへ差し替える。minecraft:special は専用レンダラであり、テクスチャを
// 差し替える手段がそもそも無いので触らない(＝バニラの見た目と挙動を残す)。
// ---------------------------------------------------------------------------

// モデルツリーを走査して minecraft:model リーフのノード自体を出現順に集める。
// minecraft:special の内側へは降りない(差し替え対象にしないため)。
function collectModelLeaves(node, out = []) {
  if (Array.isArray(node)) {
    for (const child of node) collectModelLeaves(child, out);
    return out;
  }
  if (!node || typeof node !== "object") return out;
  if (node.type === "minecraft:model") {
    out.push(node);
    return out;
  }
  if (node.type === "minecraft:special") return out;
  for (const key of ["cases", "fallback", "on_true", "on_false", "entries"]) {
    if (key in node) collectModelLeaves(node[key], out);
  }
  // cases[] / entries[] の各要素は {when|threshold, model:{...}} という形なので、
  // type を持たないオブジェクトからは model プロパティを辿る。
  if (!node.type && node.model && typeof node.model === "object") {
    collectModelLeaves(node.model, out);
  }
  return out;
}

// 「同じアイテムが、状況によって別のモデルで描かれる」ことを表す property。
// これらが入っているマテリアルだけ、バニラの構造を保ったまま差し替える。
//
// 逆に minecraft:trim_material のような「データ差分」による分岐は対象にしない。
// カスタムアイテムのテクスチャは1枚しか無く、鍛冶型16種ぶんのリーフを作っても
// 全部同じ絵になるだけで、モデルファイルが17倍に増える以外の効果が無いため。
// (分岐を保持しないので、鍛冶型を付けたカスタム防具のアイコンは従来どおり
//  カスタム絵のまま変わらない — これは以前からの挙動で、退行ではない。)
const RENDER_CONTEXT_PROPERTIES = new Set([
  "minecraft:display_context",
  "minecraft:using_item",
  "minecraft:use_duration"
]);

function hasRenderContextSplit(node) {
  if (Array.isArray(node)) return node.some((child) => hasRenderContextSplit(child));
  if (!node || typeof node !== "object") return false;
  if (RENDER_CONTEXT_PROPERTIES.has(node.property)) return true;
  return Object.values(node).some((value) => hasRenderContextSplit(value));
}

// リーフのバニラ model id から、生成するカスタムモデルのサフィックスを決める。
// 例) bow / bow_pulling_0        → ""      / "pulling_0"
//     netherite_spear / _in_hand → ""      / "in_hand"
function leafSuffix(primaryId, leafId, index) {
  const base = (id) => String(id).split("/").pop();
  const primary = base(primaryId);
  const leaf = base(leafId);
  if (leaf === primary) return "";
  if (leaf.startsWith(`${primary}_`)) return leaf.slice(primary.length + 1);
  return `alt${index}`;
}

// material のバニラ定義から「差し替えるべきリーフ」の一覧を返す。
// 戻り値: [{ vanillaId, suffix }] — 先頭が主モデル(GUI アイコン等に使われるもの)。
// 主モデルは「id が item/<material小文字> と一致するリーフ」を優先する。BOW のように
// 走査順の先頭が bow_pulling_0 になるケースでも、意味的な主モデルを先頭へ固定するため。
function vanillaLeafModels(material) {
  const mat = String(material || "").toUpperCase();
  const def = DEFS[mat];
  if (!def) {
    throw new Error(`material "${material}" の item 定義データが見つかりません`);
  }
  // 描画コンテキストによる出し分けを持たないマテリアルは、主モデル1枚だけを見る
  // (大多数のアイテムがここ。従来と同じく <assetName>.json が1つ出るだけ)。
  const ids = hasRenderContextSplit(def.model)
    ? [...new Set(collectModelLeaves(def.model).map((n) => n.model))]
    : [`minecraft:item/${mat.toLowerCase()}`].filter((id) =>
        collectModelLeaves(def.model).some((n) => n.model === id));
  if (ids.length === 0) {
    // 全リーフが minecraft:special のマテリアル(PLAYER_HEAD 等)。差し替え先が無いので
    // 主モデル1枚だけを素の generated として扱う(従来どおり平面アイコンになる)。
    return [{ vanillaId: null, suffix: "" }];
  }
  const primaryId = `minecraft:item/${mat.toLowerCase()}`;
  const at = ids.indexOf(primaryId);
  if (at > 0) {
    ids.splice(at, 1);
    ids.unshift(primaryId);
  }
  return ids.map((vanillaId, index) => ({
    vanillaId,
    suffix: leafSuffix(ids[0], vanillaId, index)
  }));
}

// assetName + サフィックスから、実際に書き出すモデルファイル名を決める。
function leafAssetName(assetName, suffix) {
  return suffix ? `${assetName}__${suffix}` : assetName;
}

// 自動生成モデル(テクスチャ1枚ルート)のリーフ1つぶんの json。
// parent はバニラの同リーフをそのまま指すので、display 変換(構え方・大きさ)は
// マテリアル本来のものを継承し、layer0 だけがカスタムテクスチャになる。
function generatedLeafModel(vanillaId, assetName) {
  return {
    parent: vanillaId || "minecraft:item/generated",
    textures: { layer0: `trinityforge:item/${assetName}` }
  };
}

// 配線済み1行ぶんの CMD エントリのモデルノードを組み立てる。
//
// 自動生成モデル(テクスチャ1枚ルート)は、バニラ定義の構造を丸ごと複製してから
// minecraft:model リーフだけをカスタムモデルへ差し替える。これにより
// トライデントの専用レンダラ・弓の引き絞り・槍の手持ちモデルがカスタム品でも生き残る。
//
// カスタムモデル(bbmodel アップロード, customModel:true)は素の minecraft:model のままにする。
// 立体モデルを自分で作った以上、全ての表示コンテキストでそれを出すのが作者の意図であり、
// GUI だけ独自でその他はバニラ、という中途半端な合成にはしない。
function entryModelFor(material, allocation) {
  const custom = `trinityforge:item/${allocation.assetName}`;
  if (allocation.customModel) {
    return { type: "minecraft:model", model: custom };
  }
  const tree = fallbackForMaterial(material);
  // バニラ自身が素の単一モデル(剣・素材など大多数)、あるいは分岐が描画コンテキスト由来で
  // ないもの(防具の鍛冶型など)は、複製しても意味が無いので素で返す。
  if (tree.type === "minecraft:model" || !hasRenderContextSplit(tree)) {
    return { type: "minecraft:model", model: custom };
  }
  const byVanillaId = new Map(
    vanillaLeafModels(material)
      .filter((leaf) => leaf.vanillaId)
      .map((leaf) => [leaf.vanillaId, `trinityforge:item/${leafAssetName(allocation.assetName, leaf.suffix)}`])
  );
  const targets = collectModelLeaves(tree).filter((node) => byVanillaId.has(node.model));
  // 差し替えられる minecraft:model リーフが1つも無いマテリアル(PLAYER_HEAD 等、全リーフが
  // minecraft:special)。構造を残してもカスタムテクスチャを出す場所が無いので、
  // 素のモデルへ倒す(頭モデルではなく登録した平面アイコンを出す＝従来どおりの意図した挙動)。
  if (targets.length === 0) {
    return { type: "minecraft:model", model: custom };
  }
  for (const node of targets) {
    node.model = byVanillaId.get(node.model);
  }
  return tree;
}

// material を付け替えられた行(reconcileWithUsageDetailed の moved)の自動生成モデルを、
// 新しい material のバニラリーフへ貼り直す。
//
// 自動生成モデルの parent は「登録時の material のバニラモデル」を焼き込んである。
// 貼り直さないと (1) 構え方・大きさが旧 material のまま残り、(2) BOW/TRIDENT のように
// リーフ構成が違う material へ移した場合は entryModelFor が参照する
// <assetName>__pulling_0.json 等がそもそも存在せず、描画が丸ごと落ちる。
//
// customModel:true の行は作者が書いた JSON なので絶対に上書きしない。
// 旧 material 由来の余分なリーフファイルは消さない(共有アセットを誤削除しない方針に合わせる)。
function rewriteMovedModels(packRoot, moved) {
  const modDir = modelsDir(packRoot);
  const rewritten = [];
  for (const move of Array.isArray(moved) ? moved : []) {
    if (!move || !move.assetName || move.customModel) continue;
    if (!move.to || !move.to.material) continue;
    // 元から未配線(モデルJSONが無い)の行は対象外。
    if (!fs.existsSync(path.join(modDir, `${move.assetName}.json`))) continue;
    let leaves;
    try {
      leaves = vanillaLeafModels(move.to.material);
    } catch (_) {
      continue; // バニラ定義データを持たない material は従来どおり触らない
    }
    for (const leaf of leaves) {
      const leafAsset = leafAssetName(move.assetName, leaf.suffix);
      fs.writeFileSync(
        path.join(modDir, `${leafAsset}.json`),
        JSON.stringify(generatedLeafModel(leaf.vanillaId, move.assetName), null, 2) + "\n",
        "utf8"
      );
    }
    rewritten.push(move.assetName);
  }
  return rewritten;
}

// 台帳を material ごとにグループし、assets/minecraft/items/<material>.json (range_dispatch) を
// 全再生成する。
//
// H-3 (range_dispatchフォールスルー修正): entriesは「テクスチャ実在行のみ」ではなく、
// そのmaterialの台帳全行について生成する。未配線の行は、そのentryのmodelを
// 「そのmaterialのバニラfallbackモデル」にする。range_dispatchはthreshold以下の最大エントリを
// 採用するため、未配線cmdのentryを省略すると1つ下のthresholdのモデルが誤って描画される
// (フォールスルー)。entry自体は必ず生成し、見た目だけバニラへ落とすことでこれを防ぐ。
//
// 「配線済み」判定はモデルJSON(models/item/<assetName>.json)の実在で統一する
// (通常テクスチャ・カスタムモデルJSONのどちらの経路でも同じ場所に生成されるため)。
// テクスチャPNGの実在判定には依存しない(カスタムモデルは複数PNGを使う場合がある等)。
//
// ファイル生成条件(現状維持): そのmaterialに配線済み行が1つ以上あること。
// 配線済み行が0件になったmaterialの定義ファイルは削除する。
function regenerateItemDefinitions(packRoot, registryPath) {
  const registry = loadRegistry(registryPath);
  const modDir = modelsDir(packRoot);
  const defsDir = itemDefsDir(packRoot);
  fs.mkdirSync(defsDir, { recursive: true });

  function hasModel(a) {
    return !!(a.assetName && fs.existsSync(path.join(modDir, `${a.assetName}.json`)));
  }

  const byMaterial = new Map();
  for (const a of registry.allocations) {
    const list = byMaterial.get(a.material) || [];
    list.push(a);
    byMaterial.set(a.material, list);
  }

  // ファイル生成条件: そのmaterialに配線済み行が1つ以上あること。
  const activeMaterials = new Set();
  for (const [material, list] of byMaterial.entries()) {
    if (list.some(hasModel)) activeMaterials.add(material.toLowerCase());
  }

  // 既存ファイルのうち、今回テクスチャが無くなった material は削除する。
  let existingFiles = [];
  try { existingFiles = fs.readdirSync(defsDir).filter((f) => f.endsWith(".json")); } catch (_) { existingFiles = []; }
  for (const file of existingFiles) {
    const matLower = file.replace(/\.json$/, "");
    if (!activeMaterials.has(matLower)) {
      fs.unlinkSync(path.join(defsDir, file));
    }
  }

  for (const [material, list] of byMaterial.entries()) {
    if (!activeMaterials.has(material.toLowerCase())) continue;
    const sorted = [...list].sort((a, b) => a.cmd - b.cmd);
    const fallback = fallbackForMaterial(material);
    const entries = sorted.map((a) => ({
      threshold: a.cmd,
      model: hasModel(a) ? entryModelFor(material, a) : fallback
    }));
    // defs[material] の "model" 以外のトップレベルキー(例: WOODEN_SPEAR等のswap_animation_scale)を
    // そのまま生成ファイルのトップレベルへ引き継ぐ(バニラの手持ちアニメーション等を壊さないため)。
    const sourceDef = DEFS[material.toUpperCase()] || {};
    const extraTopLevel = {};
    for (const key of Object.keys(sourceDef)) {
      if (key !== "model") extraTopLevel[key] = deepClone(sourceDef[key]);
    }
    const def = {
      ...extraTopLevel,
      model: {
        type: "minecraft:range_dispatch",
        property: "minecraft:custom_model_data",
        fallback,
        entries
      }
    };
    const outPath = path.join(defsDir, `${material.toLowerCase()}.json`);
    fs.writeFileSync(outPath, JSON.stringify(def, null, 2) + "\n", "utf8");
  }

  return { materials: [...activeMaterials] };
}

// 配線済み (material, cmd) のテクスチャプレビュー情報を返す。
// モデルJSON (models/item/<assetName>.json) が参照するテクスチャのうち、
// trinityforge:item/<name> はパック内PNGを base64 で同梱する。
// minecraft: 参照はバニラ資産のためローカルに実体が無く pngBase64:null で返す
// (UI側は参照名のみ表示する)。未配線・モデル欠落・JSON破損は hasTexture:false。
function previewInfo(packRoot, registryPath, material, cmd) {
  const registry = loadRegistry(registryPath);
  const row = registry.allocations.find((a) => a.material === material && a.cmd === cmd);
  if (!row || !row.assetName) return { hasTexture: false, textures: [] };
  const modelPath = path.join(modelsDir(packRoot), `${row.assetName}.json`);
  if (!fs.existsSync(modelPath)) return { hasTexture: false, textures: [] };
  let modelJson;
  try {
    modelJson = JSON.parse(fs.readFileSync(modelPath, "utf8"));
  } catch (_) {
    return { hasTexture: false, textures: [] };
  }
  const refs = [];
  collectTextureRefs(modelJson, refs);
  const seen = new Set();
  const textures = [];
  for (const ref of refs) {
    if (typeof ref !== "string" || seen.has(ref)) continue;
    seen.add(ref);
    if (ref.startsWith("trinityforge:item/")) {
      const name = ref.slice("trinityforge:item/".length);
      // validateModelTextureRefs と同じ正規化制約 (パストラバーサル防止)。
      if (name.startsWith(".") || !ASSET_NAME_RE.test(name)) continue;
      const pngPath = path.join(texturesDir(packRoot), `${name}.png`);
      textures.push({
        ref, name,
        pngBase64: fs.existsSync(pngPath) ? fs.readFileSync(pngPath).toString("base64") : null
      });
    } else {
      textures.push({ ref, name: null, pngBase64: null });
    }
  }
  // 2026-07-25: 「登録済みなのにプレビューが真っ白」を、UIが原因まで言えるように分類する。
  //   broken=true は「モデルは存在するが、パック内のPNGに解決できる参照が1つも無い」状態。
  //   Blockbenchプロジェクトの誤投入や、参照名のタイポで実際に起きていた。
  const resolvable = textures.filter((t) => t.pngBase64);
  return {
    hasTexture: true,
    assetName: row.assetName,
    parent: row.parent || null,
    customModel: !!row.customModel,
    broken: resolvable.length === 0,
    blockbenchProject: Array.isArray(modelJson.textures),
    textures
  };
}

// パックソース2つ (skill-gui + items) をマージして決定論的zipを出力する。
function collectFiles(rootDir, baseDir) {
  const out = [];
  if (!fs.existsSync(rootDir)) return out;
  const stack = [rootDir];
  while (stack.length) {
    const dir = stack.pop();
    for (const name of fs.readdirSync(dir)) {
      if (name === "__pycache__" || name.startsWith(".")) continue;
      const abs = path.join(dir, name);
      const stat = fs.statSync(abs);
      if (stat.isDirectory()) {
        stack.push(abs);
      } else if (stat.isFile()) {
        const rel = path.relative(baseDir, abs).split(path.sep).join("/");
        out.push({ path: rel, data: fs.readFileSync(abs) });
      }
    }
  }
  return out;
}

function buildPack(packRoot) {
  const skillGuiDir = path.join(packRoot, "trinityforge-skill-gui");
  const itemsSourceDir = itemsDir(packRoot);
  const skillGuiFiles = collectFiles(skillGuiDir, skillGuiDir);
  const itemFiles = collectFiles(itemsSourceDir, itemsSourceDir);

  const seen = new Map();
  for (const f of skillGuiFiles) seen.set(f.path, "trinityforge-skill-gui");
  for (const f of itemFiles) {
    if (seen.has(f.path)) {
      throw new Error(`パックソース間でファイルが重複しています: ${f.path} (trinityforge-skill-gui と trinityforge-items の両方に存在)`);
    }
  }

  const allEntries = [...skillGuiFiles, ...itemFiles];
  const outPath = path.join(packRoot, "dist", "TrinityForge-Pack.zip");
  const buf = buildZip(allEntries, outPath);
  const sha1 = crypto.createHash("sha1").update(buf).digest("hex");
  return { sha1, size: buf.length, fileCount: allEntries.length, path: outPath };
}

// 台帳・テクスチャ実在・非対応判定・最終ビルド情報をまとめて返す。
// H-2: 台帳が破損していても例外を投げず、registryCorrupt:true と空の allocations を返す
// (UI側respack-view.jsで破損警告を表示する)。
function status(packRoot, registryPath) {
  const { registry, corrupt } = loadRegistrySafe(registryPath);
  const modDir = modelsDir(packRoot);
  const rows = registry.allocations.map((a) => {
    // 配線済み判定はモデルJSON実在で統一 (通常テクスチャ/カスタムモデルJSONどちらの経路でも同じ)。
    const hasTexture = !!(a.assetName && fs.existsSync(path.join(modDir, `${a.assetName}.json`)));
    return { ...a, hasTexture };
  });

  const zipPath = path.join(packRoot, "dist", "TrinityForge-Pack.zip");
  let build = null;
  if (fs.existsSync(zipPath)) {
    const buf = fs.readFileSync(zipPath);
    const stat = fs.statSync(zipPath);
    build = {
      path: zipPath,
      size: buf.length,
      sha1: crypto.createHash("sha1").update(buf).digest("hex"),
      mtime: stat.mtime.toISOString()
    };
  }

  return {
    allocations: rows,
    reserved: [...RESERVED_CMDS],
    build,
    registryCorrupt: corrupt
  };
}

module.exports = {
  writeTexture,
  writeModel,
  rewriteMovedModels,
  regenerateItemDefinitions,
  buildPack,
  status,
  previewInfo,
  normalizeAssetName,
  inferParent,
  // バニラ構造の保持まわり(テストと一括移行スクリプトから使う)。
  vanillaLeafModels,
  leafAssetName,
  generatedLeafModel,
  entryModelFor
};
