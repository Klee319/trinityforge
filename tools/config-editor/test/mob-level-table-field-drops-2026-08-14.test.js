"use strict";

// combat/mob-level-table.yml の add-drops へ 2026-08-14 に足した3キー
// (chance-by-level / where / baby) の editor 側ミラー検証。
//
// 【なぜ要るか】yml に書けるのに editor が知らないキーは、
//   (1) スキーマが弾いて「手書きした yml をファイルごと保存できない」
//   (2) 往復で消えて「開いて保存しただけで設定が変わる」
// のどちらかで壊れる。ここでは出荷 yml そのものを読み込んで両方を否定する。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");
const { validate } = require("../lib/schema");
const { readConfig, serializeConfig } = require("../lib/yamlio");

const SHIPPED = path.join(
  __dirname, "..", "..", "..", "TrinityForge", "src", "main", "resources", "combat", "mob-level-table.yml"
);

function shippedData() {
  assert.ok(fs.existsSync(SHIPPED), "出荷 mob-level-table.yml が見つからない: " + SHIPPED);
  return readConfig(SHIPPED);
}

// --- スキーマ: 3キーの受理範囲が Java と一致していること -------------------------------------

test("tf-mob-level-table: chance-by-level / where / baby の正常値はエラーなし", () => {
  const errs = validate("tf-mob-level-table", {
    tiers: [{
      "min-level": 0,
      "add-drops": [{
        material: "custom:ravager_hide",
        chance: 0.05,
        "chance-by-level": { "from-level": 1, "from-chance": 0.05, "to-level": 100, "to-chance": 0.5 },
        min: 0,
        max: 2,
        mobs: ["RAVAGER"],
        where: "field"
      }, {
        material: "custom:thread_swiftcast",
        chance: 0.01,
        min: 1,
        max: 1,
        mobs: ["ZOMBIE"],
        where: "field",
        baby: true
      }, {
        material: "custom:gacha_ticket_1",
        chance: 0.005,
        min: 1,
        max: 1,
        where: "dungeon"
      }]
    }]
  });
  assert.deepEqual(errs, []);
});

test("tf-mob-level-table: 3キー未指定(省略)はエラーなし(後方互換)", () => {
  const errs = validate("tf-mob-level-table", {
    tiers: [{ "min-level": 0, "add-drops": [{ material: "BONE", chance: 0.1, min: 1, max: 1 }] }]
  });
  assert.deepEqual(errs, []);
});

test("tf-mob-level-table: where は field/dungeon/any 以外を弾く", () => {
  const errs = validate("tf-mob-level-table", {
    tiers: [{ "min-level": 0, "add-drops": [{ material: "BONE", chance: 0.1, min: 1, max: 1, where: "overworld" }] }]
  });
  assert.ok(errs.some((e) => e.includes("add-drops[0].where")));
  assert.deepEqual(
    validate("tf-mob-level-table", {
      tiers: [{ "min-level": 0, "add-drops": [{ material: "BONE", chance: 0.1, min: 1, max: 1, where: "any" }] }]
    }),
    []
  );
});

test("tf-mob-level-table: baby は真偽値以外を弾く", () => {
  const errs = validate("tf-mob-level-table", {
    tiers: [{ "min-level": 0, "add-drops": [{ material: "BONE", chance: 0.1, min: 1, max: 1, baby: "yes" }] }]
  });
  assert.ok(errs.some((e) => e.includes("add-drops[0].baby")));
});

test("tf-mob-level-table: chance-by-level の欠落項目/範囲外/逆転はエラー", () => {
  // 1項目でも欠けると Java 側がカーブごと捨てて素の chance に戻る = 設定したのに何も変わらない。
  const missing = validate("tf-mob-level-table", {
    tiers: [{
      "min-level": 0,
      "add-drops": [{
        material: "BONE", chance: 0.1, min: 1, max: 1,
        "chance-by-level": { "from-level": 1, "to-level": 100, "to-chance": 0.5 }
      }]
    }]
  });
  assert.ok(missing.some((e) => e.includes("chance-by-level.from-chance")));

  const outOfRange = validate("tf-mob-level-table", {
    tiers: [{
      "min-level": 0,
      "add-drops": [{
        material: "BONE", chance: 0.1, min: 1, max: 1,
        "chance-by-level": { "from-level": 1, "from-chance": -0.1, "to-level": 100, "to-chance": 1.5 }
      }]
    }]
  });
  assert.ok(outOfRange.some((e) => e.includes("chance-by-level.from-chance")));
  assert.ok(outOfRange.some((e) => e.includes("chance-by-level.to-chance")));

  const inverted = validate("tf-mob-level-table", {
    tiers: [{
      "min-level": 0,
      "add-drops": [{
        material: "BONE", chance: 0.1, min: 1, max: 1,
        "chance-by-level": { "from-level": 100, "from-chance": 0.05, "to-level": 100, "to-chance": 0.5 }
      }]
    }]
  });
  assert.ok(inverted.some((e) => e.includes("to-level")));

  const notAMap = validate("tf-mob-level-table", {
    tiers: [{
      "min-level": 0,
      "add-drops": [{ material: "BONE", chance: 0.1, min: 1, max: 1, "chance-by-level": [1, 2] }]
    }]
  });
  assert.ok(notAMap.some((e) => e.includes("chance-by-level")));
});

