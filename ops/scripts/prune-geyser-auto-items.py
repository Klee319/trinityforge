"""GeyserExtra の custom_items.json から「パック走査で毎起動作り直せる」エントリだけを外科的に消す。

なぜこれが要るのか
------------------
統合版のカスタムアイテム名は GeyserExtra が生成する Bedrock パックの ``texts/*.lang``
から出る。その値は ``custom_items.json`` の ``display_name`` をそのまま流したものなので、
**台帳に既にある英語名を直さないかぎり、Java パックへ lang を足しても名前は変わらない。**

``GeyserExtraPaper#prepopulateRegistryFromJavaPack`` は
``getByCustomModelData(baseItem, cmd)`` が既に在ると **その CMD を丸ごと skip する**。
``CustomItemScanner`` の更新側も ``hasDisplayName()`` が false のときしか名前を上げない。
つまり一度 "Wooden Sword" で登録された CMD は、実行時スキャンでも起動時走査でも
**永久に直らない**。直す唯一の手段が「該当エントリを台帳から消して作り直させる」こと。

なぜ全消しではだめか
--------------------
``custom_items.json`` は生成物ではなく**永続台帳**である
(``docs/agent-context/bedrock-geyser.md``)。PDC 経路のエントリは
「誰かが実際にそのアイテムを手に持った」ときにしか作られないので、消すと再観測まで戻らない
＝事実上のデータ削除になる。だから **再導出できるものだけ**を消す。

削除対象の判定 (5 条件すべてを満たすものだけ)
---------------------------------------------
1. ``name`` が ``custom_<ベースアイテム名>_<cmd>`` ちょうど
   (``GeyserExtraPaper#generateAutoMappingName`` の書式。javap で確認済み)
2. ``custom_model_data > 0`` (prepopulate 側が cmd<=0 を登録しないため、
   0 のものは必ず別経路＝PDC/item_model 由来)
3. その ``(ベースアイテム, cmd)`` が **現在のパックに実在し、かつ ``trinityforge:``
   名前空間の専用モデルを指している**
   → 次の起動で prepopulate が必ず作り直す、かつ lang で正しい名前を付けられる
4. ``display_name`` が ``prettifyBaseItemName(baseItem)`` と完全一致
   ("Wooden Sword" のような英語フォールバックそのもの)。
   日本語名を持つエントリは実物観測で得た本物なので絶対に消さない
5. キー集合が ``{name, custom_model_data, display_name, creative_category,
   register, allow_offhand}`` の部分集合
   → ``pdc_identifier`` / ``armor`` / ``item_model`` / ``unbreakable`` が付いていたら
     PDC 由来なので除外

**注意**: 1 の書式は ``CustomItemScanner#generateMappingName`` のフォールバックとも一致する
(PDC の id が取れなかった実物観測でも同じ名前になる)。1 だけでは由来を切り分けられないので、
「再導出できる」ことを保証している 3 が本体で、4 と 5 が保険という構造になっている。

使い方
------
既定は dry-run。実削除は ``--apply`` を明示する。**Paper を止めてから実行すること**
(稼働中は GeyserExtra が同じファイルを上書き保存するので編集が消える)。
入口は ``prune-geyser-auto-items.ps1`` のほうを使うと停止確認まで通る。

    python ops/scripts/prune-geyser-auto-items.py --registry <path> --pack <path>
    python ops/scripts/prune-geyser-auto-items.py --registry <path> --pack <path> --apply

終了コード: 0 = 正常 (dry-run 含む) / 1 = 安全弁が働いて中断 / 2 = 引数・入出力エラー。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import zipfile
from datetime import datetime
from pathlib import Path

NAMESPACE = "trinityforge"
ITEMS_PREFIX = "assets/minecraft/items/"

# prepopulate が作るエントリが持ちうるキーの全集合。これ以外が 1 つでも付いていたら
# 実物観測 (PDC / armor / item_model) 由来なので削除対象から外す。
AUTO_ENTRY_KEYS = frozenset(
    {"name", "custom_model_data", "display_name", "creative_category", "register", "allow_offhand"}
)

# 「パックを指し間違えて 0 件になり、何も消えないまま成功と誤解する」を防ぐ下限。
# TF パックの trinityforge: 専用モデル付き CMD は 2026-08-02 時点で 113 件。
MIN_EXPECTED_DEDICATED_CMD = 50


class Aborted(RuntimeError):
    """安全弁が働いて中断したことを表す。"""


def prettify_base_item_name(base_item: str) -> str:
    """``GeyserExtraPaper#prettifyBaseItemName`` の移植。

    ``minecraft:wooden_sword`` → ``Wooden Sword``。``_`` ``:`` ``/`` を空白にして
    直後の 1 文字を大文字にするだけ (javap で分岐まで突き合わせ済み)。
    """
    body = base_item[len("minecraft:") :] if base_item.startswith("minecraft:") else base_item
    out: list[str] = []
    upper_next = True
    for char in body:
        if char in "_:/":
            out.append(" ")
            upper_next = True
        elif upper_next:
            out.append(char.upper())
            upper_next = False
        else:
            out.append(char)
    return "".join(out)


def collect_model_refs(node: object, found: list[str]) -> None:
    """入れ子の model 定義から ``"model": "<ref>"`` を全部拾う。

    弓は ``condition`` → ``range_dispatch`` と多段に入れ子になるので、
    トップレベルだけ見ると参照を取りこぼす。
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


