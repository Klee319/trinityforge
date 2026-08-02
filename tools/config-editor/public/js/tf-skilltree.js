"use strict";

// TrinityForge skilltree/*.yml 専用フォーム。
//   構造: skill/display-name/icon/starting-coords/prestige{...}/nodes{<id>: node}
//   実効(編集可): display-name/icon/starting-coords, node の name/level/role/parent/parents-any/group/icon/cost,
//                 description(自由記述説明。要件⑤), buffs(数値),
//                 dedicated-effects(解放効果。プレフィックス付き動的ID。2026-07-23大改修), prestige の enabled/at-level/name/description/buffs
//   不活性(表示のみ): skill(識別子)
//   非推奨(UIから除去。読まれない): effects[] / commands[] — 既存データがあっても触らず温存するだけで表示しない。
//
// 往復ロスレス最優先: working は受け取った data を直接編集する。既知キーのみ専用UI、
// 未知キー・キー順・空マップは温存する。effects/commands は絶対に書き換え/削除しない(表示しないだけ)。

(function () {
    const h = window.h;

  function card(headChildren, bodyChildren) {
    return h("div", { class: "entry-card" }, [
      h("div", { class: "entry-head" }, headChildren),
      h("div", { class: "entry-body" }, bodyChildren)
    ]);
  }
  function field(key, control, opts) {
    return h("div", { class: "form-field" }, [window.fieldLabelEl(key, opts), control]);
  }
  function emptyGuide(title, hint) {
    return h("div", { class: "empty-guide" }, [
      h("div", { class: "empty-guide-title", text: title }),
      h("div", { class: "empty-guide-hint", text: hint })
    ]);
  }
  function renameKey(map, oldKey, newKey) {
    const rebuilt = {};
    for (const k of Object.keys(map)) rebuilt[k === oldKey ? newKey : k] = map[k];
    for (const k of Object.keys(map)) delete map[k];
    Object.assign(map, rebuilt);
  }

  // 旧 native を開いた時点で buffs / dedicated-effects に移行する。未対応キーは消さず残すため、
  // この版で意味が分からない独自データを黙って破壊しない。
  const LEGACY_NATIVE_TO_BUFF = {
    // archery_chargedshotunlocked_toggle は 2026-07-27 に対応先の charged-shot-unlocked ごと
    // 撤去した(挙動ゼロの同語反復フラグだった)。旧データにこのnativeキーが残っていても、
    // 未対応キーは消さずそのまま残す方針なので黙って壊れることはない。
    arsmagic_unlockedtier_add: "ars-tier-bonus",
    arsmagic_glyphslots_add: "glyph-slot-bonus",
    // 2026-07-31: lightarmor_/heavyarmor_movementspeedperpiece_add の移行先
    // (light-/heavy-armor-move-speed-per-piece) を語彙ごと廃止した。存在しないステキーへ横流しすると
    // 開いて保存した瞬間に channel NONE で無言ドロップされるため、ここからは外して native に残す
    // (「未対応キーは消さず残す」既定方針どおり)。移動速度は set-buffs の move-speed で書き直す。
    // 2026-07-27(armor-set-buffs全面移行): 旧 setamount(セット効果の増幅率)は armor-set-bonus
    // 1本へ統一されたのでそのまま横流しできる。旧 setdodgechance/setknockbackresistance は
    // 装備部位数条件の set-buffs スキーマへ移行しないと意味を保てない(平坦な buffs には対応先が無い)ため、
    // ここでは移行せず native に残す(「未対応キーは消さず残す」既定方針どおり)。
    lightarmor_setamount_add: "armor-set-bonus",
    heavyarmor_setamount_add: "armor-set-bonus"
  };
  function migrateLegacyNative(obj) {
    if (!obj || !obj.native || typeof obj.native !== "object" || Array.isArray(obj.native)) return;
    const native = obj.native;
    for (const [oldKey, newKey] of Object.entries(LEGACY_NATIVE_TO_BUFF)) {
      if (!Object.prototype.hasOwnProperty.call(native, oldKey)) continue;
      if (!obj.buffs || typeof obj.buffs !== "object" || Array.isArray(obj.buffs)) obj.buffs = {};
      obj.buffs[newKey] = Number(obj.buffs[newKey] || 0) + Number(native[oldKey] || 0);
      delete native[oldKey];
    }
    const coatingKeys = ["lightweapons_coatingunlocked_toggle", "heavyweapons_coatingunlocked_toggle"];
    if (coatingKeys.some((key) => Object.prototype.hasOwnProperty.call(native, key))) {
      if (!Array.isArray(obj["dedicated-effects"])) obj["dedicated-effects"] = [];
      if (!obj["dedicated-effects"].some((effect) => effect && effect.id === "feature:weapon-coating-unlock")) {
        obj["dedicated-effects"].push({ id: "feature:weapon-coating-unlock" });
      }
      coatingKeys.forEach((key) => delete native[key]);
    }
    if (Object.keys(native).length === 0) delete obj.native;
  }
  // 未使用の "node-N" 連番IDを生成する。
  function nextNodeId(nodes) {
    let i = 1;
    while (Object.prototype.hasOwnProperty.call(nodes, `node-${i}`)) i++;
    return `node-${i}`;
  }

  // map のキー順で id を delta 分だけ移動する (表示順の入替のみ・値/参照は不変)。
  function moveKey(map, id, delta) {
    const keys = Object.keys(map);
    const i = keys.indexOf(id);
    if (i < 0) return;
    const j = i + delta;
    if (j < 0 || j >= keys.length) return;
    keys.splice(j, 0, keys.splice(i, 1)[0]);
    const rebuilt = {};
    for (const k of keys) rebuilt[k] = map[k];
    for (const k of Object.keys(map)) delete map[k];
    Object.assign(map, rebuilt);
  }

  // バフ候補は item-stat config と同じ全ステ辞書 (lore由来 STAT_LIST + フォールバック)。
  // 日本語ラベル付きプルダウン(window.statSelect)で選ぶ。英語固定リストは廃止。
  function buffStatList() {
    const primary = (window.STAT_LIST && window.STAT_LIST.length) ? window.STAT_LIST : [];
    const fallback = window.FALLBACK_STATS || [];
    const seen = new Set();
    const out = [];
    for (const k of primary.concat(fallback)) { if (!seen.has(k)) { seen.add(k); out.push(k); } }
    // item-stat と同じカテゴリ順（攻撃→守備→補助→Ars→その他）を保つ。
    // 実際のセレクトも window.statSelect を使うため、追加時の既定キーと表示順が食い違わない。
    const rank = { attack: 0, defense: 1, support: 2, ars: 3, other: 4 };
    return out.sort((a, b) => {
      const ma = (window.STAT_META && window.STAT_META[a]) || {};
      const mb = (window.STAT_META && window.STAT_META[b]) || {};
      const ca = ma.category || (typeof window.inferStatCategory === "function" ? window.inferStatCategory(a) : "other");
      const cb = mb.category || (typeof window.inferStatCategory === "function" ? window.inferStatCategory(b) : "other");
      const ra = rank[ca] == null ? 9 : rank[ca];
      const rb = rank[cb] == null ? 9 : rank[cb];
      if (ra !== rb) return ra - rb;
      const oa = Number(ma.order == null ? 1000 : ma.order);
      const ob = Number(mb.order == null ? 1000 : mb.order);
      return oa !== ob ? oa - ob : String(a).localeCompare(String(b));
    });
  }

  const UNSET_LAYER = "__unset__";

  function normStat(key) {
    return String(key || "").trim().toLowerCase().replace(/_/g, "-");
  }

  function multiplierLayersFor(stat) {
    const layers = Array.isArray(window.MULTIPLIER_LAYERS) ? window.MULTIPLIER_LAYERS : [];
    return layers.filter((layer) => layer && layer.id
      && (!layer.stat || normStat(layer.stat) === normStat(stat)));
  }

  function multiplierRows(obj, rootKey) {
    const rows = [];
    const root = obj[rootKey];
    if (!root || typeof root !== "object" || Array.isArray(root)) return rows;
    for (const [layerId, stats] of Object.entries(root)) {
      if (!stats || typeof stats !== "object" || Array.isArray(stats)) continue;
      for (const stat of Object.keys(stats)) rows.push({ layerId, stat });
    }
    return rows;
  }

  function cleanupMultiplierLayer(obj, rootKey, layerId) {
    if (!obj[rootKey] || typeof obj[rootKey] !== "object") return;
    const layer = obj[rootKey][layerId];
    if (!layer || typeof layer !== "object" || Object.keys(layer).length === 0) {
      delete obj[rootKey][layerId];
    }
    if (Object.keys(obj[rootKey]).length === 0) delete obj[rootKey];
  }

  function firstFreeLayer(obj, rootKey, stat) {
    const used = new Set(multiplierRows(obj, rootKey).filter((row) => row.stat === stat).map((row) => row.layerId));
    return multiplierLayersFor(stat).find((layer) => !used.has(layer.id))?.id || null;
  }

  // buffs(加算) + multipliers(乗算モード) の描画。ノード/プレステージで共通利用する。
  function buffsSection(obj, buffsKey, title, description) {
    const supportsMultipliers = buffsKey === "buffs" || buffsKey === "mainhand-buffs";
    const multiplierKey = buffsKey === "mainhand-buffs" ? "mainhand-multipliers" : "multipliers";
    const box = h("div", {});
    box.appendChild(window.subTitleEl(
      title || "バフ (buffs / multipliers)",
      description || "加算モードは総合ステータスへ加算。乗算モードは同一レイヤ内を足し合わせ、レイヤ間を乗算して総合値へ適用します。"
    ));
    // カテゴリ絞り込み(要望2026-07-26: ステが増えてきたので追加候補をカテゴリで絞れるように)。
    // カテゴリのID→日本語ラベルは tf-lore.js の LORE_CATEGORIES をそのまま再利用する(重複定義しない)。
    // カテゴリ一覧自体は window.STAT_META に実際に出現する category 値からハードコードせず動的生成する。
    let categoryFilter = "";
    function statCategoryOf(key) {
      const meta = (window.STAT_META && window.STAT_META[key]) || {};
      return meta.category || "other";
    }
    function categoryLabel(id) {
      const found = (Array.isArray(window.LORE_CATEGORIES) ? window.LORE_CATEGORIES : []).find((entry) => entry[0] === id);
      return found ? found[1] : id;
    }
    function categoryFilterOptions() {
      const seen = new Set();
      for (const k of buffStatList()) seen.add(statCategoryOf(k));
      const known = (Array.isArray(window.LORE_CATEGORIES) ? window.LORE_CATEGORIES : []).map((entry) => entry[0]);
      const ordered = known.filter((id) => seen.has(id))
        .concat(Array.from(seen).filter((id) => !known.includes(id)).sort());
      const opts = [{ value: "", primary: "すべて", secondary: "" }];
      for (const id of ordered) opts.push({ value: id, primary: categoryLabel(id), secondary: id });
      return opts;
    }
    function passesCategoryFilter(key) {
      return !categoryFilter || statCategoryOf(key) === categoryFilter;
    }
    const filterRow = h("div", { class: "stat-row skilltree-category-filter" });
    filterRow.appendChild(h("span", { class: "mini-label", text: "カテゴリ絞り込み" }));
    filterRow.appendChild(window.listSelect({
      value: categoryFilter,
      options: categoryFilterOptions,
      className: "stat-category-select",
      onChange: (v) => { categoryFilter = v || ""; render(); }
    }));
    box.appendChild(filterRow);

    const rows = h("div", { class: "stat-rows" });
    box.appendChild(rows);

    function firstUnusedKey(map) {
      const filtered = buffStatList().filter(passesCategoryFilter);
      for (const k of filtered) if (!Object.prototype.hasOwnProperty.call(map, k)) return k;
      for (const k of buffStatList()) if (!Object.prototype.hasOwnProperty.call(map, k)) return k;
      return "attack-power";
    }

    function render() {
      rows.innerHTML = "";
      const map = obj[buffsKey] && typeof obj[buffsKey] === "object" ? obj[buffsKey] : null;
      const keys = map ? Object.keys(map) : [];
      const multRows = supportsMultipliers ? multiplierRows(obj, multiplierKey) : [];
      if (keys.length === 0 && multRows.length === 0) {
        rows.appendChild(emptyGuide("バフが未設定です。",
          "「+ バフ追加」でitem-statと同じステ辞書から選び、加算/乗算モードを設定します。"));
      }
      for (const key of keys) {
        // item-stat と同じ日本語ラベル付きステ選択 (Lore表示configの name+単位 表記・その他自由入力可)。
        const keySel = window.statSelect(key, (nv) => {
          if (!nv || nv === key) return false;
          if (Object.prototype.hasOwnProperty.call(map, nv)) {
            if (!supportsMultipliers) {
              alert("この条件バフには同じステータスを重複して登録できません。");
              return false;
            }
            // 同じステの2行目は、item-stats と同様に未使用レイヤの乗算行として追加する。
            const layerId = firstFreeLayer(obj, multiplierKey, nv);
            if (!layerId) {
              alert("このステータス用の未使用乗算レイヤがありません。先にロア表示設定でレイヤを追加してください。");
              return false;
            }
            delete map[key];
            if (Object.keys(map).length === 0) delete obj[buffsKey];
            if (!obj[multiplierKey] || typeof obj[multiplierKey] !== "object") obj[multiplierKey] = {};
            if (!obj[multiplierKey][layerId] || typeof obj[multiplierKey][layerId] !== "object") {
              obj[multiplierKey][layerId] = {};
            }
            obj[multiplierKey][layerId][nv] = 1.0;
            render();
            return true;
          }
          renameKey(map, key, nv);
          render();
          return true;
        }, passesCategoryFilter);
        // %ステは割合(0.0〜1.0)保存・%表示で入力する (item-statと同じ入力体験)。
        const valCtl = window.statValueControl
          ? window.statValueControl(key, map[key], (v) => { map[key] = v == null ? 0 : v; })
          : window.numberInput(map[key], (v) => { map[key] = v == null ? 0 : v; });
        const multToggle = window.checkboxInput(false, (enabled) => {
          if (!enabled) return;
          const layerId = firstFreeLayer(obj, multiplierKey, key);
          if (!layerId) {
            alert("このステータス用の未使用乗算レイヤがありません。先にロア表示設定で基準ステータスを指定した乗算レイヤを追加してください。");
            render();
            return;
          }
          delete map[key];
          if (Object.keys(map).length === 0) delete obj[buffsKey];
          if (!obj[multiplierKey] || typeof obj[multiplierKey] !== "object") obj[multiplierKey] = {};
          if (!obj[multiplierKey][layerId] || typeof obj[multiplierKey][layerId] !== "object") {
            obj[multiplierKey][layerId] = {};
          }
          obj[multiplierKey][layerId][key] = 1.0;
          render();
        });
        rows.appendChild(h("div", { class: "stat-row" }, [
          keySel, valCtl,
          // item-stat と同じ単位スロット (2文字分の固定幅で行の開始位置を揃える)。
          window.statUnitSlot ? window.statUnitSlot(key) : null,
          supportsMultipliers ? h("label", { class: "inline-check", title: "ONにするとこの行を乗算モードへ変更します" }, [
            multToggle, h("span", { class: "mini-label", text: "乗算" })
          ]) : null,
          h("button", {
            class: "btn-small danger", type: "button", text: "×",
            onclick: () => { delete map[key]; if (Object.keys(map).length === 0) delete obj[buffsKey]; render(); }
          })
        ]));
      }

      for (const { layerId, stat } of multRows) {
        const layer = obj[multiplierKey][layerId];
        const defs = multiplierLayersFor(stat);
        const currentDef = (Array.isArray(window.MULTIPLIER_LAYERS) ? window.MULTIPLIER_LAYERS : [])
          .find((candidate) => candidate.id === layerId);
        const options = defs.map((candidate) => ({
          value: candidate.id,
          primary: candidate.name || candidate.id,
          secondary: candidate.id
        }));
        if (!defs.some((candidate) => candidate.id === layerId)) {
          options.push({
            value: layerId,
            primary: `${currentDef ? currentDef.name : layerId} (未定義/基準ステ不一致)`,
            secondary: layerId
          });
        }
        const keySel = window.statSelect(stat, (next) => {
          if (!next || next === stat) return false;
          if (!multiplierLayersFor(next).some((candidate) => candidate.id === layerId)) {
            alert("現在のレイヤは変更先ステータス用に定義されていません。先にレイヤを変更してください。");
            return false;
          }
          if (Object.prototype.hasOwnProperty.call(layer, next)) {
            alert("同じレイヤに同一ステータスが既にあります");
            return false;
          }
          layer[next] = layer[stat];
          delete layer[stat];
          render();
          return true;
        }, passesCategoryFilter);
        const value = h("div", { class: "stat-row" }, [
          h("span", { class: "mult-prefix", text: "x" }),
          window.numberInput(layer[stat], (v) => { layer[stat] = v == null ? 1 : v; })
        ]);
        const multToggle = window.checkboxInput(true, (enabled) => {
          if (enabled) return;
          if (map && Object.prototype.hasOwnProperty.call(map, stat)) {
            alert("このステータスの加算モードは既に設定済みです。先に加算行を削除してください。");
            render();
            return;
          }
          if (!obj[buffsKey] || typeof obj[buffsKey] !== "object") obj[buffsKey] = {};
          obj[buffsKey][stat] = 0;
          delete layer[stat];
          cleanupMultiplierLayer(obj, multiplierKey, layerId);
          render();
        });
        const layerSelect = window.listSelect({
          value: layerId,
          options,
          onCommit: (nextLayer) => {
            if (!nextLayer || nextLayer === layerId) return false;
            if (!obj[multiplierKey][nextLayer] || typeof obj[multiplierKey][nextLayer] !== "object") {
              obj[multiplierKey][nextLayer] = {};
            }
            if (Object.prototype.hasOwnProperty.call(obj[multiplierKey][nextLayer], stat)) {
              alert("選択先レイヤに同一ステータスが既にあります");
              return false;
            }
            obj[multiplierKey][nextLayer][stat] = layer[stat];
            delete layer[stat];
            cleanupMultiplierLayer(obj, multiplierKey, layerId);
            render();
            return true;
          }
        });
        rows.appendChild(h("div", { class: "stat-row mult-row" }, [
          keySel, value,
          window.statUnitSlot ? window.statUnitSlot(null) : null,
          h("label", { class: "inline-check" }, [
            multToggle, h("span", { class: "mini-label", text: "乗算" })
          ]),
          layerSelect,
          h("button", {
            class: "btn-small danger", type: "button", text: "×",
            onclick: () => {
              delete layer[stat];
              cleanupMultiplierLayer(obj, multiplierKey, layerId);
              render();
            }
          })
        ]));
      }
    }

    render();
    box.appendChild(h("div", { class: "skilltree-add-row" }, [
      h("button", {
        class: "btn-small", type: "button", text: "+ バフ追加",
        onclick: () => {
          if (!obj[buffsKey] || typeof obj[buffsKey] !== "object") obj[buffsKey] = {};
          const nk = firstUnusedKey(obj[buffsKey]);
          obj[buffsKey][nk] = 0;
          render();
        }
      })
    ]));
    return box;
  }

  // set-buffs(装備部位数条件バフ)の描画。light_armor / heavy_armor ツリーのノード/プレステージ専用。
  // 段は3部位/4部位の2枠固定(1/2/5以上は不正)。乗算モード用の別キーは持たない(このスキーマに乗算モード枠は無い)。
  function setBuffsSection(obj) {
    const box = h("div", {});
    box.appendChild(window.subTitleEl("セット条件バフ (set-buffs)",
      "所属ツリーの防具を指定部位数以上装備している間だけ加算。成立している最大の段だけが採用される"
        + "(3と4の両方は加算されない)。"));

    function tierMap(tier, create) {
      if (!obj["set-buffs"] || typeof obj["set-buffs"] !== "object") {
        if (!create) return null;
        obj["set-buffs"] = {};
      }
      if (!obj["set-buffs"][tier] || typeof obj["set-buffs"][tier] !== "object") {
        if (!create) return null;
        obj["set-buffs"][tier] = {};
      }
      return obj["set-buffs"][tier];
    }
    function cleanupTier(tier) {
      const m = tierMap(tier, false);
      if (m && Object.keys(m).length === 0) delete obj["set-buffs"][tier];
      if (obj["set-buffs"] && Object.keys(obj["set-buffs"]).length === 0) delete obj["set-buffs"];
    }
    function firstUnusedSetBuffKey(map) {
      for (const k of buffStatList()) if (!Object.prototype.hasOwnProperty.call(map, k)) return k;
      return "dodge-chance";
    }

    for (const tier of [3, 4]) {
      const tierBox = h("div", { class: "set-buffs-tier" });
      tierBox.appendChild(h("div", { class: "mini-label", text: `${tier}部位以上` }));
      const rows = h("div", { class: "stat-rows" });
      tierBox.appendChild(rows);

      function render() {
        rows.innerHTML = "";
        const m = tierMap(tier, false);
        const keys = m ? Object.keys(m) : [];
        if (keys.length === 0) {
          rows.appendChild(emptyGuide(`${tier}部位段は未設定です。`, "「+ バフ追加」で追加できます。"));
        }
        for (const key of keys) {
          const keySel = window.statSelect(key, (nv) => {
            if (!nv || nv === key) return false;
            const mm = tierMap(tier, true);
            if (Object.prototype.hasOwnProperty.call(mm, nv)) {
              alert("この段には同じステータスを重複して登録できません。");
              return false;
            }
            renameKey(mm, key, nv);
            render();
            return true;
          });
          const mm = tierMap(tier, true);
          const valCtl = window.statValueControl
            ? window.statValueControl(key, mm[key], (v) => { mm[key] = v == null ? 0 : v; })
            : window.numberInput(mm[key], (v) => { mm[key] = v == null ? 0 : v; });
          rows.appendChild(h("div", { class: "stat-row" }, [
            keySel, valCtl,
            window.statUnitSlot ? window.statUnitSlot(key) : null,
            h("button", {
              class: "btn-small danger", type: "button", text: "×",
              onclick: () => { delete mm[key]; cleanupTier(tier); render(); }
            })
          ]));
        }
      }

      render();
      tierBox.appendChild(h("div", { class: "skilltree-add-row" }, [
        h("button", {
          class: "btn-small", type: "button", text: "+ バフ追加",
          onclick: () => {
            const mm = tierMap(tier, true);
            const nk = firstUnusedSetBuffKey(mm);
            mm[nk] = 0;
            render();
          }
        })
      ]));
      box.appendChild(tierBox);
    }
    return box;
  }

  // TF native rewards — string key → number (or string) map。
  // 候補は labels.js の NATIVE_PERK_META を正とする (製材ボーナス等の伐採キー含む)。
  function nativePresetKeys() {
    const L = window.LABELS;
    if (L && L.NATIVE_PERK_META && typeof L.NATIVE_PERK_META === "object") {
      return Object.keys(L.NATIVE_PERK_META);
    }
    return [];
  }

  function unitBadge(text) {
    if (!text) return null;
    return h("span", { class: "field-unit", title: "入力単位", text: text });
  }

  function nativePerkOptionText(key) {
    const L = window.LABELS;
    const ja = L && typeof L.nativePerkLabel === "function" ? L.nativePerkLabel(key) : key;
    const unit = L && typeof L.nativePerkUnit === "function" ? L.nativePerkUnit(key) : "";
    const base = (ja && ja !== key) ? `${ja} (${key})` : key;
    return unit ? `${base} ［${unit}］` : base;
  }

  // バフの statSelect と同じ操作感: 日本語プルダウン + 「その他(自由入力)」。
  function nativeSelect(currentKey, onCommit) {
    const CUSTOM = "__custom__";
    const cur = currentKey == null ? "" : String(currentKey);

    function buildOptions() {
      const keys = nativePresetKeys().slice();
      if (cur && !keys.includes(cur)) keys.unshift(cur);
      const opts = keys.map((k) => {
        const L = window.LABELS;
        const ja = L && typeof L.nativePerkLabel === "function" ? L.nativePerkLabel(k) : k;
        const unit = L && typeof L.nativePerkUnit === "function" ? L.nativePerkUnit(k) : "";
        const primary = unit
          ? ((ja && ja !== k ? ja : k) + ` ［${unit}］`)
          : (ja && ja !== k ? ja : k);
        return {
          value: k,
          primary,
          secondary: ja && ja !== k ? k : "",
          title: nativePerkOptionText(k)
        };
      });
      opts.push({ value: CUSTOM, primary: "その他(自由入力)…", secondary: "" });
      return opts;
    }

    return window.listSelect({
      value: cur || undefined,
      options: buildOptions,
      allowCustom: true,
      customValue: CUSTOM,
      customPlaceholder: "任意のTFネイティブ効果キー",
      className: "stat-select native-select",
      onCommit: (nv) => {
        if (!nv) return false;
        return onCommit(nv);
      }
    });
  }

  function nativeSection(obj, nativeKey) {
    const box = h("div", {});
    box.appendChild(h("div", { class: "sub-title", text: "ネイティブ効果 (TF native rewards)" }));
    box.appendChild(h("div", {
      class: "field-desc",
      style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 6px;",
      text: "数値はキー横の単位に従って入力（例: 割合は 0〜1、tick は 20=1秒）。製材ボーナスは「伐採: 製材ボーナス」。"
    }));
    const rows = h("div", { class: "stat-rows" });
    box.appendChild(rows);

    function firstUnusedKey(map) {
      for (const k of nativePresetKeys()) if (!Object.prototype.hasOwnProperty.call(map, k)) return k;
      return "arssmithing_craftqualitybonus_add";
    }

    function render() {
      rows.innerHTML = "";
      const map = obj[nativeKey] && typeof obj[nativeKey] === "object" ? obj[nativeKey] : null;
      const keys = map ? Object.keys(map) : [];
      if (keys.length === 0) {
        rows.appendChild(emptyGuide(
          "ネイティブが未設定です。",
          "「+ ネイティブ追加」でTFネイティブ効果を日本語メニューから選び、値を追加します。"
        ));
      }
      for (const key of keys) {
        const keySel = nativeSelect(key, (nv) => {
          if (!nv || nv === key) return false;
          if (Object.prototype.hasOwnProperty.call(map, nv)) { alert("同じキーが既にあります"); return false; }
          renameKey(map, key, nv);
          render();
          return true;
        });
        const val = map[key];
        const valCtl = typeof val === "number" || val == null
          ? window.numberInput(val, (v) => { map[key] = v == null ? 0 : v; })
          : window.textInput(val, (v) => { map[key] = v; });
        const L = window.LABELS;
        const unit = L && typeof L.nativePerkUnit === "function" ? L.nativePerkUnit(key) : "";
        const rowKids = [keySel, valCtl];
        const badge = unitBadge(unit);
        if (badge) rowKids.push(badge);
        rowKids.push(h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => { delete map[key]; if (Object.keys(map).length === 0) delete obj[nativeKey]; render(); }
        }));
        rows.appendChild(h("div", { class: "stat-row" }, rowKids));
      }
    }

    render();
    box.appendChild(h("div", { class: "form-actions" }, [
      h("button", {
        class: "btn-small", type: "button", text: "+ ネイティブ追加",
        onclick: () => {
          if (!obj[nativeKey] || typeof obj[nativeKey] !== "object") obj[nativeKey] = {};
          const nk = firstUnusedKey(obj[nativeKey]);
          obj[nativeKey][nk] = 0;
          render();
        }
      })
    ]));
    return box;
  }

  // 要件⑤: 自由記述の説明(description)。obj.description を正とし、無ければ
  // obj["effect-text"](旧キー)を初期表示だけに使う。編集すると必ず obj.description に書く
  // (旧 effect-text キーには一切触れない。既存データがあれば温存されたまま残る)。
  function descriptionSection(obj) {
    const initial = typeof obj.description === "string" ? obj.description
        : (typeof obj["effect-text"] === "string" ? obj["effect-text"] : "");
    // フレーバー説明文 (lore) と同じリッチ着色入力 (legacy &コード)。複数行対応: 1行=GUIの1行として
    // 「+ 行追加」で増やせる。保存は \n 結合の単一文字列 (obj.description)。TF側は \n で行分割し
    // legacy & で着色して表示する。空(全行なし)のときはキー自体を書かない。
    const lines = initial === "" ? [] : initial.split("\n");
    const usedLegacyFallback = typeof obj.description !== "string" && typeof obj["effect-text"] === "string";
    function sync() {
      const joined = lines.join("\n");
      if (joined === "") delete obj.description;
      else obj.description = joined;
    }
    const rowsHost = h("div", { class: "lore-rows-host" });
    function rerenderRows() {
      rowsHost.innerHTML = "";
      // renderLoreRows は loreArray を直接ミューテートする。onEdit=軽い同期、onStructureChange=行の増減後に再描画。
      rowsHost.appendChild(window.renderLoreRows(lines, "legacy", sync, () => { sync(); rerenderRows(); }));
    }
    rerenderRows();
    return h("div", { class: "skilltree-desc" }, [
      field("description", rowsHost, {
        label: "フレーバー説明文 (description)",
        desc: "自由記述のノード説明(フレーバー)。「+ 行追加」で複数行にできます(1行=GUIの1行)。&コードで着色可。TFスキルGUIへ表示されます。"
            + (usedLegacyFallback ? " (旧 effect-text から初期表示。編集すると description として保存されます)" : "")
      })
    ]);
  }

  // 解放効果セクションの「種別」選択メニュー。
  const GATE_TYPE_OPTIONS = [
    { value: "glyph", primary: "グリフ解放" },
    { value: "brew", primary: "醸造解放" },
    { value: "trade", primary: "取引解放" },
    { value: "recipe", primary: "レシピゲート" },
    { value: "drop", primary: "ドロップ解放" },
    { value: "feature", primary: "機能解放" },
    { value: "overenchant", primary: "オーバーエンチャ" },
    { value: "ars-tier", primary: "ArsTier" },
    { value: "reward", primary: "特殊報酬" }
  ];
  const DROP_PROFESSIONS = [
    { value: "mining", primary: "採掘" },
    { value: "woodcutting", primary: "伐採" },
    { value: "digging", primary: "掘削" },
    { value: "fishing", primary: "釣り" }
  ];

  // 種別選択直後に追加する既定エントリを作る。語彙が空で選べない種別は null (呼び出し側でalert)。
  function buildDefaultGatePlacement(type, vocab) {
    switch (type) {
      case "glyph": {
        const first = vocab.glyphs[0];
        return first ? { id: `glyph:${first.key}` } : null;
      }
      case "brew": {
        const first = vocab.brews[0];
        return first ? { id: `brew:${first}` } : null;
      }
      case "trade": {
        const first = vocab.trades[0];
        return first ? { id: `trade:${first}` } : null;
      }
      case "recipe": {
        const firstRecipe = vocab.recipes[0];
        if (firstRecipe) return { id: `recipe:${firstRecipe}` };
        const firstRitual = vocab.rituals[0];
        return firstRitual ? { id: `ritual:${firstRitual}` } : null;
      }
      case "drop": return { id: "drop:mining:" };
      case "feature": {
        const first = vocab.features[0];
        if (!first) return null;
        const placement = { id: `feature:${first.id}` };
        if (first.param === "level") placement.value = 0;
        return placement;
      }
      case "overenchant": {
        const first = vocab.overenchants[0];
        return first ? { id: `overenchant:${first}` } : null;
      }
      case "ars-tier": return { id: "ars-tier", value: 1 };
      case "reward": {
        const first = vocab.specialRewards[0];
        return first ? { id: `reward:${first}` } : null;
      }
      default: return null;
    }
  }

  // 解放効果(dedicated-effects, プレフィックス付き動的ID)セクション。
  // 種別ごとに専用UIを出し、生成/編集は常に「プレフィックス:target」形式のIDを直接書き換える。
  // 旧形式(非プレフィックス)のIDは変換せず、警告表示のみで残す(削除は可)。
  // unique 種別が duplicateIds に含まれていれば警告行を出す。ars-tier は加算型のため対象外。
  // ワイルドカード(glob)を大文字小文字無視の完全一致正規表現へ変換する。* = 任意長, ? = 任意1文字。
  // 他の正規表現メタ文字はリテラル扱いにエスケープする。空/不正時は例外。
  function globToRegex(glob) {
    const src = String(glob == null ? "" : glob).trim();
    if (!src) throw new Error("empty pattern");
    const escaped = src
      .replace(/[.+^${}()|[\]\\]/g, "\\$&") // * と ? 以外のメタ文字をエスケープ
      .replace(/\*/g, ".*")
      .replace(/\?/g, ".");
    return new RegExp("^" + escaped + "$", "i");
  }

  // タスク2 (2026-07-26): feature:<id>(param="scale")のtier値セレクトメニュー向け純関数。
  // DOM を持たず、定義済みtier配列と現在値から「選択肢一覧」と「現在値が未定義tierかどうか」を
  // 返すだけ。未定義値(その機構の tiers に無い数値、または旧データ由来のゴミ値)は黙って消さず、
  // 選択肢の先頭に警告付きで残す(呼び出し側でラベルを組み立てる)。
  // @param {number[]} definedTiers その機構(gimmick yml)に実在するtier番号(昇順)
  // @param {number|null|undefined} currentValue placement.value (未設定 = tier1相当)
  // @returns {{options:Array<{value:string,primary:string}>, currentValue:string, isKnownValue:boolean}}
  function buildTierSelectOptions(definedTiers, currentValue) {
    const tiers = Array.isArray(definedTiers)
      ? [...new Set(definedTiers)].filter((n) => Number.isInteger(n) && n > 0).sort((a, b) => a - b)
      : [];
    const options = [{ value: "", primary: "空欄(tier1相当)" }];
    for (const t of tiers) options.push({ value: String(t), primary: `tier ${t}` });
    const cur = currentValue == null ? "" : String(currentValue);
    const isKnownValue = options.some((o) => o.value === cur);
    return { options, currentValue: cur, isKnownValue };
  }
  window.TIER_SELECT_LOGIC = { buildTierSelectOptions };

  function unlockEffectsSection(node, duplicateIdsGetter, vocabulary) {
    const GATE = window.GATE_EFFECTS;
    const vocab = vocabulary || {
      glyphs: [], brews: [], trades: [], features: [], overenchants: [], drops: [], specialRewards: [], recipes: [], rituals: [], featureTiers: {}
    };
    if (!vocab.featureTiers || typeof vocab.featureTiers !== "object") vocab.featureTiers = {};

    const box = h("div", {});
    box.appendChild(h("div", { class: "sub-title", text: "解放効果 (dedicated-effects)" }));
    box.appendChild(h("div", {
      class: "field-desc",
      style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 6px;",
      text: "習得すると解放される機能・レシピ・儀式エフェクト・取引・グリフ等。レシピゲート内でクラフトレシピまたは儀式エフェクトを選びます。数値報酬はネイティブ効果側を使ってください。"
    }));
    const rows = h("div", { class: "stat-rows" });
    box.appendChild(rows);

    function sortedGlyphOptions(currentKey) {
      const sorted = vocab.glyphs.slice().sort((a, b) => {
        const ca = a.category || "", cb = b.category || "";
        if (ca !== cb) return ca < cb ? -1 : 1;
        return a.displayName < b.displayName ? -1 : (a.displayName > b.displayName ? 1 : 0);
      });
      const opts = sorted.map((g) => ({
        value: g.key,
        primary: g.category ? `[${g.category}] ${g.displayName}` : g.displayName,
        secondary: g.key
      }));
      if (currentKey && !opts.some((o) => o.value === currentKey)) {
        opts.unshift({ value: currentKey, primary: `${currentKey} (語彙外)`, secondary: currentKey });
      }
      return opts;
    }

    function plainOptions(list, currentValue) {
      const opts = (list || []).slice().sort().map((v) => ({ value: v, primary: v }));
      if (currentValue && !opts.some((o) => o.value === currentValue)) {
        opts.unshift({ value: currentValue, primary: `${currentValue} (語彙外)` });
      }
      return opts;
    }

    function featureOf(id) {
      return vocab.features.find((f) => f.id === id) || null;
    }

    function featureOptions(currentId) {
      const opts = vocab.features.map((f) => ({
        value: f.id, primary: `${f.label} (${f.id})`, secondary: f.id
      }));
      if (currentId && !opts.some((o) => o.value === currentId)) {
        opts.unshift({ value: currentId, primary: `${currentId} (語彙外)`, secondary: currentId });
      }
      return opts;
    }

    function dropCategoryOptions(profession, currentCategoryId) {
      const opts = vocab.drops
        .filter((d) => d.profession === profession)
        .map((d) => ({ value: d.categoryId, primary: d.displayName, secondary: d.categoryId }));
      if (currentCategoryId && !opts.some((o) => o.value === currentCategoryId)) {
        opts.unshift({ value: currentCategoryId, primary: `${currentCategoryId} (語彙外)`, secondary: currentCategoryId });
      }
      return opts;
    }

    // ---- 種別ごとの行内コントロール。onIdChange(newId) が呼ばれると placement.id を書き換えて再描画する。----
    function renderGlyphRow(target, onIdChange) {
      return [window.listSelect({
        value: target, options: sortedGlyphOptions(target), placeholder: "グリフを選択…",
        onCommit: (v) => { if (!v || v === target) return false; onIdChange(`glyph:${v}`); return true; }
      })];
    }
    function renderBrewRow(target, onIdChange) {
      return [window.listSelect({
        value: target, options: plainOptions(vocab.brews, target), placeholder: "醸造グループを選択…",
        onCommit: (v) => { if (!v || v === target) return false; onIdChange(`brew:${v}`); return true; }
      })];
    }
    // 2026-07-29: 職業は WEAPONSMITH 等の英字 enum が保存値なので、表示だけ labels.js の
    // 和名へ差し替える (村人取引タブのセレクトと同じ辞書)。
    function renderTradeRow(target, onIdChange) {
      const opts = plainOptions(vocab.trades, target).map((o) => {
        // plainOptions は語彙外の現在値に「(語彙外)」を付ける。その注記は消さない。
        if (o.primary !== o.value) return o;
        const ja = window.LABELS ? window.LABELS.professionLabel(o.value) : o.value;
        return { value: o.value, primary: ja, secondary: o.value };
      });
      return [window.listSelect({
        value: target, options: opts, placeholder: "職業を選択…",
        onCommit: (v) => { if (!v || v === target) return false; onIdChange(`trade:${v}`); return true; }
      })];
    }
    // ワイルドカード(*/?)一致で、カタログのクラフトレシピ/儀式エフェクトを recipe:<id> / ritual:<id> として
    // まとめて dedicated-effects へ追加する。既存IDはスキップ。kind = "recipe" | "ritual"。
    // 注記: recipe: ゲートは items/catalog.yml のワークベンチレシピ(カタログ出力ID)のみが対象。
    //       バニラレシピはこのゲート機構の対象外(語彙 vocab.recipes に含まれない)。
    /**
     * ワイルドカード一括追加モーダル (2026-07-27 UI改善)。
     *
     * <p>旧UIは「行内の細い入力欄 + 一括追加ボタン」で、押すまで何件どれが入るのか分からず、
     * 結果は alert で事後報告されるだけだった。入力しながら一致結果を出し、
     * 追加するものを個別に外せる形へ変えた(追加済みは選べないよう固定表示)。
     */
    function openWildcardModal(kind) {
      const kindLabel = kind === "ritual" ? "儀式エフェクト" : "クラフトレシピ";
      const source = (kind === "ritual" ? vocab.rituals : vocab.recipes) || [];
      if (!Array.isArray(source) || source.length === 0) {
        alert(`${kindLabel}が定義されていません。`);
        return;
      }
      if (!Array.isArray(node["dedicated-effects"])) node["dedicated-effects"] = [];
      const existing = new Set(node["dedicated-effects"].map((p) => String(p && p.id)));
      const ids = source.map(String);
      /** チェックを外したID(既定は全選択なので、外したものだけ覚える)。 */
      const deselected = new Set();

      // クラフトレシピはカタログIDなので表示名を引ける(CUSTOM_ITEM_LABELS、catalogタブを
      // 開いた後なら埋まっている)。儀式エフェクトIDは items.yml ritual_effects の自己記述的な
      // slugで、対応する表示名フィールドが無いため生IDのまま(フォールバック無しで恒常的に生ID)。
      function matchLabel(id) {
        if (kind !== "recipe") return id;
        const labels = window.CUSTOM_ITEM_LABELS || {};
        return labels[`custom:${id}`] || labels[`custom:${String(id).toLowerCase()}`] || id;
      }

      const overlay = h("div", { class: "modal-overlay" });
      const close = () => { if (overlay.parentNode) overlay.parentNode.removeChild(overlay); };
      const summary = h("div", { class: "gate-bulk-summary" });
      const list = h("div", { class: "gate-bulk-list" });
      const addBtn = h("button", { class: "btn primary", type: "button", text: "追加" });

      let pattern = "*";
      const patInput = window.textInput("*", (v) => { pattern = v; renderMatches(); },
        "* / great_* / *_sword");
      patInput.classList.add("gate-bulk-pattern");

      function matchedIds() {
        let re;
        try { re = globToRegex(pattern); } catch (_) { return null; }
        return ids.filter((id) => re.test(id));
      }

      function selectedIds() {
        const matches = matchedIds() || [];
        return matches.filter((id) => !existing.has(`${kind}:${id}`) && !deselected.has(id));
      }

      function renderMatches() {
        list.textContent = "";
        const matches = matchedIds();
        if (matches === null) {
          summary.textContent = "パターンを入力してください（* = 任意の文字列, ? = 1文字）。";
          addBtn.disabled = true;
          return;
        }
        const already = matches.filter((id) => existing.has(`${kind}:${id}`));
        const selectable = matches.length - already.length;
        summary.textContent = `一致 ${matches.length}件 / 追加できる ${selectable}件`
          + (already.length ? ` / 追加済み ${already.length}件` : "");
        if (matches.length === 0) {
          list.appendChild(h("div", { class: "empty-hint", text: "一致するIDがありません。" }));
          addBtn.disabled = true;
          return;
        }
        for (const id of matches) {
          const isExisting = existing.has(`${kind}:${id}`);
          const row = h("label", { class: "gate-bulk-row" + (isExisting ? " is-existing" : "") });
          const box = h("input", { type: "checkbox" });
          box.checked = !isExisting && !deselected.has(id);
          box.disabled = isExisting;
          box.addEventListener("change", () => {
            if (box.checked) deselected.delete(id); else deselected.add(id);
            updateAddButton();
          });
          row.appendChild(box);
          const label = matchLabel(id);
          row.appendChild(h("span", { class: "gate-bulk-id", text: label }));
          if (label !== id) {
            row.appendChild(h("span", { class: "entry-sum-id", text: id }));
          }
          if (isExisting) {
            row.appendChild(h("span", { class: "gate-bulk-tag", text: "追加済み" }));
          }
          list.appendChild(row);
        }
        updateAddButton();
      }

      function updateAddButton() {
        const count = selectedIds().length;
        addBtn.disabled = count === 0;
        addBtn.textContent = count > 0 ? `${count}件を追加` : "追加";
      }

      addBtn.addEventListener("click", () => {
        const picked = selectedIds();
        for (const id of picked) {
          const gateId = `${kind}:${id}`;
          node["dedicated-effects"].push({ id: gateId });
          existing.add(gateId);
        }
        close();
        render();
      });

      const box = h("div", { class: "modal-box modal-box-wide" }, [
        h("div", { class: "modal-title", text: `一括追加: ${kindLabel}` }),
        h("div", { class: "modal-text", text:
          "パターンに一致するIDを解放効果へまとめて追加します。* = 任意の文字列 / ? = 1文字。"
          + (kind === "ritual" ? "" : " バニラレシピは対象外です（カタログのワークベンチレシピのみ）。") }),
        h("div", { class: "gate-bulk-controls" }, [
          patInput,
          h("button", { class: "btn-small", type: "button", text: "すべて選択",
            onclick: () => { deselected.clear(); renderMatches(); } }),
          h("button", { class: "btn-small", type: "button", text: "すべて解除",
            onclick: () => { for (const id of matchedIds() || []) deselected.add(id); renderMatches(); } })
        ]),
        summary,
        list,
        h("div", { class: "modal-actions" }, [
          addBtn,
          h("button", { class: "btn-small", type: "button", text: "キャンセル", onclick: close })
        ])
      ]);
      overlay.appendChild(box);
      overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });
      document.body.appendChild(overlay);
      renderMatches();
      patInput.focus();
    }

    // レシピゲート行内に出す「ワイルドカード一括追加」の起動ボタン。
    function wildcardControls(kind) {
      return [h("button", {
        class: "btn-small", type: "button", text: "一括追加…",
        title: "パターンに一致する" + (kind === "ritual" ? "儀式エフェクト" : "クラフトレシピ")
          + "をまとめて解放効果に追加します（追加前に一致結果を確認できます）。",
        onclick: () => openWildcardModal(kind)
      })];
    }

    function renderRecipeGateRow(prefix, target, onIdChange) {
      const isRitual = prefix === "ritual";
      const mode = window.listSelect({
        value: isRitual ? "ritual" : "recipe",
        options: [
          { value: "recipe", primary: "クラフトレシピ" },
          { value: "ritual", primary: "儀式エフェクト" }
        ],
        onCommit: (v) => {
          if (!v || v === (isRitual ? "ritual" : "recipe")) return false;
          const next = v === "ritual" ? vocab.rituals[0] : vocab.recipes[0];
          if (!next) { alert(v === "ritual" ? "儀式エフェクトが定義されていません。" : "クラフトレシピが定義されていません。"); return false; }
          onIdChange(`${v}:${next}`);
          return true;
        }
      });
      if (isRitual) {
        return [mode, window.listSelect({
          value: target, options: plainOptions(vocab.rituals, target), placeholder: "儀式エフェクトを選択…",
          onCommit: (v) => { if (!v || v === target) return false; onIdChange(`ritual:${v}`); return true; }
        }), ...wildcardControls("ritual")];
      }
      // レシピ素材欄と同一の Material / custom:<itemId> 入力を使う。
      // 実行時の recipe: ゲートはカタログIDを受けるため、保存時だけ custom: を除いて正規化する。
      const shown = String(target || "").startsWith("custom:") ? target : `custom:${target || ""}`;
      if (typeof window.setCustomItemCandidates === "function") {
        // 別タブを一度も開いていない場合でも、レシピゲートの候補は必ずサジェストする。
        window.setCustomItemCandidates(vocab.recipes, { replace: false });
      }
      return [mode, window.materialInput(shown, "material-list", (v) => {
        const raw = String(v == null ? "" : v).trim();
        const catalogId = raw.replace(/^custom:/i, "").trim();
        if (catalogId && catalogId !== target) onIdChange(`recipe:${catalogId}`);
      }, { allowCustom: true }), ...wildcardControls("recipe")];
    }
    function renderFeatureRow(placement, target, onIdChange) {
      const kids = [window.listSelect({
        value: target, options: featureOptions(target), placeholder: "機能を選択…",
        onCommit: (v) => { if (!v || v === target) return false; onIdChange(`feature:${v}`); return true; }
      })];
      const feat = featureOf(target);
      if (feat && (feat.param === "level" || feat.param === "scale")) {
        const GATE = window.GATE_EFFECTS;
        // タスク2 (2026-07-26): scale(tier)は自由数値入力ではなく、その機構(gimmick yml)に
        // 実際に定義済みのtierからセレクトで選べるようにする。定義済みtierが0件(=tierTableEditor側で
        // まだ1行も作っていない)の間は、実質「空欄(tier1相当)」以外に選びようが無く自由入力の方が
        // 実用的なので、その場合だけ従来どおりの数値入力にフォールバックする。既存値が候補に無い
        // 場合でも黙って消さず、警告付きの選択肢として残す(buildTierSelectOptions)。
        const definedTiers = feat.param === "scale" ? (vocab.featureTiers && vocab.featureTiers[feat.id]) : null;
        if (feat.param === "scale" && Array.isArray(definedTiers) && definedTiers.length > 0) {
          const built = window.TIER_SELECT_LOGIC.buildTierSelectOptions(definedTiers, placement.value);
          const opts = built.options.slice();
          if (!built.isKnownValue) {
            opts.unshift({
              value: built.currentValue,
              primary: `⚠ tier ${built.currentValue}(未定義)`,
              title: "この機構にはこのtierの行がまだ定義されていません。tierTableEditorで行を作るまでグローバル既定値にフォールバックします。"
            });
          }
          kids.push(window.listSelect({
            value: built.currentValue,
            options: opts,
            placeholder: "tierを選択…",
            onChange: (v) => {
              const n = v === "" ? null : Number(v);
              const edit = GATE.resolveFeatureValueEdit(feat.param, n);
              if (edit.remove) { delete placement.value; return; }
              placement.value = edit.value;
            }
          }));
        } else {
          kids.push(window.numberInput(placement.value, (v) => {
            const edit = GATE.resolveFeatureValueEdit(feat.param, v);
            if (edit.remove) { delete placement.value; return; }
            placement.value = edit.value;
          }, { int: true }));
        }
        // scale: value キー自体が無ければ Java側で tier1 扱いになる(FeatureEffectParam#defaultsMissingValue、
        // 2026-07-25 gather-rework-active-framework §1)。level は値が必須(欠落は読み込み時にdrop)。
        kids.push(unitBadge(feat.param === "scale" ? "段階(tier, 空欄=1)" : "段階"));
      }
      return kids;
    }
    function renderOverenchantRow(target, onIdChange) {
      return [window.listSelect({
        value: target, options: plainOptions(vocab.overenchants, target), placeholder: "オーバーエンチャIDを選択…",
        onCommit: (v) => { if (!v || v === target) return false; onIdChange(`overenchant:${v}`); return true; }
      })];
    }
    // 2026-07-29: 特殊報酬IDは機械名なので、gate-vocabulary が返すラベル
    // (「称号: 見習い」形式)を主表示にする。ラベルが無いIDは従来どおりIDのまま。
    function renderRewardRow(target, onIdChange) {
      const labels = (vocab && vocab.specialRewardLabels) || {};
      const opts = plainOptions(vocab.specialRewards, target).map((o) => {
        if (o.primary !== o.value || !labels[o.value]) return o;
        return { value: o.value, primary: labels[o.value], secondary: o.value };
      });
      return [window.listSelect({
        value: target, options: opts, placeholder: "特殊報酬を選択…",
        onCommit: (v) => { if (!v || v === target) return false; onIdChange(`reward:${v}`); return true; }
      })];
    }
    function renderArsTierRow(placement) {
      return [
        window.numberInput(placement.value, (v) => { placement.value = v == null ? 0 : v; }, { int: true }),
        unitBadge("ティア(+N加算)")
      ];
    }
    function renderDropRow(target, onIdChange) {
      const parsed = GATE.parseDropTarget(target);
      const kids = [];
      kids.push(window.listSelect({
        value: parsed.profession, options: DROP_PROFESSIONS.slice(), placeholder: "職業を選択…",
        onCommit: (v) => {
          if (!v || v === parsed.profession) return false;
          const rest = parsed.mode === "item" ? `item:${parsed.itemId || ""}` : (parsed.categoryId || "");
          onIdChange(`drop:${v}:${rest}`);
          return true;
        }
      }));
      kids.push(window.listSelect({
        value: parsed.mode,
        options: [{ value: "category", primary: "カテゴリ単位" }, { value: "item", primary: "アイテム単位" }],
        onCommit: (v) => {
          if (!v || v === parsed.mode) return false;
          onIdChange(v === "item" ? `drop:${parsed.profession}:item:` : `drop:${parsed.profession}:`);
          return true;
        }
      }));
      if (parsed.mode === "item") {
        kids.push(window.materialInput(parsed.itemId, "material-list", (v) => {
          onIdChange(`drop:${parsed.profession}:item:${(v == null ? "" : String(v)).trim()}`);
        }, { allowCustom: true }));
      } else {
        kids.push(window.listSelect({
          value: parsed.categoryId, options: dropCategoryOptions(parsed.profession, parsed.categoryId),
          placeholder: "カテゴリを選択…",
          onCommit: (v) => {
            if (!v || v === parsed.categoryId) return false;
            onIdChange(`drop:${parsed.profession}:${v}`);
            return true;
          }
        }));
      }
      return kids;
    }

    function typeRowChildren(placement, parsed, onIdChange) {
      switch (parsed.type) {
        case "glyph": return renderGlyphRow(parsed.target, onIdChange);
        case "brew": return renderBrewRow(parsed.target, onIdChange);
        case "trade": return renderTradeRow(parsed.target, onIdChange);
        case "recipe": return renderRecipeGateRow("recipe", parsed.target, onIdChange);
        // 既存 ritual: ID も「レシピゲート」の儀式エフェクト選択として表示する。
        case "ritual": return renderRecipeGateRow("ritual", parsed.target, onIdChange);
        case "drop": return renderDropRow(parsed.target, onIdChange);
        case "feature": return renderFeatureRow(placement, parsed.target, onIdChange);
        case "overenchant": return renderOverenchantRow(parsed.target, onIdChange);
        case "ars-tier": return renderArsTierRow(placement);
        case "reward": return renderRewardRow(parsed.target, onIdChange);
        default: return [];
      }
    }

    function render() {
      rows.innerHTML = "";
      const list = Array.isArray(node["dedicated-effects"]) ? node["dedicated-effects"] : null;
      const duplicateIds = duplicateIdsGetter();
      if (!list || list.length === 0) {
        rows.appendChild(emptyGuide("解放効果が未設定です。", "「+ 追加」で行を追加し、行内の種別セレクトで選びます。"));
      } else {
        list.forEach((placement, idx) => {
          const parsed = GATE.parseGateEffectId(placement.id);
          const rowChildren = [];
          if (!parsed) {
            // 旧形式(非プレフィックス)ID: このUIでは変換しない。表示・削除のみ許可して温存する。
            rowChildren.push(h("span", {
              class: "field-readonly", text: String(placement.id),
              title: "旧形式のID(要変換)。プレフィックス付き動的IDへの変換が必要です。"
            }));
            if (placement.value != null) {
              rowChildren.push(window.numberInput(placement.value, (v) => { placement.value = v == null ? 0 : v; }));
            }
            rowChildren.push(h("span", {
              class: "warn-badge", style: "color:#c0392b;font-weight:bold;",
              title: "プレフィックス付きIDへの変換が必要です(このUIでは編集できません)。",
              text: "⚠ 旧形式(要変換)"
            }));
          } else {
            // 種別は行内 select で選ぶ(バフUIと同じ操作性)。種別を変えると既定エントリへ作り直す。
            // recipe/ritual はどちらも「レシピゲート」種別として表示する(renderRecipeGateRow が両対応)。
            const displayType = parsed.type === "ritual" ? "recipe" : parsed.type;
            rowChildren.push(window.listSelect({
              value: displayType,
              options: GATE_TYPE_OPTIONS.slice(),
              className: "dedicated-effect-select",
              onCommit: (v) => {
                if (!v || v === displayType) return false;
                const rebuilt = buildDefaultGatePlacement(v, vocab);
                if (!rebuilt) { alert("この種別の語彙が読み込まれていません。"); return false; }
                list[idx] = rebuilt;
                render();
                return true;
              }
            }));
            rowChildren.push(...typeRowChildren(placement, parsed, (nv) => {
              if (nv === placement.id) return;
              placement.id = nv;
              render();
            }));
            if (GATE.isUniqueGateEffectType(parsed.type) && duplicateIds.has(parsed.raw)) {
              rowChildren.push(h("span", {
                class: "warn-badge", style: "color:#c0392b;font-weight:bold;",
                title: "この解放効果は1箇所限定ですが、同じ設定内の複数ノードに置かれています。",
                text: "⚠ 重複"
              }));
            }
          }
          rowChildren.push(h("button", {
            class: "btn-small danger", type: "button", text: "×",
            onclick: () => {
              list.splice(idx, 1);
              if (list.length === 0) delete node["dedicated-effects"];
              render();
            }
          }));
          rows.appendChild(h("div", { class: "stat-row" }, rowChildren));
        });
      }
    }

    render();

    // ---- 追加UI(バフUIと同じ): 左下「+ 追加」で既定行を1つ足し、種別は行内 select で選ぶ。----
    // 既定は glyph。語彙が無ければ他種別(drop/ars-tier は語彙不要で必ず作れる)から最初に作れるものを使う。
    box.appendChild(h("div", { class: "skilltree-add-row" }, [
      h("button", {
        class: "btn-small", type: "button", text: "+ 追加",
        onclick: () => {
          let placement = buildDefaultGatePlacement("glyph", vocab);
          if (!placement) {
            for (const opt of GATE_TYPE_OPTIONS) {
              placement = buildDefaultGatePlacement(opt.value, vocab);
              if (placement) break;
            }
          }
          if (!placement) { alert("追加できる解放効果の語彙がありません。"); return; }
          if (!Array.isArray(node["dedicated-effects"])) node["dedicated-effects"] = [];
          node["dedicated-effects"].push(placement);
          render();
        }
      })
    ]));
    return box;
  }

  // 解放効果セクションの語彙を一括供給するAPI。取得失敗時は全種別空の語彙(語彙外/カスタムのみ選べる状態)。
  async function fetchGateVocabulary() {
    try {
      const r = await fetch("/api/gate-vocabulary");
      if (!r.ok) throw new Error("gate-vocabulary failed");
      const json = await r.json();
      return {
        glyphs: Array.isArray(json.glyphs) ? json.glyphs : [],
        brews: Array.isArray(json.brews) ? json.brews : [],
        trades: Array.isArray(json.trades) ? json.trades : [],
        features: Array.isArray(json.features) ? json.features : [],
        overenchants: Array.isArray(json.overenchants) ? json.overenchants : [],
        drops: Array.isArray(json.drops) ? json.drops : [],
        specialRewards: Array.isArray(json.specialRewards) ? json.specialRewards : [],
        recipes: Array.isArray(json.recipes) ? json.recipes : [],
        rituals: Array.isArray(json.rituals) ? json.rituals : []
      };
    } catch (_) {
      return { glyphs: [], brews: [], trades: [], features: [], overenchants: [], drops: [], specialRewards: [], recipes: [], rituals: [] };
    }
  }

  // タスク2 (2026-07-26): feature:<id>(param="scale")のtier値をセレクトメニュー化するための語彙。
  // /api/tier-vocabulary は lib/gate-vocabulary.js とは別の新設エンドポイント(そちらは他作業者が
  // 編集中のため変更しない方針)。取得失敗時は全件空配列(= 従来どおりの自由数値入力にフォールバック)。
  async function fetchTierVocabulary() {
    try {
      const r = await fetch("/api/tier-vocabulary");
      if (!r.ok) throw new Error("tier-vocabulary failed");
      const json = await r.json();
      const tiers = json && typeof json.tiers === "object" && json.tiers ? json.tiers : {};
      const out = {};
      for (const k of Object.keys(tiers)) out[k] = Array.isArray(tiers[k]) ? tiers[k] : [];
      return out;
    } catch (_) {
      return {};
    }
  }

  window.buildSkillTreeForm = async function buildSkillTreeForm(data) {
    const MAINHAND_BUFF_SKILLS = new Set(["light_weapons", "heavy_weapons", "archery", "ars_magic", "mining", "woodcutting", "digging", "fishing"]);
    // set-buffs(装備部位数条件バフ)は light_armor / heavy_armor ツリーのみ有効。他ツリーに書かれていたら
    // Java側(SkillTreeConfig)が警告して無視するので、editorも同じ2ツリーだけに描画を出す。
    const SET_BUFF_SKILLS = new Set(["light_armor", "heavy_armor"]);
    const [vocabulary, featureTiers] = await Promise.all([fetchGateVocabulary(), fetchTierVocabulary()]);
    vocabulary.featureTiers = featureTiers;
    const working = data && typeof data === "object" ? data : {};
    const supportsMainhandBuffs = MAINHAND_BUFF_SKILLS.has(String(working.skill || "").toLowerCase());
    const supportsSetBuffs = SET_BUFF_SKILLS.has(String(working.skill || "").toLowerCase());
    migrateLegacyNative(working.prestige);
    if (working.nodes && typeof working.nodes === "object") {
      Object.values(working.nodes).forEach(migrateLegacyNative);
    }
    const root = h("div", { class: "dedicated-form skilltree-form" });

    // ---- ツリーヘッダ ----
    const treeBody = h("div", { class: "entry-inputs" });
    // 表示名は catalog の displayname と同じリッチ着色入力 (legacy &コード)。
    // TFスキルGUIが直接読み込み、&コードで着色する。
    treeBody.appendChild(field("display-name", window.richTextInput(working["display-name"], "legacy", (v) => { working["display-name"] = v; }),
      { label: "表示名", desc: "スキルツリーの表示名。&コードで着色できます(例: &b軽量武器)。" }));
    const iconHint = window.materialHintEl(working.icon);
    const iconInput = window.materialInput(working.icon, "material-list", (v) => { working.icon = v; iconHint.update(v); });
    treeBody.appendChild(field("icon", h("span", { class: "form-field" }, [iconInput, iconHint]),
      { label: "アイコン素材", desc: "GUIに表示するバニラMaterial。日本語メニューから選べます。" }));
    treeBody.appendChild(field("starting-coords", window.textInput(working["starting-coords"], (v) => { working["starting-coords"] = v; }, "x,y"),
      { label: "開始座標", desc: "ツリーGUIの起点座標 x,y。" }));
    root.appendChild(card([
      h("span", { class: "entry-key-label", text: "スキルツリー" }),
      h("span", { class: "entry-key-label", text: working.skill != null ? String(working.skill) : "" })
    ], [treeBody]));

    // ---- prestige ----
    if (working.prestige && typeof working.prestige === "object") {
      const P = working.prestige;
      const pBody = h("div", { class: "entry-inputs" });
      pBody.appendChild(field("enabled", window.checkboxInput(P.enabled, (v) => { P.enabled = v; }),
        { label: "有効", desc: "プレステージ機能を有効にするか。" }));
      pBody.appendChild(field("at-level", window.numberInput(P["at-level"], (v) => { P["at-level"] = v; }, { int: true }),
        { label: "到達レベル", desc: "プレステージが解放されるスキルレベル。" }));
      pBody.appendChild(field("name", window.textInput(P.name, (v) => { P.name = v; }),
        { label: "表示名", desc: "プレステージの表示名。" }));
      pBody.appendChild(field("max-times", window.numberInput(P["max-times"], (v) => {
        // プレステージ上限回数。1以上の整数のみ。空/0以下はキー削除(=既定の1回上限)。
        if (v == null || v === "") { delete P["max-times"]; return; }
        const n = Math.trunc(Number(v));
        if (!Number.isFinite(n) || n < 1) delete P["max-times"];
        else P["max-times"] = n;
      }, { int: true }),
        { label: "上限回数 (max-times)", desc: "プレステージ可能な最大回数。到達レベルで繰り返しプレステージでき、この回数に達すると打ち止め。空欄=1回(既定)。" }));
      pBody.appendChild(descriptionSection(P));
      pBody.appendChild(buffsSection(P, "buffs"));
      if (supportsMainhandBuffs) pBody.appendChild(buffsSection(P, "mainhand-buffs", "メインハンド条件バフ (mainhand-buffs)",
        "このツリーに対応する武器/ツールをメインハンドに持つ間だけ加算されます。プレステージでは、習得済み段階ごとに加算されます。"));
      if (supportsSetBuffs) pBody.appendChild(setBuffsSection(P));
      root.appendChild(card([h("span", { class: "entry-key-label", text: "プレステージ (prestige)" })], [pBody]));
    }

    // ---- nodes ----
    // nodesContainer 配下だけを CRUD 操作のたびに renderNodes() で再構築する。
    const nodesContainer = h("div", { class: "nodes-container" });
    root.appendChild(nodesContainer);
    // 折りたたみ状態(開いているノードidの集合)。既定は全て折りたたみ。再描画をまたいで保持する。
    const expanded = new Set();

    function renderNodes() {
      nodesContainer.innerHTML = "";

      const nodes = working.nodes && typeof working.nodes === "object" ? working.nodes : null;
      const nodeIds = nodes ? Object.keys(nodes) : [];

      if (nodeIds.length === 0) {
        nodesContainer.appendChild(emptyGuide("ノードがありません。", "下の「+ ノード追加」ボタンで新しいノードを作成できます。"));
      }

      // 2026-07-29: 親ノード/代替親/排他グループが「生ノードIDのセレクト」「カンマ区切りの
      // 自由入力」「素の自由入力」で、日本語のノード名では選べずタイポも素通りしていた。
      // ノード名を主表示・IDを副表示にしたセレクトへ統一する。
      const ROOT_VALUE = "(root)";
      const nodeLabelOf = (nid) => {
        const n = nodes && nodes[nid];
        const name = n && typeof n === "object" && typeof n.name === "string" ? n.name.trim() : "";
        return name || nid;
      };
      const nodeOptionsExcept = (selfId) => nodeIds
        .filter((nid) => nid !== selfId)
        .map((nid) => ({ value: nid, primary: nodeLabelOf(nid), secondary: nid }));
      // 既に使われている排他グループ名。名前は運用側が決める任意文字列なので和訳はしない。
      const usedGroupNames = [...new Set(nodeIds
        .map((nid) => nodes[nid] && nodes[nid].group)
        .filter((g) => typeof g === "string" && g.trim() !== ""))];

      for (const id of nodeIds) {
        const node = nodes[id] && typeof nodes[id] === "object" ? nodes[id] : (nodes[id] = {});
        const nameInput = window.textInput(node.name, (v) => { node.name = v; });
        nameInput.classList.add("node-name-input");

        // ノードID編集(改名)。blur/enter確定。空・重複は弾いて元に戻す。
        const idInput = h("input", {
          class: "field-input entry-key-input",
          value: id,
          spellcheck: "false",
          onchange: (e) => {
            const nv = e.target.value.trim();
            if (!nv || nv === id) { e.target.value = id; return; }
            if (Object.prototype.hasOwnProperty.call(nodes, nv)) {
              alert("同じノードIDが既に存在します: " + nv);
              e.target.value = id;
              return;
            }
            renameKey(nodes, id, nv);
            for (const nid of Object.keys(nodes)) {
              const n = nodes[nid];
              if (n && typeof n === "object" && n.parent === id) n.parent = nv;
              if (n && typeof n === "object" && Array.isArray(n["parents-any"])) {
                n["parents-any"] = n["parents-any"].map((parent) => parent === id ? nv : parent);
              }
            }
            renderNodes();
          }
        });

        const dupBtn = h("button", {
          class: "btn-small", type: "button", text: "複製",
          onclick: () => {
            const newId = nextNodeId(nodes);
            const copy = JSON.parse(JSON.stringify(node));
            copy.name = (typeof copy.name === "string" ? copy.name : "") + " (コピー)";
            nodes[newId] = copy;
            expanded.add(newId); // 複製直後は開いておく
            renderNodes();
          }
        });
        const delBtn = h("button", {
          class: "btn-small danger", type: "button", text: "削除",
          onclick: () => {
            const fallbackParent = node.parent != null ? node.parent : null;
            delete nodes[id];
            for (const nid of Object.keys(nodes)) {
              const n = nodes[nid];
              if (n && typeof n === "object" && n.parent === id) n.parent = fallbackParent;
              if (n && typeof n === "object" && Array.isArray(n["parents-any"])) {
                n["parents-any"] = n["parents-any"].filter((parent) => parent !== id);
                if (n["parents-any"].length === 0) delete n["parents-any"];
              }
            }
            renderNodes();
          }
        });

        const grid = h("div", { class: "field-grid" });
        grid.appendChild(field("level", window.numberInput(node.level, (v) => { node.level = v; }, { int: true }),
          { label: "到達レベル", desc: "このノードを取得可能になるスキルレベル。" }));
        grid.appendChild(field("role", window.selectLabeledInput(node.role, ["main", "intermediate", "branch", "greek"], "skill-role", (v) => { node.role = v; }),
          { label: "役割", desc: "main=主軸(縦幹)/intermediate=中間/branch=左右分岐/greek=排他分岐。レイアウトと配置に影響。" }));

        const siblingOptions = nodeOptionsExcept(id);
        const parentVal = node.parent == null ? ROOT_VALUE : String(node.parent);
        const parentOptions = [{ value: ROOT_VALUE, primary: "起点 (親なし)", secondary: "root" }]
          .concat(siblingOptions);
        // 消えたノードを指したまま保存されている場合も、値を落とさず候補へ補う。
        if (parentVal !== ROOT_VALUE && !siblingOptions.some((o) => o.value === parentVal)) {
          parentOptions.push({ value: parentVal, primary: parentVal, secondary: "存在しないノード" });
        }
        grid.appendChild(field("parent", window.listSelect({
          value: parentVal,
          options: parentOptions,
          onChange: (v) => { node.parent = v === ROOT_VALUE ? null : v; }
        }), { label: "親ノード", desc: "接続元ノード。「起点 (親なし)」でツリーの起点になる。ツリーの枝を定義する。" }));

        const anyParentsBox = h("div", { class: "stat-rows" });
        const anyParentsOf = () => (Array.isArray(node["parents-any"]) ? node["parents-any"] : []);
        function setAnyParents(next) {
          const values = next.filter((v, i, all) => v && v !== id && all.indexOf(v) === i);
          if (values.length === 0) delete node["parents-any"];
          else node["parents-any"] = values;
        }
        function renderAnyParents() {
          anyParentsBox.innerHTML = "";
          const list = anyParentsOf();
          if (!list.length) {
            anyParentsBox.appendChild(h("div", { class: "empty-hint", text: "代替親はありません。" }));
          }
          list.forEach((pid, idx) => {
            const opts = siblingOptions.slice();
            if (!opts.some((o) => o.value === pid)) {
              opts.unshift({ value: pid, primary: pid, secondary: "存在しないノード" });
            }
            anyParentsBox.appendChild(h("div", { class: "stat-row" }, [
              window.listSelect({
                value: pid,
                options: opts,
                onChange: (v) => {
                  if (!v) return;
                  const next = anyParentsOf().slice();
                  next[idx] = v;
                  setAnyParents(next);
                  renderAnyParents();
                }
              }),
              h("button", {
                class: "btn-small danger", type: "button", text: "×",
                onclick: () => {
                  const next = anyParentsOf().slice();
                  next.splice(idx, 1);
                  setAnyParents(next);
                  renderAnyParents();
                }
              })
            ]));
          });
          const pool = siblingOptions.filter((o) => !anyParentsOf().includes(o.value));
          if (pool.length) {
            anyParentsBox.appendChild(h("div", { class: "stat-row" }, [
              window.listSelect({
                value: "", options: pool, placeholder: "＋ 代替親を追加…",
                onChange: (v) => {
                  if (!v) return;
                  setAnyParents(anyParentsOf().concat(v));
                  renderAnyParents();
                }
              })
            ]));
          }
        }
        renderAnyParents();
        grid.appendChild(field("parents-any", anyParentsBox,
          { label: "代替親ノード", desc: "親ノードまたはこの一覧のどれか1つを解放していれば合流ノードを取得可能。" }));

        const groupOptions = [{ value: "", primary: "(排他なし)" }]
          .concat(usedGroupNames.map((g) => ({ value: g, primary: g })));
        if (node.group && !usedGroupNames.includes(node.group)) {
          groupOptions.push({ value: String(node.group), primary: String(node.group) });
        }
        groupOptions.push({ value: "__custom__", primary: "＋ 新しいグループ名…" });
        grid.appendChild(field("group", window.listSelect({
          value: node.group == null ? "" : String(node.group),
          options: groupOptions,
          allowCustom: true,
          customPlaceholder: "グループ名 (半角英数)",
          onChange: (v) => { if (!v) delete node.group; else node.group = v; }
        }), { label: "排他グループ", desc: "同じ親かつ同じグループ名の兄弟だけが相互排他。親が異なる同名グループは同じ選択ルートの続きとして取得可能。" }));

        const nodeIconHint = window.materialHintEl(node.icon);
        const nodeIconInput = window.materialInput(node.icon, "material-list", (v) => { node.icon = v; nodeIconHint.update(v); });
        grid.appendChild(field("icon", h("span", { class: "form-field" }, [nodeIconInput, nodeIconHint]),
          { label: "アイコン素材", desc: "このノードのGUIアイコン。日本語メニューから選べます。" }));

        grid.appendChild(field("cost", window.numberInput(node.cost, (v) => { node.cost = v; }, { int: true }),
          { label: "取得コスト", desc: "このノードの取得に必要なスキルポイント数。" }));

        const bodyChildren = [grid, descriptionSection(node)];
        bodyChildren.push(buffsSection(node, "buffs"));
        if (supportsMainhandBuffs) bodyChildren.push(buffsSection(node, "mainhand-buffs", "メインハンド条件バフ (mainhand-buffs)",
          "このツリーに対応する武器/ツールをメインハンドに持つ間だけ加算されます。"));
        if (supportsSetBuffs) bodyChildren.push(setBuffsSection(node));
        bodyChildren.push(unlockEffectsSection(node, () => window.GATE_EFFECTS.computeDuplicateGateEffectIds(nodes), vocabulary));

        // 表示順の上下入替 (working.nodes のキー順を入替。parent参照はid基準なので不変)。
        const upBtn = h("button", { class: "btn-small", type: "button", text: "↑", title: "表示順を上へ", onclick: () => { moveKey(nodes, id, -1); renderNodes(); } });
        const downBtn = h("button", { class: "btn-small", type: "button", text: "↓", title: "表示順を下へ", onclick: () => { moveKey(nodes, id, 1); renderNodes(); } });

        // 折りたたみ: 既定は閉じる。expanded にあるidのみ本体を表示する。ヘッダの▶/▼で切替。
        const bodyEl = h("div", { class: "entry-body" }, bodyChildren);
        bodyEl.style.display = expanded.has(id) ? "" : "none";
        const toggleBtn = h("button", {
          class: "btn-small", type: "button", text: expanded.has(id) ? "▼" : "▶", title: "詳細の折りたたみ切替",
          onclick: () => {
            if (expanded.has(id)) { expanded.delete(id); bodyEl.style.display = "none"; toggleBtn.textContent = "▶"; }
            else { expanded.add(id); bodyEl.style.display = ""; toggleBtn.textContent = "▼"; }
          }
        });

        const headEl = h("div", { class: "entry-head" }, [toggleBtn, idInput, nameInput, upBtn, downBtn, dupBtn, delBtn]);
        nodesContainer.appendChild(h("div", { class: "entry-card" }, [headEl, bodyEl]));
      }

      // 「+ ノード追加」ボタンはタブの一番下に配置する。
      nodesContainer.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn-small", type: "button", text: "+ ノード追加",
          onclick: () => {
            if (!working.nodes || typeof working.nodes !== "object") working.nodes = {};
            const ids = Object.keys(working.nodes);
            const newId = nextNodeId(working.nodes);
            const lastId = ids.length > 0 ? ids[ids.length - 1] : null;
            working.nodes[newId] = { role: "branch", parent: lastId };
            expanded.add(newId); // 追加直後は編集しやすいよう開いておく
            renderNodes();
          }
        })
      ]));
    }

    renderNodes();

    return { element: root, getData: () => working };
  };

})();
