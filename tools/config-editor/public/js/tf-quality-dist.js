"use strict";

// 品質分布プレビュー (品質設定タブ埋め込み)。
// 「品質値(mode)」を入れると、実際に手に入るアイテムの品質ティア分布を可視化する。
// 計算式は TrinityForge の実 Java ソースを写経したもの (実挙動の権威は TF 本体):
//   品質ティア抽選 (0..maxQuality) — 制作・ドロップとも「上下非対称な正規分布(split-normal)」:
//     CraftQualityPolicy.resolveQualityNormal: 1つの N(0,1) サンプル Z を引き、
//       Z >= 0 なら round(mode + Z*spreadUp)   (上振れ側は spread-up)
//       Z <  0 なら round(mode + Z*spreadDown)  (下振れ側は spread-down)
//     を [0, maxQuality] に clamp。上下の質量は各 0.5 で、σ は各側の広がりだけを変える。
//   ⇒ 連続近似の CDF は F(x)=Φ((x-mode)/spreadUp) (x>=mode) / Φ((x-mode)/spreadDown) (x<mode)。
// 段2「ランダムロール層」: item-stats.yml の各アイテム `random:` に書いたステは、品質に応じたロール分布で
//   レンジ{min,max}内を抽選される。段1と同じ上下非対称の正規分布(split-normal)で、中心modeは品質比例:
//     qNorm = 品質 / maxQuality                                    (ロール中心。0=min寄り, 1=max寄り)
//     σ = Z>=0 ? roll-spread-up : roll-spread-down  (Z=標準正規)    (上振れ/下振れ側の広がり)
//     R = clamp(qNorm + Z*σ, 0, 1)                                 (実ロール到達度=範囲内の割合)
//   到達割合 x∈[0,1] の CDF は split-normal: F(x)=Φ((x-qNorm)/σup) (x>=qNorm) / Φ((x-qNorm)/σdown) (x<qNorm)。
//   [0,1] にclampされるため、低品質でも稀にmax、高品質でも稀にminが出る(正規分布の裾)。

