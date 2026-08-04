/**
 * config-editor のスクリーンショットを撮る (依存ゼロ / ヘッドレス Chrome + CDP)。
 *
 * Claude Code の Browser ペインは非表示だとフレームを合成しないためスクショが撮れない。
 * こちらはペインの表示状態に依存しない。詳細と注意点は ./lib/cdp.mjs の冒頭コメント。
 *
 * 前提: config-editor を先に起動しておく (`cd tools/config-editor && npm start`)。
 *
 * 使い方:
 *   node ops/scripts/editor-screenshot.mjs [http://localhost:8787]
 * 出力先: tmp/shots/*.png (tmp/ は .gitignore 済み = コミットされない)
 */
import { mkdirSync, writeFileSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { launchChrome, waitForEditorReady, gotoNav, expandCards, sleep } from "./lib/cdp.mjs";

const BASE_URL = process.argv[2] || "http://localhost:8787";
const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const OUT_DIR = path.join(REPO_ROOT, "tmp", "shots");
const PROFILE_DIR = path.join(REPO_ROOT, "tmp", "chrome-profile-shot");
const PORT = 9333;

/** 撮る対象。prepare は「そのビューポートで撮る直前に行う準備」。 */
const SHOTS = [
  { name: "1440-home", w: 1440, h: 900, note: "デスクトップ / ホーム" },
  { name: "1440-weapons", w: 1440, h: 900, note: "デスクトップ / 武器タブ カード展開",
    prepare: async (cdp) => { await gotoNav(cdp, 2); await sleep(500); await expandCards(cdp, 3); } },
  { name: "1024-weapons", w: 1024, h: 768, note: "タブレット横 / サイドバー216px",
    prepare: async (cdp) => { await gotoNav(cdp, 2); await sleep(500); await expandCards(cdp, 3); } },
  { name: "768-weapons", w: 768, h: 1024, note: "タブレット縦 / ドロワー(閉)",
    prepare: async (cdp) => { await gotoNav(cdp, 2); await sleep(500); await expandCards(cdp, 3); } },
  { name: "375-weapons", w: 375, h: 812, note: "スマホ / ドロワー(閉) / ラベルと入力が縦積み",
    prepare: async (cdp) => { await gotoNav(cdp, 2); await sleep(500); await expandCards(cdp, 3); } },
  { name: "375-drawer-open", w: 375, h: 812, note: "スマホ / ハンバーガーでドロワーを開いた状態",
    prepare: async (cdp) => { await gotoNav(cdp, 2); await sleep(500); await cdp.eval("document.getElementById('sidebar-toggle-btn').click()"); } },
  { name: "375-respack", w: 375, h: 812, note: "スマホ / CMD台帳(8列)が .table-scroll で横スクロールできる",
    prepare: async (cdp) => { await gotoNav(cdp, 22); } },
  { name: "375-home", w: 375, h: 812, note: "スマホ / ホーム" }
];

/** サイドバーが「ドッキング / ドロワー(開閉)」のどれかと、本文の見切れ量を測る。 */
const MEASURE_FN = `(() => {
  const m = document.querySelector('.main');
  const sb = document.getElementById('app-sidebar');
  const cs = getComputedStyle(sb);
  return {
    mainW: m.clientWidth,
    clipPx: m.scrollWidth - m.clientWidth,
    sidebar: cs.position === 'absolute'
      ? (cs.visibility === 'hidden' ? 'drawer(closed)' : 'drawer(OPEN)')
      : 'docked ' + Math.round(sb.getBoundingClientRect().width) + 'px',
    title: document.getElementById('editor-title')?.textContent
  };
})()`;

async function main() {
  rmSync(OUT_DIR, { recursive: true, force: true });
  mkdirSync(OUT_DIR, { recursive: true });

  const { cdp, close } = await launchChrome({ port: PORT, profileDir: PROFILE_DIR });
  const results = [];
  try {
    for (const shot of SHOTS) {
      await cdp.setViewport(shot.w, shot.h);
      if (!await waitForEditorReady(cdp, BASE_URL)) {
        results.push({ name: shot.name, ok: false, why: "描画待ちタイムアウト (editor は起動している?)" });
        continue;
      }
      if (shot.prepare) { await shot.prepare(cdp); await sleep(700); }
      await cdp.flushAnimations();
      await sleep(250);

      const { data } = await cdp.send("Page.captureScreenshot", { format: "png" });
      writeFileSync(path.join(OUT_DIR, shot.name + ".png"), Buffer.from(data, "base64"));
      results.push({ name: shot.name, ok: true, size: shot.w + "x" + shot.h, ...await cdp.eval(MEASURE_FN), note: shot.note });
    }
  } finally {
    await close();
  }

  console.log(JSON.stringify(results, null, 2));
  console.log("\n出力: " + OUT_DIR);
}

main().catch((err) => { console.error("FAILED: " + err.message); process.exit(1); });
