package com.trinityforge.items;

import com.trinityforge.pdc.ItemData;
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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link EquipmentTicketEffect} 共通の対象選択GUIエンジン(ランダムステータス再抽選券/品質レベルアップ券の
 * 2種で共有)。要件仕様どおり、{@code NativeSkillTreeMenu#handleNode} と同じ
 * 「1クリック目でpending、同一対象への2クリック目で確定」の作法を踏襲する — 新しい確認方式を作らない。
 *
 * <p><b>対象の同一性再確認</b>: pending にした瞬間の対象の姿({@link TargetSnapshot})を GUI 側の
 * メモリ(Sessionインスタンス、PDCではない)に保持し、確定クリック時に「今そのスロットにある実物」と
 * 突き合わせる。GUI を開いてから確定までの間にインベントリを操作された場合(装備を外す/入れ替える等)、
 * 一致しなければ確定させない — ArsPaper の {@code ThreadTargetIdentity} と同じ問題への TF 側の対処。
 *
 * <p><b>券の消費タイミング</b>: 確定クリックの直前に、GUI を開いたときに使ったのと同じカタログIDの
 * 券がメインハンドにまだ残っているかを再確認してから1個減らす。GUI を開いた後に券をドロップ/収納された
 * ケースを無償適用に化けさせないため。
 *
 * <p>対象候補はプレイヤーの防具4部位・両手・メイン32+ホットバー(合計36の収納枠)から集める。
 * 防具/両手の読み書きは個別 getter/setter を使う({@link PlayerInventory#getItem(EquipmentSlot)} の
 * 統合APIはMockBukkitでの実装差があるため、{@code EquipmentDurabilityService} と同じ理由で避ける)。
 */
public final class EquipmentTicketGui implements Listener {

    private static final int SIZE = 54;
    private static final int HEADER_SLOT = 4;
    private static final int CANDIDATE_START = 9;
    private static final int CANDIDATE_END = 44; // inclusive, 36 slots
    private static final List<String> EQUIPMENT_REFS = List.of(
            "eq:HEAD", "eq:CHEST", "eq:LEGS", "eq:FEET", "eq:HAND", "eq:OFF_HAND");

    private final Plugin plugin;
    private final NamespacedKey targetRefKey;

    public EquipmentTicketGui(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.targetRefKey = new NamespacedKey(plugin, "ticket_target_ref");
    }

    public void open(Player player, EquipmentTicketEffect effect) {
        render(player, effect, null, null);
    }

    private void render(Player player, EquipmentTicketEffect effect, String pendingRef, TargetSnapshot pendingSnapshot) {
        Session session = new Session(effect, pendingRef, pendingSnapshot);
        Inventory inventory = Bukkit.createInventory(session, SIZE, Component.text(effect.title()));
        session.inventory = inventory;

        int slot = CANDIDATE_START;
        int shown = 0;
        for (String ref : allRefs(player)) {
            if (slot > CANDIDATE_END) {
                break; // 36件を超える対象は表示しきれない(MVPの割り切り。通常この件数には達しない)。
            }
            ItemStack real = readRef(player, ref);
            if (!isCandidate(real, effect)) {
                continue;
            }
            boolean pending = ref.equals(pendingRef);
            inventory.setItem(slot, candidateButton(real, effect, ref, pending));
            slot++;
            shown++;
        }

        inventory.setItem(HEADER_SLOT, header(effect, shown));
        player.openInventory(inventory);
    }

    private static boolean isCandidate(ItemStack real, EquipmentTicketEffect effect) {
        if (real == null || real.getType().isAir() || !real.hasItemMeta()) {
            return false;
        }
        // スタック2個以上は複製/データ喪失になるので候補にすら出さない
        // (ArsPaper ThreadApplicationPolicy#isStackTooLargeToSocket と同じ考え方)。
        if (real.getAmount() != 1) {
            return false;
        }
        return effect.eligible(real);
    }

    private ItemStack header(EquipmentTicketEffect effect, int candidateCount) {
        ItemStack stack = new ItemStack(Material.BOOK);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(effect.title(), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (candidateCount == 0) {
            lore.add(Component.text("対象になる装備がありません。", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("対象: " + candidateCount + "件", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("クリックして選択し、もう一度クリックして確定してください。",
                            NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack candidateButton(ItemStack real, EquipmentTicketEffect effect, String ref, boolean pending) {
        ItemStack button = real.clone();
        ItemMeta meta = button.getItemMeta();
        List<Component> lore = new ArrayList<>();
        if (meta.hasLore() && meta.lore() != null) {
            lore.addAll(meta.lore());
        }
        lore.add(Component.text("────────────────", NamedTextColor.DARK_GRAY));
        lore.addAll(effect.previewLore(real));
        lore.add(Component.text(pending ? "もう一度クリックして確定" : "クリックして選択",
                        pending ? NamedTextColor.YELLOW : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(targetRefKey, PersistentDataType.STRING, ref);
        button.setItemMeta(meta);
        return button;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Session session)) {
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
        String ref = clicked.getItemMeta().getPersistentDataContainer()
                .get(targetRefKey, PersistentDataType.STRING);
        if (ref == null) {
            return; // ヘッダなど非対象アイテム
        }

        if (!ref.equals(session.pendingRef)) {
            // 1クリック目: pending にするだけ。券は消費しない。
            ItemStack real = readRef(player, ref);
            if (!isCandidate(real, session.effect)) {
                player.sendMessage(Component.text("その対象はもう選べません。", NamedTextColor.RED));
                render(player, session.effect, null, null);
                return;
            }
            render(player, session.effect, ref, TargetSnapshot.of(real));
            return;
        }

        // 2クリック目(確定): 対象の同一性を再確認する。
        ItemStack current = readRef(player, ref);
        if (!isCandidate(current, session.effect)
                || !Objects.equals(session.pendingSnapshot, TargetSnapshot.of(current))) {
            player.sendMessage(Component.text(
                    "対象が変わったため確定できません。選び直してください。", NamedTextColor.RED));
            render(player, session.effect, null, null);
            return;
        }

        // 券がまだメインハンドにあるかを確定直前に再確認する。無ければ無償適用にしない。
        ItemStack heldTicket = player.getInventory().getItemInMainHand();
        if (heldTicket == null || heldTicket.getType().isAir() || heldTicket.getAmount() < 1
                || !session.effect.catalogId().equals(catalogIdOf(heldTicket))) {
            player.sendMessage(Component.text("券が見つかりません。", NamedTextColor.RED));
            player.closeInventory();
            return;
        }

        Optional<ItemStack> applied = session.effect.apply(current.clone());
        if (applied.isEmpty()) {
            player.sendMessage(Component.text("効果を適用できませんでした。", NamedTextColor.RED));
            render(player, session.effect, null, null);
            return;
        }
        writeRef(player, ref, applied.get());
        consumeMainHandTicket(player);
        player.sendMessage(Component.text(session.effect.appliedMessage(), NamedTextColor.GREEN));
        player.closeInventory();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Session) {
            event.setCancelled(true);
        }
    }

    private static void consumeMainHandTicket(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            return;
        }
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItemInMainHand(held.getAmount() <= 0 ? null : held);
    }

    private static String catalogIdOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return ItemData.of(stack.getItemMeta()).catalogId().orElse(null);
    }

    /** 対象候補の参照文字列一覧(防具4部位+両手+収納36枠)。 */
    private static List<String> allRefs(Player player) {
        List<String> refs = new ArrayList<>(EQUIPMENT_REFS);
        int size = player.getInventory().getSize();
        for (int i = 0; i < size; i++) {
            refs.add("st:" + i);
        }
        return refs;
    }

    private static ItemStack readRef(Player player, String ref) {
        PlayerInventory inventory = player.getInventory();
        if (ref.startsWith("eq:")) {
            return itemAt(inventory, EquipmentSlot.valueOf(ref.substring(3)));
        }
        return inventory.getItem(Integer.parseInt(ref.substring(3)));
    }

    private static void writeRef(Player player, String ref, ItemStack stack) {
        PlayerInventory inventory = player.getInventory();
        if (ref.startsWith("eq:")) {
            setItemAt(inventory, EquipmentSlot.valueOf(ref.substring(3)), stack);
            return;
        }
        inventory.setItem(Integer.parseInt(ref.substring(3)), stack);
    }

    /**
     * {@code PlayerInventory#getItem(EquipmentSlot)} の統合APIを使わず個別 getter を経由する
     * ({@code EquipmentDurabilityService#itemAt} と同じ理由: MockBukkit のスロット統合APIの
     * 実装差でテストが黙って通り抜けるのを避ける)。
     */
    private static ItemStack itemAt(PlayerInventory inventory, EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> inventory.getHelmet();
            case CHEST -> inventory.getChestplate();
            case LEGS -> inventory.getLeggings();
            case FEET -> inventory.getBoots();
            case HAND -> inventory.getItemInMainHand();
            case OFF_HAND -> inventory.getItemInOffHand();
            default -> null;
        };
    }

    private static void setItemAt(PlayerInventory inventory, EquipmentSlot slot, ItemStack item) {
        switch (slot) {
            case HEAD -> inventory.setHelmet(item);
            case CHEST -> inventory.setChestplate(item);
            case LEGS -> inventory.setLeggings(item);
            case FEET -> inventory.setBoots(item);
            case HAND -> inventory.setItemInMainHand(item);
            case OFF_HAND -> inventory.setItemInOffHand(item);
            default -> {
                // 人間が持たないスロットは対象外。
            }
        }
    }

    /** pending にした瞬間のスナップショット(確定時の同一性再確認用)。GUIインスタンスのメモリにのみ存在しPDCには書かない。 */
    private record TargetSnapshot(Material material, String catalogId, Long rollSeed, int quality, int amount) {
        static TargetSnapshot of(ItemStack stack) {
            ItemMeta meta = stack.getItemMeta();
            ItemData data = ItemData.of(meta);
            return new TargetSnapshot(stack.getType(), data.catalogId().orElse(null),
                    data.rollSeed().orElse(null), data.quality(), stack.getAmount());
        }
    }

    private static final class Session implements InventoryHolder {
        private final EquipmentTicketEffect effect;
        private final String pendingRef;
        private final TargetSnapshot pendingSnapshot;
        private Inventory inventory;

        private Session(EquipmentTicketEffect effect, String pendingRef, TargetSnapshot pendingSnapshot) {
            this.effect = effect;
            this.pendingRef = pendingRef;
            this.pendingSnapshot = pendingSnapshot;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
