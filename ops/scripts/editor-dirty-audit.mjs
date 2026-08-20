/**
 * 「編集したのに未保存警告が出ない（＝保存されない）」画面を全画面で洗う。
 *
 * 判定: isEditorDirty は非公開だが beforeunload ハンドラが使っているので、
 * cancelable な beforeunload を撃って defaultPrevented を見れば外から判定できる。
 *
 * 各画面ごとに毎回リロードして測る（dirty は一度立つと戻らない実装なので、
 * 前の画面の汚れを持ち込むと後続が全部 dirty=true に見えて検査にならない）。
 *
 * **保存は一切しない。** DOM 上の値を書き換えて判定したらリロードで捨てる。
 *
 * 使い方: node ops/scripts/editor-dirty-audit.mjs [http://localhost:8787] [検査する画面index,カンマ区切り]
 */
import { fileURLToPath } from "node:url";
import path from "node:path";
import { launchChrome, waitForEditorReady, sleep } from "./lib/cdp.mjs";

const BASE_URL = process.argv[2] || "http://localhost:8787";
// 第2引数に "50,51" のように渡すとその画面だけを検査する(全画面は 500 回以上リロードするので遅い)。
const ONLY = process.argv[3]
  ? new Set(process.argv[3].split(",").map((s) => Number(s.trim())).filter((n) => Number.isFinite(n)))
  : null;
// TABS=1 で画面内タブ(.recipe-tab)を1枚ずつ回す。PORT を変えれば1本目と並行して走らせられる。
const TABS = process.env.TABS === "1";
const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const PROFILE_DIR = path.join(REPO_ROOT, "tmp", "chrome-profile-dirty" + (TABS ? "-tabs" : ""));
const PORT = Number(process.env.PORT || (TABS ? 9336 : 9335));

const HELPERS = `
// alert/confirm はレンダラを止める。CDP 側でも閉じるが、こちらで潰しておけば
// 「重複キーで弾かれた」ことを戻り値として観測できる。
window.__alerts = [];
window.alert = (m) => { window.__alerts.push('alert:' + String(m)); };
// confirm は **必ず false**。cmdEnsureCatalogItemCmd のように confirm の先で
// /api/cmd/allocate + catalog.yml の PUT を走らせる経路があるので、true を返すと
// 「検査しただけ」のはずが実ファイルとCMD台帳を書き換えてしまう。
window.confirm = (m) => { window.__alerts.push('confirm:' + String(m)); return false; };
// prompt はキー名を聞くだけ(「+ カテゴリ」等)なので値を返して操作を先へ通す。
// null を返すと操作自体が起きず、dirty=false が正しくなって検査にならない。
window.prompt = (m) => { window.__alerts.push('prompt:' + String(m)); return 'ZZTEST'; };
// 検査を中断させるべき応答(却下 alert / 拒否した confirm)だけを拾う。
window.__blocked = () => window.__alerts.filter((a) => !a.startsWith('prompt:'));
window.__dirty = () => {
  const ev = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(ev);
  return ev.defaultPrevented;
};
window.__visible = (el) => el && !el.disabled && el.offsetParent !== null;
// 検索欄・絞り込み欄は「編集しても未保存にならないのが正しい」ので検査対象から外す。
// (これを外さないと全カタログ画面が text=NG に見える偽陽性になる)
window.__isDataField = (el) => {
  if (!el) return false;
  const cls = String(el.className || '');
  if (/search|filter|list-select-custom/i.test(cls)) return false;
  if (el.type === 'search') return false;
  const ph = String(el.placeholder || '');
  if (/検索|絞り込/.test(ph)) return false;
  // 「表示ステータス (攻撃/守備/…)」のような**表示フィルタ**は yml のデータではないので
  // 切り替えても未保存にならないのが正しい。見出しで判別して検査対象から外す。
  const sub = el.closest('.sub-section');
  if (sub) {
    const t = (sub.querySelector('.sub-title')?.textContent || '');
    if (/表示|フィルタ|絞り込|並び/.test(t)) return false;
  }
  // 実データを持つ行の中にあるものだけ
  return !!el.closest('.entry-card, .form-field, .stat-rows, .sub-section, .const-body, .field-grid');
};
// カード/セクションを開いて、隠れているフォーム部品を検査対象に出す
window.__openAll = (n) => {
  document.querySelectorAll('.main .entry-card.is-collapsed .entry-head').forEach((h, j) => { if (j < n) h.click(); });
};
// カテゴリタブ(あれば)の一覧
window.__catTabs = () => Array.from(document.querySelectorAll('.main button.editor-cat-tab, .main button.recipe-tab'))
  .map((b) => (b.textContent || '').trim());
`;

