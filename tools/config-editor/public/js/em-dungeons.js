"use strict";

// EliteMobs 同梱の既定ダンジョン台帳(public/data/elitemobs-dungeons.json)のローダ。
// 台帳は tools/scripts/gen-mob-overrides.py が実サーバの plugins/EliteMobs から生成する
// 「何が既定で存在するか」の一覧であり、config ではない(編集対象ではない)。
//
// 用途:
//   - mob-overrides: 既定ダンジョン/既定モブのidを編集不可にする判定と、日本語名の初期表示
//   - dungeon/gates: 行き先ワールド・content-package のセレクト候補
//
// 読み込みは1回だけ。失敗しても機能は落とさない(候補が空になるだけで手動入力は常に可能)。
(function () {
  // 2026-07-26: 文書相対 "data/..." から**ルート絶対**へ変更。相対だと解決先が「現在の文書のパス」に
  // 依存するため、パスセグメントを持つ画面から読むと /<現在の階層>/data/... を取りに行って404になる。
  // エディタの他の fetch は全て "/api/..." のルート絶対で書かれており、ここだけが例外だった。
  const URL = "/data/elitemobs-dungeons.json";

  let dungeons = [];
  let byWorld = new Map();
  let promise = null;
  let loaded = false;

  function index(list) {
    dungeons = Array.isArray(list) ? list : [];
    byWorld = new Map();
    for (const d of dungeons) {
      if (!d || typeof d !== "object" || !d.world) continue;
      const mobs = Array.isArray(d.mobs) ? d.mobs : [];
      byWorld.set(String(d.world), {
        world: String(d.world),
        displayName: d.displayName ? String(d.displayName) : String(d.world),
        package: d.package ? String(d.package) : "",
        contentType: d.contentType ? String(d.contentType) : "",
        mobs,
        mobById: new Map(mobs.filter((m) => m && m.id).map((m) => [String(m.id), m]))
      });
    }
  }

  const EM = {
    /** 台帳を読み込む。多重呼び出しは同じ Promise を共有する。 */
    load() {
      if (promise) return promise;
      promise = fetch(URL, { cache: "no-cache" })
        .then((res) => (res.ok ? res.json() : null))
        .then((payload) => {
          index(payload && payload.dungeons);
          loaded = true;
          return dungeons;
        })
        .catch(() => {
          // 台帳が無い環境(生成前)でもエディタは動く。候補ゼロで手動入力に委ねる。
          loaded = true;
          return dungeons;
        });
      return promise;
    },

    /** 読み込み済みかどうか。未読込なら isKnown* は常に false を返す。 */
    isLoaded() { return loaded; },

    all() { return dungeons; },

    get(world) { return byWorld.get(String(world == null ? "" : world)) || null; },

    /** EliteMobs 同梱の既定ダンジョンか(= ワールド名を書き換えさせない対象か)。 */
    isKnownWorld(world) { return byWorld.has(String(world == null ? "" : world)); },

    /** 既定ダンジョンに実在するモブidか。 */
    isKnownMob(world, mobId) {
      const d = byWorld.get(String(world == null ? "" : world));
      return !!(d && d.mobById.has(String(mobId == null ? "" : mobId)));
    },

    dungeonName(world) {
      const d = byWorld.get(String(world == null ? "" : world));
      return d ? d.displayName : "";
    },

    mobName(world, mobId) {
      const d = byWorld.get(String(world == null ? "" : world));
      const m = d && d.mobById.get(String(mobId == null ? "" : mobId));
      return m && m.displayName ? String(m.displayName) : "";
    },

    /** listSelect 用: 全既定ダンジョン。日本語名を主・ワールド名を副に出す。 */
    dungeonOptions() {
      return dungeons.map((d) => ({
        value: String(d.world),
        primary: d.displayName ? String(d.displayName) : String(d.world),
        secondary: String(d.world),
        title: `${d.displayName || d.world} / ${d.world}`
      }));
    },

    /** listSelect 用: content_packages のファイル名(拡張子なし)。 */
    packageOptions() {
      const seen = new Map();
      for (const d of dungeons) {
        if (!d.package || seen.has(d.package)) continue;
        seen.set(d.package, {
          value: String(d.package),
          primary: d.displayName ? String(d.displayName) : String(d.package),
          secondary: String(d.package)
        });
      }
      return Array.from(seen.values());
    },

    /** listSelect 用: 指定ワールドのモブ。world 未指定なら全ダンジョン横断。 */
    mobOptions(world) {
      const scopes = world && byWorld.has(String(world))
        ? [byWorld.get(String(world))]
        : dungeons.map((d) => byWorld.get(String(d.world))).filter(Boolean);
      const out = [];
      const seen = new Set();
      for (const d of scopes) {
        for (const m of d.mobs) {
          if (!m || !m.id || seen.has(m.id)) continue;
          seen.add(m.id);
          out.push({
            value: String(m.id),
            primary: m.displayName ? String(m.displayName) : String(m.id),
            secondary: scopes.length === 1 ? String(m.id) : `${d.displayName} / ${m.id}`,
            title: `${m.displayName || m.id} (${d.displayName}) — ${m.id}`
          });
        }
      }
      return out;
    }
  };

  window.EM_DUNGEONS = EM;
  // フォームが開かれる前に取得を始める(初回描画の候補欠けを減らす)。
  if (typeof fetch === "function") EM.load();
})();
