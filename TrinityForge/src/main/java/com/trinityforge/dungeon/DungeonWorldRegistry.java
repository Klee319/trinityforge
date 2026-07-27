package com.trinityforge.dungeon;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe set of world UUIDs currently known to be instanced EliteMobs dungeon worlds.
 *
 * <p>The EliteMobs fork depends on TrinityForge via {@code softdepend} (load order: TrinityForge
 * loads first, then the fork), so the fork can safely call into TrinityForge at its own enable
 * time. The fork registers an instanced dungeon world's UUID here when it creates the world, and
 * unregisters it when the instance is destroyed. A future TF-side listener (e.g. the
 * {@code dungeon-only-exp} EXP gate) can then do an O(1) {@link #isDungeonWorld(UUID)} check to
 * decide whether a player is currently inside a dungeon instance.
 *
 * <p><b>Out of scope:</b> this class only stores and exposes the registry. Wiring any actual
 * listener/gate logic that consumes {@link #isDungeonWorld(UUID)} (e.g. gating weapon-skill EXP)
 * is a separate, future task and is intentionally not done here.
 */
public final class DungeonWorldRegistry {

    private final Set<UUID> dungeonWorlds = ConcurrentHashMap.newKeySet();

    /** Registers {@code worldId} as a dungeon world. A {@code null} id is a no-op. */
    public void register(UUID worldId) {
        if (worldId == null) {
            return;
        }
        dungeonWorlds.add(worldId);
    }

    /** Unregisters {@code worldId}. A {@code null} id is a no-op. */
    public void unregister(UUID worldId) {
        if (worldId == null) {
            return;
        }
        dungeonWorlds.remove(worldId);
    }

    /** True when {@code worldId} is a currently-registered dungeon world; {@code null} -> false. */
    public boolean isDungeonWorld(UUID worldId) {
        if (worldId == null) {
            return false;
        }
        return dungeonWorlds.contains(worldId);
    }

    /** Clears every registered dungeon world. */
    public void clear() {
        dungeonWorlds.clear();
    }
}
