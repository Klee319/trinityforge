package com.trinityforge.combat;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Re-derives vanilla armor Protection-family enchantments into TF's own defense mitigation (2026-07-25
 * 被弾側バグ修正 課題1). Defensive counterpart of {@link EnchantmentStatBridge} — kept as its own class
 * (per the parallel-work split with the attack-side owner: this class only ever reads victim-side armor
 * enchants, never touches attacker-side stat bridging).
 *
 * <p><b>Why this exists:</b> {@code CombatListener} zeroes the vanilla {@code ARMOR} and {@code
 * RESISTANCE} {@code DamageModifier}s and re-derives them ({@link VanillaArmorMapping}, {@code
 * SymmetricCombatService#potionResistanceReduction}) so TF's own pipeline is the single source of
 * mitigation. Armor-enchant reduction (Protection/Fire Protection/Blast Protection/Projectile
 * Protection) lands on the vanilla {@code MAGIC} modifier, which was previously left un-zeroed AND
 * un-re-derived — worse, {@code CombatListener} calls the single-argument {@code
 * event.setDamage(DamageModifier.BASE, total)}, which (per the Paper/CraftBukkit implementation) does
 * NOT recompute the other modifiers from the new base the way {@code setDamage(double)} does. The net
 * effect: a small vanilla-computed absolute MAGIC reduction (from the tiny original vanilla damage) got
 * subtracted from TF's much larger final damage, shrinking a nominal Protection IV mitigation (~64% at
 * vanilla-exact scale — see below) down to ~1.8%. Zeroing MAGIC alongside ARMOR/RESISTANCE and
 * re-deriving it here (this class) fixes both the broken-scale bug and restores full mitigation
 * strength — the same "zero + re-derive together" pattern the {@code FOLDED_MODIFIERS} javadoc already
 * documents and warns must never be split.
 *
 * <p>Only {@link Enchantment#PROTECTION} (general — applies to every hit) and
 * {@link Enchantment#PROJECTILE_PROTECTION} (only on a projectile hit) are read here. {@code
 * Enchantment#BLAST_PROTECTION} and {@code Enchantment#FIRE_PROTECTION} are intentionally NOT bridged:
 * their damage causes ({@code ENTITY_EXPLOSION}/{@code FIRE}/{@code FIRE_TICK}/{@code LAVA}) never reach
 * {@code CombatListener#onEntityDamageByEntity}/{@code #handleMobToPlayerDamage} — those methods only run
 * when a {@link Player}/mob attacker is resolved (melee or a player-shot projectile), so an
 * explosion/fire event never gets its MAGIC modifier zeroed by TF in the first place. Bridging Blast/Fire
 * Protection here anyway would fabricate mitigation TF is never asked to apply, so they are left out.
 *
 * <p><b>Formula</b> (vanilla, per the Minecraft Wiki "Protection" page): each armor piece contributes an
 * Enchantment Protection Factor (EPF) of {@code level × 1} for Protection and {@code level × 2} for the
 * "twice as effective" specific protections (Blast/Fire/Projectile — only Projectile is read here). EPF
 * sums across all 4 armor pieces, is capped at {@code 20}, and each EPF point is worth 4% damage
 * reduction before scaling — so the vanilla-exact ceiling is {@code 20 × 4% = 80%}. No per-piece
 * randomisation is modelled (this mirrors the existing {@link VanillaArmorMapping}
 * pure/deterministic-formula convention — vanilla itself has no randomness in the EPF sum or the
 * reduction step, only in whether the {@code doPostHurtEffects} rewards happen to matter here, which
 * they don't for this deterministic mapping).
 *
 * <p><b>{@code defense.enchant-protection-scale}</b> (2026-07-25): the EPF-capped vanilla reduction
 * above is multiplied by this config-resolved scale (0.0 = a caller can zero out enchant mitigation
 * entirely; {@code 1.0} restores exact vanilla behaviour — Protection IV full set = 16 EPF = 64%
 * reduction — before this knob was introduced. The scale is applied AFTER the EPF cap, so the 20-EPF /
 * 80% ceiling is still the pre-scale maximum. This class stays a pure function of its arguments (no
 * config statics read here); the caller ({@code SymmetricCombatService}) resolves the scale from
 * {@code combat/damage.yml} and passes it in.
 */
public final class DefenseEnchantmentBridge {

    /** EPF granted per level of the general {@link Enchantment#PROTECTION}. */
    private static final double EPF_PER_LEVEL_GENERAL = 1.0;
    /** EPF granted per level of a "specific" protection (Projectile/Blast/Fire) — twice as effective. */
    private static final double EPF_PER_LEVEL_SPECIFIC = 2.0;
    /** Vanilla's combined-across-all-armor EPF ceiling. */
    private static final double EPF_CAP = 20.0;
    /** Damage reduction contributed per EPF point (so the 20-point cap == 80% reduction). */
    private static final double REDUCTION_PER_EPF = 0.04;

    private DefenseEnchantmentBridge() {
    }

    /**
     * Vanilla-exact overload (scale {@code 1.0}). Kept for existing callers/tests that want the raw
     * EPF-capped vanilla formula with no balance scaling applied.
     *
     * @param living        the victim whose worn armor enchants are read; {@code null} yields no bonus
     * @param projectileHit {@code true} when this hit's cause is {@code PROJECTILE} (adds Projectile
     *                      Protection on top of the always-active general Protection)
     * @return a {@link DefenseStats} carrying only {@code damageReduction} (被ダメージ軽減%), meant to
     *         be {@code combine}d into the rest of the defender profile before the pipeline's clamp.
     */
    public static DefenseStats toDefense(LivingEntity living, boolean projectileHit) {
        return toDefense(living, projectileHit, 1.0);
    }

    /**
     * @param living                 the victim whose worn armor enchants are read; {@code null} yields no bonus
     * @param projectileHit          {@code true} when this hit's cause is {@code PROJECTILE} (adds Projectile
     *                               Protection on top of the always-active general Protection)
     * @param enchantProtectionScale multiplier applied to the EPF-capped vanilla reduction
     *                               ({@code combat/damage.yml defense.enchant-protection-scale}); a
     *                               negative value is clamped to {@code 0} (never amplifies damage).
     *                               {@code 1.0} reproduces exact vanilla behaviour.
     * @return a {@link DefenseStats} carrying only {@code damageReduction} (被ダメージ軽減%), meant to
     *         be {@code combine}d into the rest of the defender profile before the pipeline's clamp.
     */
    public static DefenseStats toDefense(LivingEntity living, boolean projectileHit,
                                         double enchantProtectionScale) {
        if (living == null) {
            return DefenseStats.NONE;
        }
        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return DefenseStats.NONE;
        }
        int protectionLevels = 0;
        int specificLevels = 0;
        for (ItemStack piece : equipment.getArmorContents()) {
            if (piece == null || piece.getType().isAir() || !piece.hasItemMeta()) {
                continue;
            }
            ItemMeta meta = piece.getItemMeta();
            if (meta == null) {
                continue;
            }
            protectionLevels += Math.max(0, meta.getEnchantLevel(Enchantment.PROTECTION));
            if (projectileHit) {
                specificLevels += Math.max(0, meta.getEnchantLevel(Enchantment.PROJECTILE_PROTECTION));
            }
        }
        double epf = protectionLevels * EPF_PER_LEVEL_GENERAL + specificLevels * EPF_PER_LEVEL_SPECIFIC;
        double cappedEpf = Math.min(Math.max(epf, 0.0), EPF_CAP);
        double scale = Math.max(enchantProtectionScale, 0.0);
        double reduction = cappedEpf * REDUCTION_PER_EPF * scale;
        return new DefenseStats(0, 0, reduction, 0, 0);
    }
}
