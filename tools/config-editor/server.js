"use strict";

const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const express = require("express");

const { REGISTRY, findById } = require("./lib/registry");
const { readConfig, serializeConfig } = require("./lib/yamlio");
const {
  validate,
  validateItemStatsLayerRefs,
  validateSkillTreeLayerRefs
} = require("./lib/schema");
const { CONSTANT_SOURCES, extractConstants, buildUpdatedData, validateConstants } = require("./lib/constants");
const { buildGateVocabulary } = require("./lib/gate-vocabulary");
const { buildTierVocabulary } = require("./lib/tier-vocabulary");
const { SPELL_FORMS } = require("./lib/spell-form-vocabulary");
const { buildMaterialLabels, JA_ITEMS_FILENAME } = require("./lib/materialLabels");
const CmdRegistry = require("./lib/cmd-registry");
const { registerCmdRoutes, computeCmdWarnings, syncCmdRegistryAfterSave } = require("./lib/cmd-routes");

const ROOT = __dirname;
const CONFIG_PATH = path.join(ROOT, "tool-config.json");

// 既定はローカル専用ツールとしてループバックにバインドする。
// external.enabled=true のときのみ、明示オプトインで外部公開する (認証は任意 — resolveBinding 参照)。
const LOOPBACK_HOSTS = ["127.0.0.1", "::1", "localhost"];

// ループバック host を常に IPv4 127.0.0.1 に正規化する (IPv6のみ待受などのブレを防ぐ)。
function resolveLoopback(requested) {
  const host = requested || "127.0.0.1";
  if (host === "127.0.0.1") return host;
  if (!LOOPBACK_HOSTS.includes(host)) {
    process.stderr.write(`[info] host "${host}" は resolveLoopback 対象外です。127.0.0.1 に正規化します。\n`);
  }
  return "127.0.0.1";
}

// 認証情報を解決する。パスワードは環境変数 CONFIG_EDITOR_PASSWORD を最優先で読む
// (config へ平文で書かない運用を推奨)。無ければ tool-config.json の external.auth.password を使う。
function resolveAuth(cfg) {
  const auth = (cfg.external && cfg.external.auth) || {};
  const username = process.env.CONFIG_EDITOR_USER || auth.username || "admin";
  const password = process.env.CONFIG_EDITOR_PASSWORD || auth.password || "";
  return { username: String(username), password: String(password) };
}

// バインド先を決める。外部公開は「明示オプトイン(external.enabled)」でのみ行う。
//
// 【2026-07-26 ユーザー判断により fail-safe を撤去】以前はここで「認証パスワードが空なら外部公開を
// 中止して 127.0.0.1 に強制する」ガードを掛けていた。無認証でのグローバル公開を明示的に選択したため、
// パスワードが空でも要求どおり bindHost へバインドする。警告は出し続ける(黙って公開しない)。
// 元の挙動に戻したい場合は、下の !auth.password ブロックで
// `return { host: "127.0.0.1", external: false };` を返すようにすればよい。
function resolveBinding(cfg, auth) {
  const ext = cfg.external || {};
  if (ext.enabled !== true) {
    return { host: resolveLoopback(cfg.host), external: false };
  }
  const bindHost = typeof ext.bindHost === "string" && ext.bindHost.trim() ? ext.bindHost.trim() : "0.0.0.0";
  if (!auth.password) {
    process.stderr.write(
      `[warn] 認証パスワードが未設定のまま ${bindHost} で外部公開します。到達できる全員が無認証で`
      + "サーバconfigの編集・配備を行える状態です。認証を有効にするには環境変数 CONFIG_EDITOR_PASSWORD"
      + " か external.auth.password を設定してください。\n"
    );
  }
  return { host: bindHost, external: true };
}

function loadToolConfig() {
  const raw = fs.readFileSync(CONFIG_PATH, "utf8");
  const cfg = JSON.parse(raw);
  const auth = resolveAuth(cfg);
  const binding = resolveBinding(cfg, auth);
  return {
    // 環境変数 EDITOR_PORT があれば優先 (ローカル検証で待受ポートを一時変更する用途)。
    // PORT も見るのは、開発用プレビュー(.claude/launch.json)が空きポートを渡してくるため。
    port: Number(process.env.EDITOR_PORT) || Number(process.env.PORT) || Number(cfg.port) || 8787,
    host: binding.host,
    external: binding.external,
    auth,
    basePaths: cfg.basePaths || {},
    // 保存時にミラーする稼働サーバ側 plugins/<Plugin> パス。未設定ならミラーしない。
    deployPaths: cfg.deployPaths || {},
    // CMD台帳・リソースパックソースの配置先 (ROOT相対 or 絶対パス)。未設定なら既定値。
    resourcePackPath: cfg.resourcePackPath || "../../resourcepack",
    // リソースパック配布先の GitHub リポジトリ ("owner/repo")。公開リポであること。
    packRepo: typeof cfg.packRepo === "string" ? cfg.packRepo : "",
    // 保存時自動バックアップの退避先ルート (ROOT相対 or 絶対パス)。
    // 既定値はリポジトリルート直下の backups/ (resources 配下を汚さない・jarに同梱されない)。
    backupDir: cfg.backupDir || "../../backups",
    // ファイルごとに残す世代数の上限。0以下は「無制限」として扱う (安全側: 削除しない)。
    backupKeep: Number.isFinite(Number(cfg.backupKeep)) ? Number(cfg.backupKeep) : 50
  };
}

