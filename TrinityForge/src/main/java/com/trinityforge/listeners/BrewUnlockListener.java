package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewUnlockGroup;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.stats.CatalogIdentity;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Gated custom brew results ({@code brew-unlocks} in crafting-features.yml), gated by
 * {@code brew:<groupId>} (2026-07-23 動的ID方式改修 §3): unlike a recipe gate, an unreferenced group stays
 * <b>locked</b> — no node placing {@code brew:<groupId>} means {@code isActive} naturally returns
 * {@code false} for every player, so no explicit "unreferenced -&gt; open" branch is needed here.
 *
 * <ul>
 *   <li>Ingredient and at least one bottle base matching a configured recipe require the group's
 *       gate id; unrelated vanilla recipes that happen to share the ingredient remain usable.</li>
 *   <li>{@code custom:&lt;catalogId&gt;} ingredients are not vanilla brew fuels — this listener starts
 *       the brewing stand timer when a matching unlocked recipe is present.</li>
 * </ul>
 */
public final class BrewUnlockListener implements Listener {

    private static final int BREW_TIME_TICKS = 400;
    private static final String GATE_PREFIX = "brew:";

    private final DedicatedEffectsConfig dedicatedEffects;
    private final CraftingFeaturesConfig features;
    private final Plugin plugin;

    public BrewUnlockListener(DedicatedEffectsConfig dedicatedEffects,
                              CraftingFeaturesConfig features,
                              Plugin plugin) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.features = Objects.requireNonNull(features, "features");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        BrewerInventory inv = event.getContents();
        ItemStack ingredient = inv.getIngredient();
        if (ingredient == null || ingredient.getType().isAir()) {
            return;
        }

        List<ItemStack> results = event.getResults();
        List<MatchedSpec> matched = matchSpecs(ingredient).stream()
                .filter(match -> hasMatchingBottleBase(inv, results, match.spec().base()))
                .toList();
        if (matched.isEmpty()) {
            return; // not a gated TF brew recipe
        }

        // スタンド全体で「どれか1つの解放」を見ると、同じ材料を使う別baseの未解放瓶を
        // 混ぜるだけでゲートを迂回できる。各瓶について、そのbaseに一致するspecの少なくとも
        // 1つを解放していることを要求する。BrewEventは瓶単位でcancelできないため、1本でも
        // 未解放ならスタンド全体を止める。
        for (int slot = 0; slot < 3; slot++) {
            PotionMeta probe = potionMetaForSlot(inv, results, slot);
            if (probe == null) {
                continue;
            }
            boolean hasGatedMatch = false;
            boolean hasUnlockedMatch = false;
            for (MatchedSpec m : matched) {
                if (!baseMatches(probe, m.spec().base())) {
                    continue;
                }
                hasGatedMatch = true;
                if (playerHasEffectNear(inv, m.effectId())) {
                    hasUnlockedMatch = true;
                    break;
                }
            }
            if (hasGatedMatch && !hasUnlockedMatch) {
                event.setCancelled(true);
                return;
            }
        }

