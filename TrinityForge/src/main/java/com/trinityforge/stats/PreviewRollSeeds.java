package com.trinityforge.stats;

/**
 * Stable sentinel rollSeeds used only for prepare-slot previews (craft / anvil / smithing).
 * Real stamps must never keep these seeds — pickup and next-tick restamp treat them as unstamped.
 */
public final class PreviewRollSeeds {

    /** Craft prepare preview ({@code TFPREV\\0\\1}). */
    public static final long CRAFT = 0x5446505245560001L;
    /** Anvil combine prepare preview ({@code TFANVIL\\1}). */
    public static final long ANVIL = 0x5446414E56494C01L;
    /** Smithing table prepare preview ({@code TFSMITH\\1}). */
    public static final long SMITHING = 0x5446534D49544801L;

    private PreviewRollSeeds() {
    }

    public static boolean isPreview(long rollSeed) {
        return rollSeed == CRAFT || rollSeed == ANVIL || rollSeed == SMITHING;
    }
}
