package com.trinityforge.items;

import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 魂縛解きの符: owner を消し bind を TRADEABLE にする。SOULBOUND のまま owner だけ消すと
 * 次の拾得で焼き直されるので、修正前の {@code /tf bind clear} 相当ではこのテストは落ちる。
 */
class OwnerUnbindTicketEffectTest {

    private static final long ORIGINAL_ROLL_SEED = 777777L;
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private ItemFactory itemFactory;
    private OwnerUnbindTicketEffect effect;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        ItemAssembler assembler = mock(ItemAssembler.class);
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenAnswer(invocation -> {
            ItemMeta meta = invocation.getArgument(0, ItemMeta.class);
            ItemData data = ItemData.of(meta);
            data.setRollSeed(invocation.getArgument(2, Long.class));
            data.setQuality(invocation.getArgument(3, Integer.class));
            return 1;
        });
        itemFactory = new ItemFactory(assembler);
        effect = new OwnerUnbindTicketEffect(itemFactory);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ItemStack soulboundOwned() {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = stack.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setRollSeed(ORIGINAL_ROLL_SEED);
        data.setQuality(7);
        data.setCatalogId("guard_blade");
        data.setBindType(BindType.SOULBOUND);
        data.setOwner(OWNER);
        stack.setItemMeta(meta);
        return stack;
    }

    @Test
    void applyClearsOwnerAndForcesTradeable() {
        Optional<ItemStack> result = effect.apply(soulboundOwned());

        assertTrue(result.isPresent());
        ItemData data = ItemData.of(result.get().getItemMeta());
        assertTrue(data.owner().isEmpty(), "所有者が残っていると次の拾得で焼き直される");
        assertEquals(BindType.TRADEABLE, data.bindType().orElseThrow(),
                "SOULBOUND のままだと PickupQualityListener が owner を焼き直す");
        assertEquals(ORIGINAL_ROLL_SEED, data.rollSeed().orElseThrow(), "rollSeed は触らない");
        assertEquals(7, data.quality(), "品質は触らない");
    }

    @Test
    void eligibleWhenSoulboundEvenWithoutOwnerYet() {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = stack.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setRollSeed(ORIGINAL_ROLL_SEED);
        data.setBindType(BindType.SOULBOUND);
        stack.setItemMeta(meta);

        assertTrue(effect.eligible(stack), "未拾得の魂縛も永続解除の対象");
    }

    @Test
    void eligibleIsFalseForTradeableWithoutOwner() {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = stack.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setRollSeed(ORIGINAL_ROLL_SEED);
        data.setBindType(BindType.TRADEABLE);
        stack.setItemMeta(meta);

        assertFalse(effect.eligible(stack));
        assertTrue(effect.apply(stack.clone()).isEmpty());
    }

    @Test
    void eligibleIsFalseForTicketItself() {
        ItemStack ticket = new ItemStack(Material.PAPER);
        ItemMeta meta = ticket.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setRollSeed(ORIGINAL_ROLL_SEED);
        data.setCatalogId(OwnerUnbindTicketEffect.CATALOG_ID);
        data.setBindType(BindType.SOULBOUND);
        data.setOwner(OWNER);
        ticket.setItemMeta(meta);

        assertFalse(effect.eligible(ticket), "符自身を対象にしてはいけない");
    }

    @Test
    void arsThreadDoesNotGoThroughItemFactoryStamp() {
        ItemStack thread = new ItemStack(Material.STRING);
        ItemMeta meta = thread.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setRollSeed(ORIGINAL_ROLL_SEED);
        data.setQuality(3);
        data.setCatalogId("thread_bastion");
        data.setBindType(BindType.SOULBOUND);
        data.setOwner(OWNER);
        meta.getPersistentDataContainer().set(
                new NamespacedKey("arspaper", "thread_item_type"),
                PersistentDataType.STRING,
                "bastion");
        thread.setItemMeta(meta);

        Optional<ItemStack> result = effect.apply(thread);

        assertTrue(result.isPresent());
        ItemMeta after = result.get().getItemMeta();
        assertEquals("bastion", after.getPersistentDataContainer().get(
                new NamespacedKey("arspaper", "thread_item_type"), PersistentDataType.STRING),
                "stamp するとスレッド lore が消えるのでマーカーは残す");
        ItemData unbound = ItemData.of(after);
        assertTrue(unbound.owner().isEmpty());
        assertEquals(BindType.TRADEABLE, unbound.bindType().orElseThrow());
    }
}
