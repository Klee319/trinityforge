package com.trinityforge.durability;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 手書きで装備をスロットから消すときに、バニラと同じ {@link PlayerItemBreakEvent} を撃つ。
 *
 * <p>{@code HumanEntity#damageItemStack} は MockBukkit 未実装なので耐久消費は
 * {@code Damageable} 直操作になっている。その経路はイベントを飛ばない。
 * ArsPaper は装着スレッドの返却をこのイベントだけ見ている。
 */
public final class ItemBreakSignal {

    private ItemBreakSignal() {
    }

    public static void fire(Player player, ItemStack broken) {
        if (player == null || broken == null || broken.getType().isAir()) {
            return;
        }
        player.getServer().getPluginManager().callEvent(
                new PlayerItemBreakEvent(player, broken.clone()));
    }
}
