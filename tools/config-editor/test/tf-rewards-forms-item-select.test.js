"use strict";

// tf-rewards-forms.js 修正1 (2026-07-27) の回帰テスト。
//
// 背景: アチーブメント/図鑑の「付与アイテム (rewards.items)」欄は、内部IDそのままを
// datalist付きtextInputで表示していた。ユーザーからは日本語表示名が見えず、
// カタログID/バニラMaterial名を暗記していないと選べない状態だった。
//
// 修正: itemIdInput を util.js の window.itemRefSelect (listSelect ベースの共通ヘルパー)
// 経由に置き換えた。表示は「表示名 (ID)」、保存値は今までどおりID文字列そのもの。
// 候補に無い値(手書きID・未知のカタログID)は消さず、先頭候補として残す。
//
// listSelect 自体のDOM描画(開閉/位置計算/キーボード操作)は util.js の既存範囲であり、
// ここでは itemIdInput -> itemRefSelect が listSelect へ渡す cfg (options/value/allowCustom)
// の中身と、onChange で保存される値がID(ラベルではない)であることを検証する。

const test = require("node:test");
const assert = require("node:assert/strict");

function makeEl(tag, attrs) {
  const el = {
    tag,
    props: attrs || {},
    children: [],
    appendChild(c) { if (c != null && c !== false) el.children.push(c); return c; },
    addEventListener() {},
    querySelector() { return null; },
    querySelectorAll() { return []; }
  };
  Object.defineProperty(el, "innerHTML", {
    get() { return ""; },
    set() { el.children = []; }
  });
  return el;
}

// util.js を実物のまま読み込み、window.h だけ document 不要の軽量版へ差し替え、
// window.listSelect は cfg をそのまま捕捉するスタブへ差し替える。
// (window.itemRefSelect / window.fieldLabelEl / window.numberInput / window.textInput は
//  util.js の実装をそのまま使う — いずれも document.* を直接叩かない純粋な h() ラッパーのため。)
function setupDom() {
  global.window = global.window || {};
  global.document = global.document || {};
  global.alert = () => {};

  // 2026-07-31: バニラ Material の日本語主表示は window.LABELS.materialLabel* 経由なので、
  // 実物の labels.js を読み込む(document を一切触らないので素の window で足りる)。
  // スタブを置くと「辞書は引けているのにセレクトが生ID」という今回の症状を検知できない。
  delete require.cache[require.resolve("../public/js/labels.js")];
  require("../public/js/labels.js");

  delete require.cache[require.resolve("../public/js/util.js")];
  require("../public/js/util.js");

  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children != null) {
      (Array.isArray(children) ? children : [children]).forEach((c) => c != null && c !== false && el.appendChild(c));
    }
    return el;
  };

  const captured = [];
  global.window.listSelect = (cfg) => {
    const el = makeEl("span", { value: cfg.value });
    el.cfg = cfg;
    captured.push(cfg);
    return el;
  };

  global.window.MATERIALS = ["DIAMOND", "IRON_INGOT"];
  // 2026-07-29: アチーブメントの「基本」節に説明Lore(forms.js の共通lore行エディタ)が入った。
  // forms.js は document を直接触るのでここでは読み込まず、行数だけ数えられるスタブにする。
  global.window.renderLoreRows = (arr) => makeEl("div", { class: "lore-rows", loreLength: arr.length });
  // 2026-07-31: 表示名欄が colors.js の richTextInput(着色パレット付き入力)へ移った。
  // colors.js は document を直接触るのでここでは読み込まない。スタブが無いと
  // buildAchievementsForm が renderDetail の1行目で TypeError を投げ、この下の
  // アサーションが**1つも実行されないまま**テストが赤くなる(=回帰検知が死ぬ)。
  global.window.richTextInput = (value, mode, onInput) => {
    const el = makeEl("span", { class: "rich-host", mode, value });
    el.__onInput = onInput;
    return el;
  };

  delete require.cache[require.resolve("../public/js/tf-rewards-forms.js")];
  require("../public/js/tf-rewards-forms.js");

  return captured;
}

