"use strict";

// リソースパック zip を GitHub release へ公開する (配布URL発行)。
// 認証済みの gh CLI を利用する。公開先リポジトリは tool-config.json の packRepo
// ("owner/repo"、公開リポであること — クライアントは認証なしでDLするため)。
//
// タグは pack-YYYYMMDDHHMMSS の一意な連番形式。zip は公開直前に必ず再ビルドし、
// 返却する sha1 と release 資産の実体が一致することを保証する。

const { execFileSync } = require("child_process");
const Respack = require("./respack");

const REPO_RE = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/;

function publish(packRoot, repo) {
  if (!repo || !REPO_RE.test(repo)) {
    throw new Error("packRepo が未設定または不正です。tool-config.json に \"packRepo\": \"owner/repo\" を設定してください");
  }
  // 公開する zip は必ずその場でビルドし直す (sha1 と資産の乖離を防ぐ)。
  const build = Respack.buildPack(packRoot);
  const tag = "pack-" + new Date().toISOString().replace(/[-:.TZ]/g, "").slice(0, 14);
  const title = `TrinityForge Pack ${tag}`;
  const notes = [
    "TrinityForge リソースパック自動配布 release。",
    "",
    `- sha1: \`${build.sha1}\``,
    `- size: ${build.size} bytes / files: ${build.fileCount}`,
    "",
    "server.properties 設定例:",
    "```",
    `resource-pack=https://github.com/${repo}/releases/download/${tag}/TrinityForge-Pack.zip`,
    `resource-pack-sha1=${build.sha1}`,
    "```"
  ].join("\n");
  try {
    execFileSync("gh", [
      "release", "create", tag, build.path,
      "--repo", repo, "--title", title, "--notes", notes
    ], { stdio: ["ignore", "pipe", "pipe"] });
  } catch (err) {
    const stderr = err && err.stderr ? String(err.stderr) : "";
    throw new Error(`gh release create に失敗しました: ${stderr || err.message}`);
  }
  const url = `https://github.com/${repo}/releases/download/${tag}/TrinityForge-Pack.zip`;
  return { tag, url, sha1: build.sha1, size: build.size, fileCount: build.fileCount, repo };
}

module.exports = { publish, REPO_RE };
