package com.trinityforge.listeners;

import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.TableGeneration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 既存スレッドは汎用 {@link ItemAssembler#assemble} に通さず、品質と pt を保ったまま
 * Ars の lore 組み直しへ委譲する(W-53 の表更新穴)。
 */
class ItemRefreshListenerTest {

    private static final NamespacedKey THREAD_ITEM_TYPE_KEY =
            new NamespacedKey("arspaper", "thread_item_type");

    private ServerMock server;
    private ItemAssembler assembler;
    private TableGeneration tableGeneration;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        assembler = mock(ItemAssembler.class);
        tableGeneration = new TableGeneration();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ItemStack stampedThread(long rollSeed, int quality) {
        ItemStack stack = new ItemStack(Material.STRING);
        stack.editMeta(meta -> {
            meta.getPersistentDataContainer().set(THREAD_ITEM_TYPE_KEY, PersistentDataType.STRING, "backpack");
            ItemData data = ItemData.of(meta);
            data.setRollSeed(rollSeed);
            data.setQuality(quality);
        });
        return stack;
    }

    @Test
    void staleThreadKeepsSeedAndQualityAndNeverGoesThroughAssemble() {
        AtomicInteger refreshes = new AtomicInteger();
        ItemRefreshListener listener = new ItemRefreshListener(assembler, tableGeneration, stack -> {
            refreshes.incrementAndGet();
            return true;
        });
        PlayerMock player = server.addPlayer();
        ItemStack thread = stampedThread(42L, 7);
        player.getInventory().setItem(4, thread);

        listener.refreshAllOnlinePlayers();

        assertEquals(1, refreshes.get(), "Ars の identity 維持リフレッシュが走ること");
        verify(assembler, never()).assemble(any(), any(), anyLong(), anyInt());
        verify(assembler).appendOwnerLoreIfMissing(any());
        ItemData after = ItemData.of(player.getInventory().getItem(4).getItemMeta());
        assertEquals(42L, after.rollSeed().orElseThrow(), "pt(rollSeed) を消してはいけない");
        assertEquals(7, after.quality(), "品質を振り直してはいけない");
        assertEquals(tableGeneration.current(), after.tableGeneration().orElseThrow(),
                "世代を焼かないと持ち替えのたびに何度も組み直す");
    }

    @Test
    void currentGenerationThreadStillRefreshesLoreOnHold() {
        AtomicInteger refreshes = new AtomicInteger();
        ItemRefreshListener listener = new ItemRefreshListener(assembler, tableGeneration, stack -> {
            refreshes.incrementAndGet();
            return true;
        });
        PlayerMock player = server.addPlayer();
        ItemStack thread = stampedThread(42L, 7);
        thread.editMeta(meta -> ItemData.of(meta).setTableGeneration(tableGeneration.current()));
        player.getInventory().setItemInMainHand(thread);

        listener.refreshAllOnlinePlayers();

        assertEquals(1, refreshes.get(),
                "スレッドは世代が最新でも持ち替えで lore を組み直す（Ars 側フォーマット変更を届ける）");
        verify(assembler, never()).assemble(any(), any(), anyLong(), anyInt());
        verify(assembler).appendOwnerLoreIfMissing(any());
    }

    @Test
    void threadStillSkipsAssembleWhenTheArsRefreshFails() {
        ItemRefreshListener listener = new ItemRefreshListener(assembler, tableGeneration, stack -> false);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, stampedThread(99L, 3));

        listener.refreshAllOnlinePlayers();

        verify(assembler, never()).assemble(any(), any(), anyLong(), anyInt());
        verify(assembler).appendOwnerLoreIfMissing(any());
        ItemData after = ItemData.of(player.getInventory().getItem(0).getItemMeta());
        assertEquals(99L, after.rollSeed().orElseThrow());
        assertTrue(after.tableGeneration().isEmpty(), "失敗時は世代を焼かず、Ars 復帰後に再試行する");
    }

    @Test
    void ordinaryGearStillGoesThroughAssemble() {
        ItemRefreshListener listener = new ItemRefreshListener(assembler, tableGeneration, stack -> {
            throw new AssertionError("スレッドマーカーが無い品を Ars 経路へ流している");
        });
        PlayerMock player = server.addPlayer();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.editMeta(meta -> {
            ItemData data = ItemData.of(meta);
            data.setRollSeed(8L);
            data.setQuality(2);
        });
        player.getInventory().setItemInMainHand(sword);

        listener.refreshAllOnlinePlayers();

        verify(assembler).assemble(any(), any(), anyLong(), anyInt());
    }
}
