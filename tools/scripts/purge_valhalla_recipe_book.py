#!/usr/bin/env python3
"""Remove stale valhallammo recipe keys from offline player NBT files."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime
import os
from pathlib import Path
import shutil

import nbtlib


RECIPE_LISTS = ("recipes", "toBeDisplayed")
PREFIX = "valhallammo:"


def stale_count(player_file: Path) -> int:
    document = nbtlib.load(player_file)
    recipe_book = document.get("recipeBook")
    if recipe_book is None:
        return 0
    return sum(
        1
        for key in RECIPE_LISTS
        for recipe in recipe_book.get(key, [])
        if str(recipe).startswith(PREFIX)
    )


def purge(player_file: Path, backup_root: Path) -> int:
    document = nbtlib.load(player_file)
    recipe_book = document.get("recipeBook")
    if recipe_book is None:
        return 0

    removed = 0
    for key in RECIPE_LISTS:
        recipes = recipe_book.get(key)
        if recipes is None:
            continue
        kept = [recipe for recipe in recipes if not str(recipe).startswith(PREFIX)]
        removed += len(recipes) - len(kept)
        recipe_book[key] = nbtlib.List[nbtlib.String](kept)
    if removed == 0:
        return 0

    backup_root.mkdir(parents=True, exist_ok=True)
    shutil.copy2(player_file, backup_root / player_file.name)
    temporary = player_file.with_name(player_file.name + ".tf-tmp")
    try:
        document.save(temporary, gzipped=True)
        if stale_count(temporary) != 0:
            raise RuntimeError(f"validation failed for {player_file}")
        os.replace(temporary, player_file)
    finally:
        temporary.unlink(missing_ok=True)
    return removed


@contextmanager
def hold_world_lock(playerdata: Path):
    lock = playerdata.parent / "session.lock"
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


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("playerdata", type=Path)
    parser.add_argument(
        "--apply",
        action="store_true",
        help="write changes; the Minecraft server must be fully stopped",
    )
    args = parser.parse_args()
    playerdata = args.playerdata.resolve()
    if not playerdata.is_dir():
        raise SystemExit(f"playerdata directory not found: {playerdata}")

    candidates = sorted((*playerdata.glob("*.dat"), *playerdata.glob("*.dat_old")))
    affected = [(path, stale_count(path)) for path in candidates]
    affected = [(path, count) for path, count in affected if count]
    print(f"affected_files={len(affected)} stale_entries={sum(count for _, count in affected)}")
    for path, count in affected:
        print(f"{path.name}: {count}")
    if not args.apply or not affected:
        return

    with hold_world_lock(playerdata):
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        backup_root = playerdata.parent / "migration-backups" / timestamp / "playerdata"
        removed = sum(purge(path, backup_root) for path, _ in affected)
        remaining = sum(stale_count(path) for path, _ in affected)
        if remaining:
            raise RuntimeError(f"migration left {remaining} stale recipe entries")
        print(f"removed_entries={removed} backup={backup_root}")


if __name__ == "__main__":
    main()
