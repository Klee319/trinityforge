package com.trinityforge.progression;

import com.trinityforge.config.domains.RoleBuffsConfig.CombatRoleSpec;
import com.trinityforge.config.domains.RoleBuffsConfig.SupportRoleSpec;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.text.MiniText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code /tf role set}(引数なし)で開くロール選択GUI(2026-07-28)。
 *
 * <p>アイテム1個=1ロールで、表示名に職業名(label)、lore に効果({@link RoleDescriptions})を出す。
 * 引数版 {@code /tf role set <combat> [support]} と同じ {@link RoleChangeService} を通すので、
 * 「近くに敵モブが居ると変更不可」等のゲートはGUI経由でも同じように効く。
 *
 * <p>クリックは常にキャンセルし、ドラッグも {@link SettingsGui} と同様に全面禁止する
 * (GUIへ実アイテムを置けてしまうと消失事故になるため)。
 */
public final class RoleSelectGui implements Listener {

    private static final int SIZE = 54;
    private static final int COMBAT_HEADER_SLOT = 4;
    private static final int COMBAT_ROW_START = 10;
    private static final int SUPPORT_HEADER_SLOT = 31;
    private static final int SUPPORT_ROW_START = 37;
    private static final int CLEAR_SLOT = 49;
    /** 1行に置ける最大件数(左右の枠を1マスずつ空ける)。 */
    private static final int MAX_PER_ROW = 7;

    private static final Material DEFAULT_COMBAT_ICON = Material.IRON_SWORD;
    private static final Material DEFAULT_SUPPORT_ICON = Material.BOOK;

    private final RoleChangeService roleChangeService;
    private final RoleDescriptions descriptions;
    private final NamespacedKey combatKey;
    private final NamespacedKey supportKey;
    private final NamespacedKey clearKey;

    public RoleSelectGui(Plugin plugin, RoleChangeService roleChangeService, RoleDescriptions descriptions) {
        Objects.requireNonNull(plugin, "plugin");
        this.roleChangeService = Objects.requireNonNull(roleChangeService, "roleChangeService");
        this.descriptions = Objects.requireNonNull(descriptions, "descriptions");
        this.combatKey = new NamespacedKey(plugin, "role_gui_combat");
        this.supportKey = new NamespacedKey(plugin, "role_gui_support");
        this.clearKey = new NamespacedKey(plugin, "role_gui_clear");
    }

    public void open(Player player) {
        Session session = new Session();
        Inventory inventory = Bukkit.createInventory(session, SIZE, Component.text("ロール選択"));
        session.inventory = inventory;

        PlayerData data = PlayerData.of(player);
        String currentCombat = data.rolePrimary().orElse(null);
        String currentSupport = data.roleSupport().orElse(null);

        inventory.setItem(COMBAT_HEADER_SLOT, header("戦闘職", Material.NETHERITE_SWORD,
                currentCombat, roleChangeService.config().combatRole(currentCombat) == null
                        ? null : roleChangeService.config().combatRole(currentCombat).label()));
        List<CombatRoleSpec> combatRoles = new ArrayList<>(roleChangeService.config().combatRoles().values());
        for (int i = 0; i < combatRoles.size() && i < MAX_PER_ROW; i++) {
            CombatRoleSpec spec = combatRoles.get(i);
            inventory.setItem(COMBAT_ROW_START + i,
                    combatButton(spec, spec.id().equals(currentCombat)));
        }

        inventory.setItem(SUPPORT_HEADER_SLOT, header("補助職", Material.ENCHANTED_BOOK,
                currentSupport, roleChangeService.config().supportRole(currentSupport) == null
                        ? null : roleChangeService.config().supportRole(currentSupport).label()));
        List<SupportRoleSpec> supportRoles = new ArrayList<>(roleChangeService.config().supportRoles().values());
        for (int i = 0; i < supportRoles.size() && i < MAX_PER_ROW; i++) {
            SupportRoleSpec spec = supportRoles.get(i);
            inventory.setItem(SUPPORT_ROW_START + i,
                    supportButton(spec, spec.id().equals(currentSupport)));
        }

        inventory.setItem(CLEAR_SLOT, clearButton());
        player.openInventory(inventory);
    }

