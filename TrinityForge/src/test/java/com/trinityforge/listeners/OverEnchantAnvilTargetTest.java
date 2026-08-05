package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 金床の上限突破合成が、そのアイテムに本来付かないエンチャントを載せてしまわないこと
 * （実サーバ報告「ツルハシに射撃ダメージがつく等、不適正ツールにエンチャントが付いてしまう」
 * 2026-08-04）。
 *
 * <h2>壊れ方</h2>
 * {@code onAnvil} は候補を first / second / result の3つから集める。金床の2枠目に置いた
 * エンチャント本のエンチャントは必ず候補に入るので、「射撃ダメージ増加」の本を持ち込むと
 * {@code b > 0} になり、{@code a <= 0 && b <= 0} の早期 continue を通り抜ける。
 * そこから先は {@code combined = max(a, b)} を結果へ書き込むだけで、書き込みは
 * {@code addUnsafeEnchantment} なので<b>対象種別の検査を素通りする</b>。
 * 結果、ツルハシに射撃ダメージ増加が乗った。
 *
 * <p>この経路は<b>上限突破プロファイルが有効なエンチャントにしか通らない</b>点に注意
 * （{@code overMax <= ench.getMaxLevel()} で先に continue するため）。だから「特定のパークを
 * 取ったプレイヤーだけで起きる」という再現しづらい形になっていた。
 */
class OverEnchantAnvilTargetTest {

    /** 射撃ダメージ増加。弓専用なのでツルハシには本来付かない。 */
    private static final Enchantment BOW_ONLY = Enchantment.POWER;
    /** ダメージ増加。剣に正しく付く方。上限突破そのものが死んでいないかの対照に使う。 */
    private static final Enchantment SWORD_OK = Enchantment.SHARPNESS;

    private OverEnchantListener listener;
    private Player player;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        player = mock(Player.class);

        DedicatedEffectsConfig effects = mock(DedicatedEffectsConfig.class);
        when(effects.isActive(any(), any())).thenReturn(true);

        CraftingFeaturesConfig features = mock(CraftingFeaturesConfig.class);
        // 両方ともバニラ上限 +1 まで許すプロファイルが有効、という状況を作る。
        when(features.overEnchantMaxLevel(any(), any())).thenAnswer(invocation -> {
            Enchantment ench = invocation.getArgument(1);
            if (BOW_ONLY.equals(ench) || SWORD_OK.equals(ench)) {
                return ench.getMaxLevel() + 1;
            }
            return 0;
        });

        listener = new OverEnchantListener(effects, features);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("ツルハシ + 射撃ダメージ増加の本 → ツルハシに射撃ダメージは載らない")
    void anvilDoesNotPushBowEnchantOntoAPickaxe() {
        ItemStack pickaxe = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemStack powerBook = book(BOW_ONLY, BOW_ONLY.getMaxLevel());
        // バニラの金床は付けられない組み合わせなので、結果はツルハシのまま(名前変更等で残ることはある)。
        ItemStack vanillaResult = new ItemStack(Material.DIAMOND_PICKAXE);

        // 前提の確認: そもそも射撃ダメージはツルハシに付かない。ここが false ならテストが無意味。
        assertFalse(BOW_ONLY.canEnchantItem(pickaxe),
                "前提が崩れている: 射撃ダメージ増加がツルハシに付与可能になっている");

        PrepareAnvilEvent event = anvilEvent(pickaxe, powerBook, vanillaResult);
        listener.onAnvil(event);

        ArgumentCaptor<ItemStack> captor = ArgumentCaptor.forClass(ItemStack.class);
        verify(event, never()).setResult(captor.capture());
        assertEquals(0, vanillaResult.getEnchantmentLevel(BOW_ONLY),
                "金床の結果(ツルハシ)に射撃ダメージ増加が書き込まれている");
    }

    @Test
    @DisplayName("剣 + ダメージ増加の本 → 上限突破は今までどおり効く")
    void anvilStillOverEnchantsWhatTheItemCanActuallyTake() {
        int vanillaMax = SWORD_OK.getMaxLevel();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(SWORD_OK, vanillaMax);
        ItemStack sharpBook = book(SWORD_OK, vanillaMax);
        ItemStack vanillaResult = new ItemStack(Material.DIAMOND_SWORD);
        vanillaResult.addUnsafeEnchantment(SWORD_OK, vanillaMax);

        PrepareAnvilEvent event = anvilEvent(sword, sharpBook, vanillaResult);
        listener.onAnvil(event);

        ArgumentCaptor<ItemStack> captor = ArgumentCaptor.forClass(ItemStack.class);
        verify(event).setResult(captor.capture());
        assertEquals(vanillaMax + 1, captor.getValue().getEnchantmentLevel(SWORD_OK),
                "剣に正しく付くエンチャントまで弾いてしまっている(上限突破が死んでいる)");
    }

    @Test
    @DisplayName("本 + 本 の上限突破も生きている（本は canEnchantItem が常に false なので特例が要る）")
    void anvilStillOverEnchantsBookOnBook() {
        int vanillaMax = BOW_ONLY.getMaxLevel();
        ItemStack left = book(BOW_ONLY, vanillaMax);
        ItemStack right = book(BOW_ONLY, vanillaMax);
        ItemStack vanillaResult = book(BOW_ONLY, vanillaMax);

        // 本に対しては canEnchantItem がほぼ常に false になる。canCarry の特例が無いと
        // 「本の上限突破」という機能そのものが無言で死ぬ。
        assertFalse(BOW_ONLY.canEnchantItem(vanillaResult),
                "前提が崩れている: エンチャント本が canEnchantItem を満たしている");

        PrepareAnvilEvent event = anvilEvent(left, right, vanillaResult);
        listener.onAnvil(event);

        ArgumentCaptor<ItemStack> captor = ArgumentCaptor.forClass(ItemStack.class);
        verify(event).setResult(captor.capture());
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) captor.getValue().getItemMeta();
        assertTrue(meta.getStoredEnchantLevel(BOW_ONLY) == vanillaMax + 1,
                "本 + 本 の上限突破が効いていない: " + meta.getStoredEnchantLevel(BOW_ONLY));
    }

    private static ItemStack book(Enchantment ench, int level) {
        ItemStack stack = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) stack.getItemMeta();
        meta.addStoredEnchant(ench, level, true);
        stack.setItemMeta(meta);
        return stack;
    }

    private PrepareAnvilEvent anvilEvent(ItemStack first, ItemStack second, ItemStack result) {
        AnvilInventory inventory = mock(AnvilInventory.class);
        when(inventory.getFirstItem()).thenReturn(first);
        when(inventory.getSecondItem()).thenReturn(second);

        AnvilView view = mock(AnvilView.class);
        when(view.getPlayer()).thenReturn(player);

        PrepareAnvilEvent event = mock(PrepareAnvilEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getView()).thenReturn(view);
        when(event.getResult()).thenReturn(result);
        return event;
    }

    /** 使わないが、Predicate の型を明示しておくとモックの引数解決で迷わない。 */
    @SuppressWarnings("unused")
    private static Predicate<String> alwaysActive() {
        return id -> true;
    }
}
