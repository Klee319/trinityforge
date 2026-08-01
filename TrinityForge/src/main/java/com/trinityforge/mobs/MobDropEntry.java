package com.trinityforge.mobs;

import org.bukkit.Material;

import java.util.Objects;

/**
 * One drop table entry for a {@link MobTypeDefinition}: a vanilla {@link Material} OR a
 * {@code custom:<id>} reference (a TF {@code items/catalog.yml} id or an ArsPaper {@code materials.yml}
 * id, resolved through {@code com.trinityforge.stats.CrossPluginItemResolver} at drop-roll time), a
 * per-death roll chance, an inclusive stack-size range, and an optional fixed quality override.
 *
 * <p>The {@code material}/{@code catalogId} exclusive-or shape is deliberately identical to
 * {@link LevelTierDropEntry} and {@link MobOverrideDropEntry} (2026-08-01 U13). Until then this record
 * required a non-null {@link Material}, which made {@code combat/mob-types.yml} the ONLY one of the
 * three mob drop tables that could not name a custom item — and the config editor's mob 定義 screen
 * could not offer custom items there either. No new item-resolution mechanism is introduced: the
 * {@code custom:} prefix is stripped locally by the loader ({@code MobTypesConfig#parseDrops}), exactly
 * as the two sibling tables already do.
 *
 * @param material  the vanilla item to drop, or {@code null} when {@link #catalogId()} is set instead
 * @param catalogId the catalog / ArsPaper custom item id to drop (prefix already stripped), or
 *                  {@code null} when {@link #material()} is set instead
 * @param chance    per-death roll chance [0,1]
 * @param min       minimum stack size (inclusive), &gt;= 0
 * @param max       maximum stack size (inclusive), &gt;= {@code min}
 * @param quality   fixed quality to stamp, or {@code null} to derive it from the dying mob's level
 *                  (see {@code MobTypeDropListener}). Only meaningful for a {@link #material()} entry:
 *                  a catalog item is already built with its own roll seed/quality by the resolver, so
 *                  stamping it again would overwrite that.
 */
public record MobDropEntry(Material material, String catalogId, double chance, int min, int max,
                            Integer quality) {

    public MobDropEntry {
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

    /**
     * 旧5引数(バニラ {@link Material} 専用)の後方互換コンストラクタ。custom: 対応のために正準
     * コンストラクタへ {@code catalogId} を1つ挿入したので、既存の呼び出し(テスト多数)をそのまま
     * 通すために残す。
     */
    public MobDropEntry(Material material, double chance, int min, int max, Integer quality) {
        this(Objects.requireNonNull(material, "material"), null, chance, min, max, quality);
    }

    public static MobDropEntry ofMaterial(Material material, double chance, int min, int max,
                                           Integer quality) {
        return new MobDropEntry(Objects.requireNonNull(material, "material"), null, chance, min, max, quality);
    }

    public static MobDropEntry ofCatalog(String catalogId, double chance, int min, int max,
                                          Integer quality) {
        return new MobDropEntry(null, Objects.requireNonNull(catalogId, "catalogId"), chance, min, max, quality);
    }

    /** {@code true} when this entry names a custom item ({@link #catalogId()}) instead of a Material. */
    public boolean isCustom() {
        return catalogId != null;
    }
}
