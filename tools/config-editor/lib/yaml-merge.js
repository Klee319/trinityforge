"use strict";

// 編集後のプレーンなデータ(JSON)を、元の YAML ドキュメントへ「上書き」する形で適用する。
//
// なぜ必要か (2026-08-16):
//   editor の保存は YAML.parse → JS オブジェクト → YAML.stringify という往復で、
//   ファイル先頭のヘッダコメント以外の【本文コメントが毎回すべて消えていた】。
//   このリポジトリは yml 内の日本語コメントを恒久知識の置き場にしているので、
//   「開いて保存しただけ」で経緯が消える。実際 skilltree/ars_smithing.yml では
//   「enchant_book_* は method: ritual なので recipe: では無効」という注意書き4行が消え、
//   同じ取り違えが再発している(無言で常時解放になる事故)。
//
// やり方:
//   元ファイルを Document として保持したまま、新データと同じキー/要素を突き合わせて
//   ノードを再利用する。再利用したノードにはコメントがぶら下がったまま残る。
//   一致しないものだけ新規ノードを作る(そこに付いていたコメントは元の値の説明なので落ちてよい)。

const YAML = require("yaml");

function isPlainObject(v) {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

function keyOf(pair) {
  const key = pair && pair.key;
  if (key == null) return "";
  return String(YAML.isScalar(key) ? key.value : key);
}

// ノードが表す値と、新データが「同じもの」か。配列要素の対応付けにだけ使う。
function sameValue(node, value) {
  try {
    return JSON.stringify(node.toJSON()) === JSON.stringify(value);
  } catch (_) {
    return false;
  }
}

// 配列要素がマップのとき、値が変わっていても「同じ要素」と見なすための識別子。
const IDENTITY_KEYS = ["id", "key", "name"];

function identityOf(value) {
  if (!isPlainObject(value)) return null;
  for (const k of IDENTITY_KEYS) {
    if (value[k] != null) return `${k}=${String(value[k])}`;
  }
  return null;
}

function identityOfNode(node) {
  if (!YAML.isMap(node)) return null;
  for (const k of IDENTITY_KEYS) {
    const pair = node.items.find((p) => keyOf(p) === k);
    if (pair && YAML.isScalar(pair.value) && pair.value.value != null) {
      return `${k}=${String(pair.value.value)}`;
    }
  }
  return null;
}

// 元ノードのコメントを新ノードへ引き継ぐ(キー行に付いたコメントは Pair 側にあるので対象外)。
function carryComments(oldNode, newNode) {
  if (!oldNode || !newNode || typeof newNode !== "object") return newNode;
  if (oldNode.commentBefore != null) newNode.commentBefore = oldNode.commentBefore;
  if (oldNode.comment != null) newNode.comment = oldNode.comment;
  if (oldNode.spaceBefore) newNode.spaceBefore = oldNode.spaceBefore;
  return newNode;
}

function mergeNode(oldNode, value, doc) {
  if (isPlainObject(value)) {
    if (!YAML.isMap(oldNode)) return carryComments(oldNode, doc.createNode(value));
    const byKey = new Map();
    for (const pair of oldNode.items) {
      if (!byKey.has(keyOf(pair))) byKey.set(keyOf(pair), pair);
    }
    const items = [];
    for (const key of Object.keys(value)) {
      const old = byKey.get(key);
      if (old) {
        old.value = mergeNode(old.value, value[key], doc);
        items.push(old);
      } else {
        items.push(doc.createPair(key, value[key]));
      }
    }
    oldNode.items = items;
    return oldNode;
  }

  if (Array.isArray(value)) {
    if (!YAML.isSeq(oldNode)) return carryComments(oldNode, doc.createNode(value));
    const unused = oldNode.items.slice();
    const take = (predicate) => {
      const idx = unused.findIndex(predicate);
      if (idx < 0) return null;
      return unused.splice(idx, 1)[0];
    };
    const items = value.map((item) => {
      // 完全一致 → 同一性キー一致 の順に対応付ける。どちらも無ければ新規ノード。
      const identity = identityOf(item);
      const matched = take((node) => sameValue(node, item))
        || (identity ? take((node) => identityOfNode(node) === identity) : null);
      return matched ? mergeNode(matched, item, doc) : doc.createNode(item);
    });
    oldNode.items = items;
    return oldNode;
  }

  // スカラー(および null)。値が同じなら元ノードごと温存する(引用符の流儀も保たれる)。
  if (YAML.isScalar(oldNode) && oldNode.value === value) return oldNode;
  return carryComments(oldNode, doc.createNode(value));
}

/**
 * 元の YAML 文字列のコメントを保ったまま data を適用した YAML 文字列を返す。
 * 元文字列がパースできない/中身が無いときは null を返す(呼び出し側が従来の直列化へ落ちる)。
 * @param {string} previousRaw
 * @param {*} data
 * @param {object} [stringifyOptions]
 * @returns {string|null}
 */
function mergeIntoYaml(previousRaw, data, stringifyOptions) {
  if (typeof previousRaw !== "string" || previousRaw.trim() === "") return null;
  let doc;
  try {
    doc = YAML.parseDocument(previousRaw);
  } catch (_) {
    return null;
  }
  if (!doc || (doc.errors && doc.errors.length > 0) || doc.contents == null) return null;
  try {
    doc.contents = mergeNode(doc.contents, data, doc);
    return doc.toString(stringifyOptions || {});
  } catch (_) {
    return null;
  }
}

module.exports = { mergeIntoYaml };
