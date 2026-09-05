package com.trinityforge.items;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台帳 W-312: 99個を超えるスタックがアイテムエンティティのコーデック上限
 * ({@code [1,99]})を超えて地面に落ち、チャンク保存時に無言で消える不具合の回帰テスト。
 */
class ItemStackDropsTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void splitsA257CountRottenFleshStackIntoFiveStacksSummingToTheOriginal() {
        ItemStack stack = new ItemStack(Material.ROTTEN_FLESH, 257); // max stack size 64
        List<ItemStack> parts = ItemStackDrops.split(stack);

        assertEquals(5, parts.size(), "257 = 64*4 + 1 -> 5 stacks");
        int sum = parts.stream().mapToInt(ItemStack::getAmount).sum();
        assertEquals(257, sum);
        for (ItemStack part : parts) {
            assertTrue(part.getAmount() <= 64, "each part must not exceed the material's max stack size");
            assertTrue(part.getAmount() <= 99, "each part must not exceed the item-entity codec limit (99)");
        }
    }

    @Test
    void splits100CountMax64StackIntoTwoParts() {
        ItemStack stack = new ItemStack(Material.IRON_INGOT, 100); // max stack size 64
        List<ItemStack> parts = ItemStackDrops.split(stack);

        assertEquals(2, parts.size());
        assertEquals(64, parts.get(0).getAmount());
        assertEquals(36, parts.get(1).getAmount());
    }

    @Test
    void singleItemReturnsOneClonedStackNotTheSameInstance() {
        ItemStack stack = new ItemStack(Material.DIAMOND, 1);
        List<ItemStack> parts = ItemStackDrops.split(stack);

        assertEquals(1, parts.size());
        assertEquals(1, parts.get(0).getAmount());
        assertNotSame(stack, parts.get(0), "split must never hand back the caller's own instance");
    }

    @Test
    void maxStackSizeOneMaterialWithThreeItemsSplitsIntoThreeStacks() {
        // NETHERITE_SWORD (maxStackSize 1) を3個 -> 分割単位は min(99,1)=1 -> 3本。
        ItemStack stack = new ItemStack(Material.NETHERITE_SWORD, 3);
        List<ItemStack> parts = ItemStackDrops.split(stack);

        assertEquals(3, parts.size());
        for (ItemStack part : parts) {
            assertEquals(1, part.getAmount());
        }
    }

    @Test
    void metaSurvivesEveryPartOfTheSplit() {
        ItemStack stack = new ItemStack(Material.ROTTEN_FLESH, 150);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName("Special Flesh");
        stack.setItemMeta(meta);

        List<ItemStack> parts = ItemStackDrops.split(stack);
        assertTrue(parts.size() >= 2);
        for (ItemStack part : parts) {
            assertTrue(part.hasItemMeta());
            assertEquals("Special Flesh", part.getItemMeta().getDisplayName());
        }
    }

    @Test
    void giveOrDropSplitDropsOnlySub99StacksWhenInventoryIsFull() {
        WorldMock world = server.addSimpleWorld("give_or_drop_split_world");
        PlayerMock player = server.addPlayer();
        player.teleport(world.getSpawnLocation());
        // インベントリを埋め尽くして addItem が必ず leftover を返すようにする。
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            player.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
        }

        ItemStack prize = new ItemStack(Material.ROTTEN_FLESH, 257);
        ItemStackDrops.giveOrDropSplit(player, prize);

        List<org.bukkit.entity.Item> dropped =
                new java.util.ArrayList<>(world.getEntitiesByClass(org.bukkit.entity.Item.class));
        assertTrue(!dropped.isEmpty(), "満杯インベントリなら床へ落ちるはず");
        for (org.bukkit.entity.Item item : dropped) {
            assertTrue(item.getItemStack().getAmount() <= 99,
                    "床に落ちたどのエンティティも99個を超えてはならない(コーデック上限)");
        }
        int totalDropped = dropped.stream().mapToInt(item -> item.getItemStack().getAmount()).sum();
        assertEquals(257, totalDropped, "全量が保存される(消えない)こと");
    }
}
