package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.stats.BrewRecipeSupport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/** Merge two potion bottles in a brewing stand (alchemy potion-merge). */
public final class PotionMergeListener implements Listener {

    private static final String UNLOCK = "potion-merge";

    private final DedicatedEffectsConfig dedicatedEffects;
    private final CraftingFeaturesConfig features;

    public PotionMergeListener(DedicatedEffectsConfig dedicatedEffects, CraftingFeaturesConfig features) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.features = Objects.requireNonNull(features, "features");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrewingClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof BrewerInventory)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // 2026-07-26 tier-expand: potion-merge は feature:<id> SCALE化(旧NONE)。valueMax の
        // present/emptyそのものが従来の isActive() 相当のゲートを兼ねる(SCALEはvalue省略時にtier1が
        // 自動補完されるため、既存の単一解放ノードは無改変のまま従来どおり動作する — VeinMiningListener
        // と同じidiom)。
        OptionalDouble tierValue = dedicatedEffects.valueMax(player, UNLOCK);
        if (tierValue.isEmpty()) {
            return;
        }
        int tier = (int) tierValue.getAsDouble();
        // 2026-08-25 修正: ポーションはスタック上限1なので、既にポーションが入っているスロットへ
        // カーソルのポーションを重ねるクリックはバニラでは PLACE_* ではなく SWAP_WITH_CURSOR になる
        // (BrewInsertion#insertedStack と同じ idiom で確認済み)。旧条件は PLACE_* だけを許可しており、
        // 「カーソルにもスロットにもポーションがある」状態と PLACE_* が同時に成立しないため、
        // このリスナーが永久に発火しなかった。SWAP_OFFHAND(Fキー)はそのまま維持する。
        if (event.getClick() != ClickType.SWAP_OFFHAND && event.getAction() != InventoryAction.PLACE_ALL
                && event.getAction() != InventoryAction.PLACE_ONE
                && event.getAction() != InventoryAction.PLACE_SOME
                && event.getAction() != InventoryAction.SWAP_WITH_CURSOR) {
            return;
        }
        ItemStack cursor = event.getCursor();
        ItemStack slot = event.getCurrentItem();
        if (!isPotion(cursor) || !isPotion(slot) || cursor.equals(slot)) {
            return;
        }
        ItemStack merged = mergePotions(slot, cursor, tier);
        if (merged == null) {
            player.sendActionBar(Component.text("ポーションを統合できません。", NamedTextColor.RED));
            return;
        }
        event.setCancelled(true);
        // GTH-03 exploit fix (2026-07-25): the previous code mutated `slot`/`cursor` amounts in place and
        // never re-assigned the slot/cursor, relying on getCurrentItem()/getCursor() being LIVE references
        // back into the inventory/cursor — an assumption every sibling "consume one" implementation in this
        // package (WoodRepairListener#onInventoryClick, GachaListener#consumeOneTicket,
        // XpBottleListener#consumeOneAndGive) deliberately does NOT make; they all clone, decrement the
        // clone, null out at amount<=0, and explicitly re-assign via setCurrentItem/setItemOnCursor. Potions
        // stack at 1, so both operands always hit exactly that zero-amount case. Matching the established
        // pattern closes two problems at once: (1) a possible free duplication if the live-reference
        // assumption ever doesn't hold (the two source potions would survive unconsumed while the merged
        // potion is still handed out), and (2) a leftover amount-0 "ghost" ItemStack sitting in the slot/
        // cursor either way, which other listeners checking "item != null" (instead of "item is empty")
        // could misread as an occupied slot.
        ItemStack newSlot = slot.getAmount() > 1 ? slot.clone() : null;
        if (newSlot != null) {
            newSlot.setAmount(newSlot.getAmount() - 1);
        }
        event.setCurrentItem(newSlot);

        ItemStack newCursor = cursor.getAmount() > 1 ? cursor.clone() : null;
        if (newCursor != null) {
            newCursor.setAmount(newCursor.getAmount() - 1);
        }
        player.setItemOnCursor(newCursor);

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(merged);
        leftover.values().forEach(s -> player.getWorld().dropItemNaturally(player.getLocation(), s));
        player.sendActionBar(Component.text("ポーションを統合しました。", NamedTextColor.GREEN));
    }

    /**
     * 統合できるのは「同一効果のカスタムポーション同士」だけ(2026-08-25 ユーザー決定 / W-115)。
     * 効果を持たないもの同士(素の水入り瓶やバニラのままの醸造品など)は統合不可。
     * 片方だけ効果を持つ・効果の種類集合が食い違う場合も不可(何を統合したか分からなくなるため)。
     */
    private ItemStack mergePotions(ItemStack a, ItemStack b, int tier) {
        if (!(a.getItemMeta() instanceof PotionMeta metaA) || !(b.getItemMeta() instanceof PotionMeta metaB)) {
            return null;
        }
        Set<PotionEffectType> typesA = effectTypes(metaA);
        Set<PotionEffectType> typesB = effectTypes(metaB);
        if (typesA.isEmpty() || typesB.isEmpty() || !typesA.equals(typesB)) {
            return null;
        }
        Map<PotionEffectType, PotionEffect> effects = new LinkedHashMap<>();
        for (PotionEffect eff : metaA.getCustomEffects()) {
            effects.put(eff.getType(), eff);
        }
        for (PotionEffect eff : metaB.getCustomEffects()) {
            effects.merge(eff.getType(), eff, (left, right) ->
                    left.getAmplifier() >= right.getAmplifier() ? left : right);
        }
        if (effects.size() > features.potionMergeMaxEffects(tier)) {
            return null;
        }
        int maxTicks = features.potionMergeMaxDurationSeconds(tier) * 20;
        ItemStack out = new ItemStack(Material.POTION);
        PotionMeta outMeta = (PotionMeta) out.getItemMeta();
        List<PotionEffect> applied = new ArrayList<>();
        for (PotionEffect eff : effects.values()) {
            applied.add(new PotionEffect(eff.getType(), Math.min(maxTicks, eff.getDuration()), eff.getAmplifier()));
        }
        // TFの他のカスタム効果ポーション組み立て(BrewRecipeSupport#customPotion /
        // PotionQualityListener#applyQuality)と同じ既存パターンに揃える: baseをWATERへ倒して
        // 全部custom effectsで表現し、統合版で水入り瓶に見えないよう色も焼く(W-113と同根の問題)。
        outMeta.setBasePotionType(PotionType.WATER);
        outMeta.clearCustomEffects();
        for (PotionEffect eff : applied) {
            outMeta.addCustomEffect(eff, true);
        }
        BrewRecipeSupport.applyMixedColor(outMeta, applied);
        if (!outMeta.hasDisplayName()) {
            outMeta.displayName(BrewRecipeSupport.potionDisplayName(Material.POTION, applied));
        }
        out.setItemMeta(outMeta);
        return out;
    }

    private static Set<PotionEffectType> effectTypes(PotionMeta meta) {
        Set<PotionEffectType> types = new java.util.LinkedHashSet<>();
        for (PotionEffect eff : meta.getCustomEffects()) {
            types.add(eff.getType());
        }
        return types;
    }

    private static boolean isPotion(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        return stack.getType() == Material.POTION || stack.getType() == Material.SPLASH_POTION
                || stack.getType() == Material.LINGERING_POTION;
    }
}
