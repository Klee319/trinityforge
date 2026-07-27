package com.github.klee319.dpschecker.gui;

import com.github.klee319.dpschecker.calculator.DPSCalculator;
import com.github.klee319.dpschecker.calculator.DamageStats;
import com.github.klee319.dpschecker.dummy.DummyEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.klee319.dpschecker.gui.MainMenuGUI.noItalic;

public class StatsGUI implements InventoryHolder {

    private final Inventory inventory;
    private final DummyEntity dummy;

    public StatsGUI(JavaPlugin plugin, DummyEntity dummy) {
        this.dummy = dummy;
        this.inventory = Bukkit.createInventory(this, 54,
                Component.text("DPS Dummy - ダメージ統計", NamedTextColor.DARK_GREEN));
        initializeItems(plugin);
    }

    private void initializeItems(JavaPlugin plugin) {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        filler.editMeta(meta -> meta.displayName(Component.text("")));
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        int windowSeconds = plugin.getConfig().getInt("dps.window-seconds", 10);
        DamageStats totalStats = DPSCalculator.buildTotalStats(dummy.getDamageRecords(), windowSeconds);

        // Total stats (slot 4) — DPS removed; use /dps on <name> for accurate session DPS.
        ItemStack totalItem = new ItemStack(Material.GOLDEN_SWORD);
        totalItem.editMeta(meta -> {
            meta.displayName(noItalic(Component.text("総合ダメージ統計", NamedTextColor.GOLD)));
            meta.lore(List.of(
                    noItalic(Component.text(String.format("平均: %.2f", totalStats.average()), NamedTextColor.YELLOW)),
                    noItalic(Component.text(String.format("最大: %.2f", totalStats.max()), NamedTextColor.GREEN)),
                    noItalic(Component.text(String.format("最小: %.2f", totalStats.min()), NamedTextColor.AQUA)),
                    noItalic(Component.text(String.format("合計: %.2f", totalStats.total()), NamedTextColor.WHITE)),
                    noItalic(Component.text(String.format("ヒット数: %d", totalStats.hitCount()), NamedTextColor.GRAY)),
                    Component.empty(),
                    noItalic(Component.text("DPSは /dps on <名前> で計測", NamedTextColor.DARK_GRAY))
            ));
        });
        inventory.setItem(4, totalItem);

        // Per-type stats — use precomputed valid slot positions
        List<Integer> validSlots = List.of(
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43);
        List<DamageStats> allStats = DPSCalculator.buildAllStats(dummy.getDamageRecords(), windowSeconds);
        int slotIndex = 0;
        for (DamageStats stats : allStats) {
            if (slotIndex >= validSlots.size()) break;
            int slot = validSlots.get(slotIndex++);

            Material mat = getMaterialForCause(stats.cause());
            String causeName = translateCause(stats.cause());

            ItemStack item = new ItemStack(mat);
            item.editMeta(meta -> {
                meta.displayName(noItalic(Component.text(causeName, NamedTextColor.YELLOW)));
                meta.lore(List.of(
                        noItalic(Component.text(String.format("平均: %.2f", stats.average()), NamedTextColor.YELLOW)),
                        noItalic(Component.text(String.format("最大: %.2f", stats.max()), NamedTextColor.GREEN)),
                        noItalic(Component.text(String.format("最小: %.2f", stats.min()), NamedTextColor.AQUA)),
                        noItalic(Component.text(String.format("合計: %.2f", stats.total()), NamedTextColor.WHITE)),
                        noItalic(Component.text(String.format("ヒット数: %d", stats.hitCount()), NamedTextColor.GRAY))
                ));
            });
            inventory.setItem(slot, item);
        }

        // Back button (slot 49)
        ItemStack back = new ItemStack(Material.ARROW);
        back.editMeta(meta -> meta.displayName(noItalic(Component.text("戻る", NamedTextColor.WHITE))));
        inventory.setItem(49, back);
    }

    public void handleClick(Player player, int slot, JavaPlugin plugin) {
        if (slot == 49) {
            player.openInventory(new MainMenuGUI(plugin, dummy).getInventory());
        }
    }

    private Material getMaterialForCause(DamageCause cause) {
        return switch (cause) {
            case ENTITY_ATTACK -> Material.IRON_SWORD;
            case ENTITY_SWEEP_ATTACK -> Material.DIAMOND_SWORD;
            case PROJECTILE -> Material.ARROW;
            case FIRE, FIRE_TICK -> Material.FIRE_CHARGE;
            case LAVA -> Material.LAVA_BUCKET;
            case POISON -> Material.SPIDER_EYE;
            case MAGIC -> Material.SPLASH_POTION;
            case WITHER -> Material.WITHER_SKELETON_SKULL;
            case THORNS -> Material.CACTUS;
            case FALL -> Material.FEATHER;
            case LIGHTNING -> Material.LIGHTNING_ROD;
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> Material.TNT;
            case CONTACT -> Material.SWEET_BERRIES;
            case CRAMMING -> Material.PISTON;
            case FREEZE -> Material.POWDER_SNOW_BUCKET;
            case SONIC_BOOM -> Material.SCULK_SHRIEKER;
            default -> Material.PAPER;
        };
    }

    private String translateCause(DamageCause cause) {
        return switch (cause) {
            case ENTITY_ATTACK -> "近接攻撃";
            case ENTITY_SWEEP_ATTACK -> "範囲攻撃";
            case PROJECTILE -> "飛び道具";
            case FIRE -> "炎上";
            case FIRE_TICK -> "炎上ダメージ";
            case LAVA -> "溶岩";
            case POISON -> "毒";
            case MAGIC -> "魔法";
            case WITHER -> "ウィザー効果";
            case THORNS -> "棘の鎧";
            case FALL -> "落下";
            case LIGHTNING -> "雷";
            case ENTITY_EXPLOSION -> "爆発(エンティティ)";
            case BLOCK_EXPLOSION -> "爆発(ブロック)";
            case CONTACT -> "接触";
            case CRAMMING -> "圧死";
            case FREEZE -> "凍結";
            case SONIC_BOOM -> "ソニックブーム";
            case CUSTOM -> "カスタム";
            default -> cause.name();
        };
    }

    public DummyEntity getDummy() { return dummy; }

    @Override
    public Inventory getInventory() { return inventory; }
}
