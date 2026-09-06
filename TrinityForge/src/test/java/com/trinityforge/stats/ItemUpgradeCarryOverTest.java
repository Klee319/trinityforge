package com.trinityforge.stats;

import com.trinityforge.pdc.ItemData;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 儀式アップグレードで品質／ロール以外がコアから成果物へ残ること。
 */
class ItemUpgradeCarryOverTest {

    private static final NamespacedKey THREAD_SLOTS = new NamespacedKey("arspaper", "thread_slots");
    private static final NamespacedKey BACKPACK_DATA = new NamespacedKey("arspaper", "backpack_data");
    private static final NamespacedKey CUSTOM_ITEM_ID = new NamespacedKey("arspaper", "custom_item_id");
    private static final NamespacedKey ARMOR_TIER = new NamespacedKey("arspaper", "armor_tier");
    private static final NamespacedKey SPELL_SLOTS = new NamespacedKey("arspaper", "spell_slots");
    private static final NamespacedKey SPELL_BOOK_UUID = new NamespacedKey("arspaper", "spell_book_uuid");

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("スレッド・バックパック・シード・枠拡張は残り、identity と品質ロールは残らない")
    void persistentCopyKeepsSocketsAndSkipsIdentityAndRoll() {
        UUID owner = UUID.randomUUID();
        ItemStack core = new ItemStack(Material.LEATHER_CHESTPLATE);
        core.editMeta(meta -> {
            var pdc = meta.getPersistentDataContainer();
            pdc.set(THREAD_SLOTS, PersistentDataType.STRING, "[\"backpack\"]");
            pdc.set(BACKPACK_DATA, PersistentDataType.STRING, "{\"0\":\"stone\"}");
            pdc.set(CUSTOM_ITEM_ID, PersistentDataType.STRING, "mage_arcane_novice_chestplate");
            pdc.set(ARMOR_TIER, PersistentDataType.INTEGER, 1);
            ItemData data = ItemData.of(meta);
            data.setCatalogId("mage_arcane_novice_chestplate");
            data.setRollSeed(111L);
            data.setQuality(80);
            data.setOwner(owner);
            data.setParticleSeed("seed_flame");
            data.setRitualThreadSlotBonus(2);
        });

        ItemStack result = new ItemStack(Material.LEATHER_CHESTPLATE);
        result.editMeta(meta -> {
            var pdc = meta.getPersistentDataContainer();
            pdc.set(CUSTOM_ITEM_ID, PersistentDataType.STRING, "mage_arcane_apprentice_chestplate");
            pdc.set(ARMOR_TIER, PersistentDataType.INTEGER, 2);
            ItemData data = ItemData.of(meta);
            data.setCatalogId("mage_arcane_apprentice_chestplate");
            data.setQuality(0);
        });

        ItemUpgradeCarryOver.copyPersistent(core, result);

        ItemData copied = ItemData.of(result.getItemMeta());
        var pdc = result.getItemMeta().getPersistentDataContainer();
        assertEquals("[\"backpack\"]", pdc.get(THREAD_SLOTS, PersistentDataType.STRING));
        assertEquals("{\"0\":\"stone\"}", pdc.get(BACKPACK_DATA, PersistentDataType.STRING));
        assertEquals("mage_arcane_apprentice_chestplate",
                pdc.get(CUSTOM_ITEM_ID, PersistentDataType.STRING),
                "新しい成果物の Ars identity は上書きしてはいけない");
        assertEquals(2, pdc.get(ARMOR_TIER, PersistentDataType.INTEGER));
        assertEquals("mage_arcane_apprentice_chestplate", copied.catalogId().orElseThrow());
        assertEquals(0, copied.quality(), "品質は儀式実行者で新規ロールするので写さない");
        assertEquals(false, copied.hasRollSeed(), "rollSeed も写さない");
        assertEquals(owner, copied.owner().orElseThrow());
        assertEquals("seed_flame", copied.particleSeed().orElseThrow());
        assertEquals(2, copied.ritualThreadSlotBonus());
    }

    @Test
    @DisplayName("mage_ 以外（武器）でもエンチャントとスレッドが残る")
    void weaponCoreKeepsEnchantsAndThreads() {
        ItemStack core = new ItemStack(Material.DIAMOND_SWORD);
        core.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        core.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        core.editMeta(meta -> meta.getPersistentDataContainer()
                .set(THREAD_SLOTS, PersistentDataType.STRING, "[\"bleed\"]"));

        ItemStack result = new ItemStack(Material.NETHERITE_SWORD);
        ItemUpgradeCarryOver.copyPersistent(core, result);
        ItemUpgradeCarryOver.copyEnchantments(core, result);

        assertEquals(5, result.getEnchantmentLevel(Enchantment.SHARPNESS));
        assertEquals(3, result.getEnchantmentLevel(Enchantment.UNBREAKING));
        assertEquals("[\"bleed\"]", result.getItemMeta().getPersistentDataContainer()
                .get(THREAD_SLOTS, PersistentDataType.STRING));
    }

