#!/usr/bin/env python3
"""ArsPaper materials.yml 側の新規アイテム用に 16x16 スプライトを生成する（2026-07-31）。

【何のために作ったか】
ダンジョン踏破の証 19 種・ソースの階梯 5 種・束縛者素材 3 種は config 上は存在するのに
テクスチャが無く、実ゲームでは全部が同じバニラ見た目（BRICK / AMETHYST_SHARD / …）で
並んでいた。「印を19個集める」コンテンツなのに見分けが付かない。

【配線はしない】
このスクリプトが書くのは PNG だけ。`assets/minecraft/items/*.json` も `cmd-registry.json` も
触らない（ユーザー指示: 目視確認したいので配線はしない）。配線は絵を確認してから行う。

【Bedrock（統合版）の制約に合わせてある】
- 16x16 / RGBA
- **半透明ピクセルを 1 つも作らない**（alpha は 0 か 255 のみ）。統合版は半透明を扱えない。

使い方:
    python resourcepack/generate_material_sprites.py            # 生成
    python resourcepack/generate_material_sprites.py --force    # 既存ファイルも上書き
"""

import os
import sys

from PIL import Image

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "trinityforge-items", "assets", "trinityforge", "textures", "item")

# ---------------------------------------------------------------------------
# 共通パレット。キーは下のアスキーアートの1文字と対応する。
# ---------------------------------------------------------------------------
TRANSPARENT = (0, 0, 0, 0)


def rgb(hex_string):
    value = hex_string.lstrip("#")
    return (int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), 255)


# ---------------------------------------------------------------------------
# 1) ダンジョン踏破の証（19種）— 共通の勲章シルエット + ダンジョンごとの紋章と色
# ---------------------------------------------------------------------------
# D=外周の影 / L=縁の明色 / M=縁の中間色 / B=盤面の暗色 / b=盤面の明色 / G=紋章
SEAL_TEMPLATE = [
    "................",
    ".....DDDDDD.....",
    "...DDLLLLLLDD...",
    "..DLLMMMMMMLLD..",
    ".DLLMBBBBBBMLLD.",
    ".DLMBbbbbbbBMLD.",
    ".DLMBbbbbbbBMLD.",
    "DLMBbbbbbbbbBMLD",
    "DLMBbbbbbbbbBMLD",
    ".DLMBbbbbbbBMLD.",
    ".DLMBbbbbbbBMLD.",
    ".DLLMBBBBBBMLLD.",
    "..DLLMMMMMMLLD..",
    "...DDLLLLLLDD...",
    ".....DDDDDD.....",
    "................",
]

# 紋章の貼り付け位置（盤面の中央 6x4）。
GLYPH_ORIGIN = (5, 6)
GLYPH_SIZE = (6, 4)

# 各ダンジョンの紋章。'G' が紋章色、'.' は盤面のまま。
# 抽象的な幾何マークにしてあるのは、16x16 の中の 6x4 で「読める」形がこの程度に限られるため。
SEAL_GLYPHS = {
    # 鉱山系 — ツルハシ / 交差ツルハシ / 段掘り。色も茶→青灰→白灰で離してある
    "mines":            ["GGG..G", "G...G.", "...G..", "..G..."],
    "deep_mines":       ["GGG.GG", "G.G.G.", "..GGG.", ".GG.GG"],
    "quarry":           ["GG....", "GGGG..", "GGGGGG", "GGGGGG"],
    # 灼熱の洞窟 — 炎
    "cave":             ["..GG..", ".GGGG.", "GG.GGG", ".GGGG."],
    # 古橋 — アーチ
    "bridge":           [".GGGG.", "G....G", "G....G", "GG..GG"],
    # 地下都市 — 三つの塔
    "city":             ["G.G.G.", "G.G.GG", "G.G.GG", "GGGGGG"],
    # 登攀路 — 梯子
    "climb":            ["G....G", "GGGGGG", "G....G", "GGGGGG"],
    # 宮殿 — 王冠
    "palace":           ["G.GG.G", "GGGGGG", ".GGGG.", ".GGGG."],
    # 騎士団の城 — 盾
    "knight_castle":    ["GGGGGG", "GGGGGG", ".GGGG.", "..GG..",],
    # 下水道迷宮 — 迷路の十字
    "sewer_maze":       ["G.GGG.", "G...G.", "GGG.G.", "..G.GG"],
    # 蒸気機関工房 — 歯車
    "steamworks":       [".G..G.", "GGGGGG", "GG..GG", ".G..G."],
    # ネザーの鐘 — 鐘
    "nether_bell":      ["..GG..", ".GGGG.", "GGGGGG", "..GG.."],
    # ネザーの荒野 — 三つの炎
    "nether_wastes":    ["G.G.G.", "GGGGG.", ".G.G.G", "G.G.GG"],
    # 闇の大聖堂 — 十字
    "dark_cathedral":   ["..GG..", "GGGGGG", "..GG..", "..GG.."],
    # エンチャント試練 — 煌めき
    "enchant_trial":    ["G..G..", ".GGG.G", "GGGGGG", ".GGG.."],
    # 花火工房 — 破裂
    "fireworks":        ["G.G.G.", ".GGG.G", "GGGGGG", ".GGG.."],
    # ハロウィン闘技場 — カボチャの顔
    "hallosseum":       ["GGGGGG", "G.GG.G", "GGGGGG", "G.GG.G"],
    # 北極 — 雪片
    "north_pole":       ["G.GG.G", ".GGGG.", "GGGGGG", ".GGGG."],
    # 世界を繋ぐ者 — 縫い目（円を斜めに裂く）
    "binder":           [".GGGG.", "G.GG.G", "G.GG.G", ".GGGG."],
}