def dedicated_cmds_from_pack(pack: Path) -> set[tuple[str, int]]:
    """パックが定義する CMD のうち ``trinityforge:`` 専用モデルを持つものの集合。

    ``(ベースアイテム名(名前空間なし), cmd)`` を返す。zip とディレクトリの両方を受ける
    (dist の zip が正だが、ビルド前の ``trinityforge-items/`` を直接見たいこともある)。
    """
    documents: list[tuple[str, dict]] = []
    if pack.is_dir():
        items_dir = pack / "assets" / "minecraft" / "items"
        if not items_dir.is_dir():
            raise Aborted(f"パックに {ITEMS_PREFIX} がありません: {pack}")
        for path in sorted(items_dir.glob("*.json")):
            documents.append((path.stem, json.loads(path.read_text(encoding="utf-8"))))
    else:
        with zipfile.ZipFile(pack) as archive:
            names = [
                name
                for name in archive.namelist()
                if name.startswith(ITEMS_PREFIX) and name.endswith(".json")
            ]
            if not names:
                raise Aborted(f"パックに {ITEMS_PREFIX} がありません: {pack}")
            for name in sorted(names):
                stem = name[len(ITEMS_PREFIX) : -len(".json")]
                documents.append((stem, json.loads(archive.read(name).decode("utf-8"))))

    dedicated: set[tuple[str, int]] = set()
    for stem, document in documents:
        model = document.get("model")
        if not isinstance(model, dict):
            continue
        if model.get("property") != "minecraft:custom_model_data":
            continue
        for entry in model.get("entries", []):
            threshold = entry.get("threshold")
            if not isinstance(threshold, int):
                continue
            refs: list[str] = []
            collect_model_refs(entry.get("model"), refs)
            if any(ref.startswith(f"{NAMESPACE}:") for ref in refs):
                dedicated.add((stem, threshold))
    return dedicated


def is_auto_entry(base_item: str, entry: dict, dedicated: set[tuple[str, int]]) -> bool:
    """冒頭の 5 条件を全部満たすか。1 つでも欠けたら残す (残す側に倒す)。"""
    short = base_item.split(":", 1)[-1]
    cmd = entry.get("custom_model_data")
    if not isinstance(cmd, int) or cmd <= 0:
        return False
    if entry.get("name") != f"custom_{short}_{cmd}":
        return False
    if (short, cmd) not in dedicated:
        return False
    if entry.get("display_name") != prettify_base_item_name(base_item):
        return False
    if not set(entry.keys()) <= AUTO_ENTRY_KEYS:
        return False
    return True