// リソースパックのルートディレクトリ (絶対パス)。
function resourcePackRoot() {
  const p = toolConfig.resourcePackPath;
  return path.isAbsolute(p) ? p : path.resolve(ROOT, p);
}

function cmdRegistryPath() {
  return path.join(resourcePackRoot(), "cmd-registry.json");
}

// 自動バックアップの退避先ルート (絶対パス)。
function backupRoot() {
  const p = toolConfig.backupDir;
  return path.isAbsolute(p) ? p : path.resolve(ROOT, p);
}

function packRepo() {
  return toolConfig.packRepo;
}

let toolConfig = loadToolConfig();

// registry エントリの絶対パスを解決する。base が未設定なら null。
function resolveAbsPath(entry) {
  const base = toolConfig.basePaths[entry.base];
  if (!base) return null;
  const baseAbs = path.isAbsolute(base) ? base : path.resolve(ROOT, base);
  // rel は registry 固定値のみ。念のため .. を拒否する。
  if (entry.rel.split(/[\\/]/).includes("..")) return null;
  return path.join(baseAbs, entry.rel);
}

// 稼働サーバへのミラー先パス。deployPaths 未設定 / 空なら null。
function resolveDeployAbsPath(entry) {
  const deploy = toolConfig.deployPaths && toolConfig.deployPaths[entry.base];
  if (!deploy || !String(deploy).trim()) return null;
  const baseAbs = path.isAbsolute(deploy) ? deploy : path.resolve(ROOT, deploy);
  if (entry.rel.split(/[\\/]/).includes("..")) return null;
  return path.join(baseAbs, entry.rel);
}

function resolvePathInfo(configured) {
  if (!configured && configured !== "") {
    return { configured: "", absolute: "", exists: false };
  }
  const value = String(configured || "");
  const absolute = value
    ? (path.isAbsolute(value) ? value : path.resolve(ROOT, value))
    : "";
  return {
    configured: value,
    absolute,
    exists: absolute ? fs.existsSync(absolute) : false
  };
}

// SoT 保存後に同じ内容を稼働サーバへミラーする。失敗しても SoT 保存は成功扱い。
function mirrorToDeploy(entry, output, sourceAbs) {
  const deployAbs = resolveDeployAbsPath(entry);
  if (!deployAbs) {
    return { skipped: "not configured" };
  }
  const sourceNorm = path.resolve(sourceAbs);
  const deployNorm = path.resolve(deployAbs);
  if (sourceNorm === deployNorm) {
    return { skipped: "same as base", path: deployAbs };
  }
  try {
    fs.mkdirSync(path.dirname(deployAbs), { recursive: true });
    writeFileAtomic(deployAbs, output);
    return { ok: true, path: deployAbs };
  } catch (err) {
    return { ok: false, path: deployAbs, error: err.message };
  }
}

function timestamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  const ms = String(d.getMilliseconds()).padStart(3, "0");
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}-${ms}`;
}

// entry (registry の {base, rel} 形式) から、そのファイル専用のバックアップ保存先ディレクトリを求める。
// backups/<base>/<rel の各階層>/ という構成にし、最後の階層 (ファイル名と同名のディレクトリ) の中に
// 世代ファイルをまとめる (例: backups/trinityforge/stats/item-stats.yml/item-stats.yml.bak-...)。
// resources 配下から完全に切り離すことで jar への巻き込みを防ぐ。
function backupDirFor(entry) {
  const relParts = String(entry.rel).split(/[\\/]/).filter(Boolean);
  return path.join(backupRoot(), String(entry.base), ...relParts);
}

// "<base>.bak-YYYYMMDD-HHMMSS-mmm" (連番付きなら末尾に "-n" が追加される) を、
// タイムスタンプ部分 (固定長なので文字列比較で正しく時系列順になる) と連番に分解する。
function parseBackupSuffix(name, prefix) {
  const suffix = name.slice(prefix.length);
  const parts = suffix.split("-");
  const stampKey = parts.slice(0, 3).join("-");
  const seq = parts.length > 3 ? Number(parts[3]) : 0;
  return { stampKey, seq: Number.isFinite(seq) ? seq : 0 };
}

function compareBackupNames(a, b, prefix) {
  const pa = parseBackupSuffix(a, prefix);
  const pb = parseBackupSuffix(b, prefix);
  if (pa.stampKey !== pb.stampKey) return pa.stampKey < pb.stampKey ? -1 : 1;
  return pa.seq - pb.seq;
}

// backupKeep を超えた古い世代を削除する。0以下 (=無制限) なら何もしない (安全側)。
function pruneBackups(dir, baseName) {
  const keep = Number(toolConfig.backupKeep);
  if (!Number.isFinite(keep) || keep <= 0) return;
  const prefix = `${baseName}.bak-`;
  let files;
  try {
    files = fs.readdirSync(dir).filter((f) => f.startsWith(prefix));
  } catch (_) {
    return;
  }
  if (files.length <= keep) return;
  files.sort((a, b) => compareBackupNames(a, b, prefix));
  const toDelete = files.slice(0, files.length - keep);
  for (const f of toDelete) {
    try {
      fs.unlinkSync(path.join(dir, f));
    } catch (_) {
      // 削除失敗 (権限等) は本処理を止めない。次回保存時の剪定で再試行される。
    }
  }
}

// バックアップを原子的に作成する。COPYFILE_EXCL で新規作成を保証し、既存(EEXIST)なら連番で
// 再採番して再試行するため、同一秒の並行保存でも既存バックアップを上書きしない。
// 保存先は編集対象と同じフォルダではなく、entry({base, rel})から求めた専用バックアップルート配下。
function createBackup(abs, entry) {
  const dir = backupDirFor(entry);
  fs.mkdirSync(dir, { recursive: true });
  const baseName = path.basename(entry.rel);
  const stamp = timestamp();
  let candidate = path.join(dir, `${baseName}.bak-${stamp}`);
  let n = 1;
  for (;;) {
    try {
      fs.copyFileSync(abs, candidate, fs.constants.COPYFILE_EXCL);
      break;
    } catch (err) {
      if (err.code === "EEXIST") {
        candidate = path.join(dir, `${baseName}.bak-${stamp}-${n++}`);
        continue;
      }
      throw err;
    }
  }
  pruneBackups(dir, baseName);
  return candidate;
}

// 一時ファイルへ書いてから rename で原子的に置換する。ENOSPC/異常終了で本体を壊さない。
function writeFileAtomic(abs, output) {
  const tmp = `${abs}.tmp-${process.pid}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  try {
    fs.writeFileSync(tmp, output, "utf8");
    fs.renameSync(tmp, abs);
  } catch (err) {
    try {
      if (fs.existsSync(tmp)) fs.unlinkSync(tmp);
    } catch (_) {
      // 一時ファイルの掃除失敗は本エラーを覆い隠さないよう握りつぶす。
    }
    throw err;
  }
}

// 同時編集用の楽観的ロック用リビジョン (内容ハッシュ)。未作成ファイルは null。
function fileRevision(abs) {
  if (!abs || !fs.existsSync(abs)) return null;
  const buf = fs.readFileSync(abs);
  return crypto.createHash("sha256").update(buf).digest("hex").slice(0, 16);
}

const app = express();

// --- 認証 (Basic) ---
// パスワードが設定されているとき全ルート(静的資産+API)を保護する。external モードでは
// resolveBinding がパスワードを必須とするため、外部公開時は常に認証が有効になる。
// 依存追加なし: Node標準 crypto の timingSafeEqual で定数時間比較する。
function timingSafeEqualStr(a, b) {
  const ba = Buffer.from(String(a), "utf8");
  const bb = Buffer.from(String(b), "utf8");
  if (ba.length !== bb.length) {
    crypto.timingSafeEqual(ba, ba); // 長さ差でも一定の比較コストを踏み、極端な早期returnを避ける
    return false;
  }
  return crypto.timingSafeEqual(ba, bb);
}

function basicAuth(username, password) {
  const realm = "TrinityForge Config Editor";
  return (req, res, next) => {
    const header = req.headers.authorization || "";
    const m = /^Basic\s+(.+)$/i.exec(header);
    let ok = false;
    if (m) {
      let decoded = "";
      try { decoded = Buffer.from(m[1], "base64").toString("utf8"); } catch (_) { decoded = ""; }
      const idx = decoded.indexOf(":");
      const u = idx >= 0 ? decoded.slice(0, idx) : "";
      const p = idx >= 0 ? decoded.slice(idx + 1) : "";
      // 短絡させず両方を必ず比較する (ユーザ名の一致有無をタイミングで漏らさない)。
      const okUser = timingSafeEqualStr(u, username);
      const okPass = timingSafeEqualStr(p, password);
      ok = okUser && okPass;
    }
    if (ok) return next();
    // 失敗時は総当たりを鈍らせる小さな遅延を挟んでから 401 を返す。
    setTimeout(() => {
      res.set("WWW-Authenticate", `Basic realm="${realm}", charset="UTF-8"`);
      res.status(401).json({ error: "認証が必要です" });
    }, 400);
  };
}

