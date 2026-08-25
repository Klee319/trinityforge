package com.trinityforge.progression;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SettingsGui} のページ送り (2026-08-17 ユーザー報告「称号とパーティクルの9個目以降が
 * /tf settings の設定に表示されない」)。
 *
 * <p>以前は 1 行 8 件で打ち切っており、出荷 special-rewards.yml の称号20件のうち12件が
 * GUI から永久に見えなかった。<b>件数で黙って切らない</b>ことがこのテストの主題。
 */
class SettingsGuiPagingTest {

    private static final int TITLE_ROW_START = 9;
    private static final int TITLE_NEXT_PAGE_SLOT = TITLE_ROW_START + 17;

    private ServerMock server;
    private SettingsGui gui;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        SpecialRewardsConfig config = mock(SpecialRewardsConfig.class);
        Map<String, SpecialRewardsConfig.Title> titles = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            titles.put("title_" + i, mock(SpecialRewardsConfig.Title.class));
        }
        when(config.titles()).thenReturn(titles);
        when(config.particles()).thenReturn(Map.of());
        SpecialRewardService rewardService = mock(SpecialRewardService.class);
        when(rewardService.isUnlocked(any(), anyString())).thenReturn(true);
        gui = new SettingsGui(MockBukkit.createMockPlugin(), config, rewardService,
                id -> java.util.Optional.empty());
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("20件の称号は2ページに分かれ、1ページ目には次ページボタンが出る")
    void twentyTitlesSpanTwoPages() {
        assertEquals(2, SettingsGui.pageCount(20));
        assertEquals(1, SettingsGui.pageCount(0), "0件でも1ページ扱い(ページ番号を0に丸めるため)");

        gui.open(player);
        Inventory open = player.getOpenInventory().getTopInventory();

        // 解除ボタンの次から15件ぶんが埋まっていること(旧実装は8件で止まっていた)。
        for (int i = 1; i <= 15; i++) {
            assertNotNull(open.getItem(TITLE_ROW_START + i), "1ページ目のスロット " + i + " が空");
        }
        assertNotNull(open.getItem(TITLE_NEXT_PAGE_SLOT), "次ページボタンが無い");
        assertNull(open.getItem(TITLE_ROW_START + 16), "1ページ目に前ページボタンは出さない");
    }

    @Test
    @DisplayName("次ページボタンを押すと残り5件が出る")
    void secondPageShowsTheRemainingTitles() {
        gui.open(player);

        InventoryClickEvent event = new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER, TITLE_NEXT_PAGE_SLOT,
                org.bukkit.event.inventory.ClickType.LEFT,
                org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        gui.onClick(event);

        Inventory open = player.getOpenInventory().getTopInventory();
        for (int i = 1; i <= 5; i++) {
            ItemStack item = open.getItem(TITLE_ROW_START + i);
            assertNotNull(item, "2ページ目のスロット " + i + " が空 (16〜20件目が見えない)");
        }
        assertNull(open.getItem(TITLE_ROW_START + 6), "2ページ目は5件で終わるはず");
        assertTrue(open.getItem(TITLE_ROW_START + 16) != null, "2ページ目には前ページボタンが要る");
        assertNull(open.getItem(TITLE_NEXT_PAGE_SLOT), "最終ページに次ページボタンは出さない");
    }
}