# ダンジョンごとの色。(縁, 盤面暗, 盤面明, 紋章)
# 印は「同じ family に見えて、色で区別が付く」ことを狙う。
SEAL_COLORS = {
    "mines":            ("#9a7346", "#4a3320", "#6d4c2e", "#f0c98a"),
    "deep_mines":       ("#5a6a86", "#1e2738", "#2f3c54", "#9fc0f0"),
    "quarry":           ("#a8a8a2", "#5c5a52", "#807d72", "#f2eede"),
    "cave":             ("#a05a2c", "#5a2412", "#8a3a18", "#ffb24a"),
    "bridge":           ("#8f7a55", "#4a3c26", "#6d5a3a", "#e6cf9a"),
    "city":             ("#6f7f8f", "#2c3a46", "#445666", "#9fd0e6"),
    "climb":            ("#8f8f6f", "#3f4230", "#5e6248", "#d6dc9a"),
    "palace":           ("#b09a4a", "#4d3f14", "#786320", "#ffe97a"),
    "knight_castle":    ("#8e94a6", "#33384a", "#4c536b", "#cdd6ee"),
    "sewer_maze":       ("#6a7f5a", "#26361f", "#3b5230", "#a7d68a"),
    "steamworks":       ("#9a7a5a", "#3f2e1e", "#5e462e", "#e0a86a"),
    "nether_bell":      ("#a8873c", "#4a2c10", "#6f4418", "#ffcf6a"),
    "nether_wastes":    ("#8f4030", "#4a1410", "#6e2018", "#ff8a5a"),
    "dark_cathedral":   ("#5f5570", "#241f2e", "#3a3247", "#b9a8dc"),
    "enchant_trial":    ("#7a6aa8", "#2a2244", "#413566", "#c9b6ff"),
    "fireworks":        ("#a85a7a", "#4a1830", "#6e2448", "#ff9ad0"),
    "hallosseum":       ("#a86a20", "#4a2408", "#6e3810", "#ffb03a"),
    "north_pole":       ("#7aa0b8", "#243c4a", "#37596e", "#c9edff"),
    "binder":           ("#b8a03a", "#3a2c46", "#584070", "#ffe45a"),
}


def build_seal(key):
    rim, body_dark, body_light, glyph = SEAL_COLORS[key]
    rim_color = rgb(rim)
    palette = {
        ".": TRANSPARENT,
        "D": darken(rim_color, 0.45),
        "M": rim_color,
        "L": lighten(rim_color, 0.35),
        "B": rgb(body_dark),
        "b": rgb(body_light),
        "G": rgb(glyph),
    }
    grid = [list(row) for row in SEAL_TEMPLATE]
    gx, gy = GLYPH_ORIGIN
    for dy, row in enumerate(SEAL_GLYPHS[key]):
        for dx, ch in enumerate(row):
            if ch == "G":
                grid[gy + dy][gx + dx] = "G"
    return render(grid, palette)


