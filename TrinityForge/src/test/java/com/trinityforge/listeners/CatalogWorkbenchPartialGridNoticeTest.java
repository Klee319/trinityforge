package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.stats.CatalogRecipeRegistrar;
import com.trinityforge.stats.ExternalItemRegistry;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「クラフトはできたのに『材料にできません』の通知が出る」の回帰ガード (2026-08-24 実サーバ報告)。
 *
 * <p><b>真因は判定ではなく通知の出しどきだった。</b> {@code PrepareItemCraftEvent} は
 * <b>マスを1つ触るたびに飛ぶ</b>ので、カタログ品を作業台へ並べている途中の盤面は
 * どのレシピにも一致しない ── そこで名指しすると<b>まだ何も奪っていないのに止めたと言う</b>。
 *
 * <p>統合版では必ず起きる。Geyser はクラフト要求を<b>マスへの1つずつの配置</b>へ翻訳するので、
 * 最終的に成立するレシピであっても組み立ての途中で不一致の盤面を必ず通る。
 * ブロック系アイテムの Bedrock 登録を直してレシピが届くようになった直後に
 * 「クラフトはできたが通知がまだ出る」という形で表面化した。
 *
 * <p>ここで固定するのは<b>「レシピが選ばれていたときだけ通知する」</b>こと。W-87 が守りたかった場面
 * (バニラのレシピが成立していて、カタログ品のせいでそれを消した) は selected が非 null なので残る。
 */
class CatalogWorkbenchPartialGridNoticeTest {

    private static final String PLUGIN_LAYER = "arspaper";
    private static final int COMPRESSED_NETHERRACK_CMD = 1001;

    private CatalogWorkbenchListener listener;
    private Player player;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        CatalogRecipeRegistrar registrar = mock(CatalogRecipeRegistrar.class);
        when(registrar.registered(any())).thenReturn(Optional.empty());
        when(registrar.allRegistered()).thenReturn(List.of());
        listener = new CatalogWorkbenchListener(registrar, mock(ItemCatalogConfig.class));
        player = mock(Player.class);
        ExternalItemRegistry.updateExternalPlugin(PLUGIN_LAYER, Map.of(
                "netherrack_2x", new ExternalItemRegistry.Definition(
                        "netherrack_2x", Material.NETHERRACK,
                        COMPRESSED_NETHERRACK_CMD, "81倍圧縮ネザーラック")));
    }

    @AfterEach
    void tearDown() {
        ExternalItemRegistry.updateExternalPlugin(PLUGIN_LAYER, Map.of());
        MockBukkit.unmock();
    }

    /** 見た目は素のネザーラックそのままの圧縮ネザーラック。 */
    private static ItemStack compressedNetherrack() {
        ItemStack stack = new ItemStack(Material.NETHERRACK);
        stack.editMeta(meta -> meta.setCustomModelData(COMPRESSED_NETHERRACK_CMD));
        return stack;
    }

    /**
     * 盤面と「Bukkit が選んだレシピ」を持つ prepare イベント。
     *
     * <p>MockBukkit の作業台ビューではなく mock を組むのは、<b>選ばれたレシピが null かどうか</b>
     * だけがこのテストの関心で、そこを直接置きたいため。
     */
    private PrepareItemCraftEvent prepareEvent(ItemStack[] matrix, ShapedRecipe selected) {
        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getMatrix()).thenReturn(matrix);
        when(inventory.getRecipe()).thenReturn(selected);
        InventoryView view = mock(InventoryView.class);
        when(view.getPlayer()).thenReturn(player);
        return new PrepareItemCraftEvent(inventory, view, false);
    }

    /** 組み立て途中: カタログ品は乗っているが、まだどのレシピにも一致していない。 */
    private static ItemStack[] partialGrid() {
        ItemStack[] matrix = new ItemStack[9];
        matrix[4] = compressedNetherrack();
        return matrix;
    }

    /** 完成盤面: バニラの鍛冶型複製が成立していて、中央だけがカタログ品。 */
    private static ItemStack[] templateDuplicationGrid() {
        ItemStack diamond = new ItemStack(Material.DIAMOND);
        return new ItemStack[] {
                diamond.clone(),
                new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                diamond.clone(),
                diamond.clone(), compressedNetherrack(), diamond.clone(),
                diamond.clone(), diamond.clone(), diamond.clone(),
        };
    }

    @Test
    @DisplayName("組み立て途中の盤面では通知しない(まだ何も奪っていない)")
    void doesNotNotifyWhileTheGridIsStillBeingFilled() {
        listener.onPrepareCraft(prepareEvent(partialGrid(), null));

        verify(player, never()).sendActionBar(any(Component.class));
    }

    @Test
    @DisplayName("成立していたレシピを消したときは従来どおり通知する(W-87)")
    void stillNotifiesWhenARealResultWasRemoved() {
        ShapedRecipe vanilla = mock(ShapedRecipe.class);
        when(vanilla.getKey()).thenReturn(
                NamespacedKey.minecraft("netherite_upgrade_smithing_template"));

        listener.onPrepareCraft(prepareEvent(templateDuplicationGrid(), vanilla));

        verify(player, times(1)).sendActionBar(any(Component.class));
    }
}
