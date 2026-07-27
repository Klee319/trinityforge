package com.trinityforge.listeners;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EliteMobsCommandGateListenerTest {

    @Test
    void blocksEmAndAgAliases() {
        assertTrue(EliteMobsCommandGateListener.isBlockedLabel("em"));
        assertTrue(EliteMobsCommandGateListener.isBlockedLabel("elitemobs"));
        assertTrue(EliteMobsCommandGateListener.isBlockedLabel("ag"));
        assertTrue(EliteMobsCommandGateListener.isBlockedLabel("adventurersguild"));
    }

    @Test
    void allowsUnrelatedCommands() {
        assertFalse(EliteMobsCommandGateListener.isBlockedLabel("skills"));
        assertFalse(EliteMobsCommandGateListener.isBlockedLabel("trinityforge"));
        assertFalse(EliteMobsCommandGateListener.isBlockedLabel("help"));
    }
}