# ---------------------------------------------------------------------------
# 2) ソースの階梯（5種）— 欠片 → 結晶 → 凝縮器 → 機関 → 無限核
# ---------------------------------------------------------------------------
# c=本体暗 / C=本体明 / h=highlight / d=輪郭 / f=枠(金属) / F=枠の明色
SOURCE_SPRITES = {
    "source_shard": [
        "................",
        "................",
        "........dd......",
        ".......dCCd.....",
        "......dCChCd....",
        ".....dCChhCCd...",
        ".....dCChhCCd...",
        "....dCCChhCCCd..",
        "....dCCChhCCCd..",
        "....dCCCCCCCd...",
        ".....dCCCCCd....",
        "......dCCCd.....",
        ".......dCd......",
        "........d.......",
        "................",
        "................",
    ],
    "source_crystal": [
        "................",
        ".......dd.......",
        "......dChd......",
        ".....dCChhd.....",
        "....dCCChhCd....",
        "...dCCCChhCCd...",
        "...dCCCChhCCd...",
        "..dCCCCChhCCCd..",
        "..dCCCCChhCCCd..",
        "...dCCCChhCCd...",
        "...dCCCChhCCd...",
        "....dCCChhCd....",
        ".....dCChhd.....",
        "......dChd......",
        ".......dd.......",
        "................",
    ],
    "source_condenser": [
        "................",
        "..ffffffffffff..",
        "..fFFFFFFFFFFf..",
        "..fFdddddddddf..",
        "..fFdCCChhCCdf..",
        "..fFdCChhhhCdf..",
        "..fFdChhhhhhdf..",
        "..fFdChhhhhhdf..",
        "..fFdCChhhhCdf..",
        "..fFdCCChhCCdf..",
        "..fFddddddddddf.",
        "..fFFFFFFFFFFf..",
        "..ffffffffffff..",
        "...f.f....f.f...",
        "...f.f....f.f...",
        "................",
    ],
    "source_engine": [
        "...ff......ff...",
        "...fF......Ff...",
        "..ffffffffffff..",
        "..fFFFFFFFFFFf..",
        "..fFdCCCCCCdFf..",
        "..fFdChhhhCdFf..",
        ".ffFdChhhhCdFff.",
        ".fFFdChhhhCdFFf.",
        ".ffFdChhhhCdFff.",
        "..fFdChhhhCdFf..",
        "..fFdCCCCCCdFf..",
        "..fFFFFFFFFFFf..",
        "..ffffffffffff..",
        "...ff.ff.ff.f...",
        "....f.ff.ff.....",
        "................",
    ],
    "infinity_source_core": [
        ".....dddddd.....",
        "...ddFFFFFFdd...",
        "..dFFhhhhhhFFd..",
        ".dFFhhCCCChhFFd.",
        ".dFhhCChhCCdhFd.",
        "dFFhCChhhhCChFFd",
        "dFhhChhFFhhChhFd",
        "dFhChhFFFFhhChFd",
        "dFhChhFFFFhhChFd",
        "dFhhChhFFhhChhFd",
        "dFFhCChhhhCChFFd",
        ".dFhhCCdhCCdhFd.",
        ".dFFhhCCCChhFFd.",
        "..dFFhhhhhhFFd..",
        "...ddFFFFFFdd...",
        ".....dddddd.....",
    ],
}

SOURCE_PALETTES = {
    "source_shard":         ("#1c5c6e", "#2f9fb8", "#a8f0ff", "#0d2f38", "#7a7a7a", "#b0b0b0"),
    "source_crystal":       ("#123f52", "#1f7f9c", "#8ce8ff", "#08202b", "#7a7a7a", "#b0b0b0"),
    "source_condenser":     ("#123f52", "#2472a0", "#7ad4ff", "#08202b", "#4a5566", "#8a95a8"),
    "source_engine":        ("#2a1a4a", "#5a3fa0", "#c9a8ff", "#150c26", "#4a4050", "#8a7f96"),
    "infinity_source_core": ("#5a3a08", "#c8901c", "#ffe98a", "#2b1a04", "#8a6a14", "#ffd24a"),
}


def build_source(key):
    dark, mid, hi, outline, frame, frame_hi = SOURCE_PALETTES[key]
    palette = {
        ".": TRANSPARENT,
        "d": rgb(outline),
        "c": rgb(dark),
        "C": rgb(mid),
        "h": rgb(hi),
        "f": rgb(frame),
        "F": rgb(frame_hi),
    }
    return render([list(row) for row in SOURCE_SPRITES[key]], palette)


