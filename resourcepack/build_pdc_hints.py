"""GeyserExtra 向け PDC ヒントファイルを生成する。

なぜ必要か
----------
統合版でカスタムアイテムが「専用の見た目」「正しい名前」「オフハンドに
置ける」の 3 つを得るには、Geyser にカスタムアイテムとして登録されている
必要がある。とくにオフハンドは Bedrock 側がバニラの短いホワイトリスト
(盾/トーテム/地図/矢) しか許さないので、**登録されていない TF アイテムは
統合版ではオフハンドに置けない**。

ところが PDC でしか識別できないアイテムには、ベースアイテムを宣言する
手段がリソースパックに存在しない。そのため GeyserExtra 側の先回り登録は
CMD と item_model の 2 経路しかなく、PDC アイテムは

  「誰かが実物を持つ」または「レシピの材料/結果に現れる」

まで台帳に載らなかった。モブドロップ・ダンジョン報酬・商人販売のように
レシピを持たない入手経路のアイテムは、どの経路にも掛からない。

このスクリプトは catalog.yml から (pdc_identifier, base_item, display_name)
を宣言する ``pdc_hints/*.json`` を書き出す。GeyserExtra はこれを起動時に
読んで先に登録するので、誰も持っていないアイテムでも最初から正しく出る。

pdc_identifier の形について
---------------------------
GeyserExtra は ItemStack の PDC から ``<namespace>:<value>`` 形式の識別子を
組み立てる。TF は ``trinityforge:catalog_id`` に catalog.yml の id を書くので、
ここで出す値は ``trinityforge:<id>`` になる。

**注意**: GeyserExtra 側の識別子抽出が「PDC のキー名に id/type 等を含む
最初のもの」を採るとき、キー名の辞書順で先に来る ``bind_type`` を拾って
しまい、全アイテムが ``trinityforge:tradeable`` に潰れる不具合があった
(2026-08-03 修正: ヒント語をランク順に走査する形へ変更)。ここで出す値と
実行時に抽出される値が一致しなくなるので、GeyserExtra 側の
``CustomItemScanner.PDC_ID_KEY_HINTS`` を触るときはこのスクリプトの前提も
確認すること。

CMD を持つアイテムも出す理由
----------------------------
CMD を持つアイテムは本来 CMD 経路で登録されるが、それが成立するのは
「Java パックがその CMD のモデルを定義している」場合だけで、テクスチャ
未作成のアイテムは CMD 経路でも登録されない。PDC ヒントを併せて出して
おけば、見た目はバニラのままでも名前とオフハンドは正しくなる。
CMD 定義の方が predicate として狭いので、両方ある場合は GeyserExtra 側の
specificity 順で CMD が優先される。
"""

from __future__ import annotations

import json
import re
from pathlib import Path

HERE = Path(__file__).parent
CATALOG = HERE.parent / "TrinityForge" / "src" / "main" / "resources" / "items" / "catalog.yml"
OUTPUT = HERE / "dist" / "trinityforge-catalog-pdc-hints.json"
NAMESPACE = "trinityforge"

# GeyserExtra の sanitizeForStableId 相当。空白/ハイフン/コロンを _ に潰し、
# 残りは [a-z0-9_] 以外を落とす。ここが Java 側とずれると、生成した
# pdc_identifier と実行時に抽出される識別子が一致せず、先回り登録した
# エントリと実物のエントリが二重に載る。
_SANITIZE_STRIP = re.compile(r"[^a-z0-9_]")

# GeyserExtra の isStableIdValueValid 相当 (2〜64 文字)。
_MIN_ID_LENGTH = 2
_MAX_ID_LENGTH = 64

# MiniMessage タグ (<b>, </color>, <color:dark_purple> …) と レガシー § コード。
# Bedrock のパック lang は装飾を持てないので、名前はプレーン化して出す。
_MINIMESSAGE_TAG = re.compile(r"</?[a-zA-Z0-9_#:/\-]+>")
_LEGACY_CODE = re.compile(r"§[0-9a-fk-orA-FK-OR]")


def sanitize_id(value: str) -> str | None:
    """Java 側 ``CustomItemScanner.sanitizeForStableId`` と同じ結果を返す。

    実行時に登録され得ない形 (2 文字未満・64 文字超) は ``None`` を返す。
    ここで弾かずに出すと、実物を持ったときに別の識別子で 2 件目が載る。
    """
    lowered = value.lower().replace(" ", "_").replace("-", "_").replace(":", "_")
    sanitized = _SANITIZE_STRIP.sub("", lowered)
    if not _MIN_ID_LENGTH <= len(sanitized) <= _MAX_ID_LENGTH:
        return None
    return sanitized


def plain_text(value: str) -> str:
    return _LEGACY_CODE.sub("", _MINIMESSAGE_TAG.sub("", value)).strip()


def load_catalog_items() -> dict[str, dict[str, object]]:
    """catalog.yml の ``items:`` を最小限のパースで読む。

    PyYAML を前提にしないのは、このリポジトリの他の生成スクリプトが
    どれも標準ライブラリだけで動いているため。必要なのは
    id / material / display-name / custom-model-data の 4 つだけなので、
    2 段のインデントを追う素朴な読み方で足りる。
    """
    items: dict[str, dict[str, object]] = {}
    current: str | None = None
    in_items = False

    for raw in CATALOG.read_text(encoding="utf-8").splitlines():
        if raw.startswith("items:"):
            in_items = True
            continue
        if not in_items:
            continue
        # トップレベルの別セクションに出たら終了。
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
            if key in ("material", "display-name", "custom-model-data") and value:
                items[current][key] = value

    return items


def build_entries() -> list[dict[str, object]]:
    entries: list[dict[str, object]] = []
    skipped: list[str] = []
    for item_id, fields in sorted(load_catalog_items().items()):
        material = fields.get("material")
        if not isinstance(material, str):
            # material の無い項目は catalog 側で弾かれるはずだが、
            # 生成物に穴の開いたエントリを混ぜるより黙って飛ばす方が安全。
            skipped.append(f"{item_id} (material 無し)")
            continue
        sanitized_id = sanitize_id(item_id)
        if sanitized_id is None:
            skipped.append(f"{item_id} (識別子として無効)")
            continue
        entry: dict[str, object] = {
            "pdc_identifier": f"{NAMESPACE}:{sanitized_id}",
            "base_item": f"minecraft:{material.strip().lower()}",
        }
        display_name = fields.get("display-name")
        if isinstance(display_name, str):
            plain = plain_text(display_name)
            if plain:
                entry["display_name"] = plain
        entries.append(entry)
    if skipped:
        # 黙って落とすと「統合版でその 1 個だけ出ない」になって原因が追えない。
        print("skipped=" + str(len(skipped)) + ": " + ", ".join(skipped))
    return entries


def validate(entries: list[dict[str, object]]) -> None:
    """同じ pdc_identifier が 2 回出ていないかだけ検査する。

    GeyserExtra 側は重複を「先勝ち + 警告」で処理するので、ここで落と
    さないと片方が黙って消える。
    """
    seen: set[str] = set()
    duplicates: list[str] = []
    for entry in entries:
        identifier = str(entry["pdc_identifier"])
        if identifier in seen:
            duplicates.append(identifier)
        seen.add(identifier)
    if duplicates:
        raise RuntimeError(
            "pdc_identifier が重複している (catalog.yml の id 衝突か"
            " sanitize 後の衝突):\n" + "\n".join(sorted(set(duplicates)))
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
