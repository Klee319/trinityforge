"use strict";

// CMD (CustomModelData) 自動割当 + リソースパック自動生成の express ルート定義。
// server.js からの分離目的は行数制限 (800行) 遵守のため。
// ctx = { readEntryById, resourcePackRoot, cmdRegistryPath } は server.js 側の toolConfig 変更
// (PUT /api/settings での再読込) に追従できるよう、値ではなく毎回呼び出す関数として渡す。

const CmdRegistry = require("./cmd-registry");
const CmdRemoval = require("./cmd-removal");
const Respack = require("./respack");
const RespackPublish = require("./respack-publish");

// GET /api/cmd/usage, POST /api/cmd/allocate, POST/GET /api/respack/* を app に登録する。
function registerCmdRoutes(app, ctx) {
  app.get("/api/cmd/usage", (req, res) => {
    try {
      const usage = CmdRegistry.scanUsage(ctx.readEntryById);
      const { registry, corrupt } = CmdRegistry.loadRegistrySafe(ctx.cmdRegistryPath());
      res.json({
        usage,
        reserved: [...CmdRegistry.RESERVED_CMDS],
        allocations: registry.allocations,
        registryCorrupt: corrupt
      });
    } catch (err) {
      res.status(500).json({ error: `CMD使用状況の取得に失敗しました: ${err.message}` });
    }
  });

  app.post("/api/cmd/allocate", (req, res) => {
    const body = req.body || {};
    const material = typeof body.material === "string" ? body.material.trim().toUpperCase() : "";
    if (!material || !/^[A-Z0-9_]+$/.test(material)) {
      return res.status(400).json({ error: "material は大文字英数字・アンダースコアのみの必須項目です" });
    }
    try {
      const { cmd } = CmdRegistry.allocate(
        ctx.cmdRegistryPath(),
        { material, id: body.id, source: body.source },
        ctx.readEntryById
      );
      res.json({ cmd });
    } catch (err) {
      // H-2: 台帳破損は操作を拒否する。専用エラー型なら500、それ以外(material不正等)も500だが
      // メッセージで区別できるようにする。
      res.status(500).json({ error: `CMDの自動割当に失敗しました: ${err.message}` });
    }
  });

  // CMD一括割当。items: [{material, id, source}] を受け取り、台帳ロード/保存を各1回で
  // まとめて採番する (単発 allocate の並列呼び出しは台帳競合するため禁止)。
  app.post("/api/cmd/allocate-bulk", (req, res) => {
    const body = req.body || {};
    const rawItems = Array.isArray(body.items) ? body.items : null;
    if (!rawItems || rawItems.length === 0) {
      return res.status(400).json({ error: "items は1件以上の配列で指定してください" });
    }
    const items = [];
    for (const it of rawItems) {
      const material = it && typeof it.material === "string" ? it.material.trim().toUpperCase() : "";
      if (!material || !/^[A-Z0-9_]+$/.test(material)) {
        return res.status(400).json({
          error: `material は大文字英数字・アンダースコアのみの必須項目です (id=${(it && it.id) || "?"})`
        });
      }
      items.push({ material, id: it.id, source: it.source });
    }
    try {
      const { results } = CmdRegistry.allocateBulk(ctx.cmdRegistryPath(), items, ctx.readEntryById);
      res.json({ results });
    } catch (err) {
      res.status(500).json({ error: `CMDの一括割当に失敗しました: ${err.message}` });
    }
  });

  app.post("/api/respack/texture", (req, res) => {
    const body = req.body || {};
    const material = typeof body.material === "string" ? body.material.trim().toUpperCase() : "";
    const cmd = Number(body.cmd);
    const id = typeof body.id === "string" ? body.id : "";
    const parent = body.parent;
    // A: 任意の {frametime, interpolate} を指定するとアニメーションテクスチャ(.png.mcmeta)として登録する。
    const animation = body.animation && typeof body.animation === "object" ? body.animation : undefined;
    let pngBuffer;
    try {
      pngBuffer = Buffer.from(String(body.pngBase64 || ""), "base64");
    } catch (err) {
      return res.status(400).json({ error: "pngBase64 のデコードに失敗しました" });
    }
    try {
      const result = Respack.writeTexture(
        { material, cmd, id, pngBuffer, parent, animation },
        ctx.resourcePackRoot(),
        ctx.cmdRegistryPath()
      );
      res.json(result);
    } catch (err) {
      // H-2: 台帳破損によるエラーは (入力不正の400ではなく) 500として区別する。
      if (err && err.registryCorrupt) {
        return res.status(500).json({ error: err.message });
      }
      res.status(400).json({ error: `テクスチャの登録に失敗しました: ${err.message}` });
    }
  });

  // B: カスタムモデルJSON(上級者向け)の登録。modelJson + 0件以上のtextures[]を受け取り、
  // 自動生成モデルの代わりに modelJson をそのまま配線する。
  app.post("/api/respack/model", (req, res) => {
    const body = req.body || {};
    const material = typeof body.material === "string" ? body.material.trim().toUpperCase() : "";
    const cmd = Number(body.cmd);
    const id = typeof body.id === "string" ? body.id : "";
    const parent = body.parent;
    let modelJson;
    try {
      modelJson = typeof body.modelJson === "string" ? JSON.parse(body.modelJson) : body.modelJson;
    } catch (err) {
      return res.status(400).json({ error: `modelJson のパースに失敗しました: ${err.message}` });
    }
    let textures;
    try {
      textures = (Array.isArray(body.textures) ? body.textures : []).map((t) => ({
        name: t && t.name,
        pngBuffer: Buffer.from(String((t && t.pngBase64) || ""), "base64")
      }));
    } catch (err) {
      return res.status(400).json({ error: "textures[].pngBase64 のデコードに失敗しました" });
    }
    try {
      const result = Respack.writeModel(
        { material, cmd, id, modelJson, textures, parent },
        ctx.resourcePackRoot(),
        ctx.cmdRegistryPath()
      );
      res.json(result);
    } catch (err) {
      if (err && err.registryCorrupt) {
        return res.status(500).json({ error: err.message });
      }
      // Blockbench変換の失敗は原因が複数個あるので、UIが箇条書きで出せるよう配列も返す。
      if (err && Array.isArray(err.conversionErrors)) {
        return res.status(400).json({ error: err.message, conversionErrors: err.conversionErrors });
      }
      res.status(400).json({ error: `カスタムモデルの登録に失敗しました: ${err.message}` });
    }
  });

  app.post("/api/respack/build", (req, res) => {
    try {
      const result = Respack.buildPack(ctx.resourcePackRoot());
      res.json(result);
    } catch (err) {
      res.status(500).json({ error: `リソースパックのビルドに失敗しました: ${err.message}` });
    }
  });

  // GitHub release への公開 (再ビルド→gh release create→配布URL/sha1返却)。
  app.post("/api/respack/publish", (req, res) => {
    try {
      const result = RespackPublish.publish(ctx.resourcePackRoot(), ctx.packRepo ? ctx.packRepo() : "");
      res.json(result);
    } catch (err) {
      res.status(500).json({ error: `GitHub releaseへの公開に失敗しました: ${err.message}` });
    }
  });

  // 個別登録解除: (material,cmd) を参照している全 config ファイルから該当エントリを削除し、
  // 台帳・リソパ定義を同期する。孤児(item-statsだけが参照)はもちろん、カタログ等に実体が
  // 残っている場合もその定義ごと削除される(利用者が1件ずつ手動で選ぶ危険承知の操作)。
  app.post("/api/respack/unregister", (req, res) => {
    const body = req.body || {};
    const material = typeof body.material === "string" ? body.material.trim().toUpperCase() : "";
    const cmd = Number(body.cmd);
    if (!material || !/^[A-Z0-9_]+$/.test(material) || !Number.isInteger(cmd) || cmd <= 0) {
      return res.status(400).json({ error: "material (大文字英数字) と正の整数 cmd を指定してください" });
    }
    try {
      const removed = removeAllocationEverywhere(ctx, material, cmd);
      if (removed.length === 0) {
        // fork の Java が material と CMD をハードコードしているアイテムは、どの yml にも
        // 実体が無いので上の走査では1件も当たらない。この場合は台帳の行だけを落として
        // 「リソパの配線を外す」(アイテム自体は fork 側に残る) 意味の登録解除にする。
        removed.push(...removeExternalAllocation(ctx, material, cmd));
      }
      if (removed.length === 0) {
        return res.status(404).json({ error: `${material}#${cmd} を参照するconfigが見つかりませんでした` });
      }
      const syncWarning = syncCmdRegistryAfterSave(ctx);
      res.json({ removed, ledgerSynced: !syncWarning, warning: syncWarning || null });
    } catch (err) {
      res.status(500).json({ error: `登録解除に失敗しました: ${err.message}` });
    }
  });

  // カタログ削除の一括反映: authoritativeな定義ファイルには存在せず item-stats だけが
  // 参照している孤児(=カタログ等から削除済みなのにstat参照だけ残った行)を一括で掃除する。
  app.post("/api/respack/prune-orphans", (req, res) => {
    try {
      const usage = CmdRegistry.scanUsage(ctx.readEntryById);
      const orphans = CmdRemoval.findOrphanAllocations(usage);
      if (orphans.length === 0) {
        return res.json({ removed: [], count: 0, ledgerSynced: true, warning: null });
      }
      // 孤児は参照専用(item-stats)のみが参照。item-stats を1回読み全孤児キーを除去して1回で保存。
      let data = ctx.readEntryById("item-stats");
      const removed = [];
      if (data) {
        for (const o of orphans) {
          const r = CmdRemoval.removeAllocationFromData("item-stats", data, o.material, o.cmd);
          if (r.removed.length > 0) {
            data = r.data;
            removed.push(...r.removed.map((x) => ({ ...x, material: o.material, cmd: o.cmd })));
          }
        }
        if (removed.length > 0) ctx.writeEntry("item-stats", data);
      }
      const syncWarning = syncCmdRegistryAfterSave(ctx);
      res.json({ removed, count: removed.length, ledgerSynced: !syncWarning, warning: syncWarning || null });
    } catch (err) {
      res.status(500).json({ error: `カタログ削除の一括反映に失敗しました: ${err.message}` });
    }
  });

  app.get("/api/respack/status", (req, res) => {
    try {
      res.json(Respack.status(ctx.resourcePackRoot(), ctx.cmdRegistryPath()));
    } catch (err) {
      res.status(500).json({ error: `リソースパック状態の取得に失敗しました: ${err.message}` });
    }
  });

  // 配線済み (material, cmd) のテクスチャプレビュー (モデルJSONが参照するPNGをbase64で返す)。
  app.get("/api/respack/preview", (req, res) => {
    const material = typeof req.query.material === "string" ? req.query.material.trim().toUpperCase() : "";
    const cmd = Number(req.query.cmd);
    if (!material || !/^[A-Z0-9_]+$/.test(material) || !Number.isInteger(cmd) || cmd <= 0) {
      return res.status(400).json({ error: "material (大文字英数字) と正の整数 cmd を指定してください" });
    }
    try {
      res.json(Respack.previewInfo(ctx.resourcePackRoot(), ctx.cmdRegistryPath(), material, cmd));
    } catch (err) {
      res.status(500).json({ error: `テクスチャプレビューの取得に失敗しました: ${err.message}` });
    }
  });
}

