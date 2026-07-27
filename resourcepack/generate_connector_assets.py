from __future__ import annotations

import json
from pathlib import Path
import struct
import zlib


ROOT = Path(__file__).parent / "trinityforge-skill-gui"
SHAPES = {
    "corner_ne": "NE",
    "corner_se": "SE",
    "corner_sw": "SW",
    "corner_nw": "NW",
    "junction_new": "NEW",
    "junction_nes": "NES",
    "junction_esw": "ESW",
    "junction_nsw": "NSW",
    "cross_nesw": "NESW",
    # 直線/endpoint も同じパレット・同じ線幅(2px)で一元生成する。かつては別由来の手作りPNG
    # (淡色グラデ/太さ4px)が混在し、十字と色味・太さが揃っていなかった(2026-07-22修正)。
    "continuous_vertical": "NS",
    "direct_vertical": "NS",
    "continuous_horizontal": "EW",
    "endpoint_verti_top": "NS",
    "endpoint_verti_bottom": "NS",
    "endpoint_horiz_left": "EW",
    "endpoint_horiz_right": "EW",
}
# endpoint の端キャップ(線の終端を1px広げる装飾)。既存テクスチャの形状を踏襲。
CAPS = {
    "endpoint_verti_top": [(6, 0), (9, 0)],
    "endpoint_verti_bottom": [(6, 15), (9, 15)],
    "endpoint_horiz_left": [(0, 6), (0, 9)],
    "endpoint_horiz_right": [(15, 6), (15, 9)],
}
COLORS = {
    "locked": (72, 72, 72),
    "unlockable": (255, 170, 32),
    "unlocked": (92, 220, 72),
}


def chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(
        ">I", zlib.crc32(kind + data) & 0xFFFFFFFF
    )


def png(arms: str, state: str, shape: str = "") -> bytes:
    frame_count = 1 if state == "locked" else 4
    width, height = 16, 16 * frame_count
    base = COLORS[state]
    pixels = bytearray(width * height * 4)
    for frame in range(frame_count):
        factor = 1.0 if frame_count == 1 else (0.72, 0.88, 1.0, 0.88)[frame]
        color = tuple(min(255, round(channel * factor)) for channel in base) + (255,)
        y_offset = frame * 16

        def paint(x: int, y: int) -> None:
            index = ((y_offset + y) * width + x) * 4
            pixels[index:index + 4] = bytes(color)

        if "N" in arms:
            for y in range(0, 9):
                for x in (7, 8):
                    paint(x, y)
        if "S" in arms:
            for y in range(7, 16):
                for x in (7, 8):
                    paint(x, y)
        if "W" in arms:
            for y in (7, 8):
                for x in range(0, 9):
                    paint(x, y)
        if "E" in arms:
            for y in (7, 8):
                for x in range(7, 16):
                    paint(x, y)
        for x, y in CAPS.get(shape, ()):
            paint(x, y)

    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)
        raw.extend(pixels[y * stride:(y + 1) * stride])
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def write_json(path: Path, document: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    for state in ("locked", "unlockable", "unlocked"):
        for shape, arms in SHAPES.items():
            name = f"{state}_{shape}"
            texture = ROOT / "assets/minecraft/textures/item/gui/connection_pieces" / f"{name}.png"
            texture.parent.mkdir(parents=True, exist_ok=True)
            texture.write_bytes(png(arms, state, shape))
            if state != "locked":
                texture.with_suffix(".png.mcmeta").write_text(
                    '{"animation":{"frametime":3,"frames":[0,1,2,3,2,1]}}\n',
                    encoding="utf-8",
                )
            write_json(
                ROOT / "assets/minecraft/models/item/gui/connection_pieces" / f"{name}.json",
                {
                    "parent": "item/generated",
                    "textures": {"layer0": f"item/gui/connection_pieces/{name}"},
                    "gui_light": "front",
                    "display": {"gui": {"scale": [1.125, 1.125, 1.125]}},
                },
            )
            write_json(
                ROOT / "assets/trinityforge/items/gui/connection" / f"{name}.json",
                {
                    "model": {
                        "type": "minecraft:model",
                        "model": f"minecraft:item/gui/connection_pieces/{name}",
                    }
                },
            )


if __name__ == "__main__":
    main()