# ---------------------------------------------------------------------------
# 3) 束縛者の素材（3種）
# ---------------------------------------------------------------------------
BINDER_SPRITES = {
    # 次元の破片 — 縦に裂けた面。棒に見えないよう幅を持たせ、割れ目を入れる
    "binder_fragment": [
        "................",
        ".........dd.....",
        "........dChd....",
        ".......dCChhd...",
        "......dCCchhd...",
        "......dCCchhd...",
        ".....dCCcchhd...",
        ".....dCCcchhd...",
        "....dCCccchhd...",
        "....dCCcchhd....",
        "...dCCcchhd.....",
        "...dCCchhd......",
        "..dCCchhd.......",
        "..dCChd.........",
        "..dCd...........",
        "...d............",
    ],
    # 現実の芯 — 巻き取られた糸。スレッド再抽選の触媒
    "reality_thread_core": [
        "................",
        "......dddd......",
        "....ddCCCCdd....",
        "...dCChhhhCCd...",
        "..dCChdddddCCd..",
        "..dChdCCCCdhCd..",
        ".dCChdChhCdhCCd.",
        ".dCChdChhCdhCCd.",
        ".dCChdCCCCdhCCd.",
        ".dCChddddddhCCd.",
        "..dCChhhhhhCCd..",
        "..dCCCCCCCCCCd..",
        "...dCChhhhCCd...",
        "....ddCCCCdd....",
        "......dddd......",
        "................",
    ],
    # 深淵の合金 — インゴット。台形にして「塊」ではなく鋳造物に見せる
    "abyssal_ingot": [
        "................",
        "................",
        "................",
        "....dddddddd....",
        "...dhhhhhhhhdd..",
        "..dhhhhhhhhhhhd.",
        ".dCCCCCCCCCCCCd.",
        ".dCCCCCCCCCCCCd.",
        ".dCcccccccccCCd.",
        "..dcccccccccCd..",
        "...ddddddddddd..",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
}

BINDER_PALETTES = {
    "binder_fragment":     ("#3a1050", "#8e3ec0", "#f0b8ff", "#1c0828"),
    "reality_thread_core": ("#0e3a44", "#1f8ea8", "#a8f0ff", "#061e24"),
    "abyssal_ingot":       ("#2a2136", "#55466e", "#9a84b8", "#100a18"),
}


def build_binder(key):
    dark, mid, hi, outline = BINDER_PALETTES[key]
    palette = {
        ".": TRANSPARENT,
        "d": rgb(outline),
        "c": rgb(dark),
        "C": rgb(mid),
        "h": rgb(hi),
    }
    return render([list(row) for row in BINDER_SPRITES[key]], palette)


# ---------------------------------------------------------------------------
# 描画ユーティリティ
# ---------------------------------------------------------------------------
def lighten(color, amount):
    r, g, b, a = color
    return (min(255, int(r + (255 - r) * amount)),
            min(255, int(g + (255 - g) * amount)),
            min(255, int(b + (255 - b) * amount)), a)


def darken(color, amount):
    r, g, b, a = color
    return (int(r * (1.0 - amount)), int(g * (1.0 - amount)), int(b * (1.0 - amount)), a)


def render(grid, palette):
    """アスキーアートを 16x16 RGBA へ。alpha は 0 か 255 だけになる（統合版の制約）。"""
    if len(grid) != 16:
        raise ValueError("16行である必要があります: %d行" % len(grid))
    image = Image.new("RGBA", (16, 16), TRANSPARENT)
    pixels = image.load()
    for y, row in enumerate(grid):
        if len(row) != 16:
            raise ValueError("%d行目が16文字ではありません: %d文字 (%s)" % (y, len(row), "".join(row)))
        for x, ch in enumerate(row):
            color = palette.get(ch)
            if color is None:
                raise ValueError("%d行%d列の文字 %r がパレットにありません" % (y, x, ch))
            if color[3] not in (0, 255):
                raise ValueError("半透明は統合版で扱えません: %r" % (color,))
            pixels[x, y] = color
    return image


def main():
    force = "--force" in sys.argv
    if not os.path.isdir(OUT_DIR):
        raise SystemExit("テクスチャ出力先が見つかりません: %s" % OUT_DIR)

    jobs = []
    for key in SEAL_GLYPHS:
        jobs.append(("dungeon_seal_" + key, lambda k=key: build_seal(k)))
    for key in SOURCE_SPRITES:
        jobs.append((key, lambda k=key: build_source(k)))
    for key in BINDER_SPRITES:
        jobs.append((key, lambda k=key: build_binder(k)))

    written = skipped = 0
    for name, builder in jobs:
        path = os.path.join(OUT_DIR, name + ".png")
        if os.path.exists(path) and not force:
            skipped += 1
            continue
        builder().save(path, "PNG")
        written += 1
    print("生成 %d 件 / 既存のためスキップ %d 件 -> %s" % (written, skipped, OUT_DIR))
    print("※ 配線(assets/minecraft/items/*.json と cmd-registry.json)は意図的に触っていません。")


if __name__ == "__main__":
    main()
