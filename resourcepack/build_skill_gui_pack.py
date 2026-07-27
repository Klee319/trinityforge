from __future__ import annotations

import hashlib
import json
from pathlib import Path
import zipfile


ROOT = Path(__file__).parent / "trinityforge-skill-gui"
OUTPUT = Path(__file__).parent / "dist" / "TrinityForge-SkillGUI.zip"
DIRECTIONS = ("n", "ne", "e", "se", "s", "sw", "w", "nw")
NODES = {
    "node_locked": "icon_locked_trinket_slot",
    "node_confirm": "button_confirm_selection",
    "node_unlocked": "button_save",
}
SKILLS = (
    "archery", "farming", "heavyarmor", "heavyweapons", "landscaping",
    "lightarmor", "lightweapons", "mining", "power",
)
CONNECTION_STATES = ("locked", "unlockable", "unlocked")
CONNECTION_SHAPES = (
    "continuous_horizontal", "continuous_vertical", "direct_vertical",
    "endpoint_horiz_left", "endpoint_horiz_right",
    "endpoint_verti_bottom", "endpoint_verti_top",
    "corner_ne", "corner_se", "corner_sw", "corner_nw",
    "junction_new", "junction_nes", "junction_esw", "junction_nsw",
    "cross_nesw",
)


def expected_files() -> set[str]:
    expected = {
        "pack.mcmeta",
        "assets/trinityforge/font/skill_gui.json",
        "assets/trinityforge/textures/font/skills_gui.png",
        "assets/trinityforge/textures/font/space_split.png",
    }
    for direction in DIRECTIONS:
        name = f"skilltree_{direction}"
        expected.update({
            f"assets/trinityforge/items/gui/{name}.json",
            f"assets/minecraft/models/item/gui/{name}.json",
            f"assets/minecraft/textures/item/gui/{name}.png",
        })
    for wrapper, source in NODES.items():
        expected.update({
            f"assets/trinityforge/items/gui/{wrapper}.json",
            f"assets/minecraft/models/item/gui/{source}.json",
            f"assets/minecraft/textures/item/gui/{source}.png",
        })
    for skill in SKILLS:
        expected.update({
            f"assets/trinityforge/items/gui/skill/{skill}.json",
            f"assets/minecraft/models/item/gui/skillicon_{skill}.json",
            f"assets/minecraft/textures/item/gui/skillicon_{skill}.png",
        })
    for state in CONNECTION_STATES:
        for shape in CONNECTION_SHAPES:
            name = f"{state}_{shape}"
            expected.update({
                f"assets/trinityforge/items/gui/connection/{name}.json",
                f"assets/minecraft/models/item/gui/connection_pieces/{name}.json",
                f"assets/minecraft/textures/item/gui/connection_pieces/{name}.png",
            })
            if state != "locked":
                expected.add(
                    f"assets/minecraft/textures/item/gui/connection_pieces/{name}.png.mcmeta"
                )
    return expected


def asset_path(kind: str, identifier: str, suffix: str) -> Path:
    if ":" in identifier:
        namespace, path = identifier.split(":", 1)
    else:
        namespace, path = "minecraft", identifier
    return ROOT / "assets" / namespace / kind / f"{path}{suffix}"


def validate() -> list[Path]:
    files = sorted(
        (path for path in ROOT.rglob("*") if path.is_file()),
        key=lambda path: path.relative_to(ROOT).as_posix(),
    )
    packable = {
        path.relative_to(ROOT).as_posix()
        for path in files
        if path.name != "README.md"
    }
    expected = expected_files()
    if packable != expected:
        unexpected = sorted(packable - expected)
        missing_expected = sorted(expected - packable)
        raise RuntimeError(
            "skill GUI allowlist mismatch:"
            f"\nunexpected={unexpected}\nmissing={missing_expected}"
        )
    documents = {
        path: json.loads(path.read_text(encoding="utf-8"))
        for path in files
        if path.suffix == ".json" or path.name == "pack.mcmeta"
    }
    missing: list[str] = []
    item_root = ROOT / "assets" / "trinityforge" / "items"
    for path, document in documents.items():
        if path.is_relative_to(item_root):
            model = document["model"]["model"]
            if not asset_path("models", model, ".json").exists():
                missing.append(f"{path}: model {model}")
        if "/models/" in path.as_posix():
            for texture in document.get("textures", {}).values():
                if not asset_path("textures", texture, ".png").exists():
                    missing.append(f"{path}: texture {texture}")
    if missing:
        raise RuntimeError("missing resource-pack references:\n" + "\n".join(missing))
    return [path for path in files if path.name != "README.md"]


def build(files: list[Path]) -> tuple[int, int, str]:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUTPUT, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            info = zipfile.ZipInfo(path.relative_to(ROOT).as_posix())
            info.date_time = (1980, 1, 1, 0, 0, 0)
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes())
    content = OUTPUT.read_bytes()
    with zipfile.ZipFile(OUTPUT) as archive:
        count = len(archive.namelist())
    return count, len(content), hashlib.sha1(content).hexdigest()


if __name__ == "__main__":
    packed_files, size, sha1 = build(validate())
    print(f"packed_files={packed_files} bytes={size} sha1={sha1}")
