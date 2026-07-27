package com.trinityforge.mobs;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.ItemFactory;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link GateKeyMatcher} (2026-07-27 ダンジョンゲートのカスタムアイテム鍵対応): 所持判定・計数・消費の
 * 一致条件、特に「同じMaterialのカスタム品をバニラ鍵の判定に巻き込まない」ケースを検証する。
 */
class GateKeyMatcherTest {

    private ServerMock server;
    private ItemCatalogConfig catalog;
    private ItemFactory itemFactory;
    private GateKeyMatcher matcher;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        catalog = mock(ItemCatalogConfig.class);
        itemFactory = mock(ItemFactory.class);
        when(catalog.template(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        CrossPluginItemResolver resolver = new CrossPluginItemResolver(catalog, itemFactory);
        matcher = new GateKeyMatcher(resolver);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ItemStack stampedCatalogItem(Material material, String catalogId) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId(catalogId);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack stampedArsItem(Material material, String id) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("arspaper", "custom_item_id"), PersistentDataType.STRING, id);
        stack.setItemMeta(meta);
        return stack;
    }

    @Test
    void vanillaMaterialKeyMatchesPlainVanillaStack() {
        ItemStack stack = new ItemStack(Material.AMETHYST_SHARD, 3);
        assertTrue(matcher.matches(stack, "AMETHYST_SHARD"));
    }

    @Test
    void vanillaMaterialKeyDoesNotMatchCustomStampedStackOfSameMaterial() {
        // 鍵がバニラMaterial(AMETHYST_SHARD)のとき、同じMaterialだがPDCでカスタムIDが焼かれている
        // スタック(=カスタム品)は鍵として一致してはいけない。
        ItemStack customStack = stampedCatalogItem(Material.AMETHYST_SHARD, "tf_special_shard");
        assertFalse(matcher.matches(customStack, "AMETHYST_SHARD"));
    }

    @Test
    void catalogKeyMatchesStampedStackWithSameId() {
        ItemStack stack = stampedCatalogItem(Material.LEATHER, "tf_crypt_sigil");
        assertTrue(matcher.matches(stack, "tf_crypt_sigil"));
    }

    @Test
    void catalogKeyDoesNotMatchDifferentCatalogId() {
        ItemStack stack = stampedCatalogItem(Material.LEATHER, "tf_other_item");
        assertFalse(matcher.matches(stack, "tf_crypt_sigil"));
    }

    @Test
    void catalogKeyDoesNotMatchPlainVanillaStack() {
        ItemStack stack = new ItemStack(Material.LEATHER);
        assertFalse(matcher.matches(stack, "tf_crypt_sigil"));
    }

    @Test
    void arsStampedItemMatchesByArsId() {
        ItemStack stack = stampedArsItem(Material.PAPER, "tf_scrap");
        assertTrue(matcher.matches(stack, "tf_scrap"));
    }

    @Test
    void countSumsAcrossMatchingSlotsOnly() {
        PlayerMock player = server.addPlayer();
        Inventory inv = player.getInventory();
        ItemStack sigil = stampedCatalogItem(Material.LEATHER, "tf_crypt_sigil");
        sigil.setAmount(2);
        inv.setItem(0, sigil);
        inv.setItem(1, stampedCatalogItem(Material.LEATHER, "tf_other_item"));
        inv.setItem(2, new ItemStack(Material.LEATHER, 5)); // plain vanilla, must not count

        assertEquals(2, matcher.count(inv, "tf_crypt_sigil"));
    }

    @Test
    void consumeRemovesExactAmountAcrossSlotsAndLeavesRemainderStack() {
        PlayerMock player = server.addPlayer();
        Inventory inv = player.getInventory();
        ItemStack slotA = stampedCatalogItem(Material.LEATHER, "tf_crypt_sigil");
        slotA.setAmount(1);
        ItemStack slotB = stampedCatalogItem(Material.LEATHER, "tf_crypt_sigil");
        slotB.setAmount(3);
        inv.setItem(0, slotA);
        inv.setItem(1, slotB);

        matcher.consume(inv, "tf_crypt_sigil", 2);

        assertEquals(2, matcher.count(inv, "tf_crypt_sigil"));
        // slot 0 (amount 1) fully consumed, slot 1 partially consumed down to 2.
        assertNull(inv.getItem(0));
        assertEquals(2, inv.getItem(1).getAmount());
    }

    @Test
    void consumeDoesNotTouchNonMatchingStacksOfSameMaterial() {
        PlayerMock player = server.addPlayer();
        Inventory inv = player.getInventory();
        inv.setItem(0, new ItemStack(Material.AMETHYST_SHARD, 5)); // vanilla key material stack
        ItemStack customShard = stampedCatalogItem(Material.AMETHYST_SHARD, "tf_special_shard");
        customShard.setAmount(5);
        inv.setItem(1, customShard);

        matcher.consume(inv, "AMETHYST_SHARD", 3);

        assertEquals(2, inv.getItem(0).getAmount());
        assertEquals(5, inv.getItem(1).getAmount()); // untouched — different (custom) identity
    }
}
