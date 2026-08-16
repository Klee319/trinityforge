"""バニラの防具レイヤーを下地に、inv テクスチャの配色で塗り替えた「装備したときの見た目」を起こす。

⚠⚠ 2026-08-16 に方式を全面的に入れ替えた（ユーザー報告「ヘルメットが頭全体を覆う」の修正）。
   旧版は 64x32 のキャンバスへ UV 矩形を自前で塗り分けていたが、**全ピクセルを不透明**に
   していたのが致命的だった。バニラの装備レイヤーは 7 割が透明で、その透明部分が
   「顔が見える」「ブーツが足首で止まる」「腕の素肌が出る」を作っている。
   モデルはバニラそのもの（equipment json は humanoid / humanoid_leggings を指すだけ）なので、
   壊れていたのは常にテクスチャのアルファだった。
   現在はバニラの同レイヤー PNG を下地に読み、**アルファを1ピクセルも変えずに**
   不透明部分の色だけをセットのパレットへ写像する。バニラの陰影もそのまま残る。


なぜ必要か
----------
``build_equipment_assets.py`` の docstring にある通り、着たときの見た目 (体に乗るレイヤー) は
``custom-model-data`` では一切変わらない。決めているのは ``minecraft:equippable`` の
``asset_id`` で、次の3点セットが揃って初めて効く:

1. サーバがアイテムへ ``equippable{asset_id: trinityforge:<セット名>}`` を書く (TF 側)
2. パックに ``assets/trinityforge/equipment/<セット名>.json`` (レイヤー定義) がある
3. パックに ``assets/trinityforge/textures/entity/equipment/humanoid/<セット名>.png`` と
   ``.../humanoid_leggings/<セット名>.png`` (脚だけ別レイヤー) がある

2 と 3 の配線は ``build_equipment_assets.py`` が既に持っている（レイヤー PNG が
所定パスに揃っているセットだけを自動で配線する）。このスクリプトはその「所定パスの PNG」を
**専用の手描きアセットが無いセットのために、既存の inv (インベントリ表示) テクスチャの配色から
機械的に生成する**。

⚠ これはあくまで inv テクスチャの色から起こした「仮の見た目」であり、デザイン的な正解ではない。
   将来 ``resourcepack/trinityforge-items/assets/trinityforge/textures/entity/equipment/<layer>/<asset>.png``
   に手描きのレイヤー PNG を同じパスへ置けば、そちらが上書きされて優先される
   （このスクリプトはファイルが既にあっても常に上書きする点に注意 — 手描き版を置いた後は
   再実行しないこと）。

方針
----
* **形はバニラから借りる。** そのアイテム自身の material (``LEATHER_HELMET`` なら LEATHER) に
  対応するバニラの装備レイヤー PNG をクライアント jar から読み、下地にする。
  魔導ローブは LEATHER なので柔らかい形、終盤の重装は NETHERITE の重厚な形になる ——
  「どの系統にどの形を当てるか」を人が決める必要がない。
* **アルファは1ピクセルも変えない。** 透明部分がバニラの見た目そのものなので、ここを
  塗ると必ず壊れる（旧版の全面不透明が「頭全体を覆うヘルメット」の原因だった）。
* 色は単純平均だと灰色に潰れるので、明度でソートして暗部/主色/ハイライトの3階調を
  彩度で重み付けした加重平均で抽出する（乱数不使用・決定的）。
* セットのメンバー item_id と同名の inv PNG
  (``resourcepack/trinityforge-items/assets/trinityforge/textures/item/<item_id>.png``) が
  **1枚も無いセットは生成対象外**にする（色の手がかりが無いので無から作らない）。
* 生成対象でも、``collect_sets()`` が返す ``layers`` に無いレイヤーは作らない
  （humanoid だけのセットに humanoid_leggings を作らない）。

使い方
------
    python resourcepack/build_armor_layers_from_items.py            # 生成する
    python resourcepack/build_armor_layers_from_items.py --dry-run  # 何を生成する予定かだけ出す
    python resourcepack/build_armor_layers_from_items.py --vanilla-jar <path>  # 下地の jar を明示

下地のバニラ PNG は ``%APPDATA%/.minecraft/versions/<ver>/<ver>.jar`` から読む
（**リポジトリへは commit しない** — public リポジトリなので Mojang のアセットをそのまま
置かず、色を差し替えた派生物だけを置く）。

生成後は ``python resourcepack/build_equipment_assets.py`` を実行して初めて実際に配線される
（このスクリプトは PNG を置くだけで、equipment json や台帳は書かない）。
"""

