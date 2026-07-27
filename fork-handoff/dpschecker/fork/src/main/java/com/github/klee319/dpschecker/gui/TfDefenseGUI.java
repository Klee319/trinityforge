package com.github.klee319.dpschecker.gui;

import com.github.klee319.dpschecker.dummy.DummyEntity;
import com.github.klee319.dpschecker.dummy.TfDefenseStat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.klee319.dpschecker.gui.MainMenuGUI.noItalic;

/**
 * TrinityForge defender profile editor.
 *
 * <p>操作モデル: 上段でステータスを1つ選択し、中央にその現在値を表示、下段の「桁ボタン」で
 * 位ごとに増減する(±1/10/100/1000、％系は±1%/10%)。従来の全ステ [−|値|＋] 3連から、
 * 選択式＋桁調整へ刷新(高速に大きな桁を動かせる)。選択状態は GUI 再生成をまたいで
 * コンストラクタ引数で持ち回す(クリックのたびに新インスタンスを開く既存パターンに合わせる)。
 */
public class TfDefenseGUI implements InventoryHolder {

    private static final int INFO_SLOT = 4;
    private static final int VALUE_SLOT = 22;
    private static final int BACK_SLOT = 49;

    // 上段のステ選択ボタン配置(7スタット)。
    private static final int[] SELECT_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final TfDefenseStat[] SELECTABLE = {
            TfDefenseStat.DEFENSE_RATE, TfDefenseStat.DAMAGE_REDUCTION,
            TfDefenseStat.PHYS_RESISTANCE, TfDefenseStat.MAGIC_RESISTANCE,
            TfDefenseStat.FLAT_DEFENSE, TfDefenseStat.ARMOR_STRENGTH,
            TfDefenseStat.DODGE_CHANCE
    };

    private final Inventory inventory;
    private final DummyEntity dummy;
    private final TfDefenseStat selected;

    private final Map<Integer, TfDefenseStat> selectSlots = new HashMap<>();
    private final Map<Integer, Double> adjustSlots = new HashMap<>();

    public TfDefenseGUI(JavaPlugin plugin, DummyEntity dummy) {
        this(plugin, dummy, SELECTABLE[0]);
    }

    public TfDefenseGUI(JavaPlugin plugin, DummyEntity dummy, TfDefenseStat selected) {
        this.dummy = dummy;
        this.selected = selected != null ? selected : SELECTABLE[0];
        this.inventory = Bukkit.createInventory(this, 54,
                Component.text("DPS Dummy - TF防御", NamedTextColor.DARK_BLUE));
        initializeItems();
    }

    private void initializeItems() {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        filler.editMeta(meta -> meta.displayName(Component.text("")));
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        ItemStack info = new ItemStack(Material.NETHERITE_CHESTPLATE);
        info.editMeta(meta -> {
            meta.displayName(noItalic(Component.text("TrinityForge 防御プロファイル", NamedTextColor.GOLD)));
            meta.lore(List.of(
                    noItalic(Component.text("上のアイコンでステを選び、下の桁ボタンで増減", NamedTextColor.WHITE)),
                    noItalic(Component.text("flat系(守備力/防具強度)は ±1/10/100/1000", NamedTextColor.GRAY)),
                    noItalic(Component.text("％系は ±1%/10% (0〜100%)", NamedTextColor.GRAY)),
                    noItalic(Component.text("実効上限は TF combat/damage.yml の defense.* に従う", NamedTextColor.GRAY)),
                    noItalic(Component.text("※TF防具を装備中はその防具由来の防御が優先", NamedTextColor.YELLOW)),
                    noItalic(Component.text("　(防具を外すとここで設定した手動値に復帰)", NamedTextColor.GRAY)),
                    noItalic(Component.text("変更は即座に TF PDC へ反映", NamedTextColor.DARK_GRAY))
            ));
        });
        inventory.setItem(INFO_SLOT, info);

        // 上段: ステ選択ボタン。選択中はエンチャント光沢＋名前に▶。
        for (int i = 0; i < SELECTABLE.length && i < SELECT_SLOTS.length; i++) {
            TfDefenseStat stat = SELECTABLE[i];
            int slot = SELECT_SLOTS[i];
            selectSlots.put(slot, stat);
            inventory.setItem(slot, selectButton(stat));
        }

        // 中央: 選択中ステの現在値。
        inventory.setItem(VALUE_SLOT, valueItem(selected));

        // 下段: 桁ボタン(プラスは上段=row3、マイナスは下段=row4、同一カラムで上下対応)。
        double[] digits = digitsOf(selected);
        int[] columns = columnsFor(digits.length);
        for (int i = 0; i < digits.length; i++) {
            int plusSlot = 27 + columns[i];
            int minusSlot = 36 + columns[i];
            adjustSlots.put(plusSlot, digits[i]);
            adjustSlots.put(minusSlot, -digits[i]);
            inventory.setItem(plusSlot, digitButton(selected, digits[i], true));
            inventory.setItem(minusSlot, digitButton(selected, digits[i], false));
        }

        ItemStack back = new ItemStack(Material.ARROW);
        back.editMeta(meta -> meta.displayName(noItalic(Component.text("戻る", NamedTextColor.WHITE))));
        inventory.setItem(BACK_SLOT, back);
    }

