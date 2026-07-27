"use strict";

// 外部プラグインが発行する ItemStack を、Material + CMD の公開識別子だけで台帳化する画面。
// TrinityForge はここからアイテムを生成しない。CMD重複検査と custom: 候補/レシピ照合だけに用いる。
(function () {
  const h = window.h;
  window.buildExternalItemsForm = function (data) {
    const working = data && typeof data === "object" ? data : {};
    if (!working.items || typeof working.items !== "object") working.items = {};
    const root = h("div", { class: "dedicated-form" });
    root.appendChild(h("div", { class: "form-hint", text: "外部プラグインのカスタム品を登録します。ここでは生成しません。Material と CMD が一致する実物だけを custom:ID としてレシピ・互換リストで認識し、CMD台帳の重複検査対象にもします。" }));
    const list = h("div", { class: "item-stats-list" });
    function render() {
      list.textContent = "";
      for (const [id, value] of Object.entries(working.items)) {
        const entry = value && typeof value === "object" ? value : (working.items[id] = {});
        const card = h("div", { class: "entry-card" });
        const idInput = h("input", { class: "field-input", value: id, spellcheck: "false" });
        idInput.addEventListener("change", () => {
          const next = idInput.value.trim().toLowerCase();
          if (!/^[a-z0-9_]+$/.test(next) || (next !== id && working.items[next])) { window.toast("IDは重複しない半角英小文字/数字/アンダースコアにしてください", "error"); idInput.value = id; return; }
          delete working.items[id]; working.items[next] = entry; render();
        });
        const cmd = window.numberInput(entry["custom-model-data"], (v) => { entry["custom-model-data"] = v == null ? 0 : v; }, { int: true, min: 0 });
        card.appendChild(h("div", { class: "stat-row" }, [h("span", { class: "mini-label", text: "ID (custom:ID)" }), idInput]));
        card.appendChild(h("div", { class: "stat-row" }, [h("span", { class: "mini-label", text: "Material" }), window.materialInput(entry.material || "", "material-list", (v) => { entry.material = v; })]));
        card.appendChild(h("div", { class: "stat-row" }, [h("span", { class: "mini-label", text: "CMD" }), cmd]));
        card.appendChild(h("div", { class: "stat-row" }, [h("span", { class: "mini-label", text: "表示名 (候補用)" }), h("input", { class: "field-input", value: entry["display-name"] || "", oninput: (e) => { entry["display-name"] = e.target.value; } })]));
        card.appendChild(h("button", { class: "btn-small danger", type: "button", text: "削除", onclick: () => { delete working.items[id]; render(); } }));
        list.appendChild(card);
      }
    }
    root.appendChild(list); render();
    root.appendChild(h("button", { class: "btn", type: "button", text: "+ 外部アイテムを登録", onclick: () => { let n = 1; while (working.items[`external_item_${n}`]) n++; working.items[`external_item_${n}`] = { material: "STONE", "custom-model-data": 0, "display-name": "" }; render(); } }));
    return { element: root, getData: () => working };
  };
})();
