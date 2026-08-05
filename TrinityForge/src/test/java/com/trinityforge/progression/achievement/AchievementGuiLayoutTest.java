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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /achievement} GUI の配置(2026-08-06, W-31)。
 *
 * <p>「スキルツリーと同じ配置に揃える」がユーザー要件なので、ここで縛るのは<b>スロット番号そのもの</b>:
 * <ol>
 *   <li>8方向の視点移動はキャンバスの四隅・辺の中央({@code NativeSkillTreeMenu} と同じ 0/4/8/18/26/36/40/44)</li>
 *   <li>最下段 45-52 は系統(<b>ルート実績だけ</b>)の切替バー、53 は達成状況(スキルツリーが同じ位置に
 *       モード切替を固定しているのに合わせた固定枠)</li>
 *   <li>ルートがバー幅(8枠)に収まるうちは<b>左詰め</b>。巡回(選択中を中央)に切り替わるのは
 *       9件以上のときだけ — 少数で巡回すると同じルートが8枠に並ぶだけになる</li>
 *   <li>バーのクリックは<b>解放(claim)へ流れない</b> — 起点が達成済みでも、バーで選んだだけで
 *       報酬が確定してはいけない</li>
 * </ol>
 */
class AchievementGuiLayoutTest {

    private static final Logger LOG = Logger.getLogger("AchievementGuiLayoutTest");
    private static final List<Integer> NAV_SLOTS = List.of(0, 4, 8, 18, 26, 36, 40, 44);
    private static final int SUMMARY_SLOT = 53;
    private static final int HEAD_BAR_FIRST = 45;
    private static final int HEAD_BAR_LAST = 52;
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

