package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.PdcKeys;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VanillaItemRemoverTest {

    private static final Logger LOG = Logger.getLogger("test");

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // --- parse() ---

    @Test
    void parsesBareMaterialEntry() {
        Set<VanillaItemRemover.ItemMatcher> matchers =
                VanillaItemRemover.parse(List.of("IRON_PICKAXE"), LOG);
        assertEquals(1, matchers.size());
        VanillaItemRemover.ItemMatcher matcher = matchers.iterator().next();
        assertEquals(Material.IRON_PICKAXE, matcher.material());
        assertEquals(null, matcher.enchant());
    }

    @Test
    void parsesMaterialWithEnchantScope() {
        Set<VanillaItemRemover.ItemMatcher> matchers =
                VanillaItemRemover.parse(List.of("enchanted_book:mending"), LOG);
        assertEquals(1, matchers.size());
        VanillaItemRemover.ItemMatcher matcher = matchers.iterator().next();
        assertEquals(Material.ENCHANTED_BOOK, matcher.material());
        assertEquals(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("mending")), matcher.enchant());
    }

    @Test
    void unknownMaterialIsSkippedWithoutThrowing() {
        Set<VanillaItemRemover.ItemMatcher> matchers =
                VanillaItemRemover.parse(List.of("NOT_A_REAL_MATERIAL"), LOG);
        assertTrue(matchers.isEmpty());
    }

    @Test
    void unknownEnchantIsSkippedWithoutThrowing() {
        Set<VanillaItemRemover.ItemMatcher> matchers =
                VanillaItemRemover.parse(List.of("enchanted_book:not_a_real_enchant"), LOG);
        assertTrue(matchers.isEmpty());
    }

    @Test
    void blankAndNullEntriesAreIgnored() {
        Set<VanillaItemRemover.ItemMatcher> matchers =
                VanillaItemRemover.parse(java.util.Arrays.asList("", "  ", null), LOG);
        assertTrue(matchers.isEmpty());
    }

    @Test
    void nullListYieldsEmptySet() {
        assertTrue(VanillaItemRemover.parse(null, LOG).isEmpty());
    }

    // --- shouldRemove() ---

    @Test
    void materialOnlyTargetMatchesAnyEnchantState() {
        VanillaItemRemover remover = new VanillaItemRemover();
        remover.updateTargets(List.of("IRON_PICKAXE"), LOG);

        assertTrue(remover.shouldRemove(new ItemStack(Material.IRON_PICKAXE)));
        assertFalse(remover.shouldRemove(new ItemStack(Material.DIAMOND_PICKAXE)));
    }

    @Test
    void enchantScopedTargetOnlyMatchesBooksWithThatEnchant() {
        VanillaItemRemover remover = new VanillaItemRemover();
        remover.updateTargets(List.of("enchanted_book:mending"), LOG);

        ItemStack mendingBook = enchantedBook(Enchantment.MENDING, 1);
        ItemStack unbreakingBook = enchantedBook(Enchantment.UNBREAKING, 3);
        ItemStack plainBook = new ItemStack(Material.ENCHANTED_BOOK);

        assertTrue(remover.shouldRemove(mendingBook));
        assertFalse(remover.shouldRemove(unbreakingBook));
        assertFalse(remover.shouldRemove(plainBook));
    }

    @Test
    void enchantScopedTargetMatchesDirectlyEnchantedItemsToo() {
        VanillaItemRemover remover = new VanillaItemRemover();
        remover.updateTargets(List.of("diamond_sword:sharpness"), LOG);

        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.addEnchant(Enchantment.SHARPNESS, 5, true);
        sword.setItemMeta(meta);

        assertTrue(remover.shouldRemove(sword));
        assertFalse(remover.shouldRemove(new ItemStack(Material.DIAMOND_SWORD)));
    }

    @Test
    void nullOrAirStackIsNeverRemoved() {
        VanillaItemRemover remover = new VanillaItemRemover();
        remover.updateTargets(List.of("IRON_PICKAXE"), LOG);

        assertFalse(remover.shouldRemove(null));
        assertFalse(remover.shouldRemove(new ItemStack(Material.AIR)));
    }

    @Test
    void emptyTargetsNeverRemoveAnything() {
        VanillaItemRemover remover = new VanillaItemRemover();
        assertFalse(remover.hasTargets());
        assertFalse(remover.shouldRemove(new ItemStack(Material.IRON_PICKAXE)));
    }

    @Test
    void tfCatalogItemsAreNeverRemovedEvenIfMaterialMatches() {
        VanillaItemRemover remover = new VanillaItemRemover();
        remover.updateTargets(List.of("IRON_PICKAXE"), LOG);

        ItemStack stack = new ItemStack(Material.IRON_PICKAXE);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId("tf_custom_pick");
        stack.setItemMeta(meta);

        assertFalse(remover.shouldRemove(stack));
    }

    @Test
    void rollSeedStampedItemsAreNeverRemovedEvenIfMaterialMatches() {
        VanillaItemRemover remover = new VanillaItemRemover();
        remover.updateTargets(List.of("IRON_PICKAXE"), LOG);

        ItemStack stack = new ItemStack(Material.IRON_PICKAXE);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setRollSeed(42L);
        stack.setItemMeta(meta);

        assertFalse(remover.shouldRemove(stack));
    }

    @Test
    void updateTargetsReplacesPreviousSet() {
        VanillaItemRemover remover = new VanillaItemRemover();
        remover.updateTargets(List.of("IRON_PICKAXE"), LOG);
        assertTrue(remover.shouldRemove(new ItemStack(Material.IRON_PICKAXE)));

        remover.updateTargets(List.of("DIAMOND_PICKAXE"), LOG);
        assertFalse(remover.shouldRemove(new ItemStack(Material.IRON_PICKAXE)));
        assertTrue(remover.shouldRemove(new ItemStack(Material.DIAMOND_PICKAXE)));
    }

    // --- 修正1: TF品判定の強化 ---

    @Test
    void itemWithAnyTrinityforgePdcKeyIsNeverRemovedEvenWithoutCatalogIdOrRollSeed() {
        VanillaItemRemover remover = new VanillaItemRemover();
        remover.updateTargets(List.of("EXPERIENCE_BOTTLE"), LOG);

        // XP瓶: material は素の EXPERIENCE_BOTTLE のまま、ITEM_XP_BOTTLE_AMOUNT のみ付与される
        // (XpBottleListener.stampFilledBottle と同じ状態)。catalogId/rollSeed は無い。
        ItemStack stack = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(PdcKeys.ITEM_XP_BOTTLE_AMOUNT, PersistentDataType.INTEGER, 100);
        stack.setItemMeta(meta);

        assertFalse(remover.shouldRemove(stack));
    }

    @Test
    void unstampedCmdItemMatchingCatalogTemplateIsNeverRemoved() {
        ItemTemplate template = new ItemTemplate("tf_custom_pick", Material.IRON_PICKAXE,
                null, 9001, BindType.TRADEABLE, 0, null);
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.all()).thenReturn(Map.of(template.id(), template));

        VanillaItemRemover remover = new VanillaItemRemover(catalog);
        remover.updateTargets(List.of("IRON_PICKAXE"), LOG);

        // 未刻印(catalog_id も roll_seed も無し)だが material+CMD がカタログテンプレートと一致する。
        ItemStack stack = new ItemStack(Material.IRON_PICKAXE);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(9001);
        stack.setItemMeta(meta);

        assertFalse(remover.shouldRemove(stack));
    }

    @Test
    void unstampedCmdItemNotMatchingAnyTemplateIsStillRemoved() {
        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.all()).thenReturn(Map.of());

        VanillaItemRemover remover = new VanillaItemRemover(catalog);
        remover.updateTargets(List.of("IRON_PICKAXE"), LOG);

        ItemStack stack = new ItemStack(Material.IRON_PICKAXE);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(1234);
        stack.setItemMeta(meta);

        assertTrue(remover.shouldRemove(stack));
    }

    @Test
    void plainVanillaItemWithNoTfTracesIsStillRemoved() {
        // 絶対原則: TF由来の痕跡が無い純バニラ品(修繕本等)は従来通り削除対象のまま。
        VanillaItemRemover remover = new VanillaItemRemover();
        remover.updateTargets(List.of("enchanted_book:mending"), LOG);

        assertTrue(remover.shouldRemove(enchantedBook(Enchantment.MENDING, 1)));
    }

    // --- 修正3: 不正なenchant入力でparseが落ちない ---

    @Test
    void invalidEnchantTokenDoesNotThrowAndIsSkipped() {
        // NamespacedKey.minecraft は空白等の不正文字で IllegalArgumentException を投げる経路。
        Set<VanillaItemRemover.ItemMatcher> matchers =
                VanillaItemRemover.parse(List.of("enchanted_book: not valid !!"), LOG);
        assertTrue(matchers.isEmpty());
    }

    // --- 修正4: 名前空間付きMaterial/enchant指定の解析 ---

    @Test
    void namespacedMaterialWithoutEnchantIsParsedCorrectly() {
        Set<VanillaItemRemover.ItemMatcher> matchers =
                VanillaItemRemover.parse(List.of("minecraft:iron_pickaxe"), LOG);
        assertEquals(1, matchers.size());
        VanillaItemRemover.ItemMatcher matcher = matchers.iterator().next();
        assertEquals(Material.IRON_PICKAXE, matcher.material());
        assertEquals(null, matcher.enchant());
    }

    @Test
    void namespacedMaterialWithEnchantScopeIsParsedCorrectly() {
        Set<VanillaItemRemover.ItemMatcher> matchers =
                VanillaItemRemover.parse(List.of("minecraft:enchanted_book:mending"), LOG);
        assertEquals(1, matchers.size());
        VanillaItemRemover.ItemMatcher matcher = matchers.iterator().next();
        assertEquals(Material.ENCHANTED_BOOK, matcher.material());
        assertEquals(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("mending")), matcher.enchant());
    }

    // --- 2026-07-25 修繕除去範囲拡大: ANY:<enchant> ワイルドカード ---

    @Test
    void parsesAnyWildcardEntry() {
        Set<VanillaItemRemover.ItemMatcher> matchers = VanillaItemRemover.parse(List.of("ANY:mending"), LOG);
        assertEquals(1, matchers.size());
        VanillaItemRemover.ItemMatcher matcher = matchers.iterator().next();
        assertEquals(null, matcher.material());
        assertEquals(Registry.ENCHANTMENT.get(NamespacedKey.minecraft("mending")), matcher.enchant());
    }

    @Test
    void anyWildcardIsCaseInsensitive() {
        Set<VanillaItemRemover.ItemMatcher> matchers = VanillaItemRemover.parse(List.of("any:mending"), LOG);
        assertEquals(1, matchers.size());
    }

    @Test
    void anyWildcardWithUnknownEnchantIsSkippedWithoutThrowing() {
        Set<VanillaItemRemover.ItemMatcher> matchers =
                VanillaItemRemover.parse(List.of("ANY:not_a_real_enchant"), LOG);
        assertTrue(matchers.isEmpty());
    }

    @Test
    void bareWildcardConstructorRejectsNullEnchant() {
        assertThrowsIllegalArgument(() -> new VanillaItemRemover.ItemMatcher(null, null));
    }

    private static void assertThrowsIllegalArgument(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    @Test
    void wildcardMendingMatchesEnchantedBookAndAnyGearPieceAlike() {
        // PRG要件: チェスト戦利品/村人取引で生成される「修繕付きの防具/道具そのもの」も除去対象にする。
        // 旧設計(ENCHANTED_BOOK:MENDINGのみ)はmaterial不一致でDIAMOND_PICKAXE等を見逃していた。
        VanillaItemRemover remover = new VanillaItemRemover();
        remover.updateTargets(List.of("ANY:mending"), LOG);

        assertTrue(remover.shouldRemove(enchantedBook(Enchantment.MENDING, 1)));

        ItemStack mendingPickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta pickaxeMeta = mendingPickaxe.getItemMeta();
        pickaxeMeta.addEnchant(Enchantment.MENDING, 1, true);
        mendingPickaxe.setItemMeta(pickaxeMeta);
        assertTrue(remover.shouldRemove(mendingPickaxe));

        ItemStack mendingChestplate = new ItemStack(Material.NETHERITE_CHESTPLATE);
        ItemMeta chestMeta = mendingChestplate.getItemMeta();
        chestMeta.addEnchant(Enchantment.MENDING, 1, true);
        mendingChestplate.setItemMeta(chestMeta);
        assertTrue(remover.shouldRemove(mendingChestplate));
    }

    @Test
    void wildcardMendingLeavesOtherEnchantsAndPlainGearAlone() {
        // 掃除の対象を広げすぎて他のエンチャントまで消さないこと(要件の明示的な注意点)。
        VanillaItemRemover remover = new VanillaItemRemover();
        remover.updateTargets(List.of("ANY:mending"), LOG);

        ItemStack unenchantedPickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        assertFalse(remover.shouldRemove(unenchantedPickaxe));

        ItemStack sharpnessSword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta swordMeta = sharpnessSword.getItemMeta();
        swordMeta.addEnchant(Enchantment.SHARPNESS, 5, true);
        sharpnessSword.setItemMeta(swordMeta);
        assertFalse(remover.shouldRemove(sharpnessSword));

        assertFalse(remover.shouldRemove(enchantedBook(Enchantment.UNBREAKING, 3)));
    }

    @Test
    void wildcardMendingStillRespectsTfCatalogProtection() {
        VanillaItemRemover remover = new VanillaItemRemover();
        remover.updateTargets(List.of("ANY:mending"), LOG);

        ItemStack stack = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = stack.getItemMeta();
        meta.addEnchant(Enchantment.MENDING, 1, true);
        ItemData.of(meta).setCatalogId("tf_custom_pick");
        stack.setItemMeta(meta);

        assertFalse(remover.shouldRemove(stack));
    }

    private static ItemStack enchantedBook(Enchantment enchant, int level) {
        ItemStack stack = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storageMeta) {
            storageMeta.addStoredEnchant(enchant, level, true);
        }
        stack.setItemMeta(meta);
        return stack;
    }
}
