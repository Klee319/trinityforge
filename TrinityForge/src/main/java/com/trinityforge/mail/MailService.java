package com.trinityforge.mail;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * メールの送受信（2026-08-19 / W-155）。DBアクセスは必ず<b>非同期</b>、アイテムの受け渡しは必ず
 * <b>メインスレッド</b>で行うための境界そのものがこのクラスの役割。
 *
 * <p><b>受け取りの手順を崩さないこと。</b>
 * <ol>
 *   <li>（メイン）空き枠を数える ── 足りなければ受け取り自体を始めない</li>
 *   <li>（非同期）{@link MailStore#claim} を1文で撃つ。true が返った者だけが受け取り手</li>
 *   <li>（メイン）添付を渡す。渡せない状況（既に退出）なら {@link MailStore#unclaim} で戻す</li>
 * </ol>
 * この順番を崩して「先に渡してから受取済みにする」と、渡した直後にサーバが落ちた場合に
 * <b>もう一度受け取れて添付が複製</b>する。逆に「受取済みにしてから渡す」だけで取り消しを
 * 用意しないと、退出と重なったときに<b>お詫びの品が消える</b>。
 */
public final class MailService implements Listener {

    /** 既定の有効期限（日）。運営が期限を意識せず送れるように、送信側の既定値として使う。 */
    public static final int DEFAULT_EXPIRY_DAYS = 30;
    /** 受取済みのメールを残しておく日数（これを過ぎたら掃除で消す）。 */
    private static final long CLAIMED_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    /** 失効したメールを残しておく日数（同上）。 */
    private static final long EXPIRED_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    /** 一斉送信の宛先上限。桁を間違えた指定でDBを埋めないための安全弁。 */
    private static final int MAX_BROADCAST_RECIPIENTS = 5000;

    private final Plugin plugin;
    private final MailStore store;
    /** 全プレイヤーの列挙元（進行DB）。一斉送信の宛先を「実在するプレイヤー」に限るために使う。 */
    private final Supplier<Collection<UUID>> knownPlayerIds;

    public MailService(Plugin plugin, MailStore store, Supplier<Collection<UUID>> knownPlayerIds) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.store = Objects.requireNonNull(store, "store");
        this.knownPlayerIds = Objects.requireNonNull(knownPlayerIds, "knownPlayerIds");
    }

    /**
     * 一斉送信の宛先。<b>送信時点で存在するプレイヤー</b>（進行DBに記録がある者＋現在オンライン）
     * に限る（2026-08-19 ユーザー確定）。送信後に初参加した人には届かない。
     */
    public Set<UUID> broadcastRecipients() {
        Set<UUID> ids = new LinkedHashSet<>();
        try {
            Collection<UUID> known = knownPlayerIds.get();
            if (known != null) {
                ids.addAll(known);
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "[mail] 宛先一覧の取得に失敗しました", ex);
        }
        // 進行DBに1行も無い（＝まだ何も稼いでいない）新規プレイヤーも、今いるなら宛先に含める。
        for (Player online : Bukkit.getOnlinePlayers()) {
            ids.add(online.getUniqueId());
        }
        return ids;
    }

    /**
     * 送信（非同期）。
     *
     * @param recipients  宛先。空なら何もしない
     * @param onDone      送信後にメインスレッドで呼ばれる（引数＝実際に入った通数。失敗は -1）
     */
    public void send(Collection<UUID> recipients, String senderName, String subject, String body,
                     List<ItemStack> attachments, Consumer<Integer> onDone) {
        if (recipients == null || recipients.isEmpty()) {
            if (onDone != null) {
                onDone.accept(0);
            }
            return;
        }
        long now = System.currentTimeMillis();
        long expiresAt = now + DEFAULT_EXPIRY_DAYS * 24L * 60 * 60 * 1000;
        // 添付は送信者のインベントリから取り上げた実体。ここで複製を作らないとGUI側の後始末と
        // 参照を共有してしまうので、必ず clone しておく。
        List<ItemStack> payload = new ArrayList<>();
        if (attachments != null) {
            for (ItemStack item : attachments) {
                if (item != null && !item.getType().isAir()) {
                    payload.add(item.clone());
                }
            }
        }
        List<MailMessage> messages = new ArrayList<>();
        int count = 0;
        for (UUID recipient : recipients) {
            if (recipient == null) {
                continue;
            }
            if (++count > MAX_BROADCAST_RECIPIENTS) {
                plugin.getLogger().warning("[mail] 宛先が " + MAX_BROADCAST_RECIPIENTS
                        + " 件を超えたため打ち切りました。");
                break;
            }
            messages.add(MailMessage.outgoing(recipient, senderName, subject, body, payload, now, expiresAt));
        }
        runAsync(() -> {
            int inserted;
            try {
                inserted = store.insertAll(messages);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "[mail] 送信に失敗しました", ex);
                inserted = -1;
            }
            int result = inserted;
            runSync(() -> {
                if (result > 0) {
                    notifyOnlineRecipients(messages);
                }
                if (onDone != null) {
                    onDone.accept(result);
                }
            });
        });
    }

    /** 受信箱を読む（非同期 → メインスレッドで {@code onLoaded}）。 */
    public void loadInbox(UUID playerId, Consumer<List<MailMessage>> onLoaded) {
        Objects.requireNonNull(onLoaded, "onLoaded");
        runAsync(() -> {
            List<MailMessage> mails;
            try {
                mails = store.inbox(playerId, System.currentTimeMillis());
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "[mail] 受信箱の読み込みに失敗しました", ex);
                mails = List.of();
            }
            List<MailMessage> result = mails;
            runSync(() -> onLoaded.accept(result));
        });
    }

    /**
     * 1通を受け取る。手順はクラス javadoc のとおり（空き枠 → claim → 受け渡し）。
     *
     * @param onFinished 受け取り後にメインスレッドで呼ばれる（true＝受け取れた）
     */
    public void claim(Player player, long mailId, Consumer<Boolean> onFinished) {
        Objects.requireNonNull(player, "player");
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        runAsync(() -> {
            boolean won;
            MailMessage mail = null;
            try {
                won = store.claim(mailId, playerId, now);
                if (won) {
                    mail = store.find(mailId);
                }
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "[mail] 受け取りに失敗しました", ex);
                won = false;
            }
            boolean claimed = won;
            MailMessage delivered = mail;
            runSync(() -> {
                if (!claimed || delivered == null) {
                    player.sendMessage(Component.text("そのメールは既に受け取り済みか、期限切れです。",
                            NamedTextColor.YELLOW));
                    if (onFinished != null) {
                        onFinished.accept(false);
                    }
                    return;
                }
                if (!player.isOnline()) {
                    // 受け取ったことにしたのに渡せない。未受取へ戻す（お詫びの品を消さない）。
                    runAsync(() -> {
                        try {
                            store.unclaim(mailId, now);
                        } catch (SQLException ex) {
                            plugin.getLogger().log(Level.SEVERE,
                                    "[mail] 受け取りの取り消しに失敗しました(mail id=" + mailId + ")", ex);
                        }
                    });
                    return;
                }
                deliver(player, delivered);
                if (onFinished != null) {
                    onFinished.accept(true);
                }
            });
        });
    }

    /**
     * 添付を渡す。<b>入り切らないぶんは足元へ落とす</b>。
     * ここで捨てると、受取済みになっているので二度と戻せない。
     */
    private void deliver(Player player, MailMessage mail) {
        player.sendMessage(Component.text("メールを受け取りました: ", NamedTextColor.GOLD)
                .append(Component.text(mail.subject(), NamedTextColor.YELLOW)));
        if (!mail.hasAttachments()) {
            return;
        }
        Map<Integer, ItemStack> overflow = new HashMap<>();
        for (ItemStack item : mail.attachments()) {
            overflow.putAll(player.getInventory().addItem(item.clone()));
        }
        if (overflow.isEmpty()) {
            return;
        }
        for (ItemStack left : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
        player.sendMessage(Component.text("インベントリに入り切らなかったぶんを足元へ落としました。",
                NamedTextColor.YELLOW));
    }

    /** 未受取の通数を数える（非同期 → メインスレッドで {@code onCounted}）。 */
    public void countUnclaimed(UUID playerId, Consumer<Integer> onCounted) {
        runAsync(() -> {
            int count;
            try {
                count = store.countUnclaimed(playerId, System.currentTimeMillis());
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "[mail] 未受取件数の取得に失敗しました", ex);
                count = 0;
            }
            int result = count;
            runSync(() -> onCounted.accept(result));
        });
    }

    /** 参加時の通知。未受取があるときだけ、クリックで開けるメッセージを出す。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        countUnclaimed(player.getUniqueId(), count -> {
            if (count <= 0 || !player.isOnline()) {
                return;
            }
            player.sendMessage(Component.text("未受取のメールが " + count + " 通あります。",
                            NamedTextColor.GOLD)
                    .append(Component.text(" [受け取る]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/tf mail"))));
        });
    }

    /** 送信直後、宛先がオンラインなら即座に知らせる（次のログインまで気づかないのを避ける）。 */
    private void notifyOnlineRecipients(Collection<MailMessage> messages) {
        for (MailMessage message : messages) {
            Player online = Bukkit.getPlayer(message.recipientId());
            if (online == null) {
                continue;
            }
            online.sendMessage(Component.text("新しいメールが届きました: ", NamedTextColor.GOLD)
                    .append(Component.text(message.subject(), NamedTextColor.YELLOW))
                    .append(Component.text(" [受け取る]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/tf mail"))));
        }
    }

    /** 古い受取済み／失効メールの掃除（起動時に1回）。 */
    public void purgeOldMailAsync() {
        long now = System.currentTimeMillis();
        runAsync(() -> {
            try {
                int removed = store.purge(now - CLAIMED_RETENTION_MILLIS, now - EXPIRED_RETENTION_MILLIS);
                if (removed > 0) {
                    plugin.getLogger().info("[mail] 古いメールを " + removed + " 通削除しました。");
                }
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "[mail] 古いメールの削除に失敗しました", ex);
            }
        });
    }

    private void runAsync(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    private void runSync(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }
}