// --- 出荷 yml: そのまま開けて、往復しても3キーが消えないこと ---------------------------------

test("出荷 mob-level-table.yml は editor のスキーマをそのまま通る", () => {
  const { data } = shippedData();
  assert.deepEqual(validate("tf-mob-level-table", data), [],
    "出荷 yml が editor で保存できない状態。手書きした yml をファイルごと保存できなくなる");
});

test("出荷 mob-level-table.yml を editor で往復しても chance-by-level / where / baby が消えない", () => {
  const { data, raw } = shippedData();
  const roundTripped = YAML.parse(serializeConfig(data, raw));

  // 帯・エントリの構造がそのまま残っていること。
  assert.equal(roundTripped.tiers.length, data.tiers.length);
  for (let i = 0; i < data.tiers.length; i++) {
    assert.deepEqual(roundTripped.tiers[i]["add-drops"], data.tiers[i]["add-drops"],
      `帯 #${i} (min-level=${data.tiers[i]["min-level"]}) の add-drops が往復で変わった`);
  }

  // 3キーが実際に出荷 yml に載っていること(載っていなければこの検査は素通りしてしまう)。
  const all = roundTripped.tiers.flatMap((t) => t["add-drops"] || []);
  assert.ok(all.some((d) => d["chance-by-level"]), "往復後に chance-by-level を持つエントリが1件も無い");
  assert.ok(all.some((d) => d.where === "field"), "往復後に where: field のエントリが1件も無い");
  assert.ok(all.some((d) => d.where === "dungeon"), "往復後に where: dungeon のエントリが1件も無い");
  assert.ok(all.some((d) => d.baby === true), "往復後に baby: true のエントリが1件も無い");

  // カーブの中身も数値のまま(文字列化していない)。
  const curved = all.find((d) => d["chance-by-level"]);
  for (const key of ["from-level", "from-chance", "to-level", "to-chance"]) {
    assert.equal(typeof curved["chance-by-level"][key], "number",
      `往復後の chance-by-level.${key} が数値でない`);
  }
});

// --- 出荷 yml: 帯をまたいだ内容の一致 -----------------------------------------------------------
//
// 【帯ごとに変わってよいもの / いけないもの】
//   Java 側の帯解決は floor lookup —— min-level <= L を満たす【最大の帯が1つだけ】選ばれ、
//   上の帯から下の帯へ継承されない。だから「全帯に置くつもりのエントリ」が1帯でも欠けると、
//   その帯のレベルのモブからは1個も落ちない(例外もログも出ない無言の no-op)。
//   一方で、帯ごとに違ってよいものが2つある:
//     (a) chance-by-level を持たないエントリの chance —— 帯ごとの階段
//         (素の券 gacha_ticket_0 が 0.005/0.008/0.012/0.016/0.02/0.025)。
//         1帯しか選ばれない以上、帯ごとに違う値でも二重取りにはならない。
//         下で「帯が上がるほど下がらない」ことだけを別途固定する。
//     (b) 意図して【ちょうど1帯】だけに置くエントリ —— ガチャ券【II〜IV】が
//         券の番号と帯(Lv45/65/85)を対応させている。全帯へ複製すると Lv0 の敵からも
//         最上位券が出てしまうので、複製してはいけない。
//   この2つ以外(素材・where・min/max・mobs・baby・chance-by-level)は全帯一致でなければならない。
//   Java 側の ShippedFieldDropWiringTest と同じ形。ずれると editor 側だけ赤くなる。

