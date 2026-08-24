"use strict";

// CMD (CustomModelData) 台帳の管理。
// 台帳ファイル (resourcepack/cmd-registry.json) は「どのアイテムがどの CMD を使ったか」の
// 履歴を蓄積する。一度払い出した番号は変更・再利用しない (プレイヤー所持品が壊れるため)。
//
// このモジュールは Node 標準の fs のみを使い、ブラウザ非依存。server.js とテストの双方から
// 台帳パス・reader を明示的に渡して呼ぶ (グローバル状態を持たない)。

const fs = require("fs");
const path = require("path");

// Java 側でハードコードされ、かつ「エディタが走査する TF config に現れない」CMD だけを予約する。
// 目的: config スキャン(scanUsage) では拾えない fork 由来アイテムの CMD を、自動採番の衝突回避と
// 手動編集時の警告に反映するため。
//
// ★ここには「TF config に定義があるアイテムの CMD」は入れない。
//   catalog.yml 等に載っているアイテム(魔導書=spell_book_*, ドミニオンワンド, テレポートコンパス,
//   ソースベリー 等)は scanUsage(usageList) と CMD台帳(allocations) の両方で既に衝突回避・警告され
//   るため、ここへ重複させる必要がない(2026-07-24 に 100001/100002/100003/100010/100020 を除去)。
//
// 残す = config には現れない fork 専用アイテム:
//   - メイジ防具のベース等 (100011, 100012)
//   - 筆記台/儀式の核/台座/ウェイストーン 等の fork ブロック (200001, 300001, 300002, 400001)
//   - メイジ防具セット・スレッド等 (200002-200007, 300003-300016)
//   - 無限ソース核 (500001) — 2026-08-24 追加。他の fork ブロックと同じく Java の
//     getCustomModelData() でしか決まらないのに予約から漏れていた(自動採番が奪い得た)。
// これらは fork の resources/Java でのみ CMD が決まり、TF config スキャンでは検出できないため予約が必要。
const RESERVED_CMDS = new Set([
  100011, 100012,
  200001, 200002, 200003, 200004, 200005, 200006, 200007,
  300001, 300002, 300003, 300004, 300005, 300006, 300007, 300008,
  300009, 300010, 300011, 300012, 300013, 300014, 300015, 300016,
  400001,
  500001
]);

const MAX_CMD = 9999999; // schema.js の ITEM_STATS_KEY_RE (1〜7桁) に合わせる

// Paper 1.21.11 実在Material一覧 (L-2: allocate/writeTexture の material 検証に使う)。
const VALID_MATERIALS = new Set((require("./materials-1.21.11.json").materials) || []);

function isValidMaterial(material) {
  return typeof material === "string" && VALID_MATERIALS.has(material.toUpperCase());
}

// 台帳ファイル破損 (JSON parse失敗 / 想定外シェイプ) を表す専用エラー。
// server.js側でこの型を見て HTTP 500 に振り分ける (H-2)。
class RegistryCorruptError extends Error {
  constructor(message) {
    super(message);
    this.name = "RegistryCorruptError";
    this.registryCorrupt = true;
  }
}

function emptyRegistry() {
  return { version: 1, allocations: [] };
}

function corruptMessage(registryPath) {
  return `台帳ファイルが破損しています。.bakから復旧してください: ${registryPath}`;
}

// 台帳を読む。ファイルが無ければ空台帳を返す。存在するが壊れている(JSON parse失敗/シェイプ不正)
// 場合は RegistryCorruptError を投げる (H-2: 破損を握りつぶして番号を再利用させない)。
// allocate / writeTexture / regenerateItemDefinitions など「書込に繋がる経路」はこちらを使う。
function loadRegistry(registryPath) {
  if (!fs.existsSync(registryPath)) return emptyRegistry();
  let raw;
  try {
    raw = fs.readFileSync(registryPath, "utf8");
  } catch (err) {
    throw new RegistryCorruptError(corruptMessage(registryPath));
  }
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (err) {
    throw new RegistryCorruptError(corruptMessage(registryPath));
  }
  if (!parsed || typeof parsed !== "object" || !Array.isArray(parsed.allocations)) {
    throw new RegistryCorruptError(corruptMessage(registryPath));
  }
  return { version: parsed.version || 1, allocations: parsed.allocations };
}

