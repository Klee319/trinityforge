"use strict";

// afk.yml (tf-afk) 専用セクション。
//
// 2026-07-27新設: AfkService / AfkConfig / AfkActivityListener / AfkSuppressionListener は
// 実装済みの現役機構だが、これまで config-editor に一切登録されておらず yml 直編集しか
// 調整手段が無かった。独立タブは作らず、ユーザー指示により「使用制限スイッチ」
// (use-requirements タブ, tf-crafting-features.js の buildUseRequirementsForm) の中へ
// AFK セクションとして差し込む。保存は既存のコンパニオン方式(getExtraSaves で別 config id の
// データを返し、app.js が別ファイル afk.yml として保存する)に従う。
//
// 説明文はすべて TrinityForge/src/main/resources/afk.yml の本文コメントから起こしたもの
// (勝手な文言は書かない)。
//
// 往復ロスレス: working を直接編集。未知キーは温存。
(function () {
  const h = window.h;

  function field(key, control, opts) {
    const o = opts || {};
    return h("div", { class: "form-field" }, [
      window.fieldLabelEl(o.key || key, {
        label: o.label || key,
        desc: o.desc || "",
        hideKey: o.hideKey !== false
      }),
      control
    ]);
  }
  function grid(fields) { return h("div", { class: "field-grid" }, fields); }
  function card(headChildren, bodyChildren) {
    return h("div", { class: "entry-card" }, [
      h("div", { class: "entry-head" }, Array.isArray(headChildren) ? headChildren : [headChildren]),
      h("div", { class: "entry-body" }, bodyChildren)
    ]);
  }
  function formHint(text) {
    return h("p", { class: "form-hint", text });
  }
  function numField(obj, key, opts) {
    const o = opts || {};
    return field(key, window.numberInput(obj[key], (v) => {
      if (v == null || v === "") {
        if (o.clearable) delete obj[key];
        else obj[key] = o.fallback != null ? o.fallback : 0;
        return;
      }
      obj[key] = o.int ? Math.trunc(v) : v;
    }, o.int ? { int: true } : undefined), {
      label: o.label || key,
      desc: o.desc || "",
      key
    });
  }
  function textField(obj, key, opts) {
    const o = opts || {};
    return field(key, window.textInput(obj[key] == null ? "" : String(obj[key]), (v) => {
      if (!v && o.clearable) delete obj[key];
      else obj[key] = v;
    }, o.placeholder || ""), {
      label: o.label || key,
      desc: o.desc || "",
      key
    });
  }
  // 2026-07-29: MiniMessage を書く欄が素の <input> のままで、Lore/表示名の欄(richTextInput)と
  // 体裁も編集手段も揃っていなかった。着色パレット付きの共通リッチ入力へ寄せる。
  // richTextInput は GUI(着色パレット) と 簡易(タグ生編集) をいつでも切り替えられるので、
  // 従来どおりタグを直接書きたい場合も潰れない。
  function messageField(obj, key, opts) {
    const o = opts || {};
    const control = typeof window.richTextInput === "function"
      ? window.richTextInput(obj[key] == null ? "" : String(obj[key]), "minimessage", (v) => {
          if (!v && o.clearable) delete obj[key];
          else obj[key] = v;
        })
      : window.textInput(obj[key] == null ? "" : String(obj[key]), (v) => { obj[key] = v; }, o.placeholder || "");
    return field(key, control, {
      label: o.label || key,
      desc: o.desc || "",
      key
    });
  }
  function boolField(obj, key, opts) {
    const o = opts || {};
    return field(key, window.checkboxInput(!!obj[key], (v) => { obj[key] = v; }), {
      label: o.label || key,
      desc: o.desc || "",
      key
    });
  }
  function ensureObj(parent, key) {
    if (!parent[key] || typeof parent[key] !== "object" || Array.isArray(parent[key])) parent[key] = {};
    return parent[key];
  }

  /**
   * afk.yml のデータを受け取り { el, getData } を返す。
   * el は呼び出し側(buildUseRequirementsForm)のフォーム末尾へそのまま appendChild する。
   * getData() で現在値(working そのもの、往復ロスレス)を取り出す。
   */
  window.buildAfkSection = function buildAfkSection(afkData) {
    const working = afkData && typeof afkData === "object" ? afkData : {};
    const suppress = ensureObj(working, "suppress");

    const root = h("div", { class: "afk-section" });

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "AFK(離席)判定 (afk.yml)" })],
      [
        formHint("放置しているだけで報酬が積み上がる状態(AFK釣り機・モブトラップ放置・オートクリッカー放置)を止める機構です。"
          + "「人間にしか出せない入力」(移動・視点回転・チャット/コマンド・インベントリ操作等)だけを活動とみなし、"
          + "素のクリックや腕振りアニメーションは活動として数えません。/trinityforge reload で即反映されます。"),
        boolField(working, "enabled", {
          label: "有効化 (enabled)",
          desc: "false にすると判定も報酬停止もキックも一切行いません(完全に無効)。"
        }),
        grid([
          numField(working, "idle-seconds", {
            label: "無操作→AFK扱いまでの秒数 (idle-seconds)", int: true,
            desc: "無操作がこの秒数続いたらAFK扱いにします。既定300秒(5分)。短すぎると正常プレイ(採掘の待ち・"
              + "建築中の思考時間)を誤爆します。"
          }),
          numField(working, "kick-after-seconds", {
            label: "AFKキックまでの秒数 (kick-after-seconds)", int: true,
            desc: "AFK扱いになったプレイヤーを、無操作の合計がこの秒数に達した時点でキックします。"
              + "0でキックしません(報酬停止だけ行う)。0以外にする場合は idle-seconds 以上の値にしてください"
              + "(Java側は小さい値を idle-seconds へ黙って引き上げますが、editor は保存時点でエラーにします)。"
          })
        ]),
        messageField(working, "kick-message", {
          label: "キックメッセージ (kick-message)",
          desc: "キック時に表示するメッセージ。色・装飾はパレットから選べます(内部は MiniMessage 記法)。"
        }),
        grid([
          boolField(working, "notify", {
            label: "本人へ通知 (notify)",
            desc: "true でAFK突入/復帰を本人へチャット通知します。"
          }),
          boolField(working, "tab-suffix", {
            label: "タブリスト接尾辞 (tab-suffix)",
            desc: "true でタブリストの名前に接尾辞を付けます(誰が放置中か他プレイヤーからも分かる)。"
          })
        ]),
        messageField(working, "tab-suffix-text", {
          label: "タブリスト接尾辞テキスト (tab-suffix-text)",
          desc: "接尾辞のテキスト。色・装飾はパレットから選べます(内部は MiniMessage 記法)。"
            + "tab-suffix が false のときは使われません。"
        }),
        textField(working, "exempt-permission", {
          label: "免除権限 (exempt-permission)",
          desc: "この権限を持つプレイヤーはAFK判定の対象外(報酬停止もキックもされません)。常駐させたい管理者/"
            + "建築班向け。空文字にすると免除は無効になります(空文字は意味のある値として扱われ、既定値へは寄せません)。"
        }),
        numField(working, "check-interval-ticks", {
          label: "判定間隔 (check-interval-ticks)", int: true,
          desc: "判定タイマーの間隔(tick、20tick=1秒)。短くしても判定精度は上がらないので既定のままで構いません。"
            + "20未満は保存時にエラーになります(Java側で20へ丸められ、実挙動と値がずれるため)。"
        })
      ]
    ));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "AFK中に停止する報酬 (suppress)" })],
      [
        formHint("AFK中に停止する報酬を個別に切り替えられます。"),
        grid([
          boolField(suppress, "skill-exp", {
            label: "スキル(職業)EXP (skill-exp)",
            desc: "NativeExperienceDispatcher を通る全経路が対象。管理コマンドによる付与"
              + "(/tf progression exp)は別経路なので止まりません。"
          }),
          boolField(suppress, "vanilla-exp", {
            label: "バニラ経験値 (vanilla-exp)",
            desc: "オーブ回収・かまど精錬・釣り・取引など PlayerExpChangeEvent 経由の全増加が対象。"
          }),
          boolField(suppress, "mob-drops", {
            label: "TF追加ドロップ (mob-drops)",
            desc: "TFが上乗せする追加ドロップ(mob-overrides / mob-level-table の drops)が対象。"
              + "バニラ本来のドロップはここでは止めません(止めるとモブトラップが完全に死ぬため)。"
          }),
          boolField(suppress, "fishing-sell", {
            label: "釣り自動換金 (fishing-sell)",
            desc: "釣った魚の自動換金(fish-sell-toggle)が対象。"
          })
        ])
      ]
    ));

    return {
      element: root,
      getData: () => working
    };
  };
})();
