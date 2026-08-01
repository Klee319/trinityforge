package com.trinityforge.mobs;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.Objects;
import java.util.Set;

/**
 * One {@code add-drops} entry of a {@code combat/mob-level-table.yml} level band (2026-07-25 レベル
 * テーブルのモブ別ドロップ指定): a vanilla {@link Material} OR a {@code custom:<catalogId>} reference
 * (mirrors {@code com.trinityforge.stats.RecipeIngredient}'s "exactly one of material/catalogId" shape),
 * a per-death roll chance, an inclusive stack-size range, and an optional {@code mobs} filter.
 *
 * <p>{@link #targets()} empty (the default — neither {@code mobs:} nor {@code mob-ids:} in the config)
 * means "applies to every mob", preserving the pre-existing "レベル値だけを見て横断的に適用する" behavior
 * exactly ({@code combat/mob-level-table.yml} 後方互換). {@code mobs:} narrows by vanilla
 * {@link EntityType}; {@code mob-ids:} narrows by EliteMobs モブid (2026-07-26 — EntityType alone cannot
 * tell one dungeon mob from another, since a whole roster is typically re-skinned {@code ZOMBIE}s, so
 * the earlier "EntityType covers dungeon mobs too" reasoning held only for whole-species rules). See
 * {@link MobTargetFilter} for the AND semantics when both axes are set.
 *
 * <p>Deliberately NOT {@link MobDropEntry} (used by {@code combat/mob-types.yml}'s {@code drops:}):
 * that record requires a non-null {@link Material} and has no {@code mobs}/{@code custom:} concept,
 * and widening it would let an unrelated caller accidentally construct a custom/mob-filtered entry it
 * has no code path to honour. Chance/min/max validation is intentionally identical to {@link MobDropEntry}.
 *
 * @param material  the vanilla item to drop, or {@code null} when {@link #catalogId()} is set instead
 * @param catalogId the {@code items/catalog.yml} id (or ArsPaper custom item id — see
 *                  {@code com.trinityforge.stats.CrossPluginItemResolver}, which resolves this at
 *                  drop-roll time) to drop, or {@code null} when {@link #material()} is set instead
 * @param chance    per-death roll chance [0,1]
 * @param min       minimum stack size (inclusive), &gt;= 0
 * @param max       maximum stack size (inclusive), &gt;= {@code min}
 * @param targets   which mobs this entry applies to ({@code mobs:} EntityType axis + {@code mob-ids:}
 *                  EliteMobsモブid axis); {@link MobTargetFilter#EMPTY} = every mob (back-compat default)
 * @param roles     {@code roles:} — キルしたプレイヤーの職業がこの集合に含まれるときだけロールする
 *                  (2026-08-02 柱7)。空 = 職業を問わない(後方互換の既定)。IDは
 *                  {@code progression/role-buffs.yml} のキーと同じ正規化(小文字)で保持する
 */
public record LevelTierDropEntry(Material material, String catalogId, double chance, int min, int max,
                                  MobTargetFilter targets, Set<String> roles) {

    public LevelTierDropEntry {
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
        targets = targets == null ? MobTargetFilter.EMPTY : targets;
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public static LevelTierDropEntry ofMaterial(Material material, double chance, int min, int max,
                                                 MobTargetFilter targets) {
        return ofMaterial(material, chance, min, max, targets, Set.of());
    }

    public static LevelTierDropEntry ofCatalog(String catalogId, double chance, int min, int max,
                                                MobTargetFilter targets) {
        return ofCatalog(catalogId, chance, min, max, targets, Set.of());
    }

    public static LevelTierDropEntry ofMaterial(Material material, double chance, int min, int max,
                                                 MobTargetFilter targets, Set<String> roles) {
        return new LevelTierDropEntry(Objects.requireNonNull(material, "material"), null, chance, min, max,
                targets, roles);
    }

    public static LevelTierDropEntry ofCatalog(String catalogId, double chance, int min, int max,
                                                MobTargetFilter targets, Set<String> roles) {
        return new LevelTierDropEntry(null, Objects.requireNonNull(catalogId, "catalogId"), chance, min, max,
                targets, roles);
    }

    /** Back-compat overload for callers/tests that only narrow by {@link EntityType}. */
    public static LevelTierDropEntry ofMaterial(Material material, double chance, int min, int max,
                                                 Set<EntityType> mobs) {
        return ofMaterial(material, chance, min, max, MobTargetFilter.of(mobs, null));
    }

    /** Back-compat overload for callers/tests that only narrow by {@link EntityType}. */
    public static LevelTierDropEntry ofCatalog(String catalogId, double chance, int min, int max,
                                                Set<EntityType> mobs) {
        return ofCatalog(catalogId, chance, min, max, MobTargetFilter.of(mobs, null));
    }

    public boolean isCustom() {
        return catalogId != null;
    }

    /** The {@link EntityType} axis of {@link #targets()} — kept as a named accessor for readability. */
    public Set<EntityType> mobs() {
        return targets.entityTypes();
    }

    /**
     * {@code true} when this entry applies to a kill of {@code type} carrying {@code profileId} — an
     * empty {@link #targets()} always matches.
     */
    public boolean appliesTo(EntityType type, String profileId) {
        return targets.matches(type, profileId);
    }

    /** Back-compat: matches on the {@link EntityType} axis only (no mob id available at the call site). */
    public boolean appliesTo(EntityType type) {
        return appliesTo(type, null);
    }

    /**
     * {@code true} when the killer's roles satisfy {@code roles:} — 空の {@code roles:} は常に一致する
     * (職業を問わない従来どおりの挙動)。{@code killerRoles} は戦闘職・補助職の両方を渡してよい。
     */
    public boolean allowsRoles(Set<String> killerRoles) {
        if (roles.isEmpty()) {
            return true;
        }
        if (killerRoles == null || killerRoles.isEmpty()) {
            return false;
        }
        for (String role : killerRoles) {
            if (role != null && roles.contains(role.trim().toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
