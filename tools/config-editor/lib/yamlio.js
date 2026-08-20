"use strict";

// YAML の読み書きとコメント保持を担当する。
//
// コメント保持方針 (2026-08-16 改訂):
//   元ファイルを YAML Document として保持したまま、新データと同じキー/要素のノードを再利用して
//   上書きする(lib/yaml-merge.js)。再利用したノードにはコメントが付いたまま残るので、
//   【本文途中のコメントも保存で消えない】。構造ごと差し替わった箇所のコメントだけは落ちる
//   (元の値の説明なので残す意味が無い)。
//   元ファイルがパースできない/空のときだけ、従来の「全面 stringify + ヘッダ再付与」へ落ちる。
//   旧方式は本文コメントを毎回全消しにしていて、skilltree/ars_smithing.yml の
//   「儀式は recipe: では無効」という注意書きが消えて同じ事故が再発する実害が出た。

const fs = require("fs");
const YAML = require("yaml");
const { mergeIntoYaml } = require("./yaml-merge");

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

// data(JSON) を YAML 文字列へ。
//
// 2026-08-16: 以前は毎回まるごと stringify し直してヘッダコメントだけ貼り直していたため、
// 【本文中のコメントが保存のたびに全部消えていた】。このリポジトリは yml 内の日本語コメントを
// 恒久知識の置き場にしているので、「開いて保存しただけ」で経緯が失われる(実害あり:
// skilltree/ars_smithing.yml の「儀式は recipe: では無効」という注意書きが消えて同じ事故が再発)。
// 元ファイルを Document として保持しつつ差分だけ当てる経路(lib/yaml-merge.js)を先に試し、
// 失敗したときだけ従来どおり全面 stringify にフォールバックする。
const STRINGIFY_OPTIONS = {
  lineWidth: 0, // 長い日本語文字列などを勝手に折り返さない
  nullStr: "" // null は空値として出力
};

function serializeConfig(data, previousRaw) {
  const merged = mergeIntoYaml(previousRaw, data, STRINGIFY_OPTIONS);
  if (merged != null) {
    return merged;
  }
  const body = YAML.stringify(data, STRINGIFY_OPTIONS);
  const header = previousRaw ? extractHeader(previousRaw) : "";
  if (!header) {
    return body;
  }
  return `${header}\n\n${body}`;
}

module.exports = { readConfig, serializeConfig, extractHeader };
