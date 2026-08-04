package com.trinityforge.progression.achievement;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.progression.AchievementService;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /achievement} GUI の解放フロー(2026-08-04 手動解放方式)。
 *
 * <p>{@code NativeSkillTreeMenu#handleNode} と同じ「1クリック目で確認待ち、同一ノードへの
 * 2クリック目で確定」パターンが GUI 経由でも実際に報酬を付与すること、条件成立していない
 * ノードは何度クリックしても解放されないことを検証する。クリックイベントは
 * {@code DungeonEntryGuiTest} と同じ流儀で直接構築し(Bukkit の {@code callEvent} は
 * モックイベントの {@code HandlerList} が null で NPE になるため)、{@code onClick} を直接呼ぶ。
 */
class AchievementGuiClaimFlowTest {

    private static final Logger LOG = Logger.getLogger("AchievementGuiClaimFlowTest");

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("TrinityForge");
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private AchievementsConfig configOf(String yaml) throws Exception {
        File file = new File(plugin.getDataFolder(), AchievementsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        AchievementsConfig config = new AchievementsConfig();
        config.load(plugin);
        return config;
    }

    @Test
    void secondClickOnSameNodeClaimsAndGrantsReward() throws Exception {
        AchievementsConfig config = configOf("""
                achievements:
                  jump-king:
                    display-name: "ジャンプ王"
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 1
                    rewards:
                      vanilla-exp: 50
                """);
        AchievementService service = new AchievementService(config, LOG);
        AchievementGui gui = new AchievementGui(plugin, config, null, null, service);

        player.incrementStatistic(Statistic.JUMP);
        service.pollStatistics();
        assertTrue(PlayerData.of(player).achievedIds().contains("jump-king"), "条件成立で達成扱い");
        assertFalse(PlayerData.of(player).claimedAchievementIds().contains("jump-king"),
                "達成しただけでは未解放のまま");

        gui.open(player);
        int expBefore = player.getTotalExperience();
        int slot = findSlotFor(player.getOpenInventory().getTopInventory(), "jump-king");

        gui.onClick(clickAt(slot));
        server.getScheduler().performOneTick();
        assertFalse(PlayerData.of(player).claimedAchievementIds().contains("jump-king"),
                "1クリック目は確認待ちにするだけで解放しないこと");

        gui.onClick(clickAt(slot));
        server.getScheduler().performOneTick();

        assertTrue(PlayerData.of(player).claimedAchievementIds().contains("jump-king"),
                "同一ノードへの2クリック目で解放が確定すること");
        assertTrue(player.getTotalExperience() > expBefore, "解放後に報酬(vanilla-exp)が付与されること");
    }

    @Test
    void clickingAnUnachievedNodeNeverClaimsIt() throws Exception {
        AchievementsConfig config = configOf("""
                achievements:
                  jump-king:
                    display-name: "ジャンプ王"
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 5
                """);
        AchievementService service = new AchievementService(config, LOG);
        AchievementGui gui = new AchievementGui(plugin, config, null, null, service);
        gui.open(player);
        int slot = findSlotFor(player.getOpenInventory().getTopInventory(), "jump-king");

        gui.onClick(clickAt(slot));
        server.getScheduler().performOneTick();
        gui.onClick(clickAt(slot));
        server.getScheduler().performOneTick();

        assertFalse(PlayerData.of(player).claimedAchievementIds().contains("jump-king"),
                "条件未達成のノードは何度クリックしても解放されないこと");
    }

    private InventoryClickEvent clickAt(int slot) {
        return new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER, slot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
    }

    private int findSlotFor(Inventory inventory, String achievementId) {
        NamespacedKey focusKey = new NamespacedKey(plugin, "achievement_focus");
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || !stack.hasItemMeta()) {
                continue;
            }
            String id = stack.getItemMeta().getPersistentDataContainer()
                    .get(focusKey, PersistentDataType.STRING);
            if (achievementId.equals(id)) {
                return i;
            }
        }
        throw new IllegalStateException("achievement node not found in viewport: " + achievementId);
    }
}
