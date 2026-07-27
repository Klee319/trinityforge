package com.trinityforge.gacha;

import java.util.Objects;

/**
 * One weighted prize-table row inside a {@link GachaPool} ({@code gacha.yml pools.*.entries}).
 * {@code itemId} is resolved at draw time against either {@code items/catalog.yml} (a TrinityForge
 * catalog id) or a vanilla {@link org.bukkit.Material} name — {@link GachaEntry} itself stays
 * Bukkit-free so {@link GachaDraw} can be unit-tested with no server running.
 *
 * @param itemId        catalog id or vanilla Material name (resolved by the listener, not here)
 * @param weight        relative draw weight; must be {@code > 0}
 * @param amount        stack size granted on a win; must be {@code > 0}
 * @param qualityRandom when {@code true} and the resolved item is a TrinityForge catalog item, the
 *                      listener rolls a random quality in {@code [0, maxQuality]} instead of {@code 0}
 */
public record GachaEntry(String itemId, int weight, int amount, boolean qualityRandom) {

    public GachaEntry {
        Objects.requireNonNull(itemId, "itemId");
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (weight <= 0) {
            throw new IllegalArgumentException("weight must be > 0: " + weight);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be > 0: " + amount);
        }
    }
}
