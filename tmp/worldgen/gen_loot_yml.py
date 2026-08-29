"""loot_tiers.py が出した 10 ティアから ArsPaper の loot-tables.yml を生成する。

手で 346 本のテーブル ID を書き写すと必ず取りこぼすので、ランキングから機械的に出す。
再生成すれば表の並びも確率もそのまま再現できる(＝あとで方針を変えても差分が読める)。
"""

import io
import os

import loot_tiers as T

HERE = os.path.dirname(os.path.abspath(__file__))
DEST = os.path.abspath(os.path.join(
    HERE, "..", "..", "fork-handoff", "arspaper", "fork", "src", "main", "resources", "loot-tables.yml"))

# --- 報酬の中身 ------------------------------------------------------------

# item-stats.yml のスレッド分類「品質系」10 種。品質・幸運・ガチャ・ロール系の横強化で、
# ステ上限を上げる縦強化ではないので構造物報酬に置ける。
QUALITY_THREADS = [
    ("thread_artisan", "職人"), ("thread_ritualist", "儀式"), ("thread_angler", "釣り"),
    ("thread_perfumer", "調香"), ("thread_appraiser", "鑑識"), ("thread_scholar", "学者"),
    ("thread_better_fortune", "幸運"), ("thread_gacha", "ガチャ"),
    ("thread_role_luck", "ロール幸運"), ("thread_role_effeciency", "ロール効率"),
]

# materials.yml の「ドロップ素材」20 種から、ユーザー指示の除外4種
# (エルダーガーディアン/ウォーデン/エンドラ/ウィザー)と、
# 深淵素材2種(abyssal_ingot / binder_fragment ＝ ダンジョン主の独占素材)を抜いた 13 種。
# hoglin_tusk は materials.yml に実体が無い(hoglin_fang が正)ので入れない。
MOB_MATERIALS = [
    "ravager_hide", "piglin_brute_plate", "pillager_plate", "witch_elixir", "piglin_ear",
    "stray_cloth", "skeleton_horse_bone", "endermite_soot", "guardian_spine", "husk_cloth",
    "bogged_mossy_bone", "parched_sandy_bone", "hoglin_fang",
]

# catalog.yml にレシピが1件も無い鍵 11 種。作れないので構造物が唯一の入手経路になる。
# (エンチャント試練 2〜9 はレシピがあるが前段の鍵を食う連鎖なので、ここには入れない)
KEYS_SHALLOW = ["key_bridge", "key_city", "key_sewer_maze", "key_steamworks"]
KEYS_MID = ["key_climb", "key_palace", "key_knight_castle", "key_fireworks"]
KEYS_DEEP = ["key_dark_cathedral", "key_nether_bell", "key_nether_wastes"]

# リセット系。周回の敷居を下げる代わりに極低確率。
RESET_SCROLLS = ["skill_node_lock", "skill_tree_reset", "stat_reroll_ticket"]

# ============================================================================
# 2026-08-21 ユーザー指示による方針変更(5 → 10 ティア / 鍵↑ スレッド↓)
#
#   ・「ルートチェストからの鍵の確率を上げてスレッドの確率を下げる」
#   ・「それぞれのダンジョンを漁る意味を作るため 10 ティアくらいに細分化」
#
# 鍵は【3 段の帯を作り、深部の鍵は上位2ティアにしか置かない】。5 ティア時代は
# 浅/中/深がそれぞれ1ティアに固まっていて、T3 を漁れば浅い鍵が全部揃うので
# 「どの構造物を漁るか」に意味が無かった。帯を分けると、行きたいダンジョンの鍵が
# 出る構造物を選ぶ動機になる。
#
# スレッド(品質系10種)は逆に薄くする。ダンジョン中ボスのドロップ(mob-overrides.yml)を
# 同日に Lv100 で 30% まで引き上げたので、構造物とダンジョンの両方が主経路だと
# スレッドが余る。構造物側は「たまに出る当たり」に格下げする。
# ============================================================================