// catalog↔item-stats や catalog↔materials/spellbooks のような「同一アイテムの正規の別表現」を
// クロスファイル重複警告から除外するための判定 (H-1)。
// - item-stats は「アイテム定義」ではなく既存(material,cmd)への参照専用ファイルなので、
//   item-stats を含むペアは常に除外する。
// - catalog↔materials: editorのカタログ⇔中間素材ファイル間移動機能が両方に同一アイテムを作る。
// - catalog↔spellbooks: spell_book_* (books) も触媒 (catalysts) も、catalogとspellbooksの両方に
//   同一(material,cmd)で存在するのが正規形。判定は base (":"より前) で行うため catalysts も除外される。
const MIRROR_PAIRS = [
  ["catalog", "materials"],
  ["catalog", "spellbooks"]
];
function isMirrorPair(configId, otherFileTag) {
  return MIRROR_PAIRS.some(([a, b]) => (configId === a && otherFileTag === b) || (configId === b && otherFileTag === a));
}

// CMD (CustomModelData) を含む config 保存時のクロスファイル衝突検査。
// 「保存前から既にあった衝突」(予約値のグローバル意図的再利用や、他ファイルとの既存の重複)は
// 毎回警告すると邪魔になるため、今回の保存で新規に発生した衝突のみを警告として返す(ブロックしない)。
function computeCmdWarnings(ctx, configId, oldData, newData) {
  // H-1: item-stats は「参照」専用ファイルなので、そもそも自分自身が保存されたときは
  // クロスファイル重複警告の対象外 (常に空を返す)。
  if (configId === "item-stats") return [];

  const usageAfter = CmdRegistry.scanUsage(ctx.readEntryById); // 保存後の全ファイル状態
  const otherHas = (material, cmd) => usageAfter.some((u) => {
    if (u.material !== material || u.cmd !== cmd) return false;
    return u.sources.some((s) => {
      const base = s.file.split(":")[0];
      if (base === configId) return false; // 自分自身 (spellbooks保存時のspellbooks:catalystsも含む)
      if (base === "item-stats") return false; // H-1: item-statsは重複判定に一切使わない
      if (isMirrorPair(configId, base)) return false; // H-1: 既知の意図的ミラーペア (base同士で比較。catalog↔spellbooks:catalystsも除外)
      return true;
    });
  });
  const reservedHas = (cmd) => CmdRegistry.RESERVED_CMDS.has(cmd);
  const conflictKey = (p) => `${p.material}#${p.cmd}`;

  const selfBefore = CmdRegistry.usageForFile(configId, oldData || {});
  const selfAfter = CmdRegistry.usageForFile(configId, newData || {});
  const oldConflicts = new Set(
    selfBefore.filter((p) => reservedHas(p.cmd) || otherHas(p.material, p.cmd)).map(conflictKey)
  );

  const warnings = [];
  const seen = new Set();
  for (const p of selfAfter) {
    if (!(reservedHas(p.cmd) || otherHas(p.material, p.cmd))) continue;
    const key = conflictKey(p);
    if (oldConflicts.has(key) || seen.has(key)) continue;
    seen.add(key);
    const reason = reservedHas(p.cmd) ? "予約済みCMD(Java側ハードコード)" : "他ファイルと重複";
    warnings.push(`${p.material}#${p.cmd} (${p.id || "?"}) は${reason}です`);
  }
  return warnings;
}

