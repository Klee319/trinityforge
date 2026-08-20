/**
 * config-editor の「%ステなのに割合のまま入力させている欄」の監査
 * (依存ゼロ / ヘッドレス Chrome + CDP)。
 *
 * **見つけたいのは宣言のズレではなく「どのフォームが変換を通していないか」**。
 * PERCENT ステの値入力は `statValueControl` を通すと `.pct-input`(値x100 表示・% サフィックス)
 * になる。素の `window.numberInput` を使うと、ラベルには「〜（%）」と出るのに中身は 0.1 のまま
 * になり、しかも `statUnitSlot` は PERCENT ステに空スロットを返すので**単位すら付かない**。
 * lore.yml の宣言を突き合わせるだけの監査では絶対に見つからない類のバグなので、
 * 実際に描画された DOM を見る。
 *
 * 前提: config-editor が起動していること (`cd tools/config-editor && npm start`)。
 *
 * 使い方:
 *   node ops/scripts/editor-percent-audit.mjs [http://localhost:8787]
 * 終了コード: 疑わしい欄が 1 件でもあれば 2。
 */
import { fileURLToPath } from "node:url";
import path from "node:path";
import { launchChrome, waitForEditorReady, gotoNav, expandCards, sleep } from "./lib/cdp.mjs";

const BASE_URL = process.argv[2] || "http://localhost:8787";
const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const PROFILE_DIR = path.join(REPO_ROOT, "tmp", "chrome-profile-pct");
const PORT = 9336;

/**
 * ページ内監査。数値入力ごとに「% の欄か」「%変換を通しているか」を突き合わせる。
 * 判定材料はラベル文字列(statSelect の選択肢は「名前（%）」形式)。
 */
const AUDIT_FN = `
(() => {
  const main = document.querySelector('.main');
  if (!main) return { error: 'no .main' };

  // **ステ選択と同じ行だけを見る。** 祖先を無条件にさかのぼると、隣接する CMD 番号欄や
  // 必要レベル欄まで「%ステの欄」に見えてしまう(実際に 300004 や 100 を拾って誤検出した)。
  // 行 = そのステ選択を含み、かつ数値入力を持つ最小の祖先。
  const rowOf = (sel) => {
    let node = sel.parentElement;
    for (let i = 0; i < 4 && node && node !== main; i++) {
      if (node.querySelector('input[type=number]')) return node;
      node = node.parentElement;
    }
    return null;
  };

  const rows = [];
  main.querySelectorAll('.stat-select').forEach((sel) => {
    // **innerText を使わないこと。** 折りたたみカードの中の行は非表示なので innerText が
    // 空文字になり、行ごと検査から落ちる(実際に 418 欄ある画面で 18 欄しか見ていなかった)。
    const label = (sel.textContent || '').trim().split('\\n')[0];
    const looksPercent = /[（(]\\s*%\\s*[）)]/.test(label);
    if (!looksPercent) return;
    const row = rowOf(sel);
    if (!row) return;
    // 同じ行の数値入力のうち、ステ選択より後ろに並ぶものが値入力(倍率欄は mult-prefix を持つ)。
    row.querySelectorAll('input[type=number]').forEach((input) => {
      if (input.closest('.mult-input') || input.parentElement?.querySelector('.mult-prefix')) return;
      if (sel.compareDocumentPosition(input) & Node.DOCUMENT_POSITION_PRECEDING) return;
      rows.push({
        label,
        via: 'statSelect',
        inPct: !!input.closest('.pct-input'),
        value: input.value,
        rowClass: String(row.className || '').slice(0, 40)
      });
    });
  });
  // 空振り検知用。走査母数が 0 のまま「問題なし」と報告するのが一番危ない。
  return {
    rows,
    scanned: {
      inputs: main.querySelectorAll('input[type=number]').length,
      statSelects: main.querySelectorAll('.stat-select').length
    }
  };
})()
`;

/**
 * 第2パス: ステ選択を持たない「固定キーのフォーム」を拾う。
 * ページが持つ `window.STAT_FORMATS` / `LABELS.statLabel` から PERCENT ステの
 * 日本語表示名と英字キーの一覧を作り、ラベルがそれに一致する数値欄を探す。
 * (mob-types の physical/magical/attack ブロックのように、ラベルが素のキー名の画面がある)
 */