# ティアごとの追加報酬パラメータ。if/elif で分けると 10 段では読めなくなるので表にする。
#   core        : 現実の芯(厳選の触媒)の確率。None なら置かない
#   core_range  : (min, max)。None なら1個
#   threads     : 品質系スレッド10種それぞれの確率。None なら置かない
#   keys        : (鍵リスト, 確率)。None なら置かない
#   resets      : リセット系3種それぞれの確率。None なら置かない
#   materials   : モブ素材13種それぞれの確率
#   shard       : (確率, min, max)
#   crystal     : ソースの結晶の確率
#   apple       : エンチャント金リンゴの確率(旧版から引き継ぐ枠)
#   book        : ArsPaper 自前のマナ系エンチャント本の確率
#   blocks      : [(圧縮ブロックID, 確率)]
#   multiplier  : 既存戦利品の個数倍率(貧相なところほど大きく盛る)
TIERS = {
    "T10_極上": dict(
        core=0.10, core_range=(1, 2), threads=0.015, keys=("DEEP", 0.055), resets=0.008,
        materials=0.022, shard=(0.16, 2, 4), crystal=0.05, apple=0.015, book=0.05,
        blocks=[("diamond_block_1x", 0.010), ("gold_block_1x", 0.02)], multiplier=1.2),
    "T9_最上位": dict(
        core=0.08, core_range=(1, 2), threads=0.012, keys=("DEEP", 0.045), resets=0.006,
        materials=0.020, shard=(0.15, 2, 4), crystal=0.04, apple=0.012, book=0.04,
        blocks=[("emerald_block_1x", 0.014), ("amethyst_block_1x", 0.014)], multiplier=1.25),
    "T8_上位": dict(
        core=0.05, core_range=None, threads=0.010, keys=("MID", 0.048), resets=0.004,
        materials=0.020, shard=(0.14, 1, 3), crystal=None, apple=0.008, book=0.03,
        blocks=[("gold_block_1x", 0.02), ("emerald_block_1x", 0.012)], multiplier=1.3),
    "T7_準上位": dict(
        core=0.04, core_range=None, threads=0.008, keys=("MID", 0.040), resets=0.003,
        materials=0.018, shard=(0.13, 1, 3), crystal=None, apple=None, book=None,
        blocks=[("gold_block_1x", 0.018), ("amethyst_block_1x", 0.012)], multiplier=1.35),
    "T6_中上位": dict(
        core=0.03, core_range=None, threads=0.006, keys=("MID", 0.033), resets=0.002,
        materials=0.016, shard=(0.12, 1, 3), crystal=None, apple=None, book=None,
        blocks=[("iron_block_1x", 0.02), ("redstone_block_1x", 0.015)], multiplier=1.45),
    "T5_中位": dict(
        core=0.02, core_range=None, threads=0.005, keys=("SHALLOW", 0.040), resets=None,
        materials=0.015, shard=(0.12, 1, 3), crystal=None, apple=None, book=None,
        blocks=[("iron_block_1x", 0.02), ("lapis_block_1x", 0.015)], multiplier=1.5),
    "T4_中下位": dict(
        core=0.015, core_range=None, threads=0.004, keys=("SHALLOW", 0.033), resets=None,
        materials=0.013, shard=(0.11, 1, 2), crystal=None, apple=None, book=None,
        blocks=[("copper_block_1x", 0.02), ("redstone_block_1x", 0.015)], multiplier=1.6),
    "T3_下位": dict(
        core=0.010, core_range=None, threads=0.003, keys=("SHALLOW", 0.026), resets=None,
        materials=0.011, shard=(0.10, 1, 2), crystal=None, apple=None, book=None,
        blocks=[("copper_block_1x", 0.02), ("coal_block_1x", 0.02)], multiplier=1.7),
    "T2_準下位": dict(
        core=None, core_range=None, threads=0.002, keys=("SHALLOW", 0.018), resets=None,
        materials=0.009, shard=(0.08, 1, 2), crystal=None, apple=None, book=None,
        blocks=[("coal_block_1x", 0.02), ("copper_block_1x", 0.015)], multiplier=1.85),
    # 2026-08-23 (W-187): T1 にも threads を持たせた。専属スレッド(精射)と
    # 品質系の看板(潮読み)がここに居るので、None のままだと割り当てが無言で落ちる。
    "T1_最下位": dict(
        core=None, core_range=None, threads=0.0015, keys=None, resets=None,
        materials=0.005, shard=(0.06, 1, 2), crystal=None, apple=None, book=None,
        blocks=[("coal_block_1x", 0.02)], multiplier=2.0),
}

