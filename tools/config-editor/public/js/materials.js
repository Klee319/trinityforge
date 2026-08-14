"use strict";

// Material 候補の起動時フォールバック。
// 本番の正は /api/material-labels が返す materials-1.21.11.json (Paper API 1.21.11)。
// 起動直後〜API未取得時のみこの短いリストを使う。
window.MATERIALS = [
  "WOODEN_SWORD", "STONE_SWORD", "COPPER_SWORD", "IRON_SWORD", "GOLDEN_SWORD", "DIAMOND_SWORD", "NETHERITE_SWORD",
  "WOODEN_AXE", "STONE_AXE", "COPPER_AXE", "IRON_AXE", "GOLDEN_AXE", "DIAMOND_AXE", "NETHERITE_AXE",
  "WOODEN_PICKAXE", "STONE_PICKAXE", "COPPER_PICKAXE", "IRON_PICKAXE", "GOLDEN_PICKAXE", "DIAMOND_PICKAXE", "NETHERITE_PICKAXE",
  "WOODEN_SHOVEL", "STONE_SHOVEL", "COPPER_SHOVEL", "IRON_SHOVEL", "GOLDEN_SHOVEL", "DIAMOND_SHOVEL", "NETHERITE_SHOVEL",
  "WOODEN_HOE", "STONE_HOE", "COPPER_HOE", "IRON_HOE", "GOLDEN_HOE", "DIAMOND_HOE", "NETHERITE_HOE",
  "WOODEN_SPEAR", "STONE_SPEAR", "COPPER_SPEAR", "IRON_SPEAR", "GOLDEN_SPEAR", "DIAMOND_SPEAR", "NETHERITE_SPEAR",
  "BOW", "CROSSBOW", "TRIDENT", "MACE", "SHIELD", "FISHING_ROD",
  "LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS",
  "CHAINMAIL_HELMET", "CHAINMAIL_CHESTPLATE", "CHAINMAIL_LEGGINGS", "CHAINMAIL_BOOTS",
  "COPPER_HELMET", "COPPER_CHESTPLATE", "COPPER_LEGGINGS", "COPPER_BOOTS",
  "IRON_HELMET", "IRON_CHESTPLATE", "IRON_LEGGINGS", "IRON_BOOTS",
  "GOLDEN_HELMET", "GOLDEN_CHESTPLATE", "GOLDEN_LEGGINGS", "GOLDEN_BOOTS",
  "DIAMOND_HELMET", "DIAMOND_CHESTPLATE", "DIAMOND_LEGGINGS", "DIAMOND_BOOTS",
  "NETHERITE_HELMET", "NETHERITE_CHESTPLATE", "NETHERITE_LEGGINGS", "NETHERITE_BOOTS",
  "TURTLE_HELMET", "ELYTRA"
];

// 防具の見た目素材 (armors.yml material フィールド用)
window.ARMOR_LOOK_MATERIALS = ["LEATHER", "IRON", "DIAMOND", "NETHERITE", "CHAINMAIL", "GOLD", "COPPER"];

// stat候補が取得できなかった場合のフォールバック (lore.yml のキー)
// 追加ダメ(実) flat-bonus-damage は廃止 (2026-07): コーティング内部/モブ攻撃専用となり
// アイテムステの候補から外す (既存YAMLに残っていてもロスレス表示は statSelect が補う)。
window.FALLBACK_STATS = [
  "attack-power", "attack-speed", "attack-speed-bonus", "attack-reach", "aoe-radius", "aoe-damage-rate", "aoe-max-targets",
  "item-cooldown", "crit-chance", "crit-damage", "penetration",
  "percent-bonus-damage", "damage-modifier", "fixed-damage", "bleed-chance", "bleed-damage",
  "armor-defense-rate", "armor-strength", "max-health", "knockback-resistance",
  "move-speed", "phys-resistance", "magic-resistance", "phys-flat-defense", "magic-flat-defense",
  "damage-reduction", "dodge-chance",
  // 補助系ステ: 最大耐久力(fixed/per-quality/randomで設定。floorしてintの実効耐久になる)
  "durability",
  // 採集ステ (ツール/ロッドの item-stats から per-item で実効: MiningFortuneListener / FishingQualityListener)
  "mining-fortune", "fishing-luck", "fishing-bonus",
  // Ars装備ステ (ArsPaperフォークが resolveItemStats 経由で読む。lore.yml にも語彙あり)
  "mana-bonus", "mana-regen", "hit-mana-recovery", "damage-mana-recovery", "thread-slots",
  // 触媒のマナ消費軽減 (旧 mana-cost-reduction.{flat,percent} を item-stat 化。整数閾値ステ:
  // per-quality に小数を入れると加算され、整数化した分だけ実効値が上がる=切り捨て)。
  "mana-cost-reduction-flat", "mana-cost-reduction-percent",
  // スキルツリー専用の条件付き/解放バフ。アイテムステには通常設定しないが、同じバフUIから選択できる。
  // 2026-07-31: light/heavy-armor-move-speed-per-piece は語彙ごと廃止(set-buffs の move-speed へ統合)。
  "ars-tier-bonus", "glyph-slot-bonus",
  "armor-set-bonus"
];

