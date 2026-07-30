package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig.CoatingMaterial;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.WeaponBaseFormula;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CatalogIdentity;
import com.trinityforge.stats.DerivedItemStats;
import com.trinityforge.stats.StatKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Source-gem weapon coating (alchemy weapon-coating-unlock / coating_charges_bonus stat). */
public final class WeaponCoatingListener implements Listener {

    private static final String UNLOCK = "weapon-coating-unlock";
    // 2026-07-28(数値のギミックyml集約): feature:coating-stack-increase から通常stat coating_charges_bonus
    // へ降格。単純加算(全保持ノード分の合算)しかしていなかったため、PlayerStatAggregator#totalOf経由の
    // 通常ステ読み取りへ置換した(/tf stats・loreにも表示されるようになる)。
    private static final String STACK_BONUS_KEY = StatKeys.canonical("coating-charges-bonus");
    // アイテム単体(メインハンドの武器そのもの)が持つ coating-charges ステ。防具4部位まで合算する
    // 総合ステ(PlayerCombatAggregate)ではなく、その武器固有の回数として扱うため、DerivedItemStats.resolve
    // でメインハンドの武器1点だけを解決して読む(他のアイテム単体ステ消費者と同じ経路。例:
    // CombatListener#onEntityDamageByEntity の DerivedItemStats.resolve(mainhand, ...) 呼び出し)。
    private static final String ITEM_COATING_CHARGES_KEY = StatKeys.canonical("coating-charges");

    private final DedicatedEffectsConfig dedicatedEffects;
    private final CraftingFeaturesConfig features;
    private final ItemStatsConfig itemStats;
    private final WeaponBaseFormula weaponBaseFormula;
    private final com.trinityforge.combat.PlayerStatAggregator aggregator;

    public WeaponCoatingListener(DedicatedEffectsConfig dedicatedEffects,
                                 CraftingFeaturesConfig features,
                                 ItemStatsConfig itemStats,
                                 WeaponBaseFormula weaponBaseFormula,
                                 com.trinityforge.combat.PlayerStatAggregator aggregator) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.features = Objects.requireNonNull(features, "features");
        this.itemStats = Objects.requireNonNull(itemStats, "itemStats");
        this.weaponBaseFormula = Objects.requireNonNull(weaponBaseFormula, "weaponBaseFormula");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (event.getHand() != EquipmentSlot.HAND
                || (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Player player = event.getPlayer();
        if (!dedicatedEffects.isActive(player, UNLOCK)) {
            return;
        }
        ItemStack weapon = player.getInventory().getItemInMainHand();
        ItemStack gem = player.getInventory().getItemInOffHand();
        if (!isWeapon(weapon) || gem == null || gem.getType().isAir() || !gem.hasItemMeta()) {
            return;
        }
        Optional<String> catalogId = CatalogIdentity.catalogIdOf(gem.getItemMeta());
        if (catalogId.isEmpty()) {
            return;
        }
        CoatingMaterial mat = features.coatingMaterial(catalogId.get());
        if (mat == null) {
            return;
        }
        event.setCancelled(true);

        // Stricter of (base-max-stacks, material max-stacks), then the coating_charges_bonus stat
        // (perk-wide, all sources combined via PlayerStatAggregator) PLUS the weapon's own coating-charges
        // item stat (item-wide, see itemCoatingChargesBonus). Coating charges are no longer a native
        // reward: the canonical alchemy unlock supplies this stat, so every supported weapon class
        // receives the same configured bonus.
        int perkBonus = (int) Math.max(0.0, aggregator.aggregate(player).totalOf(STACK_BONUS_KEY));
        int itemBonus = itemCoatingChargesBonus(weapon, itemStats, weaponBaseFormula);
        int maxStacks = Math.min(features.coatingBaseMaxStacks(), mat.maxStacks())
                + Math.max(0, perkBonus) + itemBonus;
        if (maxStacks < 1) {
            maxStacks = 1;
        }
        ItemMeta meta = weapon.getItemMeta();
        if (meta == null) {
            return;
        }
        ItemData data = ItemData.of(meta);
        int stacks = data.coatingStacks();
        if (stacks >= maxStacks) {
            player.sendActionBar(Component.text("コーティング上限に達しています。", NamedTextColor.RED));
            return;
        }
        gem.setAmount(gem.getAmount() - 1);
        data.setCoatingStacks(stacks + 1);
        data.setCoatingFlatDamage(data.coatingFlatDamage() + mat.bonusDamage());
        weapon.setItemMeta(meta);
        player.sendActionBar(Component.text(
                "武器をコーティングしました (" + (stacks + 1) + "/" + maxStacks + ")",
                NamedTextColor.GREEN));
    }

    private static boolean isWeapon(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        return switch (stack.getType()) {
            case WOODEN_SWORD, STONE_SWORD, IRON_SWORD, GOLDEN_SWORD, DIAMOND_SWORD, NETHERITE_SWORD,
                 WOODEN_AXE, STONE_AXE, IRON_AXE, GOLDEN_AXE, DIAMOND_AXE, NETHERITE_AXE,
                 BOW, CROSSBOW, TRIDENT -> true;
            default -> false;
        };
    }

    /**
     * The weapon's OWN {@code coating-charges} item stat (mainhand item only — not summed across the 4
     * armor slots like {@code PlayerCombatAggregate}, since a coating capacity bonus is specific to that
     * one weapon, not a player-wide total). Resolved via {@link DerivedItemStats#resolve}, the same
     * per-item stat resolution path used by {@link com.trinityforge.listeners.CombatListener} for its
     * mainhand-only reads. Floored to an int; negative values clamp to 0. Fail-safe: a null/air weapon,
     * or a null config, yields 0.
     */
    static int itemCoatingChargesBonus(ItemStack weapon, ItemStatsConfig itemStats,
                                       WeaponBaseFormula weaponBaseFormula) {
        if (weapon == null || weapon.getType().isAir() || itemStats == null || weaponBaseFormula == null) {
            return 0;
        }
        Map<String, Double> derived = DerivedItemStats.resolve(weapon, itemStats, weaponBaseFormula);
        double value = derived.getOrDefault(ITEM_COATING_CHARGES_KEY, 0.0);
        return Math.max(0, (int) Math.floor(value));
    }

    /** Called from combat stat bridge path via ItemData stacks / flat → flat-bonus-damage. */
    public static double coatingFlatBonus(ItemStack weapon, CraftingFeaturesConfig features) {
        if (weapon == null || !weapon.hasItemMeta() || features == null) {
            return 0.0;
        }
        ItemData data = ItemData.of(weapon.getItemMeta());
        double flat = data.coatingFlatDamage();
        if (flat > 0.0) {
            return flat;
        }
        // Legacy items: stacks only, no per-material flat stamp
        int stacks = data.coatingStacks();
        return stacks * features.coatingLegacyBonusPerStack();
    }
}
