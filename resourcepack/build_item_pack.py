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
import sys
import zipfile

BASE = Path(__file__).parent

# 同じディレクトリの build_item_lang を確実に見つける。
# `python resourcepack/build_item_pack.py` ならスクリプトのあるディレクトリが
# sys.path[0] に入るので素の import でも通るが、`python -m` / `runpy` /
# 他モジュールからの import ではそれが働かず ModuleNotFoundError になる。
# パックのビルドを「どこから叩いても同じ」にしておく（起動方法の違いで
# lang だけ生成されない、という無言の劣化を避ける）。
if str(BASE) not in sys.path:
    sys.path.insert(0, str(BASE))

import build_item_lang  # noqa: E402  (sys.path を整えてから読む必要がある)

ROOTS = (BASE / "trinityforge-items", BASE / "trinityforge-skill-gui")
REGISTRY = BASE / "cmd-registry.json"
OUTPUT = BASE / "dist" / "TrinityForge-Pack.zip"
# 「アイテム定義より先にアートだけ置いてある」ものを理由つきで通すための宣言ファイル。
# 無ければ「宣言ゼロ」として扱う (存在しないこと自体は正常)。詳細は
# load_unreferenced_declarations() の docstring。
DECLARATIONS = BASE / "unreferenced-assets.json"


def load_registry() -> dict:
    return json.loads(REGISTRY.read_text(encoding="utf-8"))


def allocations(document: dict | None = None) -> dict[str, set[int]]:
    """cmd-registry.json を material -> 割当済み CMD の集合 に畳む。"""
    if document is None:
        document = load_registry()
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


VANILLA_DEFS = json.loads(
    (BASE / ".." / "tools" / "config-editor" / "lib" / "vanilla-item-defs-1.21.11.json")
    .resolve()
    .read_text(encoding="utf-8")
)["defs"]

# 「同じアイテムが状況によって別モデルで描かれる」ことを表す property。
# lib/respack.js の RENDER_CONTEXT_PROPERTIES と同じ集合（ズレるとこの検査が無意味になる）。
RENDER_CONTEXT_PROPERTIES = {
    "minecraft:display_context",
    "minecraft:using_item",
    "minecraft:use_duration",
}


def render_shape(node: object) -> str:
    """モデルツリーの「構造」だけを文字列化する（モデルIDやテクスチャは無視）。"""
    if isinstance(node, dict):
        kind = node.get("type")
        if kind == "minecraft:model":
            return "model"
        if kind == "minecraft:special":
            return "special:" + str(node.get("model", {}).get("type"))
        if kind == "minecraft:select":
            return (f"select({node.get('property')})"
                    f"[{render_shape(node.get('cases'))}|{render_shape(node.get('fallback'))}]")
        if kind == "minecraft:condition":
            return (f"cond({node.get('property')})"
                    f"[{render_shape(node.get('on_true'))}|{render_shape(node.get('on_false'))}]")
        if kind == "minecraft:range_dispatch":
            return (f"range({node.get('property')})"
                    f"[{render_shape(node.get('entries'))}|{render_shape(node.get('fallback'))}]")
        # cases[] / entries[] の要素は {when|threshold, model:{...}} という形で type を持たない。
        if kind is None and isinstance(node.get("model"), dict):
            return render_shape(node["model"])
        return "?" + str(kind)
    if isinstance(node, list):
        return "+".join(render_shape(child) for child in node)
    return "-"


def has_render_context_split(node: object) -> bool:
    if isinstance(node, dict):
        if node.get("property") in RENDER_CONTEXT_PROPERTIES:
            return True
        return any(has_render_context_split(v) for v in node.values())
    if isinstance(node, list):
        return any(has_render_context_split(v) for v in node)
    return False


def authored_3d_model(root: Path, node: object) -> bool:
    """CMDエントリが「自分で作った立体モデル1個」を指しているか。

    Blockbench で起こした立体モデルは全表示コンテキストでそれを出すのが作者の意図なので、
    バニラ構造の保持を要求しない（唯一の正当な逸脱）。
    """
    if not (isinstance(node, dict) and node.get("type") == "minecraft:model"):
        return False
    identifier = node.get("model", "")
    if not isinstance(identifier, str) or not identifier.startswith("trinityforge:"):
        return False
    model_path = asset_path(root, "models", identifier, ".json")
    if not model_path.exists():
        return False
    return "elements" in json.loads(model_path.read_text(encoding="utf-8"))