/** 1 種類の変更を試して dirty になるか見る。戻り値は {kind, ok, detail}。 */
const PROBE = (kind) => `
(async () => {
  const sleep = (ms) => new Promise(r => setTimeout(r, ms));
  const kind = ${JSON.stringify(kind)};
  const inputs = Array.from(document.querySelectorAll('.main input, .main textarea'))
    .filter((el) => window.__visible(el) && window.__isDataField(el));
  const before = window.__dirty();

  // 「+ 〜追加」ボタン: 行/エントリを増やす操作。値の編集と別経路なので個別に見る。
  if (kind === 'add-button') {
    const btn = Array.from(document.querySelectorAll('.main button'))
      .filter((b) => window.__visible(b) && /^\\+|追加$/.test((b.textContent || '').trim()))[0];
    if (!btn) return { kind, skipped: 'no add button' };
    const label = (btn.textContent || '').trim().slice(0, 20);
    btn.click();
    await sleep(500);
    // prompt/confirm を伴うボタン(「+ カテゴリ」など)はスタブが null/false を返すので
    // 「何も起きなかった」のが正しい。NG と数えると偽陽性になる。
    if (window.__blocked().length) return { kind, skipped: label + ' / ' + window.__blocked()[0].slice(0, 30) };
    return { kind, before, after: window.__dirty(), detail: label };
  }

  // 「×」削除ボタン: 行を消す操作。
  if (kind === 'del-button') {
    const btn = Array.from(document.querySelectorAll('.main button.danger'))
      .filter((b) => window.__visible(b))[0];
    if (!btn) return { kind, skipped: 'no delete button' };
    const label = (btn.textContent || '').trim().slice(0, 20);
    btn.click();
    await sleep(500);
    // prompt/confirm を伴うボタン(「+ カテゴリ」など)はスタブが null/false を返すので
    // 「何も起きなかった」のが正しい。NG と数えると偽陽性になる。
    if (window.__blocked().length) return { kind, skipped: label + ' / ' + window.__blocked()[0].slice(0, 30) };
    return { kind, before, after: window.__dirty(), detail: label };
  }

  if (kind === 'list-select') {
    const trig = Array.from(document.querySelectorAll('.main .list-select .list-select-trigger'))
      .filter((el) => window.__visible(el) && window.__isDataField(el))[0];
    if (!trig) return { kind, skipped: 'no list-select' };
    trig.click();
    await sleep(180);
    const list = document.querySelector('.material-suggest-list');
    if (!list) return { kind, skipped: 'list did not open' };
    const opts = Array.from(list.querySelectorAll('li'))
      .filter((li) => !li.classList.contains('list-select-filter-row') && (li.textContent || '').trim());
    // 現在値と違う選択肢を選ぶ。
    // data-value が空の項目(「(未選択)」)と自由入力(__custom__)は「値を変えない」ので除外する
    // (これを選ぶと dirty=false が正しくなり、偽陽性の NG になる)。
    const cur = (trig.textContent || '').trim();
    const pick = opts.find((li) => {
      const v = li.getAttribute('data-value');
      // __custom__ / __material_free__ など「__…__」は自由入力欄を開く番兵で、
      // 選んでも値が変わらない。選ぶと dirty=false が正しくなり偽陽性の NG になる。
      if (!v || /^__.*__$/.test(v)) return false;
      return (li.textContent || '').trim() && !(li.textContent || '').includes(cur);
    });
    if (!pick) return { kind, skipped: 'no alternative option' };
    const label = (pick.textContent || '').trim().slice(0, 24);
    pick.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    pick.click();
    await sleep(500);
    // 重複キー等で確定が拒否された場合は「変更されていない」ので dirty=false は正しい。
    // NG と数えると偽陽性になるため skipped に落とす。
    if (window.__blocked().length) return { kind, skipped: 'rejected: ' + window.__blocked()[0].slice(0, 40) };
    return { kind, before, after: window.__dirty(), detail: cur + ' -> ' + label };
  }

  let el = null;
  if (kind === 'text') el = inputs.find((i) => (i.type || 'text') === 'text' && i.tagName === 'INPUT');
  else if (kind === 'number') el = inputs.find((i) => i.type === 'number');
  else if (kind === 'checkbox') el = inputs.find((i) => i.type === 'checkbox');
  else if (kind === 'textarea') el = inputs.find((i) => i.tagName === 'TEXTAREA');
  if (!el) return { kind, skipped: 'no ' + kind };

  const prev = kind === 'checkbox' ? el.checked : el.value;
  if (kind === 'checkbox') el.checked = !prev;
  else if (kind === 'number') el.value = String(Number(prev || 0) + 7);
  else el.value = String(prev) + 'ZZTEST';
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  await sleep(450);
  if (window.__blocked().length) return { kind, skipped: 'rejected: ' + window.__blocked()[0].slice(0, 40) };
  return {
    kind, before, after: window.__dirty(),
    detail: (el.className || '(no class)').split(' ').slice(0, 2).join('.') + ' = ' + String(prev).slice(0, 16)
  };
})()
`;

