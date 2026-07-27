"use strict";

// Blockbench プロジェクトファイル(.bbmodel 相当のJSON) → Java版アイテムモデルJSON への変換。
//
// なぜ必要か: エディタの「上級: カスタムモデル」欄に、Blockbench から Export したモデルではなく
// プロジェクトファイルをそのまま投入する事故が実際に起きた(2026-07-25)。旧実装は
// validateModelJsonShape が「parent/elements/textures のいずれかがあれば通す」ため素通りし、
// さらに collectTextureRefs が `textures` が配列のときスキップする実装だったため参照0件=検証成功
// となり、台帳に customModel:true が付いて「成功」表示のままゲーム内では描画されなかった。
//
// 変換仕様の根拠は本パック内の実データ(Blockbench の Java Item Model エクスポート結果)である
// models/item/wooden_dagger.json 等 28 件:
//   - faces[].texture は数値インデックスではなく "#<id>" 形式の文字列
//   - 参照先は root の textures マップ ({"2": "trinityforge:item/<name>", "particle": ...})
//   - UV は 0..16 に正規化せず、UV空間が16でない場合は texture_size: [w,h] を添える
//     (本パックの 28 モデルが texture_size: [32,32] で実際に動作している)
//   - 出力する root フィールドは credit / textures / texture_size / elements / gui_light /
//     display / groups のみ

const MC_AXES = ["x", "y", "z"];
// Java版のモデル座標は -16..32 の範囲外を受け付けない (Java Block/Item の 3x3x3 制限)。
const MC_COORD_MIN = -16;
const MC_COORD_MAX = 32;

// 回転制約は対象バージョン(このパックは 1.21.11)で大きく緩和されている。
//   1.21.6  : 回転角の「22.5の倍数」制限が撤廃
//   1.21.11 : 複数軸回転に対応 (rotation.x/y/z、X→Y→Zの順で適用)。[-45,45] の角度制限も撤廃
// したがって角度の丸めチェックや単軸チェックは行わない。単軸のときだけは旧記法
// ({axis, angle}) で書き出す — 旧記法は現在も有効で、古いクライアントでも読めるため。
// (両方の記法が同時にあると旧記法が優先されるので、混在させないこと。)
const ASSET_NAME_RE = /^[a-z0-9_.-]+$/;
const DATA_URL_PNG_RE = /^data:image\/png;base64,/i;

/**
 * Blockbench のプロジェクトファイルらしさを判定する。
 * Export 済みの Java モデルは meta/outliner/resolution を持たず、textures はオブジェクトなので
 * ここで false になる。
 *
 * @param {unknown} json
 * @returns {boolean}
 */
function isBlockbenchProject(json) {
  if (!json || typeof json !== "object" || Array.isArray(json)) return false;
  if (json.meta && typeof json.meta === "object" && json.meta.format_version) return true;
  if (Array.isArray(json.textures)) return true;
  if (Array.isArray(json.outliner)) return true;
  return false;
}

/** textures[].name ("test.png" 等) を assetName 規約へ寄せる。規約外文字は "_" に落とす。 */
function normalizeTextureName(rawName, index) {
  const base = String(rawName || "")
    .replace(/\.[a-z0-9]+$/i, "")
    .toLowerCase()
    .replace(/[^a-z0-9_.-]/g, "_")
    .replace(/^\.+/, "");
  if (!base || !ASSET_NAME_RE.test(base)) return `texture_${index}`;
  return base;
}

/**
 * 埋め込み data URL から PNG バッファを取り出す。
 * Blockbench は textures[].source に "data:image/png;base64,..." を持つ。
 */