function makeAchievementData(itemId) {
  return {
    achievements: {
      ach1: {
        "display-name": "テスト称号",
        trigger: { type: "statistic", statistic: "JUMP", threshold: 1 },
        broadcast: false,
        rewards: {
          special: [], commands: [],
          items: [{ id: itemId, amount: 2 }],
          "job-exp": [], "permanent-buffs": {}
        }
      }
    }
  };
}

test("付与アイテムのセレクト: カタログ候補が表示名(primary)+ID(secondary)で候補に出る", () => {
  const captured = setupDom();
  const data = makeAchievementData("unknown_hand_item");
  const catalogCandidates = [{ id: "diamond_sword_plus", label: "ダイヤの剣+" }];

  global.window.buildAchievementsForm(data, { catalogCandidates });

  const itemCfgs = captured.filter((c) => c.className === "reward-item-id-input");
  assert.equal(itemCfgs.length, 1, "付与アイテム行のセレクトが1つ描画されているはず");
  const cfg = itemCfgs[0];

  const catalogOpt = cfg.options.find((o) => o.value === "diamond_sword_plus");
  assert.ok(catalogOpt, "カタログ候補が options に含まれていない(内部IDのまま出ている)");
  assert.equal(catalogOpt.primary, "ダイヤの剣+");
  assert.equal(catalogOpt.secondary, "diamond_sword_plus");

  assert.ok(cfg.options.some((o) => o.value === "DIAMOND"), "バニラMaterialが候補に出ていない");
});

test("付与アイテムのセレクト: 候補に無い手書きIDは消えず先頭候補として残る", () => {
  const captured = setupDom();
  const data = makeAchievementData("unknown_hand_item");

  global.window.buildAchievementsForm(data, { catalogCandidates: [] });

  const cfg = captured.filter((c) => c.className === "reward-item-id-input")[0];
  assert.equal(cfg.value, "unknown_hand_item");
  const unknownOpt = cfg.options.find((o) => o.value === "unknown_hand_item");
  assert.ok(unknownOpt, "候補に無い既存IDが options から消えている");
  assert.equal(unknownOpt.primary, "unknown_hand_item");
  assert.equal(cfg.options[0].value, "unknown_hand_item", "手書きIDは候補の先頭に差し込まれるはず");
  assert.equal(cfg.allowCustom, true, "allowCustomがtrueでないと手入力の余地が消える");
  assert.match(cfg.customPlaceholder, /直接入力/);
});

test("付与アイテムのセレクト: 選択後も保存値はID文字列のまま(表示名で上書きされない)", () => {
  const captured = setupDom();
  const data = makeAchievementData("");
  const catalogCandidates = [{ id: "diamond_sword_plus", label: "ダイヤの剣+" }];

  global.window.buildAchievementsForm(data, { catalogCandidates });
  const cfg = captured.filter((c) => c.className === "reward-item-id-input")[0];

  cfg.onChange("diamond_sword_plus");

  assert.equal(data.achievements.ach1.rewards.items[0].id, "diamond_sword_plus",
    "保存値がID(生値)のままになっていない");
});

