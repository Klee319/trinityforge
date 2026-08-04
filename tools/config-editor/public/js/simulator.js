"use strict";

// 火力シミュレータ(ダメージ計算プレビュー)。
// 計算式は TrinityForge の実 Java ソースを写経したもの:
//   - DefaultDamageResolver: default = base * (1 + perLevel * level) * coefficient
//   - ComponentDamageCalculator: 8ステップ本体
//   - SymmetricCombatService: 物理/魔法の base 導出、magical.scale-with-combat-level、
//     RESISTANCE ポーション(0.2*(amp+1))の被ダメ軽減%再注入、cappedMitigation
//   - MagicStatSourcePolicy#effectiveBase(ArsPaperフォーク): 魔法基礎 =
//     グリフ基礎(spellBase) + 杖の attack-power × magical.attack-power-scale の【加算】
//     (2026-07-31 修正。以前は「spellBase × 触媒倍率」の乗算でモデル化しており実機と約400倍ずれていた)
//   - VanillaArmorMapping: armor/toughness -> 防御率%/防具強度
//   - WeaponBaseFormula: base = 1 + (useLevel^a / b)
//   - AttackStats/DefenseStats: 各フィールドの [0,1] クランプ / flat>=0
// これは「バランス確認用プレビュー」であり、実挙動の権威は TF 本体にある。