// 読み取り専用の一覧表示 (usage/status) 用。壊れていても例外を投げず、
// { registry: 空台帳, corrupt: true } を返す (UI側で破損警告を出す)。
function loadRegistrySafe(registryPath) {
  try {
    return { registry: loadRegistry(registryPath), corrupt: false };
  } catch (err) {
    return { registry: emptyRegistry(), corrupt: true };
  }
}

// 台帳を保存する。上書き前に既存ファイルを1つだけ .bak として残す (単純な原子的書込)。
// 既存ファイルが壊れている場合は .bak を上書きせず例外を投げる (H-2: 正常な .bak を破壊しない)。
// 実際には loadRegistry が先に呼ばれる経路しか無いため、壊れていればここに到達する前に
// 例外で止まる設計だが、直接 saveRegistry を呼ぶ経路が増えた場合の防御としても機能させる。
function saveRegistry(registryPath, registry) {
  fs.mkdirSync(path.dirname(registryPath), { recursive: true });
  if (fs.existsSync(registryPath)) {
    const raw = fs.readFileSync(registryPath, "utf8");
    try {
      JSON.parse(raw);
    } catch (err) {
      throw new RegistryCorruptError(
        `台帳ファイルが破損しているため上書きできません。.bakから復旧してください: ${registryPath}`
      );
    }
    try {
      fs.copyFileSync(registryPath, `${registryPath}.bak`);
    } catch (_) {
      // バックアップ失敗は保存自体を妨げない
    }
  }
  const tmp = `${registryPath}.tmp-${process.pid}-${Date.now()}`;
  const output = JSON.stringify(registry, null, 2) + "\n";
  fs.writeFileSync(tmp, output, "utf8");
  fs.renameSync(tmp, registryPath);
}

// ---- config 群からの CMD 使用状況スキャン ----
// キーは registry.js の config id と一致させる (PUT /api/config/:id の req.params.id と
// そのまま突き合わせられるようにするため)。
const SCANNERS = {
  catalog: scanCatalog,
  "item-stats": scanItemStats,
  materials: scanMaterials,
  spellbooks: scanSpellbooks,
  "external-items": scanExternalItems,
  sourcejars: scanSourceJars,
  sourcelinks: scanSourceLinks
};
const CMD_SCAN_IDS = Object.keys(SCANNERS);
const CMD_SCAN_ID_SET = new Set(CMD_SCAN_IDS);

// 台帳の行が「CMDスキャン対象の config には実体が無い外部由来」かどうか。
// fork の Java が material と CMD をハードコードしているアイテム (functional-items の
// infinity_source_core など) が該当し、どの yml にも書かれていないので scanUsage には
// 絶対に現れない。reconcile がこれを usage 起点の行と同じ扱いで落とすと、
// 次に誰かが editor で config を1つ保存した瞬間にリソパの配線ごと黙って消える。
// source 未設定(null)は usage 起点の行なので外部扱いしない(従来どおり usage に従う)。
function isExternalSource(source) {
  const base = String(source == null ? "" : source).split(":")[0];
  if (!base) return false;
  return !CMD_SCAN_ID_SET.has(base);
}

