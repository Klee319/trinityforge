# -*- coding: utf-8 -*-
"""combat/mob-types.yml の mob-types: セクションを1.21.11の全生存EntityType(90種)で再生成する。

分類は Paper の Enemy マーカー(41種)を一次情報とし、そこに「反撃してくる中立」を手で足した3群:
  A 敵対(41)      : Enemy マーカー持ち。HP/攻撃/防御/係数までフル設計。
  B 反撃中立(9)   : Enemy ではないが player を殴り返す。同じくフル設計。
  C 友好・受動(40): 家畜/魚/村人など。レベル刻印だけ行い、HPと与ダメージはバニラ据え置き。

Bukkit の Monster/Animals インターフェースは分類に使えない — HOGLIN は Animals 判定なのに
実際は敵対で、この取り違えが「ホグリンだけ弱い」という形の穴になる。Enemy マーカーが正。

係数はベース(HP 380 / 攻撃 5.5 = ZOMBIE)に対する倍率。バニラでの脅威度を土台に、
「同帯装備で何発耐えるか」というTF側の設計意図へ寄せて手で調整してある。
既存13体(ZOMBIE系/SKELETON系/SPIDER系/CREEPER/ENDERMAN/WITCH)の値は据え置き。
"""
import io

BASE_HP, BASE_ATK = 380.0, 5.5

# name: (hp_mult, atk_mult)
HOSTILE = {
    # --- 虫・小型 (数で押すが単体は弱い) ---
    "SILVERFISH": (0.45, 0.75), "ENDERMITE": (0.45, 0.80),
    "VEX": (0.40, 1.15), "SLIME": (0.50, 0.80), "MAGMA_CUBE": (0.60, 1.00),
    # --- 標準ゾンビ帯 (既存13体の値はここで維持) ---
    "ZOMBIE": (1.00, 1.00), "HUSK": (1.00, 1.00), "DROWNED": (1.00, 1.00),
    "ZOMBIE_VILLAGER": (1.00, 1.00), "ZOMBIFIED_PIGLIN": (1.00, 1.00), "PIGLIN": (1.00, 1.00),
    "PARCHED": (1.00, 1.05),
    "SKELETON": (0.85, 1.10), "STRAY": (0.85, 1.10), "BOGGED": (0.80, 1.10),
    "SPIDER": (0.70, 1.15), "CAVE_SPIDER": (0.70, 1.15),
    "CREEPER": (0.75, 1.00), "WITCH": (0.90, 1.00), "CREAKING": (1.00, 1.20),
    # --- 中位 ---
    "ENDERMAN": (1.40, 1.20), "WITHER_SKELETON": (1.10, 1.35),
    "PILLAGER": (0.90, 1.15), "VINDICATOR": (0.95, 1.45),
    "EVOKER": (1.00, 1.25), "ILLUSIONER": (1.05, 1.20),
    "GUARDIAN": (1.00, 1.20), "BLAZE": (0.85, 1.25), "BREEZE": (1.00, 1.15),
    "GHAST": (0.50, 1.60), "PHANTOM": (0.70, 1.15), "SHULKER": (1.10, 1.10),
    "HOGLIN": (1.30, 1.30), "ZOGLIN": (1.35, 1.35), "PIGLIN_BRUTE": (1.50, 1.50),
    # --- 上位 ---
    "RAVAGER": (2.60, 1.80), "ELDER_GUARDIAN": (2.20, 1.50), "GIANT": (2.60, 1.60),
    # --- ボス ---
    "WARDEN": (6.00, 3.00), "WITHER": (5.00, 2.40), "ENDER_DRAGON": (5.50, 2.20),
}
NEUTRAL = {
    "IRON_GOLEM": (2.40, 1.70), "POLAR_BEAR": (1.00, 1.30), "PANDA": (0.85, 1.10),
    "WOLF": (0.60, 1.00), "LLAMA": (0.70, 0.90), "TRADER_LLAMA": (0.70, 0.90),
    "GOAT": (0.50, 1.00), "BEE": (0.35, 0.90), "DOLPHIN": (0.50, 1.00),
}
PASSIVE = [
    "ALLAY", "ARMADILLO", "AXOLOTL", "BAT", "CAMEL", "CAMEL_HUSK", "CAT", "CHICKEN", "COD",
    "COPPER_GOLEM", "COW", "DONKEY", "FOX", "FROG", "GLOW_SQUID", "HAPPY_GHAST", "HORSE",
    # MANNEQUIN は意図的に除外。1.21.11 の生存EntityTypeのうち唯一 Mob を実装していない
    # (LivingEntity ではあるが ArmorStand と同じ「置物」側)ので、戦闘モブの表であるここに
    # 載せるとレベル刻印が乗り、頭上表示などが飾りに対して働いてしまう。
    "MOOSHROOM", "MULE", "NAUTILUS", "OCELOT", "PARROT", "PIG", "PUFFERFISH",
    "RABBIT", "SALMON", "SHEEP", "SKELETON_HORSE", "SNIFFER", "SNOW_GOLEM", "SQUID", "STRIDER",
    "TADPOLE", "TROPICAL_FISH", "TURTLE", "VILLAGER", "WANDERING_TRADER", "ZOMBIE_HORSE",
    "ZOMBIE_NAUTILUS",
]


def num(x):
    """整数になる値は整数のまま出す(380.0 ではなく 380)。"""
    r = round(x, 4)
    return str(int(r)) if r == int(r) else str(r)