KEY_LISTS = {"SHALLOW": KEYS_SHALLOW, "MID": KEYS_MID, "DEEP": KEYS_DEEP}
KEY_LABEL = {"SHALLOW": "浅部", "MID": "中位", "DEEP": "深部"}

# ============================================================================
# 2026-08-23 (W-187) ユーザー指示による方針変更 ── 「ティア専属化」
#
#   「editorの構造物ルート設定において、スレッドやカギなどが各tier固有のものが少ない。
#     特にスレッドやカギ、機能アイテムなどが同じtireに全種類入っているのが少し問題に感じる。
#     それぞれを漁る意義を設けたい。」
#   選択された強度は【強く専属化】。
#
# 改訂前の実測(tmp/w187-loot-audit.py):
#   ・品質系スレッド10種 … 全10種が 9/10 ティアに出る(t1 以外の全部)
#   ・モブ素材13種      … 全13種が 10/10 ティアに出る
#   ・鍵11種            … 2〜4ティア(唯一まともに帯分けされていた)
#   → 「どのティアを漁っても同じ表」。ティアを10段に割った意味が消えていた。
#
# 改訂後の規則(下の割り当て表が唯一の宣言):
#   ・**新トレジャースレッド10種は 1ティア1種の完全専属**。そのティアを漁る以外に入手経路が無い
#     (儀式にもクラフトにもレシピを置いていない)。これが「漁る意義」の本体。
#   ・**既存の品質系スレッド10種は 看板1ティア + 隣接1ティア(薄く)** の2ティアだけ。
#   ・**モブ素材13種は 浅/中/深の3帯**に割る。
#   ・**鍵は1本につき2ティア**まで(帯の中でもさらに絞る)。
#   ・**リセット系3種も1本につき2ティア**。
#   共通の考え方は「どこでも出るものは、どこを漁る理由にもならない」。
# ============================================================================

# 新トレジャースレッド10種(CMD 300070-300079)。ティアごとに1種の完全専属。
# 強い軸ほど上位ティアへ置く。id は fork の threads.yml と一致させること。
TREASURE_THREADS = {
    "T10_極上": ("thread_skyfall", "墜撃(空中攻撃)"),
    "T9_最上位": ("thread_maelstrom", "渦動(範囲ダメージ)"),
    "T8_上位": ("thread_exsanguinate", "瀉血(出血ダメージ量)"),
    "T7_準上位": ("thread_rampart", "城塞(防御率)"),
    "T6_中上位": ("thread_bastion", "鉄壁(物理守備力)"),
    "T5_中位": ("thread_wardstone", "護法(魔法守備力)"),
    "T4_中下位": ("thread_farsight", "遠見(距離ダメージ)"),
    "T3_下位": ("thread_piercingshot", "貫矢(矢貫通)"),
    "T2_準下位": ("thread_swiftarrow", "疾矢(矢速度)"),
    "T1_最下位": ("thread_truesight", "精射(弓精度)"),
}

# 既存の品質系スレッド10種。(看板ティア, 隣接ティア)。隣接は確率を 1/3 に落とす。
# 看板は「そのスレッドを狙うならこの構造物帯」を作るためのもの。
QUALITY_THREAD_HOME = {
    "thread_artisan": ("T10_極上", "T9_最上位"),
    "thread_ritualist": ("T9_最上位", "T10_極上"),
    "thread_appraiser": ("T8_上位", "T7_準上位"),
    "thread_scholar": ("T7_準上位", "T8_上位"),
    "thread_perfumer": ("T6_中上位", "T5_中位"),
    "thread_better_fortune": ("T5_中位", "T6_中上位"),
    "thread_gacha": ("T4_中下位", "T3_下位"),
    "thread_role_luck": ("T3_下位", "T4_中下位"),
    "thread_role_effeciency": ("T2_準下位", "T3_下位"),
    "thread_angler": ("T1_最下位", "T2_準下位"),
}