if (toolConfig.auth.password) {
  app.use(basicAuth(toolConfig.auth.username, toolConfig.auth.password));
}

// CMDテクスチャアップロード(PNG base64)のため 16mb に引上げ。
app.use(express.json({ limit: "16mb" }));
// 静的アセット(css/js)は Cache-Control: no-cache を付け、毎回 ETag/Last-Modified で
// 再検証させる。これによりエディタ更新後に古いCSS/JSがブラウザキャッシュに残って
// レイアウト崩れ(例: 品質基準値欄のはみ出し)や古い挙動が出るのを防ぐ。変更が無ければ
// 304 で軽量に済むため実質的なコストは無い。
app.use(express.static(path.join(ROOT, "public"), {
  etag: true,
  lastModified: true,
  setHeaders: (res) => {
    res.setHeader("Cache-Control", "no-cache");
  }
}));

// 現在のベースパス・デプロイパス情報 (絶対解決済み) を返す。
app.get("/api/settings", (req, res) => {
  const basePaths = {};
  for (const [key, value] of Object.entries(toolConfig.basePaths)) {
    basePaths[key] = resolvePathInfo(value);
  }
  const deployPaths = {};
  const deployKeys = new Set([
    ...Object.keys(toolConfig.basePaths || {}),
    ...Object.keys(toolConfig.deployPaths || {})
  ]);
  for (const key of deployKeys) {
    deployPaths[key] = resolvePathInfo(toolConfig.deployPaths[key] || "");
  }
  res.json({
    port: toolConfig.port,
    host: toolConfig.host,
    basePaths,
    deployPaths
  });
});

// ベースパス / デプロイパスを更新して tool-config.json に保存する。
app.put("/api/settings", (req, res) => {
  const incomingBase = req.body && req.body.basePaths;
  const incomingDeploy = req.body && req.body.deployPaths;
  if ((!incomingBase || typeof incomingBase !== "object")
      && (!incomingDeploy || typeof incomingDeploy !== "object")) {
    return res.status(400).json({ error: "basePaths または deployPaths が必要です" });
  }
  const raw = JSON.parse(fs.readFileSync(CONFIG_PATH, "utf8"));
  if (incomingBase && typeof incomingBase === "object") {
    raw.basePaths = { ...raw.basePaths, ...incomingBase };
  }
  if (incomingDeploy && typeof incomingDeploy === "object") {
    raw.deployPaths = { ...(raw.deployPaths || {}), ...incomingDeploy };
  }
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(raw, null, 2) + "\n", "utf8");
  toolConfig = loadToolConfig();
  res.json({ ok: true });
});

// Material名 -> 日本語表示名 の辞書 (表示専用)。ja_items.properties を探索し、
// 見つからなければ主要素材のフォールバック辞書を返す。YAMLのキー/値には影響しない。
function resolveMaterialLabelCandidates() {
  const candidates = [];
  // 1) arspaper ベースパス直下の ja_items.properties
  const arsBase = toolConfig.basePaths.arspaper;
  if (arsBase) {
    const abs = path.isAbsolute(arsBase) ? arsBase : path.resolve(ROOT, arsBase);
    candidates.push(path.join(abs, JA_ITEMS_FILENAME));
  }
  // 2) リポジトリ標準配置 (basePath が実サーバへ向いていてもフォールバックとして探す)
  candidates.push(path.resolve(ROOT, "../../fork-handoff/arspaper/fork/src/main/resources", JA_ITEMS_FILENAME));
  return candidates;
}

app.get("/api/material-labels", (req, res) => {
  const { source, labels, materials, version, catalogSource } = buildMaterialLabels(resolveMaterialLabelCandidates());
  res.json({
    source,
    catalogSource: catalogSource || null,
    version: version || "1.21.11",
    materials: Array.isArray(materials) ? materials : Object.keys(labels || {}),
    labels: labels || {}
  });
});

// Paper 1.21.11 TileState 対応ブロック（CustomBlock / SourceJar / Sourcelink 用）
app.get("/api/tilestate-materials", (req, res) => {
  const file = path.join(__dirname, "lib", "tilestate-materials-1.21.11.json");
  try {
    const raw = JSON.parse(fs.readFileSync(file, "utf8"));
    res.json({
      version: raw.version || "1.21.11",
      source: raw.source || null,
      materials: Array.isArray(raw.materials) ? raw.materials : []
    });
  } catch (err) {
    res.status(500).json({ error: String(err && err.message || err), materials: [] });
  }
});