// readEntryFn(configId) は上記 id を受け取り、パース済み YAML データ (無ければ null) を返す。
// 戻り値: [{material, cmd, sources:[{file, id}]}]  (同一 (material,cmd) は sources に集約)
function scanUsage(readEntryFn) {
  const map = new Map(); // key: `${material}#${cmd}` -> entry
  function record(material, cmd, file, id) {
    if (!Number.isInteger(cmd)) return;
    const mat = material && typeof material === "string" ? material.toUpperCase() : "UNKNOWN";
    const key = `${mat}#${cmd}`;
    let entry = map.get(key);
    if (!entry) {
      entry = { material: mat, cmd, sources: [] };
      map.set(key, entry);
    }
    entry.sources.push({ file, id });
  }

  for (const configId of CMD_SCAN_IDS) {
    SCANNERS[configId](readEntryFn(configId), record);
  }

  return [...map.values()];
}

// 1ファイル分だけをスキャンする (保存前後の差分検出用)。戻り値はグループ化なしのフラットな配列。
function usageForFile(configId, data) {
  const scanner = SCANNERS[configId];
  if (!scanner) return [];
  const list = [];
  scanner(data, (material, cmd, file, id) => {
    if (!Number.isInteger(cmd)) return;
    const mat = material && typeof material === "string" ? material.toUpperCase() : "UNKNOWN";
    list.push({ material: mat, cmd, file, id });
  });
  return list;
}

function scanCatalog(data, record) {
  if (!data || typeof data !== "object" || !data.items) return;
  for (const [id, entry] of Object.entries(data.items)) {
    if (!entry || typeof entry !== "object") continue;
    if (Number.isInteger(entry["custom-model-data"])) {
      record(entry.material, entry["custom-model-data"], "catalog", id);
    }
  }
}

// item-stats のキーは "MATERIAL" または "MATERIAL#CMD"。CMD 付きのみ集計対象。
const ITEM_STATS_CMD_KEY_RE = /^([A-Z0-9_]+)#(\d{1,7})$/;
function scanItemStats(data, record) {
  if (!data || typeof data !== "object" || !data.items) return;
  for (const key of Object.keys(data.items)) {
    const m = ITEM_STATS_CMD_KEY_RE.exec(key);
    if (!m) continue;
    record(m[1], Number(m[2]), "item-stats", key);
  }
}

function scanMaterials(data, record) {
  if (!data || typeof data !== "object" || !data.materials) return;
  for (const [id, entry] of Object.entries(data.materials)) {
    if (!entry || typeof entry !== "object") continue;
    if (Number.isInteger(entry.custom_model_data)) {
      record(entry.base_material, entry.custom_model_data, "materials", id);
    }
  }
}

function scanExternalItems(data, record) {
  const items = data && data.items && typeof data.items === "object" ? data.items : {};
  for (const [id, entry] of Object.entries(items)) {
    if (entry && typeof entry === "object" && Number.isInteger(entry["custom-model-data"])) {
      record(entry.material, entry["custom-model-data"], "external-items", id);
    }
  }
}

// spellbooks.yml: spell-books[] (material は SpellBook.getBaseMaterial() で Material.BOOK に
// ハードコードされておりyml上にフィールドが無いため、ここで固定値として補う) + catalysts{}。
function scanSpellbooks(data, record) {
  if (!data || typeof data !== "object") return;
  const books = Array.isArray(data["spell-books"]) ? data["spell-books"] : [];
  for (const b of books) {
    if (!b || typeof b !== "object") continue;
    if (Number.isInteger(b["custom-model-data"])) {
      record("BOOK", b["custom-model-data"], "spellbooks", b.id || "(no-id)");
    }
  }
  const catalysts = data.catalysts && typeof data.catalysts === "object" ? data.catalysts : {};
  for (const [id, entry] of Object.entries(catalysts)) {
    if (!entry || typeof entry !== "object") continue;
    if (Number.isInteger(entry["custom-model-data"])) {
      record(entry.material, entry["custom-model-data"], "spellbooks:catalysts", id);
    }
  }
}

