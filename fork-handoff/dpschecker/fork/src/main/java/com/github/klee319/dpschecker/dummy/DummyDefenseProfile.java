package com.github.klee319.dpschecker.dummy;

import com.github.klee319.dpschecker.integration.TrinityForgeBridge;
import com.trinityforge.combat.DefenseStats;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * TrinityForge mob defender profile for a DPS dummy.
 *
 * <p>Vocabulary follows COMBAT_SYSTEM_SPEC LD-13: only 耐性% is typed (physical / magical),
 * everything else (防御率%・被ダメ軽減%・守備力・防具強度・回避) is type-independent. Internally the
 * common fields are stored once and expanded into the physical/magical {@link DefenseStats} pair
 * that {@code MobData.stamp} expects (the two only differ in the resistance field).
 */
public final class DummyDefenseProfile {

    private final double defenseRate;
    private final double physResistance;
    private final double magicResistance;
    private final double damageReduction;
    private final double flatDefense;
    private final double armorStrength;
    private final double dodgeChance;

    public DummyDefenseProfile(double defenseRate, double physResistance, double magicResistance,
                               double damageReduction, double flatDefense, double armorStrength,
                               double dodgeChance) {
        this.defenseRate = defenseRate;
        this.physResistance = physResistance;
        this.magicResistance = magicResistance;
        this.damageReduction = damageReduction;
        this.flatDefense = flatDefense;
        this.armorStrength = armorStrength;
        this.dodgeChance = clamp01(dodgeChance);
    }

    public static DummyDefenseProfile empty() {
        return new DummyDefenseProfile(0, 0, 0, 0, 0, 0, 0);
    }

    public static DummyDefenseProfile fromVanillaArmor(JavaPlugin plugin, double armorPoints, double toughnessPoints) {
        // Vanilla armor -> 防御率%; vanilla toughness -> 防具強度 (flat, step-6 subtraction).
        // LD-13: both mitigate physical AND magical, so the mapped values are simply common fields.
        DefenseStats mapped = TrinityForgeBridge.mapVanillaArmor(armorPoints, toughnessPoints);
        return new DummyDefenseProfile(
                mapped.defenseRate(), 0.0, 0.0, 0.0, 0.0, mapped.armorStrength(), 0.0);
    }

    public static DummyDefenseProfile fromConfig(JavaPlugin plugin) {
        double armor = plugin.getConfig().getDouble("dummy.default-defense", 0.0);
        double toughness = plugin.getConfig().getDouble("dummy.default-armor-toughness", 0.0);
        return fromVanillaArmor(plugin, armor, toughness);
    }

    public static DummyDefenseProfile fromPdc(JavaPlugin plugin, PersistentDataContainer pdc,
                                              double legacyArmor, double legacyToughness) {
        NamespacedKey marker = key(plugin, "tf_profile");
        if (!pdc.has(marker, PersistentDataType.BOOLEAN)) {
            return fromVanillaArmor(plugin, legacyArmor, legacyToughness);
        }

        // Common fields read the physical-side key first (profiles written before the LD-13
        // unification stored per-type values; the GUI treated the physical value as primary).
        double defenseRate = pdc.getOrDefault(key(plugin, "tf_phys_def_rate"), PersistentDataType.DOUBLE, 0.0);
        double damageReduction = pdc.getOrDefault(key(plugin, "tf_phys_dmg_red"), PersistentDataType.DOUBLE, 0.0);
        double flatDefense = pdc.getOrDefault(key(plugin, "tf_phys_flat_def"), PersistentDataType.DOUBLE, 0.0);
        double physResistance = pdc.getOrDefault(key(plugin, "tf_phys_resistance"), PersistentDataType.DOUBLE, 0.0);
        double magicResistance = pdc.getOrDefault(key(plugin, "tf_magic_resistance"), PersistentDataType.DOUBLE, 0.0);
        // armorStrength(防具強度) is a CRIT-REDUCTION RATE [0,1] applied at step 2 (会心の増加分を
        // (1-r)倍に軽減), NOT a step-6 flat. A stamped 0.8 means 80% crit-bonus reduction on this target.
        double armorStrength = pdc.getOrDefault(key(plugin, "tf_armor_strength"), PersistentDataType.DOUBLE, 0.0);
        double dodge = pdc.getOrDefault(key(plugin, "tf_dodge_chance"), PersistentDataType.DOUBLE, 0.0);

        return new DummyDefenseProfile(defenseRate, physResistance, magicResistance,
                damageReduction, flatDefense, armorStrength, dodge);
    }

