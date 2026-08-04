/**
 * config-editor の横あふれ監査 (依存ゼロ / ヘッドレス Chrome + CDP)。
 *
 * **要素の矩形だけを見る検査では取りこぼす。** ブロックの border box は親に収まっているのに
 * 中の 1 トークンが長すぎてインライン内容だけがあふれるケースがあり(respack のビルド結果に
 * 出る Windows パス + SHA-1 で実際に踏んだ: 321px の枠に 653px のテキスト)、
 * 矩形検査だけだと「あふれ 0 件」と報告してしまう。**scrollWidth vs clientWidth も見る。**
 *
 * 前提: config-editor を先に起動しておく (`cd tools/config-editor && npm start`)。
 *
 * 使い方:
 *   node ops/scripts/editor-overflow-audit.mjs [http://localhost:8787]
 * 終了コード: あふれが 1 件でもあれば 2 (CI やフックから使えるように)
 */
import { fileURLToPath } from "node:url";
import path from "node:path";
import { launchChrome, waitForEditorReady, gotoNav, expandCards, sleep } from "./lib/cdp.mjs";

const BASE_URL = process.argv[2] || "http://localhost:8787";
const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const PROFILE_DIR = path.join(REPO_ROOT, "tmp", "chrome-profile-audit");
const PORT = 9334;

const WIDTHS = [375, 414, 600, 768, 900, 1024, 1200, 1440];
// ナビ index: 密なフォーム・表・2カラムを含む画面を選ぶ(全画面を回すと数十分かかる)
const PAGES = [0, 2, 6, 20, 21, 22, 25, 26, 33, 49, 51, 63, 65, 75];

/** ページ内で走らせる監査本体。返り値は問題の配列。 */
const AUDIT_FN = `
(() => {
  const main = document.querySelector('.main');
  if (!main) return { error: 'no .main' };
  const limit = main.getBoundingClientRect().right;
  const isScroller = (el) => { const ox = getComputedStyle(el).overflowX; return ox === 'auto' || ox === 'scroll'; };
  const hasScrollAncestor = (el) => { let a = el.parentElement; while (a && a !== main) { if (isScroller(a)) return true; a = a.parentElement; } return false; };
  const problems = [];
  main.querySelectorAll('*').forEach((el) => {
    const r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) return;
    if (hasScrollAncestor(el)) return;            // 横スクロールで到達できる = あふれではない
    const label = el.tagName.toLowerCase() + (el.className ? '.' + String(el.className).split(' ').slice(0,2).join('.') : '');
    // (a) 要素の矩形そのものが本文の右端を越える
    if (r.right > limit + 1) problems.push({ kind: 'box', el: label, over: Math.round(r.right - limit) });
    // (b) 矩形は収まっているが中身(長い1トークン等)があふれている。
    //     ただし次の2つは「あふれて正しい」ので除外しないと偽陽性が大量に出る:
    //     - input/textarea/select/button は欄内スクロールが正常仕様
    //     - text-overflow:ellipsis は意図的な省略表示
    else if (!isScroller(el) && el.scrollWidth > el.clientWidth + 1 && el.clientWidth > 0) {
      const cs = getComputedStyle(el);
      const isControl = /^(input|textarea|select|button)$/.test(el.tagName.toLowerCase());
      const isEllipsis = cs.textOverflow === 'ellipsis';
      if (!isControl && !isEllipsis) {
        problems.push({ kind: 'text', el: label, over: el.scrollWidth - el.clientWidth, sample: (el.textContent || '').trim().slice(0, 40) });
      }
    }
  });
  return { mainW: main.clientWidth, mainOver: main.scrollWidth - main.clientWidth, problems };
})()
`;

async function main() {
  const { cdp, close } = await launchChrome({ port: PORT, profileDir: PROFILE_DIR });
  const findings = [];
  let checks = 0;
  try {
    for (const w of WIDTHS) {
      await cdp.setViewport(w, 900);
      if (!await waitForEditorReady(cdp, BASE_URL)) {
        findings.push({ width: w, page: "-", error: "描画待ちタイムアウト (editor は起動している?)" });
        continue;
      }
      for (const idx of PAGES) {
        await gotoNav(cdp, idx);
        await sleep(420);
        await expandCards(cdp, 4);
        await sleep(250);
        await cdp.flushAnimations();
        const title = await cdp.eval("document.getElementById('editor-title')?.textContent || ''");
        const res = await cdp.eval(AUDIT_FN);
        checks++;
        if (!res.problems || !res.problems.length) continue;
        // 同じ el/kind でまとめ、あふれ量が最大のものを代表にする
        const byKey = new Map();
        for (const p of res.problems) {
          const k = p.kind + " " + p.el;
          if (!byKey.has(k) || byKey.get(k).over < p.over) byKey.set(k, p);
        }
        findings.push({
          width: w, page: title, count: res.problems.length, mainOver: res.mainOver,
          worst: [...byKey.values()].sort((a, b) => b.over - a.over).slice(0, 5)
        });
      }
    }
  } finally {
    await close();
  }

  console.log("検査した (幅 x 画面) の組み合わせ: " + checks);
  if (!findings.length) {
    console.log("あふれ 0 件 (box / text の両方)");
    return;
  }
  console.log(JSON.stringify(findings, null, 2));
  process.exitCode = 2;
}

main().catch((err) => { console.error("FAILED: " + err.message); process.exit(1); });