// ブロックとして設置可能な Material の一覧 (粉砕グリフ crush_map の「変換後が非ブロック」警告用)。
// vanilla-item-defs-1.21.11.json (クライアントjar抽出) の model パスが "minecraft:block/" で
// 始まるものをブロックアイテムとみなすヒューリスティック（実データから抽出、手書きリストではない）。
// キャッシュはプロセス起動中1回のみ計算する (ファイルは再生成しない限り不変)。
let _blockMaterialsCache = null;
app.get("/api/block-materials", (req, res) => {
  if (_blockMaterialsCache) { res.json(_blockMaterialsCache); return; }
  try {
    const file = path.join(__dirname, "lib", "vanilla-item-defs-1.21.11.json");
    const raw = JSON.parse(fs.readFileSync(file, "utf8"));
    const defs = raw.defs && typeof raw.defs === "object" ? raw.defs : {};
    const materials = Object.keys(defs).filter((key) => {
      const model = defs[key] && defs[key].model && defs[key].model.model;
      return typeof model === "string" && model.startsWith("minecraft:block/");
    }).sort();
    _blockMaterialsCache = { version: "1.21.11", source: raw._source || null, materials };
    res.json(_blockMaterialsCache);
  } catch (err) {
    res.status(500).json({ error: String(err && err.message || err), materials: [] });
  }
});

// スキル系統 (skilltree/*.yml の skill: を収集)。use-skill セレクトの選択肢に使う。
// 読めない場合は実在14+スキルのハードコードにフォールバックする。
const FALLBACK_SKILLS = [
  { id: "ALCHEMY", label: "錬金" }, { id: "ARCHERY", label: "弓術" },
  { id: "ARS_MAGIC", label: "Ars魔法" }, { id: "ARS_SMITHING", label: "Ars鍛冶" },
  { id: "DIGGING", label: "切削" }, { id: "ENCHANTING", label: "エンチャント" },
  { id: "FARMING", label: "農業" }, { id: "FISHING", label: "釣り" },
  { id: "HEAVY_ARMOR", label: "重装備" }, { id: "HEAVY_WEAPONS", label: "重量武器" },
  { id: "LIGHT_ARMOR", label: "軽装備" }, { id: "LIGHT_WEAPONS", label: "軽量武器" },
  { id: "MINING", label: "採掘" }, { id: "SMITHING", label: "鍛冶" },
  { id: "WOODCUTTING", label: "伐採" }
];

function collectSkills() {
  const base = toolConfig.basePaths.trinityforge;
  if (!base) return null;
  const baseAbs = path.isAbsolute(base) ? base : path.resolve(ROOT, base);
  const dir = path.join(baseAbs, "skilltree");
  let files;
  try {
    files = fs.readdirSync(dir).filter((f) => /\.ya?ml$/i.test(f));
  } catch (_) {
    return null; // ディレクトリ無し等はフォールバックへ
  }
  const skills = [];
  const seen = new Set();
  for (const f of files.sort()) {
    try {
      const { data } = readConfig(path.join(dir, f));
      const id = data && data.skill;
      if (typeof id !== "string" || id === "" || seen.has(id)) continue;
      seen.add(id);
      const label = data["display-name"];
      skills.push(typeof label === "string" && label ? { id, label } : { id });
    } catch (_) {
      // 個別ファイルの解析失敗はスキップし、他ファイルの収集を続ける
    }
  }
  return skills.length ? skills : null;
}

// registry.js の id からデータを読む共通ヘルパ。base未設定/未存在は null (例外を投げない)。
// CMD台帳スキャン (/api/cmd/usage, /api/respack/*) と gate-vocabulary で共用する。
function readEntry(entry) {
  if (!entry) return null;
  const abs = resolveAbsPath(entry);
  if (!abs || !fs.existsSync(abs)) return null;
  return readConfig(abs).data;
}

function readEntryById(id) {
  return readEntry(findById(id));
}

// 管理操作(個別登録解除 / カタログ削除の一括反映)用の書き込みヘルパ。
// id からファイルを解決し、スキーマ検証 → バックアップ → 直列化(コメント保持) → 原子的書込 →
// deployミラー を行う。通常の PUT と違い revision チェックはしない (サーバ内部の一括処理のため)。
// 検証NG時は例外を投げ、呼び出し側(cmd-routes)が 500 に振り分ける。
function writeEntryById(id, data) {
  const entry = findById(id);
  if (!entry) throw new Error(`未登録のconfig idです: ${id}`);
  const abs = resolveAbsPath(entry);
  if (!abs) throw new Error(`ベースパスが未設定です: ${entry.base}`);
  const errors = validate(entry.schema, data);
  if (entry.schema === "item-stats") errors.push(...validateItemStatsLayerRefs(data, readMultiplierLayers()));
  if (entry.schema === "tf-skilltree") errors.push(...validateSkillTreeLayerRefs(data, readMultiplierLayers()));
  if (errors.length > 0) {
    throw new Error(`スキーマ検証に失敗しました (${id}): ${errors.slice(0, 5).join(" / ")}`);
  }
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  let previousRaw = "";
  if (fs.existsSync(abs)) {
    previousRaw = fs.readFileSync(abs, "utf8");
    createBackup(abs, entry);
  }
  const output = serializeConfig(data, previousRaw);
  writeFileAtomic(abs, output);
  mirrorToDeploy(entry, output, abs);
  return { ok: true, path: abs };
}

