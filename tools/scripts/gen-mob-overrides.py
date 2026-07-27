"""combat/mob-overrides.yml をコンセプト付きで生成する。

【なぜ防御の"率"だけを書くのか】
mob-overrides の値は絶対値であり、396体中265体は EliteMobs の `level: dynamic`
(入場時に選んだレベルへ追従する)。ここで max-health / attack-power / flat-defense を
書くとレベル追従が壊れ、Lv1でもLv100でも同じ値になってしまう。
よってレベル依存の項目はランプ(combat/mob-import.yml)に任せ、レベル非依存の
defense-rate / resistance だけでダンジョンのコンセプトを表現する。

【コンセプト】
  MAGIC_FAVORED   物理装甲が厚い → 物理では削りにくい → 魔法ビルドが活きる
  PHYSICAL_FAVORED 魔法防御が厚い → 魔法では削りにくい → 物理ビルドが活きる
  HYBRID          個体ごとに物理耐性型/魔法耐性型が混在 → 両方の手段が要る

【レバーの選び方】 defense-rate は貫通で抜ける層、resistance は抜けない層
(dungeon/themes.yml のコメント準拠)。主レバーを defense-rate にして貫通ビルドに
逆転の余地を残し、resistance は薄い下限としてだけ乗せる。
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from em_ja_names import DUNGEON_JA, MOB_JA, SUFFIX_JA, DISAMBIGUATION_JA  # noqa: E402

EM = "D:/game/minecraft/PaperServer/TrinityForge/plugins/EliteMobs"
# 既定EliteMobsダンジョンの一覧(ワールド名・日本語名・モブid)。config-editor が
#   ・mob-overrides でワールドID/モブIDを編集不可にする判定
#   ・ダンジョンゲートのダンジョンIDセレクト候補
# に使う。config ではなく「EliteMobs が何を同梱しているか」の台帳なので editor 側に置く。
MANIFEST = ("C:/Users/T-319/Documents/Program/ClaudeCodeDev/products/minecraft/"
            "trinityforge/tools/config-editor/public/data/elitemobs-dungeons.json")
SRC = ("C:/Users/T-319/Documents/Program/ClaudeCodeDev/products/minecraft/"
       "trinityforge/TrinityForge/src/main/resources/combat/mob-overrides.yml")

MAGIC, PHYSICAL, HYBRID = "MAGIC_FAVORED", "PHYSICAL_FAVORED", "HYBRID"

# ワールド -> (コンセプト, 日本語の狙い)
CONCEPTS = {
    "em_id_the_mines":        (MAGIC, "鉱山の岩石構造体。硬い外殻を物理で削るのは非効率"),
    "em_id_the_deep_mines":   (MAGIC, "深層鉱山。地上鉱山よりさらに装甲が厚い"),
    "em_id_the_quarry":       (MAGIC, "採石場の重機械と石像。物理耐性が高い"),
    "em_id_the_city":         (MAGIC, "ドワーフの重装兵。鍛えた甲冑で物理を弾く"),
    "em_knight_castle":       (MAGIC, "騎士団。プレートアーマーの塊"),
    "em_steamworks_lair":     (MAGIC, "蒸気機械。金属外装で刃が通らない"),
    "em_fireworks":           (MAGIC, "火薬技師の機械仕掛け。装甲で覆われている"),

    "em_the_dark_cathedral":  (PHYSICAL, "呪術聖堂。魔法障壁が厚く、鋼で殴るほうが早い"),
    "em_id_binder_of_worlds": (PHYSICAL, "次元術師。魔法を吸収する"),
    "em_id_the_nether_bell":  (PHYSICAL, "儀式の鐘。術式の守りが強い"),
    "em_id_the_cave":         (PHYSICAL, "熱術の坑道。魔力の霧が魔法を減衰させる"),
    "em_north_pole":          (PHYSICAL, "氷雪の魔物。氷魔法への耐性が高い"),
    "em_hallosseum":          (PHYSICAL, "呪われた闘技場。亡霊は魔法を通しにくい"),

    "em_id_the_climb":        (HYBRID, "アンデッドと獣使いが混在。骨は物理を、影は魔法を弾く"),
    "em_sewer_maze":          (HYBRID, "下水の雑多な群れ。相手ごとに有効打が変わる"),
    "em_id_the_nether_wastes": (HYBRID, "ネザーの混成軍。装甲持ちと術者が入り混じる"),
    "em_id_the_bridge":       (HYBRID, "古代守護者。構造体と術式の複合"),
    "em_id_the_palace":       (HYBRID, "宮殿の親衛隊。前衛と後衛で耐性が違う"),
    "em_adventurers_guild":   (HYBRID, "ギルド闘技場。全ビルドの試し斬り場(計測用ダミーは無耐性のまま)"),
}
# エンチャント課題は「課題ごとに要求ビルドが変わる」ことを狙い、番号の偶奇で交互に割り当てる。
for _n in range(1, 21):
    CONCEPTS[f"em_id_enchantment_challenge_{_n}"] = (
        PHYSICAL if _n % 2 else MAGIC,
        f"エンチャント課題{_n}。課題ごとに要求ビルドが入れ替わる")

# 役割 -> (係数, ラベル)。フェーズが進むほど厚くなる。
ROLE_FACTORS = {
    "add":   (0.50, "召喚体/騎乗体"),
    "trash": (1.00, "雑魚"),
    "mini":  (1.30, "ミニボス"),
    "boss":  (1.60, "ボス"),
    "boss1": (1.50, "ボス第1段階"),
    "boss2": (1.70, "ボス第2段階"),
    "boss3": (1.90, "ボス第3段階"),
}

# 【重要】mob-import.yml の素の耐性(既定 0.12)を必ず下限として維持する。
# ここを下回る値を書くと、コンセプトの"通りやすい側"が変更前より柔らかくなり、
# 全ダンジョンが一律に弱体化する(2026-07-26 レビューで検出した実バグ)。
# 素の防御率は 0.0 なので、コンセプトは主に defense-rate で表現し、
# resistance は「素の値 + 上乗せ」を役割係数で伸ばした上で素の値を下限にクランプする。
MOB_IMPORT = ("C:/Users/T-319/Documents/Program/ClaudeCodeDev/products/minecraft/"
              "trinityforge/TrinityForge/src/main/resources/combat/mob-import.yml")

STRONG_RATE, STRONG_RESIST_EXTRA = 0.22, 0.06   # そのビルドが通りにくい側
WEAK_RATE, WEAK_RESIST_EXTRA = 0.04, 0.0        # 通りやすい側
HYBRID_BOSS_RATE, HYBRID_BOSS_RESIST_EXTRA = 0.14, 0.03  # ハイブリッドのボスは両刀可
RATE_CAP, RESIST_CAP = 0.45, 0.35

# ── バニラEXP(vanilla-exp)のランプ ─────────────────────────────────────────
# 【なぜ緩い指数なのか】 モブのHP/攻撃はプレイヤーの伸びに追従して指数的に上がるが、
# それは「同帯の装備で戦えば所要時間はほぼ一定」になるよう校正されているため
# (mob-import.yml: 雑魚 ~3〜6発)。一方バニラEXPの用途(エンチャント/金床)のコストは
# プレイヤーレベルにしか依存せず、ダンジョンレベルでは変わらない。つまりEXPを
# HPと同じ growth 1.072 で伸ばすと、同じ手間で1000倍の報酬になり完全に破綻する。
# ここでは「進行の実感」ぶんだけを緩い指数(1.008 = Lv100で約2.2倍)に乗せ、
# 残りは線形項で表現する。Lv1→Lv100 で約9倍。
# 【規模の根拠】 バニラで Lv0→30 到達に 1395 EXP、Lv30エンチャ後の30→27→30 再充填が
# 約282 EXP。下記の値だとLv100帯の1ダンジョン周回(雑魚80/中ボス6/ボス3段)で約5300 EXP
# ≒ 再充填19回ぶん。「エンチャントを気兼ねなく回せるが無制限ではない」帯に置いた。
EXP_GROWTH, EXP_GROWTH_INTERVAL = 1.008, 1.0
EXP_TRASH_BASE, EXP_TRASH_PER_LEVEL = 4.0, 0.12
EXP_FACTORS = {
    "add":   0.5,    # 召喚体/増援: 無限湧きの可能性があるので雑魚より低く
    "trash": 1.0,
    "mini":  4.0,
    "boss":  12.0,
    "boss1": 10.0,
    "boss2": 14.0,
    "boss3": 20.0,
}
EXP_PREVIEW_LEVELS = (1, 50, 100)


def exp_ramp(role):
    """役割に応じた vanilla-exp ランプ。growth は全モブ共通(規模だけを役割で変える)。"""
    factor = EXP_FACTORS[role]
    return (round(EXP_TRASH_BASE * factor, 3),
            round(EXP_TRASH_PER_LEVEL * factor, 4))


def exp_at(base, per_level, level):
    """Java側 ConversionPolicy.Ramp#at + MobOverridesConfig#toExpAmount と同じ計算。"""
    value = (base + per_level * level) * (EXP_GROWTH ** (level / EXP_GROWTH_INTERVAL))
    return max(0, round(value))


