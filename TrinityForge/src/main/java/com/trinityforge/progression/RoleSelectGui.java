package com.trinityforge.progression;

import com.trinityforge.config.domains.RoleBuffsConfig.CombatRoleSpec;
import com.trinityforge.config.domains.RoleBuffsConfig.SupportRoleSpec;
import com.trinityforge.pdc.ItemData;
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
import java.util.Optional;

/**
 * ロール選択GUI(2026-07-28)。開く入口は {@code /tf status} のロールアイコンと
 * 転職の証({@code role_reselect_ticket})の右クリックの2つ
 * (2026-08-05 の W-28 で {@code /tf role set} を廃止した)。
 *
 * <p>アイテム1個=1ロールで、表示名に職業名(label)、lore に効果({@link RoleDescriptions})を出す。
 * 可否判定は {@link RoleChangeService} 一本なので、待ち時間・交戦中ガード・
 * 「変更は券のみ」({@code allow-change: false})はどの入口から開いても同じように効く。
 *
 * <p><b>開くこと自体はゲートで塞がない</b>(2026-07-31)。ここはロールの説明を読む唯一の画面なので、
 * 交戦中ガードを有効にしている運用では「開けたのにクリックで拒否される」経路が生まれる。
 * その手戻りを、ヘッダとボタンの lore に拒否理由を出すことで吸収している。
 *
 * <p>クリックは常にキャンセルし、ドラッグも {@link SettingsGui} と同様に全面禁止する
 * (GUIへ実アイテムを置けてしまうと消失事故になるため)。
 *
 * <p><b>券モード(2026-08-04, {@code role_reselect_ticket})。</b>
 * {@link #open(Player, boolean)} に {@code ticketMode=true} を渡すと、実際に付け替える瞬間の
 * ゲート(クールダウン・交戦中ガード)を無視できる — ただし「メインハンドに券がまだ残っているか」を
 * 確定直前に再確認できた場合のみ。GUI を開いた後に券をドロップ/収納された場合は通常どおりゲートが効く
 * (無償バイパスに化けさせないため)。券は<b>実際にロールが確定した瞬間にだけ</b>1個消費する
 * (GUIを閉じただけ・同じロールを選び直しただけでは減らない — 上の「選択中」再クリックの早期returnが
 * これを保証する)。
 *
 * <p>券で変更した場合も<b>通常のクールダウンは開始する</b>({@link RoleChangeService#setCombat}/
 * {@link RoleChangeService#setSupport} の刻印ロジックはそのまま経由する。バイパスするのは
 * 「今変更できるか」の判定だけ)。ここを免除すると、券を連打して無限に付け替えられてしまい、
 * 交戦中のロールスイッチが事実上成立してしまうため(要件で明示的に確定した設計判断)。
 */
public final class RoleSelectGui implements Listener {

