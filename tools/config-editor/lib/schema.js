"use strict";

// 保存前のスキーマ検証。エラーは配列で返す (空配列=OK)。
// 汎用(generic)は構造自由なので最小限のチェックのみ。

// lore.yml の宣言語彙。APPLIES_TO は「ステータスの適用対象(PLAYER/MOB)」で、
// 下の APPLIES_TO(アイテム種別 weapon/armor/...)とは別物なので別名で受ける。
const {
  TRIGGER_WHEN,
  SOURCE_SCOPE,
  APPLIES_TO: STAT_APPLIES_TO,
  STACKING,
} = require("./lore-declaration-vocabulary");

const BIND_TYPES = ["SOULBOUND", "TRADEABLE", "OWNER_BOUND"];
const APPLIES_TO = ["weapon", "armor", "tool", "other"];

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isInteger(value) {
  return typeof value === "number" && Number.isInteger(value);
}

function isNumber(value) {
  return typeof value === "number" && Number.isFinite(value);
}

function isNonNegInteger(value) {
  return isInteger(value) && value >= 0;
}

/** 1以上の整数（抽選の weight 用。0 を許すと「候補にあるのに絶対に出ない」設定が黙って通る）。 */
function isPositiveInt(value) {
  return Number.isInteger(value) && value >= 1;
}

// item-stats のキーは Material名 (英大文字/数字/アンダースコア) + 任意の整数CMD。
// CMD は 1〜7桁 (0〜9999999) に制限し、巨大値や Number.isSafeInteger を超える桁を弾く。
const ITEM_STATS_KEY_RE = /^[A-Z0-9_]+(#\d{1,7})?$/;
// Material/EntityType名の書式チェック (実際のvocab照合はクライアント側UIが担当。他schemaの
// Material配列検証と同じ規約: 大文字英数字とアンダースコアのみ)。
const MATERIAL_RE = /^[A-Z0-9_]+$/;

function validateItemStats(data, errors) {
  if (!isPlainObject(data) || !("items" in data)) {
    errors.push("ルートに items キーが必要です");
    return;
  }
  const items = data.items;
  if (items === null) return; // items: null は空扱い
  if (!isPlainObject(items)) {
    errors.push("items はマップである必要があります");
    return;
  }
  for (const [key, entry] of Object.entries(items)) {
    if (!ITEM_STATS_KEY_RE.test(key)) {
      errors.push(`items.${key}: キーは MATERIAL または MATERIAL#<整数(1〜7桁)> の形式である必要があります`);
    }
    if (!isPlainObject(entry)) {
      errors.push(`items.${key}: エントリはマップである必要があります`);
      continue;
    }
    if (entry.fixed !== undefined && entry.fixed !== null) {
      if (!isPlainObject(entry.fixed)) {
        errors.push(`items.${key}.fixed: マップである必要があります`);
      } else {
        for (const [stat, val] of Object.entries(entry.fixed)) {
          if (!isNumber(val)) errors.push(`items.${key}.fixed.${stat}: 数値である必要があります`);
        }
      }
    }
    if (entry.random !== undefined && entry.random !== null) {
      if (!isPlainObject(entry.random)) {
        errors.push(`items.${key}.random: マップである必要があります`);
      } else {
        for (const [stat, range] of Object.entries(entry.random)) {
          if (!isPlainObject(range) || !isNumber(range.min) || !isNumber(range.max)) {
            errors.push(`items.${key}.random.${stat}: {min, max} の数値が必要です`);
          } else if (range.min > range.max) {
            errors.push(`items.${key}.random.${stat}: min(${range.min}) <= max(${range.max}) が必要です`);
          }
        }
      }
    }
    for (const intKey of ["max-slots", "max-glyphs", "max-glyph-tier", "max-bind-tier"]) {
      if (entry[intKey] !== undefined && entry[intKey] !== null && !isNonNegInteger(entry[intKey])) {
        errors.push(`items.${key}.${intKey}: 0以上の整数である必要があります`);
      }
    }
    // 品質基準値: クラフト品質modeのオフセット。負値も許容する整数。
    if (entry["quality-mode-offset"] !== undefined && entry["quality-mode-offset"] !== null
        && !isInteger(entry["quality-mode-offset"])) {
      errors.push(`items.${key}.quality-mode-offset: 整数である必要があります (負値可)`);
    }
    if (entry["special-effects"] !== undefined && entry["special-effects"] !== null) {
      if (!Array.isArray(entry["special-effects"])) {
        errors.push(`items.${key}.special-effects: 配列である必要があります`);
      } else {
        for (let i = 0; i < entry["special-effects"].length; i++) {
          if (typeof entry["special-effects"][i] !== "string") {
            errors.push(`items.${key}.special-effects[${i}]: 文字列である必要があります`);
          }
        }
      }
    }
    if (entry["set-effects"] !== undefined && entry["set-effects"] !== null) {
      if (!isPlainObject(entry["set-effects"])) {
        errors.push(`items.${key}.set-effects: マップである必要があります`);
      } else if (entry["set-effects"].thresholds !== undefined && entry["set-effects"].thresholds !== null) {
        const thr = entry["set-effects"].thresholds;
        if (!isPlainObject(thr)) {
          errors.push(`items.${key}.set-effects.thresholds: マップである必要があります`);
        } else {
          for (const [tk, stats] of Object.entries(thr)) {
            if (!/^\d+$/.test(tk) || Number(tk) < 1) {
              errors.push(`items.${key}.set-effects.thresholds.${tk}: 閾値キーは1以上の整数である必要があります`);
            }
            if (!isPlainObject(stats)) {
              errors.push(`items.${key}.set-effects.thresholds.${tk}: ステータスマップである必要があります`);
            } else {
              for (const [stat, val] of Object.entries(stats)) {
                if (!isNumber(val)) errors.push(`items.${key}.set-effects.thresholds.${tk}.${stat}: 数値である必要があります`);
              }
            }
          }
        }
      }
    }
    // 乗算モード (multipliers.<layer>.{fixed,per-quality,random})。
    // 未選択レイヤ (__unset__) は保存前に必ずレイヤを割り当てる。
    if (entry.multipliers !== undefined && entry.multipliers !== null) {
      if (!isPlainObject(entry.multipliers)) {
        errors.push(`items.${key}.multipliers: マップである必要があります`);
      } else {
        for (const [layerId, layer] of Object.entries(entry.multipliers)) {
          if (layerId === "__unset__") {
            errors.push(`items.${key}.multipliers: 乗算レイヤが未選択のステがあります (レイヤを選択してください)`);
          }
          if (!isPlainObject(layer)) {
            errors.push(`items.${key}.multipliers.${layerId}: マップである必要があります`);
            continue;
          }
          for (const secKey of ["fixed", "per-quality"]) {
            const sec = layer[secKey];
            if (sec === undefined || sec === null) continue;
            if (!isPlainObject(sec)) {
              errors.push(`items.${key}.multipliers.${layerId}.${secKey}: マップである必要があります`);
              continue;
            }
            for (const [stat, val] of Object.entries(sec)) {
              if (!isNumber(val)) errors.push(`items.${key}.multipliers.${layerId}.${secKey}.${stat}: 数値(倍率)である必要があります`);
            }
          }
          const rnd = layer.random;
          if (rnd !== undefined && rnd !== null) {
            if (!isPlainObject(rnd)) {
              errors.push(`items.${key}.multipliers.${layerId}.random: マップである必要があります`);
            } else {
              for (const [stat, range] of Object.entries(rnd)) {
                if (!isPlainObject(range) || !isNumber(range.min) || !isNumber(range.max)) {
                  errors.push(`items.${key}.multipliers.${layerId}.random.${stat}: {min, max} の数値が必要です`);
                } else if (range.min > range.max) {
                  errors.push(`items.${key}.multipliers.${layerId}.random.${stat}: min <= max が必要です`);
                }
              }
            }
          }
        }
      }
    }
  }
  // 任意カテゴリのフォールバック上書き (fallback-overrides.<catId>.{fixed,lore-default})。
  if (data["fallback-overrides"] !== undefined && data["fallback-overrides"] !== null) {
    const ov = data["fallback-overrides"];
    if (!isPlainObject(ov)) {
      errors.push("fallback-overrides: マップである必要があります");
    } else {
      for (const [catId, sec] of Object.entries(ov)) {
        if (!isPlainObject(sec)) {
          errors.push(`fallback-overrides.${catId}: マップである必要があります`);
          continue;
        }
        if (sec.fixed !== undefined && sec.fixed !== null) {
          if (!isPlainObject(sec.fixed)) {
            errors.push(`fallback-overrides.${catId}.fixed: マップである必要があります`);
          } else {
            for (const [stat, val] of Object.entries(sec.fixed)) {
              if (!isNumber(val)) errors.push(`fallback-overrides.${catId}.fixed.${stat}: 数値である必要があります`);
            }
          }
        }
      }
    }
  }
}

function validateCatalog(data, errors) {
  if (!isPlainObject(data) || !("items" in data)) {
    errors.push("ルートに items キーが必要です");
    return;
  }
  const items = data.items;
  if (items === null) return;
  if (!isPlainObject(items)) {
    errors.push("items はマップである必要があります");
    return;
  }
  for (const [id, entry] of Object.entries(items)) {
    if (!isPlainObject(entry)) {
      errors.push(`items.${id}: マップである必要があります`);
      continue;
    }
    if (!entry.material || typeof entry.material !== "string") {
      errors.push(`items.${id}.material: 必須の文字列です`);
    }
    if (entry["custom-model-data"] !== undefined && entry["custom-model-data"] !== null && !isNonNegInteger(entry["custom-model-data"])) {
      errors.push(`items.${id}.custom-model-data: 0以上の整数である必要があります`);
    }
    if (entry["use-level-requirement"] !== undefined && entry["use-level-requirement"] !== null && !isNonNegInteger(entry["use-level-requirement"])) {
      errors.push(`items.${id}.use-level-requirement: 0以上の整数である必要があります`);
    }
    if (entry["bind-type"] !== undefined && entry["bind-type"] !== null) {
      const bt = entry["bind-type"] === "MATERIAL_TRADEABLE" ? "TRADEABLE" : entry["bind-type"];
      if (!BIND_TYPES.includes(bt)) {
        errors.push(`items.${id}.bind-type: ${BIND_TYPES.join(" / ")} のいずれかである必要があります`);
      }
    }
    validateCatalogRecipe(entry.recipe, `items.${id}.recipe`, errors);
    if (entry.recipes !== undefined && entry.recipes !== null) {
      if (!Array.isArray(entry.recipes)) {
        errors.push(`items.${id}.recipes: レシピマップの配列である必要があります`);
      } else {
        entry.recipes.forEach((r, i) => {
          validateCatalogRecipe(r, `items.${id}.recipes[${i}]`, errors);
        });
      }
    }
  }
}

// reversible (解凍を許可) の付与条件判定: shaped は shape上の全非空スロットの参照値、
// shapeless は全ingredientが、それぞれ同一Material/list:/custom:参照であることを求める。
// (forms.js側の同名ロジックと同じ判定基準。素材未設定/空は「同一」とみなさない。)
function catalogRecipeIngredientsAllSame(recipe) {
  const type = recipe.type || "shaped";
  if (type === "shapeless") {
    const arr = Array.isArray(recipe.ingredients) ? recipe.ingredients : [];
    if (arr.length === 0) return false;
    return arr.every((v) => typeof v === "string" && v !== "" && v === arr[0]);
  }
  const ing = isPlainObject(recipe.ingredients) ? recipe.ingredients : {};
  const shape = Array.isArray(recipe.shape) ? recipe.shape : [];
  const symbols = new Set();
  for (const row of shape) {
    for (const ch of String(row == null ? "" : row)) {
      if (ch !== " ") symbols.add(ch);
    }
  }
  if (symbols.size === 0) return false;
  const values = [...symbols].map((s) => ing[s]);
  return values.every((v) => typeof v === "string" && v !== "" && v === values[0]);
}

// catalog.yml items.<id>.recipe / items.<id>.recipes[i] (任意)。type/shape/ingredients/amount の軽い検証のみ。
// prefix はレシピマップ自身のフルパス (例: items.foo.recipe / items.foo.recipes[1])。
// shaped の shape⇔ingredients 相互検証は ars-recipes の validateShape を再利用する。
function validateCatalogRecipe(recipe, prefix, errors) {
  if (recipe === undefined || recipe === null) return;
  if (!isPlainObject(recipe)) { errors.push(`${prefix}: マップである必要があります`); return; }
  if (recipe.method !== undefined && recipe.method !== null && !CATALOG_RECIPE_METHODS.includes(recipe.method)) {
    errors.push(`${prefix}.method: ${CATALOG_RECIPE_METHODS.join(" / ")} のいずれかである必要があります`);
  }
  const method = recipe.method || "workbench";
  // reversible (解凍を許可): workbench/inventory かつ 素材が全て同一のレシピにのみ設定できる。
  if (recipe.reversible !== undefined && recipe.reversible !== null) {
    if (typeof recipe.reversible !== "boolean") {
      errors.push(`${prefix}.reversible: true/false である必要があります`);
    } else if (recipe.reversible) {
      if (method !== "workbench" && method !== "inventory") {
        errors.push(`${prefix}.reversible: workbench または inventory のレシピにのみ設定できます (解凍を許可)`);
      } else if (!catalogRecipeIngredientsAllSame(recipe)) {
        errors.push(`${prefix}.reversible: 素材が全て同一のレシピにのみ設定できます (解凍を許可)`);
      }
    }
  }
  if (method === "combine" || method === "netherite") {
    if (recipe["source-item"] !== undefined && recipe["source-item"] !== null
        && (typeof recipe["source-item"] !== "string" || !recipe["source-item"])) {
      errors.push(`${prefix}.source-item: カタログID(文字列)である必要があります`);
    }
    if (method === "combine") {
      if (recipe["combine-exp"] !== undefined && recipe["combine-exp"] !== null && !isNonNegInteger(recipe["combine-exp"])) {
        errors.push(`${prefix}.combine-exp: 0以上の整数である必要があります`);
      }
      if (recipe["addition-item"] !== undefined && recipe["addition-item"] !== null
          && (typeof recipe["addition-item"] !== "string" || !recipe["addition-item"])) {
        errors.push(`${prefix}.addition-item: カタログID(文字列)である必要があります`);
      }
      if (recipe["inherit-source-quality"] !== undefined && recipe["inherit-source-quality"] !== null
          && typeof recipe["inherit-source-quality"] !== "boolean") {
        errors.push(`${prefix}.inherit-source-quality: true/false である必要があります`);
      }
    }
    if (recipe.amount !== undefined && recipe.amount !== null && !isNonNegInteger(recipe.amount)) {
      errors.push(`${prefix}.amount: 0以上の整数である必要があります`);
    }
    if (recipe.register !== undefined && recipe.register !== null && typeof recipe.register !== "boolean") {
      errors.push(`${prefix}.register: true/false である必要があります`);
    }
    return;
  }
  if (recipe.type !== undefined && recipe.type !== null && !RECIPE_TYPES.includes(recipe.type)) {
    errors.push(`${prefix}.type: ${RECIPE_TYPES.join(" / ")} のいずれかである必要があります`);
  }
  // inventory (インベントリ内2×2クラフト) はグリッドが2×2 (workbenchは3×3)。
  const gridSize = method === "inventory" ? 2 : 3;
  const maxShapeless = gridSize * gridSize;
  if (recipe.ingredients !== undefined && recipe.ingredients !== null) {
    const type = recipe.type || "shaped";
    if (type === "shapeless") {
      if (!Array.isArray(recipe.ingredients)) {
        errors.push(`${prefix}.ingredients: shapeless では Material名の配列である必要があります`);
      } else {
        if (recipe.ingredients.length > maxShapeless) errors.push(`${prefix}.ingredients: shapeless は最大${maxShapeless}個までです`);
        recipe.ingredients.forEach((m, i) => {
          if (typeof m !== "string" || !m) errors.push(`${prefix}.ingredients[${i}]: Material名(文字列)である必要があります`);
        });
      }
    } else if (!isPlainObject(recipe.ingredients)) {
      errors.push(`${prefix}.ingredients: shaped では 記号->Material名 のマップである必要があります`);
    }
  }
  if ((recipe.type || "shaped") === "shaped") {
    validateShape(recipe, prefix, errors, gridSize);
  }
  if (recipe.amount !== undefined && recipe.amount !== null && !isNonNegInteger(recipe.amount)) {
    errors.push(`${prefix}.amount: 0以上の整数である必要があります`);
  }
  if (recipe.register !== undefined && recipe.register !== null && typeof recipe.register !== "boolean") {
    errors.push(`${prefix}.register: true/false である必要があります`);
  }
  // 向き固定 (shaped workbench のみ意味を持つ。boolean以外は拒否)。
  // strict-orientation: true = 登録した向き以外(左右反転配置)を拒否。デフォルトはバニラ同様に両向き可。
  if (recipe["strict-orientation"] !== undefined && recipe["strict-orientation"] !== null
      && typeof recipe["strict-orientation"] !== "boolean") {
    errors.push(`${prefix}.strict-orientation: true/false である必要があります`);
  }
  // 旧仕様キー mirror (逆意味) は警告 (editorが開けば自動除去される)。
  if (recipe.mirror !== undefined && recipe.mirror !== null && typeof recipe.mirror !== "boolean") {
    errors.push(`${prefix}.mirror: true/false である必要があります (旧キー。strict-orientation への移行推奨)`);
  }
  if (recipe.method === "ritual" || recipe["pedestal-items"] !== undefined) {
    validatePedestalItems(recipe["pedestal-items"], prefix, errors);
    validateSource(recipe.source, prefix, errors);
  }
}

// ---- ArsPaper items.yml (ars-recipes) ----
// inventory = インベントリ内2×2クラフト(手持ちで作れる小型レシピ)。workbench(作業台3×3)とは
// グリッドサイズのみ異なる (method切替時の shape 上限は validateShape 側で分岐)。
const CATALOG_RECIPE_METHODS = ["workbench", "ritual", "combine", "netherite", "inventory"];
const RECIPE_METHODS = ["workbench", "ritual"];
const RECIPE_TYPES = ["shaped", "shapeless"];
// Java 側 RitualEffectRegistry の登録キーと1対1で対応させる(ArsPaper#onEnable)。ここに無い値は
// 保存時に弾かれるので、Java へ新しい効果を追加したらこの配列にも足すこと。
// 2026-07-31 修正: thread_slot_expand が抜けていて「実装済みの効果を書くと保存できない」状態だった。
// 廃止済みの "thread"(スレッド付与の儀式)は 2026-07-25 に Java から消えているので外した。
const EFFECT_TYPES = ["craft", "weather", "flight", "moonfall", "sunrise", "repair",
  "animal_summon", "mob_summon", "enchant_book", "thread_slot_expand", "thread_reroll"];
const PEDESTAL_RE = /^(custom:)?[A-Za-z_][A-Za-z0-9_]*( x[1-9]\d*)?$/;
// 儀式コア周囲の台座リング(チェビシェフ距離2の外周)は物理16台。
// "NAME xN" は台座N台分に展開されるため、合計台数で判定する。
const MAX_PEDESTAL_TOTAL = 16;

function validatePedestalItems(items, prefix, errors) {
  if (items === undefined || items === null) return;
  if (!Array.isArray(items)) { errors.push(`${prefix}.pedestal-items: 文字列配列である必要があります`); return; }
  let total = 0;
  items.forEach((it, i) => {
    if (typeof it !== "string" || !PEDESTAL_RE.test(it)) {
      errors.push(`${prefix}.pedestal-items[${i}]: "NAME" または "NAME xN" 形式(英字/数字/custom:)である必要があります`);
      return;
    }
    const m = it.match(/ x(\d+)$/);
    total += m ? parseInt(m[1], 10) : 1;
  });
  if (total > MAX_PEDESTAL_TOTAL) {
    errors.push(`${prefix}.pedestal-items: 合計${total}台は上限${MAX_PEDESTAL_TOTAL}台(コア周囲の台座リング)を超えています`);
  }
}

function validateSource(source, prefix, errors) {
  if (source === undefined || source === null) return;
  if (!isNonNegInteger(source)) errors.push(`${prefix}.source: 0以上の整数である必要があります`);
}

function validateEffectType(et, prefix, errors) {
  if (et === undefined || et === null) return;
  if (!EFFECT_TYPES.includes(et)) errors.push(`${prefix}.effect-type: ${EFFECT_TYPES.join(" / ")} のいずれかである必要があります`);
}

// gridSize: workbench/ritual等の既定は3×3。inventory(インベントリ内2×2クラフト)は2×2上限。
function validateShape(recipe, prefix, errors, gridSize) {
  const shape = recipe.shape;
  if (shape === undefined || shape === null) return;
  if (!Array.isArray(shape)) { errors.push(`${prefix}.shape: 文字列配列である必要があります`); return; }
  const size = gridSize || 3;
  if (shape.length > size) errors.push(`${prefix}.shape: 最大${size}行までです`);
  const ing = isPlainObject(recipe.ingredients) ? recipe.ingredients : {};
  // Bukkit の ShapedRecipe#shape は全行が同じ長さ(矩形)であることを要求し、違反すると
  // "Crafting recipes must be rectangular" 例外でクラフト不能になる(起動時はWARN1行のみで
  // 気づかれにくい)。2026-07-25の軽装7件事故の再発防止としてeditor側でも矩形を強制する。
  const firstRowLength = typeof shape[0] === "string" ? shape[0].length : null;
  shape.forEach((row, i) => {
    if (typeof row !== "string") { errors.push(`${prefix}.shape[${i}]: 文字列である必要があります`); return; }
    if (row.length > size) errors.push(`${prefix}.shape[${i}]: 各行は${size}文字以内である必要があります`);
    if (firstRowLength !== null && row.length !== firstRowLength) {
      errors.push(`${prefix}.shape[${i}]: 全行が同じ長さ(矩形)である必要があります(1行目は${firstRowLength}文字、この行は${row.length}文字。空スロットは半角スペースで埋めてください)`);
    }
    for (const ch of row) {
      if (ch !== " " && !(ch in ing)) errors.push(`${prefix}.shape[${i}]: 文字 "${ch}" が ingredients に定義されていません`);
    }
  });
}

function validateArsRecipes(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }

  const items = data.items;
  if (items !== undefined && items !== null) {
    if (!isPlainObject(items)) errors.push("items はマップである必要があります");
    else for (const [id, entry] of Object.entries(items)) {
      const prefix = `items.${id}`;
      if (!isPlainObject(entry) || !isPlainObject(entry.recipe)) { errors.push(`${prefix}.recipe: マップである必要があります`); continue; }
      const r = entry.recipe;
      const method = r.method;
      if (method !== undefined && !RECIPE_METHODS.includes(method)) errors.push(`${prefix}.recipe.method: ${RECIPE_METHODS.join(" / ")} のいずれかである必要があります`);
      if (method === "ritual" || r["core-item"] !== undefined || r["pedestal-items"] !== undefined) {
        validatePedestalItems(r["pedestal-items"], `${prefix}.recipe`, errors);
        validateSource(r.source, `${prefix}.recipe`, errors);
        validateEffectType(r["effect-type"], `${prefix}.recipe`, errors);
      } else {
        if (r.type !== undefined && !RECIPE_TYPES.includes(r.type)) errors.push(`${prefix}.recipe.type: ${RECIPE_TYPES.join(" / ")} のいずれかである必要があります`);
        validateShape(r, `${prefix}.recipe`, errors);
      }
    }
  }

  const effects = data.ritual_effects;
  if (effects !== undefined && effects !== null) {
    if (!isPlainObject(effects)) errors.push("ritual_effects はマップである必要があります");
    else for (const [id, entry] of Object.entries(effects)) {
      const prefix = `ritual_effects.${id}`;
      if (!isPlainObject(entry)) { errors.push(`${prefix}: マップである必要があります`); continue; }
      validateEffectType(entry["effect-type"], prefix, errors);
      validatePedestalItems(entry["pedestal-items"], prefix, errors);
      validateSource(entry.source, prefix, errors);
    }
  }
}

