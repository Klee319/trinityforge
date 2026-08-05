package com.trinityforge.progression.achievement;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.progression.AchievementService;
import org.bukkit.Material;
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
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /achievement} GUI の配置(2026-08-06, W-31)。
 *
 * <p>「スキルツリーと同じ配置に揃える」がユーザー要件なので、ここで縛るのは<b>スロット番号そのもの</b>:
 * <ol>
 *   <li>8方向の視点移動はキャンバスの四隅・辺の中央({@code NativeSkillTreeMenu} と同じ 0/4/8/18/26/36/40/44)</li>
 *   <li>最下段 45-52 は系統(ルート実績)の切替バー、53 は達成状況(スキルツリーが同じ位置に
 *       モード切替を固定しているのに合わせた固定枠)</li>
 *   <li>バーのクリックは<b>解放(claim)へ流れない</b> — 起点が達成済みでも、バーで選んだだけで
 *       報酬が確定してはいけない</li>
 * </ol>
 */
class AchievementGuiLayoutTest {

    private static final Logger LOG = Logger.getLogger("AchievementGuiLayoutTest");
    private static final List<Integer> NAV_SLOTS = List.of(0, 4, 8, 18, 26, 36, 40, 44);
    private static final int SUMMARY_SLOT = 53;
    private static final int HEAD_BAR_CENTER = 49;
    private static final int VIEWPORT_CENTER = 22;

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