// ============================================================
// 2026-07-31 報告「セレクトメニューが id 表記のまま日本語にならない」の回帰テスト。
//
// 出荷 achievements.yml の rewards.items[].id は **全16行が `custom:<id>` 形式**なのに、
// itemRefSelect の候補 value は素のカタログID。照合が必ず外れるので
// 「候補に無い値は先頭に残す」救済へ落ち、`custom:tf_gacha_ticket_5` が主表示になっていた。
// 表示だけ候補のラベルへ寄せ、保存値は verbatim（`custom:` を剥がして書き戻すと
// 「開いて保存しただけ」で16行の無関係な差分が出る。Java 側は両形式を解ける）。
// ============================================================
test("付与アイテムのセレクト: custom:<id> の現在値も日本語表示になり、保存値は custom: 付きのまま", () => {
  const captured = setupDom();
  const data = makeAchievementData("custom:tf_gacha_ticket_5");
  const catalogCandidates = [{ id: "tf_gacha_ticket_5", displayName: "ガチャ券【V】" }];

  global.window.buildAchievementsForm(data, { catalogCandidates });
  const cfg = captured.filter((c) => c.className === "reward-item-id-input")[0];

  assert.equal(cfg.value, "custom:tf_gacha_ticket_5", "現在値が書き換えられている");
  const opt = cfg.options[0];
  assert.equal(opt.value, "custom:tf_gacha_ticket_5",
    "custom: 付きの現在値が候補の先頭に残っていない");
  assert.equal(opt.primary, "ガチャ券【V】",
    "custom: 接頭辞のせいで候補と照合できず、生トークンが主表示になっている");
  assert.equal(opt.secondary, "custom:tf_gacha_ticket_5",
    "副表記に保存値(custom: 付き)を出して、実際に書かれる形が分かるようにする");

  // 素のカタログID候補は今までどおり別途載る (custom: 側と共存する)。
  assert.ok(cfg.options.some((o) => o.value === "tf_gacha_ticket_5" && o.primary === "ガチャ券【V】"));

  // 保存値は勝手に正規化しない。
  assert.equal(data.achievements.ach1.rewards.items[0].id, "custom:tf_gacha_ticket_5");
});

test("付与アイテムのセレクト: custom: でない未知IDは従来どおり生IDが主表示(既存契約を壊さない)", () => {
  const captured = setupDom();
  const data = makeAchievementData("unknown_hand_item");
  global.window.buildAchievementsForm(data, {
    catalogCandidates: [{ id: "tf_gacha_ticket_5", displayName: "ガチャ券【V】" }]
  });
  const cfg = captured.filter((c) => c.className === "reward-item-id-input")[0];
  assert.equal(cfg.options[0].value, "unknown_hand_item");
  assert.equal(cfg.options[0].primary, "unknown_hand_item");
  assert.equal(cfg.options[0].secondary, "");
});

test("付与アイテムのセレクト: custom: の中身が候補に無ければ生トークンのまま残す(ロスレス)", () => {
  const captured = setupDom();
  const data = makeAchievementData("custom:no_such_item");
  global.window.buildAchievementsForm(data, { catalogCandidates: [] });
  const cfg = captured.filter((c) => c.className === "reward-item-id-input")[0];
  assert.equal(cfg.options[0].value, "custom:no_such_item");
  assert.equal(cfg.options[0].primary, "custom:no_such_item");
});

// ------------------------------------------------------------
// アチーブメントの「図鑑対象 (collection.targets)」欄。
// 候補源が catalog.yml 単体だったため、ArsPaper materials.yml 由来の品が構造的に候補へ
// 入らず `item:dungeon_seal_binder` が生表示になっていた(実測 55 件中 31 件)。
// app.js 側は共通の候補源へ揃えたので、ここではフォームが「渡された候補を使って
// item:<id> を日本語で出せる」ことを固定する。
// ------------------------------------------------------------
test("図鑑対象のセレクト: materials.yml 由来の候補も日本語表示になる", () => {
  const captured = setupDom();
  const data = {
    achievements: {
      ach1: {
        "display-name": "印を集める",
        trigger: { type: "collection", collection: { scope: "item", targets: ["dungeon_seal_binder"], threshold: 1 } },
        broadcast: false,
        rewards: { special: [], commands: [], items: [], "job-exp": [], "permanent-buffs": {} }
      }
    }
  };
  global.window.buildAchievementsForm(data, {
    // materials.yml の display_name は &色コード入り。素の文字に落として出すこと。
    catalogCandidates: [{ id: "dungeon_seal_binder", displayName: "&e世界を繋ぐ者の印" }],
    collectionData: { categories: { items: {}, mobs: {} } }
  });

  const row = captured.find((c) => c.value === "item:dungeon_seal_binder");
  assert.ok(row, "図鑑対象の行が描画されていない");
  const opt = row.options.find((o) => o.value === "item:dungeon_seal_binder");
  assert.ok(opt, "候補源に materials 由来IDが入っていない(生の item:<id> 表示になる)");
  assert.equal(opt.primary, "アイテム: 世界を繋ぐ者の印");
});