        for (int slot = 0; slot < 3; slot++) {
            ItemStack bottle = inv.getItem(slot);
            if (bottle == null || bottle.getType().isAir()) {
                continue;
            }
            PotionMeta probe = potionMetaForSlot(inv, results, slot);
            if (probe == null) {
                continue;
            }

            for (MatchedSpec m : matched) {
                if (!playerHasEffectNear(inv, m.effectId())) {
                    continue;
                }
                if (!baseMatches(probe, m.spec().base())) {
                    continue;
                }
                ItemStack custom = makeCustomPotion(bottle.getType(), m.spec());
                while (results.size() <= slot) {
                    results.add(null);
                }
                results.set(slot, custom);
                break;
            }
        }
    }

    /** Start brewing when a custom: catalog ingredient is placed for an unlocked recipe. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrewerClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof BrewerInventory brew)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> tryStartCustomBrew(brew, player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrewerDrag(InventoryDragEvent event) {
        if (!(event.getInventory() instanceof BrewerInventory brew)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> tryStartCustomBrew(brew, player));
    }

    /** Hopper inserts into brewing stands — start custom brew if a nearby player holds the unlock. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        Inventory dest = event.getDestination();
        if (!(dest instanceof BrewerInventory brew)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player unlockHolder = null;
            for (HumanEntity viewer : brew.getViewers()) {
                if (viewer instanceof Player p) {
                    unlockHolder = p;
                    break;
                }
            }
            tryStartCustomBrew(brew, unlockHolder);
        });
    }

    private void tryStartCustomBrew(BrewerInventory brew, Player hintPlayer) {
        ItemStack ingredient = brew.getIngredient();
        if (ingredient == null || ingredient.getType().isAir()) {
            return;
        }
        List<MatchedSpec> matched = matchSpecs(ingredient);
        if (matched.isEmpty()) {
            return;
        }
        // Vanilla materials start brewing on their own; only force-start custom: recipes.
        boolean needsForceStart = false;
        for (MatchedSpec m : matched) {
            if (isCustomKey(m.spec().ingredient())) {
                needsForceStart = true;
                break;
            }
        }
        if (!needsForceStart) {
            return;
        }
        if (!hasUnlockedMatch(brew, hintPlayer, matched)) {
            return;
        }
        boolean anyBottle = false;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack bottle = brew.getItem(slot);
            if (bottle == null || !(bottle.getItemMeta() instanceof PotionMeta meta)) {
                continue;
            }
            for (MatchedSpec m : matched) {
                if (!hasEffect(hintPlayer, brew, m.effectId())) {
                    continue;
                }
                if (baseMatches(meta, m.spec().base())) {
                    anyBottle = true;
                    break;
                }
            }
            if (anyBottle) {
                break;
            }
        }
        if (!anyBottle) {
            return;
        }
        if (!(brew.getHolder() instanceof BrewingStand stand)) {
            return;
        }
        if (stand.getBrewingTime() > 0) {
            return;
        }
        if (stand.getFuelLevel() <= 0) {
            return;
        }
        stand.setBrewingTime(BREW_TIME_TICKS);
        stand.update();
    }

    private boolean hasUnlockedMatch(BrewerInventory brew, Player hint, List<MatchedSpec> matched) {
        for (MatchedSpec m : matched) {
            if (hasEffect(hint, brew, m.effectId())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEffect(Player hint, BrewerInventory brew, String effectId) {
        if (hint != null && dedicatedEffects.isActive(hint, effectId)) {
            return true;
        }
        return playerHasEffectNear(brew, effectId);
    }

    private List<MatchedSpec> matchSpecs(ItemStack ingredient) {
        List<MatchedSpec> out = new ArrayList<>();
        for (Map.Entry<String, BrewUnlockGroup> entry : features.brewUnlocks().entrySet()) {
            String effectId = GATE_PREFIX + entry.getKey();
            for (BrewPotionSpec spec : entry.getValue().potions()) {
                if (ingredientMatches(ingredient, spec.ingredient())) {
                    out.add(new MatchedSpec(effectId, spec));
                }
            }
        }
        return out;
    }

    private static boolean hasMatchingBottleBase(BrewerInventory inv, List<ItemStack> results,
                                                 String baseName) {
        for (int slot = 0; slot < 3; slot++) {
            ItemStack bottle = inv.getItem(slot);
            PotionMeta probe = bottle != null && bottle.getItemMeta() instanceof PotionMeta pm ? pm : null;
            if (probe == null && slot < results.size() && results.get(slot) != null
                    && results.get(slot).getItemMeta() instanceof PotionMeta rm) {
                probe = rm;
            }
            if (probe != null && baseMatches(probe, baseName)) {
                return true;
            }
        }
        return false;
    }

    private static PotionMeta potionMetaForSlot(BrewerInventory inv, List<ItemStack> results, int slot) {
        ItemStack bottle = inv.getItem(slot);
        if (bottle != null && bottle.getItemMeta() instanceof PotionMeta meta) {
            return meta;
        }
        if (slot < results.size()) {
            ItemStack result = results.get(slot);
            if (result != null && result.getItemMeta() instanceof PotionMeta meta) {
                return meta;
            }
        }
        return null;
    }

    private boolean playerHasEffectNear(BrewerInventory inv, String effectId) {
        for (HumanEntity viewer : inv.getViewers()) {
            if (viewer instanceof Player player && dedicatedEffects.isActive(player, effectId)) {
                return true;
            }
        }
        if (!(inv.getHolder() instanceof BrewingStand stand)) {
            return false;
        }
        Location loc = stand.getLocation();
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        for (Player player : loc.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(loc) <= 64.0 // 8 blocks
                    && dedicatedEffects.isActive(player, effectId)) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack makeCustomPotion(Material bottleType, BrewPotionSpec spec) {
        Material type = bottleType == Material.SPLASH_POTION || bottleType == Material.LINGERING_POTION
                ? bottleType : Material.POTION;
        ItemStack out = new ItemStack(type);
        PotionMeta meta = (PotionMeta) out.getItemMeta();
        if (meta == null) {
            return out;
        }
        meta.clearCustomEffects();
        meta.setBasePotionType(PotionType.WATER);
        meta.addCustomEffect(new PotionEffect(spec.type(), spec.durationTicks(), spec.amplifier()), true);
        out.setItemMeta(meta);
        return out;
    }

    private static boolean ingredientMatches(ItemStack ingredient, String expected) {
        if (expected == null || expected.isBlank()) {
            return false;
        }
        String raw = expected.trim();
        if (isCustomKey(raw)) {
            String catalogId = raw.substring(raw.indexOf(':') + 1).trim();
            if (catalogId.isEmpty() || !ingredient.hasItemMeta()) {
                return false;
            }
            Optional<String> id = CatalogIdentity.catalogIdOf(ingredient.getItemMeta());
            return id.filter(catalogId::equals).isPresent();
        }
        Material mat = Material.matchMaterial(raw);
        return mat != null && ingredient.getType() == mat;
    }

    private static boolean isCustomKey(String raw) {
        return raw != null && raw.toLowerCase(Locale.ROOT).startsWith("custom:");
    }

    private static boolean baseMatches(PotionMeta meta, String baseName) {
        if (baseName == null || baseName.isBlank()) {
            return true;
        }
        try {
            PotionType expected = PotionType.valueOf(baseName.trim().toUpperCase(Locale.ROOT));
            PotionType actual = meta.getBasePotionType();
            return actual == expected;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private record MatchedSpec(String effectId, BrewPotionSpec spec) {}
}