    private ItemStack header(String title, Material icon, String currentId, String currentLabel) {
        ItemStack stack = new ItemStack(icon);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(title, NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        // label は MiniMessage 可なので、文字列連結に混ぜる前にタグを落とす。
        meta.lore(List.of(Component.text("現在: "
                        + (currentId == null ? "(なし)"
                                : (currentLabel == null ? currentId : MiniText.plain(currentLabel))),
                        NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack combatButton(CombatRoleSpec spec, boolean selected) {
        List<Component> lore = new ArrayList<>();
        for (String line : spec.description()) {
            lore.add(MiniText.render(line, NamedTextColor.GRAY));
        }
        lore.add(Component.text("[効果]", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> effects = descriptions.describeCombat(spec);
        lore.addAll(effects.isEmpty()
                ? List.of(Component.text("  (なし)", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false))
                : effects);
        return roleButton(spec.label(), spec.icon(), DEFAULT_COMBAT_ICON, lore, selected,
                combatKey, spec.id());
    }

    private ItemStack supportButton(SupportRoleSpec spec, boolean selected) {
        List<Component> lore = new ArrayList<>();
        for (String line : spec.description()) {
            lore.add(MiniText.render(line, NamedTextColor.GRAY));
        }
        lore.add(Component.text("[効果]", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> effects = descriptions.describeSupport(spec);
        lore.addAll(effects.isEmpty()
                ? List.of(Component.text("  (なし)", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false))
                : effects);
        return roleButton(spec.label(), spec.icon(), DEFAULT_SUPPORT_ICON, lore, selected,
                supportKey, spec.id());
    }

    private ItemStack roleButton(String label, String iconName, Material fallbackIcon,
                                 List<Component> lore, boolean selected,
                                 NamespacedKey key, String roleId) {
        ItemStack stack = new ItemStack(resolveIcon(iconName, fallbackIcon));
        ItemMeta meta = stack.getItemMeta();
        // label は MiniMessage 可。色を書いていない label だけ、選択状態の色(緑/白)を当てる。
        meta.displayName(Component.text(selected ? "▶ " : "",
                        selected ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)
                .append(MiniText.render(label, selected ? NamedTextColor.GREEN : NamedTextColor.WHITE)));
        List<Component> full = new ArrayList<>(lore);
        full.add(Component.empty());
        full.add(Component.text(selected ? "選択中" : "クリックで選択",
                        selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(full);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, roleId);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack clearButton() {
        ItemStack stack = new ItemStack(Material.BARRIER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("ロールを解除", NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("戦闘職・補助職の両方を外します", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(clearKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    /** 未設定/不正な Material 名は既定アイコンへフォールバックする(GUIが空白にならないように)。 */
    static Material resolveIcon(String iconName, Material fallback) {
        if (iconName == null || iconName.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(iconName.trim().toUpperCase(Locale.ROOT));
        return material != null && material.isItem() ? material : fallback;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Session)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        var pdc = clicked.getItemMeta().getPersistentDataContainer();
        String combatId = pdc.get(combatKey, PersistentDataType.STRING);
        String supportId = pdc.get(supportKey, PersistentDataType.STRING);
        boolean clear = pdc.has(clearKey, PersistentDataType.BYTE);
        if (combatId == null && supportId == null && !clear) {
            return;
        }
        // 引数版コマンドと同じゲート。拒否理由はチャットへ出し、GUIは開いたままにする。
        var deny = roleChangeService.denyReason(player);
        if (deny.isPresent()) {
            player.sendMessage(Component.text(deny.get(), NamedTextColor.RED));
            return;
        }
        if (clear) {
            roleChangeService.clear(player);
            player.sendMessage(Component.text("ロールをクリアしました。", NamedTextColor.YELLOW));
        } else if (combatId != null) {
            if (!roleChangeService.setCombat(player, combatId)) {
                player.sendMessage(Component.text("未知の戦闘職: " + combatId, NamedTextColor.RED));
                return;
            }
            player.sendMessage(Component.text("戦闘職を設定しました: "
                    + labelOfCombat(combatId), NamedTextColor.GREEN));
        } else {
            if (!roleChangeService.setSupport(player, supportId)) {
                player.sendMessage(Component.text("未知の補助職: " + supportId, NamedTextColor.RED));
                return;
            }
            player.sendMessage(Component.text("補助職を設定しました: "
                    + labelOfSupport(supportId), NamedTextColor.GREEN));
        }
        open(player);
    }

    // チャット1行への文字列連結に使うので、MiniMessage タグは落としてから返す。
    private String labelOfCombat(String id) {
        CombatRoleSpec spec = roleChangeService.config().combatRole(id);
        return spec == null ? id : MiniText.plain(spec.label());
    }

    private String labelOfSupport(String id) {
        SupportRoleSpec spec = roleChangeService.config().supportRole(id);
        return spec == null ? id : MiniText.plain(spec.label());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Session) {
            event.setCancelled(true);
        }
    }

    private static final class Session implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