# モブ素材13種 → 浅/中/深の3帯。深部の素材は上位3ティアにしか出ない。
MATERIAL_BANDS = {
    "DEEP": (["ravager_hide", "piglin_brute_plate", "guardian_spine", "hoglin_fang", "piglin_ear"],
             ["T10_極上", "T9_最上位", "T8_上位"]),
    "MID": (["pillager_plate", "witch_elixir", "endermite_soot", "bogged_mossy_bone"],
            ["T7_準上位", "T6_中上位", "T5_中位"]),
    "SHALLOW": (["stray_cloth", "skeleton_horse_bone", "husk_cloth", "parched_sandy_bone"],
                ["T4_中下位", "T3_下位", "T2_準下位", "T1_最下位"]),
}

# 鍵は1本につき2ティアまで。帯(浅/中/深)の中でもさらに散らす。
KEY_HOME = {
    "key_dark_cathedral": ["T10_極上", "T9_最上位"],
    "key_nether_bell": ["T9_最上位", "T8_上位"],
    "key_nether_wastes": ["T10_極上", "T8_上位"],
    "key_climb": ["T8_上位", "T7_準上位"],
    "key_palace": ["T7_準上位", "T6_中上位"],
    "key_knight_castle": ["T6_中上位", "T5_中位"],
    "key_fireworks": ["T8_上位", "T6_中上位"],
    "key_bridge": ["T5_中位", "T4_中下位"],
    "key_city": ["T4_中下位", "T3_下位"],
    "key_sewer_maze": ["T3_下位", "T2_準下位"],
    "key_steamworks": ["T5_中位", "T2_準下位"],
}

# リセット系も1本につき2ティア。極低確率なのは従来どおり。
RESET_HOME = {
    "skill_node_lock": ["T10_極上", "T8_上位"],
    "skill_tree_reset": ["T9_最上位", "T7_準上位"],
    "stat_reroll_ticket": ["T10_極上", "T7_準上位"],
}

# 旧版(ハードコード15件)が対象にしていたバニラ表のうち、structures.json に載らなかったもの。
# 案1 のデータパックは要塞を stronghold/* へ差し替えるので corridor は参照されないが、
# データパックを外した構成でも報酬が消えないよう保険として明示的に足す。
# 「対象から落ちたことに気づけない」のが一番高くつくので、落ちた分はここに書き残す。
# 10 ティア化で要塞の他の表(stronghold/generic・jail)は T8 に落ちたので、corridor も T8 に置く。
LEGACY_SAFETY = {
    "T8_上位": ["minecraft:chests/stronghold_corridor"],
}

TIER_DOC = {
    "T10_極上": "既存の中身が最も豪華な帯。要塞跡の宝物庫・洋館・エンドの塔・上位ヴォールトなど。"
                "現実の芯と深部の鍵が最も厚い。",
    "T9_最上位": "極上に次ぐ帯。深部の鍵はここまで。芯もまだ厚い。",
    "T8_上位": "要塞の主要チェストとダンジョン main 帯。中位の鍵の本命。深部素材の下限。",
    "T7_準上位": "塔の最上階・城の宝物庫帯。バニラ試練の間の報酬チェストもここ。中位素材の上限。",
    "T6_中上位": "前哨基地・醸造室帯。中位の鍵の下限で、圧縮ブロックが鉄になる。",
    "T5_中位": "図書室・工房帯。浅部の鍵の本命。中位素材の下限。",
    "T4_中下位": "倉庫・墓地帯。浅部の鍵と浅部素材が中心。",
    "T3_下位": "旗・石工などの端物チェスト帯。浅部の鍵は薄く出る。",
    "T2_準下位": "生活雑貨帯。素材と圧縮ブロックが中心で、鍵とスレッドは当たり枠。",
    "T1_最下位": "食料箱・樽・低級スポナーなどの端物。鍵と芯は出ないが、"
                 "**このティア専属のスレッドと潮読みの看板がここにある**ので漁る理由はある。"
                 "既存戦利品の量は2倍。",
}


