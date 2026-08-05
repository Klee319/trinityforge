package com.trinityforge.skilltree.runtime;

import com.trinityforge.progression.NativeProgressionService;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NativeSkillTreeMenu}: パーク一覧モード(2026-08-05新設, W-29)の回帰。
 *
 * <p>{@code NativeSkillTreeMenuOverviewTest} と同じ流儀(実物のカタログ/リポジトリ/サービスを組み、
 * ツリー供給元だけを固定リストへ差し替える)で構築する。モード判定は{@code Session}が非公開のため、
 * 通常モードだけが描画する移動矢印(スロット4)の有無で行う。
 *
 * <p>守る不変条件:
 * <ol>
 *   <li>最下段左端(スロット45)は<b>全モードで</b>時計アイコン固定。選択バーはそこを明け渡して
 *       46-52 の7枠になる(45が選択バーに食われると要件そのものが成立しない)。</li>
 *   <li>パーク一覧はそのツリーの<b>全パーク</b>を出す。格子容量(20)を超えるツリー(実物では POWER が
 *       35 パーク)は<b>ページ送りで残りを出す</b> — 黙って切り落とすと「一覧に無いパークがある」
 *       ことに誰も気づけない。</li>
 *   <li>パークをクリックすると通常モードへ戻り、そのパークがビューポート中央に来る。</li>
 * </ol>
 */
class NativeSkillTreeMenuPerkListTest {