// ---- ArsPaper materials.yml / threads.yml (ars-materials / ars-threads) ----
// 儀式レシピ (core-item / pedestal-items / source) の共有検証。result は扱わない。
function validateRitualRecipe(recipe, prefix, errors) {
  if (recipe === undefined || recipe === null) return;
  if (!isPlainObject(recipe)) { errors.push(`${prefix}.recipe: マップである必要があります`); return; }
  if (recipe["core-item"] !== undefined && typeof recipe["core-item"] !== "string") {
    errors.push(`${prefix}.recipe.core-item: 文字列である必要があります`);
  }
  validatePedestalItems(recipe["pedestal-items"], `${prefix}.recipe`, errors);
  validateSource(recipe.source, `${prefix}.recipe`, errors);
}

function validateArsMaterials(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const materials = data.materials;
  if (materials === undefined || materials === null) return;
  if (!isPlainObject(materials)) { errors.push("materials はマップである必要があります"); return; }
  for (const [id, entry] of Object.entries(materials)) {
    const prefix = `materials.${id}`;
    if (!isPlainObject(entry)) { errors.push(`${prefix}: マップである必要があります`); continue; }
    if (entry.base_material !== undefined && typeof entry.base_material !== "string") {
      errors.push(`${prefix}.base_material: 文字列である必要があります`);
    }
    if (entry.custom_model_data !== undefined && entry.custom_model_data !== null && !isNonNegInteger(entry.custom_model_data)) {
      errors.push(`${prefix}.custom_model_data: 0以上の整数である必要があります`);
    }
    if (entry.display_name !== undefined && entry.display_name !== null && typeof entry.display_name !== "string") {
      errors.push(`${prefix}.display_name: 文字列である必要があります`);
    }
    if (entry.name_color !== undefined && entry.name_color !== null && typeof entry.name_color !== "string") {
      errors.push(`${prefix}.name_color: 文字列である必要があります`);
    }
    if (entry.lore !== undefined && entry.lore !== null) {
      if (!Array.isArray(entry.lore)) errors.push(`${prefix}.lore: 文字列配列である必要があります`);
      else entry.lore.forEach((line, i) => { if (typeof line !== "string") errors.push(`${prefix}.lore[${i}]: 文字列である必要があります`); });
    }
    if (entry.enchant_glow !== undefined && entry.enchant_glow !== null && typeof entry.enchant_glow !== "boolean") {
      errors.push(`${prefix}.enchant_glow: 真偽値である必要があります`);
    }
    validateRitualRecipe(entry.recipe, prefix, errors);
  }
}

const THREAD_EFFECT_KEYS = ["regen-bonus", "mana-bonus", "recovery", "cost-reduction", "slots"];

function validateArsThreads(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const threads = data.threads;
  if (threads === undefined || threads === null) return;
  if (!isPlainObject(threads)) { errors.push("threads はマップである必要があります"); return; }
  for (const [id, entry] of Object.entries(threads)) {
    const prefix = `threads.${id}`;
    if (!isPlainObject(entry)) { errors.push(`${prefix}: マップである必要があります`); continue; }
    if (entry.display_name !== undefined && entry.display_name !== null && typeof entry.display_name !== "string") {
      errors.push(`${prefix}.display_name: 文字列である必要があります`);
    }
    for (const k of THREAD_EFFECT_KEYS) {
      if (entry[k] !== undefined && entry[k] !== null && !isNumber(entry[k])) {
        errors.push(`${prefix}.${k}: 数値である必要があります`);
      }
    }
    if (entry.stackable !== undefined && entry.stackable !== null && typeof entry.stackable !== "boolean") {
      errors.push(`${prefix}.stackable: 真偽値である必要があります`);
    }
    if (entry.max !== undefined && entry.max !== null && !isNonNegInteger(entry.max)) {
      errors.push(`${prefix}.max: 0以上の整数である必要があります`);
    }
    validateRitualRecipe(entry.recipe, prefix, errors);
  }
}

// ============================================================
// P4: TF 小型 config 群 + ArsPaper glyphs
// 既知キー・型・enum を検証し、未知キーは温存する (検証は読むだけで data を変えない)。
// ============================================================

const LORE_FORMATS = ["FLAT", "PERCENT", "INTEGER", "SCALAR"];
const ATTR_OPERATIONS = ["ADD_NUMBER", "ADD_SCALAR", "MULTIPLY_SCALAR_1"];
const CATEGORY_KEYS = ["weapon", "armor", "tool", "other"];

// ---- lore.yml (tf-lore) ----
function validateTfLore(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const layout = data.layout;
  if (layout !== undefined && layout !== null) {
    if (!isPlainObject(layout)) errors.push("layout はマップである必要があります");
    else {
      for (const k of ["header", "footer"]) {
        if (layout[k] !== undefined && layout[k] !== null && !Array.isArray(layout[k])) {
          errors.push(`layout.${k}: 配列である必要があります`);
        }
      }
      for (const k of ["line-template", "score-line-template"]) {
        if (layout[k] !== undefined && layout[k] !== null && typeof layout[k] !== "string") {
          errors.push(`layout.${k}: 文字列(MiniMessage)である必要があります`);
        }
      }
      // 高度な色ルール (layout.colors.{fixed,roll}.{positive,negative,chance-positive,chance-negative})
      if (layout.colors !== undefined && layout.colors !== null) {
        if (!isPlainObject(layout.colors)) errors.push("layout.colors: マップである必要があります");
        else {
          for (const group of ["fixed", "roll"]) {
            const g = layout.colors[group];
            if (g === undefined || g === null) continue;
            if (!isPlainObject(g)) { errors.push(`layout.colors.${group}: マップである必要があります`); continue; }
            for (const ck of ["positive", "negative", "chance-positive", "chance-negative"]) {
              if (g[ck] !== undefined && g[ck] !== null && typeof g[ck] !== "string") {
                errors.push(`layout.colors.${group}.${ck}: 文字列(色名)である必要があります`);
              }
            }
          }
        }
      }
    }
  }
  const bind = data.bind;
  if (bind !== undefined && bind !== null) {
    if (!isPlainObject(bind)) errors.push("bind: マップである必要があります");
    else {
      for (const k of ["show-owner", "show-use-requirement"]) {
        if (bind[k] !== undefined && bind[k] !== null && typeof bind[k] !== "boolean") {
          errors.push(`bind.${k}: 真偽値である必要があります`);
        }
      }
      for (const k of ["owner-line", "use-requirement-line"]) {
        if (bind[k] !== undefined && bind[k] !== null && typeof bind[k] !== "string") {
          errors.push(`bind.${k}: 文字列(MiniMessage)である必要があります`);
        }
      }
    }
  }
  // 乗算レイヤ定義 (multiplier-layers: [{id, name, stat}] の配列)。
  // 2026-07 仕様変更: レイヤは基準ステータス(stat)ごとの定義で、stat は必須。
  const layers = data["multiplier-layers"];
  if (layers !== undefined && layers !== null) {
    if (!Array.isArray(layers)) errors.push("multiplier-layers: 配列である必要があります");
    else {
      const seen = new Set();
      layers.forEach((entry, i) => {
        if (!isPlainObject(entry)) { errors.push(`multiplier-layers[${i}]: マップである必要があります`); return; }
        if (!entry.id || typeof entry.id !== "string") {
          errors.push(`multiplier-layers[${i}].id: 必須の文字列です`);
        } else if (seen.has(entry.id)) {
          errors.push(`multiplier-layers[${i}].id: "${entry.id}" が重複しています`);
        } else {
          seen.add(entry.id);
        }
        if (entry.name !== undefined && entry.name !== null && typeof entry.name !== "string") {
          errors.push(`multiplier-layers[${i}].name: 文字列である必要があります`);
        }
        if (!entry.stat || typeof entry.stat !== "string") {
          errors.push(`multiplier-layers[${i}].stat: 必須の文字列です (このレイヤの基準ステータスを選択してください)`);
        }
      });
    }
  }
  const stats = data.stats;
  if (stats !== undefined && stats !== null) {
    if (!isPlainObject(stats)) { errors.push("stats はマップである必要があります"); return; }
    for (const [key, entry] of Object.entries(stats)) {
      if (!isPlainObject(entry)) { errors.push(`stats.${key}: マップである必要があります`); continue; }
      if (entry.format !== undefined && entry.format !== null && !LORE_FORMATS.includes(entry.format)) {
        errors.push(`stats.${key}.format: ${LORE_FORMATS.join(" / ")} のいずれかである必要があります`);
      }
      if (entry.decimals !== undefined && entry.decimals !== null && !isNonNegInteger(entry.decimals)) {
        errors.push(`stats.${key}.decimals: 0以上の整数である必要があります`);
      }
      if (entry.order !== undefined && entry.order !== null && !isInteger(entry.order)) {
        errors.push(`stats.${key}.order: 整数である必要があります`);
      }
      if (entry.category !== undefined && entry.category !== null) {
        // 2026-07-23 の7分類再編 (attack/defense/craft/gathering/utility/ars/other、旧 support は
        // utility へ改名) にこの検証だけ追随していなかったため、UI(tf-lore.js CATEGORY_OPTIONS)が
        // 出す craft/gathering/utility を選ぶと保存が必ず弾かれていた。UI側の選択肢と同一に保つこと。
        const cats = ["attack", "defense", "craft", "gathering", "utility", "ars", "other"];
        if (!cats.includes(String(entry.category))) {
          errors.push(`stats.${key}.category: ${cats.join(" / ")} のいずれかである必要があります`);
        }
      }
      for (const b of ["show-sign", "hide-when-zero"]) {
        if (entry[b] !== undefined && entry[b] !== null && typeof entry[b] !== "boolean") {
          errors.push(`stats.${key}.${b}: 真偽値である必要があります`);
        }
      }
      if (entry.unit !== undefined && entry.unit !== null && typeof entry.unit !== "string") {
        errors.push(`stats.${key}.unit: 文字列である必要があります`);
      }
      validateLoreTrigger(entry.trigger, `stats.${key}.trigger`, errors);
      validateLoreLimits(entry.limits, `stats.${key}.limits`, errors);
    }
  }
}

// 段階1宣言 (trigger:/limits:)。両方とも任意(optional) — 未宣言のstatは引き続き許可される
// (Java側 LoreConfigDeclarationTest の許可リストがラチェットを管理する)。
function validateLoreTrigger(trigger, prefix, errors) {
  if (trigger === undefined || trigger === null) return;
  if (!isPlainObject(trigger)) { errors.push(`${prefix}: マップである必要があります`); return; }
  if (trigger.when !== undefined && trigger.when !== null && !TRIGGER_WHEN.includes(trigger.when)) {
    errors.push(`${prefix}.when: ${TRIGGER_WHEN.join(" / ")} のいずれかである必要があります`);
  }
  if (trigger.sources !== undefined && trigger.sources !== null && !SOURCE_SCOPE.includes(trigger.sources)) {
    errors.push(`${prefix}.sources: ${SOURCE_SCOPE.join(" / ")} のいずれかである必要があります`);
  }
  const applies = trigger["applies-to"];
  if (applies !== undefined && applies !== null) {
    if (!Array.isArray(applies) || applies.length === 0) {
      errors.push(`${prefix}.applies-to: 1個以上の配列である必要があります`);
    } else {
      for (const a of applies) {
        if (!STAT_APPLIES_TO.includes(a)) {
          errors.push(`${prefix}.applies-to: 不正な値 "${a}" (許可: ${STAT_APPLIES_TO.join(" / ")})`);
        }
      }
    }
  }
}

// limits: の閉じた語彙。数値上限フィールドは全て「X」+ 任意の「X-ref」の対で構成される
// (Java側 StatLimits.declaredBounds() と同じ規則)。stacking のみ非数値フィールド。
const LORE_LIMITS_NUMERIC_FIELDS = ["cap", "floor", "min-pieces", "max-distance", "max-duration-ticks"];
const LORE_LIMITS_KNOWN_FIELDS = new Set([
  ...LORE_LIMITS_NUMERIC_FIELDS,
  ...LORE_LIMITS_NUMERIC_FIELDS.map((f) => `${f}-ref`),
  "stacking",
]);

function validateLoreLimits(limits, prefix, errors) {
  if (limits === undefined || limits === null) return;
  if (!isPlainObject(limits)) { errors.push(`${prefix}: マップである必要があります`); return; }

  for (const key of Object.keys(limits)) {
    if (!LORE_LIMITS_KNOWN_FIELDS.has(key)) {
      errors.push(`${prefix}.${key}: 不明なフィールドです (許可: ${[...LORE_LIMITS_KNOWN_FIELDS].join(" / ")})`);
    }
  }

  for (const numKey of LORE_LIMITS_NUMERIC_FIELDS) {
    const value = limits[numKey];
    if (value === undefined || value === null) continue;
    if (numKey === "min-pieces") {
      if (!isNonNegInteger(value)) {
        errors.push(`${prefix}.min-pieces: 0以上の整数である必要があります`);
      }
    } else if (!isNumber(value)) {
      errors.push(`${prefix}.${numKey}: 数値である必要があります`);
    }
  }

  for (const numKey of LORE_LIMITS_NUMERIC_FIELDS) {
    const refKey = `${numKey}-ref`;
    const ref = limits[refKey];
    if (ref === undefined || ref === null) continue;
    // X-ref があって X が無いのはエラー(比較対象が無い)。Java側 LoreConfig#parseLimits と同じ規則。
    if (limits[numKey] === undefined || limits[numKey] === null) {
      errors.push(`${prefix}.${refKey}: ${prefix}.${numKey} が無いと宣言できません(比較対象が必要です)`);
      continue;
    }
    if (typeof ref !== "string" || !ref) {
      errors.push(`${prefix}.${refKey}: 文字列である必要があります`);
      continue;
    }
    // 書式のみ検証する ("<相対path>#<keypath>" または "java:<FQCN>#<CONST>")。実解決は
    // Java側 CapRefResolver + LoreConfigDeclarationTest が担当する(editorはJVMを起動できない)。
    const isJava = ref.startsWith("java:");
    const body = isJava ? ref.slice("java:".length) : ref;
    if (!body.includes("#") || body.split("#")[0] === "" || body.split("#").slice(1).join("#") === "") {
      errors.push(`${prefix}.${refKey}: "${isJava ? "java:<FQCN>" : "<相対path>"}#<${isJava ? "CONST" : "key.path"}>" 形式である必要があります`);
    }
  }

  if (limits.stacking !== undefined && limits.stacking !== null && !STACKING.includes(limits.stacking)) {
    errors.push(`${prefix}.stacking: ${STACKING.join(" / ")} のいずれかである必要があります`);
  }
}

// ---- craft-quality.yml (tf-craft-quality) ----
function validateTfCraftQuality(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const drop = data.drop;
  if (drop !== undefined && drop !== null) {
    if (!isPlainObject(drop)) errors.push("drop はマップである必要があります");
    else {
      if (drop.enabled !== undefined && drop.enabled !== null && typeof drop.enabled !== "boolean") {
        errors.push("drop.enabled: 真偽値(true/false)である必要があります");
      }
      for (const k of ["strength-per-quality", "base-quality"]) {
        if (drop[k] !== undefined && drop[k] !== null && !Number.isInteger(drop[k])) {
          errors.push(`drop.${k}: 整数である必要があります`);
        }
      }
      if (drop.spread !== undefined && drop.spread !== null && !isNumber(drop.spread)) {
        errors.push("drop.spread: 数値(標準偏差σ)である必要があります");
      }
    }
  }
}

// ---- skill-exp.yml (tf-skill-exp) ----
// スキルEXP獲得設定。各セクション(ars-smithing 等)はスカラー値のマップ。
// exp-per-craft は 0以上の数値。将来のスキル追加に備え未知セクションは緩く許容する。
function validateNonNegativeExpNumber(value, path, errors) {
  if (value !== undefined && value !== null && (!isNumber(value) || value < 0)) {
    errors.push(`${path}: 0以上の数値である必要があります`);
  }
}

function validateNonNegativeExpMap(value, path, errors) {
  if (value === undefined || value === null) return;
  if (!isPlainObject(value)) {
    errors.push(`${path}: マップである必要があります`);
    return;
  }
  for (const [key, amount] of Object.entries(value)) {
    validateNonNegativeExpNumber(amount, `${path}.${key}`, errors);
  }
}

function validateKillExp(section, path, baseIsMap, errors) {
  if (section === undefined || section === null) return;
  if (!isPlainObject(section)) {
    errors.push(`${path}: マップである必要があります`);
    return;
  }
  if (section.enabled !== undefined && section.enabled !== null
      && typeof section.enabled !== "boolean") {
    errors.push(`${path}.enabled: 真偽値である必要があります`);
  }
  if (baseIsMap) validateNonNegativeExpMap(section.base, `${path}.base`, errors);
  else validateNonNegativeExpNumber(section.base, `${path}.base`, errors);
  validateNonNegativeExpNumber(section["per-mob-level"], `${path}.per-mob-level`, errors);
  validateNonNegativeExpNumber(section["per-max-health"], `${path}.per-max-health`, errors);
  validateNonNegativeExpMap(
    section["entity-type-multipliers"],
    `${path}.entity-type-multipliers`,
    errors
  );
}