def combat_entry(name, hp_m, atk_m):
    hp, atk = num(BASE_HP * hp_m), num(BASE_ATK * atk_m)
    return (
        f"  {name}:\n"
        f"    level: 0\n"
        f"    coordinate-coefficient: 0.02\n"
        f"    max-health: {hp}\n"
        f"    armor-strength: 0\n"
        f"    physical:\n"
        f"      defense-rate: 0\n      resistance: 0\n"
        f"      damage-reduction: 0\n      flat-defense: 0\n"
        f"    magical:\n"
        f"      defense-rate: 0\n      resistance: 0\n"
        f"      damage-reduction: 0\n      flat-defense: 0\n"
        f"    attack:\n"
        f"      attack-power: {atk}\n"
        f"      flat-bonus-damage: 0\n      percent-bonus-damage: 0\n"
        f"      crit-chance: 0\n      crit-damage: 0\n"
        f"      penetration: 0.05\n      damage-modifier: 1\n      fixed-damage: 0\n"
        f"    level-coefficients:\n"
        f"      max-health: 0\n"
        f"      max-health-growth: 1.055\n"
        f"      max-health-growth-interval: 1.0\n"
        f"      armor-strength: 0.007\n"
        f"      physical:\n"
        f"        defense-rate: 0.007\n        resistance: 0.007\n"
        f"        damage-reduction: 0.007\n        flat-defense: 0.007\n"
        f"      magical:\n"
        f"        defense-rate: 0.007\n        resistance: 0.007\n"
        f"        damage-reduction: 0.007\n        flat-defense: 0.007\n"
        f"      attack:\n"
        f"        attack-power: 0\n"
        f"        attack-power-growth: 1.03\n"
        f"        attack-power-growth-interval: 1.0\n"
        f"        flat-bonus-damage: 0\n        percent-bonus-damage: 0\n"
        f"        penetration: 0.008\n        crit-chance: 0\n        crit-damage: 0\n"
        f"        damage-modifier: 0\n        fixed-damage: 0\n"
    )


def passive_entry(name):
    # max-health を書かない = バニラHPのまま。damage-modifier: 1 は「中立値」の明示で、
    # 省略すると0扱いになり与ダメージが半減してしまうため据え置きたいなら書く必要がある。
    return (
        f"  {name}:\n"
        f"    level: 0\n"
        f"    coordinate-coefficient: 0.02\n"
        f"    attack:\n"
        f"      damage-modifier: 1\n"
    )


# レベル帯ごとの討伐バニラEXP。オーバーワールドでも「敵のレベルに応じて経験値がもらえる」ようにする
# 本体で、mob-level-table.yml の tiers を生成する。バニラのゾンビが5EXPなので、Lv0帯の8は約1.6倍から
# 始まり、Lv85+で140(=約28倍)に届く。HPの伸び(growth 1.055)ほど急ではないのは、EXPをHPと同率で伸ばすと
# 「所要時間は一定なのに報酬だけ1000倍」になって進行が壊れるため(2026-07-26 の per-mob ランプと同じ理由)。
EXP_TIERS = [(0, 8), (10, 15), (25, 28), (45, 50), (65, 85), (85, 140)]


def level_table_tiers():
    """A群+B群のEntityTypeだけを対象にした帯を吐く。C群(友好)を含めないのが肝:
    含めると牛がゾンビと同じEXPを落とすようになり、「友好モブはバニラEXPのみ」が壊れる。"""
    targets = sorted(set(HOSTILE) | set(NEUTRAL))
    lines = ["tiers:\n"]
    for min_level, exp in EXP_TIERS:
        lines.append(f"  - min-level: {min_level}\n")
        lines.append(f"    vanilla-exp: {exp}\n")
        lines.append("    mobs:\n")
        for name in targets:
            lines.append(f"      - {name}\n")
    return "".join(lines)


def write_level_table():
    path = "../../TrinityForge/src/main/resources/combat/mob-level-table.yml"
    src = io.open(path, encoding="utf-8-sig").read()
    marker = "\ntiers:"
    head = src[:src.index(marker)]
    io.open(path, "w", encoding="utf-8", newline="\n").write(head + "\n" + level_table_tiers())
    return len(EXP_TIERS), len(set(HOSTILE) | set(NEUTRAL))


def main():
    path = "../../TrinityForge/src/main/resources/combat/mob-types.yml"
    src = io.open(path, encoding="utf-8-sig").read()
    head = src[:src.index("\nmob-types:")]

    out = [head, "\nmob-types:\n"]
    out.append("  # --- A群: 敵対モブ(Paper の Enemy マーカー基準・41種) ---------------------\n")
    for n in sorted(HOSTILE):
        out.append(combat_entry(n, *HOSTILE[n]))
    out.append("\n  # --- B群: 反撃してくる中立モブ(9種) --------------------------------------\n")
    for n in sorted(NEUTRAL):
        out.append(combat_entry(n, *NEUTRAL[n]))
    out.append("\n  # --- C群: 友好・受動モブ(40種) -------------------------------------------\n")
    out.append("  # レベルの刻印だけ行い、HP と与ダメージはバニラのまま。討伐で得られるのは\n")
    out.append("  # バニラどおりの経験値だけで、mob-level-table.yml のレベル帯 vanilla-exp は\n")
    out.append("  # 同ファイル側の mobs: フィルタで A/B 群に限定してある。\n")
    for n in PASSIVE:
        out.append(passive_entry(n))

    io.open(path, "w", encoding="utf-8", newline="\n").write("".join(out))
    print("A群(敵対)=%d B群(反撃中立)=%d C群(友好)=%d 合計=%d"
          % (len(HOSTILE), len(NEUTRAL), len(PASSIVE), len(HOSTILE) + len(NEUTRAL) + len(PASSIVE)))
    tiers, targets = write_level_table()
    print("mob-level-table: 帯=%d 対象EntityType=%d (C群は意図的に対象外)" % (tiers, targets))


if __name__ == "__main__":
    main()