(function () {
  const SVGNS = "http://www.w3.org/2000/svg";

  function clampInt(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  // 標準正規CDF Φ (erf近似 Abramowitz & Stegun 7.1.26)。
  function erf(x) {
    const s = x < 0 ? -1 : 1;
    const ax = Math.abs(x);
    const t = 1 / (1 + 0.3275911 * ax);
    const y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-ax * ax);
    return s * y;
  }
  function normalCdf(z) { return 0.5 * (1 + erf(z / Math.SQRT2)); }

  // split-normal の CDF。mode を境に上側は spreadUp、下側は spreadDown を使う。σ<=0 の側は
  // 微小値でその側を mode に潰す(preview 近似)。
  function splitCdf(x, mode, sUp, sDown) {
    const s = x >= mode ? Math.max(1e-9, sUp) : Math.max(1e-9, sDown);
    return normalCdf((x - mode) / s);
  }

  // 品質ティア確率 P[q] (q=0..maxQuality)。連続 split-normal を round で離散化 → 各ティアの
  // 区間 [q-0.5, q+0.5) に落ちる確率。端はクランプで累積。合計は必ず 1。
  function tierProbs(mode, sUp, sDown, maxQuality) {
    const probs = new Array(Math.max(0, maxQuality) + 1).fill(0);
    if (maxQuality < 0) return probs;
    if (sUp <= 0 && sDown <= 0) { probs[clampInt(Math.round(mode), 0, maxQuality)] += 1; return probs; }

    for (let q = 0; q <= maxQuality; q++) {
      const pLo = q === 0 ? 0 : splitCdf(q - 0.5, mode, sUp, sDown);           // 0 以下は全て 0 にクランプ
      const pHi = q === maxQuality ? 1 : splitCdf(q + 0.5, mode, sUp, sDown);  // max 以上は全て max にクランプ
      probs[q] = Math.max(0, pHi - pLo);
    }
    return probs;
  }

  // 段2 ロール到達度の分布。ある品質レベルで、到達割合 x∈[0,1] (0=min, 1=max) の histogram を返す。
  // 中心 mode = inset + qNorm*(1-2*inset) の split-normal を [0,1] にclamp。両端はclampで累積(段1 tierProbs
  // と同じ端処理)。inset を上げると品質0/max でも中心が端から内側へ寄り、両側に裾のある山形になる。
  function reachDistribution(quality, maxQuality, rollSpreadUp, rollSpreadDown, rollCenterInset, bins) {
    const nBins = Math.max(1, bins || 20);
    const qNorm = maxQuality <= 0 ? 0 : clampInt(Math.round(quality), 0, maxQuality) / maxQuality;
    const inset = Math.max(0, Math.min(0.49, Number.isFinite(rollCenterInset) ? rollCenterInset : 0));
    const mode = inset + qNorm * (1 - 2 * inset);
    const cdf = (x) => splitCdf(x, mode, rollSpreadUp, rollSpreadDown);
    const probs = [];
    for (let i = 0; i < nBins; i++) {
      const a = i / nBins, b = (i + 1) / nBins;
      const lo = i === 0 ? 0 : cdf(a);            // 0未満のロールは 0 にクランプ → 先頭binへ
      const hi = i === nBins - 1 ? 1 : cdf(b);    // 1超のロールは 1 にクランプ → 末尾binへ
      probs.push({ a, b, mid: (a + b) / 2, p: Math.max(0, hi - lo) });
    }
    let expReach = 0;
    for (const p of probs) expReach += p.mid * p.p; // clamp込みの期待到達割合(bin近似)
    return { qNorm, mode, expReach, probs };
  }
  window.tfReachDistribution = reachDistribution;

  window.tfQualityDistribution = function tfQualityDistribution(params) {
    const maxQuality = Math.max(0, Math.round(params.maxQuality));
    const mode = Math.round(params.mode);
    const spreadUp = Math.max(0, params.spreadUp);
    const spreadDown = Math.max(0, params.spreadDown);

    const probs = tierProbs(mode, spreadUp, spreadDown, maxQuality);
    let expTier = 0;
    for (let q = 0; q <= maxQuality; q++) expTier += q * probs[q];

    return {
      maxQuality, mode, spreadUp, spreadDown,
      tierProbs: probs.map((p, q) => ({ q, p })),
      expTier,
      topTierProb: probs[maxQuality] || 0,
      bottomTierProb: probs[0] || 0
    };
  };

  // ---------- SVG 描画 ----------
  function svg(tag, attrs) {
    const el = document.createElementNS(SVGNS, tag);
    for (const k in attrs) el.setAttribute(k, attrs[k]);
    return el;
  }

  // 汎用棒グラフ。data = [{label, value, hint, color}]。valueMax 未指定なら max(value)。
  function barChart(title, data, opts) {
    const options = opts || {};
    const W = 100, H = 46, padL = 3, padR = 3, padTop = 8, padBottom = 9;
    const box = document.createElementNS(SVGNS, "svg");
    box.setAttribute("viewBox", `0 0 ${W} ${H}`);
    box.setAttribute("class", "qd-chart");
    box.setAttribute("preserveAspectRatio", "none");

    const vMax = options.valueMax != null ? options.valueMax : Math.max(1e-9, ...data.map((d) => d.value));
    const plotW = W - padL - padR;
    const plotH = H - padTop - padBottom;
    const n = data.length || 1;
    const slot = plotW / n;
    const gap = Math.min(slot * 0.2, 0.8);
    const bw = Math.max(0.4, slot - gap);

    // 基線
    box.appendChild(svg("line", { x1: padL, y1: padTop + plotH, x2: W - padR, y2: padTop + plotH, stroke: "var(--border)", "stroke-width": 0.3 }));

    for (let i = 0; i < data.length; i++) {
      const d = data[i];
      const hgt = vMax > 0 ? (d.value / vMax) * plotH : 0;
      const x = padL + i * slot + (slot - bw) / 2;
      const y = padTop + plotH - hgt;
      const rect = svg("rect", {
        x: x.toFixed(2), y: y.toFixed(2), width: bw.toFixed(2), height: Math.max(0, hgt).toFixed(2),
        rx: 0.3, fill: d.color || "var(--accent)"
      });
      const title = document.createElementNS(SVGNS, "title");
      title.textContent = d.hint || `${d.label}: ${d.value}`;
      rect.appendChild(title);
      box.appendChild(rect);
      if (options.showLabels !== false && n <= 14) {
        const t = svg("text", { x: (x + bw / 2).toFixed(2), y: (padTop + plotH + 6).toFixed(2), "text-anchor": "middle", class: "qd-axis-text" });
        t.textContent = d.label;
        box.appendChild(t);
      }
    }
    if (options.markerX != null) {
      const mx = padL + clampInt(options.markerX, 0, 1) * plotW;
      box.appendChild(svg("line", { x1: mx.toFixed(2), y1: padTop - 2, x2: mx.toFixed(2), y2: padTop + plotH, stroke: "var(--warn)", "stroke-width": 0.5, "stroke-dasharray": "1.5 1" }));
    }

    const wrap = window.h("div", { class: "qd-panel" }, [
      window.h("div", { class: "qd-panel-title", text: title }),
      box
    ]);
    if (options.subtitle) wrap.appendChild(window.h("div", { class: "qd-panel-sub", text: options.subtitle }));
    return wrap;
  }

  // ---------- 埋め込みパネル ----------
  // getConfig(): { maxQuality, spreadUp, spreadDown } を「編集中フォームの生値」から返す。
  window.buildQualityDistPanel = function buildQualityDistPanel(getConfig) {
    const state = { mode: null };

    const root = window.h("div", { class: "qd-root" });
    root.appendChild(window.h("div", { class: "qd-note", text: "品質値(mode)を入れると、(段1)実際に手に入る品質ティアの分布と、(段2)その品質での random ステのロール到達分布を試算します。段1は制作・敵ドロップ共通の上下非対称な正規分布(spread-up/spread-down)、段2は中心mode(品質比例)から上下非対称の正規分布(roll-spread-up/roll-spread-down)で範囲内を抽選し、roll-center-inset で中心を端から押し込めます。すべて上のフィールドの現在値を反映します。実挙動の権威は TF 本体です。" }));

    const controls = window.h("div", { class: "qd-controls" });
    const charts = window.h("div", { class: "qd-charts" });
    const stats = window.h("div", { class: "qd-stats" });
    root.appendChild(controls);
    root.appendChild(stats);
    root.appendChild(charts);

    function cfg() {
      const c = getConfig() || {};
      const maxQuality = Number.isFinite(c.maxQuality) ? Math.max(0, Math.round(c.maxQuality)) : 9;
      const spreadUp = Number.isFinite(c.spreadUp) ? Math.max(0, c.spreadUp) : 1.5;
      const spreadDown = Number.isFinite(c.spreadDown) ? Math.max(0, c.spreadDown) : 1.5;
      const rollSpreadUp = Number.isFinite(c.rollSpreadUp) ? Math.max(0, c.rollSpreadUp) : 0.15;
      const rollSpreadDown = Number.isFinite(c.rollSpreadDown) ? Math.max(0, c.rollSpreadDown) : 0.15;
      const rollCenterInset = Number.isFinite(c.rollCenterInset) ? Math.max(0, Math.min(0.49, c.rollCenterInset)) : 0.0;
      return { maxQuality, spreadUp, spreadDown, rollSpreadUp, rollSpreadDown, rollCenterInset };
    }

    function redraw() {
      const c = cfg();
      if (state.mode == null) state.mode = Math.round(c.maxQuality / 2);
      state.mode = clampInt(state.mode, 0, c.maxQuality);

      const dist = window.tfQualityDistribution({
        mode: state.mode, spreadUp: c.spreadUp, spreadDown: c.spreadDown, maxQuality: c.maxQuality
      });

      // --- controls (mode スライダのみ。σ は上のフィールドで編集) ---
      controls.innerHTML = "";
      const modeRange = window.h("input", { type: "range", min: "0", max: String(c.maxQuality), step: "1", value: String(state.mode), class: "qd-range" });
      modeRange.addEventListener("input", () => { state.mode = Number(modeRange.value); redraw(); });
      const modeNum = window.numberInput(state.mode, (v) => { state.mode = v == null ? 0 : Number(v); redraw(); }, { int: true });
      controls.appendChild(window.h("label", { class: "qd-field" }, [
        window.h("span", { class: "qd-label", text: "品質値 mode (0.." + c.maxQuality + ")" }), modeRange, modeNum
      ]));

      // --- stats ---
      stats.innerHTML = "";
      const reachStat = window.tfReachDistribution(state.mode, c.maxQuality, c.rollSpreadUp, c.rollSpreadDown, c.rollCenterInset, 20);
      const cards = [
        { label: "期待品質ティア", value: dist.expTier.toFixed(2) + " / " + c.maxQuality },
        { label: "最上位ティア確率", value: (dist.topTierProb * 100).toFixed(1) + "%" },
        { label: "最下位ティア確率", value: (dist.bottomTierProb * 100).toFixed(1) + "%" },
        { label: "上振れσ / 下振れσ", value: c.spreadUp.toFixed(2) + " / " + c.spreadDown.toFixed(2) },
        { label: "ロール中心/期待(品質" + state.mode + ")", value: (reachStat.mode * 100).toFixed(0) + "% / " + (reachStat.expReach * 100).toFixed(0) + "%" }
      ];
      for (const cd of cards) {
        stats.appendChild(window.h("div", { class: "qd-stat-card" }, [
          window.h("div", { class: "qd-stat-label", text: cd.label }),
          window.h("div", { class: "qd-stat-value", text: cd.value })
        ]));
      }

      // --- chart ---
      charts.innerHTML = "";
      const tierData = dist.tierProbs.map((t) => ({
        label: String(t.q), value: t.p,
        color: t.q === c.maxQuality ? "var(--ok)" : (t.q === state.mode ? "var(--warn)" : "var(--accent)"),
        hint: "品質" + t.q + ": " + (t.p * 100).toFixed(1) + "%"
      }));
      charts.appendChild(barChart("品質ティア確率 (どのティアに落ちるか)", tierData, {
        valueMax: Math.max(...tierData.map((d) => d.value), 1e-9),
        subtitle: "横軸=品質ティア 0.." + c.maxQuality + " / 縦軸=確率。mode(橙)を境に上側は上振れσ、下側は下振れσ。"
      }));

      // --- 段2: ロール到達分布 (選択中の品質 mode でのランダムロール層) ---
      const reach = window.tfReachDistribution(state.mode, c.maxQuality, c.rollSpreadUp, c.rollSpreadDown, c.rollCenterInset, 20);
      const reachData = reach.probs.map((b) => ({
        label: "",
        value: b.p,
        // 中心 mode(橙)を境に、下側=下振れσ(accent) / 上側=上振れσ(ok)。
        color: b.mid < reach.mode ? "var(--accent)" : "var(--ok)",
        hint: "到達割合 " + b.a.toFixed(2) + "〜" + b.b.toFixed(2) + ": " + (b.p * 100).toFixed(1) + "%"
      }));
      charts.appendChild(barChart("ロール到達分布 (品質 " + state.mode + " のとき / random ステの範囲内どこに出るか)", reachData, {
        valueMax: Math.max(...reachData.map((d) => d.value), 1e-9),
        markerX: reach.mode,
        showLabels: false,
        subtitle: "横軸=到達割合 0(min)〜1(max) / 縦軸=確率。橙線=中心mode(" + (reach.mode * 100).toFixed(0)
          + "% / インセット" + (c.rollCenterInset * 100).toFixed(0) + "%)。中心を境に上側は上振れσ・下側は下振れσ。期待到達="
          + (reach.expReach * 100).toFixed(0) + "%(端clamp込み)。インセットを上げると極端な品質でも山形になる。"
      }));
    }

    redraw();
    return { element: root, redraw };
  };
})();