def baseline_resistance():
    """combat/mob-import.yml の素の耐性。ハードコードせず読み取り、同期ズレを防ぐ。"""
    txt = read(MOB_IMPORT)
    block = re.search(r"^physical:\n(?:\s+.*\n)*?\s+resistance:\s*\{[^}]*base:\s*([0-9.]+)",
                      txt, re.M)
    return float(block.group(1)) if block else 0.12



# ── 日本語の表示名(display-name) ─────────────────────────────────────────
# EliteMobs 側の英語名を em_ja_names.py の訳表で日本語化し、同一ダンジョン内で衝突する
# 場合だけ識別子(第N波 / 第N段階 / 個別指定)を足す。表示専用で、モブidそのものは変えない。
WAVE_RE = re.compile(r"wave_(\d+)")


def source_name(folder, filename):
    """custombosses ファイルの name: を色コード/プレースホルダ抜きで返す。"""
    txt = read(os.path.join(EM, "custombosses", folder, filename))
    m = re.search(r"^name:\s*(.+)$", txt, re.M)
    if not m:
        return ""
    raw = m.group(1).strip().strip('"').strip("'")
    raw = re.sub(r"&[0-9a-fk-orA-FK-OR]", "", raw)
    return re.sub(r"\$[a-zA-Z]+", "", raw).strip()


