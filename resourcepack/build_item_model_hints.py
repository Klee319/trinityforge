"""GeyserExtra 向け item-model ヒントファイルを生成する。

なぜ必要か
----------
1.21.4+ の Java パックでは、スキルツリー GUI のアイコンを
``assets/trinityforge/items/gui/**.json`` (= ``minecraft:item_model``
データコンポーネント) で差し替えている。この JSON は「どのバニラ
アイテムに乗るか」を一切持たない。一方 Geyser のカスタムアイテム登録
はベースアイテム単位なので、パックだけからは統合版向けの登録が作れない。

GeyserExtra はこの穴を埋めるために ``item_model_hints/*.json`` を読む。
(baseItem, item_model) の組を先に宣言しておけば、初回のパック生成から
統合版にテクスチャが乗る。宣言が無い場合は「実際にそのアイテムを持つ
ItemStack をプレイヤーが一度見る → サーバ再起動」まで反映されない
(2026-07-28 に実機で確認: スキルツリーを開いた 01:08 に 22 件だけが
discovery され、01:04 に生成済みだったパックには 1 件も入らなかった)。

ベースアイテムの出どころ
------------------------
``SkillTreeGuiVisuals`` (ノード/コネクタ/移動ボタン) と各
``skilltree/*.yml`` の ``icon:`` (スキル選択ボタン)。ここを変更したら
このスクリプトの表も直すこと。整合性は validate() が
``trinityforge-skill-gui`` の実ファイルと突き合わせて検査する。
"""

from __future__ import annotations

import json
from pathlib import Path

HERE = Path(__file__).parent
ITEMS_ROOT = HERE / "trinityforge-skill-gui" / "assets" / "trinityforge" / "items"
OUTPUT = HERE / "dist" / "trinityforge-skilltree-item-model-hints.json"
NAMESPACE = "trinityforge"

# SkillTreeGuiVisuals.CONTROLS — 8 方向の移動ボタンは全て ARROW。
DIRECTIONS = ("n", "ne", "e", "se", "s", "sw", "w", "nw")
CONTROL_BASE_ITEM = "minecraft:arrow"

# SkillTreeGuiVisuals.node() — 未解放/確認待ちだけが専用モデルを持つ。
# 解放済みノードは editor 指定の perk アイコンをそのまま出すので
# gui/node_unlocked は実行時に一度も使われない (2026-07-22 のユーザー方針)。
# 使われないモデルを登録するとカスタムアイテムが 1 枠無駄になるだけなので除外する。
NODE_BASE_ITEMS = {
    "gui/node_locked": "minecraft:rotten_flesh",
    "gui/node_confirm": "minecraft:structure_void",
}

# SkillTreeGuiVisuals.connector() — 状態が染料の色に対応する。
CONNECTION_BASE_ITEMS = {
    "locked": "minecraft:gray_dye",
    "unlockable": "minecraft:orange_dye",
    "unlocked": "minecraft:lime_dye",
}

# SkillTreeGuiVisuals.SKILL_MODELS の値 → そのスキルツリーの icon:。
# 2026-07-28: landscaping は以前 WOODCUTTING と DIGGING で共有していたが、
# スキル選択GUIで伐採と切削のアイコンが同じになるため DIGGING を
# SKILL_MODELS から外した (専用モデルが無いので digging.yml の
# icon: IRON_SHOVEL をそのまま出す)。よって iron_shovel の宣言も外す —
# 残すと使われないモデルのためにカスタムアイテム枠を 1 つ無駄にする。
SKILL_BASE_ITEMS = {
    "power": ["minecraft:armor_stand"],
    "mining": ["minecraft:iron_pickaxe"],
    "farming": ["minecraft:iron_hoe"],
    "archery": ["minecraft:bow"],
    "lightweapons": ["minecraft:diamond_sword"],
    "heavyweapons": ["minecraft:netherite_axe"],
    "lightarmor": ["minecraft:leather_chestplate"],
    "heavyarmor": ["minecraft:iron_chestplate"],
    "landscaping": ["minecraft:iron_axe"],
}

DISPLAY_NAMES = {
    "gui/node_locked": "未解放",
    "gui/node_confirm": "確認",
}


def build_entries() -> list[dict[str, str]]:
    entries: list[dict[str, str]] = []

    for direction in DIRECTIONS:
        entries.append({
            "item_model": f"{NAMESPACE}:gui/skilltree_{direction}",
            "base_item": CONTROL_BASE_ITEM,
        })

    for model, base_item in NODE_BASE_ITEMS.items():
        entry = {"item_model": f"{NAMESPACE}:{model}", "base_item": base_item}
        if model in DISPLAY_NAMES:
            entry["display_name"] = DISPLAY_NAMES[model]
        entries.append(entry)

    connection_dir = ITEMS_ROOT / "gui" / "connection"
    for path in sorted(connection_dir.glob("*.json")):
        state = path.stem.split("_", 1)[0]
        base_item = CONNECTION_BASE_ITEMS.get(state)
        if base_item is None:
            raise RuntimeError(f"未知のコネクタ状態: {path.name}")
        entries.append({
            "item_model": f"{NAMESPACE}:gui/connection/{path.stem}",
            "base_item": base_item,
        })

    for skill, base_items in SKILL_BASE_ITEMS.items():
        for base_item in base_items:
            entries.append({
                "item_model": f"{NAMESPACE}:gui/skill/{skill}",
                "base_item": base_item,
            })

    return entries


def validate(entries: list[dict[str, str]]) -> None:
    """宣言したモデルが全てパックに実在するか、実在するのに宣言漏れが無いかを検査する。

    GeyserExtra 側は「パックに無いモデルのヒント」を警告付きで捨てるだけなので、
    タイプミスは実機で静かに 1 枠だけ落ちる。ここで落として気付けるようにする。
    """
    declared = {entry["item_model"] for entry in entries}
    on_disk = {
        f"{NAMESPACE}:" + path.relative_to(ITEMS_ROOT).as_posix()[: -len(".json")]
        for path in ITEMS_ROOT.rglob("*.json")
    }
    missing = sorted(declared - on_disk)
    if missing:
        raise RuntimeError(
            "パックに存在しない item_model を宣言している:\n" + "\n".join(missing)
        )
    # node_unlocked は意図的な除外なので、それ以外の未宣言だけを異常とみなす。
    unclaimed = sorted(on_disk - declared - {f"{NAMESPACE}:gui/node_unlocked"})
    if unclaimed:
        raise RuntimeError(
            "パックにあるのにヒント未宣言の item_model がある"
            " (統合版だけテクスチャが出ない):\n" + "\n".join(unclaimed)
        )


if __name__ == "__main__":
    hint_entries = build_entries()
    validate(hint_entries)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(
        json.dumps({"entries": hint_entries}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"entries={len(hint_entries)} -> {OUTPUT}")
