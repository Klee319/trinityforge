package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig.DisassemblyOutput;
import com.trinityforge.config.domains.CraftingFeaturesConfig.DisassemblyRule;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.ItemFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles right-clicking a {@code scrap-conversion.<sourceId>} source item (currently only
 * {@code tf_scrap}, the "plain" scrap left over from {@link DisassemblyListener} that has no
 * forward recipe of its own): consumes {@code base-amount} of the held stack and gives one
 * weighted-random output item, drawn from {@code progression/crafting-features.yml}
 * ({@code scrap-conversion.<id>.outputs}).
 *
 * <h2>Why this is not a Bukkit crafting recipe</h2>
 * <p>{@code tf_scrap}'s {@code base_material} (ArsPaper {@code materials.yml}) is
 * {@code IRON_NUGGET} — the exact same base material and count (4, in a 2x2 shape) already used by
 * the existing {@code iron_ingot_scrap} recipe registered from that same file. Both the fork's
 * {@code RecipeManager#resolveIngredient} and TF's own catalog-recipe ingredient resolution collapse
 * a {@code custom:<id>} ingredient to {@code RecipeChoice.MaterialChoice} (base material only, PDC is
 * not consulted — see {@code docs/agent-context/common-traps.md}, "custom: 素材の MaterialChoice
 * 登録が同形のバニラレシピを無言で潰す"). Registering any new crafting-table recipe (shaped or
 * shapeless, TF-side {@code added-recipes} or fork-side) that also consumes 4x {@code IRON_NUGGET}
 * would therefore have an <b>identical input pattern</b> to the existing {@code iron_ingot_scrap}
 * recipe. Bukkit/vanilla can only resolve one recipe for an ambiguous input — whichever recipe is
 * registered first always wins, permanently shadowing the other for every matching input (including
 * plain vanilla iron nuggets, no PDC required). Depending on plugin enable order this would either
 * make the new conversion permanently dead on arrival, or — far worse — permanently break the
 * existing {@code iron_ingot_scrap} recipe for players who already rely on it. Neither outcome is
 * acceptable, so this feature cannot be implemented as any kind of Bukkit {@code Recipe}.
 *
 * <h2>Why this is not {@code PrepareItemCraftEvent} either</h2>
 * <p>Even if the material collision above did not exist, rolling the weighted output inside
 * {@code PrepareItemCraftEvent} (which fires every time the crafting grid's contents are
 * re-evaluated, including just to render the result-slot preview) would let a player see the
 * preview, and only actually take it (triggering {@code CraftItemEvent}) once it happens to show the
 * rare tier they want — reopening/nudging the grid to force a re-roll of the preview and thereby
 * defeating the intended weight distribution ("厳選"). It would also risk the preview and the
 * actually-received item disagreeing if the two events roll independently.
 *
 * <p>Instead this mirrors {@link GachaListener}'s established "hold item, right-click → resolve
 * exactly once, consume, give" interaction pattern: there is no crafting-grid preview at all, so
 * there is nothing to observe before the roll is committed, and the roll only ever happens once per
 * click.
 */
public final class ScrapConversionListener implements Listener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Logger LOG = Logger.getLogger(ScrapConversionListener.class.getName());

    private final CraftingFeaturesConfig features;
    private final CrossPluginItemResolver itemResolver;

    public ScrapConversionListener(CraftingFeaturesConfig features, ItemCatalogConfig itemCatalog,
                                    ItemFactory itemFactory) {
        this.features = Objects.requireNonNull(features, "features");
        this.itemResolver = new CrossPluginItemResolver(
                Objects.requireNonNull(itemCatalog, "itemCatalog"),
                Objects.requireNonNull(itemFactory, "itemFactory"));
    }

    /**
     * No {@code ignoreCancelled} — same reason as {@link GachaListener#onInteract}:
     * {@code RIGHT_CLICK_AIR} is constructed with {@code isCancelled() == true} already (no clicked
     * block), so an {@code ignoreCancelled = true} subscriber never receives it at all. Cancellation
     * is instead checked via {@link PlayerInteractEvent#useItemInHand()}.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            // Paper fires this event for both hands; only the main-hand instance is handled so a
            // source item in the off-hand does not double-convert.
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.useItemInHand() == Event.Result.DENY) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack heldStack = player.getInventory().getItemInMainHand();
        Optional<String> sourceId = CrossPluginItemResolver.idOf(heldStack);
        if (sourceId.isEmpty()) {
            return;
        }
        DisassemblyRule rule = features.scrapConversion(sourceId.get());
        if (rule == null || !rule.hasBaseAmount()) {
            return;
        }
        // A recognized scrap-conversion source item never falls through to vanilla right-click
        // behaviour, regardless of whether the conversion below succeeds (same convention as
        // GachaListener treating a recognized ticket item).
        event.setCancelled(true);
        convertAndConsume(player, heldStack, rule);
    }

    private void convertAndConsume(Player player, ItemStack heldStack, DisassemblyRule rule) {
        int required = (int) Math.max(1, Math.floor(rule.baseAmount()));
        if (heldStack.getAmount() < required) {
            player.sendActionBar(Component.text(
                    "スクラップが" + required + "個必要です。(所持: " + heldStack.getAmount() + "個)",
                    NamedTextColor.RED));
            return;
        }
        // Rolled exactly once, at the moment of consumption — see the class javadoc for why this
        // must never happen inside a crafting-grid preview event.
        DisassemblyOutput chosen = rule.pick(ThreadLocalRandom.current().nextDouble());
        if (chosen == null) {
            LOG.warning("[scrap-conversion] no valid weighted output configured for held item '"
                    + CrossPluginItemResolver.idOf(heldStack).orElse("?") + "'; conversion aborted");
            return;
        }
        Optional<ItemStack> prize = resolveOutput(chosen.item());
        if (prize.isEmpty()) {
            // Guard: never consume the source item when the configured output cannot be resolved/built.
            LOG.warning("[scrap-conversion] output item '" + chosen.item()
                    + "' could not be resolved; source item not consumed");
            player.sendActionBar(Component.text("変換に失敗しました。管理者に連絡してください。", NamedTextColor.RED));
            return;
        }

        consumeAmount(player, heldStack, required);
        giveOrDrop(player, prize.get());
        player.sendMessage(MINI_MESSAGE.deserialize(
                "<green>スクラップを" + required + "個変換しました！ <white><item></white> を獲得しました！</green>",
                Placeholder.component("item", com.trinityforge.progression.CollectionEntryNames.itemName(
                        stripCustomPrefix(chosen.item()), prize.get()))));
    }

    private Optional<ItemStack> resolveOutput(String token) {
        try {
            return itemResolver.create(token, ThreadLocalRandom.current().nextLong(), 0);
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "[scrap-conversion] failed to build output '" + token + "'", ex);
            return Optional.empty();
        }
    }

    private static String stripCustomPrefix(String id) {
        if (id == null) {
            return "";
        }
        String trimmed = id.trim();
        return trimmed.regionMatches(true, 0, "custom:", 0, 7) ? trimmed.substring(7).trim() : trimmed;
    }

    private void giveOrDrop(Player player, ItemStack prize) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(prize);
        if (leftover.isEmpty()) {
            return;
        }
        for (ItemStack remainder : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), remainder);
        }
    }

    private void consumeAmount(Player player, ItemStack heldStack, int amount) {
        int remaining = heldStack.getAmount() - amount;
        if (remaining <= 0) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        ItemStack updated = heldStack.clone();
        updated.setAmount(remaining);
        player.getInventory().setItemInMainHand(updated);
    }
}