def display_name(mob_id, english):
    base = MOB_JA.get(english, english) or mob_id
    low = mob_id.lower()
    suffix = "".join(label for token, label in SUFFIX_JA if token in low)
    return base + suffix


def disambiguate(mob_id):
    """同名衝突時にだけ足す識別子。一般則(第N波)→個別指定→最後の砦としてid全体。"""
    explicit = DISAMBIGUATION_JA.get(mob_id)
    if explicit:
        return explicit
    wave = WAVE_RE.search(mob_id.lower())
    if wave:
        return f" (第{int(wave.group(1))}波)"
    return f" ({mob_id})"


def resolve_display_names(mob_ids, english_by_id):
    """1ダンジョン分の {モブid: 日本語表示名}。衝突したグループにだけ識別子を付ける。"""
    grouped = {}
    for mob_id in mob_ids:
        name = display_name(mob_id, english_by_id.get(mob_id, ""))
        grouped.setdefault(name, []).append(mob_id)
    resolved = {}
    for name, ids in grouped.items():
        for mob_id in ids:
            resolved[mob_id] = name + disambiguate(mob_id) if len(ids) > 1 else name
    return resolved

def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


def package_index():
    index = {}
    pkg_dir = os.path.join(EM, "content_packages")
    for name in sorted(os.listdir(pkg_dir)):
        if not name.endswith(".yml"):
            continue
        txt = read(os.path.join(pkg_dir, name))
        world = re.search(r"^worldName:\s*(\S+)", txt, re.M)
        folder = re.search(r"^dungeonConfigFolderName:\s*(\S+)", txt, re.M)
        ctype = re.search(r"^contentType:\s*(\S+)", txt, re.M)
        enabled = re.search(r"^isEnabled:\s*(\S+)", txt, re.M)
        if not world or (enabled and enabled.group(1).lower() != "true"):
            continue
        world_name = world.group(1)
        entry = (world_name, name[:-4], ctype.group(1) if ctype else "?")
        keys = {world_name}
        if folder:
            keys.add(folder.group(1))
        for prefix in ("em_id_", "em_"):
            if world_name.startswith(prefix):
                keys.add(world_name[len(prefix):])
        for key in keys:
            index.setdefault(key, entry)
    return index