from __future__ import annotations

import argparse
import colorsys
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
import build_equipment_assets as bea  # noqa: E402  (sys.path 設定の後でないと import できない)

PACK = HERE / "trinityforge-items"
ITEM_TEXTURES = PACK / "assets" / "trinityforge" / "textures" / "item"

CANVAS_W, CANVAS_H = 64, 32
LEGGINGS_DARKEN = 0.85  # humanoid_leggings は humanoid よりわずかに暗くする

# 3階調を切り出すときの、彩度による重み付け。彩度0(完全な灰色)でも重み0にはしない
# (灰色しか無い素材で全ピクセル重み0になり平均が壊れるのを避ける)。
SAT_WEIGHT_BASE = 0.2
SAT_WEIGHT_SCALE = 0.8


def item_texture_path(item_id: str) -> Path:
    return ITEM_TEXTURES / f"{item_id}.png"


def existing_item_textures(item_ids: list[str]) -> list[Path]:
    """与えた item_id のうち、実在する inv PNG のパスだけを id 順で返す。"""
    paths = []
    for item_id in sorted(item_ids):
        p = item_texture_path(item_id)
        if p.is_file():
            paths.append(p)
    return paths


def pick_source_textures(entry: dict[str, object], layer: str) -> list[Path]:
    """あるレイヤーの色の元にする inv PNG を選ぶ。

    優先順位:
      1. そのレイヤーを担当するメンバー (helmet/chestplate/boots は humanoid、
         leggings は humanoid_leggings) の inv PNG。
      2. それが1枚も無ければ、セット内の他メンバーの inv PNG で代用する
         (無いよりは色の手がかりがあるほうがマシ、という判断)。
    """
    members = entry["members"]
    assert isinstance(members, dict)

    same_layer_ids = [
        item_id for item_id, info in members.items() if info["layer"] == layer
    ]
    paths = existing_item_textures(same_layer_ids)
    if paths:
        return paths

    # 代用: セット全メンバーのうち存在する inv PNG を全部使う。
    return existing_item_textures(list(members))


def set_has_any_inv_texture(entry: dict[str, object]) -> list[str]:
    """このセットに inv PNG が存在するメンバー id 一覧を返す(空なら生成対象外)。"""
    members = entry["members"]
    assert isinstance(members, dict)
    return [item_id for item_id in sorted(members) if item_texture_path(item_id).is_file()]


def extract_palette(paths: list[Path]) -> tuple[tuple[int, int, int], tuple[int, int, int], tuple[int, int, int]]:
    """inv PNG 群から (暗部, 主色, ハイライト) の3階調を決定的に抽出する。

    単純平均は禁止（透明な背景を除いても色数が少ないアイコンだと簡単に灰色へ潰れる）。
    ここでは:
      1. alpha>=128 の不透明ピクセルだけを対象にする。
      2. 明度(HSV の V)でソートし、ピクセル数で3等分する(暗い側/中間/明るい側)。
      3. 各グループを「彩度が高いほど重い」加重平均で1色に畳む
         (灰色寄りのピクセルに主色が引っ張られないようにするため)。
    乱数は一切使わないので、同じ入力 PNG なら常に同じ3色が出る。
    """
    pixels: list[tuple[int, int, int, float]] = []  # (r, g, b, v)
    for path in paths:
        with Image.open(path) as im:
            rgba = im.convert("RGBA")
            for r, g, b, a in rgba.getdata():
                if a < 128:
                    continue
                _, _, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
                pixels.append((r, g, b, v))

    if not pixels:
        # 呼び出し側で「inv PNG が1枚もあるセットだけ」を対象にしているので通常は来ないが、
        # 全ピクセルが透明という極端なケースの保険として中間グレーを返す。
        mid = (128, 128, 128)
        return mid, mid, mid

    pixels.sort(key=lambda p: p[3])  # 明度で昇順
    n = len(pixels)
    third = max(1, n // 3)
    buckets = [pixels[:third], pixels[third : 2 * third], pixels[2 * third :]]
    # 端数吸収: 3個未満で空バケットができたら隣を複製する。
    for i in range(3):
        if not buckets[i]:
            buckets[i] = buckets[min(i, len(buckets) - 1) - 1] if i > 0 else buckets[i + 1]

    def weighted_average(bucket: list[tuple[int, int, int, float]]) -> tuple[int, int, int]:
        total_weight = 0.0
        r_sum = g_sum = b_sum = 0.0
        for r, g, b, _ in bucket:
            _, s, _ = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            weight = SAT_WEIGHT_BASE + SAT_WEIGHT_SCALE * s
            total_weight += weight
            r_sum += r * weight
            g_sum += g * weight
            b_sum += b * weight
        if total_weight <= 0:
            n_b = len(bucket)
            return (
                sum(p[0] for p in bucket) // n_b,
                sum(p[1] for p in bucket) // n_b,
                sum(p[2] for p in bucket) // n_b,
            )
        return (
            int(round(r_sum / total_weight)),
            int(round(g_sum / total_weight)),
            int(round(b_sum / total_weight)),
        )

    dark = weighted_average(buckets[0])
    main = weighted_average(buckets[1])
    light = weighted_average(buckets[2])
    return dark, main, light


def scale_color(color: tuple[int, int, int], factor: float) -> tuple[int, int, int]:
    return tuple(max(0, min(255, int(round(c * factor)))) for c in color)  # type: ignore[return-value]


def lerp_color(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))  # type: ignore[return-value]