    private ItemStack selectButton(TfDefenseStat stat) {
        double value = dummy.getDefenseProfile().valueOf(stat);
        boolean isSelected = stat == selected;
        ItemStack item = new ItemStack(iconOf(stat));
        item.editMeta(meta -> {
            String prefix = isSelected ? "▶ " : "";
            meta.displayName(noItalic(Component.text(prefix + stat.displayName(),
                    isSelected ? NamedTextColor.YELLOW : NamedTextColor.AQUA)));
            meta.lore(List.of(
                    noItalic(Component.text("現在: " + formatValue(stat, value), NamedTextColor.WHITE)),
                    noItalic(Component.text(isSelected ? "選択中" : "クリックで選択", NamedTextColor.GRAY))
            ));
            if (isSelected) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
        });
        return item;
    }

    private ItemStack valueItem(TfDefenseStat stat) {
        double value = dummy.getDefenseProfile().valueOf(stat);
        ItemStack item = new ItemStack(iconOf(stat));
        item.editMeta(meta -> {
            meta.displayName(noItalic(Component.text(stat.displayName() + ": " + formatValue(stat, value),
                    NamedTextColor.GOLD)));
            meta.lore(List.of(
                    noItalic(Component.text(
                            String.format("範囲: %s 〜 %s",
                                    formatValue(stat, stat.min()),
                                    formatValue(stat, stat.max())),
                            NamedTextColor.GRAY)),
                    noItalic(Component.text("下のボタンで増減", NamedTextColor.DARK_GRAY))
            ));
        });
        return item;
    }

    private ItemStack digitButton(TfDefenseStat stat, double magnitude, boolean plus) {
        ItemStack item = new ItemStack(plus ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        String label = (plus ? "+" : "-") + formatStep(stat, magnitude);
        item.editMeta(meta -> meta.displayName(noItalic(Component.text(label,
                plus ? NamedTextColor.GREEN : NamedTextColor.RED))));
        return item;
    }

    public void handleClick(Player player, int slot, JavaPlugin plugin) {
        if (slot == BACK_SLOT) {
            player.openInventory(new MainMenuGUI(plugin, dummy).getInventory());
            return;
        }

        TfDefenseStat pick = selectSlots.get(slot);
        if (pick != null) {
            player.openInventory(new TfDefenseGUI(plugin, dummy, pick).getInventory());
            return;
        }

        Double delta = adjustSlots.get(slot);
        if (delta != null) {
            dummy.adjustTfDefenseStat(selected, delta);
            player.openInventory(new TfDefenseGUI(plugin, dummy, selected).getInventory());
        }
    }

    // flat系は4桁(1/10/100/1000)、％系は2桁(1%/10%)。
    private static double[] digitsOf(TfDefenseStat stat) {
        return stat.percent() ? new double[]{0.01, 0.10} : new double[]{1.0, 10.0, 100.0, 1000.0};
    }

    // 桁数に応じて 9列(0-8)の中央寄せカラムを返す。プラス=27+col, マイナス=36+col で上下対応。
    private static int[] columnsFor(int count) {
        int start = (9 - count) / 2;
        int[] cols = new int[count];
        for (int i = 0; i < count; i++) {
            cols[i] = start + i;
        }
        return cols;
    }

    private static Material iconOf(TfDefenseStat stat) {
        return switch (stat) {
            case DEFENSE_RATE -> Material.IRON_SWORD;
            case DAMAGE_REDUCTION -> Material.TURTLE_HELMET;
            case FLAT_DEFENSE -> Material.IRON_CHESTPLATE;
            case PHYS_RESISTANCE -> Material.SHIELD;
            case MAGIC_RESISTANCE -> Material.ENCHANTED_GOLDEN_APPLE;
            case ARMOR_STRENGTH -> Material.NETHERITE_CHESTPLATE;
            case DODGE_CHANCE -> Material.FEATHER;
        };
    }

    // 桁ボタンのラベル(％系は "1%"/"10%"、flat系は "1"/"10"/"100"/"1000")。
    private static String formatStep(TfDefenseStat stat, double magnitude) {
        if (stat.percent()) {
            return String.format("%.0f%%", magnitude * 100.0);
        }
        return String.format("%.0f", magnitude);
    }

    private static String formatValue(TfDefenseStat stat, double value) {
        if (stat.percent()) {
            return String.format("%.0f%%", value * 100.0);
        }
        return String.format("%.1f", value);
    }

    public DummyEntity getDummy() { return dummy; }

    @Override
    public Inventory getInventory() { return inventory; }
}
