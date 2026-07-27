package com.github.klee319.dpschecker.gui;

import com.github.klee319.dpschecker.calculator.DPSCalculator;
import com.github.klee319.dpschecker.calculator.DamageStats;
import com.github.klee319.dpschecker.dummy.DummyEntity;
import com.github.klee319.dpschecker.dummy.TfDefenseStat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class MainMenuGUI implements InventoryHolder {

    private final Inventory inventory;
    private final DummyEntity dummy;

    public MainMenuGUI(JavaPlugin plugin, DummyEntity dummy) {
        this.dummy = dummy;
        this.inventory = Bukkit.createInventory(this, 27,
                Component.text("DPS Dummy - メインメニュー", NamedTextColor.DARK_PURPLE));
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

        // Overview info (slot 4) — DPS removed; use /dps on <name> for accurate measurement.
        ItemStack info = new ItemStack(Material.PLAYER_HEAD);
        info.editMeta(meta -> {
            meta.displayName(noItalic(Component.text("ダミー情報", NamedTextColor.GOLD)));
            meta.lore(List.of(
                    noItalic(Component.text(String.format("HP: %.1f / %.1f", getHealth(), dummy.getMaxHp()), NamedTextColor.RED)),
                    noItalic(Component.text(String.format("防御率: %.0f%% (物理魔法共通)",
                            dummy.getDefenseProfile().valueOf(TfDefenseStat.DEFENSE_RATE) * 100),
                            NamedTextColor.BLUE)),
                    noItalic(Component.text(String.format("耐性: 物理 %.0f%% / 魔法 %.0f%%",
                            dummy.getDefenseProfile().valueOf(TfDefenseStat.PHYS_RESISTANCE) * 100,
                            dummy.getDefenseProfile().valueOf(TfDefenseStat.MAGIC_RESISTANCE) * 100),
                            NamedTextColor.LIGHT_PURPLE)),
                    noItalic(Component.text(String.format("回避率: %.0f%%",
                            dummy.getDefenseProfile().valueOf(TfDefenseStat.DODGE_CHANCE) * 100),
                            NamedTextColor.AQUA)),
                    noItalic(Component.text(String.format("守備力+防具強度: %.1f (flat)",
                            dummy.getDefenseProfile().valueOf(TfDefenseStat.FLAT_DEFENSE)
                                    + dummy.getDefenseProfile().valueOf(TfDefenseStat.ARMOR_STRENGTH)),
                            NamedTextColor.YELLOW)),
                    noItalic(Component.text(dummy.isUndead() ? "アンデッド: ON" : "アンデッド: OFF",
                            dummy.isUndead() ? NamedTextColor.DARK_GREEN : NamedTextColor.GOLD)),
                    Component.empty(),
                    noItalic(Component.text(String.format("総ヒット数: %d", totalStats.hitCount()), NamedTextColor.WHITE))
            ));
        });
        inventory.setItem(4, info);

        // Stats button (slot 11)
        ItemStack statsBtn = new ItemStack(Material.BOOK);
        statsBtn.editMeta(meta -> {
            meta.displayName(noItalic(Component.text("ダメージ統計", NamedTextColor.GREEN)));
            meta.lore(List.of(
                    noItalic(Component.text("クリックして統計を表示", NamedTextColor.GRAY))
            ));
        });
        inventory.setItem(11, statsBtn);

        // Equipment button (slot 13)
        ItemStack equipBtn = new ItemStack(Material.ARMOR_STAND);
        equipBtn.editMeta(meta -> {
            meta.displayName(noItalic(Component.text("装備管理", NamedTextColor.AQUA)));
            meta.lore(List.of(
                    noItalic(Component.text("クリックして装備を管理", NamedTextColor.GRAY))
            ));
        });
        inventory.setItem(13, equipBtn);

        // Settings button (slot 15)
        ItemStack settingsBtn = new ItemStack(Material.COMPARATOR);
        settingsBtn.editMeta(meta -> {
            meta.displayName(noItalic(Component.text("設定", NamedTextColor.YELLOW)));
            meta.lore(List.of(
                    noItalic(Component.text("クリックしてHP・防御力を設定", NamedTextColor.GRAY))
            ));
        });
        inventory.setItem(15, settingsBtn);

        // TF defense button (slot 17)
        ItemStack tfDefenseBtn = new ItemStack(Material.NETHERITE_CHESTPLATE);
        tfDefenseBtn.editMeta(meta -> {
            meta.displayName(noItalic(Component.text("TF防御設定", NamedTextColor.DARK_BLUE)));
            meta.lore(List.of(
                    noItalic(Component.text("物理/魔法の全防御ステを設定", NamedTextColor.GRAY))
            ));
        });
        inventory.setItem(17, tfDefenseBtn);

        // Effects button (slot 20)
        ItemStack effectsBtn = new ItemStack(Material.BREWING_STAND);
        effectsBtn.editMeta(meta -> {
            meta.displayName(noItalic(Component.text("エフェクト一覧", NamedTextColor.LIGHT_PURPLE)));
            meta.lore(List.of(
                    noItalic(Component.text("クリックして現在のエフェクトを確認", NamedTextColor.GRAY))
            ));
        });
        inventory.setItem(20, effectsBtn);

        // Reset button (slot 22)
        ItemStack resetBtn = new ItemStack(Material.TNT);
        resetBtn.editMeta(meta -> {
            meta.displayName(noItalic(Component.text("統計リセット", NamedTextColor.RED)));
            meta.lore(List.of(
                    noItalic(Component.text("クリックでダメージ記録をリセット", NamedTextColor.GRAY))
            ));
        });
        inventory.setItem(22, resetBtn);

        // Heal button (slot 24)
        ItemStack healBtn = new ItemStack(Material.GOLDEN_APPLE);
        healBtn.editMeta(meta -> {
            meta.displayName(noItalic(Component.text("全回復", NamedTextColor.GREEN)));
            meta.lore(List.of(
                    noItalic(Component.text("クリックでHPを全回復", NamedTextColor.GRAY))
            ));
        });
        inventory.setItem(24, healBtn);
    }

    private double getHealth() {
        if (dummy.getEntity() != null && !dummy.getEntity().isDead()) {
            return dummy.getEntity().getHealth();
        }
        return 0;
    }

    public void handleClick(Player player, int slot, JavaPlugin plugin) {
        switch (slot) {
            case 11 -> player.openInventory(new StatsGUI(plugin, dummy).getInventory());
            case 13 -> player.openInventory(new EquipmentGUI(plugin, dummy).getInventory());
            case 15 -> player.openInventory(new SettingsGUI(plugin, dummy).getInventory());
            case 17 -> player.openInventory(new TfDefenseGUI(plugin, dummy).getInventory());
            case 20 -> player.openInventory(new EffectsGUI(plugin, dummy).getInventory());
            case 22 -> {
                dummy.resetRecords();
                player.sendMessage(Component.text("[DPSChecker] ", NamedTextColor.GOLD)
                        .append(Component.text("統計をリセットしました。", NamedTextColor.GREEN)));
                player.openInventory(new MainMenuGUI(plugin, dummy).getInventory());
            }
            case 24 -> {
                dummy.healToFull();
                player.sendMessage(Component.text("[DPSChecker] ", NamedTextColor.GOLD)
                        .append(Component.text("HPを全回復しました。", NamedTextColor.GREEN)));
                player.openInventory(new MainMenuGUI(plugin, dummy).getInventory());
            }
        }
    }

    static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public DummyEntity getDummy() { return dummy; }

    @Override
    public Inventory getInventory() { return inventory; }
}