(function () {
  const h = window.h;

  function clamp01(v) { return Math.max(0, Math.min(1, Number.isFinite(v) ? v : 0)); }
  function nonNeg(v) { return Math.max(0, Number.isFinite(v) ? v : 0); }
  function round(v) { return Math.round(v * 1000) / 1000; }

  function pickNum(defaults, id, fallback) {
    const v = defaults && defaults.fields ? defaults.fields[id] : undefined;
    return (v === undefined || v === null || !Number.isFinite(Number(v))) ? fallback : Number(v);
  }
  function pickBool(defaults, id, fallback) {
    const v = defaults && defaults.fields ? defaults.fields[id] : undefined;
    return typeof v === "boolean" ? v : fallback;
  }

  function initModel(defaults) {
    return {
      mode: "physical",
      c: {
        physCoef: pickNum(defaults, "physical.base-coefficient", 1.0),
        magCoef: pickNum(defaults, "magical.base-coefficient", 1.0),
        perLevel: pickNum(defaults, "level-scaling.per-level", 0.01),
        physMin: pickNum(defaults, "physical.min-component-damage", 1.0),
        magMin: pickNum(defaults, "magical.min-component-damage", 1.0),
        magScale: pickBool(defaults, "magical.scale-with-combat-level", true),
        formulaA: pickNum(defaults, "weapon-base-formula.a", 2.0),
        formulaB: pickNum(defaults, "weapon-base-formula.b", 100.0),
        vaRatePerPoint: pickNum(defaults, "vanilla-armor.defense-rate-per-point", 0.04),
        vaRateMax: pickNum(defaults, "vanilla-armor.defense-rate-max", 0.8),
        // 防具強度(会心軽減率%)/toughness点。Java 既定 0(バニラ防具は会心軽減に寄与しないフォールバック)。
        vaStrengthPerPoint: pickNum(defaults, "vanilla-armor.armor-strength-per-point", 0.0),
        maxMitigation: pickNum(defaults, "defense.max-mitigation-rate", 0.9),
        maxCritReduction: pickNum(defaults, "defense.max-crit-reduction", 1.0),
        // 杖(触媒)の attack-power を魔法基礎ダメージへ加算するときの係数。Java 既定 1.0(100%加算)、範囲[0,10]。
        magAttackPowerScale: pickNum(defaults, "magical.attack-power-scale", 1.0)
      },
      combatLevel: 0,
      physMethod: "formula",
      attackPower: 5,
      useLevel: 20,
      vanillaAttack: 7,
      spellBase: 6,
      // 杖/触媒(use-skill: ARS_MAGIC)の attack-power。魔法基礎へ「加算」される(乗算ではない)。
      catalystAttackPower: 0,
      critChance: 0,
      critDamage: 0.5,
      penetration: 0,
      flatBonus: 0,
      percentBonus: 0,
      damageModifier: 0,
      fixedDamage: 0,
      defMethod: "typed",
      defenseRate: 0,
      resistance: 0,
      damageReduction: 0,
      flatDefense: 0,
      armorStrength: 0,
      dodge: 0,
      armorPoints: 0,
      toughnessPoints: 0,
      potionAmp: null
    };
  }

  // MagicStatSourcePolicy#scaledAttackPower の写経: 負の attack-power は0扱い、係数は[0,10]へクランプ。
  function scaledAttackPower(power, scale) {
    const p = Number.isFinite(power) ? power : 0;
    if (p <= 0) return 0;
    const s = Number.isFinite(scale) ? Math.max(0, Math.min(10, scale)) : 1;
    return p * s;
  }

  // 攻撃側の基本 attack-power (物理: 指定方法別 / 魔法: spellBase + 杖のattack-power×係数の【加算】)。
  function deriveAttackPower(m) {
    if (m.mode === "magical") {
      return nonNeg(m.spellBase) + scaledAttackPower(m.catalystAttackPower, m.c.magAttackPowerScale);
    }
    if (m.physMethod === "formula") {
      const b = m.c.formulaB > 0 ? m.c.formulaB : 1000;
      return 1 + Math.pow(Math.max(0, m.useLevel), m.c.formulaA) / b;
    }
    if (m.physMethod === "vanilla") return m.vanillaAttack;
    return m.attackPower;
  }

  // level-scaling を反映した基本ダメージ(パイプライン step1 の入力 defaultDamage)。
  function deriveDefaultDamage(m) {
    const level = m.mode === "physical" ? m.combatLevel : (m.c.magScale ? m.combatLevel : 0);
    const coef = m.mode === "physical" ? m.c.physCoef : m.c.magCoef;
    return deriveAttackPower(m) * (1 + m.c.perLevel * Math.max(0, level)) * coef;
  }

  // 防御プロファイルを実コードのクランプ/合算/上限に合わせて確定する。
  function deriveDefense(m) {
    let defenseRate; let resistance; let damageReduction; let flatDefense; let armorStrength;
    if (m.defMethod === "vanilla") {
      defenseRate = Math.min(nonNeg(m.c.vaRateMax), nonNeg(m.armorPoints) * nonNeg(m.c.vaRatePerPoint));
      armorStrength = nonNeg(m.toughnessPoints) * nonNeg(m.c.vaStrengthPerPoint);
      resistance = 0; damageReduction = 0; flatDefense = 0;
    } else {
      defenseRate = m.defenseRate; resistance = m.resistance; damageReduction = m.damageReduction;
      flatDefense = m.flatDefense; armorStrength = m.armorStrength;
    }
    // RESISTANCE ポーション: 0.2*(amp+1) を被ダメ軽減%へ再注入(合算 -> [0,1]クランプ -> 上限)。
    let potion = 0;
    if (m.potionAmp !== null && Number.isFinite(m.potionAmp) && m.potionAmp >= 0) {
      potion = 0.2 * (m.potionAmp + 1);
    }
    defenseRate = clamp01(defenseRate);
    resistance = clamp01(resistance);
    damageReduction = clamp01(damageReduction + potion);
    flatDefense = nonNeg(flatDefense);
    armorStrength = nonNeg(armorStrength);
    // cappedMitigation: 防御率%/耐性%/被ダメ軽減% の3種とも max-mitigation-rate で上限。
    // 防御率%は貫通可能だが、貫通0のケース(mob/多くのプレイヤー)では無条件の完全免疫化を防ぐため、
    // 2026-07 以降は防御率%もここでキャップされる(DefenseStats#cappedMitigation)。
    const cap = clamp01(m.c.maxMitigation);
    defenseRate = Math.min(defenseRate, cap);
    resistance = Math.min(resistance, cap);
    damageReduction = Math.min(damageReduction, cap);
    return { defenseRate, resistance, damageReduction, flatDefense, armorStrength };
  }

  // 8ステップ本体(ComponentDamageCalculator 写経)。crit=会心の有無で分岐。
  function computeSteps(m, crit) {
    const D = deriveDefaultDamage(m);
    const d = deriveDefense(m);
    const penetration = clamp01(m.penetration);
    const critDamage = Number.isFinite(m.critDamage) ? m.critDamage : 0;
    const minClamp = m.mode === "physical" ? m.c.physMin : m.c.magMin;

    let base = D + m.flatBonus + D * m.percentBonus;
    const s1 = base;
    // 会心: 防具強度(会心軽減率%)が会心の増加分だけを (1-r) 倍に軽減する。r は [0,1] 構造クランプ後、
    // defense.max-crit-reduction で上限cap(Java: cappedCritReduction)。既定1.0=キャップ無し。
    const critReduction = Math.min(clamp01(d.armorStrength), clamp01(m.c.maxCritReduction));
    if (crit) base *= (1 + Math.max(0, critDamage) * (1 - critReduction));
    const s2 = base;
    base *= (1 - d.defenseRate * (1 - penetration));
    const s3 = base;
    base *= (1 - d.resistance);
    const s4 = base;
    base *= (1 + m.damageModifier);
    base *= (1 - d.damageReduction);
    const s5 = base;
    const preFlat = base;
    // 6. 固定防御減算は守備力(flat)のみ。防具強度は step2 の会心軽減へ役割変更したためここには含めない。
    base -= d.flatDefense;
    const s6 = base;
    base = Math.max(base, minClamp);
    const s7 = base;
    const refund = Math.max(0, Math.min(m.fixedDamage, preFlat - base));
    base += refund;
    const s8 = base;
    return { defaultDamage: D, defense: d, steps: [s1, s2, s3, s4, s5, s6, s7, s8], final: base };
  }

  const STEP_LABELS = [
    "1. 基礎加算  default + 固定追加 + default*割合追加",
    "2. 会心  x(1 + 会心ダメージ*(1 - 防具強度[会心軽減率]))",
    "3. 防御率(貫通可)  x(1 - 防御率*(1 - 貫通))",
    "4. 耐性(貫通不可)  x(1 - 耐性)",
    "5. 補正/軽減  x(1 + ダメージ補正) x(1 - 被ダメ軽減)",
    "6. 固定防御減算  - 守備力",
    "7. 下限クランプ  max(値, 下限)",
    "8. 固定ダメージ払戻し  + refund"
  ];

  window.buildSimulatorView = function buildSimulatorView(defaults) {
    const model = initModel(defaults);
    const root = h("div", { class: "sim-view" });
    root.appendChild(h("div", { class: "sim-note", text: "これは TrinityForge の計算式を写したバランス確認用プレビューです。実挙動の権威は TF 本体にあります。" }));

    const grid = h("div", { class: "sim-grid" });
    const form = h("div", { class: "sim-form" });
    const output = h("div", { class: "sim-output" });
    grid.appendChild(form);
    grid.appendChild(output);
    root.appendChild(grid);

    function recalc() { renderOutput(output, model); }
    function rebuild() { renderForm(form, model, recalc, rebuild); recalc(); }
    rebuild();

    return { element: root };
  };

  // ---- form helpers ----
  function numRow(label, model, key, recalc, opts) {
    const options = opts || {};
    const input = window.numberInput(model[key], (v) => {
      model[key] = v === null || v === "" ? 0 : Number(v);
      recalc();
    }, { int: options.int });
    return h("label", { class: "sim-field" }, [h("span", { class: "sim-label", text: label }), input]);
  }

  function constNumRow(label, model, key, recalc, opts) {
    const options = opts || {};
    const input = window.numberInput(model.c[key], (v) => {
      model.c[key] = v === null || v === "" ? 0 : Number(v);
      recalc();
    }, { int: options.int });
    return h("label", { class: "sim-field" }, [h("span", { class: "sim-label", text: label }), input]);
  }

  function checkRow(label, model, key, recalc, onC) {
    const input = window.checkboxInput(model[key], (v) => { model[key] = v; recalc(); if (onC) onC(); });
    return h("label", { class: "sim-field inline" }, [input, h("span", { class: "sim-label", text: label })]);
  }

  function constCheckRow(label, model, key, recalc) {
    const input = window.checkboxInput(model.c[key], (v) => { model.c[key] = v; recalc(); });
    return h("label", { class: "sim-field inline" }, [input, h("span", { class: "sim-label", text: label })]);
  }

  function radioRow(label, model, key, options, recalc, rebuild) {
    const box = h("div", { class: "sim-radios" });
    for (const opt of options) {
      const id = `${key}-${opt.value}`;
      const radio = h("input", { type: "radio", name: key, value: opt.value, id });
      if (model[key] === opt.value) radio.checked = true;
      radio.addEventListener("change", () => { model[key] = opt.value; rebuild(); });
      box.appendChild(h("label", { class: "sim-radio" }, [radio, h("span", { text: opt.label })]));
    }
    return h("div", { class: "sim-field-group" }, [h("span", { class: "sim-label strong", text: label }), box]);
  }

  function section(title, children) {
    return h("section", { class: "sim-section" }, [h("div", { class: "sim-section-title", text: title }), ...children]);
  }

  function renderForm(form, model, recalc, rebuild) {
    form.innerHTML = "";

    form.appendChild(radioRow("モード", model, "mode",
      [{ value: "physical", label: "物理" }, { value: "magical", label: "魔法" }], recalc, rebuild));

    // ---- 攻撃側 ----
    const atk = [];
    atk.push(numRow("combatレベル", model, "combatLevel", recalc, { int: true }));
    if (model.mode === "physical") {
      atk.push(radioRow("基本火力の指定方法", model, "physMethod", [
        { value: "attack-power", label: "attack-power直接" },
        { value: "formula", label: "使用可能lvから式(1+lv^a/b)" },
        { value: "vanilla", label: "バニラ攻撃力値" }
      ], recalc, rebuild));
      if (model.physMethod === "attack-power") atk.push(numRow("attack-power", model, "attackPower", recalc));
      else if (model.physMethod === "formula") atk.push(numRow("使用可能lv (useLevel)", model, "useLevel", recalc, { int: true }));
      else atk.push(numRow("バニラ攻撃力", model, "vanillaAttack", recalc));
    } else {
      atk.push(numRow("spellBase (グリフ基礎+増減グリフ)", model, "spellBase", recalc));
      atk.push(numRow("杖/触媒の attack-power", model, "catalystAttackPower", recalc));
    }
    atk.push(numRow("会心率 (0..1)", model, "critChance", recalc));
    atk.push(numRow("会心ダメージ (例0.5=+50%)", model, "critDamage", recalc));
    atk.push(numRow("貫通率 (0..1)", model, "penetration", recalc));
    atk.push(numRow("固定追加ダメージ", model, "flatBonus", recalc));
    atk.push(numRow("割合追加ダメージ (例0.2=+20%)", model, "percentBonus", recalc));
    atk.push(numRow("ダメージ補正 (例0.1=+10%)", model, "damageModifier", recalc));
    atk.push(numRow("固定ダメージ (払戻し)", model, "fixedDamage", recalc));
    form.appendChild(section("攻撃側", atk));

    // ---- 防御側 ----
    const def = [];
    def.push(radioRow("指定方法", model, "defMethod", [
      { value: "typed", label: "typed直接指定" },
      { value: "vanilla", label: "バニラ armor/toughness" }
    ], recalc, rebuild));
    if (model.defMethod === "typed") {
      def.push(numRow("防御率 (0..1)", model, "defenseRate", recalc));
      def.push(numRow("耐性 (0..1)", model, "resistance", recalc));
      def.push(numRow("被ダメ軽減 (0..1)", model, "damageReduction", recalc));
      def.push(numRow("守備力 (flat)", model, "flatDefense", recalc));
      def.push(numRow("防具強度 (会心軽減率 0..1)", model, "armorStrength", recalc));
      def.push(numRow("回避率 (0..1)", model, "dodge", recalc));
    } else {
      def.push(numRow("armor ポイント", model, "armorPoints", recalc));
      def.push(numRow("armor_toughness ポイント", model, "toughnessPoints", recalc));
      def.push(numRow("回避率 (0..1)", model, "dodge", recalc));
    }
    const potionInput = window.numberInput(model.potionAmp, (v) => {
      model.potionAmp = v === null || v === "" ? null : Number(v);
      recalc();
    }, { int: true });
    def.push(h("label", { class: "sim-field" }, [h("span", { class: "sim-label", text: "RESISTANCE増幅レベル(空=なし)" }), potionInput]));
    form.appendChild(section("防御側", def));

    // ---- 定数(既定は damage.yml から / ここで上書き可) ----
    const cons = [];
    cons.push(constNumRow("physical 基本係数", model, "physCoef", recalc));
    cons.push(constNumRow("magical 基本係数", model, "magCoef", recalc));
    cons.push(constNumRow("level per-level", model, "perLevel", recalc));
    cons.push(constNumRow("physical 下限", model, "physMin", recalc));
    cons.push(constNumRow("magical 下限", model, "magMin", recalc));
    cons.push(constCheckRow("magical にレベル倍率適用", model, "magScale", recalc));
    cons.push(constNumRow("魔法への attack-power 係数", model, "magAttackPowerScale", recalc));
    cons.push(constNumRow("式 指数a", model, "formulaA", recalc));
    cons.push(constNumRow("式 除数b", model, "formulaB", recalc));
    cons.push(constNumRow("軽減率上限", model, "maxMitigation", recalc));
    cons.push(constNumRow("会心軽減率上限", model, "maxCritReduction", recalc));
    cons.push(constNumRow("バニラ 防御率/point", model, "vaRatePerPoint", recalc));
    cons.push(constNumRow("バニラ 防御率上限", model, "vaRateMax", recalc));
    cons.push(constNumRow("バニラ 防具強度(会心軽減率)/point", model, "vaStrengthPerPoint", recalc));
    form.appendChild(section("定数 (既定: combat/damage.yml、上書き可)", cons));
  }

  // ---- output ----
  function renderOutput(output, model) {
    output.innerHTML = "";
    const nonCrit = computeSteps(model, false);
    const crit = computeSteps(model, true);
    const dodge = clamp01(model.dodge);

    output.appendChild(h("div", { class: "sim-out-head", text: `基本ダメージ default = ${round(nonCrit.defaultDamage)}  (モード: ${model.mode === "physical" ? "物理" : "魔法"})` }));

    const d = nonCrit.defense;
    output.appendChild(h("div", { class: "sim-defense-line", text: `防御確定値: 防御率 ${round(d.defenseRate)} / 耐性 ${round(d.resistance)} / 被ダメ軽減 ${round(d.damageReduction)} / 守備力 ${round(d.flatDefense)} / 防具強度 ${round(d.armorStrength)}` }));

    const table = h("table", { class: "sim-table" });
    table.appendChild(h("thead", {}, h("tr", {}, [
      h("th", { text: "ステップ" }),
      h("th", { class: "num", text: "非会心" }),
      h("th", { class: "num", text: "会心" })
    ])));
    const tbody = h("tbody");
    for (let i = 0; i < STEP_LABELS.length; i++) {
      tbody.appendChild(h("tr", {}, [
        h("td", { text: STEP_LABELS[i] }),
        h("td", { class: "num", text: String(round(nonCrit.steps[i])) }),
        h("td", { class: "num", text: String(round(crit.steps[i])) })
      ]));
    }
    table.appendChild(tbody);
    /* R (2026-08-04): 狭い画面で列が切り落とされないようスクロール枠に入れる (.respack-table と同じ理由)。 */
    output.appendChild(h("div", { class: "table-scroll" }, [table]));

    // 最終値サマリ
    const expected = model.critChance * crit.final + (1 - clamp01(model.critChance)) * nonCrit.final;
    const afterDodge = expected * (1 - dodge);
    const summary = h("div", { class: "sim-summary" });
    summary.appendChild(finalCard("非会心 最終ダメージ", round(nonCrit.final)));
    summary.appendChild(finalCard("会心 最終ダメージ", round(crit.final)));
    summary.appendChild(finalCard("会心率反映 期待値", round(expected)));
    summary.appendChild(finalCard(`回避反映後 期待値 (回避 ${round(dodge)})`, round(afterDodge)));
    output.appendChild(summary);
  }

  function finalCard(label, value) {
    return h("div", { class: "sim-final-card" }, [
      h("div", { class: "sim-final-label", text: label }),
      h("div", { class: "sim-final-value", text: String(value) })
    ]);
  }

  // DOM を作らない純関数だけを公開する(単体テスト用)。式が実 Java から乖離すると
  // 「調整に使う唯一のツールが黙ってずれる」ので、ここをテストで縛る。
  window.SIMULATOR_LOGIC = {
    initModel, scaledAttackPower, deriveAttackPower, deriveDefaultDamage, computeSteps
  };
})();