# ---------------------------------------------------------------------------------------------
# バニラのレイヤー PNG を「形の下地」にする
# ---------------------------------------------------------------------------------------------
#
# ⚠⚠ 2026-08-16 の作り直し。以前はここで 64x32 のキャンバスを UV 矩形ごとに塗り分けて
#    自前で絵を起こしていたが、**全ピクセルを alpha=255 で塗っていた**のが致命的だった。
#
#    バニラの装備レイヤーは 7 割が透明で、その透明部分こそが見た目を作っている:
#      humanoid/diamond.png   不透明 632/2048 = 30.9%
#      humanoid/netherite.png 不透明 699/2048 = 34.1%
#      humanoid_leggings/diamond.png 不透明 280/2048 = 13.7%
#    全面不透明にすると、同じバニラのモデルに描いても
#      ・ヘルメット → 顔の開口が埋まって<頭のボックス全体を覆う無地の箱>になる
#      ・ブーツ     → 脚の矩形が全部塗られて<腰から足先までの脚全体>になる
#      ・チェスト   → 腕も全面ベタ塗りになる
#    という壊れ方をする(実際にユーザー報告で挙がった症状そのもの)。
#
#    そこで「形はバニラのまま、色だけ差し替える」へ方針を変えた。バニラの同レイヤー PNG を
#    下地として読み、**alpha をそのまま引き継ぎ**、不透明ピクセルの明度だけをセットの
#    3階調パレットへ写像する。バニラの陰影(リベット・稜線・縁取り)もそのまま残る。
#
# どのバニラ防具を下地にするかは、**そのアイテム自身の material** で決める。
# catalog.yml の防具は LEATHER_* / DIAMOND_* / NETHERITE_* のいずれかを土台にしているので、
# 「自分が実際に使っているバニラ防具の形」をそのまま使うのが最も忠実で、判断も要らない
# (魔導ローブは LEATHER なので柔らかい形、終盤の重装は NETHERITE の重厚な形になる)。
VANILLA_BASE_BY_MATERIAL = {
    "LEATHER": "leather",
    "CHAINMAIL": "chainmail",
    "COPPER": "copper",
    "IRON": "iron",
    "GOLDEN": "gold",  # バニラのテクスチャ名は golden ではなく gold
    "DIAMOND": "diamond",
    "NETHERITE": "netherite",
    "TURTLE": "turtle_scute",
}

VANILLA_TEXTURE_DIR = "assets/minecraft/textures/entity/equipment"

# クライアント jar の探索先。--vanilla-jar で明示指定もできる。
# ⚠ バニラの PNG はこのリポジトリへ commit しない(public リポジトリなので Mojang の
#   アセットをそのまま置かない)。生成時に jar から読むだけで、出力は色を差し替えた派生物。
VANILLA_JAR_VERSIONS = ("1.21.11", "1.21.10", "1.21.8")