    public void writePdc(JavaPlugin plugin, PersistentDataContainer pdc) {
        pdc.set(key(plugin, "tf_profile"), PersistentDataType.BOOLEAN, true);
        // Common fields are duplicated into both typed key sets so older readers keep working.
        pdc.set(key(plugin, "tf_phys_def_rate"), PersistentDataType.DOUBLE, defenseRate);
        pdc.set(key(plugin, "tf_phys_resistance"), PersistentDataType.DOUBLE, physResistance);
        pdc.set(key(plugin, "tf_phys_dmg_red"), PersistentDataType.DOUBLE, damageReduction);
        pdc.set(key(plugin, "tf_phys_flat_def"), PersistentDataType.DOUBLE, flatDefense);
        pdc.set(key(plugin, "tf_magic_def_rate"), PersistentDataType.DOUBLE, defenseRate);
        pdc.set(key(plugin, "tf_magic_resistance"), PersistentDataType.DOUBLE, magicResistance);
        pdc.set(key(plugin, "tf_magic_dmg_red"), PersistentDataType.DOUBLE, damageReduction);
        pdc.set(key(plugin, "tf_magic_flat_def"), PersistentDataType.DOUBLE, flatDefense);
        pdc.set(key(plugin, "tf_armor_strength"), PersistentDataType.DOUBLE, armorStrength);
        pdc.set(key(plugin, "tf_dodge_chance"), PersistentDataType.DOUBLE, dodgeChance);
    }

    public DummyDefenseProfile adjust(TfDefenseStat stat, double delta) {
        double next = clamp(valueOf(stat) + delta, stat.min(), stat.max());
        return with(stat, next);
    }

    private DummyDefenseProfile with(TfDefenseStat stat, double value) {
        return switch (stat) {
            case DEFENSE_RATE -> new DummyDefenseProfile(value, physResistance, magicResistance,
                    damageReduction, flatDefense, armorStrength, dodgeChance);
            case PHYS_RESISTANCE -> new DummyDefenseProfile(defenseRate, value, magicResistance,
                    damageReduction, flatDefense, armorStrength, dodgeChance);
            case MAGIC_RESISTANCE -> new DummyDefenseProfile(defenseRate, physResistance, value,
                    damageReduction, flatDefense, armorStrength, dodgeChance);
            case DAMAGE_REDUCTION -> new DummyDefenseProfile(defenseRate, physResistance, magicResistance,
                    value, flatDefense, armorStrength, dodgeChance);
            case FLAT_DEFENSE -> new DummyDefenseProfile(defenseRate, physResistance, magicResistance,
                    damageReduction, value, armorStrength, dodgeChance);
            case ARMOR_STRENGTH -> new DummyDefenseProfile(defenseRate, physResistance, magicResistance,
                    damageReduction, flatDefense, value, dodgeChance);
            case DODGE_CHANCE -> new DummyDefenseProfile(defenseRate, physResistance, magicResistance,
                    damageReduction, flatDefense, armorStrength, value);
        };
    }

    public double valueOf(TfDefenseStat stat) {
        return switch (stat) {
            case DEFENSE_RATE -> defenseRate;
            case PHYS_RESISTANCE -> physResistance;
            case MAGIC_RESISTANCE -> magicResistance;
            case DAMAGE_REDUCTION -> damageReduction;
            case FLAT_DEFENSE -> flatDefense;
            case ARMOR_STRENGTH -> armorStrength;
            case DODGE_CHANCE -> dodgeChance;
        };
    }

    /** Physical component for {@code MobData.stamp}: common fields + physical resistance. */
    public DefenseStats physical() {
        return new DefenseStats(defenseRate, physResistance, damageReduction, flatDefense, armorStrength);
    }

    /** Magical component for {@code MobData.stamp}: common fields + magical resistance. */
    public DefenseStats magical() {
        return new DefenseStats(defenseRate, magicResistance, damageReduction, flatDefense, armorStrength);
    }

    /** @deprecated identical to {@link #magical()} since the LD-13 common-field unification. */
    @Deprecated
    public DefenseStats magicalForStamp() {
        return magical();
    }

    public double dodgeChance() {
        return dodgeChance;
    }

    /**
     * Domain guard before stamping: percent fields to [0,1], flat fields to >= 0. TF applies its
     * own configurable clamp downstream ({@code damage.yml defense.*}); this only keeps the GUI
     * values inside the editable domain.
     */
    public DummyDefenseProfile normalizedForPipeline() {
        return new DummyDefenseProfile(
                clamp01(defenseRate),
                clamp01(physResistance),
                clamp01(magicResistance),
                clamp01(damageReduction),
                Math.max(0.0, flatDefense),
                Math.max(0.0, armorStrength),
                dodgeChance);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    private static NamespacedKey key(JavaPlugin plugin, String name) {
        return new NamespacedKey(plugin, name);
    }
}
