package com.trinityforge.smithing;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.SmithingRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 統合版の鍛冶台補助が「いつ横取りするか」を固定する。
 *
 * <p>補助は<b>手持ちを勝手に鍛冶台へ移す</b>操作なので、条件を緩めると
 * 「鍛冶台を開いただけなのにインゴットや鍛冶型を base スロットへ吸われる」という
 * 元の不具合より悪い壊し方をする。ここで縛るのはその境界。
 */
class BedrockSmithingAssistListenerTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock(); // ItemStack / NamespacedKey の生成に Bukkit 実装が要る
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** バニラのネザライト強化相当(型=テンプレート / base=ダイヤ剣 / addition=ネザライトインゴット)。 */
    private static SmithingRecipe vanillaNetherite() {
        return new SmithingTransformRecipe(
                new NamespacedKey("test", "netherite_sword"),
                new ItemStack(Material.NETHERITE_SWORD),
                new RecipeChoice.MaterialChoice(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                new RecipeChoice.MaterialChoice(Material.DIAMOND_SWORD),
                new RecipeChoice.MaterialChoice(Material.NETHERITE_INGOT));
    }

    /** TF が catalog.yml の {@code method: netherite} から登録する、バニラに無い base のレシピ。 */
    private static SmithingRecipe trinityForgeBow() {
        return new SmithingTransformRecipe(
                new NamespacedKey("test", "catalog_diamond_bow_smithing"),
                new ItemStack(Material.BOW),
                new RecipeChoice.MaterialChoice(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                new RecipeChoice.MaterialChoice(Material.BOW),
                new RecipeChoice.MaterialChoice(Material.NETHERITE_INGOT));
    }

    private static List<SmithingRecipe> recipes() {
        return List.of(vanillaNetherite(), trinityForgeBow());
    }

    /**
     * 今回の報告そのもの。統合版クライアントは弓を base スロットへ置かせないので、
     * ここが false になると補助が一切働かない。
     */
    @Test
    void assistsForBaseItemThatBedrockClientRefuses() {
        assertTrue(BedrockSmithingAssistListener.shouldAssist(new ItemStack(Material.BOW), recipes()));
    }

    @Test
    void assistsForVanillaBaseItem() {
        assertTrue(BedrockSmithingAssistListener.shouldAssist(new ItemStack(Material.DIAMOND_SWORD), recipes()));
    }

    /** 追加素材スロットの物を base へ差し込むと、正しく動いているバニラの強化を壊す。 */
    @Test
    void doesNotAssistForAdditionItem() {
        assertFalse(BedrockSmithingAssistListener.shouldAssist(new ItemStack(Material.NETHERITE_INGOT), recipes()));
    }

    /** 鍛冶型も同様。統合版でも型スロットには普通に置ける。 */
    @Test
    void doesNotAssistForTemplateItem() {
        assertFalse(BedrockSmithingAssistListener.shouldAssist(
                new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE), recipes()));
    }

    /**
     * どのレシピの base でもない物は横取りしない。
     * 松明を持ったまま鍛冶台を開いた人から松明を奪わないための線。
     */
    @Test
    void doesNotAssistForUnrelatedItem() {
        assertFalse(BedrockSmithingAssistListener.shouldAssist(new ItemStack(Material.TORCH), recipes()));
    }

    /**
     * base でもあり addition でもある物は<b>addition を優先して横取りしない</b>。
     * ダイヤはトリム素材(addition)なので、base 一致だけで判断すると
     * 「トリムのつもりで持ったダイヤが base スロットへ入る」事故になる。
     */
    @Test
    void additionWinsOverBaseWhenItemMatchesBoth() {
        SmithingRecipe baseIsDiamond = new SmithingTransformRecipe(
                new NamespacedKey("test", "diamond_as_base"),
                new ItemStack(Material.DIAMOND_BLOCK),
                new RecipeChoice.MaterialChoice(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                new RecipeChoice.MaterialChoice(Material.DIAMOND),
                new RecipeChoice.MaterialChoice(Material.NETHERITE_INGOT));
        SmithingRecipe diamondIsAddition = new SmithingTransformRecipe(
                new NamespacedKey("test", "diamond_as_addition"),
                new ItemStack(Material.DIAMOND_HELMET),
                new RecipeChoice.MaterialChoice(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                new RecipeChoice.MaterialChoice(Material.IRON_HELMET),
                new RecipeChoice.MaterialChoice(Material.DIAMOND));

        assertFalse(BedrockSmithingAssistListener.shouldAssist(
                new ItemStack(Material.DIAMOND), List.of(baseIsDiamond, diamondIsAddition)));
    }

    @Test
    void doesNotAssistForEmptyHand() {
        assertFalse(BedrockSmithingAssistListener.shouldAssist(null, recipes()));
        assertFalse(BedrockSmithingAssistListener.shouldAssist(new ItemStack(Material.AIR), recipes()));
    }

    /** レシピが一つも取れなかった場合は base 一致が成立しないので横取りしない(手持ちを守る側に倒す)。 */
    @Test
    void doesNotAssistWhenNoRecipesAreKnown() {
        assertFalse(BedrockSmithingAssistListener.shouldAssist(new ItemStack(Material.BOW), List.of()));
    }

    /**
     * Floodgate の UUID は {@code new UUID(0, xuid)} で組まれるので、
     * 文字列表現では上位 4 ブロックがすべて 0 になる。
     */
    @Test
    void detectsFloodgateUuid() {
        assertTrue(BedrockSmithingAssistListener.isBedrockId(
                UUID.fromString("00000000-0000-0000-0009-0123456789ab")));
        assertTrue(BedrockSmithingAssistListener.isBedrockId(new UUID(0L, 1L)));
    }

    /** Java 版の正規 UUID は version 4 = 上位 64bit に version ビットが立つので必ず非 0。 */
    @Test
    void doesNotDetectJavaUuid() {
        for (int i = 0; i < 100; i++) {
            assertFalse(BedrockSmithingAssistListener.isBedrockId(UUID.randomUUID()));
        }
        assertFalse(BedrockSmithingAssistListener.isBedrockId(null));
    }
}