def arena_index():
    index = {}
    arena_dir = os.path.join(EM, "customarenas")
    if not os.path.isdir(arena_dir):
        return index
    for name in sorted(os.listdir(arena_dir)):
        if not name.endswith(".yml"):
            continue
        txt = read(os.path.join(arena_dir, name))
        start = re.search(r"^startLocation:\s*([^,\s]+)", txt, re.M)
        label = re.search(r"^arenaName:\s*(.+)$", txt, re.M)
        if not start:
            continue
        display = label.group(1).strip() if label else name[:-4]
        index[display.lower().replace(" ", "_")] = (start.group(1), display)
    return index


def mob_ids(folder):
    """importmobs / PDCスタンプと同じ正規化: 拡張子を落とし、残る '.' を '_' に。"""
    return [mob_id for mob_id, _ in mob_files(folder)]


def mob_files(folder):
    """(モブid, ファイル名) の一覧。id の正規化は importmobs / PDCスタンプと同じ。"""
    path = os.path.join(EM, "custombosses", folder)
    pairs = []
    for name in sorted(os.listdir(path)):
        low = name.lower()
        if not (low.endswith(".yml") or low.endswith(".yaml")):
            continue
        mob_id = (name[:-4] if low.endswith(".yml") else name[:-5]).replace(".", "_")
        pairs.append((mob_id, name))
    return pairs


# フェーズ表記は2系統ある: `_p1/_p2/_p3` と `_phase_0/_phase_1/_phase_2`(0始まり)。
PHASE_P = re.compile(r"_p([123])(?:_|$)")
PHASE_WORD = re.compile(r"_phase_(\d+)(?:_|$)")


def phase_of(mob):
    """1始まりのフェーズ番号。フェーズ表記が無ければ None。"""
    m = PHASE_P.search(mob)
    if m:
        return int(m.group(1))
    m = PHASE_WORD.search(mob)
    if m:
        return min(int(m.group(1)) + 1, 3)  # phase_0 が第1段階
    return None


def base_name(mob):
    """同一個体の別バリアント(フェーズ/召喚体/騎乗体)を1つの名前に畳む。

    ハイブリッドの耐性型はこの名前で決めるので、`bone_champion` と
    `bone_champion_p2` が別タイプに割れることがない。
    """
    name = PHASE_P.sub("", PHASE_WORD.sub("", mob))
    return re.sub(r"_(summon|mount|drone|corpse)(_.*)?$", "", name)


def role_of(mob, phase_bases):
    """モブidから役割を推定する。

    `phase_bases` は「フェーズ違いが存在する個体の base 名」集合。これに載っている
    素の名前(例: bone_champion)は、名前に boss と入っていなくても多段ボスの第1段階。
    """
    m = mob.lower()
    if "dummy" in m and "training" in m:
        return None  # 計測器: 一切いじらない
    phase = phase_of(m)
    if "mini_boss" in m or "miniboss" in m:
        return "mini"
    # 増援・召喚体はボス戦の一部でも「add」。名前に boss を含んでいても本体と同格にはしない
    # (例: sewer_tier_40_fire_boss_reinforcement)。騎乗体だけはボス本体が乗る器なので除外する。
    if any(k in m for k in ("summon", "reinforcement", "drone", "corpse", "dummy")):
        return "add"
    if "boss" in m or phase is not None or m in phase_bases:
        return f"boss{phase or 1}"
    if "mount" in m:
        return "add"
    return "trash"