def _keys_for(tier):
    """このティアに置く鍵。KEY_HOME(1本につき2ティア)から引く。"""
    return [k for k, tiers in KEY_HOME.items() if tier in tiers]


def _resets_for(tier):
    return [k for k, tiers in RESET_HOME.items() if tier in tiers]


def _materials_for(tier):
    for _band, (items, tiers) in MATERIAL_BANDS.items():
        if tier in tiers:
            return items
    return []


def _quality_threads_for(tier):
    """(スレッドid, 確率係数)。看板は等倍、隣接は 1/3。それ以外のティアには出さない。"""
    out = []
    for tid, (home, near) in QUALITY_THREAD_HOME.items():
        if tier == home:
            out.append((tid, 1.0))
        elif tier == near:
            out.append((tid, 1.0 / 3.0))
    return out


def entries_for(tier):
    """ティアごとの追加候補。TIERS の表と、上の専属割り当て表だけを見る(段ごとの分岐は持たない)。"""
    t = TIERS[tier]
    out = []
    if t["core"] is not None:
        out.append(("# --- 厳選の触媒(現実の芯)", None))
        if t["core_range"]:
            out.append(("custom:reality_thread_core", t["core"], t["core_range"][0], t["core_range"][1]))
        else:
            out.append(("custom:reality_thread_core", t["core"]))

    # --- トレジャースレッド(このティア専属。ここ以外では絶対に出ない) ---
    treasure = TREASURE_THREADS.get(tier)
    if treasure is not None:
        tid, label = treasure
        out.append(("# --- ★このティア専属のスレッド: %s。他のどのティアにも出ない" % label, None))
        # 専属なので確率は品質系より厚くする(1ティアぶんの母数しか無いため)。
        out.append(("custom:" + tid, round(t["threads"] * 2.5, 4)
                    if t["threads"] is not None else 0.006))

    if t["keys"] is not None:
        kind, chance = t["keys"]
        keys = _keys_for(tier)
        out.append(("# --- レシピの無い鍵。2026-08-23: 1本につき2ティアまでへ絞った(%s帯)" % KEY_LABEL[kind], None))
        out += [("custom:" + k, chance) for k in keys]
    if t["threads"] is not None:
        quality = _quality_threads_for(tier)
        if quality:
            out.append(("# --- 品質系スレッド。看板は等倍、隣接ティアは 1/3。ここに無い種はこのティアでは出ない", None))
            out += [("custom:" + tid, round(t["threads"] * factor, 5)) for tid, factor in quality]
    resets = _resets_for(tier)
    if resets and t["resets"] is not None:
        out.append(("# --- リセット系(極低確率)。2026-08-23: 1本につき2ティアまで", None))
        out += [("custom:" + s_, t["resets"]) for s_ in resets]
    materials = _materials_for(tier)
    out.append(("# --- モブ素材(浅/中/深の3帯へ分割済み)・ソース", None))
    out += [("custom:" + m, t["materials"]) for m in materials]
    if t["crystal"] is not None:
        out.append(("custom:source_crystal", t["crystal"]))
    shard_chance, shard_min, shard_max = t["shard"]
    out.append(("custom:source_shard", shard_chance, shard_min, shard_max))
    if t["apple"] is not None:
        out.append(("# --- 旧版から引き継ぐ枠(消すと入手経路が1本減るので残す)", None))
        out.append(("ENCHANTED_GOLDEN_APPLE", t["apple"]))
    out.append(("# --- 圧縮ブロック(たまに大口の資源が出る枠)", None))
    for item, chance in t["blocks"]:
        out.append(("custom:" + item, chance))
    return out


