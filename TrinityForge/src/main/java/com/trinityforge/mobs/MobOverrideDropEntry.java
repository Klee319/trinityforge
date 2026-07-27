package com.trinityforge.mobs;

import org.bukkit.Material;

import java.util.Objects;

/**
 * One {@code drops:} entry of a {@code combat/mob-overrides.yml} mob entry (2026-07-26 ダンジョン×モブ
 * 単位オーバーライド新設): a vanilla {@link Material} OR a {@code custom:<id>} reference resolved via
 * {@code com.trinityforge.stats.CrossPluginItemResolver} at drop-roll time (same {@code custom:} prefix
 * convention as {@link LevelTierDropEntry}/{@code RecipeIngredient}/{@code ItemCatalogConfig} — no new
 * item-resolution mechanism invented), plus a per-death roll chance and an inclusive stack-size range.
 *
 * <p>Deliberately NOT {@link LevelTierDropEntry}: that record also carries a per-entry {@code mobs}
 * EntityType filter that has no meaning here (this table is already keyed on a specific mob id one level
 * up, in {@link MobOverrideEntry}), and reusing it would let a caller accidentally construct a
 * mob-filtered entry this table has no code path to honour.
 *
 * @param material  the vanilla item to drop, or {@code null} when {@link #catalogId()} is set instead
 * @param catalogId the {@code custom:<id>} catalog/ArsPaper id to drop, or {@code null} when
 *                  {@link #material()} is set instead
 * @param chance    per-death roll chance [0,1]
 * @param min       minimum stack size (inclusive), &gt;= 0
 * @param max       maximum stack size (inclusive), &gt;= {@code min}
 */
public record MobOverrideDropEntry(Material material, String catalogId, double chance, int min, int max) {

    public MobOverrideDropEntry {
        int itemSet = (material != null ? 1 : 0) + (catalogId != null ? 1 : 0);
        if (itemSet != 1) {
            throw new IllegalArgumentException("exactly one of material/catalogId must be set");
        }
        if (catalogId != null && catalogId.isBlank()) {
            throw new IllegalArgumentException("custom catalog id must not be blank");
        }
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

    public static MobOverrideDropEntry ofMaterial(Material material, double chance, int min, int max) {
        return new MobOverrideDropEntry(Objects.requireNonNull(material, "material"), null, chance, min, max);
    }

    public static MobOverrideDropEntry ofCatalog(String catalogId, double chance, int min, int max) {
        return new MobOverrideDropEntry(null, Objects.requireNonNull(catalogId, "catalogId"), chance, min, max);
    }

    public boolean isCustom() {
        return catalogId != null;
    }
}
