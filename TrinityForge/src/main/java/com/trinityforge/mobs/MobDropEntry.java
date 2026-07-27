package com.trinityforge.mobs;

import org.bukkit.Material;

import java.util.Objects;

/**
 * One drop table entry for a {@link MobTypeDefinition}: a material, a per-death roll chance, an
 * inclusive stack-size range, and an optional fixed quality override.
 *
 * @param material the item to drop
 * @param chance   per-death roll chance [0,1]
 * @param min      minimum stack size (inclusive), &gt;= 0
 * @param max      maximum stack size (inclusive), &gt;= {@code min}
 * @param quality  fixed quality to stamp, or {@code null} to derive it from the dying mob's level
 *                 (see {@code MobTypeDropListener})
 */
public record MobDropEntry(Material material, double chance, int min, int max, Integer quality) {

    public MobDropEntry {
        Objects.requireNonNull(material, "material");
        if (!Double.isFinite(chance) || chance < 0.0 || chance > 1.0) {
            throw new IllegalArgumentException("chance must be in [0,1]: " + chance);
        }
        if (min < 0) {
            throw new IllegalArgumentException("min must be >= 0: " + min);
        }
        if (min > max) {
            throw new IllegalArgumentException("min must be <= max: min=" + min + " max=" + max);
        }
    }
}