HEADER = """\
# ============================================================
# ArsPaper 構造物ルートチェストへの追加抽選 (loot-tables.yml)
# ============================================================
# 「構造物を漁る旨み」を作るファイル。ルートテーブルが実体化する瞬間に割り込んで、
# (1) 既存の戦利品の個数を増やし、(2) ここに書いたアイテムを確率で足す。
#
# 割り込む入口は2つある(2026-08-23、W-187):
#   ・LootGenerateEvent      … 普通のチェスト・樽
#   ・BlockDispenseLootEvent … **ヴォールトと試練のスポナー**
#     (これらは LootGenerateEvent を発火しない。PaperMC #11680 は「対応しない」でクローズ)
#
# 【2026-08-16 全面改訂】資源サーバへ入れるデータパック(案1)の構造物を実際に
# ダウンロードして全ルートテーブルを読み、**既存の戦利品の豪華さ順** にティアへ
# 割り直した。ユーザー方針は「デフォルトの戦利品が豪華なところには豪華な報酬を、
# 微妙なところには微妙な報酬を。ベースのルートがバニラであっても同じ」。
#
# 【2026-08-21 改訂】5 ティア → **10 ティア**。5 段だと1段に 86 本入る帯があり、
# どの構造物を漁っても同じ報酬表だった(ユーザー指示「それぞれのダンジョンを漁る
# 意味を作るため 10 ティアくらいに細分化」)。あわせて **鍵の確率を上げ、
# スレッドの確率を下げた**。鍵は浅部/中位/深部の3帯に分け、深部の鍵は上位2ティア
# にしか置かない ── 行きたいダンジョンの鍵が出る構造物を選ぶ動機を作るため。
#
# ⚠ このファイルの pools は **tmp/worldgen/gen_loot_yml.py の生成物** である。
#   手で表を足し引きすると次の再生成で消える。方針を変えるときはスクリプト側を直すこと。
#   根拠(どの表がどのスコアで何ティアになったか)は tmp/worldgen/report/loot-tiers.csv。
#
# ⚠ 旧版は対象を `dungeons_and_taverns:*` と書いていたが、**その名前空間は実在しない**
#   (Dungeons and Taverns の実際の名前空間は `nova_structures`)。つまり旧版の
#   データパック向けプールは 1 度も発火していなかった。
#
# ------------------------------------------------------------
# enabled: 全体スイッチ。false でこのファイルの抽選を止める
#          (ウォーデンの残響の欠片は config.yml の mob-drops 側なので影響しない)
#
# block-datapack-enchant-books:
#   データパックが独自に足したエンチャント(nova_structures:* など)を持つ
#   エンチャント本を、ルート生成時に取り除く。TF 側のエンチャント体系と
#   整合しないため既定 true。バニラの修繕は TF の removed-vanilla-items が別途消す。
#   ⚠ 除去は「ArsPaper が自前の本を足す前」に走るので、type: enchant-book で
#     足したマナ系の本は巻き込まれない。
#
# pools.<プールID>:
#   tables:  対象ルートテーブル。書き方は3通りで、どれを混ぜてもよい
#     - simple_dungeon                      … パスの最後の要素と一致
#     - minecraft:chests/simple_dungeon     … 完全一致
#     - nova_structures:*                   … その namespace のルートテーブル全部
#   quantity-multiplier:
#     既存の戦利品(バニラ/データパックが生成した分)の個数に掛ける倍率。
#     端数は確率で +1 する(1.5 なら 50% で切り上げ)。省略/1.0 で無効。
#     ⚠ 追加抽選で足したアイテムには掛からない(二重に増えるため)。
#   rolls:   抽選回数(1-16)。各候補について rolls 回だけ独立に確率判定する。
#   entries: 候補
#     - type:  item(既定) / enchant-book
#       item:  バニラ Material 名 (DIAMOND) または custom:<ID>
#       chance: 0.0-1.0。1判定あたりの確率
#       min / max: 個数の範囲(省略時 1)
#
# 【2026-08-23 改訂 (W-187)】ユーザー指摘「スレッドやカギなどが各tier固有のものが少ない。
#   同じ tier に全種類入っているのが問題。それぞれを漁る意義を設けたい」。実測すると
#   品質系スレッド10種が 9/10 ティア、モブ素材13種が 10/10 ティアに出ていて、
#   帯分けできていたのは鍵だけだった。方針【強く専属化】で作り直した:
#     ・**トレジャースレッド10種(新設・CMD 300070-300079)は 1ティア1種の完全専属**。
#       儀式にもクラフトにもレシピが無いので、そのティアを漁る以外の入手経路が無い。
#     ・既存の品質系スレッド10種は **看板1ティア + 隣接1ティア(確率 1/3)** だけ。
#     ・モブ素材13種は **浅/中/深の3帯**。
#     ・鍵とリセット系は **1本につき2ティア**まで。
#
# 【2026-08-23 改訂 (W-187)】トライアルチャンバーとヴォールトを **対象へ戻した**。
#   ユーザー報告「試練のスポナーにカスタム登録のアイテムが反映されず、一部チェストが空」。
#   2026-08-16 に経済理由で意図的に除外していたが、ユーザー判断は
#   「デフォルトの試練のスポナーの中身の豪華さで tier を判断 => tier 割り当て」なので、
#   特別扱いをやめて他の表と同じ豪華さスコアに載せた。
#   ⚠ ヴォールトと試練のスポナーは **LootGenerateEvent を発火しない**
#     (PaperMC #11680 は「対応しない」でクローズ)。ArsPaper 側の
#     BlockDispenseLootEvent リスナーが受け持つので、片方だけでは無言で効かない。
#
# 【バランスの考え方】
# ・構造物は「厳選スレッドと、作れない鍵の入手経路」。ステ上限を上げる縦強化は置かない。
# ・村・壺/発掘は **意図的に対象外**(無限湧き・大量にあるので経済が壊れる)。
# ・深淵の合金 / 束縛者の欠片は構造物から出さない(ダンジョン主の独占素材という設定を守る)。
# ============================================================

# 設定リファレンス(本文コメント移設先): docs/config-reference/arspaper/loot-tables.md
enabled: true
block-datapack-enchant-books: true

pools:
"""