function extractEmbeddedTextures(project, errors) {
  const list = Array.isArray(project.textures) ? project.textures : [];
  const out = [];
  const usedNames = new Set();
  list.forEach((tex, index) => {
    const source = tex && typeof tex.source === "string" ? tex.source : "";
    // Blockbench の texture id は文字列("0")のことも数値のこともある。faces[].texture との
    // 突き合わせに使うため、ここでは常に文字列へ寄せる。
    const id = tex && tex.id != null ? String(tex.id) : String(index);
    let name = normalizeTextureName(tex && tex.name, index);
    while (usedNames.has(name)) name = `${name}_${index}`;
    usedNames.add(name);

    if (!DATA_URL_PNG_RE.test(source)) {
      // 画像が埋め込まれていない(外部ファイル参照)プロジェクト。PNGを別途アップロードすれば
      // 成立するため、ここではエラーにせず「変換後の参照だけ作る」扱いにする。
      out.push({ id, name, pngBuffer: null, embedded: false, index });
      return;
    }
    const base64 = source.slice(source.indexOf(",") + 1);
    let pngBuffer;
    try {
      pngBuffer = Buffer.from(base64, "base64");
    } catch (_) {
      errors.push(`テクスチャ "${tex && tex.name}" の埋め込み画像をデコードできませんでした`);
      return;
    }
    out.push({ id, name, pngBuffer, embedded: true, index, particle: !!(tex && tex.particle) });
  });
  return out;
}

/**
 * bbmodel の element.rotation ([x,y,z] 配列) を Java版の rotation オブジェクトへ変換する。
 * 単軸なら旧記法 {origin, axis, angle}、複数軸なら 1.21.11 の新記法 {origin, x, y, z} を返す。
 * 角度の丸め・軸数のチェックはしない(1.21.11 では制約が撤廃されているため)。
 */
function convertRotation(element, elementLabel, errors) {
  const rot = element.rotation;
  if (!Array.isArray(rot) || rot.every((v) => !v)) return null;
  if (rot.some((v) => typeof v !== "number" || !Number.isFinite(v))) {
    errors.push(`${elementLabel}: rotation に数値でない値が含まれています`);
    return null;
  }

  const origin = Array.isArray(element.origin) && element.origin.length === 3
    ? element.origin.map(Number)
    : [8, 8, 8];

  const nonZero = [];
  rot.forEach((value, axisIndex) => {
    if (value !== 0) nonZero.push({ axis: MC_AXES[axisIndex], angle: value });
  });
  if (nonZero.length === 1) {
    return { origin, axis: nonZero[0].axis, angle: nonZero[0].angle };
  }
  return { origin, x: rot[0], y: rot[1], z: rot[2] };
}

/** from/to がJava版の許容範囲(-16..32)に収まっているか。 */
function validateBounds(element, elementLabel, errors) {
  const coords = [];
  if (Array.isArray(element.from)) coords.push(...element.from);
  if (Array.isArray(element.to)) coords.push(...element.to);
  for (const value of coords) {
    if (typeof value !== "number" || !Number.isFinite(value)) {
      errors.push(`${elementLabel}: from/to に数値でない値が含まれています`);
      return;
    }
    if (value < MC_COORD_MIN || value > MC_COORD_MAX) {
      errors.push(
        `${elementLabel}: 座標 ${value} がJava版の許容範囲 (${MC_COORD_MIN}〜${MC_COORD_MAX}) を超えています`
      );
      return;
    }
  }
}

