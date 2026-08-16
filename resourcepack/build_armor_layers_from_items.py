"""inv テクスチャの配色から、防具の「装備したときの見た目」レイヤー PNG を機械的に起こす。

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
* セットのメンバー item_id と同名の inv PNG
  (``resourcepack/trinityforge-items/assets/trinityforge/textures/item/<item_id>.png``) が
  **1枚も無いセットは生成対象外**にする（バニラ以下の劣化を防ぐ。無から作らない）。
* 生成対象でも、``collect_sets()`` が返す ``layers`` に無いレイヤーは作らない
  （humanoid だけのセットに humanoid_leggings を作らない）。
* 色は単純平均だと灰色に潰れるので、明度でソートして暗部/主色/ハイライトの3階調を
  彩度で重み付けした加重平均で抽出する（乱数不使用・決定的）。

使い方
------
    python resourcepack/build_armor_layers_from_items.py            # 生成する
    python resourcepack/build_armor_layers_from_items.py --dry-run  # 何を生成する予定かだけ出す

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


# バニラの装備レイヤーシートは 64x32 の中に、クラシックなプレイヤースキンと同じ配置で
# 頭/胴/腕/脚の UV 矩形が並ぶ（Mojang のプレイヤースキン規格そのもの。64x32 レイアウトでは
# 左腕・左脚は右側の矩形をミラー参照するので、矩形は右側の1組だけで足りる）。
# 均一グリッドで区切ると部位境界と無関係な位置に継ぎ目が出るため、輪郭線とグラデーションは
# 必ずこの UV 矩形単位で描く。表記は (部位名, 面名, x, y, 幅, 高さ)。
UV_RECTS: list[tuple[str, str, int, int, int, int]] = [
    # 頭
    ("head", "top", 8, 0, 8, 8),
    ("head", "bottom", 16, 0, 8, 8),
    ("head", "right", 0, 8, 8, 8),
    ("head", "front", 8, 8, 8, 8),
    ("head", "left", 16, 8, 8, 8),
    ("head", "back", 24, 8, 8, 8),
    # 胴
    ("body", "top", 20, 16, 8, 4),
    ("body", "bottom", 28, 16, 8, 4),
    ("body", "right", 16, 20, 4, 12),
    ("body", "front", 20, 20, 8, 12),
    ("body", "left", 28, 20, 4, 12),
    ("body", "back", 32, 20, 8, 12),
    # 腕(右側の矩形を左腕がミラー参照する)
    ("arm", "top", 44, 16, 4, 4),
    ("arm", "bottom", 48, 16, 4, 4),
    ("arm", "right", 40, 20, 4, 12),
    ("arm", "front", 44, 20, 4, 12),
    ("arm", "left", 48, 20, 4, 12),
    ("arm", "back", 52, 20, 4, 12),
    # 脚(右側の矩形を左脚がミラー参照する)
    ("leg", "top", 4, 16, 4, 4),
    ("leg", "bottom", 8, 16, 4, 4),
    ("leg", "right", 0, 20, 4, 12),
    ("leg", "front", 4, 20, 4, 12),
    ("leg", "left", 8, 20, 4, 12),
    ("leg", "back", 12, 20, 4, 12),
]

# 矩形の外周1pxに引く縁取りの暗さ(暗部色をさらにこの倍率で暗くする)。
BORDER_DARKEN = 0.6


def render_layer(
    dark: tuple[int, int, int], main: tuple[int, int, int], light: tuple[int, int, int]
) -> Image.Image:
    """64x32 の装備レイヤー PNG を1枚描く。

    まずキャンバス全体を主色で塗りつぶし(UV_RECTS のどこからも参照されない領域はここで
    塗った主色のまま残る)、その上から UV_RECTS の矩形ごとに「外周1pxの暗い縁取り＋内側は
    矩形ローカルで上(ハイライト寄り)→下(暗部寄り)へ補間するグラデーション」を描く。
    グラデーションはキャンバス全体で1本にせず、矩形ごとに独立させる(体の部位境界と無関係な
    位置に継ぎ目が出るのを避けるため)。alpha は常に255(不透明)、乱数は使わないので
    同じ入力なら常に同じ出力になる。
    """
    im = Image.new("RGBA", (CANVAS_W, CANVAS_H), (main[0], main[1], main[2], 255))
    px = im.load()
    assert px is not None

    border_color = scale_color(dark, BORDER_DARKEN)

    for _part, _face, rx, ry, rw, rh in UV_RECTS:
        for ly in range(rh):
            y = ry + ly
            on_border_y = ly == 0 or ly == rh - 1
            if rh > 1:
                t = ly / (rh - 1)  # 矩形ローカルの 0(上) → 1(下)
            else:
                t = 0.0
            if t < 0.5:
                row_color = lerp_color(light, main, t / 0.5)
            else:
                row_color = lerp_color(main, dark, (t - 0.5) / 0.5)

            for lx in range(rw):
                x = rx + lx
                on_border_x = lx == 0 or lx == rw - 1
                if rw > 2 and rh > 2 and (on_border_x or on_border_y):
                    color = border_color
                elif rw <= 2 or rh <= 2:
                    # 矩形が薄すぎて内側が確保できない場合は縁取り色で塗りつぶす。
                    color = border_color
                else:
                    color = row_color
                px[x, y] = (color[0], color[1], color[2], 255)

    return im


def main() -> int:
    parser = argparse.ArgumentParser(
        description="inv テクスチャの配色から防具の装備時レイヤー PNG を生成する"
    )
    parser.add_argument("--dry-run", action="store_true", help="書き込まず、生成予定だけ出す")
    args = parser.parse_args()

    if not bea.CATALOG.is_file():
        print(f"catalog.yml が見つかりません: {bea.CATALOG}", file=sys.stderr)
        return 1

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
            sources = pick_source_textures(entry, layer)
            dark, main_c, light = extract_palette(sources)
            if layer == bea.LAYER_LEGGINGS:
                dark = scale_color(dark, LEGGINGS_DARKEN)
                main_c = scale_color(main_c, LEGGINGS_DARKEN)
                light = scale_color(light, LEGGINGS_DARKEN)
            out_path = bea.texture_path(layer, asset)
            src_names = ", ".join(p.name for p in sources)
            print(
                f"    -> {layer:<18} 素材[{src_names}]"
                f" 暗{dark} 主{main_c} 明{light}  出力: {out_path.relative_to(HERE)}"
            )
            if not args.dry_run:
                out_path.parent.mkdir(parents=True, exist_ok=True)
                im = render_layer(dark, main_c, light)
                im.save(out_path)
                written += 1

    print()
    if args.dry_run:
        print("--dry-run なので何も書きませんでした。")
        return 0

    print(f"--- 生成しました ({written} 枚) ---")
    print(
        "⚠ ここで作った PNG は inv テクスチャの色から機械的に起こした仮の見た目です。"
        " 手描きのレイヤー PNG を同じパスへ置けば、そちらに置き換わります"
        "(その場合はこのスクリプトを再実行しないこと)。"
    )
    print("続けて python resourcepack/build_equipment_assets.py を実行すると実際に配線されます。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
