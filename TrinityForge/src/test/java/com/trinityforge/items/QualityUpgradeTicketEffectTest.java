package com.trinityforge.items;

import com.trinityforge.config.domains.QualityConfig;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 品質レベルアップ券({@link QualityUpgradeTicketEffect})の効果本体テスト。
 * rollSeedが維持され品質だけ+1されること／最上位品質での拒否を検証する。
 */
class QualityUpgradeTicketEffectTest {

    private static final long ORIGINAL_ROLL_SEED = 555555L;

    private ItemAssembler assembler;
    private ItemFactory itemFactory;
    private QualityConfig qualityConfig;
    private QualityUpgradeTicketEffect effect;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        assembler = mock(ItemAssembler.class);
        // GrindstonePreserveListenerTestと同じ理由(実ItemAssemblerはMockBukkit未実装APIを踏む)で
        // rollSeed/qualityを刻印するだけの最小再現に差し替える。
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenAnswer(invocation -> {
            ItemMeta meta = invocation.getArgument(0, ItemMeta.class);
            ItemData data = ItemData.of(meta);
            data.setRollSeed(invocation.getArgument(2, Long.class));
            data.setQuality(invocation.getArgument(3, Integer.class));
            return 1;
        });
        itemFactory = new ItemFactory(assembler);
        qualityConfig = mock(QualityConfig.class);
        when(qualityConfig.maxQuality()).thenReturn(15);
        effect = new QualityUpgradeTicketEffect(itemFactory, qualityConfig);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ItemStack stamped(int quality) {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = stack.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setRollSeed(ORIGINAL_ROLL_SEED);
        data.setQuality(quality);
        stack.setItemMeta(meta);
        return stack;
    }

    @Test
    void applyRaisesQualityByOneAndKeepsRollSeed() {
        ItemStack input = stamped(5);

        Optional<ItemStack> result = effect.apply(input.clone());

        assertTrue(result.isPresent());
        ItemData data = ItemData.of(result.get().getItemMeta());
        assertEquals(6, data.quality(), "品質は+1されるはず");
        assertEquals(ORIGINAL_ROLL_SEED, data.rollSeed().orElseThrow(),
                "rollSeedはランダムロール(厳選幅)なので維持されなければならない");
    }

    @Test
    void eligibleIsFalseAtMaxQualityAndApplyReturnsEmpty() {
        ItemStack maxed = stamped(15);

        assertFalse(effect.eligible(maxed), "最上位品質は候補にすら出してはいけない");
        assertTrue(effect.apply(maxed.clone()).isEmpty(),
                "最上位品質での適用は空(=拒否)を返し、券は消費されない");
    }

    @Test
    void eligibleIsFalseWithoutRollSeed() {
        ItemStack plain = new ItemStack(Material.DIAMOND_SWORD);

        assertFalse(effect.eligible(plain), "rollSeedを持たないアイテムは対象外");
        assertTrue(effect.apply(plain.clone()).isEmpty());
    }
}
