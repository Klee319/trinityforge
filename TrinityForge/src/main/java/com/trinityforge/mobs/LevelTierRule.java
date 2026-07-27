package com.trinityforge.mobs;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * One level-band row of {@code combat/mob-level-table.yml} (レベルテーブル要望): the rules that apply
 * to a kill whose resolved combat level falls in this band (see {@link MobLevelBandTable}).
 *
 * @param removeDrops materials stripped from the death drop list (regardless of source — vanilla
 *                     default loot, or a TF {@code mob-types.yml} additive drop; see
 *                     {@code MobLevelTableListener} for exactly where this runs in the drop pipeline)
 * @param addDrops     extra drops for this band only ({@link LevelTierDropEntry}: material-or-custom/
 *                      chance/min/max/mobs 2026-07-25 レベルテーブルのモブ別ドロップ指定拡張), rolled
 *                      independently of {@code removeDrops}
 * @param vanillaExp   fixed vanilla EXP orb amount for a kill in this band ({@code
 *                     EntityDeathEvent#setDroppedExp}), or {@code null} to leave the vanilla/default
 *                     amount untouched
 * @param targets      band-wide "which mobs does this row apply to" filter (2026-07-26 「レベルテーブルを
 *                      付けるモブを指定できない」要望). {@link MobTargetFilter#EMPTY} — the default when
 *                      neither {@code mobs:} nor {@code mob-ids:} is written at band level — keeps the
 *                      original behavior of applying to every mob whose level falls in the band. When
 *                      set, the WHOLE row is skipped for a non-matching kill ({@code removeDrops},
 *                      {@code addDrops} and {@code vanillaExp} alike). That is what distinguishes it
 *                      from {@link LevelTierDropEntry}'s own filter, which only narrows its one drop.
 */
public record LevelTierRule(List<Material> removeDrops, List<LevelTierDropEntry> addDrops,
                             Integer vanillaExp, MobTargetFilter targets) {

    public LevelTierRule {
        removeDrops = removeDrops == null ? List.of() : List.copyOf(removeDrops);
        addDrops = addDrops == null ? List.of() : List.copyOf(addDrops);
        targets = targets == null ? MobTargetFilter.EMPTY : targets;
        if (vanillaExp != null && vanillaExp < 0) {
            throw new IllegalArgumentException("vanillaExp must be >= 0 when set: " + vanillaExp);
        }
    }

    /** Back-compat: a band with no target filter (applies to every mob whose level falls in it). */
    public LevelTierRule(List<Material> removeDrops, List<LevelTierDropEntry> addDrops, Integer vanillaExp) {
        this(removeDrops, addDrops, vanillaExp, MobTargetFilter.EMPTY);
    }

    /** {@code true} when this whole band applies to a kill of {@code type} carrying {@code profileId}. */
    public boolean appliesTo(EntityType type, String profileId) {
        return targets.matches(type, profileId);
    }
}
