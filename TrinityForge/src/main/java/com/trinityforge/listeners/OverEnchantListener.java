package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Over-cap enchant levels per {@code overenchant:<id>} profile ({@code crafting-features.yml}
 * over-enchant.&lt;id&gt;.enchants), gated by the {@code overenchant:} dynamic gate prefix (2026-07-23
 * 動的ID方式改修 §3). Profile ids are already arbitrary/config-defined (no more special-cased
 * over-enchant-1/2/3); this listener just prefixes the id before the gate lookup.
 */
public final class OverEnchantListener implements Listener {

    private static final String GATE_PREFIX = "overenchant:";

    private final DedicatedEffectsConfig dedicatedEffects;
    private final CraftingFeaturesConfig features;

    public OverEnchantListener(DedicatedEffectsConfig dedicatedEffects, CraftingFeaturesConfig features) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.features = Objects.requireNonNull(features, "features");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (!(event.getEnchanter() instanceof Player player)) {
            return;
        }
        Map<Enchantment, Integer> enchants = event.getEnchantsToAdd();
        enchants.replaceAll((ench, level) -> {
            int overMax = maxLevel(player, ench);
            if (overMax <= 0) {
                return level;
            }
            int vanillaMax = ench.getMaxLevel();
            // At vanilla cap, allow +1 toward the profile absolute max (not a full jump).
            if (level >= vanillaMax && overMax > vanillaMax) {
                return Math.min(overMax, level + 1);
            }
            return Math.min(level, overMax);
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvil(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        ItemStack first = event.getInventory().getFirstItem();
        ItemStack second = event.getInventory().getSecondItem();
        ItemStack result = event.getResult();
        if (first == null || result == null) {
            return;
        }

        boolean changed = false;
        ItemStack out = result.clone();

        Set<Enchantment> candidates = new HashSet<>();
        candidates.addAll(enchantKeys(first));
        if (second != null) {
            candidates.addAll(enchantKeys(second));
        }
        candidates.addAll(enchantKeys(out));

        for (Enchantment ench : candidates) {
            int overMax = maxLevel(player, ench);
            if (overMax <= ench.getMaxLevel()) {
                continue;
            }
            if (!canCarry(out, ench)) {
                // 実サーバ報告「ツルハシに射撃ダメージが付く」(2026-08-04)の真因。
                // 候補は first / second / result の3つから集めるので、金床の2枠目に置いた
                // エンチャント本(例: 射撃ダメージ増加)のエンチャントも必ず候補に入る。
                // 下の合成は b>0 なら a=0 でも「combined = max(a,b)」を結果へ書き込み、
                // 書き込みは addUnsafeEnchantment なので【対象種別の検査を素通りする】。
                // 結果、ツルハシ + 射撃ダメージ増加の本 が「射撃ダメージ付きツルハシ」になっていた。
                // バニラの金床は canEnchant を見て弾くので、ここでも同じ判定で門を作る。
                // 既に載ってしまっている分をここで剥がしはしない(他プラグイン/管理付与を壊さないため。
                // 剥がすなら別途、移行用の掃除として明示的にやる)。
                continue;
            }
            int a = enchantLevel(first, ench);
            int b = second == null ? 0 : enchantLevel(second, ench);
            if (a <= 0 && b <= 0) {
                // Still clamp result if somehow above cap
                int current = enchantLevel(out, ench);
                if (current > overMax) {
                    setEnchantLevel(out, ench, overMax);
                    changed = true;
                }
                continue;
            }
            int combined;
            if (a == b && a > 0) {
                combined = a + 1;
            } else {
                combined = Math.max(a, b);
            }
            combined = Math.min(combined, overMax);
            int current = enchantLevel(out, ench);
            if (combined > current || current > overMax) {
                setEnchantLevel(out, ench, Math.min(combined, overMax));
                changed = true;
            }
        }

        if (changed) {
            event.setResult(out);
        }
    }

    private int maxLevel(Player player, Enchantment ench) {
        return features.overEnchantMaxLevel(id -> dedicatedEffects.isActive(player, GATE_PREFIX + id), ench);
    }

    /**
     * そのアイテムが本来このエンチャントを載せてよいか。バニラの金床と同じ判定
     * ({@link Enchantment#canEnchantItem}) を使う。
     *
     * <p>エンチャント本({@link EnchantmentStorageMeta})だけは例外で常に true を返す。本は
     * 「どの装備向けのエンチャントでも保管できる入れ物」であって装備ではないため、
     * {@code canEnchantItem} は本に対してほぼ常に false を返す。ここで弾くと
     * 本 + 本 の合成で上限突破が効かなくなる(＝この機能そのものが死ぬ)。
     */
    private static boolean canCarry(ItemStack stack, Enchantment ench) {
        if (stack == null || ench == null) {
            return false;
        }
        if (stack.getItemMeta() instanceof EnchantmentStorageMeta) {
            return true;
        }
        return ench.canEnchantItem(stack);
    }

    private static Set<Enchantment> enchantKeys(ItemStack stack) {
        Set<Enchantment> keys = new HashSet<>(stack.getEnchantments().keySet());
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage) {
            keys.addAll(storage.getStoredEnchants().keySet());
        }
        return keys;
    }

    private static int enchantLevel(ItemStack stack, Enchantment ench) {
        int level = stack.getEnchantmentLevel(ench);
        if (level > 0) {
            return level;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage) {
            return storage.getStoredEnchantLevel(ench);
        }
        return 0;
    }

    private static void setEnchantLevel(ItemStack stack, Enchantment ench, int level) {
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage) {
            if (level <= 0) {
                storage.removeStoredEnchant(ench);
            } else {
                storage.addStoredEnchant(ench, level, true);
            }
            stack.setItemMeta(storage);
            return;
        }
        stack.removeEnchantment(ench);
        if (level > 0) {
            stack.addUnsafeEnchantment(ench, level);
        }
    }
}
