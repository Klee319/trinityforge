"use strict";

// progression/level-broadcast.yml (tf-level-broadcast) 専用フォーム。
//
// 2026-08-17 新設: それまでこの config は「その他」ではなく「スキルギミック」に置かれ、
// schema も "generic"(汎用JSONツリーエディタ)だったため、キー名は英字のまま・
// 説明もゼロ・除外スキルは手打ちという状態だった。ユーザー指示により
// 「その他」カテゴリへ移し、専用GUIを与える。
//
// 説明文はすべて TrinityForge/src/main/resources/progression/level-broadcast.yml の
// 本文コメントから起こしたもの(勝手な文言は書かない)。
//
// 往復ロスレス: data を直接編集する(未知キーは触らないので温存される)。
(function () {
  const h = window.h;

  // /api/skills が読めない場合のフォールバック (constants.js の SKILL_FALLBACK_IDS と同一)。
  const SKILL_FALLBACK_IDS = [
    "ALCHEMY", "ARCHERY", "ARS_MAGIC", "ARS_SMITHING", "DIGGING", "ENCHANTING",
    "FARMING", "FISHING", "HEAVY_ARMOR", "HEAVY_WEAPONS", "LIGHT_ARMOR",
    "LIGHT_WEAPONS", "MINING", "SMITHING", "WOODCUTTING"
  ];

  function skillIds() {
    return (Array.isArray(window.SKILLS) && window.SKILLS.length)
      ? window.SKILLS.map((s) => s.id) : SKILL_FALLBACK_IDS.slice();
  }

  function skillLabel(id) {
    const ja = window.LABELS && typeof window.LABELS.enumLabel === "function"
      ? window.LABELS.enumLabel("skill", id) : id;
    return ja && ja !== id ? ja + " (" + id + ")" : id;
  }

  function field(label, control, desc) {
    return h("div", { class: "form-field" }, [
      window.fieldLabelEl(label, { label, desc: desc || "", hideKey: true }),
      control
    ]);
  }

  function card(title, bodyChildren) {
    return h("div", { class: "entry-card" }, [
      h("div", { class: "entry-head" }, [h("span", { class: "entry-key-label", text: title })]),
      h("div", { class: "entry-body" }, bodyChildren)
    ]);
  }

  function grid(fields) { return h("div", { class: "field-grid" }, fields); }
  function formHint(text) { return h("p", { class: "form-hint", text }); }

  function boolField(obj, key, label, desc) {
    return field(label, window.checkboxInput(obj[key] !== false, (v) => { obj[key] = !!v; }), desc);
  }

  function intField(obj, key, label, desc, fallback) {
    return field(label, window.numberInput(obj[key], (v) => {
      obj[key] = (v == null || v === "") ? fallback : Math.trunc(v);
    }, { int: true }), desc);
  }

  function numField(obj, key, label, desc, fallback) {
    return field(label, window.numberInput(obj[key], (v) => {
      obj[key] = (v == null || v === "") ? fallback : v;
    }), desc);
  }

  /** 文字列/数値のリストをチップで足し引きする共通ブロック。 */
  function listBlock(owner, key, opts) {
    const o = opts || {};
    const body = h("div", {});

    function normalized() {
      if (!Array.isArray(owner[key])) owner[key] = [];
      return owner[key];
    }

    function render() {
      const list = normalized();
      body.innerHTML = "";
      const chips = h("div", { class: "cf-mat-list" });
      if (!list.length) {
        chips.appendChild(h("p", { class: "form-hint", text: o.emptyText || "（なし＝全部アナウンスする）" }));
      }
      list.forEach((value, index) => {
        chips.appendChild(h("div", { class: "cf-mat-card" }, [
          h("div", { class: "cf-mat-card-head" }, [
            h("span", { class: "entry-key-label", text: o.display ? o.display(value) : String(value) }),
            h("button", {
              class: "btn-small danger", type: "button", text: "削除",
              onclick: () => { list.splice(index, 1); render(); }
            })
          ])
        ]));
      });
      chips.appendChild(o.adder(list, render));
      body.appendChild(chips);
    }

    render();
    return body;
  }

  window.buildLevelBroadcastForm = function buildLevelBroadcastForm(data) {
    const working = data && typeof data === "object" ? data : {};
    if (!working.sound || typeof working.sound !== "object") working.sound = {};
    const sound = working.sound;

    const root = h("div", { class: "dedicated-form" });
    root.appendChild(formHint(
      "誰かがキリのよいレベルへ到達したとき、サーバ全体へ1行流す機構です。"
      + "「レベルが上がった」という本人向けの通知（チャット/効果音/タイトル）は"
      + "「スキルEXP獲得」タブの level-up.* が担当していて、この画面とは独立して有効化できます。"
    ));

    // --- 基本 -------------------------------------------------------------
    root.appendChild(card("アナウンス", [
      grid([
        boolField(working, "enabled", "全体アナウンスを有効にする",
          "オフにすると、この画面の他の設定は一切効きません。"),
        intField(working, "multiple-of", "何レベルごとに流すか",
          "10 なら Lv10/20/30… で流れます。0 以下は無効なので、警告を出して既定(10)へ戻されます。", 10),
        intField(working, "max-announcements-per-batch", "1回のまとまりで流す最大行数",
          "アナウンスは (プレイヤー, スキル) ごとに最高到達レベルだけへ畳んでから流すので、"
          + "「1回のEXP付与で Lv9→Lv31」でも1行(Lv30)になります。"
          + "それでも管理コマンドで全スキルを一括で上げるとスキル数だけ行が出るため、ここで頭を押さえます。"
          + "超過分は黙って捨てます(件数はサーバログへ FINE で残ります)。", 3),
        boolField(working, "include-power", "総合(POWER)の到達も流す",
          "既定はオフ。POWER は各スキルのレベルアップから派生して積み上がる導出値で、"
          + "直接稼ぐ経路がありません。オンにすると「採掘 Lv30 到達」と「総合 Lv30 到達」が"
          + "同じ行動で連続して流れることがあります。")
      ]),
      field("アナウンス本文 (MiniMessage)",
        window.richTextInput(working.message == null ? "" : String(working.message), "minimessage",
          (v) => { working.message = v; }),
        "差し込みは %player%（プレイヤー名） / %skill%（スキルの表示名） / %level%（到達レベルの数値）。"
        + "%player% と %level% は必須で、どちらかを欠くと警告を出して既定の書式で表示されます"
        + "（設定ミスで「誰が何レベルになったのか分からない行」が流れるのを防ぐため）。"
        + "レガシーの & カラーコードは解釈されません。")
    ]));

    // --- 効果音 -----------------------------------------------------------
    root.appendChild(card("効果音", [
      grid([
        boolField(sound, "enabled", "アナウンスと同時に鳴らす", ""),
        field("音名", window.textInput(sound.key == null ? "" : String(sound.key), (v) => { sound.key = v; },
          "UI_TOAST_CHALLENGE_COMPLETE"),
          "enum 風(UI_TOAST_CHALLENGE_COMPLETE)でも、レジストリキー"
          + "(ui.toast.challenge_complete / minecraft:ui.toast.challenge_complete)でも構いません。"
          + "解決できない名前を書いても例外にはならず、警告を1回だけ出して無音で続行します。"),
        numField(sound, "volume", "音量", "", 1.0),
        numField(sound, "pitch", "ピッチ", "Minecraft の有効域 0.5〜2.0 へ丸められます。", 1.0)
      ])
    ]));

    // --- 除外 -------------------------------------------------------------
    const excludedSkills = listBlock(working, "excluded-skills", {
      emptyText: "（なし＝全スキルがアナウンス対象）",
      display: (v) => skillLabel(String(v)),
      adder: (list, render) => {
        const remaining = skillIds().filter((id) => !list.some((v) => String(v).toUpperCase() === id));
        if (!remaining.length) return h("p", { class: "form-hint", text: "全スキルが除外済みです。" });
        return window.listSelect({
          value: "",
          options: [{ value: "", primary: "+ 除外するスキルを選ぶ" }].concat(
            remaining.map((id) => ({ value: id, primary: skillLabel(id), secondary: id }))),
          onChange: (v) => { if (v) { list.push(v); render(); } }
        });
      }
    });

    const excludedLevels = listBlock(working, "excluded-levels", {
      emptyText: "（なし＝倍数レベルは全部アナウンスする）",
      display: (v) => "Lv" + v,
      adder: (list, render) => {
        let pending = null;
        return h("div", { class: "cf-mat-card-head" }, [
          window.numberInput(null, (v) => { pending = v; }, { int: true }),
          h("button", {
            class: "btn-small", type: "button", text: "+ 追加",
            onclick: () => {
              if (pending == null || pending === "") return;
              const level = Math.trunc(pending);
              if (!list.includes(level)) list.push(level);
              render();
            }
          })
        ]);
      }
    });

    root.appendChild(card("アナウンスしない対象", [
      formHint("ここに入れたものだけが静かになります。POWER は上の「総合(POWER)の到達も流す」で制御するので、"
        + "除外スキルへ書く必要はありません。"),
      field("除外するスキル", excludedSkills, ""),
      field("除外するレベル", excludedLevels,
        "例: 10 と 20 を入れると Lv10/Lv20 だけ静かになります。")
    ]));

    // app.js は {element, getData} を期待する(素の DOM ノードを返すと appendChild で落ちる)。
    return { element: root, getData: () => working };
  };
})();