    /** main の下に3系統がぶら下がる、出荷config と同じ形(真のルートは1件だけ)。 */
    private AchievementsConfig branchedConfig() throws Exception {
        return configOf("""
                achievements:
                  main:
                    display-name: "初めの一歩"
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 1
                  life:
                    display-name: "薪を積む"
                    parent: main
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 9999
                  war:
                    display-name: "百を斬る"
                    parent: main
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 9999
                  delve:
                    display-name: "初踏破"
                    parent: main
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 9999
                """);
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
    void navigationSitsOnTheCanvasCornersAndEdgesLikeTheSkillTree() throws Exception {
        AchievementGui gui = new AchievementGui(plugin, branchedConfig(), null, null, null);
        gui.open(player);
        Inventory top = player.getOpenInventory().getTopInventory();

        NamespacedKey navX = new NamespacedKey(plugin, "achievement_nav_x");
        for (int slot : NAV_SLOTS) {
            ItemStack stack = top.getItem(slot);
            assertNotNull(stack, "視点移動ボタンが無いスロット: " + slot);
            assertNotNull(stack.getItemMeta().getPersistentDataContainer()
                    .get(navX, PersistentDataType.INTEGER), "スロット " + slot + " は視点移動ボタンであること");
        }
        // 旧配置(最下段の 45-53)には視点移動を残さない。
        for (int slot = 45; slot <= 53; slot++) {
            ItemStack stack = top.getItem(slot);
            if (stack != null && stack.hasItemMeta()) {
                assertFalse(stack.getItemMeta().getPersistentDataContainer()
                        .has(navX, PersistentDataType.INTEGER),
                        "最下段に視点移動が残っている: " + slot);
            }
        }
    }

    @Test
    void theBottomRowIsTheBranchSelectorAndTheSummaryKeepsTheRightEnd() throws Exception {
        AchievementGui gui = new AchievementGui(plugin, branchedConfig(), null, null, null);
        gui.open(player);
        Inventory top = player.getOpenInventory().getTopInventory();

        for (int slot = 45; slot <= 52; slot++) {
            assertNotNull(head(top.getItem(slot)), "系統切替バーに穴がある: " + slot);
        }
        assertEquals("main", head(top.getItem(HEAD_BAR_CENTER)), "選択中の系統が中央に来ること");

        ItemStack summary = top.getItem(SUMMARY_SLOT);
        assertNotNull(summary);
        assertEquals(Material.BOOK, summary.getType());
        assertTrue(plain(summary).contains("達成状況"));
    }

    @Test
    void clickingABranchIconRecentersOnItAndRotatesTheBar() throws Exception {
        AchievementGui gui = new AchievementGui(plugin, branchedConfig(), null, null, null);
        gui.open(player);
        Inventory top = player.getOpenInventory().getTopInventory();

        int warSlot = -1;
        for (int slot = 45; slot <= 52; slot++) {
            if ("war".equals(head(top.getItem(slot)))) {
                warSlot = slot;
                break;
            }
        }
        assertTrue(warSlot >= 0, "系統バーに war が並んでいること");

        gui.onClick(clickAt(warSlot));
        server.getScheduler().performOneTick();

        Inventory after = player.getOpenInventory().getTopInventory();
        assertEquals("war", head(after.getItem(HEAD_BAR_CENTER)), "押した系統が中央へ回ること");
        assertEquals("war", focus(after.getItem(VIEWPORT_CENTER)), "その系統の起点がキャンバス中央に来ること");
    }

    @Test
    void clickingTheBranchIconOfAnAchievedRootNeverClaimsIt() throws Exception {
        AchievementsConfig config = branchedConfig();
        AchievementService service = new AchievementService(config, LOG);
        AchievementGui gui = new AchievementGui(plugin, config, null, null, service);

        player.incrementStatistic(Statistic.JUMP);
        service.pollStatistics();
        assertTrue(PlayerData.of(player).achievedIds().contains("main"), "前提: main は達成済み");

        gui.open(player);
        // バーの main を2回押す(ノードなら1回目=確認待ち・2回目=解放が確定するクリック回数)。
        for (int i = 0; i < 2; i++) {
            Inventory top = player.getOpenInventory().getTopInventory();
            int mainSlot = -1;
            for (int slot = 45; slot <= 52; slot++) {
                if ("main".equals(head(top.getItem(slot)))) {
                    mainSlot = slot;
                    break;
                }
            }
            assertTrue(mainSlot >= 0);
            gui.onClick(clickAt(mainSlot));
            server.getScheduler().performOneTick();
        }

        assertFalse(PlayerData.of(player).claimedAchievementIds().contains("main"),
                "系統バーのクリックは解放フローへ流れないこと(バーと本体でPDCキーを分けている理由)");
    }

    /**
     * W-30(2026-08-06): 閉じて開き直したときにスクロール位置と選択中の系統が戻ること。
     */
    @Test
    void reopeningRestoresTheScrollPositionAndTheSelectedBranch() throws Exception {
        AchievementGui gui = new AchievementGui(plugin, branchedConfig(), null, null, null);
        gui.open(player);

        int warSlot = -1;
        Inventory top = player.getOpenInventory().getTopInventory();
        for (int slot = 45; slot <= 52; slot++) {
            if ("war".equals(head(top.getItem(slot)))) {
                warSlot = slot;
            }
        }
        gui.onClick(clickAt(warSlot));
        server.getScheduler().performOneTick();
        assertEquals("war", focus(player.getOpenInventory().getTopInventory().getItem(VIEWPORT_CENTER)),
                "前提: war が中央に来ている");

        gui.open(player); // 閉じて開き直した相当

        Inventory reopened = player.getOpenInventory().getTopInventory();
        assertEquals("war", focus(reopened.getItem(VIEWPORT_CENTER)), "スクロール位置が戻ること");
        assertEquals("war", head(reopened.getItem(HEAD_BAR_CENTER)), "選択中の系統も戻ること");
    }

    @Test
    void reopeningNeverCarriesOverAPendingClaimConfirmation() throws Exception {
        AchievementsConfig config = branchedConfig();
        AchievementService service = new AchievementService(config, LOG);
        AchievementGui gui = new AchievementGui(plugin, config, null, null, service);

        player.incrementStatistic(Statistic.JUMP);
        service.pollStatistics();
        assertTrue(PlayerData.of(player).achievedIds().contains("main"), "前提: main は達成済み");

        gui.open(player);
        int mainSlot = -1;
        Inventory top = player.getOpenInventory().getTopInventory();
        for (int slot = 0; slot < 45; slot++) {
            if ("main".equals(focus(top.getItem(slot)))) {
                mainSlot = slot;
                break;
            }
        }
        assertTrue(mainSlot >= 0);

        gui.onClick(clickAt(mainSlot)); // 1クリック目 = 確認待ち
        server.getScheduler().performOneTick();
        assertFalse(PlayerData.of(player).claimedAchievementIds().contains("main"));

        gui.open(player); // 一度閉じて開き直す
        gui.onClick(clickAt(mainSlot)); // 開き直した後の1クリック目
        server.getScheduler().performOneTick();

        assertFalse(PlayerData.of(player).claimedAchievementIds().contains("main"),
                "確認待ちを持ち越すと「閉じて開いて1クリック」で解放が確定してしまう");
    }

    private InventoryClickEvent clickAt(int slot) {
        return new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER, slot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
    }

    private String head(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "achievement_head"), PersistentDataType.STRING);
    }

    private String focus(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "achievement_focus"), PersistentDataType.STRING);
    }

    private static String plain(ItemStack stack) {
        var name = stack.getItemMeta().displayName();
        return name == null ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(name);
    }
}
