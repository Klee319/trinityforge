package com.trinityforge.mobs;

import com.trinityforge.combat.SymmetricCombatService;
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
import java.util.Objects;

/**
 * ダンジョンゲートの鍵アイテムを右クリックしたときの潜入確認GUI(2026-07-27)。{@code SettingsGui} と
 * 同じ流儀({@link Listener} + {@link InventoryHolder} でなりすまし防止 + ボタンに
 * {@link NamespacedKey} をPDCで焼く + クリック/ドラッグは常に {@code setCancelled(true)})。
 *
 * <p>同じ鍵アイテムで複数ゲートが引っかかる場合の選択画面({@link #openSelection})と、
 * 1件に絞れた後の確認画面({@link #openConfirm})の2画面構成。確定ボタン押下時は GUI を開いた時点の
 * 判定を信用せず、{@link DungeonGateService#evaluateOnly} でその場再検証してから転送する。
 */
public final class DungeonEntryGui implements Listener {

    private static final int SIZE = 27;
    private static final int INFO_SLOT = 13;
    private static final int CONFIRM_SLOT = 11;
    private static final int CANCEL_SLOT = 15;
    private static final int SELECTION_START_SLOT = 10;
    private static final int SELECTION_PAGE_SIZE = 15;
    private static final int PREVIOUS_PAGE_SLOT = 0;
    private static final int NEXT_PAGE_SLOT = 8;

    private final Plugin plugin;
    private final DungeonGateService gateService;
    private final SymmetricCombatService combatService;
    private final DungeonTeleporter teleporter;
    private final NamespacedKey confirmKey;
    private final NamespacedKey cancelKey;
    private final NamespacedKey selectionKey;
    private final NamespacedKey navigationKey;

    public DungeonEntryGui(Plugin plugin, DungeonGateService gateService, SymmetricCombatService combatService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.gateService = Objects.requireNonNull(gateService, "gateService");
        this.combatService = Objects.requireNonNull(combatService, "combatService");
        this.teleporter = new DungeonTeleporter(gateService);
        this.confirmKey = new NamespacedKey(plugin, "dungeon_entry_gui_confirm");
        this.cancelKey = new NamespacedKey(plugin, "dungeon_entry_gui_cancel");
        this.selectionKey = new NamespacedKey(plugin, "dungeon_entry_gui_selection");
        this.navigationKey = new NamespacedKey(plugin, "dungeon_entry_gui_navigation");
    }

