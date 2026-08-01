"""TrinityForge-Pack.zip を再生成する。

これまで配布 zip は手作業で作られていたため、`trinityforge-items/` を直しても zip が
古いまま commit され、**「直したのにサーバでは古い見た目のまま」**が繰り返し起きていた
(2026-08-01 時点で zip はソースより 56 ファイル欠落・54 ファイル古い状態だった)。

zip の中身は `trinityforge-items/` と `trinityforge-skill-gui/` の単純な和集合である
(実測で確認済み。zip にしか無いファイルは 0 件)。

build の前に validate() が下記を機械検証する。ここが今回の再発防止の本体で、
**台帳に無い custom_model_data を書いた json は zip に入らない**:

  1. `assets/minecraft/items/<material>.json` の threshold が cmd-registry.json の
     同 material の割当に実在すること
     (実際に、実在しない `NETHERITE_HOE:68` を持つ json が紛れ込んでいた)
  2. threshold が昇順かつ重複なし (range_dispatch は昇順前提)
  3. 参照している `trinityforge:item/<model>` の model json が実在すること
  4. model json が参照している texture の png が実在すること
  5. 2 つのソースディレクトリで同じパスが衝突していないこと
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import zipfile

BASE = Path(__file__).parent
ROOTS = (BASE / "trinityforge-items", BASE / "trinityforge-skill-gui")
REGISTRY = BASE / "cmd-registry.json"
OUTPUT = BASE / "dist" / "TrinityForge-Pack.zip"


def allocations() -> dict[str, set[int]]:
    """cmd-registry.json を material -> 割当済み CMD の集合 に畳む。"""
    document = json.loads(REGISTRY.read_text(encoding="utf-8"))
    table: dict[str, set[int]] = {}
    for entry in document["allocations"]:
        table.setdefault(entry["material"], set()).add(entry["cmd"])
    return table


def asset_path(root: Path, kind: str, identifier: str, suffix: str) -> Path:
    if ":" in identifier:
        namespace, path = identifier.split(":", 1)
    else:
        namespace, path = "minecraft", identifier
    return root / "assets" / namespace / kind / f"{path}{suffix}"


def thresholds(document: dict) -> list[int]:
    entries = document.get("model", {}).get("entries", [])
    return [entry["threshold"] for entry in entries if "threshold" in entry]


def model_ids(node: object, found: set[str]) -> None:
    """入れ子になった model 定義から trinityforge: 名前空間の参照を全部拾う。

    引き絞りの `minecraft:condition` / `range_dispatch` のように model は再帰的に
    入れ子になるので、トップレベルだけを見ると参照漏れを見逃す。
    """
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "model" and isinstance(value, str):
                if value.startswith("trinityforge:"):
                    found.add(value)
            else:
                model_ids(value, found)
    elif isinstance(node, list):
        for value in node:
            model_ids(value, found)


def validate() -> list[tuple[Path, str]]:
    registry = allocations()
    problems: list[str] = []
    packable: dict[str, Path] = {}

    for root in ROOTS:
        for path in sorted(root.rglob("*")):
            if not path.is_file():
                continue
            relative = path.relative_to(root).as_posix()
            if relative in packable:
                problems.append(f"{relative}: {packable[relative]} と {root} で衝突")
            packable[relative] = path

    items_root = ROOTS[0] / "assets" / "minecraft" / "items"
    for path in sorted(items_root.glob("*.json")):
        material = path.stem.upper()
        document = json.loads(path.read_text(encoding="utf-8"))
        values = thresholds(document)
        if values != sorted(values):
            problems.append(f"{path.name}: threshold が昇順でない {values}")
        if len(values) != len(set(values)):
            problems.append(f"{path.name}: threshold が重複 {values}")
        for value in values:
            if value not in registry.get(material, set()):
                problems.append(
                    f"{path.name}: cmd-registry.json に無い割当 {material}:{value}"
                )

    for relative, path in sorted(packable.items()):
        if not relative.endswith(".json"):
            continue
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            problems.append(f"{relative}: JSON として読めない ({error})")
            continue
        root = next(candidate for candidate in ROOTS if path.is_relative_to(candidate))
        found: set[str] = set()
        model_ids(document, found)
        for identifier in sorted(found):
            if not asset_path(root, "models", identifier, ".json").exists():
                problems.append(f"{relative}: model {identifier} が無い")
        if "/models/" in relative:
            for texture in document.get("textures", {}).values():
                # バニラ名前空間の texture はクライアント側にあるのが正常なので対象外。
                # パックが持つべきなのは trinityforge: 名前空間のものだけ。
                if not texture.startswith("trinityforge:"):
                    continue
                if not asset_path(root, "textures", texture, ".png").exists():
                    problems.append(f"{relative}: texture {texture} が無い")

    if problems:
        raise RuntimeError(
            "リソースパックの整合性チェックに失敗:\n  " + "\n  ".join(problems)
        )
    return sorted(packable.items())


def build(files: list[tuple[str, Path]]) -> tuple[int, int, str]:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUTPUT, "w", zipfile.ZIP_DEFLATED) as archive:
        for relative, path in files:
            info = zipfile.ZipInfo(relative)
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