    private static final int NAV_SLOT_PROBE = 4;   // "move-n"(通常モードのみ描画される)
    private static final int VIEWPORT_CENTER = 22; // 9x5ビューポートの中心(row2,col4)
    private static final int BIG_TREE_NODES = 25;  // 格子容量20を超える(=2ページになる)
    /**
     * ツリーごとに生成される合成ルートパーク({@code <compact>_perk_root}、lv0/コスト0)の分。
     * config のノード数と一覧の件数は 1 ずれる — キャンバス上に実在するマスなので一覧にも出す。
     */
    private static final int SYNTHETIC_ROOT_PERKS = 1;

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;
    private SqliteProgressionRepository repository;
    private NativeSkillTreeMenu menu;
    private NamespacedKey actionKey;
    private NamespacedKey valueKey;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("TrinityForge");
        player = server.addPlayer();

        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        repository = new SqliteProgressionRepository("jdbc:sqlite::memory:");
        NativeProgressionService progression = new NativeProgressionService(repository, catalog);
        NativePerkService perks = new NativePerkService(progression, () -> List.of(
                trunkTree(SkillId.MINING, "採掘", 2),
                trunkTree(SkillId.WOODCUTTING, "伐採", BIG_TREE_NODES)));
        menu = new NativeSkillTreeMenu(plugin, progression, perks, ignored -> { });
        actionKey = new NamespacedKey(plugin, "skill_menu_action");
        valueKey = new NamespacedKey(plugin, "skill_menu_value");
    }

    @AfterEach
    void tearDown() throws Exception {
        repository.close();
        MockBukkit.unmock();
    }

    /** 主軸(MAIN)だけを縦一列に連ねたツリー。ノード数だけを変えたいのでレイアウトは最小構成にする。 */
    private static SkillTree trunkTree(String skillId, String displayName, int nodeCount) {
        LinkedHashMap<String, SkillNode> nodes = new LinkedHashMap<>();
        String previous = null;
        for (int i = 1; i <= nodeCount; i++) {
            String id = String.format("M%02d", i);
            nodes.put(id, new SkillNode(id, "パーク" + i, i * 10, SkillRole.MAIN, previous, null,
                    "STONE", 1, "", Map.of(), Map.of(), List.of(), List.of(), List.of()));
            previous = id;
        }
        return new SkillTree(skillId, displayName, "STONE", "2,10", null, nodes);
    }

    @Test
    void theClockIsPinnedToTheBottomLeftAndTheSkillSelectorGivesUpThatSlot() {
        menu.open(player);
        Inventory top = player.getOpenInventory().getTopInventory();

        ItemStack clock = top.getItem(SkillTreeOverviewLayout.PERK_LIST_SLOT);
        assertNotNull(clock, "最下段左端はパーク一覧トグルで埋まっていること");
        assertEquals("toggle-perk-list", action(clock));
        assertEquals(Material.CLOCK, clock.getType(), "ユーザー指定のアイコンは時計");
        assertTrue(plain(clock).contains("パーク一覧"));

        // 選択バーは 46-52 の7枠へ縮む(45 を明け渡した分)。
        for (int slot = 46; slot <= 52; slot++) {
            assertEquals("select-skill", action(top.getItem(slot)), "選択バーのスロット " + slot);
        }
        assertEquals("toggle-view", action(top.getItem(SkillTreeOverviewLayout.TOGGLE_SLOT)));
    }

    @Test
    void theClockIsAlsoPinnedInTheAllTreeOverviewSoTheButtonNeverMoves() {
        menu.open(player);
        clickSlot(SkillTreeOverviewLayout.TOGGLE_SLOT);
        server.getScheduler().performOneTick();

        Inventory top = player.getOpenInventory().getTopInventory();
        assertNull(top.getItem(NAV_SLOT_PROBE), "前提: 全ツリー一覧モードに居る");
        assertEquals("toggle-perk-list", action(top.getItem(SkillTreeOverviewLayout.PERK_LIST_SLOT)));
    }

    @Test
    void togglingTheClockListsEveryPerkOfTheCurrentTreeAndClickingOneCentersItInDetailMode() {
        menu.open(player); // MINING(2ノード)
        clickSlot(SkillTreeOverviewLayout.PERK_LIST_SLOT);
        server.getScheduler().performOneTick();

        Inventory top = player.getOpenInventory().getTopInventory();
        assertNull(top.getItem(NAV_SLOT_PROBE), "パーク一覧モードはツリー本体も移動矢印も描画しない");
        assertTrue(plain(top.getItem(SkillTreeOverviewLayout.PERK_LIST_SLOT)).contains("通常表示に戻る"),
                "一覧に入ったら同じボタンが戻る操作になる");

        List<Integer> perkSlots = slotsWithAction(top, "jump-perk");
        assertEquals(2 + SYNTHETIC_ROOT_PERKS, perkSlots.size(),
                "2ノードのツリーは合成ルートを含む3パークが出る");
        ItemStack second = top.getItem(perkSlots.get(1));
        assertTrue(plain(second).contains("クリックでこのパークへ移動"));
        String perkName = displayName(second);

        clickSlot(perkSlots.get(1));
        server.getScheduler().performOneTick();

        Inventory detail = player.getOpenInventory().getTopInventory();
        assertNotNull(detail.getItem(NAV_SLOT_PROBE), "パークをクリックすると通常モードへ戻る");
        ItemStack centered = detail.getItem(VIEWPORT_CENTER);
        assertNotNull(centered, "クリックしたパークがビューポート中央に据えられる");
        assertEquals(perkName, displayName(centered));
        assertEquals("node", action(centered));
    }

    @Test
    void perksBeyondTheGridCapacityAreReachableThroughTheSecondPageInsteadOfBeingDropped() {
        menu.open(player, SkillId.WOODCUTTING); // 25ノード > 格子容量20
        clickSlot(SkillTreeOverviewLayout.PERK_LIST_SLOT);
        server.getScheduler().performOneTick();

        Inventory first = player.getOpenInventory().getTopInventory();
        assertNull(first.getItem(SkillTreeOverviewLayout.PAGE_PREV_SLOT), "1ページ目に「前へ」は出さない");
        ItemStack next = first.getItem(SkillTreeOverviewLayout.PAGE_NEXT_SLOT);
        assertNotNull(next, "容量を超えたら「次へ」が出る");
        assertEquals("perk-page", action(next));
        assertEquals("1", value(next));

        Set<String> shown = new HashSet<>(valuesWithAction(first, "jump-perk"));
        assertEquals(SkillTreeOverviewLayout.capacity(), shown.size(), "1ページ目は格子いっぱい");

        clickSlot(SkillTreeOverviewLayout.PAGE_NEXT_SLOT);
        server.getScheduler().performOneTick();

        Inventory second = player.getOpenInventory().getTopInventory();
        assertNotNull(second.getItem(SkillTreeOverviewLayout.PAGE_PREV_SLOT), "2ページ目には「前へ」が出る");
        assertNull(second.getItem(SkillTreeOverviewLayout.PAGE_NEXT_SLOT), "最終ページに「次へ」は出さない");
        List<String> rest = valuesWithAction(second, "jump-perk");
        int total = BIG_TREE_NODES + SYNTHETIC_ROOT_PERKS;
        assertEquals(total - SkillTreeOverviewLayout.capacity(), rest.size(),
                "残りのパークが2ページ目に出る");

        shown.addAll(rest);
        assertEquals(total, shown.size(), "全パークがどこかのページで必ず見られる");
    }

    /**
     * W-30(2026-08-06): 閉じて開き直したときにスクロール位置・モード・ページが戻ること。
     * ここで縛るのは「パーク一覧から飛んだ中心が再オープンで維持される」までの往復。
     */
    @Test
    void reopeningRestoresTheScrollPositionInsteadOfSnappingBackToTheStart() {
        menu.open(player);
        String atStart = displayName(player.getOpenInventory().getTopInventory().getItem(VIEWPORT_CENTER));

        clickSlot(SkillTreeOverviewLayout.PERK_LIST_SLOT);
        server.getScheduler().performOneTick();
        List<Integer> perkSlots = slotsWithAction(player.getOpenInventory().getTopInventory(), "jump-perk");
        clickSlot(perkSlots.get(2)); // 合成ルート・M01 の次 = M02
        server.getScheduler().performOneTick();

        String moved = displayName(player.getOpenInventory().getTopInventory().getItem(VIEWPORT_CENTER));
        assertNotEquals(atStart, moved, "前提: ジャンプで中心が動いていること");

        menu.open(player); // 閉じて開き直した相当
        assertEquals(moved, displayName(
                        player.getOpenInventory().getTopInventory().getItem(VIEWPORT_CENTER)),
                "再オープンで初期位置へ戻らないこと");
    }

    @Test
    void reopeningRestoresThePerkListModeAndItsPage() {
        menu.open(player, SkillId.WOODCUTTING);
        clickSlot(SkillTreeOverviewLayout.PERK_LIST_SLOT);
        server.getScheduler().performOneTick();
        clickSlot(SkillTreeOverviewLayout.PAGE_NEXT_SLOT);
        server.getScheduler().performOneTick();

        menu.open(player);

        Inventory top = player.getOpenInventory().getTopInventory();
        assertNull(top.getItem(NAV_SLOT_PROBE), "パーク一覧モードのまま開くこと");
        assertNotNull(top.getItem(SkillTreeOverviewLayout.PAGE_PREV_SLOT),
                "2ページ目のまま開くこと(「前へ」が出ている)");
    }

    @Test
    void askingForADifferentTreeStartsFromItsOwnOriginInsteadOfTheRememberedPosition() {
        menu.open(player, SkillId.WOODCUTTING);
        clickSlot(SkillTreeOverviewLayout.PERK_LIST_SLOT);
        server.getScheduler().performOneTick();

        // 別ツリーを名指しで開いたら、覚えている位置ではなくそのツリーの起点から。
        menu.open(player, SkillId.MINING);

        Inventory top = player.getOpenInventory().getTopInventory();
        assertNotNull(top.getItem(NAV_SLOT_PROBE), "名指しの別ツリーは通常モードで開くこと");
    }

    private void clickSlot(int slot) {
        InventoryClickEvent event = new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER, slot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        menu.onClick(event);
    }

    private List<Integer> slotsWithAction(Inventory inventory, String expected) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && stack.hasItemMeta() && expected.equals(action(stack))) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private List<String> valuesWithAction(Inventory inventory, String expected) {
        List<String> values = new ArrayList<>();
        for (int slot : slotsWithAction(inventory, expected)) {
            values.add(value(inventory.getItem(slot)));
        }
        return values;
    }

    private String action(ItemStack stack) {
        assertNotNull(stack);
        return stack.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    private String value(ItemStack stack) {
        return stack.getItemMeta().getPersistentDataContainer().get(valueKey, PersistentDataType.STRING);
    }

    private static String displayName(ItemStack stack) {
        var name = stack.getItemMeta().displayName();
        return name == null ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(name);
    }

    private static String plain(ItemStack stack) {
        StringBuilder text = new StringBuilder(displayName(stack));
        for (var line : stack.getItemMeta().lore() == null
                ? List.<net.kyori.adventure.text.Component>of() : stack.getItemMeta().lore()) {
            text.append(' ').append(
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(line));
        }
        return text.toString();
    }
}
