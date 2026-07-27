"use strict";

// Phase3 専用フォーム:
//   ars-config / ban / skills-ars_*
// dedicated-effects カタログUIは廃止(2026-07-23)。解放効果はスキルツリーのノードで直接設定する(tf-skilltree.js)。
// 往復ロスレス: working を直接編集。未知キーは温存。

(function () {
  const h = window.h;

  function card(head, body) {
    return h("div", { class: "entry-card" }, [
      h("div", { class: "entry-head" }, Array.isArray(head) ? head : [head]),
      h("div", { class: "entry-body" }, body)
    ]);
  }
  function field(key, control, opts) {
    const o = opts || {};
    return h("div", { class: "form-field" }, [
      window.fieldLabelEl(o.key || key, {
        label: o.label || key,
        desc: o.desc || "",
        hideKey: o.hideKey !== false
      }),
      control
    ]);
  }
  function grid(fields) { return h("div", { class: "field-grid" }, fields); }
  function sub(text) { return h("div", { class: "sub-title", text }); }
  function banner(text) {
    return h("div", { class: "form-banner", text });
  }
  function emptyGuide(title, hint) {
    return h("div", { class: "empty-guide" }, [
      h("div", { class: "empty-guide-title", text: title }),
      h("div", { class: "empty-guide-hint", text: hint })
    ]);
  }
  function ensureObj(parent, key) {
    if (!parent[key] || typeof parent[key] !== "object" || Array.isArray(parent[key])) parent[key] = {};
    return parent[key];
  }
  function ensureArr(parent, key) {
    if (!Array.isArray(parent[key])) parent[key] = [];
    return parent[key];
  }
  function renameKey(map, oldKey, newKey) {
    const rebuilt = {};
    for (const k of Object.keys(map)) rebuilt[k === oldKey ? newKey : k] = map[k];
    for (const k of Object.keys(map)) delete map[k];
    Object.assign(map, rebuilt);
  }
  function numField(obj, key, opts) {
    const o = opts || {};
    return field(key, window.numberInput(obj[key], (v) => {
      if (v == null || v === "") {
        if (o.clearable) delete obj[key];
        else obj[key] = o.fallback != null ? o.fallback : 0;
        return;
      }
      obj[key] = o.int ? Math.trunc(v) : v;
    }, o.int ? { int: true } : undefined), {
      label: o.label || key,
      desc: o.desc || "",
      key
    });
  }
  function textField(obj, key, opts) {
    const o = opts || {};
    return field(key, window.textInput(obj[key] == null ? "" : String(obj[key]), (v) => {
      if (!v && o.clearable) delete obj[key];
      else obj[key] = v;
    }, o.placeholder || ""), {
      label: o.label || key,
      desc: o.desc || "",
      key
    });
  }
  function boolField(obj, key, opts) {
    const o = opts || {};
    return field(key, window.checkboxInput(!!obj[key], (v) => { obj[key] = v; }), {
      label: o.label || key,
      desc: o.desc || "",
      key
    });
  }
  function stringListEditor(arr, opts) {
    const o = opts || {};
    const box = h("div", { class: "stat-rows" });
    function render() {
      box.innerHTML = "";
      if (!arr.length) {
        box.appendChild(h("div", { class: "empty-hint", text: o.empty || "まだありません。" }));
      }
      arr.forEach((val, idx) => {
        const row = h("div", { class: "stat-row" });
        row.appendChild(window.textInput(val == null ? "" : String(val), (v) => { arr[idx] = v; }, o.placeholder || ""));
        row.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => { arr.splice(idx, 1); render(); }
        }));
        box.appendChild(row);
      });
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: o.addLabel || "+ 追加",
        onclick: () => { arr.push(o.defaultValue != null ? o.defaultValue : ""); render(); }
      }));
    }
    render();
    return box;
  }
  function intListEditor(arr, opts) {
    const o = opts || {};
    const box = h("div", { class: "stat-rows" });
    function render() {
      box.innerHTML = "";
      if (!arr.length) {
        box.appendChild(h("div", { class: "empty-hint", text: o.empty || "まだありません。" }));
      }
      arr.forEach((val, idx) => {
        const row = h("div", { class: "stat-row" });
        row.appendChild(window.numberInput(val, (v) => {
          arr[idx] = v == null || v === "" ? 0 : Math.trunc(v);
        }, { int: true }));
        row.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => { arr.splice(idx, 1); render(); }
        }));
        box.appendChild(row);
      });
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: o.addLabel || "+ 追加",
        onclick: () => { arr.push(o.defaultValue != null ? o.defaultValue : 0); render(); }
      }));
    }
    render();
    return box;
  }
  function selectField(obj, key, options, opts) {
    const o = opts || {};
    const optsList = options.map((v) => ({ value: v, primary: v }));
    return field(key, window.listSelect({
      value: obj[key] == null ? "" : String(obj[key]),
      placeholder: o.placeholder || "選択…",
      options: optsList,
      onChange: (v) => {
        if (!v && o.clearable) delete obj[key];
        else if (v) obj[key] = v;
      }
    }), {
      label: o.label || key,
      desc: o.desc || "",
      key
    });
  }

  // mana.source-auto-consume.items map (itemId -> mana per item) 編集UI。
  // T4 (2026-07-25): 元は ArsPaper 全体設定(ars-config)画面内にあったが、「その他のギミック」
  // (tf-crafting-features.js) 画面へ移設。両画面から呼べるよう共有ヘルパーとして公開する。
  // アイテムIDの自由入力は recipes.js の素材選択UI(RECIPES_UI.itemPicker = window.materialInput、
  // バニラMaterial + custom:カタログ両対応)を使う。
  window.buildSourceAutoConsumeItemsEditor = function buildSourceAutoConsumeItemsEditor(itemsMap) {
    const box = h("div", { class: "stat-rows" });
    function itemPickerFor(value, onChange) {
      if (window.RECIPES_UI && typeof window.RECIPES_UI.itemPicker === "function") {
        return window.RECIPES_UI.itemPicker(value, onChange);
      }
      // recipes.js 未ロード時のフォールバック(通常到達しない)。
      return window.textInput(value, onChange, "source_berry");
    }
    function render() {
      box.innerHTML = "";
      const keys = Object.keys(itemsMap);
      if (!keys.length) box.appendChild(h("div", { class: "empty-hint", text: "未設定（ソース自動消費アイテムなし）" }));
      keys.forEach((k) => {
        box.appendChild(h("div", { class: "stat-row" }, [
          itemPickerFor(k, (nv) => {
            const id = (nv || "").trim();
            if (!id || id === k) return;
            if (Object.prototype.hasOwnProperty.call(itemsMap, id)) { alert("重複"); return; }
            renameKey(itemsMap, k, id);
            render();
          }),
          window.numberInput(itemsMap[k], (v) => {
            itemsMap[k] = v == null || v === "" ? 1 : Math.max(1, Math.trunc(v));
          }, { int: true }),
          h("button", {
            class: "btn-small danger", type: "button", text: "×",
            onclick: () => { delete itemsMap[k]; render(); }
          })
        ]));
      });
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ アイテム",
        onclick: () => {
          let n = 1;
          let id = `item-${n}`;
          while (Object.prototype.hasOwnProperty.call(itemsMap, id)) { n += 1; id = `item-${n}`; }
          itemsMap[id] = 25;
          render();
        }
      }));
    }
    render();
    if (window.RECIPES_UI && typeof window.RECIPES_UI.ensureCustomDatalist === "function") {
      window.RECIPES_UI.ensureCustomDatalist();
    }
    return box;
  };

  const LEVELBAR_COLORS = ["BLUE", "YELLOW", "GREEN", "PINK", "PURPLE", "RED", "WHITE"];
  const LEVELBAR_STYLES = ["SEGMENTED_6", "SEGMENTED_10", "SEGMENTED_12", "SEGMENTED_20", "SOLID"];

  // ============================================================
  // ArsPaper config.yml
  // ============================================================
  window.buildArsConfigForm = function buildArsConfigForm(data) {
    const working = data && typeof data === "object" ? data : {};
    const formCd = ensureObj(working, "form-cooldowns");
    const mana = ensureObj(working, "mana");
    // mana.source-auto-consume.items は 2026-07-25 T4 で「その他のギミック」(crafting-features)画面へ
    // 移設済み。この画面では触れない(未設定キーを新規生成して往復差分を作らないよう ensureObj もしない)。
    // 2026-07-25 T3: geyser.disable-custom-model-data は BaseCustomItem.isCustomModelDataDisabled() が
    // 実際に参照しているため削除しない(削除前提の指示に反する実装依存を検知 → 維持して報告)。
    const geyser = ensureObj(working, "geyser");
    const enchants = ensureObj(working, "enchantments");
    const mobDrops = ensureObj(working, "mob-drops");
    const loot = ensureObj(working, "loot");
    if (!Array.isArray(enchants["mana-regen-per-level"])) enchants["mana-regen-per-level"] = [0, 1, 3, 6];
    if (!Array.isArray(enchants["mana-boost-per-level"])) enchants["mana-boost-per-level"] = [0, 15, 30, 50];

    const root = h("div", { class: "dedicated-form" });
    root.appendChild(banner("ArsPaper 全体設定。スレッド／グリフ本体は各専用画面で編集します。"));

    // form-cooldowns map
    // 2026-07-25 T1: form名の自由入力(タイポで無効設定が作れる)を、recipes.js の素材選択UIと同じ
    // labeledSelect/listSelect部品によるセレクトメニューへ置換。選択肢は formOptions
    // (/api/spell-forms = lib/spell-form-vocabulary.js が唯一の情報源)。
    // 2026-07-25 D修正: formOptions の宣言は下の renderCd 呼び出し・loadFormOptions 呼び出しより
    // 前に置く必要がある(constのTDZ)。以前は呼び出しの後にあり、buildArsConfigForm 実行時に
    // ReferenceError で必ず中断していた(全カード未描画のバグ)。
    const formOptions = [];
    const cdBox = h("div", { class: "stat-rows" });
    function renderCd() {
      cdBox.innerHTML = "";
      const keys = Object.keys(formCd);
      if (!keys.length) cdBox.appendChild(h("div", { class: "empty-hint", text: "未設定（0=従来CTにフォールバック）" }));
      keys.forEach((k) => {
        const cur = formOptions.some((o) => o.value === k)
          ? formOptions
          : [{ value: k, primary: k + " (未知のform)", secondary: k }].concat(formOptions);
        cdBox.appendChild(h("div", { class: "stat-row" }, [
          window.listSelect({
            value: k,
            placeholder: "formを選択…",
            options: cur,
            onChange: (nv) => {
              const id = (nv || "").trim();
              if (!id || id === k) return;
              if (Object.prototype.hasOwnProperty.call(formCd, id)) { alert("重複"); return; }
              renameKey(formCd, k, id);
              renderCd();
            }
          }),
          window.numberInput(formCd[k], (v) => { formCd[k] = v == null || v === "" ? 0 : v; }),
          h("button", {
            class: "btn-small danger", type: "button", text: "×",
            onclick: () => { delete formCd[k]; renderCd(); }
          })
        ]));
      });
      const usedForms = new Set(keys);
      const remaining = formOptions.filter((o) => !usedForms.has(o.value));
      cdBox.appendChild(h("div", { class: "form-actions" }, [
        window.listSelect({
          value: "",
          placeholder: remaining.length ? "+ form CT追加…" : "追加できるformがありません",
          options: remaining,
          disabled: !remaining.length,
          onChange: (v) => {
            if (!v || Object.prototype.hasOwnProperty.call(formCd, v)) return;
            formCd[v] = 0;
            renderCd();
          }
        })
      ]));
    }
    renderCd();
    loadFormOptions();

    // form-cooldowns のキー候補 (2026-07-25 T1: /api/spell-forms が唯一の情報源。
    // Java正典との同期は test/spell-form-vocabulary-java-parity.test.js が検証する)。
    async function loadFormOptions() {
      try {
        const res = await fetch("/api/spell-forms");
        const json = await res.json();
        const list = json && json.data;
        formOptions.length = 0;
        if (Array.isArray(list)) {
          for (const f of list) {
            formOptions.push({ value: f.id, primary: f.label ? `${f.label} (${f.id})` : f.id, secondary: f.id });
          }
        }
      } catch (_) { /* empty */ }
      renderCd();
    }

    root.appendChild(card(h("strong", { text: "form-cooldowns" }), [
      h("div", { class: "form-hint", text: "形態ごとの独立CT(秒)。0/未定義=従来計算CT。" }),
      cdBox
    ]));

    root.appendChild(card(h("strong", { text: "mana" }), [
      h("div", {
        class: "form-hint",
        text: "初期マナ上限／初期回復量／回復間隔(tick)／recovery(戦闘・非発動)は「プレイヤー基礎ステータス」"
          + "(base-stats.yml の mana-max-base 等)へ移設しました。編集はそちらの画面で行ってください。"
      }),
      grid([
        numField(mana, "per-glyph-unlock-bonus", { label: "グリフ解放ごと上限+", int: true }),
        numField(mana, "max-percent-cap", { label: "%上昇キャップ", int: true })
      ]),
      h("div", {
        class: "form-hint",
        text: "source-auto-consume.items（アイテムID → マナ/個）は「その他のギミック」(crafting-features) "
          + "画面の「ソース自動消費」タブへ移設しました。編集はそちらの画面で行ってください。"
      })
    ]));

    root.appendChild(card(h("strong", { text: "geyser" }), [
      h("div", {
        class: "form-hint",
        text: "ars-magic の経験値設定(exp-per-cast / exp-per-mana)は「スキルEXP獲得」(skill-exp.yml の "
          + "ars-magic:)へ統合しました。編集はそちらの画面で行ってください。"
      }),
      grid([
        boolField(geyser, "disable-custom-model-data", {
          label: "CMD無効(Geyser)",
          desc: "統合版で透明になる場合はON。BaseCustomItem.isCustomModelDataDisabled() が実参照するため維持。"
        })
      ])
    ]));

    root.appendChild(card(h("strong", { text: "enchantments" }), [
      numField(enchants, "max-level", { label: "最大レベル", int: true }),
      // 2026-07-25 T5: yml キー(mana-regen-per-level等)は変更せず(後方互換のため)、表示ラベルのみ
      // 日本語化。キーを変えると読み手のJava(GlyphConfig等)側の追随・後方互換読みが必要になり
      // リスク/コストが見合わないため、表示だけを日本語化する方を選んだ(判断理由)。
      sub("エンチャントLvごとのマナ自然回復量 (Lv0から)"),
      intListEditor(enchants["mana-regen-per-level"], { addLabel: "+ レベル帯を追加" }),
      sub("エンチャントLvごとのマナ上限増加量 (Lv0から)"),
      intListEditor(enchants["mana-boost-per-level"], { addLabel: "+ レベル帯を追加" })
    ]));

    root.appendChild(card(h("strong", { text: "mob-drops / loot" }), [
      grid([
        boolField(mobDrops, "warden-echo-shard", { label: "ウォーデン→残響の欠片" }),
        numField(mobDrops, "warden-echo-shard-min", { label: "欠片 min", int: true }),
        numField(mobDrops, "warden-echo-shard-max", { label: "欠片 max", int: true }),
        boolField(loot, "enabled", { label: "ルートチェストON" }),
        numField(loot, "enchant-book-chance", { label: "エンチャ本出現率" }),
        numField(loot, "enchanted-golden-apple-chance", { label: "金リンゴ出現率" })
      ])
    ]));

    return { element: root, getData: () => working };
  };

  // ============================================================
  // ArsPaper ban.yml
  // ============================================================
  window.buildBanForm = function buildBanForm(data) {
    const working = data && typeof data === "object" ? data : {};
    ensureArr(working, "banned-spells");
    const root = h("div", { class: "dedicated-form" });
    root.appendChild(banner("サーバ全体でBANするスペル（効果グリフ）。glyphs.yml に存在するグリフから選択。ワールド別BANは /ars world ban。"));

    const listBox = h("div", { class: "stat-rows" });
    const glyphOptions = [];

    function normalizeKey(s) {
      const raw = String(s || "").trim();
      if (!raw) return "";
      if (raw.includes(":")) return raw;
      return "arspaper:" + raw;
    }

    function bare(s) {
      const n = normalizeKey(s);
      const i = n.indexOf(":");
      return i >= 0 ? n.slice(i + 1) : n;
    }

    async function loadGlyphOptions() {
      try {
        const res = await fetch("/api/config/glyphs");
        const json = await res.json();
        const g = json && json.data && json.data.glyphs;
        glyphOptions.length = 0;
        if (g && typeof g === "object") {
          for (const id of Object.keys(g).sort()) {
            glyphOptions.push({ value: "arspaper:" + id, primary: id, secondary: "arspaper:" + id });
          }
        }
      } catch (_) { /* empty */ }
      render();
    }

    function render() {
      listBox.innerHTML = "";
      const arr = working["banned-spells"];
      if (!arr.length) {
        listBox.appendChild(h("div", { class: "empty-hint", text: "BANなし（空配列）" }));
      }
      arr.forEach((val, idx) => {
        const cur = normalizeKey(val);
        const opts = glyphOptions.slice();
        if (cur && !opts.some((o) => o.value === cur)) {
          opts.unshift({ value: cur, primary: bare(cur) + " (glyphs外)", secondary: cur });
        }
        listBox.appendChild(h("div", { class: "stat-row" }, [
          window.listSelect({
            value: cur,
            placeholder: "グリフを選択…",
            options: opts,
            onChange: (v) => { arr[idx] = normalizeKey(v); }
          }),
          h("button", {
            class: "btn-small danger", type: "button", text: "×",
            onclick: () => { arr.splice(idx, 1); render(); }
          })
        ]));
      });
      const used = new Set(arr.map(normalizeKey));
      const remaining = glyphOptions.filter((o) => !used.has(o.value));
      listBox.appendChild(h("div", { class: "form-actions" }, [
        window.listSelect({
          value: "",
          placeholder: remaining.length ? "+ BAN追加…" : "追加できるグリフがありません",
          options: remaining,
          disabled: !remaining.length,
          onChange: (v) => {
            if (!v) return;
            arr.push(normalizeKey(v));
            render();
          }
        })
      ]));
    }

    root.appendChild(card(h("strong", { text: "banned-spells" }), [listBox]));
    loadGlyphOptions();
    return { element: root, getData: () => working };
  };

  // ============================================================
  // skills/ars_magic.yml · skills/ars_smithing.yml (legacy display metadata)
  // ============================================================
  window.buildArsSkillDefForm = function buildArsSkillDefForm(data, opts) {
    const o = opts || {};
    const working = data && typeof data === "object" ? data : {};
    const root = h("div", { class: "dedicated-form" });
    root.appendChild(banner(o.banner || "スキル表示定義（表示名・アイコン・レベルバー）。進行度曲線は skills/base/*_progression.yml。"));

    root.appendChild(card(h("strong", { text: "表示" }), [
      grid([
        textField(working, "display_name", { label: "表示名", placeholder: "&7Ars魔法" }),
        field("icon", window.materialInput(working.icon || "", "material-list", (v) => {
          if (v) working.icon = v;
          else delete working.icon;
        }, { allowCustom: true }), { label: "icon", key: "icon" }),
        textField(working, "description", { label: "説明", placeholder: "&8…" })
      ])
    ]));

    root.appendChild(card(h("strong", { text: "levelbar" }), [
      grid([
        textField(working, "levelbar_title", { label: "タイトル" }),
        selectField(working, "levelbar_color", LEVELBAR_COLORS, { label: "色" }),
        selectField(working, "levelbar_style", LEVELBAR_STYLES, { label: "スタイル" })
      ])
    ]));

    return { element: root, getData: () => working };
  };

  window.buildArsMagicSkillForm = function buildArsMagicSkillForm(data) {
    return window.buildArsSkillDefForm(data, {
      banner: "Ars魔法表示定義。EXP・進行は ars-config / skills/base/ars_magic_progression.yml。"
    });
  };

  window.buildArsSmithingSkillForm = function buildArsSmithingSkillForm(data) {
    return window.buildArsSkillDefForm(data, {
      banner: "Ars鍛冶表示定義。品質・スレッドは skilltree / crafting-features 側。"
    });
  };

})();