// ステ選択(セレクトメニュー)から必ず隠すキー。
// statList() は「STAT_LIST(lore.yml 由来) ∪ FALLBACK_STATS」の和集合を候補にするので、
// lore.yml から語彙を消しただけでは配備先の古い lore.yml 経由でセレクトに出続ける。
// 「選ばせてはいけないキー」はこの1本で止める(2026-08-13 新設。以前は forms.js と
// tf-base-stats.js が各自 ["flat-defense"] をハードコードし、ars-spellbooks.js は
// 除外自体を持っていなかった)。
//  - flat-defense: 旧・単純守備力。phys-flat-defense / magic-flat-defense へ分離済み。
//  - tool-enchant-efficiency: 2026-07-26 の「効率」ステ統合で gathering-efficiency へ吸収された
//    旧綴り。StatKeys.LEGACY_KEY_ALIASES が canonical 化の時点で gathering_efficiency へ
//    読み替えるため、両方をセレクトに出すと「別項目に見えて実体は同じキー」になり、
//    同じアイテムに2つ設定すると後勝ちで片方が黙って消える。
// どちらも既存 yml に値が残っていれば statSelect がロスレス表示で補うので、値は失われない。
window.HIDDEN_STATS = ["flat-defense", "tool-enchant-efficiency"];

// 各statの既定表示フォーマット (lore.yml が読めない/キー欠落時のフォールバック)。
// PERCENT のステは item-stats フォームで % 入力 (内部は 0.0〜1.0 の割合で保存) に切り替える。
// 権威は各サーバーの lore.yml。ここは初期値・保険。
window.FALLBACK_STAT_FORMATS = {
  "attack-power": "FLAT",
  "attack-speed": "FLAT",
  "attack-speed-bonus": "PERCENT",
  "attack-reach": "FLAT",
  "aoe-radius": "FLAT",
  "aoe-damage-rate": "PERCENT",
  "aoe-max-targets": "INTEGER",
  "item-cooldown": "FLAT",
  "flat-bonus-damage": "FLAT",
  "percent-bonus-damage": "PERCENT",
  "damage-modifier": "PERCENT",
  "crit-chance": "PERCENT",
  "crit-damage": "PERCENT",
  "penetration": "PERCENT",
  "bleed-chance": "PERCENT",
  "bleed-damage": "FLAT",
  "armor-defense-rate": "FLAT",
  "armor-strength": "PERCENT",
  "max-health": "FLAT",
  "knockback-resistance": "PERCENT",
  "move-speed": "PERCENT",
  "phys-resistance": "PERCENT",
  "magic-resistance": "PERCENT",
  "phys-flat-defense": "FLAT",
  "magic-flat-defense": "FLAT",
  "fixed-damage": "FLAT",
  "damage-reduction": "PERCENT",
  "dodge-chance": "PERCENT",
  // 補助系: 最大耐久力(整数。小数点以下は切り捨て)
  "durability": "INTEGER",
  // 採集ステ。採掘運/釣り運/釣りボーナスはいずれも「追加ドロップ期待値・宝率の増加率」なので
  // 割合(PERCENT)。+15% なら期待値+0.15個(整数部は確定、小数部はその確率で+1個)。
  // ※ 権威は lore.yml。ここは lore.yml が読めないときのフォールバックなので値を一致させる
  //   (2026-07-28 まで mining-fortune / fishing-luck、2026-08-05 まで fishing-bonus が
  //    FLAT のままズレていた)。
  "mining-fortune": "PERCENT",
  "fishing-luck": "PERCENT",
  "fishing-bonus": "PERCENT",
  // 2026-08-12: INTEGER + 単位 "%" からの訂正。PercentStatNormalize が [0,1] の割合として
  // 扱うキーで、出荷 item-stats.yml も 0.004 等の割合。INTEGER のままだと %入力にならず
  // 「単位が % なのに 0.004 と小数で出る」(実機の lore も "+0%" になっていた)。
  "mana-cost-reduction-percent": "PERCENT",
  // Ars装備ステ (加算値)
  "mana-bonus": "FLAT",
  "mana-regen": "FLAT",
  "hit-mana-recovery": "FLAT",
  "damage-mana-recovery": "FLAT",
  "thread-slots": "INTEGER",
  // マナ消費軽減(実数) = 消費マナから減算する整数。整数ステとして扱い、per-quality 小数の
  // 累積を floor して実効値にする(閾値方式)。率のほうは上の PERCENT 群に移した。
  "mana-cost-reduction-flat": "INTEGER",
  // 旧・ツールエンチャレベル。2026-07-26 に gathering-efficiency へ統合済みで、セレクトには
  // 出さない(HIDDEN_STATS)。ここに残すのは、旧綴りが書かれたままの yml を開いたときに
  // statSelect のロスレス表示がフォーマット不明で壊れないようにするためだけ。新規に選べる項目ではない。
  "tool-enchant-efficiency": "INTEGER"
};

