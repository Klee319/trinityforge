"use strict";

// レシピ画面が組み立てる `custom:<id>` 候補（recipes.js の ensureCustomDatalist が使う）。
//
// 【なぜ items.yml を読まないのか — 2026-08-21 ユーザー報告】
//   「エディターでアイテムをセレクトメニューから選択する時に、存在しないアイテム
//    custom:エンチャントされた金リンゴ が表示される。こんなアイテムカタログにない」
//
//   ArsPaper の items.yml は<b>レシピ定義だけ</b>のファイルで、カスタムアイテムを1件も
//   登録しない（登録するのは materials.yml / functional-items.yml / threads.yml /
//   spellbooks.yml と TF の catalog.yml）。items: の各キーは「そのレシピの名前」であって
//   アイテムIDではなく、実際に出荷 items.yml に残る 2 件（trident /
//   エンチャントされた金リンゴ）はどちらも<b>結果がバニラアイテム</b>のレシピ。
//   それを `custom:<キー>` として候補へ流していたので、選ぶと決して解決されないIDが
//   config へ書き込まれる状態になっていた（しかも「日本語のキー」なので、
//   カスタムアイテムの表示名そのものに見える＝誤選択を誘う）。
//
//   items.yml のキーが custom アイテムになる経路は<b>構造的に存在しない</b>:
//   ローダーは result 未指定なら `custom:<id>` を結果に据えるが、その id を持つ
//   カスタムアイテムが無ければレシピごと登録に失敗する。だから「今の2件だけ除外」ではなく
//   ファイルごと候補源から外すのが正しい。
//
// この関数はブラウザ非依存（DOM も fetch も触らない）。呼び出し側が読み込んだ生データを
// 渡すだけにして、Node のテストから実 yml を通した検証ができるようにしている。
(function (root, isBrowser) {
  function plain(raw) {
    if (raw == null || raw === "") return "";
    if (isBrowser && typeof root.stripDisplayNamePlain === "function") {
      return root.stripDisplayNamePlain(raw);
    }
    return String(raw);
  }

  function pushSection(out, section, nameKey, idPrefix) {
    if (!section || typeof section !== "object") return;
    for (const [id, entry] of Object.entries(section)) {
      const raw = entry && typeof entry === "object" ? entry[nameKey] : null;
      const label = plain(raw) || id;
      out.push({ id: (idPrefix || "") + id, label });
    }
  }

  /**
   * @param {object} sources
   * @param {object} [sources.catalog]       TrinityForge items/catalog.yml
   * @param {object} [sources.materials]     ArsPaper materials.yml
   * @param {object} [sources.threads]       ArsPaper threads.yml
   * @param {object} [sources.externalItems] TrinityForge items/external-items.yml
   * @returns {{id: string, label: string}[]} setCustomItemCandidates へそのまま渡せる形
   */
  function buildRecipeCustomCandidates(sources) {
    const s = sources && typeof sources === "object" ? sources : {};
    const out = [];
    pushSection(out, (s.catalog || {}).items, "display-name");
    pushSection(out, (s.materials || {}).materials, "display_name");
    // 空のスレッドは threads.yml に行が無い（Java 側の ThreadType が持つ）ので手で足す。
    out.push({ id: "thread_empty", label: "空のスレッド" });
    pushSection(out, (s.threads || {}).threads, "display_name", "thread_");
    const external = (s.externalItems || {}).items;
    if (external && typeof external === "object") {
      for (const [id, entry] of Object.entries(external)) {
        const raw = entry && typeof entry === "object" ? entry["display-name"] : null;
        out.push({ id, label: raw ? String(raw) : `外部: ${id}` });
      }
    }
    return out;
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = { buildRecipeCustomCandidates };
  }
  if (!isBrowser) return;
  root.buildRecipeCustomCandidates = buildRecipeCustomCandidates;
})(typeof window !== "undefined" ? window : globalThis, typeof window !== "undefined" && typeof document !== "undefined");