def check_render_structure(path: Path, document: dict, root: Path) -> list[str]:
    """CMDエントリがバニラの描画構造を捨てていないかを検査する。

    2026-08-03 の退行がここを素通りした: カスタムCMDのエントリが素の minecraft:model へ
    潰されていたため、トライデントの専用レンダラ（立体モデル＋投擲アニメーション）、弓の
    引き絞り3段階、槍の手持ちモデルが、CMD付きアイテムでだけ全部消えていた。
    fallback（CMDなし）は正しかったので、バニラ品だけ見ても気づけない。
    """
    material = path.stem.upper()
    vanilla = VANILLA_DEFS.get(material)
    if not vanilla:
        return []
    vanilla_model = vanilla["model"]
    # 描画コンテキストで出し分けないマテリアル（防具の鍛冶型など、データ差分の分岐だけを
    # 持つもの）は、カスタム側で1枚に潰してよい。テクスチャが1枚しか無い以上、
    # 分岐を残しても全部同じ絵になるだけなので。
    if not has_render_context_split(vanilla_model):
        return []
    expected = render_shape(vanilla_model)
    problems: list[str] = []
    model = document.get("model", {})
    if model.get("type") != "minecraft:range_dispatch":
        return []
    for entry in model.get("entries", []):
        actual = render_shape(entry.get("model"))
        if actual == expected or authored_3d_model(root, entry.get("model")):
            continue
        problems.append(
            f"{path.name}: cmd {entry.get('threshold')} がバニラの描画構造を捨てている "
            f"(期待 {expected} / 実際 {actual})。"
            "素の minecraft:model へ潰すと専用レンダラや引き絞り/手持ちモデルが失われる"
        )
    return problems


def load_unreferenced_declarations() -> dict[str, str]:
    """「まだ配線していないが意図的に置いてある資産」の宣言を path -> 理由 で返す。

    D-1 は「誰も参照していない資産」を落とすが、**アイテム定義より先にアートだけが
    入る**ことは実際にある (例: ArsPaper の materials.yml が id を参照しているのに
    アイテム定義がまだ無い hoglin_tusk)。そういうものを毎回ビルドを止める形で扱うと、
    結局「検査を外す」方向へ倒れるので、理由を書かせたうえで通す逃げ道を用意する。

    ただの黙らせリストに劣化させないために、呼び出し側で**逆方向も検査する**
    (宣言したのに実は配線済み / 実はもう存在しない、を両方エラーにする)。
    """
    if not DECLARATIONS.exists():
        return {}
    document = json.loads(DECLARATIONS.read_text(encoding="utf-8"))
    table: dict[str, str] = {}
    for entry in document.get("assets", []):
        reason = str(entry.get("reason", "")).strip()
        if not reason:
            raise RuntimeError(
                f"{DECLARATIONS.name}: '{entry.get('path')}' に reason が無い。"
                "理由の書いていない宣言は許可リストと同じで、書いた本人以外には検証できない"
            )
        table[entry["path"]] = reason
    return table


def item_asset_identifier(relative: str, kind: str) -> str | None:
    """'assets/trinityforge/<kind>/item/xxx.ext' から 'trinityforge:item/xxx' を作る。

    対象外 (item 以外の kind、font/entity/gui など別レーンの資産) なら None。
    識別子は常に `trinityforge:item/<assetName>` の形しか使わない
    (asset_path も cmd-registry.json の割当も全部この形なので、ここもそれに合わせる)。
    """
    prefix = f"assets/trinityforge/{kind}/item/"
    if not relative.startswith(prefix):
        return None
    stem = relative[len(prefix):]
    for suffix in (".json", ".png"):
        if stem.endswith(suffix):
            stem = stem[: -len(suffix)]
            break
    else:
        return None
    return f"trinityforge:item/{stem}"


def collect_reachable_item_assets(
    items_root: Path, roots: tuple[Path, ...]
) -> tuple[set[str], set[str]]:
    """`assets/minecraft/items/*.json` を起点に trinityforge:item/ のモデル参照を
    推移的に辿る (parent も辿る)。到達したモデル識別子集合とテクスチャ識別子集合を返す。

    参照先が実在しない場合は既存の検査 (「model が無い」) が別途拾うので、ここでは
    黙ってスキップする (二重報告を避ける)。
    """
    frontier: set[str] = set()
    for path in sorted(items_root.glob("*.json")):
        document = json.loads(path.read_text(encoding="utf-8"))
        model_ids(document, frontier)

    reachable_models: set[str] = set()
    reachable_textures: set[str] = set()
    queue = list(frontier)
    while queue:
        identifier = queue.pop()
        if identifier in reachable_models:
            continue
        reachable_models.add(identifier)

        model_path = next(
            (
                candidate
                for root in roots
                if (candidate := asset_path(root, "models", identifier, ".json")).exists()
            ),
            None,
        )
        if model_path is None:
            continue

        model_document = json.loads(model_path.read_text(encoding="utf-8"))
        for texture in model_document.get("textures", {}).values():
            if isinstance(texture, str) and texture.startswith("trinityforge:"):
                reachable_textures.add(texture)

        parent = model_document.get("parent")
        if isinstance(parent, str) and parent.startswith("trinityforge:"):
            if parent not in reachable_models:
                queue.append(parent)

        nested: set[str] = set()
        model_ids(model_document, nested)
        for nested_identifier in nested:
            if nested_identifier not in reachable_models:
                queue.append(nested_identifier)

    return reachable_models, reachable_textures


