package com.trinityforge.listeners;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EliteMobsCommandGateListener}(2026-07-27 許可サブコマンド方式への変更): ラベル単位の全面
 * ブロックから、{@code em}/{@code elitemobs} はサブコマンド単位の許可リストへ緩和した。
 */
class EliteMobsCommandGateListenerTest {

    @Test
    void allowsListedEmSubcommands() {
        assertFalse(EliteMobsCommandGateListener.isBlocked("em", "start"));
        assertFalse(EliteMobsCommandGateListener.isBlocked("em", "quit"));
        assertFalse(EliteMobsCommandGateListener.isBlocked("em", "track"));
        assertFalse(EliteMobsCommandGateListener.isBlocked("em", "dungeontp"));
        assertFalse(EliteMobsCommandGateListener.isBlocked("em", "dungeontpdialog"));
        assertFalse(EliteMobsCommandGateListener.isBlocked("em", "spawntp"));
        assertFalse(EliteMobsCommandGateListener.isBlocked("em", "arena"));
        assertFalse(EliteMobsCommandGateListener.isBlocked("elitemobs", "start"));
    }

    @Test
    void blocksBareEmAndElitemobs() {
        assertTrue(EliteMobsCommandGateListener.isBlocked("em", null));
        assertTrue(EliteMobsCommandGateListener.isBlocked("elitemobs", null));
    }

    @Test
    void blocksUnlistedEmSubcommands() {
        assertTrue(EliteMobsCommandGateListener.isBlocked("em", "shop"));
        assertTrue(EliteMobsCommandGateListener.isBlocked("em", "repair"));
        assertTrue(EliteMobsCommandGateListener.isBlocked("elitemobs", "quest"));
    }

    @Test
    void blocksAdventurersGuildAliasesRegardlessOfSubcommand() {
        assertTrue(EliteMobsCommandGateListener.isBlocked("ag", null));
        assertTrue(EliteMobsCommandGateListener.isBlocked("ag", "start"));
        assertTrue(EliteMobsCommandGateListener.isBlocked("adventurersguild", null));
        assertTrue(EliteMobsCommandGateListener.isBlocked("adventurers_guild", null));
    }

    @Test
    void parsesRawMessageIncludingNamespaceCaseAndSpacing() {
        // 名前空間付き・大文字・余分な空白でもラベル/サブコマンドを正しく取り出せること。
        assertFalse(EliteMobsCommandGateListener.isBlockedCommand("/minecraft:elitemobs track boss abc"));
        assertFalse(EliteMobsCommandGateListener.isBlockedCommand("/EM   Start"));
        assertTrue(EliteMobsCommandGateListener.isBlockedCommand("/minecraft:em"));
        assertTrue(EliteMobsCommandGateListener.isBlockedCommand("/em  shop"));
        assertTrue(EliteMobsCommandGateListener.isBlockedCommand("/AG"));
        // コマンドでない発言・空文字は対象外。
        assertFalse(EliteMobsCommandGateListener.isBlockedCommand("em start"));
        assertFalse(EliteMobsCommandGateListener.isBlockedCommand(""));
        assertFalse(EliteMobsCommandGateListener.isBlockedCommand(null));
    }

    @Test
    void allowsUnrelatedCommands() {
        assertFalse(EliteMobsCommandGateListener.isBlocked("skills", null));
        assertFalse(EliteMobsCommandGateListener.isBlocked("trinityforge", "start"));
        assertFalse(EliteMobsCommandGateListener.isBlocked("help", null));
    }
}