    @Test
    @DisplayName("魔導書のスペルスロットと個体 UUID は残る")
    void spellBookKeepsSlotsAndUuid() {
        String uuid = UUID.randomUUID().toString();
        ItemStack core = new ItemStack(Material.BOOK);
        core.editMeta(meta -> {
            var pdc = meta.getPersistentDataContainer();
            pdc.set(SPELL_SLOTS, PersistentDataType.STRING, "[{\"glyph\":\"projectile\"}]");
            pdc.set(SPELL_BOOK_UUID, PersistentDataType.STRING, uuid);
            pdc.set(CUSTOM_ITEM_ID, PersistentDataType.STRING, "spell_book_novice");
        });

        ItemStack result = new ItemStack(Material.BOOK);
        result.editMeta(meta -> meta.getPersistentDataContainer()
                .set(CUSTOM_ITEM_ID, PersistentDataType.STRING, "spell_book_apprentice"));

        ItemUpgradeCarryOver.copyPersistent(core, result);

        var pdc = result.getItemMeta().getPersistentDataContainer();
        assertEquals("[{\"glyph\":\"projectile\"}]", pdc.get(SPELL_SLOTS, PersistentDataType.STRING));
        assertEquals(uuid, pdc.get(SPELL_BOOK_UUID, PersistentDataType.STRING));
        assertEquals("spell_book_apprentice", pdc.get(CUSTOM_ITEM_ID, PersistentDataType.STRING));
    }

    @Test
    @DisplayName("stamp が tool-enchant を剥がしたあとも、コアのエンチャントが戻る")
    void enchantCopyAfterStampRestoresPlayerEnchants() {
        ItemStack core = new ItemStack(Material.DIAMOND_HELMET);
        core.addUnsafeEnchantment(Enchantment.PROTECTION, 4);

        ItemStack result = new ItemStack(Material.LEATHER_HELMET);
        ItemUpgradeCarryOver.copyEnchantments(core, result);

        assertEquals(4, result.getEnchantmentLevel(Enchantment.PROTECTION));
    }

    @Test
    @DisplayName("成果物側の方が高いエンチャントは下げない")
    void enchantCopyKeepsHigherResultLevel() {
        ItemStack core = new ItemStack(Material.DIAMOND_SWORD);
        core.addUnsafeEnchantment(Enchantment.SHARPNESS, 2);
        ItemStack result = new ItemStack(Material.NETHERITE_SWORD);
        result.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);

        ItemUpgradeCarryOver.copyEnchantments(core, result);

        assertEquals(5, result.getEnchantmentLevel(Enchantment.SHARPNESS));
    }

    @Test
    @DisplayName("コアにデータが無ければ成果物を汚さない")
    void emptyCoreDoesNotInventData() {
        ItemStack core = new ItemStack(Material.IRON_HELMET);
        ItemStack result = new ItemStack(Material.LEATHER_HELMET);
        result.editMeta(meta -> ItemData.of(meta).setCatalogId("mage_arcane_novice_helmet"));

        ItemUpgradeCarryOver.copyPersistent(core, result);
        ItemUpgradeCarryOver.copyEnchantments(core, result);

        assertEquals("mage_arcane_novice_helmet",
                ItemData.of(result.getItemMeta()).catalogId().orElseThrow());
        assertNull(result.getItemMeta().getPersistentDataContainer()
                .get(THREAD_SLOTS, PersistentDataType.STRING));
        assertEquals(0, result.getEnchantments().size());
    }

    @Test
    @DisplayName("金床の修繕コストは残る")
    void repairCostIsCopied() {
        ItemStack core = new ItemStack(Material.DIAMOND_CHESTPLATE);
        core.editMeta(meta -> {
            if (meta instanceof Repairable repairable) {
                repairable.setRepairCost(17);
            }
        });
        ItemStack result = new ItemStack(Material.NETHERITE_CHESTPLATE);

        ItemUpgradeCarryOver.copyPersistent(core, result);

        Repairable copied = (Repairable) result.getItemMeta();
        assertEquals(17, copied.getRepairCost());
    }
}
