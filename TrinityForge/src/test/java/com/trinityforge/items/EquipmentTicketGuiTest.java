package com.trinityforge.items;

import com.trinityforge.pdc.ItemData;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EquipmentTicketGui} の対象選択GUI共通エンジンのテスト。効果本体は
 * {@link FakeEffect}(このテスト専用の最小実装)に差し替え、GUIの機構(候補列挙/2クリック確定/
 * 対象同一性再確認/スタック2個以上の除外/券の消費)だけを検証する。
 */
class EquipmentTicketGuiTest {

    private static final String TICKET_ID = "test_ticket";

    private ServerMock server;
    private EquipmentTicketGui gui;
    private PlayerMock player;
    private FakeEffect effect;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        gui = new EquipmentTicketGui(MockBukkit.createMockPlugin());
        player = server.addPlayer();
        effect = new FakeEffect();
        player.getInventory().setItemInMainHand(ticketStack());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ItemStack ticketStack() {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId(TICKET_ID);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack stampedGear(int quality) {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = stack.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setRollSeed(999L);
        data.setQuality(quality);
        stack.setItemMeta(meta);
        return stack;
    }

    private InventoryClickEvent clickEvent(int slot) {
        return new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER, slot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
    }

    @Test
    void eligibleGearInInventoryAppearsAsCandidate() {
        // slot 0 は既定の選択中ホットバー = メインハンドと同一スロットなので使わない
        // (使うと setItem(0, ...) がセットアップで置いた券を上書きしてしまう)。
        player.getInventory().setItem(9, stampedGear(3));

        gui.open(player, effect);
        Inventory top = player.getOpenInventory().getTopInventory();

        boolean found = false;
        for (int i = 0; i < top.getSize(); i++) {
            ItemStack item = top.getItem(i);
            if (item != null && item.getType() == Material.DIAMOND_SWORD) {
                found = true;
            }
        }
        assertTrue(found, "候補一覧に対象の装備が出るはず");
    }

    @Test
    void stackOfTwoIsNeverShownAsCandidate() {
        ItemStack twoStack = stampedGear(3);
        twoStack.setAmount(2);
        player.getInventory().setItem(9, twoStack);

        gui.open(player, effect);
        Inventory top = player.getOpenInventory().getTopInventory();

        for (int i = 0; i < top.getSize(); i++) {
            ItemStack item = top.getItem(i);
            assertFalse(item != null && item.getType() == Material.DIAMOND_SWORD,
                    "スタック2個以上は複製/データ喪失になるので候補に出してはいけない");
        }
    }

    @Test
    void firstClickPendsAndDoesNotApplyOrConsumeTicket() {
        // slot 0 は既定の選択中ホットバー = メインハンドと同一スロットなので使わない
        // (使うと setItem(0, ...) がセットアップで置いた券を上書きしてしまう)。
        player.getInventory().setItem(9, stampedGear(3));
        gui.open(player, effect);
        int slot = candidateSlot();

        gui.onClick(clickEvent(slot));

        assertEquals(0, effect.applyCount, "1クリック目はpendingにするだけで適用してはいけない");
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount(),
                "1クリック目では券を消費してはいけない");
    }

    @Test
    void secondClickOnSameTargetConfirmsAndConsumesOneTicket() {
        // slot 0 は既定の選択中ホットバー = メインハンドと同一スロットなので使わない
        // (使うと setItem(0, ...) がセットアップで置いた券を上書きしてしまう)。
        player.getInventory().setItem(9, stampedGear(3));
        gui.open(player, effect);
        int slot = candidateSlot();

        gui.onClick(clickEvent(slot)); // 1クリック目: pending
        int slotAfterPending = candidateSlot(); // 再描画後の同じ対象のスロット
        gui.onClick(clickEvent(slotAfterPending)); // 2クリック目: 確定

        assertEquals(1, effect.applyCount, "2クリック目で確定し、効果が1回だけ適用されるはず");
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        assertTrue(mainHand == null || mainHand.getType() == Material.AIR,
                "券は1個しか持っていなかったので消費後は空になるはず");
    }

    @Test
    void targetChangedBetweenClicksIsRejectedAndDoesNotConsumeTicket() {
        // slot 0 は既定の選択中ホットバー = メインハンドと同一スロットなので使わない
        // (使うと setItem(0, ...) がセットアップで置いた券を上書きしてしまう)。
        player.getInventory().setItem(9, stampedGear(3));
        gui.open(player, effect);
        int slot = candidateSlot();

        gui.onClick(clickEvent(slot)); // 1クリック目: pending(quality=3を記憶)

        // GUIを開いてから確定までの間に対象が変わった(品質が変わった)ことを模す。
        player.getInventory().setItem(9, stampedGear(9));

        int slotAfterPending = candidateSlot();
        gui.onClick(clickEvent(slotAfterPending)); // 2クリック目のつもりが対象不一致

        assertEquals(0, effect.applyCount, "対象が変わっていたら確定させてはいけない");
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount(),
                "確定できなかったのだから券は消費されない");
    }

    @Test
    void nonEligibleItemIsNeverShownAsCandidate() {
        effect.eligible = false;
        // slot 0 は既定の選択中ホットバー = メインハンドと同一スロットなので使わない
        // (使うと setItem(0, ...) がセットアップで置いた券を上書きしてしまう)。
        player.getInventory().setItem(9, stampedGear(3));

        gui.open(player, effect);
        Inventory top = player.getOpenInventory().getTopInventory();

        for (int i = 0; i < top.getSize(); i++) {
            ItemStack item = top.getItem(i);
            assertFalse(item != null && item.getType() == Material.DIAMOND_SWORD,
                    "eligible()がfalseの装備は候補に出してはいけない");
        }
    }

    /** 現在開いているGUIの中から候補ボタン(DIAMOND_SWORD)のスロット番号を探す。 */
    private int candidateSlot() {
        Inventory top = player.getOpenInventory().getTopInventory();
        for (int i = 0; i < top.getSize(); i++) {
            ItemStack item = top.getItem(i);
            if (item != null && item.getType() == Material.DIAMOND_SWORD) {
                return i;
            }
        }
        throw new AssertionError("候補ボタンが見つからない");
    }

    /** テスト専用の最小 {@link EquipmentTicketEffect} 実装。 */
    private static final class FakeEffect implements EquipmentTicketEffect {
        private boolean eligible = true;
        private int applyCount = 0;

        @Override
        public String catalogId() {
            return TICKET_ID;
        }

        @Override
        public String title() {
            return "テスト効果";
        }

        @Override
        public boolean eligible(ItemStack stack) {
            return eligible;
        }

        @Override
        public List<Component> previewLore(ItemStack stack) {
            return List.of(Component.text("preview"));
        }

        @Override
        public Optional<ItemStack> apply(ItemStack stack) {
            applyCount++;
            return Optional.of(stack);
        }

        @Override
        public String appliedMessage() {
            return "applied";
        }
    }
}
