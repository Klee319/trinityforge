package com.trinityforge.items;

import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemStatProfile;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
        when(assembler.assemble(any(), any(), anyLong(), anyInt(), anyBoolean())).thenAnswer(invocation -> {
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
        data.setQualityScore(37);
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
        assertEquals(37, data.qualityScore().orElseThrow(),
                "品質昇華ではランダムロールのptを変更してはいけない");
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

    @Test
    void eligibleIsFalseForTicketItself() {
        ItemStack crystal = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta meta = crystal.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setRollSeed(ORIGINAL_ROLL_SEED);
        data.setQuality(0);
        data.setCatalogId(QualityUpgradeTicketEffect.CATALOG_ID);
        crystal.setItemMeta(meta);

        assertFalse(effect.eligible(crystal), "品質昇華の結晶は自分自身を対象にしてはいけない");
    }

    @Test
    void eligibleIsFalseWhenQualityDoesNotVary() {
        ItemStatsConfig stats = mock(ItemStatsConfig.class);
        ItemStatProfile fixedOnly = new ItemStatProfile(
                Map.of("attack-damage", 5.0), Map.of(), Map.of());
        when(stats.profileFor(eq(Material.DIAMOND_SWORD), nullable(Integer.class))).thenReturn(Optional.of(fixedOnly));
        QualityUpgradeTicketEffect gated =
                new QualityUpgradeTicketEffect(new ItemFactory(assembler, stats), qualityConfig);

        assertFalse(gated.eligible(stamped(5)), "品質が動かない品は昇華の対象外");
        assertTrue(gated.apply(stamped(5)).isEmpty());
    }

    @Test
    void markedArsThreadNeverFallsThroughToGenericStampWhenArsIsUnavailable() {
        ItemStack thread = stamped(5);
        ItemMeta meta = thread.getItemMeta();
        meta.getPersistentDataContainer().set(
                new NamespacedKey("arspaper", "thread_item_type"),
                PersistentDataType.STRING, "circulation");
        thread.setItemMeta(meta);

        assertTrue(effect.apply(thread).isEmpty(),
                "ArsPaper未ロード時に汎用stampへ流すとスレッドのセット効果loreが消えるため拒否する");
        assertEquals(5, ItemData.of(thread.getItemMeta()).quality(),
                "専用更新に失敗したときは品質PDCも元へ戻す");
        verify(assembler, never()).assemble(any(), any(), anyLong(), anyInt());
        verify(assembler, never()).assemble(any(), any(), anyLong(), anyInt(), anyBoolean());
    }
}