function validateTfSkillExp(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const removedKeys = {
    "ars-magic": ["exp-per-cast", "exp-per-mana"],
    combat: [
      "exp-per-hit", "mode", "damage-scale", "mob-level-scale",
      "same-target-cooldown-seconds", "by-skill"
    ]
  };
  for (const [skill, section] of Object.entries(data)) {
    if (section === undefined || section === null) continue;
    // skill-exp.yml 直下にはスキル別マップだけでなく、戦闘EXP全体へ適用する
    // スカラー設定もある。マップ判定より先に既知スカラーを検証しないと、
    // エディタで正しい実ファイルをそのまま保存しても拒否してしまう。
    if (skill === "dungeon-only-exp") {
      if (typeof section !== "boolean") {
        errors.push("dungeon-only-exp: 真偽値(true/false)である必要があります");
      }
      continue;
    }
    if (skill === "outside-dungeon-exp-rate") {
      if (!isNumber(section) || section < 0) {
        errors.push("outside-dungeon-exp-rate: 0以上の数値である必要があります");
      }
      continue;
    }
    if (!isPlainObject(section)) { errors.push(`${skill}: マップである必要があります`); continue; }
    if (skill === "gathering") {
      const mode = section["exp-mode"];
      if (mode !== undefined && mode !== null
          && !["drop_sum", "block_value", "max"].includes(mode)) {
        errors.push("gathering.exp-mode: ドロップ合計・破壊ブロック基準・大きい方を採用から選択してください");
      }
    }
    for (const key of removedKeys[skill] || []) {
      if (Object.prototype.hasOwnProperty.call(section, key)) {
        errors.push(`${skill}.${key}: 廃止された設定キーです。現行のEXP設定へ移行してください`);
      }
    }
    if (skill === "exp-display") {
      if (section.mode !== undefined && section.mode !== null && typeof section.mode !== "string") {
        errors.push("exp-display.mode: 文字列である必要があります");
      }
      const bs = section["bossbar-seconds"];
      if (bs !== undefined && bs !== null && (!isNumber(bs) || bs < 0)) {
        errors.push("exp-display.bossbar-seconds: 0以上の数値である必要があります");
      }
      const mcb = section["max-concurrent-bossbars"];
      if (mcb !== undefined && mcb !== null && (!isInteger(mcb) || mcb <= 0)) {
        errors.push("exp-display.max-concurrent-bossbars: 正の整数である必要があります");
      }
      continue;
    }
    if (skill === "use-level-scaling") {
      if (section.enabled !== undefined && section.enabled !== null && typeof section.enabled !== "boolean") {
        errors.push("use-level-scaling.enabled: 真偽値である必要があります");
      }
      const maxMult = section["max-multiplier"];
      if (maxMult !== undefined && maxMult !== null && (!isNumber(maxMult) || maxMult < 1)) {
        errors.push("use-level-scaling.max-multiplier: 1以上の数値である必要があります");
      }
      const perLevel = section["per-level"];
      if (perLevel !== undefined && perLevel !== null) {
        if (!isPlainObject(perLevel)) { errors.push("use-level-scaling.per-level: マップである必要があります"); }
        else for (const [k, v] of Object.entries(perLevel)) {
          if (v !== undefined && v !== null && !isNumber(v)) {
            errors.push(`use-level-scaling.per-level.${k}: 数値である必要があります`);
          }
        }
      }
      continue;
    }
    if (skill === "level-up") {
      if (section.chat !== undefined && section.chat !== null && typeof section.chat !== "boolean") {
        errors.push("level-up.chat: 真偽値である必要があります");
      }
      if (section["sound-enabled"] !== undefined && section["sound-enabled"] !== null && typeof section["sound-enabled"] !== "boolean") {
        errors.push("level-up.sound-enabled: 真偽値である必要があります");
      }
      if (section.sound !== undefined && section.sound !== null && typeof section.sound !== "string") {
        errors.push("level-up.sound: 文字列である必要があります");
      }
      const tel = section["title-every-levels"];
      if (tel !== undefined && tel !== null && (!isInteger(tel) || tel <= 0)) {
        errors.push("level-up.title-every-levels: 正の整数である必要があります");
      }
      continue;
    }
    const exp = section["exp-per-craft"];
    if (exp !== undefined && exp !== null && (!isNumber(exp) || exp < 0)) {
      errors.push(`${skill}.exp-per-craft: 0以上の数値である必要があります`);
    }
    // 2026-07-30: 素材別クラフトEXP (smithing.exp-per-material)。
    // キーは Material 名 または custom:<カタログID>、値は 0 以上の数値。
    const perMaterial = section["exp-per-material"];
    if (perMaterial !== undefined && perMaterial !== null) {
      if (!isPlainObject(perMaterial)) {
        errors.push(`${skill}.exp-per-material: マップである必要があります`);
      } else {
        for (const [material, value] of Object.entries(perMaterial)) {
          if (!isNumber(value) || value < 0) {
            errors.push(`${skill}.exp-per-material.${material}: 0以上の数値である必要があります`);
          }
        }
      }
    }
    if (skill === "ars-magic") {
      validateKillExp(section["kill-exp"], "ars-magic.kill-exp", false, errors);
      const blockBreak = section["block-break-exp"];
      if (blockBreak !== undefined && blockBreak !== null) {
        if (!isPlainObject(blockBreak)) {
          errors.push("ars-magic.block-break-exp: マップである必要があります");
        } else {
          if (blockBreak.enabled !== undefined && blockBreak.enabled !== null
              && typeof blockBreak.enabled !== "boolean") {
            errors.push("ars-magic.block-break-exp.enabled: 真偽値である必要があります");
          }
          validateNonNegativeExpNumber(
            blockBreak["source-multiplier"],
            "ars-magic.block-break-exp.source-multiplier",
            errors
          );
        }
      }
    }
    if (skill === "combat") {
      validateKillExp(section["kill-exp"], "combat.kill-exp", true, errors);
    }
  }
}

// ---- mining-gimmick.yml (tf-mining-gimmick) ----
// 最小限・許容的: suspicious-block-respawn.loot-tables の型のみ検証する (他は generic 相当)。
function validateTfMiningGimmick(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const respawn = data["suspicious-block-respawn"];
  if (respawn === undefined || respawn === null) return;
  if (!isPlainObject(respawn)) { errors.push("suspicious-block-respawn: マップである必要があります"); return; }
  const lootTables = respawn["loot-tables"];
  if (lootTables === undefined || lootTables === null) return;
  if (!isPlainObject(lootTables)) { errors.push("suspicious-block-respawn.loot-tables: マップである必要があります"); return; }
  for (const [key, value] of Object.entries(lootTables)) {
    if (typeof value !== "string") {
      errors.push(`suspicious-block-respawn.loot-tables.${key}: 文字列(LootTable名)である必要があります`);
    }
  }
}

// ---- mob-import.yml (tf-mob-import) ----
// 最小限・許容的: unknown-mobs.synthesize の型のみ検証する (他は generic 相当)。
function validateTfMobImport(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const unknownMobs = data["unknown-mobs"];
  if (unknownMobs === undefined || unknownMobs === null) return;
  if (!isPlainObject(unknownMobs)) { errors.push("unknown-mobs: マップである必要があります"); return; }
  if (unknownMobs.synthesize !== undefined && unknownMobs.synthesize !== null && typeof unknownMobs.synthesize !== "boolean") {
    errors.push("unknown-mobs.synthesize: 真偽値である必要があります");
  }
}

// ---- tool-enchants.yml (tf-tool-enchants) ----
function validateTfToolEnchants(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const enchants = data.enchants;
  if (enchants === undefined || enchants === null) return;
  if (!isPlainObject(enchants)) { errors.push("enchants はマップである必要があります"); return; }
  for (const [id, entry] of Object.entries(enchants)) {
    if (!isPlainObject(entry)) { errors.push(`enchants.${id}: マップである必要があります`); continue; }
    if (entry.enchant !== undefined && entry.enchant !== null && typeof entry.enchant !== "string") {
      errors.push(`enchants.${id}.enchant: 文字列(エンチャントキー)である必要があります`);
    }
    const applies = entry["applies-to"];
    if (applies !== undefined && applies !== null) {
      if (!Array.isArray(applies)) errors.push(`enchants.${id}.applies-to: 配列である必要があります`);
      else for (const a of applies) if (!CATEGORY_KEYS.includes(a)) errors.push(`enchants.${id}.applies-to: 不正な値 "${a}" (許可: ${CATEGORY_KEYS.join("/")})`);
    }
    const th = entry["quality-thresholds"];
    if (th !== undefined && th !== null) {
      if (!Array.isArray(th)) errors.push(`enchants.${id}.quality-thresholds: 配列である必要があります`);
      else th.forEach((n, i) => { if (!isNumber(n)) errors.push(`enchants.${id}.quality-thresholds[${i}]: 数値である必要があります`); });
    }
  }
}

// ---- quality.yml (tf-quality) ----
function validateTfQuality(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  if (data["max-quality"] !== undefined && data["max-quality"] !== null && !isNonNegInteger(data["max-quality"])) {
    errors.push("max-quality: 0以上の整数である必要があります");
  }
  if (data["give-default-quality"] !== undefined && data["give-default-quality"] !== null && !isNonNegInteger(data["give-default-quality"])) {
    errors.push("give-default-quality: 0以上の整数である必要があります");
  }
  // 段1(品質ティア)σ + 段2(ロール到達)σ: いずれも 0以上の数値。
  for (const k of ["spread-up", "spread-down", "roll-spread-up", "roll-spread-down"]) {
    if (data[k] !== undefined && data[k] !== null && (!isNumber(data[k]) || data[k] < 0)) {
      errors.push(`${k}: 0以上の数値である必要があります`);
    }
  }
  // ロール中心インセット: 中心modeを両端から押し込む量。範囲[0,0.5)。
  const inset = data["roll-center-inset"];
  if (inset !== undefined && inset !== null && (!isNumber(inset) || inset < 0 || inset >= 0.5)) {
    errors.push("roll-center-inset: [0,0.5)の数値である必要があります");
  }
}

// ---- quality-tiers.yml (tf-quality-tiers) ----
function validateTfQualityTiers(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const tiers = data.tiers;
  if (tiers === undefined || tiers === null) return;
  if (!Array.isArray(tiers)) { errors.push("tiers は配列である必要があります"); return; }
  tiers.forEach((t, i) => {
    if (!isPlainObject(t)) { errors.push(`tiers[${i}]: マップである必要があります`); return; }
    if (t.name !== undefined && t.name !== null && typeof t.name !== "string") errors.push(`tiers[${i}].name: 文字列である必要があります`);
    if (t.color !== undefined && t.color !== null && typeof t.color !== "string") errors.push(`tiers[${i}].color: 文字列である必要があります`);
  });
}

// ---- gacha.yml (tf-gacha) ----
function validateTfGacha(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const tickets = data.tickets;
  if (tickets !== undefined && tickets !== null) {
    if (!isPlainObject(tickets)) { errors.push("tickets はマップである必要があります"); }
    else for (const [id, t] of Object.entries(tickets)) {
      if (!isPlainObject(t)) { errors.push(`tickets.${id}: マップである必要があります`); continue; }
      if (t.pool === undefined || t.pool === null || typeof t.pool !== "string" || !t.pool) {
        errors.push(`tickets.${id}.pool: 参照する景品プールID(文字列)が必要です`);
      }
    }
  }
  const pools = data.pools;
  if (pools !== undefined && pools !== null) {
    if (!isPlainObject(pools)) { errors.push("pools はマップである必要があります"); return; }
    for (const [pid, pool] of Object.entries(pools)) {
      if (!isPlainObject(pool)) { errors.push(`pools.${pid}: マップである必要があります`); continue; }
      const pity = pool.pity;
      if (pity !== undefined && pity !== null) {
        if (!isPlainObject(pity)) { errors.push(`pools.${pid}.pity: マップである必要があります`); }
        else if (pity.threshold !== undefined && pity.threshold !== null
          && (!isInteger(pity.threshold) || pity.threshold < 0)) {
          errors.push(`pools.${pid}.pity.threshold: 0以上の整数である必要があります(0=無効)`);
        }
      }
      const entries = pool.entries;
      if (entries === undefined || entries === null) continue;
      if (!Array.isArray(entries)) { errors.push(`pools.${pid}.entries: 配列である必要があります`); continue; }
      entries.forEach((e, i) => {
        if (!isPlainObject(e)) { errors.push(`pools.${pid}.entries[${i}]: マップである必要があります`); return; }
        if (typeof e.item !== "string" || !e.item) errors.push(`pools.${pid}.entries[${i}].item: itemCatalog ID または Material名(文字列)が必要です`);
        if (e.weight !== undefined && e.weight !== null && (!isInteger(e.weight) || e.weight < 1)) errors.push(`pools.${pid}.entries[${i}].weight: 1以上の整数である必要があります`);
        if (e.amount !== undefined && e.amount !== null && (!isInteger(e.amount) || e.amount < 1)) errors.push(`pools.${pid}.entries[${i}].amount: 1以上の整数である必要があります`);
        if (e["quality-random"] !== undefined && e["quality-random"] !== null && typeof e["quality-random"] !== "boolean") errors.push(`pools.${pid}.entries[${i}].quality-random: 真偽値である必要があります`);
      });
    }
  }
}

// ---- thread-rolls.yml (ars-thread-rolls) ----
// スレッド1個ごとの厳選(主ステ1つ + サブステ0〜4つ)の抽選テーブル。
// Java 側 ThreadRollConfig は「壊れている候補だけ捨てて残りで抽選する」fail-open なので、
// ここでは「その設定のまま保存すると抽選が意図と変わる」ものだけをエラーにする。
function validateArsThreadRolls(data, errors) {
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  if (data.enabled !== undefined && typeof data.enabled !== "boolean") {
    errors.push("enabled: 真偽値である必要があります");
  }
  const rarities = data.rarities;
  if (rarities !== undefined) {
    if (!isPlainObject(rarities)) { errors.push("rarities はマップである必要があります"); }
    else {
      for (const [id, node] of Object.entries(rarities)) {
        if (!isPlainObject(node)) { errors.push(`rarities.${id}: マップである必要があります`); continue; }
        if (!isPositiveInt(node.weight)) errors.push(`rarities.${id}.weight: 1以上の整数である必要があります`);
        if (node.multiplier !== undefined && (!isNumber(node.multiplier) || node.multiplier <= 0)) {
          errors.push(`rarities.${id}.multiplier: 0より大きい数値である必要があります`);
        }
      }
    }
  }
  for (const section of ["main-stats", "sub-stats"]) {
    const pool = data[section];
    if (pool === undefined) continue;
    if (!isPlainObject(pool)) { errors.push(`${section} はマップである必要があります`); continue; }
    for (const [stat, node] of Object.entries(pool)) {
      if (!isPlainObject(node)) { errors.push(`${section}.${stat}: マップである必要があります`); continue; }
      if (!isPositiveInt(node.weight)) errors.push(`${section}.${stat}.weight: 1以上の整数である必要があります`);
      if (!isNumber(node.min)) errors.push(`${section}.${stat}.min: 数値である必要があります`);
      if (!isNumber(node.max)) errors.push(`${section}.${stat}.max: 数値である必要があります`);
      if (isNumber(node.min) && isNumber(node.max) && node.max < node.min) {
        errors.push(`${section}.${stat}: max は min 以上である必要があります`);
      }
      if (isNumber(node.min) && isNumber(node.max) && node.min === 0 && node.max === 0) {
        errors.push(`${section}.${stat}: min/max が両方0だとこの候補は抽選対象から外れます`);
      }
      if (node.percent !== undefined && typeof node.percent !== "boolean") {
        errors.push(`${section}.${stat}.percent: 真偽値である必要があります`);
      }
    }
  }
  const subCount = data["sub-count"];
  if (subCount !== undefined) {
    if (!isPlainObject(subCount)) { errors.push("sub-count はマップである必要があります"); }
    else {
      for (const [count, weight] of Object.entries(subCount)) {
        if (!/^\d+$/.test(String(count))) errors.push(`sub-count: 本数キー "${count}" は0以上の整数である必要があります`);
        if (!isPositiveInt(weight)) errors.push(`sub-count.${count}: 1以上の整数である必要があります`);
      }
    }
  }
}

// ---- thread-sets.yml (ars-thread-sets) ----
function validateArsThreadSets(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const sets = data["thread-sets"];
  if (sets === undefined || sets === null) return;
  if (!isPlainObject(sets)) { errors.push("thread-sets はマップである必要があります"); return; }
  for (const [tname, node] of Object.entries(sets)) {
    if (!isPlainObject(node)) { errors.push(`thread-sets.${tname}: マップである必要があります`); continue; }
    const thresholds = node.thresholds;
    if (thresholds === undefined || thresholds === null) continue;
    if (!isPlainObject(thresholds)) { errors.push(`thread-sets.${tname}.thresholds: マップである必要があります`); continue; }
    for (const [n, statMap] of Object.entries(thresholds)) {
      if (!/^\d+$/.test(String(n)) || Number(n) < 1) errors.push(`thread-sets.${tname}.thresholds: しきい値キー "${n}" は1以上の整数である必要があります`);
      if (!isPlainObject(statMap)) { errors.push(`thread-sets.${tname}.thresholds.${n}: ステのマップである必要があります`); continue; }
      for (const [stat, val] of Object.entries(statMap)) {
        if (!isNumber(val)) errors.push(`thread-sets.${tname}.thresholds.${n}.${stat}: 数値である必要があります`);
      }
    }
  }
}

// ---- attribute-map.yml (tf-attribute-map) ----
function validateTfAttributeMap(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const mappings = data.mappings;
  if (mappings === undefined || mappings === null) return;
  if (!isPlainObject(mappings)) { errors.push("mappings はマップである必要があります"); return; }
  for (const [stat, entry] of Object.entries(mappings)) {
    if (!isPlainObject(entry)) { errors.push(`mappings.${stat}: マップである必要があります`); continue; }
    if (entry.attribute !== undefined && entry.attribute !== null && typeof entry.attribute !== "string") {
      errors.push(`mappings.${stat}.attribute: 文字列である必要があります`);
    }
    if (entry.operation !== undefined && entry.operation !== null && !ATTR_OPERATIONS.includes(entry.operation)) {
      errors.push(`mappings.${stat}.operation: ${ATTR_OPERATIONS.join(" / ")} のいずれかである必要があります`);
    }
    if (entry.scale !== undefined && entry.scale !== null && !isNumber(entry.scale)) {
      errors.push(`mappings.${stat}.scale: 数値である必要があります`);
    }
  }
}

// ---- item-categories.yml (tf-item-categories) ----
function validateTfItemCategories(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const overrides = data.overrides;
  if (overrides === undefined || overrides === null) return;
  if (!isPlainObject(overrides)) { errors.push("overrides はマップである必要があります"); return; }
  for (const [mat, cats] of Object.entries(overrides)) {
    if (!Array.isArray(cats)) { errors.push(`overrides.${mat}: 配列である必要があります`); continue; }
    for (const c of cats) if (!CATEGORY_KEYS.includes(c)) errors.push(`overrides.${mat}: 不正なカテゴリ "${c}" (許可: ${CATEGORY_KEYS.join("/")})`);
  }
}

// ---- glyphs.yml (ars-glyphs) ----
function validateArsGlyphs(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const glyphs = data.glyphs;
  if (glyphs !== undefined && glyphs !== null) {
    if (!isPlainObject(glyphs)) { errors.push("glyphs はマップである必要があります"); }
    else validateArsGlyphsEntries(glyphs, errors);
  }
  validateArsGlyphsExchangeMappings(data, errors);
}