// (material,cmd) を参照している全 CMD スキャン対象ファイルから該当エントリを削除し、
// 変更のあったファイルだけを書き戻す。戻り値: 削除された [{file,id}] の配列。
// 台帳同期・リソパ定義再生成は呼び出し側で syncCmdRegistryAfterSave により行う。
function removeAllocationEverywhere(ctx, material, cmd) {
  const removed = [];
  for (const configId of CmdRegistry.CMD_SCAN_IDS) {
    const data = ctx.readEntryById(configId);
    if (!data) continue;
    const r = CmdRemoval.removeAllocationFromData(configId, data, material, cmd);
    if (r.removed.length > 0) {
      ctx.writeEntry(configId, r.data);
      removed.push(...r.removed);
    }
  }
  return removed;
}

// スキャン対象の config には実体が無い外部由来(fork の Java ハードコード等)の割当を
// 台帳から落とす。scanUsage に現れない行なので、config を書き換える経路では消せない。
// 戻り値: 削除された [{file,id}] の配列 (該当なしなら空)。
function removeExternalAllocation(ctx, material, cmd) {
  const registryPath = ctx.cmdRegistryPath();
  const registry = CmdRegistry.loadRegistry(registryPath);
  const list = Array.isArray(registry.allocations) ? registry.allocations : [];
  const kept = [];
  const removed = [];
  for (const allocation of list) {
    if (allocation.material === material && allocation.cmd === cmd
        && CmdRegistry.isExternalSource(allocation.source)) {
      removed.push({ file: String(allocation.source), id: allocation.id || "(no-id)" });
      continue;
    }
    kept.push(allocation);
  }
  if (removed.length === 0) return [];
  CmdRegistry.saveRegistry(registryPath, { ...registry, allocations: kept });
  return removed;
}

