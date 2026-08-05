package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CatalogIdentity;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.PreviewRollSeeds;
import com.trinityforge.stats.RecipeSpec;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Catalog {@code recipe.method: netherite} on the smithing table.
 *
 * <p>Template = netherite upgrade smithing template, base = {@code source-item},
 * addition = netherite ingot; result = this catalog entry with source quality.
 * Combine recipes are handled by {@link CatalogAnvilListener} on the anvil.
 *
 * <p><b>Bukkitレシピとの分担(2026-08-01 U7)</b>: base スロットに置けるかどうかと
 * 「そもそも {@link PrepareSmithingEvent} が飛ぶかどうか」は Bukkit 側のレシピが決める
 * (CraftBukkit の {@code SmithingMenu#createResult} はレシピが一致したときだけこのイベントを呼び、
 * 不一致なら結果スロットを空にして終わる)。そのため
 * {@code CatalogRecipeRegistrar#registerNetheriteOne} が base=素材の {@code SmithingTransformRecipe}
 * を登録し、<b>実際に何が出来上がるかはこのリスナーだけが決める</b>。
 * 登録レシピの base は「材質だけ」の緩い判定なので、素のバニラ弓でも一致してしまう —
 * 精密照合({@link CatalogItemMatch#matchesTemplate})が外れたときに
 * {@link #clearForeignNetheriteResult} が結果を消すことで「素の弓＋インゴット→ネザライトの弓」の
 * 抜け道を塞いでいる。
 */
public final class CatalogSmithingListener implements Listener {

    private final ItemCatalogConfig itemCatalog;
    private final ItemFactory itemFactory;

    public CatalogSmithingListener(ItemCatalogConfig itemCatalog, ItemFactory itemFactory) {
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepare(PrepareSmithingEvent event) {
        SmithingInventory inventory = event.getInventory();
        if (!isNetheriteTemplate(inventory.getInputTemplate()) || !isNetheriteIngot(inventory.getInputMineral())) {
            // 防具トリム等、ネザライト強化以外の組み合わせには一切干渉しない。
            return;
        }
        Match match = match(inventory);
        if (match == null) {
            clearForeignNetheriteResult(event);
            return;
        }
        int quality = CatalogItemMatch.qualityOf(match.base());
        ItemStack result = itemFactory.create(match.resultTemplate(), PreviewRollSeeds.SMITHING, quality);
        CatalogIdentity.ensure(result, itemCatalog);
        carryOverEnchantments(match.base(), result);
        event.setResult(result);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmith(SmithItemEvent event) {
        Match match = match(event.getInventory());
        if (match == null) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType().isAir()) {
            return;
        }
        int quality = CatalogItemMatch.qualityOf(match.base());
        long seed = ThreadLocalRandom.current().nextLong();
        ItemStack stamped = itemFactory.create(match.resultTemplate(), seed, quality);
        CatalogIdentity.ensure(stamped, itemCatalog);
        carryOverEnchantments(match.base(), stamped);
        event.setCurrentItem(stamped.clone());
        if (event.getWhoClicked() instanceof org.bukkit.entity.Player player && !event.isShiftClick()) {
            player.setItemOnCursor(stamped.clone());
        }
    }

    /**
     * ネザライト強化の 3 点が揃っているのに TF の精密照合が外れたとき、Bukkit 側で組み上がった結果が
     * 「{@code method: netherite} を持つカタログアイテム」なら結果を消す。
     *
     * <p>{@code CatalogRecipeRegistrar} が登録するスミス台レシピの base は材質だけの
     * {@code MaterialChoice} なので、素のバニラ弓や既にネザライト化済みの弓でも一致してしまう。
     * その場合ここで潰さないと、素材無しでネザライト装備が量産できてしまう。
     *
     * <p>判定を「結果側のカタログIDが netherite レシピを持つか」にしているのは、バニラのレシピが
     * base の PDC を引き継いで結果に載せてくるケース(ダイヤ装備→バニラのネザライト装備)を
     * 巻き込まないため — その場合結果に載るのは<b>素材側</b>のID(例 {@code diamond_dagger})で、
     * それ自身は netherite レシピを持たないので消さない。
     */
    private void clearForeignNetheriteResult(PrepareSmithingEvent event) {
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir() || !result.hasItemMeta()) {
            return;
        }
        Optional<String> catalogId = ItemData.of(result.getItemMeta()).catalogId();
        if (catalogId.isEmpty()) {
            return;
        }
        boolean tfNetheriteResult = itemCatalog.template(catalogId.get())
                .map(template -> template.recipes().stream()
                        .anyMatch(recipe -> recipe.isNetherite() && recipe.shouldRegister()))
                .orElse(false);
        if (tfNetheriteResult) {
            event.setResult(null);
        }
    }

    /**
     * ネザライト強化の素材に付いていたエンチャントを成果物へ引き継ぐ
     * （実サーバ報告「ネザライト化したときにエンチャントがはがれる」2026-08-05 の修正）。
     *
     * <p>このリスナーは成果物を {@code itemFactory.create(...)} で<b>まっさらに作り直す</b>。
     * TF のステータス/品質を正しく刻むにはそれが必要だが、その副作用として素材側の
     * エンチャントが丸ごと消えていた。バニラのネザライト強化はエンチャントを保持するので、
     * プレイヤーから見ると「強化したら全部消えた」という取り返しのつかない損失になる。
     *
     * <p><b>付与可否({@code canEnchantItem})で絞り込まない。</b> 素材が正当に持っていた
     * エンチャントなので、成果物でも正当である。TF のカタログ品は見た目のために本来の武器種と
     * 違う Material を土台にすることがあり（杖など）、ここで絞ると<b>正しいエンチャントの方が
     * 消える</b>。金床側の不正付与は {@link OverEnchantListener} の入口で塞いである。
     *
     * <p>テンプレート側が同じエンチャントを持つ場合はレベルの高い方を残す。
     */
    private static void carryOverEnchantments(ItemStack base, ItemStack result) {
        if (base == null || result == null || result.getType().isAir()) {
            return;
        }
        for (Map.Entry<Enchantment, Integer> entry : base.getEnchantments().entrySet()) {
            Enchantment ench = entry.getKey();
            int fromBase = entry.getValue();
            if (fromBase <= 0) {
                continue;
            }
            if (result.getEnchantmentLevel(ench) >= fromBase) {
                continue;
            }
            // addUnsafeEnchantment: 上限突破パークで素材が上限超えのレベルを持っている場合に、
            // ここで削られないようにする。
            result.addUnsafeEnchantment(ench, fromBase);
        }
    }

    private Match match(SmithingInventory inventory) {
        ItemStack template = inventory.getInputTemplate();
        ItemStack base = inventory.getInputEquipment();
        ItemStack addition = inventory.getInputMineral();
        if (base == null || base.getType().isAir()) {
            return null;
        }
        if (!isNetheriteTemplate(template) || !isNetheriteIngot(addition)) {
            return null;
        }

        for (ItemTemplate resultTemplate : itemCatalog.all().values()) {
            for (RecipeSpec recipe : resultTemplate.recipes()) {
                if (!recipe.isNetherite() || !recipe.shouldRegister()) {
                    continue;
                }
                Optional<ItemTemplate> source = itemCatalog.template(recipe.sourceItem());
                if (source.isEmpty() || !CatalogItemMatch.matchesTemplate(base, source.get())) {
                    continue;
                }
                return new Match(resultTemplate, base);
            }
        }
        return null;
    }

    private static boolean isNetheriteTemplate(ItemStack stack) {
        return stack != null && stack.getType() == Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE;
    }

    private static boolean isNetheriteIngot(ItemStack stack) {
        return stack != null && stack.getType() == Material.NETHERITE_INGOT;
    }

    private record Match(ItemTemplate resultTemplate, ItemStack base) {
    }
}
