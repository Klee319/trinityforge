package com.trinityforge.mail;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code /tf mail} — 受信箱（2026-08-19 / W-155）。未受取のメールを一覧し、クリックで受け取る。
 *
 * <p><b>枠を数えてから受け取る。</b> 添付の個数ぶんの空き枠が無いまま受け取ると、入り切らないぶんが
 * 足元へ落ちる（消えはしないが、溶岩の上などでは事故になる）。先に数えて止めるほうが親切なので、
 * 空きが足りないときは受け取り自体を始めない。
 *
 * <p><b>プレイヤーのインベントリは触らせない。</b> このGUIはクリックを全部キャンセルし、
 * 受け取りだけを {@link MailService} 経由で行う。誤ってGUIへ物を置ける実装にすると、
 * 閉じた瞬間に消える（受信箱GUIは表示専用で、中身をどこにも保存していないため）。
 */
public final class MailInboxGui implements Listener {

    private static final int SIZE = 54;
    private static final int MAILS_PER_PAGE = 45;
    private static final int PREV_SLOT = 45;
    private static final int INFO_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final MailService service;
    private final NamespacedKey mailIdKey;
    private final NamespacedKey attachmentCountKey;
    private final NamespacedKey pageKey;

    public MailInboxGui(Plugin plugin, MailService service) {
        Objects.requireNonNull(plugin, "plugin");
        this.service = Objects.requireNonNull(service, "service");
        this.mailIdKey = new NamespacedKey(plugin, "mail_inbox_id");
        this.attachmentCountKey = new NamespacedKey(plugin, "mail_inbox_attachments");
        this.pageKey = new NamespacedKey(plugin, "mail_inbox_page");
    }

    /** 受信箱を開く（読み込みは非同期なので、開くのは読み終わってから）。 */
    public void open(Player player) {
        open(player, 0);
    }

    public void open(Player player, int page) {
        service.loadInbox(player.getUniqueId(), mails -> {
            if (!player.isOnline()) {
                return;
            }
            render(player, mails, Math.max(0, page));
        });
    }

    private void render(Player player, List<MailMessage> mails, int page) {
        int pages = Math.max(1, (mails.size() + MAILS_PER_PAGE - 1) / MAILS_PER_PAGE);
        int shown = Math.min(page, pages - 1);
        Inventory inventory = Bukkit.createInventory(new Holder(), SIZE,
                Component.text("メール受信箱 (" + mails.size() + "通)", NamedTextColor.DARK_AQUA));
        int from = shown * MAILS_PER_PAGE;
        for (int i = 0; i < MAILS_PER_PAGE && from + i < mails.size(); i++) {
            inventory.setItem(i, icon(mails.get(from + i)));
        }
        if (shown > 0) {
            inventory.setItem(PREV_SLOT, navButton(Material.ARROW, "前のページ", shown - 1));
        }
        if (shown < pages - 1) {
            inventory.setItem(NEXT_SLOT, navButton(Material.ARROW, "次のページ", shown + 1));
        }
        inventory.setItem(INFO_SLOT, info(mails.isEmpty(), shown + 1, pages));
        player.openInventory(inventory);
    }

    private ItemStack icon(MailMessage mail) {
        ItemStack item = new ItemStack(mail.hasAttachments() ? Material.CHEST : Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(mail.subject().isBlank() ? "(件名なし)" : mail.subject(),
                NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("送信者: " + mail.senderName(), NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("送信日時: " + STAMP.format(Instant.ofEpochMilli(mail.createdAtMillis())),
                NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        if (mail.expiresAtMillis() > 0L) {
            lore.add(Component.text("受取期限: " + STAMP.format(Instant.ofEpochMilli(mail.expiresAtMillis())),
                    NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        for (String line : mail.body().split("\n")) {
            if (line.isBlank()) {
                lore.add(Component.empty());
                continue;
            }
            lore.add(safeBody(line).decoration(TextDecoration.ITALIC, false));
        }
        if (mail.hasAttachments()) {
            lore.add(Component.empty());
            lore.add(Component.text("添付: " + mail.attachments().size() + " 個", NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("クリックで受け取る", NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(mailIdKey, PersistentDataType.LONG, mail.id());
        // 空き枠の判定に使う。lore の文字列から数え直すと、表記を変えた瞬間に静かに壊れる。
        meta.getPersistentDataContainer().set(attachmentCountKey, PersistentDataType.INTEGER,
                mail.attachments().size());
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 本文は MiniMessage として描くが、<b>壊れた記法でGUIごと開けなくならない</b>ようにする。
     * 本文は運営が手で打つので、閉じ忘れたタグは普通に混ざる。
     */
    private static Component safeBody(String line) {
        try {
            return MINI_MESSAGE.deserialize(line);
        } catch (RuntimeException broken) {
            return Component.text(line, NamedTextColor.WHITE);
        }
    }

    private ItemStack navButton(Material material, String label, int targetPage) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(pageKey, PersistentDataType.INTEGER, targetPage);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack info(boolean empty, int page, int pages) {
        ItemStack item = new ItemStack(empty ? Material.GRAY_STAINED_GLASS_PANE : Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(empty ? "未受取のメールはありません" : "ページ " + page + "/" + pages,
                NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)) {
            return;
        }
        // 表示専用。プレイヤー側の枠を含めて一切操作させない（置いた物は保存されず消えるため）。
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) {
            return;
        }
        var pdc = clicked.getItemMeta().getPersistentDataContainer();
        Integer targetPage = pdc.get(pageKey, PersistentDataType.INTEGER);
        if (targetPage != null) {
            open(player, targetPage);
            return;
        }
        Long mailId = pdc.get(mailIdKey, PersistentDataType.LONG);
        if (mailId == null) {
            return;
        }
        Integer attachments = pdc.get(attachmentCountKey, PersistentDataType.INTEGER);
        int needed = attachments == null ? 0 : attachments;
        if (needed > 0 && freeSlots(player) < needed) {
            player.sendMessage(Component.text("インベントリの空きが足りません（" + needed
                    + " 枠必要）。整理してから受け取ってください。", NamedTextColor.RED));
            return;
        }
        player.closeInventory();
        service.claim(player, mailId, ok -> {
            if (ok && player.isOnline()) {
                open(player);
            }
        });
    }

    private static int freeSlots(Player player) {
        int free = 0;
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) {
                free++;
            }
        }
        return free;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
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
