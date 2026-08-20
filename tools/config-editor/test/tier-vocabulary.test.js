"use strict";

// タスク2 (2026-07-26) の回帰テスト: lib/tier-vocabulary.js の buildTierVocabulary。
// スキルツリー「機能解放」の feature:<id>(param="scale") が参照する各ギミックymlの tiers: から
// 実際に定義済みのtier番号一覧を抽出する純関数。対象は vein-mining / haste-active-mining /
// tree-fell / area-harvest の4件(gate-vocabulary.js FEATURES で param:"scale" の元4件と一致)に加え、
// 2026-07-26 tier-expand で xp-bottle-store-unlock / potion-merge(craftingFeatures) を追加。
// 2026-08-15: xp-bottle-store-unlock の参照先を fishing → craftingFeatures へ移設(実体設定が
// stats/fishing-gimmick.yml から progression/crafting-features.yml へ移ったのに追随)。
// 2026-07-28 (数値のギミックyml集約) で furnace-smelt-speed/bonus(smithing) と
// digging-durability-vanilla-exp/job-exp(digging) を追加 — この4件だけ tiers がymlの1段ネスト下
// (furnace-smelt.speed / durability-exp.vanilla-exp)にあるため、ドット区切りパス解決の回帰も見る。

const test = require("node:test");
const assert = require("node:assert/strict");
const { buildTierVocabulary, extractTierNumbers, SCALE_FEATURE_SECTIONS } = require("../lib/tier-vocabulary");

const EMPTY_ALL = {
  "vein-mining": [], "haste-active-mining": [], "haste-active-digging": [],
  "tree-fell": [], "area-harvest": [],
  "xp-bottle-store-unlock": [], "potion-merge": [],
  "furnace-smelt-speed": [], "furnace-smelt-bonus": [],
  "digging-durability-vanilla-exp": [], "digging-durability-job-exp": []
};

test("buildTierVocabulary: 各機構の定義済みtier番号を昇順で返す", () => {
  const gimmicks = {
    mining: {
      "vein-mining": { tiers: { "2": { "max-extra-blocks": 5 }, "1": { "max-extra-blocks": 2 } } },
      "haste-active-mining": { tiers: { "1": { amplifier: 0 } } }
    },
    woodcutting: {
      "tree-fell": { tiers: { "3": { "max-extra-logs": 9 }, "1": {}, "2": {} } }
    },
    farming: {
      "area-harvest": { tiers: { "1": { radius: 1 } } }
    },
    craftingFeatures: {
      "xp-bottle-store": { tiers: { "1": { "store-amount": 100 }, "2": { "store-amount": 200 } } },
      "potion-merge": { tiers: { "1": { "max-effects": 5 } } }
    },
    smithing: {
      "furnace-smelt": {
        speed: { percent: 10, tiers: { "3": { percent: 30 }, "1": { percent: 10 }, "2": { percent: 20 } } },
        bonus: { percent: 10, tiers: { "1": { percent: 10 } } }
      }
    },
    digging: {
      "durability-exp": {
        "vanilla-exp": { tiers: { "1": { "cap-percent": 50 } } },
        "job-exp": { tiers: { "1": { "cap-percent": 25 }, "2": { "cap-percent": 40 } } }
      }
    }
  };
  const result = buildTierVocabulary(gimmicks);
  assert.deepEqual(result["vein-mining"], [1, 2]);
  assert.deepEqual(result["haste-active-mining"], [1]);
  assert.deepEqual(result["tree-fell"], [1, 2, 3]);
  assert.deepEqual(result["area-harvest"], [1]);
  assert.deepEqual(result["xp-bottle-store-unlock"], [1, 2]);
  assert.deepEqual(result["potion-merge"], [1]);
  assert.deepEqual(result["furnace-smelt-speed"], [1, 2, 3]);
  assert.deepEqual(result["furnace-smelt-bonus"], [1]);
  assert.deepEqual(result["digging-durability-vanilla-exp"], [1]);
  assert.deepEqual(result["digging-durability-job-exp"], [1, 2]);
});

test("buildTierVocabulary: ネストしたkeyは途中の階層が欠けていても例外を投げず空配列", () => {
  // furnace-smelt はあるが speed が無い / durability-exp 自体が無い、のどちらでも壊れない。
  const result = buildTierVocabulary({
    smithing: { "furnace-smelt": { bonus: { tiers: { "1": {} } } } },
    digging: { "auto-mode-multiplier": 0.25 }
  });
  assert.deepEqual(result["furnace-smelt-speed"], []);
  assert.deepEqual(result["furnace-smelt-bonus"], [1]);
  assert.deepEqual(result["digging-durability-vanilla-exp"], []);
});

test("SCALE_FEATURE_SECTIONS: gate-vocabulary.js の param:'scale' 全件を網羅している(ドリフト検知)", () => {
  // 片方だけ増やすと「tier番号が自由入力に退化して気づかない」ので、両者の集合一致を強制する。
  const { FEATURES } = require("../lib/gate-vocabulary");
  const scaleIds = FEATURES.filter((f) => f.param === "scale").map((f) => f.id).sort();
  assert.deepEqual(Object.keys(SCALE_FEATURE_SECTIONS).sort(), scaleIds);
});

test("buildTierVocabulary: tiers未定義のセクションは空配列(フラット値のみ運用)", () => {
  const result = buildTierVocabulary({ mining: { "vein-mining": { "max-extra-blocks": 3 } } });
  assert.deepEqual(result["vein-mining"], []);
});

test("buildTierVocabulary: ギミックデータが全て欠損/nullでも例外を投げず全件空配列", () => {
  assert.deepEqual(buildTierVocabulary(null), EMPTY_ALL);
  assert.deepEqual(buildTierVocabulary({}), EMPTY_ALL);
});

test("extractTierNumbers: 不正キー(0以下・非整数・文字列)は無視する", () => {
  const section = { tiers: { "0": {}, "-1": {}, "abc": {}, "2": {}, "1.5": {} } };
  assert.deepEqual(extractTierNumbers(section), [2]);
});

test("extractTierNumbers: tiers が配列やスカラーの場合も空配列(壊れない)", () => {
  assert.deepEqual(extractTierNumbers({ tiers: [1, 2, 3] }), []);
  assert.deepEqual(extractTierNumbers({ tiers: "oops" }), []);
  assert.deepEqual(extractTierNumbers({}), []);
  assert.deepEqual(extractTierNumbers(null), []);
});