function validateArsGlyphsEntries(glyphs, errors) {
  for (const [id, entry] of Object.entries(glyphs)) {
    if (entry === null) continue;
    if (!isPlainObject(entry)) { errors.push(`glyphs.${id}: マップである必要があります`); continue; }
    if (entry.tier !== undefined && entry.tier !== null && !(isInteger(entry.tier) && entry.tier >= 1 && entry.tier <= 3)) {
      errors.push(`glyphs.${id}.tier: 1〜3 の整数である必要があります`);
    }
    if (entry["mana-cost"] !== undefined && entry["mana-cost"] !== null && !isNumber(entry["mana-cost"])) {
      errors.push(`glyphs.${id}.mana-cost: 数値である必要があります`);
    }
    // display-name/category は editor専用の表示整理用メタデータ (fork実装は無視する)。値のみ型検証する。
    if (entry["display-name"] !== undefined && entry["display-name"] !== null && typeof entry["display-name"] !== "string") {
      errors.push(`glyphs.${id}.display-name: 文字列である必要があります`);
    }
    if (entry.category !== undefined && entry.category !== null && typeof entry.category !== "string") {
      errors.push(`glyphs.${id}.category: 文字列である必要があります`);
    }
    for (const mapKey of ["params", "max-augments"]) {
      if (entry[mapKey] !== undefined && entry[mapKey] !== null && !isPlainObject(entry[mapKey])) {
        errors.push(`glyphs.${id}.${mapKey}: マップである必要があります`);
      }
    }
    const uc = entry["unlock-cost"];
    if (uc !== undefined && uc !== null) {
      if (!isPlainObject(uc)) errors.push(`glyphs.${id}.unlock-cost: マップである必要があります`);
      else {
        if (uc.level !== undefined && uc.level !== null && !isNonNegInteger(uc.level)) {
          errors.push(`glyphs.${id}.unlock-cost.level: 0以上の整数である必要があります`);
        }
        if (uc.materials !== undefined && uc.materials !== null) {
          if (!isPlainObject(uc.materials)) errors.push(`glyphs.${id}.unlock-cost.materials: マップである必要があります`);
          else for (const [m, cnt] of Object.entries(uc.materials)) {
            if (!isNonNegInteger(cnt)) errors.push(`glyphs.${id}.unlock-cost.materials.${m}: 0以上の整数である必要があります`);
          }
        }
      }
    }
  }
}

// exchange_tiers / crush_map / entity_exchange_pairs は glyphs.<id> の有無に関わらず
// (glyphs: が省略されていても) ルート直下の独立構造として検証する。
function validateArsGlyphsExchangeMappings(data, errors) {
  // exchange_tiers: ブロックMaterialのグループ配列。同一ティア内でサイクル変換、
  // Amplify増強で上位ティアの先頭へ変換される。順序(ティア間・ティア内とも)に意味がある。
  const exchangeTiers = data.exchange_tiers;
  if (exchangeTiers !== undefined && exchangeTiers !== null) {
    if (!Array.isArray(exchangeTiers)) {
      errors.push("exchange_tiers: 配列(ティアのリスト)である必要があります");
    } else {
      exchangeTiers.forEach((group, i) => {
        if (!Array.isArray(group) || group.length === 0) {
          errors.push(`exchange_tiers[${i}]: Material名の配列(1個以上)である必要があります`);
          return;
        }
        group.forEach((m, j) => {
          if (typeof m !== "string" || !MATERIAL_RE.test(m)) {
            errors.push(`exchange_tiers[${i}][${j}]: Material名である必要があります`);
          }
        });
      });
    }
  }
  // crush_map: 変換前Material -> 変換後Material(または非ブロックアイテム)のマップ。
  const crushMap = data.crush_map;
  if (crushMap !== undefined && crushMap !== null) {
    if (!isPlainObject(crushMap)) {
      errors.push("crush_map: マップである必要があります");
    } else {
      for (const [before, after] of Object.entries(crushMap)) {
        if (!MATERIAL_RE.test(before)) errors.push(`crush_map.${before}: キーはMaterial名である必要があります`);
        if (typeof after !== "string" || !MATERIAL_RE.test(after)) {
          errors.push(`crush_map.${before}: 変換後はMaterial名である必要があります`);
        }
      }
    }
  }
  // entity_exchange_pairs: EntityTypeのサイクル配列(2要素=双方向ペア、3要素以上=循環)。
  const entityPairs = data.entity_exchange_pairs;
  if (entityPairs !== undefined && entityPairs !== null) {
    if (!Array.isArray(entityPairs)) {
      errors.push("entity_exchange_pairs: 配列(サイクルのリスト)である必要があります");
    } else {
      entityPairs.forEach((cycle, i) => {
        if (!Array.isArray(cycle) || cycle.length < 2) {
          errors.push(`entity_exchange_pairs[${i}]: EntityTypeの配列(2個以上)である必要があります`);
          return;
        }
        cycle.forEach((e, j) => {
          if (typeof e !== "string" || !MATERIAL_RE.test(e)) {
            errors.push(`entity_exchange_pairs[${i}][${j}]: EntityType名である必要があります`);
          }
        });
      });
    }
  }
}

// ---- smithing-gimmick.yml (tf-smithing-gimmick) ----
// 唯一の値: auto-mode-multiplier (ホッパー自動投入時の減衰係数, 0..1)。
// SmithingGimmickConfig#clamp01 と同じ範囲を検証する。
function validateTfSmithingGimmick(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const v = data["auto-mode-multiplier"];
  if (v !== undefined && v !== null && !(isNumber(v) && v >= 0 && v <= 1)) {
    errors.push("auto-mode-multiplier: 0〜1 の数値である必要があります");
  }
}

// ---- spellbooks.yml (ars-spellbooks) ----
function validateArsSpellbooks(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const books = data["spell-books"];
  if (books === undefined || books === null) return;
  if (!Array.isArray(books)) { errors.push("spell-books: 配列である必要があります"); return; }
  books.forEach((b, i) => {
    if (!isPlainObject(b)) { errors.push(`spell-books[${i}]: マップである必要があります`); return; }
    if (b.id !== undefined && b.id !== null && typeof b.id !== "string") {
      errors.push(`spell-books[${i}].id: 文字列である必要があります`);
    }
    if (b["display-name"] !== undefined && b["display-name"] !== null && typeof b["display-name"] !== "string") {
      errors.push(`spell-books[${i}].display-name: 文字列である必要があります`);
    }
    if (b["name-color"] !== undefined && b["name-color"] !== null && typeof b["name-color"] !== "string") {
      errors.push(`spell-books[${i}].name-color: 文字列である必要があります`);
    }
    for (const f of ["max-slots", "max-glyph-tier", "custom-model-data"]) {
      if (b[f] !== undefined && b[f] !== null && !isNonNegInteger(b[f])) {
        errors.push(`spell-books[${i}].${f}: 0以上の整数である必要があります`);
      }
    }
    if (b["upgrade-from"] !== undefined && b["upgrade-from"] !== null && typeof b["upgrade-from"] !== "string") {
      errors.push(`spell-books[${i}].upgrade-from: 文字列である必要があります`);
    }
    if (b.cooldown !== undefined && b.cooldown !== null && !isNumber(b.cooldown)) {
      errors.push(`spell-books[${i}].cooldown: 数値である必要があります`);
    }
    if (b.recipe !== undefined && b.recipe !== null) {
      validateCatalogRecipe(b.recipe, `spell-books[${i}]`, errors);
    }
  });

  const catalysts = data.catalysts;
  if (catalysts === undefined || catalysts === null) return;
  if (!isPlainObject(catalysts)) { errors.push("catalysts: マップである必要があります"); return; }
  for (const [id, entry] of Object.entries(catalysts)) {
    const prefix = `catalysts.${id}`;
    if (!isPlainObject(entry)) { errors.push(`${prefix}: マップである必要があります`); continue; }
    if (!entry.material || typeof entry.material !== "string") {
      errors.push(`${prefix}.material: 必須の文字列です`);
    }
    if (entry["display-name"] !== undefined && entry["display-name"] !== null && typeof entry["display-name"] !== "string") {
      errors.push(`${prefix}.display-name: 文字列である必要があります`);
    }
    if (entry["name-color"] !== undefined && entry["name-color"] !== null && typeof entry["name-color"] !== "string") {
      errors.push(`${prefix}.name-color: 文字列である必要があります`);
    }
    if (entry.color !== undefined && entry.color !== null && typeof entry.color !== "string") {
      errors.push(`${prefix}.color: 文字列である必要があります`);
    }
    if (entry["custom-model-data"] !== undefined && entry["custom-model-data"] !== null && !isNonNegInteger(entry["custom-model-data"])) {
      errors.push(`${prefix}.custom-model-data: 0以上の整数である必要があります`);
    }
    if (entry["bind-type"] !== undefined && entry["bind-type"] !== null && !BIND_TYPES.includes(entry["bind-type"])) {
      errors.push(`${prefix}.bind-type: ${BIND_TYPES.join(" / ")} のいずれかである必要があります`);
    }
    if (entry["max-bind-tier"] !== undefined && entry["max-bind-tier"] !== null && !isInteger(entry["max-bind-tier"])) {
      errors.push(`${prefix}.max-bind-tier: 整数である必要があります`);
    }
    if (entry.cooldown !== undefined && entry.cooldown !== null && !isNumber(entry.cooldown)) {
      errors.push(`${prefix}.cooldown: 数値である必要があります`);
    }
    if (entry.lore !== undefined && entry.lore !== null) {
      if (!Array.isArray(entry.lore)) errors.push(`${prefix}.lore: 文字列配列である必要があります`);
      else entry.lore.forEach((line, li) => { if (typeof line !== "string") errors.push(`${prefix}.lore[${li}]: 文字列である必要があります`); });
    }
    const mcr = entry["mana-cost-reduction"];
    if (mcr !== undefined && mcr !== null) {
      if (!isPlainObject(mcr)) {
        errors.push(`${prefix}.mana-cost-reduction: マップである必要があります`);
      } else {
        for (const f of ["flat", "percent"]) {
          if (mcr[f] !== undefined && mcr[f] !== null && !isNumber(mcr[f])) {
            errors.push(`${prefix}.mana-cost-reduction.${f}: 数値である必要があります`);
          }
        }
      }
    }
    const stats = entry.stats;
    if (stats !== undefined && stats !== null) {
      if (!isPlainObject(stats)) {
        errors.push(`${prefix}.stats: マップである必要があります`);
      } else {
        for (const grp of ["fixed", "per-quality"]) {
          const g = stats[grp];
          if (g === undefined || g === null) continue;
          if (!isPlainObject(g)) { errors.push(`${prefix}.stats.${grp}: マップである必要があります`); continue; }
          for (const [stat, val] of Object.entries(g)) {
            if (!isNumber(val)) errors.push(`${prefix}.stats.${grp}.${stat}: 数値である必要があります`);
          }
        }
        const rnd = stats.random;
        if (rnd !== undefined && rnd !== null) {
          if (!isPlainObject(rnd)) {
            errors.push(`${prefix}.stats.random: マップである必要があります`);
          } else {
            for (const [stat, range] of Object.entries(rnd)) {
              if (!isPlainObject(range) || !isNumber(range.min) || !isNumber(range.max)) {
                errors.push(`${prefix}.stats.random.${stat}: {min, max} の数値が必要です`);
              } else if (range.min > range.max) {
                errors.push(`${prefix}.stats.random.${stat}: min(${range.min}) <= max(${range.max}) が必要です`);
              }
            }
          }
        }
      }
    }
  }
}

// ---- combat/damage.yml (tf-combat-damage) ----
// 寛容な検証: 存在するキーの型が明らかに不正な場合のみエラーにする。
// 旧 attack-stat-keys / defense-stat-keys は2026-07-26にJava側からも撤去済み(CMB-31)。
// 配備済みの古いymlに残っていてもエディタが温存するだけなので検証しない。
function validateTfCombatDamage(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }

  function checkGroup(groupKey, numberFields, boolFields) {
    const group = data[groupKey];
    if (group === undefined || group === null) return;
    if (!isPlainObject(group)) { errors.push(`${groupKey}: マップである必要があります`); return; }
    for (const f of numberFields) {
      if (group[f] !== undefined && group[f] !== null && !isNumber(group[f])) {
        errors.push(`${groupKey}.${f}: 数値である必要があります`);
      }
    }
    for (const f of boolFields) {
      if (group[f] !== undefined && group[f] !== null && typeof group[f] !== "boolean") {
        errors.push(`${groupKey}.${f}: 真偽値である必要があります`);
      }
    }
  }

  checkGroup("physical", ["base-coefficient", "min-component-damage"], []);
  checkGroup("magical", ["base-coefficient", "min-component-damage"], ["scale-with-combat-level"]);
  checkGroup("stat-unification", [], ["offhand-enabled"]);
  checkGroup("weapon-base-formula", ["a", "b"], ["enabled"]);
  checkGroup("level-scaling", ["per-level"], []);
  checkGroup("defense", ["max-mitigation-rate", "max-dodge-chance", "min-rate", "max-rate", "min-flat", "max-flat"], []);
  checkGroup("vanilla-armor", ["defense-rate-per-point", "defense-rate-max", "armor-strength-per-point"], []);

  const bleed = data.bleed;
  if (bleed !== undefined && bleed !== null) {
    if (!isPlainObject(bleed)) { errors.push("bleed: マップである必要があります"); }
    else {
      for (const f of ["tick-interval-ticks", "ticks"]) {
        if (bleed[f] !== undefined && bleed[f] !== null && !isInteger(bleed[f])) {
          errors.push(`bleed.${f}: 整数である必要があります`);
        }
      }
    }
  }

  // 範囲サニティ: base-coefficient は0以上を期待 (明らかに不正な値のみ弾く)。
  for (const groupKey of ["physical", "magical"]) {
    const group = data[groupKey];
    if (isPlainObject(group) && isNumber(group["base-coefficient"]) && group["base-coefficient"] < 0) {
      errors.push(`${groupKey}.base-coefficient: 0以上の数値である必要があります`);
    }
  }
}

// ---- combat/mob-types.yml (tf-mob-types) ----
const MOB_ENTITY_TYPE_RE = /^[A-Z0-9_]+$/;
const MOB_DEFENSE_FIELDS_01 = ["defense-rate", "resistance", "damage-reduction"];

function validateMobDefenseBlock(block, prefix, errors) {
  if (block === undefined || block === null) return;
  if (!isPlainObject(block)) { errors.push(`${prefix}: マップである必要があります`); return; }
  for (const f of MOB_DEFENSE_FIELDS_01) {
    if (block[f] !== undefined && block[f] !== null && (!isNumber(block[f]) || block[f] < 0 || block[f] > 1)) {
      errors.push(`${prefix}.${f}: 0.0〜1.0の数値である必要があります`);
    }
  }
  if (block["flat-defense"] !== undefined && block["flat-defense"] !== null && (!isNumber(block["flat-defense"]) || block["flat-defense"] < 0)) {
    errors.push(`${prefix}.flat-defense: 0以上の数値である必要があります`);
  }
}

function validateMobDrops(drops, prefix, errors) {
  if (drops === undefined || drops === null) return;
  if (!Array.isArray(drops)) { errors.push(`${prefix}.drops: 配列である必要があります`); return; }
  drops.forEach((d, i) => {
    const p = `${prefix}.drops[${i}]`;
    if (!isPlainObject(d)) { errors.push(`${p}: マップである必要があります`); return; }
    if (typeof d.material !== "string" || !d.material) errors.push(`${p}.material: 必須の文字列(Material名)です`);
    if (!isNumber(d.chance) || d.chance < 0 || d.chance > 1) errors.push(`${p}.chance: 0.0〜1.0の数値である必要があります`);
    if (!isNonNegInteger(d.min)) errors.push(`${p}.min: 0以上の整数である必要があります`);
    if (!isNonNegInteger(d.max)) errors.push(`${p}.max: 0以上の整数である必要があります`);
    if (isNonNegInteger(d.min) && isNonNegInteger(d.max) && d.min > d.max) {
      errors.push(`${p}: min(${d.min}) <= max(${d.max}) が必要です`);
    }
    if (d.quality !== undefined && d.quality !== null && !isInteger(d.quality)) {
      errors.push(`${p}.quality: 整数である必要があります`);
    }
  });
}

function validateMobCoeffDefenseBlock(block, prefix, errors) {
  if (block === undefined || block === null) return;
  if (!isPlainObject(block)) { errors.push(`${prefix}: マップである必要があります`); return; }
  // 係数は加算デルタなので 0〜1 制約は掛からない（負も可）。
  for (const f of [...MOB_DEFENSE_FIELDS_01, "flat-defense"]) {
    if (block[f] !== undefined && block[f] !== null && !isNumber(block[f])) {
      errors.push(`${prefix}.${f}: 数値である必要があります`);
    }
  }
}

function validateMobLevelCoefficients(block, prefix, errors) {
  if (block === undefined || block === null) return;
  if (!isPlainObject(block)) { errors.push(`${prefix}: マップである必要があります`); return; }
  for (const f of ["max-health", "armor-strength"]) {
    if (block[f] !== undefined && block[f] !== null && !isNumber(block[f])) {
      errors.push(`${prefix}.${f}: 数値である必要があります`);
    }
  }
  validateMobCoeffDefenseBlock(block.physical, `${prefix}.physical`, errors);
  validateMobCoeffDefenseBlock(block.magical, `${prefix}.magical`, errors);
}

function validateArsItemLookEntry(entry, prefix, errors) {
  if (!isPlainObject(entry)) {
    errors.push(`${prefix}: マップである必要があります`);
    return;
  }
  if (entry.material !== undefined && entry.material !== null && typeof entry.material !== "string") {
    errors.push(`${prefix}.material: 文字列である必要があります`);
  }
  if (entry["display-name"] !== undefined && entry["display-name"] !== null
      && typeof entry["display-name"] !== "string") {
    errors.push(`${prefix}.display-name: 文字列である必要があります`);
  }
  if (entry["custom-model-data"] !== undefined && entry["custom-model-data"] !== null
      && !isNonNegInteger(entry["custom-model-data"])) {
    errors.push(`${prefix}.custom-model-data: 0以上の整数である必要があります`);
  }
  if (entry.lore !== undefined && entry.lore !== null) {
    if (!Array.isArray(entry.lore)) {
      errors.push(`${prefix}.lore: 配列である必要があります`);
    } else {
      entry.lore.forEach((line, i) => {
        if (typeof line !== "string") errors.push(`${prefix}.lore[${i}]: 文字列である必要があります`);
      });
    }
  }
  if (entry.recipe !== undefined && entry.recipe !== null) {
    validateCatalogRecipe(entry.recipe, prefix, errors);
  }
}

function validateArsSourceJars(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const jars = data.jars;
  if (jars === undefined || jars === null) return;
  if (!isPlainObject(jars)) { errors.push("jars はマップである必要があります"); return; }
  for (const [id, entry] of Object.entries(jars)) {
    const prefix = `jars.${id}`;
    validateArsItemLookEntry(entry, prefix, errors);
    if (isPlainObject(entry) && entry.capacity !== undefined && entry.capacity !== null
        && !isNumber(entry.capacity)) {
      errors.push(`${prefix}.capacity: 数値である必要があります (-1で無限)`);
    }
  }
}

function validateArsMaterialValueMap(block, prefix, errors) {
  if (block === undefined || block === null) return;
  if (!isPlainObject(block)) { errors.push(`${prefix}: マップである必要があります`); return; }
  const mats = block.materials;
  if (mats === undefined || mats === null) return;
  if (!isPlainObject(mats)) { errors.push(`${prefix}.materials: マップである必要があります`); return; }
  for (const [mat, val] of Object.entries(mats)) {
    if (typeof mat !== "string" || !mat.trim()) {
      errors.push(`${prefix}.materials: Material キーは非空文字列である必要があります`);
    }
    if (!isNumber(val) || val < 0) {
      errors.push(`${prefix}.materials.${mat}: 0以上の数値である必要があります`);
    }
  }
}

function validateArsSourceLinks(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  for (const key of ["volcanic", "mycelial", "alchemical"]) {
    validateArsMaterialValueMap(data[key], key, errors);
  }
  const items = data.items;
  if (items === undefined || items === null) return;
  if (!isPlainObject(items)) { errors.push("items はマップである必要があります"); return; }
  for (const [id, entry] of Object.entries(items)) {
    validateArsItemLookEntry(entry, `items.${id}`, errors);
  }
}

