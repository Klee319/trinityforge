"use strict";

// threeWayMerge (public/js/merge.js) の単体テスト。
// 実行: node --test tools/config-editor/test
// merge.js はブラウザ用 IIFE (window 前提) なので、window を global に生やして読み込む。

const test = require("node:test");
const assert = require("node:assert/strict");

global.window = global.window || {};
require("../public/js/merge.js");

const { threeWayMerge } = global.window;

// ---- 同時編集バグ再現: 双方が同じリストへ別エントリを追加 ----
// タブA(remote)が eA を追加して保存済み、タブB(local)が eB を追加して 409→マージ。
// 旧実装は配列を葉扱いで local 丸勝ちにするため、保存済みの eA が消えていた。

test("双方が末尾追加した配列は両方の追加が残る (保存済みエントリを棄却しない)", () => {
  const e1 = { item: "sword", weight: 10 };
  const eA = { item: "shield", weight: 5 };
  const eB = { item: "bow", weight: 3 };
  const { data, conflicts } = threeWayMerge(
    { entries: [e1] },
    { entries: [e1, eB] },
    { entries: [e1, eA] }
  );
  assert.deepEqual(data.entries, [e1, eB, eA]);
  assert.deepEqual(conflicts, []);
});

test("双方が同一エントリを追加した場合は重複しない", () => {
  const e1 = { item: "sword" };
  const eX = { item: "shield" };
  const { data, conflicts } = threeWayMerge(
    { entries: [e1] },
    { entries: [e1, eX] },
    { entries: [e1, eX, { item: "bow" }] }
  );
  assert.deepEqual(data.entries, [e1, eX, { item: "bow" }]);
  assert.deepEqual(conflicts, []);
});

// ---- id キー付きリスト: 別要素の編集同士は両立する ----

test("idで同定できる配列は別要素の編集が両立する (local編集 + remote追加)", () => {
  const base = { list: [{ id: "a", v: 1 }] };
  const local = { list: [{ id: "a", v: 2 }] }; // 自分: a を編集
  const remote = { list: [{ id: "a", v: 1 }, { id: "b", v: 9 }] }; // 相手: b を追加して保存済み
  const { data, conflicts } = threeWayMerge(base, local, remote);
  assert.deepEqual(data.list, [{ id: "a", v: 2 }, { id: "b", v: 9 }]);
  assert.deepEqual(conflicts, []);
});

test("idキー配列: 双方が別要素を編集 → 両方の編集が残る", () => {
  const base = { list: [{ id: "a", v: 1 }, { id: "b", v: 1 }] };
  const local = { list: [{ id: "a", v: 2 }, { id: "b", v: 1 }] };
  const remote = { list: [{ id: "a", v: 1 }, { id: "b", v: 2 }] };
  const { data, conflicts } = threeWayMerge(base, local, remote);
  assert.deepEqual(data.list, [{ id: "a", v: 2 }, { id: "b", v: 2 }]);
  assert.deepEqual(conflicts, []);
});

test("idキー配列: local削除 vs remote未変更 → 削除が通る", () => {
  const base = { list: [{ id: "a", v: 1 }, { id: "b", v: 1 }] };
  const local = { list: [{ id: "a", v: 1 }] };
  const remote = { list: [{ id: "a", v: 1 }, { id: "b", v: 1 }, { id: "c", v: 7 }] };
  const { data, conflicts } = threeWayMerge(base, local, remote);
  assert.deepEqual(data.list, [{ id: "a", v: 1 }, { id: "c", v: 7 }]);
  assert.deepEqual(conflicts, []);
});

test("idキー配列: local削除 vs remote編集 → 衝突として記録し削除(local)優先", () => {
  const base = { list: [{ id: "a", v: 1 }, { id: "b", v: 1 }] };
  const local = { list: [{ id: "a", v: 1 }] };
  const remote = { list: [{ id: "a", v: 1 }, { id: "b", v: 99 }] };
  const { data, conflicts } = threeWayMerge(base, local, remote);
  assert.deepEqual(data.list, [{ id: "a", v: 1 }]);
  assert.equal(conflicts.length, 1);
});

// ---- 従来挙動の回帰ガード ----

test("同定不能な配列の双方変更は従来通り衝突+local優先", () => {
  const { data, conflicts } = threeWayMerge(
    { nums: [1] },
    { nums: [2] },
    { nums: [3] }
  );
  assert.deepEqual(data.nums, [2]);
  assert.deepEqual(conflicts, ["nums"]);
});

test("同一要素(同一id)を双方が別内容に編集 → 葉単位で衝突しlocal優先", () => {
  const base = { list: [{ id: "a", v: 1 }] };
  const local = { list: [{ id: "a", v: 2 }] };
  const remote = { list: [{ id: "a", v: 3 }] };
  const { data, conflicts } = threeWayMerge(base, local, remote);
  assert.deepEqual(data.list, [{ id: "a", v: 2 }]);
  assert.equal(conflicts.length, 1);
});

test("オブジェクトのキー単位マージは従来通り (双方の追加キーが残る)", () => {
  const { data, conflicts } = threeWayMerge(
    { a: 1 },
    { a: 1, b: 2 },
    { a: 1, c: 3 }
  );
  assert.deepEqual(data, { a: 1, b: 2, c: 3 });
  assert.deepEqual(conflicts, []);
});

test("片側のみ変更なら配列もそのまま採用 (従来通り)", () => {
  const base = { entries: [{ item: "sword" }] };
  const local = { entries: [{ item: "sword" }] };
  const remote = { entries: [{ item: "sword" }, { item: "bow" }] };
  const { data, conflicts } = threeWayMerge(base, local, remote);
  assert.deepEqual(data.entries, [{ item: "sword" }, { item: "bow" }]);
  assert.deepEqual(conflicts, []);
});
