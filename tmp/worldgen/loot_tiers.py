"""案1 データパック＋バニラの「構造物チェスト」を、既存の戦利品の豪華さで 5 ティアへ分ける。

ユーザー方針(2026-08-16):
  「デフォルトの戦利品が豪華なところには豪華な報酬を、微妙なところには微妙な報酬を追加する。
    ベースのルートがバニラであっても同じ」

なので **構造物の規模ではなく、そのルートテーブルの中身そのもの** を物差しにする。
規模で切ると「小屋なのに中身がダイヤだらけ」の表が下位に落ちて逆転するため。

除外するもの:
  - 村 / トライアルチャンバー … 無限に湧く or 大量にあるので経済が壊れる(既存の設計判断を維持)
  - 中身が 0 種の表     … 参照だけの器
  - 種以外の農作物など、チェスト以外の器(釣り・ブロック解体)は元から structures.json に出てこない
"""

import csv
import io
import json
import os
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "report")

# make_scale_csv.py と同じ格付け。物差しを2本持つと必ずズレるので使い回す。
LADDER_S = {
    "minecraft:nether_star", "minecraft:elytra", "minecraft:dragon_egg",
    "minecraft:heart_of_the_sea", "minecraft:netherite_ingot", "minecraft:netherite_scrap",
    "minecraft:ancient_debris", "minecraft:netherite_upgrade_smithing_template",
    "minecraft:totem_of_undying", "minecraft:enchanted_golden_apple", "minecraft:beacon",
    "minecraft:netherite_block",
}
LADDER_A = {
    "minecraft:diamond", "minecraft:diamond_block", "minecraft:golden_apple",
    "minecraft:trident", "minecraft:enchanted_book", "minecraft:saddle", "minecraft:name_tag",
    "minecraft:experience_bottle", "minecraft:conduit", "minecraft:diamond_sword",
    "minecraft:diamond_pickaxe", "minecraft:diamond_chestplate", "minecraft:diamond_helmet",
    "minecraft:diamond_leggings", "minecraft:diamond_boots", "minecraft:diamond_axe",
    "minecraft:diamond_shovel", "minecraft:diamond_hoe", "minecraft:diamond_horse_armor",
    "minecraft:golden_horse_armor", "minecraft:music_disc_otherside", "minecraft:music_disc_pigstep",
}
LADDER_B = {
    "minecraft:emerald", "minecraft:gold_block", "minecraft:iron_block", "minecraft:gold_ingot",
    "minecraft:iron_ingot", "minecraft:lapis_lazuli", "minecraft:obsidian",
    "minecraft:crying_obsidian", "minecraft:amethyst_shard", "minecraft:copper_block",
    "minecraft:redstone", "minecraft:ender_pearl",
}

# 対象外にする表。パスにこの語が入るものを丸ごと落とす。
#   village    … 無限湧き(村人交易と重なる)なので経済が壊れる。従来どおり対象外。
#   pots / archaeology / brush … 発掘・壺の中身であってトレジャーチェストではない
#                                (数が多く、1個あたりの中身も1〜2品なので報酬の器に向かない)
#
# ⚠ 2026-08-23 (W-187): trial_chamber / vault / ominous を**対象外から外した**。
#   ユーザー報告「試練のスポナーにカスタム登録のアイテムが反映されず、一部チェストが空」
#   → 実際 pools に1本も入っていなかった(2026-08-16 に経済理由で意図的に除外していた)。
#   ユーザー判断は「デフォルトの試練のスポナーの中身の豪華さで tier を判断 => tier 割り当て」
#   なので、特別扱いをやめて他の表と同じ豪華さスコアに載せる。
#   バニラ試練の間は reward=18.1 / intersection=14.4 / corridor=1.5 / entrance=0.25 と
#   スコアの幅が広いので、10ティアへ自然にばらける(＝豪華な器だけが豪華な報酬をもらう)。
EXCLUDE_TOKENS = ("village", "pots/", "pot_", "archaeology", "brush")

# 構造物(structures.json)から参照されていなくても対象に含める表。
#   ヴォールトと試練のスポナーは **構造物の piece が直接持つのではなくブロックが持つ** ので
#   structures.json に現れない。ここを拾わないと「試練の間に何も足されない」状態が続く。
# ⚠ これらは LootGenerateEvent では捕まえられない(Paper #11680: ヴォールトと
#   試練のスポナーはこのイベントを発火しない)。ArsPaper 側の
#   BlockDispenseLootEvent リスナーが受け持つ。片方だけ直すと無言で効かない。
UNREFERENCED_INCLUDE_TOKENS = ("vault", "/spawners/", "spawners/")

# 語での一致だと巻き込みすぎる表を、ID 名指しで拾う。
#   バニラのヴォールトが引く表は `chests/trial_chambers/reward`(通常) と
#   `..._ominous`(不吉) の2本だけ。`reward_common` / `_rare` / `_unique` は
#   その2本が内部で参照する子表なので、ここへ入れると同じ器へ二重に足すことになる。
#   通常版は構造物参照があって自然に入るが、不吉版は参照が無く漏れる。
UNREFERENCED_INCLUDE_IDS = ("minecraft:chests/trial_chambers/reward_ominous",)