function validateTfMobTypes(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  if (data.defaults !== undefined && data.defaults !== null) {
    if (!isPlainObject(data.defaults)) {
      errors.push("defaults: マップである必要があります");
    } else {
      const d = data.defaults;
      if (d.level !== undefined && d.level !== null && !isNonNegInteger(d.level)) {
        errors.push("defaults.level: 0以上の整数である必要があります");
      }
      if (d["coordinate-coefficient"] !== undefined && d["coordinate-coefficient"] !== null
          && !isNumber(d["coordinate-coefficient"])) {
        errors.push("defaults.coordinate-coefficient: 数値である必要があります");
      }
      if (d["max-health"] !== undefined && d["max-health"] !== null
          && (!isNumber(d["max-health"]) || d["max-health"] <= 0)) {
        errors.push("defaults.max-health: 0より大きい数値である必要があります");
      }
      if (d["armor-strength"] !== undefined && d["armor-strength"] !== null
          && (!isNumber(d["armor-strength"]) || d["armor-strength"] < 0)) {
        errors.push("defaults.armor-strength: 0以上の数値である必要があります");
      }
      validateMobDefenseBlock(d.physical, "defaults.physical", errors);
      validateMobDefenseBlock(d.magical, "defaults.magical", errors);
      validateMobLevelCoefficients(d["level-coefficients"], "defaults.level-coefficients", errors);
    }
  }
  const mobTypes = data["mob-types"];
  if (mobTypes === undefined || mobTypes === null) return;
  if (!isPlainObject(mobTypes)) { errors.push("mob-types はマップである必要があります"); return; }
  for (const [entityType, entry] of Object.entries(mobTypes)) {
    const prefix = `mob-types.${entityType}`;
    if (!MOB_ENTITY_TYPE_RE.test(entityType)) {
      errors.push(`${prefix}: キーは大文字英数字とアンダースコアの EntityType 名である必要があります`);
    }
    if (!isPlainObject(entry)) { errors.push(`${prefix}: マップである必要があります`); continue; }
    if (entry.level !== undefined && entry.level !== null && !isNonNegInteger(entry.level)) {
      errors.push(`${prefix}.level: 0以上の整数である必要があります`);
    }
    if (entry["coordinate-coefficient"] !== undefined && entry["coordinate-coefficient"] !== null && !isNumber(entry["coordinate-coefficient"])) {
      errors.push(`${prefix}.coordinate-coefficient: 数値である必要があります`);
    }
    if (entry["max-health"] !== undefined && entry["max-health"] !== null
        && (!isNumber(entry["max-health"]) || entry["max-health"] <= 0)) {
      errors.push(`${prefix}.max-health: 0より大きい数値である必要があります`);
    }
    if (entry["armor-strength"] !== undefined && entry["armor-strength"] !== null && (!isNumber(entry["armor-strength"]) || entry["armor-strength"] < 0)) {
      errors.push(`${prefix}.armor-strength: 0以上の数値である必要があります`);
    }
    validateMobDefenseBlock(entry.physical, `${prefix}.physical`, errors);
    validateMobDefenseBlock(entry.magical, `${prefix}.magical`, errors);
    validateMobLevelCoefficients(entry["level-coefficients"], `${prefix}.level-coefficients`, errors);
    validateMobDrops(entry.drops, prefix, errors);
  }
}

// ---- combat/mob-level-table.yml (tf-mob-level-table) ----
// 2026-07-26: 対象モブ絞り込み(mobs = EntityType / mob-ids = EliteMobsモブid)。
// 帯そのものと add-drops の各エントリで同じ形なので共通化する。両方を指定した場合は AND。
function validateMobTargetFilter(host, prefix, errors) {
  if (host.mobs !== undefined && host.mobs !== null) {
    if (!Array.isArray(host.mobs)) {
      errors.push(`${prefix}.mobs: 配列である必要があります`);
    } else {
      host.mobs.forEach((mob, k) => {
        if (typeof mob !== "string" || !/^[A-Z0-9_]+$/.test(mob)) {
          errors.push(`${prefix}.mobs[${k}]: EntityType名(大文字英数字/アンダースコア)である必要があります`);
        }
      });
    }
  }
  if (host["mob-ids"] !== undefined && host["mob-ids"] !== null) {
    if (!Array.isArray(host["mob-ids"])) {
      errors.push(`${prefix}.mob-ids: 配列である必要があります`);
    } else {
      host["mob-ids"].forEach((id, k) => {
        // Java側 MobIdNormalizer が .yml を除去し残りのドットを _ にするので、拡張子付きも許容する。
        if (typeof id !== "string" || !/^[A-Za-z0-9_.-]+$/.test(id)) {
          errors.push(`${prefix}.mob-ids[${k}]: モブid(英数字/_/-/.)である必要があります`);
        }
      });
    }
  }
}

function validateTfMobLevelTable(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  if (data["dungeon-only"] !== undefined && data["dungeon-only"] !== null && typeof data["dungeon-only"] !== "boolean") {
    errors.push("dungeon-only: 真偽値である必要があります");
  }
  // 2026-07-27 牧場対策: 帯(tiers)とは独立したトップレベルの EntityType 一覧。
  // TrinityForgeの戦闘スキルEXP(武器命中/防具被弾)だけを止める — バニラEXPオーブと魔法は対象外。
  // mobs:/mob-ids: と同じ「EntityType名(大文字英数字/アンダースコア)」語彙を使う。
  if (data["no-skill-exp-mobs"] !== undefined && data["no-skill-exp-mobs"] !== null) {
    if (!Array.isArray(data["no-skill-exp-mobs"])) {
      errors.push("no-skill-exp-mobs: 配列である必要があります");
    } else {
      data["no-skill-exp-mobs"].forEach((mob, k) => {
        if (typeof mob !== "string" || !/^[A-Z0-9_]+$/.test(mob)) {
          errors.push(`no-skill-exp-mobs[${k}]: EntityType名(大文字英数字/アンダースコア)である必要があります`);
        }
      });
    }
  }
  const tiers = data.tiers;
  if (tiers === undefined || tiers === null) return;
  if (!Array.isArray(tiers)) { errors.push("tiers: 配列である必要があります"); return; }
  const seenMinLevels = new Set();
  tiers.forEach((tier, i) => {
    const prefix = `tiers[${i}]`;
    if (!isPlainObject(tier)) { errors.push(`${prefix}: マップである必要があります`); return; }
    if (!isNonNegInteger(tier["min-level"])) {
      errors.push(`${prefix}.min-level: 0以上の整数である必要があります`);
    } else if (seenMinLevels.has(tier["min-level"])) {
      errors.push(`${prefix}.min-level: ${tier["min-level"]} は他の帯と重複しています`);
    } else {
      seenMinLevels.add(tier["min-level"]);
    }
    if (tier["remove-drops"] !== undefined && tier["remove-drops"] !== null) {
      if (!Array.isArray(tier["remove-drops"])) {
        errors.push(`${prefix}.remove-drops: 配列である必要があります`);
      } else {
        tier["remove-drops"].forEach((m, j) => {
          if (typeof m !== "string" || !m) errors.push(`${prefix}.remove-drops[${j}]: 必須の文字列(Material名)です`);
        });
      }
    }
    if (tier["add-drops"] !== undefined && tier["add-drops"] !== null) {
      if (!Array.isArray(tier["add-drops"])) {
        errors.push(`${prefix}.add-drops: 配列である必要があります`);
      } else {
        tier["add-drops"].forEach((d, j) => {
          const p = `${prefix}.add-drops[${j}]`;
          if (!isPlainObject(d)) { errors.push(`${p}: マップである必要があります`); return; }
          // 2026-07-25 §2-B: Material名 または custom:<カタログID> を受け付ける(materials[]バリデーション
          // と同じ語彙、1562-1565行目参照)。
          if (typeof d.material !== "string" || !d.material
              || (!/^[A-Z0-9_]+$/.test(d.material) && !/^custom:[a-z0-9_]+$/i.test(d.material))) {
            errors.push(`${p}.material: Material名または custom:<カタログID> である必要があります`);
          }
          if (!isNumber(d.chance) || d.chance < 0 || d.chance > 1) errors.push(`${p}.chance: 0.0〜1.0の数値である必要があります`);
          if (!isNonNegInteger(d.min)) errors.push(`${p}.min: 0以上の整数である必要があります`);
          if (!isNonNegInteger(d.max)) errors.push(`${p}.max: 0以上の整数である必要があります`);
          if (isNonNegInteger(d.min) && isNonNegInteger(d.max) && d.min > d.max) {
            errors.push(`${p}: min(${d.min}) <= max(${d.max}) が必要です`);
          }
          // 2026-07-25 §2-A: mobs は省略可。2026-07-26: mob-ids も同様(両方指定で AND)。
          validateMobTargetFilter(d, p, errors);
        });
      }
    }
    if (tier["vanilla-exp"] !== undefined && tier["vanilla-exp"] !== null && !isNonNegInteger(tier["vanilla-exp"])) {
      errors.push(`${prefix}.vanilla-exp: 0以上の整数である必要があります`);
    }
    // 2026-07-26: 帯そのものにも対象モブ絞り込みを置ける(この帯の全効果がそのモブだけに当たる)。
    validateMobTargetFilter(tier, prefix, errors);
  });
}

// ---- combat/mob-overrides.yml (tf-mob-overrides) 2026-07-26新設 ----
// overrides.<worldName|default>.mobs.<mobId>: { stats: {...}, drops: [...] }
const MOB_OVERRIDE_DEFENSE_FIELDS = ["defense-rate", "resistance", "damage-reduction", "flat-defense"];
const MOB_OVERRIDE_ATTACK_FIELDS = [
  "attack-power", "flat-bonus-damage", "percent-bonus-damage",
  "crit-chance", "crit-damage", "penetration", "damage-modifier", "fixed-damage"
];
function validateMobOverrideStats(stats, prefix, errors) {
  if (stats === undefined || stats === null) return;
  if (!isPlainObject(stats)) { errors.push(`${prefix}.stats: マップである必要があります`); return; }
  if (stats.level !== undefined && stats.level !== null && !isNonNegInteger(stats.level)) {
    errors.push(`${prefix}.stats.level: 0以上の整数である必要があります`);
  }
  if (stats["max-health"] !== undefined && stats["max-health"] !== null && !isNumber(stats["max-health"])) {
    errors.push(`${prefix}.stats.max-health: 数値である必要があります`);
  }
  if (stats["armor-strength"] !== undefined && stats["armor-strength"] !== null && !isNumber(stats["armor-strength"])) {
    errors.push(`${prefix}.stats.armor-strength: 数値である必要があります`);
  }
  for (const comp of ["physical", "magical"]) {
    const block = stats[comp];
    if (block === undefined || block === null) continue;
    if (!isPlainObject(block)) { errors.push(`${prefix}.stats.${comp}: マップである必要があります`); continue; }
    for (const field of MOB_OVERRIDE_DEFENSE_FIELDS) {
      if (block[field] !== undefined && block[field] !== null && !isNumber(block[field])) {
        errors.push(`${prefix}.stats.${comp}.${field}: 数値である必要があります`);
      }
    }
  }
  const attack = stats.attack;
  if (attack !== undefined && attack !== null) {
    if (!isPlainObject(attack)) { errors.push(`${prefix}.stats.attack: マップである必要があります`); }
    else {
      for (const field of MOB_OVERRIDE_ATTACK_FIELDS) {
        if (attack[field] !== undefined && attack[field] !== null && !isNumber(attack[field])) {
          errors.push(`${prefix}.stats.attack.${field}: 数値である必要があります`);
        }
      }
    }
  }
}
// vanilla-exp: モブごとのレベル依存EXP式。数値単体(レベル非依存の固定量)か
// { base, per-level, growth, growth-interval } のランプ (combat/mob-import.yml と同じ形)。
const MOB_OVERRIDE_EXP_RAMP_FIELDS = ["base", "per-level", "growth", "growth-interval"];
function validateMobOverrideVanillaExp(exp, prefix, errors) {
  if (exp === undefined || exp === null) return;
  if (isNumber(exp)) {
    if (exp < 0) errors.push(`${prefix}.vanilla-exp: 0以上の数値である必要があります`);
    return;
  }
  if (!isPlainObject(exp)) {
    errors.push(`${prefix}.vanilla-exp: 数値、または { base, per-level, growth, growth-interval } の`
      + `マップである必要があります`);
    return;
  }
  for (const key of Object.keys(exp)) {
    if (!MOB_OVERRIDE_EXP_RAMP_FIELDS.includes(key)) {
      errors.push(`${prefix}.vanilla-exp.${key}: 未知のキーです`
        + ` (${MOB_OVERRIDE_EXP_RAMP_FIELDS.join(" / ")} のみ)`);
    }
  }
  for (const key of MOB_OVERRIDE_EXP_RAMP_FIELDS) {
    const v = exp[key];
    if (v === undefined || v === null) continue;
    if (!isNumber(v)) { errors.push(`${prefix}.vanilla-exp.${key}: 数値である必要があります`); continue; }
    if (key === "growth" && v < 0) errors.push(`${prefix}.vanilla-exp.growth: 0以上の数値である必要があります`);
    if (key === "growth-interval" && !(v > 0)) {
      errors.push(`${prefix}.vanilla-exp.growth-interval: 0より大きい数値である必要があります`);
    }
  }
}
function validateMobOverrideDrops(drops, prefix, errors) {
  if (drops === undefined || drops === null) return;
  if (!Array.isArray(drops)) { errors.push(`${prefix}.drops: 配列である必要があります`); return; }
  drops.forEach((d, j) => {
    const p = `${prefix}.drops[${j}]`;
    if (!isPlainObject(d)) { errors.push(`${p}: マップである必要があります`); return; }
    if (typeof d.item !== "string" || !d.item
        || (!/^[A-Z0-9_]+$/.test(d.item) && !/^custom:[a-z0-9_]+$/i.test(d.item))) {
      errors.push(`${p}.item: Material名または custom:<カタログID> である必要があります`);
    }
    if (!isNumber(d.chance) || d.chance < 0 || d.chance > 1) errors.push(`${p}.chance: 0.0〜1.0の数値である必要があります`);
    if (!isNonNegInteger(d.min)) errors.push(`${p}.min: 0以上の整数である必要があります`);
    if (!isNonNegInteger(d.max)) errors.push(`${p}.max: 0以上の整数である必要があります`);
    if (isNonNegInteger(d.min) && isNonNegInteger(d.max) && d.min > d.max) {
      errors.push(`${p}: min(${d.min}) <= max(${d.max}) が必要です`);
    }
  });
}
// display-name: ダンジョン/モブの日本語表示名。省略可、指定時は非空文字列。
// Java側 MobOverridesConfig#trimToNull と揃え、空白のみは「未設定」ではなく誤りとして拾う。
function validateDisplayName(host, prefix, errors) {
  const name = host["display-name"];
  if (name === undefined || name === null) return;
  if (typeof name !== "string" || name.trim() === "") {
    errors.push(`${prefix}.display-name: 空でない文字列である必要があります(未設定なら行ごと削除)`);
  }
}
// level-cutoff: レベル差による足きり (2026-07-27新設)。scope単位・mob単位どちらも同じ形:
// { over-level: { threshold, exp-rate, drop-rate }, under-level: { item-threshold } }。
// Java側 MobOverridesConfig#parseLevelCutoff / nullableValidatedRate と揃える: threshold系は
// 整数であれば負値も許容(「無効」の正当な表現)、exp-rate/drop-rateは -1 または [0.0, 1.0] のみ有効。
function validateMobLevelCutoffRate(value, prefix, errors) {
  if (value === undefined || value === null) return;
  if (!isNumber(value) || !(value === -1 || (value >= 0 && value <= 1))) {
    errors.push(`${prefix}: -1、または 0.0〜1.0 の数値である必要があります`);
  }
}
function validateMobLevelCutoffThreshold(value, prefix, errors) {
  if (value === undefined || value === null) return;
  if (!Number.isInteger(value)) {
    errors.push(`${prefix}: 整数である必要があります(未設定または負値で無効)`);
  }
}
function validateMobLevelCutoff(cutoff, prefix, errors) {
  if (cutoff === undefined || cutoff === null) return;
  if (!isPlainObject(cutoff)) { errors.push(`${prefix}.level-cutoff: マップである必要があります`); return; }
  const over = cutoff["over-level"];
  if (over !== undefined && over !== null) {
    if (!isPlainObject(over)) {
      errors.push(`${prefix}.level-cutoff.over-level: マップである必要があります`);
    } else {
      validateMobLevelCutoffThreshold(over.threshold, `${prefix}.level-cutoff.over-level.threshold`, errors);
      validateMobLevelCutoffRate(over["exp-rate"], `${prefix}.level-cutoff.over-level.exp-rate`, errors);
      validateMobLevelCutoffRate(over["drop-rate"], `${prefix}.level-cutoff.over-level.drop-rate`, errors);
    }
  }
  const under = cutoff["under-level"];
  if (under !== undefined && under !== null) {
    if (!isPlainObject(under)) {
      errors.push(`${prefix}.level-cutoff.under-level: マップである必要があります`);
    } else {
      validateMobLevelCutoffThreshold(under["item-threshold"],
        `${prefix}.level-cutoff.under-level.item-threshold`, errors);
    }
  }
}
function validateTfMobOverrides(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const overrides = data.overrides;
  if (overrides === undefined || overrides === null) return;
  if (!isPlainObject(overrides)) { errors.push("overrides: マップである必要があります"); return; }
  for (const scopeName of Object.keys(overrides)) {
    const scope = overrides[scopeName];
    if (scope === undefined || scope === null) continue;
    const scopePrefix = `overrides.${scopeName}`;
    if (!isPlainObject(scope)) { errors.push(`${scopePrefix}: マップである必要があります`); continue; }
    validateDisplayName(scope, scopePrefix, errors);
    validateMobLevelCutoff(scope["level-cutoff"], scopePrefix, errors);
    const mobs = scope.mobs;
    if (mobs === undefined || mobs === null) continue;
    if (!isPlainObject(mobs)) { errors.push(`${scopePrefix}.mobs: マップである必要があります`); continue; }
    for (const mobId of Object.keys(mobs)) {
      const entry = mobs[mobId];
      const prefix = `${scopePrefix}.mobs.${mobId}`;
      if (entry === undefined || entry === null) continue;
      if (!isPlainObject(entry)) { errors.push(`${prefix}: マップである必要があります`); continue; }
      validateDisplayName(entry, prefix, errors);
      validateMobOverrideStats(entry.stats, prefix, errors);
      validateMobOverrideDrops(entry.drops, prefix, errors);
      validateMobOverrideVanillaExp(entry["vanilla-exp"], prefix, errors);
      validateMobLevelCutoff(entry["level-cutoff"], prefix, errors);
      validateMobAbilityRefs(entry.abilities, prefix, errors);
    }
  }
}

/**
 * mob-overrides の abilities: は combat/mob-abilities.yml のテンプレートID列。
 * ここでは「文字列の配列で、IDの形が正しいか」だけを見る -- 実在チェックをしないのは、
 * Java 側もロード順に依存しない作りにしてあり(未定義IDは発動時に読み飛ばす)、
 * editor が別ファイルの内容に依存すると片方だけ保存したときに保存できなくなるため。
 */
function validateMobAbilityRefs(abilities, prefix, errors) {
  if (abilities === undefined || abilities === null) return;
  if (!Array.isArray(abilities)) {
    errors.push(`${prefix}.abilities: 配列である必要があります`);
    return;
  }
  abilities.forEach((v, i) => {
    if (typeof v !== "string" || !v.trim()) {
      errors.push(`${prefix}.abilities[${i}]: 特殊攻撃テンプレートID(文字列)である必要があります`);
    } else if (!/^[a-z0-9_]+$/.test(v.trim().toLowerCase())) {
      errors.push(`${prefix}.abilities[${i}]: '${v}' はIDとして不正です (半角英小文字・数字・アンダースコアのみ)`);
    }
  });
}

// ---- combat/mob-abilities.yml (tf-mob-abilities) 2026-07-31新設 ----
const MOB_ABILITY_TYPES = ["ground_slam", "projectile_volley", "charge", "aura",
  "teleport_strike", "beam", "summon"];

/** Java の MobAbility が clamp する範囲。editor だけ広いと「保存できたのに実挙動が違う」になる。 */
const MOB_ABILITY_RANGES = {
  "damage-percent": [0, 100],
  "cooldown-seconds": [0.5, 600],
  "chance": [0, 1],
  "range": [1, 64],
  "radius": [0, 32],
  "count": [0, 64],
  "spread-degrees": [0, 360],
  "duration-seconds": [0, 60],
  "knockback": [0, 5],
  "particle-count": [0, 500]
};

