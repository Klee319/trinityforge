package com.trinityforge.pdc;

import com.trinityforge.combat.DamageType;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.combat.AttackStats;
import com.trinityforge.pdc.PdcKeys;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Type-safe view over a mob's PersistentDataContainer (ADDON_INTEGRATION_SPEC 6).
 * Holds the defender stat profile (per-component 守備力/耐性/防御率), level, and dungeon theme.
 *
 * <p>Absent values fall back to the vanilla baseline ({@link DefenseStats#NONE}, level 0) so a
 * plain mob with no addon data still flows through the symmetric pipeline unmodified
 * (COMBAT_SYSTEM_SPEC 6). Stats are read live each call; nothing is cached here.
 */
public final class MobData {

    /** Default mob level when the PDC carries no level (COMBAT_SYSTEM_SPEC 6 baseline). */
    public static final int DEFAULT_LEVEL = 0;

    private static final Logger LOG = Logger.getLogger(MobData.class.getName());

    /**
     * Keys already warned about for a DOUBLE type mismatch. Static (not per-instance) and
     * unbounded-but-small (one entry per PDC key that is ever misconfigured, not per mob), so a
     * misconfigured mob only logs once per server run instead of once per damage tick.
     */
    private static final Set<NamespacedKey> TYPE_MISMATCH_WARNED = ConcurrentHashMap.newKeySet();

    private final org.bukkit.persistence.PersistentDataContainer container;

    private MobData(org.bukkit.persistence.PersistentDataContainer container) {
        this.container = Objects.requireNonNull(container, "container");
    }

    /** Wraps the holder's container. */
    public static MobData of(PersistentDataHolder holder) {
        return new MobData(Objects.requireNonNull(holder, "holder").getPersistentDataContainer());
    }

    /**
     * True when this mob carries an addon stat profile. The level key is the profile marker: an
     * EliteMobs/dungeon mob has it set, a plain vanilla mob does not (COMBAT_SYSTEM_SPEC 6, which
     * then applies the config default profile instead).
     */
    public boolean hasProfile() {
        return container.has(PdcKeys.MOB_LEVEL, PersistentDataType.INTEGER);
    }

    /**
     * True only when this mob was stamped by {@code MobTypeSpawnListener} from {@code
     * combat/mob-types.yml}. Unlike {@link #hasProfile()} (keyed on {@link PdcKeys#MOB_LEVEL},
     * which EliteMobs/dungeon mobs also set), this disambiguates a vanilla mob-types mob from any
     * other profile source so drop/quality logic never misapplies to Elite/dungeon mobs.
     */
    public boolean isMobTypeStamped() {
        return container.has(PdcKeys.MOB_TYPE_STAMPED, PersistentDataType.BYTE);
    }

    public int level() {
        return container.getOrDefault(PdcKeys.MOB_LEVEL, PersistentDataType.INTEGER, DEFAULT_LEVEL);
    }

    /** The dungeon attribute theme, if one was assigned (DUNGEON spec). */
    public Optional<String> dungeonTheme() {
        return Optional.ofNullable(
                container.get(PdcKeys.MOB_DUNGEON_THEME, PersistentDataType.STRING));
    }

    /**
     * The EliteMobsカスタムボス設定ファイル名({@link PdcKeys#MOB_PROFILE_ID}), if the spawning fork
     * stamped one. Empty for field mobs (mob-types.yml) and for EliteMobs mobs spawned by a fork build
     * that predates this stamp (2026-07-26 mob-overrides新設).
     */
    public Optional<String> profileId() {
        return Optional.ofNullable(
                container.get(PdcKeys.MOB_PROFILE_ID, PersistentDataType.STRING));
    }

    /** The per-individual roll seed (個体ばらつき用シード, MOB_ROLL_SEED), if one was stamped. */
    public OptionalLong rollSeed() {
        return container.has(PdcKeys.MOB_ROLL_SEED, PersistentDataType.LONG)
                ? OptionalLong.of(container.get(PdcKeys.MOB_ROLL_SEED, PersistentDataType.LONG))
                : OptionalLong.empty();
    }

    /**
     * Builds the defender inputs for one damage component. 防具強度 (armorStrength) is shared
     * across components; the other four fields are read from the type-specific keys.
     */
    public DefenseStats defenseFor(DamageType type) {
        Objects.requireNonNull(type, "type");
        double armorStrength = readDouble(PdcKeys.MOB_ARMOR_STRENGTH);
        return switch (type) {
            case PHYSICAL -> new DefenseStats(
                    readDouble(PdcKeys.MOB_PHYS_DEFENSE_RATE),
                    readDouble(PdcKeys.MOB_PHYS_RESISTANCE),
                    readDouble(PdcKeys.MOB_PHYS_DAMAGE_REDUCTION),
                    readDouble(PdcKeys.MOB_PHYS_FLAT_DEFENSE),
                    armorStrength);
            case MAGICAL -> new DefenseStats(
                    readDouble(PdcKeys.MOB_MAGIC_DEFENSE_RATE),
                    readDouble(PdcKeys.MOB_MAGIC_RESISTANCE),
                    readDouble(PdcKeys.MOB_MAGIC_DAMAGE_REDUCTION),
                    readDouble(PdcKeys.MOB_MAGIC_FLAT_DEFENSE),
                    armorStrength);
            case TYPELESS -> DefenseStats.NONE;
        };
    }

    /**
     * Type-independent defender dodge chance [0,1] (回避, SKILL_TREE_SPEC 6.2 Q3). Rolled once per
     * attack, so it is read here (not in {@link #defenseFor}, which is per-component). Absent -> 0;
     * positive overflow is capped at 1; negative authored values are retained and naturally never proc.
     */
    public double dodgeChance() {
        return Math.min(1.0, readDouble(PdcKeys.MOB_DODGE_CHANCE));
    }

    /** True when this mob carries stamped attack stats from mob-types.yml. */
    public boolean hasAttackProfile() {
        return container.has(PdcKeys.MOB_ATTACK_POWER, PersistentDataType.DOUBLE);
    }

    /** Reads attacker-side stats stamped at spawn; empty when {@link #hasAttackProfile()} is false. */
    public AttackStats attackStats() {
        if (!hasAttackProfile()) {
            return AttackStats.plain(0);
        }
        return new AttackStats(
                readDouble(PdcKeys.MOB_ATTACK_POWER),
                readDouble(PdcKeys.MOB_ATTACK_FLAT_BONUS),
                readDouble(PdcKeys.MOB_ATTACK_PERCENT_BONUS),
                readDouble(PdcKeys.MOB_ATTACK_CRIT_CHANCE),
                readDouble(PdcKeys.MOB_ATTACK_CRIT_DAMAGE),
                readDouble(PdcKeys.MOB_ATTACK_PENETRATION),
                readDouble(PdcKeys.MOB_ATTACK_DAMAGE_MODIFIER, 1.0),
                readDouble(PdcKeys.MOB_ATTACK_FIXED_DAMAGE));
    }

    /**
     * Stamps a full defender profile (level + per-component defense) onto {@code holder}'s PDC,
     * using the exact same 9 keys {@link #hasProfile()}/{@link #level()}/{@link #defenseFor}
     * read (COMBAT_SYSTEM_SPEC 6): once written, the mob flows through the existing symmetric
     * combat pipeline exactly like an EliteMobs/dungeon profile, with no pipeline changes needed.
     * {@code physical} and {@code magical} are expected to share one 防具強度 value (the
     * {@link #defenseFor} armorStrength key is not per-component); {@code physical}'s value is
     * used for that shared key.
     */
    public static void stamp(PersistentDataHolder holder, int level, DefenseStats physical, DefenseStats magical) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(physical, "physical");
        Objects.requireNonNull(magical, "magical");
        org.bukkit.persistence.PersistentDataContainer container = holder.getPersistentDataContainer();
        container.set(PdcKeys.MOB_LEVEL, PersistentDataType.INTEGER, level);
        container.set(PdcKeys.MOB_ARMOR_STRENGTH, PersistentDataType.DOUBLE, physical.armorStrength());
        container.set(PdcKeys.MOB_PHYS_DEFENSE_RATE, PersistentDataType.DOUBLE, physical.defenseRate());
        container.set(PdcKeys.MOB_PHYS_RESISTANCE, PersistentDataType.DOUBLE, physical.resistance());
        container.set(PdcKeys.MOB_PHYS_DAMAGE_REDUCTION, PersistentDataType.DOUBLE, physical.damageReduction());
        container.set(PdcKeys.MOB_PHYS_FLAT_DEFENSE, PersistentDataType.DOUBLE, physical.flatDefense());
        container.set(PdcKeys.MOB_MAGIC_DEFENSE_RATE, PersistentDataType.DOUBLE, magical.defenseRate());
        container.set(PdcKeys.MOB_MAGIC_RESISTANCE, PersistentDataType.DOUBLE, magical.resistance());
        container.set(PdcKeys.MOB_MAGIC_DAMAGE_REDUCTION, PersistentDataType.DOUBLE, magical.damageReduction());
        container.set(PdcKeys.MOB_MAGIC_FLAT_DEFENSE, PersistentDataType.DOUBLE, magical.flatDefense());
    }

    /**
     * Same as {@link #stamp} but also writes {@link PdcKeys#MOB_TYPE_STAMPED}. Used SOLELY by
     * {@code MobTypeSpawnListener} for {@code combat/mob-types.yml} vanilla-mob profiles — never
     * by the generic EliteMobs/dungeon profile path — so {@code MobTypeDropListener} can tell the
     * two apart even though both also share {@link PdcKeys#MOB_LEVEL}.
     */
    public static void stampMobType(PersistentDataHolder holder, int level, DefenseStats physical,
                                     DefenseStats magical) {
        stamp(holder, level, physical, magical);
        holder.getPersistentDataContainer().set(PdcKeys.MOB_TYPE_STAMPED, PersistentDataType.BYTE, (byte) 1);
    }

    /** Stamps scaled attack stats for mob→player symmetric pipeline routing. */
    public static void stampAttack(PersistentDataHolder holder, AttackStats attack) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(attack, "attack");
        org.bukkit.persistence.PersistentDataContainer container = holder.getPersistentDataContainer();
        container.set(PdcKeys.MOB_ATTACK_POWER, PersistentDataType.DOUBLE, attack.defaultDamage());
        container.set(PdcKeys.MOB_ATTACK_FLAT_BONUS, PersistentDataType.DOUBLE, attack.flatBonusDamage());
        container.set(PdcKeys.MOB_ATTACK_PERCENT_BONUS, PersistentDataType.DOUBLE, attack.percentBonusDamage());
        container.set(PdcKeys.MOB_ATTACK_CRIT_CHANCE, PersistentDataType.DOUBLE, attack.critChance());
        container.set(PdcKeys.MOB_ATTACK_CRIT_DAMAGE, PersistentDataType.DOUBLE, attack.critDamage());
        container.set(PdcKeys.MOB_ATTACK_PENETRATION, PersistentDataType.DOUBLE, attack.penetration());
        container.set(PdcKeys.MOB_ATTACK_DAMAGE_MODIFIER, PersistentDataType.DOUBLE, attack.damageModifier());
        container.set(PdcKeys.MOB_ATTACK_FIXED_DAMAGE, PersistentDataType.DOUBLE, attack.fixedDamage());
    }

    /**
     * Reads a DOUBLE-typed value, defaulting to 0.0 when absent. A present key with a mismatched
     * type (e.g. stored as INTEGER) also silently reads back as 0.0 via {@code getOrDefault}; that
     * is detected here and logged once per key so a misconfigured profile is not silently ignored.
     */
    private double readDouble(NamespacedKey key) {
        return readDouble(key, 0.0);
    }

    private double readDouble(NamespacedKey key, double fallback) {
        if (container.has(key) && !container.has(key, PersistentDataType.DOUBLE)
                && TYPE_MISMATCH_WARNED.add(key)) {
            LOG.warning("[MobData] PDC key '" + key + "' is present but not a DOUBLE; reading fallback "
                    + fallback + ". "
                    + "Check the profile/importer output that stamped this mob for a type mismatch.");
        }
        return container.getOrDefault(key, PersistentDataType.DOUBLE, fallback);
    }
}
