package com.github.klee319.dpschecker.gui;

import com.github.klee319.dpschecker.dummy.DummyEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import static com.github.klee319.dpschecker.gui.MainMenuGUI.noItalic;

public class EquipmentGUI implements InventoryHolder {

    private static final int SLOT_HELMET = 10;
    private static final int SLOT_CHESTPLATE = 11;
    private static final int SLOT_LEGGINGS = 12;
    private static final int SLOT_BOOTS = 13;
    private static final int SLOT_MAIN_HAND = 15;
    private static final int SLOT_OFF_HAND = 16;
    private static final int SLOT_BACK = 22;

    private final Inventory inventory;
    private final DummyEntity dummy;

    public EquipmentGUI(JavaPlugin plugin, DummyEntity dummy) {
        this.dummy = dummy;
        this.inventory = Bukkit.createInventory(this, 27,
                Component.text("DPS Dummy - 装備管理", NamedTextColor.DARK_AQUA));
        initializeItems();
    }

    private void initializeItems() {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        filler.editMeta(meta -> meta.displayName(Component.text("")));
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        Zombie entity = dummy.getEntity();
        if (entity != null && !entity.isDead()) {
            EntityEquipment eq = entity.getEquipment();

            setSlotOrPlaceholder(SLOT_HELMET, eq.getHelmet(), "ヘルメット", Material.GLASS_PANE);
            setSlotOrPlaceholder(SLOT_CHESTPLATE, eq.getChestplate(), "チェストプレート", Material.GLASS_PANE);
            setSlotOrPlaceholder(SLOT_LEGGINGS, eq.getLeggings(), "レギンス", Material.GLASS_PANE);
            setSlotOrPlaceholder(SLOT_BOOTS, eq.getBoots(), "ブーツ", Material.GLASS_PANE);
            setSlotOrPlaceholder(SLOT_MAIN_HAND, eq.getItemInMainHand(), "メインハンド", Material.GLASS_PANE);
            setSlotOrPlaceholder(SLOT_OFF_HAND, eq.getItemInOffHand(), "オフハンド", Material.GLASS_PANE);
        }

        // Labels
        setLabel(1, "防具", NamedTextColor.BLUE);
        setLabel(6, "武器", NamedTextColor.RED);

        // Back button
        ItemStack back = new ItemStack(Material.ARROW);
        back.editMeta(meta -> meta.displayName(noItalic(Component.text("戻る", NamedTextColor.WHITE))));
        inventory.setItem(SLOT_BACK, back);
    }

    private void setSlotOrPlaceholder(int slot, ItemStack current, String label, Material placeholder) {
        if (current != null && current.getType() != Material.AIR) {
            inventory.setItem(slot, current.clone());
        } else {
            ItemStack ph = new ItemStack(placeholder);
            ph.editMeta(meta -> {
                meta.displayName(noItalic(Component.text(label + " (空)", NamedTextColor.DARK_GRAY)));
                meta.lore(java.util.List.of(
                        noItalic(Component.text("アイテムを持ってクリックで装着", NamedTextColor.GRAY))
                ));
            });
            inventory.setItem(slot, ph);
        }
    }

    private void setLabel(int slot, String text, NamedTextColor color) {
        ItemStack label = new ItemStack(Material.WHITE_STAINED_GLASS_PANE);
        label.editMeta(meta -> meta.displayName(noItalic(Component.text(text, color))));
        inventory.setItem(slot, label);
    }

    public void handleClick(Player player, InventoryClickEvent event, JavaPlugin plugin) {
        int rawSlot = event.getRawSlot();
        int topSize = inventory.getSize();

        // Bottom inventory click: allow normal interaction, handle shift-click to auto-equip
        if (rawSlot >= topSize) {
            if (event.getClick().isShiftClick()) {
                event.setCancelled(true);
                ItemStack item = event.getCurrentItem();
                if (item == null || item.getType() == Material.AIR) return;

                int targetSlot = determineEquipmentSlot(item.getType());
                if (targetSlot < 0) return;

                Zombie entity = dummy.getEntity();
                if (entity == null || entity.isDead()) return;

                EntityEquipment eq = entity.getEquipment();
                ItemStack currentEquipment = getEquipmentAt(eq, targetSlot);
                boolean hasEquipment = currentEquipment != null && currentEquipment.getType() != Material.AIR;

                setEquipmentAt(eq, targetSlot, item.clone());
                event.setCurrentItem(hasEquipment ? currentEquipment.clone() : ItemStack.empty());

                refreshGUI(player, plugin);
            }
            return;
        }

        if (rawSlot < 0) {
            event.setCancelled(true);
            return;
        }

        // Top inventory click: block shift-click (no meaningful target inside top GUI)
        if (event.getClick().isShiftClick()) {
            event.setCancelled(true);
            return;
        }

        if (rawSlot == SLOT_BACK) {
            event.setCancelled(true);
            player.openInventory(new MainMenuGUI(plugin, dummy).getInventory());
            return;
        }

        if (!isEquipmentSlot(rawSlot)) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        Zombie entity = dummy.getEntity();
        if (entity == null || entity.isDead()) return;

        EntityEquipment eq = entity.getEquipment();
        ItemStack cursor = event.getCursor();
        ItemStack currentEquipment = getEquipmentAt(eq, rawSlot);

        boolean hasCursor = cursor != null && cursor.getType() != Material.AIR;
        boolean hasEquipment = currentEquipment != null && currentEquipment.getType() != Material.AIR;

        if (hasCursor && hasEquipment) {
            ItemStack toEntity = cursor.clone();
            ItemStack toPlayer = currentEquipment.clone();
            setEquipmentAt(eq, rawSlot, toEntity);
            player.setItemOnCursor(toPlayer);
        } else if (hasCursor) {
            ItemStack toEntity = cursor.clone();
            setEquipmentAt(eq, rawSlot, toEntity);
            player.setItemOnCursor(ItemStack.empty());
        } else if (hasEquipment) {
            ItemStack toPlayer = currentEquipment.clone();
            setEquipmentAt(eq, rawSlot, null);
            player.setItemOnCursor(toPlayer);
        }

        refreshGUI(player, plugin);
    }