function validateTfMobAbilities(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  if (data.enabled !== undefined && typeof data.enabled !== "boolean") {
    errors.push("enabled: 真偽値である必要があります");
  }
  if (data["check-interval-ticks"] !== undefined) {
    const interval = data["check-interval-ticks"];
    if (!Number.isInteger(interval) || interval < 5 || interval > 200) {
      errors.push("check-interval-ticks: 5〜200 の整数である必要があります (Java 側もこの範囲に丸めます)");
    }
  }
  const abilities = data.abilities;
  if (abilities === undefined || abilities === null) return;
  if (!isPlainObject(abilities)) { errors.push("abilities: マップである必要があります"); return; }
  for (const [id, entry] of Object.entries(abilities)) {
    const prefix = `abilities.${id}`;
    if (!/^[a-zA-Z0-9_]+$/.test(id)) {
      errors.push(`${prefix}: IDは半角英数字とアンダースコアのみ使用できます`);
    }
    if (!isPlainObject(entry)) { errors.push(`${prefix}: マップである必要があります`); continue; }
    if (!MOB_ABILITY_TYPES.includes(entry.type)) {
      errors.push(`${prefix}.type: ${MOB_ABILITY_TYPES.join(" / ")} のいずれかである必要があります`);
    }
    if (entry["damage-type"] !== undefined
        && !["physical", "magical"].includes(String(entry["damage-type"]).toLowerCase())) {
      errors.push(`${prefix}.damage-type: physical / magical のいずれかである必要があります`);
    }
    for (const [key, bounds] of Object.entries(MOB_ABILITY_RANGES)) {
      const value = entry[key];
      if (value === undefined || value === null) continue;
      if (typeof value !== "number" || !Number.isFinite(value) || value < bounds[0] || value > bounds[1]) {
        errors.push(`${prefix}.${key}: ${bounds[0]}〜${bounds[1]} の数値である必要があります`);
      }
    }
    for (const key of ["display-name", "projectile", "summon-type", "particle", "sound"]) {
      if (entry[key] !== undefined && entry[key] !== null && typeof entry[key] !== "string") {
        errors.push(`${prefix}.${key}: 文字列である必要があります`);
      }
    }
    // 型ごとの必須項目。空欄のまま保存すると Java 側は「発動しなかった」扱いで黙って何もしない。
    if (entry.type === "projectile_volley" && !String(entry.projectile || "").trim()) {
      errors.push(`${prefix}.projectile: projectile_volley では投射物(EntityType)の指定が必須です`);
    }
    if (entry.type === "summon" && !String(entry["summon-type"] || "").trim()) {
      errors.push(`${prefix}.summon-type: summon では召喚するモブ(EntityType)の指定が必須です`);
    }
    const effects = entry.effects;
    if (effects !== undefined && effects !== null) {
      if (!Array.isArray(effects)) {
        errors.push(`${prefix}.effects: 配列である必要があります`);
      } else {
        effects.forEach((effect, i) => {
          if (!isPlainObject(effect)) {
            errors.push(`${prefix}.effects[${i}]: マップである必要があります`);
            return;
          }
          if (typeof effect.type !== "string" || !effect.type.trim()) {
            errors.push(`${prefix}.effects[${i}].type: PotionEffectType名(文字列)である必要があります`);
          }
          if (effect["duration-seconds"] !== undefined
              && (typeof effect["duration-seconds"] !== "number" || effect["duration-seconds"] < 0)) {
            errors.push(`${prefix}.effects[${i}].duration-seconds: 0以上の数値である必要があります`);
          }
          if (effect.amplifier !== undefined && (!Number.isInteger(effect.amplifier) || effect.amplifier < 0)) {
            errors.push(`${prefix}.effects[${i}].amplifier: 0以上の整数である必要があります`);
          }
        });
      }
    }
  }
}

// ---- loot-tables.yml (ars-loot-tables) ----
// 構造物ルートチェストへの追加抽選。Java 側(LootTableConfig)の丸めと同じ範囲を張る。
const LOOT_ENTRY_TYPES = ["item", "enchant-book"];

function validateArsLootTables(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  if (data.enabled !== undefined && typeof data.enabled !== "boolean") {
    errors.push("enabled: 真偽値である必要があります");
  }
  const pools = data.pools;
  if (pools === undefined || pools === null) return;
  if (!isPlainObject(pools)) { errors.push("pools: マップである必要があります"); return; }
  for (const [id, pool] of Object.entries(pools)) {
    const prefix = `pools.${id}`;
    if (!/^[a-zA-Z0-9_]+$/.test(id)) {
      errors.push(`${prefix}: IDは半角英数字とアンダースコアのみ使用できます`);
    }
    if (!isPlainObject(pool)) { errors.push(`${prefix}: マップである必要があります`); continue; }
    // tables: が空だと「書いたのに永久に出ない」プールになる。Java 側は警告するだけなので
    // ここでエラーにして保存前に気づけるようにする。
    if (!Array.isArray(pool.tables) || !pool.tables.length) {
      errors.push(`${prefix}.tables: 対象ルートテーブルを1件以上指定してください (空だと永久に発動しません)`);
    } else {
      pool.tables.forEach((table, i) => {
        if (typeof table !== "string" || !table.trim()) {
          errors.push(`${prefix}.tables[${i}]: 文字列である必要があります`);
        }
      });
    }
    if (pool.rolls !== undefined && pool.rolls !== null
        && (!Number.isInteger(pool.rolls) || pool.rolls < 1 || pool.rolls > 16)) {
      errors.push(`${prefix}.rolls: 1〜16 の整数である必要があります (Java 側もこの範囲に丸めます)`);
    }
    if (!Array.isArray(pool.entries) || !pool.entries.length) {
      errors.push(`${prefix}.entries: 候補を1件以上指定してください`);
      continue;
    }
    pool.entries.forEach((entry, i) => {
      const ep = `${prefix}.entries[${i}]`;
      if (!isPlainObject(entry)) { errors.push(`${ep}: マップである必要があります`); return; }
      const type = entry.type === undefined || entry.type === null ? "item" : String(entry.type);
      if (!LOOT_ENTRY_TYPES.includes(type)) {
        errors.push(`${ep}.type: ${LOOT_ENTRY_TYPES.join(" / ")} のいずれかである必要があります`);
      }
      if (type !== "enchant-book" && !String(entry.item || "").trim()) {
        errors.push(`${ep}.item: Material名 または custom:<ID> の指定が必須です`);
      }
      if (entry.chance !== undefined && entry.chance !== null
          && (typeof entry.chance !== "number" || !(entry.chance >= 0) || entry.chance > 1)) {
        errors.push(`${ep}.chance: 0〜1 の数値である必要があります`);
      }
      for (const key of ["min", "max"]) {
        if (entry[key] !== undefined && entry[key] !== null
            && (!Number.isInteger(entry[key]) || entry[key] < 1 || entry[key] > 64)) {
          errors.push(`${ep}.${key}: 1〜64 の整数である必要があります`);
        }
      }
      if (Number.isInteger(entry.min) && Number.isInteger(entry.max) && entry.min > entry.max) {
        errors.push(`${ep}: min が max を超えています`);
      }
    });
  }
}

function validateGeneric(data, errors) {
  // 汎用: ルートがマップまたは配列であればOK (スカラー単体は想定しない)。
  if (data === null) return;
  if (typeof data !== "object") {
    errors.push("ルートはマップまたは配列である必要があります");
  }
}

// ---- crafting-features.yml (tf-crafting-features) ----
function validateTfCraftingFeatures(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const gated = data["gated-catalog-recipes"];
  if (gated !== undefined && gated !== null) {
    if (!isPlainObject(gated)) errors.push("gated-catalog-recipes はマップである必要があります");
    else {
      for (const [k, v] of Object.entries(gated)) {
        if (typeof v !== "string" || !v.trim()) {
          errors.push(`gated-catalog-recipes.${k}: 専用効果ID(文字列)である必要があります`);
        }
      }
    }
  }
  const thread = data["thread-slots"];
  if (thread !== undefined && thread !== null) {
    if (!isPlainObject(thread)) errors.push("thread-slots はマップである必要があります");
    else {
      const caps = thread["max-by-category"];
      if (caps !== undefined && caps !== null) {
        if (!isPlainObject(caps)) errors.push("thread-slots.max-by-category はマップである必要があります");
        else {
          for (const [k, v] of Object.entries(caps)) {
            if (!Number.isInteger(v) || v < 0) {
              errors.push(`thread-slots.max-by-category.${k}: 0以上の整数である必要があります`);
            }
          }
        }
      }
    }
  }
  const removed = data["removed-vanilla-recipes"];
  if (removed !== undefined && removed !== null) {
    if (!Array.isArray(removed)) {
      errors.push("removed-vanilla-recipes は配列である必要があります");
    } else {
      for (let i = 0; i < removed.length; i++) {
        const v = removed[i];
        if (typeof v !== "string" || !v.trim()) {
          errors.push(`removed-vanilla-recipes[${i}]: レシピキー(文字列)である必要があります`);
          continue;
        }
        // minecraft:iron_sword / iron_sword / somepack:foo を許容。trinityforge: は対象外。
        if (!/^([a-z0-9_.-]+:)?[a-z0-9/_.-]+$/.test(v.trim().toLowerCase())) {
          errors.push(`removed-vanilla-recipes[${i}]: '${v}' はレシピキーとして不正です (例: minecraft:iron_sword)`);
        } else if (v.trim().toLowerCase().startsWith("trinityforge:")) {
          errors.push(`removed-vanilla-recipes[${i}]: trinityforge: のレシピは削除対象にできません (カタログ側でレシピを削除してください)`);
        }
      }
    }
  }
  const removedItems = data["removed-vanilla-items"];
  if (removedItems !== undefined && removedItems !== null) {
    if (!Array.isArray(removedItems)) {
      errors.push("removed-vanilla-items は配列である必要があります");
    } else {
      for (let i = 0; i < removedItems.length; i++) {
        const v = removedItems[i];
        if (typeof v !== "string" || !v.trim()) {
          errors.push(`removed-vanilla-items[${i}]: アイテム指定(文字列)である必要があります`);
          continue;
        }
        const trimmed = v.trim();
        const lower = trimmed.toLowerCase();
        if (lower.startsWith("trinityforge:")) {
          errors.push(`removed-vanilla-items[${i}]: '${v}' TFカスタムアイテム(trinityforge:)は削除対象にできません`);
          continue;
        }
        // Material または Material:enchant_id (コロンは0〜1個)。
        const colonCount = (trimmed.match(/:/g) || []).length;
        if (colonCount > 1 || !/^[^:\s]+(:[^:\s]+)?$/.test(trimmed)) {
          errors.push(`removed-vanilla-items[${i}]: '${v}' はアイテム指定として不正です (例: IRON_PICKAXE / enchanted_book:mending)`);
        }
      }
    }
  }
  const added = data["added-recipes"];
  if (added !== undefined && added !== null) {
    if (!Array.isArray(added)) {
      errors.push("added-recipes は配列である必要があります");
    } else {
      added.forEach((r, i) => {
        if (!isPlainObject(r)) { errors.push(`added-recipes[${i}]: マップである必要があります`); return; }
        if (typeof r.result !== "string" || !r.result.trim()) {
          errors.push(`added-recipes[${i}].result: 結果アイテム(バニラMaterial名)を指定してください`);
        } else if (/^(custom:|list:)/i.test(r.result.trim())) {
          errors.push(`added-recipes[${i}].result: 結果はバニラアイテムのみです (custom:/list: 不可)`);
        }
        const type = r.type || "shaped";
        if (type !== "shaped" && type !== "shapeless") {
          errors.push(`added-recipes[${i}].type: shaped または shapeless である必要があります`);
        }
        const method = r.method || "workbench";
        if (method !== "workbench" && method !== "inventory") {
          errors.push(`added-recipes[${i}].method: workbench または inventory である必要があります`);
        }
        if (type === "shaped") {
          if (!Array.isArray(r.shape) || r.shape.length === 0) {
            errors.push(`added-recipes[${i}].shape: 配置(文字列の配列)が必要です`);
          }
          if (!isPlainObject(r.ingredients)) {
            errors.push(`added-recipes[${i}].ingredients: 記号→素材のマップが必要です`);
          }
        } else if (!Array.isArray(r.ingredients) || r.ingredients.length === 0) {
          errors.push(`added-recipes[${i}].ingredients: 素材(文字列の配列)が必要です`);
        }
      });
    }
  }
  const woodRepair = data["wood-repair"];
  if (woodRepair !== undefined && woodRepair !== null) {
    if (!isPlainObject(woodRepair)) errors.push("wood-repair はマップである必要があります");
    else {
      const mats = woodRepair.materials;
      if (mats !== undefined && mats !== null) {
        if (!isPlainObject(mats)) errors.push("wood-repair.materials はマップである必要があります");
        else {
          for (const [id, entry] of Object.entries(mats)) {
            if (!isPlainObject(entry)) continue;
            const qr = entry["quick-repair"];
            if (qr !== undefined && qr !== null && typeof qr !== "boolean") {
              errors.push(`wood-repair.materials.${id}.quick-repair: 真偽値(true/false)である必要があります`);
            }
          }
        }
      }
    }
  }
  const bp = data["enchant-bookshelf-power"];
  if (bp !== undefined && bp !== null) {
    if (!isPlainObject(bp)) errors.push("enchant-bookshelf-power はマップである必要があります");
    else {
      if (bp["max-bookshelves"] !== undefined && bp["max-bookshelves"] !== null && !isNonNegInteger(bp["max-bookshelves"])) {
        errors.push("enchant-bookshelf-power.max-bookshelves: 0以上の整数である必要があります");
      }
      if (bp["power-per-bookshelf"] !== undefined && bp["power-per-bookshelf"] !== null && !isNumber(bp["power-per-bookshelf"])) {
        errors.push("enchant-bookshelf-power.power-per-bookshelf: 数値である必要があります");
      }
    }
  }
  const oe = data["over-enchant"];
  if (oe !== undefined && oe !== null) {
    if (!isPlainObject(oe)) errors.push("over-enchant はマップである必要があります");
    else {
      // Legacy: shared enchants list
      if (oe.enchants !== undefined && oe.enchants !== null && !Array.isArray(oe.enchants)) {
        errors.push("over-enchant.enchants は配列である必要があります");
      }
      // New: <effectId>.enchants.<ENCHANT>: maxLevel
      for (const [pid, profile] of Object.entries(oe)) {
        if (pid === "tiers" || pid === "fortune-cap-bonus" || pid === "enchants" || pid === "profiles") continue;
        if (!isPlainObject(profile)) continue;
        const caps = profile.enchants;
        if (caps === undefined || caps === null) continue;
        if (!isPlainObject(caps)) {
          errors.push(`over-enchant.${pid}.enchants はマップである必要があります`);
          continue;
        }
        for (const [ench, max] of Object.entries(caps)) {
          if (!Number.isInteger(max) || max < 1) {
            errors.push(`over-enchant.${pid}.enchants.${ench}: 1以上の整数である必要があります`);
          }
        }
      }
    }
  }
}

// ---- crafting-features.yml companion tabs (tf-enchant-gimmick / tf-brew-gimmick) ----
// T6 (2026-07-26): 「エンチャントギミック」「醸造ギミック」タブは crafting-features.yml を丸ごと
// 読み込み・書き戻す(担当外のサブツリーは温存)。物理ファイルは同一のため、検証も
// validateTfCraftingFeatures をそのまま再利用する(over-enchant / wood-repair 等の既存チェックが
// どちらのタブ経由の保存でも一貫して効く)。
function validateTfEnchantGimmick(data, errors) {
  validateTfCraftingFeatures(data, errors);
}
function validateTfBrewGimmick(data, errors) {
  validateTfCraftingFeatures(data, errors);
}

// ---- use-requirements.yml (tf-use-requirements) ----
function validateTfUseRequirements(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  if (data.enforce !== undefined && data.enforce !== null && typeof data.enforce !== "boolean") {
    errors.push("enforce: 真偽値(true/false)である必要があります");
  }
}

// ---- afk.yml (tf-afk) ----
// AFK(離席)判定(TrinityForge/src/main/resources/afk.yml が正)。「使用制限スイッチ」タブ内へ
// コンパニオン表示する(2026-07-27新設)。Java側(AfkConfig)は不正値を黙って丸めるが、editor側は
// 保存時点でエラーにする(丸め後の実挙動と editor に保存した値がずれる事故を防ぐ、
// このプロジェクトで繰り返し踏んでいる問題への対策)。
function validateTfAfk(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  if (data.enabled !== undefined && data.enabled !== null && typeof data.enabled !== "boolean") {
    errors.push("enabled: 真偽値(true/false)である必要があります");
  }
  const idleSeconds = data["idle-seconds"];
  if (idleSeconds !== undefined && idleSeconds !== null && !(isInteger(idleSeconds) && idleSeconds >= 1)) {
    errors.push("idle-seconds: 1以上の整数である必要があります");
  }
  const kickAfterSeconds = data["kick-after-seconds"];
  if (kickAfterSeconds !== undefined && kickAfterSeconds !== null && !(isInteger(kickAfterSeconds) && kickAfterSeconds >= 0)) {
    errors.push("kick-after-seconds: 0以上の整数である必要があります");
  }
  // Java側は「0以外かつ idle-seconds 未満」を idle-seconds へ黙って引き上げる。editor は
  // 保存値と実挙動のずれを防ぐため、この組み合わせを保存時点でエラーにする。
  if (isInteger(kickAfterSeconds) && isInteger(idleSeconds) && kickAfterSeconds !== 0 && kickAfterSeconds < idleSeconds) {
    errors.push("kick-after-seconds: 0(キックしない)以外にする場合は idle-seconds 以上である必要があります");
  }
  if (data["kick-message"] !== undefined && data["kick-message"] !== null && typeof data["kick-message"] !== "string") {
    errors.push("kick-message: 文字列である必要があります");
  }
  if (data.notify !== undefined && data.notify !== null && typeof data.notify !== "boolean") {
    errors.push("notify: 真偽値(true/false)である必要があります");
  }
  if (data["tab-suffix"] !== undefined && data["tab-suffix"] !== null && typeof data["tab-suffix"] !== "boolean") {
    errors.push("tab-suffix: 真偽値(true/false)である必要があります");
  }
  if (data["tab-suffix-text"] !== undefined && data["tab-suffix-text"] !== null && typeof data["tab-suffix-text"] !== "string") {
    errors.push("tab-suffix-text: 文字列である必要があります");
  }
  // exempt-permission: 空文字は「免除無効」という意味のある値なので既定へ寄せず、型のみ検証する。
  if (data["exempt-permission"] !== undefined && data["exempt-permission"] !== null && typeof data["exempt-permission"] !== "string") {
    errors.push("exempt-permission: 文字列である必要があります");
  }
  const checkIntervalTicks = data["check-interval-ticks"];
  if (checkIntervalTicks !== undefined && checkIntervalTicks !== null && !(isInteger(checkIntervalTicks) && checkIntervalTicks >= 20)) {
    errors.push("check-interval-ticks: 20以上の整数である必要があります(20未満はJava側で20へ丸められるため)");
  }
  const suppress = data.suppress;
  if (suppress !== undefined && suppress !== null) {
    if (!isPlainObject(suppress)) {
      errors.push("suppress: マップである必要があります");
    } else {
      for (const key of ["skill-exp", "vanilla-exp", "mob-drops", "fishing-sell"]) {
        const value = suppress[key];
        if (value !== undefined && value !== null && typeof value !== "boolean") {
          errors.push(`suppress.${key}: 真偽値(true/false)である必要があります`);
        }
      }
    }
  }
}

// ---- skilltree/*.yml (tf-skilltree) ----
function validateTfSkillTree(data, errors) {
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  validateSkillBuffOwner(data.prestige, "prestige", errors);
  if (data.nodes !== undefined && data.nodes !== null) {
    if (!isPlainObject(data.nodes)) {
      errors.push("nodes: マップである必要があります");
    } else {
      for (const [id, node] of Object.entries(data.nodes)) {
        if (!isPlainObject(node)) {
          errors.push(`nodes.${id}: マップである必要があります`);
          continue;
        }
        validateSkillBuffOwner(node, `nodes.${id}`, errors);
      }
    }
  }
}

function validateSkillBuffOwner(owner, prefix, errors) {
  if (owner === undefined || owner === null) return;
  if (!isPlainObject(owner)) { errors.push(`${prefix}: マップである必要があります`); return; }
  for (const key of ["buffs", "mainhand-buffs"]) {
    if (owner[key] !== undefined && owner[key] !== null) {
      if (!isPlainObject(owner[key])) {
        errors.push(`${prefix}.${key}: ステータス→数値のマップである必要があります`);
      } else {
        for (const [stat, value] of Object.entries(owner[key])) {
          if (!isNumber(value)) errors.push(`${prefix}.${key}.${stat}: 数値である必要があります`);
        }
      }
    }
  }
  if (owner.multipliers !== undefined && owner.multipliers !== null) {
    if (!isPlainObject(owner.multipliers)) {
      errors.push(`${prefix}.multipliers: レイヤ→ステータス→倍率のマップである必要があります`);
    } else {
      for (const [layerId, stats] of Object.entries(owner.multipliers)) {
        if (layerId === "__unset__") {
          errors.push(`${prefix}.multipliers: 乗算レイヤが未選択のステータスがあります`);
        }
        if (!isPlainObject(stats)) {
          errors.push(`${prefix}.multipliers.${layerId}: ステータス→倍率のマップである必要があります`);
          continue;
        }
        for (const [stat, value] of Object.entries(stats)) {
          if (!isNumber(value)) {
            errors.push(`${prefix}.multipliers.${layerId}.${stat}: 数値(倍率)である必要があります`);
          }
        }
      }
    }
  }
  // set-buffs(装備部位数条件バフ、armor-set-buffs全面移行§1): 段キーは3・4のみ。値はstat→数値。
  // light_armor/heavy_armor以外のツリーでの使用可否はJava側(SkillTreeConfig)が警告して無視するので、
  // ここではデータ形状だけを検証する(ツリー種別のスコープ判定はしない)。
  if (owner["set-buffs"] !== undefined && owner["set-buffs"] !== null) {
    if (!isPlainObject(owner["set-buffs"])) {
      errors.push(`${prefix}.set-buffs: 段(3/4)→ステータス→数値のマップである必要があります`);
    } else {
      for (const [tier, stats] of Object.entries(owner["set-buffs"])) {
        if (tier !== "3" && tier !== "4") {
          errors.push(`${prefix}.set-buffs.${tier}: 段は3または4である必要があります`);
        }
        if (!isPlainObject(stats)) {
          errors.push(`${prefix}.set-buffs.${tier}: ステータス→数値のマップである必要があります`);
          continue;
        }
        for (const [stat, value] of Object.entries(stats)) {
          if (!isNumber(value)) errors.push(`${prefix}.set-buffs.${tier}.${stat}: 数値である必要があります`);
        }
      }
    }
  }
}

