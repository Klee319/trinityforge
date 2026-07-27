"use strict";

// アイテムステータス統合ビュー。
// タブ: 武器 / 防具 / ツール / 触媒・魔導書 / スレッド / 儀式エフェクト

(function () {
  const WEAPON_SKILLS = [
    ["HEAVY_WEAPONS", "重武器"],
    ["LIGHT_WEAPONS", "軽武器"],
    ["ARCHERY", "弓術"]
  ];
  const ARMOR_SKILLS = [
    ["LIGHT_ARMOR", "軽装備"],
    ["HEAVY_ARMOR", "重装備"]
  ];
  const TOOL_SKILLS = [
    ["MINING", "採掘"],
    ["DIGGING", "切削"],
    ["WOODCUTTING", "伐採"],
    ["FARMING", "農業"],
    ["FISHING", "釣り"],
    ["", "未指定(combat)"]
  ];

  window.ITEM_STATS_USE_SKILLS = {
    weapon: WEAPON_SKILLS,
    armor: ARMOR_SKILLS,
    tool: TOOL_SKILLS,
    other: [["", "未指定"]],
    catalyst: [["ARS_MAGIC", "Ars魔法"], ["", "未指定"]],
    spellbook: [["ARS_MAGIC", "Ars魔法"], ["", "未指定"]],
    thread: [["ARS_MAGIC", "Ars魔法"], ["", "未指定"]]
  };

  // NOTE: 旧 buildItemStatsHubView (統合ハブビュー) はスプリットビュー移行で削除済み。
  // このファイルは ITEM_STATS_USE_SKILLS (split-views.js / forms.js が参照) と
  // collectItemStatsCmdCollisions のために残っている。

  /** Detect MATERIAL#CMD appearing under multiple inferred categories; null CMD at most one per material. */
  window.collectItemStatsCmdCollisions = function collectItemStatsCmdCollisions(data, catLabels) {
    const items = data && data.items;
    if (!items || typeof items !== "object") return [];
    const byKey = new Map();
    const nullCmd = new Map();
    const msgs = [];
    for (const key of Object.keys(items)) {
      const hash = key.indexOf("#");
      const mat = hash >= 0 ? key.slice(0, hash) : key;
      const cmd = hash >= 0 ? key.slice(hash + 1) : null;
      const cat = (typeof window.getItemDisplayTab === "function")
        ? window.getItemDisplayTab(data, key, mat)
        : (typeof window.inferItemCategory === "function" ? window.inferItemCategory(mat) : "other");
      const label = (catLabels && catLabels[cat]) || cat;
      if (cmd == null || cmd === "") {
        if (nullCmd.has(mat)) {
          msgs.push(`バニラ(CMDなし) ${mat} が複数: ${nullCmd.get(mat)} と ${label}`);
        } else nullCmd.set(mat, label);
        continue;
      }
      const full = mat + "#" + cmd;
      if (byKey.has(full)) {
        msgs.push(`${full} が重複: ${byKey.get(full)} と ${label}`);
      } else byKey.set(full, label);
    }
    return msgs.slice(0, 20);
  };
})();
