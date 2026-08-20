"use strict";

// スキルツリー「解放効果」動的ゲートIDの純関数群。
// 設計書 2026-07-23-stat-gate-overhaul.md §3 準拠。
//   id形式: glyph:<key> / brew:<groupId> / trade:<profId> / recipe:<itemId> / ritual:<itemId>
//           drop:<prof>:<categoryId> / drop:<prof>:item:<itemId> / feature:<id> / overenchant:<id>
//           reward:<id> / ars-tier (コロン無し、加算型)
// tf-skilltree.js のUIとテスト(test/gate-effects.test.js)の両方から window.GATE_EFFECTS 経由で使う。

(function () {
  const PREFIX_TYPES = {
    glyph: "glyph",
    brew: "brew",
    trade: "trade",
    recipe: "recipe",
    ritual: "ritual",
    drop: "drop",
    feature: "feature",
    overenchant: "overenchant",
    reward: "reward"
  };

  const TYPE_LABELS = {
    glyph: "グリフ解放",
    brew: "醸造解放",
    trade: "取引解放",
    recipe: "レシピゲート",
    ritual: "レシピゲート（儀式エフェクト）",
    drop: "ドロップ解放",
    feature: "機能解放",
    overenchant: "オーバーエンチャ",
    "ars-tier": "ArsTier",
    reward: "特殊報酬"
  };

  // 一回性(unique)警告の対象種別。ars-tier は加算型(+N)のため対象外(設計書§3.2)。
  const UNIQUE_TYPES = new Set([
    "glyph", "brew", "trade", "recipe", "ritual", "drop", "feature", "overenchant", "reward"
  ]);

  /**
   * プレフィックス付き動的IDを解析する。
   * @param {*} id
   * @returns {{type:string, target:string, raw:string}|null} 未知形式(旧IDなど)は null
   */
  function parseGateEffectId(id) {
    const raw = String(id == null ? "" : id).trim();
    if (!raw) return null;
    if (raw === "ars-tier") return { type: "ars-tier", target: "", raw };
    const idx = raw.indexOf(":");
    if (idx <= 0) return null;
    const prefix = raw.slice(0, idx);
    const target = raw.slice(idx + 1);
    if (!PREFIX_TYPES[prefix] || !target) return null;
    return { type: PREFIX_TYPES[prefix], target, raw };
  }

  // drop:<prof>:<categoryId> / drop:<prof>:item:<itemId> の target をさらに分解する。
  function parseDropTarget(target) {
    const parts = String(target || "").split(":");
    const profession = parts[0] || "";
    if (parts[1] === "item") {
      return { profession, mode: "item", itemId: parts.slice(2).join(":") };
    }
    return { profession, mode: "category", categoryId: parts.slice(1).join(":") };
  }

  function isLegacyGateEffectId(id) {
    return parseGateEffectId(id) === null;
  }

  function gateEffectTypeLabel(type) {
    return TYPE_LABELS[type] || type;
  }

  function isUniqueGateEffectType(type) {
    return UNIQUE_TYPES.has(type);
  }

  /**
   * 重複判定に使うキーを1配置ぶん返す。判定対象外(ars-tier / 旧形式 / 欠損)は null。
   *
   * feature:<id> の value は「そのノードが解放する段階(tier)」であり、Java 側は
   * DedicatedEffectGateIndex#valueMaxByPerks で保持ノードのうち最大の tier を採る。
   * つまり同じ機能を tier 違いで複数ノードに置くのは設計どおりの正しい形なので、
   * feature だけは tier までキーに含めて「引数が違えば別物」とする
   * (2026-08-14: id だけで数えていたため tier 違いが全部「⚠ 重複」になっていた)。
   * 空欄の value は FeatureEffectParam#defaultsMissingValue により tier1 として読まれるので
   * 明示 value:1 と同一キーに畳む。
   *
   * feature 以外の unique 種別 (glyph/brew/trade/recipe/ritual/drop/overenchant/reward) は
   * 純粋な on/off 解放で value に意味が無い。手書き yml に紛れ込んだ value で
   * 重複警告が消えないよう、value は一切キーに入れない。
   * @param {{id:*, value:*}} placement dedicated-effects の1要素
   * @returns {string|null}
   */
  function gateEffectDuplicateKey(placement) {
    const parsed = parseGateEffectId(placement && placement.id);
    if (!parsed || !isUniqueGateEffectType(parsed.type)) return null;
    if (parsed.type !== "feature") return parsed.raw;
    const raw = placement.value;
    const tier = raw == null || raw === "" ? 1 : Number(raw);
    return `${parsed.raw}#${Number.isFinite(tier) ? tier : String(raw)}`;
  }

  /**
   * 重複しているキーごとに「どこに置かれているか」を返す。
   *
   * <p>2026-08-16: 以前は開いているツリー1本の nodes しか見ておらず、<b>ツリーをまたぐ重複を
   * 構造的に検出できなかった</b>(Java 側 DedicatedEffectGateIndex は全16ツリー横断で警告を出す)。
   * 実際 glyph:snare が alchemy と ars_magic の両方に置かれていたのを editor は一度も表示できて
   * いなかった。他ツリー分は {@code extraPlacements} で渡す。
   *
   * @param {object} nodes 開いているツリーの working.nodes
   * @param {Array<{id:*, value:*, where:string}>} [extraPlacements] 他ツリーの配置(保存済み)
   * @param {string} [currentLabel] 開いているツリーの表示名(where 用)
   * @returns {Map<string, string[]>} 2箇所以上にあるキー → 配置場所の一覧
   */
  function computeDuplicateGateEffectLocations(nodes, extraPlacements, currentLabel) {
    const locations = new Map();
    const push = (placement, where) => {
      const key = gateEffectDuplicateKey(placement);
      if (key == null) return;
      if (!locations.has(key)) locations.set(key, []);
      locations.get(key).push(where);
    };
    for (const nodeId of Object.keys(nodes || {})) {
      const node = nodes[nodeId];
      const list = node && Array.isArray(node["dedicated-effects"]) ? node["dedicated-effects"] : [];
      for (const placement of list) {
        push(placement, `${currentLabel || "このツリー"}/${nodeId}`);
      }
    }
    for (const placement of Array.isArray(extraPlacements) ? extraPlacements : []) {
      push(placement, (placement && placement.where) || "他ツリー");
    }
    for (const [key, where] of [...locations]) {
      if (where.length < 2) locations.delete(key);
    }
    return locations;
  }

  /**
   * 全ノード横断で、一回性の解放効果が同じ設定のまま複数ノードに置かれていれば警告集合を返す。
   * ars-tier(加算型)は対象外。旧形式(未知プレフィックス)のIDは判定不能のため対象外。
   * @param {object} nodes skilltree working.nodes
   * @param {Array<{id:*, value:*, where:string}>} [extraPlacements] 他ツリーの配置
   * @returns {Set<string>} 重複しているキーの集合 (gateEffectDuplicateKey と同じ形)
   */
  function computeDuplicateGateEffectIds(nodes, extraPlacements) {
    return new Set(computeDuplicateGateEffectLocations(nodes, extraPlacements).keys());
  }

  /**
   * feature:<id> 配置の value 数値入力欄が編集されたときの新しい value を返す純関数
   * (2026-07-25 アクティブスキルtier常時1固定バグ修正)。
   * Java側 FeatureEffectParam の意味論と一致させる:
   *   - "scale": value キー自体が無ければ tier1 相当として扱われる
   *     (FeatureEffectParam#defaultsMissingValue)。空欄入力を 0 として書き込むと
   *     「tier0(TierTable#resolveが空を返しフォールバック既定値になる)」という別の意味になって
   *     しまうため、空欄は value キーを削除して「未設定」に戻す。
   *   - "level"(またはその他の requiresValue 系): 値が必須で、欠落は配置ごと読み込み時にdropされる
   *     (FeatureEffectParam#requiresValue)。空欄はドロップされるより 0 を明示するほうが安全なため、
   *     既存の慣例どおり 0 にフォールバックする。
   * @param {"scale"|"level"|"none"|string} param
   * @param {number|null} rawValue numberInput の onInput が返す値(空欄は null)
   * @returns {{remove:true}|{remove:false, value:number}}
   */
  function resolveFeatureValueEdit(param, rawValue) {
    if (param === "scale") {
      return rawValue == null ? { remove: true } : { remove: false, value: rawValue };
    }
    return { remove: false, value: rawValue == null ? 0 : rawValue };
  }

  /**
   * recipe: ゲートのターゲットを実行時のキー形へ正規化する(2026-08-16)。
   *
   * <p>実行時のゲートキーは {@code CatalogCraftGateListener#resolveGateId} が返す
   * 「レシピキーの path 部分」で、バニラレシピでは必ず小文字(`diamond_sword`)。ところが
   * レシピゲート欄は Material セレクト(`DIAMOND_SWORD` 形式)を候補に出すため、選ぶと
   * `recipe:DIAMOND_SWORD` が書かれ、大文字小文字が一致せず<b>無言で効かないゲート</b>になる
   * (GateEffectId#parse はプレフィックスしか小文字化しない)。
   *
   * <p>カタログID/ArsPaperのエントリIDは元から小文字なので影響を受けない。判定は
   * 「英大文字を含み、かつ小文字を含まない」= Material 名の形だけに絞る。
   * @param {*} raw
   * @returns {string}
   */
  function normalizeRecipeGateTarget(raw) {
    const target = String(raw == null ? "" : raw).trim();
    if (!target) return "";
    return /^[A-Z0-9_]+$/.test(target) ? target.toLowerCase() : target;
  }

  /**
   * recipe:/ritual: のチャンネル取り違えを判定する(2026-08-16)。
   *
   * <p>ArsPaper の UnlockGate は {@code hasRecipePermission} と {@code hasRitualPermission} が
   * <b>別々のマップ</b>を引くので、儀式アイテムを recipe: 側に置くと儀式経路はそのマップを一切見ず
   * <b>無言で常時解放</b>になる(逆も同じ)。語彙は両方持っているので editor 側で検出できる。
   * @param {"recipe"|"ritual"} prefix いま書かれているチャンネル
   * @param {string} target
   * @param {{recipes?:string[], rituals?:string[]}} vocab
   * @returns {null|{correct:"recipe"|"ritual", message:string}} 問題なしは null
   */
  function gateChannelMismatch(prefix, target, vocab) {
    const id = String(target == null ? "" : target).trim();
    if (!id) return null;
    const recipes = (vocab && vocab.recipes) || [];
    const rituals = (vocab && vocab.rituals) || [];
    if (prefix === "recipe" && rituals.includes(id) && !recipes.includes(id)) {
      return {
        correct: "ritual",
        message: "これは儀式(method: ritual)のIDです。レシピゲート側に置くと儀式経路が"
          + "このマップを見ないため、無言で常時解放になります。「儀式エフェクト」へ切り替えてください。"
      };
    }
    if (prefix === "ritual" && recipes.includes(id) && !rituals.includes(id)) {
      return {
        correct: "recipe",
        message: "これは作業台レシピのIDです。儀式ゲート側に置くと無言で常時解放になります。"
          + "「クラフトレシピ」へ切り替えてください。"
      };
    }
    return null;
  }

  const API = {
    normalizeRecipeGateTarget,
    gateChannelMismatch,
    parseGateEffectId,
    parseDropTarget,
    isLegacyGateEffectId,
    gateEffectTypeLabel,
    isUniqueGateEffectType,
    gateEffectDuplicateKey,
    computeDuplicateGateEffectIds,
    computeDuplicateGateEffectLocations,
    resolveFeatureValueEdit
  };

  window.GATE_EFFECTS = API;
})();
