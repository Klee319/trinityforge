"""防具の「装備したときの見た目」を自動で配線する。

なぜ必要か
----------
アイテムとしての見た目 (インベントリ / 手持ち / 地面) は ``custom-model-data`` で差し替わるが、
**着たときに体の上へ乗るレイヤーは CMD では一切変わらない**。あれを決めているのは
``minecraft:equippable`` データコンポーネントの ``asset_id`` で、次の3点セットが揃って初めて効く:

1. サーバがアイテムへ ``equippable{asset_id: trinityforge:<セット名>}`` を書く (TF 側)
2. パックに ``assets/trinityforge/equipment/<セット名>.json`` (レイヤー定義) がある
3. パックに ``assets/trinityforge/textures/entity/equipment/humanoid/<セット名>.png`` と
   ``.../humanoid_leggings/<セット名>.png`` がある (脚だけ別レイヤーなのはバニラと同じ)

このスクリプトは 2 と 3 の対応付けを機械的に作り、TF が 1 をやるための台帳を出力する。

考え方は ``cmd-registry.json`` と同じ「永続台帳 + 自動配線」:
  * catalog.yml の防具アイテムを **スロット接尾辞を落として** セットへまとめる
    (``infinity_helmet`` / ``infinity_chestplate`` / ... → セット ``infinity``)
  * セット名でレイヤー PNG を探し、**必要なレイヤーが揃っているセットだけ**を配線する
  * 配線したセットは ``equipment-registry.json`` に記録する (asset 名は再利用しない)
  * TF が読む ``items/equipment-assets.yml`` を出力する

⚠ **揃っていないセットは絶対に配線しない。** ``asset_id`` を書いたのにパックに定義が無いと、
   その防具は着たときに **バニラの見た目に戻るのではなく透明になる**。
   「まだテクスチャが無い」は「バニラのまま」であるべきで、透明はバグにしか見えない。

使い方
------
    python resourcepack/build_equipment_assets.py            # 生成する
    python resourcepack/build_equipment_assets.py --dry-run  # 何が配線されるかだけ出す

レイヤー PNG を所定の場所に置いて再実行すれば、そのセットが自動で配線される。
PNG を置く前でも実行してよい (配線候補と不足分の一覧が出る)。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).parent
CATALOG = HERE.parent / "TrinityForge" / "src" / "main" / "resources" / "items" / "catalog.yml"
PACK = HERE / "trinityforge-items"
REGISTRY = HERE / "equipment-registry.json"
TF_ASSET_LIST = (
    HERE.parent / "TrinityForge" / "src" / "main" / "resources" / "items" / "equipment-assets.yml"
)

NAMESPACE = "trinityforge"

# バニラの防具レイヤー種別。脚だけ別レイヤーなのは体との重なりを避けるためで、
# ここはバニラの humanoid / humanoid_leggings をそのまま踏襲する。
LAYER_HUMANOID = "humanoid"
LAYER_LEGGINGS = "humanoid_leggings"

# catalog.yml の material がこれで終わるものを防具とみなし、レイヤー種別を決める。
# ⚠ TURTLE_SCUTE のような「防具の素材」は防具ではないので、接尾辞一致で判定する。
ARMOR_MATERIALS = {
    "_HELMET": LAYER_HUMANOID,
    "_CHESTPLATE": LAYER_HUMANOID,
    "_LEGGINGS": LAYER_LEGGINGS,
    "_BOOTS": LAYER_HUMANOID,
}

# id からセット名を切り出すための接尾辞。material 側と一致していなくてもよい
# (例: ``mage_arcane_novice_helmet`` は LEATHER_HELMET)。
ID_SUFFIXES = ("_helmet", "_chestplate", "_leggings", "_boots")

_MINIMESSAGE_TAG = re.compile(r"<[^<>]+>")
_LEGACY_CODE = re.compile(r"[&§][0-9a-fk-orA-FK-OR]")


def load_catalog_items() -> dict[str, dict[str, str]]:
    """catalog.yml の ``items:`` から id/material/display-name だけを読む。

    PyYAML に依存しないのは、このディレクトリの他の生成スクリプトが
    どれも標準ライブラリだけで動いているため (build_pdc_hints.py と同じ読み方)。
    """
    items: dict[str, dict[str, str]] = {}
    current: str | None = None
    in_items = False

    for raw in CATALOG.read_text(encoding="utf-8").splitlines():
        if raw.startswith("items:"):
            in_items = True
            continue
        if not in_items:
            continue
        if raw and not raw.startswith(" ") and not raw.startswith("#"):
            break

        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue

        indent = len(raw) - len(raw.lstrip(" "))
        if indent == 2 and stripped.endswith(":"):
            current = stripped[:-1].strip()
            items[current] = {}
            continue
        if indent == 4 and current is not None and ":" in stripped:
            key, _, value = stripped.partition(":")
            key = key.strip()
            value = value.strip()
            if key in ("material", "display-name") and value:
                items[current][key] = value

    return items


def plain_text(value: str) -> str:
    return _LEGACY_CODE.sub("", _MINIMESSAGE_TAG.sub("", value)).strip()


def layer_of(material: str) -> str | None:
    for suffix, layer in ARMOR_MATERIALS.items():
        if material.endswith(suffix):
            return layer
    return None


def set_name_of(item_id: str) -> str:
    """``infinity_helmet`` → ``infinity``。接尾辞が付いていなければ id をそのまま使う。"""
    for suffix in ID_SUFFIXES:
        if item_id.endswith(suffix) and len(item_id) > len(suffix):
            return item_id[: -len(suffix)]
    return item_id


def texture_path(layer: str, asset: str) -> Path:
    return PACK / "assets" / NAMESPACE / "textures" / "entity" / "equipment" / layer / f"{asset}.png"


def collect_sets() -> dict[str, dict[str, object]]:
    """catalog.yml の防具を、スロット接尾辞を落としたセットへまとめる。"""
    sets: dict[str, dict[str, object]] = {}
    for item_id, fields in sorted(load_catalog_items().items()):
        material = str(fields.get("material", "")).upper()
        layer = layer_of(material)
        if layer is None:
            continue

        asset = set_name_of(item_id)
        entry = sets.setdefault(asset, {"asset": asset, "members": {}, "layers": set()})
        members = entry["members"]
        assert isinstance(members, dict)
        members[item_id] = {
            "material": material,
            "layer": layer,
            "name": plain_text(str(fields.get("display-name", item_id))),
        }
        layers = entry["layers"]
        assert isinstance(layers, set)
        layers.add(layer)
    return sets


def load_registry() -> dict[str, object]:
    if REGISTRY.exists():
        return json.loads(REGISTRY.read_text(encoding="utf-8"))
    return {"version": 1, "assets": []}


def main() -> int:
    parser = argparse.ArgumentParser(description="防具の装備時テクスチャを自動配線する")
    parser.add_argument("--dry-run", action="store_true", help="生成せず、配線予定と不足だけ出す")
    args = parser.parse_args()

    if not CATALOG.is_file():
        print(f"catalog.yml が見つかりません: {CATALOG}", file=sys.stderr)
        return 1

    sets = collect_sets()
    if not sets:
        print("catalog.yml に防具が1件も見つかりませんでした。命名規則を確認してください。")
        return 1

    wired: list[dict[str, object]] = []
    missing: list[tuple[str, list[str]]] = []

    for asset, entry in sorted(sets.items()):
        layers = sorted(entry["layers"])  # type: ignore[arg-type]
        absent = [layer for layer in layers if not texture_path(layer, asset).is_file()]
        if absent:
            missing.append((asset, absent))
            continue
        members = entry["members"]
        assert isinstance(members, dict)
        wired.append({"asset": asset, "layers": layers, "members": sorted(members)})

    print(f"--- catalog.yml の防具セット {len(sets)} 件 ---")
    print(f"  配線できる    : {len(wired)} 件")
    print(f"  テクスチャ待ち: {len(missing)} 件")
    print()

    if missing:
        print("--- レイヤー PNG が無いので配線しないセット ---")
        print("  (下のパスに 64x32 の PNG を置いて再実行すれば自動で配線されます)")
        for asset, absent in missing:
            for layer in absent:
                print(f"  {asset:<30} 要: {texture_path(layer, asset).relative_to(HERE)}")
        print()

    if args.dry_run:
        print("--dry-run なので何も書きませんでした。")
        return 0

    # ---- 1. equipment 定義 json ----------------------------------------------------------
    equipment_dir = PACK / "assets" / NAMESPACE / "equipment"
    if wired:
        equipment_dir.mkdir(parents=True, exist_ok=True)
    for item in wired:
        asset = str(item["asset"])
        layers = item["layers"]
        assert isinstance(layers, list)
        doc = {"layers": {layer: [{"texture": f"{NAMESPACE}:{asset}"}] for layer in layers}}
        (equipment_dir / f"{asset}.json").write_text(
            json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

    # ---- 2. 永続台帳 ----------------------------------------------------------------------
    # asset 名は使い回さない。一度配ったアイテムの equippable が指す先なので、
    # 別のセットへ付け替えると既存の装備が他人の見た目になる。
    registry = load_registry()
    known: dict[str, dict[str, object]] = {
        str(a["asset"]): dict(a) for a in registry.get("assets", [])  # type: ignore[union-attr]
    }
    for item in wired:
        asset = str(item["asset"])
        record = known.setdefault(asset, {"asset": asset})
        record["layers"] = item["layers"]
        record["members"] = item["members"]
    registry["assets"] = [known[key] for key in sorted(known)]
    REGISTRY.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # ---- 3. TF が読む一覧 -----------------------------------------------------------------
    lines = [
        "# 自動生成 — 直接編集しないこと (resourcepack/build_equipment_assets.py が書く)",
        "#",
        "# 装備したときの見た目を差し替えられる防具セットの一覧。",
        "# TF はここに載っている id にだけ minecraft:equippable の asset_id を書く。",
        "# 載っていない防具は asset_id を書かない = バニラの見た目のまま。",
        "#",
        "# ⚠ パックに定義が無い asset_id を書くと、その防具は着たときに透明になる。",
        "#   だから「パックに実物がある」ことをこのファイルで保証する。",
        "",
    ]
    if wired:
        lines.append("equipment-assets:")
        for item in wired:
            asset = str(item["asset"])
            members = item["members"]
            assert isinstance(members, list)
            lines.append(f"  {asset}:")
            lines.append("    items:")
            for member in members:
                lines.append(f"      - {member}")
    else:
        # 空でも【必ず書く】。TF は saveResource() でこのファイルを配るので、
        # jar の中に無いと起動時に IllegalArgumentException になる。
        # 空 = 「配線済みのセットは無い」= 全防具がバニラの見た目、という正しい状態。
        lines.append("equipment-assets: {}")
    TF_ASSET_LIST.parent.mkdir(parents=True, exist_ok=True)
    TF_ASSET_LIST.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print("--- 生成しました ---")
    print(f"  {equipment_dir.relative_to(HERE)}/*.json  ({len(wired)} 件)")
    print(f"  {REGISTRY.name}")
    print(f"  {TF_ASSET_LIST}")
    print()
    print("⚠ このスクリプトは配信 zip を作りません。パックの再生成と発行は別手順です。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