def classify(document: dict, dedicated: set[tuple[str, int]]) -> tuple[list[tuple[str, dict]], int]:
    """(削除対象 [(ベースアイテム, entry)], 全エントリ数)。"""
    doomed: list[tuple[str, dict]] = []
    total = 0
    for base_item, entries in document["items"].items():
        for entry in entries:
            total += 1
            if is_auto_entry(base_item, entry, dedicated):
                doomed.append((base_item, entry))
    return doomed, total


def assert_no_precious(doomed: list[tuple[str, dict]]) -> None:
    """PDC 由来を 1 件でも巻き込んでいたら中断する最後の関門。

    is_auto_entry で弾いているはずのものを二重に見る。片方のロジックを将来いじって
    穴が空いても、ここで止まる。
    """
    for base_item, entry in doomed:
        for forbidden in ("pdc_identifier", "armor", "item_model", "unbreakable"):
            if forbidden in entry:
                raise Aborted(
                    f"PDC 由来の可能性があるエントリが削除対象に入りました: "
                    f"{base_item} / {entry.get('name')} (禁止キー {forbidden})"
                )
        display = entry.get("display_name", "")
        if any(ord(char) > 127 for char in display):
            raise Aborted(
                f"実物観測で付いた表示名を消そうとしました: "
                f"{base_item} / {entry.get('name')} / {display!r}"
            )


def prune(document: dict, doomed: list[tuple[str, dict]]) -> dict:
    """削除後の新しい文書を作る (元の dict は書き換えない)。

    エントリが 0 件になったベースアイテムはキーごと落とす。空配列を残すと
    GeyserExtra 側が「登録済みだが中身なし」と読む余地があるため。
    """
    doomed_ids = {id(entry) for _, entry in doomed}
    items: dict[str, list[dict]] = {}
    for base_item, entries in document["items"].items():
        kept = [entry for entry in entries if id(entry) not in doomed_ids]
        if kept:
            items[base_item] = kept
    return {"items": items}


