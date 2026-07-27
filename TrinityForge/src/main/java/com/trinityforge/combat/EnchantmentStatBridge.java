package com.trinityforge.combat;

import com.trinityforge.stats.StatKeys;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Maps vanilla enchantment levels on an item to TF stat bonuses so enchant books apply through the
 * TF pipeline (attack-power %, durability %) instead of Bukkit's discarded vanilla base.
 */
public final class EnchantmentStatBridge {

    /** Default: each general damage-enchant level adds 5% to attack-power. */
    private static final double ATTACK_PERCENT_PER_LEVEL = 0.05;
    /** 特攻系 (SMITE/BANE_OF_ARTHROPODS/IMPALING): +7.5%/lv, stronger than general enchants. */
    private static final double CONDITIONAL_ATTACK_PERCENT_PER_LEVEL = 0.075;
    /** Breach (mace): each level adds +10% penetration (folded into the TF penetration stat). */
    private static final double BREACH_PENETRATION_PER_LEVEL = 0.10;
    /**
     * Luck of the Sea: each level adds +20% {@code fishing_luck} (stat-gate-overhaul §2.3). Folded into
     * {@link Bonuses#fishingLuckBonus()} and consumed by {@code FishingGimmickListener.luckTotalOf}
     * (2026-07-25: confirmed wired in, updated from an earlier "not yet consumed" note).
     */
    private static final double FISHING_LUCK_PER_LEVEL = 0.20;

    private EnchantmentStatBridge() {
    }

    /**
     * @param durabilityMultiplier 2026-07-25 ユーザー決定「バニラ優先」により常時 {@code 0.0}
     *                             ({@link #bonuses} はもう Unbreaking からこれを populate しない —
     *                             バニラの「耐久消費をスキップする確率」だけに一本化)。フィールド/
     *                             {@link #adjustedDurability} 自体は他エンチャント由来の将来拡張に
     *                             備えて残す(現状は常に恒等関数として振る舞う)。
     * @param densityLevel Density(重撃)エンチャントの生レベル(0=無し)。落下距離依存のため乗率化できず、
     *                     {@link MaceSmashDamage#densityBonus} へレベルのまま渡す(CombatListener 側で
     *                     MACE のスマッシュ攻撃判定と組み合わせて加算)。
     */
    public record Bonuses(double attackPowerMultiplier, double durabilityMultiplier,
                          double penetrationBonus, double fishingLuckBonus, int densityLevel) {
        public static final Bonuses NONE = new Bonuses(0.0, 0.0, 0.0, 0.0, 0);

        /** Back-compat convenience for callers that predate the Breach penetration bonus. */
        public Bonuses(double attackPowerMultiplier, double durabilityMultiplier) {
            this(attackPowerMultiplier, durabilityMultiplier, 0.0, 0.0, 0);
        }

        /** Back-compat convenience for callers that predate the fishing-luck bonus. */
        public Bonuses(double attackPowerMultiplier, double durabilityMultiplier, double penetrationBonus) {
            this(attackPowerMultiplier, durabilityMultiplier, penetrationBonus, 0.0, 0);
        }

        /** Back-compat convenience for callers that predate the Density level. */
        public Bonuses(double attackPowerMultiplier, double durabilityMultiplier, double penetrationBonus,
                        double fishingLuckBonus) {
            this(attackPowerMultiplier, durabilityMultiplier, penetrationBonus, fishingLuckBonus, 0);
        }
    }