/** 素材ごとに、それが載っている帯の index 集合を作る。 */
function bandsByMaterial(tiers) {
  const bands = new Map();
  tiers.forEach((tier, index) => {
    for (const drop of tier["add-drops"] || []) {
      const material = String(drop.material || "").trim().toLowerCase();
      if (!material) continue;
      if (!bands.has(material)) bands.set(material, new Set());
      bands.get(material).add(index);
    }
  });
  return bands;
}

/** キー順・型ゆれに依存しない比較用の文字列。chance は階段用に落とすことがある。 */
function canonicalDrop(drop) {
  const copy = { ...drop };
  if (copy["chance-by-level"] === undefined || copy["chance-by-level"] === null) {
    delete copy.chance;
  }
  const sortDeep = (value) => {
    if (Array.isArray(value)) return value.map(sortDeep);
    if (value && typeof value === "object") {
      return Object.keys(value).sort().reduce((acc, key) => {
        acc[key] = sortDeep(value[key]);
        return acc;
      }, {});
    }
    return value;
  };
  return JSON.stringify(sortDeep(copy));
}

test("出荷 mob-level-table.yml: 各素材は『全帯』か『ちょうど1帯』のどちらか(中途半端な残り方は削除事故)", () => {
  const { data } = shippedData();
  const tiers = data.tiers;
  assert.ok(tiers.length >= 2, "帯が1つしかないとこの検査は素通りする");

  const problems = [];
  for (const [material, bands] of bandsByMaterial(tiers)) {
    if (bands.size !== 1 && bands.size !== tiers.length) {
      problems.push(`${material}: ${bands.size}/${tiers.length} 帯`);
    }
  }
  assert.deepEqual(problems, [],
    "add-drops に『一部の帯にだけ残っている』素材がある。帯は floor lookup で1つしか選ばれないので、"
    + "書かれていない帯のレベルのモブからは1個も落ちない。許される形は『全帯』か『ちょうど1帯』だけ");
});

test("出荷 mob-level-table.yml: 全帯に載る add-drops は6帯すべてで内容が同一(chance の階段だけは例外)", () => {
  const { data } = shippedData();
  const tiers = data.tiers;
  const bands = bandsByMaterial(tiers);

  const allBandMaterials = new Set(
    [...bands.entries()].filter(([, set]) => set.size === tiers.length).map(([material]) => material)
  );
  assert.ok(allBandMaterials.size > 0, "全帯に載っている素材が1件も無い(配線ごと消えている)");

  const canonical = tiers.map((tier) =>
    (tier["add-drops"] || [])
      .filter((drop) => allBandMaterials.has(String(drop.material || "").trim().toLowerCase()))
      .map(canonicalDrop)
      .join("\n")
  );
  const distinct = new Set(canonical);
  assert.equal(distinct.size, 1,
    "帯によって add-drops が違う。Java 側の帯解決は floor lookup で1つの帯しか選ばれず、"
    + "上の帯から下の帯へ継承されないので、内容の薄い帯のレベルのモブからはその差分が落ちない");
  assert.ok(canonical[0].length > 0, "全帯共通の add-drops が空(配線ごと消えている)");
});

test("出荷 mob-level-table.yml: 帯ごとに chance を変えているエントリは、帯が上がるほど下がらない", () => {
  const { data } = shippedData();
  // chance-by-level を持つエントリはレベル補間側で右上がりが保証されるので対象外。
  // ここで見るのは「帯ごとの階段」で確率を上げているエントリだけ。
  const steps = new Map();
  for (const tier of data.tiers) {
    const minLevel = Number(tier["min-level"]);
    for (const drop of tier["add-drops"] || []) {
      if (drop["chance-by-level"]) continue;
      const material = String(drop.material || "").trim().toLowerCase();
      if (!material) continue;
      if (!steps.has(material)) steps.set(material, []);
      steps.get(material).push({ minLevel, chance: Number(drop.chance) });
    }
  }
  assert.ok(steps.size > 0, "chance-by-level を持たないエントリが1件も無い(この検査が素通りする)");

  const problems = [];
  for (const [material, rows] of steps) {
    rows.sort((a, b) => a.minLevel - b.minLevel);
    for (let i = 1; i < rows.length; i++) {
      if (rows[i].chance < rows[i - 1].chance) {
        problems.push(`${material}: min-level ${rows[i].minLevel} の chance ${rows[i].chance} が`
          + ` 下の帯(${rows[i - 1].chance})より低い`);
      }
    }
  }
  assert.deepEqual(problems, [],
    "帯ごとに chance を変えているエントリで、帯が上がるほど確率が下がっている。"
    + "『強い(=レベルの高い)モブを狩るほど出る』という報酬設計と逆になっている");
});
