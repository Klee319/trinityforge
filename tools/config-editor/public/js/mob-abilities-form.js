"use strict";

// combat/mob-abilities.yml (tf-mob-abilities) 専用フォーム。2026-07-31 新設。
//
// 敵の特殊攻撃「テンプレート」を編集する画面。モブへの割り当ては
// 「モブオーバーライド (mob-overrides)」画面の各モブの abilities: 欄で行う。
//
// 往復ロスレス方針: working を直接編集し、未知キー・キー順は温存する。

(function () {
  const h = window.h;

  // 以下のレイアウト部品は mob-forms.js / p5-forms.js と同じ実装。
  // それらは IIFE ローカルで window へ出ていないため、ここでも同じものを持つ
  // (クラス名を揃えないと既存画面と見た目が食い違う)。
  function card(headChildren, bodyChildren) {
    return h("div", { class: "entry-card" }, [
      h("div", { class: "entry-head" }, headChildren),
      h("div", { class: "entry-body" }, bodyChildren)
    ]);
  }
  function fieldRow(key, control, opts) {
    return h("div", { class: "form-field" }, [window.fieldLabelEl(key, opts), control]);
  }
  // 見出しの説明はブラウザ標準の title ではなく「?」の独自ツールチップへ回す (2026-08-01)。
  // 第2引数の名前は呼び出し側との互換のため据え置き (中身は説明文)。
  // util.js を読まない最小 window (単体テスト) では見出しだけを出す。
  // フォールバックでも title 属性は使わない — 標準ツールチップが二重に出る旧方式そのものなので。
  function subTitle(text, title) {
    if (typeof window.subTitleEl === "function") return window.subTitleEl(text, title);
    return h("div", { class: "sub-title", text });
  }
  function uniqueKey(map, base) {
    if (!Object.prototype.hasOwnProperty.call(map, base)) return base;
    let i = 1;
    let k = base + "_" + i;
    while (Object.prototype.hasOwnProperty.call(map, k)) k = base + "_" + (++i);
    return k;
  }
  function renameKey(map, oldKey, newKey) {
    const rebuilt = {};
    for (const k of Object.keys(map)) rebuilt[k === oldKey ? newKey : k] = map[k];
    for (const k of Object.keys(map)) delete map[k];
    Object.assign(map, rebuilt);
  }

  // 1.21 現行の PotionEffectType キー。tf-lifestyle-forms.js の POTION_EFFECT_OPTIONS と同じ集合。
  // 一覧に無いキーも自由入力で通す(バージョンで増えた分を editor の更新待ちにしない)。
  const POTION_EFFECT_IDS = ["SPEED", "SLOWNESS", "HASTE", "MINING_FATIGUE", "STRENGTH",
    "INSTANT_HEALTH", "INSTANT_DAMAGE", "JUMP_BOOST", "NAUSEA", "REGENERATION", "RESISTANCE",
    "FIRE_RESISTANCE", "WATER_BREATHING", "INVISIBILITY", "BLINDNESS", "NIGHT_VISION", "HUNGER",
    "WEAKNESS", "POISON", "WITHER", "HEALTH_BOOST", "ABSORPTION", "SATURATION", "GLOWING",
    "LEVITATION", "LUCK", "UNLUCK", "SLOW_FALLING", "CONDUIT_POWER", "DOLPHINS_GRACE",
    "BAD_OMEN", "HERO_OF_THE_VILLAGE"];

  // Java の MobAbility.Type と 1:1。増やすときは Java 側の enum と同時に
  // (lib/schema.js の MOB_ABILITY_TYPES と labels.js の ENUM_LABELS も同時に)。
  const TYPES = ["ground_slam", "projectile_volley", "charge", "aura",
    "teleport_strike", "beam", "summon",
    // 2026-08-16 追加
    "repulse", "vortex_pull", "delayed_zone",
    // 2026-08-17 に Java 側へ追加されていたが editor の型一覧から漏れていた
    "projectile_rain"];

  // 型の日本語名は labels.js の ENUM_LABELS["mob-ability-type"] が正 (辞書を二重に持たない)。
  // 2026-08-01 まではここだけに辞書があり、セレクト本体
  // (selectLabeledInput(..., "mob-ability-type", ...)) が引く labels.js 側にグループが無かったため、
  // **セレクトだけが生ID表示**になっていた。カード見出しは日本語なのでズレに気づけない。
  //
  // ⚠️ 辞書は**描画のたびに引き直す**。`const TYPE_LABELS = window.LABELS...` と束縛すると、
  // index.html の <script> 順が変わって labels.js より先に読まれた瞬間に `{}` を掴んだまま
  // 固定され、警告もエラーも出ないまま生ID表示へ戻る (読み込み順への暗黙依存)。
  function typeLabel(type) {
    const dict = (window.LABELS && window.LABELS.ENUM_LABELS
      && window.LABELS.ENUM_LABELS["mob-ability-type"]) || {};
    return dict[type] || type;
  }

  // 型ごとに「意味を持つ」パラメータ。意味の無い欄を出すと、書いても効かない設定を
  // 書かせてしまう(このリポジトリで何度も起きている「静かに無効」の作り方そのもの)。
  // 2026-09-04 追加。vertical-radius は円判定(hitsCircle)の上下方向の届き(「同じ床にいる」の判定)。
  // 円判定を使わない projectile_volley / summon には意味が無いので出さない。
  const FIELDS_BY_TYPE = {
    ground_slam: ["radius", "knockback", "vertical-radius"],
    projectile_volley: ["count", "spread-degrees", "projectile"],
    // 頭上から降らせる。radius=ばら撒く円の半径 / count=本数 / projectile=降らせる EntityType
    projectile_rain: ["radius", "count", "projectile"],
    charge: ["radius", "knockback", "vertical-radius"],
    aura: ["radius", "duration-seconds", "vertical-radius"],
    teleport_strike: ["radius", "knockback", "vertical-radius"],
    beam: ["radius", "count", "vertical-radius"],
    summon: ["radius", "count", "summon-type"],
    // 2026-08-16 追加。knockback は repulse=吹き飛ばし / vortex_pull=引き寄せ の強さ(向きが逆になるだけ)。
    // delayed_zone の duration-seconds は「印を置いてから着弾までの予告秒」で、aura の持続とは意味が違う。
    repulse: ["radius", "knockback", "vertical-radius"],
    vortex_pull: ["radius", "knockback", "vertical-radius"],
    delayed_zone: ["radius", "duration-seconds", "knockback", "vertical-radius"]
  };

  // [min, max, step] — Java 側の clamp と同じ範囲。
  const NUMERIC_BOUNDS = {
    "damage-percent": [0, 100, 0.05],
    "cooldown-seconds": [0.5, 600, 0.5],
    "chance": [0, 1, 0.01],
    "range": [1, 64, 1],
    "radius": [0, 32, 0.5],
    "count": [0, 64, 1],
    "spread-degrees": [0, 360, 5],
    "duration-seconds": [0, 60, 0.5],
    "knockback": [0, 5, 0.1],
    "particle-count": [0, 500, 1],
    "cast-seconds": [0, 2.5, 0.1],
    "vertical-radius": [0.5, 8, 0.5],
    "health-below": [0, 1, 0.05],
    "health-above": [0, 1, 0.05]
  };

  const FIELD_LABELS = {
    "damage-percent": "ダメージ倍率 (damage-percent)",
    "cooldown-seconds": "クールダウン秒 (cooldown-seconds)",
    "chance": "発動率 (chance)",
    "range": "射程 (range)",
    "radius": "半径 (radius)",
    "count": "個数 (count)",
    "spread-degrees": "扇の開き角 (spread-degrees)",
    "duration-seconds": "持続秒 (duration-seconds)",
    "knockback": "吹き飛ばし (knockback)",
    "particle-count": "パーティクル個数 (particle-count)",
    "cast-seconds": "予告秒 (cast-seconds)",
    "vertical-radius": "上下の届き (vertical-radius)",
    "lethal": "致命予告 (lethal)",
    "health-below": "残HP上限 (health-below)",
    "health-above": "残HP下限 (health-above)"
  };

  const FIELD_DESCS = {
    "damage-percent": "そのモブの攻撃力に対する倍率。0 にすると「ダメージなしの演出＋効果だけ」になります。",
    "chance": "判定1回あたりの発動率。判定間隔は上の check-interval-ticks です。",
    "range": "この距離より遠い相手には撃ちません。",
    "count": "投射数 / 召喚数 / beam の刻み数(ほぼ射程m)。",
    "duration-seconds": "aura は1秒ごとにダメージ倍率ぶんを刻むので、伸ばすほど総ダメージが増えます。",
    "particle-count": "1未満にすると演出そのものが出ません(0 は「消える」ではありません)。",
    "cast-seconds": "予告（詠唱）秒。0 は予告なし＝即時発動(従来どおり)。0 より大きく0.5未満は"
      + "Java側が0.5へ切り上げます。delayed_zone で未指定なら duration-seconds が予告時間として使われます。",
    "vertical-radius": "円判定の上下方向の届き(「同じ床にいる」の判定)。既定 3.0。radius へはフォールバックしません。",
    "lethal": "致命的な予告かどうか。最終ダメージの判定には使いません。プレイヤー1人に同時に向けられる"
      + "予告の本数の上限(致命1本・合計2本まで)を決める枠として使われます。",
    "health-below": "自分の残HP割合がこの値以下のときだけ発動します(既定1.0=制限なし)。",
    "health-above": "自分の残HP割合がこの値以上のときだけ発動します(既定0.0=制限なし)。両方指定すると中盤だけ出る技も作れます。"
  };

  /**
   * 候補つきの文字列セレクト。window.listSelect は cfg オブジェクト1個を受ける
   * (位置引数で呼ぶと cfg.options が undefined になり、候補が一切出ない)。
   * 候補に無い値も許すのは、バージョンで増えた EntityType / Sound を editor 側の
   * 語彙更新待ちにしないため。
   */
  // labels は省略可の { id -> 日本語名 } 辞書。渡さない場合は従来どおり生IDをそのまま出す
  // (Sound のように既存のJA辞書が無いものは無理に作らない)。
  function idSelect(current, ids, onChange, labels) {
    const cur = current == null ? "" : String(current);
    const CUSTOM = "__custom_id__";
    const ja = (id) => (labels && labels[id]) || id;
    const options = (ids || []).map((id) => ({ value: id, primary: ja(id), secondary: ja(id) === id ? "" : id, title: id }));
    if (cur && !(ids || []).includes(cur)) {
      options.unshift({ value: cur, primary: ja(cur), secondary: ja(cur) === cur ? "(一覧外)" : cur, title: cur });
    }
    options.push({ value: CUSTOM, primary: "その他(自由入力)…", secondary: "" });
    return window.listSelect({
      value: cur,
      placeholder: "選択…",
      allowCustom: true,
      customPlaceholder: "英大文字のキーを直接入力",
      customValue: CUSTOM,
      options: options,
      onCommit: (v) => { onChange(v); return true; }
    });
  }

  function numberField(host, key) {
    const bounds = NUMERIC_BOUNDS[key] || [0, 1000, 1];
    const input = h("input", {
      class: "field-input num", type: "number",
      min: String(bounds[0]), max: String(bounds[1]), step: String(bounds[2]),
      value: host[key] == null ? "" : String(host[key])
    });
    input.addEventListener("change", () => {
      if (input.value === "") { delete host[key]; return; }
      const raw = Number(input.value);
      if (!Number.isFinite(raw)) { delete host[key]; return; }
      const clamped = Math.max(bounds[0], Math.min(bounds[1], raw));
      host[key] = clamped;
      if (clamped !== raw) input.value = String(clamped);
    });
    return fieldRow(FIELD_LABELS[key] || key, input, { desc: FIELD_DESCS[key] });
  }

  function textField(host, key, label, candidates, desc, labels) {
    const control = candidates && candidates.length
      ? idSelect(host[key], candidates, (nv) => {
          if (!nv) delete host[key]; else host[key] = nv;
        }, labels)
      : (() => {
          const input = h("input", {
            class: "field-input", value: host[key] == null ? "" : String(host[key])
          });
          input.addEventListener("change", () => {
            const nv = input.value.trim();
            if (!nv) delete host[key]; else host[key] = nv;
          });
          return input;
        })();
    return fieldRow(label, control, { desc: desc });
  }

  function effectsBlock(entry) {
    const box = h("div", { class: "card-list-body" });
    function render() {
      box.innerHTML = "";
      box.appendChild(h("div", {
        class: "field-desc",
        style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 4px;",
        text: "命中した相手に付けるポーション効果。ダメージ倍率を 0 にして効果だけ付ける使い方もできます。"
      }));
      const list = Array.isArray(entry.effects) ? entry.effects : [];
      list.forEach((effect, index) => {
        if (!effect || typeof effect !== "object") return;
        box.appendChild(h("div", { class: "stat-row" }, [
          h("span", { class: "range-label", text: "効果" }),
          idSelect(effect.type, POTION_EFFECT_IDS, (nv) => { effect.type = nv; }, window.POTION_EFFECT_LABELS_JA),
          h("span", { class: "range-label", text: "秒" }),
          (() => {
            const input = h("input", {
              class: "field-input num", type: "number", min: "0", step: "0.5",
              value: effect["duration-seconds"] == null ? "" : String(effect["duration-seconds"])
            });
            input.addEventListener("change", () => {
              const v = Number(input.value);
              if (input.value === "" || !Number.isFinite(v) || v < 0) delete effect["duration-seconds"];
              else effect["duration-seconds"] = v;
            });
            return input;
          })(),
          h("span", { class: "range-label", text: "強さ" }),
          (() => {
            const input = h("input", {
              class: "field-input num", type: "number", min: "0", step: "1",
              value: effect.amplifier == null ? "" : String(effect.amplifier)
            });
            input.addEventListener("change", () => {
              const v = Number(input.value);
              if (input.value === "" || !Number.isInteger(v) || v < 0) delete effect.amplifier;
              else effect.amplifier = v;
            });
            return input;
          })(),
          h("button", {
            class: "btn-small danger", type: "button", text: "×",
            onclick: () => {
              entry.effects.splice(index, 1);
              if (!entry.effects.length) delete entry.effects;
              render();
            }
          })
        ]));
      });
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 効果を追加",
        onclick: () => {
          if (!Array.isArray(entry.effects)) entry.effects = [];
          entry.effects.push({ type: "SLOWNESS", "duration-seconds": 3, amplifier: 0 });
          render();
        }
      }));
    }
    render();
    return box;
  }

  window.buildMobAbilitiesForm = function buildMobAbilitiesForm(data) {
    const working = data && typeof data === "object" ? data : {};
    if (typeof working.enabled !== "boolean") working.enabled = true;
    if (!working.abilities || typeof working.abilities !== "object") working.abilities = {};
    const root = h("div", { class: "dedicated-form" });

    function headerCard() {
      const enabled = window.checkboxInput(working.enabled, (v) => { working.enabled = v; });
      const interval = h("input", {
        class: "field-input num", type: "number", min: "5", max: "200", step: "1",
        value: String(working["check-interval-ticks"] == null ? 20 : working["check-interval-ticks"])
      });
      interval.addEventListener("change", () => {
        const v = Math.round(Number(interval.value));
        const clamped = Number.isFinite(v) ? Math.max(5, Math.min(200, v)) : 20;
        working["check-interval-ticks"] = clamped;
        interval.value = String(clamped);
      });
      return card([subTitle("全体設定")], [
        fieldRow("有効化 (enabled)", enabled,
          { desc: "false にすると周期タスクそのものを回しません(全モブの特殊攻撃が止まります)。" }),
        fieldRow("判定間隔tick (check-interval-ticks)", interval,
          { desc: "短くすると負荷が直線的に増えます。5〜200。実際の発動頻度は各技のクールダウンと発動率で決まります。" })
      ]);
    }

    function abilityCard(id) {
      const entry = working.abilities[id] && typeof working.abilities[id] === "object"
        ? working.abilities[id] : (working.abilities[id] = {});
      if (!TYPES.includes(entry.type)) entry.type = "ground_slam";

      const nameLabel = h("span", {
        class: "card-title",
        text: entry["display-name"] ? String(entry["display-name"]) : id
      });
      const head = [nameLabel, h("span", { class: "card-subtitle", text: typeLabel(entry.type) })];

      const body = h("div", { class: "card-list-body" });
      function renderBody() {
        body.innerHTML = "";
        const idInput = h("input", { class: "field-input", value: id });
        idInput.addEventListener("change", () => {
          const next = idInput.value.trim().toLowerCase();
          if (!next || next === id) { idInput.value = id; return; }
          if (!/^[a-z0-9_]+$/.test(next)) {
            alert("IDは半角英小文字・数字・アンダースコアのみ使用できます");
            idInput.value = id;
            return;
          }
          if (Object.prototype.hasOwnProperty.call(working.abilities, next)) {
            alert("同じIDが既にあります");
            idInput.value = id;
            return;
          }
          renameKey(working.abilities, id, next);
          render();
        });
        body.appendChild(fieldRow("テンプレートID", idInput,
          { desc: "モブオーバーライドの abilities: からこのIDで参照します。改名すると参照は追随しません。" }));

        body.appendChild(textField(entry, "display-name", "技名 (display-name)", null,
          "発動時に周囲へアクションバーで出す名前。MiniMessage 可。空欄なら無表示。"));

        body.appendChild(fieldRow("型 (type)",
          window.selectLabeledInput
            ? window.selectLabeledInput(entry.type, TYPES, "mob-ability-type", (v) => {
                entry.type = v;
                renderBody();
              })
            : (() => {
                const sel = h("select", { class: "field-input" });
                for (const t of TYPES) {
                  sel.appendChild(h("option", { value: t, text: typeLabel(t), selected: entry.type === t }));
                }
                sel.addEventListener("change", () => { entry.type = sel.value; renderBody(); });
                return sel;
              })(),
          { desc: "型を変えると下の項目が入れ替わります(その型で意味を持つ項目だけを出します)。" }));

        body.appendChild(fieldRow("ダメージ種別 (damage-type)",
          window.selectLabeledInput
            ? window.selectLabeledInput(String(entry["damage-type"] || "physical"),
                ["physical", "magical"], "mob-ability-damage-type", (v) => { entry["damage-type"] = v; })
            : (() => {
                const sel = h("select", { class: "field-input" });
                for (const t of ["physical", "magical"]) {
                  sel.appendChild(h("option", {
                    value: t, text: t, selected: String(entry["damage-type"] || "physical") === t
                  }));
                }
                sel.addEventListener("change", () => { entry["damage-type"] = sel.value; });
                return sel;
              })(),
          { desc: "magical は魔法防御で受けます。物理/魔法の防具を作り分ける意味がここで生まれます。" }));

        // 全型に共通の数値
        for (const key of ["damage-percent", "cooldown-seconds", "chance", "range", "cast-seconds",
          "health-below", "health-above"]) {
          body.appendChild(numberField(entry, key));
        }
        // 全型に共通の予告フラグ (2026-09-04 追加)。数値ではないので numberField を使わず
        // enabled 欄と同じ checkboxInput で描画する。
        body.appendChild(fieldRow(FIELD_LABELS.lethal,
          window.checkboxInput(entry.lethal === true, (v) => {
            if (v) entry.lethal = true; else delete entry.lethal;
          }),
          { desc: FIELD_DESCS.lethal }));
        // 型ごとの数値/文字列
        for (const key of FIELDS_BY_TYPE[entry.type] || []) {
          if (key === "projectile") {
            body.appendChild(textField(entry, "projectile", "投射物 (projectile)",
              window.VANILLA_MOBS || null,
              "EntityType 名。ARROW / SMALL_FIREBALL / WITHER_SKULL など。必須です。",
              window.MOB_LABELS_JA));
          } else if (key === "summon-type") {
            body.appendChild(textField(entry, "summon-type", "召喚するモブ (summon-type)",
              window.VANILLA_MOBS || null,
              "EntityType 名。呼ばれた増援は呼び主と同じレベル帯で刻印されます。必須です。",
              window.MOB_LABELS_JA));
          } else {
            body.appendChild(numberField(entry, key));
          }
        }

        body.appendChild(subTitle("演出"));
        body.appendChild(textField(entry, "particle", "パーティクル (particle)", window.VANILLA_PARTICLES || null,
          "データ必須の種類(FLASH / DUST / BLOCK / ITEM / ENTITY_EFFECT / SHRIEK / SCULK_CHARGE / VIBRATION 等)は"
          + "書いても無視されます(データ引数なしで撃つと例外になり戦闘処理を道連れにするため)。",
          window.PARTICLE_LABELS_JA));
        body.appendChild(numberField(entry, "particle-count"));
        // Sound / PotionEffectType の一覧は editor 語彙に無いので自由入力。
        body.appendChild(textField(entry, "sound", "効果音 (sound)", null,
          "Sound 名。ENTITY_GENERIC_EXPLODE など。未知の名前は無音になります。"));

        body.appendChild(subTitle("命中時の効果 (effects)"));
        body.appendChild(effectsBlock(entry));

        body.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "このテンプレートを削除",
          onclick: () => {
            if (!confirm(id + " を削除しますか？(このIDを参照しているモブは技を撃たなくなります)")) return;
            delete working.abilities[id];
            render();
          }
        }));
      }
      renderBody();
      return window.collapsibleCard(head, [body], { expanded: false });
    }

    function render() {
      root.innerHTML = "";
      root.appendChild(headerCard());
      const list = h("div", { class: "card-list" });
      const ids = Object.keys(working.abilities);
      if (!ids.length) {
        list.appendChild(h("div", {
          class: "field-desc",
          text: "テンプレートがありません。「+ テンプレートを追加」で作成します。"
        }));
      }
      for (const id of ids) list.appendChild(abilityCard(id));
      list.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ テンプレートを追加",
        onclick: () => {
          const id = uniqueKey(working.abilities, "new_ability");
          working.abilities[id] = { type: "ground_slam", "damage-percent": 1.0,
            "cooldown-seconds": 10, chance: 0.3, range: 8, radius: 4 };
          render();
        }
      }));
      root.appendChild(card([subTitle("特殊攻撃テンプレート (abilities)",
        "モブへの割り当ては「モブオーバーライド (mob-overrides)」画面の各モブの abilities: 欄で行います")], [list]));
    }

    render();
    return { element: root, getData: () => working };
  };
})();