class VanillaBaseMissing(RuntimeError):
    """下地にするバニラ PNG が手に入らない。"""


def find_vanilla_jar(explicit: str | None) -> Path:
    if explicit:
        p = Path(explicit)
        if not p.is_file():
            raise VanillaBaseMissing(f"--vanilla-jar に指定された jar がありません: {p}")
        return p

    import os

    appdata = os.environ.get("APPDATA")
    roots = []
    if appdata:
        roots.append(Path(appdata) / ".minecraft" / "versions")
    roots.append(Path.home() / ".minecraft" / "versions")

    for root in roots:
        for version in VANILLA_JAR_VERSIONS:
            candidate = root / version / f"{version}.jar"
            if candidate.is_file():
                return candidate
    raise VanillaBaseMissing(
        "バニラのクライアント jar が見つかりません。装備レイヤーの<形>はバニラの PNG を"
        " 下地にして起こすので、これが無いと生成できません。\n"
        "  探した場所: " + ", ".join(str(r) for r in roots) + "\n"
        "  探したバージョン: " + ", ".join(VANILLA_JAR_VERSIONS) + "\n"
        "  --vanilla-jar <path> で明示指定もできます。"
    )


def load_vanilla_layer(jar: Path, layer: str, base: str) -> Image.Image:
    """クライアント jar からバニラの装備レイヤー PNG を読む。"""
    import io
    import zipfile

    name = f"{VANILLA_TEXTURE_DIR}/{layer}/{base}.png"
    with zipfile.ZipFile(jar) as zf:
        try:
            raw = zf.read(name)
        except KeyError as exc:
            raise VanillaBaseMissing(f"{jar.name} に {name} がありません") from exc
    with Image.open(io.BytesIO(raw)) as im:
        return im.convert("RGBA")


def base_material_for(entry: dict[str, object], layer: str) -> str | None:
    """そのレイヤーを担当するメンバーが使っているバニラ防具の系統名(LEATHER 等)を返す。

    catalog.yml の実データではセット内・レイヤー内で material 系統が混ざることは無いが、
    万一混ざったら「一番多いもの」を採る(決定的にするため、同数なら名前順で先のもの)。
    """
    members = entry["members"]
    assert isinstance(members, dict)
    counts: dict[str, int] = {}
    for info in members.values():
        if info["layer"] != layer:
            continue
        family = str(info["material"]).rsplit("_", 1)[0]
        counts[family] = counts.get(family, 0) + 1
    if not counts:
        return None
    return sorted(counts, key=lambda k: (-counts[k], k))[0]


def luminance(r: int, g: int, b: int) -> float:
    """0..1 の相対輝度(ITU-R BT.709)。バニラの陰影の強弱をここで拾う。"""
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0


def recolor_vanilla_layer(
    base: Image.Image,
    dark: tuple[int, int, int],
    main: tuple[int, int, int],
    light: tuple[int, int, int],
) -> Image.Image:
    """バニラのレイヤー PNG を、形と陰影を保ったままセットの3階調へ塗り替える。

    * **alpha は1ピクセルも変えない。** ここがこの関数の存在理由で、透明部分こそが
      「顔が見える」「ブーツが足首で止まる」を作っている。
    * 不透明ピクセルは、そのテクスチャ内の最暗〜最明で正規化した明度 t を求め、
      t<0.5 なら 暗部→主色、t>=0.5 なら 主色→ハイライト へ補間する。
      バニラ側の相対的な陰影(縁の暗さ・面の明るさ)がそのまま残る。
    * 乱数は使わないので、同じ下地と同じパレットなら常に同じ出力になる。
    """
    pixels: list[tuple[int, int, int, int]] = list(base.getdata())  # type: ignore[arg-type]
    lums = [luminance(r, g, b) for r, g, b, a in pixels if a > 0]
    if not lums:
        raise VanillaBaseMissing("下地のバニラ PNG が全面透明です(バージョン差の可能性)")
    lo, hi = min(lums), max(lums)
    span = (hi - lo) or 1.0

    out: list[tuple[int, int, int, int]] = []
    for r, g, b, a in pixels:
        if a == 0:
            out.append((0, 0, 0, 0))
            continue
        t = (luminance(r, g, b) - lo) / span
        if t < 0.5:
            color = lerp_color(dark, main, t / 0.5)
        else:
            color = lerp_color(main, light, (t - 0.5) / 0.5)
        out.append((color[0], color[1], color[2], a))

    im = Image.new("RGBA", base.size)
    im.putdata(out)
    return im


