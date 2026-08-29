package com.trinityforge.items;

import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.stats.CraftQualityService;
import com.trinityforge.stats.CraftRollMods;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemStatProfile;
import com.trinityforge.stats.StatRange;
import org.bukkit.Material;
import org.bukkit.entity.Player;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
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

    @Test
    void applyWithPlayerBakesCraftRollMods() {
        CraftQualityService quality = mock(CraftQualityService.class);
        CraftRollMods mods = new CraftRollMods(0.10, 0.20, 0.05);
        Player player = MockBukkit.getMock().addPlayer();
        when(quality.craftRollMods(player)).thenReturn(mods);
        StatRerollTicketEffect withLuck = new StatRerollTicketEffect(itemFactory, () -> quality);

        Optional<ItemStack> result = withLuck.apply(stamped(), player);

        assertTrue(result.isPresent());
        ItemData data = ItemData.of(result.get().getItemMeta());
        assertEquals(mods, data.craftRollMods(), "厳選の護符はロール運・ロール効率を焼かなければならない");
        assertEquals(ORIGINAL_QUALITY, data.quality(), "品質は維持されなければならない");
    }

    @Test
    void applyWithoutPlayerKeepsCraftRollModsNone() {
        Optional<ItemStack> result = effect.apply(stamped());

        assertTrue(result.isPresent());
        assertEquals(CraftRollMods.NONE, ItemData.of(result.get().getItemMeta()).craftRollMods());
    }

    @Test
    void applyClearsStaleCraftRollModsWhenPlayerHasNone() {
        ItemStack input = stamped();
        ItemMeta meta = input.getItemMeta();
        ItemData.of(meta).setCraftRollMods(new CraftRollMods(0.50, 0.40, 0.10));
        input.setItemMeta(meta);

        Optional<ItemStack> result = effect.apply(input.clone());

        assertTrue(result.isPresent());
        ItemMeta out = result.get().getItemMeta();
        assertEquals(CraftRollMods.NONE, ItemData.of(out).craftRollMods());
        assertFalse(out.getPersistentDataContainer().has(PdcKeys.ITEM_CRAFT_ROLL_UP, PersistentDataType.DOUBLE),
                "古いロール運 PDC が残ってはならない");
        assertFalse(out.getPersistentDataContainer().has(
                PdcKeys.ITEM_CRAFT_ROLL_DOWN_REDUCTION, PersistentDataType.DOUBLE));
        assertFalse(out.getPersistentDataContainer().has(
                PdcKeys.ITEM_CRAFT_ROLL_INSET_DELTA, PersistentDataType.DOUBLE));
    }

    @Test
    void previewLoreSaysRollModsComeFromThePerformer() {
        String joined = effect.previewLore(stamped()).stream()
                .map(component -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(component))
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(joined.contains("実行者の今の値"),
                "プレビューは装備PDCではなく実行者のロール運だと書かなければならない: " + joined);
    }

    @Test
    void eligibleIsFalseForTheTicketItselfEvenWithRollSeed() {
        ItemStack charm = stampedCharm();

        assertFalse(effect.eligible(charm), "厳選の護符は自分自身を対象にしてはいけない");
        assertTrue(effect.apply(charm.clone()).isEmpty());
    }

    @Test
    void eligibleIsFalseWhenCatalogItemHasNoRandomLayer() {
        ItemStatsConfig stats = mock(ItemStatsConfig.class);
        when(stats.profileFor(any(), nullable(Integer.class))).thenReturn(Optional.empty());
        StatRerollTicketEffect gated = new StatRerollTicketEffect(new ItemFactory(assembler, stats));

        ItemStack material = stamped();
        ItemMeta meta = material.getItemMeta();
        ItemData.of(meta).setCatalogId("endermite_soot");
        material.setItemMeta(meta);

        assertFalse(gated.eligible(material), "item-stats が無いカタログ品は厳選ロールが無い");
        assertTrue(gated.apply(material.clone()).isEmpty());
    }

    @Test
    void eligibleIsFalseForFixedOnlyProfile() {
        ItemStatsConfig stats = mock(ItemStatsConfig.class);
        ItemStatProfile fixedOnly = new ItemStatProfile(
                Map.of("attack-damage", 5.0), Map.of(), Map.of());
        when(stats.profileFor(eq(Material.BLAZE_ROD), nullable(Integer.class))).thenReturn(Optional.of(fixedOnly));
        StatRerollTicketEffect gated = new StatRerollTicketEffect(new ItemFactory(assembler, stats));

        ItemStack catalyst = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = catalyst.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setRollSeed(ORIGINAL_ROLL_SEED);
        data.setQuality(0);
        catalyst.setItemMeta(meta);

        assertFalse(gated.eligible(catalyst), "fixed のみの素材は rollSeed を引き直す意味が無い");
    }

    @Test
    void eligibleIsTrueWhenRandomLayerExists() {
        ItemStatsConfig stats = mock(ItemStatsConfig.class);
        ItemStatProfile random = new ItemStatProfile(
                Map.of("attack-damage", 5.0), Map.of(),
                Map.of("attack-damage", new StatRange(1.0, 3.0)));
        when(stats.profileFor(eq(Material.DIAMOND_SWORD), nullable(Integer.class))).thenReturn(Optional.of(random));
        StatRerollTicketEffect gated = new StatRerollTicketEffect(new ItemFactory(assembler, stats));

        assertTrue(gated.eligible(stamped()));
    }

    private ItemStack stampedCharm() {
        ItemStack stack = new ItemStack(Material.RABBIT_FOOT);
        ItemMeta meta = stack.getItemMeta();
        ItemData data = ItemData.of(meta);
        data.setRollSeed(ORIGINAL_ROLL_SEED);
        data.setQuality(0);
        data.setCatalogId(StatRerollTicketEffect.CATALOG_ID);
        stack.setItemMeta(meta);
        return stack;
    }
}
