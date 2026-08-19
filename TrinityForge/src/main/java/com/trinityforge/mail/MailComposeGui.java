package com.trinityforge.mail;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /tf mail compose} — 運営がメールを書いて送るGUI（2026-08-19 / W-155）。
 *
 * <p>用途はユーザーの依頼どおり2つ。<b>お詫び報酬を全鯖民へメッセージ付きで一斉送信</b>と、
 * <b>個別に課金報酬などの特殊アイテムを送信</b>。添付は上の5段へ<b>実物を置く</b>方式にした
 * ── カタログIDを打たせる方式だと、品質やPDCの乗った現物（＝実際に配りたい物）を指定できない。
 *
 * <p><b>添付を絶対に消さない。</b> 置いた実物を預かる以上、この画面の失敗は「運営のアイテムが
 * 消える」に直結する。そのため
 * <ul>
 *   <li>送信せずに閉じたら<b>必ず送信者へ返す</b>（入り切らないぶんは足元へ落とす）</li>
 *   <li>入力待ちで一時的に閉じるときは返さず<b>下書きに預ける</b>（戻ってきたら復元する）</li>
 *   <li>入力待ちのまま退出したら、その時点で<b>返す</b>（下書きを抱えたまま消さない）</li>
 * </ul>
 */
public final class MailComposeGui implements Listener {

    private static final int SIZE = 54;
    /** 添付を置ける枠（0〜44）。ここだけは自由に操作させる。 */
    private static final int ATTACHMENT_SLOTS = 45;
    private static final int SLOT_RECIPIENT_MODE = 45;
    private static final int SLOT_TARGET = 46;
    private static final int SLOT_SUBJECT = 48;
    private static final int SLOT_BODY = 50;
    private static final int SLOT_SEND = 52;
    private static final int SLOT_CANCEL = 53;

    private static final String CANCEL_TOKEN = "キャンセル";

    private final Plugin plugin;
    private final MailService service;
    private final NamespacedKey actionKey;
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();
    /** 入力待ちの項目（{@code subject} / {@code body} / {@code target}）。 */
    private final Map<UUID, String> awaitingInput = new ConcurrentHashMap<>();
    /** 送信確定で閉じた回。{@link #onClose} が添付を返さないようにするための印。 */
    private final Set<UUID> sending = ConcurrentHashMap.newKeySet();