def main() -> int:
    parser = argparse.ArgumentParser(
        description="バニラの防具レイヤーを下地に、inv テクスチャの配色で塗り替えた装備時レイヤー PNG を生成する"
    )
    parser.add_argument("--dry-run", action="store_true", help="書き込まず、生成予定だけ出す")
    parser.add_argument(
        "--vanilla-jar",
        help="下地にするバニラ PNG を取り出すクライアント jar。省略すると .minecraft から自動で探す",
    )
    args = parser.parse_args()

    if not bea.CATALOG.is_file():
        print(f"catalog.yml が見つかりません: {bea.CATALOG}", file=sys.stderr)
        return 1

    try:
        jar = find_vanilla_jar(args.vanilla_jar)
    except VanillaBaseMissing as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(f"下地にするバニラ jar: {jar}")
    print()

    sets = bea.collect_sets()
    if not sets:
        print("catalog.yml に防具が1件も見つかりませんでした。")
        return 1

    generate: list[tuple[str, dict[str, object], list[str]]] = []
    skip: list[str] = []

    for asset, entry in sorted(sets.items()):
        present = set_has_any_inv_texture(entry)
        if not present:
            skip.append(asset)
            continue
        generate.append((asset, entry, present))

    print(f"--- catalog.yml の防具セット {len(sets)} 件 ---")
    print(f"  inv テクスチャあり(生成対象): {len(generate)} 件")
    print(f"  inv テクスチャなし(対象外)  : {len(skip)} 件")
    print()

    if skip:
        print("--- inv PNG が1枚も無いので生成しないセット ---")
        for asset in skip:
            print(f"  {asset}")
        print()

    written = 0
    for asset, entry, present in generate:
        layers = sorted(entry["layers"])  # type: ignore[arg-type]
        print(f"[{asset}] inv素材: {', '.join(present)}")
        for layer in layers:
            family = base_material_for(entry, layer)
            base_name = VANILLA_BASE_BY_MATERIAL.get(family or "")
            if base_name is None:
                print(
                    f"    -> {layer:<18} ⚠ material 系統 {family!r} に対応するバニラ下地が"
                    " VANILLA_BASE_BY_MATERIAL に無いのでスキップ",
                    file=sys.stderr,
                )
                continue

            sources = pick_source_textures(entry, layer)
            dark, main_c, light = extract_palette(sources)
            if layer == bea.LAYER_LEGGINGS:
                dark = scale_color(dark, LEGGINGS_DARKEN)
                main_c = scale_color(main_c, LEGGINGS_DARKEN)
                light = scale_color(light, LEGGINGS_DARKEN)
            out_path = bea.texture_path(layer, asset)
            src_names = ", ".join(p.name for p in sources)
            print(
                f"    -> {layer:<18} 下地[{base_name}] 色元[{src_names}]"
                f" 暗{dark} 主{main_c} 明{light}  出力: {out_path.relative_to(HERE)}"
            )
            if not args.dry_run:
                base = load_vanilla_layer(jar, layer, base_name)
                if base.size != (CANVAS_W, CANVAS_H):
                    print(
                        f"    ⚠ 下地 {layer}/{base_name}.png が {base.size} で "
                        f"{(CANVAS_W, CANVAS_H)} ではありません。バニラの仕様変更を疑うこと",
                        file=sys.stderr,
                    )
                out_path.parent.mkdir(parents=True, exist_ok=True)
                im = recolor_vanilla_layer(base, dark, main_c, light)
                im.save(out_path)
                written += 1

    print()
    if args.dry_run:
        print("--dry-run なので何も書きませんでした。")
        return 0

    print(f"--- 生成しました ({written} 枚) ---")
    print(
        "⚠ ここで作った PNG は「バニラの防具の形と陰影のまま、色だけ差し替えた」ものです。"
        " 形はバニラそのものなので、頭を覆いすぎる/脚が全部塗られる といった破綻は起きません。"
        " 手描きのレイヤー PNG を同じパスへ置けば、そちらに置き換わります"
        "(その場合はこのスクリプトを再実行しないこと)。"
    )
    print("続けて python resourcepack/build_equipment_assets.py を実行すると実際に配線されます。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
