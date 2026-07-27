"use strict";

// 「はじめに」ホーム画面。起動直後に表示し、このツールでできること・現在の編集対象
// (ベースパス)・代表的な操作への導線を案内する。表示専用。

(function () {
  const h = window.h;

  // opts: { settings, onNavigate(id) }
  window.buildHomeView = function buildHomeView(opts) {
    const options = opts || {};
    const onNavigate = options.onNavigate || function () {};
    const root = h("div", { class: "home-view" });

    // ---- 概要 ----
    root.appendChild(h("section", { class: "home-hero" }, [
      h("h2", { class: "home-hero-title", text: "TrinityForge / ArsPaper コンフィグエディタ" }),
      h("p", { class: "home-hero-lead", text: "武器・防具・アイテムの設定ファイル(YAML)を、フォーム画面から安全に編集するためのツールです。値を入力して「保存」を押すと、実際の設定ファイルがその場で書き換わります。保存の直前には自動でバックアップが作られます。" })
    ]));

    // ---- 現在の編集対象 (ベースパス) ----
    root.appendChild(renderBasePaths(options.settings));

    // ---- 代表的な操作への導線 ----
    const cards = h("div", { class: "home-cards" });
    const links = [
      { id: "__manual__", title: "使い方を読む", desc: "はじめての方はこちら。用語と操作の流れを日本語で解説します。", tag: "案内" },
      { id: "catalog", title: "アイテムを追加する", desc: "アイテムカタログで、素材(Material)や表示名、テクスチャ(CMD)、使用スキルを設定します。", tag: "config" },
      { id: "items", title: "アイテムのレシピを編集する", desc: "作業台の3×3グリッドや儀式のUIで、ArsPaper のクラフトレシピを作ります(items)。", tag: "config" },
      { id: "materials", title: "アイテムの説明文・色を編集する", desc: "中間素材の説明文(lore)を範囲選択して着色し、tooltipプレビューで確認します(materials)。", tag: "config" },
      { id: "item-stats", title: "アイテムに固有ステを付ける", desc: "Materialごとに固定ステ・ランダムステを割り当てます。キーで検索・絞り込みもできます。", tag: "config" },
      { id: "__constants__", title: "戦闘バランスを調整", desc: "複数ファイルにまたがる戦闘定数を1画面で編集します。", tag: "tool" },
      { id: "__simulator__", title: "火力を試算する", desc: "設定値からダメージの目安を計算し、バランスを確認します。", tag: "tool" }
    ];
    for (const link of links) {
      cards.appendChild(h("button", {
        class: "home-card", type: "button", onclick: () => onNavigate(link.id)
      }, [
        h("div", { class: "home-card-head" }, [
          h("span", { class: "home-card-title", text: link.title }),
          h("span", { class: `nav-badge ${link.tag === "tool" ? "tool" : link.tag === "案内" ? "info" : ""}`, text: link.tag })
        ]),
        h("div", { class: "home-card-desc", text: link.desc })
      ]));
    }
    root.appendChild(h("section", {}, [
      h("div", { class: "home-section-title", text: "やりたいことから始める" }),
      cards
    ]));

    // ---- 注意 ----
    root.appendChild(h("section", { class: "home-note" }, [
      h("div", { class: "home-note-title", text: "保存についての注意" }),
      h("ul", { class: "home-note-list" }, [
        h("li", { text: "「保存」は実際の設定ファイルを書き換えます。編集内容はサーバーに即反映されるものではなく、ゲーム側での再読込コマンド(例: /trinityforge reload)が必要です。" }),
        h("li", { text: "保存の直前に、リポジトリ直下の backups/ フォルダへタイムスタンプ付きの自動バックアップ(.bak-...)を作成します(元のファイルと同じフォルダには作られません)。世代は既定で新しい50件まで保持し、超えた分は古いものから自動的に削除されます。元に戻したいときはbackups/から復元できます。" }),
        h("li", { text: "入力に不備がある場合は保存されず、どこが不正かを日本語で表示します(ファイルは変更されません)。" })
      ])
    ]));

    return root;
  };

  function renderBasePaths(settings) {
    const section = h("section", { class: "home-basepaths" });
    section.appendChild(h("div", { class: "home-section-title", text: "編集元とサーバ反映先" }));
    if (!settings || !settings.basePaths) {
      section.appendChild(h("div", { class: "home-basepath-row", text: "パス情報を取得できませんでした。" }));
      return section;
    }
    function addGroup(title, map) {
      section.appendChild(h("div", { class: "home-basepath-group", text: title }));
      if (!map) return;
      for (const [key, info] of Object.entries(map)) {
        const jaKey = key === "trinityforge" ? "TrinityForge" : key === "arspaper" ? "ArsPaper" : key;
        section.appendChild(h("div", { class: "home-basepath-row" }, [
          h("span", { class: "home-basepath-key", text: jaKey }),
          h("span", { class: "home-basepath-path", text: info.absolute || info.configured || "(未設定)" }),
          h("span", { class: `path-status ${info.exists ? "ok" : "missing"}`, text: info.exists ? "検出済み" : "未検出" })
        ]));
      }
    }
    addGroup("編集元 (repo resources)", settings.basePaths);
    addGroup("サーバ反映先 (plugins)", settings.deployPaths || {});
    section.appendChild(h("div", {
      class: "home-basepath-hint",
      text: "保存すると編集元へ書き込み、続けてサーバ反映先へ同じ内容をミラーします。パス変更は画面上部で「パス保存」。ゲーム内反映にはプラグイン reload が必要な場合があります。"
    }));
    return section;
  }
})();