def stats_for(concept, role, identity):
    """(physical, magical) の (defense-rate, resistance) を返す。

    `identity` はハイブリッドで耐性型を振り分けるためのキー(同一個体は同じ値)。
    通し番号ではなく名前から決めるので、モブが増減しても既存個体の型が入れ替わらない。
    """
    factor = ROLE_FACTORS[role][0]
    base_resist = baseline_resistance()

    def scaled(rate, resist_extra):
        # 耐性は素の値を絶対の下限にする。役割係数で縮む召喚体でも素の値は割らない。
        resist = max(base_resist, (base_resist + resist_extra) * factor)
        return (round(min(rate * factor, RATE_CAP), 3),
                round(min(resist, RESIST_CAP), 3))

    if concept == MAGIC:            # 物理装甲が厚い
        return scaled(STRONG_RATE, STRONG_RESIST_EXTRA), scaled(WEAK_RATE, WEAK_RESIST_EXTRA)
    if concept == PHYSICAL:         # 魔法防御が厚い
        return scaled(WEAK_RATE, WEAK_RESIST_EXTRA), scaled(STRONG_RATE, STRONG_RESIST_EXTRA)
    # HYBRID: ボスは両刀可、雑魚は個体ごとに耐性型を振り分けて両方の手段を要求する。
    if role.startswith("boss"):
        both = scaled(HYBRID_BOSS_RATE, HYBRID_BOSS_RESIST_EXTRA)
        return both, both
    if sum(identity.encode("utf-8")) % 2 == 0:
        return scaled(STRONG_RATE, STRONG_RESIST_EXTRA), scaled(WEAK_RATE, WEAK_RESIST_EXTRA)
    return scaled(WEAK_RATE, WEAK_RESIST_EXTRA), scaled(STRONG_RATE, STRONG_RESIST_EXTRA)


def resolve_world(folder, packages, arenas):
    if folder in packages:
        world, pkg, ctype = packages[folder]
        return world, f"{pkg} ({ctype})"
    for key, (world, display) in arenas.items():
        if folder.startswith(key):
            return world, f"アリーナ「{display}」"
    return None


CONCEPT_LABEL = {
    MAGIC: "魔法ビルドが活きる(物理装甲が厚い)",
    PHYSICAL: "物理ビルドが活きる(魔法防御が厚い)",
    HYBRID: "ハイブリッド(個体ごとに耐性型が混在)",
}



def write_manifest(dungeons, packages, arenas):
    """config-editor 用の既定EliteMobsダンジョン台帳を書き出す。

    mob-overrides に載る29ダンジョン(モブを持つもの)に加えて、モブを1体も持たない
    content_package も「ダンジョンゲートで選べる行き先」として全て載せる。ゲートは
    モブの有無と無関係に張れるため、ここを絞ると入場条件を設定できないダンジョンが出る。
    """
    known = {d["world"] for d in dungeons}
    extra = []
    for world, package, content_type in sorted(set(packages.values())):
        if world in known:
            continue
        extra.append({
            "world": world,
            "displayName": DUNGEON_JA.get(world, world),
            "package": package,
            "contentType": content_type,
            "mobs": [],
        })
    package_by_world = {world: (package, content_type)
                        for world, package, content_type in packages.values()}
    for dungeon in dungeons:
        package, content_type = package_by_world.get(dungeon["world"], ("", ""))
        dungeon["package"] = package
        dungeon["contentType"] = content_type
    payload = {
        "_comment": ("EliteMobs 同梱の既定ダンジョン台帳。tools/scripts/gen-mob-overrides.py が生成する。"
                     "手編集しないこと(再生成で上書きされる)。"),
        "dungeons": sorted(dungeons + extra, key=lambda d: d["world"]),
    }
    os.makedirs(os.path.dirname(MANIFEST), exist_ok=True)
    with open(MANIFEST, "w", encoding="utf-8", newline="\n") as f:
        json.dump(payload, f, ensure_ascii=False, indent=1)
        f.write("\n")


