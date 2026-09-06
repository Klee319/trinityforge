package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CatalogIdentity;
import com.trinityforge.stats.DerivedItemStats;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemUpgradeCarryOver;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.PreviewRollSeeds;
import com.trinityforge.stats.RecipeSpec;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.meta.ItemMeta;

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
 *
 * <p><b>W-51(2026-08-18)</b>: カタログ照合が外れるのは「よそのカタログ品」だけではない。
 * {@link CatalogItemMatch#matchesTemplate} は CMD(CustomModelData) を持たないアイテムを
 * 問答無用で false にするため、CMD の無い<b>素のバニラ装備</b>(品質 PDC だけは
 * {@code CraftQualityListener} により刻まれている)もここに落ちてくる。そのままだと
 * Bukkit 標準のスミスレシピ({@code copyDataComponents} 既定 true)が base の PDC を
 * 丸ごとコピーしたまま Material だけ差し替えるので、lore・耐久上限・use-level-requirement が
 * 旧 Material(ダイヤ等)の値に凍結される。{@link #restampPlainQualityUpgrade}/
 * {@link #restampPlainQualitySmith} がこのケースを検知し、{@link ItemFactory#stamp}
 * で新 Material の item-stats プロファイルに基づいて再組み立てする(品質と rollSeed は引き継ぐ —
 * 2026-08-29 ユーザー決定。W-51 の再抽選は撤回)。
 *
 * <p><b>W-140(2026-08-19) 増殖バグ修正 — {@code setItemOnCursor} を呼んではいけない</b>:
 * 実サーバ報告「鍛冶台でネザライト化する際に素材を消費せず無限にネザライト化できる」。
 * {@link SmithItemEvent} は {@code InventoryClickEvent} であり、CraftBukkit の
 * {@code handleContainerClick} は<b>イベントを発火してから</b>バニラの実処理
 * ({@code AbstractContainerMenu.clicked}) を走らせる。そこでカーソルへ完成品を載せてしまうと、
 * バニラは「カーソルが埋まっている」状態から結果枠の取得を試みるので
 * {@code tryRemove(count, maxStackSize - cursorCount)} の上限が 1-1=0 になり
 * <b>{@code ResultSlot#onTake} が一度も呼ばれない = 素材が消費されない</b>。それでいて手には
 * こちらが載せた完成品が残るため、盤面そのままで無限に取り出せる。
 * {@code CraftQualityListener#onCraft} が 2026-07-28 に同じ理由で通った道で、
 * <b>結果枠({@code setCurrentItem})だけを差し替えれば</b>バニラが正規の
 * 「カーソル空 → onTake(素材消費) → カーソルへ」を実行する。
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
            if (!restampPlainQualityUpgrade(event, inventory.getInputEquipment())) {
                clearForeignNetheriteResult(event);
            }
            return;
        }
        int quality = CatalogItemMatch.qualityOf(match.base());
        long seed = CatalogItemMatch.rollSeedOf(match.base())
                .orElse(PreviewRollSeeds.SMITHING);
        ItemStack result = itemFactory.create(match.resultTemplate(), seed, quality);
        CatalogIdentity.ensure(result, itemCatalog);
        // まっさらな create() が素材のエンチャントを落とすので、品質 stamp 前でも後でも
        // プレイヤー付与分は max で戻す（儀式と同じ ItemUpgradeCarryOver）。
        ItemUpgradeCarryOver.copyEnchantments(match.base(), result);
        ArsSocketCarryOver.copy(match.base(), result);
        event.setResult(result);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmith(SmithItemEvent event) {
        Match match = match(event.getInventory());
        if (match == null) {
            restampPlainQualitySmith(event);
            return;
        }
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType().isAir()) {
            return;
        }
        int quality = CatalogItemMatch.qualityOf(match.base());
        long seed = CatalogItemMatch.rollSeedOf(match.base())
                .orElseGet(ThreadLocalRandom.current()::nextLong);
        ItemStack stamped = itemFactory.create(match.resultTemplate(), seed, quality);
        CatalogIdentity.ensure(stamped, itemCatalog);
        ItemUpgradeCarryOver.copyEnchantments(match.base(), stamped);
        ArsSocketCarryOver.copy(match.base(), stamped);
        // 結果枠だけを差し替える。カーソルには絶対に触らない(理由は setItemOnCursor の禁止理由)。
        event.setCurrentItem(stamped.clone());
    }

    /**
     * W-51: プレビュー({@link #onPrepare})側の CMD 無し品質付きバニラ装備の救済。
     * 品質と rollSeed は base から見せる（確定時と同じ個体）。
     *
     * @return この経路で処理した(=結果を差し替えた)なら {@code true}。{@code false} のときは
     *         呼び出し側が {@link #clearForeignNetheriteResult} など既存の経路へフォールバックする。
     */
    private boolean restampPlainQualityUpgrade(PrepareSmithingEvent event, ItemStack base) {
        ItemStack vanillaResult = event.getResult();
        if (!isPlainQualityUpgrade(base, vanillaResult)) {
            return false;
        }
        int quality = CatalogItemMatch.qualityOf(base);
        long seed = CatalogItemMatch.rollSeedOf(base).orElse(PreviewRollSeeds.SMITHING);
        ItemStack stamped = vanillaResult.clone();
        itemFactory.stamp(stamped, seed, quality);
        event.setResult(stamped);
        return true;
    }

    /**
     * W-51: 実際に強化を確定させる側。品質と rollSeed は base から引き継ぐ
     * (2026-08-29 ユーザー決定「ベースの品質ptとロールを引き継ぐ」。W-51 の再抽選は撤回)。
     */
    private boolean restampPlainQualitySmith(SmithItemEvent event) {
        ItemStack base = event.getInventory().getInputEquipment();
        ItemStack current = event.getCurrentItem();
        if (!isPlainQualityUpgrade(base, current)) {
            return false;
        }
        int quality = CatalogItemMatch.qualityOf(base);
        long seed = CatalogItemMatch.rollSeedOf(base)
                .orElseGet(ThreadLocalRandom.current()::nextLong);
        ItemStack stamped = current.clone();
        itemFactory.stamp(stamped, seed, quality);
        // 結果枠だけを差し替える。カーソルには絶対に触らない(理由は setItemOnCursor の禁止理由)。
        event.setCurrentItem(stamped.clone());
        return true;
    }

    /**
     * 「CMD 無し・TF 品質 PDC(rollSeed) 持ち・Material が実際に変わっている」の 3 条件が揃ったときだけ
     * true。CMD 付きは {@link CatalogItemMatch#matchesTemplate} 側の経路に任せる(カタログ品を
     * 二重に処理しない)。rollSeed を一度も刻まれていない完全な素のバニラ装備(品質 0 未満どころか
     * PDC 自体が無い)はこれまで通り素通しのまま(仕様変更の対象外 — 救済は「TF 品質を持つ既存装備」
     * だけ)。
     */
    private static boolean isPlainQualityUpgrade(ItemStack base, ItemStack candidateResult) {
        if (base == null || base.getType().isAir() || !base.hasItemMeta()) {
            return false;
        }
        ItemMeta baseMeta = base.getItemMeta();
        if (DerivedItemStats.customModelDataOf(baseMeta) != null) {
            return false;
        }
        if (!ItemData.of(baseMeta).hasRollSeed()) {
            return false;
        }
        return candidateResult != null && !candidateResult.getType().isAir()
                && candidateResult.getType() != base.getType();
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
