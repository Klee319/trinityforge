package com.trinityforge.pdc;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDC round-trip for {@link ItemData}'s {@code catalogId} accessor: {@code ItemFactory.create} stamps
 * the {@code items/catalog.yml} id so {@code ItemAssembler.assemble} can re-resolve flavor lore on
 * every (re-)assembly, even though {@code assemble} only ever sees the bare Material.
 */
class ItemDataTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ItemMeta freshMeta() {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        return stack.getItemMeta();
    }

    @Test
    void catalogIdIsAbsentByDefault() {
        ItemData data = ItemData.of(freshMeta());
        assertTrue(data.catalogId().isEmpty(), "an item with no stamped catalog id has none (back-compat)");
    }

    @Test
    void catalogIdRoundTripsThroughSetAndGet() {
        ItemMeta meta = freshMeta();
        ItemData data = ItemData.of(meta);

        data.setCatalogId("example_sword");

        assertEquals("example_sword", ItemData.of(meta).catalogId().orElseThrow());
    }

    @Test
    void catalogIdSurvivesItemMetaRoundTripViaItemStack() {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId("example_bow");
        stack.setItemMeta(meta);

        ItemData restored = ItemData.of(stack.getItemMeta());
        assertEquals("example_bow", restored.catalogId().orElseThrow());
    }

    // --- craftRollMods (段2 鍛冶ロールパーク): per-crafter stage-2 roll deltas baked at craft time ---

    @Test
    void craftRollModsIsNoneByDefault() {
        ItemData data = ItemData.of(freshMeta());
        assertEquals(com.trinityforge.stats.CraftRollMods.NONE, data.craftRollMods());
    }

    @Test
    void craftRollModsRoundTripsThroughSetAndGet() {
        ItemMeta meta = freshMeta();
        ItemData data = ItemData.of(meta);
        com.trinityforge.stats.CraftRollMods mods = new com.trinityforge.stats.CraftRollMods(0.3, 0.1, -0.05);

        data.setCraftRollMods(mods);

        assertEquals(mods, ItemData.of(meta).craftRollMods());
    }

    @Test
    void craftRollModsSurvivesItemMetaRoundTripViaItemStack() {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = stack.getItemMeta();
        com.trinityforge.stats.CraftRollMods mods = new com.trinityforge.stats.CraftRollMods(0.2, 0.2, 0.05);
        ItemData.of(meta).setCraftRollMods(mods);
        stack.setItemMeta(meta);

        ItemData restored = ItemData.of(stack.getItemMeta());
        assertEquals(mods, restored.craftRollMods());
    }

    @Test
    void settingZeroCraftRollModsWritesNothingAndReadsBackAsNone() {
        ItemMeta meta = freshMeta();
        ItemData data = ItemData.of(meta);

        data.setCraftRollMods(com.trinityforge.stats.CraftRollMods.NONE);

        assertEquals(com.trinityforge.stats.CraftRollMods.NONE, data.craftRollMods());
        assertTrue(meta.getPersistentDataContainer().getKeys().isEmpty(),
                "a zero-mods write must not pollute the item's PDC");
    }

    @Test
    void coatingFlatDamageRoundTrips() {
        ItemMeta meta = freshMeta();
        ItemData data = ItemData.of(meta);
        data.setCoatingStacks(2);
        data.setCoatingFlatDamage(4.5);
        ItemData reread = ItemData.of(meta);
        assertEquals(2, reread.coatingStacks());
        assertEquals(4.5, reread.coatingFlatDamage(), 1e-9);
    }

    @Test
    void zeroCoatingFlatDamageClearsKey() {
        ItemMeta meta = freshMeta();
        ItemData data = ItemData.of(meta);
        data.setCoatingFlatDamage(1.0);
        data.setCoatingFlatDamage(0.0);
        assertEquals(0.0, data.coatingFlatDamage(), 1e-9);
    }
}
