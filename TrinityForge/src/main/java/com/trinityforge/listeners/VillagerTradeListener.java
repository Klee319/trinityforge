package com.trinityforge.listeners;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.VillagerTradesConfig;
import com.trinityforge.config.domains.VillagerTradesConfig.ProfessionTrades;
import com.trinityforge.config.domains.VillagerTradesConfig.TradeOffer;
import com.trinityforge.config.domains.VillagerTradesConfig.TradeStack;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Perk-gated villager trades ({@code economy/villager-trades.yml}), gated by {@code trade:<PROFESSION>}
 * (2026-07-23 動的ID方式改修 §3): unlike a recipe gate, an unreferenced profession stays <b>locked</b> — no
 * node placing {@code trade:<PROFESSION>} means {@code isActive} naturally returns {@code false} for every
 * player, so no explicit "unreferenced -&gt; open" branch is needed here (contrast
 * {@link CatalogCraftGateListener}).
 */
public final class VillagerTradeListener implements Listener {

    private static final String GATE_PREFIX = "trade:";

    private static final Component LOCKED = Component.text(
            "この村人との取引はスキルツリーで解放されていません。", NamedTextColor.RED);

    private final DedicatedEffectsConfig dedicatedEffects;
    private final VillagerTradesConfig tradesConfig;
    private final ItemCatalogConfig catalog;
    private final ItemFactory itemFactory;

    public VillagerTradeListener(DedicatedEffectsConfig dedicatedEffects,
                                 VillagerTradesConfig tradesConfig,
                                 ItemCatalogConfig catalog,
                                 ItemFactory itemFactory) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.tradesConfig = Objects.requireNonNull(tradesConfig, "tradesConfig");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getInventory() instanceof MerchantInventory merchant)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(merchant.getHolder() instanceof Villager villager)) {
            return;
        }
        ProfessionTrades cfg = tradesConfig.byProfession().get(villager.getProfession());
        if (cfg == null) {
            return;
        }
        String professionName = villager.getProfession().name();
        boolean unlocked = dedicatedEffects.isActive(player, GATE_PREFIX + professionName);
        if (!unlocked) {
            event.setCancelled(true);
            player.sendMessage(LOCKED);
            return;
        }
        // 初回のみ注入する。GUIを開くたびに setRecipes で再構築すると使用回数(取引ロック)が
        // 巻き戻り無限購入 exploit になり、!blockVanillaTrades 時はカスタム取引が重複蓄積する。
        // 現在の職業向けに注入済みなら以降はバニラの使用回数/補充機構へ委ねる(PdcKeys 参照)。
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        String injectedFor = pdc.get(PdcKeys.VILLAGER_TRADES_INJECTED, PersistentDataType.STRING);
        if (professionName.equals(injectedFor)) {
            return;
        }
        List<MerchantRecipe> custom = buildRecipes(cfg.trades());
        // config は取引を定義しているのに全件解決に失敗した(カタログ未ロード等の一時状態)場合は、
        // 注入もマーキングもせず次回オープンで再試行する。ここでマークすると、バニラ取引を消去
        // (blockVanillaTrades)したり空注入で確定したりして、後からカタログが揃っても復旧しない。
        if (!cfg.trades().isEmpty() && custom.isEmpty()) {
            return;
        }
        boolean injected = false;
        if (cfg.blockVanillaTrades()) {
            // config が空 = 取引無効化の意図。解決済みカスタムのみ(空なら全取引消去)を冪等に設定。
            villager.setRecipes(custom);
            injected = true;
        } else if (!custom.isEmpty()) {
            List<MerchantRecipe> merged = new ArrayList<>(villager.getRecipes());
            merged.addAll(custom);
            villager.setRecipes(merged);
            injected = true;
        }
        if (injected) {
            pdc.set(PdcKeys.VILLAGER_TRADES_INJECTED, PersistentDataType.STRING, professionName);
        }
    }

    /**
     * 村人が転職するとバニラが取引一覧を再生成し、以前注入したカスタム取引は失われる。注入済み
     * マーカーを消して次回オープン時に(新しい職業向けの)再注入を許すことで、転職→復職の往復でも
     * カスタム取引が恒久的に消えないようにする。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCareerChange(VillagerCareerChangeEvent event) {
        event.getEntity().getPersistentDataContainer().remove(PdcKeys.VILLAGER_TRADES_INJECTED);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) {
            return;
        }
        ProfessionTrades cfg = tradesConfig.byProfession().get(villager.getProfession());
        if (cfg == null) {
            return;
        }
        Player player = event.getPlayer();
        boolean unlocked = dedicatedEffects.isActive(player, GATE_PREFIX + villager.getProfession().name());
        if (!unlocked && cfg.blockVanillaTrades()) {
            event.setCancelled(true);
            player.sendMessage(LOCKED);
        }
    }

    private List<MerchantRecipe> buildRecipes(List<TradeOffer> offers) {
        List<MerchantRecipe> out = new ArrayList<>();
        for (TradeOffer offer : offers) {
            ItemStack input = resolveStack(offer.input());
            ItemStack result = resolveStack(offer.output());
            if (input == null || result == null) {
                continue;
            }
            MerchantRecipe recipe = new MerchantRecipe(result, 0, offer.maxUses(), false,
                    offer.villagerXp(), 0.05f);
            recipe.addIngredient(input);
            out.add(recipe);
        }
        return out;
    }

    private ItemStack resolveStack(TradeStack stack) {
        if (stack.isCatalog()) {
            return catalog.template(stack.catalogId())
                    .map(t -> itemFactory.create(t, ThreadLocalRandom.current().nextLong(), 0))
                    .orElse(null);
        }
        Material mat = Material.matchMaterial(stack.material());
        if (mat == null || mat.isAir()) {
            return null;
        }
        return new ItemStack(mat, stack.amount());
    }
}