function scanSourceJars(data, record) {
  if (!data || typeof data !== "object" || !data.jars) return;
  for (const [id, entry] of Object.entries(data.jars)) {
    if (!entry || typeof entry !== "object") continue;
    if (Number.isInteger(entry["custom-model-data"])) {
      record(entry.material, entry["custom-model-data"], "sourcejars", id);
    }
  }
}

function scanSourceLinks(data, record) {
  if (!data || typeof data !== "object" || !data.items) return;
  for (const [id, entry] of Object.entries(data.items)) {
    if (!entry || typeof entry !== "object") continue;
    if (Number.isInteger(entry["custom-model-data"])) {
      record(entry.material, entry["custom-model-data"], "sourcelinks", id);
    }
  }
}

// ---- 次の空き CMD を求める ----
// 開始点はその material の使用済み最大値(config+台帳)+1 (無ければ1)。
// 衝突判定は安全側に「全 material の使用値(UNKNOWNを含む)」+ 予約値を対象にする
// (誤って material を空欄のまま払い出した場合の事故を避けるため)。
function nextCmd(material, usage, registry) {
  const mat = String(material || "").toUpperCase();
  const usageList = Array.isArray(usage) ? usage : [];
  const allocations = registry && Array.isArray(registry.allocations) ? registry.allocations : [];

  // MAX_CMD 超えの不正値 (手入力事故など) が config に混ざっていても連番の起点を壊さないよう、
  // 上限内の値だけを起点計算に使う (衝突集合 globalUsed には引き続き含める)。
  let start = 0;
  for (const u of usageList) {
    if (u.material === mat && u.cmd > start && u.cmd <= MAX_CMD) start = u.cmd;
  }
  for (const a of allocations) {
    if (a.material === mat && a.cmd > start && a.cmd <= MAX_CMD) start = a.cmd;
  }
  start += 1;
  if (start < 1) start = 1;

  const globalUsed = new Set();
  for (const u of usageList) globalUsed.add(u.cmd);
  for (const a of allocations) globalUsed.add(a.cmd);

  let candidate = start;
  while (RESERVED_CMDS.has(candidate) || globalUsed.has(candidate)) {
    candidate += 1;
    if (candidate > MAX_CMD) {
      throw new Error(`CMD の割当上限(${MAX_CMD})を超過しました (material=${mat})`);
    }
  }
  if (candidate > MAX_CMD) {
    throw new Error(`CMD の割当上限(${MAX_CMD})を超過しました (material=${mat})`);
  }
  return candidate;
}

// 新しい CMD を1つ払い出し、台帳へ記録して保存する。
function allocate(registryPath, { material, id, source }, readEntryFn) {
  if (!material || typeof material !== "string" || !/^[A-Z0-9_]+$/.test(material)) {
    throw new Error("material は大文字英数字・アンダースコアのみである必要があります");
  }
  if (!isValidMaterial(material)) {
    throw new Error(`material "${material}" は Paper 1.21.11 に実在しないMaterialです`);
  }
  const registry = loadRegistry(registryPath);
  const usage = scanUsage(readEntryFn);
  const cmd = nextCmd(material, usage, registry);
  const nextRegistry = {
    version: registry.version || 1,
    allocations: [
      ...registry.allocations,
      {
        material: material.toUpperCase(),
        cmd,
        id: id || null,
        source: source || null,
        allocatedAt: new Date().toISOString()
      }
    ]
  };
  saveRegistry(registryPath, nextRegistry);
  return { cmd, registry: nextRegistry };
}

