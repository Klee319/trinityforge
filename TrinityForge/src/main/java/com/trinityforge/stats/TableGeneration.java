package com.trinityforge.stats;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Monotonic counter bumped once per successful {@code /trinityforge reload} (SELECTION_SPEC 5: a
 * stat/lore/attribute table edit must reach items already in play, not just newly-created ones).
 *
 * <p>{@link ItemAssembler} stamps the current value into every item it assembles
 * ({@code PdcKeys.ITEM_TABLE_GENERATION} via {@code ItemData}); the refresh listener compares an
 * item's stamped value against {@link #current()} ({@link ItemRefreshPolicy#needsRefresh}) so it
 * can skip re-assembling an item that is already current instead of doing so on every inventory
 * scan. The initial value is randomized per plugin enable so items persisted by an earlier server
 * process are stale even when config was edited while the server was offline.
 */
public final class TableGeneration {

    private final AtomicInteger generation;

    public TableGeneration() {
        this(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
    }

    TableGeneration(int initialGeneration) {
        if (initialGeneration <= 0) {
            throw new IllegalArgumentException("initialGeneration must be positive");
        }
        this.generation = new AtomicInteger(initialGeneration);
    }

    /** The generation in effect right now. */
    public int current() {
        return generation.get();
    }

    /** Called once a config reload completes; every previously-assembled item becomes stale. */
    public int bump() {
        return generation.incrementAndGet();
    }
}
