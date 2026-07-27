package com.github.klee319.dpschecker.gui;

import com.github.klee319.dpschecker.dummy.DummyEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.klee319.dpschecker.gui.MainMenuGUI.noItalic;

public class SettingsGUI implements InventoryHolder {

    private static final int SLOT_HP = 13;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_TF_DEFENSE = 16;
    private static final int SLOT_UNDEAD = 15;
    private static final int SLOT_BACK = 18;
    private static final int SLOT_RESPAWN = 26;

    /** HP桁ボタン: ±1 / ±10 / ±100 / ±1000（TF防御GUIと同系統）。 */
    private static final double[] HP_DIGITS = {1.0, 10.0, 100.0, 1000.0};
    private static final int[] HP_DIGIT_COLUMNS = {1, 2, 3, 4};
    private static final double HP_MIN = 1.0;
    private static final double HP_MAX = 2048.0;

    private final Inventory inventory;
    private final DummyEntity dummy;
    private final Map<Integer, Double> hpAdjustSlots = new HashMap<>();

    public SettingsGUI(JavaPlugin plugin, DummyEntity dummy) {
        this.dummy = dummy;
        this.inventory = Bukkit.createInventory(this, 27,
                Component.text("DPS Dummy - 設定", NamedTextColor.DARK_RED));
        initializeItems();
    }

    private void initializeItems() {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        filler.editMeta(meta -> meta.displayName(Component.text("")));
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        // 上段プラス / 下段マイナス（同一カラムで上下対応）
        for (int i = 0; i < HP_DIGITS.length; i++) {
            int col = HP_DIGIT_COLUMNS[i];
            double digit = HP_DIGITS[i];
            int plusSlot = col;
            int minusSlot = 18 + col;
            hpAdjustSlots.put(plusSlot, digit);
            hpAdjustSlots.put(minusSlot, -digit);
            inventory.setItem(plusSlot, digitButton(digit, true));
            inventory.setItem(minusSlot, digitButton(digit, false));
        }

        ItemStack hpItem = new ItemStack(Material.RED_DYE);
        hpItem.editMeta(meta -> {
            meta.displayName(noItalic(Component.text("最大HP", NamedTextColor.RED)));
            meta.lore(List.of(
                    noItalic(Component.text(String.format("現在: %.1f", dummy.getMaxHp()), NamedTextColor.WHITE)),
                    noItalic(Component.text(String.format("範囲: %.0f ~ %.0f", HP_MIN, HP_MAX), NamedTextColor.GRAY)),
                    noItalic(Component.text("上下の桁ボタンで ±1/10/100/1000", NamedTextColor.DARK_GRAY))
            ));
        });
        inventory.setItem(SLOT_HP, hpItem);

        ItemStack info = new ItemStack(Material.BOOK);
        info.editMeta(meta -> {
            meta.displayName(noItalic(Component.text("ダミー設定", NamedTextColor.GOLD)));
            meta.lore(List.of(
                    noItalic(Component.text("HP・アンデッド・リスポーン", NamedTextColor.WHITE)),
                    noItalic(Component.text("TF防御は専用GUIから設定", NamedTextColor.GRAY))
            ));
        });
        inventory.setItem(SLOT_INFO, info);

        ItemStack tfDefense = new ItemStack(Material.NETHERITE_CHESTPLATE);
        tfDefense.editMeta(meta -> {
            meta.displayName(noItalic(Component.text("TF防御設定を開く", NamedTextColor.DARK_BLUE)));
            meta.lore(List.of(
                    noItalic(Component.text("物理/魔法の全防御ステ", NamedTextColor.GRAY))
            ));
        });
        inventory.setItem(SLOT_TF_DEFENSE, tfDefense);

        ItemStack undeadItem = new ItemStack(
                dummy.isUndead() ? Material.ZOMBIE_HEAD : Material.PLAYER_HEAD);
        undeadItem.editMeta(meta -> {
            meta.displayName(noItalic(Component.text(
                    dummy.isUndead() ? "アンデッド: ON" : "アンデッド: OFF",
                    dummy.isUndead() ? NamedTextColor.DARK_GREEN : NamedTextColor.GOLD)));
            meta.lore(List.of(
                    noItalic(Component.text("クリックで切替", NamedTextColor.GRAY)),
                    noItalic(Component.text(
                            dummy.isUndead()
                                    ? "治癒系バフでダメージ / 毒は無効"
                                    : "治癒系バフで回復 / 毒でダメージ",
                            NamedTextColor.DARK_GRAY))
            ));
        });
        inventory.setItem(SLOT_UNDEAD, undeadItem);

        ItemStack respawnItem = new ItemStack(Material.END_CRYSTAL);
        respawnItem.editMeta(meta -> {
            meta.displayName(noItalic(Component.text("リスポーン", NamedTextColor.LIGHT_PURPLE)));
            meta.lore(List.of(
                    noItalic(Component.text("クリックでダミーをリスポーン", NamedTextColor.GRAY)),
                    noItalic(Component.text("(HP全回復 + 統計リセット)", NamedTextColor.GRAY))
            ));
        });
        inventory.setItem(SLOT_RESPAWN, respawnItem);

        ItemStack back = new ItemStack(Material.ARROW);
        back.editMeta(meta -> meta.displayName(noItalic(Component.text("戻る", NamedTextColor.WHITE))));
        inventory.setItem(SLOT_BACK, back);
    }

    private ItemStack digitButton(double magnitude, boolean plus) {
        ItemStack item = new ItemStack(plus ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        String label = (plus ? "+" : "-") + String.format("%.0f", magnitude);
        item.editMeta(meta -> meta.displayName(noItalic(Component.text(label,
                plus ? NamedTextColor.GREEN : NamedTextColor.RED))));
        return item;
    }

    public void handleClick(Player player, int slot, JavaPlugin plugin) {
        Double hpDelta = hpAdjustSlots.get(slot);
        if (hpDelta != null) {
            adjustHp(player, plugin, hpDelta);
            return;
        }

        switch (slot) {
            case SLOT_TF_DEFENSE -> player.openInventory(new TfDefenseGUI(plugin, dummy).getInventory());
            case SLOT_UNDEAD -> {
                dummy.setUndead(!dummy.isUndead());
                player.openInventory(new SettingsGUI(plugin, dummy).getInventory());
            }
            case SLOT_RESPAWN -> {
                dummy.respawn();
                player.sendMessage(Component.text("[DPSChecker] ", NamedTextColor.GOLD)
                        .append(Component.text("ダミーをリスポーンしました。", NamedTextColor.GREEN)));
                player.closeInventory();
            }
            case SLOT_BACK -> player.openInventory(new MainMenuGUI(plugin, dummy).getInventory());
        }
    }

    private void adjustHp(Player player, JavaPlugin plugin, double delta) {
        double next = clamp(dummy.getMaxHp() + delta, HP_MIN, HP_MAX);
        dummy.setMaxHp(next);
        dummy.healToFull();
        player.openInventory(new SettingsGUI(plugin, dummy).getInventory());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public DummyEntity getDummy() { return dummy; }

    @Override
    public Inventory getInventory() { return inventory; }
}