    /**
     * @param weapon held/firing weapon with enchants
     * @param victim   target entity for conditional enchants; null skips conditional checks
     */
    public static Bonuses bonuses(ItemStack weapon, LivingEntity victim) {
        if (weapon == null || weapon.getType().isAir() || !weapon.hasItemMeta()) {
            return Bonuses.NONE;
        }
        ItemMeta meta = weapon.getItemMeta();
        if (meta == null || meta.getEnchants().isEmpty()) {
            return Bonuses.NONE;
        }
        double attackBonus = 0.0;
        double penetrationBonus = 0.0;
        double fishingLuckBonus = 0.0;
        int densityLevel = 0;
        for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
            Enchantment enchant = entry.getKey();
            int level = entry.getValue();
            if (level <= 0) {
                continue;
            }
            if (isGeneralDamageEnchant(enchant)) {
                attackBonus += level * ATTACK_PERCENT_PER_LEVEL;
            } else if (isConditionalDamageEnchant(enchant) && victim != null
                    && matchesConditional(enchant, victim)) {
                attackBonus += level * CONDITIONAL_ATTACK_PERCENT_PER_LEVEL;
            } else if (enchant.equals(Enchantment.BREACH)) {
                penetrationBonus += level * BREACH_PENETRATION_PER_LEVEL;
            } else if (enchant.equals(Enchantment.LUCK_OF_THE_SEA)) {
                fishingLuckBonus += level * FISHING_LUCK_PER_LEVEL;
            } else if (enchant.equals(Enchantment.DENSITY)) {
                // #1 重撃(Density): 落下距離依存のため率(%)化できない — 生レベルをそのまま持ち回り、
                // CombatListener が MaceSmashDamage.densityBonus(fallDistance, level) で計算する
                // (2026-07-25バグ修正: 従来は分岐自体が無く完全に無視されていた)。
                densityLevel = level;
            }
        }
        return new Bonuses(attackBonus, 0.0, penetrationBonus, fishingLuckBonus, densityLevel);
    }

    /** Applies attack-power multiplier to a base attack-power value. */
    public static double adjustedAttackPower(double baseAttackPower, Bonuses bonuses) {
        if (bonuses.attackPowerMultiplier() <= 0.0) {
            return baseAttackPower;
        }
        return baseAttackPower * (1.0 + bonuses.attackPowerMultiplier());
    }

    /** Applies durability multiplier; returns floor int, min 1. */
    public static int adjustedDurability(int baseDurability, Bonuses bonuses) {
        if (baseDurability <= 0 || bonuses.durabilityMultiplier() <= 0.0) {
            return baseDurability;
        }
        return Math.max(1, (int) Math.floor(baseDurability * (1.0 + bonuses.durabilityMultiplier())));
    }

    /** Vanilla {@code Sweeping Edge} level on {@code weapon}, or {@code 0} if absent/no meta. */
    public static int sweepingEdgeLevel(ItemStack weapon) {
        if (weapon == null || weapon.getType().isAir() || !weapon.hasItemMeta()) {
            return 0;
        }
        ItemMeta meta = weapon.getItemMeta();
        return meta == null ? 0 : Math.max(0, meta.getEnchantLevel(Enchantment.SWEEPING_EDGE));
    }

    /**
     * Vanilla sweep-attack damage formula: {@code round(1 + attackDamage * level / (level + 1))}
     * ({@code attackDamage} = the weapon's damage after Sharpness/Smite/Bane-of-Arthropods, i.e. what
     * TF's own {@code baseDamage} already represents via {@link #adjustedAttackPower} — BEFORE armor/
     * potions/crit, which are applied afterwards per-victim by the rest of TF's pipeline, matching
     * vanilla where each sweep sub-hit target defends independently). With no Sweeping Edge (level 0)
     * this always reduces to a flat {@code 1}, matching vanilla's well-known "bare sword sweep = 1
     * damage" baseline — so this formula is applied unconditionally to sweep hits, not gated on the
     * enchant being present.
     */
    public static double sweepDamage(double attackDamage, int sweepingLevel) {
        int level = Math.max(0, sweepingLevel);
        double safeAttackDamage = Double.isFinite(attackDamage) ? attackDamage : 0.0;
        return Math.round(1.0 + safeAttackDamage * level / (double) (level + 1));
    }

    public static String attackPowerKey() {
        return StatKeys.canonical("attack-power");
    }

    public static String fishingLuckKey() {
        return StatKeys.canonical("fishing-luck");
    }

    private static boolean isGeneralDamageEnchant(Enchantment enchant) {
        return enchant.equals(Enchantment.SHARPNESS)
                || enchant.equals(Enchantment.POWER);
    }

    private static boolean isConditionalDamageEnchant(Enchantment enchant) {
        return enchant.equals(Enchantment.SMITE)
                || enchant.equals(Enchantment.BANE_OF_ARTHROPODS)
                || enchant.equals(Enchantment.IMPALING);
    }

    private static boolean matchesConditional(Enchantment enchant, LivingEntity victim) {
        if (enchant.equals(Enchantment.SMITE)) {
            return isUndead(victim.getType());
        }
        if (enchant.equals(Enchantment.BANE_OF_ARTHROPODS)) {
            EntityType type = victim.getType();
            return type == EntityType.SPIDER || type == EntityType.CAVE_SPIDER
                    || type == EntityType.SILVERFISH || type == EntityType.ENDERMITE
                    || type.name().contains("BEE");
        }
        if (enchant.equals(Enchantment.IMPALING)) {
            // #5 2026-07-25バグ修正: バニラ1.21のImpalingは「対象が水中または雨に濡れているか」
            // (LivingEntity#isInWaterOrRain 相当)で判定する — EntityTypeの固定列挙(旧isAquatic)では
            // 水生モブ以外(陸上モブが雨天/水中にいる場合)を取りこぼし、逆に列挙漏れの新モブ種も落とす。
            return victim.isInWaterOrRain();
        }
        return false;
    }

    /**
     * 2026-07-25バグ修正: {@code BOGGED}(1.21追加のアンデッド・スケルトン亜種)が列挙にも
     * フォールバック名前一致("ZOMBIE"/"SKELETON"を含まない)にも該当せず、Smiteが効かなかった。
     * 同じ理由で {@code CAMEL_HUSK} と {@code PARCHED}(いずれも同時期に確認したアンデッド系統だが
     * 名前に "ZOMBIE"/"SKELETON" を含まない)も取りこぼしていたため、あわせて明示追加する。
     */
    private static boolean isUndead(EntityType type) {
        return switch (type) {
            case ZOMBIE, HUSK, DROWNED, ZOMBIE_VILLAGER, SKELETON, STRAY, WITHER_SKELETON,
                 WITHER, PHANTOM, ZOGLIN, ZOMBIFIED_PIGLIN, BOGGED, CAMEL_HUSK, PARCHED -> true;
            default -> type.name().contains("ZOMBIE") || type.name().contains("SKELETON");
        };
    }
}
