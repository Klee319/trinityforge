"use strict";

(function () {
  const h = window.h;
  const state = {
    configs: [],
    current: null,
    editor: null,
    kind: null,
    settings: null,
    revisions: {},
    baseSnapshots: {},
    constantsRevision: null,
    constantsBase: null
  };

  // レジストリ由来でない専用ビュー。サイドバーのグループごとに分けて表示する。
  // カタログ/ステータスは統合ハブではなくカテゴリ別の分割ビュー。
  const NAV_SECTIONS = [
    {
      title: "はじめに",
      views: [
        { id: "__home__", label: "ホーム", kind: "home", badge: "案内" },
        { id: "__manual__", label: "使い方マニュアル", kind: "manual", badge: "案内" }
      ]
    },
    {
      title: "アイテムカタログ",
      views: [
        { id: "__cat_weapon__", label: "武器", kind: "split", badge: "tool",
          split: { type: "catalog", configId: "catalog", categoryKey: "weapon", itemCategory: "weapon" } },
        { id: "__cat_armor__", label: "防具", kind: "split", badge: "tool",
          split: { type: "catalog", configId: "catalog", categoryKey: "armor", itemCategory: "armor" } },
        { id: "__cat_tool__", label: "ツール", kind: "split", badge: "tool",
          split: { type: "catalog", configId: "catalog", categoryKey: "tool", itemCategory: "tool" } },
        { id: "__cat_other__", label: "補助", kind: "split", badge: "tool",
          split: { type: "catalog", configId: "catalog", categoryKey: "other", itemCategory: "other" } },
        { id: "__cat_material__", label: "素材", kind: "split", badge: "tool",
          split: { type: "materials", configId: "materials", categoryKey: "material" } },
        { id: "__cat_catalyst__", label: "触媒", kind: "split", badge: "tool",
          split: { type: "catalog", configId: "catalog", categoryKey: "catalyst", itemCategory: "catalyst" } },
        { id: "__cat_spellbook__", label: "魔導書", kind: "split", badge: "tool",
          split: { type: "catalog", configId: "catalog", categoryKey: "spellbook", itemCategory: "spellbook" } },
        { id: "__cat_thread__", label: "スレッド", kind: "split", badge: "tool",
          split: { type: "catalog", configId: "catalog", categoryKey: "thread", itemCategory: "thread" } }
      ]
    },
    {
      title: "アイテムステータス",
      views: [
        { id: "__stats_weapon__", label: "武器", kind: "split", badge: "tool",
          split: { type: "item-stats", configId: "item-stats", categoryKey: "weapon", itemCategory: "weapon" } },
        { id: "__stats_armor__", label: "防具", kind: "split", badge: "tool",
          split: { type: "item-stats", configId: "item-stats", categoryKey: "armor", itemCategory: "armor" } },
        { id: "__stats_tool__", label: "ツール", kind: "split", badge: "tool",
          split: { type: "item-stats", configId: "item-stats", categoryKey: "tool", itemCategory: "tool" } },
        { id: "__stats_other__", label: "補助", kind: "split", badge: "tool",
          split: { type: "item-stats", configId: "item-stats", categoryKey: "other", itemCategory: "other" } },
        { id: "__stats_catalyst__", label: "触媒", kind: "split", badge: "tool",
          split: { type: "item-stats", configId: "item-stats", categoryKey: "catalyst", itemCategory: "catalyst" } },
        { id: "__stats_spellbook__", label: "魔導書", kind: "split", badge: "tool",
          split: { type: "item-stats", configId: "item-stats", categoryKey: "spellbook", itemCategory: "spellbook" } },
        { id: "__stats_thread__", label: "スレッド", kind: "split", badge: "tool",
          split: { type: "item-stats", configId: "item-stats", categoryKey: "thread", itemCategory: "thread" } }
      ]
    },
    // 2026-07-27新設: 「アイテムステータス」の直後に「機能アイテム」カテゴリを配置(ユーザー指示)。
    // TF と Ars はユーザー目線で統合されているべきという方針から、Ars の特殊アイテム/ソースリンク/
    // ソースジャーを「魔法」カテゴリから切り出してここへ集約する。この3件は registry 由来の config
    // (CONFIG_SECTIONS 側の section: "functional-items")なので、views ではなく configSectionKey で
    // renderSidebar 側に「ここへ registry config を差し込め」と伝える(専用ビューは持たない)。
    {
      title: "機能アイテム",
      configSectionKey: "functional-items"
    },
    {
      title: "戦闘ツール",
      views: [
        { id: "__constants__", label: "共通変数（戦闘定数）", kind: "constants", badge: "tool" },
        { id: "__simulator__", label: "火力シミュレータ", kind: "simulator", badge: "tool" }
      ]
    },
    {
      title: "リソースパック",
      views: [
        { id: "__respack__", label: "リソースパック管理", kind: "respack", badge: "tool" }
      ]
    }
  ];
  // 分割ビューへ移した config は個別一覧に出さない（二重導線を避ける）。
  // spellbooks / threads / thread-sets はカタログ・item-stats の触媒/魔導書/スレッドへ統合済み。
  const SPLIT_HIDDEN_IDS = ["catalog", "materials", "spellbooks", "threads", "item-stats", "thread-sets", "material-lists"];
  // グリフ(glyphs)画面のコンパニオン。サイドバー非表示で「グリフ」画面内から一緒に編集・保存する。
  const GLYPH_COMPANION_IDS = ["glyph-damage-boost"];
  // T7 (2026-07-26): ポーション品質換算(alchemy-quality)は「醸造ギミック」(brew-gimmick)画面、
  // エンチャント運(enchant-luck)は「エンチャントギミック」(enchant-gimmick)画面の専用タブへそれぞれ
  // 表示移設(T5 では一旦「その他ギミック」へ集約していたが、エンチャント/醸造関連の切り出し先を
  // 各専用タブへ揃えるためユーザー指示で分離)。サイドバー個別一覧からは引き続き隠し、それぞれの画面内
  // から一緒に編集・保存する(保存先の config id・YAMLキーパスは不変)。
  const BREW_GIMMICK_COMPANION_IDS = ["alchemy-quality"];
  const ENCHANT_GIMMICK_COMPANION_IDS = ["enchant-luck"];
  // T6 (2026-07-26): 「食事ギミック」単独タブを廃止し、農業ギミック(farming-gimmick)画面内へ統合表示する。
  // ファイル自体(stats/food-gimmick.yml)は不変。保存は farming-gimmick 画面の getExtraSaves 経由。
  const FARMING_GIMMICK_COMPANION_IDS = ["food-gimmick"];
  // T8 (2026-07-26): 総合ステータス上限(combat/stat-caps.yml)は「プレイヤー基礎ステータス」画面の
  // 「上限」タブへコンパニオン表示する。加えて「採集効率の上限 (gathering-efficiency)」(旧称:最終効率)は設定1個
  // (max-enchant-level)のためだけの独立カテゴリだったのを畳み、同じ「上限」タブへ統合したため、
  // こちらもサイドバー単独表示をやめる(ファイル自体・保存先キーパスは不変。後方互換用に残す)。
  const BASE_STATS_COMPANION_IDS = ["stat-caps", "gathering-efficiency"];
  // T9 (2026-07-27): AFK(離席)判定(afk.yml)は独立タブを作らず「使用制限スイッチ」(use-requirements)
  // 画面内へ AFK セクションとしてコンパニオン表示する(ユーザー指示)。ファイル自体(afk.yml)は不変。
  // 保存は use-requirements 画面の getExtraSaves 経由(tf-crafting-features.js buildUseRequirementsForm)。
  const USE_REQUIREMENTS_COMPANION_IDS = ["afk"];
  /**
   * コンパニオン config id → buildEditorForLoadedConfig(schema, data, opts) の opts キー。
   *
   * 2026-07-26 修正: `applyMergedToEditor` はコンパニオン側がマージされたとき、tf-quality と
   * tf-skill-exp の2スキーマにしか再構築の分岐が無かった。それ以外のコンパニオン
   * (enchant-luck / alchemy-quality / stat-caps / crafting-features / food-gimmick /
   * ars-config / glyph-damage-boost) では関数が何もせず戻り、**マージ結果が画面に反映されないまま
   * リビジョンだけ進む**ため、次の保存でマージ前の内容がそのまま書き戻る(=マージが無かったことになる)。
   * ここを一覧にして汎用分岐から引けるようにした。新しいコンパニオンを足したらここにも足すこと。
   *
   * gathering-efficiency は「隠してあるだけで編集経路が無い」(max-enchant-level は stat-caps.yml 側の
   * gathering-efficiency-max-enchant-level へ移設済み)ため、意図的に載せていない。
   */
  const COMPANION_OPTION_KEYS = {
    "glyph-damage-boost": "glyphDamageBoostData",
    "crafting-features": "craftingFeaturesData",
    "food-gimmick": "foodGimmickData",
    "enchant-luck": "enchantLuckData",
    "alchemy-quality": "alchemyQualityData",
    "stat-caps": "statCapsData",
    "ars-config": "arsConfigData",
    "afk": "afkData",
    // 2026-07-28: 「怪しいブロックの再生成」を掘削ギミック画面へ移設したため、
    // mining-gimmick.yml が掘削画面のコンパニオンになった。
    "mining-gimmick": "miningGimmickData",
    // 2026-07-27: 「特殊アイテム」(functional-items)画面が TF の skill_node_lock/skill_tree_reset
    // (catalog.yml側の該当2件のみ)をコンパニオンとして統合表示するため。
    "catalog": "catalogData"
  };
  // 品質定義タブへ統合表示するためサイドバー個別一覧から隠す config。
  //   craft-quality(mode/drop) は quality ビュー内に統合し、保存時に一緒に PUT する。
  //   progression-* は skill-exp ビューの曲線コンパニオン (skills/base/*_progression.yml)。
  const QUALITY_COMPANION_ID = "craft-quality";
  const PROGRESSION_SKILL_IDS = [
    "alchemy", "archery", "digging", "enchanting", "farming", "fishing",
    "heavy_armor", "heavy_weapons", "light_armor", "light_weapons",
    "mining", "power", "smithing", "woodcutting", "ars_magic", "ars_smithing"
  ];
  const PROGRESSION_CONFIG_IDS = PROGRESSION_SKILL_IDS.map((s) => `progression-${s}`);
  const HIDDEN_CONFIG_IDS = [
    QUALITY_COMPANION_ID, ...PROGRESSION_CONFIG_IDS, ...SPLIT_HIDDEN_IDS, ...GLYPH_COMPANION_IDS,
    ...BREW_GIMMICK_COMPANION_IDS, ...ENCHANT_GIMMICK_COMPANION_IDS, ...FARMING_GIMMICK_COMPANION_IDS,
    ...BASE_STATS_COMPANION_IDS, ...USE_REQUIREMENTS_COMPANION_IDS
  ];
  // configSectionKey のみを持つ NAV_SECTIONS エントリ(views 無し)を reduce に混ぜても安全にする。
  const ALL_TOOL_VIEWS = NAV_SECTIONS.reduce((acc, s) => acc.concat(s.views || []), []);
  // config をドメイン単位でまとめるセクション定義。
  // level:"main" → 青色メイン見出し / level:"sub" → 灰色小文字サブ見出し。
  // children がある main は見出しのみで、実 config は子サブへ振る。
  const CONFIG_SECTIONS = [
    {
      // 2026-07 仕様変更: 「品質」→「ステータス定義」へリネームし、使用制限スイッチ
      // (use-requirements) をこのメインカテゴリへ移動（旧「装備・制限」カテゴリは削除）。
      key: "quality", title: "ステータス定義", level: "main",
      order: ["quality", "quality-tiers", "lore", "player-base-stats", "use-requirements"]
    },
    {
      // 2026-07-27: functional-items / sourcelinks / sourcejars は新設の「機能アイテム」カテゴリ
      // (NAV_SECTIONS の configSectionKey: "functional-items" 経由)へ移動したため order から外す。
      key: "recipes-magic", title: "魔法", level: "main",
      order: ["items", "glyphs", "ars-config", "ban"]
    },
    {
      key: "skilltree", title: "スキルツリー", level: "main",
      order: [
            "skilltree-light-weapons", "skilltree-heavy-weapons", "skilltree-archery",
            "skilltree-light-armor", "skilltree-heavy-armor",
            "skilltree-ars-magic", "skilltree-ars-smithing",
            "skilltree-smithing", "skilltree-mining", "skilltree-digging",
            "skilltree-woodcutting", "skilltree-farming", "skilltree-fishing",
            "skilltree-alchemy", "skilltree-enchanting"
      ]
    },
    {
      key: "skill-gimmicks", title: "スキルギミック", level: "main",
      // T6 (2026-07-26): food-gimmick は farming-gimmick タブへ統合表示のため単独タブ廃止
      // (HIDDEN_CONFIG_IDS 経由でサイドバーから隠すので、この order にも含めない)。
      // enchant-gimmick / brew-gimmick は「その他ギミック」(crafting-features) から切り出した新タブ。
      order: [
            "skill-exp", "skills-ars-magic", "skills-ars-smithing",
            "gacha",
            "mining-gimmick", "smithing-gimmick", "woodcutting-gimmick", "digging-gimmick", "farming-gimmick",
            "fishing-gimmick",
            "enchant-gimmick", "brew-gimmick",
            "crafting-features", "villager-trades"
      ]
    },
    {
      key: "other", title: "その他", level: "main",
      order: ["role-buffs", "special-rewards", "achievements", "collection"]
    },
    {
      key: "mobs-dungeon", title: "モブダンジョン", level: "main",
      order: [
        "mob-types", "mob-profiles", "mob-import", "hate-rates",
        "dungeon-gates", "dungeon-themes"
      ]
    }
  ];
  const FALLBACK_SECTION_KEY = "skill-gimmicks";
  // 2026-07-27新設: NAV_SECTIONS 側の「機能アイテム」グループ(configSectionKey: "functional-items")
  // が差し込む registry config セクション。CONFIG_SECTIONS 本体には含めない(あちらは「専用ビューが
  // 先に描画された後、config を並べる」ループ向けで、この3件は専用ビュー群の中に割り込ませたいため)。
  // level: "flat" は renderConfigSection に「見出しは呼び出し元(NAV_SECTIONS側)が既に出した」と伝え、
  // 自前の見出し描画をスキップさせる印(cfg:main / sub のどちらでもない第三の扱い)。
  const FUNCTIONAL_ITEMS_NAV_SECTION = {
    key: "functional-items", title: "機能アイテム", level: "flat",
    order: ["functional-items", "sourcelinks", "sourcejars"]
  };

  async function api(method, url, body) {
    const opts = { method, headers: { "Content-Type": "application/json" } };
    if (body !== undefined) opts.body = JSON.stringify(body);
    const res = await fetch(url, opts);
    const json = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw Object.assign(new Error(json.error || res.statusText), {
        details: json.details,
        status: res.status,
        payload: json
      });
    }
    return json;
  }

  function rememberRevision(configId, revision) {
    if (configId == null) return;
    if (revision === undefined) return;
    state.revisions[configId] = revision;
  }

  function rememberBase(configId, data) {
    if (configId == null) return;
    const clone = typeof window.cloneData === "function" ? window.cloneData(data) : JSON.parse(JSON.stringify(data == null ? {} : data));
    state.baseSnapshots[configId] = clone == null ? {} : clone;
  }

  /**
   * catalog.yml + materials.yml (ArsPaper 中間素材) を合わせてカタログ参照候補を作る。
   * 2026-07-25: 素材タブが catalogItemSuggest 系の候補に出てこない不具合の修正。
   * catalog.yml は他画面と同じ流儀で rememberRevision/rememberBase する (差分検知対象)。
   * materials.yml は完全に読み取り専用の参照データとして扱うため、あえて
   * rememberRevision/rememberBase を呼ばない (どの画面の getExtraSaves にも materials は
   * 含まれないので保存対象に混ざる経路が無い。state.baseSnapshots/state.revisions を
   * 触らないことで、実際に「素材」タブを開いて編集中の場合の差分検知・楽観ロックに一切
   * 影響を与えない)。
   * どちらかの取得に失敗しても、取れた方だけで候補を返す。
   */
  async function fetchCatalogCandidatesWithMaterials() {
    let catalogData = null;
    let materialsData = null;
    try {
      const cr = await api("GET", "/api/config/catalog");
      if (cr && cr.data) {
        rememberRevision("catalog", cr.revision);
        rememberBase("catalog", cr.data);
        catalogData = cr.data;
      }
    } catch (_) { /* optional */ }
    try {
      const mr = await api("GET", "/api/config/materials");
      if (mr && mr.data) materialsData = mr.data;
    } catch (_) { /* optional */ }
    const candidates = typeof window.buildCatalogCandidates === "function"
      ? window.buildCatalogCandidates(catalogData || {}, materialsData || {})
      : [];
    // 取得したついでに custom: 候補としても共有登録しておく。materialInput は
    // window.CUSTOM_ITEM_CANDIDATES をフォールバック候補源にしているので、
    // ここで積んでおけば同一セッション内の他画面でもカスタムアイテムが選べる。
    if (candidates.length && typeof window.setCustomItemCandidates === "function") {
      window.setCustomItemCandidates(candidates, { replace: false });
    }
    return candidates;
  }

  /**
   * 2026-07-28: 「醸造などカスタムアイテムも適用できるべき箇所でカスタムアイテムが
   * セレクトメニューにない」への対処。materialInput({allowCustom:true}) はカスタム候補を
   * グローバル window.CUSTOM_ITEM_CANDIDATES から引くが、これを積むのは
   * fetchCatalogCandidatesWithMaterials を明示的に呼ぶ一部の画面だけだった。そのため
   * 醸造ギミック(醸造解放の材料)やエンチャントギミック等を直接開くと候補が空のままで、
   * 「custom:<ID> を手入力しないと設定できない」状態になっていた。
   * 画面種別ごとに呼び出しを足すと必ず漏れるので、エディタ構築の共通入口で一度だけ読む。
   * 取得失敗・カタログ空でもバニラ素材だけで編集は続行できるので、常に握り潰す。
   */
  let customItemCandidatePromise = null;
  function ensureCustomItemCandidates() {
    if (!customItemCandidatePromise) {
      customItemCandidatePromise = fetchCatalogCandidatesWithMaterials().catch(() => []);
    }
    return customItemCandidatePromise;
  }

  /**
   * T6 (2026-07-26): 他画面のコンパニオンとして id の config を1つ読み込む共通ヘルパー。
   * options[optKey] が既に渡されていればそれを使い、無ければ state.baseSnapshots のキャッシュ、
   * それも無ければ GET してキャッシュする(元は tf-crafting-features の source-auto-consume /
   * alchemy-quality / enchant-luck 読み込みに個別実装されていたものを共通化)。
   * 鍛冶ギミック/伐採ギミックの disassembly / wood-repair コンパニオン(crafting-features)、
   * エンチャント/醸造ギミック自身の読み込み、農業ギミックの food-gimmick コンパニオンで使う。
   */
  async function loadConfigCompanion(id, optKey, options) {
    const opts = options || {};
    if (opts[optKey] !== undefined) return opts[optKey];
    if (state.baseSnapshots[id]) return window.cloneData(state.baseSnapshots[id]);
    try {
      const r = await api("GET", `/api/config/${id}`);
      rememberRevision(id, r.revision);
      rememberBase(id, r.data);
      return r.data && typeof r.data === "object" ? r.data : {};
    } catch (_) { return undefined; }
  }

  /**
   * フォーム構築時の構造正規化（空ブロック補完・旧キー除去・_editor 補完など）を
   * 「未保存変更」に数えないよう、エディタ開いた直後の getData でベースラインを合わせる。
   */
  function syncBaseFromEditor(primaryConfigId) {
    const ed = state.editor;
    if (!ed || typeof ed.getData !== "function") return;
    if (state.kind === "constants") {
      const constants = ed.getData();
      state.constantsBase = typeof window.cloneData === "function"
        ? window.cloneData(constants)
        : JSON.parse(JSON.stringify(constants == null ? {} : constants));
      return;
    }
    const configId = primaryConfigId != null
      ? primaryConfigId
      : (ed.configId || state.current);
    if (configId) rememberBase(configId, ed.getData());
    if (typeof ed.getExtraSaves === "function") {
      for (const extra of ed.getExtraSaves()) {
        if (extra && extra.id != null) rememberBase(extra.id, extra.data);
      }
    }
    if (typeof ed.getCraftData === "function") {
      rememberBase(QUALITY_COMPANION_ID, ed.getCraftData());
    }
    if (typeof ed.getProgressionData === "function") {
      const curves = ed.getProgressionData() || {};
      for (const skillId of Object.keys(curves)) {
        rememberBase(`progression-${skillId}`, curves[skillId]);
      }
    }
  }

  /** 保存衝突時の選択。強制上書きは相手の変更を失うためUIから提供しない。 */
  function conflictChoiceModal() {
    return new Promise((resolve) => {
      const overlay = h("div", { class: "modal-overlay" });
      const cleanup = (choice) => {
        if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        resolve(choice);
      };
      const box = h("div", { class: "modal-box modal-box-wide" }, [
        h("div", { class: "modal-title", text: "他の編集者が先に保存しました" }),
        h("div", { class: "modal-text", text:
          "読み込み時点を基準に、自分の変更と相手の最新を突き合わせて統合できます。"
          + "リストへの追加は双方とも残します。同じ項目を双方が別内容に変えている場合は自分側を優先します。"
        }),
        h("div", { class: "modal-actions modal-actions-stack" }, [
          h("button", {
            class: "btn primary", type: "button", text: "マージして確認",
            onclick: () => cleanup("merge")
          }),
          h("button", {
            class: "btn ghost", type: "button", text: "破棄して再読込（自分の未保存を捨てる）",
            onclick: () => cleanup("discard")
          }),
          h("button", {
            class: "btn ghost", type: "button", text: "キャンセル",
            onclick: () => cleanup("cancel")
          })
        ])
      ]);
      overlay.appendChild(box);
      overlay.addEventListener("click", (e) => {
        // モーダル外クリックで未保存内容を破棄しない。
        if (e.target === overlay) cleanup("cancel");
      });
      document.body.appendChild(overlay);
    });
  }

  /** 未保存のまま離れる時、保存を第一選択に提示する3択モーダル。戻り値 "save"|"discard"|"cancel"。 */
  function saveBeforeLeaveModal() {
    return new Promise((resolve) => {
      const overlay = h("div", { class: "modal-overlay" });
      const cleanup = (choice) => {
        if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        resolve(choice);
      };
      const box = h("div", { class: "modal-box modal-box-wide" }, [
        h("div", { class: "modal-title", text: "未保存の変更があります" }),
        h("div", { class: "modal-text", text: "この画面を離れる前に保存しますか？" }),
        h("div", { class: "modal-actions modal-actions-stack" }, [
          h("button", {
            class: "btn primary", type: "button", text: "保存する",
            onclick: () => cleanup("save")
          }),
          h("button", {
            class: "btn ghost", type: "button", text: "保存せずに移動",
            onclick: () => cleanup("discard")
          })
        ])
      ]);
      overlay.appendChild(box);
      overlay.addEventListener("click", (e) => {
        // モーダル外クリック / Escape は安全側(移動しない=cancel)。
        if (e.target === overlay) cleanup("cancel");
      });
      document.body.appendChild(overlay);
    });
  }

  function toastMergeResult(conflicts) {
    const n = (conflicts && conflicts.length) || 0;
    let msg = "マージしました。内容を確認して再保存してください。";
    if (n > 0) {
      const sample = conflicts.slice(0, 8).join(", ");
      msg = `マージしました（衝突 ${n} 件は自分側を優先: ${sample}${n > 8 ? "…" : ""}）。内容を確認して再保存してください。`;
    }
    toast(msg, "warn");
  }

  /**
   * マージ結果を現在のエディタへ反映する（サーバ再取得はしない）。
   */
  async function applyMergedToEditor(configId, mergedData) {
    const main = document.getElementById("editor-area");
    if (!main) return;

    if (state.kind === "constants" && configId === "__constants__") {
      state.editor = window.buildConstantsView(mergedData);
      main.innerHTML = "";
      main.appendChild(state.editor.element);
      return;
    }

    if (state.kind === "split") {
      const view = ALL_TOOL_VIEWS.find((v) => v.id === state.current);
      if (!view || !view.split) return;
      const sp = view.split;
      // 2026-07-29: ここで main を空にしてから下の else で return する経路があり、
      // 「今開いている画面(catalog/item-stats)とは別の config がマージされた」ときに
      // アイテム一覧が丸ごと消えて白紙になっていた(state.editor は生きているので実害なし=
      // ユーザー報告どおり)。クリアは差し替え直前だけ行う。
      if (sp.type === "thread-bundle") {
        const threadsData = configId === "threads" ? mergedData
          : (typeof state.editor.getData === "function" ? state.editor.getData() : (state.baseSnapshots.threads || {}));
        let setsData = state.baseSnapshots["thread-sets"] || {};
        if (configId === "thread-sets") setsData = mergedData;
        else if (typeof state.editor.getExtraSaves === "function") {
          const extras = state.editor.getExtraSaves();
          const found = extras.find((e) => e.id === "thread-sets");
          if (found) setsData = found.data;
        }
        state.editor = window.buildSplitConfigView({
          type: "thread-bundle",
          configId: "threads",
          categoryKey: sp.categoryKey || "thread",
          threadsData,
          threadSetsData: setsData
        });
      } else if (sp.configId === configId) {
        let catalogCandidates = [];
        if (sp.type === "item-stats") {
          catalogCandidates = await fetchCatalogCandidatesWithMaterials();
        }
        // catalog⇄materials 跨ぎ移動の相手ファイル: ステージ済みの移動 (dirty extra) があれば
        // 引き継ぎ、無ければ再取得する。
        let counterpartId = null;
        let counterpartData = null;
        let counterpartDirty = false;
        if (sp.type === "catalog" || sp.type === "materials") {
          counterpartId = sp.type === "catalog" ? "materials" : "catalog";
          if (typeof state.editor.getExtraSaves === "function") {
            const found = state.editor.getExtraSaves().find((e) => e.id === counterpartId);
            if (found) {
              counterpartData = found.data;
              counterpartDirty = true;
            }
          }
          if (!counterpartData) {
            try {
              const xr = await api("GET", `/api/config/${counterpartId}`);
              rememberRevision(counterpartId, xr.revision);
              counterpartData = (xr.data && typeof xr.data === "object") ? xr.data : {};
              rememberBase(counterpartId, counterpartData);
            } catch (_) {
              counterpartId = null;
              counterpartData = null;
            }
          }
        }
        state.editor = window.buildSplitConfigView({
          type: sp.type,
          configId: sp.configId,
          categoryKey: sp.categoryKey || "default",
          itemCategory: sp.itemCategory,
          data: mergedData,
          catalogCandidates,
          counterpartId,
          counterpartData,
          counterpartDirty
        });
      } else {
        // 表示中の config ではないものがマージされた: 画面は触らない(消さない)。
        return;
      }
      main.innerHTML = "";
      main.appendChild(state.editor.element);
      document.getElementById("save-btn").disabled = false;
      // マージ後は「ディスク未保存」として残すため syncBase しない
      return;
    }

    if (state.kind === "config") {
      const meta = state.configs.find((c) => c.id === state.current) || {};
      // companion のみマージされた場合はメイン+companion を組み立て直す
      if (state.current === configId) {
        const extras = {};
        if (meta.schema === "tf-quality" && typeof state.editor.getCraftData === "function") {
          extras.craftData = state.editor.getCraftData();
        }
        if (meta.schema === "tf-skill-exp" && typeof state.editor.getProgressionData === "function") {
          extras.progression = state.editor.getProgressionData() || {};
        }
        const editor = await buildEditorForLoadedConfig(meta.schema || "", mergedData, extras);
        state.editor = editor;
        main.innerHTML = "";
        main.appendChild(editor.element);
        document.getElementById("save-btn").disabled = false;
        return;
      }
      if (configId === QUALITY_COMPANION_ID && meta.schema === "tf-quality") {
        const qualityData = typeof state.editor.getData === "function" ? state.editor.getData() : (state.baseSnapshots[state.current] || {});
        await loadQualityTierMax();
        state.editor = window.buildQualityForm(qualityData, mergedData);
        main.innerHTML = "";
        main.appendChild(state.editor.element);
        document.getElementById("save-btn").disabled = false;
        return;
      }
      if (String(configId).startsWith("progression-") && meta.schema === "tf-skill-exp") {
        const skillExpData = typeof state.editor.getData === "function" ? state.editor.getData() : (state.baseSnapshots[state.current] || {});
        const progression = {};
        if (typeof state.editor.getProgressionData === "function") {
          Object.assign(progression, state.editor.getProgressionData() || {});
        }
        const skillId = configId.slice("progression-".length);
        progression[skillId] = mergedData;
        state.editor = window.buildSkillExpForm(skillExpData, progression);
        main.innerHTML = "";
        main.appendChild(state.editor.element);
        document.getElementById("save-btn").disabled = false;
        return;
      }
      // 汎用コンパニオン分岐 (2026-07-26 修正)。上の tf-quality / tf-skill-exp 専用分岐に該当しない
      // コンパニオンがマージされた場合、ここでメイン画面ごと組み立て直してマージ結果を反映する。
      // これが無いと関数が黙って戻り、リビジョンだけ進んだ状態で画面はマージ前のまま残る
      // (次の保存でマージ内容が消える)。
      const optKey = COMPANION_OPTION_KEYS[configId];
      if (optKey) {
        const mainData = typeof state.editor.getData === "function"
          ? state.editor.getData()
          : (state.baseSnapshots[state.current] || {});
        // 同じ画面が複数コンパニオンを抱える場合(例: 機能アイテムレシピ)、マージ対象以外の
        // コンパニオンは編集中の値をそのまま引き継ぐ。再取得すると未保存編集を捨ててしまう。
        const extras = {};
        if (typeof state.editor.getExtraSaves === "function") {
          for (const extra of state.editor.getExtraSaves()) {
            const key = extra && extra.id != null ? COMPANION_OPTION_KEYS[extra.id] : null;
            if (key) extras[key] = extra.data;
          }
        }
        extras[optKey] = mergedData;
        const editor = await buildEditorForLoadedConfig(meta.schema || "", mainData, extras);
        state.editor = editor;
        main.innerHTML = "";
        main.appendChild(editor.element);
        document.getElementById("save-btn").disabled = false;
      }
    }
  }

  /** selectConfig と同じスキーマ分岐でエディタを組み立てる（マージ再適用用）。 */
  async function buildEditorForLoadedConfig(schema, data, opts) {
    const options = opts || {};
    // どの画面でも materialInput のカスタム候補が空にならないよう、共通入口で一度だけ読む。
    await ensureCustomItemCandidates();
    switch (schema) {
      case "item-stats": {
        const catalogCandidates = await fetchCatalogCandidatesWithMaterials();
        return window.buildItemStatsForm(data, { catalogCandidates });
      }
      case "catalog": return window.buildCatalogForm(data);
      case "external-items": return window.buildExternalItemsForm(data);
      case "tf-gacha": {
        // 券IDはカタログ品だけが有効なので、候補を渡してセレクトにする。
        const catalogCandidates = await fetchCatalogCandidatesWithMaterials();
        return window.buildGachaForm(data, { catalogCandidates });
      }
      case "ars-thread-sets": return window.buildThreadSetsForm(data);
      case "ars-recipes": return window.buildRecipesForm(data, { onlyEffects: true });
      case "ars-materials": return window.buildMaterialsForm(data);
      case "ars-threads": return window.buildThreadsForm(data);
      case "tf-lore": return window.buildLoreForm(data);
      case "tf-skilltree": return window.buildSkillTreeForm(data);
      case "tf-craft-quality": return window.buildCraftQualityForm(data);
      case "tf-quality": {
        await loadQualityTierMax();
        let craftData;
        if (options.craftData !== undefined) craftData = options.craftData;
        else if (state.baseSnapshots[QUALITY_COMPANION_ID]) craftData = window.cloneData(state.baseSnapshots[QUALITY_COMPANION_ID]);
        else {
          try {
            const cr = await api("GET", `/api/config/${QUALITY_COMPANION_ID}`);
            rememberRevision(QUALITY_COMPANION_ID, cr.revision);
            rememberBase(QUALITY_COMPANION_ID, cr.data);
            craftData = cr.data && typeof cr.data === "object" ? cr.data : {};
          } catch (_) { craftData = undefined; }
        }
        return window.buildQualityForm(data, craftData);
      }
      case "tf-quality-tiers": return window.buildQualityTiersForm(data);
      case "tf-skill-exp": {
        // 2026-07-28: EXP テーブルの素材セレクトにカスタムアイテム(catalog.yml / materials.yml)を
        // 出すための候補は、上の ensureCustomItemCandidates() で共通に読み込み済み。
        const progression = options.progression || {};
        if (!options.progression) {
          for (const skillId of PROGRESSION_SKILL_IDS) {
            const pid = `progression-${skillId}`;
            if (state.baseSnapshots[pid]) {
              progression[skillId] = window.cloneData(state.baseSnapshots[pid]);
              continue;
            }
            try {
              const pr = await api("GET", `/api/config/${pid}`);
              rememberRevision(pid, pr.revision);
              rememberBase(pid, pr.data);
              if (pr && pr.data && typeof pr.data === "object") progression[skillId] = pr.data;
            } catch (_) { /* optional */ }
          }
        }
        return window.buildSkillExpForm(data, progression);
      }
      case "ars-glyphs": {
        let boostData;
        if (options.glyphDamageBoostData !== undefined) boostData = options.glyphDamageBoostData;
        else if (state.baseSnapshots["glyph-damage-boost"]) boostData = window.cloneData(state.baseSnapshots["glyph-damage-boost"]);
        else {
          try {
            const br = await api("GET", "/api/config/glyph-damage-boost");
            rememberRevision("glyph-damage-boost", br.revision);
            rememberBase("glyph-damage-boost", br.data);
            boostData = br.data && typeof br.data === "object" ? br.data : {};
          } catch (_) { boostData = undefined; }
        }
        return window.buildGlyphsForm(data, boostData);
      }
      case "ars-spellbooks": return window.buildSpellbooksForm(data);
      case "tf-mob-types": return window.buildMobTypesForm(data);
      case "tf-mob-level-table": return window.buildMobLevelTableForm(data);
      case "tf-dungeon-gates": {
        const catalogCandidates = await fetchCatalogCandidatesWithMaterials();
        return window.buildDungeonGatesForm(data, { catalogCandidates });
      }
      case "tf-dungeon-themes": return window.buildDungeonThemesForm(data);
      case "tf-mob-import": return window.buildMobImportForm(data);
      case "tf-mob-profiles": return window.buildMobProfilesForm(data);
      case "tf-mob-overrides": {
        // drops の item は Material 名だけでなく custom:<カタログID> も取れるので、
        // カタログ候補を渡してセレクトメニューから選べるようにする (2026-07-26)。
        const catalogCandidates = await fetchCatalogCandidatesWithMaterials();
        return window.buildMobOverridesForm(data, { catalogCandidates });
      }
      case "tf-hate-rates": return window.buildHateRatesForm(data);
      case "tf-combat-display": return window.buildCombatDisplayForm(data);
      case "tf-mining-gimmick": return window.buildMiningGimmickForm(data);
      case "tf-smithing-gimmick": {
        // T6 (2026-07-26): crafting-features.yml の disassembly(解体)サブツリーをコンパニオン表示。
        const craftingFeaturesData = await loadConfigCompanion("crafting-features", "craftingFeaturesData", options);
        return window.buildSmithingGimmickForm(data, { craftingFeaturesData });
      }
      case "tf-woodcutting-gimmick": {
        // T6 (2026-07-26): crafting-features.yml の wood-repair(圧縮木材修繕)サブツリーをコンパニオン表示。
        const craftingFeaturesData = await loadConfigCompanion("crafting-features", "craftingFeaturesData", options);
        const catalogCandidates = await fetchCatalogCandidatesWithMaterials();
        return window.buildWoodcuttingGimmickForm(data, { craftingFeaturesData, catalogCandidates });
      }
      case "tf-digging-gimmick": {
        // 2026-07-28: 「怪しいブロックの再生成」(考古学=ブラシ)を採掘タブからここへ移設。
        // 保存先ファイルは stats/mining-gimmick.yml のままなのでコンパニオンとして読み込む。
        const miningGimmickData = await loadConfigCompanion("mining-gimmick", "miningGimmickData", options);
        return window.buildDiggingGimmickForm(data, { miningGimmickData });
      }
      case "tf-farming-gimmick": {
        // T6 (2026-07-26): 食事ギミック(food-gimmick.yml)をこのタブ内へ統合表示。ファイルは別のまま。
        const foodGimmickData = await loadConfigCompanion("food-gimmick", "foodGimmickData", options);
        const catalogCandidates = await fetchCatalogCandidatesWithMaterials();
        return window.buildFarmingGimmickForm(data, { foodGimmickData, catalogCandidates });
      }
      case "tf-food-gimmick": {
        const catalogCandidates = await fetchCatalogCandidatesWithMaterials();
        return window.buildFoodGimmickForm(data, { catalogCandidates });
      }
      case "tf-fishing-gimmick": return window.buildFishingGimmickForm(data);
      case "tf-enchant-gimmick": {
        // T7 (2026-07-26): エンチャント運(enchant-luck.yml)をこのタブへコンパニオン表示する。
        const enchantLuckData = await loadConfigCompanion("enchant-luck", "enchantLuckData", options);
        return window.buildEnchantGimmickForm(data, { enchantLuckData });
      }
      case "tf-brew-gimmick": {
        // T7 (2026-07-26): ポーション品質換算(alchemy-quality.yml)をこのタブへコンパニオン表示する。
        const alchemyQualityData = await loadConfigCompanion("alchemy-quality", "alchemyQualityData", options);
        return window.buildBrewGimmickForm(data, { alchemyQualityData });
      }
      case "tf-villager-trades": return window.buildVillagerTradesForm(data);
      case "tf-role-buffs": return window.buildRoleBuffsForm(data);
      case "tf-base-stats": {
        // ロア表示のステ一覧(STAT_LIST/STAT_META)を確実に用意してから描画する。
        if (typeof loadStatList === "function"
            && (!window.STAT_LIST || !window.STAT_LIST.length)) {
          try { await loadStatList(); } catch (_) { /* フォールバックで続行 */ }
        }
        // T8 (2026-07-26): 総合ステータス上限(combat/stat-caps.yml)を「上限」タブへコンパニオン表示。
        const statCapsData = await loadConfigCompanion("stat-caps", "statCapsData", options);
        return window.buildBaseStatsForm(data, { statCapsData });
      }
      case "ars-config": return window.buildArsConfigForm(data);
      case "ars-ban": return window.buildBanForm(data);
      case "tf-skills-ars-magic": return window.buildArsMagicSkillForm(data);
      case "tf-skills-ars-smithing": return window.buildArsSmithingSkillForm(data);
      case "tf-crafting-features": {
        // コーティング/木材修繕の素材はカタログID参照 (catalogIdControl)。ArsPaper materials.yml
        // 由来の素材も自然に選べるべきなので、item-stats 等と同じ共通ヘルパーで候補を作る。
        const catalogCandidates = await fetchCatalogCandidatesWithMaterials();
        // T4 (2026-07-25): source-auto-consume.items (ArsPaper config.yml) をこのタブへ移設。
        // ars-config スクリーンとは別画面から編集するコンパニオン (getExtraSaves で保存)。
        let arsConfigData;
        if (options.arsConfigData !== undefined) arsConfigData = options.arsConfigData;
        else if (state.baseSnapshots["ars-config"]) arsConfigData = window.cloneData(state.baseSnapshots["ars-config"]);
        else {
          try {
            const ar = await api("GET", "/api/config/ars-config");
            rememberRevision("ars-config", ar.revision);
            rememberBase("ars-config", ar.data);
            arsConfigData = ar.data && typeof ar.data === "object" ? ar.data : {};
          } catch (_) { arsConfigData = undefined; }
        }
        // T7 (2026-07-26): ポーション品質換算(alchemy-quality)/エンチャント運(enchant-luck)は
        // 醸造ギミック/エンチャントギミックタブへ表示移設したため、このタブではもう読み込まない。
        return window.buildCraftingFeaturesForm(data, { catalogCandidates, arsConfigData });
      }
      case "tf-use-requirements": {
        // T9 (2026-07-27): AFK(離席)判定(afk.yml)をこのタブ内へ統合表示。ファイルは別のまま。
        // farming-gimmick が food-gimmick を読む経路(loadConfigCompanion)と完全に揃える
        // (options 優先 → state.baseSnapshots キャッシュ → GET して rememberRevision/rememberBase)。
        const afkData = await loadConfigCompanion("afk", "afkData", options);
        return window.buildUseRequirementsForm(data, { afkData });
      }
      case "tf-special-rewards": return window.buildSpecialRewardsForm(data);
      case "tf-achievements": {
        const [specialRewards, catalogCandidates, collectionData] = await Promise.all([
          loadSpecialRewards(),
          (async () => {
            try {
              const cr = await api("GET", "/api/config/catalog");
              if (cr && cr.data) {
                rememberRevision("catalog", cr.revision);
                rememberBase("catalog", cr.data);
                return typeof window.buildCatalogCandidates === "function"
                  ? window.buildCatalogCandidates(cr.data) : [];
              }
            } catch (_) { /* optional */ }
            return [];
          })(),
          (async () => { try { const r = await api("GET", "/api/config/collection"); return r && r.data ? r.data : {}; } catch (_) { return {}; } })()
        ]);
        return window.buildAchievementsForm(data, {
          specialRewardIds: specialRewards.ids,
          specialRewardLabels: specialRewards.labels,
          catalogCandidates,
          collectionData
        });
      }
      case "tf-collection": {
        // categories-items は catalogEntryControl (catalogItemSuggest) 経由。
        // ArsPaper materials.yml も収集物として自然に選べるべきなので共通ヘルパーを使う。
        const [specialRewards, catalogCandidates] = await Promise.all([
          loadSpecialRewards(),
          fetchCatalogCandidatesWithMaterials()
        ]);
        return window.buildCollectionForm(data, {
          specialRewardIds: specialRewards.ids,
          specialRewardLabels: specialRewards.labels,
          catalogCandidates
        });
      }
      case "ars-sourcejars": return window.buildSourceJarsForm(data);
      case "ars-sourcelinks": return window.buildSourceLinksForm(data);
      // 2026-07-25: 機能アイテムの表示名/lore/enchant-glow/material/recipe は
      // functional-items.yml 単一ファイルに統合済み。他ファイル(items.yml)への companion読み込みは
      // 不要(custom:候補サジェストは RECIPES_UI.ensureCustomDatalist が担う)。
      // 2026-07-27: 画面名は「特殊アイテム」にリネーム。TF の skill_node_lock/skill_tree_reset
      // (catalog.yml の該当2件のみ)をこの画面へ統合するため catalog.yml をコンパニオンとして読み込む
      // (afk / crafting-features と同じ loadConfigCompanion 経由。保存は getExtraSaves でロスレスに
      // catalog.yml 全体を書き戻す)。
      case "ars-functional-items": {
        const catalogData = await loadConfigCompanion("catalog", "catalogData", options);
        return window.buildFunctionalItemsForm(data, { catalogData });
      }
      default: return window.buildGenericEditor(data);
    }
  }

  /**
   * 楽観的ロック付き PUT。409 時はマージ / 再読込 / キャンセルを選択。
   * @returns {object|null} 保存結果。再読込・キャンセル時 null。マージ時 { merged:true, conflicts, data }。
   */
  async function putConfig(configId, data, opts) {
    const options = opts && typeof opts === "object" ? opts : {};
    const body = { data };
    if (state.revisions[configId] != null) body.expectedRevision = state.revisions[configId];
    try {
      const r = await api("PUT", `/api/config/${configId}`, body);
      rememberRevision(configId, r.revision);
      rememberBase(configId, data);
      return r;
    } catch (err) {
      if (err.status === 409 && err.payload && err.payload.conflict) {
        rememberRevision(configId, err.payload.revision);
        const choice = await conflictChoiceModal();

        if (choice === "discard") {
          toast("相手の最新を読み直しました。未保存の自分の変更は破棄されています。", "warn");
          if (typeof options.onConflictReload === "function") {
            await options.onConflictReload();
          } else if (state.kind === "split") {
            const view = ALL_TOOL_VIEWS.find((v) => v.id === state.current);
            if (view) await selectTool(view);
          } else if (state.current) {
            await selectConfig(state.current);
          }
          return null;
        }

        if (choice !== "merge") return null;

        // merge
        const base = state.baseSnapshots[configId] != null ? state.baseSnapshots[configId] : {};
        const remote = err.payload.data && typeof err.payload.data === "object" ? err.payload.data : {};
        const merged = typeof window.threeWayMerge === "function"
          ? window.threeWayMerge(base, data, remote)
          : { data: data, conflicts: ["(merge unavailable)"] };
        // ベースラインは相手の最新に更新する。merged をベースにすると未保存差分が消え、
        // 次の保存が「変更なし」扱いになってしまう。
        rememberBase(configId, remote);
        if (typeof options.onMerged === "function") {
          await options.onMerged(merged.data, merged.conflicts || []);
        } else {
          await applyMergedToEditor(configId, merged.data);
        }
        toastMergeResult(merged.conflicts);
        return { merged: true, conflicts: merged.conflicts || [], data: merged.data };
      }
      throw err;
    }
  }

  function toast(message, kind) {
    const el = document.getElementById("toast");
    el.textContent = message;
    el.className = `toast show ${kind || ""}`;
    clearTimeout(toast._t);
    // エラーは読み切れるよう自動で消さず、クリックで閉じる。成功/通知は数秒で消える。
    el.onclick = () => { el.className = "toast"; };
    if (kind === "error") return;
    toast._t = setTimeout(() => { el.className = "toast"; }, 4500);
  }
  // cmd-tools.js / respack-view.js など他モジュールからの通知表示用に公開する。
  window.toast = toast;

  // lore.yml のキーから stat候補リストと、各statの表示フォーマット(PERCENT/FLAT/…)を構築する。
  // フォーマットは item-stats フォームで割合系ステを % 入力に切り替えるために使う。
  // STAT_META は order/category/name（セレクトの並び・Lore表示名）。
  async function loadStatList() {
    const set = new Set();
    const formats = {};
    const meta = {};
    for (const id of ["lore"]) {
      try {
        const r = await api("GET", `/api/config/${id}`);
        const stats = r.data && r.data.stats;
        if (stats && typeof stats === "object") {
          for (const [k, spec] of Object.entries(stats)) {
            set.add(k);
            const entry = spec && typeof spec === "object" ? spec : {};
            const fmt = entry.format;
            if (typeof fmt === "string" && fmt) formats[k] = fmt.toUpperCase();
            const cat = entry.category
              || (typeof window.inferLoreCategory === "function" ? window.inferLoreCategory(k) : "other");
            meta[k] = {
              order: Number.isFinite(Number(entry.order)) ? Number(entry.order) : 100,
              category: cat,
              name: typeof entry.name === "string" ? entry.name : "",
              unit: typeof entry.unit === "string" ? entry.unit : ""
            };
          }
        }
        // 乗算レイヤ定義 (lore.yml multiplier-layers)。item-stats の乗算モードUIが参照する。
        const layers = r.data && Array.isArray(r.data["multiplier-layers"]) ? r.data["multiplier-layers"] : [];
        window.MULTIPLIER_LAYERS = layers
          .filter((l) => l && typeof l === "object" && l.id)
          .map((l) => ({
            id: String(l.id),
            name: typeof l.name === "string" && l.name ? l.name : String(l.id),
            // 2026-07: レイヤは基準ステータスごとの定義。stat未指定は旧形式(全ステ許容)扱い。
            stat: typeof l.stat === "string" ? l.stat : ""
          }));
      } catch (_) { /* 未作成なら無視 */ }
    }
    window.STAT_LIST = set.size ? Array.from(set) : window.FALLBACK_STATS.slice();
    window.STAT_FORMATS = Object.assign({}, window.FALLBACK_STAT_FORMATS || {}, formats);
    window.STAT_META = meta;
    // lore order で STAT_LIST 自体も並べておく（フィルタ無しセレクト用）
    window.STAT_LIST.sort((a, b) => {
      const ma = meta[a] || {};
      const mb = meta[b] || {};
      const ca = String(ma.category || "other");
      const cb = String(mb.category || "other");
      if (ca !== cb) return ca.localeCompare(cb);
      const oa = Number(ma.order != null ? ma.order : 1000);
      const ob = Number(mb.order != null ? mb.order : 1000);
      if (oa !== ob) return oa - ob;
      return a.localeCompare(b);
    });
    fillDatalist("stat-list", window.STAT_LIST);
  }

  // quality-tiers.yml のティア数から「実効最大品質」を求める (プラグインと同じ: Nティア -> 品質 0..N-1)。
  // 品質定義プレビューの mode 段階はこの実効値に追従する (ティアを増やすと mode の刻みも増える)。
  // ティアが無ければ null → 数値 max-quality フォールバックに委ねる。品質フォームを開くたびに最新値を取る。
  async function loadQualityTierMax() {
    try {
      const r = await api("GET", "/api/config/quality-tiers");
      const tiers = r.data && r.data.tiers;
      window.TF_QUALITY_TIER_MAX = Array.isArray(tiers) && tiers.length > 0 ? tiers.length - 1 : null;
    } catch (_) {
      window.TF_QUALITY_TIER_MAX = null; // 未作成/取得失敗なら数値フォールバック
    }
  }

  // アチーブメント/図鑑の rewards.special 候補として、special-rewards.yml の
  // titles/particles/particle-seeds キーを和集合で取得する (未作成なら空配列)。
  //
  // 2026-07-29: ID だけを返していたため、報酬セレクトが new_title / new_particle のような
  // 生IDの羅列になっていた(ユーザー報告「セレクトメニューの名称が全てID形式」)。
  // 種別(称号/パーティクル)と表示名をラベル辞書として一緒に返し、セレクト側で
  // primary=日本語 / secondary=ID に出し分ける。
  const SPECIAL_REWARD_GROUPS = [
    ["titles", "称号"],
    ["particles", "パーティクル"],
    ["particle-seeds", "パーティクルシード"]
  ];
  function specialRewardName(group, raw) {
    const entry = raw && typeof raw === "object" ? raw : {};
    if (group === "titles") {
      // 称号の display は MiniMessage。タグを落としたプレーン文字を主表示にする。
      return typeof window.stripDisplayNamePlain === "function"
        ? window.stripDisplayNamePlain(entry.display || "")
        : String(entry.display || "");
    }
    const particle = entry.particle ? String(entry.particle) : "";
    if (!particle) return "";
    return (window.PARTICLE_LABELS_JA && window.PARTICLE_LABELS_JA[particle]) || particle;
  }
  async function loadSpecialRewards() {
    try {
      const r = await api("GET", "/api/config/special-rewards");
      rememberRevision("special-rewards", r.revision);
      const d = (r.data && typeof r.data === "object") ? r.data : {};
      rememberBase("special-rewards", d);
      const ids = [];
      const labels = {};
      for (const [group, kind] of SPECIAL_REWARD_GROUPS) {
        const map = d[group];
        if (!map || typeof map !== "object") continue;
        for (const [id, raw] of Object.entries(map)) {
          if (Object.prototype.hasOwnProperty.call(labels, id)) continue;
          ids.push(id);
          labels[id] = { kind, name: specialRewardName(group, raw) };
        }
      }
      ids.sort();
      return { ids, labels };
    } catch (_) {
      return { ids: [], labels: {} };
    }
  }

  function fillDatalist(id, values) {
    const dl = document.getElementById(id);
    if (!dl) return;
    dl.innerHTML = "";
    for (const v of values) dl.appendChild(h("option", { value: v }));
  }

  // 専用ビュー(__id__) または config id へ遷移する (ホームのカードから使う)。
  function navigateById(id) {
    const view = ALL_TOOL_VIEWS.find((v) => v.id === id);
    if (view) return selectTool(view);
    return selectConfig(id);
  }

  // スキル系統一覧 (/api/skills) を取得してキャッシュする (catalog の use-skill セレクト用)。
  // API 由来のラベルは静的日本語辞書に無い id のみ補完する (既存の日本語ラベルを優先)。
  async function loadSkills() {
    try {
      const r = await api("GET", "/api/skills");
      window.SKILLS = Array.isArray(r.skills) ? r.skills : [];
      const grp = window.LABELS && window.LABELS.ENUM_LABELS && window.LABELS.ENUM_LABELS["use-skill"];
      if (grp) {
        for (const s of window.SKILLS) {
          if (s && s.id && s.label && !grp[s.id]) grp[s.id] = s.label;
        }
      }
    } catch (_) {
      // 取得失敗時はフォールバック(forms.js の固定ID)で動作させる。
      window.SKILLS = window.SKILLS || [];
    }
  }

  // Material名 -> 日本語 の辞書 + 1.21.11 候補一覧をサーバから取得し、表示層に反映する。
  async function loadMaterialLabels() {
    try {
      const r = await api("GET", "/api/material-labels");
      window.MATERIAL_LABELS = r.labels || {};
      if (Array.isArray(r.materials) && r.materials.length > 0) {
        window.MATERIALS = r.materials.slice();
      } else {
        window.MATERIALS = Object.keys(window.MATERIAL_LABELS).sort();
      }
      window.MATERIALS_VERSION = r.version || "1.21.11";
      fillDatalist("material-list", window.MATERIALS);
      document.querySelectorAll(".material-suggest").forEach((el) => {
        if (typeof el._rebuildSuggestIndex === "function") el._rebuildSuggestIndex();
      });
      if (window.MATERIALS.length < 500) {
        console.warn("[config-editor] Material候補が少ないです:", window.MATERIALS.length,
          "— サーバ再起動と lib/materials-1.21.11.json の有無を確認してください。");
      }
      try {
        const ts = await api("GET", "/api/tilestate-materials");
        if (Array.isArray(ts.materials) && ts.materials.length) {
          window.TILESTATE_MATERIALS = ts.materials.slice();
        }
      } catch (e) {
        console.warn("[config-editor] tilestate-materials 取得失敗:", e);
      }
      // 粉砕グリフ(crush_map)の「変換後が非ブロック」警告用。取得失敗時は警告判定を諦める(空集合)。
      try {
        const bm = await api("GET", "/api/block-materials");
        window.BLOCK_MATERIALS = new Set(Array.isArray(bm.materials) ? bm.materials : []);
      } catch (e) {
        console.warn("[config-editor] block-materials 取得失敗:", e);
        window.BLOCK_MATERIALS = new Set();
      }
    } catch (err) {
      console.warn("[config-editor] material-labels 取得失敗:", err);
    }
  }

  async function loadSettings() {
    const s = await api("GET", "/api/settings");
    state.settings = s;
    const box = document.getElementById("basepaths");
    box.innerHTML = "";

    function addPathGroup(title, map, dataAttr) {
      if (!map || typeof map !== "object") return;
      box.appendChild(h("div", { class: "path-group-label", text: title }));
      for (const [key, info] of Object.entries(map)) {
        const input = h("input", {
          class: "field-input path-input",
          value: info.configured || "",
          spellcheck: "false",
          "data-kind": dataAttr,
          "data-key": key
        });
        const status = h("span", {
          class: `path-status ${info.exists ? "ok" : "missing"}`,
          text: info.exists ? "検出" : "未検出"
        });
        box.appendChild(h("div", { class: "path-row" }, [
          h("span", { class: "path-label", text: key }), input, status
        ]));
      }
    }

    addPathGroup("編集元 (repo)", s.basePaths, "base");
    addPathGroup("サーバ反映先", s.deployPaths || {}, "deploy");
    updatePathsSummary(s);
  }

  function updatePathsSummary(settings) {
    const el = document.getElementById("paths-summary");
    if (!el) return;
    el.innerHTML = "";
    const maps = [settings && settings.basePaths, settings && settings.deployPaths];
    let ok = 0;
    let total = 0;
    for (const map of maps) {
      if (!map) continue;
      for (const info of Object.values(map)) {
        total += 1;
        if (info && info.exists) ok += 1;
        el.appendChild(h("span", { class: `paths-dot ${info && info.exists ? "ok" : ""}`, title: info && info.absolute ? info.absolute : "" }));
      }
    }
    if (total) {
      el.appendChild(h("span", { text: `${ok}/${total}` }));
    }
  }

  function setPathsPanelOpen(open) {
    const panel = document.getElementById("paths-panel");
    const btn = document.getElementById("paths-toggle-btn");
    if (!panel || !btn) return;
    if (open) panel.removeAttribute("hidden");
    else panel.setAttribute("hidden", "");
    btn.setAttribute("aria-expanded", open ? "true" : "false");
  }

  function bindPathsPanel() {
    const btn = document.getElementById("paths-toggle-btn");
    const panel = document.getElementById("paths-panel");
    if (!btn || !panel || btn._pathsBound) return;
    btn._pathsBound = true;
    btn.addEventListener("click", (e) => {
      e.stopPropagation();
      setPathsPanelOpen(panel.hasAttribute("hidden"));
    });
    document.addEventListener("click", (e) => {
      if (panel.hasAttribute("hidden")) return;
      if (panel.contains(e.target) || btn.contains(e.target)) return;
      setPathsPanelOpen(false);
    });
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape") setPathsPanelOpen(false);
    });
  }

  async function saveSettings() {
    const inputs = document.querySelectorAll("#basepaths .path-input");
    const basePaths = {};
    const deployPaths = {};
    inputs.forEach((i) => {
      const kind = i.getAttribute("data-kind");
      const key = i.getAttribute("data-key");
      if (kind === "deploy") deployPaths[key] = i.value;
      else basePaths[key] = i.value;
    });
    try {
      await api("PUT", "/api/settings", { basePaths, deployPaths });
      toast("パス設定を保存しました", "ok");
      await loadSettings();
      await loadConfigs();
      setPathsPanelOpen(false);
    } catch (err) {
      toast(`パス設定の保存失敗: ${err.message}`, "error");
    }
  }

  function deployToastNote(results) {
    const arr = (Array.isArray(results) ? results : [results]).filter(Boolean);
    const deploys = [];
    for (const r of arr) {
      if (r.deploy) deploys.push(r.deploy);
      if (r.deploys && typeof r.deploys === "object") {
        for (const d of Object.values(r.deploys)) deploys.push(d);
      }
    }
    if (!deploys.length) return "";
    const fails = deploys.filter((d) => d && d.ok === false);
    if (fails.length) {
      return ` サーバ反映失敗: ${fails[0].error || "不明"}`;
    }
    const oks = deploys.filter((d) => d && d.ok);
    if (oks.length) return ` サーバconfigへも反映 (${oks.length}件)`;
    const skips = deploys.filter((d) => d && d.skipped === "not configured");
    if (skips.length === deploys.length) return " (deployPaths未設定のためサーバ未反映)";
    return "";
  }

  // PUT /api/config/:id が返す cmdWarnings (CMDのクロスファイル衝突、保存はブロックしない) をまとめる。
  function cmdWarningsNote(results) {
    const arr = (Array.isArray(results) ? results : [results]).filter(Boolean);
    const warnings = [];
    for (const r of arr) {
      if (Array.isArray(r.cmdWarnings)) warnings.push(...r.cmdWarnings);
    }
    if (!warnings.length) return "";
    return `\n[CMD注意] ${warnings.join(" / ")}`;
  }
  async function loadConfigs() {
    const r = await api("GET", "/api/configs");
    state.configs = r.configs;
    renderSidebar();
  }

  // サイドバーのメインカテゴリ折りたたみ状態 (localStorage で再訪時も保持)。
  const NAV_COLLAPSE_KEY = "cfgedit_nav_collapsed";
  const collapsedNavGroups = (() => {
    try {
      const raw = JSON.parse(localStorage.getItem(NAV_COLLAPSE_KEY) || "[]");
      return new Set(Array.isArray(raw) ? raw : []);
    } catch (_) { return new Set(); }
  })();
  function persistCollapsedNavGroups() {
    try { localStorage.setItem(NAV_COLLAPSE_KEY, JSON.stringify([...collapsedNavGroups])); } catch (_) { /* 保存不可でも続行 */ }
  }

  function renderSidebar() {
    const nav = document.getElementById("config-list");
    nav.innerHTML = "";

    // メインカテゴリ見出し (折りたたみトグル付き)。クリックでグループの中身を開閉する。
    function appendGroupTitle(groupKey, title) {
      const collapsed = collapsedNavGroups.has(groupKey);
      nav.appendChild(h("button", {
        class: "nav-group-title nav-group-toggle",
        type: "button",
        title: collapsed ? "クリックで展開" : "クリックで折りたたみ",
        onclick: () => {
          if (collapsedNavGroups.has(groupKey)) collapsedNavGroups.delete(groupKey);
          else collapsedNavGroups.add(groupKey);
          persistCollapsedNavGroups();
          renderSidebar();
        }
      }, [
        h("span", { class: "nav-group-caret", text: collapsed ? "▸" : "▾" }),
        h("span", { text: title })
      ]));
      return !collapsed;
    }

    // config をドメイン (CONFIG_SECTIONS) ごとにまとめて表示。
    // 「アイテム系config」ラッパーは置かず、各ドメインをメイン見出しとして並べる。
    // NAV_SECTIONS 側の configSectionKey (「機能アイテム」グループ) からも参照するため、
    // 専用ビュー群のループより先にバケツ分けと描画ヘルパーを用意しておく。
    const buckets = {};
    function collectSectionKeys(sections) {
      for (const section of sections) {
        if (section.children) collectSectionKeys(section.children);
        else buckets[section.key] = [];
      }
    }
    collectSectionKeys(CONFIG_SECTIONS);
    collectSectionKeys([FUNCTIONAL_ITEMS_NAV_SECTION]);

    for (const c of state.configs) {
      if (HIDDEN_CONFIG_IDS.includes(c.id)) continue;
      const key = buckets[c.section] ? c.section : FALLBACK_SECTION_KEY;
      if (!buckets[key]) buckets[key] = [];
      buckets[key].push(c);
    }

    function sortByOrder(items, order) {
      if (!order) return items;
      return items.slice().sort((a, b) => {
        const ia = order.indexOf(a.id);
        const ib = order.indexOf(b.id);
        return (ia === -1 ? order.length : ia) - (ib === -1 ? order.length : ib);
      });
    }

    function appendConfigItems(items) {
      for (const c of items) {
        nav.appendChild(h("button", {
          class: `nav-item ${state.current === c.id ? "active" : ""}`,
          type: "button",
          onclick: () => selectConfig(c.id)
        }, [
          h("span", { class: "nav-item-label", text: String(c.label || c.id).replace(/\s*\([^)]*\)/g, "") })
        ]));
      }
    }

    // level: "flat" は見出しを自前で出さない(呼び出し元が既に nav:<title> の見出しを描画済み)。
    // NAV_SECTIONS の configSectionKey 経由の呼び出し専用。
    function renderConfigSection(section) {
      const isFlat = section.level === "flat";
      const isMain = !isFlat && section.level !== "sub";
      if (isFlat) {
        // 見出しなし・items だけ並べる。
      } else if (isMain) {
        // メイン見出しは折りたたみ可能。折りたたみ中はサブ見出し・項目ごと隠す。
        if (!appendGroupTitle(`cfg:${section.key}`, section.title)) return;
      } else {
        nav.appendChild(h("div", { class: "nav-subgroup-title", text: section.title }));
      }
      if (Array.isArray(section.children) && section.children.length) {
        for (const child of section.children) renderConfigSection(child);
        return;
      }
      const items = sortByOrder(buckets[section.key] || [], section.order);
      if (!items.length) return;
      appendConfigItems(items);
    }

    // 専用ビュー群 (はじめに / アイテムカタログ / アイテムステータス / 機能アイテム / 戦闘ツール /
    // リソースパック) をグループ表示。configSectionKey を持つエントリ(「機能アイテム」)は
    // 専用ビューを持たず、代わりに registry 由来の config (FUNCTIONAL_ITEMS_NAV_SECTION) を
    // このグループ見出しの直下へ差し込む。
    for (const section of NAV_SECTIONS) {
      if (!appendGroupTitle(`nav:${section.title}`, section.title)) continue;
      if (section.configSectionKey === "functional-items") {
        renderConfigSection(FUNCTIONAL_ITEMS_NAV_SECTION);
        continue;
      }
      for (const view of section.views) {
        nav.appendChild(h("button", {
          class: `nav-item ${state.current === view.id ? "active" : ""}`,
          type: "button",
          onclick: () => selectTool(view)
        }, [
          h("span", { class: "nav-item-label", text: view.label })
        ]));
      }
    }

    for (const section of CONFIG_SECTIONS) renderConfigSection(section);
  }

  function isConfigDataChanged(configId, data) {
    if (!configId || typeof window.deepEqual !== "function") return false;
    const base = state.baseSnapshots[configId];
    if (base == null) return true;
    return !window.deepEqual(data, base);
  }

  /** 現在のエディタに自分の未保存変更があるか（読み込み時のローカル基準と比較）。 */
  function isEditorDirty() {
    if (!state.editor || typeof state.editor.getData !== "function") return false;
    if (typeof window.deepEqual !== "function") return false;
    if (state.kind === "constants") {
      if (!state.constantsBase) return false;
      return !window.deepEqual(state.editor.getData(), state.constantsBase);
    }
    if (state.kind !== "config" && state.kind !== "split") return false;
    const configId = state.kind === "split" && state.editor.configId
      ? state.editor.configId
      : state.current;
    if (!configId || !state.baseSnapshots[configId]) return false;
    if (isConfigDataChanged(configId, state.editor.getData())) return true;
    if (typeof state.editor.getExtraSaves === "function") {
      for (const extra of state.editor.getExtraSaves()) {
        if (!extra || !extra.id) continue;
        const base = state.baseSnapshots[extra.id];
        if (base == null) continue;
        if (isConfigDataChanged(extra.id, extra.data)) return true;
      }
    }
    if (typeof state.editor.getCraftData === "function" && state.baseSnapshots[QUALITY_COMPANION_ID]) {
      if (!window.deepEqual(state.editor.getCraftData(), state.baseSnapshots[QUALITY_COMPANION_ID])) return true;
    }
    if (typeof state.editor.getProgressionData === "function") {
      const curves = state.editor.getProgressionData() || {};
      for (const skillId of Object.keys(curves)) {
        const progressionId = `progression-${skillId}`;
        if (state.baseSnapshots[progressionId]
            && isConfigDataChanged(progressionId, curves[skillId])) return true;
      }
    }
    return false;
  }

  /** 未保存のまま離れる場合は確認。同じ画面への再入はスキップ。保存を第一選択に提示する。 */
  async function confirmLeaveIfDirty(nextId) {
    if (nextId != null && nextId === state.current) return true;
    if (!isEditorDirty()) return true;
    const choice = await saveBeforeLeaveModal();
    if (choice === "cancel") return false;
    if (choice === "save") {
      await save();
      // 保存が中断/失敗して未保存が残っている場合は移動しない(変更を失わない)。
      return !isEditorDirty();
    }
    // "discard": 保存せずに移動
    return true;
  }

  // 2026-07-29: 画面切替の競合ガード。
  // selectTool / selectConfig は「main を空にする → await で config を取得 → 追記」という
  // 順で動くため、取得中にもう一度切り替えると 2 つの呼び出しが両方とも最後の追記まで
  // 走り、同じ main に 2 つのビューが並ぶ(= カテゴリタブから下がページ内に複製される)。
  // アイテムカタログ/アイテムステータスは 1 画面で 3 本 API を叩くので特に踏みやすい。
  // 切替のたびに世代番号を進め、追記直前に「自分が最新か」を確かめる。
  let navSeq = 0;
  function beginNav() { return ++navSeq; }
  /** 追記直前の共通処理。古い呼び出しなら false を返して何も描かない。 */
  function mountMain(token, el) {
    if (token !== navSeq) return false;
    const main = document.getElementById("editor-area");
    if (!main) return false;
    main.innerHTML = "";
    main.appendChild(el);
    return true;
  }

  /** タブ(サイドメニュー)切替時はスクロールを最上部へ戻す (前タブの位置を引き継がない)。 */
  function resetMainScroll() {
    const mainPane = document.querySelector(".main");
    if (mainPane) mainPane.scrollTop = 0;
  }

  // 専用ビュー(ホーム / 使い方 / 共通変数 / シミュレータ)へ切り替える。
  async function selectTool(view) {
    if (!(await confirmLeaveIfDirty(view.id))) return;
    const navToken = beginNav();
    state.current = view.id;
    state.kind = view.kind;
    state.editor = null;
    renderSidebar();
    const main = document.getElementById("editor-area");
    main.innerHTML = "";
    resetMainScroll();
    const saveBtn = document.getElementById("save-btn");
    document.getElementById("editor-title").textContent = view.label;

    if (view.kind === "home") {
      document.getElementById("editor-sub").textContent = "このツールでできることと、今の編集対象(ベースパス)を確認できます。";
      saveBtn.disabled = true;
      main.appendChild(window.buildHomeView({ settings: state.settings, onNavigate: navigateById }));
      return;
    }

    if (view.kind === "manual") {
      document.getElementById("editor-sub").textContent = "各機能の使い方を日本語で解説します。";
      saveBtn.disabled = true;
      main.appendChild(window.buildManualView());
      return;
    }

    if (view.kind === "respack") {
      document.getElementById("editor-sub").textContent =
        "CMD台帳の一覧確認とリソースパックのビルドを行います。保存対象はありません(読み取り+アクション実行のみ)。";
      saveBtn.disabled = true;
      main.appendChild(window.buildRespackView());
      return;
    }

    if (view.kind === "split") {
      document.getElementById("editor-sub").textContent =
        "任意カテゴリタブで整理できます。実YAMLは対応するファイルへ保存されます。";
      try {
        const sp = view.split || {};
        const loaded = {};
        if (sp.type === "thread-bundle") {
          const tr = await api("GET", "/api/config/threads");
          const sr = await api("GET", "/api/config/thread-sets");
          rememberRevision("threads", tr.revision);
          rememberRevision("thread-sets", sr.revision);
          const threadsData = (tr.data && typeof tr.data === "object") ? tr.data : {};
          const threadSetsData = (sr.data && typeof sr.data === "object") ? sr.data : {};
          rememberBase("threads", threadsData);
          rememberBase("thread-sets", threadSetsData);
          state.editor = window.buildSplitConfigView({
            type: "thread-bundle",
            configId: "threads",
            categoryKey: sp.categoryKey || "thread",
            threadsData,
            threadSetsData
          });
          syncBaseFromEditor("threads");
        } else {
          const r = await api("GET", `/api/config/${sp.configId}`);
          rememberRevision(sp.configId, r.revision);
          loaded.data = (r.data && typeof r.data === "object") ? r.data : {};
          rememberBase(sp.configId, loaded.data);
          let catalogCandidates = [];
          if (sp.type === "item-stats") {
            catalogCandidates = await fetchCatalogCandidatesWithMaterials();
          }
          // catalog⇄materials のファイル跨ぎ移動用に相手ファイルも読み込んでおく
          // (移動が発生した保存時だけ extraSaves 経由で一緒に PUT される)。
          let counterpartId = null;
          let counterpartData = null;
          if (sp.type === "catalog" || sp.type === "materials") {
            counterpartId = sp.type === "catalog" ? "materials" : "catalog";
            try {
              const xr = await api("GET", `/api/config/${counterpartId}`);
              rememberRevision(counterpartId, xr.revision);
              counterpartData = (xr.data && typeof xr.data === "object") ? xr.data : {};
              rememberBase(counterpartId, counterpartData);
            } catch (_) {
              // 相手ファイルが読めなくても編集自体は継続 (跨ぎ移動だけ無効化)。
              counterpartId = null;
              counterpartData = null;
            }
          }
          state.editor = window.buildSplitConfigView({
            type: sp.type,
            configId: sp.configId,
            categoryKey: sp.categoryKey || "default",
            itemCategory: sp.itemCategory,
            data: loaded.data,
            catalogCandidates,
            counterpartId,
            counterpartData
          });
          syncBaseFromEditor(sp.configId);
        }
        if (!mountMain(navToken, state.editor.element)) return;
        saveBtn.disabled = false;
      } catch (err) {
        if (!mountMain(navToken, h("div", { class: "empty", text: `読み込み失敗: ${err.message}` }))) return;
        saveBtn.disabled = true;
      }
      return;
    }

    if (view.kind === "constants") {
      document.getElementById("editor-sub").textContent =
        "複数の戦闘バランス定数を集約編集します (combat/damage.yml, progression/combat-level.yml)。";
      try {
        const r = await api("GET", "/api/constants");
        state.constantsRevision = r.revision != null ? r.revision : null;
        state.constantsBase = typeof window.cloneData === "function"
          ? window.cloneData(r.constants) : JSON.parse(JSON.stringify(r.constants || {}));
        state.editor = window.buildConstantsView(r.constants);
        syncBaseFromEditor();
        if (!mountMain(navToken, state.editor.element)) return;
        saveBtn.disabled = false;
      } catch (err) {
        if (!mountMain(navToken, h("div", { class: "empty", text: `読み込み失敗: ${err.message}` }))) return;
        saveBtn.disabled = true;
      }
      return;
    }

    // simulator
    document.getElementById("editor-sub").textContent =
      "TrinityForge の計算式を写したバランス確認用プレビューです。実挙動の権威は TF 本体にあります。";
    saveBtn.disabled = true;
    let defaults = null;
    try {
      const r = await api("GET", "/api/constants");
      defaults = r.constants;
    } catch (_) { /* 定数が読めなくてもフォーム既定値で動作する */ }
    const view2 = window.buildSimulatorView(defaults);
    state.editor = view2;
    mountMain(navToken, view2.element);
  }

  async function selectConfig(id) {
    if (!(await confirmLeaveIfDirty(id))) return;
    const navToken = beginNav();
    state.current = id;
    state.kind = "config";
    renderSidebar();
    const main = document.getElementById("editor-area");
    main.innerHTML = "";
    resetMainScroll();
    document.getElementById("save-btn").disabled = true;

    let r;
    try {
      r = await api("GET", `/api/config/${id}`);
    } catch (err) {
      mountMain(navToken, h("div", { class: "empty", text: `読み込み失敗: ${err.message}` }));
      return;
    }
    if (navToken !== navSeq) return;
    rememberRevision(id, r.revision);

    const meta = state.configs.find((c) => c.id === id) || {};
    document.getElementById("editor-title").textContent =
      String(meta.label || id).replace(/\s*\([^)]*\)\s*$/, "");
    document.getElementById("editor-sub").textContent = r.exists
      ? "設定ファイルを編集中"
      : "未作成（保存すると新規作成されます）";

    const data = r.data && typeof r.data === "object" ? r.data : {};
    rememberBase(id, data);
    const editor = await buildEditorForLoadedConfig(r.schema, data);
    state.editor = editor;
    syncBaseFromEditor(id);
    if (!mountMain(navToken, editor.element)) return;
    document.getElementById("save-btn").disabled = false;
  }

  // 本文コメントが多いファイルは、保存でヘッダ以外のコメントが失われる。初回のみ確認する。
  const COMMENT_HEAVY_SCHEMAS = new Set([
    "ars-recipes", "ars-materials", "ars-threads",
    // P4: 本文コメントが多い (glyphs は特に多い) ファイル群。
    "ars-glyphs", "tf-lore",
    "tf-quality", "tf-craft-quality", "tf-quality-tiers", "tf-skill-exp",
    // spellbooks.yml はティアごとに区切りコメントが多い。
    "ars-spellbooks",
    // gacha.yml / thread-sets.yml はヘッダ解説コメントが多い。
    "tf-gacha", "ars-thread-sets",
    // skilltree/*.yml はヘッダ+ノードごとの区切りコメントが非常に多い (汎用エディタで編集)。
    "tf-skilltree",
    // crafting-features / use-requirements はヘッダ解説コメントが多い。
    // T6 (2026-07-26): エンチャント/醸造ギミックは crafting-features.yml を丸ごと保存し、
    // 鍛冶/伐採ギミックはそのコンパニオンとして crafting-features.yml も一緒に保存するため、
    // どちらも同じ理由でコメント消失リスクの警告対象に含める。
    "tf-crafting-features", "tf-use-requirements", "tf-enchant-gimmick", "tf-brew-gimmick",
    "tf-smithing-gimmick",
    // mob-types.yml はヘッダ解説+記述例コメントが多い。
    "tf-mob-types",
    // mob-level-table.yml もヘッダ解説+記述例コメントが多い。
    "tf-mob-level-table",
    // mob-overrides.yml もヘッダ解説+記述例コメントが多い (2026-07-26新設)。
    "tf-mob-overrides",
    "tf-dungeon-gates", "tf-dungeon-themes", "tf-mob-profiles", "tf-mob-import", "tf-hate-rates",
    "tf-combat-display",
    "tf-mining-gimmick", "tf-woodcutting-gimmick", "tf-digging-gimmick", "tf-farming-gimmick",
    "tf-food-gimmick", "tf-fishing-gimmick", "tf-villager-trades", "tf-role-buffs",
    "tf-base-stats",
    "ars-config", "ars-ban",
    "tf-skills-ars-magic", "tf-skills-ars-smithing",
    "ars-sourcejars", "ars-sourcelinks"
  ]);
  const HIDE_COMMENT_WARN_KEY = "cfgedit_hide_comment_warn";
  // M-8: 「今後表示しない」は schema 別に記憶する (1ファイル種別で消しても他種別では警告を出す)。
  function hideCommentWarnKey(schema) { return `${HIDE_COMMENT_WARN_KEY}_${schema}`; }

  function confirmCommentLoss(schema) {
    return new Promise((resolve) => {
      if (!COMMENT_HEAVY_SCHEMAS.has(schema)) return resolve(true);
      const storageKey = hideCommentWarnKey(schema);
      let hide = false;
      try { hide = localStorage.getItem(storageKey) === "1"; } catch (_) { /* localStorage 不可でも続行 */ }
      if (hide) return resolve(true);

      const overlay = h("div", { class: "modal-overlay" });
      const dontShow = h("input", { type: "checkbox" });
      const cleanup = () => { if (overlay.parentNode) overlay.parentNode.removeChild(overlay); };
      const box = h("div", { class: "modal-box" }, [
        h("div", { class: "modal-title", text: "保存前の確認" }),
        h("div", { class: "modal-text", text: "このファイルは本文コメントが多く、保存でヘッダ以外のコメントが失われます（保存前に自動バックアップを作成します）。続行しますか？" }),
        h("label", { class: "modal-check" }, [dontShow, h("span", { text: "今後このファイル種別で表示しない" })]),
        h("div", { class: "modal-actions" }, [
          h("button", { class: "btn ghost", type: "button", text: "キャンセル", onclick: () => { cleanup(); resolve(false); } }),
          h("button", {
            class: "btn primary", type: "button", text: "続行して保存",
            onclick: () => {
              if (dontShow.checked) { try { localStorage.setItem(storageKey, "1"); } catch (_) { /* 保存不可でも続行 */ } }
              cleanup(); resolve(true);
            }
          })
        ])
      ]);
      overlay.appendChild(box);
      document.body.appendChild(overlay);
    });
  }

  /** 未編集の保存操作ではPUTせず、サーバ上の最新内容を安全に再読込する。 */
  async function reloadCurrentEditor() {
    if (state.kind === "config" && state.current) {
      await selectConfig(state.current);
      return;
    }
    const view = ALL_TOOL_VIEWS.find((v) => v.id === state.current);
    if (view) await selectTool(view);
  }

  async function save() {
    if (!state.current || !state.editor) return;
    // 古い画面を開いていただけの利用者が保存して、他の編集者の変更と競合する経路を防ぐ。
    if (!isEditorDirty()) {
      await reloadCurrentEditor();
      toast("自分の変更はありません。サーバの最新内容を再読込しました。", "ok");
      return;
    }
    if (state.kind === "constants") return saveConstants();
    if (state.kind === "split") return saveSplitView();
    if (state.kind !== "config") return;
    const meta = state.configs.find((c) => c.id === state.current) || {};
    const proceed = await confirmCommentLoss(meta.schema);
    if (!proceed) return;
    if (meta.schema === "item-stats" || meta.schema === "tf-skill-exp") {
      /* ok */
    }
    if (typeof state.editor.getCmdCollisionData === "function" || meta.schema === "item-stats") {
      const statsData = typeof state.editor.getCmdCollisionData === "function"
        ? state.editor.getCmdCollisionData()
        : (typeof state.editor.getData === "function" ? state.editor.getData() : null);
      if (statsData && typeof window.collectItemStatsCmdCollisions === "function") {
        const msgs = window.collectItemStatsCmdCollisions(statsData, {
          weapon: "武器", armor: "防具", tool: "ツール", other: "補助"
        });
        if (msgs.length) {
          toast("CMD衝突があるため保存できません:\n" + msgs.join("\n"), "error");
          return;
        }
      }
    }
    const data = state.editor.getData();
    try {
      const saved = [];
      if (isConfigDataChanged(state.current, data)) {
        const r = await putConfig(state.current, data);
        if (!r) return;
        if (r.merged) return;
        saved.push(r);
      }
      // 機能アイテムレシピタブ等、複数configへ跨って保存するフォーム用 (getExtraSaves)。
      if (typeof state.editor.getExtraSaves === "function") {
        for (const extra of state.editor.getExtraSaves()) {
          if (!extra || !extra.id) continue;
          if (!isConfigDataChanged(extra.id, extra.data)) continue;
          const er = await putConfig(extra.id, extra.data);
          if (!er) return;
          if (er.merged) return;
          saved.push(er);
        }
      }
      // 品質定義ビューは craft-quality.yml(mode/drop) も一緒に保存する (統合表示)。
      if (typeof state.editor.getCraftData === "function") {
        const craftData = state.editor.getCraftData();
        if (isConfigDataChanged(QUALITY_COMPANION_ID, craftData)) {
          const cr = await putConfig(QUALITY_COMPANION_ID, craftData);
          if (!cr) return;
          if (cr.merged) return;
          saved.push(cr);
        }
      }
      // skill-exp ビューは skills/base/*_progression.yml の曲線も一緒に保存する。
      if (typeof state.editor.getProgressionData === "function") {
        const curves = state.editor.getProgressionData() || {};
        for (const skillId of Object.keys(curves)) {
          if (!PROGRESSION_SKILL_IDS.includes(skillId)) continue;
          const progressionId = `progression-${skillId}`;
          if (!isConfigDataChanged(progressionId, curves[skillId])) continue;
          const pr = await putConfig(progressionId, curves[skillId]);
          if (!pr) return;
          if (pr.merged) return;
          saved.push(pr);
        }
      }
      const first = saved[0] || {};
      const backupMsg = first.backup ? `自動バックアップを作成しました (${first.backup})` : "";
      const warnNote = cmdWarningsNote(saved);
      toast(`保存しました。${backupMsg}${deployToastNote(saved)}${warnNote}`, warnNote ? "warn" : "ok");
      await loadConfigs();
      // lore の表示名変更をステータスセレクトへ即時反映
      if (state.current === "lore" || meta.schema === "tf-lore") {
        await loadStatList();
      }
    } catch (err) {
      toast(formatSaveError(err), "error");
    }
  }

  async function saveSplitView() {
    const ed = state.editor;
    if (!ed || typeof ed.getData !== "function") return;
    if (typeof ed.getCmdCollisionData === "function") {
      const statsData = ed.getCmdCollisionData();
      if (statsData && typeof window.collectItemStatsCmdCollisions === "function") {
        const msgs = window.collectItemStatsCmdCollisions(statsData, {
          weapon: "武器", armor: "防具", tool: "ツール", other: "補助"
        });
        if (msgs.length) {
          toast("CMD衝突があるため保存できません:\n" + msgs.join("\n"), "error");
          return;
        }
      }
    }
    const configId = ed.configId;
    if (!configId) {
      toast("保存先 config が不明です", "error");
      return;
    }
    try {
      const saved = [];
      const primaryData = ed.getData();
      if (isConfigDataChanged(configId, primaryData)) {
        const r = await putConfig(configId, primaryData);
        if (!r) return;
        if (r.merged) return;
        saved.push(r);
      }
      if (typeof ed.getExtraSaves === "function") {
        for (const extra of ed.getExtraSaves()) {
          if (!isConfigDataChanged(extra.id, extra.data)) continue;
          const er = await putConfig(extra.id, extra.data);
          if (!er) return;
          if (er.merged) return;
          saved.push(er);
        }
      }
      const warnNote = cmdWarningsNote(saved);
      toast(`保存しました。${deployToastNote(saved)}${warnNote}`, warnNote ? "warn" : "ok");
      await loadConfigs();
    } catch (err) {
      toast(formatSaveError(err), "error");
    }
  }

  // 保存失敗を「見出し + 箇条書きの詳細」の読みやすい日本語にまとめる。
  function formatSaveError(err) {
    let msg = `保存できませんでした: ${err.message}`;
    if (err.details && err.details.length) {
      msg += "\n\n次の項目を確認してください:";
      for (const d of err.details) msg += `\n・${d}`;
    }
    msg += "\n\n(このメッセージはクリックで閉じます。ファイルは変更されていません)";
    return msg;
  }

  async function saveConstants() {
    const constants = state.editor.getData();
    try {
      const body = { constants };
      if (state.constantsRevision != null) body.expectedRevision = state.constantsRevision;
      let r;
      try {
        r = await api("PUT", "/api/constants", body);
      } catch (err) {
        if (err.status === 409 && err.payload && err.payload.conflict) {
          state.constantsRevision = err.payload.revision;
          const choice = await conflictChoiceModal();
          if (choice === "discard") {
            toast("相手の最新を読み直しました。未保存の自分の変更は破棄されています。", "warn");
            const view = ALL_TOOL_VIEWS.find((v) => v.id === state.current);
            if (view) await selectTool(view);
            return;
          } else if (choice === "merge") {
            const base = state.constantsBase != null ? state.constantsBase : {};
            const remote = err.payload.constants && typeof err.payload.constants === "object"
              ? err.payload.constants : {};
            const merged = typeof window.threeWayMerge === "function"
              ? window.threeWayMerge(base, constants, remote)
              : { data: constants, conflicts: [] };
            state.constantsBase = typeof window.cloneData === "function"
              ? window.cloneData(remote) : JSON.parse(JSON.stringify(remote));
            await applyMergedToEditor("__constants__", merged.data);
            toastMergeResult(merged.conflicts);
            return;
          } else {
            return;
          }
        } else {
          throw err;
        }
      }
      if (r.revision != null) state.constantsRevision = r.revision;
      state.constantsBase = typeof window.cloneData === "function"
        ? window.cloneData(constants) : JSON.parse(JSON.stringify(constants));
      const names = Object.values(r.backups || {}).filter(Boolean);
      const backupMsg = names.length ? `自動バックアップ: ${names.join(", ")}` : "";
      toast(`共通変数を保存しました。${backupMsg}${deployToastNote(r)}`, "ok");
    } catch (err) {
      toast(formatSaveError(err), "error");
    }
  }

  async function init() {
    fillDatalist("material-list", window.MATERIALS);
    document.getElementById("save-btn").addEventListener("click", save);
    document.getElementById("save-paths-btn").addEventListener("click", saveSettings);
    bindPathsPanel();
    window.addEventListener("beforeunload", (e) => {
      if (!isEditorDirty()) return;
      e.preventDefault();
      e.returnValue = "";
    });
    try {
      await loadSettings();
      await loadMaterialLabels();
      await loadSkills();
      await loadStatList();
      await loadConfigs();
      // 起動直後は「はじめに」ホームを表示する。
      const home = ALL_TOOL_VIEWS.find((v) => v.kind === "home");
      if (home) selectTool(home);
    } catch (err) {
      toast(`初期化エラー: ${err.message}`, "error");
    }
  }

  window.addEventListener("DOMContentLoaded", init);
})();
