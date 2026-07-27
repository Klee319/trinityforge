package com.trinityforge.mobs;

import org.bukkit.Material;

import java.util.List;
import java.util.Objects;

/**
 * One dungeon's entry gate (DUNGEON_SPEC D2, decision Q4): a combat-level requirement (primary) plus
 * an optional consumed key item. The primary lookup key is the destination world name; optional
 * {@code aliases} map EliteMobs content-package filenames (stable blueprint names) to the same gate
 * so TF and EliteMobs share one config ({@code dungeon/gates.yml}).
 *
 * <p>D3 topology: an optional {@code region} makes this a 区画ダンジョン gate — entry is enforced when
 * the player crosses INTO the region (move or teleport), instead of / in addition to a cross-world
 * teleport into {@code world}. Region gates use the gate name only as an identifier (it need not be a
 * real world name).
 *
 * <p>A {@code requiredCombatLevel} of 0 disables the level gate; a null {@code keyMaterial} disables
 * the key gate.
 *
 * @param world               primary lookup key (destination world name, or a label for region gates)
 * @param aliases             optional EliteMobs content-package names (case-insensitive lookup)
 * @param requiredCombatLevel minimum combat level to enter (0 = no level gate)
 * @param keyMaterial         item consumed on entry, or {@code null} for no key gate
 * @param keyAmount           how many of {@code keyMaterial} are required/consumed (>= 1)
 * @param region              in-place dungeon boundary, or {@code null} for a world-keyed gate
 */
public record DungeonGate(String world, List<String> aliases, int requiredCombatLevel,
                          Material keyMaterial, int keyAmount, GateRegion region) {

    public DungeonGate {
        Objects.requireNonNull(world, "world");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        if (requiredCombatLevel < 0) {
            requiredCombatLevel = 0;
        }
        if (keyAmount < 1) {
            keyAmount = 1;
        }
    }

    /** Back-compat constructor without a region (world/alias-keyed gate). */
    public DungeonGate(String world, List<String> aliases, int requiredCombatLevel,
                       Material keyMaterial, int keyAmount) {
        this(world, aliases, requiredCombatLevel, keyMaterial, keyAmount, null);
    }

    /** Back-compat constructor without aliases. */
    public DungeonGate(String world, int requiredCombatLevel, Material keyMaterial, int keyAmount) {
        this(world, List.of(), requiredCombatLevel, keyMaterial, keyAmount, null);
    }

    public boolean keyRequired() {
        return keyMaterial != null;
    }

    public boolean hasRegion() {
        return region != null;
    }
}
