"use strict";

// 機能アイテムタブ (魔法カテゴリ・schema: ars-functional-items)。
//
// 2026-07-25 に定義が functional-items.yml の1ファイルへ統合された(旧: catalog.yml 3件 +
// items.yml レシピに分裂)。編集可能キーは display-name / lore / enchant-glow / material(一部) /
// recipe の5つのみで、全て単一ファイル内で完結する(旧来の source:"catalog"|"items" 振り分けや
// recipeKey(waystone_craft 等)の特別扱いは、統合により不要になったため削除した)。
//
// 対象8アイテム: dominion_wand / teleport_compass / pedestal / ritual_core /
//                scribing_table / waystone / infinity_source_core / source_berry
// 内部ID(このファイルのitems.<id>キー名そのもの)は fork Java 実装が直接参照する固定値のため
// editorからは新規追加/削除/リネーム不可(読み取り専用のIDチップとしてのみ表示)。
//
// material の編集可否は3件(dominion_wand/teleport_compass/source_berry=保持アイテム)のみ許可。
// 残り5件(ブロック系)は fork の FunctionalItemConfig.java#MATERIAL_OVERRIDE_ALLOWED で拒否され
// warning ログのみで無視される(TileState対応判定・儀式の近傍探索がMaterialに密結合のため)。
// この許可リストは Java 側が唯一の正典。JS側の MATERIAL_EDITABLE_IDS はその複製であり、
// test/functional-items-java-parity.test.js が FunctionalItemConfig.java のソースを直接
// 正規表現で読み取って自動ドリフト検知する(lib/gate-vocabulary.js の FEATURES と同じ手法)。

