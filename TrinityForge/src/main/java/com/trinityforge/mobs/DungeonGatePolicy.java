package com.trinityforge.mobs;

/**
 * Pure dungeon entry gate (DUNGEON_SPEC, gap D2): may a player enter a dungeon given its gate
 * requirements? The gate is a <strong>combat-level requirement (primary) plus an optional key-item
 * consumption</strong> (decision Q4); both are per-dungeon config. Bukkit-free so the rule is fully
 * unit-testable — the listener supplies the player's combat level and whether the key item is held.
 *
 * <p>A non-positive {@code requiredLevel} disables the level gate; {@code keyRequired = false}
 * disables the key gate. With both disabled the dungeon is open (fail-open until an operator sets a
 * gate, matching the "add gates later" launch stance).
 */
public final class DungeonGatePolicy {

    /** Why entry was denied, or {@link #NONE} when allowed. */
    public enum Denial { NONE, UNDER_LEVEL, MISSING_KEY }

    private DungeonGatePolicy() {
    }

    public static Denial evaluate(int requiredLevel, int playerCombatLevel, boolean keyRequired, boolean hasKey) {
        if (requiredLevel > 0 && playerCombatLevel < requiredLevel) {
            return Denial.UNDER_LEVEL;
        }
        if (keyRequired && !hasKey) {
            return Denial.MISSING_KEY;
        }
        return Denial.NONE;
    }

    public static boolean allowed(int requiredLevel, int playerCombatLevel, boolean keyRequired, boolean hasKey) {
        return evaluate(requiredLevel, playerCombatLevel, keyRequired, hasKey) == Denial.NONE;
    }
}
