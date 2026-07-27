package com.trinityforge.mobs;

import org.bukkit.entity.EntityType;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A "which mobs does this apply to" narrowing filter, shared by {@code combat/mob-level-table.yml}'s
 * level bands ({@link LevelTierRule}) and their individual {@code add-drops} entries
 * ({@link LevelTierDropEntry}) — 2026-07-26 「レベルテーブルを付けるモブを指定できない」要望.
 *
 * <p>Two independent axes, because the two kinds of mob on this server are addressed differently:
 * <ul>
 *   <li>{@link #entityTypes()} ({@code mobs:} in YAML) — vanilla {@link EntityType}. The only way to
 *       address a field mob from {@code combat/mob-types.yml}.</li>
 *   <li>{@link #mobIds()} ({@code mob-ids:} in YAML) — EliteMobs カスタムボスのモブid, i.e. the same key
 *       {@code combat/mob-profiles.yml}/{@code combat/mob-overrides.yml} use and the fork stamps as
 *       {@code MOB_PROFILE_ID}. EntityType alone cannot distinguish dungeon mobs from one another (a
 *       whole dungeon's roster is typically re-skinned {@code ZOMBIE}s), so band-level targeting of a
 *       specific dungeon boss is only expressible here.</li>
 * </ul>
 *
 * <p><b>Semantics</b>: an axis that is empty imposes no constraint (the back-compat default — an
 * entirely empty filter applies to every mob, exactly as before this record existed). A non-empty axis
 * must match. When BOTH axes are set they are ANDed: each is an independent narrowing, so listing an
 * EntityType and a mob id means "this specific dungeon mob, and only when it is that EntityType". Use a
 * single axis for the common "either/or" intent.
 *
 * <p>Mob ids are normalized through {@link MobIdNormalizer} on the way in, so an operator who pastes the
 * EliteMobs file name ({@code boss.yml}) matches the same mob as one who types the bare id
 * ({@code boss}) — the trap documented in project memory as 「getFilename()は必ず.yml付き」.
 */
public record MobTargetFilter(Set<EntityType> entityTypes, Set<String> mobIds) {

    /** Applies to every mob (both axes unconstrained) — the config default when no keys are present. */
    public static final MobTargetFilter EMPTY = new MobTargetFilter(Set.of(), Set.of());

    public MobTargetFilter {
        entityTypes = entityTypes == null ? Set.of() : Set.copyOf(entityTypes);
        mobIds = mobIds == null ? Set.of() : Set.copyOf(mobIds);
    }

    /** Builds a filter, normalizing every mob id and dropping blank/null ids. */
    public static MobTargetFilter of(Collection<EntityType> entityTypes, Collection<String> mobIds) {
        Set<String> normalized = new LinkedHashSet<>();
        if (mobIds != null) {
            for (String raw : mobIds) {
                String id = MobIdNormalizer.normalize(raw);
                if (id != null && !id.isBlank()) {
                    normalized.add(id);
                }
            }
        }
        return new MobTargetFilter(entityTypes == null ? Set.of() : new LinkedHashSet<>(entityTypes),
                normalized);
    }

    /** {@code true} when neither axis constrains anything (= applies to every mob). */
    public boolean isEmpty() {
        return entityTypes.isEmpty() && mobIds.isEmpty();
    }

    /**
     * {@code true} when a kill of {@code type} carrying {@code profileId} passes every configured axis.
     *
     * @param type      the killed entity's {@link EntityType} (never {@code null} in practice)
     * @param profileId the mob's {@code MOB_PROFILE_ID} stamp, or {@code null} for a mob that carries
     *                  none (every field mob, and any EliteMobs mob spawned by a pre-stamp fork build).
     *                  A filter that names mob ids can never match such a mob — it has no id to match.
     */
    public boolean matches(EntityType type, String profileId) {
        if (!entityTypes.isEmpty() && !entityTypes.contains(type)) {
            return false;
        }
        if (mobIds.isEmpty()) {
            return true;
        }
        String id = MobIdNormalizer.normalize(profileId);
        return id != null && mobIds.contains(id);
    }
}
