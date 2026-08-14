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
   * 全ノード横断で、一回性の解放効果が同じ設定のまま複数ノードに置かれていれば警告集合を返す。
   * ars-tier(加算型)は対象外。旧形式(未知プレフィックス)のIDは判定不能のため対象外。
   * @param {object} nodes skilltree working.nodes
   * @returns {Set<string>} 重複しているキーの集合 (gateEffectDuplicateKey と同じ形)
   */
  function computeDuplicateGateEffectIds(nodes) {
    const counts = {};
    for (const nodeId of Object.keys(nodes || {})) {
      const node = nodes[nodeId];
      const list = node && Array.isArray(node["dedicated-effects"]) ? node["dedicated-effects"] : [];
      for (const placement of list) {
        const key = gateEffectDuplicateKey(placement);
        if (key == null) continue;
        counts[key] = (counts[key] || 0) + 1;
      }
    }
    return new Set(Object.keys(counts).filter((key) => counts[key] > 1));
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

  const API = {
    parseGateEffectId,
    parseDropTarget,
    isLegacyGateEffectId,
    gateEffectTypeLabel,
    isUniqueGateEffectType,
    gateEffectDuplicateKey,
    computeDuplicateGateEffectIds,
    resolveFeatureValueEdit
  };

  window.GATE_EFFECTS = API;
})();