    /** 候補ゲートが複数あるとき、どれに入るか選ばせる画面。 */
    public void openSelection(Player player, List<DungeonGate> candidates) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(candidates, "candidates");
        openSelection(player, List.copyOf(candidates), 0);
    }

    private void openSelection(Player player, List<DungeonGate> candidates, int requestedPage) {
        int pageCount = Math.max(1, (candidates.size() + SELECTION_PAGE_SIZE - 1) / SELECTION_PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        SelectionSession session = new SelectionSession(candidates, page);
        Inventory inventory = Bukkit.createInventory(session, SIZE,
                Component.text("入場先を選択 (" + (page + 1) + "/" + pageCount + ")"));
        session.inventory = inventory;
        int firstCandidate = page * SELECTION_PAGE_SIZE;
        int lastCandidate = Math.min(firstCandidate + SELECTION_PAGE_SIZE, candidates.size());
        for (int index = firstCandidate; index < lastCandidate; index++) {
            inventory.setItem(SELECTION_START_SLOT + index - firstCandidate,
                    selectionIcon(candidates.get(index), index));
        }
        if (page > 0) {
            inventory.setItem(PREVIOUS_PAGE_SLOT, navigationIcon("« 前のページ", -1));
        }
        if (page < pageCount - 1) {
            inventory.setItem(NEXT_PAGE_SLOT, navigationIcon("次のページ »", 1));
        }
        player.openInventory(inventory);
    }

    /** 1件に絞れたゲートの潜入確認画面。 */
    public void openConfirm(Player player, DungeonGate gate) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(gate, "gate");
        ConfirmSession session = new ConfirmSession(gate);
        Inventory inventory = Bukkit.createInventory(session, SIZE, Component.text("潜入確認"));
        session.inventory = inventory;

        boolean meetsRequirements = gateService.evaluateOnly(player, gate) == DungeonGatePolicy.Denial.NONE;
        inventory.setItem(INFO_SLOT, infoIcon(player, gate));
        inventory.setItem(CONFIRM_SLOT, confirmIcon(meetsRequirements));
        inventory.setItem(CANCEL_SLOT, cancelIcon());
        player.openInventory(inventory);
    }

    private ItemStack selectionIcon(DungeonGate gate, int candidateIndex) {
        ItemStack stack = new ItemStack(Material.MAP);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(gate.displayNameOrWorld(), NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("クリックで選択", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(selectionKey, PersistentDataType.INTEGER, candidateIndex);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack navigationIcon(String label, int delta) {
        ItemStack stack = new ItemStack(delta < 0 ? Material.ARROW : Material.SPECTRAL_ARROW);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(navigationKey, PersistentDataType.INTEGER, delta);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack infoIcon(Player player, DungeonGate gate) {
        int combatLevel = combatService.combatLevelOf(player.getUniqueId());
        ItemStack stack = new ItemStack(Material.KNOWLEDGE_BOOK);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(gate.displayNameOrWorld(), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("必要戦闘Lv: " + gate.requiredCombatLevel()
                        + " / 現在の戦闘Lv: " + combatLevel, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (gate.keyRequired()) {
            int held = gateService.keyMatcher().count(player.getInventory(), gate.keyItem());
            lore.add(Component.text("消費: " + gateService.keyMatcher().displayName(gate.keyItem())
                            + " x" + gate.keyAmount(), NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("所持: " + held, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack confirmIcon(boolean enabled) {
        ItemStack stack = new ItemStack(enabled ? Material.LIME_CONCRETE : Material.RED_CONCRETE);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("潜入する", enabled ? NamedTextColor.GREEN : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        if (!enabled) {
            meta.lore(List.of(Component.text("条件を満たしていません", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)));
        }
        meta.getPersistentDataContainer().set(confirmKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack cancelIcon() {
        ItemStack stack = new ItemStack(Material.BARRIER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("やめる", NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(cancelKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof ConfirmSession session) {
            event.setCancelled(true);
            handleConfirmClick(event, session);
        } else if (holder instanceof SelectionSession session) {
            event.setCancelled(true);
            handleSelectionClick(event, session);
        }
    }

    private void handleConfirmClick(InventoryClickEvent event, ConfirmSession session) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();
        if (meta.getPersistentDataContainer().has(confirmKey, PersistentDataType.BYTE)) {
            player.closeInventory();
            attemptEntry(player, session.gate);
        } else if (meta.getPersistentDataContainer().has(cancelKey, PersistentDataType.BYTE)) {
            player.closeInventory();
        }
    }

    private void handleSelectionClick(InventoryClickEvent event, SelectionSession session) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();
        Integer pageDelta = meta.getPersistentDataContainer().get(navigationKey, PersistentDataType.INTEGER);
        if (pageDelta != null) {
            openSelection(player, session.candidates, session.page + pageDelta);
            return;
        }
        Integer selectedIndex = meta.getPersistentDataContainer()
                .get(selectionKey, PersistentDataType.INTEGER);
        if (selectedIndex == null || selectedIndex < 0 || selectedIndex >= session.candidates.size()) {
            return;
        }
        openConfirm(player, session.candidates.get(selectedIndex));
    }

    /**
     * 「その場でもう一度」検証してから転送する(GUIを開いた時点の判定を信用しない)。転送/委譲が
     * 成功したことを確認してから鍵を消費する({@link DungeonEntryExecutor}で順序を保証)。
     */
    private void attemptEntry(Player player, DungeonGate gate) {
        DungeonGatePolicy.Denial denial = gateService.evaluateOnly(player, gate);
        switch (denial) {
            case UNDER_LEVEL -> player.sendMessage(Component.text(
                    "このダンジョンに入るには combat level " + gate.requiredCombatLevel() + " が必要です",
                    NamedTextColor.RED));
            case MISSING_KEY -> player.sendMessage(Component.text(
                    "入場には " + gateService.keyMatcher().displayName(gate.keyItem())
                            + " x" + gate.keyAmount() + " が必要です", NamedTextColor.RED));
            case NONE -> teleporter.proceedToTarget(player, gate, () -> gateService.consumeKey(player, gate));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof ConfirmSession || holder instanceof SelectionSession) {
            event.setCancelled(true);
        }
    }

    private static final class ConfirmSession implements InventoryHolder {
        private final DungeonGate gate;
        private Inventory inventory;

        private ConfirmSession(DungeonGate gate) {
            this.gate = gate;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class SelectionSession implements InventoryHolder {
        private final List<DungeonGate> candidates;
        private final int page;
        private Inventory inventory;

        private SelectionSession(List<DungeonGate> candidates, int page) {
            this.candidates = candidates;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
