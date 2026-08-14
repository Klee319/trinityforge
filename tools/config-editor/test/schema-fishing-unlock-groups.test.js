"use strict";

// fishing-gimmick.yml (tf-fishing-gimmick) の fishing.groups / fishing.unlock-groups
// (機能解放追加用テーブル、任意キー) 検証テスト。2026-08-15新設。
// unlock-groups は groups と完全に同一の Category スキーマなので、同じ規則で検証されることを固定する
// (config-editor.md 「検証側とUI側の両方を確認し、片方だけ直して終わらせない」の教訓に基づき、
//  unlock-groups だけ緩い/存在しない検証にしない)。

const { test } = require("node:test");
const assert = require("node:assert/strict");
const { validate } = require("../lib/schema.js");

function fishingDoc(groups, unlockGroups) {
  const fishing = {};
  if (groups !== undefined) fishing.groups = groups;
  if (unlockGroups !== undefined) fishing["unlock-groups"] = unlockGroups;
  return { fishing };
}

test("unlock-groups キーが無い設定は検証エラー無し(任意キー)", () => {
  const errors = validate("tf-fishing-gimmick", fishingDoc({
    treasure: { categories: { tier1: { "display-name": "宝", entries: [{ item: "NAME_TAG", weight: 1, amount: 1 }] } } }
  }));
  assert.deepEqual(errors, []);
});

test("unlock-groups が groups と同じ正常な形なら検証エラー無し", () => {
  const errors = validate("tf-fishing-gimmick", fishingDoc(
    { treasure: { categories: { tier1: { "display-name": "宝", entries: [{ item: "NAME_TAG", weight: 1, amount: 1 }] } } } },
    { treasure: { categories: { rare: { "display-name": "宝(解放枠)", entries: [{ item: "DIAMOND", weight: 5, amount: 2 }] } } } }
  ));
  assert.deepEqual(errors, []);
});

test("unlock-groups.<id>.categories.<id>.entries[].weight が0以下は groups と同じ規則で弾かれる", () => {
  const errors = validate("tf-fishing-gimmick", fishingDoc(undefined, {
    treasure: { categories: { rare: { entries: [{ item: "DIAMOND", weight: 0, amount: 1 }] } } }
  }));
  assert.ok(
    errors.some((e) => /fishing\.unlock-groups\.treasure\.categories\.rare\.entries\[0\]\.weight: 1以上の整数である必要があります/.test(e)),
    JSON.stringify(errors)
  );
});

test("groups.<id>.categories.<id>.entries[].weight が0以下も同じ規則で弾かれる(unlock-groupsだけ厳しくしていないことの対照)", () => {
  const errors = validate("tf-fishing-gimmick", fishingDoc({
    treasure: { categories: { tier1: { entries: [{ item: "NAME_TAG", weight: 0, amount: 1 }] } } }
  }));
  assert.ok(
    errors.some((e) => /fishing\.groups\.treasure\.categories\.tier1\.entries\[0\]\.weight: 1以上の整数である必要があります/.test(e)),
    JSON.stringify(errors)
  );
});

test("unlock-groups.<id>.categories.<id>.entries[].amount が0以下は弾かれる", () => {
  const errors = validate("tf-fishing-gimmick", fishingDoc(undefined, {
    junk: { categories: { rare: { entries: [{ item: "STRING", weight: 1, amount: 0 }] } } }
  }));
  assert.ok(
    errors.some((e) => /fishing\.unlock-groups\.junk\.categories\.rare\.entries\[0\]\.amount: 1以上の整数である必要があります/.test(e)),
    JSON.stringify(errors)
  );
});

test("unlock-groups.<id>.categories.<id>.entries[].weight が非整数(小数)は弾かれる", () => {
  const errors = validate("tf-fishing-gimmick", fishingDoc(undefined, {
    treasure: { categories: { rare: { entries: [{ item: "DIAMOND", weight: 1.5, amount: 1 }] } } }
  }));
  assert.ok(
    errors.some((e) => /fishing\.unlock-groups\.treasure\.categories\.rare\.entries\[0\]\.weight/.test(e)),
    JSON.stringify(errors)
  );
});

test("unlock-groups が配列など不正な型ならエラー", () => {
  const errors = validate("tf-fishing-gimmick", fishingDoc(undefined, ["treasure"]));
  assert.ok(
    errors.some((e) => /fishing\.unlock-groups はマップである必要があります/.test(e)),
    JSON.stringify(errors)
  );
});

test("unlock-groups.<id>.categories.<id>.display-name が文字列でなければエラー", () => {
  const errors = validate("tf-fishing-gimmick", fishingDoc(undefined, {
    treasure: { categories: { rare: { "display-name": 123, entries: [] } } }
  }));
  assert.ok(
    errors.some((e) => /fishing\.unlock-groups\.treasure\.categories\.rare\.display-name: 文字列である必要があります/.test(e)),
    JSON.stringify(errors)
  );
});