// ---- items/material-lists.yml (tf-material-lists) ----
// レシピ素材欄 list:<id> トークンの解決先。lists.<id>.{label?, materials[]}。
const MATERIAL_LIST_ID_RE = /^[a-z0-9_]+$/;

function validateTfMaterialLists(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const lists = data.lists;
  if (lists === undefined || lists === null) return;
  if (!isPlainObject(lists)) { errors.push("lists はマップである必要があります"); return; }
  for (const [id, entry] of Object.entries(lists)) {
    const prefix = `lists.${id}`;
    if (!MATERIAL_LIST_ID_RE.test(id)) {
      errors.push(`${prefix}: リストIDは半角英小文字/数字/アンダースコアのみ使用できます`);
    }
    if (!isPlainObject(entry)) { errors.push(`${prefix}: マップである必要があります`); continue; }
    if (entry.label !== undefined && entry.label !== null && typeof entry.label !== "string") {
      errors.push(`${prefix}.label: 文字列である必要があります`);
    }
    const materials = entry.materials;
    if (!Array.isArray(materials) || materials.length === 0) {
      errors.push(`${prefix}.materials: Material名の配列(1個以上)が必要です`);
      continue;
    }
    materials.forEach((m, i) => {
      if (typeof m !== "string" || (!/^[A-Z0-9_]+$/.test(m) && !/^custom:[a-z0-9_]+$/i.test(m))) {
        errors.push(`${prefix}.materials[${i}]: Material名または custom:<アイテムID> である必要があります`);
      }
    });
  }
}

function validateExternalItems(data, errors) {
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const items = data.items;
  if (items === undefined || items === null) return;
  if (!isPlainObject(items)) { errors.push("items はマップである必要があります"); return; }
  for (const [id, entry] of Object.entries(items)) {
    const prefix = `items.${id}`;
    if (!/^[a-z0-9_]+$/.test(id)) errors.push(`${prefix}: IDは半角英小文字/数字/アンダースコアのみ使用できます`);
    if (!isPlainObject(entry)) { errors.push(`${prefix}: マップである必要があります`); continue; }
    if (typeof entry.material !== "string" || !/^[A-Z0-9_]+$/.test(entry.material)) errors.push(`${prefix}.material: Material名が必要です`);
    if (!isNonNegInteger(entry["custom-model-data"])) errors.push(`${prefix}.custom-model-data: 0以上の整数が必要です`);
    if (entry["display-name"] !== undefined && typeof entry["display-name"] !== "string") errors.push(`${prefix}.display-name: 文字列である必要があります`);
  }
}

// ---- progression/special-rewards.yml (tf-special-rewards) ----
const PARTICLE_SHAPES = ["circle", "aura"];

function validateTfSpecialRewards(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }

  if (data["prune-orphaned-grants"] !== undefined && data["prune-orphaned-grants"] !== null
      && typeof data["prune-orphaned-grants"] !== "boolean") {
    errors.push("prune-orphaned-grants: 真偽値である必要があります");
  }

  const titles = data.titles;
  if (titles !== undefined && titles !== null) {
    if (!isPlainObject(titles)) errors.push("titles はマップである必要があります");
    else for (const [id, entry] of Object.entries(titles)) {
      if (!isPlainObject(entry)) { errors.push(`titles.${id}: マップである必要があります`); continue; }
      if (entry.display !== undefined && entry.display !== null && typeof entry.display !== "string") {
        errors.push(`titles.${id}.display: 文字列(MiniMessage)である必要があります`);
      }
    }
  }

  const particles = data.particles;
  if (particles !== undefined && particles !== null) {
    if (!isPlainObject(particles)) errors.push("particles はマップである必要があります");
    else for (const [id, entry] of Object.entries(particles)) {
      if (!isPlainObject(entry)) { errors.push(`particles.${id}: マップである必要があります`); continue; }
      if (entry.particle !== undefined && entry.particle !== null && typeof entry.particle !== "string") {
        errors.push(`particles.${id}.particle: 文字列(Bukkit Particle名)である必要があります`);
      }
      if (entry.count !== undefined && entry.count !== null && !isNonNegInteger(entry.count)) {
        errors.push(`particles.${id}.count: 0以上の整数である必要があります`);
      }
      if (entry.radius !== undefined && entry.radius !== null && (!isNumber(entry.radius) || entry.radius < 0)) {
        errors.push(`particles.${id}.radius: 0以上の数値である必要があります`);
      }
      if (entry["interval-ticks"] !== undefined && entry["interval-ticks"] !== null && !isNonNegInteger(entry["interval-ticks"])) {
        errors.push(`particles.${id}.interval-ticks: 0以上の整数である必要があります`);
      }
      if (entry.shape !== undefined && entry.shape !== null && !PARTICLE_SHAPES.includes(entry.shape)) {
        errors.push(`particles.${id}.shape: ${PARTICLE_SHAPES.join(" / ")} のいずれかである必要があります`);
      }
    }
  }

  const seeds = data["particle-seeds"];
  if (seeds !== undefined && seeds !== null) {
    if (!isPlainObject(seeds)) errors.push("particle-seeds はマップである必要があります");
    else for (const [id, entry] of Object.entries(seeds)) {
      if (!isPlainObject(entry)) { errors.push(`particle-seeds.${id}: マップである必要があります`); continue; }
      if (entry["seed-item"] !== undefined && entry["seed-item"] !== null && typeof entry["seed-item"] !== "string") {
        errors.push(`particle-seeds.${id}.seed-item: 文字列(Material名 または custom:<id>)である必要があります`);
      }
      if (entry.particle !== undefined && entry.particle !== null && typeof entry.particle !== "string") {
        errors.push(`particle-seeds.${id}.particle: 文字列(Bukkit Particle名)である必要があります`);
      }
      if (entry.count !== undefined && entry.count !== null && !isNonNegInteger(entry.count)) {
        errors.push(`particle-seeds.${id}.count: 0以上の整数である必要があります`);
      }
    }
  }
}

// ---- achievements.yml / collection.yml 共通の報酬拡張フィールド (items/vanilla-exp/job-exp/permanent-buffs) ----
// Java側 SkillId 16種と一致させること (config-editor/public/js/tf-rewards-forms.js の JOB_EXP_SKILLS と同期)。
const REWARD_JOB_SKILLS = [
  "ALCHEMY", "ARCHERY", "ARS_MAGIC", "ARS_SMITHING", "DIGGING", "ENCHANTING",
  "FARMING", "FISHING", "HEAVY_ARMOR", "HEAVY_WEAPONS", "LIGHT_ARMOR",
  "LIGHT_WEAPONS", "MINING", "POWER", "SMITHING", "WOODCUTTING"
];

// prefix配下の rewards.items / rewards.vanilla-exp / rewards.job-exp / rewards.permanent-buffs を検証する。
// achievements.yml の rewards と collection.yml の reward-tiers 両方で共有する。
function validateRewardExtras(rewards, prefix, errors) {
  if (rewards.items !== undefined && rewards.items !== null) {
    if (!Array.isArray(rewards.items)) {
      errors.push(`${prefix}.items: 配列である必要があります`);
    } else {
      rewards.items.forEach((v, i) => {
        if (!isPlainObject(v)) { errors.push(`${prefix}.items[${i}]: マップである必要があります`); return; }
        if (typeof v.id !== "string" || !v.id.trim()) {
          errors.push(`${prefix}.items[${i}].id: 必須の文字列(catalogID または Material名)です`);
        }
        if (v.amount !== undefined && v.amount !== null && !(isInteger(v.amount) && v.amount > 0)) {
          errors.push(`${prefix}.items[${i}].amount: 1以上の整数である必要があります`);
        }
      });
    }
  }
  if (rewards["vanilla-exp"] !== undefined && rewards["vanilla-exp"] !== null) {
    if (!isNonNegInteger(rewards["vanilla-exp"])) {
      errors.push(`${prefix}.vanilla-exp: 0以上の整数である必要があります`);
    }
  }
  if (rewards["job-exp"] !== undefined && rewards["job-exp"] !== null) {
    if (!Array.isArray(rewards["job-exp"])) {
      errors.push(`${prefix}.job-exp: 配列である必要があります`);
    } else {
      rewards["job-exp"].forEach((v, i) => {
        if (!isPlainObject(v)) { errors.push(`${prefix}.job-exp[${i}]: マップである必要があります`); return; }
        if (!REWARD_JOB_SKILLS.includes(v.skill)) {
          errors.push(`${prefix}.job-exp[${i}].skill: SkillId(${REWARD_JOB_SKILLS.join(" / ")})のいずれかである必要があります`);
        }
        if (!isNumber(v.amount)) {
          errors.push(`${prefix}.job-exp[${i}].amount: 数値である必要があります`);
        }
      });
    }
  }
  if (rewards["permanent-buffs"] !== undefined && rewards["permanent-buffs"] !== null) {
    if (!isPlainObject(rewards["permanent-buffs"])) {
      errors.push(`${prefix}.permanent-buffs: マップである必要があります`);
    } else {
      for (const [k, v] of Object.entries(rewards["permanent-buffs"])) {
        if (!isNumber(v)) errors.push(`${prefix}.permanent-buffs.${k}: 数値である必要があります`);
      }
    }
  }
}

// ---- progression/achievements.yml (tf-achievements) ----
// 2026-07-31: counter を追加(AchievementsConfig.TriggerType と 1:1)。累計カウンタ型は
// バニラ統計に無い総量(儀式で消費した累計ソース等)をしきい値判定する。
const ACHIEVEMENT_TRIGGER_TYPES = ["statistic", "advancement", "static", "counter"];

/** trigger.type: counter で選べる累計カウンタID。Java/フォーク側が実際に加算しているものだけ。 */
const ACHIEVEMENT_COUNTER_IDS = ["source_spent"];
// 2026-07-30: Bukkit の Statistic.Type が UNTYPED でないもの = 修飾子(Material/EntityType)必須。
// public/js/tf-rewards-forms.js の QUALIFIED_STATISTIC_OPTIONS と同じ集合を保つこと。
const QUALIFIED_STATISTICS = [
  "MINE_BLOCK", "CRAFT_ITEM", "USE_ITEM", "BREAK_ITEM", "PICKUP", "DROP",
  "KILL_ENTITY", "ENTITY_KILLED_BY"
];

function validateTfAchievements(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }

  const vanillaAdv = data["vanilla-advancements"];
  if (vanillaAdv !== undefined && vanillaAdv !== null) {
    if (!isPlainObject(vanillaAdv)) {
      errors.push("vanilla-advancements: マップである必要があります");
    } else {
      if (vanillaAdv.disabled !== undefined && vanillaAdv.disabled !== null && typeof vanillaAdv.disabled !== "boolean") {
        errors.push("vanilla-advancements.disabled: 真偽値である必要があります");
      }
      if (vanillaAdv["keep-recipe-advancements"] !== undefined && vanillaAdv["keep-recipe-advancements"] !== null
          && typeof vanillaAdv["keep-recipe-advancements"] !== "boolean") {
        errors.push("vanilla-advancements.keep-recipe-advancements: 真偽値である必要があります");
      }
      if (vanillaAdv.keep !== undefined && vanillaAdv.keep !== null) {
        if (!Array.isArray(vanillaAdv.keep)) {
          errors.push("vanilla-advancements.keep: 配列である必要があります");
        } else {
          vanillaAdv.keep.forEach((v, i) => {
            if (typeof v !== "string") errors.push(`vanilla-advancements.keep[${i}]: 文字列である必要があります`);
          });
        }
      }
    }
  }

  const achievements = data.achievements;
  if (achievements === undefined || achievements === null) return;
  if (!isPlainObject(achievements)) { errors.push("achievements はマップである必要があります"); return; }
  for (const [id, entry] of Object.entries(achievements)) {
    const prefix = `achievements.${id}`;
    if (!isPlainObject(entry)) { errors.push(`${prefix}: マップである必要があります`); continue; }
    if (entry["display-name"] !== undefined && entry["display-name"] !== null && typeof entry["display-name"] !== "string") {
      errors.push(`${prefix}.display-name: 文字列である必要があります`);
    }
    if (entry.broadcast !== undefined && entry.broadcast !== null && typeof entry.broadcast !== "boolean") {
      errors.push(`${prefix}.broadcast: 真偽値である必要があります`);
    }
    // アイコン/説明Lore/前提・配置 (2026-07-29)。前提は「達成そのものを縛る」ので、
    // 不明IDや自己参照をここで止めないと「条件を満たしても永久に取れない」定義が通ってしまう。
    if (entry.icon !== undefined && entry.icon !== null && typeof entry.icon !== "string") {
      errors.push(`${prefix}.icon: 文字列(カタログID / custom:ID / Material名)である必要があります`);
    }
    if (entry.lore !== undefined && entry.lore !== null) {
      if (!Array.isArray(entry.lore)) errors.push(`${prefix}.lore: 配列である必要があります`);
      else entry.lore.forEach((v, i) => {
        if (typeof v !== "string") errors.push(`${prefix}.lore[${i}]: 文字列である必要があります`);
      });
    }
    if (entry.coords !== undefined && entry.coords !== null) {
      if (typeof entry.coords !== "string") {
        errors.push(`${prefix}.coords: "x,y" 形式の文字列である必要があります`);
      } else if (entry.coords.trim() !== "" && !/^-?\d+\s*,\s*-?\d+$/.test(entry.coords.trim())) {
        errors.push(`${prefix}.coords: "x,y" 形式(整数2つ)である必要があります: ${entry.coords}`);
      }
    }
    if (entry.parent !== undefined && entry.parent !== null) {
      if (typeof entry.parent !== "string") {
        errors.push(`${prefix}.parent: アチーブメントID(文字列)である必要があります`);
      } else if (entry.parent.trim() !== "") {
        const parent = entry.parent.trim();
        if (parent === id) errors.push(`${prefix}.parent: 自分自身を前提にはできません`);
        else if (!Object.prototype.hasOwnProperty.call(achievements, parent)) {
          errors.push(`${prefix}.parent: 存在しないアチーブメントIDです: ${parent}`);
        }
      }
    }
    const parentsAny = entry["parents-any"];
    if (parentsAny !== undefined && parentsAny !== null) {
      if (!Array.isArray(parentsAny)) {
        errors.push(`${prefix}.parents-any: 配列である必要があります`);
      } else {
        parentsAny.forEach((v, i) => {
          if (typeof v !== "string" || !v.trim()) {
            errors.push(`${prefix}.parents-any[${i}]: アチーブメントID(文字列)である必要があります`);
            return;
          }
          const ref = v.trim();
          if (ref === id) errors.push(`${prefix}.parents-any[${i}]: 自分自身を前提にはできません`);
          else if (!Object.prototype.hasOwnProperty.call(achievements, ref)) {
            errors.push(`${prefix}.parents-any[${i}]: 存在しないアチーブメントIDです: ${ref}`);
          }
        });
      }
    }
    const trigger = entry.trigger;
    if (trigger === undefined || trigger === null) {
      errors.push(`${prefix}.trigger: 必須です`);
    } else if (!isPlainObject(trigger)) {
      errors.push(`${prefix}.trigger: マップである必要があります`);
    } else {
      if (!ACHIEVEMENT_TRIGGER_TYPES.includes(trigger.type)) {
        errors.push(`${prefix}.trigger.type: ${ACHIEVEMENT_TRIGGER_TYPES.join(" / ")} のいずれかである必要があります`);
      } else if (trigger.type === "statistic") {
        if (typeof trigger.statistic !== "string" || !trigger.statistic) {
          errors.push(`${prefix}.trigger.statistic: 必須の文字列(Bukkit Statistic名)です`);
        }
        // 2026-07-30: 修飾子(Material/EntityType)が必要な統計。Java 側は空欄だと
        // そのアチーブメントごと skip する(AchievementsConfig#parseStatisticQualifier)ので、
        // 保存前にここで落として「無言で消える定義」を作らせない。
        if (QUALIFIED_STATISTICS.includes(trigger.statistic)
            && (typeof trigger["statistic-qualifier"] !== "string" || !trigger["statistic-qualifier"].trim())) {
          errors.push(`${prefix}.trigger.statistic-qualifier: ${trigger.statistic} は対象(Material/EntityType)の指定が必須です`);
        }
        if (trigger["statistic-qualifier"] !== undefined && typeof trigger["statistic-qualifier"] !== "string") {
          errors.push(`${prefix}.trigger.statistic-qualifier: 文字列である必要があります`);
        }
        if (!isNonNegInteger(trigger.threshold)) {
          errors.push(`${prefix}.trigger.threshold: 0以上の整数である必要があります`);
        }
      } else if (trigger.type === "advancement") {
        if (typeof trigger.advancement !== "string" || !trigger.advancement) {
          errors.push(`${prefix}.trigger.advancement: 必須の文字列(進捗キー)です`);
        }
      } else if (trigger.type === "counter") {
        // カウンタIDは自由文字列だと「誰も達成できない定義」を静かに作れてしまうので、
        // 実際に加算されている既知のIDだけを通す(増やすときは Java 側の加算実装と同時に)。
        if (!ACHIEVEMENT_COUNTER_IDS.includes(trigger.counter)) {
          errors.push(`${prefix}.trigger.counter: ${ACHIEVEMENT_COUNTER_IDS.join(" / ")} のいずれかである必要があります`);
        }
        if (!isPositiveInt(trigger.threshold)) {
          errors.push(`${prefix}.trigger.threshold: 1以上の整数である必要があります`);
        }
      } else if (trigger.type === "static") {
        const c = trigger.collection;
        if (!isPlainObject(c)) errors.push(`${prefix}.trigger.collection: マップである必要があります`);
        else {
          if (!["all", "category", "item", "mob"].includes(c.scope)) errors.push(`${prefix}.trigger.collection.scope: all / category / item / mob のいずれかである必要があります`);
          // 2026-07-31: 複数対象 targets を通す。Java 側は 2026-07-27 に単数 target から
          // 複数 targets へ拡張済みだったのに、ここは単数キー必須のままだった ── そのため
          // 「Java では正しく動く定義」がエディタでは保存できず、複数対象アチーブメントを
          // GUI で作れなかった(手書き yml を開くと必ず検証エラーになる状態)。
          const targets = c.targets;
          if (targets !== undefined && targets !== null) {
            if (!Array.isArray(targets) || targets.some((t) => typeof t !== "string" || !t.trim())) {
              errors.push(`${prefix}.trigger.collection.targets: 空でない文字列の配列である必要があります`);
            }
          }
          const hasTarget = typeof c.target === "string" && c.target;
          const hasTargets = Array.isArray(targets) && targets.some((t) => typeof t === "string" && t.trim());
          if (c.scope !== "all" && !hasTarget && !hasTargets) {
            errors.push(`${prefix}.trigger.collection.target / targets: scopeがall以外ではどちらかが必須です`);
          }
          // scope=item/mob かつ percent でないときは threshold 省略可(既定=列挙した件数)。
          // Java 側 AchievementsConfig.parseTrigger と同じ既定値規則。category/all は候補数と
          // 列挙数が一致しないので従来どおり必須。
          const thresholdOptional = hasTargets && c.percent !== true
            && (c.scope === "item" || c.scope === "mob");
          if (!(thresholdOptional && (c.threshold === undefined || c.threshold === null))
              && (!isNonNegInteger(c.threshold) || c.threshold < 1)) {
            errors.push(`${prefix}.trigger.collection.threshold: 1以上の整数である必要があります`
              + `(scope=item/mob で targets を列挙した場合のみ省略可)`);
          }
          if (c.percent !== undefined && typeof c.percent !== "boolean") errors.push(`${prefix}.trigger.collection.percent: 真偽値である必要があります`);
        }
      }
    }
    const rewards = entry.rewards;
    if (rewards !== undefined && rewards !== null) {
      if (!isPlainObject(rewards)) {
        errors.push(`${prefix}.rewards: マップである必要があります`);
      } else {
        if (rewards.special !== undefined && rewards.special !== null) {
          if (!Array.isArray(rewards.special)) errors.push(`${prefix}.rewards.special: 配列である必要があります`);
          else rewards.special.forEach((v, i) => {
            if (typeof v !== "string" || !v) errors.push(`${prefix}.rewards.special[${i}]: 特殊報酬ID(文字列)である必要があります`);
          });
        }
        if (rewards.commands !== undefined && rewards.commands !== null) {
          if (!Array.isArray(rewards.commands)) errors.push(`${prefix}.rewards.commands: 配列である必要があります`);
          else rewards.commands.forEach((v, i) => {
            if (typeof v !== "string") errors.push(`${prefix}.rewards.commands[${i}]: 文字列である必要があります`);
          });
        }
        validateRewardExtras(rewards, `${prefix}.rewards`, errors);
      }
    }
  }
}

