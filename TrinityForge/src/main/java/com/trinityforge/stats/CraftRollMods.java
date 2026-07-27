package com.trinityforge.stats;

/** Per-crafter stage-2 (stat-roll) modifiers baked into an item at craft time (鍛冶ロールパーク). All
 *  reach-fraction units matching {@link QualityRollModel}. Defaults (NONE) leave the roll unchanged. */
public record CraftRollMods(double rollUpBonus, double rollDownReduction, double rollInsetDelta) {
    public static final CraftRollMods NONE = new CraftRollMods(0.0, 0.0, 0.0);

    public boolean isZero() {
        return rollUpBonus == 0.0 && rollDownReduction == 0.0 && rollInsetDelta == 0.0;
    }
}
