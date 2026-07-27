package com.trinityforge.progression.core;

import java.util.List;

/**
 * Stable uppercase skill ID constants for all 16 TrinityForge-owned skills.
 * These match the {@code skills/base/<id_lower>_progression.yml} filenames (uppercased)
 * and are used as map keys throughout the progression domain, SQLite rows, and ports.
 *
 * <p>Using string constants rather than an enum keeps the domain decoupled from Valhalla's
 * own skill type enum and lets external skill providers (e.g. ARS_MAGIC) reuse the same
 * identifier without an enum dependency.
 */
public final class SkillId {
    private SkillId() {}

    public static final String ALCHEMY       = "ALCHEMY";
    public static final String ARCHERY       = "ARCHERY";
    public static final String ARS_MAGIC     = "ARS_MAGIC";
    public static final String ARS_SMITHING  = "ARS_SMITHING";
    public static final String DIGGING       = "DIGGING";
    public static final String ENCHANTING    = "ENCHANTING";
    public static final String FARMING       = "FARMING";
    public static final String FISHING       = "FISHING";
    public static final String HEAVY_ARMOR   = "HEAVY_ARMOR";
    public static final String HEAVY_WEAPONS = "HEAVY_WEAPONS";
    public static final String LIGHT_ARMOR   = "LIGHT_ARMOR";
    public static final String LIGHT_WEAPONS = "LIGHT_WEAPONS";
    public static final String MINING        = "MINING";
    public static final String POWER         = "POWER";
    public static final String SMITHING      = "SMITHING";
    public static final String WOODCUTTING   = "WOODCUTTING";

    /** All 16 TrinityForge-owned skill IDs in a stable, alphabetical order. */
    public static final List<String> ALL = List.of(
            ALCHEMY, ARCHERY, ARS_MAGIC, ARS_SMITHING, DIGGING, ENCHANTING,
            FARMING, FISHING, HEAVY_ARMOR, HEAVY_WEAPONS, LIGHT_ARMOR, LIGHT_WEAPONS,
            MINING, POWER, SMITHING, WOODCUTTING
    );
}