// CMD台帳を保存後の全config状態へ同期する (未登録分の自動追記)。
// config自体の保存は既に成功しているため、台帳が壊れていても保存結果は失敗にしない。
// 代わりに破損メッセージを返し、呼び出し側 (server.js) が cmdWarnings へ含める (H-2)。
function syncCmdRegistryAfterSave(ctx) {
  try {
    const usage = CmdRegistry.scanUsage(ctx.readEntryById);
    const registry = CmdRegistry.loadRegistry(ctx.cmdRegistryPath());
    const { registry: nextRegistry, moved } = CmdRegistry.reconcileWithUsageDetailed(usage, registry);
    CmdRegistry.saveRegistry(ctx.cmdRegistryPath(), nextRegistry);
    // material だけ差し替えられた行は、自動生成モデルの parent が旧 material を指したまま残る。
    // item定義を再生成する前に、新しい material のバニラリーフへ貼り直す。
    Respack.rewriteMovedModels(ctx.resourcePackRoot(), moved);
    // 台帳から外れたモデルは item定義からも除去する。PNG等の生アセットは安全のため消さず、
    // 必要なら再利用できる状態で残す（共有テクスチャを誤削除しない）。
    Respack.regenerateItemDefinitions(ctx.resourcePackRoot(), ctx.cmdRegistryPath());
    return null;
  } catch (err) {
    return err && err.registryCorrupt ? err.message : `CMD台帳の同期に失敗しました: ${err.message}`;
  }
}

module.exports = { registerCmdRoutes, computeCmdWarnings, syncCmdRegistryAfterSave };