def check_undrawn_item_assets(
    registry_document: dict, items_root: Path, roots: tuple[Path, ...]
) -> list[str]:
    """cmd-registry.json の割当のうち、パックに実物 (model/texture) があるのに
    items json の threshold に配線されていないものを列挙する。

    CMD 割当の大半は「意図的にバニラ見た目のまま」で正常 (実測 800 件超) なので、
    「パックに <id> の実物がある」もの**だけ**を対象にする。そうしないと検査が
    無意味になる。
    """
    problems: list[str] = []
    for entry in registry_document["allocations"]:
        asset_name = entry["id"]
        identifier = f"trinityforge:item/{asset_name}"
        has_model = any(
            asset_path(root, "models", identifier, ".json").exists() for root in roots
        )
        has_texture = any(
            asset_path(root, "textures", identifier, ".png").exists() for root in roots
        )
        if not (has_model or has_texture):
            continue

        material = entry["material"]
        items_path = items_root / f"{material.lower()}.json"
        if not items_path.exists():
            problems.append(
                f"cmd {entry['cmd']} ({material}:{asset_name}): "
                "パックに実物があるのに items json 自体が無い"
            )
            continue

        document = json.loads(items_path.read_text(encoding="utf-8"))
        if entry["cmd"] not in thresholds(document):
            problems.append(
                f"cmd {entry['cmd']} ({material}:{asset_name}): "
                "パックに実物があるのに items json の threshold に配線されていない"
            )
    return problems


def validate() -> list[tuple[Path, str]]:
    registry_document = load_registry()
    registry = allocations(registry_document)
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
        problems.extend(check_render_structure(path, document, ROOTS[0]))

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

    # D-1: 誰からも参照されていない資産 (旧自動生成スキームの残骸が典型例)。
    declared = load_unreferenced_declarations()
    reachable_models, reachable_textures = collect_reachable_item_assets(items_root, ROOTS)
    unreferenced: set[str] = set()
    for relative, path in sorted(packable.items()):
        model_identifier = item_asset_identifier(relative, "models")
        if model_identifier is not None and model_identifier not in reachable_models:
            unreferenced.add(relative)
        texture_identifier = item_asset_identifier(relative, "textures")
        if texture_identifier is not None and texture_identifier not in reachable_textures:
            unreferenced.add(relative)

    for relative in sorted(unreferenced - set(declared)):
        problems.append(
            f"{relative}: どこからも参照されていない資産。"
            f"(1) items json へ配線する (2) 削除する (3) 意図的に先行して置いてあるなら"
            f" {DECLARATIONS.name} に理由つきで宣言する — のどれかを選ぶこと"
        )
    # 宣言の腐り止め。「宣言したまま実は配線済み/実は消えた」を放置すると、宣言ファイルが
    # ただの黙らせリストに劣化して検査ごと無効化される (このリポジトリで実際に起きた事故)。
    for relative in sorted(declared):
        if relative not in packable:
            problems.append(
                f"{DECLARATIONS.name}: '{relative}' はもう存在しない。宣言を消すこと"
            )
        elif relative not in unreferenced:
            problems.append(
                f"{DECLARATIONS.name}: '{relative}' は配線済みになっている。宣言を消すこと"
            )

    # D-2: パックに実物があるのに items json の threshold に配線されていない CMD。
    problems.extend(check_undrawn_item_assets(registry_document, items_root, ROOTS))

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
    # 統合版の名前解決に使う lang は毎回作り直す。手で置くと catalog.yml を
    # 直したのに古い名前が配布され続ける (zip が stale だった過去と同じ事故)。
    lang_table, lang_notes = build_item_lang.generate()
    build_item_lang.write(lang_table)
    for note in lang_notes:
        print(f"lang_note={note}")
    print(f"lang_keys={len(lang_table)}")

    packed_files, size, sha1 = build(validate())
    print(f"packed_files={packed_files} bytes={size} sha1={sha1}")