    /**
     * ルートが2本(main / war)ある形。main の下に子が2つぶら下がるので、
     * 「子は系統の起点にならない」も同時に見られる。
     */
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
                  delve:
                    display-name: "初踏破"
                    parent: main
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 9999
                  war:
                    display-name: "百を斬る"
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 9999
                """);
    }

    /** ルートだけを {@code count} 本並べたconfig(バー幅を超えたときの巡回表示を見る用)。 */
    private AchievementsConfig rootsConfig(int count) throws Exception {
        StringBuilder yaml = new StringBuilder("achievements:\n");
        for (int i = 0; i < count; i++) {
            yaml.append("  root").append(i).append(":\n")
                    .append("    display-name: \"起点").append(i).append("\"\n")
                    .append("    coords: \"").append(i * 3).append(",0\"\n")
                    .append("    trigger:\n")
                    .append("      type: statistic\n")
                    .append("      statistic: JUMP\n")
                    .append("      threshold: 9999\n");
        }
        return configOf(yaml.toString());
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
        for (int slot = HEAD_BAR_FIRST; slot <= SUMMARY_SLOT; slot++) {
            ItemStack stack = top.getItem(slot);
            if (stack != null && stack.hasItemMeta()) {
                assertFalse(stack.getItemMeta().getPersistentDataContainer()
                        .has(navX, PersistentDataType.INTEGER),
                        "最下段に視点移動が残っている: " + slot);
            }
        }
    }

    /**
     * ルート実績だけがバーに並ぶ(2026-08-06 ユーザー確定)。ルート2本なら 45,46 に左詰めで、
     * 47-52 は空。分岐先(life / delve)は起点として並べない。
     */
    @Test
    void theBottomRowListsOnlyRootsLeftAlignedAndTheSummaryKeepsTheRightEnd() throws Exception {
        AchievementGui gui = new AchievementGui(plugin, branchedConfig(), null, null, null);
        gui.open(player);
        Inventory top = player.getOpenInventory().getTopInventory();

        assertEquals(List.of("main", "war"), headsIn(top), "系統バーはルートだけ・config順の左詰め");
        assertEquals("main", head(top.getItem(HEAD_BAR_FIRST)));
        assertEquals("war", head(top.getItem(HEAD_BAR_FIRST + 1)));
        for (int slot = HEAD_BAR_FIRST + 2; slot <= HEAD_BAR_LAST; slot++) {
            assertNull(head(top.getItem(slot)), "ルート数より多い枠を埋めないこと: " + slot);
        }
        assertEquals("main", selectedHead(top), "既定では先頭の系統が選択中");

        ItemStack summary = top.getItem(SUMMARY_SLOT);
        assertNotNull(summary);
        assertEquals(Material.BOOK, summary.getType());
        assertTrue(plain(summary).contains("達成状況"));
    }

    /** 9件以上でだけ巡回表示に切り替わる(選択中が中央・8枠すべて埋まる)。 */
    @Test
    void moreRootsThanTheBarWidthFallBackToCenteredRotation() throws Exception {
        AchievementGui gui = new AchievementGui(plugin, rootsConfig(10), null, null, null);
        gui.open(player);
        Inventory top = player.getOpenInventory().getTopInventory();

        for (int slot = HEAD_BAR_FIRST; slot <= HEAD_BAR_LAST; slot++) {
            assertNotNull(head(top.getItem(slot)), "巡回表示ではバーに穴が空かない: " + slot);
        }
        assertEquals("root0", head(top.getItem(HEAD_BAR_CENTER)), "選択中が中央に来ること");
        assertEquals("root0", selectedHead(top));
    }

    @Test
    void clickingARootIconRecentersOnItAndMarksItSelected() throws Exception {
        AchievementGui gui = new AchievementGui(plugin, branchedConfig(), null, null, null);
        gui.open(player);

        int warSlot = headSlot(player.getOpenInventory().getTopInventory(), "war");
        assertTrue(warSlot >= 0, "系統バーに war が並んでいること");

        gui.onClick(clickAt(warSlot));
        server.getScheduler().performOneTick();

        Inventory after = player.getOpenInventory().getTopInventory();
        assertEquals("war", selectedHead(after), "押した系統が選択中になること");
        assertEquals("war", focus(after.getItem(VIEWPORT_CENTER)), "その系統の起点がキャンバス中央に来ること");
        assertEquals(List.of("main", "war"), headsIn(after), "左詰めなので並び順は動かさない");
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
            int mainSlot = headSlot(player.getOpenInventory().getTopInventory(), "main");
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

        int warSlot = headSlot(player.getOpenInventory().getTopInventory(), "war");
        gui.onClick(clickAt(warSlot));
        server.getScheduler().performOneTick();
        assertEquals("war", focus(player.getOpenInventory().getTopInventory().getItem(VIEWPORT_CENTER)),
                "前提: war が中央に来ている");

        gui.open(player); // 閉じて開き直した相当

        Inventory reopened = player.getOpenInventory().getTopInventory();
        assertEquals("war", focus(reopened.getItem(VIEWPORT_CENTER)), "スクロール位置が戻ること");
        assertEquals("war", selectedHead(reopened), "選択中の系統も戻ること");
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
        for (int slot = 0; slot < HEAD_BAR_FIRST; slot++) {
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

    /** バーに並んでいる起点IDを左から順に。 */
    private List<String> headsIn(Inventory inventory) {
        List<String> heads = new ArrayList<>();
        for (int slot = HEAD_BAR_FIRST; slot <= HEAD_BAR_LAST; slot++) {
            String headId = head(inventory.getItem(slot));
            if (headId != null) {
                heads.add(headId);
            }
        }
        return heads;
    }

    private int headSlot(Inventory inventory, String headId) {
        for (int slot = HEAD_BAR_FIRST; slot <= HEAD_BAR_LAST; slot++) {
            if (headId.equals(head(inventory.getItem(slot)))) {
                return slot;
            }
        }
        return -1;
    }

    /** 「表示中の系統」と書かれている枠の起点ID(選択中の表現はloreでしか外から見えない)。 */
    private String selectedHead(Inventory inventory) {
        for (int slot = HEAD_BAR_FIRST; slot <= HEAD_BAR_LAST; slot++) {
            ItemStack stack = inventory.getItem(slot);
            String headId = head(stack);
            if (headId == null) {
                continue;
            }
            var lore = stack.getItemMeta().lore();
            if (lore == null) {
                continue;
            }
            for (var line : lore) {
                if (net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(line).contains("表示中の系統")) {
                    return headId;
                }
            }
        }
        return null;
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