// 複数アイテムへ CMD をまとめて払い出す (一括登録ボタン用)。
// 台帳ロード・configスキャン・保存を各1回に抑え、割当は直列に行う
// (allocate を並列に叩くと scanUsage/saveRegistry が競合するため、一括はこちらを使う)。
// requests: [{material, id, source}] / 戻り値: { results: [{id, material, cmd}], registry }
function allocateBulk(registryPath, requests, readEntryFn) {
  const list = Array.isArray(requests) ? requests : [];
  for (const r of list) {
    const material = r && r.material;
    if (!material || typeof material !== "string" || !/^[A-Z0-9_]+$/.test(material)) {
      throw new Error(`material は大文字英数字・アンダースコアのみである必要があります (id=${(r && r.id) || "?"})`);
    }
    if (!isValidMaterial(material)) {
      throw new Error(`material "${material}" は Paper 1.21.11 に実在しないMaterialです (id=${(r && r.id) || "?"})`);
    }
  }
  const registry = loadRegistry(registryPath);
  const usage = scanUsage(readEntryFn);
  const allocations = [...registry.allocations];
  const results = [];
  for (const r of list) {
    const cmd = nextCmd(r.material, usage, { allocations });
    allocations.push({
      material: r.material.toUpperCase(),
      cmd,
      id: r.id || null,
      source: r.source || null,
      allocatedAt: new Date().toISOString()
    });
    results.push({ id: r.id || null, material: r.material.toUpperCase(), cmd });
  }
  const nextRegistry = { version: registry.version || 1, allocations };
  if (results.length > 0) {
    saveRegistry(registryPath, nextRegistry);
  }
  return { results, registry: nextRegistry };
}

// config に存在するが台帳に無い (material,cmd) を台帳へ追記する。既存行は id を更新するのみ。
// 純関数 (registry を書き換えず新しいオブジェクトを返す)。呼び出し側で saveRegistry すること。
function adoptFromConfigs(usage, registry) {
  const base = registry && Array.isArray(registry.allocations) ? registry.allocations : [];
  const byKey = new Map();
  for (const a of base) byKey.set(`${a.material}#${a.cmd}`, { ...a });

  for (const u of Array.isArray(usage) ? usage : []) {
    const key = `${u.material}#${u.cmd}`;
    const primarySource = u.sources && u.sources[0] ? u.sources[0] : { file: null, id: null };
    const existing = byKey.get(key);
    if (existing) {
      byKey.set(key, { ...existing, id: existing.id || primarySource.id, source: existing.source || primarySource.file });
    } else {
      byKey.set(key, {
        material: u.material,
        cmd: u.cmd,
        id: primarySource.id || null,
        source: primarySource.file || null,
        allocatedAt: new Date().toISOString()
      });
    }
  }

  return { version: (registry && registry.version) || 1, allocations: [...byKey.values()] };
}

// 台帳を「現存するconfigのCMD」へ完全同期する。削除済みアイテムの行を残すと、
// リソースパック定義にも残ってしまうため、履歴台帳ではなく現行の配線台帳として扱う。
// 同一(material,cmd)の assetName/customModel 等は維持し、由来/idだけ最新のconfigへ正規化する。
//
// 2026-08-22: editor で material だけを差し替える（鎌を HOE から SWORD へ、など）と
// (material,cmd) が別キーになるため、この行は「新規の未配線行」として扱われ
// assetName/parent が無言で消えていた。台帳が assetName を失うと
// regenerateItemDefinitions が「未配線」と判断してバニラモデルの entry を書くので、
// **アセットは1つも消えていないのにテクスチャの割り当てだけが外れる**。
// キーが変わっただけの行は id で引き継ぐ（下記 detailed 実装を参照）。
function reconcileWithUsage(usage, registry) {
  return reconcileWithUsageDetailed(usage, registry).registry;
}

