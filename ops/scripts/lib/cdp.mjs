/**
 * ヘッドレス Chrome を CDP (Chrome DevTools Protocol) で直接叩く最小クライアント。**依存ゼロ**。
 *
 * なぜ自作なのか: Claude Code の Browser ペインは**非表示だとフレームを合成しない**ため、
 * スクリーンショットが撮れないうえに CSS transition が進まず、`resize` / `matchMedia` の
 * change イベントも 1 度も発火しない。ペインでの計測は「効いていないように見える」誤診を招く。
 * こちらはペインの表示状態に依存しないので、スクショも遷移後の状態も確実に取れる。
 * puppeteer 等を入れないのは、この用途のためだけに editor の依存を増やしたくないため
 * (Node 24 の global WebSocket / fetch だけで足りる)。
 *
 * 使う側は launchChrome() → new Cdp(ws) → cdp.eval(...) の順。後片付けは close() に任せる。
 */
import { spawn } from "node:child_process";
import { existsSync, rmSync } from "node:fs";

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const CHROME_CANDIDATES = [
  process.env.CHROME,
  "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
  "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
  process.env.LOCALAPPDATA ? `${process.env.LOCALAPPDATA}\\Google\\Chrome\\Application\\chrome.exe` : null
].filter(Boolean);

export function findChrome() {
  const hit = CHROME_CANDIDATES.find((p) => existsSync(p));
  if (!hit) {
    throw new Error(
      "chrome.exe が見つからない。環境変数 CHROME に実行ファイルのフルパスを渡して再実行する:\n"
      + "  set CHROME=C:\\path\\to\\chrome.exe"
    );
  }
  return hit;
}

/** CDP のリクエスト/レスポンス対応と Runtime.evaluate のラッパ。 */
export class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    this.dialogs = [];
    ws.addEventListener("message", (ev) => {
      const msg = JSON.parse(ev.data);
      // alert/confirm はレンダラを止めるので、開いた瞬間に閉じないと以降の
      // Runtime.evaluate が全部 30 秒タイムアウトする(= 検査が途中で死ぬ)。
      // 破壊的な確認を誤って通さないよう accept: false (キャンセル相当) で閉じる。
      if (msg.method === "Page.javascriptDialogOpening") {
        this.dialogs.push({ type: msg.params.type, message: msg.params.message });
        this.send("Page.handleJavaScriptDialog", { accept: false }).catch(() => {});
        return;
      }
      if (!msg.id || !this.pending.has(msg.id)) return;
      const { resolve, reject } = this.pending.get(msg.id);
      this.pending.delete(msg.id);
      if (msg.error) reject(new Error(msg.method + ": " + JSON.stringify(msg.error)));
      else resolve(msg.result);
    });
  }

  /** 直近で閉じたダイアログを取り出す(検査対象が alert を出したかの判定用)。 */
  takeDialogs() { const d = this.dialogs; this.dialogs = []; return d; }

  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error("CDP timeout: " + method));
        }
      }, 30000);
    });
  }

  async eval(expression) {
    const r = await this.send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) {
      throw new Error("eval failed: " + JSON.stringify(r.exceptionDetails.text || r.exceptionDetails));
    }
    return r.result.value;
  }

  /** 幅を変えて開き直す。ペインと違い、ここでは実際に再レイアウトが起きる。 */
  async setViewport(width, height) {
    await this.send("Emulation.setDeviceMetricsOverride", {
      width, height, deviceScaleFactor: 1, mobile: false
    });
  }

  /**
   * 遷移とアニメーションを確定させる。**計測前に必ず呼ぶ。**
   * transition 途中の computed 値を読んで「CSS が効いていない」と誤診した事故があるため。
   */
  async flushAnimations() {
    await this.eval("document.querySelectorAll('*').forEach((el) => el.getAnimations().forEach((a) => a.finish()))");
  }
}

/**
 * ヘッドレス Chrome を起動して CDP を張る。
 * @param {object} o
 * @param {number} o.port           リモートデバッグポート (スクリプトごとに別番号にする)
 * @param {string} o.profileDir     使い捨てプロファイル。前回の残骸ごと作り直す
 * @param {string[]} [o.extraArgs]  追加の chrome 引数
 * @returns {Promise<{cdp: Cdp, close: () => Promise<void>}>}
 */
export async function launchChrome({ port, profileDir, extraArgs = [] }) {
  rmSync(profileDir, { recursive: true, force: true });
  const chrome = spawn(findChrome(), [
    "--headless=new",
    "--remote-debugging-port=" + port,
    "--user-data-dir=" + profileDir,
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-gpu",
    "--hide-scrollbars",
    "--force-device-scale-factor=1",
    ...extraArgs,
    "about:blank"
  ], { stdio: "ignore" });

  let wsUrl = null;
  for (let i = 0; i < 60; i++) {
    await sleep(250);
    try {
      const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
      const page = list.find((t) => t.type === "page");
      if (page && page.webSocketDebuggerUrl) { wsUrl = page.webSocketDebuggerUrl; break; }
    } catch { /* 起動待ち */ }
  }
  if (!wsUrl) {
    chrome.kill();
    throw new Error("Chrome の DevTools に接続できなかった (ポート " + port + ")");
  }

  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => {
    ws.addEventListener("open", res, { once: true });
    ws.addEventListener("error", rej, { once: true });
  });

  const cdp = new Cdp(ws);
  await cdp.send("Page.enable");
  await cdp.send("Runtime.enable");

  return {
    cdp,
    close: async () => {
      ws.close();
      chrome.kill();
      await sleep(400);
      rmSync(profileDir, { recursive: true, force: true });
    }
  };
}

/**
 * config-editor の描画完了(ナビ生成)を待つ。設定を非同期に取ってから描画するので、
 * navigate 直後に計測すると必ず空を読む。
 */
export async function waitForEditorReady(cdp, url, { tries = 60, intervalMs = 200 } = {}) {
  await cdp.send("Page.navigate", { url });
  for (let i = 0; i < tries; i++) {
    await sleep(intervalMs);
    try {
      if (await cdp.eval("document.querySelectorAll('#config-list .nav-item').length > 0")) return true;
    } catch { /* 遷移中は evaluate 自体が失敗する */ }
  }
  return false;
}

/** ナビ index をクリックして画面を切り替える。 */
export async function gotoNav(cdp, index) {
  await cdp.eval(`(() => { const n = document.querySelectorAll('#config-list .nav-item'); if (n[${index}]) n[${index}].click(); })()`);
}

/** 折りたたまれたカードを先頭から n 件だけ開く(フォーム中身を検査対象に入れるため)。 */
export async function expandCards(cdp, n) {
  await cdp.eval(`document.querySelectorAll('.main .entry-card.is-collapsed .entry-head').forEach((h, j) => { if (j < ${n}) h.click(); })`);
}