def load(name):
    return json.load(io.open(os.path.join(OUT, name), encoding="utf-8"))


def resolve_items(tables_by_id, tid, seen=None):
    """refs を辿って、その表から最終的に出うるアイテム ID の集合を返す。"""
    if seen is None:
        seen = set()
    if tid in seen:
        return set()
    seen.add(tid)
    t = tables_by_id.get(tid)
    if not t:
        return set()
    items = {i[0] for i in t["items"]}
    for ref in t.get("refs", []):
        items |= resolve_items(tables_by_id, ref, seen)
    return items


def luxury(items):
    """中身の豪華さ。S を持つ表を最優先し、同点は A / B / 種類数で割る。"""
    s = len(items & LADDER_S)
    a = len(items & LADDER_A)
    b = len(items & LADDER_B)
    return s * 12.0 + a * 3.0 + b * 1.0 + min(len(items), 40) * 0.05


def excluded(tid):
    path = tid.split(":", 1)[-1].lower()
    return any(tok in path for tok in EXCLUDE_TOKENS)


def collect():
    tables = load("loot_tables.json")
    structures = load("structures.json")
    by_id = {t["id"]: t for t in tables}

    used = defaultdict(set)  # loot table id -> 使う構造物 id
    for s in structures:
        for tid in s.get("loot_tables", []):
            used[tid].add(s["id"])

    # 2026-08-23 (W-187): ヴォールト/試練のスポナーは structures.json に現れない
    # (piece ではなくブロックが表を持つ)ので、明示的に拾い直す。
    extra = 0
    for tid in by_id:
        if tid in used:
            continue
        low = tid.split(":", 1)[-1].lower()
        if any(tok in low for tok in UNREFERENCED_INCLUDE_TOKENS) \
                or tid in UNREFERENCED_INCLUDE_IDS:
            used[tid] = set()
            extra += 1
    print("構造物参照の無いヴォールト/スポナー表を追加: %d 件" % extra)

    rows = []
    dropped = []
    for tid, structs in used.items():
        if excluded(tid):
            dropped.append(tid)
            continue
        items = resolve_items(by_id, tid)
        if not items:
            continue
        rows.append({
            "table": tid,
            "namespace": tid.split(":", 1)[0],
            "score": luxury(items),
            "items": len(items),
            "s": len(items & LADDER_S),
            "a": len(items & LADDER_A),
            "b": len(items & LADDER_B),
            "structures": len(structs),
        })
    rows.sort(key=lambda r: (-r["score"], r["table"]))
    # 黙って落とすと「対象に入っているつもりの表が入っていない」事故になるので必ず件数を出す。
    print("対象外にした表: %d 件 (村/壺・発掘)" % len(dropped))
    return rows


# 2026-08-21: 5 ティア → 10 ティアへ細分化(ユーザー選択)。
# 5 段階だと「豪華さスコア 4.5〜15.7 が全部 T3」のように 86 本が同じ報酬表になり、
# どの構造物を漁っても同じという体感になっていた。倍にすると1段の幅が半分になり、
# 「この構造物はこれが出る帯」という差が実際に出る。
TIER_NAMES = ["T10_極上", "T9_最上位", "T8_上位", "T7_準上位", "T6_中上位",
              "T5_中位", "T4_中下位", "T3_下位", "T2_準下位", "T1_最下位"]
# 上から 4/6/8/10/11/12/13/12/12/12 %。上位ほど薄くして「当たりの構造物」を作る
# (5 ティア時代の 8/15/25/27/25 をそのまま半分に割ると上位が厚くなりすぎる)。
TIER_CUTS = [0.04, 0.10, 0.18, 0.28, 0.39, 0.51, 0.64, 0.76, 0.88, 1.00]


def assign(rows):
    n = len(rows)
    bounds = [int(round(n * c)) for c in TIER_CUTS]
    out = []
    i = 0
    for name, end in zip(TIER_NAMES, bounds):
        while i < end:
            rows[i]["tier"] = name
            out.append(rows[i])
            i += 1
    return out


if __name__ == "__main__":
    rows = assign(collect())
    print("対象ルートテーブル: %d" % len(rows))
    per_tier = defaultdict(list)
    for r in rows:
        per_tier[r["tier"]].append(r)
    for name in TIER_NAMES:
        rs = per_tier[name]
        ns = defaultdict(int)
        for r in rs:
            ns[r["namespace"]] += 1
        print("  %-10s %3d 件  スコア %.1f〜%.1f  %s"
              % (name, len(rs), rs[-1]["score"], rs[0]["score"],
                 " ".join("%s:%d" % kv for kv in sorted(ns.items(), key=lambda x: -x[1]))))
        print("        例: %s" % ", ".join(r["table"] for r in rs[:4]))

    with io.open(os.path.join(OUT, "loot-tiers.csv"), "w", encoding="utf-8-sig", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=["tier", "table", "namespace", "score", "items",
                                           "s", "a", "b", "structures"])
        w.writeheader()
        for r in rows:
            w.writerow(r)
    print("\n書き出し: report/loot-tiers.csv")