def serialize(document: dict) -> bytes:
    """GeyserExtra が書くのと同じ体裁 (UTF-8 / 2 スペース / LF / 末尾改行なし)。

    実測で、元ファイルはこの書式で **バイト単位に復元できる**ことを確認している。
    """
    return json.dumps(document, ensure_ascii=False, indent=2).encode("utf-8")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def report(doomed: list[tuple[str, dict]], total: int, kept: int) -> None:
    print(f"registry_entries={total} delete={len(doomed)} keep={kept}")
    by_base: dict[str, list[int]] = {}
    for base_item, entry in doomed:
        by_base.setdefault(base_item, []).append(entry["custom_model_data"])
    for base_item in sorted(by_base):
        cmds = ",".join(str(cmd) for cmd in sorted(by_base[base_item]))
        print(f"  delete {base_item}: {cmds}")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="custom_items.json からパック走査で再導出できるエントリだけを消す",
    )
    parser.add_argument("--registry", required=True, type=Path, help="custom_items.json のパス")
    parser.add_argument(
        "--pack",
        required=True,
        type=Path,
        help="配布中の Java パック (TrinityForge-Pack.zip か展開済みディレクトリ)",
    )
    parser.add_argument(
        "--backup-dir",
        type=Path,
        default=None,
        help="バックアップ先 (既定: custom_items.json と同階層の custom_items.backups)",
    )
    parser.add_argument("--apply", action="store_true", help="実際に書き換える (既定は dry-run)")
    parser.add_argument(
        "--max-delete",
        type=int,
        default=150,
        help="この件数を超える削除は中断する (既定 150)",
    )
    parser.add_argument(
        "--allow-reformat",
        action="store_true",
        help="元ファイルを書式再現できない場合でも書き換えを許す (通常は中断)",
    )
    args = parser.parse_args(argv)

    if not args.registry.is_file():
        print(f"error: 台帳がありません: {args.registry}", file=sys.stderr)
        return 2
    if not args.pack.exists():
        print(f"error: パックがありません: {args.pack}", file=sys.stderr)
        return 2

    try:
        dedicated = dedicated_cmds_from_pack(args.pack)
        if len(dedicated) < MIN_EXPECTED_DEDICATED_CMD:
            raise Aborted(
                f"パックの trinityforge: 専用モデル付き CMD が {len(dedicated)} 件しかありません "
                f"(想定 {MIN_EXPECTED_DEDICATED_CMD} 件以上)。--pack が古い/別物の可能性が高いので中断します。"
            )
        print(f"pack_dedicated_cmd={len(dedicated)} pack={args.pack}")

        original = args.registry.read_bytes()
        document = json.loads(original.decode("utf-8"))
        if not isinstance(document.get("items"), dict) or not document["items"]:
            raise Aborted(f"台帳の items が読めません/空です: {args.registry}")

        # 書式再現の検証。ここが一致しないなら、こちらの writer は元の書式を保てない
        # = 消す予定のないエントリも差分になる。台帳相手にそれは許容しない。
        if serialize(document) != original and not args.allow_reformat:
            raise Aborted(
                "この台帳はこのスクリプトの書式で復元できません (GeyserExtra 側の出力書式が変わった?)。"
                "差分が削除以外にも及ぶため中断します。--allow-reformat で強行できます。"
            )

        doomed, total = classify(document, dedicated)
        assert_no_precious(doomed)

        if len(doomed) > args.max_delete:
            raise Aborted(
                f"削除対象が {len(doomed)} 件で上限 {args.max_delete} を超えました。"
                "判定条件かパックの取り違えを疑ってください。"
            )

        pruned = prune(document, doomed)
        kept = sum(len(entries) for entries in pruned["items"].values())
        if kept + len(doomed) != total:
            raise Aborted(f"件数が合いません: keep={kept} delete={len(doomed)} total={total}")

        report(doomed, total, kept)

        if not doomed:
            print("nothing to do: 削除対象が 0 件です")
            return 0

        if not args.apply:
            print("dry-run: 何も書き換えていません。実削除するには --apply を付けてください。")
            return 0

        backup_dir = args.backup_dir or args.registry.parent / "custom_items.backups"
        backup_dir.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        backup = backup_dir / f"custom_items.{stamp}.json"
        shutil.copy2(args.registry, backup)
        if backup.read_bytes() != original:
            raise Aborted(f"バックアップの内容が元と一致しません: {backup}")
        print(f"backup={backup} sha256={sha256(original)}")

        payload = serialize(pruned)
        temporary = args.registry.with_suffix(args.registry.suffix + ".tmp")
        with temporary.open("wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, args.registry)

        # 書いた結果を読み直して検証する。ズレていたらバックアップへ戻す。
        try:
            written = args.registry.read_bytes()
            if written != payload:
                raise Aborted("書き込んだ内容が読み戻せません")
            check = json.loads(written.decode("utf-8"))
            if check != pruned:
                raise Aborted("書き戻した JSON が意図した内容と一致しません")
            leftover, after_total = classify(check, dedicated)
            if leftover:
                raise Aborted(f"削除しきれていません: {len(leftover)} 件残っています")
            if after_total != kept:
                raise Aborted(f"残存件数が想定と違います: {after_total} != {kept}")
        except Exception:
            shutil.copy2(backup, args.registry)
            print(f"rolled back from {backup}", file=sys.stderr)
            raise

        print(f"applied: deleted={len(doomed)} remaining={after_total} sha256={sha256(payload)}")
        print(
            "次: (1) Java パックを release へ差し替え (2) server.properties の "
            "resource-pack / resource-pack-sha1 を更新 (3) Paper 起動 (4) プロキシ再起動"
        )
        return 0

    except Aborted as error:
        print(f"abort: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