// ------------------------------------------------------------
// 図鑑 (collection) の「カテゴリ: アイテム」欄。
// catalogItemSuggest しか使っておらずバニラ Material を候補に持たなかったため、
// 「遺物」カテゴリ(16件すべてバニラ Material)が primary=生ID / secondary="候補外" だった。
// アチーブメント画面の図鑑対象欄は既にバニラも載せているので、流儀を揃える。
// ------------------------------------------------------------
function makeCollectionData(entryId) {
  return {
    enabled: true,
    categories: {
      items: { relics: { "display-name": "遺物", order: 1, entries: [entryId] } },
      mobs: {}
    }
  };
}

/** 図鑑フォームを組み、「カテゴリ: アイテム」タブへ切り替える。 */
function buildCollectionAndOpenItems(data, opts) {
  const form = global.window.buildCollectionForm(data, opts);
  const tab = form.element.children[0].children
    .find((b) => b.children[0] && b.children[0].props.text === "カテゴリ: アイテム");
  assert.ok(tab, "「カテゴリ: アイテム」タブが無い");
  tab.props.onclick();
  return form;
}

test("図鑑のアイテム欄: バニラ Material が候補に入り「候補外」にならない", () => {
  const captured = setupDom();
  global.window.MATERIALS = ["ECHO_SHARD", "DIAMOND"];
  global.window.MATERIAL_LABELS = { ECHO_SHARD: "残響の欠片", DIAMOND: "ダイヤモンド" };

  buildCollectionAndOpenItems(makeCollectionData("ECHO_SHARD"), {
    catalogCandidates: [{ id: "abyss_sword", displayName: "<color:#3b1f5e>深淵の剣</color>" }]
  });

  const cfg = captured[captured.length - 1];
  assert.equal(cfg.value, "ECHO_SHARD");
  const echo = cfg.options.find((o) => o.value === "ECHO_SHARD");
  assert.ok(echo, "バニラ Material が候補に入っていない");
  assert.equal(echo.primary, "残響の欠片", "バニラ Material が生IDのまま表示されている");
  assert.notEqual(echo.secondary, "候補外", "候補集合に入っているのに「候補外」扱いになっている");
  // カタログ品はバニラより前に並ぶ(listSelect の描画上限200件でも必ず見える)。
  const catalogIdx = cfg.options.findIndex((o) => o.value === "abyss_sword");
  const vanillaIdx = cfg.options.findIndex((o) => o.value === "ECHO_SHARD");
  assert.ok(catalogIdx >= 0 && catalogIdx < vanillaIdx,
    "カタログ候補がバニラより後ろに並んでいる(描画上限でカタログ品が見えなくなる)");
  assert.equal(cfg.options[catalogIdx].primary, "深淵の剣", "MiniMessage タグが落ちていない");
});

test("図鑑のアイテム欄: 選択後の保存値はID文字列のまま", () => {
  const captured = setupDom();
  global.window.MATERIALS = ["ECHO_SHARD"];
  global.window.MATERIAL_LABELS = { ECHO_SHARD: "残響の欠片" };
  const data = makeCollectionData("ECHO_SHARD");
  buildCollectionAndOpenItems(data, { catalogCandidates: [{ id: "abyss_sword", displayName: "深淵の剣" }] });

  const cfg = captured[captured.length - 1];
  cfg.onChange("abyss_sword");
  assert.deepEqual(data.categories.items.relics.entries, ["abyss_sword"],
    "保存値が表示名やオブジェクトで上書きされている");
});