    /** ロール付け直し券({@code items/catalog.yml})のカタログID。{@link RoleTicketItemListener}等が参照する。 */
    public static final String TICKET_CATALOG_ID = "role_reselect_ticket";

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
        open(player, false);
    }

    /**
     * @param ticketMode {@code true} なら {@code role_reselect_ticket} 経由で開いたことを示す
     *                   ({@link #onClick} が確定時のゲートをバイパスし、確定後に券を1個消費する)。
     */
    public void open(Player player, boolean ticketMode) {
        Session session = new Session(ticketMode);
        Inventory inventory = Bukkit.createInventory(session, SIZE, Component.text("ロール選択"));
        session.inventory = inventory;

        PlayerData data = PlayerData.of(player);
        String currentCombat = data.rolePrimary().orElse(null);
        String currentSupport = data.roleSupport().orElse(null);

        // 拒否理由はヘッダとボタンの両方に出す。押してから初めて拒否されるより、開いた時点で
        // 「今は変えられない」と分かるほうが手戻りが少ない(2026-07-31)。
        // 表示に使うのは実際に押したときと同じ denyReasonForCombat/Support(2026-08-05, W-28) —
        // 待ち時間・交戦中ガード・「券が必要」を別々に組み立てると、表示と実挙動が食い違う
        // (「表示は待てと言うのに押したら通った」/その逆)。
        // 券モードでは「実際に押した瞬間に券があればゲートを無視する」ので何も出さない。
        String combatNotice = ticketMode ? null : roleChangeService.denyReasonForCombat(player).orElse(null);
        String supportNotice = ticketMode ? null : roleChangeService.denyReasonForSupport(player).orElse(null);

        inventory.setItem(COMBAT_HEADER_SLOT, header("戦闘職", Material.NETHERITE_SWORD,
                currentCombat, roleChangeService.config().combatRole(currentCombat) == null
                        ? null : roleChangeService.config().combatRole(currentCombat).label(),
                combatNotice, ticketMode));
        List<CombatRoleSpec> combatRoles = new ArrayList<>(roleChangeService.config().combatRoles().values());
        for (int i = 0; i < combatRoles.size() && i < MAX_PER_ROW; i++) {
            CombatRoleSpec spec = combatRoles.get(i);
            inventory.setItem(COMBAT_ROW_START + i,
                    combatButton(spec, spec.id().equals(currentCombat), combatNotice));
        }

        inventory.setItem(SUPPORT_HEADER_SLOT, header("補助職", Material.ENCHANTED_BOOK,
                currentSupport, roleChangeService.config().supportRole(currentSupport) == null
                        ? null : roleChangeService.config().supportRole(currentSupport).label(),
                supportNotice, ticketMode));
        List<SupportRoleSpec> supportRoles = new ArrayList<>(roleChangeService.config().supportRoles().values());
        for (int i = 0; i < supportRoles.size() && i < MAX_PER_ROW; i++) {
            SupportRoleSpec spec = supportRoles.get(i);
            inventory.setItem(SUPPORT_ROW_START + i,
                    supportButton(spec, spec.id().equals(currentSupport), supportNotice));
        }

        inventory.setItem(CLEAR_SLOT, clearButton());
        player.openInventory(inventory);
    }

    private ItemStack header(String title, Material icon, String currentId, String currentLabel,
                             String notice, boolean ticketMode) {
        ItemStack stack = new ItemStack(icon);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(title, NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        // label は MiniMessage 可なので、文字列連結に混ぜる前にタグを落とす。
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("現在: "
                        + (currentId == null ? "(なし)"
                                : (currentLabel == null ? currentId : MiniText.plain(currentLabel))),
                        NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (notice != null) {
            lore.add(Component.text(notice, NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (ticketMode) {
            lore.add(Component.text("付け替え券を使用中: クールダウン・交戦中制限を無視できます",
                            NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack combatButton(CombatRoleSpec spec, boolean selected, String notice) {
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
                combatKey, spec.id(), notice);
    }

    private ItemStack supportButton(SupportRoleSpec spec, boolean selected, String notice) {
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
                supportKey, spec.id(), notice);
    }

    private ItemStack roleButton(String label, String iconName, Material fallbackIcon,
                                 List<Component> lore, boolean selected,
                                 NamespacedKey key, String roleId, String notice) {
        ItemStack stack = new ItemStack(resolveIcon(iconName, fallbackIcon));
        ItemMeta meta = stack.getItemMeta();
        // label は MiniMessage 可。色を書いていない label だけ、選択状態の色(緑/白)を当てる。
        meta.displayName(Component.text(selected ? "▶ " : "",
                        selected ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)
                .append(MiniText.render(label, selected ? NamedTextColor.GREEN : NamedTextColor.WHITE)));
        List<Component> full = new ArrayList<>(lore);
        full.add(Component.empty());
        if (selected) {
            full.add(Component.text("選択中", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
        } else if (notice != null) {
            full.add(Component.text(notice, NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            full.add(Component.text("クリックで選択", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
        }
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
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("戦闘職・補助職の両方を外します", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (roleChangeService.config().roleChangeCooldownMillis() > 0L) {
            // 解除でもクールダウンは刻む(刻まないと「解除→即再選択」が迂回路になる)。
            // 押す前に分かるようにここへ書く。
            lore.add(Component.text("解除しても変更の待ち時間は発生します", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
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
        var pdc = clicked.getItemMeta().getPersistentDataContainer();
        String combatId = pdc.get(combatKey, PersistentDataType.STRING);
        String supportId = pdc.get(supportKey, PersistentDataType.STRING);
        boolean clear = pdc.has(clearKey, PersistentDataType.BYTE);
        if (combatId == null && supportId == null && !clear) {
            return;
        }
        // 「選択中」をもう一度押しただけなら何も起きないので、待ち時間の警告も出さない
        // (= 券モードでもここで return するので、選び直しただけでは券が減らない)。
        PlayerData current = PlayerData.of(player);
        if ((combatId != null && current.rolePrimary().filter(combatId::equals).isPresent())
                || (supportId != null && current.roleSupport().filter(supportId::equals).isPresent())) {
            return;
        }
        // 券モード(clear以外)は、メインハンドに券がまだ残っている場合に限りゲートを無視する。
        // GUIを開いた後に券が無くなっていた場合は通常どおりのゲートへフォールバックする
        // (無償バイパスに化けさせないため)。
        boolean ticketBypass = session.ticketMode && !clear && heldTicketPresent(player);
        // 引数版コマンドと同じゲート。拒否理由はチャットへ出し、GUIは開いたままにする。
        // クールダウンは枠ごとなので、押したボタンに対応する枠のゲートを見る
        // (共通ゲートだけを見ると、戦闘職の待ち時間で補助職まで押せなくなる)。
        // 解除だけは /tf role clear と同じく交戦中ガードを通さない(外すだけなので塞ぐ理由が無い)。
        var deny = clear ? roleChangeService.changeDisabledReason(player)
                : ticketBypass ? Optional.<String>empty()
                : combatId != null ? roleChangeService.denyReasonForCombat(player)
                : roleChangeService.denyReasonForSupport(player);
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
            if (ticketBypass) {
                consumeMainHandTicket(player);
            }
        } else {
            if (!roleChangeService.setSupport(player, supportId)) {
                player.sendMessage(Component.text("未知の補助職: " + supportId, NamedTextColor.RED));
                return;
            }
            player.sendMessage(Component.text("補助職を設定しました: "
                    + labelOfSupport(supportId), NamedTextColor.GREEN));
            if (ticketBypass) {
                consumeMainHandTicket(player);
            }
        }
        open(player, session.ticketMode);
    }

    /** メインハンドが {@link #TICKET_CATALOG_ID} の券(1個以上)かどうか。 */
    private static boolean heldTicketPresent(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir() || held.getAmount() < 1 || !held.hasItemMeta()) {
            return false;
        }
        return TICKET_CATALOG_ID.equals(ItemData.of(held.getItemMeta()).catalogId().orElse(null));
    }

    /** メインハンドの券を1個消費する({@code NativeSkillTreeMenu#consumeHeldItem} と同じ作法)。 */
    private static void consumeMainHandTicket(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            return;
        }
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItemInMainHand(held.getAmount() <= 0 ? null : held);
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
        private final boolean ticketMode;
        private Inventory inventory;

        private Session(boolean ticketMode) {
            this.ticketMode = ticketMode;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