// ---- progression/collection.yml (tf-collection) ----
function validateTfCollectionCategoryGroup(group, prefix, errors) {
  if (group === undefined || group === null) return;
  if (!isPlainObject(group)) { errors.push(`${prefix}: マップである必要があります`); return; }
  for (const [catId, entry] of Object.entries(group)) {
    const p = `${prefix}.${catId}`;
    if (!isPlainObject(entry)) { errors.push(`${p}: マップである必要があります`); continue; }
    if (entry["display-name"] !== undefined && entry["display-name"] !== null && typeof entry["display-name"] !== "string") {
      errors.push(`${p}.display-name: 文字列である必要があります`);
    }
    if (entry.order !== undefined && entry.order !== null && !isInteger(entry.order)) {
      errors.push(`${p}.order: 整数である必要があります`);
    }
    if (entry.entries !== undefined && entry.entries !== null) {
      if (!Array.isArray(entry.entries)) errors.push(`${p}.entries: 配列である必要があります`);
      else entry.entries.forEach((v, i) => {
        if (typeof v !== "string" || !v) errors.push(`${p}.entries[${i}]: 文字列である必要があります`);
      });
    }
  }
}

function validateTfCollection(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  if (data.enabled !== undefined && data.enabled !== null && typeof data.enabled !== "boolean") {
    errors.push("enabled: 真偽値である必要があります");
  }
  const sources = data.sources;
  if (sources !== undefined && sources !== null) {
    if (!isPlainObject(sources)) errors.push("sources はマップである必要があります");
    else for (const k of ["catalog-items", "mob-kills"]) {
      if (sources[k] !== undefined && sources[k] !== null && typeof sources[k] !== "boolean") {
        errors.push(`sources.${k}: 真偽値である必要があります`);
      }
    }
  }
  const categories = data.categories;
  if (categories !== undefined && categories !== null) {
    if (!isPlainObject(categories)) {
      errors.push("categories: マップである必要があります");
    } else {
      validateTfCollectionCategoryGroup(categories.items, "categories.items", errors);
      validateTfCollectionCategoryGroup(categories.mobs, "categories.mobs", errors);
    }
  }
  const tiers = data["reward-tiers"];
  if (tiers !== undefined && tiers !== null) {
    if (!isPlainObject(tiers)) { errors.push("reward-tiers はマップである必要があります"); return; }
    for (const [id, entry] of Object.entries(tiers)) {
      const prefix = `reward-tiers.${id}`;
      if (!isPlainObject(entry)) { errors.push(`${prefix}: マップである必要があります`); continue; }
      if (entry.threshold !== undefined && entry.threshold !== null && !isNonNegInteger(entry.threshold)) {
        errors.push(`${prefix}.threshold: 0以上の整数である必要があります`);
      }
      if (entry.title !== undefined && entry.title !== null && typeof entry.title !== "string") {
        errors.push(`${prefix}.title: 文字列である必要があります`);
      }
      if (entry.broadcast !== undefined && entry.broadcast !== null && typeof entry.broadcast !== "boolean") {
        errors.push(`${prefix}.broadcast: 真偽値である必要があります`);
      }
      if (entry.commands !== undefined && entry.commands !== null) {
        if (!Array.isArray(entry.commands)) errors.push(`${prefix}.commands: 配列である必要があります`);
        else entry.commands.forEach((v, i) => {
          if (typeof v !== "string") errors.push(`${prefix}.commands[${i}]: 文字列である必要があります`);
        });
      }
      if (entry.special !== undefined && entry.special !== null) {
        if (!Array.isArray(entry.special)) errors.push(`${prefix}.special: 配列である必要があります`);
        else entry.special.forEach((v, i) => {
          if (typeof v !== "string" || !v) errors.push(`${prefix}.special[${i}]: 特殊報酬ID(文字列)である必要があります`);
        });
      }
      validateRewardExtras(entry, prefix, errors);
    }
  }
}

// ---- food-gimmick.yml (tf-food-gimmick) ----
// custom-foods: <itemId>: { food-level: 整数0-20, saturation: 0以上の数値 }
function validateTfFoodGimmick(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const customFoods = data["custom-foods"];
  if (customFoods === undefined || customFoods === null) return;
  if (!isPlainObject(customFoods)) { errors.push("custom-foods はマップである必要があります"); return; }
  for (const [id, entry] of Object.entries(customFoods)) {
    const prefix = `custom-foods.${id}`;
    if (!id) { errors.push(`${prefix}: キー(アイテムID)は空でない文字列である必要があります`); }
    if (!isPlainObject(entry)) { errors.push(`${prefix}: マップである必要があります`); continue; }
    const level = entry["food-level"];
    if (level !== undefined && level !== null && !(isInteger(level) && level >= 0 && level <= 20)) {
      errors.push(`${prefix}.food-level: 0〜20 の整数である必要があります`);
    }
    const saturation = entry.saturation;
    if (saturation !== undefined && saturation !== null && !(isNumber(saturation) && saturation >= 0)) {
      errors.push(`${prefix}.saturation: 0以上の数値である必要があります`);
    }
  }
}

// ---- fishing-gimmick.yml (tf-fishing-gimmick) ----
// xp-bottle-store.return-rate: 取り出し時に返る割合(0.0〜1.0)
// fish-sell.prices: Material -> 基準売却額(0以上)。fish-sell.max-sells-per-minute: 0以上の整数。
// fishing.ocean-biomes: バイオームidの文字列配列(namespace無し小文字。ハードコード列挙はしない)。
function validateTfFishingGimmick(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const xp = data["xp-bottle-store"];
  if (xp !== undefined && xp !== null) {
    if (!isPlainObject(xp)) { errors.push("xp-bottle-store はマップである必要があります"); }
    else {
      const rate = xp["return-rate"];
      if (rate !== undefined && rate !== null && !(isNumber(rate) && rate >= 0 && rate <= 1)) {
        errors.push("xp-bottle-store.return-rate: 0.0〜1.0 の数値である必要があります");
      }
    }
  }
  const fishSell = data["fish-sell"];
  if (fishSell !== undefined && fishSell !== null) {
    if (!isPlainObject(fishSell)) { errors.push("fish-sell はマップである必要があります"); }
    else {
      const prices = fishSell.prices;
      if (prices !== undefined && prices !== null) {
        if (!isPlainObject(prices)) { errors.push("fish-sell.prices はマップである必要があります"); }
        else {
          for (const [material, price] of Object.entries(prices)) {
            if (!material || !material.trim()) {
              errors.push("fish-sell.prices: キー(Material)は空でない文字列である必要があります");
            }
            if (price !== undefined && price !== null && !(isNumber(price) && price >= 0)) {
              errors.push(`fish-sell.prices.${material}: 0以上の数値である必要があります`);
            }
          }
        }
      }
      const maxSells = fishSell["max-sells-per-minute"];
      if (maxSells !== undefined && maxSells !== null && !(isInteger(maxSells) && maxSells >= 0)) {
        errors.push("fish-sell.max-sells-per-minute: 0以上の整数である必要があります");
      }
    }
  }
  const fishing = data.fishing;
  if (fishing !== undefined && fishing !== null) {
    if (!isPlainObject(fishing)) { errors.push("fishing はマップである必要があります"); }
    else {
      const biomes = fishing["ocean-biomes"];
      if (biomes !== undefined && biomes !== null) {
        if (!Array.isArray(biomes)) { errors.push("fishing.ocean-biomes は配列である必要があります"); }
        else {
          biomes.forEach((b, i) => {
            if (typeof b !== "string" || !b.trim()) {
              errors.push(`fishing.ocean-biomes[${i}]: 空でない文字列である必要があります`);
            }
          });
        }
      }
    }
  }
}

// ---- glyph-damage-boost.yml (tf-glyph-damage-boost) ----
// boosted-glyphs: グリフID文字列の配列。有効なグリフIDかどうかは glyphs.yml 側の変更で
// 動くため、あえてここでは照合しない(疎結合を維持するため空でない文字列であることのみ検査)。
function validateTfGlyphDamageBoost(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const list = data["boosted-glyphs"];
  if (list === undefined || list === null) return;
  if (!Array.isArray(list)) { errors.push("boosted-glyphs は配列である必要があります"); return; }
  list.forEach((v, i) => {
    if (typeof v !== "string" || !v.trim()) {
      errors.push(`boosted-glyphs[${i}]: 空でない文字列である必要があります`);
    }
  });
}

// ---- ArsPaper config.yml (ars-config) ----
// mana.source-auto-consume.items.<itemId>: 1個あたり消費マナ(正の整数)
function validateArsConfig(data, errors) {
  if (data === null) return;
  if (!isPlainObject(data)) { errors.push("ルートはマップである必要があります"); return; }
  const mana = data.mana;
  if (mana === undefined || mana === null) return;
  if (!isPlainObject(mana)) { errors.push("mana はマップである必要があります"); return; }
  const sac = mana["source-auto-consume"];
  if (sac === undefined || sac === null) return;
  if (!isPlainObject(sac)) { errors.push("mana.source-auto-consume はマップである必要があります"); return; }
  const items = sac.items;
  if (items === undefined || items === null) return;
  if (!isPlainObject(items)) { errors.push("mana.source-auto-consume.items はマップである必要があります"); return; }
  for (const [id, amount] of Object.entries(items)) {
    if (!id || !id.trim()) {
      errors.push("mana.source-auto-consume.items: キー(アイテムID)は空でない文字列である必要があります");
    }
    if (!(isInteger(amount) && amount > 0)) {
      errors.push(`mana.source-auto-consume.items.${id}: 1以上の整数である必要があります`);
    }
  }
}

// プレイヤー基礎ステ (combat/base-stats.yml): base-stats は「ステ名→数値」の平坦マップのみ。
function validateTfBaseStats(data, errors) {
  if (data == null || typeof data !== "object" || Array.isArray(data)) {
    errors.push("ルートはオブジェクトである必要があります");
    return;
  }
  const map = data["base-stats"];
  if (map == null) return; // 未設定 = 全ステ空欄(バニラ)
  if (typeof map !== "object" || Array.isArray(map)) {
    errors.push("base-stats はステ名→数値のマップである必要があります");
    return;
  }
  for (const [key, value] of Object.entries(map)) {
    if (typeof value !== "number" || !Number.isFinite(value)) {
      errors.push(`base-stats.${key}: 有限の数値である必要があります`);
    }
  }
}

// 総合ステータス上限 (combat/stat-caps.yml): T8 (2026-07-26新設)。
// stat-caps: はステ名→数値の平坦マップ(未記載=上限なし。明示的な0を含め、キーがあれば有効な上限)。
// base-stats とは逆の規約で「キーが無い」ことに意味があるため、ここでは値の型だけ検証し、
// 「キーが無ければOK」という base-stats と同じ緩さを保つ(キーの許可リスト検証はしない = 将来
// Java側で効くキーが増えても editor 側の追随なしに保存できるようにするため)。
// gathering-efficiency-max-enchant-level はルート直下の独立キー(整数。0以下=無制限)。
function validateTfStatCaps(data, errors) {
  if (data == null || typeof data !== "object" || Array.isArray(data)) {
    errors.push("ルートはオブジェクトである必要があります");
    return;
  }
  const caps = data["stat-caps"];
  if (caps != null) {
    if (typeof caps !== "object" || Array.isArray(caps)) {
      errors.push("stat-caps はステ名→数値のマップである必要があります");
    } else {
      for (const [key, value] of Object.entries(caps)) {
        if (typeof value !== "number" || !Number.isFinite(value)) {
          errors.push(`stat-caps.${key}: 有限の数値である必要があります`);
        }
      }
    }
  }
  const bookshelfLevel = data["gathering-efficiency-max-enchant-level"];
  if (bookshelfLevel !== undefined && bookshelfLevel !== null && !isInteger(bookshelfLevel)) {
    errors.push("gathering-efficiency-max-enchant-level: 整数である必要があります(0以下=無制限)");
  }
}

function validate(schemaType, data) {
  const errors = [];
  switch (schemaType) {
    case "tf-base-stats":
      validateTfBaseStats(data, errors);
      break;
    case "tf-stat-caps":
      validateTfStatCaps(data, errors);
      break;
    case "item-stats":
      validateItemStats(data, errors);
      break;
    case "catalog":
      validateCatalog(data, errors);
      break;
    case "ars-recipes":
      validateArsRecipes(data, errors);
      break;
    case "ars-materials":
      validateArsMaterials(data, errors);
      break;
    case "ars-threads":
      validateArsThreads(data, errors);
      break;
    case "tf-lore":
      validateTfLore(data, errors);
      break;
    case "tf-skilltree":
      validateTfSkillTree(data, errors);
      break;
    case "tf-craft-quality":
      validateTfCraftQuality(data, errors);
      break;
    case "tf-skill-exp":
      validateTfSkillExp(data, errors);
      break;
    case "tf-tool-enchants":
      validateTfToolEnchants(data, errors);
      break;
    case "tf-quality":
      validateTfQuality(data, errors);
      break;
    case "tf-quality-tiers":
      validateTfQualityTiers(data, errors);
      break;
    case "tf-gacha":
      validateTfGacha(data, errors);
      break;
    case "tf-combat-damage":
      validateTfCombatDamage(data, errors);
      break;
    case "ars-thread-sets":
      validateArsThreadSets(data, errors);
      break;
    case "ars-thread-rolls":
      validateArsThreadRolls(data, errors);
      break;
    case "tf-attribute-map":
      validateTfAttributeMap(data, errors);
      break;
    case "tf-item-categories":
      validateTfItemCategories(data, errors);
      break;
    case "ars-glyphs":
      validateArsGlyphs(data, errors);
      break;
    case "ars-spellbooks":
      validateArsSpellbooks(data, errors);
      break;
      case "tf-mob-types":
      validateTfMobTypes(data, errors);
      break;
    case "tf-mob-level-table":
      validateTfMobLevelTable(data, errors);
      break;
    case "tf-mob-abilities":
      validateTfMobAbilities(data, errors);
      break;
    case "tf-mob-overrides":
      validateTfMobOverrides(data, errors);
      break;
    case "ars-sourcejars":
      validateArsSourceJars(data, errors);
      break;
    case "ars-loot-tables":
      validateArsLootTables(data, errors);
      break;
    case "ars-sourcelinks":
      validateArsSourceLinks(data, errors);
      break;
    case "tf-crafting-features":
      validateTfCraftingFeatures(data, errors);
      break;
    case "tf-enchant-gimmick":
      validateTfEnchantGimmick(data, errors);
      break;
    case "tf-brew-gimmick":
      validateTfBrewGimmick(data, errors);
      break;
    case "tf-use-requirements":
      validateTfUseRequirements(data, errors);
      break;
    case "tf-afk":
      validateTfAfk(data, errors);
      break;
    case "tf-material-lists":
      validateTfMaterialLists(data, errors);
      break;
    case "external-items":
      validateExternalItems(data, errors);
      break;
    case "tf-special-rewards":
      validateTfSpecialRewards(data, errors);
      break;
    case "tf-achievements":
      validateTfAchievements(data, errors);
      break;
    case "tf-collection":
      validateTfCollection(data, errors);
      break;
    case "tf-food-gimmick":
      validateTfFoodGimmick(data, errors);
      break;
    case "tf-fishing-gimmick":
      validateTfFishingGimmick(data, errors);
      break;
    case "tf-glyph-damage-boost":
      validateTfGlyphDamageBoost(data, errors);
      break;
    case "ars-config":
      validateArsConfig(data, errors);
      break;
    case "tf-smithing-gimmick":
      validateTfSmithingGimmick(data, errors);
      break;
    case "tf-mining-gimmick":
      validateTfMiningGimmick(data, errors);
      break;
    case "tf-mob-import":
      validateTfMobImport(data, errors);
      break;
    case "generic":
    default:
      validateGeneric(data, errors);
  }
  return errors;
}

// ---- item-stats × lore.yml のクロスファイル検証 ----
// 乗算レイヤはステータスごとの定義 (lore.yml multiplier-layers[].stat) なので、
// item-stats の multipliers.<layerId> が (1) 未定義レイヤ、(2) 基準ステ不一致のステ
// を含む場合はエラー。stat 未指定の旧形式レイヤは全ステ許容 (後方互換)。
// layers は lore.yml の multiplier-layers 配列 (読めなければ呼び出し側が [] を渡す)。
function validateItemStatsLayerRefs(data, layers) {
  const errors = [];
  if (!isPlainObject(data) || !isPlainObject(data.items)) return errors;
  const norm = (k) => String(k || "").trim().toLowerCase().replace(/_/g, "-");
  const defs = new Map();
  for (const l of Array.isArray(layers) ? layers : []) {
    if (isPlainObject(l) && l.id) defs.set(String(l.id), typeof l.stat === "string" ? l.stat : "");
  }
  for (const [key, entry] of Object.entries(data.items)) {
    if (!isPlainObject(entry) || !isPlainObject(entry.multipliers)) continue;
    for (const [layerId, layer] of Object.entries(entry.multipliers)) {
      if (layerId.startsWith("__")) continue; // __unset__ は validateItemStats が担当
      if (!defs.has(layerId)) {
        errors.push(`items.${key}.multipliers.${layerId}: 未定義の乗算レイヤです`
          + " (ロア表示configの multiplier-layers に定義してください)");
        continue;
      }
      const baseStat = defs.get(layerId);
      if (!baseStat || !isPlainObject(layer)) continue;
      for (const secKey of ["fixed", "per-quality", "random"]) {
        const sec = layer[secKey];
        if (!isPlainObject(sec)) continue;
        for (const stat of Object.keys(sec)) {
          if (norm(stat) !== norm(baseStat)) {
            errors.push(`items.${key}.multipliers.${layerId}.${secKey}.${stat}: `
              + `レイヤ「${layerId}」の基準ステータスは ${baseStat} のため、このステには使えません`);
          }
        }
      }
    }
  }
  return errors;
}

// ---- skilltree/*.yml × lore.yml のクロスファイル検証 ----
function validateSkillTreeLayerRefs(data, layers) {
  const errors = [];
  if (!isPlainObject(data)) return errors;
  const norm = (k) => String(k || "").trim().toLowerCase().replace(/_/g, "-");
  const defs = new Map();
  for (const layer of Array.isArray(layers) ? layers : []) {
    if (isPlainObject(layer) && layer.id) {
      defs.set(String(layer.id), typeof layer.stat === "string" ? layer.stat : "");
    }
  }
  const owners = [];
  if (isPlainObject(data.prestige)) owners.push(["prestige", data.prestige]);
  if (isPlainObject(data.nodes)) {
    for (const [id, node] of Object.entries(data.nodes)) {
      if (isPlainObject(node)) owners.push([`nodes.${id}`, node]);
    }
  }
  for (const [prefix, owner] of owners) {
    if (!isPlainObject(owner.multipliers)) continue;
    for (const [layerId, stats] of Object.entries(owner.multipliers)) {
      if (layerId.startsWith("__")) continue;
      if (!defs.has(layerId)) {
        errors.push(`${prefix}.multipliers.${layerId}: 未定義の乗算レイヤです`
          + " (ロア表示configの multiplier-layers に定義してください)");
        continue;
      }
      const baseStat = defs.get(layerId);
      if (!baseStat || !isPlainObject(stats)) continue;
      for (const stat of Object.keys(stats)) {
        if (norm(stat) !== norm(baseStat)) {
          errors.push(`${prefix}.multipliers.${layerId}.${stat}: `
            + `レイヤ「${layerId}」の基準ステータスは ${baseStat} のため、このステには使えません`);
        }
      }
    }
  }
  return errors;
}

module.exports = {
  validate,
  validateItemStatsLayerRefs,
  validateSkillTreeLayerRefs,
  BIND_TYPES,
  APPLIES_TO
};
