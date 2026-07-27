"use strict";

// YAML の読み書きとコメント保持を担当する。
//
// コメント保持方針 (README にも記載):
//   フロントから届く data は構造ごと編集され得る (エントリ追加/削除など) ため、
//   任意の内部コメントを機械的に正しい位置へ戻すのは非現実的。そこで確実性を優先し、
//   「ファイル先頭のヘッダコメントブロック(先頭の連続する # 行と空行)」を生ファイルから
//   退避し、再シリアライズした本文の先頭へ必ず再付与する。ヘッダの日本語コメントは壊れない。
//   本文途中のコメントは失われ得るが、保存前に必ずバックアップを取るため復元可能。

const fs = require("fs");
const YAML = require("yaml");

// 生ファイル先頭から、コメント行(前後空白可)と空行が連続する範囲をヘッダとして抽出する。
// 最初に現れる「非コメントかつ非空」の行の手前までをヘッダとみなす。
function extractHeader(rawText) {
  const lines = rawText.split(/\r?\n/);
  const header = [];
  for (const line of lines) {
    const trimmed = line.trim();
    const isComment = trimmed.startsWith("#");
    const isBlank = trimmed.length === 0;
    if (isComment || isBlank) {
      header.push(line);
    } else {
      break;
    }
  }
  // 末尾の空行は本文との間に空行1つだけ残す形に正規化する。
  while (header.length > 0 && header[header.length - 1].trim() === "") {
    header.pop();
  }
  return header.length > 0 ? header.join("\n") : "";
}

function readConfig(absPath) {
  if (!fs.existsSync(absPath)) {
    return { exists: false, data: null, raw: "" };
  }
  const raw = fs.readFileSync(absPath, "utf8");
  const data = YAML.parse(raw);
  // 空ファイルや null の場合は空オブジェクトへ寄せる。
  return { exists: true, data: data === undefined || data === null ? {} : data, raw };
}

// data(JSON) を YAML 文字列へ。既存ヘッダを先頭へ再付与する。
function serializeConfig(data, previousRaw) {
  const body = YAML.stringify(data, {
    lineWidth: 0, // 長い日本語文字列などを勝手に折り返さない
    nullStr: "" // null は空値として出力
  });
  const header = previousRaw ? extractHeader(previousRaw) : "";
  if (!header) {
    return body;
  }
  return `${header}\n\n${body}`;
}

module.exports = { readConfig, serializeConfig, extractHeader };
