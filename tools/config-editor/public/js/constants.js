"use strict";

// 共通変数(戦闘定数)エディタ。複数YAMLの横断定数を1画面で編集する。
// サーバの GET /api/constants から受け取った {fields, pillars, skills} を working に保持し、
// スカラーは直接編集、配列(pillars)/マップ(skills)は構造変更時のみ再描画する。
// getData() は working を返し、app.js が PUT /api/constants へ送る。

(function () {
  const h = window.h;

  // ============================================================================
  // 専用効果(dedicated-effects)カタログは廃止(2026-07-23)。解放効果はスキルツリーのノードで
  // プレフィックス付き動的ID(glyph:/brew:/trade:/recipe:/ritual:/drop:/feature:/overenchant:/
  // ars-tier/reward:)として直接設定する。語彙は /api/gate-vocabulary から供給する(tf-skilltree.js)。
  // window.DEDICATED_EFFECTS は他タブ(tf-crafting-features.js等)がフォールバック参照するため
  // 空配列として残す(Array.isArray チェックのみで使われる。中身は今後使わない)。
  // ============================================================================
  window.DEDICATED_EFFECTS = [];

  // スカラー定数のUIメタ情報 (id はサーバ FIELD_SPECS と一致必須)。
  const FIELD_GROUPS = [
    {
      title: "物理 (combat/damage.yml)",
      fields: [
        { id: "physical.base-coefficient", label: "基本係数", kind: "number", desc: "物理の基本ダメージ(gear非依存)全体に掛かる倍率。" },
        { id: "physical.min-component-damage", label: "下限クランプ", kind: "number", desc: "step7: 1コンポーネントがこの値を下回らない床。負値なら最終的に敵を回復し得ます。" }
      ]
    },
    {
      title: "魔法",
      fields: [
        { id: "magical.base-coefficient", label: "基本係数", kind: "number", desc: "魔法(spell/触媒)基本ダメージに掛かる倍率。" },
        { id: "magical.min-component-damage", label: "下限クランプ", kind: "number", desc: "魔法コンポーネントの step7 下限。負値なら最終的に敵を回復し得ます。" },
        { id: "magical.scale-with-combat-level", label: "combatレベル倍率を適用", kind: "boolean", desc: "true: 魔法もレベル倍率で伸びる。false: bypass(グリフ/触媒のみ)。" }
      ]
    },
    {
      title: "武器基本火力式  base = 1 + (useLevel^a / b)",
      fields: [
        { id: "weapon-base-formula.enabled", label: "有効", kind: "boolean", desc: "武器の attack-power を使用可能lvから算出する式を使うか。" },
        { id: "weapon-base-formula.a", label: "指数 a", kind: "number", desc: "使用可能lvの伸びに対する火力カーブの鋭さ(0以上)。" },
        { id: "weapon-base-formula.b", label: "除数 b", kind: "number", desc: "厳密に正(>0)。大きいほど火力が緩やか。" }
      ]
    },
    {
      title: "レベルスケーリング",
      fields: [
        { id: "level-scaling.per-level", label: "レベル毎倍率", kind: "number", desc: "基本ダメージ = base * (1 + per-level * combatLevel)。" }
      ]
    },
    {
      title: "防御(安全弁)",
      fields: [
        { id: "defense.max-mitigation-rate", label: "軽減率上限", kind: "number", desc: "貫通不可の耐性%/被ダメ軽減%の上限(0..1)。0.9で最低10%は通る。防御率%(貫通可)も同じ上限でキャップされる。" },
        { id: "defense.max-dodge-chance", label: "回避率上限", kind: "number", desc: "回避率の上限(0..1)。0.9で最低10%は命中する(無敵回避防止)。" },
        { id: "defense.max-crit-reduction", label: "会心軽減率上限", kind: "number", desc: "防具強度(会心軽減率%)の上限(0..1)。既定1.0=キャップ無し(会心の増加分を最大100%軽減しうるが、相手の会心ダメージが0%未満へ反転することはない)。1.0未満で会心は必ず(1-上限)の増加を残す。" }
      ]
    },
    {
      title: "防御クランプ (旧: 戦闘ダメージタブ)",
      fields: [
        { id: "defense.min-rate", label: "下限(率)", kind: "number", desc: "被ダメージ軽減率の下限。負にすると防御が被ダメージを増幅する側に働く。" },
        { id: "defense.max-rate", label: "上限(率)", kind: "number", desc: "被ダメージ軽減率の上限。1を超えると全軽減を超え、最終ダメージが負(=回復)になりえる。" },
        { id: "defense.min-flat", label: "下限(守備力等flat)", kind: "number", desc: "守備力などflat値の下限クランプ。" },
        { id: "defense.max-flat", label: "上限(守備力等flat)", kind: "number", desc: "守備力などflat値の上限クランプ。" }
      ]
    },
    {
      title: "バニラ防具ミラー",
      fields: [
        { id: "vanilla-armor.defense-rate-per-point", label: "防御率/armor点", kind: "number", desc: "victim の vanilla armor 1点あたり付与する防御率%。" },
        { id: "vanilla-armor.defense-rate-max", label: "防御率上限", kind: "number", desc: "算出した防御率%の上限クランプ(0..1)。" },
        { id: "vanilla-armor.armor-strength-per-point", label: "防具強度(会心軽減率)/toughness点", kind: "number", desc: "vanilla armor_toughness 1点あたりの防具強度(会心軽減率%)。既定0=バニラ防具は会心軽減に寄与しない(後日config調整するフォールバック)。" }
      ]
    },
    {
      title: "出血 (Bleed DoT)",
      fields: [
        { id: "bleed.tick-interval-ticks", label: "適用間隔(ticks)", kind: "int", desc: "適用間のサーバtick(20=1秒)。" },
        { id: "bleed.ticks", label: "適用回数", kind: "int", desc: "出血が続く適用回数。" }
      ]
    },
    {
      title: "攻撃範囲 (AoE) 全体設定 (旧: 戦闘ダメージタブ)",
      fields: [
        { id: "aoe.hit-players", label: "他プレイヤーも巻き込む", kind: "boolean", desc: "有効にすると他プレイヤーもAoEの範囲ダメージ対象になる(PvP)。既定=モブのみ。半径/割合等は武器個別(item-stats)の aoe-radius・aoe-damage-rate・aoe-max-targets で指定する。" }
      ]
    },
    {
      title: "PvP (player→player 専用の抑制)",
      fields: [
        { id: "pvp.enabled", label: "PvP抑制を有効にする", kind: "boolean", desc: "OFFにすると対人も従来どおりモブと同じ計算になる。TFのダメージ式はモブ向けに調整されており、Lv100帯の攻撃力 約1052 に対しプレイヤー最大体力は約33しかないため、OFFのままだと「先に当てた方が確定で即死」になる。" },
        { id: "pvp.damage-multiplier", label: "ダメージ倍率", kind: "number", desc: "PvPダメージに掛ける倍率。下の割合上限にまだ届かない低レベル帯の手触りを調整するつまみ。0にすると対人ダメージが完全に0=実質PvP禁止。" },
        { id: "pvp.max-damage-percent-of-max-health", label: "1発の上限(最大体力比)", kind: "number", desc: "1発で削れる量を被弾者の最大体力の何割までにするか。既定0.15=倒すのに最低7発かかる。倍率だけに頼らずこれを置いているのは、攻撃力が指数で伸びてもプレイヤーの体力はほぼ一定という構造が根本原因だから — 割合上限はスケールフリーなので攻撃カーブを触っても調整し直しが要らない。0で上限なし。" }
      ]
    },
    // 攻撃ステキー対応 / 防御ステキー対応 の欄は撤去(2026-07-24)。2026-07-26 に Java 側の
    // config 経路も撤去され(CMB-31)、キー名は AttackStatKeys / DefenseStatKeys の定数が単一の真実。
    // config からは改名できないので、editor に欄を戻してはいけない。
    {
      title: "combatレベル カーブ (progression/combat-level.yml)",
      fields: [
        { id: "curve.scale", label: "スケール", kind: "number", desc: "combat level = round(max(pillarスコア) * scale)。" },
        { id: "curve.min-level", label: "最小レベル", kind: "int", desc: "クランプ下限。" },
        { id: "curve.max-level", label: "最大レベル", kind: "int", desc: "クランプ上限。" }
      ]
    },
    {
      title: "combatレベル キャッシュ",
      fields: [
        { id: "cache.ttl-seconds", label: "TTL(秒)", kind: "int", desc: "スキルレベル読取キャッシュの有効秒(0..300、0で無効)。" }
      ]
    }
  ];

  window.buildConstantsView = function buildConstantsView(constants) {
    const working = {
      fields: (constants && constants.fields) ? { ...constants.fields } : {},
      pillars: Array.isArray(constants && constants.pillars) ? constants.pillars.map((p) => ({ top: p.top, divisor: p.divisor })) : [],
      skills: (constants && constants.skills) ? { ...constants.skills } : {}
    };

    const root = h("div", { class: "constants-view" });

    for (const group of FIELD_GROUPS) {
      root.appendChild(renderFieldGroup(group, working));
    }
    root.appendChild(renderPillars(working));
    root.appendChild(renderSkills(working));

    return { element: root, getData: () => working };
  };

  function renderFieldGroup(group, working) {
    const body = h("div", { class: "const-body" });
    for (const field of group.fields) {
      body.appendChild(renderField(field, working));
    }
    return h("section", { class: "const-card" }, [
      h("div", { class: "const-card-title", text: group.title }),
      body
    ]);
  }

  function renderField(field, working) {
    const current = working.fields[field.id];
    let control;
    if (field.kind === "boolean") {
      control = window.checkboxInput(current, (v) => { working.fields[field.id] = v; });
    } else if (field.kind === "string") {
      control = window.textInput(current == null ? "" : String(current), (v) => {
        working.fields[field.id] = v.trim();
      });
    } else {
      control = window.numberInput(current, (v) => {
        // 空欄(null)は既存値を保持し、意図しない 0 上書きを避ける。
        if (v === null || v === "") return;
        working.fields[field.id] = v;
      }, { int: field.kind === "int" });
    }
    return h("div", { class: "const-field" }, [
      h("div", { class: "const-field-head" }, [
        h("span", { class: "const-label", text: field.label }),
        control
      ]),
      h("div", { class: "const-desc", text: field.desc })
    ]);
  }

  // pillars: {top:int, divisor:double} の配列 (追加/削除)。
  function renderPillars(working) {
    const card = h("section", { class: "const-card" });
    card.appendChild(h("div", { class: "const-card-title", text: "pillars (柱: 上位top個の合計 / divisor)" }));
    const body = h("div", { class: "const-body" });

    function render() {
      body.innerHTML = "";
      working.pillars.forEach((p, idx) => {
        const row = h("div", { class: "kv-row" }, [
          h("span", { class: "mini-label", text: "top" }),
          window.numberInput(p.top, (v) => { p.top = v == null ? 0 : v; }, { int: true }),
          h("span", { class: "mini-label", text: "divisor" }),
          window.numberInput(p.divisor, (v) => { p.divisor = v == null ? 0 : v; }),
          h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { working.pillars.splice(idx, 1); render(); } })
        ]);
        body.appendChild(row);
      });
      body.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ pillar 追加",
        onclick: () => { working.pillars.push({ top: 1, divisor: 1.0 }); render(); }
      }));
    }
    render();
    card.appendChild(body);
    return card;
  }

  // /api/skills が読めない場合のフォールバックID (server.js の FALLBACK_SKILLS と同一の15スキル)。
  const SKILL_FALLBACK_IDS = [
    "ALCHEMY", "ARCHERY", "ARS_MAGIC", "ARS_SMITHING", "DIGGING", "ENCHANTING",
    "FARMING", "FISHING", "HEAVY_ARMOR", "HEAVY_WEAPONS", "LIGHT_ARMOR",
    "LIGHT_WEAPONS", "MINING", "SMITHING", "WOODCUTTING"
  ];
  // スキルIDの候補一覧。app.js の loadSkills() が /api/skills から window.SKILLS([{id,...}])へ格納済み。
  function skillIdCandidates() {
    return (Array.isArray(window.SKILLS) && window.SKILLS.length)
      ? window.SKILLS.map((s) => s.id) : SKILL_FALLBACK_IDS.slice();
  }

  // skills: スキル名 -> weight のマップ (追加/削除)。
  function renderSkills(working) {
    const card = h("section", { class: "const-card" });
    card.appendChild(h("div", { class: "const-card-title", text: "skills (スキル名 -> weight)" }));
    const body = h("div", { class: "const-body" });

    // スキル名入力のサジェスト用 datalist(候補付き自由入力)。material等と同様に候補源(window.SKILLS)を提示しつつ、
    // 任意文字列の入力(リネーム)も許す。id はビュー内で一意にする。
    const dataListId = "skills-suggest-list";
    const dataList = h("datalist", { id: dataListId });
    for (const id of skillIdCandidates()) dataList.appendChild(h("option", { value: id }));
    card.appendChild(dataList);

    function render() {
      body.innerHTML = "";
      for (const name of Object.keys(working.skills)) {
        body.appendChild(renderSkillRow(name));
      }
      body.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ スキル追加",
        onclick: () => {
          let key = "NEW_SKILL", i = 1;
          while (Object.prototype.hasOwnProperty.call(working.skills, key)) key = `NEW_SKILL_${i++}`;
          working.skills[key] = 1.0;
          render();
        }
      }));
    }

    function renderSkillRow(name) {
      const nameInput = h("input", { class: "field-input", value: name, spellcheck: "false", list: dataListId, autocomplete: "off" });
      nameInput.addEventListener("change", (e) => {
        const nv = e.target.value.trim();
        if (!nv || nv === name) { e.target.value = name; return; }
        if (Object.prototype.hasOwnProperty.call(working.skills, nv)) { alert("同名スキルが既に存在します"); e.target.value = name; return; }
        const rebuilt = {};
        for (const k of Object.keys(working.skills)) rebuilt[k === name ? nv : k] = working.skills[k];
        working.skills = rebuilt;
        render();
      });
      return h("div", { class: "kv-row" }, [
        nameInput,
        h("span", { class: "mini-label", text: "weight" }),
        window.numberInput(working.skills[name], (v) => { working.skills[name] = v == null ? 0 : v; }),
        h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { delete working.skills[name]; render(); } })
      ]);
    }

    render();
    card.appendChild(body);
    return card;
  }
})();
