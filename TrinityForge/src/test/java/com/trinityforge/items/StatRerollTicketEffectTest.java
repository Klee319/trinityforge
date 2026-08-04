package com.trinityforge.items;

import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ランダムステータス再抽選券({@link StatRerollTicketEffect})の効果本体テスト。
 * 品質が維持されること／rollSeedが変わること／rollSeedを持たない品では拒否されることを検証する。
 */
class StatRerollTicketEffectTest {

    private static final long ORIGINAL_ROLL_SEED = 123123123L;
    private static final int ORIGINAL_QUALITY = 8;

    private ItemAssembler assembler;
    private ItemFactory itemFactory;
    private StatRerollTicketEffect effect;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        assembler = mock(ItemAssembler.class);
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenAnswer(invocation -> {
            ItemMeta meta = invocation.getArgument(0, ItemMeta.class);
            ItemData data = ItemData.of(meta);
            data.setRollSeed(invocation.getArgument(2, Long.class));
            data.setQuality(invocation.getArgument(3, Integer.class));
            return 1;
        });
        itemFactory = new ItemFactory(assembler);
        effect = new StatRerollTicketEffect(itemFactory);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ItemStack stamped() {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = stack.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setRollSeed(ORIGINAL_ROLL_SEED);
        data.setQuality(ORIGINAL_QUALITY);
        stack.setItemMeta(meta);
        return stack;
    }

    @Test
    void applyChangesRollSeedButKeepsQuality() {
        ItemStack input = stamped();

        Optional<ItemStack> result = effect.apply(input.clone());

        assertTrue(result.isPresent());
        ItemData data = ItemData.of(result.get().getItemMeta());
        assertEquals(ORIGINAL_QUALITY, data.quality(), "品質は維持されなければならない");
        assertNotEquals(ORIGINAL_ROLL_SEED, data.rollSeed().orElseThrow(),
                "rollSeedは引き直されなければならない");
    }

    @Test
    void eligibleIsFalseWithoutRollSeedAndApplyReturnsEmpty() {
        ItemStack plain = new ItemStack(Material.DIAMOND_SWORD);

        assertFalse(effect.eligible(plain), "厳選ロールを持たない品は対象外");
        assertTrue(effect.apply(plain.clone()).isEmpty(), "対象外の適用は空(=拒否)を返す");
    }

    @Test
    void eligibleIsTrueForStampedEquipment() {
        assertTrue(effect.eligible(stamped()));
    }
}
