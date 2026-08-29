"""統合版(Bedrock)でカスタムアイテム名が識別子のまま出る問題を潰すための lang を生成する。

なぜ Java パックに lang を置くのが正解なのか
--------------------------------------------
統合版に出る名前は **Bedrock パックの ``texts/*.lang``** からしか引けない
(``docs/agent-context/bedrock-geyser.md`` の「カスタムアイテム名はパックの
``texts/*.lang`` からしか出ない」)。ただし **その Bedrock パックを作るのは
GeyserExtra 側** (``<Geyser>/extensions/geyserextra/packs/geyserextra_auto.zip``)
であって、このリポジトリではない。GeyserExtra は既に texts/ja_JP.lang と
texts/en_US.lang を両キー形式 (``item.geyserextra:<name>`` と
``item.geyserextra:<name>.name``) で吐いている。**つまり足りないのは lang 自体
ではなく、そこへ流し込む「名前」だった。**

GeyserExtra は Java パックを走査して CMD ごとに Bedrock アイテムを先行登録する
(``GeyserExtraPaper#prepopulateRegistryFromJavaPack``)。このとき表示名は
``deriveFallbackDisplayName`` が次の順で決める:

  1. モデル参照の終端名から Mojang 慣習のキー ``item.<ns>.<終端名>`` を組み立て、
     **オペレータの Java パックの lang** (``assets/<ns>/lang/<locale>.json``) を引く
  2. 引けなければベースマテリアル名を英語で整形する ("Wooden Sword")

TF のパックは lang を 1 枚も持っていなかったので常に 2 が使われ、統合版では
``wooden_sword`` 系の英語名/識別子のまま出ていた。**このスクリプトが 1 の経路を
埋める。** 生成物は Java 版クライアントにとっては未参照キーなので無害
(TF はアイテム名をリテラルの Component で設定しており翻訳キーを使っていない)。

反映経路 (どれか 1 つでも欠けると効かない)
------------------------------------------
  build_item_lang.py → trinityforge-items/assets/trinityforge/lang/*.json
    → build_item_pack.py → dist/TrinityForge-Pack.zip
    → GitHub release へ再配布 + server.properties の resource-pack / sha1 更新
    → Paper 起動時に GeyserExtra がパックを取得 → custom_items.json の display_name 更新
    → **プロキシ再起動** で geyserextra_auto.zip の texts/*.lang が入れ替わる

キーの名前空間について (バニラ名を潰さないための決定的な制約)
--------------------------------------------------------------
lang のキーはパック全体でグローバルなので、``item.minecraft.*`` を書くと
**バニラアイテムの名前を全プレイヤー分書き換えてしまう**。よってこのスクリプトは
``trinityforge:`` 名前空間のモデルを持つ CMD だけを対象にし、
``item.minecraft.*`` / ``block.minecraft.*`` は生成後に機械的に弾く。
専用モデルを持たない CMD (``minecraft:item/diamond_sword`` をそのまま指している
122 件) は、原理的にこの方式では名前を付けられない。テクスチャを作って
専用モデルへ差し替える (K-14 のリソースパック配線) のが先。

名前の出どころ
--------------
  * ``TrinityForge/src/main/resources/items/catalog.yml`` の ``display-name``
    (MiniMessage 記法)
  * ArsPaper フォークの ``materials.yml`` の ``display_name`` (レガシー ``&`` 記法)
  * ArsPaper フォークの ``functional-items.yml`` の ``display-name`` (MiniMessage 記法)
    ── こちらは Java 実装が材質と CMD を決めるのでエントリ自体は材質を持たない。
    材質と CMD は台帳の ``id`` で引く (2026-08-24 に無限ソース核を ``materials.yml``
    から移して以降、この経路が無いとブロック系特殊アイテムの名前が引けない)。

いずれもタグ/カラーコードを剥がしてプレーン日本語にする。
``materials.yml`` はフォーク側 = ``.gitignore`` 対象でクローンには存在しない。
存在しないときは **既存の生成物から「まだパックが参照していて、かつ台帳の
source が materials のもの」だけを引き継ぐ**。こうしないとフォークを持たない
クローンで生成しただけで 11 件の名前が消え、パックが環境依存になる。
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - 環境依存の案内
    sys.exit("PyYAML が要る: python -m pip install pyyaml")

HERE = Path(__file__).parent
REPO = HERE.parent
PACK_ROOT = HERE / "trinityforge-items"
ITEMS_DIR = PACK_ROOT / "assets" / "minecraft" / "items"
LANG_DIR = PACK_ROOT / "assets" / "trinityforge" / "lang"
REGISTRY = HERE / "cmd-registry.json"
CATALOG = REPO / "TrinityForge" / "src" / "main" / "resources" / "items" / "catalog.yml"
MATERIALS = REPO / "fork-handoff" / "arspaper" / "fork" / "src" / "main" / "resources" / "materials.yml"
FUNCTIONAL_ITEMS = (
    REPO / "fork-handoff" / "arspaper" / "fork" / "src" / "main" / "resources" / "functional-items.yml"
)

NAMESPACE = "trinityforge"
# GeyserExtra の javaPackLocale が ja_jp、フォールバックが en_us
# (JavaPackLangReader.FALLBACK_LOCALE)。TF のアイテム名は日本語しか無いので
# 両方に同じ値を書く — en_us だけを見る設定に切り替わっても名前が消えないようにする。
LOCALES = ("ja_jp", "en_us")

# 生成してはいけないキーの接頭辞。ここに触れるとバニラアイテムの名前が
# 全 Java プレイヤー分書き換わる。
FORBIDDEN_KEY_PREFIXES = ("item.minecraft.", "block.minecraft.", "entity.minecraft.")

_MINIMESSAGE_TAG = re.compile(r"<[^<>]*>")
_LEGACY_COLOR = re.compile(r"[&§][0-9a-fk-orA-FK-OR]")


def plain_text(raw: str) -> str:
    """MiniMessage タグとレガシーカラーコードを剥がしてプレーン文字列にする。

    ``木の<b>ハルバード</b>`` → ``木のハルバード`` / ``&f9倍圧縮…`` → ``9倍圧縮…``。
    ``<gradient:#a:#b>`` のような引数付きタグも ``<...>`` に入れ子が無いので
    同じ正規表現で落ちる。
    """
    text = _MINIMESSAGE_TAG.sub("", raw)
    text = _LEGACY_COLOR.sub("", text)
    return " ".join(text.split())


def collect_model_refs(node: object, found: list[str]) -> None:
    """入れ子の model 定義から ``"model": "<ref>"`` を全部拾う。

    弓は ``condition`` → ``range_dispatch`` と 2 段入れ子になるので、
    トップレベルだけ見ると参照を取りこぼす (GeyserExtra 側の
    ``JavaPackReader#collectModelRefs`` と同じ走査)。
    """
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "model" and isinstance(value, str):
                found.append(value)
            else:
                collect_model_refs(value, found)
    elif isinstance(node, list):
        for value in node:
            collect_model_refs(value, found)


def pack_cmd_models() -> dict[tuple[str, int], list[str]]:
    """パックの ``assets/minecraft/items/*.json`` から (MATERIAL, CMD) -> TF モデル参照。

    値は ``trinityforge:`` 名前空間の参照だけ。1 つの CMD が複数の参照を持つことが
    ある (引き絞りの状態違い) ので、**全部の終端名にキーを張る**。GeyserExtra が
    どれを ``modelRef`` に採るかは解決順に依存するため、片方だけ書くと外れる。
    """
    table: dict[tuple[str, int], list[str]] = {}
    for path in sorted(ITEMS_DIR.glob("*.json")):
        material = path.stem.upper()
        document = json.loads(path.read_text(encoding="utf-8"))
        model = document.get("model", {})
        if not isinstance(model, dict):
            continue
        if model.get("type") != "minecraft:range_dispatch":
            continue
        if model.get("property") != "minecraft:custom_model_data":
            continue
        for entry in model.get("entries", []):
            threshold = entry.get("threshold")
            if not isinstance(threshold, int):
                continue
            refs: list[str] = []
            collect_model_refs(entry.get("model"), refs)
            tf_refs = [ref for ref in refs if ref.startswith(f"{NAMESPACE}:")]
            if tf_refs:
                table[(material, threshold)] = tf_refs
    return table


def registry_sources() -> dict[tuple[str, int], dict]:
    document = json.loads(REGISTRY.read_text(encoding="utf-8"))
    return {
        (entry["material"].upper(), int(entry["cmd"])): entry
        for entry in document["allocations"]
    }


def catalog_names() -> dict[tuple[str, int], str]:
    document = yaml.safe_load(CATALOG.read_text(encoding="utf-8"))
    names: dict[tuple[str, int], str] = {}
    for item in (document.get("items") or {}).values():
        material = item.get("material")
        cmd = item.get("custom-model-data")
        display = item.get("display-name")
        if material and cmd and display:
            names[(str(material).upper(), int(cmd))] = str(display)
    return names


def material_names() -> dict[tuple[str, int], str]:
    if not MATERIALS.exists():
        return {}
    document = yaml.safe_load(MATERIALS.read_text(encoding="utf-8"))
    names: dict[tuple[str, int], str] = {}
    for item in (document.get("materials") or {}).values():
        material = item.get("base_material")
        cmd = item.get("custom_model_data")
        display = item.get("display_name")
        if material and cmd and display:
            names[(str(material).upper(), int(cmd))] = str(display)
    return names


def functional_item_names(sources: dict[tuple[str, int], dict]) -> dict[tuple[str, int], str]:
    """ArsPaper の ``functional-items.yml``(設定エディタの「特殊アイテム」)の表示名。

    このファイルのエントリは **材質と CMD を持たない** ── ブロック系のアイテムは
    Java 実装 (``getBlockMaterial`` / ``getCustomModelData``) が決めるため。
    そこで台帳の ``id`` を突き合わせて (material, cmd) へ写す。

    2026-08-24 に無限ソース核を ``materials.yml`` から移した時点で、この経路が無いと
    表示名が 1 件も引けなくなり、統合版でアイテム名が識別子のまま出る。
    """
    if not FUNCTIONAL_ITEMS.exists():
        return {}
    document = yaml.safe_load(FUNCTIONAL_ITEMS.read_text(encoding="utf-8"))
    by_id: dict[str, str] = {}
    for item_id, item in (document.get("items") or {}).items():
        display = (item or {}).get("display-name")
        if display:
            by_id[str(item_id)] = str(display)
    names: dict[tuple[str, int], str] = {}
    for key, entry in sources.items():
        display = by_id.get(str(entry.get("id")))
        if display:
            names[key] = display
    return names


def previous_entries() -> dict[str, str]:
    path = LANG_DIR / f"{LOCALES[0]}.json"
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def terminal(model_ref: str) -> str:
    """``trinityforge:item/wooden_scythe`` → ``wooden_scythe``。

    GeyserExtra の ``guessItemTranslationKey`` と同じ「最後の ``/`` 以降」規則。
    """
    return model_ref.split(":", 1)[1].rsplit("/", 1)[-1]


def generate() -> tuple[dict[str, str], list[str]]:
    """lang の中身と、人間が読むための注記を返す。ファイル書き込みはしない。"""
    notes: list[str] = []
    models = pack_cmd_models()
    sources = registry_sources()
    names = dict(catalog_names())
    forked = material_names()
    for key, value in forked.items():
        names.setdefault(key, value)
    for key, value in functional_item_names(sources).items():
        names.setdefault(key, value)

    have_fork = bool(forked)
    if not have_fork:
        notes.append(
            f"materials.yml が無い ({MATERIALS}) — フォーク由来の名前は既存の生成物から引き継ぐ"
        )

    carried = previous_entries() if not have_fork else {}
    entries: dict[str, str] = {}
    owners: dict[str, tuple[str, int]] = {}
    unnamed: list[tuple[str, int]] = []

    for (material, cmd), refs in sorted(models.items()):
        raw = names.get((material, cmd))
        source = sources.get((material, cmd), {}).get("source")
        for ref in refs:
            key = f"item.{NAMESPACE}.{terminal(ref)}"
            if raw:
                text = plain_text(raw)
                if not text:
                    raise RuntimeError(
                        f"{material}:{cmd} の display-name がタグを剥がすと空になる: {raw!r}"
                    )
            elif source == "materials" and key in carried:
                # フォーク未取得のクローンでの再生成。台帳が materials 由来だと
                # 言っていて、かつパックがまだそのモデルを参照している分だけ残す。
                text = carried[key]
            else:
                if (material, cmd) not in unnamed:
                    unnamed.append((material, cmd))
                continue
            if key in entries and entries[key] != text:
                raise RuntimeError(
                    f"lang キー {key} が {owners[key]} と {(material, cmd)} で別名になる: "
                    f"{entries[key]!r} / {text!r}"
                )
            entries[key] = text
            owners[key] = (material, cmd)

    for key in entries:
        if key.startswith(FORBIDDEN_KEY_PREFIXES):
            raise RuntimeError(f"バニラ名を潰すキーを生成しようとした: {key}")
        if not key.startswith(f"item.{NAMESPACE}."):
            raise RuntimeError(f"想定外の名前空間のキー: {key}")

    if unnamed:
        notes.append(f"表示名が引けなかった CMD {len(unnamed)} 件: {unnamed[:5]}")

    return dict(sorted(entries.items())), notes


def write(entries: dict[str, str]) -> list[Path]:
    LANG_DIR.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    body = json.dumps(entries, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    for locale in LOCALES:
        path = LANG_DIR / f"{locale}.json"
        # newline="\n" 固定。Windows の既定は CRLF に変換するので、zip の中身が
        # 実行 OS 依存になり sha1 が動く (パック配布は sha1 検証付き)。
        with path.open("w", encoding="utf-8", newline="\n") as handle:
            handle.write(body)
        written.append(path)
    return written


def dedicated_model_coverage() -> tuple[int, int]:
    """(専用モデルを持つ CMD 数, パックが定義する CMD 総数)。"""
    total = 0
    dedicated = 0
    for path in sorted(ITEMS_DIR.glob("*.json")):
        document = json.loads(path.read_text(encoding="utf-8"))
        model = document.get("model", {})
        if not isinstance(model, dict):
            continue
        if model.get("property") != "minecraft:custom_model_data":
            continue
        for entry in model.get("entries", []):
            if not isinstance(entry.get("threshold"), int):
                continue
            total += 1
            refs: list[str] = []
            collect_model_refs(entry.get("model"), refs)
            if any(ref.startswith(f"{NAMESPACE}:") for ref in refs):
                dedicated += 1
    return dedicated, total


if __name__ == "__main__":
    table, remarks = generate()
    files = write(table)
    covered, defined = dedicated_model_coverage()
    for remark in remarks:
        print(f"note: {remark}")
    print(
        f"lang_keys={len(table)} locales={len(files)} "
        f"cmd_with_dedicated_model={covered} cmd_defined_in_pack={defined}"
    )