    public MailComposeGui(Plugin plugin, MailService service) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.service = Objects.requireNonNull(service, "service");
        this.actionKey = new NamespacedKey(plugin, "mail_compose_action");
    }

    /** 下書き。送信者ごとに1つ。 */
    private static final class Draft {
        private boolean toEveryone;
        private String targetName = "";
        private UUID targetId;
        private String subject = "";
        private String body = "";
        private final List<ItemStack> attachments = new ArrayList<>();
    }

    public void open(Player admin) {
        open(admin, drafts.computeIfAbsent(admin.getUniqueId(), id -> new Draft()));
    }

    /** 宛先を決め打ちで開く（{@code /tf mail compose <名前|all>}）。 */
    public void open(Player admin, String presetTarget) {
        Draft draft = drafts.computeIfAbsent(admin.getUniqueId(), id -> new Draft());
        if (presetTarget != null && !presetTarget.isBlank()) {
            if ("all".equalsIgnoreCase(presetTarget) || "@a".equals(presetTarget)) {
                draft.toEveryone = true;
                draft.targetName = "";
                draft.targetId = null;
            } else {
                applyTarget(admin, draft, presetTarget);
            }
        }
        open(admin, draft);
    }

    private void open(Player admin, Draft draft) {
        Inventory inventory = Bukkit.createInventory(new Holder(), SIZE,
                Component.text("メール作成", NamedTextColor.DARK_AQUA));
        for (int i = 0; i < draft.attachments.size() && i < ATTACHMENT_SLOTS; i++) {
            inventory.setItem(i, draft.attachments.get(i));
        }
        // 預けた実体はGUIへ移したので、下書き側からは外す（両方に残すと閉じたときに二重に返る）。
        draft.attachments.clear();
        inventory.setItem(SLOT_RECIPIENT_MODE, button(Material.BEACON, "宛先: "
                        + (draft.toEveryone ? "全員（送信時点で存在するプレイヤー）" : "個別"),
                "recipient_mode", List.of("クリックで切り替え")));
        inventory.setItem(SLOT_TARGET, button(Material.PLAYER_HEAD,
                "個別の宛先: " + (draft.targetName.isBlank() ? "(未設定)" : draft.targetName),
                "target", List.of("クリックしてチャットで名前を入力",
                        draft.toEveryone ? "※ 宛先が『全員』の間は使われません" : "")));
        inventory.setItem(SLOT_SUBJECT, button(Material.NAME_TAG,
                "件名: " + (draft.subject.isBlank() ? "(未設定)" : draft.subject),
                "subject", List.of("クリックしてチャットで入力")));
        inventory.setItem(SLOT_BODY, button(Material.WRITABLE_BOOK,
                "本文: " + (draft.body.isBlank() ? "(未設定)" : summarize(draft.body)),
                "body", List.of("クリックしてチャットで入力",
                        "「\\n」で改行、MiniMessage の色タグも使えます")));
        inventory.setItem(SLOT_SEND, button(Material.LIME_CONCRETE, "送信する", "send",
                List.of("上の枠に置いたアイテムを添付して送ります")));
        inventory.setItem(SLOT_CANCEL, button(Material.BARRIER, "破棄して閉じる", "cancel",
                List.of("置いたアイテムは返却されます")));
        admin.openInventory(inventory);
    }

    private static String summarize(String body) {
        String single = body.replace("\n", " ");
        return single.length() <= 24 ? single : single.substring(0, 24) + "…";
    }

    private ItemStack button(Material material, String label, String action, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            if (!line.isBlank()) {
                lines.add(Component.text(line, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
        }
        meta.lore(lines);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player admin)) {
            return;
        }
        // 添付枠(0〜44)への操作はそのまま通す。触ってよいのはここだけ。
        boolean inTopInventory = event.getRawSlot() >= 0 && event.getRawSlot() < SIZE;
        if (inTopInventory && event.getRawSlot() >= ATTACHMENT_SLOTS) {
            event.setCancelled(true);
            handleAction(admin, event.getCurrentItem());
            return;
        }
        if (!inTopInventory && event.isShiftClick()) {
            // 下からのシフトクリックは操作枠へ飛び込みうるので、素直に通さない。
            // 添付は「上の枠へ直接置く」運用に寄せる（誤って設定ボタンを潰すより分かりやすい）。
            event.setCancelled(true);
        }
    }

    private void handleAction(Player admin, ItemStack clicked) {
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        String action = clicked.getItemMeta().getPersistentDataContainer()
                .get(actionKey, PersistentDataType.STRING);
        if (action == null) {
            return;
        }
        Draft draft = drafts.computeIfAbsent(admin.getUniqueId(), id -> new Draft());
        switch (action) {
            case "recipient_mode" -> {
                draft.toEveryone = !draft.toEveryone;
                stash(admin, draft);
                open(admin, draft);
            }
            case "target", "subject", "body" -> promptFor(admin, draft, action);
            case "send" -> send(admin, draft);
            case "cancel" -> admin.closeInventory();
            default -> { }
        }
    }

    /** 今開いているGUIの添付を下書きへ預ける（開き直しても消えないように）。 */
    private void stash(Player admin, Draft draft) {
        Inventory top = admin.getOpenInventory().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) {
            return;
        }
        draft.attachments.clear();
        for (int i = 0; i < ATTACHMENT_SLOTS; i++) {
            ItemStack item = top.getItem(i);
            if (item != null && !item.getType().isAir()) {
                draft.attachments.add(item.clone());
            }
            top.setItem(i, null);
        }
    }

    private void promptFor(Player admin, Draft draft, String field) {
        stash(admin, draft);
        awaitingInput.put(admin.getUniqueId(), field);
        admin.closeInventory();
        String label = switch (field) {
            case "target" -> "宛先のプレイヤー名";
            case "subject" -> "件名";
            default -> "本文";
        };
        admin.sendMessage(Component.text(label + "をチャットに入力してください。", NamedTextColor.AQUA));
        admin.sendMessage(Component.text("　「" + CANCEL_TOKEN + "」と入力すると変更せずに戻ります。",
                NamedTextColor.GRAY));
    }

    /**
     * 入力待ちの発言を横取りする。{@link EventPriority#LOWEST} で受けてキャンセルするので、
     * 入力内容がチャット欄へ流れることはない。GUIの再表示は<b>メインスレッドへ戻してから</b>
     * 行う（チャットイベントは非同期）。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player admin = event.getPlayer();
        String field = awaitingInput.remove(admin.getUniqueId());
        if (field == null) {
            return;
        }
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!admin.isOnline()) {
                return;
            }
            Draft draft = drafts.computeIfAbsent(admin.getUniqueId(), id -> new Draft());
            if (!CANCEL_TOKEN.equals(input)) {
                switch (field) {
                    case "target" -> applyTarget(admin, draft, input);
                    case "subject" -> draft.subject = input;
                    default -> draft.body = input.replace("\\n", "\n");
                }
            }
            open(admin, draft);
        });
    }

    /**
     * 宛先の名前を UUID へ解決する。<b>オンライン → サーバが知っている名前</b>の順に見る。
     * 一度も来たことのない名前は解決しない（{@code getOfflinePlayer(String)} は未知の名前にも
     * UUID をでっち上げて返すので、それを宛先にすると<b>誰にも届かないメールが静かに積まれる</b>）。
     */
    private void applyTarget(Player admin, Draft draft, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            draft.targetId = online.getUniqueId();
            draft.targetName = online.getName();
            draft.toEveryone = false;
            return;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            draft.targetId = cached.getUniqueId();
            draft.targetName = cached.getName() == null ? name : cached.getName();
            draft.toEveryone = false;
            return;
        }
        admin.sendMessage(Component.text("そのプレイヤーは見つかりません: " + name
                + "（このサーバへ来たことがある名前だけ指定できます）", NamedTextColor.RED));
    }

    private void send(Player admin, Draft draft) {
        stash(admin, draft);
        if (draft.subject.isBlank()) {
            admin.sendMessage(Component.text("件名が未設定です。", NamedTextColor.RED));
            open(admin, draft);
            return;
        }
        Set<UUID> recipients = new LinkedHashSet<>();
        if (draft.toEveryone) {
            recipients.addAll(service.broadcastRecipients());
        } else if (draft.targetId != null) {
            recipients.add(draft.targetId);
        }
        if (recipients.isEmpty()) {
            admin.sendMessage(Component.text("宛先が未設定です（個別なら名前を、全員なら宛先を切り替えてください）。",
                    NamedTextColor.RED));
            open(admin, draft);
            return;
        }
        List<ItemStack> attachments = List.copyOf(draft.attachments);
        String subject = draft.subject;
        String body = draft.body;
        // 送信が確定したので、この下書きの添付はもう返さない。
        draft.attachments.clear();
        drafts.remove(admin.getUniqueId());
        sending.add(admin.getUniqueId());
        admin.closeInventory();
        service.send(recipients, admin.getName(), subject, body, attachments, inserted -> {
            sending.remove(admin.getUniqueId());
            if (!admin.isOnline()) {
                return;
            }
            if (inserted < 0) {
                admin.sendMessage(Component.text("送信に失敗しました。サーバログを確認してください。",
                        NamedTextColor.RED));
                // 失敗したぶんの添付は戻す（消えたと思われるのが一番まずい）。
                giveBack(admin, attachments);
                return;
            }
            admin.sendMessage(Component.text("メールを " + inserted + " 通送信しました。",
                    NamedTextColor.GREEN));
        });
    }

    /**
     * 送信せずに閉じた回。<b>置いてあった添付を必ず返す</b>。
     * 入力待ちで閉じた回（{@link #promptFor}）は下書きへ預けてあるので、ここでは何も残っていない。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player admin)) {
            return;
        }
        if (sending.remove(admin.getUniqueId())) {
            return; // 送信確定で閉じた。返さない。
        }
        List<ItemStack> left = new ArrayList<>();
        for (int i = 0; i < ATTACHMENT_SLOTS; i++) {
            ItemStack item = event.getInventory().getItem(i);
            if (item != null && !item.getType().isAir()) {
                left.add(item.clone());
                event.getInventory().setItem(i, null);
            }
        }
        if (awaitingInput.containsKey(admin.getUniqueId())) {
            // 入力待ちの一時的な閉じ。下書きへ預けたまま戻ってくるので返さない。
            Draft draft = drafts.computeIfAbsent(admin.getUniqueId(), id -> new Draft());
            draft.attachments.addAll(left);
            return;
        }
        Draft draft = drafts.remove(admin.getUniqueId());
        if (draft != null) {
            left.addAll(draft.attachments);
        }
        giveBack(admin, left);
    }

    /** 退出したら下書きを畳んで、預かっている添付を返す（オフラインなら足元へ落とす）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player admin = event.getPlayer();
        awaitingInput.remove(admin.getUniqueId());
        sending.remove(admin.getUniqueId());
        Draft draft = drafts.remove(admin.getUniqueId());
        if (draft != null && !draft.attachments.isEmpty()) {
            giveBack(admin, draft.attachments);
        }
    }

    private static void giveBack(Player admin, List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (ItemStack item : items) {
            Map<Integer, ItemStack> overflow = admin.getInventory().addItem(item.clone());
            for (ItemStack rest : overflow.values()) {
                admin.getWorld().dropItemNaturally(admin.getLocation(), rest);
            }
        }
    }

    /** このGUIを他のインベントリと見分けるためだけの標識。 */
    private static final class Holder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            throw new UnsupportedOperationException("marker holder");
        }
    }
}
