"use strict";

// ダンジョン / モブプロファイル / ヘイト の専用フォーム群。
// 往復ロスレス: working を直接編集。未知キーは温存。

(function () {
  const h = window.h;

  function card(head, body) {
    return h("div", { class: "entry-card" }, [
      h("div", { class: "entry-head" }, head),
      h("div", { class: "entry-body" }, body)
    ]);
  }
  function field(label, control, opts) {
    const o = opts || {};
    return h("div", { class: "form-field" }, [
      window.fieldLabelEl(o.key || label, {
        label: o.label || label,
        desc: o.desc || "",
        hideKey: o.hideKey !== false,
        required: !!o.required
      }),
      control
    ]);
  }
  function grid(fields) { return h("div", { class: "field-grid" }, fields); }
  function sub(text) { return h("div", { class: "sub-title", text }); }
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
  function ensureObj(parent, key) {
    if (!parent[key] || typeof parent[key] !== "object" || Array.isArray(parent[key])) parent[key] = {};
    return parent[key];
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

  // ---- base+per-level (+任意で growth 指数) ランプ (themes / mob-import) ----
  //   opts.showGrowth: true のときだけ growth / growth-interval 欄を追加描画する。
  //   growth 系は任意入力: 空欄なら書き込まない(未入力キーを追加しない=既定の線形挙動を維持)。
  //   themes.yml 側は showGrowth を渡さない(base/per-levelのみ)ため、ここでの出し分けで
  //   themes フォームへ growth 欄が漏れ出ることはない。
  function rampEditors(host, keys, title, opts) {
    const o = opts || {};
    const showGrowth = !!o.showGrowth;
    const box = h("div", { class: "mob-defense-block" });
    box.appendChild(sub(title));
    const rows = h("div", { class: "stat-rows" });
    for (const key of keys) {
      const ramp = host[key] && typeof host[key] === "object" ? host[key] : (host[key] = { base: 0, "per-level": 0 });
      if (ramp.base == null) ramp.base = 0;
      if (ramp["per-level"] == null) ramp["per-level"] = 0;
      const rowChildren = [
        h("span", { class: "form-label", text: key }),
        h("span", { class: "mini-label", text: "基準" }),
        window.numberInput(ramp.base, (v) => { ramp.base = v == null ? 0 : v; }),
        h("span", { class: "mini-label", text: "Lvごと" }),
        window.numberInput(ramp["per-level"], (v) => { ramp["per-level"] = v == null ? 0 : v; })
      ];
      if (showGrowth) {
        rowChildren.push(
          h("span", { class: "mini-label", text: "指数(growth)" }),
          window.numberInput(ramp.growth == null ? "" : ramp.growth, (v) => {
            if (v == null || v === "") delete ramp.growth;
            else ramp.growth = v;
          }),
          h("span", { class: "mini-label", text: "指数間隔" }),
          window.numberInput(ramp["growth-interval"] == null ? "" : ramp["growth-interval"], (v) => {
            if (v == null || v === "") delete ramp["growth-interval"];
            else ramp["growth-interval"] = v;
          })
        );
      }
      rows.appendChild(h("div", { class: "stat-row" }, rowChildren));
    }
    box.appendChild(rows);
    if (showGrowth) {
      box.appendChild(h("div", { class: "form-hint", text:
        "指数(growth)・指数間隔は空欄のままなら書き込まれず、従来どおり線形(基準+Lv×増分)のままです。growth>1.0で指数的に増加します。" }));
    }
    return box;
  }

  const DEFENSE_KEYS = ["defense-rate", "resistance", "damage-reduction", "flat-defense"];

  // ============================================================
  // dungeon/gates.yml
  // ============================================================
  // ゲートIDに紐づく EliteMobs パッケージ名を台帳から自動導出し、g["content-package"] へ書く。
  // forceOverwrite=true (ID確定直後) は選び直した以上その値を信じて上書きする。
  // forceOverwrite=false (台帳の非同期到着時の補完) は既存値を消さない — 手書きワールド名ゲートの
  // 既存 content-package を誤って消さないため。
  function applyContentPackageFromDungeon(g, worldId, forceOverwrite) {
    const d = window.EM_DUNGEONS && window.EM_DUNGEONS.get(worldId);
    if (!d || !d.package) return;
    if (!forceOverwrite && g["content-package"]) return;
    g["content-package"] = d.package;
  }

  window.buildDungeonGatesForm = function buildDungeonGatesForm(data, options) {
    const opts = options && typeof options === "object" ? options : {};
    const catalogCandidates = Array.isArray(opts.catalogCandidates) ? opts.catalogCandidates : [];
    const working = data && typeof data === "object" ? data : {};
    if (!working.gates || typeof working.gates !== "object" || Array.isArray(working.gates)) {
      working.gates = {};
    }
    const gates = working.gates;
    const root = h("div", { class: "dedicated-form dungeon-gates-form card-list" });
    const list = h("div", { class: "card-list-body" });
    root.appendChild(h("div", {
      class: "form-banner",
      text: "ダンジョン入場条件。キーは行き先ワールド名（＝ダンジョンの選択）。"
        + "EliteMobs のパッケージ名はその選択から自動で紐づき、複数 blueprint を束ねたいときだけ別名を足します。"
    }));
    root.appendChild(list);

    function render() {
      list.innerHTML = "";
      const ids = Object.keys(gates);
      if (!ids.length) {
        list.appendChild(emptyGuide(
          "ゲートがまだありません",
          "「+ ゲート追加」で入場条件を作ってください。空のままでは一般ユーザーはEliteMobsダンジョンへ入場できません。"
        ));
      }
      for (const id of ids) {
        const g = gates[id] && typeof gates[id] === "object" ? gates[id] : (gates[id] = {});
        const aliases = Array.isArray(g.aliases) ? g.aliases : (g.aliases = []);
        // ダンジョンID(= 行き先ワールド名)は EliteMobs 同梱ダンジョンから選べるようにする。
        // 自作ダンジョンもあるので手動入力は常に可能(allowCustom)。
        // 選択済みの表示は listSelect 自身が「日本語名 (ワールド名)」で出すため、別途ラベルは足さない。
        const head = [
          window.listSelect({
            value: id,
            allowCustom: true,
            customPlaceholder: "ワールド名を直接入力",
            placeholder: "ダンジョンを選択…",
            className: "entry-key-input",
            options: () => (window.EM_DUNGEONS ? window.EM_DUNGEONS.dungeonOptions() : []),
            onCommit: (nv) => {
              const v = String(nv || "").trim();
              if (!v || v === id) return false;
              if (Object.prototype.hasOwnProperty.call(gates, v)) {
                alert("同じワールド名が既にあります"); return false;
              }
              renameKey(gates, id, v);
              applyContentPackageFromDungeon(gates[v], v, true);
              render();
              return true;
            }
          }),
          h("span", { class: "spacer" }),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { delete gates[id]; render(); }
          })
        ];
        const body = [
          grid([
            numField(g, "required-combat-level", {
              label: "必要戦闘Lv",
              desc: "0または空欄＝レベル制限なし",
              int: true,
              clearable: true
            }),
            field("key-item", window.itemRefSelect({
              value: g["key-item"] != null ? g["key-item"] : (g["key-material"] != null ? g["key-material"] : ""),
              catalogCandidates,
              placeholder: "アイテムを選択…（空欄＝鍵なし）",
              onChange: (v) => {
                const nv = String(v || "").trim();
                if (!nv) { delete g["key-item"]; delete g["key-material"]; return; }
                g["key-item"] = nv;
                delete g["key-material"];
              }
            }), {
              label: "必要鍵アイテム",
              desc: "入場時に消費するアイテム。カタログ品/ArsPaper品/バニラ Material のいずれも指定できます。空欄＝鍵なし",
              key: "key-item"
            }),
            numField(g, "key-amount", {
              label: "鍵の個数",
              desc: "省略時は1",
              int: true,
              clearable: true
            })
          ]),
          h("div", {
            class: "form-hint",
            text: "EliteMobs のパッケージ名はダンジョンの選択から自動で紐づけます（content-package）。"
              + "複数の blueprint を紐づけたい場合だけ下の別名を使ってください。"
          }),
          sub("別名 (aliases) — EliteMobs の複数 blueprint 名"),
          aliasEditor(aliases, () => render())
        ];
        list.appendChild(window.collapsibleCard
          ? window.collapsibleCard(head, body, { expanded: ids.length <= 3 })
          : card(head, body));
      }
      list.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn", type: "button", text: "+ ゲート追加",
          onclick: () => {
            let n = "dungeon_world", i = 1;
            while (Object.prototype.hasOwnProperty.call(gates, n)) n = `dungeon_world_${i++}`;
            gates[n] = { "required-combat-level": 1 };
            render();
          }
        })
      ]));
    }

    function aliasEditor(aliases, onChange) {
      const box = h("div", { class: "stat-rows" });
      aliases.forEach((a, idx) => {
        box.appendChild(h("div", { class: "stat-row" }, [
          window.textInput(a, (v) => { aliases[idx] = v; }, "blueprint名"),
          h("button", {
            class: "btn-small danger", type: "button", text: "×",
            onclick: () => { aliases.splice(idx, 1); onChange(); }
          })
        ]));
      });
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 別名追加",
        onclick: () => { aliases.push(""); onChange(); }
      }));
      return box;
    }

    // content-package が未設定の既存ゲートを台帳から補完する
    // (既に値があるものは触らない — 手書きワールド名ゲートの既存値を消さないため)。
    function backfillContentPackages() {
      for (const gid of Object.keys(gates)) {
        const g = gates[gid];
        if (g && typeof g === "object") applyContentPackageFromDungeon(g, gid, false);
      }
    }

    // 台帳が既に読み込み済みなら下の非同期分岐には入らないので、ここで必ず1回補完しておく。
    // これを描画前の1回だけにすると「台帳が間に合った回だけ自動導出される」不安定な挙動になる。
    backfillContentPackages();
    render();
    // 既定ダンジョン台帳は非同期取得。届いたらセレクト候補と日本語名を出すため描き直す。
    if (window.EM_DUNGEONS && !window.EM_DUNGEONS.isLoaded()) {
      window.EM_DUNGEONS.load().then(() => {
        backfillContentPackages();
        render();
      });
    }
    return { element: root, getData: () => working };
  };

  // ============================================================
  // dungeon/themes.yml
  // ============================================================
  window.buildDungeonThemesForm = function buildDungeonThemesForm(data) {
    const working = data && typeof data === "object" ? data : {};
    if (!working.themes || typeof working.themes !== "object" || Array.isArray(working.themes)) {
      working.themes = {};
    }
    const themes = working.themes;
    const root = h("div", { class: "dedicated-form dungeon-themes-form card-list" });
    const list = h("div", { class: "card-list-body" });
    root.appendChild(h("div", {
      class: "form-banner",
      text: "ダンジョン属性テーマ。importmobs theme <名前> でモブに守備バイアスを付与します。物理寄り／魔法寄り／バランスなど。"
    }));
    root.appendChild(list);

    function render() {
      list.innerHTML = "";
      const ids = Object.keys(themes);
      if (!ids.length) {
        list.appendChild(emptyGuide("テーマがありません", "「+ テーマ追加」で物理要塞・魔法結界などのプリセットを作ります。"));
      }
      for (const id of ids) {
        const t = themes[id] && typeof themes[id] === "object" ? themes[id] : (themes[id] = {});
        const phys = ensureObj(t, "physical");
        const mag = ensureObj(t, "magical");
        if (!t["armor-strength"] || typeof t["armor-strength"] !== "object") {
          t["armor-strength"] = { base: 0, "per-level": 0 };
        }
        const head = [
          h("input", {
            class: "field-input entry-key-input",
            value: id,
            spellcheck: "false",
            onchange: (e) => {
              const nv = e.target.value.trim();
              if (!nv || nv === id) { e.target.value = id; return; }
              if (Object.prototype.hasOwnProperty.call(themes, nv)) {
                alert("同じテーマ名があります"); e.target.value = id; return;
              }
              renameKey(themes, id, nv);
              render();
            }
          }),
          h("span", { class: "spacer" }),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { delete themes[id]; render(); }
          })
        ];
        const body = [
          rampEditors(phys, DEFENSE_KEYS, "物理への守備 (physical)"),
          rampEditors(mag, DEFENSE_KEYS, "魔法への守備 (magical)"),
          rampEditors({ "armor-strength": t["armor-strength"] }, ["armor-strength"], "防具強度 (共通)")
        ];
        // armor-strength is top-level ramp — fix the last rampEditors call
        body[2] = (() => {
          const box = h("div", { class: "mob-defense-block" });
          box.appendChild(sub("防具強度 (共通)"));
          const ramp = t["armor-strength"];
          box.appendChild(h("div", { class: "stat-row" }, [
            h("span", { class: "mini-label", text: "基準" }),
            window.numberInput(ramp.base, (v) => { ramp.base = v == null ? 0 : v; }),
            h("span", { class: "mini-label", text: "Lvごと" }),
            window.numberInput(ramp["per-level"], (v) => { ramp["per-level"] = v == null ? 0 : v; })
          ]));
          return box;
        })();
        list.appendChild(window.collapsibleCard
          ? window.collapsibleCard(head, body, { expanded: false })
          : card(head, body));
      }
      list.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn", type: "button", text: "+ テーマ追加",
          onclick: () => {
            let n = "new_theme", i = 1;
            while (Object.prototype.hasOwnProperty.call(themes, n)) n = `new_theme_${i++}`;
            themes[n] = {
              physical: Object.fromEntries(DEFENSE_KEYS.map((k) => [k, { base: 0, "per-level": 0 }])),
              magical: Object.fromEntries(DEFENSE_KEYS.map((k) => [k, { base: 0, "per-level": 0 }])),
              "armor-strength": { base: 0, "per-level": 0 }
            };
            render();
          }
        })
      ]));
    }
    render();
    return { element: root, getData: () => working };
  };

  // ============================================================
  // combat/mob-import.yml
  // ============================================================
  // attack ブロックの8ステ (mob-import.yml attack:)。damage-modifier は既定1.0(乗算中立)。
  const ATTACK_KEYS = [
    "attack-power", "flat-bonus-damage", "percent-bonus-damage", "crit-chance",
    "crit-damage", "penetration", "damage-modifier", "fixed-damage"
  ];

  window.buildMobImportForm = function buildMobImportForm(data) {
    const working = data && typeof data === "object" ? data : {};
    const root = h("div", { class: "dedicated-form mob-import-form" });
    const level = ensureObj(working, "level");
    const theme = ensureObj(working, "theme");
    const phys = ensureObj(working, "physical");
    const mag = ensureObj(working, "magical");
    if (!working["armor-strength"] || typeof working["armor-strength"] !== "object") {
      working["armor-strength"] = { base: 0, "per-level": 0 };
    }
    if (!working["max-health"] || typeof working["max-health"] !== "object") {
      working["max-health"] = { base: 150.0, "per-level": 0 };
    }
    const attack = ensureObj(working, "attack");
    const variance = ensureObj(working, "variance");
    const unknownMobs = ensureObj(working, "unknown-mobs");

    root.appendChild(h("div", {
      class: "form-banner",
      text: "EliteMobs 一括インポートの既定ルール。編集後は /trinityforge reload → importmobs の順で反映します。"
    }));

    const levelSrc = window.listSelect({
      value: level.source || "ELITEMOBS",
      options: [
        { value: "ELITEMOBS", primary: "EliteMobsのレベルを使う", secondary: "ELITEMOBS" },
        { value: "FIXED", primary: "固定レベル", secondary: "FIXED" }
      ],
      onChange: (v) => { level.source = v; }
    });

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "レベル決定" })],
      [grid([
        field("source", levelSrc, { label: "レベル取得元", key: "source", desc: "ELITEMOBS＝各ボスのlevel欄 / FIXED＝全員同じ" }),
        numField(level, "fixed", { label: "固定Lv", int: true, desc: "source=FIXED のとき使う値" }),
        numField(level, "default", { label: "フォールバックLv", int: true, desc: "EMレベルが数値でないとき" })
      ])]
    ));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "未インポートモブの扱い (unknown-mobs)" })],
      [
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("synthesize", {
            label: "未登録モブも自動でTF管理下に入れる",
            desc: "true(既定)＝combat/mob-profiles.yml に載っていないEliteMobsカスタムボスも、"
              + "スポーン時にこのファイルのランプ(このタブの他の設定)を実レベルで評価して自動導出し、TrinityForge の管理下に入れます。"
              + "無料DL枠ダンジョンを新規追加した直後でも importmobs を回す前から反映され、combat/mob-overrides.yml でステ/ドロップを個別指定できます。"
              + "false＝旧挙動。mob-profiles.yml に載っているモブだけがTF駆動になり、それ以外はEliteMobs側の素の値のまま(mob-overrides.ymlも効きません)。"
          }),
          window.checkboxInput(unknownMobs.synthesize !== false, (v) => { unknownMobs.synthesize = v; })
        ])
      ]
    ));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "既定テーマ" })],
      [textField(theme, "default", {
        label: "テーマ名",
        desc: "dungeon/themes.yml のキー。空＝テーマなし",
        clearable: true,
        placeholder: "例: physical_fortress"
      })]
    ));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "守備の合成式 (基準 + Lv×増分)" })],
      [
        rampEditors(phys, DEFENSE_KEYS, "物理"),
        rampEditors(mag, DEFENSE_KEYS, "魔法"),
        (() => {
          const box = h("div", { class: "mob-defense-block" });
          box.appendChild(sub("防具強度"));
          const ramp = working["armor-strength"];
          box.appendChild(h("div", { class: "stat-row" }, [
            h("span", { class: "mini-label", text: "基準" }),
            window.numberInput(ramp.base, (v) => { ramp.base = v == null ? 0 : v; }),
            h("span", { class: "mini-label", text: "Lvごと" }),
            window.numberInput(ramp["per-level"], (v) => { ramp["per-level"] = v == null ? 0 : v; })
          ]));
          return box;
        })()
      ]
    ));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "最大HP (max-health)" })],
      [rampEditors(working, ["max-health"], "TF駆動の最大HP。0=unconfiguredならEliteMobs側のHPを維持", { showGrowth: true })]
    ));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "攻撃 (attack)" })],
      [
        rampEditors(attack, ATTACK_KEYS, "攻撃側ステ (spawn時にモブへ焼き込み)", { showGrowth: true }),
        h("div", { class: "form-hint", text:
          "damage-modifier の既定は 1.0 (乗算の中立値)。0.0 にすると威力が半減するため、意図せず0にしないよう注意してください。" })
      ]
    ));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "個体ばらつき (variance)" })],
      [
        h("div", { class: "form-hint", text:
          "同一levelでも個体ごとにHP/攻撃を±割合で揺らします。0=ばらつき無し(完全に決定的)。防御(defense-rate/resistance/damage-reduction/flat-defense/armor-strength)は対象外です。" }),
        grid([
          numField(variance, "hp", { label: "HPのばらつき", desc: "0.15 = ±15%" }),
          numField(variance, "attack", { label: "攻撃のばらつき", desc: "0.15 = ±15%" })
        ])
      ]
    ));

    return { element: root, getData: () => working };
  };

  // ============================================================
  // combat/mob-profiles.yml
  // ============================================================
  window.buildMobProfilesForm = function buildMobProfilesForm(data) {
    const working = data && typeof data === "object" ? data : {};
    if (!working.profiles || typeof working.profiles !== "object" || Array.isArray(working.profiles)) {
      working.profiles = {};
    }
    const profiles = working.profiles;
    const root = h("div", { class: "dedicated-form mob-profiles-form card-list" });
    const list = h("div", { class: "card-list-body" });
    root.appendChild(h("div", {
      class: "form-banner",
      text: "EliteMobs 個別モブの守備プロファイル。通常は importmobs で生成し、例外だけここで手編集します。"
    }));
    const filter = window.textInput("", () => render(), "ID / 表示名で絞り込み…");
    root.appendChild(h("div", { class: "form-field", style: "margin:8px 0;" }, [
      h("span", { class: "form-label", text: "検索" }),
      filter
    ]));
    root.appendChild(list);

    function render() {
      list.innerHTML = "";
      const q = (filter.value || "").trim().toLowerCase();
      let ids = Object.keys(profiles);
      if (q) {
        ids = ids.filter((id) => {
          const p = profiles[id] || {};
          return id.toLowerCase().includes(q)
            || String(p["source-name"] || "").toLowerCase().includes(q)
            || String(p["dungeon-theme"] || "").toLowerCase().includes(q);
        });
      }
      if (!Object.keys(profiles).length) {
        list.appendChild(emptyGuide(
          "プロファイルが空です",
          "/trinityforge importmobs で EliteMobs フォルダから一括生成するか、「+ 手書き追加」で例外モブを作ります。"
        ));
      } else if (!ids.length) {
        list.appendChild(emptyGuide("一致なし", "検索条件を変えてください。"));
      }
      for (const id of ids) {
        const p = profiles[id] && typeof profiles[id] === "object" ? profiles[id] : (profiles[id] = {});
        const phys = ensureObj(p, "physical");
        const mag = ensureObj(p, "magical");
        const displayName = String(p["source-name"] || "").trim();
        const head = [
          h("strong", { text: displayName || id }),
          h("span", { class: "entry-sum-id", text: id }),
          h("span", { class: "entry-sum-meta", text: p["entity-type"] || "?" }),
          h("span", { class: "entry-sum-meta", text: `Lv.${p.level != null ? p.level : "?"}` }),
          h("span", { class: "spacer" }),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { delete profiles[id]; render(); }
          })
        ];
        const body = [
          grid([
            numField(p, "level", { label: "レベル", int: true }),
            textField(p, "dungeon-theme", { label: "テーマ", clearable: true, placeholder: "physical_fortress" }),
            textField(p, "source-name", { label: "表示名(参考)", clearable: true }),
            textField(p, "entity-type", { label: "EntityType(参考)", clearable: true }),
            numField(p, "armor-strength", { label: "防具強度" })
          ]),
          defenseFlatBlock("物理守備", phys),
          defenseFlatBlock("魔法守備", mag)
        ];
        list.appendChild(window.collapsibleCard
          ? window.collapsibleCard(head, body, { expanded: false })
          : card(head, body));
      }
      list.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn", type: "button", text: "+ 手書き追加",
          onclick: () => {
            let n = "custom_mob", i = 1;
            while (Object.prototype.hasOwnProperty.call(profiles, n)) n = `custom_mob_${i++}`;
            profiles[n] = {
              level: 1,
              "dungeon-theme": "",
              "source-name": "",
              "entity-type": "ZOMBIE",
              "armor-strength": 0,
              physical: Object.fromEntries(DEFENSE_KEYS.map((k) => [k, 0])),
              magical: Object.fromEntries(DEFENSE_KEYS.map((k) => [k, 0]))
            };
            render();
          }
        })
      ]));
    }

    function defenseFlatBlock(title, obj) {
      return h("div", { class: "mob-defense-block" }, [
        sub(title),
        grid(DEFENSE_KEYS.map((key) => numField(obj, key, { label: key, clearable: true })))
      ]);
    }

    filter.addEventListener("input", () => render());
    render();
    return { element: root, getData: () => working };
  };

  // ============================================================
  // hate/rates.yml
  // ============================================================
  window.buildHateRatesForm = function buildHateRatesForm(data) {
    const working = data && typeof data === "object" ? data : {};
    const limits = ensureObj(working, "limits");
    const decay = ensureObj(working, "decay");
    const eviction = ensureObj(working, "eviction");
    const sweep = ensureObj(working, "sweep");
    const threat = ensureObj(working, "threat");

    const root = h("div", { class: "dedicated-form hate-rates-form" });
    root.appendChild(h("div", {
      class: "form-banner",
      text: "ヘイト（脅威）の仕組みチューニング。タンク等のロール倍率は role-buffs 側です。"
    }));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "上限 (limits)" })],
      [grid([
        numField(limits, "max-tracked-mobs", { label: "追跡モブ上限", int: true, desc: "超えたら古いものから外す" }),
        numField(limits, "max-attackers-per-mob", { label: "1体あたり攻撃者上限", int: true })
      ])]
    ));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "減衰 (decay)" })],
      [grid([
        field("enabled", window.checkboxInput(!!decay.enabled, (v) => { decay.enabled = v; }), {
          label: "時間減衰を使う",
          key: "enabled",
          desc: "OFFが既定（バランス中立）"
        }),
        numField(decay, "per-second", { label: "毎秒減衰率", desc: "0〜1。0＝減衰なし" })
      ])]
    ));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "追い出し・掃除" })],
      [grid([
        numField(eviction, "entry-ttl-seconds", { label: "放置TTL(秒)", int: true, desc: "0＝無効。長い放置エントリだけ回収" }),
        numField(sweep, "interval-ticks", { label: "掃除間隔(tick)", int: true, desc: "20tick=1秒" }),
        numField(threat, "per-damage", { label: "ダメージ→脅威倍率", desc: "与ダメ1あたりの脅威。既定1.0" })
      ])]
    ));

    return { element: root, getData: () => working };
  };

  // ============================================================
  // combat/display.yml
  // ============================================================
  window.buildCombatDisplayForm = function buildCombatDisplayForm(data) {
    const working = data && typeof data === "object" ? data : {};
    const focusHp = ensureObj(working, "focus-hp");
    const damagePopup = ensureObj(working, "damage-popup");
    const damageIndicator = ensureObj(working, "damage-indicator-particles");

    const root = h("div", { class: "dedicated-form combat-display-form" });
    root.appendChild(h("div", {
      class: "form-banner",
      text: "TF独自の頭上表示(HP/ダメージ)設定。EliteMobsフォーク側の頭上表示は二重表示回避のため全OFFにしてある。reloadで反映（表示エンティティは非永続のため次tickから）。"
    }));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "視線HP表示 (focus-hp)" })],
      [grid([
        field("enabled", window.checkboxInput(!!focusHp.enabled, (v) => { focusHp.enabled = v; }), {
          label: "視線HP表示を使う",
          key: "enabled",
          desc: "視線を向けた対象モブの頭上にLv/名前/現HP/最大HPを表示。falseで停止"
        })
      ])]
    ));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "ダメージポップアップ (damage-popup)" })],
      [grid([
        field("enabled", window.checkboxInput(!!damagePopup.enabled, (v) => { damagePopup.enabled = v; }), {
          label: "ダメージポップアップを使う",
          key: "enabled",
          desc: "実際に適用されたTFダメージ量を頭上に一瞬ポップアップ表示。falseで停止（軽量化目的）"
        }),
        numField(damagePopup, "duration-ticks", { label: "表示時間(tick)", int: true, desc: "20tick=1秒。短いほど軽量" }),
        numField(damagePopup, "min-damage", { label: "最小表示ダメージ", desc: "この値未満のダメージは非表示。0＝全て表示" })
      ])]
    ));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "被弾パーティクル上限 (damage-indicator-particles)" })],
      [grid([
        numField(damageIndicator, "max-count", {
          label: "1ヒットの最大個数",
          int: true,
          desc: "バニラは与ダメージに比例して個数を出すためTFのダメージ帯だと画面が埋まる。0＝完全に消す／-1＝制限しない。表示のみでダメージ計算には影響しない。packetevents 導入時のみ有効"
        })
      ])]
    ));

    return { element: root, getData: () => working };
  };
})();
