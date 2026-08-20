"use strict";

// progression/special-rewards.yml / achievements.yml / collection.yml 専用フォーム。
// 設計書 2026-07-23-stat-gate-overhaul.md §6.1/§6.2/§6.3/§6.7 準拠。
// 往復ロスレス: working を直接編集。未知キー・キー順は温存する。

(function (isBrowser) {
  // ============================================================
  // 純関数 (ブラウザ非依存・Node テストから直接 require 可能)
  // ============================================================

  function uniqueKey(map, base) {
    if (!Object.prototype.hasOwnProperty.call(map, base)) return base;
    let i = 1;
    let k = base + "_" + i;
    while (Object.prototype.hasOwnProperty.call(map, k)) k = base + "_" + (++i);
    return k;
  }
  function renameKey(map, oldKey, newKey) {
    const rebuilt = {};
    for (const k of Object.keys(map)) rebuilt[k === oldKey ? newKey : k] = map[k];
    for (const k of Object.keys(map)) delete map[k];
    Object.assign(map, rebuilt);
  }

  // 累計カウンタID (trigger.type: counter)。lib/schema.js の ACHIEVEMENT_COUNTER_IDS と 1:1。
  // 実際に加算実装があるものだけを並べる ── 存在しないIDを書けるようにすると
  // 「条件を満たしようがないアチーブメント」が静かにできあがる。
  const ACHIEVEMENT_COUNTER_IDS = [
    "source_spent", "glyph_unlocked", "glyph_harm", "glyph_break", "glyph_exchange",
    "glyph_grow", "ritual_performed", "ritual_effect_used", "spell_augment_used",
    "catalyst_cast", "enchant_book_shared"
  ];

  // 2026-08-16 追加のトリガー種別。lib/schema.js の同名定数と 1:1 で保つこと。
  const ACHIEVEMENT_TRIGGER_TYPES = [
    "statistic", "advancement", "static", "counter", "gear-use", "skill-level"
  ];
  const ACHIEVEMENT_GEAR_SLOTS = ["weapon", "armor"];
  // COMBAT は「総合戦闘レベル」の擬似ID (Java 側 SkillLevelRequirement.COMBAT)。
  const ACHIEVEMENT_SKILL_LEVEL_IDS = [
    "ALCHEMY", "ARCHERY", "ARS_MAGIC", "ARS_SMITHING", "DIGGING", "ENCHANTING",
    "FARMING", "FISHING", "HEAVY_ARMOR", "HEAVY_WEAPONS", "LIGHT_ARMOR",
    "LIGHT_WEAPONS", "MINING", "POWER", "SMITHING", "WOODCUTTING", "COMBAT"
  ];

  // 特殊報酬/アチーブメント/図鑑カテゴリの共通ID規則: 半角英数字・ハイフン・アンダースコアのみ。
  const REWARD_ID_RE = /^[a-zA-Z0-9_-]+$/;
  function isValidRewardId(id) {
    return typeof id === "string" && REWARD_ID_RE.test(id.trim());
  }
  // 変更後IDの妥当性 + (自分自身以外との)一意性を検査する。戻り値: null=OK、それ以外はエラーメッセージ。
  function checkRewardIdAvailable(map, currentId, nextIdRaw) {
    const next = typeof nextIdRaw === "string" ? nextIdRaw.trim() : "";
    if (!next) return "IDを入力してください";
    if (!isValidRewardId(next)) return "IDは半角英数字・ハイフン・アンダースコアのみ使用できます";
    if (next !== currentId && Object.prototype.hasOwnProperty.call(map, next)) return "同じIDが既にあります";
    return null;
  }

  // アチーブメントの trigger を正規化する (type既定=statistic、必須フィールドの実体化)。
  function normalizeAchievementTrigger(trigger) {
    const t = trigger && typeof trigger === "object" && !Array.isArray(trigger) ? trigger : {};
    if (t.type === "collection") t.type = "static";
    if (!ACHIEVEMENT_TRIGGER_TYPES.includes(t.type)) t.type = "statistic";
    if (t.type === "statistic") {
      if (typeof t.statistic !== "string") t.statistic = "";
      // 2026-07-30: 修飾子必須の統計 (MINE_BLOCK / CRAFT_ITEM / KILL_ENTITY 等) 用。
      // 修飾子不要の統計では Java 側が無視するだけなので、ここでは値を消さずそのまま保持する
      // (統計を一時的に切り替えても書きかけの対象が消えないため)。
      if (typeof t["statistic-qualifier"] !== "string") t["statistic-qualifier"] = "";
      if (!Number.isFinite(Number(t.threshold))) t.threshold = 1;
    } else if (t.type === "advancement") {
      if (typeof t.advancement !== "string") t.advancement = "";
    } else if (t.type === "counter") {
      // 2026-07-31: 累計カウンタ型。空欄のまま保存すると Java 側がこのアチーブメントごと skip する
      // (= 書いたのに存在しない) ので、既定値を入れておく。
      if (!ACHIEVEMENT_COUNTER_IDS.includes(t.counter)) t.counter = ACHIEVEMENT_COUNTER_IDS[0];
      if (!Number.isFinite(Number(t.threshold)) || Number(t.threshold) < 1) t.threshold = 1;
    } else if (t.type === "gear-use") {
      // 2026-08-16: その装備で実際にダメージを与えたら達成。items が空だと到達不能な定義に
      // なるので、実体だけは必ず作っておく(空配列は保存時に検証で落ちる)。
      if (!t["gear-use"] || typeof t["gear-use"] !== "object") t["gear-use"] = {};
      const g = t["gear-use"];
      if (!ACHIEVEMENT_GEAR_SLOTS.includes(g.slot)) g.slot = ACHIEVEMENT_GEAR_SLOTS[0];
      g.items = (Array.isArray(g.items) ? g.items : [])
        .filter((v) => typeof v === "string" && v.trim() !== "")
        .map((v) => v.trim());
    } else if (t.type === "skill-level") {
      // 2026-08-16: skills のうち count 種類が level に達したら達成。count は skills 件数が上限。
      if (!t["skill-level"] || typeof t["skill-level"] !== "object") t["skill-level"] = {};
      const s = t["skill-level"];
      s.skills = (Array.isArray(s.skills) ? s.skills : [])
        .filter((v) => typeof v === "string" && v.trim() !== "")
        .map((v) => v.trim().toUpperCase())
        .filter((v, i, arr) => arr.indexOf(v) === i);
      const level = Number(s.level);
      s.level = Number.isFinite(level) ? Math.min(100, Math.max(1, Math.floor(level))) : 1;
      const count = Number(s.count);
      s.count = Number.isFinite(count) ? Math.max(1, Math.floor(count)) : 1;
      if (s.skills.length) s.count = Math.min(s.count, s.skills.length);
    } else if (t.type === "static") {
      if (!t.collection || typeof t.collection !== "object") t.collection = {};
      if (!["all", "category", "item", "mob"].includes(t.collection.scope)) t.collection.scope = "all";
      if (typeof t.collection.target !== "string") t.collection.target = "";
      // 2026-07-27: 複数対象 (collection.targets)。単数 target は後方互換で残し、常に targets[0] と
      // 同じ値に保つ。統合の順序は Java 側 parseCollectionTargets と厳密に同じ
      // (targets を並べ、単数 target がそこに無ければ先頭へ足す)。逆にすると
      // 「targets: [] と target: x が両方ある」既存ファイルで x を取りこぼす。
      const single = t.collection.target.trim();
      let targets = (Array.isArray(t.collection.targets) ? t.collection.targets : [])
        .filter((v) => typeof v === "string" && v.trim() !== "")
        .map((v) => v.trim())
        .filter((v, i, arr) => arr.indexOf(v) === i);
      if (single && !targets.includes(single)) targets = [single].concat(targets);
      t.collection.targets = targets;
      t.collection.target = targets[0] || "";
      // 2026-07-31: threshold 未指定の既定を Java 側 (AchievementsConfig.parseTrigger) に合わせる。
      // 以前は常に 1 を入れていたため、「targets を3つ並べて threshold を省略した = 3種そろったら達成」と
      // 書いた yml をエディタで開いて保存し直すだけで threshold: 1 が書き込まれ、
      // 「どれか1つ登録で達成」へ<b>無言で格下げ</b>されていた(条件が緩む方向なので気づきにくい)。
      // scope=item/mob かつ percent でないときだけ「列挙した件数」を既定にする ──
      // category/all は列挙数と候補数が一致しないので 1 のままにする。
      if (!Number.isFinite(Number(t.collection.threshold))) {
        const countable = !t.collection.percent
          && (t.collection.scope === "item" || t.collection.scope === "mob");
        t.collection.threshold = countable ? Math.max(1, targets.length) : 1;
      }
      t.collection.percent = !!t.collection.percent;
    }
    return t;
  }
  // 図鑑トリガの閾値を対象数へ追随させるべきか判断する (2026-07-27)。
  // 追随するなら新しい閾値、しないなら null。
  //
  // 「複数対象そろったら達成」が複数指定の主目的なので、対象を増減したら閾値も付いていくのが既定。
  // ヒント表示だけにすると追随し忘れて「どれか1つ登録で達成」に静かに劣化する。
  // ただし一度でも手で閾値を触ったらユーザーの意図とみなして追随を止める(thresholdTouched)。
  // 追随の条件を scope=item/mob かつ count 判定に限るのは、Java 側 AchievementsConfig の
  // 「threshold 省略時は targets の件数」既定と同じ範囲にそろえるため
  // (category/all は列挙数と候補数が一致しないので自動では決められない)。
  function autoCollectionThreshold(collection, thresholdTouched) {
    const c = collection && typeof collection === "object" ? collection : {};
    if (thresholdTouched || c.percent) return null;
    if (c.scope !== "item" && c.scope !== "mob") return null;
    const next = Math.max(1, (Array.isArray(c.targets) ? c.targets : []).length);
    return next === Number(c.threshold) ? null : next;
  }

  // アチーブメント/図鑑報酬 共通拡張フィールド (items[]/job-exp[]/permanent-buffs{}) を実体化する。
  // vanilla-exp は任意スカラーのため実体化しない (未設定=キー無し)。
  function normalizeRewardExtras(container) {
    const c = container && typeof container === "object" && !Array.isArray(container) ? container : {};
    if (!Array.isArray(c.items)) c.items = [];
    if (!Array.isArray(c["job-exp"])) c["job-exp"] = [];
    if (c["permanent-buffs"] == null || typeof c["permanent-buffs"] !== "object" || Array.isArray(c["permanent-buffs"])) {
      c["permanent-buffs"] = {};
    }
    return c;
  }
  // アチーブメントの rewards を正規化する (special[]/commands[]/items[]/job-exp[]/permanent-buffs{} を実体化)。
  function normalizeAchievementRewards(rewards) {
    const r = rewards && typeof rewards === "object" && !Array.isArray(rewards) ? rewards : {};
    if (!Array.isArray(r.special)) r.special = [];
    if (!Array.isArray(r.commands)) r.commands = [];
    return normalizeRewardExtras(r);
  }
  // 配列から空文字列/非文字列を取り除く (保存直前フィルタ)。
  function filterNonEmptyStrings(arr) {
    return (Array.isArray(arr) ? arr : []).filter((v) => typeof v === "string" && v.trim());
  }
  // items[]: id が空の行を除去し、amount を1以上の整数に丸める (保存直前フィルタ)。
  function filterRewardItems(arr) {
    return (Array.isArray(arr) ? arr : [])
      .filter((v) => v && typeof v === "object" && typeof v.id === "string" && v.id.trim())
      .map((v) => {
        const n = Number(v.amount);
        return { id: v.id.trim(), amount: Number.isFinite(n) && n > 0 ? Math.floor(n) : 1 };
      });
  }
  // job-exp[]: skill が空の行を除去する (保存直前フィルタ)。
  function filterJobExp(arr) {
    return (Array.isArray(arr) ? arr : [])
      .filter((v) => v && typeof v === "object" && typeof v.skill === "string" && v.skill.trim())
      .map((v) => {
        const n = Number(v.amount);
        return { skill: v.skill, amount: Number.isFinite(n) ? n : 0 };
      });
  }
  // permanent-buffs{}: 数値でない値を除去する (保存直前フィルタ)。
  function filterPermanentBuffs(map) {
    const out = {};
    if (map && typeof map === "object") {
      for (const [k, v] of Object.entries(map)) {
        if (!k) continue;
        const n = Number(v);
        if (Number.isFinite(n)) out[k] = n;
      }
    }
    return out;
  }
  // items/job-exp/permanent-buffs/vanilla-exp をまとめて保存直前フィルタする (container を直接書き換える)。
  function filterRewardExtras(container) {
    const c = container && typeof container === "object" && !Array.isArray(container) ? container : {};
    c.items = filterRewardItems(c.items);
    c["job-exp"] = filterJobExp(c["job-exp"]);
    c["permanent-buffs"] = filterPermanentBuffs(c["permanent-buffs"]);
    if (c["vanilla-exp"] == null || c["vanilla-exp"] === "") delete c["vanilla-exp"];
    return c;
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = {
      uniqueKey, renameKey,
      isValidRewardId, checkRewardIdAvailable,
      normalizeAchievementTrigger, normalizeAchievementRewards, normalizeRewardExtras,
      autoCollectionThreshold,
      filterNonEmptyStrings, filterRewardItems, filterJobExp, filterPermanentBuffs, filterRewardExtras
    };
  }
  if (!isBrowser) return;

  // ============================================================
  // DOM 部品 (ブラウザ専用)
  // ============================================================
  const h = window.h;

  function card(headChildren, bodyChildren) {
    return h("div", { class: "entry-card" }, [
      h("div", { class: "entry-head" }, headChildren),
      h("div", { class: "entry-body" }, bodyChildren)
    ]);
  }
  function field(label, control, hint) {
    const kids = [h("span", { class: "form-label", text: label }), control];
    if (hint) kids.push(h("div", { class: "field-hint", text: hint }));
    return h("div", { class: "form-field" }, kids);
  }
  function formHint(text) { return h("p", { class: "form-hint", text }); }
  function emptyHint(text) { return h("div", { class: "empty-hint", text }); }
  function ensureObj(parent, key, fallback) {
    if (parent[key] == null || typeof parent[key] !== "object" || Array.isArray(parent[key])) {
      parent[key] = fallback != null ? fallback : {};
    }
    return parent[key];
  }
  function idRenameInput(map, id, onRenamed) {
    const input = h("input", { class: "field-input", value: id, spellcheck: "false" });
    input.addEventListener("change", () => {
      const err = checkRewardIdAvailable(map, id, input.value);
      if (err) { alert(err); input.value = id; return; }
      const next = input.value.trim();
      if (next === id) return;
      renameKey(map, id, next);
      // 2026-07-29: 参照(アチーブメントの parent / parents-any)を追随させたい呼び元のため、
      // 新旧IDを渡す。既存の呼び元は引数を無視するだけなので影響しない。
      onRenamed(next, id);
    });
    return input;
  }
  function stringListEditor(arr, opts) {
    const o = opts || {};
    const box = h("div", { class: "stat-rows" });
    function render() {
      box.innerHTML = "";
      if (!arr.length) box.appendChild(emptyHint(o.empty || "まだありません。"));
      arr.forEach((val, idx) => {
        const row = h("div", { class: "stat-row" });
        row.appendChild(window.textInput(val || "", (v) => { arr[idx] = v; }, o.placeholder || ""));
        row.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => { arr.splice(idx, 1); render(); }
        }));
        box.appendChild(row);
      });
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: o.addLabel || "+ 追加",
        onclick: () => { arr.push(""); render(); }
      }));
    }
    render();
    return box;
  }

  // ------------------------------------------------------------
  // アチーブメント/図鑑 報酬拡張 (items/vanilla-exp/job-exp/permanent-buffs) 共通UI。
  // Java側 SkillId 16種。日本語ラベルはこのフォーム専用に定義する。
  // ------------------------------------------------------------
  const JOB_EXP_SKILLS = [
    ["ALCHEMY", "錬金"], ["ARCHERY", "弓術"], ["ARS_MAGIC", "魔法"], ["ARS_SMITHING", "魔法鍛冶"],
    ["DIGGING", "掘削"], ["ENCHANTING", "エンチャント"], ["FARMING", "農業"], ["FISHING", "釣り"],
    ["HEAVY_ARMOR", "重装甲"], ["HEAVY_WEAPONS", "重武器"], ["LIGHT_ARMOR", "軽装甲"],
    ["LIGHT_WEAPONS", "軽武器"], ["MINING", "採掘"], ["POWER", "パワー"], ["SMITHING", "鍛冶"],
    ["WOODCUTTING", "伐採"]
  ];
  function jobSkillSelect(value, onChange) {
    const opts = JOB_EXP_SKILLS.map(([v, ja]) => ({ value: v, primary: ja, secondary: v }));
    const cur = value || "";
    if (cur && !JOB_EXP_SKILLS.some(([v]) => v === cur)) opts.unshift({ value: cur, primary: cur, secondary: "" });
    return window.listSelect({ value: cur, options: opts, onChange, placeholder: "職業スキルを選択…" });
  }
  function statLabelOf(key) {
    if (window.LABELS && typeof window.LABELS.statLabel === "function") return window.LABELS.statLabel(key);
    return key;
  }
  function statKeySelect(value, onChange, optionKeys) {
    const keys = Array.isArray(optionKeys) ? optionKeys
      : (Array.isArray(window.STAT_LIST) ? window.STAT_LIST : []);
    const opts = keys.map((k) => ({ value: k, primary: statLabelOf(k), secondary: k }));
    const cur = value || "";
    if (cur && !keys.includes(cur)) opts.unshift({ value: cur, primary: statLabelOf(cur), secondary: cur });
    return window.listSelect({ value: cur, options: opts, onChange, placeholder: "statキーを選択…" });
  }
  // アイテムID入力: カタログ候補(渡されていれば)+バニラMaterialの listSelect。
  // 表示は「表示名 (ID)」の日本語主表示、保存値は今までどおり ID文字列そのもの(catalogID生値 or
  // Material名)。ゲート画面 (tf-dungeon-forms.js buildDungeonGatesForm) の必要鍵アイテムと同じ
  // 選択体験を共有するため、実体は util.js の window.itemRefSelect 共通ヘルパー。
  function itemIdInput(value, onChange, catalogCandidates) {
    return window.itemRefSelect({
      value: value || "",
      onChange,
      catalogCandidates,
      placeholder: "アイテムを選択…",
      customPlaceholder: "catalogID / バニラMaterial を直接入力",
      className: "reward-item-id-input"
    });
  }
  // rewards.items: id+amount の行リスト。
  function itemsRewardEditor(list, catalogCandidates) {
    const box = h("div", { class: "stat-rows" });
    function render() {
      box.innerHTML = "";
      if (!list.length) box.appendChild(emptyHint("付与アイテムがありません。"));
      list.forEach((raw, idx) => {
        const entry = raw && typeof raw === "object" ? raw : (list[idx] = { id: "", amount: 1 });
        const row = h("div", { class: "stat-row" });
        row.appendChild(itemIdInput(entry.id || "", (v) => { entry.id = v; }, catalogCandidates));
        row.appendChild(h("span", { class: "range-label", text: "個数" }));
        row.appendChild(window.numberInput(entry.amount == null ? 1 : entry.amount, (v) => {
          entry.amount = v == null ? 1 : Math.max(1, Math.floor(v));
        }, { int: true }));
        row.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => { list.splice(idx, 1); render(); }
        }));
        box.appendChild(row);
      });
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ アイテム追加",
        onclick: () => { list.push({ id: "", amount: 1 }); render(); }
      }));
    }
    render();
    return box;
  }
  // rewards.job-exp: skill+amount の行リスト。
  function jobExpEditor(list) {
    const box = h("div", { class: "stat-rows" });
    function render() {
      box.innerHTML = "";
      if (!list.length) box.appendChild(emptyHint("職業経験値がありません。"));
      list.forEach((raw, idx) => {
        const entry = raw && typeof raw === "object" ? raw : (list[idx] = { skill: "MINING", amount: 100 });
        const row = h("div", { class: "stat-row" });
        row.appendChild(jobSkillSelect(entry.skill, (v) => { entry.skill = v; }));
        row.appendChild(window.numberInput(entry.amount == null ? 0 : entry.amount, (v) => {
          entry.amount = v == null ? 0 : v;
        }));
        row.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => { list.splice(idx, 1); render(); }
        }));
        box.appendChild(row);
      });
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 職業EXP追加",
        onclick: () => { list.push({ skill: "MINING", amount: 100 }); render(); }
      }));
    }
    render();
    return box;
  }
  // rewards.permanent-buffs: statキー -> 数値 のマップ行リスト。
  function permanentBuffsEditor(map) {
    const box = h("div", { class: "stat-rows" });
    function render() {
      box.innerHTML = "";
      const keys = Object.keys(map);
      if (!keys.length) box.appendChild(emptyHint("永続バフがありません。"));
      keys.forEach((key) => {
        const row = h("div", { class: "stat-row" });
        row.appendChild(statKeySelect(key, (nextKey) => {
          if (!nextKey || nextKey === key || Object.prototype.hasOwnProperty.call(map, nextKey)) return;
          const v = map[key];
          delete map[key];
          map[nextKey] = v;
          render();
        }));
        row.appendChild(window.numberInput(map[key], (v) => { map[key] = v == null ? 0 : v; }));
        row.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => { delete map[key]; render(); }
        }));
        box.appendChild(row);
      });
      const statList = Array.isArray(window.STAT_LIST) ? window.STAT_LIST : [];
      const available = statList.filter((s) => !Object.prototype.hasOwnProperty.call(map, s));
      if (available.length) {
        const addRow = h("div", { class: "stat-row" });
        addRow.appendChild(statKeySelect("", (v) => {
          if (v && !Object.prototype.hasOwnProperty.call(map, v)) { map[v] = 0; render(); }
        }, available));
        box.appendChild(addRow);
      } else if (statList.length) {
        box.appendChild(h("div", { class: "field-hint", text: "全statキーを割り当て済みです。" }));
      } else {
        box.appendChild(h("div", { class: "field-hint", text: "statキー一覧が未読込です。" }));
      }
    }
    render();
    return box;
  }
  // rewards/tier に共通する拡張フィールド (items/vanilla-exp/job-exp/permanent-buffs) の form-field 群を返す。
  // container は事前に normalizeRewardExtras() 済みであること。
  function buildRewardExtrasFields(container, catalogCandidates) {
    return [
      h("div", { class: "form-field" }, [
        h("span", { class: "form-label", text: "付与アイテム (items)" }),
        itemsRewardEditor(container.items, catalogCandidates)
      ]),
      field("バニラ経験値 (vanilla-exp)", window.numberInput(
        container["vanilla-exp"] == null ? "" : container["vanilla-exp"],
        (v) => {
          if (v == null) { delete container["vanilla-exp"]; return; }
          container["vanilla-exp"] = Math.max(0, Math.floor(v));
        },
        { int: true }
      ), "空欄のまま=未設定"),
      h("div", { class: "form-field" }, [
        h("span", { class: "form-label", text: "職業経験値 (job-exp)" }),
        jobExpEditor(container["job-exp"])
      ]),
      h("div", { class: "form-field" }, [
        h("span", { class: "form-label", text: "永続ステータスバフ (permanent-buffs)" }),
        permanentBuffsEditor(container["permanent-buffs"])
      ])
    ];
  }

  // ============================================================
  // 1. special-rewards.yml
  // ============================================================
  // 1.21.11の全パーティクル(vocab-1.21.11.jsで定義済み)。未読込時は最小限のフォールバックを使う。
  const PARTICLE_OPTIONS_FALLBACK = [
    ["FLAME", "炎"], ["SOUL_FIRE_FLAME", "魂の炎"], ["HEART", "ハート"],
    ["HAPPY_VILLAGER", "幸せ(緑キラキラ)"], ["CRIT", "クリティカル"], ["ENCHANT", "エンチャント文字"],
    ["PORTAL", "ポータル"], ["END_ROD", "エンドロッド"], ["GLOW", "発光"], ["WAX_ON", "蝋引き"],
    ["ELECTRIC_SPARK", "電気火花"], ["SNOWFLAKE", "雪片"], ["CHERRY_LEAVES", "桜の花びら"],
    ["COMPOSTER", "たい肥"], ["DRIPPING_HONEY", "蜂蜜滴り"], ["FIREWORK", "花火"],
    ["NOTE", "音符"], ["SMOKE", "煙"], ["CAMPFIRE_COSY_SMOKE", "焚き火の煙"],
    ["WITCH", "魔女"], ["DRAGON_BREATH", "ドラゴンブレス"], ["SONIC_BOOM", "ソニックブーム"]
  ];
  const PARTICLE_OPTIONS = Array.isArray(window.VANILLA_PARTICLES) && window.VANILLA_PARTICLES.length
    ? window.VANILLA_PARTICLES.map((id) => [id, (window.PARTICLE_LABELS_JA && window.PARTICLE_LABELS_JA[id]) || id])
    : PARTICLE_OPTIONS_FALLBACK;
  function particleSelect(value, onChange) {
    const opts = PARTICLE_OPTIONS.map(([v, ja]) => ({ value: v, primary: ja, secondary: v }));
    const cur = value || "";
    if (cur && !PARTICLE_OPTIONS.some(([v]) => v === cur)) {
      opts.unshift({ value: cur, primary: cur, secondary: "" });
    }
    opts.push({ value: "__custom__", primary: "＋ 自由入力…" });
    return window.listSelect({
      value: cur, options: opts, onChange, allowCustom: true,
      customPlaceholder: "Bukkit Particle名を入力", placeholder: "パーティクルを選択…"
    });
  }
  const SHAPE_LABELS = { circle: "円形散布", aura: "まとわりつく" };
  function shapeSelect(value, onChange) {
    return window.listSelect({
      value: SHAPE_LABELS[value] ? value : "circle",
      options: Object.entries(SHAPE_LABELS).map(([v, ja]) => ({ value: v, primary: ja, secondary: v })),
      onChange
    });
  }

  // ------------------------------------------------------------
  // rewards.special の候補表示 (2026-07-29)。
  // special-rewards.yml のIDは new_title / new_particle のような機械名なので、
  // 生IDのまま並べると何を選んでいるのか読めなかった。app.js の loadSpecialRewards が作る
  // ラベル辞書 { id: {kind, name} } を使って「称号: 見習い」の形で日本語表示する。
  // 辞書が無い(単体テスト等)場合は従来どおりIDを出す。
  // ------------------------------------------------------------
  function specialRewardLabelOf(labels, id) {
    const meta = labels && typeof labels === "object" ? labels[id] : null;
    if (!meta) return id;
    const name = meta.name == null ? "" : String(meta.name).trim();
    if (!meta.kind) return name || id;
    return name ? meta.kind + ": " + name : meta.kind + ": " + id;
  }
  // 割り当て済み1件分の行 (日本語ラベル + 小さくID)。
  function specialRewardAssignedRow(labels, id, onRemove) {
    return h("div", { class: "stat-row" }, [
      h("span", { class: "range-label", text: specialRewardLabelOf(labels, id), title: id }),
      h("span", { class: "field-hint", text: id }),
      h("button", { class: "btn-small danger", type: "button", text: "×", onclick: onRemove })
    ]);
  }
  // 追加用セレクト (未割り当てのIDだけを日本語表示で並べる)。
  function specialRewardAddSelect(available, labels, onPick) {
    return window.listSelect({
      value: "",
      options: available.map((id) => ({
        value: id, primary: specialRewardLabelOf(labels, id), secondary: id
      })),
      placeholder: "＋ 特殊報酬を追加…",
      onChange: (v) => { if (v) onPick(v); }
    });
  }

  window.buildSpecialRewardsForm = function buildSpecialRewardsForm(data) {
    const working = data && typeof data === "object" ? data : {};
    ensureObj(working, "titles", {});
    ensureObj(working, "particles", {});
    ensureObj(working, "particle-seeds", {});

    const root = h("div", { class: "dedicated-form" });
    root.appendChild(formHint(
      "称号/パーティクル/パーティクルシードを定義します。ここで作ったIDはスキルツリー(reward:<id>)・図鑑・"
      + "アチーブメントの報酬から共通で参照できます。"
    ));

    function renderTitles() {
      const titles = working.titles;
      const list = h("div", { class: "cf-mat-list" });
      const ids = Object.keys(titles);
      if (!ids.length) list.appendChild(emptyHint("称号がありません。"));
      for (const id of ids) {
        const entry = titles[id] && typeof titles[id] === "object" ? titles[id] : (titles[id] = {});
        const c = h("div", { class: "cf-mat-card" });
        c.appendChild(h("div", { class: "cf-mat-card-head" }, [
          h("span", { class: "entry-key-label", text: "称号ID" }),
          idRenameInput(titles, id, renderTitles),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { delete titles[id]; renderTitles(); }
          })
        ]));
        c.appendChild(field("表示名 (MiniMessage)", window.richTextInput(entry.display || "", "minimessage", (v) => {
          entry.display = v;
        })));
        list.appendChild(c);
      }
      list.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 称号を追加",
        onclick: () => { titles[uniqueKey(titles, "new_title")] = { display: "新しい称号" }; renderTitles(); }
      }));
      titlesBody.innerHTML = "";
      titlesBody.appendChild(list);
    }

    function renderParticles() {
      const particles = working.particles;
      const list = h("div", { class: "cf-mat-list" });
      const ids = Object.keys(particles);
      if (!ids.length) list.appendChild(emptyHint("パーティクルがありません。"));
      for (const id of ids) {
        const entry = particles[id] && typeof particles[id] === "object" ? particles[id] : (particles[id] = {});
        if (entry.count == null) entry.count = 8;
        if (entry.radius == null) entry.radius = 0.6;
        if (entry["interval-ticks"] == null) entry["interval-ticks"] = 10;
        if (!entry.shape) entry.shape = "circle";
        const c = h("div", { class: "cf-mat-card" });
        c.appendChild(h("div", { class: "cf-mat-card-head" }, [
          h("span", { class: "entry-key-label", text: "パーティクルID" }),
          idRenameInput(particles, id, renderParticles),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { delete particles[id]; renderParticles(); }
          })
        ]));
        c.appendChild(field("particle", particleSelect(entry.particle, (v) => { entry.particle = v; })));
        const row = h("div", { class: "stat-row" });
        row.appendChild(h("span", { class: "range-label", text: "count" }));
        row.appendChild(window.numberInput(entry.count, (v) => { if (v != null) entry.count = Math.max(0, Math.floor(v)); }, { int: true }));
        row.appendChild(h("span", { class: "range-label", text: "radius" }));
        row.appendChild(window.numberInput(entry.radius, (v) => { if (v != null) entry.radius = Math.max(0, v); }));
        row.appendChild(h("span", { class: "range-label", text: "interval-ticks" }));
        row.appendChild(window.numberInput(entry["interval-ticks"], (v) => { if (v != null) entry["interval-ticks"] = Math.max(0, Math.floor(v)); }, { int: true }));
        c.appendChild(h("div", { class: "form-field" }, [h("span", { class: "form-label", text: "発生パラメータ" }), row]));
        c.appendChild(field("形状 (shape)", shapeSelect(entry.shape, (v) => { entry.shape = v; })));
        list.appendChild(c);
      }
      list.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ パーティクルを追加",
        onclick: () => {
          particles[uniqueKey(particles, "new_particle")] = {
            particle: "FLAME", count: 8, radius: 0.6, "interval-ticks": 10, shape: "circle"
          };
          renderParticles();
        }
      }));
      particlesBody.innerHTML = "";
      particlesBody.appendChild(list);
    }

    function renderSeeds() {
      const seeds = working["particle-seeds"];
      const list = h("div", { class: "cf-mat-list" });
      const ids = Object.keys(seeds);
      if (!ids.length) list.appendChild(emptyHint("パーティクルシードがありません。"));
      for (const id of ids) {
        const entry = seeds[id] && typeof seeds[id] === "object" ? seeds[id] : (seeds[id] = {});
        if (entry.count == null) entry.count = 4;
        const c = h("div", { class: "cf-mat-card" });
        c.appendChild(h("div", { class: "cf-mat-card-head" }, [
          h("span", { class: "entry-key-label", text: "シードID" }),
          idRenameInput(seeds, id, renderSeeds),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { delete seeds[id]; renderSeeds(); }
          })
        ]));
        c.appendChild(field("seed-item", window.materialInput(entry["seed-item"] || "", "material-list", (v) => {
          entry["seed-item"] = v;
        }, { allowCustom: true })));
        c.appendChild(field("particle", particleSelect(entry.particle, (v) => { entry.particle = v; })));
        c.appendChild(field("count", window.numberInput(entry.count, (v) => {
          if (v != null) entry.count = Math.max(0, Math.floor(v));
        }, { int: true })));
        list.appendChild(c);
      }
      list.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ シードを追加",
        onclick: () => {
          seeds[uniqueKey(seeds, "new_seed")] = { "seed-item": "", particle: "CRIT", count: 4 };
          renderSeeds();
        }
      }));
      seedsBody.innerHTML = "";
      seedsBody.appendChild(list);
    }

    const titlesBody = h("div");
    const particlesBody = h("div");
    const seedsBody = h("div");
    // 2026-07-27: display の余白設定だけが専用GUIから漏れていて、yml を直接触るしかなかった。
    // 値を触らないまま保存しても消えはしない(working をそのまま返す往復ロスレス方式)が、
    // 「GUIから編集できない設定」が1つ残るのでここへ出す。
    // 2026-08-03: キーを head-offset-y から nametag-clearance へ変更。旧キーは「パッセンジャーの
    // マウント点からの相対値」で、マウント点の実高さ(1.35)を知らないと正しい値を出せず、
    // 0.35 も 0.75 もネームタグ(2.3)に届かず重なって名前を隠していた。新キーは
    // 「ネームタグの上端からさらに空ける余白」なので、負でない限り必ずネームタグより上に出る。
    const display = ensureObj(working, "display", {});
    // 2026-07-29: 全体設定2枚 + 称号/パーティクル/シードの一覧3枚が縦積みで、下のシードを
    // 直すたびに全部スクロールしていた。タブに割って1画面1関心にする。
    const generalPane = h("div");
    const titlesPane = h("div");
    const particlesPane = h("div");
    const seedsPane = h("div");
    generalPane.appendChild(card(
      [h("span", { class: "entry-key-label", text: "称号の頭上表示 (display)" })],
      [h("div", { class: "field-grid" }, [
        field("ネームタグとの余白 (ブロック)", window.numberInput(
          display["nametag-clearance"] == null ? 0.4 : display["nametag-clearance"],
          (v) => { display["nametag-clearance"] = v == null ? 0 : v; }
        ), "称号の行を、バニラのネームタグ(プレイヤー名)の上端からさらに何ブロック上に置くか。"
          + "0 でネームタグの真上に接し、大きくするほど離れる。0未満は既定値(0.4)に戻される — "
          + "負にすると名前に重なって隠すため。/trinityforge reload で反映。")
      ])]
    ));
    generalPane.appendChild(card(
      [h("span", { class: "entry-key-label", text: "孤児化した付与分の自動剥奪 (prune-orphaned-grants)" })],
      [h("div", { class: "field-grid" }, [
        field("有効にする", window.checkboxInput(working["prune-orphaned-grants"] !== false, (v) => {
          working["prune-orphaned-grants"] = !!v;
        }), "true(既定)にすると、このファイルから削除した報酬IDを、プレイヤーの保持分"
          + "(直接付与リスト/装備中の称号・パーティクル)からも参加時 + /trinityforge reload 時に自動で取り除く。"
          + "安全弁: このファイルの読み込みに失敗した回(YAML構文エラー等)は、剥奪処理そのものを"
          + "自動でスキップする(壊れた設定を「全部未定義」と誤判定して全員の報酬を消し飛ばす事故を防ぐため)。")
      ])]
    ));
    titlesPane.appendChild(card([h("span", { class: "entry-key-label", text: "称号 (titles)" })], [titlesBody]));
    particlesPane.appendChild(card([h("span", { class: "entry-key-label", text: "パーティクル (particles)" })], [particlesBody]));
    seedsPane.appendChild(card([h("span", { class: "entry-key-label", text: "パーティクルシード (particle-seeds)" })], [seedsBody]));

    renderTitles();
    renderParticles();
    renderSeeds();

    const SPECIAL_TABS = [
      { id: "titles", label: "称号", pane: titlesPane },
      { id: "particles", label: "パーティクル", pane: particlesPane },
      { id: "seeds", label: "パーティクルシード", pane: seedsPane },
      { id: "general", label: "全体設定", pane: generalPane }
    ];
    let activeSpecialTab = "titles";
    const specialTabs = h("div", { class: "recipe-tabs cf-tabs", role: "tablist" });
    const specialBody = h("div", { class: "cf-body" });
    root.appendChild(specialTabs);
    root.appendChild(specialBody);
    function renderSpecialTabs() {
      specialTabs.innerHTML = "";
      for (const tab of SPECIAL_TABS) {
        specialTabs.appendChild(h("button", {
          class: "recipe-tab" + (activeSpecialTab === tab.id ? " active" : ""),
          type: "button", role: "tab", "aria-selected": activeSpecialTab === tab.id ? "true" : "false",
          onclick: () => { activeSpecialTab = tab.id; renderSpecialTabs(); renderSpecialBody(); }
        }, [h("span", { text: tab.label })]));
      }
    }
    function renderSpecialBody() {
      specialBody.innerHTML = "";
      const tab = SPECIAL_TABS.find((t) => t.id === activeSpecialTab) || SPECIAL_TABS[0];
      specialBody.appendChild(tab.pane);
    }
    renderSpecialTabs();
    renderSpecialBody();

    return { element: root, getData: () => working };
  };

  // ============================================================
  // 2. achievements.yml (左: 一覧select / 右: 編集フォームの2ペイン)
  // ============================================================
  const STATISTIC_OPTIONS = [
    ["JUMP", "ジャンプ回数"], ["WALK_ONE_CM", "歩行距離(cm)"], ["SPRINT_ONE_CM", "走行距離(cm)"],
    ["SWIM_ONE_CM", "泳いだ距離(cm)"], ["FLY_ONE_CM", "飛行距離(cm)"], ["PLAY_ONE_MINUTE", "プレイ時間(tick)"],
    ["MOB_KILLS", "モブ討伐数"], ["PLAYER_KILLS", "プレイヤー討伐数"], ["DEATHS", "死亡回数"],
    ["FISH_CAUGHT", "釣り上げ数"], ["ANIMALS_BRED", "繁殖回数"], ["DAMAGE_DEALT", "与えたダメージ"],
    ["DAMAGE_TAKEN", "受けたダメージ"], ["ITEM_ENCHANTED", "エンチャント回数"],
    ["TRADED_WITH_VILLAGER", "村人取引回数"], ["SLEEP_IN_BED", "就寝回数"], ["RAID_WIN", "襲撃勝利回数"]
  ];
  // 2026-07-30: 修飾子(qualifier)が必要な統計。Bukkit の Statistic.Type が UNTYPED 以外のものは
  // Player#getStatistic(Statistic) 単体では読めず、Material / EntityType を添える必要がある。
  // Java 側は trigger.statistic-qualifier で受ける (AchievementsConfig.StatisticQualifier)。
  // 3 番目の要素は必要な修飾子の種類 (block = ブロックMaterial / item = アイテムMaterial / entity = EntityType)。
  const QUALIFIED_STATISTIC_OPTIONS = [
    ["MINE_BLOCK", "指定ブロックの採掘数", "block"],
    ["CRAFT_ITEM", "指定アイテムのクラフト数", "item"],
    ["USE_ITEM", "指定アイテムの使用回数", "item"],
    ["BREAK_ITEM", "指定アイテムの破損回数", "item"],
    ["PICKUP", "指定アイテムの拾得数", "item"],
    ["DROP", "指定アイテムの投棄数", "item"],
    ["KILL_ENTITY", "指定モブの討伐数", "entity"],
    ["ENTITY_KILLED_BY", "指定モブに倒された回数", "entity"]
  ];
  /** その統計が要求する修飾子の種類。UNTYPED(修飾子不要)なら空文字。 */
  function statisticQualifierKind(statistic) {
    const hit = QUALIFIED_STATISTIC_OPTIONS.find(([v]) => v === statistic);
    return hit ? hit[2] : "";
  }
  // バニラ進捗キーのセレクト (2026-07-29)。従来は自由入力だけで、タイポすると
  // 永久に達成できない定義が無警告で作れた。候補は vocab-1.21.11.js の主要進捗。
  // 網羅ではないので allowCustom は残す (データパック進捗も書けるようにするため)。
  function advancementSelect(value, onChange) {
    const ids = Array.isArray(window.VANILLA_ADVANCEMENTS) ? window.VANILLA_ADVANCEMENTS : [];
    const ja = window.ADVANCEMENT_LABELS_JA || {};
    const opts = ids.map((id) => ({ value: id, primary: ja[id] || id, secondary: id }));
    const cur = value || "";
    if (cur && !ids.includes(cur)) opts.unshift({ value: cur, primary: cur, secondary: "候補外" });
    opts.push({ value: "__custom__", primary: "＋ 自由入力…" });
    return window.listSelect({
      value: cur, options: opts, onChange, allowCustom: true,
      customPlaceholder: "minecraft:story/mine_diamond",
      placeholder: "進捗を選択…"
    });
  }
  function statisticSelect(value, onChange) {
    const opts = STATISTIC_OPTIONS.map(([v, ja]) => ({ value: v, primary: ja, secondary: v }));
    // 修飾子必須の統計も候補に出す(2026-07-30 に Java 側が対応したので書けるようになった)。
    // secondary に「要・対象指定」と添えて、選ぶと下に対象欄が増えることを示す。
    for (const [v, ja] of QUALIFIED_STATISTIC_OPTIONS) {
      opts.push({ value: v, primary: ja, secondary: v + " (要・対象指定)" });
    }
    const cur = value || "";
    const known = STATISTIC_OPTIONS.some(([v]) => v === cur) || QUALIFIED_STATISTIC_OPTIONS.some(([v]) => v === cur);
    if (cur && !known) opts.unshift({ value: cur, primary: cur, secondary: "" });
    opts.push({ value: "__custom__", primary: "＋ 自由入力…" });
    return window.listSelect({
      value: cur, options: opts, onChange, allowCustom: true,
      customPlaceholder: "Bukkit Statistic名を入力", placeholder: "統計を選択…"
    });
  }
  /**
   * statistic-qualifier の選択欄。kind に応じて候補の語彙を切り替える。
   * block/item は Material、entity は EntityType。Java 側は BLOCK 型に非ブロック Material を
   * 書くとそのアチーブメントごと skip するので、種類を混ぜないことが重要。
   */
  function statisticQualifierSelect(kind, value, onChange) {
    const cur = value || "";
    let opts;
    if (kind === "entity") {
      opts = ENTITY_TYPE_CANDIDATES.map((id) => ({
        value: id, primary: (window.MOB_LABELS_JA && window.MOB_LABELS_JA[id]) || id, secondary: id
      }));
    } else {
      const materials = Array.isArray(window.MATERIALS) ? window.MATERIALS : [];
      opts = materials.map((mat) => {
        const ja = window.LABELS && typeof window.LABELS.materialLabel === "function"
          ? window.LABELS.materialLabel(mat) : "";
        return { value: mat, primary: ja || mat, secondary: mat };
      });
    }
    if (cur && !opts.some((o) => o.value === cur)) opts.unshift({ value: cur, primary: cur, secondary: "候補外" });
    opts.push({ value: "__custom__", primary: "＋ 自由入力…" });
    return window.listSelect({
      value: cur, options: opts, onChange, allowCustom: true,
      customPlaceholder: kind === "entity" ? "EntityType名を入力" : "Material名を入力",
      placeholder: kind === "entity" ? "モブを選択…" : "アイテム/ブロックを選択…"
    });
  }

  // ---- 前提・分岐 (2026-07-29) ----------------------------------------------
  // achievements.yml の parent / parents-any / coords をスキルツリーと同じ感覚で編集する。
  // 前提は「達成そのものを縛る」(ユーザー確定方針)ので、ここを間違えると永久に取れない
  // 定義ができる。自分自身の指定と循環はUIの段階で拒否する。
  function achievementNodeLabel(achievements, id) {
    const entry = achievements[id];
    const name = entry && typeof entry === "object" ? entry["display-name"] : null;
    return name && String(name).trim() ? String(name) : id;
  }
  /** from から parent 鎖をたどって target に到達するか(循環検出)。 */
  function reachesViaParent(achievements, from, target) {
    const seen = new Set();
    let cur = from;
    while (cur && !seen.has(cur)) {
      if (cur === target) return true;
      seen.add(cur);
      const entry = achievements[cur];
      cur = entry && typeof entry === "object" && typeof entry.parent === "string"
        ? entry.parent.trim() : null;
    }
    return false;
  }
  /** parent 鎖の深さ(起点=0)。循環・不明IDは 0 扱いで止める(一覧描画を落とさない)。 */
  function prerequisiteDepth(achievements, id) {
    const seen = new Set();
    let depth = 0;
    let cur = id;
    while (cur && !seen.has(cur)) {
      seen.add(cur);
      const entry = achievements[cur];
      const parent = entry && typeof entry === "object" && typeof entry.parent === "string"
        ? entry.parent.trim() : "";
      if (!parent || !achievements[parent]) return depth;
      depth += 1;
      cur = parent;
    }
    return depth;
  }
  const GATE_NONE = "__none__";
  // ヘッドレステスト(window スタブ)では alert が無いので握りつぶす。拒否そのものは戻り値で行う。
  function gateAlert(message) {
    if (typeof window !== "undefined" && typeof window.alert === "function") window.alert(message);
  }
  /** ID改名/削除に前提参照を追随させる。newId=null なら参照を落とす。純関数ではなく in-place。 */
  function remapPrerequisiteIds(achievements, oldId, newId) {
    if (!oldId) return;
    for (const entry of Object.values(achievements)) {
      if (!entry || typeof entry !== "object") continue;
      if (typeof entry.parent === "string" && entry.parent.trim() === oldId) {
        if (newId) entry.parent = newId; else delete entry.parent;
      }
      if (Array.isArray(entry["parents-any"])) {
        entry["parents-any"] = entry["parents-any"]
          .map((v) => (typeof v === "string" && v.trim() === oldId ? newId : v))
          .filter((v) => typeof v === "string" && v.trim() !== "");
      }
    }
  }
  function buildGateFields(entry, achievements, selfId, rerender) {
    const out = [];
    const others = Object.keys(achievements).filter((id) => id !== selfId);
    const optionOf = (id) => ({ value: id, primary: achievementNodeLabel(achievements, id), secondary: id });

    const parent = typeof entry.parent === "string" ? entry.parent.trim() : "";
    const parentOptions = [{ value: GATE_NONE, primary: "(前提なし・起点にする)", secondary: "" }]
      .concat(others.map(optionOf));
    if (parent && !others.includes(parent)) {
      parentOptions.push({ value: parent, primary: parent, secondary: "存在しないID" });
    }
    out.push(field("前提アチーブメント (parent)", window.listSelect({
      value: parent || GATE_NONE,
      options: parentOptions,
      placeholder: "前提を選択…",
      onCommit: (v) => {
        const next = v === GATE_NONE ? "" : String(v || "");
        if (next && reachesViaParent(achievements, next, selfId)) {
          gateAlert("循環しています: " + achievementNodeLabel(achievements, next)
            + " は(親をたどると)このアチーブメント自身に戻ります。");
          return false;
        }
        if (next) entry.parent = next; else delete entry.parent;
        rerender();
        return true;
      }
    }), "これを達成するまで、条件を満たしてもこのアチーブメントは達成になりません(報酬も出ません)。"));

    if (!Array.isArray(entry["parents-any"])) {
      if (entry["parents-any"] == null) entry["parents-any"] = [];
      else entry["parents-any"] = [];
    }
    const anyList = entry["parents-any"];
    const anyBox = h("div", { class: "stat-rows" });
    function renderAny() {
      anyBox.innerHTML = "";
      if (!anyList.length) anyBox.appendChild(emptyHint("分岐前提はありません。"));
      anyList.forEach((id, idx) => {
        const row = h("div", { class: "stat-row" });
        row.appendChild(h("span", {
          class: "range-label",
          text: achievementNodeLabel(achievements, id) + (achievements[id] ? "" : "(存在しないID)")
        }));
        row.appendChild(h("span", { class: "nav-badge", text: id }));
        row.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "×", title: "この前提を外す",
          onclick: () => { anyList.splice(idx, 1); renderAny(); }
        }));
        anyBox.appendChild(row);
      });
      const pool = others.filter((id) => !anyList.includes(id));
      if (pool.length) {
        const addRow = h("div", { class: "stat-row" });
        addRow.appendChild(window.listSelect({
          value: "", options: pool.map(optionOf), placeholder: "＋ 分岐前提を追加…",
          onCommit: (v) => {
            const next = String(v || "");
            if (!next || anyList.includes(next)) return false;
            if (reachesViaParent(achievements, next, selfId)) {
              gateAlert("循環しています: " + achievementNodeLabel(achievements, next)
                + " は(親をたどると)このアチーブメント自身に戻ります。");
              return false;
            }
            anyList.push(next);
            renderAny();
            return true;
          }
        }));
        anyBox.appendChild(addRow);
      }
    }
    renderAny();
    out.push(h("div", { class: "form-field" }, [
      h("span", { class: "form-label", text: "分岐前提 (parents-any)" }),
      anyBox,
      h("div", {
        class: "field-hint",
        text: "「いずれか1つを達成していれば挑戦できる」合流点を作ります。parent と併記した場合も"
          + "「どれか1つ」で開きます(AND ではありません)。"
      })
    ]));

    if (typeof entry.coords !== "string") entry.coords = "";
    out.push(field("GUI座標 (coords)", window.textInput(entry.coords, (v) => {
      entry.coords = typeof v === "string" ? v.trim() : "";
    }, "例: 4,2"), "/achievement のGUIでの位置を \"x,y\" で固定します(yは下向きに増加)。"
      + "空欄なら parent の関係から自動配置します。まずは空欄のままで構いません。"));

    return out;
  }

  window.buildAchievementsForm = function buildAchievementsForm(data, options) {
    const opts = options && typeof options === "object" ? options : {};
    const specialRewardIds = Array.isArray(opts.specialRewardIds) ? opts.specialRewardIds : [];
    const specialRewardLabels = opts.specialRewardLabels && typeof opts.specialRewardLabels === "object"
      ? opts.specialRewardLabels : {};
    const collectionData = opts.collectionData && typeof opts.collectionData === "object" ? opts.collectionData : {};
    const working = data && typeof data === "object" ? data : {};
    ensureObj(working, "achievements", {});
    const achievements = working.achievements;

    const root = h("div", { class: "dedicated-form achievement-form" });

    // vanilla-advancements (2026-07-28): サーバ側でバニラ進捗(advancement)解除自体を止める設定。
    const vanillaAdv = ensureObj(working, "vanilla-advancements", {});
    if (!Array.isArray(vanillaAdv.keep)) vanillaAdv.keep = [];
    const vanillaPane = h("div");
    vanillaPane.appendChild(card(
      [h("span", { class: "entry-key-label", text: "バニラ進捗の解除抑止 (vanilla-advancements)" })],
      [h("div", { class: "field-grid" }, [
        field("バニラ進捗解除を止める (disabled)", window.checkboxInput(vanillaAdv.disabled !== false, (v) => {
          vanillaAdv.disabled = !!v;
        }), "true(既定)でバニラ進捗の解除(右上トースト・進捗画面の達成)をサーバ側でキャンセルする。"
          + "対象は minecraft: 名前空間の進捗だけで、データパック/他プラグインの進捗は巻き込まない。"),
        field("レシピ進捗だけは通す (keep-recipe-advancements)",
          window.checkboxInput(vanillaAdv["keep-recipe-advancements"] !== false, (v) => {
            vanillaAdv["keep-recipe-advancements"] = !!v;
          }), "true(既定・推奨)で minecraft:recipes/ 配下の進捗だけは解除を通す。バニラはこの隠し進捗で"
          + "レシピ本の解禁を配っているため、false にすると新しいレシピが一切解放されなくなる。")
      ])]
      .concat([field("追加で通す進捗キー (keep, 前方一致)",
        stringListEditor(vanillaAdv.keep, {
          placeholder: "例: minecraft:story/", addLabel: "+ 追加",
          empty: "追加の除外はありません。"
        }),
        "上記以外で解除を通したい進捗キーの前方一致リスト(namespace:path形式、例: \"minecraft:story/\")。")])
    ));
    vanillaPane.appendChild(formHint(
      "相互作用の注意: trigger.type: advancement のTFアチーブメントは、disabled=true にすると"
      + "PlayerAdvancementCriterionGrantEvent自体がキャンセルされて永久に達成不能になる"
      + "(type: advancementの定義が1件以上あるのにdisabled=trueだと起動時にコンソールへ警告が出る)。"
    ));

    const layout = h("div", { class: "achievement-layout" });
    const listPane = h("div", { class: "achievement-list-pane" });
    const detailPane = h("div", { class: "achievement-detail-pane" });
    layout.appendChild(listPane);
    layout.appendChild(detailPane);

    // 2026-07-29: 1画面に「バニラ進捗の抑止」「一覧」「1件の全設定」が縦積みで、アチーブメントを
    // 1つ直すたびに長距離スクロールしていた。上段タブで別画面に割り、詳細側も節タブに割る。
    const listTop = h("div");
    listTop.appendChild(formHint(
      "左の一覧からアチーブメントを選び、右側のタブで基本情報・条件・前提・報酬を設定します。"
    ));
    listTop.appendChild(layout);
    const ACHIEVEMENT_TABS = [
      { id: "list", label: "アチーブメント", pane: listTop },
      { id: "vanilla", label: "バニラ進捗の抑止", pane: vanillaPane }
    ];
    let activeTab = "list";
    const tabsEl = h("div", { class: "recipe-tabs cf-tabs", role: "tablist" });
    const tabBody = h("div", { class: "cf-body" });
    root.appendChild(tabsEl);
    root.appendChild(tabBody);
    function renderTabs() {
      tabsEl.innerHTML = "";
      for (const tab of ACHIEVEMENT_TABS) {
        tabsEl.appendChild(h("button", {
          class: "recipe-tab" + (activeTab === tab.id ? " active" : ""),
          type: "button", role: "tab", "aria-selected": activeTab === tab.id ? "true" : "false",
          onclick: () => { activeTab = tab.id; renderTabs(); renderTabBody(); }
        }, [h("span", { text: tab.label })]));
      }
    }
    function renderTabBody() {
      tabBody.innerHTML = "";
      const tab = ACHIEVEMENT_TABS.find((t) => t.id === activeTab) || ACHIEVEMENT_TABS[0];
      tabBody.appendChild(tab.pane);
    }

    let selectedId = Object.keys(achievements)[0] || null;
    // 詳細ペインの節タブ。選択IDを跨いでも保つ(同じ節を続けて直したいことが多いため)。
    const DETAIL_SECTIONS = [
      { id: "basic", label: "基本" },
      { id: "trigger", label: "達成条件" },
      { id: "gate", label: "前提・分岐" },
      { id: "rewards", label: "報酬" }
    ];
    let activeSection = "basic";

    function renderList() {
      listPane.innerHTML = "";
      const ids = Object.keys(achievements);
      if (!ids.length) listPane.appendChild(emptyHint("アチーブメントがありません。"));
      for (const id of ids) {
        const entry = achievements[id] || {};
        // 前提の深さぶん字下げして、一覧のままノードの親子関係が読めるようにする(2026-07-29)。
        const depth = prerequisiteDepth(achievements, id);
        const branches = Array.isArray(entry["parents-any"]) ? entry["parents-any"].length : 0;
        listPane.appendChild(h("button", {
          class: "nav-item achievement-list-item" + (id === selectedId ? " active" : ""),
          type: "button",
          onclick: () => { selectedId = id; renderList(); renderDetail(); }
        }, [
          h("span", {
            class: "nav-item-label",
            text: (depth > 0 ? "　".repeat(depth) + "└ " : "") + (entry["display-name"] || id)
              + (branches ? " (分岐" + branches + ")" : "")
          }),
          h("span", { class: "nav-badge", text: id })
        ]));
      }
      listPane.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ アチーブメント追加",
        onclick: () => {
          const id = uniqueKey(achievements, "new_achievement");
          achievements[id] = {
            "display-name": "新しいアチーブメント",
            trigger: normalizeAchievementTrigger({}),
            broadcast: false,
            rewards: normalizeAchievementRewards({})
          };
          selectedId = id;
          renderList();
          renderDetail();
        }
      }));
    }

    function renderDetail() {
      detailPane.innerHTML = "";
      if (!selectedId || !achievements[selectedId]) {
        detailPane.appendChild(emptyHint("左の一覧からアチーブメントを選択してください。"));
        return;
      }
      const entry = achievements[selectedId] && typeof achievements[selectedId] === "object"
        ? achievements[selectedId] : (achievements[selectedId] = {});
      entry.trigger = normalizeAchievementTrigger(entry.trigger);
      entry.rewards = normalizeAchievementRewards(entry.rewards);

      const head = h("div", { class: "entry-head-row" }, [
        h("span", { class: "range-label", text: "ID" }),
        idRenameInput(achievements, selectedId, (nextId, oldId) => {
          // renameKey 後、選択IDを追従させてから再描画
          const ids = Object.keys(achievements);
          selectedId = ids.find((k) => achievements[k] === entry) || selectedId;
          // 前提として自分を指している他ノードの参照も張り替える。放置すると
          // 「存在しない前提」になり、その枝が丸ごと永久に達成不能になる。
          remapPrerequisiteIds(achievements, oldId, nextId);
          renderList();
          renderDetail();
        }),
        h("button", {
          class: "btn-small danger", type: "button", text: "削除",
          onclick: () => {
            const removed = selectedId;
            delete achievements[selectedId];
            // 削除したIDを前提に持つノードから参照を落とす(残すと達成不能になる)。
            remapPrerequisiteIds(achievements, removed, null);
            selectedId = Object.keys(achievements)[0] || null;
            renderList();
            renderDetail();
          }
        })
      ]);

      // 節ごとの中身。表示は活性な節だけ(肥大化対策)だが、生成は全節ぶん行う。
      const sections = { basic: [], trigger: [], gate: [], rewards: [] };
      // 2026-07-29: 表示名も lore と同じくアイテムカタログ相当のリッチテキスト欄にした
      // (以前は表示名だけ素のテキスト欄で、同じカードの中で入力UIが揃っていなかった)。
      sections.basic.push(field("表示名 (display-name)",
        window.richTextInput(entry["display-name"] || "", "minimessage", (v) => {
          entry["display-name"] = v;
        }),
        "MiniMessage記法で色を付けられます。GUIでは色を書かなかったときだけ達成状況の色が付きます。"));

      // アイコン (2026-07-29): /achievement のGUIに出すアイテム。カタログID・custom:・バニラ
      // Material のどれでも書ける。空欄なら紙(PAPER)。報酬アイテム欄と同じセレクトを使う。
      if (typeof entry.icon !== "string") entry.icon = "";
      sections.basic.push(field("アイコン (icon)", window.itemRefSelect({
        value: entry.icon,
        catalogCandidates: opts.catalogCandidates,
        onChange: (v) => { entry.icon = v || ""; },
        placeholder: "アイコンを選択…(空欄=紙)"
      }), "/achievement のGUIでこのアチーブメントに使うアイテム。カタログの独自アイテムも選べます。"
        + "空欄のままなら PAPER が使われます。"));

      // 説明Lore (2026-07-29): アイテムカタログと同じ複数行エディタ(MiniMessage)。
      if (!Array.isArray(entry.lore)) entry.lore = [];
      sections.basic.push(h("div", { class: "form-field" }, [
        h("span", { class: "form-label", text: "説明Lore (lore)" }),
        window.renderLoreRows(entry.lore, "minimessage", () => {}, () => renderDetail()),
        h("div", {
          class: "field-hint",
          text: "GUIのアイテム説明に達成状況・条件行と一緒に並びます。MiniMessage記法が使えます。"
        })
      ]));

      // 裏アチーブメント (2026-08-16): 達成するまで GUI に一切出ない。系統バーの母数からも外れる。
      // 起点(前提なし)に付けると系統ごと消えてしまうので、前提が無いノードでは出さない。
      const hasAnyPrerequisite = (typeof entry.parent === "string" && entry.parent.trim())
        || (Array.isArray(entry["parents-any"]) && entry["parents-any"].some((v) => typeof v === "string" && v.trim()));
      if (hasAnyPrerequisite || entry.hidden === true) {
        sections.basic.push(field("裏アチーブメント (hidden)",
          window.checkboxInput(entry.hidden === true, (v) => {
            entry.hidden = !!v;
            renderDetail();
          }),
          "ONにすると達成するまでGUIに現れず、「この系統: N件」の母数からも外れます。"
            + "前提が無いノード(系統の起点)には付けられません(系統ごと入口が消えるため)。"));
      }

      // 2026-07-29: 以前はここを form-field-group(枠線+内側パディング)で囲み、さらに
      // それを「トリガー」ラベル付きの form-field で包んでいた。だが「達成条件」タブの中身は
      // このトリガー設定しかないので、ラベルも枠も同じことを 3 回言っているだけで、
      // 枠の分だけ左右に余白が積まれて読みづらくなっていた。素の縦積みへ 2 段フラット化する。
      const triggerBody = h("div", { class: "ach-trigger-fields" });
      function renderTriggerFields() {
        triggerBody.innerHTML = "";
        triggerBody.appendChild(field("トリガー種別", window.selectLabeledInput(entry.trigger.type, ACHIEVEMENT_TRIGGER_TYPES, "achievement-trigger", (v) => {
          entry.trigger.type = v;
          entry.trigger = normalizeAchievementTrigger(entry.trigger);
          renderTriggerFields();
        })));
        if (entry.trigger.type === "statistic") {
          triggerBody.appendChild(field("統計項目 (trigger.statistic)", statisticSelect(entry.trigger.statistic, (v) => {
            const before = statisticQualifierKind(entry.trigger.statistic);
            entry.trigger.statistic = v;
            // 修飾子の種類が変わったら前の値は無意味(ブロック名がモブ欄に残る等)なので捨てる。
            if (statisticQualifierKind(v) !== before) entry.trigger["statistic-qualifier"] = "";
            renderTriggerFields();
          })));
          const qualifierKind = statisticQualifierKind(entry.trigger.statistic);
          if (qualifierKind) {
            triggerBody.appendChild(field(
              "対象 (trigger.statistic-qualifier)",
              statisticQualifierSelect(qualifierKind, entry.trigger["statistic-qualifier"], (v) => {
                entry.trigger["statistic-qualifier"] = v;
              }),
              qualifierKind === "entity"
                ? "この統計は対象モブの指定が必須です。空欄だと起動時に警告が出てこのアチーブメントごと読み込まれません。"
                : (qualifierKind === "block"
                  ? "この統計は対象ブロックの指定が必須です。ブロックでない Material を指定すると読み込まれません。"
                  : "この統計は対象アイテムの指定が必須です。空欄だと起動時に警告が出て読み込まれません。")
            ));
          }
          triggerBody.appendChild(field("閾値 (trigger.threshold)", window.numberInput(entry.trigger.threshold, (v) => {
            if (v != null) entry.trigger.threshold = Math.max(0, Math.floor(v));
          }, { int: true })));
        } else if (entry.trigger.type === "advancement") {
          triggerBody.appendChild(field("進捗キー (trigger.advancement)", advancementSelect(entry.trigger.advancement, (v) => {
            entry.trigger.advancement = v;
          }), "一覧にないデータパック進捗は「＋ 自由入力…」から namespace:path で書けます。"));
        } else if (entry.trigger.type === "counter") {
          triggerBody.appendChild(field("カウンタ (trigger.counter)",
            window.selectLabeledInput(entry.trigger.counter, ACHIEVEMENT_COUNTER_IDS, "achievement-counter", (v) => {
              entry.trigger.counter = v;
            }),
            "バニラ統計に無い累計値。儀式でソースを消費したときに加算されるので、第2目標「累計1億ソース」はこれで書きます。"));
          triggerBody.appendChild(field("閾値 (trigger.threshold)", window.numberInput(entry.trigger.threshold, (v) => {
            if (v != null) entry.trigger.threshold = Math.max(1, Math.floor(v));
          }, { int: true }), "1以上。0や空欄だと読み込み時にこのアチーブメントごと捨てられます。"));
        } else if (entry.trigger.type === "gear-use") {
          // 2026-08-16: 「その装備で実際にダメージを与えた」で判定する。クラフトで判定すると
          // 使用レベル制限を跨いで先に取れてしまうため、記録側は GearUseListener が
          // EntityDamageByEntityEvent(MONITOR) でだけ書く。
          const g = entry.trigger["gear-use"];
          const plainName = (raw) => (typeof window.stripDisplayNamePlain === "function"
            ? window.stripDisplayNamePlain(raw) : String(raw == null ? "" : raw));
          const gearCandidates = (Array.isArray(opts.catalogCandidates) ? opts.catalogCandidates : [])
            .map((v) => {
              const name = plainName(v.label != null && v.label !== "" ? v.label : v.displayName);
              return { value: v.id, primary: name || v.id, secondary: v.id };
            })
            .concat((Array.isArray(window.MATERIALS) ? window.MATERIALS : []).map((mat) => {
              const ja = window.LABELS && typeof window.LABELS.materialLabel === "function"
                ? window.LABELS.materialLabel(mat) : "";
              return { value: mat, primary: `バニラ: ${ja || mat}`, secondary: mat };
            }));
          triggerBody.appendChild(field("部位 (gear-use.slot)", window.listSelect({
            value: g.slot,
            options: [
              { value: "weapon", primary: "武器(与ダメージ時の手持ち)", secondary: "weapon" },
              { value: "armor", primary: "防具(与ダメージ時の着用)", secondary: "armor" }
            ],
            onCommit: (v) => {
              const next = String(v || "weapon");
              if (next === g.slot) return false;
              g.slot = next;
              return true;
            }
          }), "武器は矢を撃った時点の弓/クロスボウも記録します(撃った後に持ち替えても誤記録しません)。"));
          const itemsBody = h("div", { class: "stat-rows" });
          function renderGearItems() {
            itemsBody.innerHTML = "";
            if (!g.items.length) itemsBody.appendChild(emptyHint("対象が選ばれていません(このままだと誰も達成できません)。"));
            g.items.forEach((item, idx) => {
              const row = h("div", { class: "stat-row" });
              row.appendChild(window.listSelect({
                value: item,
                options: gearCandidates,
                onCommit: (v) => {
                  const next = String(v || "").trim();
                  if (!next) return false;
                  g.items = g.items.slice();
                  g.items[idx] = next;
                  g.items = g.items.filter((x, i, arr) => arr.indexOf(x) === i);
                  renderGearItems();
                  return true;
                }
              }));
              row.appendChild(h("button", {
                class: "btn-small danger", type: "button", text: "×", title: "この装備を外す",
                onclick: () => { g.items = g.items.filter((_, i) => i !== idx); renderGearItems(); }
              }));
              itemsBody.appendChild(row);
            });
            const pool = gearCandidates.filter((o) => !g.items.includes(o.value));
            if (pool.length) {
              const addRow = h("div", { class: "stat-row" });
              addRow.appendChild(window.listSelect({
                value: "", options: pool, placeholder: "＋ 装備を追加…",
                onCommit: (v) => {
                  const next = String(v || "").trim();
                  if (!next || g.items.includes(next)) return false;
                  g.items = g.items.concat(next);
                  renderGearItems();
                  return true;
                }
              }));
              itemsBody.appendChild(addRow);
            }
          }
          renderGearItems();
          triggerBody.appendChild(field("対象装備 (gear-use.items)", itemsBody,
            "列挙したどれか1つで達成です(「和」ではなく「or」)。同じ階梯の武器を全部並べてください。"));
        } else if (entry.trigger.type === "skill-level") {
          // 2026-08-16: 「skills のうち count 種類が level に達したら達成」。
          // count > skills 件数 は永久に達成できないので UI 側で上限を掛ける。
          const s = entry.trigger["skill-level"];
          const skillLabel = (id) => {
            const hit = JOB_EXP_SKILLS.find(([v]) => v === id);
            return hit ? hit[1] : id;
          };
          const skillCandidates = ACHIEVEMENT_SKILL_LEVEL_IDS.map((id) => ({
            value: id,
            primary: id === "COMBAT" ? "総合戦闘レベル" : skillLabel(id),
            secondary: id
          }));
          const countInput = window.numberInput(s.count, (v) => {
            const max = Math.max(1, s.skills.length);
            s.count = Math.min(max, Math.max(1, Math.floor(Number(v) || 1)));
          }, { int: true });
          const skillsBody = h("div", { class: "stat-rows" });
          function renderSkillRows() {
            skillsBody.innerHTML = "";
            if (!s.skills.length) skillsBody.appendChild(emptyHint("スキルが選ばれていません(このままだと誰も達成できません)。"));
            s.skills.forEach((skill, idx) => {
              const row = h("div", { class: "stat-row" });
              row.appendChild(window.listSelect({
                value: skill,
                options: skillCandidates,
                onCommit: (v) => {
                  const next = String(v || "").trim().toUpperCase();
                  if (!next) return false;
                  const copy = s.skills.slice();
                  copy[idx] = next;
                  s.skills = copy.filter((x, i, arr) => arr.indexOf(x) === i);
                  syncSkillCount();
                  renderSkillRows();
                  return true;
                }
              }));
              row.appendChild(h("button", {
                class: "btn-small danger", type: "button", text: "×", title: "このスキルを外す",
                onclick: () => {
                  s.skills = s.skills.filter((_, i) => i !== idx);
                  syncSkillCount();
                  renderSkillRows();
                }
              }));
              skillsBody.appendChild(row);
            });
            const pool = skillCandidates.filter((o) => !s.skills.includes(o.value));
            if (pool.length) {
              const addRow = h("div", { class: "stat-row" });
              addRow.appendChild(window.listSelect({
                value: "", options: pool, placeholder: "＋ スキルを追加…",
                onCommit: (v) => {
                  const next = String(v || "").trim().toUpperCase();
                  if (!next || s.skills.includes(next)) return false;
                  s.skills = s.skills.concat(next);
                  renderSkillRows();
                  return true;
                }
              }));
              skillsBody.appendChild(addRow);
            }
          }
          function syncSkillCount() {
            const max = Math.max(1, s.skills.length);
            if (s.count <= max) return;
            s.count = max;
            countInput.value = String(max);
          }
          renderSkillRows();
          triggerBody.appendChild(field("対象スキル (skill-level.skills)", skillsBody,
            "ここに並べたスキルのうち、下の「必要な種類数」だけがレベルに達したら達成です。"));
          triggerBody.appendChild(field("必要レベル (skill-level.level)", window.numberInput(s.level, (v) => {
            if (v != null) s.level = Math.min(100, Math.max(1, Math.floor(v)));
          }, { int: true }), "1〜100。"));
          triggerBody.appendChild(field("必要な種類数 (skill-level.count)", countInput,
            "1なら「どれか1つ」、対象スキル数と同じなら「全部」。対象スキル数を超える値は保存できません。"));
        } else {
          const c = entry.trigger.collection;
          const categories = [];
          for (const kind of ["items", "mobs"]) {
            for (const [id, value] of Object.entries((collectionData.categories || {})[kind] || {})) {
              categories.push({ value: `category:${id}`, primary: `カテゴリ: ${(value && value["display-name"]) || id}`, secondary: id });
            }
          }
          // 候補生成器 (catalog-candidates.js) が返すキーは displayName。label しか見ていなかった
          // ため「アイテム: infinity_sword」のようにIDが主表示になっていた (2026-07-29)。
          const plainName = (raw) => (typeof window.stripDisplayNamePlain === "function"
            ? window.stripDisplayNamePlain(raw) : String(raw == null ? "" : raw));
          const catalogItemCandidates = (Array.isArray(opts.catalogCandidates) ? opts.catalogCandidates : [])
            .map((v) => {
              const name = plainName(v.label != null && v.label !== "" ? v.label : v.displayName);
              return { value: `item:${v.id}`, primary: `アイテム: ${name || v.id}`, secondary: v.id };
            });
          // 2026-07-29: カタログ品しか候補に出ておらず、「ダイヤモンドを入手」のような
          // バニラアイテム条件が作れなかった。図鑑側(CollectionListener)がここに書かれた
          // Material を記録するようになったので、バニラMaterialも候補に載せる。
          const vanillaItemCandidates = (Array.isArray(window.MATERIALS) ? window.MATERIALS : [])
            .map((mat) => {
              const ja = window.LABELS && typeof window.LABELS.materialLabel === "function"
                ? window.LABELS.materialLabel(mat) : "";
              return { value: `item:${mat}`, primary: `バニラ: ${ja || mat}`, secondary: mat };
            });
          const itemCandidates = catalogItemCandidates.concat(vanillaItemCandidates);
          // 2026-07-27 タスク4横断監査: vocab-1.21.11.js の window.MOB_LABELS_JA (recipes.js/ars-p4.js の
          // モブ選択で使われているのと同じ辞書) に和名があるのに、ここだけ生の EntityType ID をそのまま
          // primary に出していた。既存辞書をそのまま使い、未登録の場合だけIDへフォールバックする。
          const mobCandidates = ENTITY_TYPE_CANDIDATES.map((id) => {
            const ja = window.MOB_LABELS_JA && window.MOB_LABELS_JA[id];
            return { value: `mob:${id}`, primary: ja ? `モブ: ${ja}` : `モブ: ${id}`, secondary: id };
          });
          const allOption = { value: "all", primary: "すべての図鑑カテゴリ", secondary: "ALL" };
          const allOptions = [allOption].concat(categories, itemCandidates, mobCandidates);
          const labelOf = (scope, target) => {
            const hit = allOptions.find((o) => o.value === `${scope}:${target}`);
            return hit ? hit.primary : `${scope}: ${target}`;
          };
          // 対象は複数持てる (collection.targets)。scope は全対象で共通なので、行を足すときは
          // 現在の scope と同じ種類の候補だけを出す — scope 混在は Java 側の候補集合の作り方
          // (scope で prefix を決める) と噛み合わないため、UI の段階で作れないようにしておく。
          const targetsBody = h("div", { class: "stat-rows" });
          function candidatesForScope() {
            if (c.scope === "category") return categories;
            if (c.scope === "item") return itemCandidates;
            if (c.scope === "mob") return mobCandidates;
            return [];
          }
          // 閾値の対象数への追随。判断は autoCollectionThreshold(純関数)に置いてある。
          // 開いた時点で「対象数と閾値が一致していない」なら、それは手で決めた値とみなして追随しない。
          let thresholdTouched = Number(c.threshold || 1) !== Math.max(1, c.targets.length);
          const thresholdInput = window.numberInput(c.threshold, (v) => {
            c.threshold = Math.max(1, Math.floor(Number(v) || 1));
            thresholdTouched = true;
          }, { int: true });
          function syncThreshold() {
            const next = autoCollectionThreshold(c, thresholdTouched);
            if (next == null) return;
            c.threshold = next;
            thresholdInput.value = String(next);
          }
          function syncTargets(next) {
            c.targets = next.filter((v, i, arr) => v && arr.indexOf(v) === i);
            c.target = c.targets[0] || "";
            syncThreshold();
          }
          function renderTargets() {
            targetsBody.innerHTML = "";
            if (c.scope === "all") {
              targetsBody.appendChild(h("div", {
                class: "field-hint",
                text: "「すべての図鑑カテゴリ」は対象指定を取りません（図鑑全体の登録数で判定します）。"
              }));
              return;
            }
            if (!c.targets.length) targetsBody.appendChild(emptyHint("対象が選ばれていません。"));
            c.targets.forEach((target, idx) => {
              const row = h("div", { class: "stat-row" });
              row.appendChild(window.listSelect({
                value: `${c.scope}:${target}`,
                options: candidatesForScope(),
                onCommit: (v) => {
                  const rest = String(v || "").split(":").slice(1).join(":");
                  if (!rest) return false;
                  const next = c.targets.slice();
                  next[idx] = rest;
                  syncTargets(next);
                  renderTargets();
                  return true;
                }
              }));
              row.appendChild(h("button", {
                class: "btn-small danger", type: "button", text: "×",
                title: "この対象を外す",
                onclick: () => { const next = c.targets.slice(); next.splice(idx, 1); syncTargets(next); renderTargets(); }
              }));
              targetsBody.appendChild(row);
            });
            const pool = candidatesForScope().filter((o) => !c.targets.includes(o.value.split(":").slice(1).join(":")));
            if (pool.length) {
              const addRow = h("div", { class: "stat-row" });
              addRow.appendChild(window.listSelect({
                value: "", options: pool, placeholder: "＋ 対象を追加…",
                onCommit: (v) => {
                  const rest = String(v || "").split(":").slice(1).join(":");
                  if (!rest) return false;
                  syncTargets(c.targets.concat(rest));
                  renderTargets();
                  return true;
                }
              }));
              targetsBody.appendChild(addRow);
            }
            if (c.targets.length > 1) {
              targetsBody.appendChild(h("div", {
                class: "field-hint",
                text: thresholdTouched
                  ? "複数指定は「和」で数えます。現在の閾値 " + c.threshold + " 件で達成になります"
                    + "（全部そろって達成にするなら閾値を " + c.targets.length + " に）。"
                  : "複数指定は「和」で数えます。閾値は対象数に追随中（" + c.targets.length
                    + " 件そろったら達成）。手で変えるとそこで追随は止まります。"
              }));
            }
          }
          triggerBody.appendChild(field("図鑑対象の種類", window.listSelect({
            value: c.scope === "all" ? "all" : c.scope,
            options: [
              allOption,
              { value: "category", primary: "カテゴリ", secondary: "category" },
              { value: "item", primary: "アイテム", secondary: "item" },
              { value: "mob", primary: "モブ", secondary: "mob" }
            ],
            onCommit: (v) => {
              const next = String(v || "all");
              if (next === c.scope) return false;
              c.scope = next;
              syncTargets([]); // 種類が変わると旧IDは別名前空間になるので持ち越さない。
              renderTargets();
              return true;
            }
          }), c.targets.length ? "現在: " + c.targets.map((t) => labelOf(c.scope, t)).join(" / ") : ""));
          triggerBody.appendChild(field("対象 (collection.targets)", targetsBody,
            "複数選べます。単一だけ選べば従来どおり collection.target としても保存されます。"));
          renderTargets();
          triggerBody.appendChild(field("閾値", thresholdInput,
            "登録済みが何件で達成か（判定方式が percent のときは百分率 1-100）。"));
          triggerBody.appendChild(field("判定方式", window.listSelect({
            value: c.percent ? "percent" : "count",
            options: [
              { value: "count", primary: "件数で判定", secondary: "count" },
              { value: "percent", primary: "百分率で判定", secondary: "percent" }
            ],
            onCommit: (v) => {
              const next = v === "percent";
              if (next === c.percent) return false;
              c.percent = next;
              syncThreshold();
              renderTargets();
              return true;
            }
          })));
        }
      }
      renderTriggerFields();
      sections.trigger.push(triggerBody);

      sections.basic.push(h("label", { class: "inline-check" }, [
        window.checkboxInput(!!entry.broadcast, (v) => { entry.broadcast = !!v; }),
        h("span", { text: "サーバ通知 (broadcast) — 達成時に全体通知" })
      ]));

      // ---- 前提・分岐 (2026-07-29): スキルツリーと同じ「親→子」でノードをつなぐ ----
      // parent は単一の必須前提、parents-any は「いずれか1つ」。両方書いた場合は
      // Java 側 prerequisitesMet() と同じく OR (どれか1つ満たせば開く)。
      for (const el of buildGateFields(entry, achievements, selectedId, renderDetail)) {
        sections.gate.push(el);
      }

      // rewards.special: 候補から複数選択して追加、行ごとに削除
      const specialBox = h("div", { class: "stat-rows" });
      function renderSpecial() {
        specialBox.innerHTML = "";
        const list = entry.rewards.special;
        if (!list.length) specialBox.appendChild(emptyHint("特殊報酬が割り当てられていません。"));
        list.forEach((id, idx) => {
          specialBox.appendChild(specialRewardAssignedRow(specialRewardLabels, id, () => {
            list.splice(idx, 1);
            renderSpecial();
          }));
        });
        const available = specialRewardIds.filter((id) => !list.includes(id));
        if (available.length) {
          const addRow = h("div", { class: "stat-row" });
          addRow.appendChild(specialRewardAddSelect(available, specialRewardLabels, (v) => {
            if (!list.includes(v)) { list.push(v); renderSpecial(); }
          }));
          specialBox.appendChild(addRow);
        } else if (specialRewardIds.length) {
          specialBox.appendChild(h("div", { class: "field-hint", text: "定義済みの特殊報酬をすべて割り当て済みです。" }));
        } else {
          specialBox.appendChild(h("div", { class: "field-hint", text: "特殊報酬タブ (special-rewards) でIDを作成すると候補に出ます。" }));
        }
      }
      renderSpecial();
      sections.rewards.push(h("div", { class: "form-field" }, [h("span", { class: "form-label", text: "特殊報酬 (rewards.special)" }), specialBox]));

      sections.rewards.push(h("div", { class: "form-field" }, [
        h("span", { class: "form-label", text: "コンソールコマンド (rewards.commands)" }),
        stringListEditor(entry.rewards.commands, { addLabel: "+ コマンド追加", placeholder: "give %player% diamond 1", empty: "コマンドがありません。" })
      ]));

      for (const el of buildRewardExtrasFields(entry.rewards, opts.catalogCandidates)) sections.rewards.push(el);

      const sectionTabs = h("div", { class: "recipe-tabs cf-tabs achievement-section-tabs", role: "tablist" });
      for (const sec of DETAIL_SECTIONS) {
        sectionTabs.appendChild(h("button", {
          class: "recipe-tab" + (activeSection === sec.id ? " active" : ""),
          type: "button", role: "tab", "aria-selected": activeSection === sec.id ? "true" : "false",
          onclick: () => { activeSection = sec.id; renderDetail(); }
        }, [h("span", { text: sec.label })]));
      }
      const active = sections[activeSection] || sections.basic;
      detailPane.appendChild(card([head], [sectionTabs].concat(active)));
    }

    renderList();
    renderDetail();
    renderTabs();
    renderTabBody();

    return {
      element: root,
      getData: () => {
        for (const entry of Object.values(achievements)) {
          if (!entry || typeof entry !== "object") continue;
          if (entry.rewards && typeof entry.rewards === "object") {
            entry.rewards.commands = filterNonEmptyStrings(entry.rewards.commands);
            filterRewardExtras(entry.rewards);
          }
          // 2026-07-29: 表示用に実体化した空欄をYAMLへ書き出さない(既存ファイルを
          // 開いて保存しただけで icon: "" / lore: [] が生えるのを避ける)。
          if (typeof entry.icon === "string" && entry.icon.trim() === "") delete entry.icon;
          // lore の空行は「区切り」として意図的に置かれるので中身は間引かない
          // (アイテムカタログ側 functional-items.js と同じ扱い)。空配列だけ落とす。
          if (Array.isArray(entry.lore) && entry.lore.length === 0) delete entry.lore;
          if (typeof entry.coords === "string" && entry.coords.trim() === "") delete entry.coords;
          if (typeof entry.parent === "string" && entry.parent.trim() === "") delete entry.parent;
          if (Array.isArray(entry["parents-any"])) {
            entry["parents-any"] = filterNonEmptyStrings(entry["parents-any"]);
            if (!entry["parents-any"].length) delete entry["parents-any"];
          }
        }
        return working;
      }
    };
  };

  // ============================================================
  // 3. collection.yml (カテゴリのみ。報酬はアチーブメントの collection トリガーへ移管)
  // ============================================================
  // E-2 (2026-07-25): mob-forms.js と重複していた37種+ENDER_DRAGON を vocab-1.21.11.js の
  // VANILLA_MOBS(paper-api javap抽出、召喚可能な生物83種、ENDER_DRAGON含む)へ集約する。
  // ⚠️ 旧配列にあった "WITHER_BOSS" は集約時に含めていない — paper-api 1.21.11 の
  // org.bukkit.entity.EntityType には存在しない値であることを class バイト列で確認済み
  // (有効な EntityType 名は "WITHER" のみ)。無効な値を候補に残すと選択時に壊れたデータを
  // 作ってしまうため、他の36種+ENDER_DRAGONは全て含めた上でこの1件のみ除外している。要ユーザー確認。
  const ENTITY_TYPE_CANDIDATES = Array.isArray(window.VANILLA_MOBS) ? window.VANILLA_MOBS : [];

  const COLLECTION_SECTIONS = [
    { id: "overview", label: "概要" },
    { id: "categories-items", label: "カテゴリ: アイテム" },
    { id: "categories-mobs", label: "カテゴリ: モブ" }
  ];

  window.buildCollectionForm = function buildCollectionForm(data, options) {
    const opts = options && typeof options === "object" ? options : {};
    const catalogCandidates = Array.isArray(opts.catalogCandidates) ? opts.catalogCandidates : [];
    const working = data && typeof data === "object" ? data : {};
    if (typeof working.enabled !== "boolean") working.enabled = true;
    ensureObj(working, "sources", { "catalog-items": true, "mob-kills": true });
    ensureObj(working, "categories", {});
    ensureObj(working.categories, "items", {});
    ensureObj(working.categories, "mobs", {});

    // 2026-07-31: ここは catalogItemSuggest しか使っておらず**バニラ Material を候補に持たない**
    // ため、collection.yml の「遺物」カテゴリ(16件すべてバニラ Material)が
    // 「primary=生ID / secondary=候補外」で表示されていた。日本語名は素材辞書に全件あるので、
    // 辞書ではなく候補集合の欠落。アチーブメント画面の図鑑対象欄は既にバニラも載せており
    // 画面間で流儀が食い違っていたので、共通ヘルパー itemRefSelect(カタログ品→バニラの順に
    // 並ぶ)へ揃える。保存値は今までどおりID文字列そのもの。
    function catalogEntryControl(value, onChange) {
      if (typeof window.itemRefSelect === "function") {
        return window.itemRefSelect({
          value: value || "",
          onChange,
          catalogCandidates,
          placeholder: "カタログID / バニラMaterial を選択…",
          customPlaceholder: "カタログID / バニラMaterial を直接入力"
        });
      }
      return window.textInput(value || "", onChange, "カタログID");
    }
    // 2026-07-29: datalist 付きの素の text 入力だったため、候補は英字 EntityType の羅列で
    // 日本語では引けず、タイポも素通りしていた。他画面と同じ listSelect (primary=和名 /
    // secondary=ID、絞り込み入力つき) へ統一する。mob-types.yml の独自 mobTypeId も
    // 書けるよう自由入力は残す。
    function mobEntryControl(value, onChange) {
      return window.mobTypeSelect(value, onChange, {
        unknownNote: "mob-types.yml",
        customPlaceholder: "ZOMBIE / mob-types.yml の mobTypeId"
      });
    }

    let active = "overview";
    const root = h("div", { class: "dedicated-form cf-form" });
    const tabsEl = h("div", { class: "recipe-tabs cf-tabs", role: "tablist" });
    const bodyEl = h("div", { class: "cf-body" });
    root.appendChild(tabsEl);
    root.appendChild(bodyEl);

    function setActive(id) { active = id; renderTabs(); renderBody(); }
    function renderTabs() {
      tabsEl.innerHTML = "";
      for (const sec of COLLECTION_SECTIONS) {
        tabsEl.appendChild(h("button", {
          class: "recipe-tab" + (active === sec.id ? " active" : ""),
          type: "button", role: "tab", "aria-selected": active === sec.id ? "true" : "false",
          onclick: () => setActive(sec.id)
        }, [h("span", { text: sec.label })]));
      }
    }

    function renderOverview() {
      bodyEl.appendChild(card(
        [h("span", { class: "entry-key-label", text: "有効化" })],
        [
          h("label", { class: "inline-check" }, [
            window.checkboxInput(!!working.enabled, (v) => { working.enabled = !!v; }),
            h("span", { text: "enabled (図鑑機能を有効化)" })
          ]),
          h("label", { class: "inline-check" }, [
            window.checkboxInput(!!working.sources["catalog-items"], (v) => { working.sources["catalog-items"] = !!v; }),
            h("span", { text: "sources.catalog-items (アイテム入手を記録)" })
          ]),
          h("label", { class: "inline-check" }, [
            window.checkboxInput(!!working.sources["mob-kills"], (v) => { working.sources["mob-kills"] = !!v; }),
            h("span", { text: "sources.mob-kills (モブ討伐を記録)" })
          ])
        ]
      ));
      // 2026-07-27: 図鑑GUIの見た目。未発見エントリの錠前アイコンを差し替えられるようにする。
      const gui = ensureObj(working, "gui", {});
      bodyEl.appendChild(card(
        [h("span", { class: "entry-key-label", text: "図鑑GUI (gui)" })],
        [h("div", { class: "field-grid" }, [
          field("未発見アイコン (locked-icon)",
            window.materialInput(gui["locked-icon"] || "BARRIER", "collection-locked-icon-list",
              (v) => { gui["locked-icon"] = v || "BARRIER"; }),
            "未発見エントリに使う「錠前」アイコンのMaterial。解決できない名前は BARRIER へ自動で戻ります。")
        ])]
      ));
      // 表示名の上書き。既定は空でよい (アイテムは display-name、モブは翻訳キーで正しく出る)。
      // モブだけは検索/並べ替えがサーバー側で英名基準になるため、日本語で引きたいものをここへ書く。
      const displayNames = ensureObj(working, "display-names", {});
      ensureObj(displayNames, "items", {});
      ensureObj(displayNames, "mobs", {});
      bodyEl.appendChild(card(
        [h("span", { class: "entry-key-label", text: "表示名の上書き (display-names)" })],
        [
          h("div", {
            class: "field-hint",
            text: "省略可。アイテムはカタログの display-name が、モブはバニラの翻訳名がそのまま出ます。"
              + "ここへ書くと図鑑の表示名だけでなく、名前検索・名前ソートの基準もその名前になります"
              + "（モブを日本語で検索したいときに使います）。"
          }),
          h("div", { class: "sub-title", text: "アイテム (display-names.items)" }),
          keyValueEditor(displayNames.items, {
            keyControl: (value, onChange) => catalogEntryControl(value, onChange),
            keyPlaceholder: "カタログID",
            valuePlaceholder: "表示名",
            addLabel: "+ アイテム表示名を追加",
            empty: "上書きはありません（通常はこのままで問題ありません）。",
            newKey: "new_item"
          }),
          h("div", { class: "sub-title", text: "モブ (display-names.mobs)" }),
          keyValueEditor(displayNames.mobs, {
            keyControl: (value, onChange) => mobEntryControl(value, onChange),
            keyPlaceholder: "ENTITY_TYPE",
            valuePlaceholder: "表示名",
            addLabel: "+ モブ表示名を追加",
            empty: "上書きはありません（通常はこのままで問題ありません）。",
            newKey: "ZOMBIE"
          })
        ]
      ));
    }

    /**
     * 単純な「キー→文字列」マップの編集UI。キー欄のコントロールは呼び出し側が差し込む
     * (アイテムはカタログサジェスト、モブはEntityType候補)。
     */
    function keyValueEditor(map, cfg) {
      const box = h("div", { class: "stat-rows" });
      function render() {
        box.innerHTML = "";
        const keys = Object.keys(map);
        if (!keys.length) box.appendChild(emptyHint(cfg.empty));
        for (const key of keys) {
          const row = h("div", { class: "stat-row" });
          row.appendChild(cfg.keyControl(key, (next) => {
            const trimmed = String(next || "").trim();
            if (!trimmed || trimmed === key) return;
            if (Object.prototype.hasOwnProperty.call(map, trimmed)) return;
            const value = map[key];
            delete map[key];
            map[trimmed] = value;
            render();
          }));
          row.appendChild(window.textInput(map[key] == null ? "" : String(map[key]),
            (v) => { map[key] = v; }, cfg.valuePlaceholder));
          row.appendChild(h("button", {
            class: "btn-small danger", type: "button", text: "×",
            onclick: () => { delete map[key]; render(); }
          }));
          box.appendChild(row);
        }
        box.appendChild(h("button", {
          class: "btn-small", type: "button", text: cfg.addLabel,
          onclick: () => { map[uniqueKey(map, cfg.newKey)] = ""; render(); }
        }));
      }
      render();
      return box;
    }

    function renderCategoryGroup(groupKey, entryControl, addPlaceholderId) {
      const group = working.categories[groupKey];
      const ids = Object.keys(group);
      if (!ids.length) bodyEl.appendChild(emptyHint("カテゴリがありません。"));
      for (const catId of ids) {
        const entry = group[catId] && typeof group[catId] === "object" ? group[catId] : (group[catId] = {});
        if (!Array.isArray(entry.entries)) entry.entries = [];
        const c = h("div", { class: "cf-mat-card" });
        c.appendChild(h("div", { class: "cf-mat-card-head" }, [
          h("span", { class: "entry-key-label", text: "カテゴリID" }),
          idRenameInput(group, catId, renderBody),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { delete group[catId]; renderBody(); }
          })
        ]));
        c.appendChild(field("表示名 (display-name)", window.textInput(entry["display-name"] || "", (v) => {
          entry["display-name"] = v;
        })));
        c.appendChild(field("表示順 (order)", window.numberInput(entry.order == null ? 1 : entry.order, (v) => {
          if (v != null) entry.order = Math.floor(v);
        }, { int: true })));
        const entriesBox = h("div", { class: "stat-rows" });
        entry.entries.forEach((val, idx) => {
          const row = h("div", { class: "stat-row" });
          row.appendChild(entryControl(val, (v) => { entry.entries[idx] = v; }));
          row.appendChild(h("button", {
            class: "btn-small danger", type: "button", text: "×",
            onclick: () => { entry.entries.splice(idx, 1); renderBody(); }
          }));
          entriesBox.appendChild(row);
        });
        entriesBox.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ 追加",
          onclick: () => { entry.entries.push(""); renderBody(); }
        }));
        const bulkInput = h("input", {
          class: "field-input",
          placeholder: groupKey === "items"
            ? "infinity_* / *_SWORD / DIAMOND（カンマ区切り・*可）"
            : "ZOMBIE, SKELETON（カンマ区切り・*可）"
        });
        entriesBox.appendChild(h("div", { class: "stat-row" }, [bulkInput, h("button", {
          class: "btn-small", type: "button", text: "一括追加",
          onclick: () => {
            const patterns = bulkInput.value.split(",").map((v) => v.trim()).filter(Boolean);
            // 2026-08-18: items 側の候補が catalogCandidates(カスタムIDのみ)だけで、
            // itemRefSelect では選べるバニラ Material が一括追加からは構造的に選べなかった。
            // 候補集合の作り方は catalog-candidates.js の bulkAddCandidateIds に集約してある
            // (Node テストから直接検証するため)。
            const candidates = typeof window.bulkAddCandidateIds === "function"
              ? window.bulkAddCandidateIds(groupKey, catalogCandidates, window.MATERIALS, ENTITY_TYPE_CANDIDATES)
              : (groupKey === "items" ? catalogCandidates.map((v) => v.id) : ENTITY_TYPE_CANDIDATES);
            const added = [];
            for (const pattern of patterns) {
              const escaped = pattern.replace(/[.+?^${}()|[\]\\]/g, "\\$&");
              const re = new RegExp("^" + escaped.replace(/\*/g, ".*") + "$", "i");
              candidates.filter((id) => re.test(id)).forEach((id) => { if (!entry.entries.includes(id) && !added.includes(id)) added.push(id); });
            }
            entry.entries.push(...added); renderBody();
          }
        })]));
        c.appendChild(h("div", { class: "form-field" }, [h("span", { class: "form-label", text: "登録アイテム/モブ (entries)" }), entriesBox]));
        bodyEl.appendChild(c);
      }
      bodyEl.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ カテゴリを追加",
        onclick: () => { group[uniqueKey(group, addPlaceholderId)] = { "display-name": "新しいカテゴリ", order: ids.length + 1, entries: [] }; renderBody(); }
      }));
    }

    // 注: collection.yml の reward-tiers 用フォームはここにあったが、報酬はアチーブメントの
    // 図鑑トリガー(trigger.type: static)へ移管済みでタブからは到達不能な死にコードだった
    // (しかも buildCollectionForm のスコープに無い specialRewardIds を参照しており、
    // 呼ばれれば必ず ReferenceError になる)。2026-07-29 のセレクト日本語化監査で削除。
    // 既存ファイルの reward-tiers 値そのものは working をそのまま返す往復ロスレス方式で温存される。

    function renderBody() {
      bodyEl.innerHTML = "";
      switch (active) {
        case "overview": renderOverview(); break;
        case "categories-items": renderCategoryGroup("items", catalogEntryControl, "weapons"); break;
        case "categories-mobs": renderCategoryGroup("mobs", mobEntryControl, "bosses"); break;
        default: renderOverview();
      }
    }

    renderTabs();
    renderBody();

    return {
      element: root,
      getData: () => {
        return working;
      }
    };
  };
})(typeof window !== "undefined" && typeof document !== "undefined");