/** faces[].texture(数値/文字列インデックス) を "#<id>" 形式へ。未解決なら null。 */
function resolveFaceTexture(faceTexture, texturesById) {
  if (faceTexture == null) return null;
  const key = String(faceTexture).replace(/^#/, "");
  return texturesById.has(key) ? `#${key}` : null;
}

function convertElements(project, texturesById, errors) {
  const source = Array.isArray(project.elements) ? project.elements : [];
  const out = [];
  source.forEach((element, index) => {
    if (!element || typeof element !== "object") return;
    // Blockbench の locator / null_object 等はメッシュではないので出力しない。
    if (element.type && element.type !== "cube") return;
    if (element.export === false) return;

    // Blockbench の既定名は全要素で同じ("bb_main" 等)になりがちなので、名前だけでは
    // どれを指すか分からない。必ず通し番号を添えて特定できるようにする。
    const label = `要素${index + 1} "${element.name || "(無名)"}"`;
    validateBounds(element, label, errors);

    const faces = {};
    const sourceFaces = element.faces && typeof element.faces === "object" ? element.faces : {};
    for (const [side, face] of Object.entries(sourceFaces)) {
      if (!face || typeof face !== "object") continue;
      const textureRef = resolveFaceTexture(face.texture, texturesById);
      // texture 未割当の面 (Blockbench では texture: null) は出力しない。出力すると
      // Java版が missing texture で紫黒描画になるため、面ごと落とす方が実害が小さい。
      if (!textureRef) continue;
      const converted = { uv: Array.isArray(face.uv) ? face.uv.map(Number) : [0, 0, 16, 16], texture: textureRef };
      if (typeof face.rotation === "number" && face.rotation !== 0) converted.rotation = face.rotation;
      if (typeof face.tintindex === "number") converted.tintindex = face.tintindex;
      if (typeof face.cullface === "string") converted.cullface = face.cullface;
      faces[side] = converted;
    }
    if (Object.keys(faces).length === 0) return;

    const converted = { from: element.from, to: element.to, faces };
    if (typeof element.name === "string" && element.name) converted.name = element.name;
    if (element.shade === false) converted.shade = false;
    const rotation = convertRotation(element, label, errors);
    if (rotation) converted.rotation = rotation;
    out.push(converted);
  });
  return out;
}

/**
 * Blockbench プロジェクト → Java版アイテムモデル。
 *
 * @param {object} project パース済みの .bbmodel JSON
 * @param {(name: string) => string} texturePathOf assetName → モデルが参照するテクスチャパス
 * @returns {{modelJson: object, textures: Array<{name: string, pngBuffer: Buffer}>, warnings: string[]}}
 * @throws {Error} Java版で表現できない構造を含む場合 (err.conversionErrors に全件を格納)
 */
function convertBlockbenchProject(project, texturePathOf) {
  const errors = [];
  const warnings = [];

  const textures = extractEmbeddedTextures(project, errors);
  if (textures.length === 0) {
    errors.push("プロジェクトにテクスチャが1枚も含まれていません");
  }
  const texturesById = new Map(textures.map((t) => [t.id, t]));

  const elements = convertElements(project, texturesById, errors);
  if (elements.length === 0 && errors.length === 0) {
    errors.push("出力できる立方体要素がありません（テクスチャ未割当の面のみ、または要素が0件）");
  }

  if (errors.length > 0) {
    const err = new Error(
      "Blockbenchプロジェクトの変換に失敗しました:\n- " + errors.join("\n- ")
    );
    err.conversionErrors = errors;
    throw err;
  }

  const textureMap = {};
  for (const tex of textures) {
    textureMap[tex.id] = texturePathOf(tex.name);
  }
  const particle = textures.find((t) => t.particle) || textures[0];
  if (particle) textureMap.particle = texturePathOf(particle.name);

  const modelJson = {
    credit: "Converted from Blockbench project by TrinityForge Config Editor",
    textures: textureMap,
    elements
  };

  // UV空間が16x16でない場合のみ texture_size を添える(本パックの既存28モデルと同じ方式)。
  const resolution = project.resolution || {};
  const uvWidth = Number(resolution.width) || 16;
  const uvHeight = Number(resolution.height) || 16;
  if (uvWidth !== 16 || uvHeight !== 16) {
    modelJson.texture_size = [uvWidth, uvHeight];
  }

  if (project.display && typeof project.display === "object") modelJson.display = project.display;
  // Blockbench はプロジェクト上 front_gui_light(真偽値) で持ち、Java エクスポート時に
  // gui_light: "front" へ変換する。これを写さないとインベントリ内の陰影が Blockbench の
  // プレビューと食い違う(側面ライティングになる)。
  if (project.gui_light) {
    modelJson.gui_light = project.gui_light;
  } else if (project.front_gui_light === true) {
    modelJson.gui_light = "front";
  }

  const notEmbedded = textures.filter((t) => !t.embedded);
  for (const tex of notEmbedded) {
    warnings.push(
      `テクスチャ "${tex.name}" はプロジェクトに埋め込まれていません。同名のPNG (${tex.name}.png) を併せてアップロードするか、パック内に既存である必要があります`
    );
  }

  return {
    modelJson,
    textures: textures.filter((t) => t.embedded).map((t) => ({ name: t.name, pngBuffer: t.pngBuffer })),
    warnings
  };
}

module.exports = {
  isBlockbenchProject,
  convertBlockbenchProject,
  normalizeTextureName,
  MC_COORD_MIN,
  MC_COORD_MAX
};
