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
        assertFalse(EliteMobsCommandGateListener.isBlocked("em", "quit"));
        assertFalse(EliteMobsCommandGateListener.isBlocked("em", "track"));
        assertFalse(EliteMobsCommandGateListener.isBlocked("elitemobs", "quit"));
        assertFalse(EliteMobsCommandGateListener.isBlocked("elitemobs", "track"));
    }

    /**
     * 2026-07-30(ユーザー確定): 権限なしプレイヤーが EliteMobs コマンドを使えてしまう問題への対応で
     * 許可リストを {@code quit}/{@code track} まで縮小した。とくに {@code dungeontp} は TF の
     * 鍵/戦闘レベルゲートを迂回してダンジョンへ入れるため、必ず塞がっていること。
     */
    @Test
    void blocksInstanceAndTeleportSubcommandsForUnprivilegedPlayers() {
        assertTrue(EliteMobsCommandGateListener.isBlocked("em", "dungeontp"));
        assertTrue(EliteMobsCommandGateListener.isBlocked("em", "dungeontpdialog"));
        assertTrue(EliteMobsCommandGateListener.isBlocked("em", "spawntp"));
        assertTrue(EliteMobsCommandGateListener.isBlocked("em", "arena"));
    }

    /**
     * 2026-08-17(ユーザー報告): ダンジョン内で {@code /em start} が打てず開始できなかった。
     * {@code start} は入場済みインスタンスの中でしか意味を持たず TF のゲートを迂回しないので許可する。
     * {@code quit} は元から許可(閉じ込め防止)。
     */
    @Test
    void allowsInstanceStartAndQuitForUnprivilegedPlayers() {
        assertFalse(EliteMobsCommandGateListener.isBlocked("em", "start"));
        assertFalse(EliteMobsCommandGateListener.isBlocked("elitemobs", "start"));
        assertFalse(EliteMobsCommandGateListener.isBlocked("em", "quit"));
        assertFalse(EliteMobsCommandGateListener.isBlocked("elitemobs", "quit"));
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
        // 大文字・余分な空白でもラベル/サブコマンドを取り出せること(許可判定そのものは別テスト)。
        assertFalse(EliteMobsCommandGateListener.isBlockedCommand("/EM   Start"));
        assertTrue(EliteMobsCommandGateListener.isBlockedCommand("/EM   Shop"));
        assertFalse(EliteMobsCommandGateListener.isBlockedCommand("/EM   Quit"));
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
