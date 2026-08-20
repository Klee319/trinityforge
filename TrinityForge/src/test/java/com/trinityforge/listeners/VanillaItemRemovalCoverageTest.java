package com.trinityforge.listeners;

import com.trinityforge.stats.VanillaItemRemover;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-07-28 実サーバ報告「ワールドに修繕が存在してしまっている」の回帰テスト。
 *
 * <p>{@code removed-vanilla-items: [ANY:MENDING]} を設定していても、
 * <ul>
 *   <li>外部プラグインが自前で drop したアイテム(={@link ItemSpawnEvent} しか通らない経路)</li>
 *   <li>チェスト等のコンテナに既に入っている分</li>
 *   <li>エンダーチェスト({@code PlayerInventory#getContents()} に含まれない)</li>
 * </ul>
 * が一切掃除されず、ワールドに残り続けていた。
 */
class VanillaItemRemovalCoverageTest {

    private static final Logger LOG = Logger.getLogger(VanillaItemRemovalCoverageTest.class.getName());

    private ServerMock server;
    private VanillaItemRemovalListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        VanillaItemRemover remover = new VanillaItemRemover();
        remover.updateTargets(List.of("ANY:mending"), LOG);
        listener = new VanillaItemRemovalListener(MockBukkit.createMockPlugin(), remover);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ItemStack mendingBook() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(Enchantment.MENDING, 1, true);
        book.setItemMeta(meta);
        return book;
    }

    @Test
    void itemSpawnedByAnyPathIsCancelled() {
        Item entity = mock(Item.class);
        when(entity.getItemStack()).thenReturn(mendingBook());
        ItemSpawnEvent event = mock(ItemSpawnEvent.class);
        when(event.getEntity()).thenReturn(entity);

        listener.onItemSpawn(event);

        verify(event).setCancelled(true);
    }

    @Test
    void openingAChestSweepsItsContents() {
        Inventory chest = server.createInventory(null, 27);
        chest.setItem(3, mendingBook());
        InventoryOpenEvent event = mock(InventoryOpenEvent.class);
        when(event.getInventory()).thenReturn(chest);

        listener.onInventoryOpen(event);

        assertNull(chest.getItem(3), "コンテナに保管された修繕付きアイテムも掃除されること");
    }

    @Test
    void joinSweepsTheEnderChestToo() {
        PlayerMock player = server.addPlayer();
        player.getEnderChest().setItem(0, mendingBook());

        listener.sweepAllOnline();

        assertNull(player.getEnderChest().getItem(0),
                "エンダーチェストは getContents() に含まれないので明示的に掃除すること");
    }
}