// ArsPaper form-cooldowns のform名サジェスト語彙(2026-07-25 config editor T1)。
// lib/spell-form-vocabulary.js が唯一の情報源(Java正典との同期は
// test/spell-form-vocabulary-java-parity.test.js が検証)。
app.get("/api/spell-forms", (req, res) => {
  res.json({ ok: true, data: SPELL_FORMS });
});

// スキルツリー「解放効果」動的ゲートの語彙一括供給。各ソースが無い/空でも空配列で返す(落ちない)。
app.get("/api/gate-vocabulary", (req, res) => {
  try {
    const sources = {
      glyphs: readEntryById("glyphs"),
      craftingFeatures: readEntryById("crafting-features"),
      villagerTrades: readEntryById("villager-trades"),
      gimmicks: {
        mining: readEntryById("mining-gimmick"),
        woodcutting: readEntryById("woodcutting-gimmick"),
        fishing: readEntryById("fishing-gimmick"),
        digging: readEntryById("digging-gimmick") // registry未登録/ファイル未実装なら null
      },
      specialRewards: readEntryById("special-rewards"), // registry未登録/ファイル未実装なら null
      catalog: readEntryById("catalog"),
      items: readEntryById("items"),
      // 2026-08-14: recipe:/ritual: ゲートの候補に ArsPaper 側のレシピ定義を全部入れる。
      // 機能アイテム(ワンド/ウェイストーン等)が1件もセレクトに出ていなかったのが発端。
      // どれか欠けても buildGateVocabulary は空として扱うので落ちない。
      functionalItems: readEntryById("functional-items"),
      materials: readEntryById("materials"),
      sourcejars: readEntryById("sourcejars"),
      sourcelinks: readEntryById("sourcelinks"),
      spellbooks: readEntryById("spellbooks")
    };
    res.json(buildGateVocabulary(sources));
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// タスク2 (2026-07-26): スキルツリー「機能解放」のtier(scale)パラメータをセレクトメニュー化するための
// 語彙供給。gate-vocabulary とは別エンドポイント(lib/gate-vocabulary.js は他作業者が編集中のため
// 変更しない方針)。対象は vein-mining / haste-active-mining / tree-fell / area-harvest の4件に加え、
// 2026-07-26 tier-expand で xp-bottle-store-unlock(fishing-gimmick) / potion-merge(crafting-features)
// をSCALE化したのに伴い fishing / craftingFeatures バケットを追加。2026-07-28 (数値のギミックyml集約)
// で furnace-smelt-speed/bonus と digging-durability-vanilla-exp/job-exp をSCALE化したため
// smithing / digging バケットを追加。
app.get("/api/tier-vocabulary", (req, res) => {
  try {
    const gimmicks = {
      mining: readEntryById("mining-gimmick"),
      woodcutting: readEntryById("woodcutting-gimmick"),
      farming: readEntryById("farming-gimmick"),
      fishing: readEntryById("fishing-gimmick"),
      craftingFeatures: readEntryById("crafting-features"),
      smithing: readEntryById("smithing-gimmick"),
      digging: readEntryById("digging-gimmick")
    };
    res.json({ ok: true, tiers: buildTierVocabulary(gimmicks) });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.get("/api/skills", (req, res) => {
  const skills = collectSkills();
  res.json({ skills: skills || FALLBACK_SKILLS, source: skills ? "skilltree" : "fallback" });
});

// CMD (CustomModelData) 自動割当 + リソースパック自動生成 (lib/cmd-routes.js に分離)。
// ctx は毎回関数越しに toolConfig を参照するため、/api/settings での再読込にも追従する。
const cmdCtx = { readEntryById, writeEntry: writeEntryById, resourcePackRoot, cmdRegistryPath, packRepo };
registerCmdRoutes(app, cmdCtx);

// config 一覧
app.get("/api/configs", (req, res) => {
  const list = REGISTRY.map((entry) => {
    const abs = resolveAbsPath(entry);
    return {
      id: entry.id,
      label: entry.label,
      group: entry.group,
      section: entry.section,
      schema: entry.schema,
      exists: abs ? fs.existsSync(abs) : false
    };
  });
  res.json({ configs: list });
});

// 単一 config の取得
app.get("/api/config/:id", (req, res) => {
  const entry = findById(req.params.id);
  if (!entry) return res.status(404).json({ error: "未登録のconfig id です" });
  const abs = resolveAbsPath(entry);
  if (!abs) return res.status(400).json({ error: `ベースパス "${entry.base}" が未設定です` });
  try {
    const { exists, data } = readConfig(abs);
    res.json({
      id: entry.id,
      label: entry.label,
      schema: entry.schema,
      exists,
      data: exists ? data : {},
      revision: fileRevision(abs)
    });
  } catch (err) {
    res.status(500).json({ error: `YAMLの読み込みに失敗しました: ${err.message}` });
  }
});

// lore.yml の乗算レイヤ定義 ([{id, name, stat}]) を読む。読めない/未定義なら []。
// item-stats 保存時のクロスファイル検証 (validateItemStatsLayerRefs) 用。
function readMultiplierLayers() {
  try {
    const loreEntry = findById("lore");
    if (!loreEntry) return [];
    const loreAbs = resolveAbsPath(loreEntry);
    if (!loreAbs) return [];
    const { exists, data } = readConfig(loreAbs);
    if (!exists || !data || !Array.isArray(data["multiplier-layers"])) return [];
    return data["multiplier-layers"];
  } catch (_) {
    return [];
  }
}

// 保存: 検証 -> (楽観的ロック) -> バックアップ -> 書き込み (検証NG時はバックアップを作らない)
// body.expectedRevision が現ファイルと不一致なら 409。body.force=true で上書き可。
app.put("/api/config/:id", (req, res) => {
  const entry = findById(req.params.id);
  if (!entry) return res.status(404).json({ error: "未登録のconfig id です" });
  const abs = resolveAbsPath(entry);
  if (!abs) return res.status(400).json({ error: `ベースパス "${entry.base}" が未設定です` });

  const data = req.body && req.body.data;
  if (data === undefined) return res.status(400).json({ error: "body.data が必要です" });

  // 検証 (NGなら書き込まない)
  const errors = validate(entry.schema, data);
  // item-stats は lore.yml の乗算レイヤ定義とのクロスファイル検証も行う
  // (レイヤはステータスごとの定義: 未定義レイヤ / 基準ステ不一致はエラー)。
  if (entry.schema === "item-stats") {
    errors.push(...validateItemStatsLayerRefs(data, readMultiplierLayers()));
  }
  if (entry.schema === "tf-skilltree") {
    errors.push(...validateSkillTreeLayerRefs(data, readMultiplierLayers()));
  }
  if (errors.length > 0) {
    return res.status(400).json({ error: "スキーマ検証に失敗しました", details: errors });
  }

  try {
    fs.mkdirSync(path.dirname(abs), { recursive: true });

    const force = !!(req.body && req.body.force);
    const expected = req.body && req.body.expectedRevision;
    const currentRev = fileRevision(abs);
    if (!force && expected !== undefined && expected !== null && currentRev !== null && expected !== currentRev) {
      const { exists, data: latest } = readConfig(abs);
      return res.status(409).json({
        error: "他の編集者が先に保存したため、そのままでは上書きできません",
        conflict: true,
        revision: currentRev,
        data: exists ? latest : {}
      });
    }

    let previousRaw = "";
    let backup = null;
    let oldParsedData = null;
    if (fs.existsSync(abs)) {
      previousRaw = fs.readFileSync(abs, "utf8");
      try { oldParsedData = readConfig(abs).data; } catch (_) { oldParsedData = null; }
      backup = createBackup(abs, entry);
    }

    const output = serializeConfig(data, previousRaw);
    writeFileAtomic(abs, output);
    const deploy = mirrorToDeploy(entry, output, abs);

    // CMDを含むconfigの保存後は、台帳同期 + クロスファイル衝突の警告(ブロックしない)を行う。
    let cmdWarnings;
    if (CmdRegistry.CMD_SCAN_IDS.includes(entry.id)) {
      try {
        cmdWarnings = computeCmdWarnings(cmdCtx, entry.id, oldParsedData, data);
      } catch (err) {
        cmdWarnings = [`CMD衝突検査でエラーが発生しました: ${err.message}`];
      }
      // 台帳が破損していた場合は syncCmdRegistryAfterSave が警告メッセージを返す
      // (config自体は既に保存済みのため、この時点では保存失敗にしない。H-2)。
      const syncWarning = syncCmdRegistryAfterSave(cmdCtx);
      if (syncWarning) cmdWarnings = [...(cmdWarnings || []), syncWarning];
    }

    res.json({
      ok: true,
      backup: backup ? path.basename(backup) : null,
      path: abs,
      revision: fileRevision(abs),
      deploy,
      cmdWarnings
    });
  } catch (err) {
    res.status(500).json({ error: `保存に失敗しました: ${err.message}` });
  }
});

// 共通変数(戦闘定数)の各ソースファイルを絶対解決する。base 未設定は null。
function resolveConstantPaths() {
  const out = {};
  for (const [key, src] of Object.entries(CONSTANT_SOURCES)) {
    out[key] = resolveAbsPath(src);
  }
  return out;
}

// 現在の各ソースを読み、パース済みデータを返す。base 未設定なら例外。
function readConstantSources() {
  const paths = resolveConstantPaths();
  const data = {};
  for (const [key, abs] of Object.entries(paths)) {
    if (!abs) {
      const err = new Error(`ベースパス "${CONSTANT_SOURCES[key].base}" が未設定です`);
      err.userFacing = true;
      throw err;
    }
    const { exists, data: parsed } = readConfig(abs);
    data[key] = exists ? parsed : {};
  }
  return { paths, data };
}

function constantsRevision(paths) {
  const parts = [];
  for (const abs of Object.values(paths)) {
    parts.push(fileRevision(abs) || "missing");
  }
  return crypto.createHash("sha256").update(parts.join("|")).digest("hex").slice(0, 16);
}

// 共通変数の集約取得。
app.get("/api/constants", (req, res) => {
  try {
    const { paths, data } = readConstantSources();
    res.json({ constants: extractConstants(data.damage, data.combatLevel), revision: constantsRevision(paths) });
  } catch (err) {
    const status = err.userFacing ? 400 : 500;
    res.status(status).json({ error: `共通変数の読み込みに失敗しました: ${err.message}` });
  }
});

// 共通変数の保存: 検証 -> 各ソースへ (バックアップ -> 原子的書込・ヘッダコメント保持)。
app.put("/api/constants", (req, res) => {
  const payload = req.body && req.body.constants;
  if (payload === undefined) return res.status(400).json({ error: "body.constants が必要です" });

  const errors = validateConstants(payload);
  if (errors.length > 0) {
    return res.status(400).json({ error: "共通変数の検証に失敗しました", details: errors });
  }

  try {
    const { paths, data } = readConstantSources();
    const force = !!(req.body && req.body.force);
    const expected = req.body && req.body.expectedRevision;
    const currentRev = constantsRevision(paths);
    if (!force && expected !== undefined && expected !== null && expected !== currentRev) {
      return res.status(409).json({
        error: "他の編集者が先に保存したため、そのままでは上書きできません",
        conflict: true,
        revision: currentRev,
        constants: extractConstants(data.damage, data.combatLevel)
      });
    }

    const updated = buildUpdatedData(payload, data.damage, data.combatLevel);
    const backups = {};
    const deploys = {};
    for (const [key, abs] of Object.entries(paths)) {
      fs.mkdirSync(path.dirname(abs), { recursive: true });
      let previousRaw = "";
      let backup = null;
      if (fs.existsSync(abs)) {
        previousRaw = fs.readFileSync(abs, "utf8");
        backup = createBackup(abs, CONSTANT_SOURCES[key]);
      }
      const output = serializeConfig(updated[key], previousRaw);
      writeFileAtomic(abs, output);
      backups[key] = backup ? path.basename(backup) : null;
      deploys[key] = mirrorToDeploy(CONSTANT_SOURCES[key], output, abs);
    }
    res.json({ ok: true, backups, deploys, revision: constantsRevision(paths) });
  } catch (err) {
    const status = err.userFacing ? 400 : 500;
    res.status(status).json({ error: `共通変数の保存に失敗しました: ${err.message}` });
  }
});

// require() 経由 (テスト等) では listen しない。`node server.js` 直接実行時のみ起動する。
if (require.main === module) {
  app.listen(toolConfig.port, toolConfig.host, () => {
    // 起動ログのみ標準出力へ (本番コードではないローカルツール)
    process.stdout.write(`TrinityForge config editor: http://${toolConfig.host}:${toolConfig.port}\n`);
    if (toolConfig.external) {
      process.stdout.write("============================================================\n");
      process.stdout.write("[⚠ 外部公開モード] editor がネットワークに公開されています。\n");
      // 認証はパスワードがある時だけ実際に掛かる (basicAuth の mount 条件と同じ) ので、
      // 表示もそれに合わせる。空パスワードで「Basic 有効」と出すのは事実と異なる。
      process.stdout.write(toolConfig.auth.password
        ? `  ・認証: Basic 有効 (user=${toolConfig.auth.username})。\n`
        : "  ・認証: 無効(パスワード未設定)。到達できる全員が編集・配備できます。\n");
      process.stdout.write("  ・平文HTTPのため Basic 認証情報は暗号化されません(base64のみ)。\n");
      process.stdout.write("    インターネット公開時は必ず TLS リバースプロキシ(Caddy/nginx/Cloudflare Tunnel)経由にしてください。\n");
      process.stdout.write("  ・OSファイアウォール/ルータのポート開放は必要最小限に絞ってください。\n");
      process.stdout.write("  ・パスワードは環境変数 CONFIG_EDITOR_PASSWORD 推奨 (tool-config.json への平文記載は避ける)。\n");
      process.stdout.write("============================================================\n");
    } else if (toolConfig.auth.password) {
      process.stdout.write(`  認証: Basic 有効 (user=${toolConfig.auth.username})、ループバックのみ。\n`);
    }
  });
}

// テスト専用エクスポート (backup.test.js から利用)。本体の起動フローには影響しない。
module.exports = {
  app,
  createBackup,
  pruneBackups,
  backupDirFor,
  timestamp,
  __setToolConfigForTest(overrides) {
    Object.assign(toolConfig, overrides);
  }
};