async function main() {
  const { cdp, close } = await launchChrome({ port: PORT, profileDir: PROFILE_DIR });
  const rows = [];
  try {
    await cdp.setViewport(1440, 900);
    if (!await waitForEditorReady(cdp, BASE_URL)) throw new Error("editor の描画待ちタイムアウト");
    await cdp.eval(HELPERS);
    const navs = await cdp.eval(
      "Array.from(document.querySelectorAll('#config-list .nav-item')).map(n => (n.textContent||'').trim())"
    );
    console.log("画面数: " + navs.length);

    // 画面内タブ(.recipe-tab)は既定タブしか検査されないので、TABS=1 では
    // 2枚目以降のタブも回す(「ガチャ券タブを編集したら〜」のようなタブ単位の報告に対応する)。
    async function openScreen(i, tabIndex) {
      if (!await waitForEditorReady(cdp, BASE_URL)) return false;
      await cdp.eval(HELPERS);
      await cdp.eval(`(() => { const n = document.querySelectorAll('#config-list .nav-item'); if (n[${i}]) n[${i}].click(); })()`);
      await sleep(900);
      if (tabIndex != null) {
        await cdp.eval(`(() => { const t = document.querySelectorAll('.main .recipe-tab'); if (t[${tabIndex}]) t[${tabIndex}].click(); })()`);
        await sleep(700);
      }
      await cdp.eval("window.__openAll(3)");
      await sleep(400);
      return true;
    }

    for (let i = 0; i < navs.length; i++) {
      if (ONLY && !ONLY.has(i)) continue;
      const kinds = ["text", "number", "checkbox", "textarea", "list-select", "add-button", "del-button"];
      // tabIndex=null は既定タブ(=タブが無い画面もこれ1回で済む)。
      let tabJobs = [null];
      let tabLabels = [];
      if (TABS) {
        if (await openScreen(i, null)) {
          tabLabels = await cdp.eval("Array.from(document.querySelectorAll('.main .recipe-tab')).map(b => (b.textContent||'').trim())");
          if (Array.isArray(tabLabels) && tabLabels.length > 1) {
            tabJobs = tabLabels.map((_, t) => t);
          }
        }
      }
      for (const tab of tabJobs) {
        const probes = [];
        for (const kind of kinds) {
          // 種類ごとに毎回リロード（dirty は戻らないので使い回せない）
          if (!await openScreen(i, tab)) { probes.push({ kind, skipped: "reload timeout" }); continue; }
          try {
            probes.push(await cdp.eval(PROBE(kind)));
          } catch (err) {
            probes.push({ kind, error: String(err.message).slice(0, 120) });
          }
        }
        const title = await cdp.eval("document.getElementById('editor-title')?.textContent || ''");
        const tabName = tab == null ? "" : ` [タブ: ${tabLabels[tab] || tab}]`;
        const bad = probes.filter((p) => p && !p.skipped && !p.error && p.before === false && p.after === false);
        rows.push({ i, nav: navs[i] + tabName, title, probes, bad: bad.map((b) => b.kind) });
        const mark = bad.length ? "  <<< 警告が出ない: " + bad.map((b) => b.kind).join(",") : "";
        console.log(`[${i}] ${navs[i]}${tabName} / ${title} ` + probes.map((p) => p.skipped ? p.kind + "=-" : (p.error ? p.kind + "=E" : p.kind + "=" + (p.after ? "OK" : "NG"))).join(" ") + mark);
      }
    }
  } finally {
    await close();
  }

  console.log("\n=== 編集しても未保存警告が出ない画面 ===");
  const bad = rows.filter((r) => r.bad.length);
  if (!bad.length) console.log("なし");
  for (const r of bad) {
    console.log(`[${r.i}] ${r.nav} / ${r.title} -> ${r.bad.join(", ")}`);
    for (const p of r.probes.filter((p) => r.bad.includes(p.kind))) console.log("      " + p.kind + ": " + p.detail);
  }
}

main().catch((err) => { console.error("FAILED: " + err.message); process.exit(1); });