    private void refreshGUI(Player player, JavaPlugin plugin) {
        // 装備が変わったので TF 防御プロファイルを装備由来へ再算出(#6)。
        dummy.refreshEquipmentDefense();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof EquipmentGUI) {
                player.openInventory(new EquipmentGUI(plugin, dummy).getInventory());
            }
        }, 1L);
    }

    private int determineEquipmentSlot(Material type) {
        String name = type.name();
        if (name.endsWith("_HELMET") || type == Material.TURTLE_HELMET || type == Material.CARVED_PUMPKIN) {
            return SLOT_HELMET;
        }
        if (name.endsWith("_CHESTPLATE") || type == Material.ELYTRA) {
            return SLOT_CHESTPLATE;
        }
        if (name.endsWith("_LEGGINGS")) {
            return SLOT_LEGGINGS;
        }
        if (name.endsWith("_BOOTS")) {
            return SLOT_BOOTS;
        }
        if (type == Material.SHIELD) {
            return SLOT_OFF_HAND;
        }
        return SLOT_MAIN_HAND;
    }

    private boolean isEquipmentSlot(int slot) {
        return slot == SLOT_HELMET || slot == SLOT_CHESTPLATE || slot == SLOT_LEGGINGS
                || slot == SLOT_BOOTS || slot == SLOT_MAIN_HAND || slot == SLOT_OFF_HAND;
    }

    private ItemStack getEquipmentAt(EntityEquipment eq, int slot) {
        return switch (slot) {
            case SLOT_HELMET -> eq.getHelmet();
            case SLOT_CHESTPLATE -> eq.getChestplate();
            case SLOT_LEGGINGS -> eq.getLeggings();
            case SLOT_BOOTS -> eq.getBoots();
            case SLOT_MAIN_HAND -> eq.getItemInMainHand();
            case SLOT_OFF_HAND -> eq.getItemInOffHand();
            default -> null;
        };
    }

    private void setEquipmentAt(EntityEquipment eq, int slot, ItemStack item) {
        switch (slot) {
            case SLOT_HELMET -> eq.setHelmet(item);
            case SLOT_CHESTPLATE -> eq.setChestplate(item);
            case SLOT_LEGGINGS -> eq.setLeggings(item);
            case SLOT_BOOTS -> eq.setBoots(item);
            case SLOT_MAIN_HAND -> eq.setItemInMainHand(item);
            case SLOT_OFF_HAND -> eq.setItemInOffHand(item);
        }
    }

    public void onClose() {
        // Auto-relocate misplaced armor: if an armor item is in the wrong slot
        // and the correct slot is empty, move it. Otherwise leave it alone.
        Zombie entity = dummy.getEntity();
        if (entity == null || entity.isDead()) return;

        EntityEquipment eq = entity.getEquipment();
        int[] slotIds = {SLOT_HELMET, SLOT_CHESTPLATE, SLOT_LEGGINGS, SLOT_BOOTS, SLOT_MAIN_HAND, SLOT_OFF_HAND};
        ItemStack[] items = {
                eq.getHelmet(),
                eq.getChestplate(),
                eq.getLeggings(),
                eq.getBoots(),
                eq.getItemInMainHand(),
                eq.getItemInOffHand()
        };

        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item == null || item.getType() == Material.AIR) continue;

            int correctSlot = determineArmorSlot(item.getType());
            if (correctSlot < 0) continue;
            if (correctSlot == slotIds[i]) continue;

            int targetIdx = -1;
            for (int j = 0; j < slotIds.length; j++) {
                if (slotIds[j] == correctSlot) {
                    targetIdx = j;
                    break;
                }
            }
            if (targetIdx < 0) continue;

            ItemStack atTarget = items[targetIdx];
            if (atTarget == null || atTarget.getType() == Material.AIR) {
                items[targetIdx] = item;
                items[i] = null;
            }
        }

        eq.setHelmet(items[0]);
        eq.setChestplate(items[1]);
        eq.setLeggings(items[2]);
        eq.setBoots(items[3]);
        eq.setItemInMainHand(items[4] == null ? ItemStack.empty() : items[4]);
        eq.setItemInOffHand(items[5] == null ? ItemStack.empty() : items[5]);

        // 再配置後の最終装備で TF 防御プロファイルを再算出(#6)。
        dummy.refreshEquipmentDefense();
    }

    private int determineArmorSlot(Material type) {
        String name = type.name();
        if (name.endsWith("_HELMET") || type == Material.TURTLE_HELMET || type == Material.CARVED_PUMPKIN) {
            return SLOT_HELMET;
        }
        if (name.endsWith("_CHESTPLATE") || type == Material.ELYTRA) {
            return SLOT_CHESTPLATE;
        }
        if (name.endsWith("_LEGGINGS")) {
            return SLOT_LEGGINGS;
        }
        if (name.endsWith("_BOOTS")) {
            return SLOT_BOOTS;
        }
        return -1;
    }

    public DummyEntity getDummy() { return dummy; }

    @Override
    public Inventory getInventory() { return inventory; }
}