const AUDIT_FIXED_FN = `
(() => {
  const main = document.querySelector('.main');
  if (!main) return { error: 'no .main' };
  const fmt = window.STAT_FORMATS || {};
  const fallback = window.FALLBACK_STAT_FORMATS || {};
  const label = (window.LABELS && window.LABELS.statLabel) ? window.LABELS.statLabel : (k) => k;
  const names = new Map();               // 表示名/キー -> 正規キー
  for (const src of [fmt, fallback]) {
    for (const [k, v] of Object.entries(src)) {
      if (String(v || '').toUpperCase() !== 'PERCENT') continue;
      names.set(k, k);
      const ja = label(k);
      if (ja && ja !== k) names.set(ja, k);
      // モブ画面は physical/magical ブロック内で接頭辞を落とした短縮キーを使う。
      const short = k.replace(/^(phys|magic|armor)-/, '');
      if (short !== k) names.set(short, k);
    }
  }
  const hits = [];
  main.querySelectorAll('input[type=number]').forEach((input) => {
    if (input.closest('.pct-input')) return;
    if (input.closest('.stat-row')) return;         // 第1パスの担当
    let node = input.parentElement, text = '';
    for (let i = 0; i < 3 && node && node !== main; i++) {
      const lab = node.querySelector('label, .field-label, .row-label');
      if (lab) { text = (lab.textContent || '').trim(); break; }
      node = node.parentElement;
    }
    if (!text) return;
    const key = text.replace(/\\s*[（(].*$/, '').trim();
    const canon = names.get(key) || names.get(text);
    if (!canon) return;
    hits.push({ label: text, key: canon, value: input.value });
  });
  return { hits };
})()
`;

async function main() {
  const { cdp, close } = await launchChrome({ port: PORT, profileDir: PROFILE_DIR });
  const bad = [];
  const ok = [];
  const fixed = [];
  let totalInputs = 0;
  let totalSelects = 0;
  try {
    await cdp.setViewport(1440, 1000);
    if (!await waitForEditorReady(cdp, BASE_URL)) {
      console.error("editor の描画待ちタイムアウト (npm start しているか確認)");
      process.exit(1);
    }
    const navCount = await cdp.eval("document.querySelectorAll('#config-list .nav-item').length");
    console.log(`ナビ ${navCount} 画面を走査する`);

    for (let idx = 0; idx < navCount; idx++) {
      await gotoNav(cdp, idx);
      await sleep(380);
      await expandCards(cdp, 6);       // 折りたたみカードの中の欄も描画させる
      await sleep(260);
      const title = await cdp.eval("document.getElementById('editor-title')?.textContent || ''");
      // タブを持つ画面は全タブを回す(item-stats の武器/防具/スレッド等)。
      const tabCount = await cdp.eval("document.querySelectorAll('.main .tab-bar .tab, .main .tabs .tab').length");
      const tabs = Math.max(1, Number(tabCount) || 1);
      for (let t = 0; t < tabs; t++) {
        if (tabs > 1) {
          await cdp.eval(`document.querySelectorAll('.main .tab-bar .tab, .main .tabs .tab')[${t}]?.click(); true`);
          await sleep(320);
          await expandCards(cdp, 6);
          await sleep(220);
        }
        const res = await cdp.eval(AUDIT_FN);
        if (res && !res.error) {
          totalInputs += res.scanned.inputs;
          totalSelects += res.scanned.statSelects;
          for (const row of res.rows) {
            const rec = { page: `${idx}: ${title}`, tab: tabs > 1 ? t : null, ...row };
            if (!row.inPct) bad.push(rec);
            else ok.push(rec);
          }
        }
        const res2 = await cdp.eval(AUDIT_FIXED_FN);
        if (res2 && !res2.error) {
          for (const hit of res2.hits) {
            fixed.push({ page: `${idx}: ${title}`, tab: tabs > 1 ? t : null, ...hit });
          }
        }
      }
    }
  } finally {
    await close();
  }

  const seen = new Set();
  const uniq = bad.filter((b) => {
    const k = `${b.page}|${b.label}`;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });

  console.log(`\n走査母数: 数値入力 延べ ${totalInputs} 欄 / ステ選択 延べ ${totalSelects} 個`);
  if (totalSelects < 100) {
    console.error("!! 走査母数が少なすぎる。描画待ちが足りていない可能性が高い(この結果は信用しないこと)");
  }
  console.log(`%変換を通している欄: ${ok.length} 件`);
  console.log(`■ %ラベルなのに素の数値入力: ${uniq.length} 種 (延べ ${bad.length} 欄)`);
  for (const b of uniq) {
    console.log(`  [${b.page}${b.tab != null ? ` / tab${b.tab}` : ""}] ${b.label}  = ${b.value}  (${b.via})`);
  }

  const seen2 = new Set();
  const uniqFixed = fixed.filter((f) => {
    const k = `${f.page}|${f.label}`;
    if (seen2.has(k)) return false;
    seen2.add(k);
    return true;
  });
  console.log(`\n■ 固定キーのフォームで %変換を通していない欄: ${uniqFixed.length} 種 (延べ ${fixed.length} 欄)`);
  for (const f of uniqFixed) {
    console.log(`  [${f.page}${f.tab != null ? ` / tab${f.tab}` : ""}] ${f.label} -> ${f.key} = ${f.value}`);
  }
  process.exit(uniq.length ? 2 : 0);
}

main().catch((e) => { console.error(e); process.exit(1); });