def main():
    packages, arenas = package_index(), arena_index()
    root = os.path.join(EM, "custombosses")
    folders = sorted(d for d in os.listdir(root) if os.path.isdir(os.path.join(root, d)))

    by_world = {}
    unmapped = []
    for folder in folders:
        ids = mob_ids(folder)
        if not ids:
            continue
        resolved = resolve_world(folder, packages, arenas)
        if resolved is None:
            unmapped.append((folder, len(ids)))
            continue
        world, label = resolved
        bucket = by_world.setdefault(world, {"sources": [], "mobs": [], "english": {}})
        bucket["sources"].append(f"{label} — custombosses/{folder} / {len(ids)}体")
        bucket["mobs"].extend(ids)
        for mob_id, filename in mob_files(folder):
            bucket["english"][mob_id] = source_name(folder, filename)

    manifest = []
    out = ["overrides:",
           "  # 全ダンジョン共通のフォールバック(ワールド個別指定が無いモブに使われる)",
           "  default:",
           "    mobs: {}"]
    total = neutral = 0
    for world in sorted(by_world):
        bucket = by_world[world]
        concept, intent = CONCEPTS.get(world, (HYBRID, "未分類(ハイブリッド既定)"))
        out.append("")
        for source in bucket["sources"]:
            out.append(f"  # {source}")
        out.append(f"  # ▼コンセプト: {CONCEPT_LABEL[concept]} — {intent}")
        out.append(f"  {world}:")
        dungeon_ja = DUNGEON_JA.get(world, world)
        out.append(f'    display-name: "{dungeon_ja}"')
        out.append("    mobs:")
        mobs = sorted(set(bucket["mobs"]))
        names_ja = resolve_display_names(mobs, bucket["english"])
        manifest.append({
            "world": world,
            "displayName": dungeon_ja,
            "mobs": [{"id": m, "displayName": names_ja[m]} for m in mobs],
        })
        # 「フェーズ違いが存在する個体」の base 名。名前に boss を含まない多段ボス
        # (例: bone_champion / bone_champion_p2) を第1段階として拾うために使う。
        phase_bases = {base_name(m).lower() for m in mobs if phase_of(m.lower()) is not None}
        for mob in mobs:
            role = role_of(mob, phase_bases)
            if role is None:
                out.append(f"      {mob}:")
                out.append(f'        display-name: "{names_ja[mob]}"')
                out.append("        # DPS計測用ダミー: 測定値が狂うため耐性は付けない。")
                out.append("        # EXPは0固定(繰り返し殴れる計測器が経験値稼ぎ場になるのを防ぐ)。")
                out.append("        stats: {}")
                out.append("        vanilla-exp: 0")
                out.append("        drops: []")
                neutral += 1
                total += 1
                continue
            (pr, pres), (mr, mres) = stats_for(concept, role, base_name(mob.lower()))
            eb, epl = exp_ramp(role)
            preview = " / ".join(f"Lv{lv}:{exp_at(eb, epl, lv)}" for lv in EXP_PREVIEW_LEVELS)
            out.append(f"      {mob}:   # {ROLE_FACTORS[role][1]} — EXP {preview}")
            out.append(f'        display-name: "{names_ja[mob]}"')
            out.append("        stats:")
            out.append(f"          physical: {{ defense-rate: {pr}, resistance: {pres} }}")
            out.append(f"          magical:  {{ defense-rate: {mr}, resistance: {mres} }}")
            out.append(f"        vanilla-exp: {{ base: {eb}, per-level: {epl}, "
                       f"growth: {EXP_GROWTH}, growth-interval: {EXP_GROWTH_INTERVAL} }}")
            out.append("        drops: []")
            total += 1

    if unmapped:
        out.append("")
        out.append("  # ワールドの対応が特定できなかったフォルダ(default スコープに手で足してください):")
        for folder, count in unmapped:
            out.append(f"  #   custombosses/{folder} ({count}体)")

    write_manifest(manifest, packages, arenas)

    src = read(SRC)
    marker = src.index("\noverrides:")
    with open(SRC, "w", encoding="utf-8", newline="\n") as f:
        f.write(src[:marker + 1] + "\n".join(out) + "\n")
    print(f"worlds={len(by_world)} mobs={total} neutral_dummies={neutral} unmapped={unmapped}")

    print("\n-- vanilla-exp 早見表 (役割 × レベル) --")
    for role in ("add", "trash", "mini", "boss1", "boss2", "boss3"):
        eb, epl = exp_ramp(role)
        cells = "  ".join(f"Lv{lv}={exp_at(eb, epl, lv):>5}" for lv in (1, 10, 25, 50, 75, 100))
        print(f"  {ROLE_FACTORS[role][1]:<10} base={eb:<6} per-level={epl:<7} {cells}")

    print("\n-- ダンジョン1周の概算EXP (雑魚80/中ボス6/ボス3段を仮定) --")
    for lv in (1, 25, 50, 75, 100):
        total_exp = (80 * exp_at(*exp_ramp("trash"), lv)
                     + 6 * exp_at(*exp_ramp("mini"), lv)
                     + sum(exp_at(*exp_ramp(r), lv) for r in ("boss1", "boss2", "boss3")))
        print(f"  Lv{lv:<4} {total_exp:>7} EXP   (Lv0→30到達1395EXP換算で {total_exp / 1395:.1f} 周分 / "
              f"Lv30エンチャ再充填282EXP換算で {total_exp / 282:.0f} 回分)")


if __name__ == "__main__":
    main()