// 各statのデフォルト単位 (lore.yml の unit が読めない/キー欠落時のフォールバック)。
// PERCENT ステは自動で "%"、それ以外はここに無ければ単位なし。
// 「単位」入力はデフォルトをグレーアウト表示し、「カスタム」チェックONのときのみ自由入力。
// 2026-07-31: lore.yml に unit があるのにここに無いキーが14件あり、Lore表示設定の単位欄が
// 全部「カスタム」扱いで表示されていた(tick が既定として扱われない症状の正体)。
// test/lore-unit-and-stat-vocab-drift.test.js が lore.yml と双方向で機械照合する。
window.FALLBACK_STAT_UNITS = {
  "attack-reach": "m",
  "aoe-radius": "m",
  "aoe-max-targets": "体",
  "item-cooldown": "秒",
  "mana-regen": "/秒",
  "thread-slots": "枠",
  // 押し出し量(velocity 加算)。矢/近接で同じ単位系なので表記も m で揃える。
  "arrow-knockback": "m",
  "melee-knockback": "m",
  "power-attack-radius": "m",
  "stun-duration-bonus": "tick",
  // mana-cost-reduction-percent はここに書かない(2026-08-12)。PERCENT ステは % が自動で付くので、
  // 単位も書くと二重になる。lore.yml 側でも unit: を消してある。
  "workbench-quality-bonus": "pt",
  "ritual-quality-bonus": "pt",
  // 2026-08-05: ロール3キーはパーセントポイント記法(CraftQualityService が /100 して σ/収束へ足す)。
  // format は FLAT のまま(PERCENT にすると表示が ×100 されて 10% が 1000% になる)ので単位だけ付ける。
  "craft-roll-up-bonus": "%",
  "craft-roll-down-reduction": "%",
  "craft-roll-inset": "%",
  "potion-quality-bonus": "pt",
  // 2026-08-15: 単位の無い実数ステへ一斉に単位を付けた(ユーザー報告「守備力とかあるべきものに
  // 単位ついていないの違和感ある」)。守備力は%ではない — 防御率/耐性/被ダメ軽減の乗算より前の
  // 素の引き算なので「1ポイント = 被ダメージ1」。攻撃力と同じ「ダメ」を付けると桁の差が見て分かる。
  // 運(enchant-luck/loot-luck)と品質σ(workbench/ritual の upswing/downswing)は単位の意味が
  // 確定していないので付けていない。
  "phys-flat-defense": "ダメ",
  "magic-flat-defense": "ダメ",
  "flat-defense": "ダメ",
  "reflect-flat": "ダメ",
  "max-health": "HP",
  "armor-defense-rate": "点",
  "attack-power": "ダメ",
  "bleed-damage": "ダメ",
  "fixed-damage": "ダメ",
  "flat-bonus-damage": "ダメ",
  "attack-speed": "回/秒",
  "arrow-piercing": "体",
  "mana-bonus": "MP",
  "damage-mana-recovery": "MP",
  "hit-mana-recovery": "MP",
  "mana-cost-reduction-flat": "MP",
  "mana-idle-bonus-flat": "MP",
  "coating-charges": "回",
  "coating-charges-bonus": "回",
  "durability": "回",
  "mob-drop-quality": "pt",
  "gathering-efficiency": "Lv",
  // 2026-08-14: lapis-cost-reduction を stats/lore.yml から撤去したので、この辞書からも消す
  // (この辞書は lore.yml の unit と双方向で照合されるため、残すと死にキーとして検知される)。
  "ars-tier-bonus": "ティア",
  "glyph-slot-bonus": "枠",
  // 2026-08-13: mana-regen-base / mana-regen-interval-ticks は stats/lore.yml から撤去した
  // (combat/base-stats.yml 専用の全プレイヤー共通定数で、アイテムのロアには出ない)。
  // この辞書は lore.yml の unit と双方向で照合されるため、残すと死にキーとして検知される。
  "mana-idle-seconds": "秒"
};

