package com.trinityforge.combat;

import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.CombatLevelConfig;
import com.trinityforge.config.domains.MobTypesConfig;
import com.trinityforge.pdc.MobData;
import com.trinityforge.progression.SkillLevelSource;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared entry point that runs one attack component through the symmetric pipeline using live config
 * (COMBAT_SYSTEM_SPEC 2.1/2.2). Both physical (melee, via {@code CombatListener}) and magical (Ars
 * spell, M2) route through here so the two components are guaranteed to use the identical 8-step math,
 * level curve, and defender resolution.
 *
 * <p>{@link #magicalFinalDamage} is the M2 reception port: the Ars integration computes a spell's base
 * damage and catalyst attack stats, then calls this to fold them through TrinityForge's pipeline and
 * gets back the final damage to apply. Until that integration lands the port simply has no caller.
 *
 * <p>Reads config live each call, so {@code /trinityforge reload} re-balances with no code change.
 * Must be called on the server main thread (combat events and Bukkit reads are synchronous).
 */
public final class SymmetricCombatService {

    private final CombatDamageConfig damageConfig;
    private final CombatLevelConfig combatLevelConfig;
    private final MobTypesConfig mobTypes;
    private final SkillLevelSource skillLevelSource;
    private final PlayerDefenseResolver playerDefenseResolver;

    public SymmetricCombatService(CombatDamageConfig damageConfig,
                                  CombatLevelConfig combatLevelConfig,
                                  MobTypesConfig mobTypes,
                                  SkillLevelSource skillLevelSource,
                                  PlayerDefenseResolver playerDefenseResolver) {
        this.damageConfig = Objects.requireNonNull(damageConfig, "damageConfig");
        this.combatLevelConfig = Objects.requireNonNull(combatLevelConfig, "combatLevelConfig");
        this.mobTypes = Objects.requireNonNull(mobTypes, "mobTypes");
        this.skillLevelSource = Objects.requireNonNull(skillLevelSource, "skillLevelSource");
        this.playerDefenseResolver = Objects.requireNonNull(playerDefenseResolver, "playerDefenseResolver");
    }

    /**
     * 序盤(低レベル帯)モブの火力緩和倍率。式と無効化条件は
     * {@link EarlyLevelAttackSoftening#multiplier(boolean, int, double, int)} を参照。
     */
    double earlyLevelAttackMultiplier(int mobLevel) {
        return EarlyLevelAttackSoftening.multiplier(
                damageConfig.earlyLevelAttackEnabled(),
                damageConfig.earlyLevelAttackUntilLevel(),
                damageConfig.earlyLevelAttackLevel0Multiplier(),
                mobLevel);
    }

    /** The attacker's gear-independent combat level (ADDON_INTEGRATION_SPEC 1.5). */
    public int combatLevelOf(UUID attackerId) {
        return combatLevelConfig.model().compute(skillLevelSource.levelsOf(attackerId));
    }

    /**
     * Physical component: the vanilla base damage is level-scaled and folded through the pipeline.
     *
     * @param attack the attacker's stats template (its {@code defaultDamage} is replaced by the scaled value)
     */
    public double physicalFinalDamage(UUID attackerId, PersistentDataHolder victim,
                                      double vanillaBaseDamage, AttackStats attack) {
        return physicalFinalDamageResult(attackerId, victim, vanillaBaseDamage, attack).damage();
    }

    /**
     * Same as {@link #physicalFinalDamage} with crit flag for VFX ({@link CritFlash}).
     */
    public CombatHitResult physicalFinalDamageResult(UUID attackerId, PersistentDataHolder victim,
                                                     double vanillaBaseDamage, AttackStats attack) {
        return physicalFinalDamageResult(attackerId, victim, vanillaBaseDamage, attack, false);
    }

    /**
     * Same as {@link #physicalFinalDamageResult(UUID, PersistentDataHolder, double, AttackStats)} but
     * lets the caller flag a projectile hit ({@code projectileHit}) so {@link DefenseEnchantmentBridge}
     * also folds in the victim's Projectile Protection (2026-07-25 課題1: vanilla armor-enchant
     * mitigation re-derivation, alongside {@code MAGIC} modifier zeroing in {@code CombatListener}).
     */
    public CombatHitResult physicalFinalDamageResult(UUID attackerId, PersistentDataHolder victim,
                                                     double vanillaBaseDamage, AttackStats attack,
                                                     boolean projectileHit) {
        double base = resolver().physicalDefaultDamage(vanillaBaseDamage, combatLevelOf(attackerId));
        return componentResult(DamageType.PHYSICAL, victim, attack.withDefaultDamage(base),
                damageConfig.minComponentDamage(), vanillaProtectionDefense(victim, projectileHit));
    }

    /**
     * Physical component for a mob attacker: scales vanilla base damage by the mob's stamped level
     * (not the player's combat level) and routes through the symmetric pipeline against a player
     * defender.
     */
    public double physicalFinalDamageFromMob(LivingEntity mobAttacker, Player victim,
                                             double vanillaBaseDamage, AttackStats attack) {
        return physicalFinalDamageFromMobResult(mobAttacker, victim, vanillaBaseDamage, attack).damage();
    }

    /**
     * Same as {@link #physicalFinalDamageFromMob} with crit flag for VFX ({@link CritFlash}).
     */
    public CombatHitResult physicalFinalDamageFromMobResult(LivingEntity mobAttacker, Player victim,
                                                            double vanillaBaseDamage, AttackStats attack) {
        return physicalFinalDamageFromMobResult(mobAttacker, victim, vanillaBaseDamage, attack, false);
    }

    /**
     * Same as {@link #physicalFinalDamageFromMobResult(LivingEntity, Player, double, AttackStats)} but
     * lets the caller flag a projectile hit.
     *
     * <p><b>2026-07-30</b>: {@code CombatListener#resolveMobAttacker} now also owns a mob's
     * <em>projectile</em> damage (skeleton arrows etc. previously bypassed the TF pipeline entirely),
     * so the victim's Projectile Protection must be re-derived for those hits — TF zeroes the vanilla
     * {@code MAGIC} modifier unconditionally, and a re-derivation that is not asked for silently
     * deletes the enchantment's mitigation.
     */
    public CombatHitResult physicalFinalDamageFromMobResult(LivingEntity mobAttacker, Player victim,
                                                            double vanillaBaseDamage, AttackStats attack,
                                                            boolean projectileHit) {
        Objects.requireNonNull(mobAttacker, "mobAttacker");
        Objects.requireNonNull(victim, "victim");
        int mobLevel = MobData.of(mobAttacker).level();
        double base = resolver().physicalDefaultDamage(vanillaBaseDamage, mobLevel);
        double itemAttackPower = attack.defaultDamage();
        double baseDamage = (itemAttackPower != 0 ? itemAttackPower : base) * earlyLevelAttackMultiplier(mobLevel);
        return componentResult(DamageType.PHYSICAL, victim, attack.withDefaultDamage(baseDamage),
                damageConfig.minComponentDamage(), vanillaProtectionDefense(victim, projectileHit));
    }

    /**
     * Magical component for a mob attacker (B1: EliteMobs ability visuals + damage routed through
     * TF): the ability's base damage is scaled by the mob's stamped level — the mob level is the
     * difficulty knob for BOTH components, unlike the player-side C2 combat-level bypass which only
     * governs gear/skill-independent player magic — and folded through the magical pipeline against
     * the player defender (magical defense stats, magical min-component-damage).
     *
     * <p>Same attack-power replacement rule as {@link #physicalFinalDamageFromMob}: a stamped
     * {@code attack-power} replaces the scaled base outright; otherwise the scaled ability base is
     * used. Like {@link #magicalFinalDamage}, a negative result (possible when
     * {@code magical.min-component-damage} is configured negative) means the consumer MUST heal the
     * victim by its magnitude instead of damaging.
     */
    public double magicalFinalDamageFromMob(LivingEntity mobAttacker, Player victim,
                                            double abilityBaseDamage, AttackStats attack) {
        return magicalFinalDamageFromMobResult(mobAttacker, victim, abilityBaseDamage, attack).damage();
    }

    /**
     * Same as {@link #magicalFinalDamageFromMob} with crit flag for VFX ({@link CritFlash}).
     */
    public CombatHitResult magicalFinalDamageFromMobResult(LivingEntity mobAttacker, Player victim,
                                                           double abilityBaseDamage, AttackStats attack) {
        Objects.requireNonNull(mobAttacker, "mobAttacker");
        Objects.requireNonNull(victim, "victim");
        int mobLevel = MobData.of(mobAttacker).level();
        double base = resolver().magicalDefaultDamage(abilityBaseDamage, mobLevel);
        double itemAttackPower = attack.defaultDamage();
        double baseDamage = (itemAttackPower != 0 ? itemAttackPower : base) * earlyLevelAttackMultiplier(mobLevel);
        return componentResult(DamageType.MAGICAL, victim, attack.withDefaultDamage(baseDamage),
                damageConfig.magicalMinComponentDamage());
    }

    /**
     * Physical component for an <em>already-finalized</em> base damage (#5 bleed DoT / #6 addon-delegated
     * damage): {@code flatBase} is an authored per-tick / post-formula number, so — unlike
     * {@link #physicalFinalDamage} — it is NOT re-scaled by the attacker's combat level nor by the global
     * {@code physical.base} coefficient (both of those govern raw vanilla-weapon growth and would
     * double-scale an already-final number). Only the victim's defense and the single dodge roll of the
     * pipeline apply. At the default coefficient {@code 1.0} + a neutral (level-0) attacker this returns
     * the same value as {@link #physicalFinalDamage}, so wiring an existing caller over is behaviour-safe.
     *
     * @param victim the defender whose defense/dodge is applied
     * @param flatBase the finalized base damage; passed straight through as the component's default damage
     * @param attack the attacker's stats template (its {@code defaultDamage} is replaced by {@code flatBase})
     */
    public double physicalFinalDamageFlat(PersistentDataHolder victim, double flatBase, AttackStats attack) {
        return componentResult(DamageType.PHYSICAL, victim, attack.withDefaultDamage(flatBase),
                damageConfig.minComponentDamage()).damage();
    }

    /**
     * Magical twin of {@link #physicalFinalDamageFlat}: an <em>already-finalized</em> magical base
     * (e.g. an EliteMobs ability whose damage formula already folded the mob level in) is NOT
     * re-scaled by any level curve or the {@code magical.base} coefficient — only the victim's
     * MAGICAL defense (and the single dodge roll) applies. The EliteMobs fork routes elite ability
     * damage with a magic-typed cause here (B1) so 魔法耐性/魔法防御 finally matter against mobs.
     * Like {@link #magicalFinalDamage}, a negative result (negative configured
     * {@code magical.min-component-damage}) means the consumer MUST heal the victim by its
     * magnitude instead of damaging.
     */
    public double magicalFinalDamageFlat(PersistentDataHolder victim, double flatBase, AttackStats attack) {
        return componentResult(DamageType.MAGICAL, victim, attack.withDefaultDamage(flatBase),
                damageConfig.magicalMinComponentDamage()).damage();
    }

    /**
     * DoT (出血・毒・ウィザー共通): “被ダメージ軽減以外を無効化”.
     *
     * <p>回避/防御率/耐性/守備力/防具強度/固定ダメージ/ダメージ補正などを一切無視し、
     * {@link DefenseStats#damageReduction()} だけで {@code final = base * (1 - reduction)} を計算する。
     * ポーションRESISTANCEは耐性%への加算に変更されたため、このパスには乗らない（仕様）。
     *
     * <p>負に落ちないように clamp と min-component-damage（物理の床）は適用する。
     */
    public double bleedFinalDamageFlat(PersistentDataHolder victim, double flatBase) {
        if (victim == null || !Double.isFinite(flatBase) || flatBase <= 0.0) {
            return 0.0;
        }
        // damageReductionは type-independent（耐性・守備力等は無視）なので、physical側で解決してよい。
        DefenderProfile defender = resolveDefender(DamageType.PHYSICAL, victim);
        DefenseStats stats = defender.stats()
                .clampedTo(damageConfig.defenseClamp())
                .cappedMitigation(damageConfig.maxMitigationRate());
        double reduction = stats.damageReduction();
        double finalDamage = flatBase * (1.0 - reduction);
        // component() と同じ「床」を適用する（Bleedが完全に無視されることを防ぐため）。
        return Math.max(finalDamage, damageConfig.minComponentDamage());
    }

    /**
     * Magical component (M2 port): the spell/catalyst base damage is folded through the pipeline
     * (COMBAT_SYSTEM_SPEC 2.2). By default the magical base damage BYPASSES the combat-level curve
     * (C2: 魔法はcombatレベルbypass): a level of 0 is passed so the {@code (1 + perLevel * level)}
     * multiplier collapses to 1. When {@code combat/damage.yml magical.scale-with-combat-level} is
     * {@code true} the legacy behaviour is restored and the magical base scales with the attacker's
     * combat level exactly like physical.
     *
     * <p>#6 Part B: {@code magical.min-component-damage} may now be configured negative, so this method can
     * return a NEGATIVE final damage. The Ars fork consumer MUST treat a negative result as HEALING the
     * victim by its magnitude (mirroring {@code CombatListener}'s physical-side handling), not as damage.
     *
     * @param attack the attacker's stats template (its {@code defaultDamage} is replaced by the scaled value)
     */
    public double magicalFinalDamage(UUID attackerId, PersistentDataHolder victim,
                                     double spellBaseDamage, AttackStats attack) {
        return magicalFinalDamageResult(attackerId, victim, spellBaseDamage, attack).damage();
    }

    /**
     * Same as {@link #magicalFinalDamage} with crit flag for VFX ({@link CritFlash}).
     */
    public CombatHitResult magicalFinalDamageResult(UUID attackerId, PersistentDataHolder victim,
                                                    double spellBaseDamage, AttackStats attack) {
        int level = damageConfig.magicalScaleWithCombatLevel() ? combatLevelOf(attackerId) : 0;
        double base = resolver().magicalDefaultDamage(spellBaseDamage, level);
        return componentResult(DamageType.MAGICAL, victim, attack.withDefaultDamage(base),
                damageConfig.magicalMinComponentDamage());
    }

    /**
     * Runs one attack component through the pipeline. Dodge is rolled once here, per component
     * call. Physical (melee) and magical (Ars) arrive as separate calls, so today — where every
     * attack is single-type — one roll == one whole attack, matching 回避=攻撃全体を無効化 (Q3).
     * A genuine hybrid swing (§8, deferred) would call both entry points and roll dodge twice; when
     * hybrid lands, unify the two components into one {@code pipeline.compute} so dodge stays a
     * single whole-attack roll.
     */
    private CombatHitResult componentResult(DamageType type, PersistentDataHolder victim, AttackStats attack,
                                            double minComponentDamage) {
        return componentResult(type, victim, attack, minComponentDamage, DefenseStats.NONE);
    }

    /**
     * @param extraDefense an additional defender addend combined in before the clamp/cap choke — used by
     *                     the physical entry points to fold in {@link #vanillaProtectionDefense}
     *                     (課題1) without polluting {@link #resolveDefender}, which is also shared by the
     *                     magical/DoT/hybrid paths where TF never zeroes the vanilla {@code MAGIC}
     *                     modifier and so must NOT double-apply this re-derivation.
     */
    private CombatHitResult componentResult(DamageType type, PersistentDataHolder victim, AttackStats attack,
                                            double minComponentDamage, DefenseStats extraDefense) {
        Objects.requireNonNull(victim, "victim");
        Objects.requireNonNull(attack, "attack");
        DefenderProfile defender = resolveDefender(type, victim);
        // Single clamp choke: every defender source (item, perk, addon, vanilla mirror, potion, mob PDC,
        // extraDefense) has already been combine()d, so the config-driven domain clamp (負クランプ対応, #6)
        // is applied here exactly once, then the B3 balance cap bounds 耐性%/被ダメージ軽減% below full immunity.
        DefenseStats stats = clampedDefense(defender.stats().combine(extraDefense));
        // B3の回避版: 回避率も defense.max-dodge-chance でcapする(無上限だと加算スタッキングで
        // 回避率1.0=永久無敵が構成可能になるため)。
        DodgeResolver cappedDodge = DodgeResolver.capped(DodgeResolver.RANDOM, damageConfig.maxDodgeChance());
        SymmetricDamagePipeline pipeline =
                new SymmetricDamagePipeline(CritResolver.RANDOM, cappedDodge, minComponentDamage);
        return pipeline.computeResult(
                List.of(new ComponentInput(type, attack, stats)), defender.dodgeChance());
    }

    private DefenseStats clampedDefense(DefenseStats stats) {
        return stats.clampedTo(damageConfig.defenseClamp())
                .cappedMitigation(damageConfig.maxMitigationRate())
                .cappedCritReduction(damageConfig.maxCritReduction());
    }

    /**
     * Resolves the defender's stats + dodge for one component (COMBAT_SYSTEM_SPEC 6, LD-8/LD-13).
     * Priority:
     * <ol>
     *   <li>An addon PDC profile ({@link MobData#hasProfile()}) always wins: typed defender keys +
     *       its own 回避率 (dungeon/EliteMobs mob).</li>
     *   <li>A {@link Player} combines the vanilla armor/toughness mirror (防御率% + 防具強度, common
     *       across types per LD-13) with the item-side TF-only defense (typed 耐性% + common
     *       守備力/被ダメ軽減) and 回避 from {@link PlayerDefenseResolver}. The armor-skill baseline
     *       (LD-8 γ) is a future addend through the same {@link DefenseStats#combine}.</li>
     *   <li>Any other {@link LivingEntity} (plain vanilla mob) uses the vanilla armor/toughness
     *       mirror alone (now both physical and magical per LD-13) plus its PDC 回避率 (0 unless
     *       stamped).</li>
     *   <li>A non-living victim keeps the flat config default.</li>
     * </ol>
     */
    private DefenderProfile resolveDefender(DamageType type, PersistentDataHolder victim) {
        DefenderProfile base = resolveBaseDefender(type, victim);
        if (type == DamageType.TYPELESS) {
            return new DefenderProfile(DefenseStats.NONE, base.dodgeChance());
        }
        if (!(victim instanceof LivingEntity living)) {
            return base;
        }
        // Re-derive the vanilla RESISTANCE potion inside the pipeline (B1). CombatListener folds the
        // engine's RESISTANCE modifier to 0 to avoid double mitigation, exactly like vanilla ARMOR — but
        // unlike armor (re-derived via vanillaArmorDefense) potion resistance had no re-injection, so the
        // effect silently vanished. Re-apply it as 耐性% (Lv×10%, added onto TF's phys/magic resistance —
        // resolveDefender runs once per component type, so the same addend lands on both types). Note this
        // means bleed/poison/wither (damage-reduction-only paths) deliberately ignore the potion.
        double potionResistance = potionResistanceReduction(living);
        if (potionResistance <= 0.0) {
            return base;
        }
        DefenseStats withPotion = base.stats().combine(new DefenseStats(0, potionResistance, 0, 0, 0));
        return new DefenderProfile(withPotion, base.dodgeChance());
    }

    private DefenderProfile resolveBaseDefender(DamageType type, PersistentDataHolder victim) {
        MobData mob = MobData.of(victim);
        if (mob.hasProfile()) {
            return new DefenderProfile(mob.defenseFor(type), mob.dodgeChance());
        }
        if (victim instanceof Player player) {
            DefenderProfile itemSide = playerDefenseResolver.resolve(player, type);
            // PlayerArmorChangeEvent cannot be cancelled and the gate removes rejected armor one
            // tick later. During that window, suppress the vanilla armor/toughness mirror as well as
            // the already-filtered TF item stats. Suppressing the whole vanilla armor addend is
            // intentionally conservative for this transient invalid loadout: it cannot grant a
            // partial benefit from the denied piece.
            DefenseStats vanilla = playerDefenseResolver.hasDeniedArmor(player)
                    ? DefenseStats.NONE : vanillaArmorDefense(player);
            DefenseStats combined = vanilla.combine(itemSide.stats());
            return new DefenderProfile(combined, itemSide.dodgeChance());
        }
        if (victim instanceof LivingEntity living) {
            // Untagged living mobs: vanilla armor + combat/mob-types.yml defaults (COMBAT_SYSTEM_SPEC §6).
            DefenseStats combined = vanillaArmorDefense(living).combine(mobTypes.defaultDefense(type));
            return new DefenderProfile(combined, mob.dodgeChance());
        }
        return new DefenderProfile(mobTypes.defaultDefense(type), 0.0);
    }

    /**
     * The resistance% the victim's vanilla RESISTANCE potion contributes to TF's typed resistance:
     * {@code 0.1 * (amplifier + 1)} (Resistance I = 10%, weaker than vanilla's 20%/lv by design —
     * it is added onto the player's TF phys/magic resistance instead of replacing them).
     * 0 when the effect is absent. The [0,1] clamp and the B3 balance cap are applied downstream.
     */
    private static double potionResistanceReduction(LivingEntity living) {
        PotionEffect effect = living.getPotionEffect(PotionEffectType.RESISTANCE);
        return effect == null ? 0.0 : 0.1 * (effect.getAmplifier() + 1);
    }

    private DefenseStats vanillaArmorDefense(LivingEntity living) {
        return VanillaArmorMapping.toDefense(
                attributeValue(living, Attribute.ARMOR),
                attributeValue(living, Attribute.ARMOR_TOUGHNESS),
                damageConfig.vanillaArmorDefenseRatePerPoint(),
                damageConfig.vanillaArmorDefenseRateMax(),
                damageConfig.vanillaArmorStrengthPerPoint());
    }

    /**
     * 課題1 (2026-07-25): re-derives vanilla armor Protection/Projectile Protection into TF's
     * damage-reduction so zeroing the vanilla {@code MAGIC} modifier in {@code CombatListener} does not
     * silently delete armor-enchant mitigation. {@code NONE} for a non-{@link LivingEntity} victim (a
     * non-living/PDC-only target has no worn armor to read). See {@link DefenseEnchantmentBridge} for
     * the full rationale and formula.
     */
    private DefenseStats vanillaProtectionDefense(PersistentDataHolder victim, boolean projectileHit) {
        if (!(victim instanceof LivingEntity living)) {
            return DefenseStats.NONE;
        }
        if (living instanceof Player player && playerDefenseResolver.hasDeniedArmor(player)) {
            // Same deferred-removal window as resolveBaseDefender: a rejected enchanted piece must
            // not contribute Protection/Projectile Protection for one hit.
            return DefenseStats.NONE;
        }
        return DefenseEnchantmentBridge.toDefense(living, projectileHit, damageConfig.enchantProtectionScale());
    }

    private static double attributeValue(LivingEntity living, Attribute attribute) {
        AttributeInstance instance = living.getAttribute(attribute);
        return instance != null ? instance.getValue() : 0.0;
    }

    private DefaultDamageResolver resolver() {
        return new DefaultDamageResolver(damageConfig.physicalBaseCoefficient(),
                damageConfig.magicalBaseCoefficient(), damageConfig.levelScalingPerLevel());
    }
}