(function (root, isBrowser) {
  // ============================================================
  // 純関数コア (ブラウザ非依存。Node テストから直接 require 可能)
  // ============================================================

  function clone(v) {
    return v === undefined ? undefined : JSON.parse(JSON.stringify(v));
  }

  // functional-items.yml items.<id> の正典8件 (ファイル内の並び順)。
  // 2026-08-24 追加: infinity_source_core。実体は Java のカスタムブロック
  // (InfinitySourceCore) だが定義だけ materials.yml に残っており、ブロック登録済み id は
  // 素材アイテム登録の側で丸ごとスキップされるため、素材画面で直しても無反応だった
  // (効いていたのは recipe だけ)。実装を Java で特別扱いするアイテムはこの画面が正典。
  const FUNCTIONAL_ITEM_IDS = Object.freeze([
    "dominion_wand", "teleport_compass", "pedestal",
    "ritual_core", "scribing_table", "waystone", "infinity_source_core", "source_berry"
  ]);

  // ============================================================
  // エンチャント本 (儀式) — 2026-08-14 に items.yml から移設
  // ============================================================
  //
  // 実サーバ報告「回生とマナ関連のエンチャント本の儀式レシピが消えている」の正体は、
  // items.yml 用の画面(schema: ars-recipes)が app.js で常に onlyEffects:true で構築されており
  // items: セクションを1件も描画しないこと。yml にもゲーム内にも最初から在ったが、
  // editor からは見ることも編集することもできなかった。
  //
  // 移設先を functional-items.yml にしたのは、fork の FunctionalItemConfig が items: の全キーを
  // 総なめで読むため display-name / lore / enchant-glow の上書きがそのまま効くから
  // (専用の config 層を新設しない、というユーザー方針)。EnchantBookRitualEffect 側に
  // 参照を配線済み。レシピの登録キーはエントリIDそのままなので、儀式の成立条件も
  // 解放ゲート(unlock-gate.yml の ritual-perks)のキーも移設で変わらない。
  //
  // ID は recipe.effect-params が結果を決めるため editor から追加/削除/リネームしない
  // (読み取り専用のIDチップとして出す。7件の機能アイテムと同じ扱い)。
  const ARS_ENCHANT_BOOK_IDS = Object.freeze([
    "enchant_book_mana_regen_1", "enchant_book_mana_regen_2", "enchant_book_mana_regen_3",
    "enchant_book_mana_boost_1", "enchant_book_mana_boost_2", "enchant_book_mana_boost_3",
    "enchant_book_share", "enchant_book_soulbound"
  ]);
  const ARS_ENCHANT_BOOK_LABELS = Object.freeze({
    enchant_book_mana_regen_1: "マナ再生 I",
    enchant_book_mana_regen_2: "マナ再生 II",
    enchant_book_mana_regen_3: "マナ再生 III",
    enchant_book_mana_boost_1: "マナ上昇 I",
    enchant_book_mana_boost_2: "マナ上昇 II",
    enchant_book_mana_boost_3: "マナ上昇 III",
    enchant_book_share: "共有",
    enchant_book_soulbound: "回生"
  });

  // エントリの1件目の儀式レシピを返す (recipe: / recipes: の両方を見る。無ければ null)。
  // エンチャント本の name / effect-type / effect-params はこのオブジェクトの中にある。
  function firstRitualRecipe(entry) {
    if (!entry || typeof entry !== "object") return null;
    const list = [];
    if (entry.recipe && typeof entry.recipe === "object" && !Array.isArray(entry.recipe)) list.push(entry.recipe);
    if (Array.isArray(entry.recipes)) {
      for (const r of entry.recipes) if (r && typeof r === "object" && !Array.isArray(r)) list.push(r);
    }
    for (const r of list) {
      if (String(r.method || "").trim().toLowerCase() === "ritual") return r;
    }
    return null;
  }

  // material 上書きが許可されるID (保持アイテムのみ)。
  // ★唯一の正典は FunctionalItemConfig.java の MATERIAL_OVERRIDE_ALLOWED。変更したら
  //   test/functional-items-java-parity.test.js を必ず流して同期を確認すること。
  const MATERIAL_EDITABLE_IDS = Object.freeze(["dominion_wand", "teleport_compass", "source_berry"]);

  function isMaterialEditable(id) {
    return MATERIAL_EDITABLE_IDS.indexOf(id) !== -1;
  }

  // ============================================================
  // TrinityForge 側の特殊アイテム2件 (catalog.yml、この画面へ統合表示)
  // ============================================================
  //
  // 2026-07-27新設: 「TF と Ars はユーザー目線では統合されているべき」というユーザー方針により、
  // catalog.yml (TrinityForge 側) の skill_node_lock / skill_tree_reset を、この「特殊アイテム」
  // 画面(旧名: 機能アイテム、schema: ars-functional-items)へ統合する。
  // 定義自体は catalog.yml に残したまま(Java 側の再ビルドは不要)。
  //
  // ID は com.trinityforge.skilltree.runtime.SkillTreeItems が直接参照する固定値のため
  // (catalog.yml 4346-4348行のコメント参照)、editor からは常に読み取り専用。
  // 唯一の正典は catalog.yml 側のコメントと SkillTreeItems.java。この2件は Ars の7件と違い、
  // material 上書き許可の概念(FunctionalItemConfig)とは無関係の別ファイル・別仕組みなので
  // MATERIAL_EDITABLE_IDS には混ぜない。
  // 2026-08-04追加の3件(role_reselect_ticket/stat_reroll_ticket/quality_upgrade_ticket)は
  // resourcepack/cmd-registry.json が別セッション編集中だったため custom-model-data 未設定のまま
  // 出荷している(CMD割当は reports/ACTIVE_RECORD.md 追跡の後追いタスク)。UI上は他の2件と同じ
  // カードで material/CMD/表示名/enchant-glow/lore/recipe を編集できる(CMDが空欄なだけ)。
  // 2026-08-24追加の3件(exp_cleanse_tonic_*)は「EXP解呪の良薬」。日次逓減(stats/skill-exp.yml の
  // daily-diminishing)を飲んだ瞬間だけ全スキル一括で引き戻す使い切りで、引き戻す先の倍率は
  // com.trinityforge.items.ExpCleanseTonic が持つ固定値(50/75/90%)。ここから編集できるのは
  // 見た目とレシピだけで、倍率は editor 側に無い。custom-model-data も未割り当て。
  const TF_SPECIAL_ITEM_IDS = Object.freeze([
    "skill_node_lock", "skill_tree_reset",
    "role_reselect_ticket", "stat_reroll_ticket", "quality_upgrade_ticket",
    "exp_cleanse_tonic_lesser", "exp_cleanse_tonic_greater", "exp_cleanse_tonic_supreme"
  ]);
  const TF_SPECIAL_ITEM_LABELS = Object.freeze({
    skill_node_lock: "スキルノードの楔",
    skill_tree_reset: "スキル再構築の書",
    role_reselect_ticket: "職業付け替えの証",
    stat_reroll_ticket: "厳選やり直しの護符",
    quality_upgrade_ticket: "品質昇華の結晶",
    exp_cleanse_tonic_lesser: "EXP解呪の良薬・並 (50%まで)",
    exp_cleanse_tonic_greater: "EXP解呪の良薬・上 (75%まで)",
    exp_cleanse_tonic_supreme: "EXP解呪の良薬・極 (90%まで)"
  });

  // catalog.yml の items.<id> のうち TF 特殊アイテム2件だけを、無ければ空オブジェクトで補完する。
  // normalizeFunctionalItemsData と異なり catalogData を**直接**書き換える(clone しない)。
  // この画面は catalog.yml を丸ごとコンパニオン保存する(getExtraSaves)ため、渡された参照そのものを
  // working として使い続ける必要がある(clone すると保存時に catalog.yml の他の内容と食い違う)。
  function ensureTfSpecialItems(catalogData) {
    const out = catalogData && typeof catalogData === "object" ? catalogData : {};
    if (!out.items || typeof out.items !== "object") out.items = {};
    for (const id of TF_SPECIAL_ITEM_IDS) {
      if (!out.items[id] || typeof out.items[id] !== "object") out.items[id] = {};
    }
    return out;
  }

  // functional-items.yml の生データ(items.<id>)を、正典7件が(無ければ空オブジェクトで)
  // 必ず存在する形に正規化する。元データは変更しない。未知のキー(想定外のid)は温存する
  // (削除により手編集データを壊さないため)。
  function normalizeFunctionalItemsData(data) {
    const out = clone(data && typeof data === "object" ? data : {}) || {};
    if (!out.items || typeof out.items !== "object") out.items = {};
    for (const id of FUNCTIONAL_ITEM_IDS) {
      if (!Object.prototype.hasOwnProperty.call(out.items, id)
          || out.items[id] === null || typeof out.items[id] !== "object") {
        out.items[id] = {};
      }
    }
    return out;
  }

  // 保存直前の整形: 空の lore 配列はキーごと落とす(表示用に補完した空配列をYAMLへ出力しない)。
  // それ以外のフィールドは呼び出し側 (UI側のハンドラ) が setOrDelete 相当で既に整理済みの前提。
  function serializeFunctionalItemsData(data) {
    const out = clone(data && typeof data === "object" ? data : {}) || {};
    if (!out.items || typeof out.items !== "object") return out;
    for (const id of Object.keys(out.items)) {
      const entry = out.items[id];
      if (!entry || typeof entry !== "object") continue;
      if (Array.isArray(entry.lore) && entry.lore.length === 0) delete entry.lore;
    }
    return out;
  }

  const CORE_LOGIC = {
    FUNCTIONAL_ITEM_IDS,
    MATERIAL_EDITABLE_IDS,
    isMaterialEditable,
    normalizeFunctionalItemsData,
    serializeFunctionalItemsData,
    TF_SPECIAL_ITEM_IDS,
    TF_SPECIAL_ITEM_LABELS,
    ensureTfSpecialItems,
    ARS_ENCHANT_BOOK_IDS,
    ARS_ENCHANT_BOOK_LABELS,
    firstRitualRecipe
  };

  root.FUNCTIONAL_ITEMS_CORE = CORE_LOGIC;
  if (typeof module !== "undefined" && module.exports) module.exports = CORE_LOGIC;

  // ============================================================
  // DOM 部品 (ブラウザ専用)
  // ============================================================
  if (!isBrowser) return;

  const h = window.h;
  const fieldRow = window.RECIPES_UI.fieldRow;

  // 空文字/未指定は該当キーを削除する (catalog タブの setOrDelete と同じ挙動)。
  function setOrDelete(obj, key, value) {
    if (value === "" || value === null || value === undefined) delete obj[key];
    else obj[key] = value;
  }

  const ITEM_LABELS = {
    dominion_wand: "ドミニオンワンド",
    teleport_compass: "テレポートコンパス",
    pedestal: "台座",
    ritual_core: "儀式の核",
    scribing_table: "筆記台",
    waystone: "ウェイストーン",
    source_berry: "ソースベリー"
  };

  window.buildFunctionalItemsForm = function buildFunctionalItemsForm(data, opts) {
    const options = opts && typeof opts === "object" ? opts : {};
    const working = CORE_LOGIC.normalizeFunctionalItemsData(data);
    // 2026-07-27: TF の特殊アイテム2件(catalog.yml)をこの画面のコンパニオンとして統合する。
    // catalogData が渡された場合のみ(未読み込み時は undefined)、下部に専用セクションを追加する。
    const hasCatalog = options.catalogData !== undefined && options.catalogData !== null;
    const catalogWorking = hasCatalog ? CORE_LOGIC.ensureTfSpecialItems(options.catalogData) : null;
    const root = h("div", { class: "dedicated-form functional-items-form" });
    const listBox = h("div", { class: "card-list" });
    const expandedCards = new Set();

    root.appendChild(h("div", { class: "sub-title", text: "ArsPaper 機能アイテム" }));
    root.appendChild(h("div", { class: "form-hint", text:
      "内部ID (このアイテムを識別するキー) はプログラム制御のため変更できません。"
      + " 表示名・lore・エンチャント光・レシピは全アイテムで編集できます。"
      + " material は「保持アイテム」3種(ワンド/コンパス/ベリー)のみ編集可能です"
      + "(台座/儀式の核/筆記台/ウェイストーンはブロック実装がMaterialに密結合のため、フォーク側で変更が無視されます)。"
    }));
    root.appendChild(listBox);

    // レシピ入力の custom: 候補サジェストは、カタログ/素材/items/threads/外部アイテムを横断して
    // 集める共通ロード(recipes.js の RECIPES_UI.ensureCustomDatalist)をそのまま使う。
    if (window.RECIPES_UI && typeof window.RECIPES_UI.ensureCustomDatalist === "function") {
      window.RECIPES_UI.ensureCustomDatalist().catch(() => {});
    }

    // 2026-08-18 (W-52): この画面の7件のうち4件(pedestal/ritual_core/scribing_table/waystone)は
    // material を持たない(仕様上編集不可)ため、catalog-candidates.js の候補生成側で拾えても
    // 表示名が古いまま(このセッション中の display-name 編集を追わない)ことがある。
    // ars-forms.js の buildMaterialsForm と同じ「render() のたびに現在値で再登録する」パターンに
    // 揃え、custom: 参照(解放ゲート・レシピの ingredient/pedestal-items 欄)の表示名を
    // このセッション中の編集にも追随させる。
    function registerCustomLabels() {
      if (typeof window.setCustomItemCandidates !== "function") return;
      window.setCustomItemCandidates(CORE_LOGIC.FUNCTIONAL_ITEM_IDS.map((fid) => ({
        id: fid,
        label: window.stripDisplayNamePlain(working.items[fid]["display-name"]) || ITEM_LABELS[fid] || fid
      })), { replace: false });
    }

    function render() {
      listBox.innerHTML = "";
      registerCustomLabels();
      for (const id of CORE_LOGIC.FUNCTIONAL_ITEM_IDS) listBox.appendChild(renderCard(id));
    }

    function renderCard(id) {
      const entry = working.items[id];
      if (!Array.isArray(entry.lore)) entry.lore = [];
      const materialEditable = CORE_LOGIC.isMaterialEditable(id);

      const plainDisplay = window.stripDisplayNamePlain(entry["display-name"]) || ITEM_LABELS[id] || id;
      const head = [
        h("div", { class: "entry-collapse-summary" }, [
          h("span", { class: "entry-sum-name", text: plainDisplay }),
          h("span", { class: "entry-sum-id", text: id })
        ])
      ];

      const idChip = h("div", { class: "func-item-meta" }, [
        h("div", {
          class: "func-item-chip is-readonly",
          title: "プログラム制御のため変更不可 (fork Java ハードコード)"
        }, [
          h("span", { class: "mini-label", text: "item id" }),
          h("span", { class: "func-item-chip-value", text: id })
        ])
      ]);

      // tooltip ライブプレビュー (catalog タブと同じ部品)。
      const preview = window.buildTooltipPreview();
      function refreshPreview() {
        const nm = entry["display-name"];
        preview.update({
          name: (nm != null && nm !== "") ? nm : (ITEM_LABELS[id] || id),
          nameMode: "minimessage",
          loreLines: Array.isArray(entry.lore) ? entry.lore : [],
          loreMode: "minimessage"
        });
      }

      const inputChildren = [];

      if (materialEditable) {
        // 2026-08-02: materialHintEl は削除 (materialInput 自身が 2026-07-29 の listSelect 移行で
        // 既に日本語表示名(primary)を出しているため、隣に並べると同じ名前が2回出て行が潰れる)。
        const matInput = window.materialInput(entry.material, "material-list", (v) => {
          setOrDelete(entry, "material", v);
        });
        inputChildren.push(fieldRow("material", h("span", { class: "input-with-hint" }, [matInput])));
      } else {
        inputChildren.push(h("div", { class: "empty-guide" }, [
          h("div", { class: "empty-guide-title", text: "material 編集不可" }),
          h("div", { class: "empty-guide-hint", text:
            "このアイテムはブロック実装(TileState対応判定・儀式の近傍探索)がMaterialに密結合しているため、"
            + "functional-items.yml に material を書いてもフォーク側で無視されます(警告ログのみ)。" })
        ]));
      }

      inputChildren.push(
        fieldRow("display-name", window.richTextInput(entry["display-name"], "minimessage", (v) => {
          setOrDelete(entry, "display-name", v);
          refreshPreview();
        })),
        fieldRow("enchant-glow", (() => {
          const row = h("label", { class: "form-field inline-check" });
          row.appendChild(window.checkboxInput(!!entry["enchant-glow"], (v) => {
            if (v) entry["enchant-glow"] = true; else delete entry["enchant-glow"];
          }));
          row.appendChild(h("span", { class: "form-label", text: "enchant aura (オンでエンチャント光)" }));
          return row;
        })()),
        h("div", { class: "sub-title", text: "フレーバー説明文 (lore)" }),
        window.renderLoreRows(entry.lore, "minimessage", refreshPreview, () => render())
      );

      const inputs = h("div", { class: "entry-inputs" }, [idChip].concat(inputChildren));
      const previewCol = h("div", { class: "entry-preview" }, [
        h("div", { class: "preview-label", text: "表示プレビュー" }),
        preview.element,
        h("div", { class: "preview-note", text: "品質ティア行・自動ステ行はここには表示されません。lore はその前に差し込まれるフレーバー説明文です。" })
      ]);

      refreshPreview();

      // レシピ編集UIはアイテムカテゴリ(catalog)タブと完全共通化する。結果アイテムは常にこの
      // エントリ自身(custom:<id>)のため結果アイテム欄は無く、renderCatalogRecipeSection側の
      // 仕様どおりそのまま合致する。
      const recipeSection = window.renderCatalogRecipeSection(entry, () => render(), working.items, id, { allowMirror: true });

      const card = window.collapsibleCard(head, [h("div", { class: "entry-2col" }, [inputs, previewCol]), recipeSection], {
        expanded: expandedCards.has(id),
        onToggle: (open) => { if (open) expandedCards.add(id); else expandedCards.delete(id); }
      });
      card.classList.add("recipe-card");
      return card;
    }

    render();

    root.appendChild(buildEnchantBookSection(working).element);

    if (hasCatalog) {
      root.appendChild(buildTfSpecialItemsSection(catalogWorking).element);
    }

    return {
      element: root,
      getData: () => CORE_LOGIC.serializeFunctionalItemsData(working),
      // catalog.yml は該当2件だけを触り、それ以外はロスレスに丸ごと書き戻す(afk / crafting-features
      // と同じコンパニオン方式)。
      getExtraSaves: () => hasCatalog ? [{ id: "catalog", data: catalogWorking }] : []
    };
  };

  // エンチャント本 (儀式) 専用のカード群。同じ functional-items.yml の items: を編集するので
  // working をそのまま受け取る (getData は呼び出し側の1本で足りる。companion保存は不要)。
  //
  // 7件の機能アイテムとの違いは3点だけ:
  //   - material 欄を出さない (結果は常に ENCHANTED_BOOK。格納エンチャントのメタが必須なので変更不可)
  //   - 儀式レシピの表示名 (recipe.name) を出す (儀式ブラウザGUIに出るのはこちら)
  //   - エンチャント種別/レベル (recipe.effect-params) を読み取り専用チップで見せる
  //     (プログラムが結果を決める値なので editor から触らせない)
  function buildEnchantBookSection(working) {
    const root = h("div", { class: "func-enchant-book-section" });
    root.appendChild(h("div", { class: "sub-title", text: "ArsPaper エンチャント本 (儀式)" }));
    root.appendChild(h("div", { class: "form-hint", text:
      "本(BOOK)を儀式の核に置き、台座の素材と Source を消費してカスタムエンチャント本を作る儀式です。"
      + " 内部ID とエンチャント種別/レベルはプログラムが結果を決めるため変更できません。"
      + " 表示名・lore・エンチャント光・レシピ(核/台座/Source)は編集できます。"
      + " 表示名と lore を空にすると「<エンチャント名> <ローマ数字>」の既定表示に戻ります。"
    }));
    const listBox = h("div", { class: "card-list" });
    root.appendChild(listBox);
    const expandedCards = new Set();

    function presentIds() {
      return CORE_LOGIC.ARS_ENCHANT_BOOK_IDS.filter((id) =>
        working.items[id] && typeof working.items[id] === "object");
    }

    function registerCustomLabels(ids) {
      if (typeof window.setCustomItemCandidates !== "function") return;
      window.setCustomItemCandidates(ids.map((eid) => ({
        id: eid,
        label: window.stripDisplayNamePlain(working.items[eid]["display-name"])
          || CORE_LOGIC.ARS_ENCHANT_BOOK_LABELS[eid] || eid
      })), { replace: false });
    }

    function render() {
      listBox.innerHTML = "";
      const ids = presentIds();
      registerCustomLabels(ids);
      if (ids.length === 0) {
        listBox.appendChild(h("div", { class: "empty-guide" }, [
          h("div", { class: "empty-guide-title", text: "エンチャント本の定義がありません" }),
          h("div", { class: "empty-guide-hint", text:
            "functional-items.yml の items: に enchant_book_* が1件もありません。"
            + "出荷ymlから消えているか、旧レイアウト(items.yml 側)のままです。" })
        ]));
        return;
      }
      for (const id of ids) listBox.appendChild(renderCard(id));
    }

    function renderCard(id) {
      const entry = working.items[id];
      if (!Array.isArray(entry.lore)) entry.lore = [];
      const label = CORE_LOGIC.ARS_ENCHANT_BOOK_LABELS[id] || id;

      const plainDisplay = window.stripDisplayNamePlain(entry["display-name"]) || label;
      const head = [
        h("div", { class: "entry-collapse-summary" }, [
          h("span", { class: "entry-sum-name", text: plainDisplay }),
          h("span", { class: "entry-sum-id", text: id })
        ])
      ];

      const ritual = CORE_LOGIC.firstRitualRecipe(entry);
      const params = ritual && ritual["effect-params"] && typeof ritual["effect-params"] === "object"
        ? ritual["effect-params"] : {};
      const chips = [
        h("div", {
          class: "func-item-chip is-readonly",
          title: "プログラム制御のため変更不可 (レシピの登録キー・解放ゲートのキーと同一)"
        }, [
          h("span", { class: "mini-label", text: "item id" }),
          h("span", { class: "func-item-chip-value", text: id })
        ]),
        h("div", {
          class: "func-item-chip is-readonly",
          title: "recipe.effect-params。EnchantBookRitualEffect が結果のエンチャントを決める値"
        }, [
          h("span", { class: "mini-label", text: "enchantment" }),
          h("span", { class: "func-item-chip-value", text:
            String(params.enchantment != null ? params.enchantment : "—")
            + " Lv" + String(params.level != null ? params.level : "—") })
        ])
      ];
      const idChip = h("div", { class: "func-item-meta" }, chips);

      const preview = window.buildTooltipPreview();
      function refreshPreview() {
        const nm = entry["display-name"];
        preview.update({
          name: (nm != null && nm !== "") ? nm : label,
          nameMode: "minimessage",
          loreLines: Array.isArray(entry.lore) ? entry.lore : [],
          loreMode: "minimessage"
        });
      }

      const inputChildren = [];
      if (ritual) {
        inputChildren.push(fieldRow("name",
          window.textInput(ritual.name != null ? String(ritual.name) : "", (v) => {
            setOrDelete(ritual, "name", v);
          }), "儀式の表示名 (recipe.name・儀式ブラウザGUIに出る名前)"));
      }
      inputChildren.push(
        fieldRow("display-name", window.richTextInput(entry["display-name"], "minimessage", (v) => {
          setOrDelete(entry, "display-name", v);
          refreshPreview();
        })),
        fieldRow("enchant-glow", (() => {
          const row = h("label", { class: "form-field inline-check" });
          row.appendChild(window.checkboxInput(!!entry["enchant-glow"], (v) => {
            if (v) entry["enchant-glow"] = true; else delete entry["enchant-glow"];
          }));
          row.appendChild(h("span", { class: "form-label",
            text: "enchant aura (格納エンチャントで既に光るため通常は不要)" }));
          return row;
        })()),
        h("div", { class: "sub-title", text: "フレーバー説明文 (lore)" }),
        window.renderLoreRows(entry.lore, "minimessage", refreshPreview, () => render())
      );

      const inputs = h("div", { class: "entry-inputs" }, [idChip].concat(inputChildren));
      const previewCol = h("div", { class: "entry-preview" }, [
        h("div", { class: "preview-label", text: "表示プレビュー" }),
        preview.element,
        h("div", { class: "preview-note", text: "格納エンチャントの行(バニラ描画)はここには表示されません。" })
      ]);

      refreshPreview();

      const recipeSection = window.renderCatalogRecipeSection(entry, () => render(), working.items, id, { allowMirror: false });

      const card = window.collapsibleCard(head, [h("div", { class: "entry-2col" }, [inputs, previewCol]), recipeSection], {
        expanded: expandedCards.has(id),
        onToggle: (open) => { if (open) expandedCards.add(id); else expandedCards.delete(id); }
      });
      card.classList.add("recipe-card");
      return card;
    }

    render();
    return { element: root };
  }

  // TrinityForge 特殊アイテム2件 (catalog.yml) 専用のカード群。Ars の7件のカード表示とほぼ同じ
  // 見た目にするが、material 編集欄は出さない(このユーザー方針では言及されていないため固定のまま)。
  function buildTfSpecialItemsSection(catalogWorking) {
    const root = h("div", { class: "func-tf-special-section" });
    root.appendChild(h("div", { class: "sub-title", text: "TrinityForge 特殊アイテム (catalog.yml)" }));
    root.appendChild(h("div", { class: "form-hint", text:
      "内部ID は com.trinityforge.skilltree.runtime.SkillTreeItems が直接参照する固定値のため変更できません。"
      + " 表示名・CMD・エンチャント光・lore・レシピは編集できます。定義はこれまでどおり catalog.yml に残ります。"
    }));
    const listBox = h("div", { class: "card-list" });
    root.appendChild(listBox);
    const expandedCards = new Set();

    function render() {
      listBox.innerHTML = "";
      if (typeof window.setCustomItemCandidates === "function") {
        window.setCustomItemCandidates(CORE_LOGIC.TF_SPECIAL_ITEM_IDS.map((tid) => ({
          id: tid,
          label: window.stripDisplayNamePlain(catalogWorking.items[tid]["display-name"])
            || CORE_LOGIC.TF_SPECIAL_ITEM_LABELS[tid] || tid
        })), { replace: false });
      }
      for (const id of CORE_LOGIC.TF_SPECIAL_ITEM_IDS) listBox.appendChild(renderCard(id));
    }

    function renderCard(id) {
      const entry = catalogWorking.items[id];
      if (!Array.isArray(entry.lore)) entry.lore = [];

      const plainDisplay = window.stripDisplayNamePlain(entry["display-name"]) || CORE_LOGIC.TF_SPECIAL_ITEM_LABELS[id] || id;
      const head = [
        h("div", { class: "entry-collapse-summary" }, [
          h("span", { class: "entry-sum-name", text: plainDisplay }),
          h("span", { class: "entry-sum-id", text: id })
        ])
      ];

      const idChip = h("div", { class: "func-item-meta" }, [
        h("div", {
          class: "func-item-chip is-readonly",
          title: "ID変更禁止: com.trinityforge.skilltree.runtime.SkillTreeItems がこのIDをハードコード参照しているため変更できません"
        }, [
          h("span", { class: "mini-label", text: "item id" }),
          h("span", { class: "func-item-chip-value", text: id })
        ])
      ]);

      const preview = window.buildTooltipPreview();
      function refreshPreview() {
        const nm = entry["display-name"];
        preview.update({
          name: (nm != null && nm !== "") ? nm : (CORE_LOGIC.TF_SPECIAL_ITEM_LABELS[id] || id),
          nameMode: "minimessage",
          loreLines: Array.isArray(entry.lore) ? entry.lore : [],
          loreMode: "minimessage"
        });
      }

      const inputChildren = [
        fieldRow("display-name", window.richTextInput(entry["display-name"], "minimessage", (v) => {
          setOrDelete(entry, "display-name", v);
          refreshPreview();
        })),
        fieldRow("custom-model-data", window.numberInput(entry["custom-model-data"], (v) => {
          setOrDelete(entry, "custom-model-data", v);
        }, { int: true })),
        fieldRow("enchant-glow", (() => {
          const row = h("label", { class: "form-field inline-check" });
          row.appendChild(window.checkboxInput(!!entry["enchant-glow"], (v) => {
            if (v) entry["enchant-glow"] = true; else delete entry["enchant-glow"];
          }));
          row.appendChild(h("span", { class: "form-label", text: "enchant aura (オンでエンチャント光)" }));
          return row;
        })()),
        h("div", { class: "sub-title", text: "フレーバー説明文 (lore)" }),
        window.renderLoreRows(entry.lore, "minimessage", refreshPreview, () => render())
      ];

      const inputs = h("div", { class: "entry-inputs" }, [idChip].concat(inputChildren));
      const previewCol = h("div", { class: "entry-preview" }, [
        h("div", { class: "preview-label", text: "表示プレビュー" }),
        preview.element,
        h("div", { class: "preview-note", text: "品質ティア行・自動ステ行はここには表示されません。lore はその前に差し込まれるフレーバー説明文です。" })
      ]);

      refreshPreview();

      // レシピ編集UIはカタログ画面と完全共通化する。itemsMap は catalog.yml の items をそのまま渡す
      // (このアイテムのレシピ結果は自身=custom:<id> であり catalog 名前空間で解決されるため)。
      const recipeSection = window.renderCatalogRecipeSection(entry, () => render(), catalogWorking.items, id, { allowMirror: true });

      const card = window.collapsibleCard(head, [h("div", { class: "entry-2col" }, [inputs, previewCol]), recipeSection], {
        expanded: expandedCards.has(id),
        onToggle: (open) => { if (open) expandedCards.add(id); else expandedCards.delete(id); }
      });
      card.classList.add("recipe-card");
      return card;
    }

    render();
    return { element: root };
  }
})(typeof window !== "undefined" ? window : (typeof module !== "undefined" ? module.exports : this), typeof window !== "undefined" && typeof document !== "undefined");