def render():
    rows = T.assign(T.collect())
    per_tier = {}
    for r in rows:
        per_tier.setdefault(r["tier"], []).append(r)

    buf = [HEADER]
    for tier in T.TIER_NAMES:
        rs = per_tier.get(tier, [])
        if not rs:
            continue
        pool_id = tier.split("_")[0].lower() + "_structures"
        buf.append("")
        buf.append("  # " + "-" * 70)
        buf.append("  # %s — %d 本 (既存戦利品スコア %.1f〜%.1f)" %
                   (tier, len(rs), rs[-1]["score"], rs[0]["score"]))
        buf.append("  # %s" % TIER_DOC[tier])
        buf.append("  # " + "-" * 70)
        buf.append("  %s:" % pool_id)
        buf.append("    quantity-multiplier: %.2f" % TIERS[tier]["multiplier"])
        buf.append("    rolls: 1")
        buf.append("    tables:")
        for r in rs:
            buf.append("      - %s" % r["table"])
        for extra in LEGACY_SAFETY.get(tier, []):
            buf.append("      # データパック無し構成のための保険(structures.json には現れない表)")
            buf.append("      - %s" % extra)
        buf.append("    entries:")
        for e in entries_for(tier):
            if e[1] is None:
                buf.append("      %s" % e[0])
                continue
            item, chance = e[0], e[1]
            buf.append("      - item: %s" % item)
            buf.append("        chance: %s" % ("%.3f" % chance).rstrip("0").rstrip("."))
            if len(e) > 2:
                buf.append("        min: %d" % e[2])
                buf.append("        max: %d" % e[3])
        book = TIERS[tier]["book"]
        if book is not None:
            buf.append("      # ArsPaper 自前のマナ系エンチャント本(体系が整合しているので残す)")
            buf.append("      - type: enchant-book")
            buf.append("        chance: %s" % ("%.3f" % book).rstrip("0").rstrip("."))
    buf.append("")
    return "\n".join(buf)


if __name__ == "__main__":
    text = render()
    with io.open(DEST, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    print("書き出し: %s (%d 行)" % (DEST, text.count("\n") + 1))
