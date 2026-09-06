package com.trinityforge.stats;

import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.config.domains.ItemStatsConfig;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ItemFactory#create} must stamp the catalog id onto every catalog-built item BEFORE calling
 * {@link ItemAssembler#assemble}, so {@code assemble} can resolve the template's flavor lore both on
 * this build and on every future re-assembly ({@code ItemRefreshListener} only has the item's own PDC,
 * never the original template). {@link ItemFactory#stamp} is a non-catalog path (craft/fishing) and
 * must NOT stamp a catalog id.
 *
 * <p>{@link ItemAssembler} is mocked out here rather than built for real: {@code ItemFactory}'s own
 * contract under test (catalog-id stamping order, and only for the catalog path) is independent of
 * what {@code assemble} actually does with the config/AttributeApplier chain — that behaviour belongs
 * to {@code ItemAssemblerTest}. Using a mock also sidesteps a real {@code AttributeApplier} hitting
 * MockBukkit 4.110.0's unimplemented {@code Material#getDefaultAttributeModifiers}, which is unrelated
 * to catalog-id stamping.
 */
class ItemFactoryTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ItemFactory factoryWithMockAssembler() {
        ItemAssembler assembler = mock(ItemAssembler.class);
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenReturn(0);
        return new ItemFactory(assembler);
    }

    @Test
    void createStampsTheTemplatesOwnCatalogIdBeforeDelegatingToAssemble() {
        ItemAssembler assembler = mock(ItemAssembler.class);
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenAnswer(invocation -> {
            var meta = invocation.getArgument(0, org.bukkit.inventory.meta.ItemMeta.class);
            // At the moment assemble() is invoked, the catalog id must already be on the meta's PDC.
            assertEquals("flavor_sword", ItemData.of(meta).catalogId().orElseThrow());
            return 0;
        });
        ItemTemplate template = new ItemTemplate("flavor_sword", Material.DIAMOND_SWORD, null, null,
                BindType.TRADEABLE, 0, null, List.of("<gray>flavor</gray>"));

        ItemStack stack = new ItemFactory(assembler).create(template, 1L, 0);

        verify(assembler).assemble(any(), any(), anyLong(), anyInt());
        ItemData data = ItemData.of(stack.getItemMeta());
        assertEquals("flavor_sword", data.catalogId().orElseThrow());
    }

    @Test
    void createIdentityOnlyStampsIdentityButNoRollSeed() {
        ItemTemplate template = new ItemTemplate("catalyst_x", Material.BLAZE_ROD,
                "<gold>Catalyst</gold>", 100012, BindType.SOULBOUND, 0, null, List.of());

        ItemStack stack = factoryWithMockAssembler().createIdentityOnly(template);

        ItemData data = ItemData.of(stack.getItemMeta());
        assertEquals("catalyst_x", data.catalogId().orElseThrow(),
                "identity (catalog id) must still be stamped");
        assertTrue(data.rollSeed().isEmpty(),
                "createIdentityOnly must leave the item WITHOUT a rollSeed so a downstream craft-quality"
                        + " stamp (CraftQualityListener's hasRollSeed() guard) still applies a per-crafter quality");
    }

    @Test
    void createStackableLeavesRollMetadataOutAndKeepsFlavorLore() {
        ItemTemplate template = new ItemTemplate("key_x", Material.TRIAL_KEY,
                "<gold>Key</gold>", 5501, BindType.TRADEABLE, 0, null,
                List.of("", "<gray>fixed lore</gray>"));

        ItemFactory factory = factoryWithMockAssembler();
        ItemStack first = factory.createStackable(template);
        ItemStack second = factory.createStackable(template);
        ItemStack recipeResult = factory.createIdentityOnly(template);

        ItemData data = ItemData.of(first.getItemMeta());
        assertTrue(data.rollSeed().isEmpty(), "fixed rewards must not carry per-draw roll identity");
        assertTrue(first.isSimilar(second), "same fixed catalog reward must be stack-compatible");
        assertTrue(first.isSimilar(recipeResult), "gacha and recipe rewards of one key must stack together");
        assertEquals("fixed lore", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(first.getItemMeta().lore().get(1)));
    }

    @Test
    void qualityVariesRecognizesBareMaterialDropWithoutItemMeta() {
        ItemStatsConfig stats = mock(ItemStatsConfig.class);
        ItemStatProfile profile = new ItemStatProfile(
                Map.of("attack-damage", 5.0), Map.of("attack-damage", 0.5), Map.of());
        when(stats.profileFor(Material.DIAMOND_SWORD, null)).thenReturn(Optional.of(profile));

        ItemFactory factory = new ItemFactory(mock(ItemAssembler.class), stats);

        assertTrue(factory.qualityVaries(new ItemStack(Material.DIAMOND_SWORD)),
                "死亡時に生成された未刻印の素 ItemStack も品質プロファイルを参照できること");
    }

    @Test
    void stampDoesNotSetCatalogId() {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);

        factoryWithMockAssembler().stamp(stack, 1L, 0);

        ItemData data = ItemData.of(stack.getItemMeta());
        assertTrue(data.catalogId().isEmpty(),
                "the craft/fishing stamp path is not catalog-sourced and must not get a catalog id");
    }

    // --- catalog `color:` / `enchant-glow:` application (item A) ---

    @Test
    void createAppliesLeatherColorFromTemplate() {
        ItemTemplate template = new ItemTemplate("vest", Material.LEATHER_CHESTPLATE, null, null,
                BindType.TRADEABLE, 0, null, List.of(), (RecipeSpec) null, "#8B0000", false);

        ItemStack stack = factoryWithMockAssembler().create(template, 1L, 0);

        var meta = stack.getItemMeta();
        assertTrue(meta instanceof org.bukkit.inventory.meta.LeatherArmorMeta);
        org.bukkit.inventory.meta.LeatherArmorMeta leatherMeta =
                (org.bukkit.inventory.meta.LeatherArmorMeta) meta;
        assertEquals(org.bukkit.Color.fromRGB(0x8B0000), leatherMeta.getColor());
    }

    @Test
    void createIgnoresColorOnNonLeatherMaterial() {
        // ItemCatalogConfig already fail-softs an invalid color to null before construction, but
        // ItemFactory itself must also tolerate a non-leather material safely if ever handed one directly.
        ItemTemplate template = new ItemTemplate("blade", Material.DIAMOND_SWORD, null, null,
                BindType.TRADEABLE, 0, null, List.of(), (RecipeSpec) null, null, false);

        ItemStack stack = factoryWithMockAssembler().create(template, 1L, 0);

        assertFalse(stack.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta);
    }

    @Test
    void createAppliesEnchantGlowWithoutHidingTooltip() {
        ItemTemplate template = new ItemTemplate("glowing_stick", Material.STICK, null, null,
                BindType.TRADEABLE, 0, null, List.of(), (RecipeSpec) null, null, true);

        ItemStack stack = factoryWithMockAssembler().create(template, 1L, 0);

        ItemMeta meta = stack.getItemMeta();
        assertTrue(hasGlowPresentation(meta),
                "enchant-glow must show the vanilla shimmer");
        assertFalse(glintHidesEnchants(meta),
                "glint-override glow must not set HIDE_ENCHANTS (that hides real enchants later)");
    }

    @Test
    void createWithoutEnchantGlowAddsNoEnchant() {
        ItemTemplate template = new ItemTemplate("plain_stick", Material.STICK, null, null,
                BindType.TRADEABLE, 0, null, List.of(), (RecipeSpec) null, null, false);

        ItemStack stack = factoryWithMockAssembler().create(template, 1L, 0);

        assertFalse(stack.getItemMeta().hasEnchants());
        assertFalse(stack.getItemMeta().hasItemFlag(ItemFlag.HIDE_ENCHANTS));
    }

    @Test
    void clearEnchantGlowRemovesHideEnchantsLeftoverFromLegacyDummy() {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = stack.getItemMeta();
        meta.addEnchant(Enchantment.SHARPNESS, 2, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        ItemFactory.clearEnchantGlow(meta);

        assertFalse(meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS),
                "catalog glow off must drop leftover HIDE_ENCHANTS so new enchants can show");
        assertEquals(2, meta.getEnchantLevel(Enchantment.SHARPNESS));
    }

    @Test
    void applyEnchantGlowOnLegacyDummyDoesNotHideExistingEnchants() {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = stack.getItemMeta();
        meta.addEnchant(Enchantment.SHARPNESS, 2, true);
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        ItemFactory.applyEnchantGlow(meta);

        assertFalse(meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS));
        assertEquals(2, meta.getEnchantLevel(Enchantment.SHARPNESS));
        assertEquals(0, meta.getEnchantLevel(Enchantment.UNBREAKING),
                "dummy Unbreaking I stamped with HIDE_ENCHANTS is the old glow, not a player enchant");
        assertTrue(hasGlowPresentation(meta));
    }

    /** Paper glint override, or MockBukkit fallback of dummy Unbreaking + HIDE_ENCHANTS. */
    private static boolean hasGlowPresentation(ItemMeta meta) {
        try {
            if (Boolean.TRUE.equals(meta.getEnchantmentGlintOverride())) {
                return true;
            }
        } catch (Throwable ignored) {
            // MockBukkit unimplemented: fall through to dummy-enchant signature.
        }
        return meta.hasEnchants() && meta.getItemFlags().contains(ItemFlag.HIDE_ENCHANTS);
    }

    private static boolean glintHidesEnchants(ItemMeta meta) {
        try {
            return Boolean.TRUE.equals(meta.getEnchantmentGlintOverride())
                    && meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // --- 「スレッド枠拡張」儀式 (F2 2026-07-31) ---

    private ItemFactory factoryWithThreadSlotCaps() {
        ItemAssembler assembler = mock(ItemAssembler.class);
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenReturn(0);
        // craftingFeatures は yml 未ロード = 既定の上限マップ(全カテゴリ 5)。
        return new ItemFactory(assembler,
                new com.trinityforge.config.domains.ItemStatsConfig(),
                new com.trinityforge.config.domains.CraftingFeaturesConfig());
    }

    @Test
    void expandRitualThreadSlotSucceedsOnWeaponsAndCatalysts() {
        // F2: 出荷 yml が weapon/tool/other の上限を 5 にしているので、儀式は武器・触媒でも
        // 成立しなければならない(成立するのに効果が無い、が今回の不具合の裏返し)。
        ItemFactory factory = factoryWithThreadSlotCaps();

        for (Material material : List.of(
                Material.NETHERITE_SWORD, Material.BLAZE_ROD, Material.NETHERITE_PICKAXE,
                Material.DIAMOND_CHESTPLATE)) {
            ItemStack stack = new ItemStack(material);
            ItemStack expanded = factory.expandRitualThreadSlot(stack, 3).orElseThrow(
                    () -> new AssertionError(material + " のスレッド枠拡張儀式が失敗した"
                            + "(出荷ymlの上限は全カテゴリ 5 なので成立するべき)"));
            assertEquals(1, ItemData.of(expanded.getItemMeta()).ritualThreadSlotBonus(),
                    material + " の累計付与カウンタが +1 されていない");
        }
    }

    @Test
    void expandRitualThreadSlotStopsAtTheRitualsOwnCumulativeMax() {
        ItemFactory factory = factoryWithThreadSlotCaps();
        ItemStack stack = new ItemStack(Material.NETHERITE_SWORD);

        ItemStack once = factory.expandRitualThreadSlot(stack, 1).orElseThrow();
        assertEquals(1, ItemData.of(once.getItemMeta()).ritualThreadSlotBonus());
        // max-slots=1 に到達済み → 素材を消費させないため empty を返す。
        assertTrue(factory.expandRitualThreadSlot(once, 1).isEmpty(),
                "累計上限に到達したら empty(素材消費なし)で返すこと");
    }
}