// stat のデフォルト単位を解決 (lore.yml 由来 STAT_META.unit → フォールバック辞書 → PERCENT は "%")。
window.defaultStatUnit = function defaultStatUnit(key) {
  const meta = window.STAT_META && window.STAT_META[key];
  if (meta && typeof meta.unit === "string" && meta.unit) return meta.unit;
  const fmt = ((window.STAT_FORMATS && window.STAT_FORMATS[key])
    || (window.FALLBACK_STAT_FORMATS && window.FALLBACK_STAT_FORMATS[key]) || "FLAT").toUpperCase();
  if (fmt === "PERCENT") return "%";
  return (window.FALLBACK_STAT_UNITS && window.FALLBACK_STAT_UNITS[key]) || "";
};

window.BIND_TYPES = ["SOULBOUND", "TRADEABLE", "OWNER_BOUND"];
window.APPLIES_TO = ["weapon", "armor", "tool", "other"];

// Bukkit LeatherArmorMeta で染色可能な素材か (ItemCatalogConfig.parseColor と同基準: LEATHER_*)。
window.isLeatherArmorMaterial = function isLeatherArmorMaterial(material) {
  const m = String(material == null ? "" : material).trim().toUpperCase();
  return m.startsWith("LEATHER_");
};

// Material名からカテゴリ("weapon"|"armor"|"tool"|"other")を推論する (表示上のサブタブ振り分けのフォールバック)。
// `_editor.itemTabs` に明示ピンがあればそちらを優先する (斧/クワなど武器・ツール両用向け)。
// item-categories.yml の overrides とは独立の簡易ヒューリスティック (config-editor 内の表示補助であり、
// TF本体のアイテム分類ロジックの権威ではない)。
window.inferItemCategory = function inferItemCategory(material) {
  const m = String(material == null ? "" : material).trim().toUpperCase();
  if (!m) return "other";
  if (m.endsWith("_SWORD") || m.endsWith("_AXE") || m.endsWith("_SPEAR")) return "weapon";
  if (m === "BOW" || m === "CROSSBOW" || m === "TRIDENT" || m === "MACE") return "weapon";
  if (m.endsWith("_HELMET") || m.endsWith("_CHESTPLATE") || m.endsWith("_LEGGINGS") || m.endsWith("_BOOTS") || m === "ELYTRA") return "armor";
  if (m.endsWith("_PICKAXE") || m.endsWith("_SHOVEL") || m.endsWith("_HOE")) return "tool";
  if (m === "FISHING_ROD" || m === "SHEARS" || m === "FLINT_AND_STEEL") return "tool";
  return "other";
};