// reconcileWithUsage の本体。引き継ぎが起きた行を moved で返すので、
// 呼び出し側は「新しい material のバニラリーフへモデルを貼り直す」処理を続けられる。
// 戻り値: { registry, moved: [{id, assetName, customModel, from:{material,cmd}, to:{material,cmd}}] }
// registry には moved を含めない（saveRegistry がそのまま JSON 化するため）。
function reconcileWithUsageDetailed(usage, registry) {
  const priorList = (registry && registry.allocations) || [];
  const entries = Array.isArray(usage) ? usage : [];

  const existing = new Map();
  for (const allocation of priorList) {
    existing.set(`${allocation.material}#${allocation.cmd}`, allocation);
  }
  const nextKeys = new Set(entries.map((entry) => `${entry.material}#${entry.cmd}`));

  // fork の Java 由来など、スキャン対象の config には実体が無い行は usage で判断できない。
  // usage に同じ (material,cmd) が現れていない限りそのまま残す(消せるのは登録解除だけ)。
  const preserved = priorList.filter(
    (allocation) => isExternalSource(allocation.source)
      && !nextKeys.has(`${allocation.material}#${allocation.cmd}`)
  );
  const preservedKeys = new Set(preserved.map((a) => `${a.material}#${a.cmd}`));

  // 引き継ぎ候補 = 「assetName を持ち、かつ元の (material,cmd) が今回の usage に残っていない」行。
  // 元のキーが残っているなら別アイテムがそこに居るということなので移動ではない。
  // 同じ id が複数行にあると引き継ぎ先を決められないため、その id ごと候補から落とす
  // （黙って片方へ寄せると、もう片方が理由不明で未配線になる）。
  const movable = new Map();
  for (const allocation of priorList) {
    const id = allocation.id;
    if (!id || !allocation.assetName) continue;
    if (nextKeys.has(`${allocation.material}#${allocation.cmd}`)) continue;
    // 残す行は移動元にしない(引き継がれると assetName が二重に使われる)。
    if (preservedKeys.has(`${allocation.material}#${allocation.cmd}`)) continue;
    movable.set(id, movable.has(id) ? null : allocation);
  }

  // 既に (material,cmd) 一致で維持される assetName は引き継ぎ先に使えない
  // （別 (material,cmd) と同じ assetName を共有すると、どちらのモデルを書いたか分からなくなる）。
  const claimed = new Set();
  for (const entry of entries) {
    const prior = existing.get(`${entry.material}#${entry.cmd}`);
    if (prior && prior.assetName) claimed.add(prior.assetName);
  }
  for (const allocation of preserved) {
    if (allocation.assetName) claimed.add(allocation.assetName);
  }

  const allocations = [];
  const moved = [];
  for (const entry of entries) {
    const prior = existing.get(`${entry.material}#${entry.cmd}`) || {};
    const primary = entry.sources && entry.sources[0] ? entry.sources[0] : {};
    const id = primary.id || null;

    let carried = null;
    if (!prior.assetName && id) {
      const candidate = movable.get(id);
      if (candidate && !claimed.has(candidate.assetName)) {
        carried = candidate;
        claimed.add(candidate.assetName);
        movable.set(id, null); // 1行にしか引き継がない
      }
    }

    const next = {
      ...prior,
      material: entry.material,
      cmd: entry.cmd,
      id,
      source: primary.file || null,
      allocatedAt: prior.allocatedAt || (carried && carried.allocatedAt) || new Date().toISOString()
    };
    if (carried) {
      next.assetName = carried.assetName;
      if (carried.parent) next.parent = carried.parent;
      if (carried.customModel) next.customModel = carried.customModel;
      moved.push({
        id,
        assetName: carried.assetName,
        customModel: !!carried.customModel,
        from: { material: carried.material, cmd: carried.cmd },
        to: { material: entry.material, cmd: entry.cmd }
      });
    }
    allocations.push(next);
  }
  allocations.push(...preserved);

  return { registry: { version: (registry && registry.version) || 1, allocations }, moved };
}

module.exports = {
  RESERVED_CMDS,
  MAX_CMD,
  CMD_SCAN_IDS,
  RegistryCorruptError,
  isValidMaterial,
  isExternalSource,
  loadRegistry,
  loadRegistrySafe,
  saveRegistry,
  scanUsage,
  usageForFile,
  nextCmd,
  allocate,
  allocateBulk,
  adoptFromConfigs,
  reconcileWithUsage,
  reconcileWithUsageDetailed
};
