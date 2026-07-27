#!/usr/bin/env python3
"""Idempotently remove non-GUI Valhalla runtime content from canonical configs."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime
import os
from pathlib import Path
import re
import shutil
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[2]
TIERS = ("wooden", "stone", "copper", "golden", "iron", "diamond", "netherite")
FAMILIES = ("dagger", "rapier", "morningstar", "warhammer", "long_spear", "greataxe")
ITEM_IDS = {
    "wooden_arrows", "flint_arrows", "stone_arrows", "copper_arrows", "golden_arrows",
    "iron_arrows", "diamond_arrows", "teleport_arrows", "netherite_arrows",
    "removeimmunity_arrows", "vial", "vial_antiheal", "vial_poison", "vial_hurt", "vial_holy",
    *(f"{tier}_{family}" for tier in TIERS for family in FAMILIES),
}
REFERENCE_IDS = ITEM_IDS | {"choral_leather"}
CMDS = {
    1981820, 1981821, 1981822, 1981825, 1981826, 1981827,
    8778210, 8778212, 8778214, 8778216, 8778218,
    8778220, 8778224, 8778226, 8778228, 8778232,
    9928310, 9928311, 9928312, 9928313, 9928314,
    8892630, 1998270,
}


def indentation(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def remove_mapping_entries(
    text: str, parent: str, child_indent: int, should_remove: callable
) -> str:
    lines = text.splitlines(keepends=True)
    result: list[str] = []
    inside = False
    skipping = False
    for line in lines:
        stripped = line.strip()
        indent = indentation(line)
        if indent == 0 and stripped == f"{parent}:":
            inside = True
            skipping = False
            result.append(line)
            continue
        if inside and indent == 0 and stripped and not stripped.startswith("#"):
            inside = False
            skipping = False
        if inside and indent == child_indent:
            key_match = re.match(r"^[\"']?([^:\"']+)[\"']?:", stripped)
            if key_match:
                skipping = should_remove(key_match.group(1))
        elif inside and skipping and stripped and indent <= child_indent:
            skipping = False
        if not skipping:
            result.append(line)
    return "".join(result)


def banned_reference(value: Any) -> bool:
    raw = str(value)
    if raw in REFERENCE_IDS:
        return True
    return any(raw.endswith(f"#{cmd}") for cmd in CMDS)


def clean_editor(value: Any) -> Any:
    if isinstance(value, list):
        cleaned = [clean_editor(item) for item in value if not banned_reference(item)]
        return [item for item in cleaned if item is not None]
    if isinstance(value, dict):
        original_items = value.get("itemIds")
        result = {
            key: clean_editor(child)
            for key, child in value.items()
            if not banned_reference(key)
        }
        if isinstance(original_items, list) and any(banned_reference(item) for item in original_items):
            if not result.get("itemIds"):
                return None
        return result
    return value


def replace_editor(text: str) -> str:
    match = re.search(r"(?m)^_editor:\s*$", text)
    if not match:
        return text
    editor_text = text[match.start():]
    editor = yaml.safe_load(editor_text).get("_editor", {})
    cleaned = clean_editor(editor)
    rendered = yaml.safe_dump(
        {"_editor": cleaned}, allow_unicode=True, sort_keys=False, width=4096
    )
    return text[:match.start()] + rendered


def write_validated(path: Path, text: str) -> None:
    yaml.safe_load(text)
    path.write_text(text, encoding="utf-8", newline="\n")


def clean_catalog(path: Path) -> None:
    text = remove_mapping_entries(
        path.read_text(encoding="utf-8"), "items", 2, lambda key: key in ITEM_IDS
    )
    write_validated(path, replace_editor(text))


def clean_stats(path: Path) -> None:
    text = remove_mapping_entries(
        path.read_text(encoding="utf-8"), "items", 2, banned_reference
    )
    text = re.sub(
        r"(?m)^# 下はバニラ装備・ツールの雛形.*\n"
        r"(?:# Valhalla CMD 権威.*\n"
        r"(?:# .*\n){0,3})?",
        "# 下はバニラ装備・ツールとTFネイティブ品の個別ステータス定義。\n",
        text,
    )
    write_validated(path, replace_editor(text))


def clean_progressions(directory: Path) -> None:
    if not directory.is_dir():
        return
    for path in directory.glob("*.yml"):
        lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
        result: list[str] = []
        skip_indent: int | None = None
        for line in lines:
            indent = indentation(line)
            stripped = line.strip()
            if skip_indent is not None:
                if stripped and indent <= skip_indent:
                    skip_indent = None
                else:
                    continue
            if re.match(r"^recipes_unlock:\s*$", stripped):
                skip_indent = indent
                continue
            for cmd in CMDS:
                line = re.sub(
                    rf"(:\s*){cmd}(\s*(?:#.*)?$)", rf"\g<1>-1\g<2>", line
                )
            result.append(line)
        write_validated(path, "".join(result))


def clean_ars_materials(path: Path) -> None:
    text = remove_mapping_entries(
        path.read_text(encoding="utf-8"),
        "materials",
        2,
        lambda key: key == "choral_leather",
    )
    write_validated(path, replace_editor(text))


def repository_targets() -> tuple[Path, Path, Path, Path]:
    return (
        ROOT / "TrinityForge/src/main/resources/items/catalog.yml",
        ROOT / "TrinityForge/src/main/resources/stats/item-stats.yml",
        ROOT / "TrinityForge/src/main/resources/skills/base",
        ROOT / "fork-handoff/arspaper/fork/src/main/resources/materials.yml",
    )


def live_targets(server: Path) -> tuple[Path, Path, Path, Path]:
    return (
        server / "plugins/TrinityForge/items/catalog.yml",
        server / "plugins/TrinityForge/stats/item-stats.yml",
        server / "plugins/TrinityForge/skills/base",
        server / "plugins/ArsPaper/materials.yml",
    )


def level_name(server: Path) -> str:
    properties = server / "server.properties"
    if not properties.is_file():
        raise SystemExit(f"server.properties not found: {properties}")
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("level-name="):
            return line.split("=", 1)[1].strip() or "world"
    return "world"


@contextmanager
def hold_server_lock(server: Path):
    lock = server / level_name(server) / "session.lock"
    if not lock.is_file():
        raise SystemExit(f"cannot verify stopped server; session lock not found: {lock}")
    if os.name != "nt":
        raise SystemExit("automatic session.lock verification is supported on Windows only")
    import msvcrt

    with lock.open("r+b") as handle:
        try:
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError as error:
            raise SystemExit(
                f"server appears to be running; cannot lock {lock}"
            ) from error
        try:
            yield
        finally:
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)


def clean_targets(targets: tuple[Path, Path, Path, Path]) -> None:
    clean_catalog(targets[0])
    clean_stats(targets[1])
    clean_progressions(targets[2])
    if targets[3].is_file():
        clean_ars_materials(targets[3])


def clean_live(server: Path) -> None:
    targets = live_targets(server)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup = server / "migration-backups" / timestamp / "plugin-configs"
    with hold_server_lock(server):
        for target in (targets[0], targets[1]):
            if not target.is_file():
                raise SystemExit(f"required live config not found: {target}")
        for target in (targets[0], targets[1], targets[3]):
            if not target.is_file():
                continue
            destination = backup / target.relative_to(server)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, destination)
        if targets[2].is_dir():
            shutil.copytree(
                targets[2], backup / targets[2].relative_to(server), dirs_exist_ok=True
            )
        clean_targets(targets)
        print(f"backup={backup}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--server-root",
        type=Path,
        help="clean deployed plugin configs after backing them up; server must be stopped",
    )
    args = parser.parse_args()
    if args.server_root:
        clean_live(args.server_root.resolve())
    else:
        clean_targets(repository_targets())
    print(f"Removed {len(ITEM_IDS)} item definitions and {len(CMDS)} CMD values.")
